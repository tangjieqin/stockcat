<script setup lang="ts">
/**
 * 监控页（告警引擎的监控对象，与自选列表独立）。
 * 添加时可直接带上告警规则（自动命名）；持仓标志由交易流水实时推导。
 * 对应 docs/UI.md 2.x「告警-监控标的」与 docs/API.md §2。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  addMonitorTarget,
  deleteMonitorTarget,
  listMonitorTargets,
  updateMonitorTargetEnabled,
  type MonitorTarget,
} from '../api/instrument'
import { autoRuleName, buildParams, cooldownToSeconds, createAlertRule, listAlertRules, RULE_TYPES, type CooldownUnit } from '../api/alert'
import { ApiError } from '../api/client'
import { changeColor } from '../api/quote'
import { basisLabel, getLatestPremiums, premiumText, type FundPremium } from '../api/fund'
import { listPositions } from '../api/trade'

const router = useRouter()
const targets = ref<MonitorTarget[]>([])
const holdingIds = ref<Set<number>>(new Set())
const rules = ref<Array<{ instrumentId: number; name: string; enabled: boolean }>>([])
const premiums = ref<Record<string, FundPremium>>({})
const loading = ref(false)
const errorMsg = ref('')

const holding = (t: MonitorTarget) => holdingIds.value.has(t.instrument.id)

function premiumOf(instrumentId: number): FundPremium | null {
  return premiums.value[String(instrumentId)] ?? null
}

function premiumHint(instrumentId: number): string {
  const p = premiumOf(instrumentId)
  if (!p) return '尚未同步基金参考数据（进入详情页点「刷新参考数据」）'
  const nav = p.nav == null ? '—' : `${p.nav}${p.navDate ? `（${p.navDate}）` : ''}`
  return [
    `价格 ${p.price ?? '—'}`,
    `IOPV ${p.iopv ?? '—'}`,
    `净值 ${nav}`,
    `基准 ${p.premiumBasis ?? '—'}${p.premiumAsOf ? ` @ ${p.premiumAsOf}` : ''}`,
  ].join(' · ')
}

/** 每个标的挂的规则（名称聚合，供「规则」列展示） */
const rulesByInstrument = computed(() => {
  const map = new Map<number, string[]>()
  for (const r of rules.value) {
    if (!map.has(r.instrumentId)) map.set(r.instrumentId, [])
    map.get(r.instrumentId)!.push(r.name)
  }
  return map
})

function rulesOf(t: MonitorTarget): string[] {
  return rulesByInstrument.value.get(t.instrument.id) ?? []
}

// 添加弹窗状态
const showAdd = ref(false)
const form = ref({
  market: 'CN_A',
  symbol: '',
  type: 'STOCK',
  name: '',
  withRule: true,
  ruleType: 'PRICE_ABOVE',
  threshold: 0,
  cooldownValue: 30,
  cooldownUnit: 'm' as CooldownUnit,
})
const addError = ref('')
const submitting = ref(false)

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const [targetList, positions, rulePage] = await Promise.all([
      listMonitorTargets(),
      listPositions(),
      listAlertRules({ size: 100 }).catch(() => ({ items: [] as Array<{ instrumentId: number; name: string; enabled: boolean }> })),
    ])
    targets.value = targetList
    holdingIds.value = new Set(positions.filter((p) => p.quantity > 0).map((p) => p.instrumentId))
    // 规则加载失败不挡监控列表，只是「规则」列显示 -
    rules.value = rulePage.items
    // 溢价只对 ETF/LOF 取；失败不影响行情显示（附加列，不能把整页拖成错误页）
    const fundIds = targetList.filter((t) => t.instrument.type !== 'STOCK').map((t) => t.instrument.id)
    premiums.value = fundIds.length
      ? await getLatestPremiums(fundIds).catch(() => ({}))
      : {}
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
    // 1. 添加监控（名称由行情源自动补全）
    const created = await addMonitorTarget({
      market: form.value.market,
      symbol: form.value.symbol.trim(),
      type: form.value.type,
      name: form.value.name.trim() || undefined,
    })
    // 2. 同步创建告警规则（自动命名，失败不影响已添加的监控）
    if (form.value.withRule) {
      try {
        const ruleParams = buildParams(form.value.ruleType, { threshold: form.value.threshold })
        await createAlertRule({
          name: autoRuleName(created.instrument.name, form.value.ruleType, ruleParams),
          instrumentId: created.instrument.id,
          ruleType: form.value.ruleType,
          params: ruleParams,
          cooldownSeconds: cooldownToSeconds(Number(form.value.cooldownValue) || 0, form.value.cooldownUnit),
        })
      } catch (e) {
        addError.value = `标的已添加，但规则创建失败：${e instanceof ApiError ? e.message : '请重试'}（可在「规则」页手动创建）`
        await load()
        return
      }
    }
    showAdd.value = false
    form.value = { market: 'CN_A', symbol: '', type: 'STOCK', name: '', withRule: true, ruleType: 'PRICE_ABOVE', threshold: 0, cooldownValue: 30, cooldownUnit: 'm' }
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
  if (!confirm(`确定移除监控「${t.instrument.symbol} ${t.instrument.name}」吗？\n（仅移除监控关系，不影响标的主数据与历史数据；其告警规则保留，可在规则页清理）`)) {
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
        <h1>监控</h1>
        <p class="sub">告警引擎的评估范围；行情与告警只针对这里启用的标的。与「自选列表」相互独立；「持仓」由交易流水实时推导。</p>
      </div>
      <button class="btn primary" @click="showAdd = true">＋ 添加监控</button>
    </div>

    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <div v-if="targets.length" class="table-wrap">
      <table class="table">
      <thead>
        <tr>
          <th>标的</th>
          <th>最新价</th>
          <th>溢价</th>
          <th>持仓</th>
          <th>规则</th>
          <th>状态</th>
          <th style="width: 140px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in targets" :key="t.id">
          <td>
            <div class="inst-name">{{ t.instrument.name }}</div>
            <div class="inst-code mono">{{ t.instrument.symbol }}</div>
          </td>
          <td class="price mono" :style="{ color: changeColor(t.quote?.changePercent) }">
            {{ t.quote?.lastPrice ?? '—' }}
          </td>
          <td class="premium-cell">
            <template v-if="t.instrument.type !== 'STOCK'">
              <span
                class="mono"
                :title="premiumHint(t.instrument.id)"
                :style="{ color: changeColor(premiumOf(t.instrument.id)?.premiumRate ?? null) }"
              >
                {{ premiumText(premiumOf(t.instrument.id)?.premiumRate ?? null) }}
              </span>
              <span v-if="premiumOf(t.instrument.id)" class="basis" :title="premiumHint(t.instrument.id)">
                {{ basisLabel(premiumOf(t.instrument.id)!) }}
              </span>
            </template>
            <span v-else class="muted">-</span>
          </td>
          <td>
            <span v-if="holding(t)" class="badge hold">持仓中</span>
            <span v-else class="muted">—</span>
          </td>
          <td>
            <span
              v-if="rulesOf(t).length"
              class="rule-link"
              :title="rulesOf(t).join('；')"
              @click="router.push('/alerts/rules')"
            >
              {{ rulesOf(t).length }} 条
            </span>
            <span v-else class="muted">-</span>
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
    </div>

    <div v-else-if="!loading" class="empty">暂无监控标的，点击右上角「添加监控标的」开始</div>
    <div v-else>加载中…</div>

    <!-- 添加弹窗 -->
    <div v-if="showAdd" class="modal-mask" @click.self="showAdd = false">
      <div class="modal">
        <h2>添加监控</h2>
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

        <div class="rule-section">
          <label class="rule-check">
            <input v-model="form.withRule" type="checkbox" />
            同时创建告警规则（规则名按下方选择自动拼接）
          </label>
          <template v-if="form.withRule">
            <div class="form-row">
              <label>类型</label>
              <select v-model="form.ruleType">
                <option v-for="t in RULE_TYPES.filter((x) => x.phase === 'P0')" :key="t.value" :value="t.value">
                  {{ t.label }}
                </option>
              </select>
            </div>
            <div class="form-row">
              <label>阈值</label>
              <input v-model.number="form.threshold" type="number" step="0.01" />
            </div>
            <div class="form-row">
              <label>冷却时间</label>
              <input v-model.number="form.cooldownValue" type="number" min="0" />
              <select v-model="form.cooldownUnit" class="unit-select">
                <option value="s">秒</option>
                <option value="m">分钟</option>
                <option value="h">小时</option>
              </select>
            </div>
          </template>
        </div>

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
/* 标的列：名称在前，代码小字在后 */
.inst-name {
  font-size: 13px;
}
.inst-code {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 1px;
}
.price {
  font-variant-numeric: tabular-nums;
}
.rule-link {
  color: var(--accent);
  cursor: pointer;
}
.rule-link:hover {
  text-decoration: underline;
}
/* 溢价列（仅 ETF/LOF 有值） */
.premium-cell {
  white-space: nowrap;
}
.basis {
  font-size: 10px;
  color: var(--text-tertiary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 3px;
  margin-left: 4px;
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
.badge.hold {
  background: #eff6ff;
  color: #1d4ed8;
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
.rule-section {
  border-top: 1px dashed var(--border);
  margin-top: 12px;
  padding-top: 12px;
}
.rule-check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 10px;
  cursor: pointer;
}
.unit {
  font-size: 12px;
  color: var(--text-tertiary);
}
.unit-select {
  width: 90px;
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
