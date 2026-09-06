// 鉴权相关接口
import http from './http'

// 注册：body {username,password} -> data {userId}
export function register(username, password) {
  return http.post('/api/auth/register', { username, password })
}

// 登录：body {username,password} -> data {token, userId, username}
export function login(username, password) {
  return http.post('/api/auth/login', { username, password })
}

// 获取当前用户：GET /api/me -> data {userId, username}
export function fetchMe() {
  return http.get('/api/me')
}
