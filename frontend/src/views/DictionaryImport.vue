<template>
  <div class="dict-import">
    <!--
      术语批量导入（批次 17 从词典页剥离成独立页面）。

      为什么独立：这是**管理员特权操作**（后端 POST /dictionary/import 是
      @RequireRole("管理员")），而词典页的日常任务是查词、本地编辑、提提案。
      挤在同一页既容易误触，又让侧栏入口与门禁对不上 ——
      此前前端用三档 canWrite 显示入口、后端却只收管理员，组长点了必然 403。
    -->
    <PanelCard title="术语批量导入">
      <div class="target-row">
        <span class="tip">导入目标</span>
        <el-radio-group v-model="target" size="small">
          <el-radio-button value="org">当前组织的词典</el-radio-button>
          <el-radio-button value="base">基础层（全局通用词库）</el-radio-button>
        </el-radio-group>
        <span class="tip">
          {{
            target === 'base'
              ? '写入基础层后，所有组织都会使用这批词条；归档版本与组织层独立计数。'
              : '写入当前组织自有词条；该组织归一时仍会回退基础层。'
          }}
        </span>
      </div>

      <div class="target-row">
        <span class="tip">术语类型</span>
        <el-select v-model="type" style="width: 140px" size="small" aria-label="术语类型">
          <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </div>

      <div class="import-row">
        <el-upload
          ref="uploadRef"
          v-model:file-list="dictFileList"
          drag
          :auto-upload="false"
          :limit="1"
          :on-change="onFileChange"
          :on-remove="onFileRemove"
          :on-exceed="onExceed"
          accept=".xlsx,.xls,.csv,.json"
        >
          <div class="upload-tip">
            拖拽文件到此处，或<em>点击选择</em>
            <div class="sub">支持 Excel(.xlsx/.xls) / CSV / JSON</div>
          </div>
        </el-upload>
        <div class="import-actions">
          <el-button type="primary" :loading="importing" :disabled="!importFile" @click="handleImport">
            开始导入
          </el-button>
          <!-- 原文案写「导入前会自动备份，可在下方版本回滚恢复」——备份表已随批次17
               废弃，现在是「导入后生成归档版本」。文案不改会指向已不存在的能力。 -->
          <div class="tip" style="margin-top: var(--sp-2)">
            导入成功后会自动生成一份归档版本，可在词典页「归档版本」查看与回滚。
          </div>

          <!-- 格式说明移出 el-upload 拖拽区：原先嵌在拖拽热区里，
               <summary> 会冒泡触发原生文件选择 -->
          <details class="fmt-detail">
            <summary>查看格式说明</summary>
            <div class="fmt-body">
              · Excel / CSV：第 1 列「标准术语」、第 2 列「别名」（多个用 、或 ; 分隔），可选第 3 列「国标代码」<br />
              · JSON：条目数组，每项含「标准术语」「别名」，可选「来源」「国标代码」
            </div>
          </details>
        </div>
      </div>

      <div v-if="importResult" class="import-result">
        <StatCard label="文件总行数" :value="importResult.total" />
        <StatCard label="成功导入" :value="importResult.imported" tone="green" />
        <StatCard label="失败" :value="importResult.failed" tone="red" />
        <StatCard v-if="importResult.archiveVersion" label="归档版本"
                  :value="'v' + importResult.archiveVersion" tone="green" />
        <div v-if="importResult.failures?.length" class="failures">
          <div class="ded-hd">失败明细</div>
          <div v-for="f in importResult.failures" :key="f.row" class="ded-item">
            <span>第 {{ f.row }} 行：{{ f.reason }}</span>
          </div>
        </div>
      </div>
    </PanelCard>

    <PanelCard title="这条通道的特殊之处">
      <ul class="notes">
        <li>普通成员改组织词典<b>必须走提案</b>（词典页「我的词典」→ 提交更新提案 → 组长审核）。本页是管理员直写，不生成提案。</li>
        <li>直写同样会生成归档版本并计入 5 份限额 —— 否则它会成为唯一一条没有历史版本的改基线方式，出问题回不到上一版。</li>
        <li>写「基础层」会影响所有组织的归一结果，请谨慎。</li>
      </ul>
    </PanelCard>
  </div>
</template>

<script setup>
// 术语批量导入（管理员直写）。与词典页共用 upload 相关状态。
import { ref } from 'vue'
import { ElMessage, genFileId } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import { importDict } from '@/api/dictionary'
import { confirmBox } from '@/utils/confirm'

// 与 TermTypes.ALL（后端唯一权威）一致；标签沿用词典页的叫法
const TYPES = [
  { value: 'disease', label: '疾病' },
  { value: 'pattern', label: '证候' },
  { value: 'symptom', label: '症状' },
  { value: 'herb', label: '中药' },
  { value: 'formula', label: '方剂' }
]
const typeLabel = (v) => (TYPES.find((t) => t.value === v) || {}).label || v

const uploadRef = ref(null)
const dictFileList = ref([])
const importFile = ref(null)
const importing = ref(false)
const importResult = ref(null)
// org = 当前组织自有词条；base = 基础层（全局通用词库）
const target = ref('org')
// 术语类型：原先由词典页的 activeTab 隐式决定，搬成独立页后必须显式选，
// 否则会把文件导进「疾病」而用户以为是「证候」—— 静默写错类型，比报错更难发现。
const type = ref('herb')

const onFileChange = (file) => {
  // 1. 原样保留用户选择的文件（el-upload 的 UploadFile 本身就是 File 的子类）
  importFile.value = file
  importResult.value = null
}

const onFileRemove = () => {
  importFile.value = null
  importResult.value = null
}

const onExceed = (files) => {
  // limit=1：再次选择时替换掉旧文件，避免「换了文件却没反应」
  uploadRef.value?.clearFiles()
  const f = files[0]
  f.uid = genFileId()
  uploadRef.value?.handleStart(f)
  importFile.value = f
}

const handleImport = async () => {
  if (!importFile.value) return
  // 覆盖式入库，先二次确认（取消则中止）
  if (!(await confirmBox(
    `确定用「${importFile.value.name}」覆盖【${typeLabel(type.value)}】${
      target.value === 'base' ? '基础层' : '当前组织'
    }词典吗？`,
    '术语批量导入',
    { type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '取消' }
  ))) return

  importing.value = true
  importResult.value = null
  try {
    const form = new FormData()
    form.append('file', importFile.value)
    form.append('type', type.value)
    const res = await importDict(form, target.value)
    importResult.value = res.data || null
    ElMessage.success(`导入完成：成功 ${res.data?.imported ?? 0} / 共 ${res.data?.total ?? 0}`)
    uploadRef.value?.clearFiles()
    importFile.value = null
  } catch {
    // 拦截器已提示
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
/* 导入目标选择器 */
.target-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
}
/* 说明卡里的要点列表 */
.notes {
  margin: 0;
  padding-left: var(--sp-4);
  font-size: 13px;
  color: var(--text-sub);
  line-height: 1.9;
}
</style>
