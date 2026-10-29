// 市场状态 store（REST 拉取，平铺 MarketStateDTO 字段）
import { defineStore } from 'pinia'
import {
  fetchMarketState,
  startMarket,
  skipDay,
  fetchDailySummary,
  fetchLeaderboard,
  fetchEquityCurve,
  pauseMarket,
  resumeMarket
} from '@/api/market'
import { fetchGameModes } from '@/api/game'
import { useQuotesStore } from './quotes'

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
    // 这一份世界是否已被服务端冻住（MarketStateDTO.paused，2026-10-25）：
    // 状态条的"行情已暂停"与倒计时冻结只认这个字段，不认"我刚发过 pause 请求"。
    paused: false,
    // 可选模式清单（开新局的选择器用，来自后端 GameMode，不再前端另抄）
    modes: [],
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
    // 拉一次模式清单；已拉过就不重复请求（选择器可能同屏多处）
    async fetchModes() {
      if (this.modes.length) return this.modes
      try {
        this.modes = await fetchGameModes()
      } catch {
        this.modes = []
      }
      return this.modes
    },

    async fetchState() {
      this.loading = true
      try {
        const s = await fetchMarketState()
        Object.assign(this, s)
        // 收盘 / 结算 / 跳过当日之后 WS 不再推指数，行情 store 里还留着"点下跳过那一刻"的旧值，
        // 大盘页就会一直显示旧指数（老 bug）。这里把 game_state 里的收盘指数写回去；
        // 盘中不动它——盘中以 WS 实时值为准，状态接口的 index_value 每 5 跳才落库一次。
        if (!this.isOpen) {
          useQuotesStore().indexValue = s.indexValue
        }
      } finally {
        this.loading = false
      }
    },

    /**
     * 冻结 / 恢复本账户世界。
     *
     * <p>服务端在 ALERT 落地时已经自己暂停（NotifyPublisher），这里的调用是幂等补刀；
     * 失败也不谎报——paused 只认下一次 fetchState 带回来的真值，前端绝不自己"假装"停住了。
     */
    async pauseWorld() {
      try {
        await pauseMarket()
      } catch {
        // 网络抖动由服务端兜底；下一次状态轮询会把真实 paused 带回来
      }
    },

    async resumeWorld() {
      try {
        await resumeMarket()
        this.paused = false
      } catch {
        // 同上：不谎报，等轮询校正
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
