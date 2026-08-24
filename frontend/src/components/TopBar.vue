<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getWeather } from '../api'

const router = useRouter()
const route = useRoute()

// ========== 时间 ==========
const nowTime = ref(new Date())
let timer = null
onMounted(() => {
  timer = setInterval(() => (nowTime.value = new Date()), 1000)
})
onBeforeUnmount(() => timer && clearInterval(timer))

const weekdayMap = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const timeText = computed(() => {
  const d = nowTime.value
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const wd = weekdayMap[d.getDay()]
  const period = d.getHours() < 5 ? '凌晨' : d.getHours() < 11 ? '上午'
    : d.getHours() < 13 ? '中午' : d.getHours() < 18 ? '下午' : '晚上'
  return `${hh}:${mm}  ${wd} · ${period}`
})

// ========== 天气 ==========
const weather = ref(null)
async function loadWeather() {
  try {
    const w = await getWeather()
    weather.value = w
  } catch (e) {
    weather.value = null
  }
}
onMounted(loadWeather)

// 天气文字 → SVG 图标类型
const weatherIconType = computed(() => {
  if (!weather.value || !weather.value.text) return 'unknown'
  const t = weather.value.text
  if (/雷|暴.*雨|冰雹/.test(t)) return 'thunder'
  if (/雪/.test(t)) return 'snow'
  if (/雨/.test(t)) return 'rain'
  if (/雾|霾|沙尘/.test(t)) return 'fog'
  if (/阴/.test(t)) return 'cloudy'
  if (/多云/.test(t)) return 'partly'
  if (/晴/.test(t)) return 'sunny'
  return 'unknown'
})

const weatherText = computed(() => {
  if (!weather.value) return '—'
  const parts = [weather.value.text || '晴']
  if (weather.value.temp !== undefined && weather.value.temp !== null) parts.push(`${weather.value.temp}℃`)
  return parts.join(' ')
})
const feelsText = computed(() => {
  if (!weather.value) return '体感—'
  const f = weather.value.feelsLike
  if (f === undefined || f === null || f === '') return '体感—'
  return `体感${f}℃`
})

// ========== 路由跳转 ==========
const routes = [
  { key: 'dopamine', label: '多巴胺', to: '/dopamine' },
  { key: 'work', label: '办公', to: '/work' }
]
const activeKey = computed(() => route.name)
function go(key, to) {
  if (activeKey.value !== key) router.push(to)
}
function goSettings() { router.push('/settings') }
</script>

<template>
  <header class="topbar">
    <!-- LOGO -->
    <div class="logo" @click="() => router.push('/')">
      <svg class="logo-svg" viewBox="0 0 32 32" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M6 14 L16 4 L26 14 V26 H6 Z" />
        <path d="M10 26 V18 H22 V26" />
        <path d="M16 9 V26" stroke-width="1" opacity="0.5"/>
      </svg>
      <span class="logo-text">多巴胺</span>
    </div>

    <!-- 导航 -->
    <nav class="nav">
      <div
        v-for="r in routes"
        :key="r.key"
        class="nav-item"
        :class="{ active: activeKey === r.key }"
        @click="go(r.key, r.to)"
      >{{ r.label }}</div>
    </nav>

    <!-- 右侧：天气 + 时间 + 设置 -->
    <div class="right">
      <!-- 天气 -->
      <div class="weather-block" :title="weatherText + ' · ' + feelsText">
        <!-- sunny -->
        <svg v-if="weatherIconType === 'sunny'" class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="4"/>
          <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
        </svg>
        <!-- partly cloudy -->
        <svg v-else-if="weatherIconType === 'partly'" class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="9" cy="9" r="3.2"/>
          <path d="M9 3v1.2M4.22 4.22l.84.84M3 9h1.2"/>
          <path d="M15 14a4 4 0 0 1 4 4H9a4 4 0 0 1 0.2-7.99A5.5 5.5 0 0 1 15 14z"/>
        </svg>
        <!-- cloudy -->
        <svg v-else-if="weatherIconType === 'cloudy'" class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M7 18h11a4 4 0 0 0 0.2-7.99A5.5 5.5 0 0 0 6 12.6 4 4 0 0 0 7 18z"/>
        </svg>
        <!-- rain -->
        <svg v-else-if="weatherIconType === 'rain'" class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M7 14h11a4 4 0 0 0 0.2-7.99A5.5 5.5 0 0 0 6 8.6 4 4 0 0 0 7 14z"/>
          <path d="M10 17l-1 3M14 17l-1 3M18 17l-1 3"/>
        </svg>
        <!-- snow -->
        <svg v-else-if="weatherIconType === 'snow'" class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M7 14h11a4 4 0 0 0 0.2-7.99A5.5 5.5 0 0 0 6 8.6 4 4 0 0 0 7 14z"/>
          <path d="M12 17v5M10 18l4 3M14 18l-4 3"/>
        </svg>
        <!-- thunder -->
        <svg v-else-if="weatherIconType === 'thunder'" class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M7 13h11a4 4 0 0 0 0.2-7.99A5.5 5.5 0 0 0 6 7.6 4 4 0 0 0 7 13z"/>
          <path d="M12 15l-2 4h3l-1 4 4-6h-3l1-3"/>
        </svg>
        <!-- fog -->
        <svg v-else-if="weatherIconType === 'fog'" class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M5 10h14M5 14h14M5 18h14"/>
          <circle cx="18" cy="8" r="2" stroke-width="1.3"/>
        </svg>
        <!-- unknown -->
        <svg v-else class="wicon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="9"/>
          <path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 2-2.5 2.2-2.5 4"/>
          <circle cx="12" cy="18" r="0.8" fill="currentColor"/>
        </svg>

        <div class="wtext">
          <div class="wline1">{{ weatherText }}</div>
          <div class="wline2">{{ feelsText }}</div>
        </div>
      </div>

      <div class="divider"></div>

      <div class="time-block">
        <span class="time">{{ timeText }}</span>
      </div>

      <button class="settings-btn" title="设置" @click="goSettings">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="3"/>
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
        </svg>
      </button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: var(--topbar-h);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #FFFFFF;
  border-bottom: 1px solid var(--border);
  gap: 16px;
  flex-shrink: 0;
}
.logo {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--text);
}
.logo-svg { color: var(--text); }
.logo-text {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.nav {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 16px;
}
.nav-item {
  padding: 6px 14px;
  font-size: 13px;
  color: var(--text-muted);
  border: 1px solid transparent;
  border-radius: var(--radius);
  cursor: pointer;
  transition: all 0.12s;
}
.nav-item:hover { color: var(--text); border-color: var(--border); }
.nav-item.active {
  color: var(--text);
  border-color: var(--text);
  font-weight: 500;
}
.right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}
.weather-block {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: #FFFFFF;
}
.wicon { color: var(--text); }
.wtext { line-height: 1.3; }
.wline1 { font-size: 12px; font-weight: 500; color: var(--text); }
.wline2 { font-size: 11px; color: var(--text-muted); }
.divider { width: 1px; height: 28px; background: var(--border); }
.time-block { font-size: 13px; color: var(--text); }
.time { letter-spacing: 0.4px; }
.settings-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px; height: 32px;
  border-radius: var(--radius);
  background: #FFFFFF;
  border: 1px solid var(--border);
  color: var(--text);
  cursor: pointer;
  transition: all 0.12s;
}
.settings-btn:hover { border-color: var(--text); }
</style>
