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

const props = defineProps({
  stockId: { type: Number, required: true }
})

const messagesStore = useMessagesStore()

// 当前股相关消息：messages store list 中 stockId === 当前股
const messages = computed(() =>
  messagesStore.list.filter((m) => m.stockId === props.stockId)
)

// 来源枚举 -> 中文标签
const SRC_LABEL = {
  AUTHORITY: '权威公告',
  MEDIA: '财经媒体',
  RUMOR: '股吧传闻',
  INSIDER: '神秘内幕',
  FUN: '玩梗',
  NPC: '营业部',
  SYSTEM: '系统'
}
// 来源枚举 -> 样式类（令牌色：红/黄/灰/紫/绿/蓝）
const SRC_CLS = {
  AUTHORITY: 'src-auth',
  MEDIA: 'src-media',
  RUMOR: 'src-rumor',
  INSIDER: 'src-insider',
  FUN: 'src-fun',
  NPC: 'src-npc',
  SYSTEM: 'src-system'
}
function srcLabel(s) {
  return SRC_LABEL[s] || s || '消息'
}
function srcCls(s) {
  return SRC_CLS[s] || 'src-system'
}

// 三态枚举 -> 中文标签
const TRUTH_LABEL = {
  PENDING: '未证实',
  CONFIRMED: '已证实',
  DENIED: '已辟谣'
}
const TRUTH_CLS = {
  PENDING: 'tr-pending',
  CONFIRMED: 'tr-confirmed',
  DENIED: 'tr-denied'
}
function truthLabel(t) {
  return TRUTH_LABEL[t] || t || ''
}
function truthCls(t) {
  return TRUTH_CLS[t] || 'tr-pending'
}
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
