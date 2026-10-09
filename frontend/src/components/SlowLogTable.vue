<template>
  <!-- 慢查询日志。**durationUs 是微秒**，展示层必须换算 —— 直接显示会让人以为耗时很短。 -->
  <div class="slowlog">
    <div class="toolbar">
      <el-input-number
          v-model="count"
          :min="1"
          :max="128"
          :precision="0"
          size="small"
          controls-position="right"
          @change="load"
      />
      <el-button size="small" :loading="loading" :icon="Refresh" @click="load">
        {{ t('common.refresh') }}
      </el-button>
      <span class="hint">{{ t('monitor.slowlog.countHint') }}</span>
    </div>

    <el-table :data="entries" border size="small" max-height="420">
      <el-table-column :label="t('monitor.slowlog.id')" prop="id" width="90"/>
      <el-table-column :label="t('monitor.slowlog.timestamp')" prop="timestamp" width="180"/>
      <el-table-column :label="t('monitor.slowlog.duration')" width="120">
        <template #default="{ row }">
          <span :class="{ 'dur-slow': (row.durationUs ?? 0) >= 10000 }" class="mono">
            {{ formatMicros(row.durationUs) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('monitor.slowlog.command')" prop="command" min-width="320">
        <template #default="{ row }">
          <span class="mono" :title="row.command">{{ row.command }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-cell">{{ t('monitor.slowlog.empty') }}</div>
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {Refresh} from '@element-plus/icons-vue'
import {getSlowLog, type SlowLogVO} from '../api'
import {formatMicros} from '../composables/useFormat'

const {t} = useI18n()

const props = defineProps<{ profileId: number }>()

const entries = ref<SlowLogVO[]>([])
const count = ref(10)
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const res = await getSlowLog(props.profileId, count.value)
    entries.value = res.data ?? []
  } catch (e: any) {
    entries.value = []
    ElMessage.error(t('monitor.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

watch(() => props.profileId, load, {immediate: true})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.mono {
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}

/* ≥10ms 标红，与 Redis slowlog 的默认告警量级一致 */
.dur-slow {
  color: var(--el-color-danger);
}

.empty-cell {
  padding: 16px;
  text-align: center;
  color: var(--el-text-color-placeholder);
}
</style>