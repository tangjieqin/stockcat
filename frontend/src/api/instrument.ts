import { http } from './client'
import type { Quote } from './quote'

/** 标的主数据（对应 docs/API.md §2） */
export interface Instrument {
  id: number
  market: string
  symbol: string
  name: string
  type: 'STOCK' | 'ETF' | 'LOF'
  currency: string
}

/** 监控标的（quote 为该标的最新可用快照，无快照时为 null） */
export interface MonitorTarget {
  id: number
  instrument: Instrument
  enabled: boolean
  createdAt: string
  quote: Quote | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export async function listInstruments(params: { keyword?: string; page?: number; size?: number }): Promise<Page<Instrument>> {
  const resp = await http.get<Page<Instrument>>('/instruments', { params })
  return resp.data
}

export async function listMonitorTargets(): Promise<MonitorTarget[]> {
  const resp = await http.get<MonitorTarget[]>('/monitor-targets')
  return resp.data
}

export async function addMonitorTarget(req: { market: string; symbol: string; type: string; name?: string }): Promise<MonitorTarget> {
  const resp = await http.post<MonitorTarget>('/monitor-targets', req)
  return resp.data
}

export async function updateMonitorTargetEnabled(id: number, enabled: boolean): Promise<MonitorTarget> {
  const resp = await http.patch<MonitorTarget>(`/monitor-targets/${id}`, { enabled })
  return resp.data
}

export async function deleteMonitorTarget(id: number): Promise<void> {
  await http.delete(`/monitor-targets/${id}`)
}
