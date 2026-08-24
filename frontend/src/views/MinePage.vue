<script setup>
import { ref, computed, onMounted } from 'vue'
import { getMineHistory } from '../api'

const tab = ref('submitted') // 'submitted' | 'done' | 'deleted'
const loading = ref(true)
const errorMsg = ref('')
const data = ref({ submitted: [], done: [], deleted: [] })

const counts = computed(() => ({
  submitted: data.value.submitted?.length || 0,
  done: data.value.done?.length || 0,
  deleted: data.value.deleted?.length || 0
}))

const currentList = computed(() => {
  if (tab.value === 'submitted') return data.value.submitted || []
  if (tab.value === 'done') return data.value.done || []
  return data.value.deleted || []
})

async function load() {
  try {
    loading.value = true
    data.value = await getMineHistory()
  } catch (e) {
    errorMsg.value = '加载历史失败：' + e.message
  } finally {
    loading.value = false
  }
}

function fmtDate(d) {
  if (!d) return '—'
  try {
    const dt = new Date(d)
    if (isNaN(dt.getTime())) return d
    const pad = (n) => String(n).padStart(2, '0')
    return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`
  } catch {
    return d
  }
}

function taskSortKey(t) {
  if (tab.value === 'submitted') return t.submittedAt || t.createdAt
  if (tab.value === 'deleted') return t.deletedAt || t.createdAt
  return t.createdAt
}

onMounted(load)
</script>

<template>
  <div class="mine">
    <!-- 标题 -->
    <section class="title-row">
      <div class="title-text">
        <h1>我的</h1>
        <p class="sub">已上交 / 已完成 / 已删除 — 工作留痕</p>
      </div>
      <div class="title-line"></div>
    </section>

    <!-- Tab 三栏切换 -->
    <div class="tabs">
      <div class="tab" :class="{ active: tab === 'submitted' }" @click="tab = 'submitted'">
        已上交
        <span class="count-pill">{{ counts.submitted }}</span>
      </div>
      <div class="tab" :class="{ active: tab === 'done' }" @click="tab = 'done'">
        已完成
        <span class="count-pill">{{ counts.done }}</span>
      </div>
      <div class="tab" :class="{ active: tab === 'deleted' }" @click="tab = 'deleted'">
        已删除
        <span class="count-pill deleted">{{ counts.deleted }}</span>
      </div>
    </div>

    <div v-if="loading" class="empty-state">加载中…</div>
    <div v-else-if="errorMsg" class="empty-state error">{{ errorMsg }}</div>
    <template v-else>
      <div v-if="!currentList.length" class="empty-state">
        <template v-if="tab === 'submitted'">还没有上交的任务。完成任务后点击「上交今日小结」会出现在这里。</template>
        <template v-else-if="tab === 'done'">还没有已完成的任务。勾选任务完成状态后会出现在这里。</template>
        <template v-else>还没有删除过的任务。删除的任务会保留在这里留痕，不会真的从数据库移除。</template>
      </div>

      <div v-else class="task-list">
        <div
          v-for="t in [...currentList].sort((a, b) => new Date(taskSortKey(b)) - new Date(taskSortKey(a)))"
          :key="t.id"
          class="task-card"
          :class="'cat-' + (t.category || 'CUSTOM').toLowerCase()"
        >
          <div class="task-head">
            <div class="task-title">{{ t.title }}</div>
            <div class="task-tags">
              <span class="tag cat-tag">{{ t.category }}</span>
              <span class="tag grp-tag">{{ t.group === 'TODAY' ? '今日' : '明日' }}</span>
              <span v-if="t.source === 'LLM'" class="tag src-tag">AI拆单</span>
              <span v-else class="tag src-tag">手动</span>
              <span
                v-if="tab === 'submitted'"
                class="tag st-tag submitted">已上交</span>
              <span
                v-else-if="tab === 'done'"
                class="tag st-tag done">已完成</span>
              <span
                v-else
                class="tag st-tag deleted">已删除</span>
            </div>
          </div>
          <div class="task-foot">
            <div class="meta">
              <span class="meta-item"><strong>创建：</strong>{{ fmtDate(t.createdAt) }}</span>
              <span v-if="tab === 'submitted'" class="meta-item highlight">
                <strong>上交：</strong>{{ fmtDate(t.submittedAt || t.createdAt) }}
              </span>
              <span v-if="tab === 'deleted'" class="meta-item danger">
                <strong>删除：</strong>{{ fmtDate(t.deletedAt || t.createdAt) }}
              </span>
              <span v-if="t.estimatedMinutes" class="meta-item">
                <strong>预估：</strong>{{ t.estimatedMinutes }} 分钟
              </span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.mine {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 760px;
  margin: 0 auto;
  height: 100%;
  overflow-y: auto;
  padding: 20px;
  padding-bottom: 24px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-bottom: 8px;
}
.title-text h1 { font-size: 20px; font-weight: 600; letter-spacing: 0.5px; }
.title-text .sub { font-size: 13px; color: var(--text-muted); margin-top: 4px; }
.title-line { flex: 1; height: 1px; background: var(--border); }

/* ===== Tab ===== */
.tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border);
}
.tab {
  position: relative;
  padding: 10px 18px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-muted);
  cursor: pointer;
  transition: color 0.12s;
  margin-right: 12px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.tab:hover { color: var(--text); }
.tab.active {
  color: var(--text);
  border-bottom: 2px solid var(--text);
  margin-bottom: -1px;
}
.count-pill {
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11.5px;
  line-height: 1.6;
  border: 1px solid var(--border);
  background: #FAFAFA;
  color: var(--text);
  font-weight: 500;
}
.count-pill.deleted {
  color: #b91c1c;
  border-color: #f5cfc0;
  background: #FFF5F0;
}

/* ===== 空态 ===== */
.empty-state {
  text-align: center;
  padding: 80px 40px;
  color: var(--text-muted);
  font-size: 13.5px;
  line-height: 1.8;
  background: #FFFFFF;
  border: 1px dashed var(--border);
  border-radius: var(--radius-card);
}
.empty-state.error { color: var(--danger); }

/* ===== 任务卡片列表 ===== */
.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.task-card {
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 14px 16px;
  transition: border-color 0.12s;
}
.task-card:hover { border-color: var(--text); }

.task-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 10px;
}
.task-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--text);
  line-height: 1.5;
  word-break: break-word;
}
.task-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  flex-shrink: 0;
  justify-content: flex-end;
}
.tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 11.5px;
  line-height: 1.6;
  border: 1px solid var(--border);
  background: #FAFAFA;
  color: var(--text-muted);
  font-weight: 500;
}
.cat-tag { color: #1e40af; background: #EFF6FF; border-color: #BFDBFE; }
.grp-tag { color: #065f46; background: #ECFDF5; border-color: #A7F3D0; }
.src-tag { color: #4b5563; background: #F3F4F6; border-color: #D1D5DB; }
.st-tag.submitted { color: #1f7a3d; background: #eef9f1; border-color: #cde7d4; }
.st-tag.done { color: #1d4ed8; background: #eff6ff; border-color: #bfdbfe; }
.st-tag.deleted { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }

.task-foot {
  padding-top: 4px;
  border-top: 1px dashed var(--border);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
}
.meta-item {
  font-size: 12.5px;
  color: var(--text-muted);
  line-height: 1.7;
}
.meta-item strong {
  color: var(--text-muted);
  font-weight: 500;
  margin-right: 2px;
}
.meta-item.highlight {
  color: #1f7a3d;
  font-weight: 500;
}
.meta-item.highlight strong { color: #1f7a3d; }
.meta-item.danger {
  color: #b91c1c;
  font-weight: 500;
}
.meta-item.danger strong { color: #b91c1c; }
</style>
