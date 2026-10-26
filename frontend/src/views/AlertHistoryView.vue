<script setup lang="ts">
/**
 * 告警历史页。对应 docs/UI.md「告警-历史」与 docs/API.md §5。
 * 事件展示规则快照（删除规则后历史仍可解释）与观测值。
 */
import { onMounted, ref } from 'vue'
import { formatParams, listAlertEvents, ruleTypeLabel, type AlertEvent } from '../api/alert'

const events = ref<AlertEvent[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const errorMsg = ref('')
const SIZE = 50

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

onMounted(() => load(0))
</script>

<template>
  <div>
    <h1>告警历史</h1>
    <p class="sub">共 {{ total }} 条事件；每条含触发时的规则快照，规则删除后仍可解释。</p>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <table v-if="events.length" class="table">
      <thead>
        <tr>
          <th>触发时间</th>
          <th>标的</th>
          <th>规则</th>
          <th>条件（快照）</th>
          <th>触发时观测</th>
          <th>行情时间</th>
          <th>通知</th>
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
              <span v-for="n in e.notifications" :key="n.id" class="badge" :class="n.status === 'SENT' ? 'ok' : n.status === 'FAILED' ? 'fail' : ''">
                {{ n.status === 'SENT' ? '已发送' : n.status === 'FAILED' ? '发送失败' : '发送中' }}
              </span>
            </template>
            <span v-else class="badge off">未通知</span>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else-if="!loading" class="empty">暂无告警事件 —— 配置规则后，交易时段内触发将在此显示</div>
    <div v-else>加载中…</div>

    <div v-if="total > SIZE" class="pager">
      <button class="btn" :disabled="page === 0" @click="load(page - 1)">上一页</button>
      <span class="muted">第 {{ page + 1 }} 页</span>
      <button class="btn" :disabled="(page + 1) * SIZE >= total" @click="load(page + 1)">下一页</button>
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
</style>
