<template>
  <div :class="{ 'is-collapsed': collapsed }" class="profile-switcher">
    <!--
      展开态：实例下拉 + 状态圆点（绿 CONNECTED / 红 OFFLINE）。
      折叠态(aside 64px)：退化为图标按钮，点击展开侧栏再选（64px 放不下下拉面板）。
    -->
    <template v-if="!collapsed">
      <div v-if="profiles.length > 0" class="switcher-select">
        <el-select
            :model-value="activeProfileId ?? undefined"
            :placeholder="t('app.header.selectProfile')"
            size="small"
            @change="onSelect"
        >
          <el-option
              v-for="p in profiles"
              :key="p.id"
              :label="p.name"
              :value="p.id"
          >
            <span class="switcher-option">
              <span :style="{ background: dotColor(p) }" class="state-dot"/>
              <span class="option-name">{{ p.name }}</span>
              <span :class="`state-${p.profileState.toLowerCase()}`" class="option-state">
                {{ stateText(p.profileState) }}
              </span>
            </span>
          </el-option>
        </el-select>
        <el-button
            :icon="Setting"
            class="manage-btn"
            size="small"
            text
            @click="goManage"
        >
          {{ t('profileManage.title') }}
        </el-button>
      </div>

      <!-- 零连接引导：不显示下拉，直接给"添加连接"入口；首拉未完成时先给加载态 -->
      <div v-else-if="!profilesLoaded" class="switcher-empty">
        <span class="loading-hint">{{ t('common.loading') }}</span>
      </div>
      <div v-else class="switcher-empty">
        <el-button size="small" type="primary" @click="goManage">{{ t('profileManage.add') }}</el-button>
      </div>
    </template>

    <!-- 折叠态：当前实例状态色圆点 + 点击展开侧栏 -->
    <el-tooltip
        v-else
        :content="activeProfile ? activeProfile.name : t('app.header.selectProfile')"
        placement="right"
    >
      <button
          :aria-label="activeProfile ? activeProfile.name : t('app.header.selectProfile')"
          class="collapsed-btn"
          type="button"
          @click="$emit('expand')"
      >
        <span :style="{ background: dotColor(activeProfile) }" class="state-dot"/>
        <el-icon>
          <Connection/>
        </el-icon>
      </button>
    </el-tooltip>
  </div>
</template>

<script lang="ts" setup>
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {Connection, Setting} from '@element-plus/icons-vue'
import type {ProfileState, ProfileVO} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'

const {t} = useI18n()

/**
 * 侧栏顶部实例切换器。
 * 每项带连接状态圆点：绿 CONNECTED / 红 OFFLINE。
 * 切换只更新全局 activeProfileId（并由 setActiveProfile 重置 activeDb），
 * 当前路由不变，各视图 watch 后自行重取。
 */
withDefaults(defineProps<{ collapsed?: boolean }>(), {collapsed: false})

defineEmits<{ expand: [] }>()

const router = useRouter()
const {profiles, profilesLoaded, activeProfileId, activeProfile, setActiveProfile} =
    injectGlobalState()

const onSelect = (id: number) => {
  if (id != null) setActiveProfile(id)
}

const goManage = () => {
  router.push('/profiles')
}

/** 状态圆点配色：仅 CONNECTED / OFFLINE 两态（无 CONNECTING 中间态） */
const dotColor = (p?: ProfileVO | null) => {
  if (!p) return 'var(--el-text-color-disabled)'
  return p.profileState === 'CONNECTED' ? 'var(--el-color-success)' : 'var(--el-color-danger)'
}

const stateText = (s: ProfileState) => (s === 'CONNECTED' ? t('common.connected') : t('common.offline'))
</script>

<style scoped>
.profile-switcher {
  padding: 10px 10px 8px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.switcher-select {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.switcher-select :deep(.el-select) {
  width: 100%;
}

.manage-btn {
  width: 100%;
  justify-content: flex-start;
  padding-left: 4px;
}

.switcher-empty {
  text-align: center;
}

.loading-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.switcher-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.option-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.option-state {
  font-size: 12px;
}

.state-connected {
  color: var(--el-color-success);
}

.state-offline {
  color: var(--el-text-color-secondary);
}

/* 状态圆点：10px 圆，状态色由内联 style 提供 */
.state-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* 折叠态图标按钮：与 el-menu 折叠项的尺寸观感对齐 */
.collapsed-btn {
  width: 100%;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: none;
  background: transparent;
  color: var(--el-text-color-regular);
  cursor: pointer;
  border-radius: 4px;
}

.collapsed-btn:hover {
  background: var(--el-fill-color);
}
</style>