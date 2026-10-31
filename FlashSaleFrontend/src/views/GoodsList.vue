<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listGoods } from '../api/goods'
import CountDown from '../components/CountDown.vue'

const router = useRouter()
const goods = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    goods.value = await listGoods()
  } catch (e) {
    goods.value = []
  } finally {
    loading.value = false
  }
})

function statusText(status) {
  if (status === 0) return '未开始'
  if (status === 1) return '秒杀中'
  return '已结束'
}

function goSeckill(item) {
  if (item.seckillStatus !== 1) return
  router.push(`/order/${item.id}`)
}
</script>

<template>
  <section class="head">
    <div>
      <span class="eyebrow">LIMITED TIME OFFER</span>
      <h1>今日秒杀</h1>
      <p>库存有限，先到先得。点击「立即秒杀」进入排队，系统异步生成订单。</p>
    </div>
    <div class="tip">
      <b>一人一单</b>
      <span>同一商品重复下单会被拒绝</span>
    </div>
  </section>

  <div v-if="loading" class="empty">正在加载商品...</div>
  <div v-else-if="goods.length === 0" class="empty">暂无商品</div>

  <section v-else class="grid">
    <article class="card" v-for="item in goods" :key="item.id">
      <div class="thumb">
        <span class="thumb-glow"></span>
        <span class="thumb-word">{{ item.name }}</span>
        <span class="status" :class="'st' + item.seckillStatus">{{ statusText(item.seckillStatus) }}</span>
      </div>

      <div class="body">
        <h3>{{ item.name }}</h3>
        <p class="desc">{{ item.title }}</p>

        <div class="price-row">
          <span class="seckill-price"><em>¥</em>{{ item.seckillPrice }}</span>
          <span class="origin-price">¥{{ item.price }}</span>
        </div>

        <div class="meta">
          <span>库存 <b>{{ item.stockCount }}</b></span>
          <CountDown :start-time="item.startTime" :end-time="item.endTime" :status="item.seckillStatus" />
        </div>

        <button
          class="buy"
          :class="{ disabled: item.seckillStatus !== 1 }"
          :disabled="item.seckillStatus !== 1"
          @click="goSeckill(item)"
        >
          {{ item.seckillStatus === 1 ? '立即秒杀' : statusText(item.seckillStatus) }}
        </button>
      </div>
    </article>
  </section>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  margin-bottom: 28px;
  flex-wrap: wrap;
}

.eyebrow {
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 10.5px;
  letter-spacing: 0.16em;
  color: var(--accent);
}

.head h1 { margin: 8px 0 8px; font-size: clamp(28px, 3.6vw, 38px); letter-spacing: -0.035em; }
.head p { margin: 0; color: #4f5560; font-size: 14px; }

.tip {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 16px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  background: #fbf9f4;
  font-size: 11.5px;
  color: var(--muted);
}
.tip b { color: var(--ink); font-size: 13px; }

.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: 0 2px 10px rgba(23, 24, 28, 0.03);
  transition: transform 0.18s, box-shadow 0.18s;
}
.card:hover { transform: translateY(-3px); box-shadow: var(--shadow); }

.thumb {
  position: relative;
  height: 148px;
  background: linear-gradient(135deg, #1b1e24 0%, #33363f 100%);
  display: flex;
  align-items: flex-end;
  padding: 14px 16px;
  overflow: hidden;
}
.thumb-glow {
  position: absolute;
  width: 170px;
  height: 170px;
  right: -40px;
  top: -60px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(226, 59, 46, 0.55), transparent 68%);
}
.thumb-word {
  position: relative;
  color: rgba(255, 255, 255, 0.88);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.2;
  max-width: 78%;
}
.status {
  position: absolute;
  top: 12px;
  right: 12px;
  font-size: 10.5px;
  padding: 3px 8px;
  border-radius: 999px;
  font-weight: 600;
}
.st0 { background: rgba(254, 243, 199, 0.95); color: #b45309; }
.st1 { background: rgba(253, 236, 234, 0.96); color: #a9251b; }
.st2 { background: rgba(255, 255, 255, 0.22); color: #f2efe9; }

.body { padding: 16px 16px 18px; }
.body h3 { margin: 0 0 4px; font-size: 16px; letter-spacing: -0.01em; }
.desc { margin: 0 0 12px; font-size: 12.5px; color: var(--muted); min-height: 20px; }

.price-row { display: flex; align-items: baseline; gap: 8px; margin-bottom: 10px; }
.seckill-price { color: var(--accent); font-size: 24px; font-weight: 800; letter-spacing: -0.03em; }
.seckill-price em { font-style: normal; font-size: 13px; margin-right: 1px; }
.origin-price { color: #9aa0aa; font-size: 12px; text-decoration: line-through; }

.meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 14px;
}
.meta b { color: var(--ink); }

.buy {
  width: 100%;
  padding: 10px;
  border-radius: 9px;
  background: var(--accent);
  color: #fff;
  font-size: 13.5px;
  font-weight: 650;
  transition: background 0.15s;
}
.buy:hover:not(.disabled) { background: var(--accent-dark); }
.buy.disabled { background: #e8e4dc; color: #9aa0aa; cursor: not-allowed; }

.empty {
  padding: 80px 0;
  text-align: center;
  color: var(--muted);
  font-size: 14px;
}

@media (max-width: 960px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .grid { grid-template-columns: 1fr; }
  .tip { display: none; }
}
</style>
