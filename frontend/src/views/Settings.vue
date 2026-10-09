<template>
  <div class="settings">
    <h3 class="page-title">{{ t('settings.title') }}</h3>

    <el-card class="section" shadow="never">
      <template #header>{{ t('settings.general') }}</template>
      <el-form label-width="160px">
        <el-form-item :label="t('settings.language')">
          <el-radio-group :model-value="currentLocale" @change="onLanguageChange">
            <el-radio-button
                v-for="lang in LANGUAGES"
                :key="lang.value"
                :value="lang.value"
            >
              {{ lang.label }}
            </el-radio-button>
          </el-radio-group>
          <div class="hint">{{ t('settings.languageHint') }}</div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="section" shadow="never">
      <template #header>{{ t('settings.preferences') }}</template>
      <el-form label-width="160px">
        <el-form-item :label="t('app.header.collapseNav')">
          <el-switch
              :model-value="asideCollapsed"
              @change="onAsideCollapsedChange"
          />
        </el-form-item>
        <div class="hint">{{ t('settings.localOnlyHint') }}</div>
      </el-form>
    </el-card>

    <el-card class="section" shadow="never">
      <template #header>{{ t('settings.about') }}</template>
      <el-form label-width="160px">
        <el-form-item :label="t('settings.aboutBackend')">
          <div>
            {{ t('app.title') }}
            <div class="hint">{{ t('settings.aboutBackendHint') }}</div>
          </div>
        </el-form-item>
        <el-form-item :label="t('settings.aboutSecurity')">
          <el-alert
              class="security"
              :title="t('settings.aboutSecurityText')"
              type="warning"
              :closable="false"
              show-icon
          />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {useI18n} from 'vue-i18n'
import {currentLocale, LANGUAGES, type LocaleId, setLanguage} from '../i18n'
import {STORAGE_KEYS} from '../composables/useGlobalState'

const {t} = useI18n()

/**
 * 偏好走 localStorage（**后端无 preferences 端点**，不为它扩后端）。
 * 侧栏折叠态与 App.vue 共用同一个键，故两处切换互相生效。
 *
 * 用 ref 而非 computed(localStorage)：localStorage 不是响应式源，
 * computed 缓存一次后不会因外部写入而更新 —— 显式 ref + 赋值才可靠。
 */
const readCollapsed = (): boolean => {
  try {
    return window.localStorage.getItem(STORAGE_KEYS.asideCollapsed) === '1'
  } catch {
    return false
  }
}

const asideCollapsed = ref(readCollapsed())

const onLanguageChange = (value: string | number | boolean) => {
  setLanguage(value as LocaleId)
}

const onAsideCollapsedChange = (value: string | number | boolean) => {
  const collapsed = value === true
  try {
    window.localStorage.setItem(STORAGE_KEYS.asideCollapsed, collapsed ? '1' : '0')
  } catch {
    // 无 localStorage（隐私模式/node 测试）时忽略
  }
  asideCollapsed.value = collapsed
}
</script>

<style scoped>
.page-title {
  margin: 0 0 12px;
  font-size: 16px;
}

.section {
  margin-bottom: 12px;
  max-width: 760px;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
}

.security {
  max-width: 520px;
}
</style>