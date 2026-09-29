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

      <!-- 本局模式：只在开户时选，选定后整局不变 -->
      <div v-if="mode === 'register'" class="gamemode-pick">
        <button
          v-for="opt in GAME_MODES"
          :key="opt.code"
          type="button"
          class="gamemode-opt"
          :class="{ on: gameMode === opt.code }"
          :title="opt.desc"
          @click="gameMode = opt.code"
        >
          <b>{{ opt.label }}</b>
          <span>{{ opt.desc }}</span>
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
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import SvgIcon from '@/components/SvgIcon.vue'
import bankDoor from '@/assets/img/bank-door.png'
import bullImg from '@/assets/img/bull.png'
import pandaImg from '@/assets/img/panda.png'

const router = useRouter()
const user = useUserStore()

const mode = ref('login')

// 开户时可选的本局模式（与后端 GameMode 对齐）
const GAME_MODES = [
  { code: 'STANDARD', label: '标准模式', desc: '72 个交易日 · 约 2 小时 · ±10%' },
  { code: 'QUICK', label: '快速模式', desc: '36 个交易日 · 约 30 分钟 · ±20%' }
]
const gameMode = ref('STANDARD')
const username = ref('')
const password = ref('')
const confirmPwd = ref('')
const loading = ref(false)
const errText = ref('')

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
  }
})
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
      await user.register(username.value, password.value, gameMode.value)
    }
    router.push('/market')
  } catch (e) {
    // 后端 400 业务错误（如“用户名已存在”“用户名或密码错误”）透传 msg
    const msg = e && e.message ? e.message : '操作失败，请稍后再试'
    errText.value = msg
    ElMessage.error(msg)
  } finally {
    loading.value = false
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

.gamemode-pick {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}
.gamemode-opt {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: var(--sp-2) var(--sp-3);
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  font-family: var(--font-fun);
  color: var(--ink-2);
  cursor: pointer;
  text-align: left;
}
.gamemode-opt b { font-size: var(--fs-md); color: var(--ink); }
.gamemode-opt span { font-size: var(--fs-xs); font-family: var(--font-body); }
.gamemode-opt.on {
  color: var(--on-brand);
  background: var(--brand);
  box-shadow: var(--shadow);
}
.gamemode-opt.on b, .gamemode-opt.on span { color: var(--on-brand); }
.submit-btn { justify-content: center; margin-top: var(--sp-1); width: 100%; }
.submit-ic { width: 1.1em; height: 1.1em; }

@media (max-width: 700px) {
  .login-scene { padding: var(--sp-6) var(--sp-4); }
}
</style>
