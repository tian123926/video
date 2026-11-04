package com.vod.common.constant;

import java.util.Map;
import java.util.Set;

/**
 * 点播业务常量：桶名、MQ 主题、分片限制等。
 */
public final class VideoConstants {

    public static final String BUCKET_SOURCE = "video-source";
    public static final String BUCKET_COVER = "video-cover";
    public static final String BUCKET_HLS = "video-hls";
    public static final String BUCKET_AVATAR = "video-avatar";

    public static final Map<String, String> BUCKETS = Map.of(
            "source", BUCKET_SOURCE,
            "cover", BUCKET_COVER,
            "hls", BUCKET_HLS,
            "avatar", BUCKET_AVATAR
    );

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "mp4", "mov", "m4v", "webm"
    );

    public static final String TOPIC_TRANSCODE = "vod-transcode-topic";
    public static final String GROUP_TRANSCODE = "vod-transcode-consumer-group";
    public static final String HEADER_SERVICE_TOKEN = "X-Service-Token";

    /** 单个分片最大 20 个签名 URL */
    public static final int MAX_PART_URLS = 20;
    /** 最大文件 2GiB */
    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    /** 固定分片 5MiB（除最后一片） */
    public static final long DEFAULT_CHUNK_SIZE = 5L * 1024 * 1024;
    /** 上传会话有效期（分钟） */
    public static final int SESSION_EXPIRE_MINUTES = 120;
    /** 播放地址有效期（秒） */
    public static final int DEFAULT_PLAY_EXPIRE_SECONDS = 1800;

    public static final int HLS_TIME_SECONDS = 6;
    public static final String HLS_PLAYLIST_NAME = "index.m3u8";
    public static final String FIRST_FRAME_NAME = "first-frame.jpg";

    private VideoConstants() {
    }

    public static boolean isAllowedExtension(String ext) {
        return ext != null && ALLOWED_EXTENSIONS.contains(ext.toLowerCase());
    }
}
