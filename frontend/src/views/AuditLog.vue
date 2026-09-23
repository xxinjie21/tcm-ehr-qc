<template>
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
        <el-button :loading="exporting" :disabled="!available" @click="handleExport">导出 CSV</el-button>
        <el-button type="danger" plain :disabled="!available" @click="purgeVisible = true">清 理</el-button>
        <span class="tip">共 {{ total }} 条</span>
      </div>

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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import { getLogs, getLogActions, exportLogs, purgeLogs } from '@/api/log'
import { saveBlob } from '@/utils/download'

/** 图例配色；具体选项由后端返回（UX-19），未匹配到的走默认色 */
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

const actionOptions = ref([])

const loadActions = async () => {
  try {
    const res = await getLogActions()
    actionOptions.value = res.data || []
  } catch {
    actionOptions.value = []
  }
}

const query = reactive({ action: '', keyword: '', page: 1, size: 10 })
const logs = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
// 加载失败（超时 / 服务异常 / 无权限）置 false，空态给出「重试」入口；
// 无论何种情况都不退化成展示编造的日志
const available = ref(true)

const tagType = (action) => TAG_TYPES[action] || 'primary'

const loadLogs = async () => {
  loading.value = true
  try {
    const res = await getLogs(query)
    logs.value = res.data?.list || []
    total.value = res.data?.total || 0
    available.value = true
  } catch {
    logs.value = []
    total.value = 0
    available.value = false
  } finally {
    loading.value = false
  }
}

/** 每页条数变化回到第 1 页（UX-24） */
const handleSizeChange = () => {
  query.page = 1
  loadLogs()
}

const handleExport = async () => {
  exporting.value = true
  try {
    const blob = await exportLogs(query)
    saveBlob(blob, `audit_logs_${Date.now()}.csv`)
  } catch {
    // 拦截器已按实际状态（超时 / 无权限 / 服务异常）给出提示，这里不再叠加泛化文案
  } finally {
    exporting.value = false
  }
}

const purgeVisible = ref(false)
const purgeDate = ref('')
const purging = ref(false)

const handlePurge = async () => {
  await ElMessageBox.confirm(
    `确定清理 ${purgeDate.value} 之前的日志吗？会先归档到 logs/ 再删除。`,
    '清理操作日志',
    { type: 'warning' }
  )
  purging.value = true
  try {
    const res = await purgeLogs(purgeDate.value)
    const d = res.data || {}
    const n = d.deleted || 0
    ElMessage.success(n > 0 ? `已清理 ${n} 条，归档 ${d.archivedFile}` : '该日期之前没有日志，无需清理')
    purgeVisible.value = false
    purgeDate.value = ''
    query.page = 1
    loadLogs()
    loadActions()
  } finally {
    purging.value = false
  }
}

onMounted(() => {
  loadActions()
  loadLogs()
})
</script>

<style scoped>
.filter-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
}
.purge-tip {
  margin: 0 0 12px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--text-sub);
}
.purge-tip code {
  background: var(--paper);
  padding: 0 4px;
  border-radius: 3px;
}
</style>
