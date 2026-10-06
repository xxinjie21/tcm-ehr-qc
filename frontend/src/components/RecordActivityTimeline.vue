<template>
  <!-- 对标 D3「活动流 / 变更审计」：病历详情页的「活动」页签。
       数据来自 GET /api/logs/by-object（后端按 (org_id, object_type, object_id) 建了索引，
       且复用审计页同一套可见范围 —— 按 ID 查不绕开数据域）。 -->
  <div v-loading="loading" class="activity">
    <el-empty
      v-if="!loading && !rows.length"
      :image-size="64"
      description="这条病历还没有改动留痕（留痕从本次上线开始记，历史改动查不到）"
    />
    <el-timeline v-else>
      <el-timeline-item
        v-for="r in rows"
        :key="r.id"
        :timestamp="fmt(r.logTime)"
        placement="top"
        :type="typeOf(r.action)"
      >
        <div class="act-hd">
          <b>{{ r.action }}</b>
          <span class="act-by">
            {{ r.operator || 'unknown' }}<template v-if="r.role"> · {{ r.role }}</template>
          </span>
        </div>
        <div v-if="r.detail" class="act-detail">{{ r.detail }}</div>
        <div v-if="r.orgName" class="act-org">所属组：{{ r.orgName }}</div>
      </el-timeline-item>
    </el-timeline>
    <!-- 分页用「加载更多」而不是页码：这是某个对象的历史，按时间倒序往下看比跳页自然 -->
    <div v-if="total > rows.length" class="act-more">
      <el-button link type="primary" :loading="loadingMore" @click="loadMore">
        还有 {{ total - rows.length }} 条，加载更多
      </el-button>
    </div>
  </div>
</template>

<script setup>
// 病历活动流（对标 D3）。刻意只读：审计留痕没有删除与编辑入口。
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getObjectLogs } from '@/api/log'

const props = defineProps({
  recordId: { type: String, default: '' }
})
// 让父级能在页签上显示条数徽标
const emit = defineEmits(['count'])

const PAGE_SIZE = 20
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const loadingMore = ref(false)

const typeOf = (action) => {
  // 颜色只用来区分"改了什么"的类别，不表达褒贬：删除红、修改蓝、其余灰
  if (!action) return 'info'
  if (action.includes('删除')) return 'danger'
  if (action.includes('修改')) return 'primary'
  return 'info'
}

const fmt = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '—')

async function load(reset = true) {
  if (!props.recordId) return
  if (reset) {
    loading.value = true
    rows.value = []
  } else {
    loadingMore.value = true
  }
  try {
    const res = await getObjectLogs({
      objectType: 'record',
      objectId: props.recordId,
      page: reset ? 1 : Math.floor(rows.value.length / PAGE_SIZE) + 1,
      pageSize: PAGE_SIZE
    })
    const list = res?.data?.list || []
    rows.value = reset ? list : rows.value.concat(list)
    total.value = res?.data?.total || rows.value.length
    emit('count', total.value)
  } catch {
    // 拦截器已提示；这里不再叠一层，避免"审计查不到"被说成"病历有问题"
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

const loadMore = () => load(false)

// 切换病历要重新拉，否则会把上一条的改动记在另一条病历名下
watch(() => props.recordId, () => load(true), { immediate: true })
</script>

<style scoped>
.activity {
  min-height: 120px;
  /* 与左栏一致：内容高时内部滚动，别把弹窗页脚顶出视口（页脚是唯一的关闭出口） */
  max-height: calc(86vh - 190px);
  overflow: auto;
}

.act-hd {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
}

.act-by {
  font-size: var(--fs-xs);
  color: var(--text-sub);
}

.act-detail {
  margin-top: 2px;
  font-size: var(--fs-base);
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
}

.act-org {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--text-sub);
}

.act-more {
  text-align: center;
  padding-bottom: var(--sp-2);
}
</style>
