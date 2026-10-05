import request from '@/utils/request'

export function getLogs(params) {
  return request.get('/logs', { params })
}

// 操作类型选项：取库中实际出现过的值，避免前端写死清单与后端漂移
export function getLogActions() {
  return request.get('/logs/actions')
}

// 导出走文件流，数据量大时远超默认 30s，单独放宽超时
export function exportLogs(params) {
  return request.get('/logs/export', { params, responseType: 'blob', timeout: 200000 })
}

// 按对象查活动流（对标 D3）：病历详情页的「活动」页签用它。
// 后端按 (org_id, object_type, object_id) 建索引，且复用审计页同一套可见范围。
export function getObjectLogs({ objectType, objectId, page = 1, pageSize = 20 }) {
  return request.get('/logs/by-object', { params: { objectType, objectId, page, pageSize } })
}
