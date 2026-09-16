# 收藏缺失插件「建议安装」完整实施 Roadmap

- 日期：2026-09-16
- 状态：IN_PROGRESS；已获准实施，EIS-01、EIS-02 已完成，EIS-03 待实施，完成状态以第 4 节批次勾选为准。
- 类型：产品 child plan；进度从第 4 节第一个未勾选批次推导，不另设活动任务字段。
- 父路线：[Android / macOS / Windows 正式 Roadmap](2026-06-30-mihon-desktop-refactor-roadmap.md)。本稿登记为待执行专项，不切换父计划当前执行指针，不恢复其他专项。
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
- [ ] EIS-03：共享批次编排、安装入口去重与 Desktop 全部安装闭环。
- [ ] EIS-04：Android 全部安装、系统交互与生命周期闭环。
- [ ] EIS-05：跨端集成收口、正式产物与真实运行验收。

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

**验证与验收**：从真实两个 UI action 入口竞争同包，只有一个有效事务且另一请求不取消它；批次失败不回滚成功项；无新增建议自动加入；未提交项停止，正在提交的项依真实结果结束。使用本地 HTTP fixture→production coordinator→临时插件目录→真实 loader 的闭环测试；真实产物来源可验证且不执行未知远端代码。

**独立检查门槛**：主代理或未实施该部分的审查者检查去重原子性、取消/提交边界、来源与签名策略，确认通过后 EIS-04 方可依赖。

**预期修改范围**：共享 install/presentation、两端必须共享的 install action adapter、Desktop UI/runtime 与安装集成测试。不新建下载队列或全局任务系统。预计 1–1.5 工程日。

### EIS-04：Android 批次安装与系统生命周期

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

Windows 环境与串行 Gradle 示例（计划命令，尚未执行）：

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
python scripts/gradle-coordinator.py run --key eis-final -- gradlew.bat :domain:jvmTest :domain:testReleaseUnitTest :data:jvmTest :data:testReleaseUnitTest :app:testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test spotlessCheck --max-workers=2
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
