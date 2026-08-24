<script setup>
import { ref, computed } from 'vue'
import { recommendMeal, adoptMeal } from '../api'
import { usePublicData } from '../composables/usePublicData.js'

const { weather } = usePublicData()

const mood = ref('')
const tasteTags = ref([])
const diningType = ref('DINE_IN')

const TASTE_OPTIONS = ['酸辣', '清淡', '甜口', '咸鲜', '重口', '轻食', '热食', '凉菜']
const DINING_OPTIONS = [
  { value: 'TAKEOUT', label: '外卖' },
  { value: 'DINE_IN', label: '堂吃' },
  { value: 'COOK', label: '自己做' }
]

function toggleTaste(t) {
  if (tasteTags.value.includes(t)) {
    tasteTags.value = tasteTags.value.filter((x) => x !== t)
  } else {
    tasteTags.value.push(t)
  }
}

const loading = ref(false)
const reply = ref('')
const degraded = ref(false)
const adopted = ref(false)
const adoptMsg = ref('')

const weatherSummary = computed(() => (weather.value ? weather.value.text + ' ' + weather.value.temp + '℃' : '—'))

async function recommend() {
  loading.value = true
  adopted.value = false
  reply.value = ''
  try {
    const res = await recommendMeal({
      mood: mood.value || '平淡',
      weather: weatherSummary.value,
      tasteTags: tasteTags.value.length ? tasteTags.value : ['不限'],
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
  // 从回复里提取首行作为餐名，简单取前 20 字
  const title = reply.value.split('\n')[0].slice(0, 20)
  try {
    await adoptMeal(title)
    adopted.value = true
    adoptMsg.value = `已把「${title}」写入今日流程表并标记完成`
  } catch (e) {
    adoptMsg.value = '采纳失败：' + e.message
  }
}
</script>

<template>
  <div class="dopamine">
    <section class="context card">
      <h2 class="sec-title">情境输入</h2>

      <div class="field">
        <label class="lbl">心情</label>
        <input v-model="mood" placeholder="如：略累、兴奋、平淡…" />
      </div>

      <div class="field">
        <label class="lbl">天气（自动带入）</label>
        <div class="weather-box">{{ weatherSummary }}</div>
      </div>

      <div class="field">
        <label class="lbl">口味</label>
        <div class="chips">
          <button
            v-for="t in TASTE_OPTIONS"
            :key="t"
            class="chip"
            :class="{ on: tasteTags.includes(t) }"
            @click="toggleTaste(t)"
          >{{ t }}</button>
        </div>
      </div>

      <div class="field">
        <label class="lbl">用餐方式</label>
        <div class="radios">
          <button
            v-for="o in DINING_OPTIONS"
            :key="o.value"
            class="radio"
            :class="{ on: diningType === o.value }"
            @click="diningType = o.value"
          >{{ o.label }}</button>
        </div>
      </div>

      <button class="rec-btn" @click="recommend" :disabled="loading">
        {{ loading ? '大模型计算中…' : '让大模型推荐这一餐' }}
      </button>
    </section>

    <section class="result card" v-if="reply">
      <h2 class="sec-title">餐食推荐</h2>
      <div class="reply" :class="{ degraded }">{{ reply }}</div>
      <button v-if="!degraded" class="adopt" @click="adopt" :disabled="adopted">
        {{ adopted ? '已采纳 ✓' : '采纳并写入流程表' }}
      </button>
      <div v-if="adoptMsg" class="adopt-msg">{{ adoptMsg }}</div>
    </section>

    <section class="placeholder card" v-else>
      <div class="ph-icon">🍜</div>
      <p class="muted">填好心情、口味和用餐方式，点上方按钮获取一餐推荐。</p>
      <p class="muted sm">采纳后会自动写入流程表「今日用餐」并标记完成（多巴胺释放闭环）。</p>
    </section>
  </div>
</template>

<style scoped>
.dopamine {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 720px;
  margin: 0 auto;
  height: 100%;
  overflow-y: auto;
  padding-bottom: 12px;
}
.sec-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; }

.context { padding: 16px; }
.field { margin-bottom: 14px; }
.lbl { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 6px; }
.weather-box {
  padding: 8px 12px;
  background: var(--surface-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.chips, .radios { display: flex; flex-wrap: wrap; gap: 6px; }
.chip, .radio {
  padding: 5px 12px;
  font-size: 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: var(--surface);
}
.chip.on, .radio.on {
  background: var(--accent-soft);
  color: var(--accent-text);
  border-color: var(--accent);
}

.rec-btn {
  width: 100%;
  padding: 10px;
  background: var(--accent);
  color: var(--brand-on);
  border-color: var(--accent);
  font-weight: 600;
}
.rec-btn:hover { opacity: 0.9; border-color: var(--accent); }

.result { padding: 16px; }
.reply {
  padding: 12px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
  border-radius: var(--radius);
  white-space: pre-wrap;
  color: var(--accent-text);
}
.reply.degraded {
  background: var(--surface-muted);
  border-color: var(--border);
  color: var(--text);
}
.adopt {
  margin-top: 12px;
  width: 100%;
  padding: 10px;
  background: var(--accent);
  color: var(--brand-on);
  border-color: var(--accent);
  font-weight: 600;
}
.adopt-msg { margin-top: 8px; font-size: 13px; color: var(--success); }

.placeholder {
  padding: 32px 16px;
  text-align: center;
}
.ph-icon { font-size: 40px; margin-bottom: 8px; }
.sm { font-size: 12px; margin-top: 6px; }
</style>
