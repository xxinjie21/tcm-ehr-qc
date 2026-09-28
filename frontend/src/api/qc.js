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

// ===== 质控批量重算（§七 L5/L6：异步任务，4 个接口）=====
// 提交：返回 taskId，不等结果（原先是长耗时同步接口，前端要放宽到 200s 超时）
export function recomputeQc(data) {
  return request.post('/qc/score/batch', data)
}

// 任务列表（最近 50 条，不含失败明细）
export function listQcBatch() {
  return request.get('/qc/score/batch')
}

// 任务进度 + 分级汇总（含失败明细）；前端 2s 轮询本接口
export function getQcBatch(id) {
  return request.get(`/qc/score/batch/${id}`)
}

// 取消任务：排队中的直接落终态，运行中的置取消位由 worker 自行收尾
export function cancelQcBatch(id) {
  return request.post(`/qc/score/batch/${id}/cancel`)
}
