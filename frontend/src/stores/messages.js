// 消息中心 store（消息列表 / 弹幕 / 通知）
import { defineStore } from 'pinia'
import { fetchMessages, markMessageRead, markAllRead } from '@/api/message'
import { channelOf, toneOf, CHANNEL } from '@/constants/messageMeta'

const DANMU_MAX = 30 // 弹幕环形缓冲上限
const BANNER_MAX = 5 // 大弹幕队列上限（超了丢最旧的，绝不无限堆）
const POPUP_MAX = 3 // 同屏浮层上限
// 轻量通知 kind -> 标题（后端 pushNotification 只带 kind 时兜底）
const NOTIFICATION_TITLE = {
  IPO_SUBSCRIBE: '新股申购',
  IPO_WIN: '中签结果',
  IPO_SETTLED: '打新卖出',
  MARGIN_CALL: '追加保证金',
  LIQUIDATION: '强制平仓',
  HALT: '停牌核查',
  ERA_SWITCH: '纪元切换'
}

export const useMessagesStore = defineStore('messages', {
  state: () => ({
    list: [], // MessageDTO 列表
    total: 0,
    danmu: [], // 弹幕环形缓冲：[{id,text,user?}]
    notifications: [], // 轻量通知对象（字段不定）
    banners: [], // 头条大弹幕队列（新消息按通道入队，播一条移一条）
    popups: [], // 右下角浮层队列（最多 3 条，组件侧定时收起）
    presented: new Set(), // 已"当面提醒"过的消息 id，防重复播报
    primed: false // 首轮只登记不播报：刷新页面不该把历史未读全弹一遍
  }),

  getters: {
    // 未读数（实时由 list 计算，保证与读状态一致）
    unreadCount(state) {
      return state.list.filter((m) => !m.isRead).length
    }
  },

  actions: {
    // 轮询补获新消息并走"当面提醒"通道。
    // 为什么轮询而不是 WS：后端 /user/queue/messages 通道虽有，但全仓没有任何生产调用点
    // （消息只落库不推送），逐个写入方补推送要改 10 个服务；这里 5 秒拉一次未读，
    // 覆盖所有来源（事件/结算/追保/强平/停牌/除权/打新/纪元/NPC），一处解决。
    async pollAndPresent() {
      let page
      try {
        page = await fetchMessages({ unreadOnly: true, size: 20 })
      } catch {
        return // 拉取失败静默，下轮再试
      }
      const records = (page && page.records) || []
      const silent = !this.primed
      this.primed = true
      for (const m of records.slice().reverse()) {
        if (!m || m.id == null) continue
        if (!this.list.some((x) => x.id === m.id)) {
          this.list.unshift(m)
          this.total += 1
        }
        if (this.presented.has(m.id)) continue
        this.presented.add(m.id)
        if (!silent) this.present(m)
      }
    },

    // 拉取消息列表（支持过滤条件透传）
    // append=true 时仅追加 records（分页“加载更多”），不覆盖已有 list
    async fetchMessages(filters = {}) {
      const page = await fetchMessages(filters)
      const records = page.records || []
      if (filters.append) {
        // 去重（按 id）后追加，避免重复条目
        const seen = new Set(this.list.map((m) => m.id))
        for (const m of records) {
          if (!seen.has(m.id)) this.list.push(m)
        }
      } else {
        this.list = records
      }
      this.total = page.total != null ? page.total : this.list.length
      return page
    },

    async markRead(id) {
      await markMessageRead(id)
      const m = this.list.find((x) => x.id === id)
      if (m) m.isRead = true
    },

    async markAllRead() {
      await markAllRead()
      this.list.forEach((m) => {
        m.isRead = true
      })
    },

    // WS 推送的单条消息入列（置顶），并按通道决定"当面提醒"的强度
    pushMessage(dto) {
      if (!dto || dto.id == null) return
      if (this.list.some((m) => m.id === dto.id)) return
      this.list.unshift(dto)
      this.total += 1
      this.present(dto)
    },

    // 通道分发：BANNER 进大弹幕队列、POPUP 进右下角浮层、FEED 什么都不做。
    // 只在实时推送时调用——历史列表加载（fetchMessages）不补播，避免刷新就刷屏。
    present(m) {
      const channel = channelOf(m)
      if (channel === CHANNEL.FEED) return
      const item = {
        id: m.id,
        title: m.title || '消息',
        text: m.content || '',
        tone: toneOf((m.title || '') + (m.content || ''))
      }
      if (channel === CHANNEL.BANNER) {
        this.banners.push(item)
        if (this.banners.length > BANNER_MAX) this.banners.splice(0, this.banners.length - BANNER_MAX)
        return
      }
      this.popups.push(item)
      if (this.popups.length > POPUP_MAX) this.popups.splice(0, this.popups.length - POPUP_MAX)
    },

    // 大弹幕播完一条出队（组件动画结束回调）
    shiftBanner() {
      this.banners.shift()
    },

    // 浮层收起（超时或玩家点掉）
    dismissPopup(id) {
      this.popups = this.popups.filter((p) => p.id !== id)
    },

    // 弹幕入列：容错解析“股吧ID：内容”格式
    pushDanmu(text) {
      if (text == null) return
      const str = String(text)
      let user
      let content = str
      const idx = str.indexOf('：')
      if (idx > 0 && idx < 12) {
        user = str.slice(0, idx)
        content = str.slice(idx + 1)
      }
      this.danmu.push({ id: Date.now() + Math.random(), text: content, user })
      // 维持环形缓冲上限
      if (this.danmu.length > DANMU_MAX) {
        this.danmu.splice(0, this.danmu.length - DANMU_MAX)
      }
    },

    // 轻量通知入列（容错 title/content/kind 字段）
    pushNotification(obj) {
      if (obj == null) return
      this.notifications.push(obj)
    }
  }
})
