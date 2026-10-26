import { http } from './client'

/**
 * 组合层接口（P1.5，对应 docs/API.md §6）。
 *
 * 全部数字都由后端从 position_snapshot 聚合；前端不做任何盈亏计算，
 * 口径（不复权收盘价、简单收益）只在 Java 里定义一次。
 */
export interface EquityCurvePoint {
  tradeDate: string
  marketValue: number | null
  costValue: number
  unrealizedPnl: number | null
  realizedPnlCum: number
  totalPnl: number | null
  positions: number
  pricedPositions: number
  /** false = 有持仓缺当日收盘价，市值只算了能取到价的部分 */
  complete: boolean
}

export interface EquityCurveSummary {
  firstDate: string | null
  lastDate: string | null
  startMarketValue: number | null
  endMarketValue: number | null
  marketValueChange: number | null
  endCostValue: number | null
  endUnrealizedPnl: number | null
  endRealizedPnlCum: number | null
  endTotalPnl: number | null
  netInvested: number | null
  /** 简单收益率（总盈亏 / 净投入），未做资金加权；最后一天缺价时为 null */
  returnPct: number | null
}

export interface EquityCurve {
  from: string
  to: string
  points: EquityCurvePoint[]
  summary: EquityCurveSummary
  lastSnapshotDate: string | null
  priceAdjust: string
  note: string
}

export interface PositionChange {
  instrumentId: number
  symbol: string | null
  name: string | null
  openDate: string | null
  closeDate: string
  openQuantity: number
  closeQuantity: number
  quantityDelta: number
  openAvgCost: number | null
  closeAvgCost: number
  avgCostDelta: number | null
  openMarketValue: number | null
  closeMarketValue: number | null
  marketValueDelta: number | null
  openUnrealized: number | null
  closeUnrealized: number | null
  unrealizedDelta: number | null
  realizedPnlInRange: number
  realizedPnlCum: number
  tradeCount: number
  closed: boolean
}

export interface RebuildResult {
  from: string
  to: string
  tradeDates: number
  instruments: number
  rows: number
  syncedInstruments: number
  warnings: string[]
  elapsedMs: number
}

export interface RangeParams {
  from?: string
  to?: string
}

export async function getEquityCurve(params: RangeParams = {}): Promise<EquityCurve> {
  const resp = await http.get<EquityCurve>('/portfolio/equity-curve', { params })
  return resp.data
}

export async function getPositionChanges(params: RangeParams = {}): Promise<PositionChange[]> {
  const resp = await http.get<PositionChange[]>('/portfolio/position-changes', { params })
  return resp.data
}

/** 改过流水后手动重建快照（收盘任务每天也会跑一次） */
export async function rebuildSnapshots(params: RangeParams = {}): Promise<RebuildResult> {
  const resp = await http.post<RebuildResult>('/portfolio/snapshots/rebuild', null, { params })
  return resp.data
}
