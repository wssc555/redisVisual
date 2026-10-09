<template>
  <div class="set-editor">
    <div class="editor-head">
      <span class="editor-title">{{ t('typeEditors.set.title') }}</span>
      <span v-if="size !== null" class="editor-sub">
        {{ t('typeEditors.set.total', {n: size}) }}
      </span>
    </div>

    <!--
      SSCAN 无 match 参数（后端未暴露 Redis SSCAN 的 MATCH）—— UI 不提供过滤框，
      需要按内容查找请走「值检索」页。count 必传（同 Hash），后端硬钳制上限 500。
    -->
    <div class="scan-bar">
      <el-input-number
          v-model="count"
          :min="1"
          :max="500"
          :precision="0"
          size="small"
          controls-position="right"
          @change="reset"
      />
      <el-button size="small" :loading="loading" @click="reset">
        {{ t('typeEditors.common.refresh') }}
      </el-button>
    </div>
    <div class="scan-hint">{{ t('typeEditors.set.countHint') }}</div>

    <el-table :data="members" border size="small" max-height="320">
      <el-table-column :label="t('typeEditors.common.member')" prop="member" min-width="220">
        <template #default="{ row }">
          <span class="mono" :title="row">{{ row }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" width="170" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="onCheckExists(row)">
            {{ t('typeEditors.set.checkExists') }}
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

    <div class="editor-actions">
      <el-button size="small" @click="openAdd">{{ t('typeEditors.set.addMembers') }}</el-button>
      <el-button v-if="hasMore" size="small" :loading="loading" @click="loadMore">
        {{ t('typeEditors.common.loadMore') }}
      </el-button>
      <span v-else-if="members.length > 0" class="exhausted">
        {{ t('typeEditors.common.exhausted') }}
      </span>
    </div>

    <el-alert
        class="no-match-hint"
        :title="t('typeEditors.set.noMatchHint')"
        type="info"
        :closable="false"
    />

    <el-dialog v-model="addVisible" :title="t('typeEditors.set.addMembers')" width="480px" append-to-body>
      <el-form label-width="80px">
        <el-form-item :label="t('typeEditors.common.member')">
          <el-input
              v-model="addText"
              type="textarea"
              :rows="6"
              :placeholder="t('typeEditors.set.membersPlaceholder')"
          />
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
  </div>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {addSetMembers, ApiError, deleteSetMember, ErrorCode, scanSetMembers, setMemberExists,} from '../api'
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

/** count 必传，默认 50（后端上限 500） */
const count = ref(50)
const submitting = ref(false)
const addVisible = ref(false)
const addText = ref('')
const addTtl = ref<number | undefined>(undefined)

/** SSCAN 返回的 size = SCARD 基数，用于展示总数 */
const size = ref<number | null>(null)

/**
 * SSCAN 游标分页：**number 游标模式** —— `nextCursor === 0` 判耗尽，
 * 后端不返回 exhausted 字段。
 */
const pagination = useScanPagination<string, number>({
  mode: 'number',
  fetchPage: async (cursor) => {
    const res = await scanSetMembers(props.profileId, props.keyName, {
      db: props.db,
      cursor,
      count: count.value,
    })
    if (res.data?.size != null) size.value = res.data.size
    return {
      items: res.data?.members ?? [],
      nextCursor: res.data?.nextCursor ?? 0,
    }
  },
})

const members = pagination.items
const loading = pagination.loading
const hasMore = pagination.hasMore
const loadMore = pagination.loadMore
const reset = pagination.reset

watch(
    () => [props.profileId, props.db, props.keyName],
    () => {
      size.value = null
      // watch 里没有调用方兜底，必须就地 catch，否则网络错误成 unhandled rejection
      reset().catch((e) => handleError(e, t('typeEditors.common.loadFailed')))
    },
    {immediate: true},
)

const openAdd = () => {
  addText.value = ''
  addTtl.value = undefined
  addVisible.value = true
}

const onAdd = async () => {
  const list = addText.value.split('\n').map((s) => s.trim()).filter((s) => s !== '')
  if (list.length === 0) {
    ElMessage.warning(t('typeEditors.set.membersRequired'))
    return
  }
  submitting.value = true
  try {
    const res = await addSetMembers(props.profileId, props.keyName, list, addTtl.value, props.db)
    // added 只计真正新增的（已存在的成员不计入）
    ElMessage.success(t('typeEditors.set.added', {n: res.data?.added ?? 0}))
    addVisible.value = false
    await reset()
  } catch (e: any) {
    handleError(e, t('typeEditors.common.saveFailed'))
  } finally {
    submitting.value = false
  }
}

const onCheckExists = async (member: string) => {
  try {
    const res = await setMemberExists(props.profileId, props.keyName, member, props.db)
    const exists = res.data?.exists === true
    if (exists) {
      ElMessage.success(`${t('typeEditors.set.exists')}: ${member}`)
    } else {
      ElMessage.info(`${t('typeEditors.set.notExists')}: ${member}`)
    }
  } catch (e: any) {
    handleError(e, t('typeEditors.common.loadFailed'))
  }
}

const onDelete = async (member: string) => {
  const ok = await confirm(
      t('typeEditors.set.deleteMemberConfirm', {member}),
      t('typeEditors.set.deleteMember'),
      'warning',
  )
  if (!ok) return
  submitting.value = true
  try {
    await deleteSetMember(props.profileId, props.keyName, member, props.db)
    ElMessage.success(t('typeEditors.set.deleted'))
    await reset()
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

.scan-bar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.scan-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 8px;
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

.no-match-hint {
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