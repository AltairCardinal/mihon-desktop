# Windows 启动提示

## 契约与范围

- SOURCE：Windows 的 jpackage EXE 启动 JVM 后才执行 Main、配置选择、单实例协调、DI 和 Compose；此前没有可见反馈。
- PROJECT_POLICY：打包的 Windows EXE 使用 JVM 原生启动画面，在应用初始化前尽早显示。画面为 512×256、无标题栏、深色底、Mihon 字样、“正在启动 · Starting…”和底部强调色条，参考用户提供的 NeeView 启动卡片结构；颜色来自默认暗色主题背景 `#1B1B1F`、主色 `#B0C6FF`。这是一张静态等待提示，不显示虚构的百分比或下载进度。
- PROJECT_POLICY：正常和恢复最大化启动均显示提示；不新增可交互按钮、不更改窗口恢复或用户偏好。仅承诺 Windows 打包 EXE，不扩展到 Android、macOS 或 Gradle 开发运行。
- 没有 HTML_ADAPTER。验收必须运行真实打包 EXE，原型或系统 Java 不能代替。

## 接线与边界

沿用 Compose 原生打包链，`nativeDistributions.appResourcesRootDir` 将 `src/main/appResources/windows/startup-splash.png` 复制到 Windows 的 `app/resources/`。Windows JVM 参数为 `-splash:$APPDIR/resources/startup-splash.png`，由 jpackage 在启动时展开安装目录，支持路径中的空格和移动整个未打包目录。不增加外部启动器、独立进程或运行时字体依赖。

JVM 在 Main 执行前解码并显示画面；第一次 AWT/Swing 窗口变为可见时自动关闭，进程结束也随之销毁。它不等待网络请求或书架数据加载完成。最大化恢复使用 DWM cloak，原生窗口可见与解除 cloak 之间仍可能有短暂空隙；这份提示不改变既有首帧恢复流程，不承诺全程无空隙。提示出现速度取决于系统进程创建、磁盘和杀毒检查，不能保证物理意义上的零延迟。单实例转发、无界面运行或失败退出也可能短暂显示原生画面，不为这些路径创建长期窗口。缺失/损坏图片时 JVM 可能省略提示；完整产物必须保留 `app/resources/`。

源码 PNG 是简单排版资产，使用 Segoe UI Light 与微软雅黑渲染，不随包分发字体。修改样式时保持可读性并检查原生截图。公共说明须等待用户对具体候选验收后再更新。

## 定向验收

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:MIHON_SPLASH_EXE = '<本轮 Final unpacked EXE 的实际绝对路径>'
python scripts/tests/windows-startup-splash-test.py
```

测试打开临时 Test Mode 配置，以本次 launcher PID 的进程树与精确 EXE 路径共同限定操作对象。记录真实 JavaSplash 原生窗口早于主窗口出现、正常及最大化恢复、提示不残留、真实健康接口及正常关闭。已有用户实例和配置不作为测试对象。本地提示出现上限为 1 秒，仅表示当前机器的回归阈值，不是所有硬件的启动 SLA。

可选设置 `MIHON_SPLASH_TRACE` 保存逐次枚举记录，设置 `MIHON_SPLASH_CAPTURE` 保存原生提示截图（此选项需要 Pillow）。测试不扫描源码或复制 launcher 配置；删除打包参数或资源后应在实际 EXE 验收中失败。仍须观察原生截图及主窗口恢复观感。测试通过、构建成功或提交均不代表用户已验收。

官方依据：[JVM SplashScreen 生命周期](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/SplashScreen.html)、[jpackage 原生启动画面参数](https://docs.oracle.com/en/java/javase/21/jpackage/support-application-features.html)、[Compose 平台资源打包](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html#adding-files-to-packaged-application)。
