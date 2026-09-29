<template>
  <div>
    <PanelCard title="组织管理">
      <el-tabs v-model="tab" @tab-change="load">
        <el-tab-pane label="待审批创建组织" name="pending">
          <el-table v-loading="loading" :data="pendingGroups" border stripe>
            <el-table-column prop="code" label="组编码" width="140" />
            <el-table-column prop="name" label="组名称" min-width="160" />
            <el-table-column prop="appliedBy" label="申请人" width="120" />
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="doApprove(row)">通过</el-button>
                <el-button link type="danger" size="small" @click="rejectRow = row; rejectVisible = true">
                  拒绝
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="组列表" name="list">
          <el-table v-loading="loading" :data="groups" border stripe>
            <el-table-column prop="code" label="组编码" width="130" />
            <el-table-column prop="name" label="组名称" min-width="140" />
            <el-table-column label="所有者" width="120">
              <template #default="{ row }">{{ row.ownerName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="memberCount" label="成员数" width="80" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'active' ? 'success'
                  : row.status === 'stopped' ? 'danger' : row.status === 'rejected' ? 'info' : 'warning'" effect="plain">
                  {{ statusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <template v-if="row.status === 'active'">
                  <el-button link type="danger" size="small" @click="doStop(row)">停用</el-button>
                </template>
                <template v-else-if="row.status === 'stopped'">
                  <el-button link type="success" size="small" @click="doActivate(row)">恢复</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="待加入用户" name="pool">
          <el-table v-loading="loading" :data="poolUsers" border stripe>
            <el-table-column prop="username" label="用户名" min-width="160" />
            <el-table-column prop="createTime" label="注册时间" width="200">
              <template #default="{ row }">{{ fmtDateTime(row.createTime) }}</template>
            </el-table-column>
          </el-table>
          <p class="pool-tip">待加入的用户只有所有者「我的组织」页里的拉人入口能接收。</p>
        </el-tab-pane>
      </el-tabs>

      <!-- 拒绝弹窗：理由必填（拒绝后编码会被改写释放） -->
      <el-dialog v-model="rejectVisible" title="拒绝创建组织申请" width="440px">
        <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="拒绝理由（必填，申请人可见）" />
        <template #footer>
          <el-button @click="rejectVisible = false">取消</el-button>
          <el-button type="danger" :disabled="!rejectReason.trim()" @click="doReject">确认拒绝</el-button>
        </template>
      </el-dialog>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import { listOrgs, approveGroup, rejectGroup, stopGroup, activateGroup, listPendingUsers } from '@/api/org'
import { fmtDateTime } from '@/utils/format'

const tab = ref('pending')
const loading = ref(false)
const groups = ref([])
const pendingGroups = ref([])
const poolUsers = ref([])
const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectRow = ref(null)

const statusText = (s) => ({ active: '生效', stopped: '已停用', rejected: '已拒绝', pending: '待审批' }[s] || s)

const load = async () => {
  loading.value = true
  try {
    const [g, pool] = await Promise.all([listOrgs(), listPendingUsers()])
    groups.value = g.data || []
    pendingGroups.value = (g.data || []).filter((x) => x.status === 'pending')
    poolUsers.value = pool.data || []
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const doApprove = async (row) => {
  try {
    await approveGroup(row.id)
    ElMessage.success(`已通过「${row.name}」，${row.ownerName || '申请人'} 成为所有者`)
    load()
  } catch {
    // 拦截器已提示
  }
}

const doReject = async () => {
  if (!rejectReason.value.trim() || !rejectRow.value) return
  try {
    await rejectGroup(rejectRow.value.id, rejectReason.value.trim())
    ElMessage.success('已拒绝，编码已被释放可重新申请')
    rejectVisible.value = false
    rejectReason.value = ''
    rejectRow.value = null
    load()
  } catch {
    // 拦截器已提示
  }
}

const doStop = async (row) => {
  try {
    await stopGroup(row.id)
    ElMessage.warning(`「${row.name}」已停用：成员将无法登录，数据保留`)
    load()
  } catch {
    // 拦截器已提示
  }
}

const doActivate = async (row) => {
  try {
    await activateGroup(row.id)
    ElMessage.success(`「${row.name}」已恢复`)
    load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<style scoped>
.pool-tip {
  margin-top: 12px;
  font-size: 12.5px;
  color: var(--text-sub);
}
</style>