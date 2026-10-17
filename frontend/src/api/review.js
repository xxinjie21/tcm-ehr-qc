import request from '@/utils/request'

// 待复核任务列表：page/pageSize/status（待复核|已完成）/overdueOnly；
// 范围参数 department/start/end/importStart/importEnd（两端齐全才生效，与病历数据、质控等页
// 同一套 RangeFilter 口径）；config 透传 axios 配置（如 { signal }）
export function listReviewTasks(params, config = {}) {
  return request.get('/review/tasks', { params, ...config })
}

// 复核概览统计：待复核 / 已完成 / 待复核超期 三个数一次返回（替代三次 pageSize=1，性能审查 P1-5）。
// 收与列表同一套范围参数 —— 卡片是「点进去看到的队列」的入口，数字必须与点击后的列表一致
export function getReviewStats(params = {}) {
  return request.get('/review/tasks/count', { params })
}

// 复核提交（POST /records/{id}/review）按「接口路径域」放在 @/api/records：
// 本目录以路径域分模块，把 /records/* 的调用留在这里会让同一路径域的调用散在两处。
