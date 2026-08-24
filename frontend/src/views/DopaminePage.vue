<script setup>
import { ref, computed, onMounted } from 'vue'
import { recommendMeal, adoptMeal } from '../api'
import { usePublicData } from '../composables/usePublicData.js'

const { weather } = usePublicData()

const mood = ref('')
const taste = ref('')        // 口味：输入框值
const tastePreset = ref('')  // 口味：下拉预设值
const diningType = ref('DINE_IN')

const TASTE_PRESETS = ['酸辣', '清淡', '甜口', '咸鲜', '重口', '轻食', '热食', '凉菜', '香辣', '麻辣', '酸甜', '鲜香']
const DINING_OPTIONS = [
  { value: 'TAKEOUT', label: '外卖', icon: '🥡' },
  { value: 'DINE_IN', label: '堂吃', icon: '🍽️' },
  { value: 'COOK', label: '自己做', icon: '👨‍🍳' }
]

const loading = ref(false)
const reply = ref('')
const degraded = ref(false)
const adopted = ref(false)
const adoptMsg = ref('')
const showTasteDropdown = ref(false)

const weatherSummary = computed(() => (weather.value ? weather.value.text + ' ' + weather.value.temp + '℃' : '—'))

// 下拉选项：把预设 + 输入框值（如不在预设里）合并显示
const tasteOptions = computed(() => {
  const opts = [...TASTE_PRESETS]
  if (taste.value && !TASTE_PRESETS.includes(taste.value)) {
    opts.unshift(taste.value)
  }
  return opts
})

function selectTaste(preset) {
  taste.value = preset
  tastePreset.value = preset
  showTasteDropdown.value = false
}

async function recommend() {
  loading.value = true
  adopted.value = false
  reply.value = ''
  try {
    const tasteList = taste.value ? [taste.value] : (tastePreset.value ? [tastePreset.value] : ['不限'])
    const res = await recommendMeal({
      mood: mood.value || '平淡',
      weather: weatherSummary.value,
      tasteTags: tasteList,
      diningType: diningType.value
    })
    reply.value = res.reply
    degraded.value = res.degraded
  } catch (e) {
    reply.value = '推荐失败：' + e.message
    degraded.value = true
  } finally {
    loading.value = false
  }
}

async function adopt() {
  const title = reply.value.split('\n')[0].slice(0, 20)
  try {
    await adoptMeal(title)
    adopted.value = true
    adoptMsg.value = `已把「${title}」写入今日流程表并标记完成`
  } catch (e) {
    adoptMsg.value = '采纳失败：' + e.message
  }
}

// 点击外部关闭下拉
function handleTasteBlur() {
  setTimeout(() => { showTasteDropdown.value = false }, 150)
}
</script>

<template>
  <div class="dopamine">
    <!-- 情境输入卡片 -->
    <section class="hero-card">
      <div class="hero-bg"></div>
      <div class="hero-content">
        <h1 class="hero-title">🎯 今天吃什么？</h1>
        <p class="hero-sub">告诉大模型你的心情和偏好，让 AI 为你推荐一餐</p>
      </div>
    </section>

    <section class="form-card">
      <div class="form-row">
        <div class="form-item">
          <label class="lbl">💭 心情</label>
          <input v-model="mood" placeholder="如：略累、兴奋、平淡…" />
        </div>
        <div class="form-item">
          <label class="lbl">🌤️ 天气</label>
          <div class="weather-display">{{ weatherSummary }}</div>
        </div>
      </div>

      <!-- 口味：输入框 + 下拉 -->
      <div class="form-item">
        <label class="lbl">👅 口味偏好</label>
        <div class="taste-wrapper">
          <input
            v-model="taste"
            placeholder="输入口味，或点击下方选择"
            @focus="showTasteDropdown = true"
            @blur="handleTasteBlur"
          />
          <div class="taste-dropdown" v-if="showTasteDropdown">
            <div
              v-for="t in tasteOptions"
              :key="t"
              class="taste-option"
              :class="{ active: taste === t }"
              @mousedown="selectTaste(t)"
            >{{ t }}</div>
          </div>
        </div>
        <div class="taste-hints">
          <span
            v-for="t in TASTE_PRESETS.slice(0, 6)"
            :key="t"
            class="hint-tag"
            :class="{ on: taste === t }"
            @click="selectTaste(t)"
          >{{ t }}</span>
        </div>
      </div>

      <!-- 用餐方式：大卡片选择 -->
      <div class="form-item">
        <label class="lbl">🍴 用餐方式</label>
        <div class="dining-cards">
          <div
            v-for="o in DINING_OPTIONS"
            :key="o.value"
            class="dining-card"
            :class="{ active: diningType === o.value }"
            @click="diningType = o.value"
          >
            <span class="dining-icon">{{ o.icon }}</span>
            <span class="dining-label">{{ o.label }}</span>
          </div>
        </div>
      </div>

      <button class="rec-btn" @click="recommend" :disabled="loading">
        <span v-if="!loading">✨ 让大模型推荐这一餐</span>
        <span v-else>🎲 大模型计算中…</span>
      </button>
    </section>

    <!-- 推荐结果卡片 -->
    <section class="result-card" v-if="reply">
      <div class="result-header">
        <span class="result-icon">🍜</span>
        <h2>今日推荐</h2>
      </div>
      <div class="reply-content" :class="{ degraded }">{{ reply }}</div>
      <button
        v-if="!degraded"
        class="adopt-btn"
        @click="adopt"
        :disabled="adopted"
      >
        {{ adopted ? '✅ 已采纳' : '📝 采纳并写入流程表' }}
      </button>
      <div v-if="adoptMsg" class="adopt-msg">{{ adoptMsg }}</div>
    </section>

    <!-- 空状态 -->
    <section class="empty-card" v-else>
      <div class="empty-ill">🍽️</div>
      <p class="empty-title">填好心情和口味</p>
      <p class="empty-desc">点上方按钮，让 AI 为你推荐一餐</p>
    </section>
  </div>
</template>

<style scoped>
.dopamine {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 720px;
  margin: 0 auto;
  height: 100%;
  overflow-y: auto;
  padding-bottom: 16px;
}

/* Hero 卡片 - 腾讯视频风格 */
.hero-card {
  position: relative;
  border-radius: 16px;
  overflow: hidden;
  padding: 32px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 50%, #f093fb 100%);
  color: white;
  box-shadow: 0 8px 32px rgba(102, 126, 234, 0.3);
}
.hero-bg {
  position: absolute;
  top: -50%;
  right: -20%;
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, rgba(255,255,255,0.3) 0%, transparent 70%);
  border-radius: 50%;
}
.hero-content { position: relative; z-index: 1; }
.hero-title { font-size: 24px; font-weight: 700; margin-bottom: 6px; }
.hero-sub { font-size: 13px; opacity: 0.9; }

/* 表单卡片 */
.form-card {
  background: var(--surface);
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 14px;
}
.form-item { margin-bottom: 14px; position: relative; }
.lbl {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  margin-bottom: 8px;
}
.weather-display {
  padding: 10px 14px;
  background: linear-gradient(135deg, #e0f2fe, #f0f9ff);
  border-radius: 10px;
  border: 1px solid #bae6fd;
  font-weight: 500;
  color: #0369a1;
}

/* 口味输入 + 下拉 */
.taste-wrapper { position: relative; }
.taste-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 4px;
  background: white;
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  max-height: 200px;
  overflow-y: auto;
  z-index: 10;
}
.taste-option {
  padding: 10px 14px;
  cursor: pointer;
  font-size: 14px;
  border-bottom: 1px solid #f3f4f6;
  transition: background 0.15s;
}
.taste-option:last-child { border-bottom: none; }
.taste-option:hover { background: var(--accent-soft); }
.taste-option.active { background: var(--accent-soft); color: var(--accent-text); font-weight: 600; }
.taste-hints { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.hint-tag {
  padding: 4px 12px;
  font-size: 12px;
  background: var(--surface-muted);
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s;
  color: var(--text-muted);
}
.hint-tag:hover { background: var(--accent-soft); color: var(--accent-text); }
.hint-tag.on { background: var(--accent); color: white; }

/* 用餐方式卡片选择 */
.dining-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.dining-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 16px 12px;
  border: 2px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
  cursor: pointer;
  transition: all 0.2s;
}
.dining-card:hover { border-color: var(--accent); transform: translateY(-2px); }
.dining-card.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  box-shadow: 0 4px 12px rgba(39, 210, 191, 0.25);
}
.dining-icon { font-size: 28px; }
.dining-label { font-size: 13px; font-weight: 500; }

/* 推荐按钮 */
.rec-btn {
  width: 100%;
  padding: 14px;
  margin-top: 4px;
  background: linear-gradient(135deg, #27d2bf, #14b8a6);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  box-shadow: 0 4px 16px rgba(39, 210, 191, 0.35);
  transition: all 0.2s;
}
.rec-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 20px rgba(39, 210, 191, 0.45); }

/* 结果卡片 */
.result-card {
  background: var(--surface);
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
  border-left: 4px solid var(--accent);
}
.result-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.result-header h2 { font-size: 16px; font-weight: 600; }
.result-icon { font-size: 20px; }
.reply-content {
  padding: 14px;
  background: linear-gradient(135deg, var(--accent-soft), #f0fdfa);
  border-radius: 12px;
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 14px;
}
.reply-content.degraded {
  background: var(--surface-muted);
  color: var(--text-muted);
}
.adopt-btn {
  margin-top: 14px;
  width: 100%;
  padding: 12px;
  background: var(--brand);
  color: white;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  transition: all 0.2s;
}
.adopt-btn:hover:not(:disabled) { opacity: 0.9; }
.adopt-msg { margin-top: 10px; font-size: 13px; color: var(--success); }

/* 空状态 */
.empty-card {
  text-align: center;
  padding: 40px 20px;
  background: var(--surface);
  border-radius: 16px;
}
.empty-ill { font-size: 48px; margin-bottom: 12px; }
.empty-title { font-size: 16px; font-weight: 600; color: var(--text); }
.empty-desc { font-size: 13px; color: var(--text-muted); margin-top: 4px; }

@media (max-width: 600px) {
  .form-row { grid-template-columns: 1fr; }
}
</style>
