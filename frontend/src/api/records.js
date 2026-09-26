import request from '@/utils/request'

// 病历批量导入（多文件，Excel 解析 + 去重入库可能较慢，超时放宽）
export function importRecords(formData) {
  return request.post('/records/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000
  })
}

export function createRecord(data) {
  return request.post('/records', data)
}

// F·7.2 原始病历只读查看
export function getRawRecord(recordId) {
  return request.get(`/records/raw/${recordId}`)
}

// F·7.3 修改（仅 structuredData）/ 批量删除
export function updateRecord(recordId, data) {
  return request.put(`/records/${recordId}`, data)
}

export function deleteRecords(ids) {
  return request.delete('/records', { data: { ids } })
}

// 按筛选范围批量删除（条件全空后端拒绝，防误删全库）
export function deleteRecordsByFilter(filters) {
  return request.post('/records/delete-by-filter', filters, { timeout: 200000 })
}

// F·7.4 多条件分页查询
export function searchRecords(data) {
  return request.post('/records/search', data)
}
