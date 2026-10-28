<script setup lang="ts">
/**
 * 首页·市场与账户总览。
 * 栅格三行（对应 docs/UI.md §2.1 与 2026-10 用户提供的布局示意）：
 *   第一行：市场指标（三大指数）
 *   第二行：持仓概览 + 自选与监控
 *   第三行：最近告警 + 最新复盘
 * 任一块缺失显示空态，不伪造数据；当日盈亏暂无口径，不展示假值。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changeColor, fetchCustomIndices, listIndices, refreshIndices, type IndexQuote } from '../api/quote'
import { listAlertEvents, listAlertRules } from '../api/alert'
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
const refreshing = ref(false)

/** 告警统计（原「告警总览」页的内容，2026-10 并入首页）：规则总数/启用中/今日事件/历史事件 */
const alertStats = ref<{ rules: number; enabled: number; today: number; total: number } | null>(null)

async function loadAlertStats() {
  try {
    const [rulePage, eventPage] = await Promise.all([
      listAlertRules({ size: 100 }),
      listAlertEvents({ size: 50 }),
    ])
    const today = new Date().toDateString()
    alertStats.value = {
      rules: rulePage.items.length,
      enabled: rulePage.items.filter((r) => r.enabled).length,
      total: eventPage.total,
      today: eventPage.items.filter((e) => new Date(e.triggeredAt).toDateString() === today).length,
    }
  } catch {
    // 统计加载失败不影响首页其他块
  }
}

// ---- 指数展示：紧凑单行列表 + 「添加」按钮 ----
// 内置指数由后端 stockcat.indices 配置驱动；展示哪些、加哪些自定义指数存在浏览器本地
const HIDDEN_KEY = 'stockcat.index-hidden'
const CUSTOM_KEY = 'stockcat.index-custom'
const SECID_RE = /^\d+\.[A-Za-z0-9]+$/

function loadList(key: string): string[] {
  try {
    return JSON.parse(localStorage.getItem(key) ?? '[]') as string[]
  } catch {
    return []
  }
}

const hiddenSymbols = ref<Set<string>>(new Set(loadList(HIDDEN_KEY)))
const customSecids = ref<string[]>(loadList(CUSTOM_KEY))
const customQuotes = ref<IndexQuote[]>([])
const showManage = ref(false)
const newSecid = ref('')
const adding = ref(false)
const manageError = ref('')

interface IndexRow {
  key: string
  region: string
  name: string
  lastPrice: number | null
  changePercent: number | null
}

/** 展示行：未被隐藏的内置指数在前，自定义指数在后，一行一条 */
const indexRows = computed<IndexRow[]>(() => {
  const rows: IndexRow[] = indices.value
    .filter((i) => !hiddenSymbols.value.has(i.symbol))
    .map((i) => ({
      key: i.symbol,
      region: i.region ?? '海外',
      name: i.name,
      lastPrice: i.lastPrice,
      changePercent: i.changePercent,
    }))
  const customMap = new Map(customQuotes.value.map((q) => [q.symbol, q]))
  for (const secid of customSecids.value) {
    const q = customMap.get(secid)
    rows.push({
      key: `c:${secid}`,
      region: q?.region ?? '海外',
      name: q?.name ?? secid,
      lastPrice: q?.lastPrice ?? null,
      changePercent: q?.changePercent ?? null,
    })
  }
  return rows
})

function toggleHidden(symbol: string) {
  const next = new Set(hiddenSymbols.value)
  if (next.has(symbol)) next.delete(symbol)
  else next.add(symbol)
  hiddenSymbols.value = next
  localStorage.setItem(HIDDEN_KEY, JSON.stringify([...next]))
}

async function loadCustomQuotes() {
  if (!customSecids.value.length) {
    customQuotes.value = []
    return
  }
  try {
    customQuotes.value = await fetchCustomIndices(customSecids.value)
  } catch {
    // 自定义指数查询失败保留旧值，不影响内置指数
  }
}

async function addCustom() {
  manageError.value = ''
  const secid = newSecid.value.trim()
  if (!SECID_RE.test(secid)) {
    manageError.value = '格式不对：应为「市场.代码」，如 1.000688、100.DJIA'
    return
  }
  if (customSecids.value.includes(secid) || indices.value.some((i) => secid.endsWith(i.symbol))) {
    manageError.value = '该指数已在列表里'
    return
  }
  adding.value = true
  try {
    const [q] = await fetchCustomIndices([secid])
    if (!q || q.name == null) {
      manageError.value = '行情源没有这个代码，检查后重试'
      return
    }
    const next = [...customSecids.value, secid]
    customSecids.value = next
    localStorage.setItem(CUSTOM_KEY, JSON.stringify(next))
    customQuotes.value = [...customQuotes.value.filter((x) => x.symbol !== secid), q]
    newSecid.value = ''
  } catch (e) {
    manageError.value = e instanceof ApiError ? e.message : '查询失败，请重试'
  } finally {
    adding.value = false
  }
}

function removeCustom(secid: string) {
  const next = customSecids.value.filter((s) => s !== secid)
  customSecids.value = next
  localStorage.setItem(CUSTOM_KEY, JSON.stringify(next))
  customQuotes.value = customQuotes.value.filter((q) => q.symbol !== secid)
}

function customName(secid: string): string {
  return customQuotes.value.find((q) => q.symbol === secid)?.name ?? secid
}

function fmtPct(n: number | null): string {
  return n != null ? (n > 0 ? '+' : '') + n + '%' : '—'
}

/** 手动刷新指数：调度器只在 A 股交易窗口采集，海外指数收盘更新后用它拉最新值 */
async function onRefreshIndices() {
  refreshing.value = true
  try {
    indices.value = await refreshIndices()
  } catch {
    // 刷新失败保留旧值
  } finally {
    refreshing.value = false
  }
}

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
  void loadAlertStats()
  void loadCustomQuotes()
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

    <!-- 第一行：市场指标（紧凑单行列表，可自行添加） -->
    <div class="indices-block">
      <div class="indices-head">
        <span class="indices-title">全球指数</span>
        <div class="indices-actions">
          <button class="btn small" @click="showManage = true">添加</button>
          <button class="btn small" :disabled="refreshing" @click="onRefreshIndices">
            {{ refreshing ? '刷新中…' : '刷新' }}
          </button>
        </div>
      </div>
      <div v-if="indexRows.length" class="index-rows">
        <div v-for="row in indexRows" :key="row.key" class="index-row">
          <span class="index-region">{{ row.region }}</span>
          <span class="index-name">{{ row.name }}</span>
          <span class="index-price" :style="{ color: changeColor(row.changePercent) }">{{ row.lastPrice ?? '—' }}</span>
          <span class="index-change" :style="{ color: changeColor(row.changePercent) }">{{ fmtPct(row.changePercent) }}</span>
        </div>
      </div>
      <div v-else class="muted-line">没有要展示的指数 —— 点右上「添加」勾选想看的，或输入代码自定义</div>
    </div>

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

      <!-- 第三行：最近告警（上部告警统计 2×2） + 最新复盘 -->
      <div class="row-2">
        <div class="card clickable" @click="router.push('/alerts/history')">
          <div class="card-title">最近告警</div>
          <div v-if="alertStats" class="alert-stats">
            <div class="astat" @click.stop="router.push('/alerts/rules')">
              <div class="astat-num">{{ alertStats.rules }}</div>
              <div class="astat-label">规则总数</div>
            </div>
            <div class="astat" @click.stop="router.push('/alerts/rules')">
              <div class="astat-num">{{ alertStats.enabled }}</div>
              <div class="astat-label">启用中</div>
            </div>
            <div class="astat" @click.stop="router.push('/alerts/history')">
              <div class="astat-num">{{ alertStats.today }}</div>
              <div class="astat-label">今日事件</div>
            </div>
            <div class="astat" @click.stop="router.push('/alerts/history')">
              <div class="astat-num">{{ alertStats.total }}</div>
              <div class="astat-label">历史事件</div>
            </div>
          </div>
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

    <!-- 添加/管理指数弹窗：勾选内置指数 + 自定义 secid -->
    <div v-if="showManage" class="modal-mask" @click.self="showManage = false">
      <div class="modal">
        <h2>展示哪些指数</h2>
        <div class="check-grid">
          <label v-for="idx in indices" :key="idx.symbol" class="check-item">
            <input type="checkbox" :checked="!hiddenSymbols.has(idx.symbol)" @change="toggleHidden(idx.symbol)" />
            <span class="index-region">{{ idx.region ?? '海外' }}</span>
            {{ idx.name }}
          </label>
        </div>
        <template v-if="customSecids.length">
          <div class="manage-sub">自定义</div>
          <div v-for="secid in customSecids" :key="secid" class="custom-row">
            <span>{{ customName(secid) }} <span class="custom-secid">{{ secid }}</span></span>
            <button class="btn small danger" @click="removeCustom(secid)">移除</button>
          </div>
        </template>
        <div class="add-row">
          <input v-model="newSecid" placeholder="东财代码，如 1.000688" @keyup.enter="addCustom" />
          <button class="btn primary" :disabled="adding" @click="addCustom">{{ adding ? '查询中…' : '添加' }}</button>
        </div>
        <p class="hint">格式「市场.代码」：沪 1.、深 0.、海外 100.（如 100.DJIA）；名称与地区由行情源自动识别。自定义只存在本浏览器，不加进后端配置。</p>
        <div v-if="manageError" class="banner error">{{ manageError }}</div>
        <div class="modal-foot">
          <button class="btn primary" @click="showManage = false">完成</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 第一行：全球指数，紧凑单行列表 */
.indices-block {
  margin-bottom: 16px;
}
.indices-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.indices-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.indices-actions {
  display: flex;
  gap: 8px;
}
.index-rows {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 4px 16px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  column-gap: 36px;
}
.index-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  border-bottom: 1px dashed var(--border);
}
.index-row:nth-last-child(-n + 2) {
  border-bottom: none;
}
.index-region {
  font-size: 10px;
  color: var(--text-tertiary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
  flex-shrink: 0;
}
.index-name {
  flex: 1;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.index-price {
  font-weight: 600;
}
.index-change {
  width: 64px;
  text-align: right;
}
.muted-line {
  color: var(--text-tertiary);
  font-size: 12px;
  margin-bottom: 16px;
}
/* 管理指数弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}
.modal {
  width: 440px;
  max-width: 92vw;
  background: var(--bg-panel);
  border-radius: 12px;
  padding: 20px 24px;
}
.modal h2 {
  font-size: 16px;
  margin: 0 0 14px;
}
.check-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 12px;
}
.check-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
}
.manage-sub {
  font-size: 12px;
  color: var(--text-tertiary);
  margin: 14px 0 6px;
}
.custom-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  padding: 4px 0;
}
.custom-secid {
  font-family: Consolas, monospace;
  font-size: 11px;
  color: var(--text-tertiary);
}
.add-row {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
.add-row input {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 13px;
}
.hint {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 8px;
}
.btn.danger {
  color: #b91c1c;
}
.banner.error {
  background: #fdf2f2;
  color: #b91c1c;
  border: 1px solid #f8b4b4;
  border-radius: 8px;
  padding: 8px 12px;
  margin-top: 10px;
  font-size: 13px;
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
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
/* 最近告警卡片上部的告警统计：一行两个 */
.alert-stats {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}
.astat {
  background: var(--bg-hover);
  border-radius: 8px;
  padding: 10px 12px;
  text-align: center;
  cursor: pointer;
}
.astat:hover {
  background: var(--accent-soft);
}
.astat-num {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.astat-label {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 2px;
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
