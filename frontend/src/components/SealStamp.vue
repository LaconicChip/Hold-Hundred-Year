<template>
  <!-- 封板 / 炸板印章：mounted 或文字变化时播放一次盖章回弹动画 -->
  <span :key="stampKey" :class="sealCls">{{ text }}</span>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'

const props = defineProps({
  broken: { type: Boolean, default: false }, // true 时为炸板（灰删除线）
  text: { type: String, default: '封板' },
  mini: { type: Boolean, default: false } // true 用 seal-mini 34px
})

const stampKey = ref(0)

const sealCls = computed(() => ({
  seal: true,
  broken: props.broken,
  'seal-mini': props.mini,
  'seal-stamp': true
}))

// 首次挂载与文字变化都重新触发盖章动画（通过变更 key 强制重渲染）
onMounted(() => { stampKey.value++ })
watch(() => props.text, () => { stampKey.value++ })
</script>

<style scoped>
/* 盖章弹出：scale(1.4 -> 1) 回弹，0.35s */
@keyframes seal-stamp {
  0% { transform: rotate(-12deg) scale(1.4); opacity: 0; }
  60% { transform: rotate(-12deg) scale(0.94); opacity: 1; }
  100% { transform: rotate(-12deg) scale(1); }
}
.seal-stamp {
  animation: seal-stamp 0.35s ease-out;
}
/* 尊重系统级减弱动效偏好 */
@media (prefers-reduced-motion: reduce) {
  .seal-stamp {
    animation: none;
  }
}
</style>
