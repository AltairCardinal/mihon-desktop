# 同步空间恢复实施与验收记录

对应[实施计划](../roadmap/2026-10-03-sync-space-recovery-roadmap.md)。用户于 2026-10-03 授权实施；基线 `5ef39e5812`，本工作树启动时干净。此文件集中记录本轮证据，不创建逐任务快照。尚未取得的验证不记为通过。

## 范围与预算

共享故障判断、恢复 UI、显式新空间切换与首次导入；Desktop 和 Android 复用同一实现。最多两个实施代理：R1/R3 共享 data/domain 由同一代理串行承担，另一代理承担共享表现层；主代理维护接口、整合、独立数据安全审查和发布验收。同一 worktree 重型 Gradle 仅串行执行。预计 2–4 小时，一轮独立审查、必要修复复审一轮，focused 按实际红绿执行，最终全量一次，全部实现稳定后正式构建。

不清空本机数据，不删除真实远端空间，不借验收改用户授权范围；有风险远端复现使用 MockWebServer 或另获授权的隔离空间。真实 GitHub 检查只通过 production 客户端及正式产物进行，不以 curl/TCP 替代。

本能力覆盖共享 data/domain、Compose、Desktop adapter、双宿主契约及发布说明，已超过 8 文件/400 行提示值；这些是同一恢复流程的必要集成边界，不按文件拆分不可交付批次。主要风险为跨存储激活、旧批次目标隔离和异步回调覆盖；以固定身份/CAS、持久阶段、真实生产契约和独立核对约束。HTML 不在本轮修改范围。

## 已核对事实

- 实体 Android 已连接，SDK platform36 与 build-tools36 可用；最后安装的取证候选为 vc46。没有将本轮构建结果提前写为已安装或已通过业务验收。
- `SyncRuntime.bindSetup` 先停止 coordinator，再保存 SecureStore 连接，之后 `SyncBaselineStore.connectAndImport` 在数据库事务中激活空间并冻结当前本机导入基线。跨两种存储没有共同事务，切换需要显式持久恢复阶段。
- 原 `disconnect` 清除凭据，不能借此实现更换空间；原按账户单槽 pending 可能保护旧首次导入，不能删除来消除错误页。
- Desktop 通过 `DesktopLibrarySyncAction` 与 `DesktopSyncPanelSheet` 使用共享 `SyncPanelContent`；浏览器失败已有本端通知，向导可保持原地重试，不增设第二套恢复 UI。
- macOS `mbp-lan` 可达，但当前数据盘仅约 2.9 GiB 可用，既有同步 checkout 有未提交改动。本轮不覆盖该 checkout、不清理其他文件。当前待核对新隔离构建实际容量，不能仅凭剩余量宣称构建必然失败。脚本默认的隔离 JDK21 实际存在，不依赖系统 PATH 中不可用的 Java；若构建因容量中止，保留真实阻塞，不宣称通过。

## 验证记录

最终回归补充：首次全量在 data Android 400 项中发现 1 项诊断身份取证失败；修复为诊断专用纯读取后，400 项通过。继续未完成的 JVM 模块时，data 821 项出现 4 项失败（1 跳过），Desktop 3240 项出现作者页 1 项失败（3 跳过）；此轮整体失败，不称为全量通过。共享失败包括实时观察等待整个 exchange 锁、存储故障反馈被吞掉，以及旧诊断夹具错误地对已 CONNECTED 状态要求重复初始化。

`sync-space-data-regressions-final` 1 分 37 秒，104/104 通过、零跳过：JVM RuntimeWiring 28、失败诊断 7、慢速接收实时进度 1、面板诊断 12、切换 19、恢复 9；Android 切换 19、恢复 9。原 STORAGE 测试增加 FAILED 状态、运行记录不变和零 HTTP 断言；先确认新增状态契约红，再最小修复。CONNECTED 成功继续禁止重复初始化；初始化诊断保留在真实 SPACE_CONFIRMED 前置。Desktop 作者页未涉及本轮文件，正在以完整模块复验，尚不将其判断为基线或偶发问题。

观察与变更的锁边界：`connectionFacts`/`spaceRecovery` 仅在互斥锁空闲时补本地持久激活；网络交换持锁时直接读取现有投影，不等待网络、不执行恢复写入。exchange、确认、切换和显式检查仍串行互斥并在执行前验证持久门控。诊断直接使用纯原始取证，避免激活补偿提前改变待观察身份。此边界由真实生产慢速下载/上传进度和双宿主持久恢复契约覆盖。

`sync-space-desktop-final` 完整 Desktop JVM 复验与仓库格式检查通过：431 个类、3240 项、0 失败/错误、3 跳过；此前作者页失败本次通过，未修改该页或放宽测试。全量继续任务中其余 JVM 结果已核 XML：domain 568、presentation-sync 139、presentation-theme 26、core/common 56、source-api 19 全部通过；test-desktop 52 项既有有效结果复用（UP-TO-DATE）。不重复运行已通过且没有相关变更的规模压力组。

正式 Desktop 使用官方 `build-desktop.sh build-only`：同一冻结生产 diff 已有等价完整 Desktop 测试证据；只跳过重复测试，不跳过发布构建与实际运行验收。为规避已观察到的联网元数据等待，在用户 Gradle init.d 暂时加入仅匹配本轮 checkout 绝对路径的 offline guard，包装器 finally 删除自己的唯一文件；不改系统代理或其他 worktree。macOS 使用独立 source/deploy/dist，原脏 checkout 和既有应用不变。

发布证据（版本 `0.11.19.77.5ef39e5`）：

- Windows 统一入口已分配 BUILD77，但默认 PowerShell 分派退出 127；系统 PowerShell 和未加 NonInteractive 的原生 pwsh 启动也未进入构建。只读启动探针确认本运行环境原生 `pwsh.exe -NoProfile -NonInteractive -File` 可用，随后按相同已分配版本继续官方 `build-windows.ps1 -SkipTests -VersionAllocated -ExpectedVersion`；`sync-space-windows-noninteractive` 通过，实际 production 扩展安装与版本验收通过。未直接用 Gradle 代替发布脚本，没有重复版本分配。
- 日志 `Final unpacked EXE:` 为 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.77.5ef39e5-unpacked/Mihon Desktop.exe`，已确认存在。正式 Windows ZIP SHA-256 为 `7adca7fc1b4a08bdd067a7e5c139214bfb1c8298e1428f3ab353368103a84ae1`。
- Windows 正式 EXE 使用新的隔离配置启动实际 Test Mode，MAIN/RECOVERY 打开、关闭、再次打开通过；实际 DPAPI 后端的保留探针写入、进程退出、同一配置重启后读取并删除通过。`sync-space-windows-runtime.json` 为 PASSED。此检查使用未连接的新配置，不代替已连接账户的真实 GitHub 恢复业务验收。
- macOS 官方 build-only 通过（3 分 4 秒），实际应用为 `/Users/altair/github/mihon-space-recovery-release-20261003/Mihon Desktop.app`。同一正式应用的 MAIN/RECOVERY 打开、关闭、再次打开通过；加密存储 WRITE 返回 503/SyncSecureStoreException。同会话只读 `security show-keychain-info` 明确返回 `User interaction is not allowed`，属于本次真实 SSH 环境限制；不解锁用户钥匙串、不更换安全后端、不称为完整 Mac 同步验收通过。
- macOS ZIP 已传回本机 `app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.77.5ef39e5-macos.zip`，本机与远端 SHA-256 均为 `1f33397cc3d3d1f0e422a5ae1a6acdbe348f2c56da54bc03090fbe7cf1fbabfe`。Windows/Mac 本轮唯一 offline guard 均已删除。

- `sync-space-android-full-remaining` PASSED/exit0，16 分 27 秒：完成首次全量被中断的 Android 任务；app 677（7 跳过）、data 400、domain 473、presentation-theme 26、widget 3、source-api 19、core/common 10，共 1608 项，零失败/错误、7 个既有条件跳过。完整 Desktop 3240 项与本轮 Android 1608 项分别有独立终态日志，不把外层等待超时当作 Gradle 结束。
- 官方 `python scripts/build-android.py candidate --offline` 通过，R8/资源裁剪保持开启，原应用身份与原证书连续，versionCode47 / `0.19.4-aex.29`。正式 APK：`app/artifacts/android/0.19.4-aex.29-vc47-5ef39e5812-release/Mihon-Fork-0.19.4-aex.29-vc47-release-universal.apk`；SHA-256 `a2c007a66e7895297083dd5e0781fec3989f3fddeff09396b208d5e0a4ffb9ea`。候选内部校验与另一次官方 verify 均通过，四 ABI 完整、debuggable=false、v2/v3 签名有效、无 telemetry/updater、mapping 与本次 Gradle 完成记录一致。未将 Gradle 中间 APK 当作交付。
- 2026-10-03 05:43 UTC 再查 ADB 仍为空；没有执行安装或设备输入，也没有将平板状态判断为锁屏。设备重新连接的问题已向用户提出，尚无设备恢复证据。实际应用升级、原账号旧空间不可访问→新空间首次同步的实体业务验收仍待完成；Mac Keychain 重启读写也仍待可访问钥匙串的运行环境。

## 本轮交付与未关闭验收

R1–R3 已完成实现、主代理独立数据安全核对、失败项定向修复复验和发布产物构建；同一功能批次提交代码、测试、版本及说明。R4 的自动测试、原生离屏布局/事件、Windows 正式运行和三端构建已完成；R4 checkbox 保持未勾选，因为实体已连接账户的正式恢复业务和 Mac Keychain 验收未取得，不能以本地模拟或新配置 Test Mode 代替。

用户验收路径：运行本轮正式 Windows 程序（或安装本轮 APK）→书架→同步→恢复。原空间不可访问时应明确保留本机数据，并提供重新检查、检查授权、连接其他空间、创建新空间。选择创建后按向导在 GitHub 建立私有空仓库并授权，返回继续；目标和密码确认完成前不切换，取消不上传。成功激活新空间后从当前本机状态建立独立同步基线；旧未应用的待决定内容留旧空间并告知数量，不自动迁入。退出/重启后的继续切换应沿原持久意图恢复，而非回到旧失败重试。

边界：本轮不自动在 GitHub 创建或删除仓库，不改 App 授权选择；只能恢复本机仍存在的数据，不能复原已删除远端独有内容。其他设备必须分别明确连接新空间。永久恢复门控保留用户自动同步设置，但在恢复完成前不启动新的后台交换；普通网络和限流仍使用原重试语义。

计划 checkbox 仅随实现、审查、验证与提交均完成后更新。

- UI 初轮 `sync-recovery-ui-red` 编译成功，6/6 因缺少恢复节点或错误的创建确认说明而失败，证明原生产 UI 不具备所需入口。后续 candidates-red 13 项中 10 项通过，空候选入口正确失败，另两项为 LazyColumn 未完成布局的测试定位错误，已收紧为真实滚动并等待节点后执行；不把夹具错误记为产品红。
- 共享 R1 初轮正确红：必需仓库 404 仍返回 NETWORK；已连接快照入口 404 没有持久恢复事实。补充损坏恢复记录测试暴露 fail-closed 缺口。CONNECTED 测试的零导入夹具会先完成并清除 pending，已改为真实暂停导入并断言 pending 存在；该夹具超时不记为产品缺陷。
- Desktop `sync-recovery-desktop-red` 2/2 正确失败：原生关闭后触发器未获焦点，Test Mode 没有恢复投影。最小实现后 `sync-recovery-desktop-green` 2/2 通过，37 秒；实际书架包装将 OpenRecovery 与 Escape/关闭送至 panel，关闭后真实焦点回同步按钮。Test Mode 仅输出恢复枚举/忙状态，五个动作发送到同一 panel，不输出账户、URL、仓库 ID、密钥或恢复材料；控制器自身状态转换由共享集成验证承担。
- 已查看共享 Compose 离屏的中文浅色、英文深色 320 宽/200% 字体恢复方式页，说明和四条路径可到达；顶栏沿原有单行省略。图像位于忽略的 `presentation-sync/build/sync-visual/`，是本轮候选布局证据，不称为上游像素基准，也不代替正式运行。

- R1 独立核对发现三类缺口：显式检查覆盖未来版本的恢复记录；返回/授权后旧检查结果重新导航；已认证索引身份不匹配仍归 UNKNOWN。对应 `sync-space-r1-boundaries-red` 有 3 项正确红，`sync-space-r1-index-red` 有 1 项正确红。最小修复后 `sync-space-r1-boundaries-green` JVM 9/9 通过（63 秒）。复核确认 SecureStore 写前先验证既有版本，取消检查同时递增回调版本；远端校验类型只用于已核验树结构与 Git OID 校验后载荷，不将任意网络解析或本机异常归为远端损坏。
- 恢复 UI 补充检查反馈：`sync-recovery-feedback-red` 3/3 因缺少检查中/未完成反馈正确失败（24 秒），最小实现后 `sync-recovery-feedback-green` 3/3 通过（33 秒），表现层格式检查通过。主卡与恢复页明确反馈检查进展；NETWORK/STORAGE 显示本次检查未完成及产品原因，不重复永久恢复原因。
- macOS 已在 `/Users/altair/github/mihon-sync-space-recovery-20261003` 建立隔离 checkout，基线 `5ef39e5812`；只传输 Git bundle 并本地 clone，不修改原脏 checkout。剩余空间仍约 2.9 GiB，尚未执行本轮构建。
- Android 正式签名预检 `python scripts/build-android.py check --signing` 通过，JDK21、SDK36、build-tools36 和原发布证书可用；该预检不是候选构建或安装证据。当前 vc46 尚未作为本轮版本，正式候选另行递增。
- R3 固定设计：按稳定 intentId 保存独立版本记录和独立目标 setup，以账户活动指针 CAS 选择，原 v3 pending 与此前目标记录保留。阶段为 PREPARING/ACTIVATING/COMPLETE；确认固定账户、旧绑定版本和新目标身份。ACTIVATING 未完成时阻止上传，冷启动只补本地提交；合法初始化仍复用原 checkpoint，不自动重写远端。健康旧连接在 PREPARING 时可显式重查并取消未提交切换；UI 用独立 SWITCH_PENDING 文案，不将该状态归为远端删除。具体代码与崩溃验证待本批完成。
- R1 扩展回归时，`sync-space-r1-freeze-fixed` 暴露两处合法初始化兼容失败：旧空分支通过固定缺失原因进入受保护初始化，新通用 HTTP 消息打断该判断。保留旧测试，改用远端校验类型并保留实际树缺 descriptor/index 的原因；真实 HTTP 404 仍独立分类。`sync-space-r1-final` 46 秒通过，XML 核对 JVM 恢复 9、Git Safety 36、HTTP Safety 12，Android 恢复 9，共 66 项零失败；data/domain 格式检查通过。既有损坏空间拒绝覆盖与竞争初始化语义得到保护。
- Desktop 模块未注册 Spotless，调用 `:app-desktop:spotlessApply` 在任务解析阶段失败，未运行或修改产品。核对该模块实际插件后，不为此添加新插件；本轮四个 Desktop 文件遵循原有格式、无超长行且 `git diff --check` 通过。最终仓库格式检查覆盖既有已注册模块。
- R3 表现层 4 项正确产品红覆盖目标/旧待决定数量确认、创建确认真实数量、未完成切换独立说明、改名会话通知；新增第 5 项实际布局红确认长目标说明在 320 宽/200% 字号被裁切且无法阅读。原 AlertDialog 说明区域加纵向滚动后，`sync-space-r3-ui-green-fixed` 21 秒 5/5 通过，表现层格式检查通过。主代理独立查看 `presentation-sync/build/sync-visual/space-connection-confirm-320-2.0-zh.png`：长目标、109 项留旧空间说明与确认/取消均可读可达。该证据保护 UI 呈现与事件派发，尚不证明 controller 激活成功；真实激活另由 R3 生产契约验证。
- `sync-space-r3-red` 编译成功，真实 HTTP/SQLite 两项正确失败：ConnectOther 未到候选选择，CreateNew 未产生确认（44 秒）。尚未实施跨存储流程时保留这些行为红，不把 UI 静态通过当作激活通过。根审查补齐明确的“继续切换”入口契约：复用 BeginSetup 优先恢复活动 intent，不能重新读取旧 v3 pending 或创建新 intent；ACTIVATING 只补本地提交。
- “继续切换”共享 UI 新增 1 项两页面真实点击测试，23 秒正确红后 `sync-space-continue-ui-green` 34 秒转绿，表现层格式检查通过；仍待 R3 production 意图恢复验证。根源码核对另外发现 bulkJob 独立于 coordinator 的切换竞态窗口，以及 exchange SUCCESS 结算经 connectionFacts 重入非重入 mutex 的路径，已交原实施者定向复现与修复；此时尚无该两边界的运行通过证据。

- R3 后续真实生产契约的 checkpoint 红暴露目标 checkpoint 已写、意图 target 尚未写的中断恢复缺口；按独立目标 checkpoint 修复后恢复同一意图。SUCCESS 候选回归以原重入实现定向复现 1 项正确超时红，改为结算使用已捕获连接，正常 SUCCESS 可在有界时间内完成。
- `sync-space-r3-exit-red` 2 项正确红分别为已确认目标 404 后显式另选仍返回旧意图、密码页返回不能回候选；另加原空间重查失败错误取消草稿的红。修复后 `sync-space-r3-exit-green` 16/16 通过（66 秒，协调器实际退出 0）。源码独立核对：显式新选择归档独立记录并保存 previousIntentId，不 retarget 原初始化 checkpoint；只有原空间身份与内容核验成功才取消 PREPARING；普通继续/重试优先活动意图，不能被旧单槽 pending 抢占。
- `sync-space-r3-final-cases-fixed` JVM 切换 18/18、表现层真实 controller/HTTP/SQLite 集成 2/2 通过（67 秒）。覆盖同名新 ID 空仓库、错误密码/账户漂移、连续切换、旧 pending、旧待决定批量操作、数据库 trigger 导致基线事务回滚、四个激活 SecureStore 中断点及同库同密封存储重启；冷恢复不发 HTTP，不重复基线。新目标已初始化到 SPACE_CONFIRMED 再删除时，显式另选保留原 checkpoint 和可追溯归档链，不陷入永久重试。真实旧 bulk job 在准备期间和新空间激活后仍保留 3 条旧空间待决定项，不修改新空间数据。
- 改名首次失败夹具使用错误分支，不计为产品红；换为真实固定分支后，临时还原旧地址检查路径，`sync-space-r3-rename-red` 1/1 正确失败（32 秒），恢复实现后正常改名及三个本地地址提交中断点全绿。核对相同固定 ID、完整身份与加密内容验证先于本地提交，既有禁止自动转发重定向凭据策略保留。
- 收口期间一次在线 Gradle 配置等待，经 jcmd 核对停在依赖元数据 HEAD/TLS；只停止协调器记录的本任务进程树，不全局终止 daemon。离线复跑进入实际测试。未知切换记录的四项调度查询原先会抛出 UnsupportedSyncSpace，离线正确红后修复为保留可读本地投影、恢复判断仍 fail-closed；没有把配置等待当作产品红。
- 扩展 legacy 回归保留全部原断言：旧不支持的绑定在前置查询不被新切换逻辑抢占；已断开 legacy 仍允许原有新设置路径；坏密钥和已认证损坏数据保留 INVALID_DATA，而持久恢复 reason 为 SPACE_DATA_INVALID。新增索引测试随此兼容语义改 problem 预期，完整性、持久阻塞、零新运行和零 HTTP 断言未放宽。
- 冻结候选适用证据为 137 项：`sync-space-r3-compat-final` JVM 恢复 9、原 onboarding/bootstrap/legacy 20、Android 恢复 9，38/38（65 秒）；前批 `sync-space-r3-freeze-final` 有效通过的双宿主切换 38、HTTP 12、Git Safety 36、baseline 11、真实 Compose controller 2，共 99 项。该前批自身曾有其他失败，不把整次调用记为成功。实际旧 outbox 已冻结并保存密文 artifact，再切换并真实上传新基线；两宿主核对旧密钥/批次/事件/历史不变，新远端解码只有新 scope/actor 的当前本机 INITIAL_IMPORT。
- 最终一次仓库全量 `sync-space-recovery-full` 在 Android data 400 项中发现 1 项原诊断一致性回归后停止（95 秒），尚未执行的 JVM/测试客户端/格式不记为通过。原因是 connectionFacts 新增本地恢复预读发生在诊断 before 事实捕获之前。仅将同一 raw decoder 提供给诊断 readFacts，保持诊断纯只读；普通连接查询的冷激活补交不变。原测试构成正确红；`sync-space-diagnostic-fix` 双宿主各 diagnostic 12、switch 19、recovery 9，80/80（60 秒）转绿，格式通过。随后 `sync-space-recovery-remaining` 只复验受影响 data Android 完整模块并接续尚未运行的 JVM、测试客户端和格式；不重复已完成的全仓 Android 检查。

## 存储与维护边界

本轮不迁移数据库 schema、不改变远端同步格式。沿用原 SecureStore 密封记录，恢复判断按精确 connectionRevision 绑定；固定仓库 ID 和空间标识是身份，名称只是地址。未来版本或损坏记录不视作“没有记录”，不得覆盖；后台停止分配新运行，显式操作反馈存储问题。

切换意图记录 `sync-switch-intent-v1-<intentId>`、目标 checkpoint `sync-switch-setup-v1-<intentId>` 与账户指针 `sync-switch-pointer-v1-<accountId>` 分开保存。新意图通过 previousIntentId 引用前一个意图；原账户 v3 pending、旧连接材料、旧 import/outbox/inbox 和历史不删除。新空间使用新的身份及基线；再次连接曾用空间时保留它既有身份，通过 BACKUP_RESTORE 建立当前本机基线，不复用旧首次导入作为当前内容快照。

阶段 PREPARING 只准备固定目标，ACTIVATING 保存确认后过程，COMPLETE 表示本地连接和基线已提交，CANCELLED 归档未完成的显式选择。用户“继续切换”恢复同一意图；“连接其他空间/创建新空间”可以归档尚未保存 targetConnection 的意图并创建独立目标，即使原目标初始化已经写过远端，也不复用或改写原 checkpoint。已有 targetConnection 的激活只补固定本地提交，不能借另选目标绕过部分提交。归档保留远端初始化证据，不删除远端仓库或对象。

跨存储激活顺序：密封保存目标 connection → CAS 绑定材料 → SQLite 单事务激活目标并以 intentId 冻结唯一基线 → 保存 CONNECTED checkpoint → 意图 COMPLETE。重启沿固定材料幂等补交，不再次初始化远端；数据库事务失败保留旧活动空间和原基线。原空间 bulk 工作在准备/确认前取消并等待，但持久 job 和待决定条目保留；既有 projector 的 active(scope) 事务检查保护旧 job 不落到新空间。

改名使用独立密封 address-v1 记录：完整身份和加密验证先于任何本地写入，之后更新连接、数据库地址和对应 pending，最后标记完成；中断后的本地补交不访问远端。沿用不自动跟随重定向的 production 客户端，授权清单提供固定 ID 的规范地址；不向跨域或降级地址转发凭据。

诊断读取复用相同 raw decoder，但不触发本地激活；在 before/after 事实之间保留真实身份变化的检测。维护时不得把可补交的正常连接读取直接接回诊断，以免观察本身提交状态或漏掉空间切换。

当前尚未取得正式产物及最终全量验证，不宣称本轮功能完成。
