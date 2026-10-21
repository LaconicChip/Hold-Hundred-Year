// 存档架接口：这一版没有账号系统，入口就是"列存档 / 新建 / 进入 / 删除"。
import http from './http'

// GET /api/saves -> [{ id, name, day, totalDays, modeLabel, marketState, finished, netAsset, lastPlayedAt }]
export function fetchSaves() {
  return http.get('/api/saves')
}

// 新建一份存档（不会覆盖旧存档）：body 可选 { mode: 'STANDARD' | 'QUICK' }
export function createSave(mode) {
  return http.post('/api/saves', mode ? { mode } : {})
}

// 进入一份存档：返回 { token, userId, username }
export function openSave(id) {
  return http.post(`/api/saves/${id}/open`)
}

// 删除一份存档（连同该存档的全部游戏数据，不可恢复）
export function deleteSave(id) {
  return http.delete(`/api/saves/${id}`)
}
