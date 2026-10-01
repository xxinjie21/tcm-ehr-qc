import request from '@/utils/request'

export function login(data) {
  return request.post('/auth/login', data)
}

// 用户注册（角色固定为「用户」，后端不接收 role）
export function register(data) {
  return request.post('/auth/register', data)
}

// 退出登录：让服务端作废该用户全部已签发的令牌
// 不调这个的话，那张 JWT 在有效期内仍可用 —— 复制到别的浏览器照样能调接口
export function logout() {
  return request.post('/auth/logout')
}
