<template>
  <div class="history-table">
    <el-table v-loading="loading" :data="items" border size="small">
      <el-table-column :label="t('history.table.eventTime')" prop="eventTime" width="170">
        <template #default="{ row }">{{ formatDateTime(row.eventTime) }}</template>
      </el-table-column>
      <el-table-column :label="t('history.table.profile')" prop="profileId" width="90"/>
      <el-table-column :label="t('history.table.db')" width="70">
        <template #default="{ row }">{{ row.db ?? '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('history.table.keyName')" prop="keyName" min-width="200">
        <template #default="{ row }">
          <span class="mono" :title="row.keyName">{{ row.keyName }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('history.table.keyType')" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.keyType" size="small" type="info">{{ row.keyType }}</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('history.table.operation')" prop="operation" width="100">
        <template #default="{ row }">
          <!-- 删除类操作标红：筛 DEL 即可得删除记录，红色便于快速识别 -->
          <el-tag size="small" :type="isDeleteOp(row.operation) ? 'danger' : 'primary'">
            {{ row.operation }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('history.table.valuePreview')" min-width="220">
        <template #default="{ row }">
          <span v-if="row.valuePreview" class="mono" :title="row.valuePreview">
            {{ row.valuePreview }}
          </span>
          <span v-else class="muted">{{ t('common.none') }}</span>
          <!-- value_bytes 记原始长度，预览窗口外的部分不在这里 -->
          <el-tag v-if="row.valueBytes != null && isTruncated(row)" class="trunc-tag" size="small" type="info">
            {{ t('history.table.truncated') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('history.table.valueBytes')" width="100">
        <template #default="{ row }">{{ formatBytes(row.valueBytes) }}</template>
      </el-table-column>
      <el-table-column :label="t('history.table.operator')" prop="operator" width="110">
        <template #default="{ row }">{{ row.operator ?? '—' }}</template>
      </el-table-column>
      <template #empty>
        <div class="empty-cell">{{ t('history.empty') }}</div>
      </template>
    </el-table>

    <!--
      页码分页（与 SCAN 游标语义不同）：total 由后端给出，直接喂 el-pagination。
      注意用 :current-page 而非 v-model —— page 是 prop，v-model 会尝试对
      readonly prop 赋值触发 Vue 警告；翻页意图经 pageChange 事件交父级驱动。
    -->
    <el-pagination
        v-if="total > pageSize"
        :current-page="page"
        class="pager"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="emit('pageChange', $event)"
    />
    <div v-else-if="total > 0" class="total-hint">{{ t('history.table.total', {n: total}) }}</div>
  </div>
</template>

<script setup lang="ts">
import {useI18n} from 'vue-i18n'
import {DELETE_OPERATIONS, type KeyHistoryVO} from '../api'
import {formatBytes, formatDateTime} from '../composables/useFormat'

const {t} = useI18n()

defineProps<{
  items: KeyHistoryVO[]
  total: number
  page: number
  pageSize: number
  loading?: boolean
}>()

const emit = defineEmits<{ (e: 'pageChange', page: number): void }>()

const isDeleteOp = (op: string) => DELETE_OPERATIONS.has(op)

/**
 * 预览是否被截断：`value_bytes` 是原始长度，后端按 history.preview-bytes（默认 1024）
 * 截断后写入 value_preview。比较必须按 **UTF-8 字节数**而非 JS 字符串长度 ——
 * valueBytes 记的是字节，而 `preview.length` 是 UTF-16 code unit 数，
 * 中文等多字节字符下两者差 3 倍，直接比较会把未截断的值误标为「已截断」。
 */
const utf8ByteLength = (s: string): number => new TextEncoder().encode(s).length

const isTruncated = (row: KeyHistoryVO) =>
    row.valueBytes != null && row.valuePreview != null && row.valueBytes > utf8ByteLength(row.valuePreview)
</script>

<style scoped>
.mono {
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}

.muted {
  color: var(--el-text-color-placeholder);
}

.trunc-tag {
  margin-left: 6px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.total-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty-cell {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-placeholder);
}
</style>