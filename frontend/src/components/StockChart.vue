<template>
  <div class="chart-card card">
    <div class="chart-head">
      <span class="chart-title">分时走势</span>
      <span class="chart-sub">股价与百年指数 · 相对开盘涨跌幅</span>
    </div>
    <!-- 空态：mock 有数据，仅在真实接口返回空数组时显示 -->
    <div v-if="!hasData" class="chart-empty">行情尚未开盘</div>
    <!-- 双线对比：股价与指数均归一化为“相对首点的涨跌幅%”，单轴可比 -->
    <VChart
      v-else
      class="chart"
      :option="option"
      :autoresize="true"
    />
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
  LegendComponent
} from 'echarts/components'
import VChart from 'vue-echarts'

// 按需注册 ECharts 模块（vue-echarts 要求显式 use）
use([
  CanvasRenderer,
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent
])

const props = defineProps({
  // 单股分时：[{ time, price }]，来自 api/stocks.js fetchStockQuotes（当前走 mock）
  stockPoints: { type: Array, default: () => [] },
  // 指数分时：[{ time, value }]，来自 api/stocks.js fetchIndexPoints（当前走 mock）
  indexPoints: { type: Array, default: () => [] }
})

// 是否缺数（任一为空即判空态；真实接口补齐后自动生效）
const hasData = computed(
  () => props.stockPoints.length > 0 && props.indexPoints.length > 0
)

// 把价格序列归一化为“相对首点的涨跌幅%”
function toPctSeries(points, key) {
  if (!points.length) return []
  const base = points[0][key]
  if (!base) return points.map(() => 0)
  return points.map((p) => +(((p[key] - base) / base) * 100).toFixed(2))
}

const option = computed(() => {
  const len = Math.min(props.stockPoints.length, props.indexPoints.length)
  const times = []
  const stockPct = []
  const indexPct = []
  for (let i = 0; i < len; i++) {
    times.push(props.stockPoints[i].time)
    stockPct.push(toPctSeries(props.stockPoints, 'price')[i])
    indexPct.push(toPctSeries(props.indexPoints, 'value')[i])
  }

  return {
    color: ['#ff2436', '#6f675c'], // 该股=品牌红，百年指数=墨灰（仅用令牌色）
    grid: { left: 44, right: 16, top: 36, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#fffdf7',
      borderColor: '#2b2620',
      borderWidth: 2,
      textStyle: { color: '#2b2620', fontSize: 12 },
      valueFormatter: (v) => (v > 0 ? '+' : '') + v.toFixed(2) + '%'
    },
    legend: {
      data: ['该股', '百年指数'],
      top: 4,
      right: 8,
      itemWidth: 18,
      itemHeight: 8,
      textStyle: { color: '#2b2620', fontSize: 12, fontWeight: 700 }
    },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#2b2620' } },
      axisLabel: { color: '#6f675c', fontSize: 11, interval: 7 }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        color: '#6f675c',
        fontSize: 11,
        formatter: '{value}%'
      },
      splitLine: { lineStyle: { color: '#e6dcc8', type: 'dashed' } }
    },
    series: [
      {
        name: '该股',
        type: 'line',
        data: stockPct,
        smooth: false,
        showSymbol: false,
        lineStyle: { width: 2.5, color: '#ff2436' }
      },
      {
        name: '百年指数',
        type: 'line',
        data: indexPct,
        smooth: false,
        showSymbol: false,
        lineStyle: { width: 2, color: '#6f675c', type: 'dashed' }
      }
    ]
  }
})
</script>

<style scoped>
.chart-card {
  padding: 14px 16px 8px;
  margin-top: 16px;
}
.chart-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}
.chart-title {
  font-family: var(--font-fun);
  font-size: 19px;
}
.chart-sub {
  color: var(--ink-2);
  font-size: 12px;
}
.chart {
  width: 100%;
  height: 320px;
}
.chart-empty {
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink-3);
  font-size: 15px;
}
</style>
