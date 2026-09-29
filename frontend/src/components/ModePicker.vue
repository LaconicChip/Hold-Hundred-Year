<template>
  <!-- 一局一选：开新局前挑模式（注册时不绑模式）。compact 用于状态条，full 用于终局页。 -->
  <div class="mp" :class="{ 'mp-compact': compact }">
    <span v-if="!compact" class="mp-lead">这一局怎么打？</span>
    <button
      v-for="opt in market.modes"
      :key="opt.code"
      type="button"
      class="mp-opt"
      :class="{ on: modelValue === opt.code }"
      :title="opt.desc"
      @click="$emit('update:modelValue', opt.code)"
    >
      <b>{{ opt.label }}</b>
      <span>{{ opt.desc }}</span>
    </button>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useMarketStore } from '@/stores/market'

const market = useMarketStore()
onMounted(() => market.fetchModes())

defineProps({
  modelValue: { type: String, default: 'STANDARD' },
  compact: { type: Boolean, default: false }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.mp { display: flex; flex-direction: column; gap: var(--sp-2); }
.mp-compact { flex-direction: row; align-items: center; gap: var(--sp-1); }
.mp-lead { font-family: var(--font-fun); font-size: var(--fs-sm); color: var(--ink-2); }
.mp-opt {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: var(--sp-1) var(--sp-3);
  background: var(--card);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  font-family: var(--font-fun);
  color: var(--ink-2);
  cursor: pointer;
  text-align: left;
}
.mp-opt b { font-size: var(--fs-base); color: var(--ink); }
.mp-opt span { font-size: var(--fs-xs); }
.mp-opt.on { color: var(--on-brand); background: var(--brand); box-shadow: var(--shadow); }
.mp-opt.on b, .mp-opt.on span { color: var(--on-brand); }
.mp-compact .mp-opt { flex-direction: row; align-items: baseline; gap: 6px; padding: 2px var(--sp-2); }
.mp-compact .mp-opt span { font-size: 10px; }
</style>
