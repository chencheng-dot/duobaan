<script setup>
import { ref, onMounted, computed, reactive, watch } from 'vue'
import {
  getLlmConfig, saveLlmConfig, getAllProviders,
  getWeatherConfig, saveWeatherConfig,
  listApiProfiles, createApiProfile, updateApiProfile, activateApiProfile, deleteApiProfile
} from '../api'

// ====================== 5 个 Tab 元数据（数据驱动 → 通用表单/保存/激活/删除）======================
const TAB_DEFS = [
  { key: 'text',    type: 'LLM',     label: '文本模型',   symbol: '💬', nameHint: '如 个人对话-4o' },
  { key: 'image',   type: 'IMAGE',   label: '图片模型',   symbol: '🎨', nameHint: '如 DALL·E 3 官方' },
  { key: 'audio',   type: 'AUDIO',   label: '语音模型',   symbol: '🎵', nameHint: '如 TTS+Whisper' },
  { key: 'video',   type: 'VIDEO',   label: '视频模型',   symbol: '🎬', nameHint: '如 Seedance 官方' },
  { key: 'weather', type: 'WEATHER', label: '天气服务',   symbol: '⛅', nameHint: '如 默认北京' }
]
const tab = ref('text')
const activeTab = computed(() => TAB_DEFS.find(t => t.key === tab.value))

// providersAll = { LLM: [...], IMAGE: [...], AUDIO: [...], VIDEO: [...], WEATHER: [...] }
const providersAll = ref({})

// 每个普通 Tab（前 4 个 = 非天气）一份响应式状态
function makeGenericState() {
  return reactive({
    form: { name: '', providerCode: '', baseUrl: '', apiKey: '', model: '', timeoutSeconds: 30 },
    saving: false, saved: false, editingId: null, profiles: []
  })
}
const g = reactive({
  text:  makeGenericState(),
  image: makeGenericState(),
  audio: makeGenericState({ timeoutSeconds: 180 }),   // 视频型态默认给 180s，但 audio 这里覆盖 30
  video: makeGenericState()
})
// audio/viedo 默认 timeout 修正
g.audio.form.timeoutSeconds = 30
g.video.form.timeoutSeconds = 180

// 天气独立（字段不一样：apiHost / location / cacheTtl）
const weather = reactive({
  form: { name: '', provider: 'qweather', apiHost: '', apiKey: '', location: '北京', cacheTtlSeconds: 600 },
  saving: false, saved: false, editingId: null, profiles: []
})

const loading = ref(true)
const errorMsg = ref('')
const delConfirm = ref({ open: false, type: null, id: null, name: '' })

// 提供给模板：当前"普通 Tab"状态（如果当前是天气 Tab 则为 null）
const currentGeneric = computed(() => {
  if (tab.value === 'weather') return null
  return g[tab.value]
})
const currentType = computed(() => activeTab.value?.type)
const currentProviders = computed(() => providersAll.value[currentType.value] || [])

// ====================== 初始化加载 ======================
async function loadAll() {
  try {
    loading.value = true
    // 并行：拉所有 providersAll + 5 种 profiles + 旧 config（回填到天气/文本）
    const [allProv, llmCfgOld, wthCfgOld,
      textList, imgList, audList, vidList, wthList] = await Promise.all([
        getAllProviders(),
        getLlmConfig().catch(() => null),
        getWeatherConfig().catch(() => null),
        listApiProfiles('LLM').catch(() => []),
        listApiProfiles('IMAGE').catch(() => []),
        listApiProfiles('AUDIO').catch(() => []),
        listApiProfiles('VIDEO').catch(() => []),
        listApiProfiles('WEATHER').catch(() => [])
      ])
    providersAll.value = allProv || {}

    // ---- 文本默认 ----
    const textProv = (providersAll.value.LLM || [])[0]?.code || 'CHATGPT'
    Object.assign(g.text.form, {
      providerCode: llmCfgOld?.provider || textProv,
      baseUrl: llmCfgOld?.baseUrl || '',
      model: llmCfgOld?.model || '',
      timeoutSeconds: llmCfgOld?.timeoutSeconds || 30
    })
    if (!g.text.form.baseUrl) applyPresetTo('text', textProv, /*silent*/true)
    g.text.profiles = textList
    applyActiveTo('text', textList)

    // ---- 图片默认 ----
    g.image.form.timeoutSeconds = 60
    applyPresetTo('image', (providersAll.value.IMAGE || [])[0]?.code, true)
    g.image.profiles = imgList
    applyActiveTo('image', imgList)

    // ---- 语音默认 ----
    g.audio.form.timeoutSeconds = 30
    applyPresetTo('audio', (providersAll.value.AUDIO || [])[0]?.code, true)
    g.audio.profiles = audList
    applyActiveTo('audio', audList)

    // ---- 视频默认 ----
    g.video.form.timeoutSeconds = 180
    applyPresetTo('video', (providersAll.value.VIDEO || [])[0]?.code, true)
    g.video.profiles = vidList
    applyActiveTo('video', vidList)

    // ---- 天气 ----
    if (wthCfgOld) {
      Object.assign(weather.form, {
        provider: wthCfgOld.provider || 'qweather',
        apiHost: wthCfgOld.apiHost || '',
        location: wthCfgOld.location || '北京',
        cacheTtlSeconds: wthCfgOld.cacheTtlSeconds || 600
      })
    }
    weather.profiles = wthList
    applyActiveWeather(wthList)
  } catch (e) {
    errorMsg.value = '加载配置失败：' + e.message
  } finally {
    loading.value = false
  }
}

function applyActiveTo(key, list) {
  const p = list.find(q => q.isActive)
  if (!p) return
  const s = g[key]
  s.editingId = null
  s.form.name = p.name || ''
  s.form.providerCode = p.provider || ''
  s.form.baseUrl = p.baseUrl || ''
  s.form.apiKey = ''  // 打码，不给明文
  s.form.model = p.model || ''
  s.form.timeoutSeconds = p.timeoutSeconds || 30
}
function applyActiveWeather(list) {
  const p = list.find(q => q.isActive)
  if (!p) return
  weather.editingId = null
  weather.form.name = p.name || ''
  weather.form.provider = p.provider || 'qweather'
  weather.form.apiHost = p.baseUrl || ''
  weather.form.apiKey = ''
  weather.form.location = p.location || '北京'
  weather.form.cacheTtlSeconds = p.cacheTtlSeconds || 600
}

function applyPresetTo(key, code, silent = false) {
  const type = TAB_DEFS.find(t => t.key === key)?.type
  const presets = providersAll.value[type] || []
  const preset = presets.find(p => p.code === code)
  const s = g[key]
  s.form.providerCode = code
  if (preset?.baseUrl) s.form.baseUrl = preset.baseUrl
  if (preset?.defaultModel) s.form.model = preset.defaultModel
  if (!silent) s.saved = false
}

// ====================== 通用保存/激活/删除（前 4 个 Tab）======================
async function saveGeneric() {
  const key = tab.value, type = currentType.value, s = currentGeneric.value
  try {
    s.saving = true; s.saved = false
    const name = (s.form.name || '').trim() || `未命名-${activeTab.value.label}`
    const payload = {
      profileType: type,
      name,
      provider: s.form.providerCode,
      baseUrl: s.form.baseUrl || '',
      model: s.form.model || '',
      apiKey: s.form.apiKey || '',
      location: null, cacheTtlSeconds: null,
      timeoutSeconds: s.form.timeoutSeconds,
      setActive: true
    }
    let created
    if (s.editingId) {
      created = await updateApiProfile(s.editingId, payload)
    } else {
      if (!payload.apiKey) throw new Error('新建配置请填写 API Key（编辑模式下留空可保留原 Key）')
      created = await createApiProfile(payload)
    }
    s.form.name = created.name
    if (!s.form.apiKey) s.form.apiKey = created.apiKeyMasked || ''
    s.profiles = await listApiProfiles(type)
    const reloaded = s.profiles.find(q => q.id === created.id)
    if (reloaded) s.editingId = reloaded.id
    s.saved = true
    setTimeout(() => { s.saved = false }, 2500)
  } catch (e) {
    errorMsg.value = '保存失败：' + e.message
  } finally {
    s.saving = false
  }
}

async function activateGeneric(id) {
  const key = tab.value, type = currentType.value, s = currentGeneric.value
  try {
    await activateApiProfile(id)
    s.profiles = await listApiProfiles(type)
    const p = s.profiles.find(q => q.id === id)
    if (p) {
      s.editingId = p.id
      s.form.name = p.name || ''
      s.form.providerCode = p.provider || s.form.providerCode
      s.form.baseUrl = p.baseUrl || ''
      s.form.model = p.model || ''
      s.form.timeoutSeconds = p.timeoutSeconds || 30
    }
    s.saved = true
    setTimeout(() => { s.saved = false }, 1800)
  } catch (e) { errorMsg.value = '激活失败：' + e.message }
}

function editGenericProfile(p) {
  const s = currentGeneric.value
  s.editingId = p.id
  s.form.name = p.name || ''
  s.form.providerCode = p.provider || ''
  s.form.baseUrl = p.baseUrl || ''
  s.form.apiKey = ''
  s.form.model = p.model || ''
  s.form.timeoutSeconds = p.timeoutSeconds || 30
}

// ====================== 天气保存/激活/编辑 ======================
async function saveWeather() {
  try {
    weather.saving = true; weather.saved = false
    const payload = {
      profileType: 'WEATHER',
      name: (weather.form.name || '').trim() || '未命名天气',
      provider: weather.form.provider || 'qweather',
      baseUrl: weather.form.apiHost || '',
      model: null,
      apiKey: weather.form.apiKey || '',
      location: weather.form.location || '北京',
      cacheTtlSeconds: weather.form.cacheTtlSeconds || 600,
      timeoutSeconds: null,
      setActive: true
    }
    let created
    if (weather.editingId) {
      created = await updateApiProfile(weather.editingId, payload)
    } else {
      if (!payload.apiKey) throw new Error('新建天气配置请填写 API Key（编辑模式下留空可保留原 Key）')
      created = await createApiProfile(payload)
    }
    weather.form.name = created.name
    if (!weather.form.apiKey) weather.form.apiKey = created.apiKeyMasked || ''
    weather.profiles = await listApiProfiles('WEATHER')
    const reloaded = weather.profiles.find(q => q.id === created.id)
    if (reloaded) weather.editingId = reloaded.id
    try { window.dispatchEvent(new CustomEvent('weather:forceRefresh')) } catch (_) {}
    weather.saved = true
    setTimeout(() => { weather.saved = false }, 2500)
  } catch (e) {
    errorMsg.value = '保存失败：' + e.message
  } finally {
    weather.saving = false
  }
}
async function activateWeather(id) {
  try {
    await activateApiProfile(id)
    weather.profiles = await listApiProfiles('WEATHER')
    const p = weather.profiles.find(q => q.id === id)
    if (p) {
      weather.editingId = p.id
      weather.form.name = p.name || ''
      weather.form.provider = p.provider || 'qweather'
      weather.form.apiHost = p.baseUrl || ''
      weather.form.location = p.location || '北京'
      weather.form.cacheTtlSeconds = p.cacheTtlSeconds || 600
    }
    try { window.dispatchEvent(new CustomEvent('weather:forceRefresh')) } catch (_) {}
    weather.saved = true
    setTimeout(() => { weather.saved = false }, 1800)
  } catch (e) { errorMsg.value = '激活失败：' + e.message }
}
function editWeatherProfile(p) {
  weather.editingId = p.id
  weather.form.name = p.name || ''
  weather.form.provider = p.provider || 'qweather'
  weather.form.apiHost = p.baseUrl || ''
  weather.form.location = p.location || '北京'
  weather.form.cacheTtlSeconds = p.cacheTtlSeconds || 600
  weather.form.apiKey = ''
}

// ====================== 删除（前 4 通用 + 天气 合并）======================
function askDelete(scope, p) {
  delConfirm.value = { open: true, type: scope, id: p.id, name: p.name }
}
function cancelDelete() { delConfirm.value = { open: false, type: null, id: null, name: '' } }
async function confirmDelete() {
  const { type, id } = delConfirm.value
  if (!id) return
  try {
    await deleteApiProfile(id)
    if (type !== 'weather') {
      const def = TAB_DEFS.find(t => t.key === type)
      g[type].profiles = await listApiProfiles(def.type)
      if (g[type].editingId === id) {
        g[type].editingId = null
        g[type].form.name = ''
      }
    } else {
      weather.profiles = await listApiProfiles('WEATHER')
      if (weather.editingId === id) {
        weather.editingId = null
        weather.form.name = ''
      }
    }
    cancelDelete()
  } catch (e) {
    errorMsg.value = '删除失败：' + e.message
  }
}

// Tab 上的"绿点"表示某一类已经有 active profile
const readyState = computed(() => ({
  text:    g.text.profiles.some(p => p.isActive),
  image:   g.image.profiles.some(p => p.isActive),
  audio:   g.audio.profiles.some(p => p.isActive),
  video:   g.video.profiles.some(p => p.isActive),
  weather: weather.profiles.some(p => p.isActive)
}))

watch(errorMsg, (v) => { if (v) setTimeout(() => { errorMsg.value = '' }, 5000) })

onMounted(loadAll)
</script>

<template>
  <div class="settings">
    <section class="title-row">
      <div class="title-text">
        <h1>系统设置</h1>
        <p class="sub">配置 4 类多模态模型（文本/图片/语音/视频）+ 天气服务</p>
      </div>
      <div class="title-line"></div>
    </section>

    <!-- 5 Tab：每个带绿点（ready=true 显示） -->
    <div class="tabs">
      <div
        v-for="t in TAB_DEFS"
        :key="t.key"
        class="tab"
        :class="{ active: tab === t.key }"
        @click="tab = t.key"
      >
        <span class="tab-symbol">{{ t.symbol }}</span>
        {{ t.label }}
        <span class="dot" v-if="readyState[t.key]"></span>
      </div>
    </div>

    <div v-if="loading" class="loading-state">加载中…</div>

    <!-- ========================================================= -->
    <!-- 通用模态 Tab（文本 / 图片 / 语音 / 视频）结构完全一致   -->
    <!-- ========================================================= -->
    <template v-if="currentGeneric && !loading">
      <section class="config-card">
        <h2 class="card-title">选择提供商</h2>
        <div class="provider-grid">
          <div
            v-for="p in currentProviders"
            :key="p.code"
            class="provider-card"
            :class="{ active: currentGeneric.form.providerCode === p.code }"
            @click="applyPresetTo(tab, p.code); currentGeneric.saved = false"
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
          <label class="lbl">配置名称</label>
          <input v-model="currentGeneric.form.name" :placeholder="`如 ${activeTab.nameHint}`" @input="currentGeneric.saved = false" />
        </div>
        <div class="config-item">
          <label class="lbl">API Base URL</label>
          <input v-model="currentGeneric.form.baseUrl" placeholder="例如 https://api.openai.com/v1" @input="currentGeneric.saved = false" />
          <p class="hint" v-if="currentProviders.find(q => q.code === currentGeneric.form.providerCode)">
            预设：{{ currentProviders.find(q => q.code === currentGeneric.form.providerCode).baseUrl || '（自定义，请填写）' }}
          </p>
        </div>
        <div class="config-item">
          <label class="lbl">API Key <span class="required">*</span></label>
          <input v-model="currentGeneric.form.apiKey" placeholder="粘贴 Key，编辑时留空表示不换 Key" @input="currentGeneric.saved = false" />
          <p class="hint">Key 仅保存在本地 MySQL；设置页 list/详情接口返回的永远是打码版；删除 = 物理行清空不留残片</p>
        </div>
        <div class="config-item">
          <label class="lbl">模型 ID</label>
          <input v-model="currentGeneric.form.model" :placeholder="activeTab.type==='IMAGE'?'如 dall-e-3':activeTab.type==='AUDIO'?'如 tts-1':activeTab.type==='VIDEO'?'如 seedance-1-0-pro':'如 gpt-4o-mini'" @input="currentGeneric.saved = false" />
          <p class="hint" v-if="currentProviders.find(q => q.code === currentGeneric.form.providerCode)?.defaultModel">
            推荐：{{ currentProviders.find(q => q.code === currentGeneric.form.providerCode).defaultModel }}
          </p>
        </div>
        <div class="config-item">
          <label class="lbl">超时（秒）</label>
          <input v-model.number="currentGeneric.form.timeoutSeconds" type="number" min="5" max="600" @input="currentGeneric.saved = false" />
          <p class="hint">图片一般 30-60 秒；视频建议 120-300 秒；文本一般 10-30 秒</p>
        </div>
      </section>

      <div class="save-area">
        <button class="save-btn" @click="saveGeneric" :disabled="currentGeneric.saving">
          {{ currentGeneric.saving ? '保存中…' : `保存${activeTab.label}并启用` }}
        </button>
        <div v-if="currentGeneric.saved" class="save-success">配置已保存并设为「使用中」</div>
      </div>

      <section class="info-card" v-if="currentGeneric.profiles.length">
        <h2 class="card-title">已保存的{{ activeTab.label }}（{{ currentGeneric.profiles.length }}）</h2>
        <div class="profile-list">
          <div
            v-for="p in currentGeneric.profiles"
            :key="p.id"
            class="profile-card"
            :class="{ active: p.isActive, editing: currentGeneric.editingId === p.id }"
          >
            <div class="profile-head">
              <div class="profile-name">
                <span class="pname">{{ p.name }}</span>
                <span v-if="p.isActive" class="badge badge-active">使用中</span>
                <span v-if="currentGeneric.editingId === p.id" class="badge badge-edit">编辑中</span>
              </div>
              <div class="profile-actions">
                <button v-if="!p.isActive" class="mini-btn" @click="activateGeneric(p.id)">设为默认</button>
                <button class="mini-btn" @click="editGenericProfile(p)">编辑</button>
                <button class="mini-btn danger" @click="askDelete(tab, p)">删除</button>
              </div>
            </div>
            <div class="profile-body">
              <div class="profile-meta"><span class="k">提供商</span><span class="v">{{ p.provider || '—' }}</span></div>
              <div class="profile-meta"><span class="k">Base URL</span><span class="v mono">{{ p.baseUrl || '—' }}</span></div>
              <div class="profile-meta"><span class="k">模型</span><span class="v mono">{{ p.model || '—' }}</span></div>
              <div class="profile-meta"><span class="k">Key</span><span class="v mono muted">{{ p.apiKeyMasked }}</span></div>
              <div class="profile-meta"><span class="k">超时</span><span class="v">{{ p.timeoutSeconds ? p.timeoutSeconds + ' 秒' : '—' }}</span></div>
              <div class="profile-meta"><span class="k">更新</span><span class="v muted">{{ p.updatedAt }}</span></div>
            </div>
          </div>
        </div>
      </section>

      <section class="info-card">
        <h2 class="card-title">{{ activeTab.label }}说明</h2>
        <template v-if="tab==='text'">
          <div class="info-list">
            <div class="info-item"><strong>ChatGPT</strong> — platform.openai.com，推荐 gpt-4o / gpt-4o-mini</div>
            <div class="info-item"><strong>DeepSeek</strong> — platform.deepseek.com，便宜且强，R1 推理</div>
            <div class="info-item"><strong>豆包</strong> — console.volces.com/ark，字节火山引擎</div>
            <div class="info-item"><strong>千问</strong> — dashscope.console.aliyun.com，阿里云</div>
            <div class="info-item"><strong>自定义</strong> — 任何 OpenAI 兼容端点（/chat/completions）</div>
          </div>
        </template>
        <template v-else-if="tab==='image'">
          <div class="info-list">
            <div class="info-item"><strong>DALL·E 3 (OpenAI)</strong> — /images/generations，支持 1024×1792 横/竖</div>
            <div class="info-item"><strong>Seedream 混元生图</strong> — 火山方舟 seedream-t2i-pro 系列</div>
            <div class="info-item"><strong>万相</strong> — 阿里通义 wanx2.x 系列（走兼容式 API）</div>
            <div class="info-item"><strong>自定义</strong> — 任何 OpenAI 兼容 /images/generations 端点</div>
          </div>
        </template>
        <template v-else-if="tab==='audio'">
          <div class="info-list">
            <div class="info-item"><strong>OpenAI (TTS + Whisper)</strong> — 一套配置兼顾 TTS(/audio/speech) 与 ASR(/audio/transcriptions)。模型框填 TTS 模型（如 tts-1/tts-1-hd），ASR 后端默认自动回退 whisper-1（如果模型框填 tts 开头），也可直接写 whisper-1</div>
            <div class="info-item"><strong>火山语音 / MiniMax</strong> — 直接选预设填 Key 即可</div>
            <div class="info-item"><strong>自定义</strong> — 任何 OpenAI 兼容 /audio/speech 和 /audio/transcriptions 端点</div>
          </div>
        </template>
        <template v-else-if="tab==='video'">
          <div class="info-list">
            <div class="info-item"><strong>Seedance</strong> — 火山方舟生视频 seedance-1-0-pro 系列</div>
            <div class="info-item"><strong>可灵 Kling (快手)</strong> — api.klingai.com/v1，kling-v1</div>
            <div class="info-item"><strong>万相视频</strong> — 阿里通义 wanx2.x-v2v-turbo</div>
            <div class="info-item"><strong>自定义</strong> — 任何 OpenAI 兼容 /videos/generations 端点。多数视频厂商是异步"提交+轮询"，首版支持同步直接返回 URL，或 status=pending 时二次调用</div>
          </div>
        </template>
      </section>
    </template>

    <!-- ========================================================= -->
    <!-- 天气 Tab（保留原有详细说明和风控制台 3 个 API 勾选清单）  -->
    <!-- ========================================================= -->
    <template v-if="tab==='weather' && !loading">
      <section class="config-card">
        <h2 class="card-title">和风天气</h2>
        <div class="config-item">
          <label class="lbl">配置名称</label>
          <input v-model="weather.form.name" placeholder="如 默认北京" @input="weather.saved = false" />
        </div>
        <div class="config-item">
          <label class="lbl">API Host <span class="required">*</span></label>
          <input v-model="weather.form.apiHost" placeholder="例如：abc123xyz.def.qweatherapi.com（不带 https:// 和路径）" @input="weather.saved = false" />
          <p class="hint">
            <strong>和风自 2026 年起停用了旧公共域名（geoapi/devapi/api.qweather.com，全部返回 404）</strong>。
            请在 <code>console.qweather.com → 设置 → API Host</code> 页面复制你的<strong>个人专属 Host</strong>（形如
            <code>xxx.def.qweatherapi.com</code>），粘贴到此。
          </p>
        </div>
        <div class="config-item">
          <label class="lbl">API Key <span class="required">*</span></label>
          <input v-model="weather.form.apiKey" placeholder="访问 console.qweather.com 控制台创建凭据后粘贴" @input="weather.saved = false" />
          <p class="hint">
            创建凭据时「身份认证方式」选择 <strong>API 密钥</strong>，「选择启用的 API」选择
            <strong>指定 API</strong>，按下方清单勾选 3 个即可。
          </p>
        </div>
        <div class="config-item">
          <label class="lbl">城市名 <span class="required">*</span></label>
          <input v-model="weather.form.location" placeholder="如 北京、上海、成都、广州" @input="weather.saved = false" />
          <p class="hint">支持中文城市名，系统会自动解析为 LocationID。也可直接输入数字 LocationID（如 101010100）</p>
        </div>
        <div class="config-item">
          <label class="lbl">缓存时长（秒）</label>
          <input v-model.number="weather.form.cacheTtlSeconds" type="number" min="60" max="7200" @input="weather.saved = false" />
          <p class="hint">避免频繁调用，默认 600 秒（10 分钟）</p>
        </div>
      </section>

      <section class="config-card">
        <h2 class="card-title">需要启用的 API（3 个）</h2>
        <p class="card-subtitle">
          在「和风控制台 → 项目管理 → 凭据 → 编辑 → 指定 API」里勾选下面这 3 项
        </p>
        <div class="api-list">
          <div class="api-card">
            <div class="api-head"><span class="api-name">GeoAPI</span><span class="tag tag-free">免费</span></div>
            <div class="api-desc"><strong>用途：</strong>中文城市名 → LocationID</div>
            <div class="api-path"><strong>接口：</strong><code>geoapi.qweather.com/v2/city/lookup</code></div>
          </div>
          <div class="api-card">
            <div class="api-head"><span class="api-name">天气预报</span><span class="tag tag-free">免费</span></div>
            <div class="api-desc"><strong>用途：</strong>实时天气 + 体感</div>
            <div class="api-path"><strong>接口：</strong><code>devapi.qweather.com/v7/weather/now</code></div>
          </div>
          <div class="api-card">
            <div class="api-head"><span class="api-name">天气指数</span><span class="tag tag-free">免费</span><span class="tag tag-note">限免</span></div>
            <div class="api-desc"><strong>用途：</strong>穿衣/紫外线等生活指数</div>
            <div class="api-path"><strong>接口：</strong><code>devapi.qweather.com/v7/indices/1d</code></div>
          </div>
        </div>
      </section>

      <div class="save-area">
        <button class="save-btn" @click="saveWeather" :disabled="weather.saving">
          {{ weatherSaving ? '保存中…' : '保存天气配置并启用' }}
        </button>
        <div v-if="weather.saved" class="save-success">配置已保存，下次刷新即生效</div>
      </div>

      <section class="info-card" v-if="weather.profiles.length">
        <h2 class="card-title">已保存的天气配置（{{ weather.profiles.length }}）</h2>
        <div class="profile-list">
          <div
            v-for="p in weather.profiles"
            :key="p.id"
            class="profile-card"
            :class="{ active: p.isActive, editing: weather.editingId === p.id }"
          >
            <div class="profile-head">
              <div class="profile-name">
                <span class="pname">{{ p.name }}</span>
                <span v-if="p.isActive" class="badge badge-active">使用中</span>
                <span v-if="weather.editingId === p.id" class="badge badge-edit">编辑中</span>
              </div>
              <div class="profile-actions">
                <button v-if="!p.isActive" class="mini-btn" @click="activateWeather(p.id)">设为默认</button>
                <button class="mini-btn" @click="editWeatherProfile(p)">编辑</button>
                <button class="mini-btn danger" @click="askDelete('weather', p)">删除</button>
              </div>
            </div>
            <div class="profile-body">
              <div class="profile-meta"><span class="k">API Host</span><span class="v mono">{{ p.baseUrl || '—' }}</span></div>
              <div class="profile-meta"><span class="k">Key</span><span class="v mono muted">{{ p.apiKeyMasked }}</span></div>
              <div class="profile-meta"><span class="k">城市</span><span class="v">{{ p.location || '—' }}</span></div>
              <div class="profile-meta"><span class="k">缓存</span><span class="v">{{ p.cacheTtlSeconds ? p.cacheTtlSeconds + ' 秒' : '—' }}</span></div>
              <div class="profile-meta"><span class="k">更新</span><span class="v muted">{{ p.updatedAt }}</span></div>
            </div>
          </div>
        </div>
      </section>
    </template>

    <div v-if="errorMsg" class="save-error" style="text-align:center">{{ errorMsg }}</div>

    <Teleport to="body">
      <div v-if="delConfirm.open" class="modal-mask" @click.self="cancelDelete">
        <div class="modal">
          <h3 class="modal-title">确认删除？</h3>
          <p class="modal-body">
            即将删除配置 <code>{{ delConfirm.name }}</code>。
            <br/><strong>删除后数据行将从数据库物理移除，API Key 不留残片，无法恢复。</strong>
          </p>
          <div class="modal-actions">
            <button class="btn-ghost" @click="cancelDelete">取消</button>
            <button class="btn-danger" @click="confirmDelete">确认删除</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.settings {
  display: flex; flex-direction: column; gap: 16px;
  max-width: 760px; margin: 0 auto;
  height: 100%; overflow-y: auto;
  padding: 20px; padding-bottom: 24px;
}
.title-row { display: flex; align-items: center; gap: 16px; padding-bottom: 8px; }
.title-text h1 { font-size: 20px; font-weight: 600; letter-spacing: 0.5px; }
.title-text .sub { font-size: 13px; color: var(--text-muted); margin-top: 4px; }
.title-line { flex: 1; height: 1px; background: var(--border); }

.tabs { display: flex; flex-wrap: wrap; gap: 0; border-bottom: 1px solid var(--border); }
.tab {
  position: relative; padding: 10px 16px; font-size: 14px; font-weight: 500;
  color: var(--text-muted); cursor: pointer; transition: color 0.12s;
  margin-right: 8px; display: inline-flex; align-items: center; gap: 6px;
}
.tab-symbol { font-size: 15px; }
.tab .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--success); }
.tab:hover { color: var(--text); }
.tab.active { color: var(--text); border-bottom: 2px solid var(--text); margin-bottom: -1px; }

.loading-state { text-align: center; padding: 60px; color: var(--text-muted); }

.config-card {
  background: #FFFFFF; border: 1px solid var(--border);
  border-radius: var(--radius-card); padding: 20px;
}
.card-title { font-size: 15px; font-weight: 600; margin-bottom: 16px; }

.provider-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 10px;
}
.provider-card {
  padding: 14px 12px; border: 1px solid var(--border); border-radius: var(--radius);
  cursor: pointer; transition: all 0.12s;
}
.provider-card:hover { border-color: var(--text); }
.provider-card.active { border-color: var(--text); box-shadow: inset 0 0 0 1px var(--text); }
.provider-name { font-size: 14px; font-weight: 600; margin-bottom: 4px; }
.provider-url { font-size: 11px; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; }
.provider-url.muted { font-style: italic; }

.config-item { margin-bottom: 16px; }
.config-item:last-child { margin-bottom: 0; }
.lbl { display: block; font-size: 13px; font-weight: 500; margin-bottom: 8px; }
.required { color: var(--danger); }
.hint { font-size: 12px; color: var(--text-muted); margin-top: 6px; line-height: 1.6; }
.hint code {
  background: #F9FAFB; border: 1px solid var(--border);
  padding: 1px 6px; border-radius: 4px; font-size: 11px;
}

.save-area {
  display: flex; flex-direction: column; align-items: center;
  gap: 8px; padding: 4px 0;
}
.save-btn {
  padding: 10px 36px; background: var(--text); color: #FFFFFF;
  border: 1px solid var(--text); border-radius: var(--radius);
  font-size: 14px; font-weight: 500; transition: opacity 0.12s;
}
.save-btn:hover:not(:disabled) { opacity: 0.85; }
.save-success { font-size: 13px; color: var(--success); }
.save-error { font-size: 13px; color: var(--danger); }

.info-card {
  background: #FFFFFF; border: 1px solid var(--border);
  border-radius: var(--radius-card); padding: 20px;
}
.info-list { display: flex; flex-direction: column; gap: 8px; }
.info-item {
  padding: 10px 12px; background: #FAFAFA;
  border: 1px solid var(--border); border-radius: var(--radius);
  font-size: 13px; line-height: 1.6; color: var(--text-muted);
}
.info-item strong { color: var(--text); font-weight: 600; }
.info-item code {
  background: #FFFFFF; border: 1px solid var(--border);
  padding: 1px 6px; border-radius: 4px; font-size: 11px;
}

.card-subtitle {
  font-size: 13px; color: var(--text-muted);
  margin-top: -8px; margin-bottom: 16px; line-height: 1.6;
}
.api-list { display: flex; flex-direction: column; gap: 10px; }
.api-card {
  padding: 14px 14px 12px; border: 1px solid var(--border);
  border-radius: var(--radius); background: #FAFAFA;
}
.api-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.api-name { font-size: 14px; font-weight: 600; color: var(--text); }
.tag {
  display: inline-flex; align-items: center; padding: 1px 8px;
  border-radius: 10px; font-size: 11px; line-height: 1.8; border: 1px solid var(--border);
}
.tag-free { color: #1f7a3d; border-color: #cde7d4; background: #eef9f1; }
.tag-paid { color: #9a3412; border-color: #f5cfc0; background: #fef3ee; }
.tag-note { color: #78520a; border-color: #f1dfa7; background: #fff8e4; }
.api-desc { font-size: 12.5px; color: var(--text-muted); margin-bottom: 4px; line-height: 1.6; }
.api-path { font-size: 12px; color: var(--text-muted); }
.api-path code {
  background: #FFFFFF; border: 1px solid var(--border);
  padding: 1px 6px; border-radius: 4px; font-size: 11px;
}

/* ===== 多套配置列表卡片 ===== */
.profile-list { display: flex; flex-direction: column; gap: 12px; }
.profile-card {
  border: 1px solid var(--border); border-radius: var(--radius);
  background: #FAFAFA; padding: 14px; transition: all 0.12s;
}
.profile-card.active {
  border-color: var(--success); background: #f2fbf4;
  box-shadow: inset 0 0 0 1px var(--success);
}
.profile-card.editing {
  border-color: var(--text); box-shadow: inset 0 0 0 1px var(--text);
}
.profile-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 10px; margin-bottom: 10px;
}
.profile-name { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pname { font-size: 15px; font-weight: 600; color: var(--text); }
.badge {
  display: inline-flex; align-items: center; padding: 1px 8px;
  border-radius: 10px; font-size: 11px; line-height: 1.8; border: 1px solid var(--border);
}
.badge-active { color: #1f7a3d; border-color: #cde7d4; background: #eef9f1; }
.badge-edit   { color: #555;    border-color: #ddd;    background: #f0f0f0; }
.profile-actions { display: flex; gap: 6px; flex-shrink: 0; }
.mini-btn {
  padding: 4px 10px; font-size: 12px; background: #FFFFFF;
  border: 1px solid var(--border); border-radius: 6px;
  color: var(--text); cursor: pointer; transition: all 0.12s;
}
.mini-btn:hover { border-color: var(--text); }
.mini-btn.danger { color: #b91c1c; border-color: #f5cfc0; background: #FFF5F0; }
.mini-btn.danger:hover { background: #FEE5D9; border-color: #b91c1c; }

.profile-body {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 6px 16px;
}
.profile-meta {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 12.5px; line-height: 1.8;
}
.profile-meta .k { color: var(--text-muted); flex-shrink: 0; margin-right: 8px; }
.profile-meta .v {
  color: var(--text); text-align: right;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 160px;
}
.profile-meta .v.muted { color: var(--text-muted); }
.profile-meta .v.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11.5px;
}

/* ===== Modal ===== */
.modal-mask {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.35);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.modal {
  width: 420px; max-width: 92vw; background: #FFFFFF;
  border: 1px solid var(--border); border-radius: 12px;
  padding: 22px 24px 18px; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.modal-title { font-size: 16px; font-weight: 600; margin: 0 0 10px 0; }
.modal-body {
  font-size: 13px; color: var(--text-muted); line-height: 1.7; margin: 0 0 18px 0;
}
.modal-body code {
  background: #F9FAFB; border: 1px solid var(--border);
  padding: 1px 6px; border-radius: 4px; font-size: 11.5px; color: var(--text);
}
.modal-body strong { color: #b91c1c; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; }
.btn-ghost {
  padding: 7px 16px; font-size: 13px; background: #FFFFFF;
  border: 1px solid var(--border); border-radius: 8px;
  color: var(--text); cursor: pointer; transition: all 0.12s;
}
.btn-ghost:hover { border-color: var(--text); }
.btn-danger {
  padding: 7px 16px; font-size: 13px; font-weight: 500;
  background: #b91c1c; border: 1px solid #b91c1c; border-radius: 8px;
  color: #FFFFFF; cursor: pointer; transition: opacity 0.12s;
}
.btn-danger:hover { opacity: 0.9; }
</style>
