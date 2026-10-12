# 通用作品语言和翻译组筛选实施计划

状态：本批通用筛选实现、focused 验证和限定文件提交完成。研究基线 `292eaf5682`，产品验证基线 HEAD 为 `bac6fa05e5` 加本批及已保护的其他任务未提交差异；未把他方改动纳入本提交。研究依据见[研究报告](../2026-10-12-catalog-language-and-translation-groups-research.md)。本批不等于正文识别精度认证或正式发布。

active-task: none

## 本批用户路径和验收

图源搜索已有作者词 → 打开应用侧语言／翻译组筛选 → 选择中文和任一翻译组 → 原有卡片显示语言及组名，结果同时满足条件 → 当前页无匹配时仍可继续加载原始下一页 → 清除筛选恢复原查询和已加载结果。Android 与 Desktop 使用相同领域判断。

本批识别明确标题语言、翻译署名及现有元数据线索，保留未知／冲突；标签注明推断属性，不声称已核验正文。原题含日语不抹掉明确中文翻译证据，纯汉字不直接判中文。自动全文 OCR、长期人工纠错和备份迁移是研究提出的后续升级手段，不是原请求必须采用的实现方式；本批不使用尚未验证的 OCR 自动裁决。

- [x] CF-01 共享识别和筛选：先写真实 detector 红测；最小实现覆盖中文、简繁、其他明确语言、否定／招募、截断、冲突、翻译组和联合组；语言同类 OR、与组选项 AND，未识别可显式保留。复用现有语言契约，不改变作者归属／持久化 schema。
- [x] CF-02 双端原生搜索接线：即使 source filters 为空也有入口，保留关键词与原生筛选；卡片／列表显示语言和翻译署名，窄屏可操作，取消草稿不生效；过滤后零可见项保留原始页游标，有继续加载和范围反馈，不无界抓取。
- [x] CF-03 定向验收与提交：production 事件接线、旧请求隔离和分页回归；只运行受影响 focused 测试及格式检查；主代理验收共享机制，必要的一轮修复只补验影响路径。仅提交本计划列明的本任务文件，不发布正式产物或改变版本。

## 分工和文件边界

### 原生界面契约

- SOURCE：继续使用现有 SourceBrowseScreen／BrowseSourceScreen 的图源请求、原生过滤树、查询代次、登录恢复、canonical 漫画和详情入口。Desktop 封面仍为 7:10，Android 仍沿其 Book 组件；presentation-core 当前是 Android 模块，不能直接供 Desktop 消费，两端只分别适配 Compose 控件，识别与筛选语义在 domain 共用。
- PROJECT_POLICY：在现有筛选附近提供“语言／翻译组”，不依赖扩展提供过滤项；横向滚动的入口行与独立纵向滚动弹窗保证窄屏操作。中文、简体、繁体及常见语言可多选，组名来自已加载元数据；未知可单独保留，冲突归待确认。不重新创建 Pager／query generation，不清作者查询或原生 FilterList。
- PROJECT_POLICY：弹窗持有本次草稿，应用才更新当前浏览会话。重置只重置草稿；取消、Escape／Android 返回、遮罩关闭不修改条件，保留页面，返回触发器焦点。关闭搜索页结束本次局部筛选，不新增全局持久化偏好。
- PROJECT_POLICY：卡片／列表展示阅读语言和翻译署名，推断标记明确；未知不假定日文，简繁不从组名猜测。查询中、原始空结果、过滤后暂无匹配、请求错误分别反馈；错误仍用原有恢复动作。选项作用于已检查范围，不能声称图源全量计数。
- 验收：真实双端入口／草稿取消／应用／清除／下一页事件测试，Android 三种布局、Desktop 原生 Compose 离屏宽窄窗口检查；共用 domain 契约覆盖语言／组 AND/OR 与未知。正式安装包和系统级键盘焦点验收不在本批发布声明中，不能用离屏测试声称已经发布。

- 实施子代理一：`MangaLanguageDetector.kt`、独立的 `domain/source/service` 结果元数据／过滤文件，以及对应 domain 测试。不修改另一会话的 `TitleCreditParser`、CreatorDiscovery*、CreatorRole、SQL、DI、备份。
- 实施子代理二：待 CF-01 API 固定后接 Android `ui/browse/source/browse` 与 `presentation/browse`、对应 Android focused 测试。不得改 manga 详情文件。
- 主代理：Desktop `ui/browse/SourceBrowseScreen.kt` 及独立筛选组件／测试、必要共享文案、此计划和研究报告维护、整合验收。
- 翻译组证据在 source result 独立命名空间内，不能作为 `TITLE_CREATOR`／`TITLE_CIRCLE`。保留原始名称，不冻结新的公共 translator 持久化 schema。
- 共享 i18n 文件已有另一会话改动：本任务只增加 `catalog_result_*` 前缀键，正式写入及提交前协调边界；双方不得把对方片段一起提交。若不能安全分片，等待另一会话提交后再加入。

## Gradle 执行权协调

当前：本任务持有 e63b 的串行 Gradle 执行权。对方第二批已改在 `D:/Codex/worktrees/title-credits-main-daily-20261012` 隔离验证／构建；e63b 中它继续维护的作者文件与共享文案片段仍需保护。以下保留交接依据，描述过去状态，不是新的窗口请求。

交接前另一标题署名实施会话持有本 worktree 重型 Gradle 的执行权。本任务只准备红测／接口与文档，未启动第二套 Gradle，未在相关红测前写行为实现。

请求：该会话在其自身计划中记录可交接的时点，或在现有串行队列中运行本计划后续登记的 focused 命令。所有命令继续经 `scripts/gradle-coordinator.py`，不得并行；状态与证据写文件，禁止用往返消息回执代替记录。

已就绪，登记以下三条 focused 红测，在明确交接后由本任务串行执行；可复用禁止自动重试的 init script。Debug Robolectric 与当前署名验证一致，不构建交付物。每条均应保留失败输出并继续后续命令，编译／夹具错误不能当作行为红测。

```powershell
python scripts/gradle-coordinator.py run --key catalog-result-domain-red -- .\gradlew.bat :domain:jvmTest --tests tachiyomi.domain.source.service.SourceResultMetadataContractTest --tests tachiyomi.domain.creator.service.MangaLanguageDetectorTest --console=plain
python scripts/gradle-coordinator.py run --key catalog-result-desktop-red -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.ui.browse.CatalogResultFilterWiringTest --console=plain
python scripts/gradle-coordinator.py run --key catalog-result-android-red -- .\gradlew.bat :app:testDebugUnitTest --tests eu.kanade.tachiyomi.ui.browse.source.browse.AndroidCatalogResultFilterUiTest --tests eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceRecoveryBehaviorTest --console=plain
```

预期行为红：共享 detector 缺少标题标记／翻译组识别；双端无 `catalog-result-filter-open` 入口；Android 重复原始页被现有 reducer 去重为空导致分页边界丢失。原有语言与恢复用例应维持通过。实际红绿结果、协调器 key 和失败原因仍在本文件记录，不另建逐任务报告。

已读署名计划中的协调回复：其交付前本工作树仅保留红测和兼容接口，不加入未验证行为；i18n 在其提交后释放。若协调者代跑发现编译错误，可将错误位置写回署名计划，由本任务修测试后登记重跑。

协调更新：对方已在文件确认读取三条命令，改为其双端产物完成后释放执行权，由本任务执行，未代跑。红测准备及 UI／分页契约核查已完成。

已接收署名计划的“Gradle 已交接（双端产物均已冻结）”：本任务现在持有串行执行权，已启动 `catalog-result-domain-red`；Desktop、Android 依次运行。i18n 仍等待其显式提交释放，其他任务文件继续保护。

署名第一批已提交 `bac6fa05e5`，i18n 的 12 个 `catalog_result_*` 已由本任务加入。已读其第二批计划；`title-inline-ui-red` 短窗口占用期间本任务启动被协调器 busy 拒绝，未并行；该窗口结束后接续 `catalog-result-review-red-desktop-green`。后续需要短窗口请先登记到文件，本任务将明确交接，不以短暂空闲取代执行权交接。尤其测试 XML 会被后续同模块任务覆盖，须先保留 focused 摘要；不得把他方测试结果当本任务结果。

## 执行证据

- `catalog-result-domain-red`：22 项（新共享契约 15、旧 detector 7），12 项因缺少真实行为失败；旧 7 项全部通过。失败包括日文原标题覆盖 `[Chinese]`、同级冲突被取第一个、翻译署名和 AND/OR 筛选未实现。不是编译失败；据此开始共享规则绿阶段。
- `catalog-result-desktop-red`：900／360 宽原生 Compose 场景均通过初始作者查询和首批结果断言，然后等待不存在的筛选入口失败（2/2）；据此实现本地筛选、草稿与有界下一页。
- `catalog-result-android-red`：8 项中 3 失败，缺少入口和重复 raw 页边界是有效行为红；完整 Screen 用例在初始加载时超时，先修测试同步，不能当作实现完成证据。其余 5 项既有恢复用例通过。
- `catalog-result-domain-green-android-red-2`：共享 15+7 全绿；Android 夹具补初始 Compose 同步后完整页在第108行筛选入口处正确失败，另一个入口和重复raw页也正确失败，其余5项继续通过。开始 Android 绿实现。XML随后被他方同模块focused任务覆盖，协调器日志保留实际失败点和统计，不宣称其为最后代码绿测。
- 主代理独立共享语义审查第一轮：发现“無邪気漢化組”被泛化否定前缀排除、匿名／机器处理标记被造为组织、两个完整组名的联合署名未拆三项。先保留首次绿，再补真实反例红测与限定修复，不另起审查任务。
- `catalog-result-review-red-desktop-green`：共享25项仅新增3个指定反例失败；Desktop57项中55项通过，新宽窄窗口2项走通筛选、分页、组和数据库实时投影后，因清除筛选仍锚定旧结果位置失败。已显式把过滤条件变化的网格位置回到首项，保持原始结果和请求不变。
- `catalog-result-focused-green`：共享25项、Desktop7项均通过；Desktop另50项既有共享状态通过结果复用上一命令（后续只改列表滚动复位）。Android16项中15通过，完整链路测试因题名处于合并节点的子语义树而找不到选择器，XML确认unmerged树存在；保留全部断言并修选择器，补“隐藏已收藏”真实页面接线后复验同类，不扩大模块范围。
- 共享语义审查修复复验：3项反例全部转绿，源码已核对否定仅作用于角色短语、匿名处理不造实体、显式联合保留两个原名与同一原证据；本轮独立审查关闭。置信数值是规则强度，未用真实站点留出集校准，不是准确率承诺。
- `catalog-result-android-green-format-check` 在配置阶段发现 Desktop 未应用 Spotless，无产品测试执行；第二次限定检查不能跨 projectDir，也不算格式通过。最终用忽略目录内的 init script 在 Desktop 临时应用仓库 `mihon.code.lint`，对 domain／Android／Desktop 各自显式列出本任务 18 个 Kotlin 文件，不改构建配置，不扫描或修正其他任务文件。
- `catalog-result-android-green-format-check-2`：Android 17 项中16通过。新增隐藏收藏用例失败的已证实原因是 `InMemoryPreferenceStore` 每次构造新偏好实例，设置后再取值仍是默认值；UI 测试改用隔离 SharedPreferences 的 `AndroidPreferenceStore`，增加重新实例化 SourcePreferences 后值仍存在的断言，保留原验收。
- `catalog-result-format-check` 已真正执行三个限定 Kotlin 格式任务：domain通过，Android／Desktop仅换行、缩进和import等格式项；`catalog-result-format-apply` 完成同范围修正。后续复验必须覆盖格式后的代码。
- `catalog-result-android-final-desktop-label-red`：格式后的 Android 17 项全部通过（完整 Screen UI 4、分页恢复6、ScreenModel7），三个限定格式检查通过。Desktop 新增“来源 genre 未核验仍标推断”用例正确失败，原宽窄两场景仍通过；修正 label 条件使其与 Android 一致，不改检测／请求链路。
- `catalog-result-final-focused`：格式后共享25项（新契约18+旧detector7）、Desktop8项（本批3+canonical4+原生筛选parity1）全部通过，0失败／错误／跳过；18个 Kotlin 文件的三个限定格式任务通过。Android17项复用上一命令，之后只改 Desktop label，未把组合证据写成最终全库重跑。共50项本批 focused 用例有效通过；此前额外50项共享状态回归仍有独立通过记录，不计入本批50项。
- 三份资源XML可解析，各12个 `catalog_result_*` 键唯一且占位符一致；本任务 `git diff --check` 和27处本地文档链接核验通过。Desktop 360／900宽真实 Compose 离屏图像已检查，Android三布局通过真实Screen事件测试；未执行系统级窗口／实体设备交互，不生成本批EXE/APK，不运行全量、正式发布或版本分配。

## 交付与复验入口

最终产品范围是源内浏览／搜索的会话筛选与派生标记。OCR、正文准确率认证、详情人工更正、持久标签／备份、排除组、全局跨源搜索仍未实现，不因本批勾选完成而提升为已交付。语言置信度不进入人物关系置信度；同作者、同组或同标题不建立 canonical edition/work 等价。

共享接口在 `domain/source/service`：`SourceResultMetadata.from(SManga|Manga)`、`CatalogResultFilter.matches`。保持现有 detector 调用兼容；翻译组原名和原始证据仅做临时投影，不改 public translator schema、CreatorRole 或 SQL。另一会话可按这些文件复用发现，本批没有给其生产文件或资源片段代提交。

Focused 测试可按以下范围复验；每条独占本worktree协调器，命令完成后再运行下一条，不添加未限定模块测试。此次禁用自动测试重试，原始日志位于忽略目录 `.gradle-coordinator/`，上述 key 可定位相应记录。

```powershell
python scripts/gradle-coordinator.py run --key catalog-domain-recheck -- .\gradlew.bat --no-parallel --max-workers=2 :domain:jvmTest --tests tachiyomi.domain.source.service.SourceResultMetadataContractTest --tests tachiyomi.domain.creator.service.MangaLanguageDetectorTest --console=plain
python scripts/gradle-coordinator.py run --key catalog-desktop-recheck -- .\gradlew.bat --no-parallel --max-workers=2 :app-desktop:jvmTest --tests mihon.desktop.ui.browse.CatalogResultFilterWiringTest --tests mihon.desktop.ui.browse.SourceBrowseCanonicalResultWiringTest --tests mihon.desktop.ui.browse.SourceBrowseFilterParityTest --console=plain
python scripts/gradle-coordinator.py run --key catalog-android-recheck -- .\gradlew.bat --no-parallel --max-workers=2 :app:testDebugUnitTest --tests eu.kanade.tachiyomi.ui.browse.source.browse.AndroidCatalogResultFilterUiTest --tests eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceRecoveryBehaviorTest --tests eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModelBehaviorTest --console=plain
```

仅本批18个Kotlin文件、2份文档以及3份共享资源中的36个 `catalog_result_*` 新增条目进入同一功能提交。目录内没有新增独立状态服务或报告；格式 init script、截图和日志是忽略的过程产物。共享Gradle执行权在本批提交后释放，后续任务仍须经协调器串行。

## 原实现批次预算与退出规则

最多 2 个实施子代理，主代理对未实施的共享识别部分作一轮独立验收；不扩大到数据迁移或公共 HTTP 重构。focused 红绿、双端接线及格式检查各按真实需要串行执行，不运行未限定测试类的完整模块或全量测试。本轮不分配正式版本，不构建正式安装包。预计 1–2 小时，主要成本为 Kotlin 编译和双端 UI 验证。

超过 8 文件／400 行时按该完整功能闭环评估内聚性，不为压行拆开接线。相关验证不能完成时保留未完成项，继续处理可独立工作；不得再次把文档交付当作实现完成。

## 用户体验包构建（2026-10-12）

用户已明确要求Windows和Android体验包。在隔离目录 `D:/Codex/worktrees/catalog-pack-20261012` 构建。准备期间对方第三批已完成focused验证并提交，因此在首次构建前把冻结基线从 `7d3c3d34bf` 更新为 `1d26852128`，包含本批 `993aa6e0ee`、作者追踪第二批和已验收详情同行署名调整 `6c34c05e25`。双方通过此文件核对范围；不请求原树的Gradle窗口、不改对方文件、不合入main。

Android候选预留 `versionCode=80`、`versionName=0.19.4-aex.62`，原树及隔离构建树同步该唯一版本文件；其他候选必须使用更高号码。Windows采用 `scripts/build-desktop.sh preview`，Android采用 `scripts/build-android.py candidate`，不运行全量测试、不安装设备；两端在隔离树串行构建。两端源码冻结输入一致，Android正式签名和Windows production runtime由统一入口验证。原生用户体验交由用户，本轮不宣称正式发布或整窗交互验收。

预算：主代理直接执行，无子代理／额外独立审查，预计15–30分钟；复用本批50项focused证据与作者第二批已记录证据。失败仅诊断和重试受阻入口，不扩张产品范围；交付实际EXE／APK及可追溯清单，在同一构建交付提交记录版本与结果。

构建完成：Windows协调器 `catalog-windows-preview` 成功，实际Gradle 1m05；Android统一入口自身协调器 `android-candidate` 成功，实际Gradle 3m56。没有执行测试任务或全量矩阵，没有安装或操作模拟器／实体设备。

- [Windows体验EXE](<D:/Codex/worktrees/catalog-pack-20261012/app-desktop/artifacts/preview/windows/20261012T043610Z-610be4a7/Mihon-Desktop-0.11.19.91.1d26852-unpacked/Mihon Desktop.exe>)：版本 `0.11.19.91.1d26852`，来自本轮日志的 `Final unpacked EXE:`，文件已核对存在。退出旧实例后打开；运行依赖同目录完整应用文件，不能单独搬走EXE。preview-manifest 的 build/sourceIntegrity/productionRuntime均PASS，nativeInteraction保持NOT_RUN。
- [Android签名APK](D:/Codex/worktrees/catalog-pack-20261012/app/artifacts/android/0.19.4-aex.62-vc80-1d26852128-release/Mihon-Fork-0.19.4-aex.62-vc80-release-universal.apk)：版本 `0.19.4-aex.62`／code80，包名 `app.mihon.desktop.fork`，四ABI universal、minSdk26、R8及资源压缩开启。统一 `verify` 再次成功，原证书连续，签名v2/v3有效；SHA256 `e9944cb51d5b2477272d01dab9a9001eec9dc816abe4d92a11ad47ee6ce1d3ad`。
- 两端冻结product输入摘要相同：`6fb813d3f34f506e8ebca9b462e60dbc331711377d560e332a0a0503933025f7`。源码为 `1d26852128` 加已预留80/.62版本文件，清单如实记录dirty版本分配，未把未提交内容称为该hash本身。与已交付vc79隔离树相比，45个SQL迁移文件逐字节一致，最高45；本批未更改schema。此检查不替代实体设备升级验证。
- 体验范围：源内搜索 → 语言／翻译组 → 中文、未知和组条件 → 查看派生标记及继续加载；作品详情显示已提交的同行标题作者署名并可进入完整作者页。正文OCR、真实来源语言精度认证仍未实现；本批不宣称Android运行/升级或Windows整窗人工交互PASS。

隔离工作树和其中完整产物目录保留供用户体验，不执行清理。版本预留及本段交付记录在原e63b只暂存这份计划和 `gradle/android-release.properties`；不合并main、不覆盖先前体验包、不提交生成产物。双方可通过本文件读取构建完成状态，无需消息回执。
