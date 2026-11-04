package com.vod.common.exception;

import com.vod.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常，携带业务错误码与 HTTP 状态码。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final int httpStatus;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage(), errorCode.getHttpStatus());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode.getCode(), message, errorCode.getHttpStatus());
    }

    public BusinessException(int code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }
}
