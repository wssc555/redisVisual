/**
 * preload —— 渲染进程与壳之间**唯一**的通道（§3.3 最小暴露）。
 *
 * 只暴露 3 个只读方法，不暴露 `ipcRenderer` 本体、不暴露 fs / child_process：
 * Electron 默认权限面较大，全部收敛靠这里。
 *
 * 通道名与 `main.ts` 的 `ipcMain.handle` 严格一一对应：
 *   backend_port / startup_errors / read_backend_log
 */
import {contextBridge, ipcRenderer} from 'electron'

const bridge = {
    /** sidecar 实际监听端口；未就绪返回 null（前端轮询） */
    backendPort: (): Promise<number | null> => ipcRenderer.invoke('backend_port'),
    /** 启动期错误清单（资源缺失 / spawn 失败 / 握手前退出） */
    startupErrors: (): Promise<string[]> => ipcRenderer.invoke('startup_errors'),
    /** backend.log 尾部 500 行（排障用） */
    readBackendLog: (): Promise<string[]> => ipcRenderer.invoke('read_backend_log'),
}

// 挂到 window（渲染进程的 globalThis）—— 前端按 globalThis.redisviz 探测，
// 保证 vitest(node 环境) 下不触碰 window 也不会 ReferenceError。
contextBridge.exposeInMainWorld('redisviz', bridge)
