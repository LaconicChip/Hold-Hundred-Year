<template>
  <div class="card quote-card">
    <table class="quotes">
      <thead>
        <tr>
          <th>名称</th>
          <th>现价</th>
          <th>涨跌幅</th>
          <!-- 状态列：为后续扩展预留；当前仅按价格推断封板/跌停提示 -->
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="q in list" :key="q.stockId" class="qrow" @click="onRow(q)">
          <td class="name-cell">
            <b>{{ q.name }}</b><span class="sec-tag">{{ q.sector }}</span>
          </td>
          <td class="num price" :class="[priceCls(q.changePct), flashMap[q.stockId]]">
            {{ q.current.toFixed(2) }}
          </td>
          <td class="num" :class="priceCls(q.changePct)">{{ fmtPct(q.changePct) }}</td>
          <td class="status-cell">
            <!-- 封板：从价格（changePct>=9.8%）推断的纯展示提示，非后端字段 -->
            <SealStamp v-if="q.changePct >= 9.8" mini text="封板" />
            <span v-else-if="q.changePct <= -9.8" class="limit-down-text">跌停</span>
            <span v-else class="muted">—</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useQuotesStore } from '@/stores/quotes'
import SealStamp from './SealStamp.vue'

const quotes = useQuotesStore()
const router = useRouter()
const list = computed(() => quotes.quoteList)

const flashMap = ref({})
let initialized = false

// 红涨绿跌：涨红 / 跌绿 / 平墨色
function priceCls(pct) {
  if (pct > 0) return 'up'
  if (pct < 0) return 'down'
  return 'flat'
}
function fmtPct(pct) {
  const v = +pct
  if (!v) return '0.00%'
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}
function onRow(q) {
  // 个股详情路由 Task 12.3 建立，路由不存在时静默忽略
  router.push(`/stock/${q.stockId}`).catch(() => {})
}

// 现价变动闪烁：快照到达（tickIndex 变化）时比较 current / prevCurrent，
// 该格背景闪一次红/绿；尊重 prefers-reduced-motion（由 CSS 关闭动画）
watch(
  () => quotes.tickIndex,
  () => {
    if (!initialized) {
      initialized = true // 首次快照不闪烁
      return
    }
    const map = {}
    list.value.forEach((q) => {
      if (q.current > q.prevCurrent) map[q.stockId] = 'flash-up'
      else if (q.current < q.prevCurrent) map[q.stockId] = 'flash-down'
    })
    flashMap.value = map
    setTimeout(() => {
      flashMap.value = {}
    }, 650)
  }
)
</script>

<style scoped>
.quote-card {
  padding: 10px 14px;
  margin-top: 16px;
}
/* 紧凑行情表（13.5px），忠实 style-tile 第五节 */
table.quotes {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
}
table.quotes th {
  font-family: var(--font-fun);
  font-weight: 400;
  font-size: 14px;
  color: var(--ink-2);
  border-bottom: 2px solid var(--ink);
  padding: 8px 6px;
  text-align: right;
}
table.quotes th:first-child,
table.quotes td:first-child {
  text-align: left;
}
table.quotes td {
  padding: 7px 6px;
  border-bottom: 1px dashed #d8cfbc;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
table.quotes tr:hover td {
  background: #fdf6e3;
}
.sec-tag {
  display: inline-block;
  font-size: 11px;
  border: 1.5px solid var(--ink-2);
  border-radius: 6px;
  padding: 0 6px;
  color: var(--ink-2);
  margin-left: 6px;
}
.name-cell b {
  font-weight: 700;
}
.limit-down-text {
  color: var(--down);
  font-weight: 700;
}
.muted {
  color: var(--ink-3);
}
.flat {
  color: var(--ink);
}
/* 现价变动闪烁（0.6s） */
.flash-up {
  animation: flashUp 0.6s ease-out;
}
.flash-down {
  animation: flashDown 0.6s ease-out;
}
@keyframes flashUp {
  0% {
    background: var(--up-flash);
  }
  100% {
    background: transparent;
  }
}
@keyframes flashDown {
  0% {
    background: var(--down-flash);
  }
  100% {
    background: transparent;
  }
}
@media (prefers-reduced-motion: reduce) {
  .flash-up,
  .flash-down {
    animation: none;
  }
}
</style>
