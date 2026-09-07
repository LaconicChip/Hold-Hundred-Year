// 个人资产 / 持仓 / 流水 / 称号 接口（后端已实现，spec §11）
import http from './http'

// 资产四项 + 担保比例：GET /api/me/assets
export function fetchAssets() {
  return http.get('/api/me/assets')
}

// 持仓列表：GET /api/me/positions
export function fetchPositions() {
  return http.get('/api/me/positions')
}

// 交易流水：GET /api/me/trades
export function fetchTrades() {
  return http.get('/api/me/trades')
}

// 称号徽章：GET /api/me/titles
export function fetchTitles() {
  return http.get('/api/me/titles')
}
