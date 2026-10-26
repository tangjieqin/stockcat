<script setup lang="ts">
/**
 * 我的持仓页。对应 docs/UI.md「我的股票-我的持仓」与 docs/API.md §6。
 * 市值/浮动盈亏只基于有效快照；缺失或过期显示 —（不按 0）。
 */
import { onMounted, ref } from 'vue'
import { listPositions, type Position } from '../api/trade'

const positions = ref<Position[]>([])
const loading = ref(false)
const errorMsg = ref('')

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    positions.value = await listPositions()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function fmt(n: number | null, digits = 2): string {
  return n == null ? '—' : n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

function pnlColor(n: number | null): string {
  if (n == null) return 'var(--text-primary)'
  return n > 0 ? 'var(--up)' : n < 0 ? 'var(--down)' : 'var(--text-primary)'
}

function pct(pos: Position): number | null {
  if (pos.unrealizedPnl == null || pos.avgCost === 0) return null
  return Number(((pos.unrealizedPnl / (pos.quantity * pos.avgCost)) * 100).toFixed(2))
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>我的持仓</h1>
        <p class="sub">由交易流水实时重放计算（摊薄成本含费用）；行情过期时市值与浮动盈亏显示 —。</p>
      </div>
      <button class="btn" @click="load">⟳ 刷新</button>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <table v-if="positions.length" class="table">
      <thead>
        <tr>
          <th>标的</th>
          <th>持有数量</th>
          <th>摊薄成本</th>
          <th>最新价</th>
          <th>市值</th>
          <th>浮动盈亏</th>
          <th>浮动收益率</th>
          <th>已实现盈亏</th>
          <th>价格状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in positions" :key="p.instrumentId">
          <td><span class="mono">{{ p.symbol }}</span> {{ p.name }}</td>
          <td class="mono">{{ fmt(p.quantity, 0) }}</td>
          <td class="mono">{{ fmt(p.avgCost, 3) }}</td>
          <td class="mono">{{ fmt(p.lastPrice, 3) }}</td>
          <td class="mono">{{ fmt(p.marketValue) }}</td>
          <td class="mono" :style="{ color: pnlColor(p.unrealizedPnl) }">{{ fmt(p.unrealizedPnl) }}</td>
          <td class="mono" :style="{ color: pnlColor(pct(p)) }">
            {{ pct(p) != null ? (pct(p)! > 0 ? '+' : '') + pct(p) + '%' : '—' }}
          </td>
          <td class="mono" :style="{ color: pnlColor(p.realizedPnl) }">{{ fmt(p.realizedPnl) }}</td>
          <td>
            <span class="badge" :class="p.priceStatus === 'VALID' ? 'ok' : 'stale'">
              {{ p.priceStatus === 'VALID' ? '有效' : p.priceStatus === 'NO_DATA' ? '—' : p.priceStatus }}
            </span>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else-if="!loading" class="empty">暂无持仓 —— 在「交易流水」记录买入后自动计算</div>
    <div v-else>加载中…</div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.sub {
  color: var(--text-tertiary);
  font-size: 13px;
  margin: -4px 0 0;
}
.table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}
.table th,
.table td {
  text-align: left;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}
.table th {
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-weight: 600;
}
.mono {
  font-family: Consolas, monospace;
  font-variant-numeric: tabular-nums;
}
.badge {
  font-size: 12px;
  border-radius: 4px;
  padding: 2px 8px;
}
.badge.ok {
  background: #ecfdf5;
  color: #047857;
}
.badge.stale {
  background: #fffbeb;
  color: #b45309;
}
.empty {
  color: var(--text-tertiary);
  padding: 48px 0;
  text-align: center;
}
.banner.error {
  background: #fdf2f2;
  color: #b91c1c;
  border: 1px solid #f8b4b4;
  border-radius: 8px;
  padding: 8px 12px;
  margin: 10px 0;
  font-size: 13px;
}
.btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
</style>
