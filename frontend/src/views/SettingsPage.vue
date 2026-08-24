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
const weatherCfg = ref({ provider: 'qweather', apiHost: '', apiKey: '', location: '北京', cacheTtlSeconds: 600 })
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
      apiHost: wth.apiHost || '',
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
          <label class="lbl">API Host <span class="required">*</span></label>
          <input v-model="weatherCfg.apiHost" placeholder="例如：abc123xyz.def.qweatherapi.com（不带 https:// 和路径）" @input="weatherSaved = false" />
          <p class="hint">
            <strong>和风自 2026 年起停用了旧公共域名（geoapi/devapi/api.qweather.com，全部返回 404）</strong>。
            请在 <code>console.qweather.com → 设置 → API Host</code> 页面复制你的<strong>个人专属 Host</strong>（形如
            <code>xxx.def.qweatherapi.com</code>），粘贴到此。
          </p>
        </div>
        <div class="config-item">
          <label class="lbl">API Key <span class="required">*</span></label>
          <input v-model="weatherCfg.apiKey" placeholder="访问 console.qweather.com 控制台创建凭据后粘贴" @input="weatherSaved = false" />
          <p class="hint">
            创建凭据时「身份认证方式」选择 <strong>API 密钥</strong>，「选择启用的 API」选择
            <strong>指定 API</strong>，按下方清单勾选 3 个即可。
          </p>
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

      <!-- 需要启用的 API 清单 -->
      <section class="config-card">
        <h2 class="card-title">需要启用的 API（3 个）</h2>
        <p class="card-subtitle">
          在「和风控制台 → 项目管理 → 凭据 → 编辑 → 指定 API」里勾选下面这 3 项（多勾选浪费额度，少勾选会调用失败）
        </p>
        <div class="api-list">
          <div class="api-card">
            <div class="api-head">
              <span class="api-name">GeoAPI</span>
              <span class="tag tag-free">免费</span>
            </div>
            <div class="api-desc"><strong>用途：</strong>中文城市名 → LocationID 解析</div>
            <div class="api-path"><strong>接口：</strong><code>geoapi.qweather.com/v2/city/lookup</code></div>
          </div>

          <div class="api-card">
            <div class="api-head">
              <span class="api-name">天气预报</span>
              <span class="tag tag-free">免费</span>
            </div>
            <div class="api-desc"><strong>用途：</strong>实时天气 + 体感温度（= 和风的"实况天气"）</div>
            <div class="api-path"><strong>接口：</strong><code>devapi.qweather.com/v7/weather/now</code></div>
          </div>

          <div class="api-card">
            <div class="api-head">
              <span class="api-name">天气指数</span>
              <span class="tag tag-free">免费</span><span class="tag tag-note">免费额度有限</span>
            </div>
            <div class="api-desc"><strong>用途：</strong>保留能力（穿衣/紫外线等生活指数，用于后续美食推荐与提示）</div>
            <div class="api-path"><strong>接口：</strong><code>devapi.qweather.com/v7/indices/1d</code></div>
          </div>
        </div>
        <p class="api-footnote">
          其余 API（分钟降水、辐照、海洋、空气质量、热带气旋、时光机、天气预警、天文）为<strong>高级付费</strong>项目，本项目暂未使用，<strong>请勿勾选</strong>以节省配额。
        </p>
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
          <div class="info-item"><strong>1.</strong> 打开 <code>console.qweather.com</code> 注册 / 登录账号</div>
          <div class="info-item"><strong>2.</strong> 复制「设置 → API Host」里的<strong>个人专属 Host</strong>，粘贴到本页 API Host（形如 <code>xxx.def.qweatherapi.com</code>）</div>
          <div class="info-item"><strong>3.</strong> 新建项目 → 创建凭据（名称随便，身份认证选 <strong>API 密钥</strong>，启用的 API 选<strong>指定 API</strong>，勾选上方 3 项保存）</div>
          <div class="info-item"><strong>4.</strong> 复制凭据的 Key 粘贴到本页"API Key"，填入<strong>具体城市名</strong>（填"成都"，别填省份"四川"）保存即可</div>
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

.card-subtitle {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: -8px;
  margin-bottom: 16px;
  line-height: 1.6;
}

.api-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.api-card {
  padding: 14px 14px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: #FAFAFA;
}
.api-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.api-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 1.8;
  border: 1px solid var(--border);
}
.tag-free {
  color: #1f7a3d;
  border-color: #cde7d4;
  background: #eef9f1;
}
.tag-paid {
  color: #9a3412;
  border-color: #f5cfc0;
  background: #fef3ee;
}
.tag-note {
  color: #78520a;
  border-color: #f1dfa7;
  background: #fff8e4;
}
.api-desc { font-size: 12.5px; color: var(--text-muted); margin-bottom: 4px; line-height: 1.6; }
.api-path { font-size: 12px; color: var(--text-muted); }
.api-path code {
  background: #FFFFFF;
  border: 1px solid var(--border);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
}
.api-footnote {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-muted);
  padding: 10px 12px;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  background: #FFFFFF;
}
</style>
