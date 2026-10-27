import { http } from './client'
import type { Instrument } from './instrument'

/** 最新快照（对应 docs/API.md §2 quote 结构；缺失字段为 null，不按 0） */
export interface Quote {
  lastPrice: number | null
  previousClose: number | null
  changePercent: number | null
  volume: number | null
  turnover: number | null
  marketTime: string | null
  collectedAt: string | null
  dataStatus: 'VALID' | 'STALE' | 'PARTIAL' | 'INVALID'
  source: string
}

export interface WatchlistItem {
  id: number
  instrument: Instrument
  createdAt: string | null
  /** 自定义分组；null = 默认「自选」 */
  groupName: string | null
  quote: Quote | null
}

export interface RefreshSummary {
  requested: number
  succeeded: number
  failed: number
  results: Array<{ instrumentId: number; status: 'SUCCESS' | 'FAILED'; code: string | null }>
}

/** 内置指数行情（配置驱动，调度器自动采集） */
export interface IndexQuote {
  symbol: string
  name: string
  lastPrice: number | null
  changePercent: number | null
  marketTime: string | null
  collectedAt: string | null
  source: string
}

export async function listWatchlist(): Promise<WatchlistItem[]> {
  const resp = await http.get<WatchlistItem[]>('/watchlist')
  return resp.data
}

/** 自定义分组名列表（不含默认「自选」）。 */
export async function listWatchlistGroups(): Promise<string[]> {
  const resp = await http.get<string[]>('/watchlist/groups')
  return resp.data
}

export async function addWatchlist(req: {
  market: string
  symbol: string
  type: string
  name?: string
  groupName?: string
}): Promise<WatchlistItem> {
  const resp = await http.post<WatchlistItem>('/watchlist', req)
  return resp.data
}

/** 移动到分组；groupName 传 undefined/null = 移回默认「自选」。 */
export async function moveWatchlistGroup(instrumentId: number, groupName: string | null): Promise<WatchlistItem> {
  const resp = await http.patch<WatchlistItem>(`/watchlist/${instrumentId}/group`, { groupName })
  return resp.data
}

export async function deleteWatchlistItem(instrumentId: number): Promise<void> {
  await http.delete(`/watchlist/${instrumentId}`)
}

export async function refreshQuotes(instrumentIds: number[]): Promise<RefreshSummary> {
  const resp = await http.post<RefreshSummary>('/quotes/refresh', { instrumentIds })
  return resp.data
}

export async function listIndices(): Promise<IndexQuote[]> {
  const resp = await http.get<IndexQuote[]>('/indices')
  return resp.data
}

/** 批量取最新快照（后端返回以 instrumentId 为键的 Map）。 */
export async function getLatestQuotes(instrumentIds: number[]): Promise<Record<string, Quote>> {
  const resp = await http.get<Record<string, Quote>>('/quotes/latest', {
    // 后端声明 List<Long>，用逗号串由 Spring 默认转换器切分，避免 axios 序列化成 instrumentIds[]
    params: { instrumentIds: instrumentIds.join(',') },
  })
  return resp.data
}

/** 涨跌幅颜色：A 股红涨绿跌 */
export function changeColor(changePercent: number | null | undefined): string {
  if (changePercent == null) return 'var(--text-primary)'
  return changePercent > 0 ? 'var(--up)' : changePercent < 0 ? 'var(--down)' : 'var(--text-primary)'
}
