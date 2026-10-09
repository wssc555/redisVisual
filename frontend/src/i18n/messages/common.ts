/**
 * 通用词条（跨页面复用的高频文案）。
 *
 * 本文件只放**跨域复用**的词条；页面专属文案一律写入自己的域模块
 * （dashboard / profileManage / keyBrowse / typeEditors / monitor / history）。
 */
export const zh = {
    confirm: '确认',
    cancel: '取消',
    retry: '重试',
    remove: '删除',
    edit: '编辑',
    save: '保存',
    search: '查询',
    refresh: '刷新',
    loading: '加载中…',
    operation: '操作',
    submit: '提交',
    none: '无',
    unknownError: '未知错误',
    gotIt: '知道了',
    later: '稍后再说',
    offline: '离线',
    online: '在线',
    connected: '已连接',
    noData: '暂无数据',
    total: '合计',
    /** 空值占位：null / undefined 显示为破折号，与后端「尚无采样」语义一致 */
    dash: '—',
    key: '键名',
    keyPlaceholder: '输入 key，支持 glob 通配',
    db: '库',
    /** 写操作二次确认（useCrudConfirm） */
    crud: {
        confirmTitle: '确认操作',
    },
    /** 集群实例的 db 恒为 0 */
    clusterDbFixed: '集群模式固定使用 db 0',
    /** 大 Key 防护被触发时的统一引导（40001 且 msg 含「大 Key」） */
    largeKey: {
        hint: '该 key 元素过多，已拒绝无界全量请求；请使用分页参数逐批读取',
    },
    /** 离线态横幅（50302） */
    offlineBanner: {
        title: '当前实例不可用',
        hint: '连接超时或实例离线。请检查实例状态后重试，或切换到其他实例。',
    },
}

export type CommonSchema = typeof zh

export const en: CommonSchema = {
    confirm: 'Confirm',
    cancel: 'Cancel',
    retry: 'Retry',
    remove: 'Delete',
    edit: 'Edit',
    save: 'Save',
    search: 'Query',
    refresh: 'Refresh',
    loading: 'Loading…',
    operation: 'Actions',
    submit: 'Submit',
    none: 'None',
    unknownError: 'Unknown error',
    gotIt: 'Got it',
    later: 'Later',
    offline: 'Offline',
    online: 'Online',
    connected: 'Connected',
    noData: 'No data',
    total: 'Total',
    dash: '—',
    key: 'Key',
    keyPlaceholder: 'Enter key, glob patterns supported',
    db: 'DB',
    crud: {
        confirmTitle: 'Confirm operation',
    },
    clusterDbFixed: 'Cluster mode always uses db 0',
    largeKey: {
        hint: 'This key has too many elements; unbounded full reads are rejected. Please page through it',
    },
    offlineBanner: {
        title: 'Instance unavailable',
        hint: 'Connection timed out or the instance is offline. Check the instance and retry, or switch to another one.',
    },
}