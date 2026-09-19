import request from '@/utils/request'

export function getOverview() {
  return request.get('/stats/overview')
}

export function getStats(data) {
  return request.post('/stats', data)
}

// 科室动态选项（批B·4.1 U11）
export function getDepartments() {
  return request.get('/stats/departments')
}
