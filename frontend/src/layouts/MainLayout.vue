<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        中医电子病历质控与标准化系统<em>TCM EHR Quality Control &amp; Standardization</em>
      </div>
      <div class="user">
        <span class="avatar">{{ userStore.role?.charAt(0) || '用' }}</span>
        <span>{{ userStore.role || '用户' }}</span>
        <el-button link class="logout" @click="handleLogout">退出</el-button>
      </div>
    </header>

    <div class="layout">
      <aside>
        <ul class="menu">
          <template v-for="group in menuGroups" :key="group.title">
            <li class="sec">{{ group.title }}</li>
            <li v-for="item in group.items" :key="item.path">
              <a
                href="javascript:;"
                :class="{ on: $route.path === item.path }"
                @click="$router.push(item.path)"
              >{{ item.title }}</a>
            </li>
          </template>
        </ul>
      </aside>

      <main>
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()

// 菜单全量展示（角色权限由后端接口控制）；未开发页面显示占位页
const menuGroups = [
  {
    title: '数据处理',
    items: [
      { title: '首页看板', path: '/dashboard' },
      { title: '病历数据', path: '/records' },
      { title: '结构化解析', path: '/nlp-extract' },
      { title: '质控校验', path: '/qc-check' },
      { title: '人工复核', path: '/review' },
      { title: '清洗与导出', path: '/governance' }
    ]
  },
  {
    title: '系统配置',
    items: [
      { title: '术语词典', path: '/dictionary' },
      { title: '日志审计', path: '/audit-log' }
    ]
  }
]

const handleLogout = () => {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
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
}
</style>
