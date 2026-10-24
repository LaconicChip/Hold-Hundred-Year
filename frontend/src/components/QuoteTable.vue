<template>
  <div class="card quote-card card-hero">
    <div class="quote-head">
      <h3 class="quote-title">
        <SvgIcon name="candle" />
        营业部行情牌
      </h3>
      <span v-if="isClosed" class="sticker st-halt closed-badge">已收盘</span>
      <span class="quote-tip muted-2">点一行看个股</span>
    </div>

    <div class="scroll-x">
      <table class="tbl quotes">
        <thead>
          <tr>
            <th>名称</th>
            <th>现价</th>
            <th>涨跌幅</th>
            <th class="col-open">今开</th>
            <th class="col-high">最高</th>
            <th class="col-low">最低</th>
            <th class="col-prev">昨收</th>
            <th class="col-streak">连板</th>
            <th class="col-status">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="q in list"
            :key="q.stockId"
            class="qrow"
            :class="rowCls(q)"
            @click="onRow(q)"
          >
            <!-- 名称：ST / 停牌 / 持仓贴纸 -->
            <td class="name-cell">
              <b>{{ q.name }}</b>
              <span v-if="q.st" class="sticker st-st st-inline">ST</span>
              <span v-if="q.halted" class="sticker st-halt st-inline">
                <SvgIcon name="door" class="st-ic" />停牌
              </span>
              <span v-if="held(q.stockId)" class="sticker st-inline st-hold">
                <SvgIcon name="wallet" class="st-ic" />持仓 {{ held(q.stockId).shares }}
              </span>
              <span class="sec-tag">{{ q.sector }}</span>
              <span class="mob-meta">
                {{ q.sector }} · 开 {{ fmtPrice(q.open) }} · 高 {{ fmtPrice(q.high) }} · 低 {{ fmtPrice(q.low) }} · 昨收 {{ fmtPrice(q.prevClose) }}
                <template v-if="q.limitUpStreak > 0"> · {{ q.limitUpStreak }} 连板</template>
                <template v-if="q.sealed === 'UP'"> · 封板</template>
                <template v-else-if="q.sealed === 'DOWN'"> · 跌停</template>
              </span>
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

            <td class="num dim col-open">{{ fmtPrice(q.open) }}</td>
            <td class="num dim col-high">{{ fmtPrice(q.high) }}</td>
            <td class="num dim col-low">{{ fmtPrice(q.low) }}</td>
            <td class="num dim col-prev">{{ fmtPrice(q.prevClose) }}</td>

            <!-- 连板：火苗叠加 -->
            <td class="streak-cell col-streak">
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
            <td class="status-cell col-status">
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuotesStore } from '@/stores/quotes'
import { useMarketStore } from '@/stores/market'
import { useUserStore } from '@/stores/user'
import SealStamp from './SealStamp.vue'
import SvgIcon from './SvgIcon.vue'
import { priceCls, fmtPrice, fmtPct } from '@/utils/format'

const quotes = useQuotesStore()
const market = useMarketStore()
const user = useUserStore()
const router = useRouter()
const list = computed(() => quotes.quoteList)

// 持仓股：行情表给一眼可辨的标记（行高亮 + 名称后"持仓 N股"贴纸）
const held = (stockId) => user.heldMap[stockId] || null
onMounted(() => {
  // 已有缓存不重复拉（个股页成交后会刷新同一份，两处共用）
  if (!user.positions.length) user.fetchPositions()
})

// 收盘/结算/终局态：行情牌挂"已收盘"角标
const isClosed = computed(() =>
  ['CLOSED', 'SETTLING', 'BANKRUPT', 'FINISHED'].includes(market.marketState)
)

const flashMap = ref({})
let initialized = false

// 是否钉在涨/跌停：优先看封板状态机，其次用服务端下发的本局涨跌停价比对。
// 原来写死 9.8%，快速模式（±20%）下会把 +10% 的票误标成涨停。
function atPrice(price, limit) {
  return limit != null && limit > 0 && Math.abs(price - limit) < 0.005
}
function isLimitUp(q) {
  return q.sealed === 'UP' || atPrice(q.current, q.limitUp)
}
function isLimitDown(q) {
  return q.sealed === 'DOWN' || atPrice(q.current, q.limitDown)
}
function onRow(q) {
  router.push(`/stock/${q.stockId}`).catch(() => {})
}
// 行级样式：持仓行高亮（优先，一眼找出自己的票）、封板底色、停牌置灰
function rowCls(q) {
  return {
    'row-hold': !!held(q.stockId),
    'row-sealed-up': !held(q.stockId) && q.sealed === 'UP',
    'row-sealed-down': !held(q.stockId) && q.sealed === 'DOWN',
    'row-halted': q.halted
  }
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
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: var(--sp-1);
}
.quote-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-md);
}
.quote-tip { font-size: var(--fs-xs); }
/* 手机端不再横向滑动：表头只留名称/现价/涨跌幅，开高低与昨收落到名称下方的第二行 */
.mob-meta { display: none; }
@media (max-width: 700px) {
  .col-open,
  .col-high,
  .col-low,
  .col-prev,
  .col-streak,
  .col-status { display: none; }
  .scroll-x { overflow: visible; }
  .scroll-x > table { min-width: 0; }
  /* 固定列宽：名称列 54%，现价/涨跌幅各 23%。不固定的话第二行元信息会把
     名称列撑到 400px，整张表溢出手机屏（实拍踩过）。 */
  .quotes { font-size: 13px; table-layout: fixed; }
  .quotes th:nth-child(1) { width: 54%; }
  .quotes th:nth-child(2) { width: 23%; }
  .quotes th:nth-child(3) { width: 23%; }
  .name-cell { overflow: hidden; }
  .mob-meta {
    display: block;
    margin-top: 1px;
    font-size: 11px;
    line-height: 1.35;
    color: var(--ink-2);
    white-space: normal;
  }
  .sec-tag { display: none; }
  .name-cell b { font-size: var(--fs-base); }
  .price { font-size: var(--fs-md); }
}

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

/* 封板行高亮底色 / 停牌行置灰 */
.row-sealed-up td { background: var(--up-soft) !important; }
.row-sealed-down td { background: var(--down-soft) !important; }
.row-sealed-up:hover td,
.row-sealed-down:hover td { background: var(--hover) !important; }
.row-halted td { opacity: 0.5; }
.closed-badge { transform: rotate(-2deg); }

/* 持仓行：整行高亮 + 左侧品牌色标记条（封板/停牌色不覆盖持仓，持仓优先可辨） */
.row-hold td { background: var(--player-row) !important; }
.row-hold:hover td { background: var(--hover) !important; }
.row-hold td:first-child { box-shadow: inset 4px 0 0 var(--brand); }
.st-hold {
  background: var(--player-row);
  color: var(--ink);
  transform: none;
  box-shadow: none;
  margin-left: 4px;
}

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
