import request from '@/utils/request'

export function login(data) {
  return request.post('/auth/login', data)
}

// 用户注册（角色固定为审核员，后端不接收 role）
export function register(data) {
  return request.post('/auth/register', data)
}
