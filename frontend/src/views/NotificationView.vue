<script setup lang="ts">
/**
 * 通知配置页（P0.6）。渠道状态 / 测试发送 / 最近通知 / 失败重试。
 */
import { onMounted, ref } from 'vue'
import {
  listNotifications,
  notificationStatus,
  retryNotification,
  testNotification,
  type NotificationRecord,
} from '../api/notification'
import { ApiError } from '../api/client'

const records = ref<NotificationRecord[]>([])
const configured = ref<boolean | null>(null)
const loading = ref(false)
const testing = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const [recs, status] = await Promise.all([listNotifications(), notificationStatus()])
    records.value = recs
    configured.value = status.configured
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function sendTest() {
  testing.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const r = await testNotification()
    successMsg.value = r.status === 'SENT'
      ? '✅ 测试通知已发送，请查看微信「方糖」服务号'
      : `❌ 发送失败: ${r.lastErrorCode} ${r.lastErrorMessage ?? ''}`
    await load()
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '发送失败'
  } finally {
    testing.value = false
  }
}

async function retry(r: NotificationRecord) {
  try {
    await retryNotification(r.id)
    await load()
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '重试失败'
  }
}

function fmtTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function statusBadge(s: string): { text: string; cls: string } {
  if (s === 'SENT') return { text: '已发送', cls: 'ok' }
  if (s === 'FAILED') return { text: '失败', cls: 'bad' }
  return { text: '待发送', cls: 'stale' }
}

onMounted(load)
</script>

<template>
  <div>
    <h1>通知配置</h1>
    <p class="sub">告警触发后通过 Server酱 推送到微信；失败可手动重试，已成功不会重复发送。</p>

    <div v-if="successMsg" class="banner ok">{{ successMsg }}</div>
    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <div class="channel-card">
      <div class="channel-head">
        <div>
          <div class="channel-name">Server酱（SERVERCHAN）</div>
          <div class="channel-desc">推送到微信「方糖」服务号；免费版每天 5 条</div>
        </div>
        <span class="badge" :class="configured ? 'ok' : 'bad'">
          {{ configured == null ? '…' : configured ? '已配置' : '未配置' }}
        </span>
      </div>
      <button class="btn primary" :disabled="testing" @click="sendTest">
        {{ testing ? '发送中…' : '发送测试通知' }}
      </button>
      <p class="hint">SendKey 存放于项目根目录 .env（不提交 git）；换 key 后重启 Java 生效。</p>
    </div>

    <h2 class="section">最近通知</h2>
    <div v-if="records.length" class="table-wrap">
      <table class="table">
      <thead>
        <tr>
          <th>时间</th>
          <th>渠道</th>
          <th>状态</th>
          <th>尝试</th>
          <th>错误</th>
          <th>关联事件</th>
          <th style="width: 70px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in records" :key="r.id">
          <td class="muted">{{ fmtTime(r.createdAt) }}</td>
          <td>{{ r.channel }}</td>
          <td><span class="badge" :class="statusBadge(r.status).cls">{{ statusBadge(r.status).text }}</span></td>
          <td class="mono">{{ r.attemptCount }}</td>
          <td class="muted err-cell">{{ r.lastErrorCode ? r.lastErrorCode + ': ' + (r.lastErrorMessage ?? '') : '—' }}</td>
          <td>
            <span v-if="r.alertEventId" class="mono">#{{ r.alertEventId }}</span>
            <span v-else class="muted">测试</span>
          </td>
          <td>
            <button v-if="r.status === 'FAILED' && r.alertEventId" class="btn small" @click="retry(r)">重试</button>
          </td>
        </tr>
      </tbody>
    </table>
    </div>
    <div v-else-if="!loading" class="empty">暂无通知记录 —— 点「发送测试通知」验证渠道</div>
  </div>
</template>

<style scoped>
.sub {
  color: var(--text-tertiary);
  font-size: 13px;
  margin: -8px 0 16px;
}
.channel-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px 20px;
  margin-bottom: 24px;
}
.channel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.channel-name {
  font-weight: 700;
  font-size: 14px;
}
.channel-desc {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 2px;
}
.hint {
  font-size: 12px;
  color: var(--text-tertiary);
  margin: 10px 0 0;
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
.err-cell {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.badge.bad {
  background: #fdf2f2;
  color: #b91c1c;
}
.badge.stale {
  background: #fffbeb;
  color: #b45309;
}
.empty {
  color: var(--text-tertiary);
  padding: 40px 0;
  text-align: center;
}
.banner.ok {
  background: #ecfdf5;
  color: #047857;
  border: 1px solid #a7f3d0;
  border-radius: 8px;
  padding: 8px 12px;
  margin: 10px 0;
  font-size: 13px;
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
.btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.btn.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.btn.small {
  padding: 3px 8px;
}
</style>
