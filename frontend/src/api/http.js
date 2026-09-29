// 统一 HTTP 客户端
// 约定：
//   1. 所有 REST 接口前缀 /api，由 vite proxy 转发到后端 8080
//   2. 统一响应体 R{code,msg,data}，成功 code===0
//   3. 鉴权信息从 localStorage 的 'hold-year-token' 读取，注入 Bearer
//   4. HTTP 401 -> 清 token 并跳 /login；HTTP 400 业务失败 -> reject(msg)

import axios from 'axios'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import router from '@/router'

const TOKEN_KEY = 'hold-year-token'

// 读取本地令牌
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

// 写入 / 清除本地令牌
export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

const http = axios.create({
  baseURL: '/',
  timeout: 15000
})

// 请求拦截器：注入 JWT
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：解包 R，处理错误码
http.interceptors.response.use(
  (resp) => {
    // 拿到后端响应体（axios 默认 JSON 解析）
    const body = resp.data
    // 容错：部分静态资源或异常返回非 R 结构，直接透传
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      // 业务失败：HTTP 400 with code!=0
      const err = new Error(body.msg || '请求失败')
      err.code = body.code
      return Promise.reject(err)
    }
    return body
  },
  (error) => {
    const status = error.response ? error.response.status : 0
    const body = error.response ? error.response.data : null
    if (status === 401) {
      setToken('')
      ElMessage.error('登录已过期，请重新登录')
      if (router.currentRoute.value.name !== 'login') {
        router.push('/login')
      }
      const err = new Error('未认证')
      err.code = 401
      return Promise.reject(err)
    }
    if (status === 400 && body && body.msg) {
      const err = new Error(body.msg)
      err.code = body.code || 400
      return Promise.reject(err)
    }
    const msg = (body && body.msg) || error.message || '网络异常'
    const err = new Error(msg)
    err.code = status
    return Promise.reject(err)
  }
)

export default http
