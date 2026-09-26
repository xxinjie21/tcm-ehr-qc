import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    role: localStorage.getItem('role') || '',
    menus: JSON.parse(localStorage.getItem('menus') || '[]')
  }),
  actions: {
    setLogin({ token, role, menus }) {
      // 1. 写入内存 state
      this.token = token
      this.role = role
      this.menus = menus
      // 2. 持久化到 localStorage
      localStorage.setItem('token', token)
      localStorage.setItem('role', role)
      localStorage.setItem('menus', JSON.stringify(menus))
    },
    logout() {
      // 1. 清空内存 state
      this.token = ''
      this.role = ''
      this.menus = []
      // 2. 移除 localStorage 持久化
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('menus')
    }
  }
})
