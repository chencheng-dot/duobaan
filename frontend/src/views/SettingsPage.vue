<script setup>
import { ref, onMounted, computed } from 'vue'
import { getLlmConfig, saveLlmConfig, getProviders } from '../api'

const providers = ref([])
const config = ref({
  provider: 'DEEPSEEK',
  baseUrl: '',
  apiKey: '',
  model: '',
  timeoutSeconds: 30
})

const loading = ref(true)
const saving = ref(false)
const saved = ref(false)
const errorMsg = ref('')

async function loadConfig() {
  try {
    loading.value = true
    const [cfg, provs] = await Promise.all([getLlmConfig(), getProviders()])
    config.value = {
      provider: cfg.provider || 'CUSTOM',
      baseUrl: cfg.baseUrl || '',
      apiKey: cfg.apiKey || '',
      model: cfg.model || '',
      timeoutSeconds: cfg.timeoutSeconds || 30
    }
    providers.value = provs
    // 如果是 CUSTOM 但没有值，自动选一个预设
    if (!config.value.baseUrl) {
      applyPreset('DEEPSEEK')
    }
  } catch (e) {
    errorMsg.value = '加载配置失败：' + e.message
  } finally {
    loading.value = false
  }
}

function applyPreset(code) {
  const preset = providers.value.find(p => p.code === code)
  if (preset) {
    config.value.provider = code
    if (preset.baseUrl) config.value.baseUrl = preset.baseUrl
    if (preset.defaultModel) config.value.model = preset.defaultModel
  }
}

function onProviderChange(e) {
  const code = e.target.value
  applyPreset(code)
  saved.value = false
}

async function save() {
  try {
    saving.value = true
    saved.value = false
    errorMsg.value = ''
    await saveLlmConfig({
      provider: config.value.provider,
      baseUrl: config.value.baseUrl,
      apiKey: config.value.apiKey,
      model: config.value.model,
      timeoutSeconds: config.value.timeoutSeconds
    })
    saved.value = true
    setTimeout(() => { saved.value = false }, 3000)
  } catch (e) {
    errorMsg.value = '保存失败：' + e.message
  } finally {
    saving.value = false
  }
}

const isConfigured = computed(() => config.value.apiKey && config.value.apiKey.length > 0)

onMounted(loadConfig)
</script>

<template>
  <div class="settings">
    <!-- 页面头部 -->
    <section class="page-header">
      <div class="header-icon">⚙️</div>
      <div>
        <h1>大模型配置</h1>
        <p class="subtitle">选择你偏好的 AI 提供商，输入 API Key，保存后即可在对话和拆单中使用</p>
      </div>
      <div class="status-badge" :class="{ ready: isConfigured }">
        {{ isConfigured ? '✓ 已配置' : '○ 未配置' }}
      </div>
    </section>

    <div v-if="loading" class="loading-state">⏳ 加载中…</div>

    <template v-else>
      <!-- 选择提供商 -->
      <section class="config-card">
        <h2 class="card-title">🔌 选择 AI 提供商</h2>
        <div class="provider-grid">
          <div
            v-for="p in providers"
            :key="p.code"
            class="provider-card"
            :class="{ active: config.provider === p.code }"
            @click="applyPreset(p.code)"
          >
            <div class="provider-name">{{ p.name }}</div>
            <div class="provider-url" v-if="p.baseUrl">{{ p.baseUrl.replace('https://', '') }}</div>
            <div class="provider-url muted" v-else>自定义</div>
            <div class="provider-check" v-if="config.provider === p.code">✓</div>
          </div>
        </div>
      </section>

      <!-- 详细配置 -->
      <section class="config-card">
        <h2 class="card-title">🔧 连接配置</h2>

        <div class="config-item">
          <label class="lbl">API Base URL</label>
          <input
            v-model="config.baseUrl"
            placeholder="如 https://api.deepseek.com/v1"
            @input="saved = false"
          />
          <p class="hint" v-if="config.provider && config.provider !== 'CUSTOM'">
            {{ providers.find(p => p.code === config.provider)?.baseUrl }}（预设值，可修改）
          </p>
        </div>

        <div class="config-item">
          <label class="lbl">API Key <span class="required">*</span></label>
          <div class="key-input">
            <input
              v-model="config.apiKey"
              :type="saved ? 'password' : 'text'"
              placeholder="输入你的 API Key"
              @input="saved = false"
            />
            <button class="toggle-key" @click="saved = !saved" v-if="config.apiKey">
              {{ saved ? '👁️' : '👁️‍🗨️' }}
            </button>
          </div>
          <p class="hint">Key 仅保存在本地数据库，不会上传到任何第三方</p>
        </div>

        <div class="config-item">
          <label class="lbl">模型名称</label>
          <input
            v-model="config.model"
            placeholder="如 deepseek-chat"
            @input="saved = false"
          />
          <p class="hint" v-if="config.provider && config.provider !== 'CUSTOM'">
            预设推荐：{{ providers.find(p => p.code === config.provider)?.defaultModel }}
          </p>
        </div>

        <div class="config-item">
          <label class="lbl">超时时间（秒）</label>
          <input
            v-model.number="config.timeoutSeconds"
            type="number"
            min="5"
            max="120"
            @input="saved = false"
          />
        </div>
      </section>

      <!-- 保存按钮 -->
      <div class="save-area">
        <button class="save-btn" @click="save" :disabled="saving">
          <span v-if="!saving">💾 保存配置</span>
          <span v-else>⏳ 保存中…</span>
        </button>
        <div v-if="saved" class="save-success">✅ 配置已保存，立即可用</div>
        <div v-if="errorMsg" class="save-error">❌ {{ errorMsg }}</div>
      </div>

      <!-- 提供商说明 -->
      <section class="info-card">
        <h2 class="card-title">📖 提供商说明</h2>
        <div class="info-list">
          <div class="info-item">
            <strong>ChatGPT</strong> — 官方 OpenAI 接口，访问 platform.openai.com 获取 Key
          </div>
          <div class="info-item">
            <strong>DeepSeek</strong> — 深度求索，性价比高，访问 platform.deepseek.com
          </div>
          <div class="info-item">
            <strong>豆包</strong> — 字节跳动火山引擎，访问 console.volces.com/ark
          </div>
          <div class="info-item">
            <strong>千问</strong> — 阿里巴巴阿里云，访问 dashscope.console.aliyun.com
          </div>
          <div class="info-item">
            <strong>自定义</strong> — 支持任何 OpenAI 兼容协议的 API 端点
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 720px;
  margin: 0 auto;
  height: 100%;
  overflow-y: auto;
  padding-bottom: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px;
  background: linear-gradient(135deg, #4B3FE3 0%, #764ba2 100%);
  border-radius: 16px;
  color: white;
  box-shadow: 0 8px 32px rgba(75, 63, 227, 0.3);
}
.header-icon { font-size: 36px; }
.page-header h1 { font-size: 22px; font-weight: 700; }
.subtitle { font-size: 13px; opacity: 0.85; margin-top: 4px; }
.status-badge {
  margin-left: auto;
  padding: 6px 14px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
}
.status-badge.ready {
  background: rgba(39, 210, 191, 0.4);
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.loading-state {
  text-align: center;
  padding: 60px;
  color: var(--text-muted);
  font-size: 16px;
}

.config-card {
  background: var(--surface);
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
  color: var(--text);
}

/* 提供商选择 */
.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}
.provider-card {
  position: relative;
  padding: 16px 14px;
  border: 2px solid var(--border);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
  background: var(--surface);
}
.provider-card:hover {
  border-color: var(--brand);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(75, 63, 227, 0.1);
}
.provider-card.active {
  border-color: var(--brand);
  background: var(--brand-soft);
  box-shadow: 0 4px 16px rgba(75, 63, 227, 0.2);
}
.provider-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: 4px;
}
.provider-url {
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.provider-url.muted { color: var(--text-muted); font-style: italic; }
.provider-check {
  position: absolute;
  top: 8px;
  right: 10px;
  width: 20px;
  height: 20px;
  background: var(--brand);
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}

/* 表单项 */
.config-item { margin-bottom: 16px; }
.config-item:last-child { margin-bottom: 0; }
.lbl {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  margin-bottom: 8px;
}
.required { color: var(--danger); }
.key-input { position: relative; display: flex; gap: 8px; }
.key-input input { flex: 1; }
.toggle-key {
  padding: 8px 12px;
  background: var(--surface-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  font-size: 16px;
}
.toggle-key:hover { background: var(--brand-soft); }
.hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 6px;
}

/* 保存区域 */
.save-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
}
.save-btn {
  padding: 12px 40px;
  background: linear-gradient(135deg, #4B3FE3, #764ba2);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  box-shadow: 0 4px 16px rgba(75, 63, 227, 0.35);
  transition: all 0.2s;
}
.save-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(75, 63, 227, 0.45);
}
.save-success {
  font-size: 13px;
  color: var(--success);
  font-weight: 500;
}
.save-error {
  font-size: 13px;
  color: var(--danger);
  font-weight: 500;
}

/* 信息卡片 */
.info-card {
  background: var(--surface);
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}
.info-list { display: flex; flex-direction: column; gap: 10px; }
.info-item {
  padding: 10px 12px;
  background: var(--surface-muted);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
}
.info-item strong { color: var(--text); }
</style>
