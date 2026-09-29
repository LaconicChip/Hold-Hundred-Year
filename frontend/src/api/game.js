// 终局、重开与本局模式接口
import http from './http'

// 终局结果：GET /api/game/result -> GameResultDTO
export function fetchGameResult() {
  return http.get('/api/game/result')
}

// 开新的一局（清数据 + 重新播种）：POST /api/game/restart，body 可带 mode
export function restartGame(gameMode) {
  return http.post('/api/game/restart', gameMode ? { mode: gameMode } : {})
}

// 可选的一局模式：GET /api/game/modes -> [{code,label,description,totalDays}]
export function fetchGameModes() {
  return http.get('/api/game/modes')
}
