<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { usePublicData, fmtTime } from '../composables/usePublicData.js'

const route = useRoute()
const { time, weather } = usePublicData()

const modeLabel = computed(() => (route.name === 'dopamine' ? '多巴胺模式' : '办公模式'))
const isDopamine = computed(() => route.name === 'dopamine')

const periodLabel = computed(() => (time.value?.period ? `· ${time.value.period}` : ''))
const weekdayLabel = computed(() => time.value?.weekday || '')
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <span class="logo">⚡</span>
      <span class="name">多巴胺</span>
    </div>

    <div class="cells">
      <div class="cell weather">
        <span class="dot" :class="{ dopamine: isDopamine }"></span>
        <template v-if="weather">
          <span class="w-text">{{ weather.text }}</span>
          <span class="w-temp">{{ weather.temp }}℃</span>
          <span class="muted hidden-sm">体感{{ weather.feelsLike }}℃</span>
        </template>
        <span v-else class="muted">天气加载中…</span>
      </div>

      <div class="cell time">
        <span class="dot" :class="{ dopamine: isDopamine }"></span>
        <span class="t-clock">{{ fmtTime(time) }}</span>
        <span class="muted hidden-sm">{{ weekdayLabel }} {{ periodLabel }}</span>
      </div>
    </div>

    <div class="mode-tag" :class="{ dopamine: isDopamine }">
      {{ modeLabel }}
    </div>
  </header>
</template>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 16px;
  height: 56px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}
.brand { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.brand .logo { font-size: 18px; }
.brand .name { font-size: 16px; color: var(--brand); }

.cells { display: flex; gap: 8px; flex: 1; justify-content: center; }
.cell {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--surface-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 6px 12px;
  font-size: 13px;
}
.dot {
  width: 7px; height: 7px; border-radius: var(--radius-full);
  background: var(--brand); flex-shrink: 0;
}
.dot.dopamine { background: var(--accent); }
.w-temp { font-weight: 600; }
.t-clock { font-weight: 600; font-variant-numeric: tabular-nums; }

.mode-tag {
  font-size: 12px; font-weight: 500;
  color: var(--brand-text); background: var(--brand-soft);
  border: 1px solid var(--brand);
  border-radius: var(--radius-full);
  padding: 3px 12px;
}
.mode-tag.dopamine {
  color: var(--accent-text); background: var(--accent-soft); border-color: var(--accent);
}

@media (max-width: 720px) {
  .hidden-sm { display: none; }
}
</style>
