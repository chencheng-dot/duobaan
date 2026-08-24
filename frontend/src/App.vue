<script setup>
import { RouterView } from 'vue-router'
import TopBar from './components/TopBar.vue'
import SideRail from './components/SideRail.vue'
</script>

<template>
  <div class="shell">
    <header class="topbar-wrap"><TopBar /></header>
    <div class="body">
      <SideRail />
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
}
.topbar-wrap {
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
  padding: 16px;
  background: #FFFFFF;
}
.content {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}
</style>
