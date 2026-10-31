<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getGoods } from '../api/goods'
import { queryResult, seckill } from '../api/seckill'

const route = useRoute()
const router = useRouter()
const goodsId = route.params.goodsId

const stage = ref('queuing') // queuing | success | failed | timeout
const message = ref('正在排队...')
const orderId = ref(null)
const goods = ref(null)

let timer = null
let attempts = 0
const MAX_ATTEMPTS = 60 // 500ms * 60 = 30 秒

const statusTitle = computed(() => {
  if (stage.value === 'success') return '抢购成功'
  if (stage.value === 'failed') return '秒杀失败'
  if (stage.value === 'timeout') return '仍在排队'
  return '正在排队'
})

const statusDesc = computed(() => {
  if (stage.value === 'success') return '订单已生成，请留意订单号'
  if (stage.value === 'failed') return message.value
  if (stage.value === 'timeout') return '排队时间较长，可稍后重新查询'
  return '系统正在异步创建订单，请勿重复提交'
})

async function loadGoods() {
  try {
    goods.value = await getGoods(goodsId)
  } catch (e) {
    goods.value = null
  }
}

async function startSeckill() {
  try {
    await seckill(goodsId)
    message.value = '购买成功!正在排队...'
    poll()
  } catch (e) {
    if (e && e.message === '未登录或Token无效') {
      router.push('/login')
      return
    }
    stage.value = 'failed'
    message.value = (e && e.message) || '秒杀失败'
  }
}

async function poll() {
  attempts += 1
  try {
    const result = await queryResult(goodsId)
    if (result && Number(result.orderId) > 0) {
      orderId.value = result.orderId
      stage.value = 'success'
      stopPolling()
      return
    }
    if (attempts >= MAX_ATTEMPTS) {
      stage.value = 'timeout'
      stopPolling()
      return
    }
    timer = setTimeout(poll, 500)
  } catch (e) {
    stopPolling()
    stage.value = 'failed'
    message.value = (e && e.message) || '查询失败'
    if (e && e.message === '未登录或Token无效') router.push('/login')
  }
}

function stopPolling() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function retry() {
  stopPolling()
  stage.value = 'queuing'
  message.value = '正在重新查询...'
  attempts = 0
  poll()
}

onMounted(() => {
  loadGoods()
  startSeckill()
})

onUnmounted(stopPolling)
</script>

<template>
  <div class="wrap">
    <div class="panel" :class="stage">
      <div class="ring">
        <span v-if="stage === 'queuing'" class="spinner"></span>
        <span v-else-if="stage === 'success'" class="mark ok">OK</span>
        <span v-else-if="stage === 'timeout'" class="mark wait">...</span>
        <span v-else class="mark fail">!</span>
      </div>

      <h1>{{ statusTitle }}</h1>
      <p class="desc">{{ statusDesc }}</p>

      <div class="goods" v-if="goods">
        <div class="goods-name">{{ goods.name }}</div>
        <div class="goods-price">
          秒杀价 <b>¥{{ goods.seckillPrice }}</b>
          <span class="origin">原价 ¥{{ goods.price }}</span>
        </div>
      </div>

      <div class="order" v-if="orderId">
        <span>订单号</span>
        <b class="mono">{{ orderId }}</b>
      </div>

      <div class="actions">
        <button class="primary" v-if="stage === 'timeout'" @click="retry">重新查询</button>
        <button class="primary" @click="router.push('/goods')">返回商品列表</button>
        <button class="ghost" v-if="!orderId" @click="router.push(`/goods`)">看看其他商品</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrap { display: flex; justify-content: center; padding: 24px 0; }

.panel {
  width: 100%;
  max-width: 520px;
  text-align: center;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 44px 34px 34px;
  box-shadow: var(--shadow);
}

.ring {
  width: 76px;
  height: 76px;
  margin: 0 auto 18px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #fbf9f4;
  border: 1px solid var(--line);
}

.spinner {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: 3px solid #e8e4dc;
  border-top-color: var(--accent);
  animation: spin 0.9s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.mark {
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 18px;
  font-weight: 800;
}
.mark.ok { color: #22703a; }
.mark.wait { color: var(--amber); letter-spacing: 0.1em; }
.mark.fail { color: var(--accent-dark); }

h1 { margin: 0 0 8px; font-size: 25px; letter-spacing: -0.025em; }
.desc { margin: 0 0 24px; font-size: 13.5px; color: var(--muted); }

.goods {
  text-align: left;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 14px;
  background: #fbf9f4;
}
.goods-name { font-size: 14px; font-weight: 650; margin-bottom: 4px; }
.goods-price { font-size: 12.5px; color: var(--muted); }
.goods-price b { color: var(--accent); font-size: 15px; }
.origin { margin-left: 10px; text-decoration: line-through; color: #9aa0aa; }

.order {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid #b9ded8;
  background: #e6f4f1;
  border-radius: 12px;
  padding: 13px 16px;
  margin-bottom: 20px;
  font-size: 13px;
  color: var(--teal);
}
.order b { font-size: 17px; letter-spacing: 0.02em; }

.actions { display: flex; flex-direction: column; gap: 10px; }

.primary {
  padding: 11px;
  border-radius: 10px;
  background: var(--ink);
  color: #f7f5ef;
  font-size: 14px;
  font-weight: 600;
  transition: background 0.15s;
}
.primary:hover { background: #2a2c33; }

.ghost {
  padding: 10px;
  border-radius: 10px;
  background: transparent;
  border: 1px solid var(--line);
  color: #4f5560;
  font-size: 13px;
}
.ghost:hover { background: #fbf9f4; }
</style>
