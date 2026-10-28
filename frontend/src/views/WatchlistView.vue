<script setup lang="ts">
/**
 * 自选列表页（行情总览）。
 * 对应 docs/UI.md「我的股票-自选列表」与 docs/API.md §2/§3。
 * 无快照显示 —（不按 0）；STALE 标注行情时间；来源与采集时间可见。
 * 自定义分组：顶部页签「自选 / 我的组合…」切换，行内可移动分组；分组名存 watchlist_item.group_name（null=默认自选）。
 */
import { computed, onMounted, ref } from 'vue'
import {
  addWatchlist,
  changeColor,
  deleteWatchlistItem,
  listWatchlist,
  listWatchlistGroups,
  moveWatchlistGroup,
  refreshQuotes,
  type RefreshSummary,
  type WatchlistItem,
} from '../api/quote'
import { ApiError } from '../api/client'
import { basisLabel, getLatestPremiums, premiumText, type FundPremium } from '../api/fund'

const items = ref<WatchlistItem[]>([])
const groups = ref<string[]>([])
/** '' = 默认「自选」，其余为自定义分组名 */
const activeGroup = ref('')
const premiums = ref<Record<string, FundPremium>>({})
const loading = ref(false)
const errorMsg = ref('')
const refreshing = ref(false)
const refreshSummary = ref<RefreshSummary | null>(null)

const showAdd = ref(false)
const form = ref({ market: 'CN_A', symbol: '', type: 'STOCK', name: '', group: '' })
const addError = ref('')
const submitting = ref(false)

const filteredItems = computed(() =>
  activeGroup.value === ''
    ? items.value.filter((i) => !i.groupName)
    : items.value.filter((i) => i.groupName === activeGroup.value),
)

function countOf(group: string): number {
  return group === ''
    ? items.value.filter((i) => !i.groupName).length
    : items.value.filter((i) => i.groupName === group).length
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const [list, groupNames] = await Promise.all([listWatchlist(), listWatchlistGroups()])
    items.value = list
    groups.value = groupNames
    // 当前分组移空后已不存在，回退到「自选」
    if (activeGroup.value !== '' && !groupNames.includes(activeGroup.value)) {
      activeGroup.value = ''
    }
    await loadPremiums()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/**
 * 溢价只对 ETF/LOF 取；失败不影响行情显示（溢价是附加列，不能把整页拖成错误页）。
 */
async function loadPremiums() {
  const ids = items.value.filter((i) => i.instrument.type !== 'STOCK').map((i) => i.instrument.id)
  if (!ids.length) {
    premiums.value = {}
    return
  }
  try {
    premiums.value = await getLatestPremiums(ids)
  } catch {
    premiums.value = {}
  }
}

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

async function refreshAll() {
  if (!items.value.length) return
  refreshing.value = true
  refreshSummary.value = null
  try {
    refreshSummary.value = await refreshQuotes(items.value.map((i) => i.instrument.id))
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    refreshing.value = false
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
    await addWatchlist({
      market: form.value.market,
      symbol: form.value.symbol.trim(),
      type: form.value.type,
      name: form.value.name.trim() || undefined,
      groupName: form.value.group || undefined,
    })
    showAdd.value = false
    form.value = { market: 'CN_A', symbol: '', type: 'STOCK', name: '', group: '' }
    await load()
  } catch (e) {
    addError.value = e instanceof ApiError ? e.message : '添加失败，请重试'
  } finally {
    submitting.value = false
  }
}

/** 新建分组：分组本身随第一个成员的加入而创建（空分组不落库）。 */
function newGroup() {
  const name = prompt('输入新分组名称（添加标的时选择该分组即可创建）')
  if (name === null) return
  const trimmed = name.trim()
  if (!trimmed) return
  if (trimmed.length > 64) {
    alert('分组名最长 64 字符')
    return
  }
  showAdd.value = true
  form.value = { market: 'CN_A', symbol: '', type: 'STOCK', name: '', group: trimmed }
}

/** 行内移动分组；group 为 '' 表示移回默认「自选」。 */
async function moveTo(item: WatchlistItem, group: string) {
  if ((item.groupName ?? '') === group) return
  try {
    await moveWatchlistGroup(item.instrument.id, group || null)
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

async function remove(item: WatchlistItem) {
  if (!confirm(`确定从自选移除「${item.instrument.symbol} ${item.instrument.name}」吗？\n（只移除自选关系，不删除标的主数据和历史行情）`)) {
    return
  }
  try {
    await deleteWatchlistItem(item.instrument.id)
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  }
}

function fmtTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

function statusBadge(status: string | undefined): { text: string; cls: string } {
  switch (status) {
    case 'VALID':
      return { text: '有效', cls: 'ok' }
    case 'STALE':
      return { text: '数据过期', cls: 'stale' }
    case 'PARTIAL':
      return { text: '部分缺失', cls: 'stale' }
    case 'INVALID':
      return { text: '无效', cls: 'bad' }
    default:
      return { text: '—', cls: 'off' }
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>自选列表</h1>
        <p class="sub">关注标的的行情总览；行情来自 eastmoney-push2，过期数据显示标注而非 0。</p>
      </div>
      <div class="actions">
        <button class="btn" :disabled="refreshing || !items.length" @click="refreshAll">
          {{ refreshing ? '刷新中…' : '⟳ 刷新行情' }}
        </button>
        <button class="btn primary" @click="showAdd = true">＋ 添加自选</button>
      </div>
    </div>

    <div v-if="refreshSummary" class="banner ok">
      刷新完成：{{ refreshSummary.succeeded }} 成功 / {{ refreshSummary.failed }} 失败（共 {{ refreshSummary.requested }}）
    </div>
    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <!-- 分组页签：自选 + 自定义组合 -->
    <div class="group-tabs">
      <button class="group-tab" :class="{ active: activeGroup === '' }" @click="activeGroup = ''">
        自选 <span class="cnt">{{ countOf('') }}</span>
      </button>
      <button
        v-for="g in groups"
        :key="g"
        class="group-tab"
        :class="{ active: activeGroup === g }"
        @click="activeGroup = g"
      >
        {{ g }} <span class="cnt">{{ countOf(g) }}</span>
      </button>
      <button class="group-tab new" title="新建分组" @click="newGroup">＋ 新建分组</button>
    </div>

    <div v-if="filteredItems.length" class="table-wrap">
      <table class="table">
      <thead>
        <tr>
          <th>标的</th>
          <th>最新价</th>
          <th>涨跌幅</th>
          <th>溢价</th>
          <th>昨收</th>
          <th>行情时间</th>
          <th>状态</th>
          <th style="width: 130px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="it in filteredItems" :key="it.id">
          <td>
            <RouterLink class="detail-link" :to="`/stocks/${it.instrument.id}`">
              <div class="inst-name">{{ it.instrument.name }}<span class="type-tag">{{ it.instrument.type }}</span></div>
              <div class="inst-code mono">{{ it.instrument.symbol }}</div>
            </RouterLink>
          </td>
          <td class="mono">{{ it.quote?.lastPrice ?? '—' }}</td>
          <td class="mono" :style="{ color: changeColor(it.quote?.changePercent) }">
            {{ it.quote?.changePercent != null ? it.quote.changePercent + '%' : '—' }}
          </td>
          <td class="premium-cell">
            <template v-if="it.instrument.type !== 'STOCK'">
              <span
                class="mono"
                :title="premiumHint(it.instrument.id)"
                :style="{ color: changeColor(premiumOf(it.instrument.id)?.premiumRate ?? null) }"
              >
                {{ premiumText(premiumOf(it.instrument.id)?.premiumRate ?? null) }}
              </span>
              <span v-if="premiumOf(it.instrument.id)" class="basis" :title="premiumHint(it.instrument.id)">
                {{ basisLabel(premiumOf(it.instrument.id)!) }}
              </span>
            </template>
            <span v-else class="muted">—</span>
          </td>
          <td class="mono">{{ it.quote?.previousClose ?? '—' }}</td>
          <td class="muted">{{ fmtTime(it.quote?.marketTime ?? null) }}</td>
          <td>
            <span v-if="it.quote" class="badge" :class="statusBadge(it.quote.dataStatus).cls">
              {{ statusBadge(it.quote.dataStatus).text }}
            </span>
            <span v-else class="badge off">—</span>
            <div v-if="it.quote" class="src">采集 {{ fmtTime(it.quote.collectedAt) }}</div>
          </td>
          <td class="ops">
            <RouterLink class="btn small" :to="`/stocks/${it.instrument.id}`">K 线</RouterLink>
            <select
              class="move-select"
              :value="it.groupName ?? ''"
              title="移动到分组"
              @change="moveTo(it, ($event.target as HTMLSelectElement).value)"
            >
              <option value="">自选</option>
              <option v-for="g in groups" :key="g" :value="g">{{ g }}</option>
            </select>
            <button class="btn small danger" @click="remove(it)">移除</button>
          </td>
        </tr>
      </tbody>
    </table>
    </div>

    <div v-else-if="!loading" class="empty">
      {{ activeGroup === '' ? '暂无自选标的，点击右上角「添加自选」开始' : `分组「${activeGroup}」暂无标的，可在行内移动过来` }}
    </div>
    <div v-else>加载中…</div>

    <!-- 添加弹窗 -->
    <div v-if="showAdd" class="modal-mask" @click.self="showAdd = false">
      <div class="modal">
        <h2>添加自选标的</h2>
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
          <input v-model="form.symbol" placeholder="如 600519 / 510300 / 000001" />
        </div>
        <div class="form-row">
          <label>名称</label>
          <input v-model="form.name" placeholder="可选，通常自动从行情源补全" />
        </div>
        <div class="form-row">
          <label>分组</label>
          <select v-model="form.group">
            <option value="">自选（默认）</option>
            <option v-for="g in groups" :key="g" :value="g">{{ g }}</option>
          </select>
        </div>
        <p class="hint">名称将由行情源自动补全并验证；无法验证且未填名称时不会创建。新分组可先点「＋ 新建分组」。</p>
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
.actions {
  display: flex;
  gap: 8px;
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
.muted {
  color: var(--text-tertiary);
  font-size: 12px;
}
.type-tag {
  font-size: 10px;
  color: var(--text-tertiary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
  margin-left: 4px;
}
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
.src {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 2px;
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
.badge.bad {
  background: #fdf2f2;
  color: #b91c1c;
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
  color: var(--text-primary);
  text-decoration: none;
  display: inline-block;
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
}
.ops {
  white-space: nowrap;
}
.ops .btn + .btn,
.ops .move-select {
  margin-left: 6px;
}
.move-select {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 3px 4px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-panel);
  max-width: 96px;
}
.group-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0 12px;
  flex-wrap: wrap;
}
.group-tab {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 999px;
  padding: 4px 14px;
  font-size: 13px;
  cursor: pointer;
  color: var(--text-secondary);
}
.group-tab:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.group-tab.active {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.group-tab.new {
  border-style: dashed;
  color: var(--text-tertiary);
}
.group-tab .cnt {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-left: 2px;
}
.detail-link {
  color: inherit;
  text-decoration: none;
}
.detail-link:hover {
  color: var(--accent);
  text-decoration: underline;
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
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
</style>
