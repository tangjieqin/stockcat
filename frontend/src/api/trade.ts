import { http } from './client'
import type { Page } from './instrument'
import type { Quote } from './quote'

/** 交易流水（docs/API.md §6） */
export interface Trade {
  id: number
  instrumentId: number
  symbol: string
  instrumentName: string
  direction: 'BUY' | 'SELL'
  price: number
  quantity: number
  fee: number
  tradeTime: string
  note: string | null
}

export interface Position {
  instrumentId: number
  symbol: string
  name: string
  quantity: number
  avgCost: number
  lastPrice: number | null
  marketValue: number | null
  unrealizedPnl: number | null
  realizedPnl: number
  priceAsOf: string | null
  priceStatus: string
}

export interface InstrumentOption {
  id: number
  symbol: string
  name: string
}

export async function listTrades(params: { instrumentId?: number; page?: number; size?: number }): Promise<Page<Trade>> {
  const resp = await http.get<Page<Trade>>('/trades', { params })
  return resp.data
}

export async function createTrade(req: {
  instrumentId: number
  direction: 'BUY' | 'SELL'
  price: number
  quantity: number
  fee?: number
  tradeTime: string
  note?: string
}): Promise<Trade> {
  const resp = await http.post<Trade>('/trades', req)
  return resp.data
}

export async function deleteTrade(id: number): Promise<void> {
  await http.delete(`/trades/${id}`)
}

export async function listPositions(): Promise<Position[]> {
  const resp = await http.get<Position[]>('/positions')
  return resp.data
}

/** 全部标的（下拉用） */
export async function listAllInstruments(): Promise<InstrumentOption[]> {
  const resp = await http.get<{ items: Array<{ id: number; symbol: string; name: string }> }>('/instruments', {
    params: { size: 100 },
  })
  return resp.data.items.map((i) => ({ id: i.id, symbol: i.symbol, name: i.name }))
}

export type { Quote }
