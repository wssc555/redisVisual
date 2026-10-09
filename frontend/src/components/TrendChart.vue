<template>
  <!--
    echarts 折线图（按需注册 LineChart/Grid/Tooltip，避免全量引入）。
    **数据源是后端内存环形缓冲，重启即清零** —— 所以空态是正常状态而非错误，
    文案必须说清"采样中"，否则用户会以为是 bug。
  -->
  <div class="trend-chart">
    <div class="chart-head">
      <span class="chart-title">{{ title }}</span>
      <!-- prop 不可写，不能 v-model：单向绑定 + emit 更新（父组件 v-model:window-seconds 接收） -->
      <el-radio-group
          :model-value="windowSeconds"
          size="small"
          @update:model-value="(v: string | number | boolean | undefined) => emit('update:windowSeconds', Number(v))"
      >
        <el-radio-button :value="60">{{ t('dashboard.trend.seconds', {n: 60}) }}</el-radio-button>
        <el-radio-button :value="300">{{ t('dashboard.trend.seconds', {n: 300}) }}</el-radio-button>
        <el-radio-button :value="900">{{ t('dashboard.trend.seconds', {n: 900}) }}</el-radio-button>
      </el-radio-group>
    </div>
    <div v-if="series.length === 0" class="chart-empty">{{ t('dashboard.trend.empty') }}</div>
    <div v-else ref="chartRef" class="chart-canvas"/>
  </div>
</template>

<script setup lang="ts">
import {nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import * as echarts from 'echarts/core'
import {LineChart} from 'echarts/charts'
import {GridComponent, TooltipComponent} from 'echarts/components'
import {CanvasRenderer} from 'echarts/renderers'
import {formatNumber} from '../composables/useFormat'
import type {TrendSeries} from './trendTypes'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const {t} = useI18n()

const props = defineProps<{
  title: string
  /** 时间轴（ISO 字符串） */
  timestamps: string[]
  series: TrendSeries[]
  /** 字节型数值走字节格式化（内存趋势用；负载趋势为纯数字） */
  bytes?: boolean
  windowSeconds: number
}>()

const emit = defineEmits<{ (e: 'update:windowSeconds', v: number): void }>()

const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const formatValue = (v: number): string => (props.bytes ? formatBytesShort(v) : formatNumber(v))

const formatBytesShort = (v: number): string => {
  if (!Number.isFinite(v)) return '-'
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  if (v < 1024 * 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)} MB`
  return `${(v / 1024 / 1024 / 1024).toFixed(2)} GB`
}

const render = () => {
  if (!chart || props.series.length === 0) return
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      // 主题跟随 Element Plus：靠 CSS 变量取值，浅色主题下文字可读
      backgroundColor: 'var(--el-bg-color-overlay, #fff)',
      borderColor: 'var(--el-border-color-light, #e4e7ed)',
      textStyle: {color: 'var(--el-text-color-primary, #303133)', fontSize: 12},
      axisPointer: {type: 'line'},
    },
    grid: {left: 8, right: 16, top: 28, bottom: 8, containLabel: true},
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: props.timestamps,
      axisLabel: {
        color: 'var(--el-text-color-secondary, #909399)',
        fontSize: 11,
        formatter: (v: string) => (v ? v.slice(11, 19) : ''),
      },
      axisLine: {lineStyle: {color: 'var(--el-border-color, #dcdfe6)'}},
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        color: 'var(--el-text-color-secondary, #909399)',
        fontSize: 11,
        formatter: (v: number) => formatValue(v),
      },
      splitLine: {lineStyle: {color: 'var(--el-border-color-lighter, #ebeef5)'}},
    },
    series: props.series.map((s) => ({
      name: s.name,
      type: 'line' as const,
      smooth: true,
      showSymbol: false,
      data: s.values,
    })),
  })
}

onMounted(async () => {
  await nextTick()
  if (chartRef.value && props.series.length > 0) {
    chart = echarts.init(chartRef.value)
    render()
  }
  // 窄档抽屉/侧栏宽度变化会影响容器尺寸
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})

function onResize() {
  chart?.resize()
}

watch(
    () => [props.series, props.timestamps],
    async () => {
      await nextTick()
      // 空态时销毁实例：容器不存在时 init 会拿到 0 宽高
      if (props.series.length === 0) {
        chart?.dispose()
        chart = null
        return
      }
      if (!chart && chartRef.value) {
        chart = echarts.init(chartRef.value)
      }
      render()
    },
    {deep: true},
)
</script>

<style scoped>
.trend-chart {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chart-title {
  font-weight: 500;
}

.chart-empty {
  padding: 32px;
  text-align: center;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.chart-canvas {
  height: 260px;
  width: 100%;
}
</style>