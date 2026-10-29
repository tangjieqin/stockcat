/**
 * 交割单文本解析（华泰/华西/同花顺导出格式 + 自由文本兜底）。
 *
 * 解析策略：
 * 1. 逐行处理；表头行（含「代码」和「操作/方向/业务」）按列名建映射，后续行走精确列解析；
 * 2. 分隔符优先级：制表符 > 逗号（CSV）> 连续空格 > 单空格；
 * 3. 无表头的自由文本用正则兜底：6 位代码、日期时间、买入/卖出关键词、数字序列猜「数量×价格≈金额」；
 * 4. 无 6 位代码的行（银证转账、利息、表头说明等）直接跳过，不进预览。
 *
 * 解析结果只做预览，用户可改后提交；字段错了在预览里标红。
 */

export interface ParsedTrade {
  line: number // 原文行号（1 起，报错时定位用）
  symbol: string
  name: string
  type: 'STOCK' | 'ETF' | 'LOF'
  direction: 'BUY' | 'SELL'
  price: number
  quantity: number
  fee: number
  tradeTime: string // ISO 串
  note: string
  error: string | null // 非 null = 解析失败，不可导入（仍展示供修正）
}

const CODE_RE = /\b(\d{6})\b/
const DATE_RE = /(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})/
const TIME_RE = /(\d{1,2}):(\d{2})(?::(\d{2}))?/
const CHINESE_NAME_RE = /[\u4e00-\u9fa5A-Za-z0-9*·]{2,12}/g

/** 这些词出现在"名称候选"里时视为业务词，不是标的名称 */
const NAME_STOPWORDS = [
  '买入', '卖出', '证券', '人民币', '担保品', '融资', '融券', '成交', '数量', '价格', '金额',
  '手续费', '印花税', '过户费', '发生', '日期', '时间', '股息', '红利', '利息', '申购', '配号',
]

function guessType(code: string): ParsedTrade['type'] {
  if (/^16/.test(code) || /^50[12]/.test(code)) return 'LOF'
  if (/^(5|1)/.test(code)) return 'ETF'
  return 'STOCK'
}

function toLocalIso(y: number, m: number, d: number, hh = 0, mm = 0, ss = 0): string {
  return new Date(y, m - 1, d, hh, mm, ss).toISOString()
}

function parseNumber(raw: string): number | null {
  const n = Number(raw.replace(/,/g, '').replace('¥', '').replace('￥', ''))
  return Number.isFinite(n) ? n : null
}

interface ColMap {
  date: number
  time: number
  code: number
  name: number
  op: number
  qty: number
  price: number
  amount: number
  fees: number[]
}

/** 表头行 -> 列映射；识别不出返回 null */
function headerMap(headerCells: string[]): ColMap | null {
  const find = (names: string[]): number =>
    headerCells.findIndex((c) => names.some((n) => c.includes(n)))
  const map: ColMap = {
    date: find(['成交日期', '发生日期', '日期']),
    time: find(['成交时间', '时间']),
    code: find(['证券代码', '代码']),
    name: find(['证券名称', '名称']),
    op: find(['操作', '业务名称', '摘要', '方向', '标志']),
    qty: find(['成交数量', '成交笔数', '数量']),
    price: find(['成交价格', '成交价', '价格', '均价']),
    amount: find(['发生金额', '成交金额', '金额']),
    fees: [],
  }
  if (map.code < 0 || map.op < 0) return null
  headerCells.forEach((c, i) => {
    if (['手续费', '佣金', '印花税', '过户费', '规费', '附加费用', '其他费'].some((n) => c.includes(n))) {
      map.fees.push(i)
    }
  })
  return map
}

/** 单行解析的中间结果（tradeTime 为空表示日期没解析出来） */
interface RawParsed {
  symbol: string
  name: string
  direction: 'BUY' | 'SELL' | null
  price: number
  quantity: number
  fee: number
  tradeTime: string
  error: string | null
}

function directionOf(text: string): 'BUY' | 'SELL' | null {
  if (/买入|买/.test(text)) return 'BUY'
  if (/卖出|卖/.test(text)) return 'SELL'
  return null
}

/** 无表头自由文本行解析 */
function parseFreeLine(cells: string[], lastDate: { y: number; m: number; d: number } | null): RawParsed {
  const line = cells.join(' ')
  const codeM = line.match(CODE_RE)
  const dateM = line.match(DATE_RE)
  if (dateM) lastDate = { y: +dateM[1], m: +dateM[2], d: +dateM[3] }
  const timeM = line.match(TIME_RE)
  const direction = directionOf(line)
  const nameM = [...line.matchAll(CHINESE_NAME_RE)].map((m) => m[0]).filter(
    (s) => !NAME_STOPWORDS.some((w) => s.includes(w)) && !/^\d/.test(s),
  )

  // 所有数字（排除代码、日期、时间片段）
  const numbers: number[] = []
  for (const cell of cells) {
    if (CODE_RE.test(cell.trim()) && cell.trim().length === 6) continue
    if (DATE_RE.test(cell) || TIME_RE.test(cell)) continue
    const n = parseNumber(cell)
    if (n != null && n !== 0) numbers.push(n)
  }

  let quantity = 0
  let price = 0
  let fee = 0
  // 猜：整数当数量，带小数的当价格/金额；有 3 个以上时取「数量×价格≈金额」误差最小的一对
  if (numbers.length >= 3) {
    let best: { q: number; p: number; err: number } | null = null
    for (const q of numbers) {
      if (!Number.isInteger(q) || q <= 0) continue
      for (const p of numbers) {
        if (p <= 0 || Number.isInteger(p)) continue
        for (const a of numbers) {
          if (a === q || a === p) continue
          const err = Math.abs(q * p - a)
          if (!best || err < best.err) best = { q, p, err }
        }
      }
    }
    if (best && best.err / (best.q * best.p) < 0.05) {
      quantity = best.q
      price = best.p
      // 费用 = 其余小数里最大的减不去管（保守按 0）
    } else if (numbers.length >= 2) {
      quantity = numbers.find((n) => Number.isInteger(n) && n > 0) ?? 0
      price = numbers.find((n) => !Number.isInteger(n) && n > 0) ?? 0
    }
  } else if (numbers.length === 2) {
    quantity = numbers.find((n) => Number.isInteger(n) && n > 0) ?? 0
    price = numbers.find((n) => !Number.isInteger(n) && n > 0) ?? 0
  }

  const y = lastDate?.y ?? 0
  if (!codeM || !y || !direction || quantity <= 0 || price <= 0) {
    return {
      symbol: codeM?.[1] ?? '',
      name: nameM[0] ?? '',
      direction,
      price,
      quantity,
      fee,
      tradeTime: y ? toLocalIso(y, lastDate!.m, lastDate!.d, timeM ? +timeM[1] : 15, timeM ? +timeM[2] : 0) : '',
      error:
        !codeM ? null // 由调用方过滤（无代码整行跳过）
        : !y ? '未识别成交日期'
        : !direction ? '未识别买入/卖出（分红、转账等非买卖行请删除）'
        : quantity <= 0 || price <= 0 ? '未识别出数量和价格，请手工补全'
        : null,
    }
  }
  return {
    symbol: codeM[1],
    name: nameM[0] ?? '',
    direction,
    price,
    quantity,
    fee,
    tradeTime: toLocalIso(y, lastDate!.m, lastDate!.d, timeM ? +timeM[1] : 15, timeM ? +timeM[2] : 0),
    error: null,
  }
}

/** 表头列行解析（精确取列） */
function parseMappedLine(cells: string[], map: ColMap, lastDate: { y: number; m: number; d: number } | null): RawParsed {
  const cell = (i: number) => (i >= 0 && i < cells.length ? cells[i].trim() : '')
  const code = (cell(map.code).match(CODE_RE)?.[1]) ?? ''
  const opText = cell(map.op)
  const dateText = cell(map.date)
  const dateM = dateText.match(DATE_RE)
  if (dateM) lastDate = { y: +dateM[1], m: +dateM[2], d: +dateM[3] }
  const timeM = cell(map.time).match(TIME_RE)
  const direction = directionOf(opText)
  const quantity = map.qty >= 0 ? (parseNumber(cell(map.qty)) ?? 0) : 0
  const price = map.price >= 0 ? (parseNumber(cell(map.price)) ?? 0) : 0
  let fee = 0
  for (const fi of map.fees) fee += parseNumber(cell(fi)) ?? 0
  const amount = map.amount >= 0 ? parseNumber(cell(map.amount)) : null
  // 发生金额与成交金额的差额 = 费用（同花顺口径）
  if (fee === 0 && amount != null && map.amount >= 0 && quantity * price > 0) {
    const diff = Math.abs(Math.abs(amount) - quantity * price)
    if (diff > 0.005 && diff < quantity * price * 0.05) fee = Math.round(diff * 100) / 100
  }
  const name = cell(map.name).replace(/^[\s,]+|[\s,]+$/g, '')

  const y = lastDate?.y ?? 0
  const error =
    !code ? '未识别代码'
    : !y ? '未识别成交日期'
    : !direction ? `未识别买入/卖出（业务：${opText || '空'}）`
    : quantity <= 0 || price <= 0 ? '数量或价格缺失，请手工补全'
    : null

  return {
    symbol: code,
    name,
    direction,
    price,
    quantity,
    fee,
    tradeTime: y
      ? toLocalIso(y, lastDate!.m, lastDate!.d, timeM ? +timeM[1] : 15, timeM ? +timeM[2] : 0)
      : '',
    error,
  }
}

export interface ParseResult {
  rows: ParsedTrade[]
  skipped: string[] // 被跳过的行（无代码：转账/利息/表头说明等），原文供核对
}

/**
 * 解析交割单文本。
 * @param text 粘贴的原文
 * @param accountTag 账户标签（如「华西」「华泰」），写进每行备注
 */
export function parseDealText(text: string, accountTag: string): ParseResult {
  const rows: ParsedTrade[] = []
  const skipped: string[] = []
  let map: ColMap | null = null
  let lastDate: { y: number; m: number; d: number } | null = null

  const lines = text.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i].trim()
    if (!raw) continue

    // 分隔符：制表符 > 逗号 > 连续空格 > 单空格
    let cells: string[]
    if (raw.includes('\t')) cells = raw.split('\t').map((c) => c.trim()).filter(Boolean)
    else if (/,.+,.+/.test(raw)) cells = raw.split(',').map((c) => c.trim())
    else if (/\s{2,}/.test(raw)) cells = raw.split(/\s{2,}/).map((c) => c.trim()).filter(Boolean)
    else cells = raw.split(/\s+/).map((c) => c.trim()).filter(Boolean)

    // 表头行：建映射（可能出现多次，以最后一次为准）
    const hasHeader = cells.some((c) => c.includes('代码')) && cells.some((c) => /操作|方向|业务|摘要|标志/.test(c))
    if (hasHeader) {
      const parsed = headerMap(cells)
      if (parsed) {
        map = parsed
        continue
      }
    }

    // 无 6 位代码的行：说明行/资金行/汇总行，跳过并记录（但其中的分组日期要记住，
    // 有些导出格式日期只在分组行出现一次）
    const codeMatch = raw.match(CODE_RE)
    if (!codeMatch) {
      const dm = raw.match(DATE_RE)
      if (dm) lastDate = { y: +dm[1], m: +dm[2], d: +dm[3] }
      if (/\d{4}[-/年]/.test(raw) || cells.length >= 2) skipped.push(raw)
      continue
    }

    const partial: RawParsed = map ? parseMappedLine(cells, map, lastDate) : parseFreeLine(cells, lastDate)
    if (partial.tradeTime) {
      const d: Date = new Date(partial.tradeTime)
      lastDate = { y: d.getFullYear(), m: d.getMonth() + 1, d: d.getDate() }
    }

    if (!partial.symbol) {
      skipped.push(raw)
      continue
    }

    rows.push({
      line: i + 1,
      symbol: partial.symbol,
      name: partial.name,
      type: guessType(partial.symbol),
      direction: partial.direction ?? 'BUY',
      price: partial.price,
      quantity: partial.quantity,
      fee: partial.fee,
      tradeTime: partial.tradeTime,
      note: `${accountTag} 交割单导入`,
      error: partial.error,
    })
  }
  return { rows, skipped }
}
