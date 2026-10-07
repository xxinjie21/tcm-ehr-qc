import request from '@/utils/request'

// 待复核任务列表：page/pageSize/status（待复核|已完成）；config 透传 axios 配置（如 { signal }）
export function listReviewTasks(params, config = {}) {
  return request.get('/review/tasks', { params, ...config })
}

// 复核提交（POST /records/{id}/review）按「接口路径域」放在 @/api/records：
// 本目录以路径域分模块，把 /records/* 的调用留在这里会让同一路径域的调用散在两处。
