# 多设备同步技术方案（已通过审核）

- 日期：2026-09-13
- 状态：2026-09-13 用户审核通过，最终 DEMO 已确认并授权生产实施；当前执行[实施计划](roadmap/2026-09-13-multi-device-sync-implementation.md)，尚未完成产品接入与发布验收。
- 依据：本任务最新要求、[当前 DEMO 说明](prototypes/multi-device-sync/README.md)、仓库代码及文中官方资料。
- 代码核对基线：a2d870e32；工作区另有无关改动，本方案不修改这些实现。
- 证据边界：已经核对源码与公开协议；尚未进行真实私库并发写入、最低 Android 版本、加密互通、耗电及大数据性能试验。下文明确区分“已证实”和“建议设计”，参数是待验证起点。
- [原需求草案](2026-09-12-multi-device-sync-requirements.md)中的“更新/同步平级页签”已被后续设计取代。界面以书架子面板为准；第14节决策组合已随本方案通过审核。源码核对和待实测边界仍按本文记录，不因审核通过而视为实现完成。

## 1. 推荐决策

### 2026-09-13 后续确认的交互修订

用户已确认以下调整，并要求先应用到 HTML DEMO。本文后续涉及手填 PAT、三行状态及人工冲突选择的旧描述均由这里覆盖；其余架构边界继续有效。[交互原型](prototypes/multi-device-sync/README.md)只作为交互基准，真实产品实施进度以[roadmap](roadmap/2026-09-13-multi-device-sync-implementation.md)为准。

- 授权采用 GitHub App 设备码流程：打开系统浏览器登录并授权，由应用接收结果；用户不手填或维护访问令牌。首次仍需完成专用私有仓库授权及恢复资料保存/导入，GitHub 登录不替代解密密钥。原型使用本地模拟授权页，不连接真实账号。
- 只提供“立即同步”，同时交换双向变动，按钮位于单行状态右侧。定期同步开启且空闲时，主文案显示“XX天XX小时XX分后同步”并省略零值单位；关闭时显示待同步总量/同步结果，同步中与失败有相应反馈。小字显示收藏与关注、阅读记录明细。取消待确认独立于上传和接收，不阻塞其他有效变动。
- 待处理列表与角标只计算有效取消收藏/关注。“在此设备取消”“保留在此设备”沿用各接收端独立决定，后者不产生反向 ADD；批量冻结和恢复规则继续有效。
- 有因果先后的操作采用有效后继；无法判定先后的并发收藏/取消、关注/取消，自动优先保留收藏/关注；章节读完与并发明确未读，优先明确未读。后续明确操作仍可改变结果。既有书架/备份不是新的收藏操作。
- 阅读会话保持当前页；下一次打开按统一规则选择最近续读记录，其他位置保留在历史，不弹强制冲突选择。真实时间无法可靠排序时需定义稳定后备规则，不承诺恢复离线多端的绝对操作顺序，也不能永久取最大页码。
- 来源缺失时保留接收记录并自动重试，作者同名但身份不同则保留独立来源记录；不强制用户在同步时匹配，也不猜测合并身份。处理结果可从同步记录查看。

这些规则改变了旧方案的人工冲突裁决，需要在生产实现前补齐共享契约测试与边界验证。DEMO 仅表现入口、选择和反馈，不为适配这些规则修复其内存模型。

推荐采用：**共享 Kotlin 同步核心 + 本地事务操作日志 + GitHub 专用私有仓库 REST 传输 + 接收端分别确认取消**。

Git 负责保管和交换操作批次。收藏、关注、阅读的位置关系及冲突由客户端共享规则解释。生产客户端不直接照搬 HTML 的内存模型，也不通过“比对两个设备当前书架的差集”生成取消。

第一版建议：

| 项目 | 建议 |
| --- | --- |
| 客户端 | Android、Windows、macOS，共享业务和协议；首批实机验证至少 Android 与 Windows，macOS 发布前单独验收 |
| 服务商 | GitHub；接口保留 provider 边界，Gitea/GitLab 后续分别适配 |
| 用户设置 | 浏览器完成 GitHub 设备登录，授权并选择专用私库，保存或导入同步恢复资料；不手填令牌 |
| 数据 | 收藏/取消收藏、关注/取消关注、章节已读/明确未读、续读位置、最近阅读摘要 |
| 不同步 | 阅读模式及显示偏好、下载文件/队列、扩展与登录凭据、作者扫描策略、整套应用设置 |
| 初始导入 | 当前已有数据合并导入；历史明确取消不被旧书架无条件覆盖 |
| 触发 | 手动、启动后异步、默认每小时尽力调度；不做逐次翻页网络上传 |
| 隐私 | 建议首期使用客户端加密；令牌与数据密钥分开、每台设备安全保存 |
| 长期维护 | 首期保留完整操作历史；先验证规模，禁止未经协议设计自动删除旧取消记录 |

优点是复用现有业务与 HTTP 链路，不需要用户安装 Git，也不依赖任意一台设备持续在线。主要成本是可靠地捕获所有用户操作、处理因果冲突、补充安全配置及恢复流程；Git 接口本身不是工作量最大的部分。

## 2. 本轮用户体验基线

- 书架顶栏提供同步按钮。后台执行时旋转；有手动事项时显示数量；其余仅显示普通图标。旋转和数量可以同时存在，不使用感叹号或紧迫性提示。
- 更新底部导航只显示漫画更新提示。
- 同步是书架的子功能，使用当前 DEMO 的高位底部面板。设置进入同一面板内的子页面，用返回按钮返回列表。
- 列表标题是“待手动处理的同步”。多选操作条位于条目列表直接上方；支持长按、全选、反选、按范围选择和一键全部处理。批量仅覆盖取消收藏/关注。
- 顶栏计数按需要用户作出决定的事项计算；同一对象同一字段的冲突合并为一个决定，不同时重复计为取消和冲突。
- “同步状态”使用一行，主文案显示运行状态或下一次同步倒计时，小字显示收藏/关注与阅读的待同步明细，右侧提供“立即同步”。数量按本设备待上传操作信封计算，不是已有收藏或历史总量；分类之和等于总数。
- 上述计数仅包含本机待上传业务事件，空间清单、设备登记和加密元信息等协议控制数据不计入。本机事件尚不能完成身份编码或正在上传时仍计入；已收到但缺来源的远端事件保留并自动重试，不计入待上传或取消待确认角标。
- 上传成功只清除已经确认发布的那批操作。同步期间新增操作保留；离线、授权失败及结果未知均不能提前清零。
- 本轮结果只在本次打开的面板内显示；收起后不回放旧通知。持久化错误详情和待处理项保留在状态/详情中，不依赖临时通知。
- 两端并列、深色网页背景和展开的演示栏只是 HTML 预览方式，不增加生产应用的设备实验台。

建议把“源待安装、身份待匹配、阅读位置选择”也纳入手动事项，但不要让纯网络失败额外增加顶栏第四种状态。未配置时普通同步按钮进入配置引导。

## 3. 现有代码能复用什么

以下为已核对的源码事实；新增符号名均是建议名称，并非宣称已经存在。

| 能力 | 已有入口/权威 | 接入方式与限制 |
| --- | --- | --- |
| 数据库与事务 | [DatabaseHandler](../data/src/commonMain/kotlin/tachiyomi/data/DatabaseHandler.kt)、data 的 Android/JVM SQLDelight driver | 同一数据库事务提交业务变更和同步事件；复用当前 schema authority，不建第二套业务数据库 |
| 漫画状态 | [UpdateLibraryMembership](../domain/src/commonMain/kotlin/tachiyomi/domain/manga/interactor/UpdateLibraryMembership.kt)、[UpdateManga](../domain/src/commonMain/kotlin/tachiyomi/domain/manga/interactor/UpdateManga.kt)、[MangaRepositoryImpl](../data/src/commonMain/kotlin/tachiyomi/data/manga/MangaRepositoryImpl.kt) | 收藏事务能力已有，但部分批量/浏览入口走通用更新。收敛到同一用户语义入口；不能把所有 metadata update 都变成同步事件 |
| 阅读进度/历史 | [RecordReadingProgress](../domain/src/commonMain/kotlin/tachiyomi/domain/reader/interactor/RecordReadingProgress.kt)、[SqlDelightReadingProgressRepository](../data/src/commonMain/kotlin/tachiyomi/data/reader/SqlDelightReadingProgressRepository.kt) | 已有幂等事件、章节状态及可选历史的事务能力；Android 当前 recordHistory=false，历史由 ReaderViewModel.updateHistory 单独 UpsertHistory。需协调真实调用点，不能只追加一个回调就宣称全部历史被捕获 |
| 手动已读/未读 | [SetChapterReadStatus](../domain/src/commonMain/kotlin/tachiyomi/domain/chapter/interactor/SetChapterReadStatus.kt)及 ChapterRepository | 覆盖章节多选和批量路径，明确记录未读意图；接收应用不能触发本地下载删除等附带行为 |
| 作者关注 | [CreatorRepositoryImpl](../data/src/commonMain/kotlin/tachiyomi/data/creator/CreatorRepositoryImpl.kt)、[作者归档契约](architecture/adr/0003-author-archive-v2-contract.md) | 复用 watch enabled 与 portable_key；取消只停用关注，保留归档。扫描周期和发现通知 outbox 不混入同步队列 |
| 定期任务 | [Android LibraryUpdateJob](../app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt)、[Desktop LibraryUpdateScheduler](../app-desktop/src/main/kotlin/mihon/desktop/domain/LibraryUpdateScheduler.kt) | 复用各平台注册、约束及生命周期模式；新增独立同步任务，不等待漫画检查成功后才同步 |
| HTTP | [Android NetworkHelper](../core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt)、[JVM NetworkHelper](../core/common/src/jvmMain/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt) | 通过现有 DI 注入生产 HTTP 客户端，连接测试与日常同步共用代理/TLS链路 |
| 凭据 | [DesktopCredentialStore](../app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopCredentialStore.kt) | Desktop 添加同步专用 namespace；Android 本轮未发现可直接复用的同等安全存储，需要平台 adapter |
| 备份 | [BackupCodec](../data/src/commonMain/kotlin/tachiyomi/data/backup/BackupCodec.kt)、[AuthorArchiveBackupContributor](../data/src/commonMain/kotlin/tachiyomi/data/backup/AuthorArchiveBackupContributor.kt) | 复用身份和映射规则；备份恢复是专门的来源类型，不伪装成刚刚发生的用户操作 |

现有 mangas/chapters 的 version、last_modified_at、is_syncing 能帮助维护状态，但没有完整表达用户意图、接收端确认和跨设备因果关系，不能直接作为本方案的操作协议。

接入时必须核对：漫画详情、书架批量、浏览快速收藏、作者页、章节批量、阅读器、迁移、备份恢复。不能仅在按钮点击后调用“记日志”，否则事务失败或其他入口绕过时会漏记。

具体遗漏风险：Android LibraryScreenModel 的批量取消和 BrowseSourceScreenModel 的收藏、Desktop LibraryScreenModel 的批量取消仍可绕开详情所用的 UpdateLibraryMembership；迁移通过 updateMembershipsAtomically 修改成员关系。作者 followCreator 还会写 source/language/policy，因此远端接收须复用或抽取仅修改关注状态的事务能力，不能调用带空默认参数的关注流程清空本机扫描设置。

## 4. 共享架构与事务边界

~~~mermaid
flowchart LR
    UI["Android / Desktop 用户操作"] --> UC["现有共享 Use Case"]
    UC --> TX["同一数据库事务：业务更新 + 操作日志"]
    TX --> Q["本机待上传队列"]
    TR["手动 / 启动 / 定期"] --> CO["共享 SyncCoordinator"]
    Q --> CO
    CO <--> AD["GitHub REST adapter"]
    AD <--> REPO["专用私库"]
    CO --> IN["持久化接收日志 / 校验依赖"]
    IN --> RULE["共享合并与确认规则"]
    RULE --> DB["现有业务表 / 待手动处理表"]
    DB --> UI
~~~

建议 domain/commonMain 放协议模型、对象身份、冲突规则、SyncCoordinator 及窄接口；data/commonMain 放 SQLDelight journal、收件、游标和业务投影。Android/JVM adapter 承接 HTTP、安全存储、调度和平台 UI，沿用 Injekt 与 Voyager。Android/Desktop 不分别重写合并算法。

核心写入方式：

1. 明确来源：USER、REMOTE_SYNC、INITIAL_IMPORT、BACKUP_RESTORE、MIGRATION、METADATA_REFRESH。来源由具体调用链传递，不使用进程级全局“正在同步”开关；迁移需单独协调对象绑定，不把机械ID替换解释成取消。
2. USER：业务更新与不可变事件在同一 SQLDelight 事务内提交；任一失败，两者均回滚。
3. REMOTE_SYNC：收件状态、业务投影或待确认项及幂等回执同事务更新；不生成新的用户 outbox，也不触发远端指令没有包含的删除下载等行为。
4. INITIAL_IMPORT / BACKUP_RESTORE：走后述基线协调，不逐字段伪造 USER。
5. METADATA_REFRESH：保留正常刷新，不因标题、封面、章节抓取或源缺失生成取消。
6. 冲突中的“保留本地并同步”属于新的显式 USER 决定，即使最终 favorite 值没有变化也应记录；“忽略本次取消”只是本端处理回执。

事务原子性建立在现有 SQLite/driver 正确配置上，不能据此保证硬件损坏永不丢数据。后续故障注入应覆盖进程中止与实际 SQLite 配置。[SQLite 原子提交说明](https://www.sqlite.org/atomiccommit.html)

## 5. 协议：传播操作，保留先后关系

### 5.1 事件字段

| 字段 | 用途 |
| --- | --- |
| protocolVersion / spaceId / generation | 协议版本、同步空间和历史代次隔离 |
| actorId / actorEpoch / seq | 安装身份、设备实例代次、事务内单调序号；组合为全局事件 ID |
| category / effects[] | 操作信封的收藏、关注或阅读分类；包含一个或多个不可变字段效果，outbox 按信封计数一次 |
| effects[].effectId / objectKey / field | 信封内稳定效果ID、漫画收藏、作者关注、某章阅读状态、某书续读位置等具体字段 |
| effects[].kind / payload | ADD、REMOVE、READ_STATUS、RESUME_POSITION、READING_SUMMARY 等明确语义；白名单字段 |
| effects[].parents | 该对象字段的已知因果前驱集合，引用 (opId, effectId)，用于证明先后 |
| origin / importId | 用户、首次基线、恢复来源；导入幂等 |
| occurredAt | 界面解释和历史展示，不能作为冲突裁决的唯一依据 |
| batchId / contentDigest | 批次与内容一致性检查，重试不改变同一事件内容 |

actorId/epoch 不复用系统硬件标识。恢复、克隆或重装不能沿用旧写入身份继续产生相同序号；已有未上传事件保留原 ID，新的事件使用新 epoch。源 ID 等 64 位标识采用规范字符串编码，避免跨实现数值精度丢失。

下文“事件”指不可变操作信封，“字段效果”指其中一个 effect。信封整体校验、上传和去重；因果头、投影与接收端决定按 effect 保存。一次阅读可包含已读、续读、历史三个效果：已读和历史可先应用，续读留给下一次阅读会话，上传计数仍为一条。坏信封整体隔离；完整有效信封内的取消确认不阻塞其他独立字段。

### 5.2 因果与冲突

建议使用对象字段上的事件前驱图：新事件引用其已知前驱；有祖先关系就能证明先后，无祖先关系就是并发候选。Git 提交先后只说明上传顺序，不能替代业务因果。因果跟踪可参考逻辑时钟研究，但本方案不是声称引入某个 CRDT 库后即可解决所有确认规则。[Dotted Version Vectors 论文](https://arxiv.org/abs/1011.5808)

- A 收藏 → A 取消 → A 重新收藏：最后收藏因果上覆盖前两者；迟到的旧取消失效。
- A、B 离线对同一字段作出相反操作：保留两个有效头；收藏/关注并发时优先保留，读完与明确未读并发时优先明确未读。自动计算结果，不创建要求用户解决的同步冲突事项。
- 同方向并发操作可合并展示，因果头仍保留，供后续决定引用。
- 自动选择结果不伪造新用户操作。用户此后作出明确操作时，引用当前已知有效头作为前驱；再收到未被覆盖的并发操作时重新计算结果。
- parents 缺失时先持久化为待依赖，不猜测前后关系；依赖就绪只要求前驱已经持久化、校验通过且因果链完整，不要求前驱已被本端确认或应用。后继重新收藏可直接参与有效头计算，使尚待确认的前驱取消失效。
- 把“已下载收到的版本”“已安全投影的版本”“当前阅读会话已采用的版本”分开，不能一接收到远端页码就让本地阅读会话隐式采用它。
- 取消待确认不阻塞其他漫画、作者或章节，也不阻塞已验证的后继；只有未知、损坏或因果链不完整的前驱阻塞相关字段效果。

### 5.3 本地持久化

建议新增以下逻辑表，实际 migration 编号在实现时依据仓库最新 schema 分配：

| 表 | 必须保存的内容 |
| --- | --- |
| sync_spaces / sync_actors | 仓库身份、spaceId、generation、实例序号、初始导入状态；不存明文密钥 |
| sync_events / sync_effects | 不可变操作信封和字段效果；opId及(opId,effectId)分别唯一，每效果保存因果依赖和处理状态 |
| sync_outbox / sync_batches | QUEUED、IN_FLIGHT、PUBLISHED 状态；冻结事件集合、密文及摘要 |
| sync_inbox / sync_cursors | 已持久化区间、缺口、批次完整性、已处理状态；收件与应用游标分开 |
| sync_object_heads | 每对象字段有效头、投影版本和冲突集合 |
| sync_decisions | 当前设备对指定字段效果的确认、忽略、失效状态 |
| sync_local_history_suppressions | 本机清除历史的显示屏蔽水位/效果集合；不上传为全局历史删除 |
| sync_object_bindings | 远端身份与本地数据库 ID 对照、缺源/待匹配状态 |
| sync_runs | 三种触发的运行结果、分阶段计数和可恢复错误 |

网络采用至少一次传输，本地依赖唯一键与事务保证同一事件不重复生效；不承诺网络层 exactly-once。

S3b1 的实现约束：`sync_batches` 使用 OPEN / SEALED / PUBLISHED；`sync_outbox` 在得到发布确认前保持 QUEUED，不依赖一次进程内的 IN_FLIGHT 状态恢复。读取待上传批次时在事务内封存 OPEN，之后的用户操作进入新批次。准备阶段把 batch、不可变 index、actor head 的全部密文字节保存到同一 `prepared_upload` 字段，首次保存者确定制品；后续准备或重启只复用已保存内容。保存失败不进行网络写入，UNCONFIRMED 保留队列。发布确认还须匹配空间、仓库、批次、序号范围及已保存制品，在同一事务内只标记该批事件；同步期间新产生的事件不受影响。

批次的 `objects` 是参与认证的重建描述，不是额外用户操作或待上传计数。新操作在业务事务内捕获漫画、章节和作者的描述，同批同身份保留首次捕获值；阅读事件同时携带章及父漫画描述。显示名称/作者/扫描组最多 4096 字符、缩略图地址最多 8192 字符，仅截取同步描述，不修改本机资料或身份 URL。切批计算完整 UTF-8 编码（含描述及 JSON 分隔符），同时满足 256 事件和 512 KiB 上限。读侧拒绝重复、无关联、未知字段及不合法身份。未发布的早期开发批次允许没有描述；迁移保留其原始事件，不伪造名称或改写操作身份，缺少重建材料由后续接收/初次合并流程处理。

空描述字段继续省略，保证早期开发批次在上限处也保留原编码；非空描述计入字段头的额外字节。阅读描述存在时，章节的类型、来源和父漫画必须与承载阅读效果的对象一致。空白显示名只在同步描述中使用原 URL／作者 portable_key 作稳定兜底，非有限可选章号省略；旧表中重复的自然键按最低本机 ID 确定性读取描述，不合并、删除或改变按 ID 执行的本机操作。

## 6. 漫画、章节与作者身份

- 漫画首先按来源身份 + 原始稳定漫画 URL 对应，数据库自增 ID 只在本机使用。规范化应沿用已有源规则，不任意删除查询参数或仅按标题合并。
- 章节使用漫画身份 + 该源章节稳定 URL。章节号、名称、扫描组只是辅助说明，不能作为唯一键。
- 作者复用 portable_key 和已有 merge redirect；随关注携带最低限度的作者信息与可验证关联。同名但身份不同的作者保留独立记录，缺来源时保留输入并自动重试，不强制人工匹配，不复制整套作者扫描配置。
- 阅读未收藏漫画时创建最低限度的本地对象以承载历史/位置，favorite 保持 false。
- 缺少扩展或登录条件：操作进入 inbox、身份可保留；能建立准确记录的收藏可显示不可用来源，无法核对的章节/作者保留待匹配，不自动安装扩展。
- 本地文件和跨源迁移首期不做基于名字/章节号的猜测匹配。保留远端引用，身份无法定位时记录原因并等待条件恢复；迁移产生的本地 ID 变化不应被误记为一批取消和重新收藏，不在同步流程强制用户关联。

## 7. 取消、批量与阅读规则

### 7.1 各接收端分别确认

来源端按现有交互完成取消，记录 REMOVE。接收端实际已收藏/关注且取消有效时，创建持久化待处理项并保留原状态。

确认：在事务中重新校验有效头和对象状态，然后应用本端取消。忽略：持久化对此事件的本端忽略，保留本端状态。二者均不代表其他设备确认，也不自动生成反向 ADD；所以允许用户保留设备差异。

对象已经未收藏/未关注时，记为无需变更，不制造无意义确认。新设备首次重放整个历史时，先求每字段当前有效结果，再与其本地状态协调，不能先把历史 ADD 全部加入、再要求确认早已发生的 REMOVE。

旧取消被因果后续收藏覆盖后，原待处理项变为失效。点击确认时必须再次校验，避免列表展示和点击之间有新操作到达。

多选和“全部处理”冻结当时的事件 ID 与版本集合。执行前显示取消漫画/作者数量；新到达项不偷偷加入。长列表按数据库查询分页并使用 LazyColumn，不加载全部详情到内存。大批次按有界事务分段，显示实际完成/跳过/失败数量；退出后保留已提交处理结果，恢复不能再重复取消。

### 7.2 阅读

- 章节阅读状态与当前续读位置分别同步。不同章节已读可独立合并；明确标为未读与并发读完时自动保留明确未读，后续有效读完仍可改变结果。
- 续读位置使用章节稳定键和零基页索引；保留可取得的总页数/内容版本提示。用户重读早页合法，不采用永久“最大页码胜出”。
- 已有阅读进度提交点追加事件，不额外在每个滚动像素生成日志。同一幂等阅读事件不重复记；已持久化的有效事件不因准备上传而丢弃。
- 推荐首期沿用 DEMO 的计数口径：按待上传事件条数计数，而非去重后的漫画数。一次阅读领域事件可携带已读变化与历史摘要，计数一次，避免同一持久化回调被重复算成三条。
- 接收端正在阅读时：更新下次续读候选，保留当前显示页、会话因果基线和模式。下一次进入阅读器先按因果后继，再按可用时间和稳定 ID 后备规则选择位置；其他位置保留在历史，不弹强制冲突选择。
- 章节定位成功但页码无法验证时，提示从章首继续；不自动跳到相似章节。
- 首期最近阅读摘要只支撑历史列表与续读。完整逐次时长、历史删除、书签等不隐含纳入。“本机清除历史”记录独立显示屏蔽水位/效果集合，重复收件和重建投影均尊重该屏蔽；新发生的真实阅读才可重新显示。该屏蔽不上传为全局历史删除，也不抹除远端日志。
- 接收阅读事件不增加本机实际阅读时长，不重复触发第三方追踪或下载删除；读过记录和本设备会话统计分开。
- 建议无痕阅读不生成同步 outbox：当前 Android 无痕不写进度，Desktop 可写本机进度但不写历史，需要同步共享契约屏蔽无痕上传，并保持各端现有本地表现。关闭无痕不能把之前被屏蔽的动作追补上传。

## 8. 首次启用、恢复与历史

已有数据并没有完整用户操作史。建议初次启用时明确展示导入范围，冻结一个数据库基线及水位；水位之后的新动作照常记录，导入可分批和断点续传。

基线只表示“启用时已有的东西”，优先级低于明确用户决定：

1. 远端没有该对象历史：双方已有收藏/关注并集导入，空设备不产生取消。
2. 远端当前有效头包含尚未被因果后继覆盖的取消：旧本地收藏/旧备份不覆盖明确用户操作，也不能以刚下载到的版本为 parents 伪装成新的重新收藏。接收端本来已收藏时仍沿用取消确认；用户主动重新收藏才记录新的 USER。历史取消已被有效重新收藏覆盖时，按当前收藏合并，不额外要求恢复确认。
3. 同一 importId 的基线重试幂等；退出重进不重新制造导入。
4. 已接入设备恢复旧备份：记录恢复来源，不把恢复的每个字段转换为 USER。恢复数据采用基线优先级；同步自动协调远端历史，后续明确用户操作正常记录，不增加同步冲突处理入口。
5. 普通备份不携带可被克隆继续写入的 actor 身份、凭据或自动批准其他设备的决策；为保留本端忽略状态，未来同步专用恢复资料须区分“同设备恢复”与“新设备加入”。首期至少保证正常重启不丢决策，不能承诺普通旧备份包含尚不存在的同步信息。

基线的低优先级与上传先后无关：水位之后的明确 USER 决定不能被迟到的导入基线覆盖。

实现约束（S3c2）：两端原生备份恢复通过共享 `BackupRestoreSync` 包装真实业务恢复。每本漫画、作者归档分别在原业务数据库事务中冻结实际成功写入的正向数据和描述；失败单元回滚，已完成单元可继续分段进入 outbox。`sync_restore_runs` 保存完成、部分完成、取消或失败，`sync_restore_units` 防止同一恢复内重复导入；应用退出后已提交的基线仍可继续处理，不承诺自动重新打开原备份文件。新操作与恢复来源分离，恢复中切换同步空间不会把该次基线转投新空间。

普通 `.tachibk` 不导出同步表，偏好导入与导出都排除 `Preference.isAppState`；同步凭据、写入身份和本机决定不能通过普通偏好备份克隆。本次普通数据恢复保留当前安装已有的写入身份，不能从备份取得另一安装的身份；原始数据库复制或同步专用恢复须走独立身份续接规则。只恢复设置不启动数据基线。

本机历史屏蔽由 `HistoryRepository` 的三种清除入口在同一事务中保存目标与已观察的各空间/actor/epoch 序号水位。已观察的旧事件、基线事件及重复投影不能重新显示被清除历史；新的本机阅读和水位之后的 USER 事件可显示。对从未观察过的 actor，无法仅凭本机水位判断其操作的真实先后，因此其 USER 事件仍可显示，不能据此承诺所有离线操作的绝对时间顺序。普通备份恢复保留清除后已经可见的新阅读时间，不以旧备份时间覆盖它。屏蔽和最后可共享阅读值只保存在本机，不上传为全局删除。

首期远端不自动清理操作日志和取消 tombstone。本地保留已见远端水位与摘要，发现仓库回退、批次删除或同 ID 不同内容，暂停受影响同步，保留本地队列。

后续若增加 checkpoint/压缩，必须保留有效因果头、取消事实、未解决冲突和每设备确认边界，并定义旧 generation 设备重新加入；不能仅打包一份当前书架。长期离线设备回归需恢复兼容的历史，或作为恢复候选重新协调，不能强推旧快照。

Git 历史会持续占用空间。删除当前树中的文件不等于清除历史；首期不自动 force push、轮换仓库或宣称无限容量。达到验证容量边界时，应提示维护/迁移并保留未上传事件，不继续无限膨胀。

## 9. Git 服务选择与交换协议

### 9.1 候选比较

| 路线 | 评价 |
| --- | --- |
| GitHub Git Database REST API | 推荐首期：可经现有 HTTP 访问 blob/tree/commit/ref，无须本地 Git；限流、幂等、业务合并由本项目处理 |
| JGit / Git smart HTTP | 通用 Git 服务适配潜力更好；引入 Git 对象库、磁盘缓存、Android/R8/代理适配成本，暂不作为默认 |
| 系统 Git 子进程 | Desktop 可用于诊断，但不能假设 Android 或用户机器安装 Git，正式链路不依赖 |
| Gitea / GitLab REST | 保留扩展接口，每家分别实现能力契约；不能仅更换 API base URL |
| WebDAV / 自建 API | 可作为后续传输替代；仍需要同一事件协议与并发机制，自建服务另有部署和维护成本 |

GitHub 提供原始 Git 对象及引用 API。[Git Database 概述](https://docs.github.com/en/rest/git)
JGit 7 要求 Java 17，而 Android 仅覆盖部分 JDK API；这意味着需要实测，不能直接断言 JGit 不支持 Android或完全兼容。[JGit 7](https://projects.eclipse.org/projects/technology.jgit/releases/7.0.0)、[Android Java 边界](https://developer.android.com/build/jdks)
Gitea 的批量文件接口与 GitHub ref 更新契约不同，须另行验收并发行为。[Gitea API](https://docs.gitea.com/api/operations/repo-change-files/)

### 9.2 仓库布局（建议）

~~~text
专用私有仓库 / mihon-sync-v1 分支
  space.json                         协议、空间ID、generation、加密元信息
  indexes/<actor-epoch>/head.bin      该设备最新分片索引（加密）
  indexes/<actor-epoch>/<part>.bin    不可变分片清单（加密）
  events/<actor-epoch>/<batch-id>.bin 不可变操作批次（加密）
~~~

批次按设备实例分开，不让所有设备覆写同一个收藏 JSON。清单保存序号范围、事件数、摘要和前片引用；从指定 HEAD 的完整清单定位新批次，不依赖列举整个仓库。清单分页，未知扩展文件保留，不覆盖仓库其他内容。

起始上限建议：单批最多 256 个事件且解密后最多 512 KiB；单次下载/解密总量和单对象文本长度也设上限。超大导入分批继续。这些是防止无界请求的工程起点，不是已测出的最优性能数字。

### 9.3 一轮同步

1. 以空间为粒度串行协调；从数据库冻结待上传事件集合，本轮新事件留给下一轮。
2. 读取同步分支 HEAD=H，后续所有清单/blob 都按固定 SHA 读取；校验 spaceId、版本、代次、摘要、依赖和大小，先持久化 inbox。
3. 按依赖投影有效输入或建立待处理项；未匹配的对象保留，不能当作成功应用。
4. 若有本地队列，把冻结事件编码、加密并持久化批次原始密文字节，重试复用相同 ID 和内容。
5. 基于 H 的 tree 追加本设备批次并更新其清单，保留其他设备文件；创建 parent=H 的新 commit。
6. 用 force=false 更新同一个同步 ref。并发设备先发布导致不能 fast-forward 时，重读最新 HEAD，把原批次并入新 tree 后重建 commit；有界退避，建议每轮最多 3 次竞争重试，随后保留队列。
7. 只有 ref 发布成功，或后续读取证实目标批次已在有效分支且内容相符，才能事务性标为 PUBLISHED。blob 创建成功不等于发布完成。
8. 收件成功、应用成功、上传成功分别统计。上传后可再检查一次 HEAD，处理竞争期间到达的数据；不为追赶持续更新无限循环，也不创建空提交。

GitHub 的更新 ref 接口提供 sha 与 force，没有文档化 expectedOldSha，因此这是**基于 fast-forward 的乐观并发**，不是 API 原生 CAS。必须通过真实竞争实验验证；Git 层成功也不表示业务冲突已经解决。[GitHub refs](https://docs.github.com/en/rest/git/refs)、[Git commits](https://docs.github.com/en/rest/git/commits)

初始化建议让用户先创建带 README 的专用私库。空库无法直接建立 Git DB ref，可通过明确的“初始化所选仓库”动作使用 Contents API；两个设备竞争创建同步分支时，落败端读取并加入已有空间，不覆盖其 spaceId 或密钥元信息，也不继续用另一把密钥写入。[GitHub 初始化说明](https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-your-git-database)

### 9.4 服务限制

GitHub 常规已认证 REST 配额为每用户每小时 5000 次，多个设备/令牌可能共享该用户配额；另有次级限制，常规内容生成限制包括每分钟 80 次、每小时 500 次，具体以服务返回为准。遵守 Retry-After、剩余额度和重置时间，避免一页一次提交。[GitHub 限流](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)

Contents 目录列举最多 1000 项；递归 Git tree 可能在 10 万项或 7 MB 时截断。因此需要分片清单并检查 truncated，不能把截断视为对象被删除。[Contents](https://docs.github.com/en/rest/repos/contents)、[Trees](https://docs.github.com/en/rest/git/trees)

建议性能验收样本包括 1 万/10 万事件、3 台/10 台设备、120/1 万条待处理；记录首次同步时间、增量请求数、内存、仓库增长和 UI 响应。没有实测前不承诺初始化耗时或支持无限历史。

## 10. 凭据、加密与配置页面

建议配置路径：书架 → 同步 → 齿轮 → 同步服务。展示服务商、专用仓库、连接测试、设备名称、初始导入说明、恢复密钥确认，以及本设备启动/定期设置。

- GitHub App 开启设备流程，用户在浏览器登录并授权选定专库的 Contents 读写；不申请删除仓库或 Workflows 权限。令牌由应用取得并安全保存、续期，组织审批、过期和撤销均作为可恢复状态。[GitHub 设备授权](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)、[令牌续期](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)
- 连接测试与实际同步共用 production HTTP、认证和代理。Windows 实机要区分代理握手、TLS、HTTP；不能以 curl 成功替代验收。
- 建议生成随机可恢复的数据密钥，用经过验证的 AEAD 实现加密批次/清单，Tink 作为候选。共用 CryptoCodec 接口，Android/JVM 分别装配相容实现；确切依赖版本、R8、包体和互通测试是实施前置验证。[Tink Java/Android](https://developers.google.com/tink/setup/java)
- AAD 绑定 spaceId、generation、协议和 batchId，不放标题、token等隐私，因为 AAD 不加密。加密认证失败停止该批次投影，不覆盖已有有效数据。[Tink AEAD](https://developers.google.com/tink/aead)
- 数据密钥通过用户控制的恢复资料/设备间导入交付，必须有丢失后的明确后果。首期不要求云端账号替用户托管解密密钥；令牌不随同步事件传播。
- Android 用本机 Keystore 密钥包装可恢复的数据密钥和 token；不能把不可导出的 Keystore 密钥直接当跨设备主密钥。Desktop 复用系统凭据后端并隔离同步 namespace；安全存储不可用时提示修复，不静默降级明文。[Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- 公开的仓库路径、批次数量/长度和提交时刻仍可能泄露活动元信息；避免在提交信息、作者字段中放漫画名或真实邮箱。
- AEAD 不能独自防止服务器回滚/删除。已加入设备以本地水位和哈希链检查；全新设备没有可信旧锚点时，不能证明服务器提供了最新完整历史。
- 撤销设备 token 防止后续访问，但不能收回它已取得的密钥/历史；失窃时需轮换密钥，并说明旧历史仍可被旧密钥读取。
- 取消收藏是业务状态变化，不意味着 Git 历史永久擦除。彻底迁移/删除空间是单独动作，普通同步不重写历史。[GitHub 历史删除边界](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)

暂停自动同步仍保留事件记录；断开凭据不清空本地数据和队列。更换同步空间必须创建隔离命名空间，重新确认导入，不把旧空间的 outbox 自动投递到新仓库。

## 11. 三种触发与运行状态

Android 通过独立 WorkManager 任务注册周期与网络约束；启动在应用初始化后异步请求一次，手动请求进入同一协调入口。周期任务和一次性任务的唯一名称不能单独替代业务串行保护，需共享运行锁/可恢复 lease，避免不同触发互相覆盖。

具体复用 [CreatorDiscoveryJob](../app/src/main/java/eu/kanade/tachiyomi/data/library/CreatorDiscoveryJob.kt) 的共享 executor + 平台 Worker 模式，Desktop 在 [DesktopAppRuntime](../app-desktop/src/main/kotlin/mihon/desktop/DesktopAppRuntime.kt) 注册独立 service。Android 现有 MainActivity.CheckForUpdates 可作异步启动体验参考，其发现更新后推页面的行为不移植到自动同步。

WorkManager 周期最短 15 分钟，实际执行受约束和系统优化影响，可能延迟或跳过；建议产品默认 1 小时，可选 15 分钟、1/6/24 小时。强行停止、休眠或系统限制时不承诺定时运行，重新获得执行机会后补同步，不把错过的每个周期补成一轮。[Android 周期任务](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

Desktop 沿用现有进程内调度和应用生命周期：启动异步、进程存活时周期运行、手动可用。应用完全退出后没有常驻进程，不继续同步；若要求退出后运行，需要另行设计系统服务，不暗中增加常驻进程。

运行状态建议为 IDLE / FETCHING / APPLYING / PUBLISHING / PARTIAL / FAILED；取消任务释放 lease 并持久化已有结果。数据库查询的待上传/手动事项与运行状态独立组合，避免“没上传”被误显示为“没待处理”。

重复触发合并，当前轮结束后至多安排一个后续请求；网络重试有退避上限。所有 I/O 均不阻塞 UI 主线程。首页、收藏和阅读仅等待自己的本地事务，不等待 Git。

## 12. 失败与恢复

| 情形 | 行为 |
| --- | --- |
| 离线、DNS、代理、TLS 失败 | 保留队列，显示具体阶段；不伪报同步成功 |
| 401 / 403 / 404 | 区分过期、权限/组织审批、限流、私库不可见及仓库不存在；不能看到404就新建覆盖 |
| 409 / 422 | 区分竞争、空库、保护分支、校验失败；只对可确认的竞争做有界重建 |
| 超时发生在 ref 更新后 | 结果未知，先读回并核对批次 ID/摘要，不能重新生成不同事件或直接清队列 |
| 应用被终止 | 重启恢复 IN_FLIGHT 批次；重读确认远端状态；事务保证不会只改业务不记事件 |
| 未知协议/必需字段、损坏密文或历史缺口 | 保留原始输入和本地队列，暂停受影响范围；未知写入协议时空间只读待升级 |
| 本地存储失败/磁盘满 | 本地用户动作的事务失败明确反馈；不能动作显示成功而日志丢失 |
| 不匹配来源/作者/章节 | 持久化为待匹配，其他对象继续；补齐来源后可重试 |
| 批量处理期间有新增/失效项 | 仅处理冻结且仍有效的集合，反馈完成与跳过数量 |
| 错误仓库/换代/分支回退 | 不以较旧远端覆盖本地；先校验身份并进入恢复流程 |

## 13. 实施批次与真实验收门槛

实施已开始，当前执行单元和证据见[实施计划](roadmap/2026-09-13-multi-device-sync-implementation.md)；不按文件数拆分，也不预先分配迁移版本号。

| 批次 | 可审查交付 | 必须通过的验证 |
| --- | --- | --- |
| A：协议与可行性验证 | 固定字段/身份/前驱规则；GitHub竞争及加密互通小型验证 | 同一基线两端竞争、未知发布结果、空库竞争；Android最低版本/R8与Windows实际HTTP/代理；不能用curl代替 |
| B：本地可靠记录 | 收藏、作者、阅读所有真实入口同事务记日志；持久化待上传计数 | 先失败测试再实现；故障注入、重启、批量路径、恢复/metadata不误记 |
| C：交换与确认 | GitHub adapter、inbox投影、冲突、接收端确认和旧操作失效 | 共享三设备契约、乱序/重复/断网/崩溃、MockWebServer成功及403/429/500/畸形响应 |
| D：产品链路 | 双端书架入口、面板设置、配置/密钥、长列表批量、三触发 | DI、Screen/Voyager导航、WorkManager与Desktop调度、后台不抢页、瞬时通知生命周期 |
| E：恢复和收口 | 初始导入、备份协调、缺源匹配、规模边界和维护说明 | 空设备不清书架、旧备份不复活取消、阅读不跳页/不改模式、1万/10万事件性能与发布产物验收 |

关键验收向量：

- A 收藏、空 B 先同步，A 不被取消；C 在 A 关机后仍可接收。
- A 取消，B 确认、C 忽略；各自结果持久化且不来回反转。
- 取消待处理后重新收藏，旧按钮不能删除新收藏；三端相反决定可解释。
- 本地写事务各失败点、ref 超时、收件崩溃及重复重试，不丢失、不重复应用。
- 同步过程中继续阅读，新事件保留；同漫画重读早页有效；阅读显示偏好始终设备独有。
- 120/大量条目多选、全选、全部处理，中途新项和失效项不会误处理。
- 单行待同步总数与小字分类明细一致；成功只清本轮确认发布集合，失败保持数量。
- 同步核心被破坏、平台入口绕过日志、DI/导航/HTTP接入断开时，测试必须失败。

每批红绿循环只跑 focused tests；完成批次做相应集成与格式核验，阶段/最终才扩展完整测试。正式 Desktop 发布按仓库构建脚本和实际运行产物验收。HTML 的27项测试只能作为交互参考，不能证明生产同步可靠。

## 14. 提交审核的决策

| 编号 | 推荐批准项 | 对用户的实际影响 |
| --- | --- | --- |
| D1 | 首期 GitHub 专用私库 + REST；保留其他服务商接口 | 浏览器设备登录并授权专库，无需手填令牌或安装 Git；Gitea/GitLab 暂不承诺可用 |
| D2（沿用） | 各接收端分别确认取消；忽略只保留本端 | 与当前 DEMO 一致，允许设备差异；不会一次确认删除所有端 |
| D3 | 阅读含已读/明确未读、续读页、最近历史；显示模式独立，无痕阅读不上传 | 可在另一端继续读，也保留重读/回退意图；完整时长、书签等不扩大 |
| D4 | 首次已有数据合并导入；基线低于明确用户操作 | 空设备不会清空其他端，旧书架不会自动复活已取消条目；只沿用接收端取消确认，不增加冲突匹配流程 |
| D5 | 启动异步 + 默认每小时尽力后台；Desktop完全退出不运行 | 自动同步有平台调度延迟，手动始终可用；不增设常驻服务 |
| D6 | 首期客户端加密，并提供恢复密钥流程 | 私库服务无法直接读取漫画内容字段；新设备需要密钥，丢失全部密钥无法解密远端 |

以上组合已于2026-09-13获用户审核通过。用户随后要求先对照方案检查DEMO交互，见[交互审查与调整清单](prototypes/2026-09-13-sync-demo-interaction-audit.md)。后续生产实施仍先执行批次A，验证尚未实测的硬约束；真实服务、加密和后台能力不能以DEMO代替验收。
