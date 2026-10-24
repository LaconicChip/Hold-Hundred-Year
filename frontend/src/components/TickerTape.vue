<template>
  <div class="ticker night">
    <span class="tk-label">
      <SvgIcon name="alert" class="tk-ic" />
      盘面
    </span>
    <div class="tk-view">
      <div class="track" :style="trackStyle">
        <span v-for="(it, i) in doubled" :key="i" class="tk-item" :class="it.cls">
          <b class="tk-cat">{{ it.cat }}</b>
          <i v-if="it.dir !== 'none'" class="dot" :class="it.dir === 'up' ? 'r' : 'g'"></i>
          {{ it.text }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useQuotesStore } from '@/stores/quotes'
import { useMarketStore } from '@/stores/market'
import { useUserStore } from '@/stores/user'
import { useMessagesStore } from '@/stores/messages'
import { fmtMoney, fmtPct } from '@/utils/format'
import SvgIcon from './SvgIcon.vue'

const quotes = useQuotesStore()
const market = useMarketStore()
const user = useUserStore()
const messages = useMessagesStore()

/**
 * 快讯条只放"看了能改变操作"的信息，一条废话都不留：
 * ① 先报钱的风险：持仓跌停封死/停牌出不去、担保比例逼近追保线、高利贷待还；
 * ② 市场宽度与涨跌停封死家数（行情表要自己数，这里一句话）；
 * ③ 指数与纪元、当前时段与剩余时间；④ 领涨领跌、最强最弱板块、连板高度；
 * ⑤ 自己的持仓盈亏、仓位、异动和今天关于持仓的消息。
 *
 * 旧版把 EVENT 消息标题和最近 6 条通知全塞进来滚，等于把消息中心又抄一遍：
 * 玩家不会看第二眼，还把①②③④淹掉了。消息该去的地方是消息中心和铃铛，不是这里。
 */
const MAX_ITEMS = 15

const items = computed(() => {
  const rows = quotes.quoteList
  const out = []
  const add = (cat, text, dir = 'none') => {
    if (text) out.push({ cat, text, dir, cls: dir === 'up' ? 'tk-up' : dir === 'down' ? 'tk-down' : '' })
  }
  if (!rows.length) {
    add('盘面', '行情还没就绪')
    return finish(out)
  }

  const pct = (r) => Number(r.changePct) || 0
  const active = rows.filter((r) => !r.halted)
  const up = active.filter((r) => pct(r) > 0.01).length
  const down = active.filter((r) => pct(r) < -0.01).length
  const flat = active.length - up - down
  const sealedUp = rows.filter((r) => r.sealed === 'UP').length
  const sealedDown = rows.filter((r) => r.sealed === 'DOWN').length
  const halted = rows.filter((r) => r.halted).length
  // 涨跌停价由后端按本局模式（标准/快速、ST）算好，不能用写死的 9.8%
  const atLimitUp = rows.filter((r) => r.limitUp > 0 && r.current >= r.limitUp - 0.005).length
  const atLimitDown = rows.filter((r) => r.limitDown > 0 && r.current <= r.limitDown + 0.005).length

  const held = holdingsView(rows)

  // 先报"钱要出事"的：持仓卖不出去、担保比例逼近红线、高利贷待还
  if (held.stuck > 0) {
    add('风险', `${held.stuck} 只持仓出不去（跌停封死/停牌）`, 'down')
  }
  if (held.marginWarning) {
    add('风险', held.marginWarning.text, held.marginWarning.dir)
  }
  if (held.sharkDebt > 0) {
    add('风险', `高利贷待还 ¥${fmtMoney(held.sharkDebt)}`, 'down')
  }

  add('宽度', `涨 ${up} · 跌 ${down} · 平 ${flat}`, up > down ? 'up' : down > up ? 'down' : 'none')
  add(
    '涨跌停',
    `涨停 ${atLimitUp}（封死 ${sealedUp}）· 跌停 ${atLimitDown}（封死 ${sealedDown}）· 停牌 ${halted}`,
    atLimitUp > atLimitDown ? 'up' : atLimitDown > atLimitUp ? 'down' : 'none'
  )
  if (market.indexValue > 0) {
    const indexPct = market.indexDayPct || 0
    add(
      '指数',
      `百年指数 ${market.indexValue.toFixed(2)}（${fmtPct(indexPct)}）`
        + (market.eraName ? ` · ${market.eraName}` : ''),
      indexPct > 0 ? 'up' : indexPct < 0 ? 'down' : 'none'
    )
  }
  const session = sessionText()
  if (session) add('时段', session)

  const sorted = [...active].sort((a, b) => pct(b) - pct(a))
  const top = sorted.filter((r) => pct(r) > 0).slice(0, 2)
  const bottom = sorted.filter((r) => pct(r) < 0).slice(-2).reverse()
  if (top.length) {
    add('领涨', top.map((r) => `${r.name} ${fmtPct(pct(r))}`).join(' · '), 'up')
  }
  if (bottom.length) {
    add('领跌', bottom.map((r) => `${r.name} ${fmtPct(pct(r))}`).join(' · '), 'down')
  }
  const lead = sectorMoves(rows)
  if (lead.best) {
    add('最强', `${lead.best.sector} ${fmtPct(lead.best.avg)}（${lead.best.count} 只）`, 'up')
  }
  if (lead.worst) {
    add('最弱', `${lead.worst.sector} ${fmtPct(lead.worst.avg)}（${lead.worst.count} 只）`, 'down')
  }
  const streak = streakView(rows)
  add('连板', streak.text, streak.dir)

  if (held.count > 0) {
    add(
      '持仓',
      `${held.count} 只 · 浮盈 ${signedMoney(held.profit)}（${fmtPct(held.profitPct)}）`,
      held.profit > 0 ? 'up' : held.profit < 0 ? 'down' : 'none'
    )
    if (held.positionPct != null) {
      add('仓位', `${held.positionPct.toFixed(0)}% · 现金 ${(100 - held.positionPct).toFixed(0)}%`)
    }
  } else {
    add('持仓', `空仓${held.hasAssets ? ` · 现金 ¥${fmtMoney(held.cash)}` : ''}`)
  }

  // 持仓异动：只报自己的，且只报值得动手的那几只
  for (const r of held.movers) {
    const note = r.sealed === 'DOWN' ? '跌停封死，卖不出' : r.sealed === 'UP' ? '涨停封死' : r.halted ? '停牌' : `现价 ${r.current.toFixed(2)}`
    add('异动', `${r.name} ${fmtPct(pct(r))} · ${note}`, pct(r) > 0 ? 'up' : pct(r) < 0 ? 'down' : 'none')
  }
  const latest = holdingMessage()
  if (latest) add('消息', clip(latest.title || latest.content || '持仓有新消息', 28))

  if (!out.length) add('盘面', '暂无异动，一切正常')
  return finish(out)
})

function finish(out) {
  return out.slice(0, MAX_ITEMS).map((it, i) => ({ ...it, key: i }))
}

function signedMoney(v) {
  const n = Number(v) || 0
  return `${n > 0 ? '+' : n < 0 ? '-' : ''}¥${fmtMoney(Math.abs(n))}`
}

function clip(text, limit) {
  const value = String(text || '')
  return value.length > limit ? `${value.slice(0, limit)}…` : value
}

function sessionText() {
  const state = market.marketState
  const remain = clock(market.countdownTicks * (market.tickMs || 1000))
  if (state === 'MORNING_OPEN') return `上午盘 · 剩余 ${remain}`
  if (state === 'AFTERNOON_OPEN') return `下午盘 · 剩余 ${remain}`
  if (state === 'LUNCH_BREAK') return '午休 · 13:00 开盘'
  if (state === 'SETTLING') return '结算中'
  if (state === 'CLOSED') return `已收盘 · 第 ${market.currentDay} 日`
  if (state === 'NOT_STARTED') return '未开盘'
  if (state === 'BANKRUPT' || state === 'FINISHED') return '本局已结束'
  return ''
}

function clock(ms) {
  const total = Math.max(0, Math.round((Number(ms) || 0) / 1000))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

/** 持仓的实时口径：现价取行情板，成本/股数取持仓表，现金/负债取资产看板。 */
function holdingsView(rows) {
  const positions = user.positions || []
  const assets = user.assets || null
  const cash = assets ? Number(assets.cash) || 0 : 0
  const debt = assets ? Number(assets.debt) || 0 : 0
  const sharkDebt = assets ? Number(assets.sharkDebt) || 0 : 0
  const financeDebt = Math.max(0, debt - sharkDebt)
  let marketValue = 0
  let cost = 0
  let profit = 0
  const heldRows = []
  for (const p of positions) {
    const row = rows.find((r) => r.stockId === p.stockId) || null
    const current = row ? row.current : Number(p.current) || 0
    const shares = Number(p.shares) || 0
    const costPrice = Number(p.costPrice) || 0
    const pl = (current - costPrice) * shares
    marketValue += current * shares
    cost += costPrice * shares
    profit += pl
    heldRows.push({ ...p, row, current, pl })
  }
  const total = cash + marketValue
  const positionPct = assets && total > 0 ? (marketValue / total) * 100 : null
  const guard = assets && financeDebt > 0 ? total / financeDebt : 0
  let marginWarning = null
  if (guard > 0 && guard < 140) {
    marginWarning = { text: `担保 ${guard.toFixed(0)}% · 已低于追保线 140%`, dir: 'down' }
  } else if (guard >= 140 && guard < 160) {
    marginWarning = { text: `担保 ${guard.toFixed(0)}% · 距追保线 ${(guard - 140).toFixed(0)} 个点`, dir: 'down' }
  }
  const stuck = heldRows.filter((h) => h.row && (h.row.sealed === 'DOWN' || h.row.halted)).length
  const movers = heldRows
    .filter((h) => h.row && (Math.abs(Number(h.row.changePct) || 0) >= 3 || h.row.sealed !== 'NONE' || h.row.halted))
    .sort((a, b) => Math.abs(Number(b.row.changePct) || 0) - Math.abs(Number(a.row.changePct) || 0))
    .slice(0, 2)
    .map((h) => h.row)
  return {
    count: positions.length,
    hasAssets: !!assets,
    cash,
    sharkDebt,
    profit,
    profitPct: cost > 0 ? (profit / cost) * 100 : 0,
    positionPct,
    stuck,
    marginWarning,
    movers
  }
}

function streakView(rows) {
  const streaks = rows.filter((r) => (r.limitUpStreak || 0) >= 2)
  if (!streaks.length) return { text: '0 只', dir: 'none' }
  const top = streaks.reduce(
    (best, r) => ((r.limitUpStreak || 0) > (best.limitUpStreak || 0) ? r : best),
    streaks[0]
  )
  return { text: `${streaks.length} 只 · 最高 ${top.limitUpStreak} 板（${top.name}）`, dir: 'up' }
}

/** 今天关于持仓的那条最新消息（单票或所属板块），只报一条，不把消息中心搬过来。 */
function holdingMessage() {
  const positions = user.positions || []
  if (!positions.length) return null
  const ids = new Set(positions.map((p) => Number(p.stockId)))
  const sectors = new Set(positions.map((p) => p.sector).filter(Boolean))
  return (messages.list || []).find((m) => {
    if (m.gameDay != null && market.currentDay != null && m.gameDay !== market.currentDay) return false
    if (m.stockId != null && ids.has(Number(m.stockId))) return true
    return !!(m.sector && sectors.has(m.sector))
  }) || null
}

/** 按板块平均涨跌幅找出最强/最弱的一条主线（少于 2 只票的板块不参与，避免单点噪声）。 */
function sectorMoves(rows) {
  const by = {}
  for (const r of rows) {
    if (r.halted) continue
    const g = (by[r.sector] = by[r.sector] || { sector: r.sector, sum: 0, count: 0 })
    g.sum += r.changePct
    g.count += 1
  }
  const groups = Object.values(by)
    .filter((g) => g.count >= 2)
    .map((g) => ({ ...g, avg: g.sum / g.count }))
  if (!groups.length) return {}
  const sorted = [...groups].sort((a, b) => b.avg - a.avg)
  return { best: sorted[0], worst: sorted[sorted.length - 1] }
}

const trackStyle = computed(() => ({
  animationDuration: `${Math.max(30, items.value.length * 4)}s`
}))

// 复制两份首尾相接，配合 @keyframes marquee 平移 -50% 无缝滚动
const doubled = computed(() => items.value.concat(items.value))
</script>
<style scoped>
.ticker {
  display: flex;
  align-items: stretch;
  overflow: hidden;
}
/* 左侧固定标签 */
.tk-label {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 var(--sp-3);
  font-family: var(--font-body);
  font-weight: 800;
  font-size: var(--fs-xs);
  letter-spacing: 0.5px;
  color: var(--ink);
  background: var(--yellow);
  border-right: 1.5px solid var(--ink);
}
.tk-ic { width: 1em; height: 1em; }

.tk-view { flex: 1; min-width: 0; overflow: hidden; }
.track {
  display: inline-block;
  padding: 7px 0;
  white-space: nowrap;
  animation: marquee 30s linear infinite;
}
/* 悬停暂停：滚动的信息再多，玩家想细看时也得停得住 */
.ticker:hover .track { animation-play-state: paused; }
.tk-item {
  display: inline-block;
  white-space: nowrap;
  margin-right: 48px;
  color: var(--on-night);
  font-size: var(--fs-sm);
}
/* 类别小章：盘面 / 指数 / 主线 / 持仓 / 风险，一眼知道这条在说什么 */
.tk-cat {
  font-weight: 700;
  font-size: var(--fs-xs);
  color: var(--yellow);
  margin-right: 6px;
}
.tk-tag {
  font-weight: 700;
  margin-right: 2px;
}
.tk-up { color: var(--on-night-up); }
.tk-down { color: var(--on-night-down); }
.tk-item .dot { border-color: rgba(255, 255, 255, 0.45); }

@keyframes marquee {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}

/* 尊重系统级减弱动效：关闭动画，改为静态换行展示 */
@media (prefers-reduced-motion: reduce) {
  .ticker { overflow: visible; }
  .tk-view { overflow: visible; }
  .track { animation: none; white-space: normal; }
  .tk-item { display: inline-block; margin-right: 16px; margin-bottom: 4px; }
}
</style>
