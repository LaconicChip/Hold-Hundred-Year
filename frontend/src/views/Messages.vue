<template>
  <div class="msg-page">
    <!-- 页头：返回大盘 + 标题 + 全部已读 -->
    <header class="page-head card">
      <button class="back-btn" @click="goBack">
        <SvgIcon name="bank" class="back-ic" />返回大盘
      </button>
      <h1 class="page-title">消息中心</h1>
      <button class="btn btn-sm mark-all" :disabled="!hasUnread" @click="onMarkAll">
        全部已读
      </button>
    </header>

    <!-- 筛选条：来源分段 + 仅未读开关 -->
    <section class="card filter-card">
      <div class="seg">
        <button
          v-for="f in SRC_FILTERS"
          :key="f.value"
          class="seg-btn"
          :class="{ on: filters.source === f.value }"
          @click="onSource(f.value)"
        >
          {{ f.label }}
        </button>
      </div>
      <label class="unread-toggle">
        <input type="checkbox" v-model="filters.unreadOnly" @change="reload" />
        <span>仅未读</span>
      </label>
    </section>

    <!-- 消息流 -->
    <section class="card list-card">
      <div v-if="loading && items.length === 0" class="list-empty">加载中…</div>
      <div v-else-if="items.length === 0" class="list-empty">暂无消息</div>

      <template v-else>
        <div
          v-for="m in items"
          :key="m.id"
          :ref="(el) => setMsgRef(m.id, el)"
          class="msg"
          :class="{ unread: !m.isRead, hl: highlightId === m.id }"
          @click="handleMsgClick(m)"
        >
          <!-- 头像：NPC 用 NpcAvatar，其它来源单字色块 -->
          <NpcAvatar v-if="isNpc(m)" :npc="npcCode(m)" :size="40" />
          <span
            v-else
            class="avatar"
            :style="{ background: avatarFor(m.source).bg, color: avatarFor(m.source).fg }"
          >{{ avatarFor(m.source).ch }}</span>

          <div class="msg-main">
            <div class="msg-line">
              <span class="src" :class="srcCls(m.source)">{{ srcLabel(m.source) }}</span>
              <b v-if="npcName(m)" class="msg-name">{{ npcName(m) }}</b>
              <span class="time">第 {{ m.gameDay }} 天 · {{ hhmm(m.createdAt) }}</span>
              <!-- 三态文字徽章 -->
              <span v-if="m.truthStatus" class="truth" :class="truthCls(m.truthStatus)">
                {{ truthLabel(m.truthStatus) }}
              </span>
            </div>

            <!-- 标题 / 正文 -->
            <div class="msg-title" :class="{ bold: !m.isRead }">{{ m.title || m.content }}</div>
            <div v-if="m.title && m.content" class="msg-content">{{ m.content }}</div>

            <!-- 追报：查看原消息 -->
            <button
              v-if="m.followupOf != null"
              class="followup-link"
              @click="gotoOriginal(m.followupOf)"
            >
              查看原消息
            </button>

            <!-- 原消息下的追报子行 -->
            <div v-if="followupsOf(m.id).length" class="followups">
              <div v-for="c in followupsOf(m.id)" :key="c.id" class="followup-row">
                <span class="followup-tag" :class="followupTagCls(c)">
                  {{ followupTag(c) }}
                </span>
                <span class="followup-text">{{ c.content }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 加载更多 -->
        <button
          v-if="hasMore"
          class="btn load-more"
          :disabled="loading"
          @click="loadMore"
        >
          {{ loading ? '加载中…' : '加载更多' }}
        </button>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessagesStore } from '@/stores/messages'
import { ElMessage } from 'element-plus'
import NpcAvatar from '@/components/NpcAvatar.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import {
  SRC_FILTERS,
  SRC_AVATAR,
  srcLabel,
  srcCls,
  truthLabel,
  truthCls,
  resolveNpcCode
} from '@/constants/messageMeta'

const router = useRouter()
const messages = useMessagesStore()

const SIZE = 20
const filters = ref({ source: '', unreadOnly: false })
const page = ref(1)
const loading = ref(false)
const items = ref([])
const highlightId = ref(null)
const msgRefs = new Map()

// 当前筛选下的总条数（用于“加载更多”判定）
const total = ref(0)
const hasMore = computed(() => items.value.length < total.value)

const hasUnread = computed(() => items.value.some((m) => !m.isRead))

// ---- 头像 / NPC 解析 ----
function isNpc(m) {
  return m.source === 'NPC'
}
function npcCode(m) {
  return resolveNpcCode(`${m.title || ''} ${m.content || ''}`) || 'SYSTEM'
}
// NPC 代码 -> 中文名（用于消息行加粗展示）
const NPC_NAME_BY_CODE = {
  DAMA: '张大妈',
  LAOWANG: '隔壁老王',
  LAOLI: '保安老李',
  CHIEF: '首席分析师',
  JUSHI: '炒股养家居士',
  LAOQIAN: '拆迁户老钱',
  XINJIMIN: '95后新基民',
  CHENGXUYUAN: '辞职炒股的程序猿'
}
function npcName(m) {
  const code = resolveNpcCode(`${m.title || ''} ${m.content || ''}`)
  return code ? NPC_NAME_BY_CODE[code] || '' : ''
}
function avatarFor(source) {
  return SRC_AVATAR[source] || { ch: '?', bg: '#888', fg: '#fff' }
}

// ---- 追报关联 ----
const followupMap = computed(() => {
  const map = {}
  for (const m of items.value) {
    if (m.followupOf != null) {
      ;(map[m.followupOf] = map[m.followupOf] || []).push(m)
    }
  }
  return map
})
function followupsOf(id) {
  return followupMap.value[id] || []
}
function followupTag(c) {
  if (c.truthStatus === 'DENIED') return '辟谣'
  if (c.truthStatus === 'CONFIRMED') return '证实'
  return '更新'
}
function followupTagCls(c) {
  if (c.truthStatus === 'DENIED') return 'ft-denied'
  if (c.truthStatus === 'CONFIRMED') return 'ft-confirmed'
  return 'ft-pending'
}
function setMsgRef(id, el) {
  if (el) msgRefs.set(id, el)
  else msgRefs.delete(id)
}
function gotoOriginal(id) {
  const el = msgRefs.get(id)
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  highlightId.value = id
  setTimeout(() => {
    if (highlightId.value === id) highlightId.value = null
  }, 1600)
}

// ---- 时间 ----
function hhmm(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

// ---- 数据加载 ----
async function reload() {
  page.value = 1
  loading.value = true
  try {
    const res = await messages.fetchMessages({
      source: filters.value.source,
      unreadOnly: filters.value.unreadOnly,
      page: 1,
      size: SIZE
    })
    items.value = messages.list
    total.value = res.total != null ? res.total : messages.list.length
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
async function loadMore() {
  if (loading.value) return
  page.value += 1
  loading.value = true
  try {
    const res = await messages.fetchMessages({
      source: filters.value.source,
      unreadOnly: filters.value.unreadOnly,
      page: page.value,
      size: SIZE,
      append: true
    })
    items.value = messages.list
    total.value = res.total != null ? res.total : messages.list.length
  } catch {
    page.value -= 1
  } finally {
    loading.value = false
  }
}
function onSource(v) {
  filters.value.source = v
  reload()
}

// 点击未读消息自动标记已读
async function onRead(m) {
  if (m.isRead) return
  try {
    await messages.markRead(m.id)
  } catch {
    // 标记失败不影响阅读
  }
}
// 借助事件委托：在 msg 点击时触发标记已读
function handleMsgClick(m) {
  onRead(m)
}

async function onMarkAll() {
  try {
    await messages.markAllRead()
    ElMessage.success('已全部标记为已读')
  } catch {
    ElMessage.error('操作失败')
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/market')
}

onMounted(reload)
</script>

<style scoped>
.msg-page {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 20px 80px;
}
/* 页头 */
.page-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 18px;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font-fun);
  font-size: 15px;
  border: 2px solid var(--ink);
  background: var(--card);
  border-radius: 999px;
  padding: 6px 16px;
  box-shadow: 2px 2px 0 var(--ink);
  cursor: pointer;
  white-space: nowrap;
}
.back-btn:hover {
  transform: translate(-1px, -1px);
  box-shadow: 3px 3px 0 var(--ink);
}
.back-btn:active {
  transform: translate(2px, 2px);
  box-shadow: 1px 1px 0 var(--ink);
}
.back-ic {
  width: 16px;
  height: 16px;
}
.page-title {
  flex: 1;
  font-family: var(--font-fun);
  font-size: 30px;
}
.btn-sm {
  font-size: 14px;
  padding: 5px 16px;
  box-shadow: 2px 2px 0 var(--ink);
}
.mark-all:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
/* 筛选条 */
.filter-card {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.seg {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.seg-btn {
  font-family: var(--font-fun);
  font-size: 13px;
  border: 2px solid var(--ink);
  background: var(--card);
  border-radius: 999px;
  padding: 4px 12px;
  box-shadow: 2px 2px 0 var(--ink);
  cursor: pointer;
}
.seg-btn.on {
  background: var(--brand);
  color: #fff;
}
.seg-btn:not(.on):hover {
  transform: translate(-1px, -1px);
  box-shadow: 3px 3px 0 var(--ink);
}
.unread-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 14px;
  cursor: pointer;
  white-space: nowrap;
}
.unread-toggle input {
  width: 16px;
  height: 16px;
  accent-color: var(--brand);
}
/* 列表 */
.list-card {
  margin-top: 16px;
}
.list-empty {
  color: var(--ink-3);
  font-size: 14px;
  padding: 30px 4px;
  text-align: center;
}
/* 消息行（style-tile 第七节） */
.msg {
  display: flex;
  gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px dashed #d8cfbc;
  border-left: 3px solid transparent;
  padding-left: 8px;
  cursor: pointer;
}
.msg.unread {
  border-left-color: var(--up);
}
.msg.hl {
  background: #fff3cd;
  border-radius: 8px;
}
.msg-main {
  flex: 1;
  min-width: 0;
}
.avatar {
  width: 40px;
  height: 40px;
  min-width: 40px;
  border: 2px solid var(--ink);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-fun);
  font-size: 17px;
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
.time {
  color: var(--ink-3);
  font-size: 12px;
  margin-left: 6px;
}
.msg-title {
  margin-top: 4px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.msg-title.bold {
  font-weight: 700;
}
.msg-content {
  margin-top: 2px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-2);
  word-break: break-word;
}
/* 来源徽章 */
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
/* 追报 */
.followup-link {
  margin-top: 4px;
  font-size: 12px;
  color: var(--blue);
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
}
.followups {
  margin-top: 6px;
  border-left: 2px dashed var(--ink-3);
  padding-left: 10px;
}
.followup-row {
  display: flex;
  gap: 6px;
  margin-top: 4px;
  font-size: 13px;
}
.followup-tag {
  font-size: 11px;
  font-weight: 700;
  border: 1.5px solid var(--ink);
  border-radius: 6px;
  padding: 0 6px;
  white-space: nowrap;
}
.ft-pending {
  background: #fff3cd;
  color: #8a6d00;
}
.ft-confirmed {
  background: #d6f0df;
  color: var(--down);
}
.ft-denied {
  background: #e9e9e9;
  color: #888;
  text-decoration: line-through;
}
.followup-text {
  color: var(--ink-2);
  word-break: break-word;
}
.load-more {
  display: block;
  margin: 16px auto 0;
}
</style>
