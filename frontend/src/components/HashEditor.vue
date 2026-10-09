<template>
  <div class="hash-editor">
    <div class="editor-head">
      <span class="editor-title">{{ t('typeEditors.hash.title') }}</span>
    </div>

    <!--
      count 必传：不传 count 后端判为「无界全量请求」，
      元素数超 large-key-threshold（默认 5000）直接 40001 拒绝。
      因此这里不给"留空=全部"的选项，而是显式要求填写。
    -->
    <div class="scan-bar">
      <el-input
          v-model="match"
          :placeholder="t('typeEditors.hash.matchPlaceholder')"
          clearable
          size="small"
          class="match-input"
          @change="reset"
      />
      <el-input-number
          v-model="count"
          :min="1"
          :max="1000"
          :precision="0"
          size="small"
          controls-position="right"
          @change="reset"
      />
      <el-button size="small" :loading="loading" @click="reset">
        {{ t('typeEditors.common.refresh') }}
      </el-button>
    </div>
    <div class="scan-hint">{{ t('typeEditors.hash.countHint') }}</div>

    <el-table :data="fields" border size="small" max-height="320">
      <el-table-column :label="t('typeEditors.common.field')" prop="field" min-width="140">
        <template #default="{ row }">
          <span class="mono" :title="row.field">{{ row.field }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('typeEditors.common.value')" min-width="200">
        <template #default="{ row }">
          <span class="mono" :title="row.value">{{ row.value }}</span>
          <el-tag v-if="row.truncated" class="trunc-tag" size="small" type="info">
            {{ t('typeEditors.common.truncated') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openEdit(row)">{{ t('common.edit') }}</el-button>
          <el-button size="small" text type="danger" @click="onDelete(row)">
            {{ t('common.remove') }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-cell">{{ t('typeEditors.common.empty') }}</div>
      </template>
    </el-table>

    <div class="editor-actions">
      <el-button size="small" @click="openAdd">{{ t('typeEditors.hash.addField') }}</el-button>
      <el-button size="small" @click="openBatch">{{ t('typeEditors.hash.batchAdd') }}</el-button>
      <el-button v-if="hasMore" size="small" :loading="loading" @click="loadMore">
        {{ t('typeEditors.common.loadMore') }}
      </el-button>
      <span v-else-if="fields.length > 0" class="exhausted">
        {{ t('typeEditors.common.exhausted') }}
      </span>
    </div>

    <!-- 单字段新增 / 编辑 -->
    <el-dialog v-model="editVisible" :title="editTitle" width="520px" append-to-body>
      <el-form label-width="90px">
        <el-form-item :label="t('typeEditors.common.field')">
          <el-input v-model="editField" :disabled="isEdit"/>
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.value')">
          <el-input v-model="editValue" type="textarea" :rows="3"/>
        </el-form-item>
        <!-- ttlSeconds 为 null/缺省时后端保持原有 TTL 不变（不清空） -->
        <el-form-item :label="t('typeEditors.common.ttlOptional')">
          <el-input-number
              v-model="editTtl"
              :min="-1"
              :placeholder="t('common.none')"
              controls-position="right"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onSave">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量写入：单次上限 1000 条；元素级 ttlSeconds 被后端忽略，只取顶层 -->
    <el-dialog v-model="batchVisible" :title="t('typeEditors.hash.batchAdd')" width="560px" append-to-body>
      <el-form label-width="90px">
        <el-form-item :label="t('typeEditors.common.value')">
          <el-input
              v-model="batchText"
              type="textarea"
              :rows="10"
              :placeholder="t('typeEditors.hash.batchPlaceholder')"
          />
          <div class="batch-hint">{{ t('typeEditors.hash.batchHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('typeEditors.common.ttlOptional')">
          <el-input-number
              v-model="batchTtl"
              :min="-1"
              :placeholder="t('common.none')"
              controls-position="right"
          />
          <div class="batch-hint">{{ t('typeEditors.hash.batchTtlIgnored') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="onBatch">
          {{ t('common.submit') }}
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
  batchSetHashFields,
  deleteHashField,
  ErrorCode,
  type HashFieldVO,
  scanHashFields,
  setHashField,
} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {useScanPagination} from '../composables/useScanPagination'

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

/** 匹配模式：glob。变化后必须重置游标，否则只在旧游标之后扫描 */
const match = ref('*')
/** count 必传，默认 100（远低于大 Key 阈值 5000） */
const count = ref(100)

const submitting = ref(false)
const editVisible = ref(false)
const editField = ref('')
const editValue = ref('')
const editTtl = ref<number | undefined>(undefined)
const isEdit = ref(false)

const batchVisible = ref(false)
const batchText = ref('')
const batchTtl = ref<number | undefined>(undefined)

const editTitle = computed(() =>
    isEdit.value ? t('typeEditors.hash.editField') : t('typeEditors.hash.addField'),
)

/**
 * HSCAN 游标分页：**number 游标模式** —— `nextCursor === 0` 判耗尽，
 * 后端不返回 exhausted 字段（useScanPagination 的 number 分支正是为此）。
 */
const pagination = useScanPagination<HashFieldVO, number>({
  mode: 'number',
  fetchPage: async (cursor) => {
    const res = await scanHashFields(props.profileId, props.keyName, {
      db: props.db,
      cursor,
      match: match.value || '*',
      count: count.value,
    })
    return {
      items: res.data?.fields ?? [],
      nextCursor: res.data?.nextCursor ?? 0,
    }
  },
})

const fields = pagination.items
const loading = pagination.loading
const hasMore = pagination.hasMore

const loadMore = pagination.loadMore
const reset = pagination.reset

watch(
    () => [props.profileId, props.db, props.keyName],
    () => {
      match.value = '*'
      // watch 里没有调用方兜底，必须就地 catch，否则网络错误成 unhandled rejection
      reset().catch((e) => handleError(e, t('typeEditors.common.loadFailed')))
    },
    {immediate: true},
)

const openAdd = () => {
  isEdit.value = false
  editField.value = ''
  editValue.value = ''
  editTtl.value = undefined
  editVisible.value = true
}

const openEdit = (row: HashFieldVO) => {
  isEdit.value = true
  editField.value = row.field
  editValue.value = row.value
  editTtl.value = undefined
  editVisible.value = true
}

const onSave = async () => {
  if (editField.value.trim() === '' || editValue.value === '') {
    ElMessage.warning(t('typeEditors.common.emptyValue'))
    return
  }
  submitting.value = true
  try {
    await setHashField(
        props.profileId,
        props.keyName,
        {
          field: editField.value.trim(),
          value: editValue.value,
          // undefined = 后端保持原 TTL；不能传 0（那是删除 key）
          ttlSeconds: editTtl.value,
        },
        props.db,
    )
    ElMessage.success(t('typeEditors.common.saved'))
    editVisible.value = false
    await reset()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.saveFailed'))
  } finally {
    submitting.value = false
  }
}

const onDelete = async (row: HashFieldVO) => {
  const ok = await confirm(
      t('typeEditors.hash.deleteFieldConfirm', {field: row.field}),
      t('typeEditors.hash.deleteField'),
      'warning',
  )
  if (!ok) return
  submitting.value = true
  try {
    await deleteHashField(props.profileId, props.keyName, row.field, props.db)
    ElMessage.success(t('typeEditors.hash.deleted'))
    await reset()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.deleteFailed'))
  } finally {
    submitting.value = false
  }
}

const openBatch = () => {
  batchText.value = ''
  batchTtl.value = undefined
  batchVisible.value = true
}

const onBatch = async () => {
  const entries: Array<{ field: string; value: string }> = []
  const lines = batchText.value.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (line.trim() === '') continue
    const eq = line.indexOf('=')
    if (eq <= 0) {
      ElMessage.error(t('typeEditors.hash.batchInvalidLine', {n: i + 1}))
      return
    }
    entries.push({field: line.slice(0, eq).trim(), value: line.slice(eq + 1)})
  }
  if (entries.length === 0) {
    ElMessage.warning(t('typeEditors.common.emptyValue'))
    return
  }
  if (entries.length > 1000) {
    ElMessage.error(t('typeEditors.hash.batchTooLarge', {n: entries.length}))
    return
  }
  submitting.value = true
  try {
    // 注意：元素的 ttlSeconds 不传（后端会忽略），只给顶层 ttlSeconds
    await batchSetHashFields(
        props.profileId,
        props.keyName,
        {entries, ttlSeconds: batchTtl.value},
        props.db,
    )
    ElMessage.success(t('typeEditors.hash.batchDone'))
    batchVisible.value = false
    await reset()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.saveFailed'))
  } finally {
    submitting.value = false
  }
}

/** 统一错误分支：40902 类型变化 / 40402 key 已删 / 其余带前缀 toast */
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
  margin-bottom: 8px;
}

.editor-title {
  font-weight: 500;
}

.scan-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}

.match-input {
  flex: 1;
}

.scan-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
  line-height: 1.4;
}

.editor-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}

.exhausted {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.batch-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 2px;
}

.mono {
  font-family: monospace;
  font-size: 13px;
  word-break: break-all;
}

.trunc-tag {
  margin-left: 6px;
}

.empty-cell {
  padding: 16px;
  text-align: center;
  color: var(--el-text-color-placeholder);
}
</style>