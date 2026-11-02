/**
 * Electron 主进程 —— 桌面壳的全部职责。
 *
 * 职责清单：
 * 1. 打包态从 resources 拉起后端 sidecar（`runtime/bin/java -jar app.jar
 *    --server.port=0 --announce-port --app.db-path=<userData>/app.db`）；
 * 2. 逐行扫描 stdout 找 `PORT=<port>` 握手行，经 `backend_port` IPC 暴露给渲染进程；
 * 3. 开发态（`electron .` 未打包）或资源缺失：端口保持 null，前端轮询超时后走 vite proxy；
 * 4. 单实例（二次启动聚焦已有窗口）；
 * 5. 退出（关窗 / before-quit）时 kill sidecar；
 * 6. 诊断日志全量落盘 `<userData>/logs/backend.log`（release 无控制台，这是唯一的诊断出口）。
 *
 * 安全模型（§3.3）：壳只做壳的事 —— contextIsolation + sandbox + nodeIntegration:false，
 * preload 仅暴露 3 个只读通道；主进程不注册多余 handler；release 不开 devTools。
 *
 * 加载方式（§3.2 方案 A）：自定义标准协议 `app://localhost` 承载渲染进程，
 * 使 origin 稳定可精确白名单（后端 CORS 只认这一条），而非 `file://` 退化成 `null`。
 */
import {app, BrowserWindow, ipcMain, protocol, shell} from 'electron'
import {type ChildProcess, spawn} from 'node:child_process'
import * as fs from 'node:fs'
import * as http from 'node:http'
import * as os from 'node:os'
import * as path from 'node:path'
import * as readline from 'node:readline'

/** 自定义协议 scheme：standard + secure，否则 Chromium 会把 origin 序列化成 `null`。 */
const APP_SCHEME = 'app'
const APP_HOST = 'localhost'
const APP_ORIGIN = `${APP_SCHEME}://${APP_HOST}`
/** 开发态渲染进程地址（vite dev server），与 vite.config.ts 的 server.port 一致。 */
const DEV_SERVER_URL = 'http://localhost:5173'
/** `read_backend_log` 返回的最大行数。 */
const LOG_TAIL_LINES = 500
/**
 * 应用数据目录名。数据目录的**基目录**三平台不同（Win: %APPDATA%、macOS: ~/Library/Application Support、
 * Linux: $XDG_DATA_HOME 即 ~/.local/share），setPath 时按平台各自对齐（见文件底部），已有 app.db / app.key 三平台都能读到。
 */
const APP_DATA_DIR_NAME = 'com.redisviz.frontend'

interface BackendState {
    /** sidecar 实际监听端口；null = 未就绪 / 未拉起（前端轮询等待或走 proxy） */
    port: number | null
    child: ChildProcess | null
    /** 启动期错误（资源缺失 / spawn 失败 / 握手前退出），前端超时后拉取弹框 */
    startupErrors: string[]
}

const state: BackendState = {port: null, child: null, startupErrors: []}

let mainWindow: BrowserWindow | null = null
/** backend.log 绝对路径；空串 = 日志不可写（静默降级，不影响握手） */
let backendLogPath = ''

// ---------------------------------------------------------------------------
// 诊断日志
// ---------------------------------------------------------------------------

/**
 * 打开 `<userData>/logs/backend.log`（追加模式）。打不开则保持空路径，
 * 后续 logLine 静默 no-op —— 握手逻辑不依赖日志可写。
 */
function openBackendLog(): void {
    try {
        const dir = path.join(app.getPath('userData'), 'logs')
        fs.mkdirSync(dir, {recursive: true})
        backendLogPath = path.join(dir, 'backend.log')
    } catch {
        backendLogPath = ''
    }
}

/**
 * 带秒级 unix 时间戳的日志行；`tag` 区分来源（shell / java / java-err）。
 * 同步追加：主进程单线程内调用，同步写能保序且实现最简（日志量级为启动期几百行）。
 */
function logLine(tag: string, line: string): void {
    if (!backendLogPath) return
    try {
        const secs = Math.floor(Date.now() / 1000)
        fs.appendFileSync(backendLogPath, `[${secs}] [${tag}] ${line}\n`)
    } catch {
        // 日志写入失败不影响任何业务流程
    }
}

/** 读取 backend.log 尾部 LOG_TAIL_LINES 行；文件不存在返回空清单。 */
function readBackendLogTail(): string[] {
    try {
        const file = path.join(app.getPath('userData'), 'logs', 'backend.log')
        const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
        // split 会为末尾换行留一个空串，先丢弃再取尾部窗口
        if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()
        return lines.slice(Math.max(0, lines.length - LOG_TAIL_LINES))
    } catch {
        return []
    }
}

// ---------------------------------------------------------------------------
// sidecar 拉起 + 端口握手
// ---------------------------------------------------------------------------

/**
 * 资源根目录。
 * - 打包态：`process.resourcesPath`，即 `<exe 目录>/resources`（electron-builder 的 extraResources
 *   默认落到这里）→ 便携版与安装版同布局：`resources/app.jar` + `resources/runtime/`；
 * - 开发态：`<app 目录>/resources`，方便本地放一份 jar 验证握手；不存在即走 proxy。
 */
function resourcesRoot(): string {
    return app.isPackaged
        ? process.resourcesPath
        : path.join(app.getAppPath(), 'resources')
}

function isFile(p: string): boolean {
    try {
        return fs.statSync(p).isFile()
    } catch {
        return false
    }
}

/** jlink runtime 的 java 可执行文件（**不可退化成宿主 PATH 上的裸 java**）。 */
function javaExecutable(root: string): string {
    return path.join(root, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
}

/**
 * 拉起后端并开始握手扫描。
 *
 * 资源缺失（开发态常态 / release 安装包损坏）不抛错：保持空 state，前端超时后弹框，
 * 期间 `'/api'` 兜底走 vite proxy —— 开发工作流不变。
 */
function spawnBackend(): void {
    openBackendLog()

    const root = resourcesRoot()
    const jar = path.join(root, 'app.jar')
    const java = javaExecutable(root)
    const jarExists = isFile(jar)
    const javaExists = isFile(java)

    logLine('shell', `resources=${root} jar=${jar} jarExists=${jarExists} java=${java} javaExists=${javaExists}`)

    if (!jarExists || !javaExists) {
        logLine('shell', 'resources missing — dev/proxy mode, no sidecar spawned')
        // release 下资源缺失 = 安装包损坏，属启动错误；开发态是正常形态，不进错误清单
        if (app.isPackaged) {
            state.startupErrors.push(
                `后端资源缺失：jar=${jar}（存在=${jarExists}），java=${java}（存在=${javaExists}）。` +
                '安装包可能不完整，请重新安装/解压。',
            )
        }
        return
    }

    // `--app.db-path` 口径：AppPaths 按**数据库文件路径**处理该值（不是目录），
    // 故这里显式拼上 app.db —— 传目录会导致 jdbc:sqlite:<目录> 开库失败。
    // 父目录由后端 StorageDataSourceConfig 创建，无需壳侧预建。
    const dbFile = path.join(app.getPath('userData'), 'app.db')
    const args = ['-jar', jar, '--server.port=0', '--announce-port', `--app.db-path=${dbFile}`]
    logLine('shell', `spawning: ${java} ${args.join(' ')}`)

    // 显式建目录：spawn 的 cwd 指向 userData，不依赖 openBackendLog() 的 mkdir 副作用
    //（后者失败会静默降级为「日志不可写」，但目录必须存在才能做 cwd）。
    try {
        fs.mkdirSync(app.getPath('userData'), {recursive: true})
    } catch (e) {
        logLine('shell', `mkdir userData failed: ${e instanceof Error ? e.message : String(e)}`)
    }

    let child: ChildProcess
    try {
        child = spawn(java, args, {
            // Windows：java.exe 是控制台程序，不隐藏会弹黑色命令行框
            windowsHide: true,
            stdio: ['ignore', 'pipe', 'pipe'],
            // 固定 cwd 为可写的数据目录：从 Finder/快捷方式启动时宿主 cwd 可能是 `/` 或只读目录，
            // 后端任何相对路径（日志、缓存）若落在那里会静默失败。
            cwd: app.getPath('userData'),
        })
    } catch (e) {
        const msg = e instanceof Error ? e.message : String(e)
        logLine('shell', `spawn failed: ${msg}`)
        state.startupErrors.push(`后端进程启动失败：${msg}`)
        return
    }

    state.child = child
    logLine('shell', `spawned pid=${child.pid ?? 'unknown'}`)

    // spawn 的同步异常极少（如 cwd 不存在），绝大多数失败走 error 事件（ENOENT/EACCES）
    child.on('error', (err) => {
        logLine('shell', `spawn error: ${err.message}`)
        state.startupErrors.push(`后端进程启动失败：${err.message}`)
    })

    // stdout：逐行落盘 + 前缀扫描握手行。
    // 不能用「首行」语义 —— Spring banner 与启动日志先于协议行出现（见 PortAnnouncer javadoc）。
    let announced = false
    if (child.stdout) {
        const out = readline.createInterface({input: child.stdout})
        out.on('line', (line) => {
            logLine('java', line)
            const trimmed = line.trim()
            if (!trimmed.startsWith('PORT=')) return
            const raw = trimmed.slice('PORT='.length).trim()
            const port = Number(raw)
            // 解析失败只记日志、继续扫描后续行（不 panic、不中止）
            if (!Number.isInteger(port) || port <= 0 || port > 65535) {
                logLine('shell', `invalid PORT value ${JSON.stringify(raw)} — 继续扫描后续行`)
                return
            }
            announced = true
            state.port = port
            logLine('shell', `handshake ok, port=${port}`)
        })
        // stdout 关闭仍未握手 = 后端已死（Spring 启动失败/被杀）。java 侧真实异常在同文件上方的
        // java / java-err 行里；这条提示保证用户不会「只见超时不见原因」。
        out.on('close', () => {
            logLine('shell', 'backend stdout closed — process exited before/after handshake (see java lines above)')
            if (!announced) {
                state.startupErrors.push(
                    '后端进程提前退出，未能完成端口握手（常见原因：JVM 启动异常）。详见 backend.log 中 java/java-err 行。',
                )
            }
        })
    }

    // stderr：全量落盘。java 的启动异常（如「找不到或无法找到主类」）走 stderr。
    if (child.stderr) {
        const err = readline.createInterface({input: child.stderr})
        err.on('line', (line) => logLine('java-err', line))
        err.on('close', () => logLine('shell', 'backend stderr closed'))
    }
}

/**
 * 退出时回收 sidecar：kill，避免 JVM 残留卡端口 / 锁库。
 *
 * JVM 侧的有序关闭（Spring 上下文、lettuce 连接、HikariCP）在强杀下不执行 ——
 * 这些资源句柄由 OS 兜底回收，无跨进程残留状态，强杀可接受。
 */
function killBackend(): void {
    const child = state.child
    state.child = null
    if (!child || child.exitCode !== null) return
    logLine('shell', `killing backend sidecar pid=${child.pid ?? 'unknown'}`)
    try {
        child.kill()
    } catch (e) {
        logLine('shell', `kill failed: ${e instanceof Error ? e.message : String(e)}`)
    }
}

// ---------------------------------------------------------------------------
// app:// 协议（§3.2 方案 A）
// ---------------------------------------------------------------------------

/** 渲染进程产物的最小 MIME 映射（覆盖 vite build 的全部产出类型）。 */
const MIME_TYPES: Record<string, string> = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.map': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.ico': 'image/x-icon',
    '.webp': 'image/webp',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf',
    '.otf': 'font/otf',
}

function mimeOf(file: string): string {
    return MIME_TYPES[path.extname(file).toLowerCase()] ?? 'application/octet-stream'
}

/**
 * 注册 `app://` 处理器：把请求映射到 `dist/` 下的静态文件。
 *
 * 三点必须做对：
 * 1. 目录逃逸防护（`../` 越出 dist 一律 403）；
 * 2. 未知路径回退 index.html —— `createWebHistory()` 的深链接（如 `/keys/1`）刷新时才不会 404；
 * 3. 显式 Content-Type —— 缺失时 Chromium 会拒绝执行 module script（整页白屏）。
 */
function registerAppProtocol(): void {
    const distDir = path.join(app.getAppPath(), 'dist')
    const indexFile = path.join(distDir, 'index.html')

    protocol.handle(APP_SCHEME, async (request) => {
        let relative: string
        try {
            relative = decodeURIComponent(new URL(request.url).pathname)
        } catch {
            return new Response('bad request', {status: 400})
        }
        const target = path.normalize(path.join(distDir, relative))
        if (target !== distDir && !target.startsWith(distDir + path.sep)) {
            return new Response('forbidden', {status: 403})
        }

        const file = target === distDir ? indexFile : target
        try {
            const data = await fs.promises.readFile(file)
            // 拷贝成独立 Uint8Array：Buffer 可能是池化切片，直接当 body 会带上旁邻字节
            return new Response(new Uint8Array(data), {headers: {'Content-Type': mimeOf(file)}})
        } catch {
            try {
                const html = await fs.promises.readFile(indexFile)
                return new Response(new Uint8Array(html), {headers: {'Content-Type': mimeOf(indexFile)}})
            } catch {
                return new Response('not found', {status: 404})
            }
        }
    })
}

// ---------------------------------------------------------------------------
// 开发态渲染加载（vite dev server 就绪探测）
// ---------------------------------------------------------------------------

/** vite 未就绪多久后展示提示页（vite 冷启动一般 1~3s，留足余量） */
const DEV_SERVER_HINT_AFTER_MS = 10_000
const DEV_SERVER_POLL_INTERVAL_MS = 500

const delay = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

/** 探测 dev server 是否可连；任何错误/超时一律视为不可达（false）。 */
function probeDevServer(timeoutMs = 800): Promise<boolean> {
    return new Promise((resolve) => {
        const req = http.get(`${DEV_SERVER_URL}/`, {timeout: timeoutMs}, (res) => {
            res.resume() // 排空响应体，尽早释放连接
            resolve(true)
        })
        req.on('timeout', () => {
            req.destroy()
            resolve(false)
        })
        req.on('error', () => resolve(false))
    })
}

/** vite 未就绪时的占位提示页（data URL，独立文档，不受 index.html 的 CSP 约束）。 */
function devServerErrorUrl(): string {
    const html = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>Redis Visualizer</title>
<style>
body{font-family:"Segoe UI",system-ui,sans-serif;background:#f5f7fa;color:#303133;margin:0;
display:flex;align-items:center;justify-content:center;height:100vh}
.box{max-width:560px;background:#fff;border:1px solid #e4e7ed;border-radius:8px;
padding:32px 40px;box-shadow:0 2px 12px rgba(0,0,0,.06)}
h1{font-size:18px;margin:0 0 12px}p{font-size:14px;line-height:1.8;margin:6px 0;color:#606266}
code{background:#f0f2f5;padding:2px 6px;border-radius:4px;font-size:13px}
</style></head>
<body><div class="box">
<h1>等待 Vite 开发服务器…</h1>
<p>开发态渲染进程地址 <code>http://localhost:5173</code> 暂不可达。</p>
<p>请在 <code>frontend/</code> 目录另开终端运行 <code>pnpm dev</code>；检测到服务器就绪后，本窗口会自动加载页面。</p>
</div></body></html>`
    return `data:text/html;charset=utf-8,${encodeURIComponent(html)}`
}

/**
 * 开发态加载渲染进程：轮询等待 vite 就绪后再 loadURL。
 * 「先起 electron、后起 vite」的顺序也能自动接上；超过 10s 仍未就绪则展示提示页，
 * 之后 vite 一起来自动换到真实页面 —— 避免 `electron:dev` 单跑时的静默白屏。
 */
async function loadDevRenderer(win: BrowserWindow): Promise<void> {
    logLine('shell', `dev mode: waiting for vite dev server at ${DEV_SERVER_URL}`)
    const startedAt = Date.now()
    let hintShown = false
    while (!win.isDestroyed()) {
        if (await probeDevServer()) {
            logLine('shell', 'dev server reachable — loading renderer')
            await win.loadURL(DEV_SERVER_URL).catch((e: Error) => logLine('shell', `loadURL(dev) failed: ${e.message}`))
            return
        }
        if (!hintShown && Date.now() - startedAt >= DEV_SERVER_HINT_AFTER_MS) {
            hintShown = true
            logLine('shell', 'dev server still unreachable — showing hint page')
            await win.loadURL(devServerErrorUrl()).catch(() => {
            })
        }
        await delay(DEV_SERVER_POLL_INTERVAL_MS)
    }
}

// ---------------------------------------------------------------------------
// 窗口
// ---------------------------------------------------------------------------

function createWindow(): void {
    mainWindow = new BrowserWindow({
        // 窗口 1280×800，最小 960×640（§3.4）
        width: 1280,
        height: 800,
        minWidth: 960,
        minHeight: 640,
        title: 'Redis Visualizer',
        backgroundColor: '#ffffff',
        show: false,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            // 安全三件套：默认值也显式写出，防止后续误改
            contextIsolation: true,
            sandbox: true,
            nodeIntegration: false,
            // release 不开 devTools；排障可临时置 true
            devTools: !app.isPackaged,
            spellcheck: false,
        },
    })

    // 首帧就绪后再显示，避免白屏闪烁
    mainWindow.once('ready-to-show', () => mainWindow?.show())
    mainWindow.on('closed', () => {
        mainWindow = null
    })

    // 外链交给系统浏览器，壳内不开新窗口（Electron 默认权限面更大，这里主动收紧）
    mainWindow.webContents.setWindowOpenHandler(({url}) => {
        if (/^https?:\/\//i.test(url)) void shell.openExternal(url)
        return {action: 'deny'}
    })
    // 阻止渲染进程被导航到任意地址（仅允许自身协议与 dev server）。
    // 用「协议+host」比较而非 startsWith 前缀匹配：`app://localhost.evil.com/`、
    // `http://localhost:5173@evil.com/` 这类前缀合法但 host 不同的地址必须拦下。
    // 注意 Node 的 URL 对非特殊协议（app:）的 .origin 返回 'null'，故不能直接比 origin。
    mainWindow.webContents.on('will-navigate', (event, url) => {
        let allowed = false
        try {
            const target = new URL(url)
            const originLike = `${target.protocol}//${target.host}`
            allowed = originLike === APP_ORIGIN || originLike === DEV_SERVER_URL
        } catch {
            // 解析失败一律不放行
        }
        if (!allowed) {
            event.preventDefault()
            logLine('shell', `blocked navigation to ${url}`)
        }
    })

    if (app.isPackaged) {
        void mainWindow.loadURL(`${APP_ORIGIN}/index.html`)
    } else {
        void loadDevRenderer(mainWindow)
    }
}

// ---------------------------------------------------------------------------
// IPC（与 preload.ts 的通道名严格一一对应）
// ---------------------------------------------------------------------------

function registerIpc(): void {
    // 「还没好」是正常中间态：不抛错、不阻塞，返回 null 让前端继续轮询
    ipcMain.handle('backend_port', () => state.port)
    ipcMain.handle('startup_errors', () => state.startupErrors.slice())
    ipcMain.handle('read_backend_log', () => readBackendLogTail())
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------

// 必须在 app ready 之前调用：standard 是关键，否则 origin 序列化为 null，
// 后端 CORS 要么拒绝、要么被迫开 null 通配（等于放弃同源策略）。
protocol.registerSchemesAsPrivileged([
    {
        scheme: APP_SCHEME,
        privileges: {standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true},
    },
])

// 数据目录的基目录按平台不同：
// Windows %APPDATA% / macOS ~/Library/Application Support —— 与 Electron 的 appData 一致；
// Linux 是 ~/.local/share（XDG data），而 Electron 的 appData 指向 ~/.config —— 必须显式对齐，
// 否则 Linux 上已有的 app.db / app.key 读不到，会静默新建一份空库。
const userDataDir = process.platform === 'linux'
    ? path.join(process.env.XDG_DATA_HOME || path.join(os.homedir(), '.local', 'share'), APP_DATA_DIR_NAME)
    : path.join(app.getPath('appData'), APP_DATA_DIR_NAME)
app.setPath('userData', userDataDir)

if (!app.requestSingleInstanceLock()) {
    // 二次启动：直接退出，由已运行实例的 second-instance 处理聚焦
    app.quit()
} else {
    app.on('second-instance', () => {
        if (!mainWindow) return
        if (mainWindow.isMinimized()) mainWindow.restore()
        mainWindow.focus()
    })

    app.whenReady().then(() => {
        registerAppProtocol()
        registerIpc()
        spawnBackend()
        createWindow()

        // macOS：Dock 图标点击且无窗口时重建（关窗即退出的策略见下）
        app.on('activate', () => {
            if (BrowserWindow.getAllWindows().length === 0) createWindow()
        })
    })

    // 关窗即退出（macOS 同样，见 §3.4）
    app.on('window-all-closed', () => {
        app.quit()
    })

    // 退出前回收 JVM：window-all-closed → before-quit 覆盖正常退出路径
    app.on('before-quit', () => {
        killBackend()
    })

    process.on('exit', () => {
        killBackend()
    })
}
