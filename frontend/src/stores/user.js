import { defineStore } from 'pinia'

/**
 * 登录态（阶段2 起带组织上下文）。
 *
 * <p>orgId / orgRole / status / pendingGroup 随登录带出，<b>仅供前端渲染</b>
 * （菜单、引导页文案）；服务端每请求由 JwtInterceptor 重新解析组织，
 * 客户端改 localStorage 无效 —— 不要在这里做任何鉴权逻辑。</p>
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    role: localStorage.getItem('role') || '',
    menus: JSON.parse(localStorage.getItem('menus') || '[]'),
    orgId: localStorage.getItem('orgId') || '',
    orgRole: localStorage.getItem('orgRole') || '',
    status: localStorage.getItem('status') || '',
    pendingGroup: localStorage.getItem('pendingGroup') === '1',
    // 词典 / 质控规则的写授权位：后端下发，默认 false（无授权时前端不显示写入口）
    canWriteDictionary: localStorage.getItem('canWriteDictionary') === '1',
    canWriteQcRules: localStorage.getItem('canWriteQcRules') === '1'
  }),

  getters: {
    isAdmin: (s) => s.role === '管理员',
    hasOrg: (s) => !!s.orgId && s.orgId !== '',
    isOrgOwner: (s) => s.orgRole === 'owner',
    /** 词典写入口：管理员 / 所有者 / 被授权成员 */
    canWriteDictionaryEntry: (s) =>
      s.role === '管理员' || s.orgRole === 'owner' || s.canWriteDictionary,
    /** 质控规则写入口：管理员 / 所有者 / 被授权成员 */
    canWriteQcRulesEntry: (s) =>
      s.role === '管理员' || s.orgRole === 'owner' || s.canWriteQcRules
  },

  actions: {
    setLogin({ token, role, menus, orgId = '', orgRole = '', status = '', pendingGroup = false,
      canWriteDictionary = false, canWriteQcRules = false }) {
      this.token = token
      this.role = role
      this.menus = menus || []
      this.orgId = orgId || ''
      this.orgRole = orgRole || ''
      this.status = status || ''
      this.pendingGroup = !!pendingGroup
      this.canWriteDictionary = !!canWriteDictionary
      this.canWriteQcRules = !!canWriteQcRules
      localStorage.setItem('token', this.token)
      localStorage.setItem('role', this.role)
      localStorage.setItem('menus', JSON.stringify(this.menus))
      localStorage.setItem('orgId', this.orgId)
      localStorage.setItem('orgRole', this.orgRole)
      localStorage.setItem('status', this.status)
      localStorage.setItem('pendingGroup', this.pendingGroup ? '1' : '0')
      localStorage.setItem('canWriteDictionary', this.canWriteDictionary ? '1' : '0')
      localStorage.setItem('canWriteQcRules', this.canWriteQcRules ? '1' : '0')
    },
    logout() {
      this.token = ''
      this.role = ''
      this.menus = []
      this.orgId = ''
      this.orgRole = ''
      this.status = ''
      this.pendingGroup = false
      this.canWriteDictionary = false
      this.canWriteQcRules = false
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('menus')
      localStorage.removeItem('orgId')
      localStorage.removeItem('orgRole')
      localStorage.removeItem('status')
      localStorage.removeItem('pendingGroup')
      localStorage.removeItem('canWriteDictionary')
      localStorage.removeItem('canWriteQcRules')
    }
  }
})