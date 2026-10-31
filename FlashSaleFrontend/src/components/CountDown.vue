<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  startTime: { type: String, default: '' },
  endTime: { type: String, default: '' },
  status: { type: Number, default: 1 }
})

const now = ref(Date.now())
let timer = null

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const target = computed(() => {
  if (props.status === 0) return new Date(props.startTime).getTime()
  if (props.status === 1) return new Date(props.endTime).getTime()
  return null
})

const label = computed(() => {
  if (props.status === 0) return '距开始'
  if (props.status === 1) return '距结束'
  return ''
})

const remain = computed(() => {
  if (!target.value) return '00:00:00'
  const diff = Math.max(0, target.value - now.value)
  const total = Math.floor(diff / 1000)
  const days = Math.floor(total / 86400)
  const h = String(Math.floor((total % 86400) / 3600)).padStart(2, '0')
  const m = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return days > 0 ? `${days}天 ${h}:${m}:${s}` : `${h}:${m}:${s}`
})
</script>

<template>
  <span class="countdown" v-if="label">
    <em>{{ label }}</em>
    <b class="mono">{{ remain }}</b>
  </span>
</template>

<style scoped>
.countdown {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}
.countdown em { font-style: normal; }
.countdown b { color: var(--accent-dark); font-size: 13px; letter-spacing: 0.02em; }
</style>
