<template>
  <div class="statusbar card">
    <!-- 交易日提示（快乐体粗字） -->
    <b class="day-num">{{ dayText }}</b>

    <!-- 纪元旗标：bull/bear/shock 决定底色与图标 -->
    <span class="era-flag" :class="eraCls">
      <SvgIcon :name="eraIcon" class="era-ic" />
      {{ market.eraName || '平稳年代' }}
    </span>

    <!-- 段位徽章（tier 可能为空，默认 RETAIL） -->
    <TierBadge :tier="tier" />

    <!-- 百年指数 + 指数涨跌 -->
    <span class="index-box">
      <span class="index-label">百年指数</span>
      <span class="num index-val">{{ indexText }}</span>
      <span v-if="indexChg !== 0" class="num index-chg" :class="indexChgCls">{{ indexChgText }}</span>
    </span>

    <!-- 阶段与倒计时 -->
    <span class="phase">
      <template v-if="market.isOpen">
        {{ phaseLabel }}<span class="num countdown">{{ countdownText }}</span>
      </template>
      <template v-else-if="market.marketState === 'LUNCH_BREAK'">
        午休中（不可交易）
      </template>
      <template v-else>{{ phaseLabel }}</template>
    </span>

    <!-- 操作区 -->
    <span class="ops">
      <button
        v-if="showStart"
        class="btn btn-primary"
        :disabled="market.loading"
        @click="onStart"
      >
        <SvgIcon name="play" />
        {{ startText }}
      </button>

      <button
        v-if="showSkip"
        class="btn"
        :disabled="market.loading"
        @click="onSkip"
      >
        <SvgIcon name="skip" />
        {{ skipText }}
      </button>

      <button
        v-if="market.isEnded"
        class="btn btn-primary"
        @click="onViewResult"
      >
        查看终局结算
      </button>

      <!-- 铃铛：未读数角标，>0 才显示 -->
      <span class="bell-wrap" @click="onBell">
        <SvgIcon name="bell" class="bell-ic" />
        <span v-if="unread > 0" class="cnt">{{ unread > 99 ? '99+' : unread }}</span>
      </span>
    </span>
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useMarketStore } from '@/stores/market'
import { useQuotesStore } from '@/stores/quotes'
import { useMessagesStore } from '@/stores/messages'
import SvgIcon from './SvgIcon.vue'
import TierBadge from './TierBadge.vue'

const emit = defineEmits(['skipped'])
const router = useRouter()
const market = useMarketStore()
const quotes = useQuotesStore()
const messages = useMessagesStore()

// 阶段中文映射
const PHASE_MAP = {
  NOT_STARTED: '未开盘',
  MORNING_OPEN: '上午盘',
  LUNCH_BREAK: '午休',
  AFTERNOON_OPEN: '下午盘',
  SETTLING: '结算中',
  CLOSED: '已收盘',
  BANKRUPT: '爆仓',
  FINISHED: '已通关'
}
const phaseLabel = computed(() => PHASE_MAP[market.marketState] || market.marketState)

const dayText = computed(() => `第 ${market.currentDay} 个交易日 · 第 ${market.year} 年`)

// 纪元旗标：market.eraTone getter -> bull/bear/shock
const ERA_TONE = {
  bull: { cls: 'era-bull', icon: 'bull' },
  bear: { cls: 'era-bear', icon: 'bear' },
  shock: { cls: 'era-shock', icon: 'alert' }
}
const eraCls = computed(() => (ERA_TONE[market.eraTone] || ERA_TONE.shock).cls)
const eraIcon = computed(() => (ERA_TONE[market.eraTone] || ERA_TONE.shock).icon)

const tier = computed(() => market.tier || 'RETAIL')

// 指数展示
const indexText = computed(() => (quotes.indexValue ? quotes.indexValue.toFixed(2) : '—'))
const indexChg = computed(() => quotes.indexChangePct)
const indexChgCls = computed(() => (indexChg.value > 0 ? 'up' : indexChg.value < 0 ? 'down' : 'flat'))
const indexChgText = computed(() => {
  const v = indexChg.value
  if (!v) return ''
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
})

// 未读角标
const unread = computed(() => messages.unreadCount)

// 开始按钮（未开盘 / 已收盘）
const showStart = computed(() => market.marketState === 'NOT_STARTED' || market.marketState === 'CLOSED')
const showSkip = computed(() => !market.isEnded)
const startText = computed(() =>
  market.marketState === 'NOT_STARTED' ? '开始第 1 天' : '开始下一交易日'
)
// 跳过文案：盘中（含午休）为“跳过当日”，否则“跳过下一交易日”
const DURING_DAY = ['MORNING_OPEN', 'LUNCH_BREAK', 'AFTERNOON_OPEN']
const skipText = computed(() =>
  DURING_DAY.includes(market.marketState) ? '跳过当日' : '跳过下一交易日'
)

// ---- 倒计时（本地每秒递减，fetchState / WS 快照刷新时校正）----
const remaining = ref(0)
let timer = null
function fmt(s) {
  const m = Math.floor(s / 60)
  const sec = s % 60
  return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0')
}
const countdownText = computed(() => fmt(remaining.value))

function recalibrate() {
  if (market.isOpen) remaining.value = (market.countdownTicks || 0) * 3
}
watch(() => market.countdownTicks, recalibrate)
watch(() => market.isOpen, recalibrate)

onMounted(() => {
  recalibrate()
  timer = setInterval(() => {
    if (market.isOpen && remaining.value > 0) remaining.value--
  }, 1000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

// ---- 操作 ----
async function onStart() {
  if (market.loading) return
  try {
    await market.start()
  } catch (e) {
    ElMessage.error(e?.message || '开始失败')
  }
}

async function onSkip() {
  if (market.loading) return
  try {
    await ElMessageBox.confirm('快进后直接收盘结算，期间无法交易，确定跳过？', '跳过确认', {
      type: 'warning',
      confirmButtonText: '确定跳过',
      cancelButtonText: '再想想'
    })
  } catch {
    return // 用户取消
  }
  try {
    const summary = await market.skip() // DailySummaryDTO
    emit('skipped', summary) // 由 Market.vue 跳转收盘晚报页
  } catch (e) {
    ElMessage.error(e?.message || '跳过失败')
  }
}

function onBell() {
  router.push('/messages')
}

function onViewResult() {
  router.push('/result')
}
</script>

<style scoped>
.statusbar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px;
}
.day-num {
  font-family: var(--font-fun);
  font-size: 18px;
  font-weight: 700;
  white-space: nowrap;
}
.index-box {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}
.index-label {
  font-size: 13px;
  color: var(--ink-2);
}
.index-val {
  font-size: 15px;
}
.index-chg {
  font-size: 15px;
}
.phase {
  color: var(--ink-2);
  white-space: nowrap;
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}
.countdown {
  color: var(--ink);
}
.ops {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}
/* 铃铛与角标 */
.bell-wrap {
  position: relative;
  font-size: 20px;
  cursor: pointer;
  display: inline-flex;
}
.bell-ic {
  width: 22px;
  height: 22px;
}
.bell-wrap .cnt {
  position: absolute;
  top: -6px;
  right: -9px;
  background: var(--brand);
  color: #fff;
  border: 1.5px solid var(--ink);
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  padding: 0 5px;
  line-height: 1.5;
  font-family: var(--font-body);
}
.flat {
  color: var(--ink);
}
</style>
