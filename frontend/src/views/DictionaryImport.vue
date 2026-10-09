<template>
  <div class="dict-import">
    <!-- M2（审查报告）：导入页常驻返回入口 —— 面包屑不可点，未成功导入/导入失败时
         页面不能连一条出路都没有，否则用户卡死在这；全部角色都需要。
         L6（审查报告）：原先复用 rh-link（那是给提示条用的 13px 文字链），
         挂在页头主入口上显得突兀；改用与页内其余操作一致的 el-button。 -->
    <div class="import-top">
      <el-button size="small" @click="goDictionary">← 返回术语词典</el-button>
    </div>
    <!--
      批量导入词典。

      **两种去向，按身份给最小选择**：
        · 所有人 → 导入「本机个人词典」（localStorage，只存你这台浏览器，不碰小组基线）
        · 管理员 → 还可选择「直接生效」写进小组基线（特权通道，不走审核）
      成员若想把本地词表推广给小组：到「词典」页 →「我的词典」提交提案，组长审核后合并。
    -->
    <!-- 导入后的重跑引导（批次 21）：
         词表改了不等于归一结果改了 —— structured_data 是解析时写下的快照。
         不说清楚，用户会以为「导入没生效」，然后反复重传同一个文件。 -->
    <div v-if="rerunNeeded" class="rerun-hint">
      <div class="rh-title">词表已生效，但还需要重跑一次解析</div>
      <div class="rh-desc">
        术语归一的结果存在每条病历的结构化字段里，是<b>解析那一刻算好就固定下来的</b>。
        刚导入的新词条不会自动套到已有病历上 —— 必须重跑「结构化解析 + 质控」才会生效。
      </div>
      <div class="rh-ops">
        <el-button type="primary" size="small" :loading="rerunning" @click="rerunAll">
          立即重跑解析与质控
        </el-button>
        <router-link class="rh-link" to="/standardization-report">查看质量报告</router-link>
        <!-- 与「查看质量报告」并排：导入完这一页的任务就结束了，用户要么去看结果，
             要么回词典确认新词条 —— 两条出口都给，别让人靠侧边栏自己找路 -->
        <router-link class="rh-link" to="/dictionary">返回术语词典</router-link>
        <!-- 「稍后再说」：与左侧两条出口的区别是「什么都不做」，所以给它一个真按钮，
             点了把这条提示收起来 —— 重跑入口在「标准化质量报告」页，随时能回去。 -->
        <el-button link class="rh-skip" @click="dismissRerun">
          稍后再说（可随时回来重跑）
        </el-button>
      </div>
    </div>

    <PanelCard title="批量导入词典">
      <!-- 第一步：选类型 -->
      <div class="step">
        <div class="step-no">1</div>
        <div class="step-body">
          <div class="step-t">选术语类型</div>
          <div class="step-d">要与「小组基线」里现有的类型一致，导入后归一才会按新词条命中。</div>
          <el-select v-model="type" style="width: 160px" size="small" aria-label="术语类型">
            <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </div>
      </div>

      <!-- 第二步：选文件 -->
      <div class="step">
        <div class="step-no">2</div>
        <div class="step-body">
          <div class="step-t">上传词典文件</div>
          <div class="step-d">
            支持 Excel(.xlsx/.xls)、CSV、JSON。首列必须是<b>标准术语</b>，第二列<b>别名</b>（多个用「、」分隔，可选）。
          </div>
          <el-upload
            ref="uploadRef"
            v-model:file-list="dictFileList"
            drag
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            :on-exceed="onFileExceed"
            accept=".xlsx,.xls,.csv,.json"
          >
            <div class="upload-tip">
              拖拽文件到此处，或<em>点击选择</em>
              <div class="sub">支持 Excel / CSV / JSON，单个文件不超过 50MB</div>
            </div>
          </el-upload>

          <!-- 上传文件这条路径也要能用 AI 建议。
               此前 AI 按钮只长在下面的粘贴块里，上传的人不会把它和文件联系起来；
               这里给一个属于「上传」自己的入口。两条路径各按各的输入取词，
               但结果落到**同一块**建议面板（见下方 .ai-suggest）。
               形态取「我的 LLM」里「启用 LLM」同款的 el-switch（只借外观，不借那边的逻辑）：
               打开 = 对文件解析出的词条跑一次建议，关闭 = 收起建议面板。 -->
          <div class="upload-foot">
            <span class="uf-label">开启 AI 建议</span>
            <el-switch
              v-model="uploadSuggestOn"
              :loading="suggestLoading"
              :disabled="!importTerms.length || isPastedFile"
              @change="onUploadSuggestToggle"
            />
            <span class="tip">
              对文件解析出的词条逐条判断「挂到已有标准词当别名 / 新建标准词」，确认后再导入
            </span>
          </div>

          <!-- 没有现成文件时不必先去造一个：直接把词粘进来即可。
               从「标准化质量报告」复制的「A、B、C」可直接粘（逐个当标准词入库）；
               要带别名就每行一条，行内用 Tab / 逗号分隔「标准词」与「别名」。
               解析结果包装成 JSON File 后走与上传完全相同的链路（预览 / 体检 / 提交），
               不另起一套逻辑 —— 否则两条路会各自演化出不同的行为。 -->
          <div class="paste-block">
            <!-- 标题独占一行、说明另起一行 —— 与上方「步骤标题 + 步骤说明」同一套层级。
                 原先这两句挤在同一行里，读起来像一句话被硬拆成两半，
                 也让人分不清「或直接粘贴文本」是标题还是正文的一部分。 -->
            <div class="paste-t">或直接粘贴文本</div>
            <div class="paste-d">
              每行一条，标准词与别名之间用 Tab 分隔（从 Excel 复制即 Tab）；也可直接粘「A、B、C」这样的一串标准词。
            </div>
            <el-input
              v-model="pastedText"
              type="textarea"
              :rows="4"
              placeholder="神疲乏力&#10;食少纳呆&#10;恶风&#9;平时也怕风"
            />
            <div class="paste-foot">
              <el-button size="small" :disabled="!parsedPasted.length" @click="usePastedText">
                粘贴后生成待导入文件（{{ parsedPasted.length }} 条）
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :loading="suggestLoading"
                :disabled="!parsedPasted.length"
                @click="askSuggest(parsedPasted)"
              >AI 建议怎么补</el-button>
              <span v-if="!parsedPasted.length" class="tip">
                <!-- L4：AI 与导入按钮为什么灰，给出说明而不是让人瞎点 -->
                需先粘贴文本，AI 建议基于粘贴框里的词条
              </span>
              <span v-if="pastedText.trim() && !parsedPasted.length" class="paste-warn">
                没解析出词条，请检查格式
              </span>
            </div>
            <!-- M3①（审查报告）：AI 不可用的前置提示 —— 先告诉用户会拿到什么，
                 而不是让他点完、等一次往返、再发现是张需要手工逐条填的空表。 -->
            <div v-if="llmAvailable === false" class="ai-off">
              当前没有可用的模型：到右上角「导入 LLM」填入自己的 API Key 后，AI 建议才会真正产出结果。
              现在点「AI 建议怎么补」只会返回词表里字面相近的候选，需人工逐条判断。
            </div>
          </div>

          <!-- AI 建议结果：只出候选，必须逐条确认后才生成词条。
               不直接落库是刻意的 —— 归一链路不消费 LLM 判定，判定地基仍是规则与人工。 -->
          <div v-if="suggestions.length" class="ai-suggest">
            <div class="as-head">
              <span class="as-title">AI 补词建议（{{ suggestions.length }} 条）</span>
              <span class="tip">{{ suggestNote }}</span>
            </div>
            <el-table :data="suggestions" border size="small" max-height="320">
              <el-table-column prop="original" label="原文" min-width="120" />
              <el-table-column label="建议动作" width="120">
                <template #default="{ row }">
                  <el-select v-model="row.action" size="small">
                    <el-option label="挂别名" value="alias" />
                    <el-option label="新建标准词" value="new" />
                    <el-option label="忽略" value="ignore" />
                    <el-option label="待判断" value="unknown" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="标准词" min-width="150">
                <template #default="{ row }">
                  <el-input
                    v-model="row.standardTerm"
                    size="small"
                    :disabled="row.action === 'ignore' || row.action === 'unknown'"
                    placeholder="填标准词"
                  />
                </template>
              </el-table-column>
              <el-table-column label="依据" min-width="220">
                <template #default="{ row }">
                  <span class="as-reason">{{ row.reason || '—' }}</span>
                  <el-tag
                    v-if="row.source === 'model'"
                    size="small"
                    type="warning"
                    effect="plain"
                    class="as-tag"
                  >AI 生成</el-tag>
                  <el-tag v-else-if="row.source === 'dict'" size="small" effect="plain" class="as-tag">
                    词表命中
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
            <div class="as-foot">
              <el-button size="small" type="primary" :disabled="!confirmable.length" @click="applySuggestions">
                采用结果（{{ confirmable.length }} 条）
              </el-button>
              <el-button size="small" @click="suggestions = []">关闭</el-button>
              <span class="tip">采用后会填回上方粘贴框，核对无误再导入</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 第三步：确认 -->
      <div class="step">
        <div class="step-no">3</div>
        <div class="step-body">
          <div class="step-t">选择去向并确认</div>
          <div class="step-d">{{ modeTip }}</div>
          <!-- 批次7：dry-run 预览。后端 /dictionary/parse 明确「只解析、不落库」，
               所以可以放心在点确认之前先把影响面摆出来（条数 + 前几条样例）。 -->
          <div style="margin-top: 6px">
            <span v-if="previewLoading" class="tip">正在解析文件（只解析，不会写入任何数据）…</span>
            <template v-else-if="preview && preview.terms.length">
              <div class="prev-head">
                <b>将写入 {{ preview.count }} 条术语</b>
                <!-- M10（审查报告）：生成待导入文件后必须能撤回 —— 此前只能刷新页面才能回到初始态 -->
                <el-button size="small" text @click="clearImportFile">撤回</el-button>
              </div>
              <!-- H5（审查报告）：粘贴内容改过之后，这份明细与待导入文件都已过期。
                   提示放在表格**上方**，避免用户把下面这份旧明细当成「当前要导入的内容」。 -->
              <div v-if="pastedStale" class="prev-stale">
                粘贴内容已变化，下面这份明细与待导入文件已失效 —— 请重新点上方「粘贴后生成待导入文件」
              </div>
              <!-- M8（审查报告）：把解析出的词条**逐条**摆出来。原先只给「条数 + 前 6 条示例」，
                   而这一步之后是覆盖型导入（直写基线会整体替换共享词典），用户没有可核对的东西。
                   表体 max-height 内滚，不撑破「1600×900 一屏」的版式约束。 -->
              <el-table :data="preview.terms" size="small" border max-height="260" class="prev-table">
                <el-table-column prop="standardTerm" label="标准术语" min-width="140" />
                <el-table-column label="别名" min-width="160">
                  <template #default="{ row }">
                    <span v-if="row.aliases?.length">{{ row.aliases.join('、') }}</span>
                    <span v-else class="tip">—</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>
            <span v-else-if="importFile" class="tip">未取得预览（解析失败或格式不符），仍可继续，但请自行确认{{ isPastedFile ? '粘贴内容' : '文件内容' }}</span>
          </div>

          <!-- M11（审查报告）：体检必须在**导入前**可见。
               它是解析接口在预览阶段就返回的，此前被丢弃、只在「导入本地」成功后才显示，
               而「直接生效到小组基线」这条覆盖型路径更是永远看不到 —— 偏偏它最需要先看体检。
               与结果区那份体检是同一段结构：两处互斥（这里 !result、那里 result），
               刻意不抽组件 —— 两处的上下文文案不同，且本项目单文件页面惯例外置组件成本更高。 -->
          <div v-if="!result && lintIssues.length" class="lint">
            <div class="lint-hd">
              词表体检：{{ lintErrors }} 项需要修改，{{ lintWarnings }} 项建议确认
            </div>
            <div v-for="(it, i) in lintIssues" :key="i" class="lint-item" :class="it.level">
              <div class="lint-top">
                <el-tag size="small" :type="it.level === 'error' ? 'danger' : 'warning'" effect="plain">
                  {{ it.level === 'error' ? '需修改' : '建议确认' }}
                </el-tag>
                <span class="lint-msg">{{ it.message }}</span>
                <span v-if="it.count > 1" class="lint-count">（{{ it.count }} 条）</span>
              </div>
              <div v-if="it.terms" class="lint-terms">{{ it.terms }}</div>
              <div v-if="it.advice" class="lint-advice">{{ it.advice }}</div>
            </div>
          </div>

          <!-- 确认区：去向选择 + 主操作 + 状态提示收成一组，与上方的预览明细 / 词表体检
               用一条分隔线分开。这样这一步在版面上是**两个阶段** ——
               先「核对上面那份明细」，再「在下面选去向、点导入」；
               原先它们竖着堆成一长串，主操作跟在一堆说明后面，找不到落脚点。 -->
          <div class="confirm-area">
            <!-- 管理员可选「直接生效」；其余身份只有本地一条路，不给选择避免困惑 -->
            <div v-if="isAdmin" class="target-row">
              <el-radio-group v-model="mode" size="small">
                <el-radio-button value="local">导入本机个人词典</el-radio-button>
                <el-radio-button value="direct">直接生效到小组基线</el-radio-button>
              </el-radio-group>
            </div>

            <div v-if="isAdmin && mode === 'direct'" class="target-row">
              <el-radio-group v-model="target" size="small">
                <el-radio-button value="org">当前组织</el-radio-button>
                <el-radio-button value="base">基础层（影响所有组织）</el-radio-button>
              </el-radio-group>
            </div>

            <el-button
              type="primary"
              class="do-btn"
              :loading="submitting"
              :disabled="!canImport"
              @click="handleSubmit"
            >{{ submitLabel }}</el-button>
            <!-- H5：粘贴内容变了就必须重新生成，不能拿着旧文件往下导 -->
            <span v-if="pastedStale" class="prev-stale">粘贴内容已变化，请重新点上方「粘贴后生成待导入文件」</span>
            <span v-else-if="!importFile" class="tip">{{ pastedText.trim() ? '粘贴后请先点上方「粘贴后生成待导入文件」' : '请先上传文件，或用粘贴内容生成待导入文件' }}</span>
            <!-- H2：导入失败要在页内可见、可重试，不能只靠一闪而过的 toast -->
            <div v-if="submitError" class="import-err" role="alert">
              <b>导入失败：</b>{{ submitError }}
            </div>
          </div>
        </div>
      </div>

<div v-if="result" class="import-result">
          <StatCard label="文件解析" :value="result.parsed" />
          <StatCard v-if="result.failed > 0" label="解析失败" :value="result.failed" tone="red" />
          <StatCard v-if="result.added != null" label="本地新增" :value="result.added" />
          <div class="what-next">
            <b>接下来会怎样：</b>{{ nextStepText }}
          </div>

          <!-- 词表体检（批次 21）：这些问题导入时不会报错，
               但会让词条悄悄变少或归一失效，所以在这里指出来 -->
          <div v-if="lintIssues.length" class="lint">
            <div class="lint-hd">
              词表体检：{{ lintErrors }} 项需要修改，{{ lintWarnings }} 项建议确认
            </div>
            <div v-for="(it, i) in lintIssues" :key="i" class="lint-item" :class="it.level">
              <div class="lint-top">
                <el-tag size="small" :type="it.level === 'error' ? 'danger' : 'warning'" effect="plain">
                  {{ it.level === 'error' ? '需修改' : '建议确认' }}
                </el-tag>
                <span class="lint-msg">{{ it.message }}</span>
                <span v-if="it.count > 1" class="lint-count">（{{ it.count }} 条）</span>
              </div>
              <div v-if="it.terms" class="lint-terms">{{ it.terms }}</div>
              <div v-if="it.advice" class="lint-advice">{{ it.advice }}</div>
            </div>
          </div>

          <div v-if="result.failures?.length" class="failures">
            <div class="ded-hd">解析失败的行（这些不会被导入）</div>
            <div v-for="(f, i) in result.failures" :key="i" class="ded-item">
              第 {{ f.row }} 行：{{ f.reason }}
            </div>
          </div>

          <!-- 导入完成后给出明确出口。
               原先这一页导入完就「断」在这里：没有任何按钮回到术语词典，
               用户只能自己去侧边栏找路（面包屑也不是链接）。 -->
          <div class="import-done-ops">
            <el-button type="primary" @click="goDictionary">返回术语词典</el-button>
            <span class="tip">在「我的词典」里可以核对刚导入的词条</span>
          </div>
        </div>
    </PanelCard>

    <PanelCard title="格式示例">
      <!-- 直接给可照抄的样子，比抽象描述省事 -->
      <div class="sample">
        <div class="sample-t">Excel / CSV（两列：标准术语、别名）</div>
        <table class="sample-tb">
          <thead><tr><th>标准术语</th><th>别名</th></tr></thead>
          <tbody>
            <tr><td>肝郁气滞</td><td>肝气郁结、肝郁</td></tr>
            <tr><td>柴胡</td><td>北柴胡、醋柴胡</td></tr>
          </tbody>
        </table>
        <div class="sample-t" style="margin-top: var(--sp-3)">JSON（等价写法）</div>
        <!-- 一行一个字段：这张卡只有 320~420px 宽，整条对象写成一行会被右边缘截断
             （实测 "aliases": ["北柴胡", "醋… 后面看不全）—— 示例卡是给人照抄的，
             看不全就等于没给。 -->
        <pre class="code">[
  {
    "standardTerm": "肝郁气滞",
    "aliases": ["肝气郁结", "肝郁"]
  },
  {
    "standardTerm": "柴胡",
    "aliases": ["北柴胡", "醋柴胡"]
  }
]</pre>
      </div>
    </PanelCard>

    <!-- 并入本机个人词典前的合并确认弹窗：与「词典」页「拉取组内词典」共用同一个组件 -->
    <DictMergeDialog
      v-model:visible="mergeVisible"
      :result="mergeResult"
      :type-key="type"
      @confirm="applyMerge"
    />
  </div>
</template>

<script setup>
// 批量导入词典。所有人可导入本机个人词典；管理员可直写基线。
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, genFileId } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import DictMergeDialog from '@/components/DictMergeDialog.vue'
import { importDict, parseDictFile } from '@/api/dictionary'
import { runTermSuggest } from '@/api/ai'
import { getLlmConfig } from '@/api/llm'
import { submitNlpBatch } from '@/api/nlp'
import { recomputeQc } from '@/api/qc'
import { confirmBox } from '@/utils/confirm'
import { apiErrorMessage } from '@/utils/errorMessage'
import { useUserStore } from '@/stores/user'
import { splitAliases } from '@/utils/terms'
import { mergeTermLists } from '@/utils/dictMerge'

const TYPES = [
  { value: 'disease', label: '疾病' },
  { value: 'pattern', label: '证候' },
  { value: 'symptom', label: '症状' },
  { value: 'herb', label: '中药' },
  { value: 'formula', label: '方剂' },
  { value: 'tongue', label: '舌象' },
  { value: 'pulse', label: '脉象' },
  { value: 'treatment', label: '治法' }
]
const typeLabel = (v) => (TYPES.find((t) => t.value === v) || {}).label || v

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === '管理员')

// 导入完成后回术语词典。用 router.push 而不是 <router-link>：
// 结果区里它要呈现为**主按钮**（这一步的出口），而 rh-link 那套链接样式是给提示条用的。
const router = useRouter()
// 来源页（「术语词典」）会把当前选中的类型放进 query.type，下面用它决定默认值
const route = useRoute()
const goDictionary = () => router.push('/dictionary')

const uploadRef = ref(null)
const dictFileList = ref([])
// 批次7：dry-run 预览结果 { count, terms } 与加载态
const preview = ref(null)
const previewLoading = ref(false)
const importFile = ref(null)
/** M9（审查报告）：本次待导入文件解析出的完整词条 —— AI 建议的入参，上传与粘贴共用 */
const importTerms = ref([])
const submitting = ref(false)
/** H2（审查报告）：导入失败要在页内可见、可走，不能只靠一闪而过的 toast */
const submitError = ref('')
const result = ref(null)
/** 词表体检结果（批次 21），来自 /parse 响应的 lint 段 */
const lint = ref(null)
/** 导入完成后置位：提示需要重跑解析，mode=direct 时才提示（本地词典不影响基线） */
const rerunHint = ref(null)
const rerunning = ref(false)
/** 「稍后再说」把本次提示收起来了；下一次导入会重新放出来 */
const rerunDismissed = ref(false)
// 类型默认跟随来源：从「术语词典」页某类型点「批量导入」进来时，路由 query 带该类型，
// 这里按 TYPES 白名单校验后采用；缺失或非法（侧边栏入口 / 直接敲 URL）回落 'herb'。
// 页内下拉仍可自由改 —— 只是默认值跟着来源走。
const qType = String(route.query.type || '')
const type = ref(TYPES.some((t) => t.value === qType) ? qType : 'herb')
// M4（审查报告）：默认落到更安全的「导入本机个人词典」——
// 「直接生效到小组基线」是特权通道，不应作为默认可直接被「顺手点两下」触发。
// 管理员若真要直写，选一次即可（确认框文案仍在兜底）。
const mode = ref('local')
const target = ref('org')

const modeTip = computed(() => {
  if (isAdmin.value && mode.value === 'direct') {
    return '文件并入小组基线并生成归档版本：同标准词合并别名、不删除已有词条，不经过审核，立即对所有成员生效。仅在确信无误时使用。'
  }
  return '文件解析后并入你的本机个人词典（只存在你这台浏览器，不影响小组基线，也不影响其他成员）。'
})

const submitLabel = computed(() =>
  isAdmin.value && mode.value === 'direct' ? '直接导入' : '导入本地词典')

const nextStepText = computed(() => {
  if (isAdmin.value && mode.value === 'direct') {
    return '已写入小组基线，可在「词典」页的「归档版本」查看这次的快照。'
  }
  return '到「词典」页 →「我的词典」可查看这些词；如想推广给小组，在那里点「提交提案」，组长审核通过后才会进入小组基线。'
})

// 词表体检（批次 21）：优先取后端已排好序的 topIssues，没有就退回 errors+warnings。
// 用 topIssues 是因为它按「影响条数」降序 —— 界面上第一条永远是覆盖面最大、
// 改一处能消掉一大片的那条，而不是按检查顺序流水账。
const lintIssues = computed(() => {
  if (!lint.value) return []
  if (Array.isArray(lint.value.topIssues) && lint.value.topIssues.length) {
    return lint.value.topIssues
  }
  return [...(lint.value.errors || []), ...(lint.value.warnings || [])]
})
const lintErrors = computed(() => lint.value?.errors?.length || 0)
const lintWarnings = computed(() => lint.value?.warnings?.length || 0)

// 个人词典在 localStorage，键与词典页「我的词典」保持一致
const localKey = () => `dict.local.${userStore.orgId || 'base'}.${type.value}`

/** 读本机个人词典（坏数据当空处理，不因为一条脏记录整份读不出来） */
function readLocalTerms() {
  try {
    const obj = JSON.parse(localStorage.getItem(localKey()) || '{}') || {}
    return Array.isArray(obj.terms) ? obj.terms : []
  } catch (e) {
    return []
  }
}

/** 写本机个人词典；localStorage 满 / 隐私模式会抛，由调用方兜住并如实告知 */
function writeLocalTerms(terms) {
  localStorage.setItem(localKey(), JSON.stringify({
    at: new Date().toLocaleString(),
    terms
  }))
}

// 合并确认弹窗（与「词典」页「拉取组内词典」共用同一个组件与同一套合并口径）
const mergeVisible = ref(false)
const mergeResult = ref(null)

/**
 * 把解析出的词条并入本机个人词典。
 *
 * <p><b>并入而不是覆盖</b>：本地词典是持续累积的工作副本，导入只往里加。
 * 合并口径走 {@code utils/dictMerge.js}（同标准词别名取并集、剔除自指），
 * 与后端 {@code DictionaryServiceImpl.mergeEntries} 一致 ——
 * 本地这套若自己另立口径，会出现「本机 100 条、提交上去 98 条」的对不上。</p>
 *
 * <p><b>有歧义就交人工</b>：同名但别名不一致、或同一个词在两边归属不同，
 * 都返回 null 并打开弹窗，等用户选完再落盘。纯新增没有歧义，直接写。</p>
 *
 * @returns {number|null} 新增条数；返回 null 表示已打开弹窗、尚未落盘
 */
function mergeIntoLocal(terms) {
  const incoming = terms.map((t) => ({
    standardTerm: t.standardTerm,
    aliases: t.aliases || [],
    source: t.source || '批量导入'
  }))
  const r = mergeTermLists(readLocalTerms(), incoming)
  if (!r.sameTermDiff.length && !r.collisions.length) {
    writeLocalTerms(r.merged)
    return r.added
  }
  mergeResult.value = r
  mergeVisible.value = true
  return null
}

/** 弹窗里选完 → 落盘。失败（存储满）必须说出来，不能假装成功 */
const applyMerge = (finalTerms) => {
  const added = mergeResult.value?.added ?? null
  try {
    writeLocalTerms(finalTerms)
    // 把「本地新增」卡片补上真实条数：弹窗路径下这件统计本来要到这一步才算得出来
    if (result.value && added != null) result.value.added = added
    ElMessage.success(`已并入本机个人词典（合计 ${finalTerms.length} 条）`)
  } catch (e) {
    ElMessage.warning('本机存储不可用或已满，导入未能保存')
  }
}

/**
 * 批次 7：dry-run 预览 —— 调「只解析、不落库」的解析接口（后端 javadoc 明确如此），
 * 算出条目数与前几条样例。覆盖型导入（组织 / 基础层）会把共享词典整体换掉，
 * 用户点确认前必须看到影响面，而不是只看到文件名。
 */
const loadPreview = async (file) => {
  previewLoading.value = true
  try {
    const form = new FormData()
    // H1 成因二：ES upload 组件回调给的是包裹对象，真正的原生文件在 .raw 上 ——
    // 直接塞 FormData 会被转成字符串 "[object Object]"（后端先报「缺少文件参数：file」）
    form.append('file', file?.raw ?? file)
    // H1 成因一：type 只放 query（parseDictFile 的 params 已带），表单里**不得再放一份** ——
    // 两处同时放会被后端判成「术语类型非法」（4001）
    const res = await parseDictFile(form, type.value)
    // 兼容两种返回：直接是数组，或包在 terms 里
    const terms = Array.isArray(res.data) ? res.data : (res.data?.terms || [])
    // M8（审查报告）：把**完整**词条留在 preview 里，第三步据此渲染明细表。
    // 原先只留 { count, sample: 前 6 条 }，用户拿不到可核对的东西。
    preview.value = {
      count: terms.length,
      terms
    }
    // M9（审查报告）：AI 建议改吃「本次待导入文件解析出的词条」——
    // 原先只吃粘贴框，上传文件的路径完全够不着 AI 建议。
    importTerms.value = terms
    // M11（审查报告）：体检结果在预览阶段就拿到了，必须在导入**之前**摆出来 ——
    // 它正是决定「要不要现在导入」的依据。此前被丢弃，只在「导入本地」成功后才显示，
    // 而「直接生效到小组基线」这条覆盖型路径更是永远看不到。
    lint.value = res.data?.lint ?? null
  } catch {
    // 预览失败不阻断导入，但绝不假装「0 条」—— 置 null，界面按「未预览」呈现
    preview.value = null
    importTerms.value = []
    lint.value = null
  } finally {
    previewLoading.value = false
  }
}

const onFileChange = (file) => {
    importFile.value = file
    result.value = null
    submitError.value = ''
    // 换文件就清掉上一份的体检结果，否则会误以为是新文件的问题
    lint.value = null
    // 批次7：换文件即重算预览，避免「看着 A 的预览导入了 B」
    preview.value = null
    importTerms.value = []
    // 换文件后上一份建议已不适用：开关归位、面板收起
    uploadSuggestOn.value = false
    suggestions.value = []
    loadPreview(file)
  }
  // 移除文件与「撤回」同一语义（M10）：一并清掉预览与体检，避免残留上一个文件的明细
  const onFileRemove = () => clearImportFile()
const onFileExceed = (files) => {
  uploadRef.value?.clearFiles()
  const f = files[0]
  if (!f) return
  f.uid = genFileId()
  uploadRef.value?.handleStart(f)
  importFile.value = f
  // L5（审查报告）：静默替换会让用户以为两个文件都在队列里 —— 给一句提示
  ElMessage.warning('每次只能上传 1 个文件，已替换为最新选择')
}

// ---- 粘贴文本导入 ----
// 与文件导入共用同一条链路：把粘贴内容包装成 JSON File，后续的 dry-run 预览、
// 词表体检、直写基线 / 并入本地、重跑引导全部复用，不另写一套。
const pastedText = ref('')

/**
 * 解析粘贴的文本为词条数组。
 *
 * 三种约定，按内容自动区分：
 *   · 多行            → 每行一条，行内分列出「标准词」与「别名」；
 *   · 单行含 Tab/竖线 → 按「标准词 + 别名」单条处理；
 *   · 单行不含分列符  → 按顿号 / 逗号拆成多个标准词（别名留空）。
 *     最后这条是给「标准化质量报告」的「复制待补词」准备的 ——
 *     它复制出来的就是「神疲乏力、食少纳呆、…」这种顿号分隔的一串词。
 *
 * ⚠️ 单行必须靠 Tab/竖线来判「这是要分列」，不能靠逗号：单行里的逗号
 * 既可能是「多个词的分隔」也可能是「标准词与别名的分隔」，无法两全；
 * 而 Tab 只可能来自表格粘贴，语义唯一。
 */
const parsePastedTerms = (text) => {
  // 去掉开头空行与首尾空白，但**保留行首 Tab** —— 它是「首列为空」的信号，见下方注释
  const raw = String(text || '').replace(/^\n+/, '').replace(/\s+$/, '')
  if (!raw) return []
  // 只去行尾空白、保留行首 —— 行首的 Tab 是「首列为空」的信号（从表格复制到一列空值就会这样），
  // 若连行首一起 trim 掉，那行会被误当成一个标准词写进词典，正是要防的那类脏数据。
  const lines = raw.split(/\r?\n/).map((l) => l.replace(/\s+$/, '')).filter(Boolean)
  // 行内分列：Tab 是表格粘贴的主通道（从 Excel 复制就是 Tab），竖线与逗号一并认，
  // 让从 CSV 复制来的两列也能直接落进来。
  const splitCells = (line) => line.split(/[\t|,，;；]/).map((c) => c.trim())
  const out = []
  if (lines.length > 1) {
    for (const line of lines) {
      const cells = splitCells(line)
      const std = cells[0]
      if (!std) continue
      out.push({ standardTerm: std, aliases: splitAliases(cells.slice(1).join('、'), std) })
    }
    return out
  }
  if (/[\t|]/.test(lines[0])) {
    const cells = splitCells(lines[0])
    const std = cells[0]
    if (!std) return []
    return [{ standardTerm: std, aliases: splitAliases(cells.slice(1).join('、'), std) }]
  }
  for (const w of lines[0].split(/[、,，;；\s]+/)) {
    const std = w.trim()
    if (std) out.push({ standardTerm: std, aliases: [] })
  }
  return out
}

const parsedPasted = computed(() => parsePastedTerms(pastedText.value))

// 当前待导入文件是否来自粘贴（L2：措辞区分「内容 / 文件」、确认框源名判断）
const isPastedFile = computed(() => String(importFile.value?.name || '').startsWith('pasted-'))

// ---- H5：粘贴框与待导入文件必须同源 ----
// 「生成待导入文件」做出的是一份**独立快照**；此后改粘贴框并不会让它更新，
// 于是出现「屏幕上写 A、点下去导入 B」。这里给生成那一刻的内容记一个指纹，
// 粘贴框一旦对不上就让导入按钮失效。
// 用 computed 而不是 watch：内容改回原样时自动恢复可用，不白丢用户已生成的那份文件。
const pastedFingerprint = ref('')
/** 指纹 = 标准词 + 别名，顺序敏感（改一个别名也算变了） */
const fingerprintOf = (terms) => JSON.stringify(terms.map((t) => [t.standardTerm, t.aliases || []]))
/** 粘贴生成的待导入文件是否已因粘贴框被改动而失效 */
const pastedStale = computed(
  () => !!pastedFingerprint.value && fingerprintOf(parsedPasted.value) !== pastedFingerprint.value
)
/** 能否提交导入（H5：粘贴内容变了就不能再导旧文件） */
const canImport = computed(() => !!importFile.value && !pastedStale.value)

/** 撤回待导入文件（M10）：预览 / 体检 / 结果一并回到「未选择」态 */
const clearImportFile = () => {
  importFile.value = null
  preview.value = null
  importTerms.value = []
  lint.value = null
  result.value = null
  // 待导入内容撤掉了，挂在它上面的 AI 建议也一并收起（含上传区开关归位）
  uploadSuggestOn.value = false
  suggestions.value = []
  // 撤回后不再与粘贴框联动，否则会立刻又被判成「已失效」
  pastedFingerprint.value = ''
  uploadRef.value?.clearFiles()
}

/** 用粘贴内容走与文件导入完全相同的链路（M5：按钮名与文案说清「这是导入前置闸门」） */
const usePastedText = () => {
  const terms = parsedPasted.value
  if (!terms.length) return
  const blob = new Blob([JSON.stringify(terms)], { type: 'application/json' })
  // 文件名带条数，便于和真实上传文件区分；确认框里会改写成「粘贴的 N 条」
  const file = new File([blob], `pasted-${terms.length}-terms.json`, {
    type: 'application/json'
  })
  onFileChange(file)
  // H5：记下这一刻的内容指纹，之后粘贴框对不上即判失效
  pastedFingerprint.value = fingerprintOf(terms)
  ElMessage.success(`已生成待导入文件（${terms.length} 条），确认无误后点下方导入`)
}

// ---- AI 补词建议 ----
// 只出候选、不落库：LLM 判定不进归一链路，用户逐条确认后才生成词条。
//
// M3①（审查报告）：AI 不可用要**提前**说 —— 不能等用户点了按钮、等一次模型往返、
// 拿到一张全是「待判断」的空表才知道。配置接口只回「参数是否齐备」、不发起任何模型请求，代价很低。
// 三态：null = 还没问到（不问就不提示）/ true / false。
const llmAvailable = ref(null)
onMounted(async () => {
  try {
    const res = await getLlmConfig()
    llmAvailable.value = !!res.data?.available
  } catch {
    // 拿不到配置就不下结论（可能是没权限），不打扰用户
    llmAvailable.value = null
  }
})

/** 上传区的「开启 AI 建议」开关：打开即对上传文件解析出的词条跑一次建议，关闭收起结果。
 *  只借 el-switch 的外观，与「我的 LLM」里的「启用 LLM」没有状态关联。 */
const uploadSuggestOn = ref(false)
const suggestions = ref([])
const suggestLoading = ref(false)
const suggestNote = ref('')

/** 可采用的条目：动作是挂别名或新建，且标准词非空 */
const confirmable = computed(() =>
  suggestions.value.filter(
    (s) => (s.action === 'alias' || s.action === 'new') && String(s.standardTerm || '').trim()
  )
)

/**
 * AI 建议的入参由**调用方显式给出**：
 *   · 上传区的「开启 AI 建议」→ 该文件解析出的词条（importTerms）
 *   · 粘贴行的「AI 建议怎么补」→ 粘贴框当前的词条（parsedPasted）
 * 两条路径各有各的入口，不再靠一个「自动挑来源」的 computed 去猜 ——
 * 猜错会让用户对着上传的文件拿到一份基于粘贴框的建议。
 */
/** 向 AI 要建议；失败或不可用都不影响手动路径 */
const askSuggest = async (source) => {
  const words = (source || []).map((t) => t.standardTerm).filter(Boolean)
  if (!words.length) return
  suggestLoading.value = true
  suggestions.value = []
  try {
    const reply = await runTermSuggest({ terms: words, termType: type.value })
    const list = reply?.termSuggestions || []
    if (!list.length) {
      ElMessage.warning('AI 没有返回建议，请稍后重试')
      return
    }
    suggestions.value = list.map((s) => ({ ...s, aliases: s.aliases || [] }))
    // 把「当前类型可能不对」这件事说出来：报告页复制的待补词恒来自症状类
    const typeHint = type.value === 'symptom'
      ? ''
      : `当前类型是「${typeLabel(type.value)}」，而质量报告复制的待补词属于症状类，请确认。`
    suggestNote.value = reply.llmAvailable
      ? `AI 建议仅供参考，标「AI 生成」的条目未经权威词表校验。${typeHint}`
      : `AI 不可用，以下是词表中字面相近的候选，需人工判断。${typeHint}`
  } catch (e) {
    ElMessage.warning(e?.message || 'AI 建议生成失败')
  } finally {
    suggestLoading.value = false
  }
}

/** 上传区开关：打开就对上传文件解析出的词条跑一次建议；关闭把建议面板收起 */
const onUploadSuggestToggle = (on) => {
  if (!on) {
    suggestions.value = []
    return
  }
  askSuggest(importTerms.value)
}

/** 把确认过的建议填回粘贴框，并**立即生成待导入文件**（H3 / 4.3.2）：
 *  原来只回填粘贴框、预览还是旧的「N 条」——屏幕上写 A、实际导入 B 的错位根因；
 *  现在采纳即调 usePastedText()，预览 / 待导入文件 / 导入内容三者同时同步，且省一次点击。
 *
 *  **按标准词归并**：同一个标准词常被多行命中（一行判「新建」、其余判「挂别名」），
 *  逐行输出会写出「柴胡」和「柴胡\t柴胡」两行 —— 待导入文件里同一个标准词出现两次，
 *  词表体检立刻报「需修改」。归并后一个标准词只出一行，别名合并去重。 */
const applySuggestions = () => {
  const byStd = new Map()
  for (const s of confirmable.value) {
    const std = String(s.standardTerm || '').trim()
    if (!std) continue
    if (!byStd.has(std)) byStd.set(std, new Set())
    const bag = byStd.get(std)
    // 挂别名：原文本身就是那个别名；新建：原文即标准词（等于 std 的会在下面被滤掉）
    if (s.action === 'alias') bag.add(String(s.original || '').trim())
    for (const a of s.aliases || []) bag.add(String(a || '').trim())
  }
  const lines = []
  for (const [std, bag] of byStd) {
    const aliases = [...bag].filter((a) => a && a !== std)
    lines.push(aliases.length ? `${std}\t${aliases.join('、')}` : std)
  }
  if (!lines.length) return
  pastedText.value = lines.join('\n')
  // 让下一次模板渲染把 enable 状态算对后立即生成文件；computed 同步重算，可直接调
  usePastedText()
  suggestions.value = []
  ElMessage.success(`已采纳 ${lines.length} 条并生成待导入文件，确认后点下方面板导入`)
}

const handleSubmit = async () => {
  if (!importFile.value) return
  const direct = isAdmin.value && mode.value === 'direct'
  const where = direct
    ? (target.value === 'base' ? '基础层（影响所有组织）' : '当前组织')
    : '本机个人词典'
  // 粘贴导入没有真实文件名，别让确认框显示「pasted-8-terms.json」这种内部产物名
  const isPasted = importFile.value.name.startsWith('pasted-')
  const src = isPasted ? `粘贴的 ${parsedPasted.value.length} 条` : `「${importFile.value.name}」`
  const ok = await confirmBox(
    direct
      ? `将把${src}并入【${where}】的${typeLabel(type.value)}词典（同标准词合并别名，不删除已有词条），立即生效。`
      : `将把${src}解析后并入${where}（${typeLabel(type.value)}），不影响小组基线。`,
    direct ? '确认直接导入' : '确认导入本地',
    { type: 'warning', confirmButtonText: direct ? '直接导入' : '导入本地', cancelButtonText: '取消' }
  )
  if (!ok) return

  submitting.value = true
  submitError.value = ''
  try {
    const form = new FormData()
    // H1 成因二：同一处理，取原生文件（upload 组件包裹对象的 .raw）
    form.append('file', importFile.value?.raw ?? importFile.value)
    if (direct) {
      const res = await importDict(form, type.value, target.value)
      result.value = {
        parsed: res.data?.imported ?? 0,
        failed: res.data?.failed ?? 0,
        failures: res.data?.failures ?? []
      }
      ElMessage.success(res.msg || '已导入并生效')
      // P1-6：导入成功但「归档版本生成失败」时，后端会回 archiveWarning（含真实原因）——
      // 此前**界面从不显示它**（全仓搜不到该字段），用户以为一切正常：后端已如实报出，价值却没到达用户。
      // 用警告消息（固定位置、可关闭、停留久一点）确保可见，而不是塞进下方可能不在视野内的结果区。
      if (res.data?.archiveWarning) {
        ElMessage.warning({ message: res.data.archiveWarning, duration: 8000, showClose: true })
      }
      } else {
        const res = await parseDictFile(form, type.value)
        const terms = res.data?.terms ?? []
        // 先把体检结果挂上：即便后面因为「没解析出词条」提前返回，
        // 用户也能看到问题出在哪，而不是只得到一句「没有术语」
        lint.value = res.data?.lint ?? null
        if (!terms.length) {
          ElMessage.warning('文件里没有解析出任何术语，请检查首列「标准术语」是否为空')
          return
        }
        let added
        try {
          added = mergeIntoLocal(terms)
        } catch (e) {
          // localStorage 满 / 隐私模式：明确告知没存进去，不假装成功
          ElMessage.warning('本机存储不可用或已满，导入未能保存')
          return
        }
        result.value = {
        parsed: terms.length,
        // 弹窗路径下 added 是 null（还没落盘），先留空不显示这张卡 ——
        // 原来写 `added ?? 0` 会让卡片显示「本地新增 0」，而实际合并后可能新增几十条
        added,
        failed: (res.data?.failures ?? []).length,
        failures: res.data?.failures ?? []
      }
        if (added === null) {
          // 有需要人工确认的差异：等弹窗里选完再落盘，成功提示由 applyMerge 出
          ElMessage.info('有需要确认的差异，请在弹窗里选择后合并')
        } else {
          ElMessage.success(`已并入本机个人词典（新增 ${added} 条）`)
        }
      }
      // 导入完成了，但要提醒「词表变了不等于归一结果变了」——
      // structured_data 是抽取时写下的快照，不重跑解析，新词条不会生效。
      rerunHint.value = { mode: direct ? 'direct' : 'local', at: new Date().toLocaleString() }
      // 上一次导入被「稍后再说」收起来的提示，这一轮重新放出来
      rerunDismissed.value = false
      uploadRef.value?.clearFiles()
      importFile.value = null
    } catch (e) {
      // H2 / M6：失败留在页面里给「哪一步、怎么办」，拦截器那条通用 toast 之外再补上下文
      submitError.value = apiErrorMessage(e, '导入失败')
    } finally {
      submitting.value = false
    }
  }

  // ---- 导入后的重跑引导 ----
  // 「本机个人词典」只影响这台浏览器上的个人用词，不影响小组基线，
  // 因此不需要（也不应该）在这里提示重跑；只有落到小组基线的那条路径才需要。
  const rerunNeeded = computed(() => rerunHint.value?.mode === 'direct' && !rerunDismissed.value)

  /** 「稍后再说」：收起这条提示。重跑入口在「标准化质量报告」页，随时能回去重跑 */
  const dismissRerun = () => {
    rerunDismissed.value = true
  }

  const rerunAll = async () => {
    if (!(await confirmBox(
      '将对本组织全部病历重跑「结构化解析 + 质控」。新词表要生效必须重跑：'
      + '解析结果存在病历的结构化字段里，不重跑就还是旧的。',
      '确认重跑解析与质控',
      { type: 'warning', confirmButtonText: '开始重跑', cancelButtonText: '取消' }
    ))) return
    rerunning.value = true
    try {
      await submitNlpBatch()
      await recomputeQc()
      ElMessage.success('已提交重跑任务，完成后到「标准化质量报告」查看新结果')
      rerunHint.value = null
    } catch {
      // 拦截器已提示
    } finally {
      rerunning.value = false
    }
  }
  </script>

<style scoped>
/* 粘贴文本导入：没有现成文件时的第二条入口 */
/* 第二条入口的分隔线用**浅一档**的 --line-soft，步骤之间的分隔线才用 --line。
   两级线的深浅拉开，版面上一眼能分出「这是同一步里的另一条路」
   和「这是下一步」—— 原先两处都用 --line，看起来像并列的四个步骤。
   与项目既有的两级用法一致（--line 边框线 / --line-soft 更轻的分隔）。 */
.paste-block {
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--line-soft);
}
/* 两条入口的标题与说明：与「步骤标题 + 步骤说明」同一套层级
   （标题 15px 墨色，说明 13px 次级灰）—— 同一页里同级的文字用同一种排版 */
.paste-t {
  font-size: var(--fs-base);
  color: var(--ink);
  margin-bottom: 2px;
}
.paste-d {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  line-height: 1.7;
  margin-bottom: var(--sp-2);
}
.paste-foot {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}
/* 上传路径的 AI 入口：与上传组件同区，别让 AI 按钮只长在下面的粘贴块里 */
.upload-foot {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-2);
}
/* 开关左侧的说明文字：与「我的 LLM」里「启用 LLM」的排布一致（文字在左、开关在右） */
.uf-label {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.paste-warn {
  font-size: var(--fs-xs);
  color: var(--danger);
}
/* M3①：AI 不可用的前置提示。整行块，不挤在按钮那一行里 */
.ai-off {
  margin-top: var(--sp-2);
  padding: var(--sp-1) var(--sp-2);
  border-left: 3px solid var(--ochre);
  background: var(--ochre-surface);
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  line-height: 1.6;
}

/* AI 补词建议：候选表 + 逐条确认 */
.ai-suggest {
  margin-top: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--ochre-surface);
}
.as-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}
.as-title {
  font-size: var(--fs-base);
  color: var(--ink);
}
.as-reason {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.as-tag { margin-left: var(--sp-1); }
.as-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}

/* 导入后的重跑引导：说清「为什么要重跑」，否则用户会以为导入没生效而反复重传 */
.rerun-hint {
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-3);
  border-left: 3px solid var(--ochre);
  background: var(--ochre-surface);
  border-radius: 4px;
}
.rh-title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.rh-desc {
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
.rh-ops {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  flex-wrap: wrap;
  margin-top: var(--sp-2);
}
.rh-link {
  font-size: var(--fs-xs);
  color: var(--link, #2b6cb0);
}
/* 「稍后再说」：这一行唯一的「什么都不做」出口。靠右放、配色压到最弱 ——
   它原先是个不可点的 span，夹在两个蓝色链接后面，看起来像「第三个失效的链接」。
   el-button link 自带 padding:2px 且行高比 13px 的链接高，用 height:auto 抹平，别把这一行撑高。 */
.rh-skip {
  margin-left: auto;
  height: auto;
  padding: 0 var(--sp-1);
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
/* 不加这条，el-button link 悬停会变主题蓝 —— 那又变回「链接」了 */
.rh-skip:hover,
.rh-skip:focus {
  color: var(--text);
}
/* 步骤条：序号圆点 + 标题 + 说明，降低「不知道下一步做什么」的成本 */
.step {
  display: flex;
  gap: var(--sp-3);
  padding-bottom: var(--sp-4);
  margin-bottom: var(--sp-4);
  border-bottom: 1px dashed var(--line);
}
.step:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
}
.step-no {
  flex: 0 0 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: var(--fs-xs);
  font-weight: 600;
}
.step-body {
  flex: 1 1 auto;
  min-width: 0;
}
.step-t {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.step-d {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  line-height: 1.7;
  margin-bottom: var(--sp-2);
}
/* 确认区：与上方的「核对」内容（预览明细 / 词表体检）用一条分隔线分开，
   让「核对」与「确认导入」在版面上成为两个阶段，而不是混在一起的一长串。
   只加分隔与留白，不动任何控件尺寸。 */
.confirm-area {
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
}
.target-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
.do-btn {
  margin-top: 2px;
}
/* M8：预览明细表 —— 「将写入 N 条术语」与「撤回」同排，表体在 max-height 内滚 */
.prev-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  margin-bottom: var(--sp-1);
}
.prev-table {
  margin-top: var(--sp-1);
}
/* H5：粘贴内容变化后的失效提示（预览区上方与导入按钮旁两处复用同一套样式） */
.prev-stale {
  display: block;
  margin: var(--sp-1) 0;
  font-size: var(--fs-xs);
  color: var(--danger);
  line-height: 1.6;
}
/* H2：导入失败页内提示——与成功结果区共用同一块位置语义，失败不静默 */
.import-err {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  border-left: 3px solid var(--danger);
  background: var(--danger-surface);
  color: var(--text);
  font-size: var(--fs-xs);
  line-height: 1.6;
}
/* V7（审查报告）：「格式示例」卡此前独占整行 —— 1384px 宽只填 31%、右侧空 932px。
   改为与导入面板**左右并排**：示例卡收敛到 320~420px 的右列（内容正好铺满），
   导入面板占左列；顶部的重跑引导横跨两列。窄屏回退为上下堆叠。 */
.dict-import {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 420px);
  gap: 0 var(--sp-3);
  align-items: start;
}
.dict-import > .rerun-hint {
  grid-column: 1 / -1;
}
/* M2：常驻返回入口的呼吸位，别贴在面板上 */
.import-top {
  grid-column: 1 / -1;
  margin-bottom: var(--sp-2);
}
@media (max-width: 1100px) {
  .dict-import {
    grid-template-columns: 1fr;
  }
}
.import-result {
  margin-top: var(--sp-4);
  /* V1 同款：结果统计卡横排吃掉宽度，不再竖着堆成三条满宽窄卡；
     文本块（下一步说明 / 体检 / 失败行 / 出口行）整行占满。 */
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
}
.import-result .stat {
  flex: 1 1 200px;
  min-width: 0;
}
.import-result > .what-next,
.import-result > .lint,
.import-result > .failures,
.import-result > .import-done-ops {
  flex: 0 0 100%;
}
/* 词表体检（批次 21）：问题清单。错误与警告用左侧色条区分，不用整块红黄底 ——
   整块底色会让人以为「导入失败了」，其实多数条目仍会正常导入 */
.lint {
  margin-top: var(--sp-3);
}
.lint-hd {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: var(--sp-2);
}
.lint-item {
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-2);
  border-left: 3px solid var(--line);
  background: var(--surface-sub);
  border-radius: 0 4px 4px 0;
}
.lint-item.error {
  border-left-color: var(--danger);
}
.lint-item.warning {
  border-left-color: var(--ochre);
}
.lint-top {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.lint-msg {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}
.lint-count {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.lint-terms {
  margin-top: 4px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text);
  word-break: break-all;
}
.lint-advice {
  margin-top: 4px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
/* 「接下来会怎样」：把结果落到下一步动作上，而不是只报数字 */
.what-next {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  background: var(--ink-light);
  border-radius: 4px;
  font-size: var(--fs-base);
}
/* 格式示例：给可照抄的表 */
.sample-t {
  font-size: var(--fs-base);
  font-weight: 600;
  margin-bottom: var(--sp-2);
}
.sample-tb {
  border-collapse: collapse;
  font-size: var(--fs-xs);
}
.sample-tb th,
.sample-tb td {
  border: 1px solid var(--line);
  padding: var(--sp-1) var(--sp-3);
  text-align: left;
}
.sample-tb th {
  background: var(--surface-sub);
  font-weight: 600;
}
.code {
  margin: 0;
  padding: var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  overflow-x: auto;
}
/* 导入完成后的出口行：与上方统计卡留出间距，按钮与说明同一行对齐 */
.import-done-ops {
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
</style>
