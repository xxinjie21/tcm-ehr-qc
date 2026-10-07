/**
 * ECharts 按需引入。
 *
 * <p>只注册实际用到的图表与组件，避免把整个 echarts 打进首屏 chunk
 * （原实现 `import * as echarts from 'echarts'` 使看板路由 chunk 约 1.03MB）。
 * 新增图表类型时在此处补注册，不要在页面里直接 import 'echarts'。</p>
 */
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer
])

// 减弱动效（审查报告第三轮 §七#5）：CSS 的 prefers-reduced-motion 全局兜底只管 DOM 动画，
// ECharts 是 canvas 自绘、不吃 CSS 规则 —— 系统开启「减弱动态效果」时图表入场动画照播。
// 这里注册一个全局预处理器：减弱动效开启时给所有 setOption 关掉动画（zero risk：
// 关的只是入场补间，数据渲染不受影响）。
if (typeof window !== 'undefined' && window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
  echarts.registerPreprocessor((option) => {
    if (option) option.animation = false
  })
}

export default echarts
