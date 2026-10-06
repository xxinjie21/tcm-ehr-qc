import { defineStore } from 'pinia'
import { getMyOrg } from '@/api/org'

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
    // 用户名：右上角显示「这是谁」。只显示 role 的话两个管理员长得一模一样。
    username: localStorage.getItem('username') || '',
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
    // 词典写入口：管理员 / 所有者 / 被授权成员
    canWriteDictionaryEntry: (s) =>
      s.role === '管理员' || s.orgRole === 'owner' || s.canWriteDictionary,
    // 质控规则写入口：管理员 / 所有者 / 被授权成员
    canWriteQcRulesEntry: (s) =>
      s.role === '管理员' || s.orgRole === 'owner' || s.canWriteQcRules
  },

  actions: {
    setLogin({ token, username, role, menus, orgId = '', orgRole = '', status = '',
      pendingGroup = false, canWriteDictionary = false, canWriteQcRules = false }) {
      this.token = token
      this.username = username || ''
      this.role = role
      this.menus = menus || []
      this.orgId = orgId || ''
      this.orgRole = orgRole || ''
      this.status = status || ''
      this.pendingGroup = !!pendingGroup
      this.canWriteDictionary = !!canWriteDictionary
      this.canWriteQcRules = !!canWriteQcRules
      localStorage.setItem('token', this.token)
      localStorage.setItem('username', this.username)
      localStorage.setItem('role', this.role)
      localStorage.setItem('menus', JSON.stringify(this.menus))
      localStorage.setItem('orgId', this.orgId)
      localStorage.setItem('orgRole', this.orgRole)
      localStorage.setItem('status', this.status)
      localStorage.setItem('pendingGroup', this.pendingGroup ? '1' : '0')
      localStorage.setItem('canWriteDictionary', this.canWriteDictionary ? '1' : '0')
      localStorage.setItem('canWriteQcRules', this.canWriteQcRules ? '1' : '0')
    },
    /**
     * 28.20：向服务端重取组织上下文与两个写授权位。
     *
     * <p>登录时把权限快照进了 localStorage，owner 之后改你的权限 / 把你移出组织，
     * 本机在重新登录前一直显示旧状态 —— 连刷新页面也不行。进主框架时调一次即可让刷新生效。
     * 纯体验修正，不是鉴权：失败（含无 token）就保持原状，服务端每请求仍会重新解析组织。</p>
     */
    async refreshOrg() {
      try {
        const res = await getMyOrg()
        const data = res.data || {}
        this.orgId = data.org?.id || ''
        this.orgRole = data.myRole || ''
        this.canWriteDictionary = !!data.canWriteDictionary
        this.canWriteQcRules = !!data.canWriteQcRules
        localStorage.setItem('orgId', this.orgId)
        localStorage.setItem('orgRole', this.orgRole)
        localStorage.setItem('canWriteDictionary', this.canWriteDictionary ? '1' : '0')
        localStorage.setItem('canWriteQcRules', this.canWriteQcRules ? '1' : '0')
      } catch {
        // 保持登录时的快照
      }
    },
    logout() {
      this.token = ''
      this.username = ''
      this.role = ''
      this.menus = []
      this.orgId = ''
      this.orgRole = ''
      this.status = ''
      this.pendingGroup = false
      this.canWriteDictionary = false
      this.canWriteQcRules = false
      localStorage.removeItem('token')
      localStorage.removeItem('username')
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