# Desktop Test Coverage Report

## 当前覆盖

| 区域 | 覆盖状态 | 代表测试 |
|---|---|---|
| 导航 pending 状态 | 已覆盖 | `TestNavigationControllerTest` |
| HTTP JSON body 解析 | 已覆盖 | `TestHttpServerJsonTest` |
| Screen/Tab 类型契约 | 已覆盖 | `ScreenInstantiationSmokeTest`、`NavigationContractTest` |
| 下载队列核心逻辑 | 已覆盖 | `DownloadManagerTest`、`DownloadManagerReorderTest`、`ParallelDownloadLimitTest` |
| 阅读器纯逻辑 | 已覆盖 | `ReaderNavigatorTest`、`ReaderKeyboardActionTest`、`VirtualPageListTest`、`ZoomStateTest` |
| 书库与详情 ScreenModel | 已覆盖 | `LibraryScreenModelTest`、`MangaDetailScreenModelTest` |
| Robot 客户端基础结构 | 已覆盖 | `test-desktop:test` |

## 已发现并修复的覆盖问题

- `ReaderScenarioSmokeTestSuite` 与 `CoreScenarioSmokeTestSuite` 中同名测试类冲突，导致测试编译失败。
- Ktor client suspend API 在非协程测试中直接调用，导致测试编译失败。
- 导航 pending 状态共用 `clearPendingNavigation()`，tab 消费可能清掉 screen 请求。
- reader pending screen 被消费后未清理，可能重复打开阅读器。
- HTTP body 使用手写 split 解析，无法处理真实 URL 和带标点标题。

## 缺口

| 缺口 | 风险 | 下一步 |
|---|---|---|
| HTTP route-level 测试不足 | API 可能返回成功但 UI 未变化 | 为 `testHttpServer()` 增加 Ktor test host 测试 |
| 被删除的网络/扩展测试未恢复 | Cloudflare、扩展安装、源页面解析回退风险 | 恢复 MockWebServer 覆盖 |
| 部分 smoke 测试断言过宽 | 测试无法发现真实失败 | 收紧断言，失败必须可定位 |
| 截图验证无 CI artifact 流程 | 视觉回归不可追溯 | CI 上传截图，不提交到 git |

## 合入门槛

```bash
./gradlew :app-desktop:jvmTest :test-desktop:test
```

涉及 HTTP/API 的变更还必须包含 MockWebServer 或 Ktor route 测试。
