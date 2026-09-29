<template>
  <!-- 股吧弹幕：底部一条"营业部黑板"。分道飞行 + 限速队列，涨停跌停会刷屏。
       默认开启；关闭后收成一条小浮条，随时可再打开（打开时不回放积压，避免一坨糊屏）。 -->
  <div v-if="!closed" class="danmu-box night">
    <div class="dm-lanes">
      <div
        v-for="f in flyers"
        :key="f.key"
        class="danmu"
        :class="['tone-' + f.tone, { 'is-big': f.big }]"
        :style="f.style"
      >
        <b class="dm-user" :style="{ color: f.userColor }">{{ f.user }}：</b>
        <span class="dm-text">{{ f.text }}</span>
        <span class="dm-like">赞 {{ f.likes }}</span>
      </div>
    </div>

    <div v-if="!flyers.length && !queue.length" class="dm-empty">
      <SvgIcon name="bear" class="dm-mascot" />
      <span class="dm-empty-text">弹幕静悄悄，行情在酝酿</span>
    </div>

    <div class="dm-tools">
      <span class="dm-live"><i class="dm-dot"></i>股吧实时</span>
      <button class="dm-close" type="button" title="隐藏弹幕" @click="toggle">
        <SvgIcon name="close" class="dm-close-ic" />隐藏
      </button>
    </div>
  </div>

  <button v-else class="danmu-closed" @click="toggle">
    <SvgIcon name="bell" class="dm-ic" />
    弹幕已关闭，点击开启
  </button>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useMessagesStore } from '@/stores/messages'
import { toneOf } from '@/constants/messageMeta'
import SvgIcon from './SvgIcon.vue'

const messages = useMessagesStore()

const STORE_KEY = 'danmu-closed'
const closed = ref(localStorage.getItem(STORE_KEY) === '1')

const LANES = 5 // 分道数：每道同一时刻只允许一条，杜绝叠成一坨
const LANE_H = 28 // 每条道的高度（px）
const TICK_MS = 240 // 队列消费节拍
const QUEUE_MAX = 40 // 积压上限：超了丢最旧的，宁可少飘也不堆屏
const NAME_POOL = ['韭菜阿花', '满仓哥', '打板小王', '老登B77', '定投李', '梭哈张', '关灯吃面人', '北向往', '接盘侠A', '长线王']
const USER_COLORS = [
  'var(--yellow)',
  'var(--on-night-up)',
  'var(--on-night-down)',
  'var(--blue-soft)',
  'var(--yellow-soft)'
]

const flyers = ref([])
const queue = ref([])
const laneBusy = new Array(LANES).fill(0) // 每条道占用到什么时候（Date.now() 毫秒）
let cursor = 0 // 已消费的弹幕 seq
let pump = null

// 涨停/跌停/熔断这类大事：字号加大并连飘几条，做出"刷屏"
const BIG_HINTS = ['涨停', '跌停', '封死', '炸板', '崩盘', '熔断', '千股', '腰斩', '翻倍']
const isBig = (text) => BIG_HINTS.some((h) => (text || '').includes(h))
const rand = (n) => Math.floor(Math.random() * n)

function enqueue(dm) {
  const big = isBig(dm.text)
  const copies = big ? 2 + rand(2) : 1 // 大事刷屏 2~3 条，平常一条
  for (let i = 0; i < copies; i++) {
    queue.value.push({
      key: dm.seq + '-' + i,
      text: dm.text,
      user: dm.user || NAME_POOL[rand(NAME_POOL.length)],
      userColor: USER_COLORS[rand(USER_COLORS.length)],
      likes: 1 + rand(99),
      tone: toneOf(dm.text),
      big,
      speed: (big ? 7 : 8) + Math.random() * 4
    })
  }
  if (queue.value.length > QUEUE_MAX) {
    queue.value.splice(0, queue.value.length - QUEUE_MAX)
  }
}

function freeLane(now) {
  for (let i = 0; i < LANES; i++) {
    if (laneBusy[i] <= now) return i
  }
  return -1
}

function tick() {
  if (closed.value || !queue.value.length) return
  const now = Date.now()
  const lane = freeLane(now)
  if (lane < 0) return // 五道全忙就等下一拍，绝不叠在同一行
  const item = queue.value.shift()
  laneBusy[lane] = now + item.speed * 1000
  const top = 10 + lane * LANE_H
  flyers.value.push({
    ...item,
    style: { top: top + 'px', animationDuration: item.speed + 's', fontSize: item.big ? '17px' : '13px' }
  })
  setTimeout(() => {
    flyers.value = flyers.value.filter((x) => x.key !== item.key)
  }, item.speed * 1000 + 200)
}

// 只消费"比游标新"的弹幕：数组封顶、批量到达、页面切换都不会漏也不会回放旧消息
function drain() {
  for (const dm of messages.danmu) {
    if (dm.seq <= cursor) continue
    cursor = dm.seq
    if (!closed.value) enqueue(dm)
  }
}

watch(() => messages.danmuVersion, drain)

onMounted(() => {
  cursor = messages.danmuSeq // 挂载时已有的一律不回放
  pump = setInterval(tick, TICK_MS)
})
onUnmounted(() => {
  if (pump) clearInterval(pump)
  pump = null
})

function toggle() {
  closed.value = !closed.value
  localStorage.setItem(STORE_KEY, closed.value ? '1' : '0')
  if (closed.value) {
    flyers.value = []
    queue.value = []
  } else {
    // 重新开启：从当前进度继续飘，不把积压的一次性倒出来
    cursor = messages.danmuSeq
  }
}
</script>

<style scoped>
.danmu-box {
  position: relative;
  height: 158px;
  overflow: hidden;
  box-shadow: var(--shadow);
  margin-top: var(--sp-4);
}
.dm-lanes { position: absolute; inset: 0; }
.danmu {
  position: absolute;
  left: 100%;
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  max-width: none;
  white-space: nowrap;
  color: var(--on-night-soft);
  font-size: 13px;
  animation-name: dm-fly;
  animation-timing-function: linear;
  animation-fill-mode: none;
}
.dm-user { font-weight: 700; }
.dm-text { font-weight: 500; }
.dm-like {
  font-size: 11px;
  color: var(--ink-3);
  opacity: 0.75;
}
/* 语义着色：利好红贴纸、利空绿贴纸，其余走深色底浅色字 */
.tone-up .dm-text {
  color: var(--on-brand);
  background: var(--up);
  border-radius: 4px;
  padding: 0 5px;
}
.tone-down .dm-text {
  color: var(--on-brand);
  background: var(--down);
  border-radius: 4px;
  padding: 0 5px;
}
.is-big { font-weight: 900; letter-spacing: 0.5px; }
.is-big .dm-text { padding: 1px 7px; }
@keyframes dm-fly {
  from { transform: translateX(0); }
  to { transform: translateX(calc(-100vw - 100%)); }
}

.dm-tools {
  position: absolute;
  top: 6px;
  left: 8px;
  right: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  z-index: 5;
}
.dm-live {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font-fun);
  font-size: var(--fs-xs);
  color: var(--on-night-soft);
  opacity: 0.7;
}
.dm-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--up);
}
.dm-close {
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
  inset: 34px var(--sp-3) var(--sp-3);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 2px dashed rgba(255, 253, 247, 0.25);
  border-radius: var(--radius-md);
  color: var(--on-night-soft);
  opacity: 0.75;
}
.dm-mascot { width: 30px; height: 30px; }
.dm-empty-text { font-family: var(--font-fun); font-size: var(--fs-sm); }

/* 关闭态：收成一条小浮条 */
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

/* 尊重系统级减弱动效：不飘动，改为静态列出最近几条 */
@media (prefers-reduced-motion: reduce) {
  .danmu {
    animation: none !important;
    left: 8px;
    transform: none;
  }
}
</style>
