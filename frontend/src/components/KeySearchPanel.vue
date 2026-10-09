<template>
  <div class="search-panel">
    <el-form label-width="110px" @submit.prevent>
      <el-form-item :label="t('keyBrowse.search.keyKeyword')">
        <el-input
            v-model="keyKeyword"
            :placeholder="t('keyBrowse.search.keyPlaceholder')"
            clearable
        />
      </el-form-item>
      <el-form-item :label="t('keyBrowse.search.valueKeyword')">
        <el-input
            v-model="valueKeyword"
            :placeholder="t('keyBrowse.search.valuePlaceholder')"
            clearable
        />
      </el-form-item>
      <el-form-item :label="t('keyBrowse.search.limit')">
        <el-input-number v-model="limit" :min="1" :max="200" controls-position="right"/>
        <el-button class="run-btn" type="primary" :loading="loading" @click="run">
          {{ t('keyBrowse.search.run') }}
        </el-button>
        <el-button :disabled="loading" @click="reset">{{ t('keyBrowse.search.reset') }}</el-button>
      </el-form-item>
    </el-form>

    <!-- value 检索成本提示：逐 key 读值做子串匹配，大库上开销很高 -->
    <el-alert
        v-if="valueKeyword"
        class="cost-hint"
        :title="t('keyBrowse.search.costHint')"
        type="info"
        :closable="false"
        show-icon
    />

    <!--
      契约提示区：
      exhausted=false 是「部分结果」而**不是错误** —— 撞上了后端扫描预算或命中上限。
      必须明示，否则用户会把部分结果误当全量。
    -->
    <el-alert
        v-if="result"
        class="contract-hint"
        :title="contractText"
        :type="result.exhausted ? 'success' : 'warning'"
        :closable="false"
        show-icon
    />

    <el-table v-if="result && result.items.length > 0" :data="result.items" border size="small">
      <el-table-column :label="t('common.key')" prop="key" min-width="180">
        <template #default="{ row }">
          <span class="hit-key" :title="row.key">{{ row.key }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('keyBrowse.search.type')" prop="type" width="80"/>
      <el-table-column :label="t('keyBrowse.search.size')" width="90">
        <template #default="{ row }">{{ formatNumber(row.size) }}</template>
      </el-table-column>
      <el-table-column :label="t('keyBrowse.search.ttl')" prop="ttlFormat" width="110"/>
      <el-table-column :label="t('keyBrowse.search.preview')" min-width="200">
        <template #default="{ row }">
          <span v-if="row.matchedPreview" class="hit-preview" :title="row.matchedPreview">
            {{ row.matchedPreview }}
          </span>
          <span v-else class="hit-none">{{ t('common.none') }}</span>
          <el-tag v-if="row.truncated" class="trunc-tag" size="small" type="info">
            {{ t('keyBrowse.search.truncated') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="emit('select', row.key)">
            {{ t('keyBrowse.detail.title') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-else-if="ran && !loading" class="search-empty">{{ t('keyBrowse.search.empty') }}</div>
  </div>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {ApiError, ErrorCode, type KeySearchVO, searchKeys} from '../api'
import {formatNumber} from '../composables/useFormat'

const {t} = useI18n()

const props = defineProps<{
  profileId: number
  db: number
}>()

const emit = defineEmits<{ (e: 'select', key: string): void }>()

const keyKeyword = ref('')
const valueKeyword = ref('')
/** 保守默认：后端 max-page-size 上限 200，先给 50 */
const limit = ref(50)
const loading = ref(false)
const result = ref<KeySearchVO | null>(null)
/** 是否已执行过至少一次检索（区分「还没搜」与「搜了没结果」） */
const ran = ref(false)

const contractText = computed(() => {
  const r = result.value
  if (!r) return ''
  return r.exhausted
      ? t('keyBrowse.search.complete', {scanned: r.scanned})
      : t('keyBrowse.search.partial', {scanned: r.scanned, budget: r.budget})
})

const reset = () => {
  keyKeyword.value = ''
  valueKeyword.value = ''
  result.value = null
  ran.value = false
}

const run = async () => {
  const key = keyKeyword.value.trim()
  const value = valueKeyword.value.trim()
  // 后端对两个关键词都为空直接返 40001（不会默默跑满预算），前端先拦
  if (key === '' && value === '') {
    ElMessage.warning(t('keyBrowse.search.atLeastOne'))
    return
  }
  loading.value = true
  ran.value = true
  try {
    const res = await searchKeys(props.profileId, {
      db: props.db,
      key: key || undefined,
      value: value || undefined,
      limit: limit.value,
    })
    result.value = res.data ?? null
  } catch (e: any) {
    result.value = null
    if (e instanceof ApiError && e.code === ErrorCode.VALIDATION_ERROR) {
      ElMessage.error(`${t('keyBrowse.search.runFailed')}: ${e.message}`)
    } else {
      ElMessage.error(t('keyBrowse.search.runFailed', {msg: e.message}))
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.run-btn {
  margin-left: 12px;
}

.cost-hint,
.contract-hint {
  margin-bottom: 10px;
}

.hit-key,
.hit-preview {
  font-family: monospace;
  word-break: break-all;
}

.hit-preview {
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.hit-none {
  color: var(--el-text-color-placeholder);
}

.trunc-tag {
  margin-left: 6px;
}

.search-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
</style>