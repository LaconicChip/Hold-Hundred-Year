<template>
  <!-- 非阻塞提醒，两档：
       · 持仓股的消息 → 顶部居中的醒目弹窗（常驻 14 秒、带"看这只票"入口，需要你看见）
       · 其它消息     → 右下角轻提示（6 秒自动收起）
       两者都不是模态框：不锁滚动、不吃点击，下单/切页随时可做。 -->
  <div class="notice-layer">
    <transition-group name="hold" tag="div" class="hold-stack">
      <article v-for="p in holdPopups" :key="p.id" class="hold-card" :class="'tone-' + p.tone">
        <span class="hold-badge">持仓</span>
        <div class="hold-main">
          <h4 class="hold-title">{{ p.title }}</h4>
          <p class="hold-text">{{ p.text }}</p>
          <div class="hold-actions">
            <button v-if="p.stockId" class="btn btn-sm" type="button" @click="goStock(p.stockId)">
              看这只票
            </button>
            <button class="btn btn-sm btn-ghost" type="button" @click="goMessages">去消息中心</button>
            <button class="hold-close" type="button" aria-label="收起提醒" @click="close(p.id)">
              <SvgIcon name="close" />
            </button>
          </div>
        </div>
      </article>
    </transition-group>

    <transition-group name="popup" tag="div" class="popup-stack">
      <article v-for="p in cornerPopups" :key="p.id" class="popup" :class="'tone-' + p.tone">
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
import { computed, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessagesStore } from '@/stores/messages'
import SvgIcon from './SvgIcon.vue'

const messages = useMessagesStore()
const router = useRouter()

const HOLD_MS = 14000 // 持仓相关：留更久，让你来得及看见
const CORNER_MS = 6000

const holdPopups = computed(() => messages.popups.filter((p) => p.hold))
const cornerPopups = computed(() => messages.popups.filter((p) => !p.hold))

const timers = new Map()

// 每条提醒各自计时收起（持仓档停留更久）
watch(
  () => messages.popups.map((p) => p.id).join('|'),
  () => {
    for (const p of messages.popups) {
      if (timers.has(p.id)) continue
      const ms = p.hold ? HOLD_MS : CORNER_MS
      timers.set(
        p.id,
        setTimeout(() => {
          messages.dismissPopup(p.id)
          timers.delete(p.id)
        }, ms)
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

function goStock(stockId) {
  close(stockId ? messages.popups.find((p) => p.stockId === stockId)?.id : null)
  router.push('/stock/' + stockId)
}

function goMessages() {
  router.push('/messages')
}
</script>

<style scoped>
.notice-layer { position: fixed; inset: 0; pointer-events: none; z-index: 910; }
.hold-stack {
  position: absolute;
  top: 14px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  width: min(560px, 92vw);
}
.popup-stack {
  position: absolute;
  right: var(--sp-4);
  bottom: var(--sp-4);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  width: 300px;
}

/* 持仓档：米白卡 + 2px 墨描边 + 硬阴影 + 左侧色条 */
.hold-card {
  pointer-events: auto;
  position: relative;
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-3) var(--sp-3) var(--sp-4);
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
}
.hold-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 6px;
  border-radius: 12px 0 0 12px;
  background: var(--ink);
}
.tone-up.hold-card::before { background: var(--up); }
.tone-down.hold-card::before { background: var(--down); }
.hold-badge {
  align-self: flex-start;
  flex: none;
  font-family: var(--font-fun);
  font-size: var(--fs-xs);
  font-weight: 900;
  color: var(--on-brand);
  background: var(--brand);
  border-radius: 4px;
  padding: 1px 5px;
}
.hold-main { flex: 1; min-width: 0; }
.hold-title {
  margin: 0 0 2px;
  font-family: var(--font-fun);
  font-size: var(--fs-md);
  font-weight: 900;
  color: var(--ink);
}
.tone-up .hold-title { color: var(--up); }
.tone-down .hold-title { color: var(--down); }
.hold-text {
  margin: 0;
  font-size: var(--fs-sm);
  line-height: 1.5;
  color: var(--ink-2);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.hold-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}
.btn-sm {
  pointer-events: auto;
  font-size: var(--fs-xs);
  padding: 1px 10px;
}
.btn-ghost { background: var(--paper); }
.hold-close {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  color: var(--ink-3);
  cursor: pointer;
}
.hold-close:hover { color: var(--ink); }
.hold-close :deep(svg) { width: 12px; height: 12px; }

/* 右下角轻提示档 */
.popup {
  pointer-events: auto;
  position: relative;
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

.hold-enter-active, .hold-leave-active,
.popup-enter-active, .popup-leave-active {
  transition: transform 0.22s ease, opacity 0.22s ease;
}
.hold-enter-from, .hold-leave-to { opacity: 0; transform: translateY(-14px); }
.popup-enter-from, .popup-leave-to { opacity: 0; transform: translateX(20px); }
</style>
