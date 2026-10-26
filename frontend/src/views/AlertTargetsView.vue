<script setup lang="ts">
/**
 * 监控标的页（告警引擎的监控对象，与自选列表独立）。
 * 对应 docs/UI.md 2.x「告警-监控标的」与 docs/API.md §2。
 */
import { onMounted, ref } from 'vue'
import {
  addMonitorTarget,
  deleteMonitorTarget,
  listMonitorTargets,
  updateMonitorTargetEnabled,
  type MonitorTarget,
} from '../api/instrument'
import { ApiError } from '../api/client'
import { changeColor } from '../api/quote'

const targets = ref<MonitorTarget[]>([])
const loading = ref(false)
const errorMsg = ref('')

// 添加弹窗状态
const showAdd = ref(false)
const form = ref({ market: 'CN_A', symbol: '', type: 'STOCK', name: '' })
const addError = ref('')
const submitting = ref(false)

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    targets.value = await listMonitorTargets()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function submitAdd() {
  addError.value = ''
  if (!form.value.symbol.trim()) {
    addError.value = '请输入标的代码'
    return
  }
  submitting.value = true
  try {
    await addMonitorTarget({
      market: form.value.market,
      symbol: form.value.symbol.trim(),
      type: form.value.type,
      name: form.value.name.trim() || undefined,
    })
    showAdd.value = false
    form.value = { market: 'CN_A', symbol: '', type: 'STOCK', name: '' }
    await load()
  } catch (e) {
    addError.value = e instanceof ApiError ? e.message : '添加失败，请重试'
  } finally {
    submitting.value = false
  }
}

async function toggle(t: MonitorTarget) {
  try {
    const updated = await updateMonitorTargetEnabled(t.id, !t.enabled)
    t.enabled = updated.enabled
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

async function remove(t: MonitorTarget) {
  if (!confirm(`确定移除监控标的「${t.instrument.symbol} ${t.instrument.name}」吗？\n（仅移除监控关系，不影响标的主数据与历史数据）`)) {
    return
  }
  try {
    await deleteMonitorTarget(t.id)
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>监控标的</h1>
        <p class="sub">告警引擎的评估范围；行情与告警只针对这里启用的标的。与「自选列表」相互独立。</p>
      </div>
      <button class="btn primary" @click="showAdd = true">＋ 添加监控标的</button>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <table v-if="targets.length" class="table">
      <thead>
        <tr>
          <th>代码</th>
          <th>名称</th>
          <th>市场</th>
          <th>类型</th>
          <th>最新价</th>
          <th>状态</th>
          <th style="width: 140px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in targets" :key="t.id">
          <td class="mono">{{ t.instrument.symbol }}</td>
          <td>{{ t.instrument.name }}</td>
          <td>{{ t.instrument.market }}</td>
          <td>{{ t.instrument.type }}</td>
          <td class="mono" :style="{ color: changeColor(t.quote?.changePercent) }">
            {{ t.quote?.lastPrice ?? '—' }}
          </td>
          <td>
            <span class="badge" :class="t.enabled ? 'ok' : 'off'">{{ t.enabled ? '监控中' : '已停用' }}</span>
          </td>
          <td>
            <button class="btn small" @click="toggle(t)">{{ t.enabled ? '停用' : '启用' }}</button>
            <button class="btn small danger" @click="remove(t)">移除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else-if="!loading" class="empty">暂无监控标的，点击右上角「添加监控标的」开始</div>
    <div v-else>加载中…</div>

    <!-- 添加弹窗 -->
    <div v-if="showAdd" class="modal-mask" @click.self="showAdd = false">
      <div class="modal">
        <h2>添加监控标的</h2>
        <div class="form-row">
          <label>市场</label>
          <select v-model="form.market">
            <option value="CN_A">CN_A（A股）</option>
          </select>
        </div>
        <div class="form-row">
          <label>类型</label>
          <select v-model="form.type">
            <option value="STOCK">股票 STOCK</option>
            <option value="ETF">ETF</option>
            <option value="LOF">LOF</option>
          </select>
        </div>
        <div class="form-row">
          <label>代码</label>
          <input v-model="form.symbol" placeholder="如 600519 / 510300" />
        </div>
        <div class="form-row">
          <label>名称</label>
          <input v-model="form.name" placeholder="标的不存在时必填（P0 无数据源）" />
        </div>
        <p class="hint">若标的已存在可不填名称；新标的需填写名称（P0.3 接入数据源后自动补全）。</p>
        <div v-if="addError" class="banner error">{{ addError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="showAdd = false">取消</button>
          <button class="btn primary" :disabled="submitting" @click="submitAdd">
            {{ submitting ? '提交中…' : '添加' }}
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
}
.table td:nth-child(5) {
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
.badge.off {
  background: var(--bg-hover);
  color: var(--text-tertiary);
}
.empty {
  color: var(--text-tertiary);
  padding: 48px 0;
  text-align: center;
}
.btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
  color: var(--text-primary);
}
.btn:hover {
  background: var(--bg-hover);
}
.btn.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.btn.small {
  padding: 3px 8px;
  margin-right: 6px;
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
  width: 400px;
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
.hint {
  font-size: 12px;
  color: var(--text-tertiary);
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
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
</style>
