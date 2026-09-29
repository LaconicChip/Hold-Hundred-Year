<template>
  <div class="page">
    <!-- 页头：返回 + 名称/代码/板块 + 现价/涨跌额/涨跌幅 -->
    <header class="page-head card">
      <button class="back-btn" @click="goBack">
        <SvgIcon name="chevron-left" class="back-ic" />返回大盘
      </button>

      <div class="head-main">
        <div class="name-row">
          <span class="stock-name">{{ stock.name }}</span>
          <span v-if="row.st" class="sticker st-st st-inline">ST</span>
          <span v-if="row.halted" class="sticker st-halt st-inline">
            <SvgIcon name="door" class="st-ic" />停牌
          </span>
          <span class="stock-code num">{{ stock.code }}</span>
          <StickerTag type="plain" :text="stock.sector" />
        </div>
      </div>

      <div class="price-box" :class="priceCls(quote.changePct)">
        <SvgIcon
          :name="quote.changePct > 0 ? 'arrow-up' : quote.changePct < 0 ? 'arrow-down' : 'minus'"
          class="price-arrow"
        />
        <span class="cur-price num">{{ quote.current.toFixed(2) }}</span>
        <span class="cur-chg num">{{ fmtSigned(chgAmount) }}</span>
        <span class="cur-pct num">{{ fmtPct(quote.changePct) }}</span>
      </div>
    </header>

    <!-- 行情信息卡：开/昨收/高/低/涨停/跌停 + 连板/封板/停牌/ST -->
    <div class="card info-card">
      <div class="info-grid">
        <div class="kv-item"><span class="lab">今开</span><b class="num" :class="priceCls(infoOpen - dayPrevClose)">{{ fmtPrice(infoOpen) }}</b></div>
        <div class="kv-item"><span class="lab">昨收</span><b class="num">{{ fmtPrice(dayPrevClose) }}</b></div>
        <div class="kv-item"><span class="lab">最高</span><b class="num" :class="priceCls(infoHigh - dayPrevClose)">{{ fmtPrice(infoHigh) }}</b></div>
        <div class="kv-item"><span class="lab">最低</span><b class="num" :class="priceCls(infoLow - dayPrevClose)">{{ fmtPrice(infoLow) }}</b></div>
        <div class="kv-item"><span class="lab">涨停价</span><b class="num up">{{ fmtPrice(limitUpPrice) }}</b></div>
        <div class="kv-item"><span class="lab">跌停价</span><b class="num down">{{ fmtPrice(limitDownPrice) }}</b></div>
      </div>
      <div class="info-badges">
        <span v-if="row.limitUpStreak > 0" class="streak-badge">
          <SvgIcon v-for="n in Math.min(row.limitUpStreak, 5)" :key="n" name="flame" class="flame-ico" />
          <b class="num">{{ row.limitUpStreak }}</b>连板
        </span>
        <SealStamp v-if="row.sealed === 'UP'" text="封板" />
        <span v-else-if="row.sealed === 'DOWN'" class="limit-down">跌停封死</span>
        <span v-if="row.halted" class="sticker st-halt">停牌核查</span>
        <span v-if="row.st" class="sticker st-st">ST · 涨跌停 ±5%</span>
      </div>
    </div>

    <!-- 分时 / 日K tab 切换 -->
    <div class="chart-tabs">
      <div class="seg">
        <button :class="{ on: tab === 'intraday' }" @click="tab = 'intraday'">分时</button>
        <button :class="{ on: tab === 'kline' }" @click="switchToKline">日K</button>
      </div>
    </div>

    <StockChart
      v-if="tab === 'intraday'"
      :points="stockPoints"
      :prev-close="dayPrevClose"
      :limit-up="limitUpPrice"
      :limit-down="limitDownPrice"
      :closed="isClosed"
    />
    <KlineChart v-else :kline="kline" />

    <!-- 买卖面板卡片 -->
    <StockTradePanel
      :stock-id="stock.id"
      :price="quote.current"
      :change-pct="quote.changePct"
      :is-open="market.isOpen"
      :balance="balance"
      :holding-shares="position ? position.availableShares : 0"
      :holding-cost="position ? position.costPrice : 0"
      :holding-value="position ? position.marketValue : 0"
      @traded="onTraded"
    />

    <!-- 我的持仓卡片 -->
    <div class="pos-card card">
      <div class="pos-head">
        <span class="pos-title">
          <SvgIcon name="wallet" class="pos-ic" />我的持仓
        </span>
        <span v-if="position" class="tag">持有 {{ position.shares }} 股</span>
      </div>

      <div v-if="!position" class="empty pos-empty">
        <SvgIcon name="bull" class="pos-mascot" />
        <span class="empty-title">尚未持有该股</span>
        <span class="empty-desc">在上面下个买单，就能在这儿看到浮盈浮亏</span>
      </div>

      <div v-else class="scroll-x">
        <table class="tbl pos-table">
          <thead>
            <tr>
              <th>股数</th>
              <th>可用</th>
              <th>成本价</th>
              <th>现价</th>
              <th>市值</th>
              <th>浮盈浮亏</th>
              <th>盈亏率</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td class="num">{{ position.shares }}</td>
              <td class="num">{{ position.availableShares }}</td>
              <td class="num">{{ position.costPrice.toFixed(2) }}</td>
              <td class="num" :class="priceCls(position.current - position.costPrice)">
                {{ position.current.toFixed(2) }}
              </td>
              <td class="num">{{ position.marketValue.toFixed(2) }}</td>
              <td class="num" :class="priceCls(position.profit)">{{ fmtMoney(position.profit) }}</td>
              <td class="num" :class="priceCls(position.profit)">{{ fmtPct(position.profitPct) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 该股消息流卡片 -->
    <StockMsgList :stock-id="stock.id" />

    <!-- 个股弹幕（复用大盘同款 DanmuBar） -->
    <DanmuBar />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuotesStore } from '@/stores/quotes'
import { useMarketStore } from '@/stores/market'
import { useUserStore } from '@/stores/user'
import { useMessagesStore } from '@/stores/messages'
import { fetchStockList, fetchStockQuotes, fetchStockKline } from '@/api/stocks'
import StockChart from '@/components/StockChart.vue'
import KlineChart from '@/components/KlineChart.vue'
import StockTradePanel from '@/components/StockTradePanel.vue'
import StockMsgList from '@/components/StockMsgList.vue'
import StickerTag from '@/components/StickerTag.vue'
import SealStamp from '@/components/SealStamp.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import DanmuBar from '@/components/DanmuBar.vue'
import { priceCls, fmtPct, fmtPrice, fmtMoney } from '@/utils/format'
import { TICK_MS } from '@/constants/rhythm'

const route = useRoute()
const router = useRouter()
const quotes = useQuotesStore()
const market = useMarketStore()
const user = useUserStore()
const messages = useMessagesStore()

// 路由参数：stockId（1..24）
const stockId = computed(() => {
  const id = parseInt(route.params.id, 10)
  return isNaN(id) ? 0 : id
})
const stock = computed(
  () => quotes.defOf(stockId.value) || { id: 0, name: '未知', code: '', sector: '', basePrice: 0 }
)

// 实时行情：quotes store rows[stockId]（WS 快照 + REST 全量兜底）
const quote = computed(() => {
  const r = quotes.rows[stockId.value]
  if (r) return { current: r.current, changePct: r.changePct, prevCurrent: r.prevCurrent }
  return { current: stock.value.basePrice, changePct: 0, prevCurrent: stock.value.basePrice }
})

// REST 全量行情行（开高低/昨收/连板/停牌/ST/封板）：quoteList 中取当前股
const row = computed(() => {
  const found = quotes.quoteList.find((q) => q.stockId === stockId.value)
  return found || {
    open: null, high: null, low: null,
    prevClose: stock.value.basePrice, limitUpStreak: 0,
    halted: false, st: false, sealed: 'NONE'
  }
})

// 当日行情口径（开/昨收/高/低）：盘中与收盘态都由 /api/stocks 下发——后端在收盘态
// 已用落库日K bar 还原当日 OHLC 与昨收（结算会把 prev_close/OHL 重置为收盘价）
const dayPrevClose = computed(() => row.value.prevClose || stock.value.basePrice)
const infoOpen = computed(() => row.value.open)
const infoHigh = computed(() => row.value.high)
const infoLow = computed(() => row.value.low)

// 涨跌停价由后端按本局模式算好下发（标准 ±10% / 快速 ±20%，ST 取一半）。
// 前端不再自己乘系数：写死 1.10 曾让快速模式的虚线停在 10% 的位置。
const limitUpPrice = computed(() => row.value.limitUp || 0)
const limitDownPrice = computed(() => row.value.limitDown || 0)
// 涨跌额（现价 - 昨收，元）
const chgAmount = computed(() => quote.value.current - dayPrevClose.value)

// 收盘/结算/终局态：图上挂"已收盘"角标
const isClosed = computed(() =>
  ['CLOSED', 'SETTLING', 'BANKRUPT', 'FINISHED'].includes(market.marketState)
)

// 可用资金：取 user store 资产看板的 cash（与买卖面板同源）
const balance = computed(() => (user.assets ? user.assets.cash : 0))

// 持仓：复用 user store 的持仓列表（行情表高亮同一份，成交后统一刷新）
const position = computed(() => user.positions.find((p) => p.stockId === stockId.value) || null)
function loadPosition() {
  return user.fetchPositions()
}

// 分时数据（固定 90 槽，3 秒轮询实时延伸——后端一跳 GameConstants.TICK_MS=3000ms）
const stockPoints = ref([])
async function loadChartData() {
  try {
    stockPoints.value = await fetchStockQuotes(stockId.value)
  } catch {
    stockPoints.value = []
  }
}

// 日K数据：切到日K tab 时拉取（结算日落库的 OHLC 蜡烛）
const tab = ref('intraday')
const kline = ref([])
async function loadKline() {
  try {
    kline.value = await fetchStockKline(stockId.value, 60)
  } catch {
    kline.value = []
  }
}
function switchToKline() {
  tab.value = 'kline'
  loadKline()
}

function onTraded() {
  // 成交后刷新持仓与资产（持仓同一份缓存，行情表高亮随动）
  user.fetchPositions()
  user.fetchAssets().catch(() => {})
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/market')
}

// 红涨绿跌：涨红 / 跌绿 / 平墨色
function fmtSigned(v) {
  const n = +v
  if (!n) return '0.00'
  return (n > 0 ? '+' : '') + n.toFixed(2)
}

// 进入页面：向服务端要"这只票的历史消息"（带 stockId 过滤）。
// 之前是拉全局最新 100 条再在前端挑，一天产 3~6 条消息，十几天前的个股新闻就再也翻不到了。
async function loadMessages() {
  try {
    // includeSector：本票专属 + 本票所属板块的新闻一起要（板块级消息没有 stockId）
    await messages.fetchMessages({ stockId: stockId.value, includeSector: true, size: 60 })
  } catch {
    // 消息接口失败不阻塞个股详情
  }
}

// 市场状态轮询（2 秒一次：午休↔下午盘切换时交易面板与倒计时随动）
let statePoll = null
// 分时轮询：一跳一拉，间隔与引擎跳速对齐（收盘态拉到完整 90 点，开销可忽略）
let intradayPoll = null

onMounted(async () => {
  // 全量行情兜底：收盘/WS 未推时展示最新落库价而非基准价（与 Market.vue 同款），
  // 顺带补齐静态档案——直接刷新进本页时 store 里可能一条都没有
  try {
    quotes.applyStockList(await fetchStockList())
  } catch {
    // 行情接口失败时保留基准价初始化
  }
  // 刷新市场状态：交易面板的可交易判定依赖 market.isOpen，缺省会导致盘中误显"休市中"
  market.fetchState().catch(() => {})
  statePoll = setInterval(() => {
    market.fetchState().catch(() => {})
  }, 2000)
  await loadChartData()
  intradayPoll = setInterval(() => {
    loadChartData()
  }, market.tickMs || TICK_MS)
  await loadPosition()
  await user.fetchAssets().catch(() => {})
  await loadMessages()
})

onUnmounted(() => {
  if (statePoll) clearInterval(statePoll)
  if (intradayPoll) clearInterval(intradayPoll)
})

// 路由切换股票时重新加载数据
watch(stockId, async () => {
  stockPoints.value = []
  kline.value = []
  await loadChartData()
  await loadPosition()
  await loadMessages()
})
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/* ---------- 页头 ---------- */
.page-head {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: 0;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  font-family: var(--font-fun);
  font-size: var(--fs-md);
  border: var(--border);
  background: var(--card);
  border-radius: var(--radius-pill);
  padding: 6px var(--sp-4);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  white-space: nowrap;
  transition: 0.08s;
}
.back-btn:hover { transform: translate(-1px, -1px); box-shadow: var(--shadow); background: var(--hover); }
.back-btn:active { transform: translate(2px, 2px); box-shadow: var(--shadow-press); }
.back-ic { width: 15px; height: 15px; }

.head-main { flex: 1; min-width: 0; }
.name-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.stock-name {
  font-family: var(--font-fun);
  font-size: var(--fs-3xl);
  line-height: 1.1;
}
.stock-code {
  color: var(--ink-2);
  font-size: var(--fs-md);
}
/* 名称行内贴纸：不旋转，避免破坏基线 */
.st-inline { transform: none; box-shadow: none; }
.st-ic { width: 12px; height: 12px; }

/* 现价块：涨红跌绿整体着色，含涨跌额 */
.price-box {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex: none;
  padding: var(--sp-1) var(--sp-3);
  border: var(--border);
  border-radius: var(--radius-md);
  background: var(--paper-warm);
  box-shadow: var(--shadow-sm);
}
.price-arrow { width: 1em; height: 1em; align-self: center; }
.cur-price { font-size: var(--fs-2xl); }
.cur-chg { font-size: var(--fs-lg); }
.cur-pct { font-size: var(--fs-lg); }

/* ---------- 行情信息卡 ---------- */
.info-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  padding: var(--sp-3) var(--sp-4);
}
.info-grid {
  display: grid;
  grid-template-columns: repeat(6, auto);
  gap: var(--sp-2) var(--sp-5);
}
.kv-item { display: flex; flex-direction: column; gap: 0; }
.kv-item .lab { font-size: var(--fs-xs); color: var(--ink-2); }
.kv-item b { font-size: var(--fs-md); font-weight: 700; }
.info-badges {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.streak-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-family: var(--font-fun);
  font-size: var(--fs-base);
  color: var(--orange);
}

/* ---------- 图表 tab ---------- */
.chart-tabs { display: flex; }
.chart-tabs .seg { box-shadow: var(--shadow-sm); }

/* ---------- 持仓 ---------- */
.pos-card { padding: var(--sp-3) var(--sp-4); }
.pos-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-2);
}
.pos-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
}
.pos-ic { width: 1em; height: 1em; color: var(--brand); }
.pos-empty { padding: var(--sp-6) var(--sp-4); }
.pos-mascot { width: 44px; height: 44px; opacity: 0.8; }

@media (max-width: 760px) {
  .page-head { flex-wrap: wrap; }
  .stock-name { font-size: var(--fs-2xl); }
  .price-box { width: 100%; justify-content: flex-start; }
  .info-grid { grid-template-columns: repeat(3, auto); }
}
</style>
