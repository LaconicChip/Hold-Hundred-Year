<template>
  <el-dialog v-model="visible" title="新股申购" width="380px" class="ipo-dialog">
    <div v-if="ipo" class="ipo-body">
      <p class="ipo-name">{{ ipo.ipoName || ('申购编号 ' + ipo.scriptDayId) }}</p>
      <p class="ipo-price">发行价 <span class="num">{{ ipo.issuePrice != null ? ipo.issuePrice : '—' }}</span></p>
    </div>
    <template #footer>
      <button class="btn" @click="close">关闭</button>
      <button class="btn btn-primary" :disabled="subscribing" @click="subscribe">顶格申购</button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useMessagesStore } from '@/stores/messages'
import { subscribe as apiSubscribe } from '@/api/ipo'

const messages = useMessagesStore()
const visible = ref(false)
const ipo = ref(null)
const subscribing = ref(false)

// 已处理（申购或关闭）的 scriptDayId，避免同一通知重复弹窗
const shown = new Set()

// 是否为打新通知：含 scriptDayId，且（含 ipoName / issuePrice / kind|type 含 IPO 字样）
function isIpo(n) {
  if (!n || !n.scriptDayId) return false
  if (n.ipoName || n.issuePrice != null) return true
  const k = `${n.kind || ''} ${n.type || ''}`.toLowerCase()
  if (/ipo/.test(k)) return true
  return false
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

async function subscribe() {
  if (!ipo.value) return
  subscribing.value = true
  try {
    await apiSubscribe(ipo.value.scriptDayId)
    ElMessage.success('申购成功，祝你好运')
    shown.add(ipo.value.scriptDayId) // 记住已申购，不再弹
    visible.value = false
  } catch (e) {
    ElMessage.error(e?.message || '申购失败')
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
.ipo-body {
  font-size: 15px;
  line-height: 2;
}
.ipo-name {
  font-family: var(--font-fun);
  font-size: 18px;
}
.ipo-price {
  color: var(--ink-2);
}
</style>
