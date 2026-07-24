---
parent-roadmap: docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md
parent-task: 6
task-base: 12a7445580123e719638830af587a8dfa41d4e0f
original-ref: 6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status-source: this-file
active-task: Task 12
---

# Mihon Desktop 最终 parity 审计实施计划

> 本计划是父路线图 Task 6 的唯一活动执行入口。父 Task 6 在本计划全部完成前保持未勾选。

## 目标与固定事实

目标不是把现有阶段状态批量改名，而是让 64 项能力中的每一项最终都有可复核的固定原版 provenance、当前 Android consumer、Desktop consumer、shared 实现或合法平台 adapter、production protection test，以及唯一终态 `VERIFIED` 或有完整证据的 `EXEMPT`。

本计划固定以下事实，后续执行不得以移动中的当前 `main` 覆盖：

- 固定原版：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。
- 本计划基线：`12a7445580123e719638830af587a8dfa41d4e0f`。
- manifest 恰有 64 项；当前只有 4 项终态：`VERIFIED` 为 90、91、94，`EXEMPT` 为 85。
- 当前 31 项缺少固定原版 `upstreamRef` 或 `upstreamSymbols`：9、10、11、12、16、17、19、22、24、26、44、45、47、49、51、53、54、56、57、59、61、62、64、66、71、72、73、74、93、95、96。
- `UNCLASSIFIED_DEBT` 为 3、4、32、39、69、70、87、88；`TEMP-COMPAT` 为 35、74、96。
- 现有 parity contract 通过只证明阶段状态自洽，尚未提供“64 项只能是 `VERIFIED | EXEMPT`”的最终 closure gate。
- `TEST_COVERAGE_REPORT.md` 位于仓库根目录；父计划写出的 `docs/automation/TEST_COVERAGE_REPORT.md` 不存在，不得创建同名影子权威。
- 父 Task 0 等正文细项仍有未勾选项而总览已勾选；最终文档收口必须恢复 overview、正文和证据的一致性。
- source/extension compat 已有真实 fixture 的逐符号 evidence；最终审计必须复用它，不能另造无 provenance 清单。

## 执行规则

1. 每一批开始时记录该批 `task-base`，先运行 `git diff 6fbf6dfca203d99d6dd32137f2df97ced40c81b8..<task-base> -- <相关路径>`，再用 `git show 6fbf...:<path>` 读取固定原版。
2. provenance 必须区分 `FIXED_ORIGINAL`、`CURRENT_ANDROID`、`SHARED_OR_ADAPTER`、`DESKTOP_CONSUMER` 与 `FIXTURE`；固定原版符号记录准确行号，当前 consumer 记录文件与生产符号。当前 Android 测试和当前 shared 输出不能冒充固定原版证据。
3. 每批最多处理 8 个 parity ID。状态只能在 production wiring、行为测试和角色证据同时成立后升级。
4. `EXEMPT` 必须包含不可替代的 OS/平台能力证据、用户可见边界、失败反馈、保护测试和可追溯的用户批准记录（批准来源、日期、批准的具体能力边界）；没有真实批准记录不得标记 `EXEMPT`，不得由代理代替用户决定或虚构批准。“尚未完成”或“环境暂不可用”不是豁免。
5. 删除前必须先有 Desktop 独有行为回归；历史格式不得在没有兼容 fixture 时删除 reader/writer；跨平台 bugfix 和 Desktop 增强必须记录为 deviation，不能伪装成固定原版行为。
6. 只读 inventory 若发现需要产品修改，不得在 inventory 提交中顺手修复。它必须按上下文簇创建有限 child plan，写明实际文件、TDD、用户入口/反馈、边界和验证，然后把本计划 `active-task` 指向该 child plan。没有真实产品缺口时不得创建空 child plan。
7. 每个 Task 单独检查 `git status`、精确暂存、提交，并更新本计划 overview。不得纳入用户已有脏文件或构建产物。
8. 每个实现 Task 只分配一个实现代理，并在实现完成后由一个未参与实现的独立审查代理审查；审查拒绝时最多进行一次修复复审。超过一次仍未通过时必须停止并重规划，不得继续叠加修复。

## Task 总览

- [x] Task 1：建立可独立触发的最终 closure RED gate
- [x] Task 2：补齐 provenance 批次 P1（9、10、11、12、16、17、19、22）
- [x] Task 3：补齐 provenance 批次 P2（24、26、44、45、47、49、51、53）
- [x] Task 4：补齐 provenance 批次 P3（54、56、57、59、61、62、64、66）
- [x] Task 5：补齐 provenance 批次 P4（71、72、73、74、93、95、96）
- [x] Task 6：核验状态批次 A（3、4、7、8、9、10、11、12）
- [x] Task 7：核验状态批次 B（16、17、19、22、24、26、28、29）
- [x] Task 8：核验状态批次 C（30、32、33、34、35、36、37、38）
- [x] Task 9：核验状态批次 D（39、40、43、44、45、47、49、51）
- [x] Task 10：核验状态批次 E（53、54、56、57、59、61、62、64）
- [x] Task 11：核验状态批次 F（66、67、68、69、70、71、72、73）
- [ ] Task 12：核验状态批次 G（74、81、82、83、84、85、86、87）
- [ ] Task 13：核验状态批次 H（88、90、91、92、93、94、95、96）
- [ ] Task 14：逐项裁决 8 项 `UNCLASSIFIED_DEBT`
- [ ] Task 15：完成候选平台能力与 `EXEMPT` 审查
- [ ] Task 16A：审计 compat 与历史格式删除证据（35、74、96）
- [ ] Task 16B：审计重复业务规则
- [ ] Task 16C：建立 UI→data/network/manager 架构守卫
- [ ] Task 16D：盘点并约束最终 Test Mode 全场景入口
- [ ] Task 17：执行并回收真实产品缺口 child plan
- [ ] Task 18：让 64 项最终 closure 与架构 gate 变绿
- [ ] Task 19：运行全量测试、Windows/macOS 构建与运行验收
- [ ] Task 20：收口维护文档与父子 checkbox

### Task 1：建立可独立触发的最终 closure RED gate

**Risk axis:** final-closure-gate

**Platform boundary:** verification

**Estimated scope:** 4 files, 300 lines

**Verification:** 默认 parity contract 保持 GREEN；显式 final-audit Gradle 入口因 60 项非终态而按正确原因 RED，并报告全部非终态 ID。

**Files:**
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/build.gradle.kts`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`

**Steps:**
1. 为 manifest evidence 增加可表达固定原版符号与行号、当前 Android、shared/adapter、Desktop consumer、fixture/artifact 的结构；先让 4 个现有终态缺证据时精确失败，再补齐它们。
2. 增加仅由显式 final-audit 入口启用的断言：恰有 64 个唯一 ID、每项终态只能是 `VERIFIED | EXEMPT`、终态 role evidence 完整、protection test 指向真实 production 链。
3. 保持普通开发测试可运行；显式 final gate 在 Task 18 前预期 RED，不得用 `@Disabled`、吞异常或硬编码当前计数伪造通过。

**Execution evidence（已完成）：** 基线 `c03c08c331c463009183e3ef4842c1923ba6b16a`。普通契约 RED 精确命中 `ID 85: terminal status requires roleEvidence`；补齐 85/90/91/94 五类角色证据后，`DesktopProductCapabilityContractTest` 为 `34/34` GREEN。显式 `:app-desktop:finalParityAudit` 从实际 manifest 计算并报告 60 个非终态 ID 后按预期 RED。mutation 将 ID3 临时提升为 `VERIFIED` 时精确命中 `ID 3: terminal status requires roleEvidence`；固定原版 symbol mutation 也精确命中 fixed blob/line 校验；恢复后再次输出同一 60-ID RED，manifest 无残留 mutation。命令：focused `:app-desktop:jvmTest --tests 'mihon.desktop.parity.DesktopProductCapabilityContractTest'`、显式 gate `:app-desktop:finalParityAudit`、根 `spotlessCheck`；唯一复审 `APPROVED 0/0/0`。提交证据为本 Task 的四文件原子提交（hash 见交付报告）。范围 `4 files/231 touched`，下一项为 Task 2。

### Task 2：补齐 provenance 批次 P1（9、10、11、12、16、17、19、22）

**Risk axis:** provenance-p1

**Platform boundary:** verification

**Estimated scope:** 5 files, 360 lines

**Verification:** parity contract 与该批 protection tests GREEN；固定原版路径、符号、准确行号和当前三类 consumer 均可解析。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `app-desktop/src/test/resources/parity/fixed-main-path-inventory.json`
- Modify: `app-desktop/build.gradle.kts`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`

**Scope correction:** Task 1 的 fixed-blob/line 校验要求每个 fixed-original path 都存在于 inventory；现有 inventory 不覆盖本批 8 项的大多数固定原版路径。因此 inventory 与 manifest provenance 不可拆分，范围由 3 文件修正为 4 文件，仍保持 `≤360 touched`。

**Review-required scope correction:** 独立审查要求 provenance contract 不得把源码 marker 当作行为完成证据；可重复聚合执行 Desktop、domain 与 data production tests 的 Gradle 入口不可拆分，因此加入 `app-desktop/build.gradle.kts`，范围由 4 文件修正为 5 文件，仍保持 `≤360 touched`。

**Steps:**
1. 对 8 项分别从固定 ref 取证，不得从当前 `app/` 反推原版。
2. 记录固定原版符号/行号、当前 Android consumer、shared/adapter、Desktop consumer 和 fixture/test。
3. 本 Task 只补 provenance；发现产品链缺失时记录到 Task 14/16A–16D 的 inventory 输入，不预判终态。

**Execution evidence（已完成）：** 基线 `ffcbb2240dd803b948d40970ce99c97da6b6b865`。新增 batch contract 首次 RED 精确命中 `ID 9: upstreamRef must not be blank`；8 项均从 `6fbf6df...` 取得 path/symbol/line/blob，状态保持 `WIRED/WIRED/WIRED/NOT_STARTED/SHARED/SHARED/WIRED/SHARED`。独立审查修复轮先由缺失 `behaviorVerificationTask` 精确 RED；ID19 改为真实 `LibraryBottomActionMenu → LibraryTab → LibraryScreenModel.setMangaCategories` 固定链并将本批边界收窄到分类动作，其余批处理动作留给 Task 7；ID22 改为 fixed favorite/category/remove 顺序链，将 fork atomic membership 明确归为 `MIGRATION_OUTPUT` 与 `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT`，保留 `SHARED` 和未证明等价 gap。聚合 `:app-desktop:task2ParityVerification` 执行 18 个 Desktop/domain/data 生产行为类、258 tests、0 failure/0 skipped；错误 ID19 Desktop 行号 mutation 精确 RED 后恢复。focused batch 与普通 contract GREEN；显式 final gate 仍报告同一 60 个非终态 ID 并按设计 RED；根 `spotlessCheck`、plan guard、diff/range 通过。累计范围 `5 files/348 touched`，提交 hash 见交付报告；下一项为 Task 3。

### Task 3：补齐 provenance 批次 P2（24、26、44、45、47、49、51、53）

**Risk axis:** provenance-p2

**Platform boundary:** verification

**Estimated scope:** 5 files, 400 lines

**Verification:** parity contract 与相关 chapter/cover/reader protection tests GREEN；8 项角色证据均来自正确 authority。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `app-desktop/src/test/resources/parity/fixed-main-path-inventory.json`
- Modify: `app-desktop/build.gradle.kts`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`

**Scope correction:** Task 1 的 fixed-blob/line 契约要求本批 fixed-original path 进入 inventory；Task 2 的独立审查又要求 provenance 不得由源码 marker 自证，而要有执行真实 production behavior tests 的单一 Gradle 入口。因此 inventory 与 `task3ParityVerification` 不可从本批拆分，范围由 3 文件修正为 5 文件、上限 400 touched。

**Steps:**
1. 逐项取固定 ref 的章节、封面和 reader 符号及准确行号。
2. 分开记录当前 Android reader consumer、shared contract 和 Desktop decoder/viewer/progress consumer。
3. 相邻 portrait 配对等 fork 增强只记 deviation，不得写入 fixed-original role。

**Execution evidence（已完成）：** 基线 `49a072aeac3e0265c458ad06d93462bdf16a9fb0`。新增 batch contract 首次 RED 精确命中 `ID 24: upstreamRef must not be blank`；8 项均从 `6fbf6df...` 取得 path/symbol/line/blob，状态保持 `SHARED/WIRED/WIRED/WIRED/WIRED/WIRED/WIRED/WIRED`。ID26 的 shared cover workflow 与 ID53 的 transactional progress workflow 明确归为 fork `MIGRATION_OUTPUT`，没有冒充当前 Android shared consumer；相邻 portrait 配对仍仅保留为 ID43 的 fork product enhancement，未写入本批 fixed-original 角色。聚合 `:app-desktop:task3ParityVerification` 执行 17 个 Desktop/domain 生产行为类、254 tests、0 failure/0 skipped；focused batch 与普通 contract GREEN；显式 final gate 仍报告同一 60 个非终态 ID 并按设计 RED；根 `spotlessCheck`、plan guard、diff/range 通过。范围 `5 files/286 touched`，提交 hash 见交付报告；下一项为 Task 4。

### Task 4：补齐 provenance 批次 P3（54、56、57、59、61、62、64、66）

**Risk axis:** provenance-p3

**Platform boundary:** verification

**Estimated scope:** 5 files, 400 lines

**Verification:** parity contract 与下载、更新、历史、统计 protection tests GREEN；8 项固定原版和当前 consumer 角色完整。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `app-desktop/src/test/resources/parity/fixed-main-path-inventory.json`
- Modify: `app-desktop/build.gradle.kts`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`

**Scope correction:** Task 1 的 fixed-blob/line 契约要求本批 fixed-original path 进入 inventory；Task 2/3 已建立“manifest 方法必须由单一 Gradle 入口实际执行”的约束。因此 fixed inventory 与 `task4ParityVerification` 是本批完成条件，范围由 3 文件修正为 5 文件、上限 400 touched。

**Steps:**
1. 从固定 ref 取 reader navigation、download queue、自动下载、library update、updates/history/stats 证据。
2. 对 ID56 明确比较固定原版 source 分组与当前 Desktop 分组；差异在状态核验前不得被文案掩盖。
3. 当前 SQLDelight/shared 测试只证明 current wiring，另行绑定固定 fixture。

**Execution evidence（已完成）：** 基线 `af3ccf6e0637a8b1909f8532de8888c5296cda5b`。新增 batch contract 首次 RED 精确命中 `ID 54: upstreamRef must not be blank`；8 项均从 `6fbf6df...` 取得 path/symbol/line/blob，状态保持 `WIRED/WIRED/WIRED/WIRED/WIRED/WIRED/WIRED/SHARED`。ID56 明确绑定 fixed `DownloadQueueScreenModel.groupBy { it.source }` 与 current Desktop `queue.groupBy(DownloadItem::sourceId)`，保留 source-name 解析及 missing-source fallback 差异；ID57、61、66 的 fork shared scheduler/checkpoint/aggregation 均记录为 `MIGRATION_OUTPUT`，没有用 SQLDelight/shared current 测试冒充 fixed authority。聚合 `:app-desktop:task4ParityVerification` 以全局 headless 模式执行 14 个 Desktop/domain 生产行为类、162 tests、0 failure/0 skipped；focused batch 与普通 contract GREEN；显式 final gate 仍报告同一 60 个非终态 ID 并按设计 RED；根 `spotlessCheck`、plan guard、JSON、diff/range 通过。范围 `5 files/296 touched`，提交 hash 见交付报告；下一项为 Task 5。

### Task 5：补齐 provenance 批次 P4（71、72、73、74、93、95、96）

**Risk axis:** provenance-p4

**Platform boundary:** verification

**Estimated scope:** 4 files, 380 lines

**Verification:** parity contract、backup fixture contract 与 architecture guard GREEN；7 项不再使用 “fixed-main provenance pending”。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/resources/parity/fixed-main-path-inventory.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `app-desktop/build.gradle.kts`
- Modify: `docs/desktop-parity/PARITY_TRACKER.md`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`

**Scope correction:** Task 1 要求每个 fixed-original path 由 fixed-main inventory 绑定 blob，Task 5 又要求通过单一 Gradle 入口执行真实 data backup fixture contract 与 Desktop architecture guard；这两项不能由 manifest marker scan 代替。因此加入 `fixed-main-path-inventory.json` 与 `app-desktop/build.gradle.kts`，范围由 4 文件修正为 6 文件，仍保持 `≤400 touched`。

**Steps:**
1. 分开记录固定原版 backup 产物、当前 Android 历史产物和 Desktop 历史产物；当前分支生成的 fixture 不得冒充固定原版。
2. 为高级维护、模块边界和兼容成本记录可验证的固定原版/当前职责，而不是抽象口号。
3. 立即消除 tracker 对 71–74 与 manifest 的状态矛盾，但不提前升级终态。

**Execution evidence（已完成）：** 基线 `6577070f4a9b4a4aafe32dd883b462a9c54073c8`。新增 batch contract 首次 RED 精确命中 `ID 71: upstreamRef must not be blank`；7 项均从 `6fbf6df...` 取得 path/symbol/line/blob，manifest 状态保持 7 项 `NOT_STARTED`，tracker 的 71–74 已同步为 `NOT_STARTED` 而未提前升级。固定原版 Backup creator/restorer/job/model、当前 Android consumer 与 fork shared codec/adapter 分角色记录；`android-full.tachibk` 仍由 fixed main 生成，`desktop-first-writer.tachibk` 独立来自 Desktop `8c6d18c20`，当前分支 fixture 没有冒充 fixed source。聚合 `:app-desktop:task5ParityVerification` 以全局 headless 模式执行 11 个 Desktop/data 真实行为类、114 tests、0 failure/0 skipped；真实 backup fixture contract `4/4`、architecture guard `4/4`、focused batch 与普通 contract GREEN；显式 final gate 仍报告同一 60 个非终态 ID 并按设计 RED；根 `spotlessCheck`、plan guard、JSON、diff/range 通过。范围 `6 files/316 touched`，提交 hash 见交付报告；下一项为 Task 6。

### Task 6：核验状态批次 A（3、4、7、8、9、10、11、12）

**Risk axis:** status-a

**Platform boundary:** verification

**Estimated scope:** 4 files, 400 lines

**Verification:** 8 项 production wiring/protection tests 与 parity contract GREEN；每个终态都有 role evidence，未闭合项保留准确 gap。

**Steps:** 对每项执行 fixed-original → current Android → shared/adapter → Desktop production call path 核验；只有真实行为与失败路径被测试执行后才改为终态。

**Scope correction:** 独立审查要求加入 `behaviorMethods ⊆ protectionTests` mutation guard，并准确解析父子计划 YAML frontmatter 与产品文件范围；文件数仍为 4，触达上限修正为 400 行。

**Audit evidence（已完成）：** 基线 `0cec5ed06a96dde60e63a9c882a42c9cbbaf1ba4`，ID 12 closeout 基线 `30eef79cd0c8ea6b2449c9c0fb4369d26acba5a1`。ID 9 同时具备固定原版、当前 Android、shared decoder、Desktop Skia consumer、真实成功/失败 fixture 与 protection tests，状态从 `WIRED` 提升为 `VERIFIED`。ID 12 的 fixed-main 与 current Android `CrashLogUtil.dumpLogs()` 均覆盖成功日志导出及失败反馈；Desktop 因无 Android logcat/share runtime 使用平台 adapter，并由 `Main.kt` 在 production startup 安装 `CrashHandler`，测试覆盖成功写入、轮转、不可写 failure containment 以及 stderr 原始异常/失败/截断反馈，因此从 `NOT_STARTED` 提升为 `VERIFIED`。ID 3/4 的未分类架构债务交给 Task 14；ID 8 的 network/manager wiring gap 交给 Task 16C；ID 10 的 Android/Desktop 重复任务规则交给 Task 16B；ID 11 的 native delivery success 与 Android shared-event consumption gap 交给 Task 17；ID 7 缺 current Android PreferenceStore adapter 真实行为契约，保持 `WIRED`。控制权推进到 `active-task: Task 7`。

**Verification evidence：** 初始状态契约精确 RED 于 `ID 9 expected VERIFIED but was WIRED`；ID 12 closeout 状态契约精确 RED 于 `ID 12 expected VERIFIED but was NOT_STARTED`。补证后 Task 6 focused `1/1` 与普通 parity contract `39/39` GREEN；真实行为矩阵为 domain `7/7`、Desktop preference `22/22`、current Android decoder `8/8`、Desktop 八类 production tests `224/224`。显式 final gate 按设计 RED，并将非终态从 60 项依次准确缩减为 59 项和 58 项（ID 9、12 已移出）。

### Task 7：核验状态批次 B（16、17、19、22、24、26、28、29）

**Risk axis:** status-b

**Platform boundary:** verification

**Estimated scope:** 4 files, 360 lines

**Verification:** library/detail/source focused tests 与 parity contract GREEN；8 项状态不由源码字符串扫描自证。

**Steps:** 核验分类、筛选、批处理、收藏/分类、章节、封面、source membership 与单源浏览的真实 consumer。

**Audit evidence（child plan 已回收）：** R1 `6fb82074adeceda25be2f3a12621ce510fd0423c` 稳定结构化审计，R2 `af9c522ec9f5c7032ebe3503bab6f9a6a1659e6f` 补齐产品链。ID 17 的 fixed authority、shared `EvaluateLibrary`、current Android `applyFilters`/`applySort` consumer、Desktop consumer 与真实 mutation fixture 全部闭合，从 `SHARED` 提升为 `VERIFIED`。ID 19 的 fixed/current Android 批量菜单、shared 分类能力、Desktop 分类/已读/未读/移除以及六档下载、迁移、可见项反选、partial feedback/navigation fixtures 全部闭合，从 `WIRED` 提升为 `VERIFIED`。ID 29 保持 `VERIFIED`；ID 16/22/24/26/28 保留各自真实 gap。

**Verification evidence：** R3 状态契约先精确 RED 于 `ID 17 expected VERIFIED but was SHARED`，补证后 focused `1/1`、普通 parity contract `40/40` GREEN；R2 回归为 Desktop library/navigation `44/44`、Android consumer `2/2`，domain `EvaluateLibrary` `6/6`、data membership transaction `2/2`。终态契约已验证 fixed/current/shared-or-adapter/Desktop/fixture 五角色，显式 final gate 按设计 RED 并精确报告 55 个非终态 ID。

**Closeout execution evidence（已完成）：** R1 `6fb82074adeceda25be2f3a12621ce510fd0423c`、R2 `af9c522ec9f5c7032ebe3503bab6f9a6a1659e6f`、R3 `f9cbea69a5185f2c2ee663b4a8c023a0dbc82fce` 均已提交；八项 `statusDecision` 与保留 gap 已由父 closeout 契约核验，Task 7 已勾选，控制权推进到 `active-task: Task 8`。本 closeout 提交自身 hash 仅在交付报告记录，不写回本计划。

### Task 8：核验状态批次 C（30、32、33、34、35、36、37、38）

**Risk axis:** status-c

**Platform boundary:** verification

**Estimated scope:** 4 files, 360 lines

**Verification:** source/extension shared、Android、Desktop 与真实 compat fixture tests GREEN；8 项状态和 evidence 一致。

**Steps:** 复用既有逐符号 compat evidence；ID35 在 Task 16A 前不得删除仍被真实扩展 fixture 触达的 shim。

**Audit evidence（已完成）：** ID 30 的 fixed-main 全局搜索、current Android shared-service consumer、shared query service、Desktop canonical/retry/stale-generation consumer 与两端 fixture 闭合；ID 33 的两端 catalog consumer 覆盖成功、空、畸形及 403/429/500；ID 34 的两端 install coordinator/adapter 覆盖真实 JAR 安装、digest/repository/signer、HTTP taxonomy 与 rollback；ID 36 的 shared trust policy 在两端覆盖 legacy/untrusted failure；ID 37 的 shared presentation store 在两端覆盖分类、搜索、失败反馈与重试，五项均提升为 `VERIFIED`。ID 32 保持 `NOT_STARTED` 并交 Task 14；ID 35 的真实 ManHuaGui fixture 仍执行 compat shim，保持 `WIRED`/`TEMP-COMPAT` 并交 Task 16A；ID 38 缺 current Android real-source preference behavior fixture，保持 `WIRED` 并交 Task 18。无本轮可顺手修复的产品缺口，未创建 child plan。

**Verification evidence：** Task 8 状态契约先精确 RED 于 `ID 30 expected VERIFIED but was WIRED`，再 GREEN `1/1`；shared/domain `64/64`、Android `37/37`、Desktop/compat `91/91`、普通契约 `41/41` 均全绿且零跳过。显式 `finalParityAudit` 仅按设计 RED 于恰好 `50` 个非终态 ID；完成逐项五角色与真实行为绑定后，控制权推进到 `active-task: Task 9`。

### Task 9：核验状态批次 D（39、40、43、44、45、47、49、51）

**Risk axis:** status-d

**Platform boundary:** verification

**Estimated scope:** 4 files, 360 lines

**Verification:** login/Cloudflare/reader focused tests 与 parity contract GREEN；固定 reader 默认和 Desktop 增强清楚分层。

**Steps:** 核验浏览器登录/挑战恢复和 reader 解码、预载、过渡、导航、色彩链；平台 IO 只作为 adapter。

**Audit evidence（Task 9 已完成）：** ID 39 缺 fixed-main embedded WebView 等价能力，保留 `WIRED` 并交 Task 14。ID 40/43/44/45/49 的挑战恢复、配对增强边界、区域解码、预载取消与导航证据闭合，保持 `VERIFIED`。ID 47 已由 Pager/Webtoon holder → production observer seam → `sharedStateFlow.collectLatest` 的两项可执行 fixture 保护；ID 51 已由 current Android preference/helper 与 Desktop `ReaderViewport → ReaderViewportColorLayer → readerColorTransform → readerColorMatrix` 的 mounted 像素 fixture 保护。两项五角色及断链 mutation 均闭合，保持 `VERIFIED`；Task 9 已勾选，控制权推进到未勾选的 Task 10。

**Verification evidence：** 初始 Task 9 审计由 source-scan mutation 揭示 47/51 误升并恢复 gap；S1 `c04721f44b5c490c541f05c868c77975142e87e2` 清除了 manifest、Task 3 历史及 reader evidence 中的 scan 认证。S2 `d0311eb381a45d323bc28cd1ee4ac010e312fc2d` 以 Android `14/14`、Desktop `21/21`、均 0 skipped 的真实 wiring 测试替代扫描；Pager、Webtoon 与 Desktop 三项断链 mutation 均精确 RED 后恢复。S3 状态契约先精确 RED 于 `ID 47 expected VERIFIED but was WIRED`，补齐五角色后 focused `1/1`、新增 Android `5/5`、新增 Desktop `2/2`、ordinary contract `42/42` 均 GREEN 且 0 skipped；显式 `finalParityAudit` 实际只按设计 RED 于 `43` 个非终态 ID。

**Closeout execution evidence（已完成）：** S1 `c04721f44b5c490c541f05c868c77975142e87e2`、S2 `d0311eb381a45d323bc28cd1ee4ac010e312fc2d`、S3 `84add84daad5606a20ac9793d39349b7bbb0a744` 已顺序闭合；最终裁决为 ID 39 `WIRED → Task 14`，ID 40/43/44/45/47/49/51 `VERIFIED`。S4 focused 先精确 RED 于 `Task 9 closeout must advance to Task 10 ==> expected Task 10 but was Task 9`（1 test/1 failed），补齐父状态后 GREEN `1/1`；ordinary contract `42/42`、0 skip，显式 final gate 唯一按设计 RED 于精确 43 个非终态 ID 且不含 47/51，`spotlessCheck`、JSON、plan、diff、range guards 均 PASS。范围为 3 files/21 touched；S4 自身提交 hash 仅在交付报告中提供，不写回计划。

### Task 10：核验状态批次 E（53、54、56、57、59、61、62、64）

**Risk axis:** status-e

**Platform boundary:** verification

**Estimated scope:** 4 files, 360 lines

**Verification:** reader-progress/download/update/history focused tests 与 parity contract GREEN；ID56 分组差异没有被错误升级。

**Steps:** 核验 progress transaction、chapter navigation、队列/并发/自动下载、library update、updates/history；若 ID56 仍有语义差异，输出实际 gap 给 Task 14。

**Audit evidence（已完成）：** ID 53/57/59/61/62/64 的 fixed-original、current Android、shared/adapter、Desktop production consumer 与可执行 fixture 五角色均闭合，提升为 `VERIFIED`。ID 54 保持 `WIRED`：current Android `ReaderViewModel.getChapterList → shared filter` 目前只有 source scan，断开 production wiring 不会让可执行测试失败，有限转交 Task 14。ID 57 明确绑定 shared `schedule`/`retryDelayMillis` 与 Desktop 三个 production 调用，并由真实 `DesktopDownloadManager` + MockWebServer fixture 断言 2/4/8 秒策略；ID 59 由完整 DI 初始化后的 `LibraryUpdateScheduler → FilterChaptersForDownload → EnqueueDownload → PersistentDownloadStore` 链保护，fixture 以真实“仅未读”偏好排除已读同号候选、只持久化保留候选。ID 56 不误升：fixed-main 按 source object identity 分组，而 Desktop 按持久化 `sourceId` 投影并提供 missing-source fallback，替换 source object 或重复 ID 时的等价性未闭合，保持 `WIRED` 并有限转交 Task 14。Task 10 已勾选，控制权推进到未勾选的 Task 11。

**Verification evidence：** Task 10 初始状态契约精确 RED 于 capability set `expected [53,54,56,57,59,61,62,64] but was []`；fresh review 修复又精确 RED 于 `ID 54 expected WIRED but was VERIFIED`。ID 59 临时绕过 production filter 后 fixture 精确 RED 于 `expected [103] but was [102,103]`，恢复 production wiring 后 Task 10 focused + DI `2/2` GREEN。Task 3 reader/progress 聚合 `261/261`、Task 4 download/update/history 聚合 `168/168`、Desktop retry 集成 `8/8`、ordinary parity contract `43/43` 均 GREEN，0 failure/0 skipped；`spotlessCheck` GREEN。显式 `finalParityAudit` 唯一按设计 RED 于精确 `37` 个非终态 ID，ID 54/56 均保留在清单中。

### Task 11：核验状态批次 F（66、67、68、69、70、71、72、73）

**Risk axis:** status-f

**Platform boundary:** verification

**Estimated scope:** 4 files, 380 lines

**Verification:** stats/migration/tracking/backup focused tests 与 parity contract GREEN；当前 reliability 增强不冒充 fixed-main。

**Steps:** 核验统计、单/批迁移、tracker provider/sync、手动备份/恢复/自动备份；69/70 的 fixed-main 缺口逐字写入 Task 14 输入。

**Audit evidence（已完成）：** ID 67/68 的 fixed-original、current Android、shared、Desktop production consumer 与可执行 fixture 五角色闭合，提升为 `VERIFIED`。ID 66 保持 `SHARED` 并转交 Task 14：current Android 仍是独立直接 `StatsData` 聚合，缺少能在 Android 统计语义漂移时失败的 production behavior fixture。ID 69/70 保持 `CHARACTERIZED` 并有限转交 Task 14：ID 69 尚缺 production provider configuration、bind-existing/new-entry、refresh-before-update、initial reading status/date、MAL error、search model、private/date/delete、enhanced auto-match、Suwayomi delete、provider error classification/retry、Komga DNS/server discovery、Kitsu/MangaUpdates request shape；ID 70 尚缺 refresh-before-update、login/progress filtering、parallel provider updates、per-track monotonic highest progress、network constraint、unique work、exponential backoff、bounded retry、queue cleanup。OS credential、persistent checkpoint 等安全性/可靠性增强均保留为 enhancement，不冒充 fixed-main 等价证据。ID 71 的 Desktop creator production chain 可执行，但 fixed-original artifact fixture 仍缺失，源码 generator scan 被排除为行为证据，保持 `WIRED`；ID 72 现有 fixture 仅是历史 Desktop first-writer artifact，未执行 fixed-original Android artifact 或 current Android `BackupRestorer`，保持 `WIRED`；ID 73 仅证明进程内 prune，退出后唤醒与等价 periodic scheduling 未闭合，保持 `WIRED`；三项均转交 Task 14。Task 11 已勾选，控制权推进到未勾选的 Task 12。

**Verification evidence：** Task 11 状态契约先精确 RED 于空 capability set，独立审查指出 ID 66/72 证据不足后又精确 RED 于保守状态预期；修复后单契约 `1/1` GREEN。Desktop migration/tracking/backup focused `75/75`、domain migration/provider/sync `19/19`、data membership/backup codec `6/6`、Android current consumer/API/codec `16/16`、ordinary parity contract `44/44` 均 GREEN，0 failure/0 skipped；`spotlessCheck`、JSON、计划交接、diff/range 与 headless 配置核验均通过。Task 4 聚合 GREEN；Task 5 聚合 `119/120`，唯一失败是用户既有未提交 `DownloadQueueScreen.kt` 触发 `DesktopArchitectureGuardTest` 大文件行数基线，与 Task 11 三文件变更无关且未回滚。显式 `finalParityAudit` 唯一按设计 RED，并精确报告 `35` 个非终态 ID。

### Task 12：核验状态批次 G（74、81、82、83、84、85、86、87）

**Risk axis:** status-g

**Platform boundary:** verification

**Estimated scope:** 4 files, 380 lines

**Verification:** backup compatibility、platform integration、i18n focused tests 与 parity contract GREEN；ID85 豁免证据仍成立。

**Steps:** 核验跨端备份、URI、分享、锁、屏幕隐私、Widget 豁免、更新、i18n；候选 OS 结论留给 Task 15。

### Task 13：核验状态批次 H（88、90、91、92、93、94、95、96）

**Risk axis:** status-h

**Platform boundary:** verification

**Estimated scope:** 4 files, 380 lines

**Verification:** settings/accessibility/maintenance/architecture focused tests 与 parity contract GREEN；已终态 90/91/94 不回退。

**Steps:** 复核 88 的真实共享边界、92 的 Unsupported 子能力反馈、93/95/96 的 production/architecture evidence。

> Tasks 6–13 共用文件边界：只修改 manifest、parity contract、本计划和至多一个该批现有 protection test；不得把多批产品修复混入状态提交。

### Task 14：逐项裁决 8 项 `UNCLASSIFIED_DEBT`

**Risk axis:** unclassified-debt

**Platform boundary:** verification

**Estimated scope:** 4 files, 400 lines

**Verification:** 3、4、32、39、69、70、87、88 每项都有 `reuse | extract | adapter | deviation | exempt` 唯一决策、真实 call path 和保护证据；无 “待以后处理” 空结论。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `docs/desktop-parity/PARITY_TRACKER.md`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`
- Create if needed: `docs/superpowers/plans/<date>-mihon-desktop-final-parity-<context-cluster>.md`

**Steps:**
1. 逐项决定能否直接复用、是否应抽共享能力、是否只需平台 adapter，以及用户入口/反馈是否完整。
2. 已有证据足够则删除 `UNCLASSIFIED_DEBT` 并给出终态；需要产品改动时按上下文簇创建有限 child plan，不在本 Task 顺手改代码。
3. child plan 必须固定原版 ref，列真实文件和 TDD，单 Task 不超过 8 files/400 lines，并更新本计划 resume 入口。

### Task 15：完成候选平台能力与 `EXEMPT` 审查

**Risk axis:** platform-terminal-evidence

**Platform boundary:** verification

**Estimated scope:** 5 files, 400 lines

**Verification:** 81、82、83、84、85、86、92 全部获得可重复 OS/产物证据后成为 `VERIFIED` 或严格 `EXEMPT`；每个 `EXEMPT` 均有真实、可追溯的用户批准记录，显式 platform-evidence contract GREEN。

**Steps:**
1. 复用既有 Windows/macOS/Linux 能力报告，但对当前构建重新验证真实 production adapter、用户反馈与失败状态。
2. `CANDIDATE` 不能直接改名；无法提供真实 OS 能力时，只有能力本质不可用、UI 诚实反馈且用户明确批准该具体边界时才能 `EXEMPT`。没有批准记录必须保持非终态并请求用户决定，代理不得补写或推断批准。
3. 需要产品修复时输出有限 platform child plan；环境暂缺只记录验证阻塞，不伪造豁免。

### Task 16A：审计 compat 与历史格式删除证据（35、74、96）

**Risk axis:** compat-history-removal

**Platform boundary:** verification

**Estimated scope:** 5 files, 360 lines

**Verification:** 35、74、96 有逐符号调用、真实 fixture、removal condition 和历史读写兼容证据；需要删除的 compat 有有限 child plan，仍需保留的 adapter 有非空理由和回归测试。

**Steps:**
1. 对 source/extension compat 复用真实 fixture evidence，删除条件按符号判断，不能按目录一刀切。
2. 对 backup reader/writer 先跑固定原版、当前 Android、Desktop 历史 fixture；没有 backward compatibility 前不得切 writer 或删 reader。
3. 本 Task 只决定 compat/历史格式的删除或保留证据；需要产品修改时输出有限 child plan，不混入重复规则或架构守卫。

### Task 16B：审计重复业务规则

**Risk axis:** duplicate-business-rules

**Platform boundary:** verification

**Estimated scope:** 5 files, 380 lines

**Verification:** shared、当前 Android 和 Desktop 的领域规则/状态机按 symbol 与 production caller 对照；每个重复项都有保留、抽取或删除唯一结论，并有能在 wiring 断开时失败的行为测试入口。

**Steps:**
1. 只审计业务规则、状态机和 writer ownership，不把合法平台 side effect adapter 判为重复实现。
2. 固定原版语义、cross-platform bugfix 与 Desktop product deviation 分栏记录。
3. 需要产品修改时按共享上下文簇输出有限 child plan；不得用源码字符串相同/不同作为完成证据。

### Task 16C：建立 UI→data/network/manager 架构守卫

**Risk axis:** ui-dependency-architecture

**Platform boundary:** tooling

**Estimated scope:** 6 files, 400 lines

**Verification:** 架构测试执行真实 production 依赖并拒绝 UI 直接依赖 data query、HTTP client、download/extension manager 或 ClassLoader；允许列表仅含有理由的平台 adapter，断开合法 use case/port 后测试精确 RED。

**Steps:**
1. 盘点现有 Architecture Guard 能力，行数基线与源码文本扫描不能作为唯一守卫。
2. 为 `UI → shared use case/port → repository/platform adapter` 建立可执行依赖约束。
3. 发现违规时输出有限产品 child plan；本 Task 不顺手重构 consumer。

### Task 16D：盘点并约束最终 Test Mode 全场景入口

**Risk axis:** testmode-scenario-coverage

**Platform boundary:** verification

**Estimated scope:** 6 files, 400 lines

**Verification:** coverage inventory 将 64 项全部映射到恰好 13 个场景族中的至少一个，或记录真实、可测试的非 UI 边界；另有 5/5 Desktop 永久保护映射，未映射计数为 0。缺少 runner/production wiring 时输出有限 child plan。

**场景族（最终必须恰好 13/13）：**

1. `library`
2. `manga-detail`
3. `browse/global-search+source-login`
4. `extensions`
5. `reader`
6. `downloads`
7. `updates/upcoming`
8. `history`
9. `migration`
10. `backup/restore`
11. `settings/platform`
12. `tracking`
13. `about`

**Desktop 永久保护（最终必须恰好 5/5）：**作者入口、Upcoming、双页、自动滚动、APK→JAR。

**Steps:**
1. 建立 manifest ID → 场景族/非 UI 边界 → production trigger → observable feedback → test action/assertion 映射；非 UI 边界必须有真实行为测试，不能成为逃避场景覆盖的标签。
2. 最终唯一 runtime 入口固定为 `./scripts/desktop-final-parity-test.sh`：它必须启动本轮构建脚本产出的固定未打包 EXE，并报告 `13/13`、`5/5`、64 项零未映射。
3. 现有 `./scripts/desktop-smoke-test.sh` 只能作为补充回归，不能替代上述入口或其精确计数。
4. 若入口、Test Mode action/state 或场景断言缺失，只输出有限产品 child plan，由 Task 17 实施。

### Task 17：执行并回收真实产品缺口 child plan

**Risk axis:** child-plan-return-gate

**Platform boundary:** verification

**Estimated scope:** 2 files, 160 lines

**Verification:** Tasks 14、15、16A、16B、16C、16D 生成的每个 child plan 均完成其 focused tests、一个独立审查和最多一次修复复审、范围门禁和提交；未生成 child plan 的项目有明确“现有 production evidence 足够”结论。

**Steps:** 按 child plan 顺序执行；每次完成后回到本计划更新 status 与 evidence。14、15、16A–16D 任一 inventory 未完成或任一 child plan 未完成时，不得进入 Task 18。

### Task 18：让 64 项最终 closure 与架构 gate 变绿

**Risk axis:** terminal-parity-gate

**Platform boundary:** verification

**Estimated scope:** 5 files, 360 lines

**Verification:** Tasks 14、15、16A、16B、16C、16D、17 全部完成后，显式 final-audit Gradle 入口 GREEN，精确断言 64/64 为 `VERIFIED | EXEMPT`、角色证据完整、每个 `EXEMPT` 有真实用户批准记录、无 `UNCLASSIFIED_DEBT`/`TEMP-COMPAT`、架构守卫 GREEN。

**Steps:**
1. 逐项确认终态，不得批量替换状态字符串。
2. 收紧 contract：终态、固定 ref、准确行号、当前 Android/Desktop/shared-or-adapter、fixture/protection test、EXEMPT evidence 与可追溯用户批准记录均为强制；批准缺失必须失败，不得用默认值生成。
3. 将 final parity gate 接入明确的 Gradle lifecycle 入口，不用普通 compile 成功替代。

### Task 19：运行全量测试、Windows/macOS 构建与运行验收

**Risk axis:** final-runtime-validation

**Platform boundary:** verification

**Estimated scope:** 3 files, 300 lines

**Verification:** Spotless、相关 shared/Android、`:app-desktop:jvmTest`、`:test-desktop:test`、补充 smoke、final parity gate 全绿；唯一 runtime 入口启动固定 EXE 并精确报告 13/13 场景族、5/5 永久保护、64 项零未映射；Windows/macOS 使用构建脚本产出同一版本并运行验收，Linux 边界诚实记录。

**Files:**
- Create: `docs/superpowers/reports/2026-07-23-mihon-desktop-final-parity-verify.md`
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`

**Steps:**
1. 串行运行 `./gradlew spotlessCheck`、相关 shared/Android 契约、`:app-desktop:jvmTest`、`:test-desktop:test` 与 final parity gate。
2. Desktop 迭代只能用 `./scripts/build-desktop.sh` 构建；随后运行唯一入口 `./scripts/desktop-final-parity-test.sh`，由它启动该轮固定未打包 Windows EXE。最终结果必须为 13 个场景族全部通过、5 项永久保护全部通过、64 项映射零遗漏。
3. 另行运行 `./scripts/desktop-smoke-test.sh` 作为补充；其通过不能替代第 2 步。
4. 在 macOS 使用同一提交和构建脚本验收 app bundle；Linux/WSL 无真实环境时只记录边界，不外推。
5. 报告必须记录固定 `original-ref`、用过的 `git show <original-ref>:<path>`/blob 校验方式及结果、真实命令、测试数、失败/跳过、13/13、5/5、零未映射、版本、绝对产物路径、每个 EXEMPT 的用户批准引用、deviation 和环境限制。

### Task 20：收口维护文档与父子 checkbox

**Risk axis:** final-doc-authority

**Platform boundary:** docs

**Estimated scope:** 6 files, 400 lines

**Verification:** 两份计划 guard、final parity gate、文档路径/状态一致性检查和 `git diff --check` GREEN；父 Task 6 仅在全部证据成立后勾选。

**Files:**
- Modify: `docs/desktop-parity/PARITY_TRACKER.md`
- Modify: `docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md`
- Modify: `TEST_COVERAGE_REPORT.md`
- Modify: `docs/automation/TASK_TRACKER.md`
- Modify: `docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md`
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`

**Steps:**
1. tracker 保持治理说明和由 manifest 导出的终态摘要，不复制第二份 64 项权威表。
2. 比较报告只写 fixed-original、intentional cross-platform bugfix、Desktop product deviation 或有证据 EXEMPT。
3. 更新根目录 coverage report 与 automation tracker，删除已关闭 gap，保留真实剩余边界。
4. 对齐父 overview 与所有已完成父 Task 0、1A、1B、2A、2B、3A、3B、4A、4B、5A、5B 的正文 checkbox；每一勾选都必须能引用对应 child plan、验证报告、提交和测试证据，不能只因 overview 已勾而回填。随后对齐 Task 6 正文与 child overview；全部完成后才勾父 Task 6，并将 `active-child-plan` 设为 `none`。
5. 运行：
   - `bash scripts/comet-project-guard.sh plan docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`
   - `bash scripts/comet-project-guard.sh plan docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md`
   - `git diff --check`

## 任务交付物与过程产物

任务交付物：

- 64/64 terminal manifest 与可执行 final gate；
- 清晰分层的 fixed original / current Android / shared-or-adapter / Desktop evidence；
- 删除或有证据保留的兼容层、重复规则与平台豁免；
- 可维护的 comparison、coverage、automation、tracker 和父路线图状态；
- Windows/macOS 当前提交的真实构建与运行验收。

过程产物仅限：

- 本 child plan；
- 必要时由 Tasks 14–16D 产生的有限、按上下文簇拆分的产品 child plan；
- 一份最终验证报告。不得创建逐项快照、第二份 manifest 或巨型 diff 包。
