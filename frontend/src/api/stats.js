import request from '@/utils/request'

export function getOverview() {
  return request.get('/stats/overview')
}

export function getStats(data) {
  return request.post('/stats', data)
}
