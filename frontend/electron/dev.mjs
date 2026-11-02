#!/usr/bin/env node
/**
 * 开发态编排器：一条命令同时拉起 vite dev server 与 Electron 壳。
 *
 * 行为：
 * 1. 5173 已有监听（手动 `pnpm dev` 过）→ 复用现有 vite，不重复拉起；
 * 2. 否则用本地 node_modules 的 vite.js 拉起（spawn node 直跑 js 文件，
 *    不经 shell / CMD shim，绕开 Windows 下 .bin 引号坑）；
 * 3. 拉起 Electron —— main.ts 的 loadDevRenderer() 会轮询等 vite 就绪后自动加载渲染层，
 *    所以两个进程谁先谁后都能接上；
 * 4. 任一进程退出 / Ctrl+C → 两个都回收，不留孤儿 vite 占着 5173。
 *
 * 注意：electron 包在 Node 侧 `import('electron')` 导出的是**可执行文件路径**
 * （见 node_modules/electron/index.js），并非渲染侧那个 electron 模块。
 */
import {spawn} from 'node:child_process'
import * as http from 'node:http'
import * as path from 'node:path'
import {fileURLToPath} from 'node:url'

const frontendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const viteBin = path.join(frontendDir, 'node_modules', 'vite', 'bin', 'vite.js')
/** @type {string} electron 包在 Node 侧导出可执行文件绝对路径 */
const electronBin = (await import('electron')).default

const DEV_SERVER_URL = 'http://localhost:5173'

let vite = null
let electronApp = null
let shuttingDown = false

function kill(child) {
    if (!child || child.exitCode !== null || child.signalCode !== null) return
    try {
        child.kill()
    } catch {
        // 退出竞态，忽略
    }
}

function shutdown(exitCode = 0) {
    if (shuttingDown) return
    shuttingDown = true
    kill(vite)
    kill(electronApp)
    process.exit(exitCode)
}

process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))
process.on('exit', () => {
    kill(vite)
    kill(electronApp)
})

/** 探测 dev server 是否可连；任何错误/超时一律视为不可达（false）。与 main.ts 同口径。 */
function probeDevServer(timeoutMs = 800) {
    return new Promise((resolve) => {
        const req = http.get(`${DEV_SERVER_URL}/`, {timeout: timeoutMs}, (res) => {
            res.resume()
            resolve(true)
        })
        req.on('timeout', () => {
            req.destroy()
            resolve(false)
        })
        req.on('error', () => resolve(false))
    })
}

// --- 拉起 vite（5173 空闲时）-------------------------------------------------

if (await probeDevServer()) {
    console.log('[dev] vite dev server 已在 5173 运行 —— 复用现有实例')
} else {
    vite = spawn(process.execPath, [viteBin], {cwd: frontendDir, stdio: 'inherit'})
    vite.on('error', (e) => {
        console.error(`[dev] vite 启动失败: ${e.message}`)
        shutdown(1)
    })
    console.log(`[dev] vite 启动中 (pid=${vite.pid})`)
}

// --- 拉起 Electron -----------------------------------------------------------

// 某些宿主环境（如 AI 沙箱）自带 ELECTRON_RUN_AS_NODE=1，会把 electron.exe
// 退化成纯 Node 运行（require('electron') 变成路径字符串）——拉起应用壳时必须清掉。
const env = {...process.env}
delete env.ELECTRON_RUN_AS_NODE

electronApp = spawn(electronBin, [frontendDir], {cwd: frontendDir, stdio: 'inherit', env})
electronApp.on('error', (e) => {
    console.error(`[dev] electron 启动失败: ${e.message}`)
    shutdown(1)
})
console.log(`[dev] electron 启动中 (pid=${electronApp.pid})`)

// --- 联动回收 -----------------------------------------------------------------

vite?.on('exit', (code) => {
    if (shuttingDown) return
    console.log(`[dev] vite 退出 (code=${code}) —— 同步退出 electron`)
    shutdown(code ?? 1)
})
electronApp.on('exit', (code) => {
    if (shuttingDown) return
    console.log(`[dev] electron 退出 (code=${code}) —— 同步退出 vite`)
    shutdown(code ?? 0)
})
