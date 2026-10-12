# e63b 工作树合入主干：冲突处理交接

日期：2026-10-12。本文是未来合并时的操作依据，不是已合入主干或已通过合并后验收的声明。用户要求等待同工作树另一项研究一起整合；本文不解除该等待条件。本次仅编写文档，没有执行真正的 merge、cherry-pick、发布或产品测试。

## 1. 快照与已确认冲突

| 对象 | 本次读取的状态 |
| --- | --- |
| 来源工作树 | `D:/Codex/worktrees/e63b/mihon`，detached HEAD，产品代码顶点 `6c34c05e25`，写本文前工作区干净 |
| 本地主干 main | `49c25db9ba`；这不是远端最新状态的保证，本次没有 fetch |
| 共同祖先 | `9699618bdc9f9fcc0c07eb8c8c58c9715bac2f81` |
| 来源相对共同祖先 | 84个文件变化，含作者归属、语言/翻译组筛选、研究与版本分配 |
| 两边同时改过的文件 | 仅 `gradle/android-release.properties` |
| Git 合并预演 | `git merge-tree --write-tree --name-only main HEAD` 返回1，仅报告上述版本文件内容冲突；没有更改分支、索引或工作区。该命令会生成临时Git树对象 |

因此，下文只有版本文件是**本快照已确认的文本冲突**。其余是未来主干推进、选择性搬运或人工解决冲突时的语义检查点，不能声称它们现在已冲突。真正合并前必须重新获取两端提交号、共同祖先、文件差异和预演结果；无文本冲突不等于产品行为验收通过。

## 2. 要一起带入的提交

按来源历史顺序：

| 提交 | 内容及合并地位 |
| --- | --- |
| `c2da62a163` | 作者归属与追踪设计；后续实施状态和用户反馈优先于初始提案 |
| `292eaf5682` | 通用语言/翻译组研究和离线证据；候选设计不是已冻结公共schema |
| `bac6fa05e5` | 双端标题归属预研、来源策略、索引接线与回归 |
| `993aa6e0ee` | 双端源内语言/翻译组筛选；不能因本次主要关注作者就漏合 |
| `7d3c3d34bf` | 标题姓名进入完整作者页，作者级标题检索、详情复核与并发保护 |
| `6c34c05e25` | 移除详情设置按钮；“标题识别作者”与姓名同行，位于姓名前方 |

本文的文档提交，以及本工作树后续经过验收的研究/实现提交，也须由最终整合者确认纳入清单。不要把 `6c34c05e25` 永远当作来源最新顶点。

旧隔离分支 `codex/title-credits-main-daily-20261012` 顶点 `f598adc928`，有 `c5b5703928`、`ed64ce9822`、`f598adc928` 三个对应作者提交。它**不包含**语言筛选 `993aa6e0ee`、其研究 `292eaf5682` 或最后的详情修正 `6c34c05e25`。优先从本来源历史整体整合，不同时把旧分支与其原始提交各合一遍。若未来main已经吸收旧分支，先用 `git log --cherry-mark --left-right main...<来源顶点>` 检查补丁等价，再确定缺失提交；不能只按hash判断重复。

## 3. 当前版本文件冲突怎么处理

当前main：`versionCode=77`、`versionName=0.19.4-aex.59`；当前来源：`versionCode=79`、`versionName=0.19.4-aex.61`。这两个版本字段是一组，不交叉拼接。

本快照的解决结果应保留来源已分配的79/.61，同时原样保留双方一致的 `applicationId=app.mihon.desktop.fork` 与 `releaseCertificateSha256`。不可为消除冲突更换应用身份或证书。不要整文件套用 ours/theirs 作为通用规则。

未来若任一分支已经分配更高候选，先核对当时的版本文件、候选manifest与实际交付记录，保留最新有效的版本配对，防止降级或重复占号。79/.61对应的既有APK不含本轮最终UI和catalog合并结果；**不能用79重新发布不同APK**。需要新体验APK时通过 `python scripts/build-android.py candidate` 按当时状态分配下一候选，不在冲突处理时凭本文写死80或手工猜versionName。

## 4. 语义冲突处理表

路径均相对仓库根；发生冲突时读整段调用链、参数默认值与测试，不能只保留能编译的一侧。

| 区域/主要入口 | 必须保留的最终行为 | 不能用来“解决”冲突的做法 |
| --- | --- | --- |
| Desktop `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailComponents.kt`、`MangaDetailScreen.kt`；Android `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt`、`eu/kanade/tachiyomi/ui/manga/MangaScreen.kt` | 图源原作者保留；下方同一个FlowRow先显示“标题识别作者”，再显示可点击姓名；长姓名自然换行；只在确有社团时显示社团；无候选不出现该行；进入现有完整作者页 | 恢复“标题自动归属设置”按钮/传递槽位，或改为菜单入口、简化预览页；用解析结果覆盖原作者字段 |
| 两端 `ui/.../author[s]/CreatorTitleAttributionControl.kt`、其UI测试 | 控制器作为独立组件保留旧回归，详情入口已经移除；测试夹具仅旧控制器用例显式挂载它 | 因组件仍存在就重新接回详情；删除完整作者页功能来消除依赖 |
| `domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/TitleCreditParser.kt`、`ExtractCreatorsFromManga` | 保守解析开头署名；裸名字仅候选/明确确认上下文使用；排除语言、活动、上传者、翻译标记；个人与社团不自动当别名合并 | 泛化“所有方括号都是作者”；从社团包含人名推断二者为同一身份 |
| `CreatorIdentity`、`CreatorRepository`、`data/.../creator/CreatorRepositoryImpl.kt`、`data/src/commonMain/sqldelight/tachiyomi/data/author_archive.sq` | 用户点名形成可追溯证据；已有USER/RESTORE关系保留权威字段，幂等追加完整标题证据；POSSIBLE不静默升级；标题模式要求USER/RESTORE、VERIFIED与有效完整标记；事务拒绝已变更source/url/title快照 | SQL仅用宽松子串命中；覆盖人工关系；在事务外先创建孤立作者；凭点击自动改变图源全局策略 |
| `CreatorDiscoveryPlanning`、`CreatorDiscoveryService`、`CatalogueCreatorDiscoverySourceAdapter`、`CreatorDiscoverySourcePort` | 每个作者的titleSearch贯穿不可变计划、关键词召回与详情验证；标题模式绕过把上传者当作者的专用搜索；详情标题须匹配完整已确认名；别名、关注、来源范围、既有归档继续可用 | 只改搜索入口、详情仍按上传者匹配；把搜索命中当归属；丢失titleSearch参数或默认回退false；按同作者反推同作品/版本 |
| 同上并发、缓存、持久化 | 缓存键包含作品键和titleSearch；先读取基线再读取模式；首次切换重建静默基线，旧generation不能覆盖；重复确认不重复重置，已有outbox保留 | 合并时交换读取顺序、放宽generation检查或让不同模式共用详情缓存 |
| Android `eu/kanade/domain/DomainModule.kt`、Desktop `DesktopAppModule.kt`/`DesktopUiDependencies.kt`，恢复/同步/章节仓库接线 | 来源策略、提取器与索引器沿现有DI/持久化链共用；章节更新、备份恢复及远端投影沿实际production接线，不退回互不一致的临时提取器 | 为编译通过删新依赖、传null或让恢复链绕过归属索引 |
| `domain/.../creator/service/MangaLanguageDetector.kt`、`domain/.../source/service/SourceResultMetadata.kt`、`CatalogTitleEvidence.kt`、`CatalogResultFilter.kt` | 复用LanguageAssertionContract，区分READING/ORIGINAL；日文原标题不能否定中文译版；语言和组各自多选OR、维度之间AND；未知/冲突显式处理；翻译组与creator/circle证据空间分离 | 用有汉字或Windows中文OCR判断中文；给CreatorRole加translator；把翻译组写作者根/章节排除表；把研究置信数当已校准精度 |
| 两端SourceBrowse页面、Android ScreenModel/三种卡片布局 | 语言/翻译组是源内会话本地筛选，复用原查询和原始分页；筛后空页仍可继续原始下一页；有界加载/重试、重复raw页保护；清除恢复已加载结果；保留隐藏收藏与实时书架投影 | 把本地无匹配伪装成源无下一页、无界自动翻页、清空原查询或遗漏某个列表布局；把这套会话筛选套到作者自动追踪上 |
| `i18n/src/commonMain/moko-resources/{base,zh-rCN,zh-rTW}/strings.xml` | 合并creator与catalog_result两组资源，按key保留唯一项与一致占位符；最终creator_title_attribution_preview为“标题识别作者”（繁体/英文有对应值） | 整文件取一侧导致另一功能文案丢失；重新采用“当前标题识别结果”或用旧截图恢复旧布局 |
| 研究、roadmap和证据 | 保留历史基线与实际测试范围；初始设计和后续实现分别标明；本说明不覆盖其他任务的进度 | 把“已研究”当“已实现”，或把旧分支的测试/安装包说成最终合并结果 |

## 5. 未来整合步骤（仅供届时执行）

1. 等用户要求的另一项研究交付，读取它的最终交接/提交；检查两端status，保护未提交内容。当前来源为detached HEAD，清理工作树前应在真正整合时为最终来源顶点建立保留引用，不只记录一个可能失去引用的hash。
2. 固定来源tip、main tip与共同祖先；若要联网更新，遵守AGENTS代理规则。重新跑文件交集和merge-tree预演，记录本次真实冲突，而不是照搬本文结论。
3. 从当时main建立单独 `codex/` 整合分支和新工作树。建议命令形态如下，路径用届时确认的新空目录；这不是现在执行合并的授权：

```powershell
$sourceTip = git -C D:/Codex/worktrees/e63b/mihon rev-parse HEAD
# 检查sourceTip包含最终研究提交及本文，并确认来源工作区干净。
git worktree add -b codex/e63b-main-integration <新的整合工作树路径> main
# 在整合工作树运行；出现冲突时保留现场并按第3、4节逐项解决。
git merge --no-commit --no-ff $sourceTip
```

4. 检查 `git diff --name-only --diff-filter=U`；只暂存逐项解决的文件。不得使用整树ours/theirs或破坏性reset来清除冲突。普通合并中ours是整合分支、theirs是来源；rebase/cherry-pick语境不同，不依赖词义猜测版本。
5. 确认主干已有阅读器配对回归、双页诊断等变化仍保留。检查所有最终差异，不因只一个冲突而跳过UI/DI接线与版本身份核对。若人工解决产生新的产品行为，先补真实失败测试再实施；数据库/并发/身份等高风险冲突须独立审查。
6. 按下一节运行最小充分focused范围。通过后提交整合结果；是否推进main、打包/发布按届时用户授权办理。当前“等另一研究一起”不被本文替换为立即合并。

## 6. 合并后的定向验收

以下是未来复验清单，本次文档任务**未执行**。从真实解决的冲突选择范围，不自动跑所有模块。只有版本文件冲突时，优先执行版本/签名预检与两端用户路径focused测试；若共享协议或持久化被修改，追加对应契约。已有历史结果仅在代码及影响链未变时复用，须记录所依赖基线。

统一要求：设置AGENTS规定的UTF-8、PYTHONDONTWRITEBYTECODE和Android SDK/JDK环境；经 `scripts/gradle-coordinator.py` 串行运行，不与其他任务抢Gradle。每条测试命令限定类/方法，不把未限定模块测试当作focused。

| 冲突或改动范围 | 对应测试类（完整包名） |
| --- | --- |
| 双端标题详情（两条基础复验） | `mihon.desktop.ui.authors.CreatorTitleAttributionUiTest`；`eu.kanade.tachiyomi.ui.browse.author.AndroidCreatorTitleAttributionUiTest` |
| 源内筛选入口和空页分页（两条基础复验） | `mihon.desktop.ui.browse.CatalogResultFilterWiringTest`；`eu.kanade.tachiyomi.ui.browse.source.browse.AndroidCatalogResultFilterUiTest` |
| 作者完整页面/发现接线被改 | `mihon.desktop.ui.authors.AuthorsProductionWiringTest`；`eu.kanade.tachiyomi.ui.browse.author.AndroidAuthorArchiveWiringTest`，按实际受影响方法限定 |
| 标题解析/计划/详情模式 | `tachiyomi.domain.creator.service.TitleCreditParserTest`、`CreatorDiscoveryPlanningTest`、`CreatorDiscoverySourceModeTest`（后两者同包） |
| 作者持久化/并发 | `tachiyomi.data.creator.CreatorIdentityEditingTest`、`CreatorDiscoveryExecutorStateMachineTest`、`CreatorTitleModeReadRaceTest`、`CreatorTitleAttributionCancellationTest`（后续类同包） |
| 来源策略与批量索引 | `tachiyomi.domain.creator.service.CreatorTitleAttributionEditorTest`、`CreatorTitleAttributionSessionTest`；`tachiyomi.data.creator.CreatorLibraryIndexRepositoryTest`；`tachiyomi.data.manga.MangaRepositoryCreatorIndexIntegrationTest` |
| 语言/组规则 | `tachiyomi.domain.source.service.SourceResultMetadataContractTest`；`tachiyomi.domain.creator.service.MangaLanguageDetectorTest` |
| 浏览分页/书架投影 | `mihon.desktop.ui.browse.SourceBrowseCanonicalResultWiringTest`、`SourceBrowseFilterParityTest`；`eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceRecoveryBehaviorTest`、`BrowseSourceScreenModelBehaviorTest`（省略包名者同前一个类） |
| DI、同步/恢复索引 | 双端 `CreatorGlobalScheduleWiringTest`，以及受影响的 `SourceUpdateMemoBackupIntegrationTest`、`BackupRestoreSyncWiringTest`、同步投影契约；按当前路径与真正冲突选取，不为本说明自动扩大范围 |

基础复验示例（在整合工作树逐条执行）：

```powershell
python scripts/build-android.py check --signing
python scripts/gradle-coordinator.py run --key e63b-merge-desktop -- .\gradlew.bat --no-parallel --max-workers=2 :app-desktop:jvmTest --tests mihon.desktop.ui.authors.CreatorTitleAttributionUiTest --tests mihon.desktop.ui.browse.CatalogResultFilterWiringTest --console=plain
python scripts/gradle-coordinator.py run --key e63b-merge-android -- .\gradlew.bat --no-parallel --max-workers=2 :app:testDebugUnitTest --tests eu.kanade.tachiyomi.ui.browse.author.AndroidCreatorTitleAttributionUiTest --tests eu.kanade.tachiyomi.ui.browse.source.browse.AndroidCatalogResultFilterUiTest --console=plain
```

再检查 `git diff --check`、冲突标记、三语言资源key/占位符及受影响文件格式。历史scoped lint init脚本在忽略目录，不保证新工作树存在；应按实际修改文件重建限定范围，不复制临时lint豁免或机械执行全库spotlessApply。

手动验收（打出包含最终整合源码的体验包后执行）：

- [ ] 作品详情：原作者保留；其下为“标题识别作者 + 姓名”，不存在标题设置按钮；无社团不补占位，无候选不出现行。
- [ ] 点击姓名：进入完整作者页，可以关注、管理别名、查看作品/来源和检查新作；原始作者字段不被改写。
- [ ] 图源内以作者词搜索：打开语言/翻译组筛选，条件生效；首个原始页无匹配但仍有下一页时可继续加载；清除条件恢复原查询与结果。
- [ ] 确认版本号、证书与应用身份；在被授权的隔离运行环境验证，不自动操作用户实体设备。

## 7. 历史证据和交付边界

- 作者第二批：旧隔离分支100项focused、Android原生单路径/签名候选与Windows打包runtime证据，见[标题归属实施计划](roadmap/2026-10-12-title-attribution-preview-roadmap.md)。它不包含catalog或最后UI调整。
- 作者第三批：`6c34c05e25` 的双端11项focused有效通过，Android限定格式与diff检查通过，无新EXE/APK，无全量。
- Catalog：`993aa6e0ee` 的50项本批focused有效通过，其他回归的复用与组合证据边界见[筛选实施计划](roadmap/2026-10-12-catalog-filter-implementation.md)。没有此批独立安装包或正文OCR精度认证。
- [作者设计](2026-10-12-creator-attribution-and-tracking-design.md)与[语言/翻译组研究](2026-10-12-catalog-language-and-translation-groups-research.md)保留设计动机、反例与非目标；不得把研究里的后续提案顺带实施进冲突修复。

新日常体验包：Desktop走项目preview入口；Android走candidate并保留原签名。不得直接交付Gradle临时输出，也不得把旧包更名当成整合包。合并或文档完成不触发全量测试、版本递增、正式发布或main推进；只有届时明确的里程碑才执行对应完整矩阵。

最终合并记录至少包含：两端固定tip、采用的提交集合、实际冲突与逐项选择、验证命令/范围/结果及复用基线、新产物（若有）的源码身份、剩余限制与主干最终hash。记录到本交接文档或当时唯一整合计划，避免另建多份互相矛盾的状态报告。
