import request from '@/utils/request'

export function getOverview() {
  return request.get('/stats/overview')
}

export function getStats(data) {
  return request.post('/stats', data)
}

// 科室动态选项
export function getDepartments() {
  return request.get('/stats/departments')
}

// 看板扩展：趋势 / 科室合格率 / 评分分布 / 词典规模
export function getExtraStats(params) {
  return request.get('/stats/extra', { params })
}
