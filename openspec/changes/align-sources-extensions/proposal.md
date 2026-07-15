## Why

Mihon Desktop 的源浏览、扩展发现/安装/更新、安全校验和挑战登录仍存在与 Android 原版并行的业务实现，以及不断扩张的兼容 stub。Desktop 当初为快速交付重写了这些链路；除 ClassLoader、APK→JAR、文件目录和浏览器会话等真实平台差异外，其余状态与规则都应回归原版共享实现。

## What Changes

- 共享源列表、单源浏览、搜索、分页、空状态、错误和偏好 schema。
- 共享扩展发现、版本兼容、安全信任、安装/更新事务及回滚状态。
- 将 Desktop loader/installer 收敛为目录、ClassLoader、APK→JAR 和隔离 side effect。
- 实现 Desktop 浏览器登录与 Cookie 回传；FlareSolverr 只保留为显式可选后备。
- 删除无真实扩展调用证据的 compat stub，以及重复的搜索、版本与错误规则。
- 更新 parity 28–40、87 的实现证据与状态。

## Capabilities

### New Capabilities

- `source-extension-parity`：定义跨端源与扩展状态、安全、安装更新、挑战登录和 Desktop 平台适配边界。

### Modified Capabilities

无既有 OpenSpec capability 的需求发生变化。

## Impact

影响 `source-api`、domain/common、Android/Desktop 源与扩展实现、Desktop 兼容层、i18n、DI、导航、HTTP 集成测试和 Test Mode。不会机械复制 Android PackageInstaller 或 WebView，也不会删除已经有真实扩展和回归测试保护的 Desktop 产品能力。
