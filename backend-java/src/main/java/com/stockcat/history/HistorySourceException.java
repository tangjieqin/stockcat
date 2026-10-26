package com.stockcat.history;

/**
 * 历史行情源异常。code 与 Python 内部接口约定的错误码一致，便于业务层直接记录/翻译：
 * NO_DATA / NOT_SUPPORTED / UPSTREAM_ERROR / UPSTREAM_TIMEOUT / DATA_SOURCE_UNAVAILABLE / INVALID_RESPONSE。
 */
public class HistorySourceException extends RuntimeException {

    private final String code;

    public HistorySourceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public HistorySourceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
