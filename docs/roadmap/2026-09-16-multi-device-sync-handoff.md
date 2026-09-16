# 多设备同步收紧交接

更新时间：2026-09-16。用户明确要求停止本代理实施，将后续工作交给其他 agents。本文是当前接续入口；roadmap 中较早的“正在运行”“尚未授权”等文字是历史记录，不覆盖本文的最新状态。

## 接续完成回执（2026-09-16）

**S5b已按本交接范围完成，随本批产品提交；以下1–7节保留原交接时点，不再是待办清单。** 最终状态、产物路径和C10/C11/C17/C18证据见[实施roadmap最终收口](2026-09-13-multi-device-sync-implementation.md)。接手后只修instrumentation结束前偏好写盘等待，API26/36两阶段共12项通过并经限定独立审查；正常app.mihon release重建及包内许可证核对通过，APK未签名。正常构建切换遥测后曾复用旧许可证，已用现有生成任务强制刷新并重新打包，维护限制已记录。

Windows v36/Mac v37、真实GitHub、完整回归和规模证据保持冻结，没有重新开发同步、重新授权或追赶main。正常APK只交付`artifacts-normal-release-final/`中的候选；旧`artifacts-normal-release/`不交付。保留用户原profile、恢复文件和专用私库；额外合成探针按原交接保留。

## 1. 接手目标与硬边界

**只完成剩余 S5b 发布门槛并提交已有候选，不重新开发同步，不整治整个测试基础设施。**

- 已完成 S1–S5a；S5b 尚未完成、尚未提交产品代码，必须保持未勾选。
- 产品范围仍为已批准的 GitHub 操作日志同步、收藏/作者关注/阅读、接收端取消确认、三种触发及最终 DEMO 交互。阅读模式保持本机独立。
- Android 许可证构建修复已经用户明确追加授权。不要将这次授权解释为任意依赖升级或构建系统改造许可。
- Windows 与 macOS 已通过的范围冻结。只有后续修改确实影响它们的 production 行为，才追加相应验证。
- 不追赶扩展任务最新 main，不再合并其他提交；整合边界已经固定为 `dbf3f050a1`。
- 不再使用 Luna 技能。不为占满并发启动代理；当前剩余 Android 步骤有前后依赖，优先由一位接手者连续执行。

**禁止顺手做：** 新服务商、新同步字段、新 UI、一般性退出/进程治理、整包 R8 keep、关闭 minify/shrink、许可证生成绕过、全面依赖升级、通用测试框架重构、重新运行已通过的规模试验。

发现失败时先分类：①直接阻断当前验收项的产品缺陷，按 TDD 定向修复；②测试或环境准备错误，修正最小边界；③非阻断的旧问题，记录后移交。不得只因“测试红了”就无限扩展 S5b。若最小修复仍要求新增能力或全面重构，向用户说明证据、成本和替代方案，再决定是否增项。

## 2. 工作区与提交状态

| 项目 | 值 |
| --- | --- |
| 唯一实施工作区 | `D:/Shell/Github/mihon-sync` |
| 分支 | `codex/multi-device-sync` |
| S5a / 当前产品基线提交 | `7338d529ab9ba89af817b16d1a88355ca9c57f49` |
| 另一任务工作区 | `D:/Shell/Github/mihon`，不要修改、清理或覆盖 |
| 当前候选 | 基线加现有 tracked modifications 和 untracked 文件，均保留在原工作树 |
| 本次交接提交 | 仅本文和 roadmap；不代表 S5b 产品通过或已提交 |

**必须接手原工作树。仅 checkout 基线或交接提交会漏掉尚未提交的实现、测试和许可证资料。** 开始先看 `git status --short`，不要 reset/checkout/clean，不要把 untracked 文件当垃圾。交接文档提交后的 HEAD 与上表产品基线不同是正常现象。

权威入口：

- [实施 roadmap](2026-09-13-multi-device-sync-implementation.md)：S5b 与 C1–C18，历史过程保留。
- [技术方案](../2026-09-13-multi-device-sync-technical-proposal.md)：尤其 13.1 的发布运行隔离边界。
- [执行经验与成本](2026-09-15-multi-device-sync-execution-costs.md)：各批成本、失败和复用结论。
- [最终 DEMO 入口](../prototypes/multi-device-sync/README.md)：只作交互权威，不再修改 DEMO。

## 3. 已通过证据：直接复用

所有本机协调器证据位于工作树 `.gradle-coordinator/`，是被忽略的本地文件，不随 Git 传递。按具体 key 读取 `.json` 的终态及 `.log`，不要只看进程是否存在。测试 XML 会被后续 focused 运行覆盖，不能把当前 XML 目录自动当作历次全量快照。

| 范围 | 结果与入口 |
| --- | --- |
| 共享完整回归 | `sync-s5b-shared-android-full`：domain JVM 471 / Android 406、data JVM 357 / Android 225，合计 1459，零失败；已含 1万/10万规模 |
| Android app 完整 JVM | `sync-s5b-android-app-final`：409，零失败/错误/跳过 |
| Desktop 测试客户端 | 同轮 `test-desktop` 52 项通过 |
| 同步面板计数修复 | `sync-s5b-published-count-red-proxy` 正确 RED：expected 0 / actual 1；`sync-s5b-count-green-license-app` 两目标各 15 项通过 |
| Windows 完整候选 | `sync-s5b-windows-count-final`：3067 项，仅 manifest 行号引用失败、3 项既有条件跳过；其余结果有效 |
| 上述唯一失败补验 | `sync-s5b-manifest-anchor-final`：34 项零失败/错误/跳过，28 秒；仅 ID95 的 `app/build.gradle.kts` 证据 224→230，无业务变更 |
| Windows 正式产物 | `sync-s5b-windows-delivery`：规定脚本 `build-only` 成功，正式 EXE 版本与 production 扩展安装运行验收通过 |
| Mac 最终完整候选 | `sync-s5b-macos-gui-count-final`：3067 项零失败、8 项既有条件跳过，正式 v37 App 构建成功 |
| Mac 实际系统后端 | 最终 App 两个 GUI 会话进程完成合成 Keychain 写 201→重启读 200/重复 409、面板打开与正常退出 |
| 许可证功能测试 | `sync-s5b-license-fixture-final`：Android 2 + Desktop 2，全部通过 |
| 实际许可证完整性 | `sync-s5b-license-coverage-official`：226 个真实 artifact modules、255 个导出 libraries、90 个官方合并别名，缺失 0、版本不符 0；Tink/FlexibleAdapter 与许可证正文已核对 |
| 最新 Android 隔离 R8 构建 | `sync-s5b-android-art-entry-final`：PASSED / exit 0，2026-09-16 04:25:05 UTC 结束，用时 2分22秒；运行验收仍未做 |

Android JVM 不是 ART，隔离验收 APK 不是正常发布身份；Mac 条件跳过不计通过。真实 GitHub 并发成功也不等于现场强制制造过同 HEAD 碰撞；后者有既有 HTTP 故障契约，不再另造现场竞争。

### 真实 GitHub 与用户决定已经完成

用户已完成 GitHub App 登录、专用私库安装授权、两个隔离 Windows 正式 EXE 的创建/加入空间与恢复文件操作。不要再次索要授权、恢复密钥或创建仓库。私库为 `AltairCardinal/mihon-sync`，同步使用 `mihon-sync-v1` 分支，原 main README 保留。

两个隔离测试库只预置合成漫画业务元数据，之后全部通过 production 收藏入口产生操作。实际验证：

- 两端并发交换后均保留双方发布集合。
- 接收端有 1 项待确认时，本机 2 条独立操作仍上传成功。
- 用户已对《Sync acceptance A》选择“保留在此设备”。之后双向同步，发起端仍未收藏，接收端仍收藏，事件数不增加，没有反向 ADD。
- 最终两库各 7 个事件、2 个 actor、待确认 0；最终 v36 EXE 两端待上传均为 0，授权/本机决定跨启动保留，再次真实同步 SUCCESS。

安全证据：`.gradle-coordinator/s5b-windows-runtime/github-exchange-evidence.json` 是旧 v34（包含计数缺陷）；`delivery36-evidence.json` 是已修复最终 v36，必须区分。没有保存真实 token 或恢复资料到报告。

## 4. 最短剩余路径

### A. 保存已有 Android 产物，再执行既定 ART 验收

**不需要再次构建当前隔离候选。** 最新构建已成功，未安装或运行新候选。当前文件：

- `app/build/outputs/apk/release/app-universal-release.apk`，2026-09-16 12:24:56 本地时间，67,674,198 字节。
- `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`，856,655 字节；较早时间戳是 Gradle UP-TO-DATE，不能据此判定构建失败。
- `app/build/outputs/mapping/release/` 为同轮 mapping；构建 normal release 前先把本轮 APK、metadata、mapping、SHA 保存在既有 ignored 验收目录下新的 `artifacts-entry-final/`，不要覆盖首轮失败证据 `artifacts/`。

隔离身份固定为 `app.mihon.syncacceptance` / `app.mihon.syncacceptance.test`，minify/shrink 开启、non-debuggable，用本机 debug key 只签此验收身份。不能当正式发布包交付。

设备是另一任务既有模拟器：`emulator-5580`（`mihon-aex-api36`）、`emulator-5582`（`mihon-aex-api26`）。执行前只读核对 serial、AVD/API、无其他活跃 instrumentation。仅安装/停止自己的两个包；不能重启模拟器、改系统设置、清其他应用或另开高内存模拟器。已创建但未启动的 `mihon-sync-api26` AVD 不需要启动。

每个 API 各执行 3 项 prepare 与 3 项 verify，中间只 force-stop 自有 app；两阶段之间禁止清数据：

```powershell
& 'D:/Android/Sdk/platform-tools/adb.exe' -s <serial> shell am instrument -w -e class eu.kanade.tachiyomi.sync.SyncReleaseAcceptanceInstrumentationTest -e syncAcceptancePhase prepare app.mihon.syncacceptance.test/androidx.test.runner.AndroidJUnitRunner
& 'D:/Android/Sdk/platform-tools/adb.exe' -s <serial> shell am force-stop app.mihon.syncacceptance
& 'D:/Android/Sdk/platform-tools/adb.exe' -s <serial> shell am instrument -w -e class eu.kanade.tachiyomi.sync.SyncReleaseAcceptanceInstrumentationTest -e syncAcceptancePhase verify app.mihon.syncacceptance.test/androidx.test.runner.AndroidJUnitRunner
```

覆盖固定 AEAD 向量/错误 key/AAD/篡改、真实 App DI/业务仓库/日志、Keystore 跨进程恢复及包装 key 丢失 fail-closed。它不包含 Android 本机的真实 GitHub 登录，不要伪称包含。

首轮旧 APK 在 runner 启动时因 `androidx.tracing.Trace` 缺失崩溃，尚未执行业务断言。已冻结 `scripts/sync-android-acceptance.pro`：Trace 边界加精确 62 方法/构造器、5 字段，方法体仍允许优化，普通 release 规则未改。`.gradle-coordinator/s5b-android-runtime/acceptance-api-diff.json` 含旧 APK/DEX 与原始 class 的差集审计及 SHA。若新运行仍失败，先识别是跨 APK 测试入口还是产品路径，不要无限增加 keep，更不允许整包保留掩盖问题。

### B. 生成正常 Android 发布 APK

A 的候选证据保存后，按项目既定 release 参数构建，**不带 acceptance init，也不带单测许可证绕过脚本**：

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:ANDROID_HOME = 'D:/Android/Sdk'
$env:ANDROID_SDK_ROOT = 'D:/Android/Sdk'
$env:HTTP_PROXY = 'http://127.0.0.1:10808'
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
python scripts/gradle-coordinator.py run --key sync-s5b-android-normal-release -- gradlew.bat '-Dhttp.proxyHost=127.0.0.1' '-Dhttp.proxyPort=10808' '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=10808' :app:assembleRelease -Pinclude-telemetry -Penable-updater --max-workers=2 --console=plain
```

先核对协调器没有重型任务运行。正式包的实际 appId、签名状态、版本和输出路径需要如实读取记录，不承诺已有正式签名密钥；不得安装覆盖另一任务/用户原 app。网络遵循 AGENTS 代理与有界重试要求。

### C. 最终收口

- 仅针对 A/B 期间实际修改执行必要 focused 测试、对应集成/格式验证。不得无差别重跑 Windows/Mac 全量、共享规模或已通过的账号流程。
- 当前候选原有交叉审查/限定复核已完成；新增实质修复只做该边界的独立审查，不重开整套架构审查。
- 核对 `git status`、diff、untracked 内容和产物身份。S5b 涉及多文件是平台验收/必要 fixture 的同一收口批次；不要为了行数拆成不能独立使用的机械任务。
- 更新 roadmap 最新表格和 C10/C11/C17/C18 状态、成本经验，完成提交才勾选 S5b。不要把本次文档提交当作产品完成。
- 产品源码/测试/必要文档提交一个内聚批次；最终报告提交 hash、真实 APK/EXE/App、实际验证与限制。无需再为已提交后的 hash 字样重建所有平台，说明产物来自基线加候选即可。

## 5. Windows / Mac 交付与现场

Windows 最终产物（已核对存在，来自日志 `Final unpacked EXE:`）：

- `D:/Shell/Github/mihon-sync/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.36.7338d52-unpacked/Mihon Desktop.exe`
- 同目录 `Mihon-Desktop-0.11.19.36.7338d52-windows.zip`；日志 SHA-256 为 `8e6a1e478bda73e6b0a5da8c2620a4c4efd40d6486a3cd76a19a446d1a1c7fbd`。

Mac 最终产物在远端，不是 Windows 本地路径：

- 主机 `mbp-lan`，工作树 `/Users/altair/github/mihon-sync-acceptance`。
- `/Users/altair/github/mihon-sync-acceptance/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.37.7338d52-unpacked/Mihon Desktop.app`。
- 完整构建日志、`runtime-final-result.json`、两次 runtime 日志及关键 XML 已取回 `C:/Users/feeli/AppData/Local/Temp/mihon-sync-macos-acceptance/`，root 已读取核验。
- Mac GUI 与 SSH 的 Keychain 安全会话不同已证实；GUI 后端已通过。不要再次要求用户解锁，不修改 HOME/CFFIXED_USER_HOME、默认钥匙串或安全策略。本轮临时 LaunchAgent 已移除。

版本名中的 `7338d52` 是产品基线，不代表 S5b 未提交 diff 已在该提交中。Mac/Windows BUILD 不同，属于各自脚本分配。最终文档不要把它们写成同一个源码提交的干净构建。

### 交接时遗留进程与合成记录

- 只读核对所有 `sync-s5b-*.json`，没有 STARTING/RUNNING 的协调器任务。Android最后一次构建已成功退出。
- Windows 两个最终验收 PID `29728`、`53800` 曾在 shutdown 202 后短暂仍存在，本次交接核对均已消失；未采集退出码/栈，不能称为已证实退出缺陷，不要据此开一般性退出修复。
- `.gradle-coordinator/s5b-windows-runtime/delivery36-probe.json` 留有本代理额外创建的随机合成探针 ID，尚未做再次读取删除。它不是用户密钥。需要清理时仅在相同隔离 profile 中使用已有 `/test/sync/probe/verify/<id>` 一次，返回 200 即删除；不必为它开启新一轮安全后端验收，不能删除真实记录。
- 两个 Windows profile、恢复文件与授权仍由用户控制，均保留。不要复制其凭据到报告或仓库，不清空专用 GitHub 仓库。
- Android代理已收到停止后续操作要求，工具显示 interrupted；Mac代理已 completed。接手前不要依赖旧代理仍在工作，先核实现场。不存在必须重启的本任务服务。

## 6. 未提交候选的代码入口

| 上下文簇 | 主要路径与目的 |
| --- | --- |
| 已上传计数修复 | `data/src/commonMain/sqldelight/tachiyomi/data/sync_journal.sq` 的 `getPendingCategoryCounts` 排除 PUBLISHED；`SyncPanelStorageContract.kt` 新增真实发布后的归零断言，无 migration |
| 正式 Desktop 验收隔离 | `DesktopTestProfile.kt`、`Main.kt`、`TestMode.kt`、`TestHttpServer.kt`、`SyncTestRoutes.kt` 及对应测试；安全状态白名单、合成探针、显式 DI/profile，防止访问已关闭全局 graph |
| Desktop 既有 fixture | DI/许可证/reader/Compose/Library 的最小生命周期和当前接口适配；`parity-manifest.json` 只维护当前 evidence，不改变 capability 状态和固定原版证据 |
| Android 既有 fixture | 9 个 app 测试类与 `app/src/test/java/eu/kanade/tachiyomi/test/` 的真实 Voyager/JDBC 宿主修正 |
| Android ART | `app/src/androidTest/java/eu/kanade/tachiyomi/sync/`、`scripts/sync-android-acceptance.init.gradle`、`.pro` |
| Android 许可证 | `gradle/libs.versions.toml` 仅插件拆为 14.0.0，runtime 仍 13.2.1；`app/build.gradle.kts`、`app/license-metadata/`、`AndroidLicensesPluginFunctionalTest.kt` |
| Mac 构建隔离 | `scripts/build-desktop.sh` 与 `scripts/tests/build-desktop-macos-test.py`，默认路径行为保留；Mac真实 3测试/12场景通过，Windows skip 不算通过 |
| 版本/维护说明 | `AppVersion.kt` 当前 Windows BUILD36，技术方案、roadmap、执行成本文档 |

许可证 metadata 是有来源的单库补充，不是假 POM；旧生成清单的整份复用被明确拒绝。实际依赖核对遵循插件官方 MERGE/EXACT 语义，不能把所有别名当遗漏，也不能跳过所有别名。相关准备失败和修正已在成本文档记录。

## 7. 成本与范围反思交接

本代理已向用户承认：产品需求基本守界，但 S5b 的验收与配套工程投入扩大；测试夹具准备不足、机械 evidence 漂移、R8 跨 APK 测试耦合造成重试和重复构建。不能以“都与验收有关”为无限加码理由。

收紧方法：已有证据冻结；一位执行者完成 Android 连续上下文；只修阻断；过程仅维护本文/原 roadmap/原成本文档，不增加报告体系、快照包或通用工具。A/B 成功后按 C 立即提交，不再添加“最后再测一次”的新场景。

历史 goal 原始累计计数曾超过一千万，包含上下文/工具/代理等累计窗口，不能当作净生成 token 或账单。最近审计调用返回 goal=null，不能伪造延续计数、创建重复目标或宣称原 goal 已完成。新接手者若被明确要求创建新 goal，应只覆盖本文剩余边界。
