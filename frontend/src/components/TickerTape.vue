<template>
  <div class="ticker night">
    <span class="tk-label">
      <SvgIcon name="alert" class="tk-ic" />
      快讯
    </span>
    <div class="tk-view">
      <div class="track">
        <span v-for="(it, i) in doubled" :key="i" class="tk-item" :class="it.cls">
          <i v-if="it.dir !== 'none'" class="dot" :class="it.dir === 'up' ? 'r' : 'g'"></i>
          <b v-if="it.dir !== 'none'" class="tk-tag">{{ it.dir === 'up' ? '涨' : '跌' }}</b>
          {{ it.text }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useQuotesStore } from '@/stores/quotes'
import { useMessagesStore } from '@/stores/messages'
import SvgIcon from './SvgIcon.vue'

const quotes = useQuotesStore()
const messages = useMessagesStore()

// 合成跑马灯文本：大波动快讯 + EVENT 消息标题 + 通知快讯
// 同一条内容只出现一次（按文本去重），避免满屏重复
const items = computed(() => {
  const out = []
  const seen = new Set()
  function push(text, dir) {
    const t = String(text || '').trim()
    if (!t || seen.has(t)) return
    seen.add(t)
    out.push({ text: t, dir, cls: dir === 'up' ? 'tk-up' : dir === 'down' ? 'tk-down' : '' })
  }
  // 1) 行情大波动快讯（仅后端有的字段：名称 + changePct 推断）
  quotes.quoteList.forEach((q) => {
    if (q.changePct >= 9.8) push(`${q.name}封死涨停`, 'up')
    else if (q.changePct <= -9.8) push(`${q.name}封死跌停`, 'down')
  })
  // 2) 消息中心中 type=EVENT 的标题
  messages.list
    .filter((m) => m && m.type === 'EVENT')
    .forEach((m) => push(m.title || m.content || '', 'none'))
  // 3) 通知快讯（取最近若干条，字段不定容错）
  messages.notifications
    .slice(-6)
    .forEach((n) => push(n.title || n.content || n.ipoName || n.text || '', 'none'))
  if (!out.length) push('欢迎来到百年证券营业部，行情即将开始', 'none')
  return out
})

// 复制两份首尾相接，配合 @keyframes marquee 平移 -50% 无缝滚动
const doubled = computed(() => items.value.concat(items.value))
</script>

<style scoped>
.ticker {
  display: flex;
  align-items: stretch;
  overflow: hidden;
  box-shadow: var(--shadow);
  margin-top: var(--sp-3);
}
/* 左侧固定标签 */
.tk-label {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 var(--sp-3);
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--yellow);
  border-right: 2px solid var(--ink);
}
.tk-ic { width: 1em; height: 1em; }

.tk-view { flex: 1; min-width: 0; overflow: hidden; }
.track {
  display: inline-block;
  padding: 7px 0;
  animation: marquee 30s linear infinite;
}
.tk-item {
  margin-right: 48px;
  color: var(--on-night);
  font-size: var(--fs-sm);
}
.tk-tag {
  font-weight: 700;
  margin-right: 2px;
}
.tk-up { color: var(--on-night-up); }
.tk-down { color: var(--on-night-down); }
.tk-item .dot { border-color: rgba(255, 255, 255, 0.45); }

@keyframes marquee {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}

/* 尊重系统级减弱动效：关闭动画，改为静态换行展示 */
@media (prefers-reduced-motion: reduce) {
  .ticker { overflow: visible; }
  .tk-view { overflow: visible; }
  .track { animation: none; white-space: normal; }
  .tk-item { display: inline-block; margin-right: 16px; margin-bottom: 4px; }
}
</style>
