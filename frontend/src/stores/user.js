// 用户 store（登录态 / 资产 / WS 接线）
import { defineStore } from 'pinia'
import { login as apiLogin, register as apiRegister, fetchMe } from '@/api/auth'
import { fetchAssets } from '@/api/me'
import { setToken, getToken } from '@/api/http'
import { ws } from '@/api/ws'
import { useQuotesStore } from './quotes'
import { useMessagesStore } from './messages'
import { useMarketStore } from './market'

// 把 WS 四通道接到各 store 的推送方法（绑定一次即可，重连后无需重复）
function bindWsToStores() {
  const quotes = useQuotesStore()
  const messages = useMessagesStore()
  ws.on('quotes', (snap) => quotes.applySnapshot(snap))
  ws.on('messages', (dto) => messages.pushMessage(dto))
  ws.on('notifications', (obj) => messages.pushNotification(obj))
  ws.on('danmu', (text) => messages.pushDanmu(text))
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    userId: 0,
    username: '',
    assets: null // 资产对象（mock 或真实）
  }),

  getters: {
    isLoggedIn(state) {
      return !!state.token
    }
  },

  actions: {
    // 登录：成功后存 token + 用户信息，拉 /api/me、拉资产、连接 WS
    async login(username, password) {
      const data = await apiLogin(username, password)
      this._applyAuth(data)
      await this._afterLogin()
      return data
    },

    // 注册：成功后自动登录
    async register(username, password) {
      await apiRegister(username, password)
      return this.login(username, password)
    },

    // 写入鉴权信息（token 持久化）
    _applyAuth(data) {
      if (data.token) {
        setToken(data.token)
        this.token = data.token
      }
      if (data.userId != null) this.userId = data.userId
      if (data.username) this.username = data.username
    },

    // 登录后的补充动作
    async _afterLogin() {
      try {
        const me = await fetchMe()
        if (me.userId != null) this.userId = me.userId
        if (me.username) this.username = me.username
      } catch {
        // /api/me 失败不阻塞主流程
      }
      await this.fetchAssets()
      // 连接 WS 并接线
      if (this.token) {
        bindWsToStores()
        ws.connect(this.token)
      }
    },

    // 拉取资产（mock 开关在 me.js 内）
    async fetchAssets() {
      try {
        this.assets = await fetchAssets()
      } catch {
        this.assets = null
      }
    },

    // 登出：清 token、断 WS、重置所有 store
    logout() {
      setToken('')
      this.token = ''
      this.userId = 0
      this.username = ''
      this.assets = null
      ws.disconnect()

      // 重置其它 store
      try {
        useQuotesStore().$reset()
      } catch {}
      try {
        useMessagesStore().$reset()
      } catch {}
      try {
        useMarketStore().$reset()
      } catch {}
    }
  }
})
