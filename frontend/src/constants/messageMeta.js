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
  AUTHORITY: { ch: '公', bg: 'var(--up)', fg: '#fff' },
  MEDIA: { ch: '媒', bg: 'var(--yellow)', fg: 'var(--ink)' },
  RUMOR: { ch: '传', bg: '#888', fg: '#fff' },
  INSIDER: { ch: '内', bg: '#8a6fd8', fg: '#fff' },
  FUN: { ch: '梗', bg: 'var(--down)', fg: '#fff' },
  SYSTEM: { ch: '系', bg: 'var(--ink)', fg: '#fff' }
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
