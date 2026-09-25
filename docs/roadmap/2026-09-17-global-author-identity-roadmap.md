# 跨插件唯一作者 · 正式实施 Roadmap

- 日期：2026-09-17；状态：COMPLETED；GA-01至GA-06已完成。代码、审查、自动化与正式桌面运行证据保留；最终Android真机验收由用户自行执行并明确报告完成。
- 类型：产品child plan；从第3节第一个未勾选批次推导进度，不再声明active-task。
- 父路线：[主Roadmap](2026-06-30-mihon-desktop-refactor-roadmap.md)。仅登记待执行专项，不改变父路线active-child-plan，不恢复旧作者归档专项及其无关backlog。
- 最终产品依据：[功能设计](../2026-09-17-global-author-functional-design.md)；[技术方案](../2026-09-17-creator-identity-reconciliation-proposal.md)；[已确认HTML](../prototypes/author-identity/index.html)，基线提交 `77a610534`。
- 本文件记录批次进度与提交/审查/验证证据；已有capability的机器状态仍以 `app-desktop/src/test/resources/parity/parity-manifest.json` 为准。capability状态仅在对应production验收证据成立后更新。

## 1. 目标、范围与交付门槛

漫画柜《平行天堂》及其他插件中的同文本署名始终进入一个作者页；不同名字通过添加别名归入当前作者，此后跨插件自动复用。交付包括共享持久化约束、旧库升级、双端交互、统一关注与全局调度、备份及现有同步兼容，并在Android真机与Windows/macOS正式产物验证。

精确姓名是身份判定依据，portable key是引用标识，来源ID只负责作品访问。不得采用UI去重掩盖底层多根，不引入作者网页取证、逐插件adapter、同名不同人例外、相似度自动合并或额外HTTP请求。

已确认交互不得改回：身份选择、合并预览、自由输入改名、每作者周期。作者列表无头像；主名无图标且使用作品标题样式；别名横排可换行；作品下方图源名本身为横向按钮，封面与标题顶部对齐。复用原有作者发现/通知/筛选/语言范围及Desktop能力，不能以精简DEMO为由删除已有产品能力。

本期不新增异名合并/主名偏好/周期的在线同步协议、可视化拆分管理页或部署同步服务。现有关注同步必须正确兼容本地合并；无法证明兼容时GA-05不得关闭。旧拆分/删除动作仍必须满足同名唯一不变量。全部批次验收前不向真实用户库发布中间迁移版。

## 2. 已核对的入口与复用约束

路径相对仓库根；以下是规划时读到的事实，开工时仅复核相关diff，不重做无关调查。

| 入口 | 当前事实与实施方向 |
|---|---|
| `domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/CreatorNameNormalizer.kt` | `tokenizeNames`提供原始token，`splitNames`按normalize去重；身份入口使用原始token精确去重，保留normalize供搜索 |
| `domain/src/commonMain/kotlin/tachiyomi/domain/creator/interactor/ManageCreatorIdentity.kt` | `resolve`按候选数返回Ambiguous，并暴露createDistinct/select；替换为统一精确解析，不让旧入口绕过约束 |
| `data/src/commonMain/sqldelight/tachiyomi/data/author_archive.sq` | 现有根、别名、关注、作品与重定向继续复用；新增全局精确名称索引、revision及必要迁移记录。旧watch含period_millis，必须停止用其决定周期 |
| `data/src/commonMain/kotlin/tachiyomi/data/creator/CreatorRepositoryImpl.kt` | 现有merge会迁移watch等关联；抽取可在同一外层事务复用的内核，不复制一套合并实现 |
| `domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/CreatorLibraryIndexer.kt`、`CreatorDiscoveryPlanning.kt` | 索引及发现同走名称注册；查询预算、分页、退避继续复用，搜索归一化不能决定人物归属 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/author/AuthorsTab.kt`、`app-desktop/src/main/kotlin/mihon/desktop/ui/authors/` | 复用既有导航/ScreenModel；共享命令和状态语义，平台只做视图与生命周期适配 |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt` | 当前身份Chip不同于冻结的原版署名样式；改入口与渲染时同步验证未收藏漫画链路 |
| `app/src/main/java/eu/kanade/tachiyomi/data/library/CreatorDiscoveryJob.kt`、`app-desktop/src/main/kotlin/mihon/desktop/domain/CreatorDiscoveryScheduler.kt` | 复用后台生命周期与任务锁，注入共享日历周期计算，不新建第二调度器 |
| `data/src/commonMain/kotlin/tachiyomi/data/backup/AuthorArchiveBackupContributor.kt` | 在既有贡献器增量保存精确名称与映射，格式版本按开工时最新值分配 |
| `data/src/commonMain/kotlin/mihon/data/sync/projection/SyncAuthorIdentity.kt`、`domain/src/commonMain/kotlin/mihon/domain/sync/SyncProtocol.kt` | 当前解析以portable key和MERGED链为核心，协议有FOLLOWING而无别名决策字段；补名称收敛/旧键投影兼容，不把displayName当异名合并指令 |

## 3. 批次清单与依赖

- [x] **GA-01：精确名称唯一内核、历史迁移与所有身份写入入口**（无前置）
- [x] **GA-02：添加别名、主名切换及双端最终交互**（依赖GA-01及其独立审查）
- [x] **GA-03：全局作者设置与真实发现调度**（依赖GA-02）
- [x] **GA-04：备份恢复与设置兼容**（依赖GA-01至GA-03）
- [x] **GA-05：现有作者关注同步兼容**（依赖GA-01、GA-02、GA-04稳定契约）
- [x] **GA-06：双端集成、迁移回归与正式发布验收**（依赖GA-01至GA-05）

默认串行，原因是共享repository、schema、身份映射和测试fixtures存在写入冲突。Android/Desktop作为每个用户能力的共同交付面，不拆成两个语义分叉项目。每批原则上一份包含实现、测试和必要证据的提交；超过8文件/400行时说明内聚性与风险，不拆成不可独立验收的schema/模型/视图微任务。

只有实现、独立审查（适用时）、验证、提交全部完成才勾选。中间绿灯不代表批次完成。完整最终报告只在GA-06统一给出；批次之间记录简短证据与下一依赖。

## 4. 详细任务与验收

### GA-01 · 唯一名称内核及自动升级

**用户结果**：升级或打开新来源漫画后，同文本作者始终直达同一档案，历史三个冈本伦自动归一，无需选择或整理。

**实施内容**：
1. 在既有SQLDelight schema增加BINARY精确名称注册表、identity_revision及迁移检查点/恢复记录。主名和已接受原始别名均入表，不能把搜索标准化变体当接受别名。
2. 提供共享精确resolve/observe入口与外层事务可调用的合并内核；注册、关系绑定、角色并集、关注迁移和旧键重定向原子完成。并发唯一约束冲突回滚本次创建并读取胜出者，无孤立Creator。
3. 将原始名称交集构造成连通分量，稳定选择根并分组迁移；可中断重试且幂等，环/缺失引用拒绝该组，迁移中作者入口显示准备/重试而非歧义选择。恢复记录与迁移写入同一成功边界。
4. 接入ManageCreatorIdentity、漫画详情含未收藏漫画、索引/重建、元数据更新、发现和现有手动写入口。createDistinct、按漫画拆分、旧alias表写入不得重建同名多根；所有直接写入点列入接线检查。备份/同步专门语义留GA-04/05，但必须经过同一存储约束。
5. 保留原始署名、作品自然键和多角色；未知名称创建，空白不创建，改署名重新解析不凭站内ID自动合并异名。旧“同名不同人”记录留历史但不再阻断。

**红灯与验证**：扩展真实SQL repository、迁移fixture和 `MangaRepositoryCreatorIndexIntegrationTest`；共享契约覆盖三根历史、名称传递交集、跨源缺ID、大小写/繁简/标点、多token多角色、重复索引、并发创建及事务注入失败。重启恢复后检查关系总量、旧键、关注及无孤立记录。Android/Desktop真实resolve接线损坏必须导致测试失败。

**完成条件**：功能设计A1/A2全部通过；schema与事务独立审查通过后才允许GA-02依赖。预计12–18有效工程小时，主要成本是迁移fixture、旧写入口审计与并发回滚验证。本批可能超过估算行数，但唯一约束与全部写入接线必须保持为同一可验收闭环。

**实施证据（2026-09-17，GA-01验收通过；随本批提交完成，不代表正式发布）**：
- 初始红灯：`.gradle-coordinator/ga01-red.log`中4项数据契约按预期失败；`ga01-domain-red.log`中2项精确分词契约按预期失败。后续focused及双端既有wiring曾通过，但不能覆盖独立审查发现的缺口。
- 第1轮独立审查：GPT-6 Astra medium判定不通过。阻塞包括重复全库扫描及缺少分组恢复资料、备份/遗留写入归根未迁移完整关系、发现仍用搜索归一化、角色并集/原始别名保留、旧根导航、双端真实resolve与准备/重试反馈、revision写入覆盖。
- 模块验证`ga01-modules`经协调器停止，结果CANCELLED/130，不算完整测试通过；修复阶段只做受影响focused、wiring及格式验证，不重复该全量。
- 修复复审：GPT-6 Astra medium仍判定不通过。未关闭：迁移前完整作品/角色/关注恢复图；legacy多个已占用名称的传递合并；旧根作品订阅重定向；双端真实未收藏漫画SQL解析接线；重复resolve/bind的revision幂等性；区分合并角色并集与元数据角色替换。一次readiness/每组事务、发现精确匹配、原始变体及标点、单次旧根读取等已有改进，但不能代替整个批次验收。原规模性能验证未完成，逐mention SQL的吞吐仍须定向核验。
- 独立补验：`python scripts/gradle-coordinator.py run --key ga01-acceptance-sync-format -- .\gradlew.bat :data:jvmTest --tests mihon.data.sync.JvmSyncRemoteProjectionContractTest :app:spotlessCheck :data:spotlessCheck :domain:spotlessCheck`，PASSED/exit0/20s；日志`.gradle-coordinator/ga01-acceptance-sync-format.log`。此前`ga01-review-data-recheck`的抽象契约过滤没有执行同步case，不算同步证据；`format5`仅为Apply，格式Check由此处补齐。
- 已使用1轮审查及1轮修复复审；用户随后批准追加约2–3.5小时，限定修复上述6项、补定向性能证据及1次独立审查。用户同时撤销原模型指定，后续遵循AGENTS默认继承及调度规则：因原实施者存在已确认语义理解偏差，更换实施者接管现有diff，复用独立验收者，保持两个工作角色。追加修复现已通过，证据见下；不重跑模块全量。schema、共享repository及legacy/backup/sync直接写入属于同一唯一身份闭环，超过8文件/400行仍作为一个内聚批次；主要风险为旧库迁移、关系完整性与性能。

- 追加修复验收：独立验收者只读审查稳定产物，R1完整17表迁移前恢复图、R2遗留多名称传递合并、R3旧根持续订阅、R4双端真实未收藏SQL入口、R5重复解析revision幂等与自动绑定、R6普通角色替换/合并并集全部通过。恢复资料为组件内审计与受控恢复JSON，不提供通用撤销工具。
- 最终focused：`ga01-repair-final-focused5.log` PASSED/exit0，数据层87项（repository 46、index 22、legacy 10、schema 1、真实sync runner 8）零失败。`ga01-repair-stable-check.log` PASSED/exit0，Android 6项、Desktop 2项，app/data/domain spotlessCheck通过；独立审查核对XML，主代理核对日志与diff检查。
- 原规模性能case：10,000漫画/20,000精确名称，首次进度12ms、回填7.821s，重放进度2ms、回填5.151s，满足原500ms/15s门槛。`ga01-repair-performance.log`整轮因当时别名顺序revision用例失败而exit1，只将性能case记通过；该缺陷已修复并纳入最终repository 46项绿灯，未将整轮记为通过。
- 本批提交 `e6a75afd3f` 包含实现、测试和checkoff。GA-02前置门禁解除；真机、正式Windows/macOS产物及最终模块/全量验证仍留GA-06，不以JVM入口测试代替发布验收。

### GA-02 · 最终作者交互与命令接线

**用户结果**：最终DEMO中的添加别名、点击别名设主名、统一关注、图源按钮导航在两平台真实可用，退出重开仍保存。

**实施内容**：
1. 新增或扩展共享AddCreatorAliases/UpdateCreatorDisplayName命令（名称可遵循现有风格）。多选携带全组选中revision及幂等键；原子提交保留发起根和主名、名称/角色并集、关注启用并集，不改全局设置。
2. 状态覆盖候选加载/搜索/无候选、选择草稿、提交中、成功、失败、资料过期。刷新保留有效选择、移除已归入当前根项；无选择禁用添加，无预览/二次确认。
3. 别名点击显示规定确认文案；重新校验名称归属，成功旧主名留别名，失败保持数据与窗口。保留焦点返回与新主名焦点、屏幕阅读器语义及Escape/返回键。
4. 对齐作者列表无头像、主名标题样式、别名横排、来源按钮与封面顶部对齐；按已有作品关系分组，不按作者或标题自动并漫画。点击各图源进入其准确版本，缺插件仍保留本地详情和不可用标记。
5. 接入两端ScreenModel、DI、漫画署名和作者列表入口。列表/详情/旧导航订阅同一根；保留其他作者能力；校验旧删除/拆分入口安全边界，不开发新拆分页。

**红灯与验证**：真实repository命令测试+共享状态契约；Screen实例化、Voyager导航类型、DI解析、点击图源到指定mangaId、未收藏漫画署名进入根的集成测试。覆盖多选部分过期、双击、失败原子性、名称归属竞争、关注并集、旧入口、重启、320dp/主题/长别名换行与键盘焦点。HTML只能作为对照，不能作为production wiring证据。

**完成条件**：A3/A4通过；用户可见页面与 `77a610534` 对照一致，旧同名选框不可再达。涉及事务命令扩展必须审查；预计10–16小时，成本集中在双端状态/导航接线和错误路径。

**实施证据（2026-09-17，GA-02复审通过；随本批提交完成后暂停）**：
- GA-01前置提交 `e6a75afd3f` 已通过独立审查。复用追加修复实施者继续本批，共享命令和窗口状态复用既有repository/merge/recovery，双端保留平台视图适配。主代理维护文档，实施者独占Gradle协调权；本批不运行正式构建或安装真机。
- `ga02-command-red.log` 的4项真实SQL命令测试先因未实现失败，`ga02-command-green2.log` 四项通过；`ga02-editor-green.log` 累计6项通过，覆盖多选合并、全组过期拒绝、回滚、主名归属、取消、搜索与刷新选择。以上为早期局部证据，最终接线与复审结果见下。
- 旧按漫画/自由新名拆分无法安全表达完整名称迁移时，入口说明限制且不写入；不新增拆分管理器。正式发布前验证该限制，不以移除其他作者能力简化页面。
- 第1轮独立审查曾不通过；后续限定修复复审通过，见下。实施者适用证据42个独立case：data编辑12（`ga02-final-focused2`）+过滤1（`ga02-filter-green`）；Desktop既有行为8、页面7、漫画署名1（`ga02-final-focused3`），Follow1（`ga02-follow-layout-red`），新过滤/别名搜索2及原布局复验（`ga02-filter-green`）；Android作者入口/模型7、DI1（`ga02-final-focused3`）、Follow1（`ga02-follow-layout-red`）、过滤1（`ga02-filter-green`）。上述是跨轮次且仍适用的case证据，不表示某个整轮42项全绿；独立审查已核对适用证据。
- `ga02-wiring-green1`虽然exit0但Android旧Follow首轮失败后重试通过，不计干净整轮；后续Follow修正真实IO的虚拟超时/新增依赖mock后独立通过。`final-focused2/3`和`follow-layout-red`含后来修复的编译或用例失败，只复用其中明确通过的case。早期`ga02-ui-red`为测试夹具无挂起点导致取消，不计行为红灯；作品对照失败已证实为测试mock覆盖，不采用早期加载时机假设。
- 主代理已查看最新320dp深浅离屏图，真实本地PNG、长别名/来源换行、缺插件标记及更多按钮可达。实际mounted页面测试覆盖添加/主名确认/焦点/失败草稿/双击、作品搜索与来源过滤保持完整计数、准确本地版本导航；Android为真实ScreenModel/SQL/DI及导航事件，尚非Android Compose真机点击验收。`ga02-stable-check`格式Check、diff检查通过，正式运行留GA-06。


- 第1轮独立审查：两项阻塞——双端pending/rejected版本仍保留无封面/图源层级不一致的旧呈现；`refreshDisplayName`缺少迟到会话隔离，取消A并打开B后A旧响应可能取消或覆盖B。共享事务、全组版本校验、恢复凭据及幂等处理未发现新增阻塞。已交原实施者按预留1次修复复审处理两项及直接影响，当时保持未勾选；不新增代理或全量测试。

- 唯一限定修复复审：APPROVED，上述两项关闭。主名迟到成功/失败均按会话版本拒绝，pendingSnapshot与幂等键纳入同一原子状态；两端canonical/pending/rejected复用作品行，保留原审核操作及准确导航。实施代理两次容量错误后由主代理收回职责接管限定修复，未更换指定模型或新增代理，独立验收者保持不变。
- 修复红绿：`ga02-review-name-red`三项按正确原因失败，`ga02-review-name-green`四项通过；`ga02-review-rows-red2`真实挂载页面缺封面节点失败，`ga02-review-rows-green`Desktop两项及Android八项通过。`ga02-review-row-actions`补验真实More→WorkCompare及图源→准确mangaId通过；`ga02-review-check`三个模块spotlessCheck通过，主代理及独立验收者diff检查通过。
- 本批超过8文件/400行，因共享原子命令、两端窗口状态与真实页面接线构成一个可独立验收的能力闭环，保持同批提交；风险集中于迁移/幂等、窗口并发与双端交互，已分别覆盖测试和独立复审。
- 本批实现、测试、checkoff与[交接文档](2026-09-17-global-author-identity-handoff.md)同提交，提交说明为 `Add creator alias editing and consistent author pages`。用户于2026-09-17明确要求完成GA-02后暂停，GA-03至GA-06未启动实施，正式构建/真机验收仍未完成。

### GA-03 · 全局频率与后台任务

**用户结果**：作者列表右上角设置每天/每周/每月，一份周期对所有已关注及未来关注作者生效，详情不再提供周期设置。

**实施内容**：
1. 复用共享Preference/DI保存枚举和迁移完成标记；新配置缺失统一每天，旧每作者period_millis保留恢复用途但不再读取。保留关注的来源/语言/通知配置。
2. 共享可注入时钟与时区的日历到期计算，按功能设计第12节处理月末/DST/时区、首次关注、保存后重算及恢复补跑；成功与失败来源检查点分开处理。
3. 接入Android CreatorDiscoveryJob与Desktop CreatorDiscoveryScheduler的真实规划入口，复用锁、恢复、约束、退避和通知outbox。周期设置不绕开退避、不生成并发重复任务、不取消已运行事务。
4. 两端列表齿轮草稿/保存/失败/取消接线；移除或重定向原有周期编辑入口，任何入口都不得留下第二份设置权威。原有其他发现设置继续有效。

**红灯与验证**：共享参数化时间测试+旧配置fixture；扩展现有调度测试，并验证production job/scheduler实际调用新计算器。覆盖31日、闰年、时区切换、DST、失败退避、离线恢复、重复触发、取消关注抑制新通知、设置持久化/未来作者及名称操作不改频率。

**完成条件**：A5通过，真实平台后台触发至少各一次；不以sleep模拟或独立计算器绿灯替代接线。预计8–12小时，主要成本是时间边界与两平台任务生命周期。

**实施与审查记录（2026-09-17，GA-03通过限定复审，随本批提交完成）**：
- 用户明确恢复交接后由实施子代理承担GA-03代码与验证，主代理维护文档与验收，独立审查者只读审查；Gradle由实施者独占协调。全局偏好、日历到期、双端列表设置及后台接线属于同一能力批次，超过8文件/400行仍保持同批；主要风险是失败退避、关注取消与后台竞争。
- 有效红灯：`ga03-default-red2`证明旧60秒作者周期仍参与成功检查点；`ga03-calendar-red`覆盖月末/时区；`ga03-editor-red2`覆盖设置草稿；`ga03-platform-red4`暴露Android实际DI/Job缺口；`ga03-ui-red`真实Desktop列表缺齿轮；`ga03-unfollow-red2`证明取消后仍提交/通知；`ga03-never-red`证明从未成功来源遗留future deadline未立即到期。编译/夹具失败不计行为红灯；临时禁用重算未计入证据。
- `ga03-initial-green2`、`ga03-wiring-green`、`ga03-unfollow-green`是已取得的局部绿灯。`ga03-batch`因Desktop新DI/调度用例失败而未通过，Android旧入口SQLite驱动加载失败后自动重试也不计干净整轮；其Android实际Compose设置用例已通过。最终适用证据待限定修复后记录。
- 首轮独立审查不通过：取消关注将通知置为CANCELLED后，已取出的交付结果或FAILED重置PENDING仍可能要求非法终态转换，导致整批异常。要求真实Repository/Worker覆盖交付中取消后成功/重试、FAILED重置前取消、后续其他作者继续处理；另需关闭Desktop实际DI验证失败。原实施者负责限定修复，预留唯一修复复审，未启动GA-04。

- 限定修复：`ga03-review-race-red`三个真实Repository/Worker用例分别因CANCELLED→DELIVERED/FAILED/PENDING非法转换失败；取消终态优先忽略迟到写回，FAILED重置后再次检查递送目标，后续作者继续。`ga03-review-fix-green`数据状态机26项及domain outbox通过，但Desktop夹具仍失败，不记整轮绿灯。
- Desktop实际DI失败已定位为测试替换缓存SourceManager及测试源ID/非原子计数问题；改用现有本地测试扩展加载入口及原子计数，不改变生产来源约束。`ga03-final-focused` PASSED/exit0/53s，主代理核对XML：data48（状态机26、仓库22）、domain9（日历3、设置2、outbox4）、Desktop29（调度16、真实DI/平台2、作者页面11）、Android3（实际DI/Job2、实际Compose设置1），均零失败/错误/跳过；app/data/domain spotlessCheck通过。最终限定复审及旧Android入口补验结果见下。

- `ga03-android-mixed` PASSED/exit0/45s，Android新Robolectric后台2项、实际Compose1项与旧plain JVM入口8项按此顺序混跑，XML零失败/错误/跳过/重试，app格式检查通过。旧夹具沿用项目既有模式注册并仅释放自己的JDBC driver；最初类加载器身份未保留，不把原因推断写成已证实事实。相关混跑问题已关闭，GA-06仍按最终diff执行全量。
- 唯一限定复审：APPROVED。独立审查者核对三项正确红灯、取消终态修复及后续作者继续处理、真实Desktop DI与混跑XML/格式证据；未重复Gradle。主代理核对97个独立用例（48+9+29+11）及diff检查。实现、测试、文档/checkoff同批提交；没有全量测试、正式构建或用户库安装，不以本批JVM平台调用替代GA-06发布验收。本批提交 `de65a9bc91`，下一依赖GA-04解除。

### GA-04 · 新旧备份及恢复

**用户结果**：升级/重装/恢复备份后保持完整名字集合和关注，旧备份不能恢复出多个同名身份。

**实施内容**：
1. 扩展既有BackupAuthorArchive及贡献器，保存精确名称、主名、旧键映射和必要版本信息，不能仅导出旧normalized aliases。沿用现有备份恢复事务与设置选择范围。
2. 新格式空库恢复完整图；已有库先按名称统一根，保留本地有效主名，再按现有恢复规则应用关注及作品。旧格式缺名称表时从原始主名/接受别名回填，缺周期字段不覆盖已配置全局值。
3. 校验环、缺失引用、重复名称冲突和未知格式；错误必须在受影响事务提交前报告。保留旧键可解析，重复恢复幂等。恢复资料不得写出仓库或日志中的用户隐私。

**红灯与验证**：扩展 `AuthorArchiveBackupContributorTest` 并调用双端真实backup creator/restorer；新→新空库/非空库、旧→新、重复恢复、大小写变体无损、损坏拒绝、设置选择排除、未知枚举/格式。以完整关系图断言，不能只比较序列化字符串。

**完成条件**：A6通过；独立审查格式与迁移原子性，备份协议变更不得先被下游默认为可用。预计6–10小时。

**实施与审查记录（2026-09-17，GA-04通过限定复审，随本批提交完成）**：
- 前置GA-03提交`de65a9bc91`已通过审查。复用原实施者/独立审查者，Gradle继续串行；v5格式、恢复归根、双端恢复范围及真实备份接线作为同一能力批次，超过8文件/400行不拆成不可独立验收的微任务。
- v5新增精确names/origin，真实导出显式版本5，历史缺省版本保4；贡献器等待生产Repository的身份就绪；恢复保留有效本地主名、旧键、角色与关注范围。全局频率在现有应用偏好备份内校验，未知值保留本地并报告。Desktop现有确认框新增应用设置选项，纯作者档案备份预览不再误判为空；语言断言和发现记录映射最终根键。
- 行为红灯包括`ga04-archive-red2`名称丢失/主名覆盖、`ga04-wire-red`旧省略版本误读、`ga04-settings-red`两端未知频率覆盖、`ga04-choice-red`Desktop设置排除入口、`ga04-role-red`双角色丢失、`ga04-relationships-red`语言旧键/纯作者预览。编译或格式失败不作为行为红灯。
- `ga04-platform-green`局部通过；`ga04-final`行为测试通过但data三处超长行导致格式Apply失败，不记整轮完成，也不以Apply替代Check。
- 首轮独立审查NOT APPROVED：显式MERGED多个本地根经新共同根归并时主名受排列影响；提前归根后同作品多个旧键发现逐项覆盖，可能丢失UNSEEN/PENDING。要求统一稳定选根，并按最终根/来源/URL归并备份内发现碰撞，沿用既有未读/待处理优先、首次时间/基线代数规则；保持单条恢复既有语义。原实施者限定修复并补两种顺序、重复恢复及格式证据，随后唯一限定复审；未开始GA-05。

- 限定修复红绿：`ga04-review-red`两项真实恢复测试分别因LocalA变LocalB、UNSEEN变SEEN失败；名称交集和显式MERGED统一稳定选根，备份内同根/来源/URL发现按原有优先级归并，单条对本地恢复语义不变。`ga04-review-green`行为绿：归档14项、双端实际创建/编解码/恢复各1项，主代理核XML零失败/错误/跳过；该命令因测试SQL长行格式失败，不记整轮成功。最终格式与限定复审结果见下。

- 唯一限定复审APPROVED：两项阻塞关闭，真实两种排列与重复恢复测试通过，双端实际创建/编解码/恢复绿灯且未修改的设置/UI证据仍适用。`ga04-format-close` PASSED/exit0/16s，data/app spotlessCheck通过，主代理与独立审查者diff检查通过。此前`ga04-final`的data12/Desktop60/Android11为83项行为证据，新增两项在归档14项及双端各1项限定补验通过；不称某次85项整轮全绿。最终实现/测试/checkoff同批提交，无全量或正式发布，下一依赖GA-05解除。

### GA-05 · 现有关注同步兼容

**用户结果**：启用关注同步不影响本地作者统一或添加别名；旧键收到关注/取消事件仍落到正确根。别名与主名操作不被错误宣称在线同步。

**实施内容**：
1. 沿用SyncAuthorIdentity与现有journal/projection；可信精确姓名用于本地归根，portable key用于引用映射，不因远端key相同强行接受新的异名。缺名字又无已知key时挂起并在既有同步状态给出原因。
2. 本地同名迁移/异名添加维护旧键→根映射与现有关注事件的归属；明确后续取消按原因果语义执行，合并并集不能复活已取消状态。
3. 去除“开启同步就禁止本地合并”类门槛。复核descriptor字段是否足够：不足只提交最小兼容方案并配fixture；不偷用displayName/FOLLOWING传递异名合并或全局频率。
4. 同版本和混合版本接收要么安全落库要么明确兼容错误；不静默丢数据或阻断无关类别。异名集合在线传播留独立未来协议，本期不部署服务。

**红灯与验证**：复用 `data/src/commonTest/kotlin/mihon/data/sync/SyncCreatorJournalContract.kt` 及Android/JVM runners；真实journal到projection覆盖跨设备同名不同key、本地合并后旧key、乱序重复、取消/重新关注、缺名字、环、旧descriptor、同步关闭/重开。验证名称/主名本地操作没有意外远端合并副作用。

**完成条件**：A7通过；跨模块协议/因果投影独立审查通过。无法证明安全时保持未完成且阻止GA-06发布，不以禁用本地同名统一绕过。预计8–14小时。

**实施与审查记录（2026-09-17，GA-05通过限定复审，随本批提交完成）**：
- 沿用GA-04原实施者和独立审查者，真实journal→sealed outbox→inbox→projection共享契约验证既有FOLLOWING；descriptor字段足够，不新增在线别名、主名或频率协议。旧inbox夹具改用异名及真实Repository合并，避免同名已自动归根后手工反向MERGED制造环。
- `ga05-replay-red2`正确行为红灯：跨键同名归根后本地明确取消，已消费旧键ADD的dirty重投影恢复关注。首次`ga05-replay-red`为夹具编译失败，不计行为红灯。`ga05-replay-green`focused通过。
- `ga05-batch` PASSED/exit0/45s，主代理核对双端XML：每端journal14、inbox17、projection8，共78项零失败/错误/跳过；data spotlessCheck及diff检查通过。此前`ga05-contract`的坏身份夹具错误已修，不称整轮通过。
- 首轮独立审查NOT APPROVED，唯一阻塞：整个heads变化不能证明新ADD。已消费旧ADD、本地取消后，旧键收到并发REMOVE时，add-wins仍返回true且heads变化，会错误恢复关注。原实施者执行限定修复，必须以未消费且参与当前有效决定的ADD判断，补双端真实并发REMOVE不复活及新ADD可重关注测试，再进行唯一限定复审；未解除GA-06依赖。
- 限定修复：`ga05-review-red`双端各1项正确失败于新增并发REMOVE不应重放已消费ADD；提取原归约器effective heads的USER优先规则供投影共用，仅未消费有效ADD可应用关注。`ga05-review-green` PASSED/exit0/62s，主代理核XML：domain协议13，双端各journal15/inbox17/projection8，共93项零失败/错误/跳过；domain/data spotlessCheck与diff检查通过。覆盖真正新ADD及新接收端相反到达顺序，保持原add-wins和取消确认语义；唯一限定复审待结论。
- 唯一限定复审APPROVED：独立审查者核对有效ADD消费判断、共享选择规则与真实journal分支，复核双端正确红灯、93项XML及格式证据，唯一阻塞关闭。主代理核对同一证据并同批提交实现/测试/文档/checkoff。GA-04前置提交为`e852c0d07e`；GA-06依赖解除，正式运行验收尚未执行。
- 本批含必要维护文档共9个文件；消费判断、共享归约选择与双端因果契约是同一可交付兼容修复，保持同批。主要风险为取消/重关注因果，已由真实分支红绿和独立复审覆盖。

### GA-06 · 集成与正式运行验收

**用户结果**：真实设备上的漫画柜复现消失，功能可在正式产物中持续使用且原有作品/关注不丢失。

**实施内容与验证**：
1. 汇总前五批提交、审查和测试证据，确认所有写入口、备份、同步与调度都走共享核心；检查manifest适用项，不能用报告覆盖机器状态。
2. 对脱敏迁移fixture做完整升级→作者直达→添加别名→切主名→全局设置→检查→备份恢复→旧键关注同步→重启链路，核对作品/角色/关注数量及无同名多根。
3. 同一最终diff运行相关模块全套、Android/Desktop全量、格式、Test Mode。Gradle由协调器串行执行；不在每个微任务重复完整发布构建。
4. Windows/macOS使用 `scripts/build-desktop.sh`；只有同一diff已有等价完整测试证据时才允许build-only。Windows验收最终发布目录EXE，报告引用构建日志 `Final unpacked EXE:` 的实际存在路径。Android使用现有fork release身份配置构建（用户确认保持telemetry/updater关闭），并在已核对包名/配置/备份的真机验收，不混用模拟器结果。
5. Android漫画柜《平行天堂》点击署名直达唯一作者；对照Windows同源同作品。站点不可用时记录网络与本地链路各自结果，离线fixture不能代替真实图源复现项；用户库测试先保留可恢复备份，不清空原数据。
6. 视觉与交互核对最终基线：列表无头像，主名/别名区分，横向图源按钮，封面顶对齐，主题/窄屏/键盘/系统字体缩放。运行中退后台/重启后仍正确；原有通知、发现筛选、阅读/下载回归。

**完成条件**：A1–A8有证据，所有必需平台产物路径/版本/结果明确，无阻塞缺口才勾选。某平台不可访问时记录真实阻塞，不能以另一平台通过关闭。预计6–10小时，墙钟另受构建队列、macOS连接和真机/图源可用性影响。

**历史执行记录（以下保留各阶段当时状态，最终结论见收口证据）**：
- 前置GA-05已提交`f6e6b534df`，独立限定复审通过。延续实施/审查两角色；原实施会话不可用后按既有diff和协调器终态恢复实施者，不重做前五批。尚未运行最终全量或安装本轮产物。
- macOS隔离仓库`/Users/altair/Github/mihon-global-author-release`基于GA-05提交，原仓库既有改动未动；x86_64、JDK21、Android36 SDK及本机代理入口已核对。正式构建待最终diff稳定，并使用独立dist/deploy路径。
- Windows用户库只读快照保存在仓库外，SQLite完整性通过、schema25；迁移前711漫画/2922章节/13作者/13漫画作者关系/10来源作品/13来源作品角色/1关注，观察到1组ACTIVE精确同名多根。快照不纳入仓库或测试fixture；正式runtime升级后需核对收敛及数据保留。
- Android目标fork仍为aex.7/code25，漫画柜扩展已安装。无凭据锁通过正常系统唤醒解除；一次USB中断后用户重新连接。已在原应用创建完整默认备份（不含敏感设置），设备Download与仓库外`D:/Codex/home/tmp/mihon-ga06-20260917/android-preupgrade.tachibk`各保留一份，1,566,913字节，gzip完整性通过；未称已完成恢复验证。拟正式版本aex.8/code26，遥测/更新器继续关闭。
- 2026-09-18原版真机基线：漫画柜《平行天堂》→点击“冈本伦”实际出现三个同名选项的身份选择框，已取证后取消，未选择/创建身份。临时USB保持唤醒设置已恢复原值0，未安装本轮APK。
- 新增测试模式身份/主名/周期/真实漫画署名动作及受限同步fixture，复用真实DI、身份用例、设置调度与inbox/projector/journal；固定fixture仅隔离profile可用。`ga06-http-red`、`ga06-sync-red`、`ga06-resolve-red`为入口缺失的行为红灯，后续focused闭环；中间陈旧revision夹具已按产品规则重读。`ga06-focused` PASSED/exit0/50s，主代理与独立审查核XML：profile5、身份HTTP1、原controller2，共8项零失败/错误/跳过；app spotlessCheck和diff检查通过。
- GA-06代码首审APPROVED，无代码阻塞；不是发布完成。独立审查核profile/marker/数据库根及其他同步空间的写前防护、真实旧键因果路径、Android版本/签名限制。首审预算已使用，必要限定修复复审尚未使用。
- 早期启动失败：执行工具在CreateProcess前拒绝`start`与代理组合，仅返回`blocked by policy`，无具体原因。用户明确确认后的原样唯一重试仍被拒绝，当时无PID/状态JSON/日志；未改壳、权限或入口绕过。随后用户明确指定改用`run`，启动已成功，旧策略阻塞解除。
- 全量命令改为：`python scripts/gradle-coordinator.py run --key ga06-final -- .\gradlew.bat :domain:jvmTest :domain:testReleaseUnitTest :data:jvmTest :data:testReleaseUnitTest :app:testReleaseUnitTest :app-desktop:jvmTest -PincludeIntegrationTests=true :test-desktop:test :app-desktop:finalParityAudit spotlessCheck --console=plain`。会话UTF-8/SDK/临时代理前置见AGENTS。该次42秒后因唯一i18n base/strings.xml工作树CRLF失败；转LF后与HEAD/index字节一致、Git无差异。七项主要测试尚未执行，test-desktop为UP-TO-DATE。`ga06-final-remaining`继续未执行目标与未完成格式，未重复已通过验证；已发现契约/DI/迁移等失败，待按实际证据限定修复，不能记全量通过。
- `ga06-final-remaining`因HTTP测试清理Preferences后未恢复全局Injekt，产生`Node has been removed`下载级联失败，已仅停止协调器进程树（CANCELLED/130）；data/Desktop中断、Android与audit未到达，不能作通过证据。`ga06-isolation-red`正确复现退出后binding泄漏；新隔离scope和finally恢复后断言通过。
- 干净诊断还确认旧schema目录/历史v16混用、DI分层夹具缺config前置、作者设置factory直连repository违反架构及机器清单陈旧引用。限定修复未改迁移SQL或放宽guard：冻结v16原20表、当前完整24表，复用scheduler.hasDueWork，真实config前置，按实际符号更新清单。`ga06-review-fixes` PASSED/exit0/96s，domain13/data9/Desktop66共88项零失败/错误/跳过，domain/data格式与diff检查通过；下载用例实际先于HTTP运行，隔离证明来自HTTP退出后binding断言，不宣称执行顺序证据。唯一限定修复复审APPROVED：独立核对finally恢复、production到期查询、schema目录及机器清单，未放宽守卫；用户已明确批准追加一次Desktop全量及此前未完成验证，由原实施者串行执行。
- 获批追加`ga06-final-approved`于2026-09-18 01:04实际运行，01:07仅停止该协调器进程树（CANCELLED/130）。新output确认另一处同类型污染：GA-03的`CreatorGlobalScheduleWiringTest`直接初始化全局DI并删除Preferences，没有恢复原Injekt，下载类随后出现`Node has been removed`；此前HTTP隔离修复仍有效，但没有覆盖这处独立入口。另发现`ExtensionRepoRepositoryPersistenceTest`历史v18迁移测试失败，正在限定诊断。由原实施者补该设置测试红绿与真实先后顺序回归，并检查本任务新增用例有无同类遗漏；本轮domain Android完整414项零失败/错误/跳过；data JVM/Desktop中断，data Android/app Android/audit未到。后续`ga06-ga03-isolation-red`以退出后binding断言正确复现，隔离scope/finally修复后`ga06-ga03-isolation-ordered`按显式类排序实际执行设置2→下载目录4→恢复19→重试15，共40项全绿；整命令因独立v18夹具仍失败而exit1，不称整绿。v18夹具已补齐该历史版本真实旧表，仅剥离18后新增对象，再执行原完整升级与读回断言；`ga06-v18-fixture-green` PASSED/25s，5项及data格式通过。仅修改2个测试文件，无production/迁移SQL变化；主代理核diff及XML，本任务范围同类扫描无第三处遗漏。用户已明确批准完成剩余验证阶段：先data/Android/audit后单独Desktop全量，已通过模块复用，新失败仅必要定向修复复验；预算20–60分钟，不扩审查轮次。
- `ga06-format-final` app spotlessCheck通过（19s，UP-TO-DATE），Desktop无Spotless任务，三个文件仅人工机械整理导入，无行为变化。macOS隔离仓库已同步21个当前差异文件并逐SHA-256一致，未传用户数据，尚未构建；最终版本分配须协调两平台脚本，避免重复递增。GA-06保持未勾选、当前diff未提交，三平台正式验收待完成。

后续验证（2026-09-18）：`ga06-remaining-platforms`完整运行18分38秒，exit1。data JVM45 suites/425项，仅历史v16夹具1失败，含1万/10万事件规模用例通过；data Android24 suites/233项全绿；finalParityAudit完成且NON_TERMINAL_IDS为空。app Android99 suites/475条执行记录（含既有自动重试），三个缺偏好模块前置的DI用例各失败三次，关注用例首轮超时后重试通过，不称该全量干净通过。原始168份XML已存忽略目录归档。仅修v16历史夹具及两处Android测试的真实PreferenceModule前置，无production/迁移SQL修改；`ga06-platform-fixes` PASSED/37s，data1+Android14共15项零失败/错误/跳过/重复执行，data/app格式通过。原有正确用例与本次受影响路径复验组成平台证据，不重复已通过规模测试。`ga06-desktop-complete`按默认执行配置单独进行完整Desktop集成测试，未使用诊断阶段的类排序或并行覆盖。

**最终收口证据（GA-06完成）**：
- 用户在本任务中明确反馈“我进行了安卓真机验收并完成了”。据此登记用户执行的真机验收完成，关闭最终待验收项；未新增Agent真机操作、截图或逐项测量证据，不虚构用户未提供的细节。实现及测试已在`6828bfc618`提交，既有独立审查、自动化、正式产物和桌面运行证据继续适用。
- 已验证的源码、测试、正式版本与记录在`6828bfc618`提交保存；当时后置的真机验收现已由用户报告完成。产物版本后缀取构建时前置HEAD `f6e6b53`，实际包含本批已审查diff；不是该旧提交的干净构建，不为填自身提交hash另造状态提交。
- `ga06-desktop-complete` PASSED/4分1秒，412 suites/3117项，0失败/错误、3项条件跳过（Mac JXA、非headless Windows原生隐私、非release自定义周期），无重试；16个重名来自参数化显示名，不是重复重试。完整XML已归档忽略目录。前述平台失败定向关闭，已有通过证据复用。
- `ga06-windows-build` PASSED/90秒，官方脚本build-only唯一递增44→45，正式扩展runtime安装验收通过。日志唯一`Final unpacked EXE:`为`D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.45.f6e6b53-unpacked/Mihon Desktop.exe`，文件已核实。完整ZIP SHA-256 `e34d8864455b0ca764fa9b668a9c5912ba557635afd36201b7197c79a4017835`。
- `ga06-macos-build-lf` PASSED/2分40秒，同版本45，独立app部署到`/Users/altair/Applications/Mihon-Global-Author-Acceptance.app`。初次Windows管道把CR附在mode参数导致立即失败，未分配版本；改UTF-8/LF临时启动文件后成功，未重复版本递增。用系统ditto归档并传回`app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.45.f6e6b53-macos-x64.zip`，194339709字节，双端SHA-256一致：`7ead6097f2ca2bbb42b2abdf873cc9d61015c30a59afb3a4e1bcf34410e94a5a`。
- `ga06-android-release` PASSED/2分17秒，R8开启、telemetry/updater关闭；既有证书v2/v3签名校验通过。APK为`app/artifacts/android/0.19.4-aex.8-rc1/Mihon-Fork-0.19.4-aex.8-rc1-universal.apk`，67940235字节，SHA-256 `5ee9e6a3d30770c640257318eba4fae78f03500e115830d6e8a51d5ce18f97fe`；未安装或操作真机。
- Windows与macOS均使用上述真实发布产物和独立test-profile执行：固定旧键ADD→添加别名→切主名→daily/weekly/monthly→接收取消确认→旧ADD重放不复活→新ADD可关注→本地取消journal→重新关注→原生新备份。各自重启后状态保持，并在各自新空profile恢复为同一根/两名称/正确主名/关注true/monthly。Windows私有证据在`D:/Codex/home/tmp/mihon-ga06-20260917/`，Mac固定脱敏证据在`/Users/altair/ga06-20260918/evidence`；没有用户库传输、真实同步服务或系统桌面截图。该证据是headless production运行链，不冒充人工UI点击或Android真机。
- Windows真实schema25隔离副本升级27：711漫画/2922章节/10来源作品保留；按最终根映射核原13条漫画关系和13条来源作品角色关系全部保留，角色与启用关注未丢；原ACTIVE姓名均唯一登记。真实漫画柜搜索返回《平行天堂》`/comic/23333/`，选择后复用既有漫画89，署名精确解析根13，重启后仍同根。升级后原生备份创建成功，原用户库未修改。
- 额外原Android备份→Windows空profile返回PARTIAL_FAILURE。66个失败单位全部为`sourcePreferences`；Android使用`source_<ID>`，Desktop在`DesktopBackupRestorer`直接转Long，GA-04前已存在，非本任务回归。1157漫画/41338章节/699 ACTIVE作者落库且无相应失败单位，但未逐项比对源备份，不能声称完整恢复；图源偏好未恢复。记录为独立既有兼容问题，本轮未做产品修复或将该额外检查标为成功。
- 本批超过8文件/400行，新增受限测试模式、跨模块接线验证、历史夹具修复和正式版本/证据是同一收口批次；没有拆分成无法独立验收的微提交。所有本次正式验收进程已关闭，用户数据/备份、运行脚本/日志及构建产物均不入源码提交。真机门槛由上述用户验收回执关闭，GA-06勾选完成；既有图源偏好跨平台恢复问题仍独立保留，不算本次已修复。

设备验收记录：用户曾将Android真机验收留到最后，现已明确报告自行验收完成。无需重新连接、唤醒、安装或重跑验证；原生备份继续保留。用户随后明确要求提交此前未提交的验收状态文档，不改写后续其他任务提交。

## 5. 实施流程、预算与失败处理

以上估算合计50–80有效工程小时，是范围估算，不是耗时承诺。2026-09-17用户已授权实现本roadmap并标记完成任务；初始执行使用GPT-5.6 Sol medium、独立验收使用GPT-6 Astra medium；用户后续明确撤销模型指定，新增子代理使用默认继承配置并遵循AGENTS调度规则。保持实施与独立验收两个角色串行交付，每批1轮审查及必要时1轮修复复审，最终全量组合1次；GA-01已另获追加1次限定修复审查授权，见该批证据。构建等待与外部环境故障另列，不重复进行历史站点身份调查。

启动后遵循仓库委派要求：主代理先定义验收并将GA-01主要实现交实施子代理；主代理负责接口、集成和独立验收。默认最多2个子代理（实施者+独立审查者），复用原代理串行交付相近批次，不并行写schema/repository。高风险迁移、事务、备份和同步边界在下游依赖前审查；每批预算1轮独立审查，必要修复复审最多1轮，新增轮次/显著成本按仓库规则说明并申请。低风险纯视图调整可在对应批次自查，不另开审查项目。

每批先写真实行为失败测试并确认正确原因，再最小实现、重构并复跑focused；批次完成跑受影响单元/集成/wiring/格式检查。阶段末跑对应模块完整测试；GA-06收口全量组合一次。不能用源代码字符串、mock parser或复制实现的测试代替production路径。若修改HTTP解析则补MockWebServer成功、空、403/429/500和畸形响应契约，否则不扩张网络测试范围。

数据失败按事务回滚并保持可重试；遇到损坏根链、格式不兼容或平台不可用，记录观察、影响和下一步，不把异常改成人物选择。共享契约需要实质变化时先更新设计及该批次依赖再继续，不因文件变多重规划。不得降级为纯UI去重、关闭同步/备份或跳过迁移来勾选完成。

执行中每批在本文件对应小节追加简短证据：提交hash、实现范围、红灯原因、实际验证命令/结果、独立审查结论、未解决风险和下一依赖。未执行测试明确写未运行；暂不填虚构类名/日志/产物。子代理按仓库要求返回status/diff/tests/commit/process/next。没有单独任务快照或巨型diff包。

## 6. 实施验证入口与前置状态

实施时的验证命令模板（当前未运行；测试过滤按实际修改的既有类/新增契约更新）：

```powershell
# 数据层focused示例；通过协调器避免同一worktree并发Gradle
python scripts/gradle-coordinator.py run --key ga01-data -- ./gradlew.bat :data:jvmTest --tests "tachiyomi.data.creator.CreatorRepositoryImplTest"
# Android真实作者入口接线
python scripts/gradle-coordinator.py run --key ga02-android -- ./gradlew.bat :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.browse.author.AndroidAuthorArchiveWiringTest"
# 最终格式与Android测试，完整Desktop测试由正式构建脚本执行或复用同diff证据
python scripts/gradle-coordinator.py run --key ga06-final -- ./gradlew.bat spotlessCheck testReleaseUnitTest
```

正式Desktop构建在可运行仓库脚本的shell执行 `./scripts/build-desktop.sh`，macOS同样使用该脚本；Android发布使用 `-I scripts/android-fork-release.init.gradle assembleRelease` 并通过同一Gradle协调器；2026-09-17用户明确确认保持telemetry/updater关闭，不传 `-Pinclude-telemetry` 或 `-Penable-updater`。沿用fork包名与已有签名，正式收口时同步分配可升级版本并更新签名脚本版本校验。开始前设置Windows/Python UTF-8与SDK环境，核对当时实际可用task；以上不是对尚未生成的测试类或产物作出通过声明。

启动前置（2026-09-17）：Android SDK的android-36/android.jar、build-tools/36.0.0/aapt2.exe及platform-tools/adb.exe存在；用户随后连接真机；只读确认PCE-W30/API31、目标app.mihon.desktop.fork版本0.19.4-aex.7（versionCode 25），另有app.mihon及app.mihon.dev，最终验收须核对目标身份并先备份；尚未安装或验收本轮产物。mbp-lan只读连接成功（macOS 14.8.4、JDK21.0.10可用、约29GiB可用磁盘）；后续须使用隔离工作树及应用目录，保护远端既有改动与实例。工作区已有未跟踪testfile/，不纳入本任务。上述仅为环境前置，不表示任何批次通过；实际测试与发布结果随批次记录。
