# ADR-0001: 产品基线与平台边界

日期：2026-06-30

状态：Accepted

## 背景

Mihon 当前同时存在 Android 原版应用和 `app-desktop` Compose Desktop 应用。审计确认 `app-desktop` 已验证了大量桌面功能，但也包含 Desktop UI、Desktop DI、手动 scheduler、ClassLoader fallback、Android stubs、Swing/AWT 等桌面专属实现和技术债。

如果把 `app-desktop` 当成 Android 新基线，会把这些债务带入 Android 主线。反过来，如果 Windows/macOS 版本重写原版 Android UI，也会丢失当前 `app-desktop` 已完成的桌面能力。

## 决策

- Android 以原版 Mihon `app/` 为产品基线。
- macOS / Windows 以当前 `app-desktop` 为产品基线。
- Android 不直接复用 `app-desktop` UI、DI、scheduler、ClassLoader、Android stubs、Swing/AWT 交互或桌面路径策略。
- `app-desktop` 独有功能回流 Android 时，必须拆成四类资产后再合并：
  - 用户功能规格：入口、状态、反馈、边界。
  - 共享业务逻辑：domain、data、source、backup、core 或原版 Mihon 已有 UseCase。
  - Android 平台实现：原版 Mihon ScreenModel、DI、WorkManager、Notification、WebView、权限模型。
  - 测试资产：Android 单元测试、DI wiring、导航/集成测试、跨端契约测试。
- 可共享范围优先为 domain、data、source、backup、纯算法、功能规格和测试资产。
- 禁止共享范围包括 Desktop UI、Desktop DI、Desktop scheduler、Desktop ClassLoader、Android stubs、Swing/AWT、macOS/Windows 打包脚本和桌面路径策略。

## 后果

正面：

- Android 主线保持原版 Mihon 架构，避免引入 Desktop 临时方案。
- macOS/Windows 可以继续利用 `app-desktop` 已有功能。
- 跨平台复用从“复制 UI”转为“共享业务契约和测试”。

代价：

- Android 与 Desktop UI 需要分别维护。
- Desktop 独有功能回流 Android 时需要重新接入 Android 平台能力。
- 每个 Android 合并项必须填写资产拆分清单，评审成本上升。

## 验收

- roadmap 固定本产品路线。
- Android 合并模板必须引用本 ADR。
- 架构守护测试禁止 Android 主源码引入 Desktop runtime、AWT、Swing。
