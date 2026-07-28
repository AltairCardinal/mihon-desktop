# Desktop Automation Task Tracker

## 最终状态

Mihon Desktop parity 自动化已完成。本文件只记录维护边界；64 项 capability 状态仍以
`app-desktop/src/test/resources/parity/parity-manifest.json` 为唯一机器权威。

| 范围 | 状态 | 验收 |
|---|---|---|
| Test Mode lifecycle | 完成 | `--test-mode`、端口、headless、owner/close/reset |
| HTTP production wiring | 完成 | state/navigation/actions 与真实 ScreenModel/controller/DI owner 对接 |
| 错误与生命周期 | 完成 | unknown/stale/unavailable/partial/cancel/closed 与 typed feedback |
| Robot/client contracts | 完成 | `test-desktop:test` 28/28 |
| 场景族 | 完成 | 13/13 |
| Desktop 永久保护 | 完成 | 5/5 |
| Capability mapping | 完成 | 64/64，`unmapped=0` |
| Windows runtime | 完成 | 固定未打包 EXE，版本 `0.11.14.51.19a55d7` |
| macOS runtime | 完成 | 本轮生成 app bundle，同版本 `0.11.14.51.19a55d7` |

## 13 个场景族

- library；
- manga detail；
- browse/global search/source login；
- extensions；
- reader；
- downloads；
- updates/upcoming；
- history；
- migration；
- backup/restore；
- settings/platform；
- tracking；
- about。

五项永久保护为 Authors entry、Upcoming、dual-page、auto-scroll、APK-to-JAR。它们保持
Desktop 产品零回退，但不冒充 64 项中的普通 capability evidence。

## Final parity runner

- 入口：`./scripts/desktop-final-parity-test.sh`。
- provenance：只接受 `scripts/build-desktop.sh evidence` 生成并封存的产物；source identity
  或 artifact tree 不匹配时在启动前 fail-closed。
- Windows：启动固定未打包 EXE。
- macOS：通过 `MIHON_FINAL_PARITY_EXE` 指向 app bundle 内可执行文件，并以
  `MIHON_FINAL_PARITY_PROVENANCE_COMMAND` 验证整个 bundle provenance。
- lifecycle：启动前拒绝已有 health owner；启动后同时验证 health 与本次 PID；结束时精确
  teardown。
- 汇总：必须精确报告 13/13 families、5/5 permanent protections、64/64 capability IDs、
  `unmapped=0`，任一 FAIL 均使 runner 非零退出。

## 截图权限边界

Test Mode 仍暴露 `POST /test/screenshot`，由 `ScreenshotService` 使用 AWT Robot 捕获屏幕。
macOS 实测中，普通 `--headless` 只预检输入监听，`--test-mode --headless` 会额外预检
ScreenCapture 权限，即使 final parity 客户端未调用截图端点。仓库没有麦克风或系统音频采集
实现；macOS 的“屏幕与系统音频录制”是权限类别名称。

最终 Windows/macOS 场景不依赖截图；macOS 在权限拒绝时仍 13/13、5/5、64/64。用户已明确
认为产品不应请求或保留该能力，因此维护者不得把授权录屏加入正常启动要求，也不得用截图成功
替代 production state/action 断言。本轮 parity closure 只记录该边界，不新增 roadmap 外任务。

## 不入库

- `docs/automation/screenshots/`；
- 本机构建产物或一次性截图；
- agent 中间状态文件；
- 本机 TCC 数据库或权限授予状态。

完整版本、测试计数、产物哈希与环境限制见
`docs/superpowers/reports/2026-07-23-mihon-desktop-final-parity-verify.md`。
