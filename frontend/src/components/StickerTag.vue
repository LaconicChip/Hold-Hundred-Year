<template>
  <!-- 贴纸标签：st=橙底白字 / halt=米黄底带门图标 / plain=灰描边小标签 -->
  <span class="sticker" :class="cls">
    <slot name="icon">
      <SvgIcon v-if="type === 'halt'" name="door" class="st-ic" />
    </slot>
    <span class="st-text">{{ text }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import SvgIcon from './SvgIcon.vue'

const props = defineProps({
  type: { type: String, default: 'plain' }, // 'st' | 'halt' | 'plain'
  text: { type: String, default: '' }
})

const cls = computed(() => ({
  'st-st': props.type === 'st',
  'st-halt': props.type === 'halt',
  'st-plain': props.type === 'plain'
}))
</script>

<style scoped>
/* plain：灰描边小标签，覆盖 .sticker 默认贴纸底色与阴影 */
.sticker.st-plain {
  background: var(--card);
  color: var(--ink-2);
  border-color: var(--ink-3);
  box-shadow: none;
  transform: none;
}
.st-ic {
  width: 13px;
  height: 13px;
}
</style>
