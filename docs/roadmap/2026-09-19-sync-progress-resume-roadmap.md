# 同步进度与待机恢复 Roadmap

日期：2026-09-19。状态：IN_PROGRESS；R1–R4 已完成实现、独立复审、验证与提交，R5 的版本矩阵与 macOS 运行证据已补齐，真实同步空间和全量基线门禁仍未闭合。

设计权威：[同步进度、条目日志与待机恢复设计](../2026-09-19-sync-progress-resume-design.md)。原型：[并列入口](../prototypes/multi-device-sync/index.html)与[现有边界](../prototypes/multi-device-sync/README.md)。

## 1. 范围、状态与依赖

目标：双端共用真实进度／日志；Android 返回前台自动继续已接受且被系统中断的同步，用户主动暂停保持暂停。实现沿用已有同步协议、数据库队列和生产客户端。

本文件为产品 child plan，从第一个未勾选任务推导进度，不另设 active-task，不改动其他 roadmap 的完成状态或父计划指针。checkbox 仅在实现、必要审查、验证与提交均完成后勾选。

排除：新同步协议、密码／权限改造、远端迁移、桌面电源事件、应用退出后常驻、扩展功能整治。UIDT 是 R3 的有条件决策，不是默认必选实现。范围差异先记录与说明，不在实施中默默扩大。

执行顺序：R1 → R2 → R3 → R4 → R5。R2–R4 构成一个完整生产功能批次，一轮独立审查分两次交付检查：R2 的迁移与执行权接口稳定后先审查，通过后才推进依赖它的 R3/R4；完整接入后补查平台与 UI wiring，未通过前不发布。复用同一审查者，不重复审查未变部分。R1 是可独立审阅的原型批次。禁止把每张表／每个文件分成单独任务。

## 2. 执行预算与验证规则

本轮已由主代理整合 R1–R4 生产接入、原型和测试；本计划不授权远端账号写入或覆盖用户手机数据。设备、Doze、正式签名和发布产物仍需在 R5 单独核对，不以当前 focused 测试替代。

建议实施配置：最多两个子代理，第一位负责 R1 及连续 R2–R4 的主要实现和 focused 验证；主代理负责接口、整合与验收；第二位从 R2 接口稳定时开始一轮分阶段独立审查，聚焦数据完整性、迁移、执行权、恢复和 production wiring。依赖工作串行，不宣称多实现并行；必要时一次定向修复复审。预计实现、设备验证与构建共 2–4 个工作日，主要成本为恢复竞争测试和 Android 正式运行验证；设备／发布门槛缺失记录真实阻塞，新增范围或审查轮次另行说明。

严格红绿重构：每个行为先运行真实失败测试，再最小实现，再 focused 复验。生产批次完成运行受影响模块测试与格式；最终全量 Android/Desktop 和发布构建集中一轮，不按小任务重复执行。Gradle 全程由同一协调者经 `scripts/gradle-coordinator.py` 串行运行。

过程记录放本计划末尾，不另建逐任务报告；生产功能批次原则上一个含测试、实现、文档与 checkoff 的提交，必要审查修复最多一个追加提交。R1 原型可单独提交。超出文件／行数估算说明内聚性，不机械拆散功能。

## 3. 任务清单

- [x] R1：调整双端交互原型，覆盖自动恢复与主动暂停的区别。（实现、独立复审、focused 浏览器验证与提交完成）
- [x] R2：接入共享运行记录、真实检查点进度与可串行恢复的执行入口。（实现、独立复审、SQLite/运行链路验证与提交完成）
- [x] R3：接入 Android 前台恢复、一次性后台补偿与通知。（实现、独立复审、Robolectric focused 验证与提交完成；真机门禁归 R5）
- [x] R4：接入双端原生进度、条目日志与首次合并恢复闭环。（实现、独立复审、Compose/Desktop wiring 验证与提交完成）
- [ ] R5：完成独立审查、正式运行验证与发布交付。

### R1：双端交互原型

**前置**：阅读现有 DEMO README 与设计第 3 节。当前实现已改为系统中断自动恢复，主动暂停才需要手动继续；checkbox 仍等待独立审查、提交和最终收口。

**范围**：仅修改 `docs/prototypes/multi-device-sync/` 中相关展示状态、样式、浏览器测试和 README。复用 Mihon 外壳与 data-testid，不扩张 sync-model。默认场景演示返回前台自动继续；新增主动暂停／手动继续、等待网络／联网继续、未知总量、进程重建、重试耗尽、授权阻塞及成功收口场景。

**交付**：Windows/Android 共用进度与最近 5 条日志布局；长名称、320px 手机和宽屏可读；演示工具栏位于产品外，双端导航与定时器隔离。更新 README 精确区分模拟与生产。

**验证**：先让 `sync-progress.test.cjs` 因旧场景需要手动继续而失败，再实现自动恢复模拟；运行相关进度、交互与 `parallel-preview.test.cjs` 浏览器测试，脚本 `node --check`；检查截图、无横向溢出、关闭重开与另一端焦点。旧失败需在基线复现并记录，不通过弱化断言计为通过。

**退出条件**：原型与设计状态表一致，产物可审阅；当前 `sync-progress` 与 `sync-interactions` focused 浏览器测试通过。用户尚未确认的界面决定仍保留待审标识。

### R2：共享持久化与恢复执行

**前置**：R1 状态与计数语义明确；核对实施 HEAD 的 schema 版本，不预占迁移编号。

**范围**：现有 domain/data sync runtime、coordinator、交换／导入／收件／上传检查点、SQLDelight 增量迁移及共享契约测试。新增本机运行和有界日志，绑定空间／generation／连接修订；复用现有冻结上传制品、inbox 去重和事务。引入请求／恢复／观察分工与单执行者归属，Panel 和调度后续接同一入口。旧历史保持可读取，新增本机状态排除备份。

**必须交付**：

1. 请求先持久化后执行，运行与尝试分开；进程重建对账，用户暂停／取消不会被恢复清除。
2. 业务检查点与计数一致，重试不双计，分页日志与保留上限不影响恢复数据。
3. 前台与 Worker 的执行权竞争、取消交接、旧尝试条件写保护；失效检测不依赖墙钟强行夺取执行权。
4. HTTP／阶段有界等待，上传结果未知先核对，原始冻结制品继续复用；部分网络失败进入同一有界重试策略。
5. 配置 pendingSetup 与首次合并完成标记纳入恢复，不只是普通 outbox 续传。

**红绿测试**：真实文件 SQLite 迁移与重开；请求落盘后未入队；业务提交后 UI 计数写入中断；冻结上传后杀进程；远端已提交但响应丢失；inbox 重放；进程重建；并发手动／前台／Worker；暂停与完成竞争；换空间后旧 owner 回写；连接修订变化；磁盘错误；日志超过 500 条。用共享 Android/JVM 契约跑同一真实实现；HTTP 场景用 MockWebServer、有状态远端 fixture 与真实 parser，不扫描源码代替行为。

**退出条件**：上述 focused 通过，生产交换链确实产生状态与进度，SQLite 运行记录可重开，ownerSession 条件写保护已覆盖；生产批次审查与提交未完成前不勾选。

### R3：Android 前台恢复与后台补偿

**前置**：R2 恢复资格、执行权与进度接口稳定，迁移与执行权已通过独立审查。此任务复用原实施代理，不另开平行 runtime 写入者。

**范围**：App 生命周期、AndroidSyncScheduler、SyncWorker、必要网络回调、DI、同步通知及 Manifest 最小声明；Desktop 只做共享接口必要适配，不加入系统电源监听。

**必须交付**：

1. ON_START 与冷启动合并进入 resumeIfNeeded；未完成已接受运行不受“启动新同步”开关误阻断；普通新队列遵守设置。
2. 前台应用级执行路径可直接恢复，WorkManager 作为持久化补偿；已有任务健康则观察，退避中任务不能阻塞前台路径，禁止并发真实交换。
3. 同进程被暂停的 HTTP／失效 Worker 能有界停止并交接；应用离开前台的交接与任务入队窗口可对账。
4. 一次性和周期任务区分，唯一 work 与 KEEP 的尾部竞争有覆盖；周期关闭不能取消手动运行。
5. 网络回调去重、实际客户端确认可达性；PARTIAL 网络错误也恢复；预算跨重建保存，429／Retry-After 不被前台重试绕开。
6. 前台 Worker/expedited 的适用分支、独立通知、暂停／取消和权限拒绝路径；提升受限可观察，不能吞异常后继续宣称长期后台执行。

**UIDT 决策点**：先记录真实运行时长／配额证据。没有长传输必要性则记录“本轮未采用”，基础任务继续；确需采用时提出平台 adapter 和验收成本，批准后在本节补精确任务范围。UIDT 不用于普通自动恢复和周期任务，不通过虚拟点击绕开用户发起要求。

**红绿测试**：Robolectric 经真实 App 生命周期／DI／WorkManager wiring 验证；模拟 Worker 已排队退避、系统停止 leader、网络来回切换、用户暂停、冷启动与启动选项关闭、通知拒绝及提升异常。受控时钟测试预算，不靠长 sleep。运行记录带真实停止原因或 UNKNOWN，不从屏幕关闭推断原因。

**退出条件**：实际 production entry 能启动恢复，UI 可观察真实执行状态；当前 Worker 重复执行回归已通过，系统分支仍由 R5 的 APK 运行验收证明。

### R4：双端原生页面与初次合并收口

**前置**：R2 数据源与 R3 平台状态可用；复用 R1 经确认的视觉规则。

**范围**：presentation-sync 共享组件、SyncPanelController、Android/Desktop 面板 adapter、必要 i18n 与已有记录页；所有计数来自 production 状态，不在 UI 创建进度计时器。桌面应用启动接共享未完成运行判定，保留原周期行为。

**必须交付**：首次 MERGING 与日常同步复用阶段进度；已知总量显示当前阶段分数，未知总量不造百分比；最近 5 条与更多日志分页；主动暂停和暂停导入作用域清晰；重开还原记录、后台不抢页。初次合并中断后自动收口到现有主面板，不重新要求密码、不重复建库。完成与用户待确认取消条目独立。

**红绿测试**：共享 panel storage contract + 两端实际 UI wiring；构造真实部分完成、等待网络、暂停、恢复、错误、已完成但待确认等状态。覆盖暂停/继续按钮派发、关闭重开、重建后计数、日志限额、错误修复入口及账号切换。新增导航或 DI 时补相应集成测试。Desktop 启动恢复与 Android 使用相同恢复资格契约。

**退出条件**：两端真实组件行为一致，平台差异限制在 adapter；当前 Compose 状态/日志回归已通过，原型示例计数不进入生产。

### R5：独立审查、真机与正式交付

**前置**：R2–R4 完整候选及 focused 证据；完成审查前不把任务勾为已完成。复用 R2 的独立审查者，保留仍有效的迁移与执行权结论，补查后续接入和受影响的未知上传结果、取消／换空间与真实 DI；发现缺陷交原实施者修复。

**运行矩阵**：

| 场景 | 方法与必须看到的结果 |
| --- | --- |
| 实际锁屏／返回 | 正式 APK 同步中锁屏，再解锁返回；无需点击继续，阶段和日志推进；记录四个时点并评估 1 秒／2 秒目标 |
| Doze | 在隔离测试设备使用 `adb shell dumpsys deviceidle force-idle`，退出使用 `unforce`；必要 battery unplug 测试后 reset；任务恢复且不重复应用 |
| 进程重建 | 同步中系统杀进程后重开；普通 kill 与设置 force-stop 分别记录，不混同；force-stop 期间不承诺自启，用户重开后对账 |
| 网络／旧连接 | HTTP fixture 延迟响应、断网后恢复；等待与重试可见；旧连接释放前不启动并行发布 |
| 主动暂停／预算 | 暂停后锁屏、联网、重开仍暂停；重试耗尽显示按钮，不因回前台无限重置 |
| 并发与未知结果 | 前台、周期、手动同时请求；发布已生效但响应丢失；只有一个执行者，同一制品确认一次 |
| Android 版本 | API 26/33 验证旧 expedited 与通知分支，35/36 验证适用服务限制／配额；缺少设备或条件须列阻塞，不能用 JVM 代替 |
| 双端一致性 | Windows 正式 runtime 与 Android 共用阶段／日志／暂停／重启恢复契约；Windows/macOS 不验收专用电源唤醒承诺 |

**最终检查与构建**：相关格式、Android/Desktop 全量测试及 Test Mode 集中运行；按仓库要求完成 Windows/macOS 正式构建和运行验证，Windows 必须经 `scripts/build-desktop.sh`，已有等价全量证据时才使用 build-only。Android 交付 arm64 正式签名 APK，并核对与目标已安装版本的 applicationId、versionCode 和签名升级兼容；未经授权不覆盖用户安装。R8 运行验证不以 debug APK 替代。

真实远端账号操作只在已有或明确批准的隔离空间进行；没有授权先完成本地 fixture，不擅自修改用户仓库。报告实际 Windows `Final unpacked EXE:` 路径、Android APK、macOS `.app`、版本、测试和限制。任一发布门槛不可用时保持 R5 未勾选，报告实际缺项。

**结束条件**：独立审查与必要修复通过、任务全部验证且已提交、正式产物路径存在。完成报告按仓库规定列用户可见特性、实际修复和可执行验收项；不得把本文计划描述作为功能已实现的证据。

## 4. 当前交付记录

2026-09-19：完成 R1–R4 实现整合。原型新增系统中断自动恢复、主动暂停、等待网络、未知总量、重试耗尽和授权阻塞；生产侧新增 SQLDelight 运行/日志表及 27 号迁移、真实阶段进度、分页日志、ownerSession claim/条件写入、Android ON_START 恢复、按 `nextRetryAt` 延迟的一次性 WorkManager 补偿、dataSync 前台通知、Desktop 共享恢复入口、Compose 进度卡片，以及重开后恢复 FAILED/BLOCKED 终态、倒计时和重试动作。补充了暂停/取消竞态、重复恢复去重、attempt 不回退、重试预算、阻塞态持久化与进程重建接管测试。独立复审最终 PASS，未发现 must-fix；R1–R4 已勾选并提交。

本轮收口核验：同步 focused 契约继续通过（原型 progress/interaction 6/6、domain `SyncCoordinatorTest`、data 运行存储/迁移/wiring/controller、Android `AndroidSyncRuntimeWiringTest`、`presentation-sync` `SyncPanelContentTest`、Desktop `DesktopSyncSchedulerTest`、`spotlessCheck`）。Desktop 全量在修正本轮 `DesktopAppModule.kt` 新增回调造成的 parity manifest 行号漂移后通过（`3159 tests completed, 0 failed, 2 skipped`），并以发布未打包 EXE 的 Test Mode `/test/health`、`/test/state`、`/test/shutdown` 完成运行验收。Android fork 正式 arm64 APK 已以既有 `bd8e3af...a648cae3` 证书签名，包名 `app.mihon.desktop.fork`、versionCode `29`、versionName `0.19.4-aex.11`、ABI `arm64-v8a`；Windows 产物为 `0.11.19.50.df0b70c`。普通 Android `app.mihon` release 也完成 R8 编译但仅生成 unsigned 输出，未作为交付物。

补充设备验收：在隔离 API 26、33、35 与 36 AVD 上安装 fork universal release，锁屏/解锁分别观察到 `Awake → Asleep/Display OFF → 解锁`，`dumpsys deviceidle force-idle/unforce` 分别得到 `IDLE → ACTIVE`；force-stop 后没有自启，手动重新打开成功，崩溃缓冲区无 FATAL。API 33/35 镜像分别为 `x86_64-33_r17.zip`（SHA-1 `2b96f5bd5c79bfe1cc645e70b3e630b5755d9711`）和 `x86_64-35_r09.zip`（SHA-1 `0103e6dab21290c4b9d16550a3ce99476f884eef`）。隔离 R8 release instrumentation 在 API 33、35、36 均按 `prepare`/force-stop/`verify` 两进程执行，各 `4/4` 通过（密码格式、Keystore 缺失闭环、AEAD 篡改保护、跨进程仓库/Keystore 保留）。这些是平台生命周期与 release wiring 证据，不等同于已配置同步空间后的真实条目推进。

macOS 验收补充：在 `mbp-lan` 的独立 bundle 检出目录使用 JDK 21 和 `scripts/build-desktop.sh build-only` 构建成功，版本 `0.11.19.51.ab58a0a`；产物部署到 `/private/tmp/codex-mihon-deploy-r5/Mihon Desktop.app`，Test Mode 的 `/test/health`、`/test/state`、`/test/shutdown` 均返回成功。该构建复用已通过的 Desktop JVM 全量证据，未改动远端用户工作树。

2026-09-20：收口基线修复完成。由于新增 27 号同步运行迁移使生成数据库 schema 版本为 28，`CreatorArchiveV2Contract.LATEST_SCHEMA_VERSION` 与旧 schema 重建测试辅助已同步更新；`domain:jvmTest + data:jvmTest` 通过（`BUILD SUCCESSFUL in 16m 24s`）。`InstalledAppsPermissionAppModuleTest` 的隔离 Injekt scope 补注册放宽的 `AndroidSyncScheduler` mock 后，`:app:testDebugUnitTest` 通过（`BUILD SUCCESSFUL in 3m 4s`）；`spotlessCheck` 通过。以上变更只修复测试 fixture 与隔离 wiring，不改变生产同步语义。

R5 仍未完成：本轮没有真实账号/同步空间，不能宣称锁屏期间条目进度推进或重复应用已被真机证明；完整原型套件为 39 项中 35 通过、4 项既有 library-sync/UI 断言失败。上述剩余项仍是发布门禁，不能勾选 R5。
