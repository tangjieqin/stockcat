package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.FundReference;
import com.stockcat.domain.Instrument;
import com.stockcat.fund.FundNavClient;
import com.stockcat.fund.FundNavFetch;
import com.stockcat.fund.FundNavPoint;
import com.stockcat.mapper.FundReferenceMapper;
import com.stockcat.mapper.InstrumentMapper;
import com.stockcat.quote.FundQuote;
import com.stockcat.quote.FundSourceException;
import com.stockcat.quote.TencentFundClient;
import com.stockcat.web.dto.FundPremiumResponse;
import com.stockcat.web.dto.FundReferenceResponse;
import com.stockcat.web.dto.FundRefreshResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基金参考数据与溢价（P1.4）。
 *
 * <p>数据分两条路进来，失败也分开报告：
 * <ul>
 *   <li>IOPV + 价格：{@link TencentFundClient}（Java 直连，盘中每 15 秒）</li>
 *   <li>单位净值：{@link FundNavClient} → Python 适配器（每晚一次，带净值日期）</li>
 * </ul>
 *
 * <p>溢价率不落库，查询时现算。三条硬规则（docs/DATA_SOURCES.md §3.1）：
 * <ol>
 *   <li>ETF 有 IOPV 就用 IOPV 作分母，标注 {@code premiumBasis=IOPV}</li>
 *   <li>没有 IOPV（LOF/股票/ETF 取数失败）只能用**带日期**的净值，标注 {@code premiumBasis=NAV}
 *       并给出 {@code premiumAsOf} 与 {@code navStaleDays}</li>
 *   <li>两者都没有就返回 null —— 不用"昨天的净值"偷偷顶替实时 IOPV</li>
 * </ol>
 */
@Service
public class FundReferenceService {

    private static final Logger log = LoggerFactory.getLogger(FundReferenceService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final Set<String> FUND_TYPES = Set.of("ETF", "LOF");

    public static final int MAX_DAYS = 250;
    private static final int PREMIUM_SCALE = 4;

    private final FundReferenceMapper fundMapper;
    private final InstrumentMapper instrumentMapper;
    private final TencentFundClient tencentClient;
    private final FundNavClient navClient;
    private final int defaultNavDays;

    public FundReferenceService(FundReferenceMapper fundMapper,
                                InstrumentMapper instrumentMapper,
                                TencentFundClient tencentClient,
                                FundNavClient navClient,
                                @Value("${stockcat.fund.nav.days:30}") int defaultNavDays) {
        this.fundMapper = fundMapper;
        this.instrumentMapper = instrumentMapper;
        this.tencentClient = tencentClient;
        this.navClient = navClient;
        this.defaultNavDays = clampDays(defaultNavDays);
    }

    public boolean isFund(Instrument instrument) {
        return instrument != null && isFundType(instrument.getInstrumentType());
    }

    /** 只有 ETF/LOF 有 IOPV 或单位净值；股票/指数没有溢价这个概念。 */
    public static boolean isFundType(String instrumentType) {
        return instrumentType != null && FUND_TYPES.contains(instrumentType.toUpperCase());
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 盘中刷新：批量取腾讯行情，写当天（按数据源行情时间判定）的 IOPV 与价格。
     * 不抛异常——实时 IOPV 失败不能影响行情采集主链路。
     */
    public MarketRefresh refreshMarket(List<Instrument> instruments) {
        List<Instrument> funds = instruments.stream().filter(this::isFund).toList();
        if (funds.isEmpty()) {
            return new MarketRefresh(0, 0, 0, null, null);
        }
        List<FundQuote> quotes;
        try {
            quotes = tencentClient.fetch(funds.stream().map(Instrument::getSymbol).toList());
        } catch (FundSourceException e) {
            log.warn("基金实时参考数据获取失败: [{}] {}", e.getCode(), e.getMessage());
            return new MarketRefresh(funds.size(), 0, funds.size(), e.getCode(), e.getMessage());
        }

        Map<String, FundQuote> byCode = new HashMap<>();
        for (FundQuote quote : quotes) {
            byCode.put(quote.code(), quote);
        }

        int updated = 0;
        int failed = 0;
        for (Instrument fund : funds) {
            FundQuote quote = byCode.get(fund.getSymbol());
            // 没有行情时间就无法确定这行属于哪个交易日，宁可不写也不要写错日期
            if (quote == null || quote.marketTime() == null
                    || (quote.price() == null && quote.iopv() == null)) {
                failed++;
                continue;
            }
            FundReference row = new FundReference();
            row.setInstrumentId(fund.getId());
            row.setTradeDate(quote.marketTime().atZoneSameInstant(CN).toLocalDate());
            row.setPrice(quote.price());
            row.setIopv(quote.iopv());
            row.setIopvTime(quote.iopv() == null ? null : quote.marketTime());
            row.setMarketSourceCode(TencentFundClient.SOURCE_CODE);
            try {
                fundMapper.upsertMarketData(row);
                updated++;
            } catch (Exception e) {
                failed++;
                log.warn("基金参考数据写入失败 {}: {}", fund.getSymbol(), e.getMessage());
            }
        }
        return new MarketRefresh(funds.size(), updated, failed, null, null);
    }

    /**
     * 同步净值（含净值日期）。失败返回 code/message，不抛异常。
     */
    public NavSync syncNav(Instrument instrument, int days) {
        if (!isFund(instrument)) {
            return new NavSync(0, 0, "NOT_APPLICABLE", "仅 ETF/LOF 有单位净值");
        }
        try {
            FundNavFetch fetch = navClient.fetch(instrument.getSymbol(), clampDays(days));
            int written = 0;
            for (FundNavPoint point : fetch.items()) {
                FundReference row = new FundReference();
                row.setInstrumentId(instrument.getId());
                // 净值行的 trade_date 就取净值日期：这条记录描述的是"哪一天的净值"
                row.setTradeDate(point.date());
                row.setNav(point.nav());
                row.setNavDate(point.date());
                row.setNavSourceCode(FundNavClient.SOURCE_CODE);
                fundMapper.upsertNav(row);
                written++;
            }
            if (fetch.skippedRows() > 0) {
                log.warn("净值同步 {}：上游 {} 行无法解析或重复", instrument.getSymbol(), fetch.skippedRows());
            }
            return new NavSync(written, fetch.skippedRows(), null, null);
        } catch (FundSourceException e) {
            log.warn("净值同步失败 {}: [{}] {}", instrument.getSymbol(), e.getCode(), e.getMessage());
            return new NavSync(0, 0, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("净值同步异常 {}: {}", instrument.getSymbol(), e.getMessage(), e);
            return new NavSync(0, 0, "SYNC_ERROR", "同步异常: " + e.getMessage());
        }
    }

    /**
     * 手动刷新单只基金：行情（IOPV/价格）+ 净值两条都跑，结果分开报告。
     */
    public FundRefreshResponse refresh(Long instrumentId) {
        long start = System.currentTimeMillis();
        Instrument instrument = requireInstrument(instrumentId);
        if (!isFund(instrument)) {
            throw ApiException.validationError("仅 ETF/LOF 有净值与溢价，该标的是 " + instrument.getInstrumentType());
        }

        MarketRefresh market = refreshMarket(List.of(instrument));
        NavSync nav = syncNav(instrument, defaultNavDays);

        boolean marketOk = market.updated() > 0;
        boolean navOk = nav.written() > 0;
        String status = marketOk && navOk ? "SUCCESS" : (marketOk || navOk ? "PARTIAL" : "FAILED");
        String code = market.code() != null ? market.code() : nav.code();
        String message = joinMessages(market.message(), nav.message());

        log.info("基金参考数据刷新 {}：行情 {} 净值 {} 期 status={} 耗时 {}ms",
                instrument.getSymbol(), marketOk, nav.written(), status, System.currentTimeMillis() - start);
        return new FundRefreshResponse(instrumentId, instrument.getSymbol(), marketOk,
                nav.written(), nav.skipped(), status, code, message, System.currentTimeMillis() - start);
    }

    /**
     * 批量刷新（调度用）：自选 ∪ 监控 ∪ 持仓中的基金。
     */
    public BatchRefresh refreshAll(List<Long> instrumentIds, int days) {
        long start = System.currentTimeMillis();
        List<Instrument> funds = instrumentMapper.selectByIds(instrumentIds).stream().filter(this::isFund).toList();
        if (funds.isEmpty()) {
            return new BatchRefresh(0, 0, 0, 0, System.currentTimeMillis() - start);
        }
        MarketRefresh market = refreshMarket(funds);
        int navWritten = 0;
        int failed = market.failed();
        for (Instrument fund : funds) {
            NavSync nav = syncNav(fund, days);
            navWritten += nav.written();
            if (nav.code() != null) {
                failed++;
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("基金参考数据刷新：基金 {} 行情更新 {} 净值入库 {} 期 失败 {} 耗时 {}ms",
                funds.size(), market.updated(), navWritten, failed, elapsed);
        return new BatchRefresh(funds.size(), market.updated(), navWritten, failed, elapsed);
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 单只基金的参考数据与最近 days 行历史。
     * 不存在的标的是 404；股票返回 applicable=false（不是错误，是没有这个概念）。
     */
    public FundReferenceResponse query(Long instrumentId, int days) {
        Instrument instrument = requireInstrument(instrumentId);
        if (!isFund(instrument)) {
            return new FundReferenceResponse(instrumentId, instrument.getSymbol(), instrument.getInstrumentType(),
                    false, "股票没有单位净值与溢价，仅 ETF/LOF 适用", null, List.of());
        }
        List<FundReference> rows = fundMapper.selectHistory(instrumentId, clampDays(days));
        if (rows.isEmpty()) {
            return new FundReferenceResponse(instrumentId, instrument.getSymbol(), instrument.getInstrumentType(),
                    true, "尚未同步基金参考数据（盘中每 15 秒写 IOPV/价格，净值每晚同步）", null, List.of());
        }
        FundReference latestRow = rows.get(0);
        FundReference navRow = latestDatedNav(instrumentId);
        List<FundPremiumResponse> history = new ArrayList<>();
        for (FundReference row : rows) {
            // 历史行只用当天自己记录的基准，避免"拿今天的净值算昨天的溢价"
            history.add(premium(instrument, row, row));
        }
        return new FundReferenceResponse(instrumentId, instrument.getSymbol(), instrument.getInstrumentType(),
                true, null, premium(instrument, latestRow, navRow), history);
    }

    /**
     * 批量取当前溢价（自选列表用）。股票不在结果里——前端显示 —，不按 0。
     */
    public Map<Long, FundPremiumResponse> latestPremiums(List<Instrument> instruments) {
        List<Instrument> funds = instruments.stream().filter(this::isFund).toList();
        if (funds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = funds.stream().map(Instrument::getId).toList();
        Map<Long, FundReference> latestByInstrument = indexById(fundMapper.selectLatestByInstrumentIds(ids));
        Map<Long, FundReference> navByInstrument = indexById(fundMapper.selectLatestDatedNavByInstrumentIds(ids));

        Map<Long, FundPremiumResponse> result = new LinkedHashMap<>();
        for (Instrument fund : funds) {
            FundPremiumResponse premium = premium(fund, latestByInstrument.get(fund.getId()),
                    navByInstrument.get(fund.getId()));
            if (premium != null) {
                result.put(fund.getId(), premium);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 内部

    private Instrument requireInstrument(Long instrumentId) {
        Instrument instrument = instrumentMapper.findById(instrumentId);
        if (instrument == null) {
            throw ApiException.notFound("标的不存在: id=" + instrumentId);
        }
        return instrument;
    }

    private FundReference latestDatedNav(Long instrumentId) {
        List<FundReference> rows = fundMapper.selectLatestDatedNavByInstrumentIds(List.of(instrumentId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<Long, FundReference> indexById(List<FundReference> rows) {
        Map<Long, FundReference> map = new HashMap<>();
        for (FundReference row : rows) {
            map.put(row.getInstrumentId(), row);
        }
        return map;
    }

    /**
     * 计算一行的溢价视图。
     *
     * @param row    这一行（提供价格与 IOPV）
     * @param navRow 提供带日期的净值；当前行自己有带日期的净值时优先用自己
     */
    static FundPremiumResponse premium(Instrument instrument, FundReference row, FundReference navRow) {
        if (row == null) {
            return null;
        }
        boolean etf = "ETF".equalsIgnoreCase(String.valueOf(instrument.getInstrumentType()));
        Double price = row.getPrice();
        boolean ownNav = row.getNav() != null && row.getNavDate() != null;
        Double nav = ownNav ? row.getNav() : (navRow == null ? null : navRow.getNav());
        LocalDate navDate = ownNav ? row.getNavDate() : (navRow == null ? null : navRow.getNavDate());
        Long staleDays = staleDays(navDate, row.getTradeDate());

        if (etf && row.getIopv() != null && price != null) {
            return new FundPremiumResponse(row.getTradeDate(), price, row.getIopv(), row.getIopvTime(),
                    nav, navDate, staleDays,
                    percent(price, row.getIopv()), "IOPV",
                    row.getIopvTime() == null ? null : row.getIopvTime().toString(),
                    row.getMarketSourceCode(), row.getNavSourceCode(), row.getFetchedAt());
        }
        if (nav != null && price != null) {
            return new FundPremiumResponse(row.getTradeDate(), price, row.getIopv(), row.getIopvTime(),
                    nav, navDate, staleDays,
                    percent(price, nav), "NAV",
                    navDate == null ? null : navDate.toString(),
                    row.getMarketSourceCode(), row.getNavSourceCode(), row.getFetchedAt());
        }
        // 缺基准或缺价格：溢价留空，但价格/净值本身照常返回，界面可以只显示已知的那部分
        return new FundPremiumResponse(row.getTradeDate(), price, row.getIopv(), row.getIopvTime(),
                nav, navDate, staleDays, null, null, null, row.getMarketSourceCode(), row.getNavSourceCode(), row.getFetchedAt());
    }

    /** 净值距行情日的自然日差；净值日期缺失返回 null（不按 0 假装"当天净值"）。 */
    static Long staleDays(LocalDate navDate, LocalDate tradeDate) {
        if (navDate == null || tradeDate == null) {
            return null;
        }
        return Math.max(0, ChronoUnit.DAYS.between(navDate, tradeDate));
    }

    /** (price - basis) / basis × 100，4 位小数；用 BigDecimal 避免浮点尾数进到界面。 */
    static Double percent(Double price, Double basis) {
        if (price == null || basis == null || basis <= 0) {
            return null;
        }
        return BigDecimal.valueOf(price)
                .subtract(BigDecimal.valueOf(basis))
                .divide(BigDecimal.valueOf(basis), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(PREMIUM_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public static int clampDays(int days) {
        if (days <= 0) {
            return 1;
        }
        return Math.min(days, MAX_DAYS);
    }

    private static String joinMessages(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "；" + second;
    }

    /** 行情刷新结果（内部用）。 */
    public record MarketRefresh(int requested, int updated, int failed, String code, String message) {
    }

    /** 净值同步结果（内部用）。 */
    public record NavSync(int written, int skipped, String code, String message) {
    }

    /** 批量刷新结果（调度日志用）。 */
    public record BatchRefresh(int instruments, int marketUpdated, int navWritten, int failed, long elapsedMs) {
    }
}
