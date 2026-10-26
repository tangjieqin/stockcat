package com.stockcat.quote;

/**
 * 标的代码 -> 行情源 secid 的映射规则（P0 仅支持 A 股沪深）。
 * 东财 secid：沪市前缀 1，深市前缀 0。
 */
public final class SymbolMapping {

    private SymbolMapping() {
    }

    /**
     * @return 如 "1.600519"；无法识别时返回 null（调用方应拒绝并说明，不猜测）。
     */
    public static String cnAToSecid(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        char head = symbol.charAt(0);
        // 沪市: 6xx 股票, 5xx 基金/ETF, 9xx B股
        if (head == '6' || head == '5' || head == '9') {
            return "1." + symbol;
        }
        // 深市: 0xx 股票, 1xx 基金/债券, 2xx B股, 3xx 创业板
        if (head == '0' || head == '1' || head == '2' || head == '3') {
            return "0." + symbol;
        }
        // 北交所 8xx/4xx、未知前缀：P0 不支持
        return null;
    }
}
