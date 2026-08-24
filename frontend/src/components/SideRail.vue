<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const current = computed(() => route.name)

function go(name) {
  if (current.value !== name) router.push({ name })
}
</script>

<template>
  <aside class="rail">
    <button
      class="mode-btn"
      :class="{ active: current === 'work' }"
      @click="go('work')"
    >
      <span class="ic">💼</span>
      <span class="lbl">办公</span>
    </button>
    <button
      class="mode-btn"
      :class="{ active: current === 'dopamine' }"
      @click="go('dopamine')"
    >
      <span class="ic">🍜</span>
      <span class="lbl">多巴胺</span>
    </button>
    <div class="spacer"></div>
    <button
      class="mode-btn"
      :class="{ active: current === 'settings' }"
      @click="go('settings')"
      title="大模型配置"
    >
      <span class="ic">⚙️</span>
      <span class="lbl">设置</span>
    </button>
  </aside>
</template>

<style scoped>
.rail {
  width: 88px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}
.spacer { flex: 1; }
.mode-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 14px 8px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow);
  transition: all 0.15s;
}
.mode-btn .ic { font-size: 20px; }
.mode-btn .lbl { font-size: 13px; font-weight: 500; }
.mode-btn:hover { border-color: var(--brand); }
.mode-btn.active {
  border-color: var(--brand);
  background: var(--brand-soft);
  color: var(--brand-text);
}
/* 多巴胺按钮激活态切换为辅色 */
.mode-btn:nth-child(2).active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-text);
}
/* 设置按钮激活态 */
.mode-btn:last-child.active {
  border-color: #f59e0b;
  background: #fffbeb;
  color: #92400e;
}
</style>
