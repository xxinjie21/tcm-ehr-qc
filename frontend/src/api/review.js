import request from '@/utils/request'

// 待复核任务列表：page/pageSize/status（待复核|已完成）
export function listReviewTasks(params) {
  return request.get('/review/tasks', { params })
}

// 人工校正与复核：correctedData 可空（仅裁定）
export function submitReview(recordId, data) {
  return request.post(`/records/${recordId}/review`, data)
}
