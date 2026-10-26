<script setup lang="ts">
/**
 * 首页·总览（P0.9 完整版）。
 * 市场概况（指数）/ 持仓表现 / 重点行情 / 最近告警 / 复盘入口。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changeColor, listIndices, type IndexQuote } from '../api/quote'
import { http, ApiError } from '../api/client'

const router = useRouter()

interface Dashboard {
  indices: IndexQuote[]
  positionsSummary: { totalMarketValue: number; totalUnrealizedPnl: number; positionCount: number }
  topMovers: Array<{ instrumentId: number; changePercent: number; lastPrice: number | null }>
  pendingAlerts: Array<{ eventId: number; symbol: string; ruleName: string; triggeredAt: string }>
  latestRecap: { recapId: number; recapDate: string; title: string; status: string } | null
}

const dashboard = ref<Dashboard | null>(null)
const indices = ref<IndexQuote[]>([])
const error = ref('')

onMounted(async () => {
  try {
    dashboard.value = (await http.get<Dashboard>('/dashboard')).data
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  }
  try {
    indices.value = await listIndices()
  } catch {
    // 指数失败不影响其余块
  }
})

function pnlColor(n: number): string {
  return n > 0 ? 'var(--up)' : n < 0 ? 'var(--down)' : 'var(--text-primary)'
}

function fmt(n: number, digits = 2): string {
  return n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

function fmtTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}
</script>

<template>
  <div>
    <h1>首页 · 总览</h1>

    <div v-if="error" class="banner error">无法加载总览：{{ error }}</div>

    <!-- 市场概况：指数 -->
    <div v-if="indices.length" class="indices">
      <div v-for="idx in indices" :key="idx.symbol" class="index-card">
        <div class="index-name">{{ idx.name }}</div>
        <div class="index-price" :style="{ color: changeColor(idx.changePercent) }">{{ idx.lastPrice ?? '—' }}</div>
        <div class="index-change" :style="{ color: changeColor(idx.changePercent) }">
          {{ idx.changePercent != null ? (idx.changePercent > 0 ? '+' : '') + idx.changePercent + '%' : '—' }}
        </div>
      </div>
    </div>
    <div v-else class="muted-line">指数行情将在交易时段由调度器自动采集</div>

    <div v-if="dashboard" class="grid">
      <!-- 持仓表现 -->
      <div class="card" @click="router.push('/stocks/positions')">
        <div class="card-title">持仓表现</div>
        <div class="card-big" :style="{ color: pnlColor(dashboard.positionsSummary.totalUnrealizedPnl) }">
          {{ fmt(dashboard.positionsSummary.totalUnrealizedPnl) }}
        </div>
        <div class="card-sub">浮动盈亏（元）</div>
        <div class="card-sub">
          总市值 {{ fmt(dashboard.positionsSummary.totalMarketValue) }} · {{ dashboard.positionsSummary.positionCount }} 个标的
        </div>
      </div>

      <!-- 重点行情 -->
      <div class="card" @click="router.push('/stocks/watchlist')">
        <div class="card-title">自选涨跌居前</div>
        <div v-if="dashboard.topMovers.length" class="mini-list">
          <div v-for="m in dashboard.topMovers" :key="m.instrumentId" class="mini-row">
            <span class="mini-pct" :style="{ color: changeColor(m.changePercent) }">
              {{ (m.changePercent > 0 ? '+' : '') + m.changePercent + '%' }}
            </span>
          </div>
        </div>
        <div v-else class="card-sub">自选列表为空</div>
      </div>

      <!-- 最近告警 -->
      <div class="card" @click="router.push('/alerts/history')">
        <div class="card-title">最近告警</div>
        <div v-if="dashboard.pendingAlerts.length" class="mini-list">
          <div v-for="a in dashboard.pendingAlerts.slice(0, 4)" :key="a.eventId" class="mini-row alert-row">
            <span class="mini-symbol">{{ a.symbol }}</span>
            <span class="mini-name">{{ a.ruleName }}</span>
            <span class="mini-time">{{ fmtTime(a.triggeredAt) }}</span>
          </div>
        </div>
        <div v-else class="card-sub">暂无告警事件</div>
      </div>

      <!-- 复盘入口 -->
      <div class="card" @click="dashboard.latestRecap ? router.push('/recap/market') : router.push('/recap/market')">
        <div class="card-title">最新复盘</div>
        <template v-if="dashboard.latestRecap">
          <div class="card-big small">{{ dashboard.latestRecap.title }}</div>
          <div class="card-sub">{{ dashboard.latestRecap.recapDate }}</div>
        </template>
        <div v-else class="card-sub">还没写过复盘 —— 今天就从它开始</div>
      </div>
    </div>
    <div v-else-if="!error">加载中…</div>
  </div>
</template>

<style scoped>
.indices {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.index-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 18px;
  min-width: 140px;
}
.index-name {
  font-size: 12px;
  color: var(--text-secondary);
}
.index-price {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  margin: 4px 0 2px;
}
.index-change {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.muted-line {
  color: var(--text-tertiary);
  font-size: 12px;
  margin-bottom: 20px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}
.card {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
  background: var(--bg-panel);
  cursor: pointer;
  transition: box-shadow 0.15s;
}
.card:hover {
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
}
.card-title {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.card-big {
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.card-big.small {
  font-size: 18px;
}
.card-sub {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
}
.mini-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mini-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.mini-pct {
  font-weight: 700;
}
.mini-symbol {
  font-family: Consolas, monospace;
}
.mini-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mini-time {
  font-size: 11px;
  color: var(--text-tertiary);
}
.banner.error {
  background: #fdf2f2;
  color: #b91c1c;
  border: 1px solid #f8b4b4;
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 14px;
  font-size: 13px;
}
</style>
