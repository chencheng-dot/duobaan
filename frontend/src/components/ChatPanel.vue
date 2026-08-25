<script setup>
import { ref, nextTick, onMounted } from 'vue'
import {
  chatStream,
  parseTasks,
  bulkCreateTasks,
  getChatHistory,
  generateImage,
  generateSpeech,
  transcribeAudio,
  generateVideo,
  getTasks,
  migrateTask,
  patchTask,
  createTask
} from '../api'

const props = defineProps({
  mode: { type: String, default: 'WORK' }, // WORK | DOPAMINE
  title: { type: String, default: '大模型对话' },
  placeholder: { type: String, default: '输入你的需求…' }
})

const emit = defineEmits(['tasks-created'])

// ================= 模态选择器 =================
/**
 * AUTO:    智能模式（输入文字 → 先用文本模型判断/答复；若用户描述是"生图/视频/TTS/ASR"，仍可手动切 Tab）
 * TEXT:    纯文本对话（chatStream）
 * IMAGE:   文生图
 * ASR:     语音转写（上传文件）
 * TTS:     文生语音（把输入框文字转成音频播放）
 * VIDEO:   文生视频
 */
const MODES = [
  { key: 'AUTO',  label: '智能', symbol: '◎', hint: '默认用文本模型对话，有特殊需求请切到对应 Tab' },
  { key: 'TEXT',  label: '文本', symbol: 'A', hint: '纯文本大模型流式问答' },
  { key: 'IMAGE', label: '图片', symbol: '🖼', hint: '文生图（尺寸默认 1024×1024）' },
  { key: 'ASR',   label: '转写', symbol: '🎙', hint: '上传音频 → 自动转文字' },
  { key: 'TTS',   label: '朗读', symbol: '🔊', hint: '把输入框文字朗读为语音' },
  { key: 'VIDEO', label: '视频', symbol: '🎬', hint: '文生视频（默认 5s / 16:9）' }
]
const mediaMode = ref('AUTO')
const asrFile = ref(null)
const asrFileName = ref('')

// ================= 消息 / 对话状态 =================
const messages = ref([])
const input = ref('')
const loading = ref(false)
const listEl = ref(null)
const fileInputRef = ref(null)
let abortFn = null

// 拆单结果（待确认/已确认）
const parsedTasks = ref([])
const parsing = ref(false)
const saving = ref(false)

async function scrollToBottom() {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

/** 解析 chat_message 的富内容：约定前缀 %%RICH_MEDIA%% + JSON */
function parseContent(raw) {
  if (typeof raw !== 'string') return { text: raw ?? '', rich: null }
  if (!raw.startsWith('%%RICH_MEDIA%%')) return { text: raw, rich: null }
  try {
    const json = raw.slice('%%RICH_MEDIA%%'.length)
    const obj = JSON.parse(json)
    return { text: obj.text || '', rich: obj }
  } catch (_) {
    return { text: raw, rich: null }
  }
}

/** 组件挂载时拉最近 50 条历史，恢复上一次对话 — 刷新页面不再空 */
onMounted(async () => {
  try {
    const list = await getChatHistory(props.mode, 50)
    if (Array.isArray(list) && list.length) {
      messages.value = list.map((m) => {
        const { text, rich } = parseContent(m.content ?? '')
        return {
          role: m.role || 'assistant',
          content: text,
          rich,
          streaming: false
        }
      })
      await scrollToBottom()
    }
  } catch (e) {
    console.warn('[ChatPanel] 加载对话历史失败：', e.message)
  }
})

// ================= 统一发送入口 =================
function sendText() {
  const text = input.value.trim()
  if (!text || loading.value) return
  switch (mediaMode.value) {
    case 'AUTO':
    case 'TEXT':
      return sendTextLLM(text)
    case 'IMAGE':
      return doImage(text)
    case 'TTS':
      return doTTS(text)
    case 'VIDEO':
      return doVideo(text)
    default:
      return sendTextLLM(text)
  }
}

/** 普通文本模型流式对话（也是「智能」Tab 的默认行为） */
async function sendTextLLM(text) {
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true
  const bubble = { role: 'assistant', content: '', streaming: true }
  messages.value.push(bubble)
  await scrollToBottom()

  abortFn = chatStream(text, props.mode, {
    onDelta: (delta) => {
      bubble.content += delta
      scrollToBottom()
    },
    onDone: () => {
      bubble.streaming = false
      loading.value = false
      abortFn = null
      // 流式完成后自动解析 ```json``` 任务指令 → 调 migrate/patch/create → 触发 FlowTable 刷新
      executeTaskInstructions(bubble)
    },
    onError: (msg) => {
      bubble.content += `\n[流式错误: ${msg}]`
      bubble.streaming = false
      bubble.degraded = true
      loading.value = false
      abortFn = null
      executeTaskInstructions(bubble) // 即便出错，已收到的 JSON 仍要执行
    }
  })
}

function stop() {
  if (abortFn) abortFn()
  loading.value = false
  const last = messages.value[messages.value.length - 1]
  if (last && last.streaming) {
    last.streaming = false
    last.content += '\n[已中止]'
  }
}

// ================= 任务指令自动执行器（解决：用户说"把任务转到明天"，助手只说不做）=================
/**
 * 大模型返回的约定格式（包裹在 ```json ``` 代码块中，可多个）：
 *   [{ "title": "近期数据异常分析", "date": "今天"|"明天"|"今日"|"明日"|"TODAY"|"TOMORROW", "status": "TODO"|"DOING"|"DONE"|"SUBMITTED" }]
 *
 * 字段含义：
 *  - title 必填：按标题精确/包含匹配流程表里的任务（先搜今日再搜明日，取首条命中）
 *  - date  可选：改分组 → 调 /api/tasks/{id}/migrate
 *  - status 可选：改状态 → 调 /api/tasks/{id}/patch
 *  - 若 title 未找到 + 给了 date → 新建一个任务到该分组（用户说"明天去买奶茶"，没这条也能自动建）
 *
 * 执行成功后会：
 *  1. 在助手气泡下方追加一个 "已自动执行：…" 的摘要块
 *  2. emit('tasks-created') 触发 WorkPage 里的 flowRef.load() 重新拉取今/明日列表
 */

function parseDateToGroup(s) {
  if (!s) return null
  const k = String(s).trim()
  if (/^(今天|今日|today|TODAY)$/.test(k)) return 'TODAY'
  if (/^(明天|明日|tomorrow|TOMORROW)$/.test(k)) return 'TOMORROW'
  return null
}
/** 从一段文本（通常是某代码块「正上方 1-2 行小标题」）推断默认分组 */
function inferGroupFromContext(contextText) {
  if (!contextText) return null
  if (/(明天|明日|tomorrow)/i.test(contextText)) return 'TOMORROW'
  if (/(今天|今日|today)/i.test(contextText)) return 'TODAY'
  return null
}
/** 从「助手消息全文 + 紧邻用户上一条」推断全局分组（用户说"把任务转到明天"）*/
function inferGlobalGroup(text, userPrevText) {
  const combined = [text, userPrevText || ''].join('\n')
  if (/(转到明天|移到明天|移至明日|移至明天|放到明天|明天做|明天去|明天的任务|明日任务|tomorrow)/i.test(combined)) return 'TOMORROW'
  if (/(转到今天|移到今天|移至今日|今日任务|today)/i.test(combined)) return 'TODAY'
  return null
}
function normStatus(s) {
  if (!s) return null
  const k = String(s).trim()
  const m = {
    TODO: 'TODO', '待办': 'TODO',
    DOING: 'DOING', '进行中': 'DOING', '做中': 'DOING',
    DONE: 'DONE', '完成': 'DONE', '已完成': 'DONE',
    SUBMITTED: 'SUBMITTED', '上交': 'SUBMITTED', '已上交': 'SUBMITTED'
  }
  return m[k] || m[k.toUpperCase()] || null
}

/**
 * 指代消解：用户写「把这个任务转到明天」，把"这个"翻译成真实任务标题
 *  优先级：用户消息里直接出现任务名 → 对话历史用户消息里命中任务 → 今日任务唯一时直接返回
 */
function resolveAnaphora(userLastText, allTasks) {
  if (!userLastText) return null
  const hasAnaphora = /(这个|该|当前|此|这条)/.test(userLastText)
  if (!hasAnaphora) return null
  for (const t of allTasks || []) if (t.title && userLastText.includes(t.title)) return t.title
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const mb = messages.value[i]
    if (!mb || mb.role !== 'user' || mb.content === userLastText) continue
    for (const t of allTasks || []) if (t.title && mb.content.includes(t.title)) return t.title
  }
  const todays = (allTasks || []).filter((t) => t.group === 'TODAY')
  if (todays.length === 1) return todays[0].title
  return null
}

/**
 * 主解析入口（兼容 LLM 三种常见格式）：
 *   1. ```json [{title,date,status}]``` 对象数组
 *   2. 「明日任务：」+ ```json ["近期数据异常分析"]``` 字符串数组 + 上下文分组
 *   3. 纯文本：「今日任务：X」「把 X 移到明天」「X 任务改为已完成」
 *   4. 指代兜底：用户说「这个任务」→ resolveAnaphora 猜标题 + inferGlobalGroup 猜分组
 * 返回 { title, group, status, source }[]
 */
function extractTaskInstructions(bubbleContent, userLastText, allTasks) {
  const out = []
  const text = bubbleContent || ''
  if (!text && !userLastText) return out
  const globalGroup = inferGlobalGroup(text, userLastText)

  // ===== 第一层 & 第二层：代码块 =====
  const codeRe = /```(?:json)?[ \t]*\r?\n?([\s\S]*?)(?:```|$)/gi
  const blocks = []
  let m
  let lastEnd = 0
  while ((m = codeRe.exec(text)) !== null) {
    blocks.push({ pre: text.slice(lastEnd, m.index), raw: (m[1] || '').trim() })
    lastEnd = m.index + m[0].length
  }
  for (const b of blocks) {
    const nearby = b.pre.split(/\r?\n/).filter((s) => s.trim()).slice(-2).join('\n')
    const blockGroup = inferGroupFromContext(nearby) || globalGroup
    if (!b.raw) continue
    // 容错：LLM 常把 JSON 写成字符串套了两层引号、或 \[ \]、或 \"...\"
    let cleaned = b.raw.replace(/^['"\s]+|['"\s]+$/g, '').replace(/^\\/, '').replace(/\\$/, '')
    let parsed = null
    try { parsed = JSON.parse(cleaned) }
    catch (_) { try { parsed = JSON.parse(cleaned.replace(/\\"/g, '"')) } catch (__) {} }
    if (parsed == null) continue
    const items = Array.isArray(parsed) ? parsed : [parsed]
    for (const item of items) {
      if (item == null) continue
      if (typeof item === 'string') {
        const t = item.trim()
        if (!t) continue
        out.push({ title: t, group: blockGroup, status: null, source: `json-string-array${blockGroup ? '(' + blockGroup + ')' : ''}` })
      } else if (typeof item === 'object') {
        const title = (item.title || item.task || item.name || '').toString().trim()
        if (!title) continue
        const g = parseDateToGroup(item.date || item.group) || blockGroup
        out.push({ title, group: g, status: normStatus(item.status), source: 'json-object' })
      }
    }
  }

  // ===== 第三层：纯文本 =====
  if (!out.length) {
    const headingRe = /(今天|今日|明天|明日)[\s:：]*任务[\s:：]*([^\n\r]+)/gi
    let h
    while ((h = headingRe.exec(text)) !== null) {
      const grp = /(明天|明日)/.test(h[1]) ? 'TOMORROW' : 'TODAY'
      if (/^\s*(无|暂无|none)\s*$/i.test(h[2])) continue
      const titles = h[2]
        .split(/[,，;；、]|\r?\n|\s*[-*•]\s+/)
        .map((s) => s.trim().replace(/^[-*•]\s*/, ''))
        .filter(Boolean)
      for (const t of titles) out.push({ title: t, group: grp, status: null, source: `plaintext-heading(${grp})` })
    }
  }
  if (!out.length) {
    const moveRe = /(?:已将|把|将)?(?:任务)?[「"'“”‘’]?([^「」"'“”‘’\n\r]{2,60}?)[」"'“”‘’]?(?:任务)?(?:已)?(?:移至|转到|放到|调整到|归属到|归类为)(今天|今日|明天|明日)/gi
    let h2
    while ((h2 = moveRe.exec(text)) !== null) {
      const title = h2[1].replace(/^(把|将|让|给|我|帮我)/, '').trim()
      const grp = /(明天|明日)/.test(h2[2]) ? 'TOMORROW' : 'TODAY'
      if (title) out.push({ title, group: grp, status: null, source: `plaintext-move(${grp})` })
    }
    const statusRe = /(?:把|将)?(?:任务)?[「"'“”‘’]?([^「」"'“”‘’\n\r]{2,60}?)[」"'“”‘’]?(?:任务)?(?:状态|改成|改为|标记为|设为)(TODO|待办|DOING|进行中|做中|DONE|完成|已完成|SUBMITTED|上交|已上交)/gi
    let h3
    while ((h3 = statusRe.exec(text)) !== null) {
      const title = h3[1].replace(/^(把|将|让|给|我|帮我)/, '').trim()
      const s = normStatus(h3[2])
      if (title && s) out.push({ title, group: null, status: s, source: `plaintext-status(${s})` })
    }
  }

  // ===== 第四层：指代兜底 =====
  if (!out.length && globalGroup) {
    const title = resolveAnaphora(userLastText || '', allTasks || [])
    if (title) out.push({ title, group: globalGroup, status: null, source: `anaphora(${globalGroup})` })
  }

  // 去重
  const seen = new Set()
  return out.filter((x) => {
    const k = `${x.title}__${x.group || ''}__${x.status || ''}`
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
}

async function executeTaskInstructions(bubble) {
  const text = bubble.content || ''
  if (props.mode !== 'WORK') return

  bubble.executing = true
  bubble.execResults = []
  await scrollToBottom()

  let changed = false
  try {
    const [todayList, tomorrowList] = await Promise.all([getTasks('TODAY'), getTasks('TOMORROW')])
    const all = [...todayList, ...tomorrowList]

    let userLastText = ''
    const idx = messages.value.indexOf(bubble)
    if (idx > 0) {
      for (let i = idx - 1; i >= 0; i--) {
        if (messages.value[i] && messages.value[i].role === 'user') {
          userLastText = messages.value[i].content || ''
          break
        }
      }
    }

    const instructions = extractTaskInstructions(text, userLastText, all)
    if (!instructions.length) {
      bubble.execResults.push('ℹ️ 未识别到可执行的任务结构（如仍未生效，请手动点「→明日」按钮，或把你的需求描述得更具体："把 XX 任务移到明天"）。')
      bubble.executing = false
      scrollToBottom()
      return
    }
    bubble.execResults.push(`🔍 识别到 ${instructions.length} 条可执行指令（来源：${instructions.map((x) => x.source).join(' + ')}）`)
    await scrollToBottom()

    function findTask(title) {
      if (!title) return null
      const t = String(title).trim()
      if (!t) return null
      return (
        all.find((x) => x.title === t) ||
        all.find((x) => x.title && x.title.includes(t)) ||
        all.find((x) => t.includes(x.title))
      )
    }

    for (const ins of instructions) {
      const title = (ins.title || '').toString().trim()
      const group = ins.group
      const status = ins.status
      if (!title) { bubble.execResults.push('⚠️ 指令缺少 title，已跳过'); continue }
      try {
        const existing = findTask(title)
        if (!existing) {
          if (group) {
            const created = await createTask({ title, category: 'LLM', group })
            changed = true
            all.push(created)
            bubble.execResults.push(`➕ 流程表没找到「${title}」，已新建到「${group === 'TODAY' ? '今日' : '明日'}」id=${created.id}`)
            if (status && status !== created.status) {
              const up = await patchTask(created.id, { status })
              bubble.execResults.push(`🔧 新建任务「${title}」状态改为 ${up.status}`)
            }
          } else {
            bubble.execResults.push(`❌ 没找到「${title}」任务，且未明确分组（今天/明天），无法迁移/新建`)
          }
          continue
        }
        if (group && existing.group !== group) {
          await migrateTask(existing.id, group)
          changed = true
          bubble.execResults.push(`✅ 「${title}」已迁移至「${group === 'TODAY' ? '今日' : '明日'}」（原分组=${existing.group}）`)
          existing.group = group
        } else if (group) {
          bubble.execResults.push(`ℹ️ 「${title}」已在「${group === 'TODAY' ? '今日' : '明日'}」，无需迁移`)
        }
        if (status && existing.status !== status) {
          await patchTask(existing.id, { status })
          changed = true
          bubble.execResults.push(`🔧 「${title}」状态改为 ${status}`)
        } else if (status) {
          bubble.execResults.push(`ℹ️ 「${title}」状态已是 ${status}`)
        }
        if (!group && !status) {
          bubble.execResults.push(`ℹ️ 「${title}」未给出分组或新状态，不做变更`)
        }
      } catch (e) {
        bubble.execResults.push(`💥 处理「${title}」失败：${e.message || String(e)}`)
      }
    }
  } catch (e) {
    bubble.execResults.push(`💥 拉取任务快照失败：${e.message || String(e)}`)
  } finally {
    bubble.executing = false
    scrollToBottom()
  }

  if (changed) emit('tasks-created')
}

// ================= IMAGE 文生图 =================
async function doImage(prompt) {
  loading.value = true
  messages.value.push({ role: 'user', content: '【文生图】' + prompt })
  input.value = ''
  const bubble = { role: 'assistant', content: '🎨 生图中，请稍候…', loading: true }
  messages.value.push(bubble)
  await scrollToBottom()
  try {
    const res = await generateImage({ prompt, size: '1024x1024', n: 1, mode: props.mode })
    bubble.loading = false
    if (res.status === 'succeeded') {
      bubble.content = '🎨 生成完成（点击图片可新标签页打开）'
      bubble.rich = { kind: 'IMAGE', payload: res, text: bubble.content }
    } else if (res.status === 'pending') {
      bubble.content = '⏳ 图片生成中（异步任务已提交，约 10~30 秒）…'
      bubble.rich = { kind: 'IMAGE', payload: res, text: bubble.content }
    } else {
      bubble.content = res.error || '生图失败'
      bubble.degraded = true
    }
  } catch (e) {
    bubble.loading = false
    bubble.content = '生图异常：' + e.message
    bubble.degraded = true
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

// ================= TTS 文生语音 =================
async function doTTS(text) {
  loading.value = true
  messages.value.push({ role: 'user', content: '【朗读】' + text })
  input.value = ''
  const bubble = { role: 'assistant', content: '🔊 合成语音中…', loading: true }
  messages.value.push(bubble)
  await scrollToBottom()
  try {
    const res = await generateSpeech({ input: text, voice: 'alloy', format: 'mp3', speed: 1.0, mode: props.mode })
    bubble.loading = false
    if (res.status === 'succeeded') {
      bubble.content = '🔊 朗读完成（点击音频播放）'
      bubble.rich = { kind: 'AUDIO_TTS', payload: res, text: bubble.content }
    } else {
      bubble.content = res.error || '合成失败'
      bubble.degraded = true
    }
  } catch (e) {
    bubble.loading = false
    bubble.content = 'TTS 异常：' + e.message
    bubble.degraded = true
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

// ================= ASR 语音转写 =================
function openFilePicker() {
  if (fileInputRef.value) fileInputRef.value.click()
}
function onFilePicked(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  asrFile.value = f
  asrFileName.value = f.name
}
async function runASR() {
  if (!asrFile.value || loading.value) return
  const theFile = asrFile.value
  const theName = asrFileName.value
  loading.value = true
  messages.value.push({ role: 'user', content: '【语音转写】' + theName })
  const bubble = { role: 'assistant', content: '🎙 转写中…', loading: true }
  messages.value.push(bubble)
  asrFile.value = null
  asrFileName.value = ''
  if (fileInputRef.value) fileInputRef.value.value = ''
  await scrollToBottom()
  try {
    const res = await transcribeAudio(theFile, props.mode)
    bubble.loading = false
    if (res.status === 'succeeded' && res.text) {
      bubble.content = '🎙 转写结果：\n' + res.text
    } else {
      bubble.content = res.error || '转写失败（未返回文本）'
      bubble.degraded = true
    }
  } catch (e) {
    bubble.loading = false
    bubble.content = '转写异常：' + e.message
    bubble.degraded = true
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

// ================= VIDEO 文生视频 =================
async function doVideo(prompt) {
  loading.value = true
  messages.value.push({ role: 'user', content: '【文生视频】' + prompt })
  input.value = ''
  const bubble = { role: 'assistant', content: '🎬 视频生成中（较慢约 1~3 分钟）…', loading: true }
  messages.value.push(bubble)
  await scrollToBottom()
  try {
    const res = await generateVideo({ prompt, seconds: 5, ratio: '16:9', mode: props.mode })
    bubble.loading = false
    if (res.status === 'succeeded') {
      bubble.content = '🎬 视频生成完成'
      bubble.rich = { kind: 'VIDEO', payload: res, text: bubble.content }
    } else if (res.status === 'pending') {
      bubble.content = '⏳ 视频仍在排队处理中，请稍后刷新：' + (res.error || '')
      bubble.rich = { kind: 'VIDEO', payload: res, text: bubble.content }
    } else {
      bubble.content = res.error || '生成失败'
      bubble.degraded = true
    }
  } catch (e) {
    bubble.loading = false
    bubble.content = '视频生成异常：' + e.message
    bubble.degraded = true
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

// ================= 富媒体渲染辅助 =================
function imageSrc(item) {
  if (item && item.url) return item.url
  if (item && item.b64Data) return `data:image/png;base64,${item.b64Data}`
  return ''
}
function audioSrc(payload) {
  if (!payload) return ''
  if (payload.audioBytes) {
    const bytes = Uint8Array.from(atob(payload.audioBytes), (c) => c.charCodeAt(0))
    const blob = new Blob([bytes], { type: payload.audioMime || 'audio/mpeg' })
    return URL.createObjectURL(blob)
  }
  return ''
}
function videoSrc(item) { return item && item.url ? item.url : '' }

// ================= 拆单（保留原 WORK 模式功能） =================
async function parseCurrent() {
  const text = input.value.trim()
  if (!text || parsing.value) return
  parsing.value = true
  try {
    const list = await parseTasks(text, 'TODAY')
    parsedTasks.value = list
  } catch (e) {
    parsedTasks.value = []
    messages.value.push({
      role: 'assistant',
      content: '拆单失败：' + e.message,
      degraded: true
    })
    await scrollToBottom()
  } finally {
    parsing.value = false
  }
}
function removeParsed(i) { parsedTasks.value.splice(i, 1) }
async function saveParsed() {
  if (!parsedTasks.value.length || saving.value) return
  saving.value = true
  try {
    const tasks = parsedTasks.value.map((t) => ({
      title: t.title,
      group: t.group,
      category: 'WORK'
    }))
    const created = await bulkCreateTasks(tasks)
    parsedTasks.value = []
    emit('tasks-created', created)
    messages.value.push({
      role: 'assistant',
      content: `已把 ${created.length} 条任务写入流程表。`
    })
    await scrollToBottom()
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      content: '批量写入失败：' + e.message,
      degraded: true
    })
    await scrollToBottom()
  } finally {
    saving.value = false
  }
}

function onKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    if (mediaMode.value === 'ASR') {
      runASR()
    } else {
      sendText()
    }
  }
}
function onMainBtnClick() {
  if (mediaMode.value === 'ASR') {
    if (asrFile.value) runASR()
  } else {
    sendText()
  }
}
function sendDisabled() {
  if (loading.value) return true
  if (mediaMode.value === 'ASR') return !asrFile.value
  return !input.value.trim()
}
</script>

<template>
  <section class="chat card">
    <div class="chat-head">
      <span class="ttl">{{ title }}</span>
      <span class="hint muted">回车发送 · Shift+回车换行</span>
    </div>

    <!-- 模态选择器（顶栏新增） -->
    <div class="modes">
      <div
        v-for="m in MODES"
        :key="m.key"
        class="mode"
        :class="{ active: mediaMode === m.key }"
        :title="m.hint"
        @click="mediaMode = m.key"
      >
        <span class="mode-sym">{{ m.symbol }}</span>
        <span class="mode-txt">{{ m.label }}</span>
      </div>
    </div>

    <div class="chat-list" ref="listEl">
      <div v-if="!messages.length" class="empty muted">
        在下方输入，让大模型帮你规划{{ mode === 'WORK' ? '今天/明天的安排' : '这一餐吃什么' }}。<br/>
        切到「🖼图片 / 🎬视频 / 🔊朗读 / 🎙转写」Tab 可调用对应模型。
      </div>
      <div
        v-for="(m, i) in messages"
        :key="i"
        class="bubble"
        :class="m.role"
      >
        <div class="bubble-text">
          {{ m.content }}<span v-if="m.streaming" class="cursor">▌</span>
          <span v-if="m.loading" class="spinner"></span>
          <span v-if="m.degraded" class="badge">降级</span>
        </div>

        <!-- 任务指令自动执行结果摘要 -->
        <div v-if="m.executing || (m.execResults && m.execResults.length)" class="exec">
          <div class="exec-head">
            <span>🤖 自动执行任务指令</span>
            <span v-if="m.executing" class="spinner"></span>
          </div>
          <div v-if="m.execResults && m.execResults.length" class="exec-list">
            <div v-for="(r, j) in m.execResults" :key="j" class="exec-line">{{ r }}</div>
          </div>
          <div v-else class="muted" style="font-size:12px">执行中…</div>
        </div>

        <!-- 富内容：图片 -->
        <div v-if="m.rich && m.rich.kind === 'IMAGE'" class="rich-block">
          <div v-if="m.rich.payload && m.rich.payload.items && m.rich.payload.items.length" class="rich-grid">
            <a
              v-for="(it, j) in m.rich.payload.items"
              :key="j"
              :href="imageSrc(it)"
              target="_blank"
              rel="noreferrer"
              class="rich-img"
            >
              <img :src="imageSrc(it)" :alt="it.revisedPrompt || '生成的图片'" />
            </a>
          </div>
          <div v-else-if="m.rich.payload && m.rich.payload.status === 'pending'" class="muted" style="font-size:12px">
            ⏳ 图片仍在异步处理中（task_id 已提交），稍后可在相同聊天中重试获取。
          </div>
        </div>

        <!-- 富内容：TTS 音频 -->
        <div v-if="m.rich && m.rich.kind === 'AUDIO_TTS' && m.rich.payload" class="rich-media">
          <audio v-if="audioSrc(m.rich.payload)" :src="audioSrc(m.rich.payload)" controls preload="metadata"></audio>
          <div v-else class="muted" style="font-size: 12px;">（当前浏览器无法播放该音频）</div>
        </div>

        <!-- 富内容：视频 -->
        <div v-if="m.rich && m.rich.kind === 'VIDEO' && m.rich.payload && m.rich.payload.items" class="rich-media">
          <video
            v-for="(it, j) in m.rich.payload.items"
            :key="j"
            :src="videoSrc(it)"
            controls
            preload="metadata"
            class="rich-video"
          ></video>
          <div v-if="!m.rich.payload.items.length && m.rich.payload.status === 'pending'" class="muted" style="font-size:12px">
            ⏳ 视频仍在处理中，稍后可在相同聊天中重试获取。
          </div>
        </div>
      </div>
    </div>

    <!-- 拆单结果卡片 -->
    <div v-if="parsedTasks.length" class="parsed">
      <div class="parsed-head">
        <span>拆出 {{ parsedTasks.length }} 条任务</span>
        <button class="save" @click="saveParsed" :disabled="saving">
          {{ saving ? '写入中…' : '全部写入流程表' }}
        </button>
      </div>
      <div class="parsed-list">
        <div v-for="(t, i) in parsedTasks" :key="i" class="parsed-item">
          <span class="p-group" :class="t.group">{{ t.group === 'TODAY' ? '今' : '明' }}</span>
          <span class="p-title">{{ t.title }}</span>
          <button class="p-rm" @click="removeParsed(i)" title="移除">✕</button>
        </div>
      </div>
    </div>

    <!-- ASR 文件选择辅助（隐藏） -->
    <input type="file" ref="fileInputRef" accept="audio/*,.mp3,.wav,.m4a,.webm,.ogg,.flac" style="display:none" @change="onFilePicked" />

    <div class="chat-input">
      <!-- ASR Tab：文件选择按钮 + 已选文件名 -->
      <template v-if="mediaMode === 'ASR'">
        <div class="asr-picker" @click="openFilePicker">
          <span v-if="asrFileName" class="asr-file">{{ asrFileName }}</span>
          <span v-else class="asr-ph muted">点击选择音频文件（支持 mp3/wav/m4a/flac）</span>
        </div>
      </template>
      <textarea
        v-else
        v-model="input"
        :placeholder="
          mediaMode === 'IMAGE' ? '请输入要生成的图片描述，如：一只戴着太阳镜的柯基在月球上奔跑，写实风格' :
          mediaMode === 'TTS'   ? '请输入要朗读的文字…' :
          mediaMode === 'VIDEO' ? '请输入要生成的视频描述，如：日落时分海浪拍打沙滩，电影感' :
          placeholder
        "
        rows="2"
        @keydown="onKey"
        :disabled="loading"
      ></textarea>
      <div class="actions">
        <button
          v-if="mode === 'WORK' && (mediaMode === 'AUTO' || mediaMode === 'TEXT')"
          class="parse"
          @click="parseCurrent"
          :disabled="parsing || !input.trim() || loading"
          title="把当前输入拆成任务"
        >
          {{ parsing ? '拆解中…' : '拆单' }}
        </button>
        <button v-if="loading" class="stop" @click="stop">中止</button>
        <button class="send" @click="onMainBtnClick" :disabled="sendDisabled()">
          {{ loading
              ? (mediaMode === 'VIDEO' ? '生成中…' : mediaMode === 'IMAGE' ? '生图中…' : mediaMode === 'TTS' ? '合成中…' : mediaMode === 'ASR' ? '转写中…' : '思考中…')
              : (mediaMode === 'ASR' ? '开始转写' : (mediaMode === 'IMAGE' ? '生成图片' : mediaMode === 'TTS' ? '开始朗读' : mediaMode === 'VIDEO' ? '生成视频' : '发送'))
          }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: #FFFFFF;
  border: none;
  box-shadow: none;
  border-radius: 0;
}
.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.ttl { font-weight: 600; font-size: 15px; color: var(--text); }
.hint { font-size: 12px; }

/* 模态选择器 */
.modes {
  display: flex;
  gap: 6px;
  padding: 10px 16px 8px;
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  background: #FFFFFF;
  flex-shrink: 0;
}
.mode {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  font-size: 12.5px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: #FFFFFF;
  color: var(--text-muted);
  cursor: pointer;
  white-space: nowrap;
  user-select: none;
  transition: all 0.15s ease;
}
.mode:hover { border-color: var(--text); color: var(--text); }
.mode.active {
  background: var(--text);
  color: #FFFFFF;
  border-color: var(--text);
}
.mode-sym { font-size: 13px; line-height: 1; }
.mode-txt { font-weight: 500; }

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.empty { text-align: center; padding: 32px 0; font-size: 13px; line-height: 1.8; }
.bubble {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.bubble.user {
  align-self: flex-end;
  background: #FFFFFF;
  border: 1px solid var(--text);
  color: var(--text);
}
.bubble.assistant {
  align-self: flex-start;
  background: #FFFFFF;
  border: 1px solid var(--border);
  color: var(--text);
}
.bubble-text { display: inline; }
.cursor {
  display: inline-block;
  margin-left: 1px;
  color: var(--text);
  animation: blink 1s steps(2, start) infinite;
}
@keyframes blink { to { visibility: hidden; } }
.spinner {
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-left: 6px;
  border: 1.5px solid var(--border);
  border-top-color: var(--text);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  vertical-align: middle;
}
@keyframes spin { to { transform: rotate(360deg); } }
.badge {
  display: inline-block;
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 0 6px;
}

/* 富内容网格/媒体 */
.rich-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 6px;
}
.rich-img {
  display: block;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border);
  background: #fafafa;
}
.rich-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.rich-media { width: 100%; }
.rich-media audio { width: 100%; min-width: 260px; max-width: 100%; }
.rich-video {
  width: 100%;
  max-width: 480px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: #000;
}

/* 任务指令自动执行摘要块 */
.exec {
  width: 100%;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  padding: 8px 10px;
  background: #fafafa;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.exec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--text);
  font-weight: 600;
}
.exec-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--text);
  line-height: 1.6;
}
.exec-line { word-break: break-word; }

.parsed {
  border-top: 1px solid var(--border);
  background: #FFFFFF;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.parsed-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--text-muted);
}
.parsed-head .save {
  font-size: 12px;
  background: #FFFFFF;
  color: var(--text);
  border: 1px solid var(--text);
  padding: 4px 12px;
}
.parsed-list { display: flex; flex-direction: column; gap: 6px; }
.parsed-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 13px;
}
.p-group {
  width: 22px; height: 22px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-full);
  font-size: 11px; font-weight: 600;
  background: #FFFFFF;
  color: var(--text);
  border: 1px solid var(--text);
  flex-shrink: 0;
}
.p-group.TOMORROW { border-style: dashed; }
.p-title { flex: 1; min-width: 0; word-break: break-word; }
.p-rm {
  font-size: 11px; padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--text-muted);
}
.p-rm:hover { border-color: var(--text); color: var(--text); }

.chat-input {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--border);
  background: #FFFFFF;
}
.chat-input textarea { resize: none; min-height: 52px; width: 100%; }
.asr-picker {
  flex: 1;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  padding: 12px 14px;
  min-height: 52px;
  display: flex;
  align-items: center;
  cursor: pointer;
  font-size: 13px;
}
.asr-picker:hover { border-color: var(--text); }
.asr-file { color: var(--text); }
.asr-ph { color: var(--text-muted); }

.actions { display: flex; flex-direction: column; gap: 6px; }
.parse {
  font-size: 12px;
  background: #FFFFFF;
  color: var(--text);
  border: 1px solid var(--text);
  padding: 5px 14px;
  white-space: nowrap;
  border-radius: var(--radius);
}
.parse:hover:not(:disabled) { background: var(--text); color: #FFFFFF; }
.stop {
  font-size: 12px;
  background: #FFFFFF;
  color: var(--danger);
  border: 1px solid var(--danger);
  padding: 5px 14px;
  white-space: nowrap;
  border-radius: var(--radius);
}
.send {
  background: var(--text);
  color: #FFFFFF;
  border: 1px solid var(--text);
  padding: 5px 20px;
  white-space: nowrap;
  border-radius: var(--radius);
  font-weight: 500;
}
.send:hover:not(:disabled) { opacity: 0.85; border-color: var(--text); }
.send:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
