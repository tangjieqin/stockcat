<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import {
  createDocument,
  deleteDocument,
  getDocument,
  importDocument,
  listDocuments,
  searchLibrary,
  type LibraryDocumentDetail,
  type LibraryDocumentSummary,
  type LibrarySearchHit,
} from '../api/library'

/**
 * 资料库：左列表右详情（P3.1 浏览），加片段级检索（P3.2）。
 *
 * 三个刻意的限制写在界面上而不是藏起来：
 * - 只收「粘贴」与「.md/.txt 文件」，PDF/HTML 会被后端拒绝并说明该怎么办；
 * - 资料导入后正文**不可编辑**，要改就重新导入（靠正文 hash 拦重复）；
 * - 检索只匹配标题与正文的**原文**（拆成 bigram 查），不做同义词扩展。
 *   无结果时就说没有，不拿"你可能想看"来凑数。
 */

const route = useRoute()
const router = useRouter()

const items = ref<LibraryDocumentSummary[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const keyword = ref('')
const loading = ref(false)
const listError = ref('')

const searchHits = ref<LibrarySearchHit[]>([])
const searchTerms = ref<string[]>([])
const searching = ref(false)
const searchError = ref('')
/** 当前定位到的片段序号，用于正文里高亮那一段 */
const hitSeq = ref<number | null>(null)

const detail = ref<LibraryDocumentDetail | null>(null)
const detailLoading = ref(false)

const showPaste = ref(false)
const saving = ref(false)
const formError = ref('')
const form = ref({ title: '', publisher: '', publishedAt: '', content: '' })
const fileInput = ref<HTMLInputElement | null>(null)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const canSubmit = computed(() => form.value.title.trim() !== '' && form.value.content.trim() !== '')
const isSearching = computed(() => keyword.value.trim() !== '')

/**
 * 正文按片段渲染：片段的 charStart/charEnd 是重叠的（上一片最后一段会带到下一片开头），
 * 直接用切片拼会重复显示，所以要按游标裁掉已被覆盖的部分。
 */
const segments = computed(() => {
  const d = detail.value
  if (!d) return []
  const out: { seq: number; text: string }[] = []
  let cursor = 0
  for (const c of d.chunks) {
    const start = Math.max(c.charStart, cursor)
    if (c.charEnd <= start) continue
    out.push({ seq: c.seq, text: d.content.slice(start, c.charEnd) })
    cursor = c.charEnd
  }
  return out
})

let searchTimer: number | undefined

function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 1
    hitSeq.value = null
    void refresh()
  }, 300)
}

async function refresh() {
  if (isSearching.value) {
    await runSearch()
  } else {
    searchHits.value = []
    searchTerms.value = []
    await loadDocuments()
  }
}

async function loadDocuments() {
  loading.value = true
  listError.value = ''
  try {
    const resp = await listDocuments({ page: page.value, size: pageSize })
    items.value = resp.items
    total.value = resp.total
    if (detail.value && !resp.items.some((i) => i.id === detail.value?.id)) {
      // 当前详情已被删或不在结果里，清掉，避免右侧显示一篇列表里看不到的资料
      detail.value = null
    }
  } catch (e) {
    listError.value = e instanceof ApiError ? e.message : '加载资料失败'
  } finally {
    loading.value = false
  }
}

async function runSearch() {
  const kw = keyword.value.trim()
  if (!kw) return
  searching.value = true
  searchError.value = ''
  try {
    const resp = await searchLibrary({ keyword: kw, limit: 30 })
    searchHits.value = resp.hits
    searchTerms.value = resp.terms
  } catch (e) {
    searchError.value = e instanceof ApiError ? e.message : '检索失败'
    searchHits.value = []
  } finally {
    searching.value = false
  }
}

async function select(id: number, keepHit = false) {
  if (!keepHit) hitSeq.value = null
  detailLoading.value = true
  try {
    detail.value = await getDocument(id)
  } catch (e) {
    listError.value = e instanceof ApiError ? e.message : '打开资料失败'
  } finally {
    detailLoading.value = false
  }
}

/** 点命中片段：打开对应资料，并滚到那一段（不是只给一句 snippet 让用户自己翻） */
async function openHit(hit: LibrarySearchHit) {
  hitSeq.value = hit.seq
  if (detail.value?.id !== hit.documentId) {
    await select(hit.documentId, true)
  }
  await nextTick()
  document.getElementById(`chunk-${hit.seq}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

async function submitPaste() {
  if (!canSubmit.value) return
  saving.value = true
  formError.value = ''
  try {
    const created = await createDocument({
      title: form.value.title.trim(),
      sourceType: 'PASTE',
      publisher: form.value.publisher.trim() || null,
      publishedAt: form.value.publishedAt || null,
      content: form.value.content,
    })
    showPaste.value = false
    form.value = { title: '', publisher: '', publishedAt: '', content: '' }
    keyword.value = ''
    await refresh()
    detail.value = created
  } catch (e) {
    // 409 的 message 里带已存在的 id，直接显示给用户比"导入失败"有用
    formError.value = e instanceof ApiError ? e.message : '导入失败'
  } finally {
    saving.value = false
  }
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  listError.value = ''
  try {
    const created = await importDocument(file)
    keyword.value = ''
    await refresh()
    detail.value = created
  } catch (e) {
    listError.value = e instanceof ApiError ? e.message : '导入文件失败'
  }
}

async function remove(doc: LibraryDocumentSummary) {
  const tip = `确定删除资料「${doc.title}」吗？\n（将同时删除 ${doc.chunkCount} 个片段，删除后不可恢复）`
  if (!confirm(tip)) return
  try {
    await deleteDocument(doc.id)
    if (detail.value?.id === doc.id) detail.value = null
    await refresh()
  } catch (e) {
    listError.value = e instanceof ApiError ? e.message : '删除失败'
  }
}

function sourceLabel(t: string) {
  return t === 'FILE' ? '文件' : t === 'URL' ? '链接' : '粘贴'
}

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * snippet 高亮。先转义再标词：反过来会把正文里的 `<` 当成标签。
 * 按词长降序拼正则，避免"库存"先匹配、把"渠道库存"切成两半。
 */
function highlight(text: string, terms: string[]) {
  const escaped = escapeHtml(text)
  const usable = terms.filter((t) => t).sort((a, b) => b.length - a.length)
  if (!usable.length) return escaped
  const pattern = usable.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')
  return escaped.replace(new RegExp(`(${pattern})`, 'gi'), '<mark>$1</mark>')
}

onMounted(async () => {
  await refresh()
  await openFromQuery()
})

/**
 * 从 AI 助手点引用过来时带 `?doc=3&chunk=2`：直接打开那篇并定位到那一段。
 * 用完立刻把参数从地址栏抹掉——它不是可分享的链接（资料只在本机），
 * 留着会让刷新页面变成"再跳一次"，也容易让人以为这是永久地址。
 */
async function openFromQuery() {
  const docId = Number(route.query.doc)
  if (!Number.isFinite(docId) || docId <= 0) return
  const seq = Number(route.query.chunk)
  hitSeq.value = Number.isFinite(seq) && seq > 0 ? seq : null
  await select(docId, true)
  await nextTick()
  if (hitSeq.value != null) {
    document.getElementById(`chunk-${hitSeq.value}`)?.scrollIntoView({ block: 'center' })
  }
  void router.replace({ path: '/library' })
}

watch(() => route.query.doc, () => { void openFromQuery() })
</script>

<template>
  <div class="library">
    <aside class="list-pane card">
      <div class="pane-head">
        <input
          v-model="keyword"
          class="search"
          placeholder="搜索标题与正文（如 渠道库存 / 600519）"
          @input="onSearchInput"
        />
        <div class="actions">
          <button class="btn small" @click="showPaste = true">粘贴导入</button>
          <button class="btn small" @click="fileInput?.click()">上传文件</button>
          <input ref="fileInput" type="file" accept=".md,.markdown,.txt" hidden @change="onFilePicked" />
        </div>
      </div>

      <div v-if="listError" class="banner error">{{ listError }}</div>

      <!-- 检索结果：命中单位是片段，不是整篇 -->
      <template v-if="isSearching">
        <div class="result-head">
          <span v-if="searching" class="hint">检索中……</span>
          <span v-else-if="searchHits.length" class="hint">命中 {{ searchHits.length }} 段</span>
          <span v-if="!searching && searchTerms.length" class="hint">
            按「{{ searchTerms.join(' / ') }}」查找
          </span>
        </div>
        <div v-if="searchError" class="banner error">{{ searchError }}</div>
        <div v-else-if="!searching && !searchHits.length" class="empty">
          没有找到与「{{ keyword.trim() }}」相关的资料。
          <p class="hint">只匹配标题与正文里的原文，不猜近义词。换个短一点的词试试。</p>
        </div>
        <ul v-else class="hit-list">
          <li
            v-for="h in searchHits"
            :key="h.chunkId"
            class="hit-item"
            :class="{ active: detail?.id === h.documentId && hitSeq === h.seq }"
            @click="openHit(h)"
          >
            <div class="hit-title">{{ h.documentTitle }}</div>
            <div class="hit-meta">
              <span class="tag">第 {{ h.seq }} 段<template v-if="h.heading"> · {{ h.heading }}</template></span>
              <span v-if="h.titleMatched" class="tag warn">仅标题命中</span>
              <span>{{ h.publishedAt ?? h.importedAt.slice(0, 10) }}</span>
            </div>
            <!-- snippet 由后端按原文截取，高亮只做展示，不改内容 -->
            <div class="hit-snippet" v-html="highlight(h.snippet, h.matchedTerms)"></div>
          </li>
        </ul>
      </template>

      <!-- 浏览模式 -->
      <template v-else>
        <div v-if="loading" class="hint pad">加载中……</div>
        <div v-else-if="!items.length" class="empty">
          还没有资料。粘贴一篇，或上传 .md / .txt 文件。
        </div>

        <ul v-else class="doc-list">
          <li
            v-for="d in items"
            :key="d.id"
            class="doc-item"
            :class="{ active: detail?.id === d.id }"
            @click="select(d.id)"
          >
            <div class="doc-title">{{ d.title }}</div>
            <div class="doc-meta">
              <span class="tag">{{ sourceLabel(d.sourceType) }}</span>
              <span v-if="d.publisher">{{ d.publisher }}</span>
              <span>{{ d.publishedAt ?? d.importedAt.slice(0, 10) }}</span>
              <span>{{ d.charCount }} 字 · {{ d.chunkCount }} 段</span>
            </div>
          </li>
        </ul>

        <div v-if="pageCount > 1" class="pager">
          <button class="btn small" :disabled="page <= 1" @click="page--; loadDocuments()">上一页</button>
          <span class="hint">{{ page }} / {{ pageCount }}</span>
          <button class="btn small" :disabled="page >= pageCount" @click="page++; loadDocuments()">下一页</button>
        </div>
      </template>
    </aside>

    <section class="detail-pane card">
      <div v-if="detailLoading" class="hint pad">打开中……</div>
      <div v-else-if="!detail" class="empty big">
        选一篇资料查看正文与片段。<br />
        <span class="hint">片段是检索与引用的最小单位，按段落切、保留原文偏移。</span>
      </div>

      <template v-else>
        <header class="detail-head">
          <h1>{{ detail.title }}</h1>
          <div class="doc-meta">
            <span class="tag">{{ sourceLabel(detail.sourceType) }}</span>
            <span v-if="detail.sourceRef">{{ detail.sourceRef }}</span>
            <span v-if="detail.publisher">{{ detail.publisher }}</span>
            <span v-if="detail.publishedAt">发布 {{ detail.publishedAt }}</span>
            <span>导入 {{ detail.importedAt.slice(0, 10) }}</span>
            <span>{{ detail.charCount }} 字 · {{ detail.chunkCount }} 段</span>
          </div>
        </header>

        <article class="content">
          <span
            v-for="seg in segments"
            :key="seg.seq"
            :id="`chunk-${seg.seq}`"
            :class="{ hit: hitSeq === seg.seq }"
          >{{ seg.text }}</span>
        </article>

        <details class="chunks">
          <summary>片段（{{ detail.chunks.length }}）</summary>
          <ol>
            <li v-for="c in detail.chunks" :key="c.id">
              <span class="chunk-head">
                第 {{ c.seq }} 段<template v-if="c.heading"> · {{ c.heading }}</template>
                <span class="hint">　{{ c.charStart }}–{{ c.charEnd }}</span>
              </span>
              <div class="chunk-text">{{ c.content.slice(0, 120) }}{{ c.content.length > 120 ? '…' : '' }}</div>
            </li>
          </ol>
        </details>

        <footer class="detail-foot">
          <span class="hint">资料导入后不可编辑：要换内容就重新导入，重复的正文会被拦住。</span>
          <button class="btn small danger" @click="remove(detail)">删除</button>
        </footer>
      </template>
    </section>

    <!-- 粘贴导入 -->
    <div v-if="showPaste" class="modal-mask" @click.self="showPaste = false">
      <div class="modal wide">
        <h2>粘贴导入</h2>
        <div class="form-row">
          <label>标题</label>
          <input v-model="form.title" placeholder="如 白酒渠道调研纪要" />
        </div>
        <div class="form-row">
          <label>来源</label>
          <input v-model="form.publisher" placeholder="券商 / 媒体 / 自撰（可空）" />
        </div>
        <div class="form-row">
          <label>日期</label>
          <input v-model="form.publishedAt" type="date" />
        </div>
        <div class="form-row vertical">
          <label>正文</label>
          <textarea v-model="form.content" rows="14" placeholder="粘贴正文。保留段间空行，切片会按段落切。"></textarea>
        </div>
        <p class="hint">
          只支持粘贴与 .md / .txt 文件。PDF 没有段落概念、网页另存为 HTML 会带导航与页脚，
          这两类请复制正文后粘贴——否则脏文本会一路脏到检索结果里。
        </p>
        <div v-if="formError" class="banner error">{{ formError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="showPaste = false">取消</button>
          <button class="btn primary" :disabled="!canSubmit || saving" @click="submitPaste">
            {{ saving ? '导入中…' : '导入' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.library {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 14px;
  align-items: start;
}

.list-pane,
.detail-pane {
  padding: 12px;
  min-height: 60vh;
}

.pane-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
}

.search {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 7px 10px;
  font-size: 13px;
  font-family: inherit;
}

.actions {
  display: flex;
  gap: 8px;
}

.pad {
  padding: 12px 4px;
}

/* ---- 检索结果 ---- */

.result-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding: 0 2px 8px;
}

.hit-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.hit-item {
  padding: 9px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  cursor: pointer;
}

.hit-item:hover {
  background: var(--bg-hover, rgba(0, 0, 0, 0.03));
}

.hit-item.active {
  border-color: var(--accent, #4a7cff);
  background: var(--bg-active, rgba(74, 124, 255, 0.06));
}

.hit-title {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hit-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  font-size: 11.5px;
  color: var(--text-tertiary);
  margin-bottom: 4px;
}

.tag.warn {
  color: #b26a00;
  border-color: #e0a94a;
}

.hit-snippet {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  word-break: break-word;
}

.hit-snippet :deep(mark) {
  background: rgba(255, 193, 7, 0.4);
  color: inherit;
  border-radius: 2px;
  padding: 0 1px;
}

/* 正文里定位到的那一段 */
.content span.hit {
  background: rgba(74, 124, 255, 0.12);
  border-radius: 3px;
  box-shadow: 0 0 0 3px rgba(74, 124, 255, 0.12);
}

.doc-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.doc-item {
  padding: 9px 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
}

.doc-item:hover {
  background: var(--bg-hover, rgba(0, 0, 0, 0.03));
}

.doc-item.active {
  border-color: var(--accent, #4a7cff);
  background: var(--bg-active, rgba(74, 124, 255, 0.06));
}

.doc-title {
  font-size: 13.5px;
  font-weight: 500;
  margin-bottom: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11.5px;
  color: var(--text-tertiary);
  align-items: center;
}

.tag {
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 5px;
  font-size: 11px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 10px;
}

.empty.big {
  padding: 80px 20px;
  line-height: 1.9;
}

.detail-head h1 {
  font-size: 17px;
  margin: 2px 0 8px;
}

.content {
  white-space: pre-wrap;
  line-height: 1.85;
  font-size: 13.5px;
  margin: 14px 0 18px;
  max-width: 76ch;
}

.chunks {
  border-top: 1px solid var(--border);
  padding-top: 10px;
  font-size: 12.5px;
}

.chunks summary {
  cursor: pointer;
  color: var(--text-secondary);
}

.chunks ol {
  margin: 8px 0 0;
  padding-left: 20px;
}

.chunks li {
  margin-bottom: 8px;
}

.chunk-head {
  font-weight: 500;
}

.chunk-text {
  color: var(--text-secondary);
  line-height: 1.7;
  margin-top: 2px;
}

.detail-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid var(--border);
  margin-top: 14px;
  padding-top: 10px;
}

.form-row.vertical {
  align-items: flex-start;
}

.form-row.vertical label {
  padding-top: 6px;
}

.form-row.vertical textarea {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  line-height: 1.7;
  resize: vertical;
}

@media (max-width: 1100px) {
  .library {
    grid-template-columns: 1fr;
  }
}
</style>
