/**
 * Electron 侧类型声明。
 *
 * `RedisvizBridge` 是壳 ↔ 前端之间的**契约**（唯一真相在这里）。
 * 渲染进程 `src/api/index.ts` 出于编译边界（Node 侧 vs 浏览器侧、两套 tsconfig）
 * 不直接 import 本文件，而是保留一份等价的结构类型 —— 改这里必须同步那边。
 */

/** preload 经 contextBridge 暴露到 `globalThis.redisviz` 的接口。 */
interface RedisvizBridge {
    backendPort(): Promise<number | null>

    startupErrors(): Promise<string[]>

    readBackendLog(): Promise<string[]>
}

declare global {
    /** 仅打包态存在；浏览器 / vite dev / vitest(node) 下为 undefined。 */
        // eslint-disable-next-line no-var
    var redisviz: RedisvizBridge | undefined
}

export {}
