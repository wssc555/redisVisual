import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath} from 'node:url'

export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {'@': fileURLToPath(new URL('./src', import.meta.url))},
    },
    test: {
        // 默认 node 环境；挂载型 spec 需在文件首行声明 // @vitest-environment jsdom
        environment: 'node',
        globals: false,
        // 只收录 src 下的测试：避免扫到 .kilo/worktrees 等工具目录里的陈旧副本
        include: ['src/**/*.{test,spec}.?(c|m)[jt]s?(x)'],
    },
})