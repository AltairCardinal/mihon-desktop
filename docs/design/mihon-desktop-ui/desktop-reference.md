# Mihon Desktop 参考事实表

日期：2026-09-25。源码基线：8fae5d0ff85aa89c9c7efb5aa69a2b7e55581f83（本轮当前 HEAD）。范围只包括 Desktop 书架/筛选、作品详情/章节选择、同步面板及它们直接引用的共享主题/领域代码。以下为静态源码事实，不表示运行验收已通过。页面契约文档标注的 407ec814… 是其冻结基线；其中新增要求按 PROJECT_POLICY 处理，不作为当前实现证据。

## 标记和使用方式

- SOURCE：当前 HEAD 中可以定位到的实现事实。文件行号以该 commit 为准；Compose 默认行为不被写成仓库显式常量。
- PROJECT_POLICY：仓库 AGENTS.md 及本轮 README/page-contracts 的复用、上游语义和交互验收约束。它规定目标，不代表某个控件目前已符合。
- HTML_ADAPTER：把 Desktop 源码结构映射到本地 HTML 原型的建议。CSS 像素、浏览器焦点和截图不能证明 Compose 实际尺寸或产品视觉通过。
- 仅当 appTheme=DEFAULT 时，下面的六色值代表 Mihon 默认 Tachiyomi 调色板；其他主题或 Monet 可能不同。source code 通过 MaterialTheme 使用当前 ColorScheme。

## SOURCE：当前源码事实

| 页面/对象 | 可复用事实 | 证据位置 |
|---|---|---|
| 书架入口 | LibraryToolbar 在普通模式显示标题、DesktopLibrarySyncAction、搜索和显示/排序/筛选等动作；搜索模式改为输入框并请求焦点。 | LibraryComponents.kt：152–205；LibraryTab.kt：551–570 |
| 网格布局 | 同时按宽高选择 portrait/landscape 列偏好；列数大于 0 用 Fixed，否则用 Adaptive(minCardWidth)。内容边距 8dp，横纵间距均 8dp。 | LibraryComponents.kt：545–576 |
| 封面比例（按平台） | Desktop 书架网格和详情封面均用 0.7f（7:10）及 ContentScale.Crop；Android 共享 MangaCover.Book 用 2f/3f（2:3）及 Crop。两端源比例不同，不可统一描述成 2:3。 | Desktop LibraryComponents.kt：799–809；MangaDetailComponents.kt：137–155；Android MangaCover.kt：18–20、31–50 |
| 列表封面 | LibraryList 的封面图片显式 48dp 方形；这是图片本身的尺寸，不能当成整行或点击区域尺寸。 | LibraryComponents.kt：608–659 |
| 书架卡片操作 | 卡片主体使用 shiftAwareCombinedClickable，并另行监听鼠标次键打开 context menu。该卡片主要点击覆盖 Card 内容。 | LibraryComponents.kt：751–789；ShiftAwareClickModifier.kt：15–33 |
| 筛选循环 | LibraryScreenModel.toggleFilter 对下载、未读、开始、书签、完结、自定义区间及追踪过滤使用 TriState.next。顺序是 DISABLED → ENABLED_IS → ENABLED_NOT → DISABLED。 | LibraryScreenModel.kt：500–516；TriState.kt：3–14 |
| 强制筛选 | globalDownloadedOnly 时，toolbar/dropdown 将下载过滤展示成 ENABLED_IS，并在 LibraryFilterDropdown 禁用该项。 | LibraryComponents.kt：160–164、411–441；LibraryFilterUi.kt：18–22 |
| 书架多选 | Shift 按下的主点击走范围选择；选择模式普通点击切换实体；未选择时打开详情。长按调用 toggle。输入 helper 只读取 Shift 修饰键。 | LibraryTab.kt：304–312、704–769；ShiftAwareClickModifier.kt：15–33；LibrarySelectionState.kt：34–45 |
| 全选与反选 | 顶栏把当前 displayedItems ID 列表传给 selectAll/invertVisible。领域实现让全选把可见项 union 进已有集合；反选只翻转 visibleIds，过滤后已选的隐藏项会保留。 | LibraryTab.kt：532–538；LibrarySelectionState.kt：50–65；LibrarySelectionPolicy.kt：68–95 |
| 范围选择 | anchor 和目标都在当前 visibleIds 且分类相同时，范围按可见列表索引闭区间添加；无 anchor、分类不同或 anchor 已不可见时只添加目标并重设 anchor。 | LibrarySelectionState.kt：26–32；LibrarySelectionPolicy.kt：12–65 |
| 详情封面 | 详情图片显式宽 120dp、aspectRatio(0.7f)、ContentScale.Crop；其比例与书架网格一致，仍不是 2:3。 | MangaDetailComponents.kt：137–155 |
| 章节行 | ChapterRow 使用 Material3 ListItem 和 combinedClickable；普通行点击阅读，进入选择模式后点击选择/取消，长按选择。右侧书签、下载、重试等动作独立放入 IconButton。 | MangaDetailComponents.kt：554–683 |
| Desktop 章节选择能力 | 当前状态对象只实现 toggle、selectAll、clear；屏幕调用 selectAll 作用于 displayedChapters。没有从该状态对象找到反选或 Shift 范围 API。 | ChapterSelectionState.kt：11–26；MangaDetailScreen.kt：139、311 |
| 章节行尺寸 | 没有发现显式固定行高或整行最小高度。read 图标的 10dp/24dp 是图标 layout size，不能推导为按钮热区。行头另有 16dp 水平、8dp 垂直 padding。 | MangaDetailComponents.kt：577–593、660–684；MangaDetailChapterListItems.kt：41–53 |
| AppBar 尺寸 | 书架选择栏和作品详情使用 Material3 TopAppBar；调用点没有传显式 height。IconButton 调用未设 size。具体最终高度/命中框由 Compose 组件及运行环境决定，本表不补猜测数值。 | LibraryComponents.kt：523–538；MangaDetailScreen.kt：262–275 |
| 详情继续阅读 | nextUnread 优先取 syncedResumeChapterId 对应章节，再回退到 nextUnreadChapter。Scaffold ExtendedFloatingActionButton 只在漫画和目标章节存在、且章节未进入选择模式时显示；文案按是否已有已读章节选开始/继续。 | MangaDetailScreen.kt：256–258、543–570 |
| 书架继续阅读小图标 | 这是封面卡片上的独立小控件，不是详情 FAB；仅在开关开启且有未读章节或同步恢复 ID 时绘制。选中时点击回调变为切换选择。 | LibraryComponents.kt：561–562、871–890；LibraryTab.kt：314–322 |
| 默认浅色方案 | primary #0058CA，surface #FEFBFF，surfaceContainerHigh #FCF7FF。 | TachiyomiColorScheme.kt：55–88 |
| 默认深色方案 | primary #B0C6FF，surface #1B1B1F，surfaceContainerHigh #292730。 | TachiyomiColorScheme.kt：19–52 |
| 深色 AMOLED | static theme 的 AMOLED 分支把 surface 设为黑色、surfaceContainerHigh 设为 #131313；light 模式不走此覆盖。 | BaseColorScheme.kt：11–41 |
| 主题选择链 | DesktopTheme 从桌面偏好收集 appTheme、themeMode、themeDarkAmoled，再调用共享 AppThemeColorScheme；ThemeMode.SYSTEM 使用 isSystemInDarkTheme。 | DesktopTheme.kt：20–45；AppThemeColorScheme.kt：6–28 |
| 同步入口和依赖 | DesktopLibrarySyncAction 取 LocalDesktopUiDependencies.syncPanel；为空则不渲染入口。依赖工厂由 SyncRuntime.panel 填充。该 action 位于书架普通 toolbar；搜索模式切换 toolbar 分支时不显示它。 | DesktopSyncPanel.kt：31–40；DesktopUiDependencies.kt：170、258–266；LibraryComponents.kt：171–180 |
| 同步面板尺寸 | ModalBottomSheet sheetMaxWidth=560dp，外层 heightIn 最大 720dp；SyncPanelContent 最大 680dp。 | DesktopSyncPanel.kt：40–56 |
| 同步返回和关闭 | Escape 分派 SyncPanelAction.Back：MAIN 页由 controller 转为 Close；其他页返回 MAIN，SETUP 先取消授权。onDismissRequest 直接分派 Close；controller 关闭时取消授权、确认和选择并隐藏面板。 | DesktopSyncPanel.kt：42–52；SyncPanelController.kt：284–304 |
| 同步焦点 | Desktop wrapper 未设面板级 FocusRequester。共享 SetupPage 的密码输入有 FocusRequester；本段明确的 requestFocus 发生在切换密码可见性时。密码是 session-local remember 状态，离开步骤或关闭会重建。 | DesktopSyncPanel.kt：31–56；SyncPanelContent.kt：672–675、730–758 |
| 同步平台反馈 | 打开验证网页失败转 DesktopNotification；复制代码的 Failed/Unavailable 转桌面通知。 | DesktopSyncPanel.kt：57–74 |

## PROJECT_POLICY：仓库约束

| 约束 | 对本参考页的含义 | 来源 |
|---|---|---|
| 用户可见能力要有入口和反馈；内部能力要接入真实 production 链路并测试。 | 同步面板需记录书架入口、状态反馈和同步 runtime wiring，不能只画设置卡片。 | AGENTS.md：73–83 |
| 优先复用现有 use case、组件、导航、状态和测试工具。 | 先复用 LibraryToolbar、MangaCoverCard、ChapterRow、ColorScheme、SyncPanelContent；新视图只覆盖平台 adapter 的差异。 | AGENTS.md：85–101 |
| Mihon Desktop 上游对齐，但保留桌面独有能力。 | 书架/章节业务语义跟共享实现一致；设备同步和鼠标键盘窗口行为按 Desktop 扩展，不因 Android 没有同等入口而删除。 | AGENTS.md：103–107 |
| 证据须执行 production 实现及 wiring。 | 本表源码行号只帮助定位；未运行 Compose UI 或产品测试，不标记尺寸、焦点或返回已验收。 | AGENTS.md：109–117 |
| 本轮章节选择契约 | page-contracts D2/D3 要求当前可见集反选和 Shift 闭区间范围选择；锚点失效时只选目标。它们是新增验收目标，当前 Desktop ChapterSelectionState 尚无对应 API。 | page-contracts.md：31–37 |
| 本轮弹层焦点契约 | README 焦点约束及 page-contracts S3/S4 要求弹层入焦、Tab 循环、背景不可交互、关闭还焦，并保留状态、不抢另一端焦点；Desktop wrapper 的静态源码不证明这些已实现。 | README.md：26–30；page-contracts.md：46–48 |

## HTML_ADAPTER：原型映射界限

| 对象 | HTML 可映射的源事实 | 不应声称的结论 |
|---|---|---|
| 封面 | Desktop CSS aspect-ratio: 7 / 10，Android 样例 2 / 3；分别模拟 cover crop。网格列数随明确选择的视口策略变化。 | 不要把 Android 2:3 写成 Desktop 比例，也不要称 HTML 截图为生产基准。 |
| 颜色 | 为默认浅/深/AMOLED 方案建立 CSS variables，值按上表引用；主题切换显式选择方案。 | 不要把 #0058CA/#1B1B1F 写成所有主题唯一颜色；CSS 颜色接近不代表 Compose 色彩或系统窗口完全一致。 |
| AppBar 和章节行 | 复刻层级、动作分区、章节选择态及相同状态下动作显隐。 | 对源码未给定的顶栏高度、章节行高、IconButton 热区不要在资料表里补出 Compose 数值；HTML 自选尺寸应标为 adapter 值。 |
| 多选 | 使用当前可见 ID 作为范围和反选集合，保留隐藏的先前选择；覆盖 Shift 范围、全选、反选和空选择。 | 不要把过滤后的可见集合误画成会自动清除隐藏选择。 |
| 同步面板 | 复刻 MAIN/SETUP 内退和状态反馈；对 max-width/max-height 标注源值 560/720dp、内容上限 680dp。 | CSS px 与 Compose dp 不是可直接等同的运行时测量；缩放、焦点、窗口拖拽关闭仍需 Desktop 验收。 |

HTML 适配用于审查信息层级、状态与操作路径。生产尺寸、颜色渲染、焦点和命中区域最终应回到当前 Compose 组件与 Desktop 运行环境核验；本页不读取或审查当前 HTML Demo。

## 后续视觉核验项

- 默认主题分别打开浅色、深色和深色 AMOLED，核对 primary、surface、surfaceContainerHigh；再切换一个非默认主题确认页面不锁死默认色值。
- 按端检查封面容器：Desktop 书架和详情宽高比来自 0.7f；Android Book 来自 2f/3f。分别检查 ContentScale.Crop 的裁切，不以图片素材本身比例代替容器比例。
- 调整窗口宽高和列偏好，记录 Fixed 与 Adaptive 两条网格路径、8dp 网格留白及 toolbar 收窄时动作是否仍可访问。
- 用运行时布局检查器观察 TopAppBar、IconButton、ListItem/ChapterRow 实际边界；将测量结果和 Compose 版本/窗口缩放一并记下，不以 10/24dp 图标尺寸代替热区。
- 同步面板依次在 MAIN、SETUP 内按 Escape、再点遮罩关闭；检查 560/720/680dp 上限、密码焦点、授权取消、复制/外链错误反馈，并记录窗口尺寸及缩放。

当前报告是 SOURCE 索引与核验计划，不是视觉通过报告。未启动应用、未运行 Gradle 或自动化测试，也未把任何 HTML 预览截图用作 Mihon 视觉基准。

## 源码路径索引

以下链接指向本轮基线中的符号所在文件；行号见 SOURCE 表。Android 与 Desktop 路径分列，引用路径用于快速核对，不代表运行行为已验收。

- Desktop 书架工具栏、网格、封面卡片、筛选菜单：[LibraryComponents.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryComponents.kt#L152)
- Desktop 书架选择入口与动作：[LibraryTab.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt#L304)
- Desktop 书架过滤状态：[LibraryScreenModel.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryScreenModel.kt#L500)
- 共享三态枚举：[TriState.kt](../../../core/common/src/commonMain/kotlin/tachiyomi/core/common/preference/TriState.kt#L3)
- Desktop 书架选择状态与 Shift 处理：[LibrarySelectionState.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibrarySelectionState.kt#L16)、[ShiftAwareClickModifier.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/ShiftAwareClickModifier.kt#L15)
- 书架可见集全选/反选策略：[LibrarySelectionPolicy.kt](../../../domain/src/commonMain/kotlin/tachiyomi/domain/library/LibrarySelectionPolicy.kt#L12)
- Desktop 详情页与章节选择状态：[MangaDetailScreen.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt#L139)、[ChapterSelectionState.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/ChapterSelectionState.kt#L11)
- Desktop 详情封面与 ChapterRow：[MangaDetailComponents.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailComponents.kt#L137)
- Android Book 封面比例：[MangaCover.kt](../../../app/src/main/java/eu/kanade/presentation/manga/components/MangaCover.kt#L18)
- 默认颜色值与 Desktop 主题入口：[TachiyomiColorScheme.kt](../../../presentation-theme/src/commonMain/kotlin/eu/kanade/presentation/theme/colorscheme/TachiyomiColorScheme.kt#L19)、[DesktopTheme.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/theme/DesktopTheme.kt#L20)
- 同步面板和返回控制：[DesktopSyncPanel.kt](../../../app-desktop/src/main/kotlin/mihon/desktop/sync/DesktopSyncPanel.kt#L31)、[SyncPanelController.kt](../../../data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanelController.kt#L284)
- 同步面板内容和密码焦点：[SyncPanelContent.kt](../../../presentation-sync/src/commonMain/kotlin/mihon/presentation/sync/SyncPanelContent.kt#L672)
