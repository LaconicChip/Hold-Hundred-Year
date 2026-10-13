<template>
  <!-- 游戏主布局（规格 §12.1 纵向菜单布局）：左侧纵向导航 + 右侧内容区 -->
  <div class="shell" :class="{ 'is-mini': collapsed }">
    <aside class="side card">
      <!-- 品牌区：牛小牛 + 店招 -->
      <div class="brand">
        <img class="brand-mascot" :src="bullImg" alt="" />
        <div v-if="!collapsed" class="brand-text">
          <b>持股</b>
          <span>一百年</span>
        </div>
      </div>

      <!-- 纵向菜单 -->
      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.key"
          :to="item.to"
          class="nav-item"
          :class="{ active: isActive(item) }"
          :title="item.label"
        >
          <SvgIcon :name="item.icon" class="nav-ic" />
          <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
          <span
            v-if="item.key === 'messages' && unread > 0"
            class="nav-badge"
            :class="{ 'is-dot': collapsed }"
          >{{ collapsed ? '' : unread > 99 ? '99+' : unread }}</span>
        </router-link>
      </nav>

      <!-- 底部：段位 + 退出 -->
      <div class="side-foot">
        <div v-if="!collapsed" class="tier-slot">
          <TierBadge v-if="market.tier" :tier="market.tier" vertical />
          <span v-else class="muted-2 tier-empty">未开盘</span>
        </div>
        <button class="btn btn-sm logout-btn" title="退出登录" @click="onLogout">
          <SvgIcon name="logout" class="nav-ic" />
          <span v-if="!collapsed">退出</span>
        </button>
      </div>
    </aside>

    <!-- 折叠开关 -->
    <button
      class="collapse-btn"
      :title="collapsed ? '展开菜单' : '收起菜单'"
      @click="toggleCollapsed"
    >
      <SvgIcon :name="collapsed ? 'chevron-right' : 'chevron-left'" />
    </button>

    <main class="main">
      <router-view />
    <!-- 全站"当面提醒"层（spec §12.1 消息分级）：ALERT 模态窗在最上层（阻塞 + 暂停行情），
         头条大弹幕与右下角浮层都不阻塞操作 -->
    <HeadlineDanmu />
    <NoticePopup />
    <AlertModal />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SvgIcon from './SvgIcon.vue'
import HeadlineDanmu from './HeadlineDanmu.vue'
import NoticePopup from './NoticePopup.vue'
import AlertModal from './AlertModal.vue'
import TierBadge from './TierBadge.vue'
import bullImg from '@/assets/img/bull.png'
import { useMarketStore } from '@/stores/market'
import { useMessagesStore } from '@/stores/messages'
import { useQuotesStore } from '@/stores/quotes'
import { useUserStore } from '@/stores/user'
import { fetchStockList } from '@/api/stocks'
import { resumeMarket } from '@/api/market'

const route = useRoute()
const router = useRouter()
const market = useMarketStore()
const messages = useMessagesStore()
const user = useUserStore()
const quotes = useQuotesStore()

// 折叠态：默认展开。只有窗口真的很窄（<900px，此时侧栏会挤掉内容区）才自动收窄；
// 用户手动点过折叠按钮就记住他的选择，此后不再被窗口宽度改回去。
const MENU_KEY = 'hold-year-menu-collapsed'
const COLLAPSE_AT = 900
const collapsed = ref(localStorage.getItem(MENU_KEY) === '1')
function syncCollapsed() {
  if (localStorage.getItem(MENU_KEY) !== null) return
  collapsed.value = window.innerWidth < COLLAPSE_AT
}
function toggleCollapsed() {
  collapsed.value = !collapsed.value
  localStorage.setItem(MENU_KEY, collapsed.value ? '1' : '0')
}

// 个股：记住最近看过的股票，菜单项回到它
const lastStockId = ref(Number(sessionStorage.getItem('lastStockId') || 0))
watch(
  () => route.params.id,
  (id) => {
    if (id) {
      lastStockId.value = Number(id)
      sessionStorage.setItem('lastStockId', String(id))
    }
  },
  { immediate: true }
)

const navItems = computed(() => [
  { key: 'market', label: '大盘', icon: 'chart', to: '/market' },
  {
    key: 'stock',
    label: '个股',
    icon: 'candle',
    to: lastStockId.value ? `/stock/${lastStockId.value}` : '/market'
  },
  { key: 'assets', label: '资产', icon: 'wallet', to: '/assets' },
  { key: 'messages', label: '消息', icon: 'bell', to: '/messages' },
  { key: 'leaderboard', label: '排行', icon: 'trophy', to: '/leaderboard' },
  { key: 'summary', label: '日报', icon: 'newspaper', to: '/summary' },
  { key: 'result', label: '结算', icon: 'cert', to: '/result' }
])

const unread = computed(() => (messages.unreadCount > 0 ? messages.unreadCount : 0))

function isActive(item) {
  const name = route.name
  const path = route.path
  switch (item.key) {
    case 'stock':
      return name === 'stock-detail'
    case 'market':
      return path === '/market'
    case 'assets':
      return path === '/assets'
    case 'messages':
      return path === '/messages'
    case 'leaderboard':
      return path === '/leaderboard'
    case 'summary':
      return path === '/summary'
    case 'result':
      return path === '/result'
    default:
      return false
  }
}

// 当日口径的封板/停牌/涨跌停价只在 REST 里，WS 不推。交易日一变就清掉旧值并重拉，
// 否则会出现"新的一天已经跌停了、行情表还挂着昨天涨停封板"的错位（切页面才恢复）。
let lastDay = 0
watch(
  () => market.currentDay,
  (day) => {
    if (!day || day === lastDay) {
      lastDay = day
      return
    }
    lastDay = day
    quotes.invalidateDayFlags()
    refreshStocks()
  }
)

async function refreshStocks() {
  try {
    quotes.applyStockList(await fetchStockList())
  } catch {
    // 拉不到就保持现状：后端不可达的提示由 http 层统一弹，这里不重复刷屏
  }
}

function onLogout() {
  user.logout()
  router.push('/login')
}

onMounted(() => {
  syncCollapsed()
  window.addEventListener('resize', syncCollapsed)
  // 侧栏要显示纪元/段位/交易日，进主布局时补一次状态
  if (!market.currentDay) market.fetchState()
  // 铃铛角标：连接建立之前落库的未读不会推送过来，这里补拉一次（不播报）
  messages.syncUnread()
  // 安全阀：上一次会话留下的暂停标记不该把新世界一直冻住——页面刚打开时没有弹窗在等玩家
  if (!messages.modals.length) resumeMarket().catch(() => {})
})
onUnmounted(() => {
  window.removeEventListener('resize', syncCollapsed)
})
</script>

<style scoped>
.shell {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: var(--gap);
  max-width: var(--wrap);
  margin: 0 auto;
  padding: var(--sp-6) var(--sp-5) var(--sp-10);
}

/* ---------- 侧栏 ---------- */
.side {
  flex: none;
  width: var(--sidebar-w);
  position: sticky;
  top: var(--sp-6);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-3);
  overflow: visible;
  transition: width 0.16s;
}
.shell.is-mini .side {
  width: var(--sidebar-w-mini);
  padding: var(--sp-3) var(--sp-2);
}

/* 品牌区 */
.brand {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-2) var(--sp-3);
  border-bottom: 2px dashed var(--dash);
}
.brand-mascot {
  width: 36px;
  height: 36px;
  object-fit: contain;
  flex: none;
}
.brand-text {
  font-family: var(--font-fun);
  line-height: 1.1;
  display: flex;
  flex-direction: column;
}
.brand-text b {
  font-size: var(--fs-lg);
  color: var(--brand);
}
.brand-text span {
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

/* 菜单 */
.nav {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
  color: var(--ink);
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: 8px var(--sp-3);
  text-decoration: none;
  transition: 0.08s;
}
.shell.is-mini .nav-item {
  justify-content: center;
  padding: 9px 0;
}
.nav-item:hover {
  transform: translate(-1px, -1px);
  box-shadow: var(--shadow);
  background: var(--hover);
}
/* 激活态：品牌红填充 + 白字（规格 §12.1） */
.nav-item.active {
  background: var(--brand);
  color: var(--on-brand);
  transform: translate(1px, 1px);
  box-shadow: var(--shadow-press);
}
.nav-ic {
  width: 1.25em;
  height: 1.25em;
  flex: none;
}
.nav-label { white-space: nowrap; }

/* 未读角标 */
.nav-badge {
  margin-left: auto;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--yellow);
  color: var(--ink);
  border: var(--border-thin);
  border-radius: var(--radius-pill);
  font-family: var(--font-body);
  font-size: var(--fs-xs);
  font-weight: 700;
}
.shell.is-mini .nav-badge.is-dot {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 12px;
  height: 12px;
  padding: 0;
  margin: 0;
  background: var(--brand);
  border-color: var(--card);
}

/* 底部：段位 + 退出 */
.side-foot {
  margin-top: auto;
  padding-top: var(--sp-3);
  border-top: 2px dashed var(--dash);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
}
.tier-slot { display: flex; justify-content: center; }
.tier-empty { font-size: var(--fs-xs); }
.logout-btn { gap: 6px; }
.shell.is-mini .logout-btn {
  justify-content: center;
  padding: 6px 0;
  width: 100%;
}

/* ---------- 内容区 ---------- */
.main {
  flex: 1;
  min-width: 0;
}

/* ---------- 折叠开关 ---------- */
.collapse-btn {
  position: absolute;
  top: calc(var(--sp-6) + 30px);
  left: calc(var(--sp-5) + var(--sidebar-w) - 14px);
  z-index: 5;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--card);
  border: var(--border);
  border-radius: 50%;
  box-shadow: var(--shadow-sm);
  color: var(--ink-2);
  cursor: pointer;
  transition: 0.16s;
}
.collapse-btn:hover { color: var(--brand); transform: translate(-1px, -1px); box-shadow: var(--shadow); }
.collapse-btn :deep(svg) { width: 15px; height: 15px; }
.shell.is-mini .collapse-btn {
  left: calc(var(--sp-5) + var(--sidebar-w-mini) - 14px);
}

@media (max-width: 900px) {
  .shell { padding: var(--sp-4) var(--sp-3) var(--sp-8); }
  .side,
  .shell.is-mini .side { width: var(--sidebar-w-mini); padding: var(--sp-3) var(--sp-2); }
  .nav-item,
  .shell.is-mini .nav-item { justify-content: center; padding: 9px 0; }
  .collapse-btn { display: none; }
  .brand { justify-content: center; padding-bottom: var(--sp-2); }
}
</style>
