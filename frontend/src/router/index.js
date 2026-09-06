import { createRouter, createWebHistory } from 'vue-router'

// 占位路由：Task 12.1 起将正式实现各页面
const routes = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
