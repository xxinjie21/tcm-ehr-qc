/**
 * v-autoheight —— 多行输入框高度随内容自适应（无内部滚动条）。
 *
 * 背景：Element Plus 的 el-input `:autosize` 在本项目实测失效——
 * 病历数据异步载入、以及后续手动输入时，textarea 都停在初始行高、
 * 内容超出后出现内部滚动条、文字被截断。它既不随 modelValue 批量更新
 * 撑高，也不随原生 input 事件撑高（已逐一实测确认）。
 *
 * 因此不再依赖 autosize，改用本指令：在组件每次更新（mounted/updated）
 * 时把 textarea 高度设为内容实际高度（scrollHeight），内容多高框就多高，
 * 永不出现内部滚动条。只对 textarea 生效，单行 input 自动跳过。
 *
 * 用法：
 *   <el-input v-autoheight :type="f.multi ? 'textarea' : 'text'" :rows="2" />
 *
 * 注意：需把原 `:autosize` 去掉（autosize 会写行内 style.height 覆盖本指令）。
 * 初始高度由 :rows 给一个较小的值（如 2 行），其余高度由本指令撑满。
 */
import { nextTick } from 'vue'

export const vAutoheight = {
  mounted(el) {
    fit(el)
  },
  updated(el) {
    // updated 在宿主（el-input 组件）更新时触发，但 el-input 内部 textarea
    // 用新值渲染发生在子组件更新之后 —— 直接测会读到旧值高度。nextTick 后
    // 整棵组件树（含子组件）已更新完，此时测量才是真实内容高度。
    nextTick(() => fit(el))
  },
}

/** 把容器内（或自身是）的 textarea 高度撑到内容高度 */
function fit(el) {
  const t = el && el.querySelector ? el.querySelector('textarea') : null
  if (!t) return
  t.style.height = 'auto'
  t.style.height = `${t.scrollHeight}px`
}
