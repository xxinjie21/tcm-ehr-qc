<template>
  <!-- 首页看板：只留 4 块 —— 待办快捷条 + 3 张指标卡 + 质控趋势 + 评分分布/科室合格率 -->
  <div>
    <StatsFilter :model="filter" :departments="departments" @search="loadAll" @reset="resetFilters" />

    <!-- 待办快捷条；无权限的卡片置灰并标注，避免点了才被 403 弹回
         用 button 而非 div：天然可聚焦、支持 Enter/Space
         V1：内部改横向 —— 数字+标签靠左、箭头贴右缘。
         原竖排在 ~770px 宽的栅格卡里只占 44px，右侧 ~90% 全空；
         只重排既有子元素（数字/标签/箭头），不新增业务文案。 -->
    <section class="todo-bar">
      <button
        type="button"
        class="todo"
        :class="{ warn: overview.pendingReviewCount > 0 }"
        @click="go('/review', '人工复核')"
      >
        <span class="todo-main">
          <span class="todo-num">{{ overview.pendingReviewCount }}</span>
          <span class="todo-lbl">待复核</span>
        </span>
        <span class="todo-arrow" aria-hidden="true">›</span>
      </button>
      <button
        type="button"
        class="todo"
        :class="{ warn: govern.pendingGovern > 0, readonly: !canVisit('清洗与导出') }"
        @click="go('/governance', '清洗与导出')"
      >
        <span class="todo-main">
          <span class="todo-num">{{ govern.pendingGovern }}</span>
          <span class="todo-lbl">待清洗</span>
        </span>
        <!-- 无权限时不渲染箭头：置灰卡不该暗示「可点进去」 -->
        <span v-if="canVisit('清洗与导出')" class="todo-arrow" aria-hidden="true">›</span>
      </button>
      <!-- 「病历总数」原来也在这里占一张可点卡片，但它没有动作语义（点进去只是跳质控页），
           而且与下方指标卡的同一个数字重复。待办条只留动作型入口，数字看指标卡。 -->
    </section>

    <!-- 只遮数据区：筛选条保持可交互，避免整页白屏 -->
    <div v-loading="loading" element-loading-text="数据加载中…">
      <!-- 3 张指标卡；tone 决定数字配色（green 达标 / ochre 待办 / red 异常）。
           「待复核」不在这一排 —— 那个数字只出现在上方待办条（25.6：同一屏一次即可，
           两处都有会让看板与待办条各说一个数、对不上时无从判断谁对）。
           对标 E3「一个指标只出现一次且可下钻」：卡片即入口 —— 点了带上对应筛选去列表页，
           而筛选能落在 URL 上（对标 E5），所以下钻后的页面是可刷新、可分享的。 -->
      <!-- stagger-in：三张指标卡错峰 24ms 依次浮现（见 theme.css ④）。
           「接口返回 → 卡片出现」是页内状态切换，同时出现时人眼会把三张卡看成
           一整块；错峰后能感知到「一条一条来的」，指标数量才被看清。
           只给这一组（3 个）用，表格行与长列表一律不用 —— 几十行逐个浮现会变成等待。 -->
      <div class="stats stagger-in">
        <!-- note：口径说明，文案取自本页既有 .stats-note（不编造业务数字） -->
        <StatCard label="病历总数" :value="overview.totalRecords" icon="record"
          note="全部病历 · 不受筛选影响" clickable @click="drill('')" />
        <StatCard label="质控合格率" :value="overview.qualifiedRate" tone="green" suffix="%" icon="rate"
          note="分级「合格」的病历占比" clickable @click="drill('合格')" />
        <StatCard label="无效数据" :value="overview.invalidCount" tone="red" icon="invalid"
          note="质控判定无有效内容" clickable @click="drill('无效')" />
      </div>

      <!-- 28.18：指标口径提示 —— 页面上有三个「率/数」，不写清怎么算、算哪些病历，
           用户看到与列表页对不上的数字时无从判断谁错。 -->
      <p class="stats-note">
        口径：三张指标卡取<b>全部病历</b>，不受上方筛选影响；合格率 = 质控分级为「合格」的病历占比，
        「无效数据」为质控判定无有效内容的病历。待办条的数字与指标卡同一口径；下方图表区间随筛选变化。
      </p>

      <!-- 质控趋势（跨整行） -->
      <PanelCard title="质控趋势（按月）" class="mb">
        <FreshnessTag :time="loadedAt" reason="数据为本次页面读取时刻；解析/质控更新后请刷新" />
        <div
          v-if="extra.trend.length"
          ref="trendRef"
          class="chart-tall"
          role="img"
          :aria-label="trendLabel"
        />
        <EmptyState v-else :failed="failed" :loading="loading" text="暂无趋势数据" @retry="loadAll" />
        <!-- P5.2：趋势被截到最近 12 个月时告知，避免误以为只有这些数据 -->
        <p v-if="extra.trendTruncated" class="trend-trunc">仅展示最近 12 个月（更早的月份已省略）</p>
        <!-- L1：两条线都完全无波动时给一行文字结论，替代「图是平的」这个纯视觉信息；
             月份数取实际展示条数，数据不足 12 个月时不谎称「近 12 个月」 -->
        <p v-if="trendFlat" class="trend-flat">近 {{ (extra.trend || []).length }} 个月无波动</p>
      </PanelCard>

      <div class="grid-2 mb">
        <PanelCard title="评分分布">
          <div
            v-if="hasScores"
            ref="distRef"
            class="chart"
            role="img"
            :aria-label="distLabel"
          />
          <EmptyState v-else :failed="failed" :loading="loading" text="暂无评分数据" @retry="loadAll" />
        </PanelCard>
        <PanelCard title="科室合格率">
          <!-- 纯 CSS 进度条列表：避免为一个小占比图再起一个 ECharts 实例 -->
          <div v-if="extra.departmentRates.length" class="rate-list">
            <div v-for="d in extra.departmentRates" :key="d.department" class="rate-item">
              <span class="rate-name" :title="d.department">{{ d.department }}</span>
              <div class="rate-track">
                <div class="rate-fill" :style="{ width: d.qualifiedRate + '%' }" />
              </div>
              <span class="rate-val">{{ d.qualifiedRate }}%</span>
              <span class="rate-sub">合格 {{ d.qualified }} / 共 {{ d.total }}</span>
            </div>
          </div>
          <EmptyState
            v-else
            :failed="failed"
            :loading="loading"
            text="暂无科室数据"
            @retry="loadAll"
          />
        </PanelCard>
      </div>
    </div>
  </div>
</template>

<script setup>
// 首页看板：待办快捷条 + 指标卡 + 两张图（趋势折线、评分分布柱状）。
// 数据来自 /stats/overview（指标卡）与 /stats/extra（图表），筛选条件只影响后者。
import { useDepartments } from '@/composables/useDepartments'
import { reactive, ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import echarts from '@/utils/echarts'
import StatsFilter from '@/components/StatsFilter.vue'
import FreshnessTag from '@/components/FreshnessTag.vue'
import StatCard from '@/components/StatCard.vue'
import PanelCard from '@/components/PanelCard.vue'
import EmptyState from '@/components/EmptyState.vue'

import { getOverview, getExtraStats } from '@/api/stats'
import { governanceStats } from '@/api/governance'
import { useUserStore } from '@/stores/user'

const router = useRouter()

/**
 * 指标下钻（对标 E3）：带上对应筛选跳到病历数据页。
 *
 * <p>筛选写进 URL 而不是页面内部状态 —— 病历数据页已接入 useUrlFilters（对标 E5），
 * 所以下钻后的视图可以刷新、可以发给同事，而不是只有当前这次点击有效。</p>
 *
 * @param {string} grade 空串=不限；否则为 '合格' / '无效'
 */
const drill = (grade) => {
  router.push(grade ? { path: '/records', query: { grade } } : { path: '/records' })
}
const userStore = useUserStore()

// 科室选项取自后端，与站内其他筛选器同一数据源
// 科室下拉选项（缓存：useDepartments 单例）
const { departments, reload: loadDepartments } = useDepartments()

// 待办卡片按登录返回的菜单判断可达性；无权限时置灰并说明原因
const canVisit = (menuTitle) => (userStore.menus || []).includes(menuTitle)
// 待办卡片跳转：先按菜单判断可达性，无权限时只提示不跳转（避免点了才被 403 弹回）
const go = (path, menuTitle) => {
  if (!canVisit(menuTitle)) {
    ElMessage.info(`「${menuTitle}」不在你的菜单里，不可访问`)
    return
  }
  router.push(path)
}

// 筛选条件：科室 + 就诊日期区间；只作用于图表接口
const filter = reactive({ department: '', start: '', end: '' })
const loading = ref(false)
// P2-9 数据新鲜度：页面读取时刻（诚实、不依赖后端字段、不猜加载函数内部）
const loadedAt = ref(new Date().toLocaleString())
// 区分「加载失败」与「确实为空」
const failed = ref(false)

// 指标卡数据（无参接口，不受筛选影响）
const overview = ref({
  totalRecords: 0,
  qualifiedRate: 0,
  pendingReviewCount: 0,
  invalidCount: 0
})
// 待清洗数：仅管理员可读，非管理员恒为 0（卡片置灰）
const govern = reactive({ pendingGovern: 0 })
const extra = ref({ trend: [], departmentRates: [], scoreDistribution: [] })

// ECharts 实例：惰性创建、按需重建（见 renderTrend / renderDist）
const trendRef = ref(null)
const distRef = ref(null)
let trendChart = null
let distChart = null

// 评分分布全为 0 时不画图（否则是一根空柱）
const hasScores = computed(() => (extra.value.scoreDistribution || []).some((b) => b.count > 0))

// 图表的文本替代：给屏幕阅读器与无法看图的环境提供关键结论
const trendLabel = computed(() => {
  const t = extra.value.trend || []
  if (!t.length) return '质控趋势图，暂无数据'
  const last = t[t.length - 1]
  return `质控趋势折线图，共 ${t.length} 个月；最新 ${last.month} 合格率 ${last.qualifiedRate}%，待复核 ${last.pendingReview} 条`
})

// 评分分布图的文本替代：只列非空分档，供屏幕阅读器与无法看图的环境使用
const distLabel = computed(() => {
  const d = (extra.value.scoreDistribution || []).filter((b) => b.count > 0)
  if (!d.length) return '评分分布图，暂无数据'
  return `评分分布柱状图：${d.map((b) => `${b.bucket} 分 ${b.count} 条`).join('，')}`
})

// L1：趋势是否完全无波动 —— 合格率与待复核两条线**各自** max===min 才算「平」。
// 单条平、另一条有起伏仍是有效信息（如合格率恒 100 但待复核在变），不能一并抹掉。
const trendFlat = computed(() => {
  const t = extra.value.trend || []
  if (!t.length) return false
  const flat = (arr) => Math.max(...arr) === Math.min(...arr)
  return flat(t.map((p) => p.qualifiedRate)) && flat(t.map((p) => p.pendingReview))
})

// 渲染趋势图。容器变化时先 dispose 旧实例再重建，避免 ECharts 挂在已卸载的 DOM 上
const renderTrend = () => {
  // 1. 空态占位时容器不存在，直接返回（等有数据再画）
  if (!trendRef.value) return
  // 2. 实例复用：容器换了先销毁旧实例再重建，避免 ECharts 挂在已卸载的 DOM 上
  if (!trendChart || trendChart.getDom() !== trendRef.value) {
    if (trendChart) trendChart.dispose()
    trendChart = echarts.init(trendRef.value)
  }
  // 3. 取趋势数据（按月）
  const t = extra.value.trend
  // 4. 组装配置并渲染：双 Y 轴 —— 左轴合格率、右轴待复核条数
  trendChart.setOption({
    // I6/C3：全站浮层统一墨绿投影（--shadow-pop 同值 rgba(47,70,57,.12)）；
    // ECharts 默认是冷灰投影，落在宣纸底上发灰。formatter 把月份/指标名保持默认灰 13px，
    // 数值用 15px 墨绿加粗 —— 让「100」「0」成为 tooltip 的视觉主项。
    // 已确认 utils/echarts.js 只注册基础组件、未启用 rich/安全过滤，renderMode 默认 html，可直接返 HTML。
    tooltip: {
      trigger: 'axis',
      textStyle: { fontSize: 13 },
      extraCssText: 'box-shadow: 0 2px 8px rgba(47,70,57,.12); border-radius: 2px;',
      formatter: (params) => {
        const list = Array.isArray(params) ? params : [params]
        const rows = list.map((p) =>
          `${p.marker}${p.seriesName}：<b style="color:#2f4639;font-size:15px">${p.value}</b>`
        ).join('<br/>')
        return `${list[0]?.axisValue ?? ''}<br/>${rows}`
      }
    },
    // F6：图例字号 12→13，与全站字号令牌上移后的最低档对齐
    legend: { data: ['合格率', '待复核'], right: 10, top: 0, textStyle: { fontSize: 13 } },
    // L1：容器由 230px 压到 180px 后，grid 上边距同步从 34 压到 28，避免图例与绘区间留死白
    grid: { left: 44, right: 48, top: 28, bottom: 28 },
    xAxis: { type: 'category', data: t.map((p) => p.month), axisLine: { lineStyle: { color: '#d8d2c4' } }, axisLabel: { fontSize: 13 } },
    yAxis: [
      // F6：两条 Y 轴补显式 axisLabel 字号 13（保持既有 color / splitLine 配置不变）
      { type: 'value', name: '合格率%', max: 100, axisLabel: { formatter: '{value}', fontSize: 13 }, splitLine: { lineStyle: { color: '#efebe1' } } },
      { type: 'value', splitLine: { show: false }, axisLabel: { fontSize: 13 } }
    ],
    series: [
      {
        name: '合格率', type: 'line', smooth: true, yAxisIndex: 0,
        // L1：180px 高的图里折线几乎无起伏，加数据点标记让每个月的取值可指认
        showSymbol: true, symbolSize: 5,
        data: t.map((p) => p.qualifiedRate),
        itemStyle: { color: 'var(--ink-mid)' }, areaStyle: { color: 'rgba(61,90,76,0.10)' }
      },
      {
        name: '待复核', type: 'line', smooth: true, yAxisIndex: 1,
        showSymbol: true, symbolSize: 5,
        data: t.map((p) => p.pendingReview),
        itemStyle: { color: 'var(--ochre)' }, lineStyle: { type: 'dashed' }
      }
    ]
  })
}

// 渲染评分分布柱状图；实例复用策略同 renderTrend
const renderDist = () => {
  // 1. 空态占位时容器不存在，直接返回
  if (!distRef.value) return
  // 2. 实例复用：容器换了先销毁旧实例再重建
  if (!distChart || distChart.getDom() !== distRef.value) {
    if (distChart) distChart.dispose()
    distChart = echarts.init(distRef.value)
  }
  // 3. 取评分分布数据（按分数档）
  const d = extra.value.scoreDistribution
  // 4. 组装配置并渲染：单轴柱状，x 为分数档、y 为条数
  distChart.setOption({
    // I6/C3：与趋势图同一套浮层口径 —— 墨绿投影 + 数值 15px 墨绿加粗。
    // 轴触发下 p.name 即分数档，数值用 <b> 突出。
    tooltip: {
      trigger: 'axis',
      textStyle: { fontSize: 13 },
      extraCssText: 'box-shadow: 0 2px 8px rgba(47,70,57,.12); border-radius: 2px;',
      formatter: (params) => {
        const list = Array.isArray(params) ? params : [params]
        return list.map((p) =>
          `${p.marker}${p.name}：<b style="color:#2f4639;font-size:15px">${p.value}</b>`
        ).join('<br/>')
      }
    },
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    // F6：两条轴 axisLabel 补 13（原轴标签走 ECharts 默认字号，未随令牌上移）
    xAxis: { type: 'category', data: d.map((b) => b.bucket), axisLine: { lineStyle: { color: '#d8d2c4' } }, axisLabel: { fontSize: 13 } },
    yAxis: { type: 'value', axisLabel: { fontSize: 13 }, splitLine: { lineStyle: { color: '#efebe1' } } },
    series: [{
      type: 'bar', barWidth: '46%',
      // 28.21：0 值档位单独弱化，避免与「有数据」的档位在视觉上同一观感
      data: d.map((b) => b.count === 0
        ? { value: 0, itemStyle: { color: 'rgba(77,107,88,0.18)' } }
        : b.count),
      itemStyle: { color: '#4d6b58', borderRadius: [2, 2, 0, 0] }
    }]
  })
}

// 图表接口的查询参数（指标卡走无参的 /stats/overview，所以这里只喂趋势 / 分布）
const params = () => ({
  department: filter.department || '',
  start: filter.start || '',
  end: filter.end || '',
  pattern: ''
})

// 看板只拉主线口径：指标卡走 /stats/overview（轻量），趋势/分布走 /stats/extra
const loadAll = async () => {
  // 1. 置加载态并清掉上一次的失败标记
  loading.value = true
  failed.value = false
  try {
    // 2. 并行拉取指标卡 / 图表数据（带筛选）与清洗统计，三块互不依赖（P4.6）
    const govTask = userStore.role === '管理员'
      ? governanceStats().catch(() => ({ data: { pendingGovern: 0 } }))
      : Promise.resolve(null)
    const [ov, ex, gc] = await Promise.all([getOverview(), getExtraStats(params()), govTask])
    // 3. 分别回填指标卡与图表数据；清洗统计失败/非管理员不影响其余看板
    overview.value = ov.data
    extra.value = ex.data
    if (gc) {
      govern.pendingGovern = gc.data?.pendingGovern ?? 0
    }
  } catch {
    // 拦截器已提示；标记失败态，空态区据此给出重试入口
    failed.value = true
  } finally {
    // 5. 收尾：重画两张图并复位加载态
    // 等 DOM 更新（空态换成图表容器）后再初始化 ECharts，否则拿不到 ref
    await nextTick()
    renderTrend()
    renderDist()
    loading.value = false
  }
}

// 重置筛选条件并重新拉取
const resetFilters = () => {
  filter.department = ''
  filter.start = ''
  filter.end = ''
  loadAll()
}

onMounted(() => {
  loadDepartments()
  loadAll()
  window.addEventListener('resize', handleResize)
})

// 窗口尺寸变化时让图表跟随容器重算（P4.7：rAF 合并，拖动不逐帧重排）
let rafId = null
const handleResize = () => {
  if (rafId) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    if (trendChart) trendChart.resize()
    if (distChart) distChart.resize()
  })
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (rafId) cancelAnimationFrame(rafId)
  // 卸载时销毁两个 ECharts 实例，避免残留监听与内存泄漏
  ;[trendChart, distChart].forEach((c) => c && c.dispose())
  trendChart = distChart = null
})
</script>

<style scoped>
/* 待办快捷条：两列等宽网格，放 2 张待办卡（病历总数卡已移除 —— 它没有动作语义、
   数字也与指标卡重复）。「待复核」只在这里给数字，指标卡不再重复。 */
.todo-bar {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--sp-3);
  margin-bottom: 10px;
}
.todo {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-4);
  cursor: pointer;
  /* 微反馈只过渡阴影，不做位移 —— transform 会劫持 position:fixed 后代的包含块（红线），
     且 hover 上的 translateY 会让并排卡片整行抖一下 */
  transition: box-shadow var(--dur-fast) var(--ease-out);
  /* button 元素重置：保持原卡片观感*/
  width: 100%;
  text-align: left;
  font-family: inherit;
  font-size: inherit;
  color: inherit;
  /* V1：横向排布 —— 数字+标签靠左（竖排关系不变），箭头贴右缘收口，
     把原先右侧 ~90% 的空白变成两端锚定后的呼吸位 */
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}
.todo:hover {
  box-shadow: 0 2px 8px rgba(47, 70, 57, 0.1);
}
/* 无权限卡片：置灰、取消悬浮反馈，并标注原因*/
.todo.readonly {
  cursor: default;
  opacity: 0.72;
}
.todo.readonly:hover {
  box-shadow: none;
}
/* 左侧信息块：数字在上、标签在下（沿用原竖排关系，只是整体挪到卡片左侧） */
.todo-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
/* 右侧箭头：原文案里的 › 从标签中拆出右置，作为「可点击入口」的收口 */
.todo-arrow {
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
  flex-shrink: 0;
}
/* 「仅管理员」小标记 */
.todo-lock {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  border: 1px solid var(--line);
  border-radius: 2px;
  padding: 0 var(--sp-1);
  margin-left: var(--sp-1);
}
/* 待办数字：大号；有待办时（.warn）转 ochre */
.todo-num {
  font-size: var(--fs-xl);
  font-weight: bold;
  color: var(--ink);
  line-height: 1.2;
}
.todo.warn .todo-num { color: var(--ochre-text); }
.todo-lbl {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-top: 2px;
}
/* 28.19-08：描述性指标统一等宽栅格。
   原先用 flex + flex:1，最后一张卡被剩余空间拉伸、与其余卡不等宽；
   改成 auto-fit 等分栅格，宽屏各占一份，窄屏自动收列。 */
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--sp-3);
  margin-bottom: 10px;
}
/* 下方两块面板并排（窄屏收单列） */
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-3);
}
/* 面板自带下边距，网格内用间距代替，避免双重留白 */
.grid-2 :deep(.panel) {
  margin-bottom: 0;
}
.mb {
  margin-bottom: 10px;
}
/* 图表容器固定高度：ECharts 需要确定的尺寸才能初始化 */
.chart {
  width: 100%;
  height: 210px;
}
.chart-tall {
  width: 100%;
  /* L1：230px 高几乎全空却独占整行；180px + 数据点标记后信息密度足够 */
  height: 180px;
}
/* 科室合格率列表：超 260px 时内部滚动 */
.rate-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 260px;
  overflow-y: auto;
}
.rate-item {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
}
/* V5：科室名改 min-width + 允许收缩 —— 原固定 width:72px + flex-shrink:0 会把
   「合格 x / 共 y」挤成 3 行（sub 只有 40px 宽）；现在空间紧时科室名先收缩、
   过长省略（完整名放 title），右侧口径行保持一行 */
.rate-name {
  min-width: 72px;
  text-align: right;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 进度条轨道与填充；宽度由行内 style 按百分比给出 */
.rate-track {
  flex: 1;
  height: 13px;
  background: #f0ede4;
  border-radius: 2px;
  overflow: hidden;
}
.rate-fill {
  height: 100%;
  background: var(--ink-mid);
}
.rate-val {
  /* V5：固定 width → min-width，右对齐保持；46px 足够放下「100%」 */
  min-width: 46px;
  text-align: right;
  color: var(--ink);
}
.rate-sub {
  /* V5：「合格 500 / 共 500」一行放下（原 40px 宽被折成 3 行、整行高 59px）；
     nowrap + min-width 保证单行，整行高回到 ~20px */
  min-width: 96px;
  white-space: nowrap;
  color: var(--text-sub-strong);
}
/* 窄屏（<1200px）：面板与待办条都收成单列 */
@media (max-width: 1200px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
  .todo-bar {
    grid-template-columns: 1fr;
  }
}
/* P5.2：趋势截断提示 */
.trend-trunc { margin: var(--sp-2) 0 0; font-size: var(--fs-xs); color: var(--text-sub-strong); }
/* L1：完全无波动时的文字结论；与 trend-trunc 同级同权重，不抢图表注意力 */
.trend-flat { margin: var(--sp-2) 0 0; font-size: var(--fs-xs); color: var(--text-sub-strong); }
/* 28.18：指标口径说明 —— 灰底浅字，与卡片区分开，不抢指标数字的注意力 */
.stats-note {
  margin: 0 0 10px;
  padding: var(--sp-2) var(--sp-3);
  background: var(--ink-light);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-sub-strong);
}
.stats-note b { color: var(--ink); }
/* 对标 A5：指标卡下方的血缘行。刻意做得比正文轻 —— 它是下钻入口，不该和指标抢注意力 */
</style>
