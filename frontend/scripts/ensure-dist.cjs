// tauri build 前置校验：确认 frontend/dist 已存在（CI/本地已先执行 pnpm build），避免重复构建。
//
// 为什么是独立脚本文件而不是 node -e 内联：
//   Windows 上 Tauri CLI 通过 `cmd /S /C <script>` 执行 hook，内联 JS 里的双引号
//   会被 cmd 解析层吃掉/错位，导致 node 收到的代码以字面量 " 开头，直接报
//   "Unterminated string constant"。独立文件 + 无引号命令对 cmd/sh 均安全。
//
// 工作目录：tauri CLI 调用目录（各 workflow 与本地均为 frontend/）。
// 兼容从 src-tauri 或 frontend 两种 cwd 调用，故同时探测两个相对位置。
const fs = require('fs');
const path = require('path');

const candidates = [
  path.resolve('dist/index.html'),
  path.resolve('../dist/index.html'),
];

if (candidates.some((p) => fs.existsSync(p))) {
  console.log('frontend/dist 已就绪（已预构建），跳过重复构建');
} else {
  console.error('frontend/dist 未就绪：请先执行 pnpm build');
  process.exit(1);
}
