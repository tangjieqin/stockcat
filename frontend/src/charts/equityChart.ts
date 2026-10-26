import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { use } from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { CanvasRenderer } from 'echarts/renderers'
import type { EquityCurvePoint } from '../api/portfolio'

/**
 * 收益曲线（P1.5）。三条线：总市值、持仓成本、累计收益（浮动 + 已实现）。
 *
 * 市值与收益量级差一个数量级，所以用双 y 轴：左轴金额、右轴收益。
 * 只画后端给的点，不在前端补点或插值——"某天没快照"就该露出缺口，而不是画一条平滑的假线。
 */
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent])

const MARKET = '#2563eb'
const COST = '#9ca3af'
const PNL = '#dc2626'
const AXIS = '#9ca3af'
const SPLIT = '#f0f1f4'

function money(value: number | null | undefined): string {
  if (value == null) return '—'
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

export function buildEquityOption(points: EquityCurvePoint[]): EChartsOption {
  const dates = points.map((p) => p.tradeDate)
  return {
    animationDuration: 300,
    grid: { left: 70, right: 68, top: 44, bottom: 40 },
    legend: {
      top: 6,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: { fontSize: 11, color: '#6b7280' },
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter: (params: unknown) => {
        const list = params as Array<{ axisValue: string; seriesName: string; data: number | null }>
        if (!list?.length) return ''
        const lines = [`<div style="font-size:12px;color:#6b7280">${list[0].axisValue}</div>`]
        for (const item of list) {
          lines.push(
            `<div style="display:flex;justify-content:space-between;gap:16px;font-size:12px">
               <span>${item.seriesName}</span>
               <span style="font-variant-numeric:tabular-nums">${money(item.data)}</span>
             </div>`,
          )
        }
        return lines.join('')
      },
    },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#e5e7eb' } },
      axisLabel: { color: AXIS, fontSize: 11, formatter: (v: string) => v.slice(5) },
      axisTick: { show: false },
    },
    yAxis: [
      {
        type: 'value',
        name: '金额',
        nameTextStyle: { color: AXIS, fontSize: 11 },
        scale: true,
        splitLine: { lineStyle: { color: SPLIT } },
        axisLabel: { color: AXIS, fontSize: 11, formatter: (v: number) => money(v) },
      },
      {
        type: 'value',
        name: '累计收益',
        position: 'right',
        nameTextStyle: { color: PNL, fontSize: 11 },
        splitLine: { show: false },
        axisLabel: { color: PNL, fontSize: 11, formatter: (v: number) => money(v) },
      },
    ],
    series: [
      {
        name: '总市值',
        type: 'line',
        data: points.map((p) => p.marketValue),
        showSymbol: false,
        connectNulls: false,
        lineStyle: { width: 1.8, color: MARKET },
        itemStyle: { color: MARKET },
        areaStyle: { color: 'rgba(37, 99, 235, 0.08)' },
      },
      {
        name: '持仓成本',
        type: 'line',
        data: points.map((p) => p.costValue),
        showSymbol: false,
        connectNulls: false,
        lineStyle: { width: 1.2, color: COST, type: 'dashed' },
        itemStyle: { color: COST },
      },
      {
        name: '累计收益',
        type: 'line',
        yAxisIndex: 1,
        data: points.map((p) => p.totalPnl),
        showSymbol: false,
        connectNulls: false,
        lineStyle: { width: 1.6, color: PNL },
        itemStyle: { color: PNL },
        markLine: {
          silent: true,
          symbol: 'none',
          label: { show: false },
          lineStyle: { color: '#d1d5db', type: 'dotted' },
          data: [{ yAxis: 0 }],
        },
      },
    ],
  }
}
