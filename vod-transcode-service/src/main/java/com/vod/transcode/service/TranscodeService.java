package com.vod.transcode.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.api.ErrorCode;
import com.vod.common.constant.VideoConstants;
import com.vod.common.constant.VideoStatus;
import com.vod.common.message.TranscodeMessage;
import com.vod.transcode.client.MinioInternalClient;
import com.vod.transcode.client.dto.ObjectUrlResp;
import com.vod.transcode.config.TranscodeProperties;
import com.vod.transcode.entity.TranscodeTask;
import com.vod.transcode.entity.Video;
import com.vod.transcode.mapper.TranscodeTaskMapper;
import com.vod.transcode.mapper.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 转码主流程：CAS 领取任务 → 下载源文件 → ffmpeg 生成 HLS/首帧 → 上传 → 回写状态。
 */
@Slf4j
@Service
public class TranscodeService {

    private static final int OBJECT_URL_EXPIRES_SECONDS = 604800;
    private static final String M3U8_CONTENT_TYPE = "application/vnd.apple.mpegurl";
    private static final String TS_CONTENT_TYPE = "video/mp2t";
    private static final String JPG_CONTENT_TYPE = "image/jpeg";
    private static final int MAX_ERROR_LENGTH = 500;
    private static final String DEFAULT_EXTENSION = "mp4";

    private final TranscodeTaskMapper taskMapper;
    private final VideoMapper videoMapper;
    private final MinioInternalClient minioInternalClient;
    private final FfmpegService ffmpegService;
    private final TranscodeProperties properties;

    public TranscodeService(TranscodeTaskMapper taskMapper,
                            VideoMapper videoMapper,
                            MinioInternalClient minioInternalClient,
                            FfmpegService ffmpegService,
                            TranscodeProperties properties) {
        this.taskMapper = taskMapper;
        this.videoMapper = videoMapper;
        this.minioInternalClient = minioInternalClient;
        this.ffmpegService = ffmpegService;
        this.properties = properties;
    }

    public void process(TranscodeMessage msg) {
        if (msg == null || msg.getVideoId() == null) {
            log.warn("收到无效转码消息: {}", msg);
            return;
        }
        Long videoId = msg.getVideoId();

        TranscodeTask task = taskMapper.selectOne(Wrappers.<TranscodeTask>lambdaQuery()
                .eq(TranscodeTask::getVideoId, videoId)
                .last("LIMIT 1"));
        if (task == null) {
            log.warn("未找到转码任务, 跳过 videoId={}", videoId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int claimed = taskMapper.update(null, Wrappers.<TranscodeTask>lambdaUpdate()
                .eq(TranscodeTask::getId, task.getId())
                .eq(TranscodeTask::getStatus, VideoStatus.TASK_WAITING)
                .set(TranscodeTask::getStatus, VideoStatus.TASK_RUNNING)
                .set(TranscodeTask::getStartedAt, now));
        if (claimed == 0) {
            log.info("转码任务已被处理或状态非待消费, 跳过 videoId={} taskId={}", videoId, task.getId());
            return;
        }
        task.setStatus(VideoStatus.TASK_RUNNING);
        task.setStartedAt(now);

        Path workDir = Paths.get(properties.getTempDir(), "transcode", String.valueOf(videoId));
        try {
            updateVideoStatus(videoId, VideoStatus.TRANSCODING);

            ObjectUrlResp sourceUrl = minioInternalClient.getObjectUrl(
                    task.getSourceBucket(), task.getSourceObject(), OBJECT_URL_EXPIRES_SECONDS);
            String extension = extensionOf(task.getSourceObject());
            Path sourceFile = workDir.resolve("source." + extension);
            minioInternalClient.download(sourceUrl.getUrl(), sourceFile);

            double durationSeconds = ffmpegService.probeDurationSeconds(sourceFile);
            ffmpegService.transcodeToHls(sourceFile, workDir);
            Path firstFrame = workDir.resolve(VideoConstants.FIRST_FRAME_NAME);
            ffmpegService.extractFirstFrame(sourceFile, firstFrame);

            uploadOutputs(task, workDir, firstFrame);
            markSuccess(task, videoId, durationSeconds);

            log.info("视频转码完成 videoId={} duration={}s", videoId, durationSeconds);
        } catch (Exception e) {
            log.error("视频转码失败 videoId={} taskId={}", videoId, task.getId(), e);
            markFailure(task, videoId, e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void updateVideoStatus(Long videoId, int status) {
        videoMapper.update(null, Wrappers.<Video>lambdaUpdate()
                .eq(Video::getId, videoId)
                .set(Video::getStatus, status));
    }

    private void uploadOutputs(TranscodeTask task, Path workDir, Path firstFrame) throws IOException {
        String prefix = task.getTargetPrefix() == null ? "" : task.getTargetPrefix();
        String hlsBucket = task.getTargetBucket();

        minioInternalClient.upload(hlsBucket, prefix + VideoConstants.HLS_PLAYLIST_NAME,
                workDir.resolve(VideoConstants.HLS_PLAYLIST_NAME), M3U8_CONTENT_TYPE);

        List<Path> segments;
        try (Stream<Path> stream = Files.list(workDir)) {
            segments = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("seg-") && name.endsWith(".ts");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path segment : segments) {
            minioInternalClient.upload(hlsBucket, prefix + segment.getFileName(), segment, TS_CONTENT_TYPE);
        }

        String coverObject = "cover/" + task.getVideoId() + "/" + VideoConstants.FIRST_FRAME_NAME;
        minioInternalClient.upload(VideoConstants.BUCKET_COVER, coverObject, firstFrame, JPG_CONTENT_TYPE);
    }

    private void markSuccess(TranscodeTask task, Long videoId, double durationSeconds) {
        LocalDateTime finishedAt = LocalDateTime.now();
        taskMapper.update(null, Wrappers.<TranscodeTask>lambdaUpdate()
                .eq(TranscodeTask::getId, task.getId())
                .set(TranscodeTask::getStatus, VideoStatus.TASK_SUCCESS)
                .set(TranscodeTask::getFinishedAt, finishedAt)
                .set(TranscodeTask::getErrorMsg, null));

        String prefix = task.getTargetPrefix() == null ? "hls/" + videoId + "/" : task.getTargetPrefix();
        Video video = videoMapper.selectById(videoId);
        var videoUpdate = Wrappers.<Video>lambdaUpdate()
                .eq(Video::getId, videoId)
                .set(Video::getStatus, VideoStatus.READY)
                .set(Video::getHlsBucket, VideoConstants.BUCKET_HLS)
                .set(Video::getM3u8Object, prefix + VideoConstants.HLS_PLAYLIST_NAME)
                .set(Video::getCoverBucket, VideoConstants.BUCKET_COVER)
                .set(Video::getFirstFrameObject,
                        "cover/" + videoId + "/" + VideoConstants.FIRST_FRAME_NAME)
                .set(Video::getDurationMs, (int) Math.round(durationSeconds * 1000))
                .set(Video::getErrorMsg, null);
        if (video != null && video.getPublishTime() == null) {
            videoUpdate.set(Video::getPublishTime, finishedAt);
        }
        videoMapper.update(null, videoUpdate);
    }

    private void markFailure(TranscodeTask task, Long videoId, Exception exception) {
        String errorMsg = truncate(exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage());
        try {
            taskMapper.update(null, Wrappers.<TranscodeTask>lambdaUpdate()
                    .eq(TranscodeTask::getId, task.getId())
                    .set(TranscodeTask::getStatus, VideoStatus.TASK_FAILED)
                    .setSql("retry_count = retry_count + 1")
                    .set(TranscodeTask::getErrorMsg, errorMsg));
            videoMapper.update(null, Wrappers.<Video>lambdaUpdate()
                    .eq(Video::getId, videoId)
                    .set(Video::getStatus, VideoStatus.FAILED)
                    .set(Video::getErrorMsg, errorMsg));
        } catch (Exception updateError) {
            log.error("回写转码失败状态时出错 videoId={} taskId={}", videoId, task.getId(), updateError);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除临时文件失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir, e);
        }
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return ErrorCode.TRANSCODE_FAILED.getMessage();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private static String extensionOf(String objectName) {
        if (objectName == null) {
            return DEFAULT_EXTENSION;
        }
        int index = objectName.lastIndexOf('.');
        if (index < 0 || index == objectName.length() - 1) {
            return DEFAULT_EXTENSION;
        }
        String extension = objectName.substring(index + 1).toLowerCase();
        return extension.matches("[a-z0-9]+") ? extension : DEFAULT_EXTENSION;
    }
}
