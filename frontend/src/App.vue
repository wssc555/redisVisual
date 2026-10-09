<template>
  <el-config-provider :locale="epLocale">
    <el-container style="height: 100vh">
      <!--
        壳层三档:
        wide(≥1440) 侧栏展开 220px / mid(1024–1439) 默认折叠 64px / narrow(<1024) 覆盖式抽屉。
        窄档不销毁侧栏 DOM，而是整体移出屏幕、经 .app-aside--drawer 覆盖在内容上
        （单份菜单标记，避免 el-drawer 与 el-aside 两处模板漂移）。
      -->
      <el-aside
          :class="{ 'app-aside--drawer': isNarrow, 'app-aside--open': isNarrow && drawerOpen }"
          :width="asideWidth"
          class="app-aside"
      >
        <div class="aside-inner">
          <ProfileSwitcher :collapsed="!isNarrow && collapsed" @expand="collapsed = false"/>
          <el-menu
              :collapse="!isNarrow && collapsed"
              :default-active="currentRoute"
              class="aside-menu"
              router
          >
            <el-menu-item index="/">
              <el-icon>
                <DataLine/>
              </el-icon>
              <span>{{ t('app.menu.dashboard') }}</span>
            </el-menu-item>
            <el-menu-item index="/keys">
              <el-icon>
                <Grid/>
              </el-icon>
              <span>{{ t('app.menu.keys') }}</span>
            </el-menu-item>
            <el-menu-item index="/monitor">
              <el-icon>
                <Monitor/>
              </el-icon>
              <span>{{ t('app.menu.monitor') }}</span>
            </el-menu-item>
            <!-- 操作日志不依赖激活实例（可查已删实例的历史），故常驻显示 -->
            <el-menu-item index="/history">
              <el-icon>
                <Tickets/>
              </el-icon>
              <span>{{ t('app.menu.history') }}</span>
            </el-menu-item>
            <el-menu-item index="/profiles">
              <el-icon>
                <Connection/>
              </el-icon>
              <span>{{ t('app.menu.profileManage') }}</span>
            </el-menu-item>
            <el-menu-item index="/settings">
              <el-icon>
                <Tools/>
              </el-icon>
              <span>{{ t('app.menu.settings') }}</span>
            </el-menu-item>
          </el-menu>
          <!-- 收起/展开导航：仅宽/中档显示（窄档抽屉由遮罩/汉堡键收起）；折叠态仅图标 -->
          <div v-if="!isNarrow" class="aside-footer">
            <el-button
                :aria-label="collapsed ? t('app.header.expandNav') : t('app.header.collapseNav')"
                :icon="collapsed ? Expand : Fold"
                :title="collapsed ? t('app.header.expandNav') : t('app.header.collapseNav')"
                style="width: 100%"
                text
                @click="collapsed = !collapsed"
            />
          </div>
        </div>
      </el-aside>

      <el-container>
        <el-header class="app-header">
          <!-- 窄档汉堡键：唤出覆盖抽屉 -->
          <el-button
              v-if="isNarrow"
              :aria-label="t('app.header.openNav')"
              :icon="MenuIcon"
              class="hamburger"
              text
              @click="drawerOpen = true"
          />
          <h2 class="app-title">Redis Visualizer</h2>
          <span v-if="activeProfile" class="active-profile">
          <span :style="{ background: dotColor }" class="state-dot"/>
          {{ activeProfile.name }}
        </span>
          <span v-else-if="profilesLoaded" class="active-profile active-profile--none">
          {{ t('app.header.noProfile') }}
        </span>
          <!-- 语言切换：下拉即切，localStorage 持久化 -->
          <el-dropdown class="lang-switch" trigger="click" @command="onLanguageCommand">
            <el-button :aria-label="t('app.header.language')" text>
              {{ currentLanguageLabel }}
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                    v-for="lang in LANGUAGES"
                    :key="lang.value"
                    :command="lang.value"
                >
                  {{ lang.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-header>
        <el-main>
          <!-- 握手等待期(booting)不渲染任何页面组件：避免 baseURL 未定时发起请求风暴 -->
          <router-view v-if="bootStatus.ready"/>
        </el-main>
      </el-container>

      <!-- 窄档抽屉遮罩：点击关闭（路由切换也会自动收起） -->
      <div v-if="isNarrow && drawerOpen" class="aside-mask" @click="drawerOpen = false"/>

      <!-- 启动诊断面板：握手等待期全屏显示后端日志流 + 前端事件，结束后自动卸载 -->
      <StartupDiagnostics v-if="!bootStatus.ready"/>

      <!-- 首次启动引导：零连接时弹出，引导进入连接配置 -->
      <FirstRunGuide
          v-model="firstRunGuideVisible"
          @add="handleGuideAdd"
          @later="handleGuideLater"
      />
    </el-container>
  </el-config-provider>
</template>

<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref, watch, watchEffect} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {
  Connection,
  DataLine,
  Expand,
  Fold,
  Grid,
  Menu as MenuIcon,
  Monitor,
  Tickets,
  Tools,
} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import ProfileSwitcher from './components/ProfileSwitcher.vue'
import FirstRunGuide from './components/FirstRunGuide.vue'
import StartupDiagnostics from './components/StartupDiagnostics.vue'
import {bootReady, bootStatus} from './composables/bootLog'
import {ensureActiveProfile, STORAGE_KEYS, useGlobalState,} from './composables/useGlobalState'
import {useResponsive} from './composables/useResponsive'
import {currentLocale, epLocale, LANGUAGES, type LocaleId, setLanguage} from './i18n'
import './styles/responsive.css'

const route = useRoute()
const router = useRouter()
const {t} = useI18n()
const currentRoute = computed(() => route.path)

// ---- 语言切换 ----
const currentLanguageLabel = computed(
    () => LANGUAGES.find((l) => l.value === currentLocale.value)?.label ?? '',
)
const onLanguageCommand = (cmd: string | number | object) => setLanguage(cmd as LocaleId)

const {tier, isNarrow} = useResponsive()

// 全局单例：由 App 创建并 provide，其余组件通过 injectGlobalState() 读取
const state = useGlobalState()
const {
  profiles,
  profilesLoaded,
  activeProfile,
  loadProfiles,
  preferences,
  savePreference,
} = state

/** 侧边导航折叠态：true = 仅图标（宽/中档有效；窄档走抽屉，不折叠） */
const collapsed = ref(false)
/** 窄档覆盖抽屉开关 */
const drawerOpen = ref(false)
/** 偏好恢复完成前不回写 asideCollapsed，避免启动时一次无意义的写 */
let preferenceRestored = false

const asideWidth = computed(() => {
  if (isNarrow.value) return '220px'
  return collapsed.value ? '64px' : '220px'
})

const dotColor = computed(() => {
  const p = activeProfile.value
  if (!p) return 'var(--el-text-color-disabled)'
  return p.profileState === 'CONNECTED' ? 'var(--el-color-success)' : 'var(--el-color-danger)'
})

// ---- 启动时序：loadProfiles → 恢复 activeProfileId（内含 activeDb 恢复）→ 折叠态偏好 ----
onMounted(async () => {
  // 等待 sidecar 握手结束（成功或超时）再走启动序列：
  // booting 期间 baseURL 未定，提前 loadProfiles 只会产生一批注定失败的请求。
  await bootReady

  try {
    await loadProfiles()
  } catch (e: any) {
    ElMessage.error(t('app.loadProfilesFailed', {msg: e.message}))
  }

  // 折叠态偏好：显式设置过才生效；未设置时 mid 档默认折叠
  const saved = preferences.value[STORAGE_KEYS.asideCollapsed]
  if (saved === '1') collapsed.value = true
  else if (saved === '0') collapsed.value = false
  else collapsed.value = tier.value === 'mid'
  preferenceRestored = true

  // 首次启动且无保存的激活实例 → 自动选第一个，避免功能页全部空转
  ensureActiveProfile(state)

  // 首次启动引导：连接列表为空 = 从未添加过连接。
  // 只在 loadProfiles 成功后判断 —— 失败时无法区分「零连接」与「后端不可达」，
  // 此时引导弹框没有意义（后端不可达的错误由 main.ts 的启动错误弹框负责）。
  if (profiles.value.length === 0 && !firstRunGuideDismissed.value) {
    firstRunGuideVisible.value = true
  }
})

// ---- 首次启动引导 ----
const firstRunGuideVisible = ref(false)
/** 本次会话内用户已明确处理过引导（稍后再说/已跳转），不再重复弹出 */
const firstRunGuideDismissed = ref(false)

const handleGuideAdd = () => {
  firstRunGuideVisible.value = false
  firstRunGuideDismissed.value = true
  router.push({path: '/profiles', query: {new: '1'}})
}

const handleGuideLater = () => {
  firstRunGuideVisible.value = false
  firstRunGuideDismissed.value = true
  // 留在当前页；零连接重定向会把用户带到连接配置页，从那里也能随时添加
}

/** 折叠态持久化 */
watch(collapsed, (v) => {
  if (preferenceRestored) savePreference(STORAGE_KEYS.asideCollapsed, v ? '1' : '0')
})

// 连接列表变化时兜底激活实例（被删 / 首次加载）
watch(profiles, () => ensureActiveProfile(state))

// 零连接引导：除连接配置页外全部重定向
watchEffect(() => {
  if (profilesLoaded.value && profiles.value.length === 0 && route.path !== '/profiles') {
    router.replace('/profiles')
  }
})

// 窄档路由切换后自动收起抽屉
watch(() => route.path, () => {
  drawerOpen.value = false
})

/**
 * 状态刷新：**没有独立的 status 端点** —— P1 的 profileState 顺带刷新即可。
 * 10s 轮询驱动切换器圆点；页签隐藏时暂停。
 */
let pollTimer: number | null = null

const onVisibilityChange = () => {
  if (!document.hidden && profiles.value.length > 0) {
    loadProfiles().catch(() => {
      // 轮询失败静默：圆点维持上次状态，下一轮再试
    })
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  pollTimer = window.setInterval(() => {
    if (document.hidden || profiles.value.length === 0) return
    loadProfiles().catch(() => {
    })
  }, 10000)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

// 激活实例被删除后，ensureActiveProfile 已兜底切到第一个；此处仅做一次用户提示
// 注：这里**不**监听 activeProfileId 变化提示"实例已删除"——
// watch 无法区分「删除兜底切换」与「用户主动切换」，会让每次切换都误报。
// 删除流程自有确认弹窗与成功提示（ProfileManage.onDelete），无需重复。
</script>

<style>
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.el-header h2 {
  color: #303133;
}

/* 侧边栏宽度切换动画（150-300ms 微交互区间） */
.app-aside {
  transition: width 0.25s ease;
  overflow: hidden;
}

.aside-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.aside-menu {
  flex: 1;
}

.aside-footer {
  border-top: 1px solid var(--el-menu-border-color, #e6e6e6);
  padding: 6px;
}

/* ---- 窄档覆盖式抽屉：侧栏整体滑入覆盖，而不是挤压内容 ---- */
@media (max-width: 1023.98px) {
  .app-aside--drawer {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 1200;
    /* 覆盖模式下不做 width 过渡(display 切换语义)，改做滑入位移 */
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: var(--el-box-shadow-light);
  }

  .app-aside--drawer.app-aside--open {
    transform: translateX(0);
  }
}

.aside-mask {
  position: fixed;
  inset: 0;
  z-index: 1100;
  background: rgba(0, 0, 0, 0.35);
}
</style>

<style scoped>
.app-header {
  border-bottom: 1px solid #dcdfe6;
  display: flex;
  align-items: center;
  padding: 0 20px;
}

/* 窄档标题缩略 */
.app-title {
  margin: 0;
  font-size: 18px;
}

.app-header .hamburger + .app-title {
  font-size: 16px;
}

.active-profile {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.active-profile--none {
  color: var(--el-text-color-placeholder);
}

/* 语言切换按钮（紧随实例状态之后，顶栏最右） */
.lang-switch {
  margin-left: 12px;
  font-size: 13px;
}

.state-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>