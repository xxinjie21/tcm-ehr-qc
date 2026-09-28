/**
 * 病历 21 原始字段的唯一权威定义（P3.5）。
 *
 * 此前 Records.vue（录入表单）/ Review.vue（复核列表）/ RecordDetailDialog.vue
 * （详情弹窗）各有一份 FIELDS，字段名、标签与顺序靠手工同步，已出现过
 * 「自述 / 自诉」这类标签漂移。这里收敛为一份：
 *
 * - 键、标签、顺序只此一处；标签以 database-init.sql 的列注释为准（如 self_report → 自诉）
 * - `max` / `multi` 是**录入表单**专用属性，一并放在这里（表单直接用它渲染控件）
 * - `wide` 是**布局**属性（列表分栏 / 详情 span），各页布局不同 → 由各页用
 *   {@link fieldsWithWide} 声明自己要哪些字段占整行，不放进本表
 */

/** 病历 21 原始字段（含表单属性 max / multi） */
export const RECORD_FIELDS = [
  { key: 'registrationNo', label: '登记号', max: 50 },
  { key: 'outpatientNo', label: '门诊号', max: 50 },
  { key: 'gender', label: '性别' },
  { key: 'age', label: '年龄', max: 20 },
  { key: 'visitCount', label: '就诊次数' },
  { key: 'westernDiagnosis', label: '西医诊断', multi: true },
  { key: 'tcmDiagnosis', label: '中医诊断', multi: true },
  { key: 'chiefComplaint', label: '主诉', multi: true },
  { key: 'selfReport', label: '自诉', multi: true },
  { key: 'presentIllness', label: '现病史', multi: true },
  { key: 'inspection', label: '望诊', multi: true },
  { key: 'pulse', label: '脉诊', multi: true },
  { key: 'tongue', label: '舌诊', multi: true },
  { key: 'physicalExam', label: '查体', multi: true },
  { key: 'pattern', label: '辨证结论', multi: true },
  { key: 'prescription', label: '草药', multi: true },
  { key: 'followUp', label: '随访', multi: true },
  { key: 'treatmentEffect', label: '治疗效果', multi: true },
  { key: 'department', label: '开单科室', max: 50 },
  { key: 'doctorId', label: '医生工号', max: 50 },
  { key: 'visitTime', label: '接诊时间' }
]

/**
 * 取带 `wide` 标记的字段表（供各页布局使用）。
 *
 * @param {string[]} wideKeys 需要占整行的字段键
 * @returns {Array<object>} 字段表副本，命中 wideKeys 的项带 wide:true
 */
export function fieldsWithWide(wideKeys = []) {
  const set = new Set(wideKeys)
  return RECORD_FIELDS.map((f) => (set.has(f.key) ? { ...f, wide: true } : { ...f }))
}
