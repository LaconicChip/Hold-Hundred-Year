// WebSocket 客户端封装（STOMP over 原生 WebSocket）
// ---------------------------------------------------------------------------
// 后端为原生 WebSocket 端点（/ws），鉴权通过握手 query 参数 token 传递，
// 因此【不使用】SockJS，直接用 @stomp/stompjs 的 brokerURL 连接。
//
// 四通道（后端 convertAndSendToUser 机制，客户端固定订阅地址）：
//   /user/queue/quotes           -> MarketSnapshot {tickIndex,indexValue,quotes:[{stockId,current,changePct}]}
//   /user/queue/messages         -> MessageDTO 单条
//   /user/queue/notifications    -> 任意 JSON（字段不定，容错）
//   /user/queue/danmu            -> 纯文本（可能含“股吧ID：内容”）
//
// 断线自动重连：reconnectDelay 5000。
// 登出时调用 ws.disconnect() 显式断开。
// ---------------------------------------------------------------------------

import { Client } from '@stomp/stompjs'

const CHANNELS = ['quotes', 'messages', 'notifications', 'danmu']

function destOf(channel) {
  return `/user/queue/${channel}`
}

class WsClient {
  constructor() {
    this.client = null
    this.handlers = Object.create(null)
    this.connected = false
  }

  // 连接并订阅四个用户专属通道
  connect(token) {
    if (!token) return
    // 先断开旧的，避免重复激活
    if (this.client) this.disconnect()

    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    this.client = new Client({
      // 浏览器实际连到 vite dev server（/ws 由 vite proxy 转发到后端 8080）
      brokerURL: `${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`,
      reconnectDelay: 5000,
      // STOMP debug 全部静默，避免控制台噪音
      debug: () => {}
    })

    this.client.onConnect = () => {
      this.connected = true
      // 重连后重新订阅（stomp 重连会再次触发 onConnect）
      CHANNELS.forEach((c) => {
        this.client.subscribe(destOf(c), (msg) => this._dispatch(c, msg.body))
      })
    }

    this.client.onStompError = (frame) => {
      // 协议层错误，仅记录不影响重连
      console.warn('[ws] STOMP error:', frame.headers['message'])
    }

    this.client.onWebSocketClose = () => {
      this.connected = false
    }

    this.client.activate()
  }

  // 注册某通道的处理函数；多次注册以最后一次为准
  on(channel, handler) {
    if (CHANNELS.includes(channel)) {
      this.handlers[channel] = handler
    }
  }

  _dispatch(channel, raw) {
    const handler = this.handlers[channel]
    if (!handler) return
    if (channel === 'danmu') {
      // 弹幕为纯文本，直接透传（由 store 负责容错解析股吧格式）
      handler(raw)
      return
    }
    // 其余通道尝试 JSON 解析，失败则回退为原始字符串
    try {
      handler(JSON.parse(raw))
    } catch {
      handler(raw)
    }
  }

  // 显式断开（登出时调用）
  disconnect() {
    this.connected = false
    if (this.client) {
      this.client.deactivate()
      this.client = null
    }
  }
}

// 单例导出
export const ws = new WsClient()
