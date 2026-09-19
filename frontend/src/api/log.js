import request from '@/utils/request'

export function getLogs(params) {
  return request.get('/logs', { params })
}

// 操作类型选项：取库中实际出现过的值，避免前端写死清单与后端漂移（UX-19）
export function getLogActions() {
  return request.get('/logs/actions')
}

// 导出走文件流，数据量大时远超默认 30s，单独放宽超时（UX-04）
export function exportLogs(params) {
  return request.get('/logs/export', { params, responseType: 'blob', timeout: 200000 })
}
