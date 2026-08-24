<script setup>
import { ref, onMounted, computed } from 'vue'
import { getTasks, createTask, patchTask, migrateTask, submitTasks, deleteTask } from '../api'

const today = ref([])
const tomorrow = ref([])
const activeGroup = ref('TODAY')
const newTitle = ref('')
const submitting = ref(false)
const submitResult = ref(null)

// 返回真正的数组（.value），否则 v-for 会遍历 ref 对象本身
const list = computed(() => (activeGroup.value === 'TODAY' ? today.value : tomorrow.value))

// 当前激活分组对应的底层数组（用于直接增删，避免给 computed 赋值）
function targetArray() {
  return activeGroup.value === 'TODAY' ? today.value : tomorrow.value
}

async function load() {
  today.value = await getTasks('TODAY')
  tomorrow.value = await getTasks('TOMORROW')
}

async function add() {
  const t = newTitle.value.trim()
  if (!t) return
  const created = await createTask({ title: t, category: 'CUSTOM', group: activeGroup.value })
  targetArray().push(created)
  newTitle.value = ''
}

async function toggle(t) {
  const next = t.status === 'DONE' ? 'TODO' : 'DONE'
  const updated = await patchTask(t.id, { status: next })
  Object.assign(t, updated)
}

async function move(t, group) {
  await migrateTask(t.id, group)
  await load()
}

async function remove(t) {
  await deleteTask(t.id)
  const arr = targetArray()
  const idx = arr.findIndex((x) => x.id === t.id)
  if (idx >= 0) arr.splice(idx, 1)
}

async function submit() {
  submitting.value = true
  try {
    submitResult.value = await submitTasks()
    await load()
    setTimeout(() => (submitResult.value = null), 3000)
  } finally {
    submitting.value = false
  }
}

function statusText(s) {
  return { TODO: '待办', DOING: '进行中', DONE: '完成', SUBMITTED: '已上交' }[s] || s
}

onMounted(load)
</script>

<template>
  <section class="flow card">
    <div class="flow-head">
      <span class="ttl">流程表</span>
      <button class="submit" @click="submit" :disabled="submitting">
        {{ submitting ? '上交中…' : '上交今日小结' }}
      </button>
    </div>

    <div v-if="submitResult" class="result">
      已上交 {{ submitResult.submittedCount }} 项，剩余 {{ submitResult.remainingCount }} 项
    </div>

    <div class="tabs">
      <button :class="{ active: activeGroup === 'TODAY' }" @click="activeGroup = 'TODAY'">
        今日 · {{ today.length }}
      </button>
      <button :class="{ active: activeGroup === 'TOMORROW' }" @click="activeGroup = 'TOMORROW'">
        明日 · {{ tomorrow.length }}
      </button>
    </div>

    <div class="add-row">
      <input v-model="newTitle" placeholder="新增任务…" @keydown.enter="add" />
      <button class="add-btn" @click="add">＋</button>
    </div>

    <div class="task-list">
      <div v-if="!list.length" class="empty muted">暂无任务</div>
      <div v-for="t in list" :key="t.id" class="task" :class="t.status.toLowerCase()">
        <label class="check">
          <input type="checkbox" :checked="t.status === 'DONE'" @change="toggle(t)" />
        </label>
        <div class="t-body">
          <div class="t-title">{{ t.title }}</div>
          <div class="t-meta muted">
            <span class="cat">{{ t.category }}</span> ·
            <span class="src">{{ t.source === 'LLM' ? 'AI生成' : '手动' }}</span> ·
            <span class="st">{{ statusText(t.status) }}</span>
          </div>
        </div>
        <div class="t-actions">
          <button class="mini" v-if="activeGroup === 'TODAY'" @click="move(t, 'TOMORROW')" title="挪到明日">→明日</button>
          <button class="mini" v-else @click="move(t, 'TODAY')" title="挪到今日">←今日</button>
          <button class="mini danger" @click="remove(t)" title="删除">✕</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.flow {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.flow-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.ttl { font-weight: 600; font-size: 15px; }
.submit {
  font-size: 12px;
  background: var(--brand-soft);
  color: var(--brand-text);
  border-color: var(--brand);
  padding: 5px 10px;
}
.result {
  padding: 8px 16px;
  font-size: 13px;
  color: var(--success);
  background: rgba(29, 201, 129, 0.08);
  border-bottom: 1px solid var(--border);
}
.tabs { display: flex; gap: 4px; padding: 8px 12px 0; }
.tabs button {
  flex: 1; font-size: 13px; padding: 8px;
  border: 1px solid var(--border); border-radius: var(--radius);
  background: var(--surface);
}
.tabs button.active { background: var(--brand-soft); color: var(--brand-text); border-color: var(--brand); }

.add-row { display: flex; gap: 6px; padding: 8px 12px; }
.add-btn { width: 36px; padding: 0; font-size: 18px; }

.task-list { flex: 1; overflow-y: auto; padding: 4px 12px 12px; }
.empty { text-align: center; padding: 24px 0; }
.task {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  margin-bottom: 6px;
  background: var(--surface);
}
.task.done { background: var(--surface-muted); }
.task.done .t-title { text-decoration: line-through; color: var(--text-muted); }
.check { margin-top: 2px; }
.t-body { flex: 1; min-width: 0; }
.t-title { font-size: 14px; word-break: break-word; }
.t-meta { font-size: 12px; margin-top: 2px; }
.t-actions { display: flex; flex-direction: column; gap: 4px; }
.mini { font-size: 11px; padding: 3px 6px; border-radius: var(--radius); }
.mini.danger:hover { border-color: var(--danger); color: var(--danger); }
</style>
