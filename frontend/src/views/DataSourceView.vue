<script setup lang="ts">
/**
 * 数据源页（P0 承诺）：数据从哪儿来、开没开、多久取一次、上次结果如何。
 *
 * 三条不写的：不显示任何凭据、不把没统计的源写成"正常"、不把采集时点快照说成收盘价。
 */
import { computed, onMounted, ref } from 'vue'
import { getSystemStatus, type DataSourceItem } from '../api/system'

const sources = ref<DataSourceItem[]>([])
const loading = ref(false)
const errorMsg = ref('')
const checkedAt = ref<Date | null>(null)

const enabledCount = computed(() => sources.value.filter((item) => item.enabled).length)
const failing = computed(() => sources.value.filter((item) => item.stats?.status === 'FAILED'))

function badgeClass(value: string | null | undefined): string {
  if (value === 'UP') return 'ok'
  if (value === 'FAILED' || value === 'DOWN' || value === 'DEGRADED') return 'bad'
  return 'off'
}

function label(value: string | null | undefined): string {
  if (!value || value === 'UNKNOWN') return '未运行'
  if (value === 'UP') return '正常'
  if (value === 'FAILED') return '失败'
  return value
}

function time(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function checkedText(): string {
  return checkedAt.value ? checkedAt.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—'
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await getSystemStatus()
    sources.value = data.dataSources ?? []
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
        <h1>数据源</h1>
        <p class="sub">
          共 {{ sources.length }} 个数据源，启用 {{ enabledCount }} 个。这里只读展示，改动在服务端配置里。
        </p>
      </div>
      <div class="actions">
        <button class="btn" :disabled="loading" @click="load">{{ loading ? '读取中…' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>
    <div v-else-if="failing.length" class="banner error">
      有 {{ failing.length }} 个数据源上次运行失败：{{ failing.map((item) => item.name).join('、') }}。
      失败只影响依赖它的功能，实时行情与告警不受影响。
    </div>

    <div v-if="!sources.length && !loading" class="empty">未取到数据源信息</div>

    <div class="source-grid">
      <section class="card source" v-for="item in sources" :key="item.key">
        <div class="source-head">
          <div>
            <h2>{{ item.name }}</h2>
            <div class="provider">{{ item.provider }}</div>
          </div>
          <div class="badges">
            <span class="badge" :class="item.enabled ? 'ok' : 'off'">{{ item.enabled ? '启用' : '停用' }}</span>
            <span class="badge" :class="badgeClass(item.stats?.status)">{{ label(item.stats?.status) }}</span>
          </div>
        </div>

        <dl class="kv">
          <dt>地址</dt>
          <dd class="mono">{{ item.baseUrl || '—' }}</dd>

          <dt>频率</dt>
          <dd>
            {{ item.schedule }}
            <span v-if="item.cron" class="cron mono" :title="item.cron">cron {{ item.cron }}</span>
          </dd>

          <template v-if="item.stats">
            <dt>上次运行</dt>
            <dd class="mono">{{ time(item.stats.lastAttemptAt) }}</dd>
            <dt>上次成功</dt>
            <dd class="mono">{{ time(item.stats.lastSuccessAt) }}</dd>
            <dt>结果</dt>
            <dd>
              {{ item.stats.summary || (item.stats.status === 'UNKNOWN' ? '本进程启动后还未运行过' : '—') }}
              <div v-if="item.stats.lastError" class="err-text">{{ item.stats.lastError }}</div>
            </dd>
          </template>
          <template v-else>
            <dt>运行统计</dt>
            <dd class="muted">无独立统计（随其他任务一起执行）</dd>
          </template>
        </dl>

        <p class="note">{{ item.note }}</p>
      </section>
    </div>

    <section class="card block">
      <h2>口径与小字</h2>
      <ul class="notes">
        <li>行情是<b>采集时点快照</b>，不是交易所官方收盘价；超过时限会标 STALE，缺失字段留空不补 0。</li>
        <li>基金净值是<b>日终净值</b>，T 日数据当晚才公布；LOF 的溢价率以它为准，ETF 盘中看 IOPV。</li>
        <li>这里不回显任何凭据；通知渠道的密钥在「通知配置」页只看"是否已配置"。</li>
        <li>任务统计是内存态，Java 重启后回到"未运行"，跑过一次才显示真实结果。</li>
      </ul>
      <p class="muted foot">上次刷新：{{ checkedText() }}</p>
    </section>
  </div>
</template>

<style scoped>
.source-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}
.source {
  display: flex;
  flex-direction: column;
}
.source-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 12px;
}
.source-head h2 {
  font-size: 15px;
  margin: 0 0 2px;
}
.provider {
  font-size: 12px;
  color: var(--text-tertiary);
}
.badges {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.kv {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 6px 10px;
  margin: 0 0 12px;
  font-size: 13px;
}
.kv dt {
  color: var(--text-tertiary);
  font-size: 12px;
  line-height: 1.7;
}
.kv dd {
  margin: 0;
  line-height: 1.7;
  word-break: break-all;
}
.cron {
  display: block;
  font-size: 11px;
  color: var(--text-tertiary);
}
.err-text {
  font-size: 12px;
  color: #b91c1c;
}
.note {
  margin: auto 0 0;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.block {
  margin-top: 12px;
}
.block h2 {
  font-size: 14px;
  margin: 0 0 8px;
}
.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.9;
}
.notes b {
  color: var(--text-primary);
}
.foot {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
