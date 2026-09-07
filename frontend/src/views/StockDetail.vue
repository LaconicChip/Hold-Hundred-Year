<template>
  <div class="stock-page">
    <!-- 页头：返回大盘 + 名称 + 代码 + 板块 + 现价 + 涨跌幅 -->
    <header class="page-head card">
      <button class="back-btn" @click="goBack">
        <SvgIcon name="bank" class="back-ic" />返回大盘
      </button>
      <div class="head-main">
        <div class="name-row">
          <span class="stock-name">{{ stock.name }}</span>
          <span class="stock-code num">{{ stock.code }}</span>
          <StickerTag type="plain" :text="stock.sector" />
        </div>
        <div class="price-row">
          <span class="cur-price num" :class="priceCls(quote.changePct)">
            {{ quote.current.toFixed(2) }}
          </span>
          <span class="cur-pct num" :class="priceCls(quote.changePct)">
            {{ fmtPct(quote.changePct) }}
          </span>
        </div>
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
      :holding-shares="position ? position.shares : 0"
      @traded="onTraded"
    />

    <!-- 我的持仓卡片 -->
    <div class="pos-card card">
      <div class="pos-title">我的持仓</div>
      <div v-if="!position" class="pos-empty">尚未持有该股</div>
      <table v-else class="pos-table">
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
            <td class="num">{{ position.shares }}</td>
            <td class="num">{{ position.costPrice.toFixed(2) }}</td>
            <td class="num" :class="priceCls(position.current - position.costPrice)">{{ position.current.toFixed(2) }}</td>
            <td class="num">{{ position.marketValue.toFixed(2) }}</td>
            <td class="num" :class="priceCls(position.profit)">{{ fmtMoney(position.profit) }}</td>
            <td class="num" :class="priceCls(position.profit)">{{ fmtPct(position.profitPct) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 该股消息流卡片 -->
    <StockMsgList :stock-id="stock.id" />

    <!-- 个股弹幕（复用大盘同款 DanmuBar） -->
    <DanmuBar />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuotesStore } from '@/stores/quotes'
import { useMarketStore } from '@/stores/market'
import { useUserStore } from '@/stores/user'
import { useMessagesStore } from '@/stores/messages'
import { getStockById } from '@/constants/stocks'
import { fetchStockQuotes, fetchIndexPoints } from '@/api/stocks'
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
const stock = computed(() => getStockById(stockId.value) || { id: 0, name: '未知', code: '', sector: '', basePrice: 0 })

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
    stockPoints.value = await fetchStockQuotes(stockId.value) // 后端补齐后自动走真实接口
  } catch {
    stockPoints.value = []
  }
  try {
    indexPoints.value = await fetchIndexPoints() // 后端补齐后自动走真实接口
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
// 持仓现价相对成本的着色由模板内 (current - costPrice) 直接计算，无需额外占位

// 进入页面：补充拉取消息（size:100），并按当前股过滤展示
async function loadMessages() {
  try {
    await messages.fetchMessages({ size: 100 })
  } catch {
    // 消息接口失败不阻塞个股详情
  }
}

onMounted(async () => {
  // 无行情数据时用基准价初始化，避免现价空白
  if (!quotes.rows || !Object.keys(quotes.rows).length) quotes.initFromDefs()
  await loadChartData()
  await loadPosition()
  await user.fetchAssets().catch(() => {})
  await loadMessages()
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
.stock-page {
  max-width: 980px;
  margin: 0 auto;
}
.page-head {
  display: flex;
  align-items: center;
  gap: 18px;
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
.head-main {
  flex: 1;
}
.name-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stock-name {
  font-family: var(--font-fun);
  font-size: 30px;
  line-height: 1.1;
}
.stock-code {
  color: var(--ink-2);
  font-size: 15px;
}
.price-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-top: 6px;
}
.cur-price {
  font-size: 24px;
}
.cur-pct {
  font-size: 18px;
}
.pos-card {
  padding: 14px 16px;
  margin-top: 16px;
}
.pos-title {
  font-family: var(--font-fun);
  font-size: 19px;
  margin-bottom: 8px;
}
.pos-empty {
  color: var(--ink-3);
  font-size: 14px;
  padding: 14px 4px;
  text-align: center;
}
.pos-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
}
.pos-table th {
  font-family: var(--font-fun);
  font-weight: 400;
  font-size: 13px;
  color: var(--ink-2);
  border-bottom: 2px solid var(--ink);
  padding: 6px 6px;
  text-align: right;
}
.pos-table th:first-child,
.pos-table td:first-child {
  text-align: left;
}
.pos-table td {
  padding: 8px 6px;
  border-bottom: 1px dashed #d8cfbc;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.flat {
  color: var(--ink);
}
</style>
