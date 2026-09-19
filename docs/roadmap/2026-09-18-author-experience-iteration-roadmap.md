# 作者页体验迭代 · 开发Roadmap

日期：2026-09-18。状态：IN_PROGRESS。

- 需求权威：[完整设计](../2026-09-18-author-experience-iteration-design.md)。交互基线：[DEMO](../prototypes/author-identity/index.html)，提交 `7773095f5e`。
- 本计划为产品child plan，从首个未勾选批次推导进度，不声明active-task。旧[唯一作者Roadmap](2026-09-17-global-author-identity-roadmap.md)保持原完成状态，不重置GA任务。
- 本计划已进入生产实施，父级唯一 `active-child-plan` 指向本计划。AX-01至AX-05期间不执行正式构建/安装/部署；AX-06按下文收口验收。各批依赖与提交边界按本计划执行。
- checkbox仅在实现、相关测试、必要独立审查和提交均完成后勾选；历史DEMO测试不替代本计划production证据。

## 1. 开发边界与前置

本轮按完整用户能力分批，Android/Desktop共享语义在同批交付。现有作者身份、别名、全局周期和高级校正是回归对象，不重新开发。代码检查已确认存在归档投影、发现/read状态、outbox和OpenCreatorWorkVersion，应扩展其真实链路。

开始每批前检查实际HEAD、未提交改动、相关AGENTS及现有测试；保护并排除其他任务改动。本次规划时旧功能设计、旧roadmap/handoff、i18n及testfile已有未提交内容，不能把它们当成本轮可覆盖文件。新增字符串需在实施时协调同文件改动。

不包含自动模糊同作、外部作者数据库、远端热门排行、额外通知中心、新同步功能和手工代表作管理。只有已确认同作品关系可聚合；日期来源ID不决定作者身份。

## 2. 依赖、规模与执行安排

| 批次 | 用户可见交付 | 前置 | 预计工作量 |
|---|---|---|---|
| AX-01 | 关注/全部、顶部栏、B布局代表作 | 当前已完成作者能力 | 8–12小时 |
| AX-02 | 三视图、收藏、完整多源选择、显示偏好 | AX-01 | 8–12小时 |
| AX-03 | 稳定首次发现日期、章节完整性与日期保存 | AX-02 | 6–10小时 |
| AX-04 | 来源日期可信判断及生产更新接线 | AX-03 | 8–12小时 |
| AX-05 | 作品级新作提醒与查看闭环 | AX-02、AX-03；默认在AX-04后执行 | 8–12小时 |
| AX-06 | 双端整合、回归、正式发布运行验收 | AX-01至AX-05 | 6–10小时 |

总估算44–68工程小时，不含外部真实图源跨日采样等待；日期测试使用可注入时钟和本地HTTP fixture，无需等待24小时跑测试。真实来源没有充分证据时保持unknown，不以工期为由写入白名单。

默认串行，避免共享ScreenModel、schema与测试资源写入冲突；有额外授权/明确边界时才并行无依赖工作。首个AX-01任务簇已由实施子代理负责主要实现与验证，主代理负责接口、整合与验收；后续相近任务复用同一实施者。主代理不重复实现已委派部分。

每次执行先声明实际范围和预算，默认最多2个子代理（实施与必要独立审查），1轮审查及最多1次限定修复复审。多个高风险里程碑的审查须作为各自明确执行批次安排；若要求一次完成全部而会超过本次授权审查预算，先说明额外成本确认安排，不默许无限复审。AX-01使用1名实施子代理，主代理完成独立整合验收；AX-03与AX-05的审查在各自批次单独安排。

## 3. 实施批次

### AX-01 · 作者导航、关注子页与代表作卡片

- [x] AX-01：交付双端作者列表及详情外壳，A01–A04/A15相关项通过。

**用户结果**：进入作者默认关注，可切全部；无关注有引导。作者卡片无头像，以最多3部作品辨认；进入详情有标准返回栏，返回保留子页和滚动。

**范围与入口**：Android `AuthorsTab.kt`，Desktop `AuthorsTab.kt/AuthorsScreenModels.kt`，现有CreatorArchiveRepository投影、history聚合与封面adapter。可提取共享代表作selector和批量查询，不动身份算法、日期质量或新作通知。

**实施内容**：

1. 先扩展真实repository批量作者卡片投影，包含关注根、去重作品数、收藏/阅读信号与封面请求描述；每页最多50作者，不在Compose逐作者查询。
2. 按设计第3节实现最多3项资格、分档、稳定选择及可重建缓存，覆盖无封面、无作品和合并。禁止以章节数或source版本数推断热门。
3. 双端接入关注/全部状态、精确空态与查看全部入口；详情返回还原子页/滚动，主导航重新进入默认关注。
4. 详情接标准Scaffold/标题/返回，保留主名/别名/关注/添加别名语义。普通页面移除语言可信度枚举，既有高级校正路径仍可达。
5. 复用缓存封面和加载失败占位；所有字符串走现有资源体系。

**红绿验证**：共享selector行为测试先失败；真实SQL多版本、阅读和收藏数据驱动投影测试；双端mounted UI/ScreenModel测试验证默认页、空态、返回、代表作去重、稳定性、晋级和名称回归；新增导航/DI须实例化与接线测试。用查询次数随页数增长的测试防N+1，不用源码扫描。

**边界/完成证据**：不改任务调度，不新增远端请求。提交production＋测试＋本项证据；深浅主题、320dp和长名截图/离屏证据，记录所运行focused与格式检查。通常跨多模块超过8文件仍保持本能力为一批，不按文件拆任务。

**内聚性与风险**：实际改动跨 Android、Desktop、domain、data/SQL、i18n 与集成测试，并超过8文件/400行；这些改动共同完成同一作者列表能力，拆开会留下未接线的投影、缓存或平台入口。v27→v28只新增最多3项选择的本地可重建缓存，作者删除时级联清理；独立测试验证缓存迁移、合并/重开稳定性以及不进入备份和远端同步。

**执行证据**：

| 范围 | 命令/记录 | 结果 |
|---|---|---|
| Android mounted 作者流程 | `:app:testReleaseUnitTest --tests eu.kanade.tachiyomi.ui.browse.author.AndroidCreatorSettingsUiTest`；协调器 `ax01-postformat-android1` | exit 0；默认关注、空态、搜索/分页、重试、返回保留和320dp卡片布局通过 |
| Repository / migration | `:data:jvmTest --tests tachiyomi.data.creator.CreatorCardProjectionTest --tests tachiyomi.data.creator.CreatorArchiveMigration15Test`；`ax01-postformat-data1` | exit 0；SQLite投影、页边界、稳定缓存、v27→v28迁移与重开通过 |
| Domain契约 / selector | `:domain:jvmTest --tests tachiyomi.domain.creator.CreatorArchiveV2ContractTest --tests tachiyomi.domain.creator.service.CreatorRepresentativeWorkSelectorTest`；`ax01-postformat-domain1` | exit 0；缓存表删除策略及代表作筛选/稳定性通过 |
| Desktop mounted与架构接线 | `:app-desktop:jvmTest --tests mihon.desktop.ui.authors.AuthorsProductionWiringTest --tests mihon.desktop.architecture.DesktopArchitectureGuardTest`；`ax01-authors-interactor-desktop-green1`；另以真实SQLite仓库运行 `AuthorCardProductionWiringTest`，`ax01-desktop-author-card-reentry-green1` | exit 0；Following默认、代表作卡片、失败重试、导航回返和interactor wiring通过 |
| 备份 / 同步边界 | `AuthorArchiveBackupContributorTest`：`ax01-backup-cache-exclusion-green1`；`JvmSyncRemoteProjectionContractTest`：`ax01-sync-cache-local-green1` | 均exit 0；本地选择缓存不写入备份或同步 |
| 格式 / diff | `spotlessCheck`：`ax01-final-spotlesscheck4`；`git diff --check` | 均exit 0 |

TDD红绿记录：`ax01-android-author-card-layout-red2` exit 1，真实320dp mounted场景确认代表作栏与作者标题重叠；修复纵向卡片结构后 `ax01-android-author-card-layout-green1` exit 0。首轮viewport断言按300–320dp编写，但实际列表因左右inset为288dp；将断言改为相对真实列表及屏幕边界，没有放宽生产布局。最终截图 `app-desktop/build/ax01/author-card-320-light.png` 与 `app-desktop/build/ax01/author-card-320-dark.png` 均存在并已检查。格式修复中发现的长行和导入顺序已修正，最终全局Spotless通过；一次把过滤参数放在多个Gradle test task之后的验证命令启动了宽测试，经协调器取消（exit 130），随后Android、data、domain各用独立过滤命令复验并通过。

### AX-02 · 作者作品三视图、收藏和多源选择

- [x] AX-02：交付作者作品浏览闭环，A05–A08/A15相关项通过。

**用户结果**：作品可切列表/舒适网格/紧凑网格；第一次跟随书架，主动选择后所有作者共用保存值。所有收藏图标在封面左上角；点作品选择准确图源版本。

**前置**：AX-01投影/导航稳定。**入口**：LibraryPreferences、平台LibraryDisplayMode、CommonMangaItem/BrowseBadges、CreatorWorkArchiveFilter、OpenCreatorWorkVersion及两端作者详情。

**实施内容**：

1. 定义一个可缺省的作者作品显示覆盖偏好，复用偏好存储。读缺省不写入；同值主动选择也保存；重启、切作者、别名变化保留；无效值安全回退；写失败保留旧值并反馈。
2. 最小抽取书架视觉组件，加入作者页日期/来源/新作插槽；列表与网格均在封面左上角放CollectionsBookmark，透明度0.34，标题不变淡。不得沿用原版列表行尾图标。
3. 横向Material来源chips＋搜索交集过滤，数量紧随作品标题；收藏聚合与来源窗口读取未过滤完整组，避免筛选隐藏收藏源导致状态丢失。
4. 单源/多源均打开选择窗口，按版本展示封面、章节信息、收藏和日期占位；点击准确版本走OpenCreatorWorkVersion。缺源保留本地入口、失败保留窗口；取消恢复作品焦点。
5. 使用现有目录可提供的信息；完整性不足先显示未知/部分，不假装0章。日期投影在AX-03/04增强，不硬编码真实源日期。

**红绿验证**：真实PreferenceStore重启测试及共享有效模式契约；双端真实点击切模式/换作者、选择同值、保存失败、过滤后完整多源、准确mangaId、已有manga不重复、图标相对封面位置和可访问性。所有新增navigator.push/DI绑定有集成测试。测试覆盖1源、3源、缺源、部分目录、不同收藏状态、长标题320dp。

**边界/证据**：不新增收藏操作，不联动修改书架偏好，不模拟后台抓取作为窗口加载。双端focused/格式及可运行UI证据随单批提交。

**内聚性与风险**：实际改动跨 Android、Desktop、domain、i18n 与两端 mounted/wiring 测试，并超过8文件/400行；这些改动共同完成同一作品浏览闭环，拆开会留下偏好继承、完整版本聚合、来源选择或导航错误的未接线状态。作者显示覆盖只使用一个设备级偏好键；作品卡显示与来源窗口始终从未过滤的确认版本组计算收藏和版本，来源筛选仍只作用于可见列表。打开版本增加串行门控，失败保留窗口和旧状态；取消/Escape通过实际 `FocusRequester` 恢复作品卡焦点。AX-02不改变章节日期事实，日期占位继续由AX-03/04负责。

**执行证据**：

| 范围 | 命令/记录 | 结果 |
|---|---|---|
| 偏好契约 | `:domain:jvmTest --tests tachiyomi.domain.library.LibraryPreferencesTest`；`ax02-preference-contract-green1` | exit 0；缺省跟随书架、显式覆盖、无效值回退及不回写书架通过 |
| Android 三视图/来源窗口 | `:app:testReleaseUnitTest --tests '*AndroidCreatorSettingsUiTest.mounted author work display modes*'`；`ax02-android-display-layout-green4`、`ax02-source-dialog-android-green2`、`ax02-android-focus-final1` | 均exit 0；320dp真实挂载验证列表/舒适网格/紧凑网格、过滤后仍显示完整1/2/3版本、取消焦点恢复通过 |
| Android 作者接线 | `:app:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.ui.browse.author.AndroidAuthorArchiveWiringTest.actual Android detail editor obeys shared identity contract'`；`ax02-android-archive-final2` | exit 0；已有版本复用与缺失版本保存走真实 `OpenCreatorWorkVersion`，未因新增偏好依赖破坏身份编辑接线 |
| Desktop 三视图/来源窗口 | `:app-desktop:jvmTest --tests 'mihon.desktop.ui.authors.AuthorsProductionWiringTest.mounted display mode follows shelf until explicit selection and survives remount'`；`ax02-desktop-source-final2`、`ax02-desktop-focus-final4` | 均exit 0；真实SQLite挂载验证书架继承、显式持久化、重启恢复、完整来源窗口与取消路径 |
| 有效红灯记录 | `ax02-display-mode-red1`、`ax02-display-mode-persistence-layout-red2`、`ax02-android-display-layout-red5` | 均为生产行为断言失败且编译成功；分别先证明入口缺失、重启后布局未实现、Android标题布局未接线，随后由本批实现修复 |
| 格式与差异 | `spotlessCheck`；`ax02-spotless-final5`；`git diff --check` | 均通过；仅报告换行格式提示，无空白错误 |

审阅结论：按需求逐项检查了设备级覆盖、无效值回退、来源过滤与完整版本聚合、收藏透明度/左上角标记、缺源与失败保留窗口、串行打开和取消焦点恢复；未发现会扩大到AX-03日期或AX-05提醒的实现。一次全类 Desktop 回归因既有 MockK 全局接线测试的并行隔离失败而失败（6个非本批测试，AX-02定向测试通过），未将其作为生产回归证据。

### AX-03 · 首次发现日期与元数据持久化

- [x] AX-03：交付稳定日期及真实章节完整性投影，A08/A13及日期恢复项通过。

**用户结果**：没有可信上架日期时显示固定首次发现年月日；刷新、重启或恢复不会变晚，未抓目录不冒充0章。

**前置**：AX-02投影和UI插槽。**入口**：author_archive.sq及CreatorArchiveRepositoryImpl、CreatorArchiveV2Contract、既有章节更新/目录状态、作者备份贡献者。

**实施内容**：

1. 复核当前schema，追加最小迁移；复用first_seen_at和last_seen_at，增加来源日期证据、展示快照所需字段/表及索引。不得预占旧迁移号或建镜像schema。
2. 实现完整日期验证、部分日期精度保存、来源/首次发现区分、只向前校正、冻结日/时区。旧记录从已有首次时间回填，不以迁移执行日替换。
3. 作品聚合从有效版本取最早首次日期，收藏/名称刷新不覆盖；合并/拆分按成员证据处理。快照保留证据来源、资格与策略版本。
4. 投影章节数及完整性：UNKNOWN/PARTIAL/COMPLETE内部状态，经产品文案展示。只计当前版本目录，保留已获取数，去重和完整性沿用现有目录更新事务。
5. 两端直接消费投影显示首次发现、已有可信快照及未知日期；接入当前网络观察入口而非独立演示服务。
6. 同批扩展作者备份/恢复兼容及迁移测试，事实可恢复，restore不发新作通知；本地质量样本不进入备份。精确schema/默认/失败回滚说明写入设计附录。

**红绿验证**：真实SQL迁移/重跑/失败回滚，旧fixture/新备份往返；闰日/非法日/未来值/仅年份/月/时区变更、刷新与重启不可后移；部分目录到完整目录、导入日期取早、作品拆分不污染。UI必须读取真实repository，不用模型复制实现。

**审查关口**：迁移、日期数据完整性与备份为高风险；由未参与实现者独立审查通过后才能让AX-04/05依赖。记录用例与真实日志，不以“测试已绿”替代审查。采用本执行批次预先声明的审查预算。

**边界**：不建立真实源白名单，不将章节最早日期写为上架日，不扩大同步字段语义。

**执行证据**：

| 范围 | 命令/记录 | 结果 |
|---|---|---|
| 备份、日期、旧行恢复与迁移 | `ax03-green-restore-fallback`、`ax03-green-backup-full2`、`ax03-batch-data`；包含旧 payload 保留、日期/时区一致性、碰撞合并、无通知恢复、`legacy-manga:*` 行复用、v15/v16 fixture 重放 | 均 exit 0；恢复按 source+manga 限定复用旧行，未知目录不会写成真实 0 |
| 领域契约与匹配边界 | `ax03-batch-domain`；`CreatorWorkArchiveMetadataTest`、`CreatorArchiveV2ContractTest`、`WorkMatchScorerTest` | exit 0；UNKNOWN 章节数在匹配中为 null，COMPLETE 的 0 仍保留 |
| Android 生产更新接线 | `ax03-batch-android-fixed`；`UpdateMangaCreatorArchiveIntegrationTest`、`AndroidCreatorSettingsUiTest` | exit 0；章节刷新真实写入 COMPLETE 目录事实及 mangaId，作者页读取真实投影 |
| Desktop 生产更新与 UI | `ax03-batch-desktop-final`；`LibraryUpdateCheckerTest`、`SaveSourceMangaForDetailsTest`、`AuthorsProductionWiringTest`、`AuthorsScreenModelsTest` | exit 0；更新/详情保存接入目录写入，三种布局显示首次发现日期，来源窗口区分未知/部分/完整，UNKNOWN 不进入章节匹配 |
| 红绿与格式 | 有效红灯：`ax03-red-restore-valid2`、`ax03-review-red-match`；绿灯：`ax03-green-restore-fallback`、`ax03-green-unknown-match`；`ax03-spotless-final`、`git diff --check` | 红灯均为编译成功后的行为断言失败；修复后全部通过，diff 无错误（仅 CRLF 转换提示） |
| 独立审查 | `/root/ax03_review` 最终复审 | `APPROVED`；未发现恢复去重、迁移/备份兼容、未知匹配或双端 wiring 回归 |

**实现边界与维护说明**：schema 从 v28 迁移到 v29，新增冻结日期/时区、章节完整性、目录计数和最新章节时间；旧备份字段缺失时保留本地事实，恢复不创建通知。Android 与 Desktop 更新入口只记录当前目录观察结果；没有可信来源日期时仍使用本地首次发现日期，质量采样和真实来源白名单留给 AX-04。

### AX-04 · 日期可信采样与生产更新链路

- [x] AX-04：交付本地可信来源维护与质量反馈，A12–A13通过。

**用户结果**：正确日期被使用，今日回填等异常值显示待核实；无可信日期继续稳定首次发现。更新插件后不会立即信任新解析值。

**前置**：AX-03审查通过的schema/日期选择器。**入口**：既有CatalogueCreatorDiscoverySourceAdapter、SManga/SChapter结果、目录更新入口、SourceDiscoveryObservation及两端平台来源描述。

**实施内容**：

1. 注册表按扩展包/版本/sourceId/字段种类记录unknown/trusted/suspect及证据；实现设计第8节采样容量和清理规则，禁止无限增长。
2. 以章节自然键对照至少3作品×3历史章节、跨24小时稳定样本；追加章节/重排不误判。历史章节整体漂移到抓取日降级，单次同日仅未知，网络失败不降级。
3. 作品上架字段单独确认adapter语义和跨日样本；缺统一字段就回退，不能逐源抓HTML补洞。解析端默认当前时间必须标为非事实证据。
4. 接入真实现有更新/发现事务及双端DI；UI显示可信上架、可信最新章节或待核实，资格降级保留展示快照并提示依据待核实。
5. 插件升级重新取证、已有快照可追溯。使用本地响应fixture，不把演示漫画柜评级写成真实可信配置。

**红绿验证**：共享时钟参数测试＋真实repository注册表状态机；若修改HTTP/解析，MockWebServer覆盖成功、空/缺字段、403/429/500、畸形响应，执行真实adapter→观测→存储→UI投影。测试新章插入/章节重排、样本不足、全章漂移、字段隔离、插件升级、采样上限和清理不删事实。

**完成证据/边界**：Android与Desktop更新入口必须真实调用共享策略，删除wiring时测试失败。缺真实来源证据可保留unknown，但不允许把实现未接线称为“等待验证”。不得为本批爬全站或修改第三方插件。

**执行证据**：

| 范围 | 命令/记录 | 结果 |
|---|---|---|
| 日期质量策略 | `ax04-red-policy3`、`ax04-green-policy3`、`ax04-red-policy-boundary1`、`ax04-green-policy-boundary3`；`SourceDateQualityPolicyTest` | 红灯先证明类型/策略与网络失败容量、未确认语义边界缺失；绿灯通过3作品×3章节、跨24小时、漂移SUSPECT、网络失败独立诊断容量及publication semantic-confirmed门槛 |
| SQLite 注册表与投影 | `ax04-red-repository`、`ax04-green-repository2`、`ax04-red-per-work-projection1`、`ax04-green-per-work-projection3`、`ax04-red-projection-boundaries3`、`ax04-green-projection-boundaries7`；`SourceDateQualityRepositoryTest` | 真实SQLite验证扩展版本/字段隔离、网络失败不降级、按作品自然键日期、无效/未来/未确认/网络失败publication样本过滤，以及同毫秒插件升级切换current identity |
| 迁移、schema与备份边界 | `ax04-green-migration-current2`、`ax04-green-domain-data-boundaries7`；`CreatorArchiveMigration16Test`、`CreatorArchiveMigration15Test`、`AuthorArchiveBackupContributorTest`、`CreatorArchiveV2ContractTest` | migration 29 与 fresh schema 一致，v16 replay、备份/恢复及本地质量表排除通过；质量 registry/sample/current 表保持设备本地可重建，不进入同步载荷 |
| 生产更新与发现接线 | `ax04-green-android-wiring2`、`ax04-final-android2`、`ax04-final-desktop2`、`ax04-green-desktop-di1` | Android `UpdateManga`、Desktop library/detail 更新及 discovery adapter 使用真实 extension package/version、稳定作品/章节自然键和共享策略；移除 DI/wiring 时 focused 测试失败 |
| UI 与格式 | `ax04-green-ui-label2`、`ax04-spotless-check6`、`git diff --check` | Android/Desktop 来源选择窗口显示可信上架/最新章节日期、未知或待核实状态；格式与差异检查通过（仅 CRLF 转换提示） |
| 独立审查 | `/root/ax03_review` 修复复审 | `APPROVED`；未发现可复现 P0/P1/P2 风险 |

**实现边界与维护说明**：质量键按扩展包、扩展版本、sourceId及字段种类隔离；更新插件后通过 current identity 立即回到 UNKNOWN，不继承旧版本 trusted。有效日期证据与网络失败、未来值、未确认语义等诊断样本分开限额，诊断保留30天并有独立容量，不会挤掉可信样本。章节展示沿用每作品目录事实并受当前质量状态门控；上架日期只读取 adapter 明确提供且 semantic-confirmed 的作品样本，按作品自然键投影。没有统一字段、样本不足或来源失败时保持 UNKNOWN/待核实，不做 HTML 抓取、白名单评级或第三方插件修改。

### AX-05 · 新作未查看状态与提醒闭环

- [x] AX-05：交付应用内新作提醒及持久化查看，A09–A11/A14通过。

**用户结果**：关注作者有新作时提示，双图源同作只算1部；进入作者页和取消窗口不清除，成功打开任一漫画版本后清除，重启不复发。

**前置**：AX-02版本打开链路、AX-03首次发现事实；默认按顺序在AX-04之后执行。**入口**：现有discoveries/read状态、CreatorDiscoveryService、outbox、observeUnreadDiscoveries、markDiscoveriesSeen及导航成功事件。

**实施内容**：

1. 基于现有来源级事件实现共享作品级未查看投影；必要的查看持久记录与所有成员markSeen在同一事务，保留旧发现明细。明确并发新来源、同作合并和拆分规则，不能只用UI内存Set。
2. 沿用baseline，首次存量/恢复/合并不造新作；未关注不生成提醒，既有作品新增来源/章节不重新提醒。多作者总计作品去重。
3. 两端作者卡片新作数、应用内提醒和查看入口、详情新作置前及三布局标记接线。查看入口清空过滤/搜索，不触发隐式已读。
4. 只有具体漫画详情成功进入后执行作品级确认；导航失败不写，写失败保留未查看并反馈。取消窗口、返回作者、通知发送成功均不当已查看。
5. 弹窗期间发现不抢焦点/跳页，关闭后更新；多作者依次定位。取消关注抑制提醒，保留历史事实；重关注不补取消期间事件。
6. 同批扩展备份、导入/同步回放兼容和恢复去重；系统通知继续复用原outbox及平台adapter，不新增通知权限入口。

**红绿验证**：真实SQL并发/事务、baseline、2源同作1条计数、已有作品新源、已查看后迟到源、关注变化、作者/作品合并与拆分、重启/备份恢复；真实生产scheduler/job→发现→outbox/未查看投影接线；双端mounted点击和成功/失败导航；模态隔离与计数及时刷新。纯domain单测不足。

**审查关口**：查看去重、迁移与通知数据完整性须独立审查，并在AX-06之前关闭阻塞。与AX-03不同的审查里程碑须在启动执行时明确范围/预算，不无限追加轮次。

**边界**：不按标题自动判同作，不改已有系统通知中心外观，不将通知发送当已查看，不新增周期配置。

**执行证据**：

| 范围 | 命令/记录 | 结果 |
|---|---|---|
| 作品级 SQL 投影与持久化 | `ax05-red-uncanonical1`、`ax05-green-uncanonical-multiauthor1`、`ax05-red-multiauthor1`、`ax05-data-focused7`；`:data:jvmTest --tests tachiyomi.data.creator.CreatorRepositoryImplTest` | 红测分别证明无 canonical 未读标记缺失、同时间代表受 discovery id 影响；绿测通过无 canonical、稳定作者键/来源键代表、两源去重、标记已读、重启和迟到来源抑制 |
| 关注状态、outbox 与返回投影 | `ax05-green-disabled-history2`、`ax05-red-outbox-return1`、`ax05-green-outbox-return1` | 取消关注只隐藏未读提醒但保留已读历史；迟到来源不创建新 outbox；新 discovery 返回 `PENDING` delivery state |
| Android 生产接线 | `ax05-android-navigation-test3`；`:app:testReleaseUnitTest --tests eu.kanade.tachiyomi.ui.browse.author.AndroidAuthorArchiveWiringTest` | 真实 SQLite 与 Android ScreenModel 验证导航请求未被消费前保持未读，导航确认后按 canonical/source 成员事务清除；打开失败和标记失败保留状态并在详情页显示错误 |
| Desktop 生产接线 | `ax05-desktop-authors-test6`、`ax05-desktop-updates-test6`；`:app-desktop:jvmTest --tests mihon.desktop.ui.authors.AuthorsProductionWiringTest --tests mihon.desktop.updates.UpdatesScreenModelTest` | 真实 SQLite、作者详情和更新页验证作品提醒、取消关注过滤、三布局未读标记及导航确认后的清除 |
| 双端编译与格式 | `ax05-p1-p2-compile1`；`ax05-spotless-final5`；`git diff --check` | Android/Desktop/data 编译通过；Spotless 通过；diff 无空白错误（仅 CRLF 转换提示） |
| 独立审查 | `/root/ax03_review` 初审及修复复审 | 初审的无 canonical、稳定代表、取消关注和导航时序问题已关闭；修复复审提出的错误可见性与 outbox 返回时序已由本批红绿测试和双端编译关闭 |

**实现边界与维护说明**：作品级未读状态复用现有 source discovery、`read_state` 和 outbox 事实，没有新增 schema/migration 或备份字段。canonical work 使用所有当前 source member 进行事务性 `markWorkSeen`，未 canonical work 只标记对应 source work；enabled watch 过滤只影响提醒视图，已读历史仍保留。作者卡片、作者详情三种布局、Android/Desktop 更新页均读取真实 repository 投影；来源选择器关闭、进入作者页或通知投递成功不会自动清除，只有 `navigator.push` 成功后才标记。标记失败保留未读并在作者详情页显示原因。多作者代表按最早发现时间、稳定 creator portable key、稳定 source key、discovery id 选择；不按标题自动合并，canonical merge/split 继续由既有关系事实决定。

### AX-06 · 双端整合与正式运行验收

- [ ] AX-06：A01–A15全部有有效production证据，正式交付本轮迭代。

**前置**：前五批实现/审查/提交完成；无未解决迁移或通知幂等阻塞。

**实施内容**：

1. 对照冻结DEMO和设计矩阵核查真实Android/Desktop界面：关注/全部、固定顶部栏、B布局、别名、模式继承覆盖、全部视图收藏左上角、多源窗口、新作和日期。
2. 使用包含3作者、多源同作、不同收藏/目录完整性、未知/可疑日期、长名字/来源和缺插件的本地可复现场景；不能把HTML fixture测试结果当production验收。
3. 验证升级旧库、重启、备份恢复、回放、离线、插件缺失；重点核对首次日期与查看事实。保留旧高级校正、手动检查和独有功能，不以对齐DEMO为理由删除。
4. 执行对应Android/Desktop模块完整测试、必要格式、Test Mode与最终全量验收；Gradle按仓库协调器串行。已对同一diff通过等价完整Desktop测试时，可按脚本规则使用build-only避免重复。
5. Windows正式交付必须通过`scripts/build-desktop.sh`（按shell环境调用），不直接Gradle构建部署；macOS构建/运行用现有授权环境，Android生成所需产物并记录真机/离屏实际结果。缺平台环境必须真实记录阻塞，不能勾完成。
6. 报告Windows构建日志`Final unpacked EXE:`且核实文件存在，链接正式产物；临时目录不能当发布地址。安装或替换用户现有运行环境前核对授权及实例，本计划不是删除数据授权。
7. 更新适用的parity-manifest能力证据及本计划状态；如不属于该manifest范围，不为本轮建立第二套状态权威。迁移限制、真实源证据不足和平台差异写入最终说明。

**验证预算**：本收口执行范围默认完整Android/Desktop测试各1次及Windows/macOS正式构建运行各1次；仅因失败或相关新改动才追加受影响验证。上游批次focused结果有效时直接复用，不每个小任务都跑完整Desktop/release。

**交付**：production提交、设计最终差异、测试/审查证据、可点击正式构建产物和用户验收路径。所有目标平台及高风险证据满足前，本项保持未勾选；不能用“用户自行测试”替代未完成的必要自动化。

## 4. 通用红绿与证据规则

1. 每批先写覆盖真实行为或接线的失败测试，记录正确失败原因；实现最小变化使之通过，整理后复验受影响测试。
2. 测试优先执行真实repository/SQL、共享契约及mounted UI。新增Screen/Tab验证实例化和导航类型；DI初始化实际模块；HTTP变更执行原始响应解析路径。
3. 批次完成执行受影响模块单元/集成/wiring和格式检查。每阶段完整检查放在对应收口，不反复执行finalParityAudit和发布构建。
4. 子代理回执包含status/diff/tests/commit/process/next，主代理核实证据与进程；等待超时先查协调器，禁止另启动重型Gradle。
5. 一个能力批次原则上一个包含测试、production和checkoff的提交，审查修复最多再一个；仅暂存本任务改动，不为纯进度推进单独提交。
6. 用例、命令、exit code、失败修复原因和正式路径记录在对应任务证据段；不生成逐任务快照包或巨型diff。

## 5. 需求覆盖与防扩张检查

| 设计范围 | 所属任务 | 必须保持的边界 |
|---|---|---|
| 关注/全部、顶部栏、B布局与代表作算法 | AX-01 | 不做外部排名、头像或全站抓取 |
| 主名/别名/全局频率 | AX-01、AX-06回归 | 已实现能力不另造一套 |
| 三显示方式、继承与全作者单覆盖 | AX-02 | 不按作者保存，不回写书架 |
| 收藏左上角、来源chips、版本选择 | AX-02 | 保留完整成员，准确版本导航 |
| 首次日期、章数完整性、恢复 | AX-03 | 不能把刷新日期当首次时间 |
| 来源分字段可信与漂移识别 | AX-04 | 初始未知，有界采样，不虚构白名单 |
| 新作提示、查看、跨源去重 | AX-05 | 复用发现/outbox，成功进入才清除 |
| 双端一致、迁移回归、正式产物 | AX-06 | 不用DEMO证明生产已完成 |

遇到无可靠上架字段按既定首次发现回退，不扩大成逐插件开发；发现现有同作关系不足时保留独立作品，不扩大成匹配引擎重写；某来源离线不阻止本地页面交付。若实际需要改动身份规则、同步协议或删除旧数据，先停止该扩张部分并提出具体差异、成本与替代方案。
