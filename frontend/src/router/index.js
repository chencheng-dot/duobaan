import { createRouter, createWebHashHistory } from 'vue-router'
import WorkPage from '../views/WorkPage.vue'
import DopaminePage from '../views/DopaminePage.vue'
import MinePage from '../views/MinePage.vue'
import SettingsPage from '../views/SettingsPage.vue'

// 使用 hash 路由，避免 Spring Boot 端额外配置 SPA 回退
const routes = [
  { path: '/', redirect: '/work' },
  { path: '/work', name: 'work', component: WorkPage },
  { path: '/dopamine', name: 'dopamine', component: DopaminePage },
  { path: '/mine', name: 'mine', component: MinePage },
  { path: '/settings', name: 'settings', component: SettingsPage }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
