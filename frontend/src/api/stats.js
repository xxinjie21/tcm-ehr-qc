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

// 看板一次拉取：指标卡 + 4 类统计（批C·4.2 U6）
export function getAllStats(params) {
  return request.get('/stats/all', { params })
}

// 看板扩展：趋势 / 科室合格率 / 评分分布 / 词典规模（批C·4.2）
export function getExtraStats(params) {
  return request.get('/stats/extra', { params })
}
