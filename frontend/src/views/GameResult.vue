<template>
  <div class="page" :class="{ bankrupt: isBankrupt }">
    <!-- 页头 -->
    <header class="page-head">
      <div>
        <h1 class="page-title">
          <SvgIcon name="cert" class="title-ic" />终局结算
        </h1>
        <p class="page-desc">一百年过去了，这是你的成绩单</p>
      </div>
    </header>

    <div v-if="loading" class="card state-card">结算数据生成中…</div>
    <div v-else-if="!result" class="empty state-card">
      <SvgIcon name="bear" class="state-mascot" />
      <span class="empty-title">终局数据尚未生成</span>
      <span class="empty-desc">爆仓或走完 {{ market.totalDays }} 天，这里才会出结果</span>
      <button class="btn btn-sm" @click="goMarket">回大盘</button>
    </div>

    <template v-else>
      <!-- 通关：大红奖状 -->
      <section v-if="isFinished" class="cert">
        <div class="cert-inner">
          <h2 class="cert-title">通关奖状</h2>
          <p class="cert-body">
            兹证明 股民<b>{{ user.username || '无名氏' }}</b>
            历时 <span class="num">{{ result.endDay || market.totalDays }}</span> 个交易日，
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
        <img :src="rooftopImg" class="comic-banner" alt="天台黑白漫画背景" />
        <div class="comic-head">
          <h2 class="comic-title">天台见</h2>
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

      <!-- 全剧净资产 vs 指数曲线 -->
      <section class="card block-card">
        <div class="sec-head">
          <span class="card-title">
            <SvgIcon name="chart" class="title-ic-sm" />全程战绩：净资产 vs 百年指数
          </span>
        </div>
        <div v-if="!curve.length" class="empty block-empty">
          <span class="empty-title">暂无曲线数据</span>
        </div>
        <VChart v-else class="curve" :option="curveOption" :autoresize="true" />
      </section>

      <!-- 最终排行榜 -->
      <section class="card block-card">
        <div class="sec-head">
          <span class="card-title">
            <SvgIcon name="trophy" class="title-ic-sm" />最终排行榜
          </span>
        </div>
        <div v-if="!rankings.length" class="empty block-empty">
          <span class="empty-title">尚无排行数据</span>
        </div>
        <template v-else>
          <div
            v-for="(r, i) in rankings"
            :key="r.name + i"
            class="rank-row"
            :class="{ player: r.isPlayer, gone: isGone(r) }"
          >
            <span class="rank-no" :class="{ top: i < 3 && !isGone(r) }">
              <template v-if="isGone(r)">—</template>
              <template v-else-if="i === 0">冠</template>
              <template v-else-if="i === 1">亚</template>
              <template v-else-if="i === 2">季</template>
              <template v-else>{{ i + 1 }}</template>
            </span>
            <span class="rank-name">
              <span v-if="r.isPlayer" class="avatar you">您</span>
              <NpcAvatar
                v-else
                :npc="npcCodeOf(r.name)"
                :size="32"
                :class="{ dimmed: isGone(r) }"
              />
              <span class="rank-label" :class="{ 'player-name': r.isPlayer }">{{ r.name }}</span>
              <span v-if="statusText(r)" class="tag" :class="{ 'tag-danger': isGone(r) }">
                {{ statusText(r) }}
              </span>
            </span>
            <span v-if="isGone(r)" class="gone">{{ r.status }}</span>
            <b v-else class="num asset">{{ fmtMoney(r.asset) }}</b>
          </div>
        </template>
      </section>

      <!-- 强平记录（非空才显示） -->
      <section v-if="liquidations.length" class="card block-card">
        <div class="sec-head">
          <span class="card-title">
            <SvgIcon name="alert" class="title-ic-sm" />强平记录
          </span>
          <span class="tag">{{ liquidations.length }} 次</span>
        </div>
        <div v-for="(l, i) in liquidations" :key="i" class="liq-row">
          <SvgIcon name="alert" class="liq-ic" />
          <span>{{ liqText(l) }}</span>
        </div>
      </section>

      <!-- 种子号 + 再来一局 -->
      <section class="card block-card seed-card">
        <div class="seed-block">
          <span class="seed-lab">本局种子号</span>
          <span class="num seed-val">{{ result.worldSeed || '—' }}</span>
        </div>
        <div class="reroll">
          <!-- 模式只在下一局的第 1 天页面选（未开盘时状态栏给选择器）：
               这里再放一个选择器等于同一件事问两遍，点了也不算数，故删除 -->
          <button class="btn btn-primary btn-lg" :disabled="restarting" @click="onRestart">
            <SvgIcon name="play" />{{ restarting ? '播种中…' : '再来一局' }}
          </button>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { fetchGameResult, restartGame } from '@/api/game'
import { useMarketStore } from '@/stores/market'
import { useUserStore } from '@/stores/user'
import { token } from '@/styles/theme'
import NpcAvatar from '@/components/NpcAvatar.vue'
import SealStamp from '@/components/SealStamp.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { resolveNpcCode } from '@/constants/messageMeta'
import certBg from '@/assets/img/certificate.png'
import rooftopImg from '@/assets/img/rooftop.png'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { fmtPctDash as fmtPct, fmtMoneyDash as fmtMoney } from '@/utils/format'

// 按需注册 ECharts 模块（与 Assets 页一致）
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

const router = useRouter()
const user = useUserStore()
const market = useMarketStore()

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

// 曲线配置（净资产红实线左轴 / 指数墨灰虚线右轴，配色取令牌）
const curveOption = computed(() => {
  const cBrand = token('--brand')
  const cInk2 = token('--ink-2')
  const cInk = token('--ink')
  const cCard = token('--card')
  const cDash = token('--dash')

  const days = curve.value.map((p) => p.gameDay)
  const net = curve.value.map((p) => +(+p.netAsset).toFixed(2))
  const idx = curve.value.map((p) => +(+p.indexValue).toFixed(2))
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

function npcCodeOf(name) {
  return resolveNpcCode(name || '') || 'SYSTEM'
}
function isGone(r) {
  return /天台|销户|爆仓/.test(r.status || '')
}
// 状态本地化：ACTIVE 是常态不展示
function statusText(r) {
  const s = r.status || ''
  if (/天台|销户|爆仓/.test(s)) return s
  if (/^ACTIVE$/i.test(s)) return ''
  return s || ''
}
function pctCls(v) {
  const n = +v
  return n > 0 ? 'up' : n < 0 ? 'down' : 'flat'
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

function goMarket() {
  router.push('/market')
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
    ElMessage.error({ message: e?.message || '重开失败', grouping: true })
  } finally {
    restarting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.reroll {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  max-width: 860px;
}
.title-ic { width: 0.85em; height: 0.85em; color: var(--brand); }
.title-ic-sm { width: 1em; height: 1em; color: var(--brand); }
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
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
.state-card { padding: var(--sp-8) var(--sp-4); }
.state-mascot { width: 44px; height: 44px; opacity: 0.8; }

/* ---------- 通关奖状 ---------- */
.cert {
  background-image: v-bind('`url(${certBg})`');
  background-size: 100% 100%;
  background-repeat: no-repeat;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow);
  padding: var(--sp-6);
}
.cert-inner {
  position: relative;
  border-radius: var(--radius-sm);
  padding: var(--sp-7) var(--sp-6) var(--sp-6);
  text-align: center;
  /* 半透明米白底，保证文字落在奖状留白区仍清晰可读 */
  background: rgba(255, 253, 247, 0.72);
}
.cert-title {
  font-family: var(--font-fun);
  font-size: 40px;
  color: var(--seal);
  letter-spacing: 8px;
  margin: 0;
}
.cert-title::before { display: none; }
.cert-body {
  margin-top: var(--sp-5);
  font-size: var(--fs-lg);
  line-height: 2;
  text-align: left;
}
.cert-body b { color: var(--seal); padding: 0 2px; }
.cert-sign {
  margin-top: var(--sp-4);
  text-align: right;
  font-family: var(--font-fun);
  font-size: var(--fs-md);
  color: var(--ink-2);
}
.cert-seal { position: absolute; right: var(--sp-4); bottom: var(--sp-3); }
.cert-seal :deep(.seal) { width: 72px; height: 72px; font-size: var(--fs-base); }

/* ---------- 爆仓黑白漫画 ---------- */
.bankrupt { filter: grayscale(1); }
.comic { animation: shake 0.4s ease-out 1; }
@keyframes shake {
  0% { transform: translate(0, 0) rotate(0deg); }
  20% { transform: translate(-6px, 3px) rotate(-1deg); }
  40% { transform: translate(5px, -3px) rotate(1deg); }
  60% { transform: translate(-4px, -2px) rotate(-0.6deg); }
  80% { transform: translate(3px, 2px) rotate(0.6deg); }
  100% { transform: translate(0, 0) rotate(0deg); }
}
.comic-banner {
  width: 100%;
  height: 180px;
  object-fit: cover;
  display: block;
  border: 3px solid var(--ink);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow);
  margin-bottom: var(--sp-3);
}
.comic-head {
  background: var(--card);
  border: 4px solid var(--ink);
  border-radius: var(--radius-md);
  box-shadow: 4px 4px 0 var(--ink);
  padding: var(--sp-5);
  text-align: center;
}
.comic-title {
  font-family: var(--font-fun);
  font-size: 52px;
  letter-spacing: 10px;
  margin: 0;
}
.comic-title::before { display: none; }
.comic-sub {
  margin-top: var(--sp-2);
  color: var(--ink-2);
  font-size: var(--fs-md);
}
.panels {
  margin-top: var(--sp-3);
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-3);
}
.panel {
  position: relative;
  background: var(--card);
  border: 3px solid var(--ink);
  border-radius: 4px;
  box-shadow: var(--shadow);
  min-height: 110px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4) var(--sp-3);
}
.panel p {
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
  text-align: center;
}
.panel-no {
  position: absolute;
  top: 6px;
  left: var(--sp-2);
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  border: var(--border-thin);
  border-radius: 50%;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ---------- 通用区块 ---------- */
.block-card { padding: var(--sp-3) var(--sp-4); }
.block-empty { padding: var(--sp-6) var(--sp-4); }
.curve { width: 100%; height: 320px; }

/* 排行榜行 */
.rank-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2);
  border-bottom: 1px dashed var(--dash);
  border-left: 3px solid transparent;
}
.rank-row:last-child { border-bottom: none; }
.rank-row.player {
  background: var(--player-row);
  border-left-color: var(--brand);
  border-radius: var(--radius-sm);
  font-weight: 700;
}
.rank-row.gone { opacity: 0.55; }
.rank-no {
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
  width: 34px;
  text-align: center;
  flex: none;
}
.rank-no.top { color: var(--brand); }
.rank-name {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}
.rank-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.player-name { font-weight: 700; }
.avatar { width: 32px; height: 32px; min-width: 32px; flex: none; }
.avatar.you { background: var(--brand); }
.dimmed { opacity: 0.45; }
.tag-danger { color: var(--up); border-color: var(--up); }
.gone { color: var(--ink-3); font-size: var(--fs-sm); white-space: nowrap; }
.asset { font-size: var(--fs-base); white-space: nowrap; }

/* 强平记录 */
.liq-row {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-1);
  border-bottom: 1px dashed var(--dash);
  font-size: var(--fs-base);
  line-height: 1.6;
}
.liq-row:last-child { border-bottom: none; }
.liq-ic { width: 1.1em; height: 1.1em; margin-top: 4px; color: var(--up); flex: none; }

/* 种子号 + 再来一局 */
.seed-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.seed-block { display: flex; align-items: baseline; gap: var(--sp-3); }
.seed-lab {
  font-family: var(--font-fun);
  font-size: var(--fs-md);
  color: var(--ink-2);
}
.seed-val { font-size: var(--fs-md); word-break: break-all; }
</style>
