<template>
  <div v-if="!closed" class="danmu-box night">
    <button class="dm-close" title="隐藏弹幕" @click="toggle">
      <SvgIcon name="close" class="dm-close-ic" />隐藏
    </button>
    <template v-if="flyers.length">
      <div v-for="f in flyers" :key="f.id" class="danmu" :style="f.style">
        <b v-if="f.user" :style="{ color: f.color }">{{ f.user }}：</b>{{ f.text }}
      </div>
    </template>
    <div v-else class="empty empty-night dm-empty">
      <SvgIcon name="bear" class="dm-mascot" />
      <span class="empty-title">弹幕静悄悄</span>
      <span class="empty-desc">行情在酝酿，先喝口茶</span>
    </div>
  </div>

  <!-- 关闭态：收成一条小浮条，不再是一整块黑板 -->
  <button v-else class="danmu-closed" @click="toggle">
    <SvgIcon name="bell" class="dm-ic" />
    弹幕已关闭，点击开启
  </button>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useMessagesStore } from '@/stores/messages'
import SvgIcon from './SvgIcon.vue'

const messages = useMessagesStore()

// 关闭状态存 localStorage
const STORE_KEY = 'danmu-closed'
const closed = ref(localStorage.getItem(STORE_KEY) === '1')

const flyers = ref([])
// 深色底上的弹幕配色（取令牌，避免硬编码）
const PALETTE = [
  'var(--yellow)',
  'var(--on-night-down)',
  'var(--on-night-up)',
  'var(--blue-soft)',
  'var(--yellow-soft)'
]
const seen = new Set() // 本会话已生成的弹幕 id，避免重复飘

function spawn(dm) {
  const id = Date.now() + Math.random()
  const top = 8 + Math.random() * 104
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
  overflow: hidden;
  box-shadow: var(--shadow);
  margin-top: var(--sp-4);
}
.danmu {
  position: absolute;
  white-space: nowrap;
  color: var(--on-night-soft);
  font-size: var(--fs-sm);
  animation: dm linear;
}
.danmu b { font-weight: 700; }
@keyframes dm {
  from { left: 100%; }
  to { left: -30%; }
}

.dm-close {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 5;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-family: var(--font-fun);
  font-size: var(--fs-xs);
  border: var(--border);
  background: var(--card);
  color: var(--ink);
  border-radius: var(--radius-sm);
  padding: 1px 8px;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
}
.dm-close:hover { background: var(--hover); }
.dm-close-ic { width: 11px; height: 11px; }

.dm-empty {
  position: absolute;
  inset: var(--sp-3);
  border: 2px dashed rgba(255, 253, 247, 0.3);
  padding: var(--sp-3);
}
.dm-mascot { width: 34px; height: 34px; opacity: 0.85; }

/* 关闭态小浮条 */
.danmu-closed {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--sp-3);
  padding: 4px var(--sp-4);
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--card);
  border: 2px dashed var(--ink-3);
  border-radius: var(--radius-pill);
  cursor: pointer;
  transition: 0.08s;
}
.danmu-closed:hover {
  color: var(--brand);
  border-color: var(--brand);
  border-style: solid;
}
.dm-ic { width: 1.1em; height: 1.1em; }

/* 尊重系统级减弱动效：关闭飘动动画 */
@media (prefers-reduced-motion: reduce) {
  .danmu { animation: none !important; display: none; }
}
</style>
