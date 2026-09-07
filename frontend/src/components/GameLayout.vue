<template>
  <!-- 游戏主布局（规格 §12.1 纵向菜单布局）：左侧纵向导航 + 右侧内容区 -->
  <div class="game-layout">
    <aside class="side-nav card">
      <router-link
        v-for="item in NAV_ITEMS"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.path) }"
      >
        <SvgIcon :name="item.icon" class="nav-ic" />
        <span>{{ item.label }}</span>
      </router-link>
    </aside>
    <main class="page-main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'
import SvgIcon from './SvgIcon.vue'

// 图标取自 icons.svg sprite（currentColor 继承）：榜单暂无专属 symbol，用火苗（热榜）承担
const NAV_ITEMS = [
  { path: '/market', label: '大盘', icon: 'chart' },
  { path: '/assets', label: '资产', icon: 'bank' },
  { path: '/messages', label: '消息', icon: 'bell' },
  { path: '/leaderboard', label: '排行榜', icon: 'flame' }
]

const route = useRoute()
function isActive(path) {
  return route.path === path
}
</script>

<style scoped>
.game-layout {
  display: flex;
  align-items: flex-start;
  gap: 20px;
  max-width: 1240px;
  margin: 0 auto;
  padding: 24px 20px 80px;
}
.side-nav {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 14px;
  min-width: 148px;
  position: sticky;
  top: 24px;
}
.nav-item {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-family: var(--font-fun);
  font-size: 17px;
  color: var(--ink);
  background: var(--card);
  border: var(--border);
  border-radius: 12px;
  box-shadow: 3px 3px 0 var(--ink);
  padding: 10px 14px;
  text-decoration: none;
  transition: 0.08s;
}
.nav-item:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 var(--ink);
}
.nav-item.active {
  background: var(--yellow);
  transform: translate(1px, 1px);
  box-shadow: 2px 2px 0 var(--ink);
}
.nav-ic {
  width: 1.2em;
  height: 1.2em;
}
.page-main {
  flex: 1;
  min-width: 0;
}
</style>
