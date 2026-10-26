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
  createdAt: string
  updatedAt: string
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
