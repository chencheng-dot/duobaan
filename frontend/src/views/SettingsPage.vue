<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import {
  getLlmConfig, saveLlmConfig, getProviders,
  getWeatherConfig, saveWeatherConfig,
  listApiProfiles, createApiProfile, updateApiProfile, activateApiProfile, deleteApiProfile
} from '../api'

const tab = ref('llm') // 'llm' | 'weather'

// --- 大模型 ---
const providers = ref([])
const llmCfg = ref({ name: '', provider: 'CHATGPT', baseUrl: '', apiKey: '', model: '', timeoutSeconds: 30 })
const llmSaving = ref(false)
const llmSaved = ref(false)
const llmProfiles = ref([])
const editingLlmId = ref(null)

// --- 天气 ---
const weatherCfg = ref({ name: '', provider: 'qweather', apiHost: '', apiKey: '', location: '北京', cacheTtlSeconds: 600 })
const weatherSaving = ref(false)
const weatherSaved = ref(false)
const weatherProfiles = ref([])
const editingWeatherId = ref(null)

const loading = ref(true)
const errorMsg = ref('')

// 删除确认弹窗（通用）
const delConfirm = ref({ open: false, scope: null, id: null, name: '' })

async function loadAll() {
  try {
    loading.value = true
    const [llm, provs, wth, llmList, weatherList] = await Promise.all([
      getLlmConfig(), getProviders(), getWeatherConfig(),
      listApiProfiles('LLM').catch(() => []),
      listApiProfiles('WEATHER').catch(() => [])
    ])
    llmCfg.value = {
      name: '',
      provider: llm.provider || 'CHATGPT',
      baseUrl: llm.baseUrl || '',
      // 注意：list/get 返回的 apiKey 永远是打码的或空（后端 mask），这里 GET /config/llm 返回的也是打码。所以永远不清空用户输入
      apiKey: '',
      model: llm.model || '',
      timeoutSeconds: llm.timeoutSeconds || 30
    }
    providers.value = provs
    if (!llmCfg.value.baseUrl) applyPreset('CHATGPT')
    weatherCfg.value = {
      name: '',
      provider: wth.provider || 'qweather',
      apiHost: wth.apiHost || '',
      apiKey: '',
      location: wth.location || '北京',
      cacheTtlSeconds: wth.cacheTtlSeconds || 600
    }
    llmProfiles.value = llmList
    weatherProfiles.value = weatherList
    // 若 active=1 存在，自动把 active 那条的字段回填到表单（apiKey 仍然留空占位）
    const activeLlm = llmList.find(p => p.isActive)
    if (activeLlm) applyLlmProfile(activeLlm, false)
    const activeWth = weatherList.find(p => p.isActive)
    if (activeWth) applyWeatherProfile(activeWth, false)
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
  llmSaved.value = false
  editingLlmId.value = null
}

function applyLlmProfile(p, includeMarkedActive = true) {
  editingLlmId.value = includeMarkedActive ? p.id : null
  llmCfg.value = {
    name: p.name || '',
    provider: p.provider || 'CHATGPT',
    baseUrl: p.baseUrl || '',
    apiKey: '',            // 后端永远不回传明文，改 Key 请用户手动粘贴
    model: p.model || '',
    timeoutSeconds: p.timeoutSeconds || 30
  }
  llmSaved.value = false
  // 切换预设卡片高亮
  if (!providers.value.find(q => q.code === llmCfg.value.provider)) {
    // 没匹配到预设就归为 CUSTOM（但 provider 仍保留原值）
  }
}

function applyWeatherProfile(p, includeMarkedActive = true) {
  editingWeatherId.value = includeMarkedActive ? p.id : null
  weatherCfg.value = {
    name: p.name || '',
    provider: p.provider || 'qweather',
    apiHost: p.baseUrl || '',
    apiKey: '',
    location: p.location || '北京',
    cacheTtlSeconds: p.cacheTtlSeconds || 600
  }
  weatherSaved.value = false
}

// ============= LLM 保存：editingLlmId 存在则 UPDATE，否则 CREATE =============
async function saveLlm() {
  try {
    llmSaving.value = true; llmSaved.value = false
    const payload = {
      profileType: 'LLM',
      name: (llmCfg.value.name || '').trim() || '未命名大模型',
      provider: llmCfg.value.provider,
      baseUrl: llmCfg.value.baseUrl,
      model: llmCfg.value.model,
      apiKey: llmCfg.value.apiKey || '',   // 为空在后端 create 会失败，update 时表示不换
      location: null,
      cacheTtlSeconds: null,
      timeoutSeconds: llmCfg.value.timeoutSeconds,
      setActive: true   // 保存并立即设为默认（符合用户"保存了即用"的直觉）
    }
    let created
    if (editingLlmId.value) {
      created = await updateApiProfile(editingLlmId.value, payload)
    } else {
      if (!payload.apiKey) throw new Error('新建配置请填写 API Key（编辑模式下留空可保留原 Key）')
      created = await createApiProfile(payload)
    }
    llmCfg.value.name = created.name
    // 如果用户没填 Key（编辑时），把打码显示回 form 上的 Key 输入框里视觉占位
    if (!llmCfg.value.apiKey) llmCfg.value.apiKey = created.apiKeyMasked
    llmProfiles.value = await listApiProfiles('LLM')
    const reloaded = llmProfiles.value.find(q => q.id === created.id)
    if (reloaded) editingLlmId.value = reloaded.id
    // 激活成功 → Tab 上绿点闪烁
    llmSaved.value = true
    setTimeout(() => { llmSaved.value = false }, 2500)
  } catch (e) {
    errorMsg.value = '保存失败：' + e.message
  } finally {
    llmSaving.value = false
  }
}

// ============= Weather 保存 =============
async function saveWeather() {
  try {
    weatherSaving.value = true; weatherSaved.value = false
    const payload = {
      profileType: 'WEATHER',
      name: (weatherCfg.value.name || '').trim() || '未命名天气',
      provider: 'qweather',
      baseUrl: weatherCfg.value.apiHost,
      model: null,
      apiKey: weatherCfg.value.apiKey || '',
      location: weatherCfg.value.location || '北京',
      cacheTtlSeconds: weatherCfg.value.cacheTtlSeconds || 600,
      timeoutSeconds: null,
      setActive: true
    }
    let created
    if (editingWeatherId.value) {
      created = await updateApiProfile(editingWeatherId.value, payload)
    } else {
      if (!payload.apiKey) throw new Error('新建天气配置请填写 API Key（编辑模式下留空可保留原 Key）')
      created = await createApiProfile(payload)
    }
    weatherCfg.value.name = created.name
    if (!weatherCfg.value.apiKey) weatherCfg.value.apiKey = created.apiKeyMasked
    weatherProfiles.value = await listApiProfiles('WEATHER')
    const reloaded = weatherProfiles.value.find(q => q.id === created.id)
    if (reloaded) editingWeatherId.value = reloaded.id
    // 通知 TopBar 天气立即刷新
    try { window.dispatchEvent(new CustomEvent('weather:forceRefresh')) } catch (_) {}
    weatherSaved.value = true
    setTimeout(() => { weatherSaved.value = false }, 2500)
  } catch (e) {
    errorMsg.value = '保存失败：' + e.message
  } finally {
    weatherSaving.value = false
  }
}

// ============= Activate =============
async function activateLlm(id) {
  try {
    await activateApiProfile(id)
    llmProfiles.value = await listApiProfiles('LLM')
    const p = llmProfiles.value.find(q => q.id === id)
    if (p) applyLlmProfile(p, true)
    llmSaved.value = true
    setTimeout(() => { llmSaved.value = false }, 1800)
  } catch (e) { errorMsg.value = '激活失败：' + e.message }
}
async function activateWeather(id) {
  try {
    await activateApiProfile(id)
    weatherProfiles.value = await listApiProfiles('WEATHER')
    const p = weatherProfiles.value.find(q => q.id === id)
    if (p) applyWeatherProfile(p, true)
    try { window.dispatchEvent(new CustomEvent('weather:forceRefresh')) } catch (_) {}
    weatherSaved.value = true
    setTimeout(() => { weatherSaved.value = false }, 1800)
  } catch (e) { errorMsg.value = '激活失败：' + e.message }
}

// ============= Delete（含二次确认） =============
function askDelete(scope, p) {
  delConfirm.value = { open: true, scope, id: p.id, name: p.name }
}
function cancelDelete() { delConfirm.value = { open: false, scope: null, id: null, name: '' } }
async function confirmDelete() {
  const { scope, id } = delConfirm.value
  if (!id) return
  try {
    if (scope === 'llm') {
      await deleteApiProfile(id)
      llmProfiles.value = await listApiProfiles('LLM')
      // 如果删除的正在编辑 → 清空 editing & name
      if (editingLlmId.value === id) {
        editingLlmId.value = null
        llmCfg.value.name = ''
      }
    } else {
      await deleteApiProfile(id)
      weatherProfiles.value = await listApiProfiles('WEATHER')
      if (editingWeatherId.value === id) {
        editingWeatherId.value = null
        weatherCfg.value.name = ''
      }
    }
    cancelDelete()
  } catch (e) {
    errorMsg.value = '删除失败：' + e.message
  }
}

const llmReady = computed(() => llmProfiles.value.some(p => p.isActive))
const weatherReady = computed(() => weatherProfiles.value.some(p => p.isActive))

watch(errorMsg, (v) => { if (v) setTimeout(() => { errorMsg.value = '' }, 5000) })

onMounted(loadAll)
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

      <!-- 已保存的大模型配置列表 -->
      <section class="info-card" v-if="llmProfiles.length">
        <h2 class="card-title">已保存的大模型配置（{{ llmProfiles.length }}）</h2>
        <div class="profile-list">
          <div
            v-for="p in llmProfiles"
            :key="p.id"
            class="profile-card"
            :class="{ active: p.isActive, editing: editingLlmId === p.id }"
          >
            <div class="profile-head">
              <div class="profile-name">
                <span class="pname">{{ p.name }}</span>
                <span v-if="p.isActive" class="badge badge-active">使用中</span>
                <span v-if="editingLlmId === p.id" class="badge badge-edit">编辑中</span>
              </div>
              <div class="profile-actions">
                <button v-if="!p.isActive" class="mini-btn" @click="activateLlm(p.id)">设为默认</button>
                <button class="mini-btn" @click="applyLlmProfile(p, true)">编辑</button>
                <button class="mini-btn danger" @click="askDelete('llm', p)">删除</button>
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

      <!-- 已保存的天气配置列表 -->
      <section class="info-card" v-if="weatherProfiles.length">
        <h2 class="card-title">已保存的天气配置（{{ weatherProfiles.length }}）</h2>
        <div class="profile-list">
          <div
            v-for="p in weatherProfiles"
            :key="p.id"
            class="profile-card"
            :class="{ active: p.isActive, editing: editingWeatherId === p.id }"
          >
            <div class="profile-head">
              <div class="profile-name">
                <span class="pname">{{ p.name }}</span>
                <span v-if="p.isActive" class="badge badge-active">使用中</span>
                <span v-if="editingWeatherId === p.id" class="badge badge-edit">编辑中</span>
              </div>
              <div class="profile-actions">
                <button v-if="!p.isActive" class="mini-btn" @click="activateWeather(p.id)">设为默认</button>
                <button class="mini-btn" @click="applyWeatherProfile(p, true)">编辑</button>
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

    <!-- 删除确认遮罩 -->
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

/* ===== 多套配置列表卡片 ===== */
.profile-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.profile-card {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: #FAFAFA;
  padding: 14px;
  transition: all 0.12s;
}
.profile-card.active {
  border-color: var(--success);
  background: #f2fbf4;
  box-shadow: inset 0 0 0 1px var(--success);
}
.profile-card.editing {
  border-color: var(--text);
  box-shadow: inset 0 0 0 1px var(--text);
}
.profile-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.profile-name {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pname {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}
.badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 1.8;
  border: 1px solid var(--border);
}
.badge-active {
  color: #1f7a3d;
  border-color: #cde7d4;
  background: #eef9f1;
}
.badge-edit {
  color: #555;
  border-color: #ddd;
  background: #f0f0f0;
}
.profile-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.mini-btn {
  padding: 4px 10px;
  font-size: 12px;
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text);
  cursor: pointer;
  transition: all 0.12s;
}
.mini-btn:hover { border-color: var(--text); }
.mini-btn.danger {
  color: #b91c1c;
  border-color: #f5cfc0;
  background: #FFF5F0;
}
.mini-btn.danger:hover {
  background: #FEE5D9;
  border-color: #b91c1c;
}
.profile-body {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 6px 16px;
}
.profile-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12.5px;
  line-height: 1.8;
}
.profile-meta .k {
  color: var(--text-muted);
  flex-shrink: 0;
  margin-right: 8px;
}
.profile-meta .v {
  color: var(--text);
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.profile-meta .v.muted { color: var(--text-muted); }
.profile-meta .v.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11.5px;
}

/* ===== 删除确认 Modal ===== */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}
.modal {
  width: 420px;
  max-width: 92vw;
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 22px 24px 18px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.modal-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 10px 0;
}
.modal-body {
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.7;
  margin: 0 0 18px 0;
}
.modal-body code {
  background: #F9FAFB;
  border: 1px solid var(--border);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11.5px;
  color: var(--text);
}
.modal-body strong { color: #b91c1c; }
.modal-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
.btn-ghost {
  padding: 7px 16px;
  font-size: 13px;
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  cursor: pointer;
  transition: all 0.12s;
}
.btn-ghost:hover { border-color: var(--text); }
.btn-danger {
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 500;
  background: #b91c1c;
  border: 1px solid #b91c1c;
  border-radius: 8px;
  color: #FFFFFF;
  cursor: pointer;
  transition: opacity 0.12s;
}
.btn-danger:hover { opacity: 0.9; }
</style>
