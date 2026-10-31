<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'

const router = useRouter()
const form = ref({ phone: '13800000001', password: '123456' })
const loading = ref(false)

async function onSubmit() {
  if (loading.value) return
  loading.value = true
  try {
    const token = await login(form.value)
    localStorage.setItem('token', token)
    router.push('/goods')
  } catch (e) {
    // 错误提示已由 axios 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <section class="intro">
      <span class="badge">FLASHSALE · SECKILL</span>
      <h1>限时秒杀<br /><span>Redis 扛住瞬时流量</span></h1>
      <p>库存原子扣减、MQ 削峰异步落库、一人一单。登录后即可参与抢购。</p>
      <ul>
        <li><i></i>Lua 脚本原子判定，不超卖</li>
        <li><i></i>RabbitMQ 排队，异步生成订单</li>
        <li><i></i>500ms 轮询，实时拿到订单号</li>
      </ul>
    </section>

    <section class="card">
      <h2>账号登录</h2>
      <p class="hint">演示账号已预填，密码 123456</p>
      <form @submit.prevent="onSubmit">
        <label>
          <span>手机号</span>
          <input v-model.trim="form.phone" type="text" placeholder="请输入手机号" autocomplete="username" />
        </label>
        <label>
          <span>密码</span>
          <input v-model.trim="form.password" type="password" placeholder="请输入密码" autocomplete="current-password" />
        </label>
        <button type="submit" :disabled="loading">{{ loading ? '登录中...' : '登录' }}</button>
      </form>
      <p class="foot">没有账号？本项目不提供注册接口，可使用种子数据中的测试用户。</p>
    </section>
  </div>
</template>

<style scoped>
.login-wrap {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 48px;
  align-items: center;
  min-height: 62vh;
}

.badge {
  display: inline-block;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 10.5px;
  letter-spacing: 0.16em;
  color: var(--accent-dark);
  background: var(--accent-soft);
  border: 1px solid #f3b6b0;
  padding: 5px 10px;
  border-radius: 999px;
  margin-bottom: 18px;
}

h1 {
  margin: 0 0 18px;
  font-size: clamp(32px, 4vw, 46px);
  line-height: 1.12;
  letter-spacing: -0.04em;
  font-weight: 800;
}
h1 span { display: block; margin-top: 8px; font-size: 0.44em; font-weight: 600; color: var(--accent); letter-spacing: 0.02em; }

.intro p { margin: 0 0 20px; color: #4f5560; max-width: 440px; }

.intro ul { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; font-size: 13.5px; color: #3d424b; }
.intro li { display: flex; align-items: center; gap: 10px; }
.intro li i { width: 7px; height: 7px; border-radius: 50%; background: var(--teal); box-shadow: 0 0 0 4px rgba(15, 118, 110, 0.12); }

.card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 30px 30px 26px;
  box-shadow: var(--shadow);
}

.card h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -0.02em; }
.hint { margin: 0 0 22px; font-size: 12.5px; color: var(--muted); }

label { display: block; margin-bottom: 16px; }
label span { display: block; font-size: 12.5px; color: var(--muted); margin-bottom: 6px; }

input {
  width: 100%;
  padding: 11px 13px;
  font-size: 14px;
  font-family: inherit;
  color: var(--ink);
  background: #fbf9f4;
  border: 1px solid var(--line);
  border-radius: 9px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(226, 59, 46, 0.12); }

button[type="submit"] {
  width: 100%;
  margin-top: 6px;
  padding: 12px;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  font-size: 14.5px;
  font-weight: 650;
  box-shadow: 0 8px 20px rgba(226, 59, 46, 0.22);
  transition: background 0.15s, transform 0.15s;
}
button[type="submit"]:hover:not(:disabled) { background: var(--accent-dark); transform: translateY(-1px); }
button[type="submit"]:disabled { opacity: 0.65; cursor: not-allowed; }

.foot { margin: 18px 0 0; font-size: 11.5px; color: var(--muted); line-height: 1.7; }

@media (max-width: 860px) {
  .login-wrap { grid-template-columns: 1fr; gap: 28px; min-height: auto; }
  .intro ul { display: none; }
}
</style>
