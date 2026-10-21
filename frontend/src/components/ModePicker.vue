<template>
  <!-- 一局一选：开新局前挑模式（新建存档时不绑模式）。compact 用于状态条，full 用于终局页。 -->
  <div class="mp" :class="{ 'mp-compact': compact }" @mouseleave="hovered = ''">
    <span v-if="!compact" class="mp-lead">这一局怎么打？</span>
    <button
      v-for="opt in market.modes"
      :key="opt.code"
      type="button"
      class="mp-opt"
      :class="{ on: modelValue === opt.code }"
      :aria-describedby="hovered === opt.code ? `mp-tip-${opt.code}` : undefined"
      @mouseenter="hovered = opt.code"
      @focus="hovered = opt.code"
      @blur="hovered = ''"
      @click="$emit('update:modelValue', opt.code)"
    >
      <b>{{ opt.label }}</b>
      <span v-if="!compact">{{ opt.description }}</span>
    </button>

    <!-- 悬停/聚焦看详情：两种模式共用 90 跳与全部玩法机制，差别只在时间轴与单日振幅 -->
    <div v-if="detail" :id="`mp-tip-${detail.code}`" class="mp-tip" role="tooltip">
      <b class="mp-tip-title">{{ detail.label }}</b>
      <span class="mp-tip-desc">{{ detail.description }}</span>
      <span class="mp-tip-note">
        两种模式都是每天 90 跳、11 个纪元，剧本日、封板、T+1、打新、融资规则完全一致；
        差别只在时间轴长度与单日振幅。
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useMarketStore } from '@/stores/market'

const market = useMarketStore()
const hovered = ref('')
const detail = computed(() => {
  if (!hovered.value) return null
  const opt = market.modes.find((m) => m.code === hovered.value)
  return opt && opt.description ? opt : null
})

onMounted(() => market.fetchModes())

defineProps({
  modelValue: { type: String, default: 'STANDARD' },
  compact: { type: Boolean, default: false }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.mp { position: relative; display: flex; flex-direction: column; gap: var(--sp-2); }
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

/* 悬停详情卡：贴着选择器右缘向下展开，不挤动状态条布局 */
.mp-tip {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 60;
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: min(280px, 72vw);
  padding: var(--sp-2) var(--sp-3);
  background: var(--paper-warm);
  border: var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow);
  text-align: left;
}
.mp-tip-title {
  font-family: var(--font-fun);
  font-size: var(--fs-base);
  color: var(--ink);
}
.mp-tip-desc { font-size: var(--fs-sm); color: var(--ink); }
.mp-tip-note { font-size: var(--fs-xs); line-height: 1.5; color: var(--ink-2); }
/* 透明桥：让指针能从按钮平滑移进详情卡，不会穿过 8px 空隙时触发 mouseleave */
.mp-tip::before {
  content: '';
  position: absolute;
  top: -8px;
  left: 0;
  right: 0;
  height: 8px;
}
</style>
