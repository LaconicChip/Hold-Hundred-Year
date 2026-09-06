// 个人资产 / 持仓 / 流水 / 称号 接口
// 说明：以下端点后端【未实现】（见 mock.js 文件头）。
// 通过 USE_MOCK 单点切换：后端补齐后改为 false 即可走真实 http。
import http from './http'
import {
  mockAssets,
  mockPositions,
  mockTrades,
  mockTitles
} from './mock'

const USE_MOCK = true

// 资产四项 + 担保比例：GET /api/me/assets
export function fetchAssets() {
  if (USE_MOCK) return Promise.resolve(mockAssets())
  return http.get('/api/me/assets')
}

// 持仓列表：GET /api/me/positions
export function fetchPositions() {
  if (USE_MOCK) return Promise.resolve(mockPositions())
  return http.get('/api/me/positions')
}

// 交易流水：GET /api/me/trades
export function fetchTrades() {
  if (USE_MOCK) return Promise.resolve(mockTrades())
  return http.get('/api/me/trades')
}

// 称号徽章：GET /api/me/titles
export function fetchTitles() {
  if (USE_MOCK) return Promise.resolve(mockTitles())
  return http.get('/api/me/titles')
}
