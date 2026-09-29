// 运行时读取 CSS 设计令牌（供 ECharts / Canvas 等无法直接用 var() 的场景）
// 权威色值仍在 tokens.css，此处只做镜像读取，避免硬编码色值散落。
const FALLBACK = {
  '--paper': '#f6f1e7',
  '--card': '#fffdf7',
  '--ink': '#2b2620',
  '--ink-2': '#6f675c',
  '--ink-3': '#a89f91',
  '--up': '#ef3b3b',
  '--down': '#12a150',
  '--brand': '#ff2436',
  '--orange': '#ff652b',
  '--yellow': '#ffc53d',
  '--seal': '#d93025',
  '--blue': '#3a7bd5',
  '--dash': '#d8cfbc',
  '--hover': '#fdf6e3'
}

const cache = {}

// 读取一个令牌；SSR/未挂载场景回退常量
export function token(name) {
  if (cache[name] != null) return cache[name]
  let v = ''
  if (typeof window !== 'undefined' && window.getComputedStyle) {
    v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  }
  cache[name] = v || FALLBACK[name] || ''
  return cache[name]
}

