<template>
  <div>
    <!-- 三块能力改为标签页切换：原先「查表 / 批量导入 / 单条新增」纵向堆叠，
         用户到达表单本身就要滚动整屏；切换后每屏只面对一件事 -->
    <el-tabs v-model="activeTab" class="records-tabs">
      <!-- ============ 病历查询 ============ -->
      <el-tab-pane label="病历查询" name="query">
        <PanelCard title="病历查询">
          <FreshnessTag :time="loadedAt" reason="数据为本次页面读取时刻；解析/质控更新后请刷新" />
          <RangeFilter v-model="query" />
          <div class="actions">
            <el-button type="primary" :loading="searching" @click="handleSearch">查 询</el-button>
            <el-button :disabled="searching" @click="resetFilters">重置</el-button>
            <el-button
              type="danger"
              plain
              :disabled="searching || !selectedIds.length"
              @click="handleBatchDelete"
            >批量删除{{ selectedIds.length ? `（${selectedIds.length}）` : '' }}</el-button>
            <el-button
              type="danger"
              :disabled="searching || !hasFilter"
              @click="handleRangeDelete"
            >删除范围内病历</el-button>
            <!-- 禁用态下 title 不弹出，所以把前置条件写成常驻说明 -->
            <span class="tip-inline">
              {{ searching ? '正在查询…' : (hasFilter ? '删除上方范围内全部匹配病历' : '需先设置筛选范围') }}
            </span>
            <span class="tip-inline">共 {{ total }} 条</span>
          </div>

          <RecordTable
            ref="tableRef"
            :rows="rows"
            :loading="searching"
            loading-text="正在检索病历…"
            selectable
            :row-class-name="rowClass"
            :max-height="420"
            :action-width="150"
            @selection-change="onSelectionChange"
          >
            <template #action="{ row }">
                <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
                <el-button link type="danger" @click="handleDelete(row.id)">删除</el-button>
            </template>
            <template #empty>
              <EmptyState :failed="listFailed" :loading="searching"
                text="无符合条件的病历" @retry="handleSearch" />
            </template>
          </RecordTable>

          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :page-sizes="PAGE_SIZES_STANDARD"
            :total="total"
            layout="total, sizes, prev, pager, next"
            style="margin-top: var(--sp-3); justify-content: flex-end"
            @current-change="handleSearch"
            @size-change="handleSizeChange"
          />
        </PanelCard>

        <!-- 详情改回弹窗：用户明确指定用弹窗，属  的例外。
             弹窗内仍是左右两栏对照，排版与「病历完整详情」共用同一组件 -->
        <RecordDetailDialog v-model="detailVisible" :record="raw" />
      </el-tab-pane>

      <!-- ============ 病历批量导入 ============ -->
      <el-tab-pane label="病历批量导入" name="import" lazy>
        <PanelCard title="病历批量导入">
          <div class="tip">
            支持 .xlsx / .xls，单文件 ≤50MB、单次 ≤20 个文件；按「登记号」等 21 字段解析入库，
            与数据清洗同一去重口径（21 字段完全一致视为重复，跳过并记录）。
          </div>
          <div class="import-auto">
            <el-switch v-model="autoExtract" aria-label="导入后自动结构化解析" />
            <span>导入后自动结构化解析（后台任务，需抽取服务已开启）</span>
            <span class="tip">开启后导入秒回，解析交由后台队列，可在「结构化解析」页看进度</span>
          </div>

          <el-upload
            v-model:file-list="fileList"
            class="uploader"
            drag
            multiple
            :auto-upload="false"
            :limit="20"
            accept=".xlsx,.xls"
            :on-change="onFileChange"
            :on-exceed="onExceed"
          >
            <div class="up-inner">
              <div class="up-title">将 Excel 拖到此处，或<em>点击选择</em></div>
              <div class="up-sub">可多选，仅 .xlsx / .xls</div>
            </div>
          </el-upload>

          <div class="actions">
            <el-button type="primary" :loading="importing" :disabled="!fileList.length" @click="handleImport">
              {{ importing ? '导入中…' : '开始导入' }}
            </el-button>
            <el-button v-if="importing" :disabled="cancelled" @click="cancelImport">取消</el-button>
            <el-button v-else :disabled="!fileList.length" @click="fileList = []">清空</el-button>
          </div>

          <!-- 逐文件进度：给出「第 n/N 个」与已入库统计，并说明不可关页面 -->
          <div v-if="importing" class="import-progress">
            <div class="ip-hd">
              正在导入第 {{ Math.min(progress.done + 1, progress.total) }}/{{ progress.total }} 个文件：
              <b>{{ progress.current || '准备中…' }}</b>
            </div>
            <el-progress
              :percentage="progress.total ? Math.round((progress.done / progress.total) * 100) : 0"
              :stroke-width="10"
            />
            <div class="ip-sub">
              已入库 {{ progress.success }} 条，失败/跳过 {{ progress.failed }} 条；导入期间请勿关闭或刷新页面
            </div>
          </div>

          <!-- 失败态独立于上一次结果，避免误读为「本次结果」-->
          <div v-if="importFailed" class="import-failed">
            本次导入失败，请根据上方提示排查后重试（上一次结果已清除）。
          </div>

          <div v-if="summary" ref="resultBlock" class="result">
            <div class="result-hd">导入结果</div>
            <div class="stats">
              <div class="stat-item"><div class="num">{{ summary.total }}</div><div class="lbl">有效数据行</div></div>
              <div class="stat-item green"><div class="num">{{ summary.success }}</div><div class="lbl">成功入库</div></div>
              <div class="stat-item red"><div class="num">{{ summary.failed }}</div><div class="lbl">失败 / 跳过</div></div>
            </div>
            <el-table v-if="summary.failures && summary.failures.length" :data="summary.failures" border size="small" max-height="260">
              <el-table-column prop="filename" label="文件" width="240" show-overflow-tooltip />
              <el-table-column prop="reason" label="原因" show-overflow-tooltip />
            </el-table>
          </div>
        </PanelCard>
      </el-tab-pane>

      <!-- ============ 单条新增病历 ============ -->
      <el-tab-pane label="单条新增病历" name="create" lazy>
        <PanelCard title="单条新增病历">
          <!-- 控件尺寸由 el-form 的 size="large" 决定（40px / 14px）。
               ⚠️ 文本域的高度**不由 CSS 决定**：Element Plus 的 autosize 会写行内
               style="height:…px"，行内样式压过任何 class 规则 —— 之前 CSS 里写的
               min-height 完全没起作用。现病史/主诉/中医诊断这类多行字段靠
               autosize 的 minRows:3 给到 3 行。 -->
          <el-form
            ref="createFormRef"
            class="compact-form"
            :model="form"
            :rules="FORM_RULES"
            label-width="72px"
            size="large"
            scroll-to-error
          >
            <!-- 语义分区 + 多列栅格：原先 21 字段平铺是 1000px+ 长表单，
                 改成折叠分组后用户仍要逐组展开、整页依旧要滚动。
                 现改为分区常显 + 3 列栅格：21 字段压到约 11 行，常规屏幕一屏内可填完，
                 校验失败的红字也直接可见（不再藏在折叠区里） -->
            <div v-for="g in FIELD_GROUPS" :key="g.title" class="form-group">
              <div class="group-hd">{{ g.title }}</div>
              <div class="form-grid">
                <el-form-item v-for="f in groupFields(g)" :key="f.key" :label="f.label" :prop="f.key" :class="{ wide: f.wide }">
                  <!-- 性别改枚举下拉：自由文本会写进脏数据-->
                  <el-select
                    v-if="f.key === 'gender'"
                    v-model="form.gender"
                    placeholder="请选择"
                    clearable
                    style="width: 100%"
                  >
                    <el-option label="男" value="男" />
                    <el-option label="女" value="女" />
                  </el-select>
                  <el-date-picker
                    v-else-if="f.key === 'visitTime'"
                    v-model="form.visitTime"
                    type="datetime"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                    placeholder="接诊时间"
                    style="width: 100%"
                  />
                  <el-input
                    v-else
                    v-model="form[f.key]"
                    :type="f.multi ? 'textarea' : 'text'"
                    :rows="f.multi ? 3 : undefined"
                    :autosize="f.multi ? MULTI_AUTOSIZE : false"
                    :maxlength="f.max"
                    :show-word-limit="!!f.max"
                    clearable
                  />
                </el-form-item>
              </div>
            </div>
            <div class="actions create-actions">
              <el-button type="primary" :loading="creating" @click="handleCreate">新增病历</el-button>
              <el-button :disabled="creating" @click="resetForm">重置</el-button>
            </div>
          </el-form>
        </PanelCard>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmBox } from '@/utils/confirm'
import EmptyState from '@/components/EmptyState.vue'
import FreshnessTag from '@/components/FreshnessTag.vue'
import { usePagedList } from '@/composables/usePagedList'
import { useUrlFilters } from '@/composables/useUrlFilters'
import RecordTable from '@/components/RecordTable.vue'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import RecordDetailDialog from '@/components/RecordDetailDialog.vue'
import {
  importRecords, createRecord, searchRecords, getRawRecord, deleteRecords, deleteRecordsByFilter
} from '@/api/records'
import { fmtDateTime, fieldOf } from '@/utils/format'
import { useAiContextStore } from '@/stores/ai'
import { fieldsWithWide, MULTI_AUTOSIZE } from '@/utils/recordFields'
import VisitTimeCell from '@/components/cells/VisitTimeCell.vue'
import AgeGenderCell from '@/components/cells/AgeGenderCell.vue'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'

const aiStore = useAiContextStore()

// 当前标签页；导入与新增懒加载，首屏只渲染查询表
const activeTab = ref('query')

// P2-9 数据新鲜度：页面读取时刻（诚实、不依赖后端字段、不猜加载函数内部）
const loadedAt = ref(new Date().toLocaleString())
/**
 * 21 个原始字段。
 *
 * <p>`wide` 只留给真正需要整行宽度的长叙述（主诉 / 自诉 / 现病史 / 草药）；
 * `multi` 表示用文本域（单行起步、随输入自增），其余短字段走单行输入。
 * 之前 wide 有 11 个，span 2 在 3 列栅格里排不紧，纵向白白多出 4 行，
 * 这也是用户反复说「填写框还是太大」的直接原因。</p>
 */
// max = 该列在 database-init.sql 里的 VARCHAR 宽度（前端限长与 DB 列宽对齐，
// 免得超长内容在表单里输得下、落库时被截断或报错）。TEXT 列不设限。
// 字段定义收敛到 @/utils/recordFields（P3.5）；wide 仅表单布局属性
const FIELDS = fieldsWithWide(['chiefComplaint', 'selfReport', 'presentIllness', 'prescription'])

/**
 * 单条新增的分区：21 个字段平铺会产生 1000px+ 的长表单，
 * 按语义分 5 组，只有含必填项的第一组默认展开。
 */
const FIELD_MAP = FIELDS.reduce((m, f) => ({ ...m, [f.key]: f }), {})
const FIELD_GROUPS = [
  { title: '基本信息', keys: ['registrationNo', 'outpatientNo', 'gender', 'age', 'visitCount', 'department', 'doctorId', 'visitTime'] },
  { title: '主诉与病史', keys: ['chiefComplaint', 'selfReport', 'presentIllness'] },
  { title: '四诊', keys: ['inspection', 'tongue', 'pulse', 'physicalExam'] },
  { title: '诊断', keys: ['westernDiagnosis', 'tcmDiagnosis', 'pattern'] },
  { title: '处方与随访', keys: ['prescription', 'followUp', 'treatmentEffect'] }
]
// 取某分区下的字段定义：按分区声明的 keys 顺序映射回 FIELDS，并过滤掉 FIELD_MAP 里
// 不存在的 key —— 这样分组里写错 key 只会少渲染字段，不会冒出一个空表单项
// FIELD_GROUPS 与 FIELD_MAP 都是模块常量、不含响应式依赖，
// 所以预先建一次索引即可（不必用 computed —— 没有任何东西会变）。
const GROUP_FIELDS = new Map(
  FIELD_GROUPS.map((group) => [group, group.keys.map((k) => FIELD_MAP[k]).filter(Boolean)])
)
const groupFields = (group) => GROUP_FIELDS.get(group) || []

// ===== F·7.4 查询 =====
const query = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
// 范围删除要求至少一个筛选条件（与后端一致，防误删全库）
const hasFilter = computed(() => {
  const r = query.dateRange
  const range = Array.isArray(r) && r.length === 2 && r[0] && r[1]
  return !!(query.department || query.pattern || query.grade || range)
})
const page = ref(1)
const pageSize = ref(10)
// 列表骨架统一走 usePagedList：本页失败**保留已有行**、但仍要标记失败
// （空态据此区分「加载失败」与「确实无数据」），故 clearOnFailure: false
const {
  list: rows, total, loading: searching, failed: listFailed, load: loadList
} = usePagedList({
  fetcher: () => searchRecords({ ...query, page: page.value, pageSize: pageSize.value }),
  extract: (res) => ({ list: res.data?.records, total: res.data?.total }),
  clearOnFailure: false
})

// 28.7：审计页按对象跳转过来时带 recordId。必须在 useUrlFilters **之前**读取 ——
// 它的 write() 只保留自己认知的 state 键，会把 recordId 这类未知键从 query 里抹掉。
const route = useRoute()
const deepLinkRecordId = typeof route.query.recordId === 'string' ? route.query.recordId : ''

// 对标 E5「可分享视图」：筛选与分页同步到 URL（刷新 / 分享保留视图）。
// 放在这里而不是 onMounted 里：本函数在 setup 阶段**同步**还原 URL 里的条件，
// 所以第 696 行那句 onMounted(handleSearch) 会自动带上它们 —— 不需要额外再接一次加载
//（再接一次就是发两次请求）。
useUrlFilters(query, page, pageSize)

// 查询列表：翻页 / 改筛选 / 重试共用同一入口
const handleSearch = () => loadList()

// 每页条数变化回到第 1 页
const handleSizeChange = () => {
  page.value = 1
  handleSearch()
}

// 重置筛选：清空全部查询条件并回到第 1 页，随后再查一次让列表与筛选框同步回到初始态
const resetFilters = () => {
  // 1. 清空全部查询条件，四个筛选字段一起归零
  query.department = ''
  query.dateRange = null
  query.pattern = ''
  query.grade = ''
  // 2. 回到第 1 页，避免停在不存在的页码
  page.value = 1
  // 3. 重新查询，让列表与筛选框同步回初始态
  handleSearch()
}

// ===== 详情=====
const raw = ref(null)
const detailVisible = ref(false)
const activeId = ref('')

// 当前查看行高亮，便于在长表里对上号
const rowClass = ({ row }) => (row.id === activeId.value ? 'row-active' : '')

// 打开详情弹窗：按 id 回查原始病历（列表行只有摘要，完整字段不在列表数据里），
// 同时写入 aiStore 供 AI 助手「这份病历…」类提问引用；失败不弹窗，由拦截器提示
const openDetail = async (id) => {
  try {
    // 1. 按 id 回查原始病历（列表行只有摘要，完整字段不在列表数据里）
    const res = await getRawRecord(id)
    raw.value = res.data
    // 2. 记下当前 id，供列表行高亮对上号
    activeId.value = id
    // 写入共享状态，供 AI 助手"这份病历…"与解读卡使用
    // 3. 写入共享状态，供 AI 助手「这份病历…」与解读卡使用
    aiStore.setActiveRecord(res.data)
    // 4. 数据就绪后才打开弹窗；失败不弹窗
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

// 只收起弹窗、保留 raw：否则关闭动画期间内容会闪空
const closeDetail = () => {
  detailVisible.value = false
}

// 删除单条病历：先二次确认（不可恢复、会留痕），确认后提交删除并刷新列表；
// 若删的正是当前详情记录，则一并收起弹窗、清空 raw，避免弹窗继续指向已删数据
const handleDelete = async (id) => {
  // 1. 先二次确认：删除不可恢复且会留痕
  if (!(await confirmBox('确认删除该病历？删除后不可恢复（会留痕）。', '删除病历', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    }))) {
    return
  }
  // 3. 确认后提交删除
  try {
    await deleteRecords([id])
    ElMessage.success('删除成功')
    // 4. 若删的正是当前详情记录，收起弹窗并清空引用，避免弹窗指向已删数据
    if (activeId.value === id) {
      closeDetail()
      raw.value = null
      activeId.value = ''
    }
    // 5. 清空表格选中并刷新列表
    tableRef.value?.clearSelection()
    selectedIds.value = []
    handleSearch()
  } catch {
    // 拦截器已提示
  }
}

// 批量删除：表格多选 → 一次提交 ids
const tableRef = ref(null)
const selectedIds = ref([])
// 表格多选变化：只收敛成 id 数组供批量删除提交；表格自身的选中态由 clearSelection 复位
const onSelectionChange = (rows) => {
  selectedIds.value = rows.map((r) => r.id)
}
// 批量删除：对选中的 ids 二次确认（确认文案带上条数），通过后一次提交；
// 删除后清空选择并刷新列表，若当前详情记录在被删集合内则同步收起弹窗
const handleBatchDelete = async () => {
  // 1. 先取选中 id 快照，没有选中就直接返回
  const ids = selectedIds.value
  if (!ids.length) return
  // 2. 二次确认，确认文案带上将删条数
  if (!(await confirmBox(
      `确认删除选中的 ${ids.length} 份病历？删除后不可恢复（会留痕）。`,
      '批量删除病历',
      { type: 'warning', confirmButtonText: `删除 ${ids.length} 条`, cancelButtonText: '取消' }))) {
    return
  }
  // 4. 一次提交全部 id
  try {
    const res = await deleteRecords(ids)
    const n = res.data?.deletedCount ?? 0
    // 一条都没删掉时不能报成功：不属于当前组织或已被删除的 id 会被后端剔除
    if (n === 0) {
      ElMessage.warning('没有删除任何病历：选中的病历可能不属于当前组织，或已被删除')
    } else {
      ElMessage.success(`已删除 ${n} 份病历`)
    }
    // 5. 当前详情记录在被删集合内时，收起弹窗并清空引用
    if (ids.includes(activeId.value)) {
      closeDetail()
      raw.value = null
      activeId.value = ''
    }
    // 6. 清空选择并刷新列表
    tableRef.value?.clearSelection()
    selectedIds.value = []
    handleSearch()
  } catch {
    // 拦截器已提示
  }
}

// 按当前筛选范围删除全部匹配病历（前端先取条数确认，后端再按要求删）
const handleRangeDelete = async () => {
  // 1. 没有筛选条件不允许范围删除（防误删全库）
  if (!hasFilter.value) {
    ElMessage.warning('请先设置至少一个筛选条件')
    return
  }
  // 2. 先只取总数（pageSize=1），拿到确切条数供确认文案用
  let n = 0
  try {
    const res = await searchRecords({ ...query, page: 1, pageSize: 1 })
    n = res.data?.total || 0
  // 3. 取数失败就中止，不进入删除流程
  } catch {
    return
  }
  // 4. 范围内没有病历，提示后直接返回
  if (!n) {
    ElMessage.warning('当前筛选范围内没有病历')
    return
  }
  // 5. 二次确认，文案写明将删除多少条
  if (!(await confirmBox(
      `将删除当前筛选范围内全部 ${n} 份病历，删除后不可恢复（会留痕）。确认？`,
      '删除范围内病历',
      { type: 'warning', confirmButtonText: `删除 ${n} 条`, cancelButtonText: '取消' }))) {
    return
  }
  // 7. 提交范围删除
  try {
    const res = await deleteRecordsByFilter({ ...query })
    ElMessage.success(`已删除 ${res.data?.deletedCount ?? 0} 份病历`)
    // 详情可能指向已删病历，直接收起
    closeDetail()
    raw.value = null
    activeId.value = ''
    // 8. 清空选择并刷新列表
    tableRef.value?.clearSelection()
    selectedIds.value = []
    handleSearch()
  } catch {
    // 拦截器已提示
  }
}

// ===== F·7.1 导入 =====
const MAX_FILE_MB = 50
const fileList = ref([])
// 导入后自动结构化解析（默认关；需抽取服务已开启）
const autoExtract = ref(false)
const importing = ref(false)
const summary = ref(null)
const resultBlock = ref(null)  // M19：导入结果块，导入后滚入视野
const importFailed = ref(false)
// 逐文件分批上传的进度：后端导入是同步接口，拿不到中间 taskId，
// 因此按「文件」粒度推进度 —— 既真实可取消，也避免单次超大请求
const progress = reactive({ done: 0, total: 0, current: '', success: 0, failed: 0 })
const cancelled = ref(false)

// 超过单次上传数量上限（20 个）时的兜底提示：limit 拦截不会走到 on-change，
// 不显式提示用户会以为文件没被选中是卡住了
const onExceed = () => ElMessage.warning('单次最多上传 20 个文件')

// 前端预校验：类型与大小不合法直接剔除，不用等服务端返回
const onFileChange = (file, list) => {
  // 1. 取原始 File 对象，没有就跳过（如已有文件的回显）
  const raw = file.raw
  if (!raw) return
  const name = (raw.name || '').toLowerCase()
  // 2. 定义剔除函数：提示原因并把该文件移出上传列表
  const reject = (reason) => {
    ElMessage.error(`「${raw.name}」${reason}`)
    const i = list.indexOf(file)
    if (i >= 0) list.splice(i, 1)
  }
  // 3. 类型校验：非 .xlsx / .xls 直接剔除
  if (!name.endsWith('.xlsx') && !name.endsWith('.xls')) {
    reject('格式不支持，仅支持 .xlsx / .xls')
    return
  }
  // 4. 大小校验：超过单文件上限也剔除
  if (raw.size > MAX_FILE_MB * 1024 * 1024) {
    reject(`超过 ${MAX_FILE_MB}MB 上限`)
  }
}

// 导入病历：逐文件串行上传 —— 后端导入是同步接口、拿不到中间 taskId，
// 因此进度按「文件」粒度推进，也让「取消」有真实落点（当前文件传完即停）；
// 发起即清空上次结果与失败态，完成后汇总本次统计、清空文件列表并刷新查询列表
const handleImport = async () => {
  // 1. 先取出待上传的原始文件，没有文件直接返回
  const files = fileList.value.map((f) => f.raw).filter(Boolean)
  if (!files.length) return
  // 2. 发起即复位上一次结果、失败态与逐文件进度
  // 发起即清空上一次结果并复位失败态，避免把旧结果误读成本次结果
  // 2. 发起即复位上一次结果、失败态与逐文件进度（P3.4 抽出）
  resetImportState(files.length)
  // 3. 标记本次是否提交了后台结构化解析，供完成文案区分
  let autoTaskSubmitted = false
  // 3.1 开关是否开着：开着却没拿到任务号要显式提示，不能只说「导入完成」
  let autoRequested = false
  // 4. 逐文件串行上传：后端是同步接口，进度只能按「文件」粒度推进
  try {
    const failures = []
    for (let i = 0; i < files.length; i++) {
      // 5. 取消后跳出，不再提交剩余文件
      if (cancelled.value) break
      progress.current = files[i].name
      // 6. 单文件打包：文件本体 + 是否自动结构化解析
      // 6. 单文件上传（P3.4 抽出 uploadOneFile）
      const one = await uploadOneFile(files[i], i)
      if (one.autoTaskId) autoTaskSubmitted = true
      if (one.autoRequested) autoRequested = true
      progress.success += one.success
      progress.failed += one.failed
      if (one.failures.length) failures.push(...one.failures)
    }
    // 8. 汇总本次统计，供结果区展示
    // M19：导入结果块在表单/表格下方，1366×768 下落在首屏外 —— 导入完成后把它滚入视野
    nextTick(() => resultBlock.value?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }))
    summary.value = {
      total: progress.success + progress.failed,
      success: progress.success,
      failed: progress.failed,
      failures
    }
    // 9. 完成文案区分「已取消」与「已提交后台解析」两种情况
    const tail = cancelled.value ? '（已取消，未处理剩余文件）' : ''
    const autoTail = autoTaskSubmitted ? '；已提交后台结构化解析' : ''
    ElMessage.success(`导入完成：成功 ${progress.success} 条，失败 ${progress.failed} 条${autoTail}${tail}`)
    // 9.1 开关开着却没提交上：后端在「抽取服务未开启」或上游不可用时只写日志，
    //     接口照旧返回成功。不提示的话用户以为解析在跑，实际一条都不会解析
    if (autoRequested && !autoTaskSubmitted) {
      ElMessage.warning('导入完成，但自动结构化解析未提交（抽取服务未开启或上游不可用）；'
        + '可在「批量解析」页手动提交')
    }
    // 10. 清空文件列表并刷新查询列表
    fileList.value = []
    handleSearch()
  // 11. 失败则置失败态，提示「本次失败」而非沿用旧结果
  } catch {
    importFailed.value = true
  // 12. 无论成败都要关掉 loading 并清掉当前文件名
  } finally {
    importing.value = false
    progress.current = ''
  }
}

// 取消：当前文件完成后不再提交后续文件，已入库的不回滚
/**
 * 复位导入态：清空上次结果与失败态、把逐文件进度归零（P3.4 从 handleImport 抽出）
 */
const resetImportState = (fileCount) => {
  summary.value = null
  importFailed.value = false
  cancelled.value = false
  progress.done = 0
  progress.total = fileCount
  progress.current = ''
  progress.success = 0
  progress.failed = 0
  importing.value = true
}

/**
 * 上传单个文件并返回本文件的统计（P3.4 从 handleImport 抽出）。
 * 请求时序不变：调用方仍是串行 await，取消仍以 cancelImport 位控制。
 *
 * @returns {{success:number, failed:number, failures:Array, autoTaskId:any}}
 */
const uploadOneFile = async (file, index) => {
  progress.current = file.name
  const fd = new FormData()
  fd.append('files', file)
  fd.append('autoExtract', autoExtract.value ? 'true' : 'false')
  const res = await importRecords(fd)
  const s = res.data.summary || {}
  progress.done = index + 1
  return {
    success: s.success || 0,
    failed: s.failed || 0,
    failures: s.failures || [],
    autoTaskId: res.data.autoExtractTaskId || null,
    // 本次是否请求了自动解析：开关开着却没拿到任务号 = 后端没能提交上，
    // 完成文案必须说出来，不能只显示「导入完成」替后端谎报成功
    autoRequested: autoExtract.value === true
  }
}

const cancelImport = () => {
  cancelled.value = true
  ElMessage.info('已取消，正在处理中的文件完成后停止')
}

// ===== F·7.1 单条新增 =====
const emptyForm = () => FIELDS.reduce((o, f) => ({ ...o, [f.key]: '' }), {})
const form = reactive(emptyForm())
const creating = ref(false)
const createFormRef = ref(null)

// 字段级校验：必填口径 + 数值范围 + 枚举 + 长度上限
const FORM_RULES = {
  registrationNo: [
    { required: true, message: '登记号不能为空', trigger: 'blur' },
    { max: 50, message: '登记号不超过 50 字', trigger: 'blur' }
  ],
  outpatientNo: [
    { required: true, message: '门诊号不能为空', trigger: 'blur' },
    { max: 50, message: '门诊号不超过 50 字', trigger: 'blur' }
  ],
  gender: [{ pattern: /^(男|女)$/, message: '性别只能选「男」或「女」', trigger: 'change' }],
  age: [
    {
      validator: (rule, value, cb) => {
        if (value === '' || value == null) return cb()
        const n = Number(value)
        if (!Number.isFinite(n) || n < 0 || n > 150) return cb(new Error('年龄需为 0~150 的数值'))
        cb()
      },
      trigger: 'blur'
    }
  ],
  visitCount: [
    {
      validator: (rule, value, cb) => {
        if (value === '' || value == null) return cb()
        const n = Number(value)
        if (!Number.isInteger(n) || n < 1) return cb(new Error('就诊次数需为不小于 1 的整数'))
        cb()
      },
      trigger: 'blur'
    }
  ]
}

// 重置新增表单：清回空值并清除校验红字，供提交成功后与用户手动重置两条路径复用
const resetForm = () => {
  Object.assign(form, emptyForm())
  createFormRef.value?.clearValidate()
}

// 新增单条病历：先整体校验，通过后提交；空字符串的可选字段（接诊时间 / 就诊次数 / 年龄）
// 不下发，避免后端把空串当成非法值；成功后重置表单、切回查询页并回到第 1 页刷新，
// 让用户立刻确认数据已入库
const handleCreate = async () => {
  // 1. 先整体校验，不通过直接中止（红字由表单渲染）
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  // 2. 置提交中状态，防止重复提交
  creating.value = true
  try {
    // 3. 组装提交体：空的可选字段不下发，避免后端把空串当非法值
    const payload = { ...form }
    if (!payload.visitTime) delete payload.visitTime
    if (payload.visitCount === '' || payload.visitCount == null) delete payload.visitCount
    if (payload.age === '' || payload.age == null) delete payload.age
    // 4. 提交新增
    await createRecord(payload)
    ElMessage.success(`新增成功：登记号 ${payload.registrationNo}`)
    // 5. 成功后清空表单并清除校验红字
    resetForm()
    // 切回查表页并回到第 1 页刷新，让用户立刻确认已入库
    page.value = 1
    await handleSearch()
    // 6. 最后切回查询页，让用户看到刚入库的数据
    activeTab.value = 'query'
  // 7. 失败由响应拦截器统一提示
  } catch {
    // 拦截器已提示
  // 8. 无论成败都复位提交中状态
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await handleSearch()
  // 列表加载失败也照常打开详情：openDetail 走单条回查，不依赖列表
  if (deepLinkRecordId) openDetail(deepLinkRecordId)
})
</script>

<style scoped>
/* 标签页：去掉底部分隔线，避免与面板边框叠成双线 */
.records-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--sp-3);
}
.records-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.tip { line-height: 1.7; margin-bottom: 14px; }
.tip-inline {
  font-size: var(--fs-md);
  color: var(--text-sub);
}
.import-auto {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
  font-size: var(--fs-md);
  color: var(--ink);
}
.import-auto .tip {
  margin: 0;
}
.uploader :deep(.el-upload-dragger) {
  padding: var(--sp-5) 10px;
  border: 1px dashed var(--line);
  background: var(--paper);
}
.up-inner { text-align: center; }
.up-title { font-size: 13.5px; color: var(--ink); }
.up-title em { color: var(--ink-mid); font-style: normal; font-weight: bold; }
.up-sub { font-size: var(--fs-sm); color: var(--text-sub); margin-top: var(--sp-1); }
.actions { margin-top: 14px; display: flex; gap: 10px; align-items: center; }
.result { margin-top: var(--sp-4); border-top: 1px dashed var(--line-soft); padding-top: 14px; }
.import-failed {
  margin-top: 14px;
  padding: var(--sp-2) var(--sp-3);
  background: var(--danger-surface);
  border: 1px solid #e3c3bb;
  border-radius: 4px;
  font-size: var(--fs-md);
  color: var(--danger);
}
/* 逐文件导入进度*/
.import-progress {
  margin-top: 14px;
  padding: 10px 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
}
.ip-hd {
  font-size: var(--fs-md);
  color: var(--text);
  margin-bottom: var(--sp-2);
}
.ip-hd b {
  color: var(--ink);
}
.ip-sub {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--text-sub);
}
.result-hd { font-size: var(--fs-base); font-weight: bold; color: var(--ink); margin-bottom: var(--sp-3); }
.stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-3); margin-bottom: var(--sp-3); }
.stat-item { background: var(--surface); border: 1px solid var(--line); border-radius: 6px; padding: var(--sp-3) var(--sp-4); text-align: center; }
.stat-item .num { font-size: 22px; font-weight: bold; color: var(--ink); }
.stat-item .lbl { font-size: var(--fs-sm); color: var(--text-sub); margin-top: var(--sp-1); }
.stat-item.green .num { color: var(--ink-mid); }
.stat-item.red .num { color: var(--danger); }
/* 多列栅格：3 列时 21 字段压到约 11 行，常规屏幕一屏可填完。
   wide（长文本）占 2 列而非整行 —— 否则每行拉满宽度、纵向白白多出数行；
   进一步只把主诉 / 自诉 / 现病史 / 草药 4 项定为 wide（原先 11 项），
   并把控件高度统一压到 small（24px，见 theme.css 的 .compact-form） */
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 var(--sp-3);
}
.form-grid .wide {
  grid-column: span 2;
}
.form-grid :deep(.el-form-item) {
  margin-bottom: 6px;
}
.form-grid :deep(.el-form-item__label) {
  font-size: var(--fs-sm);
  line-height: 1.5;
  padding-bottom: 0;
}
/* 分区常显：不再折叠，标题只作视觉分隔 */
.form-group {
  margin-bottom: var(--sp-1);
}
.group-hd {
  position: relative;
  padding: 3px 0 var(--sp-1) var(--sp-2);
  margin-bottom: 6px;
  font-size: var(--fs-sm);
  font-weight: bold;
  color: var(--ink);
  border-bottom: 1px solid var(--line);
}
.group-hd::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  width: 3px;
  height: 12px;
  background: var(--ink-mid);
}
/* 提交按钮吸底，长表单滚动时始终可见*/
.create-actions {
  position: sticky;
  bottom: 0;
  background: var(--surface);
  padding: var(--sp-2) 0;
  border-top: 1px solid var(--line);
  z-index: 1;
}

:deep(.row-active) td {
  background: var(--ink-light) !important;
}
/* 栅格降级：
   1500px 以下如果取消 span 2，21 字段从 11 行降到 8 行，比降到 2 列更省高度；
   1200px 以下 3 列每列已不足 320px，标签左置后控件过窄，才收 2 列 */
@media (max-width: 1560px) {
  .form-grid .wide { grid-column: span 1; }
}
@media (max-width: 1200px) {
  .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 900px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
