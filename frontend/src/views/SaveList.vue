<template>
  <div class="saves-page">
    <img v-if="showMascots" :src="bullImg" class="side-mascot side-bull" alt="" />
    <img v-if="showMascots" :src="pandaImg" class="side-mascot side-panda" alt="" />

    <div class="card saves-scene">
      <div class="bank-art">
        <img :src="bankDoor" class="bank-art-img" alt="百年证券营业部大门" />
      </div>
      <h1 class="title">百年证券营业部</h1>
      <p class="slogan">“你持有一百年看看”—— 李小霄</p>

      <div class="rack-head">
        <span class="rack-kicker">存档架</span>
        <span class="rack-count" v-if="saves.length">{{ saves.length }} 份存档</span>
      </div>

      <div v-if="loading" class="rack-empty">正在翻存档…</div>

      <div v-else-if="!saves.length" class="rack-empty">
        <p class="empty-title">当前还没有存档哦</p>
        <p class="empty-desc">新建一份存档，领 10 万本金，开始你的第一百年。</p>
      </div>

      <div v-else class="save-list">
        <article v-for="s in saves" :key="s.id" class="save-card">
          <div class="save-top">
            <b class="save-name">{{ s.name }}</b>
            <span class="save-mode">{{ s.modeLabel }}</span>
          </div>
          <div class="save-meta">
            <span class="num">第 {{ s.day }} / {{ s.totalDays }} 日</span>
            <span class="num">净资产 ¥{{ fmtMoney(s.netAsset) }}</span>
          </div>
          <div class="save-bottom">
            <span class="save-state" :class="{ gone: s.finished }">{{ stateText(s) }}</span>
            <span class="save-actions">
              <button class="btn btn-primary btn-sm" :disabled="busy" @click="onOpen(s)">
                <SvgIcon name="play" class="btn-ic" />{{ s.finished ? '看结算' : '继续' }}
              </button>
              <button class="btn btn-sm danger" :disabled="busy" @click="onDelete(s)">
                <SvgIcon name="close" class="btn-ic" />删除
              </button>
            </span>
          </div>
        </article>
      </div>

      <p v-if="errText" class="err-text">
        <SvgIcon name="alert" class="err-ic" />{{ errText }}
      </p>

      <button
        v-if="errText && !loading"
        type="button"
        class="btn btn-lg retry-btn"
        @click="load"
      >再试一次</button>

      <button type="button" class="btn btn-primary btn-lg new-btn" :disabled="busy" @click="onNew">
        <SvgIcon name="plus" class="btn-ic" />{{ busyAction === 'new' ? '播种中…' : '新建存档' }}
      </button>

      <p class="rack-note">新建不会覆盖旧存档；删除会连同这一局的全部数据一起清掉。</p>
      <p class="rack-note">局域网里的朋友打开同一个地址，看到的是同一张存档架。</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { useUserStore } from '@/stores/user'
import { fetchSaves, deleteSave } from '@/api/saves'
import { fmtMoney } from '@/utils/format'
import SvgIcon from '@/components/SvgIcon.vue'
import bankDoor from '@/assets/img/bank-door.png'
import bullImg from '@/assets/img/bull.png'
import pandaImg from '@/assets/img/panda.png'

const router = useRouter()
const user = useUserStore()

const saves = ref([])
const loading = ref(true)
const errText = ref('')
const busy = ref(false)
const busyAction = ref('')

// 左右吉祥物仅宽屏展示
const showMascots = ref(false)
function syncMascots() {
  showMascots.value = window.innerWidth >= 1100
}

onMounted(async () => {
  syncMascots()
  window.addEventListener('resize', syncMascots)
  showCarriedNotice()
  await load()
})
onUnmounted(() => window.removeEventListener('resize', syncMascots))

// 令牌失效时 http.js 会留一句话再跳过来；这里只播一次，不叠加。
function showCarriedNotice() {
  const notice = sessionStorage.getItem('hhy-auth-notice')
  if (!notice) return
  sessionStorage.removeItem('hhy-auth-notice')
  ElMessage.warning({ message: notice, grouping: true })
}

async function load() {
  loading.value = true
  errText.value = ''
  try {
    saves.value = await fetchSaves()
  } catch (e) {
    errText.value = e?.message || '存档架打不开，稍后再试'
  } finally {
    loading.value = false
  }
}

function stateText(s) {
  if (s.finished) return s.marketState === 'BANKRUPT' ? '已爆仓' : '已通关'
  if (s.marketState === 'NOT_STARTED') return '尚未开盘'
  if (s.marketState === 'CLOSED') return '已收盘'
  return '进行中'
}

async function onOpen(s) {
  if (busy.value) return
  errText.value = ''
  busy.value = true
  busyAction.value = 'open'
  try {
    await user.openSaveById(s.id)
    router.push(s.finished ? '/result' : '/market')
  } catch (e) {
    reportError(e)
  } finally {
    busy.value = false
    busyAction.value = ''
  }
}

async function onNew() {
  if (busy.value) return
  errText.value = ''
  busy.value = true
  busyAction.value = 'new'
  try {
    await user.createNewSave()
    router.push('/market')
  } catch (e) {
    reportError(e)
  } finally {
    busy.value = false
    busyAction.value = ''
  }
}

async function onDelete(s) {
  if (busy.value) return
  const tip = `${s.name}：第 ${s.day} / ${s.totalDays} 个交易日，净资产 ¥${fmtMoney(s.netAsset)}。`
    + '删除会连同持仓、成交、消息、日K一起清掉，无法恢复。'
  try {
    await ElMessageBox.confirm(tip, '删除这份存档？', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '留着'
    })
  } catch {
    return
  }
  busy.value = true
  busyAction.value = 'delete'
  try {
    await deleteSave(s.id)
    // 删的正是当前进入的那份：令牌已失效，顺手清掉本地会话
    if (user.userId === s.id) user.logout()
    ElMessage.success({ message: `已删除 ${s.name}`, grouping: true })
    await load()
  } catch (e) {
    reportError(e)
  } finally {
    busy.value = false
    busyAction.value = ''
  }
}

function reportError(e) {
  const msg = e && e.message ? e.message : '操作失败，请稍后再试'
  errText.value = msg
  ElMessage.error({ message: msg, grouping: true })
}
</script>

<style scoped>
.saves-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6);
  position: relative;
  overflow: hidden;
}

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

.saves-scene {
  max-width: 480px;
  width: 100%;
  text-align: center;
  background: linear-gradient(var(--login-grad-1), var(--login-grad-2));
  position: relative;
  overflow: hidden;
  padding: var(--sp-7) var(--sp-6);
}

.bank-art { margin: 4px auto var(--sp-3); }
.bank-art-img {
  width: 168px;
  display: block;
  margin: 0 auto;
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow);
}

.title {
  font-family: var(--font-fun);
  font-size: var(--fs-2xl);
  text-align: center;
  margin-bottom: 6px;
}
.slogan {
  color: var(--ink-2);
  font-size: var(--fs-sm);
  margin-bottom: var(--sp-4);
}

.rack-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: var(--sp-3);
  padding: 0 4px;
}
.rack-kicker {
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
}
.rack-count {
  color: var(--ink-2);
  font-size: var(--fs-sm);
}

.rack-empty {
  border: var(--border);
  border-radius: var(--radius-md);
  background: var(--card);
  box-shadow: var(--shadow-sm);
  padding: var(--sp-5) var(--sp-4);
  margin-bottom: var(--sp-4);
  color: var(--ink-2);
  font-size: var(--fs-sm);
}
.empty-title {
  color: var(--ink);
  font-weight: 700;
  font-size: var(--fs-md);
  margin-bottom: 4px;
}
.empty-desc { margin: 0; }

.save-list {
  display: grid;
  gap: var(--sp-3);
  max-height: 46vh;
  overflow-y: auto;
  padding: 2px 4px 6px;
  margin-bottom: var(--sp-4);
  text-align: left;
}

.save-card {
  border: var(--border);
  border-radius: var(--radius-md);
  background: var(--card);
  box-shadow: var(--shadow-sm);
  padding: var(--sp-3) var(--sp-4);
  display: grid;
  gap: 8px;
  transition: 0.1s;
}
.save-card:hover { box-shadow: var(--shadow); }

.save-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--sp-2);
}
.save-name {
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
}
.save-mode {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  border: 1px solid var(--ink-2);
  border-radius: var(--radius-pill);
  padding: 1px 8px;
  white-space: nowrap;
}

.save-meta {
  display: flex;
  justify-content: space-between;
  gap: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.save-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-2);
}
.save-state {
  font-size: var(--fs-xs);
  color: var(--down);
  font-weight: 700;
}
.save-state.gone { color: var(--ink-2); }
.save-actions {
  display: inline-flex;
  gap: 6px;
}
.btn-ic {
  width: 1em;
  height: 1em;
  margin-right: 2px;
}
.btn.danger {
  color: var(--up);
  border-color: var(--up);
}

.retry-btn {
  width: 100%;
  justify-content: center;
  margin-bottom: var(--sp-3);
}
.new-btn {
  width: 100%;
  justify-content: center;
}

.rack-note {
  color: var(--ink-2);
  font-size: var(--fs-xs);
  margin: 6px 0 0;
}

.err-text {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--up);
  font-size: var(--fs-sm);
  font-weight: 700;
  margin: 0 0 var(--sp-3);
}
.err-ic { width: 1em; height: 1em; flex: none; }

@media (max-width: 700px) {
  .saves-scene { padding: var(--sp-5) var(--sp-4); }
  .save-list { max-height: 42vh; }
}
</style>
