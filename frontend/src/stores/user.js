import { defineStore } from 'pinia'

/**
 * 登录态（阶段2 起带课题组上下文）。
 *
 * <p>groupId / groupRole / status / pendingGroup 随登录带出，<b>仅供前端渲染</b>
 * （菜单、引导页文案）；服务端每请求由 JwtInterceptor 重新解析组，
 * 客户端改 localStorage 无效 —— 不要在这里做任何鉴权逻辑。</p>
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    role: localStorage.getItem('role') || '',
    menus: JSON.parse(localStorage.getItem('menus') || '[]'),
    groupId: localStorage.getItem('groupId') || '',
    groupRole: localStorage.getItem('groupRole') || '',
    status: localStorage.getItem('status') || '',
    pendingGroup: localStorage.getItem('pendingGroup') === '1'
  }),

  getters: {
    isAdmin: (s) => s.role === '管理员',
    hasGroup: (s) => !!s.groupId && s.groupId !== ''
  },

  actions: {
    setLogin({ token, role, menus, groupId = '', groupRole = '', status = '', pendingGroup = false }) {
      this.token = token
      this.role = role
      this.menus = menus || []
      this.groupId = groupId || ''
      this.groupRole = groupRole || ''
      this.status = status || ''
      this.pendingGroup = !!pendingGroup
      localStorage.setItem('token', this.token)
      localStorage.setItem('role', this.role)
      localStorage.setItem('menus', JSON.stringify(this.menus))
      localStorage.setItem('groupId', this.groupId)
      localStorage.setItem('groupRole', this.groupRole)
      localStorage.setItem('status', this.status)
      localStorage.setItem('pendingGroup', this.pendingGroup ? '1' : '0')
    },
    logout() {
      this.token = ''
      this.role = ''
      this.menus = []
      this.groupId = ''
      this.groupRole = ''
      this.status = ''
      this.pendingGroup = false
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('menus')
      localStorage.removeItem('groupId')
      localStorage.removeItem('groupRole')
      localStorage.removeItem('status')
      localStorage.removeItem('pendingGroup')
    }
  }
})