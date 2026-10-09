import {createRouter, createWebHistory} from 'vue-router'
import {injectGlobalState} from '../composables/useGlobalState'

// 懒加载：首屏只加载仪表盘，其余视图按需拆包（KeyBrowser 交互最重，收益最大）
const Dashboard = () => import('../views/Dashboard.vue')
const ProfileManage = () => import('../views/ProfileManage.vue')
const KeyBrowser = () => import('../views/KeyBrowser.vue')
const ServerMonitor = () => import('../views/ServerMonitor.vue')
const HistoryLog = () => import('../views/HistoryLog.vue')
const Settings = () => import('../views/Settings.vue')

const routes = [
    {path: '/', name: 'Dashboard', component: Dashboard},
    // 零连接时的重定向落点（App.vue 的 watchEffect 会把所有路由改写到这里）
    {path: '/profiles', name: 'ProfileManage', component: ProfileManage},
    {path: '/keys', name: 'KeyBrowser', component: KeyBrowser},
    {path: '/monitor', name: 'ServerMonitor', component: ServerMonitor},
    {path: '/history', name: 'HistoryLog', component: HistoryLog},
    {path: '/settings', name: 'Settings', component: Settings},
    // 未匹配 URL 不再渲染空白页，给出明确的 404 引导
    {
        path: '/:pathMatch(.*)*',
        name: 'NotFound',
        component: () => import('../views/NotFound.vue'),
    },
]

const router = createRouter({
    history: createWebHistory(),
    routes,
})

/**
 * 需要激活实例的页面守卫。
 *
 * /history 刻意**不在此列**：操作日志的 profileId 是查询参数且「不选 = 全部实例」，
 * 不依赖激活实例即可查（含已删实例的历史），故零连接时也能看。
 *
 * profilesLoaded 为 false 时放行：此刻实例列表尚不可知，硬拦会误伤正在加载中的后端。
 */
const NEEDS_PROFILE = ['/keys', '/monitor']
router.beforeEach((to, _from, next) => {
    const state = injectGlobalState()
    if (state.profilesLoaded.value && state.profiles.value.length === 0) {
        next('/profiles')
        return
    }
    if (state.profilesLoaded.value && state.activeProfileId.value == null && NEEDS_PROFILE.includes(to.path)) {
        next('/profiles')
        return
    }
    next()
})

export default router