import request from '@/utils/request'

// 术语词典 API（批次 17）
//
// 写基线只有两条路：
//   ① importDict —— 管理员直写特权通道，不生成提案，但会生成归档版本
//   ② 提交提案 → 组长审核通过后合并
// 普通成员没有第三条路；本地的修改不会自动同步回小组基线。
//
// 原 rollback / getBackups 已随 dictionary_backups 表废弃（回滚改为
// 「基于归档版本生成提案」，历史列表改为 archives），故不再提供。

/** 管理员直写导入；target=base 写基础层，缺省写当前组织 */
export function importDict(formData, target) {
  return request.post('/dictionary/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    params: target ? { target } : undefined,
    timeout: 120000
  })
}

/** 词典分页查询 / 输入联想（不传 page 返回全部命中） */
export function getTerms(params) {
  return request.get('/dictionary/terms', { params })
}

/** 导出小组基线全量词条，供存成本地个人词典 */
export function exportBaseline(params) {
  return request.get('/dictionary/baseline/export', { params })
}

/** 提交基线更新提案（携带完整目标词典） */
export function submitProposal(data) {
  return request.post('/dictionary/proposals', data)
}

/** 提案列表；成员只看自己提交的，组长看全组 */
export function listProposals(params) {
  return request.get('/dictionary/proposals', { params })
}

/** 提案与当前基线的差异（实时计算，不落表） */
export function proposalDiff(id) {
  return request.get(`/dictionary/proposals/${id}/diff`)
}

/** 在线编辑自己提交的提案内容（只影响提案，不动基线） */
export function updateProposalTerms(id, terms) {
  return request.put(`/dictionary/proposals/${id}/terms`, { terms })
}

/** 审核：approve=true 通过并合并；false 拒绝（comment 必填） */
export function auditProposal(id, data) {
  return request.post(`/dictionary/proposals/${id}/audit`, data)
}

/** 归档版本列表（每组每类最多 5 份快照，元信息永久保留） */
export function listArchives(params) {
  return request.get('/dictionary/archives', { params })
}

/** 基于归档版本生成回滚提案（不直接还原基线，仍需审核） */
export function rollbackArchive(versionNo, type) {
  return request.post(`/dictionary/archives/${versionNo}/rollback`, null, { params: { type } })
}
