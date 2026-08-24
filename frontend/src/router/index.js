import { createRouter, createWebHashHistory } from 'vue-router'
import WorkPage from '../views/WorkPage.vue'
import DopaminePage from '../views/DopaminePage.vue'

// 使用 hash 路由，避免 Spring Boot 端额外配置 SPA 回退
const routes = [
  { path: '/', redirect: '/work' },
  { path: '/work', name: 'work', component: WorkPage },
  { path: '/dopamine', name: 'dopamine', component: DopaminePage }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
