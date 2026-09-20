import request from '@/utils/request'

// LLM 运行时配置（UX-68）。
// 密钥只回传掩码（apiKeyMask），明文只在服务端内存中；前端不得写入 localStorage。

export function getLlmConfig() {
  return request.get('/llm/config')
}

export function updateLlmConfig(data) {
  return request.put('/llm/config', data)
}

// 连通性探测（先试后存）：后端探测超时上限 20 秒，此处给 25 秒余量，
// 避免 axios 默认 30 秒之前先被前端中断而只看到「网络异常」。
export function testLlmConfig(data) {
  return request.post('/llm/test', data, { timeout: 25000 })
}
