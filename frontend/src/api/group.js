import request from '@/utils/request'

// ===== 组织（阶段2 R5）=====

// 我的组：所有者/成员/申请人/待加入的用户 四个身份统一从这里拿自己的状态
export function getMyGroup() {
  return request.get('/my-group')
}

// ---- 管理员 ----
export function listGroups(status) {
  return request.get('/groups', { params: { status } })
}
export function approveGroup(id) {
  return request.post(`/groups/${id}/approve`)
}
export function rejectGroup(id, reason) {
  return request.post(`/groups/${id}/reject`, { reason })
}
export function updateGroup(id, body) {
  return request.put(`/groups/${id}`, body)
}
export function stopGroup(id) {
  return request.post(`/groups/${id}/stop`)
}
export function activateGroup(id) {
  return request.post(`/groups/${id}/activate`)
}
export function listPendingUsers() {
  return request.get('/groups/pending-users')
}

// ---- 所有者（本组织）----
export function listMembers(groupId) {
  return request.get(`/groups/${groupId}/members`)
}
export function addMember(groupId, userId) {
  return request.post(`/groups/${groupId}/members`, { userId })
}
export function removeMember(groupId, userId) {
  return request.delete(`/groups/${groupId}/members/${userId}`)
}
export function transferOwner(groupId, newOwnerUserId) {
  return request.put(`/groups/${groupId}/members/${newOwnerUserId}/transfer-owner`, { newOwnerUserId })
}
export function leaveGroup(groupId) {
  return request.post(`/groups/${groupId}/leave`)
}