// 终局与重开接口
import http from './http'

// 终局结果：GET /api/game/result -> GameResultDTO
export function fetchGameResult() {
  return http.get('/api/game/result')
}

// 重新开局（重新播种）：POST /api/game/restart
export function restartGame() {
  return http.post('/api/game/restart')
}
