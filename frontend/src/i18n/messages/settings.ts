/** 设置页与 404 页。 */
export const zh = {
    title: '设置',
    general: '通用',
    language: '界面语言',
    languageHint: '切换后立即生效并持久化到本地',
    preferences: '界面偏好',
    appearance: '外观',
    /** 偏好存 localStorage，不跨设备（后端无 preferences 端点） */
    localOnlyHint: '偏好仅保存在当前浏览器本地，不跨设备同步',
    about: '关于',
    aboutBackend: '后端',
    aboutBackendHint: 'Spring Boot 3.4.6 / Java 21 / lettuce 7.8.0，SQLite（默认）· MySQL · PostgreSQL',
    aboutSecurity: '安全提示',
    aboutSecurityText:
        '本服务等同于「持有全部已配置 Redis 实例读写权限的跳板」，仅可部署在受控内网。对外暴露前必须在网关层自行加鉴权。',
    notFound: {
        title: '页面不存在',
        text: '请求的路径没有对应的页面。',
        backHome: '返回仪表盘',
    },
}

export type SettingsSchema = typeof zh

export const en: SettingsSchema = {
    title: 'Settings',
    general: 'General',
    language: 'Language',
    languageHint: 'Takes effect immediately and is persisted locally',
    preferences: 'Interface preferences',
    appearance: 'Appearance',
    localOnlyHint: 'Preferences are stored in this browser only and are not synced across devices',
    about: 'About',
    aboutBackend: 'Backend',
    aboutBackendHint: 'Spring Boot 3.4.6 / Java 21 / lettuce 7.8.0, SQLite (default) · MySQL · PostgreSQL',
    aboutSecurity: 'Security notice',
    aboutSecurityText:
        'This service is effectively a jump host holding read/write access to every configured Redis instance. Deploy it only inside a controlled intranet, and add gateway authentication before exposing it.',
    notFound: {
        title: 'Page not found',
        text: 'No page matches the requested path.',
        backHome: 'Back to dashboard',
    },
}