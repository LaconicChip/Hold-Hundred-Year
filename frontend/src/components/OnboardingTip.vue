<template>
  <div v-if="visible" class="onboard">
    <div class="sticker onboard-card">
      <button class="ob-close" @click="close" title="关闭引导">×</button>
      <p>{{ tipText }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useMarketStore } from '@/stores/market'

const market = useMarketStore()

// 前三天对应引导文案
const TIPS = {
  1: '点击任意股票试试买入第一手',
  2: '消息中心有传闻，注意辨别真假',
  3: '试试跳过当日直接看收盘晚报'
}
const tipText = computed(() => TIPS[market.currentDay] || '')

// 关闭状态：localStorage 按天记忆 + 本会话 dismissedDays，关闭后当日不再显示
const dismissedDays = ref(new Set())
const visible = computed(() => {
  const d = market.currentDay
  if (d < 1 || d > 3) return false
  if (localStorage.getItem('onboard-closed-' + d) === '1') return false
  return !dismissedDays.value.has(d)
})

function close() {
  const d = market.currentDay
  localStorage.setItem('onboard-closed-' + d, '1')
  dismissedDays.value.add(d)
  dismissedDays.value = new Set(dismissedDays.value)
}
</script>

<style scoped>
.onboard {
  display: flex;
  justify-content: center;
  margin: 14px 0 0;
}
/* 贴纸风气泡卡片（米黄底放大版） */
.onboard-card {
  position: relative;
  background: var(--card);
  padding: 12px 26px;
  font-size: 15px;
  font-family: var(--font-body);
  font-weight: 400;
  transform: rotate(-1.5deg);
  max-width: 520px;
}
.ob-close {
  position: absolute;
  top: 0;
  right: 6px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 16px;
  color: var(--ink-2);
  line-height: 1;
}
</style>
