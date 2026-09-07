<template>
  <div v-if="visible" class="onboard">
    <div class="sticker onboard-card">
      <SvgIcon name="info" class="ob-ic" />
      <p class="ob-text">{{ tipText }}</p>
      <button class="ob-close" @click="close" title="关闭引导">
        <SvgIcon name="close" class="ob-close-ic" />
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useMarketStore } from '@/stores/market'
import SvgIcon from './SvgIcon.vue'

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
  margin: var(--sp-4) 0 0;
}
/* 贴纸风气泡卡片（米黄底放大版） */
.onboard-card {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  background: var(--yellow-soft);
  padding: var(--sp-3) 34px var(--sp-3) var(--sp-4);
  font-size: var(--fs-md);
  font-family: var(--font-body);
  font-weight: 400;
  transform: rotate(-1.5deg);
  max-width: 520px;
}
.ob-ic {
  width: 1.2em;
  height: 1.2em;
  color: var(--brand);
  flex: none;
}
.ob-text { margin: 0; }
.ob-close {
  position: absolute;
  top: 0;
  right: 6px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--ink-2);
  display: inline-flex;
  padding: 3px;
}
.ob-close:hover { color: var(--ink); }
.ob-close-ic { width: 13px; height: 13px; }
</style>
