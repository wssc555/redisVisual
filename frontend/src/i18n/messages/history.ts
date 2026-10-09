/**
 * 操作日志页（Y1）。
 *
 * ⚠️ 页顶 info 横幅的**覆盖边界**是本域最重要的文案 ——
 * 只记录经本平台执行的写/删操作，外部客户端变更与 TTL 过期不在其中。
 */
export const zh = {
    title: '操作日志',
    /** 覆盖边界横幅：必须明示，避免用户把日志当全量事实 */
    coverage: {
        title: '记录范围说明',
        text: '仅记录**经本平台**执行的写入与删除操作。外部客户端（redis-cli、业务应用）的变更、以及 Redis 端被动 TTL 过期**不在其中** —— Redis 原生不提供 key 的创建/删除时间元数据，无可回溯途径。',
    },
    filter: {
        profile: '实例',
        allProfiles: '全部实例',
        /** 已删实例的历史只有不选实例才查得到（后端对不存在的 profileId 返回 40401） */
        allProfilesHint: '不选实例可查到已删除实例的历史记录',
        db: '库',
        key: 'key 关键词',
        keyPlaceholder: '子串匹配，大小写不敏感',
        value: 'value 关键词',
        valuePlaceholder: '子串匹配（仅预览窗口内）',
        valueHint: 'value 关键词为全表扫描，建议配合 key 或时间范围收窄',
        operation: '操作类型',
        allOperations: '全部操作',
        timeRange: '时间范围',
        startTime: '开始时间',
        endTime: '结束时间',
        query: '查询',
        reset: '重置',
        timeInvalid: '开始时间不能晚于结束时间',
        /** value 检索成本高 */
        costly: '高开销',
    },
    table: {
        eventTime: '时间',
        profile: '实例',
        db: '库',
        keyName: 'key',
        keyType: '类型',
        operation: '操作',
        valuePreview: '值预览',
        valueBytes: '值长度',
        operator: '操作者',
        truncated: '已截断',
        total: '共 {n} 条',
    },
    empty: '该条件下无操作记录',
    loadFailed: '加载操作日志失败：{msg}',
    /** 删除类操作高亮 */
    deleteTag: '删除',
}

export type HistorySchema = typeof zh

export const en: HistorySchema = {
    title: 'Operation Log',
    coverage: {
        title: 'Coverage',
        text: 'Only writes and deletions performed **through this platform** are recorded. Changes made by external clients (redis-cli, business applications) and passive TTL expiry on the Redis side are **not included** — Redis provides no creation/deletion timestamp for keys, so there is no way to recover them.',
    },
    filter: {
        profile: 'Instance',
        allProfiles: 'All instances',
        allProfilesHint: 'Leave the instance unselected to include records of deleted instances',
        db: 'DB',
        key: 'Key keyword',
        keyPlaceholder: 'Substring match, case-insensitive',
        value: 'Value keyword',
        valuePlaceholder: 'Substring match (preview window only)',
        valueHint: 'Value keywords scan the whole table; narrow with key or time range',
        operation: 'Operation',
        allOperations: 'All operations',
        timeRange: 'Time range',
        startTime: 'Start',
        endTime: 'End',
        query: 'Query',
        reset: 'Reset',
        timeInvalid: 'The start time cannot be later than the end time',
        costly: 'Costly',
    },
    table: {
        eventTime: 'Time',
        profile: 'Instance',
        db: 'DB',
        keyName: 'Key',
        keyType: 'Type',
        operation: 'Operation',
        valuePreview: 'Value preview',
        valueBytes: 'Value size',
        operator: 'Operator',
        truncated: 'truncated',
        total: '{n} record(s) in total',
    },
    empty: 'No records match the current filters',
    loadFailed: 'Failed to load the operation log: {msg}',
    deleteTag: 'Delete',
}