<template>
  <div class="summary-page">
    <!-- 页头：返回大盘 + 标题 -->
    <header class="page-head card">
      <button class="back-btn" @click="goBack">
        <SvgIcon name="bank" class="back-ic" />返回大盘
      </button>
      <h1 class="page-title">收盘晚报</h1>
    </header>

    <div v-if="loading" class="card state-card">加载中…</div>
    <div v-else-if="!data" class="card state-card">暂无晚报数据</div>

    <!-- 晚报报纸（style-tile 第九节） -->
    <section v-else class="newspaper">
      <!-- 右上斜印章：跑赢红章 / 跑输灰章 -->
      <div class="news-stamp">
        <SealStamp :text="beatIndex ? '跑赢大盘' : '跑输大盘'" :broken="!beatIndex" />
      </div>

      <h3>营业部晚报 · 第 {{ data.gameDay }} 期</h3>

      <!-- 三格数据 -->
      <div class="stats">
        <div>
          <span class="stat-lab">你的日收益</span><br />
          <span class="num stat-num" :class="pctCls(data.dayReturnPct)">
            {{ fmtPct(data.dayReturnPct) }}
          </span>
        </div>
        <div>
          <span class="stat-lab">百年指数</span><br />
          <span class="num stat-num" :class="pctCls(data.indexDayPct)">
            {{ fmtPct(data.indexDayPct) }}
          </span>
        </div>
        <div>
          <span class="stat-lab">净资产</span><br />
          <span class="num stat-num">{{ fmtMoney(data.netAsset) }}</span>
        </div>
      </div>

      <!-- 今日要闻 -->
      <div v-if="newsList.length" class="news-block">
        <p v-for="(n, i) in newsList" :key="i" class="news-line">
          <span class="lab">今日要闻：</span>{{ n }}
        </p>
      </div>

      <!-- 结算提示 -->
      <div v-if="noticeList.length" class="news-block">
        <p v-for="(n, i) in noticeList" :key="i" class="news-line">
          <span class="lab">结算提示：</span>{{ n }}
        </p>
      </div>

      <!-- 营业部点评 -->
      <p v-if="data.comment" class="news-line">
        <span class="lab">营业部点评：</span>{{ data.comment }}
      </p>

      <!-- 明日提示 -->
      <p class="news-line">
        <span class="lab warn-lab"><SvgIcon name="alert" class="warn-ic" />明日提示：</span>
        当前纪元：{{ data.eraName || '平稳年代' }}
        <template v-if="guardRisk">
          ；融资担保比例 {{ (data.guardRatio * 100).toFixed(0) }}%，低于 140% 追保线，请注意追保风险。
        </template>
      </p>

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
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/market')
}

onMounted(load)
</script>

<style scoped>
.summary-page {
  max-width: 760px;
  margin: 0 auto;
  padding: 24px 20px 80px;
}
/* 页头 */
.page-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 18px;
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
}
.state-card {
  margin-top: 16px;
  color: var(--ink-3);
  font-size: 14px;
  padding: 40px 10px;
  text-align: center;
}
/* 报纸（style-tile 第九节原样气质） */
.newspaper {
  margin-top: 16px;
  background: #fbf6ea;
  border: 2px solid var(--ink);
  border-radius: 6px;
  box-shadow: var(--shadow);
  padding: 22px 26px;
  position: relative;
}
.newspaper h3 {
  font-family: var(--font-fun);
  font-size: 26px;
  text-align: center;
  border-bottom: 3px double var(--ink);
  padding-bottom: 10px;
}
.news-stamp {
  position: absolute;
  right: 18px;
  top: 16px;
  transform: rotate(10deg);
}
/* 印章放大一号（style-tile 示意 64px） */
.news-stamp :deep(.seal) {
  width: 64px;
  height: 64px;
  font-size: 13px;
}
/* 三格数据 */
.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-top: 14px;
}
.stat-lab {
  color: var(--ink-2);
}
.stat-num {
  font-size: 24px;
}
/* 行 */
.news-block {
  margin-top: 2px;
}
.news-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 10px;
  line-height: 1.7;
}
.news-line .lab {
  color: var(--ink-2);
  font-weight: 700;
  white-space: nowrap;
}
.warn-lab {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--orange) !important;
}
.warn-ic {
  width: 15px;
  height: 15px;
}
/* 底部按钮 */
.paper-actions {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px dashed #d8cfbc;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}
.flat {
  color: var(--ink);
}
</style>
