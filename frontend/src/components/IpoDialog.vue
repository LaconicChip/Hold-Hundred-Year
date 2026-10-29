<template>
  <el-dialog v-model="visible" title="新股申购" width="380px" class="ipo-dialog">
    <div v-if="ipo" class="ipo-body">
      <div class="ipo-hero">
        <SvgIcon name="ticket" class="ipo-ic" />
        <p class="ipo-name">{{ ipo.ipoName || '申购编号 ' + ipo.scriptDayId }}</p>
      </div>
      <dl class="kv">
        <dt>发行价</dt>
        <dd>{{ ipo.issuePrice != null ? '¥' + ipo.issuePrice : '—' }}</dd>
        <dt>申购编号</dt>
        <dd class="num">{{ ipo.scriptDayId }}</dd>
      </dl>
    </div>
    <template #footer>
      <button class="btn" @click="close">再想想</button>
      <button class="btn btn-primary" :disabled="subscribing" @click="subscribe">
        <SvgIcon name="check" class="btn-ic" />顶格申购
      </button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus/es/components/dialog/index.mjs'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import 'element-plus/es/components/dialog/style/css'
import { useMessagesStore } from '@/stores/messages'
import { subscribe as apiSubscribe } from '@/api/ipo'
import { useMarketStore } from '@/stores/market'
import SvgIcon from './SvgIcon.vue'

const messages = useMessagesStore()
const market = useMarketStore()
const visible = ref(false)
const ipo = ref(null)
const subscribing = ref(false)

// 已处理（申购或关闭）的 scriptDayId，避免同一通知重复弹窗
const shown = new Set()

// 是否为打新通知：含 scriptDayId，且（含 ipoName / issuePrice / kind|type 含 IPO 字样）
function isIpo(n) {
  if (!n || !n.scriptDayId) return false
  // 只认当天的申购通知：旧通知跨日后还留着，点"申购"就会被后端判"申购日已过"
  if (n.gameDay != null && Number(n.gameDay) !== market.currentDay) return false
  if (n.ipoName || n.issuePrice != null) return true
  const k = `${n.kind || ''} ${n.type || ''}`.toLowerCase()
  return /ipo/.test(k)
}

// 监听通知数组，出现新打新通知则弹窗（取最新一条未处理的）
watch(
  () => messages.notifications,
  (arr) => {
    const list = arr || []
    for (let i = list.length - 1; i >= 0; i--) {
      const n = list[i]
      if (isIpo(n) && !shown.has(n.scriptDayId)) {
        ipo.value = n
        visible.value = true
        return
      }
    }
  },
  { deep: true }
)

// 跨日：把还开着的旧申购窗关掉（同一条通知不再反复弹）
watch(
  () => market.currentDay,
  (day) => {
    if (visible.value && ipo.value && ipo.value.gameDay != null
        && Number(ipo.value.gameDay) !== day) {
      shown.add(ipo.value.scriptDayId)
      visible.value = false
    }
  }
)

// 跨日：把还开着的旧申购窗关掉（同一条通知不再反复弹）
watch(
  () => market.currentDay,
  (day) => {
    if (visible.value && ipo.value && ipo.value.gameDay != null
        && Number(ipo.value.gameDay) !== day) {
      shown.add(ipo.value.scriptDayId)
      visible.value = false
    }
  }
)

async function subscribe() {
  if (!ipo.value) return
  subscribing.value = true
  try {
    await apiSubscribe(ipo.value.scriptDayId)
    ElMessage.success('申购成功，祝你好运')
    shown.add(ipo.value.scriptDayId) // 记住已申购，不再弹
    visible.value = false
  } catch (e) {
    ElMessage.error({ message: e?.message || '申购失败', grouping: true })
  } finally {
    subscribing.value = false
  }
}

function close() {
  if (ipo.value) shown.add(ipo.value.scriptDayId) // 关闭后本次不再自动弹
  visible.value = false
}
</script>

<style scoped>
.ipo-body { font-size: var(--fs-md); }
.ipo-hero {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-3);
  background: var(--yellow-soft);
  border: var(--border);
  border-radius: var(--radius-md);
}
.ipo-ic { width: 1.4em; height: 1.4em; color: var(--orange); flex: none; }
.ipo-name {
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
}
.btn-ic { width: 1em; height: 1em; }
</style>
