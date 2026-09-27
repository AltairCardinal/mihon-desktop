# Android 阅读进度结算可靠性修复 Roadmap

日期：2026-09-27。状态：**READY_FOR_IMPLEMENTATION，尚未实施**。

行为权威：[修复设计](../2026-09-27-android-reader-progress-settlement-design.md)。既有红测及边界：[诊断证据](../evidence/android-reader-tail-settlement-2026-09-27.md)，提交 `723512708b`。本轮交付仅为文档，不代表授权构建、安装或实施已经完成。

这是独立产品 child plan：从第一个未勾选批次推导进度，不另设 `active-task`，不切换其他父 roadmap 的 `active-child-plan`，不回写既有章节配对计划或 manifest 的完成状态。checkbox 表示该批实现/必要审查/验证/提交全部完成。既有失败测试可作为红测证据，但不能勾选产品修复。

## 1. 目标与批次关系

用户结果：Android 中已经真实显示、被接受结算的末页，在快速跨章并正常退出后仍保存为已读；未显示、错误、回退拖动及手动跳章不会误记完成。原实机 212 页章节最终页索引应为 211。正常进程存活时的排空与系统强杀前未提交数据的边界以设计为准。

依赖：**RP-01 完整修复及独立审查 → RP-02 正式产物与验收**。不把 DTO、队列、ViewModel、数据库测试拆成多个不能独立交付的功能任务。RP-01 同时覆盖写入、生命周期和完成副作用，因为三者共同决定用户数据是否正确；RP-02 不再增加功能或协议。

| 批次 | 可独立交付结果 | 估算 |
| --- | --- | --- |
| RP-01 | 完整 Android 结算修复，原回归转绿且负向保护、正常关闭、失败处理均有生产链测试 | 6–10 工程小时，含 focused 循环和一次独立审查 |
| RP-02 | 集中门禁、正式 Android APK 与原实机证据，按仓库要求完成收口构建和报告 | 4–8 小时，另计环境等待；不可用环境如实记录阻塞 |

总规划约 1.5–2.5 工作日。上次三个测试类的一次 focused 调用因隔离 JVM 与自动重试耗时约 15 分钟，不能按单个断言耗时估算整个 Gradle 调用。主要成本是受控生命周期竞态、真实 SQL/同步接线、三端最终门禁及设备操作；不含同步协议重构、历史数据补偿或新阅读模式。

## 2. 实施前约束与分工

- 获得实施指令后，主代理先确认 HEAD、现有未提交改动、Android 测试基线及设备/包身份。共享工作区已有其他会话的 Desktop、共享 reader core 修改；优先使用适合的既有隔离 checkout，必要时通过 worktree 工具创建。不得回滚、清理或把它们混入修复提交。
- 正式实施的首个功能簇 RP-01 交给 **1 个实施子代理**承担主要 production 与测试；主代理负责接口冻结、代码/证据独立审查和最终集成验收。相近修复复用该实施者。本轮纯文档直接完成，不启动子代理。
- 无重型验证并行项。同 worktree 只有一位 Gradle 协调者，全部使用 `scripts/gradle-coordinator.py run --key … -- …`；等待超时先查 status/日志/PID，不重复启动。不同任务的命令也必须先协调工作区使用。
- 独立审查 **1 轮**，位于 RP-01 完整 production/测试稳定后、RP-02 之前；必要修复复审最多 **1 轮**，仅检查未通过项及影响路径。实现者不得自审该数据完整性变更。
- 预计 focused 分三组：结算/切章、生命周期/副作用、同步/DI/失败反馈；每组红→绿→重构复验，已有同代码基线红测可复用。最多 9 次 focused 调用作为规划估算，批次相关组合验证 1 次；必要失败定位不扩大到全量。
- 最终全量 Android/Desktop 各 **1 次**，格式检查与正式构建串行。自动 test-retry 的重跑按实际次数记录，不能把 retry 通过掩盖为首次通过。超过既定全量/审查上限或需要新范围时先说明证据、原因、成本和替代方案。
- 过程计划复用本 roadmap；最终证据更新既有诊断报告，不再建立逐任务报告、快照或生成器。每批原则上一个含 tests、production、必要架构说明和 checkoff 的提交；复审修复最多一个补充提交。

## 3. 批次任务

### RP-01 · 受理后持久化、退出与完成副作用的完整修复

- [ ] **已受理进度跨章/正常关闭不丢失，所有设计保护与失败处理通过，并完成独立审查及提交。**

**前置与输入**

阅读设计全文、现有 `DualPageProgressProductionWiringTest` 三个诊断用例、`ReaderProgressSettlementRaceTest`、`ReaderViewportSettlementArbiterTest`、`ReaderSyncSessionWiringTest`、`ReaderSyncResumeWiringTest`；核对 Desktop 已有 progress capture/drain 语义与 Android 配对 coordinator 的生命周期模式。以原红测为入口，不通过删断言、放宽等待或阻塞 B 激活取得通过。

**实现范围（作为同一功能批次交付）**

1. 把当前章有效结算的校验、共享 policy 计算和命令登记放在主线程一次受理过程中。邻章仍须成功激活并通过最新选择检查；未受理候选可失效，已受理 effect 不再走 latest/current-chapter 门禁。
2. 接入应用级 `AndroidReaderProgressCoordinator`：同 manga 按接受顺序串行、原 `ReadingProgressSession` 和不可变 metadata 随命令保存、结果观察取消不取消命令、任务结束释放引用；AppModule 注入且真实解析测试覆盖。
3. 实现 Reader handle 的 finish/clear 幂等关闭、已登记写入排空、新 Reader 初始读取的先前工作 barrier；不保留旧页面/图像资源，不阻塞当前 Reader 的快速翻页和跨章。
4. 将持久化完成与 UI 投影分开；正确传递受理后的逻辑 read 状态，允许主动回翻页码降低但 read 不被旧 false 覆盖。单页/Webtoon 继续走其现有合法入口。
5. 把原章完成副作用迁到事务成功之后，保留现有跟踪、重复章节与下载策略；删除入队先于退出清理，旧章完成不能修改新章 UI 或下载恢复状态。网络跟踪不堵塞后续进度事务。
6. 事务失败返回明确结果、后续独立命令继续；活跃 Reader 通过现有 eventFlow/Toast 提示一次，退出后不访问旧 Activity。补齐文案及真实错误事件接线测试，不新增设置页或持久错误系统。

**红→绿→重构验收**

- 先运行/核验 T1 的正确原因失败；为 T3/T4 启动前取消、ViewModelStore.clear、旧页面回收与数据库重开补红测。保留 T2/T5 的错误图片、反向拖动、迟到激活保护，不能改成无条件接受旧 viewport。
- 增加 T6 的完成后回翻及重复回调；T7 用可控事务延迟及真实完成 adapter/捕获服务调用证明 A/B 不串章、删除不先行。失败数据库注入必须在事务边界，不能 mock 掉共享 policy 或整个 repository 来代替存储验收。
- T8/T9/T10 覆盖事务失败、副作用失败、隐私、原同步 snapshot/scope、同书跨章顺序、DI 与 Toast wiring。使用 SQLDelight 实库测试关键字段与 reading_events；同步启用场景执行实际 journal/投影，验证 B 续读而非只检查事件条数。
- T11 复用/补充一个共享行为契约，放入两端已接入的 `data/src/testFixtures/kotlin`，连接 Android 新 adapter 与 Desktop 既有 production adapter，证明接受/完成/回翻/排空语义一致。不得只复制预期或分别测试假的队列；若必须抽取测试入口，限于注入 scope、dispatcher、服务端口，不重写 Desktop runtime。
- 最小实现转绿后清理原写入调用和过期 helper，明确 latest arbiter 只保护待激活/待受理候选，清理两条并行进度写入路径。聚焦上述测试复验，再跑一次相关组合与格式检查。

**独立审查清单（主代理，单轮）**

受理原子性；邻章 Pending 的资格； FIFO 是否按接受顺序而非协程调度顺序；正常关闭前未启动的写入是否存活；旧 page-list 释放后是否仍可执行；同步 snapshot/隐私没有被重取；回翻 read 位和实际位置正确；退出删除及 tracker 不串章；失败不触发删除；队列不持有 Activity/Bitmap；去掉 production wiring 时测试确实失败。检查真实测试结果及关键调用链，不以实现者摘要代替。

**交付与完成条件**

T1–T11 均有有效证据，原三次稳定失败的用例转绿；相关检查及独立审查通过。同步更新 `docs/architecture/reader-shared-core.md` 中进度受理/提交/生命周期说明，使其描述实际实现；本设计记录实施中发生的必要小差异。结构化交接包含 `status/diff/tests/commit/process/next`。测试、实现、架构文档与批次 checkoff 一起提交，未通过不得勾选。

**明确排除**

不改 schema、同步事件格式、宽图/配对算法、history 计时或整个 `ReaderSessionCore`；不修复 Desktop 的其他进行中问题；不添加 WorkManager、进度 WAL/新数据库、无限重试、force-stop 保证或历史进度自动修补。超过 8 文件/400 行仅说明本批 UI→受理→存储→生命周期→完成处理的内聚性与风险，不按机械文件数拆任务。

### RP-02 · 正式产物、原实机复现与集中收口

- [ ] **正式 Android 包完成原书快速跨章验收，最终门禁及证据报告完备，修复迭代提交收口。**

**前置**：RP-01 已通过独立审查，无数据/接口阻塞；已确定同一待发布 diff。读取设备当前包名、版本、证书、ABI 与源码/产物身份，检查工具链和剩余预算。

**执行顺序与范围**

1. 串行运行一次完整 Android release JVM 测试及一次完整 Desktop JVM 测试，按本次变更运行共享 domain/data 契约和格式检查。只要实现 diff 改变，先说明哪些旧证据失效；不得把局部通过充作全量通过。
2. Android 按仓库现有 fork 发布/签名流程构建正式 APK，记录绝对路径、版本、证书和 SHA-256。不能把 debug 测试或 JVM 通过代替发布运行；debug 包仅在仍需诊断时作为有明确目的的过程产物，不默认再构建一次。
3. 使用原实机、原书第四卷执行 T12 两类快速操作各三次，并做未渲染直接跳章负向对照；核对实际页面分组、详情已读、正常重开后的数据库 `last_page_read=211/read=1`、完成事件以及下一章进度。正式包不可 run-as 时采用已有授权的导出/应用诊断入口，不能清数据或拿另一包的数据库冒充。
4. 遵守仓库最终收口门禁，完成 Test Mode/相关冒烟、Windows/macOS 构建与运行。Desktop 没有新增功能；这些是仓库门禁，不扩展为 Desktop 功能重构。Windows/macOS 必须使用 `scripts/build-desktop.sh`；同一 diff 已有等价完整 Desktop 测试时可用 `build-only` 避免重复全量，遵守脚本适用条件。Windows 报告仅引用日志 `Final unpacked EXE:` 的真实存在路径。
5. 更新既有诊断报告：区分历史红测、修复后自动化、debug/正式包、实际设备操作；保留失败及环境限制。报告列明用户可执行验收、正式 APK 链接、门禁产物、提交 hash 和仍存边界。未跑到的项目保持未完成，不用“无关”改写原定必验项。

**失败与追加条件**

原机仍少记时，先保留章/视口接受序号、入队/事务完成/退出顺序与产物身份，再判断是否命中未渲染路径或不同缺陷。诊断只记录必要本地序号和状态，不保存书页内容、来源 URL、同步身份或令牌。不回退成“进入下一章即完成”。

实机断连、签名不兼容、正式数据库证据不可取得、macOS 不可用或全量失败时记录具体阻塞；能继续的独立验证继续。安装需沿用用户已有授权且不清数据，签名不兼容时不得卸载重装。需要额外完整测试、第二轮独立审查、扩展协议或显著追加成本时按预算规则单独提出，不能无边界续跑。

**交付**：通过正式 APK/数据库/重开证据确认用户问题已修复，门禁与报告真实齐全后勾选。本批的必要版本、构建配置和证据更新合并在一个实际验收提交中，不为 advance/close 单独制造状态提交；无相关源码更改的既有已验收产物不为制造 diff 而改动。

## 4. 命令入口与证据规则

Windows 执行前设置 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`、`PYTHONDONTWRITEBYTECODE=1`、`$ErrorActionPreference='Stop'`；SDK 使用 `D:\Android\Sdk`。访问外网按 AGENTS 的会话代理规则。如下是已存在的定向入口，新测试类在 RP-01 落地后加入相应组，不列不存在的类名假装可执行：

```powershell
python scripts/gradle-coordinator.py run --key reader-progress-fix-focused -- .\gradlew.bat :app:testReleaseUnitTest --tests '*DualPageProgressProductionWiringTest' --tests '*ReaderProgressSettlementRaceTest' --tests '*ReaderViewportSettlementArbiterTest'
python scripts/gradle-coordinator.py run --key reader-progress-fix-sync -- .\gradlew.bat :app:testReleaseUnitTest --tests '*ReaderSyncSessionWiringTest' --tests '*ReaderSyncResumeWiringTest' --tests '*ReaderProgressProductionWiringTest'
```

日志和 JUnit/XML 是过程产物；记录实际运行的命令、退出状态、不同用例数与自动重试次数。未确认 coordinator 结束前不能启动下一份重型 Gradle。最终产物链接必须先检查文件存在。回归测试始终验证实际生产接线及存储，不用源码符号扫描、mock parser 或测试内复制策略取得通过。

## 5. 本轮规划交付状态

本轮只新增设计和本 roadmap，做一次文档—源码对应、相对链接、任务范围/验收/依赖及 Git diff 核验，不运行构建或改产品行为。RP-01、RP-02 都保持未勾选。后续实施以当时最新 HEAD 与有效用户授权为准，不把本次规划提交当作已修复证据。
