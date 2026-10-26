<script setup lang="ts">
/**
 * 告警规则页。对应 docs/UI.md「告警-规则」与 docs/API.md §4。
 * 同一标的可挂多条规则；P1 类型展示但禁用（与原型一致）。
 */
import { onMounted, ref } from 'vue'
import {
  createAlertRule,
  deleteAlertRule,
  formatParams,
  listAlertRules,
  RULE_TYPES,
  updateAlertRuleEnabled,
  type AlertRule,
} from '../api/alert'
import { listMonitorTargets, type MonitorTarget } from '../api/instrument'
import { ApiError } from '../api/client'

const rules = ref<AlertRule[]>([])
const targets = ref<MonitorTarget[]>([])
const loading = ref(false)
const errorMsg = ref('')

const showAdd = ref(false)
const form = ref({ name: '', instrumentId: 0, ruleType: 'PRICE_ABOVE', threshold: 0, cooldownMinutes: 30 })
const addError = ref('')
const submitting = ref(false)

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const [rulePage, targetList] = await Promise.all([listAlertRules({ size: 100 }), listMonitorTargets()])
    rules.value = rulePage.items
    targets.value = targetList
    if (!form.value.instrumentId && targetList.length) {
      form.value.instrumentId = targetList[0].instrument.id
    }
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function submitAdd() {
  addError.value = ''
  if (!form.value.name.trim()) return (addError.value = '请输入规则名称')
  if (!form.value.instrumentId) return (addError.value = '请先在「监控标的」页添加标的')
  submitting.value = true
  try {
    await createAlertRule({
      name: form.value.name.trim(),
      instrumentId: form.value.instrumentId,
      ruleType: form.value.ruleType,
      params: { threshold: Number(form.value.threshold) },
      cooldownMinutes: Number(form.value.cooldownMinutes) || 0,
    })
    showAdd.value = false
    form.value = { name: '', instrumentId: targets.value[0]?.instrument.id ?? 0, ruleType: 'PRICE_ABOVE', threshold: 0, cooldownMinutes: 30 }
    await load()
  } catch (e) {
    addError.value = e instanceof ApiError ? e.message : '创建失败，请重试'
  } finally {
    submitting.value = false
  }
}

async function toggle(r: AlertRule) {
  try {
    const updated = await updateAlertRuleEnabled(r.id, !r.enabled)
    r.enabled = updated.enabled
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

async function remove(r: AlertRule) {
  if (!confirm(`确定删除规则「${r.name}」吗？\n（历史告警事件将保留，且仍可解释）`)) return
  try {
    await deleteAlertRule(r.id)
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
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
        <h1>告警规则</h1>
        <p class="sub">一条规则针对一个标的；同一标的可挂多条规则，独立评估、独立冷却。P1 类型待数据接入后开放。</p>
      </div>
      <button class="btn primary" @click="showAdd = true">＋ 新建规则</button>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <table v-if="rules.length" class="table">
      <thead>
        <tr>
          <th>规则</th>
          <th>标的</th>
          <th>条件</th>
          <th>冷却</th>
          <th>最近触发</th>
          <th>状态</th>
          <th style="width: 140px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rules" :key="r.id">
          <td>{{ r.name }}</td>
          <td><span class="mono">{{ r.symbol }}</span> {{ r.instrumentName }}</td>
          <td class="cond">{{ formatParams(r.ruleType, r.params) }}</td>
          <td>{{ r.cooldownMinutes }} 分钟</td>
          <td class="muted">{{ fmtTime(r.lastTriggeredAt) }}</td>
          <td>
            <span class="badge" :class="r.enabled ? 'ok' : 'off'">{{ r.enabled ? '启用' : '停用' }}</span>
          </td>
          <td>
            <button class="btn small" @click="toggle(r)">{{ r.enabled ? '停用' : '启用' }}</button>
            <button class="btn small danger" @click="remove(r)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else-if="!loading" class="empty">
      暂无规则。先在「监控标的」添加标的，再点击「新建规则」。
    </div>
    <div v-else>加载中…</div>

    <!-- 新建弹窗 -->
    <div v-if="showAdd" class="modal-mask" @click.self="showAdd = false">
      <div class="modal">
        <h2>新建告警规则</h2>
        <div class="form-row">
          <label>名称</label>
          <input v-model="form.name" placeholder="如 茅台跌破1700提醒" />
        </div>
        <div class="form-row">
          <label>标的</label>
          <select v-model="form.instrumentId">
            <option v-for="t in targets" :key="t.id" :value="t.instrument.id">
              {{ t.instrument.symbol }} {{ t.instrument.name }}
            </option>
          </select>
        </div>
        <div class="form-row">
          <label>类型</label>
          <select v-model="form.ruleType">
            <option v-for="t in RULE_TYPES" :key="t.value" :value="t.value" :disabled="t.phase !== 'P0'">
              {{ t.label }}（{{ t.phase }}）{{ t.phase !== 'P0' ? ' - 暂不可用' : '' }}
            </option>
          </select>
        </div>
        <div class="form-row">
          <label>阈值</label>
          <input v-model.number="form.threshold" type="number" step="0.01"
                 :placeholder="RULE_TYPES.find((t) => t.value === form.ruleType)?.paramsHint" />
        </div>
        <div class="form-row">
          <label>冷却</label>
          <input v-model.number="form.cooldownMinutes" type="number" min="0" />
          <span class="unit">分钟</span>
        </div>
        <p class="hint">条件判断含边界（如「高于 10」在价格恰好为 10 时也触发）；同一轮触发后进入冷却期。</p>
        <div v-if="addError" class="banner error">{{ addError }}</div>
        <div class="modal-foot">
          <button class="btn" @click="showAdd = false">取消</button>
          <button class="btn primary" :disabled="submitting" @click="submitAdd">
            {{ submitting ? '提交中…' : '创建' }}
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
.cond {
  font-weight: 600;
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
.unit {
  font-size: 12px;
  color: var(--text-tertiary);
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
