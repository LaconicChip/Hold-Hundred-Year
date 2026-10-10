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

// 高利贷（spec §6.6）：视图与借还都返回 SharkLoanDTO
// —— 余额 / 今日利息 / 到期倒计时 / 规则参数 / 告示 / 逐笔台账，前端只渲染不复写规则
export function fetchShark() {
  return http.get('/api/loan/shark')
}
export function sharkBorrow(amount) {
  return http.post('/api/loan/shark/borrow', { amount })
}
export function sharkRepay(amount) {
  return http.post('/api/loan/shark/repay', { amount })
}
