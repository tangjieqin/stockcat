import { BarChart, CandlestickChart, LineChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { use } from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { CanvasRenderer } from 'echarts/renderers'
import type { IndicatorPoint, KlineBar } from '../api/kline'

/**
 * K 线图的 ECharts 装配与 option 构造（P1.7）。
 *
 * 只注册用到的模块（candlestick/line/bar + grid/tooltip/dataZoom/title/legend/markLine），
 * 不整包 import echarts，构建产物小一半。
 */
use([
  CanvasRenderer,
  CandlestickChart,
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  AxisPointerComponent,
  DataZoomComponent,
  TitleComponent,
  LegendComponent,
  MarkLineComponent,
])

// A 股约定：红涨绿跌
const UP = '#dc2626'
const DOWN = '#16a34a'
const AXIS = '#9ca3af'
const SPLIT = '#f0f1f4'
const BORDER = '#e5e7eb'
const WARN = '#b45309'

/** 五个 pane 的纵向布局（px）。高度写死是为了主图/副图比例稳定，宽度仍自适应。 */
const PANES = {
  price: { top: 40, height: 270 },
  volume: { top: 338, height: 70 },
  macd: { top: 436, height: 100 },
  rsi: { top: 564, height: 100 },
  kdj: { top: 692, height: 100 },
}
const TITLE_TOP = { volume: 316, macd: 414, rsi: 542, kdj: 670 }
const GRID_LEFT = 60
const GRID_RIGHT = 24
/** 首屏默认展示的根数，与后端默认 limit 一致 */
const VISIBLE_BARS = 120

export interface KlineChartInput {
  bars: KlineBar[]
  points: IndicatorPoint[]
  /** 各指标预热所需根数（后端 requiredBars），缺失时用兜底值 */
  requiredBars: Record<string, number>
  /** 指标接口失败：K 线与成交量照常画，副图明确写"指标加载失败"，不画空线冒充 */
  indicatorError?: boolean
}

export function buildKlineOption(input: KlineChartInput): EChartsOption {
  const { bars } = input
  const dates = bars.map((b) => b.date)
  // 指标与 K 线按日期对齐（后端保证同下标同日期，这里仍按日期兜底，避免错位画错）
  const byDate = new Map(input.points.map((p) => [p.date, p]))
  const ind = bars.map((b) => byDate.get(b.date) ?? null)

  const n = bars.length
  const start = n > VISIBLE_BARS ? ((n - VISIBLE_BARS) / n) * 100 : 0
  const need = (key: string, fallback: number) => input.requiredBars[key] ?? fallback
  const macdNeed = need('macd', 34)
  const rsiNeed = Math.max(need('rsi6', 7), need('rsi12', 13), need('rsi24', 25))
  const kdjNeed = need('kdj', 9)

  const gridIndexes = [0, 1, 2, 3, 4]

  const xAxes = gridIndexes.map((i) => ({
    type: 'category' as const,
    gridIndex: i,
    data: dates,
    boundaryGap: true,
    axisLine: { lineStyle: { color: BORDER } },
    axisTick: { show: false },
    axisLabel:
      i === 4
        ? {
            fontSize: 10,
            color: AXIS,
            hideOverlap: true,
            formatter: (v: string) => v.slice(5),
          }
        : { show: false },
    splitLine: { show: false },
    axisPointer: { label: { show: i === 4 } },
  }))

  const yAxes = [
    {
      gridIndex: 0,
      scale: true,
      axisLabel: { fontSize: 10, color: AXIS, formatter: (v: number) => v.toFixed(2) },
    },
    {
      gridIndex: 1,
      scale: true,
      axisLabel: { fontSize: 10, color: AXIS, formatter: (v: number) => fmtVolumeAxis(v) },
    },
    {
      gridIndex: 2,
      scale: true,
      axisLabel: { fontSize: 10, color: AXIS, formatter: (v: number) => v.toFixed(1) },
    },
    {
      gridIndex: 3,
      min: 0,
      max: 100,
      interval: 25,
      axisLabel: { fontSize: 10, color: AXIS },
    },
    {
      gridIndex: 4,
      scale: true,
      axisLabel: { fontSize: 10, color: AXIS, formatter: (v: number) => v.toFixed(0) },
    },
  ].map((axis) => ({
    ...axis,
    axisLine: { lineStyle: { color: BORDER } },
    axisTick: { show: false },
    splitLine: { show: true, lineStyle: { color: SPLIT } },
  }))

  const paneTitle = (text: string, top: number, insufficient: boolean, needBars: number) => ({
    text: input.indicatorError
      ? `${text} · 指标加载失败`
      : insufficient
        ? `${text} · 数据不足（需 ${needBars} 根）`
        : text,
    top,
    left: GRID_LEFT,
    textStyle: {
      fontSize: 11,
      fontWeight: 'normal' as const,
      color: input.indicatorError || insufficient ? WARN : AXIS,
    },
  })

  const dif = ind.map((p) => p?.dif ?? null)
  const dea = ind.map((p) => p?.dea ?? null)
  const hist = ind.map((p) => p?.macdHist ?? null)
  const rsi6 = ind.map((p) => p?.rsi6 ?? null)
  const rsi12 = ind.map((p) => p?.rsi12 ?? null)
  const rsi24 = ind.map((p) => p?.rsi24 ?? null)
  const kLine = ind.map((p) => p?.k ?? null)
  const dLine = ind.map((p) => p?.d ?? null)
  const jLine = ind.map((p) => p?.j ?? null)

  const option: EChartsOption = {
    animation: false,
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'PingFang SC, Microsoft YaHei, system-ui, sans-serif' },
    title: [
      paneTitle('成交量（手）', TITLE_TOP.volume, false, 0),
      paneTitle('MACD(12,26,9) · 柱=2×(DIF−DEA)', TITLE_TOP.macd, n < macdNeed, macdNeed),
      paneTitle('RSI(6,12,24)', TITLE_TOP.rsi, n < rsiNeed, rsiNeed),
      paneTitle('KDJ(9,3,3) · J=3K−2D（不裁剪）', TITLE_TOP.kdj, n < kdjNeed, kdjNeed),
    ],
    legend: {
      top: 4,
      left: GRID_LEFT,
      itemWidth: 10,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { fontSize: 11, color: '#4b5563' },
      data: ['DIF', 'DEA', 'MACD', 'RSI6', 'RSI12', 'RSI24', 'K', 'D', 'J'],
    },
    grid: [
      { left: GRID_LEFT, right: GRID_RIGHT, top: PANES.price.top, height: PANES.price.height },
      { left: GRID_LEFT, right: GRID_RIGHT, top: PANES.volume.top, height: PANES.volume.height },
      { left: GRID_LEFT, right: GRID_RIGHT, top: PANES.macd.top, height: PANES.macd.height },
      { left: GRID_LEFT, right: GRID_RIGHT, top: PANES.rsi.top, height: PANES.rsi.height },
      { left: GRID_LEFT, right: GRID_RIGHT, top: PANES.kdj.top, height: PANES.kdj.height },
    ],
    axisPointer: {
      link: [{ xAxisIndex: 'all' }],
      label: { backgroundColor: '#374151', fontSize: 10 },
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: 'cross' },
      backgroundColor: 'rgba(255,255,255,0.97)',
      borderColor: BORDER,
      borderWidth: 1,
      padding: [8, 10],
      textStyle: { fontSize: 12, color: '#111827' },
      formatter: (params: unknown) => {
        const list = Array.isArray(params) ? params : [params]
        const first = list[0] as { dataIndex?: number } | undefined
        const idx = first?.dataIndex ?? -1
        if (idx < 0 || idx >= bars.length) return ''
        return tooltipHtml(bars[idx], ind[idx])
      },
    },
    xAxis: xAxes,
    yAxis: yAxes,
    dataZoom: [
      { type: 'inside', xAxisIndex: gridIndexes, start, end: 100 },
      {
        type: 'slider',
        xAxisIndex: gridIndexes,
        bottom: 6,
        height: 18,
        start,
        end: 100,
        borderColor: BORDER,
        fillerColor: 'rgba(37,99,235,0.08)',
        handleSize: '80%',
        textStyle: { fontSize: 10, color: AXIS },
      },
    ],
    series: [
      {
        name: 'K线',
        type: 'candlestick',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: bars.map((b) => [b.open ?? '-', b.close ?? '-', b.low ?? '-', b.high ?? '-']),
        itemStyle: {
          color: UP,
          color0: DOWN,
          borderColor: UP,
          borderColor0: DOWN,
        },
      },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        barWidth: '60%',
        data: bars.map((b) => ({
          value: b.volume ?? null,
          itemStyle: { color: b.close != null && b.open != null && b.close >= b.open ? UP : DOWN },
        })),
      },
      {
        name: 'MACD',
        type: 'bar',
        xAxisIndex: 2,
        yAxisIndex: 2,
        barWidth: '50%',
        data: hist.map((v) => ({
          value: v,
          itemStyle: { color: v == null ? AXIS : v >= 0 ? UP : DOWN },
        })),
      },
      lineSeries('DIF', 2, 2, dif, '#2563eb'),
      lineSeries('DEA', 2, 2, dea, '#d97706'),
      lineSeries('RSI6', 3, 3, rsi6, '#2563eb', [
        { yAxis: 20 },
        { yAxis: 50 },
        { yAxis: 80 },
      ]),
      lineSeries('RSI12', 3, 3, rsi12, '#d97706'),
      lineSeries('RSI24', 3, 3, rsi24, '#7c3aed'),
      lineSeries('K', 4, 4, kLine, '#2563eb'),
      lineSeries('D', 4, 4, dLine, '#d97706'),
      lineSeries('J', 4, 4, jLine, '#7c3aed'),
    ],
  }

  return option
}

function lineSeries(
  name: string,
  xAxisIndex: number,
  yAxisIndex: number,
  data: Array<number | null>,
  color: string,
  markLines: Array<{ yAxis: number }> = [],
) {
  return {
    name,
    type: 'line' as const,
    xAxisIndex,
    yAxisIndex,
    data,
    color,
    lineStyle: { width: 1.2 },
    showSymbol: false,
    connectNulls: false,
    ...(markLines.length
      ? {
          markLine: {
            silent: true,
            symbol: 'none',
            label: { show: false },
            lineStyle: { type: 'dashed' as const, color: '#d1d5db', width: 1 },
            data: markLines,
          },
        }
      : {}),
  }
}

function tooltipHtml(bar: KlineBar, p: IndicatorPoint | null): string {
  const row = (label: string, value: string, color = '#111827') =>
    `<span style="color:#6b7280">${label}</span> <span style="color:${color};font-family:Consolas,monospace">${value}</span>`
  const pct = bar.changePercent
  const pctColor = pct == null ? '#111827' : pct > 0 ? UP : pct < 0 ? DOWN : '#111827'
  const priceRows = [
    row('开', fmtPrice(bar.open)),
    row('高', fmtPrice(bar.high)),
    row('低', fmtPrice(bar.low)),
    row('收', fmtPrice(bar.close), pctColor),
    row('涨跌', pct == null ? '—' : `${pct > 0 ? '+' : ''}${pct.toFixed(2)}%`, pctColor),
    row('量', fmtVolume(bar.volume)),
    row('额', fmtAmount(bar.amount)),
  ].join('&nbsp;&nbsp;')

  const indicatorRows: string[] = []
  if (p) {
    const parts = [
      ['DIF', p.dif],
      ['DEA', p.dea],
      ['MACD', p.macdHist],
      ['RSI6', p.rsi6],
      ['RSI12', p.rsi12],
      ['RSI24', p.rsi24],
      ['K', p.k],
      ['D', p.d],
      ['J', p.j],
    ] as const
    const cells = parts
      .filter(([, v]) => v != null)
      .map(([k, v]) => row(k, (v as number).toFixed(2)))
    if (cells.length) {
      indicatorRows.push(`<div style="margin-top:4px">${cells.join('&nbsp;&nbsp;')}</div>`)
    }
  }

  return `<div style="font-weight:600;margin-bottom:2px">${bar.date}</div>
<div style="line-height:1.7">${priceRows}</div>
${indicatorRows.join('')}`
}

function fmtPrice(v: number | null): string {
  return v == null ? '—' : v.toFixed(2)
}

function fmtVolume(v: number | null): string {
  if (v == null) return '—'
  return v >= 1e4 ? `${(v / 1e4).toFixed(2)} 万手` : `${v.toFixed(0)} 手`
}

function fmtAmount(v: number | null): string {
  if (v == null) return '—'
  if (Math.abs(v) >= 1e8) return `${(v / 1e8).toFixed(2)} 亿`
  if (Math.abs(v) >= 1e4) return `${(v / 1e4).toFixed(2)} 万`
  return v.toFixed(0)
}

function fmtVolumeAxis(v: number): string {
  if (Math.abs(v) >= 1e8) return `${(v / 1e8).toFixed(1)}亿`
  if (Math.abs(v) >= 1e4) return `${(v / 1e4).toFixed(0)}万`
  return String(v)
}
