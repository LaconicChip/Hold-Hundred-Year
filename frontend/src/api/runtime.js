// 运行时信息：存档架上的"手机一起玩"提示用
import http from './http'

// GET /api/runtime/info -> { port, portable, lanUrls: ['http://192.168.x.x:8080'] }
export function fetchRuntimeInfo() {
  return http.get('/api/runtime/info')
}
