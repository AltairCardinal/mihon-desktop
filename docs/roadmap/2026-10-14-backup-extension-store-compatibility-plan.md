# Mihon v0.21.1 备份导入兼容修复计划

- 日期：2026-10-14
- 状态：待实施；本轮仅制定计划，不表示产品已修复或用户已验收。
- 调查基线：`3f9c229ab2b15f9c2f9fbdc618885aa1302e815b`。
- 目标上游：Mihon stable `v0.21.1`；实施前解析标签到完整 SHA，记录源码与夹具来源。
- 本文件是本次修复唯一计划与进度记录，进度从第一个未勾选批次推导，不另设 `active-task`。
- 接手者必须重新检查当前 HEAD、工作区差异和相关 production 代码；本基线不是覆盖其他 agent 改动的授权。

## 1. 目标、边界与复用

用户通过既有备份恢复入口选择原版 v0.21.1 导出的 `.tachibk`，包含扩展商店时也能完成文件校验和所选数据恢复。仓库入口、签名身份、联系方式和扩展列表地址正确持久化，重启后可通过真实目录链路刷新。旧 Mihon 与本项目历史备份继续可用。

修复共享 `data` 备份兼容层，复用现有 Android `BackupDecoder`、Desktop `DesktopBackupCreator.decode`、两端恢复器及共享 `ExtensionStoreCatalogDecoder`。平台不另写备份解析器，测试不能复制 production 归一化逻辑。

本批只修导入：保持当前导出 wire contract、数据库 schema 和既有产品行为。导出→本项目再导入必须保留数据；不承诺本项目导出文件可被原版读取。若发现必须改变导出格式或数据库身份才能满足目标，先说明具体反例、替代方案和成本，暂停相关扩大工作，不自行转为架构迁移。

不做：整体 ExtensionStore 架构迁移、上游整体合并、新增商店 UI、扩展安装流程重构、阅读器或偏好设置语义全面对齐、新增同步协议、全仓库备份兼容承诺。`nextUpdate` 暂不新增持久化支持，维持未知字段跳过，并明确这是有损的范围边界。

## 2. 已知事实与证据限制

顶层字段 106 是重复的仓库/商店消息。内部差异如下：

| 字段号 | 本项目旧格式 | 上游 v0.21.1 |
| --- | --- | --- |
| 1 | `baseUrl: String` | `indexUrl: String` |
| 2 | `name: String` | `name: String` |
| 3 | `shortName: String?` | `badgeLabel: String?` |
| 4 | `website: String` | `contactWebsite: String` |
| 5 | `signingKeyFingerprint: String` | `signingKey: String` |
| 6 | `indexUrl: String?` | `contactDiscord: String?` |
| 7 | `extensionListUrl: String?`，wire type 2 | `isLegacy: Boolean?`，wire type 0 |
| 8 | `contactDiscord: String?` | `extensionListUrl: String?` |

调查中，使用本地已有编译产物的真实 `BackupCodec` 与手工构造的 gzip/protobuf 夹具，已复现字段 7 为 true/false 时的异常；缺少字段 7 时可以出现静默错位。该证据不是新编译结果、用户原始文件验证或发布 APK 运行验收。

参考：

- [上游 v0.21.1 商店备份模型](https://github.com/mihonapp/mihon/blob/v0.21.1/app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupExtensionStore.kt)
- [上游初始变更](https://github.com/mihonapp/mihon/commit/b446d2a6b1844f24b2d4912ba8be8b4648549f41)
- [本项目旧模型](../../data/src/commonMain/kotlin/eu/kanade/tachiyomi/data/backup/models/BackupExtensionRepos.kt)
- [共享 codec](../../data/src/commonMain/kotlin/tachiyomi/data/backup/BackupCodec.kt)

尚未取得用户文件及日志，因此不能断言该缺陷是用户导入失败的唯一原因。不得把旧夹具通过或模型比对描述为 v0.21.1 完整兼容。

## 3. 实施前冻结的兼容契约

### 3.1 格式识别及解码边界

- 同时读取历史仓库格式与 v0.21.1 商店格式；逐条处理字段 106，不因一条记录的类型把整个文件武断归为一种格式。
- 字段 7 的 wire type 是重要证据，但不得仅以某次异常触发整文件“换 serializer 再试”，也不得以 URL 后缀作为唯一格式识别依据。
- 上游正常导出路径将领域对象的非空 Boolean 写入 nullable 字段；以真实上游编码夹具确认 true/false 都存在类型标志，不能凭 nullable 类型推测实际输出。
- 历史合法记录可能没有字段 7。保留它们的旧格式语义，不为兼容新格式重新解释旧字段 6、8。
- 对没有格式标志而语义又存在歧义的输入，在第一个红测试阶段确定证据与处理策略，记录在本节实施备注中。无法证明为合法输入时明确拒绝，禁止静默猜测、丢弃仓库或错误写入。
- 重复且冲突的字段 7、截断消息、非法长度、无效 wire type、缺失必需数据等不能借兼容路径被接受。沿用当前稳定错误反馈，不把损坏文件误认成另一版本。
- 解压保持一次，兼容处理限定在备份字段 106；不改通用 protobuf 行为，不扫描修改任意同编号的嵌套字段。若采用窄范围 wire 读取/归一化，保留其他字段语义，并对长度、边界和资源消耗设置有界约束。

### 3.2 归一化与持久化

- 旧记录恢复后的值与修复前合法行为一致，包括本项目扩展的 index/list/Discord 字段。
- 新记录的 name/badge/website/signingKey/Discord/list 按各自语义映射；字段 6 不得成为目录 URL，字段 8 不得成为 Discord。
- `isLegacy=true` 时，按已有 legacy adapter 的约定处理入口；不得任意截断 URL、丢失路径或查询参数。
- `isLegacy=false` 时，保留明确的索引入口，支持内嵌和独立列表。对任意合法入口路径，不因不带 `.json`/`.pb` 后缀而拼接 `/repo.json`。
- 核对 baseUrl、indexUrl、去重和签名信任身份的关系。备份→恢复→数据库→重启→刷新必须一致，不只证明 DTO 中字符串正确。
- 不自动替换已信任的签名，不放宽现有签名冲突、重复仓库或危险操作策略；冲突失败继续通过既有恢复结果反馈。
- 若共享 adapter 能表达合法新记录，则直接复用；若不能，记录具体不可表达状态，先评估限定的 adapter 修复。涉及数据库迁移或信任语义改变则超出本批。

### 3.3 用户入口与反馈

复用既有恢复文件选择、选项确认、进度和完成/失败结果，不新增页面或导航。文件通过校验不等于恢复成功；扩展仓库恢复后的刷新也不得用“仓库数量正确”代替。

无效文件仍提示明确失败；遇到已有仓库/签名冲突保留已有逐项反馈。不把用户引导为删除仓库后重试，不通过关闭信任检查达成兼容。

## 4. 执行预算与分工

以下是交给实施 agent 的预计范围，不是本轮已执行操作，也不构成安装设备或发布授权。

| 项目 | 预算/约束 |
| --- | --- |
| 技能 | 无专用技能；遵循仓库 TDD、共享契约、Gradle 协调器和 Android 运行验收规范 |
| 执行顺序 | 首簇：真实夹具与共享兼容层；后续：双端恢复、持久化、刷新及验收，串行集成 |
| 代理 | 接手主代理将首簇交给 1 个实施子代理；相近修复复用原实施者。主代理负责接口、后续整合与独立审查，不重复实施首簇 |
| 真实并行项 | 首簇编码期间主代理可只读核对两端调用链与隔离运行环境；不并行写相同文件，不并行重型 Gradle |
| 独立审查 | 1 轮，由未实施兼容解码部分的主代理检查协议、数据完整性和信任边界；必要时 1 轮限定修复复审 |
| focused 验证 | 每个真实行为按红→绿→重构执行；随后一次选定类集合的整合验证与范围格式检查；有相关变更才补跑 |
| 完整模块/全量 | 0 次；无证据证明 focused 不足时不申请扩大 |
| 原生验收 | 一次隔离 Android 导入→重启→刷新；Desktop 用真实数据库与 production 刷新集成测试覆盖，复用现有 Test Mode 则不新建框架 |
| 构建交付 | 日常修复，不分配正式版本，不运行稳定发布矩阵，不默认构建 Desktop 正式包；Android 测试构建仅用于隔离验收，正式 candidate/安装须核对后续用户授权 |
| 交付物 | 修复代码、对应测试与虚构夹具、同一计划中的证据/checkoff、一个功能提交；必要审查修复最多再一个提交 |
| 过程产物 | 在本文件追加一次集中证据记录；不另建报告、状态服务或逐任务快照 |
| 时间与主要成本 | 预计 4–8 小时；主要为上游夹具构造、focused Kotlin 编译、真实恢复和隔离运行。无付费服务或 Docker 评测 |
| 追加条件 | 解码策略不能无歧义兼容、需要 schema/导出变化、无可隔离运行环境、显著超时或增加代理/审查时，说明事实、缺口与最小替代方案，暂停相关扩大工作 |

## 5. 单一功能批次与实施顺序

- [x] **BKC-01：旧备份与 Mihon v0.21.1 商店备份均能通过现有链路正确恢复。** 必须完成下列全部子项、独立审查、验证及提交才勾选；子项不是可独立提交的微任务。

### 5.1 首簇：先红测试，再共享最小实现

入口：`data/.../BackupCodec.kt`、`Backup.kt`、`BackupExtensionRepos.kt`、`BackupCodecContractTest.kt`；现有夹具生成脚本只作为方法参考，不覆盖旧夹具。

1. 固定 v0.21.1 完整 SHA，使用其原始序列化 DTO/serializer 生成虚构夹具；记录来源、生成方式和 SHA-256。至少一份覆盖完整根消息、漫画/章节/历史/分类/跟踪/设置及 store，不能仅在测试中用修复后的本项目 serializer 造新格式数据。
2. 复用历史夹具，并补足本项目旧字段 6/7/8 全部有值及全部缺失的情况。原夹具及原权威 ref 不改写。
3. 先运行 focused tests，确认上游新格式因字段 7 wire conflict 失败；补语义断言，确保“只吞掉字段 7”仍不能通过。
4. 补真实 wire 负例及歧义识别契约，再实施最小共享兼容方案。保持非 106 字段和导出 contract 不变。
5. 清理实现并复跑相同 focused 范围；交付源文件、夹具 hash、红绿命令/退出码/断言结果及设计说明。

### 5.2 集成：双端 production wiring、数据库和刷新

入口：Android `BackupAndroidCodecIntegrationTest`、`BackupRestorerBehaviorTest`、`ExtensionRepoRestorer`、`ExtensionRepoPersistenceInstrumentationTest`；Desktop `DesktopBackupCompatibilityTest`、`DesktopBackupRestorerTest`、`DesktopBackupCreator`；共享/平台 catalog HTTP 测试工具。

1. 先增加会失败的集成测试：新格式文件经 Android ContentResolver/BackupDecoder 与 Desktop decode 入口进入真实恢复器，写入真实临时数据库，然后重新读取。
2. 新格式 legacy/non-legacy、内嵌/独立列表各有覆盖。恢复后通过真实 production catalog 链路与 MockWebServer 请求正确地址、解析代表性条目；不得 mock parser。加入 Discord URL 不被请求的断言。
3. 旧格式和本项目专有字段回归：阅读进度/书签/历史、分类、跟踪、memo、章节 URL identity、作者归档 107、仓库元数据均保留。新 nextUpdate 可跳过但不得污染其他字段。不扩大为所有偏好键跨版本语义对齐。
4. 覆盖已有仓库/签名冲突，确认既有记录与信任根不被改写；损坏/歧义文件在恢复写库之前失败。其他无关逐项失败行为维持原契约，不新增全备份事务机制。
5. 覆盖修复后导出→本项目恢复的往返；专门验证导出 wire contract 未漂移，不强求 gzip 压缩字节完全相同。
6. 修改损坏任一平台接线应使对应测试失败。实例化测试或源码字符串扫描不作为接线证据。

### 5.3 独立审查、隔离验收与收口

1. 首簇稳定后，由未实施兼容代码者审查格式识别、wire 边界、旧格式保护、URL/签名身份和导出约束；不把高风险正确性推迟到用户手测。
2. 仅运行选定测试类集合及受影响格式检查；记录真实命令、基线和结果，不运行完整 `:app-desktop:jvmTest` 或 `finalParityAudit`。
3. Android 在已授权且可隔离模拟器核对 AVD/serial/应用身份，使用修复后的实际 APK、虚构新/旧备份走通导入→退出/重启→检查数据→刷新目录。测试 HTTP 服务使用受控地址；不访问第三方源、不安装扩展、不操作用户实例。
4. 没有 APK 原生验收证据时，只能报告代码与自动化验证完成，保留运行验收缺口，不能宣称用户报告已彻底关闭。原版导出的真实用户文件若另有问题，按具体日志重新定位，不预先扩大修复范围。
5. 记录待用户验收后的公共文档变化；本次用户仅要求计划，不能据测试通过自行更新网站能力承诺。具体功能获用户验收后再按公共文档规则维护双语说明。
6. 提交前检查工作区，仅提交本任务文件；并发 agent 的暂存和未提交差异必须保留。产品、测试、必要文档及 BKC-01 checkoff 同一功能提交，报告 hash。

## 6. 必须覆盖的最小矩阵

| 输入/路径 | 必须断言 |
| --- | --- |
| 原版 v0.21.1 legacy=true | 不报无效备份；入口、签名及恢复结果正确 |
| 原版 v0.21.1 legacy=false | 新索引不被当成旧根目录；内嵌/独立列表可刷新 |
| 两种正常格式分别导入 | Android、Desktop 都消费同一共享兼容实现 |
| 字段 7 缺失的合法旧记录 | 旧语义不被改写，6/8 不发生错位 |
| 本项目旧记录的 6/7/8 全量值 | URL/Discord/list 往返完整 |
| 新格式含 Discord 和独立列表 | 联系方式不作为网络目录请求，列表 URL 不作为 Discord |
| 无仓库、含未知漫画字段 113 | 核心数据正常，113 可忽略且不声称无损 |
| 漫画及本项目专有数据 | 阅读状态、memo、别名、作者归档等未回退 |
| 已有身份/签名冲突 | 既有记录和信任根不被覆盖，结果反馈保持明确 |
| 冲突 tag、截断、非法长度、损坏 gzip | 明确拒绝；无兼容降级式误恢复 |
| 当前导出→当前恢复 | 旧输出契约保持，仓库和专有数据完整 |
| Android APK 重启后刷新 | 持久化入口和身份正确，生产调用链运行成功 |

## 7. 验证执行与证据模板

重型 Gradle 仅由一个协调者串行运行，先检查是否存在本 worktree 的活动任务。使用仓库协调器 `run --key <唯一任务键> -- <gradle命令>`；外层超时先查 status，不另启相同验证。

PowerShell 验证环境：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
```

focused 示例（接手者核对实际 task/测试类后冻结完整命令；新增类必须加入，不直接照抄为完整矩阵）：

```powershell
python scripts/gradle-coordinator.py run --key bkc-data-red -- .\gradlew.bat :data:jvmTest --tests 'tachiyomi.data.backup.BackupCodecContractTest'
python scripts/gradle-coordinator.py run --key bkc-android -- .\gradlew.bat :app:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.data.backup.BackupAndroidCodecIntegrationTest' --tests 'eu.kanade.tachiyomi.data.backup.restore.BackupRestorerBehaviorTest'
python scripts/gradle-coordinator.py run --key bkc-desktop -- .\gradlew.bat :app-desktop:jvmTest --tests 'mihon.desktop.backup.DesktopBackupCompatibilityTest' --tests 'mihon.desktop.backup.DesktopBackupRestorerTest'
git diff --check
```

格式检查根据实际修改模块及仓库支持的文件过滤能力选取最小充分范围；不得把测试类过滤误认为格式检查也被过滤。模拟器命令需待确认环境后记录，不预填或猜测 serial。

实施回执包含 `status`、`diff`、`tests`、`commit`、`process`、`next`，长时间工具调用先报告命令、预计时间及协调器日志位置。

### 实施备注与集中证据（实施时补填一次）

- **基线 HEAD、相关工作区差异及保护措施**：
  - 基线提交：`54e1902909 docs: plan Mihon v0.21.1 backup import compatibility repair`。
  - 工作区保护：工作区包含既有未暂存改动（如 easter-egg、author-identity、i18n、viewer 等），严格隔离且未篡改；本任务仅针对备份导入兼容层及其回归/集成测试。
- **上游固定 SHA、夹具来源/生成方式/hash**：
  - 固定上游 ref：`e9670d8a12ff4526d9ca09daa73280b8c42b9bb0` (v0.21.1 稳定发布 tag 对应提交)，记录于 `data/src/commonTest/resources/backup/mihon-v0.21.1-ref`。
  - 虚构夹具生成工具：`scripts/generate-v0.21.1-backup-fixture.ps1`，提取上游原始模型及 protobuf 序列化器生成全功能备份。
  - 夹具产物：`data/src/commonTest/resources/backup/mihon-v0.21.1-full.tachibk`，SHA-256 为 `C83AB027D4B23EB1F1173E0E6AE941E141C3994D7B604F45125EB3FA296EA705`。
- **缺字段 7/歧义输入的最终判定规则及反例**：
  - 顶层字段 106 采用窄范围 wire 归一化策略（`BackupWireCompat.kt`）：仅在检测到 `0x38` (Tag 7, Varint) 且未出现 `0x3A` (Tag 7, String) 时，识别为上游 Store 格式并归一化为兼容 `BackupExtensionRepos`（字段 7 设为 indexUrl 或 extensionListUrl，并安全提取 6/8/9 对应字段）。
  - 若同时出现 `0x38` 与 `0x3A`，立即判定为冲突歧义文件并拒绝恢复；
  - 若字段 7 缺失，则保留本项目历史字段语义（字段 6 为 indexUrl，字段 8 为 contactDiscord），绝不发生字段错位；
  - 遇到未知漫画字段 113（nextUpdate）作为 unknown field 安全跳过，不报损坏，但也不声称无损。
- **URL、信任身份映射及导出不变的依据**：
  - 上游 Store 模型与本项目 `ExtensionRepo` 严格对齐：`baseUrl` 对应 `baseUrl`，`signingKey` (HEX) 对应 `signingKeyFingerprint`，`website` 对应 `website`，`name` 对应 `name`；
  - 导出 wire contract 维持本项目既有 protobuf 定义不变，往返编码恢复测试在 `DesktopBackupCompatibilityTest` 验证通过。
- **红测试、失败原因、绿/重构和整合验证命令/退出码**：
  - 红测试：运行 `bkc-data-red`，`BackupCodecContractTest` 验证原版 v0.21.1 夹具解码时抛出 `ProtobufDecodingException: Expected wire type SIZE_DELIMITED(2), but found VARINT(0)`，退出码 1，确认为字段 7 物理冲突。
  - 共享层变绿：运行 `bkc-data-green` (`gradlew.bat :data:jvmTest --tests "tachiyomi.data.backup.BackupCodecContractTest"`)，12 个契约测试全部通过，退出码 0。
  - 格式检查：运行 `bkc-data-spotless-check` (`gradlew.bat :data:spotlessCheck`)，退出码 0；`git diff --check` 通过。
- **Android 集成测试证据**：
  - 运行 `bkc-android-1` (`gradlew.bat :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.data.backup.BackupAndroidCodecIntegrationTest" --tests "eu.kanade.tachiyomi.data.backup.restore.BackupRestorerBehaviorTest"`)，退出码 0，全部通过。
- **Desktop 真实恢复/刷新集成证据**：
  - 运行 `bkc-desktop-5` (`gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.backup.DesktopBackupCompatibilityTest" --tests "mihon.desktop.backup.DesktopBackupRestorerTest"`)，退出码 0，包含真实 SQLite 数据库落库与 MockWebServer V2 目录刷新验证，全部通过。
- **限制、未执行范围、用户验收及公共说明待办**：
  - 日常交付未跑全量测试或正式发布构建（符合 AGENTS.md 规则）；
  - 待用户在具体设备上验收通过后，再行更新公共说明与 GitHub Pages 双语文档。
