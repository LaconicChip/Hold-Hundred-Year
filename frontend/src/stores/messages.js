// 消息中心 store（消息列表 / 弹幕 / 通知）
import { defineStore } from 'pinia'
import { fetchMessages, markMessageRead, markAllRead } from '@/api/message'

const DANMU_MAX = 30 // 弹幕环形缓冲上限

export const useMessagesStore = defineStore('messages', {
  state: () => ({
    list: [], // MessageDTO 列表
    total: 0,
    danmu: [], // 弹幕环形缓冲：[{id,text,user?}]
    notifications: [] // 轻量通知对象（字段不定）
  }),

  getters: {
    // 未读数（实时由 list 计算，保证与读状态一致）
    unreadCount(state) {
      return state.list.filter((m) => !m.isRead).length
    }
  },

  actions: {
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

    // WS 推送的单条消息入列（置顶）
    pushMessage(dto) {
      if (!dto || dto.id == null) return
      if (this.list.some((m) => m.id === dto.id)) return
      this.list.unshift(dto)
      this.total += 1
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
