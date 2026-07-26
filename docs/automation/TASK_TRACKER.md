# Desktop Automation Task Tracker

## 当前完成

- 测试模式启动参数：`--test-mode`、`--test-http-port`、`--headless`、`--screenshot-dir`
- Ktor HTTP server：health/state/screens/navigate/action/reader/reset/history/screenshot
- 导航 pending 状态拆分：tab、screen、reader、pop 独立消费
- JSON body 标准解析：支持 URL、冒号、逗号
- Robot 客户端模块：`test-desktop`
- 回归测试：`TestNavigationControllerTest`、`TestHttpServerJsonTest`

## 待办

| 优先级 | 项目 | 验收 |
|---|---|---|
| P0 | 移除所有宽松 smoke 断言 | 不再出现“成功或失败都有效”的测试 |
| P0 | `/test/state` 接入真实 UI 状态 | 导航后状态与当前屏幕一致 |
| P1 | 为 `/test/navigate` 增加 route-level 测试 | tab、嵌套 screen、未知 screen 均覆盖 |
| P1 | Reader Robot 端到端场景 | 打开章节、翻页、关闭均验证状态 |
| P2 | 截图测试稳定化 | 截图失败时返回明确错误，不假成功 |
| P2 | CI artifact 归档截图 | 截图不提交仓库，仅上传 artifact |

## Desktop 对齐 Test Mode 计划

以下均为规划状态，不代表已经实现。单项只有在对应 API、真实 UI 状态断言和失败路径测试完成后，才能在 parity manifest 中进入 `VERIFIED`。

| 阶段 | 计划场景 | Test Mode 验收边界 | 状态 |
|---|---|---|---|
| 行为刻画 | 书库、浏览、阅读器、下载、更新、备份关键入口 | 导航后 `/test/state` 与真实 UI 一致，并覆盖空、加载、错误状态 | 计划中 |
| 共享实现 | 共享 use case/repository 替换 Desktop 重复业务 | 同一 fixture 在共享层与 Desktop wiring 得到一致结果 | 计划中 |
| UI Wiring | 导航、DI、HTTP、数据库、后台任务接点 | route/action 成功与未知输入、权限缺失、数据缺失均有明确响应 | 计划中 |
| Desktop 产品保护 | 作者、Upcoming、双页、自动滚动、APK 转换 | 现有能力可从用户入口触发，重构后行为不回退 | 计划中 |
| 端到端验证 | 代表性用户旅程与恢复场景 | 重启/重试后状态可解释，截图只作为辅助证据而非宽松断言 | 计划中 |

## 不再入库

- `docs/automation/screenshots/`
- 一次性验证报告
- agent 中间状态文件
- 本机构建产物

## Final parity runner 状态

- Task 171 fixed-EXE runner：已完成。
- 入口：`./scripts/desktop-final-parity-test.sh`。
- 产物边界：只接受 `scripts/build-desktop.sh evidence` 的固定未打包 EXE 与 Task151 provenance sidecar；当前 source identity 或应用哈希不匹配时在启动前 fail-closed。
- 运行边界：仅 headless Test Mode；启动前拒绝已有 health owner，启动后同时验证 health 与本次 PID，并精确 teardown。
- 汇总边界：`test-desktop` 汇总对照既有 coverage inventory，必须报告 13/13 families、5/5 permanent protections、64/64 capability IDs 且 `unmapped=0`。
- 场景 wiring gap 仍由 Task 172–177 负责；Task 171 不会把这些 gap 误标为已完成。
