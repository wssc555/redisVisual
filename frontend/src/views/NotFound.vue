<template>
  <div class="not-found">
    <el-result icon="warning" :title="t('settings.notFound.title')" :sub-title="t('settings.notFound.text')">
      <template #extra>
        <el-button type="primary" @click="router.push('/')">
          {{ t('settings.notFound.backHome') }}
        </el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'

const {t} = useI18n()
const router = useRouter()
</script>

<style scoped>
.not-found {
  padding: 48px 0;
}
</style>