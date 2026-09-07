<template>
  <div class="chart-card card">
    <div class="chart-head">
      <span class="chart-title">
        <SvgIcon name="candle" class="chart-ic" />分时走势
      </span>
      <span class="chart-legend">
        <span class="lg"><i class="lg-line lg-stock"></i>该股</span>
        <span class="lg"><i class="lg-line lg-index"></i>百年指数</span>
      </span>
      <span class="chart-sub muted-2">相对开盘涨跌幅 %</span>
    </div>

    <!-- 空态：收盘/未开盘时真实接口返回空数组 -->
    <div v-if="!hasData" class="empty chart-empty">
      <SvgIcon name="bull" class="chart-mascot" />
      <span class="empty-title">行情尚未开盘</span>
      <span class="empty-desc">开盘后这里会跑出股价与指数的双线对决</span>
    </div>

    <!-- 双线对比：股价与指数均归一化为“相对首点的涨跌幅%”，单轴可比 -->
    <VChart v-else class="chart" :option="option" :autoresize="true" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  MarkLineComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { token } from '@/styles/theme'
import SvgIcon from './SvgIcon.vue'

// 按需注册 ECharts 模块（vue-echarts 要求显式 use）
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent])

const props = defineProps({
  // 单股分时：[{ time, price }]，来自 api/stocks.js fetchStockQuotes
  stockPoints: { type: Array, default: () => [] },
  // 指数分时：[{ time, value }]，来自 api/stocks.js fetchIndexPoints
  indexPoints: { type: Array, default: () => [] }
})

// 是否缺数（任一为空即判空态）
const hasData = computed(() => props.stockPoints.length > 0 && props.indexPoints.length > 0)

// 把价格序列归一化为“相对首点的涨跌幅%”
function toPctSeries(points, key) {
  if (!points.length) return []
  const base = points[0][key]
  if (!base) return points.map(() => 0)
  return points.map((p) => +(((p[key] - base) / base) * 100).toFixed(2))
}

const option = computed(() => {
  const cBrand = token('--brand')
  const cInk2 = token('--ink-2')
  const cInk = token('--ink')
  const cInk3 = token('--ink-3')
  const cCard = token('--card')
  const cDash = token('--dash')

  const len = Math.min(props.stockPoints.length, props.indexPoints.length)
  const times = []
  const stockPct = []
  const indexPct = []
  const s = toPctSeries(props.stockPoints, 'price')
  const i = toPctSeries(props.indexPoints, 'value')
  for (let k = 0; k < len; k++) {
    times.push(props.stockPoints[k].time)
    stockPct.push(s[k])
    indexPct.push(i[k])
  }

  return {
    color: [cBrand, cInk2],
    grid: { left: 48, right: 18, top: 16, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: cCard,
      borderColor: cInk,
      borderWidth: 2,
      padding: [6, 10],
      textStyle: { color: cInk, fontSize: 12 },
      valueFormatter: (v) => (v > 0 ? '+' : '') + v.toFixed(2) + '%'
    },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: false,
      axisLine: { lineStyle: { color: cInk, width: 1.5 } },
      axisTick: { show: false },
      axisLabel: { color: cInk2, fontSize: 11, interval: 7 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: cInk2, fontSize: 11, formatter: '{value}%' },
      splitLine: { lineStyle: { color: cDash, type: 'dashed' } }
    },
    series: [
      {
        name: '该股',
        type: 'line',
        data: stockPct,
        smooth: false,
        showSymbol: false,
        lineStyle: { width: 2.6, color: cBrand },
        areaStyle: { color: cBrand, opacity: 0.08 },
        // 0 轴平盘参考线
        markLine: {
          silent: true,
          symbol: 'none',
          label: { show: false },
          lineStyle: { color: cInk3, type: 'dashed', width: 1.5 },
          data: [{ yAxis: 0 }]
        }
      },
      {
        name: '百年指数',
        type: 'line',
        data: indexPct,
        smooth: false,
        showSymbol: false,
        lineStyle: { width: 2, color: cInk2, type: 'dashed' }
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
.lg-stock { background: var(--brand); }
.lg-index {
  background: repeating-linear-gradient(
    90deg,
    var(--ink-2) 0 4px,
    transparent 4px 7px
  );
}
.chart-sub { font-size: var(--fs-xs); }
.chart { width: 100%; height: 300px; }
.chart-empty { height: 300px; }
.chart-mascot { width: 56px; height: 56px; opacity: 0.8; }
</style>
