<script setup lang="ts">
/**
 * AI 助手主页面（docs/UI.md「AI 助手」）。
 *
 * 一处关键取舍：无论模型是否配置，这个页面都必须能用。
 * - mode=llm        模型基于工具数据写结论
 * - mode=rule_based 未配置模型/模型失败 → 规则汇总，页面明确标注"不是模型分析"
 * 回答固定四块：分析结论 / 数据依据 / 执行过程（可折叠）/ 数据限制。
 * 会话只存在浏览器本地（P0 不建会话表），刷新不丢、不跨设备。
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import {
  askAssistant,
  getAssistantStatus,
  type AssistantReply,
  type AssistantStatus,
} from '../api/assistant'

const route = useRoute()
const router = useRouter()

const SUGGESTIONS = [
  '今天我的持仓发生了什么变化？',
  '最近哪些告警值得关注？',
  '根据现有数据说说市场情况。',
  '我存的资料里关于渠道库存怎么说？',
]

const STORAGE_KEY = 'stockcat.assistant.sessions.v1'

interface ChatMessage {
  role: 'user' | 'agent'
  text?: string
  reply?: AssistantReply
  at: string
}

interface Session {
  id: string
  title: string
  createdAt: string
  messages: ChatMessage[]
}

const sessions = ref<Session[]>([])
const currentId = ref('')
const draft = ref('')
const loading = ref(false)
const error = ref('')
const lastQuestion = ref('')
const status = ref<AssistantStatus | null>(null)
const chatBody = ref<HTMLElement | null>(null)

const current = computed(() => sessions.value.find((s) => s.id === currentId.value) ?? null)
const messages = computed(() => current.value?.messages ?? [])

const statusBadge = computed(() => {
  if (!status.value) return { cls: 'off', text: 'AI 状态未知' }
  if (!status.value.available) return { cls: 'bad', text: 'AI 服务未启动' }
  const agent = status.value.agent
  if (!agent) return { cls: 'bad', text: 'AI 服务异常' }
  return agent.modelConfigured
    ? { cls: 'ok', text: `模型已配置 · ${agent.model ?? ''}` }
    : { cls: 'stale', text: '未配置模型 · 规则汇总模式' }
})

onMounted(async () => {
  loadSessions()
  void loadStatus()
  const preset = route.query.q
  if (typeof preset === 'string' && preset.trim()) {
    await router.replace({ path: '/assistant' })
    await send(preset.trim())
  }
})

function loadSessions() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw ? (JSON.parse(raw) as Session[]) : []
    sessions.value = Array.isArray(parsed) ? parsed : []
  } catch {
    sessions.value = []
  }
  if (!sessions.value.length) {
    newSession()
  } else {
    currentId.value = sessions.value[0].id
  }
}

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value))
  } catch {
    // 本地存储满了也不该让对话中断
  }
}

async function loadStatus() {
  try {
    status.value = await getAssistantStatus()
  } catch {
    status.value = { available: false, baseUrl: '', error: '状态接口不可用' }
  }
}

function newSession() {
  const session: Session = {
    id: `s${Date.now()}`,
    title: '新对话',
    createdAt: new Date().toISOString(),
    messages: [],
  }
  sessions.value.unshift(session)
  currentId.value = session.id
  error.value = ''
  persist()
  return session
}

function selectSession(id: string) {
  currentId.value = id
  error.value = ''
  scrollToBottom()
}

function removeSession(id: string) {
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (currentId.value === id) {
    if (sessions.value.length) currentId.value = sessions.value[0].id
    else newSession()
  }
  persist()
}

async function send(text?: string) {
  const question = (text ?? draft.value).trim()
  if (!question || loading.value) return

  const session = current.value ?? newSession()
  error.value = ''
  lastQuestion.value = question
  draft.value = ''
  session.messages.push({ role: 'user', text: question, at: new Date().toISOString() })
  if (session.title === '新对话') {
    session.title = question.length > 18 ? `${question.slice(0, 18)}…` : question
  }
  persist()
  await scrollToBottom()

  loading.value = true
  try {
    const reply = await askAssistant(question)
    session.messages.push({ role: 'agent', reply, at: new Date().toISOString() })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'AI 请求失败，请重试'
  } finally {
    loading.value = false
    persist()
    await scrollToBottom()
  }
}

function retry() {
  if (lastQuestion.value) void send(lastQuestion.value)
}

function replyOf(message: ChatMessage): AssistantReply | null {
  return message.reply ?? null
}

function okCount(reply: AssistantReply): number {
  return reply.toolCalls.filter((t) => t.ok).length
}

function fmtTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function fmtDay(iso: string): string {
  const d = new Date(iso)
  const today = new Date()
  if (d.toDateString() === today.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  }
  return d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

async function scrollToBottom() {
  await nextTick()
  if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>AI 助手</h1>
        <p class="sub">
          根据问题查询持仓、行情、告警与复盘数据并汇总；每次都展示调用了哪些工具、数据截至何时。
        </p>
      </div>
      <div class="actions">
        <span class="badge" :class="statusBadge.cls">{{ statusBadge.text }}</span>
        <button class="btn" @click="newSession()">＋ 新建对话</button>
      </div>
    </div>

    <div class="assistant">
      <aside class="sessions">
        <div class="sessions-title">最近对话</div>
        <button
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === currentId }"
          @click="selectSession(s.id)"
        >
          <span class="session-name">{{ s.title }}</span>
          <span class="session-time">{{ fmtDay(s.createdAt) }}</span>
          <span class="session-del" title="删除对话" @click.stop="removeSession(s.id)">×</span>
        </button>
      </aside>

      <section class="chat">
        <div ref="chatBody" class="chat-body">
          <div v-if="!messages.length && !loading" class="welcome">
            <h2>StockCat Agent</h2>
            <p class="sub">持仓分析 · 告警解释 · 市场复盘。回答只依据系统里已有的数据。</p>
            <div class="suggest">
              <button v-for="q in SUGGESTIONS" :key="q" class="suggest-item" @click="send(q)">
                {{ q }}
              </button>
            </div>
            <p class="hint">
              数据来源：Java 只读工具（持仓 / 行情 / 告警 / 复盘 / 资料库）；不提供买卖建议。
            </p>
          </div>

          <template v-for="(m, i) in messages" :key="i">
            <div v-if="m.role === 'user'" class="msg user">
              <div class="bubble">{{ m.text }}</div>
            </div>
            <div v-else-if="replyOf(m)" class="msg agent">
              <div class="agent-head">
                <span class="badge" :class="replyOf(m)!.mode === 'llm' ? 'ok' : 'stale'">
                  {{ replyOf(m)!.mode === 'llm' ? '模型生成' : '规则汇总' }}
                </span>
                <span class="muted">
                  数据截至 {{ fmtTime(replyOf(m)!.dataAsOf) }} · 生成于 {{ fmtTime(replyOf(m)!.generatedAt) }}
                </span>
              </div>

              <div v-if="replyOf(m)!.degraded.length" class="banner warn">
                {{ replyOf(m)!.degraded.join('；') }}
              </div>

              <div class="card answer">
                <div class="section">
                  <div class="section-title">分析结论</div>
                  <p>{{ replyOf(m)!.answer.conclusion }}</p>
                </div>

                <div v-if="replyOf(m)!.answer.evidence.length" class="section">
                  <div class="section-title">数据依据</div>
                  <ul>
                    <li v-for="(ev, k) in replyOf(m)!.answer.evidence" :key="k">{{ ev }}</li>
                  </ul>
                </div>

                <!-- 引用由后端按 chunkId 拼装：点一下回到资料原文那一段，不靠模型自述出处 -->
                <div v-if="replyOf(m)!.citations?.length" class="section">
                  <div class="section-title">
                    资料引用（{{ replyOf(m)!.citations!.length }} 段）
                  </div>
                  <div class="citations">
                    <RouterLink
                      v-for="c in replyOf(m)!.citations"
                      :key="c.chunkId"
                      class="cite"
                      :to="`/library?doc=${c.documentId}&chunk=${c.seq}`"
                    >
                      {{ c.citation }}
                    </RouterLink>
                  </div>
                </div>

                <details class="section tools">
                  <summary>
                    执行过程（{{ okCount(replyOf(m)!) }}/{{ replyOf(m)!.toolCalls.length }} 成功 ·
                    {{ replyOf(m)!.elapsedMs }}ms）
                  </summary>
                  <div v-for="(t, k) in replyOf(m)!.toolCalls" :key="k" class="tool-row">
                    <span class="badge" :class="t.ok ? 'ok' : 'bad'">{{ t.ok ? '成功' : '失败' }}</span>
                    <span>{{ t.label }}</span>
                    <span class="muted">{{ t.summary ?? t.error ?? '' }}</span>
                    <span class="muted">{{ t.elapsedMs != null ? `${t.elapsedMs}ms` : '' }}</span>
                  </div>
                </details>

                <div v-if="replyOf(m)!.answer.limits.length" class="section">
                  <div class="section-title">数据限制</div>
                  <ul>
                    <li v-for="(lim, k) in replyOf(m)!.answer.limits" :key="k">{{ lim }}</li>
                  </ul>
                </div>
              </div>
            </div>
          </template>

          <div v-if="loading" class="msg agent">
            <div class="bubble plain">正在查询数据并整理结论…</div>
          </div>

          <div v-if="error" class="banner error">
            {{ error }}
            <button v-if="lastQuestion" class="btn small" style="margin-left: 8px" @click="retry()">重试</button>
          </div>
        </div>

        <div class="composer">
          <textarea
            v-model="draft"
            rows="2"
            placeholder="输入问题，例如：分析我的持仓和最近的告警…"
            @keydown.enter.exact.prevent="send()"
          />
          <button class="btn primary" :disabled="!draft.trim() || loading" @click="send()">
            {{ loading ? '生成中…' : '发送' }}
          </button>
        </div>
        <div class="composer-hint">
          回答基于系统可读取的数据；行情存在采集延迟，AI 不提供买卖建议、不修改任何业务数据。
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.assistant {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 16px;
  height: calc(100vh - 150px);
  min-height: 460px;
}
.sessions {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-panel);
  padding: 10px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sessions-title {
  font-size: 12px;
  color: var(--text-tertiary);
  padding: 2px 6px 6px;
}
.session-item {
  position: relative;
  text-align: left;
  border: none;
  background: transparent;
  border-radius: 6px;
  padding: 8px 10px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-family: inherit;
}
.session-item:hover {
  background: var(--bg-hover);
}
.session-item.active {
  background: var(--accent-soft);
}
.session-name {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: 14px;
  max-width: 100%;
}
.session-time {
  font-size: 11px;
  color: var(--text-tertiary);
}
.session-del {
  position: absolute;
  right: 8px;
  top: 6px;
  color: var(--text-tertiary);
  opacity: 0;
  font-size: 14px;
}
.session-item:hover .session-del {
  opacity: 1;
}
.chat {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-panel);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.welcome {
  margin: auto;
  text-align: center;
  max-width: 520px;
}
.welcome h2 {
  font-size: 17px;
  margin: 0 0 6px;
}
.welcome .sub {
  color: var(--text-secondary);
  font-size: 13px;
  margin: 0;
}
.suggest {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 16px 0 12px;
}
.suggest-item {
  text-align: left;
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
  color: var(--text-primary);
}
.suggest-item:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.msg.user {
  display: flex;
  justify-content: flex-end;
}
.msg.user .bubble {
  background: var(--accent);
  color: #fff;
  padding: 8px 12px;
  border-radius: 10px 10px 2px 10px;
  max-width: 78%;
  font-size: 13px;
  white-space: pre-wrap;
}
.bubble.plain {
  background: var(--bg-hover);
  color: var(--text-secondary);
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 13px;
}
.agent-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.answer .section + .section {
  margin-top: 12px;
}
.section-title {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 600;
  margin-bottom: 4px;
}
.citations {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.cite {
  font-size: 12px;
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-secondary);
  text-decoration: none;
  background: var(--bg-subtle, transparent);
}
.cite:hover {
  border-color: var(--accent, #2563eb);
  color: var(--accent, #2563eb);
}
.answer p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
}
.answer ul {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}
.tools summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary);
}
.tool-row {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  padding: 4px 0;
  flex-wrap: wrap;
}
.composer {
  border-top: 1px solid var(--border);
  padding: 10px 12px;
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.composer textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.5;
}
.composer-hint {
  font-size: 11px;
  color: var(--text-tertiary);
  padding: 0 12px 10px;
}
.banner.warn {
  background: #fffbeb;
  color: #b45309;
  border: 1px solid #fde68a;
}
@media (max-width: 860px) {
  .assistant {
    grid-template-columns: 1fr;
    height: auto;
  }
  .sessions {
    max-height: 160px;
  }
  .chat {
    height: calc(100vh - 300px);
    min-height: 420px;
  }
}
</style>
