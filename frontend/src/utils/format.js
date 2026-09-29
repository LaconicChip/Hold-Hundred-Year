// 涨跌配色与金额/百分比展示：跨页面共用的唯一口径（红涨绿跌，spec §12）
// 命名约定：默认版空值显示 0（表格里的"平"），*Dash 版空值显示 "--"（数据未到时不假装是 0）

// 红涨绿跌：涨红 / 跌绿 / 平墨色（配合 tokens.css 的 .up/.down/.flat）
export function priceCls(v) {
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return 'flat'
}

// 价格：缺值给全角破折号，其余两位小数
export function fmtPrice(v) {
  return v == null ? '—' : Number(v).toFixed(2)
}

// 百分比（带符号）：NaN/0 一律 "0.00%"
export function fmtPct(v) {
  const n = +v
  if (!n) return '0.00%'
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}

// 百分比（带符号）：仅缺值显示 "--"，真实 0 仍显示 "0.00%"
export function fmtPctDash(v) {
  const n = +v
  if (!isFinite(n)) return '--'
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}

// 千分位金额（两位小数）：缺值显示 "0.00"
export function fmtMoney(v) {
  const n = +v
  if (!isFinite(n)) return '0.00'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 千分位金额（两位小数）：缺值显示 "--"
export function fmtMoneyDash(v) {
  const n = +v
  if (!isFinite(n)) return '--'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
