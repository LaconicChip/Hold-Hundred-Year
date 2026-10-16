<template>
  <div class="login-page">
    <!-- 左右两尊吉祥物：屏宽够时才现身 -->
    <img v-if="showMascots" :src="bullImg" class="side-mascot side-bull" alt="" />
    <img v-if="showMascots" :src="pandaImg" class="side-mascot side-panda" alt="" />

    <div class="card login-scene">
      <!-- 大门视觉区：AI 生成的营业部大门 PNG（贴纸风描边阴影） -->
      <div class="bank-art">
        <img :src="bankDoor" class="bank-art-img" alt="百年证券营业部大门" />
      </div>
      <h1 class="title">百年证券营业部</h1>
      <p class="slogan">“你持有一百年看看，涨跌都算我的。”—— 门口大爷</p>

      <!-- 便携版（解压即玩）：这台机器只有一份存档，不问用户名密码，只问"要不要接着玩" -->
      <div v-if="local" class="local-scene">
        <p class="local-kicker muted-2">这台机器上的存档</p>
        <div class="save-card">
          <template v-if="local.hasSave">
            <div class="save-row">
              <span class="save-k">进度</span>
              <span class="save-v num">第 {{ local.day }} / {{ local.totalDays }} 个交易日</span>
            </div>
            <div class="save-row">
              <span class="save-k">净资产</span>
              <span class="save-v num">¥{{ fmtMoney(local.netAsset) }}</span>
            </div>
            <div class="save-row">
              <span class="save-k">模式</span>
              <span class="save-v">{{ local.modeLabel }}</span>
            </div>
            <p v-if="local.finished" class="save-flag">本局已收官，点「继续」会先进结算页</p>
          </template>
          <p v-else class="save-none">这台机器上还没有存档</p>
        </div>

        <div class="local-actions">
          <button
            type="button"
            class="btn btn-primary btn-lg act-btn"
            :disabled="!local.hasSave || loading"
            @click="onResume"
          >
            <SvgIcon name="play" class="submit-ic" />
            {{ busy('resume') ? '开门中…' : '继续游戏' }}
          </button>
          <button type="button" class="btn btn-lg act-btn" :disabled="loading" @click="onNew">
            <SvgIcon name="plus" class="submit-ic" />
            {{ busy('new') ? '播种中…' : '创建新游戏' }}
          </button>
        </div>
        <p v-if="errText" class="err-text">
          <SvgIcon name="alert" class="err-ic" />{{ errText }}
        </p>
      </div>

      <!-- 默认档（线上 / 局域网多人）：注册即开局，各自一个账号 -->
      <template v-else>
      <!-- 登录 / 注册 贴纸风分段切换 -->
      <div class="tab-switch">
        <button
          type="button"
          class="tab-btn"
          :class="{ active: mode === 'login' }"
          @click="mode = 'login'"
        >
          登录
        </button>
        <button
          type="button"
          class="tab-btn"
          :class="{ active: mode === 'register' }"
          @click="mode = 'register'"
        >
          注册
        </button>
      </div>

      <form class="login-form" @submit.prevent="onSubmit">
        <input
          v-model.trim="username"
          class="input"
          type="text"
          autocomplete="username"
          placeholder="用户名（老股民代号）"
        />
        <input v-model="password" class="input" type="password" autocomplete="current-password" placeholder="密码" />
        <input
          v-if="mode === 'register'"
          v-model="confirmPwd"
          class="input"
          type="password"
          autocomplete="new-password"
          placeholder="确认密码"
        />

        <!-- 注册态的规则提示 -->
        <p v-if="mode === 'register'" class="hint muted-2">
          注册即开局：<b class="num">¥100,000</b> 本金 + 一个全新百年世界
        </p>

        <!-- 页内红色提示（与 ElMessage 并存，便于快速反馈） -->
        <p v-if="errText" class="err-text">
          <SvgIcon name="alert" class="err-ic" />{{ errText }}
        </p>

        <button type="submit" class="btn btn-primary btn-lg submit-btn" :disabled="loading">
          <SvgIcon :name="mode === 'login' ? 'door' : 'plus'" class="submit-ic" />
          {{ loading ? '推门中…' : mode === 'login' ? '推门进场' : '开户进场' }}
        </button>
      </form>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { useUserStore } from '@/stores/user'
import { fetchLocalStatus } from '@/api/local'
import { fmtMoney } from '@/utils/format'
import SvgIcon from '@/components/SvgIcon.vue'
import bankDoor from '@/assets/img/bank-door.png'
import bullImg from '@/assets/img/bull.png'
import pandaImg from '@/assets/img/panda.png'

const router = useRouter()
const user = useUserStore()

const mode = ref('login')
const username = ref('')
const password = ref('')
const confirmPwd = ref('')
const loading = ref(false)
const errText = ref('')
// 便携版探测结果：null = 线上档（或还没探完），对象 = 这台机器有本地存档这回事
const local = ref(null)
const action = ref('')
function busy(which) {
  return loading.value && action.value === which
}

// 左右吉祥物仅宽屏展示
const showMascots = ref(false)
function syncMascots() {
  showMascots.value = window.innerWidth >= 1100
}

// 已登录直达：进入登录页即跳大盘
onMounted(() => {
  syncMascots()
  window.addEventListener('resize', syncMascots)
  if (user.isLoggedIn) {
    router.replace('/market')
    return
  }
  probeLocal()
})

// 先当单机、再当多人：这组接口只在便携档注册，线上档拿到 404 就照常显示登录/注册。
// 静默失败——探测失败不是玩家的错，不许弹任何东西吓他。
async function probeLocal() {
  try {
    const status = await fetchLocalStatus()
    if (status && status.local) local.value = status
  } catch {
    local.value = null
  }
}
onUnmounted(() => window.removeEventListener('resize', syncMascots))

async function onSubmit() {
  errText.value = ''

  // 简单校验：用户名 / 密码非空、密码不少于 4 位
  if (!username.value || !password.value) {
    errText.value = '用户名和密码不能为空'
    return
  }
  if (password.value.length < 4) {
    errText.value = '密码至少需要 4 位'
    return
  }
  if (mode.value === 'register' && password.value !== confirmPwd.value) {
    errText.value = '两次输入的密码不一致'
    return
  }

  loading.value = true
  try {
    if (mode.value === 'login') {
      await user.login(username.value, password.value)
    } else {
      await user.register(username.value, password.value)
    }
    router.push('/market')
  } catch (e) {
    reportError(e)
  } finally {
    loading.value = false
  }
}

// 后端 400 业务错误（如“用户名已存在”“用户名或密码错误”）透传 msg
function reportError(e) {
  const msg = e && e.message ? e.message : '操作失败，请稍后再试'
  errText.value = msg
  // grouping：与拦截器同文案的离线提示合并成一条，不叠两层
  ElMessage.error({ message: msg, grouping: true })
}

// 继续游戏：免密拿本地存档的令牌
async function onResume() {
  errText.value = ''
  action.value = 'resume'
  loading.value = true
  try {
    await user.resumeLocal()
    router.push(local.value && local.value.finished ? '/result' : '/market')
  } catch (e) {
    reportError(e)
  } finally {
    loading.value = false
    action.value = ''
  }
}

// 创建新游戏：已有存档必须先过一道写清楚后果的警告框
async function onNew() {
  errText.value = ''
  const save = local.value
  if (save && save.hasSave) {
    const asset = '¥' + fmtMoney(save.netAsset)
    const tip = save.finished
      ? '本局已在第 ' + save.day + ' 日收官，净资产 ' + asset + '。创建新游戏会把这段历史清空重播，无法恢复。'
      : '当前进度：第 ' + save.day + ' / ' + save.totalDays + ' 个交易日，净资产 ' + asset
        + '。创建新游戏会清空这段历史，无法恢复。'
    try {
      await ElMessageBox.confirm(tip, '存档将被清空', {
        type: 'warning',
        confirmButtonText: '清空并重开',
        cancelButtonText: '取消'
      })
    } catch {
      return // 玩家点了取消，什么都不做
    }
  }
  action.value = 'new'
  loading.value = true
  try {
    await user.startLocalNewGame()
    router.push('/market')
  } catch (e) {
    reportError(e)
  } finally {
    loading.value = false
    action.value = ''
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6);
  position: relative;
  overflow: hidden;
}

/* 左右吉祥物：贴纸感倾斜 */
.side-mascot {
  width: 150px;
  height: 150px;
  object-fit: contain;
  position: absolute;
  bottom: 8%;
  filter: drop-shadow(3px 3px 0 rgba(43, 38, 32, 0.18));
}
.side-bull { left: 8%; transform: rotate(-5deg); }
.side-panda { right: 8%; transform: rotate(4deg); }

/* 营业部大门卡片：米白渐变底 + 墨色描边 + 硬阴影（沿用 style-tile .login-scene） */
.login-scene {
  max-width: 420px;
  width: 100%;
  text-align: center;
  background: linear-gradient(var(--login-grad-1), var(--login-grad-2));
  position: relative;
  overflow: hidden;
  padding: var(--sp-8) var(--sp-7);
}

/* 大门插画：墨色描边 + 圆角 + 硬阴影（贴纸风） */
.bank-art { margin: 4px auto var(--sp-4); }
.bank-art-img {
  width: 200px;
  display: block;
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow);
}

.title {
  font-family: var(--font-fun);
  font-size: var(--fs-2xl);
  margin-bottom: 6px;
}
.slogan {
  color: var(--ink-2);
  font-size: var(--fs-sm);
  margin-bottom: var(--sp-5);
}

/* 便携版入口：存档概况 + 两个动作 */
.local-scene {
  display: grid;
  gap: var(--sp-4);
  max-width: 300px;
  margin: 0 auto;
}
.local-kicker {
  font-size: var(--fs-sm);
  letter-spacing: 1px;
}
.save-card {
  border: var(--border);
  border-radius: var(--radius-md);
  background: var(--card);
  box-shadow: var(--shadow-sm);
  padding: var(--sp-3) var(--sp-4);
  display: grid;
  gap: 6px;
  text-align: left;
}
.save-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--sp-3);
  font-size: var(--fs-sm);
}
.save-k {
  color: var(--ink-2);
}
.save-v {
  font-weight: 700;
}
.save-flag,
.save-none {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  text-align: center;
  margin: 2px 0 0;
}
.local-actions {
  display: grid;
  gap: var(--sp-3);
}
.act-btn {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

/* 登录 / 注册 分段切换：贴纸风胶囊 */
.tab-switch {
  display: inline-flex;
  border: var(--border);
  border-radius: var(--radius-pill);
  background: var(--card);
  box-shadow: var(--shadow-sm);
  padding: 3px;
  margin: 0 auto var(--sp-4);
}
.tab-btn {
  font-family: var(--font-fun);
  font-size: var(--fs-md);
  border: none;
  background: transparent;
  color: var(--ink-2);
  padding: 6px 22px;
  border-radius: var(--radius-pill);
  cursor: pointer;
  transition: 0.1s;
}
.tab-btn.active {
  background: var(--brand);
  color: var(--on-brand);
  box-shadow: var(--shadow-sm);
}

/* 表单：胶囊输入框（抄自 style-tile .input，墨色描边 + 白底） */
.login-form {
  margin: 0 auto;
  max-width: 280px;
  display: grid;
  gap: var(--sp-3);
}
.input {
  border: var(--border);
  border-radius: var(--radius-pill);
  padding: 10px var(--sp-4);
  font-size: var(--fs-base);
  background: var(--card);
  font-family: var(--font-body);
  color: var(--ink);
  outline: none;
  width: 100%;
}
.input:focus { box-shadow: var(--shadow-sm); }

.hint {
  font-size: var(--fs-sm);
  text-align: left;
  margin: -4px 4px 0;
}
.err-text {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--up);
  font-size: var(--fs-sm);
  font-weight: 700;
  text-align: left;
  margin: -2px 4px 0;
}
.err-ic { width: 1em; height: 1em; flex: none; }

.submit-btn { justify-content: center; margin-top: var(--sp-1); width: 100%; }
.submit-ic { width: 1.1em; height: 1.1em; }

@media (max-width: 700px) {
  .login-scene { padding: var(--sp-6) var(--sp-4); }
}
</style>
