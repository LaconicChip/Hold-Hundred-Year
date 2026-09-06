<template>
  <div class="login-wrap">
    <div class="card login-scene">
      <!-- 银行大门插画占位（AI 生成 PNG 位，先用 SVG 印章风格） -->
      <div class="bank-art seal" style="border-color: var(--brand); color: var(--brand);">百年</div>
      <h1 class="title">百年证券营业部</h1>
      <p class="slogan">“你持有一百年看看，涨跌都算我的。”—— 门口大爷</p>
      <p class="dev-tip">登录页开发中</p>
      <!-- 用设计令牌验证色块与字体 -->
      <div class="flag-row">
        <span class="era-flag era-bull">牛市</span>
        <span class="era-flag era-bear">股灾</span>
        <span class="sticker st-st">ST</span>
        <span class="sticker st-halt">停牌</span>
      </div>
      <div class="num-row">
        <span class="num up">88.48 +9.98%</span>
        <span class="num down">7.52 -10.00%</span>
        <span class="num">123,456.78</span>
      </div>
    </div>

    <!-- 组件验收区：截图验收自制组件渲染（不新增页面） -->
    <div class="card accept-area">
      <h2 class="accept-title">组件验收区</h2>

      <section class="accept-sec">
        <h3>SvgIcon 全 10 个</h3>
        <div class="row">
          <span v-for="n in icons" :key="n" class="cell">
            <SvgIcon :name="n" :size="30" />
            <small>{{ n }}</small>
          </span>
        </div>
      </section>

      <section class="accept-sec">
        <h3>StickerTag 三种</h3>
        <div class="row">
          <StickerTag type="st" text="ST 5%涨跌停" />
          <StickerTag type="halt" text="小黑屋停牌" />
          <StickerTag type="plain" text="普通标签" />
        </div>
      </section>

      <section class="accept-sec">
        <h3>SealStamp 正常 / 炸板 / mini</h3>
        <div class="row">
          <SealStamp text="封板" />
          <SealStamp text="炸板" broken />
          <SealStamp text="封板" mini />
        </div>
      </section>

      <section class="accept-sec">
        <h3>TierBadge 五档（横排）</h3>
        <div class="row">
          <TierBadge tier="RETAIL" />
          <TierBadge tier="MIDDLE" />
          <TierBadge tier="BIG" />
          <TierBadge tier="HOTSHOT" />
          <TierBadge tier="MASTER" />
        </div>
        <h4>竖排示例</h4>
        <div class="row">
          <TierBadge tier="MASTER" vertical sub="100万" />
        </div>
      </section>

      <section class="accept-sec">
        <h3>NpcAvatar 八位 NPC（当前缺图，走姓氏色块兜底）</h3>
        <div class="row">
          <div v-for="npc in npcs" :key="npc" class="cell">
            <NpcAvatar :npc="npc" :size="44" />
            <small>{{ npc }}</small>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
// 登录页占位组件：验收设计令牌 + 自制基础组件（Task 12.1 正式实现）
import SvgIcon from '@/components/SvgIcon.vue'
import StickerTag from '@/components/StickerTag.vue'
import SealStamp from '@/components/SealStamp.vue'
import TierBadge from '@/components/TierBadge.vue'
import NpcAvatar from '@/components/NpcAvatar.vue'

const icons = ['chart', 'play', 'skip', 'alert', 'bell', 'door', 'flame', 'bull', 'bear', 'bank']
const npcs = ['DAMA', 'LAOWANG', 'LAOLI', 'CHIEF', 'JUSHI', 'LAOQIAN', 'XINJIMIN', 'CHENGXUYUAN']
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 24px;
}
.login-scene {
  max-width: 520px;
  width: 100%;
  text-align: center;
  background: linear-gradient(#fdf3dd, #f6ecd4);
  position: relative;
  overflow: hidden;
}
.bank-art {
  width: 88px;
  height: 88px;
  font-size: 22px;
  margin: 6px auto 14px;
}
.title {
  font-family: var(--font-fun);
}
.slogan {
  color: var(--ink-2);
  margin-top: 6px;
}
.dev-tip {
  font-family: var(--font-fun);
  font-size: 20px;
  color: var(--brand);
  margin: 18px 0;
}
.flag-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 14px;
}
.num-row {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  justify-content: center;
  font-size: 16px;
}

/* 验收区 */
.accept-area {
  max-width: 720px;
  width: 100%;
}
.accept-title {
  font-family: var(--font-fun);
  font-size: 22px;
  margin: 0 0 6px;
}
.accept-sec {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--ink-3);
}
.accept-sec h3 {
  font-family: var(--font-fun);
  font-size: 16px;
  margin-bottom: 8px;
}
.accept-sec h4 {
  font-size: 13px;
  color: var(--ink-2);
  margin: 12px 0 6px;
}
.row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: center;
}
.cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-2);
}
</style>
