# 架构、复用与界面规划

本页是[根规则](../../AGENTS.md)按动作加载的项目约束。仅阅读本次动作适用章节；代码命令与普通路径以仓库根为基准，Markdown 链接按本页位置解析。

规划、实现或审查 capability、共享逻辑、数据或 UI 前使用；Desktop UI 先遵循下文链接的既有实施规范，不另建规范体系。

## 功能规划原则

**规划任何用户可见 capability 时都必须同时考虑用户界面。**

### Desktop UI 规范入口

- 新增或修改 Desktop 页面、导航、弹层、列表交互，或对应 HTML 审阅原型时，先读 [Desktop UI 实施规范](../../docs/design/mihon-desktop-ui/README.md)，再读适用的组件事实表和页面契约；其他页面按同样字段定义本次契约，不机械套用三个样本的业务。
- 实现前固定验收，区分 `SOURCE`、`PROJECT_POLICY` 与 `HTML_ADAPTER`。平台常量按实际消费组件核对，不把 Android 值自动作为 Desktop 值；当前 Desktop 封面 7:10 与 Android Book 2:3 即为已知差异。
- 必做验收不得在实现后改为未实现或不适用以获得通过。行为测试执行真实事件及 wiring，视觉检查记录来源和环境；浏览器原型通过不能代替[原生测试](desktop-acceptance.md)及[正式构建验收](desktop-build.md)。

每项用户可见 capability 必须有入口和反馈；内部基础设施不要求独立 UI，但必须被真实产品链路使用并有集成测试。规划时检查：

1. 用户如何触发？（按钮、菜单、快捷键等）
2. 结果如何反馈？（状态、Toast、对话框等）
3. 危险操作是否需要确认？（AlertDialog）

**用户可见 capability 没有入口或反馈 = 功能未完成；内部基础设施没有 production wiring 或集成测试 = 功能未完成。**

### 复用优先

新增功能前必须检查项目内是否已有相同或相近能力可复用，包括：

- 已有 Use Case、Manager、Repository、Service
- 已有搜索、分页、错误处理、缓存、同步、下载、解析流程
- 已有 Screen、Tab、Composable、导航入口
- 已有数据模型、数据库表、查询、状态管理、测试工具

规划时必须回答：

1. 能否直接复用现有功能？
2. 不能直接复用时，是否应抽取公共能力供新旧功能共用？
3. 新特性应追加到已有链路，还是确实需要独立维护？
4. 若独立实现，必须说明不能复用的技术原因和用户体验原因。

**能复用却另起一套实现，默认不允许。**

上游对齐与共享契约的常驻约束见[根规则](../../AGENTS.md#上游对齐原则)，本页补充规划及架构事实。

## 架构

Mihon 是由 Android 应用、Mihon Desktop 和共享 Kotlin 模块组成的多平台代码库，采用分层架构：

| 模块 | 职责 |
|---|---|
| `app/` | 表现层：Compose 页面、Activity、DI wiring |
| `app-desktop/` | Desktop 表现层、平台 adapter、运行时 wiring 与桌面端测试 |
| `test-desktop/` | Desktop E2E 测试客户端与 Robot API |
| `domain/` | 业务逻辑：用例、领域模型、仓库接口 |
| `data/` | 数据层：SQLDelight 数据库、仓库实现、映射 |
| `presentation-core/` | 跨页面复用的 Compose 组件 |
| `core/common/` | 公共工具与 Kotlin 扩展 |
| `source-api/` | KMP 漫画源抽象，供扩展复用 |
| `source-local/` | 本地文件源 |
| `i18n/` | Moko 字符串资源 |

### 包名

因 Tachiyomi → Mihon 迁移历史，仓库存在多个包名前缀：

- `eu.kanade.tachiyomi.*`：app 模块和多数旧代码
- `tachiyomi.domain.*` / `tachiyomi.data.*`：domain 与 data 模块
- `mihon.domain.*` / `mihon.feature.*`：较新的 Mihon 功能
- `mihon.desktop.*`：Mihon Desktop 的 UI、平台 adapter、运行时与独有能力

### 关键模式

- **依赖注入**：Android 与 Desktop 均使用 Injekt。Android 模块注册在 `app/src/main/java/eu/kanade/tachiyomi/di/`；Desktop wiring 位于 `app-desktop/src/main/kotlin/mihon/desktop/di/` 和 `DesktopUiDependencies.kt`。使用 `Injekt.get<T>()` 获取依赖，使用 `by injectLazy<T>()` 延迟注入。
- **导航**：Android 与 Desktop 均使用 Voyager（`cafe.adriel.voyager`）。Screen 实现 `cafe.adriel.voyager.core.screen.Screen`，导航通过 `Navigator` / `LocalNavigator`。
- **数据库**：SQLDelight + 协程。schema 与 migration 的唯一权威位于 `data/src/commonMain/sqldelight/`，生成查询在 `tachiyomi.data.*.db`；不得重建 `data/src/main/sqldelight/` 镜像。
- **图片加载**：Coil 3，自定义 fetcher / decoder 位于 `app/src/main/java/eu/kanade/tachiyomi/data/coil/`。
- **偏好设置**：`tachiyomi.core.common.preference` 封装 AndroidX DataStore / SharedPreferences。

### 构建逻辑

自定义 Gradle 插件位于 `buildSrc/src/main/kotlin/`：

- `mihon.android.application`：应用基础配置
- `mihon.library`：库模块配置
- `mihon.code.lint`：Spotless + ktlint

依赖版本由 `gradle/*.versions.toml` 管理。

### Desktop 阅读器的章节入口

章节位置必须区分初次打开、恢复和阅读中跨章。新开已读章节从首页开始，未读章节沿用共享原子打开结果；恢复位置仍绑定目标章节及其同步快照。阅读区域、键盘或滚轮翻页越界进入上一章末页／下一章首页，不能继承离开章节的页码。页列表尚未到达时保留入口意图，加载后再解析末页。

底栏上一章／下一章按钮与原版 Android 工具栏一致，明确进入目标章首页。`DesktopReaderScreen` 为按钮提供独立回调，`ReaderViewport` 不得把它们与阅读流的越界回调合并。两类入口复用既有章节筛选、身份和激活链路；无目标章节时不激活，加载失败沿用原有重试。不能在章节状态发布后再次套用“已读重开”的规则覆盖已选位置。

`DesktopReaderChapterTransitionIntegrationTest` 覆盖真实挂载 Screen 的底栏按钮点击、异步页列表到达及长短章节交接；`HistoryReaderOpenContextIntegrationTest` 覆盖已读／未读的新开页码和原子同步基线。键盘／滚轮仍沿用阅读流越界语义，方向与分组回归位于 `ReaderKeyboardNavigationPositionTest`。

### Desktop 阅读器放大平移边界

Desktop 的 `ReaderPanBounds` 是阅读器视口交互策略：放大后，拖动产生的单侧留白最多为对应视口尺寸的三分之一；小图或偏置布局原本不可避免的留白保留，不能继续向该方向扩大。边界复用 `resolveReaderRegionDrawGeometry` 的实际绘制范围，包含适配比例、对齐、裁白边及分割后的图片尺寸，不能以图片容器空白代替漫画边缘。

单页由 `ZoomablePageBox` 限位；双页由 `DualPagePagerViewer` 将已加载图片映射到整组视口后合并外框，共用一次手势和限位。未取得任何图片几何时不猜测边界；另一页待加载或失败时保留其加载/重试槽位范围，避免把整组误当成单张并丢失合法的双页焦点；就绪后更新实际图片边缘；收紧当前已合法焦点时遵循下述帧内保护。仅当前阅读单元可回写缩放状态，预加载单元不能改动当前视图。手势内部也保存限位后的状态，避免持续越界拖动积累隐藏位移；缩放和视口变化后重新计算。长条模式沿用原有滚动，本策略不改变 Android 缩放语义或持久化设置。真实指针与像素回归位于 `SharedDualPageNativeStablePresentationTest`，几何策略回归位于 `ReaderPanBoundsTest`。

双页以稳定物理 frame 身份记录最近一次受限后的合法焦点。后台图片到达、重试或未绘槽撤销使实际边界收紧时，真实几何继续更新，但允许保留已显示焦点，不能自动搬动画面。用户继续向留白方向拖动不能超过该焦点；回拖立即跟随并收紧这份临时余量，不能积累隐藏位移或在松手后重新扩大。背景几何与同帧输入合并时仍以此前合法显示值为边界，不把新的越界输入当成许可。主动缩放、视口尺寸变化及更换物理帧按实际几何重新限位；首次尚无真实边界时不凭空保留越界焦点。此状态只保存当前组件的有限几何／焦点元数据，不影响配对、图片身份、读取预算或阅读进度。背景到图与真实拖动的组合回归保留在上述原生测试，合帧状态回归执行 production `ReaderPanBoundsState`。

后台邻页解码回报的宽图集合属于图片元数据。Desktop `ReaderScreenModel.setSpreadPages` 只更新该集合，不能借用主动呈现变更入口清空当前显示单元、已绘页集合或推进外部输入版本；共享原图事实与稳定帧协议负责后续配对。用户切换方向、模式、自动布局、拆页以及真实几何上下文变更仍走各自原有失效与重新定位流程。处理方法回归使用真实HTTP／Skia目标像素，再调用生产元数据入口检查选中帧及几何；完整邻页异步回调还须由当前候选窗口另行验证。

### 同步面板的打开时序

同步工具栏的 `Open` 与面板的 `Close` 在 `SyncPanelController.dispatch()` 中立即更新可见状态，不能等待安全存储、数据库、诊断报告或其他串行命令。状态读取和关闭后的任务清理仍通过原命令队列执行；可见请求版本保证旧的打开、关闭或返回操作不覆盖用户后来的打开意图。同步图标转动只表示后台活动，不是允许打开面板的条件。初次读取尚未完成时，复用现有的连接检查状态；已停止的 controller 不再接收打开请求。回归入口是 `SyncPanelStorageContract` 中安全存储读取暂停期间的打开、关闭与重新打开测试。

初次连接检查不能阻塞本地待确认列表。活动空间和队列的数据库订阅在 `loaded == false` 时通过本地队列读取活动身份、仓库元数据及待确认快照，沿用现有列表展示，也持续展示检查期间新产生的条目。这只是数据库投影，不表示凭据、保护模式或连接已经核验；`loaded` 仍为 false，连接检查及其失败重试入口保留，完整检查完成后才补齐连接信息。单条操作继续使用事务内的活动空间和 binding 校验；无活动空间时清空初始本地投影，不读取或合并历史空间条目。初始快照与完整快照共用现有发布锁，完整检查完成后不允许迟到的初始投影覆盖它。回归测试在重建 controller 后挂起真实安全存储读取，覆盖已有条目、检查期间新条目、两种单条决定，以及真实指针点击的 UI 接线。

待确认条目的保留/取消属于本机数据库决定。单条按钮直接生效，不弹确认窗：dispatch 固定当前空间和条目 binding，交给独立的本地串行队列，不能排在完整同步状态、凭据或恢复读取之后；projector 复用既有事务，再次校验数据库活动身份和 binding。批量操作仍冻结条目集合并显示确认窗，提交时以及每页处理前校验 active space。空间切换仍先取消本地批处理，切换准备期间不启动新的本地决定。

本地决定完成后立即读取数据库局部快照并更新条目和计数，不重读 GitHub 凭据、加密空间记录或生成诊断报告。局部读取/发布与完整刷新最后的数据库快照读取/发布共用互斥锁，慢凭据和恢复读取不能持锁，避免旧快照重新显示已处理条目；选择引用等命令队列内部状态仍由原队列维护。数据库订阅使用同一局部刷新，授权、空间变更与同步结果仍走完整刷新。失败或失效的决定不得通过提前隐藏条目伪装成成功。`SyncPanelStorageContract` 覆盖完整安全存储读取挂起期间两种单条决定直接提交及刷新、零额外凭据读取和零 GitHub 请求，以及既有的批量确认、失败重试和空间切换回归；真实指针点击到 production controller 的 UI 集成测试覆盖单击即生效且没有确认窗。

### 同步授权与令牌续期

同步的账户查询、仓库管理、恢复和 Git 数据交换通过 `SyncOnboarding` 的请求级 token provider 获取凭据，不能把会话开始时的访问令牌固定到整轮同步。provider 缓存绑定当前 `authorizationEpoch`；显式重新授权或清除凭据后，旧会话必须停止，不能自动跟随新账号。浏览器返回的候选令牌在身份验证期间固定使用，不得替换成当前已保存账号的令牌。

有效令牌只在内存中复用，临近到期或 `PersistentGitHubCredentialStore.tokenRefreshEpoch` 改变时才重新读取并续期，避免每个请求都启动 Windows DPAPI 解密子进程。刷新 epoch 仅在新凭据成功持久化后更新；续期失败仍保留原凭据，并沿用网络重试或确认失效后的授权恢复路径。身份确认使用授权 epoch，不能把正常令牌轮换当作账号变更。相关行为由 `SyncRuntimeWiringTest` 的会话续期、缓存和身份隔离用例覆盖。

### 书架分类标签排序

书架分类标签的直接拖动由 `LibraryCategoryTabs` 适配 Desktop 指针，越过 Compose 水平移动阈值即开始，无需长按；保留 Material
`SecondaryScrollableTabRow`、普通点击、滚轮与现有分类数量显示。拖动期间仅投影标签顺序，
边缘停留可横向滚动；松手才调用现有 `LibraryScreenModel.reorderCategory` / 共享
`ReorderCategory` 保存，索引只计算自定义分类，系统默认分类固定在最前。
选中分类按稳定 ID 保留；保存期间禁止重复操作，失败清除投影、显示原有错误反馈并允许重试。
手势取消、离页或拖动期间收到分类仓库变化时放弃未保存投影。该适配器使用标签实测位置，
不将分类管理页的 LazyColumn 拖动状态套用到 Material TabRow，也不新增排序权威。
`LibraryCategoryDragTest` 通过真实书架指针和 SQLite 覆盖保存、重开、失败恢复、默认分类及窄窗口边缘拖动。

### 书架下载反馈与更新摘要

书架下载数量不使用 `batchCategoryResultMessage`，该字段只负责分类操作反馈。下载结果由 `downloadResultMessage` 保留最近一次文案供离页通知使用，页面通过无 replay 的 `downloadMessages` 事件显示 Material Snackbar；连续请求替换当前气泡，关闭、超时或返回书架不能重播旧事件。下载数量以真实入队接受结果为准；原始目录没有未读时提示没有未读，有未读但被筛选、已下载或已排队时提示没有可添加的未读。部分失败和源不可用保留错误信息，不伪装成没有未读；书签下载为空仍使用章节不可用提示。

更新摘要与“更新结果”在同一行，摘要的各栏在窄窗口内单行横向滚动、按钮保留可访问宽度，不改变现有结果弹窗与任务状态权威。回归位于 `LibraryPageCompositionTest`，执行真实下载菜单点击、Snackbar 生命周期、重复提示和宽窄窗口按钮接线。

### Desktop 书架更新反馈的会话边界

书架更新只由分类下方的状态行反馈；`DesktopSystemNotifier` 按稳定 task ID 屏蔽书架任务通知，其他任务仍走原通知链。状态行包含更新状态、仅在新章节总数大于零时出现的作品/章节统计，以及仅在失败数大于零时出现的失败统计。计数从实际任务单元投影，按作品 ID 去重；结果弹窗继续提供原有的失败原因、取消、重试和恢复操作。

`LibraryScreenModel.updateBannerVisible` 是内存中的展示状态，与持久化任务及恢复数据分离。首次观察到真实运行任务可展示，历史终态不能恢复提示。状态栏归属于触发更新时的稳定分类 ID（`updateBannerCategoryId`），与任务的 ALL/CATEGORY 处理范围分离。其他分类不展示此任务反馈；运行中切走不清理原分类的反馈，返回原分类继续显示实时状态。分类重排不改变归属，重复运行请求不得将已有任务反馈转移到其他分类；实际新任务、重试或恢复从当前发起分类捕获归属。首次恢复仅复用任务已有的明确 categoryId，全局恢复没有分类信息时不猜测归属、不跨分类展示。更新在根书架可见期间完成时保留反馈，直到完成后的下一次分类或页面切换；离开根书架或停留在其他分类期间收到完成、失败或取消终态则隐藏，返回时不重播。`enterUpdateBannerHost`/`leaveUpdateBannerHost` 由根页的 composition 生命周期接线，任务观察流仍持续跟踪真实状态。隐藏后迟到结果以及后台调度不能自行解除隐藏，用户再次显式触发更新、重试或恢复时才重新展示，已有运行任务不能重复启动。结果弹窗、主题及窗口尺寸变化不清理提示。隐藏只影响反馈，不取消后台任务、不删除检查点；启动失败仍在可见状态行反馈，任务失败的原因和恢复路径留在结果弹窗。

回归由 `LibraryPageCompositionTest` 覆盖真实分类点击、详情返回、主导航切换、栏位及结果弹窗接线，`NotificationServiceTest` 与 `LibraryUpdateRecoveryIntegrationTest` 覆盖通知归属和真实任务结果。

### Windows 下载单页首次组合

Windows 的已下载、固定 RTL/LTR 单页、无自动布局及自动拆页入口可在首次组合及完整模型即将创建时读取同一 session 的已发布页表。只有章、代次、恢复位置及显示设置均可确定时，才先挂载真实 Queued holder，实际 apply 后创建完整模型；同一 runtime 与 movable-content registry 保留该 holder，再经原有 Ready、内容认证、解码与真实绘制链路呈现。进度由实际绘制确认。

最终选择仍未就绪或身份/设置无法确认时沿完整模型原链；准入只决策一次，后到页表不再次尝试。这项时序限制可能使正常首开不命中，不能以命中样本宣称每次都达到 Android 耗时。完整模型创建前取消由轻 entry 释放 runtime，创建后由原模型释放，均只释放一次。双页、条漫和自动拆页沿其现有路径。实现与证据见[首开收口记录](../evidence/desktop-reader-startup-closeout-2026-11-08.md)。
