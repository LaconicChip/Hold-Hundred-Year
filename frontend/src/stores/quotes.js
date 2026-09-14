// 实时行情 store（由 WebSocket 快照驱动）
import { defineStore } from 'pinia'
import { STOCKS, STOCK_MAP } from '@/constants/stocks'

export const useQuotesStore = defineStore('quotes', {
  state: () => ({
    // 当前 tick 序号
    tickIndex: 0,
    // 指数最新值
    indexValue: 0,
    // 指数上一tick值（用于计算涨跌/闪烁）
    prevIndexValue: 0,
    // 个股行情表：stockId -> {stockId,name,sector,current,prevCurrent,changePct}
    rows: {}
  }),

  getters: {
    // 行情数组（含静态字段 + 实时字段，供行情表渲染），按 id 升序
    quoteList(state) {
      return STOCKS
        .map((s) => {
          const r = state.rows[s.id] || {}
          return {
            stockId: s.id,
            code: s.code,
            name: s.name,
            sector: s.sector,
            basePrice: s.basePrice,
            current: r.current != null ? r.current : s.basePrice,
            prevCurrent: r.prevCurrent != null ? r.prevCurrent : s.basePrice,
            prevClose: r.prevClose != null ? r.prevClose : s.basePrice,
            changePct: r.changePct != null ? r.changePct : 0,
            // 当日开高低 / 连板 / 停牌 / ST / 封板：来自 REST 全量行情，
            // WS 快照不下发这些字段，未加载时为 null，页面按“—”展示
            open: r.open != null ? r.open : null,
            high: r.high != null ? r.high : null,
            low: r.low != null ? r.low : null,
            limitUpStreak: r.limitUpStreak || 0,
            halted: !!r.halted,
            st: !!r.st,
            sealed: r.sealed || 'NONE'
          }
        })
        .sort((a, b) => a.stockId - b.stockId)
    },

    // 指数较上一 tick 的涨跌百分比（后端快照仅提供 indexValue，
    // 无当日开盘基准，此处按 tick 环比展示）
    indexChangePct(state) {
      if (!state.prevIndexValue) return 0
      return +(((state.indexValue - state.prevIndexValue) / state.prevIndexValue) * 100).toFixed(2)
    }
  },

  actions: {
    // 合并服务端推送的 MarketSnapshot
    applySnapshot(snapshot) {
      if (!snapshot) return
      this.prevIndexValue = this.indexValue || snapshot.indexValue
      this.indexValue = snapshot.indexValue
      if (snapshot.tickIndex != null) this.tickIndex = snapshot.tickIndex

      const quotes = snapshot.quotes || []
      quotes.forEach((q) => {
        const def = STOCK_MAP[q.stockId]
        if (!def) return
        const prev = this.rows[q.stockId]
        this.rows[q.stockId] = {
          stockId: q.stockId,
          name: def.name,
          sector: def.sector,
          // 记录旧值供闪烁动画
          prevCurrent: prev ? prev.current : q.current,
          current: q.current,
          // WS 快照不带昨收，沿用上次 REST 落库值（首拉前退化为基准价）
          prevClose: prev ? prev.prevClose != null ? prev.prevClose : def.basePrice : def.basePrice,
          // 后端 changePct 为小数比率（0.0998 表示 +9.98%），此处统一转百分数
          changePct: q.changePct * 100,
          // WS 快照不带开高低/连板/停牌/ST/封板，沿用上次 REST 落库值
          open: prev ? prev.open : null,
          high: prev ? prev.high : null,
          low: prev ? prev.low : null,
          limitUpStreak: prev ? prev.limitUpStreak || 0 : 0,
          halted: prev ? !!prev.halted : false,
          st: prev ? !!prev.st : false,
          sealed: prev ? prev.sealed || 'NONE' : 'NONE'
        }
      })
    },

    // 用 REST 全量行情（GET /api/stocks）初始化/覆盖行情表：页面刷新后 WS 推送
    // 尚未到达（或已收盘无推送）时，展示最新落库价而非基准价
    applyStockList(list) {
      if (!Array.isArray(list)) return
      const next = { ...this.rows }
      list.forEach((q) => {
        const def = STOCK_MAP[q.stockId]
        if (!def) return
        const prev = this.rows[q.stockId]
        next[q.stockId] = {
          stockId: q.stockId,
          name: def.name,
          sector: def.sector,
          prevCurrent: prev ? prev.current : q.current,
          current: q.current,
          changePct: q.changePct * 100,
          prevClose: q.prevClose != null ? q.prevClose : def.basePrice,
          open: q.open != null ? q.open : null,
          high: q.high != null ? q.high : null,
          low: q.low != null ? q.low : null,
          limitUpStreak: q.limitUpStreak || 0,
          halted: !!q.halted,
          st: !!q.st,
          sealed: q.sealed || 'NONE'
        }
      })
      this.rows = next
    },

    // 未开盘时用基准价初始化 24 行（防止行情表空白）
    initFromDefs() {
      const next = {}
      STOCKS.forEach((s) => {
        next[s.id] = {
          stockId: s.id,
          name: s.name,
          sector: s.sector,
          prevCurrent: s.basePrice,
          current: s.basePrice,
          changePct: 0,
          prevClose: s.basePrice,
          open: null,
          high: null,
          low: null,
          limitUpStreak: 0,
          halted: false,
          st: false,
          sealed: 'NONE'
        }
      })
      this.rows = next
      this.indexValue = 0
      this.prevIndexValue = 0
      this.tickIndex = 0
    }
  }
})
