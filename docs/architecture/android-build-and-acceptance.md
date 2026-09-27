# Android 构建、签名、交付与验收规范设计

日期：2026-09-28。状态：**设计已形成，目标流程尚未实现**。

本文定义整个下游项目的 Android 构建目标、责任边界、失败处理和迁移验收。标为“现状”的内容已核对仓库；其余为目标规范。完成文末迁移前，继续使用现有 fork init 脚本和外部签名脚本，不把本文提出的新入口当作可运行命令。本次设计不授权自动发布、真机安装或设备控制。

## 1. 目的与决策

项目是独立维护的 Mihon fork，日常 Release 应直接构建本项目的应用身份。面向开发者保留 Debug，面向用户交付和正式验收保留 Release；两者使用同一产品源码，通过编译配置、签名与应用数据隔离满足不同用途。

1. 保留 `release` build type，将默认 `applicationId` 改为 `app.mihon.desktop.fork`。不再让遗漏 init 参数生成上游身份 APK。
2. Debug 身份为 `app.mihon.desktop.fork.dev`；保持 `namespace = eu.kanade.tachiyomi` 和现有源码包名，不做无关重命名。
3. 正式验收和交付使用同一份签名 APK，以文件 SHA-256 绑定运行证据。默认 Release 的未签名输出仍是合法构建中间产物。
4. 包名、版本、预期证书和正式配置有唯一来源；Gradle、签名、校验、CI 共用，不在多个脚本手工维护版本副本。
5. 复用 Gradle wrapper、协调器、既有签名脚本、测试和产物目录。只增加薄的 Android 操作入口，不另建任务调度器、测试框架或发布服务。
6. 构建、安装、设备操作、发布是独立动作。生成 APK 不隐式安装；安装不隐式操作用户阅读器；已有明确授权可持续使用，用户改为自行验收后停止代操作。

## 2. 已核实的现状与来源

| 内容 | 当前事实 | 来源 |
| --- | --- | --- |
| 默认身份 | `app.mihon`，code 18，name `0.19.4`；Debug 追加 `.dev` | [app/build.gradle.kts](../../app/build.gradle.kts) |
| fork 身份 | init 脚本改成 `app.mihon.desktop.fork`；当前 code 32，name `0.19.4-aex.14` | [fork init](../../scripts/android-fork-release.init.gradle) |
| Release 限制 | fork init 强制 R8、资源压缩、非调试、遥测/更新器关闭、Gradle 外签名 | [fork init](../../scripts/android-fork-release.init.gradle) |
| 签名 | 现有 PowerShell 脚本检查版本、身份、zipalign 与证书；密钥在仓库外，密码通过本机 DPAPI 解密后临时传入环境变量 | [签名脚本](../../scripts/sign-android-fork-release.ps1)、[一次性建钥脚本](../../scripts/create-android-fork-release-key.ps1) |
| 测试变体 | 默认 `mihon.testBuildType=debug`；显式 `release` 才加入 release 专用测试源集 | [app/build.gradle.kts](../../app/build.gradle.kts) |
| 其他变体 | 还存在 foss、preview、benchmark；preview/benchmark 使用调试签名 | [app/build.gradle.kts](../../app/build.gradle.kts) |
| 工具链 | 仓库 CI JDK 21；minSdk 26、compile/targetSdk 36；本机 SDK `D:/Android/Sdk`、Build Tools 36.0.0 | [JDK](../../.github/.java-version)、[AndroidConfig](../../buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt)、[AGENTS](../../AGENTS.md) |
| 日常 CI | Build & Test 仍带遥测/更新器参数构建默认 Release，上传未签名 arm64 APK | [build.yml](../../.github/workflows/build.yml) |
| 发布 CI | Release 限定 `mihonapp/mihon`，不能作为本 fork 发布机制；其中签名工具版本仍写 35.0.1 | [release.yml](../../.github/workflows/release.yml)、[仓库治理](repository-governance.md) |
| 隔离验收 | AEX-05、EIS、Sync 脚本均有默认 `app.mihon` 的前置断言；有各自隔离身份和限制 | [AEX-05](../../scripts/aex05-upgrade-identity.init.gradle)、[EIS](../../scripts/eis-android-acceptance.init.gradle)、[Sync](../../scripts/sync-android-acceptance.init.gradle) |
| 最近事件 | 先生成默认身份测试签名包，后补 fork aex.14；空闲 daemon 持有旧 dex 导致构建失败；最终同证书升级成功，用户自行验收阅读器 | [CP-03 记录](../roadmap/2026-09-27-chapter-pairing-persistence-evidence.md) |

现有跨平台统一产品语义版本的长期约定继续保留，见[版本策略与发布矩阵](versioning-and-release-matrix.md)。本文只集中 Android 构建来源和升级序列；过渡期沿用 aex 显示版本，不宣称已经实现三端统一版本，也不顺带修改 Desktop 版本生成器。正式跨平台产品发布仍须满足该矩阵的约束。

## 3. 构建身份与用途

| 配置 | 目标 applicationId | 编译/签名 | 允许用途 |
| --- | --- | --- | --- |
| Debug | `app.mihon.desktop.fork.dev` | 可调试，关闭 Release 的 R8/资源压缩；调试证书 | TDD、内部状态排查、平台接线测试 |
| Release | `app.mihon.desktop.fork` | 不可调试；R8/资源压缩开启；正式证书 | 用户安装、正式业务、升级和真实 ART 验收 |
| 专项隔离验收 | 既有明确隔离 ID | 按测试契约配置；允许调试签名；记录额外 keep/测试接线 | 只证明该配置下的测试项，不作为用户升级产物 |
| preview / benchmark / foss | fork 基础 ID 加现有对应后缀 | 保留各自变体语义 | 兼容既有开发/性能用途；不新增正式发布渠道 |

Release 基线保持遥测与自动更新器关闭。默认 Gradle 的 Release/FOSS 等正式配置必须对违背基线的开关失败退出，不能只在外部 wrapper 检查。未来启用须有对应服务、配置与授权的独立设计，不能机械采用上游 CI 参数。

Debug 迁移到新 ID 后，既有 `app.mihon.dev` 数据不会自动进入新应用。保留旧安装，可按用户意图使用生产备份/恢复；不卸载、不复制私有数据库冒充自动迁移。原正式 `app.mihon.desktop.fork` 的 ID 和证书保持连续，官方 `app.mihon` 安装不受本项目升级操作影响。

专项隔离脚本必须更新“宿主默认 ID”的断言，同时保留其明确的隔离目标 ID 和旧宿主升级夹具。禁止同时加载多个身份改写脚本。涉及 `${applicationId}` 的 provider、Shizuku authority、测试包 targetPackage、深链和快捷方式必须检查实际 merged manifest 与运行接线；不能靠全局字符串替换完成迁移。

## 4. 版本与签名规范

### 4.1 唯一版本来源

目标新增 `gradle/android-release.properties`，保存公开构建元数据：`applicationId`、`versionCode`、`versionName`、`releaseCertificateSha256`。这些字段由 Gradle 正常配置和签名/校验入口共同读取。默认 fork ID 和正式证书不可通过普通命令行参数改成任意值；改变它们属于独立身份迁移。

- `versionCode` 是整数升级序列，与显示版本、提交计数、Desktop BUILD 均独立。现有已安装基线为 32；下一份不同的正式候选至少使用 33，并核对目标设备及已交付候选的最大值。
- 过渡期 `versionName` 沿用 `0.19.4-aex.N`。不从 N 推导 code，不用 Git 提交数自动分配正式版本。
- 每份将安装或交付的不同正式候选占用新 code；同一 SHA-256 的 APK 在多设备复用不增加版本。失败候选允许留下号码空档。
- 查询、单元测试、格式检查、普通 Debug 构建不自动修改版本。版本在候选开始前分配，进入同一功能批次的提交；多个工作树须先串行确认分配，发现分支版本冲突在构建前解决。
- 版本字段参与构建输入。已交付 APK 不覆盖；重新编译或重新签名后先重新计算哈希，不能假设还是同一份产物。

### 4.2 密钥与签名

现有 fork 公共证书 SHA-256 为 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`。继续复用既有仓库外密钥和签名脚本；证书指纹可以入库，私钥、密码、解密内容不进入 Git、日志、Gradle 属性或命令行明文。

正式构建前检查密钥文件可访问、凭据可在当前身份解密，并只输出公有证书检查结果。失败则把签名明确标为阻塞；可以继续未签名 CI 检查，不创建替代密钥或用调试证书冒充正式包。原先“文件存在”与“密钥可签名”必须分别验证。

签名前检查实际 APK 的 ID、版本、SDK/ABI、非调试状态与对齐；签名后验证方案、证书和完整 APK 哈希。测试 APK 另以 `-Instrumentation` 路径验证测试包 ID 及宿主 targetPackage。将预期证书作为发布契约，不能靠传入任意 `ExpectedCertificate` 让正式入口放行。

Windows 沿用 DPAPI。无密钥的 CI 只构建未签名产物；如以后启用其他系统的签名，增加凭据 adapter 并复用同一校验逻辑，不另维护版本/包名规则。CI 不直接消费 Windows DPAPI 文件。本设计不迁移密钥或建立新的签名托管。

## 5. 构建入口与职责

目标新增跨平台薄入口 `scripts/build-android.py`，复用 Python 标准库、Gradle wrapper、`gradle-coordinator.py`、已有签名逻辑与 Android SDK 工具。以下是**待实现的接口契约**，不是当前可用命令。

| 子命令 | 行为 | 不隐式执行的后续动作 |
| --- | --- | --- |
| `check` | 核对工作树、工具链、配置、协调器；需要签名时验证签名可用性；传入设备时只读核对安装身份 | 不分配版本、不构建、不安装 |
| `debug` | 构建 Debug，校验调试身份并输出 APK 路径 | 不占正式版本、不安装 |
| `candidate` | preflight → 构建 Release → 签名 → 检查 → 保存候选；`--unsigned` 仅生成明确标注的 CI 产物 | 不隐藏运行全量测试、不安装、不发布 |
| `verify --artifact …` | 重算给定 APK 哈希，读取 manifest/证书，与候选清单比对 | 不重建、不修改 APK |
| `install --artifact … --serial …` | 验证候选与指定设备，已授权范围内执行覆盖安装并核对安装身份 | 不清数据、不默认操作 UI 或 force-stop、不发布 |

每个模式都说明所需前置证据与结果。`candidate` 完成只表示候选已生成，不能将测试缺失或运行未验收变成 PASS。`--unsigned` 的结果不能进入正式安装路径；调试安装只接受明确的 Debug/隔离测试身份。

Gradle 仍支持直接 `:app:assembleRelease`，并强制输出 fork 配置；这是开发/CI 的底层任务，用户交付必须继续走签名与产物验证。没有建立“第二套 Release”。迁移后 fork init 先成为读取同一来源的兼容入口，不再覆盖身份；仓库调用方迁完后删除。底层任务成功不自动生成“验收已通过”的结论。

### 5.1 工具链与环境

JDK 沿用 `.github/.java-version` 的 21，SDK 版本由 `AndroidConfig` 权威定义。本机 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 为 `D:/Android/Sdk`；检查 android-36、Build Tools 36.0.0 和 adb 实际文件。签名及 CI 使用经过核对的同一 Build Tools 基线，不继承旧 workflow 的 35.0.1 硬编码。

Windows 命令前设置 `$ErrorActionPreference = 'Stop'`、`PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`、`PYTHONDONTWRITEBYTECODE=1`。`local.properties`、密钥路径和设备私有信息不提交。依赖完整时可 `--offline`；离线缓存不足应明确报错，按已有网络代理规则恢复依赖，不把网络失败记录为产品测试失败。

## 6. 从开发到交付的流程

1. **确定验收范围**：固定行为、平台、测试设备类型、是否升级、是否由用户自行验收；区分开发包与正式候选。检查现有安装和已交付版本，明确所需发布身份。
2. **实现与验证**：按 TDD 完成定向测试，再完成批次、阶段和最终收口要求。高风险变更按仓库规定独立审查。测试报告应能关联到实际产品/构建输入。
3. **冻结构建输入**：核对 Git 状态，保护其他任务改动，优先使用与测试相同的工作树。记录基础 commit、实际源码差异摘要/哈希和构建配置，分配候选版本。
4. **一次构建、签名、校验**：由协调器串行完成，输出唯一候选 APK 和紧凑产物清单。保存退出状态和必要日志，不以历史 `build/` 文件的存在认定本轮成功。
5. **隔离环境运行**：用该已签名 APK 完成适用的新装、旧版原位升级、受影响业务和冷启动检查。涉及迁移时用真实旧版产生的数据库或已核实的历史库，不把当前库改版本号伪造旧库。
6. **真机交付/验收**：用户已有明确安装授权时使用同一 APK 安装；否则先提供可审阅候选和升级信息，再询问安装。明确由谁操作设备。用户自行验收时提供短操作清单与结果记录位置，不持续控制其 UI。
7. **结果收口**：报告构建、签名、安装、启动、业务分别达到的状态；完整通过后才把相应验收项勾选。提交相关源码/配置/文档；外部上传和发布另按已有授权执行。

### 6.1 源码与产物对应

优先使用可追溯的提交。为兼容仓库“实现、验证、必要 checkoff 同一批次提交”的规则，允许从已冻结的未提交候选构建，但必须记录 `sourceRevision`、`sourceDiffSha256` 和构建实际消费的相关未跟踪输入摘要，不能只用 HEAD 后缀声称干净源码。不要保存巨型 diff 包或复制包含凭据的机器文件。

发布清单同时覆盖 buildSrc、Gradle 配置、依赖锁定/校验文件、资源、init 脚本及 release keep 规则的变化。最终提交若只增加报告，可以关联到构建源快照；如改变会进入 APK 的输入，则原 APK 不是最终代码产物，需要重新构建及受影响验证。不要为让文件名带最终提交号而无意义重建已经验收的相同候选。

## 7. 分层验证与结果复用

| 层级 | 必做项 | 能证明什么 |
| --- | --- | --- |
| 行为红绿 | 真实实现的定向失败→成功→重构测试 | 当前行为和边界；不需要每次打包 |
| 批次验证 | 相关 Android/共享单元、数据库、DI/导航/HTTP/UI 接线与格式检查 | 改动范围内集成正确 |
| 阶段/最终验证 | 按 AGENTS 完整 Android 与受影响共享模块；最终跨端收口按要求完成 Desktop、Test Mode 和双平台发布验收 | 本批最终源码的回归结果 |
| 正式 APK | R8/资源压缩、非调试、签名、API/ABI与真实 ART/业务运行 | 该 APK 在所测环境下的发布行为 |
| 原位升级 | 旧签名安装→同身份更高版本→数据/权限/业务→冷启动 | 既有实例升级和适用迁移 |
| 真机人工 | 使用该 APK，按冻结清单观察画面和操作结果 | 用户反馈明确覆盖的行为 |

常用实际任务仍为 `:app:testReleaseUnitTest`、`:domain:jvmTest`、`:data:jvmTest`、`spotlessCheck`；按影响范围使用。测试必须覆盖生产调用链，不能只扫描字符串或复制实现。

Release instrumentation 通过 `-Pmihon.testBuildType=release` 构建对应宿主和测试 APK，核对实际安装宿主哈希、证书、targetPackage 后运行适用 suite。现有 AEX release ABI suite 是特定能力门槛，不作为整个应用的通用全量验收；内部白盒测试保留在适合其结构的 Debug 配置，不为使 R8 测试通过批量保留内部类。`adb` 退出 0 不等于测试成功，必须检查 runner 终态、执行数量、失败、跳过和崩溃。

正式基线为最低 API 26 与当前 API 36，并覆盖代表性 arm64 设备/模拟器及需要的 x86_64 隔离环境。不是每个定向修复重跑整张矩阵；涉及 SDK、JNI、ABI、R8、安装器或身份迁移必须补验受影响组合。universal 为默认验收交付 APK；如另交付 ABI 专用 APK，每份独立记录哈希并验证其适用 ABI，不能用 universal 哈希替代。

同一输入下有效结果可复用。纯文档、显示版本等机械修改不自动触发产品逻辑全量重跑，但更改构建身份、签名接线、R8、依赖或打包输入必须验证对应真实产物。出现产品缺陷时补红测、修复、重验受影响范围；遵守已声明的全量次数和审查预算。旧候选运行证据在二进制变化后不能自动标成新候选通过。

## 8. 产物与证据

目标交付目录沿用被 Git 忽略的 `app/artifacts/android/`，新候选使用不可覆盖的子目录：

```text
app/artifacts/android/<versionName>-vc<versionCode>-<sourceShortSha>/
  Mihon-Fork-<versionName>-vc<versionCode>-universal.apk
  artifact.json
  mapping/                 # 对应该次 R8 的映射，存在时保留
```

同一目录已存在时校验并复用完全相同的产物，或拒绝覆盖；不得替换其中 APK 后沿用原验收结论。Debug/CI 未签名产物使用带 `debug`/`unsigned` 的独立名称，不进入“正式签名包”交付链接。`app/build/outputs/` 只作过程输入。

`artifact.json` 只保存必要字段：源码 revision 与差异指纹、版本/ID、variant、构建时间、JDK/Gradle/SDK/Build Tools、R8/遥测/更新器配置、min/target SDK、实际 ABI、APK 相对路径和哈希、证书指纹/签名验证结果、mapping 引用。失败时不输出看似通过的清单；无签名 CI 清单明确为 unsigned。元数据是构建证据，不是功能 capability 的新权威；继续遵守既有 parity manifest 规则。

每批沿用一份验收报告，引用测试日志和产物哈希，记录设备别名/API/ABI及场景结果，不在长期文档存用户设备序列号、私人内容或凭据。可将报告中的状态写为“候选已构建”“签名已验证”“已安装”“启动通过”“业务通过”“待用户验收”“失败/阻塞”；这些是报告字段，不再增加独立状态服务。

最终报告提供实际存在的 APK 绝对路径链接、版本、可升级来源、源码依据和未验证边界。进程存活、UID/目录/首次安装时间连续只证明安装及启动观察；库内容、配对、进度或下载保留需要业务层证据。

## 9. 安装、升级与设备责任

- 每条写入设备的命令明确 `adb -s <serial>`；serial 从本次连接确认，不从历史日志盲用。核对包名、版本、ABI和证书；不能只匹配桌面名称或用户口述版本。
- 已安装 APK 与可信历史文件哈希完全一致时可复用该文件的证书证据，否则读取实际 APK 验签；`dumpsys` 的短签名标识不作为 SHA-256 指纹。
- 对正式候选执行 `adb install -r`。新装与升级分别记录；不使用卸载、清数据、降级或换包名绕过不兼容。安装后核对实际 APK、版本和签名，不能只相信 `Success`。
- 迁移风险先在隔离环境验证并明确恢复办法；用户同意时使用应用已有备份。不能承诺备份包含所有本地状态：例如当前章节配对不进入同步/备份，见 CP-03 边界。
- 失败时保留现场与原数据。回滚优先修复后发布更高 code；降级 APK 不等于数据库可回滚。需要卸载或恢复旧库时另行说明数据影响，不作为普通重试步骤。
- 安装授权不自动涵盖清库、账号操作或测试夹具注入。用户已授权的具体操作无需反复确认。用户自行验收时，交付后等待其结果，不主动点击、重启或反复追问。
- 冷启动应在保存已完成后执行，并记录 force-stop 与重新启动；若验收的是写入中断，则单列对应失败/恢复预期，不与正常恢复混为一项。

## 10. Gradle 与文件占用处理

同一工作树的重型 Gradle 任务只由一个协调者串行运行；候选入口默认有界 worker 数及 `--no-parallel`，由协调器持有互斥。优先复用相同工作树已完成的编译，避免无意义 `clean`。不同工作树仍可能共享 Gradle daemon、缓存和机器内存，不能把目录隔离视为全机资源隔离。

发生等待超时先读取协调器状态、进程身份和日志；RUNNING 不启动第二个构建。配置失败、代码失败、测试失败、签名失败与文件锁分别记录，不用重复整套测试诊断打包错误。

文件锁按以下顺序处理：

1. 确认失败路径与工作树，定位占用 PID、启动时间和实际句柄；检查协调器与 daemon 日志是否仍有构建。
2. CPU 暂时为 0 不能独立证明空闲。确认进程归属和构建终态；`--no-daemon` 也不能保证其他旧 daemon 不占用文件。
3. 对协调器管理的本任务进程按既有 stop 入口终止。长期 daemon 不在可确认的任务树时，记录证据；只有具有明确针对该进程的授权才结束它，核对启动时间防止 PID 复用。不全局结束 Java/Gradle，不关闭其他业务。
4. 再次检查文件可独占打开和协调器终态后重试。持续占用时停止盲目追加构建；确需换工作树时核对源码输入和既有负载，不为避锁混入其他任务源码。

候选入口和协调器沿用现有日志，补足进程/工作树关联信息即可；不另建常驻守护程序。工具不可用或签名设备阻塞时交付真实状态，不将完成进程命令视为通过构建。

## 11. CI 与发布边界

| 场景 | 目标任务 | 签名/发布边界 |
| --- | --- | --- |
| PR / main 检查 | 正常 fork 配置、格式、适用 Android/共享测试、R8 未签名构建；保存报告和 mapping | 不访问生产密钥，明确 unsigned；不得给外部 PR 密钥 |
| 正式候选 | 对已验证源码生成候选，授权签名环境签名、验签，再在指定环境验收 | 无凭据时保持 unsigned/阻塞，不用新证书替代 |
| 对外发布 | 提升已验收的 APK，校验其哈希与版本，不重新编译替换 | 独立发布授权；上传到本 fork 的明确目标 |

必须迁移 `.github/workflows/build.yml` 的上游参数与产物命名。现有 `release.yml` 保持不可作为本 fork 自动发布入口；不能只删除 `github.repository == 'mihonapp/mihon'` 就启用它。迁移时先明确本项目发布目标、凭据、tag/版本一致性与审批，再替换上游 release/FOSS 发布动作及文案。设计实施与外部发布授权分开，CI PR job 不获得写权限。

跨平台共享改动继续执行仓库规定的 Desktop 验证。Android 构建入口不重复调度 Desktop，也不以 Android 单独通过宣布整个三端迭代完成。

## 12. 迁移批次与验收出口

以下为后续实施拆分；本设计提交不执行这些任务。每批是一项可独立审查的能力，不按文件数量切分。

| 批次 | 前置与范围 | 必须验证的出口 |
| --- | --- | --- |
| AB-01 默认 fork 身份与版本权威 | 先实现公开版本配置与正常 Gradle 接线；迁移 Debug/辅助变体、init 兼容入口、签名脚本版本读取及专项验收脚本前置断言；同步修正日常 CI 的不兼容上游构建参数，保持旧正式身份与密钥 | 红绿验证默认 Release/Debug 的实际 APK manifest、authority 与 test target；禁止参数失败；旧正式包→新候选的同证书升级；隔离脚本仍命中各自测试 ID。版本不漂移，测试不触发自增；本批合入不留下已知必失败的 CI 配置 |
| AB-02 统一候选与安装入口 | 依赖 AB-01 身份契约通过独立检查；薄入口复用协调器和签名脚本，增加实际 APK 验证、紧凑清单及明确安装模式 | 未签名/错误包/错误版本/错误证书/篡改 APK 拒绝；缺凭据不轮换密钥；占用任务不并发；不覆盖历史候选；无安装参数不调用 adb 写接口。使用隔离测试密钥/夹具测试失败路径，正式密钥只用于候选签名 |
| AB-03 CI 与文档切换 | 依赖前两批；build workflow 接入统一候选入口及证据上传；更新 AGENTS 常用命令、迁完旧 init 调用方后删除兼容入口；发布 workflow 保持明确阻塞直到发布环境就绪 | CI 无密钥可构建/测试并输出 unsigned；不得启用上游 telemetry/updater；不误用官方发布目标；脚本/文档/实际命令一致。静态校验只称静态通过，实际 CI 未运行时明确标注 |
| AB-04 最终正式产物与升级验收 | 最终产品/构建差异稳定后集中收口；不扩张到无关功能修复 | 一次当前代码全量及格式验证；正式 R8 签名候选，API/ABI 适用矩阵、隔离升级和真实业务；真机依授权或用户自行验收。产物哈希、源码、测试和报告一致 |

实施时按 AGENTS 将首个实质实施簇委派给实施子代理，后续相近工作复用；主代理负责接口、独立审查和集成。可用默认预算为 1 个实施子代理、1 轮独立审查分稳定里程碑完成、最多 1 轮修复复审、最终全量 1 次；具体任务启动时重新声明命令、平台与耗时，不在此预授权超额测试。相关配置行为走红绿，纯文档/机械版本调整不虚构红测。

AB-01 的身份迁移、AB-02 的签名/安装边界属于高风险，必须在被下游依赖前通过独立检查。受影响的行为测试优先真实 Gradle task、实际 manifest、签名 APK 和隔离设备；不能只断言脚本文本存在。需追加评审或全量时说明具体未解决项与新增成本，不以单纯预算耗尽为理由。

目标流程整体完成须同时满足：默认 Release 已是 fork、原正式安装可升级、Debug 隔离明确、单一版本来源、签名不可被调试路径替代、候选可追溯、CI 路径一致、故障有界处理、必需验收有结果。用户选择自行验收且未回复的场景保持待用户验收，不自动当作通过。

## 13. 设计交付与当前边界

本轮只新增本设计及文档入口，未修改 applicationId、版本分配、签名行为、CI 或 Gradle 调度，未创建目标 wrapper，未运行测试/构建或操作设备。当前可用正式构建仍是 `-I scripts/android-fork-release.init.gradle :app:assembleRelease` 后调用 `scripts/sign-android-fork-release.ps1`；当前用户设备上的 aex.14 及其手工验收责任保持 CP-03 记录中的状态。
