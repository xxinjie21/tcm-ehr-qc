import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

// 已登录用户的合法角色（与后端 AuthServiceImpl 的 ROLE_ADMIN / ROLE_AUDITOR 一致）
const KNOWN_ROLES = ['管理员', '审核员']

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue') },
  { path: '/register', name: 'Register', component: () => import('@/views/Register.vue') },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      // 双角色（meta.title 与登录返回的 menus 名称一致，MainLayout 按 menus 渲染）
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '首页看板', roles: ['管理员', '审核员'] } },
      { path: 'review', name: 'Review', component: () => import('@/views/Placeholder.vue'), meta: { title: '人工复核', roles: ['管理员', '审核员'] } },
      // 仅管理员
      { path: 'records', name: 'Records', component: () => import('@/views/Records.vue'), meta: { title: '病历数据', roles: ['管理员'] } },
      { path: 'nlp-extract', name: 'NlpExtract', component: () => import('@/views/Placeholder.vue'), meta: { title: '结构化解析', roles: ['管理员'] } },
      { path: 'qc-check', name: 'QcCheck', component: () => import('@/views/Placeholder.vue'), meta: { title: '质控校验', roles: ['管理员'] } },
      { path: 'governance', name: 'Governance', component: () => import('@/views/Governance.vue'), meta: { title: '清洗与导出', roles: ['管理员'] } },
      { path: 'dictionary', name: 'Dictionary', component: () => import('@/views/Dictionary.vue'), meta: { title: '术语词典', roles: ['管理员'] } },
      { path: 'audit-log', name: 'AuditLog', component: () => import('@/views/AuditLog.vue'), meta: { title: '日志审计', roles: ['管理员'] } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  const isPublic = to.path === '/login' || to.path === '/register'

  // 未登录只能访问公开页
  if (!isPublic && !userStore.token) {
    return '/login'
  }
  if (isPublic) {
    return true
  }
  // 登录态存在但角色缺失或非法（localStorage 被清、旧版本残留）→ 强制重新登录。
  // 若在此放行，下面的角色守卫会把自己重定向到 /dashboard，形成无限循环。
  if (!KNOWN_ROLES.includes(userStore.role)) {
    return '/login'
  }
  // 角色守卫：直输管理页 URL 时退回角色落地页，不进入无权限页面
  const roles = to.meta && to.meta.roles
  if (roles && !roles.includes(userStore.role)) {
    ElMessage.error('无权限访问该页面')
    return '/dashboard'
  }
  return true
})

export default router
