/** 应用壳层：菜单、顶栏、启动引导、启动诊断。 */
export const zh = {
    title: 'Redis 可视化',
    menu: {
        dashboard: '仪表盘',
        keys: '键空间',
        monitor: '服务器监控',
        history: '操作日志',
        profileManage: '连接配置',
        settings: '设置',
    },
    header: {
        language: '语言',
        noProfile: '未选择实例',
        selectProfile: '选择实例',
        collapseNav: '收起导航',
        expandNav: '展开导航',
        openNav: '打开导航',
    },
    loadProfilesFailed: '加载连接配置失败：{msg}',
    startup: {
        errorTitle: '后端启动失败',
        errorIntro: '桌面模式下后端服务未能就绪，以下是收集到的启动错误：',
        errorLogPath: '完整日志见应用数据目录下的 backend.log',
        notReadyIn60s: '后端服务 60 秒内未就绪（sidecar 未启动或端口握手失败）',
        waiting: '正在等待后端服务就绪…',
        title: '启动诊断',
        frontendLog: '前端事件',
    },
    firstRun: {
        title: '欢迎使用 Redis 可视化',
        lead: '还没有任何连接配置',
        text: '添加一个 Redis 实例（单机 / 集群 / 哨兵）后即可浏览键空间、查看服务器指标与操作日志。',
        hint: '所有连接凭据加密存储于本地数据库，接口永不回传密码明文。',
        addProfile: '添加连接',
    },
}

export type AppSchema = typeof zh

export const en: AppSchema = {
    title: 'Redis Visualizer',
    menu: {
        dashboard: 'Dashboard',
        keys: 'Keyspace',
        monitor: 'Server Monitor',
        history: 'Operation Log',
        profileManage: 'Connections',
        settings: 'Settings',
    },
    header: {
        language: 'Language',
        noProfile: 'No instance selected',
        selectProfile: 'Select instance',
        collapseNav: 'Collapse navigation',
        expandNav: 'Expand navigation',
        openNav: 'Open navigation',
    },
    loadProfilesFailed: 'Failed to load connections: {msg}',
    startup: {
        errorTitle: 'Backend failed to start',
        errorIntro: 'The backend service did not become ready in desktop mode. Collected startup errors:',
        errorLogPath: 'See backend.log in the app data directory for the full log',
        notReadyIn60s: 'Backend was not ready within 60s (sidecar did not start or port handshake failed)',
        waiting: 'Waiting for the backend service…',
        title: 'Startup diagnostics',
        frontendLog: 'Frontend events',
    },
    firstRun: {
        title: 'Welcome to Redis Visualizer',
        lead: 'No connection configured yet',
        text: 'Add a Redis instance (standalone / cluster / sentinel) to browse keys, inspect server metrics and review operation logs.',
        hint: 'Credentials are encrypted at rest; the API never returns passwords in plaintext.',
        addProfile: 'Add connection',
    },
}