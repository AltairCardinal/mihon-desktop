# 同步错误页恢复出口与本地 APK 验收

## 固定范围与验收

基线 13e4f1ebf7。用户批准错误恢复设计，要求实现与本地正式 APK，不上传云端。

- SOURCE：共享 SyncPanelController/SyncRuntime/SyncPanelContent 的错误、检查、授权、切换 intent 和确认链路；复用原页面及控制器，不增加新的同步服务。
- PROJECT_POLICY：通用错误在原绑定可读取且支持时同时提供重试、更换、详情；首次配置使用设置入口；读取失败/不兼容不暴露不能执行的切换。
- PROJECT_POLICY：已确认空间不可用推荐更换；授权失效推荐授权；pending 优先继续；网络和未知不断言删除。
- PROJECT_POLICY：重试忙碌防重复，失败显示明确反馈；进入恢复选择不会修改绑定，返回恢复原错误页。既有授权/检查与 pending 不因导航丢失。
- PROJECT_POLICY：原因未知的恢复页使用中性创建/连接选择，不声称原空间已损坏。创建/连接沿用确认；明确提交前保留旧连接和本机数据。
- HTML_ADAPTER：本轮不改原型，不用 HTML 代替原生共享界面与生产控制器验证。

## 过程预算与验证

一个实施代理负责状态/控制器/真实存储与 HTTP 契约，主代理负责界面、翻译、整合与一轮独立安全边界审查。重型 Gradle 串行，先 focused 红绿，最后相关契约和格式。交付正式候选 APK，不操作实体设备，不更新 Sites。

新增错误操作和返回逻辑只作用于当前面板会话；已有连接、凭据事实与切换 intent 的持久化机制保持原链路。超过文件估算仅因共享行为与双语言资源属于同一功能批次，不做无关重构。

## 验证结果

- focused 红测：错误页入口、未知原因选择、失败重试反馈、诊断返回均先因预期行为缺失失败，再补实现。
- 独立审查发现冷连接读取失败会误认为首次配置、凭据变化后会误入空向导；新增真实控制器失败契约，最小修复后均通过。共享界面同时验证旧恢复事实不能绕过当前可操作性。
- data JVM：恢复 23、切换 19、面板存储 52，全部通过。
- data Android Release：恢复 23、切换 19、面板存储 51，全部通过。
- 初次收口格式发现过长测试名，已缩短；旧 UI 授权测试随已批准的新恢复入口更新为 CheckAuthorization 并同时覆盖更换事件。最终共享 Compose 界面与终态恢复集成 131 项全部通过；data、presentation-sync、i18n 格式检查通过。
- 正式签名预检通过；包名与原证书保持连续。共享 UI 同时影响 Desktop，按项目要求经正式 Windows 构建脚本验证；使用 PowerShell 平台入口绕过已知 bash → PowerShell -File 派发故障，不直接 Gradle 部署。

正式构建与验证均已完成，详见下文。

Gradle 配置阶段曾停在依赖 Socket.connect；仅停止协调器记录的本轮进程树，设置当前会话 HTTP/HTTPS 代理与本地 bypass 后继续，同一验证成功。未修改系统代理。

## Desktop 构建边界

正式脚本首次执行完整 Desktop JVM 测试：3236 项中 3235 通过，唯一失败为 DesktopSyncPanelTest 的旧模拟状态没有 canChangeSpace，仍等待被正确隐藏的更换按钮。仅补齐该 fixture 的事实，生产 diff 不变；对实际 LibraryRootScreen → 面板 → 恢复页 wiring 进行 focused 复验，沿用其余 3235 项证据，避免重复全量。正式平台脚本随后以 VersionAllocated、SkipTests 继续同一版本构建与 production runtime 验收，不另分配版本。Desktop 没有 spotlessCheck 任务，该查询失败不作为格式证据；修改仅增加一行 fixture，使用 diff --check 核验。

- Desktop focused wiring 复验通过；同版本官方 Windows 平台构建脚本完成打包、production runtime 版本验收与真实扩展 APK 安装验收。
- Windows 正式产物：[Mihon Desktop.exe](../../app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.80.13e4f1e-unpacked/Mihon%20Desktop.exe)，版本 0.11.19.80.13e4f1e。
- Android 候选分配 versionCode 50 / 0.19.4-aex.32；签名预检通过，正式 candidate 构建与 verify 均通过。

## Android 正式交付与手动验收

- APK：[Mihon-Fork-0.19.4-aex.32-vc50-release-universal.apk](../../app/artifacts/android/0.19.4-aex.32-vc50-13e4f1ebf7-release/Mihon-Fork-0.19.4-aex.32-vc50-release-universal.apk)。
- SHA-256：`e72f3e8727449d8b13ee0b58b7d1f0dfe5cb9665737b8ecf03cd2a380272ec2d`。
- 包名 app.mihon.desktop.fork，versionCode 50，原证书 v2/v3 签名验证通过，不可调试，R8/资源压缩开启；无遥测和更新器。正式 builder 2m51s 完成。
- APK verify 明确为 candidate-only；本轮没有安装或操作用户设备，Android 实机升级与交互仍由用户验收；没有上传 Sites。
- 签名预检一次受 JAVA_TOOL_OPTIONS stderr 被 PowerShell 识别为错误影响；移除该变量，仅用 GRADLE_OPTS 为 Gradle 设置会话代理后成功。未更换密钥、未改脚本或系统代理。

验收路径：

- 同步 → 出现一般失败 → 查看重试、更换同步空间、详情；进入更换时不改变当前绑定或本机数据，返回恢复同一错误页。
- 同步 → 已确认空间不可用 → 更换作为首要操作，并可重新检查；不把未确认原因直接描述为已删除。
- 更换同步空间 → 未知原因选择页 → 中性创建/连接两种路径；实际创建/连接仍通过原确认流程。
- 授权异常 → 重新授权入口；首次配置 → 设置同步空间；读取失败/不兼容 → 不提供不可执行的更换按钮。
- 点击重试 → 忙碌期间防重复；再次失败 → 明确重试未成功。

本批次生产行为、测试、双语言文案、版本分配与记录一次提交；构建保留基线 HEAD 加冻结生产 diff 的候选证据，最终 commit 不改变 APK 生产输入。

## 安装引导信息层级修正（后续迭代）

用户批准隐藏重复配置按钮、把诊断入口改名并后移。SOURCE：共享 SetupPage(ERROR) 复用现有安装/权限引导、RetrySetup 与 DIAGNOSTICS；PROJECT_POLICY：安装缺失、仓库授权缺失、权限不足、安装暂停或不可写的引导页不显示设置同步空间，诊断信息排列在该页主要操作之后。空间不可用的更换流程仍保留，不套用安装引导规则；HTML_ADAPTER：本轮不改原型。没有存储、HTTP 或控制器变更。

一个新增 Compose 真实交互测试覆盖安装缺失与仓库授权缺失：验证无重复配置按钮，诊断在重新检查之后、名称明确且导航动作有效，重新检查继续原事件。先因旧配置按钮仍存在正确失败，再最小实现。无子代理；focused 红绿后共享 UI 全部相关回归/格式、官方 Windows 构建与正式本地 APK，用户实机自行验收，不上传云端。

本轮 shared Compose 132 项（界面 122、终态集成 10）全部通过；presentation-sync 与 i18n 格式检查通过。初次格式检查发现新增条件超过 120 列，已换行并通过复验。仅这项小范围表现层变化，不重复数据模块或全平台矩阵。

正式候选分配 0.19.4-aex.33 / versionCode 51，原包名和证书保持连续；Windows 同步迭代经官方平台构建脚本执行完整 JVM、打包与 production runtime 验收（复用上一轮已确认的 PowerShell -Command 派发方式）。正式候选构建与 verify 均通过，产物如下。

本轮 Desktop 完整 JVM 测试 3236 项全部通过，官方构建继续打包与运行验收。

本轮 Windows 正式构建、production runtime 版本与扩展安装验收通过。产物：[Mihon Desktop.exe](../../app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.81.9641a27-unpacked/Mihon%20Desktop.exe)。

本轮正式 APK：[Mihon-Fork-0.19.4-aex.33-vc51-release-universal.apk](../../app/artifacts/android/0.19.4-aex.33-vc51-9641a275f6-release/Mihon-Fork-0.19.4-aex.33-vc51-release-universal.apk)。SHA-256：`d20e3aa794c414ebba0cb31afdcfc74c17004ab93603eca20da8ad2323adf167`。Release candidate 构建 3m41s，原证书 v2/v3 签名校验通过，包名保持 app.mihon.desktop.fork；R8、资源压缩开启，不可调试。没有上传云端、没有安装或操作用户设备，Android 实机交互与升级由用户自行验收。

本轮验收：

- 安装缺失或仓库授权引导页 → 不显示设置同步空间；创建仓库、安装/管理权限与重新检查操作保留。
- 同页 → 诊断信息在主要操作之后；点击进入原诊断页，返回原错误页（既有控制器返回逻辑）。
- 一般错误与空间不可用 → 既有配置/更换恢复出口保留，仅针对安装引导调整信息层级。

## 统计暂停恢复动画与进度条终点修正（后续迭代）

SOURCE：SyncRuntime 在已存在的同步轮恢复时发布 RECOVERING hold；SyncProgressDisplaySession 对该 hold 不计算速度/估时，旧 CompactProgressCard 又把它当作停止动画。共享 Material 3 确定进度组件默认绘制终点标记，即使 fraction=0 也会出现亮点。

PROJECT_POLICY：总数未冻结且 run=RUNNING、hold=ACTIVE 或 RECOVERING 时，显示正在统计数据与无限等待动画；暂停、离线、系统等待仍静止，统计完成前仍不显示时间。总数已冻结时使用真实 fraction，并禁用默认终点标记；仅绘制真实完成进度，不把 0 条显示成已有进展。估时计算和持久化语义保持原链路。HTML_ADAPTER：本轮只改共享原生界面，不改原型。

无子代理；两个 focused Compose 测试先正确失败（恢复统计动画不存在、零进度末端颜色不同）。测试执行真实暂停/恢复事件，并覆盖主面板与首次合并页面；离线转静止。离屏原生像素测试覆盖 0/100 与 25/100，验证未完成右端与未填充轨道同色，同时语义 fraction 准确，不读取系统屏幕。最小 UI 实现后 focused 红绿、相关界面/展示/控制器集成回归与格式、项目要求的 Windows 正式构建及本地 APK；不上传云端，不操作用户设备。

本轮相关回归、格式、正式构建与原证书签名校验已完成；Android 用户实机验收待进行。

本轮相关模块 156 项回归中 155 项通过；唯一失败为旧真实控制器集成用例等待 RECOVERING 统计状态的静止轨道，正是本轮要求改变的行为。该用例改为等待无限轨道、验证 Indeterminate 且无静止轨道/计时；保持真实数据库和 controller wiring，不放宽超时或删场景。复验仅此集成用例与格式，复用其余 155 项证据，不重复全量。

真实 controller 集成 focused 复验与格式检查通过，相关 156 项证据收口。正式候选分配 0.19.4-aex.34 / versionCode 52，Windows 构建经官方平台脚本进行。

本轮 Desktop 完整 JVM 3236 项中 3235 通过；唯一失败为未修改的 LibraryMangaTestModeHttpTest 本地 HTTP 客户端收到空响应头（HTTP/1.1 header parser received no bytes）。同类 focused 复验通过，未改生产或测试代码；异常未复现，真实原因未证实，不将其归因于本轮 UI。沿用其余 3235 项有效证据，仅复验受影响类。官方 Windows 平台脚本继续以 VersionAllocated/SkipTests 对同一生产 diff、同一版本构建与运行验收，不重复全量或再分配版本。

本轮 Windows 正式构建与 production runtime 版本、扩展安装验收通过：[Mihon Desktop.exe](../../app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.82.ef4b512-unpacked/Mihon%20Desktop.exe)。

本轮正式 APK：[Mihon-Fork-0.19.4-aex.34-vc52-release-universal.apk](../../app/artifacts/android/0.19.4-aex.34-vc52-ef4b512e52-release/Mihon-Fork-0.19.4-aex.34-vc52-release-universal.apk)。SHA-256：`082602a111cb8b3f5d7c101e3eeb7501d962a003881d3c1e31bf4309070c0fa6`。正式 candidate 构建 2m25s，verify 通过，包名 app.mihon.desktop.fork、versionCode 52、原证书 v2/v3、不可调试、R8/资源压缩开启。未上传云端，未安装或操作用户设备。

本轮手动验收：

- 尚在统计数据 → 暂停 → 恢复 → 无限等待动画继续，显示正在统计数据，仍无时间行；统计实际完成后才显示完成/总数与计时。
- 主同步面板、首次合并面板均适用；暂停与离线仍显示静止轨道，不能用动画暗示正在执行。
- 已完成 0/总数 → 没有右端亮点；部分完成 → 进度条反映真实完成比例，未填充末端仍无亮点。

没有改变同步统计、保存、暂停/恢复或估时业务逻辑；修复局限在共享 Compose 表现层，并覆盖真实 controller 的状态接入。
