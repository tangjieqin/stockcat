package com.stockcat.quote;

/**
 * 基金实时参考数据源异常（腾讯 qt.gtimg.cn / Python 净值接口）。
 * 与 {@link com.stockcat.history.HistorySourceException} 分开：这两类数据各自失败，
 * 不能用一个异常类型把它们混在一起判断。
 */
public class FundSourceException extends RuntimeException {

    private final String code;

    public FundSourceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FundSourceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
