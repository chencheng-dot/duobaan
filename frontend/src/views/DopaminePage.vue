<script setup>
import { ref, computed, onMounted } from 'vue'
import { recommendMeal, adoptMeal } from '../api'
import { usePublicData } from '../composables/usePublicData.js'

const { weather } = usePublicData()

const mood = ref('')
const taste = ref('')
const tastePreset = ref('')
const diningType = ref('DINE_IN')

const TASTE_PRESETS = ['酸辣', '清淡', '甜口', '咸鲜', '重口', '轻食', '热食', '凉菜', '香辣', '麻辣', '酸甜', '鲜香']
const DINING_OPTIONS = [
  { value: 'TAKEOUT', label: '外卖' },
  { value: 'DINE_IN', label: '堂吃' },
  { value: 'COOK', label: '自己做' }
]

const loading = ref(false)
const reply = ref('')
const degraded = ref(false)
const adopted = ref(false)
const adoptMsg = ref('')
const showTasteDropdown = ref(false)

const weatherSummary = computed(() => (weather.value ? weather.value.text + ' ' + weather.value.temp + '°C' : '—'))

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

function handleTasteBlur() {
  setTimeout(() => { showTasteDropdown.value = false }, 150)
}
</script>

<template>
  <div class="dopamine">
    <!-- 标题区：纯文字 + 线条分隔 -->
    <section class="title-row">
      <div class="title-text">
        <h1>美食推荐</h1>
        <p class="sub">告诉大模型你的心情和偏好，获取今日餐食建议</p>
      </div>
      <div class="title-line"></div>
    </section>

    <!-- 表单卡片 -->
    <section class="form-card">
      <div class="form-row">
        <div class="form-item">
          <label class="lbl">心情</label>
          <input v-model="mood" placeholder="如：略累、兴奋、平淡…" />
        </div>
        <div class="form-item">
          <label class="lbl">天气</label>
          <div class="weather-display">{{ weatherSummary }}</div>
        </div>
      </div>

      <!-- 口味：输入框 + 下拉 -->
      <div class="form-item">
        <label class="lbl">口味偏好</label>
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

      <!-- 用餐方式：线条卡片选择 -->
      <div class="form-item">
        <label class="lbl">用餐方式</label>
        <div class="dining-cards">
          <div
            v-for="o in DINING_OPTIONS"
            :key="o.value"
            class="dining-card"
            :class="{ active: diningType === o.value }"
            @click="diningType = o.value"
          >
            <!-- SVG 线条图标 -->
            <svg v-if="o.value === 'TAKEOUT'" viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
              <path d="M6 8h12l1.5 11H4.5L6 8z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
              <path d="M8 8V5a4 4 0 0 1 8 0v3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
            <svg v-else-if="o.value === 'DINE_IN'" viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
              <circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="1.5"/>
              <path d="M8 12h8M12 8v8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
              <path d="M5 19h14M7 19V10a5 5 0 0 1 10 0v9" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
              <line x1="9" y1="14" x2="15" y2="14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
            <span class="dining-label">{{ o.label }}</span>
          </div>
        </div>
      </div>

      <button class="rec-btn" @click="recommend" :disabled="loading">
        <span v-if="!loading">获取推荐</span>
        <span v-else>计算中…</span>
      </button>
    </section>

    <!-- 推荐结果 -->
    <section class="result-card" v-if="reply">
      <div class="result-header">
        <h2>推荐结果</h2>
      </div>
      <div class="reply-content" :class="{ degraded }">{{ reply }}</div>
      <button
        v-if="!degraded"
        class="adopt-btn"
        @click="adopt"
        :disabled="adopted"
      >
        {{ adopted ? '已采纳' : '采纳并写入流程表' }}
      </button>
      <div v-if="adoptMsg" class="adopt-msg">{{ adoptMsg }}</div>
    </section>

    <!-- 空状态 -->
    <section class="empty-card" v-else>
      <svg viewBox="0 0 48 48" width="44" height="44" aria-hidden="true">
        <circle cx="24" cy="24" r="18" fill="none" stroke="#E5E7EB" stroke-width="1.2"/>
        <path d="M18 22l6 6 6-6" fill="none" stroke="#D1D5DB" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      <p class="empty-title">等待你的选择</p>
      <p class="empty-desc">填写表单后点击「获取推荐」</p>
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
  padding: 20px;
  padding-bottom: 24px;
}

.title-row {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  padding-bottom: 12px;
}
.title-text h1 { font-size: 20px; font-weight: 600; color: var(--text); letter-spacing: 0.5px; }
.title-text .sub { font-size: 13px; color: var(--text-muted); margin-top: 4px; }
.title-line { flex: 1; height: 1px; background: var(--border); margin-bottom: 10px; }

.form-card {
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 20px;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 14px;
}
.form-item { margin-bottom: 14px; position: relative; }
.form-item:last-child { margin-bottom: 0; }
.lbl {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  margin-bottom: 8px;
}
.weather-display {
  padding: 9px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 14px;
  background: #FFFFFF;
  color: var(--text-muted);
}

/* 口味 */
.taste-wrapper { position: relative; }
.taste-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 4px;
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  max-height: 200px;
  overflow-y: auto;
  z-index: 10;
}
.taste-option {
  padding: 8px 14px;
  cursor: pointer;
  font-size: 14px;
  border-bottom: 1px solid var(--border-soft);
  transition: background 0.12s;
}
.taste-option:last-child { border-bottom: none; }
.taste-option:hover { background: #F9FAFB; }
.taste-option.active { background: #F3F4F6; font-weight: 600; color: var(--text); }
.taste-hints { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.hint-tag {
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid var(--border);
  background: #FFFFFF;
  border-radius: var(--radius-full);
  cursor: pointer;
  color: var(--text-muted);
  transition: all 0.12s;
}
.hint-tag:hover { border-color: var(--text); color: var(--text); }
.hint-tag.on { background: var(--text); color: #FFFFFF; border-color: var(--text); }

/* 用餐方式 */
.dining-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.dining-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 18px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: #FFFFFF;
  cursor: pointer;
  transition: all 0.12s;
  color: var(--text-muted);
}
.dining-card:hover { border-color: var(--text); color: var(--text); }
.dining-card.active {
  border-color: var(--text);
  color: var(--text);
  box-shadow: inset 0 0 0 1px var(--text);
}
.dining-label { font-size: 13px; font-weight: 500; }

.rec-btn {
  width: 100%;
  padding: 12px;
  margin-top: 4px;
  background: var(--text);
  color: #FFFFFF;
  border: 1px solid var(--text);
  border-radius: var(--radius);
  font-size: 14px;
  font-weight: 500;
  transition: opacity 0.12s;
}
.rec-btn:hover:not(:disabled) { opacity: 0.85; }

.result-card {
  background: #FFFFFF;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 20px;
}
.result-header h2 { font-size: 16px; font-weight: 600; color: var(--text); margin-bottom: 12px; }
.reply-content {
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 14px;
  color: var(--text);
  background: #FFFFFF;
}
.reply-content.degraded {
  background: #FAFAFA;
  color: var(--text-muted);
}
.adopt-btn {
  margin-top: 14px;
  width: 100%;
  padding: 10px;
  background: #FFFFFF;
  color: var(--text);
  border: 1px solid var(--text);
  border-radius: var(--radius);
  font-weight: 500;
  transition: all 0.12s;
}
.adopt-btn:hover:not(:disabled) { background: var(--text); color: #FFFFFF; }
.adopt-msg { margin-top: 10px; font-size: 13px; color: var(--success); }

.empty-card {
  text-align: center;
  padding: 48px 20px;
  border: 1px dashed var(--border);
  border-radius: var(--radius-card);
}
.empty-title { font-size: 15px; font-weight: 500; color: var(--text); margin-top: 12px; }
.empty-desc { font-size: 13px; color: var(--text-muted); margin-top: 4px; }

@media (max-width: 600px) {
  .form-row { grid-template-columns: 1fr; }
}
</style>
