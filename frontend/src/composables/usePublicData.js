import { ref } from 'vue'
import { getTime, getWeather } from '../api'

// 全局共享的实时时间 + 天气（单例），供 TopBar 与各页面消费
const time = ref(null)
const weather = ref(null)
let timer = null

async function refresh() {
  try {
    time.value = await getTime()
  } catch (e) {
    // 静默失败，保持上次值
  }
  try {
    weather.value = await getWeather()
  } catch (e) {
    /* 静默 */
  }
}

export function usePublicData() {
  if (!timer) {
    refresh()
    // 每分钟刷新时间 + 天气
    timer = setInterval(refresh, 60_000)
  }
  return { time, weather, refresh }
}

// 时间格式化：14:08
export function fmtTime(t) {
  if (!t) return '--:--'
  return t.now?.slice(11, 16) ?? '--:--'
}
