import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

// 已登录用户的合法**系统级**角色（与后端 users.role 口径一致，见 docs/角色与权限矩阵.md：
// 系统级只有「管理员」；其余身份都是组织级，登录响应里另有 orgRole，不在这层判定）。
// 注意：**没有「审核员」** —— 那是线上库 auditor 的 role 漂移出来的
// （种子 SQL 写的是「用户」，线上被写成了「审核员」），属数据异常而非合法角色。
// 这里**不能**为它扩白名单（否则等于把异常当成设计）；正确的修法是对齐
// users.role / 种子，而不是给白名单加角色。漂移账号登录会在 beforeEach 被显式
// 拦截并提示（原先静默踢回登录页，用户只会以为密码错了，见审查报告 I8）。
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
        // 标准化质量报告（批次 24）：只读，登录即可；甲类=标准符合度，乙类=数据集覆盖度
        { path: 'standardization-report', name: 'StandardizationReport', component: () => import('@/views/StandardizationReport.vue'), meta: { title: '标准化质量报告' } },
      // 组织管理（仅管理员）与我的组织（所有登录用户）
      { path: 'orgs', name: 'Orgs', component: () => import('@/views/Groups.vue'), meta: { title: '组织管理', roles: ['管理员'] } },
      { path: 'my-org', name: 'MyOrg', component: () => import('@/views/MyGroup.vue'), meta: { title: '我的组织' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 切页后回到顶部。
  // ⚠️ 只对「文档级滚动」生效（登录 / 注册页，以及将来可能出现的长公开页）。
  // 应用内的主内容区不是 document 在滚 —— 它是 main 自身在滚
  // （.layout 定高 + main{overflow-y:auto}），document 的滚动量恒为 0，
  // 所以这里管不到应用内切页。那部分由 MainLayout 在
  // <Transition @before-enter="resetMainScroll"> 里手动把 main.scrollTop 归零。
  // 两处都要写：只写这里，从长页面切到短页面会停在「短页面的底部」。
  // savedPosition 用于浏览器前进/后退时还原文档级滚动位置。
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    return { top: 0 }
  }
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
  // 登录态存在但角色缺失或非法（localStorage 被清、旧版本残留）→ 强制重新登录。
  // 原来是静默 return '/login'：密码明明校验通过，页面却"刷新"回登录页，
  // 用户只当自己密码输错（I8）。先弹一条显式提示，再跳走。
  if (!KNOWN_ROLES.includes(userStore.role)) {
    ElMessage.error('当前账号角色「' + userStore.role + '」不受支持，请联系管理员')
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