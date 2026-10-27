import { http } from './client'
import type { Page } from './instrument'

/** 资料库（docs/API.md §7.2，P3.1） */

export interface LibraryDocumentSummary {
  id: number
  title: string
  sourceType: 'PASTE' | 'FILE' | 'URL'
  sourceRef: string | null
  publisher: string | null
  publishedAt: string | null
  importedAt: string
  charCount: number
  chunkCount: number
  status: 'ACTIVE' | 'ARCHIVED'
}

export interface LibraryChunk {
  id: number
  seq: number
  heading: string | null
  content: string
  /** 在原文中的字符区间（左闭右开），引用时按它回原文取 */
  charStart: number
  charEnd: number
}

export interface LibraryDocumentDetail extends LibraryDocumentSummary {
  note: string | null
  content: string
  instrumentIds: number[]
  chunks: LibraryChunk[]
}

export interface CreateDocumentPayload {
  title: string
  sourceType?: 'PASTE' | 'FILE'
  sourceRef?: string | null
  publisher?: string | null
  publishedAt?: string | null
  content: string
  instrumentIds?: number[]
  note?: string | null
}

export async function listDocuments(params: {
  keyword?: string
  page?: number
  size?: number
}): Promise<Page<LibraryDocumentSummary>> {
  const resp = await http.get<Page<LibraryDocumentSummary>>('/library/documents', { params })
  return resp.data
}

export async function getDocument(id: number): Promise<LibraryDocumentDetail> {
  const resp = await http.get<LibraryDocumentDetail>(`/library/documents/${id}`)
  return resp.data
}

export async function createDocument(payload: CreateDocumentPayload): Promise<LibraryDocumentDetail> {
  const resp = await http.post<LibraryDocumentDetail>('/library/documents', payload)
  return resp.data
}

/** 上传 .md / .txt；HTML 与 PDF 会被后端明确拒绝并说明原因 */
export async function importDocument(file: File, extra: { title?: string; publisher?: string } = {}) {
  const form = new FormData()
  form.append('file', file)
  if (extra.title) form.append('title', extra.title)
  if (extra.publisher) form.append('publisher', extra.publisher)
  const resp = await http.post<LibraryDocumentDetail>('/library/documents/import', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60_000,
  })
  return resp.data
}

export async function deleteDocument(id: number): Promise<{ deletedChunkCount: number }> {
  const resp = await http.delete<{ deletedChunkCount: number }>(`/library/documents/${id}`, {
    params: { confirm: true },
  })
  return resp.data
}

/** 片段级检索结果（P3.2）：命中单位是段落，不是整篇 */
export interface LibrarySearchHit {
  chunkId: number
  documentId: number
  documentTitle: string
  seq: number
  heading: string | null
  /** 命中位置前后各约 60 字，已压成单行 */
  snippet: string
  charStart: number
  charEnd: number
  sourceType: 'PASTE' | 'FILE' | 'URL'
  sourceRef: string | null
  publisher: string | null
  publishedAt: string | null
  importedAt: string
  score: number
  /** 用于高亮，长的排前面；被整词覆盖的碎片不会出现在这里 */
  matchedTerms: string[]
  /** 正文里没有这个词，只是标题命中——前端要说清楚，否则用户会以为高亮坏了 */
  titleMatched: boolean
}

export interface LibrarySearchResult {
  keyword: string
  /** 实际参与检索的整词（查询会被拆成 bigram） */
  terms: string[]
  hits: LibrarySearchHit[]
}

export async function searchLibrary(params: {
  keyword: string
  limit?: number
  instrumentId?: number
}): Promise<LibrarySearchResult> {
  const resp = await http.get<LibrarySearchResult>('/library/search', { params })
  return resp.data
}
