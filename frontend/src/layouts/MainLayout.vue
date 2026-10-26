<script setup lang="ts">
interface NavItem {
  label: string
  to: string
  exact?: boolean
  phase?: string
}

/**
 * 主布局：左侧导航（对应 docs/UI.md 五组导航）+ 右侧内容区。
 */
const navGroups: Array<{ label: string; items: NavItem[] }> = [
  {
    label: '首页',
    items: [{ label: '总览', to: '/', exact: true }],
  },
  {
    label: '告警',
    items: [
      { label: '总览', to: '/alerts' },
      { label: '监控', to: '/alerts/targets' },
      { label: '规则', to: '/alerts/rules' },
      { label: '历史', to: '/alerts/history' },
    ],
  },
  {
    label: '我的股票',
    items: [
      { label: '自选列表', to: '/stocks/watchlist' },
      { label: '我的持仓', to: '/stocks/positions' },
      { label: '交易流水', to: '/stocks/trades' },
      { label: '持仓变化', to: '/stocks/position-changes', phase: 'P1' },
    ],
  },
  {
    label: '复盘',
    items: [
      { label: '市场复盘', to: '/recap/market' },
      { label: '我的日记', to: '/recap/diary' },
    ],
  },
  {
    label: '其他',
    items: [
      { label: '数据源', to: '/settings/data-source' },
      { label: '通知配置', to: '/settings/notification' },
      { label: '系统状态', to: '/settings/status' },
      { label: '备份', to: '/settings/backup', phase: 'P4' },
      { label: 'AI 配置', to: '/settings/ai', phase: 'P2' },
    ],
  },
]
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">StockCat 🐱</div>
      <nav v-for="group in navGroups" :key="group.label" class="nav-group">
        <div class="nav-group-label">{{ group.label }}</div>
        <RouterLink
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ disabled: item.phase }"
          active-class="active"
        >
          {{ item.label }}
          <span v-if="'phase' in item && item.phase" class="phase-tag">{{ item.phase }}</span>
        </RouterLink>
      </nav>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}
.sidebar {
  width: 200px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  padding: 16px 8px;
  background: var(--bg-panel);
}
.brand {
  font-weight: 700;
  font-size: 16px;
  padding: 4px 12px 16px;
}
.nav-group {
  margin-bottom: 14px;
}
.nav-group-label {
  font-size: 11px;
  color: var(--text-tertiary);
  padding: 2px 12px 6px;
  letter-spacing: 1px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 6px;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 13px;
}
.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.nav-item.disabled {
  opacity: 0.5;
}
.phase-tag {
  margin-left: auto;
  font-size: 10px;
  color: var(--text-tertiary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
}
.content {
  flex: 1;
  padding: 24px 32px;
  min-width: 0;
}
</style>
