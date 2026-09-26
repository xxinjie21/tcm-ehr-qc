/**
 * ECharts 按需引入。
 *
 * <p>只注册实际用到的图表与组件，避免把整个 echarts 打进首屏 chunk
 * （原实现 `import * as echarts from 'echarts'` 使看板路由 chunk 约 1.03MB）。
 * 新增图表类型时在此处补注册，不要在页面里直接 import 'echarts'。</p>
 */
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, GraphChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GraphChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer
])

export default echarts
