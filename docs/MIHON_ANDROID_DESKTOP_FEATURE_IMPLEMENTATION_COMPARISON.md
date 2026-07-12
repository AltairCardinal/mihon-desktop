# Mihon Android 与 Mihon Desktop 功能实现方式差异清单

> 核验日期：2026-07-12
> 核验口径：当前工作树中的 `app/`（原版 Android）与 `app-desktop/`（Desktop），并追踪两端共同使用的 `domain/`、`data/`、`source-api/`、`source-local/`。
> 注意：当前工作树含尚未提交的 Desktop 扩展兼容改动；相关结论描述当前代码，不代表某个已发布版本。

## 评分规则

- **原版更优**：功能覆盖、成熟度、可靠性、安全性、可维护性或用户反馈链路明显更完整。
- **Mihon Desktop 更优**：更适合桌面输入/大屏/文件系统，或提供原版没有的能力。
- **不相上下**：核心用户能力等价，差异主要是合理的平台适配；不表示代码完全相同。
- 对“仅一端存在”的功能，默认存在的一端胜出；纯平台专属能力会结合该平台是否真正需要来判断。

## 一、整体架构与平台基础

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 1 | UI 框架 | Android Jetpack Compose，并混用 Activity、Android View 阅读器组件 | Compose Multiplatform Desktop，窗口内基本为纯 Compose | Desktop UI 更统一；原版可直接利用 Android 生命周期、系统组件和成熟 View 生态 | 不相上下 |
| 2 | 导航 | Voyager：顶层 `HomeScreen`、Tab 与嵌套 Screen；支持 Android Intent/deep link 入口 | Voyager：顶层 `Navigator` + `TabNavigator`；显式区分 Tab 与普通 Screen，并接入测试导航控制器 | 页面内导航模型接近；原版外部入口更丰富，Desktop 的导航可测试钩子更直接 | 不相上下 |
| 3 | 状态管理 | 广泛使用 `StateScreenModel`/`ScreenModel`、StateFlow、事件流和生命周期 | 新页面已使用 ScreenModel/StateFlow，但仍有部分状态直接保存在 Composable 或全局对象 | 原版分层和生命周期治理更成熟，Desktop 的状态组织尚未完全一致 | 原版更优 |
| 4 | 依赖注入 | 按 App/Domain/Data 等模块拆分 Injekt 注册 | 主要集中在 `DesktopAppModule`，再通过 `DesktopUiDependencies` 向 Compose 提供 | 原版边界更清晰；Desktop wiring 集中、易启动但规模增长后维护成本更高 | 原版更优 |
| 5 | 领域与数据复用 | Android 使用完整 `domain`/`data` 链路 | Desktop 直接复用 commonMain 领域模型、用例、仓库和 SQLDelight schema，平台缺口才自建实现 | 业务语义和数据库结构总体共享；Desktop 部分能力仍在 app 模块重复实现 | 不相上下 |
| 6 | 数据库 | SQLDelight + Android SQLite 驱动，受 Android 生命周期和备份策略管理 | SQLDelight + sqlite-jdbc/JVM driver，数据库位于桌面应用数据目录 | 查询和模型大量共享，差异主要是驱动与文件位置 | 不相上下 |
| 7 | 偏好存储 | 项目 `PreferenceStore` 抽象之上的 Android DataStore/SharedPreferences | 部分复用 PreferenceStore，阅读器等 Desktop 特有配置直接用 `java.util.prefs` | 原版存储抽象更统一、便于迁移和测试；Desktop 存在两套偏好路径 | 原版更优 |
| 8 | 网络栈 | OkHttp、Android 网络状态、WebView Cookie 桥、Cloudflare 拦截和系统代理能力 | OkHttp + `DesktopNetworkHelper`、DesktopCookieJar、自建 CF challenge/FlareSolverr 客户端 | Desktop 对无 WebView 环境提供了务实补偿，但兼容范围和成熟度不及原版 | 原版更优 |
| 9 | 图片加载与解码 | Coil 3、自定义 fetcher/decoder、SubsamplingScaleImageView/区域解码 | Coil/Compose 图片路径 + Skia 原生解码、预加载器、桌面缓存 | 原版超大图分块和移动端内存治理更成熟；Desktop Skia 解码与桌面预加载路径更直接 | 原版更优 |
| 10 | 后台任务 | WorkManager/Job、充电与网络约束、系统重启后的任务恢复 | 协程轮询/应用内 scheduler；应用退出后通常不运行 | 原版具备 OS 级持久调度和约束表达 | 原版更优 |
| 11 | 通知 | Android notification channel，下载、更新、备份等可在后台持续反馈 | `DesktopNotificationService` 主要转成应用内 Snackbar | 原版在应用后台或关闭界面后仍可见；Desktop 反馈依赖窗口存活 | 原版更优 |
| 12 | 崩溃与诊断 | Android crash activity、日志、系统信息和可选遥测链路 | 全局 `CrashHandler`、桌面日志与测试制品路径 | 两端都可捕获异常；原版诊断页面和生态整合更完整 | 原版更优 |
| 13 | 自动化测试入口 | 单元/集成测试、部分 Android UI 测试，依赖模拟器或设备 | 内建 `--test-mode`、HTTP 状态/动作 API、截图服务和 headless 启动 | Desktop 可由外部 Robot 稳定驱动完整窗口流程，测试可观测性更强 | Mihon Desktop 更优 |

## 二、主导航、书库与漫画详情

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 14 | 主导航 | 书库、更新、历史、浏览、更多 5 个主 Tab | 在上述 5 个 Tab 外新增“作者”Tab | Desktop 多一个跨作品作者入口；其余主信息架构基本对齐 | Mihon Desktop 更优 |
| 15 | 书库展示 | 网格/舒适网格/列表、封面与徽标、多列自适应 | 网格/列表等桌面布局，列数和大屏密度可配置 | 原版移动端手势和展示选项更成熟；Desktop 大屏信息密度更高 | 不相上下 |
| 16 | 分类 | 独立分类管理页，支持增删改、排序；书库按分类分页 | `CategoryManagementDialog` 与批量分类操作 | 原版管理流程和排序能力更完整；Desktop 对桌面批量操作更紧凑 | 原版更优 |
| 17 | 书库筛选 | 已读/未读、收藏状态、下载、追踪、完成状态等组合筛选 | 支持常用状态、下载和分类筛选，但筛选维度少于原版 | 原版覆盖更多业务属性和组合场景 | 原版更优 |
| 18 | 书库搜索 | 标题、作者等本地过滤，结合分类和显示模式 | `LibrarySearchFilter` 对桌面书库状态做即时过滤 | 核心体验等价，均为本地响应式过滤 | 不相上下 |
| 19 | 书库多选批处理 | 多选后分类、下载、标记已读/未读、删除等完整 action mode | `LibrarySelectionState` + 批量分类等桌面操作 | Desktop 已有核心批量能力，但动作覆盖仍少于原版 | 原版更优 |
| 20 | 随机漫画 | 书库菜单按当前可见集合随机打开 | `RandomMangaLogic` 基于当前过滤结果选择 | 两端行为目标相同 | 不相上下 |
| 21 | 漫画详情展示 | Header、描述、标签、作者、状态、源、封面和章节列表 | Compose Desktop 详情页实现同类字段，并适配宽屏 | 核心信息基本对齐；原版的折叠/移动端交互更成熟，Desktop 横向空间利用更好 | 不相上下 |
| 22 | 收藏与分类联动 | 收藏按钮、首次加入分类、移出书库后的相关清理策略 | `AddMangaToLibrary` + 分类对话框与详情页状态 | 两端都有可用入口；原版边界选项更完整 | 原版更优 |
| 23 | 章节筛选和排序 | 按编号、上传时间、来源顺序；过滤已读、书签、下载；Scanlator 过滤 | `MangaChapterSort`、筛选、多选与 excluded scanlator 用例 | 当前核心能力接近，并共享部分领域逻辑 | 不相上下 |
| 24 | 章节批量操作 | 下载、删除下载、书签、已读状态、前后章节范围选择等 | `ChapterSelectionState/Actions` 提供下载、状态和选择操作 | 原版范围选择和动作细节更丰富 | 原版更优 |
| 25 | 漫画备注 | `MangaNotesScreen`，为单部漫画保存本地备注 | `DesktopMangaNotesScreen` 提供同类入口 | 两端均有用户可见入口和持久化 | 不相上下 |
| 26 | 封面管理 | 查看、保存、分享、编辑/自定义封面，并接入 Coil 封面缓存 | `DesktopMangaCoverManager` 与封面请求，主要面向桌面文件选择/缓存 | 原版动作和分享链路更完整 | 原版更优 |
| 27 | 重复漫画提示 | 添加/迁移时结合标题、源等识别可能重复项 | `DuplicateMangaLogic` 在浏览链路显式检查重复 | 两端都能降低重复收藏风险，实现入口不同 | 不相上下 |

## 三、浏览、源与扩展

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 28 | 源列表 | 已启用源、语言分组、固定源、源筛选与源设置 | `BrowseTab`/`DesktopSourceManager` 提供源列表、启停和偏好入口 | 原版管理维度、排序和语言过滤更成熟 | 原版更优 |
| 29 | 单源浏览 | 分页浏览 popular/latest/search，动态 FilterList，稳定 paging | `SourceBrowseScreen` 调扩展源并处理过滤和分页 | 两端遵循同一 source-api；原版 paging、错误恢复和长期兼容更成熟 | 原版更优 |
| 30 | 全局搜索 | 多源并发搜索、固定源/过滤、结果分组和重试 | `GlobalSearchScreen` 多源并行，并提供 Desktop 搜索状态 | 核心能力接近；原版配置和异常处理更成熟 | 原版更优 |
| 31 | 本地源 | SAF/本地目录，支持目录、CBZ/ZIP、EPUB、metadata、封面与增量刷新 | `LocalSourceReader`/`LocalSourceScanService` + 文件监听，提供本地浏览和设置页 | 原版格式与 metadata 生态更成熟；Desktop 文件监听和直接文件系统访问更自然 | 不相上下 |
| 32 | 扩展仓库 | 使用扩展 repo index，支持自定义仓库、验证与更新 | 复用 extension repo domain，并提供独立仓库管理页 | 核心仓库模型接近；当前 Desktop 正在补齐仓库兼容细节 | 原版更优 |
| 33 | 扩展发现 | Android 扩展 APK index + 系统已安装包扫描 | 拉取 JAR/APK 元数据，在桌面目录扫描可加载产物 | 原版与上游扩展发布格式天然一致；Desktop 需要额外转换和元数据维护 | 原版更优 |
| 34 | 扩展安装 | PackageInstaller 或 Shizuku，系统级 APK 安装/卸载和签名检查 | 下载预编译 JAR，或 APK→DEX→JAR 转换后放入应用目录 | Desktop 无需系统安装确认，但转换兼容面和隔离性弱于原版 | 原版更优 |
| 35 | 扩展加载 | PackageManager/DexClassLoader，Android API 原生可用 | URLClassLoader + Android/AndroidX stub + 字节码修补 | Desktop 能复用大量 Android 扩展很有价值，但实现复杂且无法保证所有 API 行为 | 原版更优 |
| 36 | 扩展安全 | 校验签名、可信签名链，扩展由 Android 包隔离机制管理 | 主要依赖仓库信任、产物校验与 ClassLoader；仍与主应用同 JVM | 原版的签名和平台隔离明显更强 | 原版更优 |
| 37 | 扩展详情与更新 | 完整详情、权限/来源信息、更新、卸载、信任处理 | 当前工作树新增详情、图标、版本元数据、更新替换与搜索 | Desktop 已接近基本管理体验，但可信度和边缘状态仍不及原版 | 原版更优 |
| 38 | 源偏好设置 | 原生 AndroidX Preference/Compose 桥，扩展定义的设置可直接展示 | 通过 JVM PreferenceScreen 和 AndroidX stub 映射扩展偏好 | 常见控件可用；复杂自定义 Android 偏好在 Desktop 可能不兼容 | 原版更优 |
| 39 | WebView/源登录 | 内建 WebView，支持登录、站点调试、Cookie 同步和 CF challenge | 无完整嵌入式 WebView；使用 cookie 手动导入、挑战对话框或 FlareSolverr | 原版交互闭环更自然；Desktop 方案适合自动化但依赖外部服务或手工 Cookie | 原版更优 |
| 40 | Cloudflare 绕过 | WebViewChallenge 与 OkHttp Cookie 同步，用户可直接完成网页挑战 | `CloudflareChallengeManager` 弹窗收集 cookie，并可调用 FlareSolverr | Desktop 提供多路径补偿，但成功率和易用性通常低于原版 WebView | 原版更优 |

## 四、阅读器

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 41 | 阅读模式 | LTR、RTL、竖向分页、Webtoon、连续竖向 | LTR、RTL、Webtoon，并提供单页/双页桌面查看 | 原版模式枚举更丰富；Desktop 的双页是大屏核心路径 | 不相上下 |
| 42 | 双页/跨页 | 已有双页 Viewer、页配对算法、RTL 双页和旋转/拆分配置 | `DualPagePagerViewer`、自动拆宽页、spread edge matching | 原版配对规则和边界处理更成熟；Desktop 有自动边缘匹配这一桌面增强 | 不相上下 |
| 43 | 宽页拆分 | 分页和 Webtoon 均可拆分/反转，支持旋转适配 | `VirtualPageList` 在分页双页模式拆分宽页 | 原版覆盖 Webtoon、反转和旋转组合更多 | 原版更优 |
| 44 | 大图与缩放 | Subsampling image view，按区域解码超大图，成熟手势缩放 | Compose 缩放状态 + Skia 全图解码/裁切 | 原版对极大扫描图的内存峰值与区域缩放更稳健 | 原版更优 |
| 45 | 图片预加载 | Loader/holder 围绕当前页预取、缓存与取消 | `PagePreloader` 缓存邻近页并使用 Skia 解码 | 两端都有邻页预取；原版与下载缓存/生命周期结合更深，Desktop 实现更轻 | 原版更优 |
| 46 | Webtoon 滚动 | RecyclerView/Lazy loading、连续章节过渡、侧边距和缩放约束 | Compose LazyColumn、侧边距、自动滚动和速度档位 | Desktop 原生提供自动滚动；原版长章节虚拟化和缩放更成熟 | 不相上下 |
| 47 | 页面过渡 | 章节首尾过渡页、缺失章节提示、加载/错误/重试状态 | 上下章切换和底栏状态，但过渡页信息密度较少 | 原版对缺章、加载和章节边界反馈更完整 | 原版更优 |
| 48 | 阅读导航输入 | 触摸点击区、滑动、长按、音量键、方向反转和导航覆盖层 | 鼠标点击区、滚轮、键盘方向键/Esc、上下文菜单 | 两端均充分适配各自主要输入设备 | 不相上下 |
| 49 | 点击区域方案 | 默认/L/Kindlish/Edge/左右/禁用，多种反转规则 | `TapZone` + 若干 `NavigationMode`，可视化桌面点击区 | 原版预设和反转组合更多 | 原版更优 |
| 50 | 页面显示设置 | 缩放起点、6 种缩放、方向锁定、全屏、刘海、常亮等 | ScaleType、背景、双页、裁边、侧边距等桌面相关设置 | 原版细粒度更多；Desktop 不需要方向/刘海/常亮等移动端项 | 不相上下 |
| 51 | 色彩处理 | 亮度、RGBA、混合模式、灰度、反色 | RGBA/亮度滤镜和背景主题 | 原版多灰度、反色和多种 BlendMode | 原版更优 |
| 52 | 保存与分享页面 | 保存、分享、设封面，并通过 Android 通知和权限反馈 | `PageSaveHelper` + 页面右键菜单保存到桌面文件 | Desktop 保存路径选择更自然；原版分享和设封面链路更完整 | 不相上下 |
| 53 | 章节进度 | 阅读时更新 history、last page、已读状态，并联动 tracker | `ReaderProgressTracker` 更新共享数据库状态 | 核心本地进度一致；Desktop 尚无 tracker 联动 | 原版更优 |
| 54 | 跳过规则 | 跳过已读、过滤掉的章节、重复章节 | Desktop 当前主要支持跳过已读 | 原版规则更完整 | 原版更优 |
| 55 | 每部漫画阅读模式 | viewerFlags 保存漫画级模式/方向 | Desktop 读取并保存漫画级 reading mode，同时保留全局默认 | 核心能力对齐 | 不相上下 |

## 五、下载、更新、历史与统计

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 56 | 下载队列 | `DownloadManager`/`Downloader`/Store/Cache/Job 分层，后台通知与恢复 | `DesktopDownloadManager` + coroutine/Semaphore，窗口内队列页 | 原版持久恢复、状态机、缓存和后台反馈更成熟 | 原版更优 |
| 57 | 下载并发 | 按源和队列调度，结合网络/电量约束及重试 | Semaphore 控制桌面并发数，配置直接 | Desktop 调节简单清晰；原版约束和异常恢复更全面 | 原版更优 |
| 58 | 下载格式 | 目录或 CBZ，支持下载目录结构与缓存迁移 | `CbzCreator`，面向桌面文件系统输出 CBZ | 核心结果接近；Desktop 的文件可直接被其他桌面阅读器使用 | 不相上下 |
| 59 | 自动下载 | 新章节、分类、章节数等规则，WorkManager 调度 | 下载设置中提供自动下载/读后删除等桌面规则 | 原版规则、后台执行和约束更完整 | 原版更优 |
| 60 | 下载位置 | SAF 目录选择，适配 Android 分区存储 | 普通文件系统目录，支持直接打开目录 | Desktop 无 SAF 限制、可直接管理文件 | Mihon Desktop 更优 |
| 61 | 书库更新 | WorkManager 定时、分类/状态/限制、通知、失败汇总 | `LibraryUpdateScheduler/Checker/CategoryFilter` 在应用内调度并反馈 | Desktop 已覆盖核心更新；原版能在后台可靠运行并表达系统约束 | 原版更优 |
| 62 | 更新列表 | 按日期分组、下载状态、多选等完整更新流 | `UpdatesTab` + ScreenModel，按时间展示并提供过滤 | 核心查看能力接近；原版操作和后台联动更成熟 | 原版更优 |
| 63 | 即将更新 | 原版主线没有独立 upcoming 预测页 | `UpcomingScreen` 基于更新间隔预测未来更新 | Desktop 提供额外的计划视图，但预测不保证源实际发布时间 | Mihon Desktop 更优 |
| 64 | 历史记录 | History Tab，搜索、继续阅读、删除单条/全部 | History Tab + `HistoryScreenModel`，查看和继续阅读 | 当前 Desktop 已补齐历史主入口；原版管理动作更完整 | 原版更优 |
| 65 | 隐身模式 | 不写入阅读历史的全局开关，并在 UI 明确提示 | General 设置中提供 incognito mode | 核心用户语义一致 | 不相上下 |
| 66 | 统计 | 书库、阅读、分类、来源、语言、状态等多维统计 | `StatsScreen` 主要统计标题和章节等基础指标 | 原版维度、筛选和可视化更丰富 | 原版更优 |

## 六、迁移、追踪、备份与同步

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 67 | 单部漫画迁移 | 搜索目标源、比较章节、迁移/复制分类与阅读状态 | `MigrationSearchScreen` + `DesktopMigrateMangaUseCase` | Desktop 已有完整可见入口；原版选项和异常路径更成熟 | 原版更优 |
| 68 | 批量迁移 | 按源选择多部漫画、逐项目标搜索和批量流程 | 有迁移源/漫画页面，但批量编排能力较轻 | 原版的大规模迁移流程更完整 | 原版更优 |
| 69 | 追踪服务 | MAL、AniList、Kitsu、Bangumi、Shikimori、MangaUpdates、Komga、Kavita、Suwayomi 等 | 没有对应 Desktop UI/认证与同步链路，虽可复用部分 domain 模型 | 原版可登录、搜索绑定、更新进度/分数/状态；Desktop 实际不可用 | 原版更优 |
| 70 | 自动追踪更新 | 阅读完成后自动推送章节进度，并可延迟/手动同步 | 无 | 仅原版形成用户闭环 | 原版更优 |
| 71 | 手动备份 | Protobuf `.tachibk`，可选漫画、分类、历史、追踪、偏好、源与扩展仓库 | Desktop 自有 backup models/creator，输出 `.tachibk` 文件 | 两端都有 UI；原版字段覆盖更全，尤其追踪和偏好 | 原版更优 |
| 72 | 备份恢复 | 验证、预览、后台恢复、逐项结果和通知 | `DesktopBackupRestorer`，桌面文件选择后恢复支持的数据 | 原版兼容、错误汇总与部分恢复策略更成熟 | 原版更优 |
| 73 | 自动备份 | WorkManager 定期备份、保留份数、系统约束 | `AutoBackupScheduler` 在 Desktop 运行时定期创建 | 核心目的相同；原版退出应用后仍能由系统调度 | 原版更优 |
| 74 | 跨端备份兼容 | 原版 `.tachibk` 为 protobuf schema | Desktop 使用自有序列化模型，不能默认视为完全互通 | 文件扩展名相同不等于格式/字段完全兼容；跨端迁移存在风险 | 原版更优 |
| 75 | 在线/跨设备同步 | 原版主线无通用云同步，仅 tracker 或第三方服务承担部分同步 | Desktop 同样无通用云同步 | 两端都没有完整书库云同步 | 不相上下 |

## 七、Desktop 独有与 Android 平台独有能力

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 76 | 作者聚合 | 漫画详情可按作者文本搜索，但无独立作者实体工作台 | Authors Tab、作者详情、作品归并/比较与发现服务 | Desktop 将作者与作品关系提升为一等功能，适合整理跨源同作 | Mihon Desktop 更优 |
| 77 | 作品版本比较 | 主要通过迁移搜索人工比较源结果 | `WorkCompareScreen` 与 creator/work matching 服务 | Desktop 可围绕同一作品比较不同来源/版本 | Mihon Desktop 更优 |
| 78 | 键盘快捷键 | Android 主要依赖触摸和音量键，外接键盘不是核心路径 | 阅读器支持方向键、Esc 等桌面快捷操作 | Desktop 对键鼠用户效率更高 | Mihon Desktop 更优 |
| 79 | 文件系统集成 | SAF 分享/打开，需要 Android 权限和 content URI | 可直接选择、打开下载/备份/缓存目录和文件 | Desktop 对本地文件管理更透明 | Mihon Desktop 更优 |
| 80 | 多窗口/窗口尺寸适配 | 以单 Activity 为主，支持 Android 多窗口但不是专门工作流 | 原生桌面窗口和自由缩放，内容针对宽屏布局 | Desktop 更适合大屏和可变窗口 | Mihon Desktop 更优 |
| 81 | Deep link | 支持 `tachiyomi://`、HTTP manga link、外部搜索 Intent 等 | 未见等价 OS 级 URI scheme 注册和路由 | 原版可从浏览器/其他应用直接进入目标页面 | 原版更优 |
| 82 | 分享/系统 Intent | 分享漫画、页面、URL，接收外部内容和搜索 Intent | 主要通过剪贴板、浏览器和文件系统，系统级分享目标较少 | 原版与 Android 应用生态衔接更完整 | 原版更优 |
| 83 | 安全锁 | 生物识别/PIN、离开后锁定、隐私相关设置 | 无对应桌面应用锁页面 | 原版有明确隐私保护；Desktop 依赖操作系统账户/锁屏 | 原版更优 |
| 84 | 屏幕与截图安全 | 可阻止截图、处理安全屏幕和隐私模式 | 未见等价窗口内容保护 | 原版对敏感内容保护更完整 | 原版更优 |
| 85 | 桌面/系统 Widget | Android 可提供更新类 Widget | Desktop 无；桌面平台也没有统一等价机制 | 属于 Android 平台专属，Desktop 缠绕实现价值低 | 原版更优 |
| 86 | 应用更新 | Android 版本检查、下载 APK、通知与安装流程 | Release service + About/版本信息；桌面打包更新链路依平台构建脚本 | 原版应用内更新闭环更成熟；Desktop 仍依赖分发包/脚本 | 原版更优 |
| 87 | 国际化 | Moko resources，覆盖大量语言并贯穿 UI | Desktop 仍有大量硬编码英文字符串 | 原版可用语言和一致性明显更强 | 原版更优 |
| 88 | 无障碍 | Android Compose/View 语义、TalkBack、系统字体与触控规范积累较多 | Compose Desktop 基础语义可用，但键盘焦点和屏幕阅读器覆盖较少 | 原版平台成熟度和现有实现更强 | 原版更优 |
| 89 | 桌面测试控制面 | 无应用内 HTTP 自动化控制服务器 | test mode 暴露导航、动作、状态和截图 API | Desktop 对回归测试、CI 截图和远程控制显著更强；仅测试模式开放 | Mihon Desktop 更优 |

## 八、设置与维护性

| # | 功能/能力 | 原版 Mihon 实现 | Mihon Desktop 实现 | 差异明细 | 评分 |
|---:|---|---|---|---|---|
| 90 | 设置搜索 | `SettingsSearchScreen` 索引可搜索设置 | 未见全局设置搜索 | 原版设置规模大但仍可快速定位 | 原版更优 |
| 91 | 外观 | 主题模式、动态色、主题变体、语言、纯黑、导航栏等 | 主题、颜色和网格等较基础选项 | 原版选项、国际化和系统动态主题更完整 | 原版更优 |
| 92 | 安全设置 | 独立 Security 页面，含认证和隐私选项 | 无独立安全设置 | 原版覆盖用户隐私需求 | 原版更优 |
| 93 | 高级维护 | 清数据库、清缓存/Cookie、WebView、日志、诊断和 worker 信息 | 清 Cookie/网络缓存、目录入口和部分高级网络设置 | 原版工具更多；Desktop 的直接目录访问利于人工排障 | 原版更优 |
| 94 | 开源许可与构建信息 | About + 开源许可列表/单库许可、更新检查 | About 显示版本和构建信息，许可展示较少 | 原版合规信息入口更完整 | 原版更优 |
| 95 | 代码模块边界 | app/domain/data/core/source 等 Gradle 模块形成编译期边界 | Desktop 特有 UI、domain adapter、下载、备份、网络集中于 app-desktop 模块的包级边界 | 原版更利于多人长期维护；Desktop 迭代成本低但边界约束较弱 | 原版更优 |
| 96 | 平台兼容层成本 | 直接运行在目标 Android API 上 | 为 Android 扩展提供大量 `android.*`/`androidx.*` stub 和字节码转换 | Desktop 获得扩展复用收益，但长期需跟踪上游 API 与扩展行为 | 原版更优 |

## 汇总

按本清单 96 项计：

- **原版更优：64 项**
- **Mihon Desktop 更优：10 项**
- **不相上下：22 项**

总体判断：原版 Mihon 在功能完整度、扩展兼容、安全、后台执行、追踪、阅读器边界处理、国际化与长期维护上明显领先；Mihon Desktop 的优势集中在桌面输入、大屏双页、文件系统、作者/作品整理、未来更新预测和可自动化测试性。Desktop 当前已不是“缺少历史和迁移的早期移植版”，而是已补齐核心漫画管理闭环、但仍在追赶外围生态与成熟度的独立桌面实现。

## Desktop 对齐路线图

上述 64 项“原版更优”能力已进入可机器检查的重构路线图。唯一机器数据源为 `app-desktop/src/test/resources/parity/parity-manifest.json`，治理规则见 `docs/desktop-parity/PARITY_TRACKER.md`。所有条目初始均为 `NOT_STARTED`；纳入路线图不表示功能已经对齐，也不改变本报告的原有评分。后续只有在行为刻画、共享实现、UI wiring 与保护测试均满足门槛后才能推进状态。

## 主要代码证据入口

- 原版主 UI：`app/src/main/java/eu/kanade/tachiyomi/ui/`、`app/src/main/java/eu/kanade/presentation/`
- 原版阅读器：`app/src/main/java/eu/kanade/tachiyomi/ui/reader/`
- 原版下载：`app/src/main/java/eu/kanade/tachiyomi/data/download/`
- 原版备份：`app/src/main/java/eu/kanade/tachiyomi/data/backup/`
- 原版扩展：`app/src/main/java/eu/kanade/tachiyomi/extension/`
- 原版追踪：`app/src/main/java/eu/kanade/tachiyomi/data/track/`
- Desktop UI：`app-desktop/src/main/kotlin/mihon/desktop/ui/`
- Desktop 阅读器：`app-desktop/src/main/kotlin/mihon/desktop/reader/`
- Desktop 扩展：`app-desktop/src/main/kotlin/mihon/desktop/extension/` 与 `app-desktop/src/main/kotlin/android/`
- Desktop 下载/备份/网络：`app-desktop/src/main/kotlin/mihon/desktop/{download,backup,network}/`
- 共享业务与数据：`domain/src/commonMain/`、`data/src/commonMain/`、`source-api/src/commonMain/`
