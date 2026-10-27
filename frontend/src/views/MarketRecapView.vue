<script setup lang="ts">
/**
 * 市场复盘页。对应 docs/UI.md 2.9 与 docs/API.md §7。
 * 左侧日期列表（倒序）+ 右侧编辑器（市场主要情况 / 板块表现 / 正文分区）。
 *
 * AI 草稿是一条独立的支线：生成（不落库）→ 弹窗内可编辑 → 保存为 AI_DRAFT →
 * 在编辑器里继续改、点保存才转 MANUAL。页面任何位置都明确标出「数据截至」与「数据限制」。
 */
import { computed, onMounted, ref } from 'vue'
import {
  createRecap,
  deleteRecap,
  generateRecapDraft,
  getRecap,
  listRecaps,
  saveRecapDraft,
  updateRecap,
  type Recap,
  type RecapDraftResult,
} from '../api/recap'
import { ApiError } from '../api/client'

const recaps = ref<Recap[]>([])
const current = ref<Recap | null>(null)
const loading = ref(false)
const errorMsg = ref('')
const dirty = ref(false)

// 编辑态
const edit = ref({ title: '', marketSummary: '', sectorNotes: '', content: '' })
const saving = ref(false)

// 新建态
const showCreate = ref(false)
const createForm = ref({ recapDate: today(), title: '', content: '' })
const createError = ref('')

// AI 草稿态
const aiBusy = ref(false)
const aiSaving = ref(false)
const aiError = ref('')
const aiResult = ref<RecapDraftResult | null>(null)
const aiForm = ref({ title: '', marketSummary: '', sectorNotes: '', content: '' })

function today(): string {
  const now = new Date()
  // 用本地时间拼日期：toISOString 是 UTC，早上 8 点前会算成昨天
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/** 生成目标的日期：选中哪篇就生成哪天，没选中就生成今天 */
const targetDate = computed(() => current.value?.recapDate ?? today())
const manualOnTarget = computed(() =>
  recaps.value.some((r) => r.recapDate === targetDate.value && r.status === 'MANUAL'),
)
const aiDisabledReason = computed(() =>
  manualOnTarget.value ? `${targetDate.value} 已写过正式复盘，AI 草稿不会覆盖人工内容` : '',
)

function fmt(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    recaps.value = (await listRecaps({ size: 100 })).items
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function open(id: number) {
  try {
    current.value = await getRecap(id)
    edit.value = {
      title: current.value.title,
      marketSummary: current.value.marketSummary ?? '',
      sectorNotes: current.value.sectorNotes ?? '',
      content: current.value.content,
    }
    dirty.value = false
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

async function submitCreate() {
  createError.value = ''
  if (!createForm.value.title.trim()) return (createError.value = '请输入标题')
  if (!createForm.value.content.trim()) return (createError.value = '请输入正文')
  saving.value = true
  try {
    const created = await createRecap({
      recapDate: createForm.value.recapDate,
      title: createForm.value.title.trim(),
      content: createForm.value.content.trim(),
    })
    showCreate.value = false
    createForm.value = { recapDate: today(), title: '', content: '' }
    await load()
    await open(created.id)
  } catch (e) {
    createError.value = e instanceof ApiError ? e.message : '创建失败'
  } finally {
    saving.value = false
  }
}

async function save() {
  if (!current.value) return
  errorMsg.value = ''
  saving.value = true
  try {
    current.value = await updateRecap(current.value.id, {
      title: edit.value.title,
      marketSummary: edit.value.marketSummary,
      sectorNotes: edit.value.sectorNotes,
      content: edit.value.content,
    })
    dirty.value = false
    await load()
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(r: Recap) {
  if (!confirm(`确定删除 ${r.recapDate} 的复盘「${r.title}」吗？`)) return
  try {
    await deleteRecap(r.id)
    if (current.value?.id === r.id) current.value = null
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

function markDirty() {
  dirty.value = true
}

// ------------------------------------------------------------------ AI 草稿

async function generateDraft() {
  if (aiDisabledReason.value) return
  aiError.value = ''
  aiBusy.value = true
  try {
    const result = await generateRecapDraft(targetDate.value)
    aiResult.value = result
    aiForm.value = { ...result.draft }
  } catch (e) {
    aiError.value = e instanceof ApiError ? e.message : 'AI 生成失败，请稍后重试'
  } finally {
    aiBusy.value = false
  }
}

async function persistDraft() {
  if (!aiResult.value) return
  if (!aiForm.value.title.trim()) return (aiError.value = '请填写标题')
  if (!aiForm.value.content.trim()) return (aiError.value = '请填写正文')
  aiError.value = ''
  aiSaving.value = true
  try {
    const saved = await saveRecapDraft({
      recapDate: aiResult.value.date,
      title: aiForm.value.title.trim(),
      marketSummary: aiForm.value.marketSummary,
      sectorNotes: aiForm.value.sectorNotes,
      content: aiForm.value.content,
      generatedAt: aiResult.value.generatedAt,
      model: aiResult.value.model,
      dataAsOf: aiResult.value.dataAsOf,
      limitations: aiResult.value.limitations,
    })
    aiResult.value = null
    await load()
    await open(saved.id)
  } catch (e) {
    aiError.value = e instanceof ApiError ? e.message : '保存草稿失败'
  } finally {
    aiSaving.value = false
  }
}

function closeDraft() {
  aiResult.value = null
  aiError.value = ''
}

onMounted(async () => {
  await load()
  if (recaps.value.length) await open(recaps.value[0].id)
})
</script>

<template>
  <div class="recap-page">
    <div class="page-head">
      <div>
        <h1>市场复盘</h1>
        <p class="sub">一天一篇；AI 只起草稿，编辑保存后才成为正式复盘</p>
      </div>
      <div class="actions">
        <button class="btn" :disabled="aiBusy || !!aiDisabledReason" :title="aiDisabledReason"
                @click="generateDraft">
          {{ aiBusy ? 'AI 生成中…' : '✨ AI 生成草稿' }}
        </button>
        <button class="btn primary" @click="showCreate = true">＋ 新建</button>
      </div>
    </div>
    <div v-if="aiDisabledReason" class="hint ai-hint">{{ aiDisabledReason }}</div>
    <div v-if="aiError && !aiResult" class="banner error">{{ aiError }}</div>

    <div class="recap-layout">
      <!-- 左：日期列表 -->
      <aside class="list-pane">
        <div class="pane-head"><span>复盘列表</span></div>
        <div v-for="r in recaps" :key="r.id" class="recap-item"
             :class="{ active: current?.id === r.id }" @click="open(r.id)">
          <div class="date">{{ r.recapDate }}</div>
          <div class="title">{{ r.title }}</div>
          <div class="meta">
            <span v-if="r.status === 'AI_DRAFT'" class="badge stale">AI 草稿</span>
            <span v-else-if="r.aiGeneratedAt" class="badge off">AI 起草·已确认</span>
            <span class="del" @click.stop="remove(r)">删除</span>
          </div>
        </div>
        <div v-if="!recaps.length && !loading" class="empty">暂无复盘</div>
      </aside>

      <!-- 右：编辑器 -->
      <section class="editor-pane" v-if="current">
        <div v-if="current.status === 'AI_DRAFT'" class="banner draft">
          <div>
            这是 AI 草稿，尚未计入正式复盘。核对并修改后点「保存」，即转为正式复盘（MANUAL）。
          </div>
          <div class="ai-meta">
            <span>生成时间：{{ fmt(current.aiGeneratedAt) }}</span>
            <span>数据截至：{{ fmt(current.aiDataAsOf) }}</span>
            <span v-if="current.aiModel">模型：{{ current.aiModel }}</span>
            <span v-else>规则底稿</span>
          </div>
          <ul v-if="current.aiLimitations?.length" class="limits">
            <li v-for="l in current.aiLimitations" :key="l">{{ l }}</li>
          </ul>
        </div>
        <div v-else-if="current.aiGeneratedAt" class="hint ai-hint">
          本复盘由 AI 起草并于 {{ fmt(current.aiGeneratedAt) }} 生成，数据截至 {{ fmt(current.aiDataAsOf) }}；
          已人工确认。
        </div>

        <input v-model="edit.title" class="title-input" @input="markDirty" placeholder="标题" />
        <div class="zones">
          <div class="zone">
            <div class="zone-label">市场主要情况</div>
            <textarea v-model="edit.marketSummary" rows="3" @input="markDirty"
                      placeholder="指数涨跌、成交量、市场情绪……"></textarea>
          </div>
          <div class="zone">
            <div class="zone-label">板块表现</div>
            <textarea v-model="edit.sectorNotes" rows="3" @input="markDirty"
                      placeholder="领涨/领跌板块、轮动方向……"></textarea>
          </div>
          <div class="zone">
            <div class="zone-label">正文</div>
            <textarea v-model="edit.content" rows="10" @input="markDirty"
                      placeholder="当日完整复盘（Markdown）……"></textarea>
          </div>
        </div>
        <div class="editor-foot">
          <span class="muted">{{ dirty ? '有未保存修改' : '已保存' }}</span>
          <button class="btn primary" :disabled="saving || !dirty" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
        <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>
      </section>
      <section class="editor-pane empty" v-else>
        <p>选择左侧复盘，或新建一篇</p>
        <button class="btn" :disabled="aiBusy || !!aiDisabledReason" @click="generateDraft">
          {{ aiBusy ? 'AI 生成中…' : '✨ 生成今天的 AI 草稿' }}
        </button>
      </section>
    </div>

    <!-- AI 草稿预览：内容可改，溯源信息不可改 -->
    <div v-if="aiResult" class="modal-mask" @click.self="closeDraft">
      <div class="modal wide">
        <h2>AI 复盘草稿 · {{ aiResult.date }}</h2>
        <div class="ai-meta">
          <span class="badge" :class="aiResult.mode === 'llm' ? 'ok' : 'stale'">
            {{ aiResult.mode === 'llm' ? '模型生成' : '规则汇总' }}
          </span>
          <span>生成时间：{{ fmt(aiResult.generatedAt) }}</span>
          <span>数据截至：{{ fmt(aiResult.dataAsOf) }}</span>
          <span v-if="aiResult.model">模型：{{ aiResult.model }}</span>
          <span class="muted">耗时 {{ (aiResult.elapsedMs / 1000).toFixed(1) }}s</span>
        </div>
        <div v-for="d in aiResult.degraded" :key="d" class="banner warn">{{ d }}</div>
        <div class="limits-box">
          <div class="zone-label">数据限制（不可编辑，会随草稿一起落库）</div>
          <ul class="limits">
            <li v-for="l in aiResult.limitations" :key="l">{{ l }}</li>
          </ul>
        </div>

        <div class="form-row">
          <label>标题</label>
          <input v-model="aiForm.title" maxlength="128" />
        </div>
        <div class="zone">
          <div class="zone-label">市场主要情况</div>
          <textarea v-model="aiForm.marketSummary" rows="3"></textarea>
        </div>
        <div class="zone">
          <div class="zone-label">板块表现</div>
          <textarea v-model="aiForm.sectorNotes" rows="3"></textarea>
        </div>
        <div class="zone">
          <div class="zone-label">正文（Markdown）</div>
          <textarea v-model="aiForm.content" rows="12"></textarea>
        </div>

        <div class="tool-line muted">
          数据来源：{{ aiResult.toolCalls.map((t) => `${t.label}${t.ok ? '✓' : '✗'}`).join(' · ') }}
        </div>
        <div v-if="aiError" class="banner error">{{ aiError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="closeDraft">取消</button>
          <button class="btn primary" :disabled="aiSaving" @click="persistDraft">
            {{ aiSaving ? '保存中…' : '保存为草稿' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 新建弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <h2>新建复盘（一天一篇）</h2>
        <div class="form-row">
          <label>日期</label>
          <input v-model="createForm.recapDate" type="date" />
        </div>
        <div class="form-row">
          <label>标题</label>
          <input v-model="createForm.title" placeholder="如 周五复盘" />
        </div>
        <div class="form-row">
          <label>正文</label>
          <textarea v-model="createForm.content" rows="6" placeholder="初始正文，可保存后再补充分区"></textarea>
        </div>
        <div v-if="createError" class="banner error">{{ createError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="showCreate = false">取消</button>
          <button class="btn primary" :disabled="saving" @click="submitCreate">{{ saving ? '创建中…' : '创建' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 整页撑满内容区：头部固定，列表与编辑器各自滚动（不再用 100vh 魔法数字） */
.recap-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.recap-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 20px;
}
.ai-hint {
  margin: -6px 0 10px;
}
.list-pane {
  width: 240px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  overflow-y: auto;
}
.pane-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  font-size: 13px;
  padding: 4px 4px 10px;
}
.recap-item {
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
}
.recap-item:hover {
  background: var(--bg-hover);
}
.recap-item.active {
  background: var(--accent-soft);
}
.recap-item .date {
  font-size: 11px;
  color: var(--text-tertiary);
}
.recap-item .title {
  font-size: 13px;
  margin: 2px 0;
}
.recap-item .meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
}
.recap-item .del {
  color: var(--text-tertiary);
}
.recap-item .del:hover {
  color: #b91c1c;
}
.editor-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.editor-pane.empty {
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
}
.title-input {
  border: none;
  border-bottom: 1px solid var(--border);
  font-size: 18px;
  font-weight: 700;
  padding: 4px 0 10px;
  margin-bottom: 14px;
  background: transparent;
  outline: none;
}
.zones {
  flex: 1;
  overflow-y: auto;
}
.zone {
  margin-bottom: 14px;
}
.zone-label {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 600;
  margin-bottom: 4px;
}
.zone textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  resize: vertical;
  background: var(--bg-panel);
}
.editor-foot {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  padding-top: 10px;
}
.muted {
  color: var(--text-tertiary);
  font-size: 12px;
}
/* AI 草稿：来源信息与限制必须一直可见，所以单独一组不抢正文的样式 */
.banner.draft {
  background: #f8fafc;
  border: 1px solid var(--border);
  color: var(--text-secondary);
}
.banner.warn {
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #b45309;
}
.ai-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  align-items: center;
  font-size: 12px;
  color: var(--text-tertiary);
  margin: 8px 0 4px;
}
.limits {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.limits-box {
  border: 1px dashed var(--border);
  border-radius: 8px;
  padding: 8px 12px 10px;
  margin: 10px 0 14px;
  background: #fcfcfd;
}
.tool-line {
  margin-top: 4px;
}
.modal.wide {
  width: 760px;
  max-width: calc(100vw - 48px);
}
.badge.off {
  background: var(--bg-hover);
  color: var(--text-secondary);
  border-radius: 4px;
  padding: 0 4px;
}
.badge.stale {
  background: #fffbeb;
  color: #b45309;
  border-radius: 4px;
  padding: 0 4px;
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
  padding: 2px 8px;
  font-size: 12px;
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal {
  width: 420px;
  background: var(--bg-panel);
  border-radius: 12px;
  padding: 20px 24px;
}
.modal h2 {
  font-size: 16px;
  margin: 0 0 14px;
}
.form-row {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.form-row label {
  width: 42px;
  font-size: 13px;
  color: var(--text-secondary);
  flex-shrink: 0;
  padding-top: 6px;
}
.form-row input,
.form-row textarea {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 13px;
  font-family: inherit;
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
.empty {
  color: var(--text-tertiary);
  padding: 24px 0;
}
</style>
