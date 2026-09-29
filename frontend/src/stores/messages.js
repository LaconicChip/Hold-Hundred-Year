// 消息中心 store（消息列表 / 弹幕 / 通知）
// 新消息一律由后端在落库的同一条路径上 WS 推送过来（MessageService.publish → /user/queue/messages），
// 前端不再轮询发现新消息；只有"连接建立之前的历史"由 syncUnread 补一次。
import { defineStore } from 'pinia'
import { fetchMessages, markMessageRead, markAllRead } from '@/api/message'
import { useUserStore } from './user'
import { channelOf, toneOf, CHANNEL } from '@/constants/messageMeta'

const DANMU_MAX = 30 // 弹幕环形缓冲上限（只用于回看；飘动按 seq 取增量，不再依赖数组长度）
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
    danmu: [], // 弹幕环形缓冲：[{seq,text,user?}]，仅保留最近 DANMU_MAX 条
    danmuSeq: 0, // 弹幕自增序号
    danmuVersion: 0, // 每来一条 +1：组件 watch 它，而不是 watch 会封顶的数组长度
    notifications: [], // 轻量通知对象（字段不定）
    banners: [], // 头条大弹幕队列（新消息按通道入队，播一条移一条）
    popups: [], // 右下角浮层队列（最多 3 条，组件侧定时收起）
  }),

  getters: {
    // 未读数（实时由 list 计算，保证与读状态一致）
    unreadCount(state) {
      return state.list.filter((m) => !m.isRead).length
    }
  },

  actions: {
    /**
     * 进主布局时补一次未读：新消息由 WS 实时推（见 pushMessage），但"这次连接建立之前"
     * 落库的那些不会推过来，靠这一次拉取兜住铃铛角标。只登记、不播报——
     * 刷新页面把历史未读全弹一遍是灾难。
     */
    async syncUnread() {
      try {
        const page = await fetchMessages({ unreadOnly: true, size: 20 })
        for (const m of ((page && page.records) || []).slice().reverse()) {
          if (m && m.id != null && !this.list.some((x) => x.id === m.id)) {
            this.list.unshift(m)
            this.total += 1
          }
        }
      } catch {
        // 拉取失败不阻塞页面：WS 推送与消息页自己的拉取都不依赖这一次
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

    /** WS /user/queue/messages 推来的单条消息：置顶入列（角标随动）并按通道决定"当面提醒"强度。 */
    pushMessage(dto) {
      if (!dto || dto.id == null) return
      if (this.list.some((m) => m.id === dto.id)) return
      this.list.unshift(dto)
      this.total += 1
      this.present(dto)
    },

    // 这只票是否在玩家持仓里（读 user store 的持仓，与行情表高亮同一份数据）
    holdsStock(stockId) {
      try {
        return useUserStore().positions.some((p) => p && p.stockId === stockId)
      } catch {
        return false // store 未就绪时按"非持仓"处理，绝不因此抛错
      }
    },

    // 通道分发：BANNER 进大弹幕队列、POPUP 进浮层（持仓股走醒目档）、FEED 什么都不做。
    // 只在实时推送时调用——历史列表加载（fetchMessages）不补播，避免刷新就刷屏。
    present(m) {
      const channel = channelOf(m)
      if (channel === CHANNEL.FEED) return
      const item = {
        id: m.id,
        title: m.title || '消息',
        text: m.content || '',
        stockId: m.stockId != null ? m.stockId : null,
        tone: toneOf((m.title || '') + (m.content || '')),
        // 自己持有的票出事，值得一次更醒目的弹窗；别的票只在右下角轻提示
        hold: m.stockId != null && this.holdsStock(m.stockId)
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
      this.danmuSeq += 1
      this.danmu.push({ id: this.danmuSeq, seq: this.danmuSeq, text: content, user })
      // 维持环形缓冲上限
      if (this.danmu.length > DANMU_MAX) {
        this.danmu.splice(0, this.danmu.length - DANMU_MAX)
      }
      this.danmuVersion += 1 // 通知消费方按 seq 取增量（数组封顶不影响）
    },

    // 轻量通知入列（容错 title/content/kind 字段）
    pushNotification(obj) {
      if (obj == null) return
      this.notifications.push(obj)
    }
  }
})
