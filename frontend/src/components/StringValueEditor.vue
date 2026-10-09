<template>
  <div class="string-editor">
    <div class="editor-head">
      <span class="editor-title">{{ t('typeEditors.string.title') }}</span>
      <span v-if="value" class="editor-sub">
        {{ t('typeEditors.string.length', {n: formatNumber(value.length)}) }}
      </span>
    </div>

    <!--
      截断只读预览：值 > 1MB 时后端只回预览（truncated=true）+ 完整 length。
      此时**禁止编辑** —— 用户看不到全貌，改写会静默丢掉尾部数据。
    -->
    <el-alert
        v-if="value?.truncated"
        class="trunc-alert"
        :title="t('typeEditors.string.truncated')"
        type="warning"
        :closable="false"
        show-icon
    >
      <template #default>
        <div class="trunc-body">
          <span>{{ t('typeEditors.string.truncated') }}</span>
          <el-button size="small" type="primary" :loading="downloading" @click="download">
            {{ t('typeEditors.string.downloadPreview') }}
          </el-button>
        </div>
      </template>
    </el-alert>

    <el-input
        v-model="editValue"
        type="textarea"
        :rows="8"
        :readonly="value?.truncated"
        :disabled="!value"
        class="value-input"
        :placeholder="t('typeEditors.string.setPlaceholder')"
    />

    <div class="editor-actions">
      <el-button
          size="small"
          type="primary"
          :loading="saving"
          :disabled="value?.truncated || editValue === (value?.value ?? '')"
          @click="onSave"
      >
        {{ t('typeEditors.string.save') }}
      </el-button>
      <el-button size="small" :disabled="value?.truncated" @click="appendVisible = true">
        {{ t('typeEditors.string.append') }}
      </el-button>
    </div>

    <!-- 写入时可一并设 TTL；留空表示不设置 -->
    <el-form label-width="80px" class="ttl-form">
      <el-form-item :label="t('typeEditors.common.ttlOptional')">
        <el-input-number
            v-model="ttlSeconds"
            :min="-1"
            :placeholder="t('common.none')"
            controls-position="right"
        />
        <span class="ttl-hint">{{ t('typeEditors.common.ttlHint') }}</span>
      </el-form-item>
    </el-form>

    <el-dialog
        v-model="appendVisible"
        :title="t('typeEditors.string.append')"
        width="480px"
        append-to-body
    >
      <el-input
          v-model="appendText"
          type="textarea"
          :rows="4"
          :placeholder="t('typeEditors.string.appendPlaceholder')"
      />
      <template #footer>
        <el-button @click="appendVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="appending" @click="onAppend">
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
import {ApiError, appendString, ErrorCode, getString, setString, type StringValueVO} from '../api'
import {formatNumber} from '../composables/useFormat'

const {t} = useI18n()

const props = defineProps<{
  profileId: number
  db: number
  keyName: string
}>()

const emit = defineEmits<{
  (e: 'typeMismatch'): void
  (e: 'gone'): void
  (e: 'refresh'): void
}>()

const value = ref<StringValueVO | null>(null)
const editValue = ref('')
/** TTL 留空 = 不设置（undefined），不是 0 */
const ttlSeconds = ref<number | undefined>(undefined)
const saving = ref(false)
const appending = ref(false)
const downloading = ref(false)
const appendVisible = ref(false)
const appendText = ref('')

const load = async () => {
  try {
    const res = await getString(props.profileId, props.keyName, props.db)
    value.value = res.data ?? null
    editValue.value = res.data?.value ?? ''
  } catch (e: any) {
    if (e instanceof ApiError && e.code === ErrorCode.KEY_NOT_FOUND) {
      emit('gone')
      return
    }
    if (e instanceof ApiError && e.code === ErrorCode.TYPE_MISMATCH) {
      emit('typeMismatch')
      return
    }
    ElMessage.error(t('typeEditors.common.loadFailed', {msg: e.message}))
  }
}

watch(
    () => [props.profileId, props.db, props.keyName],
    () => {
      value.value = null
      editValue.value = ''
      ttlSeconds.value = undefined
      appendText.value = ''
      load()
    },
    {immediate: true},
)

const onSave = async () => {
  if (editValue.value === '') {
    ElMessage.warning(t('typeEditors.string.emptyValue'))
    return
  }
  saving.value = true
  try {
    await setString(
        props.profileId,
        {key: props.keyName, value: editValue.value, ttlSeconds: ttlSeconds.value},
        props.db,
    )
    ElMessage.success(t('typeEditors.string.saved'))
    await load()
    emit('refresh')
  } catch (e: any) {
    handleWriteError(e, () => (saving.value = false))
  } finally {
    saving.value = false
  }
}

const onAppend = async () => {
  if (appendText.value === '') {
    ElMessage.warning(t('typeEditors.string.emptyValue'))
    return
  }
  appending.value = true
  try {
    const res = await appendString(props.profileId, props.keyName, appendText.value, props.db)
    ElMessage.success(t('typeEditors.string.appended', {n: res.data?.length ?? 0}))
    appendText.value = ''
    appendVisible.value = false
    await load()
    emit('refresh')
  } catch (e: any) {
    handleWriteError(e, () => (appending.value = false))
  } finally {
    appending.value = false
  }
}

/**
 * 把当前持有的值以 Blob 方式下载到本地。
 *
 * ⚠️ **已知不完整**：截断发生在服务端（S1 只回前 1MB 预览 + 完整 length），
 * 前端拿不到尾部内容，因此大 value 下载到的是**预览片段**而非完整值。
 * 真正的完整值需要后端补一个流式下载端点（本期未提供）。
 * 小 value（未截断）时本方法即为完整下载。
 */
const download = () => {
  const text = value.value?.value ?? ''
  downloading.value = true
  try {
    const blob = new Blob([text], {type: 'text/plain;charset=utf-8'})
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${props.keyName}.txt`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(t('typeEditors.string.downloaded'))
  } finally {
    downloading.value = false
  }
}

/** 写操作的错误码分支：40902 类型变化 / 40402 已删除 */
const handleWriteError = (e: any, reset: () => void) => {
  if (e instanceof ApiError) {
    if (e.code === ErrorCode.TYPE_MISMATCH) {
      ElMessage.warning(t('typeEditors.common.typeMismatch'))
      reset()
      emit('typeMismatch')
      return
    }
    if (e.code === ErrorCode.KEY_NOT_FOUND) {
      ElMessage.warning(t('typeEditors.common.notFound'))
      reset()
      emit('gone')
      return
    }
  }
  ElMessage.error(t('typeEditors.common.saveFailed', {msg: e.message}))
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

.trunc-alert {
  margin-bottom: 10px;
}

.trunc-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.value-input :deep(.el-textarea__inner) {
  font-family: monospace;
  font-size: 13px;
}

.editor-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.ttl-form {
  margin-top: 8px;
}

.ttl-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 10px;
}
</style>