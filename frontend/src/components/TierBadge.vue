<template>
  <!-- 段位徽章：默认横排紧凑（状态条用法），vertical 切换竖排（风格稿默认） -->
  <span class="tier" :class="[tierCls, vertical ? 'is-vert' : 'is-row']">
    <span class="badge">{{ label }}</span>
    <small v-if="vertical && sub" class="tier-sub">{{ sub }}</small>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  // 段位代码
  tier: {
    type: String,
    default: 'RETAIL',
    validator: (v) => ['RETAIL', 'MIDDLE', 'BIG', 'HOTSHOT', 'MASTER'].includes(v)
  },
  vertical: { type: Boolean, default: false },
  // 竖排时可选的补充说明（如资金门槛）
  sub: { type: String, default: '' }
})

const MAP = {
  RETAIL: { label: '散户', cls: 'retail' },
  MIDDLE: { label: '中户', cls: 'middle' },
  BIG: { label: '大户', cls: 'big' },
  HOTSHOT: { label: '游资', cls: 'hot' },
  MASTER: { label: '牛散', cls: 'master' }
}

const label = computed(() => MAP[props.tier].label)
const tierCls = computed(() => MAP[props.tier].cls)
</script>

<style scoped>
/* 横排紧凑：同风格稿状态条用法（badge 13px / padding 2px 10px） */
.tier.is-row {
  flex-direction: row;
}
.tier.is-row .badge {
  font-size: 13px;
  padding: 2px 10px;
  box-shadow: 2px 2px 0 var(--ink);
}
.tier.is-vert .badge {
  /* 沿用 tokens.css 默认竖排样式（16px / 6px 18px） */
}
.tier-sub {
  font-size: 11px;
  color: var(--ink-2);
}
</style>
