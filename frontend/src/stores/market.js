// 市场状态 store（REST 拉取，平铺 MarketStateDTO 字段）
import { defineStore } from 'pinia'
import {
  fetchMarketState,
  startMarket,
  skipDay,
  fetchDailySummary,
  fetchLeaderboard,
  fetchEquityCurve
} from '@/api/market'

export const useMarketStore = defineStore('market', {
  state: () => ({
    // MarketStateDTO 字段
    marketState: '',
    currentDay: 0,
    year: 0,
    dayInYear: 0,
    eraCode: '',
    eraName: '',
    tier: '',
    tickIndex: 0,
    countdownTicks: 0,
    worldSeed: '',
    // 本局游戏档案（新建局时选定，整局不变）：节奏与涨跌停都由它决定
    mode: 'STANDARD',
    modeLabel: '标准',
    modeDescription: '',
    tickMs: 1000,
    daySeconds: 100,
    totalDays: 72,
    limitPct: 0.1,
    // 百年指数（StatusBar 指数块）：indexValue 当前值 / prevIndex 昨收基准 /
    // indexDayPct 当日涨跌幅（%），来自 MarketStateDTO
    indexValue: 0,
    prevIndex: 0,
    indexDayPct: 0,
    // 加载态
    loading: false
  }),

  getters: {
    // 交易中（早盘/午盘）
    isOpen(state) {
      return state.marketState === 'MORNING_OPEN' || state.marketState === 'AFTERNOON_OPEN'
    },

    // 终局态
    isEnded(state) {
      return state.marketState === 'BANKRUPT' || state.marketState === 'FINISHED'
    },

    // 每跳秒数（倒计时与分时轮询都用它，缺省兜底 1 秒）
    tickSeconds(state) {
      return state.tickMs > 0 ? state.tickMs / 1000 : 1
    },

    // 纪元旗标基调：bull / bear / shock（供纪元旗标样式）
    eraTone(state) {
      const label = `${state.eraName}|${state.eraCode}`
      if (/牛|AI|新能源/.test(label)) return 'bull'
      if (/股灾|熔断|熊市|黑/.test(label)) return 'bear'
      return 'shock'
    }
  },

  actions: {
    async fetchState() {
      this.loading = true
      try {
        const s = await fetchMarketState()
        Object.assign(this, s)
      } finally {
        this.loading = false
      }
    },

    async start() {
      const s = await startMarket()
      Object.assign(this, s)
      return s
    },

    // 跳到下一交易日，返回 DailySummaryDTO 供弹窗展示
    async skip() {
      const summary = await skipDay()
      // 终局态后端 400 会进入 catch，调用方处理
      await this.fetchState()
      return summary
    },

    async fetchDailySummary(gameDay) {
      return fetchDailySummary(gameDay)
    },

    async fetchLeaderboard(gameDay) {
      return fetchLeaderboard(gameDay)
    },

    async fetchEquityCurve() {
      return fetchEquityCurve()
    }
  }
})
