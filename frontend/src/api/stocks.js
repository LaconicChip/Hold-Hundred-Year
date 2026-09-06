// 股票列表 / 单股分时 / 指数分时 接口
// 说明：以下端点后端【未实现】（见 mock.js 文件头）。
// 通过 USE_MOCK 单点切换：后端补齐后改为 false 即可走真实 http。
import http from './http'
import {
  mockStockList,
  mockStockQuotes,
  mockIndexPoints
} from './mock'

const USE_MOCK = true

// 股票列表：GET /api/stocks
export function fetchStockList() {
  if (USE_MOCK) return Promise.resolve(mockStockList())
  return http.get('/api/stocks')
}

// 单股分时：GET /api/stocks/{id}/quotes
export function fetchStockQuotes(stockId) {
  if (USE_MOCK) return Promise.resolve(mockStockQuotes(stockId))
  return http.get(`/api/stocks/${stockId}/quotes`)
}

// 指数分时：GET /api/market/index
export function fetchIndexPoints() {
  if (USE_MOCK) return Promise.resolve(mockIndexPoints())
  return http.get('/api/market/index')
}
