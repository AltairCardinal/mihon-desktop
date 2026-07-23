# Mihon Desktop 设置、外观、无障碍与许可验收报告

## 结论

父 roadmap Task 5B 及子计划 Tasks 1–20/20A–20F 已完成。固定原版权威仍为
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`；当前 `app/` 仅作为 Android
consumer 验收，不作为原版实现依据。

whole-change 唯一修复复审结果为 Critical/Important/Minor `0/0/0`。Desktop IDs
90/91/94 为 VERIFIED；ID88 按实际证据保持 CHARACTERIZED，不把尚未抽取为
commonMain 的 accessibility core 虚报为跨端共享完成。

## 【功能特性】

- 设置搜索：Desktop 可从 More 进入搜索，按 shared policy 过滤/排序，导航到目标
  Screen 并一次性滚动、高亮稳定 anchor；Desktop 独有设置仍保留。
- 外观：Android 与 Desktop 共同消费 shared ThemeMode/AppTheme/default/codec/palette；
  Desktop 保留 grid、静态主题与 AMOLED，Android 保留动态 Monet adapter。
- 开源许可：Desktop 构建生成真实依赖 metadata，About 可进入 192 项许可列表与详情；
  Android 与 Desktop 使用同一首许可证/blank 规则。
- 无障碍与键盘：Desktop 设置控件具有唯一 action、Role、state/disabled semantics、
  焦点顺序以及 Enter/NumPadEnter/Space exact-once 行为。
- Desktop 独有 updater、诊断、仓库、Tracking、Security、Advanced、Backup 等能力未删除
  或降级。

## 【BUG 修复】

- Android 启动崩溃：`app` 与 `presentation-theme` 的同包同名 `ThemeMode.kt` 生成两份
  `ThemeModeKt`，运行时引发 `NoSuchMethodError`。Android adapter 现使用唯一
  `AndroidThemeModeKt` facade；APK 中两类 facade 与两个目标方法各 1 份。
- Windows updater 测试偶发失败：测试用 `java SourceFile.java` helper 的 PID 启动等待
  改为命名的有界 10 秒；production timeout、取消、强制终止与退出等待保持不变。
- 测试打开真实资源管理器：
  - 设置目录 opener 在 Gradle test worker 中禁止默认 `Desktop.open`。
  - share save 测试 factory 显式注入 no-op reveal，AWT reveal adapter 也在 test worker
    中禁止真实 `Desktop.open`。
  - 最终 Desktop full-tests 全程及结束后 `app-desktop/build/test-tmp` Explorer 窗口为 0。
- 测试打开 `repo.example`：ExtensionRepo 使用可注入 URL opener；测试使用 `.invalid`
  地址与 recording fake，系统浏览器副作用为 0。

## 自动化与平台验收

| 范围 | 结果 |
|---|---|
| whole-change 修复复审 | APPROVED `0/0/0`，focused `50/50` |
| Spotless | 通过 |
| domain/data JVM | 通过 |
| Desktop full-tests（Windows） | `2102/2102`，skipped 3，failures/errors 0 |
| Desktop full-tests（macOS） | 2098 tests，failures/errors 0，skipped 1 |
| macOS 设置相关选定测试 | `200/200` |
| Android facade/shared/search/license focused | `8/8` |
| Android SettingsSearchNavigationUiTest | 实际执行 `5/5`，10.495 秒 |
| Android API36 production launch | MainActivity top-resumed；FATAL/NoSuchMethodError 0 |
| Windows `test-tmp` Explorer 副作用 | 0 |

Android 首轮全量单元测试出现三个不同的单项异步时序失败；三个失败类分别 focused
合计 `30/30` 通过，两次 full 分别为 `190/192` 与 `191/192`。因失败项每轮漂移且
隔离均通过，未进行随机无界重跑；API36 production/instrumentation 验收另行通过。

## Windows

- 完整版本：`0.11.14.45.5a02aed`
- 固定未打包 EXE：
  `D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`
- `scripts/build-desktop.sh` 完成测试、`createDistributable`、EXE 新鲜度和真实窗口标题
  校验；窗口标题包含完整版本。

## macOS

- OS：macOS 14.8.4，x86_64
- 精确构建源：`5a02aedeabb05a556e97f2b7fef2bdb4e0528725`（BUILD44）
- 部署版本：`0.11.14.45.5a02aed`
- 部署路径：`/Applications/Mihon Desktop.app`
- TestMode health/state/screens/screenshot/More/General 共 8 个端点成功，截图为
  1792×1120。
- 远端原仓库存在用户脏改，构建使用 `/tmp` 隔离仓库，未覆盖原仓库。

## Android

- AVD：`mihon-api36-task20`
- 设备：Android 16 / API 36 / x86_64，最终为 RUNNING_UNLOCKED
- Theme/default/AMOLED、App language、More→Settings/Search、About→真实 licenses
  →首项详情均有 UIAutomator/UI dump/截图与 logcat 证据。
- 修复后 APK 符号证据、production log、instrumentation 与截图位于本地
  `.test-tmp/task20f-*`。

## 明确边界

- macOS 当前远程显示处于锁屏状态且未授予辅助访问；AX tree、VoiceOver 朗读顺序和
  真实键盘交互未自动宣称通过。Compose semantics/keyboard 自动化已通过。
- Android 未执行 TalkBack 人工朗读顺序；语言列表可达，但未逐语言完成重启与全资源巡检。
- WSL Ubuntu 24.04 可用但无 Java、无无交互 sudo。便携 JDK 下载因吞吐过低停止并清理；
  Linux paths/URI/theme/search/accessibility adapter 由跨平台 JVM 合同覆盖，原生 WSL
  运行保留为环境边界。

## 【验收清单】

- [x] More → Settings Search → 选择结果 → 目标 Screen 滚动并高亮一次
- [x] Appearance → System/Light/Dark、Default、AMOLED → 状态正确持久化与渲染
- [x] About → Open source licenses → 首项详情 → 显示真实许可正文
- [x] Desktop 键盘 Enter/NumPadEnter/Space → 支持的 action 仅触发一次
- [x] Windows 固定 EXE → 窗口标题包含 `0.11.14.45.5a02aed`
- [x] macOS `/Applications/Mihon Desktop.app` → TestMode health/state/screens 可用
- [x] Android API36 → production 冷启动无崩溃，搜索 instrumentation `5/5`
- [ ] 人工解锁 macOS 并授权辅助访问 → VoiceOver/AX/真实键盘巡检
- [ ] Android 开启 TalkBack → 朗读顺序与手势人工巡检
