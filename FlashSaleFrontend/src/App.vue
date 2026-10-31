<script setup>
import NavBar from './components/NavBar.vue'
</script>

<template>
  <NavBar />
  <main class="page">
    <router-view />
  </main>
</template>

<style>
:root {
  --bg: #f4f2ec;
  --panel: #ffffff;
  --ink: #17181c;
  --muted: #626873;
  --line: #e2ded4;
  --accent: #e23b2e;
  --accent-dark: #a9251b;
  --accent-soft: #fdecea;
  --teal: #0f766e;
  --amber: #b45309;
  --radius: 14px;
  --shadow: 0 10px 30px rgba(23, 24, 28, 0.06);
  font-family: "Inter", "PingFang SC", "Microsoft YaHei", "Segoe UI", system-ui, sans-serif;
  color: var(--ink);
}

* { box-sizing: border-box; }

body {
  margin: 0;
  background: var(--bg);
  font-size: 15px;
  line-height: 1.7;
  -webkit-font-smoothing: antialiased;
}

a { color: inherit; text-decoration: none; }

button {
  font-family: inherit;
  cursor: pointer;
  border: none;
}

.page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 32px 24px 80px;
}

.mono { font-family: "JetBrains Mono", "Cascadia Code", Consolas, monospace; }

::selection { background: rgba(226, 59, 46, 0.18); }
</style>
