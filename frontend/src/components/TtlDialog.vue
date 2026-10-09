<template>
  <el-dialog
      :model-value="modelValue"
      :title="t('keyBrowse.ttl.title')"
      width="440px"
      @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="110px" @submit.prevent>
      <el-form-item :label="t('keyBrowse.ttl.current')">
        <span class="current-ttl">{{ currentTtlLabel }}</span>
      </el-form-item>
      <el-form-item :label="t('keyBrowse.ttl.seconds')" required>
        <el-input-number
            v-model="ttlSeconds"
            :min="-1"
            :precision="0"
            controls-position="right"
            style="width: 180px"
        />
        <div class="ttl-hint">{{ t('keyBrowse.ttl.persist') }}</div>
      </el-form-item>
      <!--
        TTL = 0 不是「无过期」，而是**立即删除该 key**（后端 expire(key, 0) 语义）。
        这是最容易误操作的点，必须在提交按钮前红色明示。
      -->
      <el-alert
          v-if="ttlSeconds === 0"
          class="ttl-warn"
          :title="t('keyBrowse.ttl.zeroWarn')"
          type="error"
          :closable="false"
          show-icon
      />
      <el-alert
          v-else-if="ttlSeconds < -1"
          class="ttl-warn"
          :title="t('keyBrowse.ttl.tooSmall')"
          type="warning"
          :closable="false"
          show-icon
      />
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</el-button>
      <el-button :type="ttlSeconds === 0 ? 'danger' : 'primary'" @click="onSubmit">
        {{ t('keyBrowse.ttl.submit') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {useCrudConfirm} from '../composables/useCrudConfirm'

const {t} = useI18n()
const {confirm} = useCrudConfirm()

const props = defineProps<{
  modelValue: boolean
  keyName: string
  /** 当前 TTL（秒）；-1 = 永久 */
  currentTtl?: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', ttlSeconds: number): void
}>()

/** -1 = PERSIST（永久）；0 = 立即删除；>0 = 秒数。默认给 -1，最安全。 */
const ttlSeconds = ref(-1)

const currentTtlLabel = computed(() => {
  const v = props.currentTtl
  if (v === null || v === undefined) return t('keyBrowse.detail.ttl')
  if (v === -1) return t('keyBrowse.ttl.persist')
  return `${v} s`
})

watch(
    () => props.modelValue,
    (open) => {
      if (!open) return
      // 打开时给"永久"作为默认值：用户主动改 0 才触发删除路径
      ttlSeconds.value = -1
    },
    {immediate: true},
)

const onSubmit = async () => {
  if (ttlSeconds.value < -1) {
    // 已在模板里以 warning 呈现，这里兜底不提交
    return
  }
  if (ttlSeconds.value === 0) {
    // 立即删除 → 红色二次确认，明示不可恢复
    const ok = await confirm(
        t('keyBrowse.ttl.confirmDeleteBody', {key: props.keyName}),
        t('keyBrowse.ttl.confirmDeleteTitle'),
        'error',
    )
    if (!ok) return
  }
  emit('submit', ttlSeconds.value)
  emit('update:modelValue', false)
}
</script>

<style scoped>
.current-ttl {
  font-weight: 500;
}

.ttl-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 2px;
}

.ttl-warn {
  margin-top: 4px;
}
</style>