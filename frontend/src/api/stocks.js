// 股票列表 / 单股分时 / 指数分时 接口（后端已实现，spec §11）
import http from './http'

// 股票列表：GET /api/stocks
export function fetchStockList() {
  return http.get('/api/stocks')
}

// 单股分时：GET /api/stocks/{id}/quotes
export function fetchStockQuotes(stockId) {
  return http.get(`/api/stocks/${stockId}/quotes`)
}

// 指数分时：GET /api/market/index
export function fetchIndexPoints() {
  return http.get('/api/market/index')
}
