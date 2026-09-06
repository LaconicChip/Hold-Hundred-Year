// 消息中心相关接口
import http from './http'

// 消息列表（分页 + 过滤）
// GET /api/messages?source=&truthStatus=&unreadOnly=&gameDay=&page=1&size=20
export function fetchMessages({
  source = '',
  truthStatus = '',
  unreadOnly = false,
  gameDay = '',
  page = 1,
  size = 20
} = {}) {
  return http.get('/api/messages', {
    params: { source, truthStatus, unreadOnly, gameDay, page, size }
  })
}

// 标记单条已读：PUT /api/messages/{id}/read
export function markMessageRead(id) {
  return http.put(`/api/messages/${id}/read`)
}

// 全部已读：PUT /api/messages/read-all
export function markAllRead() {
  return http.put('/api/messages/read-all')
}
