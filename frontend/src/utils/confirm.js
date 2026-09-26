import { ElMessageBox } from 'element-plus'

/**
 * 统一的二次确认框：内部 try/catch，取消时返回 `false` 而不是抛异常。
 *
 * <p>用法：`if (!(await confirmBox('确定删除？', '删除病历'))) return`</p>
 *
 * <p>存在的原因：`ElMessageBox.confirm` 在点「取消」时是 <b>reject</b>，
 * 把 `await` 写在 `try` 之外就会产生未处理的 Promise rejection
 * （控制台 `Uncaught (in promise)`）。项目里已踩过三次，所以收成一个函数，
 * 新代码直接用它就不会再犯。**交互形态（按钮、类型、文案位置）不受影响。**</p>
 *
 * @param {string} message 正文
 * @param {string} title 标题
 * @param {object} options 透传给 ElMessageBox.confirm 的其余选项
 * @returns {Promise<boolean>} 确认 true / 取消（含关闭）false
 */
export async function confirmBox(message, title = '确认', options = {}) {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', ...options })
    return true
  } catch {
    return false
  }
}
