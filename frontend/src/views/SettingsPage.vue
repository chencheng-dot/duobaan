<script setup>
import { ref, onMounted, computed } from 'vue'
import {
  getLlmConfig, saveLlmConfig, getProviders,
  getWeatherConfig, saveWeatherConfig
} from '../api'

const tab = ref('llm') // 'llm' | 'weather'

// --- 大模型 ---
const providers = ref([])
const llmCfg = ref({ provider: 'CHATGPT', baseUrl: '', apiKey: '', model: '', timeoutSeconds: 30 })
const llmSaving = ref(false)
const llmSaved = ref(false)

// --- 天气 ---
const weatherCfg = ref({ provider: 'qweather', apiKey: '', location: '北京', cacheTtlSeconds: 600 })
const weatherSaving = ref(false)
const weatherSaved = ref(false)

const loading = ref(true)
const errorMsg = ref('')

async function load() {
  try {
    loading.value = true
    const [llm, provs, wth] = await Promise.all([
      getLlmConfig(), getProviders(), getWeatherConfig()
    ])
    llmCfg.value = {
      provider: llm.provider || 'CHATGPT',
      baseUrl: llm.baseUrl || '',
      apiKey: llm.apiKey || '',
      model: llm.model || '',
      timeoutSeconds: llm.timeoutSeconds || 30
    }
    providers.value = provs
    if (!llmCfg.value.baseUrl) applyPreset('CHATGPT')
    weatherCfg.value = {
      provider: wth.provider || 'qweather',
      apiKey: wth.apiKey || '',
      location: wth.location || '北京',
      cacheTtlSeconds: wth.cacheTtlSeconds || 600
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
    llmCfg.value.provider = code
    if (preset.baseUrl) llmCfg.value.baseUrl = preset.baseUrl
    if (preset.defaultModel) llmCfg.value.model = preset.defaultModel
  }
}

async function saveLlm() {
  try {
    llmSaving.value = true; llmSaved.value = false
    await saveLlmConfig(llmCfg.value)
    llmSaved.value = true
    setTimeout(() => { llmSaved.value = false }, 2500)
  } catch (e) {
    errorMsg.value = '保存失败：' + e.message
  } finally {
    llmSaving.value = false
  }
}

async function saveWeather() {
  try {
    weatherSaving.value = true; weatherSaved.value = false
    await saveWeatherConfig(weatherCfg.value)
    weatherSaved.value = true
    setTimeout(() => { weatherSaved.value = false }, 2500)
  } catch (e) {
    errorMsg.value = '保存失败：' + e.message
  } finally {
    weatherSaving.value = false
  }
}

const llmReady = computed(() => llmCfg.value.apiKey && llmCfg.value.apiKey.length > 0)
const weatherReady = computed(() => weatherCfg.value.apiKey && weatherCfg.value.apiKey.length > 0)

onMounted(load)
</script>

<template>
  <div class="settings">
    <!-- 标题 -->
    <section class="title-row">
      <div class="title-text">
        <h1>系统设置</h1>
        <p class="sub">配置大模型与天气服务 API</p>
      </div>
      <div class="title-line"></div>
    </section>

    <!-- Tab -->
    <div class="tabs">
      <div class="tab" :class="{ active: tab === 'llm' }" @click="tab = 'llm'">
        大模型
        <span class="dot" v-if="llmReady"></span>
      </div>
      <div class="tab" :class="{ active: tab === 'weather' }" @click="tab = 'weather'">
        天气服务
        <span class="dot" v-if="weatherReady"></span>
      </div>
    </div>

    <div v-if="loading" class="loading-state">加载中…</div>

    <!-- ============ 大模型 Tab ============ -->
    <template v-if="tab === 'llm' && !loading">
      <section class="config-card">
        <h2 class="card-title">选择提供商</h2>
        <div class="provider-grid">
          <div
            v-for="p in providers"
            :key="p.code"
            class="provider-card"
            :class="{ active: llmCfg.provider === p.code }"
            @click="applyPreset(p.code); llmSaved = false"
          >
            <div class="provider-name">{{ p.name }}</div>
            <div class="provider-url" v-if="p.baseUrl">{{ p.baseUrl.replace('https://', '') }}</div>
            <div class="provider-url muted" v-else>自定义</div>
          </div>
        </div>
      </section>

      <section class="config-card">
        <h2 class="card-title">连接配置</h2>
        <div class="config-item">
          <label class="lbl">API Base URL</label>
          <input v-model="llmCfg.baseUrl" placeholder="如 https://api.openai.com/v1" @input="llmSaved = false" />
          <p class="hint" v-if="llmCfg.provider && llmCfg.provider !== 'CUSTOM'">
            预设：{{ providers.find(p => p.code === llmCfg.provider)?.baseUrl }}
          </p>
        </div>
        <div class="config-item">
          <label class="lbl">API Key <span class="required">*</span></label>
          <input v-model="llmCfg.apiKey" type="text" placeholder="输入 API Key" @input="llmSaved = false" />
          <p class="hint">Key 仅保存在本地数据库</p>
        </div>
        <div class="config-item">
          <label class="lbl">模型名称</label>
          <input v-model="llmCfg.model" placeholder="如 gpt-4o-mini" @input="llmSaved = false" />
          <p class="hint" v-if="llmCfg.provider && llmCfg.provider !== 'CUSTOM'">
            推荐：{{ providers.find(p => p.code === llmCfg.provider)?.defaultModel }}
          </p>
        </div>
        <div class="config-item">
          <label class="lbl">超时时间（秒）</label>
          <input v-model.number="llmCfg.timeoutSeconds" type="number" min="5" max="120" @input="llmSaved = false" />
        </div>
      </section>

      <div class="save-area">
        <button class="save-btn" @click="saveLlm" :disabled="llmSaving">
          {{ llmSaving ? '保存中…' : '保存大模型配置' }}
        </button>
        <div v-if="llmSaved" class="save-success">配置已保存</div>
      </div>

      <section class="info-card">
        <h2 class="card-title">提供商说明</h2>
        <div class="info-list">
          <div class="info-item"><strong>ChatGPT</strong> — OpenAI 官方，platform.openai.com</div>
          <div class="info-item"><strong>DeepSeek</strong> — platform.deepseek.com</div>
          <div class="info-item"><strong>豆包</strong> — 字节火山引擎 console.volces.com/ark</div>
          <div class="info-item"><strong>千问</strong> — 阿里云 dashscope.console.aliyun.com</div>
          <div class="info-item"><strong>自定义</strong> — 任何 OpenAI 兼容端点</div>
        </div>
      </section>
    </template>

    <!-- ============ 天气 Tab ============ -->
    <template v-if="tab === 'weather' && !loading">
      <section class="config-card">
        <h2 class="card-title">和风天气</h2>
        <div class="config-item">
          <label class="lbl">API Key <span class="required">*</span></label>
          <input v-model="weatherCfg.apiKey" placeholder="访问 console.qweather.com 获取 Key" @input="weatherSaved = false" />
          <p class="hint">免费订阅即可，用于实时天气与体感温度</p>
        </div>
        <div class="config-item">
          <label class="lbl">城市名 <span class="required">*</span></label>
          <input v-model="weatherCfg.location" placeholder="如 北京、上海、成都、广州" @input="weatherSaved = false" />
          <p class="hint">支持中文城市名，系统会自动解析为 LocationID。也可直接输入数字 LocationID（如 101010100），默认 <code>北京</code></p>
        </div>
        <div class="config-item">
          <label class="lbl">缓存时长（秒）</label>
          <input v-model.number="weatherCfg.cacheTtlSeconds" type="number" min="60" max="7200" @input="weatherSaved = false" />
          <p class="hint">避免频繁调用，默认 600 秒（10 分钟）</p>
        </div>
      </section>

      <div class="save-area">
        <button class="save-btn" @click="saveWeather" :disabled="weatherSaving">
          {{ weatherSaving ? '保存中…' : '保存天气配置' }}
        </button>
        <div v-if="weatherSaved" class="save-success">配置已保存，下次刷新即生效</div>
      </div>

      <section class="info-card">
        <h2 class="card-title">使用说明</h2>
        <div class="info-list">
          <div class="info-item"><strong>1.</strong> 注册 <code>console.qweather.com</code> 账号</div>
          <div class="info-item"><strong>2.</strong> 创建项目，复制"API Key"粘贴到上方</div>
          <div class="info-item"><strong>3.</strong> 用"城市查询"接口找到你所在城市的 Location ID 填入</div>
          <div class="info-item"><strong>4.</strong> 保存后回到首页，顶部即可看到实时天气与体感温度</div>
        </div>
      </section>
    </template>

    <div v-if="errorMsg" class="save-error" style="text-align:center">{{ errorMsg }}</div>
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
  gap: 6px;
}
.tab .dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--success);
}
.tab:hover { color: var(--text); }
.tab.active {
  color: var(--text);
  border-bottom: 2px solid var(--text);
  margin-bottom: -1px;
}

.loading-state { text-align: center; padding: 60px; color: var(--text-muted); }

.config-card {
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 20px;
}
.card-title { font-size: 15px; font-weight: 600; margin-bottom: 16px; }

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}
.provider-card {
  padding: 14px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  transition: all 0.12s;
}
.provider-card:hover { border-color: var(--text); }
.provider-card.active {
  border-color: var(--text);
  box-shadow: inset 0 0 0 1px var(--text);
}
.provider-name { font-size: 14px; font-weight: 600; margin-bottom: 4px; }
.provider-url { font-size: 11px; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; }
.provider-url.muted { font-style: italic; }

.config-item { margin-bottom: 16px; }
.config-item:last-child { margin-bottom: 0; }
.lbl { display: block; font-size: 13px; font-weight: 500; margin-bottom: 8px; }
.required { color: var(--danger); }
.hint { font-size: 12px; color: var(--text-muted); margin-top: 6px; line-height: 1.6; }
.hint code {
  background: #F9FAFB;
  border: 1px solid var(--border);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
}
.hint a { color: var(--text); border-bottom: 1px solid var(--border); text-decoration: none; }

.save-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
.save-btn {
  padding: 10px 36px;
  background: var(--text);
  color: #FFFFFF;
  border: 1px solid var(--text);
  border-radius: var(--radius);
  font-size: 14px;
  font-weight: 500;
  transition: opacity 0.12s;
}
.save-btn:hover:not(:disabled) { opacity: 0.85; }
.save-success { font-size: 13px; color: var(--success); }
.save-error { font-size: 13px; color: var(--danger); }

.info-card {
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 20px;
}
.info-list { display: flex; flex-direction: column; gap: 8px; }
.info-item {
  padding: 10px 12px;
  background: #FAFAFA;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
}
.info-item strong { color: var(--text); font-weight: 600; }
.info-item code {
  background: #FFFFFF;
  border: 1px solid var(--border);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
}
</style>
