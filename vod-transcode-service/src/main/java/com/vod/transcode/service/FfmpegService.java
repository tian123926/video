package com.vod.transcode.service;

import com.vod.transcode.config.TranscodeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg / ffprobe 调用封装：执行、超时控制、输出收集。
 */
@Slf4j
@Service
public class FfmpegService {

    private final TranscodeProperties properties;

    public FfmpegService(TranscodeProperties properties) {
        this.properties = properties;
    }

    /**
     * ffprobe 读取时长（秒）。
     */
    public double probeDurationSeconds(Path input) {
        requireFile(input);
        List<String> command = Arrays.asList(
                properties.getFfprobePath(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1",
                input.toString());
        String output = execute(command, "ffprobe");
        for (String line : output.split("\\R")) {
            String value = line.trim();
            if (!value.isEmpty()) {
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("ffprobe 返回的时长无法解析: " + value, e);
                }
            }
        }
        throw new IllegalStateException("ffprobe 未返回视频时长: " + input);
    }

    /**
     * 生成 HLS（index.m3u8 + seg-xxxxx.ts）到输出目录。
     */
    public void transcodeToHls(Path input, Path outputDir) {
        requireFile(input);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建转码输出目录失败: " + outputDir, e);
        }
        Path playlist = outputDir.resolve("index.m3u8");
        List<String> command = Arrays.asList(
                properties.getFfmpegPath(),
                "-y",
                "-i", input.toString(),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-f", "hls",
                "-hls_time", "6",
                "-hls_playlist_type", "vod",
                "-hls_segment_filename", "seg-%05d.ts",
                "index.m3u8");
        // 以输出目录为工作目录，保证 m3u8 中记录的是相对分片名
        execute(command, "ffmpeg", outputDir);
        if (!Files.isRegularFile(playlist)) {
            throw new IllegalStateException("ffmpeg 未生成 HLS 清单: " + playlist);
        }
    }

    /**
     * 抽取首帧 jpg。
     */
    public void extractFirstFrame(Path input, Path output) {
        requireFile(input);
        List<String> command = Arrays.asList(
                properties.getFfmpegPath(),
                "-y",
                "-i", input.toString(),
                "-ss", "0",
                "-vframes", "1",
                "-q:v", "2",
                output.toString());
        execute(command, "ffmpeg");
        if (!Files.isRegularFile(output)) {
            throw new IllegalStateException("ffmpeg 未生成首帧文件: " + output);
        }
    }

    private String execute(List<String> command, String toolName) {
        return execute(command, toolName, null);
    }

    /**
     * 执行外部命令，返回合并后的 stdout + stderr 输出。
     */
    private String execute(List<String> command, String toolName, Path workingDirectory) {
        checkExecutable(command.get(0), toolName);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory.toFile());
        }
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new IllegalStateException(toolName + " 启动失败，请确认可执行文件存在且可运行: "
                    + command.get(0), e);
        }

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> readOutput(process, output), toolName + "-output-reader");
        reader.setDaemon(true);
        reader.start();

        int timeoutMinutes = Math.max(1, properties.getTimeoutMinutes());
        boolean finished;
        try {
            finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException(toolName + " 执行被中断", e);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(toolName + " 执行超时（" + timeoutMinutes + " 分钟），输出尾部:\n"
                    + tail(output));
        }

        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException(toolName + " 退出码 " + exitCode + "，输出尾部:\n" + tail(output));
        }
        return output.toString();
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        } catch (IOException e) {
            log.warn("读取命令输出失败", e);
        }
    }

    private static void requireFile(Path input) {
        if (input == null || !Files.isRegularFile(input)) {
            throw new IllegalStateException("输入文件不存在: " + input);
        }
    }

    private void checkExecutable(String executable, String toolName) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalStateException(toolName + " 可执行文件未配置");
        }
        if (executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0) {
            Path path = Path.of(executable);
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                throw new IllegalStateException(toolName + " 可执行文件不存在或不可执行: " + executable);
            }
        }
    }

    private static String tail(CharSequence output) {
        String text = output.toString();
        int max = 4000;
        return text.length() <= max ? text : text.substring(text.length() - max);
    }
}
