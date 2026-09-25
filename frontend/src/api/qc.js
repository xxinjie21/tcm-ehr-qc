import request from '@/utils/request'

// 单条预检评分（扣分明细）：POST /qc/score { recordId }
export function qcScore(data) {
  return request.post('/qc/score', data)
}

// 质控评分标准（批P）：只读下发口径与逻辑规则
export function getQcRules() {
  return request.get('/qc/rules')
}

// 范围扣分维度聚合（批P）：参数 department/start/end/pattern/grade
export function getDeductionStats(params) {
  return request.get('/qc/deduction-stats', { params })
}
