import request from '@/utils/request'

// ===== 组织（阶段2 R5）=====

// 我的组：所有者/成员/申请人/待加入的用户 四个身份统一从这里拿自己的状态
export function getMyOrg() {
  return request.get('/my-org')
}

// ---- 管理员 ----
export function listOrgs(status) {
  return request.get('/orgs', { params: { status } })
}
export function approveGroup(id) {
  return request.post(`/orgs/${id}/approve`)
}
export function rejectGroup(id, reason) {
  return request.post(`/orgs/${id}/reject`, { reason })
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
export function listPendingUsers() {
  return request.get('/orgs/pending-users')
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