<template>
  <div>
    <PanelCard title="组织管理">
      <!-- 组织列表（仅管理员）：批次 6 起组织由用户自助创建、无审核，
           管理员只做治理 —— 停用 / 恢复 / 归档 / 改派所有者 -->
      <el-table v-loading="loading" element-loading-text="正在读取组织列表…" :data="orgs" border stripe>
        <el-table-column prop="code" label="组织编码" width="130" />
        <el-table-column prop="name" label="组织名称" min-width="140" />
        <el-table-column label="所有者" width="120">
          <template #default="{ row }">{{ row.ownerName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="memberCount" label="成员数" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'stopped' ? 'danger' : row.status === 'archived' ? 'info' : 'success'"
              effect="plain"
            >{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ fmtDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button v-if="row.status === 'active'" link type="warning" size="small" @click="doStop(row)">停用</el-button>
            <el-button v-else-if="row.status === 'stopped'" link type="primary" size="small" @click="doActivate(row)">恢复</el-button>
            <el-button
              v-if="row.status !== 'archived'"
              link
              type="info"
              size="small"
              @click="archiveRow = row; archiveVisible = true"
            >归档</el-button>
            <el-button link type="primary" size="small" @click="openReassign(row)">改派所有者</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PanelCard>

    <!-- 归档（前提成员数为 0） -->
    <el-dialog v-model="archiveVisible" title="归档组织" width="440px">
      <p class="tip">归档后该组织不再出现在生效列表；仅当成员数为 0 时允许。</p>
      <el-input v-model="archiveReason" type="textarea" :rows="3" placeholder="归档原因（必填）" />
      <template #footer>
        <el-button @click="archiveVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!archiveReason.trim()" @click="doArchive">确认归档</el-button>
      </template>
    </el-dialog>

    <!-- 改派所有者（owner 账号丢失时的兜底） -->
    <el-dialog v-model="reassignVisible" title="改派所有者" width="440px">
      <p class="tip">把「{{ reassignRow?.name }}」的所有者改为下面选中的成员；原所有者降为成员。</p>
      <!-- 继任者只能来自「当前组成员」：原先是裸的用户 ID 输入框，
           既看不到用户名、又允许填组织外的人（后端会报「该用户不在此组织」）。 -->
      <el-select
        v-model="newOwnerUserId"
        placeholder="选择本组织内的成员"
        style="width: 100%"
        filterable
        :loading="reassignLoading"
      >
        <el-option
          v-for="m in reassignCandidates"
          :key="m.userId"
          :label="m.username"
          :value="m.userId"
        />
      </el-select>
      <p v-if="!reassignLoading && !reassignCandidates.length" class="tip">
        该组织没有可选成员（归档前提就是成员数为 0，故无法改派）
      </p>
      <template #footer>
        <el-button @click="reassignVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!newOwnerUserId || !reassignCandidates.length" @click="doReassign">确认改派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 组织管理（仅管理员）：批次 6 起组织由用户自助创建、无审核，
// 管理员只做治理 —— 停用 / 恢复 / 归档 / 改派所有者。
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import { listOrgs, stopGroup, activateGroup, archiveOrg, reassignOwner, listMembers } from '@/api/org'
import { fmtDateTime } from '@/utils/format'

const loading = ref(false)
const orgs = ref([])

const archiveVisible = ref(false)
const archiveReason = ref('')
const archiveRow = ref(null)
const reassignVisible = ref(false)
const reassignRow = ref(null)
const newOwnerUserId = ref('')
// 改派候选：该组织的当前成员（不提供全站用户搜索 —— 继任者必须本来就在组织里）
const reassignCandidates = ref([])
const reassignLoading = ref(false)

const statusText = (s) => ({ active: '生效', stopped: '已停用', archived: '已归档' }[s] || s)

const load = async () => {
  loading.value = true
  try {
    const res = await listOrgs()
    orgs.value = res.data || []
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
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

const doArchive = async () => {
  if (!archiveReason.value.trim() || !archiveRow.value) return
  try {
    await archiveOrg(archiveRow.value.id, archiveReason.value.trim())
    ElMessage.success('已归档')
    archiveVisible.value = false
    archiveReason.value = ''
    archiveRow.value = null
    load()
  } catch {
    // 拦截器已提示（成员数不为 0 时后端会拒绝并给出原因）
  }
}

/** 打开改派弹窗：先载入该组织成员作为候选人 */
const openReassign = async (row) => {
  reassignRow.value = row
  newOwnerUserId.value = ''
  reassignCandidates.value = []
  reassignVisible.value = true
  reassignLoading.value = true
  try {
    const res = await listMembers(row.id)
    reassignCandidates.value = (res.data || []).filter((m) => m.role !== 'owner')
  } catch {
    // 拦截器已提示
  } finally {
    reassignLoading.value = false
  }
}

const doReassign = async () => {
  if (!newOwnerUserId.value || !reassignRow.value) return
  try {
    await reassignOwner(reassignRow.value.id, newOwnerUserId.value)
    ElMessage.success('已改派所有者')
    reassignVisible.value = false
    newOwnerUserId.value = ''
    reassignRow.value = null
    load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<style scoped>
.tip {
  color: var(--text-sub);
  font-size: 12.5px;
  margin: 0 0 var(--sp-2);
}
</style>
