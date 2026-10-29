<template>
  <div v-if="items.length" class="danmu-layer" aria-hidden="true">
    <span
      v-for="item in items"
      :key="item.key"
      class="danmu"
      :class="item.cls"
      :style="item.style"
    >
      <b v-if="item.category" class="danmu-cat">{{ item.category }}</b>{{ item.text }}
    </span>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * 股吧飘屏弹幕层（2026-10-20 从 KlineChart 里抽出来共用）。
 *
 * <p>之前只有日K图有这层，分时图没有——玩家说"弹幕不显示在分时图里"，就是这个差别。
 * 现在两张图都用它，密度也调高了（航道更多、间隔更短），跟真实行情软件里的股吧弹幕一个观感。
 *
 * <p>只消费"新增"的帖子（按 seq 去重），首帧最多回放最近几条作为"这里有人在聊"的信号；
 * 图层 pointer-events:none，不挡缩放、拖动和 tooltip。
 */
const props = defineProps({
  // 帖子流：[{seq, text, category}]
  posts: { type: Array, default: () => [] },
  // 同时可见的航道数
  lanes: { type: Number, default: 7 },
  // 每条之间的间隔（毫秒，实际会再乘一个随机抖动）
  spawnMs: { type: Number, default: 400 },
  // 飞行时长（毫秒）
  flightMs: { type: Number, default: 8000 },
  // 首帧回放条数
  replay: { type: Number, default: 10 }
})

const items = ref([])
const queue = []
const seen = new Set()
let lastSeq = 0
let spawnTimer = null
let nextKey = 0

function seqOf(post) {
  return Number(post && (post.seq != null ? post.seq : post.id)) || 0
}

function enqueue(post) {
  const seq = seqOf(post)
  if (!seq || seen.has(seq)) return
  seen.add(seq)
  queue.push(post)
  while (queue.length > props.lanes * 3) queue.shift()
}

function scheduleSpawn() {
  if (spawnTimer) return
  // 间隔带随机抖动：匀速吐会看成"来一波→飘完→等一下→又来一波"的脉冲，
  // 抖开之后才是持续的流（真实的股吧也不是整齐划一地发）
  const delay = props.spawnMs * (0.4 + Math.random() * 1.4)
  spawnTimer = setTimeout(() => {
    spawnTimer = null
    if (queue.length && items.value.length < props.lanes) {
      spawn(queue.shift())
    }
    if (queue.length) scheduleSpawn()
  }, delay)
}

function spawn(post) {
  const used = new Set(items.value.map((item) => item.lane))
  const lane = Array.from({ length: props.lanes }, (_, i) => i).find((n) => !used.has(n))
  if (lane == null) {
    queue.unshift(post)
    scheduleSpawn()
    return
  }
  const text = String(post.text || '')
  const key = ++nextKey
  const category = String(post.category || '')
  const cls = category === '事件'
    ? 'is-event'
    : category === '涨停' || category === '急拉'
      ? 'is-up'
      : category === '跌停' || category === '跳水'
        ? 'is-down'
        : ''
  items.value.push({
    key,
    lane,
    category,
    text,
    cls,
    style: {
      top: `${8 + lane * 24}px`,
      animationDuration: `${props.flightMs}ms`
    }
  })
  setTimeout(() => {
    items.value = items.value.filter((item) => item.key !== key)
  }, props.flightMs + 400)
}

watch(
  () => props.posts,
  (posts) => {
    const list = Array.isArray(posts) ? posts : []
    const seqs = list.map(seqOf).filter(Boolean)
    const maxSeq = seqs.length ? Math.max(...seqs) : 0
    if (maxSeq < lastSeq) {
      // 换了一局/换了票：seq 回退，清空旧记忆
      seen.clear()
      queue.length = 0
      items.value = []
    }
    lastSeq = Math.max(lastSeq, maxSeq)
    if (!seen.size && list.length) {
      // 首次挂载：只回放最近几条，给玩家一个"这里有人聊"的信号
      list.slice(-props.replay).forEach(enqueue)
    } else {
      list.forEach(enqueue)
    }
    if (queue.length) scheduleSpawn()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (spawnTimer) {
    clearTimeout(spawnTimer)
    spawnTimer = null
  }
})
</script>

<style scoped>
.danmu-layer {
  position: absolute;
  left: 56px;
  right: 46px;
  top: 14px;
  height: 62%;
  overflow: hidden;
  pointer-events: none;
  z-index: 2;
}
.danmu {
  position: absolute;
  left: 100%;
  white-space: nowrap;
  padding: 1px 8px;
  /* 2026-10-25：整体调透（0.94 → 0.52），边框与阴影一起收轻。
     弹幕是背景氛围，不该在分时图上糊一层不透明的贴纸；文字仍保持 86% 墨色保证可读。 */
  border: 1px solid color-mix(in srgb, var(--ink) 38%, transparent);
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--card) 52%, transparent);
  color: color-mix(in srgb, var(--ink) 86%, transparent);
  font-size: 11px;
  line-height: 1.35;
  box-shadow: none;
  animation-name: danmu-fly;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
  will-change: left;
}
.danmu-cat {
  margin-right: 4px;
  font-weight: 700;
  color: color-mix(in srgb, var(--brand) 78%, transparent);
}
.danmu.is-up {
  color: color-mix(in srgb, var(--up) 82%, transparent);
  border-color: color-mix(in srgb, var(--up) 42%, transparent);
}
.danmu.is-up .danmu-cat {
  color: color-mix(in srgb, var(--up) 82%, transparent);
}
.danmu.is-down {
  color: color-mix(in srgb, var(--down) 82%, transparent);
  border-color: color-mix(in srgb, var(--down) 42%, transparent);
}
.danmu.is-down .danmu-cat {
  color: color-mix(in srgb, var(--down) 82%, transparent);
}
/* 事件跟读（2026-10-25）：消息落地后股吧接的那句话，给一条淡黄底把它从闲聊里挑出来 */
.danmu.is-event {
  border-color: color-mix(in srgb, var(--brand) 45%, transparent);
  background: color-mix(in srgb, var(--yellow) 42%, transparent);
  color: color-mix(in srgb, var(--ink) 90%, transparent);
}
.danmu.is-event .danmu-cat {
  color: color-mix(in srgb, var(--brand) 90%, transparent);
}
@keyframes danmu-fly {
  from {
    left: 100%;
  }
  to {
    left: -100%;
  }
}
</style>
