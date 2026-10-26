package com.stockcat.common;

/**
 * 业务异常，由 GlobalExceptionHandler 统一转换为 API.md 定义的错误结构。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException validationError(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
