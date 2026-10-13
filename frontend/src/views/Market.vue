<template>
  <div class="page">
    <!-- 页头：标题 + 欢迎语（品牌与退出已收进侧栏） -->
    <header class="page-head">
      <div>
        <h1 class="page-title">
          <SvgIcon name="chart" class="title-ic" />
          大盘行情
        </h1>
        <p class="page-desc">24 只标的同屏博弈 · 点任意一行进入个股详情</p>
      </div>
      <span class="who muted">
        <SvgIcon name="user" class="who-ic" />
        {{ user.username || '股民' }}
      </span>
    </header>

    <!-- 终局横幅（BANKRUPT / FINISHED 区分文案） -->
    <div v-if="market.isEnded" class="end-banner card-sm">
      <SvgIcon :name="market.marketState === 'BANKRUPT' ? 'bear' : 'bull'" class="end-mascot" />
      <div class="end-text">
        <b class="end-title">{{ market.marketState === 'BANKRUPT' ? '本局已爆仓' : '本局已通关' }}</b>
        <span class="end-sub muted">
          {{ market.marketState === 'BANKRUPT' ? '天台风大，先看看这百年是怎么亏的' : '一百年过去了，你居然还在' }}
        </span>
      </div>
      <button class="btn btn-primary btn-sm push" @click="viewResult">
        <SvgIcon name="cert" />
        查看终局结算
      </button>
    </div>

    <!-- 状态条 + 跑马灯 + 行情表 + 弹幕 -->
    <StatusBar @skipped="onSkipped" />
    <TickerTape />
    <QuoteTable />
    <DanmuBar />

    <!-- 新手引导气泡 -->
    <OnboardingTip />

    <!-- 打新弹窗（监听通知自动弹出） -->
    <IpoDialog />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useMarketStore } from '@/stores/market'
import { useQuotesStore } from '@/stores/quotes'
import { useMessagesStore } from '@/stores/messages'
import { fetchStockList } from '@/api/stocks'
import SvgIcon from '@/components/SvgIcon.vue'
import StatusBar from '@/components/StatusBar.vue'
import TickerTape from '@/components/TickerTape.vue'
import QuoteTable from '@/components/QuoteTable.vue'
import DanmuBar from '@/components/DanmuBar.vue'
import OnboardingTip from '@/components/OnboardingTip.vue'
import IpoDialog from '@/components/IpoDialog.vue'

const router = useRouter()
const user = useUserStore()
const market = useMarketStore()
const quotes = useQuotesStore()
const messages = useMessagesStore()

let pollTimer = null

// 跳过成功后由 StatusBar 抛出 DailySummaryDTO，跳转正式收盘晚报页
function onSkipped(s) {
  const gameDay = s && s.gameDay
  router.push({ path: '/summary', query: gameDay ? { gameDay } : {} })
}

// 终局横幅 -> 终局结算页
function viewResult() {
  router.push('/result')
}

onMounted(async () => {
  // 全量行情：顺带把股票静态档案（名称/板块/发行价）灌进 store；
  // 已收盘或 WS 未推时展示最新落库价，失败不阻塞大盘
  try {
    quotes.applyStockList(await fetchStockList())
  } catch {
    // 行情接口失败时保留基准价初始化
  }
  await market.fetchState()
  try {
    await messages.fetchMessages({ size: 50 }) // 供铃铛未读 + 跑马灯
  } catch {
    // 消息拉取失败不阻塞大盘
  }
  // 盘中轮询：每 2s 校正状态与倒计时（WS 只推行情不推状态，这里兜住阶段/跳号变化），
  // 顺带校正封板/停牌/涨跌停价——这些当日口径同样只在 REST 里，盘中会随时变化
  pollTimer = setInterval(() => {
    market.fetchState().catch(() => {})
    fetchStockList()
      .then((list) => quotes.applyStockList(list))
      .catch(() => {})
  }, 2000)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.page-head {
  margin-bottom: 0;
}
.title-ic {
  width: 0.85em;
  height: 0.85em;
  color: var(--brand);
}
.who {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-sm);
}
.who-ic { width: 1.2em; height: 1.2em; }

/* 终局横幅 */
.end-banner {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  background: var(--yellow-soft);
}
.end-mascot {
  width: 42px;
  height: 42px;
  flex: none;
}
.end-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.end-title {
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
}
.end-sub {
  font-size: var(--fs-xs);
}

@media (max-width: 700px) {
  .page-title { font-size: var(--fs-2xl); }
  .end-banner { flex-wrap: wrap; }
}
</style>
