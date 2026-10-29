<template>
  <!-- 最高一档提醒（后端 type=ALERT）：模态窗 + 服务端暂停这一份世界，必须亲手点掉。
       只有钱已经出事（追保/强平/爆仓、高利贷到期强卖与逾期）或票突然买不到（停牌潮）才走到这里。 -->
  <div v-if="current" class="alert-scrim">
    <div class="alert-card card" :class="'tone-' + current.tone" role="alertdialog" aria-modal="true">
      <div class="alert-head">
        <span class="alert-badge">要紧事</span>
        <h3 class="alert-title">{{ current.title }}</h3>
        <button class="alert-close" type="button" aria-label="关闭" @click="close">
          <SvgIcon name="close" />
        </button>
      </div>
      <p class="alert-text">{{ current.text }}</p>
      <!-- 关掉只认这一个动作：点叉或点"知道了"，不自动消失、点空白处也不关 -->
      <div class="alert-actions">
        <button v-if="current.stockId" class="btn btn-sm" type="button" @click="goStock">看这只票</button>
        <button class="btn btn-sm btn-ghost" type="button" @click="goMessages">去消息中心</button>
        <button class="btn btn-primary" type="button" @click="close">
          知道了，继续<span v-if="queued" class="alert-queue num">（还有 {{ queued }} 条）</span>
        </button>
      </div>
      <span class="alert-foot muted-2">
        {{ market.paused ? '行情已暂停，看完才走' : '正在暂停行情…' }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessagesStore } from '@/stores/messages'
import { useMarketStore } from '@/stores/market'
import SvgIcon from './SvgIcon.vue'

const messages = useMessagesStore()
const market = useMarketStore()
const router = useRouter()

const current = computed(() => messages.modals[0] || null)
const queued = computed(() => Math.max(messages.modals.length - 1, 0))

// 弹窗出现即请求冻结这一份世界，队列清空即恢复。pausedHere 只是本地记账，
// 保证连续两条要紧事也只暂停一次、恢复一次。
// 2026-10-25：服务端在 ALERT 落库时已经自己暂停（这才是"一定停得住"的那道保险），
// 这里的调用变成幂等补刀；文案与倒计时只认 market.paused（服务端真值）。
let pausedHere = false
watch(
  () => messages.modals.length,
  async (n) => {
    if (n > 0 && !pausedHere) {
      pausedHere = true
      await market.pauseWorld()
    }
    if (n === 0 && pausedHere) {
      pausedHere = false
      await market.resumeWorld()
    }
  },
  { immediate: true }
)

function close() {
  messages.shiftModal()
}

function goStock() {
  const id = current.value && current.value.stockId
  if (id) router.push('/stock/' + id)
}

function goMessages() {
  router.push('/messages')
}
</script>

<style scoped>
/* 遮罩吃掉全部点击：要紧事没看完就先别动别的（视觉只用既有令牌，不新造颜色） */
.alert-scrim {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4);
  background: color-mix(in srgb, var(--ink) 42%, transparent);
}
.alert-card {
  width: min(460px, 100%);
  padding: var(--sp-4) var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  box-shadow: var(--shadow-lg);
}
.alert-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.alert-close {
  margin-left: auto;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  background: var(--card);
  color: var(--ink-2);
  border: var(--border);
  border-radius: 50%;
  box-shadow: var(--shadow-sm);
  cursor: pointer;
}
.alert-close:hover { color: var(--brand); transform: translate(-1px, -1px); }
.alert-close :deep(svg) { width: 14px; height: 14px; }
.alert-badge {
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  padding: 2px 8px;
  border: var(--border);
  border-radius: 4px;
  background: var(--up);
  color: var(--on-brand);
}
.alert-title {
  margin: 0;
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
  color: var(--ink);
}
.alert-text {
  margin: 0;
  font-size: var(--fs-md);
  line-height: 1.75;
  color: var(--ink-2);
}
.alert-actions { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.alert-queue { margin-left: 6px; font-size: var(--fs-sm); }
.alert-foot { font-size: var(--fs-xs); }
.tone-down .alert-badge { background: var(--down); }
</style>
