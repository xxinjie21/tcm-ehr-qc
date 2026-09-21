/**
 * 日期时间格式化。
 *
 * 后端 LocalDateTime 没有配置 Jackson 格式，按默认 ISO 输出（带 T，秒级）。
 * 此前各处都靠内联的 `String(t).replace('T', ' ').substring(0, n)` 处理，
 * 精度是散落在各文件里的魔法数字，看不出是有意还是随手写的。
 *
 * 这里把「精度」做成显式参数，让每处调用都写明自己要哪一种 ——
 * 第八轮的取舍是按场景定：详情类到秒、紧凑摘要类到分、列表到日（悬浮再看秒）。
 */

const MODE_LEN = { date: 10, minute: 16, second: 19 }

/**
 * @param {string|Date|null|undefined} v 后端下发的 ISO 时间，如 2020-04-02T21:57:33
 * @param {'date'|'minute'|'second'} mode 精度：到日 2020-04-02 / 到分 2020-04-02 21:57 / 到秒 2020-04-02 21:57:33
 * @param {string} empty 空值占位
 * @returns {string}
 */
export function fmtDateTime(v, mode = 'second', empty = '—') {
  if (v === null || v === undefined || v === '') return empty
  const s = String(v).replace('T', ' ')
  const n = MODE_LEN[mode] || MODE_LEN.second
  return s.length > n ? s.substring(0, n) : s
}
