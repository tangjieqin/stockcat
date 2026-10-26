import { http } from './client'

/**
 * 基金参考数据与溢价（对应 docs/API.md §3.5，P1.4）。
 *
 * 溢价率必须连同 basis 一起看：
 * - `premiumBasis === 'IOPV'`：分母是实时参考净值（仅 ETF）
 * - `premiumBasis === 'NAV'`：分母是**已公布的单位净值**，盘中必然滞后 1 天以上，
 *   要用 `navStaleDays` 告诉用户"这个溢价是拿哪天净值算的"
 * - `null`：缺基准或缺价格，此时 `premiumRate` 也是 null（不要显示成 0%）
 */
export interface FundPremium {
  tradeDate: string | null
  price: number | null
  iopv: number | null
  iopvTime: string | null
  nav: number | null
  navDate: string | null
  navStaleDays: number | null
  premiumRate: number | null
  premiumBasis: 'IOPV' | 'NAV' | null
  premiumAsOf: string | null
  priceSource: string | null
  navSource: string | null
  fetchedAt: string | null
}

export interface FundReference {
  instrumentId: number
  symbol: string
  type: string
  /** 只有 ETF/LOF 为 true；股票没有净值与溢价这个概念 */
  applicable: boolean
  reason: string | null
  latest: FundPremium | null
  history: FundPremium[]
}

export interface FundRefreshResult {
  instrumentId: number
  symbol: string
  marketUpdated: boolean
  navWritten: number
  navSkipped: number
  status: 'SUCCESS' | 'PARTIAL' | 'FAILED'
  code: string | null
  message: string | null
  elapsedMs: number
}

export async function getFundReference(instrumentId: number, days = 30): Promise<FundReference> {
  const resp = await http.get<FundReference>(`/instruments/${instrumentId}/fund-reference`, { params: { days } })
  return resp.data
}

/** 批量取当前溢价（自选列表用）；股票不在返回结果里。 */
export async function getLatestPremiums(instrumentIds: number[]): Promise<Record<string, FundPremium>> {
  if (!instrumentIds.length) return {}
  const resp = await http.get<Record<string, FundPremium>>('/fund-references/latest', {
    params: { instrumentIds: instrumentIds.join(',') },
  })
  return resp.data
}

/** 手动刷新：IOPV/价格 + 净值两条都跑，结果分开返回。 */
export async function refreshFundReference(instrumentId: number): Promise<FundRefreshResult> {
  const resp = await http.post<FundRefreshResult>(`/instruments/${instrumentId}/fund-reference/refresh`)
  return resp.data
}

/** 溢价率文案：带符号、两位小数；null 显示 —（不显示 0.00%）。 */
export function premiumText(rate: number | null): string {
  if (rate == null) return '—'
  return `${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`
}

/** 基准说明：ETF 实时 IOPV / 净值（滞后 N 天）。 */
export function basisLabel(item: FundPremium): string {
  if (item.premiumBasis === 'IOPV') return 'IOPV'
  if (item.premiumBasis === 'NAV') {
    const stale = item.navStaleDays
    return stale == null ? '净值' : stale === 0 ? '净值(T)' : `净值(T-${stale})`
  }
  return '—'
}
