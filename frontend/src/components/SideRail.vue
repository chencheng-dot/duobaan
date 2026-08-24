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
      title="办公"
    >
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        <rect x="3" y="4" width="18" height="14" rx="2" fill="none" stroke="currentColor" stroke-width="1.5"/>
        <line x1="3" y1="9" x2="21" y2="9" stroke="currentColor" stroke-width="1.5"/>
        <path d="M7 19v-2h10v2" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
      </svg>
      <span class="lbl">办公</span>
    </button>

    <button
      class="mode-btn"
      :class="{ active: current === 'dopamine' }"
      @click="go('dopamine')"
      title="多巴胺"
    >
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        <path d="M4 10c0-4 3.5-6 8-6s8 2 8 6-3.5 6-8 6"
          fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        <line x1="8" y1="20" x2="16" y2="20" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        <path d="M10 16l-1 3M14 16l1 3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
      </svg>
      <span class="lbl">多巴胺</span>
    </button>

    <div class="spacer"></div>

    <button
      class="mode-btn"
      :class="{ active: current === 'settings' }"
      @click="go('settings')"
      title="设置"
    >
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        <circle cx="12" cy="12" r="3" fill="none" stroke="currentColor" stroke-width="1.5"/>
        <g fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
          <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1A1.7 1.7 0 0 0 15 4.6a1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>
        </g>
      </svg>
      <span class="lbl">设置</span>
    </button>
  </aside>
</template>

<style scoped>
.rail {
  width: 84px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex-shrink: 0;
}
.spacer { flex: 1; }
.mode-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 14px 6px;
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  color: var(--text-muted);
  transition: all 0.12s ease;
}
.mode-btn .lbl { font-size: 12px; font-weight: 500; }
.mode-btn:hover {
  color: var(--text);
  border-color: var(--text);
}
.mode-btn.active {
  color: var(--text);
  background: #FFFFFF;
  border-color: var(--text);
  box-shadow: inset 0 0 0 1px var(--text);
}
</style>
