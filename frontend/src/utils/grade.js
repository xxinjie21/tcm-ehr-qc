/**
 * 质控分级的唯一口径（批次 13）。
 *
 * <p>此前同一件事在两处各写一遍：{@code Qc.vue} 用
 * {@code grade === '合格' ? 'is-ok' : ...} 判配色，{@code Review.vue} 用
 * {@code score >= qualified ? '合格 → 进入数据清洗' : ...} 判分级 ——
 * 后者把「下一步动作」混进了分级名，于是同一条病历在质控页显示「合格」、
 * 在复核页显示「合格 → 进入数据清洗」。改动其中一处，另一处不会跟着变。</p>
 *
 * <p>这里给出三件事的唯一实现：<b>判定</b>（{@link gradeOf}）、
 * <b>名称</b>（{@code GRADE_OK} / {@code GRADE_REVIEW} / {@code GRADE_INVALID}）、
 * <b>配色</b>（{@link gradeClass}）。
 * 阈值必须来自后端规则（管理员可改），前端不写死数值。</p>
 *
 * <p>名称与后端 {@code ScoreResultVO.grade} 保持逐字一致：复核页展示的
 * 「当前分级」直接用后端返回值，不在前端重算；只有「复核后预估分级」才调
 * {@link gradeOf}，且那是投影结果、不是后端结论，界面上必须标明「预估」。</p>
 */

/** 合格 */
export const GRADE_OK = '合格'
/** 待复核 */
const GRADE_REVIEW = '待复核'
/** 无效 */
const GRADE_INVALID = '无效'

/** 分级名 → 样式类（配色唯一副本） */
const GRADE_CLASS = {
  [GRADE_OK]: 'is-ok',
  [GRADE_INVALID]: 'is-bad'
}

/** 分级名 → el-tag 的 type（28.12：列表里分级要能一眼分辨，配色口径仍只此一份） */
const GRADE_TAG = {
  [GRADE_OK]: 'success',
  [GRADE_REVIEW]: 'warning',
  [GRADE_INVALID]: 'danger'
}

/** 分级名 → 处置建议。与分级名分开：建议会随流程变化，不该混进名称里。 */
const GRADE_HINT = {
  [GRADE_OK]: '进入数据清洗',
  [GRADE_REVIEW]: '需人工复核后重算',
  [GRADE_INVALID]: '退回补录关键字段'
}

/**
 * 按后端阈值判定分级。
 *
 * @param {number} score 评分
 * @param {{qualified:number, invalid:number}|null} thresholds 后端规则里的阈值
 * @returns {string} 分级名；阈值未就绪时返回空串（调用方应显示「—」而不是猜一个）
 */
export function gradeOf(score, thresholds) {
  if (!thresholds || thresholds.qualified == null || thresholds.invalid == null) {
    return ''
  }
  if (score >= thresholds.qualified) return GRADE_OK
  if (score >= thresholds.invalid) return GRADE_REVIEW
  return GRADE_INVALID
}

/**
 * 分级名 → 样式类。未知分级走中性色，不猜。
 *
 * @param {string} grade 分级名
 * @returns {string} 样式类
 */
export function gradeClass(grade) {
  return GRADE_CLASS[grade] || 'is-mid'
}

/**
 * 分级名 → el-tag 的 type。未知分级走 info，不猜。
 *
 * @param {string} grade 分级名
 * @returns {'success'|'warning'|'danger'|'info'}
 */
export function gradeTagType(grade) {
  return GRADE_TAG[grade] || 'info'
}

/**
 * 分级名 → 处置建议。
 *
 * @param {string} grade 分级名
 * @returns {string} 建议文案；未知分级给空串
 */
export function gradeHint(grade) {
  return GRADE_HINT[grade] || ''
}

/**
 * 阈值为空时的占位展示。
 *
 * <p>此前是「未加载就退回 90」，而 90 是后端出厂默认值的拷贝 —— 管理员改过合格线后，
 * 这段等待期里界面会按 90 显示「距合格线还差 N 分」，与服务端结论矛盾。
 * 宁可显示「—」等规则到位。</p>
 */
export const THRESHOLD_PLACEHOLDER = '—'
