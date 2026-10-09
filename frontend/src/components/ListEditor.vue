<template>
  <div class="list-editor">
    <div class="editor-head">
      <span class="editor-title">{{ t('typeEditors.list.title') }}</span>
      <span v-if="total !== null" class="editor-sub">
        {{ t('typeEditors.list.total', {n: total}) }}
      </span>
    </div>

    <!--
      区间读取（闭区间）。end 恒 >= 0 —— 后端把 end=-1 判为「无界全量请求」，
      大 Key 会直接 40001 拒绝。total = LLEN 真值，可据此跳页。
    -->
    <div class="range-bar">
      <el-input-number v-model="start" :min="0" :precision="0" size="small" controls-position="right"/>
      <span class="range-sep">—</span>
      <el-input-number v-model="end" :min="0" :precision="0" size="small" controls-position="right"/>
      <el-button size="small" :loading="loading" @click="loadRange">{{ t('typeEditors.common.refresh') }}</el-button>
      <span class="range-hint">{{ t('typeEditors.list.endHint') }}</span>
    </div>

    <el-alert
        class="index-warn"
        :title="t('typeEditors.list.deleteWarn')"
        type="warning"
        :closable="false"
    />

    <el-table :data="items" border size="small" max-height="320">
      <el-table-column :label="t('typeEditors.common.index')" width="70">
        <template #default="{ $index }">{{ start + $index }}</template>
      </el-table-column>
      <el-table-column :label="t('typeEditors.common.value')" min-width="200">
        <template #default="{ row }">
          <span class="mono" :title="row">{{ row }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" width="150" fixed="right">
        <template #default="{ row, $index }">
          <el-button size="small" text @click="openEdit(start + $index, row)">
            {{ t('common.edit') }}
          </el-button>
          <el-button size="small" text type="danger" @click="onDeleteByIndex(start + $index, row)">
            {{ t('common.remove') }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-cell">{{ t('typeEditors.common.empty') }}</div>
      </template>
    </el-table>

    <!-- true 分页：total 是 LLEN 真值，可跳页 -->
    <el-pagination
        v-if="total !== null && total > pageSize"
        v-model:current-page="page"
        class="pager"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadPage"
    />

    <div class="editor-actions">
      <el-button size="small" @click="openPush('LEFT')">{{ t('typeEditors.list.pushLeft') }}</el-button>
      <el-button size="small" @click="openPush('RIGHT')">{{ t('typeEditors.list.pushRight') }}</el-button>
      <el-button size="small" @click="popVisible = true">{{ t('typeEditors.list.pop') }}</el-button>
    </div>

    <!-- 改值（LSET） -->
    <el-dialog v-model="editVisible" :title="t('typeEditors.list.setByIndex')" width="480px" append-to-body>
      <el-form label-width="80px">
        <el-form-item :label="t('typeEditors.common.index')">
          <span>{{ editIndex }}</span>
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.value')">
          <el-input v-model="editValue" type="textarea" :rows="3"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onSetByIndex">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 插入（LPUSH / RPUSH） -->
    <el-dialog v-model="pushVisible" :title="pushTitle" width="480px" append-to-body>
      <el-form label-width="80px">
        <el-form-item :label="t('typeEditors.common.value')">
          <el-input
              v-model="pushText"
              type="textarea"
              :rows="5"
              :placeholder="t('typeEditors.list.valuesPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.ttlOptional')">
          <el-input-number v-model="pushTtl" :min="-1" :placeholder="t('common.none')" controls-position="right"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pushVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onPush">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 弹出（LPOP / RPOP），direction 是查询参数 -->
    <el-dialog v-model="popVisible" :title="t('typeEditors.list.pop')" width="420px" append-to-body>
      <el-radio-group v-model="popDirection">
        <el-radio-button value="LEFT">{{ t('typeEditors.list.popLeft') }}</el-radio-button>
        <el-radio-button value="RIGHT">{{ t('typeEditors.list.popRight') }}</el-radio-button>
      </el-radio-group>
      <template #footer>
        <el-button @click="popVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onPop">
          {{ t('typeEditors.list.pop') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {
  ApiError,
  ErrorCode,
  getListRange,
  listDeleteByIndex,
  listPop,
  listPushLeft,
  listPushRight,
  listSetByIndex,
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
  /** 按索引删除失败（50000 并发冲突）时通知父级重拉区间 */
  (e: 'refresh'): void
}>()

/** 默认区间 0–19（闭区间，end 恒 >= 0） */
const start = ref(0)
const end = ref(19)
const page = ref(1)
/** 固定 20：既是区间长度也是分页粒度，二者共用同一套 start/end 语义 */
const pageSize = 20

const items = ref<string[]>([])
/** LLEN 真值；null = 尚未加载 */
const total = ref<number | null>(null)
const loading = ref(false)
const submitting = ref(false)

const editVisible = ref(false)
const editIndex = ref(0)
const editValue = ref('')

const pushVisible = ref(false)
const pushDirection = ref<'LEFT' | 'RIGHT'>('RIGHT')
const pushText = ref('')
const pushTtl = ref<number | undefined>(undefined)

const popVisible = ref(false)
const popDirection = ref<'LEFT' | 'RIGHT'>('LEFT')

const pushTitle = computed(() =>
    pushDirection.value === 'LEFT' ? t('typeEditors.list.pushLeft') : t('typeEditors.list.pushRight'),
)

/** 区间读取。start/end 由分页器驱动，保持闭区间语义。 */
const loadRange = async () => {
  loading.value = true
  try {
    // end 强制 >= 0：负值会被后端判为无界全量请求
    const safeEnd = Math.max(0, end.value)
    const res = await getListRange(props.profileId, props.keyName, start.value, safeEnd, props.db)
    items.value = res.data?.items ?? []
    total.value = res.data?.total ?? null
  } catch (e: any) {
    handleError(e)
  } finally {
    loading.value = false
  }
}

const loadPage = (p: number) => {
  page.value = p
  start.value = (p - 1) * pageSize
  end.value = start.value + pageSize - 1
  loadRange()
}

watch(
    () => [props.profileId, props.db, props.keyName],
    () => {
      start.value = 0
      end.value = 19
      page.value = 1
      total.value = null
      items.value = []
      loadRange()
    },
    {immediate: true},
)

const openEdit = (index: number, value: string) => {
  editIndex.value = index
  editValue.value = value
  editVisible.value = true
}

const onSetByIndex = async () => {
  submitting.value = true
  try {
    await listSetByIndex(props.profileId, props.keyName, editIndex.value, editValue.value, props.db)
    ElMessage.success(t('typeEditors.common.saved'))
    editVisible.value = false
    await loadRange()
    emit('refresh')
  } catch (e: any) {
    handleError(e)
  } finally {
    submitting.value = false
  }
}

/**
 * 按索引删除。
 *
 * ⚠️ 后端是 LSET(哨兵值) + LREM 两步、**非原子**：并发修改下列表结构变了会抛
 * 50000。此时提示"已被并发修改"并重拉区间，而不是当成普通错误。
 */
const onDeleteByIndex = async (index: number, value: string) => {
  const ok = await confirm(
      t('typeEditors.list.deleteIndexConfirm', {index, value}),
      t('typeEditors.list.deleteWarnTitle'),
      'warning',
  )
  if (!ok) return
  submitting.value = true
  try {
    await listDeleteByIndex(props.profileId, props.keyName, index, props.db)
    ElMessage.success(t('typeEditors.common.deleted'))
    await loadRange()
    emit('refresh')
  } catch (e: any) {
    if (e instanceof ApiError && e.code === ErrorCode.SYSTEM_ERROR) {
      // 50000 = 按索引删除的并发冲突，语义上要求用户重拉
      ElMessage.warning(t('typeEditors.common.conflict'))
      await loadRange()
      return
    }
    handleError(e)
  } finally {
    submitting.value = false
  }
}

const openPush = (dir: 'LEFT' | 'RIGHT') => {
  pushDirection.value = dir
  pushText.value = ''
  pushTtl.value = undefined
  pushVisible.value = true
}

const onPush = async () => {
  const values = pushText.value.split('\n').map((s) => s.trim()).filter((s) => s !== '')
  if (values.length === 0) {
    ElMessage.warning(t('typeEditors.list.valuesRequired'))
    return
  }
  submitting.value = true
  try {
    const fn = pushDirection.value === 'LEFT' ? listPushLeft : listPushRight
    const res = await fn(props.profileId, props.keyName, values, pushTtl.value, props.db)
    ElMessage.success(t('typeEditors.list.pushed', {n: res.data?.length ?? 0}))
    pushVisible.value = false
    await loadRange()
    emit('refresh')
  } catch (e: any) {
    handleError(e)
  } finally {
    submitting.value = false
  }
}

const onPop = async () => {
  submitting.value = true
  try {
    const res = await listPop(props.profileId, props.keyName, popDirection.value, props.db)
    const v = res.data?.value
    ElMessage.success(v ? t('typeEditors.list.popped', {value: v}) : t('typeEditors.common.empty'))
    popVisible.value = false
    await loadRange()
    emit('refresh')
  } catch (e: any) {
    handleError(e)
  } finally {
    submitting.value = false
  }
}

/** 统一错误分支：40902 类型变化 / 40402 key 已删 / 索引越界重拉 / 其余原样 toast */
const handleError = async (e: any) => {
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
    if (e.code === ErrorCode.VALIDATION_ERROR && e.message.includes('索引')) {
      ElMessage.warning(t('typeEditors.list.indexOutOfRange'))
      await loadRange()
      return
    }
  }
  ElMessage.error(t('typeEditors.common.loadFailed', {msg: e.message}))
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

.range-bar {
  display: flex;
  align-items: center;
  gap: 6px;
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

.index-warn {
  margin-bottom: 8px;
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