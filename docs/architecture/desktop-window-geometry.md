# Windows 主窗口状态恢复

## 固定契约与范围

- SOURCE：原 Main 主窗口以 1024×768 初始化，窗口隐私、锁定、阅读器和关闭协调器已经挂载在同一 Window 中。
- PROJECT_POLICY：Windows 用户移动或调整普通主窗口后正常退出，下次启动恢复尺寸和位置；最大化退出时恢复最大化，并保留还原后的普通窗口矩形。最小化不会作为启动状态。这里的尺寸指窗口大小，不涉及应用字体或系统显示缩放。
- PROJECT_POLICY：恢复最大化时，首次显示应直接呈现最大化窗口，不播放从普通矩形展开的启动过渡；后续用户主动最大化、还原仍遵循系统动画设置。
- PROJECT_POLICY：使用当前显示器的工作区域（扣除任务栏）。有效的负坐标显示器可恢复；尺寸超出当前工作区域时缩小至可见范围；原显示器移除或窗口完全离屏时回到主显示器并居中。恢复位置不要求外接屏标识永久不变。
- 没有 HTML adapter。入口是系统标题栏、窗口边缘及关闭按钮，反馈是下一次启动的实际窗口；恢复对话框和其他临时窗口不消费这份状态。

## 实现与维护

Main 使用 DesktopMainWindow 作为唯一主窗口外壳，内部继续消费 Compose WindowState，并绑定 DesktopWindowGeometryController。Windows adapter 观察真实 AWT 移动、缩放、显示和 placement 事件；普通矩形与最大化标志分开维护，最小化和全屏矩形不覆盖普通状态。变更事件只更新内存，关闭请求在应用协调器之前保存，composition 释放也保存，以覆盖 Test Mode 等正常退出路径。监听器按窗口生命周期释放，迟到事件不再写入。

Compose 的初始 WindowState 已在首次显示前包含恢复值，但 Windows 仍可能播放首次最大化过渡。恢复最大化时，在隐藏窗口完成首次 composition、绑定原生窗口时，通过窗口级 [DWMWA_TRANSITIONS_FORCEDISABLED](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute) 暂停过渡；收到 WINDOW_OPENED 后等待一次 DwmFlush，再恢复过渡。释放窗口时也清理此状态。不修改系统动画偏好，不用固定延时，也不以最大化矩形覆盖普通窗口记录。原生调用失败会记录 stderr 并继续正常显示，此时可能仍有系统过渡。

记录为一个 JSON 字符串，使用 DesktopAppPreferences.windowGeometry 和 Preference.appStateKey("desktop_window_geometry")。沿用现有 DesktopPreferenceStore 的同步 flush 和失败回滚边界，不新增数据库、DI 服务或导航。机器局部 app state 被现有备份及同步规则排除；隔离测试配置和正式用户配置各自保存。坐标单位为 AWT/Compose 的桌面逻辑坐标，不把物理像素或 Android dp 常量作为主窗口默认值来源。

首次启动、损坏 JSON、缺少字段和非正尺寸采用原初始状态。兼容未知 JSON 字段；不创建旧版迁移。保存失败保留原偏好并向现有 stderr 日志报告，不阻止应用的正常关闭。强制结束进程、断电以及不可写存储不保证保存最后状态。此能力目前只接入 Windows 主窗口。

## 定向验证

```powershell
python scripts/gradle-coordinator.py run --key window-native -- .\gradlew.bat :app-desktop:nativeWindowTest --tests "*DesktopWindowGeometryTest"
python scripts/gradle-coordinator.py run --key window-policy -- .\gradlew.bat :app-desktop:jvmTest --tests "*DesktopWindowGeometryPolicyTest" --tests "*DesktopBackupMachineLocalPreferenceTest"
```

nativeWindowTest 使用图形桌面和 native-window 标签，普通 jvmTest 排除此标签。DesktopWindowGeometryTest 打开隔离的 production 主窗口外壳，发送真实 AWT 事件后关闭并用相同本机存储重开，覆盖普通、最大化、最小化及正常矩形还原。策略测试覆盖多屏负坐标、离屏回退、任务栏工作区域、极端输入、损坏配置和保存失败。原生测试仅在 Windows 执行，不将跳过作为其他平台验收。

首次显示回归还记录 AWT SHOWING_CHANGED 时的真实矩形，以及委托真实 DwmSetWindowAttribute 的调用顺序：仅恢复最大化时在显示前关闭过渡，打开后恢复；同时覆盖原生接口失败时继续显示。该 DWM 属性只支持设置，不能用 DwmGetWindowAttribute 回读作为断言。测试保护 production 窗口接线和实际原生调用，主观动画观感仍需在体验包中观察。

体验包验收应进一步使用本轮真实发布目录中的 EXE、明确的 --test-mode/--test-profile 隔离配置及独立端口，执行系统窗口移动、大小、最大化和关闭重开；不得操作普通用户实例。
