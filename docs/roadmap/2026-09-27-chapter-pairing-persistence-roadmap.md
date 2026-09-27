# 章节双页调整持久化 Roadmap

日期：2026-09-27。状态：实施中；CP-01/02 已完成，CP-03 待实施。本文最初作为纯规划交付，实施阶段按下述批次推进。

需求权威：[按章节持久保存手动双页调整](../2026-09-27-chapter-pairing-persistence-requirements.md)。本计划为独立产品 child plan，进度从第一个未勾选批次推导，不声明 `active-task`，不切换其他父计划正在执行的 `active-child-plan`。勾选代表实现、独立审查、验证与提交全部完成；若纳入父 roadmap，只链接本计划并保留唯一 active child。若涉及 capability 登记，只更新现有 manifest 的对应项与真实证据，不新建平行状态权威。

## 1. 顺序、职责与预算

依赖：**CP-01 → CP-02 → CP-03**。前两项是各自可演示的完整平台功能批次，不能按 SQL 文件、测试类或 UI 文件拆成不通生产链的小任务。

- 获得实施指令后，主代理先冻结本文接口/验收，将 CP-01 交实施子代理，复用该代理顺序执行 CP-02 与必要修复；主代理负责独立核对存储迁移、竞态、生产接线及集成收口。默认 1 个实施子代理，无并行重型验证；纯规划阶段无需委派。
- 独立审查 1 轮，按两个稳定里程碑分段交付：CP-01 的迁移/接口必须先通过主代理独立检查，再由同一实施者推进 CP-02；第二段只检查 Android 增量及跨端契约。修复复审最多 1 轮，聚焦原阻塞。
- 每个行为先 focused 红→最小实现绿→重构后 focused 绿。每批次末运行相关单元、文件库集成、DI/UI wiring 与格式检查；一次最终全量 Android/Desktop 测试，不在小步骤运行完整 Desktop 或 `finalParityAudit`。
- 同 worktree 所有重型 Gradle 由当前批次唯一执行者协调，通过 `scripts/gradle-coordinator.py` 串行执行；长任务先报告命令、预计时间与日志。超时先查状态，不重复启动。
- 预计实施及 focused 验证 1–2 工作日；全量、双端 Desktop 构建与运行 0.5–1 工作日，取决于本机和 macOS 可用性。这是规划阶段估算，不是运行承诺。主要成本是文件数据库迁移、两端生命周期竞态和正式运行验收。
- 过程文档复用本计划，最多一份最终证据报告；不创建逐任务快照。平台环境不可用时保留真实阻塞，继续不依赖它的工作，最终对应项不勾选；新增同步/备份、页面身份体系、临时文件持久身份等扩展必须另行确认范围与成本。

## 2. 功能批次

- [x] **CP-01：共享持久化 + Desktop 完整恢复链。**
  - 前置/入口：阅读需求全文、`ReaderPairingAdjustment`、`ReaderScreenModel`、`DesktopReaderRuntimeFactory`、SQLDelight database factory 与现有迁移测试；实施前重新核对 HEAD 和用户未提交改动。
  - 范围：在 domain 定义章级记录、验证和串行读写契约，在 data 实现事务与增量迁移，通过现有 Injekt 注入 Desktop runtime；现有调整按钮走保存成功后应用，所有开章入口统一在首次双页呈现前恢复。落实 CP1–CP7、无真实章节身份的会话限定提示、版本/页数失效和失败重试。不改进度字段、不扩同步或备份，不重写配对算法。
  - 红测：以真实文件库和 production Use Case 写“调整→事务→关闭库→新 runtime/Model→恢复”失败测试；真实 Compose 点击断开时必须失败。补齐 A1–A3、A6–A10，证明旧实现因无持久恢复而失败，而非未解析符号；新增模块可先编译最小接口后制造行为红测。
  - 绿与重构：最小存储/接线实现；再清理旧会话写入路径，保留单一持久权威。覆盖延迟读取、快速退出、事务失败、章切换、多会话冲突与合法空集合，测试使用受控调度避免靠 sleep 碰运气。
  - 验证/交付：相关共享契约、SQLDelight 迁移与真实数据库重开、Desktop UI/DI/reader 集成和格式检查；主代理独立检查事务原子性、删除级联、不触发阅读/同步副作用与 stale result 防护。提供完成项、变更、红绿命令/结果、风险及结构化回执。独立检查通过才开放 CP-02；测试、实现、必要架构说明和本批次勾选合入一个提交。
  - 边界：不新增独立设置页或全章重置按钮；数据库表/接口命名可沿仓库调整，需求中的持久承诺不能降级。涉及多个模块超过估算文件/行数时记录内聚性和风险，不机械拆分。

- [x] **CP-02：Android 同契约接入与双端行为收口。**
  - 前置：CP-01 共享接口、迁移和存储关键路径已通过独立检查；复用原实施者和已有 diff/测试证据，不重新发明存储。
  - 范围：`ReaderViewModel`、`DualPagePairingStore`、`PairingState`、现有 RTL 双页 viewer/调整入口及 Android DI。将对象身份缓存保留为会话优化，按稳定章节 ID 恢复与保存；覆盖 Activity/进程重建和相邻章节加载。补齐平台失败/进行中语义，沿用已有控件，不新增 Android LTR 模式。
  - 红测：Android 实际调整事件→生产 shared repository→新 ViewModel/viewer 恢复的集成测试；复用 CP-01 共享契约数据，不复制算法。覆盖 A1–A10 中适用项、Android RTL 末页已读、宽图与生命周期；Desktop A4/A5 作为受影响回归。
  - 绿与重构：最小 adapter 接线，清除会抹掉已恢复手动边界的旧初始化路径；对 decode/viewport replacement 与首次视口报告执行实际事件。纯重排不得上报新阅读效果。
  - 验证/交付：Android focused JVM/适用 UI 集成、共享契约双平台接线、Desktop 受影响回归和格式检查；主代理独立检查 Android 增量与跨端一致性。提交包含测试、实现、`docs/architecture/reader-shared-core.md` 对权威/边界/故障处理的必要更新与批次勾选。不得把共享纯测试通过称为 Android 入口通过。

- [ ] **CP-03：正式构建、运行与统一交付。**
  - 前置：CP-01/02 行为测试通过、审查阻塞已关闭，冻结本次产品 diff；不增加功能。
  - 验证：以仓库当前任务名执行一次完整 Android 单元/Desktop 测试及必要格式检查；使用协调器串行运行。先核验本机 Android SDK 三个必需文件，按现有构建流程生成 Android 产物。Desktop 必须使用 `scripts/build-desktop.sh`，禁止直接 Gradle 部署；仅在同一未提交 diff 已有等价完整 Desktop JVM 证据时使用 `build-only`，否则安排脚本承担唯一全量 Desktop 验证，避免重复。
  - 运行：Windows 和 macOS 正式产物上执行 Test Mode 及实际 production 持久链；隔离测试数据库，执行 A1 的退出/重启恢复、A2 章间隔离、A3 清除及 A4 模式切换。Android 正式产物完成同类恢复验收；无设备/主机时记录具体未验证项，不用系统 JVM、HTML 或独立客户端替代。视觉证据遵守仓库离屏限制，不从 Test Mode 读取桌面像素。
  - 交付：一份简明证据报告记录需求 ID→测试/命令→环境/结果→产物；Windows 链接必须取构建日志 `Final unpacked EXE:` 的实际路径并核验存在，不能交付 tmp/build 目录。统一按仓库功能/修复/验收格式报告，明确仅本机持久、临时文件和同页数内容变动限制。
  - 提交策略：最终报告和本项 checkoff 随正式构建实际产生的版本/发布相关变更合并提交，不另建纯 close/advance 提交。若没有可合并的发布变更，将这份最终证据纳入最后功能批次的单一最终提交，提前安排其提交时点；不得未经授权改写已经共享的提交。失败则保持未勾选并记录阻塞。

## 3. 回执与验收约束

实施者完成/阻塞先返回 `status`、`diff`、`tests`、`commit`、`process`、`next`，可用 `scripts/agent-handoff.py` 验证。主代理不重复执行仍适用且可核验的测试；有相关改动或证据不足时只补受影响范围。任何必做验收不得在实现后改成不适用；一轮复审仍失败时，列出未通过项、实际证据、未解决原因与下一步新增成本，再请求扩大预算。

当前验证记录：CP-01 已完成实现、独立审查、focused 验证并随本次勾选提交。`cp01-final-suite` 通过 `spotlessCheck`、domain 1/1、真实 SQLite data 23/23、Desktop 97/97；Desktop Compose 用例执行实际调整按钮、文件数据库重开与新 Screen 的 `[5,6]` 显示单元。数据存储及 Compose 用例曾先绿后补做破坏行为红测，其余竞态/边界修复按红绿重构执行，不能把补红描述为先红。Compose 用例采用测试侧失败占位解码与单并发 scene dispatcher，验证配对链但不验证真实图像呈现；旧图像链在组合测试中曾出现锁顺序死锁，正式运行留待 CP-03 验收。Android legacy migration focused 因本机缺少 sqlite3x JNI 在测试 setup 阶段失败，未到版本断言；共享文件库迁移测试已通过。

CP-02 已完成 Android 实际调整按钮→生产文件库→新 ViewModel/viewer 恢复，读失败的重试/本次默认、保存中/失败反馈、相邻章预取与 RTL 过渡页恢复、宽图回调、末页进度及旧 ViewModel 销毁后应用级写入排序。主链和错误反馈分别确认行为红测，临时断开 Activity 按钮生产接线时主链测试失败；过渡页修复经实际 pager 事件红测与复审。`cp02-android-related-final2` 为 Android 相关 10 类 57/57，`cp02-data-final` 为共享 SQLite 6/6，`cp02-desktop-related-final` 为 Desktop 4 类 30/30；复审修复后 `cp02-transition-related-final2` 再验 Android 配对链 16/16 与相邻 viewer 6/6，`cp02-spotless-final` 通过格式检查。前一次组合回归因测试先挂 viewer 而与近末页自动预取重叠，手工预取尚未完成便断言；夹具改为先执行生产预取再挂 viewer，继续验证 adapter 首次显示与进度。Android 正常返回/重建时已受理写入由应用级队列继续执行；系统结束进程前未提交的写入只保证上次成功记录，没有进程退出排空 hook。正式 Android/Desktop 产物与运行验收留待 CP-03。
