<template>
  <!--
    库切换器。**集群模式下不渲染**：后端对 CLUSTER 传 db != 0 直接 40001
    （集群只有 db0），前端先拦掉而不是发一个注定失败的请求。
  -->
  <div v-if="!isCluster" class="db-selector">
    <el-select
        :model-value="activeDb"
        :loading="loading"
        size="small"
        @change="onChange"
    >
      <el-option v-for="d in databases" :key="d.db" :label="`db${d.db}`" :value="d.db">
        <span class="db-option">
          <span class="db-name">db{{ d.db }}</span>
          <span class="db-keys">{{ t('keyBrowse.db.keys', {n: d.keys}) }}</span>
        </span>
      </el-option>
    </el-select>
    <span v-if="databases.length === 0 && !loading" class="db-empty">{{ t('keyBrowse.db.empty') }}</span>
  </div>
  <el-tag v-else size="small" type="info">{{ t('keyBrowse.db.fixed') }}</el-tag>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {injectGlobalState} from '../composables/useGlobalState'

const {t} = useI18n()

const emit = defineEmits<{ (e: 'loadFailed', msg: string): void }>()

const {activeProfileId, activeProfile, activeDb, setActiveDb, activeDatabases, loadDatabases} =
    injectGlobalState()

const loading = ref(false)

const isCluster = computed(() => activeProfile.value?.mode === 'CLUSTER')

const databases = computed(() =>
    activeDatabases.value.map((d) => ({db: d.db, keys: d.keys})),
)

/** 实例切换时拉该实例的 db 列表；失败由父级提示（组件内不弹 toast） */
watch(
    activeProfileId,
    async (id) => {
      if (id == null) return
      loading.value = true
      try {
        await loadDatabases(id)
      } catch (e: any) {
        emit('loadFailed', e.message)
      } finally {
        loading.value = false
      }
    },
    {immediate: true},
)

const onChange = (db: number) => {
  if (db != null) setActiveDb(db)
}
</script>

<style scoped>
.db-selector {
  display: flex;
  align-items: center;
  gap: 8px;
}

.db-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.db-keys {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.db-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>