<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const token = ref(localStorage.getItem('token'))

function logout() {
  localStorage.removeItem('token')
  token.value = null
  router.push('/login')
}
</script>

<template>
  <header class="nav">
    <div class="nav-inner">
      <router-link to="/goods" class="brand">
        <span class="brand-mark">FS</span>
        <span class="brand-text">
          <b>FlashSale</b>
          <small>高并发秒杀</small>
        </span>
      </router-link>

      <nav class="links">
        <router-link to="/goods">商品</router-link>
        <router-link to="/login" v-if="!token">登录</router-link>
        <button v-else class="logout" @click="logout">退出</button>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.nav {
  position: sticky;
  top: 0;
  z-index: 10;
  background: rgba(244, 242, 236, 0.86);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line);
}

.nav-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 14px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  font-weight: 800;
  font-size: 14px;
  letter-spacing: 0.02em;
  box-shadow: 0 6px 16px rgba(226, 59, 46, 0.28);
}

.brand-text { display: flex; flex-direction: column; line-height: 1.2; }
.brand-text b { font-size: 15px; letter-spacing: -0.01em; }
.brand-text small { font-size: 10.5px; color: var(--muted); letter-spacing: 0.12em; }

.links { display: flex; align-items: center; gap: 20px; font-size: 13.5px; }
.links a { color: #4c515b; }
.links a:hover { color: var(--accent-dark); }

.logout {
  background: transparent;
  color: #4c515b;
  font-size: 13.5px;
  padding: 0;
}
.logout:hover { color: var(--accent-dark); }
</style>
