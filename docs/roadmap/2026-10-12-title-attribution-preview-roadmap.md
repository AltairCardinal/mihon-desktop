# 标题署名双端预研体验计划

用户授权：2026-10-12，以预研性质实现 Windows 与 Android 版本供体验。基线 `c2da62a163`；开始时工作区干净。完整设计见[署名与追踪设计](../2026-10-12-creator-attribution-and-tracking-design.md)。

active-task: none

## 本轮交付范围

按 sourceId 显式开启标题署名规则；识别前置 `[社团 (个人)]`，个人与社团分别进入现有作者页及关注/新作详情复核。两端作品详情提供确认、关闭、真实解析预览与显式整理本图源书架入口。原始字段、漫画自然键、收藏、阅读状态保留；别名沿用已有管理。

规则是用户确认启用的预研语法，并非已验证所有扩展版本的作者适配。启用后仅合格标题替代字段署名；不合格标题回退现有字段。首次操作重算当前作品，书架整理由用户显式触发，使用有界批次；不处理未收藏历史发现，不自动转移上传者关注，不新增实时纠错同步或完整来源搜索过滤器适配。

## 固定步骤和验收

- [x] TA-01 共享提取、来源偏好与生产链路：先失败测试，再实现同一 parser/policy 贯穿库索引和发现详情；确认原文保留、source 隔离、个人/社团分别匹配、人工关系保留、关闭回退、备份 evidence 往返和双端 DI。
- [x] TA-02 原生操作闭环：详情打开“标题署名（预研）” → 预览解析结果 → 确认开启/关闭 → 当前署名刷新；取消不写入，失败保留草稿；显式整理此图源书架 → 进度和结果，取消/失败说明已完成批次；真实按钮事件与接线测试、标题变更重组、角色标记、窄屏与主题可达。
- [x] TA-03 独立审查和定向补验：审查共享判断、自动关系替换、偏好失败恢复、备份与 DI；最多一轮审查与一轮修复复验，关闭具体阻塞后按受影响范围验证和格式检查。
- [x] TA-04 体验包与提交：Windows 用 build-desktop preview，核对 Final unpacked EXE 与 production runtime；Android 用 build-android candidate 交付签名候选，Debug仅用于隔离原生回归，核对身份与产物清单；授权且可隔离的模拟器执行本地夹具原生用户路径；记录实际通过/未运行项，只提交本任务文件。

## 流程预算

实施代理一个，主代理同时处理 UI、文档和只读环境核查；稳定后独立审查代理一个，总计最多两个。唯一 Gradle 协调者串行执行 focused 红绿、接线及格式检查；不运行完整模块或全量测试。Windows preview 一次，Android candidate 一次（遵守仓库用户交付要求），另一个Debug包用于隔离模拟器回归；编译失败后的必要定向修复可补构建，但显著增加成本先说明。

交付物是双端真实体验产物、实现测试及本计划中的紧凑证据；过程日志使用现有 Gradle 协调器，不新增生成器或状态系统。预计 1–2 小时，主要成本为 Kotlin 编译、focused UI/集成验证和平台打包。没有稳定发布里程碑，不递增正式 Desktop 版本，不宣称发布就绪。

## 执行证据

本批跨共享解析/关系索引/发现复核/备份与双端原生接线，超过8文件/400行仍为一个可体验闭环；无数据库迁移，不拆成无法独立验收的微提交。其他会话的 catalog-filter 改动单独保护。

- 核心红测：`title-attribution-red-2`（7/2失败）、`title-attribution-data-red`（2/2）、`title-attribution-editor-red`（10/5）、`title-attribution-chapter-red-3`（5/1）、`title-attribution-notification-red`（24/1）、`title-attribution-preference-red`（4/1）；均记录于 `.gradle-coordinator/<key>.log`。
- 核心 focused 基线 `title-attribution-final`：4个domain类24项、4个data类72项，共96项通过。覆盖提取、来源设置、库索引、详情发现、通知退出、章节目录、同步投影和备份证据。随后提交后取消的审查修复由 `title-attribution-review-red` 两项正确失败及 `title-attribution-review-green` 相关6项通过关闭；没有声称最终代码重新运行96项。
- 双端入口红测：`title-attribution-ui-red-2` 中Desktop3、Android2因缺少真实详情入口正确失败；Session4在上一红测后转绿。初次原生夹具使用不支持string-set的测试store，已改为真实平台偏好，该夹具异常不作为行为红证据。
- `title-attribution-ui-green`：Android UI2、Android DI2、备份6、restore wiring5通过；Desktop DI2通过，Desktop UI2失败为等待下一次弹窗重组不足，测试改为等待实际节点出现。所有调用仍执行真实OnClick与editor，不模拟实现。
- 独立审查一轮及唯一修复复验：提交后取消P1已由真实SQLite事务测试关闭；失败后完成数、正文个人/社团标记P2已修。`title-attribution-partial-red` 的Session、Desktop、Android各一项正确复现部分成功后结果丢失。复审源码通过，无新增阻塞，最终focused 12项（Session5、Desktop4、Android3）已通过；同命令的独立instrumentation编译因多余成员导入失败，修复后 `title-attribution-device-test-build-2` 与 `title-attribution-device-test-fix` 构建、限定格式检查通过。
- 原生Android：任务独占AVD `mihon-title-attribution-preview-20261012`、serial `emulator-5558`、API36/ranchu；开发APK `b597abb51793f9ad30bd48d47b4804e6d167ba97db1afdf5ad3776d4b2b2e27c`，隔离包名 `.fork.dev`。真实MainActivity→详情来源开关→个人页→关注→真实SQL断言及原字段保留，`TitleAttributionPreviewInstrumentationTest` 1项通过，日志 `.tmp/title-attribution-native-result-final.txt`。初次标题节点的精确唯一断言不适合合并语义树，修正测试选择器后通过，产品未改；实体设备未操作。
- Windows：`title-attribution-windows-preview` 成功；[实际EXE](../../app-desktop/artifacts/preview/windows/20261011T193020Z-cabf768a/Mihon-Desktop-0.11.19.91.292eaf5-unpacked/Mihon%20Desktop.exe)。manifest build/sourceIntegrity/productionRuntime均PASS，nativeInteraction为NOT_RUN；Desktop真实Compose组件事件已由4项focused覆盖，未把组件测试称为打包后整窗原生点击。
- Android：[签名候选APK](D:/Codex/worktrees/e63b/mihon/app/artifacts/android/0.19.4-aex.60-vc78-292eaf5682-release/Mihon-Fork-0.19.4-aex.60-vc78-release-universal.apk)，vc78，原证书连续，R8与资源压缩开启，遥测/更新器关闭；SHA256 `fe456b8369fc191ce1e8a407b06a49e0796ae0aa8379347a01b1305a8f244a82`。`build-android.py verify`通过；已在任务独占API36模拟器安装并核对APK身份/hash；冷启动正常。复用同一虚构DB/偏好夹具后发生一次启动异常，重启后真实R8详情显示个人/社团，点击进入青岚作者页并显示已关注状态；这不是用户升级/备份迁移验收。
- 两端冻结的production输入摘要同为 `62548c7785e4e9dd588cb96939d72960f2330415723f1db8ee75750d607b8bcb`，基于HEAD292eaf加本轮未提交差异。包含另一任务未接线的元数据接口占位，不把它当作已实现语言筛选，本提交排除这些文件。未执行全量、联网成人图源、厂商真机或完整发布矩阵。

## Gradle 执行权协调

已读取 catalog-filter 实施计划的分工和协调节。当前标题署名会话继续持有重型 Gradle，正在执行双端 UI focused 绿测，接着 scoped 格式、接线、Windows preview、Android candidate（交付）/debug（隔离测试）和隔离模拟器验收。最后构建完成后会在本节明确写“已交接”，此前请勿另起 Gradle。可接收 catalog-filter 计划登记的精确 focused 红测命令，在两个署名批次间串行运行并将 key/结果写回本节；不自动运行未登记测试。

署名交付前请保持其他 production 文件不再新增行为实现（红测和接口允许），避免体验包包含未验证的新产品行为；i18n 将在署名提交后释放。MangaLanguageDetector 和 domain/source/service 当前差异属于 catalog-filter，不由本会话暂存或提交。

Android交付入口校正：签名预检已通过；按仓库既有要求交付candidate而非开发APK，不扩大产品范围。预期增加R8/签名构建约5–15分钟，仍保持串行与focused范围，不执行完整发布矩阵。

已看到 catalog-result-domain/desktop/android 三条红测登记。当前仍在原生测试APK修正及双端交付打包，尚未交接；为避免红测后的新行为进入尚未冻结的体验包，安排在署名产物完成后释放执行权，由catalog会话按其计划执行三条已登记命令。没有执行过的catalog红测不记为已运行。

候选版本校正：当前工作树原75、本机主工作树77（只读核对）；首次release构建在发布前用协调器取消，未交付重复版本。按唯一版本文件分配78/0.19.4-aex.60，包名及证书不变，沿用候选构建/校验链，不运行全量测试。原生Debug验收仍绑定75的独立.dev包；release需独立核对安装启动。

原生视觉核查：Debug真实窗口，1080×2400浅色和900×2000深色；详情署名随宽度换行、对话框说明/预览/原始字段/书架整理/取消/保存可达。截图本机 `.tmp/title-attribution-native-detail.png`、`title-attribution-native-dialog.png`、`title-attribution-native-dark-dialog.png`，不作为正式发布像素一致性证据。

**Gradle 已交接（双端产物均已冻结，2026-10-12）**：`android-candidate` 的vc78签名候选、`title-attribution-windows-preview` 均成功。标题署名会话不会再启动Gradle；catalog会话可开始已登记红测及后续串行任务。i18n暂待标题署名最终提交（本节将标明提交后释放），请勿提前追加共享资源片段。当前余项仅安装最终APK、文档和显式文件提交。

## 已知运行边界与交接

### 用户体验反馈后的第二批（2026-10-12）

当前基线 `bac6fa05e5`。用户要求准备主干日常体验：无社团不显示社团；标题署名在原作者下方直接可见；点击进入完整作者页并支持标题检索作品。以下为新增批次，上一批证据不冒充本批通过。

- [x] TA-05 共享语法与发现：明确无社团署名、排除非创作者标签；标题模式图源用关键词召回并详情复核，复用完整作者档案、关注与发现链路。先 focused 红测，再最小实现与同范围复验。
- [x] TA-06 双端详情与作者入口：保留原始作者显示，候选署名直接位于下方；无社团不渲染占位。点击具有加载/错误反馈，进入既有完整作者页；原生事件和导航接线测试覆盖。
- [x] TA-07 独立审查、双端定向验收、日常产物与主干准备：仅提交本任务差异，核对主干状态后确定接入步骤。已知启动异常保持显式记录，未经验证不宣称修复。

SOURCE：两端当前均导航既有 AuthorDetailScreen，但 Desktop collectOnOpen=false；共享 adapter 优先 AuthorSearchSource，可能只搜索上传者字段。PROJECT_POLICY：复用既有作者页、源设置、错误恢复与发现预算；不新建精简作者页、不自动合并个人与社团、不改变原始元数据或已有关注。原生布局沿现有主题、封面比例和返回规则，标题候选行需在宽窄窗口换行并保持可点击。

流程预算：复用1名共享实施代理，1名独立审查；一轮审查和至多一轮修复复审。当前计划承接记录，不新增过程报告。focused 红绿与相关接线/格式/双端用户路径验证；不跑全量。预计1–2小时，主要成本双端编译与打包。

Gradle协调更新：catalog 会话仍持有执行权，本批不会自行并发启动。请 catalog 在其计划记录下一个可交接点或可代跑 focused 命令的窗口；本批先准备红测和只读调查。i18n 的 catalog_result_* 保持其独占，本批只处理 creator_* 自有差异，提交前按片段核对。无需消息回执，文件为协调依据。

已就绪的双端 UI 红测（每端新增2项）：`CreatorTitleAttributionUiTest`、`AndroidCreatorTitleAttributionUiTest`。可在 catalog 当前批次之间代跑以下命令并在此记录key/结果，或明确临时交接后由署名会话执行：

```powershell
python scripts/gradle-coordinator.py run --key title-inline-ui-red -- .\gradlew.bat -I .gradle-coordinator/title-attribution-ui-test.init.gradle :app-desktop:jvmTest --tests mihon.desktop.ui.authors.CreatorTitleAttributionUiTest :app:testDebugUnitTest --tests eu.kanade.tachiyomi.ui.browse.author.AndroidCreatorTitleAttributionUiTest --continue --console=plain
```

预期正确失败：当前header没有无需开关的 `title-credit-0` 行，且单个人语法尚不支持。无行为实现先于红测。此请求不授权启动并发Gradle。

第二批冻结合同：详情展示原始author/artist和独立标题候选；裸 `[名字]` 仅作为可点击候选，不自动推断社团或全源作者身份。显式点击进入既有完整作者根页，同时持久保留标题证据。作者发现根据该root的标题证据在现有启用/关注范围内使用关键词召回，详情复核完整已确认名称；源级自动索引开关独立，不随点击改变。无强人工关系时该点击建USER关系；已有USER/RESTORE保留权威字段与原证据，仅幂等追加新证据。发现模式为每次root查询局部值，不放到跨作者共享搜索缓存；首次模式切换沿用静默基线机制，防止旧作被当新作通知。上述持久化与模式隔离由真实数据库契约覆盖。

短窗口安排（2026-10-12 04:00 CST）：已确认最近catalog批次均终止、无活动Gradle；本批仅尝试由**同一个协调器的全局启动锁**领取 `title-inline-ui-red` 单条串行窗口，若busy则退出不启动。此窗口完成立即归还catalog后续安排，不同时启动第二条，也不绕过协调器。其他会话可按该key读取状态；这是共享协调器队列内的短窗口，不接管其整个实施批次。

`title-inline-ui-red` 已结束并归还：Desktop 6项/2失败、Android 5项/2失败，新增4项均因缺少 `title-credit-0` 可见行/单人行而正确失败；既有7项通过。日志保留在同名协调器.log，XML已读取核验。本批据此开始UI绿阶段。已读catalog的协调补充：**后续窗口必须等它在文件明确交接，不再以空闲推断**。下一请求为共享核心红测（具体命令在测试冻结后追加），无任何Gradle正在由本批运行。

下一个短窗口申请（已就绪）：共享核心红测已最终保存并冻结，包括baseline/旧scan/陈旧快照8条真实SQL契约；期待catalog当前修复批次之间明确交接以下一条命令（预计1–2分钟），本批没有运行中的Gradle。未获文件交接前本批不执行。可立即交接，由本批核实状态后启动，无需catalog代为判断夹具。

04:15 CST 接入安排更新：为完成主干准备且避免携带catalog未验收代码，已建立隔离工作树 `D:/Codex/worktrees/title-credits-main-daily-20261012`，分支 `codex/title-credits-main-daily-20261012`，基于main `49c25db9ba`，接入设计与已验收第一批；唯一版本文件冲突保留已交付78，不降级到77。**撤回上面的e63b窗口申请**：后续作者测试和体验打包在隔离接入树自身协调器串行执行；e63b重型Gradle完整归catalog，本批不再使用其Gradle。实施文件仍在e63b自有范围内维护，按明确文件清单同步到隔离树验收，不复制catalog生产/测试/i18n差异。两树不同时执行本任务的重型Gradle。

第二批核心红测：隔离树 `title-author-discovery-red`，基线 `ed64ce9822` +明确8个core测试/中性API文件，1m25结束。domain15项/5失败（parser2、planning3），data身份编辑25项/8失败；新增13项失败原因正确，其余27项通过。已核对XML：来源开关关闭时点击关系被重算删除、同名证据被改为NAME_EXACT、RESTORE被覆盖、标题root无法跨启用源发现、基线未重置、陈旧快照未拒绝。没有编译/夹具错误。完整日志位于隔离树 `.gradle-coordinator/title-author-discovery-red.log`，据此进入核心绿阶段。作者级mode仅采纳USER/RESTORE且VERIFIED的完整标题marker，AUTOMATIC源策略证据不越权升级跨源模式。

i18n第二批边界：仅调整base/zh-rCN/zh-rTW的既有 `creator_title_attribution_title`、`creator_title_attribution_description`、`creator_title_attribution_no_preview` 三键，明确直显姓名无需先开设置，旧入口仅控制源级自动归属。`catalog_result_*` 完整保留、不会由本批暂存。共享原树提交时须分别按键暂存，不能整份共享strings文件跨任务提交；隔离接入树从未复制catalog字符串。

```powershell
python scripts/gradle-coordinator.py run --key title-author-discovery-red -- .\gradlew.bat --no-parallel --max-workers=2 --continue :domain:jvmTest --tests tachiyomi.domain.creator.service.TitleCreditParserTest --tests tachiyomi.domain.creator.service.CreatorDiscoveryPlanningTest :data:jvmTest --tests tachiyomi.data.creator.CreatorIdentityEditingTest --console=plain
```

- Android最终APK额外验收保留一项观察：首次带夹具冷启动进入CrashActivity，`SecurityException: Caller app.mihon.desktop.fork ... cannot post for pkg com.android.chrome`，栈为 `NotificationExtensions.cancelNotification → ExtensionManager.updatePendingUpdatesCount`。再冷启动可用并完成作者页路径，不能据此说异常已修复。`App.getPackageName()` 仍读取主线程Chromium栈，即便调用来自后台；这与观察相符，但本轮未独立复现旧基线、未确认根因，也未扩张修改公共WebView/通知机制。日志/界面树 `.tmp/title-release-crash.xml`、`.tmp/title-release-author.xml`，实际作者页截图 `.tmp/title-attribution-release-author.png`。因此本轮只交付预研体验，不宣称稳定发布就绪。
- Windows packaged runtime PASS 是既有扩展运行时验证；整窗人工交互保持NOT_RUN，作者控件的真实Compose事件通过。Android虚构夹具Debug完整写入路径通过，R8候选读取/导航通过且带上述启动风险；未操作用户实体设备。
- 提交仅包含本功能、必要测试、此计划/设计更新和Android候选版本78。语言/翻译组任务、其报告292eaf和所有catalog红测文件均排除。
- Gradle已经释放。共享i18n在包含本计划的 `feat(authors): preview source-specific title credits on Android and Windows` 提交成功后释放；另一会话可用 `git log -1 --format=%s -- docs/roadmap/2026-10-12-title-attribution-preview-roadmap.md` 核对后写入catalog_result_*，无需另一个纯状态提交或队列回执。

第二批双端发现接线红测：隔离树 `title-platform-discovery-red`，Desktop/Android各1项共享数据库契约，均正确失败于“已启用来源标题作品为空”，无编译错误。随后同步冻结14个core文件与自有UI差异，开始 `title-author-daily-green`：parser、planner、extract、identity editing、executor state machine、双端UI和新增双端发现契约；均为类/方法限定，不是完整模块测试。

第二批唯一独立审查结果NEEDS_FIX：1）先读mode后读baseline有并发窗口，旧模式可能读取新generation并消费新基线；2）adapter按作品key共享搜索缓存时，title关键词结果会覆盖普通author搜索的readingLanguageTag。两项属于已冻结的基线/请求隔离边界，由原实施者补失败测试后最小修复，随后进行唯一一次限定修复复审。其余强关系、完整marker、陈旧快照事务和双端入口源码审查通过，仍以实际绿测为运行证据。POSSIBLE人工/恢复关系不因点击而静默升级确认级别，也不启用作者标题模式，此限制已同步设计。

第二批初版绿测：`title-author-daily-green` 成功（2m05），XML逐份核对91项、0失败、0跳过：parser3/planning12/extract7、identity25/executor31、Desktop UI6+共享发现1、Android UI5+共享发现1。此结果对应14文件冻结初版，不包含审查两项后续修复；后续按影响范围补验，不重复全量。

第二批Android候选预留：只读核对main77、原树78与隔离树78后，唯一版本文件在原树和隔离树同步预留79/0.19.4-aex.61；包名与原证书不变，尚未构建交付。其他会话后续候选须使用更高版本，不能复用79。主干工作树未修改。

审查回归红测：`title-mode-races-red` 两项均正确失败；混合mode缓存普通语言期望en实际null，真实库读序窗口使NEEDS_BASELINE被旧metadata计划消费。没有编译或夹具错误，已据此授权最小修复。范围说明：本批超过8文件/400行，但仍为同一可交付的“标题姓名进入完整作者追踪”能力；共享parser、SQL身份保护、发现请求和双端入口不可独立验收，新增文件主要为共享契约及两端真实接线测试，无新增schema、调度框架或独立作者页面。

两项审查修复已限定复审REVIEW_PASS，无新增阻塞。补强共享跨源契约，真实Service的标题scan必须对10/20/30来源调用带true的详情上下文，普通入口0次，避免假详情掩盖漏传。格式初次有测试长行和UI全限定引用失败，已拆开测试调用、导入既有helper；`title-daily-format-final` scoped domain/data/app Apply+Check成功。诊断时临时格式覆盖均已恢复，最终无.editorconfig改动或新增lint豁免。Desktop无Spotless插件，沿既有格式并执行diff check。格式后的自有Kotlin/SQL同步回原树，未覆盖已提交catalog的i18n。当前运行 `title-daily-final-focused`，在原91项加两race、既有作者导航/真实manual check接线，不运行完整模块。

第二批最终focused：隔离树 `title-daily-final-focused` 成功，12份XML共100项、0失败、0跳过。domain23（extract7/planning12/parser3/cache1）；data57（identity25/executor31/read-race1）；Desktop11（UI6/共享发现+manual check2/navigation3）；Android9（UI5/共享发现+导航4）。含修复后的两race、真实Service详情模式透传、嵌套Voyager目标及作者页手动检查按钮触发scheduler。独立复审REVIEW_PASS已收到；`git diff --check`通过。本轮没有运行任何完整模块/仓库全量测试。

第二批产物已构建（运行验收待下段）：Android `0.19.4-aex.61-vc79-ed64ce9822-release`，SHA256 `8ff7c64c4100a520ef6fda81171977dc47853968845c46913c99d7d28428f182`，原证书v2/v3验证通过、R8/资源压缩开启。Windows `title-daily-windows-preview` 成功，Final unpacked EXE实际存在于隔离树 `app-desktop/artifacts/preview/windows/20261011T205632Z-ea979b79/Mihon-Desktop-0.11.19.91.ed64ce9-unpacked/Mihon Desktop.exe`；preview-manifest build/sourceIntegrity/productionRuntime均PASS，nativeInteraction保持NOT_RUN。双端冻结productionInputs摘要一致为 `d2d045ec3a93a1cb9fd766310bd9df63750a483eaa723a1fb279802be242a6a1`，基于隔离树HEAD ed64ce9822加本批dirty差异；不包含catalog功能。正式candidate日志另保留隔离树 `.tmp/title79-release-build.log`，避免后续内部Debug同名协调key覆盖。

## 第二批日常体验交付与主干准备

- [Windows体验EXE](<D:/Codex/worktrees/title-credits-main-daily-20261012/app-desktop/artifacts/preview/windows/20261011T205632Z-ea979b79/Mihon-Desktop-0.11.19.91.ed64ce9-unpacked/Mihon Desktop.exe>)：来自本轮 `Final unpacked EXE:`，文件存在；启动前退出旧Windows实例。打包runtime PASS；Windows真实Compose事件/导航/作者检查链已通过，整窗人工交互仍NOT_RUN。
- [Android签名候选APK](D:/Codex/worktrees/title-credits-main-daily-20261012/app/artifacts/android/0.19.4-aex.61-vc79-ed64ce9822-release/Mihon-Fork-0.19.4-aex.61-vc79-release-universal.apk)：版本79，原证书连续，已安装并拉回核对身份/版本/签名/SHA256。
- 原生Debug单条 `TitleAttributionPreviewInstrumentationTest` 通过（3.186秒，OK 1 test），使用正式production DI、SQL与页面；清理的仅是专用模拟器 `.dev` 虚构夹具。不开源级开关即可看见/点击标题个人，进入完整作者页并关注，原始上传者/标题不变；“检查新作”入口存在。
- 签名R8候选在同一专用API36/ranchu、serial emulator-5558、AVD `mihon-title-attribution-preview-20261012` 上由78升级为79；冷启动成功（1147ms），复用既有虚构作品。真实点击标题个人后显示青岚完整作者页：关注状态、Add alias、Check new works、All sources、Find works和作品卡。900×2000深色窗口已查看截图，姓名位于原作者下方，布局可读。此操作不是对用户数据迁移的完整验收。
- 原生记录在隔离树 `.tmp/title-daily-native-test.log`、`title-daily-release-detail.xml/png`、`title-daily-release-author.xml/png`；测试结束已只关闭本任务模拟器。没有操作实体设备，没有访问成人图源或运行全量发布矩阵。
- 本次签名候选未复现第一批记录的启动SecurityException；公共启动代码未修改，旧问题仍未宣称修复。
- 接入分支为 `codex/title-credits-main-daily-20261012`，基于main `49c25db9ba`，包含设计、第一批及本批。主工作树未修改、main未合并，其 `docs/human/` 未跟踪内容原样保留。原e63b已由另一任务提交catalog `993aa6e0ee`，本批仅提交自己的32文件差异；隔离产物与验证不包含catalog功能，不把两个工作树的结果混称同一全量基线。

用户验收：打开一部有标题署名的作品 → 原始作者下方直接显示姓名 → 点击姓名进入完整作者页 → 点击“检查新作”，在现有启用图源和关注范围中按名称召回、详情标题复核 → 可关注与使用原有别名/作品筛选。单个人署名不显示社团；裸名字不标个人/社团角色。失败保留既有错误/重试反馈。

边界：单次沿用最多8源、4查询名、每源8页、3源并发；不是全站/全作品保证。标题追踪要求详情中可识别并匹配已确认名称的署名，搜索卡片或上传者字段命中不足以确认；既有归档保留。多人、歧义、失效图源不自动猜测，个人与社团不自动合并。已有人工/恢复POSSIBLE关系不会因点击静默升级，也不会开启作者级标题模式。图源自动归属设置与作者级确认相互独立。
