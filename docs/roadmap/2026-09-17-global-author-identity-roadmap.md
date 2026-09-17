# 跨插件唯一作者 · 正式实施 Roadmap

- 日期：2026-09-17；状态：PLANNED，尚未启动production实现。
- 类型：产品child plan；从第3节第一个未勾选批次推导进度，不再声明active-task。
- 父路线：[主Roadmap](2026-06-30-mihon-desktop-refactor-roadmap.md)。仅登记待执行专项，不改变父路线active-child-plan，不恢复旧作者归档专项及其无关backlog。
- 最终产品依据：[功能设计](../2026-09-17-global-author-functional-design.md)；[技术方案](../2026-09-17-creator-identity-reconciliation-proposal.md)；[已确认HTML](../prototypes/author-identity/index.html)，基线提交 `77a610534`。
- 本文件记录批次进度与提交/审查/验证证据；已有capability的机器状态仍以 `app-desktop/src/test/resources/parity/parity-manifest.json` 为准。本轮不更新任何production capability状态。

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

- [ ] **GA-01：精确名称唯一内核、历史迁移与所有身份写入入口**（无前置）
- [ ] **GA-02：添加别名、主名切换及双端最终交互**（依赖GA-01及其独立审查）
- [ ] **GA-03：全局作者设置与真实发现调度**（依赖GA-02）
- [ ] **GA-04：备份恢复与设置兼容**（依赖GA-01至GA-03）
- [ ] **GA-05：现有作者关注同步兼容**（依赖GA-01、GA-02、GA-04稳定契约）
- [ ] **GA-06：双端集成、迁移回归与正式发布验收**（依赖GA-01至GA-05）

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

### GA-03 · 全局频率与后台任务

**用户结果**：作者列表右上角设置每天/每周/每月，一份周期对所有已关注及未来关注作者生效，详情不再提供周期设置。

**实施内容**：
1. 复用共享Preference/DI保存枚举和迁移完成标记；新配置缺失统一每天，旧每作者period_millis保留恢复用途但不再读取。保留关注的来源/语言/通知配置。
2. 共享可注入时钟与时区的日历到期计算，按功能设计第12节处理月末/DST/时区、首次关注、保存后重算及恢复补跑；成功与失败来源检查点分开处理。
3. 接入Android CreatorDiscoveryJob与Desktop CreatorDiscoveryScheduler的真实规划入口，复用锁、恢复、约束、退避和通知outbox。周期设置不绕开退避、不生成并发重复任务、不取消已运行事务。
4. 两端列表齿轮草稿/保存/失败/取消接线；移除或重定向原有周期编辑入口，任何入口都不得留下第二份设置权威。原有其他发现设置继续有效。

**红灯与验证**：共享参数化时间测试+旧配置fixture；扩展现有调度测试，并验证production job/scheduler实际调用新计算器。覆盖31日、闰年、时区切换、DST、失败退避、离线恢复、重复触发、取消关注抑制新通知、设置持久化/未来作者及名称操作不改频率。

**完成条件**：A5通过，真实平台后台触发至少各一次；不以sleep模拟或独立计算器绿灯替代接线。预计8–12小时，主要成本是时间边界与两平台任务生命周期。

### GA-04 · 新旧备份及恢复

**用户结果**：升级/重装/恢复备份后保持完整名字集合和关注，旧备份不能恢复出多个同名身份。

**实施内容**：
1. 扩展既有BackupAuthorArchive及贡献器，保存精确名称、主名、旧键映射和必要版本信息，不能仅导出旧normalized aliases。沿用现有备份恢复事务与设置选择范围。
2. 新格式空库恢复完整图；已有库先按名称统一根，保留本地有效主名，再按现有恢复规则应用关注及作品。旧格式缺名称表时从原始主名/接受别名回填，缺周期字段不覆盖已配置全局值。
3. 校验环、缺失引用、重复名称冲突和未知格式；错误必须在受影响事务提交前报告。保留旧键可解析，重复恢复幂等。恢复资料不得写出仓库或日志中的用户隐私。

**红灯与验证**：扩展 `AuthorArchiveBackupContributorTest` 并调用双端真实backup creator/restorer；新→新空库/非空库、旧→新、重复恢复、大小写变体无损、损坏拒绝、设置选择排除、未知枚举/格式。以完整关系图断言，不能只比较序列化字符串。

**完成条件**：A6通过；独立审查格式与迁移原子性，备份协议变更不得先被下游默认为可用。预计6–10小时。

### GA-05 · 现有关注同步兼容

**用户结果**：启用关注同步不影响本地作者统一或添加别名；旧键收到关注/取消事件仍落到正确根。别名与主名操作不被错误宣称在线同步。

**实施内容**：
1. 沿用SyncAuthorIdentity与现有journal/projection；可信精确姓名用于本地归根，portable key用于引用映射，不因远端key相同强行接受新的异名。缺名字又无已知key时挂起并在既有同步状态给出原因。
2. 本地同名迁移/异名添加维护旧键→根映射与现有关注事件的归属；明确后续取消按原因果语义执行，合并并集不能复活已取消状态。
3. 去除“开启同步就禁止本地合并”类门槛。复核descriptor字段是否足够：不足只提交最小兼容方案并配fixture；不偷用displayName/FOLLOWING传递异名合并或全局频率。
4. 同版本和混合版本接收要么安全落库要么明确兼容错误；不静默丢数据或阻断无关类别。异名集合在线传播留独立未来协议，本期不部署服务。

**红灯与验证**：复用 `data/src/commonTest/kotlin/mihon/data/sync/SyncCreatorJournalContract.kt` 及Android/JVM runners；真实journal到projection覆盖跨设备同名不同key、本地合并后旧key、乱序重复、取消/重新关注、缺名字、环、旧descriptor、同步关闭/重开。验证名称/主名本地操作没有意外远端合并副作用。

**完成条件**：A7通过；跨模块协议/因果投影独立审查通过。无法证明安全时保持未完成且阻止GA-06发布，不以禁用本地同名统一绕过。预计8–14小时。

### GA-06 · 集成与正式运行验收

**用户结果**：真实设备上的漫画柜复现消失，功能可在正式产物中持续使用且原有作品/关注不丢失。

**实施内容与验证**：
1. 汇总前五批提交、审查和测试证据，确认所有写入口、备份、同步与调度都走共享核心；检查manifest适用项，不能用报告覆盖机器状态。
2. 对脱敏迁移fixture做完整升级→作者直达→添加别名→切主名→全局设置→检查→备份恢复→旧键关注同步→重启链路，核对作品/角色/关注数量及无同名多根。
3. 同一最终diff运行相关模块全套、Android/Desktop全量、格式、Test Mode。Gradle由协调器串行执行；不在每个微任务重复完整发布构建。
4. Windows/macOS使用 `scripts/build-desktop.sh`；只有同一diff已有等价完整测试证据时才允许build-only。Windows验收最终发布目录EXE，报告引用构建日志 `Final unpacked EXE:` 的实际存在路径。Android使用仓库release参数构建并在已核对包名/配置/备份的真机验收，不混用模拟器结果。
5. Android漫画柜《平行天堂》点击署名直达唯一作者；对照Windows同源同作品。站点不可用时记录网络与本地链路各自结果，离线fixture不能代替真实图源复现项；用户库测试先保留可恢复备份，不清空原数据。
6. 视觉与交互核对最终基线：列表无头像，主名/别名区分，横向图源按钮，封面顶对齐，主题/窄屏/键盘/系统字体缩放。运行中退后台/重启后仍正确；原有通知、发现筛选、阅读/下载回归。

**完成条件**：A1–A8有证据，所有必需平台产物路径/版本/结果明确，无阻塞缺口才勾选。某平台不可访问时记录真实阻塞，不能以另一平台通过关闭。预计6–10小时，墙钟另受构建队列、macOS连接和真机/图源可用性影响。

## 5. 实施流程、预算与失败处理

以上估算合计50–80有效工程小时，是范围估算，不是本轮执行承诺或自动授权。构建等待与外部环境故障另列，不重复进行历史站点身份调查。本轮只产出最终设计和roadmap，不启动任何批次。

启动后遵循仓库委派要求：主代理先定义验收并将GA-01主要实现交实施子代理；主代理负责接口、集成和独立验收。默认最多2个子代理（实施者+独立审查者），复用原代理串行交付相近批次，不并行写schema/repository。高风险迁移、事务、备份和同步边界在下游依赖前审查；每批预算1轮独立审查，必要修复复审最多1轮，新增轮次/显著成本按仓库规则说明并申请。低风险纯视图调整可在对应批次自查，不另开审查项目。

每批先写真实行为失败测试并确认正确原因，再最小实现、重构并复跑focused；批次完成跑受影响单元/集成/wiring/格式检查。阶段末跑对应模块完整测试；GA-06收口全量组合一次。不能用源代码字符串、mock parser或复制实现的测试代替production路径。若修改HTTP解析则补MockWebServer成功、空、403/429/500和畸形响应契约，否则不扩张网络测试范围。

数据失败按事务回滚并保持可重试；遇到损坏根链、格式不兼容或平台不可用，记录观察、影响和下一步，不把异常改成人物选择。共享契约需要实质变化时先更新设计及该批次依赖再继续，不因文件变多重规划。不得降级为纯UI去重、关闭同步/备份或跳过迁移来勾选完成。

执行中每批在本文件对应小节追加简短证据：提交hash、实现范围、红灯原因、实际验证命令/结果、独立审查结论、未解决风险和下一依赖。未执行测试明确写未运行；暂不填虚构类名/日志/产物。子代理按仓库要求返回status/diff/tests/commit/process/next。没有单独任务快照或巨型diff包。

## 6. 本轮文档验收

实施时的验证命令模板（当前未运行；测试过滤按实际修改的既有类/新增契约更新）：

```powershell
# 数据层focused示例；通过协调器避免同一worktree并发Gradle
python scripts/gradle-coordinator.py run --key ga01-data -- ./gradlew.bat :data:jvmTest --tests "tachiyomi.data.creator.CreatorRepositoryImplTest"
# Android真实作者入口接线
python scripts/gradle-coordinator.py run --key ga02-android -- ./gradlew.bat :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.browse.author.AndroidAuthorArchiveWiringTest"
# 最终格式与Android测试，完整Desktop测试由正式构建脚本执行或复用同diff证据
python scripts/gradle-coordinator.py run --key ga06-final -- ./gradlew.bat spotlessCheck testReleaseUnitTest
```

正式Desktop构建在可运行仓库脚本的shell执行 `./scripts/build-desktop.sh`，macOS同样使用该脚本；Android发布使用 `assembleRelease -Pinclude-telemetry -Penable-updater` 并通过同一Gradle协调器。开始前设置Windows/Python UTF-8与SDK环境，核对当时实际可用task；以上不是对尚未生成的测试类或产物作出通过声明。

本轮仅冻结设计与登记计划，生产批次全部未勾选。文档核验包括本地链接存在、基线提交存在、批次依赖无环、A1–A8映射完整、旧交互冲突清理和diff空白检查；不运行Gradle、不重新构建应用、不更改当前父计划或manifest。
