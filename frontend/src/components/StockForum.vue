<template>
  <div class="forum card">
    <div class="forum-head">
      <span class="forum-title">
        <SvgIcon name="note" class="forum-ic" />股吧 · {{ stockName }}
      </span>
      <span class="forum-count muted-2">{{ shown.length }} 条</span>
    </div>

    <ul ref="listEl" class="forum-list">
      <li v-for="p in shown" :key="p.seq" class="post" :class="tone(p.category)">
        <span class="post-who">{{ p.who }}</span>
        <span v-if="p.category && p.category !== '闲聊'" class="post-cat">{{ p.category }}</span>
        <span class="post-text">{{ p.text }}</span>
      </li>
    </ul>
    <p v-if="!shown.length" class="forum-empty muted-2">
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

const NAMES = [
  '韭菜阿花', '满仓哥', '打板小王', '老登B77', '定投李', '梭哈张',
  '关灯吃面人', '北向往', '接盘侠A', '长线王', '补仓狂魔', '跌停板常客',
  '割肉小能手', '追涨杀跌', '短线小散', '持股观望', '嘴硬价值派', '反弹就跑',
  '深套青年', '底仓守望者', '追高不悔', '佛系持股人', '明天必涨', '抄底不抄半山腰',
  '每天涨停', '尾盘起飞', '空仓看戏', '一股不碰', '摊平成本中', '消息面研究者',
  '老韭菜了', '打工人的股票', '蚂蚁搬家', '山顶风景好', '不设止损', '看盘到天亮'
]

/**
 * 昵称分配：同一个帖子永远是同一个人（否则一眼假），但**不按固定顺序轮换**。
 *
 * <p>旧版是 `seq % NAMES.length`——十二条一轮，看着就是机器在发。改成按 (票, 序号) 哈希：
 * 同一条仍然稳定（刷新页面还是那个人），但不同帖子不会整齐地按同一顺序出现，
 * 同一只票的股吧里也不会每隔几条就轮同一个名字。
 */
function whoOf(seq, given, stockId) {
  if (given) return given
  let h = (Math.imul(Number(seq) || 0, 2654435761) + Math.imul(Number(stockId) || 0, 40503)) >>> 0
  h ^= h >>> 15
  h = Math.imul(h, 2246822519) >>> 0
  h ^= h >>> 13
  return NAMES[h % NAMES.length]
}

const MAX_POSTS = 80

const shown = computed(() => {
  return messages.forumPosts(props.stockId)
    .slice(-MAX_POSTS)
    .reverse()
    .map((d) => ({ ...d, who: whoOf(d.seq, d.user, props.stockId) }))
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
.forum-head { display: flex; align-items: center; gap: var(--sp-2); }
.forum-title { display: inline-flex; align-items: center; gap: 6px; font-family: var(--font-fun); font-size: var(--fs-md); }
.forum-ic { width: 1em; height: 1em; color: var(--brand); }
.forum-count { margin-left: auto; font-size: var(--fs-xs); }
.forum-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 360px;
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
