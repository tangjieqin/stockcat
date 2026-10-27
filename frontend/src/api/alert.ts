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
  /** 冷却时间（秒）。存储统一为秒，界面可按秒/分钟/小时输入 */
  cooldownSeconds: number
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

/** 规则参数定义：empty fields = 该规则不需要参数（如金叉/死叉） */
export interface ParamField {
  key: 'threshold' | 'period'
  label: string
  default: number
  /** 有 options 时渲染成下拉（如 RSI 周期只能是 6/12/24） */
  options?: number[]
}

export interface RuleTypeMeta {
  value: string
  label: string
  phase: 'P0' | 'P1'
  /** false 时下拉里禁用（功能还没实现） */
  available: boolean
  /** intraday = 盘中每 30 秒评估；close = 收盘后评估（避免拿半根 K 线判金叉） */
  timing: 'intraday' | 'close'
  fields: ParamField[]
  /** 表单下方的口径说明：写清楚"什么时候触发"，避免用户以为收盘就一定会推 */
  note?: string
}

const THRESHOLD_PRICE: ParamField = { key: 'threshold', label: '阈值（元）', default: 0 }
const THRESHOLD_PERCENT: ParamField = { key: 'threshold', label: '阈值（%，跌 20 填 -20）', default: 0 }
/** 溢价率是百分数：2.5 表示溢价 2.5%，-1.5 表示折价 1.5% */
const THRESHOLD_PREMIUM: ParamField = { key: 'threshold', label: '溢价率阈值（%，折价填负数）', default: 2 }
const RSI_FIELDS: ParamField[] = [
  { key: 'period', label: 'RSI 周期', default: 6, options: [6, 12, 24] },
  { key: 'threshold', label: '阈值（0~100）', default: 80 },
]

/** 规则类型元数据。指标类规则只在收盘后（15:40）评估，逻辑见后端 IndicatorAlertJob */
export const RULE_TYPES: RuleTypeMeta[] = [
  { value: 'PRICE_ABOVE', label: '价格 ≥', phase: 'P0', available: true, timing: 'intraday', fields: [THRESHOLD_PRICE] },
  { value: 'PRICE_BELOW', label: '价格 ≤', phase: 'P0', available: true, timing: 'intraday', fields: [THRESHOLD_PRICE] },
  { value: 'CHANGE_PERCENT_ABOVE', label: '涨跌幅 ≥', phase: 'P0', available: true, timing: 'intraday', fields: [THRESHOLD_PERCENT] },
  { value: 'CHANGE_PERCENT_BELOW', label: '涨跌幅 ≤', phase: 'P0', available: true, timing: 'intraday', fields: [THRESHOLD_PERCENT] },
  {
    value: 'MACD_GOLDEN_CROSS',
    label: 'MACD 金叉（DIF 上穿 DEA）',
    phase: 'P1',
    available: true,
    timing: 'close',
    fields: [],
    note: '收盘后按日线判定，需要至少 35 根日 K（MACD 预热）；一天最多提醒一次。',
  },
  {
    value: 'MACD_DEATH_CROSS',
    label: 'MACD 死叉（DIF 下穿 DEA）',
    phase: 'P1',
    available: true,
    timing: 'close',
    fields: [],
    note: '收盘后按日线判定，需要至少 35 根日 K；一天最多提醒一次。',
  },
  {
    value: 'KDJ_GOLDEN_CROSS',
    label: 'KDJ 金叉（K 上穿 D）',
    phase: 'P1',
    available: true,
    timing: 'close',
    fields: [],
    note: '收盘后按日线判定，需要至少 10 根日 K；一天最多提醒一次。',
  },
  {
    value: 'KDJ_DEATH_CROSS',
    label: 'KDJ 死叉（K 下穿 D）',
    phase: 'P1',
    available: true,
    timing: 'close',
    fields: [],
    note: '收盘后按日线判定，需要至少 10 根日 K；一天最多提醒一次。',
  },
  {
    value: 'RSI_CROSS_ABOVE',
    label: 'RSI 上穿阈值',
    phase: 'P1',
    available: true,
    timing: 'close',
    fields: RSI_FIELDS,
    note: '只在"上穿那一下"提醒；已在上方不会补发，也不会每天重复推。',
  },
  {
    value: 'RSI_CROSS_BELOW',
    label: 'RSI 下穿阈值',
    phase: 'P1',
    available: true,
    timing: 'close',
    fields: RSI_FIELDS,
    note: '只在"下穿那一下"提醒；已在下方不会补发。',
  },
  {
    value: 'PREMIUM_ABOVE',
    label: '溢价率 ≥',
    phase: 'P1',
    available: true,
    timing: 'intraday',
    fields: [THRESHOLD_PREMIUM],
    note: '盘中每 30 秒判定。ETF 用实时 IOPV 作基准；LOF 只能用已公布净值，净值超过 3 天没更新时不再判定（可用参数 maxStaleDays 调整）。',
  },
  {
    value: 'PREMIUM_BELOW',
    label: '溢价率 ≤',
    phase: 'P1',
    available: true,
    timing: 'intraday',
    fields: [THRESHOLD_PREMIUM],
    note: '折价填负数，如 -1.5 表示折价 1.5%。基准口径同上：ETF 用 IOPV，LOF 用最近一期净值。',
  },
  { value: 'INDICATOR_CROSS', label: '指标交叉', phase: 'P1', available: false, timing: 'close', fields: [] },
]

export function ruleTypeMeta(type: string): RuleTypeMeta | undefined {
  return RULE_TYPES.find((t) => t.value === type)
}

/** 该类型需要渲染哪些输入框（交叉类为空） */
export function paramFields(type: string): ParamField[] {
  return ruleTypeMeta(type)?.fields ?? []
}

/**
 * 表单值 → 提交给后端的 params。
 * 只提交该类型真正需要的键：交叉类提交 {}，否则后端会按"类型选错了"拒绝。
 */
export function buildParams(type: string, values: Record<string, number | undefined>): Record<string, number> {
  const params: Record<string, number> = {}
  for (const field of paramFields(type)) {
    const raw = values[field.key]
    params[field.key] = Number(raw ?? field.default)
  }
  return params
}

/** 指标类规则的评估时机与行情类不同，表单里要说明清楚 */
export function isIndicatorRule(type: string): boolean {
  return ruleTypeMeta(type)?.timing === 'close'
}

/** 溢价类规则只对 ETF/LOF 有意义（后端创建时会再校验一次） */
export function isPremiumRule(type: string): boolean {
  return type === 'PREMIUM_ABOVE' || type === 'PREMIUM_BELOW'
}


export async function listAlertRules(params: { page?: number; size?: number }): Promise<Page<AlertRule>> {
  const resp = await http.get<Page<AlertRule>>('/alert-rules', { params })
  return resp.data
}

export type CooldownUnit = 's' | 'm' | 'h'

/** 界面输入值 + 单位 → 秒 */
export function cooldownToSeconds(value: number, unit: CooldownUnit): number {
  const factor = unit === 'h' ? 3600 : unit === 'm' ? 60 : 1
  return Math.max(0, Math.round(value * factor))
}

/** 秒 → 界面输入（自动选最大能整除的单位，便于回显） */
export function splitCooldown(seconds: number): { value: number; unit: CooldownUnit } {
  if (seconds >= 3600 && seconds % 3600 === 0) return { value: seconds / 3600, unit: 'h' }
  if (seconds >= 60 && seconds % 60 === 0) return { value: seconds / 60, unit: 'm' }
  return { value: seconds, unit: 's' }
}

/** 秒 → 人类可读文案（表格列显示用） */
export function cooldownLabel(seconds: number): string {
  if (!seconds) return '不冷却'
  const { value, unit } = splitCooldown(seconds)
  return `${value} ${unit === 'h' ? '小时' : unit === 'm' ? '分钟' : '秒'}`
}

export async function createAlertRule(req: {
  name: string
  instrumentId: number
  ruleType: string
  params: Record<string, number>
  cooldownSeconds: number
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
  req: { name: string; ruleType?: string; params: Record<string, number>; cooldownSeconds: number },
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

/**
 * 告警的 AI 解释（P2 场景二，对应 docs/API.md §5 POST /alert-events/{id}/explain）。
 * why/meaning 可能由模型改写；evidence/limits 恒由后端模板生成，不可被模型改动。
 */
export interface AlertExplain {
  eventId: number
  symbol: string | null
  name: string | null
  ruleName: string | null
  ruleType: string | null
  ruleTypeLabel: string | null
  /** 触发条件文案，如「价格 ≥ ¥1000」 */
  condition: string | null
  mode: 'llm' | 'rule_based'
  why: string
  meaning: string
  current: string
  evidence: string[]
  limits: string[]
  /** false 表示事件快照缺关键观测值，解释会明确说明"当时没有保存" */
  snapshotComplete: boolean
  generatedAt: string
  model: string | null
  degraded: string[]
  elapsedMs: number
}

export async function explainAlertEvent(id: number): Promise<AlertExplain> {
  const resp = await http.post<AlertExplain>(`/alert-events/${id}/explain`)
  return resp.data
}

export function ruleTypeLabel(type: string): string {
  return ruleTypeMeta(type)?.label ?? type
}

function isPercentRule(type: string): boolean {
  return type.startsWith('CHANGE_PERCENT') || type.startsWith('PREMIUM')
}

function signedPercent(type: string, value: number): string {
  const sign = type.includes('BELOW') || value < 0 ? '' : value > 0 ? '+' : ''
  return `${sign}${value}%`
}

/** 规则名自动拼接：{标的名称} {条件}，如「贵州茅台 价格≥¥1000」「贵州茅台 MACD 金叉」 */
export function autoRuleName(
  instrumentName: string,
  ruleType: string,
  params: Record<string, number | undefined>,
): string {
  const type = ruleTypeMeta(ruleType)
  const fields = type?.fields ?? []
  if (fields.length === 0) {
    return `${instrumentName} ${type?.label ?? ruleType}`
  }
  if (ruleType.startsWith('RSI_')) {
    const period = Number(params.period ?? 6)
    const threshold = Number(params.threshold ?? 0)
    const direction = ruleType.includes('ABOVE') ? '上穿' : '下穿'
    return `${instrumentName} RSI${period} ${direction} ${threshold}`
  }
  const value = Number(params.threshold ?? 0)
  const suffix = isPercentRule(ruleType) ? signedPercent(ruleType, value) : `¥${value}`
  return `${instrumentName} ${type?.label ?? ruleType}${suffix}`
}

/** 表格里展示的规则条件文案 */
export function formatParams(type: string, params: Record<string, unknown>): string {
  const meta = ruleTypeMeta(type)
  if (!meta || meta.fields.length === 0) {
    return meta?.label ?? JSON.stringify(params)
  }
  if (type.startsWith('RSI_')) {
    const period = Number(params.period ?? 6)
    const threshold = Number(params.threshold ?? 0)
    return `${meta.label} ${threshold}（RSI${period}）`
  }
  if (params.threshold == null) {
    return JSON.stringify(params)
  }
  const value = Number(params.threshold)
  return isPercentRule(type) ? `${meta.label} ${signedPercent(type, value)}` : `${meta.label} ¥${value}`
}

