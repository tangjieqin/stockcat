<script setup lang="ts">
/**
 * 我的日记页（左树右文，仿飞书文档）。对应 docs/UI.md 2.10 与 docs/API.md §7。
 * 文件夹/文档、重命名、编辑、移动（成环/深度后端校验）、级联删除强确认、关键词搜索。
 */
import { computed, onMounted, ref } from 'vue'
import {
  createDiaryNode,
  deleteDiaryNode,
  diaryTree,
  getDiaryNode,
  refineDiary,
  reviewDiary,
  searchDiary,
  updateDiaryNode,
  type DiaryNodeView,
  type DiaryRefineResult,
  type DiaryReviewResult,
} from '../api/recap'
import { ApiError } from '../api/client'

const tree = ref<DiaryNodeView[]>([])
const selectedId = ref<number | null>(null)
const detail = ref<{ id: number; parentId: number | null; nodeType: string; title: string; content: string } | null>(null)
const dirty = ref(false)
const errorMsg = ref('')
const saving = ref(false)
const expanded = ref<Set<number>>(new Set())

const searchKeyword = ref('')
const searchHits = ref<Array<{ id: number; title: string; path: string[] }> | null>(null)

const showCreate = ref(false)
const createForm = ref({ parentId: null as number | null, nodeType: 'DOC' as 'FOLDER' | 'DOC', title: '' })
const createError = ref('')

const showMove = ref(false)
const moveTarget = ref<number | null>(null)
const moveError = ref('')

// AI 整理（不落库）：弹出建议稿，用户点「应用到正文」才写进编辑器，再自己保存
const showRefine = ref(false)
const refining = ref(false)
const refineResult = ref<DiaryRefineResult | null>(null)
const refineDraft = ref('')
const refineError = ref('')

// AI 回顾：按关键词检索历史日记并归纳
const showReview = ref(false)
const reviewKeyword = ref('')
const reviewing = ref(false)
const reviewResult = ref<DiaryReviewResult | null>(null)
const reviewError = ref('')

/** 树按展开状态展平为 (节点, 深度) 列表 */
const flatNodes = computed(() => {
  const out: Array<{ node: DiaryNodeView; depth: number }> = []
  function walk(nodes: DiaryNodeView[], depth: number) {
    for (const n of nodes) {
      out.push({ node: n, depth })
      if (n.nodeType === 'FOLDER' && expanded.value.has(n.id)) {
        walk(n.children, depth + 1)
      }
    }
  }
  walk(tree.value, 0)
  return out
})

/** 全部文件夹（移动目标下拉） */
const allFolders = computed(() => {
  const list: Array<{ id: number; label: string }> = []
  function walk(nodes: DiaryNodeView[], prefix: string) {
    for (const n of nodes) {
      if (n.nodeType === 'FOLDER') {
        list.push({ id: n.id, label: prefix + n.title })
        walk(n.children, prefix + n.title + ' / ')
      }
    }
  }
  walk(tree.value, '')
  return list
})

async function load() {
  tree.value = await diaryTree()
}

function toggleExpand(id: number) {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
}

async function select(id: number) {
  try {
    const d = await getDiaryNode(id)
    detail.value = { id: d.id, parentId: d.parentId, nodeType: d.nodeType, title: d.title, content: d.content ?? '' }
    selectedId.value = id
    dirty.value = false
    errorMsg.value = ''
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

function openCreate(parentId: number | null, nodeType: 'FOLDER' | 'DOC') {
  createForm.value = { parentId, nodeType, title: '' }
  createError.value = ''
  showCreate.value = true
}

async function submitCreate() {
  createError.value = ''
  if (!createForm.value.title.trim()) return (createError.value = '请输入名称')
  try {
    const created = await createDiaryNode({
      parentId: createForm.value.parentId,
      nodeType: createForm.value.nodeType,
      title: createForm.value.title.trim(),
    })
    showCreate.value = false
    if (createForm.value.parentId != null) expanded.value.add(createForm.value.parentId)
    await load()
    if (created.nodeType === 'DOC') await select(created.id)
  } catch (e) {
    createError.value = e instanceof ApiError ? e.message : '创建失败'
  }
}

async function save() {
  if (!detail.value) return
  saving.value = true
  errorMsg.value = ''
  try {
    await updateDiaryNode(detail.value.id, {
      title: detail.value.title,
      content: detail.value.nodeType === 'DOC' ? detail.value.content : undefined,
    })
    dirty.value = false
    await load()
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(node: DiaryNodeView) {
  if (node.nodeType === 'FOLDER') {
    // 试探获取级联文档数（后端无 confirm 返回 400 + 数量提示）
    let docCount = 0
    try {
      await deleteDiaryNode(node.id, false)
    } catch (e) {
      const m = e instanceof ApiError ? e.message.match(/(\d+)\s*篇文档/) : null
      docCount = m ? Number(m[1]) : 0
    }
    const tip = docCount > 0
      ? `「${node.title}」下有 ${docCount} 篇文档，删除文件夹将级联删除全部内容且不可恢复！\n确定删除吗？`
      : `确定删除空文件夹「${node.title}」吗？`
    if (!confirm(tip)) return
    try {
      await deleteDiaryNode(node.id, true)
    } catch (e) {
      errorMsg.value = e instanceof ApiError ? e.message : '删除失败'
      return
    }
  } else {
    if (!confirm(`确定删除文档「${node.title}」吗？`)) return
    try {
      await deleteDiaryNode(node.id, true)
    } catch (e) {
      errorMsg.value = e instanceof ApiError ? e.message : '删除失败'
      return
    }
  }
  if (selectedId.value === node.id) {
    detail.value = null
    selectedId.value = null
  }
  await load()
}

function openMove() {
  moveTarget.value = detail.value?.parentId ?? null
  moveError.value = ''
  showMove.value = true
}

async function submitMove() {
  if (!detail.value) return
  moveError.value = ''
  try {
    await updateDiaryNode(detail.value.id, { parentId: moveTarget.value, sortOrder: 0 })
    showMove.value = false
    if (moveTarget.value != null) expanded.value.add(moveTarget.value)
    await load()
  } catch (e) {
    moveError.value = e instanceof ApiError ? e.message : '移动失败'
  }
}

async function doSearch() {
  if (!searchKeyword.value.trim()) {
    searchHits.value = null
    return
  }
  searchHits.value = await searchDiary(searchKeyword.value.trim())
}

// ------------------------------------------------------------------ AI 整理

async function openRefine() {
  if (!detail.value || detail.value.nodeType !== 'DOC') return
  showRefine.value = true
  refining.value = true
  refineError.value = ''
  refineResult.value = null
  refineDraft.value = ''
  try {
    // 传编辑器里的当前内容：用户改了还没保存，要整理的也是眼前这一版
    const r = await refineDiary({
      nodeId: detail.value.id,
      title: detail.value.title,
      content: detail.value.content,
    })
    refineResult.value = r
    refineDraft.value = r.result.refined ?? ''
  } catch (e) {
    refineError.value = e instanceof ApiError ? e.message : '整理失败'
  } finally {
    refining.value = false
  }
}

/** 只写进编辑器并标记未保存——AI 不能替用户点保存 */
function applyRefined() {
  if (!detail.value || !refineDraft.value.trim()) return
  detail.value.content = refineDraft.value
  dirty.value = true
  showRefine.value = false
}

// ------------------------------------------------------------------ AI 回顾

async function doReview() {
  if (!reviewKeyword.value.trim()) return
  reviewing.value = true
  reviewError.value = ''
  reviewResult.value = null
  try {
    reviewResult.value = await reviewDiary(reviewKeyword.value.trim(), 5)
  } catch (e) {
    reviewError.value = e instanceof ApiError ? e.message : '回顾失败'
  } finally {
    reviewing.value = false
  }
}

function jumpToHit(nodeId: number | null) {
  if (nodeId == null) return
  showReview.value = false
  select(nodeId)
}

function jumpToThread(title: string | null) {
  const hit = reviewResult.value?.hits.find((h) => h.title === title)
  jumpToHit(hit?.nodeId ?? null)
}

onMounted(load)
</script>

<template>
  <div class="diary-layout">
    <!-- 左：树 -->
    <aside class="tree-pane">
      <div class="pane-head">
        <input v-model="searchKeyword" class="search" placeholder="🔍 搜索文档" @input="doSearch" />
        <button class="btn tiny" @click="showReview = true">AI 回顾</button>
      </div>

      <div v-if="searchHits" class="search-result">
        <div v-for="h in searchHits" :key="h.id" class="hit" @click="select(h.id)">
          <div class="hit-title">{{ h.title }}</div>
          <div class="hit-path">{{ h.path.join(' / ') }}</div>
        </div>
        <div v-if="!searchHits.length" class="empty">无匹配文档</div>
      </div>

      <div v-else class="tree">
        <div class="tree-actions">
          <button class="btn tiny" @click="openCreate(null, 'FOLDER')">＋ 文件夹</button>
          <button class="btn tiny" @click="openCreate(null, 'DOC')">＋ 文档</button>
        </div>
        <div v-for="{ node: n, depth } in flatNodes" :key="n.id"
             class="node" :class="{ active: selectedId === n.id }"
             :style="{ paddingLeft: 8 + depth * 16 + 'px' }" @click="select(n.id)">
          <span class="icon" @click.stop="n.nodeType === 'FOLDER' && toggleExpand(n.id)">
            {{ n.nodeType === 'FOLDER' ? (expanded.has(n.id) ? '📂' : '📁') : '📄' }}
          </span>
          <span class="node-title">{{ n.title }}</span>
          <span class="node-ops" @click.stop>
            <template v-if="n.nodeType === 'FOLDER'">
              <span class="op" @click="openCreate(n.id, 'DOC')">＋文</span>
              <span class="op" @click="openCreate(n.id, 'FOLDER')">＋夹</span>
            </template>
            <span class="op del" @click="remove(n)">删</span>
          </span>
        </div>
        <div v-if="!tree.length" class="empty">空笔记本 —— 新建第一个文件夹或文档</div>
      </div>
    </aside>

    <!-- 右：编辑 -->
    <section class="editor-pane" v-if="detail">
      <div class="editor-head">
        <input v-model="detail.title" class="title-input" @input="dirty = true" />
        <div class="head-ops">
          <button v-if="detail.nodeType === 'DOC'" class="btn small" @click="openRefine">AI 整理</button>
          <button class="btn small" @click="openMove">移动</button>
          <button class="btn small primary" :disabled="saving || !dirty" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
      <textarea v-if="detail.nodeType === 'DOC'" v-model="detail.content" class="content-input"
                placeholder="长期笔记，随时记录……" @input="dirty = true"></textarea>
      <div v-else class="folder-tip">📁 文件夹（在左侧向其添加子文档）</div>
      <div class="foot">
        <span class="muted">{{ dirty ? '有未保存修改' : '已保存' }}</span>
      </div>
      <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>
    </section>
    <section class="editor-pane empty" v-else>
      <p>选择左侧文档开始编辑</p>
    </section>

    <!-- 新建弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <h2>新建{{ createForm.nodeType === 'FOLDER' ? '文件夹' : '文档' }}</h2>
        <div class="form-row">
          <label>名称</label>
          <input v-model="createForm.title" @keyup.enter="submitCreate" />
        </div>
        <div v-if="createError" class="banner error">{{ createError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="showCreate = false">取消</button>
          <button class="btn primary" @click="submitCreate">创建</button>
        </div>
      </div>
    </div>

    <!-- 移动弹窗 -->
    <div v-if="showMove" class="modal-mask" @click.self="showMove = false">
      <div class="modal">
        <h2>移动「{{ detail?.title }}」</h2>
        <div class="form-row">
          <label>目标</label>
          <select v-model="moveTarget">
            <option :value="null">根目录</option>
            <option v-for="f in allFolders" :key="f.id" :value="f.id">📁 {{ f.label }}</option>
          </select>
        </div>
        <p class="hint">不能移动到自己的子孙下；深度上限 5 层（后端校验）。</p>
        <div v-if="moveError" class="banner error">{{ moveError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="showMove = false">取消</button>
          <button class="btn primary" @click="submitMove">移动</button>
        </div>
      </div>
    </div>

    <!-- AI 整理弹窗：建议稿，不写库 -->
    <div v-if="showRefine" class="modal-mask" @click.self="showRefine = false">
      <div class="modal wide">
        <h2>AI 整理「{{ detail?.title }}」</h2>

        <div v-if="refining" class="hint">正在整理……</div>
        <div v-else-if="refineError" class="banner error">{{ refineError }}</div>

        <template v-else-if="refineResult">
          <div class="ai-meta">
            <span class="chip">{{ refineResult.mode === 'llm' ? '模型整理' : '规则提取' }}</span>
            <span v-if="refineResult.model">{{ refineResult.model }}</span>
            <span>{{ refineResult.elapsedMs }}ms</span>
            <span>可用标的 {{ refineResult.knownInstrumentCount }} 个</span>
          </div>

          <div v-for="d in refineResult.degraded" :key="d" class="banner warn">{{ d }}</div>

          <div class="zone">
            <div class="zone-label">概要</div>
            <p class="ai-text">{{ refineResult.result.summary || '（没有可概括的内容）' }}</p>
          </div>

          <div class="zone">
            <div class="zone-label">要点</div>
            <ul v-if="refineResult.result.highlights.length" class="ai-list">
              <li v-for="(h, i) in refineResult.result.highlights" :key="i">{{ h }}</li>
            </ul>
            <p v-else class="hint">没有提取到要点</p>
          </div>

          <div class="zone">
            <div class="zone-label">涉及标的（只认自选与持仓）</div>
            <p v-if="refineResult.result.instruments.length" class="chips">
              <span v-for="i in refineResult.result.instruments" :key="i.symbol" class="chip">
                {{ i.symbol }} {{ i.name }}
              </span>
            </p>
            <p v-else class="hint">
              文中没有出现自选或持仓中的标的（按代码或证券全称匹配，写简称可能认不出）
            </p>
          </div>

          <div class="zone">
            <div class="zone-label">待跟进</div>
            <ul v-if="refineResult.result.followUps.length" class="ai-list">
              <li v-for="(f, i) in refineResult.result.followUps" :key="i">{{ f }}</li>
            </ul>
            <p v-else class="hint">没有识别到「待跟进」这类句子</p>
          </div>

          <div class="zone">
            <div class="zone-label">整理稿</div>
            <textarea v-if="refineDraft" v-model="refineDraft" rows="10" class="ai-draft"></textarea>
            <p v-else class="hint">
              规则模式不提供改写稿 —— 换个说法不等于整理。以上提取结果可直接参考；
              想要成稿版本的整理，需要先配置模型。
            </p>
          </div>

          <div class="ai-limits">
            <div class="zone-label">数据说明</div>
            <ul class="ai-list">
              <li v-for="(l, i) in refineResult.limitations" :key="i">{{ l }}</li>
            </ul>
          </div>
        </template>

        <div class="modal-foot">
          <button class="btn" @click="showRefine = false">关闭</button>
          <button class="btn primary" :disabled="!refineDraft.trim()" @click="applyRefined">
            应用到正文（不自动保存）
          </button>
        </div>
      </div>
    </div>

    <!-- AI 回顾弹窗：先检索再归纳，没命中就直说 -->
    <div v-if="showReview" class="modal-mask" @click.self="showReview = false">
      <div class="modal wide">
        <h2>AI 回顾日记</h2>
        <div class="form-row">
          <label>检索词</label>
          <input v-model="reviewKeyword" placeholder="如 减仓 / 茅台 / 心态" @keyup.enter="doReview" />
        </div>

        <div v-if="reviewing" class="hint">正在检索并回顾……</div>
        <div v-else-if="reviewError" class="banner error">{{ reviewError }}</div>

        <template v-else-if="reviewResult">
          <div v-if="reviewResult.empty" class="banner warn">
            没有找到包含「{{ reviewResult.keyword }}」的日记，因此不做任何总结 ——
            没有材料时编一段回顾，是最难被发现的错。
          </div>
          <template v-else>
            <div class="ai-meta">
              <span class="chip">{{ reviewResult.mode === 'llm' ? '模型归纳' : '仅检索罗列' }}</span>
              <span>命中 {{ reviewResult.hitCount }} 篇</span>
              <span>{{ reviewResult.elapsedMs }}ms</span>
            </div>
            <div v-for="d in reviewResult.degraded" :key="d" class="banner warn">{{ d }}</div>

            <div v-if="reviewResult.theme" class="zone">
              <div class="zone-label">回顾</div>
              <p class="ai-text">{{ reviewResult.theme }}</p>
            </div>

            <div v-if="reviewResult.threads.length" class="zone">
              <div class="zone-label">线索</div>
              <ul class="ai-list">
                <li v-for="(t, i) in reviewResult.threads" :key="i">
                  <a href="#" @click.prevent="jumpToThread(t.title)">《{{ t.title }}》</a>
                  <span v-if="t.date" class="muted"> {{ t.date }}</span>
                  <div v-if="t.point" class="muted">{{ t.point }}</div>
                </li>
              </ul>
            </div>

            <div v-if="reviewResult.recurring.length" class="zone">
              <div class="zone-label">反复出现的</div>
              <ul class="ai-list">
                <li v-for="(r, i) in reviewResult.recurring" :key="i">{{ r }}</li>
              </ul>
            </div>

            <div v-if="reviewResult.gaps.length" class="zone">
              <div class="zone-label">没写清楚的</div>
              <ul class="ai-list">
                <li v-for="(g, i) in reviewResult.gaps" :key="i">{{ g }}</li>
              </ul>
            </div>

            <div class="zone">
              <div class="zone-label">命中日记（点击打开）</div>
              <div v-for="(h, i) in reviewResult.hits" :key="i" class="hit" @click="jumpToHit(h.nodeId)">
                <div class="hit-title">{{ h.title }}</div>
                <div class="hit-path">
                  {{ h.path }}<span v-if="h.updatedAt"> · {{ h.updatedAt.slice(0, 10) }}</span>
                </div>
                <div class="ai-text">{{ h.preview }}</div>
              </div>
            </div>
          </template>

          <div class="ai-limits">
            <div class="zone-label">数据说明</div>
            <ul class="ai-list">
              <li v-for="(l, i) in reviewResult.limitations" :key="i">{{ l }}</li>
            </ul>
            <p v-if="reviewResult.citations.length" class="muted">
              引用：{{ reviewResult.citations.join('、') }}
            </p>
          </div>
        </template>

        <div class="modal-foot">
          <button class="btn" @click="showReview = false">关闭</button>
          <button class="btn primary" :disabled="reviewing || !reviewKeyword.trim()" @click="doReview">
            {{ reviewing ? '回顾中…' : '开始回顾' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.diary-layout {
  display: flex;
  gap: 20px;
  height: calc(100vh - 100px);
}
.tree-pane {
  width: 280px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  overflow-y: auto;
}
.pane-head {
  display: flex;
  gap: 6px;
  padding: 0 4px 10px;
}
.search {
  flex: 1;
  min-width: 0;
  box-sizing: border-box;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 10px;
  font-size: 12px;
}
.tree-actions {
  display: flex;
  gap: 6px;
  padding: 4px 8px 8px;
}
.node {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
}
.node:hover {
  background: var(--bg-hover);
}
.node.active {
  background: var(--accent-soft);
}
.node-title {
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}
.node-ops {
  display: none;
  gap: 6px;
}
.node:hover .node-ops {
  display: inline-flex;
}
.op {
  font-size: 11px;
  color: var(--accent);
  cursor: pointer;
}
.op.del {
  color: #b91c1c;
}
.search-result .hit {
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.search-result .hit:hover {
  background: var(--bg-hover);
}
.hit-title {
  font-size: 13px;
}
.hit-path {
  font-size: 11px;
  color: var(--text-tertiary);
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
.editor-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.title-input {
  flex: 1;
  border: none;
  border-bottom: 1px solid var(--border);
  font-size: 18px;
  font-weight: 700;
  padding: 4px 0 10px;
  background: transparent;
  outline: none;
}
.head-ops {
  display: flex;
  gap: 8px;
}
.content-input {
  flex: 1;
  margin-top: 12px;
  border: none;
  outline: none;
  resize: none;
  font-size: 14px;
  line-height: 1.8;
  font-family: inherit;
  background: transparent;
}
.folder-tip {
  margin-top: 16px;
  color: var(--text-tertiary);
  font-size: 13px;
}
.foot {
  padding-top: 8px;
  text-align: right;
}
.muted {
  color: var(--text-tertiary);
  font-size: 12px;
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
.btn.tiny {
  padding: 2px 8px;
  font-size: 12px;
}
.btn.small {
  padding: 3px 10px;
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
  width: 400px;
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
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.form-row label {
  width: 42px;
  font-size: 13px;
  color: var(--text-secondary);
  flex-shrink: 0;
}
.form-row input,
.form-row select {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 13px;
}
.hint {
  font-size: 12px;
  color: var(--text-tertiary);
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
.empty {
  color: var(--text-tertiary);
  padding: 16px 8px;
  font-size: 12px;
}

/* ---- AI 整理 / 回顾 ---- */
.modal.wide {
  width: 760px;
  max-width: 92vw;
  max-height: 86vh;
  overflow-y: auto;
}
.ai-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 12px;
}
.chip {
  display: inline-block;
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-secondary);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.zone {
  margin-bottom: 12px;
}
.zone-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.ai-text {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  margin: 0;
}
.ai-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
}
.ai-draft {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.7;
  font-family: inherit;
  resize: vertical;
}
.ai-limits {
  border-top: 1px dashed var(--border);
  padding-top: 10px;
  margin-top: 4px;
}
</style>
