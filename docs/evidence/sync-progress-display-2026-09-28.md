# 同步进度稳定展示验收记录

日期：2026-09-30。状态：SP01/SP02完成；SP03自动化及三平台构建完成，真实账号、键盘/读屏及实体Android运行门禁待验。

需求权威：[开发规格](../2026-09-28-sync-progress-display-design.md)；执行入口：[Roadmap](../roadmap/2026-09-28-sync-progress-display-roadmap.md)。

## 基线与范围

- 工作树：`D:/Codex/worktrees/dc4c/mihon`，分支 `codex/sync-progress-display`。
- 启动 HEAD：`41e7e5ff1ef92b382de1b3134e11cf94ae1883f4`；工作树干净。
- 相对规格基线 `02c48df981`，共享面板/controller/进度事实接口无新增行为；Android/Desktop wrapper 新增 review 入口，保留该功能。
- 复用现有 runtime → controller → 共享 Compose → 双端 wrapper；不修改协议、数据库 schema、安全确认算法及 ETA 估算器。
- PROJECT_POLICY：规格 P1–P14/D01–D10 与稳定七槽约束；SOURCE：现有 Compose 卡片/主题及 action/runtime；HTML_ADAPTER：原型仅为信息架构参照。

## 环境预检

- Windows JDK 21.0.11；Git Bash 位于 `C:/Program Files/Git/bin/bash.exe`。
- Android SDK 的 android-36/android.jar、36.0.0/aapt2.exe、adb.exe 均存在。
- `python scripts/build-android.py check --signing` 成功，原证书签名预检通过，不安装或操作实体设备。
- 原始checkout与本工作树启动公开发布配置同为versionCode 35 / `0.19.4-aex.17`，原始正式候选目录最高35。候选入口按配置构建，不自动分配版本；正式交付前在唯一公开配置预分配36 / `0.19.4-aex.18`，再用统一入口构建，不复用已交付版本号。
- macOS：首次 `ssh -o BatchMode=yes -o ConnectTimeout=8 mbp` 超时，备用 `mbp-lan` 成功；macOS 14.8.4、JDK 21.0.10、Gradle cache 可用，剩余磁盘约 19GiB。日常树有用户改动，发布时使用隔离 checkout/dist/app/config，不覆盖日常实例。

- macOS 隔离源码树：`mbp-lan:/Users/altair/github/mihon-sync-progress-20260930-dc4c`，通过4MB增量bundle同步至启动HEAD；未改日常checkout/应用。
- 核对现有 parity manifest，没有本次多设备同步展示的对应独立capability；不新增重复状态表或改动其他能力状态。

## 验证与覆盖

- Compose 首轮红测：`.gradle-coordinator/sync-display-red.log`，1 项失败；运行卡阶段计数默认可见，违反默认收起详情的契约。
- 投影首轮红测：`.gradle-coordinator/sync-display-projection-red.log`，3 项失败；动作800ms/ETA2000ms、scope/hold撤销与安全nullable计数尚未实现。编译正常，失败是行为断言。
- 首轮 focused green：`.gradle-coordinator/sync-display-green.log`，投影3项与真实Compose折叠1项通过。
- 红绿循环随后覆盖隐藏面板定时取消、48dp主操作槽、200%字体真实TextLayout、保持暂停运行ID和关闭重开恢复。早期失败与中间通过只作为过程证据，不替代最终冻结。
- 独立审查第一轮：`NOT_ACCEPTED`。发现 FAILED/PARTIAL 原因被去重吞掉、修复入口缺少不兼容配置限制、详情隐藏已知零计数，以及真实批处理按钮集成证据不足。统一交原实施者修复；同轮补修200%字体说明槽裁切及去重后空消息行。
- 原冻结的共享存储、Android/Desktop wiring 通过；Android面板8项中3项因Windows不支持Unix文件权限夹具跳过，必须在macOS补验。后续发现说明槽裁切已撤销该轮UI冻结，未将它作为完成证据。
- 修复后冻结：`.gradle-coordinator/sync-display-reviewed-freeze.log`，2026-09-30 14:13:33结束，退出0，耗时1分7秒。共享UI85项全绿（71 Compose、2 onboarding、1真实controller、7投影、4 review）；JVM storage36、Android storage35、Android runtime14、Desktop panel1+wiring2均无失败/跳过；Android panel8项无失败、3项Unix夹具跳过。presentation-sync/data/i18n/app格式检查通过。
- 主代理独立读取最终JUnit XML核对上述数量/失败/跳过，唯一修复复审通过（SP01/SP02代码批次）。批处理补验通过真实协议校验的400事件夹具（每批200、同actor连续seq、匹配batchId），真实独立偏好节点在finally清理；修复夹具不改变生产协议。
- macOS隔离checkout已同步功能提交`8797b0a6c5`。离线首次因缺少Foojay插件缓存失败，尚未执行测试；用临时SSH回环转发复用本机代理后追加一次尝试成功，未修改系统代理。`sync-display-mac-focused.log`耗时5分38秒：Android panel8、runtime14、Desktop panel1+wiring2，25项全部通过且0跳过；Windows跳过的3项真实FileProvider用例在此补验。日志已复制至本工作树协调器目录，主代理核对远端实际XML。

## 实施与审查边界

- 2个子代理：同一实施者串行负责SP01/SP02，一个只读独立审查者；主代理负责文档、环境、整合与最终平台验收，未重复实施委派代码。
- SP01/SP02修改同一共享面板、投影和真实controller接线，保持为内聚用户能力；超过8文件/400行主要为边界、几何与双端契约测试，不按机械行数拆开不可独立验收的上下文。
- 数据层仅局部修复`ResumeImport`：解除导入暂停不应启动第二轮覆盖已有用户暂停运行；使用持久运行状态保留原ID。未改协议、数据库格式或调度算法。
- 核对真实构建脚本：当前Desktop入口未内置协调器，因此正式测试/构建使用唯一外层协调器；Android统一入口已内置协调器，不嵌套。实现后完整Android/Desktop测试各安排一次，修复循环仅focused。

## 契约入口与结果边界

| 规格 | production行为验证入口 | 补充门禁 |
|---|---|---|
| P1/P2、D01 | `SyncProgressPresentationTest`动作800ms/频繁变化锁定，`SyncPanelContentTest`无新事件deadline提升 | 单调时钟与数字变化不重启稳定窗 |
| P3/P6/P7、D02/D03 | 投影scope/attempt/方向立即撤比例，ETA1999/2000ms及10/60秒边界 | 真实Compose单轨道、固定时间标签 |
| P4、D09 | `D09 fixed summary geometry and status live region survive progress changes`及详情focus测试 | 新日志与数字不改变摘要配置内几何 |
| P5/P14、D04/D05 | 投影安全nullable计数、传输完成非终态，Compose终态与零计数测试 | 持久结果优先、失败原因全文 |
| P8/P9、D06/D07 | `SyncProgressControllerIntegrationTest`真实运行/controller点击；共享文件数据库storage契约 | 两端runtime wiring；bulk新增夹具必须通过真实批次校验 |
| P10、D08 | `SyncPanelContentTest`决定项/日志/公开仓库/终态原因及修复guard | Android真实FileProvider和Desktop打开adapter |
| P11 | `SyncPanelOnboardingIntegrationTest`及MAIN/SETUP共享摘要几何 | 两处使用同一投影与展示会话 |
| P12、D09 | `P12 D09 native summary matrix stays readable and scrolls at 200 percent` | 离屏Compose中英/双主题/320–560dp，TextLayout无裁切 |
| P13 | detail focus、返回/关闭和MotionDurationScale=0原生测试 | 读屏实际播报与设备人工验收待验 |
| D10 | `D10 hidden panel cancels visual wakes and reopen projects current terminal` | 隐藏取消视觉timer，生产runtime不停止 |

主代理已核对修复后的320dp中文默认、中文200%深色及英文200%浅色离屏图：说明全文与详情按钮可达，英文五行说明不再裁切；200%时单列滚动到摘要底部，顶部标题允许省略并保留设置/关闭按钮。图片保存于`presentation-sync/build/sync-visual/summary-*.png`，为当前Material主题的原生Compose候选图，不是上游像素基准，也不代替真实发布产物/读屏验收。

## 产物与待验

Desktop正式产物已生成且运行验证通过，Android签名候选已构建并verify。真实账号同步、读屏及设备人工验收保持待验。

Android版本分配过程：首轮误以为`candidate`会自动递增，实际生成vc35，Gradle构建2分34秒通过；该包不作为本次交付，保留过程现场。随后在`gradle/android-release.properties`明确预分配vc36 / `0.19.4-aex.18`并重建，不修改入口行为、不复跑全量。两次均复用原证书，vc36最终候选已独立verify，首包vc35不交付。

- Android：[正式签名候选APK](D:/Codex/worktrees/dc4c/mihon/app/artifacts/android/0.19.4-aex.18-vc36-8797b0a6c5-release/Mihon-Fork-0.19.4-aex.18-vc36-release-universal.apk)；[产物清单](D:/Codex/worktrees/dc4c/mihon/app/artifacts/android/0.19.4-aex.18-vc36-8797b0a6c5-release/artifact.json)。版本`0.19.4-aex.18`/code36，原证书v2/v3签名验证通过，非调试、R8/资源收缩开启，遥测/更新器关闭。最终构建2分20秒，`python scripts/build-android.py verify --artifact <上述APK绝对路径>`退出0。SHA-256 `d48be92492cde37707799828ec1189680df022909bf7368f199548bd93f15245`，没有安装或控制实体设备。API26–36与四ABI是manifest声明，不作为运行矩阵通过证据。
- Windows：[正式未打包EXE](<D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.69.8797b0a-unpacked/Mihon Desktop.exe>)；[完整Windows ZIP](D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.69.8797b0a-windows.zip)。地址来自构建日志`Final unpacked EXE:`且已确认文件存在。脚本`build-only`构建Gradle耗时1分37秒，正式runtime扩展安装/版本验收通过；ZIP SHA-256 `2a6ec05067aeac0c0af4782627a724fdf4dba0b2810473ff6cbfb4267ead52c2`。
- macOS x64：[本地交付ZIP](D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.69.8797b0a-macos-x64.zip)，SHA-256 `70c04cec5ed27342900696670bf5f40b688a4b336b8ee7b84c19dc5a8a260034`。原生应用包位于隔离checkout的`app-desktop/artifacts/macos/Mihon Desktop Sync Progress.app`，dist位于独立Caches目录；未覆盖日常应用。实际Mac脚本构建33秒，版本与Windows同为`0.11.19.69.8797b0a`。
- 两端均运行各自正式包内可执行文件，使用新建`--test-profile`、随机空闲端口和`--headless`；通过production sync HTTP action打开→关闭→重开→配置→关闭。验证visible/loaded/page/busy/connected/队列及待决定项，未授权、未启动同步、未碰用户远端。运行结果`sync-display-windows-release.json`、`sync-display-mac-release.json`均PASSED，使用关闭API退出自己启动的应用；未读取屏幕像素。该项证明发布DI/controller可用，不替代实际运行中的账号同步或原生UI人工验收。

## SP03集中完整验证

功能提交：`8797b0a6c5`。`sync-display-final.log`完整验证耗时33分46秒、退出0；开始时PowerShell未加引号的属性导致任务名解析失败，尚未执行测试，修正参数后启动。15分钟外层等待超时后检查协调器仍RUNNING，继续等待同一PID8644，没有重跑完整测试。线程只读检查显示已有7次限速Mock首次导入性能用例正常执行，随后全部通过。

| 完整模块 | 用例 | 失败/错误 | 跳过 |
|---|---:|---:|---:|
| presentation-sync JVM | 85 | 0 | 0 |
| data JVM | 753 | 0 | 1 |
| data Android Debug | 344 | 0 | 0 |
| app Android Release | 672 | 0 | 7 |
| test-desktop客户端 | 52 | 0 | 0 |
| app-desktop JVM（full-tests） | 3238 | 0 | 3 |

全仓库`spotlessCheck`通过。共1906项，1898通过、8跳过；data的1项是需显式启用的本地Git对照性能专项，未扩大到本次展示任务。Android跳过的3项FileProvider是Windows宿主限制，已在Mac补验；另4项历史数据库callback用例显式要求`BuildConfig.DEBUG`，原因是Release SQLite native driver要求Android。Mac Debug补验`AndroidLegacySyncMigrationTest`4项全部通过、0跳过（`sync-display-mac-migration.log`，30秒），不能把Debug通过作为Release ART证明。保留真实跳过记录，不把跳过计为通过。

Desktop脚本`full-tests`完整验证耗时4分58秒、退出0（`sync-display-desktop-full.log`），3238项中3235通过、3跳过：Mac JXA分享仅平台条件、Windows隐私原生窗口用例在headless环境跳过、非Release自定义同步间隔用例在当前发布配置跳过。脚本启用integration，按项目默认排除live-network/network-survey/final-parity-audit/parity-governance专项；没有为展示任务启动真实账号、网络调研或无关治理审计。两轮完整任务合计5144项，5133通过、11跳过，跳过边界如上；不将Mac补充的不同变体用例改记为Release通过。

复审结论：`ACCEPTED`，上一轮四项阻塞全部关闭，F说明槽修复及真实bulk偏好隔离/清理已核对，无新增阻塞。SP01/SP02以同一内聚功能提交交付；SP03构建、macOS补验及人工读屏/Tab遍历尚不作为通过。

## 提交、维护与人工门禁

- 功能/测试/必要checkoff提交`8797b0a6c50b76c8ba4e46578190dbb58d47a587`；唯一修复复审通过，无未解决代码阻塞。SP01/SP02勾选，SP03保持未勾选。
- Desktop构建从该功能提交分配BUILD69；Android从同一产品代码预分配code36/aex.18。最终提交包含这些机械版本和验收/维护文档，不新增产品代码；不为最终文档提交hash无意义重建已验证产物。
- Android清单保留`sourceRevision=8797b0a6c50b76c8ba4e46578190dbb58d47a587`、`sourceDiffSha256=81c58b9a9210ebb4d8ea272576acaac2229b0bd4d37e4c34f4d44a8d33c47d5e`及`productionInputsSha256=19e5c09f0d7a2cda3509856a8c3ac8d9ce5fd31ff9a5726f20372403a02b6e88`；不将未提交版本输入误称为干净HEAD。Mac共享产品代码及Desktop版本一致，其Android公开配置仍为35（不被Desktop应用消费）。
- 2个代理、1轮独立审查+1轮修复复审，集中完整Android与Desktop各1次；重型Gradle同工作树串行。Desktop两机打包并行；Android因版本预分配失误追加一次2分20秒构建，没有重跑全量或扩大产品范围。过程日志/候选图沿用忽略目录，不提交巨型diff或逐任务快照。

待用户使用以上实际产物完成，未执行且不能据此宣称整个roadmap或生产闪动BUG全面验收：

- [ ] Windows/macOS正常启动 → 书架 → 同步 → Tab/Shift+Tab、展开/收起详情、返回/关闭 → 焦点和层级正确；运行仍可继续。
- [ ] 系统读屏与减少动画设置 → 运行中数字/ETA更新 → 状态播报不逐秒重复，暂停后轨道静止。
- [ ] Android安装同证书code36候选（用户自行操作） → 实际ART冷启动、同步页面、大字/返回和旧版升级数据保持。
- [ ] 真实账号 → 书架同步及首次合并 → 快速阶段切换/整体ETA变化/暂停恢复/终态原因 → 摘要与按钮不增减跳动、详情与安全确认可达。

原生离屏与集成自动化证明各契约的受控输入行为；正式TestMode证明发布controller/DI的隔离入口，均不替代上述人工业务验收。
