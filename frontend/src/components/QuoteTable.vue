<template>
  <div class="card quote-card">
    <div class="quote-head">
      <h3 class="quote-title">
        <SvgIcon name="candle" />
        营业部行情牌
      </h3>
      <span class="quote-tip muted-2">点一行看个股</span>
    </div>

    <div class="scroll-x">
      <table class="tbl zebra quotes">
        <thead>
          <tr>
            <th>名称</th>
            <th>现价</th>
            <th>涨跌幅</th>
            <th>今开</th>
            <th>最高</th>
            <th>最低</th>
            <th>连板</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="q in list" :key="q.stockId" class="qrow" @click="onRow(q)">
            <!-- 名称：ST / 停牌贴纸 -->
            <td class="name-cell">
              <b>{{ q.name }}</b>
              <span v-if="q.st" class="sticker st-st st-inline">ST</span>
              <span v-if="q.halted" class="sticker st-halt st-inline">
                <SvgIcon name="door" class="st-ic" />停牌
              </span>
              <span class="sec-tag">{{ q.sector }}</span>
            </td>

            <!-- 现价（变动闪烁） -->
            <td class="num price" :class="[priceCls(q.changePct), flashMap[q.stockId]]">
              {{ fmtPrice(q.current) }}
            </td>

            <!-- 涨跌幅：涨跌停色块 -->
            <td class="num pct-cell">
              <span
                class="pct"
                :class="[priceCls(q.changePct), { 'limit-up': isLimitUp(q), 'limit-down': isLimitDown(q) }]"
              >{{ fmtPct(q.changePct) }}</span>
            </td>

            <td class="num dim">{{ fmtPrice(q.open) }}</td>
            <td class="num dim">{{ fmtPrice(q.high) }}</td>
            <td class="num dim">{{ fmtPrice(q.low) }}</td>

            <!-- 连板：火苗叠加 -->
            <td class="streak-cell">
              <template v-if="q.limitUpStreak > 0">
                <SvgIcon
                  v-for="n in Math.min(q.limitUpStreak, 5)"
                  :key="n"
                  name="flame"
                  class="flame-ico"
                />
                <span v-if="q.limitUpStreak > 5" class="streak-more num">+{{ q.limitUpStreak - 5 }}</span>
              </template>
              <span v-else class="dash">—</span>
            </td>

            <!-- 状态：封板印章 / 跌停 / 停牌 -->
            <td class="status-cell">
              <SealStamp v-if="q.sealed === 'UP'" mini text="封板" />
              <span v-else-if="q.sealed === 'DOWN'" class="limit-down">跌停</span>
              <span v-else-if="q.halted" class="sticker st-halt st-inline">小黑屋</span>
              <span v-else class="dash">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuotesStore } from '@/stores/quotes'
import SealStamp from './SealStamp.vue'
import SvgIcon from './SvgIcon.vue'

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
function fmtPrice(v) {
  return v == null ? '—' : Number(v).toFixed(2)
}
function fmtPct(pct) {
  const v = +pct
  if (!v) return '0.00%'
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}
function isLimitUp(q) {
  return q.sealed === 'UP' || q.changePct >= 9.8
}
function isLimitDown(q) {
  return q.sealed === 'DOWN' || q.changePct <= -9.8
}
function onRow(q) {
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
  padding: var(--sp-3) var(--sp-4) var(--sp-2);
}
.quote-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding-bottom: var(--sp-2);
}
.quote-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-lg);
}
.quote-tip { font-size: var(--fs-xs); }

.quotes { font-size: 13.5px; }
.qrow { cursor: pointer; }
.qrow:hover .name-cell b { color: var(--brand); }

.name-cell b { font-weight: 700; }
/* 名称行内贴纸：不旋转，避免破坏基线 */
.st-inline { transform: none; box-shadow: none; margin-left: 4px; }
.st-ic { width: 12px; height: 12px; }

.price { font-size: var(--fs-md); }
.pct { display: inline-block; padding: 0 4px; border-radius: 4px; }
.dim { color: var(--ink-2); }
.dash { color: var(--ink-3); }

.streak-cell { white-space: nowrap; }
.streak-more { font-size: var(--fs-xs); color: var(--orange); }

.status-cell { white-space: nowrap; }

/* 现价变动闪烁（0.6s） */
.flash-up { animation: flashUp 0.6s ease-out; }
.flash-down { animation: flashDown 0.6s ease-out; }
@keyframes flashUp {
  0% { background: var(--up-flash); }
  100% { background: transparent; }
}
@keyframes flashDown {
  0% { background: var(--down-flash); }
  100% { background: transparent; }
}
@media (prefers-reduced-motion: reduce) {
  .flash-up,
  .flash-down { animation: none; }
}
</style>
