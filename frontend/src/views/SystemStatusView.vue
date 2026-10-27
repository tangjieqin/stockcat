<script setup lang="ts">
/**
 * 系统状态页（P0 承诺、P2 补完）：一眼看出"哪一块坏了"。
 *
 * 原则：所有状态都来自后端实际探测或实际运行记录，
 * **不写"看起来正常"**——没跑过就是 UNKNOWN，没配就是 NOT_CONFIGURED。
 * 页面上明确写出"重启后统计清零"，免得把 UNKNOWN 当成故障排查半天。
 */
import { computed, onMounted, ref } from 'vue'
import { getSystemStatus, type JobStatus, type SystemStatus } from '../api/system'

const status = ref<SystemStatus | null>(null)
const loading = ref(false)
const errorMsg = ref('')
const checkedAt = ref<Date | null>(null)

/** 任务短名 → 人话。key 与后端 JobRunStats 的常量一致 */
const JOB_LABELS: Record<string, string> = {
  'history-sync': '历史日 K 同步',
  'fund-nav-sync': '基金净值同步',
  'position-snapshot': '持仓快照重建',
  'indicator-alert': '指标告警评估',
}

const JOB_HINTS: Record<string, string> = {
  'history-sync': '交易日 15:35',
  'fund-nav-sync': '交易日 21:30 后',
  'position-snapshot': '交易日 15:45',
  'indicator-alert': '交易日 15:40',
}

const jobs = computed(() => {
  const map = status.value?.jobs ?? {}
  return Object.keys(map).map((key) => ({ key, label: JOB_LABELS[key] || key, hint: JOB_HINTS[key] || '', job: map[key] }))
})

const appOk = computed(() => status.value?.application === 'UP')
const dbState = computed(() => {
  const s = status.value?.database
  if (!s) return { text: '未知', cls: 'off' }
  if (s === 'UP') return { text: 'UP', cls: 'ok' }
  if (s === 'NOT_CONFIGURED') return { text: '未配置', cls: 'stale' }
  return { text: s, cls: 'bad' }
})
const quote = computed(() => status.value?.quoteCollector ?? null)
const python = computed(() => status.value?.pythonService ?? null)

const overall = computed(() => {
  if (!status.value) return { text: '未取到状态', cls: 'off' }
  if (status.value.database === 'DOWN') return { text: '数据库异常', cls: 'bad' }
  if (jobs.value.some((item) => item.job.status === 'FAILED')) return { text: '有任务失败', cls: 'stale' }
  if (quote.value?.status === 'DEGRADED') return { text: '采集降级', cls: 'stale' }
  return { text: '运行中', cls: 'ok' }
})

function badgeClass(value: string | null | undefined): string {
  if (value === 'UP') return 'ok'
  if (value === 'FAILED' || value === 'DOWN' || value === 'DEGRADED') return 'bad'
  if (value === 'NOT_CONFIGURED') return 'stale'
  return 'off'
}

function label(value: string | null | undefined): string {
  if (!value) return '未运行'
  if (value === 'UP') return '正常'
  if (value === 'FAILED') return '失败'
  if (value === 'DEGRADED') return '降级'
  if (value === 'DOWN') return '不可用'
  if (value === 'UNKNOWN') return '未运行'
  return value
}

function time(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function jobSummary(job: JobStatus): string {
  if (job.summary) return job.summary
  if (job.status === 'UNKNOWN') return '本进程启动后还未运行过'
  return '—'
}

function checkedText(): string {
  return checkedAt.value ? checkedAt.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    status.value = await getSystemStatus()
    checkedAt.value = new Date()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>系统状态</h1>
        <p class="sub">
          Java、PostgreSQL、行情采集、定时任务与 AI 服务的当前状态。任务统计是内存态，<b>重启后清零</b>。
        </p>
      </div>
      <div class="actions">
        <span class="badge" :class="overall.cls">{{ overall.text }}</span>
        <button class="btn" :disabled="loading" @click="load">{{ loading ? '检查中…' : '重新检查' }}</button>
      </div>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>
    <div v-else-if="status?.database === 'DOWN'" class="banner error">
      数据库连不上：行情、告警、持仓与复盘都会失败。先确认 PostgreSQL 已启动、`.env` 连接串正确。
    </div>

    <div v-if="status" class="status-grid">
      <div class="card">
        <div class="card-label">Java 应用</div>
        <div class="card-value"><span class="badge" :class="appOk ? 'ok' : 'bad'">{{ status.application }}</span></div>
        <p class="card-note">接口与定时任务都跑在这个进程里。</p>
      </div>
      <div class="card">
        <div class="card-label">PostgreSQL</div>
        <div class="card-value"><span class="badge" :class="dbState.cls">{{ dbState.text }}</span></div>
        <p class="card-note">每次检查会真的取一次连接（2s 超时），不是读缓存。</p>
      </div>
      <div class="card">
        <div class="card-label">实时行情采集</div>
        <div class="card-value">
          <span class="badge" :class="badgeClass(quote?.status)">{{ label(quote?.status) }}</span>
          <span v-if="quote?.lastErrorCode" class="mono err-code">{{ quote.lastErrorCode }}</span>
        </div>
        <p class="card-note">
          上次成功 {{ time(quote?.lastSuccessAt) }} · 连续失败 {{ quote?.consecutiveFailures ?? 0 }} 次 ·
          成功 {{ quote?.successCount ?? 0 }} / 尝试 {{ quote?.attemptCount ?? 0 }}
        </p>
      </div>
      <div class="card">
        <div class="card-label">AI 服务（Python）</div>
        <div class="card-value">
          <span class="badge" :class="badgeClass(python?.status)">{{ label(python?.status) }}</span>
          <span v-if="python?.agent" class="badge" :class="python.agent.modelConfigured ? 'ok' : 'off'">
            {{ python.agent.modelConfigured ? python.agent.model : '规则模式' }}
          </span>
        </div>
        <p class="card-note">{{ python?.error || `工具 ${python?.agent?.toolCount ?? 0} 个 · ${python?.baseUrl || '—'}` }}</p>
      </div>
    </div>

    <section class="card block" v-if="status">
      <h2>收盘后任务</h2>
      <p class="muted intro">这些任务失败都不影响实时行情与告警；这里只回答"上次跑没跑、成没成"。</p>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 160px">任务</th>
              <th style="width: 100px">状态</th>
              <th style="width: 170px">上次运行</th>
              <th style="width: 170px">上次成功</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in jobs" :key="item.key">
              <td>
                {{ item.label }}
                <div class="muted tiny">{{ item.hint }}</div>
              </td>
              <td><span class="badge" :class="badgeClass(item.job.status)">{{ label(item.job.status) }}</span></td>
              <td class="mono">{{ time(item.job.lastAttemptAt) }}</td>
              <td class="mono">{{ time(item.job.lastSuccessAt) }}</td>
              <td>
                <div :class="{ muted: item.job.status === 'UNKNOWN' }">{{ jobSummary(item.job) }}</div>
                <div v-if="item.job.lastError" class="err-text">{{ item.job.lastError }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card block" v-if="status">
      <h2>其他</h2>
      <ul class="notes">
        <li>已配置 {{ status.indicesConfigured }} 个指数，随实时行情一起采集。</li>
        <li>数据源明细（地址、频率、上次结果）见左侧「数据源」页。</li>
        <li>本页不含密码、token 与环境变量值；数据库状态只报 UP / DOWN / 未配置。</li>
      </ul>
      <p class="muted foot">上次检查：{{ checkedText() }}</p>
    </section>
  </div>
</template>

<style scoped>
.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
.card-label {
  font-size: 12px;
  color: var(--text-tertiary);
  letter-spacing: 0.5px;
}
.card-value {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0 6px;
  min-height: 24px;
  flex-wrap: wrap;
}
.card-note {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
  word-break: break-all;
}
.err-code {
  font-size: 11px;
  color: var(--bad, #b91c1c);
}
.block {
  margin-top: 12px;
}
.block h2 {
  font-size: 14px;
  margin: 0 0 4px;
}
.intro {
  margin: 0 0 10px;
  font-size: 12px;
}
.tiny {
  font-size: 11px;
}
.err-text {
  font-size: 12px;
  color: #b91c1c;
}
.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.9;
}
.foot {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
