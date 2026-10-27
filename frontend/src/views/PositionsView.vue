<script setup lang="ts">
/**
 * 我的持仓页（P1.5）。对应 docs/UI.md §2.8 与 docs/API.md §6。
 *
 * 三个页签共用一套数据源，但口径不同：
 * - 当前持仓：由流水实时重放（不看快照），行情过期时市值/盈亏显示 —
 * - 收益曲线：快照按日聚合；缺价的点标 complete=false，不按 0 画
 * - 持仓变化：区间期初 vs 期末，期初取"区间之前最后一条快照"
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { buildEquityOption } from '../charts/equityChart'
import { listPositions, type Position } from '../api/trade'
import {
  getEquityCurve,
  getPositionChanges,
  rebuildSnapshots,
  type EquityCurve,
  type PositionChange,
} from '../api/portfolio'

type Tab = 'positions' | 'curve' | 'changes'

const route = useRoute()
const router = useRouter()

/** 页签写在 query 里：刷新/收藏/截图都能停在同一个页签，避免每次都跳回"当前持仓" */
function tabFromQuery(): Tab {
  const value = route.query.tab
  return value === 'curve' || value === 'changes' ? value : 'positions'
}

const tab = ref<Tab>(tabFromQuery())
const positions = ref<Position[]>([])
const curve = ref<EquityCurve | null>(null)
const changes = ref<PositionChange[]>([])
const loading = ref(false)
const errorMsg = ref('')

const RANGES = [
  { days: 30, label: '近 1 月' },
  { days: 90, label: '近 3 月' },
  { days: 180, label: '近 6 月' },
  { days: 365, label: '近 1 年' },
]
const rangeDays = ref(180)

const rebuilding = ref(false)
const rebuildText = ref('')
const rebuildWarnings = ref<string[]>([])

const chartOption = computed(() => buildEquityOption(curve.value?.points ?? []))
const incompleteDays = computed(() => (curve.value?.points ?? []).filter((p) => !p.complete).length)
const hasCurve = computed(() => (curve.value?.points.length ?? 0) > 0)

function fmt(n: number | null | undefined, digits = 2): string {
  return n == null ? '—' : n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

function signed(n: number | null | undefined, digits = 2): string {
  if (n == null) return '—'
  return `${n > 0 ? '+' : ''}${fmt(n, digits)}`
}

function pnlColor(n: number | null | undefined): string {
  if (n == null) return 'var(--text-primary)'
  return n > 0 ? 'var(--up)' : n < 0 ? 'var(--down)' : 'var(--text-primary)'
}

function positionPct(pos: Position): number | null {
  if (pos.unrealizedPnl == null || pos.avgCost === 0) return null
  return Number(((pos.unrealizedPnl / (pos.quantity * pos.avgCost)) * 100).toFixed(2))
}

/** 区间起点。右端交给后端（默认取库里最新快照日），避免本地时钟与快照日不一致。 */
function rangeParams(): { from?: string } {
  const d = new Date()
  d.setDate(d.getDate() - rangeDays.value)
  const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return { from: iso }
}

async function loadPositions() {
  loading.value = true
  errorMsg.value = ''
  try {
    positions.value = await listPositions()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function loadCurve() {
  loading.value = true
  errorMsg.value = ''
  try {
    curve.value = await getEquityCurve(rangeParams())
  } catch (e) {
    curve.value = null
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function loadChanges() {
  loading.value = true
  errorMsg.value = ''
  try {
    changes.value = await getPositionChanges(rangeParams())
  } catch (e) {
    changes.value = []
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function loadActive() {
  if (tab.value === 'positions') return loadPositions()
  if (tab.value === 'curve') return loadCurve()
  return loadChanges()
}

async function rebuild() {
  rebuilding.value = true
  rebuildText.value = ''
  rebuildWarnings.value = []
  errorMsg.value = ''
  try {
    const result = await rebuildSnapshots(rangeParams())
    rebuildText.value = `已重建 ${result.from} ~ ${result.to}：${result.tradeDates} 个交易日、${result.rows} 行，耗时 ${result.elapsedMs}ms`
    rebuildWarnings.value = result.warnings
    await loadActive()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    rebuilding.value = false
  }
}

watch(tab, (value) => {
  void router.replace({ query: { ...route.query, tab: value === 'positions' ? undefined : value } })
  loadActive()
})
watch(rangeDays, () => {
  if (tab.value !== 'positions') loadActive()
})

onMounted(() => {
  // 直接带 ?tab=curve 打开时，别只显示一个空页签
  if (tab.value === 'positions') {
    loadPositions()
  } else {
    loadActive()
  }
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>我的持仓</h1>
        <p class="sub">
          当前持仓由交易流水实时重放（摊薄成本含费用）；收益曲线与持仓变化基于每日快照（不复权收盘价，
          未调整分红送股）。
        </p>
      </div>
      <div class="head-actions">
        <select v-if="tab !== 'positions'" v-model.number="rangeDays" class="select">
          <option v-for="r in RANGES" :key="r.days" :value="r.days">{{ r.label }}</option>
        </select>
        <button class="btn" :disabled="rebuilding" @click="rebuild">
          {{ rebuilding ? '重建中…' : '重建快照' }}
        </button>
        <button class="btn" @click="loadActive">⟳ 刷新</button>
      </div>
    </div>

    <div class="tabs">
      <button :class="{ active: tab === 'positions' }" @click="tab = 'positions'">当前持仓</button>
      <button :class="{ active: tab === 'curve' }" @click="tab = 'curve'">收益曲线</button>
      <button :class="{ active: tab === 'changes' }" @click="tab = 'changes'">持仓变化</button>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>
    <div v-if="rebuildText" class="banner ok">{{ rebuildText }}</div>
    <div v-for="w in rebuildWarnings" :key="w" class="banner warn">{{ w }}</div>

    <!-- ---------------- 当前持仓 ---------------- -->
    <template v-if="tab === 'positions'">
      <div v-if="positions.length" class="table-wrap">
        <table class="table">
        <thead>
          <tr>
            <th>标的</th>
            <th>持有数量</th>
            <th>摊薄成本</th>
            <th>最新价</th>
            <th>市值</th>
            <th>浮动盈亏</th>
            <th>浮动收益率</th>
            <th>已实现盈亏</th>
            <th>价格状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in positions" :key="p.instrumentId">
            <td><span class="mono">{{ p.symbol }}</span> {{ p.name }}</td>
            <td class="mono">{{ fmt(p.quantity, 0) }}</td>
            <td class="mono">{{ fmt(p.avgCost, 3) }}</td>
            <td class="mono">{{ fmt(p.lastPrice, 3) }}</td>
            <td class="mono">{{ fmt(p.marketValue) }}</td>
            <td class="mono" :style="{ color: pnlColor(p.unrealizedPnl) }">{{ fmt(p.unrealizedPnl) }}</td>
            <td class="mono" :style="{ color: pnlColor(positionPct(p)) }">
              {{ positionPct(p) != null ? signed(positionPct(p), 2) + '%' : '—' }}
            </td>
            <td class="mono" :style="{ color: pnlColor(p.realizedPnl) }">{{ fmt(p.realizedPnl) }}</td>
            <td>
              <span class="badge" :class="p.priceStatus === 'VALID' ? 'ok' : 'stale'">
                {{ p.priceStatus === 'VALID' ? '有效' : p.priceStatus === 'NO_DATA' ? '—' : p.priceStatus }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <div v-else-if="!loading" class="empty">暂无持仓 —— 在「交易流水」记录买入后自动计算</div>
      <div v-else class="empty">加载中…</div>
    </template>

    <!-- ---------------- 收益曲线 ---------------- -->
    <template v-else-if="tab === 'curve'">
      <div v-if="hasCurve && curve" class="summary">
        <div class="card">
          <div class="label">期末总市值</div>
          <div class="value mono">{{ fmt(curve.summary.endMarketValue) }}</div>
          <div class="hint">区间变化 {{ signed(curve.summary.marketValueChange) }}</div>
        </div>
        <div class="card">
          <div class="label">累计收益</div>
          <div class="value mono" :style="{ color: pnlColor(curve.summary.endTotalPnl) }">
            {{ signed(curve.summary.endTotalPnl) }}
          </div>
          <div class="hint">
            浮动 {{ signed(curve.summary.endUnrealizedPnl) }} + 已实现
            {{ signed(curve.summary.endRealizedPnlCum) }}
          </div>
        </div>
        <div class="card">
          <div class="label">简单收益率</div>
          <div class="value mono" :style="{ color: pnlColor(curve.summary.returnPct) }">
            {{ curve.summary.returnPct != null ? signed(curve.summary.returnPct) + '%' : '—' }}
          </div>
          <div class="hint">净投入 {{ fmt(curve.summary.netInvested) }}</div>
        </div>
        <div class="card">
          <div class="label">期末持仓成本</div>
          <div class="value mono">{{ fmt(curve.summary.endCostValue) }}</div>
          <div class="hint">
            {{ curve.points[curve.points.length - 1].positions }} 只持仓 / 快照日
            {{ curve.lastSnapshotDate ?? '—' }}
          </div>
        </div>
      </div>

      <div v-if="hasCurve && incompleteDays" class="banner warn">
        有 {{ incompleteDays }} 个交易日存在取不到收盘价的持仓：这些天市值只统计了能取到价的部分，曲线偏低。
        请先「同步历史」补齐不复权日线，再重建快照。缺价当天不计算收益率。
      </div>

      <div v-if="hasCurve" class="chart-card">
        <VChart class="chart" :option="chartOption" autoresize />
      </div>

      <div v-if="hasCurve && curve" class="footnote">{{ curve.note }}</div>

      <div v-if="!hasCurve && !loading" class="empty">
        <p>还没有持仓快照。</p>
        <p class="empty-sub">
          快照按「流水重放 + 当日不复权收盘价」生成，需要先有交易流水和对应标的的日 K。
          收盘后 15:45 会自动生成，也可以直接点右上角「重建快照」。
        </p>
        <button class="btn" :disabled="rebuilding" @click="rebuild">立即重建快照</button>
      </div>
      <div v-else-if="loading" class="empty">加载中…</div>
    </template>

    <!-- ---------------- 持仓变化 ---------------- -->
    <template v-else>
      <div v-if="changes.length" class="table-wrap">
        <table class="table">
        <thead>
          <tr>
            <th>标的</th>
            <th>期初（日期）</th>
            <th>期末（日期）</th>
            <th>数量变化</th>
            <th>成本变化</th>
            <th>市值变化</th>
            <th>浮盈变化</th>
            <th>区间已实现</th>
            <th>成交</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in changes" :key="c.instrumentId">
            <td><span class="mono">{{ c.symbol ?? '—' }}</span> {{ c.name ?? '' }}</td>
            <td class="mono">
              {{ fmt(c.openQuantity, 0) }}
              <span class="date">{{ c.openDate ?? '区间内建仓' }}</span>
            </td>
            <td class="mono">
              {{ fmt(c.closeQuantity, 0) }}
              <span class="date">{{ c.closeDate }}</span>
            </td>
            <td class="mono">{{ signed(c.quantityDelta, 0) }}</td>
            <td class="mono">{{ signed(c.avgCostDelta, 3) }}</td>
            <td class="mono" :style="{ color: pnlColor(c.marketValueDelta) }">{{ signed(c.marketValueDelta) }}</td>
            <td class="mono" :style="{ color: pnlColor(c.unrealizedDelta) }">{{ signed(c.unrealizedDelta) }}</td>
            <td class="mono" :style="{ color: pnlColor(c.realizedPnlInRange) }">
              {{ signed(c.realizedPnlInRange) }}
            </td>
            <td class="mono">{{ c.tradeCount }} 笔</td>
            <td>
              <span v-if="c.closed" class="badge stale">区间内清仓</span>
              <span v-else-if="c.openDate === null" class="badge ok">新开仓</span>
              <span v-else class="badge">持有中</span>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <div v-else-if="!loading" class="empty">
        <p>该区间内没有持仓快照。</p>
        <p class="empty-sub">换一个更长的区间，或先点「重建快照」。</p>
      </div>
      <div v-else class="empty">加载中…</div>
    </template>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.sub {
  color: var(--text-tertiary);
  font-size: 13px;
  margin: -4px 0 0;
  max-width: 720px;
}
.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}
.tabs {
  display: flex;
  gap: 4px;
  margin: 14px 0 12px;
  border-bottom: 1px solid var(--border);
}
.tabs button {
  border: none;
  background: transparent;
  padding: 8px 14px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.tabs button.active {
  color: var(--text-primary);
  font-weight: 600;
  border-bottom-color: #2563eb;
}
.select {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 8px;
  font-size: 13px;
  color: var(--text-primary);
}
.table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}
.table th,
.table td {
  text-align: left;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}
.table th {
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-weight: 600;
  white-space: nowrap;
}
.mono {
  font-family: Consolas, monospace;
  font-variant-numeric: tabular-nums;
}
.date {
  display: block;
  font-size: 11px;
  color: var(--text-tertiary);
}
.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
}
.card .label {
  font-size: 12px;
  color: var(--text-tertiary);
}
.card .value {
  font-size: 20px;
  margin-top: 4px;
}
.card .hint {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 4px;
}
.chart-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 8px;
}
.chart {
  height: 420px;
  width: 100%;
}
.footnote {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 8px;
  line-height: 1.6;
}
.badge {
  font-size: 12px;
  border-radius: 4px;
  padding: 2px 8px;
  background: var(--bg-hover);
  color: var(--text-secondary);
  white-space: nowrap;
}
.badge.ok {
  background: #ecfdf5;
  color: #047857;
}
.badge.stale {
  background: #fffbeb;
  color: #b45309;
}
.empty {
  color: var(--text-tertiary);
  padding: 40px 16px;
  text-align: center;
}
.empty p {
  margin: 0 0 6px;
}
.empty-sub {
  font-size: 12px;
  max-width: 560px;
  margin: 0 auto 12px;
  line-height: 1.7;
}
.banner {
  border-radius: 8px;
  padding: 8px 12px;
  margin: 10px 0;
  font-size: 13px;
}
.banner.error {
  background: #fdf2f2;
  color: #b91c1c;
  border: 1px solid #f8b4b4;
}
.banner.warn {
  background: #fffbeb;
  color: #b45309;
  border: 1px solid #fde68a;
}
.banner.ok {
  background: #ecfdf5;
  color: #047857;
  border: 1px solid #a7f3d0;
}
.btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
  color: var(--text-primary);
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
