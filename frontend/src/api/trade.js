// 交易相关接口（买卖）
import http from './http'

// 买入：POST /api/trade/buy body {stockId, shares} -> TradeResultDTO
export function buy(stockId, shares) {
  return http.post('/api/trade/buy', { stockId, shares })
}

// 卖出：POST /api/trade/sell body {stockId, shares} -> TradeResultDTO
export function sell(stockId, shares) {
  return http.post('/api/trade/sell', { stockId, shares })
}
