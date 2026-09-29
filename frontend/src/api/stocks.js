// 股票列表 / 单股分时 / 指数分时 接口（后端已实现，spec §11）
import http from './http'

// 股票列表：GET /api/stocks
export function fetchStockList() {
  return http.get('/api/stocks')
}

// 单股分时：GET /api/stocks/{id}/quotes（固定 90 槽时间轴，未到槽位 price=null）
export function fetchStockQuotes(stockId) {
  return http.get(`/api/stocks/${stockId}/quotes`)
}

// 单股日K：GET /api/stocks/{id}/kline?limit=60（按 game_day 升序）
export function fetchStockKline(stockId, limit = 60) {
  return http.get(`/api/stocks/${stockId}/kline`, { params: { limit } })
}
