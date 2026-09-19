/**
 * 触发浏览器下载。
 *
 * 两个易踩的点（UX-04）：
 * 1. 锚点必须先挂进文档再 click —— 未挂载的 `<a>` 在部分浏览器（尤其 Firefox）不触发下载；
 * 2. object URL 要等下一轮事件循环再 revoke —— 同步 revoke 可能让下载尚未开始就失效。
 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  setTimeout(() => {
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }, 0)
}
