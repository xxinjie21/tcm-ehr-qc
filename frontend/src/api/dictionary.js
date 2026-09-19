import request from '@/utils/request'

export function importDict(formData) {
  return request.post('/dictionary/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}

// PDF 智能转换（LLM 即 ETL）：只返回候选预览，不落库；LLM 调用可能较慢故超时放宽
export function convertDict(formData) {
  return request.post('/dictionary/convert', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 180000
  })
}

export function getTerms(params) {
  return request.get('/dictionary/terms', { params })
}

export function rollback(data) {
  return request.post('/dictionary/rollback', data)
}

export function getBackups(params) {
  return request.get('/dictionary/backups', { params })
}
