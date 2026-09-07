<template>
  <div class="trade-card card">
    <div class="trade-head">
      <span class="trade-title">
        <SvgIcon name="banknote" class="trade-ic" />买卖委托
      </span>
      <span class="trade-cash stat">
        <span class="lab">可用资金</span>
        <b class="num val">{{ fmtMoney(balance) }}</b>
      </span>
    </div>

    <!-- 休市横幅（优先级最高，覆盖买/卖两条） -->
    <div v-if="closedReason" class="trade-banner">
      <SvgIcon name="alert" class="banner-ic" />{{ closedReason }}
    </div>

    <div class="trade-cols">
      <!-- 买入列 -->
      <div class="trade-col">
        <div class="col-label buy-label">
          <SvgIcon name="plus" class="col-ic" />买入
        </div>
        <el-input-number
          v-model="qty"
          :min="0"
          :step="100"
          :controls="true"
          size="large"
          class="qty-input"
          placeholder="输入股数"
        />
        <div class="quick-row">
          <button class="quick" @click="setQty(100)">100</button>
          <button class="quick" @click="setQty(500)">500</button>
          <button class="quick" @click="setQty(1000)">1000</button>
          <button class="quick" @click="setBuyHalf">半仓</button>
          <button class="quick" @click="setBuyAll">全仓</button>
        </div>
        <!-- 买入费用预估（前端本地估算，与后端一致口径） -->
        <dl class="kv fee">
          <dt>预估金额</dt>
          <dd>{{ fmtMoney(buyAmount) }}</dd>
          <dt>预估佣金</dt>
          <dd>{{ fmtMoney(buyCommission) }}</dd>
          <dt class="fee-total">合计支出</dt>
          <dd class="fee-total up">{{ fmtMoney(buyTotal) }}</dd>
        </dl>
        <button class="btn trade-btn buy" :disabled="buyDisabled" @click="submit('buy')">
          买入
        </button>
        <div v-if="buyReason" class="reason">{{ buyReason }}</div>
      </div>

      <!-- 卖出列 -->
      <div class="trade-col">
        <div class="col-label sell-label">
          <SvgIcon name="minus" class="col-ic" />卖出
        </div>
        <el-input-number
          v-model="qty"
          :min="0"
          :step="100"
          :controls="true"
          size="large"
          class="qty-input"
          placeholder="输入股数"
        />
        <div class="quick-row">
          <button class="quick" @click="setQty(100)">100</button>
          <button class="quick" @click="setQty(500)">500</button>
          <button class="quick" @click="setQty(1000)">1000</button>
          <button class="quick" @click="setSellHalf">半仓</button>
          <button class="quick" @click="setSellAll">全仓</button>
        </div>
        <!-- 卖出费用预估（前端本地估算，与后端一致口径） -->
        <dl class="kv fee">
          <dt>预估金额</dt>
          <dd>{{ fmtMoney(sellAmount) }}</dd>
          <dt>预估佣金</dt>
          <dd>{{ fmtMoney(sellCommission) }}</dd>
          <dt>预估印花税</dt>
          <dd>{{ fmtMoney(sellTax) }}</dd>
          <dt class="fee-total">预计到账</dt>
          <dd class="fee-total down">{{ fmtMoney(sellTotal) }}</dd>
        </dl>
        <button class="btn trade-btn sell" :disabled="sellDisabled" @click="submit('sell')">
          卖出
        </button>
        <div v-if="sellReason" class="reason">{{ sellReason }}</div>
      </div>
    </div>

    <!-- 非整手拦截提示 -->
    <div v-if="notWholeLot" class="lot-warn">委托数量须为 100 的整数倍</div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { buy, sell } from '@/api/trade'
import SvgIcon from './SvgIcon.vue'

const props = defineProps({
  stockId: { type: Number, required: true },
  price: { type: Number, default: 0 }, // 现价（quotes store）
  changePct: { type: Number, default: 0 }, // 已是百分数
  isOpen: { type: Boolean, default: false }, // 可交易时段
  balance: { type: Number, default: 0 }, // 可用资金（user.assets.cash）
  holdingShares: { type: Number, default: 0 } // 当前股可卖股数（T+1：available_shares）
})

const emit = defineEmits(['traded'])

const qty = ref(0)
const loading = ref(false)

// 涨停/跌停阈值（按价格推断，非后端字段，与行情表展示口径一致）
const LIMIT_UP = 9.8
const LIMIT_DOWN = -9.8

function setQty(v) {
  qty.value = v
}
// 买入全仓：按可用资金估算可买整手数（floor 到 100 股）
function maxBuyShares() {
  if (!props.price || props.price <= 0 || props.balance <= 0) return 0
  return Math.floor(props.balance / props.price / 100) * 100
}
function setBuyAll() {
  setQty(maxBuyShares())
}
function setBuyHalf() {
  setQty(Math.floor(maxBuyShares() / 2 / 100) * 100)
}
// 卖出全仓/半仓：按持仓股数（floor 到 100 股）
function setSellAll() {
  setQty(Math.floor(props.holdingShares / 100) * 100)
}
function setSellHalf() {
  setQty(Math.floor(props.holdingShares / 2 / 100) * 100)
}

// 数量合法性：>0 且为 100 整数倍
const validQty = computed(() => qty.value > 0 && qty.value % 100 === 0)
const notWholeLot = computed(() => qty.value > 0 && qty.value % 100 !== 0)

// 禁用原因（按优先级逐条）
const closedReason = computed(() => (props.isOpen ? '' : '休市中，无法下单'))
const buyReason = computed(() => {
  if (closedReason.value) return ''
  if (props.changePct >= LIMIT_UP) return '涨停封死，买单排不上'
  return ''
})
const sellReason = computed(() => {
  if (closedReason.value) return ''
  if (props.changePct <= LIMIT_DOWN) return '跌停封死，卖不出去'
  return ''
})
const buyDisabled = computed(() => !!closedReason.value || !!buyReason.value || !validQty.value)
const sellDisabled = computed(() => !!closedReason.value || !!sellReason.value || !validQty.value)

// 费用预估口径：佣金万3 最低5元，卖出印花税千1（与后端 GameConstants.COMMISSION_RATE=0.0003 一致）
function commissionOf(amount) {
  return Math.max(amount * 0.0003, 5)
}
const buyAmount = computed(() => +(qty.value * props.price).toFixed(2))
const buyCommission = computed(() => +commissionOf(buyAmount.value).toFixed(2))
const buyTotal = computed(() => +(buyAmount.value + buyCommission.value).toFixed(2))

const sellAmount = computed(() => +(qty.value * props.price).toFixed(2))
const sellCommission = computed(() => +commissionOf(sellAmount.value).toFixed(2))
const sellTax = computed(() => +(sellAmount.value * 0.001).toFixed(2))
const sellTotal = computed(() => +(sellAmount.value - sellCommission.value - sellTax.value).toFixed(2))

function fmtMoney(v) {
  const n = +v
  if (!isFinite(n)) return '0.00'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function submit(side) {
  // 非整手前端拦截（理论上按钮已禁用，二次保险）
  if (!validQty.value) {
    ElMessage.warning('委托数量须为 100 的整数倍')
    return
  }
  if (loading.value) return
  loading.value = true
  try {
    const api = side === 'buy' ? buy : sell
    // 调用后端：失败 Error(msg) 透传（如涨停封死/休市中/非整手/资金不足）
    const dto = await api(props.stockId, qty.value)
    // 成功：TradeResultDTO {stockId,shares,price,amount,commission,tax,balance}
    const p = dto.price != null ? dto.price : props.price
    ElMessage.success(
      `${side === 'buy' ? '买入' : '卖出'}成交 ${qty.value} 股 ｜ 成交价 ${fmtMoney(p)} ｜ 金额 ${fmtMoney(dto.amount)} ｜ 佣金 ${fmtMoney(dto.commission)}`
    )
    qty.value = 0
    emit('traded') // 通知父页刷新持仓与资产
  } catch (err) {
    // 业务失败文案直接透传（后端 msg）
    ElMessage.error(err && err.message ? err.message : '委托失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.trade-card { padding: var(--sp-3) var(--sp-4) var(--sp-3); }
.trade-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
}
.trade-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-fun);
  font-size: var(--fs-xl);
}
.trade-ic { width: 1em; height: 1em; color: var(--brand); }
.trade-cash { flex-direction: row; align-items: baseline; gap: var(--sp-2); padding: 2px var(--sp-3); }
.trade-cash .val { font-size: var(--fs-lg); }

/* 休市横幅 */
.trade-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: var(--yellow-soft);
  border: var(--border);
  border-radius: var(--radius-sm);
  padding: 6px var(--sp-3);
  font-weight: 700;
  font-size: var(--fs-base);
  margin-bottom: var(--sp-3);
}
.banner-ic { width: 1.1em; height: 1.1em; color: var(--orange); }

.trade-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
}
.trade-col {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-3);
  border: var(--border);
  border-radius: var(--radius-md);
  background: var(--card);
}
.col-label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  font-family: var(--font-fun);
  font-size: var(--fs-lg);
  border: var(--border);
  border-radius: var(--radius-pill);
  padding: 2px var(--sp-4);
  box-shadow: var(--shadow-sm);
  align-self: flex-start;
}
.col-ic { width: 0.9em; height: 0.9em; }
.buy-label { background: var(--up); color: var(--on-brand); }
.sell-label { background: var(--down); color: var(--on-brand); }

.qty-input { width: 100%; }
.quick-row { display: flex; gap: 6px; }
.quick {
  flex: 1;
  border: var(--border-thin);
  background: var(--card);
  color: var(--ink-2);
  border-radius: var(--radius-sm);
  padding: 4px 0;
  font-size: var(--fs-sm);
  font-weight: 700;
  cursor: pointer;
  font-family: var(--font-body);
  transition: 0.08s;
}
.quick:hover { background: var(--hover); color: var(--ink); border-color: var(--ink); }

.fee {
  border-top: 1px dashed var(--dash);
  padding-top: var(--sp-2);
  font-size: var(--fs-sm);
}
.fee .fee-total {
  border-top: 1px dashed var(--dash);
  padding-top: var(--sp-1);
  margin-top: var(--sp-1);
  color: var(--ink);
}

.trade-btn { width: 100%; justify-content: center; color: var(--on-brand); }
.trade-btn.buy { background: var(--up); }
.trade-btn.sell { background: var(--down); }
.trade-btn:disabled { color: var(--ink); }

.reason {
  color: var(--orange);
  font-size: var(--fs-xs);
  font-weight: 700;
  text-align: center;
}
.lot-warn {
  margin-top: var(--sp-2);
  color: var(--orange);
  font-size: var(--fs-sm);
  font-weight: 700;
  text-align: center;
}

@media (max-width: 760px) {
  .trade-cols { grid-template-columns: 1fr; }
}
</style>
