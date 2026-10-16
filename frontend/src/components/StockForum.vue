<template>
  <div class="forum card" :class="{ collapsed }">
    <div class="forum-head">
      <span class="forum-title">
        <SvgIcon name="note" class="forum-ic" />股吧 · {{ stockName }}
      </span>
      <span class="forum-count muted-2">{{ shown.length }} 条</span>
      <button type="button" class="forum-toggle" :title="collapsed ? '展开股吧' : '收起股吧'" @click="toggle">
        <SvgIcon :name="collapsed ? 'arrow-down' : 'arrow-up'" class="toggle-ic" />
      </button>
    </div>

    <ul v-if="!collapsed" ref="listEl" class="forum-list">
      <li v-for="p in shown" :key="p.seq" class="post" :class="tone(p.category)">
        <span class="post-who">{{ p.who }}</span>
        <span v-if="p.category" class="post-cat">{{ p.category }}</span>
        <span class="post-text">{{ p.text }}</span>
      </li>
    </ul>
    <p v-if="!collapsed && !shown.length" class="forum-empty muted-2">
      还没人聊这只票。开盘后有大动作就会飘进来。
    </p>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useMessagesStore } from '@/stores/messages'
import SvgIcon from './SvgIcon.vue'

const props = defineProps({
  stockId: { type: [Number, String], default: 0 },
  stockName: { type: String, default: '' }
})

const messages = useMessagesStore()
const listEl = ref(null)
const STORE_KEY = 'forum-collapsed'
const collapsed = ref(localStorage.getItem(STORE_KEY) === '1')

function toggle() {
  collapsed.value = !collapsed.value
  localStorage.setItem(STORE_KEY, collapsed.value ? '1' : '0')
}

const NAMES = [
  '韭菜阿花', '满仓哥', '打板小王', '老登B77', '定投李', '梭哈张',
  '关灯吃面人', '北向往', '接盘侠A', '长线王', '补仓狂魔', '跌停板常客'
]

/** 昵称按 seq 稳定取：同一条弹幕每次渲染都必须是同一个人，否则一眼就露出是随机贴的。 */
function whoOf(seq, given) {
  if (given) return given
  return NAMES[seq % NAMES.length]
}

const MAX_POSTS = 30

const shown = computed(() => {
  const id = Number(props.stockId)
  return messages.danmu
    // 在说这只票的 + 不点名的大盘闲聊；旧载荷没有 stockId，按大盘闲聊处理
    .filter((d) => !d.stockId || Number(d.stockId) === id)
    .slice(-MAX_POSTS)
    .reverse()
    .map((d) => ({ ...d, who: whoOf(d.seq, d.user) }))
})

function tone(cat) {
  if (cat === '涨停' || cat === '急拉') return 'is-up'
  if (cat === '跌停' || cat === '跳水') return 'is-down'
  return ''
}

// 新弹幕进来把列表滚回顶部：最新在上，不需要跟随滚动位置
watch(() => messages.danmuVersion, () => {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = 0
  })
})
</script>

<style scoped>
.forum { padding: var(--sp-3) var(--sp-4); display: grid; gap: var(--sp-2); }
.forum.collapsed { padding-bottom: var(--sp-3); }
.forum-head { display: flex; align-items: center; gap: var(--sp-2); }
.forum-title { display: inline-flex; align-items: center; gap: 6px; font-family: var(--font-fun); font-size: var(--fs-md); }
.forum-ic { width: 1em; height: 1em; color: var(--brand); }
.forum-count { margin-left: auto; font-size: var(--fs-xs); }
.forum-toggle {
  border: var(--border);
  background: var(--card);
  border-radius: var(--radius-sm);
  width: 26px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.toggle-ic { width: 14px; height: 14px; }
.forum-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 260px;
  overflow-y: auto;
  display: grid;
  gap: 6px;
}
.post {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--fs-sm);
  border-bottom: 1px dashed var(--ink-3);
  padding-bottom: 6px;
}
.post-who { color: var(--blue-soft); font-weight: 700; flex: none; }
.post-cat {
  flex: none;
  font-size: var(--fs-xs);
  border: var(--border-hair);
  border-radius: var(--radius-pill);
  padding: 0 6px;
  color: var(--ink-2);
}
.post-text { color: var(--ink); }
.is-up .post-text { color: var(--up); }
.is-down .post-text { color: var(--down); }
.forum-empty { font-size: var(--fs-sm); text-align: center; padding: var(--sp-3) 0; }
</style>
