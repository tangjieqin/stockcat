<script setup lang="ts">
/**
 * AI 配置页（P2.5）：只读展示"AI 现在能不能用、用的是哪个模型、能查哪些数据"。
 *
 * 故意**不提供**在页面里填 API Key：key 只走服务端 `.env`，
 * 一旦能在浏览器里填，它就必然会被存到浏览器或随请求穿透到前端代码里。
 * 这里给出的是一条明确的操作路径，而不是一个看起来方便的输入框。
 */
import { computed, onMounted, ref } from 'vue'
import { getAssistantStatus, type AssistantStatus } from '../api/assistant'

const status = ref<AssistantStatus | null>(null)
const loading = ref(false)
const errorMsg = ref('')
const checkedAt = ref<Date | null>(null)

/** 工具短名 → 人话。和 backend-python/app/agent/service.py 的 _TOOL_LABELS 保持一致 */
const TOOL_LABELS: Record<string, string> = {
  'market-brief': '市场概况',
  positions: '我的持仓',
  'watchlist-quotes': '自选行情',
  'alert-events': '告警事件',
  recaps: '历史复盘',
  'diary-search': '投资日记',
}

const agent = computed(() => status.value?.agent ?? null)
const available = computed(() => Boolean(status.value?.available))
const modelConfigured = computed(() => Boolean(agent.value?.modelConfigured))
const modelName = computed(() => agent.value?.model || '未设置')
const tools = computed(() => agent.value?.tools ?? [])

const overall = computed(() => {
  if (!available.value) return { text: 'AI 服务未启动', cls: 'bad' }
  if (!modelConfigured.value) return { text: '规则汇总模式', cls: 'stale' }
  return { text: '模型已启用', cls: 'ok' }
})

const checkedText = computed(() =>
  checkedAt.value ? checkedAt.value.toLocaleTimeString('zh-CN', { hour12: false }) : '—',
)

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    status.value = await getAssistantStatus()
    checkedAt.value = new Date()
  } catch (e) {
    status.value = null
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
        <h1>AI 配置</h1>
        <p class="sub">
          这里是只读状态页：AI 服务是否可达、模型是否配置、AI 能查哪些数据。
          配置项都在服务端 <code>.env</code> 里，改完需重启 backend-python。
        </p>
      </div>
      <div class="actions">
        <span class="badge" :class="overall.cls">{{ overall.text }}</span>
        <button class="btn" :disabled="loading" @click="load">
          {{ loading ? '检查中…' : '重新检查' }}
        </button>
      </div>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <!-- 三张状态卡 -->
    <div class="status-grid">
      <div class="card">
        <div class="card-label">Python AI 服务</div>
        <div class="card-value">
          <span class="badge" :class="available ? 'ok' : 'bad'">{{ available ? 'UP' : 'DOWN' }}</span>
          <span class="mono addr">{{ status?.baseUrl || '—' }}</span>
        </div>
        <p class="card-note">
          {{
            available
              ? '服务可达。行情、告警、持仓不依赖它，停了不影响这些功能。'
              : '未连上。请先启动 backend-python（uvicorn app.main:app --port 8100）。'
          }}
        </p>
      </div>

      <div class="card">
        <div class="card-label">大模型</div>
        <div class="card-value">
          <span class="badge" :class="modelConfigured ? 'ok' : 'off'">
            {{ modelConfigured ? '已配置' : '未配置' }}
          </span>
          <span class="mono">{{ modelName }}</span>
        </div>
        <p class="card-note">
          {{
            modelConfigured
              ? '回答由模型撰写，数值仍只取自业务数据；数据限制恒由模板生成。'
              : '当前为规则汇总模式：内容由真实数据拼成，没有模型写的分析段落，也不会伪造 AI 结果。'
          }}
        </p>
      </div>

      <div class="card">
        <div class="card-label">只读工具</div>
        <div class="card-value">
          <span class="card-number">{{ tools.length }}</span>
          <span class="muted">个已开放</span>
        </div>
        <p class="card-note">全部 GET、全部只读；AI 改不了流水、规则和通知。</p>
      </div>
    </div>

    <!-- 工具白名单 -->
    <section class="card block">
      <h2>AI 能查什么</h2>
      <p class="muted intro">
        白名单硬编码在 <code>backend-python/app/agent/java_tools.py</code>，加工具必须同时改代码与 Java 侧接口。
      </p>
      <div v-if="tools.length" class="chips">
        <span v-for="t in tools" :key="t" class="chip">
          <span class="chip-name">{{ TOOL_LABELS[t] || t }}</span>
          <span class="chip-code mono">{{ t }}</span>
        </span>
      </div>
      <div v-else class="empty">未取到工具清单（AI 服务不可用）</div>
    </section>

    <!-- 四个 AI 场景 -->
    <section class="card block">
      <h2>已接入的四个场景</h2>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 140px">场景</th>
              <th style="width: 180px">入口</th>
              <th>有模型时</th>
              <th>无模型 / 模型失败时</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>AI 助手问答</td>
              <td>侧边栏「AI 助手」、首页卡片</td>
              <td>关键词选工具 → 取真实数据 → 模型写结论</td>
              <td>规则汇总，标注「未配置模型」，四段里含数据限制</td>
            </tr>
            <tr>
              <td>告警 AI 解释</td>
              <td>告警历史每行 [AI 解释]</td>
              <td>模型只改写「为什么触发 / 这意味着什么」</td>
              <td>模板生成；快照缺指标时连模型都不启用</td>
            </tr>
            <tr>
              <td>每日复盘草稿</td>
              <td>市场复盘页「✨ AI 生成草稿」</td>
              <td>固定取 5 类数据 → 模型成文 → 存为 AI 草稿</td>
              <td>规则底稿；缺任一段整篇退回底稿，不拼半截</td>
            </tr>
            <tr>
              <td>日记整理 / 回顾</td>
              <td>我的日记页「✨ AI 整理」「AI 回顾」</td>
              <td>整理只读本篇正文；回顾先检索再归纳，引用只认命中篇目</td>
              <td>规则提取要点与标的（不给改写稿）；回顾只罗列检索结果</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 配置方法 -->
    <section class="card block">
      <h2>怎么接入模型</h2>
      <p class="muted intro">
        在 <code>backend-python/.env</code> 里填三项，任一项为空即视为未配置。Key 只在服务端，接口与日志都不回显。
      </p>
      <pre class="code">STOCKCAT_LLM_BASE_URL=https://api.example.com/v1
STOCKCAT_LLM_MODEL=gpt-4o-mini
STOCKCAT_LLM_API_KEY=sk-****</pre>
      <ul class="notes">
        <li>接口按 OpenAI 兼容协议调用 <code>/chat/completions</code>，换供应商只改这三项。</li>
        <li>改完重启 backend-python 生效；本页「重新检查」只探状态，不会加载新配置。</li>
        <li>本页不提供填写 Key 的输入框：能在浏览器填的 Key，迟早会被存到浏览器里。</li>
      </ul>
    </section>

    <!-- 边界 -->
    <section class="card block">
      <h2>边界与小字</h2>
      <ul class="notes">
        <li><b>只读</b>：AI 只能调上面的白名单接口，不改交易流水、不改告警规则、不发通知。</li>
        <li><b>不写库</b>：生成的内容一律是草稿，落库与确认由 Java 侧完成，Python 连不上数据库。</li>
        <li><b>可降级</b>：模型超时或报错不会重试（避免双计费），直接退回规则模式并写明原因。</li>
        <li><b>可溯源</b>：回答与草稿都带生成时间、数据截至时间、数据限制；复核时先看这三项。</li>
        <li><b>日志</b>：只记场景、模型、耗时、工具、token、错误码，不记 Key、不记 prompt 与回答原文。</li>
      </ul>
      <p class="muted foot">
        上次检查：{{ checkedText }}
        <span v-if="status && !status.available && status.error"> · {{ status.error }}</span>
      </p>
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
}
.card-number {
  font-size: 22px;
  font-weight: 600;
  line-height: 1;
}
.card-note {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}
.addr {
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
}
.block {
  margin-top: 12px;
}
.block h2 {
  font-size: 14px;
  margin: 0 0 4px;
}
.intro {
  margin: 0 0 12px;
  font-size: 12px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 4px 10px;
  background: var(--bg);
}
.chip-name {
  font-size: 13px;
}
.chip-code {
  font-size: 11px;
  color: var(--text-tertiary);
}
.code {
  margin: 0 0 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--bg);
  border: 1px solid var(--border);
  font-size: 12px;
  line-height: 1.8;
  overflow-x: auto;
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
  font-weight: 600;
}
.foot {
  margin: 12px 0 0;
  font-size: 12px;
}
code {
  font-size: 12px;
  background: var(--bg-hover);
  border-radius: 4px;
  padding: 1px 4px;
}
</style>
