<template>
  <div class="assets-page">
    <!-- 页头：返回大盘 + 标题 + 当前段位 -->
    <header class="page-head card">
      <button class="back-btn" @click="goBack">
        <SvgIcon name="bank" class="back-ic" />返回大盘
      </button>
      <h1 class="page-title">资产总览</h1>
      <TierBadge :tier="market.tier || 'RETAIL'" vertical />
    </header>

    <!-- 资产卡片：四宫格 -->
    <section class="card asset-card">
      <div class="quad">
        <div class="quad-cell">
          <div class="quad-label">现金</div>
          <div class="quad-num num">{{ assets ? fmtMoney(assets.cash) : '--' }}</div>
        </div>
        <div class="quad-cell">
          <div class="quad-label">持仓市值</div>
          <div class="quad-num num">{{ assets ? fmtMoney(assets.marketValue) : '--' }}</div>
        </div>
        <div class="quad-cell">
          <div class="quad-label">负债</div>
          <div class="quad-num num ink-num">{{ assets ? fmtMoney(assets.debt) : '--' }}</div>
        </div>
        <div class="quad-cell">
          <div class="quad-label">净资产</div>
          <div class="quad-num num">{{ assets ? fmtMoney(assets.netAsset) : '--' }}</div>
          <div v-if="assets" class="quad-sub num" :class="priceCls(totalProfit)">
            总盈亏 = {{ fmtMoney(totalProfit) }}
          </div>
        </div>
      </div>
    </section>

    <!-- 担保比例警戒条 -->
    <section v-if="hasDebt" class="card guard-card">
      <div class="card-title">担保比例</div>
      <div class="guard-pct num" :class="guardCls">{{ (assets.guardRatio * 100).toFixed(2) }}%</div>
      <div class="ruler">
        <div class="ruler-track">
          <span class="mark mark-red" :style="{ left: pos120 + '%' }">
            <i class="mark-tick"></i><em class="mark-text">120% 强平</em>
          </span>
          <span class="mark mark-orange" :style="{ left: pos140 + '%' }">
            <i class="mark-tick"></i><em class="mark-text">140% 追保</em>
          </span>
          <span class="pointer" :class="guardCls" :style="{ left: posCur + '%' }">
            <i class="pointer-flag">当前</i>
          </span>
        </div>
        <div class="ruler-base"></div>
      </div>
      <div v-if="assets.guardRatio < 1.2" class="guard-warn red">担保比例低于 120%，面临强制平仓</div>
      <div v-else-if="assets.guardRatio < 1.4" class="guard-warn orange">担保比例低于 140%，请注意追保风险</div>
    </section>
    <section v-else class="card guard-card">
      <div class="card-title">担保比例</div>
      <div class="guard-none">当前无融资负债</div>
    </section>

    <!-- 融资面板 -->
    <section class="card loan-card">
      <div class="card-title">融资融券</div>
      <div class="loan-info">
        <div class="loan-line">
          <span class="loan-k">可用融资额度</span>
          <span class="loan-v num">{{ fmtMoney(loanBorrowable) }}</span>
        </div>
        <div v-if="loanInfo" class="loan-extra">
          <span class="loan-k">借款余额</span><span class="loan-v num">{{ fmtMoney(loanInfo.debt) }}</span>
          <span class="loan-k">账户余额</span><span class="loan-v num">{{ fmtMoney(loanInfo.balance) }}</span>
        </div>
        <div v-else class="loan-extra">
          <span class="loan-note">预估可借额度 = 净资产 - 负债（口径说明：borrowable 仅在借款/还款响应返回，无独立查询接口，初始以净资产减负债估算展示）</span>
        </div>
      </div>
      <div class="loan-form">
        <el-input-number v-model="loanAmount" :min="0" :step="1000" :controls="true" class="loan-input" />
        <button class="btn btn-warn" :disabled="loanLoading" @click="doBorrow">
          <SvgIcon name="alert" class="btn-ic" />借款
        </button>
        <button class="btn" :disabled="loanLoading" @click="doRepay">还款</button>
      </div>
      <div v-if="loanErr" class="loan-err">{{ loanErr }}</div>
    </section>

    <!-- 持仓表 -->
    <section class="card pos-card">
      <div class="card-title">我的持仓</div>
      <div v-if="!positions.length" class="pos-empty">空仓中，牛小牛在吃草</div>
      <table v-else class="pos-table">
        <thead>
          <tr>
            <th>名称 / 板块</th>
            <th>股数</th>
            <th>成本</th>
            <th>现价</th>
            <th>市值</th>
            <th>浮盈浮亏</th>
            <th>盈亏率</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in positions" :key="p.stockId">
            <td class="pos-name">
              <b>{{ p.name }}</b>
              <StickerTag type="plain" :text="p.sector" />
            </td>
            <td class="num">{{ p.shares }}</td>
            <td class="num">{{ p.costPrice.toFixed(2) }}</td>
            <td class="num" :class="priceCls(p.current - p.costPrice)">{{ p.current.toFixed(2) }}</td>
            <td class="num">{{ p.marketValue.toFixed(2) }}</td>
            <td class="num" :class="priceCls(p.profit)">{{ fmtMoney(p.profit) }}</td>
            <td class="num" :class="priceCls(p.profit)">{{ fmtPct(p.profitPct) }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 流水：三段 Tab -->
    <section class="card flow-card">
      <div class="seg">
        <button class="seg-btn" :class="{ on: tab === 'trade' }" @click="tab = 'trade'">交易流水</button>
        <button class="seg-btn" :class="{ on: tab === 'loan' }" @click="tab = 'loan'">融资流水</button>
        <button class="seg-btn" :class="{ on: tab === 'ipo' }" @click="tab = 'ipo'">打新记录</button>
      </div>

      <!-- 交易流水 -->
      <div v-if="tab === 'trade'">
        <div v-if="!trades.length" class="flow-empty">暂无交易流水</div>
        <table v-else class="flow-table">
          <thead>
            <tr>
              <th>时间</th><th>名称</th><th>方向</th><th>股数</th>
              <th>价格</th><th>金额</th><th>佣金</th><th>印花税</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in trades" :key="t.id">
              <td class="num dim">{{ t.time }}</td>
              <td>{{ t.name }}</td>
              <td>
                <span :class="t.side === 'buy' ? 'side-buy' : 'side-sell'">
                  {{ t.side === 'buy' ? '买入' : '卖出' }}
                </span>
              </td>
              <td class="num">{{ t.shares }}</td>
              <td class="num">{{ t.price.toFixed(2) }}</td>
              <td class="num">{{ t.amount.toFixed(2) }}</td>
              <td class="num">{{ t.commission.toFixed(2) }}</td>
              <td class="num">{{ t.tax.toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 融资流水：后端 loan_record 查询接口未实现，补齐后接入 -->
      <div v-else-if="tab === 'loan'" class="flow-empty">暂无融资流水</div>

      <!-- 打新记录：后端打新查询接口未实现，补齐后接入 -->
      <div v-else class="flow-empty">暂无打新记录</div>
    </section>

    <!-- 108 天资产 vs 指数曲线 -->
    <section class="card curve-card">
      <div class="card-title">108 天资产 vs 百年指数</div>
      <div v-if="!curveHasData" class="curve-empty">完成首个交易日后生成曲线</div>
      <VChart v-else class="curve" :option="curveOption" :autoresize="true" />
    </section>

    <!-- 称号徽章墙 -->
    <section class="card title-card">
      <div class="card-title">称号徽章</div>
      <div v-if="!titles.length" class="title-empty">暂未获得称号</div>
      <div v-else class="title-grid">
        <div v-for="t in titles" :key="t.id" class="title-item" :class="t.unlocked ? 'on' : 'off'">
          <b class="title-name">{{ t.name }}</b>
          <span class="title-desc">{{ t.desc }}</span>
          <span v-if="!t.unlocked" class="title-lock">未解锁</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMarketStore } from '@/stores/market'
import { useUserStore } from '@/stores/user'
import { fetchAssets, fetchPositions, fetchTrades, fetchTitles } from '@/api/me'
import { borrow, repay } from '@/api/loan'
import { fetchEquityCurve } from '@/api/market'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import TierBadge from '@/components/TierBadge.vue'
import StickerTag from '@/components/StickerTag.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { ElMessage } from 'element-plus'

// 按需注册 ECharts 模块（与 StockChart 一致）
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

const router = useRouter()
const market = useMarketStore()
const user = useUserStore()

// 初始资金常量（与后端约定 100000 起始）
const INIT_CAPITAL = 100000

// ---- 数据状态 ----
const assets = ref(null)
const positions = ref([])
const trades = ref([])
const titles = ref([])

// 融资面板
const loanInfo = ref(null) // 借款/还款成功后填充真实 {amount, balance, debt, borrowable}
const loanAmount = ref(1000)
const loanLoading = ref(false)
const loanErr = ref('')

// 流水 Tab
const tab = ref('trade')

// 净值曲线
const curvePoints = ref([])

const hasDebt = computed(
  () => assets.value && assets.value.debt > 0 && assets.value.guardRatio !== 0
)
// 总盈亏 = 净资产 - 初始资金
const totalProfit = computed(() =>
  assets.value ? assets.value.netAsset - INIT_CAPITAL : 0
)

// 可用融资额度：有真实响应用 borrowable，否则按净资产减负债估算
const loanBorrowable = computed(() => {
  if (loanInfo.value) return loanInfo.value.borrowable
  if (assets.value) return Math.max(assets.value.netAsset - assets.value.debt, 0)
  return 0
})

// ---- 担保比例标尺 ----
// guardRatio 为比率（8.00 = 800%）；标尺右端取 max(当前, 160) 保证 120/140 标记可见
const pos120 = computed(() => 120 / rulerMax.value * 100)
const pos140 = computed(() => 140 / rulerMax.value * 100)
const posCur = computed(() => Math.min((assets.value.guardRatio * 100) / rulerMax.value * 100, 100))
const rulerMax = computed(() =>
  assets.value ? Math.max(assets.value.guardRatio * 100, 160) : 160
)
const guardCls = computed(() => {
  if (!assets.value) return 'flat'
  if (assets.value.guardRatio < 1.2) return 'down'
  if (assets.value.guardRatio < 1.4) return 'orange'
  return 'up'
})

// ---- 净值曲线 ----
const curveHasData = computed(() => curvePoints.value.length > 0)
const curveOption = computed(() => {
  const days = curvePoints.value.map((p) => p.gameDay)
  const net = curvePoints.value.map((p) => +p.netAsset.toFixed(2))
  const idx = curvePoints.value.map((p) => +p.indexValue.toFixed(2))
  return {
    color: ['#ff2436', '#6f675c'], // 净资产=品牌红，百年指数=墨灰（仅令牌色）
    grid: { left: 56, right: 56, top: 40, bottom: 32 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#fffdf7',
      borderColor: '#2b2620',
      borderWidth: 2,
      textStyle: { color: '#2b2620', fontSize: 12 }
    },
    legend: {
      data: ['净资产', '百年指数'],
      top: 6,
      itemWidth: 18,
      itemHeight: 8,
      textStyle: { color: '#2b2620', fontSize: 12, fontWeight: 700 }
    },
    xAxis: {
      type: 'category',
      data: days,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#2b2620' } },
      axisLabel: { color: '#6f675c', fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value',
        name: '净资产',
        scale: true,
        axisLabel: { color: '#6f675c', fontSize: 11 },
        splitLine: { lineStyle: { color: '#e6dcc8', type: 'dashed' } }
      },
      {
        type: 'value',
        name: '指数',
        scale: true,
        axisLabel: { color: '#6f675c', fontSize: 11 },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '净资产',
        type: 'line',
        yAxisIndex: 0,
        data: net,
        smooth: false,
        showSymbol: false,
        lineStyle: { width: 2.5, color: '#ff2436' }
      },
      {
        name: '百年指数',
        type: 'line',
        yAxisIndex: 1,
        data: idx,
        smooth: false,
        showSymbol: false,
        lineStyle: { width: 2, color: '#6f675c', type: 'dashed' }
      }
    ]
  }
})

// ---- 工具函数 ----
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

// 返回大盘
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/market')
}

// ---- 融资动作 ----
async function doBorrow() {
  if (loanLoading.value || !loanAmount.value) return
  loanLoading.value = true
  loanErr.value = ''
  try {
    const res = await borrow(loanAmount.value) // 失败 Error(msg) 透传
    loanInfo.value = res
    await user.fetchAssets().catch(() => {}) // 刷新资产
    const a = await fetchAssets()
    assets.value = a
    ElMessage.success('借款成功')
  } catch (e) {
    loanErr.value = e.message || '借款失败'
  } finally {
    loanLoading.value = false
  }
}
async function doRepay() {
  if (loanLoading.value || !loanAmount.value) return
  loanLoading.value = true
  loanErr.value = ''
  try {
    const res = await repay(loanAmount.value) // 失败 Error(msg) 透传
    loanInfo.value = res
    await user.fetchAssets().catch(() => {})
    const a = await fetchAssets()
    assets.value = a
    ElMessage.success('还款成功')
  } catch (e) {
    loanErr.value = e.message || '还款失败'
  } finally {
    loanLoading.value = false
  }
}

// ---- 数据加载 ----
async function loadAll() {
  const a = await fetchAssets().catch(() => null)
  assets.value = a
  if (a) await user.fetchAssets().catch(() => {}) // 同步 user store（备用）
  const pos = await fetchPositions().catch(() => [])
  positions.value = Array.isArray(pos) ? pos : pos.records || []
  const tr = await fetchTrades().catch(() => ({ records: [] }))
  trades.value = tr.records || []
  titles.value = await fetchTitles().catch(() => [])
  // 净值曲线：真实接口，失败降级空态
  try {
    const c = await fetchEquityCurve()
    curvePoints.value = c && c.points ? c.points : []
  } catch {
    curvePoints.value = []
  }
}

onMounted(loadAll)
</script>

<style scoped>
.assets-page {
  max-width: 980px;
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
/* 通用卡片标题 */
.card-title {
  font-family: var(--font-fun);
  font-size: 19px;
  margin-bottom: 10px;
}
/* 资产四宫格 */
.asset-card {
  margin-top: 16px;
  padding: 18px;
}
.quad {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.quad-cell {
  border: 2px solid var(--ink);
  border-radius: 10px;
  padding: 12px 10px;
  background: var(--paper);
}
.quad-label {
  font-family: var(--font-fun);
  font-size: 13px;
  color: var(--ink-2);
  margin-bottom: 6px;
}
.quad-num {
  font-size: 22px;
}
.ink-num {
  color: var(--ink);
}
.quad-sub {
  margin-top: 4px;
  font-size: 13px;
}
/* 担保比例 */
.guard-card {
  margin-top: 16px;
}
.guard-pct {
  font-size: 24px;
  margin-bottom: 12px;
}
.guard-none {
  color: var(--ink-2);
  font-size: 15px;
  padding: 6px 2px;
}
.ruler {
  position: relative;
  padding: 0 0 28px;
}
.ruler-track {
  position: relative;
  height: 14px;
  background: var(--paper);
  border: 2px solid var(--ink);
  border-radius: 999px;
}
.ruler-base {
  height: 0;
}
.mark {
  position: absolute;
  top: -2px;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
}
.mark-tick {
  width: 3px;
  height: 14px;
  display: block;
}
.mark-red .mark-tick {
  background: var(--up);
}
.mark-orange .mark-tick {
  background: var(--orange);
}
.mark-text {
  position: absolute;
  top: 16px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
.mark-red .mark-text {
  color: var(--up);
}
.mark-orange .mark-text {
  color: var(--orange);
}
.pointer {
  position: absolute;
  top: -4px;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
}
.pointer-flag {
  position: absolute;
  top: -22px;
  font-size: 11px;
  font-weight: 700;
  background: var(--card);
  border: 2px solid var(--ink);
  border-radius: 6px;
  padding: 0 6px;
  white-space: nowrap;
}
.guard-warn {
  margin-top: 14px;
  font-size: 14px;
  font-weight: 700;
  border: 2px solid var(--ink);
  border-radius: 10px;
  padding: 8px 12px;
}
.guard-warn.red {
  background: #ffe0de;
  color: var(--up);
}
.guard-warn.orange {
  background: #fff3cd;
  color: #b4530a;
}
/* 融资面板 */
.loan-card {
  margin-top: 16px;
}
.loan-info {
  margin-bottom: 12px;
}
.loan-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.loan-k {
  font-family: var(--font-fun);
  font-size: 14px;
  color: var(--ink-2);
}
.loan-v {
  font-size: 20px;
}
.loan-extra {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 14px;
  margin-top: 8px;
}
.loan-note {
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
}
.loan-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
}
.loan-input {
  width: 200px;
}
.btn-ic {
  width: 16px;
  height: 16px;
}
.loan-err {
  margin-top: 10px;
  color: var(--up);
  font-size: 13px;
  font-weight: 700;
}
/* 持仓表 */
.pos-card {
  margin-top: 16px;
}
.pos-empty {
  color: var(--ink-3);
  font-size: 14px;
  padding: 16px 4px;
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
.pos-name {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 流水 Tab */
.flow-card {
  margin-top: 16px;
}
.seg {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.seg-btn {
  font-family: var(--font-fun);
  font-size: 15px;
  border: 2px solid var(--ink);
  background: var(--card);
  border-radius: 999px;
  padding: 5px 16px;
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
.flow-empty {
  color: var(--ink-3);
  font-size: 14px;
  padding: 18px 4px;
  text-align: center;
}
.flow-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.flow-table th {
  font-family: var(--font-fun);
  font-weight: 400;
  font-size: 12.5px;
  color: var(--ink-2);
  border-bottom: 2px solid var(--ink);
  padding: 6px 6px;
  text-align: right;
}
.flow-table th:first-child,
.flow-table td:first-child {
  text-align: left;
}
.flow-table td {
  padding: 7px 6px;
  border-bottom: 1px dashed #d8cfbc;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.flow-table .dim {
  color: var(--ink-3);
}
.side-buy {
  color: var(--up);
  font-weight: 700;
}
.side-sell {
  color: var(--down);
  font-weight: 700;
}
/* 净值曲线 */
.curve-card {
  margin-top: 16px;
}
.curve {
  width: 100%;
  height: 340px;
}
.curve-empty {
  height: 340px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink-3);
  font-size: 15px;
}
/* 称号墙 */
.title-card {
  margin-top: 16px;
}
.title-empty {
  color: var(--ink-3);
  font-size: 14px;
  padding: 14px 4px;
  text-align: center;
}
.title-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
}
.title-item {
  border: 2px solid var(--ink);
  border-radius: 12px;
  padding: 12px 10px;
  box-shadow: 3px 3px 0 var(--ink);
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: relative;
}
.title-item.on {
  background: var(--yellow);
}
.title-item.off {
  background: #e9e4d8;
  opacity: 0.6;
}
.title-name {
  font-family: var(--font-fun);
  font-size: 17px;
}
.title-desc {
  font-size: 12px;
  color: var(--ink-2);
}
.title-item.off .title-desc {
  color: var(--ink-3);
}
.title-lock {
  position: absolute;
  top: 10px;
  right: 10px;
  font-size: 11px;
  font-weight: 700;
  border: 1.5px solid var(--ink);
  border-radius: 6px;
  padding: 0 6px;
  color: var(--ink-2);
}
.flat {
  color: var(--ink);
}
.orange {
  color: var(--orange);
}
</style>
