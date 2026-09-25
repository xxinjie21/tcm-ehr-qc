import request from '@/utils/request'

// 单条预检评分（扣分明细）：POST /qc/score { recordId }
export function qcScore(data) {
  return request.post('/qc/score', data)
}

// 质控规则（批P/Q）：读取 / 保存 / 恢复默认（{rules, warnings}）
export function getQcRules() {
  return request.get('/qc/rules')
}
export function updateQcRules(rules) {
  return request.put('/qc/rules', rules, { timeout: 60000 })
}
export function resetQcRules() {
  return request.post('/qc/rules/reset')
}

// 范围扣分维度聚合（批P）：参数 department/start/end/pattern/grade
export function getDeductionStats(params) {
  return request.get('/qc/deduction-stats', { params })
}
