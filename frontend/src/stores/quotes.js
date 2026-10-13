// 实时行情 store（由 WebSocket 快照驱动）
//
// 股票静态档案（代码/名称/板块/发行价）的唯一来源是后端 GET /api/stocks，
// 这里把它缓存成 defs 供行情表与个股页复用。前端曾经另抄一份 constants/stocks.js，
// 板块名很快就和后端漂移（"医药生物" vs "医药"），屏幕上贴的板块标签和消息里的
// 板块名对不上——所以那份静态表已删除，不要再抄回来。
import { defineStore } from 'pinia'

export const useQuotesStore = defineStore('quotes', {
  state: () => ({
    // 静态档案：stockId -> {id, code, name, sector, basePrice}
    defs: {},
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
    // 行情数组（静态档案 + 实时字段，供行情表渲染），按 id 升序
    quoteList(state) {
      return Object.values(state.defs)
        .sort((a, b) => a.id - b.id)
        .map((def) => {
          const r = state.rows[def.id] || {}
          return {
            stockId: def.id,
            code: def.code,
            name: def.name,
            sector: def.sector,
            basePrice: def.basePrice,
            current: r.current != null ? r.current : def.basePrice,
            prevCurrent: r.prevCurrent != null ? r.prevCurrent : def.basePrice,
            prevClose: r.prevClose != null ? r.prevClose : def.basePrice,
            changePct: r.changePct != null ? r.changePct : 0,
            // 当日开高低 / 连板 / 停牌 / ST / 封板：来自 REST 全量行情，
            // WS 快照不下发这些字段，未加载时为 null，页面按“—”展示
            open: r.open ?? null,
            high: r.high ?? null,
            low: r.low ?? null,
            limitUp: r.limitUp ?? null,
            limitDown: r.limitDown ?? null,
            limitUpStreak: r.limitUpStreak || 0,
            halted: !!r.halted,
            st: !!r.st,
            sealed: r.sealed || 'NONE'
          }
        })
    },

    // 指数较上一 tick 的涨跌百分比（后端快照仅提供 indexValue，
    // 无当日开盘基准，此处按 tick 环比展示）
    indexChangePct(state) {
      if (!state.prevIndexValue) return 0
      return +(((state.indexValue - state.prevIndexValue) / state.prevIndexValue) * 100).toFixed(2)
    }
  },

  actions: {
    /** 按 id 取静态档案；档案未加载到时返回 null（调用方自行兜底）。 */
    defOf(stockId) {
      return this.defs[stockId] || null
    },

    /** 从后端行情行里抽出静态档案并缓存。 */
    registerDefs(list) {
      list.forEach((q) => {
        this.defs[q.stockId] = {
          id: q.stockId,
          code: q.code,
          name: q.name,
          sector: q.sector,
          basePrice: q.basePrice
        }
      })
    },

    // 合并服务端推送的 MarketSnapshot
    applySnapshot(snapshot) {
      if (!snapshot) return
      this.prevIndexValue = this.indexValue || snapshot.indexValue
      this.indexValue = snapshot.indexValue
      if (snapshot.tickIndex != null) this.tickIndex = snapshot.tickIndex

      const quotes = snapshot.quotes || []
      quotes.forEach((q) => {
        const def = this.defs[q.stockId]
        if (!def) return // 档案未到（首帧早于 REST）：本次跳过，下一帧或 REST 会补上
        const prev = this.rows[q.stockId]
        this.rows[q.stockId] = {
          stockId: q.stockId,
          name: def.name,
          sector: def.sector,
          // 记录旧值供闪烁动画
          prevCurrent: prev ? prev.current : q.current,
          current: q.current,
          // WS 快照不带昨收，沿用上次 REST 落库值（首拉前退化为基准价）
          prevClose: prev && prev.prevClose != null ? prev.prevClose : def.basePrice,
          // 后端 changePct 为小数比率（0.0998 表示 +9.98%），此处统一转百分数
          changePct: q.changePct * 100,
          // WS 快照不带开高低/连板/停牌/ST/封板，沿用上次 REST 落库值
          open: prev ? prev.open ?? null : null,
          high: prev ? prev.high ?? null : null,
          low: prev ? prev.low ?? null : null,
          limitUp: prev ? prev.limitUp ?? null : null,
          limitDown: prev ? prev.limitDown ?? null : null,
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
      if (!Array.isArray(list) || !list.length) return
      this.registerDefs(list)
      const next = { ...this.rows }
      list.forEach((q) => {
        const prev = this.rows[q.stockId]
        next[q.stockId] = {
          stockId: q.stockId,
          name: q.name,
          sector: q.sector,
          prevCurrent: prev ? prev.current : q.current,
          current: q.current,
          changePct: q.changePct * 100,
          prevClose: q.prevClose != null ? q.prevClose : q.basePrice,
          open: q.open != null ? q.open : null,
          high: q.high != null ? q.high : null,
          low: q.low != null ? q.low : null,
          limitUp: q.limitUp ?? null,
          limitDown: q.limitDown ?? null,
          limitUpStreak: q.limitUpStreak || 0,
          halted: !!q.halted,
          st: !!q.st,
          sealed: q.sealed || 'NONE'
        }
      })
      this.rows = next
    },

    /**
     * 跨日失效（bug 修复）：开高低 / 涨跌停价 / 连板 / 停牌 / ST / 封板都是【当日口径】，
     * 只来自 REST GET /api/stocks（WS 快照只有 current 与 changePct）。新的一天已经开始、
     * REST 还没重拉时，留着昨天的值就会画成"跌停 -20% 却还挂着涨停封板红底"的错位。
     * 所以日期一变先把它们清空（页面按"—"展示），等重拉回来再填。
     */
    invalidateDayFlags() {
      for (const id of Object.keys(this.rows)) {
        this.rows[id] = {
          ...this.rows[id],
          open: null,
          high: null,
          low: null,
          limitUp: null,
          limitDown: null,
          limitUpStreak: 0,
          halted: false,
          st: false,
          sealed: 'NONE'
        }
      }
    }
  }
})
