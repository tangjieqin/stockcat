<script setup lang="ts">
/**
 * 告警历史页。对应 docs/UI.md「告警-历史」与 docs/API.md §5。
 * 事件展示规则快照（删除规则后历史仍可解释）与观测值。
 *
 * P2 场景二：每行可打开 AI 解释抽屉。解释严格基于事件快照——
 * 快照没保存的指标就说"没有保存"，不用今天的 K 线回算一个当时不存在的值。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  explainAlertEvent,
  formatParams,
  listAlertEvents,
  ruleTypeLabel,
  type AlertEvent,
  type AlertExplain,
} from '../api/alert'
import { ApiError } from '../api/client'

const router = useRouter()

const events = ref<AlertEvent[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const errorMsg = ref('')
const SIZE = 50

const explaining = ref<AlertEvent | null>(null)
const explain = ref<AlertExplain | null>(null)
const explainLoading = ref(false)
const explainError = ref('')

async function load(p = 0) {
  loading.value = true
  errorMsg.value = ''
  try {
    const resp = await listAlertEvents({ page: p, size: SIZE })
    events.value = resp.items
    total.value = resp.total
    page.value = p
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function fmtTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function observed(e: AlertEvent): string {
  if (e.observedPrice != null) return `¥${e.observedPrice}`
  if (e.observedChangePercent != null) return `${e.observedChangePercent}%`
  return '—'
}

async function openExplain(e: AlertEvent) {
  explaining.value = e
  explain.value = null
  explainError.value = ''
  explainLoading.value = true
  try {
    explain.value = await explainAlertEvent(e.id)
  } catch (err) {
    explainError.value =
      err instanceof ApiError
        ? err.message
        : 'AI 解释失败，请稍后重试（行情、告警、持仓等功能不受影响）'
  } finally {
    explainLoading.value = false
  }
}

function closeExplain() {
  explaining.value = null
  explain.value = null
  explainError.value = ''
}

/** 把上下文带进 AI 助手，继续追问（助手页支持 ?q= 预填并发送） */
function askMore() {
  const e = explaining.value
  if (!e) return
  const when = fmtTime(e.triggeredAt)
  const q = `${e.symbol} 在 ${when} 触发了“${e.ruleName}”，请结合这条告警和现在的行情、指标，说明当时的触发依据和现在的情况。`
  void router.push({ path: '/assistant', query: { q } })
}

onMounted(() => load(0))
</script>

<template>
  <div>
    <h1>告警历史</h1>
    <p class="sub">共 {{ total }} 条事件；每条含触发时的规则快照，规则删除后仍可解释。</p>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <div v-if="events.length" class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>触发时间</th>
            <th>标的</th>
            <th>规则</th>
            <th>条件（快照）</th>
            <th>触发时观测</th>
            <th>行情时间</th>
            <th>通知</th>
            <th>解释</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in events" :key="e.id">
            <td class="muted">{{ fmtTime(e.triggeredAt) }}</td>
            <td><span class="mono">{{ e.symbol }}</span></td>
            <td>
              {{ e.ruleName }}
              <span class="type-tag">{{ ruleTypeLabel(e.ruleType) }}</span>
            </td>
            <td class="cond">{{ formatParams(e.ruleType, e.paramsSnapshot) }}</td>
            <td class="mono">{{ observed(e) }}</td>
            <td class="muted">{{ fmtTime(e.quoteMarketTime) }}</td>
            <td>
              <template v-if="e.notifications.length">
                <span
                  v-for="n in e.notifications"
                  :key="n.id"
                  class="badge"
                  :class="n.status === 'SENT' ? 'ok' : n.status === 'FAILED' ? 'fail' : ''"
                >
                  {{ n.status === 'SENT' ? '已发送' : n.status === 'FAILED' ? '发送失败' : '发送中' }}
                </span>
              </template>
              <span v-else class="badge off">未通知</span>
            </td>
            <td>
              <button class="btn small" @click="openExplain(e)">AI 解释</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else-if="!loading" class="empty">暂无告警事件 —— 配置规则后，交易时段内触发将在此显示</div>
    <div v-else>加载中…</div>

    <div v-if="total > SIZE" class="pager">
      <button class="btn" :disabled="page === 0" @click="load(page - 1)">上一页</button>
      <span class="muted">第 {{ page + 1 }} 页</span>
      <button class="btn" :disabled="(page + 1) * SIZE >= total" @click="load(page + 1)">下一页</button>
    </div>

    <!-- AI 解释抽屉：不离开当前列表 -->
    <div v-if="explaining" class="drawer-mask" @click.self="closeExplain">
      <aside class="drawer">
        <header class="drawer-head">
          <div class="drawer-head-main">
            <div class="drawer-title">{{ explaining.symbol }} · {{ explaining.ruleName }}</div>
            <div class="drawer-sub">
              事件 #{{ explaining.id }} · 触发 {{ fmtTime(explaining.triggeredAt) }}
              <span v-if="explain?.ruleTypeLabel" class="type-tag">{{ explain.ruleTypeLabel }}</span>
            </div>
          </div>
          <button class="btn small" @click="closeExplain">关闭</button>
        </header>

        <div v-if="explainLoading" class="drawer-state">正在读取事件快照并生成解释…</div>
        <div v-else-if="explainError" class="banner error">{{ explainError }}</div>

        <template v-else-if="explain">
          <div v-if="!explain.snapshotComplete" class="banner warn">
            事件快照缺少关键观测值。下面会明确说明当时没有保存哪些数据，
            <strong>不会用今天的数据回算一个当时并不存在的值</strong>。
          </div>
          <div v-for="(reason, i) in explain.degraded" :key="i" class="banner warn">{{ reason }}</div>

          <section class="block">
            <h3>1. 为什么触发</h3>
            <p>{{ explain.why }}</p>
            <p v-if="explain.condition" class="cond-line">触发条件：{{ explain.condition }}</p>
          </section>

          <section class="block">
            <h3>2. 这意味着什么</h3>
            <p>{{ explain.meaning }}</p>
          </section>

          <section class="block">
            <h3>3. 当前状态</h3>
            <p>{{ explain.current }}</p>
          </section>

          <section class="block">
            <h3>4. 数据依据</h3>
            <ul v-if="explain.evidence.length" class="fact-list">
              <li v-for="(item, i) in explain.evidence" :key="i">{{ item }}</li>
            </ul>
            <p v-else class="muted">没有可引用的观测值。</p>
          </section>

          <section v-if="explain.limits.length" class="block">
            <h3>数据限制</h3>
            <ul class="limit-list">
              <li v-for="(item, i) in explain.limits" :key="i">{{ item }}</li>
            </ul>
          </section>
        </template>

        <footer class="drawer-foot">
          <span v-if="explain" class="mode-tag" :class="explain.mode">
            {{ explain.mode === 'llm' ? `模型改写措辞${explain.model ? ' · ' + explain.model : ''}` : '模板生成 · 未使用模型' }}
          </span>
          <span v-if="explain" class="muted small-text">生成于 {{ fmtTime(explain.generatedAt) }}</span>
          <button class="btn primary" :disabled="!explaining" @click="askMore">在 AI 助手中继续追问</button>
        </footer>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.sub {
  color: var(--text-tertiary);
  font-size: 13px;
  margin: -8px 0 16px;
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
}
.mono {
  font-family: Consolas, monospace;
}
.cond {
  font-weight: 600;
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
  margin-left: 4px;
}
.badge {
  font-size: 12px;
  border-radius: 4px;
  padding: 2px 8px;
}
.badge.ok {
  background: #ecfdf5;
  color: #047857;
}
.badge.fail {
  background: #fdf2f2;
  color: #b91c1c;
}
.badge.off {
  background: var(--bg-hover);
  color: var(--text-tertiary);
}
.badge + .badge {
  margin-left: 4px;
}
.empty {
  color: var(--text-tertiary);
  padding: 48px 0;
  text-align: center;
}
.banner.error {
  background: #fdf2f2;
  color: #b91c1c;
  border: 1px solid #f8b4b4;
  border-radius: 8px;
  padding: 8px 12px;
  margin: 10px 0;
  font-size: 13px;
}
.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  margin-top: 16px;
}
.btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.btn:disabled {
  opacity: 0.4;
  cursor: default;
}

/* ---------------------------------------------------------- AI 解释抽屉 */
.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  justify-content: flex-end;
  z-index: 60;
}
.drawer {
  width: 560px;
  max-width: 100vw;
  height: 100%;
  background: var(--bg-panel);
  border-left: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 18px 22px 0;
  box-sizing: border-box;
}
.drawer-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}
.drawer-head-main {
  flex: 1;
  min-width: 0;
}
.drawer-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}
.drawer-sub {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
}
.drawer-state {
  padding: 28px 0;
  color: var(--text-tertiary);
  font-size: 13px;
}
.banner.warn {
  background: #fffbeb;
  color: #92400e;
  border: 1px solid #fde68a;
  border-radius: 8px;
  padding: 8px 12px;
  margin: 12px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
}
.block {
  padding: 14px 0;
  border-bottom: 1px dashed var(--border);
}
.block h3 {
  font-size: 13px;
  margin: 0 0 6px;
  color: var(--text-secondary);
}
.block p {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.75;
}
.cond-line {
  margin-top: 6px !important;
  font-size: 12px !important;
  color: var(--text-tertiary);
}
.fact-list,
.limit-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
}
.limit-list {
  color: var(--text-tertiary);
}
.drawer-foot {
  position: sticky;
  bottom: 0;
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px 0;
  background: var(--bg-panel);
  border-top: 1px solid var(--border);
}
.drawer-foot .btn.primary {
  margin-left: auto;
}
.mode-tag {
  font-size: 11px;
  border-radius: 4px;
  padding: 2px 8px;
  background: var(--bg-hover);
  color: var(--text-tertiary);
}
.mode-tag.llm {
  background: var(--accent-soft);
  color: var(--accent);
}
.small-text {
  font-size: 11px;
}
</style>
