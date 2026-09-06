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
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
