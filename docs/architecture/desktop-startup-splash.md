# Windows 启动提示

## 契约与范围

- SOURCE：Windows 的 jpackage EXE 启动 JVM 后才执行 Main、配置选择、单实例协调、DI 和 Compose；此前没有可见反馈。
- PROJECT_POLICY：打包的 Windows EXE 在 Main 入口、配置/DI/Compose 初始化前显示轻量 AWT 启动窗口。必须先选定鼠标所在显示器、设置工作区内的居中位置，再首次显示；副屏启动过程中不能先在主屏闪现。画面为 512×256 逻辑像素、无标题栏、深色底、Mihon 字样、“正在启动 · Starting…”和底部强调色条，复用既有 PNG；颜色来自默认暗色主题背景 `#1B1B1F`、主色 `#B0C6FF`。这是静态等待提示，不显示虚构进度。
- PROJECT_POLICY：正常和恢复最大化启动均显示提示；不新增可交互按钮、不更改窗口恢复或用户偏好。仅承诺 Windows 打包 EXE，不扩展到 Android、macOS 或 Gradle 开发运行。
- 没有 HTML_ADAPTER。验收必须运行真实打包 EXE，原型或系统 Java 不能代替。

## 接线与边界

沿用 Compose 原生打包链，`nativeDistributions.appResourcesRootDir` 将 `src/main/appResources/windows/startup-splash.png` 复制到 Windows 的 `app/resources/`。Windows JVM 参数为 `-Dmihon.desktop.startupSplash=$APPDIR/resources/startup-splash.png`，由 jpackage 展开安装目录，支持路径中的空格和移动整个未打包目录。不增加外部启动器、独立进程或运行时字体依赖。

Main 调用 `showDesktopStartupSplash`，在 EDT 上先通过 `MouseInfo` 选择鼠标所在设备的 `GraphicsConfiguration`，查询不到时使用默认设备；使用该配置构造尚不可见的 `JWindow`，扣除任务栏等 inset 后以 AWT 逻辑坐标居中，再设为可见。负坐标与不同设备缩放由 AWT 配置承接，不手动混合 Win32 物理像素。窗口不请求焦点，不追随启动后的鼠标移动；主窗口仍按既有持久化位置恢复。

本进程的其他 AWT 窗口打开时，启动窗口在 opening/geometry 回调之后释放自己与全局监听器，不等待网络或书架数据加载。Main 的 `finally` 同时兜底单实例转发、取消、启动失败和正常退出的清理。`--headless`、AWT headless、非 Windows 及未设置图片属性的开发运行不创建窗口。图片缺失/损坏或创建失败时记录错误并继续启动，完整产物仍必须保留 `app/resources/`。

不再使用 JVM `-splash`，也不再枚举/搬动 `JavaSplash`。原因是 JDK 21 的 Windows 实现使用主屏尺寸决定首次位置，进入 Main 后修正已经晚于首次显示。新的反馈需等待 JVM/AWT 就绪，不能覆盖 JVM 初始化阶段；冷启动延迟需实测，不承诺零延迟或所有机器都在一秒内出现。既有主窗口最大化恢复和 DWM cloak 流程保持不变。

源码 PNG 是简单排版资产，使用 Segoe UI Light 与微软雅黑渲染，不随包分发字体。修改样式时保持可读性并检查原生截图。公共说明须等待用户对具体候选验收后再更新。

## 定向验收

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:MIHON_SPLASH_EXE = '<本轮 Final unpacked EXE 的实际绝对路径>'
$env:MIHON_SPLASH_SCREEN = 'secondary'
python scripts/tests/windows-startup-splash-test.py
```

测试打开临时 Test Mode 配置，以本次 launcher PID 的进程树与精确 EXE 路径共同限定操作对象。每次观察到可见启动窗口都断言其位于启动屏且居中，并拒绝任何可见 `JavaSplash`；覆盖普通及最大化恢复、提示不残留、真实健康接口及正常关闭。`MIHON_SPLASH_SCREEN=secondary` 要求实际副屏，测试临时将鼠标移到副屏，结束时仅在鼠标仍停留于测试位置的情况下恢复原位置；未设置则沿用当前鼠标屏幕。已有用户实例和配置不作为测试对象。本地提示出现上限为 1 秒，仅表示当前机器回归阈值，不是跨硬件 SLA。轮询只能证明已观察帧的位置，定位先于可见的生产调用顺序另由定向行为测试覆盖。

可选设置 `MIHON_SPLASH_TRACE` 保存逐次枚举记录，设置 `MIHON_SPLASH_CAPTURE` 保存原生提示截图（此选项需要 Pillow）。测试不扫描源码或复制 launcher 配置；删除打包参数或资源后应在实际 EXE 验收中失败。仍须观察原生截图及主窗口恢复观感。测试通过、构建成功或提交均不代表用户已验收。

参考：[JWindow 的显示设备构造参数](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/JWindow.html)、[JVM SplashScreen 位置限制](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/SplashScreen.html)、[IntelliJ 先定位后显示的窗口实现](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/platform/ide/bootstrap/Splash.kt)、[Compose 平台资源打包](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html#adding-files-to-packaged-application)。
