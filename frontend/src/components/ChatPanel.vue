<script setup>
import { ref, nextTick } from 'vue'
import { chatStream, parseTasks, bulkCreateTasks } from '../api'

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
}
.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.ttl { font-weight: 600; font-size: 15px; }
.hint { font-size: 12px; }

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.empty { text-align: center; padding: 32px 0; }
.bubble {
  max-width: 80%;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-word;
  position: relative;
}
.bubble.user {
  align-self: flex-end;
  background: var(--brand-soft);
  border: 1px solid var(--brand);
  color: var(--brand-text);
}
.bubble.assistant {
  align-self: flex-start;
  background: var(--surface-muted);
  border: 1px solid var(--border);
}
.cursor {
  display: inline-block;
  margin-left: 1px;
  color: var(--brand);
  animation: blink 1s steps(2, start) infinite;
}
@keyframes blink { to { visibility: hidden; } }
.badge {
  display: inline-block;
  margin-left: 8px;
  font-size: 11px;
  color: var(--warning);
  border: 1px solid var(--warning);
  border-radius: var(--radius-full);
  padding: 0 6px;
}

.parsed {
  border-top: 1px solid var(--border);
  background: var(--surface-muted);
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
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
  background: var(--brand);
  color: var(--brand-on);
  border-color: var(--brand);
  padding: 4px 10px;
}
.parsed-list { display: flex; flex-direction: column; gap: 4px; }
.parsed-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 13px;
}
.p-group {
  width: 20px; height: 20px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--radius-full);
  font-size: 11px; font-weight: 600;
  background: var(--brand-soft); color: var(--brand-text);
  border: 1px solid var(--brand);
}
.p-group.TOMORROW { background: rgba(99, 102, 241, 0.12); color: #4f46e5; border-color: #6366f1; }
.p-title { flex: 1; min-width: 0; word-break: break-word; }
.p-rm {
  font-size: 11px; padding: 2px 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.chat-input {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--border);
}
.chat-input textarea { resize: none; }
.actions { display: flex; flex-direction: column; gap: 4px; }
.parse {
  font-size: 12px;
  background: var(--surface);
  color: var(--brand-text);
  border: 1px solid var(--brand);
  padding: 4px 14px;
  white-space: nowrap;
}
.stop {
  font-size: 12px;
  background: var(--surface);
  color: var(--danger);
  border: 1px solid var(--danger);
  padding: 4px 14px;
  white-space: nowrap;
}
.send {
  background: var(--brand);
  color: var(--brand-on);
  border-color: var(--brand);
  padding: 4px 20px;
  white-space: nowrap;
}
.send:hover { opacity: 0.9; border-color: var(--brand); }
</style>
