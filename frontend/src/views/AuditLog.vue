<template>
  <!-- 操作日志页（管理员）：筛选 + 分页表格 + 导出 CSV + 按日期清理（清理前先归档） -->
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
        <!-- 导出 / 清理都依赖「日志可用」：加载失败时一并禁用，避免对空列表做写操作 -->
        <el-button :loading="exporting" :disabled="!available" @click="handleExport">导出 CSV</el-button>
        <el-button type="danger" plain :disabled="!available" @click="purgeVisible = true">清 理</el-button>
        <span class="tip">共 {{ total }} 条</span>
      </div>

      <!-- 清理确认弹窗：内容只有一段说明 + 一个日期选择，用窄弹窗（440px）即可，
           不必套页面里其它弹窗的左右分栏形态 -->
      <el-dialog v-model="purgeVisible" title="清理操作日志" width="440px">
        <p class="purge-tip">
          将清理所选日期<b>之前</b>的日志。系统先把它们导出为归档 CSV 存到 <code>logs/</code>，
          <b>归档成功后才删除</b>；本次清理本身也会记入审计。
        </p>
        <el-date-picker
          v-model="purgeDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择截止日期（清理该日之前）"
          :disabled-date="(d) => d.getTime() > Date.now()"
          style="width: 100%"
        />
        <template #footer>
          <el-button @click="purgeVisible = false">取消</el-button>
          <el-button type="danger" :loading="purging" :disabled="!purgeDate" @click="handlePurge">
            清理
          </el-button>
        </template>
      </el-dialog>

      <el-table v-loading="loading" :data="logs" border stripe style="margin-top: 12px">
        <el-table-column label="操作时间" width="170">
          <template #default="{ row }">{{ (row.logTime || '').replace('T', ' ').substring(0, 19) }}</template>
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
        <el-table-column prop="ip" label="IP" width="130" />
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
        :page-sizes="[10, 20, 50]"
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
// 对外提供三个入口 —— 查询/分页（只读）、导出 CSV、按日期清理（后端先归档再删）。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import { getLogs, getLogActions, exportLogs, purgeLogs } from '@/api/log'
import { saveBlob } from '@/utils/download'
import { confirmBox } from '@/utils/confirm'

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
const loadLogs = async () => {
  // 1. 置加载态：表格进入 loading
  loading.value = true
  try {
    // 2. 按当前查询条件（类型 / 关键字 / 分页）拉取日志
    const res = await getLogs(query)
    // 3. 回填列表与总数，并标记日志可用（导出 / 清理按钮据此解禁）
    logs.value = res.data?.list || []
    total.value = res.data?.total || 0
    available.value = true
  } catch {
    logs.value = []
    total.value = 0
    // 失败即清空并标记不可用：不退化成展示编造的日志，空态给出重试入口
    available.value = false
  } finally {
    // 无论成败都复位加载态
    loading.value = false
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

// 清理弹窗状态：purgeDate 为空时「清理」按钮不可点
const purgeVisible = ref(false)
const purgeDate = ref('')
const purging = ref(false)

// 清理流程：二次确认 → 后端归档并删除 → 刷新列表
const handlePurge = async () => {
  // 1. 二次确认（交互形态统一走 utils/confirm.js）
  if (!(await confirmBox(`确定清理 ${purgeDate.value} 之前的日志吗？会先归档到 logs/ 再删除。`,
    '清理操作日志'))) {
    return
  }
  purging.value = true
  try {
    // 2. 后端先归档到 logs/ 再删除，返回删除条数与归档文件名
    const res = await purgeLogs(purgeDate.value)
    const d = res.data || {}
    const n = d.deleted || 0
    // 3. 提示文案区分「清理了 N 条」与「本就没有可清理的」，不让空结果看起来像失败
    ElMessage.success(n > 0 ? `已清理 ${n} 条，归档 ${d.archivedFile}` : '该日期之前没有日志，无需清理')
    purgeVisible.value = false
    purgeDate.value = ''
    query.page = 1
    // 4. 刷新列表；操作类型候选也可能随清理变化，一并重取
    loadLogs()
    loadActions()
  } finally {
    purging.value = false
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
/* 清理弹窗的说明文案：多行阅读，行距放宽 */
.purge-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-sub);
}
.purge-tip code {
  background: var(--paper);
  padding: 0 4px;
  border-radius: 3px;
}
</style>
