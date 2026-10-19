<template>
  <div class="split split-picker">
    <!-- ① 选择病历：看板式列表，常驻可见-->
    <div class="pane">
    <PanelCard title="选择病历">
      <FreshnessTag :time="loadedAt" reason="数据为本次页面读取时刻；解析/质控更新后请刷新" />
      <RangeFilter v-model="query" />
      <div class="actions">
        <el-button type="primary" :loading="listLoading" @click="search(1)">查 询</el-button>
        <el-button :disabled="listLoading" @click="resetFilters">重置</el-button>
        <span class="tip">共 {{ total }} 条，点击行即载入该病历原文</span>
      </div>

      <!-- max-height 与病历数据 / 人工复核同口径（420，2026-10-07 调档）：
           默认每页 20 行（2026-10-18 由 10 调为 20，与 Qc 页 AI 预检列表同口径），
           行数足以填满与右栏工作区等高后的卡片剩余高度，消除左栏大片留白。
           切 10 / 50 条时表体在固定高度内出内部滚动条，属预期。
           slim：左栏是窄分栏，8 列全渲染会把摘要挤没、并冒出不可发现的横向滚动条；
           slim 只留「病历ID(90) / 摘要 / 操作」三列（内容宽约 420px），其余信息在
           右侧详情区可见（载入后有完整原文与结构化结果）。不传 slim 的共用页不受影响。 -->
      <!-- P0-2：高度不再由 JS 按卡片实测反算（原 V5 的 useFillTableHeight），改由布局决定 ——
           外层 .fill-table 吃掉卡片剩余高度、表格自身 height="100%"（纯 CSS 解析）：
           左栏与右侧工作区等高后，表格填满卡片剩余高度，分页不再悬在卡底、
           表格下方也不再空一大块；420 仍是最小值（见 .fill-table--picker）。 -->
      <div class="fill-table fill-table--picker">
        <RecordTable
          ref="tableRef"
          slim
          :rows="rows"
          :loading="listLoading"
          loading-text="正在读取任务列表…"
          highlight-current
          :row-class-name="rowClass"
          height="100%"
          :action-width="90"
          @row-click="loadRecord"
        >
          <template #action="{ row }">
            <el-button link type="primary" @click.stop="loadRecord(row)">载入</el-button>
          </template>
          <template #empty>
            <!-- 与病历数据页同口径：范围筛选的列表，空态要说「无符合条件」而不是
                 Element 默认的「暂无数据」—— 后者读起来像系统没数据 -->
            <EmptyState :failed="listFailed" :loading="listLoading"
              text="无符合条件的病历" @retry="() => search(1)" />
          </template>
        </RecordTable>
      </div>

      <!-- 2026-10-19 分页器布局定高：原 layout="total, sizes, prev, pager, next, jumper"
          是 flex-wrap 流动布局 —— 总条数 34182 时 20 条/页的末页号是 4 位数「1710」，
          按钮行更宽把 jumper 挤到第三行（分页器高 124px）；切 50 条/页末页号 3 位数
          「684」放得下（84px）→ 分页器高度随页数位数变化，直接改变左栏/页面长度
          （用户反馈「20 条时长为 20、切 50 后长度变 21」即此）。
          改为固定两行结构：上一行 = 上一页/页码/下一页，下一行 = 每页条数/跳页，
          两行宽度都远小于栏宽，与总页数位数无关；total 移除（「共 N 条」已在左卡
          筛选行的提示里展示，不重复）。分页器恒高、切分页不再改变页面长度。 -->
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="PAGE_SIZES_STANDARD"
:total="total"
        layout="prev, pager, next, sizes, jumper"
            :disabled="listLoading"
            class="picker-pager"
            @current-change="search"
        @size-change="handleSizeChange"
      />
    </PanelCard>
    </div>
    <div class="pane">

    <!-- 服务状态预报（25.7）：加载时先探一次，用户点「执行抽取」之前就知道这次抽不抽得出东西
         —— 原先只能先点、拿到空结果再猜。已有抽取结果时不显示：结果自带的 emptyReason
         说的是抽取那一刻的真相，比事后探测更准。两个 Tab 都受服务状态影响，故放在标签之上。 -->
    <div v-if="probeHint" class="nlp-off nlp-probe">
      <span class="np-text">
        <template v-if="probeHint === 'DISABLED'">
          自动抽取功能<b>当前没有开启</b>，现在点「执行抽取」只会得到空结果；
          开启由系统管理员在服务端完成，页面上无法自助打开。
        </template>
        <template v-else-if="probeHint === 'SERVICE_UNREACHABLE'">
          <b>抽取服务当前连不上</b>，现在点「执行抽取」只会得到空结果；
          请联系系统管理员恢复抽取服务。
        </template>
        <template v-else>
          抽取服务在跑，但<b>模型没有加载成功</b>，现在抽取只有规则兜底
          （舌象、脉象、病因、治法），识别出的要素会明显偏少。
        </template>
      </span>
      <!-- 探测结论是「此刻」的：服务恢复了但页面还是旧结论时，用户需要能手动再问一次 -->
      <el-button link type="primary" :loading="nlpProbing" @click="probeNlp(true)">重新探测</el-button>
    </div>

    <el-tabs v-model="activeTab" class="nlp-tabs">
      <!-- ============ 单条解析 ============ -->
      <el-tab-pane label="单条解析" name="single">
        <PanelCard title="NLP 结构化解析">
          <div class="loaded-bar">
            <template v-if="recordId">
              已载入病历：<b>{{ loadedLabel }}</b>
              <span v-if="loadedMeta" class="tip">{{ loadedMeta }}</span>
              <span class="tip">关闭详情将清空当前病历与抽取结果</span>
              <!-- 与人工复核一致的「关闭详情」出口：清空当前病历，列表仍常驻可见 -->
              <el-button
                link
                type="primary"
                class="picker-toggle"
                :disabled="extracting"
                @click="closeDetail"
              >
                关闭详情
              </el-button>
            </template>
            <span v-else class="tip">请在上方「选择病历」列表中点选一份病历</span>
          </div>

          <!-- 无产出时把原因讲清楚：
               术语归一依赖上游实体，没有实体就没有可归的内容，不能让用户以为「归一没执行」。
               只能把「功能没开」与「服务挂了」合写成一句「未开启，或…」，用户看不出该找谁；
               后端下发 unavailableReason，这里按原因分别给结论与下一步，四态各说一件事。
               这段是给用户看的，一律说人话 —— 配置项、端口、字段名等技术细节
               已挪到后端 slf4j 日志（PythonNlpClient 启动时 warn 一次），此处不再出现。 -->
          <div v-if="emptyReason" class="nlp-off">
            <template v-if="emptyReason === 'DISABLED'">
              <b>本次没有抽取到病历要素</b>（疾病、证候、症状、中药等）——
              自动抽取功能<b>当前还没有开启</b>，所以没有产出内容。
              这不是「术语归一」没执行：<b>归一一直在跑</b>，只是没有可归的要素。
              开启由系统管理员在服务端完成，页面上无法自助打开。
            </template>
            <template v-else-if="emptyReason === 'SERVICE_UNREACHABLE'">
              <b>本次没有抽取到病历要素</b>（疾病、证候、症状、中药等）——
              自动抽取功能<b>已经开启，但抽取服务当前连不上</b>（服务可能未启动或中途停止了），
              所以这次没有产出内容。这不是「术语归一」没执行：<b>归一一直在跑</b>，只是没有可归的要素。
              请联系系统管理员恢复抽取服务后重试。
            </template>
            <template v-else-if="emptyReason === 'MODEL_MISSING'">
              <b>本次只识别出很少的要素</b> —— 抽取服务已经启动，但<b>模型没有加载成功</b>，
              这次只跑了规则兜底（舌象、脉象、病因、治法）。这不是「术语归一」没执行：
              <b>归一一直在跑</b>。请联系系统管理员检查抽取服务的模型文件。
            </template>
            <template v-else-if="emptyReason === 'UNKNOWN'">
              <!-- 仅在后端未下发 unavailableReason（版本未同步）时出现：
                   退回原先的合并说法，不冒充「原文没写要素」误导用户。 -->
              <b>本次没有抽取到病历要素</b>（疾病、证候、症状、中药等）。
              自动抽取功能当前未开启，或抽取服务暂时不可用，所以没有产出内容。
              这不是「术语归一」没执行：<b>归一一直在跑</b>，只是没有可归的要素。
              请联系系统管理员。
            </template>
            <template v-else>
              抽取已正常执行，但这段原文里没有识别出可归一的要素（疾病、证候、症状、中药等）。
              请确认原文是否写了主诉、四诊、辨证结论、用药等内容。
            </template>
          </div>

          <!-- 原文与结果**左右并排**：这一页的核心动作就是「对着原文核实体」，
               上下排会把两者推到两屏之外，用户得来回滚 —— 比输入框窄更难受。
               输入框窄的问题不在这一层，而在外层（挑病历的列表原本比工作区还宽），
               已由 .split-picker 把宽度让给工作区。 -->
          <div class="split split-compare">
            <!-- 原文：按字段模块化，可单独修改-->
            <div class="pane">
              <div class="pane-hd">原文（按字段模块化，可单独修改）</div>
              <!-- 分区常显 + 多列栅格：原先 4 组折叠、默认只开 2 组，
                   用户仍要逐组展开、整页依旧要滚动；与病历数据页同一口径。
                   控件尺寸由 size="large" 决定（40px）；多行字段高度由 v-autoheight
                   指令按内容自适应（不再用 Element autosize —— 实测它在病历载入、
                   手动输入时都不撑高，内容超出就出内部滚动条）。 -->
              <el-form
                ref="fieldFormRef"
                class="compact-form field-form"
                label-width="72px"
                size="large"
                @submit.prevent>
                <div v-for="g in FIELD_GROUPS" :key="g.title" class="form-group">
                  <div class="group-hd">{{ g.title }}</div>
                  <div class="form-grid">
                    <el-form-item
                      v-for="f in groupFields(g)"
                      :key="f.key"
                      :label="f.label"
                      :data-field="f.key"
                      :class="{ wide: f.wide, 'src-hit': hitKey === f.key }"
                    >
                      <el-input
                        v-model="fields[f.key]"
                        v-autoheight
                        :type="f.multi ? 'textarea' : 'text'"
                        :rows="f.multi ? 2 : undefined"
                        clearable
                      />
                    </el-form-item>
                  </div>
                </div>
              </el-form>

              <!-- 整段文本只读对照：抽取请求就是这段拼接结果-->
              <details
                class="composed-panel"
                :open="composedOpen"
                @toggle="composedOpen = $event.target.open"
              >
                <summary class="composed-hd">整段文本（只读对照）</summary>
                <div class="composed">
                  <template v-if="composedParts.length">
                    <template v-for="(p, i) in composedParts" :key="i"><mark v-if="p.hit" class="hit">{{ p.text }}</mark><template v-else>{{ p.text }}</template></template>
                  </template>
                  <template v-else>（当前无内容）</template>
                </div>
              </details>

              <div class="actions pane-actions">
                <el-button type="primary" :loading="extracting" :disabled="!composedText" @click="runExtract">
                  执行抽取
                </el-button>
                <el-button
                  :disabled="!canSave"
                  title="把本次抽取结果写入该病历的结构化数据（覆盖原有的），不是新增病历"
                  @click="save"
                >写回结构化数据</el-button>
                <!-- 禁用时说明原因，而不是让用户猜-->
                <span v-if="!recordId" class="tip">先在上方列表点选一份病历才能保存</span>
                <span v-else-if="!result" class="tip">先执行抽取才能保存</span>
                <span v-else-if="resultEmpty" class="tip">
                  本次没有抽到任何要素，写回会把原有结构化数据清空，已禁用
                </span>
              </div>
            </div>

            <!-- 抽取结果：术语已归一，展示「原文 → 标准词」对照-->
            <div class="pane">
              <div class="pane-hd">
                抽取结果
                <!-- 来源标注同样按原因细分：原先只有「模型抽取 + 规则补充」与
                     「自动抽取未开启」两种，后者会把「服务连不上」也说成「未开启」。 -->
                <span v-if="result" class="src-note" :class="{ warn: !result.modelAvailable }">
                  {{ sourceNote }}
                </span>
              </div>
              <!-- 抽取整体失败（当前主要是术语索引不可用 → 503）：就地留痕，不只是一个会消失的 toast -->
              <div v-if="extractError" class="nlp-off">
                <b>本次抽取没有完成</b> —— {{ extractError }}
                抽取与术语归一依赖 ES 术语索引，索引不可用时不会退化成「全部未收录」，请处理后再重试。
              </div>
              <!-- 文本超长被 NER 截断：尾部实体可能静默丢失，必须显式告知（否则用户以为抽全了） -->
              <div v-if="result?.truncated" class="norm-note warn">
                文本超长已截断，<b>尾部实体可能丢失</b> —— 模型侧只看得到前若干 token（规则兜底仍用全文）。
                可分段抽取后合并。
              </div>
              <!-- 归一状态行：明说「归一跑没跑、跑出了什么」 -->
              <div v-if="result" class="norm-note" :class="{ warn: !!emptyReason }">
                <template v-if="emptyReason">
                  本次没有抽取到要素，<b>术语归一没有可归的内容</b>（原因见上方提示）。
                </template>
                <template v-else>
                  已按词典归一：实体显示为<b>标准术语</b>，灰色小字是归一前的原文。
                </template>
              </div>

              <!-- 归一汇总：把「这次归一到底做了什么」摊成数字。此前只有一句「已按词典归一」，
                   用户既不知道命中了多少、未命中多少，也无从判断归一到底跑没跑。 -->
              <div v-if="normStat && normStat.total" class="norm-stat">
                <div class="ns-line">
                  参与归一的实体 <b>{{ normStat.total }}</b> 个：命中 <b>{{ normStat.hit }}</b>
                  <span v-if="normStat.hit">（{{ LEVEL_TINY[1] }} {{ normStat.exact }} / {{ LEVEL_TINY[2] }} {{ normStat.contain }} / {{ LEVEL_TINY[3] }} {{ normStat.fuzzy }}）</span>，
                  未命中 <b :class="{ bad: normStat.miss > 0 }">{{ normStat.miss }}</b>
                  <span v-if="normStat.noDict">；另有 {{ normStat.noDict }} 个实体所在类型未参与归一（无词典或未勾选归一）</span>
                  <span v-if="normStat.hit">；其中「包含 / 模糊」是推断命中，<b>需人工确认</b></span>
                </div>
                <div class="ns-line">
                  归一由 <b>ES 术语索引</b> 提供（ES 是唯一权威，没有内存兜底）；<b>索引不可用时抽取会直接失败并说明原因</b>，不会静默把词判成「未收录」。
                </div>
                <div class="ns-legend">
                  <span class="lg"><i class="dot solid"></i><span class="lg-t">实心标签＝来源（模型抽取 / 规则兜底）；「置信 xx%」是模型对该片段的识别把握，<b>与归一无关</b></span></span>
                  <span class="lg"><i class="dot hollow"></i><span class="lg-t">描边标签＝归一结果，命中分三档、可靠度 <b>精确 ＞ 包含 ＞ 模糊</b>；「未收录」＝词典里没这个词</span></span>
                </div>
              </div>

              <!-- 28.17：点击结果里的实体，左侧原文区定位到对应字段并高亮原文片段。
                   「空态 ↔ 结果态」是典型的页内状态切换：加 panel-fade 让结果「浮现」
                   而不是「啪」地出现。用 mode="out-in" 先让空态淡出再让结果淡入 ——
                   两个分支都很高，同时在场会让容器高度瞬间翻倍、下方内容整体下跳。
                   不做位移：容器嵌在卡片里，位移会带动整块抖动。
                   V4：结果列被拉到与左栏等高（align-self:stretch）后，空态用
                   .empty-center 在列内垂直居中，留白上下对称、不再整块堆在底部。
                   ⚠️ 分支必须包成**同为 div** 的 wrapper（key 区分）：直接让两个不同
                   组件（StructuredDataCard / EmptyState）互为 v-if/v-else 分支。
                   Vue 3.5 的 Transition out-in 对「两个不同组件分支」实测会卡死
                   （占位注释常驻、新旧都不渲染），换成同类型根节点后才有正确的
                   类型匹配与 key 替换路径。 -->
              <Transition name="panel-fade" mode="out-in">
                <div v-if="result" key="result" class="sd-branch">
                  <StructuredDataCard :data="result" locatable @locate="locateSourceText" />
                </div>
                <!-- 28.13：空态补行动引导 —— 此前只有「尚未抽取」四个字，
                     用户不知道该在哪一步、做什么，空白区又占满一屏 -->
                <div v-else key="empty" class="sd-branch empty-center">
                  <EmptyState text="尚未抽取">
                    <div class="empty-hint">
                      先在左侧「选择病历」点一条载入原文，切到「结构化抽取」后点<b>执行抽取</b>；
                      识别出的要素会在这里按疾病 / 症状 / 证候等 9 类展示，并可逐项归一。
                    </div>
                  </EmptyState>
                </div>
              </Transition>
            </div>
          </div>
        </PanelCard>
      </el-tab-pane>

      <!-- ============ 批量解析 ============ -->
      <el-tab-pane label="批量解析" name="batch" lazy>
        <NlpBatchPanel :active="activeTab === 'batch'" />
      </el-tab-pane>
    </el-tabs>
    </div>
  </div>
</template>

<script setup>
// 单条 / 批量 NLP 结构化解析页：上方病历列表点行载入原文，下方「单条解析」「批量解析」两个 Tab。
// 单条：原文按字段模块化可逐项改，抽取后展示归一结果、可写回该病历结构化数据（覆盖原有）；
// 批量：后端异步任务（提交后可关页面、靠轮询刷新进度）；权限为「登录即可」——nlp 域 5 个端点皆 LOGIN，本页无任何管理员守卫（原注释写「仅管理员可见」有误，2026-10-05 校正）。归一只认 ES 词典，索引不可用即整体失败。
import VisitTimeCell from '@/components/cells/VisitTimeCell.vue'
import FreshnessTag from '@/components/FreshnessTag.vue'
import RecordTable from '@/components/RecordTable.vue'
import AgeGenderCell from '@/components/cells/AgeGenderCell.vue'
import { computed, reactive, ref, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { usePagedList } from '@/composables/usePagedList'
import { useUrlFilters } from '@/composables/useUrlFilters'
import { useNlpStatus } from '@/composables/useNlpStatus'
import PanelCard from '@/components/PanelCard.vue'
import StructuredDataCard from '@/components/StructuredDataCard.vue'
import NlpBatchPanel from '@/components/NlpBatchPanel.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { runNlpExtractAsync, submitNlpBatch, getNlpBatchProgress, cancelNlpBatch, listNlpBatch } from '@/api/nlp'
import { searchRecords, getRawRecord, updateRecord } from '@/api/records'
import { fmtDateTime, fmtDate } from '@/utils/format'
import { apiErrorMessage } from '@/utils/errorMessage'
import { vAutoheight } from '@/utils/autoheight'
import { LEVEL_TINY, summarizeNorm } from '@/utils/structured'
import { confirmBox } from '@/utils/confirm'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'

// 抽取服务状态（25.7）：模块级单例探测，与下方结构化数据卡片共用同一次探测结果。
// 页面加载时先探一次，用户点「执行抽取」之前就能看到「这次抽不抽得出东西」。
const { status: nlpStatus, loading: nlpProbing, probeNlp } = useNlpStatus()

const activeTab = ref('single')

// P0-2：左栏「选择病历」表格不再由 JS 定高 —— 左栏与右侧工作区等高（见 .split-picker 样式），
// 表格填满卡片剩余高度这件事由 CSS 完成（外层 .fill-table 吃剩余空间 + 表格 height="100%"）。

// P2-9 数据新鲜度：页面读取时刻（诚实、不依赖后端字段、不猜加载函数内部）
const loadedAt = ref(new Date().toLocaleString())
// ===== 病历列表=====
// 默认不带时间范围：进页面即看全库（两端都空 = 不按时间过滤）。
// 想看「刚导进来的这批」用筛选条上的「今天」按钮临时圈定。
const query = reactive({ department: '', dateRange: null, grade: '', importRange: null })
const page = ref(1)
// 默认每页 20 条：与 Qc 页 AI 预检列表同口径（10→20），让表体行数足以填满
// 与右栏工作区等高后的卡片剩余高度，消除左栏大片留白（2026-10-18 用户拍板）。
// 切 10 / 50 条时表体在固定高度内出内部滚动条，属预期（见 .fill-table--picker）。
const pageSize = ref(20)
// 列表加载失败：与「确实没有匹配」区分开（三态统一）

// 查询病历列表（分页 / 筛选 / 重试共用）：传数字即跳到该页；失败置 listFailed 与「确实没有匹配」区分，toast 由拦截器出
// latest-wins：发起时取号，回来时号不是最新就整体丢弃 —— 快速连点翻页时慢的旧响应不覆盖新结果，
// 也不提前收掉 loading（范式同 components/TermInput.vue）
// 列表骨架统一走 usePagedList：失败保留已有行，但仍标记失败（与「确实没有匹配」区分）
const { list: rows, total, loading: listLoading, failed: listFailed, load: loadRecords } = usePagedList({
  fetcher: (signal) => searchRecords({ ...query, page: page.value, pageSize: pageSize.value }, { signal }),
  extract: (res) => ({ list: res.data?.records, total: res.data?.total }),
  clearOnFailure: false
})

// 对标 E5「可分享视图」：筛选与分页同步到 URL。
// 本函数在 setup 阶段同步还原条件，因此下面 onMounted 的首次加载会自动带上它们。
useUrlFilters(query, page, pageSize)

// 查询病历列表（分页 / 筛选 / 重试共用）：传数字即跳到该页
const search = async (p) => {
  if (typeof p === 'number') page.value = p
  await loadRecords()
}

// 每页条数变化：重置回第 1 页再查，避免停留在越界页码上拿到空列表
const handleSizeChange = () => {
  page.value = 1
  search()
}

// 重置筛选条件并回到第 1 页重新查询：清空全部条件（含导入时间），回到「全库」口径
const resetFilters = () => {
  query.department = ''
  query.dateRange = null
  query.importRange = null
  query.grade = ''
  search(1)
}

const recordId = ref('')
// 已载入病历的展示标识（优先登记号），保存确认与成功提示都要回显它
const loadedLabel = ref('')
// 病历基本信息（只读）：给出上下文，但不参与抽取
const loadedMeta = ref('')

// 高亮当前已载入病历所在行，方便在列表里定位（样式见 .row-active）
const rowClass = ({ row }) => (row.id === recordId.value ? 'row-active' : '')

// ===== 原文：模块化字段=====
/**
 * 抽取输入字段与分组，与「病历数据」的单条新增保持同一套分区命名；
 * 只列真正参与抽取的 12 个字段，基本信息另在载入条里只读展示。
 */
const FIELD_GROUPS = [
  { title: '主诉与病史', keys: ['chiefComplaint', 'selfReport', 'presentIllness'] },
  { title: '四诊', keys: ['inspection', 'tongue', 'pulse', 'physicalExam'] },
  { title: '诊断', keys: ['tcmDiagnosis', 'pattern'] },
  { title: '处方与随访', keys: ['prescription', 'followUp', 'treatmentEffect'] }
]

/**
 * 字段标签与形态。
 *
 * <p>`wide` 只留给长叙述（主诉 / 自诉 / 现病史 / 草药），占 2 列；
 * `multi` 用文本域、单行起步随输入自增，其余短字段单行输入。
 * 之前 12 个字段里 10 个是 wide，span 2 在 3 列栅格排不紧，纵向多出好几行。</p>
 */
const FIELD_LABELS = {
  chiefComplaint: { label: '主诉', multi: true, wide: true },
  selfReport: { label: '自诉', multi: true, wide: true },
  presentIllness: { label: '现病史', multi: true, wide: true },
  inspection: { label: '望诊', multi: true },
  tongue: { label: '舌诊', multi: true },
  pulse: { label: '脉诊', multi: true },
  physicalExam: { label: '查体', multi: true },
  tcmDiagnosis: { label: '中医诊断', multi: true },
  pattern: { label: '辨证结论', multi: true },
  prescription: { label: '草药', multi: true, wide: true },
  followUp: { label: '随访', multi: true },
  treatmentEffect: { label: '治疗效果', multi: true }
}

// 参与抽取的全部字段键，顺序即拼接顺序；由分组推导，新增字段只改 FIELD_GROUPS 即可
const ALL_KEYS = FIELD_GROUPS.flatMap((g) => g.keys)
// 把分组的 key 展开成带标签与形态的表单项，供模板遍历渲染
const groupFields = (group) => group.keys.map((k) => ({ key: k, ...FIELD_LABELS[k] }))

// 生成一份全空字段对象；换病历 / 关闭详情时用它整体重置，避免残留上一位患者的原文
const emptyFields = () => ALL_KEYS.reduce((o, k) => ({ ...o, [k]: '' }), {})
const fields = reactive(emptyFields())

// 抽取请求的文本：字段拼接，分隔符与后端既有口径一致（后端接口不变）
const composedText = computed(() => ALL_KEYS
  .map((k) => fields[k])
  .filter((s) => s && String(s).trim())
  .map((s) => String(s).trim())
  .join('。'))

// 28.17：原文 ↔ 结构化结果联动定位。点结果里的实体 → 找到含该原文串的字段并滚动高亮，
// 同时在「整段文本」里把命中的原文片段标记出来，4 秒后自动消退。
const fieldFormRef = ref(null)
const hitKey = ref('')
const hitText = ref('')
const composedOpen = ref(false)
let hitTimer = null

const clearHit = () => {
  clearTimeout(hitTimer)
  hitKey.value = ''
  hitText.value = ''
  composedOpen.value = false
}

// 命中片段切分：没有 hitText 时整段返回，命中处包成 { hit: true } 供模板画 <mark>
const composedParts = computed(() => {
  const t = composedText.value
  const needle = hitText.value
  if (!t) return []
  if (!needle) return [{ text: t, hit: false }]
  const parts = []
  let idx = 0
  for (;;) {
    const at = t.indexOf(needle, idx)
    if (at < 0) {
      parts.push({ text: t.slice(idx), hit: false })
      break
    }
    if (at > idx) parts.push({ text: t.slice(idx, at), hit: false })
    parts.push({ text: t.slice(at, at + needle.length), hit: true })
    idx = at + needle.length
  }
  return parts.filter((p) => p.text)
})

// 从结果卡片点实体进来：原文串可能同时出现在多个字段，取第一个命中的滚动过去
const locateSourceText = (raw) => {
  const needle = String(raw || '').trim()
  if (!needle) return
  hitText.value = needle
  hitKey.value = ALL_KEYS.find((k) => String(fields[k] || '').includes(needle)) || ''
  composedOpen.value = true
  nextTick(() => {
    if (!hitKey.value) return
    const el = fieldFormRef.value && fieldFormRef.value.$el
      ? fieldFormRef.value.$el.querySelector('[data-field="' + hitKey.value + '"]')
      : null
    if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
  clearTimeout(hitTimer)
  hitTimer = setTimeout(clearHit, 4000)
}

const text = ref('')
const result = ref(null)
const extracting = ref(false)
/**
 * 抽取整体失败的原因（当前最主要的一种：术语索引不可用 → 后端 503）。
 *
 * <p>归一自 2026-09-23 起只认 ES 索引、不再有内存兜底，索引挂掉会让抽取<b>整体失败</b>而不是
 * 返回一份全是「未收录」的结果。只弹一个几秒就消失的 toast 用户很容易错过，所以就地留一条，
 * 直到下次抽取成功才清掉。</p>
 */
const extractError = ref('')

// 可写回的前提：既载入了病历、又有抽取结果，且结果不是「9 类全空」。
// 最后一条是必须的：上游不可用时后端会规范化成「200 + 空 9 类」，
// 空对象也是真值，只判 !!result 会让保存按钮可用，一点就把已有结构化数据清成空，
// 而且写回会打上人工修改标记，清洗链路从此跳过该病历、再也补不回来。
const canSave = computed(() => !!recordId.value && !!result.value && !resultEmpty.value)

// 载入一份病历：换病历时必须清空上一次抽取结果，否则会把 A 的结果存进 B
const loadRecord = async (row) => {
  // 1. 无有效行号直接返回，避免拿空 id 发请求
  if (!row?.id) return
  // 2. 进入加载态；先清掉上一份病历的定位高亮，避免高亮残留到新病历
  clearHit()
  listLoading.value = true
  try {
    // 3. 拉取该病历原文，缺字段统一兜底为空
    const res = await getRawRecord(row.id)
    const d = res.data || {}
    // 4. 逐字段回填，先整体清空避免残留上一位患者的原文
    Object.assign(fields, emptyFields())
    ALL_KEYS.forEach((k) => {
      fields[k] = d[k] == null ? '' : String(d[k])
    })
    // 5. 记下当前病历标识与只读基本信息，供保存确认回显
    recordId.value = row.id
    loadedLabel.value = d.registrationNo || row.registrationNo || row.id
    loadedMeta.value = [d.gender, d.age ? `${d.age} 岁` : '', d.department,
      d.visitTime ? String(d.visitTime).replace('T', ' ').substring(0, 16) : '']
      .filter(Boolean).join(' · ')
    // 6. 换病历必须清掉上一次的抽取结果与失败留痕，否则会把 A 的结果存进 B
    result.value = null
    extractError.value = ''
    // 7. 同步拼接文本并切到单条解析页
    text.value = composedText.value
    activeTab.value = 'single'
  } catch {
    // 拦截器已提示
  } finally {
    // 8. 无论成败都收掉加载态
    listLoading.value = false
  }
}

// 关闭详情：清空当前病历与抽取结果，列表常驻可见
const closeDetail = () => {
  recordId.value = ''
  loadedLabel.value = ''
  loadedMeta.value = ''
  Object.assign(fields, emptyFields())
  result.value = null
  extractError.value = ''
  text.value = ''
  clearHit()
}

// 执行抽取：请求体是字段拼接文本；整体失败（如术语索引不可用 503）时就地留痕并清空旧结果，
// 避免用户对着上一份结果继续操作
const runExtract = async () => {
  // 1. 进入抽取态并清掉上一次的失败留痕
  extracting.value = true
  extractError.value = ''
  try {
    // 2. 提交字段拼接文本，落抽取结果（异步：提交拿任务号后轮询，不占请求线程）
    const data = await runNlpExtractAsync({ text: composedText.value })
    result.value = data
    // 3. 没抽到要素时提示一句，避免一片空白自己表达
    if (!data.modelAvailable) {
      ElMessage.warning('本次没有抽取到病历要素，术语归一没有可归的内容')
    }
  } catch (e) {
    // 拦截器已弹过 toast；这里再就地留一条 —— 503（术语索引不可用）是关键故障，不该只靠 toast
    // 4. 失败就地留痕并清空旧结果，不让用户对着上一份结果继续操作
    extractError.value = apiErrorMessage(e, '抽取失败')
    result.value = null
  } finally {
    // 5. 无论成败都收掉抽取态
    extracting.value = false
  }
}

/**
 * 抽取无产出。
 *
 * <p>用户在再次提出「结构化解析也应该执行术语归一」。实测
 * {@code NlpController.extract} 确实调用了 {@code EntityNormalizer.normalize}，
 * 归一一直有跑；之所以看起来「没执行」，是因为上游降级只回空 9 类，归一没有可归的内容。
 * 所以这里把「有没有产出、为什么没有」直接讲出来，而不是让一片空白自己表达。</p>
 *
 * <p>：{@code modelAvailable} 只是一个布尔值，「功能没开」与「服务挂了」在前端无法区分，
 * 文案只能写成「未开启，或抽取服务暂时不可用」，用户看不出该找谁、也不知道能不能自助解决。
 * 后端现在下发 {@code unavailableReason}，这里按原因分开：开关没开要找管理员开、服务连不上要找
 * 管理员恢复服务、模型没加载只出规则兜底、四者都不是则是原文确实没写要素。</p>
 */
const ENTITY_KEYS = [
  'diseases', 'symptoms', 'tongueList', 'pulseList', 'patternList',
  'causeList', 'treatmentList', 'formulaList', 'herbs'
]
// 抽取结果是否「无产出」：9 类实体全为空即视为空；结果本身为 null 时不判空，交给空态展示
const resultEmpty = computed(() => {
  const r = result.value
  if (!r) return false
  return ENTITY_KEYS.every((k) => !(Array.isArray(r[k]) && r[k].length))
})
/**
 * ''＝有产出；否则＝降级原因。
 * 取值：DISABLED（开关没开）/ SERVICE_UNREACHABLE（服务连不上）/ MODEL_MISSING（模型未加载）
 *      —— 这三个来自后端 unavailableReason；
 *      NO_ENTITY（抽取正常，但原文确实没写要素）在前端判定；
 *      UNKNOWN 只在后端未下发原因（版本未同步）时兜底，退回原先的合并说法。
 */
const emptyReason = computed(() => {
  const r = result.value
  if (!r || !resultEmpty.value) return ''
  if (r.unavailableReason) return r.unavailableReason
  return r.modelAvailable ? 'NO_ENTITY' : 'UNKNOWN'
})

/**
 * 服务状态横幅（25.7）：只在「还没有抽取结果、且探测到服务降级」时出现。
 *
 * <p>已有 result 时不显示：结果自带的 emptyReason 说的是抽取那一刻的真相，比事后探测更准；
 * 两条同时出现会出现「上方说连不上、下方结果又说摘到了要素」的自相矛盾。</p>
 */
const probeHint = computed(() => {
  if (result.value) return ''
  return nlpStatus.value?.unavailableReason || ''
})

// 结果来源标注：按原因各说一句，避免把「服务连不上」也说成「未开启」
const sourceNote = computed(() => {
  const r = result.value
  if (!r) return ''
  if (r.modelAvailable) return '模型抽取 + 规则补充'
  if (r.unavailableReason === 'MODEL_MISSING') return '仅规则兜底（模型未加载）'
  if (r.unavailableReason === 'SERVICE_UNREACHABLE') return '自动抽取未生效（服务连不上）'
  if (r.unavailableReason === 'DISABLED') return '自动抽取未开启'
  return '自动抽取未生效'
})

/**
 * 归一汇总（第九轮）：命中多少、未命中多少、精确/包含/模糊各几条；ES 是唯一权威、无内存兜底，故不区分途径。
 *
 * <p>口径实现放在 {@code utils/structured.js}，与实体卡片共用同一份分区定义 ——
 * 两处各写一份必然漂移。</p>
 */
const normStat = computed(() => summarizeNorm(result.value))

// 写回结构化数据：先二次确认（覆盖原有内容、不可撤销），确认后才提交，取消则直接返回不发请求
const save = async () => {
  // 1. 取用于回显的病历标识（优先登记号）
  const label = loadedLabel.value || recordId.value
  if (!(await confirmBox(
      `将本次抽取结果写入病历「${label}」的结构化数据，覆盖原有内容。确认？`,
      '写回结构化数据',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }))) {
    return
  }
  try {
    // 4. 确认后写回结构化数据并提示成功
    await updateRecord(recordId.value, { structuredData: result.value })
    ElMessage.success(`已写回病历「${label}」的结构化数据`)
  } catch {
    // 拦截器已提示
  }
}

onMounted(() => {
  search(1)
  // 先探一次服务状态：探测失败（后端连不上）不提示、不阻塞首屏，探测自身也不抛
  probeNlp()
})
</script>

<style scoped>
.tip { line-height: 1.7; }
.actions { margin-top: var(--sp-3); display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.nlp-tabs :deep(.el-tabs__header) { margin-bottom: var(--sp-3); }
.nlp-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
/* 「换病历」入口：选择病历卡收起后挂在载入条右侧 */
.picker-toggle { margin-left: auto; }
.loaded-bar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  background: var(--ink-light);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: 14px;
}
.loaded-bar b { color: var(--ink); }
/* 左栏（原文）给 1.25 份宽（第九轮实测校准，原为 1.5）。
   2026-10-19 起（用户反馈「单条解析的 NLP 结构化解析也是左右分布，要用「固定一边 +
   滚动条」方案解决留白」）：内层 .split-compare 两栏改为**等高锚定 + 内容超高时
   各自内部滚动**（见下方 V6 块）——原先「框不限高、内容自然撑开、由页面统一滚动」
   的写法在右栏结果长于左栏时会让行高随内容变化（用户反馈「执行后长度变了」即此）。
   现在列高恒等于等高框，找实体在单列内滚动即可，不再需要页面来回滚两栏；
   操作条仍 sticky bottom，列内滚动时恒在可视底边。比例依据（第九轮实测，仍有效）：
   - 左栏高度对宽度不敏感 —— 799→666px 区间内左表单恒为 545px（1366 下 517px），收窄它没有代价；
   - 右栏的实体卡高度对宽度极敏感 —— 1366 下右栏 439px 宽时 sd-card 高达 1056px，488px 宽即降到
     701px 并触底（标签不再反复折行）；1600 下 533px 宽本来已触底，加宽无收益。
   - 1.25 是拐点：再往下（1.1 / 1.0）右栏只再降 20~40px，却把左栏压得过窄。
   实测效果（已注入 normVia，即后端重启后的真实状态）：
     1366 下两栏高度差 808 → 429px；1600 下 404 → 381px；两档 sd-card 均落在 701px 地板值。
   注意：右栏比左栏高是内容量差异（9 类实体分区 + 归一汇总，对 12 个字段的表单），
   不可能靠比例抹平；这里只把「因宽度不足而白折的行」消掉。 */
.split {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: start;
}
/* 外层是「左＝选择病历（挑一份就够）／右＝工作区（改原文 + 看结果）」。
   原来是 1.25fr : 1fr —— 挑病历的列表反而比干活的工作区宽，优先级是反的；
   多层分栏叠起来后工作区只剩不到四成屏宽，长文本字段逐字换行。
   这里把宽度让给工作区，列表给一个能看全登记号 + 日期的下限即可。
   V3：左栏下限 300px → 460px，右栏 1.7fr → 1.6fr。
   原因：左栏表格在 slim 模式下内容宽约 420px（90 ID + 240 摘要下限 + 90 操作，
   加单元格内边距约需 440~460px），300px 下限等于把 6 列塞进看不见的横向滚动条；
   460px 保证「内容宽 ≤ 容器宽」，不再横向滚动。右栏仍拿大头（1.6fr），
   工作区表单与实体卡不被挤窄。 */
.split-picker {
  grid-template-columns: minmax(460px, 1fr) minmax(0, 1.6fr);
}
/* 内层「原文 / 抽取结果」保持**左右并排**（这是本页的核心：对着原文核实体）。
   比例给原文略多（1.15 : 1）：原文里长文本字段多，实体侧是标签云，同样宽度下原文更吃紧。
   原先的问题不是"该不该左右"，而是外层没给工作区足够宽 —— 见 .split-picker。 */
.split-compare {
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
}
/* 2026-10-19：内层两栏等高规则集中在下方 V6 块（含 min-height:0 / overflow-y:auto /
   space-between，2026-10-19 更新）；旧 V4 单设 last-child 的写法已并入，不再单独保留。 */
/* V6（留白·对称专项）：原文列（.split-compare 左栏）同样拉到与右栏等高。
   此前左栏继承 .split 的 align-items:start，只按自身表单内容高排布（约 545px），
   右栏结果卡 701px+，高度差整块裸露在左栏卡底下方。stretch 后左栏填满该列：
   表头与 4 个表单分组自然打包在顶部（.form-group 间距保持 6px，绝不拉伸输入框行），
   .pane-actions 用 margin-top:auto 贴住列底 —— 左右两栏底边齐平，多余弹性空间
   只落在「整段文本」与操作条之间这一处，而不是摊在输入行之间。
   2026-10-19（用户反馈「单条解析的 NLP 结构化解析也是左右分布，要用同一套
   「固定一边 + 滚动条」方案解决留白」）：网格项默认 min-height:auto，内容超高时
   拒绝收缩、把 .split-compare 撑破 —— 实测执行抽取后右卡 1247px 内容把内栏顶到
   1565px、溢出 918px 等高框，卡底以下整段外溢（这正是「执行后长度变了」的观感来源）。
   修正：两栏补 min-height:0 + overflow-y:auto —— 列高恒等于等高框，内容超高时各自
   内部滚动（不裁内容）；短内容时用 space-between 在列内均匀分布、消除大块留白
   （原先 margin-top:auto 会把全部弹性空间堆在「整段文本」与操作条之间一处）。
   与右栏空态 .empty-center{margin:auto 0} 不冲突：auto 边距优先于 space-between，
   空态仍上下居中。 */
.split-compare > .pane:first-child {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  justify-content: space-between;
}
.split-compare > .pane:last-child {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  justify-content: space-between;
}
/* 2026-10-19：操作条不再用 margin-top:auto 吸底（auto 边距会优先吸收全部弹性空间，
   使上方的 justify-content:space-between 恒为 no-op）；分布交给列级 space-between，
   操作条作为末子项自然落在列底。内部滚动时 position:sticky bottom:0 仍把操作条
   钉在滚动视口底边 —— 长表单滚动时「执行抽取 / 写回」按钮恒可见，交互不受影响。 */
.split-compare > .pane:first-child .pane-actions {
  flex: 0 0 auto;
}
/* V4：空态（尚未抽取）在列内垂直居中。
   margin:auto 上下均分剩余空间 —— flex 列布局中 auto 边距先于 justify-content
   吸收空隙，故留白上下对称而不是全堆在底部。
   .empty-center 挂在 EmptyState 组件上，Vue 会把它透传到 el-empty 根节点。 */
.split-compare > .pane:last-child .empty-center { margin: auto 0; }
/* 批次3 收尾（2026-10-05）：右栏顶部是标签行「单条解析 / 批量解析」，左栏顶部是卡片标题条 ——
   两者高度不同，直接并排时左栏会明显「高出一截」（1264×569 实拍对比可见：左卡起 y≈105，右卡起 y≈150）。
   这里让左栏下移一个标签行的高度，使两栏内容同线起点。
   45px 为实拍测量值；只此一处，若调整 Element 标签主题需同步改这里。
   连带修正：原先写的是 `.split > .pane:first-child`，会把内层 .split-compare 的
   左栏（原文）也下移 45px —— 但内层两栏同在一张卡片里、表头本就同线，
   那 45px 是误伤（V4 拉伸对齐时会放大成肉眼可见的错位）。收窄到 .split-picker 才是原意。 */
.split-picker > .pane:first-child { margin-top: 45px; }
/* V5（2026-10-17）：外层左栏「选择病历」也拉到与右栏工作区**等高**并让卡内内容撑满。
   此前外层沿用 .split 的 align-items:start，左栏只有自身内容高（1680 下 652px），
   右栏 949px —— 297px 空白整块裸露在左卡下方的页面底上（用户反馈的「两栏不齐、
   下面一大块空」）。stretch 后左卡填满该列，卡内纵向弹性：标题条固定、主体吃掉剩余高度、
   表格吃掉主体剩余高度（表是卡内最大的那块），分页紧随其后。
   多余空间落在表格**内部**（行不满时表现为表体留白，空态则居中），而不是裸露在卡片外面。
   P0-2：表格高度改由这条 CSS 链决定（.fill-table + height="100%"），
   不再用 useFillTableHeight 量卡片像素（那套依赖 DOM 时机，换档/数据变化都要重量）。
   ⚠️ 内层 .split-compare 的右栏另有 V4 的处理（.empty-center 上下均分），两处不冲突。 */
.split-picker { align-items: stretch; }
.split-picker > .pane:first-child {
  display: flex;
  flex-direction: column;
}
.split-picker > .pane:first-child > :deep(.panel) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  /* 等高后卡片贴住列底，去掉 PanelCard 自带的 12px 下外边距，避免底部多出一截 */
  margin-bottom: 0;
}
/* .panel-bd 在 PanelCard 内部（是 .panel 的子元素，不是 .pane 的），故用后代选择器，
   不能写子选择器 —— 写错会静默失效：卡片高度被撑开了，但主体不会跟着长高、分页也不会沉底 */
.split-picker > .pane:first-child :deep(.panel-bd) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
/* 卡内「吃满剩余高度」的表格容器：flex 吃掉主体剩余空间，表格自身 height="100%"。
   min-height 420 与改造前写死的档位一致（列高由内容决定时不会比那时更矮）。 */
.fill-table {
  flex: 1 1 auto;
  min-height: 0;
}
.fill-table--picker {
  min-height: 420px;
  /* 2026-10-18 用户最终方案「固定一边 + 滚动条」：左列表固定高度锚定，行多时表体内滚。
     max-height 700：左卡自然高 = 45(标签行下移) + 366(非表格块) + 700(表高) = 1111px，
     作为左右两栏的锚定行高；默认 20 行（约 752px 内容）超过 700 上限 → 表体内部滚动，
     不再无限撑长页面；切 10/50 条/页同样在固定高度内内滚。
     2026-10-19：锚定的**确定性**改由右栏承担（.pane:last-child 固定 height:1111px，
     见下方）——网格行高恒定后，左卡/本容器/表格恒为 700，max-height 降级为防御性封顶；
     分页 20↔50 只改变行数与表体内滚，不再改变任何长度。 */
  height: 700px;
  max-height: 700px;
}
/* V5：分页紧跟表格（表格已吃满卡片剩余高度，多余空间落在表体内部），padding 只保证 12px 间距。
   这里**不能**写 margin-top:auto —— 自动外边距会被 getComputedStyle 解析成一个具体像素值，
   早先那版 JS 定高会把它算进「表格下方的占位」，可用高度于是恒等于当前表格高度、
   表格永远停在最小值上；改成 CSS 定高后这个坑不再成立，但分页紧贴表格仍是本页口径。 */
.split-picker > .pane:first-child :deep(.panel-bd) > .picker-pager {
  margin-top: 0;
  padding-top: var(--sp-3);
  justify-content: flex-end;
}
/* 2026-10-18 用户最终方案「固定一边 + 滚动条」：右 pane 补等高链，穿透 el-tabs 两层。
   左栏已用 .fill-table--picker{max-height:700} 锚定行高 1111px；右栏从 pane 到 PanelCard
   逐层 flex:1/min-height:0 拉到同一行高并填满，内容超高时在 .panel-bd 内部滚动，
   不再无限撑长页面。右 pane 上限 1111px = 左栏锚定行高（45 标签行 + 366 非表格块 + 700 表高），
   防止载入长病历后右卡自然高把整行再顶高，保证「固定一边」生效。
   选择器层级：.pane:last-child（本组件模板节点，无需 :deep）→ .nlp-tabs（el-tabs 根，
   class 由本模板传入，直接选）→ :deep(.el-tabs__content / .el-tab-pane)（Element 内部 DOM）
   → :deep(.panel / .panel-bd)（PanelCard 内部 DOM）。隐藏的 tab-pane 是 display:none 不参与布局。
   2026-10-19 修正：max-height → height（定锚）。max-height 只封顶、高度仍由内容驱动——
   右侧内容短（未载入/空态/结果少）时行高缩水，左列表与分页跟着变短；这正是用户反馈
   「切分页 / 执行抽取后选择病历与 NLP 解析长度会变」的根因。改为固定 height 后右栏恒定
   1111px，网格行高恒定 → 左列表恒 700、内层 .split-compare 恒等高，切分页 / 执行 /
   换病历 / 服务横幅出现均不改变页面长度；内容超高由 .panel-bd / 内栏 overflow:auto 内部滚动承接。 */
.split-picker > .pane:last-child {
  display: flex;
  flex-direction: column;
  height: 1111px;
}
.split-picker > .pane:last-child .nlp-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.split-picker > .pane:last-child .nlp-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.split-picker > .pane:last-child .nlp-tabs :deep(.el-tab-pane) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.split-picker > .pane:last-child .nlp-tabs :deep(.panel) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  margin-bottom: 0;
}
.split-picker > .pane:last-child .nlp-tabs :deep(.panel-bd) {
  flex: 1;
  min-height: 0;
  /* 必须声明 flex column：否则 .panel-bd 是 block，其子项 .split-compare 的 flex:1 1 auto
     不生效、按内容高停放，卡内 split-compare 下方残留裸空白（实测 149px）。
     与左栏 .panel-bd 同款；本规则带 .nlp-tabs 前缀，只命中右侧工作区卡，不影响批量 Tab
     （批量卡 .panel-bd 由 NlpBatchPanel 自己的 space-between 规则接管）。 */
  display: flex;
  flex-direction: column;
  overflow: auto;
}
/* 右卡内「原文 / 抽取结果」两栏吸收多余高度：在 .panel-bd(flex column) 里吃满剩余空间 */
.split-picker > .pane:last-child .split-compare {
  flex: 1 1 auto;
  min-height: 0;
}
.pane { min-width: 0; }
.pane-hd { font-size: var(--fs-base); font-weight: bold; color: var(--ink); margin-bottom: var(--sp-2); }
.src-note { font-size: var(--fs-xs); color: var(--ink-mid); margin-left: var(--sp-2); }
.src-note.warn { color: var(--danger); }
.norm-note {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: 10px;
  line-height: 1.7;
}
.norm-note b { color: var(--ink); }
.norm-note.warn {
  background: var(--danger-surface);
  border-color: var(--danger-border-soft);
  color: var(--el-color-danger-dark-2);
}
/* 归一汇总（第九轮）：把命中数、未命中数、走 ES 还是内存摊开 */
.norm-stat {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: 10px;
  line-height: 1.8;
}
.norm-stat b { color: var(--ink); font-weight: bold; }
/* 未命中数算「该看一眼」的数：命中率偏低说明词典没收这个词，用户可能要补词典 */
.norm-stat b.bad { color: var(--danger); }
.ns-line + .ns-line { margin-top: 2px; }
.ns-legend {
  margin-top: var(--sp-2);
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--line-soft);
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--fs-xs);
}
.ns-legend .lg { display: flex; align-items: flex-start; gap: 5px; }
/* 文案必须包在一个 span 里：.lg 是 flex 容器，裸文本节点与 <b> 会各自变成匿名 flex 项，
   于是「…把握，| 与归一无关 | ；规则兜底…」被 gap 拆成三段、换行后断成三行 */
.ns-legend .lg-t { flex: 1 1 auto; }
.ns-legend b { color: var(--ink); }
/* 图例上的小圆点：实心＝实体来源标签，空心＝归一标签，与卡片上的两种描边风格对应；
   none 只占位对齐，第三行没有对应标签 */
.dot { flex: 0 0 auto; width: 9px; height: 9px; border-radius: 2px; margin-top: 5px; }
.dot.solid { background: var(--ink-mid); }
.dot.hollow { background: transparent; border: 1px solid var(--ochre); }
.dot.none { background: transparent; }
/* 无产出提示：把「为什么没有结果」摆到填写区上方，不藏在空态里 */
.nlp-off {
  background: var(--danger-surface);
  border: 1px solid var(--danger-border-soft);
  border-left: 3px solid var(--danger);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-3);
  font-size: var(--fs-xs);
  line-height: 1.8;
  color: var(--el-color-danger-dark-2);
}
.nlp-off b { color: var(--danger); }
/* 服务状态横幅（25.7）：与「无产出」提示同款配色，区别在它是动手之前的预报；
   正文用 span 包住，避免 flex 把每段文字拆成独立项而乱换行 */
.nlp-probe { display: flex; align-items: center; gap: var(--sp-2); }
.nlp-probe .np-text { flex: 1 1 auto; }
.nlp-probe .el-button { flex: 0 0 auto; }

/* 28.13：空态引导文案，居中但不喧宾夺主 */
.empty-hint { max-width: 360px; margin: 0 auto; font-size: var(--fs-xs); color: var(--text-sub-strong); line-height: 1.8; }

/* 原文模块化字段；分区常显 + 2 列栅格：
   V8：基础栅格从 3 列降到 2 列 —— 3 列时舌诊/脉诊等短字段的输入框只有约 89px，
   连「淡红舌」三个字都放不下；2 列下短字段输入框约 150~170px（典型视口），
   达到 ≥140px 的可输入下限。wide（长文本）占 2 列 = 整行，整行铺满不浪费。
   控件尺寸统一由 el-form 的 size="large" 决定（40px） */
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 10px; }
.form-grid .wide { grid-column: span 2; }
.form-group { margin-bottom: var(--sp-1); }
.group-hd {
  position: relative;
  padding: var(--sp-1) 0 var(--sp-1) var(--sp-2);
  margin-bottom: 6px;
  font-size: var(--fs-xs);
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
.form-grid :deep(.el-form-item) { margin-bottom: 6px; }
.form-grid :deep(.el-form-item__label) { font-size: var(--fs-xs); color: var(--text-sub-strong); line-height: 1.5; padding-bottom: 0; }
.field-form { margin-bottom: 6px; }
/* 28.17：点结果实体后命中的字段高亮，与下方原文片段标记同一色系 */
.form-grid :deep(.el-form-item.src-hit) {
  background: var(--ochre-light);
  border-radius: 6px;
  box-shadow: 0 0 0 1px var(--ochre);
}
/* 整段文本只读对照：默认收起，不占填写区版面 */
.composed-panel {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 0 var(--sp-3);
  margin-bottom: 10px;
}
.composed-hd {
  cursor: pointer;
  list-style: none;
  padding: var(--sp-2) 0;
  font-size: var(--fs-xs);
  color: var(--ink-mid);
}
.composed-hd::-webkit-details-marker { display: none; }
.composed-hd::before { content: '▸ '; color: var(--ink-mid); }
.composed-panel[open] .composed-hd::before { content: '▾ '; }
.composed-panel[open] { padding-bottom: 10px; }
/* 执行抽取 / 保存吸底：字段区较长，主操作始终可见 */
.pane-actions {
  position: sticky;
  bottom: 0;
  background: var(--surface);
  padding: var(--sp-2) 0;
  border-top: 1px solid var(--line);
  z-index: 1;
}
.composed {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: var(--sp-3) var(--sp-3);
  font-size: var(--fs-xs);
  line-height: 1.9;
  color: var(--text);
  /* 批次16 · 16.5：去掉框内滚动。
     这里原本是 max-height:220px + overflow:auto —— 正是本项目「长内容同页展开」口径要避免的
     那种框内滚动：内容在 220px 处被截断，用户得在面板里再滚一层才能看全（本页上方注释
     记为「第九轮改回同页展开」时漏掉的这处）。同页展开即可，页面自身滚动。 */
  white-space: pre-wrap;
  margin-bottom: 10px;
}
/* 28.17：整段文本里被点中的原文片段标记 */
.composed mark.hit {
  background: var(--ochre-light);
  color: var(--ochre-text);
  font-weight: 600;
  border-radius: 3px;
  padding: 0 var(--sp-1);
}
:deep(.row-active) td { background: var(--ink-light) !important; }

/* V8：原先这里有一条 `@media (max-width: 1560px) { .form-grid { 2 列 } }` ——
   基础栅格已改为 2 列（见上方 .form-grid），该断点与基础样式重复，故删除；
   降 1 列的职责只留给 ≤900px，不再需要中间档。 */
@media (max-width: 1200px) {
  .split { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .form-grid { grid-template-columns: 1fr; }
  .form-grid .wide { grid-column: auto; }
}
</style>
