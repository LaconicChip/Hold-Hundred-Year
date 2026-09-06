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
  </div>
</template>

<script setup>
// 登录页占位组件：仅用于验收设计令牌是否生效（Task 12.1 正式实现）
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
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
</style>
