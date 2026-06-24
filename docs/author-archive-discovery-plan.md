# 作者作品归档与新作品发现追踪方案

## 0. 中期成果记录

- 2026-06-15：完成只读代码探查，确认本功能属于规划文档产出，本轮不修改功能代码，不触发实现阶段 TDD。
- 2026-06-15：已调用子 agent 并行探查数据层、源接口、UI、自动更新路径；数据层探查已回收，UI 探查超时未阻塞主流程。
- 2026-06-15：确认现有能力边界：
  - `mangas` 已有 `author`、`artist`、`last_update`、`next_update`，但没有作者实体、作品聚合实体、漫画级语言字段。
  - `chapters` 已有 `name`、`date_upload`、`chapter_number`、`scanlator`，但没有卷、章节类型、语言、版本粒度字段。
  - `CatalogueSource` 只有通用搜索、热门、最新、详情、章节列表，没有标准“按作者查询”接口。
  - Android 自动更新走 `LibraryUpdateJob`，Desktop 自动更新走 `LibraryUpdateScheduler` + `LibraryUpdateChecker`，需要抽象共享领域服务，避免两端分叉。

## 1. 设计目标与边界

目标：

1. 新增独立“作者”页面，用户可浏览作者、查看作者作品归档、进入作品和跨源版本详情。
2. 支持用户关注作者，并在书架自动更新周期内发现该作者的新作品。
3. 对同一漫画在多个漫画源中的版本进行候选聚合与差异对比，展示章节编汇、语言、更新时间、源质量等差异。
4. 支持按漫画语言标签筛选搜索结果。

必须澄清的边界：

- “100% 自动识别漫画语言”在当前源生态下不可证明。现有 `Source.lang` 是源级语言，不等于每个漫画的实际语言；`SManga` 和 `Manga` 没有漫画语言字段，扩展返回的 metadata 也不统一。
- 可落地目标应定义为：系统为每条候选生成 `language_tag`、`confidence`、`evidence`；当证据不足时标为 `unknown` 或需要用户确认。对“已确认”记录可达到 100% 可解释准确，因为值来自明确源字段、规则证据或用户确认，而不是黑箱猜测。
- 不应在第一阶段自动合并跨源作品。先展示候选和分数，用户确认后写入持久化匹配关系；已确认关系后续自动复用。

## 2. 当前项目证据

数据模型：

- `domain/src/commonMain/kotlin/tachiyomi/domain/manga/model/Manga.kt`
  - 字段包括 `source`、`url`、`title`、`artist`、`author`、`genre`、`lastUpdate`、`nextUpdate`。
  - 无漫画级语言、作者实体、归档作品 ID。
- `domain/src/commonMain/kotlin/tachiyomi/domain/chapter/model/Chapter.kt`
  - 字段包括 `name`、`dateUpload`、`chapterNumber`、`scanlator`。
  - 无 `volume`、`chapterType`、`releaseLanguage`、`edition`。
- `data/src/commonMain/sqldelight/tachiyomi/data/mangas.sq`
  - `mangas` 表已有 `author`、`artist`，索引主要是 `favorite`、`url`、`source`。
  - `getDuplicateLibraryManga` 当前只用标题模糊和 track 远端 ID 判断库内重复。
- `data/src/commonMain/sqldelight/tachiyomi/data/chapters.sq`
  - `chapters` 表只有基础章节数据，不能表达拆分话、单行本卷、额外话等结构化差异。

源接口：

- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/CatalogueSource.kt`
  - 有 `getSearchManga(page, query, filters)`、`getPopularManga`、`getLatestUpdates`、`getFilterList`。
  - `lang` 是源级 ISO 639-1 语言。
  - 无统一作者搜索、无结果语言字段。
- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Source.kt`
  - 有 `getMangaDetails`、`getChapterList`、`getPageList`。

已有可复用逻辑：

- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchScreenModel.kt`
  - 已有全源并发搜索思路。
- `app/src/main/java/mihon/feature/migration/list/search/SmartSourceSearchEngine.kt`
  - 已有标题归一化 + Levenshtein 匹配雏形。
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`
  - Android 书架自动更新入口。
- `app-desktop/src/main/kotlin/mihon/desktop/domain/LibraryUpdateScheduler.kt`
  - Desktop 自动更新调度入口。

## 3. 目标用户流程

作者入口：

- 底部导航新增“作者”Tab，或在 Browse/Library 顶部增加作者入口。Desktop 当前底部已有 Library、Updates、History、Browse、More，建议 Desktop 新增“Authors”Tab；Android 可放入 Browse 下的二级入口，待移动端导航容量评估后再决定是否升为主 Tab。
- 漫画详情页作者/画师文本变为可点击标签：点击进入作者详情页。
- 全局搜索增加“按作者搜索”模式：输入作者名后展示作者候选、作品候选和源搜索进度。

作者列表页：

- 展示“已关注”“最近发现”“全部作者”三段。
- 支持按名称搜索、按语言筛选、按最近发现时间排序。
- 已关注作者显示新作品候选数量、上次检查时间、检查状态。

作者详情页：

- 顶部显示作者名、别名、关注按钮、手动检查按钮。
- 作品归档区按“已确认作品”“候选作品”“已忽略”分组。
- 每个作品卡片显示标题、封面、语言标签、源数量、最新更新时间、是否已在书架。
- 进入作品聚合详情后，显示跨源版本对比。

跨源版本对比页：

- 一行一个源版本：源名、源语言、检测语言、章节数、最新上传日期、更新时间、是否已收藏、可读性状态。
- 章节编汇差异用结构化摘要展示：
  - 独立话：`Ch. 1, 2, 3`
  - 拆分话：`Ch. 10.1 / 10.2`
  - 单行本卷：`Vol. 1 Ch. 1-5`
  - 额外话：`Extra / Omake / Special`
- 用户可手动确认“这些版本是同一作品”或“不是同一作品”。

新作品发现反馈：

- 自动更新后，如果发现关注作者新作品，在 Updates 页顶部显示“作者新作品”筛选段。
- Desktop 通过 `DesktopNotificationService` 发通知，Android 通过现有 `LibraryUpdateNotifier` 增加作者发现通知。
- 发现项默认不自动加入书架，用户点击后进入源作品详情，可选择加入书架、忽略、绑定到已有作品。

危险操作确认：

- 删除作者关注：普通确认对话框。
- 忽略候选作品：可撤销 Snackbar。
- 合并/拆分跨源作品关系：确认对话框，说明会影响后续自动匹配。

## 4. 数据模型方案

新增 SQLDelight 表建议放在 `data/src/commonMain/sqldelight/tachiyomi/data/`，并镜像到 `data/src/main/sqldelight/tachiyomi/data/`，同时新增迁移文件。

### 4.1 作者实体

`creators`

- `_id INTEGER PRIMARY KEY`
- `display_name TEXT NOT NULL`
- `normalized_name TEXT NOT NULL UNIQUE`
- `sort_name TEXT`
- `aliases TEXT AS List<String>`
- `created_at INTEGER NOT NULL`
- `last_modified_at INTEGER NOT NULL`

`manga_creators`

- `manga_id INTEGER NOT NULL`
- `creator_id INTEGER NOT NULL`
- `role TEXT NOT NULL`，取值 `author`、`artist`、`both`、`unknown`
- `source_text TEXT`
- `confidence REAL NOT NULL`
- `evidence TEXT NOT NULL`
- `PRIMARY KEY(manga_id, creator_id, role)`
- 索引：`creator_id`、`manga_id`、`creator_id, role`

### 4.2 用户关注

`creator_watches`

- `creator_id INTEGER PRIMARY KEY`
- `enabled INTEGER AS Boolean NOT NULL`
- `source_ids TEXT AS List<Long>`，空表示启用源
- `language_tags TEXT AS List<String>`，空表示不过滤
- `last_checked_at INTEGER`
- `last_success_at INTEGER`
- `last_error TEXT`
- `created_at INTEGER NOT NULL`
- 索引：`enabled`、`last_checked_at`

### 4.3 跨源作品归档

`canonical_works`

- `_id INTEGER PRIMARY KEY`
- `primary_title TEXT NOT NULL`
- `normalized_title TEXT NOT NULL`
- `primary_creator_id INTEGER`
- `original_language TEXT`
- `created_at INTEGER NOT NULL`
- `last_modified_at INTEGER NOT NULL`
- 索引：`normalized_title`、`primary_creator_id`

`manga_work_matches`

- `manga_id INTEGER NOT NULL UNIQUE`
- `work_id INTEGER NOT NULL`
- `confidence REAL NOT NULL`
- `match_reason TEXT NOT NULL`
- `state TEXT NOT NULL`，取值 `candidate`、`confirmed`、`rejected`
- `manually_confirmed INTEGER AS Boolean NOT NULL`
- `created_at INTEGER NOT NULL`
- `last_modified_at INTEGER NOT NULL`
- 索引：`work_id`、`state`、`work_id, state`

### 4.4 发现候选

`discovery_candidates`

- `_id INTEGER PRIMARY KEY`
- `source INTEGER NOT NULL`
- `url TEXT NOT NULL`
- `title TEXT NOT NULL`
- `normalized_title TEXT NOT NULL`
- `author_text TEXT`
- `artist_text TEXT`
- `language_tag TEXT NOT NULL`
- `language_confidence REAL NOT NULL`
- `language_evidence TEXT NOT NULL`
- `thumbnail_url TEXT`
- `first_seen_at INTEGER NOT NULL`
- `last_seen_at INTEGER NOT NULL`
- `details_fetched_at INTEGER`
- `state TEXT NOT NULL`，取值 `new`、`accepted`、`ignored`、`merged`
- `UNIQUE(source, url)`
- 索引：`normalized_title`、`language_tag`、`state`、`first_seen_at`

### 4.5 章节编汇归一化

不建议直接扩展现有 `chapters` 表作为第一步，因为会影响核心阅读流程。先新增旁路表：

`chapter_variants`

- `chapter_id INTEGER PRIMARY KEY`
- `work_id INTEGER`
- `volume_number REAL`
- `chapter_number REAL`
- `part_number REAL`
- `chapter_type TEXT NOT NULL`，取值 `regular`、`split`、`volume`、`extra`、`special`、`unknown`
- `release_language TEXT`
- `confidence REAL NOT NULL`
- `evidence TEXT NOT NULL`
- 索引：`work_id`、`chapter_type`、`release_language`

归一化失败时保留 `unknown`，UI 展示原始章节名，避免误导。

## 5. 领域层与用例

新增包建议：

- `tachiyomi.domain.creator.model`
- `tachiyomi.domain.creator.repository`
- `tachiyomi.domain.creator.interactor`
- `tachiyomi.data.creator`

核心用例：

- `ExtractCreatorsFromManga`
  - 从 `Manga.author`、`Manga.artist` 中解析作者，处理多人分隔符、空值、别名归一化。
- `GetCreators`
  - 作者列表页订阅。
- `GetCreatorDetail`
  - 作者详情页订阅：关注状态、已确认作品、候选作品。
- `FollowCreator` / `UnfollowCreator`
  - 更新 `creator_watches`。
- `DiscoverCreatorWorks`
  - 对关注作者执行源搜索、详情抓取、语言识别、候选入库。
- `MatchCandidateToCanonicalWork`
  - 为候选作品计算跨源聚合分数。
- `ConfirmWorkMatch` / `RejectWorkMatch`
  - 用户确认或拒绝跨源关系。
- `NormalizeChapterVariant`
  - 从章节名和编号推断章节编汇结构。

共享服务：

- `CreatorDiscoveryService`
  - 由 Android `LibraryUpdateJob` 和 Desktop `LibraryUpdateScheduler` 调用。
  - 输入：关注作者、启用源、语言过滤、限流配置。
  - 输出：发现数量、错误列表、候选 ID。

## 6. 源接口扩展方案

第一阶段不改扩展 API：

- 使用 `CatalogueSource.getSearchManga(1, authorName, source.getFilterList())` 作为候选发现。
- 对命中候选调用 `getMangaDetails` 获取 `author/artist/genre/status/thumbnail`。
- 有 `supportsLatest` 的源可补充 `getLatestUpdates`，但不能全量爬取；只用于最近变化的源中快速发现候选。

第二阶段增加可选接口，不破坏旧扩展：

```kotlin
interface AuthorSearchSource : CatalogueSource {
    suspend fun getAuthorManga(page: Int, author: String, filters: FilterList): MangasPage
}
```

第三阶段增加更结构化的元数据接口：

```kotlin
interface StructuredMangaMetadataSource : Source {
    suspend fun getStructuredMangaMetadata(manga: SManga): StructuredMangaMetadata?
}
```

其中 `StructuredMangaMetadata` 包含：

- `authors`
- `artists`
- `aliases`
- `language`
- `originalLanguage`
- `volumes`
- `chapterGroups`

兼容策略：

- 旧扩展不实现新接口时继续走普通搜索。
- UI 明确显示“按标题/作者普通搜索发现”或“作者接口发现”，让用户理解证据质量。
- 对 Cloudflare、超时、429 做源级退避，不因单源失败中断全局发现。

## 7. 语言识别方案

语言标签来源优先级：

1. 结构化源元数据：新接口返回 `language`。
2. 源级语言：`CatalogueSource.lang`，证据为 `source_lang`，置信度中等。
3. 站点过滤器或 genre 中的语言标签：证据为 `source_filter` / `genre_tag`。
4. 标题、简介、章节名文本检测：证据为 `text_detected`，置信度低到中。
5. 用户确认：证据为 `manual`，置信度 1.0。

实现原则：

- 每个语言值必须保存 `confidence` 和 `evidence`，不能只保存裸字符串。
- 搜索筛选默认只使用置信度高于阈值的语言标签；低置信度结果进入“可能是该语言”区域。
- UI 提供“更正语言”入口。更正后写入本地覆盖表，后续匹配使用人工值。
- 对源级多语言站点不能假定所有漫画都同语言，只能标为“来自某语言源，待确认”。

验收定义：

- 自动识别验收：对构造数据集，规则输出与预期一致。
- 产品验收：用户能看到语言标签、置信度不足时能更正、筛选时不会把低置信度结果静默当成确定结果。
- “100%准确”仅适用于结构化元数据或用户确认后的记录，不承诺对所有未知源自动完成。

## 8. 跨源作品匹配与章节差异

匹配分数建议：

- 标题归一化相似度：0.30
- 作者/画师归一化相似度：0.25
- 已确认别名/人工关系：0.25
- 章节数量与最新章节接近度：0.10
- 语言与源类型一致性：0.05
- track 远端 ID 或外部 ID 一致：0.05

匹配等级：

- `>= 0.90`：高置信候选，可默认折叠在同一作品下，但仍标记“待确认”。
- `0.70 - 0.90`：普通候选，需要用户确认。
- `< 0.70`：不自动展示为同一作品，只在“可能相关”里出现。

章节编汇归一化：

- 基于现有 `ChapterRecognitionTest` 和章节识别逻辑扩展。
- 解析目标：
  - `Vol. 3 Ch. 12` -> `volume=3, chapter=12, type=regular`
  - `Ch. 12.5` -> `chapter=12.5, type=extra` 或 `split`，取决于命名证据。
  - `Ch. 12 Part 2` -> `chapter=12, part=2, type=split`
  - `Extra/Omake/Special` -> `type=extra/special`
- 无法可靠解析时保留原始名称并标 `unknown`。

UI 对比：

- 不做自动阅读进度迁移，除非用户手动选择版本并确认。
- 章节差异页展示“该源拆分为 2 个半话”“该源按单行本卷组织”等摘要。

## 9. 自动更新接入

Android：

- 在 `LibraryUpdateJob.updateChapterList()` 完成书架更新后调用 `CreatorDiscoveryService.discoverDueWatches()`。
- 复用 WorkManager 约束：网络、电量、充电、Wi-Fi 限制。
- `LibraryUpdateNotifier` 新增作者发现通知：
  - 标题：`发现关注作者的新作品`
  - 内容：`3 位作者有 8 个新候选`
  - 点击进入作者新作列表。

Desktop：

- 在 `LibraryUpdateScheduler.runLibraryUpdate()` 中完成章节更新后调用同一服务。
- 通知走 `DesktopNotificationService`。
- 测试模式 HTTP API 增加可选状态字段：关注作者数量、发现候选数量，方便 smoke test。

限流与调度：

- 每次自动更新最多检查 N 个作者、每个作者最多 M 个源、每源最多 P 页，默认保守。
- 对失败源记录 `last_error`，指数退避。
- 用户手动“立即检查”只检查当前作者，UI 显示进度和失败源。

## 10. UI 落地位置

Desktop 优先：

- 新增 `app-desktop/src/main/kotlin/mihon/desktop/ui/authors/`
  - `AuthorsTab`
  - `AuthorsRootScreen`
  - `AuthorDetailScreen`
  - `WorkCompareScreen`
  - `AuthorDiscoveryScreenModel`
- 在 `HomeScreen` 底部 `NavigationBar` 加 `AuthorsTab`。
- 在 `MangaDetailScreen` 作者字段旁增加可点击入口和关注按钮。
- 在 `UpdatesTab` 增加“作者新作”筛选或顶部提示。

Android 后续：

- 新增 `app/src/main/java/.../ui/authors/` 或 `mihon/feature/authors/`。
- Browse 页增加作者入口；漫画详情页作者字段可点击。
- 如果新增 Tab，要补 Voyager Tab 类型测试，避免 TabNavigator/Screen 混用问题。

反馈与状态：

- 搜索/发现中：进度条 + 当前源名。
- 源失败：保留失败摘要，不阻塞其他源。
- 候选为空：提示“没有发现新作品”，展示上次检查时间。
- 关注成功：Snackbar “已关注作者，后续自动更新会检查新作品”。

## 11. TDD 与测试计划

每个实现切片严格执行 Red-Green-Refactor。

数据层：

- Red：新增 SQLDelight Repository 测试，断言作者、关注、候选去重、work match 查询失败。
- Green：新增表、迁移、Repository。
- Refactor：整理 mapper 和查询。

归一化与匹配：

- 标题归一化、作者拆分、语言推断、章节类型推断必须有纯 JVM 单元测试。
- 覆盖简繁、罗马字、别名、多人作者、空作者、额外话、拆分话、卷章节。

源发现：

- 用 fake `CatalogueSource` 做集成测试：
  - 成功返回候选。
  - 空结果。
  - 某源异常不影响其他源。
  - 重复 URL 只入库一次。
  - 语言过滤生效。
- 如果修改 HTTP 源解析，按项目政策用 MockWebServer 覆盖成功、空、403、429、500、 malformed body。

UI wiring：

- 新增 Screen/Tab 必须补：
  - Screen/Tab 实例化测试。
  - Navigator push 类型测试。
  - DI resolve 测试。
- 新增 `Injekt.get<T>()` 必须把 T 加入 DI wiring 测试。

Desktop 验收：

- `./gradlew :app-desktop:jvmTest`
- `./gradlew :test-desktop:test`
- `./scripts/desktop-smoke-test.sh`
- 完成 desktop 迭代必须使用 `./scripts/build-desktop.sh feature` 或对应脚本部署，不能直接 gradle 构建部署。

## 12. 分阶段落地计划

### 阶段 1：作者索引与关注列表

用户可见变化：

- 漫画详情页作者可点击。
- 新增作者列表页。
- 用户可关注/取消关注作者。

技术范围：

- 新增 `creators`、`manga_creators`、`creator_watches`。
- 从书架漫画建立本地作者索引。
- 不做跨源发现。

验收：

- 从任意漫画详情点击作者 -> 进入作者详情。
- 点击关注 -> 作者出现在已关注列表。
- 取消关注 -> 作者不再出现在已关注列表。

### 阶段 2：作者新作品候选发现

用户可见变化：

- 已关注作者详情页有“立即检查”。
- 自动更新后 Updates 页显示作者新作品候选。

技术范围：

- 新增 `discovery_candidates`。
- `CreatorDiscoveryService` 接入 Android/Desktop 自动更新。
- 使用普通搜索作为第一版发现方式。

验收：

- 手动检查作者 -> 看到候选作品或明确空状态。
- 单源失败 -> 其他源仍返回结果，失败源展示错误。
- 自动更新后有候选 -> 通知和 Updates 入口可见。

### 阶段 3：跨源作品聚合

用户可见变化：

- 作者详情页按“作品”聚合多个源版本。
- 用户可确认/拒绝同一作品关系。

技术范围：

- 新增 `canonical_works`、`manga_work_matches`。
- 复用并扩展 SmartSearch 标题相似度。
- 写入人工确认结果。

验收：

- 同一作品不同源版本显示在同一聚合详情。
- 拒绝匹配后不再自动合并。
- 确认匹配后后续搜索复用关系。

### 阶段 4：语言标签与筛选

用户可见变化：

- 作品候选显示语言标签。
- 作者页和搜索页可按语言筛选。
- 用户可更正低置信语言。

技术范围：

- 语言证据链和本地覆盖表。
- 搜索筛选逻辑接入 `language_tag/confidence`。

验收：

- 高置信语言可筛选。
- 低置信语言不会被当作确定值。
- 用户更正后筛选立即生效。

### 阶段 5：章节编汇差异对比

用户可见变化：

- 跨源版本页展示独立话、拆分话、单行本卷、额外话等差异。

技术范围：

- 新增 `chapter_variants`。
- 章节名解析和差异摘要。

验收：

- 构造章节 `Ch. 10 Part 1/2` 显示为拆分话。
- 构造章节 `Vol. 1 Ch. 1` 显示卷信息。
- 无法解析的章节保留原名并标未知。

### 阶段 6：可选源协议增强

用户可见变化：

- 支持新协议的源，作者搜索更准、语言标签更确定。

技术范围：

- 新增可选 `AuthorSearchSource` 和结构化元数据接口。
- 扩展加载兼容旧源。

验收：

- 旧源不实现接口仍正常工作。
- 新 fake source 实现接口后，发现服务优先使用作者搜索。

## 13. 风险与应对

- 风险：全源扫描成本高。
  - 应对：只检查关注作者；源、页数、并发、频率可配置；失败退避。
- 风险：作者名多语言别名导致漏召回。
  - 应对：支持别名表和用户添加别名；搜索每个别名但限制数量。
- 风险：误合并不同作品。
  - 应对：默认候选，不自动合并；高风险操作需要确认；保留拒绝关系。
- 风险：语言识别不可靠。
  - 应对：保存证据和置信度；低置信不参与确定筛选；支持人工覆盖。
- 风险：Android/Desktop 分叉。
  - 应对：发现逻辑放 domain/data 共享层，两端只负责调度和 UI。
- 风险：扩展生态不支持作者搜索。
  - 应对：第一阶段不改协议；后续新增可选接口。

## 14. 推荐先做的最小闭环

优先实现阶段 1 + 阶段 2 的 Desktop 闭环：

1. 作者索引：从现有书架 `author/artist` 建作者页。
2. 关注作者：本地持久化。
3. 手动检查：对启用源普通搜索作者名，候选入库。
4. 自动更新：Desktop scheduler 调用发现服务。
5. 通知和 Updates 入口：用户能看到新作品候选。

这个闭环能最快验证核心价值，同时避开“自动跨源合并”和“100%语言识别”的高风险承诺。后续再用用户确认数据反哺聚合算法。
