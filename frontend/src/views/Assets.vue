<template>
  <div class="page">
    <!-- 页头（品牌与返回已由侧栏承担，这里只留标题与段位） -->
    <header class="page-head">
      <div>
        <h1 class="page-title">
          <SvgIcon name="wallet" class="title-ic" />资产总览
        </h1>
        <p class="page-desc">本金 100,000.00 · 融资融券与强平都在这儿看</p>
      </div>
      <TierBadge :tier="market.tier || 'RETAIL'" vertical />
    </header>

    <!-- 资产四宫格：数据块 -->
    <section class="card">
      <div class="grid g4 quad">
        <div class="stat">
          <span class="lab">现金</span>
          <span class="val">{{ assets ? fmtMoney(assets.cash) : '--' }}</span>
        </div>
        <div class="stat">
          <span class="lab">持仓市值</span>
          <span class="val">{{ assets ? fmtMoney(assets.marketValue) : '--' }}</span>
        </div>
        <div class="stat">
          <span class="lab">负债</span>
          <span class="val">{{ assets ? fmtMoney(assets.debt) : '--' }}</span>
        </div>
        <div class="stat stat-total">
          <span class="lab">净资产</span>
          <span class="val">{{ assets ? fmtMoney(assets.netAsset) : '--' }}</span>
          <span v-if="assets" class="sub-val" :class="priceCls(totalProfit)">
            总盈亏 {{ fmtMoney(totalProfit) }}
          </span>
        </div>
      </div>
    </section>

    <!-- 担保比例警戒条 -->
    <section class="card">
      <div class="sec-head">
        <span class="card-title">
          <SvgIcon name="scale" class="title-ic-sm" />担保比例
        </span>
        <span v-if="hasDebt" class="guard-pct num" :class="guardCls">
          {{ (assets.guardRatio * 100).toFixed(2) }}%
        </span>
      </div>

      <template v-if="hasDebt">
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
        </div>
        <div v-if="assets.guardRatio < 1.2" class="guard-warn is-danger">
          <SvgIcon name="alert" class="warn-ic" />担保比例低于 120%，面临强制平仓
        </div>
        <div v-else-if="assets.guardRatio < 1.4" class="guard-warn is-warn">
          <SvgIcon name="alert" class="warn-ic" />担保比例低于 140%，请注意追保风险
        </div>
        <div v-else class="guard-ok muted">
          <SvgIcon name="check" class="warn-ic" />担保比例安全
        </div>
      </template>
      <div v-else class="empty guard-empty">
        <span class="empty-title">当前无融资负债</span>
        <span class="empty-desc">借钱之前，这里一直是空的</span>
      </div>
    </section>

    <!-- 融资面板 -->
    <section class="card">
      <div class="sec-head">
        <span class="card-title">
          <SvgIcon name="banknote" class="title-ic-sm" />融资融券
        </span>
        <span class="stat stat-inline">
          <span class="lab">可用融资额度</span>
          <span class="val">{{ fmtMoney(loanBorrowable) }}</span>
        </span>
      </div>

      <dl v-if="loanInfo" class="kv loan-extra">
        <dt>借款余额</dt>
        <dd>{{ fmtMoney(loanInfo.debt) }}</dd>
        <dt>账户余额</dt>
        <dd>{{ fmtMoney(loanInfo.balance) }}</dd>
      </dl>
      <p v-else class="loan-note muted-2">
        预估可借额度 = 净资产 - 负债（口径说明：borrowable 仅在借款/还款响应返回，无独立查询接口，初始以净资产减负债估算展示）
      </p>

      <div class="loan-form">
        <el-input-number v-model="loanAmount" :min="0" :step="1000" :controls="true" class="loan-input" />
        <button class="btn btn-warn" :disabled="loanLoading" @click="doBorrow">
          <SvgIcon name="alert" class="btn-ic" />借款
        </button>
        <button class="btn" :disabled="loanLoading" @click="doRepay">
          <SvgIcon name="check" class="btn-ic" />还款
        </button>
      </div>
      <div v-if="loanErr" class="loan-err">{{ loanErr }}</div>
    </section>

    <!-- 持仓表 -->
    <section class="card">
      <div class="sec-head">
        <span class="card-title">
          <SvgIcon name="candle" class="title-ic-sm" />我的持仓
        </span>
        <span v-if="positions.length" class="tag">{{ positions.length }} 只</span>
      </div>
      <div v-if="!positions.length" class="empty pos-empty">
        <img :src="bullImg" class="empty-art" alt="牛小牛吉祥物" />
        <span class="empty-title">空仓中，牛小牛在吃草</span>
        <span class="empty-desc">去大盘挑一只，下个买单</span>
      </div>
      <div v-else class="scroll-x">
        <table class="tbl zebra pos-table">
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
      </div>
    </section>

    <!-- 流水：三段 Tab -->
    <section class="card">
      <div class="sec-head">
        <span class="card-title">
          <SvgIcon name="note" class="title-ic-sm" />资金流水
        </span>
        <div class="seg">
          <button :class="{ on: tab === 'trade' }" @click="tab = 'trade'">交易流水</button>
          <button :class="{ on: tab === 'loan' }" @click="tab = 'loan'">融资流水</button>
          <button :class="{ on: tab === 'ipo' }" @click="tab = 'ipo'">打新记录</button>
        </div>
      </div>

      <!-- 交易流水 -->
      <template v-if="tab === 'trade'">
        <div v-if="!trades.length" class="empty flow-empty">
          <span class="empty-title">暂无交易流水</span>
          <span class="empty-desc">第一笔成交之后，这里会有记录</span>
        </div>
        <div v-else class="scroll-x">
          <table class="tbl zebra flow-table">
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
                  <span class="tag" :class="t.side === 'buy' ? 'side-buy' : 'side-sell'">
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
      </template>

      <!-- 融资流水 -->
      <template v-else-if="tab === 'loan'">
        <div v-if="!loanRecords.length" class="empty flow-empty">
          <span class="empty-title">暂无融资流水</span>
          <span class="empty-desc">借过一笔钱，这里就会开始记账</span>
        </div>
        <div v-else class="scroll-x">
          <table class="tbl zebra flow-table">
            <thead>
              <tr>
                <th>时间</th><th>类型</th><th>金额</th>
                <th>操作后负债</th><th>交易日</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in loanRecords" :key="r.id">
                <td class="num dim">{{ r.time }}</td>
                <td>
                  <span class="tag" :class="loanCls(r.loanType)">{{ loanLabel(r.loanType) }}</span>
                </td>
                <td class="num">{{ fmtMoney(r.amount) }}</td>
                <td class="num">{{ fmtMoney(r.debtAfter) }}</td>
                <td class="num dim">第 {{ r.gameDay }} 天</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <!-- 打新记录 -->
      <template v-else>
        <div v-if="!ipoRecords.length" class="empty flow-empty">
          <span class="empty-title">暂无打新记录</span>
          <span class="empty-desc">申购日开盘弹窗里顶格申购后，这里跟踪中签与上市</span>
        </div>
        <div v-else class="scroll-x">
          <table class="tbl zebra flow-table">
            <thead>
              <tr>
                <th>时间</th><th>新股</th><th>发行价</th>
                <th>状态</th><th>盈利</th><th>交易日</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in ipoRecords" :key="r.id">
                <td class="num dim">{{ r.time }}</td>
                <td>{{ r.ipoName }}</td>
                <td class="num">{{ r.issuePrice.toFixed(2) }}</td>
                <td>
                  <span class="tag" :class="ipoCls(r.status)">{{ ipoLabel(r.status) }}</span>
                </td>
                <td class="num" :class="priceCls(r.profit)">{{ settledProfitText(r) }}</td>
                <td class="num dim">第 {{ r.gameDay }} 天</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </section>

    <!-- 全剧资产 vs 指数曲线 -->
    <section class="card">
      <div class="sec-head">
        <span class="card-title">
          <SvgIcon name="chart" class="title-ic-sm" />{{ market.totalDays || TOTAL_DAYS }} 天资产 vs 百年指数
        </span>
      </div>
      <div v-if="!curveHasData" class="empty curve-empty">
        <img :src="pandaImg" class="empty-art" alt="熊猫吉祥物" />
        <span class="empty-title">完成首个交易日后生成曲线</span>
        <span class="empty-desc">熊猫先替你守着这张空图</span>
      </div>
      <VChart v-else class="curve" :option="curveOption" :autoresize="true" />
    </section>

    <!-- 称号徽章墙 -->
    <section class="card">
      <div class="sec-head">
        <span class="card-title">
          <SvgIcon name="trophy" class="title-ic-sm" />称号徽章
        </span>
        <span v-if="titles.length" class="tag">
          已解锁 {{ titles.filter((t) => t.unlocked).length }} / {{ titles.length }}
        </span>
      </div>
      <div v-if="!titles.length" class="empty">
        <span class="empty-title">暂未获得称号</span>
      </div>
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
import { useMarketStore } from '@/stores/market'
import { useUserStore } from '@/stores/user'
import { fetchAssets, fetchPositions, fetchTrades, fetchLoanRecords, fetchIpoRecords, fetchTitles } from '@/api/me'
import { borrow, repay } from '@/api/loan'
import { fetchEquityCurve } from '@/api/market'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { token } from '@/styles/theme'
import TierBadge from '@/components/TierBadge.vue'
import StickerTag from '@/components/StickerTag.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { ElMessage } from 'element-plus'
import bullImg from '@/assets/img/bull.png'
import pandaImg from '@/assets/img/panda.png'
import { priceCls, fmtPct, fmtMoney } from '@/utils/format'
import { TOTAL_DAYS } from '@/constants/rhythm'

// 按需注册 ECharts 模块（与 StockChart 一致）
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

const market = useMarketStore()
const user = useUserStore()

// 初始资金常量（与后端约定 100000 起始）
const INIT_CAPITAL = 100000

// ---- 数据状态 ----
const assets = ref(null)
const positions = ref([])
const trades = ref([])
const loanRecords = ref([])
const ipoRecords = ref([])
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

const hasDebt = computed(() => assets.value && assets.value.debt > 0 && assets.value.guardRatio !== 0)
// 总盈亏 = 净资产 - 初始资金
const totalProfit = computed(() => (assets.value ? assets.value.netAsset - INIT_CAPITAL : 0))

// 可用融资额度：有真实响应用 borrowable，否则按净资产减负债估算
const loanBorrowable = computed(() => {
  if (loanInfo.value) return loanInfo.value.borrowable
  if (assets.value) return Math.max(assets.value.netAsset - assets.value.debt, 0)
  return 0
})

// ---- 担保比例标尺 ----
// guardRatio 为比率（8.00 = 800%）；标尺右端取 max(当前, 160) 保证 120/140 标记可见
const pos120 = computed(() => (120 / rulerMax.value) * 100)
const pos140 = computed(() => (140 / rulerMax.value) * 100)
const posCur = computed(() =>
  Math.min(((assets.value.guardRatio * 100) / rulerMax.value) * 100, 100)
)
const rulerMax = computed(() => (assets.value ? Math.max(assets.value.guardRatio * 100, 160) : 160))
const guardCls = computed(() => {
  if (!assets.value) return 'flat'
  if (assets.value.guardRatio < 1.2) return 'down'
  if (assets.value.guardRatio < 1.4) return 'orange'
  return 'up'
})

// ---- 净值曲线 ----
const curveHasData = computed(() => curvePoints.value.length > 0)
const curveOption = computed(() => {
  const cBrand = token('--brand')
  const cInk2 = token('--ink-2')
  const cInk = token('--ink')
  const cCard = token('--card')
  const cDash = token('--dash')

  const days = curvePoints.value.map((p) => p.gameDay)
  const net = curvePoints.value.map((p) => +p.netAsset.toFixed(2))
  const idx = curvePoints.value.map((p) => +p.indexValue.toFixed(2))
  return {
    color: [cBrand, cInk2],
    grid: { left: 62, right: 58, top: 16, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: cCard,
      borderColor: cInk,
      borderWidth: 2,
      padding: [6, 10],
      textStyle: { color: cInk, fontSize: 12 }
    },
    legend: {
      data: ['净资产', '百年指数'],
      top: 0,
      right: 0,
      itemWidth: 18,
      itemHeight: 8,
      textStyle: { color: cInk, fontSize: 12, fontWeight: 700 }
    },
    xAxis: {
      type: 'category',
      data: days,
      boundaryGap: false,
      axisLine: { lineStyle: { color: cInk, width: 1.5 } },
      axisTick: { show: false },
      axisLabel: { color: cInk2, fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value',
        name: '净资产',
        nameTextStyle: { color: cInk2, fontSize: 11 },
        scale: true,
        axisLabel: { color: cInk2, fontSize: 11 },
        splitLine: { lineStyle: { color: cDash, type: 'dashed' } }
      },
      {
        type: 'value',
        name: '指数',
        nameTextStyle: { color: cInk2, fontSize: 11 },
        scale: true,
        axisLabel: { color: cInk2, fontSize: 11 },
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
        lineStyle: { width: 2.6, color: cBrand },
        areaStyle: { color: cBrand, opacity: 0.08 }
      },
      {
        name: '百年指数',
        type: 'line',
        yAxisIndex: 1,
        data: idx,
        smooth: false,
        showSymbol: false,
        lineStyle: { width: 2, color: cInk2, type: 'dashed' }
      }
    ]
  }
})

// ---- 工具函数 ----
// 融资流水类型：后端 loan_record.loan_type 原文 -> 中文标签 / 配色类
const LOAN_LABEL = { BORROW: '借款', REPAY: '还款', INTEREST: '结息', LIQUIDATE: '强平还款' }
const LOAN_CLS = { BORROW: 'loan-borrow', REPAY: 'loan-repay', INTEREST: 'loan-interest', LIQUIDATE: 'loan-liquidate' }
// 打新状态：后端 user_ipo_record.status 原文 -> 中文标签 / 配色类
const IPO_LABEL = { SUBSCRIBED: '已申购', PAID: '已中签', LOST: '未中签', ABANDONED: '放弃缴款', SETTLED: '已结算' }
const IPO_CLS = { SUBSCRIBED: 'ipo-sub', PAID: 'ipo-paid', LOST: 'ipo-flat', ABANDONED: 'ipo-flat', SETTLED: 'ipo-settled' }
function loanLabel(t) {
  return LOAN_LABEL[t] || t
}
function loanCls(t) {
  return LOAN_CLS[t] || 'tag-flat'
}
function ipoLabel(s) {
  return IPO_LABEL[s] || s
}
function ipoCls(s) {
  return IPO_CLS[s] || 'tag-flat'
}
// 盈利只在结算后有意义：未结算显示占位，避免把 0 当成"没赚"
function settledProfitText(r) {
  return r.status === 'SETTLED' ? fmtMoney(r.profit) : '--'
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
    await loadLoanRecords() // 借款立即进流水
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
    await loadLoanRecords() // 还款立即进流水
    ElMessage.success('还款成功')
  } catch (e) {
    loanErr.value = e.message || '还款失败'
  } finally {
    loanLoading.value = false
  }
}

// 融资流水 / 打新记录：GET /api/me/loan-records 与 /api/me/ipo-records，失败降级空态
async function loadLoanRecords() {
  const lr = await fetchLoanRecords().catch(() => ({ records: [] }))
  loanRecords.value = lr.records || []
}
async function loadIpoRecords() {
  const ir = await fetchIpoRecords().catch(() => ({ records: [] }))
  ipoRecords.value = ir.records || []
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
  await loadLoanRecords()
  await loadIpoRecords()
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
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.title-ic { width: 0.85em; height: 0.85em; color: var(--brand); }
.title-ic-sm { width: 1em; height: 1em; color: var(--brand); }
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
}
.card-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
  margin-bottom: 0;
}

/* ---------- 资产四宫格 ---------- */
.quad { gap: var(--sp-3); }
.stat { align-items: flex-start; }
.stat-total { background: var(--paper-warm); }
.stat-inline {
  flex-direction: row;
  align-items: baseline;
  gap: var(--sp-2);
  padding: 2px var(--sp-3);
}
.stat-inline .val { font-size: var(--fs-lg); }

/* ---------- 担保比例 ---------- */
.guard-pct { font-size: var(--fs-2xl); }
.ruler {
  position: relative;
  padding: var(--sp-6) 0 var(--sp-8);
}
.ruler-track {
  position: relative;
  height: 14px;
  background: var(--paper);
  border: var(--border);
  border-radius: var(--radius-pill);
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
  height: 18px;
  display: block;
}
.mark-red .mark-tick { background: var(--up); }
.mark-orange .mark-tick { background: var(--orange); height: 34px; }
.mark-text {
  position: absolute;
  top: 20px;
  font-size: var(--fs-xs);
  font-weight: 700;
  white-space: nowrap;
}
/* 两个刻度挨得近时错位排布，避免文字叠字 */
.mark-red .mark-text { top: 20px; color: var(--up); }
.mark-orange .mark-text { top: 38px; color: var(--orange); }
.pointer {
  position: absolute;
  top: -6px;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
}
.pointer-flag {
  position: absolute;
  top: -26px;
  font-size: var(--fs-xs);
  font-weight: 700;
  font-style: normal;
  background: var(--card);
  border: var(--border);
  border-radius: 6px;
  padding: 0 6px;
  white-space: nowrap;
}
.pointer.up .pointer-flag { color: var(--down); }
.pointer.orange .pointer-flag { color: var(--orange); }
.pointer.down .pointer-flag { color: var(--up); }

.guard-warn,
.guard-ok {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--sp-3);
  font-size: var(--fs-base);
  font-weight: 700;
  border: var(--border);
  border-radius: var(--radius-md);
  padding: var(--sp-2) var(--sp-3);
}
.guard-warn.is-danger { background: var(--brand-soft); color: var(--up); }
.guard-warn.is-warn { background: var(--tr-pending-bg); color: var(--tr-pending-fg); }
.guard-ok { background: var(--down-soft); color: var(--down); font-weight: 400; }
.warn-ic { width: 1.1em; height: 1.1em; flex: none; }
.guard-empty { padding: var(--sp-5) var(--sp-4); }

/* ---------- 融资 ---------- */
.loan-extra { margin-bottom: var(--sp-2); font-size: var(--fs-sm); }
.loan-note { font-size: var(--fs-xs); line-height: 1.5; margin-bottom: var(--sp-2); }
.loan-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-3);
}
.loan-input { width: 200px; }
.btn-ic { width: 1em; height: 1em; }
.loan-err {
  margin-top: var(--sp-2);
  color: var(--up);
  font-size: var(--fs-sm);
  font-weight: 700;
}

/* ---------- 表格 ---------- */
.pos-name b { font-weight: 700; }
.flow-table { font-size: var(--fs-sm); }
.flow-table .dim { color: var(--ink-3); }
.side-buy { color: var(--up); border-color: var(--up); }
.side-sell { color: var(--down); border-color: var(--down); }
/* 融资/打新状态标签：配色走令牌，红绿只用于金额涨跌，状态本身用橙/蓝/灰/印章红 */
.loan-borrow { color: var(--orange); border-color: var(--orange); }
.loan-repay { color: var(--blue); border-color: var(--blue); }
.loan-interest { color: var(--ink-2); border-color: var(--dash); }
.loan-liquidate { color: var(--seal); border-color: var(--seal); }
.ipo-sub { color: var(--blue); border-color: var(--blue); }
.ipo-paid { color: var(--up); border-color: var(--up); }
.ipo-flat { color: var(--ink-3); border-color: var(--ink-3); }
.ipo-settled { color: var(--ink-2); border-color: var(--dash); }
.tag-flat { color: var(--ink-2); border-color: var(--dash); }
.pos-empty { padding: var(--sp-6) var(--sp-4); }
.empty-art { width: 88px; height: 88px; object-fit: contain; }
.flow-empty { padding: var(--sp-6) var(--sp-4); }

/* ---------- 曲线 ---------- */
.curve { width: 100%; height: 320px; }
.curve-empty { height: 320px; }

/* ---------- 称号墙 ---------- */
.title-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--sp-3);
}
.title-item {
  border: var(--border);
  border-radius: var(--radius-md);
  padding: var(--sp-3) var(--sp-3);
  box-shadow: var(--shadow);
  display: flex;
  flex-direction: column;
  gap: 2px;
  position: relative;
}
.title-item.on { background: var(--yellow); }
.title-item.off { background: var(--tier-retail); opacity: 0.65; }
.title-name { font-family: var(--font-fun); font-size: var(--fs-lg); }
.title-desc { font-size: var(--fs-sm); color: var(--ink-2); }
.title-item.off .title-desc { color: var(--ink-3); }
.title-lock {
  position: absolute;
  top: var(--sp-2);
  right: var(--sp-2);
  font-size: var(--fs-xs);
  font-weight: 700;
  border: var(--border-thin);
  border-radius: 6px;
  padding: 0 6px;
  color: var(--ink-2);
}

.orange { color: var(--orange); }

@media (max-width: 900px) {
  .quad { grid-template-columns: 1fr 1fr; }
}
</style>
