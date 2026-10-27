import { http } from './client'
import type { Page } from './instrument'

/** 市场复盘（docs/API.md §7） */
export interface Recap {
  id: number
  recapDate: string
  title: string
  marketSummary: string | null
  sectorNotes: string | null
  content: string
  relatedAlertEventIds: number[] | null
  status: 'MANUAL' | 'AI_DRAFT'
  /** AI 溯源信息：手工创建的复盘为 null；AI 起草后确认为 MANUAL 仍保留 */
  aiGeneratedAt: string | null
  aiModel: string | null
  aiDataAsOf: string | null
  aiLimitations: string[] | null
  createdAt: string
  updatedAt: string
}

/** AI 草稿正文四段，与生成接口的 draft 一一对应 */
export interface RecapDraftContent {
  title: string
  marketSummary: string
  sectorNotes: string
  content: string
}

export interface RecapDraftToolCall {
  tool: string
  label: string
  ok: boolean
  summary: string | null
  error: string | null
  dataAsOf: string | null
}

/** 生成结果：**未落库**，用户点「保存为草稿」才写库 */
export interface RecapDraftResult {
  date: string
  mode: 'llm' | 'rule_based'
  draft: RecapDraftContent
  limitations: string[]
  generatedAt: string
  dataAsOf: string | null
  toolCalls: RecapDraftToolCall[]
  model: string | null
  degraded: string[]
  elapsedMs: number
}

export interface DiaryNodeView {
  id: number
  parentId: number | null
  nodeType: 'FOLDER' | 'DOC'
  title: string
  sortOrder: number
  children: DiaryNodeView[]
}

export interface DiaryNodeDetail {
  id: number
  parentId: number | null
  nodeType: 'FOLDER' | 'DOC'
  title: string
  content: string | null
  relatedInstrumentIds: number[] | null
  sortOrder: number
  updatedAt: string
}

export async function listRecaps(params: { keyword?: string; page?: number; size?: number }): Promise<Page<Recap>> {
  const resp = await http.get<Page<Recap>>('/recaps', { params })
  return resp.data
}

export async function getRecap(id: number): Promise<Recap> {
  const resp = await http.get<Recap>(`/recaps/${id}`)
  return resp.data
}

export async function createRecap(req: {
  recapDate: string
  title: string
  marketSummary?: string
  sectorNotes?: string
  content: string
}): Promise<Recap> {
  const resp = await http.post<Recap>('/recaps', req)
  return resp.data
}

export async function updateRecap(id: number, req: {
  title?: string
  marketSummary?: string
  sectorNotes?: string
  content?: string
}): Promise<Recap> {
  const resp = await http.patch<Recap>(`/recaps/${id}`, req)
  return resp.data
}

export async function deleteRecap(id: number): Promise<void> {
  await http.delete(`/recaps/${id}`)
}

/**
 * 生成 AI 复盘草稿（不落库）。
 * 同一天已有 MANUAL 时后端返回 409，前端据此提示「今天已写过」而不是让用户白等。
 */
export async function generateRecapDraft(date: string): Promise<RecapDraftResult> {
  const resp = await http.post<RecapDraftResult>('/recaps/ai-draft', { date })
  return resp.data
}

/** 保存 AI 草稿：已有当天草稿则覆盖，状态恒为 AI_DRAFT */
export async function saveRecapDraft(req: {
  recapDate: string
  title: string
  marketSummary: string
  sectorNotes: string
  content: string
  generatedAt: string
  model: string | null
  dataAsOf: string | null
  limitations: string[]
}): Promise<Recap> {
  const resp = await http.post<Recap>('/recaps/drafts', req)
  return resp.data
}

export async function diaryTree(): Promise<DiaryNodeView[]> {
  const resp = await http.get<DiaryNodeView[]>('/diary/tree')
  return resp.data
}

export async function getDiaryNode(id: number): Promise<DiaryNodeDetail> {
  const resp = await http.get<DiaryNodeDetail>(`/diary/nodes/${id}`)
  return resp.data
}

export async function createDiaryNode(req: {
  parentId: number | null
  nodeType: 'FOLDER' | 'DOC'
  title: string
  content?: string
}): Promise<DiaryNodeDetail> {
  const resp = await http.post<DiaryNodeDetail>('/diary/nodes', req)
  return resp.data
}

export async function updateDiaryNode(id: number, req: {
  title?: string
  content?: string
  parentId?: number | null
  sortOrder?: number
}): Promise<DiaryNodeDetail> {
  const resp = await http.patch<DiaryNodeDetail>(`/diary/nodes/${id}`, req)
  return resp.data
}

export async function deleteDiaryNode(id: number, confirm: boolean): Promise<{ deletedDocCount: number }> {
  const resp = await http.delete<{ deletedDocCount: number }>(`/diary/nodes/${id}`, {
    params: confirm ? { confirm: true } : {},
  })
  return resp.data
}

export async function searchDiary(keyword: string): Promise<Array<{ id: number; title: string; path: string[] }>> {
  const resp = await http.get<Array<{ id: number; title: string; path: string[] }>>('/diary/nodes', {
    params: { keyword },
  })
  return resp.data
}

/** AI 日记辅助（P2 场景四）：只出建议稿，不落库 */

export interface DiaryInstrument {
  symbol: string
  name: string | null
}

export interface DiaryRefineResult {
  title: string | null
  mode: 'llm' | 'rule_based'
  result: {
    summary: string | null
    highlights: string[]
    instruments: DiaryInstrument[]
    followUps: string[]
    /** 规则模式下为 null：规则不做改写 */
    refined: string | null
  }
  knownInstrumentCount: number
  limitations: string[]
  generatedAt: string
  model: string | null
  degraded: string[]
  elapsedMs: number
}

export interface DiaryReviewHit {
  nodeId: number | null
  title: string | null
  path: string | null
  preview: string | null
  updatedAt: string | null
}

export interface DiaryReviewResult {
  keyword: string
  hitCount: number
  /** 没有命中任何日记：此时不调模型，也不给任何总结 */
  empty: boolean
  mode: 'llm' | 'rule_based'
  theme: string | null
  threads: Array<{ title: string; date: string | null; path: string | null; point: string | null }>
  recurring: string[]
  gaps: string[]
  citations: string[]
  hits: DiaryReviewHit[]
  limitations: string[]
  generatedAt: string
  model: string | null
  degraded: string[]
  elapsedMs: number
}

/** 整理一篇日记。content 传编辑器里的当前内容——未保存也能整理 */
export async function refineDiary(req: {
  nodeId?: number | null
  title?: string
  content?: string
}): Promise<DiaryRefineResult> {
  const resp = await http.post<DiaryRefineResult>('/diary/ai/refine', req)
  return resp.data
}

export async function reviewDiary(keyword: string, limit?: number): Promise<DiaryReviewResult> {
  const resp = await http.post<DiaryReviewResult>('/diary/ai/review', { keyword, limit })
  return resp.data
}
