# 作品分类与手动双页布局同步 roadmap

日期：2026-10-11。基线：`dff19a7cf1`。设计：[完整方案](../2026-10-11-library-layout-sync-design.md)。本轮获授权实施两项能力，按日常体验验证收口，无稳定发布里程碑。

active-task: none

状态：实现、独立审查和本轮选定验证已完成；本文件随功能代码同批提交。未声明稳定发布就绪。

## 预算与职责

- 实施子代理 2 个：共享代理承担 domain/data/SQL 与主要 TDD；平台代理承担 app/app-desktop/presentation-sync 的既有入口与反馈、平台测试。主代理负责设计、接口、独立审查、整合及提交。
- LS-01 → LS-02 → LS-03 接线串行依赖；平台代理先独立准备既有入口的失败测试与文案，等共享接口稳定后接线。主代理文档、共享实现与平台测试准备可并行，production 文件不交叉写入。
- 重型 Gradle 唯一协调者为共享实施代理；平台代理提供精确范围排队，主代理读取产物独立检查，不启动竞争 Gradle。
- 独立审查 1 轮，稳定协议里程碑与最终整合属于同一轮分段检查；必要修复复审最多 1 轮。
- 每簇 focused 红/绿/重构，收口受影响共享数据、Android/Desktop 接线和格式验证；完整模块/全量/正式发布构建 0 次。
- 预计 1–3 小时；主要成本是协议兼容、真实 SQLite 初次/升级补传以及双端构建测试。环境失败先诊断，不重复启动 Gradle、不安装替代运行环境。显著新增范围、成本或验证需说明具体缺口。
- 交付：方案与本 roadmap、production/测试、同一报告中的结果与局限、Git 提交。过程日志保留协调器目录，不创建逐任务快照或巨型 diff 包。

## 实施项

本次是一个跨协议、持久化和双端入口的完整功能批次。以下任务表示依赖，不按文件拆提交；勾选须实现、必要审查、验证、提交均完成。所有实现项验收后原则上一个内聚提交，审查修复最多增加一个。

- [x] **LS-01：固定协议、值模型与兼容边界。**
  - 首先核对现有空间 descriptor/AEAD 绑定、codec、历史批次及 outbox；确定可读取旧空间、拒绝未知新值的扩展方式，不盲目全局变更 protocol version。
  - 新增作品分类完整集合和章节手动布局字段，复用既有因果 reducer；固定 USER/基线、时间与稳定引用的确定性完整值选择。
  - 分类精确名称匹配，不传本地 ID/系统未分类；布局含格式/页数/开篇/单页边界/明确配对，清空有明确定义。
  - 先写 focused 协议失败测试，正确失败后最小实现，再验证非法类型/范围/重复/互斥、因果/并发/清空、乱序/重放、旧数据读取。
  - 交付接口及命令、失败原因、通过证据。主代理检查稳定协议后进入持久化；不能把新字段伪装为旧收藏/已读绕过兼容检查。

- [x] **LS-02：实现事务捕获、远端应用与已有数据补传。**
  - 前置：LS-01 接口完成检查。范围：共享 data 的分类 repository、作品 membership、章节 pairing、journal/inbox/projector、baseline 及必要 SQL。
  - 分类真实保存/批量/收藏附带分类入队；重命名/删除捕获受影响作品的新集合。新分类采用本机默认值，复用同名分类，不删除其他本地分类。
  - 布局 CAS 保存与日志同事务；远端接收用本机新 revision，清空保留 revision，不把接收结果当 USER 回流。保留现有页数/格式保护。
  - 首次与已有同步空间升级补传冻结数据，持久幂等、重启可恢复；明确区分备份/迁移/远端与 USER。
  - 先写真实 SQLite 共享契约失败测试，再实现；用不同 ID 的两个库覆盖创建/复用/清空、重复/乱序、冲突、失败回滚、无回流、升级/重启、旧 format 与 CAS。
  - schema 如需增加，只改唯一权威并验证真实旧数据迁移；不得修改历史已发布 migration。
  - 不扩展图片传输、分类偏好、备份文件格式或来源迁移产品规则。

- [x] **LS-03：双端真实入口、同步反馈和整合验收。**
  - 前置：LS-02 稳定。复用分类和通用调整页面配对入口；不重新添加已撤销的专用开篇菜单。
  - 新同步类型进入待同步统计、运行日志及必要说明，保存/同步失败沿原反馈可见。
  - Android/Desktop 各覆盖真实入口→共享 repository→outbox，以及接收→分类/布局读取；共享逻辑通过两目标契约，原 reader 活动会话保护保留。
  - focused 回归覆盖受影响原收藏、阅读、baseline/backup、同步计数及 reader 保存恢复。UI-C1～UI-S1 按实际测试逐条绑定证据，不把只测模型说成原生点击通过。
  - 主代理完成独立高风险审查、必要集成复验；格式与 diff 检查。无具体风险不扩大至完整模块/全量。
  - 更新架构说明中“分类/手动布局仅本机”的旧边界，核对本方案与实现一致；保护既有 `docs/human/`。
  - 最终只暂存本任务改动并提交，记录 hash 和实际验收缺口；未运行构建不声称产生安装包或发布就绪。

## 证据与当前状态

- 2026-10-11：已核查 production 协议只有原五字段；分类 setter 未入同步；双页持久化 format 2 已含开篇、边界及相邻配对。已委派共享实施和平台测试两个代理，主代理编写方案和固定验收。
- LS-01 红：协调器 `sync-metadata-domain-red`，`:domain:jvmTest --tests mihon.domain.sync.SyncMetadataContractTest --console=plain`；3 项中 2 个正例因旧 codec 不识别新字段而失败，1 个负例通过。日志位于 `.gradle-coordinator/sync-metadata-domain-red.log`。这是正确行为红，不把编译失败当作 TDD 红。
- LS-01 绿：`sync-metadata-domain-green2` 中 metadata/protocol/object descriptor/safety 四个测试类共 35 项通过；双层版本保留既有传输与空间绑定。
- LS-02 红：`sync-metadata-data-red` 和 `sync-metadata-supplement-red` 覆盖缺少分类/布局 outbox、重命名删除日志、事务回滚、冻结补传、schema 升级及 crypto 版本限制的预期失败。双端入口和同步面板红见 `sync-metadata-platform-red`，Android 分类夹具修正后的有效红见 `sync-metadata-android-category-red`；编译或夹具错误不计为行为红。
- LS-02 首轮绿：`sync-metadata-data-green` 实际执行新存储契约 8 项、明文/密码空间混合 schema 加解密 1 项、原布局 repository 回归 9 项，全部通过；整条命令因测试长行格式失败，不能报告命令整体通过。此后新增的升级接线、标记回滚与旧 format 1 等值用例尚需运行。
- 独立审查为主代理同一轮分段检查，覆盖协议/AEAD、分类与布局事务、projection、冻结导入、space/generation 隔离和 backup provenance。发现的 crypto event=batch 版本限制已修正并验证。`sync-metadata-cas-red` 确认旧 format 1 等值布局会把 revision 从 1 改成 2；最小修复仅对有效旧记录归一格式，仍修复损坏记录，主代理核查通过。
- 整合阶段 `sync-metadata-core-green` 中旧 schema/暂停时钟/旧 inbox 迁移 6 项、原 baseline 2 项、crypto 1 项通过；两项旧会员测试的 `effects.single()` 假设已更新，保留收藏断言并增加同事件分类及因果父项断言。新负例夹具保留系统分类 0，真实 Git 夹具的本地/远端仓库身份已统一；这些是测试夹具修正，不算产品缺陷或行为红。
- `sync-metadata-core-green2` 的 domain 6 项、JVM metadata 14 项、会员事务 2 项全部通过，包括 format 1 CAS 修复、真实加密 HTTP Git 夹具收发、队列清零及两种操作日志。命令仍因两处格式长行失败，格式修正及双端最终 focused 验证待完成；不冒充整条命令已通过。

### 最终收口证据

以下为同一基线与本轮差异上的组合证据，不代表完整模块或仓库全量测试。

| 协调器记录 | 实际结果与复用理由 |
|---|---|
| `sync-metadata-final-focused` | **整条命令 PASS**，86 秒：domain 协议 6、JVM metadata 14、会员事务 2、Android metadata 14、Android 分类模型 action 1，共 37 项，零失败/跳过；domain/data `spotlessCheck` 通过。覆盖最终测试重构及异步等待修正 |
| `sync-metadata-platform-green` | Desktop 7、Android 配对 4、同步面板 1 共 12 项通过。命令原先因分类夹具与格式失败，已分别补验关闭；这些已通过路径后续仅有格式变动，结果复用，不称原命令整体通过 |
| `sync-metadata-format-final2`、`sync-metadata-format-final4` | 分别确认受影响 app/presentation-sync、base XML、Desktop ktlint，以及 domain/data 格式通过；最终 focused 又确认 domain/data Check。中文 XML 解析与 key 唯一性核验通过 |
| `sync-metadata-core-green` | 复用旧 schema/暂停时钟/inbox 迁移 6、原 baseline 2、明文/密码空间 crypto 1 的通过结果；后续相关 production 无变更 |
| `sync-metadata-data-green` | 复用原章节配对 repository 的 9 项通过结果；后续只修远端投影等值判定，repository 存储语义未改 |
| `sync-metadata-domain-green2` | 复用旧 protocol/object descriptor/safety 的 30 项通过结果；新增 metadata 最终由上述 6 项覆盖 |

- 主代理核对协调器退出状态、实际 JUnit XML/日志、production 差异及文档，独立审查结论通过。crypto 版本限制和 format 1 等值 CAS 问题均已关闭，无未解释的相关行为失败。
- Android 分类补验改为等待真实 `LibraryScreenModel` 新建保存任务完成，再核对分类及 outbox；不以关联表中途可见作为动作完成信号，不改 production 绕过失败。
- 最终 `git diff --check` 通过；仅在已经修改的 Desktop 文件内机械整理两处旧换行，XML 仅规范行尾并保留 6 项文案差异，没有无关全文件重排。
- 过程命令与日志保留于 `.gradle-coordinator/<key>.json/.log`，仅为本机 ignored 证据。本任务按方案内聚提交；既有未跟踪 `docs/human/` 不纳入提交。
- 完整模块测试、全量测试、正式构建、实体双设备/真实 GitHub 云同步与已安装旧 APK 原生升级验收均 **未运行**；本轮交付源码与日常体验验证结果。

### 固定 UI 验收与证据层级

| 验收 | 对应 production 测试路径 | 证据边界 |
|---|---|---|
| UI-C1 | `SyncMetadataStorageContract` 分类完整集合收发；`CategoryManagementScreenTest` 既有详情分类对话框保存 | 双库覆盖名称、不同本机 ID、创建和复用；详情点击是独立本机路径，不能合称双设备原生 E2E |
| UI-C2 | `LibraryCategoryBehaviorTest` 真实分类对话框；`AndroidCategorySyncWiringTest` 书架模型 action | Desktop 点击到 SQLite/outbox；Android 模型动作到真实仓库/outbox，非原生分类点击 |
| UI-C3 | 共享存储契约的 rename/delete/clear；Desktop 分类管理既有删除路径 | 验证作品集合及本机删除行为，不同步独立空分类定义 |
| UI-P1 | 两端 `ChapterPairing...Persistence...Test` 通用调整按钮 | 实际点击保存→日志→独立接收 SQLite→重开阅读器；不连接用户云空间 |
| UI-P2 | 共享 clear/rollback；两端既有临时默认路径 | 持久清空由 repository 契约验证；“本次使用默认”继续只是会话操作 |
| UI-P3 | 共享远端接收使旧 CAS 失败；Android 既有 CAS 冲突反馈；Desktop 既有保存失败反馈 | 分层验证接收与活动会话保护；Desktop 通用失败反馈不宣称为远端冲突的原生弹层验收 |
| UI-S1 | metadata encrypted exchange 的队列/日志；`SyncPanelContentTest` 展开队列说明 | 真实 exchange 经本地 HTTP Git 夹具传输，面板由 Compose 测试验证；无实体双设备云同步验收 |

以上映射对应的本轮测试已通过，层级与局限保留。Android JVM 使用 Android handler/Robolectric 与真实 SQLite，Desktop 使用离线 Compose 测试；本轮不生成正式安装包，不将这些证据表述为已安装产品的真机升级/交互或真实 GitHub 联机验收。
