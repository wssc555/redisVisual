<template>
  <!--
    首次启动引导：零连接时的全屏遮罩模态框。
    只负责「告知 + 分流」：添加连接 → 跳连接配置页自动打开新建对话框
    (?new=1)；稍后再说 → 关闭本引导。
    show-close=false + 禁点遮罩/ESC 关闭：强制走两个显式按钮，避免误触绕过。
  -->
  <el-dialog
      :model-value="modelValue"
      width="480px"
      align-center
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      :title="t('app.firstRun.title')"
  >
    <div class="guide-body">
      <el-icon :size="44" class="guide-icon">
        <DataLine/>
      </el-icon>
      <p class="guide-title">{{ t('app.firstRun.lead') }}</p>
      <p class="guide-text">{{ t('app.firstRun.text') }}</p>
      <p class="guide-hint">{{ t('app.firstRun.hint') }}</p>
    </div>
    <template #footer>
      <el-button @click="emit('later')">{{ t('common.later') }}</el-button>
      <el-button type="primary" @click="emit('add')">{{ t('app.firstRun.addProfile') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {DataLine} from '@element-plus/icons-vue'
import {useI18n} from 'vue-i18n'

const {t} = useI18n()

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'add'): void
  (e: 'later'): void
}>()
</script>

<style scoped>
.guide-body {
  text-align: center;
  padding: 4px 8px;
}

.guide-icon {
  color: var(--el-color-primary);
  margin-bottom: 12px;
}

.guide-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 500;
}

.guide-text {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.guide-hint {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>