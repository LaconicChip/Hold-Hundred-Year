import { createRouter, createWebHistory } from 'vue-router'

// 占位路由：Task 12.1 起将正式实现各页面
const routes = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue')
  },
  // 占位路由：供登录后跳转验收（Task 12.2 正式实现大盘页）
  {
    path: '/market',
    name: 'market',
    component: () => import('../views/Market.vue')
  },
  // 个股详情页（Task 12.3）：id 为 stockId（1..24）
  {
    path: '/stock/:id',
    name: 'stock-detail',
    component: () => import('../views/StockDetail.vue')
  },
  // 资产持仓页（Task 12.4）
  {
    path: '/assets',
    name: 'assets',
    component: () => import('../views/Assets.vue')
  },
  // 消息中心（Task 12.5）
  {
    path: '/messages',
    name: 'messages',
    component: () => import('../views/Messages.vue')
  },
  // 营业部排行榜（Task 12.5）
  {
    path: '/leaderboard',
    name: 'leaderboard',
    component: () => import('../views/Leaderboard.vue')
  },
  // 收盘晚报（Task 12.5），支持 ?gameDay=N
  {
    path: '/summary',
    name: 'summary',
    component: () => import('../views/DailySummary.vue')
  },
  // 终局结算（Task 12.5）
  {
    path: '/result',
    name: 'result',
    component: () => import('../views/GameResult.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
