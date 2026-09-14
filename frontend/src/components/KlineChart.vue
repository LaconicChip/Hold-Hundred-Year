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
      <span class="chart-sub muted-2 push">红涨绿跌 · 副图为当日涨跌幅</span>
    </div>

    <!-- 空态：尚无结算日，蜡烛一根都还没长出来 -->
    <div v-if="!kline.length" class="empty chart-empty">
      <SvgIcon name="bull" class="chart-mascot" />
      <span class="empty-title">还没有日K数据</span>
      <span class="empty-desc">快进或度过几个交易日，这里会一根根长出蜡烛</span>
    </div>

    <VChart v-else class="chart" :option="option" :autoresize="true" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
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

  const days = props.kline.map((b) => '第' + b.gameDay + '日')
  const candles = props.kline.map((b) => [b.open, b.close, b.low, b.high])
  const closes = props.kline.map((b) => b.close)
  // 涨跌幅副图（%），红涨绿跌
  const chgBars = props.kline.map((b) => ({
    value: +(b.changePct * 100).toFixed(2),
    itemStyle: { color: b.changePct >= 0 ? cUp : cDown }
  }))
  // 最新价高亮：最后一根蜡烛的收盘价标注
  const lastBar = props.kline[props.kline.length - 1]

  const maLines = MA_DEFS.map((m) => ({
    name: m.name,
    type: 'line',
    xAxisIndex: 0,
    yAxisIndex: 0,
    data: maSeries(closes, m.n),
    connectNulls: false,
    showSymbol: false,
    lineStyle: { width: 1.2, color: m.color },
    emphasis: { disabled: true }
  }))

  const tooltipFormatter = (params) => {
    const idx = params[0].dataIndex
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
      { left: 56, right: 46, top: '76%', height: '14%' }
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
        type: 'value',
        gridIndex: 0,
        scale: true,
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
      { type: 'inside', xAxisIndex: [0, 1], start: 0, end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 2, height: 18, borderColor: cInk }
    ],
    series: [
      {
        name: '日K',
        type: 'candlestick',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: candles,
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
        barMaxWidth: 10
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
