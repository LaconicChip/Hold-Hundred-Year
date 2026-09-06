// 新股申购接口
import http from './http'

// 申购：POST /api/ipo/subscribe body {scriptDayId} -> IpoDTO
export function subscribe(scriptDayId) {
  return http.post('/api/ipo/subscribe', { scriptDayId })
}
