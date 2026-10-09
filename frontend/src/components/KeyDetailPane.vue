<template>
  <div class="detail-pane">
    <div v-if="!keyName" class="detail-empty">{{ t('keyBrowse.detail.noSelection') }}</div>

    <div v-else class="detail-body">
      <el-descriptions :column="isNarrow ? 1 : 2" border size="small">
        <el-descriptions-item :label="t('keyBrowse.detail.key')">
          <span class="mono key-text" :title="keyName">{{ keyName }}</span>
          <el-alert
              v-if="keyName.includes('/')"
              class="slash-hint"
              :title="t('keyBrowse.slashKeyWarning')"
              type="info"
              :closable="false"
          />
        </el-descriptions-item>
        <el-descriptions-item :label="t('keyBrowse.detail.type')">
          <el-tag v-if="detail" size="small">{{ detail.type }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('keyBrowse.detail.size')">
          <span v-if="detail">{{ formatNumber(detail.size) }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('keyBrowse.detail.ttl')">
          <span v-if="detail">{{ detail.ttlFormat }}</span>
        </el-descriptions-item>
        <!--
          encoding / memoryUsageBytes 标了 @JsonInclude(NON_NULL)，
          Redis 版本不支持 MEMORY USAGE 时可能**整体省略** → 按 undefined 消费。
        -->
        <el-descriptions-item :label="t('keyBrowse.detail.encoding')">
          <span v-if="detail?.encoding">{{ detail.encoding }}</span>
          <span v-else class="muted">{{ t('common.dash') }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('keyBrowse.detail.memoryUsage')">
          <span v-if="detail?.memoryUsageBytes != null">
            {{ formatBytes(detail.memoryUsageBytes) }}
          </span>
          <span v-else class="muted">{{ t('common.dash') }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="detail-actions">
        <el-button size="small" :icon="Timer" @click="ttlVisible = true">
          {{ t('keyBrowse.detail.setTtl') }}
        </el-button>
        <el-button size="small" :icon="EditPen" @click="renameVisible = true">
          {{ t('keyBrowse.detail.rename') }}
        </el-button>
        <el-button size="small" :icon="Delete" type="danger" @click="onDelete">
          {{ t('keyBrowse.detail.delete') }}
        </el-button>
      </div>

      <!-- 类型分发：各类型编辑器自管分页语义（互不抽象，见设计 §4） -->
      <div class="type-editor">
        <StringValueEditor
            v-if="detail?.type === 'string'"
            :profile-id="profileId"
            :db="db"
            :key-name="keyName"
            @type-mismatch="emit('typeMismatch')"
            @gone="emit('gone')"
            @refresh="emit('refreshDetail')"
        />
        <ListEditor
            v-else-if="detail?.type === 'list'"
            :profile-id="profileId"
            :db="db"
            :key-name="keyName"
            @type-mismatch="emit('typeMismatch')"
            @gone="emit('gone')"
            @refresh="emit('refreshDetail')"
        />
        <HashEditor
            v-else-if="detail?.type === 'hash'"
            :profile-id="profileId"
            :db="db"
            :key-name="keyName"
            @type-mismatch="emit('typeMismatch')"
            @gone="emit('gone')"
        />
        <SetEditor
            v-else-if="detail?.type === 'set'"
            :profile-id="profileId"
            :db="db"
            :key-name="keyName"
            @type-mismatch="emit('typeMismatch')"
            @gone="emit('gone')"
        />
        <ZSetEditor
            v-else-if="detail?.type === 'zset'"
            :profile-id="profileId"
            :db="db"
            :key-name="keyName"
            @type-mismatch="emit('typeMismatch')"
            @gone="emit('gone')"
        />
        <div v-else-if="detail" class="muted unknown-type">
          {{ t('typeEditors.common.empty') }}
        </div>
      </div>
    </div>

    <TtlDialog
        v-if="keyName"
        v-model="ttlVisible"
        :key-name="keyName"
        :current-ttl="detail?.ttlSeconds"
        @submit="onSetTtl"
    />
    <RenameDialog
        v-if="keyName"
        v-model="renameVisible"
        :conflict-message="renameConflict"
        :key-name="keyName"
        @submit="onRename"
    />
  </div>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {Delete, EditPen, Timer} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {deleteKey, type KeyDetailVO, updateTtl} from '../api'
import RenameDialog from './RenameDialog.vue'
import TtlDialog from './TtlDialog.vue'
import HashEditor from './HashEditor.vue'
import ListEditor from './ListEditor.vue'
import SetEditor from './SetEditor.vue'
import StringValueEditor from './StringValueEditor.vue'
import ZSetEditor from './ZSetEditor.vue'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {formatBytes, formatNumber} from '../composables/useFormat'
import {useResponsive} from '../composables/useResponsive'

const {t} = useI18n()
const {confirm} = useCrudConfirm()
const {isNarrow} = useResponsive()

const props = defineProps<{
  profileId: number
  db: number
  keyName: string | null
  detail: KeyDetailVO | null
  /**
   * 改名冲突原文（后端 40001「目标 key 已存在，拒绝覆盖: xxx」），空串 = 无冲突。
   * 改名请求由父级发起，只有父级拿得到失败响应 —— 故文案由父级持有、prop 下传，
   * 本组件只透传给 RenameDialog 显示（单向数据流，不走事件回传）。
   */
  renameConflict?: string
}>()

const emit = defineEmits<{
  /** key 已删除/过期 → 父级把该行从列表移除 */
  (e: 'gone'): void
  /** 40902 类型变化 → 父级自动重拉 K2 */
  (e: 'typeMismatch'): void
  /** 写操作后需要刷新 key 元信息（size / ttl） */
  (e: 'refreshDetail'): void
  (e: 'renamed', newKey: string): void
}>()

const ttlVisible = ref(false)
const renameVisible = ref(false)

/**
 * 改名成功信号：父级把选中态切到新 key 时 keyName 随之变化 → 收起对话框。
 * 冲突时 keyName 不变、对话框保持打开，RenameDialog 就地显示父级下传的冲突原文。
 * （用户切换选中其它 key 同样会触发收起，属正确行为。）
 */
watch(
    () => props.keyName,
    () => {
      renameVisible.value = false
    },
)

const onSetTtl = async (ttlSeconds: number) => {
  try {
    await updateTtl(props.profileId, props.keyName as string, ttlSeconds, props.db)
    ElMessage.success(t('keyBrowse.ttl.updated'))
    // TTL=0 = 立即删除：key 已不存在，交给父级移除该行而不是重拉（重拉会 40402）
    if (ttlSeconds === 0) {
      emit('gone')
      return
    }
    emit('refreshDetail')
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

/**
 * 改名请求交给父级执行 —— 只有父级同时持有「左栏 key 列表」与「选中态」，
 * 才能在改名成功后同步这两处（后端 PATCH 成功后 data 为空，本组件拿不到新 key 的详情）。
 * 冲突（40001「目标 key 已存在」）由父级写入 renameConflict prop 回流显示，
 * 因此这里**保持对话框打开**，失败时用户可直接改名字重试。
 */
const onRename = (newKey: string) => {
  emit('renamed', newKey)
}

const onDelete = async () => {
  const key = props.keyName as string
  const ok = await confirm(
      t('keyBrowse.delete.confirmBody', {key}),
      t('keyBrowse.delete.confirmTitle'),
      'error',
  )
  if (!ok) return
  try {
    const res = await deleteKey(props.profileId, key, props.db)
    ElMessage.success(t('keyBrowse.delete.deleted', {n: res.data?.deleted ?? 0}))
    emit('gone')
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.detail-pane {
  height: 100%;
}

.detail-empty {
  padding: 48px;
  text-align: center;
  color: var(--el-text-color-placeholder);
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mono {
  font-family: monospace;
}

.key-text {
  word-break: break-all;
}

.muted {
  color: var(--el-text-color-placeholder);
}

.slash-hint {
  margin-top: 6px;
}

.detail-actions {
  display: flex;
  gap: 8px;
}

.type-editor {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 12px;
}

.unknown-type {
  padding: 16px;
  text-align: center;
}
</style>