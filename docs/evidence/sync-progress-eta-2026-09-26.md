# 同步三阶段进度与 ETA 验收记录

日期：2026-09-26。对应[需求](../2026-09-26-sync-progress-eta-requirements.md)和[实施计划](../roadmap/2026-09-26-sync-progress-eta-roadmap.md)。

## 用户行为与边界

Android 和 Desktop 的“书架 → 同步”及首次配置合并页使用同一共享面板。面板显示准备数据、传输数据、确认结果、当前动作、条目数、已用时间与可信的阶段或整体 ETA。条目是去重后的同步事件，传输百分比按已知 HTTP 正文工作量计算；未知总量或样本不足时保持不定状态。成功只依据必要的持久确认，部分成功显示已确认、待处理和可执行的重试提示。暂停、离线、重试与恢复不会靠墙钟假推进。

迁移 37 将每次运行的已确认条目数及方向/批次回执持久化。下载只有完成接收、索引和必要投影后才能确认；投影仍待处理时保留 `PARTIAL` 和原运行身份，手动/周期操作可继续核对，启动时不会自动把它宣告成功。上传仅在真实远端 ACK 后确认。HTTP 字节回调只更新有界内存状态，不逐块写库。

本轮保持现有批次、加密与发布策略。41 个批次的样本用于数量与性能验证；“41 批整组原子发布”没有作为这次功能实施。实际 GitHub 仓库写入、读屏真人测试和用户设备上的视觉验收尚待执行。

## 自动化与运行验收

| 范围 | 执行结果 |
| --- | --- |
| TDD | 先写失败的时间线、限速 HTTP、文件数据库、跨运行投影、共享面板和 Android Worker 回归测试，确认正确失败，再实现并复验通过；独立审查 1 轮，针对阻塞项聚焦复审 1 轮。 |
| 数据层 | `:data:jvmTest` 全模块首次运行报告 `648 tests completed, 2 failed, 1 skipped`；两处失败均为旧迁移测试夹具未清除迁移 37 的新索引/表。修复夹具后，受影响的 `JvmSyncInboxStorageContractTest` 与 `CreatorArchiveMigration16Test` 整类通过，`:data:spotlessCheck` 通过。其余数据测试在首次运行通过；未重复运行全模块。 |
| Android | `:app:testReleaseUnitTest` 全模块通过；`:app:assembleDebug` 通过。在隔离的 API 35 模拟器安装 x86_64 APK、冷启动、完成初始引导并打开“书架 → 同步”，面板正常显示；该模拟器已停止。 |
| Desktop | `scripts/build-desktop.sh full-tests` 全量 JVM 测试通过；`:test-desktop:test` 通过；全仓 `spotlessCheck` 通过。Windows `scripts/build-desktop.sh build-only` 通过，发布 EXE 以隔离配置运行 Test Mode，HomeScreen → LibraryTab → 正常关闭通过。 |
| macOS | 在同基线隔离 worktree 上用 `bash scripts/build-desktop.sh build-only` 成功构建并部署 `0.11.19.57.beba814` 到 `/Users/altair/Applications/Mihon Sync ETA.app`；该应用以隔离配置运行 Test Mode，HomeScreen → LibraryTab → 正常关闭通过。 |

Windows 正式未打包产物：`D:\Codex\worktrees\85be\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.19.57.beba814-unpacked\Mihon Desktop.exe`，已按构建日志 `Final unpacked EXE:` 核对存在。Windows ZIP：`D:\Codex\worktrees\85be\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.19.57.beba814-windows.zip`，SHA-256 为 `2832c398cc89b2460d277f2c03b559ed9e3cba2fb8e63cdd8d1afbca76282cfe`。Android x86_64 debug APK：`D:\Codex\worktrees\85be\mihon\app\build\outputs\apk\debug\app-x86_64-debug.apk`。

Windows 验证日志保存在忽略目录 `.gradle-coordinator/` 中的 `sync-eta-android-full.log`、`sync-eta-desktop-full.log`、`sync-eta-windows-build.log`、`sync-eta-final-module-gates.log`、`sync-eta-migration-fixture-green2.log`、`sync-eta-test-desktop-final.log`。macOS 构建日志位于 `/Users/altair/Library/Caches/mihon-sync-eta-build.log`。日志为过程产物，不纳入版本库。

## 部分完成结果展示修复（2026-09-26）

实机只读诊断发现，同步运行已持久化为 `PARTIAL / projection_pending`，最后更新后数据库没有继续变化；界面却仍显示最后一个空下载轮次的活动状态及 `0 / 0`。当时已确认 26,778 条，未确认下载回执为 2 批、共 512 条；这些批次关联的当前来源不可用字段共 10 项。512 是整批事件数，10 是字段数，二者不能相减，也不能据此认定 512 条都因漫画源不可用而失败。

本次修复限定为结果展示和只读摘要查询。部分完成须明确本次执行已经结束，显示持久确认数、待确认批次数及其包含的条目数，并单独解释漫画源不可用的待处理内容；关闭重开或进程恢复后语义一致。同步终态不得沿用活动标题、不定进度条或空轮次 `0 / 0`。首次设置合并页和常规同步页使用相同规则，结束时间保持冻结。重试复用现有同步操作；来源恢复与批次确认条件保持原有行为。

验证遵循红绿流程：`sync-terminal-assertion-red` 记录共享 Compose 界面两项断言失败；`sync-terminal-retry-red` 记录旧入口不执行部分完成重试。`sync-terminal-focused-verified` 通过真实 SQLite 摘要、字段去重、运行隔离、重开恢复及重试不虚增确认数的 wiring 测试，另通过 6 项终态/大字体/时间冻结相关 UI 测试，以及 data、presentation-sync、i18n 的 Spotless 检查。主代理独立审查已完成，确认待处理查询限定当前运行、空间与 generation，且不进入每秒计时路径。

最终 `sync-terminal-final-gates` 通过 `:presentation-sync:jvmTest` 全模块 56 项测试及 `:app:testDebugUnitTest --tests eu.kanade.tachiyomi.data.sync.AndroidSyncPanelTest` 的 5 项宿主集成测试。`sync-terminal-apk` 通过 `:app:assembleDebug`。日志均位于忽略目录 `.gradle-coordinator/`。本批涉及 11 个文件，是同一结果展示能力所需的查询、状态、共享界面、双语文案、测试与文档；未拆分同步协议或引入数据库迁移。

本轮 Android 验收包：`app/build/outputs/apk/debug/mihon-sync-result-ui-20260926.apk`，实际包名 `app.mihon.dev`、版本 `0.19.4-9208`、SHA-256 `95087b480f591e88982a84a203e7a17b2193afb5592c369e6298e566c4004525`，已核对产物存在。本轮未重建 Windows/macOS；共享 UI 由 JVM 测试覆盖。新 APK 尚待用户安装后实机验收，上述修复前实机观察不能代替新包验收。

## 缺源恢复与完整失败日志（2026-09-27）

本轮目标是使现有同步类别在身份与描述完整时能够脱离图源扩展恢复本地数据，并为确实无法恢复的数据保存完整本地文本报告。图源是否安装、能否访问网站，不应成为写入已有同步数据的前提；网站浏览、刷新章节及图片下载仍依赖图源。本轮不扩大同步字段范围，不改变多设备冲突规则或批次安全确认条件。

恢复部分 TDD：`sync-source-free-red` 的 4 项断言在旧图源门槛下失败；`sync-source-free-green-first` 的共用投影与收件合同通过，覆盖缺源新建漫画/章节、收藏/阅读字段、旧 SOURCE 状态重试、缺描述字段隔离及取消回滚。`sync-source-free-runtime-fix` 的真实 Git/HTTP 同步测试通过，覆盖无源上传的远端确认边界，以及无源接收端落地并确认一次。`sync-source-free-android` 通过 Android 数据库实现的投影、收件、出站共用合同。主代理已独立检查恢复代码与测试，确认身份、描述及原事务保护仍生效。

失败日志 TDD：先运行缺失接口红测及共享 UI 断言红测，再实现日志存储、终态生成和平台打开。`sync-failure-core-final` 通过数据层 JVM/Android 投影、收件、历史合同，以及完整报告、真实 runtime、共享 UI 和对应模块格式检查。报告用真实 SQLite 数据覆盖 520 条失败字段（含 SOURCE/DESCRIPTION/IDENTITY）与 1 条无效事件，检查全文、运行/空间隔离、磁盘失败和重开后重建；真实同步测试覆盖终态自动生成、冷面板读取与文件丢失后重新生成。

平台接线由实施代理独立只读审查，主代理独立审查共享恢复、查询范围、报告与缓存边界。Windows 上真实 AndroidX FileProvider 的路径判断与 Robolectric 反斜杠主机路径不兼容，三项真实 provider 用例限定 Unix 执行。Mac 隔离 worktree 的 `sync-failure-unix-provider-isolated` 实际 XML 为 8 tests、0 skipped、0 failures、0 errors，验证真实内容 URI 的中文读取、目录约束、原生面板回调和无查看器分享回退。测试清理 FileProvider 静态目录缓存，避免 Robolectric 每例创建不同 filesDir 时串用路径；未 mock FileProvider。Mac 缺失的 FlexibleAdapter AAR 和 Robolectric 运行包复用 Windows 缓存，未修改仓库依赖声明。

本批涉及共享恢复、持久失败查询、报告、双端文件打开与 DI、共享界面、测试和维护文档，超过 8 文件/400 行仍属于一个可独立交付的能力。主要风险是误确认数据及跨目录文件读取，分别通过真实持久确认合同与原生文件打开边界测试覆盖；未新增数据库迁移或同步格式。

最终测试：

- `sync-source-final-suites` 执行 data 全模块 662 项，首次 3 失败、1 跳过；XML 定位为两处旧测试错误地把最后一个空进度范围当作终态，以及重开面板测试未停止后台刷新导致跨测试数据库关闭异常。修正断言和 finally 清理后，`sync-source-suite-regression-fix` 的 Runtime/S2 两类共 34 项通过，同时全仓 `spotlessCheck` 通过；未重复运行全数据模块。
- `sync-source-android-ui-offline`：Android 全模块 598 项、0 失败、3 个 Windows 不兼容 provider 用例跳过（由上述 Mac 的 8 项真实宿主测试补足）；共享 UI 全模块 57 项、0 失败；APK 构建通过。初次配置阶段的依赖网络等待被取消，实际测试和构建使用本机缓存完成。
- Mac `scripts/build-desktop.sh full-tests` 执行 Desktop 3165 项，首次 2 失败、9 跳过。一项为 DI 新增参数使 parity manifest 的两个源码行号偏移，已仅更新行号；另一项为现有真实 macOS 凭据后端测试在 SSH 会话无法访问钥匙串，`security show-keychain-info` 明确返回 `User interaction is not allowed`。不更改凭据逻辑或绕过钥匙串。
- `sync-source-desktop-platform-final` 在 Windows 聚焦验证 parity 合同、真实系统凭据后端及同步面板、DI、报告打开器，共 42 项全部通过。完整 Desktop 测试仅执行一次；平台特定失败如上记录，不把 macOS 凭据访问说成已验收。

Android 通用 APK：`app/build/outputs/apk/debug/mihon-source-independent-sync-20260927.apk`，包名 `app.mihon.dev`，版本 `0.19.4-9209`，SHA-256 `c5897164b63ccf9f63b695cfd8bece459cce9bb82889a1dd91f9c22376aeff57`。新产物尚待用户实机验收。

桌面使用既定 `scripts/build-desktop.sh build-only`，版本 `0.11.19.59.d9999a3`，Windows/macOS 均构建成功。Windows 构建脚本的真实扩展安装与版本验收通过；按 `Final unpacked EXE:` 核对正式文件存在：`D:/Codex/worktrees/85be/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.59.d9999a3-unpacked/Mihon Desktop.exe`。macOS 隔离部署到 `/Users/altair/Applications/Mihon Sync Source Free.app`，未替换用户默认应用。两个正式产物均通过隔离配置的 Test Mode：HomeScreen → LibraryTab → shutdown，退出码 0。以上启动验证不等同于 macOS 钥匙串可访问性验证。

过程日志：Windows `.gradle-coordinator/sync-source-*.log`；Mac 隔离 worktree `/Users/altair/github/mihon-source-free-20260927/.gradle-coordinator/` 下的 `sync-failure-unix-provider-isolated.log`、`sync-source-desktop-full.log`、`sync-source-macos-build.log`、`sync-source-macos-runtime.log`。仅文档保存必要结论，测试缓存与运行配置不入库。

## 同样本性能对照

本机以本地 MockWebServer、文件数据库与真实 Git/HTTP/面板链路，使用 1,600 条书架事件加 8,700 条阅读事件，共 10,300 条、41 批、每次 794 个 HTTP 请求。关闭/开启显示遥测各运行三次，交替顺序为 OFF/ON、ON/OFF、OFF/ON；HTTP 请求及响应正文总字节在每对中相同。服务端条件为响应头约 50 ms、12,500 字节响应正文约 10 ms、上传按每 1,250 字节约 1 ms 模拟。OFF 只关闭内存时间线和展示观测，安全回执、投影检查及运行结果照常执行。

| 指标 | OFF 三次，毫秒 | ON 三次，毫秒 | 配对增幅中位数 |
| --- | --- | --- | ---: |
| 冻结本轮 | 719、577、584 | 571、595、586 | +0.34% |
| 累计至准备完成 | 34,415、32,856、34,197 | 33,595、34,638、35,651 | +4.25% |
| 累计至首个确认 | 52,483、50,661、51,787 | 51,682、52,484、54,268 | +3.60% |
| 累计至全部确认 | 152,233、149,941、151,188 | 151,222、151,018、153,107 | +0.72% |
| 准备完成至首个确认 | 18,068、17,805、17,590 | 18,087、17,846、18,617 | +0.23% |
| 准备完成至全部确认 | 117,818、117,085、116,991 | 117,627、116,380、117,456 | −0.16% |

按事先约定的配对中位数口径，累计准备和传输主路径低于 5% 增幅。单次配对存在准备 +5.42%、准备完成至首确认 +5.84% 的波动，因此结果仅证明本地受控样本下的门槛，不代表所有设备和网络。原始摘要位于忽略目录 `.gradle-coordinator/sync-eta-perf-verified/`。性能对照没有放宽安全确认，也没有写入真实远端仓库。
