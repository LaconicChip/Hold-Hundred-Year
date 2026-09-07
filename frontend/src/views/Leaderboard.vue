<template>
  <div class="page">
    <!-- 页头：标题 + 日期切换 -->
    <header class="page-head">
      <div>
        <h1 class="page-title">
          <SvgIcon name="trophy" class="title-ic" />营业部排行榜
        </h1>
        <p class="page-desc">按当日净资产降序 · 本金 100,000.00</p>
      </div>
      <div class="day-switch">
        <button class="chip" :disabled="!day || day <= 1 || loading" @click="shift(-1)">
          <SvgIcon name="chevron-left" class="chip-ic" />前一日
        </button>
        <span class="day-text num">第 {{ day || '—' }} 天</span>
        <button class="chip" :disabled="!day || loading" @click="shift(1)">
          后一日<SvgIcon name="chevron-right" class="chip-ic" />
        </button>
      </div>
    </header>

    <!-- 木质榜单 -->
    <section class="wood-card">
      <div class="plaque">百年证券营业部 · 英雄榜</div>

      <div v-if="loading" class="lb-empty">加载中…</div>
      <div v-else-if="rows.length === 0" class="empty lb-empty">
        <SvgIcon name="bear" class="lb-mascot" />
        <span class="empty-title">尚无排行数据</span>
        <span class="empty-desc">收盘结算后才会生成当日榜单</span>
      </div>

      <div v-else class="lb-body">
        <div
          v-for="(r, i) in rows"
          :key="r.name + i"
          class="rank-row"
          :class="{ player: r.isPlayer, gone: isGone(r) }"
        >
          <!-- 名次：前三带奖牌贴纸 -->
          <span class="rank-no" :class="{ top: i < 3 && !isGone(r) }">
            <template v-if="isGone(r)">—</template>
            <template v-else-if="i === 0">冠</template>
            <template v-else-if="i === 1">亚</template>
            <template v-else-if="i === 2">季</template>
            <template v-else>{{ i + 1 }}</template>
          </span>

          <span class="rank-name">
            <!-- 玩家行固定红底“您”，NPC 按中文名映射 NpcAvatar -->
            <span v-if="r.isPlayer" class="avatar you">您</span>
            <NpcAvatar
              v-else
              :npc="npcCodeOf(r.name)"
              :size="32"
              :class="{ dimmed: isGone(r) }"
            />
            <span class="rank-label" :class="{ 'player-name': r.isPlayer }">
              {{ r.isPlayer ? '您（持有一百年）' : r.name }}
            </span>
            <span v-if="statusText(r)" class="tag" :class="{ 'tag-danger': isGone(r) }">
              {{ statusText(r) }}
            </span>
          </span>

          <!-- 天台/销户：行尾灰字；否则资产 + 收益率 -->
          <span v-if="isGone(r)" class="gone">{{ r.status }}</span>
          <template v-else>
            <b class="num asset">{{ fmtMoney(r.asset) }}</b>
            <span class="num ret" :class="pctCls(retPct(r))">{{ fmtPct(retPct(r)) }}</span>
          </template>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useMarketStore } from '@/stores/market'
import NpcAvatar from '@/components/NpcAvatar.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { resolveNpcCode } from '@/constants/messageMeta'

const market = useMarketStore()

// 基期本金（NPC 与玩家同为 100000，用于计算收益率）
const BASE_CAPITAL = 100000

const day = ref(0) // 0 = 尚未确定，首次请求缺省取最近
const rows = ref([])
const loading = ref(false)

// 名字反查 NPC 代码；查不到给 SYSTEM（NpcAvatar 走 hash 配色兜底）
function npcCodeOf(name) {
  return resolveNpcCode(name || '') || 'SYSTEM'
}

// 天台排队 / 已销户 / 爆仓 视为出局行
function isGone(r) {
  return /天台|销户|爆仓/.test(r.status || '')
}

// 状态本地化：ACTIVE 是在市常态，不展示；其余给出中文
function statusText(r) {
  const s = r.status || ''
  if (/天台|销户|爆仓/.test(s)) return s
  if (/^ACTIVE$/i.test(s)) return ''
  return s || ''
}

function retPct(r) {
  return (r.asset / BASE_CAPITAL - 1) * 100
}
function pctCls(v) {
  const n = +v
  return n > 0 ? 'up' : n < 0 ? 'down' : 'flat'
}
function fmtPct(v) {
  const n = +v
  if (!isFinite(n)) return '0.00%'
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}
function fmtMoney(v) {
  const n = +v
  if (!isFinite(n)) return '--'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load(gameDay) {
  loading.value = true
  try {
    const res = await market.fetchLeaderboard(gameDay || undefined)
    rows.value = res && res.rows ? res.rows : []
    if (res && res.gameDay) day.value = res.gameDay
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function shift(delta) {
  const target = day.value + delta
  if (target < 1) return
  day.value = target
  load(target)
}

onMounted(() => load(0))
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  max-width: 860px;
}
.title-ic { width: 0.85em; height: 0.85em; color: var(--brand); }

/* ---------- 日期切换 ---------- */
.day-switch {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-family: var(--font-fun);
  font-size: var(--fs-sm);
  border: var(--border);
  background: var(--card);
  border-radius: var(--radius-pill);
  padding: 3px var(--sp-3);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  transition: 0.08s;
}
.chip:hover:not(:disabled) {
  background: var(--hover);
  transform: translate(-1px, -1px);
  box-shadow: var(--shadow);
}
.chip:disabled { opacity: 0.45; cursor: not-allowed; }
.chip-ic { width: 12px; height: 12px; }
.day-text {
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
  white-space: nowrap;
  min-width: 62px;
  text-align: center;
}

/* ---------- 木质榜单 ---------- */
.wood-card {
  background: var(--wood-pale);
  border: var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  padding: var(--sp-3);
}
.plaque {
  font-family: var(--font-fun);
  font-size: var(--fs-2xl);
  text-align: center;
  color: var(--wood-text);
  background: var(--wood-deep);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: var(--sp-2) var(--sp-3);
  letter-spacing: 2px;
  margin-bottom: var(--sp-3);
}
.lb-body {
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  padding: var(--sp-1) var(--sp-2);
}
.lb-empty {
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  color: var(--ink-3);
  font-size: var(--fs-base);
  padding: var(--sp-8) var(--sp-3);
  text-align: center;
}
.lb-mascot { width: 44px; height: 44px; opacity: 0.8; }

/* 行（style-tile 第八节） */
.rank-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-2);
  border-bottom: 1px dashed var(--dash);
  border-left: 3px solid transparent;
}
.rank-row:last-child { border-bottom: none; }
.rank-row.player {
  background: var(--player-row);
  border-left-color: var(--brand);
  border-radius: var(--radius-sm);
  font-weight: 700;
}
.rank-row.gone { opacity: 0.55; }
.rank-no {
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
  width: 34px;
  text-align: center;
  flex: none;
}
.rank-no.top { color: var(--brand); }
.rank-name {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}
.rank-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.player-name { font-weight: 700; }
.avatar {
  width: 32px;
  height: 32px;
  min-width: 32px;
  flex: none;
}
.avatar.you { background: var(--brand); }
.dimmed { opacity: 0.45; }
.tag-danger {
  color: var(--up);
  border-color: var(--up);
}
.gone {
  color: var(--ink-3);
  font-size: var(--fs-sm);
  white-space: nowrap;
}
.asset { font-size: var(--fs-base); white-space: nowrap; }
.ret {
  font-size: var(--fs-sm);
  white-space: nowrap;
  width: 76px;
  text-align: right;
  flex: none;
}

@media (max-width: 700px) {
  .page-head { flex-direction: column; align-items: flex-start; }
  .ret { width: 66px; }
}
</style>
