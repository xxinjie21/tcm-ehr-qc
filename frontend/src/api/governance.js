import request from '@/utils/request'

export function normalize(data) {
  return request.post('/governance/normalize', data, { timeout: 60000 })
}

// 清洗为全库/范围同步重活（500 条约 15s，数据更多会更久），放宽超时避免被 30s 默认值中断
export function clean(data) {
  return request.post('/governance/clean', data, { timeout: 300000 })
}

// 导出走文件流，数据量大时远超默认 30s，单独放宽超时（UX-04）
export function exportDataset(data) {
  return request.post('/export/dataset', data, { responseType: 'blob', timeout: 200000 })
}

export function previewDataset(data) {
  return request.post('/export/dataset/preview', data, { timeout: 60000 })
}

export function governanceStats() {
  return request.get('/governance/stats')
}
