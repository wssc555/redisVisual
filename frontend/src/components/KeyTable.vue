<template>
  <!--
    SCAN 结果表。
    ⚠️ **只有 key 名一列** —— K1 返回的是纯字符串数组，类型 / TTL 属详情域（K2）。
    早期设计稿误以为 K1 返回 KeySummary[]，那会把 N+1 次详情请求塞进列表渲染。
  -->
  <div class="key-table">
    <div class="filter-row">
      <el-input
          class="filter-match"
          :model-value="match"
          :placeholder="t('keyBrowse.scan.matchPlaceholder')"
          clearable
          size="small"
          @update:model-value="emit('update:match', $event)"
      >
        <template #prefix>
          <el-icon>
            <Search/>
          </el-icon>
        </template>
      </el-input>
      <!-- 类型筛选：Redis 6.0+ SCAN TYPE（服务端过滤），切换由父组件重置游标 -->
      <el-select
          class="filter-type"
          :model-value="type ?? ''"
          :placeholder="t('keyBrowse.scan.typePlaceholder')"
          clearable
          size="small"
          @update:model-value="emit('update:type', $event || undefined)"
      >
        <el-option v-for="opt in TYPE_OPTIONS" :key="opt" :value="opt" :label="opt"/>
      </el-select>
    </div>

    <div v-if="keys.length === 0 && !loading" class="key-empty">{{ t('keyBrowse.scan.empty') }}</div>

    <el-scrollbar v-else class="key-scroll">
      <div
          v-for="k in keys"
          :key="k"
          :class="{ 'key-row': true, 'key-row--active': k === selectedKey }"
          @click="emit('select', k)"
      >
        <el-icon class="key-icon">
          <Document/>
        </el-icon>
        <span class="key-name" :title="k">{{ k }}</span>
      </div>
    </el-scrollbar>

    <div class="key-footer">
      <div class="key-hint">{{ t('keyBrowse.scan.orderNote') }}</div>
      <el-button
          v-if="hasMore"
          :loading="loading"
          size="small"
          @click="emit('loadMore')"
      >
        {{ t('keyBrowse.scan.loadMore') }}
      </el-button>
      <span v-else-if="keys.length > 0" class="key-exhausted">
        {{ t('keyBrowse.scan.exhausted') }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import {Document, Search} from '@element-plus/icons-vue'
import {useI18n} from 'vue-i18n'

/** 与后端白名单（KeyService.SUPPORTED_SCAN_TYPES）对齐；value 即 Redis TYPE 小写 */
const TYPE_OPTIONS = ['string', 'list', 'hash', 'set', 'zset'] as const

const {t} = useI18n()

defineProps<{
  keys: string[]
  selectedKey?: string | null
  loading?: boolean
  hasMore?: boolean
  match?: string
  type?: string
}>()

const emit = defineEmits<{
  (e: 'select', key: string): void
  (e: 'loadMore'): void
  (e: 'update:match', value: string): void
  (e: 'update:type', value: string | undefined): void
}>()
</script>

<style scoped>
.key-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
}

.filter-row {
  display: flex;
  gap: 6px;
}

.filter-match {
  flex: 1;
  min-width: 0;
}

.filter-type {
  width: 96px;
  flex-shrink: 0;
}

.key-scroll {
  flex: 1;
  min-height: 200px;
  max-height: calc(100vh - 320px);
}

.key-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.key-row:hover {
  background: var(--el-fill-color);
}

.key-row--active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.key-icon {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}

.key-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.key-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.key-footer {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
}

.key-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}

.key-exhausted {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  text-align: center;
}
</style>