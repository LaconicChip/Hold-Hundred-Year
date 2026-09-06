<template>
  <div class="trade-card card">
    <div class="trade-head">
      <span class="trade-title">买卖委托</span>
      <span class="trade-cash">可用资金 <b class="num">{{ fmtMoney(balance) }}</b></span>
    </div>

    <!-- 休市横幅（优先级最高，覆盖买/卖两条） -->
    <div v-if="closedReason" class="trade-banner">{{ closedReason }}</div>

    <div class="trade-cols">
      <!-- 买入列 -->
      <div class="trade-col">
        <div class="col-label up">买入</div>
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
        <div class="fee">
          <div class="fee-row">
            <span>预估金额</span><span class="num">{{ fmtMoney(buyAmount) }}</span>
          </div>
          <div class="fee-row">
            <span>预估佣金</span><span class="num">{{ fmtMoney(buyCommission) }}</span>
          </div>
          <div class="fee-row strong">
            <span>合计支出</span><span class="num up">{{ fmtMoney(buyTotal) }}</span>
          </div>
        </div>
        <button class="btn btn-primary trade-btn" :disabled="buyDisabled" @click="submit('buy')">
          买入
        </button>
        <div v-if="buyReason" class="reason">{{ buyReason }}</div>
      </div>

      <!-- 卖出列 -->
      <div class="trade-col">
        <div class="col-label down">卖出</div>
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
        <div class="fee">
          <div class="fee-row">
            <span>预估金额</span><span class="num">{{ fmtMoney(sellAmount) }}</span>
          </div>
          <div class="fee-row">
            <span>预估佣金</span><span class="num">{{ fmtMoney(sellCommission) }}</span>
          </div>
          <div class="fee-row">
            <span>预估印花税</span><span class="num">{{ fmtMoney(sellTax) }}</span>
          </div>
          <div class="fee-row strong">
            <span>预计到账</span><span class="num down">{{ fmtMoney(sellTotal) }}</span>
          </div>
        </div>
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

const props = defineProps({
  stockId: { type: Number, required: true },
  price: { type: Number, default: 0 }, // 现价（quotes store）
  changePct: { type: Number, default: 0 }, // 已是百分数
  isOpen: { type: Boolean, default: false }, // 可交易时段
  balance: { type: Number, default: 0 }, // 可用资金（user.assets.cash）
  holdingShares: { type: Number, default: 0 } // 当前股持仓股数
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
const closedReason = computed(() =>
  props.isOpen ? '' : '休市中，无法下单'
)
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
const buyDisabled = computed(
  () => !!closedReason.value || !!buyReason.value || !validQty.value
)
const sellDisabled = computed(
  () => !!closedReason.value || !!sellReason.value || !validQty.value
)

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
const sellTotal = computed(() =>
  +(sellAmount.value - sellCommission.value - sellTax.value).toFixed(2)
)

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
.trade-card {
  padding: 14px 16px 12px;
  margin-top: 16px;
}
.trade-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.trade-title {
  font-family: var(--font-fun);
  font-size: 19px;
}
.trade-cash {
  color: var(--ink-2);
  font-size: 13px;
}
.trade-cash b {
  color: var(--ink);
}
.trade-banner {
  background: #fff3d6;
  border: 2px solid var(--ink);
  border-radius: 8px;
  padding: 6px 10px;
  font-weight: 700;
  font-size: 14px;
  margin-bottom: 12px;
  text-align: center;
}
.trade-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.trade-col {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.col-label {
  font-family: var(--font-fun);
  font-size: 18px;
}
.qty-input {
  width: 100%;
}
.quick-row {
  display: flex;
  gap: 6px;
}
.quick {
  flex: 1;
  border: 1.5px solid var(--ink);
  background: var(--card);
  border-radius: 8px;
  padding: 5px 0;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  font-family: var(--font-body);
}
.quick:hover {
  background: #fdf6e3;
}
.fee {
  border-top: 1px dashed #d8cfbc;
  padding-top: 8px;
  font-size: 13px;
}
.fee-row {
  display: flex;
  justify-content: space-between;
  margin: 3px 0;
  color: var(--ink-2);
}
.fee-row.strong {
  font-weight: 700;
  color: var(--ink);
  border-top: 1px dashed #d8cfbc;
  margin-top: 6px;
  padding-top: 6px;
}
.trade-btn {
  width: 100%;
  justify-content: center;
}
.trade-btn.sell {
  background: var(--card);
  color: var(--down);
}
.reason {
  color: var(--orange);
  font-size: 12px;
  font-weight: 700;
  text-align: center;
}
.lot-warn {
  margin-top: 10px;
  color: var(--orange);
  font-size: 13px;
  font-weight: 700;
  text-align: center;
}
</style>
