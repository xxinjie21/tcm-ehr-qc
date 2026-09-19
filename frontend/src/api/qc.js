import request from '@/utils/request'

// 质控检验图谱（批D·3.3）：全库/范围内聚合；参数 department/start/end/pattern/grade
export function getGraph(params) {
  return request.get('/qc/graph', { params })
}

// 单条预检评分（扣分明细）：POST /qc/score { recordId }
export function qcScore(data) {
  return request.post('/qc/score', data)
}
