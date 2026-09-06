// 融资借贷相关接口
import http from './http'

// 借款：POST /api/loan/borrow body {amount} -> LoanResultDTO
export function borrow(amount) {
  return http.post('/api/loan/borrow', { amount })
}

// 还款：POST /api/loan/repay body {amount} -> LoanResultDTO
export function repay(amount) {
  return http.post('/api/loan/repay', { amount })
}
