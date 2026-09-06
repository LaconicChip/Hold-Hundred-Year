<template>
  <div v-if="!closed" class="danmu-box">
    <button class="dm-close" @click="toggle" title="关闭弹幕">隐藏</button>
    <template v-if="flyers.length">
      <div
        v-for="f in flyers"
        :key="f.id"
        class="danmu"
        :style="f.style"
      >
        <b v-if="f.user" :style="{ color: f.color }">{{ f.user }}：</b>{{ f.text }}
      </div>
    </template>
    <div v-else class="dm-empty">弹幕静悄悄，行情在酝酿</div>
  </div>
  <div v-else class="danmu-closed" @click="toggle">弹幕已关闭，点击开启</div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useMessagesStore } from '@/stores/messages'

const messages = useMessagesStore()

// 关闭状态存 localStorage
const STORE_KEY = 'danmu-closed'
const closed = ref(localStorage.getItem(STORE_KEY) === '1')

const flyers = ref([])
const PALETTE = ['#ffd166', '#8be0a8', '#ffb4ab', '#9ecbff', '#f0e6ff']
const seen = new Set() // 本会话已生成的弹幕 id，避免重复飘

function spawn(dm) {
  const id = Date.now() + Math.random()
  const top = 8 + Math.random() * 110
  const dur = 9 + Math.random() * 6
  const color = PALETTE[Math.floor(Math.random() * PALETTE.length)]
  flyers.value.push({
    id,
    user: dm.user || '',
    text: dm.text || '',
    color,
    style: { top: top + 'px', animationDuration: dur + 's' }
  })
  // 动画结束后移除
  setTimeout(() => {
    flyers.value = flyers.value.filter((x) => x.id !== id)
  }, dur * 1000)
}

// 取出未处理的新弹幕生成飞条
function processNew() {
  if (closed.value) return
  messages.danmu.forEach((dm) => {
    if (seen.has(dm.id)) return
    seen.add(dm.id)
    spawn(dm)
  })
}

// 监听弹幕数组长度变化（新增即飘）
watch(
  () => messages.danmu.length,
  () => processNew()
)

onMounted(() => {
  // 挂载时仅标记已有弹幕为已见，不回溯轰炸；之后新增才飘
  messages.danmu.forEach((dm) => seen.add(dm.id))
})
onUnmounted(() => {
  flyers.value = []
})

function toggle() {
  closed.value = !closed.value
  localStorage.setItem(STORE_KEY, closed.value ? '1' : '0')
  if (closed.value) {
    flyers.value = []
  } else {
    // 重新开启：清空已见，让存量弹幕可再次飘出
    seen.clear()
    processNew()
  }
}
</script>

<style scoped>
.danmu-box {
  position: relative;
  height: 150px;
  background: #1d1a17;
  border: 2px solid var(--ink);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: var(--shadow);
  margin-top: 16px;
}
.danmu {
  position: absolute;
  white-space: nowrap;
  color: #fdf6e3;
  font-size: 13px;
  animation: dm linear;
}
.danmu b {
  font-weight: 700;
}
@keyframes dm {
  from {
    left: 100%;
  }
  to {
    left: -30%;
  }
}
.dm-close {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 5;
  font-size: 11px;
  border: 1.5px solid var(--ink);
  background: var(--card);
  border-radius: 6px;
  padding: 1px 8px;
  cursor: pointer;
  font-family: var(--font-fun);
}
.dm-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #a89f91;
  font-size: 13px;
}
.danmu-closed {
  height: 34px;
  background: #1d1a17;
  border: 2px solid var(--ink);
  border-radius: 8px;
  color: #fdf6e3;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  cursor: pointer;
  margin-top: 16px;
}
/* 尊重系统级减弱动效：关闭飘动动画 */
@media (prefers-reduced-motion: reduce) {
  .danmu {
    animation: none !important;
    display: none;
  }
}
</style>
