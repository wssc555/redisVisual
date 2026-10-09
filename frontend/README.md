# Redis 可视化平台 · 前端

Vue 3.5 + TypeScript 5.9 + Vite 6 + Element Plus 2.14 + vue-i18n 11 + echarts 5.6。

- 设计文档（唯一权威口径）：`../docs/2026-10-06-frontend-design.md`
- 后端契约：`../docs/backend-design.md` 与 `../src/main/java`（48 端点已实现）

---

## 快速开始

```bash
pnpm install
pnpm dev        # 5173，/api 代理到 8080
pnpm build      # 含 vue-tsc --noEmit 类型检查
pnpm test       # vitest（node 环境）
```

> 后端需另行启动（`mvn spring-boot:run`，监听 8080）。前端不含后端进程。

---

## 目录结构

```
src/
├── api/index.ts              # 唯一后端触点：恒 200 拦截器 + 48 端点函数 + 全部 VO 类型
├── components/               # 无自主请求的展示组件（ProfileFormDialog 例外，内部调 validate）
│   ├── ProfileSwitcher / DbSelector / ProfileFormDialog
│   ├── KeyTable / KeySearchPanel / KeyDetailPane / TtlDialog / RenameDialog
│   ├── StringValueEditor / ListEditor / HashEditor / SetEditor / ZSetEditor
│   ├── TrendChart / StatCard / TopologyPanel / InfoSectionViewer
│   ├── SlowLogTable / ClientTable
│   └── HistoryFilterBar / HistoryTable / FirstRunGuide / StartupDiagnostics
├── composables/              # useGlobalState / useScanPagination / useCrudConfirm / useFormat / useResponsive / bootLog
├── i18n/messages/            # 9 个域，zh 与 en 同文件相邻维护
├── router/ views/ styles/ test/
```

分层规则：`api/` 是唯一后端触点 → `views/` 编排数据加载 → `components/` 只渲染。

---

## 与后端的三个关键约定

### 1. 所有端点恒返回 HTTP 200

错误只体现在响应体 `code`，故 **成功路径必须判 code**（与参照项目 kafkaVisual5 相反）：

```ts
api.interceptors.response.use((res) => {
  const body = res.data
  if (body.code !== 0) return Promise.reject(new ApiError(body.msg, body.code, res.status))
  return body
})
```

无返回值写操作（删除 / 改 TTL / 设分）的 `data` 会被整体省略，消费侧读到 `undefined`。

### 2. 秘密三态（连接配置密码）

| 用户操作   | 请求体                   | 效果         |
|------------|--------------------------|--------------|
| 不动密码框 | **省略该键**（不是空串） | 保持库中密文 |
| 点「清空」 | `password: ''`           | 清空         |
| 填新值     | `password: '新值'`       | 替换         |

把「没动」错传成空串等于 **清空用户密码**，故逻辑集中在 `views/profileForm.ts` 并有单测钉死。

### 3. 路径段转义

`encodeSegment()` 在 `encodeURIComponent` 之后再把 `/` 换成 `%2F`（前者不转义 `/`）。

⚠️ **已知联调风险**：Tomcat 默认拒绝 encoded slash。若含 `/` 的 key 操作被容器层 400 拒绝，需后端开 `ALLOW_ENCODED_SLASH`
或补 query 传 key 的端点变体（后端【待确认】#1）。UI 会在检测到 key 含 `/` 时给出提示。

---

## 四种分页形态

| 形态                | 端点               | 交互                                      | 耗尽判据                                |
|---------------------|--------------------|-------------------------------------------|-----------------------------------------|
| SCAN 游标（string） | K1                 | 加载更多                                  | `exhausted === true`                    |
| SCAN 游标（number） | H1 / E1            | 加载更多                                  | `nextCursor === 0`（无 exhausted 字段） |
| 索引区间            | L1                 | 真分页（total = LLEN）                    | —                                       |
| 页码                | Z1（索引模式）/ Y1 | el-pagination                             | —                                       |
| 预算扫描            | R1                 | 无分页，展示 scanned / exhausted / budget | —                                       |

集群 SCAN 游标是 `host|port|nodeCursor` 三段式， **原样透传、绝不解析**。

---

## 其他易错点（实现时已按此处理）

- **ZSet 分数区间模式下 `page` 不生效**（后端契约）：给 `min` 或 `max` 后 UI 隐藏分页器，只显首批。
- **List 的 `end` 恒 ≥ 0**：`end=-1` 被后端判为「无界全量请求」，大 Key 直接 40001。
- **Hash / Set 的 `count` 必传**：不传同样被判为无界全量请求；Set 上限 500。
- **TTL 设为 0 = 立即删除该 key**（不是「无过期」）：走红色二次确认；`-1` 才是 PERSIST。
- **`cpuUsagePercent` 区分 `null` 与 `0`**：`null` = 后端启动 10s 内尚无差分（显示 `—`），`0` = 真实零值。
- **`validate` 恒 `code=0`**：不可达通过 `data.reachable=false` 表达，不弹错误码 toast。
- **el-tabs 的 pane 必须显式写 `name`**，否则页签全灰。
- **模板组件 / 图标必须显式 import**（无自动导入），漏了 vue-tsc 静默、运行时静默不渲染。
- **`%2F` 在语言包里是安全字面量**，但 `@` `|` `{` `}` 在 vue-i18n 中有语法含义，新增文案需注意转义。
- **`e.method.includes('大 Key')`** 这类按 msg 子串分支依赖后端中文文案，改后端文案需同步前端。

---

## 错误码消费

| code  | 前端行为                                                                          |
|-------|-----------------------------------------------------------------------------------|
| 40001 | 按 msg 分支：含「大 Key」→ 分页引导；含「索引」→ 重拉区间；rename 冲突 → 原样展示 |
| 40401 | 引导到连接配置页 / 原样 toast                                                     |
| 40402 | key 或元素不存在 → 提示 + 从列表移除该行                                          |
| 40902 | 类型已变化 → 提示 + 自动重拉 K2                                                   |
| 50302 | 当前页切「离线态」横幅（重试 + 切换实例）                                         |
| 50000 | List 按索引删除的并发冲突 → 提示重拉区间                                          |
| 50001 | 原文 toast（后端 msg 已带上下文）                                                 |

---

## 操作日志的覆盖边界

`/history` 只记录 **经本平台**执行的写 / 删操作。外部客户端（redis-cli、业务应用）的变更与 Redis 端被动 TTL 过期
**不在其中** —— Redis 原生不提供 key 创建 / 删除时间元数据，无可回溯途径。页面顶部有常驻横幅明示。

---

## 桌面化预留

`initApiBaseUrl()` 已实现 Tauri 握手（`__TAURI_INTERNALS__` 探测 → 轮询 `invoke('backend_port')` → baseURL 指向
`http://127.0.0.1:{port}/api`，60s 超时拉 `startup_errors`）。本期 **不建** `src-tauri/` 工程；后端 CORS 白名单已含
`tauri.localhost`。

---

## 整合与部署

- `dist/` **不进后端 jar**。随 jar 发布需手动拷 `dist/*` → 后端 `src/main/resources/static/`（两边均 gitignore）。
- pnpm 12 的构建脚本白名单在 `pnpm-workspace.yaml` 的 `allowBuilds`（不读 package.json 的 `pnpm` 字段）。

---

## 安全红线

本服务等同于「持有全部已配置 Redis 实例读写权限的跳板」， **只能部署在受控内网**。暴露公网前必须在网关层自行加鉴权。凭据经
AES-GCM 加密存储，API 只回 `credentialPresence` 存在性标记、永不回明文。