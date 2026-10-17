/**
 * 日期时间格式化。
 *
 * 后端 LocalDateTime 没有配置 Jackson 格式，按默认 ISO 输出（带 T，秒级）。
 * 此前各处都靠内联的 `String(t).replace('T', ' ').substring(0, n)` 处理，
 * 精度是散落在各文件里的魔法数字，看不出是有意还是随手写的。
 *
 * 这里把「精度」做成显式参数，让每处调用都写明自己要哪一种 ——
 * 的取舍是按场景定：详情类到秒、紧凑摘要类到分、列表到日（悬浮再看秒）。
 */

const MODE_LEN = { date: 10, minute: 16, second: 19 }

/**
 * @param {string|Date|null|undefined} v 后端下发的 ISO 时间，如 2020-04-02T21:57:33
 * @param {'date'|'minute'|'second'} mode 精度：到日 2020-04-02 / 到分 2020-04-02 21:57 / 到秒 2020-04-02 21:57:33
 * @param {string} empty 空值占位
 * @returns {string}
 */
export function fmtDateTime(v, mode = 'second', empty = '—') {
  // 1. 空值直接返回占位符
  if (v === null || v === undefined || v === '') return empty
  // 2. 归一化 ISO 串：T 换成空格
  const s = String(v).replace('T', ' ')
  // 3. 按精度取长度截断（缺省秒级）
  const n = MODE_LEN[mode] || MODE_LEN.second
  return s.length > n ? s.substring(0, n) : s
}

/**
 * 取「今天」的 yyyy-MM-dd。
 *
 * <p>与 {@link fmtDateTime} 的分工：后者处理<b>后端下发</b>的 ISO 时间串；本函数处理
 * 「现在」—— 标准质量报告、质控、结构化解析、清洗与导出等页的「默认今天导入」口径
 * 都靠它拼 importRange 的初值。不并进 fmtDateTime：那函数只认带 T 的串，喂 Date
 * 会拿到 "Sat Oct 17 ..." 这种浏览器格式，静默截出脏数据。</p>
 *
 * @param {Date} [d=new Date()] 日期，默认今天
 * @returns {string} yyyy-MM-dd
 */
export function fmtDate(d = new Date()) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * 取病历原始字段的展示值（P3.2：Review 与 RecordDetailDialog 各一份，收敛于此）。
 *
 * <p>接诊时间后端下发 ISO 串（含 T），这里统一换成「日期 时间」可读形式；
 * 其余字段原样返回。模板侧统一用 {@code || '—'} 兜空。</p>
 *
 * @param {object|null} row 病历行对象
 * @param {string} key 字段名（visitTime 特殊处理）
 * @returns {*} 展示值；无行时返回空串
 */
export function fieldOf(row, key) {
  if (!row) return ''
  if (key === 'visitTime') {
    return row.visitTime ? fmtDateTime(row.visitTime, 'second', '') : ''
  }
  return row[key]
}
