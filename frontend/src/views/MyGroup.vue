<template>
  <div>
    <PanelCard title="我的组织">
      <!-- 按身份切三种视图：所有者（成员管理）/ 成员（组织信息）/ 未加入组织（引导） -->
      <!-- 加载遮罩：容器常驻、遮罩只进出（与 Dashboard / Governance / Qc / Review 一致）。
           原写法是 <div v-if="loading" v-loading="loading"> —— 用 v-if 承载 v-loading，
           元素与遮罩同生同灭，遮罩移除与下一块内容挂载不同步，就会闪一下；
           而且没给 element-loading-text，会显示 Element Plus 默认的英文 Loading...。
           最小高度只在加载时给（.is-loading），避免不加载时一直占 160px。 -->
      <div
        v-loading="loading"
        element-loading-text="正在读取我的组织…"
        :class="['my-org-wrap', { 'is-loading': loading }]"
      >

      <!-- 未加入组织：批次 6 起可自助创建；无「待加入池」也不再有「审批中」 -->
      <div v-if="!loading && !data.org" class="my-org-empty">
        <!-- 复用共享空态组件（Dashboard / Records / Qc / NlpExtract 都用它）：
             原来这里是裸 el-empty，接口失败时与「真的没加入组织」显示成同一画面，
             用户会以为自己没有组织。EmptyState 区分 failed 并给「重试」出口。 -->
        <EmptyState v-if="orgFailed" failed :loading="loading"
                    text="组织信息加载失败，请重试" @retry="loadMyOrg" />
        <EmptyState v-else text="你还没有加入任何组织">
          <p class="empty-hint">你可以自己创建一个组织（创建后你就是所有者），</p>
          <p class="empty-hint">也可以等待某个组织的所有者按用户名把你拉入。</p>
          <el-button type="primary" size="small" @click="createDialog = true">创建组织</el-button>
        </EmptyState>
      </div>

      <!-- 有组织：所有者 / 成员共用 -->
      <div v-if="!loading && data.org">
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
          <el-table v-loading="membersLoading" element-loading-text="正在读取成员…" :data="members" border stripe style="margin-top: var(--sp-3)">
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
            <el-table-column label="操作" width="190">
              <template #default="{ row }">
                <template v-if="row.role !== 'owner'">
                  <el-button link type="primary" size="small" @click="doTransfer(row)">转让所有者</el-button>
                  <el-button link type="danger" size="small" @click="doRemove(row)">移除</el-button>
                </template>
                <span v-else class="is-owner">（我）</span>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState :failed="membersFailed" :loading="membersLoading"
                          text="该组织暂无成员" @retry="loadMembers" />
            </template>
          </el-table>
        </template>

        <!-- 成员只给信息，不给任何操作入口 -->
        <template v-else>
          <p class="member-note">成员在本组织只有读权限；需要拉人 / 转让等操作请联系所有者。</p>
        </template>
      </div>
      </div>

      <!-- 创建组织 -->
      <el-dialog v-if="createDialog" v-model="createDialog" title="创建组织" width="min(480px, 94vw)" top="10vh">
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
      <el-dialog v-if="addDialog" v-model="addDialog" title="按用户名拉人" width="min(480px, 94vw)" top="10vh">
        <!-- ⚠️ 原来用的是 #append：Element Plus 的 append 插槽把内容渲染在输入框
             **外面**（两个相邻的盒子），不是框内的按钮；而且它带 :loading 的
             el-button 会在搜索时重排，导致输入框宽度/边框跳一下（看着像闪屏）。
             改用 #suffix —— 框内右侧，和普通搜索框一致。 -->
        <el-input
          v-model="keyword"
          placeholder="输入用户名（至少 2 个字符）后点搜索"
          @keyup.enter="doSearch"
        >
          <template #suffix>
            <el-button link type="primary" :loading="searching" @click="doSearch">搜索</el-button>
          </template>
        </el-input>
        <el-select
          v-model="pickUserId"
          placeholder="搜索结果（从上方结果中选择）"
          style="width: 100%; margin-top: var(--sp-3)"
          :disabled="!candidates.length"
        >
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
import EmptyState from '@/components/EmptyState.vue'
import {
  getMyOrg, listMembers, addMember, removeMember, transferOwner, leaveGroup,
  createOrg, searchUsers, setPermissions
} from '@/api/org'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'

const userStore = useUserStore()
const loading = ref(true)
const data = ref({ org: null, myRole: null })
// 加载失败标志：把「接口失败」与「确实没有组织 / 没有成员」分开 ——
// 共享 EmptyState 依赖它决定给不给「重试」入口
const orgFailed = ref(false)
const membersFailed = ref(false)
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
  orgFailed.value = false
  try {
    const res = await getMyOrg()
    data.value = res.data || {}
    // ⚠️ 原来只有「刷新成员」按钮会拉成员 —— onMounted 只调 loadMyOrg，
    // 于是进页面成员表是空的，得手动点一次才出数据。这里跟着拉一次。
    if (data.value.org) {
      await loadMembers()
    }
  } catch {
    // 拦截器已提示；仍要标成「失败」，否则空态会误显示成「你没加入组织」
    orgFailed.value = true
  } finally {
    loading.value = false
  }
}

const loadMembers = async () => {
  if (!data.value.org) return
  membersLoading.value = true
  membersFailed.value = false
  try {
    const res = await listMembers(data.value.org.id)
    members.value = res.data || []
  } catch {
    // 拦截器已提示
    membersFailed.value = true
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
/* 遮罩容器：本身不占位；加载时才给最小高度，否则遮罩没有可覆盖的区域 */
.my-org-wrap.is-loading {
  min-height: 160px;
}
.my-org-empty {
  padding: var(--sp-5) 0;
}
.empty-hint {
  margin: 0 0 var(--sp-1);
  color: var(--text-sub);
}
.owner-actions {
  margin-top: var(--sp-4);
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}
.member-note {
  margin-top: var(--sp-4);
  color: var(--text-sub);
}
.is-owner {
  color: var(--text-sub);
}
</style>
