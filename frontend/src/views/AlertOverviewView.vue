<script setup lang="ts">
/**
 * 告警总览页（P0.9）。规则/事件统计 + 最近事件。
 * P0.6 起展示通知渠道状态与每条事件的通知结果。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  formatParams,
  listAlertEvents,
  listAlertRules,
  ruleTypeLabel,
  type AlertEvent,
  type AlertRule,
} from '../api/alert'
import { notificationStatus } from '../api/notification'

const router = useRouter()
const rules = ref<AlertRule[]>([])
const events = ref<AlertEvent[]>([])
const totalEvents = ref(0)
const channelOk = ref<boolean | null>(null)
const loading = ref(false)
const errorMsg = ref('')

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const [rulePage, eventPage, status] = await Promise.all([
      listAlertRules({ size: 100 }),
      listAlertEvents({ size: 10 }),
      notificationStatus().catch(() => null),
    ])
    rules.value = rulePage.items
    events.value = eventPage.items
    totalEvents.value = eventPage.total
    channelOk.value = status?.configured ?? null
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/** 事件通知徽标状态：存在 FAILED 优先展示失败 */
function notifyBadge(e: AlertEvent): { cls: string; text: string } {
  if (!e.notifications.length) return { cls: 'dim2', text: '未通知' }
  if (e.notifications.some((n) => n.status === 'FAILED')) return { cls: 'fail', text: '发送失败' }
  if (e.notifications.some((n) => n.status === 'SENT')) return { cls: 'ok', text: '已发送' }
  return { cls: 'dim2', text: '发送中' }
}

function fmtTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function todayCount(): number {
  const today = new Date().toDateString()
  return events.value.filter((e) => new Date(e.triggeredAt).toDateString() === today).length
}

onMounted(load)
</script>

<template>
  <div>
    <h1>告警总览</h1>
    <p class="sub">告警体系的统一入口；规则触发后经 Server酱 推送到微信。</p>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <div class="stats">
      <div class="stat" @click="router.push('/alerts/rules')">
        <div class="stat-num">{{ rules.length }}</div>
        <div class="stat-label">规则总数</div>
      </div>
      <div class="stat" @click="router.push('/alerts/rules')">
        <div class="stat-num">{{ rules.filter((r) => r.enabled).length }}</div>
        <div class="stat-label">启用中</div>
      </div>
      <div class="stat" @click="router.push('/alerts/history')">
        <div class="stat-num">{{ todayCount() }}</div>
        <div class="stat-label">今日事件</div>
      </div>
      <div class="stat" @click="router.push('/alerts/history')">
        <div class="stat-num">{{ totalEvents }}</div>
        <div class="stat-label">历史事件</div>
      </div>
      <div class="stat" :class="{ dim: channelOk === null }" @click="channelOk !== null && router.push('/settings/notification')">
        <div class="stat-num">{{ channelOk === null ? '—' : channelOk ? '✓' : '✗' }}</div>
        <div class="stat-label">通知渠道</div>
      </div>
    </div>

    <h2 class="section">最近事件</h2>
    <div v-if="events.length" class="table-wrap">
      <table class="table">
      <thead>
        <tr>
          <th>触发时间</th>
          <th>标的</th>
          <th>规则</th>
          <th>条件</th>
          <th>观测</th>
          <th>通知</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="e in events" :key="e.id">
          <td class="muted">{{ fmtTime(e.triggeredAt) }}</td>
          <td><span class="mono">{{ e.symbol }}</span></td>
          <td>{{ e.ruleName }} <span class="type-tag">{{ ruleTypeLabel(e.ruleType) }}</span></td>
          <td>{{ formatParams(e.ruleType, e.paramsSnapshot) }}</td>
          <td class="mono">{{ e.observedPrice ?? e.observedChangePercent ?? '—' }}</td>
          <td><span class="badge" :class="notifyBadge(e).cls">{{ notifyBadge(e).text }}</span></td>
        </tr>
      </tbody>
    </table>
    </div>
    <div v-else-if="!loading" class="empty">暂无事件 —— 去「规则」页配置第一条告警</div>
  </div>
</template>

<style scoped>
.sub {
  color: var(--text-tertiary);
  font-size: 13px;
  margin: -8px 0 16px;
}
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 12px;
  margin-bottom: 24px;
}
.stat {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px;
  text-align: center;
  cursor: pointer;
}
.stat.dim {
  opacity: 0.5;
  cursor: default;
}
.stat-num {
  font-size: 24px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 2px;
}
.section {
  font-size: 15px;
  margin: 0 0 10px;
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
  padding: 9px 14px;
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
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 12px;
}
.badge.ok {
  background: #ecfdf5;
  color: #047857;
}
.badge.fail {
  background: #fdf2f2;
  color: #b91c1c;
}
.badge.dim2 {
  background: var(--bg-hover);
  color: var(--text-tertiary);
}
.empty {
  color: var(--text-tertiary);
  padding: 40px 0;
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
</style>
