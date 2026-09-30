<template>
  <div>
    <PanelCard title="我的组织">
      <!-- 按身份切三种视图：所有者（成员管理）/ 成员（组织信息）/ 未加入组织（引导） -->
      <div v-if="loading" v-loading="loading" class="my-org-loading" />

      <!-- 未加入组织：批次 6 起可自助创建；无「待加入池」也不再有「审批中」 -->
      <div v-else-if="!data.org" class="my-org-empty">
        <el-empty description="你还没有加入任何组织">
          <template #description>
            <p class="empty-hint">你可以自己创建一个组织（创建后你就是所有者），</p>
            <p class="empty-hint">也可以等待某个组织的所有者按用户名把你拉入。</p>
          </template>
          <el-button type="primary" size="small" @click="createDialog = true">创建组织</el-button>
        </el-empty>
      </div>

      <!-- 有组织：所有者 / 成员共用 -->
      <div v-else>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="组织名称">{{ data.org.name }}</el-descriptions-item>
          <el-descriptions-item label="组织编码">{{ data.org.code }}</el-descriptions-item>
          <el-descriptions-item label="我的身份">
            {{ data.myRole === 'owner' ? '所有者' : '成员' }}
          </el-descriptions-item>
          <el-descriptions-item label="成员数">{{ data.org.memberCount }}</el-descriptions-item>
        </el-descriptions>

        <!-- 所有者操作区 -->
        <template v-if="data.myRole === 'owner'">
          <div class="owner-actions">
            <el-button type="primary" size="small" @click="loadMembers">刷新成员</el-button>
            <el-button size="small" @click="addDialog = true">按用户名拉人</el-button>
            <el-button size="small" type="danger" plain :loading="leaving" @click="doLeave">退出组织</el-button>
          </div>
          <el-table v-loading="membersLoading" :data="members" border stripe style="margin-top: 12px">
            <el-table-column prop="username" label="用户名" min-width="120" />
            <el-table-column label="角色" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.role === 'owner' ? 'warning' : 'info'" effect="plain">
                  {{ row.role === 'owner' ? '所有者' : '成员' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="写权限" width="220">
              <template #default="{ row }">
                <template v-if="row.role !== 'owner'">
                  <el-checkbox
                    :model-value="row.canWriteDictionary === 1"
                    @change="(v) => togglePermission(row, 'dictionary', v)"
                  >词典</el-checkbox>
                  <el-checkbox
                    :model-value="row.canWriteQcRules === 1"
                    @change="(v) => togglePermission(row, 'qcRules', v)"
                  >质控规则</el-checkbox>
                </template>
                <span v-else class="is-owner">全部</span>
              </template>
            </el-table-column>
            <el-table-column prop="joinTime" label="加入时间" width="180">
              <template #default="{ row }">{{ fmtDateTime(row.joinTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <template v-if="row.role !== 'owner'">
                  <el-button link type="primary" size="small" @click="doTransfer(row)">转让所有者</el-button>
                  <el-button link type="danger" size="small" @click="doRemove(row)">移除</el-button>
                </template>
                <span v-else class="is-owner">（我）</span>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <!-- 成员只给信息，不给任何操作入口 -->
        <template v-else>
          <p class="member-note">成员在本组织只有读权限；需要拉人 / 转让等操作请联系所有者。</p>
        </template>
      </div>

      <!-- 创建组织 -->
      <el-dialog v-model="createDialog" title="创建组织" width="480px">
        <el-form label-width="88px">
          <el-form-item label="组织名称">
            <el-input v-model="createForm.name" maxlength="100" placeholder="必填" />
          </el-form-item>
          <el-form-item label="组织编码">
            <el-input v-model="createForm.code" placeholder="可留空；大写字母/数字/连字符，2~50" />
          </el-form-item>
          <el-form-item label="用途说明">
            <el-input v-model="createForm.purpose" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createDialog = false">取消</el-button>
          <el-button type="primary" :loading="creating" :disabled="!createForm.name.trim()" @click="doCreate">
            创建并成为所有者
          </el-button>
        </template>
      </el-dialog>

      <!-- 按用户名拉人 -->
      <el-dialog v-model="addDialog" title="按用户名拉人" width="480px">
        <el-input
          v-model="keyword"
          placeholder="输入用户名（至少 2 个字符）后点搜索"
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button :loading="searching" @click="doSearch">搜索</el-button>
          </template>
        </el-input>
        <el-select v-model="pickUserId" placeholder="搜索结果" style="width: 100%; margin-top: 12px" filterable>
          <el-option v-for="u in candidates" :key="u.id" :label="u.username" :value="u.id" />
        </el-select>
        <template #footer>
          <el-button @click="addDialog = false">取消</el-button>
          <el-button type="primary" :disabled="!pickUserId" @click="doAdd">拉入本组织</el-button>
        </template>
      </el-dialog>
    </PanelCard>
  </div>
</template>

<script setup>
// 我的组织：所有者管理成员（按用户名搜索拉人 / 授权 / 移除 / 转让）、成员看组织信息、
// 未加入组织可自助创建。批次 6 起无审批、无「待加入池」。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmBox } from '@/utils/confirm'
import PanelCard from '@/components/PanelCard.vue'
import {
  getMyOrg, listMembers, addMember, removeMember, transferOwner, leaveGroup,
  createOrg, searchUsers, setPermissions
} from '@/api/org'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'

const userStore = useUserStore()
const loading = ref(true)
const data = ref({ org: null, myRole: null })
const members = ref([])
const membersLoading = ref(false)
const leaving = ref(false)

const createDialog = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', code: '', purpose: '' })

const addDialog = ref(false)
const pickUserId = ref('')
const keyword = ref('')
const searching = ref(false)
const candidates = ref([])

const loadMyOrg = async () => {
  try {
    const res = await getMyOrg()
    data.value = res.data || {}
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const loadMembers = async () => {
  if (!data.value.org) return
  membersLoading.value = true
  try {
    const res = await listMembers(data.value.org.id)
    members.value = res.data || []
  } catch {
    // 拦截器已提示
  } finally {
    membersLoading.value = false
  }
}

const doCreate = async () => {
  if (!createForm.name.trim()) return
  creating.value = true
  try {
    await createOrg({
      name: createForm.name.trim(),
      code: createForm.code.trim() || null,
      purpose: createForm.purpose.trim() || null
    })
    ElMessage.success('已创建，你是该组织所有者')
    createDialog.value = false
    createForm.name = ''
    createForm.code = ''
    createForm.purpose = ''
    await loadMyOrg()
    loadMembers()
  } catch {
    // 拦截器已提示（配额 / 编码 / 重名后端会给出具体原因）
  } finally {
    creating.value = false
  }
}

const doSearch = async () => {
  if (keyword.value.trim().length < 2) {
    ElMessage.warning('关键词至少 2 个字符')
    return
  }
  searching.value = true
  try {
    const res = await searchUsers(keyword.value.trim())
    candidates.value = res.data || []
    if (!candidates.value.length) ElMessage.info('没有匹配的用户')
  } catch {
    // 拦截器已提示
  } finally {
    searching.value = false
  }
}

const doAdd = async () => {
  if (!pickUserId.value) return
  try {
    await addMember(data.value.org.id, pickUserId.value)
    ElMessage.success('已拉入本组织')
    addDialog.value = false
    pickUserId.value = ''
    keyword.value = ''
    candidates.value = []
    loadMembers()
    loadMyOrg()
  } catch {
    // 拦截器已提示
  }
}

const togglePermission = async (row, which, checked) => {
  try {
    const body = which === 'dictionary'
      ? { canWriteDictionary: checked }
      : { canWriteQcRules: checked }
    await setPermissions(data.value.org.id, row.userId, body)
    ElMessage.success('权限已更新')
    loadMembers()
  } catch {
    // 拦截器已提示
    loadMembers()
  }
}

const doRemove = async (row) => {
  if (!(await confirmBox(`确定将「${row.username}」移出本组织？`, '移除成员', { type: 'warning' }))) {
    return
  }
  try {
    await removeMember(data.value.org.id, row.userId)
    ElMessage.success('已移除')
    loadMembers()
    loadMyOrg()
  } catch {
    // 拦截器已提示
  }
}

const doTransfer = async (row) => {
  if (!(await confirmBox(`把所有者转让给「${row.username}」？转让后你自动降为成员。`, '转让所有者', { type: 'warning' }))) {
    return
  }
  try {
    await transferOwner(data.value.org.id, row.userId)
    ElMessage.success('所有者已转让')
    loadMembers()
    loadMyOrg()
  } catch {
    // 拦截器已提示
  }
}

const doLeave = async () => {
  if (!(await confirmBox('退出后你立即失去本组织数据访问权限。确定退出？', '退出组织', { type: 'warning' }))) {
    return
  }
  leaving.value = true
  try {
    await leaveGroup(data.value.org.id)
    ElMessage.success('已退出')
    userStore.logout()
    location.href = '/login'
  } catch {
    // 拦截器已提示
  } finally {
    leaving.value = false
  }
}

onMounted(loadMyOrg)
</script>

<style scoped>
.my-org-loading {
  min-height: 160px;
}
.my-org-empty {
  padding: 24px 0;
}
.empty-hint {
  margin: 0 0 4px;
  color: var(--text-sub);
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
