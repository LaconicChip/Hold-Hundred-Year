<template>
  <div class="result-page" :class="{ bankrupt: isBankrupt }">
    <div v-if="loading" class="card state-card">结算数据生成中…</div>
    <div v-else-if="!result" class="card state-card">终局数据尚未生成</div>

    <template v-else>
      <!-- 通关：大红奖状 -->
      <section v-if="isFinished" class="cert">
        <div class="cert-inner">
          <h1 class="cert-title">通关奖状</h1>
          <p class="cert-body">
            兹证明 股民<b>{{ user.username || '无名氏' }}</b>
            历时 <span class="num">{{ result.endDay || 108 }}</span> 个交易日，
            总收益率 <span class="num up">{{ fmtPct(result.totalReturnPct) }}</span>、
            百年指数涨幅 <span class="num" :class="pctCls(result.indexGainPct)">{{ fmtPct(result.indexGainPct) }}</span>、
            最终段位<b>{{ result.finalTierName || '散户' }}</b>，特发此状，以资鼓励。
          </p>
          <p class="cert-sign">百年证券营业部</p>
          <div class="cert-seal">
            <SealStamp text="百年证券" />
          </div>
        </div>
      </section>

      <!-- 爆仓：黑白漫画分镜 -->
      <section v-else class="comic">
        <div class="comic-head">
          <h1 class="comic-title">天台见</h1>
          <p class="comic-sub">
            负债 <span class="num">{{ fmtMoney(debtOf) }}</span>
            ｜ 存活 <span class="num">{{ result.endDay || '—' }}</span> 个交易日
          </p>
        </div>
        <div class="panels">
          <div class="panel">
            <span class="panel-no">壹</span>
            <p>曾经也赚过</p>
          </div>
          <div class="panel">
            <span class="panel-no">贰</span>
            <p>杠杆加满</p>
          </div>
          <div class="panel">
            <span class="panel-no">叁</span>
            <p>天台排队中</p>
          </div>
        </div>
      </section>

      <!-- 108 天净资产 vs 指数曲线 -->
      <section class="card block-card">
        <div class="card-title">全程战绩：净资产 vs 百年指数</div>
        <div v-if="!curve.length" class="block-empty">暂无曲线数据</div>
        <VChart v-else class="curve" :option="curveOption" :autoresize="true" />
      </section>

      <!-- 最终排行榜 -->
      <section class="card block-card">
        <div class="card-title">最终排行榜</div>
        <div v-if="!rankings.length" class="block-empty">尚无排行数据</div>
        <template v-else>
          <div
            v-for="(r, i) in rankings"
            :key="r.name + i"
            class="rank-row"
            :class="{ player: r.isPlayer }"
          >
            <span class="rank-no" :class="{ top: i < 3 && !isGone(r) }">
              {{ isGone(r) ? '—' : i + 1 }}
            </span>
            <span class="rank-name">
              <span v-if="r.isPlayer" class="avatar you">您</span>
              <NpcAvatar
                v-else
                :npc="npcCodeOf(r.name)"
                :size="32"
                :class="{ dimmed: isGone(r) }"
              />
              <span :class="{ 'player-name': r.isPlayer }">{{ r.name }}</span>
              <span v-if="r.status && !isGone(r)" class="tag-style">{{ r.status }}</span>
            </span>
            <span v-if="isGone(r)" class="gone">{{ r.status }}</span>
            <b v-else class="num">{{ fmtMoney(r.asset) }}</b>
          </div>
        </template>
      </section>

      <!-- 强平记录（非空才显示） -->
      <section v-if="liquidations.length" class="card block-card">
        <div class="card-title">强平记录</div>
        <div v-for="(l, i) in liquidations" :key="i" class="liq-row">
          {{ liqText(l) }}
        </div>
      </section>

      <!-- 种子号 + 再来一局 -->
      <section class="card block-card seed-card">
        <span class="seed-lab">本局种子号</span>
        <span class="num seed-val">{{ result.worldSeed || '—' }}</span>
      </section>

      <div class="actions">
        <button class="btn btn-primary btn-lg" :disabled="restarting" @click="onRestart">
          <SvgIcon name="play" />{{ restarting ? '播种中…' : '再来一局' }}
        </button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchGameResult, restartGame } from '@/api/game'
import { useUserStore } from '@/stores/user'
import NpcAvatar from '@/components/NpcAvatar.vue'
import SealStamp from '@/components/SealStamp.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { resolveNpcCode } from '@/constants/messageMeta'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'

// 按需注册 ECharts 模块（与 Assets 页一致）
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

const router = useRouter()
const user = useUserStore()

const result = ref(null)
const loading = ref(false)
const restarting = ref(false)

// 结局判定：endReason 或 ending 包含关键字
const isBankrupt = computed(() => {
  const r = result.value
  if (!r) return false
  return /BANKRUPT/i.test(`${r.endReason || ''}|${r.ending || ''}`)
})
const isFinished = computed(() => {
  const r = result.value
  if (!r) return false
  if (isBankrupt.value) return false
  return /FINISH/i.test(`${r.endReason || ''}|${r.ending || ''}`)
})

const curve = computed(() => (result.value && result.value.curve) || [])
const rankings = computed(() => (result.value && result.value.rankings) || [])
const liquidations = computed(() => (result.value && result.value.liquidations) || [])

// 爆仓页的负债：曲线末端净资产为负则取其绝对值，否则取最后一条强平记录兜底
const debtOf = computed(() => {
  const r = result.value
  if (!r) return 0
  if (r.debt != null) return r.debt
  const last = curve.value[curve.value.length - 1]
  if (last && last.netAsset < 0) return -last.netAsset
  return 0
})

// 曲线配置（仿 Assets 页：净资产红实线左轴 / 指数墨灰虚线右轴，仅令牌色）
const curveOption = computed(() => {
  const days = curve.value.map((p) => p.gameDay)
  const net = curve.value.map((p) => +(+p.netAsset).toFixed(2))
  const idx = curve.value.map((p) => +(+p.indexValue).toFixed(2))
  return {
    color: ['#ff2436', '#6f675c'],
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

function npcCodeOf(name) {
  return resolveNpcCode(name || '') || 'SYSTEM'
}
function isGone(r) {
  return /天台|销户|爆仓/.test(r.status || '')
}
function pctCls(v) {
  const n = +v
  return n > 0 ? 'up' : n < 0 ? 'down' : 'flat'
}
function fmtPct(v) {
  const n = +v
  if (!isFinite(n)) return '--'
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}
function fmtMoney(v) {
  const n = +v
  if (!isFinite(n)) return '--'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
// 强平记录字段不定，容错拼接展示
function liqText(l) {
  if (l == null) return ''
  if (typeof l === 'string') return l
  const parts = []
  if (l.gameDay != null) parts.push(`第 ${l.gameDay} 天`)
  if (l.name || l.stockName) parts.push(l.name || l.stockName)
  if (l.reason || l.detail || l.desc) parts.push(l.reason || l.detail || l.desc)
  return parts.length ? parts.join(' · ') : JSON.stringify(l)
}

async function load() {
  loading.value = true
  try {
    result.value = await fetchGameResult()
  } catch {
    result.value = null
  } finally {
    loading.value = false
  }
}

async function onRestart() {
  if (restarting.value) return
  try {
    await ElMessageBox.confirm('重新播种一个全新世界，本局数据将封存，确定再来一局？', '再来一局', {
      type: 'warning',
      confirmButtonText: '再来一局',
      cancelButtonText: '再想想'
    })
  } catch {
    return // 用户取消
  }
  restarting.value = true
  try {
    await restartGame()
    ElMessage.success('新世界已播种')
    router.push('/market')
  } catch (e) {
    ElMessage.error(e?.message || '重开失败')
  } finally {
    restarting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.result-page {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 20px 80px;
}
.state-card {
  color: var(--ink-3);
  font-size: 14px;
  padding: 40px 10px;
  text-align: center;
}
/* ---------- 通关奖状 ---------- */
.cert {
  background: #fbf3dd;
  border: 4px double var(--seal);
  border-radius: 10px;
  box-shadow: var(--shadow);
  padding: 14px;
}
.cert-inner {
  position: relative;
  border: 2px solid var(--seal);
  border-radius: 6px;
  padding: 30px 26px 26px;
  text-align: center;
}
.cert-title {
  font-family: var(--font-fun);
  font-size: 40px;
  color: var(--seal);
  letter-spacing: 8px;
}
.cert-body {
  margin-top: 18px;
  font-size: 17px;
  line-height: 2;
  text-align: left;
}
.cert-body b {
  color: var(--seal);
  padding: 0 2px;
}
.cert-sign {
  margin-top: 16px;
  text-align: right;
  font-family: var(--font-fun);
  font-size: 16px;
  color: var(--ink-2);
}
.cert-seal {
  position: absolute;
  right: 18px;
  bottom: 14px;
}
.cert-seal :deep(.seal) {
  width: 72px;
  height: 72px;
  font-size: 14px;
}
/* ---------- 爆仓黑白漫画 ---------- */
.bankrupt {
  filter: grayscale(1);
}
.comic {
  animation: shake 0.4s ease-out 1;
}
@keyframes shake {
  0% { transform: translate(0, 0) rotate(0deg); }
  20% { transform: translate(-6px, 3px) rotate(-1deg); }
  40% { transform: translate(5px, -3px) rotate(1deg); }
  60% { transform: translate(-4px, -2px) rotate(-0.6deg); }
  80% { transform: translate(3px, 2px) rotate(0.6deg); }
  100% { transform: translate(0, 0) rotate(0deg); }
}
.comic-head {
  background: var(--card);
  border: 4px solid var(--ink);
  border-radius: 12px;
  box-shadow: 4px 4px 0 var(--ink);
  padding: 22px;
  text-align: center;
}
.comic-title {
  font-family: var(--font-fun);
  font-size: 52px;
  letter-spacing: 10px;
}
.comic-sub {
  margin-top: 8px;
  color: var(--ink-2);
  font-size: 16px;
}
.panels {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.panel {
  position: relative;
  background: var(--card);
  border: 3px solid var(--ink);
  border-radius: 4px;
  box-shadow: 3px 3px 0 var(--ink);
  min-height: 110px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px 10px;
}
.panel p {
  font-family: var(--font-fun);
  font-size: 20px;
  text-align: center;
}
.panel-no {
  position: absolute;
  top: 6px;
  left: 8px;
  font-family: var(--font-fun);
  font-size: 13px;
  color: var(--ink-2);
  border: 1.5px solid var(--ink);
  border-radius: 50%;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
}
/* ---------- 通用区块 ---------- */
.block-card {
  margin-top: 16px;
}
.card-title {
  font-family: var(--font-fun);
  font-size: 19px;
  margin-bottom: 10px;
}
.block-empty {
  color: var(--ink-3);
  font-size: 14px;
  padding: 18px 4px;
  text-align: center;
}
.curve {
  width: 100%;
  height: 340px;
}
/* 排行榜行（与 Leaderboard 页一致） */
.rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px dashed #d8cfbc;
}
.rank-row:last-child {
  border-bottom: none;
}
.rank-row.player {
  background: #ffedb0;
  border-radius: 8px;
  font-weight: 700;
}
.rank-no {
  font-family: var(--font-fun);
  font-size: 18px;
  width: 34px;
  text-align: center;
}
.rank-no.top {
  color: var(--brand);
}
.rank-name {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.player-name {
  font-weight: 700;
}
.avatar {
  width: 32px;
  height: 32px;
  min-width: 32px;
  border: 2px solid var(--ink);
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-fun);
  font-size: 14px;
  color: #fff;
}
.avatar.you {
  background: var(--brand);
}
.dimmed {
  opacity: 0.45;
}
.tag-style {
  font-size: 11px;
  color: var(--ink-2);
  border: 1px dashed var(--ink-3);
  border-radius: 6px;
  padding: 0 6px;
  white-space: nowrap;
}
.gone {
  color: var(--ink-3);
  font-size: 12px;
  white-space: nowrap;
}
/* 强平记录 */
.liq-row {
  padding: 7px 4px;
  border-bottom: 1px dashed #d8cfbc;
  font-size: 14px;
  line-height: 1.6;
}
.liq-row:last-child {
  border-bottom: none;
}
/* 种子号 */
.seed-card {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.seed-lab {
  font-family: var(--font-fun);
  font-size: 15px;
  color: var(--ink-2);
}
.seed-val {
  font-size: 16px;
  word-break: break-all;
}
/* 再来一局 */
.actions {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}
</style>
