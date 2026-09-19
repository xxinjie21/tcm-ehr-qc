import request from '@/utils/request'

// NLP 实体抽取（转发 Python 服务，模型推理可能较慢，超时放宽）
export function extractNlp(data) {
  return request.post('/nlp/extract', data, { timeout: 60000 })
}
