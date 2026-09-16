# AEX-06 收紧交接（2026-09-16）

## 1. 接手结论与权威

用户要求停止范围漂移，将剩余工作交给其他 agents。本轮只收紧文档，没有继续产品实现。不要使用 Luna，不创建第二套计划或重新探索 AEX-00～05。

- 唯一进度权威：[Android 扩展兼容 roadmap](./2026-09-12-android-extension-v2-compatibility-roadmap.md)，第 7 节为最新剩余出口，历史日志只作证据。
- 长期成本/反思：[执行成本记录](./2026-09-15-android-extension-execution-costs.md)。本文件是恢复入口，不另设 active-task/checkbox 或 capability 状态。
- 仓库 `D:/Shell/Github/mihon`，产品已提交基线 `dbf3f050a1a5cd112b9a3347285259f4d319e161`（AEX05）；AEX00～05 完成，AEX06 **未完成且产品改动未提交**。交接文档提交会推进 HEAD，但不改变这个事实。
- capability 机器权威仍为 `app-desktop/src/test/resources/parity/parity-manifest.json`；交接不覆盖其状态。
- 继承仓库 AGENTS：中文、严格行为 TDD、实际 production/wiring 测试、保留 dirty 改动、每 worktree 单重型 Gradle、受限预算、精确提交。
- 旧 goal 工具最后记录 blocked、6,451,845 tokens；该数字已冻结，不是本轮用量。不要用重复建 goal、空转或统计来代替实施。

## 2. 收紧后的边界

必须完成：冻结 1.6 协议/ABI、旧扩展和数据保全、正式 R8 产物、代表性真实源业务，以及受影响 Desktop 不回退。

不再自动做：

- 逐站兼容或必须让 MangaPlus live 查询成功。保留其失败和未知原因，不声明站点故障、全部兼容或修复完成。
- 通用代理/网络重构、Reader 重构、完善其他 roadmap、补齐通用 Test Mode 的全部 13-family/Authors 历史 partial 项。
- 新偏好框架、额外隔离功能、构建框架、测试生成器；已批准的 profile 实现保留，修其实际缺陷即可。
- 为每条证据重建宿主、重跑全量、重新审查全仓、创建快照包或微任务代理。

失败处理：先判断是否破坏一个明确必需项，再做一次有假设的 focused 定位；发现新增平台/安全设计或超预算修复，说明证据、替代方案及停止条件后请用户决定。未知原因不是通过，但也不是无限调查授权。用户同意收紧不等于同意把失败写成成功。

## 3. 剩余工作按三个上下文簇执行

### A. 旧系统扩展的来源确认升级闭环（优先）

已复现：ARM 手机原有系统 MangaDex 1.4.202，仓库 1.6.0 同签名。fork 中正常信任旧包后，选择 Private 更新仍报通用完整性/信任错误，一次刷新重试相同。原系统包保持不变。

稳定入口：`app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstaller.kt` 当前约 725–750 行。已安装包无 provenance 时构造 `InstalledExtensionTrustRecord(null, null)`；共享策略返回 `ConfirmationRequired`，Android 转为 `AppError.Authentication(TrustConfirmationRequiredException)`，未形成用户确认来源→安全继续安装的闭环。

目标：复用现有安装协调器/信任 UI，明确展示并确认来源后继续；取消不写信任、不替换包；不同证书/错误来源/损坏 APK 仍拒绝。不能自动信任、用全局信任替代仓库绑定、忽略签名，不能卸载用户系统扩展绕过。

执行：先检查现有 UI/session/trust 状态与测试，再写能复现缺口的 production 集成 RED；最小修复、同测试 GREEN/重构、相关安全和生命周期回归、一次窄独立审查。同一 agent 持有整个上下文，不按测试/代码/设备机械拆分。若设计涉及新的安全语义，先确认方案，不擅自发明第二套信任系统。

完成证据：真实来源确认的确认/取消路径、错误候选拒绝、私有升级成功、旧系统包字节不变、冷启后可信状态与正确版本选择。

### B. 最终候选的缺失运行验收

Android：A 的修复会使 rc8 不再是最终候选。只针对变化重建/签名，按依赖影响复验发布安全与 ABI；ARM 使用代表性真实 MangaDex 1.6 完成查询/详情更新/阅读/新下载/断网冷启。复用已经有效的旧版、最低 API、memo 等证据；不要把 1.4 业务通过冒充 1.6。没有必要为完成此链继续追查 MangaPlus。

Desktop：Mac 0.11.19.35 五类 Reader fixture 已通过；Windows 尚需正式 GUI Test Mode。先核实用户是否手动启动（交接时未收到确认），不得绕过此前工具拒绝。已通过场景无需重复；共享代码若变更，再按实际影响补验。

Windows 手动启动入口（用户此前已收到请求）：

```powershell
& 'D:\Shell\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.19.35.dbf3f05-unpacked\Mihon Desktop.exe' --test-mode --test-profile=D:\Shell\Github\mihon\.gradle-coordinator\profile-035-windows-runtime --test-http-port=18086
```

启动前核对 artifact、进程、端口；不能把其他实例的 health 当成功。只用专用 profile，不接触正常书库、账号、注册表或 OS 凭据。

### C. 最后证据对齐、提交与交付

核对第 7 节必需项与最终 diff，补缺失而非重复全部历史验证。产品批次必须有必要审查/测试；已审过的部分不重开全仓审查。最终改动影响共享层时按仓库规定补相应完整测试，不能用旧 diff 结果冒充新 diff；不要无变化重复全量。

Desktop 发布仅 `scripts/build-desktop.sh`；已有等价完整 Desktop 测试才用 build-only。最终报告引用构建日志 Final unpacked EXE 的真实路径。记录受测 hash、签名、配置和提交来源；dirty build 中的 dbf3f05 不是 AEX06 提交号。必要项完成后才勾选 roadmap，未决单站限制显式附带。

## 4. 工作树必须保留

接手先 `git status --short`。大量已有 AEX06 改动不等于其他用户无关改动，但必须逐项核对，不 `git add .`、不 reset/checkout。

| 组 | 主要路径/说明 |
|---|---|
| Android 安装与 release | `app/build.gradle.kts`、`ExtensionInstaller.kt`、`GetExtensionsByType.kt`、`SettingsAdvancedScreen.kt`、相应安全/生命周期/UI 测试、`app/src/releaseAndroidTest/` |
| R8 Zstd | `app/proguard-rules.pro`、`source-api/consumer-proguard.pro`，公开 facade 与 JNI 精确 2 类/4 字段保留，不改成整个包 keep |
| 签名脚本 | `scripts/android-fork-release.init.gradle`、`create-android-fork-release-key.ps1`、`sign-android-fork-release.ps1`、`.gitignore`，不提交私钥/密码或机器配置 |
| Desktop 隔离 | 新 `DesktopTestProfile.kt`、`IsolatedDesktopPreferencesFactory.kt`、`DesktopTestProfileTest.kt`；Main、PlatformPaths、CredentialStore、TestArguments 及相关测试 |
| 其他待核对既有差异 | Desktop AppVersion、parity contract/manifest、GlobalSearchResultProductionWiringTest；不要顺手回滚 |
| 文档 | roadmap、成本、`docs/automation/TEST_GUIDE.md`；guide 随 profile 产品实现保留待提交 |
| 无关用户文件 | 未跟踪 `testfile/`，不读取、删除或提交 |

本轮只提交 roadmap、成本记录和本交接文档；它们包含此前本任务尚未提交的证据记录。产品及 guide 留在工作树，必须在同一工作区接手，单独 clone 文档提交不能获得完整实现。

## 5. 候选产物与环境

### Android

- SDK `D:/Android/Sdk`；PowerShell 显式配置 ANDROID_HOME/ANDROID_SDK_ROOT，Python UTF-8。网络遵守 AGENTS 的本机代理；回环不代理。
- rc8 宿主：`D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc8/Mihon-Fork-0.19.4-aex.1-rc8-universal.apk`。
- SHA-256 `6808a79bd13cd18d257eb5ea47b71446db247478e21ec9787426ae75fbd9e4f0`；包 `app.mihon.desktop.fork`；versionCode 19，min26/target36，R8/resource shrink 开启，非 debug，telemetry/updater 关闭。
- 签名材料只在 `D:/Android/Signing/mihon-desktop-fork/`：`release.p12` 与既有 DPAPI 凭据；alias `mihon-desktop-fork`。使用既有签名脚本，不重新创建密钥、不输出密码。
- 证书 SHA-256 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`。
- ARM 测试 APK：同目录 `Mihon-Fork-0.19.4-aex.1-rc8-arm-abi-tests.apk`，SHA `8b3417bbfcceff659fe89295f5e39528e1d8ef20321d35552a65b48324d031c0`。入口 `ExtensionArmReleaseAbiInstrumentationTest`，必须传发布 SHA guard；具体 argv 复用现有 runner/roadmap，不省略 guard。
- 标准 release suite 只允许专用模拟器；不要在实机运行会更换 fixture 的完整 emulator suite。ARM suite 只跑隔离 ABI/Zstd。

手机：SM-S9280、ARM64/API36、adb serial `R5CX21RQQ0H`，后续接入状态需现场确认。使用 `--user 0`，工作资料 user150 不在范围。用户原 `app.mihon` 未修改；仅 fork/test 包与其数据用于本次验收。

- 原系统 MangaDex 1.4.202 SHA `1dadd0391066e33e3d433eff08f251c29b46923a4aa38b5d44185cf71c2072c2`，备份 `.gradle-coordinator/aex06-arm-original-mangadex.apk`；证书 `9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`。升级私有副本时保留原包并核验字节。
- fork 独立存储 `/storage/emulated/0/MA06ARAEX06ARM`，名字是输入法结果，不要为整洁重命名。保留旧书库和单章下载供复验。
- 最后恢复 Wi-Fi=1、mobile_data=1、airplane=0，未改 VPN/全局代理；fork 已停止。离线测试前后需核对真实 default network，不以设置键代替网络状态。

### Desktop

- Windows 候选 `D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.35.dbf3f05-unpacked/Mihon Desktop.exe`；ZIP SHA `92a3f912799f47c9d77162e34ef880b489d2107330d70ccba6a5c8a0ac31e415`。
- Windows JDK `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`。
- Mac SSH `mbp-lan`（备用 `mbp`），专用树 `/Users/altair/Github/mihon-aex06-release`；**原 dirty 树 `/Users/altair/Github/mihon` 不动**。
- Mac 输入/日志 `/Users/altair/Github/mihon-aex06-release-input`，原 app 备份 `previous-app/Mihon Desktop.app`。正式 app `/Applications/Mihon Desktop.app` 当前版本 0.11.19.35。
- Mac JDK `/Users/altair/.jdks/jdk-21.0.10+7/Contents/Home`。构建曾通过 SSH remote-forward 18088 访问 Windows10808；不要把这个临时构建代理当应用当前代理。
- Mac profile `.../release-input/profile-035-runtime`；实际是完整路径 `/Users/altair/Github/mihon-aex06-release-input/profile-035-runtime`。用 `open -n` 走 GUI，不直接用 SSH 后台 binary 代替桌面会话。不传旧 `FileSystemPreferencesFactory` 的 JAVA_TOOL_OPTIONS。
- PowerShell stdin 跨 SSH 曾带末尾 CR，使端口 18086 被错误解析为默认 8080；改用正确转义单行命令，核对 PID/实际监听。不绕过任何工具限制。
- Mac 本轮测试 PID19449 已正常 shutdown，随后 ps 不存在；最后 pgrep 无 Mihon 进程。Windows 完整 GUI 启动未获用户确认，交接前无18086监听。动态状态接手再核对一次即可。

## 6. 可复用证据与局限

日志多数在忽略目录 `.gradle-coordinator/`，未随 Git 传输；不能因找不到日志就重做全部，先确认同一工作区。以下是索引，不替代原始日志。

| 验证 | 结果/证据 |
|---|---|
| Android 既有回归 | 之前完整 Android 367 项及相关安全/生命周期 56 项，详见 roadmap；记录过单独 flaky 重试，不伪装首次全绿 |
| rc8 ART | API26/API36 标准 ABI/Zstd 5 项通过；ARM `aex06-rc8-arm64-abi.log` 5 项、0.293s 通过；错误 SHA 和 emulator suite 对实机均正确拒绝，不计业务失败 |
| Android 系统安全 | rc8 API26 旧1.4→1.6升级、冲突签名/损坏拒绝、恢复原仓库和字节通过；不声称最终rc8重跑了私有安装全部反例 |
| ARM 旧版业务 | 系统1.4.202 搜索/详情/下载Ch2/离线冷启首屏通过；下载9 JPG+ComicInfo。两张离线截图均1/9，名为last的文件不是末页证据 |
| Zstd 审查 | 获批定向审查无 finding；同测试 APK 在 rc6 缺类、rc7 JNI abort、rc8 5 GREEN；不外推 MangaPlus live 成功 |
| Desktop profile | 参数/URI RED；存储隔离 RED；真实 main→DI→Test Mode；额外审查发现大小写目录碰撞，38s RED、43s 26 GREEN，Mac focused26 GREEN/39s |
| Desktop 全量 | `aex06-profile-desktop-phase-run` 5m49s：3027项、1失败、2跳过，失败为证据行号。修正后 `aex06-profile-contract-format-fixed` 契约通过；不要把原全量命令说成exit0；当前XML可能被focused覆盖 |
| 格式 | `aex06-profile-format-final` 28s PASS；git diff --check通过。临时ktlint任务装配失败不是产品 RED |
| Windows发布 | `aex06-profile-windows-release` PASSED，2m26s构建+真实旧APK安装/加载验收；正式EXE已核对存在 |
| Mac发布 | `aex06-profile-macos-release` PASSED，32s；旧34版还有真实1.6 JAR/1.4 APK加载记录，不冒称在35全部重跑 |
| Mac Reader | 35正式GUI，五类 downloaded_directory/CBZ/local_archive/online/partial_download 全部真实首屏及 productionClosed=true，首屏约115–274ms；online 是回环fixture，不是互联网站点 |

Mac 首轮 Reader 只到解码、未首屏、关闭标志未完成；用户确认解锁后单场景及五场景均通过，**无代码修改，原因未知**。保留 `profile-035-reader-failure.json`，不声称已修复休眠/导航 bug。用户看到空书架是在客户端请求关闭之后，不能据此断定从未导航。

客户端 `test-desktop/src/main/python/reader_test_mode.py` 有真实事件/I/O断言；`mihon_desktop_final_parity_client.py` 的其余 family 摘要只是 inventory 映射，不能当13类实测。最终封存脚本含 committed provenance gate；不要绕过 gate 后称完整sealed验收通过。

## 7. 两项未决问题，停止自动扩张

**MangaPlus**：1.6.66 官方 APK SHA `e9511110525f81f30139704bda07b0a910e5100e42326570c5c9870a7529f94b`，设备字节已核验。缺类/JNI 修好，但查询仍未知错误。已观察 HTTP200、encoding none、4字节、首字节18（error字段）、network/cache均参与；固定success/error帧调用真实扩展解码器通过。不足以认定网络、服务端或宿主无关；停止新增诊断。最终如仍存在，交付明示此站未验证成功，不声称全站兼容。

**Mac 网络提示**：用户只说“显示网络连接问题”，尚无具体页面和原文。最后只读检查系统HTTP/HTTPS/SOCKS均指向127.0.0.1:10808，xray PID808监听该端口；这不是CONNECT/TLS/HTTP链路成功证据。没有改系统代理，也没有以curl成功替代应用成功。后续先询问准确触发路径；除非它阻碍原定代表性链路或用户另立任务，不自动加入AEX修复。用户最后的“收紧、交接”优先于继续该诊断。

## 8. 调度、审查与成本规则

- 默认一个执行者连续完成 A→Android B，复用安全/设备上下文。只有 Windows GUI 真正就绪且不争用工作树/构建时，才考虑另一个限域验证者；不要启动空闲代理等待用户。
- A 的安全修复需要一个独立窄审查；范围为新增确认和安装事务，不重读全部历史。额外复审遵循预算和明确缺陷触发。
- 旧审查代理均已 completed；不能用“保持空闲”算推进。本轮核对 Windows release/format/focused 三个协调器均 PASSED，不存在已知仍需等待的该批 Gradle；若工具超时先查状态，不重启第二个构建。
- 每个有行为变化批次开始前明确流程预算；完成时记录实际命令、失败、耗时和可得token口径，不捏造费用/节省比例。冻结goal计数和受读取上限截断的footer不可当精确累计成本。
- 避免新的流程产物；只更新现有roadmap/成本/必要产品文档。交接已完成后不要为“接手、推进、close”单独建提交；产品批次测试、实现和checkoff一起交付。

## 9. 接手的第一步

先核对 AGENTS、`git status`、上述产品基线与 dirty 文件；读取 Installer 现有来源确认链及对应测试，制定 A 的最小 RED/GREEN 方案。不要从重建所有平台、重跑所有测试、继续 MangaPlus/代理排障或创建新的总计划开始。
