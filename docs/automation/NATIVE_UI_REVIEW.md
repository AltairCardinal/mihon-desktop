# 双端原生 UI 审阅流程

本流程让审阅者在 Android 和 Windows 上操作生产 Compose 界面，修改直接落在生产组件中。首个支持的页面是同步面板；其他页面需按下文方式逐个接入。HTML 原型仍可用于早期流程讨论，但不能作为原生尺寸、焦点或平台行为的验收证据。

## 启动

在仓库根目录打开 Windows PowerShell。首次编译可能较慢，之后保持开发窗口开启；`--auto` 会在保存源码后自动编译并热重载 Desktop：

```powershell
.\gradlew.bat :app-desktop:hotRunJvm --mainClass=mihon.desktop.sync.SyncUiReviewKt --auto
```

`hotRunJvm` 是仓库当前 Compose 插件生成的任务。它运行独立审阅窗口，不启动 Desktop 生产服务、资料库或外部请求。首次运行会由 Gradle 下载 JetBrains Runtime 21；境外依赖不可达时按仓库代理规则配置 Gradle JVM 代理。若热重载环境不可用，可在 IDE 中运行 `mihon.desktop.sync.SyncUiReviewKt.main` 进行普通预览；这时修改后需重新运行。窗口默认使用生产 Desktop 默认主题的系统明暗配色和生产同步弹层。

Android 使用 **Debug** 构建。用 Android Studio 把 `app` 的 Debug 变体运行到模拟器，点击应用列表中的 **Mihon UI 审阅**；也可在 Debug 应用安装后启动：

```powershell
& 'D:\Android\Sdk\platform-tools\adb.exe' -s <设备序列号> shell am start -n app.mihon.desktop.fork.dev/eu.kanade.tachiyomi.data.sync.SyncUiReviewActivity
```

先以 `adb devices -l` 核对设备序列号。调试入口只打进 Debug 构建，使用 Android 产品的 `AdaptiveSheet` 和同步面板组件。Android Studio 的 Compose Preview 可辅助查看组件，但完整手势、返回、输入法和系统窗口效果应在模拟器或真机运行时确认。上述命令只启动已经安装的 Debug 应用，不构建或安装 APK。

## 审阅一次变更

在两端选择同一个场景 ID：`disconnected`（未连接）、`setup`（首次设置）、`connected`（已连接）、`settings`（设置）、`progress`（同步进行中）。点场景按钮会重建本端的本地样本；关闭弹层后可点同一按钮重新打开。两端状态相互独立，不交换真实数据。

反馈时给出平台、场景 ID、窗口或设备尺寸、系统明暗/字号、操作路径和看到的结果。例如：“Android，`settings`，320dp 宽、200% 字号；滚到设备名称后，保存按钮被键盘遮住”。Agent 应先在相同条件下复现，再修改生产 Compose 组件或对应平台容器，并在两端重新查看受影响状态。

同步面板内容来自 [`SyncPanelContent`](../../presentation-sync/src/commonMain/kotlin/mihon/presentation/sync/SyncPanelContent.kt)。Android/Windows 审阅入口分别调用与正式入口相同的 `AndroidSyncPanelSheet`/`DesktopSyncPanelSheet`，因此该页面的内容和原生弹层不是另一份演示实现。两端默认主题遵循各自的产品配色路径。Android 审阅使用独立 Debug Activity，正式 `MainActivity` 的状态栏、导航栏、沉浸式设置可能造成外围差异；应用内其他主题、系统级权限、真机输入法及实际资料库内容仍需在正式产品入口核验。

场景状态由 [`SyncReviewPanel`](../../presentation-sync/src/commonMain/kotlin/mihon/presentation/sync/SyncReviewPanel.kt) 在内存中维护。导航、设置和进度暂停/继续可以交互；授权、真实同步、剪贴板、外链、文件及危险动作不执行。点击这类按钮不能用来判断对应业务链路已通过。关闭窗口或进程后样本重置，不写入偏好或资料库。

## 扩展到其他页面

每新增一页，先找生产 Composable、真实平台容器和依赖入口；优先用固定状态注入已有组件，不复制页面。用稳定场景 ID 定义几种有审阅价值的状态，在 Android Debug 入口和独立 Desktop 入口复用同一场景语义。平台特有页面分别设置场景，但仍使用各自生产组件。不要为了接入预览而提前重构全部 UI。

行为变化按仓库 TDD 规则先写失败的真实组件或入口测试。测试应点击审阅入口和生产组件，验证导航、状态和反馈；再做实现、聚焦测试及相关模块验证。页面定稿时回到正常 Android/Desktop 产品入口，并完成项目要求的原生、构建和运行验收。预览通过不能替代这些检查。

当前同步试点的聚焦验证命令：

```powershell
.\gradlew.bat :presentation-sync:jvmTest --tests 'mihon.presentation.sync.SyncReviewPanelTest'
.\gradlew.bat :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.data.sync.SyncUiReviewActivityTest'
.\gradlew.bat :app-desktop:jvmTest --tests 'mihon.desktop.sync.SyncUiReviewTest'
```

同一 worktree 的重型 Gradle 命令必须按仓库要求通过 `scripts/gradle-coordinator.py` 串行执行；上面的三行只列出被协调器包装的任务部分。Debug 专属测试位于 `app/src/testDebug`，正式 Release 不含审阅 Activity；若 Release 编译开始引用 Debug 类，应视为接入错误。

本批次涉及共享场景、两端原生入口、测试、工具链配置与维护说明，共跨越 13 个文件。它们共同构成“选择同一场景 → 操作生产界面 → 修改并复验”的可交付链路。主要风险是调试代码进入正式 Android 构建、弹层提取影响原入口，以及首次热重载的 JBR 下载；分别以 Debug 源集/Release 编译、两端原入口集成测试、Gradle 工具链解析和实际窗口启动验证。网络或工具链失败时应保留普通 IDE 运行入口并报告热重载未验证，不将预览测试通过写成正式运行验收。
