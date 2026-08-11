# ADR-0003：作者归档 v2 数据、状态、源端口与备份契约

- 状态：Accepted
- 日期：2026-08-11
- 决策任务：`AA0-02`
- 施工路线：[`2026-08-11-author-archive-discovery-corrective-roadmap.md`](../../roadmap/2026-08-11-author-archive-discovery-corrective-roadmap.md)
- executable projection：`CreatorArchiveV2Contract.kt`、`CreatorDiscoverySourcePort.kt`

## 1. 决策

作者归档 v2 使用 shared domain/data 作为业务与持久化权威。Android 与 Desktop 只保留 source、调度、通知、deep link 和 UI adapter。任何平台不得在 Composable、ScreenModel、WorkManager 或 Desktop scheduler 中重新决定“是否为新发现”、基线、人工决定、语言优先级或 outbox 幂等性。

SQLDelight schema 与 migration 的唯一源码权威是：

```text
data/src/commonMain/sqldelight/**
```

`data/src/main/sqldelight/**` 旧镜像不参与当前 KMP 构建，已删除并由 `:data:verifySqlDelightAuthority` 阻止重建。当前生成 schema version 为 `15`；v2 使用 `15.sqm` 完成 `15 → 16` 升级。

本文有限取代以下旧设计：

- `author-archive-discovery-plan.md` 中“镜像到 `src/main/sqldelight`”的要求；
- 旧 schema 的全局唯一规范作者名、role-in-primary-key、单一 candidate state 和单值语言字段；
- 把作者发现附加到 library-success 回调的调度方式；
- 把瞬时通知 Flow 当作新作事实源的实现；
- 把 `Source.lang` 同时当查询范围、阅读语言与原作语言的规则。

## 2. 独立状态维度

以下状态必须分列持久化，禁止重新压成一个 enum：

| 维度 | 状态 | 修改者 |
| --- | --- | --- |
| watch/source baseline | `NEEDS_BASELINE / BASELINED` | 成功的源级扫描事务 |
| discovery read | `UNSEEN / SEEN` | 用户打开或显式标记 |
| review disposition | `PENDING / ACCEPTED / IGNORED` | 用户动作；metadata refresh 不得修改 |
| creator relation | `POSSIBLE / VERIFIED` | 结构化证据或用户确认 |
| work decision | `SUGGESTED / CONFIRMED / REJECTED` | 算法只能改 suggestion；人工决定需显式撤销 |
| run | `QUEUED / RUNNING / PARTIAL / SUCCEEDED / FAILED / CANCELLED` | shared orchestrator |
| outbox delivery | `PENDING / DELIVERED / FAILED / CANCELLED` | notification adapter 回执 |

`UNSEEN/SEEN` 不表达接受或忽略；`PENDING/ACCEPTED/IGNORED` 不表达通知是否投递。取消关注只把 watch 设为 disabled，保留归档、read/review、work decision 和人工语言证据。

## 3. v2 物理映射

所有新表使用 `author_archive_` 前缀，避免与 v1 原型表双写或同名冲突。

| 物理表 | 关键列与唯一键 | FK / 保留规则 |
| --- | --- | --- |
| `author_archive_creators` | `_id`；`portable_key UNIQUE`；display/sort/status/timestamps；`merged_into_creator_id`；normalized name **不唯一** | identity 默认软删除；`ACTIVE/MERGED/DELETED`；merge redirect 同表引用且禁止成环；显式 purge 才级联 |
| `author_archive_aliases` | creator、raw、normalized、source/evidence/confidence/manual；`UNIQUE(creator_id, normalized_alias)` | creator `ON DELETE CASCADE`；同一 alias 可属于不同 creator |
| `author_archive_manga_links` | manga、creator、role/order/origin/evidence；`UNIQUE(manga_id, creator_id)` | manga、creator `ON DELETE CASCADE`；role 变化 UPDATE 同一行；metadata refresh 不覆盖人工 binding |
| `author_archive_source_works` | source、stable URL、metadata、first/last/details、optional manga；`UNIQUE(source_id, stable_source_url)` | source 不设 FK，扩展缺失时保留 tombstone；manga `ON DELETE SET NULL` |
| `author_archive_source_work_creators` | source work、creator、role/order/origin/verification/evidence；`UNIQUE(source_work_id, creator_id)` | 两端 `ON DELETE CASCADE`；POSSIBLE 不产生事件；metadata refresh 不覆盖人工 binding |
| `author_archive_watches` | creator、enabled、period、lease owner/expiry、created/modified；`UNIQUE(creator_id)` | creator `ON DELETE CASCADE`；unfollow 不删除行 |
| `author_archive_watch_sources` | watch、source、baseline/generation/due；`UNIQUE(watch_id, source_id)` | watch `ON DELETE CASCADE`；source 无 FK |
| `author_archive_watch_result_policies` | watch、probable/unknown/notification policy；`UNIQUE(watch_id)` | watch `ON DELETE CASCADE` |
| `author_archive_watch_languages` | policy、BCP-47 reading tag；`UNIQUE(policy_id, language_tag)` | policy `ON DELETE CASCADE` |
| `author_archive_runs` | portable run key、watch、state/progress/truncated/error/timestamps；`run_key UNIQUE` | watch `ON DELETE CASCADE`；终态不可回到 RUNNING |
| `author_archive_source_checkpoints` | watch/source、cursor/result/backoff/error；`UNIQUE(watch_id, source_id)` | watch `ON DELETE CASCADE`；源级成功/失败独立提交 |
| `author_archive_discoveries` | watch、source work、kind/generation/read/review/first time；`UNIQUE(watch_id, source_work_id)` | watch/source work `ON DELETE CASCADE` |
| `author_archive_canonical_works` | `portable_key UNIQUE`、主标题、status/timestamps | 默认软删除，避免 decision 失去目标 |
| `author_archive_canonical_creators` | work、creator、role/order/evidence；`UNIQUE(work_id, creator_id)` | 两端 `ON DELETE CASCADE` |
| `author_archive_canonical_versions` | work、source work、confirmation/evidence；`source_work_id UNIQUE` | 两端 `ON DELETE CASCADE`；只作为有效 work decision 的事务内物化投影，一个源版本最多属于一个已确认 work |
| `author_archive_work_decisions` | source work、work、state/actor/algorithm/evidence/time/idempotency；`idempotency_key UNIQUE` | 保留不可变历史；当前投影按 `USER > RESTORE > ALGORITHM`，同一 work 的首个 RESTORE 决定不可被后续 RESTORE 改写 |
| `author_archive_language_assertions` | subject type/key、dimension/tag/confidence/evidence/actor/algorithm/withdrawn/time；`idempotency_key UNIQUE` | subject natural key + dimension 建索引；人工记录撤销而不物理覆盖 |
| `author_archive_chapter_variants` | source work、chapter natural key、volume/chapter/part/type/raw/evidence；`UNIQUE(source_work_id, chapter_natural_key)` | source work `ON DELETE CASCADE`；解析失败保存 UNKNOWN/raw |
| `author_archive_notification_outbox` | discovery、channel/idempotency/attempt/state/error/timestamps；`idempotency_key UNIQUE` | discovery `ON DELETE CASCADE`；投递失败不删除 discovery |
| `author_archive_legacy_import_state` | entity type、legacy key、canonical fingerprint、imported time；`PRIMARY KEY(entity_type, legacy_key)` | 仅用于识别旧 binary 回滚期间的 v1 增量；不进入备份，随 bridge 在 `AA4-02` 停用 |

所有引用均显式使用 FK 或记录“不设 FK、保留 tombstone”的理由。Android 与 Desktop production driver 都必须启用 `PRAGMA foreign_keys=ON`；升级、删除和恢复测试必须断言 `PRAGMA foreign_key_check` 为空。

## 4. Repository 与事务契约

1. SourceWork 以及 `(watch, source work)` 关系 upsert 返回 `Inserted / Updated / Unchanged`，不得由调用方比较时间戳猜测。
2. metadata refresh 只更新自动 metadata/assertion；不得修改 review、read、人工 decision 或人工 language。
3. source scope 在请求前应用；reading-language result policy 在详情/assertion 后应用。两者分表、分 command、分测试。
4. 新增或重新启用 source 只令该 watch/source 回到 `NEEDS_BASELINE`，不得重置其他 source。
5. 首次成功扫描只归档；baseline 后仅 `VERIFIED + Inserted(watch relation)` 可产生 discovery。
6. 已在 library/history 的项目不冒充新发现；全局已有 SourceWork 仍可对另一 watch 形成首次关系。
7. discovery 与 outbox 必须同一事务插入；注入故障时两者皆无。外部投递失败只更新 outbox，不回滚 feed。
8. 同一 watch 同时最多一个未过期 lease；过期 lease可回收。`CancellationException` 向上传播；取消后不得写成功 checkpoint、candidate、discovery 或 outbox。
9. 一个 source 失败只回滚该源事务；其他 source 可提交，run 汇总为 `PARTIAL`。
10. 算法只能写入非显式 `SUGGESTED`；`USER` 决定必须显式，`RESTORE` 只能恢复显式 `CONFIRMED/REJECTED`。每次 typed write 或 legacy import 都先追加不可变事件，再从完整历史解析有效决定，并在同一事务重建 `canonical_versions`；被更高优先级压制的事件只能留作审计，不得直接增删物化关系。优先级为 `USER > RESTORE > ALGORITHM`；同一 work 的最新 USER 生效，首个 RESTORE 在没有 USER 时生效，只有新的显式用户动作可撤销或改变既有人工结果。
11. identity merge 保留 source portable key tombstone，并写入 surviving creator redirect；事务必须折叠所有唯一关系、重映射 watch/discovery/creator relation 与 portable-key subject，且拒绝 redirect cycle。split 生成新的随机 portable key，并以用户选择的 source-work natural-key binding 表达最终归属；同名或同 alias 不触发重新合并。
12. 运行时语言标签统一由 `CreatorArchiveLanguageTag` 规范化：trim、lowercase，主标签仅允许 2–3 个小写字母，后续子标签仅允许 2–8 个小写字母或数字；空值、`unknown/und`、`BL/GL/SF` 与非法格式一律降为 `und`。migration 必须对 tag、confidence 与 evidence 使用同一个有效性判定。

`CreatorArchiveV2Contract.kt` 是上述规则的 executable projection；AA1 的 SQLDelight repository 必须复用同一 contract vectors，而不是另写一套期望。

## 5. Source port

唯一窄接口是 `CreatorDiscoverySourcePort`：

- `enabledSourcesSnapshot()`：取得 enabled source 与 capability 的稳定快照；
- `searchPage(BoundedAuthorSearchPageRequest)`：单 alias、单 source、单页、有 page limit 与 deadline；
- `loadDetails(SourceWorkNaturalKey)`：取得详情和可用的结构化语言；
- result 只允许 success、empty 或 typed failure；
- `CancellationException` 必须传播，不得包装为 failure；
- total page、alias、并发、超时、lease、baseline 和 decision 由 shared orchestrator 拥有；
- adapter 可复用 `SourceMangaSearchService.loadPage()`，不得调用无界 `searchAllPages()`；
- 不复制 `BR-01` 的 `SourceQueryReducer`、enabled-source policy 或 materialize 规则。

旧扩展没有作者专用接口时使用 `CATALOGUE_SEARCH_FALLBACK`；是否支持结构化作者/语言由 capability 显式声明，不靠类型转换失败推断。

## 6. Migration、legacy bridge 与回滚

### 6.1 `15 → 16`

`data/src/commonMain/sqldelight/tachiyomi/migrations/15.sqm` 必须是 additive、单事务可回滚迁移：

1. 建立全部 v2 表、唯一键、索引与 FK；
2. 为 v1 creator/canonical work 生成持久随机 `portable_key`；不得用 normalized name 或标题作 identity；
3. v1 candidate 映射 SourceWork，保留 first/last/details/thumbnail/review；stable URL 先 trim，空 candidate/manga URL 分别使用含 legacy ID 的稳定 fallback key；历史项进入 baseline archive，不建 outbox；
4. `(candidate, creator)` 多 role 折叠为一条关系，保留证据并按 `BOTH > AUTHOR/ARTIST > UNKNOWN` 投影 role；
5. 所有 match 先按 v1 `created_at`、manga ID 稳定追加 decision；同一 SourceWork 的 confirmed version 再由完整有效 decision history 唯一物化，rejected 只保留 decision；
6. v1 language 迁 automatic assertion；非法 tag/未知证据降为 `und/UNKNOWN`；
7. v1 watch 的 source IDs 与 language tags 分别迁 source scope 与 result policy；
8. 运行 duplicate/orphan/`foreign_key_check`；任一失败回滚，`user_version` 保持 15，v1 数据仍可读；
9. 成功后写 `user_version=16`，v2 feature gate 才可开启。

冻结 fixture 为 `data/src/jvmTest/resources/creator/creator-schema-v15.sql`。fresh v16 与 v15→v16 的表、索引、FK 和 projection 必须等价。

### 6.2 Legacy 生命周期

- 新版不向 v1 表双写。
- `AA1-02` 起提供只读、幂等 legacy import bridge；若用户短暂回滚旧 binary 并写入 v1，重新升级时 bridge 可再次吸收增量。
- bridge 以 `author_archive_legacy_import_state` 保存长度前缀 canonical fingerprint；candidate metadata 与 review 使用不同 entity fingerprint，metadata-only 回滚刷新不得修改 v2 人工 review，旧版扫描带来的 `PENDING` 不得覆盖已有 `ACCEPTED/IGNORED/MERGED`。首次启动先登记 migration 已吸收的快照，之后只应用 fingerprint 变化，避免把未变化的 stale v1 关系重新灌入用户已 split/人工绑定的 v2 状态。
- bridge 最迟 `AA4-02` 删除；删除后不再承诺旧 binary 的 Authors 写入能重新合并，回滚必须使用升级前数据库快照或备份。
- v1 物理表与 migration tooling 保留到 `AA7-02` 完成升级样本、备份兼容和观察期后再清理。
- 不安全 downgrade 不自动改 `user_version`，不删除 v2 表，不静默丢弃 portable key 或人工决定。

## 7. Backup wire contract

`Backup` envelope 冻结字段：

```text
@ProtoNumber(107) optional AuthorArchiveBackupSection
```

不复用 legacy/保留空洞 `100/102/103`。section version 为 `1`，内部字段固定为：

| 字段 | 内容 |
| --- | --- |
| 1 | version |
| 2 | creators（alias 嵌套） |
| 3 | source works（creator relation/binding 嵌套） |
| 4 | watches / source scope / result policy |
| 5 | discovery read/review disposition |
| 6 | canonical works / confirmed versions |
| 7 | work decisions |
| 8 | manual language assertions |

自然键：

- Creator 与 CanonicalWork：持久 `portableKey`；
- SourceWork：`(sourceId, stableSourceUrl)`，stable URL 由 source adapter 提供，不做全局 lowercase/path 猜测；
- Alias：`(creatorPortableKey, normalizedAlias)`；
- Watch：`creatorPortableKey`；
- Discovery：`(creatorPortableKey, sourceId, stableSourceUrl)`；
- WorkDecision：`(sourceId, stableSourceUrl, canonicalWorkPortableKey)`；
- LanguageAssertion：`(subjectType, subject portable/natural key, dimension)`。
- LanguageAssertion subject key 固定为：SourceWork `source:<sourceId>:<stableSourceUrl>`、CanonicalWork `canonical:<portableKey>`、Creator `creator:<portableKey>`；本地数据库 `_id` 不得进入该键。

creator 备份同时保存 status 与可选 `mergedIntoPortableKey`；source-work relation 保存 creator portable key、role、order、origin、verification 与安全 evidence。恢复只按 portable/natural key 合并：normalized alias 相同绝不能合并 creator；redirect 必须先做无环校验。只保存 creator/alias 而不保存 binding 无法恢复人工 split，因此不得作为 merge/split 往返完成证据。

备份不携带本地 DB ID，不恢复 run、lease、checkpoint、pending outbox 或 delivery attempt。恢复后的 watch/source 一律重新 baseline；可恢复 read/review，但不得据此重建 OS 通知。`AA1-01` 建立 field 107 section v1 的 fields 1–3，并完成 creator、alias、merge redirect、source-work binding 的 Android/Desktop production wiring；`AA1-03` 只能向同一 section 追加 field 4，后续数据任务按本节字段表追加 fields 5–8。各平台 orchestration 复用 shared contributor，不得另建 envelope 字段或按 normalized alias 恢复身份。

## 8. 性能 reference 与门槛

固定 reference Windows 环境：

- host：`G-BURST`
- OS：Windows 11 Pro `10.0.28000`
- CPU：Intel Core i9-13900F，24 cores / 32 logical processors
- RAM：31.8 GiB
- JDK：Eclipse Adoptium OpenJDK 21.0.11
- Gradle wrapper：8.14.4
- 基线 commit：`7b7b807e041f269e4ec241c524016a7de9107e83`

测量规则：每个场景使用临时 SQLite 文件；cold 为新进程首次查询，warm 先 5 次预热再测 20 次并报告 p50/p95；使用 monotonic clock；网络用 deterministic fake source；不得把 fixture 构造计入查询时间；主线程阻塞通过 production dispatcher probe 记录。命令固定为后续同名 contract：

```powershell
.\gradlew.bat :data:jvmTest --tests "tachiyomi.data.creator.CreatorArchivePerformanceContractTest"
.\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.ui.authors.AuthorArchiveStartupPerformanceTest"
```

门槛冻结为：

| 场景 | 样本 | 门槛 |
| --- | --- | --- |
| backfill | 10k manga / 20k mentions | 总耗时 ≤15s；主线程单次≤100ms；500ms 内有进度 |
| Authors 首屏 | 10k manga / 20k links / 5k creators | DB ready 后首批 p95≤500ms；无 N+1 |
| Updates 作者 feed | 100k events，首 50 条 | indexed query + projection p95≤500ms |
| 冷启动恢复 | 100 watches / 1k pending-or-unread | 同步开销≤100ms；UI 线程不跑网络/backfill |
| 取消 | 4 个并发源，至少一个慢请求 | 1s 内停止新请求并持久化 cancelled；之后无 candidate/outbox 新写入 |

默认网络预算仍为每次 10 位作者、每位 8 个源、fallback 每源 2 页、全局 4 并发、单源 20s、任务软上限 10min。AA0-02 后不得为使实现过门而放宽；若 reference 硬件或测量方式变化，必须新增 ADR 并说明用户影响。

## 9. 验证与后果

AA0-02 只冻结 contract vectors、source port、v15 fixture、authority guard 和 ADR，不声称 v2 repository、migration 或 backup production wiring 已实现。对应 production RED 在 `AA1-02/AA1-03` 首先落地；不得提交 disabled/expected-failure 测试。

代价是 v2 新增较多关系表与迁移工作；收益是人工决定、基线、新作、通知、语言和跨源聚合不再互相覆盖，并且 Android/Desktop 可以消费同一业务核心。
