<template>
  <div>
    <PanelCard title="我的课题组">
      <!-- 按身份切三种视图：组长（成员管理）/ 组员（组信息）/ 无组（引导） -->
      <div v-if="loading" v-loading="loading" class="my-group-loading" />

      <!-- ⚠️ 无组：待分配池 / 审批中的引导页（路由守卫已拦截数据页，这里必须把话说清） -->
      <div v-else-if="!data.group && !data.pendingApplication" class="my-group-empty">
        <el-empty description="你还没有加入任何课题组">
          <template #description>
            <p class="empty-hint">你的账号位于<b>待分配池</b>，看不到任何病历数据。</p>
            <p class="empty-hint">等某个组长从池里把你拉入后即可看到本组数据。</p>
          </template>
          <router-link to="/register">
            <el-button type="primary" size="small">或自己去申请创建课题组</el-button>
          </router-link>
        </el-empty>
      </div>

      <!-- 审批中：申请人视图 -->
      <div v-else-if="data.pendingApplication" class="pending-block">
        <el-result icon="info" title="课题组申请已提交" sub-title="等管理员审批，通过后你就是该组组长">
        </el-result>
        <el-descriptions :column="1" border class="pending-desc">
          <el-descriptions-item label="组编码">{{ data.pendingApplication.code }}</el-descriptions-item>
          <el-descriptions-item label="组名称">{{ data.pendingApplication.name }}</el-descriptions-item>
          <el-descriptions-item v-if="data.pendingApplication.rejectReason" label="拒绝理由">
            {{ data.pendingApplication.rejectReason }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 有组：组长 / 组员共用 -->
      <div v-else>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="组名称">{{ data.group.name }}</el-descriptions-item>
          <el-descriptions-item label="组编码">{{ data.group.code }}</el-descriptions-item>
          <el-descriptions-item label="我的身份">
            {{ data.myRole === 'owner' ? '组长' : '组员' }}
          </el-descriptions-item>
          <el-descriptions-item label="成员数">{{ data.group.memberCount }}</el-descriptions-item>
        </el-descriptions>

        <!-- 组长操作区 -->
        <template v-if="data.myRole === 'owner'">
          <div class="owner-actions">
            <el-button type="primary" size="small" @click="loadMembers">刷新成员</el-button>
            <el-button size="small" @click="addDialog = true">从待分配池拉人</el-button>
            <el-button size="small" type="danger" plain @click="doLeave" :loading="leaving">
              退出课题组
            </el-button>
          </div>
          <el-table v-loading="membersLoading" :data="members" border stripe style="margin-top: 12px">
            <el-table-column prop="username" label="用户名" min-width="120" />
            <el-table-column label="角色" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.role === 'owner' ? 'warning' : 'info'" effect="plain">
                  {{ row.role === 'owner' ? '组长' : '组员' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="joinTime" label="加入时间" width="180">
              <template #default="{ row }">{{ fmtDateTime(row.joinTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <template v-if="row.role !== 'owner'">
                  <el-button link type="primary" size="small" @click="doTransfer(row)">转让组长</el-button>
                  <el-button link type="danger" size="small" @click="doRemove(row)">移除</el-button>
                </template>
                <span v-else class="is-owner">（我）</span>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <!-- 组员只给信息，不给任何操作入口 -->
        <template v-else>
          <p class="member-note">组员在本组只有读权限；需要拉人 / 转让等操作请联系组长。</p>
        </template>
      </div>

      <!-- 拉人弹窗 -->
      <el-dialog v-model="addDialog" title="从待分配池拉人" width="480px">
        <el-select v-model="pickUserId" placeholder="选择待分配用户" style="width: 100%" filterable>
          <el-option v-for="u in poolUsers" :key="u.id" :label="u.username" :value="u.id" />
        </el-select>
        <template #footer>
          <el-button @click="addDialog = false">取消</el-button>
          <el-button type="primary" :disabled="!pickUserId" @click="doAdd">拉入本组</el-button>
        </template>
      </el-dialog>
    </PanelCard>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmBox } from '@/utils/confirm'
import PanelCard from '@/components/PanelCard.vue'
import { getMyGroup, listMembers, addMember, removeMember, transferOwner, leaveGroup, listPendingUsers } from '@/api/group'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'

const userStore = useUserStore()
const loading = ref(true)
const data = ref({ group: null, myRole: null, pendingApplication: null })
const members = ref([])
const membersLoading = ref(false)
const leaving = ref(false)
const addDialog = ref(false)
const pickUserId = ref('')
const poolUsers = ref([])

const loadMyGroup = async () => {
  try {
    const res = await getMyGroup()
    data.value = res.data || {}
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const loadMembers = async () => {
  if (!data.value.group) return
  membersLoading.value = true
  try {
    const res = await listMembers(data.value.group.id)
    members.value = res.data || []
  } catch {
    // 拦截器已提示
  } finally {
    membersLoading.value = false
  }
}

const loadPool = async () => {
  try {
    const res = await listPendingUsers()
    poolUsers.value = res.data || []
  } catch {
    // 拦截器已提示
  }
}

const doAdd = async () => {
  if (!pickUserId.value) return
  try {
    await addMember(data.value.group.id, pickUserId.value)
    ElMessage.success('已拉入本组')
    addDialog.value = false
    pickUserId.value = ''
    loadMembers()
    loadMyGroup()
  } catch {
    // 拦截器已提示
  }
}

const doRemove = async (row) => {
  if (!(await confirmBox(`确定将「${row.username}」移出本组？`, '移除成员', { type: 'warning' }))) {
    return
  }
  try {
    await removeMember(data.value.group.id, row.userId)
    ElMessage.success('已移除')
    loadMembers()
    loadMyGroup()
  } catch {
    // 拦截器已提示
  }
}

const doTransfer = async (row) => {
  if (!(await confirmBox(`把组长转让给「${row.username}」？转让后你自动降为组员。`, '转让组长', { type: 'warning' }))) {
    return
  }
  try {
    await transferOwner(data.value.group.id, row.userId)
    ElMessage.success('组长已转让')
    loadMembers()
    loadMyGroup()
  } catch {
    // 拦截器已提示
  }
}

const doLeave = async () => {
  if (!(await confirmBox('退出后你立即失去本组数据访问权限。确定退出？', '退出课题组', { type: 'warning' }))) {
    return
  }
  leaving.value = true
  try {
    await leaveGroup(data.value.group.id)
    ElMessage.success('已退出')
    userStore.logout()
    location.href = '/login'
  } catch {
    // 拦截器已提示
  } finally {
    leaving.value = false
  }
}

onMounted(() => {
  loadMyGroup()
  // 拉人弹窗的候选需要待分配池，进入页面就预热
  loadPool()
})
</script>

<style scoped>
.my-group-loading {
  min-height: 160px;
}
.my-group-empty {
  padding: 24px 0;
}
.empty-hint {
  margin: 0 0 4px;
  color: var(--text-sub);
}
.pending-block {
  padding: 8px 0;
}
.pending-desc {
  margin-top: 12px;
}
.owner-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
  align-items: center;
}
.member-note {
  margin-top: 16px;
  color: var(--text-sub);
}
.is-owner {
  color: var(--text-sub);
}
</style>