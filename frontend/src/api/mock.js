// ============================================================================
// Mock 适配层
// ----------------------------------------------------------------------------
// 以下端点后端【未实现】（规格 §11 有定义，但后端目前缺失）：
//   GET /api/me/assets
//   GET /api/me/positions
//   GET /api/me/trades
//   GET /api/me/titles
//   GET /api/stocks                 （股票列表）
//   GET /api/stocks/{id}/quotes     （单股分时）
//   GET /api/market/index           （指数分时）
//
// 本文件按规格形状提供假数据，供前端在后端补齐前联调使用。
// 后端补齐后，把对应 api 模块顶部的 `const USE_MOCK = true` 改为 false，
// 即可走真实 http 请求，无需改动其它代码。
// ============================================================================

import { STOCKS, STOCK_MAP } from '@/constants/stocks'

// 简单随机扰动，制造行情波动的“游戏感”
function jitter(base, pct = 0.03) {
  const delta = base * pct * (Math.random() * 2 - 1)
  return +(base + delta).toFixed(2)
}

// ---------------------------------------------------------------------------
// 资产（四项 + 担保比例）：现金 / 持仓市值 / 负债 / 净资产 / 担保比例
// ---------------------------------------------------------------------------
export function mockAssets() {
  const cash = 500000
  const marketValue = 300000
  const debt = 100000
  const netAsset = cash + marketValue - debt
  // 担保比例 = (现金 + 持仓市值) / 负债；无负债时后端固定返回 0
  const guardRatio = debt > 0 ? +((cash + marketValue) / debt).toFixed(4) : 0
  return { cash, marketValue, debt, netAsset, guardRatio }
}

// ---------------------------------------------------------------------------
// 持仓列表
// ---------------------------------------------------------------------------
export function mockPositions() {
  // 取前 6 只作为初始持仓样本
  const holdingIds = [1, 2, 3, 4, 15, 18]
  return holdingIds.map((id, idx) => {
    const s = STOCK_MAP[id]
    const shares = [200, 300, 100, 500, 150, 800][idx]
    const costPrice = +(s.basePrice * (0.9 + Math.random() * 0.2)).toFixed(2)
    const current = jitter(s.basePrice)
    const value = +(shares * current).toFixed(2)
    const profit = +(shares * (current - costPrice)).toFixed(2)
    const profitPct = +((current - costPrice) / costPrice * 100).toFixed(2)
    return {
      stockId: id,
      code: s.code,
      name: s.name,
      sector: s.sector,
      shares,
      costPrice,
      current,
      marketValue: value,
      profit,
      profitPct
    }
  })
}

// ---------------------------------------------------------------------------
// 交易流水
// ---------------------------------------------------------------------------
function genTrade(id, stockId, side, daysAgo) {
  const s = STOCK_MAP[stockId]
  const shares = [100, 200, 300, 500][id % 4]
  const price = jitter(s.basePrice)
  const amount = +(shares * price).toFixed(2)
  const commission = +(amount * 0.0003).toFixed(2)
  const tax = side === 'sell' ? +(amount * 0.0005).toFixed(2) : 0
  const d = new Date(Date.now() - daysAgo * 86400000)
  return {
    id,
    time: d.toISOString().slice(0, 19).replace('T', ' '),
    stockId,
    code: s.code,
    name: s.name,
    side,
    shares,
    price,
    amount,
    commission,
    tax
  }
}

export function mockTrades() {
  const rows = [
    genTrade(1, 2, 'buy', 12),
    genTrade(2, 1, 'buy', 9),
    genTrade(3, 4, 'sell', 6),
    genTrade(4, 15, 'buy', 4),
    genTrade(5, 3, 'buy', 2),
    genTrade(6, 18, 'sell', 1)
  ]
  return { records: rows, total: rows.length }
}

// ---------------------------------------------------------------------------
// 称号徽章
// ---------------------------------------------------------------------------
export function mockTitles() {
  return [
    { id: 't1', name: '初入江湖', desc: '完成第一次交易', unlocked: true },
    { id: 't2', name: '满仓猛士', desc: '单日仓位逼近上限', unlocked: true },
    { id: 't3', name: '杠杆玩家', desc: '使用过融资借款', unlocked: false },
    { id: 't4', name: '百年股神', desc: '净值翻倍', unlocked: false }
  ]
}

// ---------------------------------------------------------------------------
// 股票列表（静态档案 + 行情快照形状）
// ---------------------------------------------------------------------------
export function mockStockList() {
  return STOCKS.map((s) => {
    const current = jitter(s.basePrice)
    const changePct = +(((current - s.basePrice) / s.basePrice) * 100).toFixed(2)
    return {
      stockId: s.id,
      code: s.code,
      name: s.name,
      sector: s.sector,
      basePrice: s.basePrice,
      current,
      changePct
    }
  })
}

// ---------------------------------------------------------------------------
// 单股分时（给定股票 id，返回若干分时点）
// ---------------------------------------------------------------------------
export function mockStockQuotes(stockId) {
  const s = STOCK_MAP[stockId]
  if (!s) return []
  const points = []
  const n = 48 // 每 5 分钟一个点，模拟一个交易日
  let price = s.basePrice
  for (let i = 0; i < n; i++) {
    price = jitter(price, 0.01)
    const minutes = i * 5
    const hh = String(9 + Math.floor((minutes + 30) / 60)).padStart(2, '0')
    const mm = String((minutes + 30) % 60).padStart(2, '0')
    points.push({ time: `${hh}:${mm}`, price })
  }
  return points
}

// ---------------------------------------------------------------------------
// 指数分时（全市场指数）
// ---------------------------------------------------------------------------
export function mockIndexPoints() {
  const points = []
  const n = 48
  let value = 3000
  for (let i = 0; i < n; i++) {
    value = jitter(value, 0.004)
    const minutes = i * 5
    const hh = String(9 + Math.floor((minutes + 30) / 60)).padStart(2, '0')
    const mm = String((minutes + 30) % 60).padStart(2, '0')
    points.push({ time: `${hh}:${mm}`, value: +value.toFixed(2) })
  }
  return points
}
