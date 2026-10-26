<script setup lang="ts">
/**
 * 标的详情页（P1.7）：日 K + 成交量 + MACD/RSI/KDJ 多副图。
 *
 * 几条不妥协的显示规则（对应 docs/UI.md §2.6.1 与 docs/API.md §3）：
 * - count=0 是「尚未同步」，显示同步引导，绝不画一条平线冒充行情；
 * - 指标预热不足显示「数据不足（需 N 根）」，不把 null 画成 0；
 * - 同步失败区分 DATA_SOURCE_UNAVAILABLE（Python 没起）与其他错误，不让用户瞎猜。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import VChart from 'vue-echarts'
import { buildKlineOption } from '../charts/klineChart'
import { getInstrument, type Instrument } from '../api/instrument'
import { changeColor, getLatestQuotes, type Quote } from '../api/quote'
import { getIndicators, getKlines, syncHistory, type IndicatorSeries, type KlineSeries, type KlineSyncSummary } from '../api/kline'
import { basisLabel, getFundReference, premiumText, refreshFundReference, type FundReference } from '../api/fund'
import { ApiError } from '../api/client'

const route = useRoute()
const instrumentId = computed(() => Number(route.params.id))

const instrument = ref<Instrument | null>(null)
const quote = ref<Quote | null>(null)
const series = ref<KlineSeries | null>(null)
const indicators = ref<IndicatorSeries | null>(null)
const indicatorError = ref(false)

const loading = ref(false)
const loadError = ref<ApiError | null>(null)
const notFound = ref(false)

const syncing = ref(false)
const syncResult = ref<KlineSyncSummary | null>(null)
const syncError = ref('')

const limit = ref(120)
const LIMIT_OPTIONS = [60, 120, 250, 500]

/** 基金参考数据（仅 ETF/LOF） */
const fundRef = ref<FundReference | null>(null)
const fundLoading = ref(false)
const fundRefreshing = ref(false)
const fundMsg = ref('')
const isFund = computed(() => instrument.value != null && instrument.value.type !== 'STOCK')

/** 防竞态：切标的/切区间时只认最后一次请求的结果 */
let requestSeq = 0

async function load() {
  const id = instrumentId.value
  if (!Number.isFinite(id) || id <= 0) {
    notFound.value = true
    return
  }
  const seq = ++requestSeq
  loading.value = true
  loadError.value = null
  notFound.value = false
  indicatorError.value = false
  syncResult.value = null

  try {
    instrument.value = await getInstrument(id)
  } catch (e) {
    if (seq !== requestSeq) return
    loading.value = false
    if (e instanceof ApiError && e.code === 'NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = e instanceof ApiError ? e : new ApiError(0, 'NETWORK_ERROR', String(e))
    }
    return
  }

  const [klineRes, indRes] = await Promise.allSettled([
    getKlines(id, { limit: limit.value, adjust: 'qfq' }),
    getIndicators(id, { limit: limit.value, adjust: 'qfq' }),
  ])
  if (seq !== requestSeq) return

  if (klineRes.status === 'fulfilled') {
    series.value = klineRes.value
  } else {
    series.value = null
    loadError.value =
      klineRes.reason instanceof ApiError
        ? klineRes.reason
        : new ApiError(0, 'NETWORK_ERROR', String(klineRes.reason))
  }

  if (indRes.status === 'fulfilled') {
    indicators.value = indRes.value
  } else {
    // 指标失败不影响 K 线：副图明确标「指标加载失败」，不画空线冒充
    indicators.value = null
    indicatorError.value = true
  }

  loading.value = false

  // 行情是装饰信息，失败就显示 —，不占用页面级错误位
  try {
    const quotes = await getLatestQuotes([id])
    quote.value = quotes[String(id)] ?? null
  } catch {
    quote.value = null
  }

  // 基金参考数据独立加载：失败只影响这一个区块，不影响 K 线
  if (isFund.value) {
    void loadFund()
  } else {
    fundRef.value = null
  }
}

async function loadFund() {
  if (!isFund.value) {
    fundRef.value = null
    return
  }
  fundLoading.value = true
  try {
    fundRef.value = await getFundReference(instrumentId.value)
  } catch {
    fundRef.value = null
  } finally {
    fundLoading.value = false
  }
}

async function doRefreshFund() {
  fundRefreshing.value = true
  fundMsg.value = ''
  try {
    const r = await refreshFundReference(instrumentId.value)
    fundMsg.value =
      r.status === 'SUCCESS'
        ? `已刷新：IOPV/价格更新，净值入库 ${r.navWritten} 期`
        : `刷新${r.status === 'PARTIAL' ? '部分成功' : '失败'}：${r.message ?? r.code ?? '未知原因'}`
    await loadFund()
  } catch (e) {
    fundMsg.value = e instanceof ApiError ? `${e.message}（${e.code}）` : String(e)
  } finally {
    fundRefreshing.value = false
  }
}

async function doSync() {
  syncing.value = true
  syncError.value = ''
  syncResult.value = null
  try {
    const days = Math.max(limit.value, 120)
    syncResult.value = await syncHistory(instrumentId.value, days)
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'DATA_SOURCE_UNAVAILABLE') {
      syncError.value = '历史数据服务（Python，默认端口 8100）不可用，请先启动 backend-python 后重试。'
    } else {
      syncError.value = e instanceof ApiError ? `${e.message}（${e.code}）` : String(e)
    }
  } finally {
    syncing.value = false
  }
}

watch(limit, () => {
  void load()
})
watch(instrumentId, () => {
  series.value = null
  indicators.value = null
  quote.value = null
  instrument.value = null
  fundRef.value = null
  fundMsg.value = ''
  void load()
})

const bars = computed(() => series.value?.bars ?? [])

const chartOption = computed(() =>
  buildKlineOption({
    bars: bars.value,
    points: indicators.value?.points ?? [],
    requiredBars: indicators.value?.requiredBars ?? {},
    indicatorError: indicatorError.value,
  }),
)

/** 预热不足的指标，用于顶部提示「先同步更多历史」 */
const shortages = computed(() => {
  const s = series.value
  const ind = indicators.value
  if (!s || !ind) return []
  const c = s.count
  const r = ind.requiredBars
  const out: string[] = []
  if (c < (r.macd ?? 34)) out.push(`MACD 需 ${r.macd ?? 34} 根`)
  const rsiNeed = Math.max(r.rsi6 ?? 7, r.rsi12 ?? 13, r.rsi24 ?? 25)
  if (c < rsiNeed) out.push(`RSI 需 ${rsiNeed} 根`)
  if (c < (r.kdj ?? 9)) out.push(`KDJ 需 ${r.kdj ?? 9} 根`)
  return out
})

const lastSyncedHours = computed(() => {
  const t = series.value?.lastFetchedAt
  if (!t) return null
  const ms = Date.now() - new Date(t).getTime()
  return Number.isFinite(ms) ? ms / 3_600_000 : null
})

const staleWarning = computed(() => {
  const h = lastSyncedHours.value
  if (h == null || h <= 24) return ''
  const days = Math.floor(h / 24)
  return `最后同步在 ${days > 0 ? `${days} 天前` : `${Math.round(h)} 小时前`}，可能不是最新数据；点「同步历史」补到最近交易日。`
})

const syncBanner = computed(() => {
  const r = syncResult.value
  if (!r) return null
  const rejected =
    r.rejectedUnclosed || r.rejectedInvalid || r.skippedUpstream
      ? `｜拒写：未收盘 ${r.rejectedUnclosed}、脏数据 ${r.rejectedInvalid}、上游问题行 ${r.skippedUpstream}`
      : ''
  if (r.status === 'SUCCESS') {
    return { cls: 'ok', text: `同步成功：请求 ${r.requestedLimit} 根，写入 ${r.accepted} 根，耗时 ${r.elapsedMs}ms${rejected}` }
  }
  if (r.status === 'SKIPPED') {
    return { cls: 'warn', text: `同步被跳过：${r.message ?? r.code ?? '代码规则不覆盖'}` }
  }
  if (r.code === 'DATA_SOURCE_UNAVAILABLE') {
    return { cls: 'error', text: '同步失败：历史数据服务（Python，默认端口 8100）未启动。先启动 backend-python 再点「同步历史」，实时行情与告警不受影响。' }
  }
  return { cls: 'error', text: `同步失败：${r.message ?? r.code ?? '未知原因'}` }
})

const latestBarDate = computed(() => bars.value.length ? bars.value[bars.value.length - 1].date : '')

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

function fmtPrice(v: number | null | undefined): string {
  if (v == null) return '—'
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 3 })
}

function statusText(status: string | undefined): { text: string; cls: string } {
  switch (status) {
    case 'VALID':
      return { text: '有效', cls: 'ok' }
    case 'STALE':
      return { text: '数据过期', cls: 'stale' }
    case 'PARTIAL':
      return { text: '部分缺失', cls: 'stale' }
    case 'INVALID':
      return { text: '无效', cls: 'bad' }
    default:
      return { text: '无快照', cls: 'off' }
  }
}

onMounted(load)
</script>

<template>
  <div v-if="notFound" class="state-page">
    <h1>标的不存在</h1>
    <p class="muted">库中没有 id={{ route.params.id }} 的标的。可能已被删除，或链接里的 id 不对。</p>
    <RouterLink class="btn" to="/stocks/watchlist">← 返回自选列表</RouterLink>
  </div>

  <div v-else>
    <div class="page-head">
      <div class="title-block">
        <RouterLink class="back" to="/stocks/watchlist">← 自选列表</RouterLink>
        <h1>
          {{ instrument ? instrument.name : '加载中…' }}
          <span v-if="instrument" class="mono symbol">{{ instrument.symbol }}</span>
          <span v-if="instrument" class="type-tag">{{ instrument.type }}</span>
        </h1>
        <p class="sub">日 K 收盘后同步入库，指标按查询现算（不落库）；P1 只支持日 K + 前复权。</p>
      </div>
      <div class="actions">
        <div class="seg" role="group" aria-label="周期">
          <button class="seg-item active" type="button">日 K</button>
          <button class="seg-item" type="button" disabled title="P1 只做日 K；分钟 K 历史深度有限，延后评估">周 K</button>
        </div>
        <div class="seg" role="group" aria-label="复权">
          <button class="seg-item active" type="button">前复权</button>
          <button class="seg-item" type="button" disabled title="P1 只同步前复权（qfq）">后复权</button>
          <button class="seg-item" type="button" disabled title="P1 只同步前复权（qfq）">不复权</button>
        </div>
        <button class="btn primary" :disabled="syncing" @click="doSync">
          {{ syncing ? '同步中…' : '⟳ 同步历史' }}
        </button>
      </div>
    </div>

    <!-- 行情条：来自最新快照，缺失显示 —，不按 0 -->
    <div class="quote-strip">
      <div class="q-item">
        <span class="q-label">最新价</span>
        <span class="q-value mono" :style="{ color: changeColor(quote?.changePercent) }">
          {{ fmtPrice(quote?.lastPrice) }}
        </span>
      </div>
      <div class="q-item">
        <span class="q-label">涨跌幅</span>
        <span class="q-value mono" :style="{ color: changeColor(quote?.changePercent) }">
          {{ quote?.changePercent != null ? `${quote.changePercent > 0 ? '+' : ''}${quote.changePercent}%` : '—' }}
        </span>
      </div>
      <div class="q-item">
        <span class="q-label">昨收</span>
        <span class="q-value mono">{{ fmtPrice(quote?.previousClose) }}</span>
      </div>
      <div class="q-item">
        <span class="q-label">行情时间</span>
        <span class="q-value">{{ fmtTime(quote?.marketTime) }}</span>
      </div>
      <div class="q-item">
        <span class="q-label">状态</span>
        <span class="badge" :class="statusText(quote?.dataStatus).cls">{{ statusText(quote?.dataStatus).text }}</span>
      </div>
      <div class="q-item grow">
        <span class="q-label">最新 K 线</span>
        <span class="q-value mono">{{ latestBarDate || '—' }}</span>
      </div>
      <div class="q-item">
        <span class="q-label">库中根数</span>
        <span class="q-value mono">{{ series ? `${series.count} 根` : '—' }}</span>
      </div>
      <div class="q-item">
        <span class="q-label">最后同步</span>
        <span class="q-value">{{ fmtTime(series?.lastFetchedAt) }}</span>
      </div>
    </div>

    <div v-if="loadError" class="banner error">
      加载失败：{{ loadError.message }}（{{ loadError.code }}）
      <button class="btn small" @click="load">重试</button>
    </div>
    <div v-else-if="staleWarning" class="banner warn">{{ staleWarning }}</div>
    <div v-if="shortages.length && series && series.count > 0" class="banner warn">
      历史根数不足：{{ shortages.join('、') }}，当前 {{ series?.count }} 根。对应副图暂为空，请先「同步历史」。
    </div>
    <div v-if="indicatorError" class="banner warn">指标接口加载失败：K 线与成交量仍可看，副图暂不可用。</div>

    <div v-if="syncBanner" class="banner" :class="syncBanner.cls">{{ syncBanner.text }}</div>
    <div v-if="syncError" class="banner error">{{ syncError }}</div>

    <!-- 基金参考数据：只有 ETF/LOF 有净值与溢价，股票不显示这块 -->
    <section v-if="isFund" class="fund-card">
      <div class="fund-head">
        <h2>基金参考数据</h2>
        <span class="muted">IOPV 随行情刷新；净值每晚同步，带净值日期</span>
        <button class="btn small" :disabled="fundRefreshing" @click="doRefreshFund">
          {{ fundRefreshing ? '刷新中…' : '⟳ 刷新参考数据' }}
        </button>
      </div>

      <p v-if="fundMsg" class="fund-msg">{{ fundMsg }}</p>
      <p v-if="fundLoading && !fundRef" class="muted">加载中…</p>
      <p v-else-if="fundRef && !fundRef.latest" class="muted">{{ fundRef.reason ?? '暂无基金参考数据' }}</p>

      <template v-else-if="fundRef?.latest">
        <div class="fund-grid">
          <div class="f-item">
            <span class="f-label">溢价率</span>
            <span class="f-value mono" :style="{ color: changeColor(fundRef.latest.premiumRate) }">
              {{ premiumText(fundRef.latest.premiumRate) }}
            </span>
            <span class="f-note">
              <template v-if="fundRef.latest.premiumBasis">
                基准 {{ basisLabel(fundRef.latest) }}
                <template v-if="fundRef.latest.premiumAsOf"> @ {{ fundRef.latest.premiumAsOf }}</template>
              </template>
              <template v-else>缺少基准或价格，不计算溢价</template>
            </span>
          </div>
          <div class="f-item">
            <span class="f-label">参考净值 IOPV</span>
            <span class="f-value mono">{{ fundRef.latest.iopv ?? '—' }}</span>
            <span class="f-note">{{ fundRef.latest.iopv ? fmtTime(fundRef.latest.iopvTime) : '只有 ETF 有实时 IOPV' }}</span>
          </div>
          <div class="f-item">
            <span class="f-label">单位净值</span>
            <span class="f-value mono">{{ fundRef.latest.nav ?? '—' }}</span>
            <span class="f-note">
              <template v-if="fundRef.latest.navDate">
                {{ fundRef.latest.navDate }}
                <template v-if="(fundRef.latest.navStaleDays ?? 0) > 0">（滞后 {{ fundRef.latest.navStaleDays }} 天）</template>
              </template>
              <template v-else>尚未同步净值</template>
            </span>
          </div>
          <div class="f-item">
            <span class="f-label">价格</span>
            <span class="f-value mono">{{ fundRef.latest.price ?? '—' }}</span>
            <span class="f-note">{{ fundRef.latest.tradeDate ?? '' }}</span>
          </div>
        </div>

        <table v-if="fundRef.history.length" class="fund-table">
          <thead>
            <tr>
              <th>日期</th>
              <th>价格</th>
              <th>单位净值</th>
              <th>净值日期</th>
              <th>IOPV</th>
              <th>溢价</th>
              <th>基准</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in fundRef.history.slice(0, 10)" :key="p.tradeDate ?? ''">
              <td class="mono">{{ p.tradeDate ?? '—' }}</td>
              <td class="mono">{{ p.price ?? '—' }}</td>
              <td class="mono">{{ p.nav ?? '—' }}</td>
              <td class="mono">{{ p.navDate ?? '—' }}</td>
              <td class="mono">{{ p.iopv ?? '—' }}</td>
              <td class="mono" :style="{ color: changeColor(p.premiumRate) }">{{ premiumText(p.premiumRate) }}</td>
              <td class="muted">{{ p.premiumBasis ? basisLabel(p) : '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="footnote">
          溢价率 =（价格 − 基准）/ 基准 × 100。ETF 盘中用实时 IOPV；LOF 没有 IOPV，只能用<strong>已公布的单位净值</strong>，
          因此盘中这个数一定滞后（表中"净值日期"与"日期"的差即滞后天数）。两者都没有时留空，不用别的数顶替。
        </p>
      </template>
    </section>

    <!-- 尚未同步：引导，不画图 -->
    <div v-if="!loading && series && series.count === 0" class="empty-card">
      <h2>尚未同步历史行情</h2>
      <p>
        这个标的还没有任何日 K 落库。count = 0 只表示「没同步过」，不代表它没有行情。
        点击下面的按钮从数据源拉取最近 120 根。
      </p>
      <button class="btn primary" :disabled="syncing" @click="doSync">
        {{ syncing ? '同步中…' : '同步最近 120 根' }}
      </button>
    </div>

    <div v-else-if="loading && !bars.length" class="empty-card">加载中…</div>

    <div v-else-if="bars.length" class="chart-card">
      <div class="chart-toolbar">
        <span class="muted">
          共 {{ bars.length }} 根 · {{ bars[0].date }} ~ {{ latestBarDate }}
          <template v-if="series?.sourceCode"> · 来源 {{ series.sourceCode }}</template>
        </span>
        <div class="range">
          <span class="muted">显示</span>
          <button
            v-for="opt in LIMIT_OPTIONS"
            :key="opt"
            class="chip"
            :class="{ active: limit === opt }"
            type="button"
            @click="limit = opt"
          >
            {{ opt }}
          </button>
          <span class="muted">根</span>
        </div>
      </div>
      <VChart class="chart" :option="chartOption" autoresize />
      <p class="footnote">
        指标口径对齐通达信：MACD(12,26,9) 柱 = 2×(DIF−DEA)；RSI 用 SMA(X,N,1) 递推；KDJ(9,3,3) 的 K/D 用 SMA 递推、
        J = 3K−2D 且<strong>不裁剪</strong>。预热不足处留空，不补 0。数据仅供自身复盘，不构成投资建议。
      </p>
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.title-block h1 {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 4px 0 2px;
}
.symbol {
  font-size: 15px;
  color: var(--text-secondary);
  font-weight: 500;
}
.back {
  font-size: 12px;
  color: var(--text-tertiary);
  text-decoration: none;
}
.back:hover {
  color: var(--accent);
}
.sub {
  color: var(--text-tertiary);
  font-size: 12px;
  margin: 0;
  max-width: 620px;
  line-height: 1.5;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.seg {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}
.seg-item {
  border: 0;
  background: var(--bg-panel);
  color: var(--text-secondary);
  font-size: 12px;
  padding: 6px 10px;
  cursor: pointer;
}
.seg-item + .seg-item {
  border-left: 1px solid var(--border);
}
.seg-item.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.seg-item:disabled {
  color: var(--text-tertiary);
  cursor: not-allowed;
  background: var(--bg-hover);
}
.quote-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 16px;
  margin: 14px 0 10px;
}
.q-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.q-item.grow {
  flex: 1;
}
.q-label {
  font-size: 11px;
  color: var(--text-tertiary);
}
.q-value {
  font-size: 13px;
}
.mono {
  font-family: Consolas, monospace;
}
.muted {
  color: var(--text-tertiary);
  font-size: 12px;
}
.type-tag {
  font-size: 10px;
  color: var(--text-tertiary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
}
.badge {
  font-size: 12px;
  border-radius: 4px;
  padding: 2px 8px;
  width: fit-content;
}
.badge.ok {
  background: #ecfdf5;
  color: #047857;
}
.badge.stale {
  background: #fffbeb;
  color: #b45309;
}
.badge.bad {
  background: #fdf2f2;
  color: #b91c1c;
}
.badge.off {
  background: var(--bg-hover);
  color: var(--text-tertiary);
}
.banner {
  border-radius: 8px;
  padding: 8px 12px;
  margin: 8px 0;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.banner.ok {
  background: #ecfdf5;
  color: #047857;
  border: 1px solid #a7f3d0;
}
.banner.warn {
  background: #fffbeb;
  color: #b45309;
  border: 1px solid #fde68a;
}
.banner.error {
  background: #fdf2f2;
  color: #b91c1c;
  border: 1px solid #f8b4b4;
}
.btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
  color: var(--text-primary);
  text-decoration: none;
  display: inline-block;
}
.btn:hover {
  background: var(--bg-hover);
}
.btn.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.btn.small {
  padding: 2px 8px;
  font-size: 12px;
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.empty-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 40px 24px;
  text-align: center;
  color: var(--text-tertiary);
  margin-top: 12px;
}
.empty-card h2 {
  font-size: 15px;
  color: var(--text-primary);
  margin: 0 0 8px;
}
.empty-card p {
  font-size: 13px;
  line-height: 1.7;
  max-width: 520px;
  margin: 0 auto 16px;
}
.chart-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 8px 14px;
  margin-top: 12px;
}
.fund-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 16px 14px;
  margin-top: 12px;
}
.fund-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.fund-head h2 {
  font-size: 14px;
  margin: 0;
}
.fund-head .btn {
  margin-left: auto;
}
.fund-msg {
  font-size: 12px;
  color: var(--accent);
  background: var(--accent-soft);
  border-radius: 6px;
  padding: 6px 10px;
  margin: 8px 0 0;
}
.fund-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 12px 0 10px;
}
.f-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.f-label {
  font-size: 11px;
  color: var(--text-tertiary);
}
.f-value {
  font-size: 16px;
}
.f-note {
  font-size: 11px;
  color: var(--text-tertiary);
  line-height: 1.4;
}
.fund-table {
  width: 100%;
  border-collapse: collapse;
}
.fund-table th,
.fund-table td {
  text-align: left;
  padding: 6px 8px;
  font-size: 12px;
  border-bottom: 1px solid var(--border);
}
.fund-table th {
  color: var(--text-secondary);
  background: var(--bg-hover);
  font-weight: 600;
}
.chart-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 10px 6px;
  gap: 12px;
  flex-wrap: wrap;
}
.range {
  display: flex;
  align-items: center;
  gap: 6px;
}
.chip {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  color: var(--text-secondary);
  border-radius: 12px;
  font-size: 12px;
  padding: 2px 10px;
  cursor: pointer;
}
.chip.active {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
.chart {
  height: 850px;
  width: 100%;
}
.footnote {
  color: var(--text-tertiary);
  font-size: 11px;
  line-height: 1.7;
  margin: 8px 12px 0;
}
.state-page {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 48px 32px;
  text-align: center;
}
.state-page .muted {
  margin-bottom: 18px;
  font-size: 13px;
}
</style>
