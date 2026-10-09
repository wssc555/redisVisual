<template>
  <el-dialog
      :model-value="modelValue"
      :title="editing ? t('profileManage.edit') : t('profileManage.add')"
      width="620px"
      top="6vh"
      :close-on-click-modal="false"
      @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="130px" @submit.prevent>
      <el-form-item :label="t('profileManage.form.name')" required>
        <el-input
            v-model="form.name"
            :placeholder="t('profileManage.form.namePlaceholder')"
        />
      </el-form-item>

      <el-form-item :label="t('profileManage.form.mode')" required>
        <el-radio-group v-model="form.mode">
          <el-radio-button
              v-for="opt in MODE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
          >
            {{ t(opt.labelKey) }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>

      <!-- STANDALONE：host + port -->
      <template v-if="form.mode === 'STANDALONE'">
        <el-form-item :label="t('profileManage.form.host')" required>
          <el-input v-model="form.host" placeholder="127.0.0.1"/>
        </el-form-item>
        <el-form-item :label="t('profileManage.form.port')" required>
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right"/>
        </el-form-item>
      </template>

      <!-- CLUSTER：节点列表（多行） -->
      <el-form-item v-if="form.mode === 'CLUSTER'" :label="t('profileManage.form.nodes')" required>
        <div class="node-list">
          <div v-for="(row, i) in form.nodes" :key="i" class="node-row">
            <el-input v-model="row.host" placeholder="127.0.0.1" class="node-host"/>
            <el-input-number
                v-model="row.port"
                :min="1"
                :max="65535"
                :placeholder="t('profileManage.form.port')"
                controls-position="right"
                class="node-port"
            />
            <el-button
                :icon="Delete"
                :title="t('profileManage.form.removeNode')"
                text
                @click="removeNode(form.nodes, i)"
            />
          </div>
          <el-button :icon="Plus" size="small" text @click="addNode(form.nodes)">
            {{ t('profileManage.form.addNode') }}
          </el-button>
        </div>
      </el-form-item>

      <!-- SENTINEL：哨兵节点列表 + 主节点名 + 独立哨兵密码 -->
      <template v-if="form.mode === 'SENTINEL'">
        <el-form-item :label="t('profileManage.form.sentinels')" required>
          <div class="node-list">
            <div v-for="(row, i) in form.sentinels" :key="i" class="node-row">
              <el-input v-model="row.host" placeholder="127.0.0.1" class="node-host"/>
              <el-input-number
                  v-model="row.port"
                  :min="1"
                  :max="65535"
                  :placeholder="t('profileManage.form.port')"
                  controls-position="right"
                  class="node-port"
              />
              <el-button
                  :icon="Delete"
                  :title="t('profileManage.form.removeNode')"
                  text
                  @click="removeNode(form.sentinels, i)"
              />
            </div>
            <el-button :icon="Plus" size="small" text @click="addNode(form.sentinels)">
              {{ t('profileManage.form.addNode') }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item :label="t('profileManage.form.masterName')" required>
          <el-input v-model="form.masterName" placeholder="mymaster"/>
        </el-form-item>
      </template>

      <el-form-item :label="t('profileManage.form.database')">
        <el-input-number v-model="form.database" :min="0" :max="15" controls-position="right"/>
        <span v-if="form.mode === 'CLUSTER'" class="form-hint">{{ t('common.clusterDbFixed') }}</span>
      </el-form-item>

      <el-form-item :label="t('profileManage.form.username')">
        <el-input v-model="form.username" autocomplete="off"/>
      </el-form-item>

      <!--
        密码三态（关键）：
        - 未配置过 → 占位"未配置"；
        - 已配置且未标记清空 → 占位"已配置，留空保持不变"，**不显示任何真实值**；
        - 点"清空" → 标记 clearedSecrets，输入框禁用并清空。
      -->
      <el-form-item :label="t('profileManage.form.password')">
        <div class="secret-row">
          <el-input
              v-model="form.password"
              :placeholder="secretPlaceholder(credentialPresence === 'PRESENT')"
              :disabled="isCleared(form, 'password')"
              autocomplete="new-password"
              show-password
              type="password"
          />
          <el-button
              v-if="credentialPresence === 'PRESENT'"
              size="small"
              text
              @click="toggleCleared(form, 'password')"
          >
            {{
              isCleared(form, 'password')
                  ? t('profileManage.form.cancelClear')
                  : t('profileManage.form.secretClear')
            }}
          </el-button>
        </div>
        <span v-if="isCleared(form, 'password')" class="form-hint form-hint--danger">
          {{ t('profileManage.form.secretCleared') }}
        </span>
      </el-form-item>

      <el-form-item v-if="form.mode === 'SENTINEL'" :label="t('profileManage.form.sentinelPassword')">
        <div class="secret-row">
          <el-input
              v-model="form.sentinelPassword"
              :placeholder="secretPlaceholder(sentinelCredentialPresence === 'PRESENT')"
              :disabled="isCleared(form, 'sentinelPassword')"
              autocomplete="new-password"
              show-password
              type="password"
          />
          <el-button
              v-if="sentinelCredentialPresence === 'PRESENT'"
              size="small"
              text
              @click="toggleCleared(form, 'sentinelPassword')"
          >
            {{
              isCleared(form, 'sentinelPassword')
                  ? t('profileManage.form.cancelClear')
                  : t('profileManage.form.secretClear')
            }}
          </el-button>
        </div>
        <span class="form-hint">{{ t('profileManage.form.sentinelPasswordHint') }}</span>
        <span v-if="isCleared(form, 'sentinelPassword')" class="form-hint form-hint--danger">
          {{ t('profileManage.form.secretCleared') }}
        </span>
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="dialog-footer">
        <!-- 测连：不弹错误码 toast，只看 data.reachable -->
        <el-button :loading="testing" @click="onTest">{{ t('profileManage.table.test') }}</el-button>
        <div class="footer-right">
          <el-button @click="emit('update:modelValue', false)">
            {{ t('profileManage.form.cancel') }}
          </el-button>
          <el-button type="primary" :loading="submitting" @click="onSubmit">
            {{ t('profileManage.form.submit') }}
          </el-button>
        </div>
      </div>
      <!--
        测连结果区：reachable=false 不是错误码，是 data 里的正常字段。
        因此用 el-alert 承载（warning 而非 error），不弹 toast。
      -->
      <el-alert
          v-if="testResult"
          class="test-result"
          :title="testTitle"
          :description="testDescription"
          :type="testResult.reachable ? 'success' : 'warning'"
          :closable="false"
          show-icon
      />
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {Delete, Plus} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {type ProfileUpsertPayload, validateProfile, type ValidateResultVO} from '../api'
import {
  buildProfilePayload,
  buildValidatePayload,
  defaultProfileForm,
  formFromProfile,
  isCleared,
  MODE_OPTIONS,
  type NodeRow,
  type ProfileForm,
  secretPlaceholder,
  toggleCleared,
  validateProfileForm,
} from '../views/profileForm'

const {t} = useI18n()

const props = defineProps<{
  modelValue: boolean
  /** 编辑时传入；不传即新建 */
  profile?: import('../api').ProfileVO | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', payload: ProfileUpsertPayload): void
}>()

const editing = computed(() => props.profile != null && props.profile.id != null)

/** 后端回的凭据存在性标记（决定密码框占位文案） */
const credentialPresence = computed(() => props.profile?.credentialPresence)
const sentinelCredentialPresence = computed(() => props.profile?.sentinelCredentialPresence)

const form = ref<ProfileForm>(defaultProfileForm())
const submitting = ref(false)
const testing = ref(false)
const testResult = ref<ValidateResultVO | null>(null)

// 每次打开对话框都重置为干净表单：沿用上次的输入是隐蔽的错误来源
watch(
    () => [props.modelValue, props.profile] as const,
    ([open]) => {
      if (!open) return
      form.value = props.profile ? formFromProfile(props.profile) : defaultProfileForm()
      testResult.value = null
    },
    {immediate: true},
)

const addNode = (rows: NodeRow[]) => {
  rows.push({host: '', port: null})
}

/** 至少保留一行空的输入框，否则用户会看到一个无法添加的空列表 */
const removeNode = (rows: NodeRow[], index: number) => {
  rows.splice(index, 1)
  if (rows.length === 0) rows.push({host: '', port: null})
}

const testTitle = computed(() =>
    testResult.value?.reachable ? t('profileManage.validate.success') : t('profileManage.validate.failed'),
)

const testDescription = computed(() => {
  const r = testResult.value
  if (!r) return ''
  if (!r.reachable) return r.error || t('profileManage.validate.unreachable')
  return [
    t('profileManage.validate.latency', {ms: r.latencyMs}),
    t('profileManage.validate.nodes', {n: r.nodeCount}),
  ].join(' · ')
})

/** 结构化消息 → i18n 渲染 */
const renderMessage = (m: { key: string; params?: Record<string, string | number> }) =>
    m.params ? t(m.key, m.params as any) : t(m.key)

const precheck = (): boolean => {
  const errors = validateProfileForm(form.value)
  if (errors.length > 0) {
    ElMessage.error(errors.map(renderMessage).join('\n'))
    return false
  }
  return true
}

const onTest = async () => {
  if (!precheck()) return
  testing.value = true
  testResult.value = null
  try {
    const payload = buildValidatePayload(form.value, editing.value, props.profile?.id)
    const res = await validateProfile(payload)
    // 恒 code=0：不可达通过 reachable=false 表达，不进 catch
    testResult.value = res.data ?? null
  } catch (e: any) {
    // 只有网络层/参数校验类错误才会到这里（如 40001 表单未填全）
    ElMessage.error(`${t('profileManage.validate.failed')}: ${e.message}`)
  } finally {
    testing.value = false
  }
}

const onSubmit = async () => {
  if (!precheck()) return
  submitting.value = true
  try {
    const payload = buildProfilePayload(form.value)
    emit('saved', payload)
    emit('update:modelValue', false)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.node-list {
  width: 100%;
}

.node-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.node-host {
  flex: 1;
}

.node-port {
  width: 130px;
}

.secret-row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}

.form-hint {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 2px;
}

.form-hint--danger {
  color: var(--el-color-danger);
}

.dialog-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.footer-right {
  display: flex;
  gap: 8px;
}

.test-result {
  margin-top: 12px;
}
</style>