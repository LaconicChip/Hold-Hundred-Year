<template>
  <!-- 营业厅看板：左边「今天第几天」+ 中间「百年指数」+ 右边「阶段/倒计时/操作」 -->
  <div class="statusbar card">
    <!-- 左：交易日 / 纪元 / 段位 -->
    <div class="sb-left">
      <div class="day-block">
        <span class="lab">交易日</span>
        <b class="day-num num">{{ market.currentDay || 0 }}</b>
        <span class="day-sub">第 {{ market.year || 0 }} 年 · 年内第 {{ market.dayInYear || 0 }} 天</span>
      </div>
      <span class="era-flag" :class="eraCls">
        <SvgIcon :name="eraIcon" class="era-ic" />
        {{ market.eraName || '平稳年代' }}
      </span>
      <TierBadge :tier="tier" />
    </div>

    <!-- 中：百年指数（看板主数据） -->
    <div class="sb-index stat" :class="indexChgCls">
      <span class="lab">
        <SvgIcon name="chart" class="lab-ic" />
        百年指数
      </span>
      <span class="val num">{{ indexText }}</span>
      <span class="sub-val num">
        <template v-if="indexChg !== 0">{{ indexChgText }}</template>
        <template v-else>平盘</template>
      </span>
    </div>

    <!-- 右：阶段 + 倒计时 + 操作 -->
    <div class="sb-right">
      <div class="phase-block">
        <span class="phase-tag" :class="{ 'is-open': market.isOpen }">
          <span class="dot" :class="market.isOpen ? 'r' : ''"></span>
          {{ phaseLabel }}
        </span>
        <span v-if="market.isOpen" class="countdown num">
          <SvgIcon name="clock" class="cd-ic" />{{ countdownText }}
        </span>
        <span v-else-if="market.marketState === 'LUNCH_BREAK'" class="phase-note">午休中 · 不可交易</span>
      </div>

      <div class="ops">
        <!-- 开局前选模式：注册不绑模式，每一局自己挑 -->
        <ModePicker v-if="showModePicker" v-model="pickedMode" compact />
        <button
          v-if="showStart"
          class="btn btn-primary btn-sm"
          :disabled="market.loading"
          @click="onStart"
        >
          <SvgIcon name="play" />
          {{ startText }}
        </button>

        <button
          v-if="showSkip"
          class="btn btn-sm"
          :disabled="market.loading"
          @click="onSkip"
        >
          <SvgIcon name="skip" />
          {{ skipText }}
        </button>

        <button
          v-if="market.isEnded"
          class="btn btn-primary btn-sm"
          @click="onViewResult"
        >
          <SvgIcon name="cert" />
          查看终局结算
        </button>

        <!-- 铃铛：未读数角标 -->
        <span class="bell-wrap" title="消息中心" @click="onBell">
          <SvgIcon name="bell" class="bell-ic" />
          <span v-if="unread > 0" class="cnt">{{ unread > 99 ? '99+' : unread }}</span>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { useMarketStore } from '@/stores/market'

import { useQuotesStore } from '@/stores/quotes'
import { useMessagesStore } from '@/stores/messages'
import SvgIcon from './SvgIcon.vue'
import TierBadge from './TierBadge.vue'
import ModePicker from './ModePicker.vue'
import { restartGame } from '@/api/game'

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

// 纪元旗标：market.eraTone getter -> bull/bear/shock
const ERA_TONE = {
  bull: { cls: 'era-bull', icon: 'bull' },
  bear: { cls: 'era-bear', icon: 'bear' },
  shock: { cls: 'era-shock', icon: 'alert' }
}
const eraCls = computed(() => (ERA_TONE[market.eraTone] || ERA_TONE.shock).cls)
const eraIcon = computed(() => (ERA_TONE[market.eraTone] || ERA_TONE.shock).icon)

const tier = computed(() => market.tier || 'RETAIL')

// 指数展示：点位优先取 WS 实时值，收盘/未推时回退 REST 状态值；
// 当日涨跌幅 = 实时点位 / 昨收基准（market.prevIndex，来自前日快照），
// 无实时值时用 market.indexDayPct 兜底
const indexText = computed(() => {
  const v = quotes.indexValue || market.indexValue
  return v ? v.toFixed(2) : '—'
})
const indexChg = computed(() => {
  const live = quotes.indexValue
  if (live && market.prevIndex) return +(((live - market.prevIndex) / market.prevIndex) * 100).toFixed(2)
  return market.indexDayPct || 0
})
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
// 跳过只对"今天还没收盘"的日子开放。收盘后再给一个"跳过下一交易日"，等于把玩家从没看过的
// 一整天凭空过掉（25 号收盘 → 直接 26 号已收盘 → 27 号开始），这一天毫无参与感。
// 要连续快进：先"开始下一交易日"，再在盘中"跳过当日"——每一天都被明确地跳过一次。
const DURING_DAY = ['MORNING_OPEN', 'LUNCH_BREAK', 'AFTERNOON_OPEN']
const showSkip = computed(() => !market.isEnded && DURING_DAY.includes(market.marketState))
const startText = computed(() =>
  market.marketState === 'NOT_STARTED' ? '开始第 1 天' : '开始下一交易日'
)
// 模式选择器只在"还没开局"时出现（第 1 天 NOT_STARTED）；开局后本局模式就定了
const pickedMode = ref(market.mode || 'STANDARD')
const showModePicker = computed(() => market.marketState === 'NOT_STARTED')
watch(() => market.mode, (m) => { if (m) pickedMode.value = m })

// 跳过按钮只在盘中/午休出现，文案恒为"跳过当日"
const skipText = computed(() => '跳过当日')

// ---- 倒计时 ----
// 用"截止时间戳"而不是本地每秒 -1：快速模式每跳 0.5 秒，倒计时本来就是小数，
// 靠 -1 递减会跨过 0 变成 -0.5，显示成负时间；这里每次刷新对齐到目标时刻，
// 之后按真实流逝时间反推，永远不会为负。
const deadline = ref(0)
// 必须有一个"会自己动"的响应式时钟：computed 里直接写 Date.now() 不是响应式的，
// 那样倒计时算一次就冻住，只有切页重新挂载才更新（就是刚踩的那个坑）。
const nowTick = ref(Date.now())
let timer = null
function fmt(s) {
  const whole = Math.max(0, Math.floor(s))
  const m = Math.floor(whole / 60)
  const sec = whole % 60
  return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0')
}
const remaining = computed(() => Math.max(0, (deadline.value - nowTick.value) / 1000))
const countdownText = computed(() => fmt(remaining.value))

function recalibrate() {
  if (market.isOpen) {
    deadline.value = Date.now() + (market.countdownTicks || 0) * market.tickSeconds * 1000
  }
}
watch(() => market.countdownTicks, recalibrate)
watch(() => market.isOpen, recalibrate)

onMounted(() => {
  recalibrate()
  timer = setInterval(() => {
    // 只做两件事：推一下时钟让倒计时自己走，以及收盘后归零。
    // 真实校准来自状态轮询/WS 带回的 countdownTicks（watch 里 recalibrate）。
    nowTick.value = Date.now()
    if (!market.isOpen) deadline.value = 0
  }, 250)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

// ---- 操作 ----
async function onStart() {
  if (market.loading) return
  try {
    // 选的模式与本局不同就先按新模式重开一局（模式一局一选，开局前可改）
    if (pickedMode.value && pickedMode.value !== market.mode) {
      await restartGame(pickedMode.value)
      await market.fetchState()
    }
    await market.start()
  } catch (e) {
    ElMessage.error({ message: e?.message || '开始失败', grouping: true })
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
    ElMessage.error({ message: e?.message || '跳过失败', grouping: true })
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
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
}

/* ---------- 左：交易日 ---------- */
.sb-left {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.day-block {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  padding: 2px var(--sp-3);
  border: var(--border);
  border-radius: var(--radius-md);
  background: var(--paper-warm);
  box-shadow: var(--shadow-sm);
}
.day-block .lab {
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.day-num {
  font-family: var(--font-fun);
  font-size: 32px;
  line-height: 1;
  color: var(--brand);
}
.day-sub {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  white-space: nowrap;
}

/* ---------- 中：百年指数 ---------- */
.sb-index {
  justify-self: center;
  min-width: 168px;
  align-items: center;
  text-align: center;
}
.sb-index .lab {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.lab-ic { width: 1em; height: 1em; }
.sb-index .val { font-size: 26px; }
.sb-index.up .val { color: var(--up); }
.sb-index.down .val { color: var(--down); }
.sb-index.up .sub-val { color: var(--up); }
.sb-index.down .sub-val { color: var(--down); }
.sb-index .sub-val {
  font-size: var(--fs-sm);
  color: var(--ink-3);
}

/* ---------- 右：阶段 + 操作 ---------- */
.sb-right {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  justify-content: flex-end;
  flex-wrap: wrap;
}
.phase-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.phase-tag {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-fun);
  font-size: var(--fs-base);
  color: var(--ink-2);
  white-space: nowrap;
}
.phase-tag.is-open { color: var(--up); }
.countdown {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-lg);
  color: var(--ink);
}
.cd-ic { width: 1em; height: 1em; color: var(--ink-2); }
.phase-note { font-size: var(--fs-xs); color: var(--ink-3); }

.ops {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
}
.bell-wrap {
  cursor: pointer;
  margin-left: var(--sp-1);
}
.bell-ic { width: 22px; height: 22px; }

@media (max-width: 1100px) {
  .statusbar { grid-template-columns: 1fr auto; }
  .sb-index { justify-self: start; }
  .sb-right { grid-column: 1 / -1; justify-content: space-between; }
}
</style>
