import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue') },
  { path: '/register', name: 'Register', component: () => import('@/views/Register.vue') },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '首页看板' } },
      { path: 'records', name: 'Records', component: () => import('@/views/Placeholder.vue'), meta: { title: '病历数据' } },
      { path: 'nlp-extract', name: 'NlpExtract', component: () => import('@/views/Placeholder.vue'), meta: { title: '结构化解析' } },
      { path: 'qc-check', name: 'QcCheck', component: () => import('@/views/Placeholder.vue'), meta: { title: '质控校验' } },
      { path: 'review', name: 'Review', component: () => import('@/views/Placeholder.vue'), meta: { title: '人工复核' } },
      { path: 'governance', name: 'Governance', component: () => import('@/views/Governance.vue'), meta: { title: '清洗与导出' } },
      { path: 'dictionary', name: 'Dictionary', component: () => import('@/views/Dictionary.vue'), meta: { title: '术语词典' } },
      { path: 'audit-log', name: 'AuditLog', component: () => import('@/views/AuditLog.vue'), meta: { title: '日志审计' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  if (to.path !== '/login' && to.path !== '/register' && !userStore.token) {
    return '/login'
  }
})

export default router
