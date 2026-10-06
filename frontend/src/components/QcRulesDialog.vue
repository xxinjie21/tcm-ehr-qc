<template>
  <!-- 规则配置（管理员 / 所有者 / 被授权成员）：句子清单 + 就地编辑，保存即生效。
       从 Qc.vue 抽出：弹窗模板 + 表单副本 + 词典候选 + 保存/恢复默认逻辑自成一体，
       与页面其余部分只通过 rules 快照与 saved 事件往来。 -->
  <el-dialog
    :model-value="modelValue"
    title="规则配置（改完点保存即生效）"
    width="min(1000px, 96vw)"
    top="4vh"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <!-- 句子式编辑器：每段就是一句可读的话，直接在句子里改数字 / 选项，不暴露 JSON -->
    <div v-if="form.rules" class="rc">
      <div class="rc-tip">下面就是当前生效的规则，直接在句子里改即可。</div>

      <div class="rc-hd">① 病历应有这些要素（完整性）</div>
      <div class="rc-line">
        病历应有
        <el-select v-model="form.elementNames" multiple filterable collapse-tags size="small" style="min-width: 320px">
          <el-option v-for="e in catalogElements" :key="e.name" :label="e.name" :value="e.name" />
        </el-select>
        ；完全缺失每项扣
        <el-input-number v-model="form.fullWeight" size="small" :min="0" :controls="false" />
        分，仅有原始记录每项扣
        <el-input-number v-model="form.partialWeight" size="small" :min="0" :controls="false" />
        分。
      </div>

      <div class="rc-hd">② 格式检查（勾选即可，无需填写规则）</div>
      <div class="rc-flow">
        <div v-for="t in catalogFormats" :key="t.field" class="rc-fmt" :class="{ on: !!fmtOf(t.field) }">
          <el-checkbox :model-value="!!fmtOf(t.field)" @change="(v) => toggleFormat(t, v)" />
          <span class="rc-fmt-l">{{ t.label }}</span>
          <template v-if="fmtOf(t.field)">
            不合规扣
            <el-input-number
              :model-value="fmtOf(t.field).weight"
              size="small"
              :min="0"
              :controls="false"
              @update:model-value="(v) => setFmtWeight(t.field, v)"
            />
            分
          </template>
        </div>
      </div>
      <div v-for="(f, i) in customFormats" :key="f.uid" class="rc-line">
        【{{ f.label || f.field }}】不合规扣
        <el-input-number v-model="f.weight" size="small" :min="0" :controls="false" />
        分
        <el-button link type="danger" @click="removeCustomFormat(i)">删</el-button>
      </div>

      <div class="rc-hd">③ 一致性规则（触发类型 → 期望类型，期望值取自词典）</div>
      <div v-for="(c, i) in form.consistency" :key="c.uid" class="rc-block">
        <div class="rc-line">
          若
          <el-select v-model="c.triggerType" filterable size="small" style="width: 120px">
            <el-option v-for="t in catalogElements" :key="t.typeKey || t.source" :label="t.name" :value="t.typeKey || t.source" />
          </el-select>
          含
          <!-- 用 el-select-v2（虚拟滚动）：证候词典已 2080 条，普通 el-select 一次挂载
               2000+ 个 el-option 会卡。allow-create 已移除 —— 候选表完整后从列表选即可，
               避免敲入词典外的错词导致一致性规则永不匹配。
               候选改为**远程检索**（remote + remote-method）：原先为喂一个下拉
               一次性把五类词典全量拉进内存（≈3589 个 option），现在展开才取前 50 条。 -->
          <el-select-v2
            v-model="c.triggerValues"
            :options="termOptionsOf(c.triggerType).options.value"
            :remote-method="termOptionsOf(c.triggerType).search"
            :loading="termOptionsOf(c.triggerType).loading.value"
            :visible-change="(v) => v && termOptionsOf(c.triggerType).preload()"
            :remote-show-suffix="false"
            multiple filterable remote collapse-tags size="small" style="min-width: 220px"
            placeholder="从词典中选（可输入搜索）"
          />
        </div>
        <div class="rc-line">
          则
          <el-select v-model="c.expectType" filterable size="small" style="width: 120px">
            <el-option v-for="t in catalogElements" :key="t.typeKey || t.source" :label="t.name" :value="t.typeKey || t.source" />
          </el-select>
          应为
          <el-select-v2
            v-model="c.expectValues"
            :options="termOptionsOf(c.expectType).options.value"
            :remote-method="termOptionsOf(c.expectType).search"
            :loading="termOptionsOf(c.expectType).loading.value"
            :visible-change="(v) => v && termOptionsOf(c.expectType).preload()"
            :remote-show-suffix="false"
            multiple filterable remote collapse-tags size="small" style="min-width: 220px"
            placeholder="从词典中选（可输入搜索）"
          />
          冲突扣
          <el-input-number v-model="c.weight" size="small" :min="0" :controls="false" />
          分
          <el-button link type="danger" @click="form.consistency.splice(i, 1)">删</el-button>
        </div>
      </div>
      <el-button size="small" @click="addConsistency">+ 添加一致性规则</el-button>

      <div class="rc-hd">④ 其它</div>
      <div class="rc-line">
        <el-switch v-model="form.rules.standardization.enabled" />
        术语标准化：未命中词典的每个扣
        <el-input-number v-model="form.rules.standardization.weightEach" size="small" :min="0" :controls="false" />
        分；未归一条数超过
        <el-input-number v-model="form.rules.standardization.cap" size="small" :min="0" :controls="false" />
        后按档累加（每满该条数再加扣一档，<b>不再封顶</b>，避免大量未归一时分数失去区分度）。
        保存即生效；已评过的病历需重跑质控才会更新。
      </div>
      <div class="rc-line">
        重复病历扣
        <el-input-number v-model="form.rules.duplicateWeight" size="small" :min="0" :controls="false" />
        分。
      </div>
      <div class="rc-line">
        合格线
        <el-input-number v-model="form.rules.thresholds.qualified" size="small" :min="0" :max="100" :controls="false" />
        分；无效线
        <el-input-number v-model="form.rules.thresholds.invalid" size="small" :min="0" :max="100" :controls="false" />
        分；核心真缺失
        <el-input-number v-model="form.rules.thresholds.seriousFullMissing" size="small" :min="1" :controls="false" />
        项判无效。
      </div>
    </div>
    <template #footer>
      <el-button @click="resetRules">恢复默认</el-button>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="savingRules" @click="saveRules">保存并生效</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 规则配置弹窗：接收页面已加载的生效规则与可选项目录，编辑副本在弹窗内部，
// 保存 / 恢复默认成功后通过 saved 事件把服务端返回交还页面刷新摘要。
import { reactive, ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmBox } from '@/utils/confirm'
import { useTermOptions } from '@/composables/useTermOptions'
import { updateQcRules, resetQcRules } from '@/api/qc'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  rules: { type: Object, default: null },
  catalogElements: { type: Array, default: () => [] },
  catalogFormats: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const savingRules = ref(false)
// 编辑用的表单副本：由 rules 克隆而来，保存时才组装回写服务端
const form = reactive({
  rules: null,
  elementNames: [],
  fullWeight: 12,
  partialWeight: 6,
  format: [],
  consistency: []
})
// 深拷贝：避免编辑时直接改动 rules（取消后 rules 必须保持原样）
const clone = (o) => JSON.parse(JSON.stringify(o))
// 可编辑行的稳定主键：v-for 不用下标做 key，删中间行后其余行的 DOM / 输入框不会错位。
// 只在本弹窗内唯一即可，用自增序号而非 randomUUID —— 非安全上下文（如局域网 http）下
// crypto.randomUUID 可能不存在
let uidSeq = 0
const nextUid = () => 'u' + ++uidSeq
// 每类词典一个取数器（composable 内部有模块级缓存，同一类型的多个下拉共用一份，
// 不会重复打同一接口）。模板里用 termOptionsOf(type) 取。
const TERM_OPTION_HOLDERS = {}
const termOptionsOf = (type) => {
  const key = type || '__none__'
  if (!TERM_OPTION_HOLDERS[key]) {
    TERM_OPTION_HOLDERS[key] = useTermOptions(() => type || 'disease')
  }
  return TERM_OPTION_HOLDERS[key]
}

// 打开时把当前规则克隆进表单（父组件保证打开前 rules 已加载）
const initForm = () => {
  const r = clone(props.rules || {})
  const els = r.completeness?.elements || []
  form.elementNames = els.map((e) => e.name)
  form.fullWeight = els[0]?.weightFull ?? 12
  form.partialWeight = els[0]?.weightPartial ?? 6
  form.format = (r.format || []).map((f) => ({
    uid: nextUid(), field: f.field, type: f.type || 'regex', expr: f.expr || '', values: f.values || [],
    label: f.label, weight: f.weight ?? 5, reason: f.reason
  }))
  form.consistency = (r.consistency || []).map((c) => ({
    uid: nextUid(),
    name: c.name,
    triggerType: c.triggerType || 'pattern',
    triggerValues: c.triggerValues || [],
    expectType: c.expectType || 'herb',
    expectValues: c.expectValues || [],
    weight: c.weight ?? 10
  }))
  // ⚠️ 这里**不许再写业务数字**：原先写成 `r.thresholds || { qualified: 90, invalid: 60,
  //    seriousFullMissing: 3 }`、`weightEach: 1, cap: 5`、`duplicateWeight ?? 5`。
  //    后端正常都会下发这些值，所以那些字面量平时是死分支；一旦真走到（后端漏字段 /
  //    版本不齐），表单会显示一套「后端没说过」的数字，用户一保存就把它们写进本组织规则 ——
  //    静默改口径。故缺失时退到本页已从后端取到的生效规则，仍无则留空让用户看见。
  form.rules = {
    standardization: r.standardization || props.rules?.standardization || null,
    duplicateWeight: r.duplicateWeight ?? props.rules?.duplicateWeight ?? null,
    thresholds: r.thresholds || props.rules?.thresholds || null
  }
}

watch(() => props.modelValue, (v) => {
  if (v) initForm()
})

// 按 field 查一条格式规则；格式规则按 field 建索引，避免模板每行重复 Array.find
const fmtMap = computed(() => {
  const map = new Map()
  for (const f of form.format) map.set(f.field, f)
  return map
})
const fmtOf = (field) => fmtMap.value.get(field)
// 非模板格式规则（历史遗留或手工添加）：不在 catalogFormats 目录里的项，单独列出供编辑
const customFormats = computed(() => form.format.filter((f) => !props.catalogFormats.some((t) => t.field === f.field)))
const toggleFormat = (t, on) => {
  if (on) {
    if (!fmtOf(t.field)) {
      form.format.push({
        uid: nextUid(),
        field: t.field, type: t.type || 'regex', expr: t.expr || '', values: clone(t.values || []),
        label: t.label, weight: t.weight ?? 5, reason: t.reason
      })
    }
  } else {
    const i = form.format.findIndex((f) => f.field === t.field)
    if (i >= 0) form.format.splice(i, 1)
  }
}
const setFmtWeight = (field, v) => {
  const f = fmtOf(field)
  if (f) f.weight = v
}
const removeCustomFormat = (i) => {
  const target = customFormats.value[i]
  const idx = form.format.indexOf(target)
  if (idx >= 0) form.format.splice(idx, 1)
}
const addConsistency = () => {
  form.consistency.push({
    uid: nextUid(),
    name: '自定义规则', triggerType: 'pattern', triggerValues: [],
    expectType: 'herb', expectValues: [], weight: 10
  })
}

// 组装保存用的规则 payload
const buildRulesPayload = () => {
  const elements = form.elementNames.map((name) => {
    const preset = props.catalogElements.find((e) => e.name === name) || {}
    return {
      name,
      source: preset.source || name,
      fallback: preset.fallback || [],
      weightFull: form.fullWeight,
      weightPartial: form.partialWeight
    }
  })
  const consistency = form.consistency
    .filter((c) => (c.triggerValues || []).length && (c.expectValues || []).length)
    .map((c) => ({
      name: c.name || '自定义规则',
      triggerType: c.triggerType,
      triggerValues: c.triggerValues,
      expectType: c.expectType,
      expectValues: c.expectValues,
      weight: c.weight
    }))
  return {
    completeness: { elements },
    format: form.format.map(({ uid, ...f }) => f),
    consistency,
    standardization: clone(form.rules.standardization),
    duplicateWeight: form.rules.duplicateWeight,
    thresholds: clone(form.rules.thresholds)
  }
}

const saveRules = async () => {
  savingRules.value = true
  try {
    const res = await updateQcRules(buildRulesPayload())
    emit('saved', res)
    ElMessage.success('规则已保存并生效')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已提示
  } finally {
    savingRules.value = false
  }
}

// 恢复默认规则：二次确认后调后端重置
const resetRules = async () => {
  if (!(await confirmBox('确定恢复默认质控规则吗？当前自定义规则将被覆盖。', '恢复默认', { type: 'warning' }))) {
    return
  }
  try {
    const res = await resetQcRules()
    emit('saved', res)
    ElMessage.success('已恢复默认规则')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已提示
  }
}
</script>

<style scoped>
/* ===== 规则配置弹窗 ===== */
/* 格式模板勾选 */
.rc-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
/* 格式模板胶囊：勾选态（.on）加深边框与底色 */
.rc-fmt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--sp-1) var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: var(--fs-xs);
  color: var(--text-sub);
}
.rc-fmt.on {
  border-color: var(--ink-mid);
  background: var(--ink-light);
  color: var(--ink);
}
.rc-fmt-l {
  font-weight: bold;
}
/* 规则配置弹窗主体：超 72vh 内部滚动，页脚按钮始终可见 */
.rc {
  max-height: 72vh;
  overflow-y: auto;
}
/* 弹窗顶部说明 */
.rc-tip {
  font-size: var(--fs-xs);
  color: var(--text-sub);
  margin-bottom: 6px;
}
/* 句子式编辑行：文字与控件同行排布，窄屏换行 */
.rc-line {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin: var(--sp-2) 0;
  font-size: var(--fs-xs);
  color: var(--ink);
  line-height: 2;
}
/* 一致性规则卡片 */
.rc-block {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-2);
  background: var(--paper);
}
/* ①②③④ 分段标题 */
.rc-hd {
  margin: var(--sp-4) 0 var(--sp-2);
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: var(--sp-2);
}
/* 预留的行式布局：当前模板用的是 .rc-line，本类暂无引用 */
.rc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--ink);
}
</style>
