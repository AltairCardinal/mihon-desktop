# ADR-0002: 平台接口边界

日期：2026-06-30

状态：Accepted

## 背景

Android 和 Desktop 都需要文件、通知、Crash、Web/Auth、更新器、浏览器、剪贴板、生命周期等平台能力。当前 Desktop 侧仍有部分能力散落在 UI、`Main.kt`、Swing/AWT 和硬编码路径中。

## 决策

平台能力必须先归属到接口边界，再接入具体平台实现。

| 能力 | Android 归属 | macOS/Windows 归属 |
| --- | --- | --- |
| 文件/目录 | 原版 Mihon 存储与权限模型 | Desktop platform adapter |
| 通知 | 原版 Mihon Notification 体系 | Desktop notification adapter |
| Crash/Debug 日志 | Android 平台目录和导出入口 | Desktop 日志目录、轮转、导出入口 |
| Web/Auth | 原版 Mihon WebView 和登录流程 | Desktop Web/Auth adapter，Windows 后续选型 |
| 更新器 | 原版 Mihon updater | Desktop updater adapter |
| 外部浏览器 | Android Intent | Desktop browser launcher adapter |
| 剪贴板 | Android ClipboardManager | Desktop clipboard adapter |
| 生命周期 | Android Application/Activity/WorkManager | Desktop runtime/lifecycle |

约束：

- Desktop UI 不直接调用 Swing/AWT、`java.awt.Desktop` 或硬编码平台路径。
- Android 不引入 Desktop runtime、scheduler、ClassLoader 或 stubs。
- 新增平台能力必须先更新本 ADR 或后续 ADR。

## 后果

正面：

- 平台能力有明确边界。
- Windows 发布工程可以逐步替换硬编码路径和 UI 中的桌面调用。
- Android 合并审查可以按表检查是否越界。

代价：

- 需要额外 adapter 层。
- 部分短期可用代码需要迁移出 UI。

## 验收

- roadmap Phase A 引用本接口边界。
- Phase D 的架构守护测试锁定 Desktop 路径债务和 Android 禁止项。
