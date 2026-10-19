/**
 * 图表配色单点（P1 任务 8 / P2「统一图表主色」）
 *
 * 为什么需要这一层：ECharts 的 color 只接受字符串，JS 里拿不到 CSS 的 var()。
 * 此前 Dashboard.vue 与 StandardizationCharts.vue 各写一份色值字面量，
 * 改主题色要改两处、漏一处就出现「两张图不同色」。
 *
 * 这里改为从 theme.css 的 --chart-* / --ink / --ochre 等令牌读取，
 * 读不到时用**与原字面量完全相同**的兜底值 —— 所以引入本模块本身不改变任何颜色。
 * 用 getter 惰性取值：模块可能在 :root 样式应用前被求值，惰性可避免拿到空值。
 */
const read = (name, fallback) => {
  if (typeof document === 'undefined') return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(name)
  return (v || '').trim() || fallback
}

export const CHART = {
  /** 坐标轴线（--chart-axis-line） */
  get axisLine() { return read('--chart-axis-line', '#d8d2c4') },
  /** 网格分割线（--chart-split-line） */
  get splitLine() { return read('--chart-split-line', '#efebe1') },
  /** 轴标签文字（--chart-axis-label） */
  get axisLabel() { return read('--chart-axis-label', '#4a4438') },
  /** 第二序列主色：合格率/柱（--chart-series-ink） */
  get seriesInk() { return read('--chart-series-ink', '#4d6b58') },
  /** 警示序列主色：无效/超期（--chart-series-warn） */
  get seriesWarn() { return read('--chart-series-warn', '#b8772f') },
  /** 数据标签强文字（--text-sub-strong） */
  get labelStrong() { return read('--text-sub-strong', '#6b6558') },
  /** 墨绿正文/标注（--ink） */
  get ink() { return read('--ink', '#2f4639') },
  /** 墨绿主色（--ink-mid） */
  get inkMid() { return read('--ink-mid', '#3d5a4c') },
  /** 赭石图形色（--ochre） */
  get ochre() { return read('--ochre', '#96714f') },
  /** 卡面底色（--surface），用作数据点描边 */
  get surface() { return read('--surface', '#ffffff') },
}

export default CHART
