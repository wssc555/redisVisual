/**
 * electron:compile 的后处理。
 *
 * 根 package.json 声明了 `"type": "module"`，而 tsc 输出的是 `.js` —— 若不额外标注，
 * Node 会把 main.js / preload.js 当 ESM 加载；而 **sandbox 化的 preload 不支持 ESM**
 * （Electron 硬限制），主进程加载 preload 会直接失败且报错难懂。
 *
 * 在产物目录里放一个 `{"type":"commonjs"}` 标记，把 dist-electron/ 圈成 CJS 作用域：
 * 比给根包去掉 type:module（会波及 vite.config.ts / vitest.config.ts 的 ESM 语义）安全得多。
 */
import {mkdirSync, writeFileSync} from 'node:fs'

mkdirSync('dist-electron', {recursive: true})
writeFileSync('dist-electron/package.json', JSON.stringify({type: 'commonjs'}, null, 2) + '\n')
console.log('[electron] dist-electron/package.json written (type: commonjs)')
