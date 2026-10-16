// 便携版入口：这台机器上的本地存档。这一档没有账号系统，所以只有三个动作。
// 默认档（线上多人）根本没注册这组接口 -> 404，前端据此回落到"登录 / 注册"。
import http from './http'

// GET /api/auth/local/status -> { local, hasSave, day, totalDays, modeLabel, marketState, finished, netAsset }
export function fetchLocalStatus() {
  return http.get('/api/auth/local/status')
}

// 继续游戏：为本地存档签发令牌
export function localResume() {
  return http.post('/api/auth/local/resume')
}

// 创建新游戏：首次即建本地玩家，已有存档则清空重播（调用前必须已经二次确认）
export function localNewGame() {
  return http.post('/api/auth/local/new')
}
