<template>
  <div class="chart-card card">
    <div class="chart-head">
      <span class="chart-title">
        <SvgIcon name="chart" class="chart-ic" />日K线
      </span>
      <span class="chart-legend">
        <span class="lg lg-ma" v-for="m in MA_DEFS" :key="m.name">
          <i class="lg-line" :style="{ background: m.color }"></i>{{ m.name }}
        </span>
      </span>
      <span class="chart-sub muted-2 push">红涨绿跌 · 滚轮缩放 · 底部滑块拖看历史</span>
    </div>

    <!-- 空态：尚无结算日，蜡烛一根都还没长出来 -->
    <div v-if="!kline.length" class="empty chart-empty">
      <SvgIcon name="bull" class="chart-mascot" />
      <span class="empty-title">还没有日K数据</span>
      <span class="empty-desc">快进或度过几个交易日，这里会一根根长出蜡烛</span>
    </div>

    <div v-else ref="wrapEl" class="chart">
      <VChart class="chart-canvas" :option="option" :autoresize="true" @datazoom="onZoom" />
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { use } from 'echarts/core'
import { CandlestickChart, BarChart, LineChart } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import { GridComponent, TooltipComponent, DataZoomComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { token } from '@/styles/theme'
import SvgIcon from './SvgIcon.vue'

// 按需注册 ECharts 模块（vue-echarts 要求显式 use）
use([
  CanvasRenderer,
  CandlestickChart,
  BarChart,
  LineChart,
  GridComponent,
  TooltipComponent,
  DataZoomComponent
])

const props = defineProps({
  // 日K序列（game_day 升序）：[{ gameDay, open, high, low, close, changePct }]
  kline: { type: Array, default: () => [] }
})

// 蜡烛宽度口径：默认一屏 40 根（证券软件"最近一段"的观感），宽度 = 绘图区宽 ÷ 根数，
// 所以不限制死宽度也能等宽铺满——之前按"每根 9px"算槽位，1440 屏能放 88 根，而一整局
// 最多 82 根，永远塞得下，滑块也就永远没有可拖的余量（"拖滑块看历史"根本不可能发生）。
const DEFAULT_BARS = 40
// 单根最窄也要认得出来：容器太窄时按这个下限减少根数（窄面板 ≈ 28 根）
const MIN_BAR_PX = 8
const MIN_BARS = 10
const MAX_BARS = 120
// 首帧还没量到容器宽度时的兜底（约等于 1440 屏去掉侧栏与留白）
const FALLBACK_GRID_PX = 800

// 绘图区宽度：随窗口/侧栏折叠变化重算，保证"每根 ~9px"这个观感在任何宽度下成立
const wrapEl = ref(null)
const gridPx = ref(0)
let resizeObserver = null

// 容器是 v-else 出来的（有数据才存在），所以不能在 onMounted 里一次性挂观察者：
// 那时量到的是 0，会一路用兜底宽度，把整段 K 线塞进一屏、滑块也就没有可拖的余量。
function attachObserver(el) {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (!el || typeof ResizeObserver === 'undefined') return
  const measure = () => {
    // 与下面 grid 的 left/right 对齐（y 轴标签 56 + 最新价标注留白 46）
    const w = el.clientWidth - 102
    if (w > 0) gridPx.value = Math.max(120, w)
  }
  measure()
  resizeObserver = new ResizeObserver(measure)
  resizeObserver.observe(el)
}
watch(wrapEl, (el) => attachObserver(el), { flush: 'post' })

onBeforeUnmount(() => {
  if (resizeObserver) resizeObserver.disconnect()
})

/**
 * 玩家自己滚出来/拖出来的窗口，记成"看几根 + 距右端空几根"，而不是百分比：
 * 新 K 线长出来、容器宽度变化（滚动条出现/侧栏折叠）都会让 option 重算，
 * 用百分比就会把他的视野一把打回默认（这个坑踩过：滑块像被焊死，拖不动）。
 *
 * <p>必须是【普通变量，不能是 ref】：拖动滑块时 ECharts 每帧都发 datazoom，回写响应式
 * 状态会立刻触发 option 重算 + setOption，等于在它手活半途把内部拖拽状态抢过来重设——
 * 结果就是"拖滑块不平移，反而把窗口拉大缩小"。这里只记账，等下次真的需要重建 option
 * （新 K 线落库 / 容器变宽）时再读它。
 */
let userWindow = null

function onZoom(e) {
  const box = (e && e.batch && e.batch[0]) || e || {}
  if (typeof box.start !== 'number' || typeof box.end !== 'number') return
  const n = props.kline.length
  if (!n) return
  const total = n + padOf(n)
  const startIdx = Math.round((box.start / 100) * (total - 1))
  const endIdx = Math.round((box.end / 100) * (total - 1))
  userWindow = {
    span: Math.max(2, endIdx - startIdx + 1),
    rightGap: Math.max(0, total - 1 - endIdx)
  }
}

/** 左侧空槽数（与 option 里的口径保持一致）。 */
function padOf(nBars) {
  return Math.max(0, viewSlots(nBars) - nBars)
}

// 一屏有多少个槽位（与"已经长出来几根"无关）：证券软件就是按槽位画轴的，
// 所以根数不足一屏时是"左边留空"，而不是把仅有的几根拉成巨无霸。
function viewSlots(total) {
  const grid = gridPx.value || FALLBACK_GRID_PX
  // 默认根数，但窄容器下按"每根不低于 MIN_BAR_PX"回收
  const byWidth = Math.max(MIN_BARS, Math.floor(grid / MIN_BAR_PX))
  const capped = Math.max(MIN_BARS, Math.min(MAX_BARS, Math.min(DEFAULT_BARS, byWidth)))
  return total ? Math.min(capped, Math.max(MIN_BARS, total)) : capped
}

/**
 * 纵向留白：可见区间的 8%、价格的 0.4%、2 分钱三者取大。
 * 关键是那个"价格比例 + 固定最小值"的兜底：窗口里只有一根蜡烛、或全天横盘
 * 导致 high==low 时，区间为 0，没有兜底就会把 0 价差铺满整个高度（"线爆炸高"）。
 */
function yPad(min, max) {
  return Math.max((max - min) * 0.08, max * 0.004, 0.02)
}

// MA 均线定义（颜色收敛到既有令牌：黄/蓝/橙）
const MA_DEFS = [
  { name: 'MA5', n: 5, color: token('--yellow') },
  { name: 'MA10', n: 10, color: token('--blue') },
  { name: 'MA20', n: 20, color: token('--orange') }
]

// 前端计算 MA(n)：前 n-1 根为 null
function maSeries(closes, n) {
  return closes.map((_, i) => {
    if (i < n - 1) return null
    let sum = 0
    for (let k = i - n + 1; k <= i; k++) sum += closes[k]
    return +(sum / n).toFixed(3)
  })
}

const option = computed(() => {
  const cUp = token('--up')
  const cDown = token('--down')
  const cInk = token('--ink')
  const cInk2 = token('--ink-2')
  const cCard = token('--card')
  const cDash = token('--dash')

  // 开局前播种的历史 K 线（game_day <= 0）标成 T-N，与局内"第 N 日"区分开
  const nBars = props.kline.length
  const slots = viewSlots(nBars)
  // 左侧空槽：根数不足一屏时把轴补足，蜡烛宽度就恒定是"一格一根"，
  // 不会出现"只有几根时摊满全屏"的巨无霸（那也是"线爆炸高"的另一半观感）
  const pad = Math.max(0, slots - nBars)
  const total = nBars + pad
  const blank = (v) => Array.from({ length: pad }, () => v)
  const days = [
    ...blank(''),
    ...props.kline.map((b) => (b.gameDay > 0 ? '第' + b.gameDay + '日' : 'T' + b.gameDay))
  ]
  // 窗口：玩家动过就按"看几根 + 距右端多远"换算回来，否则默认贴右端看最近一窗
  let startIdx = Math.max(0, total - slots)
  let endIdx = total - 1
  if (userWindow) {
    endIdx = Math.max(0, Math.min(total - 1, total - 1 - userWindow.rightGap))
    startIdx = Math.max(0, endIdx - userWindow.span + 1)
    endIdx = Math.min(total - 1, startIdx + userWindow.span - 1)
  }
  const zoomStart = total <= 1 ? 0 : (startIdx / (total - 1)) * 100
  const zoomEnd = total <= 1 ? 100 : (endIdx / (total - 1)) * 100
  // 空槽用 ECharts 官方的缺失值 '-'：candlestick 的数据项给 null 会直接抛
  // "Cannot read properties of null"，整张图就不画了（踩过一次）
  const candles = [...blank('-'), ...props.kline.map((b) => [b.open, b.close, b.low, b.high])]
  const closes = props.kline.map((b) => b.close)
  // 涨跌幅副图（%），红涨绿跌
  const chgBars = [
    ...blank(null),
    ...props.kline.map((b) => ({
      value: +(b.changePct * 100).toFixed(2),
      itemStyle: { color: b.changePct >= 0 ? cUp : cDown }
    }))
  ]
  // 最新价高亮：最后一根蜡烛的收盘价标注
  const lastBar = props.kline[props.kline.length - 1]

  const maLines = MA_DEFS.map((m) => ({
    name: m.name,
    type: 'line',
    xAxisIndex: 0,
    yAxisIndex: 0,
    data: [...blank(null), ...maSeries(closes, m.n)],
    connectNulls: false,
    showSymbol: false,
    lineStyle: { width: 1.2, color: m.color },
    emphasis: { disabled: true }
  }))

  const tooltipFormatter = (params) => {
    // 轴上多了左侧空槽，回到真实数据要减掉这段偏移
    const idx = params[0].dataIndex - pad
    const b = props.kline[idx]
    if (!b) return ''
    const pct = b.changePct * 100
    const cls = pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat'
    const rows = [
      `<b>${params[0].axisValue}</b>`,
      `开 <span class="num">${b.open.toFixed(2)}</span>　收 <span class="num">${b.close.toFixed(2)}</span>`,
      `高 <span class="num">${b.high.toFixed(2)}</span>　低 <span class="num">${b.low.toFixed(2)}</span>`,
      `涨跌幅 <span class="num ${cls}">${pct > 0 ? '+' : ''}${pct.toFixed(2)}%</span>`
    ]
    for (const p of params) {
      if (p.seriesName && p.seriesName.startsWith('MA') && p.value != null) {
        rows.push(`${p.marker}${p.seriesName} <span class="num">${p.value.toFixed(2)}</span>`)
      }
    }
    return rows.join('<br/>')
  }

  return {
    animation: false,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', link: [{ xAxisIndex: 'all' }], label: { backgroundColor: cInk } },
      backgroundColor: cCard,
      borderColor: cInk,
      borderWidth: 2,
      padding: [6, 10],
      textStyle: { color: cInk, fontSize: 12 },
      formatter: tooltipFormatter
    },
    // 两个 grid 左右边距必须一致（x 轴联动 + 十字光标对齐）；右侧留白给最新价标注
    grid: [
      { left: 56, right: 46, top: 16, height: '58%' },
      { left: 56, right: 46, top: '74%', height: '13%' }
    ],
    xAxis: [
      {
        type: 'category',
        gridIndex: 0,
        data: days,
        boundaryGap: true,
        axisLine: { lineStyle: { color: cInk, width: 1.5 } },
        axisTick: { show: false },
        axisLabel: { show: false }
      },
      {
        type: 'category',
        gridIndex: 1,
        data: days,
        boundaryGap: true,
        axisLine: { lineStyle: { color: cInk, width: 1.5 } },
        axisTick: { show: false },
        axisLabel: { color: cInk2, fontSize: 11 }
      }
    ],
    yAxis: [
      {
        // 不用 scale:true：它会把"当前窗口的高低价差"直接拉满整个高度——只有一根
        // 蜡烛或全天横盘时，价差 0.01 也能顶天立地，就是"那线爆炸高"的成因。
        // 改成按可见区间留 8% 上下白并带最小跨度兜底（见 yPad），
        // 窗口内数据变了（缩放/拖动）回调会重算，所以缩放时纵向比例也跟着走。
        type: 'value',
        gridIndex: 0,
        min: ({ min, max }) => +(min - yPad(min, max)).toFixed(3),
        max: ({ min, max }) => +(max + yPad(min, max)).toFixed(3),
        axisLabel: { color: cInk2, fontSize: 11, formatter: (v) => v.toFixed(2) },
        splitLine: { lineStyle: { color: cDash, type: 'dashed' } }
      },
      {
        type: 'value',
        gridIndex: 1,
        axisLabel: { color: cInk2, fontSize: 10, formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    dataZoom: [
      // 滚轮缩放（拖动平移只在滑块上来，避免和十字光标抢操作）
      {
        type: 'inside',
        xAxisIndex: [0, 1],
        start: zoomStart,
        end: zoomEnd,
        // 滚轮改宽度（放大=可见根数变少=蜡烛变宽）；在图上按住拖动 = 平移看历史，
        // 与底部滑块同一个窗口，两条路都能走
        zoomOnMouseWheel: true,
        moveOnMouseMove: true,
        moveOnMouseWheel: false,
        preventDefaultMouseMove: true
      },
      // 底部滑块：K 线攒多了以后拖回去看历史
      {
        type: 'slider',
        xAxisIndex: [0, 1],
        start: zoomStart,
        end: zoomEnd,
        bottom: 2,
        height: 22,
        borderColor: cInk,
        handleSize: 12,
        moveHandleSize: 6,
        emphasis: { handleStyle: { borderColor: cInk } }
      }
    ],
    series: [
      {
        name: '日K',
        type: 'candlestick',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: candles,
        // 不设 barMaxWidth：宽度由"容器宽 ÷ 可见根数"决定，天然等宽且铺满，
        // 滚轮缩放（改可见根数）就是改宽度
        barWidth: '70%',
        itemStyle: {
          color: cUp,          // 阳线（收>开）红
          color0: cDown,       // 阴线（收<开）绿
          borderColor: cUp,
          borderColor0: cDown
        },
        markPoint: {
          silent: true,
          symbol: 'roundRect',
          symbolSize: [1, 1],
          label: {
            show: true,
            position: 'right',
            fontSize: 11,
            fontWeight: 700,
            color: lastBar && lastBar.close >= lastBar.open ? cUp : cDown,
            formatter: () => (lastBar ? lastBar.close.toFixed(2) : '')
          },
          data: lastBar ? [{ coord: [days.length - 1, lastBar.close] }] : []
        }
      },
      ...maLines,
      {
        name: '涨跌幅',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: chgBars,
        barWidth: '55%'
      }
    ]
  }
})
</script>

<style scoped>
.chart-card { padding: var(--sp-3) var(--sp-4) var(--sp-2); }
.chart-head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
.chart-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
}
.chart-ic { width: 1em; height: 1em; color: var(--brand); }
.chart-legend {
  display: inline-flex;
  gap: var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.lg { display: inline-flex; align-items: center; gap: 5px; }
.lg-line { width: 16px; height: 3px; border-radius: 2px; display: inline-block; }
.chart { width: 100%; height: 400px; }
.chart-empty { height: 320px; }
.chart-mascot { width: 56px; height: 56px; opacity: 0.8; }
</style>
