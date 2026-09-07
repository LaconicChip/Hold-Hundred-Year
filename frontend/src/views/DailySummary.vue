<template>
  <div class="page">
    <!-- 页头（返回由侧栏承担） -->
    <header class="page-head">
      <div>
        <h1 class="page-title">
          <SvgIcon name="newspaper" class="title-ic" />收盘晚报
        </h1>
        <p class="page-desc">当日收官结算 · 明日开盘前必读</p>
      </div>
      <button v-if="data" class="btn btn-sm" @click="goRank">
        <SvgIcon name="trophy" class="btn-ic" />看看排名
      </button>
    </header>

    <div v-if="loading" class="card state-card">加载中…</div>
    <div v-else-if="!data" class="empty state-card">
      <SvgIcon name="bear" class="state-mascot" />
      <span class="empty-title">暂无晚报数据</span>
      <span class="empty-desc">跳过或收盘后，这里会印出当天的报纸</span>
    </div>

    <!-- 晚报报纸（style-tile 第九节） -->
    <section v-else class="newspaper">
      <!-- 右上斜印章：跑赢红章 / 跑输灰章 -->
      <div class="news-stamp">
        <SealStamp :text="beatIndex ? '跑赢大盘' : '跑输大盘'" :broken="!beatIndex" />
      </div>

      <h3>营业部晚报 · 第 {{ data.gameDay }} 期</h3>

      <!-- 三格数据 -->
      <div class="grid g3 stats">
        <div class="stat">
          <span class="lab">你的日收益</span>
          <span class="val" :class="pctCls(data.dayReturnPct)">{{ fmtPct(data.dayReturnPct) }}</span>
        </div>
        <div class="stat">
          <span class="lab">百年指数</span>
          <span class="val" :class="pctCls(data.indexDayPct)">{{ fmtPct(data.indexDayPct) }}</span>
        </div>
        <div class="stat">
          <span class="lab">净资产</span>
          <span class="val">{{ fmtMoney(data.netAsset) }}</span>
        </div>
      </div>

      <!-- 今日要闻 -->
      <div v-if="newsList.length" class="news-block">
        <h4 class="block-title">
          <SvgIcon name="note" class="block-ic" />今日要闻
        </h4>
        <p v-for="(n, i) in newsList" :key="i" class="news-line">
          <span class="news-idx num">{{ i + 1 }}</span>{{ n }}
        </p>
      </div>

      <!-- 结算提示（按类型配图标，取代裸枚举文本） -->
      <div v-if="noticeList.length" class="news-block">
        <h4 class="block-title">
          <SvgIcon name="bell" class="block-ic" />结算提示
        </h4>
        <p v-for="(n, i) in noticeList" :key="i" class="news-line">
          <SvgIcon :name="noticeIcon(n)" class="notice-ic" :class="noticeCls(n)" />{{ n }}
        </p>
      </div>

      <!-- 营业部点评 -->
      <div v-if="data.comment" class="comment">
        <SvgIcon name="bull" class="comment-mascot" />
        <p class="comment-text">{{ data.comment }}</p>
      </div>

      <!-- 明日提示 -->
      <div class="tomorrow">
        <span class="lab">
          <SvgIcon name="alert" class="lab-ic" />明日提示
        </span>
        <p class="tomorrow-text">
          当前纪元：<b>{{ data.eraName || '平稳年代' }}</b>
          <template v-if="guardRisk">
            ；融资担保比例 {{ (data.guardRatio * 100).toFixed(0) }}%，低于 140% 追保线，请注意追保风险。
          </template>
        </p>
      </div>

      <!-- 底部按钮 -->
      <div class="paper-actions">
        <button class="btn btn-primary btn-lg" :disabled="starting" @click="onNextDay">
          <SvgIcon name="play" />{{ starting ? '开市准备中…' : '开始下一交易日' }}
        </button>
        <button class="btn" @click="goMarket">
          <SvgIcon name="chart" />再看一眼行情
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useMarketStore } from '@/stores/market'
import SealStamp from '@/components/SealStamp.vue'
import SvgIcon from '@/components/SvgIcon.vue'

const route = useRoute()
const router = useRouter()
const market = useMarketStore()

const data = ref(null)
const loading = ref(false)
const starting = ref(false)

// 是否跑赢指数（决定印章样式）
const beatIndex = computed(
  () => data.value && +data.value.dayReturnPct > +data.value.indexDayPct
)
const newsList = computed(() => (data.value && data.value.news) || [])
const noticeList = computed(() => (data.value && data.value.notices) || [])
// 有融资且担保比例低于 1.4（140% 追保线）时提示风险
const guardRisk = computed(
  () => data.value && data.value.guardRatio > 0 && data.value.guardRatio < 1.4
)

// 结算提示按关键字配图标（避免裸枚举文本）
function noticeIcon(n) {
  const t = String(n || '')
  if (/追保/.test(t)) return 'alert'
  if (/强平|平仓/.test(t)) return 'alert'
  if (/中签|打新|申购/.test(t)) return 'ticket'
  if (/停牌/.test(t)) return 'door'
  if (/结算|收盘/.test(t)) return 'check'
  return 'info'
}
function noticeCls(n) {
  const t = String(n || '')
  if (/强平|平仓/.test(t)) return 'ic-danger'
  if (/追保/.test(t)) return 'ic-warn'
  if (/中签|打新|申购/.test(t)) return 'ic-blue'
  return ''
}

function pctCls(v) {
  const n = +v
  return n > 0 ? 'up' : n < 0 ? 'down' : 'flat'
}
function fmtPct(v) {
  const n = +v
  if (!isFinite(n)) return '--'
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}
function fmtMoney(v) {
  const n = +v
  if (!isFinite(n)) return '--'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

async function load() {
  loading.value = true
  try {
    const q = Number(route.query.gameDay)
    data.value = await market.fetchDailySummary(q > 0 ? q : undefined)
  } catch {
    data.value = null
  } finally {
    loading.value = false
  }
}

async function onNextDay() {
  if (starting.value) return
  starting.value = true
  try {
    await market.start()
    router.push('/market')
  } catch (e) {
    // 已开盘等情况直接回大盘即可
    ElMessage.warning(e?.message || '已回到大盘')
    router.push('/market')
  } finally {
    starting.value = false
  }
}

function goMarket() {
  router.push('/market')
}
function goRank() {
  router.push({ path: '/leaderboard', query: data.value ? { gameDay: data.value.gameDay } : {} })
}

onMounted(load)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  max-width: 860px;
}
.title-ic { width: 0.85em; height: 0.85em; color: var(--brand); }
.btn-ic { width: 1em; height: 1em; }

.state-card { padding: var(--sp-8) var(--sp-4); }
.state-mascot { width: 44px; height: 44px; opacity: 0.8; }

/* ---------- 报纸 ---------- */
.newspaper {
  background: var(--paper-warm);
  border: var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow);
  padding: var(--sp-6) var(--sp-7);
  position: relative;
}
.newspaper h3 {
  font-family: var(--font-fun);
  font-size: var(--fs-2xl);
  text-align: center;
  border-bottom: 3px double var(--ink);
  padding-bottom: var(--sp-3);
}
.news-stamp {
  position: absolute;
  right: var(--sp-4);
  top: var(--sp-3);
  transform: rotate(10deg);
}
/* 印章放大一号（style-tile 示意 64px） */
.news-stamp :deep(.seal) {
  width: 64px;
  height: 64px;
  font-size: var(--fs-sm);
}

/* 三格数据 */
.stats { margin-top: var(--sp-4); }
.stats .stat { align-items: center; text-align: center; }

/* 分区标题 */
.news-block { margin-top: var(--sp-5); }
.block-title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
  font-weight: 400;
  padding-bottom: var(--sp-1);
  border-bottom: 2px solid var(--ink);
  margin-bottom: var(--sp-2);
}
.block-ic { width: 1em; height: 1em; color: var(--brand); }

/* 行 */
.news-line {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
  line-height: 1.7;
}
.news-idx {
  flex: none;
  width: 18px;
  height: 18px;
  margin-top: 5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  border: var(--border-thin);
  border-color: var(--ink-3);
  border-radius: 50%;
}
.notice-ic {
  flex: none;
  width: 1.1em;
  height: 1.1em;
  margin-top: 5px;
  color: var(--ink-3);
}
.notice-ic.ic-danger { color: var(--up); }
.notice-ic.ic-warn { color: var(--orange); }
.notice-ic.ic-blue { color: var(--blue); }

/* 营业部点评 */
.comment {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
  padding: var(--sp-3) var(--sp-4);
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}
.comment-mascot { width: 40px; height: 40px; flex: none; }
.comment-text {
  font-size: var(--fs-base);
  line-height: 1.7;
}

/* 明日提示 */
.tomorrow {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  margin-top: var(--sp-4);
  padding: var(--sp-2) var(--sp-3);
  border: var(--border);
  border-radius: var(--radius-md);
  background: var(--yellow-soft);
}
.tomorrow .lab {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font-fun);
  color: var(--orange);
  white-space: nowrap;
}
.lab-ic { width: 1em; height: 1em; }
.tomorrow-text { line-height: 1.7; }

/* 底部按钮 */
.paper-actions {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px dashed var(--dash);
  display: flex;
  gap: var(--sp-4);
  flex-wrap: wrap;
}

@media (max-width: 700px) {
  .newspaper { padding: var(--sp-4); }
  .news-stamp { right: var(--sp-2); top: var(--sp-2); }
  .news-stamp :deep(.seal) { width: 52px; height: 52px; }
}
</style>
