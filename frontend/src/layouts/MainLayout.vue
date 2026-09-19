<template>
  <div class="app-shell">
    <!-- 跳转链接：键盘用户可跳过侧栏直达主内容（UX-33） -->
    <a class="skip-link" href="#main-content">跳到主内容</a>

    <header class="topbar">
      <div class="brand">
        中医电子病历质控与标准化系统<em>TCM EHR Quality Control &amp; Standardization</em>
      </div>
      <div class="user">
        <span class="avatar" aria-hidden="true">{{ userStore.role?.charAt(0) || '用' }}</span>
        <span>{{ userStore.role || '用户' }}</span>
        <el-button link class="logout" @click="handleLogout">退出</el-button>
      </div>
    </header>

    <div class="layout">
      <aside>
        <nav aria-label="主导航">
          <ul class="menu">
            <template v-for="group in menuGroups" :key="group.title">
              <li class="sec">{{ group.title }}</li>
              <li v-for="item in group.items" :key="item.path">
                <!-- 用 router-link 而非 javascript: 伪链接，保留真实 href 与浏览器导航语义（UX-31 / UX-33） -->
                <router-link
                  :to="item.path"
                  :class="{ on: $route.path === item.path }"
                  :aria-current="$route.path === item.path ? 'page' : undefined"
                >{{ item.title }}</router-link>
              </li>
            </template>
          </ul>
        </nav>
      </aside>

      <main id="main-content">
        <!-- 面包屑：承载分组与当前位置（UX-32） -->
        <nav v-if="breadcrumb.length" class="crumb" aria-label="面包屑">
          <template v-for="(c, i) in breadcrumb" :key="c">
            <span class="crumb-item">{{ c }}</span>
            <span v-if="i < breadcrumb.length - 1" class="crumb-sep">/</span>
          </template>
        </nav>
        <router-view />
      </main>
    </div>

    <!-- 全局 AI 助手悬浮窗（批C·3.2，所有登录页可用） -->
    <AiAssistant />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import AiAssistant from '@/components/AiAssistant.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 菜单项全量定义；实际渲染项由登录返回的 menus 过滤（批A·1.2 双角色），
// 未开发页面显示占位页
const ALL_MENUS = [
  { group: '数据处理', title: '首页看板', path: '/dashboard' },
  { group: '数据处理', title: '病历数据', path: '/records' },
  { group: '数据处理', title: '结构化解析', path: '/nlp-extract' },
  { group: '数据处理', title: '质控校验', path: '/qc-check' },
  { group: '数据处理', title: '人工复核', path: '/review' },
  { group: '数据处理', title: '清洗与导出', path: '/governance' },
  { group: '系统配置', title: '术语词典', path: '/dictionary' },
  { group: '系统配置', title: '日志审计', path: '/audit-log' }
]

const GROUP_ORDER = ['数据处理', '系统配置']

// 管理员 8 项全量；审核员仅「首页看板 + 人工复核」（与 AuthServiceImpl 一致）；空分组不渲染
const menuGroups = computed(() => {
  const allowed = userStore.menus || []
  return GROUP_ORDER
    .map((title) => ({
      title,
      items: ALL_MENUS.filter((m) => m.group === title && allowed.includes(m.title))
    }))
    .filter((group) => group.items.length > 0)
})

/** 面包屑：所属分组 + 当前页标题（UX-32）；无 meta.title 的页面不渲染 */
const breadcrumb = computed(() => {
  const title = route.meta?.title
  if (!title) return []
  const group = ALL_MENUS.find((m) => m.title === title)?.group
  return group ? [group, title] : [title]
})

const handleLogout = () => {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
/* 低于该宽度侧栏与主区会互相挤压，改为横向滚动（UX-49） */
.app-shell {
  min-width: 1024px;
}

/* 跳转链接：默认视觉隐藏，键盘聚焦时显现（UX-33） */
.skip-link {
  position: absolute;
  left: -9999px;
  top: 0;
  z-index: 2000;
  padding: 8px 14px;
  background: var(--ink);
  color: #fff;
  text-decoration: none;
  border-radius: 0 0 4px 0;
}
.skip-link:focus {
  left: 0;
}

/* ===== 顶部导航（原型 topbar） ===== */
.topbar {
  height: 52px;
  background: var(--ink);
  display: flex;
  align-items: center;
  padding: 0 20px;
  color: #fff;
}
.brand {
  font-size: 16px;
  letter-spacing: 1px;
  margin-right: 36px;
}
.brand em {
  font-style: normal;
  color: #c9b99a;
  margin-left: 8px;
  font-size: 12px;
}
.topbar .user {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #d8dfd9;
}
.topbar .avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #4d6b58;
  text-align: center;
  line-height: 28px;
  font-size: 12px;
}
.logout {
  color: #d8dfd9;
}
.logout:hover {
  color: #fff;
}

/* ===== 布局（原型 layout） ===== */
.layout {
  display: flex;
  min-height: calc(100vh - 52px);
}
aside {
  width: 176px;
  background: #fbfaf6;
  border-right: 1px solid var(--line);
  flex-shrink: 0;
}
.menu {
  list-style: none;
  padding-top: 8px;
}
.menu a {
  display: block;
  padding: 11px 18px;
  color: #55534c;
  text-decoration: none;
  font-size: 13.5px;
  border-left: 3px solid transparent;
}
.menu a:hover {
  background: var(--ink-light);
  color: var(--ink);
}
.menu a.on {
  background: var(--ink-light);
  color: var(--ink);
  border-left-color: var(--ink);
  font-weight: bold;
}
.menu .sec {
  padding: 14px 18px 4px;
  font-size: 12px;
  color: #a09c90;
}

main {
  flex: 1;
  padding: 16px 20px;
  min-width: 0;
  /* 超宽屏下卡片与图表不再被无限拉伸（UX-45） */
  max-width: 1600px;
  margin: 0 auto;
}

/* ===== 面包屑（UX-32） ===== */
.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
  font-size: 12.5px;
  color: var(--text-sub);
}
.crumb-item:last-child {
  color: var(--ink);
  font-weight: bold;
}
.crumb-sep {
  color: #c9c3b4;
}
</style>
