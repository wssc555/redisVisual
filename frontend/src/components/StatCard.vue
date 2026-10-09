<template>
  <el-card
      :class="{ 'stat-card--link': !!to }"
      :role="to ? 'link' : undefined"
      class="stat-card"
      shadow="never"
      @click="go"
  >
    <div class="stat-label">{{ label }}</div>
    <div class="stat-value" :class="valueClass">{{ displayValue }}</div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
  </el-card>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {useRouter} from 'vue-router'
import {formatBytes, formatNumber} from '../composables/useFormat'

const props = defineProps<{
  /** 卡片标题 */
  label: string
  /** 原始数值；null/undefined 显示为破折号（语义：尚无采样） */
  value: number | string | null | undefined
  /** 语义色：正常 info / 告警 warning / 危险 danger / 健康 success */
  type?: 'info' | 'success' | 'warning' | 'danger'
  /** 数值下方补充说明 */
  hint?: string
  /** 传入时按字节数格式化 */
  bytes?: boolean
  /** 传入时整卡可点击并跳转到该路由 */
  to?: string
}>()

const router = useRouter()

const go = () => {
  if (props.to) router.push(props.to)
}

const valueClass = computed(() => `stat-value--${props.type || 'info'}`)

const displayValue = computed(() => {
  const v = props.value
  // null 与 0 语义不同：前者是"尚无采样"，必须显示破折号而不是 0
  if (v === null || v === undefined) return '-'
  if (props.bytes) {
    const n = Number(v)
    return Number.isNaN(n) ? String(v) : formatBytes(n)
  }
  return typeof v === 'number' ? formatNumber(v) : v
})
</script>

<style scoped>
.stat-card {
  height: 100%;
}

.stat-card--link {
  cursor: pointer;
  transition: border-color 0.2s;
}

.stat-card--link:hover {
  border-color: var(--el-color-primary);
}

.stat-card--link:active .stat-value {
  color: var(--el-color-primary);
}

.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  word-break: break-all;
}

.stat-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
}

.stat-value--info {
  color: var(--el-text-color-primary);
}

.stat-value--success {
  color: var(--el-color-success);
}

.stat-value--warning {
  color: var(--el-color-warning);
}

.stat-value--danger {
  color: var(--el-color-danger);
}
</style>