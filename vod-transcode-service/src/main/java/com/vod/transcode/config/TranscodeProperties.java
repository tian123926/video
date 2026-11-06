package com.vod.transcode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 转码相关配置：ffmpeg 可执行文件路径、临时目录、超时时间。
 */
@Data
@Component
@ConfigurationProperties(prefix = "vod.transcode")
public class TranscodeProperties {

    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";
    private String tempDir = System.getProperty("java.io.tmpdir") + "/vod-transcode";
    /** 工具微服务地址；为空时在本机直接执行 ffmpeg/ffprobe */
    private String toolsBaseUrl = "";
    private int timeoutMinutes = 30;
}
