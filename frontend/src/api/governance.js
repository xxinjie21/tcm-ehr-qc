import request from '@/utils/request'

export function normalize(data) {
  return request.post('/governance/normalize', data, { timeout: 60000 })
}

// 清洗为全库/范围同步重活（500 条约 15s，数据更多会更久），放宽超时避免被 30s 默认值中断
export function clean(data) {
  return request.post('/governance/clean', data, { timeout: 300000 })
}

// 导出与预览在 @/api/export（路径域是 /export/*，不属于本模块）

export function governanceStats() {
  return request.get('/governance/stats')
}
