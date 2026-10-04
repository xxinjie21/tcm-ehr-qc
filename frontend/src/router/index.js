import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

// 已登录用户的合法系统级角色（与后端一致：管理员 / 用户。
// 所有者、成员、未加入组织在前端都归「用户」，组织内区分看 orgRole）
const KNOWN_ROLES = ['管理员', '用户']

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue') },
  { path: '/register', name: 'Register', component: () => import('@/views/Register.vue') },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      // meta.title 与登录返回的 menus 名称一致，MainLayout 按 menus 渲染侧栏
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '首页看板' } },
      { path: 'review', name: 'Review', component: () => import('@/views/Review.vue'), meta: { title: '人工复核' } },
      { path: 'records', name: 'Records', component: () => import('@/views/Records.vue'), meta: { title: '病历数据' } },
      { path: 'nlp-extract', name: 'NlpExtract', component: () => import('@/views/NlpExtract.vue'), meta: { title: '结构化解析' } },
      { path: 'qc-check', name: 'QcCheck', component: () => import('@/views/Qc.vue'), meta: { title: '质控校验' } },
      { path: 'governance', name: 'Governance', component: () => import('@/views/Governance.vue'), meta: { title: '清洗与导出' } },
      { path: 'dictionary', name: 'Dictionary', component: () => import('@/views/Dictionary.vue'), meta: { title: '术语词典' } },
      // 术语批量导入：「术语词典」的子项，所有登录用户可见。
      // 所有人可把文件导入本机个人词典（POST /dictionary/parse 只解析不落库）；
      // 管理员在该页还能选「直接生效」写小组基线（POST /dictionary/import 仅管理员）。
      { path: 'dictionary/import', name: 'DictionaryImport', component: () => import('@/views/DictionaryImport.vue'), meta: { title: '术语批量导入' } },
      { path: 'audit-log', name: 'AuditLog', component: () => import('@/views/AuditLog.vue'), meta: { title: '日志审计' } },
      // 组织管理（仅管理员）与我的组织（所有登录用户）
      { path: 'orgs', name: 'Orgs', component: () => import('@/views/Groups.vue'), meta: { title: '组织管理', roles: ['管理员'] } },
      { path: 'my-org', name: 'MyOrg', component: () => import('@/views/MyGroup.vue'), meta: { title: '我的组织' } }
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

  if (!isPublic && !userStore.token) {
    return '/login'
  }
  if (isPublic) {
    return true
  }
  // 登录态存在但角色缺失或非法（localStorage 被清、旧版本残留）→ 强制重新登录
  if (!KNOWN_ROLES.includes(userStore.role)) {
    return '/login'
  }
  // 角色守卫：直输管理页 URL 时退回落地页
  const roles = to.meta && to.meta.roles
  if (roles && !roles.includes(userStore.role)) {
    ElMessage.error('无权限访问该页面')
    return '/dashboard'
  }
  // 未加入组织用户的落地页：路由守卫把数据页全拦到「我的组织」，
  // 由 MyGroup.vue 渲染引导文案（可自助创建组织 / 等所有者邀请）。
  // 哪些页面算「数据页」：7 个数据处理页 + 日志审计 + 术语词典（管理员不受影响）。
  if (!userStore.hasOrg && userStore.role !== '管理员' && to.path !== '/my-org') {
    return '/my-org'
  }
  return true
})

const APP_NAME = '中医电子病历质控与标准化系统'

router.afterEach((to) => {
  const title = to.meta && to.meta.title
  document.title = title ? `${title} · ${APP_NAME}` : APP_NAME
})

export default router