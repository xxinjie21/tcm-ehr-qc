<template>
  <!-- 操作日志页（管理员）：筛选 + 分页表格 + 导出 CSV（§七 L3 起无日志删除入口） -->
  <div>
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
          style="width: 240px"
          @keyup.enter="loadLogs"
        />
        <el-button type="primary" :loading="loading" @click="loadLogs">查 询</el-button>
        <!-- 导出依赖「日志可用」：加载失败时禁用，避免对空列表做导出 -->
        <el-button :loading="exporting" :disabled="!available" @click="handleExport">导出 CSV</el-button>
        <span class="tip">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="logs" border stripe style="margin-top: 12px">
        <el-table-column label="操作时间" width="170">
          <template #default="{ row }">{{ fmtDateTime(row.logTime) }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column prop="role" label="角色" width="90" />
        <el-table-column label="操作类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.action)" effect="plain">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="target" label="操作对象" width="200" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
        <!-- 空态分两种：确实没日志 vs 加载失败（后者才给「重试」入口） -->
        <template #empty>
          <el-empty
            :description="available ? '暂无日志记录' : '日志加载失败'"
            :image-size="80"
          >
            <el-button v-if="!available" size="small" @click="loadLogs">重 试</el-button>
          </el-empty>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :page-sizes="PAGE_SIZES"
        :total="total"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
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
import { onMounted, reactive, ref } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import { getLogs, getLogActions, exportLogs } from '@/api/log'
import { saveBlob } from '@/utils/download'

/** 图例配色；具体选项由后端返回，未匹配到的走默认色 */
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

// 查询条件；page / size 直接双向绑定分页组件
const query = reactive({ action: '', keyword: '', page: 1, size: 10 })
const logs = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
// 加载失败（超时 / 服务异常 / 无权限）置 false，空态给出「重试」入口；
// 无论何种情况都不退化成展示编造的日志
const available = ref(true)

// 操作类型 → el-tag 配色；未登记的走默认色
const tagType = (action) => TAG_TYPES[action] || 'primary'

// 拉取日志列表：成功后刷新总数；失败则清空并标记不可用（供空态与按钮禁用判断）
// latest-wins：发起时取号，回来时号不是最新就整体丢弃 —— 快速连点查询/翻页时慢的旧响应
// 不覆盖新结果，也不提前收掉 loading（范式同 components/TermInput.vue）
let listSeq = 0
const loadLogs = async () => {
  // 0. 取本次请求的号
  const mine = ++listSeq
  // 1. 置加载态：表格进入 loading
  loading.value = true
  try {
    // 2. 按当前查询条件（类型 / 关键字 / 分页）拉取日志
    const res = await getLogs(query)
    if (mine !== listSeq) return
    // 3. 回填列表与总数，并标记日志可用（导出按钮据此解禁）
    logs.value = res.data?.list || []
    total.value = res.data?.total || 0
    available.value = true
  } catch {
    if (mine !== listSeq) return
    logs.value = []
    total.value = 0
    // 失败即清空并标记不可用：不退化成展示编造的日志，空态给出重试入口
    available.value = false
  } finally {
    // 只有最新一次请求才复位加载态
    if (mine === listSeq) loading.value = false
  }
}

/** 每页条数变化回到第 1 页*/
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
/* 筛选行：单行排列，窄屏自动换行 */
.filter-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
/* 右侧条数提示 */
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
}
</style>
