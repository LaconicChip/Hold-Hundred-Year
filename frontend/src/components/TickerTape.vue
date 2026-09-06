<template>
  <div class="ticker">
    <div class="track">
      <span v-for="(it, i) in doubled" :key="i" class="tk-item">
        <i v-if="it.dir !== 'none'" class="dot" :class="it.dir === 'up' ? 'r' : 'g'"></i>{{ it.text }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useQuotesStore } from '@/stores/quotes'
import { useMessagesStore } from '@/stores/messages'

const quotes = useQuotesStore()
const messages = useMessagesStore()

// 合成跑马灯文本：大波动快讯 + EVENT 消息标题 + 通知快讯
const items = computed(() => {
  const out = []
  // 1) 行情大波动快讯（仅后端有的字段：名称 + changePct 推断）
  quotes.quoteList.forEach((q) => {
    if (q.changePct >= 9.8) out.push({ text: `${q.name}封死涨停`, dir: 'up' })
    else if (q.changePct <= -9.8) out.push({ text: `${q.name}封死跌停`, dir: 'down' })
  })
  // 2) 消息中心中 type=EVENT 的标题
  messages.list
    .filter((m) => m && m.type === 'EVENT')
    .forEach((m) => {
      const t = m.title || m.content || ''
      if (t) out.push({ text: t, dir: 'none' })
    })
  // 3) 通知快讯（取最近若干条，字段不定容错）
  messages.notifications
    .slice(-6)
    .forEach((n) => {
      const t = n.title || n.content || n.ipoName || n.text || ''
      if (t) out.push({ text: t, dir: 'none' })
    })
  if (!out.length) return [{ text: '欢迎来到百年证券营业部，行情即将开始', dir: 'none' }]
  return out
})

// 复制两份首尾相接，配合 @keyframes marquee 平移 -50% 无缝滚动
const doubled = computed(() => items.value.concat(items.value))
</script>

<style scoped>
.ticker {
  background: #1d1a17;
  color: #ffb4ab;
  font-family: var(--font-body);
  border: 2px solid var(--ink);
  border-radius: 10px;
  overflow: hidden;
  white-space: nowrap;
  box-shadow: var(--shadow);
  margin-top: 12px;
}
.ticker .track {
  display: inline-block;
  padding: 7px 0;
  animation: marquee 30s linear infinite;
}
.ticker .tk-item {
  margin-right: 48px;
}
/* 涨跌圆点（无 emoji，红涨绿跌） */
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin: 0 5px 0 2px;
  border: 1px solid rgba(0, 0, 0, 0.35);
}
.dot.r {
  background: var(--up);
}
.dot.g {
  background: var(--down);
}
@keyframes marquee {
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(-50%);
  }
}
/* 尊重系统级减弱动效：关闭动画，改为静态换行展示 */
@media (prefers-reduced-motion: reduce) {
  .ticker {
    overflow: visible;
    white-space: normal;
  }
  .ticker .track {
    animation: none;
    white-space: normal;
  }
  .tk-item {
    display: inline-block;
    margin-right: 16px;
    margin-bottom: 4px;
  }
}
</style>
