import request from '@/utils/request'

// 单条预检评分（扣分明细）：POST /qc/score { recordId }
export function qcScore(data) {
  return request.post('/qc/score', data)
}

// 质控规则：读取 / 保存 / 恢复默认（{rules, warnings}）
export function getQcRules() {
  return request.get('/qc/rules')
}
export function updateQcRules(rules) {
  return request.put('/qc/rules', rules, { timeout: 60000 })
}
export function resetQcRules() {
  return request.post('/qc/rules/reset')
}

// 范围扣分维度聚合：参数 department/start/end/pattern/grade
export function getDeductionStats(params) {
  return request.get('/qc/deduction-stats', { params })
}

// 质控评分重算（全库/范围内，长耗时同步接口）
export function recomputeQc(data) {
  return request.post('/qc/score/batch', data, { timeout: 200000 })
}
