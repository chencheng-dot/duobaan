import { ref } from 'vue'
import { getTime, getWeather } from '../api'

// 全局共享的实时时间 + 天气（模块级单例 state），供 TopBar 与各页面消费。
// 轮询（60s 天气 / 1s 时钟）只保留在 TopBar.vue 一处，它挂载时调用 refresh()，
// 刷新结果写进这里的共享 ref，其它页面直接读即可；本模块不再自带任何 setInterval，
// 否则会与 TopBar 的 60s 轮询重复打同一份 /api/weather/now，且模块级定时器永不释放。
const time = ref(null)
const weather = ref(null)
let bootstrapped = false

/** 手动刷新共享数据（也可由组件在 onMounted 里调用） */
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
  // 首次使用只做一次初始拉取，把共享 state 填上；后续刷新交给 TopBar 的定时器。
  if (!bootstrapped) {
    bootstrapped = true
    refresh()
  }
  return { time, weather, refresh }
}

// 时间格式化：14:08
export function fmtTime(t) {
  if (!t) return '--:--'
  return t.now?.slice(11, 16) ?? '--:--'
}
