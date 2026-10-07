import request from '@/utils/request'

// NLP 实体抽取（转发 Python 服务，模型推理可能较慢，超时放宽）
export function extractNlp(data) {
  return request.post('/nlp/extract', data, { timeout: 60000 })
}

// 批量解析：提交后台任务（仅管理员）
export function submitNlpBatch(data) {
  return request.post('/nlp/extract/batch', data, { timeout: 60000 })
}

// 批量任务进度
export function getNlpBatchProgress(id) {
  return request.get(`/nlp/extract/batch/${id}`)
}

// 取消批量任务
export function cancelNlpBatch(id) {
  return request.post(`/nlp/extract/batch/${id}/cancel`)
}

// 抽取服务健康探测：只问「开没开、在不在、模型有没有加载」，不发抽取请求。
// 探测在后端不会失败（最坏回一份「连不上」）；超时给短一点，别让首屏被它拖住。
//
// silent 判据（I3）：这是后台探测，失败只走页面内降级提示，不弹全局红条
// （后端未部署 NLP 时 /nlp/health 是 404，打开页面就弹「接口不存在」纯属噪音）；
// 但用户主动点「重新探测」必须保留提示。useNlpStatus.probeNlp 是同一入口、
// 调用方不带参数，两种路径的区分只能落在这里，依据两条既成事实：
//   ① NlpExtract 的「重新探测」按钮是 v-if="probeHint"（即成功载荷里的 unavailableReason）
//      渲染的 —— 没成功探到过就不渲染按钮，所以「还没成功过」= 此刻只可能是后台探测；
//   ② probeNlp()（force=false）命中 useNlpStatus 的缓存直接返回、不发请求 ——
//      成功过之后唯一还会发出的健康请求就是 force 手动重探。
// 故：silent = 还没成功探到过。失败不复位开关：status 仍是旧结论、后台不会再发，
// 手动重探下次依旧要有提示。
let healthOk = false

export function getNlpHealth() {
  const p = request.get('/nlp/health', { timeout: 5000, silent: !healthOk })
  // 只认「探到有效载荷」（与 useNlpStatus 判空口径一致：data 非空才缓存结论）
  p.then((res) => { healthOk = !!(res && res.data) }).catch(() => {})
  return p
}

// 批量任务列表（最近 50 条）
export function listNlpBatch() {
  return request.get('/nlp/extract/batch')
}
