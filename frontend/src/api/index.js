// 后端 REST 客户端：时间 / 天气 / 流程表 / 大模型对话 / 餐食推荐
const BASE = '/api'

// 非全局正则没有 lastIndex 状态，循环里可以安全地对不断变短的 buf 反复匹配（带 g 会越过紧邻分隔符合并多个事件）
const SSE_SEP = /\r?\n\r?\n/

async function json(url, opts = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...opts
  })
  if (res.status === 204) return null
  const ct = res.headers.get('content-type') || ''
  if (!res.ok) {
    let msg = res.statusText
    if (ct.includes('json')) {
      const body = await res.json().catch(() => null)
      msg = body?.message || msg
    }
    throw new Error(`[${res.status}] ${msg}`)
  }
  return ct.includes('json') ? res.json() : null
}

export const getTime = () => json(`${BASE}/time/now`)
export const getWeather = () => json(`${BASE}/weather/now`)
export const getTasks = (group) => json(`${BASE}/tasks?group=${group}`)
export const createTask = (body) => json(`${BASE}/tasks`, { method: 'POST', body: JSON.stringify(body) })
export const patchTask = (id, body) => json(`${BASE}/tasks/${id}`, { method: 'PATCH', body: JSON.stringify(body) })
export const migrateTask = (id, group) => json(`${BASE}/tasks/${id}/migrate?group=${group}`, { method: 'POST', body: '{}' })
export const submitTasks = () => json(`${BASE}/tasks/submit`, { method: 'POST', body: '{}' })
export const deleteTask = (id) => json(`${BASE}/tasks/${id}`, { method: 'DELETE' })
export const hardDeleteTask = (id) => json(`${BASE}/tasks/${id}/hard`, { method: 'DELETE' })

export const chat = (message, mode) =>
  json(`${BASE}/llm/chat`, { method: 'POST', body: JSON.stringify({ message, mode }) })

/**
 * 流式对话：POST + SSE，逐 token 回调 onDelta。
 * 服务端事件名：delta（增量）、done（结束）、error（异常）。
 * 返回一个 abort 函数，可中途取消。
 */
export function chatStream(message, mode, { onDelta, onDone, onError } = {}) {
  const ctrl = new AbortController()
  let finished = false

  ;(async () => {
    let reader
    try {
      const res = await fetch(`${BASE}/llm/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ message, mode }),
        signal: ctrl.signal
      })
      if (!res.ok) throw new Error(`[${res.status}] ${await res.text().catch(() => res.statusText)}`)

      reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buf = ''
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        while (true) {
          const m = SSE_SEP.exec(buf)
          if (!m) break
          const raw = buf.slice(0, m.index)
          buf = buf.slice(m.index + m[0].length)
          if (handleSse(raw, { onDelta, onError, onDone: () => { finished = true; onDone?.() } })) break
        }
        if (finished) break
      }
      buf += decoder.decode()                       // 冲刷解码器残留字节
      if (buf.trim()) handleSse(buf, { onDelta, onError, onDone: () => { finished = true; onDone?.() } })
      if (!finished) onDone?.()                     // 服务端未发 done 时补一次，且只补一次
    } catch (e) {
      if (e.name === 'AbortError') onDone?.()        // 主动停止也算结束，避免永久 loading
      else onError?.(e.message || String(e))
    } finally {
      try { await reader?.cancel() } catch (_) { /* 忽略 */ }
    }
  })()

  return () => { finished = true; ctrl.abort() }
}

/** 解析一个 SSE 事件块；返回 true 表示收到终态事件（done/error） */
function handleSse(raw, { onDelta, onDone, onError } = {}) {
  let eventName = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    const l = line.replace(/\r$/, '')
    if (l.startsWith('event:')) eventName = l.slice(6).trim()
    else if (l.startsWith('data:')) dataLines.push(l.slice(5).replace(/^ /, ''))
  }
  const data = dataLines.join('\n')
  if (eventName === 'delta') { onDelta?.(data); return false }
  if (eventName === 'done') { onDone?.(); return true }
  if (eventName === 'error') { onError?.(data); return true }
  return false
}

/** 拆单：自然语言 → 结构化任务列表 */
export const parseTasks = (message, group) =>
  json(`${BASE}/llm/parse-tasks`, { method: 'POST', body: JSON.stringify({ message, group }) })

/** 获取某 mode 下最近 N 条对话历史（时间正序：旧→新），用于刷新后恢复 */
export const getChatHistory = (mode = 'WORK', limit = 50) =>
  json(`${BASE}/llm/history?mode=${encodeURIComponent(mode)}&limit=${limit}`)

/** 批量建任务：把拆单结果一键写入流程表 */
export const bulkCreateTasks = (tasks) =>
  json(`${BASE}/tasks/bulk`, { method: 'POST', body: JSON.stringify({ tasks }) })

export const recommendMeal = (ctx) =>
  json(`${BASE}/recommend/meal`, { method: 'POST', body: JSON.stringify(ctx) })

export const adoptMeal = (mealTitle) =>
  json(`${BASE}/recommend/adopt?mealTitle=${encodeURIComponent(mealTitle)}`, { method: 'POST', body: '{}' })

// === 系统配置 ===
export const getLlmConfig = () => json(`${BASE}/config/llm`)
export const saveLlmConfig = (dto) =>
  json(`${BASE}/config/llm`, { method: 'POST', body: JSON.stringify(dto) })

// === 天气配置 ===
export const getWeatherConfig = () => json(`${BASE}/config/weather`)
export const saveWeatherConfig = (dto) =>
  json(`${BASE}/config/weather`, { method: 'POST', body: JSON.stringify(dto) })

// === 多套 API 配置（/api/config/profiles 家族） ===
export const listApiProfiles = (type) => json(`${BASE}/config/profiles?type=${type}`)
export const getApiProfile = (id) => json(`${BASE}/config/profiles/${id}`)
export const createApiProfile = (payload) =>
  json(`${BASE}/config/profiles`, { method: 'POST', body: JSON.stringify(payload) })
export const updateApiProfile = (id, payload) =>
  json(`${BASE}/config/profiles/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
export const activateApiProfile = (id) =>
  json(`${BASE}/config/profiles/${id}/activate`, { method: 'POST', body: '{}' })
export const deleteApiProfile = (id) =>
  json(`${BASE}/config/profiles/${id}`, { method: 'DELETE' })
/** 多模态统一拉取 5 类（LLM/IMAGE/AUDIO/VIDEO/WEATHER）厂商预设，前端设置页按 Tab 渲染 */
export const getAllProviders = () => json(`${BASE}/config/providers/all`)

// === 多模态生成接口：/api/media/* ===
/** 文生图：{prompt, size, n, style, quality, mode} */
export const generateImage = (body) =>
  json(`${BASE}/media/image`, { method: 'POST', body: JSON.stringify(body) })
/** 文生语音（TTS）：返回 {audioBytes:base64, audioMime, audioFilename} */
export const generateSpeech = (body) =>
  json(`${BASE}/media/speech`, { method: 'POST', body: JSON.stringify(body) })
/** 语音转写（ASR）：上传文件 + 可选 mode，返回 {text} */
export const transcribeAudio = async (file, mode = 'WORK') => {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('mode', mode)
  const res = await fetch(`${BASE}/media/transcribe`, { method: 'POST', body: fd })
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText)
    throw new Error(`${res.status} ${txt}`)
  }
  return res.json()
}
/** 文生视频：{prompt, seconds, ratio, mode} */
export const generateVideo = (body) =>
  json(`${BASE}/media/video`, { method: 'POST', body: JSON.stringify(body) })

/** Dashscope 异步任务轮询：{taskId, profileId, kind} */
export const pollTask = ({ taskId, profileId, kind }) =>
  json(`${BASE}/media/poll-task?taskId=${encodeURIComponent(taskId)}&profileId=${profileId}&kind=${kind}`)

// === 我的：任务历史 ===
export const getMineHistory = () => json(`${BASE}/tasks/mine`)

// === 知识库（RAG） ===
export const listKbs = () => json(`${BASE}/knowledge/bases`)
export const createKb = (body) => json(`${BASE}/knowledge/bases`, { method: 'POST', body: JSON.stringify(body) })
export const deleteKb = (id) => json(`${BASE}/knowledge/bases/${id}`, { method: 'DELETE' })
export const addKbDocument = (id, text) =>
  json(`${BASE}/knowledge/bases/${id}/documents`, { method: 'POST', body: JSON.stringify({ text }) })
export const uploadKbDocument = async (id, file) => {
  const fd = new FormData()
  fd.append('file', file)
  const res = await fetch(`${BASE}/knowledge/bases/${id}/upload`, { method: 'POST', body: fd })
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText)
    throw new Error(`${res.status} ${txt}`)
  }
  return res.json()
}
export const listKbChunks = (id) => json(`${BASE}/knowledge/bases/${id}/chunks`)
export const clearKbChunks = (id) => json(`${BASE}/knowledge/bases/${id}/chunks`, { method: 'DELETE' })
export const revectorizeKb = (id) => json(`${BASE}/knowledge/bases/${id}/revectorize`, { method: 'POST' })
export const getKnowledgeStatus = () => json(`${BASE}/knowledge/status`)
