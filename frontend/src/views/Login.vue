<template>
  <div class="login-page">
    <div class="card login-scene">
      <!-- 大门视觉区：当前用 SVG 图标 bank 占位（约 110px，同 style-tile 的 i-bank）
           替换点（Task 12.6）：将下方 SvgIcon 换成 AI 生成的营业部大门 PNG，例如：
           <img class="bank-art" src="@/assets/bank-door.png" alt="百年证券营业部大门" />
           保留 SvgIcon 作为兜底，避免图片缺失时空白。 -->
      <div class="bank-art">
        <SvgIcon name="bank" :size="110" />
      </div>
      <h1 class="title">百年证券营业部</h1>
      <p class="slogan">“你持有一百年看看，涨跌都算我的。”—— 门口大爷</p>

      <!-- 登录 / 注册 贴纸风分段切换，避免 el-tabs 默认样式违和 -->
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
        <input
          v-model="password"
          class="input"
          type="password"
          autocomplete="current-password"
          placeholder="密码"
        />
        <input
          v-if="mode === 'register'"
          v-model="confirmPwd"
          class="input"
          type="password"
          autocomplete="new-password"
          placeholder="确认密码"
        />

        <!-- 页内红色提示（与 ElMessage 并存，便于快速反馈） -->
        <p v-if="errText" class="err-text">{{ errText }}</p>

        <button
          type="submit"
          class="btn btn-primary btn-lg submit-btn"
          :disabled="loading"
        >
          {{ loading ? '推门中…' : '推门进场' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import SvgIcon from '@/components/SvgIcon.vue'

const router = useRouter()
const user = useUserStore()

const mode = ref('login')
const username = ref('')
const password = ref('')
const confirmPwd = ref('')
const loading = ref(false)
const errText = ref('')

// 已登录直达：进入登录页即跳大盘
onMounted(() => {
  if (user.isLoggedIn) {
    router.replace('/market')
  }
})

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
  padding: 24px;
}

/* 营业部大门卡片：米白渐变底 + 墨色描边 + 硬阴影 + 14px 圆角（沿用 style-tile .login-scene） */
.login-scene {
  max-width: 420px;
  width: 100%;
  text-align: center;
  background: linear-gradient(#fdf3dd, #f6ecd4);
  position: relative;
  overflow: hidden;
}

/* 大门插画容器（SvgIcon 占位，未来替换为 bank-door.png） */
.bank-art {
  width: 110px;
  height: 110px;
  margin: 4px auto 14px;
  color: var(--ink);
}
.bank-art :deep(svg) {
  width: 110px;
  height: 110px;
}

.title {
  font-family: var(--font-fun);
  font-size: 28px;
  margin-bottom: 6px;
}
.slogan {
  color: var(--ink-2);
  font-size: 13px;
  margin-bottom: 18px;
}

/* 登录 / 注册 分段切换：贴纸风胶囊 */
.tab-switch {
  display: inline-flex;
  border: var(--border);
  border-radius: 999px;
  background: #fffdf7;
  box-shadow: 2px 2px 0 var(--ink);
  padding: 3px;
  margin: 0 auto 16px;
}
.tab-btn {
  font-family: var(--font-fun);
  font-size: 16px;
  border: none;
  background: transparent;
  color: var(--ink-2);
  padding: 6px 22px;
  border-radius: 999px;
  cursor: pointer;
  transition: 0.1s;
}
.tab-btn.active {
  background: var(--brand);
  color: #fff;
  box-shadow: 2px 2px 0 var(--ink);
}

/* 表单：胶囊输入框（抄自 style-tile .input，墨色描边 + 白底） */
.login-form {
  margin: 0 auto;
  max-width: 280px;
  display: grid;
  gap: 12px;
}
.input {
  border: var(--border);
  border-radius: 999px;
  padding: 10px 16px;
  font-size: 14px;
  background: #fff;
  font-family: var(--font-body);
  color: var(--ink);
  outline: none;
  width: 100%;
}
.input:focus {
  box-shadow: 2px 2px 0 var(--ink);
}

.err-text {
  color: var(--up);
  font-size: 13px;
  text-align: left;
  margin: -2px 4px 0;
}

.submit-btn {
  justify-content: center;
  margin-top: 4px;
  width: 100%;
}
</style>
