<template>
  <div class="ticker night">
    <span class="tk-label">
      <SvgIcon name="alert" class="tk-ic" />
      盘面
    </span>
    <div class="tk-view">
      <div class="track">
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
import SvgIcon from './SvgIcon.vue'

const quotes = useQuotesStore()
const market = useMarketStore()
const user = useUserStore()

/**
 * 快讯条只放"看了能改变操作"的四类信息，一条废话都不留：
 * ① 盘面温度（涨停/跌停/停牌家数——行情表要数才知道，这里一句话）；
 * ② 指数与纪元（今天该不该重仓）；③ 主线与拖累板块（钱在往哪儿走）；
 * ④ 你自己的持仓异动与"卖不出去"的风险（唯一真正要紧的一条）。
 *
 * 旧版把 EVENT 消息标题和最近 6 条通知全塞进来滚，等于把消息中心又抄一遍：
 * 玩家不会看第二眼，还把①②③④淹掉了。消息该去的地方是消息中心和铃铛，不是这里。
 */
const MAX_ITEMS = 8

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
  const sealedUp = rows.filter((r) => r.sealed === 'UP').length
  const sealedDown = rows.filter((r) => r.sealed === 'DOWN').length
  const halted = rows.filter((r) => r.halted).length
  const limitUp = rows.filter((r) => r.changePct >= 9.8).length
  const limitDown = rows.filter((r) => r.changePct <= -9.8).length
  add(
    '盘面',
    `涨停 ${limitUp} 家 · 跌停 ${limitDown} 家 · 封死 ${sealedUp + sealedDown} · 停牌 ${halted}`,
    limitUp > limitDown ? 'up' : limitDown > limitUp ? 'down' : 'none'
  )
  if (market.indexValue > 0) {
    const pct = market.indexDayPct || 0
    add(
      '指数',
      `百年指数 ${market.indexValue.toFixed(2)}（${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%）`
        + (market.eraName ? ` · ${market.eraName}` : ''),
      pct > 0 ? 'up' : pct < 0 ? 'down' : 'none'
    )
  }
  const lead = sectorMoves(rows)
  if (lead.best && lead.best.avg >= 1) {
    add('主线', `${lead.best.sector} ${sign(lead.best.avg)}%（${lead.best.count} 只）`, 'up')
  }
  if (lead.worst && lead.worst.avg <= -1) {
    add('拖累', `${lead.worst.sector} ${sign(lead.worst.avg)}%（${lead.worst.count} 只）`, 'down')
  }
  // 持仓异动：只报自己的，且只报值得动手的那几只
  const held = rows.filter((r) => user.heldMap[r.stockId])
  const moving = held
    .filter((r) => Math.abs(r.changePct) >= 3 || r.sealed !== 'NONE' || r.halted)
    .sort((a, b) => Math.abs(b.changePct) - Math.abs(a.changePct))
    .slice(0, 3)
  for (const r of moving) {
    const note = r.sealed === 'DOWN' ? '跌停封死，卖不出' : r.sealed === 'UP' ? '涨停封死' : r.halted ? '停牌' : `现价 ${r.current.toFixed(2)}`
    add('持仓', `${r.name} ${sign(r.changePct)}% · ${note}`, r.changePct > 0 ? 'up' : r.changePct < 0 ? 'down' : 'none')
  }
  const stuck = held.filter((r) => r.sealed === 'DOWN' || r.halted).length
  if (stuck > 0) {
    add('风险', `${stuck} 只持仓今天出不去（跌停封死或停牌）`, 'down')
  }
  if (!out.length) add('盘面', '暂无异动，一切正常')
  return finish(out)
})

function finish(out) {
  const capped = out.slice(0, MAX_ITEMS)
  return capped.map((it, i) => ({ ...it, key: i }))
}

function sign(v) {
  return (v > 0 ? '+' : '') + v.toFixed(2)
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

// 复制两份首尾相接，配合 @keyframes marquee 平移 -50% 无缝滚动
const doubled = computed(() => items.value.concat(items.value))
</script>
<style scoped>
.ticker {
  display: flex;
  align-items: stretch;
  overflow: hidden;
  box-shadow: var(--shadow);
  margin-top: var(--sp-3);
}
/* 左侧固定标签 */
.tk-label {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 var(--sp-3);
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--yellow);
  border-right: 2px solid var(--ink);
}
.tk-ic { width: 1em; height: 1em; }

.tk-view { flex: 1; min-width: 0; overflow: hidden; }
.track {
  display: inline-block;
  padding: 7px 0;
  animation: marquee 30s linear infinite;
}
.tk-item {
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
