import { http } from './client'

/** 告警规则（对应 docs/API.md §4） */
export interface AlertRule {
  id: number
  name: string
  instrumentId: number
  symbol: string
  instrumentName: string
  ruleType: string
  params: Record<string, unknown>
  cooldownMinutes: number
  enabled: boolean
  lastTriggeredAt: string | null
  createdAt: string
}

/** 告警事件（对应 docs/API.md §5） */
export interface AlertEvent {
  id: number
  instrumentId: number
  symbol: string
  ruleId: number | null
  ruleName: string
  ruleType: string
  paramsSnapshot: Record<string, unknown>
  observedPrice: number | null
  observedChangePercent: number | null
  observedDetail: unknown
  quoteMarketTime: string | null
  triggeredAt: string
  notifications: Array<{ id: number; channel: string; status: string; sentAt: string | null }>
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** P0 支持的规则类型元数据（P1 类型禁用提示）。条件均为含边界的 ≥/≤ 判断 */
export const RULE_TYPES = [
  { value: 'PRICE_ABOVE', label: '价格 ≥', paramsHint: '阈值（元）', paramKey: 'threshold', phase: 'P0' },
  { value: 'PRICE_BELOW', label: '价格 ≤', paramsHint: '阈值（元）', paramKey: 'threshold', phase: 'P0' },
  { value: 'CHANGE_PERCENT_ABOVE', label: '涨跌幅 ≥', paramsHint: '阈值（%，如 +5）', paramKey: 'threshold', phase: 'P0' },
  { value: 'CHANGE_PERCENT_BELOW', label: '涨跌幅 ≤', paramsHint: '阈值（%，跌20%填 -20）', paramKey: 'threshold', phase: 'P0' },
  { value: 'PREMIUM_ABOVE', label: '溢价率 ≥', paramsHint: '阈值（%）', paramKey: 'threshold', phase: 'P1' },
  { value: 'PREMIUM_BELOW', label: '溢价率 ≤', paramsHint: '阈值（%）', paramKey: 'threshold', phase: 'P1' },
  { value: 'INDICATOR_CROSS', label: '指标交叉', paramsHint: '指标参数', paramKey: 'indicator', phase: 'P1' },
] as const

export async function listAlertRules(params: { page?: number; size?: number }): Promise<Page<AlertRule>> {
  const resp = await http.get<Page<AlertRule>>('/alert-rules', { params })
  return resp.data
}

export async function createAlertRule(req: {
  name: string
  instrumentId: number
  ruleType: string
  params: Record<string, number>
  cooldownMinutes: number
}): Promise<AlertRule> {
  const resp = await http.post<AlertRule>('/alert-rules', req)
  return resp.data
}

export async function updateAlertRuleEnabled(id: number, enabled: boolean): Promise<AlertRule> {
  const resp = await http.patch<AlertRule>(`/alert-rules/${id}`, { enabled })
  return resp.data
}

/** 修改规则（名称/类型/参数/冷却时间；标的不变更，需删除重建） */
export async function updateAlertRule(
  id: number,
  req: { name: string; ruleType?: string; params: Record<string, number>; cooldownMinutes: number },
): Promise<AlertRule> {
  const resp = await http.patch<AlertRule>(`/alert-rules/${id}`, req)
  return resp.data
}

export async function deleteAlertRule(id: number): Promise<void> {
  await http.delete(`/alert-rules/${id}`)
}

export async function listAlertEvents(params: { page?: number; size?: number }): Promise<Page<AlertEvent>> {
  const resp = await http.get<Page<AlertEvent>>('/alert-events', { params })
  return resp.data
}

export function ruleTypeLabel(type: string): string {
  return RULE_TYPES.find((t) => t.value === type)?.label ?? type
}

/** 规则名自动拼接：{标的名称} {条件}{阈值}，如「贵州茅台 价格≥1000」「中兴通讯 涨跌幅≥+5%」 */
export function autoRuleName(instrumentName: string, ruleType: string, threshold: number): string {
  const v = Number(threshold)
  const isPercent = ruleType.startsWith('CHANGE_PERCENT') || ruleType.startsWith('PREMIUM')
  const sign = ruleType.includes('BELOW') || v < 0 ? '' : v > 0 ? '+' : ''
  return `${instrumentName} ${ruleTypeLabel(ruleType)}${isPercent ? `${sign}${v}%` : `¥${v}`}`
}

export function formatParams(type: string, params: Record<string, unknown>): string {
  const def = RULE_TYPES.find((t) => t.value === type)
  if (!def || params.threshold == null) return JSON.stringify(params)
  const v = Number(params.threshold)
  const sign = type.includes('BELOW') && v > 0 ? '' : v > 0 ? '+' : ''
  if (type.startsWith('CHANGE_PERCENT') || type.startsWith('PREMIUM')) {
    return `${def.label} ${sign}${v}%`
  }
  return `${def.label} ¥${v}`
}
