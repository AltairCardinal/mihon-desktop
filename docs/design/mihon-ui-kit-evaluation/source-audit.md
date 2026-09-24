# Mihon UI Kit 源码审计

日期：2026-09-24。审计对象：docs/design/mihon-ui-kit-evaluation/input/mihon-ui-kit/。本报告只评估规则正确性、可追溯性及其指导 Mihon Desktop 的充分程度，不评审安装器，也不判断 Android overlay 是否可编译。

## 结论

这套规范足以作为共享交互语义的检查清单和页面契约起点；单靠它还不足以指导 Desktop 页面落地。其强项是把选择、搜索、筛选、错误恢复和危险操作写成可检查的状态约束，并区分样例验证与生产验证。短板是规则和组件基线以 Android Compose 为主，Desktop 的窗口、键盘、焦点、弹层和组件映射没有形成同等具体的适配契约。

本审计不要求证明 128 条规则全部为真。规范本身包含原版行为抽取、跨页面归纳和新建议；原版某处的实现也不自动成为所有平台都必须沿用的正确行为。下文将来源事实、仓库观察、提案约束及未验证项分别标注，不把提案误报为源码错误。

## 方法与证据边界

本机 HEAD 为 d379849b85f1defb6a375cacc51b665cf21d7737。工作树含其他任务的未提交改动；本报告未读取或修改它们。目标 Desktop 页面文件未处于脏改状态。

上游 commit f52d890e7f8a3c418ddab41f41d4b577bce0dc06 不在本机 Git 对象库；按仓库代理约定，经 127.0.0.1:10808 只读获取了若干 GitHub raw 文件片段，未 clone。fork commit 6fbf6dfca203d99d6dd32137f2df97ced40c81b8 在本机对象库，可读取其中 Android 组件源码。

结构核对结果：manifest 有 32 个类别、每类 4 条规则和 4 个场景；Markdown 与 manifest 的 128 个规则 ID 完全一致且唯一。128 个场景状态均为 NOT_RUN；manifest 顶层 native_build_status 和 device_status 也均为 NOT_RUN。这说明规范没有把未执行的场景写成通过，但也不能由结构完整推导规则正确或体验合格。

本轮只读规则正文、source-outline、组件基线、manifest、主 spec 和少量上游/Desktop 源码。没有运行包内脚本或 installer，没有导入 overlay，没有运行 Gradle、浏览器或原生组件测试。包中预先存在的 verification 报告和日志不作为本轮验收证据。

覆盖表里的“抽样”仅表示核对了列出的部分代码片段或仓库调用点，不表示该类别四条规则、四个场景或完整生产链路已验证。“未验证”表示没有为该类别独立复核源行为；原包的引用和声明仍可供后续追查。

## 32 类覆盖表

| ID | 类别 | 本轮覆盖 |
|---|---|---|
| UI-01 | 页面类型与容器职责 | 抽样：上游 Tab 容器；Desktop 全面行为未核对 |
| UI-02 | 顶层导航与标签切换 | 抽样：上游 TabNavigator；Desktop 根导航未核对 |
| UI-03 | 顶栏标题与操作区 | 抽样：上游 AppBar/SearchToolbar 语义 |
| UI-04 | 返回关闭与取消 | 深查：原版优先级 + Desktop 书架 Escape |
| UI-05 | 安全区与滚动容器 | 未验证；Android inset 不代表 Desktop 窗口边界 |
| UI-06 | 状态保留与回跳 | 未验证进程恢复或 Desktop 页面回栈 |
| UI-07 | 主要操作与可用性 | 未验证 |
| UI-08 | 条目和点击区域 | 未验证完整点击/键盘语义 |
| UI-09 | 选择与批量操作 | 深查：Desktop 稳定 ID、可见集合和批量行为代码 |
| UI-10 | 滑动拖拽与触觉 | 未验证；手势规则须映射 Desktop 输入设备 |
| UI-11 | 搜索与键盘 | 深查：原版查询约定及 Desktop Escape 路径 |
| UI-12 | 筛选排序显示偏好 | 深查：原版三态约定及 Desktop 筛选控件 |
| UI-13 | 菜单与溢出操作 | 未验证 |
| UI-14 | 弹层与内部导航 | 深查：上游 AdaptiveSheet 与 Desktop 同步弹层 |
| UI-15 | 设置项与生效时机 | 深查：Android 组件基线；Desktop 对等控件未核对 |
| UI-16 | 输入校验与焦点 | 未验证生产输入及键盘焦点行为 |
| UI-17 | 破坏性操作 | 深查：作为建议的确认约束及原版差异说明 |
| UI-18 | 加载空态与错误 | 深查：原版保留结果/局部错误语义 |
| UI-19 | 刷新分页与长任务 | 未验证任务生命周期 |
| UI-20 | 反馈重试与恢复 | 抽样：Desktop 同步外链/复制失败反馈代码 |
| UI-21 | 全局模式与横幅 | 未验证 |
| UI-22 | 视觉基础与复用 | 未验证 Desktop 主题、字号及尺寸映射 |
| UI-23 | 封面文本角标日期 | 未验证 |
| UI-24 | 动效与滚动联动 | 未验证 |
| UI-25 | 屏幕与系统适配 | 未验证 Desktop 窗口尺寸、缩放和重排 |
| UI-26 | 无障碍与本地化 | 未验证读屏/键盘实测；规则文本仅作提案评估 |
| UI-27 | 作品与章节 | 抽样：Desktop 章节选择工具栏及可用状态 |
| UI-28 | 阅读器 | 未验证 |
| UI-29 | 下载与后台控制 | 未验证 |
| UI-30 | 源扩展与外部入口 | 未验证 |
| UI-31 | 追踪与外部绑定 | 未验证；不能代替设备同步状态模型 |
| UI-32 | 存储备份与导入导出 | 未验证；章节语义集中在备份而非设备同步 |

## 高风险条款抽查

本节深查 15 条编号规则。编号与结论是对适用性和证据边界的审查；没有对应运行时测试结果。

### UI-04.01、UI-04.02：返回与局部模式优先级

规则位置：[01-navigation.md:93–95](input/mihon-ui-kit/docs/ui/01-navigation.md)。上游 LibraryTab 使用 BackHandler；先清选择，再按搜索状态关闭局部模式：[LibraryTab.kt@f52, L258–265](https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt#L258-L265)。

当前 Desktop 书架在 [LibraryTab.kt:513–529](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt) 映射 Escape：先关移除确认，再清选择，再退出搜索。语义可共享，按键路由是平台实现细节。规则场景只写“系统返回”，不足以指导 Desktop 的 Escape、窗口快捷键、焦点所在控件及弹层截获顺序。最小补充是为每个页面声明平台输入映射，并验证同一按键只消费一层状态。

### UI-09.01、UI-09.03、UI-09.04：集合边界与批量动作

规则位置：[02-content-actions.md:67–70](input/mihon-ui-kit/docs/ui/02-content-actions.md)。Desktop 书架持有稳定 manga ID 集合：[LibrarySelectionState.kt:16–65](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibrarySelectionState.kt)；全选和反选接收 displayedItems：[LibraryTab.kt:532–537](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt)。章节栏按已选章节计算下载/删除动作并在异步完成后清选择：[MangaDetailScreen.kt:492–540](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt)。

这些是可移植且有现存实现可复用的约束；用户仍需看到范围和部分失败结果。静态代码不能证明排序/过滤中途改变、失败、取消或重复触发时集合行为正确。页面契约应写明选择集合是否是当前可见项、每项能力如何聚合、何时清空，并由生产链路测试覆盖。

### UI-11.01、UI-11.02：搜索状态与关闭行为

规则位置：[02-content-actions.md:119–120](input/mihon-ui-kit/docs/ui/02-content-actions.md)。上游 SearchToolbar 以 null 区分普通顶栏和搜索，并在关闭时替换导航动作：[AppBar.kt@f52, L269–307、L359–401](https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/components/AppBar.kt#L269-L307)。Desktop LibraryTab 使用 nullable searchQuery，Escape 清为 null：[LibraryTab.kt:241、513–529](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt)。

“未打开”与“已打开但空”是准确的状态区分；请求软件键盘、释放 IME 焦点是 Android 表述。Desktop 应改写成搜索框 focus、文本选区、Escape/Enter、Ctrl+F（若支持）和结果更新时序的契约，而不是机械复刻 IME 测试场景。

### UI-12.01、UI-12.02：三态筛选和强制条件

规则位置：[02-content-actions.md:145–146](input/mihon-ui-kit/docs/ui/02-content-actions.md)。上游使用 TriStateItem，下载模式开启时明确选中且禁用下载筛选：[LibrarySettingsDialog.kt@f52, L79–93](https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt#L79-L93)。Desktop 同样把 globalDownloadedOnly 投影为 ENABLED_IS 并禁用控件：[LibraryComponents.kt:411–441](../../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryComponents.kt)。

本条不是把筛选降成普通开关的建议，而是忠实保留共享领域状态的正当约束。规范还应要求实例契约记录循环顺序、筛选作用域、清除入口及强制条件来源；现有四个初始场景不能代替这些组合行为验证。

### UI-14.03、UI-14.04：弹层内部返回和自适应

规则位置：[03-dialogs-forms.md:41–44](input/mihon-ui-kit/docs/ui/03-dialogs-forms.md)。原版 AdaptiveSheet 的位置取决于 tablet 判定并通过 Dialog 呈现：[AdaptiveSheet.kt@f52, L69–76](https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/components/AdaptiveSheet.kt#L69-L76)。Desktop 同步入口用 ModalBottomSheet，最大宽度 560dp、高度 720dp，Escape 分派内部 Back：[DesktopSyncPanel.kt:31–56](../../../app-desktop/src/main/kotlin/mihon/desktop/sync/DesktopSyncPanel.kt)。

嵌套页先返回、未保存数据受保护是共享语义；手机底部/平板居中的物理规则不是 Desktop 规则。应补窗口缩放下的最大/最小尺寸、内容滚动、焦点循环、Escape 内退与再次 Escape 关闭、遮罩关闭策略，以及正在运行任务时关闭的行为。不能将 Android 的 AdaptiveSheet 名称当成 Desktop 组件契约。

### UI-15.01：设置组件复用

规则位置：[03-dialogs-forms.md:67–70](input/mihon-ui-kit/docs/ui/03-dialogs-forms.md)。主规范指名 Android PreferenceScaffold/PreferenceScreen；组件基线所核对的也是 fork Android 源码。fork commit 的抽样证实 AppBar 的 navigateUp 可空、PreferenceScaffold 接收可空 onBackPressed，浮动按钮使用 SmallExtendedFloatingActionButton，和组件基线自述相符。

这些事实有助于 Android 适配，但不能指挥 Desktop 新页面直接调用 Android 组件。为 Desktop 补一份真实目录、分组标题、偏好控件、禁用说明、即时持久化和确认保存组件映射，并把规则写成语义优先、平台控件次之。

### UI-17.02：不可逆/跨设备操作确认

规则位置：[03-dialogs-forms.md:119–122](input/mihon-ui-kit/docs/ui/03-dialogs-forms.md)。这是一条新增的安全建议，不应读成“原版所有危险操作都有确认框”。source-outline 明确列出原版书库移除与下载队列清空的不同语义：[source-outline.md:121–125](input/mihon-ui-kit/docs/ui/source-outline.md)。规则本身也要求按同类差异记录例外，边界设计较稳妥。

Desktop 同步控制器中存在断开、切换空间、放弃旧配置等独立决策：[SyncPanel.kt:15–29、57–124](../../../data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanel.kt)，处理分支见 [SyncPanelController.kt:404–414](../../../data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanelController.kt)。后续页面应逐项说明影响本机还是远端、清理何种凭据/数据、是否可恢复及失败后结果；只套一个通用“危险确认”标签不够具体。

### UI-18.04：已有内容上的错误

规则位置：[04-async-feedback.md:15–18](input/mihon-ui-kit/docs/ui/04-async-feedback.md)。原版 BrowseSourceScreen 在已有结果而刷新/追加失败时发出可重试 Snackbar；首次无结果时沿独立加载/错误分支呈现：[BrowseSourceScreen.kt@f52, L54–84](https://github.com/mihonapp/mihon/blob/f52d890e7f8a3c418ddab41f41d4b577bce0dc06/app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt#L54-L84)。这支持“不要把失败转换成空结果”的提案。

这是合理的跨平台行为原则，但完整标准还要声明并发请求的旧结果丢弃、重试相同参数、已有内容何时保留，以及不可重试错误的恢复动作。manifest 场景不能证明真实 HTTP/数据库状态流已满足这些要求。

### UI-20.02：失败后的有效恢复入口

规则位置：[04-async-feedback.md:67–70](input/mihon-ui-kit/docs/ui/04-async-feedback.md)。Desktop 同步面板会把浏览器打开和复制失败转换为桌面通知：[DesktopSyncPanel.kt:54–63](../../../app-desktop/src/main/kotlin/mihon/desktop/sync/DesktopSyncPanel.kt)。这显示同一失败语义可落到平台通知服务，不应强制只用 Android Snackbar/Toast。

规则要求恢复入口确实重试同一操作，方向正确。同步面板还要给出不可重试的授权失败、远端状态冲突和本地恢复选项的明确结果。应把“失败→可做什么→状态保留/重置”写入场景契约，不能只验收提示文案。

## 三个代表页面的适用性

**书架与筛选。** UI-04、UI-09、UI-11、UI-12 对搜索、可见集合、筛选三态和批量操作最有指导力。Desktop 生产页已沿用这些业务语义；入口和反馈可以复用现有 toolbar 与 dropdown。还需契约化 Escape/焦点、窗口收窄布局、类别与全局偏好范围。上游移动端行为不能决定这些 Desktop 交互。

**漫画详情与章节多选。** UI-09 与 UI-27 能约束稳定章节 ID、选中计数、继续阅读动作隐藏、按每项状态推导批量下载/删除。Desktop 详情已有 ChapterSelectionState 与动作栏，但选择入口（Ctrl/Shift/长按等）、当前排序筛选对范围选择的意义、键盘取消优先级、失败后选择是否保留，都要从 Desktop 真实行为写具体契约。此处仅读源码，未判定交互测试已通过。

**同步设置/面板。** 当前 Desktop 入口是书架工具栏的同步按钮，包装共享 SyncPanelContent 为桌面 ModalBottomSheet：[DesktopSyncPanel.kt:31–56](../../../app-desktop/src/main/kotlin/mihon/desktop/sync/DesktopSyncPanel.kt)；其共享状态包含 MAIN、SETTINGS、HISTORY、SETUP 页面和 DISCONNECT、SWITCH_SPACE、ABANDON_LEGACY 决策：[SyncPanel.kt:15–29、57–124](../../../data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanel.kt)。UI-17、UI-18、UI-20、UI-31 提供部分安全与异步原则，但 UI-31 讲外部追踪绑定，UI-32 讲存储/备份，均不能代表设备同步。

该能力需要 Desktop 扩展条款：连接/授权/配对状态、空间与设备身份、任务进度/取消、冲突决策、断开与清理范围、错误恢复和秘密信息边界；还要单测 Modal 内部 Back 与关闭、复制代码和打开外链的桌面反馈。这里属于新能力范围扩展，不是发现原版规则违反。

## 充分性判定与最小补充

1. 保留现有 UI-01–32 作为共享语义目录；用规则级 provenance 标签区分 UPSTREAM_OBSERVED、SHARED_DOMAIN_CONTRACT、PROPOSED_GUARDRAIL、DESKTOP_ADAPTER，并把精确源码 permalink 挂到具体规则，而非只挂在类别上。manifest 目前只在 category 级记录 sources，每条 rule 是 id 与 requirement；读者不能从机器索引判断该规则属于哪一类证据。
2. 为 Desktop 追加平台映射：顶层导航/窗口尺寸与重排、鼠标和键盘动作、Escape/系统返回/模态关闭优先级、焦点管理、滚动与缩放、桌面通知和剪贴板/外链。明确哪些共享语义原样复用，哪些在 adapter 中改写。
3. 给三个页面逐一填写 page contract：书架和筛选、作品详情章节选择、同步设置/面板。补充组合场景和错误边界；保持规则场景 NOT_RUN，直到 production wiring 的测试与目标运行验收产生带版本、平台、环境和命令的证据。
4. 在同步范围明确后再新增设备同步规则组；不要把它塞入 UI-31 追踪服务，也不要通过改写原版事实制造“上游已有同步规范”的印象。


## 来源标签与颗粒度说明

- 本报告称“上游已证实”时，只表示检查过的 raw 源码片段直接支持该条局部行为，不表示审阅了完整上游仓库或该类所有规则。
- “本仓库观察”指 HEAD 对应的 Desktop 生产代码静态内容；除非明确列出测试，本报告没有运行时证据。
- “规范提案”指规范希望新实现遵循的契约；只要来源未直接支持，就不把它写成 Mihon 原版已实现的行为。
- “未验证”包括没有追读实现、没有穷尽调用点、没有执行运行/集成测试；不能作为通过或缺陷证据。

manifest 中类别有 sources，而规则对象只有 id 与 requirement；例如 [catalog-manifest.json:11–38](input/mihon-ui-kit/docs/ui/catalog-manifest.json)。source-outline 以类别为单位概述原版依据，例如 [source-outline.md:19–53](input/mihon-ui-kit/docs/ui/source-outline.md)，但不能逐条证明四条规则都是原版约定。

这类索引足以让实施者按类别找参考页面，但审查者仍需判断某条是平台事实还是防错建议。建议未来给每条规则增加来源类别、平台范围和一个精确来源位置；Desktop 改写时保留原语义与 adapter 决策的双向链接。

每类四个验收场景提供了统一起步格式，不足以覆盖组合状态和真实异步竞态。比如书架的选择与搜索并存、筛选后批量操作、同步对话框内部导航与窗口 Escape，都需要页面契约另列前置、操作、预期及生产证据。最终判断：规范的共享交互内核有足够细节作为实验输入；要成为今后 Mihon Desktop UI 的单独实施规范，至少还需上述 Desktop 映射、规则级来源标注和三个页面契约。当前审计没有证明任一类全部正确，也没有证明未读类别错误。