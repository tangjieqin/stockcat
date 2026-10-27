<script setup lang="ts">
/**
 * 交易流水页。对应 docs/UI.md「我的股票-交易流水」与 docs/API.md §6。
 * 手工录入买/卖，超卖由后端校验（409 显示可卖数量）。
 */
import { onMounted, ref } from 'vue'
import {
  createTrade,
  deleteTrade,
  listAllInstruments,
  listTrades,
  type InstrumentOption,
  type Trade,
} from '../api/trade'
import { ApiError } from '../api/client'

const trades = ref<Trade[]>([])
const instruments = ref<InstrumentOption[]>([])
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

// 录入表单
const showAdd = ref(false)
const form = ref({
  instrumentId: 0,
  direction: 'BUY' as 'BUY' | 'SELL',
  price: 0,
  quantity: 100,
  fee: 0,
  tradeTime: toLocalInput(new Date()),
  note: '',
})
const addError = ref('')
const submitting = ref(false)

function toLocalInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    const [tradePage, insts] = await Promise.all([listTrades({ size: 100 }), listAllInstruments()])
    trades.value = tradePage.items
    instruments.value = insts
    if (!form.value.instrumentId && insts.length) form.value.instrumentId = insts[0].id
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function submitAdd() {
  addError.value = ''
  if (!form.value.instrumentId) return (addError.value = '请选择标的')
  if (form.value.price <= 0) return (addError.value = '价格必须大于 0')
  if (form.value.quantity <= 0) return (addError.value = '数量必须大于 0')
  submitting.value = true
  try {
    await createTrade({
      instrumentId: form.value.instrumentId,
      direction: form.value.direction,
      price: form.value.price,
      quantity: form.value.quantity,
      fee: form.value.fee || undefined,
      tradeTime: new Date(form.value.tradeTime).toISOString(),
      note: form.value.note.trim() || undefined,
    })
    showAdd.value = false
    successMsg.value = '流水已记录，持仓已重算'
    setTimeout(() => (successMsg.value = ''), 3000)
    await load()
  } catch (e) {
    addError.value = e instanceof ApiError ? e.message : '记录失败，请重试'
  } finally {
    submitting.value = false
  }
}

async function remove(t: Trade) {
  if (!confirm(`确定删除这条${t.direction === 'BUY' ? '买入' : '卖出'}流水吗？\n（受影响持仓将重算；若删除导致历史超卖将被拒绝）`)) return
  try {
    await deleteTrade(t.id)
    await load()
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '删除失败'
  }
}

function fmtTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>交易流水</h1>
        <p class="sub">手工记录每笔买卖；持仓由流水确定性重放计算，超卖会被拒绝。</p>
      </div>
      <button class="btn primary" @click="showAdd = true">＋ 记一笔</button>
    </div>

    <div v-if="successMsg" class="banner ok">{{ successMsg }}</div>
    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <div v-if="trades.length" class="table-wrap">
      <table class="table">
      <thead>
        <tr>
          <th>成交时间</th>
          <th>标的</th>
          <th>方向</th>
          <th>价格</th>
          <th>数量</th>
          <th>费用</th>
          <th>金额</th>
          <th>备注</th>
          <th style="width: 70px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in trades" :key="t.id">
          <td class="muted">{{ fmtTime(t.tradeTime) }}</td>
          <td><span class="mono">{{ t.symbol }}</span> {{ t.instrumentName }}</td>
          <td>
            <span class="badge" :class="t.direction === 'BUY' ? 'buy' : 'sell'">
              {{ t.direction === 'BUY' ? '买入' : '卖出' }}
            </span>
          </td>
          <td class="mono">{{ t.price }}</td>
          <td class="mono">{{ t.quantity }}</td>
          <td class="mono">{{ t.fee }}</td>
          <td class="mono">{{ (t.price * t.quantity).toFixed(2) }}</td>
          <td class="muted">{{ t.note || '—' }}</td>
          <td>
            <button class="btn small danger" @click="remove(t)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    </div>

    <div v-else-if="!loading" class="empty">暂无交易流水，点击「记一笔」开始</div>
    <div v-else>加载中…</div>

    <!-- 录入弹窗 -->
    <div v-if="showAdd" class="modal-mask" @click.self="showAdd = false">
      <div class="modal">
        <h2>记录交易</h2>
        <div class="form-row">
          <label>标的</label>
          <select v-model="form.instrumentId">
            <option v-for="i in instruments" :key="i.id" :value="i.id">{{ i.symbol }} {{ i.name }}</option>
          </select>
        </div>
        <div class="form-row">
          <label>方向</label>
          <div class="dir-toggle">
            <button :class="{ active: form.direction === 'BUY', buy: form.direction === 'BUY' }"
                    @click="form.direction = 'BUY'">买入</button>
            <button :class="{ active: form.direction === 'SELL', sell: form.direction === 'SELL' }"
                    @click="form.direction = 'SELL'">卖出</button>
          </div>
        </div>
        <div class="form-row">
          <label>价格</label>
          <input v-model.number="form.price" type="number" step="0.0001" min="0" />
        </div>
        <div class="form-row">
          <label>数量</label>
          <input v-model.number="form.quantity" type="number" min="1" />
        </div>
        <div class="form-row">
          <label>费用</label>
          <input v-model.number="form.fee" type="number" step="0.01" min="0" placeholder="佣金/税费合计，默认 0" />
        </div>
        <div class="form-row">
          <label>时间</label>
          <input v-model="form.tradeTime" type="datetime-local" />
        </div>
        <div class="form-row">
          <label>备注</label>
          <input v-model="form.note" placeholder="操作理由等，可选" />
        </div>
        <p class="hint">买入费用计入摊薄成本；卖出数量不得超过当时持有量。</p>
        <div v-if="addError" class="banner error">{{ addError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="showAdd = false">取消</button>
          <button class="btn primary" :disabled="submitting" @click="submitAdd">
            {{ submitting ? '提交中…' : '记录' }}
          </button>
        </div>
      </div>
    </div>
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
.muted {
  color: var(--text-tertiary);
  font-size: 12px;
}
.badge {
  font-size: 12px;
  border-radius: 4px;
  padding: 2px 8px;
}
.badge.buy {
  background: #fef2f2;
  color: var(--up);
  font-weight: 600;
}
.badge.sell {
  background: #f0fdf4;
  color: var(--down);
  font-weight: 600;
}
.empty {
  color: var(--text-tertiary);
  padding: 48px 0;
  text-align: center;
}
.banner.ok {
  background: #ecfdf5;
  color: #047857;
  border: 1px solid #a7f3d0;
  border-radius: 8px;
  padding: 8px 12px;
  margin: 10px 0;
  font-size: 13px;
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
.btn.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.btn.small {
  padding: 3px 8px;
}
.btn.danger {
  color: #b91c1c;
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal {
  width: 440px;
  background: var(--bg-panel);
  border-radius: 12px;
  padding: 20px 24px;
}
.modal h2 {
  font-size: 16px;
  margin: 0 0 14px;
}
.form-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.form-row label {
  width: 48px;
  font-size: 13px;
  color: var(--text-secondary);
  flex-shrink: 0;
}
.form-row input,
.form-row select {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 13px;
}
.dir-toggle {
  display: flex;
  gap: 6px;
  flex: 1;
}
.dir-toggle button {
  flex: 1;
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 6px 0;
  font-size: 13px;
  cursor: pointer;
}
.dir-toggle button.active.buy {
  background: var(--up);
  border-color: var(--up);
  color: #fff;
}
.dir-toggle button.active.sell {
  background: var(--down);
  border-color: var(--down);
  color: #fff;
}
.hint {
  font-size: 12px;
  color: var(--text-tertiary);
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
</style>
