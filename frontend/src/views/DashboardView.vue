<script setup lang="ts">
/**
 * 首页·市场与账户总览。
 * 栅格三行（对应 docs/UI.md §2.1 与 2026-10 用户提供的布局示意）：
 *   第一行：市场指标（三大指数）
 *   第二行：持仓概览 + 自选与监控
 *   第三行：最近告警 + 最新复盘
 * 任一块缺失显示空态，不伪造数据；当日盈亏暂无口径，不展示假值。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changeColor, listIndices, type IndexQuote } from '../api/quote'
import { http, ApiError } from '../api/client'

const router = useRouter()

interface TopMover {
  instrumentId: number
  symbol: string
  name: string
  changePercent: number
  lastPrice: number | null
}

interface Dashboard {
  indices: IndexQuote[]
  positionsSummary: { totalMarketValue: number; totalUnrealizedPnl: number; positionCount: number }
  topMovers: TopMover[]
  overview: { watchlistCount: number; monitorCount: number; enabledMonitorCount: number; enabledRuleCount: number }
  pendingAlerts: Array<{ eventId: number; symbol: string; ruleName: string; triggeredAt: string }>
  latestRecap: { recapId: number; recapDate: string; title: string; status: string } | null
}

const dashboard = ref<Dashboard | null>(null)
const indices = ref<IndexQuote[]>([])
const error = ref('')

/** 首页 AI 卡片上的快捷问题：点一下带着问题进对话页，省得手打 */
const AI_QUESTIONS = [
  '今天我的持仓发生了什么变化？',
  '最近哪些告警值得关注？',
  '根据现有数据说说市场情况。',
]

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
    <div class="page-head">
      <div>
        <h1>首页 · 市场与账户总览</h1>
        <p class="sub">市场概况、持仓表现与待处理告警一览；数值缺失时显示 — 而不是 0。</p>
      </div>
    </div>

    <div v-if="error" class="banner error">无法加载总览：{{ error }}</div>

    <!-- 第一行：市场指标 -->
    <div v-if="indices.length" class="indices">
      <div v-for="idx in indices" :key="idx.symbol" class="card index-card">
        <div class="index-name">{{ idx.name }}</div>
        <div class="index-price" :style="{ color: changeColor(idx.changePercent) }">{{ idx.lastPrice ?? '—' }}</div>
        <div class="index-change" :style="{ color: changeColor(idx.changePercent) }">
          {{ idx.changePercent != null ? (idx.changePercent > 0 ? '+' : '') + idx.changePercent + '%' : '—' }}
        </div>
      </div>
    </div>
    <div v-else class="muted-line">指数行情将在交易时段由调度器自动采集</div>

    <template v-if="dashboard">
      <!-- 第二行：持仓概览 + 自选与监控 -->
      <div class="row-2">
        <div class="card clickable" @click="router.push('/stocks/positions')">
          <div class="card-title">持仓概览</div>
          <div class="card-big" :style="{ color: pnlColor(dashboard.positionsSummary.totalUnrealizedPnl) }">
            {{ fmt(dashboard.positionsSummary.totalUnrealizedPnl) }}
          </div>
          <div class="card-sub">浮动盈亏（元）</div>
          <div class="card-sub">
            持仓市值 {{ fmt(dashboard.positionsSummary.totalMarketValue) }} · 持仓数量 {{ dashboard.positionsSummary.positionCount }}
          </div>
        </div>

        <div class="card clickable" @click="router.push('/stocks/watchlist')">
          <div class="card-title">自选与监控</div>
          <div v-if="dashboard.topMovers.length" class="mini-list">
            <div
              v-for="m in dashboard.topMovers"
              :key="m.instrumentId"
              class="mini-row clickable"
              @click.stop="router.push(`/stocks/${m.instrumentId}`)"
            >
              <span class="mini-symbol">{{ m.symbol }}</span>
              <span class="mini-name">{{ m.name }}</span>
              <span class="mini-pct" :style="{ color: changeColor(m.changePercent) }">
                {{ (m.changePercent > 0 ? '+' : '') + m.changePercent + '%' }}
              </span>
            </div>
          </div>
          <div v-else class="card-sub">自选列表为空</div>
          <div class="card-sub stat-line">
            自选 {{ dashboard.overview?.watchlistCount ?? '—' }} ·
            监控 {{ dashboard.overview?.monitorCount ?? '—' }}（启用 {{ dashboard.overview?.enabledMonitorCount ?? '—' }}）·
            启用规则 {{ dashboard.overview?.enabledRuleCount ?? '—' }}
          </div>
        </div>
      </div>

      <!-- 第三行：最近告警 + 最新复盘 -->
      <div class="row-2">
        <div class="card clickable" @click="router.push('/alerts/history')">
          <div class="card-title">最近告警</div>
          <div v-if="dashboard.pendingAlerts.length" class="mini-list">
            <div v-for="a in dashboard.pendingAlerts.slice(0, 4)" :key="a.eventId" class="mini-row">
              <span class="mini-symbol">{{ a.symbol }}</span>
              <span class="mini-name">{{ a.ruleName }}</span>
              <span class="mini-time">{{ fmtTime(a.triggeredAt) }}</span>
            </div>
          </div>
          <div v-else class="card-sub">暂无告警事件</div>
        </div>

        <div class="card clickable" @click="router.push('/recap/market')">
          <div class="card-title">最新复盘</div>
          <template v-if="dashboard.latestRecap">
            <div class="card-big small">{{ dashboard.latestRecap.title }}</div>
            <div class="card-sub">{{ dashboard.latestRecap.recapDate }}</div>
          </template>
          <div v-else class="card-sub">还没写过复盘 —— 今天就从它开始</div>
        </div>
      </div>

      <!-- 第四行：AI 助手入口（P2） -->
      <div class="card ai-card">
        <div class="ai-head">
          <div>
            <div class="card-title">AI 助手</div>
            <div class="card-sub">
              基于持仓、行情、告警与复盘数据回答问题；每次回答都会列出调用了哪些工具、数据截至何时。
            </div>
          </div>
          <RouterLink class="btn small" to="/assistant">打开对话页 →</RouterLink>
        </div>
        <div class="ai-questions">
          <button
            v-for="q in AI_QUESTIONS"
            :key="q"
            class="ai-q"
            @click="router.push({ path: '/assistant', query: { q } })"
          >
            {{ q }}
          </button>
        </div>
      </div>
    </template>
    <div v-else-if="!error" class="empty">加载中…</div>
  </div>
</template>

<style scoped>
/* 第一行：指数自适应 1~3 列 */
.indices {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}
.index-name {
  font-size: 12px;
  color: var(--text-secondary);
}
.index-price {
  font-size: 22px;
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
  margin-bottom: 16px;
}
/* 第二/三行：双列自适应，窄窗口自动变单列，不撑出屏幕 */
.row-2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}
.clickable {
  cursor: pointer;
  transition: box-shadow 0.15s;
}
.clickable:hover {
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
.stat-line {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.mini-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 4px;
}
.mini-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.mini-row.clickable:hover .mini-name {
  color: var(--accent);
}
.mini-pct {
  font-weight: 700;
}
.mini-symbol {
  font-family: Consolas, monospace;
  flex-shrink: 0;
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
/* 第四行：AI 助手入口 */
.ai-card {
  margin-bottom: 16px;
}
.ai-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 10px;
}
.ai-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ai-q {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12px;
  font-family: inherit;
  color: var(--text-secondary);
  cursor: pointer;
}
.ai-q:hover {
  border-color: var(--accent);
  color: var(--accent);
}
</style>
