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

// 已知 NPC 的姓氏与配色（与风格稿头像一致，颜色取令牌不硬编码）
const FALLBACK = {
  DAMA: { ch: '妈', bg: 'var(--av-dama)' },
  LAOWANG: { ch: '王', bg: 'var(--av-lao)' },
  LAOLI: { ch: '李', bg: 'var(--av-li)' },
  CHIEF: { ch: '析', bg: 'var(--av-chief)' },
  JUSHI: { ch: '居', bg: 'var(--av-jushi)' },
  LAOQIAN: { ch: '钱', bg: 'var(--av-laoqian)' },
  XINJIMIN: { ch: '基', bg: 'var(--av-xinjimin)' },
  CHENGXUYUAN: { ch: '猿', bg: 'var(--av-chengxuyuan)' }
}

// 任意未知 code：稳定 hash 配色，首字作为兜底字
const PALETTE = [
  'var(--av-dama)', 'var(--av-lao)', 'var(--av-li)', 'var(--av-chief)', 'var(--av-jushi)',
  'var(--av-laoqian)', 'var(--av-xinjimin)', 'var(--av-chengxuyuan)', 'var(--av-p1)', 'var(--av-p2)'
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
  color: var(--on-brand);
}
</style>
