// 终局与重开接口
import http from './http'

// 终局结果：GET /api/game/result -> GameResultDTO
export function fetchGameResult() {
  return http.get('/api/game/result')
}

// 开新的一局（清数据 + 重新播种）：POST /api/game/restart，body 可带 mode
export function restartGame(gameMode) {
  return http.post('/api/game/restart', gameMode ? { mode: gameMode } : {})
}
