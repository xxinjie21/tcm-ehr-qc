<template>
  <div class="app-shell">
    <!-- 跳转链接：键盘用户可跳过侧栏直达主内容-->
    <a class="skip-link" href="#main-content">跳到主内容</a>

    <header class="topbar">
      <div class="brand">
        中医电子病历质控与标准化系统<em>TCM EHR Quality Control &amp; Standardization</em>
      </div>
      <div class="user">
        <!-- 28.18 全局通知中心：顶栏铃铛，把「待复核 / 待清洗 / 无效数据」三类待办聚到一处。
             数据取自各页已有的概览接口，不做轮询（进主框架取一次；进详情页由各页自己刷新）。
             已读状态按 id（key:数量）存 localStorage —— 数量变化就是一条新通知，徽标会重新亮起。 -->
        <el-popover
          v-model:visible="noticeVisible"
          placement="bottom-end"
          :width="320"
          trigger="click"
          @show="markNoticesRead"
        >
          <template #reference>
            <el-badge :value="unreadNotices.length" :hidden="!unreadNotices.length" :max="9">
              <button
                type="button"
                class="notice-btn"
                :aria-label="unreadNotices.length ? `通知，${unreadNotices.length} 条未读` : '通知，无未读'"
              >
                <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    d="M12 3.5a4.5 4.5 0 0 0-4.5 4.5v3.1l-1.3 2.4a.6.6 0 0 0 .5.9h10.6a.6.6 0 0 0 .5-.9l-1.3-2.4V8A4.5 4.5 0 0 0 12 3.5Z"
                    fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"
                  />
                  <path
                    d="M9.7 16.9a2.3 2.3 0 0 0 4.6 0"
                    fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"
                  />
                </svg>
              </button>
            </el-badge>
          </template>
          <div class="notice-list">
            <div class="nt-hd">待办通知</div>
            <template v-if="notices.length">
              <button
                v-for="n in notices"
                :key="n.id"
                type="button"
                class="nt-item"
                @click="goNotice(n)"
              >
                <span class="nt-dot" :class="n.key" aria-hidden="true"></span>
                <span class="nt-body">
                  <span class="nt-title">{{ n.title }}</span>
                  <span class="nt-desc">{{ n.desc }}</span>
                </span>
                <span class="nt-go" aria-hidden="true">›</span>
              </button>
            </template>
            <div v-else class="nt-empty">暂无待办通知</div>
          </div>
        </el-popover>
        <!-- 导入 LLM（菜单项「我的 LLM」）：**对所有登录用户开放，不按角色隐藏** ——
     配置已改为「每个用户一份」（后端写 user_llm_config 自己那一行，管理员也只改自己的），
     若按管理员隐藏，普通用户就没有入口配自己的模型。校正于 2026-10-05（原注释写「仅管理员可见」已过期）。 -->
        <el-button link class="llm-entry" @click="llmVisible = true">
          导入 LLM
        </el-button>
        <!-- 右上角显示「这是谁」：用户名为主、角色为辅。
             原来只有角色标签 —— 两个管理员在页面上长得一模一样，
             操作出了问题分不清是谁做的（roleLabel 现降级为徽标）。 -->
        <span class="user-name" :title="userStore.username || ''">{{ displayName }}</span>
        <span class="user-role">{{ roleLabel }}</span>
        <el-button link class="logout" @click="handleLogout">退出</el-button>
      </div>
    </header>

    <div class="layout">
      <aside>
        <nav aria-label="主导航">
          <ul class="menu">
            <template v-for="group in menuGroups" :key="group.title">
              <li class="sec">{{ group.title }}</li>
              <template v-for="item in group.items" :key="item.path">
                <li>
                  <!-- 用 router-link 而非 javascript: 伪链接，保留真实 href 与浏览器导航语义-->
                  <router-link
                    :to="item.path"
                    :class="{ on: isActive(item) }"
                    :aria-current="isActive(item) ? 'page' : undefined"
                  >{{ item.title }}</router-link>
                </li>
                <!-- 子项（如「术语词典 > 术语批量导入」）：父项本身也可点，
                     故不做展开/收起，子项常驻显示 —— 省掉展开状态持久化这一摊复杂度。 -->
                <li v-for="child in item.children" :key="child.path" class="sub">
                  <router-link
                    :to="child.path"
                    :class="{ on: isActive(child) }"
                    :aria-current="isActive(child) ? 'page' : undefined"
                  >{{ child.title }}</router-link>
                </li>
              </template>
            </template>
          </ul>
        </nav>
      </aside>

      <main id="main-content">
        <!-- 面包屑：承载分组与当前位置-->
        <nav v-if="breadcrumb.length" class="crumb" aria-label="面包屑">
          <template v-for="(c, i) in breadcrumb" :key="c">
            <span class="crumb-item">{{ c }}</span>
            <span v-if="i < breadcrumb.length - 1" class="crumb-sep">/</span>
          </template>
        </nav>
        <!-- 每页一个 h1（视觉隐藏），与面板标题 h2 构成层级-->
        <h1 class="visually-hidden">{{ route.meta?.title || '首页看板' }}</h1>
        <router-view />
      </main>
    </div>

    <!-- 全局 AI 助手悬浮窗-->
    <AiAssistant />

    <!-- LLM 运行时配置；属「短平快的一次性配置」，按「短平快的一次性配置」的既有口径用弹窗 -->
    <LlmConfigDialog v-model="llmVisible" />
  </div>
</template>

<script setup>
import { logout as logoutApi } from '@/api/auth'
import { getOverview } from '@/api/stats'
import { governanceStats } from '@/api/governance'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import AiAssistant from '@/components/AiAssistant.vue'
import LlmConfigDialog from '@/components/LlmConfigDialog.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 28.20：登录时的权限快照会过期（owner 改权限 / 移除成员后），进主框架时重取一次
onMounted(() => {
  userStore.refreshOrg()
  loadNotices()
})

// 菜单项全量定义；实际渲染项由登录返回的 menus 过滤，
// 未开发页面显示占位页
// 「术语词典」带子项「术语批量导入」（仅管理员），故支持 children
const ALL_MENUS = [
  { group: '数据处理', title: '首页看板', path: '/dashboard' },
  { group: '数据处理', title: '病历数据', path: '/records' },
  { group: '数据处理', title: '结构化解析', path: '/nlp-extract' },
  { group: '数据处理', title: '质控校验', path: '/qc-check' },
  { group: '数据处理', title: '人工复核', path: '/review' },
  { group: '数据处理', title: '清洗与导出', path: '/governance' },
  { group: '系统配置', title: '术语词典', path: '/dictionary', children: [
    { group: '系统配置', title: '术语批量导入', path: '/dictionary/import' }
  ] },
  // 标准化质量报告（批次 24）：归一与词典质量的可视化，只读
  { group: '系统配置', title: '标准化质量报告', path: '/standardization-report' },
  { group: '系统配置', title: '日志审计', path: '/audit-log' },
  { group: '系统配置', title: '组织管理', path: '/orgs' },
  { group: '系统配置', title: '我的组织', path: '/my-org' }
]

const GROUP_ORDER = ['数据处理', '系统配置']

/** 侧栏高亮：精确匹配当前路径 */
const isActive = (node) => route.path === node.path

/**
 * 菜单由登录响应的 menus 决定（与 AuthServiceImpl.menusOf 一致）；空分组不渲染。
 *
 * <p>批次 17 起 menus 是**树**（带 children）。过滤要分别看父项与子项：
 * 后端给组长/成员的是「父项 + 空 children」，给管理员的是「父项 + 导入子项」，
 * 这里按各自的 title 列表分别过滤，父项没被授权时其子项也不该出现。</p>
 */
const menuGroups = computed(() => {
  const allowed = userStore.menus || []
  const titles = (list) => (Array.isArray(list) ? list : []).map((m) => (typeof m === 'string' ? m : m?.title))
  const allowSet = new Set(titles(allowed))
  return GROUP_ORDER
    .map((title) => ({
      title,
      items: ALL_MENUS
        .filter((m) => m.group === title && allowSet.has(m.title))
        .map((m) => ({
          title: m.title,
          path: m.path,
          // 子项也要在后端授权范围内：父项有、子项没有的（组长）就不显示子项
          children: (m.children || []).filter((c) => allowSet.has(c.title))
        }))
    }))
    .filter((group) => group.items.length > 0)
})

// 面包屑：所属分组 + 当前页标题；子项额外带上父项（「术语词典 / 术语批量导入」）
const breadcrumb = computed(() => {
  const title = route.meta?.title
  if (!title) return []
  const all = ALL_MENUS.flatMap((m) => [m, ...(m.children || [])])
  const hit = all.find((m) => m.title === title)
  if (!hit) return [title]
  // 命中的是子项：返回 [分组, 父项, 子项]
  const isChild = ALL_MENUS.some((m) => (m.children || []).some((c) => c.title === title))
  if (isChild) {
    const parent = ALL_MENUS.find((m) => (m.children || []).some((c) => c.title === title))
    return [hit.group, parent.title, title]
  }
  return [hit.group, title]
})

/**
 * 「我的 LLM」入口对所有登录用户开放。
 *
 * <p>配置已改为<b>每个用户一份</b>（后端写 user_llm_config 自己那一行），管理员也只改自己的 ——
 * 所以这里不能再按管理员隐藏，否则普通用户没有入口去配自己的模型。</p>
 */
const isAdmin = computed(() => userStore.role === '管理员')
// 右上角主标识：优先用户名；旧数据（未重登录、localStorage 里没有 username）
// 回退到角色标签，避免出现空白 —— 直接改版上线时老会话不会崩。
const displayName = computed(() => userStore.username || roleLabel.value)

// 身份下标：管理员 / 所有者 / 成员 / 未加入组织（现降级为徽标）
const roleLabel = computed(() => {
  if (userStore.role === '管理员') return '管理员'
  if (userStore.orgRole === 'owner') return '所有者'
  if (userStore.hasOrg) return '成员'
  return '未加入组织'
})
const llmVisible = ref(false)

// ===== 28.18 全局通知中心 =====
// 通知条目由概览接口实时推导，id 带上数量：数量一变就是新通知，徽标自动重新亮起。
// 只列真有待办的三类；数量为 0 不制造噪音。
const noticeVisible = ref(false)
const notices = ref([])
const NOTICE_READ_KEY = 'tcm:noticeRead'
const readNoticeIds = ref([])
const unreadNotices = computed(() => notices.value.filter((n) => !readNoticeIds.value.includes(n.id)))

const loadReadNoticeIds = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(NOTICE_READ_KEY) || '[]')
    readNoticeIds.value = Array.isArray(raw) ? raw : []
  } catch {
    readNoticeIds.value = []
  }
}

// 打开铃铛即视为已读（通知不做逐条已读交互，避免为三类聚合指标过度设计）
const markNoticesRead = () => {
  const ids = notices.value.map((n) => n.id)
  if (!ids.length) return
  readNoticeIds.value = [...new Set([...readNoticeIds.value, ...ids])].slice(-50)
  try {
    localStorage.setItem(NOTICE_READ_KEY, JSON.stringify(readNoticeIds.value))
  } catch {
    // 存不下（隐私模式/配额满）就只在本次会话内生效
  }
}

// 概览数据：待复核 / 无效数据对所有用户可见；待清洗仅管理员可读（后端限制）
const loadNotices = async () => {
  loadReadNoticeIds()
  const list = []
  try {
    const res = await getOverview()
    const pendingReview = res.data?.pendingReviewCount || 0
    const invalid = res.data?.invalidCount || 0
    if (pendingReview > 0) {
      list.push({
        key: 'review',
        count: pendingReview,
        title: `${pendingReview} 份病历待复核`,
        desc: '质控已判定需要人工确认，点击进入人工复核',
        path: '/review'
      })
    }
    if (invalid > 0) {
      list.push({
        key: 'invalid',
        count: invalid,
        title: `${invalid} 份病历判定无效`,
        desc: '质控判定无有效内容，点击在病历数据中筛选查看',
        path: '/records',
        query: { grade: '无效' }
      })
    }
  } catch {
    // 拦截器已提示；通知失败不该影响主框架使用
  }
  // 待清洗：非管理员后端会拒绝，用 try 兜住，静默跳过
  if (isAdmin.value) {
    try {
      const g = await governanceStats()
      const pendingGovern = g.data?.pendingGovern || 0
      if (pendingGovern > 0) {
        list.push({
          key: 'govern',
          count: pendingGovern,
          title: `${pendingGovern} 份病历待清洗`,
          desc: '点击进入清洗与导出，按步骤执行数据清洗',
          path: '/governance'
        })
      }
    } catch {
      // 后端限制或失败：不展示该类通知
    }
  }
  notices.value = list.map((n) => ({ ...n, id: `${n.key}:${n.count}` }))
}

const goNotice = (n) => {
  noticeVisible.value = false
  // 已经在目标页时 router.push 不会重新触发页面加载，这里补一次强制刷新
  const target = { path: n.path, query: n.query }
  if (route.path === n.path) {
    router.replace({ query: n.query || {} })
    window.location.reload()
    return
  }
  router.push(target)
}

// 退出登录：先让服务端作废令牌，再清本地状态并跳回登录页
// ⚠️ 顺序不能反：本地先清了就拿不到 token，服务端无法作废；
// 而服务端不通知的话，那张 JWT 在 24h 内仍有效，复制到别的浏览器照样能调接口。
const handleLogout = async () => {
  try {
    await logoutApi()
  } catch {
    // 登出接口失败不阻断登出：本地状态照清，否则用户会被困在已登录态
  } finally {
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
/* 低于该宽度侧栏与主区会互相挤压，改为横向滚动 */
.app-shell {
  min-width: 1024px;
}

/* 跳转链接：默认视觉隐藏，键盘聚焦时显现 */
.skip-link {
  position: absolute;
  left: -9999px;
  top: 0;
  z-index: 2000;
  padding: var(--sp-2) var(--sp-4);
  background: var(--ink);
  color: var(--surface);
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
  color: var(--surface);
}
.brand {
  font-size: var(--fs-title);
  letter-spacing: 1px;
}
.brand em {
  font-style: normal;
  color: #c9b99a;
  margin-left: var(--sp-2);
  font-size: var(--fs-xs);
}
.topbar .user {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--fs-base);
  color: #d8dfd9;
}
/* 导入 LLM 入口：与「退出」同为顶栏次级操作，样式保持一致 */
.llm-entry {
  color: #d8dfd9;
  font-size: var(--fs-base);
}
.llm-entry:hover {
  color: var(--surface);
}
/* 通知铃铛：顶栏深色底上的图标按钮，与 llm-entry 同一浅色处理 */
.notice-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #d8dfd9;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.notice-btn:hover,
.notice-btn:focus-visible {
  color: var(--surface);
  background: rgba(255, 255, 255, 0.14);
}
/* 通知面板（el-popover 内容在 body 下，但 popover 内容随组件渲染，scoped 仍生效） */
.notice-list {
  max-height: 320px;
  overflow-y: auto;
}
.nt-hd {
  font-size: var(--fs-xs);
  color: var(--text-sub);
  padding-bottom: var(--sp-1);
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: var(--sp-1);
}
.nt-item {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  width: 100%;
  padding: var(--sp-2);
  border: none;
  border-radius: 4px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}
.nt-item:hover {
  background: var(--ink-light);
}
.nt-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--ink-mid);
}
.nt-dot.review { background: var(--ochre); }
.nt-dot.invalid { background: var(--danger); }
.nt-body {
  flex: 1;
  min-width: 0;
}
.nt-title {
  display: block;
  font-size: var(--fs-base);
  color: var(--ink);
  line-height: 1.4;
}
.nt-desc {
  display: block;
  font-size: var(--fs-xs);
  color: var(--text-sub);
  line-height: 1.4;
}
.nt-go {
  flex: none;
  color: var(--text-sub);
  font-size: var(--fs-base);
}
.nt-empty {
  padding: var(--sp-3) 0;
  text-align: center;
  color: var(--text-sub);
  font-size: var(--fs-base);
}
/* 顶栏是深色底（.topbar background: var(--ink)），所以这里必须用浅色 ——
   之前按浅底习惯写了 color: var(--ink)，等于深绿字压深绿底，用户完全看不清。 */
.user-name {
  font-weight: 600;
  color: var(--el-color-primary-light-9);          /* 近白：与 --ink 底对比度 ≈ 12:1 */
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-role {
  padding: var(--sp-1) var(--sp-2);
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 2px;
  font-size: var(--fs-xs);
  color: #dbe4de;          /* 徽标比用户名弱一档，与 --surface(var(--surface)) 区分 */
}
.logout {
  color: #d8dfd9;
}
.logout:hover {
  color: var(--surface);
}

/* ===== 布局（原型 layout） ===== */
/* 固定视口：顶栏 + 布局占满整屏高，长页只在 main 内部滚动 —— 文档层不再出现上下滚动条，
   页面不会被挤窄/左移 */
.layout {
  display: flex;
  height: calc(100vh - 52px);
  overflow: hidden;
}
aside {
  width: 176px;
  background: var(--el-table-header-bg-color);
  border-right: 1px solid var(--line);
  flex-shrink: 0;
  overflow-y: auto;
}
.menu {
  list-style: none;
  padding-top: var(--sp-2);
}
.menu a {
  display: block;
  padding: var(--sp-3) var(--sp-4);
  color: var(--el-text-color-regular);
  text-decoration: none;
  font-size: var(--fs-base);
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
  padding: var(--sp-4) var(--sp-4) var(--sp-1);
  font-size: var(--fs-xs);
  color: var(--text-sub);
}

/* 子菜单（如「术语批量导入」）：缩进一级 + 稍小字号，视觉上从属于父项。
   父项本身也可点，故不做展开/收起，子项常驻。 */
.menu .sub a {
  padding-left: var(--sp-5);
  font-size: var(--fs-base);
  color: var(--text-sub);
}
.menu .sub a:hover {
  background: var(--ink-light);
  color: var(--ink);
}
.menu .sub a.on {
  background: var(--ink-light);
  color: var(--ink);
  border-left-color: var(--ink-mid);
  font-weight: bold;
}

main {
  flex: 1;
  min-width: 0;
  /* 长页内部滚动：main 铺满整宽，滚动条贴窗最右（不再因内容居中而偏左）；
     文档层不出现滚动条 → 通栏且切页不偏移 */
  overflow-y: auto;
  padding: var(--sp-4) 20px 84px;
  /* 给右下角 AI 助手悬浮球留出安全间距，避免遮挡表格底部内容 */
}
/* 内层内容仍限宽居中，但滚动容器保持通宽 */
main > * {
  max-width: 1600px /* P4.18：与设计稿一致，超出横向留白不拉伸数据区；改动此值需两档视口实测 */;
  margin: 0 auto;
}

/* ===== 面包屑===== */
.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: var(--sp-3);
  font-size: var(--fs-xs);
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
