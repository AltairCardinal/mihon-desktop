# 跨插件唯一作者身份：调研与归集方案

日期：2026-09-17。状态：技术方案已收敛，待实施批准；不是修复完成记录。第 9–15 节为确定的首版实现契约。
用户入口、页面状态、确认范围和双端交互以[功能设计](2026-09-17-global-author-functional-design.md)为准；本地[HTML 交互原型](prototypes/author-identity/index.html)用于审阅，不代表生产逻辑或跨设备身份同步已实现。
本轮范围：跨插件唯一作者身份、重复建档、已有作品归集及其数据边界。没有修改产品代码、安装应用或合并用户记录。
需求澄清：用户要求每位作者具有跨插件的唯一身份。漫画柜是已调查的证据样本，不是系统边界；仅实现同源去重不能通过本需求验收。
本地代码基线：调研时 HEAD `d80cead332`；已有其他任务未提交改动不属于本方案。

## 1. 结论

推荐采用“全局作者档案 + 多来源署名映射 + 跨来源身份判定”的方案。一位已识别作者只有一个活跃 Creator portable key、一个作者页和一个关注目标；来自不同插件的作品与作者标识都映射到这个档案。插件不是身份命名空间的最终边界。

漫画柜实际提供作者 ID。应把作品详情中的作者链接保留为结构化证据，而不是仅保存名字。姓名用于展示和候选查找，不能作为全局身份主键；站内 ID 也只证明该站把相关署名归于同一作者档案，不自动证明跨站身份相同。

历史修复必须展示跨插件归集预览并由用户确认，不静默批量合并。确认后，后续作品可以依据已确认的来源映射复用全局作者，避免每新增插件或作品又产生一个同名身份。

“身份唯一”是产品与存储约束，不等于“任意插件只给一个名字也能百分之百自动识人”。证据不足的署名保存为待关联记录，在作者页的待确认区域处理；不能按插件创建并展示多个正式同名作者，让用户长期在重复档案中选一个。真实同名不同人仍应拥有各自独立的全局身份。

### 1.1 身份分层与复用

| 层 | 标识与职责 |
|---|---|
| 全局作者 | 复用现有 Creator portable key，应用生成且不依赖插件、姓名、站点或作品；用于作者页、关注、作品作者关系 |
| 来源作者 | `{sourceNamespace, sourceAuthorId}`，作为外部标识关联到全局作者；一个全局作者可以关联多个站点的多个 ID |
| 作品署名观察 | 来源作品键、原始署名、角色、取得的链接与证据；无站内 ID 的旧插件也能记录，不伪造一个按名字生成的来源 ID |
| 身份决定 | 已确认关联、明确不同人、待确认/冲突，附依据及版本；人工决定与自动推断分开 |

示意：`全局作者 A（冈本伦） ← 漫画柜 author/363 + 插件 B 的作者标识 + 插件 C 已确认的作品署名`。B、C 是设计示例，不是本轮已实测的来源。更换插件、插件卸载或站点改名不删除 A，也不产生新的关注目标。原作/作画是人与作品之间的角色，笔名是该人的别名，均不应单独创建人物身份。

现有 Creator、portable key、canonical work 与 merge 能力已提供共享核心，不另建一套“全局作者系统”。新增的是外部标识映射、署名待关联状态与判定流程；旧无证据档案先保留数据，不能直接删除或降级已有关注。

## 2. 已核实证据与未确认项

### 2.1 真机现场

前一轮已读取真机界面：PCE-W30，`app.mihon.desktop.fork`，版本 `0.19.4-aex.4` / versionCode 22；漫画柜《平行天堂》的选择框显示三个“冈本伦”。取消后作品详情与图源名称均吻合。

发布版不支持 `run-as`，没有读取私有数据库。三个身份的全部关联作品、portable key、创建来源、关注与人工操作历史尚未取得。本轮 ADB 仅列出模拟器，因此没有新增真机数据证据。不能将以下站点样本当成手机三个身份实际关联作品的清单。

### 2.2 漫画柜公开元数据

本轮经会话代理实际请求以下页面，均返回 HTTP 200；读取的是作品元数据与作者链接，没有下载漫画页图像。

| 页面 | 作品详情中的作者链接 |
|---|---|
| [平行天堂 /comic/23333/](https://www.manhuagui.com/comic/23333/) | 冈本伦 → `/author/363/` |
| [极黑的布伦希尔德 /comic/1401/](https://www.manhuagui.com/comic/1401/) | 冈本伦 → `/author/363/` |
| [变异体少女 /comic/7992/](https://www.manhuagui.com/comic/7992/) | 冈本伦 → `/author/363/` |
| [你是我的女王 /comic/8243/](https://www.manhuagui.com/comic/8243/) | 冈本伦 → `/author/363/`；横枪萌果 → `/author/3662/` |

[作者页 363](https://www.manhuagui.com/author/363/)列出了这些作品；[繁体作者页](https://tw.manhuagui.com/author/363/)也能访问。它们支持漫画柜内的 ID 归集，不足以建立任意镜像域名互认规则，也不足以保证站点永远不改号。

检索服务返回过过期作品页及作者页 403/503；上表以随后本地经代理的实时 HTTP 读取为依据，不以搜索缓存推断当前章节数或完整作品数量。

公开 Keiyoushi 扩展的 [Manhuagui.kt 固定版本](https://github.com/keiyoushi/extensions-source/blob/9b78a3583c5f7d6a3c0b79907ac95f70de33ef3c/src/zh/manhuagui/src/eu/kanade/tachiyomi/extension/zh/manhuagui/Manhuagui.kt)中，`mangaDetailsParse` 对作者 `<a>` 取文本后写入 `SManga.author`，没有保留作者 href。这能说明公开实现的信息损失点，不能替代对用户实际安装扩展版本的核对。

### 2.3 仓库现状

- [作者索引 SQL](../data/src/commonMain/sqldelight/tachiyomi/data/author_archive.sq)：优先复用当前作品已有绑定；没有绑定时会建立新身份，并将同名歧义标为待审核。不是简单列表重复渲染。
- [索引测试](../data/src/jvmTest/kotlin/tachiyomi/data/creator/CreatorLibraryIndexRepositoryTest.kt)：现有用例明确要求不同作品上的同名作者保留不同身份。不能只改一个去重条件而保留互相冲突的契约。
- [AuthorSearchSource](../source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/AuthorSearchSource.kt) 与 [发现端口](../domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/CreatorDiscoverySourcePort.kt)：已有结构化匹配概念，但匹配只含显示名、角色、说明，没有类型化站内作者 ID。`VERIFIED` 作品关系不能直接升级为全局身份等同证据。
- [ManageCreatorIdentity](../domain/src/commonMain/kotlin/tachiyomi/domain/creator/interactor/ManageCreatorIdentity.kt)：自动解析到零个或一个候选时也会创建/绑定。[Repository](../data/src/commonMain/kotlin/tachiyomi/data/creator/CreatorRepositoryImpl.kt) 的绑定方法统一写入 `USER` 与 `explicit manga identity binding`。因此旧 `USER` 标签无法可靠区分自动绑定与用户明确确认，必须保守保留。
- 同一 Repository 已有事务型 `mergeCreatorIdentities`：迁移别名、漫画关系、源作品关系、规范作品作者关系、关注/发现记录和语言断言，保留旧身份的 `MERGED` 指向。应复用，不另写一套迁移器。
- [备份贡献器](../data/src/commonMain/kotlin/tachiyomi/data/backup/AuthorArchiveBackupContributor.kt)已包含身份 portable key 和合并指向；[同步身份解析](../data/src/commonMain/kotlin/mihon/data/sync/projection/SyncAuthorIdentity.kt)能沿本地合并链解析。二者不等于新增合并决定已经可以跨设备传播。
- Desktop 有合并/拆分 UI；当前 Android 作者详情没有对应管理入口。需要补齐共享语义的移动端入口。

本轮仅阅读这些测试和实现，没有运行测试，不能把已有测试文件当作本轮通过证据。

## 3. 判定规则

| 依据 | 处理 |
|---|---|
| 同一可信来源命名空间、同一站内作者 ID，且有作品详情署名链接 | 可建议归集；已确认映射可供后续索引复用 |
| 不同插件的来源标识已映射到同一全局作者 | 直接复用同一个作者页与关注目标，不重复确认 |
| 经适配器验证的跨站人物标识、明确的人物交叉引用，且无冲突 | 提供优先候选；首版不因此自动合并两个已存在的全局身份 |
| 已确认同一作品、对应角色和署名也吻合 | 提升跨来源候选排序；合作署名、角色不明或作品关系依赖作者推断时不能作为独立证明 |
| 用户明确确认这些身份是同一人 | 允许归集，记录决定；不自动把所有同名作品加入决定范围 |
| 同源同名、繁简相似、简介提及、普通搜索命中 | 仅形成候选，不自动合并 |
| 同名但来源作者 ID 不同 | 不同站点的不同 ID 是正常情况，不据此认定不同人；同站不同 ID 也可能是笔名/重复档案，先保持待确认，不靠 ID 差异建立永久否定 |
| 用户曾决定保持独立，或存在未解决的人工/恢复记录冲突 | 阻止自动复用与合并，转人工处理 |
| 作者链接缺失、页面错误或解析失败 | 保留现状，显示未核验，可重试；不能将失败当成无作者 |

来源作者 ID 不使用裸 `363` 作全局键。外部映射键结构为 `{sourceNamespace, sourceAuthorId}`，最终指向独立的 Creator portable key；`sourceNamespace` 表达经验证的站点身份域，而非插件显示名。保留扩展来源、实际 sourceId、作品 URL、作者 URL、抓取时间、解析器版本、原始署名与角色作溯源。不同插件访问同一站点时允许通过显式 adapter 映射共享来源键，不把偶然相同域名直接当成可信证据。不同站点的相同数字不构成同人证据。简繁域名互认仅接受经过测试的显式映射。

名称归一化仅用于查找，不能将“冈本伦”“岡本倫”的字形规则直接推广为跨站自动合并。合作作品中的多个作者链接分别处理，不从同一作品倒推这些作者属于同一身份；原作/作画信息不明确时保留来源角色，不猜测分工。

跨插件候选检索复用既有别名、规范作品/版本关系与作者发现流程；可结合姓名、别名、作品交集、角色和结构化外链排序，但不能用一个未经校准的分数替代身份确认。没有候选时允许用户创建全局作者；有同名候选时先展示作品依据，并允许选择既有作者或明确建立另一人。仅有姓名的后续新作品仍可能需要确认，不能把一次确认泛化为永久的“名字 → 人”规则。

用户确认插件 B 的某个稳定作者标识属于全局作者 A 后，B 中后续相同标识无需逐部确认。没有稳定标识时，确认范围只能是选中的署名/作品集合；可批量确认有明确范围的一组记录。否定决定也有作用域和版本，必须阻止经第三个档案的传递合并绕过“A 与 B 是不同人”的决定。

## 4. 生产接入与复用

### 4.1 获取证据

首版确定使用宿主注册的共享来源证据适配器，不修改 `Source`、`SManga` 或第三方 APK，也不以第三方先实现可选接口作为前置。今后可将适配器替换为扩展提供的类型化能力，但不属于本次实现。

通用流程必须支持所有插件的现有署名输入，不能要求每个插件先实现专属 adapter 才能拥有跨插件作者页。有稳定 ID 时自动复用已确认映射；只有文字时走共享候选与批量确认；完全没有作者元数据时允许人工关联，不猜作者。漫画柜 adapter 只是增强取证的首个样本。

首版注册漫画柜和 MangaDex 两个证据 adapter：接收生产 SourceManager 中真实加载的 source，使用其生产 HTTP 客户端、headers、Cookie、代理、限流与取消机制，通过经校验的作品路径获取详情中的作者标识。不能使用独立默认客户端，不按图源显示名猜测实例，不在 Android 页面或 Desktop 页面里各写解析逻辑。

adapter 确定放在 `data/src/commonMain/kotlin/tachiyomi/data/creator/identity/`，使用项目现有 OkHttp、Jsoup 和 kotlinx.serialization，并显式声明所需依赖；由两端现有 DI 接入。domain 只处理结构化证据。注册时核对实际扩展包、sourceId、来源域名与兼容的实现类型；不能确认则降级为人工预览。首版以额外的有界详情请求获取原始标识，按作品缓存去重，不拦截或反射第三方 parser。

修复范围只抓待核验身份已关联的本地作品，每次任务建议上限 20 部、单源并发 1、总时间 60 秒，且始终服从源自身更严格的限流。遇 403 停止该源并反馈，429 按 Retry-After 退避，超时/500保留进度供用户重试；不无限重试、不跨域跟随未知链接。作者页可辅助印证，但不能把侧栏推荐或同页所有作品都归入待修复身份。

### 4.2 共享索引

沿用 `CreatorLibraryIndexer` / `CreatorRepositoryImpl` 和既有漫画详情更新事务；网络取证在数据库事务之外完成，事务内只验证并应用已持久化证据。

索引优先级建议为：明确的保持独立/人工绑定约束 → 已确认且无冲突的来源到全局身份映射 → 当前作品可复用的明确绑定 → 跨插件候选查找 → 保存待关联署名。无证据时不误合并，也不按每部作品自动创建一个正式作者。现有“同名不自动合并”的安全目标保留，但“每作品建立一个同名 Creator”的测试预期必须改为真实待关联行为契约。首次获得强证据但已有多个历史身份时先产生预览，不在后台静默归并。

最少新增持久化信息：来源身份观察及其作品署名证据、待关联状态、来源到全局身份映射、明确的合并/保持独立决定与版本。继续以现有 Creator portable key 标识全局作者，不用站点 URL 替换已有主键。一个全局作者可对应任意数量的已确认来源键，一个已确认来源键通常对应一个活跃全局身份；人工拒绝站点归类时记录冲突/例外并停用该键的自动复用，不能让唯一索引迫使用户合并。

新的自动来源绑定与明确用户决定分开记录。旧 `USER` / `RESTORE` 不批量降级，也不根据名字补造站内 ID。

## 5. 历史记录如何归集

1. 从《平行天堂》作者选择框的“整理作者”，或全局作者详情的“关联其他来源”进入。候选范围覆盖所有插件，读取相关身份的真实作品关系、来源、关注、别名、合并链、人工/恢复记录；无需绕过发布版私有目录权限。
2. 只对这些关联作品补充作者链接证据。预览逐个展示身份及作品；标明“漫画柜同一作者页”“尚未核验”“已有人工决定”等状态。无法取得证据时允许用户自行判断，但必须明确标为人工确认。
3. 如果某身份还关联其他站点、其他作者 ID 或未核验作品，不把它整个拖入自动建议。展示未覆盖部分；用户可取消、保持独立，或通过已有拆分能力先隔离确认作品。首期不做隐式拆分再合并。
4. 用户选择保留的作者档案，确认受影响作品、别名和关注设置。无唯一人工主档时只提供稳定默认排序，不用设备内自增 ID 决定跨设备身份。
5. 应用前重新读取版本和冲突；预览期间数据变化则重新预览。完成可恢复的归档备份后，在单个外层事务内执行整个归集组。复用并适当抽取现有 merge 内核，不能简单连续调用两次独立事务合并三个身份，否则第二次失败会留下半完成状态。
6. 幂等键基于排序后的 portable keys、目标 key 和决定版本；保留旧 key 的重定向并检测环。写后核验作品关系并集、合作作者、关注与通知记录；反馈“已归集 X 个作者档案，保留 Y 部作品”。重建索引、刷新作品及重启后不得再生已归集身份。

身份合并不等于漫画合并：不合并漫画条目、章节、下载文件、阅读进度或跨源版本。同一本作品的原作与作画角色同时归到一个已确认的人时，应做有依据的角色并集，不能靠 upsert 顺序覆盖；不相关合作作者不受影响。

最终作者页按全局作者展示跨插件作品。已有确认的 canonical work 关系时，复用现有作品归档将来源版本展示在同一作品下；尚未确认是同一作品时保留来源标注，不按标题盲目折叠。页面展示别名、已关联来源和待确认署名，所有来源入口进入同一作者页；只需关注此人一次，来源过滤仍可设置，卸载插件只使对应来源不可用，不取消对人的关注。

现有关注合并会取启用状态并集、较短检查周期及更宽的部分筛选条件。该行为必须在预览中列出差异；不能无提示扩大扫描频率和通知范围。保留原语义作为默认迁移规则，用户可在同一确认中选择调整；有冲突的人工设置先处理再提交。

## 6. 恢复、备份、同步边界

跨插件唯一与跨设备同步是两个维度：跨插件唯一是本次必需验收，不能留到后续；用户没有因此授权建设公共作者目录服务或改造全部同步协议。portable key 应支持备份和既有同步引用，但独立设备先后创建同一人不会因 UUID 本身自动收敛，仍需身份映射或明确合并决定。

- SQL 失败必须回滚整个归集组；取消取证不会改变现有关系。取证结果可以缓存，但不能标成已完成修复。
- 现有 split 不是完整 unmerge：合并后别名、通知、关注策略等已汇集，不能承诺一键原样撤销。首期提供合并前可恢复备份及明确的恢复说明；恢复备份可能覆盖其后变化，不伪称无损撤销。
- 新增证据、映射和人工决定必须进入共享备份契约并通过双端往返验证。恢复旧格式时将证据视为未知，不虚构 ID；旧备份不能复活已合并身份或覆盖更新的保持独立决定。
- 现有同步可解析本地重定向，但本次阅读没有发现 merge 方法写入身份合并同步事件。因此不能承诺在 Android 归集后 Windows 自动归集，也不能用各设备 local ID 同步。
- 推荐首期范围为本地归集加备份往返。已启用作者关注同步的用户可查看预览，但新增归集写入需等 portable-key 重定向/身份决定传播契约完成并验证后开放；不要静默关闭同步、删除旧远端操作或重写旧事件。
- 若要求首期同时支持在线双端归集，则同步身份决定、冲突处理与旧 key 解析必须成为前置工作。这属于独立的跨模块协议批次，需另行确认实施范围，不能声称现有 follow 事件已足够。

## 7. 可选路线与推荐顺序

| 路线 | 优点 | 限制 | 结论 |
|---|---|---|---|
| 仅补 Android 手动合并入口 | 快，可复用现有 merge | 新作品缺少可靠映射，仍可能继续建同名档案 | 可作为临时止血，非完整第三层修复 |
| 同源同名自动合并 | 实现简单 | 同名误合并；不能保护合作作者和人工区分 | 不采用 |
| 只做站内作者 ID 归集 | 能解决漫画柜局部重复 | 无法满足跨插件唯一身份 | 仅作为证据接入，不是完整交付 |
| 全局 Creator + 多来源映射 + 跨插件候选确认 | 满足一个人一个作者页，兼容无 ID 插件 | 需待关联模型、映射、迁移及 UI | 推荐 |
| 外部人物知识库作为可选证据 | 可补充跨站别名与人物标识 | 覆盖和可用性需另行调研，不能作为唯一依赖 | 后续增强，不阻塞通用人工确认链路 |

建议按三个可独立验收的功能批次实施：

1. 共享身份与索引：复用全局 Creator，增加多来源映射和待关联署名、跨插件候选判定、旧插件降级；漫画柜作为强证据 adapter 样本，修正“自动操作标成人工确认”的新写入行为。
2. 完整作者体验与历史迁移：双端同一个作者页、跨插件作品集合、一次关注、关联其他来源及批量确认；事务幂等、角色冲突、备份恢复，保留既有 Desktop 管理能力。
3. 产品验收：至少两个真实插件指向同一个作者页，包含无结构化 ID 插件路径；实际三个旧档案归集、正式 Android/Windows 产物验证。没有多插件证据不得标为完成；若纳入同步则须完成其前置协议批次。

先前 4–6 工程日估算仅覆盖局部来源归集，不再作为满足本需求的承诺。追加调研已明确第二来源、迁移方式、接口和 UI 验收，详见第 9–15 节；本轮不启动实现或同步协议扩展。缺少稳定 ID 的自动覆盖率不能由接口可行性推算，验收以真实样本及人工确认闭环为准。

## 8. 后续实施验收要求

以下为计划验收项，本轮未执行：

- [ ] Android 与 JVM 共享真实数据库契约：不同插件/不同作者 ID 可映射到同一 portable key；同名不同人、跨站同数字、无证据、保持独立决定不误合并；并发索引不生成重复正式身份。
- [ ] 无稳定作者 ID 的旧插件：批量确认其作品署名后，所有已确认入口进入同一全局作者页；新增不确定署名进入待确认，不新建一个正式同名档案，也不因名字相同擅自关联。
- [ ] 两个真实插件中的同一作者 → 相同作者页、跨来源作品集合和同一个关注状态；更换或卸载其中一个插件不删除作者身份。合作作者、笔名和同名不同人均有代表用例。
- [ ] MockWebServer 经真实 parser/adapter：四个站点样本、多人署名、缺失链接、错误链接、403/429/500、畸形 HTML、取消及超时；普通搜索文字匹配不得冒充身份 ID。
- [ ] 核对生产 DI 与 SourceManager：两端使用真实来源实例的 HTTP 设置；Android 已安装旧扩展的 ABI 与可选能力降级通过，不只测独立客户端。
- [ ] 真实三身份 fixture：归集一次后作品并集完整、合作作者不变；重复调用、第二次迁移失败、预览过期、已有合并链、关系角色冲突、关注设置冲突均有正确结果。
- [ ] 归集后再刷新/重建索引/重启：不重新产生同名身份；用户明确保持独立后不反复自动建议同一次归集。
- [ ] 新旧备份迁移、双端往返、合并链不复活；同步开启时遵守首期写入门槛，若实现协议则验证离线并发与乱序回放。
- [ ] 作者选择框 → 整理作者/关联其他来源 → 查看跨插件身份的作品及证据 → 确认归集 → 唯一作者页显示正确跨插件作品集合；取消无写入，失败有明确反馈。
- [ ] 真机正式产物在用户三个实际档案上先展示预览，确认范围后验证；Windows 使用项目构建脚本完成发布运行验收。真实构建路径与测试结果在实施完成报告中提供，不能提前用本调研替代。

所有行为变更按红绿重构；包含数据完整性与迁移的批次须独立审查。实现时沿用仓库 Gradle 协调器及分层验证，不因本方案拆成批次而重复进行全量发布测试。

## 9. 追加可行性结论与第二来源证据

结论：可以在现有架构中实现跨插件的统一作者档案，不需要服务器、LLM 判人、公共作者数据库或第三方扩展升级。不能保证任意文字署名都可无交互识别；确定方案将自动复用与首次身份确认分开。这里的“全局”指用户作者库内跨所有插件，不承诺所有用户安装生成相同 UUID。

可执行的不变量是：所有已确认属于同一个人的来源标识与署名只能解析到一个 ACTIVE 根身份，所有相应入口/关注均使用它；未知关系不能冒充已确认关系。UUID 只解决键的唯一性，resolver、映射和人工决定才解决人物的同一性。

2026-09-17 经本机代理直接读取 MangaDex 公共 API，HTTP 200：

- [作者查询](https://api.mangadex.org/author?name=Okamoto%20Lynn&limit=10)返回 `d1fdd0e0-b817-4b17-9972-7082b6f4a7c7`，名称 `Okamoto Lynn`，简介注明 `岡本倫`。
- 作品查询返回原作条目 `64ed4d14-9e00-4b63-8799-547a508f5344`，多语言标题含《平行天堂》，作者与作画关系都指向上述 UUID。另有 Fan Colored 条目及无关近似标题，不能取第一个搜索结果当作身份或作品确认。
- [MangaDex 作品详情 API](https://api.mangadex.org/manga/64ed4d14-9e00-4b63-8799-547a508f5344)提供结构化关系；通过 `includes[]=author&includes[]=artist` 可展开名称。与漫画柜 `author/363` 对应关系可作为人工确认样本，不将本次人工调研结论写成应用硬编码。
- 公开 [MangaDexHelper.kt](https://github.com/keiyoushi/extensions-source/blob/237a13600d5532a4af373267eef5d0119a7d44c8/src/all/mangadex/src/eu/kanade/tachiyomi/extension/all/mangadex/MangaDexHelper.kt)将关系转换为名字写入 SManga，作者 UUID 不进入通用署名字段。
- 当前公开 [KeiSource](https://github.com/keiyoushi/extensions-source/blob/main/core/src/main/kotlin/keiyoushi/source/KeiSource.kt)继承 HttpSource，公开 client/headers 可沿用。仓库 [HttpSource](../source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt)也提供这些接口，因此无需反射取得请求客户端。

本轮没有运行真实 APK 的新增取证 adapter：上述是原始接口及源码可行性证据，不是 production wiring 验收。正式实现仍须通过现有真实扩展 fixture 和真机验证，不以 Python HTTP 请求代替。

另已确认：[CreatorIdentityEvidenceEvaluator](../domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/CreatorDiscoveryPlanning.kt)目前把精确姓名匹配标为 VERIFIED，置信度 0.95；结构化匹配也只比较姓名。这不是人物身份等同证据。此处必须与书架索引一起改，否则发现任务仍会污染已统一的作者档案。

## 10. 冻结的数据模型

既有 `author_archive_creators` 继续作为唯一人物实体，不新增第二套作者表。`_id` 仅作本地外键；portable key 使用既有生成方式，永久保留；名称无全局唯一约束。追加 `identity_revision INTEGER NOT NULL DEFAULT 0` 和 `identity_review_state`（`LEGACY_UNREVIEWED` / `CONFIRMED`）：迁移后的旧档案为前者，新建或明确确认档案为后者。ACTIVE/MERGED/DELETED 生命周期保持原语义。

在同一个 SQLDelight 权威目录新增 `author_identity.sq`。下表为实现契约，字段中的 ID 仅用于本地，备份使用 portable/natural key；命名以该前缀落地。

| 新表 | 必需字段与约束 |
|---|---|
| `author_archive_identity_keys` | `_id`、`namespace`、`external_id`、可空 `creator_id`、`state`、`revision`、`decision_key`；UNIQUE(namespace, external_id)；state 为 UNBOUND/BOUND/CONFLICT/BLOCKED，BOUND 必须有目标，其他状态不提供自动映射 |
| `author_archive_mentions` | `mention_key` 主键、`source_work_id`、`field`、`token_index`、`raw_name`、`normalized_name`、`input_digest`、可空 `identity_key_id` / `creator_id`、`state`、`basis`、`revision`；state 为 PENDING/BOUND/CONFLICT/STALE；BOUND 必须有目标；basis 为 USER/SOURCE_KEY/LEGACY_UNKNOWN |
| `author_archive_identity_evidence` | `evidence_key` 主键、`mention_key`、可空 `identity_key_id`、`kind`、`locator`、`parser_version`、`fetched_at`、`response_digest`、有大小上限的类型化 `payload_json`；用于溯源，不含 Cookie/token 或完整响应体 |
| `author_archive_identity_decisions` | `decision_key` 主键、`operation`、`actor`、`schema_version`、类型化 `payload_json`、`idempotency_key UNIQUE`、`created_at`；只追加，决定撤回通过新命令表达，不覆盖原记录 |
| `author_archive_identity_decision_subjects` | `decision_key`、`subject_key`、`before_revision`、`after_revision`；联合主键(decision_key, subject_key)，用于查询受影响对象及并发检查 |
| `author_archive_identity_distinct` | 排序后的 `left_subject_key`、`right_subject_key` 联合主键、`decision_key`、`active`；明确不同人约束，不把“搜索没命中”记录成否定关系 |

所有外键启用；关系表不以级联删除清除决定历史。subject key 是类型化、长度前缀编码的 Creator portable key、来源键或 mention key，不用名称或本地行号。payload 通过 Kotlin sealed DTO 验证，不接受任意 JSON 指令。

`mention_key = SHA-256(version + source-work-natural-key + field + token-index + exact-input-digest)`，使用长度前缀编码防止拼接歧义。重复抓取完全相同署名命中同一记录；字段或顺序改变产生新观察，旧记录标 STALE 并保留决定。已绑定来源键可复用到新观察；仅凭名字的旧决定不能不经检查套到新数据。证据 adapter 提供原始列表时按列表保留多人及顺序，不先用姓名合并两个不同 ID；通用文本 fallback 只沿用现有分词器，不用它决定身份。

查询索引：mentions(normalized_name, state)、mentions(source_work_id, state)、mentions(creator_id)、identity_keys(creator_id)、decision_subjects(subject_key)；复用已有 aliases(normalized_alias)。候选查询按需分页，默认 20 条，不能加载全部作者后在 UI 模糊遍历。

名字变体只作为候选：原始文本保留；规范化复用 CreatorNameNormalizer；首版不引入自动罗马字转写或模型推理。跨语言可从适配器的明确名称/别名、作品标题别名以及用户输入产生候选。MangaDex 简介属于展示证据，不用正则把任意介绍文字自动提升成确认别名。

## 11. 冻结的判定与写入接口

domain 增加 `ResolveCreatorMention`、`GetCreatorIdentityCandidates`、`PlanCreatorIdentityReconciliation`、`ApplyCreatorIdentityDecision`；复用 `ManageCreatorIdentity` 作为旧调用入口委托新用例。Repository 的存储仍由 CreatorRepositoryImpl 承担，新 SQL 隔离在上述文件，不再绕过用例直接写 USER 绑定。

尚未入书架的作品也支持作者入口：加载详情后通过 repository 的 `observeCreatorMentions` 保存 source work 与署名观察，再调用只读 resolver；这一步不创建正式作者，不要求先收藏漫画。移出书架、卸载扩展不删除已确认人物、来源映射和人工决定，既有书架关系清理不得级联清掉身份历史。

结果模型确定为 `Resolved(creatorKey)`、`NeedsConfirmation(mentionKey, candidates)`、`Conflict(reason, subjects)`。只读 resolve 不会偷偷创建作者或把自动结果写成 USER。后台索引允许应用已有确认映射，但该写入的 basis 为 SOURCE_KEY。

判定顺序固定：

1. 读取现有合并链及否定关系，检测缺失、环和冲突。用户对该署名的明确绑定优先；若来源新证据与其矛盾，保留人工绑定并记录冲突，不覆盖。
2. 当前有效来源键为 BOUND 且无冲突时，解析到唯一活跃全局作者；将该署名关联到它。这是首版唯一的跨作品自动身份复用路径，另允许复用当前未变化署名已确认的绑定。
3. 无映射时查询候选，按“已确认作品/角色对应 → 明确外部人物引用 → 确认别名匹配 → 普通名字匹配”分层排序。分层不是概率分数，各层均不自动合并未知人物。旧 LEGACY_UNKNOWN 绑定在页面保留可访问，但不能变成新的自动判定依据。
4. 有无候选都保存 PENDING；用户可选“关联已有作者”或“创建另一位作者”。列表入口将待关联署名单独分页显示，并按名字提供临时分组；分组不是正式身份，不提供独立关注目标。

命令确定为 `CreateCreator`、`ConfirmBindings`、`MergeCreators`、`MarkDistinct`、`RevokeDecision`，参数均含作用对象、expected revisions、idempotency key。CreateCreator 与所选署名绑定在同一事务；ConfirmBindings 可同时确认多个插件来源键，或仅确认无 ID 的选定署名集合。

合并前检查整个将合并集合及其映射来源键的否定关系，不能只比较最后一对作者。保留目标 portable key；旧 key 仅增加重定向，不改写已存在同步事件。角色集合 AUTHOR+ARTIST 合为 BOTH；未知角色不覆盖明确角色；证据分别保留，不以最后一次 upsert 丢失其中一项。

`RevokeDecision` 只撤销尚未产生不可逆归集的映射/否定决定并重新投影相关署名。已经执行 MergeCreators 的记录不提供假 unmerge：提示使用拆分纠正或恢复备份。两类操作在 UI 中明确区分。

所有写命令走 `handler.await(inTransaction = true)`，先查幂等记录，再比较 revision、重新验证约束、写决定及关系投影、递增 revision。重放同一命令返回既有结果；同 key 不同 payload 拒绝。预览后被刷新、其他操作改变时返回 `STALE_PLAN`，重新加载预览。网络不在事务内。

## 12. 冻结的生产链路与取证实现

内部端口 `CreatorIdentityEvidencePort.inspect(SourceWorkNaturalKey)` 返回 Success/Unsupported/Failure；Success 含有序署名、类型化来源键、明确角色、名称/标题证据。Unknown/Unsupported 是可预期降级，不影响现有阅读与漫画详情更新。

两个 data adapter 的职责固定：

- 漫画柜：只解析详情作者栏的 `/author/<数字>/` 锚点，namespace=`manhuagui`；只接受登记域名及路径规则，不能从推荐栏收集作者。
- MangaDex：请求 `/manga/<UUID>?includes[]=author&includes[]=artist`，namespace=`mangadex`；分别读取 author/artist 关系的 UUID，UUID 相同可合并角色为 BOTH。详情必要时补取 `/author/<UUID>`，只用于候选证据；不扫描全站作者。

实际请求使用加载后的 `HttpSource.client` 和 headers；MangaDex 已确认的 KeiSource 继承关系使其符合这一入口。注册由两端扩展管理器提供的包名、已加载来源实例、可信安装状态和允许的站点域名共同决定；非 HttpSource、未知变体、未知自定义域名返回 Unsupported。没有默认网络客户端兜底，不新增 SSL/代理配置。

首版两次请求路径是明确取舍：原有详情业务照常执行；身份补证在用户打开“关联来源/整理作者”或已有作者发现任务中异步运行，不把补证失败变成漫画加载失败。正向证据缓存 24 小时，按来源配置版本、namespace、作品路径和 parser version 分键；用户手动刷新可失效缓存。403/429 不写“无作者”缓存；身份决定不随缓存过期自动撤销。

持久化入口统一如下：

| 现有入口 | 必须调整 |
|---|---|
| MangaRepositoryImpl / CreatorLibraryIndexer | 同一漫画事务内 upsert 原始 mentions，投影已确认映射；不再按每部作品自动 insert Creator |
| CatalogueCreatorDiscoverySourceAdapter | 保留普通署名 fallback，并组合 evidence port 的类型化标识；兼容现有来源能力 |
| CreatorDiscoveryService / CreatorIdentityEvidenceEvaluator | 名字匹配只能得到 POSSIBLE；只有全局 resolver 确认到任务 creatorKey 才进入 VERIFIED，不相符者排除，未确认者进入待确认作品 |
| commitSourceDiscoveryObservation | 事务内再次核对引用的决定/revision，防止取证期间身份改变；未确认结果不得生成“此作者新作”的确认通知 |
| AndroidMangaCreatorNavigator / Desktop 漫画详情 | 调用同一 resolver；已确认进入同一作者页，PENDING 打开共同语义的关联面板 |
| 作者关注、别名、拆分/合并、恢复入口 | 使用全局 key 及新决定入口；保留原来有效的功能，不通过旧 upsert 名字 API 绕过约束 |

Android 在 DomainModule 注册 repository/use cases，平台 source-provider 从现有 ExtensionManager / SourceManager 提供；Desktop 在 DesktopAppModule 注册同一 data 实现。作者发现的 Android 兼容入口也必须转到同一 resolver，不能只改 Desktop 已注册的端口。

作者页继续复用 CreatorWorkArchive 与 canonical work/version 模型：已确认作品关系按作品聚合来源版本，未确认跨站作品关系保留来源条目，不在本批顺带改造作品去重。作者确认不依赖作品自动匹配，避免“先假定同作者认同作品，再用同作品证明同作者”的循环。

## 13. 迁移、备份与同步的确定边界

迁移在 SQLDelight 下一可用编号实施（本轮最大文件为 24.sqm，实施前重新核对，不提前占号）。DDL 只增加字段和新表，不在 migration 中联网、按名字合并、删除旧 Creator 或修改阅读数据。

后台回填复用现有 keyset 分页，每页 250 部：生成 mentions，将旧已关联记录标 BOUND/LEGACY_UNKNOWN，重复或冲突关系保留为待整理。原关注和旧作者页保持可访问，并标记“历史身份待核对”；不把已关注者从列表隐藏。幂等唯一键允许中断重跑。只有确认之后才把全局档案标 CONFIRMED，并迁移关系及来源映射。新进入的未知署名只进入 PENDING，不继续制造旧式同名档案。

合并前导出经读回验证的作者归档备份；现有 merge 内核抽为单事务可调用函数，成组迁移、决定、映射及旧重定向一次提交。遇活动作者扫描 lease 时拒绝执行并显示“等待作者检查结束”，不强行清空租约后与后台任务竞写。

备份扩展确定为 AuthorArchive section v5：保留原 ProtoNumber 1–8，新增 9=identityKeys、10=mentions、11=identityEvidence、12=identityDecisions、13=decisionSubjects、14=distinctDecisions；BackupCreatorIdentity 新增字段 9=identityReviewState。外键转换为 portable/natural key，revision 在目标库重新建立，不传本地 ID。

恢复顺序：整体版本/结构校验 → 既有作者/作品及重定向 → 观察与证据 → 决定/否定关系 → 映射投影 → 关系/关注核对，置于现有恢复事务边界中。v1–v4 缺失信息按 LEGACY_UNKNOWN 处理；v5 中指向缺失实体、环或矛盾决定拒绝该作者归档导入且不部分写入。向已有库恢复时，若无法证明输入决定是当前决定的后继则记录冲突，不按墙钟时间覆盖。旧客户端不支持 v5，产品必须明确提示“需升级后恢复”，不能承诺无损向旧版回退。

在线同步采用明确的首版边界，不临时扩展 v1 wire：当前 SyncProtocol 仅有 FOLLOWING 等效果，没有身份决定效果，且校验拒绝未知版本/字段；不能把合并命令藏进作者显示名或 descriptor。首版交付跨插件本地作者库与双端备份往返；作者关注同步启用时，新增身份重定向/来源归集命令只允许预览，不允许提交。既有阅读/书架同步和旧关注记录维持不变，不自动关闭任何开关。此限制必须在入口先说明，不能等用户确认后才失败。

跨设备在线身份收敛是另一个协议批次，不能作为本方案已经解决的能力。若用户要求同时交付，则必须把协议升级、混合版本处理、离线并发合并/拆分和身份决定授权范围纳入新计划；不得以两个设备各自 UUID 稳定宣称同人全局收敛。当前跨插件方案不依赖这一扩展，在不开启作者关注同步的配置中具备完整可用路径。

## 14. 用户流程与验收实例

用户路径固定为：漫画详情点击署名 → 已确认直接打开全局作者；未确认显示候选作者及其代表作品/来源 → 关联、搜索已有作者或创建另一人。作者页提供“关联其他来源”“待确认作品”和原有身份管理入口；所有确认操作展示明确范围及结果反馈。

本次实例的预期过程：

1. 读取真机三个冈本伦档案的真实关系，核验并确认归集为全局 A。
2. 映射漫画柜 `363 → A`，后续同 ID 作品自动归到 A。
3. 在 MangaDex 的《平行天堂》署名中取得上述作者 UUID。通过代表作品和姓名证据，用户确认其属于 A；写入 `mangadex:UUID → A`，并将 Okamoto Lynn 作为显示别名保留。
4. 从两个插件点击作者均进入 A；关注 A 一次即可，来源过滤仍可单独设置。普通名字搜索返回的 Uran 不得加入 A；Fan Colored 版本不得凭标题相似自动与原作合并。
5. 无结构化 ID 的第三插件也可将选定作品署名批量关联到 A；后续未确认的同名署名仍在待确认区，不冒充已确认作品。

自动化通过线：共享数据库 Android/JVM 契约运行真实 production resolver；HttpSource+MockWebServer 验证 adapter；实际扩展加载与 DI 证明复用生产客户端；UI 测试证明两端导航到同一个 Creator；迁移/备份/幂等/组回滚/否定关系绕行/角色并集/扫描竞态均有失败后修复的红绿证据。普通 metadata-name-only VERIFIED 的原契约须改为失败用例并修复，不能只新增旁路测试。

真实发布通过线：两个真实插件、同一个作者、多作品与同名反例；Android 真机和 Windows 正式产物均完成操作。当前手机三个身份的具体历史仍未读取，因此这项是实施验收门槛，不影响上述结构设计，也不能提前勾选。

## 15. 实施工作包与估算

设计选择已确定，不再以“以后选择插件接口/以后决定匹配方式”作为未决项。实施需要验证的是具体产物，不是重新设计核心身份模型。

- A：共享存储、resolver、索引和作者发现统一，红绿覆盖名字误认及新观察不建重复身份。
- B：漫画柜/MangaDex adapter、来源注册、双端 DI；无 ID fallback 与真实扩展契约。
- C：双端跨插件关联 UI、历史归集、原功能保留、事务并发及备份 v5。
- D：相关模块完整验证、独立数据完整性审查、正式 Android/Windows 构建与真实运行验收。

A 是 B/C 的前置，B/C 稳定后进入 D；真正执行时按仓库规则将首个实施簇交给实施子代理，主代理负责整合与验收。本轮没有启动子代理或实现。首版预估 8–12 工程日，不含在线身份同步协议；主要成本为数据迁移、双端接线和真实发布验证，不是两处站点解析代码。若第二个实际扩展无法加载，先诊断版本/运行时问题，不能拿公开 API 请求替代插件验收，也不顺带扩大成全部扩展兼容重构。
