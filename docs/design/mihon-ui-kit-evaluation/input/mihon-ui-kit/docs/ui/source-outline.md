# Mihon 交互规范目录与源码索引

审查日期：2026-09-24。
上游：`mihonapp/mihon`；固定基线：`f52d890e7f8a3c418ddab41f41d4b577bce0dc06`，提交时间 2026-09-22。

本文件提供需要整理为规范的类别和提炼要点，用于 fork 的 Agent 开发、设计审查和验收。它不是上游发布的官方设计规范。

## 审查范围与证据边界

本次静态查看了下列源码文件的完整内容或关键片段，覆盖主导航、书库、更新、历史、作品详情、分类、设置、源浏览、扩展、下载队列、阅读器工具栏、追踪面板及备份/存储入口。
没有构建、安装或逐屏操作 APK；动画观感、手势手感、读屏体验、设备适配、进程重建恢复和完整外部服务流程未验证。目录检索只用于定位文件，不计作已审查实现。

“应总结的条目”是建议编写的规范内容；“已确认的原版依据”描述已读实现。具体强制规则应选定适用范围与例外，不能把原版每一处局部实现都自动提升为全局规则。

## 规范目录：6 组、32 类

### 一、页面结构与导航

#### UI-01　页面类型与容器职责

**应总结的条目：** 顶层页面、普通子页面、嵌套标签内容、设置页、详情页、全屏页面和弹层如何分类；明确标题栏、导航栏、内容间距和 Snackbar 的提供方，禁止遗漏或重复。

**已确认的原版依据：** TabbedScreen 在父级构造顶栏，子标签接收内容间距和 SnackbarHostState。普通分类管理页自行提供 AppBar。 [S20][S20] [S05][S05]

#### UI-02　顶层导航与标签切换

**应总结的条目：** 底部导航/侧栏的适用条件；标签排序、角标与选中状态；重选当前标签的动作；嵌套标签切换时搜索、操作按钮和内容状态的归属。

**已确认的原版依据：** HomeScreen 定义书库、更新、历史、浏览、更多五个 Tab，平板使用侧栏；书库重选请求打开设置面板。 [S01][S01] [S03][S03] [S20][S20]

#### UI-03　顶栏标题与操作区

**应总结的条目：** 标题与副标题来源、长文本处理、返回图标条件、右侧主要/溢出操作；普通、搜索、多选及滚动详情状态分别定义。

**已确认的原版依据：** AppBar 的 navigateUp 可以为空；多选时用取消图标和计数替代常规导航与标题。详情页标题随滚动显现。 [S02][S02] [S04][S04]

#### UI-04　返回、关闭与取消优先级

**应总结的条目：** 分别定义系统返回、左上角按钮、关闭图标、遮罩点击和滑动关闭；明确是否退出搜索、多选、弹层内部步骤或当前页面。不能将所有入口绑定为统一 pop。

**已确认的原版依据：** LibraryTab 在多选或搜索状态下拦截返回，先处理选择状态；HomeScreen 在非书库根 Tab 返回书库。 [S03][S03] [S01][S01] [S02][S02]

#### UI-05　安全区、内容间距与滚动容器

**应总结的条目：** 必须消费父级 contentPadding；记录系统栏、键盘和 FAB 的避让方式；明确 nestedScroll、快滚和顶部/底部留白责任。

**已确认的原版依据：** 项目 Scaffold 连接顶栏 nestedScroll，并根据 FAB 实际高度增加底部留白。 [S15][S15] [S04][S04] [S20][S20]

#### UI-06　状态保留与跨页回跳

**应总结的条目：** 定义离开/回来后应保留的 Tab、分类、查询、筛选、滚动位置、草稿；明确普通重组、配置变化、进程重建的保障范围；规定从外部入口打开后的返回目标。

**已确认的原版依据：** HomeScreen 使用 Tab 可保存状态，并处理打开指定 Tab、作品详情或下载队列的事件。该实现不能证明所有业务状态都支持进程重建。 [S01][S01] [S03][S03]


### 二、内容操作与浏览

#### UI-07　主要操作与条件可见性

**应总结的条目：** 定义页面主操作、次要操作和位置；规定显示、隐藏、禁用及替换的条件；多选、空列表、离线和缺失能力时重新核对。

**已确认的原版依据：** 详情页继续阅读 FAB 仅在存在未读章节且未进入选择状态时出现；更新页批量操作根据选中章节状态决定是否提供。 [S04][S04] [S10][S10]

#### UI-08　列表、网格与条目点击区域

**应总结的条目：** 复用条目组件；明确封面、标题、整行、尾部按钮和继续阅读按钮各自的动作；规定点击区域嵌套和不同展示模式中的行为一致性。

**已确认的原版依据：** 作品网格提供独立的点击、长按和继续阅读回调；历史页区分封面点击与继续阅读。 [S21][S21] [S11][S11]

#### UI-09　多选、范围选择与批量操作

**应总结的条目：** 定义进入、切换、全选、反选、范围选择及退出；选中计数和底部操作联动；混合状态操作的作用对象；操作完成后是否清空选择。

**已确认的原版依据：** 书库具备选择/范围选择回调、全选与反选，选择模式影响首页导航；更新页按选中集合的状态提供批量动作。 [S03][S03] [S10][S10] [S02][S02]

#### UI-10　滑动、拖拽与触觉反馈

**应总结的条目：** 记录横滑动作的方向与用户配置、禁用条件、误触防护；排序手柄、拖拽反馈和保存时机；触觉反馈的触发位置。

**已确认的原版依据：** 分类管理页支持拖拽排序；详情页接收章节左右滑动动作配置；书库范围选择触发长按触觉反馈。 [S05][S05] [S04][S04] [S03][S03]

#### UI-11　搜索模式与键盘行为

**应总结的条目：** 区分未搜索、搜索框为空、有查询三个状态；进入、清空、提交、关闭、键盘焦点和返回；逐字过滤与显式提交分别记录；局部与全局搜索入口分清。

**已确认的原版依据：** SearchToolbar 用 null 表示普通顶栏，非 null 表示搜索模式；清空按钮保留搜索并请求焦点，搜索提交会隐藏键盘。 [S02][S02] [S03][S03] [S11][S11]

#### UI-12　筛选、排序与显示偏好

**应总结的条目：** 定义三态筛选含义和切换顺序、活动筛选提示、重置；排序方向与随机排序特例；即时生效/确认生效；全局或分类作用域；网格模式、列数和角标偏好。

**已确认的原版依据：** 书库设置分筛选、排序、显示三页；仅下载模式强制并禁用下载筛选；随机排序使用独立处理；列数区分横竖屏。 [S08][S08]


### 三、弹层、设置与输入

#### UI-13　菜单、溢出操作与嵌套菜单

**应总结的条目：** 定义哪些操作显示图标，哪些进入更多菜单；菜单层级、锚定位置、当前项状态；选择后的关闭及不支持操作的处理。

**已确认的原版依据：** AppBarActions 区分直接图标动作和 OverflowAction；下载队列排序使用嵌套菜单。 [S02][S02] [S13][S13]

#### UI-14　对话框、自适应弹层与内部导航

**应总结的条目：** 定义 AlertDialog、自适应 Sheet、带标签弹层的使用场景；标题、确认/取消；遮罩、系统返回、手势关闭和未保存数据；弹层内部导航的返回责任。

**已确认的原版依据：** AdaptiveSheet 在小屏靠底部、大屏居中，并提供内部 Navigator 包装；书库设置为带标签的弹层内容。 [S12][S12] [S08][S08]

#### UI-15　设置项类型与生效时机

**应总结的条目：** 统一分组、说明、当前值摘要和控件类型；开关、单选、多选、滑块、输入、说明项分别规定点击与保存；记录依赖项禁用、默认值和配置作用域。

**已确认的原版依据：** Preference 定义多种设置项和 enabled/onValueChanged；PreferenceScaffold 统一顶栏和内容布局。具体保存时机仍应核对对应控件实现。 [S07][S07] [S06][S06]

#### UI-16　输入表单、校验与焦点

**应总结的条目：** 规定预填值、必填/重复/范围校验、行内错误、确认按钮条件、键盘动作；取消不写入；不同提交入口执行同一验证；明确草稿是否保存。

**已确认的原版依据：** 分类创建/重命名使用 OutlinedTextField、supportingText 和焦点请求；按钮条件在具体对话框内处理。新增规范应独立审查校验逻辑，不能将现有条件全部照抄。 [S19][S19]

#### UI-17　破坏性操作与作用范围

**应总结的条目：** 逐项列出需要确认的操作及影响范围；说明删除条目、下载文件、历史、绑定关系的区别；确认前展示后果；存在撤销能力时明确边界。

**已确认的原版依据：** 书库移除对话框让用户选择移除条目和/或下载内容，并要求至少选一项；下载队列的全部取消直接调用清空操作。不能推导全应用统一确认规则。 [S14][S14] [S13][S13]


### 四、异步状态与反馈

#### UI-18　加载、空数据、无结果与错误

**应总结的条目：** 分别规定初次加载、真正空数据、搜索无结果、筛选无结果、依赖缺失和网络错误；保留页面导航；给出适用恢复操作，避免所有状态复用同一提示。

**已确认的原版依据：** 源浏览在无内容时显示 LoadingScreen 或整页提示；缺失源仍提供标题和返回；历史页区分空历史与无搜索结果。 [S09][S09] [S11][S11]

#### UI-19　刷新、分页与长任务

**应总结的条目：** 区分初始加载、已有内容刷新、分页加载和后台任务启动；明确指示器含义、重复触发防护、选择模式冲突、进度与完成状态。

**已确认的原版依据：** 更新页下拉刷新启动长任务后只短暂显示刷新指示器；源浏览可在已有结果时呈现后续错误。刷新指示器消失不能直接代表任务完成。 [S10][S10] [S09][S09]

#### UI-20　消息反馈、重试与恢复入口

**应总结的条目：** 定义 Snackbar、Toast、横幅、整页错误和确认框的适用范围；文案、时长、重试动作与任务结果；失败时是否保留已有结果和用户输入。

**已确认的原版依据：** 源浏览在已有结果且出错时通过可重试 Snackbar 提示；存储目录/文件选择的部分错误使用 Toast。统一组件选择规则需要显式批准。 [S09][S09] [S22][S22]

#### UI-21　全局模式与状态横幅

**应总结的条目：** 记录仅下载、隐私模式、索引中等状态的显示位置与顺序；规定对筛选、网络请求及数据记录的影响，避免只显示横幅而遗漏业务约束。

**已确认的原版依据：** AppStateBanners 管理仅下载、隐私和索引提示及系统栏间距；书库筛选会被仅下载模式强制。具体数据记录策略还应沿业务逻辑验证。 [S17][S17] [S08][S08]


### 五、视觉、适配与可访问性

#### UI-22　视觉基础与组件来源

**应总结的条目：** 建立颜色角色、字体层级、间距、圆角、图标和控件来源清单；优先引用现有主题/组件；数值以核对后的项目实现为准，禁止凭经验杜撰。

**已确认的原版依据：** 已查组件普遍使用 MaterialTheme、项目 padding 与 MaterialSymbols 图标，但也存在局部数值；需要提炼认可的共享约定。 [S02][S02] [S05][S05] [S07][S07] [S18][S18]

#### UI-23　封面、文本、标签、角标与日期

**应总结的条目：** 定义封面裁切和失败占位、标题截断、摘要展开、标签操作；角标语义、数量和时间格式；缺失元数据的表示方式。

**已确认的原版依据：** 顶栏标题单行省略；作品网格有下载/未读/语言角标；历史以日期分组；详情页使用可展开简介与标签回调。 [S02][S02] [S21][S21] [S11][S11] [S04][S04]

#### UI-24　动效与滚动联动

**应总结的条目：** 定义页面/Tab 过渡、列表增删/重排、顶栏背景、FAB 展开收起、弹层与阅读器工具栏动画；优先复用既有实现；动画开关和设备表现需要实测。

**已确认的原版依据：** HomeScreen 使用 Tab 淡出淡入过渡；详情页标题透明度跟随滚动；分类列表使用 animateItem；阅读器工具栏采用滑入/淡入。 [S01][S01] [S04][S04] [S05][S05] [S18][S18]

#### UI-25　屏幕尺寸、方向与系统环境

**应总结的条目：** 规定手机/平板布局、横竖屏和多窗口行为；系统栏、键盘、大字体、RTL；记录断点和内容最大宽度的唯一来源，避免每页自行选择。

**已确认的原版依据：** 主页切换底栏/侧栏；详情页分别调用大小屏实现；AdaptiveSheet 有大小屏位置规则；书库列数按方向选择偏好。环境适配结果尚未实机验证。 [S01][S01] [S04][S04] [S12][S12] [S08][S08]

#### UI-26　无障碍与本地化

**应总结的条目：** 规定可交互元素的语义标签、选中/禁用状态、读屏顺序与触控目标；图标提示和 RTL 镜像；文案资源化、复数和动态文本。

**已确认的原版依据：** AppBarActions 有 Tooltip 与 contentDescription，UpIcon 使用 AutoMirrored 图标，首页角标有复数描述；这不构成完整无障碍合规证明。 [S02][S02] [S01][S01]


### 六、业务专用交互

#### UI-27　作品详情与章节操作

**应总结的条目：** 固定详情信息、加入书库、继续阅读、分类、标签搜索、追踪、来源网页的动作映射；章节阅读/下载/书签、多选、过滤和自定义滑动的交互；更新/历史条目的快捷入口保持语义一致。

**已确认的原版依据：** 详情页具有滚动顶栏、开始/继续 FAB、章节列表与选择模式；历史和更新页分别提供打开作品/章节的入口。 [S04][S04] [S10][S10] [S11][S11]

#### UI-28　阅读器专用规则

**应总结的条目：** 工具栏显示/隐藏；阅读方向、点击区域、滑动、缩放、音量键；页码/章节跳转边界；长按页面操作；当前作品设置与全局默认；全屏、方向和系统栏。

**已确认的原版依据：** ReaderAppBars 独立管理工具栏可见性、水平/垂直章节导航和边界按钮；手势与阅读模式覆盖范围由官方阅读器说明补充，本次未逐项运行。 [S18][S18]

阅读器手势与偏好说明另见：[官方 Reader settings](https://mihon.app/docs/guides/reader-settings)。

#### UI-29　下载队列与后台任务控制

**应总结的条目：** 统一等待/运行/暂停/失败/完成的显示与允许操作；开始、暂停、恢复、取消和清空的范围；排序/拖拽；切页后任务与进度呈现；避免把启动当成功。

**已确认的原版依据：** 下载队列以 FAB 切换暂停/恢复，空队列隐藏相关动作，提供排序/清空和拖拽入口，并观察下载状态与进度。 [S13][S13]

#### UI-30　内容源、扩展、安全提示与外部跳转

**应总结的条目：** 源缺失/不可用时的入口和恢复；扩展安装、更新、取消、未受信任/加载失败；系统安装权限；应用内网页、外部浏览器、分享等动作与返回。

**已确认的原版依据：** 扩展页按 Available/Loaded/NotLoaded 分派操作，提供安装权限横幅；未受信任进入信任对话框，其余未加载原因走独立提示。 [S16][S16] [S09][S09] [S04][S04]

#### UI-31　追踪与外部服务绑定

**应总结的条目：** 定义未绑定、已绑定及重新匹配；服务能力不同导致的字段差异；状态/章节/评分/日期编辑；解绑影响范围、隐私标记和服务网页跳转。登录及同步失败需补充对应入口的运行验证。

**已确认的原版依据：** TrackInfoDialogHome 根据服务是否支持评分、阅读日期和私密追踪提供不同字段，并区分未绑定展示；完整登录流程未在本次逐项审查。 [S23][S23]

#### UI-32　存储、备份与导入导出

**应总结的条目：** 记录目录选择、权限获取/失效、取消选择、无效路径、文件检查、数据范围选择、后台任务互斥和结果反馈；为新导入或迁移流程明确步骤、取消边界和恢复策略。

**已确认的原版依据：** SettingsDataScreen 使用系统目录/文件选择器，处理 URI 权限和显示路径；恢复已有任务运行时提示不可重复启动。本次未审查备份恢复内部的全部步骤和源迁移流程。 [S22][S22]

## 针对“新增页面漏顶栏”的最低规则组合

优先形成 UI-01、UI-03、UI-04、UI-05、UI-18 的可执行规范：页面类型、顶栏、返回、内容间距、数据状态。

每个新增页面的设计记录至少填写以下字段：

```yaml
pageType: 普通子页面
containerOwner: 当前页面
referenceImplementation: CategoryScreen.kt
appBar:
  title: 使用项目文案资源
  navigation: 返回上一级；存在局部模式时按规定先退出局部模式
content:
  padding: 消费项目 Scaffold 提供的 contentPadding
  states: [加载, 空数据, 错误, 正常]
exceptions: []
```

以上是设计记录示例，不是 Mihon 已存在的配置文件或 API。页面属于嵌套标签内容、全屏阅读器或自适应弹层时，需要选择对应规则，不能机械套用普通子页面结构。

## 单条规范的建议结构

每条规范记录：规则 ID、适用页面/状态、触发动作、UI 变化、导航结果、数据副作用、复用组件与参考路径、允许例外、可执行验收步骤、证据类型与基线 commit。

验收至少区分静态审查、交互测试、视觉核对；没有执行的项目标为“未验证”。将“观察到的上游行为”“fork 明确决定的规则”“尚待确认的问题”分栏记录。

## 不应直接写成全局规则的结论

| 不宜采用的笼统规则 | 本次源码显示的例外 |
| --- | --- |
| 每个页面必须始终显示标题与返回箭头 | 顶层导航、搜索、多选、滚动详情和阅读器各有规则。[S01][S01] [S02][S02] [S04][S04] [S18][S18] |
| 点击左上角始终 pop 页面 | 搜索关闭、选择取消和根 Tab 返回需要分别处理。[S02][S02] [S03][S03] [S01][S01] |
| 每个内容 Composable 都自己创建 Scaffold | TabbedScreen 已经在父级提供页面结构。[S20][S20] |
| 所有删除或取消操作均有确认框 | 书库移除有范围确认；下载队列全部取消直接执行。[S14][S14] [S13][S13] |
| 所有错误统一替换成全屏错误 | 源浏览已有结果时可使用重试 Snackbar。[S09][S09] |
| 所有弹层在任何屏幕都可以向下滑动关闭 | 自适应弹层的大屏呈现有独立位置与手势规则。[S12][S12] |
| 所有用户修改都需要保存按钮 | 设置和筛选有直接变更接口；表单及选择对话框另行定义提交行为。[S07][S07] [S08][S08] [S19][S19] |

## 源码索引

所有源码链接固定到本次 commit；行范围表示实际读取范围，超出文件末尾的请求按实际返回内容审查。

| 来源 | 文件路径 | 本次读取范围与用途 |
| --- | --- | --- |
| [S01][S01] | `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt` | 完整文件；顶层导航、Tab 重选、返回书库与状态保存 |
| [S02][S02] | `app/src/main/java/eu/kanade/presentation/components/AppBar.kt` | 完整文件；普通、搜索、多选顶栏与菜单 |
| [S03][S03] | `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt` | 65–390 行范围；页面容器、选择/搜索退出、批量操作与刷新 |
| [S04][S04] | `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt` | 100–440 行范围；详情页大小屏分流、滚动标题、FAB 与选择状态 |
| [S05][S05] | `app/src/main/java/eu/kanade/presentation/category/CategoryScreen.kt` | 完整文件；普通子页面、空状态、拖拽排序 |
| [S06][S06] | `app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScaffold.kt` | 完整文件；设置页面统一容器 |
| [S07][S07] | `app/src/main/java/eu/kanade/presentation/more/settings/Preference.kt` | 完整文件；设置项类型、摘要、禁用及值变更接口 |
| [S08][S08] | `app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt` | 45–280 行范围；筛选/排序/显示、下载模式约束 |
| [S09][S09] | `app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt` | 60–300 行范围；无结果、加载、保留结果时的错误、缺失源 |
| [S10][S10] | `app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt` | 50–300 行范围；批量动作条件与长任务刷新提示 |
| [S11][S11] | `app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt` | 35–230 行范围；搜索空态、日期分组、封面与继续阅读入口 |
| [S12][S12] | `app/src/main/java/eu/kanade/presentation/components/AdaptiveSheet.kt` | 完整文件；手机/平板弹层容器及内部导航入口 |
| [S13][S13] | `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt` | 70–330 行范围；队列菜单、暂停/恢复、拖拽与内容间距 |
| [S14][S14] | `app/src/main/java/eu/kanade/presentation/library/DeleteLibraryMangaDialog.kt` | 完整文件；移除范围选择、确认按钮可用条件 |
| [S15][S15] | `presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Scaffold.kt` | 完整文件；nestedScroll 与 FAB 底部留白 |
| [S16][S16] | `app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt` | 50–280 行范围；安装权限、可用/加载/未加载状态与信任确认 |
| [S17][S17] | `app/src/main/java/eu/kanade/presentation/components/Banners.kt` | 1–225 行范围；下载限定、隐私模式、索引状态横幅 |
| [S18][S18] | `app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt` | 40–270 行范围；阅读器工具栏、页码导航、阅读模式与动画 |
| [S19][S19] | `app/src/main/java/eu/kanade/presentation/category/components/CategoryDialogs.kt` | 30–220 行范围；创建/重命名/删除表单及分类选择入口 |
| [S20][S20] | `app/src/main/java/eu/kanade/presentation/components/TabbedScreen.kt` | 完整文件；父级顶栏、Tab 操作与子内容的间距责任 |
| [S21][S21] | `app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt` | 完整文件；作品网格、角标、单击/长按/继续阅读入口 |
| [S22][S22] | `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDataScreen.kt` | 55–260 行范围；目录选择、权限、备份恢复入口与任务互斥 |
| [S23][S23] | `app/src/main/java/eu/kanade/presentation/track/TrackInfoDialogHome.kt` | 55–270 行范围；绑定状态、服务能力与可编辑字段 |

[S01]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt
[S02]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/components/AppBar.kt
[S03]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt
[S04]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt
[S05]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/category/CategoryScreen.kt
[S06]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScaffold.kt
[S07]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/more/settings/Preference.kt
[S08]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt
[S09]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt
[S10]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt
[S11]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt
[S12]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/components/AdaptiveSheet.kt
[S13]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt
[S14]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/library/DeleteLibraryMangaDialog.kt
[S15]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Scaffold.kt
[S16]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt
[S17]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/components/Banners.kt
[S18]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt
[S19]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/category/components/CategoryDialogs.kt
[S20]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/components/TabbedScreen.kt
[S21]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt
[S22]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDataScreen.kt
[S23]: https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/track/TrackInfoDialogHome.kt
