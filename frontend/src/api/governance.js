import request from '@/utils/request'

export function normalize(data) {
  return request.post('/governance/normalize', data, { timeout: 60000 })
}

// 同步清洗（保留兼容）。新代码走下方异步接口：可看真实进度、可停止。
export function clean(data) {
  return request.post('/governance/clean', data, { timeout: 300000 })
}

// 提交异步清洗任务：立即返回任务 ID，进度靠轮询
export function submitCleanBatch(data) {
  return request.post('/governance/clean/batch', data)
}

// 查清洗任务进度
export function getCleanBatch(id) {
  return request.get('/governance/clean/batch/' + id)
}

// 最近清洗任务列表（页面加载时据此恢复「运行中」任务，避免刷新后看不到进度）
export function listCleanBatch() {
  return request.get('/governance/clean/batch')
}

// 请求取消清洗任务
export function cancelCleanBatch(id) {
  return request.post('/governance/clean/batch/' + id + '/cancel')
}

// 导出与预览在 @/api/export（路径域是 /export/*，不属于本模块）

export function governanceStats() {
  return request.get('/governance/stats')
}
