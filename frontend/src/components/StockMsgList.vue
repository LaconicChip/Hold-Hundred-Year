<template>
  <div class="msg-card card">
    <div class="msg-title">该股消息流</div>
    <div v-if="messages.length === 0" class="msg-empty">该股今日风平浪静</div>
    <div v-for="m in messages" :key="m.id" class="msg">
      <div class="msg-main">
        <div class="msg-line">
          <!-- 来源徽章：7 类来源各自配色，复用 style-tile 第七节气质 -->
          <span class="src" :class="srcCls(m.source)">{{ srcLabel(m.source) }}</span>
          <b class="msg-name">{{ m.name || m.author || '市场' }}</b>
          <span class="time">{{ m.time || '' }}</span>
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
  SRC_LABEL,
  SRC_CLS,
  srcLabel,
  srcCls,
  TRUTH_LABEL,
  TRUTH_CLS,
  truthLabel,
  truthCls
} from '@/constants/messageMeta'

const props = defineProps({
  stockId: { type: Number, required: true }
})

const messagesStore = useMessagesStore()

// 当前股相关消息：messages store list 中 stockId === 当前股
const messages = computed(() =>
  messagesStore.list.filter((m) => m.stockId === props.stockId)
)

// 来源标签 / 三态徽章定义统一来自 messageMeta.js（与消息中心保持一致）
</script>

<style scoped>
.msg-card {
  padding: 14px 16px;
  margin-top: 16px;
}
.msg-title {
  font-family: var(--font-fun);
  font-size: 19px;
  margin-bottom: 6px;
}
.msg-empty {
  color: var(--ink-3);
  font-size: 14px;
  padding: 14px 4px;
  text-align: center;
}
.msg {
  display: flex;
  gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px dashed #d8cfbc;
}
.msg-main {
  flex: 1;
}
.msg-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
}
.msg-name {
  margin-left: 2px;
  font-weight: 700;
}
.msg-content {
  margin-top: 4px;
  font-size: 14px;
  line-height: 1.6;
}
.time {
  color: var(--ink-3);
  font-size: 12px;
  margin-left: 4px;
}
/* 来源徽章：墨色硬描边小标签 */
.src {
  font-size: 11px;
  font-weight: 700;
  border: 1.5px solid var(--ink);
  border-radius: 6px;
  padding: 0 6px;
  margin-right: 6px;
}
.src-auth {
  background: #ffe0de;
  color: var(--up);
}
.src-media {
  background: #fff3cd;
  color: #8a6d00;
}
.src-rumor {
  background: #eee;
  color: var(--ink);
}
.src-insider {
  background: #f0e6ff;
  color: #7a4fb0;
}
.src-fun {
  background: #e2f7da;
  color: var(--down);
}
.src-npc {
  background: #dbe8ff;
  color: var(--blue);
}
.src-system {
  background: #d9d4cc;
  color: var(--ink);
}
/* 三态文字徽章 */
.truth {
  font-size: 11px;
  font-weight: 700;
  border: 1.5px solid var(--ink);
  border-radius: 6px;
  padding: 0 6px;
  margin-left: 6px;
}
.tr-pending {
  background: #fff3cd;
  color: #8a6d00;
}
.tr-confirmed {
  background: #d6f0df;
  color: var(--down);
}
.tr-denied {
  background: #e9e9e9;
  color: #888;
  text-decoration: line-through;
}
</style>
