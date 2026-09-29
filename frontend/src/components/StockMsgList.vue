<template>
  <div class="msg-card card">
    <div class="msg-head">
      <span class="msg-title">
        <SvgIcon name="note" class="msg-ic" />该股消息流
      </span>
      <span class="msg-count tag">{{ messages.length }} 条</span>
    </div>

    <div v-if="messages.length === 0" class="empty msg-empty">
      <SvgIcon name="bear" class="msg-mascot" />
      <span class="empty-title">这只票还没有消息</span>
      <span class="empty-desc">历史公告、传闻、辟谣都会留在这里 —— 现在一片空白，本身有点可疑</span>
    </div>

    <div v-for="m in messages" :key="m.id" class="msg">
      <!-- NPC 立绘优先，其余来源用单字色块头像 -->
      <NpcAvatar v-if="npcCodeOf(m)" :npc="npcCodeOf(m)" :size="40" />
      <span v-else class="avatar" :style="avStyle(m.source)">{{ avChar(m.source) }}</span>

      <div class="msg-main">
        <div class="msg-line">
          <!-- 来源徽章：7 类来源各自配色，复用 style-tile 第七节气质 -->
          <span class="src" :class="srcCls(m.source)">{{ srcLabel(m.source) }}</span>
          <b class="msg-name">{{ m.name || m.author || '市场' }}</b>
          <span class="time">{{ m.time || '' }}</span>
          <!-- 板块级消息：不是点名这只票，而是它所在的板块 -->
          <span v-if="!m.stockId && m.sector" class="tag">{{ m.sector }}板块</span>
          <!-- 三态文字徽章：未证实/已证实/已辟谣 -->
          <span v-if="m.truthStatus" class="truth" :class="truthCls(m.truthStatus)">
            {{ truthLabel(m.truthStatus) }}
          </span>
        </div>
        <div class="msg-content">{{ m.content || m.text || '' }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useMessagesStore } from '@/stores/messages'
import {
  SRC_AVATAR,
  resolveNpcCode,
  srcLabel,
  srcCls,
  truthLabel,
  truthCls
} from '@/constants/messageMeta'
import NpcAvatar from './NpcAvatar.vue'
import SvgIcon from './SvgIcon.vue'

const props = defineProps({
  stockId: { type: Number, required: true }
})

const messagesStore = useMessagesStore()

// 当前股相关消息：messages store list 中 stockId === 当前股
const messages = computed(() => messagesStore.list.filter((m) => m.stockId === props.stockId))

// NPC 立绘：从文本反查（容错前缀匹配）
function npcCodeOf(m) {
  return resolveNpcCode(`${m.name || ''}${m.author || ''}`)
}
function avChar(source) {
  return (SRC_AVATAR[source] || { ch: '消' }).ch
}
function avStyle(source) {
  const a = SRC_AVATAR[source] || { bg: 'var(--ink)', fg: 'var(--on-brand)' }
  return { background: a.bg, color: a.fg }
}
</script>

<style scoped>
.msg-card { padding: var(--sp-3) var(--sp-4) var(--sp-2); }
.msg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-2);
}
.msg-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
}
.msg-ic { width: 1em; height: 1em; color: var(--brand); }
.msg-count {
  font-family: var(--font-body);
  border-color: var(--ink-3);
  color: var(--ink-2);
}
.msg-empty { padding: var(--sp-6) var(--sp-4); }
.msg-mascot { width: 40px; height: 40px; opacity: 0.8; }

.msg {
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-1);
  border-bottom: 1px dashed var(--dash);
}
.msg:last-child { border-bottom: none; }
.msg-main { flex: 1; min-width: 0; }
.msg-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
}
.msg-name { margin-left: 2px; font-weight: 700; }
.msg-content {
  margin-top: var(--sp-1);
  font-size: var(--fs-base);
  line-height: 1.6;
}
.time {
  color: var(--ink-3);
  font-size: var(--fs-sm);
  margin-left: var(--sp-1);
}
</style>
