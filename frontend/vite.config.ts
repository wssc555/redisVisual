import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath} from 'node:url'

export default defineConfig({
    plugins: [vue()],
    resolve: {
        // tsconfig 声明了 @/* → ./src/*，但 vite/vitest 本身不解析 paths，
        // 代码一旦用 @/ 导入就会构建失败。这里与 tsconfig 保持同源。
        alias: {'@': fileURLToPath(new URL('./src', import.meta.url))},
    },
    build: {
        outDir: 'dist',
        emptyOutDir: true,
    },
    server: {
        port: 5173,
        proxy: {
            // 后端固定 8080（application.yml server.port）
            '/api': 'http://localhost:8080',
        },
    },
})