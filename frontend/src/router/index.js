import { createRouter, createWebHistory } from 'vue-router'

// 路由表：8 个页面（spec §12.1 竖向菜单顺序）；无路由守卫，未登录由 http.js 的 401 拦截兜底跳 /login
const routes = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue')
  },
  // 游戏主布局（规格 §12.1 纵向菜单布局）：主页面共享左侧纵向导航
  {
    path: '/',
    component: () => import('../components/GameLayout.vue'),
    children: [
      // 大盘页（Task 12.2）
      {
        path: 'market',
        name: 'market',
        component: () => import('../views/Market.vue')
      },
      // 个股详情页（Task 12.3）：id 为 stockId（1..24）
      {
        path: 'stock/:id',
        name: 'stock-detail',
        component: () => import('../views/StockDetail.vue')
      },
      // 资产持仓页（Task 12.4）
      {
        path: 'assets',
        name: 'assets',
        component: () => import('../views/Assets.vue')
      },
      // 消息中心（Task 12.5）
      {
        path: 'messages',
        name: 'messages',
        component: () => import('../views/Messages.vue')
      },
      // 营业部排行榜（Task 12.5）
      {
        path: 'leaderboard',
        name: 'leaderboard',
        component: () => import('../views/Leaderboard.vue')
      },
      // 收盘晚报（规格 §12.1 主菜单「日报」），支持 ?gameDay=N
      {
        path: 'summary',
        name: 'summary',
        component: () => import('../views/DailySummary.vue')
      },
      // 终局结算（规格 §12.1 主菜单「结算」）
      {
        path: 'result',
        name: 'result',
        component: () => import('../views/GameResult.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
