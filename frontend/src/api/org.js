import request from '@/utils/request'

// ===== 组织 =====

// 我的组织：所有者 / 成员 / 未加入组织，统一从这里拿自己的状态
export function getMyOrg() {
  return request.get('/my-org')
}

// ---- 管理员 ----
export function listOrgs(status) {
  return request.get('/orgs', { params: { status } })
}
export function updateGroup(id, body) {
  return request.put(`/orgs/${id}`, body)
}
export function stopGroup(id) {
  return request.post(`/orgs/${id}/stop`)
}
export function activateGroup(id) {
  return request.post(`/orgs/${id}/activate`)
}
// ---- 所有者（本组织）----
export function listMembers(orgId) {
  return request.get(`/orgs/${orgId}/members`)
}
export function addMember(orgId, userId) {
  return request.post(`/orgs/${orgId}/members`, { userId })
}
export function removeMember(orgId, userId) {
  return request.delete(`/orgs/${orgId}/members/${userId}`)
}
export function transferOwner(orgId, newOwnerUserId) {
  return request.put(`/orgs/${orgId}/members/${newOwnerUserId}/transfer-owner`, { newOwnerUserId })
}
export function leaveGroup(orgId) {
  return request.post(`/orgs/${orgId}/leave`)
}

// ---- 批次 6：自助创建 / 成员搜索 / 授权开关 / 归档 / 改派 ----

/** 自助创建组织：创建者自动成为所有者，无审核 */
export function createOrg(body) {
  return request.post('/orgs', body)
}

/** 按用户名搜索可拉入的人（后端只回 id 与 username，关键词至少 2 字符） */
export function searchUsers(keyword) {
  return request.get('/orgs/users', { params: { keyword } })
}

/** 授予 / 回收成员的两个写开关（null = 该位不改） */
export function setPermissions(orgId, userId, body) {
  return request.put(`/orgs/${orgId}/members/${userId}/permissions`, body)
}

/** 归档组织（仅管理员，前提成员数为 0） */
export function archiveOrg(id, reason) {
  return request.post(`/orgs/${id}/archive`, { reason })
}

/** 改派所有者（仅管理员，owner 账号丢失时的兜底） */
export function reassignOwner(id, newOwnerUserId) {
  return request.post(`/orgs/${id}/reassign-owner`, { newOwnerUserId })
}
