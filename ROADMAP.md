# Mihon Desktop Roadmap

本文档是桌面版当前唯一 roadmap。所有阶段必须同时规划后端能力、UI 入口、用户反馈和测试覆盖。

## 当前状态

- 桌面主线：扩展兼容、书库、详情页、下载、更新、历史、阅读器、备份与测试模式已具备基础能力。
- 当前重点：稳定桌面自动化测试系统，避免 HTTP API 返回成功但 UI 未实际变化。
- 不再规划：追踪服务。桌面端 OAuth/WebView 依赖重，且跨语言漫画识别不可靠。

## 质量门禁

每个功能变更必须满足：

- Red：先写会失败的测试，并确认失败原因正确。
- Green：最小实现让测试通过。
- Refactor：清理后重跑相关测试。
- UI 可达：功能必须有按钮、菜单、快捷键或自动化 API 入口。
- 反馈明确：操作完成、失败、危险操作确认必须能被用户看到。

桌面迭代完成前至少运行：

```bash
./gradlew :app-desktop:jvmTest :test-desktop:test
```

发布或部署桌面应用时必须使用：

```bash
./scripts/build-desktop.sh
```

## P0：稳定性与测试基础

| 项目 | 状态 | 用户可见结果 | 验收 |
|---|---|---|---|
| 测试模式导航一致性 | 已修复 | HTTP 导航到漫画详情/阅读器不会被 tab 导航请求覆盖 | `TestNavigationControllerTest` 通过 |
| HTTP JSON 解析 | 已修复 | 自动化动作支持 URL、冒号、逗号等真实参数 | `TestHttpServerJsonTest` 通过 |
| 测试编译 | 已修复 | `app-desktop:jvmTest` 可进入执行阶段 | Gradle 编译测试通过 |
| 文档资产瘦身 | 已修复 | 仓库不再提交 48MB 验证截图 | `docs/automation/screenshots/` 不入库 |

## P1：自动化 API 可信化

| 项目 | 目标 | UI/API 入口 | 测试要求 |
|---|---|---|---|
| 真实状态回读 | `/test/state` 返回真实当前屏幕、下载状态、阅读器状态 | HTTP API | Ktor route 测试 + Robot 测试 |
| 导航结果确认 | `/test/navigate/{screen}` 仅在 UI 消费完成后返回成功 | HTTP API | 成功、未知屏幕、嵌套屏幕三类测试 |
| 截图稳定性 | 截图失败返回明确错误，不产生假成功 | `/test/screenshot` | headless 与 UI 模式各一条测试 |
| Robot 场景覆盖 | Library/Reader/Settings/Browse/Downloads/Updates/History | `test-desktop` Robot | 不允许“成功或失败都有效”的断言 |

## P2：阅读器体验

| 项目 | 目标 | UI 入口 | 测试要求 |
|---|---|---|---|
| 阅读器关闭一致性 | 关闭按钮、Esc、HTTP close 都返回上一屏 | 阅读器顶栏/快捷键/API | 导航栈回归测试 |
| 页码同步 | UI 翻页与 `/test/reader/state` 页码一致 | 阅读器翻页区域/API | 单页、双页、Webtoon 模式 |
| 章节边界 | 首页/尾页切章行为一致 | 阅读器点击区域/底栏 | ReaderNavigator 单元测试 + UI 契约测试 |

## P3：扩展与网络

| 项目 | 目标 | UI 入口 | 测试要求 |
|---|---|---|---|
| 扩展仓库 | 添加、删除、刷新仓库 | Settings > Browse | MockWebServer 成功/空/错误 JSON |
| 扩展安装 | 安装、更新、禁用扩展 | Browse > Extensions | 下载失败、版本比较、路由测试 |
| Cloudflare 辅助 | 手动导入 cookie 并用于请求 | Settings > Advanced | Cookie 持久化与拦截器测试 |
| 源页面解析 | 真实响应解析到页面列表 | 阅读章节 | MockWebServer 覆盖 403/429/500/ malformed |

## 维护规则

- 不提交运行截图、一次性验证报告、agent 中间状态文件。
- 大型二进制产物只放 CI artifact 或本机临时目录。
- `TEST_COVERAGE_REPORT.md` 只记录当前可验证事实，不写“已通过”但实际已删除的测试。
