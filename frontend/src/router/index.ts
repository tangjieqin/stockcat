import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由结构与 docs/UI.md 的五组导航保持一致。
 * P0.1 除首页外均为占位页，业务页面在后续阶段逐个实现。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: () => import('../layouts/MainLayout.vue'),
      children: [
        { path: '', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '首页' } },
        // AI 助手（P2）：唯一独立开发的 AI 主页面，其他业务页后续用按钮/卡片嵌入
        { path: 'assistant', name: 'assistant', component: () => import('../views/AssistantView.vue'), meta: { title: 'AI 助手' } },

        // 告警
        { path: 'alerts', name: 'alert-overview', component: () => import('../views/AlertOverviewView.vue'), meta: { title: '告警总览' } },
        { path: 'alerts/targets', name: 'alert-targets', component: () => import('../views/AlertTargetsView.vue'), meta: { title: '监控' } },
        { path: 'alerts/rules', name: 'alert-rules', component: () => import('../views/AlertRulesView.vue'), meta: { title: '告警规则' } },
        { path: 'alerts/history', name: 'alert-history', component: () => import('../views/AlertHistoryView.vue'), meta: { title: '告警历史' } },

        // 我的股票
        { path: 'stocks/watchlist', name: 'watchlist', component: () => import('../views/WatchlistView.vue'), meta: { title: '自选列表' } },
        // 详情页：只接受数字 id，避免和上面的字面路由抢匹配
        { path: 'stocks/:id(\\d+)', name: 'instrument-detail', component: () => import('../views/InstrumentDetailView.vue'), meta: { title: '标的详情' } },
        { path: 'stocks/positions', name: 'positions', component: () => import('../views/PositionsView.vue'), meta: { title: '我的持仓' } },
        { path: 'stocks/trades', name: 'trades', component: () => import('../views/TradeRecordsView.vue'), meta: { title: '交易流水' } },
        // 持仓变化自 P1.5 起并入持仓页的页签，旧链接重定向过去
        { path: 'stocks/position-changes', redirect: { path: '/stocks/positions', query: { tab: 'changes' } } },

        // 复盘
        { path: 'recap/market', name: 'market-recap', component: () => import('../views/MarketRecapView.vue'), meta: { title: '市场复盘' } },
        { path: 'recap/diary', name: 'my-diary', component: () => import('../views/DiaryView.vue'), meta: { title: '我的日记' } },

        // 其他
        { path: 'settings/data-source', name: 'data-source', component: () => import('../views/PlaceholderView.vue'), meta: { title: '数据源' } },
        { path: 'settings/notification', name: 'notification', component: () => import('../views/NotificationView.vue'), meta: { title: '通知配置' } },
        { path: 'settings/status', name: 'system-status', component: () => import('../views/PlaceholderView.vue'), meta: { title: '系统状态' } },
        { path: 'settings/backup', name: 'backup', component: () => import('../views/PlaceholderView.vue'), meta: { title: '备份', phase: 'P4' } },
        { path: 'settings/ai', name: 'ai-config', component: () => import('../views/AiConfigView.vue'), meta: { title: 'AI 配置' } },
      ],
    },
  ],
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '')} · StockCat`
})

export default router
