package com.stockcat.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 持仓快照重建结果（POST /portfolio/snapshots/rebuild）。
 *
 * <p>{@code warnings} 不为空不代表失败：常见是某个标的没有不复权日线，
 * 该标的市值会留空，曲线会标 complete=false。原样透出给用户看，比吞掉更有用。
 */
public record PositionRebuildResponse(
        LocalDate from,
        LocalDate to,
        int tradeDates,
        int instruments,
        int rows,
        int syncedInstruments,
        List<String> warnings,
        long elapsedMs
) {
}
