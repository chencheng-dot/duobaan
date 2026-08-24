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

/**
 * 流式对话：POST + SSE，逐 token 回调 onDelta。
 * 服务端事件名：delta（增量）、done（结束）、error（异常）。
 * 返回一个 abort 函数，可中途取消。
 */
export function chatStream(message, mode, { onDelta, onDone, onError } = {}) {
  const ctrl = new AbortController()
  fetch(`${BASE}/llm/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message, mode }),
    signal: ctrl.signal
  })
    .then(async (res) => {
      if (!res.ok) {
        const txt = await res.text().catch(() => res.statusText)
        throw new Error(`${res.status} ${txt}`)
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buf = ''
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        // 按 SSE 协议切分：空行分隔事件
        let idx
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const raw = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          handleSse(raw, { onDelta, onDone, onError })
        }
      }
      // 处理尾部残留
      if (buf.trim()) handleSse(buf, { onDelta, onDone, onError })
      if (onDone) onDone()
    })
    .catch((e) => {
      if (e.name === 'AbortError') return
      if (onError) onError(e.message || String(e))
    })
  return () => ctrl.abort()
}

function handleSse(raw, { onDelta, onDone, onError } = {}) {
  // 解析 SSE 事件块：event: name\ndata: payload
  let eventName = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  const data = dataLines.join('\n')
  if (eventName === 'delta' && onDelta) onDelta(data)
  else if (eventName === 'done' && onDone) onDone()
  else if (eventName === 'error' && onError) onError(data)
}

/** 拆单：自然语言 → 结构化任务列表 */
export const parseTasks = (message, group) =>
  json(`${BASE}/llm/parse-tasks`, { method: 'POST', body: JSON.stringify({ message, group }) })

/** 批量建任务：把拆单结果一键写入流程表 */
export const bulkCreateTasks = (tasks) =>
  json(`${BASE}/tasks/bulk`, { method: 'POST', body: JSON.stringify({ tasks }) })

export const recommendMeal = (ctx) =>
  json(`${BASE}/recommend/meal`, { method: 'POST', body: JSON.stringify(ctx) })

export const adoptMeal = (mealTitle) =>
  json(`${BASE}/recommend/adopt?mealTitle=${encodeURIComponent(mealTitle)}`, { method: 'POST', body: '{}' })
