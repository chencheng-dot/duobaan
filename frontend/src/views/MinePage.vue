<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import {
  getMineHistory,
  hardDeleteTask,
  listKbs,
  createKb,
  deleteKb,
  addKbDocument,
  uploadKbDocument,
  listKbChunks,
  clearKbChunks,
  revectorizeKb,
  getKnowledgeStatus
} from '../api'

const tab = ref('submitted') // 'submitted' | 'done' | 'deleted' | 'knowledge'
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

// 原先在模板内联 [...currentList].sort(...)：每次渲染都会重建数组并排序，排序逻辑也埋在模板里。
// 收敛为 computed，只在 currentList / tab 变化时重算一次。
const sortedList = computed(() =>
  [...currentList.value].sort((a, b) => new Date(taskSortKey(b)) - new Date(taskSortKey(a)))
)

onMounted(() => {
  load()
  loadKbData()
})

// 「我的 → 已删除」中的硬删除：物理移除，不可恢复
async function hardDelete(t) {
  if (!confirm(`确认彻底删除「${t.title}」？此操作不可恢复。`)) return
  try {
    await hardDeleteTask(t.id)
    data.value.deleted = data.value.deleted.filter((x) => x.id !== t.id)
  } catch (e) {
    errorMsg.value = '删除失败：' + e.message
  }
}

// ==================== 知识库（RAG） ====================
const kbs = ref([])
const kbLoading = ref(false)
const kbError = ref('')
const kbWarn = ref('')

// 新建知识库
const newKbName = ref('')
const newKbDesc = ref('')
const newKbStrategy = ref('PARAGRAPH')
const newKbChunkSize = ref(500)
const newKbChunkOverlap = ref(50)
const kbCreating = ref(false)

// RAG 运行状态
const ragStatus = ref({ embeddingReady: false, redisOk: false, hint: '' })

// 展开的知识库 + 文档录入 / 分块
const expandedKb = ref(null)
const docText = ref('')
const docSaving = ref(false)
const chunks = ref({})
const chunkLoading = ref(false)
const kbFileInput = ref(null)
const revectorizing = ref(false)

async function loadKbs() {
  kbLoading.value = true
  try {
    kbs.value = await listKbs()
    kbError.value = ''
  } catch (e) {
    kbError.value = '加载知识库失败：' + e.message
  } finally {
    kbLoading.value = false
  }
}

async function loadKbData() {
  kbWarn.value = ''
  try {
    const [kbList, status] = await Promise.all([
      listKbs().catch(() => []),
      getKnowledgeStatus().catch(() => ({ embeddingReady: false, redisOk: false, hint: '' }))
    ])
    kbs.value = kbList
    ragStatus.value = status
    if (!status.embeddingReady || !status.redisOk) {
      kbWarn.value = status.hint
    }
  } catch (e) { /* ignore */ }
}

watch(tab, (v) => {
  if (v === 'knowledge') loadKbData()
})

const STRATEGY_LABELS = { PARAGRAPH: '按段落', SENTENCE: '按句子', MARKDOWN: 'Markdown标题', SIMPLE: '固定长度' }
function strategyLabel(s) { return STRATEGY_LABELS[s] || s || '按段落' }

async function createKnowledgeBase() {
  const name = newKbName.value.trim()
  if (!name || kbCreating.value) return
  const chunkSize = Number(newKbChunkSize.value) || 500
  const chunkOverlap = Number(newKbChunkOverlap.value) || 50
  // 约束：重叠必须严格小于分块大小 —— 否则分块会原地打转/产生无效分块（原先前端无任何校验）
  if (chunkOverlap >= chunkSize) {
    kbError.value = `分块重叠（${chunkOverlap}）必须小于分块大小（${chunkSize}），请调整后再创建。`
    return
  }
  kbCreating.value = true
  try {
    await createKb({
      name,
      description: newKbDesc.value.trim(),
      splitStrategy: newKbStrategy.value,
      chunkSize,
      chunkOverlap
    })
    newKbName.value = ''
    newKbDesc.value = ''
    newKbStrategy.value = 'PARAGRAPH'
    newKbChunkSize.value = 500
    newKbChunkOverlap.value = 50
    await loadKbs()
  } catch (e) {
    kbError.value = '创建失败：' + e.message
  } finally {
    kbCreating.value = false
  }
}

async function removeKb(kb) {
  if (!confirm(`确认删除知识库「${kb.name}」及其全部分块？`)) return
  try {
    await deleteKb(kb.id)
    expandedKb.value = null
    await loadKbs()
  } catch (e) {
    kbError.value = '删除失败：' + e.message
  }
}

async function loadChunks(kbId) {
  chunkLoading.value = true
  try {
    chunks.value[kbId] = await listKbChunks(kbId)
  } catch (e) {
    chunks.value[kbId] = []
  } finally {
    chunkLoading.value = false
  }
}

async function toggleExpand(kb) {
  if (expandedKb.value === kb.id) {
    expandedKb.value = null
    return
  }
  expandedKb.value = kb.id
  docText.value = ''
  await loadChunks(kb.id)
}

async function addDocumentText(kb) {
  const text = docText.value.trim()
  if (!text || docSaving.value) return
  docSaving.value = true
  try {
    const res = await addKbDocument(kb.id, text)
    if (res.chunkCount) docText.value = ''
    if (!res.embeddingReady) {
      kbWarn.value = '分块已入库但向量化未就绪，检索暂不可用：请到「设置 → 向量化模型」配置独立的 embedding（Base URL + Key + 模型），并确保本地 Redis 已启动。'
    }
    await Promise.all([loadKbs(), loadChunks(kb.id)])
  } catch (e) {
    kbError.value = '入库失败：' + e.message
  } finally {
    docSaving.value = false
  }
}

async function uploadFile(kb, e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  docSaving.value = true
  try {
    const res = await uploadKbDocument(kb.id, f)
    if (res.chunkCount) docText.value = ''
    if (!res.embeddingReady) {
      kbWarn.value = '文件已入库但向量化未就绪，检索暂不可用：请激活大模型并填写 embedding 模型 ID。'
    }
    await Promise.all([loadKbs(), loadChunks(kb.id)])
  } catch (err) {
    kbError.value = '上传失败：' + err.message
  } finally {
    docSaving.value = false
    e.target.value = ''
  }
}

async function clearChunks(kb) {
  if (!confirm(`确认清空知识库「${kb.name}」的全部分块？`)) return
  try {
    await clearKbChunks(kb.id)
    await Promise.all([loadKbs(), loadChunks(kb.id)])
  } catch (e) {
    kbError.value = '清空失败：' + e.message
  }
}

async function doRevectorize(kb) {
  if (!kb.chunkCount) { kbError.value = '该知识库还没有分块'; return }
  if (!ragStatus.value.embeddingReady) {
    kbError.value = '请先在「设置 → 向量化模型」配置 embedding，再执行重新向量化。'
    return
  }
  revectorizing.value = true
  try {
    const n = await revectorizeKb(kb.id)
    kbWarn.value = `已对「${kb.name}」的 ${n} 个分块重新向量化，现在可以在对话中检索到了。`
    await loadKbs()
  } catch (e) {
    kbError.value = '向量化失败：' + e.message
  } finally {
    revectorizing.value = false
  }
}
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

    <!-- Tab 切换 -->
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
      <div class="tab" :class="{ active: tab === 'knowledge' }" @click="tab = 'knowledge'">
        知识库
        <span class="count-pill">{{ kbs.length }}</span>
      </div>
    </div>

    <!-- ==================== 知识库（RAG） ==================== -->
    <div v-if="tab === 'knowledge'" class="kb-panel">
      <div v-if="kbWarn" class="kb-warn">{{ kbWarn }}</div>

      <!-- 新建知识库 -->
      <div class="kb-card">
        <div class="kb-section-head">
          <span class="kb-section-title">新建知识库</span>
        </div>
        <div class="kb-row">
          <input v-model="newKbName" class="kb-input" placeholder="知识库名称，如：公司制度" />
          <input v-model="newKbDesc" class="kb-input" placeholder="描述（可选）" />
        </div>
        <div class="kb-row" style="margin-top:8px">
          <div class="kb-field">
            <label class="kb-field-lbl">分块策略</label>
            <select v-model="newKbStrategy" class="kb-select">
              <option value="PARAGRAPH">按段落（默认）</option>
              <option value="SENTENCE">按句子</option>
              <option value="MARKDOWN">按 Markdown 标题</option>
              <option value="SIMPLE">固定长度</option>
            </select>
          </div>
          <div class="kb-field">
            <label class="kb-field-lbl">分块长度（字）</label>
            <input v-model.number="newKbChunkSize" type="number" min="50" max="5000" class="kb-input" />
          </div>
          <div class="kb-field">
            <label class="kb-field-lbl">重叠（字）</label>
            <input v-model.number="newKbChunkOverlap" type="number" min="0" max="1000" class="kb-input" />
          </div>
          <button class="kb-btn" @click="createKnowledgeBase" :disabled="kbCreating || !newKbName.trim()" style="align-self:flex-end">
            {{ kbCreating ? '创建中…' : '创建' }}
          </button>
        </div>
        <div class="kb-hint muted">
          策略说明：按段落=保留段落语义聚合；按句子=按句末标点切分；Markdown=按 # 标题切分；固定长度=硬切+重叠。
        </div>
      </div>

      <!-- 知识库列表 -->
      <div v-if="kbLoading" class="empty-state">加载知识库…</div>
      <div v-else-if="kbError" class="empty-state error">{{ kbError }}</div>
      <div v-else-if="!kbs.length" class="empty-state">
        还没有知识库。新建一个知识库，再粘贴文本或上传 .txt/.md 文件入库，即可在对话中优先检索。
      </div>
      <div v-else class="kb-list">
        <div v-for="kb in kbs" :key="kb.id" class="kb-card">
          <div class="kb-card-head" @click="toggleExpand(kb)">
            <div class="kb-card-title">
              <span class="kb-name">{{ kb.name }}</span>
              <span v-if="kb.description" class="kb-desc muted">{{ kb.description }}</span>
              <span class="kb-config muted">
                {{ strategyLabel(kb.splitStrategy) }} · {{ kb.chunkSize }}字 · 重叠{{ kb.chunkOverlap }}字
              </span>
            </div>
            <div class="kb-card-actions">
              <span class="count-pill">{{ kb.chunkCount }} 分块</span>
              <span class="kb-chevron">{{ expandedKb === kb.id ? '▲' : '▼' }}</span>
            </div>
          </div>

          <div v-if="expandedKb === kb.id" class="kb-card-body">
            <textarea v-model="docText" class="kb-doc" rows="5" placeholder="粘贴要入库的文本…"></textarea>
            <div class="kb-doc-actions">
              <button class="kb-btn" @click="addDocumentText(kb)" :disabled="docSaving || !docText.trim()">
                {{ docSaving ? '入库中…' : '粘贴入库' }}
              </button>
              <button class="kb-btn ghost" @click="kbFileInput && kbFileInput.click()">上传 .txt/.md</button>
              <input
                type="file"
                ref="kbFileInput"
                accept=".txt,.md,.markdown,text/plain,text/markdown"
                style="display:none"
                @change="(e) => uploadFile(kb, e)"
              />
            </div>
            <div class="kb-hint muted">
              分块策略：{{ strategyLabel(kb.splitStrategy) }} · 每块约 {{ kb.chunkSize }} 字 · 重叠 {{ kb.chunkOverlap }} 字。
              文本入库后自动向量化并写入 Redis，对话时自动检索。
            </div>

            <div class="kb-chunks">
              <div class="kb-chunks-head">
                <span>已入库分块（{{ chunks[kb.id] ? chunks[kb.id].length : '…' }}）</span>
                <div class="kb-chunk-actions">
                  <button
                    v-if="chunks[kb.id] && chunks[kb.id].length"
                    class="kb-btn"
                    :disabled="revectorizing"
                    @click="doRevectorize(kb)"
                  >{{ revectorizing ? '向量化中…' : '重新向量化' }}</button>
                  <button
                    v-if="chunks[kb.id] && chunks[kb.id].length"
                    class="kb-btn danger-ghost"
                    @click="clearChunks(kb)"
                  >清空</button>
                </div>
              </div>
              <div v-if="chunkLoading" class="muted" style="font-size:12px">加载中…</div>
              <div v-else-if="chunks[kb.id] && chunks[kb.id].length" class="kb-chunk-list">
                <div v-for="c in chunks[kb.id]" :key="c.id" class="kb-chunk-item">
                  <span class="kb-chunk-idx">#{{ c.chunkIndex + 1 }}</span>
                  <span class="kb-chunk-text">{{ c.content }}</span>
                </div>
              </div>
              <div v-else class="muted" style="font-size:12px">暂无分块</div>
            </div>

            <button class="kb-btn danger-ghost kb-del" @click="removeKb(kb)">删除知识库</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ==================== 任务留痕 ==================== -->
    <div v-else>
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
            v-for="t in sortedList"
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
                <span v-if="tab === 'submitted'" class="tag st-tag submitted">已上交</span>
                <span v-else-if="tab === 'done'" class="tag st-tag done">已完成</span>
                <span v-else class="tag st-tag deleted">已删除</span>
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
              <button
                v-if="tab === 'deleted'"
                class="hard-del-btn"
                @click="hardDelete(t)"
                title="彻底删除，不可恢复"
              >彻底删除</button>
            </div>
          </div>
        </div>
      </template>
    </div>
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
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

/* ===== 知识库（RAG） ===== */
.kb-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.kb-warn {
  padding: 10px 14px;
  font-size: 13px;
  color: #92400e;
  background: #FFFBEB;
  border: 1px solid #FDE68A;
  border-radius: var(--radius-card);
  word-break: break-word;
}
.kb-card {
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 14px 16px;
}
.kb-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.kb-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.kb-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.kb-input {
  flex: 1;
  min-width: 160px;
  padding: 8px 12px;
  font-size: 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: #FFFFFF;
  color: var(--text);
}
.kb-input:focus { outline: none; border-color: var(--text); }
.kb-btn {
  padding: 7px 14px;
  font-size: 13px;
  background: var(--text);
  color: #FFFFFF;
  border: 1px solid var(--text);
  border-radius: var(--radius);
  cursor: pointer;
  white-space: nowrap;
}
.kb-btn:hover:not(:disabled) { opacity: 0.85; }
.kb-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.kb-btn.ghost {
  background: #FFFFFF;
  color: var(--text);
  border: 1px solid var(--text);
}
.kb-btn.danger-ghost {
  background: #FFFFFF;
  color: #b91c1c;
  border: 1px solid #fecaca;
}
.kb-btn.danger-ghost:hover:not(:disabled) { background: #fef2f2; }
.kb-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
}
.muted { color: var(--text-muted); }

/* 分块配置字段 */
.kb-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 110px;
}
.kb-field-lbl {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 500;
}
.kb-select {
  padding: 8px 10px;
  font-size: 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: #FFFFFF;
  color: var(--text);
  cursor: pointer;
}
.kb-select:focus { outline: none; border-color: var(--text); }
.kb-config {
  font-size: 11.5px;
  margin-top: 2px;
}

/* 知识库列表卡片 */
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kb-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  user-select: none;
}
.kb-card-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.kb-name { font-size: 15px; font-weight: 600; color: var(--text); }
.kb-desc { font-size: 12.5px; }
.kb-card-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.kb-chevron { font-size: 11px; color: var(--text-muted); }
.kb-card-body {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kb-doc {
  width: 100%;
  resize: vertical;
  padding: 10px 12px;
  font-size: 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--text);
  line-height: 1.6;
}
.kb-doc:focus { outline: none; border-color: var(--text); }
.kb-doc-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

/* 分块预览 */
.kb-chunks {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 10px 12px;
  background: #FAFAFA;
}
.kb-chunks-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--text);
  font-weight: 600;
  margin-bottom: 8px;
}
.kb-chunk-actions {
  display: flex;
  gap: 8px;
}
.kb-chunk-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 260px;
  overflow-y: auto;
}
.kb-chunk-item {
  display: flex;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  border-bottom: 1px dashed var(--border);
  padding-bottom: 6px;
}
.kb-chunk-idx {
  flex-shrink: 0;
  color: var(--text-muted);
  font-weight: 600;
}
.kb-chunk-text {
  color: var(--text);
  word-break: break-word;
  white-space: pre-wrap;
}
.kb-del { align-self: flex-start; }

.hard-del-btn {
  flex-shrink: 0;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: var(--radius);
  border: 1px solid var(--danger);
  color: var(--danger);
  background: transparent;
  cursor: pointer;
}
.hard-del-btn:hover {
  background: var(--danger);
  color: #fff;
}
</style>