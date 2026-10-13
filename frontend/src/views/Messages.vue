<template>
  <div class="page">
    <!-- 页头（返回由侧栏承担）：标题 + 未读数 + 全部已读 -->
    <header class="page-head">
      <div>
        <h1 class="page-title">
          <SvgIcon name="bell" class="title-ic" />消息中心
        </h1>
        <p class="page-desc">
          传闻、公告与追报都在这儿 · 未读
          <b class="num">{{ unreadCount }}</b>
          条
        </p>
      </div>
      <button class="btn btn-sm" :disabled="!hasUnread" @click="onMarkAll">
        <SvgIcon name="check" class="btn-ic" />全部已读
      </button>
    </header>

    <!-- 筛选条：来源芯片 + 仅未读开关 -->
    <section class="card filter-card">
      <div class="chips">
        <button
          v-for="f in SRC_FILTERS"
          :key="f.value"
          class="chip"
          :class="{ on: filters.source === f.value }"
          @click="onSource(f.value)"
        >
          {{ f.label }}
        </button>
      </div>
      <label class="unread-toggle" :class="{ on: filters.unreadOnly }">
        <input type="checkbox" v-model="filters.unreadOnly" @change="reload" />
        <span>仅未读</span>
      </label>
    </section>

    <!-- 消息流 -->
    <section class="card list-card">
      <div v-if="loading && items.length === 0" class="empty">
        <span class="empty-title">加载中…</span>
      </div>
      <div v-else-if="items.length === 0" class="empty">
        <SvgIcon name="bear" class="list-mascot" />
        <span class="empty-title">暂无消息</span>
        <span class="empty-desc">换个来源筛选，或者等下一条传闻</span>
      </div>

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
              <span v-if="!m.isRead" class="unread-dot"></span>
            </div>

            <!-- 标题 / 正文 -->
            <div class="msg-title" :class="{ bold: !m.isRead }">{{ m.title || m.content }}</div>
            <div v-if="m.title && m.content" class="msg-content">{{ m.content }}</div>

            <!-- 追报：查看原消息 -->
            <button
              v-if="m.followupOf != null"
              class="followup-link"
              @click.stop="gotoOriginal(m.followupOf)"
            >
              查看原消息
            </button>

            <!-- 原消息下的追报子行 -->
            <div v-if="followupsOf(m.id).length" class="followups">
              <div v-for="c in followupsOf(m.id)" :key="c.id" class="followup-row">
                <span class="truth" :class="followupTagCls(c)">{{ followupTag(c) }}</span>
                <span class="followup-text">{{ c.content }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 加载更多 -->
        <button v-if="hasMore" class="btn load-more" :disabled="loading" @click="loadMore">
          {{ loading ? '加载中…' : '加载更多' }}
        </button>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useMessagesStore } from '@/stores/messages'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
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
const unreadCount = computed(() => items.value.filter((m) => !m.isRead).length)

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
  return SRC_AVATAR[source] || { ch: '消', bg: 'var(--ink-3)', fg: 'var(--on-brand)' }
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
  if (c.truthStatus === 'DENIED') return 'tr-denied'
  if (c.truthStatus === 'CONFIRMED') return 'tr-confirmed'
  return 'tr-pending'
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
    ElMessage.error({ message: '操作失败', grouping: true })
  }
}

onMounted(reload)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.title-ic { width: 0.85em; height: 0.85em; color: var(--brand); }
.btn-ic { width: 1em; height: 1em; }

/* ---------- 筛选条 ---------- */
.filter-card {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: var(--sp-3) var(--sp-4);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  flex: 1;
  min-width: 0;
}
.chip {
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  border: var(--border);
  background: var(--card);
  color: var(--ink-2);
  border-radius: var(--radius-pill);
  padding: 3px var(--sp-3);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  transition: 0.08s;
}
.chip:hover { background: var(--hover); color: var(--ink); transform: translate(-1px, -1px); box-shadow: var(--shadow); }
.chip.on {
  background: var(--brand);
  color: var(--on-brand);
  transform: translate(1px, 1px);
  box-shadow: var(--shadow-press);
}
.unread-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  border: var(--border);
  border-radius: var(--radius-pill);
  padding: 3px var(--sp-3);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  white-space: nowrap;
  transition: 0.08s;
}
.unread-toggle.on {
  background: var(--yellow);
  color: var(--ink);
}
.unread-toggle input {
  width: 15px;
  height: 15px;
  accent-color: var(--brand);
  cursor: pointer;
}

/* ---------- 消息行 ---------- */
.list-card { padding: var(--sp-2) var(--sp-4) var(--sp-3); }
.list-mascot { width: 44px; height: 44px; opacity: 0.8; }
.msg {
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-2);
  border-bottom: 1px dashed var(--dash);
  border-left: 3px solid transparent;
  cursor: pointer;
  transition: background 0.12s;
}
.msg:hover { background: var(--hover); }
.msg:last-of-type { border-bottom: none; }
.msg.unread {
  border-left-color: var(--up);
  background: var(--brand-soft);
}
.msg.unread:hover { background: var(--hover); }
.msg.hl {
  background: var(--tr-pending-bg);
  border-radius: var(--radius-sm);
}
.msg-main { flex: 1; min-width: 0; }
.msg-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
}
.msg-name { margin-left: 2px; font-weight: 700; }
.time {
  color: var(--ink-3);
  font-size: var(--fs-sm);
  margin-left: var(--sp-2);
}
.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--up);
  border: 1px solid var(--ink);
  margin-left: var(--sp-2);
  flex: none;
}
.msg-title {
  margin-top: var(--sp-1);
  font-size: var(--fs-base);
  line-height: 1.6;
  word-break: break-word;
}
.msg-title.bold { font-weight: 700; }
.msg-content {
  margin-top: 2px;
  font-size: var(--fs-base);
  line-height: 1.6;
  color: var(--ink-2);
  word-break: break-word;
}

/* ---------- 追报 ---------- */
.followup-link {
  margin-top: var(--sp-1);
  font-size: var(--fs-sm);
  font-family: var(--font-fun);
  color: var(--blue);
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
}
.followups {
  margin-top: var(--sp-2);
  border-left: 2px dashed var(--ink-3);
  padding-left: var(--sp-3);
}
.followup-row {
  display: flex;
  gap: 6px;
  margin-top: var(--sp-1);
  font-size: var(--fs-sm);
  align-items: flex-start;
}
.followup-row .truth { margin-left: 0; margin-top: 2px; }
.followup-text { color: var(--ink-2); word-break: break-word; }

.load-more {
  display: flex;
  margin: var(--sp-4) auto 0;
}
</style>
