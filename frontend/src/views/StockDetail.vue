<template>
  <div class="page">
    <!-- 页头：返回 + 名称/代码/板块 + 现价/涨跌幅 -->
    <header class="page-head card">
      <button class="back-btn" @click="goBack">
        <SvgIcon name="chevron-left" class="back-ic" />返回大盘
      </button>

      <div class="head-main">
        <div class="name-row">
          <span class="stock-name">{{ stock.name }}</span>
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
        <span class="cur-pct num">{{ fmtPct(quote.changePct) }}</span>
      </div>
    </header>

    <!-- 分时图卡片 -->
    <StockChart :stock-points="stockPoints" :index-points="indexPoints" />

    <!-- 买卖面板卡片 -->
    <StockTradePanel
      :stock-id="stock.id"
      :price="quote.current"
      :change-pct="quote.changePct"
      :is-open="market.isOpen"
      :balance="balance"
      :holding-shares="position ? position.availableShares : 0"
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
import { getStockById } from '@/constants/stocks'
import { fetchStockList, fetchStockQuotes, fetchIndexPoints } from '@/api/stocks'
import { fetchPositions } from '@/api/me'
import StockChart from '@/components/StockChart.vue'
import StockTradePanel from '@/components/StockTradePanel.vue'
import StockMsgList from '@/components/StockMsgList.vue'
import StickerTag from '@/components/StickerTag.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import DanmuBar from '@/components/DanmuBar.vue'

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
  () => getStockById(stockId.value) || { id: 0, name: '未知', code: '', sector: '', basePrice: 0 }
)

// 实时行情：quotes store rows[stockId]
const quote = computed(() => {
  const r = quotes.rows[stockId.value]
  if (r) return { current: r.current, changePct: r.changePct, prevCurrent: r.prevCurrent }
  return { current: stock.value.basePrice, changePct: 0, prevCurrent: stock.value.basePrice }
})

// 资产：user.assets（mock 或真实）取可用资金
const balance = computed(() => (user.assets ? user.assets.cash : 0))

// 持仓：fetchPositions 中筛当前股
const position = ref(null)
async function loadPosition() {
  try {
    const list = await fetchPositions()
    const arr = Array.isArray(list) ? list : list.records || []
    position.value = arr.find((p) => p.stockId === stockId.value) || null
  } catch {
    position.value = null
  }
}

// 分时 / 指数数据
const stockPoints = ref([])
const indexPoints = ref([])
async function loadChartData() {
  try {
    stockPoints.value = await fetchStockQuotes(stockId.value)
  } catch {
    stockPoints.value = []
  }
  try {
    indexPoints.value = await fetchIndexPoints()
  } catch {
    indexPoints.value = []
  }
}

function onTraded() {
  // 成交后刷新持仓与资产
  loadPosition()
  user.fetchAssets().catch(() => {})
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/market')
}

// 红涨绿跌：涨红 / 跌绿 / 平墨色
function priceCls(v) {
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return 'flat'
}
function fmtPct(v) {
  const n = +v
  if (!n) return '0.00%'
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}
function fmtMoney(v) {
  const n = +v
  if (!isFinite(n)) return '0.00'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 进入页面：补充拉取消息（size:100），并按当前股过滤展示
async function loadMessages() {
  try {
    await messages.fetchMessages({ size: 100 })
  } catch {
    // 消息接口失败不阻塞个股详情
  }
}

// 市场状态轮询（午休↔下午盘切换时交易面板随动，与 Market.vue 同款兜底）
let statePoll = null

onMounted(async () => {
  // 无行情数据时用基准价初始化，避免现价空白
  if (!quotes.rows || !Object.keys(quotes.rows).length) quotes.initFromDefs()
  // 全量行情兜底：收盘/WS 未推时展示最新落库价而非基准价（与 Market.vue 同款）
  try {
    quotes.applyStockList(await fetchStockList())
  } catch {
    // 行情接口失败时保留基准价初始化
  }
  // 刷新市场状态：交易面板的可交易判定依赖 market.isOpen，缺省会导致盘中误显"休市中"
  market.fetchState().catch(() => {})
  statePoll = setInterval(() => {
    market.fetchState().catch(() => {})
  }, 10000)
  await loadChartData()
  await loadPosition()
  await user.fetchAssets().catch(() => {})
  await loadMessages()
})

onUnmounted(() => {
  if (statePoll) clearInterval(statePoll)
})

// 路由切换股票时重新加载数据
watch(stockId, async () => {
  position.value = null
  stockPoints.value = []
  indexPoints.value = []
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

/* 现价块：涨红跌绿整体着色 */
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
.cur-pct { font-size: var(--fs-lg); }

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
}
</style>
