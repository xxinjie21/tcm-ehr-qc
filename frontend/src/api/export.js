import request from '@/utils/request'

// 数据集导出（打在 /api/export/* 上，由后端 GovernanceController 承载）。
// 单列一个模块的理由：本目录按「接口路径域」分模块（log↔/api/logs、stats↔/api/stats、
// qc↔/api/qc…），/export/* 与 /governance/* 是两个路径域，放一起会让
// 「找不到导出接口在哪个模块」变成常态。

// 导出走文件流，数据量大时远超默认 30s，单独放宽超时
export function exportDataset(data) {
  return request.post('/export/dataset', data, { responseType: 'blob', timeout: 200000 })
}

export function previewDataset(data) {
  return request.post('/export/dataset/preview', data, { timeout: 60000 })
}
