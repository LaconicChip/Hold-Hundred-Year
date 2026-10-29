<template>
  <!-- 头条大弹幕：市场级大事（权威公告、千股跌停/涨停、熔断、救市、强平）横贯屏幕一次。
       整层 pointer-events:none，飘过即消失，绝不遮挡或打断任何操作（spec §12.1）。
       2026-10-25：字号、纸边与硬阴影整体加粗一档，并改成「大标题 + 副标题」两行——
       原来单行夹着正文，字一大就被省略号吃掉；两行之后标题能顶到 46px 还不挤。 -->
  <div class="headline-layer" aria-hidden="true">
    <div
      v-if="current"
      :key="current.id"
      class="headline"
      :class="'tone-' + current.tone"
      @animationend="onEnd"
    >
      <span class="headline-tag">头条</span>
      <span class="headline-body">
        <b class="headline-title">{{ current.title }}</b>
        <span class="headline-text">{{ current.text }}</span>
      </span>
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
  top: 14%;
  left: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--sp-3);
  max-width: 94vw;
  padding: 12px 24px;
  background: var(--card);
  border: 3px solid var(--ink);
  border-radius: var(--radius);
  box-shadow: 7px 7px 0 var(--ink);
  white-space: nowrap;
  font-family: var(--font-fun);
  animation: headline-fly 13s linear 1;
}
.headline-tag {
  flex: none;
  align-self: flex-start;
  margin-top: 4px;
  font-size: var(--fs-sm);
  font-weight: 700;
  padding: 2px 8px;
  border: 2px solid var(--ink);
  border-radius: 6px;
  color: var(--ink);
  background: var(--yellow);
}
.headline-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.headline-title {
  font-size: clamp(28px, 3vw, 46px);
  font-weight: 900;
  line-height: 1.08;
  letter-spacing: -0.5px;
}
.headline-text {
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-2);
  font-size: clamp(15px, 1.15vw, 21px);
  font-weight: 600;
  line-height: 1.25;
}
.tone-up .headline-title { color: var(--up); }
.tone-down .headline-title { color: var(--down); }
.tone-ink .headline-title { color: var(--ink); }
@keyframes headline-fly {
  from { transform: translateX(104vw); }
  to { transform: translateX(-105%); }
}
/* 手机端：贴顶 6px 会被状态栏/浏览器栏压住，玩家几乎看不见；放到屏幕上部 20% 处，
   既不挡底部标签栏，也能从正文上方横穿而过（整层 pointer-events:none，不打断操作）。
   字号仍要比正文大一截，否则"头条"和普通气泡没区别。 */
@media (max-width: 720px) {
  .headline {
    top: 20%;
    max-width: 100vw;
    padding: 8px 12px;
    gap: var(--sp-2);
    border-width: 2px;
    border-radius: var(--radius-sm);
    box-shadow: 4px 4px 0 var(--ink);
  }
  .headline-tag {
    font-size: var(--fs-xs);
    padding: 1px 5px;
    margin-top: 2px;
    border-width: 1.5px;
  }
  .headline-title { font-size: 24px; }
  .headline-text { font-size: var(--fs-sm); }
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
