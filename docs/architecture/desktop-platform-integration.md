# Desktop platform integration

Desktop entry points are registered through `DesktopAppModule`: the UI receives shared use cases and Desktop adapters from `DesktopUiDependencies`.  Platform actions enter through the single-instance broker, while share actions use `DesktopShareService`; neither path reinterprets domain data.

共享模块的 `commonMain` 只能声明共同适用的依赖；Android 的 Main dispatcher 必须归属 `androidMain`，不能通过协程 bundle 泄漏到 Desktop 的 JVM runtime。Desktop 的 production `Dispatchers.Main` 必须使用 Swing EDT。修复依赖归属时须比较 Android 的实际解析结果，保留其既有组件；不能给 Desktop 的 Android Handler stub 补方法来启用 Android dispatcher。

Main dispatcher 的平台接线测试应使用 Gradle 解析的实际 `jvmMain` runtime classpath，在不含 `coroutines-test` 的独立进程中执行 Main 与真实 `LibraryScreenModel` 的更新观察。`Dispatchers.setMain` 的状态测试只证明状态行为，不能证明正式运行时的调度器选择。正式包还须通过有库数据、更新任务活跃的正常 Library 初始化验收；空 profile 或 headless 冒烟不能替代这个启动路径。

User entry and failure matrix: About starts update checks; Updates displays shared updates; Security exposes lock/delay and reports capture limitations.  Unsupported widget, native-notification, telemetry, missing trust-root, verifier failure, cancellation, and unsupported OS all retain a visible unavailable/manual path rather than a successful-looking control.

Updates use `DesktopUpdateScreenModel` and `DesktopUpdateController` as an explicit state machine.  Downloads stay inside the update cache, validate redirect, size and checksum boundaries, and only offer automatic handoff after platform signature policy accepts the artifact.  A verifier failure, unsupported platform, missing trust root, or cancelled operation leaves a manual-update path instead of claiming installation succeeded.  The runtime owns the updater scope and waits for cancellation cleanup during application close.

Privacy adapters expose their capability rather than treating every operating system as Android.  `DesktopAppLock` consumes lock and delay settings.  `DesktopWindowPrivacy` reports supported, limited, failed, and unsupported capture protection.  Native notification-content controls and telemetry are unsupported until a real consumer exists; in-app feedback is not a substitute.  Desktop system widgets are unsupported: the Updates screen consumes shared `GetUpdates`, but no fake widget provider is created.  Android Glance widget behavior and lock privacy remain covered by the Android `presentation-widget` production test.

When adding an operating system, first add a platform adapter with a stable unsupported/limited failure result, then wire it through DI and a production-facing test.  Update the parity manifest with the fixed-main source, shared contract, Android and Desktop consumers, adapter, protection test, and any deviation.  Promote a candidate only after the applicable real OS behavior is accepted.  For updater support, publish canonical signed artifacts and trust configuration before enabling automatic handoff; rollback keeps the verified artifact available for manual installation and changes the capability back to manual-only if verification cannot be trusted.

New-OS maintenance sequence: define the capability result, add the adapter and DI consumer, cover the user-facing state and failure, validate the target OS, then update the manifest scope from candidate to accepted evidence.
