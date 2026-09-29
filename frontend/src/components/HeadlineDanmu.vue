<template>
  <!-- 头条大弹幕：市场级大事（权威公告、千股跌停/涨停、熔断、救市、强平）横贯屏幕一次。
       整层 pointer-events:none，飘过即消失，绝不遮挡或打断任何操作（spec §12.1）。 -->
  <div class="headline-layer" aria-hidden="true">
    <div
      v-if="current"
      :key="current.id"
      class="headline"
      :class="'tone-' + current.tone"
      @animationend="onEnd"
    >
      <span class="headline-tag">头条</span>
      <span class="headline-title">{{ current.title }}</span>
      <span class="headline-text">{{ current.text }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useMessagesStore } from '@/stores/messages'

const messages = useMessagesStore()
const current = computed(() => messages.banners[0] || null)

// 飘完一条出队；队列里还有就接着飘
function onEnd() {
  messages.shiftBanner()
}
</script>

<style scoped>
.headline-layer {
  position: fixed;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 900;
}
.headline {
  position: absolute;
  top: 15%;
  left: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--sp-3);
  max-width: 92vw;
  padding: var(--sp-2) var(--sp-4);
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  white-space: nowrap;
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
  font-weight: 900;
  animation: headline-fly 11s linear 1;
}
.headline-tag {
  flex: none;
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 0 6px;
  border: var(--border-thin);
  border-radius: 6px;
  color: var(--ink);
}
.headline-title { flex: none; }
.headline-text {
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-2);
  font-size: var(--fs-md);
}
.tone-up { color: var(--up); }
.tone-down { color: var(--down); }
.tone-ink { color: var(--ink); }
@keyframes headline-fly {
  from { transform: translateX(104vw); }
  to { transform: translateX(-105%); }
}
/* 尊重减少动效偏好：不飘，原地停留 6 秒后同样出队 */
@media (prefers-reduced-motion: reduce) {
  .headline {
    top: 12%;
    left: 50%;
    animation: headline-hold 6s ease 1;
  }
  @keyframes headline-hold {
    0%, 100% { opacity: 0; transform: translateX(-50%); }
    12%, 88% { opacity: 1; transform: translateX(-50%); }
  }
}
</style>
