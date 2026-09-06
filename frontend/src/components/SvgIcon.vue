<template>
  <!-- 自制 SVG 图标：沿用 tokens.css 的 .ic 基础样式（stroke 继承 currentColor） -->
  <svg class="ic svg-icon" :style="sizeStyle" role="img" :aria-label="name">
    <use :href="iconsUrl + '#' + name" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'
// Vite 会把 .svg 作为静态资源处理，返回构建后 URL
import iconsUrl from '@/assets/icons.svg'

const props = defineProps({
  // 对应 symbol 的 id（不含 i- 前缀）
  name: { type: String, required: true },
  // 尺寸：数字按 px 处理，字符串当 css 尺寸（如 '1.1em'）
  size: { type: [String, Number], default: '1.1em' }
})

const sizeStyle = computed(() => {
  const s = typeof props.size === 'number' ? props.size + 'px' : props.size
  return { width: s, height: s }
})
</script>

<style scoped>
.svg-icon {
  /* 允许外部通过 class 覆盖颜色 */
  flex: none;
}
</style>
