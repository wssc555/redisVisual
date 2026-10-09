<template>
  <div class="page">
    <div class="page-head">
      <h3 class="page-title">{{ t('history.title') }}</h3>
    </div>

    <!--
      覆盖边界横幅（必显）：用户极易把操作日志误当 Redis 的全量变更历史。
      外部客户端变更与 TTL 过期都不在其中 —— 说清比不说是负责任。
    -->
    <el-alert
        class="coverage"
        :title="t('history.coverage.title')"
        :description="t('history.coverage.text')"
        type="info"
        :closable="false"
        show-icon
    />

    <HistoryFilterBar ref="filterBarRef" :filters="filters" @query="onQuery" @reset="onReset"/>

    <HistoryTable
        :items="items"
        :total="total"
        :page="page"
        :page-size="pageSize"
        :loading="loading"
        @page-change="onPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import HistoryFilterBar from '../components/HistoryFilterBar.vue'
import HistoryTable from '../components/HistoryTable.vue'
import {type KeyHistoryVO, queryHistory} from '../api'
import type {HistoryFilters} from './historyTypes'

const {t} = useI18n()

/** 筛选草稿；默认不选实例 = 全部实例（含已删实例的历史） */
const filters = reactive<HistoryFilters>({
  profileId: undefined,
  key: '',
  value: '',
  operation: undefined,
})

const items = ref<KeyHistoryVO[]>([])
const total = ref(0)
const page = ref(1)
/** 后端 history.max-page-size 上限 200 */
const pageSize = 20
const loading = ref(false)

const filterBarRef = ref<InstanceType<typeof HistoryFilterBar> | null>(null)

/**
 * 构造查询参数：空串 / undefined 一律不下发 ——
 * 后端按"参数是否存在"决定是否加 where 条件，传空串会变成"key 包含空串"的全匹配。
 */
const buildQuery = () => ({
  profileId: filters.profileId,
  db: filters.db,
  key: filters.key?.trim() || undefined,
  value: filters.value?.trim() || undefined,
  operation: filters.operation || undefined,
  startTime: filters.startTime,
  endTime: filters.endTime,
  page: page.value,
  pageSize,
})

const load = async () => {
  loading.value = true
  try {
    const res = await queryHistory(buildQuery())
    items.value = res.data?.items ?? []
    total.value = res.data?.total ?? 0
    // 后端可能返回归一化后的 page（如请求超界被夹回最后一页）
    if (res.data?.page != null && res.data.page !== page.value) {
      page.value = res.data.page
    }
  } catch (e: any) {
    items.value = []
    total.value = 0
    ElMessage.error(t('history.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

const onQuery = () => {
  filterBarRef.value?.syncRange()
  // startTime > endTime 后端返 40001，前端先拦
  if (filters.startTime && filters.endTime && filters.startTime > filters.endTime) {
    ElMessage.warning(t('history.filter.timeInvalid'))
    return
  }
  page.value = 1
  load()
}

const onReset = () => {
  page.value = 1
  load()
}

const onPageChange = (p: number) => {
  page.value = p
  load()
}

onMounted(load)
</script>

<style scoped>
.page-head {
  margin-bottom: 12px;
}

.page-title {
  margin: 0;
  font-size: 16px;
}

.coverage {
  margin-bottom: 12px;
}
</style>