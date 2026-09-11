<template>
  <div>
    <PanelCard title="操作日志">
      <div class="filter-row">
        <el-select v-model="query.action" placeholder="操作类型" clearable style="width: 150px">
          <el-option v-for="(label, key) in ACTION_LABELS" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="操作人 / 对象关键字"
          clearable
          style="width: 240px"
          @keyup.enter="loadLogs"
        />
        <el-button type="primary" @click="loadLogs">查 询</el-button>
        <el-button :loading="exporting" @click="handleExport">导出 CSV</el-button>
        <span class="tip">共 {{ total }} 条</span>
      </div>

      <el-table :data="logs" border stripe style="margin-top: 12px">
        <el-table-column prop="time" label="操作时间" width="160" />
        <el-table-column prop="username" label="操作人" width="100" />
        <el-table-column prop="role" label="角色" width="90" />
        <el-table-column label="操作类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.action)" effect="plain">{{ ACTION_LABELS[row.action] || row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="target" label="操作对象" width="200" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" />
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="loadLogs"
      />
    </PanelCard>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import { exportLogs } from '@/api/log'
import { saveBlob } from '@/utils/download'

// 联调切换：后端 /api/logs 就绪后置为 false
const USE_MOCK = true

const ACTION_LABELS = {
  login: '登录',
  import: '导入',
  review: '复核',
  clean: '清洗',
  dict: '词典维护',
  export: '导出'
}

const MOCK_LOGS = [
  { time: '2026-08-11 14:32:05', username: 'admin', role: '管理员', action: 'dict', target: '症状词典 symptoms.json', detail: '导入症状术语 128 条，版本已备份', ip: '127.0.0.1' },
  { time: '2026-08-11 14:20:41', username: 'admin', role: '管理员', action: 'import', target: '病历批量导入', detail: '上传「电子病历精简脱敏数据_500行.xlsx」，成功 500 条', ip: '127.0.0.1' },
  { time: '2026-08-11 11:05:18', username: 'auditor', role: '审核员', action: 'review', target: 'REC202601120002', detail: '人工复核通过，评分 78 → 85', ip: '127.0.0.1' },
  { time: '2026-08-11 10:47:33', username: 'admin', role: '管理员', action: 'clean', target: '全量病历', detail: '数据清洗：去重 5，修复 3，隔离 1', ip: '127.0.0.1' },
  { time: '2026-08-11 10:12:09', username: 'auditor', role: '审核员', action: 'login', target: '登录系统', detail: '登录成功', ip: '127.0.0.1' },
  { time: '2026-08-11 09:58:47', username: 'admin', role: '管理员', action: 'export', target: '标准数据集', detail: '导出 CSV 数据集（100 条）', ip: '127.0.0.1' },
  { time: '2026-08-10 17:22:15', username: 'admin', role: '管理员', action: 'dict', target: '中药词典 herbs.json', detail: '回滚到备份 herbs.json.bak_20260810_102233', ip: '127.0.0.1' }
]

const ACTION_ORDER = Object.keys(ACTION_LABELS)
const tagType = (action) => ({ login: 'info', review: 'success', clean: 'warning' }[action] || 'primary')

const query = reactive({ action: '', keyword: '', page: 1, size: 10 })
const logs = ref([])
const total = ref(0)
const exporting = ref(false)

const loadLogs = () => {
  if (USE_MOCK) {
    let list = [...MOCK_LOGS]
    if (query.action) list = list.filter((l) => l.action === query.action)
    if (query.keyword) {
      const k = query.keyword.toLowerCase()
      list = list.filter((l) => l.username.toLowerCase().includes(k) || l.target.toLowerCase().includes(k))
    }
    total.value = list.length
    logs.value = list.slice((query.page - 1) * query.size, query.page * query.size)
    return
  }
  // 真接口模式（成员A LogController 就绪后启用）
  import('@/api/log').then(({ getLogs }) =>
    getLogs(query).then((res) => {
      logs.value = res.data.list
      total.value = res.data.total
    })
  )
}

const handleExport = async () => {
  exporting.value = true
  try {
    if (USE_MOCK) {
      const header = '操作时间,操作人,角色,操作类型,操作对象,详情,IP\n'
      const rows = MOCK_LOGS.map((l) => [l.time, l.username, l.role, ACTION_LABELS[l.action], l.target, l.detail, l.ip].join(',')).join('\n')
      saveBlob(new Blob(['\uFEFF' + header + rows], { type: 'text/csv;charset=utf-8' }), `audit_logs_${Date.now()}.csv`)
      return
    }
    const blob = await exportLogs(query)
    saveBlob(blob, `audit_logs_${Date.now()}.csv`)
  } finally {
    exporting.value = false
  }
}

loadLogs()
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
</style>
