package com.stockcat.common;

/**
 * 业务错误码。与 docs/API.md §1 的建议错误码保持一致。
 */
public enum ErrorCode {

    VALIDATION_ERROR("VALIDATION_ERROR", 400),
    NOT_FOUND("NOT_FOUND", 404),
    CONFLICT("CONFLICT", 409),
    DATA_SOURCE_UNAVAILABLE("DATA_SOURCE_UNAVAILABLE", 503),
    STALE_DATA("STALE_DATA", 503),
    INTERNAL_ERROR("INTERNAL_ERROR", 500);

    private final String code;
    private final int httpStatus;

    ErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
