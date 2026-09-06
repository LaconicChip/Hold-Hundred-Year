<template>
  <!-- NPC 头像：优先加载 AI 生成 PNG，缺图则按姓氏色块兜底 -->
  <span class="npc-avatar" :style="avatarStyle">
    <img v-if="imgUrl" :src="imgUrl" :alt="npc" class="npc-img" />
    <span v-else class="npc-ch">{{ fallback.ch }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  // NPC 代码（大小写均可）
  npc: { type: String, required: true },
  size: { type: Number, default: 40 }
})

// 任务 12.6 才会生成 PNG；当前文件不存在则走兜底
const modules = import.meta.glob('../assets/img/*.png', { eager: true })

const imgUrl = computed(() => {
  const key = `../assets/img/${props.npc.toLowerCase()}.png`
  const m = modules[key]
  return m ? m.default : null
})

// 已知 NPC 的姓氏与配色（与风格稿头像一致）
const FALLBACK = {
  DAMA: { ch: '妈', bg: '#e86a5e' },
  LAOWANG: { ch: '王', bg: '#5e8de8' },
  LAOLI: { ch: '李', bg: '#6aa872' },
  CHIEF: { ch: '析', bg: '#8a6fd8' },
  JUSHI: { ch: '居', bg: '#7a9e7e' },
  LAOQIAN: { ch: '钱', bg: '#b58b4a' },
  XINJIMIN: { ch: '基', bg: '#5eb8b0' },
  CHENGXUYUAN: { ch: '猿', bg: '#c77fa8' }
}

// 任意未知 code：稳定 hash 配色，首字作为兜底字
const PALETTE = [
  '#e86a5e', '#5e8de8', '#6aa872', '#8a6fd8', '#7a9e7e',
  '#b58b4a', '#5eb8b0', '#c77fa8', '#d98b5e', '#a86fd8'
]
function hashColor(code) {
  let h = 0
  for (const c of code) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return PALETTE[h % PALETTE.length]
}

const fallback = computed(() => {
  const hit = FALLBACK[props.npc.toUpperCase()]
  if (hit) return hit
  return {
    ch: props.npc.charAt(0).toUpperCase(),
    bg: hashColor(props.npc)
  }
})

const avatarStyle = computed(() => ({
  width: props.size + 'px',
  height: props.size + 'px',
  minWidth: props.size + 'px',
  fontSize: Math.round(props.size * 0.42) + 'px',
  background: fallback.value.bg
}))
</script>

<style scoped>
.npc-avatar {
  border: 2px solid var(--ink);
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.npc-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
  display: block;
}
.npc-ch {
  font-family: var(--font-fun);
  color: #fff;
}
</style>
