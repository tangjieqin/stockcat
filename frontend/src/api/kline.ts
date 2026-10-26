import { http } from './client'

/**
 * 历史日 K 与技术指标（对应 docs/API.md §3）。
 *
 * 约定：价格是复权后口径；字段缺失一律 null，**不要用 0 兜底**——
 * 0 会在图上画成贴底的线，看起来像"指标到了极值"，比空着更容易误判。
 */

export type Adjust = 'qfq' | 'hfq' | 'raw'

export interface KlineBar {
  date: string
  open: number | null
  high: number | null
  low: number | null
  close: number | null
  volume: number | null
  amount: number | null
  changePercent: number | null
  turnoverRate: number | null
}

export interface KlineSeries {
  instrumentId: number
  symbol: string
  adjust: Adjust
  /** 已落库根数；0 表示「尚未同步」，不是「该标的没有行情」 */
  count: number
  sourceCode: string | null
  lastFetchedAt: string | null
  bars: KlineBar[]
}

export interface IndicatorPoint {
  date: string
  close: number | null
  dif: number | null
  dea: number | null
  macdHist: number | null
  rsi6: number | null
  rsi12: number | null
  rsi24: number | null
  k: number | null
  d: number | null
  j: number | null
}

export interface IndicatorSeries {
  instrumentId: number
  symbol: string
  adjust: Adjust
  count: number
  /** 各指标预热所需最少根数，如 {macd:34,rsi6:7,rsi12:13,rsi24:25,kdj:9} */
  requiredBars: Record<string, number>
  points: IndicatorPoint[]
}

export interface KlineSyncSummary {
  instrumentId: number
  symbol: string
  adjust: string
  status: 'SUCCESS' | 'FAILED' | 'SKIPPED'
  code: string | null
  message: string | null
  requestedLimit: number
  accepted: number
  rejectedUnclosed: number
  rejectedInvalid: number
  skippedUpstream: number
  source: string | null
  elapsedMs: number
}

export async function getKlines(instrumentId: number, params: { limit?: number; adjust?: Adjust } = {}): Promise<KlineSeries> {
  const resp = await http.get<KlineSeries>(`/instruments/${instrumentId}/klines`, { params })
  return resp.data
}

export async function getIndicators(instrumentId: number, params: { limit?: number; adjust?: Adjust } = {}): Promise<IndicatorSeries> {
  const resp = await http.get<IndicatorSeries>(`/instruments/${instrumentId}/indicators`, { params })
  return resp.data
}

/** 幂等 UPSERT，可反复调用。幂等的是"落库结果"，不是"上游一定成功"。 */
export async function syncHistory(instrumentId: number, days = 120): Promise<KlineSyncSummary> {
  const resp = await http.post<KlineSyncSummary>(`/instruments/${instrumentId}/history/sync`, null, { params: { days } })
  return resp.data
}
