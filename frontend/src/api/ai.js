import request from '@/utils/request'

// AI 质控解读（批C·3.1）：规则出结论 + LLM 叙述；LLM 不可用降级模板
export function aiInterpret(data) {
  return request.post('/ai/interpret', data)
}

// AI 助手问答（批C·3.2）：业务问题 LLM+规则检索；技术实现问题兜底拒答
export function aiChat(data) {
  return request.post('/ai/chat', data)
}

// AI 复核预检意见（批D·5.1）：基于规则预检单生成复核建议
export function aiReview(data) {
  return request.post('/ai/review', data)
}
