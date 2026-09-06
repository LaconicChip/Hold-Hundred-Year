<template>
  <div class="market-page">
    <!-- 简易页头：游戏名 + 当前用户 + 退出 -->
    <header class="page-head">
      <div class="brand">百年证券营业部</div>
      <div class="head-right">
        <span class="who">你好，{{ user.username || '股民' }}</span>
        <button class="btn btn-sm" @click="onLogout">退出</button>
      </div>
    </header>

    <!-- 终局横幅（BANKRUPT / FINISHED 区分文案） -->
    <div v-if="market.isEnded" class="card end-banner">
      <div class="end-title">
        {{ market.marketState === 'BANKRUPT' ? '本局已爆仓' : '本局已通关' }}
      </div>
      <button class="btn btn-primary" @click="viewResult">查看终局结算</button>
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
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useMarketStore } from '@/stores/market'
import { useQuotesStore } from '@/stores/quotes'
import { useMessagesStore } from '@/stores/messages'
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

function onLogout() {
  user.logout()
  router.push('/login')
}

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
  // 无行情数据时用基准价初始化，避免表格空白
  if (!quotes.rows || !Object.keys(quotes.rows).length) quotes.initFromDefs()
  await market.fetchState()
  try {
    await messages.fetchMessages({ size: 50 }) // 供铃铛未读 + 跑马灯
  } catch {
    // 消息拉取失败不阻塞大盘
  }
  // 盘中轮询：每 10s 校正状态/倒计时（WS 不推状态变化时的兜底）
  pollTimer = setInterval(() => {
    market.fetchState().catch(() => {})
  }, 10000)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.market-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px 20px 80px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.brand {
  font-family: var(--font-fun);
  font-size: 26px;
}
.head-right {
  display: inline-flex;
  align-items: center;
  gap: 14px;
}
.who {
  color: var(--ink-2);
  font-size: 14px;
}
.btn-sm {
  font-size: 14px;
  padding: 5px 16px;
  box-shadow: 2px 2px 0 var(--ink);
}
.end-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  background: #fff3d6;
}
.end-title {
  font-family: var(--font-fun);
  font-size: 22px;
}
</style>
