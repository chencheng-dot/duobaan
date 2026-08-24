<script setup>
import TopBar from './components/TopBar.vue'
import SideRail from './components/SideRail.vue'
import { usePublicData } from './composables/usePublicData.js'

// 启动后即开始拉取时间与天气
usePublicData()
</script>

<template>
  <div class="shell">
    <TopBar />
    <div class="body">
      <SideRail />
      <main class="main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.body {
  flex: 1;
  display: flex;
  gap: 12px;
  padding: 12px;
  overflow: hidden;
}
.main {
  flex: 1;
  overflow: auto;
}
</style>
