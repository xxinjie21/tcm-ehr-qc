<template>
  <!-- 操作日志页：筛选 + 分页表格 + 导出 CSV（无日志删除入口）
       根节点 .page-fill：短内容时卡片撑到主区底部（V6），不再裸露下半屏纸底 -->
  <div class="page-fill">
    <PanelCard title="操作日志">
      <div class="filter-row">
        <!-- 这两个控件只有 placeholder、没有视觉标签，补 aria-label
             （Chrome 的「No label associated with a form field」检查不认 placeholder） -->
        <el-select v-model="query.action" aria-label="操作类型" placeholder="操作类型" clearable style="width: 150px">
          <el-option v-for="a in actionOptions" :key="a" :label="a" :value="a" />
        </el-select>
        <el-input
          v-model="query.keyword"
          aria-label="操作人 / 对象关键字"
          placeholder="操作人 / 对象关键字"
          clearable
          style="width: 200px"
          @keyup.enter="loadLogs"
        />
        <!-- 28.8：独立操作人筛选。关键字是模糊三列命中，要「只看某人」时不够用 -->
        <el-input
          v-model="query.operator"
          aria-label="操作人（精确）"
          placeholder="操作人（精确）"
          clearable
          style="width: 140px"
          @keyup.enter="loadLogs"
        />
        <!-- 28.8：时间范围。审计最常见的诉求是「某某时间之后发生过什么」 -->
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 360px"
        />
        <el-button type="primary" :loading="loading" @click="loadLogs">查 询</el-button>
        <!-- 导出依赖「日志可用」：加载失败时禁用，避免对空列表做导出 -->
        <el-button :loading="exporting" :disabled="logFailed" @click="handleExport">导出 CSV</el-button>
        <span class="tip">共 {{ total }} 条</span>
      </div>

      <!-- 可见范围说明：三档口径不同，不写清楚用户会以为「日志少了」 -->
      <p class="scope-tip">{{ scopeTip }}</p>

      <!-- 28.7：整行可点，跳转到关联对象（objectType/objectId 早已入库，此前只是没渲染）
           去掉写死的 max-height="520"（审查报告 V6）：520 在默认 10 条/页时根本触不到、
           形同虚设，调到 50 条/页时又变成「页内滚动 + 卡内滚动」两层嵌套。
           行数由分页控制（10/20/50），超长时统一交给 main 这一个滚动层。 -->
      <el-table v-loading="loading" element-loading-text="正在读取操作日志…" :data="logs" border stripe style="margin-top: var(--sp-3)" @row-click="onRowClick">
        <el-table-column label="操作时间" width="170">
          <template #default="{ row }">{{ fmtDateTime(row.logTime) }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column prop="role" label="角色" width="90" />
        <!-- 所属组织：三档可见范围下，这一列是判断「这条日志该不该被我看」的唯一线索 -->
        <el-table-column label="所属组织" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.action)" effect="plain">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="target" label="操作对象" width="200" show-overflow-tooltip />
        <el-table-column label="关联对象" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button
              v-if="row.objectType && row.objectId"
              link
              type="primary"
              size="small"
              @click.stop="openObject(row)"
            >
              {{ row.objectType }} · {{ row.objectId }}
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
        <!-- 空态分两种：确实没日志 vs 加载失败（后者才给「重试」入口） -->
        <template #empty>
          <EmptyState
            :failed="logFailed"
            :text="logFailed ? '日志加载失败' : '暂无日志记录'"
            @retry="loadLogs"
          />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :page-sizes="PAGE_SIZES_STANDARD"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: var(--sp-3); justify-content: flex-end"
        @current-change="loadLogs"
        @size-change="handleSizeChange"
      />
    </PanelCard>
  </div>
</template>

<script setup>
// 操作日志页：管理员查看审计留痕。
// 对外只提供两个入口 —— 查询/分页（只读）与导出 CSV。
// 日志只增不删（§七 L3）：日志删除功能已删，清理只能由运维人工归档。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { usePagedList } from '@/composables/usePagedList'
import { useUrlFilters } from '@/composables/useUrlFilters'
import { getLogs, getLogActions, exportLogs } from '@/api/log'
import { saveBlob } from '@/utils/download'
import { fmtDateTime } from '@/utils/format'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'
import { useUserStore } from '@/stores/user'

// 图例配色；具体选项由后端返回，未匹配到的走默认色
const TAG_TYPES = {
  数据清洗: 'warning',
  数据集导出: 'primary',
  词典导入: 'success',
  词典回滚: 'info',
  词典转换: 'primary',
  病历导入: 'success',
  病历修改: 'primary',
  病历删除: 'danger'
}

// 操作类型下拉候选：由后端返回，不在前端硬编码枚举
const userStore = useUserStore()

/**
 * 可见范围说明：后端按三档过滤，前端把口径写出来，
 * 否则用户看到日志「少了」会以为系统丢数据。
 */
const scopeTip = computed(() => {
  if (userStore.isAdmin) return '可见范围：全部组织。'
  if (userStore.isOrgOwner) return '可见范围：本组织的全部操作记录。'
  if (userStore.hasOrg) return '可见范围：仅本组织内你本人的操作记录。'
  return '可见范围：仅你本人的操作记录（你还没有加入任何组织）。'
})

const actionOptions = ref([])

// 拉取操作类型候选；失败退化为空列表，仍可用关键字搜索
const loadActions = async () => {
  try {
    const res = await getLogActions()
    actionOptions.value = res.data || []
  } catch {
    actionOptions.value = []
  }
}

/** 导出文件名的时间戳：与后端归档名（audit-archive-YYYYMMDD_HHmmss_SSS.csv）同一格式，
    毫秒时间戳既不可读、也与系统里另一个日志文件名风格不一致 */
const auditStamp = () => {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}_${p(d.getMilliseconds())}`
}

// 查询条件；page / pageSize 直接双向绑定分页组件（与其余列表页与 /api/logs 契约一致）。
// 28.8：operator 精确匹配、startTime/endTime 闭区间时间范围，随 query 一起进 URL（可分享）。
const query = reactive({
  action: '', keyword: '', operator: '', startTime: '', endTime: '', page: 1, pageSize: 10
})
// 日期选择器要数组、query 要两个字符串字段；用 computed 双向桥接，避免在 query 里存数组导致 URL 序列化难看
const dateRange = computed({
  get: () => (query.startTime && query.endTime ? [query.startTime, query.endTime] : []),
  set: (v) => {
    query.startTime = v?.[0] || ''
    query.endTime = v?.[1] || ''
  }
})
const exporting = ref(false)
// 列表骨架统一走 usePagedList；本页失败要「清空 + 标记」（不退化成展示编造的日志，
// 空态据此给重试入口、导出按钮据此禁用），故三个开关都用默认
const { list: logs, total, loading, failed: logFailed, load: loadLogs } = usePagedList({
  fetcher: () => getLogs(query),
  extract: (res) => ({ list: res.data?.list, total: res.data?.total })
})

// 操作类型 → el-tag 配色；未登记的走默认色
const tagType = (action) => TAG_TYPES[action] || 'primary'

// 关联对象跳转：目前唯一写入对象标识的是病历（RecordController.logOnObject），
// 其余类型先如实告知「暂不支持」，不做一个点了没反应的按钮。
const router = useRouter()
const openObject = (row) => {
  if (!row.objectType || !row.objectId) return
  if (row.objectType === 'record') {
    router.push({ path: '/records', query: { recordId: row.objectId } })
    return
  }
  ElMessage.info(`暂不支持跳转到 ${row.objectType} 对象`)
}
const onRowClick = (row) => openObject(row)

// 对标 E5「可分享视图」：本页把 page/pageSize 放在 query 里，所以组合式传 null、
// 由它认 state 上的这两个键；defaults 明确 1/10 是默认值，免得链接里出现 ?page=1。
// 审计页是最典型的分享场景：「按这个条件筛出来的日志」发给同事。
useUrlFilters(query, null, null, { defaults: { page: 1, pageSize: 10 } })

// 每页条数变化回到第 1 页
const handleSizeChange = () => {
  query.page = 1
  loadLogs()
}

// 导出当前筛选结果为 CSV：文件由后端生成，前端只负责触发浏览器下载
const handleExport = async () => {
  // 1. 置导出态：按钮 loading，避免重复导出
  exporting.value = true
  try {
    // 2. 让后端按当前筛选条件生成 CSV
    const blob = await exportLogs(query)
    // 3. 交给浏览器触发下载（文件名带可读时间戳）
    saveBlob(blob, `audit-logs-${auditStamp()}.csv`)
  } catch {
    // 拦截器已按实际状态（超时 / 无权限 / 服务异常）给出提示，这里不再叠加泛化文案
  } finally {
    // 无论成败都复位导出态
    exporting.value = false
  }
}

// 进页面并行拉取「操作类型候选」与「首屏日志」
onMounted(() => {
  loadActions()
  loadLogs()
})
</script>

<style scoped>
.scope-tip {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
/* 筛选行：单行排列，窄屏自动换行 */
.filter-row {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  flex-wrap: wrap;
}
</style>
