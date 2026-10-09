<template>
  <!--
    启动诊断全屏面板：握手等待期（桌面模式 Spring Boot 拉起需 5–30s）可视化。
    只负责"等"与"说明"——握手结束（成功或超时）后由 App.vue 卸载本组件。
  -->
  <div class="startup">
    <div class="startup-card">
      <h3 class="startup-title">{{ t('app.startup.title') }}</h3>
      <p class="startup-wait">
        <span class="spinner"/>
        {{ t('app.startup.waiting') }}
      </p>
      <div class="startup-section">{{ t('app.startup.frontendLog') }}</div>
      <!-- 非响应式数组 bootLogs：按 500ms 轮询触发重渲染 -->
      <pre class="startup-log">{{ logText }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {useI18n} from 'vue-i18n'
import {bootLogs} from '../composables/bootLog'

const {t} = useI18n()

/** 递增 tick 驱动 bootLogs（非响应式数组）重渲染 */
const tick = ref(0)
let timer: number | null = null

onMounted(() => {
  timer = window.setInterval(() => {
    tick.value++
  }, 500)
})

onBeforeUnmount(() => {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
})

const logText = computed(() => {
  // 依赖 tick 以触发重算（bootLogs 本身不是 reactive）
  void tick.value
  return bootLogs.length > 0 ? bootLogs.join('\n') : t('common.loading')
})
</script>

<style scoped>
.startup {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color, #fff);
}

.startup-card {
  width: min(680px, 92vw);
}

.startup-title {
  margin: 0 0 12px;
  font-size: 16px;
}

.startup-wait {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--el-border-color);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: startup-spin 0.9s linear infinite;
}

@keyframes startup-spin {
  to {
    transform: rotate(360deg);
  }
}

.startup-section {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.startup-log {
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  padding: 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
}
</style>