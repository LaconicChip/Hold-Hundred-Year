// 市场行情相关接口
import http from './http'

// 市场状态：GET /api/market/state -> MarketStateDTO
export function fetchMarketState() {
  return http.get('/api/market/state')
}

// 开盘（幂等）：POST /api/market/start -> MarketStateDTO
export function startMarket() {
  return http.post('/api/market/start')
}

// 跳到下一交易日：POST /api/market/skip -> DailySummaryDTO（终局态后端返回 400）
export function skipDay() {
  return http.post('/api/market/skip')
}

// 暂停 / 恢复本账户世界的实时推进：重要消息（type=ALERT）弹窗期间把行情冻住，
// 关掉弹窗必须恢复。服务端只动内存标记，不拦快进与结算
export function pauseMarket() {
  return http.post('/api/market/pause')
}

export function resumeMarket() {
  return http.post('/api/market/resume')
}

// 当日结算摘要：GET /api/market/daily-summary?gameDay=
export function fetchDailySummary(gameDay) {
  return http.get('/api/market/daily-summary', { params: { gameDay } })
}

// 排行榜：GET /api/market/leaderboard?gameDay=
export function fetchLeaderboard(gameDay) {
  return http.get('/api/market/leaderboard', { params: { gameDay } })
}

// 净值曲线：GET /api/market/equity-curve -> EquityCurveDTO
export function fetchEquityCurve() {
  return http.get('/api/market/equity-curve')
}
