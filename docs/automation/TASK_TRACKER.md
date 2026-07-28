# Desktop Automation Task Tracker

## 最终状态

Mihon Desktop parity 自动化已完成。本文件只记录维护边界；64 项 capability 状态仍以
`app-desktop/src/test/resources/parity/parity-manifest.json` 为唯一机器权威。

| 范围 | 状态 | 验收 |
|---|---|---|
| Test Mode lifecycle | 完成 | `--test-mode`、端口、headless、owner/close/reset |
| HTTP production wiring | 完成 | state/navigation/actions 与真实 ScreenModel/controller/DI owner 对接 |
| 错误与生命周期 | 完成 | unknown/stale/unavailable/partial/cancel/closed 与 typed feedback |
| Robot/client contracts | 完成 | `test-desktop:test` 27/27 |
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

## 屏幕录制权限边界

Test Mode 的 AWT Robot 截图服务、`POST /test/screenshot`、Robot 截图快捷方法和视觉回归客户端
已移除。`--screenshot-dir` 作为旧参数被忽略，截图端点固定返回 404。普通运行和 Test Mode 均
不再包含读取桌面屏幕像素的 Mihon 代码路径；仓库也没有麦克风或系统音频采集实现。

macOS 平台验收不再调用 `screencapture`，URI 和 Unsupported capture-affinity 通过进程、窗口
几何、production adapter、state/action 证据验收。Windows 的窗口隐私验收仍由外部测试宿主
捕获目标窗口，不把截图能力打入 Mihon，也不要求 macOS 用户授权录屏。

本次删除跨越 35 个文件，是同一权限能力的纵向收口：production 服务、HTTP/CLI 契约、测试
客户端、平台验收和维护文档必须同时移除，否则仍会留下可调用入口或误导性说明。主要回归风险
是 Windows 外部窗口隐私取证，因此该脚本保留原生目标窗口捕获，并仅将原 Mihon 内部反馈截图
替换为同一外部捕获机制；PowerShell runner 仍需在 Windows 门禁中执行。

## 不入库

- Windows 外部验收的一次性截图；
- agent 中间状态文件；
- 本机 TCC 数据库或权限授予状态。

完整版本、测试计数、产物哈希与环境限制见
`docs/superpowers/reports/2026-07-23-mihon-desktop-final-parity-verify.md`。
