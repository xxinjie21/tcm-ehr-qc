import request from '@/utils/request'

// 首页指标卡。params 可传 { department, start, end }：3 张卡（总数/合格率/无效）随筛选收窄，
// 不传即全量口径（顶栏铃铛等无参调用不受影响）。待复核数恒为全量。
export function getOverview(params) {
  return request.get('/stats/overview', { params })
}

// 科室动态选项
// silent：useDepartments 失败后回退 []（下拉变空但不阻断手输），
// 属于后台取数，失败只在页面内降级，不弹全局红条
export function getDepartments() {
  return request.get('/stats/departments', { silent: true })
}

// 看板扩展：趋势 / 科室合格率 / 评分分布 / 词典规模
export function getExtraStats(params) {
  return request.get('/stats/extra', { params })
}

// 标准化质量报告（批次 24）：甲类=标准符合度（目标口径），乙类=数据集覆盖度（仅下限验证）
// params 可传 { start, end }（接诊时间 yyyy-MM-dd）；不传即全部区间
export function getStandardizationReport(params) {
  return request.get('/stats/standardization-report', { params })
}

// 导出标准化质量报告 CSV（28.23）：后端生成文件流，与数据集/日志导出口径一致
export function exportStandardizationReport(params) {
  return request.get('/stats/standardization-report/export', { params, responseType: 'blob', timeout: 200000 })
}
