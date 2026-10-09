<template>
  <div class="zset-editor">
    <div class="editor-head">
      <span class="editor-title">{{ t('typeEditors.zset.title') }}</span>
      <span v-if="total !== null" class="editor-sub">
        {{ t('typeEditors.zset.total', {n: total}) }}
      </span>
    </div>

    <!-- 双模式切换：索引分页 / 分数区间 -->
    <div class="mode-bar">
      <el-radio-group v-model="rangeMode" size="small" @change="onModeChange">
        <el-radio-button :value="false">{{ t('typeEditors.zset.indexMode') }}</el-radio-button>
        <el-radio-button :value="true">{{ t('typeEditors.zset.rangeMode') }}</el-radio-button>
      </el-radio-group>
      <el-select v-model="order" class="order-select" size="small" @change="load">
        <el-option :label="t('typeEditors.zset.orderAsc')" value="ASC"/>
        <el-option :label="t('typeEditors.zset.orderDesc')" value="DESC"/>
      </el-select>
    </div>

    <div class="range-bar">
      <template v-if="rangeMode">
        <el-input-number
            v-model="min"
            :controls="false"
            :placeholder="t('typeEditors.zset.min')"
            size="small"
            @change="load"
        />
        <span class="range-sep">—</span>
        <el-input-number
            v-model="max"
            :controls="false"
            :placeholder="t('typeEditors.zset.max')"
            size="small"
            @change="load"
        />
        <span class="range-hint">{{ t('typeEditors.zset.rangeModeHint') }}</span>
      </template>
      <template v-else>
        <el-input-number
            v-model="page"
            :min="1"
            :precision="0"
            size="small"
            controls-position="right"
            @change="load"
        />
        <el-input-number
            v-model="pageSize"
            :min="1"
            :max="100"
            :precision="0"
            size="small"
            controls-position="right"
            @change="onPageSizeChange"
        />
        <span class="range-hint">{{ t('typeEditors.zset.pageSizeHint') }}</span>
      </template>
    </div>

    <el-table :data="items" border size="small" max-height="320">
      <el-table-column :label="t('typeEditors.common.member')" prop="member" min-width="180">
        <template #default="{ row }">
          <span class="mono" :title="row.member">{{ row.member }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('typeEditors.common.score')" width="110">
        <template #default="{ row }">{{ row.score }}</template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" width="260" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openSetScore(row)">
            {{ t('typeEditors.zset.setScore') }}
          </el-button>
          <el-button size="small" text @click="openIncr(row)">
            {{ t('typeEditors.zset.incrScore') }}
          </el-button>
          <el-button size="small" text @click="onQueryRank(row)">
            {{ t('typeEditors.zset.rankQuery') }}
          </el-button>
          <el-button size="small" text type="danger" @click="onDelete(row)">
            {{ t('common.remove') }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-cell">{{ t('typeEditors.common.empty') }}</div>
      </template>
    </el-table>

    <!-- 页码分页：仅索引模式。分数区间模式下 page 不生效（后端契约），必须隐藏 -->
    <el-pagination
        v-if="!rangeMode && total !== null && total > pageSize"
        v-model:current-page="page"
        class="pager"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
    />

    <div class="editor-actions">
      <el-button size="small" @click="openAdd">{{ t('typeEditors.zset.addMember') }}</el-button>
      <el-button size="small" :loading="loading" @click="load">
        {{ t('typeEditors.common.refresh') }}
      </el-button>
    </div>

    <!-- 添加（NX 语义） -->
    <el-dialog v-model="addVisible" :title="t('typeEditors.zset.addMember')" width="460px" append-to-body>
      <el-alert class="mode-hint" :title="t('typeEditors.zset.nxHint')" type="info" :closable="false"/>
      <el-form label-width="80px">
        <el-form-item :label="t('typeEditors.common.member')">
          <el-input v-model="addMember"/>
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.score')">
          <el-input-number v-model="addScore" :precision="6" controls-position="right"/>
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.ttlOptional')">
          <el-input-number
              v-model="addTtl"
              :min="-1"
              :placeholder="t('common.none')"
              controls-position="right"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onAdd">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 覆盖设分（XX 语义） -->
    <el-dialog v-model="scoreVisible" :title="t('typeEditors.zset.setScore')" width="460px" append-to-body>
      <el-alert class="mode-hint" :title="t('typeEditors.zset.xxHint')" type="info" :closable="false"/>
      <el-form label-width="80px">
        <el-form-item :label="t('typeEditors.common.member')">
          <span class="mono">{{ targetMember }}</span>
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.score')">
          <el-input-number v-model="targetScore" :precision="6" controls-position="right"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scoreVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onSetScore">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 增量加分（ZINCRBY，回新分数） -->
    <el-dialog v-model="incrVisible" :title="t('typeEditors.zset.incrScore')" width="460px" append-to-body>
      <el-alert class="mode-hint" :title="t('typeEditors.zset.incrHint')" type="info" :closable="false"/>
      <el-form label-width="80px">
        <el-form-item :label="t('typeEditors.common.member')">
          <span class="mono">{{ targetMember }}</span>
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.score')">
          <el-input-number v-model="incrScore" :precision="6" controls-position="right"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="incrVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onIncr">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {
  addZSetMember,
  ApiError,
  deleteZSetMember,
  ErrorCode,
  getZSetRange,
  getZSetRank,
  incrZSetScore,
  setZSetScore,
  type ZSetMemberVO,
} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'

const {t} = useI18n()
const {confirm} = useCrudConfirm()

const props = defineProps<{
  profileId: number
  db: number
  keyName: string
}>()

const emit = defineEmits<{
  (e: 'typeMismatch'): void
  (e: 'gone'): void
}>()

/** true = 分数区间模式（分页器隐藏）；false = 索引分页模式 */
const rangeMode = ref(false)
const order = ref<'ASC' | 'DESC'>('ASC')
const page = ref(1)
/** 后端上限 100 */
const pageSize = ref(20)
/** null / undefined = 不限（只传其中之一即进入区间模式） */
const min = ref<number | undefined>(undefined)
const max = ref<number | undefined>(undefined)

const items = ref<ZSetMemberVO[]>([])
/** ZCARD 真值 */
const total = ref<number | null>(null)
const loading = ref(false)
const submitting = ref(false)

const addVisible = ref(false)
const addMember = ref('')
const addScore = ref(0)
const addTtl = ref<number | undefined>(undefined)

const scoreVisible = ref(false)
const targetMember = ref('')
const targetScore = ref(0)

const incrVisible = ref(false)
const incrScore = ref(0)

/**
 * Z1 读取（双模式）。
 *
 * 给 min 或 max 任一即进入**分数区间模式**，此时后端忽略 page —— 只返回首批。
 * 因此该模式下 UI 必须隐藏分页器（否则用户点页码没反应会以为坏了）。
 */
const load = async () => {
  loading.value = true
  try {
    const isRange = min.value !== undefined || max.value !== undefined
    const res = await getZSetRange(props.profileId, props.keyName, {
      db: props.db,
      page: page.value,
      pageSize: pageSize.value,
      order: order.value,
      min: min.value,
      max: max.value,
    })
    items.value = res.data?.items ?? []
    total.value = res.data?.total ?? null
    // 区间模式下 page 不生效，把页码归位以免切回索引模式时跳页
    if (isRange) page.value = 1
  } catch (e: any) {
    handleError(e, t('typeEditors.common.loadFailed'))
  } finally {
    loading.value = false
  }
}

const onModeChange = (mode: boolean) => {
  // 两个方向都要清区间值：切到区间模式预填无意义；切回索引模式若残留 min/max，
  // load() 仍会走后端分数区间分支（page 被忽略），而 UI 已重新显示分页器 —— 点页码无响应。
  min.value = undefined
  max.value = undefined
  page.value = 1
  load()
}

const onPageSizeChange = () => {
  page.value = 1
  load()
}

watch(
    () => [props.profileId, props.db, props.keyName],
    () => {
      items.value = []
      total.value = null
      page.value = 1
      rangeMode.value = false
      min.value = undefined
      max.value = undefined
      load()
    },
    {immediate: true},
)

const openAdd = () => {
  addMember.value = ''
  addScore.value = 0
  addTtl.value = undefined
  addVisible.value = true
}

const onAdd = async () => {
  if (addMember.value.trim() === '') {
    ElMessage.warning(t('typeEditors.common.emptyValue'))
    return
  }
  submitting.value = true
  try {
    const res = await addZSetMember(
        props.profileId,
        props.keyName,
        addMember.value.trim(),
        addScore.value,
        addTtl.value,
        props.db,
    )
    // NX 语义：成员已存在时 added=0，分数不会被覆盖
    if ((res.data?.added ?? 0) === 0) {
      ElMessage.warning(t('typeEditors.zset.notAdded'))
    } else {
      ElMessage.success(t('typeEditors.zset.added'))
    }
    addVisible.value = false
    await load()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.saveFailed'))
  } finally {
    submitting.value = false
  }
}

const openSetScore = (row: ZSetMemberVO) => {
  targetMember.value = row.member
  targetScore.value = row.score
  scoreVisible.value = true
}

const onSetScore = async () => {
  submitting.value = true
  try {
    const res = await setZSetScore(
        props.profileId,
        props.keyName,
        targetMember.value,
        targetScore.value,
        props.db,
    )
    // XX 语义：成员不存在时 updated=0
    if ((res.data?.updated ?? 0) === 0) {
      ElMessage.warning(t('typeEditors.zset.notUpdated'))
    } else {
      ElMessage.success(t('typeEditors.common.saved'))
    }
    scoreVisible.value = false
    await load()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.saveFailed'))
  } finally {
    submitting.value = false
  }
}

const openIncr = (row: ZSetMemberVO) => {
  targetMember.value = row.member
  incrScore.value = 0
  incrVisible.value = true
}

const onIncr = async () => {
  if (incrScore.value === null || incrScore.value === undefined) {
    ElMessage.warning(t('typeEditors.zset.incrRequired'))
    return
  }
  submitting.value = true
  try {
    const res = await incrZSetScore(
        props.profileId,
        props.keyName,
        targetMember.value,
        incrScore.value,
        props.db,
    )
    // 后端返回累加后的新分数
    ElMessage.success(t('typeEditors.zset.incrDone', {score: res.data?.score ?? '—'}))
    incrVisible.value = false
    await load()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.saveFailed'))
  } finally {
    submitting.value = false
  }
}

const onQueryRank = async (row: ZSetMemberVO) => {
  try {
    const res = await getZSetRank(props.profileId, props.keyName, row.member, order.value, props.db)
    // rank 从 0 起（Redis 原生语义）
    ElMessage.info(
        t('typeEditors.zset.rankResult', {
          rank: res.data?.rank ?? '—',
          score: res.data?.score ?? '—',
        }),
    )
  } catch (e: any) {
    handleError(e, t('typeEditors.common.loadFailed'))
  }
}

const onDelete = async (row: ZSetMemberVO) => {
  const ok = await confirm(
      t('typeEditors.zset.deleteMemberConfirm', {member: row.member}),
      t('typeEditors.zset.deleteMember'),
      'warning',
  )
  if (!ok) return
  submitting.value = true
  try {
    await deleteZSetMember(props.profileId, props.keyName, row.member, props.db)
    ElMessage.success(t('typeEditors.zset.deleted'))
    await load()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.deleteFailed'))
  } finally {
    submitting.value = false
  }
}

const handleError = (e: any, fallbackKey: string) => {
  if (e instanceof ApiError) {
    if (e.code === ErrorCode.TYPE_MISMATCH) {
      ElMessage.warning(t('typeEditors.common.typeMismatch'))
      emit('typeMismatch')
      return
    }
    if (e.code === ErrorCode.KEY_NOT_FOUND) {
      ElMessage.warning(t('typeEditors.common.notFound'))
      emit('gone')
      return
    }
  }
  ElMessage.error(t(fallbackKey, {msg: e.message}))
}
</script>

<style scoped>
.editor-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.editor-title {
  font-weight: 500;
}

.editor-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.mode-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.order-select {
  width: 110px;
}

.range-bar {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.range-sep {
  color: var(--el-text-color-secondary);
}

.range-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-basis: 100%;
  line-height: 1.4;
}

.pager {
  margin-top: 10px;
  justify-content: flex-end;
}

.editor-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.mode-hint {
  margin-bottom: 12px;
}

.mono {
  font-family: monospace;
  font-size: 13px;
  word-break: break-all;
}

.empty-cell {
  padding: 16px;
  text-align: center;
  color: var(--el-text-color-placeholder);
}
</style>