<template>
  <el-dialog
      :model-value="modelValue"
      :title="t('keyBrowse.rename.title')"
      width="480px"
      @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="90px" @submit.prevent>
      <el-form-item :label="t('keyBrowse.rename.current')">
        <span class="current-key" :title="keyName">{{ keyName }}</span>
      </el-form-item>
      <el-form-item :label="t('keyBrowse.rename.newKey')" required>
        <el-input
            v-model="newKey"
            :placeholder="t('keyBrowse.rename.newKeyPlaceholder')"
            @keyup.enter="onSubmit"
        />
      </el-form-item>
      <!--
        RENAMENX 语义：目标已存在时后端返回 40001「目标 key 已存在，拒绝覆盖」。
        这里不做本地存在性预检（那需要额外一次请求且有竞态），
        而是原样展示后端 msg —— 权威且不会给出错误的"应该可以"暗示。
      -->
      <el-alert
          v-if="conflict"
          class="rename-warn"
          :title="t('keyBrowse.rename.conflict')"
          :description="conflictMessage"
          type="warning"
          :closable="false"
          show-icon
      />
      <!-- 新旧同名：后端虽是无操作成功，但提交只会让对话框悬挂，本地直接拦下 -->
      <el-alert
          v-if="sameName"
          class="rename-warn"
          :title="t('keyBrowse.rename.same')"
          type="info"
          :closable="false"
          show-icon
      />
      <el-alert
          v-if="containsSlash"
          class="rename-warn"
          :title="t('keyBrowse.slashKeyWarning')"
          type="info"
          :closable="false"
          show-icon
      />
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :disabled="sameName" @click="onSubmit">
        {{ t('keyBrowse.rename.submit') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'

const {t} = useI18n()

const props = defineProps<{
  modelValue: boolean
  keyName: string
  /**
   * 冲突提示文案（翻译 key 或后端原文）。
   * 由父级持有：改名请求是父级发的，40001「目标 key 已存在」只有父级拿得到，
   * 本组件只负责把传进来的文案显示出来。
   */
  conflictMessage?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', newKey: string): void
}>()

const newKey = ref('')

const conflict = computed(() => (props.conflictMessage ?? '') !== '')

/** 新旧同名：后端是无操作成功，但对话框会因 keyName 不变而无法关闭 —— 本地直接拦截 */
const sameName = computed(() => newKey.value.trim() !== '' && newKey.value.trim() === props.keyName)

/** 含 `/` 的 key 走 %2F 编码，可能被容器层拒绝 —— 提前提示 */
const containsSlash = computed(() => newKey.value.includes('/'))

watch(
    () => props.modelValue,
    (open) => {
      if (!open) return
      newKey.value = ''
    },
    {immediate: true},
)

const onSubmit = () => {
  const target = newKey.value.trim()
  if (target === '') return
  emit('submit', target)
}
</script>

<style scoped>
.current-key {
  font-family: monospace;
  word-break: break-all;
}

.rename-warn {
  margin-top: 4px;
}
</style>