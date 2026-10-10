# 已发布数据库分叉的兼容升级

本次同步分支合入主干时发现，两条已发布分支都使用迁移编号 `40`，内容却不同。不能重排已发布迁移或假定相同 `user_version` 具有相同物理结构。

| 已发布路径 | 数据库版本与对象族 |
| --- | --- |
| 主干迁移 40 | schema 41，章节 URL 身份、章节 ID 底线、目录阶段 |
| 同步迁移 40 | schema 41，暂停时钟与暂停转换触发器 |
| 同步迁移 41 | schema 42，统计开始时点与对应暂停累计 |
| 同步迁移 42 | schema 43，拒收对象及恢复工作流 |

合并保留主干 `40.sqm`、同步分支 `41.sqm` 和 `42.sqm` 的已发布原文。新增 `43.sqm` 使用既有章节别名索引的幂等声明作为升级检查点，使已安装的同步 schema 43 也进入 schema 44 的生产升级回调，不增加无用的数据表。

Android 的 `AppModule.onUpgrade` 与 Desktop 数据库初始化均调用同一个 `DatabaseMigration.migrateAtomically`。Android 回调不再额外调用 `super.onUpgrade` 重复迁移。生成迁移、旧版修复、分叉收敛、完整性校验及最终 `user_version` 更新在同一事务中执行。

迁移先执行至 schema 41 边界，再检查章节身份族及暂停族。对象族完整存在时保留其数据，完全缺失且符合已发布分支形状时补齐；章节族复用生成的迁移 40 DDL。暂停族仅允许 schema 41 的合法全缺状态补齐，schema 42 或以上丢失整个暂停族不能据此重建已遗失的时钟。部分存在、列或主键/外键不符、章节 ID 底线低于现存最大 ID、统计列不成对等状态均拒绝升级。

收敛后继续执行未修改的迁移 41、42 及新增检查点。若后续 DDL 或兼容检查失败，回滚本轮新增对象与数据修改，保留原 `user_version`，向既有存储故障处理链路报告；不把失败误报为升级成功，不清空用户数据，不根据异常重新创建数据库。

对应验证采用真实已发布 SQL 快照与 SQLite：共享 `PublishedSchema40ConvergenceContract` 供 JVM/Android 两个 target 执行，Android `AndroidLegacySyncMigrationTest` 另验证真实 production DI/升级回调。覆盖两个 schema 41 形状、同步 schema 42/43、章节别名及 ID 底线、暂停统计数据保留、损坏对象族拒绝和后续失败的事务回滚。最终执行结果记录在本次集成说明中。
