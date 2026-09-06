<template>
  <div class="market-placeholder">
    <div class="card">
      <h1 class="title">大盘页开发中</h1>
      <p class="tip">下一个任务（Task 12.2）将实现正式的大盘页。</p>
      <button class="btn btn-primary" @click="onLogout">退出登录</button>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const user = useUserStore()

function onLogout() {
  user.logout()
  router.push('/login')
}
</script>

<style scoped>
.market-placeholder {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.card {
  text-align: center;
  max-width: 360px;
  width: 100%;
}
.title {
  font-family: var(--font-fun);
  font-size: 26px;
  margin-bottom: 8px;
}
.tip {
  color: var(--ink-2);
  margin-bottom: 18px;
}
</style>
