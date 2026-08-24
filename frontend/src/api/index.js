// 后端 REST 客户端：时间 / 天气 / 流程表 / 大模型对话 / 餐食推荐
const BASE = '/api'

async function json(url, opts = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...opts
  })
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText)
    throw new Error(`${res.status} ${txt}`)
  }
  return res.json()
}

export const getTime = () => json(`${BASE}/time/now`)
export const getWeather = () => json(`${BASE}/weather/now`)
export const getTasks = (group) => json(`${BASE}/tasks?group=${group}`)
export const createTask = (body) => json(`${BASE}/tasks`, { method: 'POST', body: JSON.stringify(body) })
export const patchTask = (id, body) => json(`${BASE}/tasks/${id}`, { method: 'PATCH', body: JSON.stringify(body) })
export const migrateTask = (id, group) => json(`${BASE}/tasks/${id}/migrate?group=${group}`, { method: 'POST', body: '{}' })
export const submitTasks = () => json(`${BASE}/tasks/submit`, { method: 'POST', body: '{}' })
export const deleteTask = (id) => fetch(`${BASE}/tasks/${id}`, { method: 'DELETE' })

export const chat = (message, mode) =>
  json(`${BASE}/llm/chat`, { method: 'POST', body: JSON.stringify({ message, mode }) })

export const recommendMeal = (ctx) =>
  json(`${BASE}/recommend/meal`, { method: 'POST', body: JSON.stringify(ctx) })

export const adoptMeal = (mealTitle) =>
  json(`${BASE}/recommend/adopt?mealTitle=${encodeURIComponent(mealTitle)}`, { method: 'POST', body: '{}' })
