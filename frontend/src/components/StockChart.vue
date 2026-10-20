<template>
  <div class="chart-card card">
    <div class="chart-head">
      <span class="chart-title">
        <SvgIcon name="candle" class="chart-ic" />分时走势
      </span>
      <span class="chart-legend">
        <span class="lg"><i class="lg-line lg-price"></i>价格</span>
        <span class="lg"><i class="lg-line lg-avg"></i>均价</span>
      </span>
      <span v-if="closed" class="sticker st-halt closed-badge">已收盘</span>
      <span class="chart-sub muted-2 push">左轴价格 · 右轴涨跌幅%</span>
    </div>

    <!-- 空态：未开盘时固定轴 90 点全为 null；收盘态无点说明期间服务重启过（分时不落库） -->
    <div v-if="!hasData" class="empty chart-empty">
      <SvgIcon name="bull" class="chart-mascot" />
      <span class="empty-title">{{ closed ? '当日分时已过保' : '行情尚未开盘' }}</span>
      <span class="empty-desc">
        {{ closed
          ? '分时曲线只活在内存里，服务重启后从快照价重新累积；收盘价与日K不受影响'
          : `开盘后这里会跑出分时曲线，${tickSeconds} 秒一跳实时延伸` }}
      </span>
    </div>

    <div v-else class="chart-wrap">
      <VChart class="chart" :option="option" :autoresize="true" />
      <!-- 股吧飘屏：分时图里也要有人说话（2026-10-20 与日K图共用 DanmuLayer） -->
      <DanmuLayer :posts="danmu" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import {
  GridComponent,
  TooltipComponent,
  MarkLineComponent,
  MarkPointComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { token } from '@/styles/theme'
import SvgIcon from './SvgIcon.vue'
import DanmuLayer from './DanmuLayer.vue'

// 按需注册 ECharts 模块（vue-echarts 要求显式 use）
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, MarkLineComponent, MarkPointComponent])

const props = defineProps({
  // 固定 90 槽分时：[{ time, price|null }]，未到槽位 price 为 null（断线不画、尾段留白）
  points: { type: Array, default: () => [] },
  // 昨收基准价：双轴对齐、昨收虚线、涨跌幅换算共用
  prevClose: { type: Number, default: 0 },
  // 涨跌停价：后端按本局模式算好下发（标准 ±10%/ST ±5%，快速 ±20%/ST ±10%），前端不做任何百分比换算
  limitUp: { type: Number, default: 0 },
  limitDown: { type: Number, default: 0 },
  // 收盘/结算/终局态：显示"已收盘"角标
  closed: { type: Boolean, default: false },
  // 股吧帖子流（分时图里飘的弹幕）：本票专属 + 不点名的大盘闲聊
  danmu: { type: Array, default: () => [] },
  // 本局每跳秒数（空态提示语用，来自后端模式档案）
  tickSeconds: { type: Number, default: 1 }
})

const hasData = computed(() => props.points.some((p) => p.price != null))

// 最新有效点（高亮 + 价格标注）
const lastPoint = computed(() => {
  for (let i = props.points.length - 1; i >= 0; i--) {
    if (props.points[i].price != null) return { index: i, price: props.points[i].price }
  }
  return null
})

// X 轴刻度只标关键时点：午休断轴处 11:30/13:00 合并显示（同花顺惯例）
const KEY_SLOTS = [0, 22, 44, 67, 89]
function axisLabelFormatter(value, index) {
  if (index === 44) return '11:30/13:00'
  return value
}

const option = computed(() => {
  const cBlue = token('--blue')
  const cYellow = token('--yellow')
  const cInk = token('--ink')
  const cInk2 = token('--ink-2')
  const cInk3 = token('--ink-3')
  const cCard = token('--card')
  const cDash = token('--dash')
  const cUp = token('--up')
  const cDown = token('--down')

  const times = props.points.map((p) => p.time)
  const prices = props.points.map((p) => p.price)
  // 均价线：逐点累计平均（无成交量口径，等权均价近似）
  const avg = []
  let sum = 0
  let count = 0
  for (const p of props.points) {
    if (p.price != null) {
      sum += p.price
      count++
      avg.push(+(sum / count).toFixed(3))
    } else {
      avg.push(null)
    }
  }

  const base = props.prevClose > 0 ? props.prevClose : lastPoint.value?.price || 1
  // 双轴对称范围：按当日真实振幅自适应，留 15% 白；最小 ±0.8% 防止横盘日把噪声拉成
  // 巨浪。不要把涨跌停价算进来——那会让纵轴永远按 ±10%（快速 ±20%）铺满，盘中 1%
  // 的波动看起来就是一条直线（玩家反馈"开局动一下，中间几乎平盘"的视觉主因）。
  let dev = base * 0.008
  for (const p of props.points) {
    if (p.price != null) dev = Math.max(dev, Math.abs(p.price - base))
  }
  if (avg.length) {
    const lastAvg = [...avg].reverse().find((v) => v != null)
    if (lastAvg != null) dev = Math.max(dev, Math.abs(lastAvg - base))
  }
  dev *= 1.15
  let yMin = base - dev
  let yMax = base + dev
  // 涨跌停价是硬边界（2026-10-20）：顶到涨停时，纵轴必须停在涨停价上，
  // 否则"10% 的板"被 15% 的留白顶到 11.5%，板就贴不到顶（玩家反馈"涨停时上面多显示
  // 一点点空白，很难看"）。留 0.1% 的余量给涨跌停线的标签，其余空间还给真实振幅。
  const EDGE = 0.001
  if (props.limitUp > 0 && yMax > props.limitUp) {
    yMax = Math.min(yMax, props.limitUp * (1 + EDGE))
  }
  if (props.limitDown > 0 && yMin < props.limitDown) {
    yMin = Math.max(yMin, props.limitDown * (1 - EDGE))
  }
  // 右轴涨跌幅必须跟着夹紧后的左轴走（两根轴共用同一把尺子，否则百分比会与价格对不上）
  const pctMin = ((yMin - base) / base) * 100
  const pctMax = ((yMax - base) / base) * 100

  const last = lastPoint.value
  const lastPct = last ? ((last.price - base) / base) * 100 : 0

  // 只吃数字：ECharts 会把散布点/特殊系列的 value 传成数组，直接算术会抛异常并卡死 tooltip
  const tooltipFormatter = (params) => {
    const rows = [`<b>${params[0] ? params[0].axisValue : ''}</b>`]
    for (const item of params) {
      const value = typeof item.value === 'number' ? item.value : null
      if (value == null) continue
      const pct = ((value - base) / base) * 100
      const cls = pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat'
      rows.push(
        `${item.marker}${item.seriesName} <span class="num ${cls}">${value.toFixed(2)}（${pct > 0 ? '+' : ''}${pct.toFixed(2)}%）</span>`
      )
    }
    return rows.join('<br/>')
  }

  return {
    animation: false,
    grid: { left: 56, right: 56, top: 24, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', label: { backgroundColor: cInk } },
      backgroundColor: cCard,
      borderColor: cInk,
      borderWidth: 2,
      padding: [6, 10],
      textStyle: { color: cInk, fontSize: 12 },
      formatter: tooltipFormatter
    },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: false,
      axisLine: { lineStyle: { color: cInk, width: 1.5 } },
      axisTick: { show: false },
      axisLabel: {
        color: cInk2,
        fontSize: 11,
        interval: (index) => KEY_SLOTS.includes(index),
        formatter: axisLabelFormatter
      },
      splitLine: { show: false }
    },
    yAxis: [
      {
        type: 'value',
        min: yMin,
        max: yMax,
        axisLabel: {
          color: cInk2,
          fontSize: 11,
          formatter: (v) => v.toFixed(2)
        },
        splitLine: { lineStyle: { color: cDash, type: 'dashed' } }
      },
      {
        type: 'value',
        min: pctMin,
        max: pctMax,
        position: 'right',
        axisLabel: {
          color: cInk2,
          fontSize: 11,
          formatter: (v) => (v > 0 ? '+' : '') + v.toFixed(2) + '%'
        },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '价格',
        type: 'line',
        data: prices,
        connectNulls: false,
        showSymbol: false,
        lineStyle: { width: 1.8, color: cBlue },
        areaStyle: { color: cBlue, opacity: 0.08 },
        // 最新点高亮 + 价格标注：用 markPoint 而非独立系列——独立系列会把数组值带进
        // tooltip params（曾导致 formatter 抛异常、ECharts 主流程卡死、曲线停更）
        ...(last
          ? {
              markPoint: {
                silent: true,
                symbol: 'circle',
                symbolSize: 8,
                itemStyle: { color: lastPct >= 0 ? cUp : cDown, borderColor: cInk, borderWidth: 1.5 },
                label: {
                  show: true,
                  position: lastPct >= 0 ? 'top' : 'bottom',
                  fontSize: 11,
                  fontWeight: 700,
                  color: lastPct >= 0 ? cUp : cDown,
                  formatter: () => last.price.toFixed(2)
                },
                data: [{ coord: [times[last.index], last.price] }]
              }
            }
          : {}),
        markLine: {
          silent: true,
          symbol: 'none',
          label: { show: true, fontSize: 10, color: cInk2, position: 'insideStartTop' },
          lineStyle: { type: 'dashed', width: 1.2 },
          data: [
            { yAxis: base, lineStyle: { color: cInk3 }, label: { formatter: '昨收 ' + base.toFixed(2) } },
            // 午休断轴分隔线（11:30 与 13:00 之间留白）
            { xAxis: times[44], lineStyle: { color: cDash }, label: { show: false } },
            // 涨跌停线只在进入当前视野时画；平时把纵轴还给真实振幅
            ...(props.limitUp > 0 && props.limitUp <= yMax
              ? [{ yAxis: props.limitUp, lineStyle: { color: cUp }, label: { formatter: '涨停 ' + props.limitUp.toFixed(2), color: cUp } }]
              : []),
            ...(props.limitDown > 0 && props.limitDown >= yMin
              ? [{ yAxis: props.limitDown, lineStyle: { color: cDown }, label: { formatter: '跌停 ' + props.limitDown.toFixed(2), color: cDown } }]
              : [])
          ]
        }
      },
      {
        name: '均价',
        type: 'line',
        data: avg,
        connectNulls: false,
        showSymbol: false,
        lineStyle: { width: 1.6, color: cYellow }
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
.lg-price { background: var(--blue); }
.lg-avg { background: var(--yellow); }
.closed-badge { margin-left: 4px; }
.chart { width: 100%; height: 320px; }
.chart-wrap { position: relative; width: 100%; }
.chart-empty { height: 320px; }
.chart-mascot { width: 56px; height: 56px; opacity: 0.8; }
</style>
