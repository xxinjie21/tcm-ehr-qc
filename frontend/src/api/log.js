import request from '@/utils/request'

export function getLogs(params) {
  return request.get('/logs', { params })
}

export function exportLogs(params) {
  return request.get('/logs/export', { params, responseType: 'blob' })
}
