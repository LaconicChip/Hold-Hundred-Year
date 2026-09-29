// 消息元数据共享常量（来源标签 / 三态 / 单字色块头像 / NPC 名称映射）
// 由 StockMsgList 与 Messages 等页面共用，集中维护避免重复定义与配色漂移

// 来源枚举 -> 中文标签（与后端 MessageDTO.source 对齐）
export const SRC_LABEL = {
  AUTHORITY: '权威公告',
  MEDIA: '财经媒体',
  RUMOR: '股吧传闻',
  INSIDER: '神秘内幕',
  FUN: '玩梗',
  NPC: '营业部',
  SYSTEM: '系统'
}

// 来源枚举 -> 样式类（令牌色：红/黄/灰/紫/绿/蓝）
export const SRC_CLS = {
  AUTHORITY: 'src-auth',
  MEDIA: 'src-media',
  RUMOR: 'src-rumor',
  INSIDER: 'src-insider',
  FUN: 'src-fun',
  NPC: 'src-npc',
  SYSTEM: 'src-system'
}

export function srcLabel(s) {
  return SRC_LABEL[s] || s || '消息'
}
export function srcCls(s) {
  return SRC_CLS[s] || 'src-system'
}

// 三态枚举 -> 中文标签
export const TRUTH_LABEL = {
  PENDING: '未证实',
  CONFIRMED: '已证实',
  DENIED: '已辟谣'
}
export const TRUTH_CLS = {
  PENDING: 'tr-pending',
  CONFIRMED: 'tr-confirmed',
  DENIED: 'tr-denied'
}
export function truthLabel(t) {
  return TRUTH_LABEL[t] || t || ''
}
export function truthCls(t) {
  return TRUTH_CLS[t] || 'tr-pending'
}

// 非 NPC 来源的单字色块头像：背景 + 文字色
export const SRC_AVATAR = {
  AUTHORITY: { ch: '公', bg: 'var(--up)', fg: 'var(--on-brand)' },
  MEDIA: { ch: '媒', bg: 'var(--yellow)', fg: 'var(--ink)' },
  RUMOR: { ch: '传', bg: 'var(--src-rumor-fg)', fg: 'var(--on-brand)' },
  INSIDER: { ch: '内', bg: 'var(--av-chief)', fg: 'var(--on-brand)' },
  FUN: { ch: '梗', bg: 'var(--down)', fg: 'var(--on-brand)' },
  SYSTEM: { ch: '系', bg: 'var(--ink)', fg: 'var(--on-brand)' }
}

// NPC 中文名 -> 代码（NpcAvatar 的 npc 参数）
export const NPC_NAME_MAP = {
  张大妈: 'DAMA',
  隔壁老王: 'LAOWANG',
  保安老李: 'LAOLI',
  首席分析师: 'CHIEF',
  炒股养家居士: 'JUSHI',
  拆迁户老钱: 'LAOQIAN',
  '95后新基民': 'XINJIMIN',
  辞职炒股的程序猿: 'CHENGXUYUAN'
}

// 从文本（标题/内容）反查 NPC 代码：容错前缀匹配
export function resolveNpcCode(text) {
  if (!text) return null
  for (const [name, code] of Object.entries(NPC_NAME_MAP)) {
    if (text.includes(name)) return code
  }
  return null
}

// 来源筛选条选项（全部 + 7 类）
export const SRC_FILTERS = [
  { value: '', label: '全部' },
  { value: 'AUTHORITY', label: '权威公告' },
  { value: 'MEDIA', label: '财经媒体' },
  { value: 'RUMOR', label: '股吧传闻' },
  { value: 'INSIDER', label: '神秘内幕' },
  { value: 'FUN', label: '玩梗' },
  { value: 'NPC', label: '营业部' },
  { value: 'SYSTEM', label: '系统' }
]

// ============================================================
// 呈现通道（spec §12.1 消息中心之外，重要消息要"当面"告诉玩家）
//   BANNER 头条大弹幕：横贯屏幕的粗体大字，慢速飘过，pointer-events:none，绝不打断操作
//   POPUP  浮层提醒：右下角堆叠卡片，6 秒自动收起，可点进消息中心
//   FEED   普通消息：只在消息中心排队，不打扰
// 判定只看 source/type/title，后端不改协议，REST 列表与 WS 推送共用同一套规则。
// ============================================================
export const CHANNEL = { BANNER: 'banner', POPUP: 'popup', FEED: 'feed' }

// 市场级大事：值得全屏通报一次
const BANNER_HINTS = ['千股跌停', '千股涨停', '熔断', '救市', '崩盘', '制裁', '黑7月', '强制平仓', '停牌核查']
// 账户级但需要立刻知道：追保/强平/停牌/除权/中签/上市/纪元切换/内幕
const POPUP_SOURCES = ['SYSTEM', 'INSIDER']

export function channelOf(m) {
  if (!m) return CHANNEL.FEED
  const source = m.source || ''
  const title = m.title || ''
  const body = m.content || ''
  if (source === 'AUTHORITY') return CHANNEL.BANNER
  if (BANNER_HINTS.some((h) => title.includes(h) || body.includes(h))) return CHANNEL.BANNER
  if (POPUP_SOURCES.includes(source)) return CHANNEL.POPUP
  if (m.followupOf != null) return CHANNEL.POPUP // 传闻被证实/辟谣的追报，提醒一次
  return CHANNEL.FEED
}

// 语义配色（红涨绿跌，spec §12）：利好=红、利空=绿、中性=墨
const UP_HINTS = ['涨停', '新高', '救市', '反弹', '中签', '盈利', '超预期', '分红', '回暖', '放量', '转正', '增长', '落地']
const DOWN_HINTS = ['跌停', '崩盘', '爆仓', '平仓', '停牌', '核查', '制裁', '风险', '下滑', '亏损', '承压', '跳水', '腰斩', '过剩', '降价', '撤单', '停工', '违约']

export function toneOf(text) {
  const s = text || ''
  if (DOWN_HINTS.some((h) => s.includes(h))) return 'down'
  if (UP_HINTS.some((h) => s.includes(h))) return 'up'
  return 'ink'
}
