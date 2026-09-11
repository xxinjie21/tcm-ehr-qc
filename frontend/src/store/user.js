import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    role: localStorage.getItem('role') || '',
    menus: JSON.parse(localStorage.getItem('menus') || '[]')
  }),
  actions: {
    setLogin({ token, role, menus }) {
      this.token = token
      this.role = role
      this.menus = menus
      localStorage.setItem('token', token)
      localStorage.setItem('role', role)
      localStorage.setItem('menus', JSON.stringify(menus))
    },
    logout() {
      this.token = ''
      this.role = ''
      this.menus = []
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('menus')
    }
  }
})
