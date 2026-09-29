<template>
  <!-- 非阻塞浮层：账户级提醒（追保/强平/停牌/除权/中签/上市/纪元切换/内幕）堆在右下角。
       不用模态框：6 秒自动收起，点卡片跳消息中心，点叉立即关掉，全程可以继续下单。 -->
  <div class="popup-layer">
    <transition-group name="popup">
      <article v-for="p in messages.popups" :key="p.id" class="popup" :class="'tone-' + p.tone">
        <button class="popup-close" type="button" aria-label="收起提醒" @click.stop="close(p.id)">
          <SvgIcon name="close" />
        </button>
        <h4 class="popup-title" @click="goMessages">{{ p.title }}</h4>
        <p class="popup-text" @click="goMessages">{{ p.text }}</p>
      </article>
    </transition-group>
  </div>
</template>

<script setup>
import { onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessagesStore } from '@/stores/messages'
import SvgIcon from './SvgIcon.vue'

const messages = useMessagesStore()
const router = useRouter()

const AUTO_CLOSE_MS = 6000
const timers = new Map()

// 每条浮层各自计时收起（组件卸载统一清理）
watch(
  () => messages.popups.map((p) => p.id).join('|'),
  () => {
    for (const p of messages.popups) {
      if (timers.has(p.id)) continue
      timers.set(
        p.id,
        setTimeout(() => {
          messages.dismissPopup(p.id)
          timers.delete(p.id)
        }, AUTO_CLOSE_MS)
      )
    }
  },
  { immediate: true }
)

onUnmounted(() => {
  timers.forEach((t) => clearTimeout(t))
  timers.clear()
})

function close(id) {
  const t = timers.get(id)
  if (t) clearTimeout(t)
  timers.delete(id)
  messages.dismissPopup(id)
}

function goMessages() {
  router.push('/messages')
}
</script>

<style scoped>
.popup-layer {
  position: fixed;
  right: var(--sp-4);
  bottom: var(--sp-4);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  width: 300px;
  z-index: 910;
  pointer-events: none; /* 层本身不吃事件，只有卡片可点 */
}
.popup {
  position: relative;
  pointer-events: auto;
  padding: var(--sp-2) var(--sp-3);
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow);
}
.popup-title {
  margin: 0 0 2px;
  font-family: var(--font-fun);
  font-size: var(--fs-md);
  font-weight: 900;
  cursor: pointer;
}
.popup-text {
  margin: 0;
  font-size: var(--fs-sm);
  line-height: 1.45;
  color: var(--ink-2);
  cursor: pointer;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.popup-close {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  background: none;
  color: var(--ink-3);
  cursor: pointer;
}
.popup-close:hover { color: var(--ink); }
.popup-close :deep(svg) { width: 12px; height: 12px; }
.tone-up .popup-title { color: var(--up); }
.tone-down .popup-title { color: var(--down); }
.tone-ink .popup-title { color: var(--ink); }
.popup-enter-active,
.popup-leave-active { transition: transform 0.22s ease, opacity 0.22s ease; }
.popup-enter-from,
.popup-leave-to { opacity: 0; transform: translateX(20px); }
</style>
