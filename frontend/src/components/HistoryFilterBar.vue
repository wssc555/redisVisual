<template>
  <div class="filter-bar">
    <!--
      profileId 下拉刻意含"全部实例"空选项：
      已删除实例的历史**只有不选实例才查得到**（后端对不存在的 profileId 返回 40401）。
    -->
    <div class="filter-row">
      <el-select
          v-model="filters.profileId"
          class="f-profile"
          size="small"
          clearable
          :placeholder="t('history.filter.allProfiles')"
      >
        <el-option v-for="p in profiles" :key="p.id" :label="p.name" :value="p.id"/>
      </el-select>
      <el-input-number
          v-model="filters.db"
          class="f-db"
          :min="0"
          :precision="0"
          size="small"
          controls-position="right"
          :placeholder="t('keyBrowse.db.label')"
      />
      <el-select
          v-model="filters.operation"
          class="f-op"
          size="small"
          clearable
          :placeholder="t('history.filter.allOperations')"
      >
        <el-option v-for="op in HISTORY_OPERATIONS" :key="op" :label="op" :value="op"/>
      </el-select>
    </div>

    <div class="filter-row">
      <el-input
          v-model="filters.key"
          size="small"
          clearable
          :placeholder="t('history.filter.keyPlaceholder')"
      />
      <!-- value 关键词为全表扫描，必须明示成本并给收窄建议 -->
      <el-tooltip :content="t('history.filter.valueHint')" placement="top">
        <el-input
            v-model="filters.value"
            class="f-value"
            size="small"
            clearable
            :placeholder="t('history.filter.valuePlaceholder')"
        >
          <template #suffix>
            <el-tag v-if="filters.value" size="small" type="warning">
              {{ t('history.filter.costly') }}
            </el-tag>
          </template>
        </el-input>
      </el-tooltip>
    </div>

    <div class="filter-row">
      <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          size="small"
          class="f-range"
          :start-placeholder="t('history.filter.startTime')"
          :end-placeholder="t('history.filter.endTime')"
          value-format="YYYY-MM-DDTHH:mm:ss"
          format="YYYY-MM-DD HH:mm:ss"
      />
      <el-button size="small" type="primary" :icon="Search" @click="emit('query')">
        {{ t('history.filter.query') }}
      </el-button>
      <el-button size="small" @click="onReset">{{ t('history.filter.reset') }}</el-button>
    </div>

    <!-- startTime > endTime 后端返 40001，前端先校验 -->
    <el-alert
        v-if="timeInvalid"
        class="time-error"
        :title="t('history.filter.timeInvalid')"
        type="error"
        :closable="false"
        show-icon
    />
  </div>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {Search} from '@element-plus/icons-vue'
import {HISTORY_OPERATIONS} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'
import type {HistoryFilters} from '../views/historyTypes'

const {t} = useI18n()

const props = defineProps<{
  filters: HistoryFilters
}>()

const emit = defineEmits<{
  (e: 'query'): void
  (e: 'reset'): void
}>()

const {profiles} = injectGlobalState()

/**
 * 筛选条件**双向绑定到父级的 filters**（筛选栏是草稿，查询时才由父级读取）。
 * 这里用 computed 读写透传，而不是 `reactive(props.filters)` ——
 * 后者会复制出一个与父级断连的对象，用户填完点查询父级读到的仍是旧值。
 */
const filters = computed(() => props.filters)

/** el-date-picker 的 datetimerange 用数组承载，value-format 已产出后端要的本地时间串 */
const timeRange = ref<[string, string] | null>(
    props.filters.startTime && props.filters.endTime
        ? [props.filters.startTime, props.filters.endTime]
        : null,
)

/** 归一到 filters 的 startTime/endTime */
const syncRange = () => {
  if (timeRange.value && timeRange.value.length === 2) {
    props.filters.startTime = timeRange.value[0]
    props.filters.endTime = timeRange.value[1]
  } else {
    props.filters.startTime = undefined
    props.filters.endTime = undefined
  }
}

/** el-date-picker 变化即归一到 filters（副作用放在 watch，不放进 computed） */
watch(timeRange, syncRange)

/**
 * 时间范围非法（start > end）：后端 40001，前端先拦。
 * 直接读 props.filters —— 此前误写成 `filters.startTime`（filters 是 ComputedRef，
 * 该表达式恒 undefined），整段成了永不触发的死代码。
 */
const timeInvalid = computed(() => {
  const {startTime, endTime} = props.filters
  return !!(startTime && endTime && startTime > endTime)
})

const onReset = () => {
  props.filters.profileId = undefined
  props.filters.db = undefined
  props.filters.key = ''
  props.filters.value = ''
  props.filters.operation = undefined
  timeRange.value = null
  syncRange()
  emit('reset')
}

/** 供父级在 query 前校验时间范围 */
defineExpose({syncRange})
</script>

<style scoped>
.filter-bar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.filter-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.f-profile {
  width: 200px;
}

.f-db {
  width: 100px;
}

.f-op {
  width: 160px;
}

.f-value {
  width: 240px;
}

.f-range {
  width: 380px;
}

.time-error {
  margin-top: 4px;
}

@media (max-width: 1023.98px) {
  .f-range {
    width: 100%;
  }

  .f-value {
    width: 100%;
  }
}
</style>