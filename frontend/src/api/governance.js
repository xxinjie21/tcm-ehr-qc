import request from '@/utils/request'

export function normalize(data) {
  return request.post('/governance/normalize', data)
}

export function clean(data) {
  return request.post('/governance/clean', data)
}

export function exportDataset(data) {
  return request.post('/export/dataset', data, { responseType: 'blob' })
}

export function previewDataset(data) {
  return request.post('/export/dataset/preview', data)
}

export function governanceStats() {
  return request.get('/governance/stats')
}
