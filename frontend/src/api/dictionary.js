import request from '@/utils/request'

export function importDict(formData) {
  return request.post('/dictionary/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
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
