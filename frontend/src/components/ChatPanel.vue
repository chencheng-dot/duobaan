<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { chatStream, parseTasks, bulkCreateTasks, getChatHistory } from '../api'

const props = defineProps({
  mode: { type: String, default: 'WORK' }, // WORK | DOPAMINE
  title: { type: String, default: '大模型对话' },
  placeholder: { type: String, default: '输入你的需求…' }
})

// 任务写入成功后通知父组件刷新流程表
const emit = defineEmits(['tasks-created'])

const messages = ref([])
const input = ref('')
const loading = ref(false)
const listEl = ref(null)
let abortFn = null

// 拆单结果（待确认/已确认）
const parsedTasks = ref([])
const parsing = ref(false)
const saving = ref(false)

async function scrollToBottom() {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

/** 组件挂载时拉最近 50 条历史，恢复上一次对话 — 刷新页面不再空 */
onMounted(async () => {
  try {
    const list = await getChatHistory(props.mode, 50)
    if (Array.isArray(list) && list.length) {
      messages.value = list.map((m) => ({
        role: m.role || 'assistant',
        content: m.content ?? ''
      }))
      await scrollToBottom()
    }
  } catch (e) {
    // 拉历史失败不阻断对话功能，仅当首次加载为空
    console.warn('[ChatPanel] 加载对话历史失败：', e.message)
  }
})

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true
  // 占位 assistant 气泡，逐 token 追加
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
    },
    onError: (msg) => {
      bubble.content += `\n[流式错误: ${msg}]`
      bubble.streaming = false
      bubble.degraded = true
      loading.value = false
      abortFn = null
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

// 拆单：把当前输入框内容拆成结构化任务
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

function removeParsed(i) {
  parsedTasks.value.splice(i, 1)
}

// 一键写入流程表
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
    send()
  }
}
</script>

<template>
  <section class="chat card">
    <div class="chat-head">
      <span class="ttl">{{ title }}</span>
      <span class="hint muted">回车发送 · Shift+回车换行</span>
    </div>

    <div class="chat-list" ref="listEl">
      <div v-if="!messages.length" class="empty muted">
        在下方输入，让大模型帮你规划{{ mode === 'WORK' ? '今天/明天的安排' : '这一餐吃什么' }}。
      </div>
      <div
        v-for="(m, i) in messages"
        :key="i"
        class="bubble"
        :class="m.role"
      >
        {{ m.content }}<span v-if="m.streaming" class="cursor">▌</span>
        <span v-if="m.degraded" class="badge">降级</span>
      </div>
    </div>

    <!-- 拆单结果卡片：待确认后写入流程表 -->
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

    <div class="chat-input">
      <textarea
        v-model="input"
        :placeholder="placeholder"
        rows="2"
        @keydown="onKey"
        :disabled="loading"
      ></textarea>
      <div class="actions">
        <button
          v-if="mode === 'WORK'"
          class="parse"
          @click="parseCurrent"
          :disabled="parsing || !input.trim() || loading"
          title="把当前输入拆成任务"
        >
          {{ parsing ? '拆解中…' : '拆单' }}
        </button>
        <button v-if="loading" class="stop" @click="stop">中止</button>
        <button class="send" @click="send" :disabled="loading || !input.trim()">
          {{ loading ? '思考中…' : '发送' }}
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

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.empty { text-align: center; padding: 32px 0; font-size: 13px; }
.bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: var(--radius);
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  position: relative;
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
.cursor {
  display: inline-block;
  margin-left: 1px;
  color: var(--text);
  animation: blink 1s steps(2, start) infinite;
}
@keyframes blink { to { visibility: hidden; } }
.badge {
  display: inline-block;
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 0 6px;
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
.p-group.TOMORROW {
  border-style: dashed;
}
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
.chat-input textarea { resize: none; }
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
</style>
