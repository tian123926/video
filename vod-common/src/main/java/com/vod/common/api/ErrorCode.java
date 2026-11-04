package com.vod.common.api;

import lombok.Getter;

/**
 * 业务错误码（与接口文档保持一致）。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "ok", 200),
    PARAM_ERROR(40001, "参数错误", 400),
    UNAUTHORIZED(40100, "缺少或无效的 JWT", 401),
    FORBIDDEN(40300, "无权操作该上传会话", 403),
    NOT_FOUND(40400, "资源不存在", 404),
    PARTS_MISSING(40901, "存在未上传的分片", 409),
    ETAG_MISMATCH(40902, "分片 ETag 不匹配或顺序错误", 409),
    SESSION_INVALID(40903, "未初始化上传或上传会话已失效", 409),
    FILE_TOO_LARGE(41300, "文件超过 2GB", 413),
    UNSUPPORTED_TYPE(41500, "不支持的文件类型", 415),
    VIDEO_NOT_READY(42501, "视频未就绪", 425),
    MINIO_UNAVAILABLE(50001, "对象存储不可用", 500),
    MERGE_FAILED(50002, "合并分片失败", 500),
    TRANSCODE_FAILED(50003, "转码失败", 500),
    MQ_UNAVAILABLE(50300, "转码任务投递失败，系统将自动补偿", 503),
    INTERNAL_ERROR(50000, "服务器内部错误", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
