import request from '@/utils/request'

// NLP 实体抽取（转发 Python 服务，模型推理可能较慢，超时放宽）
export function extractNlp(data) {
  return request.post('/nlp/extract', data, { timeout: 60000 })
}

// 批量解析：提交后台任务（仅管理员）
export function submitNlpBatch(data) {
  return request.post('/nlp/extract/batch', data, { timeout: 60000 })
}

// 批量任务进度
export function getNlpBatchProgress(id) {
  return request.get(`/nlp/extract/batch/${id}`)
}

// 取消批量任务
export function cancelNlpBatch(id) {
  return request.post(`/nlp/extract/batch/${id}/cancel`)
}

// 抽取服务健康探测：只问「开没开、在不在、模型有没有加载」，不发抽取请求。
// 探测在后端不会失败（最坏回一份「连不上」）；超时给短一点，别让首屏被它拖住。
export function getNlpHealth() {
  return request.get('/nlp/health', { timeout: 5000 })
}

// 批量任务列表（最近 50 条）
export function listNlpBatch() {
  return request.get('/nlp/extract/batch')
}
