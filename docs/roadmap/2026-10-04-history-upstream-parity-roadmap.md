---
status: in_progress
date: 2026-10-04
---

# 历史页上游行为对齐与双端共享 roadmap

## 1. 目标、授权与状态

规格唯一入口：[历史页上游行为对齐与双端共享设计](../2026-10-04-history-upstream-parity-design.md)。目标是在本仓库 Android/Desktop 共用一套历史业务和状态，Desktop 按官方 Mihon 提供完整搜索、详情、续读、收藏、删除与反馈；历史页不再检查源目录。保留同步续读和目录安全补全，通过独立于当前章加载的 Reader 扩展承接。

官方基线为 `mihonapp/mihon@4c88f02646aa1a358611e5b3b37ef7a62909b8d9`；本地规划基线为 `92f1617fd3350a8c98eda2f4e2830551dfb5b64f`。下面验收固定为设计 P01–P18，不能在实现后因缺少功能删减。SOURCE、PROJECT_POLICY 和平台适配边界以设计为准。

2026-10-04 规划轮仅获授权编写设计与 roadmap；随后用户明确要求实现本计划，实施预算现已生效。执行基线为 `10758b91ec`，分支为 `codex/history-reader-context-repair`，worktree 为 `D:/Codex/worktrees/f235/mihon`。HP01 随本批代码/测试/证据提交完成，当前第一个未勾选项为 HP02；checkbox 仍仅表示实现、独立审查、验证及提交全部完成，不声明 active-task。父 roadmap 的唯一指针仍指向作者专项，本计划沿用前序历史专项的独立执行归属，不覆盖该指针或作者专项状态。真实结果集中记录在[聚合证据](../evidence/history-upstream-parity-2026-10-04.md)。

本计划取代[旧历史修复 roadmap](2026-10-01-history-reader-chapter-context-repair-roadmap.md)后续交付要求。旧 HR01 的有效实现/证据保留，HR02 未完成状态保留；旧追加测试批准不能自动当作本计划的重复测试额度。本计划的实施预算在用户要求执行本计划后生效。

## 2. 执行预算与功能批次

历史状态、操作入口、续读、同步上下文和关联弹窗共同交付一个完整历史页能力。采用 HP01 一个完整功能批次、HP02 最终验收；HP01 内 A–E 是按依赖推进的测试/实现范围，不是独立勾选或纯基础设施提交。这样可以在一次整体独立审查内核对两端是否真正消费同一核心。

| 项目 | 预算和限制 |
|---|---|
| 本次文档轮 | 主代理直接执行，0 子代理；设计/roadmap 各 1 份，旧两份文档仅加替代说明；一次只读 UTF-8/链接/内容/diff 核验后提交；不运行产品测试/构建 |
| 技能 | 无必需专项技能；依据现有 TDD、UI、Android 构建与 macOS 验收规范，不引入插件、HTML 原型或生成器 |
| 后续实施者 | 1 名实施子代理承担 HP01 主要代码和 focused 验证；优先复用可用且上下文清晰的原实施者，否则新建 1 名；主代理负责规划、接口核对、整合与独立验收 |
| 并行 | A→B→C→D→E 串行，共享代码/Gradle 不并发写入或运行。实施者工作时主代理只读核对来源、脚本、环境和证据，这是唯一真实并行项 |
| 审查 | 主代理未实施目标代码，独立审查 1 轮；高风险共享接口在本轮审查中先核对再让下游依赖，完整批次稳定后完成剩余审查。修复复审全计划最多 1 轮，聚焦具体失败项 |
| focused TDD | A/B/C/D 各一组红→绿→重构，只跑当前受影响类；E 一次批次集成回归+格式检查。新增失败用例纳入对应组，必要修复只复跑受影响范围，不用模块完整测试代替 |
| 最终全量 | HP01 实现、审查、相关验证、提交完成后，HP02 执行一次完整矩阵；Windows/Mac 不各重复一遍等价 Desktop full。再次 full 必须说明具体失败与成本并获批准 |
| 正式产物 | 最终 Windows 未打包 EXE、macOS .app、Android 候选各 1 次；当前源码已有等价 Desktop full 时用 build-only，避免脚本隐式重跑。安装实体 Android 设备须有单独授权 |
| 交付物 | 产品代码/共享契约/平台测试、必要文档、正式候选；最终报告聚合一份，不建逐步快照或巨型 diff 包 |
| 过程产物 | 沿用 Gradle 协调器和构建脚本日志；证据汇总至建议路径 `docs/evidence/history-upstream-parity-2026-10-04.md`，实施时建立，本轮不建空报告 |
| Git | HP01 原则一个含测试、实现、必要文档及 checkoff 的提交；审查修复最多追加一个。HP02 版本、运行证据与 checkoff 同批提交，不产生纯状态推进提交 |
| 预计实施墙钟 | HP01 12–20 小时；独立审查及必要修复 2–4 小时；HP02 2–4 小时；合计 16–28 小时，不含用户值守等待，缓存/平台不可用可能增加时间 |
| 主要成本 | 双端共享 UI 接线、收藏关联流程、Reader 会话安全、文件 SQLite/HTTP/Compose 集成及最终三平台运行；不需要 Docker、付费服务或真实远端账号写入 |

预计 HP01 涉及 25–40 个文件、超过 400 行，主要分布在 domain/data、窄共享 UI 模块及两个平台 history/reader adapter、测试和文档。内聚性来自同一历史能力的规则/入口/副作用必须一起闭环，不因行数拆出不可独立验收的 helper。实施者发现需要额外独立用户能力、schema/同步协议修改、全面 Reader 或追踪重构时，先提交具体缺口与最小替代，不直接扩大实施。

实施中已超过文件数量估算：双端共享契约需要 JVM/Android 发现 wrapper、新共享 UI 模块及真实文件库/HTTP/页面集成测试；原子 Reader 打开还要求校准已有 Reader 夹具的实际作品/章节身份与页面发布。增加的文件仍服务同一历史能力，保留页码、配对、书签、隐私和已接受写入的断言，没有独立产品能力、schema 或同步协议扩张。风险集中在双端接线、异步资格与既有 Reader 消费者回归，按本批 focused 验证和同一轮独立审查处理；不为控制文件数删除 wrapper/集成证据或拆出不能独立验收的提交。最终提交前在聚合证据记录实际规模。

独立审查不是每个测试类一轮；主代理对高风险接口的提前核对属于这唯一轮次的同一审查范围，稳定后只补未审内容和真实修改差异。出现架构假设失效或需要重开已通过审查时，按追加规则说明原因，不能把多轮复审换名称规避预算。

## 3. HP01：共享历史功能、双端接入与 Reader 边界收敛

- [x] **HP01 完成：P01–P18 的产品实现、双端共享契约和真实集成测试通过；独立审查通过、必要文档及提交完成。正式候选和现场验收由 HP02 完成。**

### 启动前置与委派

1. 用户已要求执行本计划；核对 HEAD、分支、未提交改动、原实施者状态及协调器进程。规划时已有 `AppVersion.kt`、`MACOS_ACCEPTANCE.md`、旧证据报告改动，保持所有权，不自动清理或混入提交。
2. 读取设计与固定官方来源，核对当前 app/domain/data/presentation 边界。只重查发生变化的代码；不把 fork Android 的同步选章当作官方规则，不重新追踪浮动 upstream/main。
3. 声明实际测试类、目标模块、共用接口和写入边界；新增测试名在本计划与报告中登记为真实名称。共享 UI 模块只用于 history，不升级框架版本。
4. 主代理先将本节 A–E 交给实施子代理，不提前自行实现。实施者交付目标、diff、红绿证据、剩余风险和进程信息；复用同一代理处理后续修复。
5. 主代理与实施者冻结三个窄接口：共享历史状态/事件、已选目标的 Reader 打开上下文、Reader 后台目录补全。状态中没有历史源预检，副作用由实际平台 adapter 接线；涉及持久化/因果快照的接口先核对后进入 D。

### A. 共享状态、搜索与原版列表（P01/P02/P16/P18 的相关部分）

**用户结果**：两端看到相同含义的历史列表；搜索连续输入、编辑和清空正确，数据库变化自动反映到页面。

**修改边界**：`domain/commonMain` history controller/state/events；新 `presentation-history` 的必要模块配置/列表/搜索；Android 与 Desktop history 包装、相关 DI。B/C 尚未替换的动作须通过明确 adapter 保持可用，不能留空回调；A 不单独宣称完成整个历史能力。

**红**：共享契约覆盖 nullable 查询、输入立即更新、旧查询晚到、持续订阅、日期分组和三类列表状态；Desktop 真实 Compose 输入覆盖 `abc`、中间插入、选区替换与清空，加入可控数据库闸门，确认旧实现因文本/结果错误失败。Android wrapper 驱动同一共享契约，至少一项通过真实 history DI/页面挂载证明接线生效。

**绿→重构**：迁移原版语义至纯 Kotlin 核心；用唯一 `flatMapLatest` 查询链替换 Desktop `.first()`/双重加载；两端改用共享内容和控件，删除被替代的列表/分组规则。即时输入与查询结果分别更新，不能通过强制光标到末尾掩盖编辑问题。清理后复验同组。

**退出条件**：P01/P02 自动化通过；共享页面能被两端实际挂载；查询取消/生命周期无泄漏；明确 A 中对 B/C 的临时 adapter，E 前全部收敛。无全模块测试、版本递增或正式构建。

### B. 原版操作、删除与本地 Reader 入口（P03–P07/P11/P12/P17/P18）

依赖 A。**用户结果**：封面进详情、正文续读、再次选历史续读最近记录；删除按原版确认；有本地目标就进入 Reader。

**修改边界**：共享历史动作/删除对话框，Android HistoryTab/Reader 入口适配，Desktop HistoryTab/HomeScreen 的重选回调、HistoryScreenModelFactory/HistoryReaderEntry 与详情 Reader mapper；复用 domain 用例和 data 历史仓库，默认不改变其存储错误策略。

**红**：先执行真实点击与导航宿主测试，覆盖原版选章所有分支、封面/正文不冒泡、标签首次/重复点击、当前搜索不限制全局最近记录、两种删除范围及取消、清空失败。目录服务设为可观测的失败/永久等待 port，断言普通历史打开根本不调用它，Reader 页面/下载链仍可运行；详情指定已读章不变成下一章。故意破坏实际 Reader refs/导航注入应使测试失败。

**绿→重构**：调用唯一 `GetNextChapters` 选章；撤出历史对目录服务、源检查和下载管理的依赖；将本地上下文组装收敛到历史/详情共用的 Reader adapter。删除旧行播放与加载/失败/降级状态，按原版条目动作接线；清空结果由真实仓库反馈。两端普通导航维持现有类型，Desktop 保持嵌套 Navigator。

**验证边界**：完整本机目录正常切前后章，普通历史目录请求数为 0，离线或源缺失不会在历史拦截可识别目标；无本地下一章仍显示上游反馈。同步的页码、快照及稀疏补全由 D 完成，不能用临时取消同步功能让 B 通过后收口。

### C. 收藏、分类、重复作品与追踪（P08–P10/P18）

依赖 A/B。**用户结果**：未收藏历史作品可加入书架，并有与原版一致的重复作品、分类、迁移和增强追踪行为。

**修改边界**：共享 history 收藏编排和对话框语义、`UpdateLibraryMembership` 调用、两端既有分类/重复作品/迁移/追踪 adapter 及必要 DI。只补历史入口，不重构所有页面的收藏或整个迁移/追踪模块。

**红**：真实 UI 点击未收藏按钮，分别进入无分类/默认分类/每次询问/重复作品；覆盖取消、仍加入、打开已有、迁移确认、分类管理返回。真实文件数据库验证收藏+分类原子性、错误时无半写、重复点击/outbox；通过现有平台增强追踪生产 workflow 的隔离 HTTP fixture 验证匹配、失败和官方触发时机。不能 mock 掉整个迁移或追踪调用后只断言按钮存在。

**绿→重构**：提取共有决策并接既有服务，沿用当前/目标身份及原确认流程。Android/ Desktop 都调用共同写入用例；增强追踪的框架/源/认证差异限制在 port，保留设计中选择分类前后时序边界。清理重复判断并复验同组。

**退出条件**：所有可见动作有真实业务结果或原有失败反馈；取消不提交收藏/分类；上游已触发的追踪不被误写成取消保证。未实现的 Desktop adapter 不能以“平台差异”跳过。

### D. 同步上下文与非阻塞目录补全（P13–P15，回归 P04/P11/P12）

依赖 B/C 的稳定接口。**用户结果**：同步后的未完成阅读仍能恢复页码；历史按原版续读已读章；稀疏目录不妨碍先读当前章，补齐后正常切邻章。

**修改边界**：共享 Reader 打开上下文、现有阅读进度/快照窄接口、两端 Reader 会话接入、可共享非删除目录核心及平台 fetch adapter；沿用已有归档观测、刷新 owner、SQLDelight 事务、下载识别。普通 Android 详情更新/下载更名删除链不重写，不新增 schema 或同步协议。

**接口验收顺序**：先完成共享快照读取/非删除合并的实际实现和定向红绿证据，交主代理独立核对身份、事务、因果基线与错误传播；通过后再接入两端 Reader 异步消费者。这属于预算中的唯一轮次审查，E 补齐其余稳定代码和新增差异，不能仅审设计签名代替数据安全代码审查。

**红**：两个独立文件 SQLite 经真实 journal/inbox/projection 形成仅中间未读章的接收端；延迟目录响应，先断言 Reader 已挂载且当前页面能加载/翻页，再放行三章目录并操作真实前后章。补充：已读章转下一章不带旧页/旧 snapshot；原版无下一章不偷偷重读；阅读中新同步/目录结果不跳页；关闭/换作品后迟到结果无效。

**故障与持久性测试**：普通无同步上下文阅读的目录调用为 0；完整/合法单章不重复取；不完整同步上下文每会话至多一次，同作品/详情请求合并；覆盖真实 parser 的成功、空/缺失、403/429/500、畸形体与超时。保留原文件库 H09/H11 和 source-work 自然键测试，注入真实 SQL 失败验证原子回滚及稳定 ID、历史水位、下载、页码/书签、非零首取时间。

**绿→重构**：去掉两端历史的同步目标覆盖，所选同章才恢复对应页/snapshot；其他目标复用合法新会话基线。Reader 挂载后启动设计限定的后台准备，成功只更新邻接上下文；页面加载不等待准备，目录失败不污染页面错误。抽取现有非删除合并核心供两端调用，沿用生产事务与 source adapter，不把 Android 含删除副作用的完整刷新类用于后台准备。

**退出条件**：P13–P15 有双端共享契约和各平台 production 消费证据；两端行为一致；旧目录安全测试仍适用的继续通过。不能把“从详情先打开一次”作为所有同步稀疏未读场景的验收前提。已读且本地没有下一章的例外按设计明确验收。

### E. 集成、独立审查与本批提交

1. 一次受影响回归集合覆盖 A–D、Screen 实例化、导航类型、DI、真实文件数据库/HTTP/Compose 与原作者等仅存在明确调用影响的测试；不顺手扩大到无关模块修复。
2. 更新 Test Mode `history_select`，确保调用实际共享 controller/Reader 工厂；删去历史专属重试/降级产品路由并更新 API/客户端契约。只读观测来自 mounted Reader session，禁用合成 hasNext/hasPrev 冒充实际切章。
3. 执行受影响模块格式检查。保留 P01–P18 到真实测试、来源、红/绿结果的映射；只创建一份聚合报告，不把测试计划写成已通过证据。
4. 主代理完成唯一轮次独立审查，核对共享复用、官方分支、同步快照、事务/生命周期、真实接线及旧契约替代。阻塞交原实施者修复，最多一轮限定复审；无法在预算内解决时说明具体失败、上一轮未解决原因和追加成本。
5. 若维护既有 history capability/HI-01 证据，只局部更新 manifest；不把整个非 Reader 历史计划批量标为完成，不运行 `finalParityAudit`。未获得正式运行证据的状态保持真实。
6. 审查与验证完成后，仅提交本批相关文件和 HP01 checkoff。完成回执使用 `status/diff/tests/commit/process/next`，可用现有 `scripts/agent-handoff.py` 核验；HP02 仍未勾选。

## 4. HP02：最终矩阵、正式候选和原生验收

- [ ] **HP02 完成：HP01 已提交；一次最终完整验证矩阵、三平台候选与实际运行、聚合证据和最终提交齐全。**

### 前置与一次完整验证矩阵

只有 HP01 实施、审查、相关验证与提交全部完成才进入。先核对源码/未提交产品 diff、测试 target、协调器状态、候选隔离目录及本次日志/PID，再执行。目录或接口代码在 full 后变化会使相应证据失效；不能因旧版本曾通过而省略本次最终矩阵。

| 范围 | 一次最终执行与边界 |
|---|---|
| Desktop JVM 完整含集成 | `bash scripts/build-desktop.sh full-tests`；执行前核对仍传 `includeIntegrationTests=true`，不提前由普通构建隐式执行 |
| domain/data 完整 | `:domain:jvmTest :domain:testDebugUnitTest :data:jvmTest :data:testDebugUnitTest`，两端共享契约必须实际被发现与运行 |
| 共享历史 UI 完整 | 新模块 `:presentation-history:jvmTest` 与 `:presentation-history:testDebugUnitTest`；配置需包含对应 wrapper/依赖，不能 0 测试当通过 |
| Android App/E2E 客户端/格式 | `:app:testReleaseUnitTest :test-desktop:test spotlessCheck`，同属一次最终矩阵 |
| Windows/macOS 产物 | 等价完整 Desktop 证据覆盖当前产品代码时分别用 `scripts/build-desktop.sh build-only`；仍执行版本分配、正式构建、production runtime 与发布路径校验 |
| Mac 平台差异 | 受影响输入/平台 adapter 的 focused 测试与正式运行各一次；不重复等价全量，不能以 Windows 输入测试替代 Mac 原生证据 |
| Android 候选 | `python scripts/build-android.py check --signing` 后走 `candidate` 和 `verify`；不直接交付 Gradle outputs。签名不可用时按规范记录真实阻塞，不创建替代密钥 |

表中 Android variant/task 名为当前模块模式下的计划目标，新模块实现后先核对实际 task 与测试发现结果，按真实 target 修正命令但不删验证范围。完整矩阵可以由若干串行命令组成，每个目标只运行一次；全量失败保留证据，先 focused 修复，重复完整目标前申请追加。macOS 构建目录/部署目标须指向本轮隔离候选，避免默认覆盖用户日常应用。

### 正式运行和可执行用户清单

使用隔离 profile 与虚构夹具；核对本轮产物真实路径、版本、进程、端口和配置。macOS 先读 [MACOS_ACCEPTANCE](../automation/MACOS_ACCEPTANCE.md)，区别息屏与锁屏；有冲突时保留事实并等待现场可见性确认，不反复把息屏说成锁屏。

以下每项在聚合报告登记 Windows/macOS/Android 各自结果，跨平台不能合并成一个无来源的勾选。Android 安装与实体操作依已有明确授权，用户选择自行验收时提供候选和清单，不代操作；可用的已授权隔离模拟器也必须记录版本与环境。

| 操作 | 预期结果/主要契约 |
|---|---|
| 历史 → 搜索 → 输入 `abc` → 将光标放中间输入 → 清空 → 关闭搜索 | 文本顺序/选区正确，两类空状态正确；Windows/Mac 用真实原生键盘，Android 用实际输入法；P01/P02 |
| 点击封面 → 详情 → 返回；点击正文 | 封面只进详情，正文按未读/已读规则续读；查询与位置恢复；P03/P04 |
| 已在历史 → 再次点击历史标签 | 续读全局最近记录，搜索不改变对象；P05 |
| 删除 → 默认确认；另一作品删除 → 勾选全部；清空 → 取消 | 各范围正确、取消无写入，真实失败主要靠自动化；P06/P07 |
| 未收藏 → 加入 → 选择分类；重复作品 → 打开已有/迁移确认后取消 | 原版分支可达、对象正确、无空回调；P08/P09，追踪失败矩阵由隔离自动化验证 P10 |
| 目录端点不可用但当前页面/下载可读 → 历史续读 | 直接进入 Reader 阅读；历史不出现加载章节/源不可用/降级选择；P11/P12 |
| 隔离同步未读中间章 → 历史续读 → 延迟目录后放行 → 前后章 | 先可读当前章，后邻章可用，当前页不变；P13/P14/P15 |
| 弹窗用 Tab/Shift+Tab/Escape；返回历史；切主题、窄窗/大字体 | 焦点、层级与局部状态正确；少量原生/离屏视觉证据注明来源；P16/P17 |
| 正式 Test Mode `history_select` → Reader 真实动作与返回 | production DI、目标、refs、进度和 session 与原生入口相同；P18 |

Test Mode HTTP 只能补充链路和状态断言，不能代替原生搜索、IME、焦点和返回。没有截图 API，不读取桌面像素；视觉使用允许的离屏测试或平台外部工具。平台不可用或用户尚未反馈，记录具体未验项，不能写“全部一致”。

### 收口

- 聚合报告记录官方/本地基线、P01–P18 证据、测试数量与失败/跳过、独立审查、候选来源和剩余限制；旧报告只作为仍适用证据链接，不复制旧通过结论。
- Windows 完成报告只能引用构建日志 `Final unpacked EXE:` 的实际绝对路径并确认存在；macOS 引用本轮实际 `.app`；Android 引用脚本正式候选及校验清单，均使用可点击链接。
- 必要版本、API/架构文档、manifest 的真实局部状态、最终证据与 HP02 checkoff 同批提交；保护其他任务修改。
- 最终按项目【功能特性】【BUG 修复】【验收清单】汇报，中文明确已修复的具体问题；只有通过的范围可宣称完成。

## 5. 命令约束、失败处理与交接

所有重型 Gradle 由一个协调者串行执行。外层超时先查已有状态/PID，仍为 STARTING/RUNNING 时继续等，不开第二个 Gradle。脚本本身已协调时不外层再嵌套；按实际脚本核对入口。

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
$env:HTTP_PROXY = 'http://127.0.0.1:10808'
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
$env:NO_PROXY = 'localhost,127.0.0.1,::1'

# focused 示例：只表示既有类；实施时补齐该范围实际新增的共享/平台测试类。
python scripts/gradle-coordinator.py run --key history-parity-entry -- .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.history.HistoryScreenModelTest" --tests "mihon.desktop.history.HistoryReaderComposeIntegrationTest" -PincludeIntegrationTests=true

# 只有 HP02 才可运行以下完整目标；当前构建脚本外部使用协调器。
python scripts/gradle-coordinator.py run --key history-parity-desktop-full -- 'C:\Program Files\Git\bin\bash.exe' scripts/build-desktop.sh full-tests
python scripts/gradle-coordinator.py run --key history-parity-shared-final -- .\gradlew.bat :domain:jvmTest :domain:testDebugUnitTest :data:jvmTest :data:testDebugUnitTest :presentation-history:jvmTest :presentation-history:testDebugUnitTest :app:testReleaseUnitTest :test-desktop:test spotlessCheck
python scripts/gradle-coordinator.py run --key history-parity-windows-build -- 'C:\Program Files\Git\bin\bash.exe' scripts/build-desktop.sh build-only

# Android 正式签名预检与候选；本命令不授权实体设备安装或操作。
python scripts/build-android.py check --signing
python scripts/build-android.py candidate
```

Windows 的脚本示例显式使用已核验的 Git Bash；本机 PATH 中的 `bash.exe` 是 WindowsApps 入口，不能当作 Windows 构建 shell。macOS 继续使用目标机器原生 bash。源网络请求遵循本机代理规则和有界重试，本地 MockWebServer/Test Mode 绕过代理。SDK 以真实 `android.jar`/`aapt2.exe`/`adb.exe` 与受影响 Android task 为准；不为普通 JVM 验证引入不必要 SDK 下载。

追加条件必须具体：新增代理/独立审查/再次 full/正式候选重建、schema 或协议变化、额外产品能力和显著成本，均先说明未通过项、原失败证据、上次未解决原因、拟范围/时间/替代方案，再等待决定。范围内只读诊断可以继续；不把“预算用完”当作原因，不在原因未知时归咎环境。

工具或平台不可用时保留已完成的代码、当前进程及证据，记录真实缺口；不能复用旧候选假装本轮运行。长期工具调用前报告命令、预计时间、PID/日志；实施者完成或阻塞交结构化回执，主代理不重复实现已委派部分。

本规划轮的完成条件仅是两份新文档与旧计划衔接准确、链接和 UTF-8/diff 核验通过并提交。它不创建产品完成证据、不触发上述命令、不递增版本。
