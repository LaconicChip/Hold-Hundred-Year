<template>
  <div class="lb-page">
    <!-- 页头：返回大盘 + 标题 + 日期切换 -->
    <header class="page-head card">
      <button class="back-btn" @click="goBack">
        <SvgIcon name="bank" class="back-ic" />返回大盘
      </button>
      <h1 class="page-title">营业部排行榜</h1>
      <div class="day-switch">
        <button class="day-btn" :disabled="!day || day <= 1 || loading" @click="shift(-1)">
          前一日
        </button>
        <span class="day-text">第 {{ day || '—' }} 天</span>
        <button class="day-btn" :disabled="!day || loading" @click="shift(1)">
          后一日
        </button>
      </div>
    </header>

    <!-- 木质榜单 -->
    <section class="wood-card">
      <div class="plaque">百年证券营业部 · 英雄榜</div>

      <div v-if="loading" class="lb-empty">加载中…</div>
      <div v-else-if="rows.length === 0" class="lb-empty">尚无排行数据</div>

      <div v-else class="lb-body">
        <div
          v-for="(r, i) in rows"
          :key="r.name + i"
          class="rank-row"
          :class="{ player: r.isPlayer }"
        >
          <span class="rank-no" :class="{ top: i < 3 && !isGone(r) }">
            {{ isGone(r) ? '—' : i + 1 }}
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
            <span :class="{ 'player-name': r.isPlayer }">{{ r.isPlayer ? '您（持有一百年）' : r.name }}</span>
            <span v-if="r.status && !isGone(r)" class="tag-style">{{ r.status }}</span>
          </span>
          <!-- 天台/销户：行尾灰字；否则资产数字 -->
          <span v-if="isGone(r)" class="gone">{{ r.status }}</span>
          <b v-else class="num asset">{{ fmtMoney(r.asset) }}</b>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMarketStore } from '@/stores/market'
import NpcAvatar from '@/components/NpcAvatar.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { resolveNpcCode } from '@/constants/messageMeta'

const router = useRouter()
const market = useMarketStore()

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

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/market')
}

onMounted(() => load(0))
</script>

<style scoped>
.lb-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px 20px 80px;
}
/* 页头 */
.page-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 18px;
  flex-wrap: wrap;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font-fun);
  font-size: 15px;
  border: 2px solid var(--ink);
  background: var(--card);
  border-radius: 999px;
  padding: 6px 16px;
  box-shadow: 2px 2px 0 var(--ink);
  cursor: pointer;
  white-space: nowrap;
}
.back-btn:hover {
  transform: translate(-1px, -1px);
  box-shadow: 3px 3px 0 var(--ink);
}
.back-btn:active {
  transform: translate(2px, 2px);
  box-shadow: 1px 1px 0 var(--ink);
}
.back-ic {
  width: 16px;
  height: 16px;
}
.page-title {
  flex: 1;
  font-family: var(--font-fun);
  font-size: 30px;
  white-space: nowrap;
}
.day-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.day-btn {
  font-family: var(--font-fun);
  font-size: 13px;
  border: 2px solid var(--ink);
  background: var(--card);
  border-radius: 999px;
  padding: 4px 12px;
  box-shadow: 2px 2px 0 var(--ink);
  cursor: pointer;
}
.day-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.day-btn:not(:disabled):hover {
  transform: translate(-1px, -1px);
  box-shadow: 3px 3px 0 var(--ink);
}
.day-text {
  font-family: var(--font-fun);
  font-size: 16px;
  white-space: nowrap;
}
/* 木质榜单 */
.wood-card {
  margin-top: 16px;
  background: #e8d9b8;
  border: 2px solid var(--ink);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  padding: 14px;
}
.plaque {
  font-family: var(--font-fun);
  font-size: 22px;
  text-align: center;
  color: #f3e6c8;
  background: #6b4a2a;
  border: 2px solid var(--ink);
  border-radius: 10px;
  box-shadow: 2px 2px 0 var(--ink);
  padding: 8px 12px;
  letter-spacing: 2px;
  margin-bottom: 10px;
}
.lb-body {
  background: var(--card);
  border: 2px solid var(--ink);
  border-radius: 10px;
  padding: 4px 10px;
}
.lb-empty {
  background: var(--card);
  border: 2px solid var(--ink);
  border-radius: 10px;
  color: var(--ink-3);
  font-size: 14px;
  padding: 30px 10px;
  text-align: center;
}
/* 行（style-tile 第八节） */
.rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px dashed #d8cfbc;
}
.rank-row:last-child {
  border-bottom: none;
}
.rank-row.player {
  background: #ffedb0;
  border-radius: 8px;
  font-weight: 700;
}
.rank-no {
  font-family: var(--font-fun);
  font-size: 18px;
  width: 34px;
  text-align: center;
}
.rank-no.top {
  color: var(--brand);
}
.rank-name {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.player-name {
  font-weight: 700;
}
.avatar {
  width: 32px;
  height: 32px;
  min-width: 32px;
  border: 2px solid var(--ink);
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-fun);
  font-size: 14px;
  color: #fff;
}
.avatar.you {
  background: var(--brand);
}
.dimmed {
  opacity: 0.45;
}
.tag-style {
  font-size: 11px;
  color: var(--ink-2);
  border: 1px dashed var(--ink-3);
  border-radius: 6px;
  padding: 0 6px;
  white-space: nowrap;
}
.gone {
  color: var(--ink-3);
  font-size: 12px;
  white-space: nowrap;
}
.asset {
  font-size: 14px;
  white-space: nowrap;
}
</style>
