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
