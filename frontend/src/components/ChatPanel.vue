<script setup>
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import {
  chatStream,
  parseTasks,
  bulkCreateTasks,
  getChatHistory,
  generateImage,
  generateSpeech,
  transcribeAudio,
  generateVideo,
  pollTask,
  getModelsOverview,
  activateApiProfile
} from '../api'

const props = defineProps({
  mode: { type: String, default: 'WORK' }, // WORK | DOPAMINE
  title: { type: String, default: '大模型对话' },
  placeholder: { type: String, default: '输入你的需求…' }
})

const emit = defineEmits(['tasks-created'])

// ================= 模型选择器 =================
/**
 * 聊天页顶部只有一个「模型」下拉，列出设置页里所有 AI 模型（按能力分组）。
 * 点选某个模型 = 把它设为该能力的默认（后端 activate 接口，同类只保留一个默认）。
 *
 * 发送时按内容自动路由（避免用户先手动切 Tab 再说话）：
 *   「画一只猫」      → 图片模型
 *   「生成一段视频」  → 视频模型
 *   「朗读这段话」    → 语音模型
 *   其余              → 文本模型
 * 该能力一个模型都没配时，前端直接回「你还没有「X模型」哦」，不浪费一次网络请求；
 * 配置了但 Key 失效等系统性问题仍交给后端返回的友好文案。
 */
const CAP_ORDER = [
  { type: 'LLM',       key: 'cap-text',      label: '文本' },
  { type: 'IMAGE',     key: 'cap-image',     label: '图片' },
  { type: 'AUDIO',     key: 'cap-audio',     label: '语音' },
  { type: 'VIDEO',     key: 'cap-video',     label: '视频' },
  { type: 'EMBEDDING', key: 'cap-embedding', label: '向量化' }
]

const models = ref([])           // 全量 AI 模型（apiKey 已打码）
const modelMenuOpen = ref(false)
// 只有「转写」需要显式切换（要先选文件）；其余场景一律 AUTO 自动路由。
const mediaMode = ref('AUTO')
const asrFile = ref(null)
const asrFileName = ref('')

/** 顶部下拉当前展示的文本模型名（没有则提示未配置） */
const currentModelLabel = computed(() => {
  const active = models.value.find((m) => m.profileType === 'LLM' && m.isActive)
  if (active) return active.name || active.model || '文本模型'
  return '未配置文本模型'
})

/** 按能力分组，空组也保留（让用户看到「这一类还没配」） */
const modelGroups = computed(() =>
  CAP_ORDER.map((c) => ({
    ...c,
    items: models.value.filter((m) => m.profileType === c.type)
  }))
)

/** 拉模型列表；失败不阻塞聊天（下拉显示未配置，发送时后端仍会给友好提示） */
async function loadModels() {
  try {
    const res = await getModelsOverview()
    models.value = Array.isArray(res?.ai) ? res.ai : []
  } catch (e) {
    console.warn('[ChatPanel] 加载模型列表失败：', e.message)
    models.value = []
  }
}

/** 点选下拉里的模型 → 设为该能力默认 */
async function selectModel(m) {
  modelMenuOpen.value = false
  if (!m || m.isActive) return
  try {
    await activateApiProfile(m.id)
    await loadModels()
  } catch (e) {
    console.warn('[ChatPanel] 切换默认模型失败：', e.message)
  }
}

function hasCapability(type) {
  return models.value.some((m) => m.profileType === type)
}

/** 该能力有模型但没有默认的，顺手激活第一条（后端同类只保留一个 active） */
async function ensureActive(type) {
  const list = models.value.filter((m) => m.profileType === type)
  if (!list.length) return false
  if (list.some((m) => m.isActive)) return true
  try {
    await activateApiProfile(list[0].id)
    await loadModels()
    return true
  } catch (e) {
    console.warn('[ChatPanel] 激活模型失败：', e.message)
    return false
  }
}

/**
 * 意图识别：只认明确的动作描述，避免「视频脚本」「海报文案」这类写作需求被误判。
 * 顺序：先排除文案类 → 视频 → 图片 → 朗读 → 文本。
 */
function detectIntent(text) {
  const t = text.trim()
  if (/(脚本|文案|方案|大纲|字幕稿|提示词)/.test(t)) return 'TEXT'
  if (/(生成|制作|做|拍|来).{0,8}(视频|短片|动画)|视频.{0,6}(生成|制作)/.test(t)) return 'VIDEO'
  if (/(画|绘制|生成|制作|做|来).{0,8}(图|插画|海报|壁纸|头像|logo)|来(一)?张图|^请?(帮我)?画(?!风|面|质|法|笔)/.test(t)) return 'IMAGE'
  if (/^(朗读|念一下|读一下|读一遍|帮我读|用语音说)/.test(t)) return 'TTS'
  return 'TEXT'
}

/** 缺模型时的统一回复（与后端 MediaService/LlmGatewayService 文案同款） */
function pushMissingHint(intent, text) {
  const label = { IMAGE: '图片', VIDEO: '视频', TTS: '语音' }[intent] || '对应'
  messages.value.push({ id: nextMsgId(), role: 'user', content: text })
  input.value = ''
  messages.value.push({
    id: nextMsgId(),
    role: 'assistant',
    content: `你还没有「${label}模型」哦～ 去「设置 → AI 模型」添加一个${label}模型，就能用了。`
  })
  scrollToBottom()
}

// ================= 消息 / 对话状态 =================
const messages = ref([])
const input = ref('')
const loading = ref(false)
const listEl = ref(null)
const fileInputRef = ref(null)
let abortFn = null
// 流式序号：切换 props.mode（或再次发送）后旧流的 onDelta/onDone/onError 会被丢弃，
// 避免旧流增量写进新会话（同 TopBar.vue 里 weatherSeq 的写法）。
let streamSeq = 0

// 稳定消息 id：历史里没有 id，用本地自增序号补一个，给 v-for :key 用
let msgSeq = 0
function nextMsgId() { return `m${++msgSeq}` }

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
  // 模型列表与历史并行拉：模型拉不到不阻塞聊天（发送时后端仍会兜底提示）
  loadModels()
  try {
    const list = await getChatHistory(props.mode, 50)
    if (Array.isArray(list) && list.length) {
      messages.value = list.map((m) => {
        const { text, rich } = parseContent(m.content ?? '')
        return {
          id: nextMsgId(),
          role: m.role || 'assistant',
          content: text,
          rich,
          streaming: false
        }
      })
      // 恢复 pending 状态的富媒体消息的自动轮询
      for (const bubble of messages.value) {
        if (bubble.rich && bubble.rich.payload && bubble.rich.payload.status === 'pending'
            && bubble.rich.payload.profileId && bubble.rich.payload.message) {
          const taskId = extractTaskId(bubble.rich.payload.message)
          if (taskId) {
            bubble.content = '⏳ 继续生成中（页面已刷新，恢复轮询）…'
            startPolling(bubble, taskId, bubble.rich.payload.profileId, bubble.rich.kind)
          }
        }
      }
      await scrollToBottom()
    }
  } catch (e) {
    console.warn('[ChatPanel] 加载对话历史失败：', e.message)
  }
})

// ================= Blob URL 生命周期 =================
// 每条 TTS 音频只在「生成完成时」算一次 ObjectURL 并缓存在气泡上，
// 模板直接读 m.audioUrl（不再每次渲染都调用 audioSrc() 造新 URL）；
// 组件卸载时统一 revoke，避免内存泄漏（全项目其它地方均无 revokeObjectURL）。
const blobUrls = new Set()

// 异步任务轮询表：bubble -> timer。声明提前，供 onBeforeUnmount 清理使用。
const activePollers = new Map()

function makeAudioUrl(payload) {
  if (!payload || !payload.audioBytes) return ''
  try {
    const bytes = Uint8Array.from(atob(payload.audioBytes), (c) => c.charCodeAt(0))
    const blob = new Blob([bytes], { type: payload.audioMime || 'audio/mpeg' })
    const url = URL.createObjectURL(blob)
    blobUrls.add(url)
    return url
  } catch (_) {
    return ''
  }
}

/** 替换/清空某气泡的音频时，先释放旧 URL */
function setAudioUrl(bubble, url) {
  if (bubble.audioUrl && bubble.audioUrl !== url) {
    URL.revokeObjectURL(bubble.audioUrl)
    blobUrls.delete(bubble.audioUrl)
  }
  bubble.audioUrl = url || ''
}

onBeforeUnmount(() => {
  // 1) 中止进行中的流
  streamSeq++
  if (abortFn) { try { abortFn() } catch (_) { /* 忽略 */ } abortFn = null }
  // 2) 清掉所有异步任务轮询定时器
  for (const timer of activePollers.values()) clearInterval(timer)
  activePollers.clear()
  // 3) 释放所有缓存过的 Blob URL
  for (const url of blobUrls) URL.revokeObjectURL(url)
  blobUrls.clear()
})

// ================= 统一发送入口 =================
async function sendText() {
  const text = input.value.trim()
  if (!text || loading.value) return
  if (mediaMode.value === 'ASR') return // 转写要先选文件，走文件按钮

  const intent = detectIntent(text)
  if (intent === 'TEXT') return sendTextLLM(text)

  const type = intent === 'TTS' ? 'AUDIO' : intent
  if (!hasCapability(type)) return pushMissingHint(intent, text)
  if (!(await ensureActive(type))) return pushMissingHint(intent, text)

  switch (intent) {
    case 'IMAGE': return doImage(text)
    case 'TTS':   return doTTS(text)
    case 'VIDEO': return doVideo(text)
    default:      return sendTextLLM(text)
  }
}

/** 普通文本模型流式对话（也是「智能」Tab 的默认行为） */
async function sendTextLLM(text) {
  messages.value.push({ id: nextMsgId(), role: 'user', content: text })
  input.value = ''
  loading.value = true
  const bubble = reactive({ id: nextMsgId(), role: 'assistant', content: '', streaming: true })
  messages.value.push(bubble)
  await scrollToBottom()

  const seq = ++streamSeq
  abortFn = chatStream(text, props.mode, {
    onDelta: (delta) => {
      if (seq !== streamSeq) return
      bubble.content += delta
      scrollToBottom()
    },
    onDone: () => {
      if (seq !== streamSeq) return
      bubble.streaming = false
      loading.value = false
      abortFn = null
    },
    onError: (msg) => {
      if (seq !== streamSeq) return
      bubble.content += `\n[流式错误: ${msg}]`
      bubble.streaming = false
      bubble.degraded = true
      loading.value = false
      abortFn = null
    }
  })
}

function stop() {
  streamSeq++            // 作废当前流序号：abort 触发的 onDone 不会再改写状态
  if (abortFn) { try { abortFn() } catch (_) { /* 忽略 */ } }
  abortFn = null
  loading.value = false
  const last = messages.value[messages.value.length - 1]
  if (last && last.streaming) {
    last.streaming = false
    last.content += '\n[已中止]'
  }
}

// ================= IMAGE 文生图 =================
async function doImage(prompt) {
  loading.value = true
  messages.value.push({ id: nextMsgId(), role: 'user', content: '【文生图】' + prompt })
  input.value = ''
  const bubble = reactive({ id: nextMsgId(), role: 'assistant', content: '🎨 生图中，请稍候…', loading: true })
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
      // 自动轮询 Dashscope 异步任务（每 5 秒查一次，最多 12 次 = 60 秒）
      if (res.profileId && res.message) {
        const taskId = extractTaskId(res.message)
        if (taskId) startPolling(bubble, taskId, res.profileId, 'IMAGE')
      }
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

// 从 Dashscope pending 消息中提取 task_id
function extractTaskId(message) {
  if (!message) return null
  const m = message.match(/task_id[=:]\s*([a-f0-9-]+)/i)
  return m ? m[1] : null
}

// 自动轮询异步任务状态
function startPolling(bubble, taskId, profileId, kind) {
  if (activePollers.has(bubble)) return
  let count = 0
  const maxAttempts = 12
  const timer = setInterval(async () => {
    count++
    try {
      const res = await pollTask({ taskId, profileId, kind })
      if (res.status === 'succeeded' && res.items && res.items.length) {
        clearInterval(timer)
        activePollers.delete(bubble)
        bubble.content = kind === 'VIDEO'
          ? '🎬 视频生成完成（点击可在新标签页播放）'
          : '🎨 生成完成（点击图片可新标签页打开）'
        bubble.rich = { kind, payload: res, text: bubble.content }
        // 结果只在本地 state 里生效；不在这里发 GET /api/llm/history
        // （GET 没有写入能力，既不能"同步到后端数据库"，也只会白白多一次请求）。
        scrollToBottom()
      } else if (res.status === 'degraded' || res.error) {
        clearInterval(timer)
        activePollers.delete(bubble)
        bubble.content = '❌ 生成失败：' + (res.error || res.message || '未知错误')
        bubble.degraded = true
        scrollToBottom()
      } else {
        // 仍在处理中，更新提示
        bubble.content = `⏳ 图片生成中（第 ${count} 次查询，状态：${res.message || res.status}）…`
      }
    } catch (e) {
      // 轮询出错不停止，继续重试
    }
    if (count >= maxAttempts) {
      clearInterval(timer)
      activePollers.delete(bubble)
      bubble.content = '⏰ 生成超时（60秒），请稍后刷新页面查看结果'
      scrollToBottom()
    }
  }, 5000)
  activePollers.set(bubble, timer)
}

// ================= TTS 文生语音 =================
async function doTTS(text) {
  loading.value = true
  messages.value.push({ id: nextMsgId(), role: 'user', content: '【朗读】' + text })
  input.value = ''
  const bubble = reactive({ id: nextMsgId(), role: 'assistant', content: '🔊 合成语音中…', loading: true })
  messages.value.push(bubble)
  await scrollToBottom()
  try {
    const res = await generateSpeech({ input: text, voice: 'alloy', format: 'mp3', speed: 1.0, mode: props.mode })
    bubble.loading = false
    if (res.status === 'succeeded') {
      bubble.content = '🔊 朗读完成（点击音频播放）'
      bubble.rich = { kind: 'AUDIO_TTS', payload: res, text: bubble.content }
      setAudioUrl(bubble, makeAudioUrl(res))   // 只在这里算一次 ObjectURL 并缓存
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
  messages.value.push({ id: nextMsgId(), role: 'user', content: '【语音转写】' + theName })
  const bubble = reactive({ id: nextMsgId(), role: 'assistant', content: '🎙 转写中…', loading: true })
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
  messages.value.push({ id: nextMsgId(), role: 'user', content: '【文生视频】' + prompt })
  input.value = ''
  const bubble = reactive({ id: nextMsgId(), role: 'assistant', content: '🎬 视频生成中（较慢约 1~3 分钟）…', loading: true })
  messages.value.push(bubble)
  await scrollToBottom()
  try {
    const res = await generateVideo({ prompt, seconds: 5, ratio: '16:9', mode: props.mode })
    bubble.loading = false
    if (res.status === 'succeeded') {
      bubble.content = '🎬 视频生成完成'
      bubble.rich = { kind: 'VIDEO', payload: res, text: bubble.content }
    } else if (res.status === 'pending') {
      bubble.content = '⏳ 视频仍在排队处理中（异步任务已提交，请稍候）'
      bubble.rich = { kind: 'VIDEO', payload: res, text: bubble.content }
      // 视频异步任务自动轮询
      if (res.profileId && res.message) {
        const taskId = extractTaskId(res.message)
        if (taskId) startPolling(bubble, taskId, res.profileId, 'VIDEO')
      }
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
      id: nextMsgId(),
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
      id: nextMsgId(),
      role: 'assistant',
      content: `已把 ${created.length} 条任务写入流程表。`
    })
    await scrollToBottom()
  } catch (e) {
    messages.value.push({
      id: nextMsgId(),
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

/** 发送按钮文案：提前告诉用户这句话会被路由到哪个能力 */
const sendLabel = computed(() => {
  if (mediaMode.value === 'ASR') return loading.value ? '转写中…' : '开始转写'
  if (loading.value) return '处理中…'
  return { IMAGE: '生成图片', VIDEO: '生成视频', TTS: '开始朗读' }[detectIntent(input.value)] || '发送'
})
</script>

<template>
  <section class="chat card">
    <div class="chat-head">
      <span class="ttl">{{ title }}</span>
      <span class="hint muted">回车发送 · Shift+回车换行</span>
    </div>

    <!-- 顶栏：一个模型下拉 + 转写入口；其余按输入内容自动路由 -->
    <div class="model-bar">
      <button class="model-picker" type="button" @click="modelMenuOpen = !modelMenuOpen">
        <span class="mp-label">模型</span>
        <span class="mp-name">{{ currentModelLabel }}</span>
        <span class="mp-caret">▾</span>
      </button>
      <button
        class="asr-btn"
        type="button"
        :class="{ active: mediaMode === 'ASR' }"
        @click="mediaMode = mediaMode === 'ASR' ? 'AUTO' : 'ASR'"
      >🎙 转写音频</button>
      <span class="mp-hint muted">说「画…」「生成视频…」「朗读…」会自动调用对应模型</span>

      <div v-if="modelMenuOpen" class="menu-mask" @click="modelMenuOpen = false"></div>
      <div v-if="modelMenuOpen" class="model-menu">
        <div v-for="grp in modelGroups" :key="grp.type" class="mg">
          <div class="mg-title"><span class="cap-chip" :class="grp.key">{{ grp.label }}</span></div>
          <div v-if="!grp.items.length" class="mg-empty">还没添加，去「设置 → AI 模型」添加</div>
          <button
            v-for="m in grp.items"
            :key="m.id"
            type="button"
            class="mg-item"
            :class="{ active: m.isActive }"
            @click="selectModel(m)"
          >
            <span class="mg-name">{{ m.name }}</span>
            <span class="mg-sub">{{ m.model || m.provider || '' }}<template v-if="m.isActive"> · 默认</template></span>
          </button>
        </div>
        <div class="mg-foot">
          <a href="#/settings" @click="modelMenuOpen = false">去设置添加 / 管理模型 →</a>
        </div>
      </div>
    </div>

    <div class="chat-list" ref="listEl">
      <div v-if="!messages.length" class="empty muted">
        在下方输入，让大模型帮你规划{{ mode === 'WORK' ? '今天/明天的安排' : '这一餐吃什么' }}。<br/>
        直接说「画一只猫」「生成一段视频」「朗读这段话」，会自动调用对应模型；<br/>
        还没配某类模型时，去「设置 → AI 模型」加一个就行。默认用顶部下拉选中的模型。
      </div>
      <div
        v-for="m in messages"
        :key="m.id"
        class="bubble"
        :class="m.role"
      >
        <div class="bubble-text">
          {{ m.content }}<span v-if="m.streaming" class="cursor">▌</span>
          <span v-if="m.loading" class="spinner"></span>
          <span v-if="m.degraded" class="badge">降级</span>
        </div>

        <!-- 富内容：图片 -->
        <div v-if="m.rich && m.rich.kind === 'IMAGE'" class="rich-block">
          <div v-if="m.rich.payload && m.rich.payload.items && m.rich.payload.items.length" class="rich-grid">
            <a
              v-for="(it, j) in m.rich.payload.items"
              :key="it.url || it.b64Data || ('img-' + m.id + '-' + j)"
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
          <audio v-if="m.audioUrl" :src="m.audioUrl" controls preload="metadata"></audio>
          <div v-else class="muted" style="font-size: 12px;">（当前浏览器无法播放该音频）</div>
        </div>

        <!-- 富内容：视频 -->
        <div v-if="m.rich && m.rich.kind === 'VIDEO' && m.rich.payload && m.rich.payload.items" class="rich-media">
          <video
            v-for="(it, j) in m.rich.payload.items"
            :key="it.url || ('video-' + m.id + '-' + j)"
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
        <!-- 拆单结果由 parseTasks 返回，后端不带稳定 id；用「标题+分组+序号」组合成 key，
             序号只是防重复标题冲突，不作为主标识。索引 i 仍用于 removeParsed(i)。 -->
        <div v-for="(t, i) in parsedTasks" :key="`${t.group}-${t.title}-${i}`" class="parsed-item">
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
        :placeholder="mediaMode === 'ASR' ? '（先选择一个音频文件）' : placeholder"
        rows="2"
        @keydown="onKey"
        :disabled="loading"
      ></textarea>
      <div class="actions">
        <button
          v-if="mode === 'WORK' && mediaMode === 'AUTO'"
          class="parse"
          @click="parseCurrent"
          :disabled="parsing || !input.trim() || loading"
          title="把当前输入拆成任务"
        >
          {{ parsing ? '拆解中…' : '拆单' }}
        </button>
        <button v-if="loading" class="stop" @click="stop">中止</button>
        <button class="send" @click="onMainBtnClick" :disabled="sendDisabled()">{{ sendLabel }}</button>
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

/* 模型栏：一个下拉 + 转写入口，其余按内容自动路由 */
.model-bar {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  background: #FFFFFF;
  flex-shrink: 0;
}
.model-picker {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 12.5px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: #FFFFFF;
  color: var(--text);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.model-picker:hover { border-color: var(--text); }
.mp-label { color: var(--text-muted); }
.mp-name { font-weight: 600; max-width: 180px; overflow: hidden; text-overflow: ellipsis; }
.mp-caret { color: var(--text-muted); font-size: 11px; }
.asr-btn {
  padding: 6px 12px; font-size: 12.5px; border: 1px solid var(--border);
  border-radius: 999px; background: #FFFFFF; color: var(--text-muted);
  cursor: pointer; white-space: nowrap; transition: all 0.15s ease;
}
.asr-btn:hover { border-color: var(--text); color: var(--text); }
.asr-btn.active { background: var(--text); border-color: var(--text); color: #FFFFFF; }
.mp-hint { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.menu-mask { position: fixed; inset: 0; z-index: 20; background: transparent; }
.model-menu {
  position: absolute; left: 16px; top: 46px; z-index: 21;
  width: 320px; max-height: 420px; overflow-y: auto;
  background: #FFFFFF; border: 1px solid var(--border); border-radius: var(--radius-card);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12); padding: 10px 12px;
}
.mg { padding: 6px 0; border-bottom: 1px dashed var(--border); }
.mg:last-of-type { border-bottom: none; }
.mg-title { margin-bottom: 6px; }
.mg-empty { font-size: 12px; color: var(--text-muted); padding: 2px 0; }
.mg-item {
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  width: 100%; text-align: left; padding: 6px 8px; margin-bottom: 4px;
  border: 1px solid transparent; border-radius: 8px; background: #FAFAFA; cursor: pointer;
}
.mg-item:hover { border-color: var(--text); }
.mg-item.active { background: #f2fbf4; border-color: var(--success); }
.mg-name { font-size: 13px; font-weight: 600; color: var(--text); }
.mg-sub { font-size: 11.5px; color: var(--text-muted); }
.mg-foot { padding: 8px 2px 2px; font-size: 12px; }
.mg-foot a { color: var(--text); }

.cap-chip {
  display: inline-block; padding: 1px 8px; border-radius: 999px; font-size: 11px;
  color: var(--text); background: #F3F4F6; border: 1px solid var(--border);
}
.cap-chip.cap-text { background: #EEF4FF; border-color: #D7E3FB; color: #1d4ed8; }
.cap-chip.cap-image { background: #FFF3E8; border-color: #FBD9BC; color: #b45309; }
.cap-chip.cap-audio { background: #F1EEFF; border-color: #DCD3FB; color: #6d28d9; }
.cap-chip.cap-video { background: #FEEEF0; border-color: #F9CCD3; color: #be123c; }
.cap-chip.cap-embedding { background: #ECFAF2; border-color: #C7EED8; color: #15803d; }

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
