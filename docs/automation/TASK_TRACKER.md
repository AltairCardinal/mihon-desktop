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

## 不再入库

- `docs/automation/screenshots/`
- 一次性验证报告
- agent 中间状态文件
- 本机构建产物
