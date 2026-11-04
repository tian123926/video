package com.vod.common.constant;

/**
 * t_video.status 取值。
 */
public final class VideoStatus {

    public static final int INIT = 0;
    public static final int UPLOADED = 1;
    public static final int TRANSCODING = 2;
    public static final int READY = 3;
    public static final int FAILED = 4;

    public static final int SESSION_UPLOADING = 0;
    public static final int SESSION_COMPLETED = 1;
    public static final int SESSION_ABORTED = 2;

    public static final int TASK_WAITING = 0;
    public static final int TASK_RUNNING = 1;
    public static final int TASK_SUCCESS = 2;
    public static final int TASK_FAILED = 3;

    private VideoStatus() {
    }
}
