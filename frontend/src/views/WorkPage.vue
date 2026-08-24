<script setup>
import ChatPanel from '../components/ChatPanel.vue'
import FlowTable from '../components/FlowTable.vue'
</script>

<template>
  <div class="work">
    <ChatPanel
      mode="WORK"
      title="办公对话"
      placeholder="规划今天/明天的安排，或让大模型帮你起草…"
    />
    <FlowTable />
  </div>
</template>

<style scoped>
.work {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 12px;
  height: 100%;
}

@media (max-width: 960px) {
  .work {
    grid-template-columns: 1fr;
    grid-template-rows: 1fr auto;
  }
}
</style>
