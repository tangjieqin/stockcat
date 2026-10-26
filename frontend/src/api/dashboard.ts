import { http } from './client'

/** 首页总览聚合数据（对应 docs/API.md §8，P0.1 为空结构） */
export interface Dashboard {
  indices: Array<{ symbol: string; name: string; lastPrice: number; changePercent: number }>
  positionsSummary: Record<string, unknown>
  topMovers: Array<{ symbol: string; name: string; changePercent: number }>
  pendingAlerts: Array<{ id: number; title: string; triggeredAt: string }>
  latestRecap: Record<string, unknown>
}

export async function fetchDashboard(): Promise<Dashboard> {
  const resp = await http.get<Dashboard>('/dashboard')
  return resp.data
}
