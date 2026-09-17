# 收藏缺失插件「建议安装」完整实施 Roadmap

- 日期：2026-09-16
- 状态：COMPLETE（2026-09-17）；EIS-01 至 EIS-05 全部完成。完整测试、独立审查和三端正式运行证据见第 9 节，checkoff 与最终修复纳入同一功能提交。
- 类型：产品 child plan；进度从第 4 节第一个未勾选批次推导，不另设活动任务字段。
- 父路线：[Android / macOS / Windows 正式 Roadmap](2026-06-30-mihon-desktop-refactor-roadmap.md)。本专项已完成并同步父计划登记；不切换父计划执行指针，不恢复其他专项。
- 产品依据：[需求与技术可行性](../2026-09-16-extension-install-suggestions-requirements.md)；已通过的 [HTML DEMO](../prototypes/multi-device-sync/index.html)，交互提交 `2289a939f`。
- 唯一专项进度权威：本文件的批次清单与完成证据。若涉及已有 capability，机器状态仍以 `app-desktop/src/test/resources/parity/parity-manifest.json` 为准；本轮不修改其状态。

## 1. 交付目标与冻结边界

在 Android、Windows、macOS 的「浏览 → 插件」中，用户能够看到收藏漫画所缺失且能匹配到的插件，记忆折叠、忽略建议、打开对应网站，以及单项/全部安装。Android 的系统确认、权限和取消行为必须经过真实平台链路验证。

### 已通过的交互及生产映射

| 项目 | 实施约定 |
|---|---|
| 分组顺序 | 建议内容位于已安装列表内容前，不新建顶级导航。Android 在原「已安装」分组前插入；Desktop 保留已有「已安装 / 可用」页签，在「已安装」页内容顶部呈现建议区，普通可用页继续保留安装入口。DEMO 的纵向顺序映射到既有产品容器，不重建整页导航 |
| 折叠与忽略 | 首次展开，立即本地持久化；忽略按仓库身份+包名保存，提供即时撤销，不增加长期忽略管理页 |
| 网站 | Android 使用既有 WebView；Desktop 使用既有外部浏览器并标「打开网站」。多关联源先选择；无网址时禁用并解释 |
| 安装 | 单项直接启动；全部安装一次清单确认；显示各项进度与成功/失败/取消/跳过汇总 |
| Android 取消 | 当前项取消后暂停后续系统提示，由用户选择继续或停止；不能把一键安装解释为系统免确认 |
| 本地性 | 新增忽略/展开/批次状态不参与同步。采用既有 app-state 偏好语义，同时排除应用备份导出与恢复；这是本计划的设备本地实现选择，不声称 HTML 已验证备份链路 |
| 可审阅的异常 | 未匹配、目录不完整、安装失败、权限缺失、来源需选择均有解释与可执行下一步 |

本专项不做自动添加仓库、后台自动安装、自动迁移漫画、改写 source ID、跨设备安装列表同步、Desktop 浏览器引擎、全站兼容普查或旧同步 DEMO 问题修复。已装但不受信任或加载失败归入既有诊断/信任链路，不伪装成未安装。

完整交付必须包括真实 production 接线、跨端契约、Android 平台行为、Windows/macOS 发布运行证据。HTML 截图和本地定时模拟不能替代产品验收；任何平台未验收均保留限制且不勾选最终批次。

## 2. 当前事实、复用入口与必要增量

以下路径均相对仓库根；实施前核对当前事实，不把历史文档中的旧缺口重新实现。

| 链路 | 已有入口 | 本专项增量 |
|---|---|---|
| 收藏源计数 | `app/src/main/java/eu/kanade/domain/source/interactor/GetSourcesWithFavoriteCount.kt`；`data/src/androidMain/kotlin/tachiyomi/data/source/SourceRepositoryImpl.kt`；Desktop 的 `DesktopUiDependencies.kt` 和 `MigrationSourceScreen.kt` | 复用仓库 Flow 与源注册状态，不能复制漫画查询或要求用户先进入迁移页 |
| 插件目录与匹配字段 | `domain/src/commonMain/kotlin/mihon/domain/extension/model/ExtensionArtifact.kt`、`service/ExtensionCatalogService.kt` | 新增 source ID 反向索引及建议投影；沿用目录版本、兼容、来源和缓存策略，不写第二套 parser |
| 安装存在性 | Android `ExtensionLoader.kt` 的系统/私有包查询；Desktop `DesktopExtensionManager.kt`、`DesktopExtensionLoader.kt`、产物元数据及 diagnostics | 增加不会把加载失败当不存在的只读 inventory adapter。Desktop 已安装列表源自 loadedExtensions，不能直接作为完整文件安装清单 |
| 展示及注入 | 两端 `ExtensionsScreenModel.kt`，共享 `domain/.../extension/presentation/`；Android `GetExtensionsByType`，Desktop `DesktopExtensionPresentationPort.kt`、`di/DesktopAppModule.kt`、`DesktopUiDependencies.kt` | 共享建议语义，接入已有 state 与 action store；UI 只渲染及发动作，不自己扫包或匹配 |
| 安装事务 | `ExtensionInstallCoordinator.kt`、Android `ExtensionInstaller.kt`、`installer/Installer.kt`，Desktop 现有 install port | 增加请求去重与批次编排。Android downloadAndInstall 会取消原同包事务，不能直接对建议列表 forEach |
| Android 平台提交 | PackageInstaller/Shizuku 服务队列；Legacy 直接启动 `ExtensionInstallActivity` | 统一在上层协调一次一个交互提交，保留真实系统回调、清理和来源确认 |
| 网页 | Android `ExtensionsTab.kt`、`WebViewScreen.kt`；Desktop `DesktopUrlOpener` | 选择关联缺失源的 baseUrl；缺失源实例时仍能打开网址，不假设可用插件请求头 |
| 本地偏好/备份 | `Preference.appStateKey`；Android `PreferenceBackupCreator.kt`；Desktop `DesktopBackupCreator.kt`、`DesktopBackupRestorer.kt` | 使用设备本地键并测试真实导出/恢复。已读代码证实 Android/ Desktop 导出排除 app-state，Desktop 恢复也排除；Android 恢复仍需核实并按最小范围补齐 |
| 同步 | `domain/.../sync/SyncProtocol.kt` 的显式业务事件 | 不增加事件类型或载荷字段；同步改变本机收藏后自然重算，不传播建议状态 |

能够复用的查询、下载、签名校验、安装回滚、错误映射和导航都继续复用。新增独立部分仅为过去没有的建议计算及跨安装器批次控制；它们放在共享业务层，平台系统 API 留在 adapter，不共享 Android UI 或 Desktop 类加载实现。

## 3. 先冻结的契约与状态规则

以下为拟定职责，类型命名可沿用现有命名规范，不强制创建等量文件。

### 3.1 输入、输出和一致性

- 输入为 `LibrarySourceCount(sourceId: Long, count)`、当前可用 source IDs、插件目录快照、安装 inventory、忽略集合及展示选项。不得把 Long 转成 Double，不能按图源显示名称猜归属。
- 安装 inventory 至少区分初始化中、已存在、确认缺失和无法判断，并保留包名/来源/加载结果。Android 安装存在性以包名和系统/私有拓扑为准，不因仓库不同而认为同包可以再装一份。
- Desktop 只枚举既有插件目录与受管理的最终产物，不把暂存、下载中或失败回滚文件当作成功安装；诊断不明的旧手工包保持未知。
- 输出包含按插件聚合的建议、受影响源及漫画计数、来源选择项、未匹配原因、初始化/目录失败状态。每个源的互斥候选最终最多选择一个；同一插件覆盖多个源只显示一行。
- 候选身份沿用规范化仓库 URL+签名指纹+包名；版本更新不解除忽略。歧义时逐项选择并说明来源，不默认选择最高版本或第一个仓库。全部候选都忽略时不再反复显示该源未匹配提示。
- 未加载不等于未安装；已有可用 source ID 时不推荐替代插件。候选只知道部分平台兼容时沿用现有策略，不能宣称“目录匹配”等于运行成功。
- Flow 订阅覆盖收藏增删/迁移/恢复/同步、源清单、安装/卸载、目录刷新、忽略变化。初始化未齐时不闪现错误建议；异步旧结果不能覆盖新快照。
- 搜索只过滤展示和本次安装范围；语言筛选不隐藏收藏触发的建议，内容安全限制照常生效。普通可用条目和建议条目共用安装进度与忙碌状态。

### 3.2 安装命令和批次生命周期

- 所有受影响入口在真实提交前进行同包原子预留；建议、普通安装、更新/更新全部之间不能通过重复请求互相取消。保留显式取消/重试的语义，禁止粗暴改变所有既有重复调用策略。
- 批次清单固定 package、repository identity、version/artifact identity；提交前再校验收藏资格、忽略、包存在性与仓库是否仍有效。已安装/已排队可跳过，来源或版本变更需刷新后重新确认，不能静默换包。
- 拟定状态：确认清单 → 排队 → 下载/校验 → 等待来源或系统确认 → 安装提交 → 重载 → 成功/失败/取消；另有等待权限、暂停、停止后续、批次完成。
- 默认串行提交整个建议批次，复用现有下载引擎，不增加并发优化。其他普通安装入口继续遵守平台队列与同包锁；并非禁止其他包的所有后台工作。
- 用户取消系统确认：当前项取消，剩余暂停；如果没有剩余项则直接结束，不显示无意义的继续按钮。普通单项错误记录失败并继续其他无关项；权限/服务/身份前置失效则暂停。
- 停止后续：取消可取消阶段并释放预留；已进入不可逆提交的当前项继续接收真实回调，不假报取消、不卸载成功项。晚到回调只能修改其事务，不推进新的批次。
- 清单弹出期间、排队后及下载中修改安装器都必须被识别：冻结当前项采用的方式，未提交剩余项暂停并重新确认；不能仅在首个点击时检查。
- 离开页面不销毁应用级安装事务；重进显示实际进度。进程退出后不重放整个建议批次，先协调原安装器残留回调并重新核对安装清单，用户可对仍缺失项重新发起。
- 成功必须由真实提交及运行时结果反馈。包已落地但加载失败不再推荐重复安装，转入诊断反馈；收藏或目录变化不影响既有安装事务的真实性。

## 4. 批次总览与依赖

每个 checkbox 仅在实现、必要独立审查、验证、提交均完成后勾选；批内步骤不另设进度 checkbox。第一个未勾选项就是下一个执行批次。

- [x] EIS-01：共享建议识别、安装存在性与双端 production 接线。
- [x] EIS-02：双端建议分栏与完整单项操作、本地偏好及数据隔离。
- [x] EIS-03：共享批次编排、安装入口去重与 Desktop 全部安装闭环。
- [x] EIS-04：Android 全部安装、系统交互与生命周期闭环。
- [x] EIS-05：跨端集成收口、正式产物与真实运行验收。

依赖为 EIS-01 → EIS-02 → EIS-03 → EIS-04 → EIS-05。EIS-01 的共享契约同时服务两端；EIS-03 的安装仲裁必须完成独立检查后 EIS-04 才消费它。两端共享 ScreenModel/安装边界多，默认串行交付，不为追求并行制造冲突。

EIS-01 是有真实消费方的内部能力，EIS-02 是可用的单项建议功能，EIS-03 是 Desktop 批量能力，EIS-04 是 Android 批量能力；EIS-05 只负责完整发布。不得把每个 UI 文件、测试类、checkoff 或格式化拆成独立任务。中间提交不代表整个需求完成，不发布缺少承诺能力的最终版本。

## 5. 详细任务定义

### EIS-01：可靠发现收藏缺失插件并接入两端状态

**用户行为与交付边界**：为插件页提供可靠的建议状态，保护“已安装不重复推荐”。属于内部批次，不增加孤立 UI；两端真实 ScreenModel 必须消费新状态，EIS-02 才呈现完整操作区。

**前置**：核对当前 Git 基线、已有未提交文件、现有扩展专项结果和 Gradle 进程；读真实 repository/catalog/loader 接线。记录最小可用测试命令，不建立新快照系统。

**实施步骤**：
1. 先写共享行为红测试，覆盖本地源排除、同名异 ID、64 位 ID、一包多源、已装未信任/加载失败、初始化未齐、目录不完整与来源歧义。
2. 抽取共享建议计算和明确的库存输入；建立目录源 ID 索引，复用来源与版本策略，提供可解释未匹配状态。
3. Android adapter 复用系统/私有查询；Desktop adapter 从最终产物、metadata、diagnostics 补齐成功加载列表之外的存在性，测试残留临时文件与未知元数据。
4. 注入两个 ScreenModel/presentation 链路，订阅真实收藏 repository、源状态与目录快照；避免每次渲染扫磁盘/网络。
5. 写一条真实 SQL 收藏变化→仓库→建议投影的两端集成契约，覆盖移除收藏、迁移到另一个 source ID、插件卸载后重算及旧结果晚到。

**验证与验收**：同输入两端契约结果一致；破坏 DI 或移除实际 Flow 订阅后测试失败。数据未就绪不错误推荐；未知库存保留解释；无测试内复制匹配算法。共享与相关平台 focused、wiring、受影响格式通过，独立检查库存准确性后提交。

**预期修改范围**：domain extension/presentation；两端 extension adapter、ScreenModel/DI；必要的 data 测试与 existing source 查询接线。不新增数据库 schema、不移动全部迁移代码。预计 1–1.5 工程日。

### EIS-02：可发现、可忽略、可访问网站、可单项安装

**用户入口**：浏览 → 插件 → 建议安装；两端一次交付相同语义。前置 EIS-01 已验收。

**实施步骤**：
1. 先写两端 UI/presentation 红测试：建议位于已安装之前、计数与多源描述、初次展开、刷新/重启记忆、忽略与即时撤销、搜索范围及零结果。
2. 使用既有 UI 组件和主题实现折叠区；保留 Desktop 现有页签与 Android 待更新分组；大量建议采用有界滚动，不挤出原页面。添加资源文案和读屏/键盘语义。
3. 本地 app-state 存展开与规范化忽略集合。忽略从建议移除但普通列表仍可安装，版本升级不清除；运行中的安装禁用忽略以免误解为取消。
4. 复用 WebViewScreen/DesktopUrlOpener；多关联源选择、URL 合法性和缺 URL 禁用反馈；来源歧义使用候选选择对话框，未选择不提交。所有新增导航均验证层级与 Screen 类型。
5. 单项安装调用既有 production action 与进度状态；按钮在重复点击时不能再次提交，普通可用项和建议项立即共享忙碌状态。安装完成重新核对库存，失败保留重试、已装加载失败显示诊断。
6. 空目录、加载失败、平台不兼容、未匹配提供既有仓库/迁移/诊断入口，不自动添加仓库或改收藏。
7. 用真实备份 creator/restorer 证明新增 app-state 不导出、不被外来记录覆盖；用真实同步事件导出/接收路径证明无新增事件或偏好传播，但接收收藏能重算建议。若当前同步实现尚在独立分支，保留该集成验收为阻塞项，不能为了本需求合并未授权的同步 roadmap。

**验证与验收**：重建偏好 store 后折叠/忽略仍在；另一设备独立；安装不修改漫画或 source ID；WebView 无源实例可导航但不伪造插件 headers；来源选择不绕过现有信任校验。测试必须执行真实 ScreenModel/action/导航依赖，不以截图代替行为测试。

**预期修改范围**：两端插件 Screen/ScreenModel、domain presentation 和本地 preferences、i18n、必要 backup 恢复过滤与契约测试。基础安装器与同步协议不重写。预计 1–1.5 工程日。

### EIS-03：同包请求仲裁与 Desktop 全部安装

**用户入口**：Desktop 建议安装 → 全部安装/安装匹配项 → 清单确认 → 批次进度。前置 EIS-02 已验收。

**实施步骤**：
1. 先写共享批次红测试：清单快照、重复点击、已排队跳过、同包不同来源冲突、清单后目录/版本变化、部分失败和停止后续。
2. 在既有 install coordinator/manager 边界增加原子请求仲裁；对普通可用安装、更新与更新全部做必要接线，使建议批次不取消既有事务，也不允许其他页面覆盖它。保留显式取消重试。
3. 共享编排器维护批次 ID、事务 ID、选中产物身份与结果；逐项驱动现有 install port，取消/错误清理在各终态释放预留。
4. Desktop 接入真实下载、校验、APK转换/JAR安装及运行时重载；既有签名、来源确认、回滚和代理逻辑不降级。
5. 接通一次确认清单、搜索范围、收起仍可见进度、失败重试与停止按钮；页面离开重进保留实际批次，不把 Composable 生命周期当安装生命周期。
6. 覆盖安装中卸载/其他入口同包请求、来源拒绝、晚到回调、应用级 controller 清理；保存事实结果，不把取消和失败混成成功。

**已冻结的平台边界**：EIS-03 的 Desktop 同步卸载全过程使用同包互斥。Android 本批确保已有安装预留时不派发卸载，且卸载派发与安装预留原子互斥；系统卸载等待确认的异步窗口由 EIS-04 结果协议闭合，不用超时或猜测窗口关闭来解锁。

**验证与验收**：从真实两个 UI action 入口竞争同包，只有一个有效事务且另一请求不取消它；批次失败不回滚成功项；无新增建议自动加入；未提交项停止，正在提交的项依真实结果结束。使用本地 HTTP fixture→production coordinator→临时插件目录→真实 loader 的闭环测试；真实产物来源可验证且不执行未知远端代码。

**独立检查门槛**：主代理或未实施该部分的审查者检查去重原子性、取消/提交边界、来源与签名策略，确认通过后 EIS-04 方可依赖。

**预期修改范围**：共享 install/presentation、两端必须共享的 install action adapter、Desktop UI/runtime 与安装集成测试。不新建下载队列或全局任务系统。预计 1–1.5 工程日。

### EIS-04：Android 批次安装与系统生命周期

**平台生命周期补充**：复用真实 `ExtensionInstallActivity` / Activity 结果桥关联系统卸载请求，覆盖先等待卸载确认后发起同包安装、取消和重建释放；该竞争必须在 EIS-05 前闭合。同步核对安装 Activity 重建不重复发起、配置变化不提前删除 URI、启动异常回传真实终态；不重写通用应用卸载器。

**用户入口**：Android 建议安装 → 全部安装 → 系统/私有/Shizuku 当前安装方式对应流程。前置 EIS-03 高风险接口检查通过。

**实施步骤**：
1. 先写 Android 平台红测试，覆盖 Legacy 直接 Activity 与 PackageInstaller 服务队列、权限缺失、系统取消、用户继续/停止以及回调关联。共享调度不再创建第二套平台队列。
2. 连接现有 ExtensionInstaller、ExtensionInstallActivity/Service 与事务结果；串行呈现系统交互。先校验系统共享/私有已安装包，避免原版已共享安装时重复建议。
3. 权限缺失暂停并使用既有设置入口；返回后重新检查并由用户继续。Shizuku 服务或授权失效明确暂停，不自动换安装器；私有安装沿用本应用文件链路。
4. 对确认前、下载中、系统提交后各阶段分别处理安装器变更和停止；系统提交后只标记停止后续，继续接收当前真实结果。
5. Activity 重建、旋转、返回页面、进后台、进程终止后核对实际包状态；不能自动回放已完成批次，不能重复拉起系统安装页。后台拉起受限时提示回到应用继续，不绕过系统限制。
6. 单项失败/来源拒绝/签名不符、用户取消、权限暂停分开反馈；保持原有回滚和事务清理。最后一项取消直接结束，没有剩余则不显示继续。

**验证与验收**：Legacy 与 PackageInstaller 至少各一条真实平台成功/取消/连续安装证据；私有模式验证安装重载与重启；Shizuku 可用/不可用走真实 adapter，目标环境缺服务时必须记录未验证部分，不能称全模式完成。用真实包管理回调或 instrumentation 执行 Activity 生命周期，JVM fake 不能替代系统确认行为。

**独立检查门槛**：确认权限不绕过、同包不覆盖、跨事务回调不误推进、共享与私有位置不混淆；相关批次 focused、Android instrumentation 和格式通过后提交。

**预期修改范围**：Android extension util/installer、UI/DI、共享状态中必要的平台事件与对应契约。系统 API 差异留在 Android adapter；不修复所有厂商安装器问题。预计 1–2 工程日。

### EIS-05：三端正式交付与关闭

**前置**：EIS-01～04 已提交且无审查阻塞；设备、Mac、SDK、签名身份及构建脚本可用性已核对。此阶段包含必要的发布身份/版本、运行验收和证据，不是纯状态推进提交。

**实施步骤**：
1. 冻结待发布 diff，确认用户无关改动未混入；串行执行一次最终完整测试集合与格式。失败先限定诊断，区分本次回归和已有基线，不能直接跳过。
2. Android 沿用当前 fork release 配置、签名脚本和证书，产出 R8 正式包；不得假定历史设备仍连接，不安装覆盖官方 Mihon 或清除用户数据。安装到明确授权的验收实例，记录包名/版本/证书/哈希。
3. Windows 必须使用 `scripts/build-desktop.sh`；同一 diff 已有完整 Desktop JVM 证据时可用 `build-only`，保留正式 runtime/Test Mode 验收。Mac 在对应主机用同一正式脚本和隔离产物目录，不借用系统 JDK 模拟运行通过。
4. 在真实正式产物中操作：缺失插件建议、已安装共享包排除、折叠/忽略重启、单项/多源网站、批量部分失败/取消、跨入口去重；用 Test Mode 执行真实 UI/runtime wiring，必要时最小扩充既有 action/state，不另建测试服务。
5. Android 与 Desktop 共享 fixture 核对匹配结果与本地状态隔离；Mac 验证窗口交互、外部网站打开、插件目录与安装重载，Windows 验证同项及正式 EXE 身份。
6. 完成维护说明、限制和可执行验收入口；在本文件记录实际命令、结果、平台、产物、提交。如能力已有 manifest 项，补真实证据并校验；不得为方便而把未验收项置 VERIFIED。

**完成门槛**：三端所需证据齐全，正式产物存在，剩余故障均有明确边界且无本需求阻塞。Windows 地址只取构建日志 `Final unpacked EXE:` 的实际绝对路径，核对文件存在；不把 tmp/build 下 EXE 作为交付。Android APK、macOS app/安装产物也给可点击绝对路径及版本。

**预计成本**：0.5–1.5 工程日，设备/工具链等待另计。平台不可用则停在对应验收项，继续其他独立验证；不默认免除 Mac、实机或正式发布门槛。

## 6. 测试矩阵与执行方式

| 验证面 | 最低必须覆盖 | 所属批次 |
|---|---|---|
| 共享匹配 | 64 位 ID、同名不同源、一包多源、互斥候选、忽略身份、库为空、本地源、内容限制 | 01 |
| 安装库存 | 初始化、共享/私有、未信任、已装加载失败、临时文件、元数据未知、卸载更新事件 | 01 |
| 真实数据与接线 | SQL→repository→ScreenModel，两端 DI 解析、订阅动态变化、旧快照不能覆盖 | 01–02 |
| UI/导航 | 顺序/折叠/计数/搜索、忽略撤销、Screen实例化、正确 Navigator、网站多选/无URL、键盘/窄屏 | 02 |
| 本地数据隔离 | store 重建、两设备隔离、真实备份双向过滤、真实同步无新事件且收藏接收重算 | 02 |
| 批次与仲裁 | 快照、同包竞态、来源变更、忽略/收藏变化、部分失败、停止提交边界、锁释放 | 03 |
| HTTP/安装产物 | 成功、空/缺失、403/429/500、畸形响应；摘要/签名错误、真实重载、回滚 | 03–04；复用仍适用的已有测试，不重写 decoder |
| Android 系统 | Legacy/PackageInstaller/私有/Shizuku，权限/取消/继续、后台限制、重建/迟到回调/重启核对 | 04 |
| 发布与运行 | Android release ART/R8，Windows/macOS 正式 runtime、Test Mode、最终路径与身份 | 05 |

行为变化严格先红、再最小绿、再重构复验；UI wiring 不能只有 domain 测试。测试扫描源码或在测试中复制实现逻辑不算证据。已有测试仅在覆盖当前真实路径且 wiring 损坏会失败时复用。

Windows 环境与串行 Gradle 命令模板（实际执行及有效证据见第 9 节）：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'

# 测试类名在实现时以实际新增类替换，不能复制占位符当通过证据。
python scripts/gradle-coordinator.py run --key eis-focused -- gradlew.bat :domain:jvmTest --tests '<实际共享测试类>' :domain:testReleaseUnitTest --tests '<实际共享测试类>' --max-workers=2
python scripts/gradle-coordinator.py run --key eis-platform -- gradlew.bat :app:testReleaseUnitTest --tests '<实际Android测试类>' :app-desktop:jvmTest --tests '<实际Desktop测试类>' --max-workers=2

# EIS-05 唯一完整集合；包含受影响共享模块的两目标，不能只测 app。
python scripts/gradle-coordinator.py run --key eis-final -- gradlew.bat :domain:jvmTest :domain:testReleaseUnitTest :data:jvmTest :data:testReleaseUnitTest :app:testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test spotlessCheck -PincludeIntegrationTests=true --max-workers=2
```

需要访问境外依赖时先设会话 HTTP_PROXY/HTTPS_PROXY，Gradle JVM 按现有规则另传正确引用的代理参数；SDK 先核验实际 android.jar/aapt2/adb 文件。设备 instrumentation 命令依当前 release runner、包身份及连接设备确定，在 EIS-04 开始前写入本节实际证据，不凭历史硬编码安装。

每个批次只执行对应 focused/集成与受影响格式；共享阶段完成时安排相应模块完整测试，最终完整集合可复用同一 diff 未变化的阶段证据。完整 Desktop 测试、finalParityAudit 和发布构建只在 EIS-05 收口执行，不在每个批次重复。协调器超时先查 status，进程仍运行不得再启动；测试失败后的必要定向诊断不等于允许再次全量测试。

## 7. 委派、审查、提交与预算

### 实施组织

- 本轮规划为主代理直接完成，无子代理、无产品测试、无构建；只产出一个 roadmap 并同步已有需求/DEMO状态说明。
- 后续获准实施时，首个 EIS-01 任务簇交主要实施子代理，之后复用该代理依次承担 EIS-02～04 的主要代码与测试；主代理负责接口、独立验收与 EIS-05 集成发布，不重复实现委派代码。
- 默认 1 个实施子代理；最多 2 个子代理的总上限不变。若主代理亲自实现高风险目标，则需启用第二个未参与实现的审查者；不让实施者独立批准自己的高风险部分。
- 独立审查按各批次稳定产物设置检查点，主代理对自己未实施的代码完成一轮审查，不在阶段末再重复审核同一 diff；修复复审最多一轮，聚焦失败项。若实际需要额外完整审查轮次或更多代理，先说明具体失败与新增成本并取得批准，不把计划解释成无限审查授权。
- 默认没有并行写入。实施者编码时，主代理可并行读接口、准备隔离 fixture/验收清单；同 worktree 所有重型 Gradle、集成及构建由一个协调者串行执行。

### 批次交付与恢复

- 每批一个内聚提交，包含测试、production 与必要文档/checkoff；审查修复最多另加一个提交。checkoff 表示提交中的工作已过全部出口，提交后记录 hash，不另建纯状态推进提交。
- EIS-01/03 的接口变化先通知消费者再进入下游；返回 `status/diff/tests/commit/process/next` 结构化回执，报告真实 red/green、剩余进程及日志位置，必要时用 `scripts/agent-handoff.py` 校验。
- 长调用开始前说明命令、预计耗时与可用进程/日志；超时先查同一进程。空闲无回执按项目规则一次追问，不因等待重新启动实现或重复跑测试。
- 证据记录集中写本文件第 9 节，运行日志复用协调器及标准测试报告；不生成逐任务快照、巨型 diff 包或第二份 tracker。
- 超过 8 文件/400 行时说明共享契约、平台 adapter、接线及测试为何构成同一能力，不为行数拆成不可独立交付的任务。

### 成本与追加条件

细化后合计约 **4.5–8 个工程日**，含两端及 Mac 验收、独立检查与一轮限定修复；早期 4–7 日是可行性粗估，新增细项主要为真实系统与发布验收，不是扩大产品范围。实际 agent 墙钟取决于构建、设备和外部依赖，不能把工程日直接承诺为运行时间。

成本主要为 Android 多安装器和发布生命周期验证，其次是两端接线/库存准确性；计算本身不需逐源联网，匹配约 O(S+F)。默认最多 2 子代理、独立审查一轮及限定复审一轮、实现后全量测试一次；阶段必要模块检查不重复完整 Desktop 套件。正式脚本若强制重复已完成测试或当前平台 `build-only` 不支持等价复用，先查脚本与证据，再说明额外成本，不能悄悄删掉脚本验收。

触发追加决策的情形仅包括：必须引入 Desktop 浏览器引擎、必须改同步协议/数据库迁移、安装生命周期需整体重构、需要额外设备/付费资源、突破已声明审查/全量次数、无法在隔离实例完成验收。先继续不依赖该项的必要诊断，暂停受影响实施；说明证据、备选方案、时间与范围，不能仅以“预算用完”解释失败。

## 8. 风险、失败处理与回退

| 风险 | 预防与处理 | 不允许的捷径 |
|---|---|---|
| 源 ID 在目录中不存在 | 显示未匹配及仓库/迁移入口，说明匹配覆盖率边界 | 依据名称猜源或自动改漫画 source ID |
| 已装故障插件误推荐 | inventory 与 runtime 分离，未知保留解释；补故障产物测试 | 仅比较成功加载 source 列表 |
| 多仓库候选与签名变化 | 继承来源规则，显示仓库/版本，提交前复核 | 自动选最高版本、取消签名检查 |
| 同包跨入口竞争 | 在 coordinator/manager 原子预留，关联事务和回调 | 在 UI 单靠 disabled 按钮防重 |
| 系统取消/权限导致连续弹窗 | 暂停剩余，前台明确继续；所有安装器共用批次规则 | forEach 拉起多个 Activity 或绕过权限 |
| 本地设置被同步或备份带走 | app-state 存储、真实导出/恢复及同步测试 | 仅凭 key 名声明不会同步 |
| 网页无法登录/反爬 | 保留既有容器与错误反馈，说明未安装源请求头限制 | 把能打开网站等同于源可用 |
| 设备或 macOS 环境不可用 | 记录缺口，完成其他独立测试，最终批次保持未勾选 | 以 JVM/HTML 模拟取代真实平台通过 |
| 工作树存在其他任务变更 | 先核对归属；必要时隔离 worktree，只提交本任务 | 回滚用户改动或夹带生产修复 |

安装失败沿用现有事务回滚，不删除成功插件或漫画；停止仅影响本次未完成工作。若需撤回本功能，回退相关提交并保留本地数据，不自动卸载插件、清空忽略或改动用户书架。

## 9. 完成证据记录与最终验收清单

### EIS-01 完成记录

- 工作树：`codex/extension-install-suggestions`，隔离目录 `D:/Shell/Github/mihon-eis`，基线 `3503af8b85`。只处理本专项；主工作树其他变更未纳入。
- 环境预检（2026-09-16，只读）：Android SDK 的 `android.jar`、`aapt2.exe`、`adb.exe` 存在；专用 AVD `mihon-aex-api36`、`mihon-aex-api26` 在线；`mbp-lan` 的 Darwin/JDK 21 与约 30 GiB 可用空间已核对；发布签名文件存在。未进行正式安装或构建；Shizuku 可用场景尚未验证。
- 红测试：所有命令由 `scripts/gradle-coordinator.py` 串行管理，记录位于工作树 `.gradle-coordinator/<key>.log`。`eis01-red-proxy` 的共享计算 6 项中 5 项因缺匹配/诊断/来源选择结果而失败；`eis01-observer-red` 在共享计算 6 项已绿时，观察器未订阅导致建议断言失败；`eis01-inventory-red` 的两端真实库存测试因包/初始化/未知文件缺失而失败。`eis01-candidate-red` 又确认了“已装候选被忽略后误推荐替代”“同身份版本倒序”“新版本删除旧源描述”三项正确失败。
- 接线红测试：`eis01-desktop-wiring-red` 在修复测试关闭方法后，从真实 SQLite 收藏查询到 ScreenModel 状态的等待超时；Android 同一契约也在订阅缺失时超时；两端 DI 因缺 `ObserveExtensionSuggestions` 注册失败。`eis01-manager-red` 覆盖完整目录候选保留与旧库存扫描晚到；`eis01-inventory-detail-red` 覆盖系统/私有位置、来源元数据、失败加载广播仍通知库存。
- 仓库时序红：`eis01-refresh-red`（Desktop）和 `eis01-android-refresh-red`（Android）均确认删除仓库后，旧在途请求短暂发出旧建议的断言失败。两端现按当前仓库身份过滤发布；Android 订阅已有 `GetExtensionRepo.subscribeAll()` 并复用 manager 串行刷新，覆盖设置、恢复及同步配置变化，不新增目录请求实现。
- 非行为红/验证诊断：最初一次 Gradle 直连依赖解析卡在 HTTP HEAD/TLS 连接，协调器已终止该进程，唯一一次带会话 JVM 代理的重试成功进入测试；不把网络等待计为功能红。测试中曾发生关闭方法误引用、MockK 默认 Flow getter 类型擦除及临时 Source 缺 `lang/name`，均属于 fixture 故障，不能代替红证据。后一异常还被后续 presentation `runTest` 报为 `UncaughtExceptionsBeforeTest`；需要修复并重跑受影响测试，不据单项滚动日志宣称整轮通过。
- 维护边界：`domain/.../extension/suggestion` 是两端建议语义唯一实现；源 ID 始终为 Long。先在同仓库签名身份内采用更新策略，再建源索引，不能从旧版描述复活已删除源，也不跨仓库自动选最高版本。存在性先于忽略过滤，已装/未信任/加载失败均不建议替代。
- 库存是只读的最终产物/包清单，记录已知位置、已有来源元数据及加载结果；来源未知保持空值，不从可用目录反推。Android 复用系统/私有枚举及已有 trust metadata 读取，接收失败加载的包变化通知；Desktop 只扫描顶层最终 JAR 和相应 metadata，临时事务目录及 sidecar 单独存在不计已安装。未知旧手工 JAR、无法解析的 private `.ext`、目录不可读会保守阻止确认缺失，不自动删除或修复。
- 两端 ScreenModel 消费共享 Flow：复用真实 SourceRepository 收藏计数与 SourceManager 注册状态，初始库存未齐保持 loading；磁盘扫描仅在库存生命周期变化执行。`data/src/testFixtures/kotlin/mihon/data/extension/ExtensionSuggestionSqlContract.kt` 仅为两端复用的测试契约，执行真实 SQLite、各平台 repository 和 ScreenModel/DI，不在测试内复制建议算法。
- 内聚性：本批超过 8 个文件/400 行，是同一内部能力的共享计算、平台库存适配、目录/包事件、两端 DI/状态接线及同契约测试；不能按文件拆为独立交付。安装器修改仅抽取既有 artifact/信任元数据映射供库存复用，保留原安全检查、提交和回滚流程；无数据库 schema、安装队列、同步协议或新增 UI。
- Green/重构：`eis01-final-green` 于 2026-09-17 终态 `PASSED / exit 0`，Gradle `BUILD SUCCESSFUL in 3m 8s`。原始 XML 核对：domain JVM 11、domain Android 11、Desktop 20、Android app 73，合计 115 项，失败/错误/跳过均为 0。Android app 包含 manager 15、库存 2、SQL/DI 2、presentation 9、安全回滚 31、install wiring 14；Desktop 包含真实 DI 1、库存 2、晚到仓库 1、真实 SQL 1、既有 shared wiring 15。真实 SQL 契约已通过收藏添加、迁移、移除、运行时注册和卸载反应；前次 fixture 故障涉及的 observer 与 presentation 整类已重跑通过。
- 重现参数：协调器 key `eis01-final-green` 的 JSON 保存完整命令；测试任务为 `:domain:jvmTest` 与 `:domain:testReleaseUnitTest --tests mihon.domain.extension.*Suggestion*`、`:app:testReleaseUnitTest`（上述 6 类）及 `:app-desktop:jvmTest`（上述 5 类），均为本批 focused，不是全量。`--max-workers=2`、Gradle 堆 2 GiB；HTTP/HTTPS JVM 代理仅当前调用。
- 格式：`eis01-format-clean` 已应用本任务 Kotlin 文件格式，最终同一 green 调用中 `:domain:spotlessCheck :app:spotlessCheck :data:spotlessCheck` 通过，`spotlessIdeHook` 限定本任务变更文件。此前格式调用的长行错误已修正；Desktop 没有 Spotless 插件/task，未为此引入新插件，完成编译、focused 测试及 `git diff --check`。标准编译仍有既有弃用警告和共享 SQL 测试的协程实验 API opt-in 警告，无构建错误。
- 独立检查与提交：2026-09-17 主代理独立验收通过，已核对最终源码、Android AppModule 仓库 Flow、双端旧快照门槛、库存来源及原 trust 读取逻辑、协调器终态与四组原始 XML。此前发现项在限定复验中全部关闭，无新增阻塞；本批测试、实现、文档与 EIS-01 勾选合并为同一功能提交，hash 由完成回执记录，不另建状态提交。协调器全部已结束，无本批残留 Gradle 任务。此批未生成发布产物，不能替代 EIS-02～05 的用户功能及正式平台验收。

### EIS-02 实施记录（已完成）

- 前置 EIS-01 已独立验收并提交：`c1d733810e79acf8a9c4e06f9ca8abca93a2d550`；仍在同一隔离工作树实施，不改主工作树其他任务。
- 共享面板红绿：`eis02-panel-red` 的搜索/忽略/进度/来源选择占位实现产生正确失败，`eis02-panel-green` 三项通过；独立审查发现“裁剪显示源不等于完整包不冲突”，`eis02-selection-red` 对完整 artifact 的冲突选择产生正确失败，修复后 `eis02-ui-red` 的共享三项通过。选择新候选会清除冲突候选的旧选择并要求重选；运行中按完整 artifact 源集防冲突，网站仅列关联缺失源。
- 两端接线：`eis02-panel-wiring-red` 的真实 SQLite→ScreenModel→panel 均因缺订阅超时；Android 在 `eis02-ui-red`、Desktop 在 `eis02-ui-red-fixed` 通过。新增真实 AppModule→ExtensionManager→GetExtensionRepo 无页面配置变更测试在 `eis02-ui-red-fixed` 通过，未 mock manager 或扫描源码。
- UI/action 红：Desktop `eis02-ui-red-fixed` 在 panel 已有一条建议后等不到真实页面文字；Android `eis02-android-root-red` 真实 ExtensionScreen 报建议不可见。`eis02-action-red` 的 Desktop installSuggestion 返回 null、Android 等不到 Downloading，构成安装接线红；不把未到达的重复点击后半段当作 busy guard 红。独立 busy guard 红：`eis02-duplicate-red` 的 Desktop 首次 Job 被取消、第二次为另一 Job；`eis02-navigation-red` 修正真实调度器等待后 Android 三个运行变体均 expected 1 / actual 2。随后恢复防重复实现；首次虚拟时钟等待超时不算 busy guard 红。
- fixture 诊断：Desktop 新 DI 曾误引用另一函数的局部变量，修正为真实 PreferenceStore 解析；AppModule fixture 补真实 PreferenceModule 后避免 SecurityPreferences 缺失中断第二次刷新。Android Compose host 先遇到 ActivityScenario 未注册 Activity，随后匿名 Voyager Screen 无 key；改用现有 Robolectric ActivityController/Compose rule 与明确 Screen key 后才取得真实行为红。这些故障不算功能红。一次调用 https.proxyHost 误写 `127.0.1`，无网络阻塞，后续已恢复 `127.0.0.1`。
- 本地性：`eis02-android-ui-red` 中两端真实 backup creator/restorer 新契约通过，覆盖重建 store、导出过滤、外来 app-state 无法覆盖及第二设备默认值。`eis02-android-root-red` 中两端共享 SQL 同步契约通过：真实 journal→outbox batch→inbox ingest/projector→SQL→panel；折叠/忽略不新增事件、不传播偏好，接收收藏会重算建议。接收端有已有未收藏元数据、缺运行时插件，遵守现有同步边界，不扩张缺源重建语义。
- 忽略只按规范化仓库/签名/包身份持久化并反馈 observer。若目录彻底删除映射，已无法从源反推历史包，此时未匹配解释仍合理；不新增历史源快照或永久忽略源。撤销只在当前面板会话有效，离开清除提示而不清除持久化忽略。
- 来源同名辨识与主入口：`eis02-navigation-red` 候选缺 URL/签名身份产生正确红；补显示后 `eis02-navigation-red-fixed` 此项通过。真实 Android extensionsTab 在临时撤除导航 callback 时，嵌套 Navigator 目标类型断言失败；恢复 callback 后再验证网站与迁移。Desktop 实际 BrowseSourceListScreen 的新增迁移消费方也纳入测试，未仅验证独立 ExtensionListScreen。
- 诊断红：`eis02-diagnosis-red` 的 Desktop assertAll 同时捕获迁移缺线、检查后折叠及说明不可见；Android 真实 Tab 与 ScreenModel 的库存重新检查缺调用、真实 manager inventoryProvider 扫描 expected 2 / actual 1 均正确失败。最小修复保留说明、显示中性检查完成提示；Android 只重新读取库存，不运行插件代码，Desktop 复用既有 reloadInstalled 与错误反馈。未知/加载失败结果继续由真实状态展示，不宣称检查等于修复。
- 其余 fixture 更正：主入口测试曾把现有英文 Migrate 硬编码为 Migration、使用不实现 StringSet 的 InMemoryPreferenceStore；Android 重建 observer fixture 曾触发 MockK 默认 Flow getter 类型擦除。修正为正确现有文案、隔离的真实 DesktopPreferenceStore、明确的 SourceManager Flow 属性；这些失败均不计行为红。
- 本批超过 8 文件是一个不可分割的双端单项能力：共享状态与设备偏好、真实页面/导航/DI、现有安装与库存 adapter、备份/同步契约及本地化共同交付。无新安装队列、仓库协议、同步事件类型或数据库迁移；主要风险为异步状态及平台导航，由真实接线测试覆盖。
- 共享计算、面板与偏好为双端唯一语义；Compose 适配既有平台页面、导航和本地化。单项通过既有安装链路，当前批次只处理同 ScreenModel 重复点击；跨入口事务仲裁与批次仍属 EIS-03/04。
- 最终 focused：`eis02-final-green` 首轮终态 FAILED（3m19s），不得称为整轮全绿；失败限于四处测试兼容问题：MockK 的 chained verify 同时禁止了合法 getter、只读库存测试误套用通知取消尾断言、Android 显式取消应贡献一次 InstallFinished、Desktop 旧测试仍假定重复点击取消重启。另一次 Main dispatcher 污染来自失败路径清理。修正测试后 `eis02-focused-repair` 终态 PASSED / exit 0（1m09s），重跑四个完整类：Android UI 2、manager 16、presentation 11、Desktop shared wiring 15，共 44 项。Android 仍验证旧 Error 不发出、显式取消仅一次 Finished、重试保持 Downloading，并等 owner Job 关闭再 resetMain；Desktop 在清理期间实际点击重试并断言 attempts 仍为 1，清理结束后才允许新安装。
- 原始 XML 联合核对：首轮未受影响的有效结果加修复轮替换失败类，domain JVM 16、domain Android 16、Android app 51、Desktop 24，合计 107 项，failure/error/skipped 均为 0。保留于 `.gradle-coordinator/eis02-passed-xml/`；首轮原始 suite 统计为 `eis02-final-green-results.json`。覆盖完整身份编码/解码、重建 preferences/panel/observer、版本更新仍忽略、普通列表不受忽略影响、真实多源网站/无URL禁用、嵌套导航、AppModule 仓库变化、单项重复与重试、备份及真实同步 SQL 链路。
- 格式：domain/data/i18n 受影响 Spotless 在 `eis02-final-green` 通过；Android 新 UI 与最后测试修正在 `eis02-format-clean`、`eis02-fixture-format` Apply 后由 `eis02-focused-repair` scoped Check 确认；`git diff --check` 通过。Desktop 沿用现有无 Spotless 插件边界，未增设独立格式体系。
- 窄屏离屏 PNG：`.gradle-coordinator/eis02-suggestions-narrow.png`（360×800），由真实 ExtensionListContent 的 ImageComposeScene 测试生成。长插件名、多源说明、安装/网站/忽略按钮不裁切；主代理已检查布局，最新实现同测试复跑通过。未读取系统桌面像素。
- 本批未运行全量或发布构建。单项共享忙碌范围为现有 ScreenModel；全入口事务仲裁和批量仍由 EIS-03/04 实施。主代理于 2026-09-17 独立验收通过：核实两轮原始 XML 联合 19 suites / 107 项、最终修复轮 PASSED exit 0、真实页面/导航/DI/数据隔离、窄屏 PNG 与 diff-check。此前审查项均关闭，本批 checkoff 与实现、测试、文档放入同一功能提交；hash 由完成回执记录，不另建纯状态提交。

最终验收路径（全部满足后方可关闭 EIS-05）：

| 路径 | 预期结果 |
|---|---|
| 收藏含缺失源 → 浏览 → 插件 | 已安装前显示按包去重的建议与正确收藏数；已有故障/未信任包不重复建议 |
| 折叠/忽略 → 重启 → 对端同步 | 本机记忆且另一设备不改变；即时撤销有效；普通可用列表仍能安装 |
| 单源/多源/缺网址 → 网站按钮 | 正确网站/先选择/禁用解释；Android WebView 与 Desktop 外部浏览器行为准确 |
| 搜索后全部安装 → 核对清单 | 只处理当前匹配项；新到建议不加入；来源未定不提交 |
| 建议与普通列表同时安装同包 | 同一有效事务，不能互相取消或重复弹窗 |
| Android 系统确认 → 取消 → 继续/停止 | 剩余暂停；用户选择后推进；最后一项取消直接结束 |
| 权限/服务失效或安装器变更 | 清晰暂停并提供下一步；不静默换安装方式 |
| 停止后续 → 当前已系统提交 | 等真实结果，后续不再提交；已成功插件保留 |
| 部分仓库失败/安装失败 → 重试 | 结果不完整有提示；已有成功结果保留，失败项可重试 |
| 正式 Android/Windows/macOS 运行 | 同一产品行为通过真实链路；发布身份、产物位置与证据可追溯 |

最终面向用户按项目要求报告功能、BUG修复与可执行验收项，附正式产物绝对链接、实际测试结果、已知限制及提交 hash；不能只说“全部完成”或让用户从过程日志拼出交付状态。


### EIS-03 已完成（历史红绿过程及最终验收）

- 基线为 EIS-02 `6b34ea3a606a233ddd715ef65485cae401a61bef`，同一实施代理负责修改与串行 Gradle，主代理独立检查；不增加发布或全量测试。
- 安装预留位于应用级入口、底层既有 coordinator 之前；完整产物与 lease 对象归属同时校验。停止与提交门共用原子边界，旧 lease 不能释放重试事务；实际安装清理完成前保留同包占用。
- `eis03-arbiter-red` 2 项有效红；`eis03-batch-red` 第一项受 arbiter 前置未实现阻挡，不作批次行为红。`eis03-batch-red-ready` 在 arbiter 2 项通过后确认 batch 3 项有效红。
- `eis03-guard-red` 确认真实 coordinator 提交前资格复核、单项已存在/忽略/收藏移除跳过的 2 项有效红。`eis03-shared-green` PASSED/exit 0，原始 XML 为 JVM 3+23+4、Android 3+4，共 37 项，无失败/错误/跳过。Kotlin 增量编译曾自动回退非增量，最终任务成功。
- `eis03-desktop-arbitration-red` 确认真正 API 两入口会同时进入同包安装；`eis03-resume-red` 确认暂停项被错误计为完成。修复后 `eis03-resume-api-green` PASSED/exit 0（共享恢复 5 项、Desktop API 2 项）。暂停恢复保留原批次和已完成事实，只重新确认固定剩余包清单；未完成 Desktop UI/真实安装闭环及 Android 共用入口，不能作为批次完成证据。

- EIS-03 后续有效红：`eis03-boundary-red` 原始 XML 确认 metadata 未受预留保护、信任确认同步异常泄露 lease、恢复产物与已成功源冲突共 3 项；仅为取红临时恢复的两个 API 旧边界已还原修复版。`eis03-android-arbitration-red` 确认真实 manager 安装/更新造成两次低层请求。`eis03-platform-green` 整轮 FAILED（Desktop 测试 fixture 单行补桩缺换行导致编译错误）；其有效 XML 为 domain 4+23+7、Android manager 1，通过不代表整轮通过。Desktop 新依赖的测试 fixture 仅增加真实 arbiter，未用宽松 mock 掩盖状态行为。

- 2026-09-17 交接：实施子代理因平台用量限制进入 errored，主代理核对状态后接管已有 diff；没有重启仍在运行的 Gradle。遗留 `eis03-ui-boundary-red` 随后正常结束为 FAILED / exit 1（2m16s）：共享停止后仍可恢复、失败项重试未实现；Android 真 manager→ScreenModel 重复请求的原始失败含未捕获 `ExtensionInstallBusy`；Desktop 离屏测试尚找不到批量确认入口。这些属于待修行为红，不能作为完成证据。
- 接管后的未验证实现：共享重试只重交明确确认的失败包并保留成功记录；停止后保留当前项真实中断结果但不再提供继续清单；Android 在同步 Busy 时释放页面自身请求标记，不释放另一入口的安装 lease。Desktop 增加固定清单确认入口与独立于折叠区的批次计数/停止反馈，尚缺完整逐项结果、暂停/重试 UI 及真实 HTTP→loader 闭环收口。
- 验证限制：启动 `eis03-takeover-green` focused Gradle 命令被执行策略拒绝（仅返回 `blocked by policy`，具体策略来源未确认），命令没有启动；未换写命令规避拒绝。新增 base/简中/繁中资源 XML 可解析且键唯一，`git diff --check` 通过，但这些不替代编译与行为绿测试。EIS-03 保持未勾选、未提交；主代理接管的变更仍需独立审查。
- 后续只读核验再次确认 `eis03-ui-boundary-red` 为终态 FAILED、`eis03-takeover-green` 为 NOT_STARTED；没有后台验证仍在进行。已补写 Desktop 真实 ScreenModel/API + 离屏页面的逐项结果与失败项确认重试测试，尚未运行，故对应 UI 实现未推进为完成；focused 验证重试已向用户提出确认，确认前不重新执行被拒命令。当前 diff-check 通过。
- 用户随后明确批准重试。主代理先核对 coordinator 状态，再重新执行完全相同的 `eis03-takeover-green` 启动命令；执行环境仍在 CreateProcess 前拒绝，仍仅返回 `blocked by policy`。因此授权已具备，但环境策略阻塞未解除；测试未启动，不要求用户重复批准，不据此声称验证通过。
- 拒绝来源排查：已读取两处用户级 default.rules，未发现禁止 Gradle 的显式条目；对精简命令的本地 execpolicy check 返回 matchedRules 为空，此结果不能证明完整 PowerShell 调用在所有有效策略层下获准。当前会话为 danger-full-access / approval_policy=never；因此先前直接称作 Auto-review 拒绝缺乏证据，已纠正。没有改动安全配置。最新 coordinator 状态仍为 NOT_STARTED，待执行环境解除阻塞后再完成红绿验证与独立审查。
- 卸载边界：EIS-03 Desktop 同步卸载全过程受同包仲裁保护。Android 本批仅保证已有安装 lease 时不派发卸载、卸载派发与安装预留互斥；系统卸载确认窗口的异步结果关联及取消释放必须在 EIS-04 生命周期实现中闭合，禁止用猜测超时解锁，也不能作为最终未解决限制留到交付。
- 后续根因（用户提供）：Codex Windows `0.154.0-alpha.6.2` 将整段 PowerShell 参数中同时出现的 `start` 与 HTTP URL 误判为危险启动操作，叠加 `approval_policy=never` 直接拒绝。用户用纯文本输出的三组对照复现；具体结果、归因边界与恢复条件已归档到 [实施交接](../2026-09-17-extension-install-suggestions-handoff.md#后续根因定位用户提供)。本轮未独立复现或读取用户所述源码，未修改安全配置，功能验证尚未恢复；不将根因定位等同于 EIS-03 验收完成。
- focused 验证随后恢复：使用既有协调器 `foreground` 模式及 Gradle `--offline`，不传代理参数，限制为缓存依赖与前台受管理进程；`eis03-offline-green` PASSED / exit 0，1m22s。原始 XML 核对：共享批次 9 项、Android 真实 manager→ScreenModel 同包竞争 1 项，共 10 项，failure/error/skipped 均为 0，验证了接管后的停止/失败重试和 Busy 清理修复。没有修改 Codex 安全配置。此结果不覆盖 Desktop UI、完整安装闭环或独立审查，EIS-03 仍未勾选。
- 原实施子代理恢复后继续承担 EIS-03 主实现及唯一 Gradle 执行者。主代理独立检查首 Pending 取消修复：等待事件移入持有安装预留的 Flow 的 `try/finally`，重复订阅检查保持在其外，避免第二个订阅释放第一个事务。`eis03-pending-green` 协调器终态 PASSED / exit 0（1m01s）；子代理报告普通安装、信任确认两条真实 API→presentation 用例均通过，批次最终回归仍需覆盖既有事件序列。
- 主代理已读取保留的 `.gradle-coordinator/eis03-results-green-resume-red.xml`：结果/失败项确认重试的完整界面用例通过；暂停用例准确失败于缺少 `Review remaining`，批次事实为 0/2、原清单仍保留。此轮整体 FAILED，不能作为暂停恢复完成证据；后续补恢复确认、目录变化及真实 HTTP→loader 闭环。尚未完成本批验收与提交，保持未勾选。
- Desktop 后续证据：主代理核对 `eis03-ui-green-api.xml`（22 项）与 `eis03-ui-green-rendered.xml`（6 项），失败/错误/跳过均为 0。`eis03-reconfirm-red-fixed` 整轮 FAILED，但原始 XML 中真实 Browse 批量入口→本地 HTTP 目录解析/下载→默认签名校验→production manager/coordinator→真实 loader 用例通过；断言受控 JAR 的 SHA-256、安装字节、来源元数据、加载出的源、请求数及预留释放。另一用例准确显示目录已更新到 1.6.2 而恢复确认框仍为 1.6.1，作为重新确认修复的有效红，不混作整轮通过。
- Android 跨入口进度：`eis03-android-progress-green` 整轮 FAILED；主代理核对原始 XML，presentation wiring 12 项通过，manager 19 项中的 3 个失败均来自旧 `fixed main routes package actions...` 测试变体。其 installer fixture 未调用新清理完成回调，前次请求仍持有预留；应补真实完成语义的 fixture，再复验整类，不得为通过旧断言而提前释放 production 事务。阶段验收仍未完成。

- EIS-03 维护边界：应用入口统一先获取同包 lease，完整产物、owner 对象与事务 ID 一起校验；等待来源确认也持有预留。Busy 不创建等待安装请求，不取消当前 owner；底层既有 coordinator 的合并/回滚能力继续保留。Desktop 产品入口统一经过 API，内部发布验收可直接调用 manager。首 Queued 事件在所有权 Flow 的 `try/finally` 内发出，真实清理完成才释放；Android 普通页面与建议区共用 manager 的预留/真实进度，页面自己的重复请求不得覆盖它。
- EIS-03 UI/恢复边界：应用级批次只保留内存事务，页面退出/重进不重放或取消，进程重启不自动续装。确认、恢复、失败重试均固定包清单；同来源身份内更新版本，仓库/指纹变更必须显式选择替代来源并再次确认。完整产物的源冲突（含本批已成功产物）阻止确认并给出解释；暂停/跳过不冒充安装成功，批次结果独立于建议行与折叠状态。停止后，已进入提交的当前项继续接收真实结果，仅停止后续项。目录资格失效呈现重新确认，不向用户泄露内部枚举错误。
- 本批跨越共享仲裁/批次、双端安装入口与进度、Desktop 页面/API/真实安装器以及关联测试 fixture，超过 8 文件/400 行属于同一个不能拆开验收的安装事务协议。风险集中在取消/提交竞态、来源信任、清理完成与页面寿命；以真实双线程、共享契约、双端入口、受控签名本地 HTTP 闭环和受影响整类回归覆盖，未引入新的下载器、签名策略或持久队列。

- EIS-03 限定回归进一步关闭两个边界：信任确认展示与提交都使用 `claimed.artifact` 的同一个冻结实例；Desktop 元数据读取与删除的真实 Windows 句柄竞争可导致 JAR 已删但 sidecar 遗留。`eis03-metadata-red-fixed` 在 production 读/删边界用真实句柄与屏障复现 `done=true, sidecarExists=true`，不可删除 sidecar 也必须显式失败；`eis03-metadata-manager-red-fixed` 验证真实 manager 不得返回成功。修复使用仅覆盖元数据的读写锁，包含普通读写删、commit 替换、rollback 恢复与恢复快照读取，不锁整个库存投影、不嵌套不可重入生命周期门。删除失败转为既有卸载 Boolean false 并发布实际库存，页面继续使用原失败 Snackbar。`eis03-metadata-write-red` 的真实 commit/rollback 两项进一步确认直接 fileSystem 替换也必须加入该锁；线程屏障只控制时序，不替代 production I/O。首次两个测试夹具编译失败已修正，不算行为红。

- EIS-03 最终验收（2026-09-17）：`eis03-repair-final` PASSED / exit 0，33s，四个直接受影响整类 77 项通过。首轮 `eis03-focused-final` 留存且未受后续修改影响的 XML，结合最终四类替换后的联合证据为 27 suites / 294 项（domain JVM 36、domain Android 13、app 58、Desktop 187），failure/error/skipped 均为 0；不是将失败轮整轮计为通过。原始证据保留 `.gradle-coordinator/eis03-final-*-TEST-*.xml` 和 `eis03-repair-final-TEST-*.xml`。受影响格式与最终 `git diff --check` 通过。主代理独立核对实际源码、原始 XML、协调器终态及真实签名 HTTP→coordinator→目录→loader 闭环，所有本批审查项关闭；接管代码也已由原实施代理独立核查。实现、测试、文档与 checkoff 纳入同一 EIS-03 功能提交，hash 随回执提供，不另建状态提交。
- 下一批为 EIS-04：Android 系统批次、取消/权限/重建/晚回调，以及系统卸载等待窗口的异步预留必须闭合；本批未执行全量或正式发布构建，不代表 EIS-04/05 完成。

### EIS-04 已完成（实现、独立审查、验证与本批功能提交）

**范围与协作**：沿用同一实施代理/唯一 Gradle 与 ADB 协调者、同批独立审查；主代理接管纯 Compose 组件、PackageInstaller 前台切片及 Shizuku 协议，实施代理独立检查后接回，未增加代理或另建报告。变更跨共享仲裁、Android 应用批次/DI/UI、平台 Service/Activity/AIDL 与真实测试，超过估算文件数仍属一个安装生命周期闭环；风险集中在终态归属、清理时序与进程恢复，未改同步协议或正式发布身份。

**实现及维护边界**：
- Android 既有插件页提供固定清单确认、逐项结果、暂停/明确继续、失败重试及停止；成功建议消失后结果仍可见。应用级事务不依赖页面，进程重启不回放。替代候选在完整身份选最新版本之后遵循 NSFW，不回退旧 SFW；Desktop 同一候选过滤缺口以一行修复纳入一致性收口。
- reserved 入口保留完整 catalog artifact 作资格/来源校验，仅实际 Android 下载选择 apkUrl；提交前再次检查目录、收藏、忽略、库存及安装器。批次结果先冻结，再等待真实 onFinished 与本次库存发布后释放同包 owner，普通安装释放语义保留。
- 系统结果的 step 与暂停原因由同一 deferred 原子确定；非终态提示不得完成事务。可靠中断门绑定原 owner 与用户确认 generation：已提交当前项的真实成功保留，剩余项须重确认，迟到旧通知不能污染新确认。Service 不把新安装器请求交给旧实例，不自动更换安装器；Shizuku 缺包偏好不再自动退回默认，MIUI 原策略保留。
- PackageInstaller 后台、缺少确认 payload 或 launch 异常经 APP_FOREGROUND 暂停，但 abandon 请求不等于完成；保留 sessionId＋UUID 至真实 onFinished，显式取消同样等清理，实际成功不被覆盖。abandon 异常且无终态时不猜测结果、不释放 owner。
- Shizuku 协议 version 3：prepare 固定 sessionId/UUID，commit 最多一次，唯一 PendingIntent identity 与 receiver 双 ID 校验；断连/授权失效后只查询/收敛原 session，不重放。查询不赋予 commit 权限；准备失败清理 session/FD 并保留原 cause。session 缺失只有原活跃事务的完整 APK 字段、签名集合、摘要及相对基线变化均核实后才认成功，未知保持等待。旧 install RPC 已移除。
- 卸载及回滚复用真实 Activity 结果桥：普通卸载持 removal lease，回滚持原 install owner＋独立 UUID；RESULT_OK 后仍确认系统包不存在，才删除信任。配置重建不重发、不提前删除 URI；旧结果不能解除新 owner。旧实现实际是 PackageInstaller.uninstall＋广播超时，现不以超时/广播猜测确认结束。
- 恢复原安装窗口只恢复 UUID/包名互斥，结果后等库存发布再释放，不恢复批次或安装请求。PackageInstaller 进程退出的原 session 确认仍存活，新增启动接管：仅自身初始 mySessions，先注册 main Handler callback 再读取权威快照并完成回查；同包多个 session 共用 lease 至全部终态，未知包名使库存保持不确定。构造无副作用，Manager 字段赋值后同步 start，再初始化库存；动态新会话继续由原 adapter 管理，不 commit/abandon 被接管会话。

**有效红绿与集成证据**（原始 XML 保存在 `.gradle-coordinator/<key>-xml/`，失败轮只引用明确通过的 case）：
- Activity 重发、配置变化删 URI、无关 UUID 结束窗口以及 removal 占位均取得准确红；`eis04-result-cluster-green-fixed` PASSED，Activity 8＋Manager 19＋Session 32＋Shizuku 2 共 61 项全零。UI `eis04-reserved-ui-red` 四场景缺失红→`eis04-reserved-ui-green` PASSED；实际 Screen/Tab、model、SQL/DI 后续纳入 wiring。
- 前台限制 `eis04-di-foreground-red` 有效红，`eis04-screen-foreground-red` 补缺 payload/取消清理红；`eis04-platform-green-screen-red` 中 Foreground 6＋Session 32 全绿。旧两分钟超时错误收敛已取红并移除，`eis04-pending-system-green-fixed` PASSED，Session 35 全绿；显式取消仍等待真实清理。
- `eis04-observed-wiring-green-fixed` PASSED，Manager 25＋Shizuku 22＋Wiring 15＋Batch 8＋Session 定向 1 共 71 项全零；`eis04-generation-nsfw-red` 旧 generation 污染有效红→`eis04-generation-nsfw-green` PASSED，Android Batch 8＋Wiring 16＋Desktop Action 3 共 27 项全零。Shizuku 22 包括协议、过滤器、权限/服务及五项独立安全审查修复。
- 回滚真实窗口/gateway/manager 契约红后，`eis04-rollback-window-repair` PASSED（32s），SecurityRollback 34＋Manager 26 全零；联合当时未变 Session 37＋Activity 9 为 106 项。严格 mock 缺新方法与原 expectedAbsent reload 次数修正仅为夹具适配。
- 首次 PRIVATE 设备准确暴露第一项成功后库存尚刷新导致下一项 INVENTORY_UNKNOWN；`eis04-inventory-barrier-red` 有界扫描屏障重现，修复后 `eis04-inventory-barrier-green` PASSED，Manager 27 全零，设备也已转绿。缺 Shizuku 包自动退回安装器的真实失败经偏好红绿修复；`eis04-installer-preference-green-fixed` PASSED（33s），真实 AndroidPreferenceStore 重建 2 项全零。
- 恢复窗口 `eis04-window-owner-red` 为四个独立行为红；真实 Legacy startActivity 包名 extra 经 `eis04-legacy-intent-red-fixed` 准确失败后补齐。`eis04-window-owner-green` PASSED（1m02），Session 38＋Manager 29＋Activity 10 共 77 项全零，另 Arbiter 6 全绿。
- 原 PI session 设备 red：`eis04-api36-pi-session-red.log` 初始化完成但准确包未 busy；adapter 初始三个契约红→绿。审查补同包双 session/显式 Handler/未知包名红，接线再取得 Manager 已 ready 但未知库存未标记的准确红；`eis04-session-recovery-final-green` PASSED，恢复 7＋Manager 29 全零。中间 runCurrent 导入、Looper/Handler/Companion mock、Uri、旧 InMemory store Local-copy 等错误均为夹具失败，不计行为红。

**实际设备证据**：仅专用 API36 `emulator-5580`、API26 `emulator-5582`，所有 ADB 显式序列号，不操作物理设备。使用 `-I scripts/eis-android-acceptance.init.gradle` 构建的 `app.mihon.eis.dev` / `.test` 独立 debug 身份，保留 namespace/production wiring/debug 签名，非 debug variant 被拒绝；现有 fork 不降级、不 clear。受控 APK 安装前核对碰撞与摘要，只清精确 owned 包/SQL 收藏/loopback 仓库。
- `eis04-device-build` PASSED（1m49），后续增量构建均由协调器串行；验收 APK 不作为 EIS-05 正式交付。
- `eis04-api36-private-green.log` OK(1)：真实目录/收藏/DI→双包私有 loader；`eis04-api36-system-batches.log` OK(6)：Legacy、PackageInstaller 各连续、取消后显式 resume、提交后 stop 保留当前真实成功。
- 私有跨进程 runner 明确 `-e aex04RestartPhase prepare` / `verify`；两份日志 OK(1)，markers 为 PREPARED pid5741→VERIFIED pid5828/crossProcess=true，真实 loader/updater/阅读状态保持。
- `eis04-api36-shizuku-retry.log` OK(1)：正常 Allow all the time 后双包真实 Shell prepare/commit/回调；正常撤销本验收包授权并 Deny 后 `eis04-api36-shizuku-revoked.log` OK(1)：PERMISSION、remaining 保留、不切换、不落包。`eis04-api26-shizuku-unavailable-green.log` OK(1)：无服务真实 SERVICE，不回退。
- `eis04-api36-lifecycle.log` 后台 case STATUS_CODE 0：真实 HOME→APP_FOREGROUND→回前台无自动推进→显式 resume；该调用另一个重建夹具失败不称整轮绿。修复夹具后 `eis04-api36-recreation-fixed.log` OK(1)，新增恢复接线后再次 `eis04-api36-owner-recreation.log` OK(1)/10.432s，安装/卸载同 UUID 重建均通过。
- Legacy 原进程 PID8290 kill 后原 OS 确认继续，`eis04-api36-window-verify.log` OK(1)：生产启动自动库存收敛、完整 APK 摘要、第二项未安装、batch 不回放。instrumentation 退出令原 Activity finishing，此证据只与真实 recreate/JVM exact-owner 组合说明，不单独声称 saved-owner 设备恢复。
- PI prepare PID9978/session1233159300；仅 kill 该 PID 后同一 task263/Activity record 保留，真实 SessionInfo 返回准确包名及自身 installer，原设备 red 留存。最初 probe 因前次已删除测试包的旧卸载窗口置顶/未等待前台而未到 prepare；正常关闭后夹具明确 awaitForeground，未改产品行为。`eis04-api36-pi-session-green.log` OK(1)/5.501s：新 APK 原 session1233159300 初始化即 busy，原窗口确认后真实 callback→库存自动 published→预留释放；markers 为 PID9978→10876/noReplay=true，未主动调用刷新、未重建 session。设备生命周期门槛已闭合，最终批次检查见下。
- 一次 Shizuku runner 在 case 前出现 NotificationManager 以 com.android.chrome 身份发送通知异常，重启后后续必需设备用例未再出现；仅保留未归因观察，不宣称修复、不作为 Shizuku 行为红。

**最终验收（2026-09-17）**：`eis04-focused-final` PASSED / exit 0（4m12s），原始 XML 19 suites / 219 项：domain JVM 15、domain Android 15、app 186、Desktop 3，failure/error/skipped 均为 0；包含最终 Android instrumentation 编译。原始 XML 归档 `.gradle-coordinator/eis04-focused-final-xml/`，按目标目录隔离。`eis04-readonly-format` PASSED / exit 0（28s），仅用忽略目录中的 init 限定本批文件 target，保留原 ktlint 1.8.0 / 120 字符规则，不传 `spotlessIdeHook`；当时 Check 报实际执行或 UP-TO-DATE、无 SKIPPED；EIS-05 后续发现绝对路径字符串 target 可能为空匹配，因此不再单凭这份历史 scoped 记录断言覆盖，最终以 EIS-05 原规则无 scope 全量 spotlessCheck 通过为有效覆盖。前面的 IdeHook 仅排版，不计只读检查；临时排版规则未进入仓库。Desktop 沿用编译/行为测试及 diff-check，无 Spotless 插件。最终 `git diff --check` 通过。

主代理已独立核对关键协议、修复影响路径、219 项原始 XML 和真实设备证据；主代理接管切片也由实施代理独立审查。所有本批阻塞关闭，checkoff 与 production、测试、维护文档纳入同一功能提交，hash 随回执提供。API36 验收身份临时未知来源许可通过系统设置恢复关闭，Shizuku 授权已正常 Deny；没有更改既有 fork 或物理设备。EIS-05 仍未完成：完整 Android/Desktop（含 integration）验证、Test Mode 与正式三平台产物在下一批执行；本批 debug APK 不作为正式交付。


### EIS-05 已完成（完整测试、正式三平台运行及定向修复均已验收）

复用同一实施代理承担 Test Mode、Windows/Android 发布与唯一 Windows Gradle/ADB；主代理独立审查并负责 Mac 隔离构建，另外接管单个既有 Android instrumentation 文件的正式操作验收切片，由实施代理独立核对后执行。没有新增代理、测试服务或同步协议。

运行维护约定：

- Desktop 复验入口为 `python scripts/validate-extension-suggestions-runtime.py --executable <实际正式可执行文件> --output <新的隔离证据目录>`；macOS 使用 `python3` 和 app 内 `Contents/MacOS/Mihon Desktop`。只使用已核对身份的正式产物，保留仓库内受控 fixture；输出目录不得复用普通用户配置。
- macOS 经 SSH 直接运行 GUI launcher 曾因 headless 会话失败；改用 NSWorkspace 后以真实应用 PID 与 libproc 启动身份跟踪。大小写别名以 samefile 核对文件身份，不按路径字符串误判。LaunchServices 无法提供子进程退出码，证据明确标记 `exitStatusAvailable=false`；以原进程身份消失证明退出，不能伪造 exit 0。启动身份无法确认时保留失败及清理状态，不终止不明进程。
- Android 正式验收使用 `-I scripts/android-fork-release.init.gradle '-Pmihon.testBuildType=release'` 构建 host 与独立 instrumentation APK，再用既有签名脚本签名。PowerShell 中该 `-P` 参数须整体引用。host 的精确跨 APK ABI 保留规则适用于普通 release；新增验收调用时核对实际编译后的方法、字段及外部类型，不因测试失败关闭 host 的 R8。
- Android 界面 prepare/verify 必须由真实操作建立前置、以不同 PID 验证；失败后先检查私有 receipt，恢复前置后再运行，不能手工伪造成功状态。安装方式在修改前落盘保存，清包、SQL/仓库、偏好及服务器清理分别保留失败证据；仅清理摘要、包名、源 ID 与 URL 等完整身份均确认的本任务 fixture。
- Android 界面验收通过真实 accessibility 和触摸操作定位；网站返回会保留列表偏移，批次 Flow 终态也可能早于 Compose 节点更新。先确认插件页已选中，再在纵向容器中找回标题或批次操作；行操作按准确名称和同一行范围定位，在当前自有建议视口内有界双向滚动。禁止依赖固定屏幕坐标、夹具数组顺序，或用直接业务调用替代应验收的点击。
- Android 插件广播的异步加载结果必须服从同包最新事件：Receiver 为当前加载保存一次性身份，卸载或更新请求使旧身份失效，核验与 listener 通知和卸载失效操作共用锁；真实加载在锁外执行，不取消其它包的加载。完成或失败仅清除自己的身份，避免旧任务误删新请求及累计历史包。旧结果不得重新加入已卸载的插件或覆盖更新后的状态。

- Test Mode 追加既有 `extension_` action：建议状态/实际关联源与收藏计数、来源身份选择、本地折叠/忽略/撤销、单项安装、网站选择、固定清单请求/显式替代/确认/撤销、批次继续/重试/停止。所有业务调用既有 ScreenModel/panel/batch。确认 ID 不接受调用方任意产物；捕获 batch ID，由共享 controller 原锁内执行 expectedBatchId 比较，防旧 HTTP 确认消费新 UI 批次。
- 浏览器默认 Test Mode 保护保留。仅既有 platform-acceptance token 请求头可授权一次完整 URI 匹配的 loopback HTTP 网站，必须有显式端口、无用户信息或 fragment；token 与 share 共用一次性 CAS，不进入 action 参数、快照或文档。ThreadLocal 许可同步消费并在异常时清理，其他外部动作保持禁用。实际浏览器 GET 留给正式运行验收，不把 AWT mock 当 OS 证据。
- `extension_suggestion_show` 复用既有 TestNavigationController 请求 Browse→插件，只有实际扩展内容分支挂载后才确认显示，未挂载不确认；不以 HTTP 预设 currentScreen 或 DI 快照冒充窗口显示。
- 有效红包括 HTTP 缺 action、身份操作/固定确认、来源变更选择、旧确认误启动新批次，以及实际 Browse 消费缺失；共享 generation 红为三个路径均错误放行，修复后 JVM/Android 各 10 项通过。`eis05-testmode-green` PASSED，5 类 23 项全零；`eis05-browse-navigation-green` PASSED，Rendered 9＋TestMode 10＋Website 2 共 21 项全零。单项动作夹具原先等待随后立即清除的瞬时 Installed/raw state，已改为真实 service 调用屏障，仅证明分派；真正安装成功仍由正式库存/loader 核验。`eis05-format-instrumentation` PASSED（1m37），Android instrumentation 编译有效；该轮 scoped target 后来发现绝对路径字符串未匹配实际文件，不作为格式证据。目标已改为明确 FileCollection，最终以实际匹配的检查及未限定全量 spotlessCheck 为准，不扩大历史 scoped 证据。
- 组合编辑/验证命令一次在 CreateProcess 前被策略拒绝，未执行；按已有授权使用正常文件编辑工具与独立可读协调器 run 后成功，不改安全配置、不隐藏命令。
- 共用验收脚本 `scripts/validate-extension-suggestions-runtime.py` 使用 Python 3.9 标准库、既有 `--test-profile=<absolute>` marker/隔离 PreferencesFactory、真实窗口/HTTP 接口和两份仓库受控 JAR/APK。仅在自己的已退出隔离 profile 中向应用生成的真实 SQL 写 owned 收藏与 loopback 仓库；先核四产物摘要。实际 source IDs/语言为 2919241217/en、2919241218/zh、11403285/en，与 Android probe 一致。浏览器访问使用独有路径 GET；批次故障/停止用同一本地 HTTP fixture，不替换真实校验或 loader。脚本保存自己的 PID/profile 和失败清理证据，不清普通配置，未终态句柄不得丢弃。语法/fixture 哈希预检通过；随后 Windows/macOS 正式 runtime 均已通过，最终证据见下。
- 正式 Android 版本配置及签名前置 badging 已递增至 26 / 0.19.4-aex.8；安装前核对 API36 为 25/aex.7、API26 为 19/aex.1，随后均原位升级至 code26。R8/非 debug/既有 fork 证书约束保留。正式 instrumentation 须显式 `eisForkReleaseAcceptance=true`、严格 fork 包名、非 debuggable 与专用模拟器；默认仍仅验收 debug 身份，进程恢复 receipt 入口不放宽。
- 构建前 Desktop AppVersion 为 0.11.19.39；源码冻结后两主机从同一输入由正式 build-only 各增一次至 0.11.19.40.a9e561a，没有把 Windows 已加 BUILD 的文件再送 Mac 重加。最终唯一完整集合包含 `-PincludeIntegrationTests=true`，保留 live-network/network-survey/既有全站普查排除边界；实际结果与限定修复见下。

- 唯一完整集合 `eis05-final-full` 的 domain JVM 506 / Android 440、data JVM 357 / Android 225 均全零；test-desktop 52 项来自本轮有效 Gradle cache。Desktop 3115 项发现能力清单 19 处源码行号漂移及旧 LibraryScreenModel fixture 在 SQL/主 dispatcher 清理后的悬挂协程；另有 3 个既有条件跳过（macOS 专属、headless 窗口、非 release BuildInfo），不计通过。app 旧 Wiring 测试在新 Busy 契约下等待未释放的 NonCancellable 闸门，保存线程、日志、已完成模块 XML 与 app binary 后，仅停止协调器归属进程树，整轮记 CANCELLED，未完成的 app 旧 XML 不作本轮证据。
- 限定修复仅更新 manifest 的 roleEvidence.line，并让 Desktop fixture cancelAndJoin 自有 model 后才关闭 SQL；Android Wiring 断言清理期间 Busy、不替换 owner、真实释放后重试，finally 必释放闸门。`eis05-final-repair-focused` PASSED / 54s：Wiring 14＋Desktop parity 34 / SyncPanel 2 / LibraryHttp 5 / Enhanced 9，共 64 项全零。未改生产逻辑，替换对应类后 Desktop 等价完整证据为 3115 项、零失败/错误、3 条件跳过。
- `eis05-final-app-resume` 补齐被中断 app 与 instrumentation 编译、原规则无 scope/IdeHook 的全量 spotlessCheck，PASSED / 6m08s；原始 548 次执行含 Category 首次 UncaughtExceptionsBeforeTest 后重试通过。完整 cause 定位到 AppModule fixture 只 cancel 未等 manager scope 结束便还原 Injekt；改为 cancelAndJoin 后 `eis05-final-app-cleanup` PASSED / 46s，AppModule 1＋Category 2 全零。合并有效 app 为 547 项全零，不把重试失败隐去或增加一次全量重跑。原始 XML 分别归档 final-full-xml / final-repair-focused-xml / final-app-resume-xml / final-app-cleanup-xml。
- Windows 正式 `eis05-windows-release` build-only 已通过，脚本分配 BUILD 39→40，版本 0.11.19.40.a9e561a；真实 production APK 安装/加载的既有构建验收通过。建议专项 runtime 首轮完成真实 Browse、三个浏览器 GET、折叠/忽略重启后，因 fixture 目录把 Android APK applicationId 用作 JAR 包前缀而被真实校验拒绝；JAR 实际提供者身份为 aex00.external.v16 / v15，Android 为对应 .controlled，源 ID/签名一致。仅修目录夹具，不放宽生产校验；随后 eis05-windows-runtime-fixed 全流程通过。
- Android `eis05-android-release-fixed` 正式 R8/资源压缩及 release instrumentation 构建 PASSED / 4m56s，原 fork 签名后仅 -r 将专用 API36 25/API26 19 升级至 26/aex.8，无清数据。首轮设备测试准确暴露跨 APK 的 BasePreferences.extensionInstaller 成员被 host R8 内联删除，未进入安装行为，不当作批次失败。已按编译后 instrumentation 实际 Methodref/Fieldref 补普通 release 的精确 ABI 保留规则：78 个项目方法、两个原 DEX 缺失字段，以及实际跨 APK 调用的七个外部类型边界；保留 R8 优化，不使用包通配或测试专用未压缩 host。`eis05-android-release-external-api` PASSED / 2m41s，该阶段 host SHA256 为 402aa270c4c09fe9aae0511fdc16f89a587dda5cdc0ed0d6f6c2b03d1a99bc5c。该阶段 UI verify 尚未完成；现已由下文最终修复、设备验收及收口记录替代。

- Desktop 最终正式运行：Windows `eis05-windows-runtime-fixed/result.json` 与 Mac `eis05-macos-runtime-result.json` 均 success=true，各 8 次归属明确的真实 GUI 启动、3 个 OS 浏览器网站 GET，无遗留进程或 cleanupErrors；覆盖折叠/忽略重启、隔离 profile、部分失败/显式重试、真实安装和 loader、重启不回放、单项安装、跨普通入口去重及停止。Mac 使用 LaunchServices/NSWorkspace 真实应用 PID与libproc微秒身份；异常只按完整可执行文件身份、唯一profile/port/token收回自有进程，无法确认不发送终止，Windows仍用本次Popen。Mac为x64，未声称arm64运行验收。
- Receiver 定向修复前，等价完整测试独立汇总为 5,242 项：5,239 通过、3 个既有条件跳过、零剩余失败/错误。原始完整轮 CANCELLED、app 首轮重试失败与限定替换证据均如实保留；没有第二次完整集合。后续新增四项见下方最终修复证据。

- 正式 Android 设备夹具收口：巨型 runBatch 协程曾在真实 ART 出现 native abort（原 74,342 code units / 772 registers），仅拆出 UI/清理 helper 后为 45,222 / 561，保留真实调用、断言和 finally，不推断通用 ART 大小限制。API26 系统按钮仅修为严格包名、可点、可用条件下的大小写无关等值匹配。`eis05-android-fixture-split` PASSED / 3m10s；API26 Legacy 完整 OK(1)，API36 PRIVATE 与 PackageInstaller 取消/明确继续各 STATUS_CODE 0，但之后 AVD/runner 消失，组尾缺失不当作完整组终态，后续发现的精确 owned SQL/repo 残留已核身份清除。原因未证实，未 wipe 或加载旧 snapshot。
- API36 正式 Shizuku `eis05-api36-release-shizuku-clean.log` 完整 OK(1) / 28.994s，真实 R8 host→Shell 协议→双包安装及 finally。临时 fork 授权已通过正常 Shizuku 管理 UI 恢复原关闭状态；未知来源原 allow 未更改。首次旧夹具 native abort 前未保存原 installer/isSet，无法倒推确认该最初值已恢复；后续增加修改前持久 receipt、异常重入恢复及各清理步骤独立 finally，保护此后的精确原状态，不能用新 receipt 补称历史恢复。
- 正式 UI 定位修正已独立复核：`eis05-ui-viewport-snapshot` 编译通过；`eis05-api36-ui-prepare-snapshot.log` 完整 OK(1) / 73.097s，PID10917，三个真实网站请求及忽略/折叠齐全。诊断确认 accessibility 的标题与祖先边界会短暂不一致，无法确认视口时不触摸，重新获取后成功；不放宽实际容器和屏幕边界。
- 历史间歇失败：真实 force-stop 后 `eis05-api36-ui-verify-snapshot.log` PID11204 失败于 15s 等待；随后清理发现内存仍有 v16、受控私有文件已不存在。当时未确认具体事件顺序，没有把“活跃事务先后顺序”或晚到广播当成已证实原因。原日志、PID logcat 与只读磁盘/SQL 状态已保存；精确 owned 收藏 44–46 和 loopback 仓库 36493 的残留已核身份清除并复查零条，偏好恢复已核验。随后仅增加命名阶段及真实 Flow 观察定位，没有人工刷新或重跑全量。
- 命名阶段/只读 Flow 诊断版 `eis05-ui-inventory-abi` 编译通过（3m49s）；`eis05-api36-ui-prepare-inventory.log` 完整 OK(1)/7.833s，显式 force-stop 后 `eis05-api36-ui-verify-inventory.log` 完整 OK(1)/5.985s，PID11853→12151。真实三网站请求、折叠忽略重启、v16 单项安装、卸载后重新建议、固定双项部分失败、普通入口 Busy/仅一次 GET、真实 Retry failed→Install selected(1)、双项成功及建议消失均通过。`eis05-api36-release-ui-final-markers.log` 保存顺序；`eis05-api36-final-cleanup.json` 核验专用 AVD、自有 SQL/仓库 0/0、系统包空、两私有文件不存在、两个 receipt 空及 UI 偏好恢复 unset；原未知来源 allow 保留。此轮通过不解释 PID11204 的间歇失败，当前仅追加真实 Receiver 异步加载与卸载顺序的可控 focused 复现，不重复设备试运气。
- 卸载后旧加载回写已通过真实 `ExtensionInstallReceiver.onReceive` 与 Deferred 加载屏障取得准确行为红：`eis05-receiver-order-red` 的 ADDED/REPLACED 两种场景都在 `removed` 之后错误发出 `installed/updated` 和 `changed`。两项因既有重试策略共执行六次，均准确失败，不计六个独立场景。按上述一次性身份修复后，`eis05-receiver-order-green` PASSED / 1m03s，Receiver 4＋Manager 29＋Wiring 14 共 47 项、失败/错误/跳过均零；另两项覆盖同包替换时旧清理不删新请求及不同包独立。主代理已独立核对实际 diff、红绿原始 XML、锁边界与清理；PID11204 未逐事件追踪，保留“与此竞态一致”的因果限制。新增四项后等价完整证据为 5,246 项（5,243 通过、3 条件跳过），未重复完整集合。此修复已纳入下文最终正式 R8 产物并完成受影响设备回归。
- Receiver 修复后 `eis05-android-receiver-release` R8 构建 PASSED / 2m47s，中间 host SHA `49c60b62c44de5c4642acefaaf61d93b7cb075e1cf0021970de11020a1ef68d8`。prepare 完整通过，但 verify 在真实卸载后页面重组时崩溃；R8 mapping 将 `SourceIcon:106` 精确映射到 `ExtensionManager.getAppIconForSource:199` 对已消失包元数据的强制解引用。旧 Source 暂时仍在页面上是合法窗口，沿用现有空图标/默认图反馈即可，不改 UI 架构。
- `eis05-source-icon-red` 两项分别覆盖包信息空和 applicationInfo 空，均准确 NPE（含既有重试共六次）；最小修改只在缺元数据时返回 null，不写空缓存，保留正常 loadIcon/cache。`eis05-source-icon-green` PASSED / 1m01s，Receiver 4＋Manager 31＋Wiring 14 共 49 项、失败/错误/跳过均零，主代理独立审查通过。精确 owned 仓库 41971 和三条收藏清理后 0/0，UI 偏好按崩溃前 receipt 的原 isSet=false 恢复；安装器原值由持久 receipt 在下次验收恢复。最终等价完整证据为 5,248 项（5,245 通过、3 既有条件跳过），仍只有一次完整集合；新 Android host 与受影响设备回归正在执行，中间产物不当作最终验收。

- 三端稳定交付路径（不含 instrumentation 安装包）：Windows [Final unpacked EXE](D:/Shell/Github/mihon-eis/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.40.a9e561a-unpacked/Mihon%20Desktop.exe)，SHA256 `05142991769a68175bfb0b00b838a7bd84aafc5a3aa55afc12d6ab88f669f1d0`；macOS x64 [ZIP](D:/Shell/Github/mihon-eis/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.40.a9e561a-macos-x64.zip)，SHA256 `98deab8a6e49c16136426a8f7f8fab4fd2208b57d6bcbad8b2e5def767a7814e`，实际验收 app 位于 `/Users/altair/Github/mihon-eis/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.40.a9e561a.app`；Android [正式 APK](D:/Shell/Github/mihon-eis/app/artifacts/android/mihon-desktop-fork-0.19.4-aex.8-eis-release.apk)，SHA256 `16d26af5479dd338967ca4fe1ce8015a6e851280a561984c01c8946b90db04f1`。Android 原 fork 证书 SHA256 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，code26 / aex.8，实际非 debuggable、R8、v2/v3 签名均复核；仅专用 API26/API36 模拟器验收，未操作或声称实体手机通过。Desktop 同源同版本 BUILD40，Mac 未验收 arm64。


**EIS-05 最终收口（2026-09-17）**：`eis05-android-icon-release` 正式 R8 构建 PASSED / 2m52s，最终 host SHA256 `16d26af5479dd338967ca4fe1ce8015a6e851280a561984c01c8946b90db04f1`，code26 / aex.8，原 fork 证书与 v2/v3 验签通过、非 debuggable。两专用模拟器实际安装文件摘要均与交付 APK 相同（`eis05-final-android-device-hashes.json`）。本最终产物包含 Receiver 过期回写和图标空元数据两项修复，旧 402aa / 49c60 产物仅为历史证据。

- API36 `eis05-api36-ui-prepare-icon.log` 完整 OK(1)/51.552s，显式 force-stop 后 `eis05-api36-ui-verify-icon.log` 完整 OK(1)/51.034s；PID13863→14149。三个真实网站请求、忽略/折叠跨进程、实际单装/卸载重新建议、固定双项部分失败、普通入口 Busy/单次 GET、真实 Retry failed→Install selected(1)、成功排除均通过；完整 markers 见 `eis05-api36-ui-icon-final-markers.log`。
- API36 `eis05-api36-packageinstaller-icon.log` 完整 OK(1)/47.013s；`eis05-api36-shizuku-icon.log` 完整 OK(1)/46.35s。API26 `eis05-api26-legacy-icon.log` 完整 OK(1)/95.216s。复验仅针对本次 Android 修复，不重跑无变化 Desktop 构建或完整测试集合。
- `eis05-api36-final-icon-cleanup.json` / `eis05-api26-final-icon-cleanup.json` 独立核验 AVD 身份、自有 SQL/仓库 0/0、系统包空、两私有文件不存在、receipt 空或未创建、UI 两偏好恢复 unset。当前安装器分别 PACKAGEINSTALLER/LEGACY，原 unknown-sources allow 保留，Shizuku 临时 fork 授权已正常 UI 恢复关闭；最初旧夹具未保存 installer 原值的历史限制仍如上，不伪造恢复证据。API26 仅关闭本次归属的 AVD，API36 保留，无运行中的 Gradle 或验收 runner。
- 主代理独立审查最终 production/测试差异并核对原始 XML、正式运行/签名日志与三端文件实际摘要。最终有效测试 5,248 项：5,245 通过、3 个既有条件跳过、零剩余失败/错误；原失败、重试和取消记录均保留。原规则全量格式检查及最新受影响文件检查通过，最终差异检查通过。
- 本批超过估算文件数/行数，仍为一个内聚交付：同一建议能力的 Test Mode 接线、真实三平台运行脚本、Android 发布 ABI 与实际验收暴露的卸载竞态修复须一起交付。没有新增产品导航、同步协议或独立服务；维护约定、测试与本 checkoff 纳入同一 EIS-05 提交，不单独提交状态推进。
