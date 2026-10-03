# Desktop 实机交互迭代证据

执行入口：[roadmap](../roadmap/2026-09-30-desktop-interaction-iteration-roadmap.md)。需求权威：[最终设计](../design/mihon-desktop-ui/2026-09-30-interaction-final-design.md)。本文是贯穿本迭代的唯一过程报告，不替代 capability manifest。

## RI00：起点与固定契约

- 起点：`0f6ed07dc2`，隔离工作树 `D:/Codex/worktrees/99ac/mihon`，开始时无未提交改动。用户指定的原仓库 roadmap 与此工作树同一提交；原仓库另有同步原型和详情验收文档改动，未复制、覆盖或提交。
- 父计划此前仍指向作者体验迭代；其文件说明历史实施已提交，待修复由其他计划跟踪。本轮只切换父指针，不修改其历史状态。其他 worktree 不作为本轮实现或测试输入。
- manifest：`app-desktop/src/test/resources/parity/parity-manifest.json`，当前为 64 项 capability 的 JSON 数组。原 `VERIFIED` 只代表历史证据，不能证明本轮新交互通过。保持既有 fixedMainRef 与状态，不全局重置。
- 固定来源：SOURCE 为当前 production 及文档指定的上游归档；PROJECT_POLICY 为最终设计；HTML_ADAPTER 为 DEMO。浏览器像素与原生 dp 不相互冒充。

### 接口、权威和不变量

| 对象 | 重核事实与固定边界 | 后续责任 |
| --- | --- | --- |
| Home / Voyager | 当前六根底栏，作者独立，只有 Reader 显式隐藏根栏；复用 Home 的 Navigator / TabNavigator 和 LibraryNavigationHost | RI01：五根、子页隐藏、真实事件；RI08/09 保护详情返回 |
| Browse / 作者 | Browse 已有嵌套 Navigator 和图源／扩展；AuthorsRootScreen 与真实 model 可复用，避免第二份作者仓库 | RI01：图源／作者／扩展／迁移；旧作者请求映射 |
| 更多 | 现有无痕、队列、迁移、统计、设置、关于均为真实入口，没有捐赠；新增分组接已有能力，分类使用现有管理链到 RI02 替换 | RI01 / RI02 |
| 设置 | SettingsRoot 目前单栏且维护 11 个条目；DesktopSettingsCatalog 已保存真实 route 与搜索锚点，优先成为唯一目录输入 | RI01：双栏／窄屏返回／搜索；RI03：外观内容 |
| 窗口 adapter | 原版自动资格以最短边判定：横屏 600dp，竖屏 700dp；不是单看宽度或 CSS 断点。主导航、设置、详情统一消费 | RI01 固定自动规则；RI03 加四项平板偏好；RI08 消费 |
| 双栏与封面 | TwoPanelBox 左栏为 min(可用宽度/2, 450dp)；MangaAndSourceTitlesLarge 内容两侧 16dp、封面 65%；Small 是 sizeIn(maxWidth=100dp) 和 16dp 间距 | RI08 原生布局；Desktop 7:10 保留，Android 2:3 保留 |
| 控件命中区域 | Desktop 使用 Material3 TopAppBar / IconButton / ListItem；图标 size 不代表命中区，调用方无显式统一高度 | 真实 Compose 事件与离屏尺寸测量；不杜撰数值 |
| 图标 | MoreScreen 使用 CloudOff、原版眼镜、GetApp、AutoMirrored.Label、QueryStats、Storage、Settings、Info、AutoMirrored.HelpOutline；详情家族按最终设计 DUI15 | 各 UI 批次按真实资源复用，不统一替换家族 |
| 根导航容器 | 原版 presentation-core 的 NavigationBar 是 80dp 且去掉 Material3 默认横向间隔；NavigationRail 最小 80dp、4dp 间距、垂直居中与 3dp tonalElevation | RI01 小型 Desktop adapter；Android presentation-core 模块不能直接用于 Desktop JVM，不扩大模块重构 |
| 设置公共目录 | 冻结上游 SettingsMainScreen 顺序为外观／书架／阅读器／下载／追踪／浏览／数据／安全／高级／关于；图标为 MaterialSymbols.Rounded 家族，阅读器 auto-mirror | RI01 同一目录；Desktop 常规设置保留为扩展，不挤占公共首项；SVG 通过本地资源适配，不引入 CDN |
| 书架选择 | LibrarySelectionState 当前调用共享 LibrarySelectionPolicy；Shift 追加闭区间，普通点击开详情／选中态增减；当前 pointer helper 只读取 Shift | RI06：明确替换／追加、Ctrl 与 wheel，保留隐藏选择 |
| 章节选择 | ChapterSelectionState 目前只有 toggle / selectAll / clear；无锚点／范围／反选，不能把书架保留隐藏选择套过来 | RI07 裁剪接口；RI09 追加范围与批量真实事件 |
| 封面链 | DesktopCustomCoverStore 用 mangaId 文件优先于 source URL；MangaCoverRequest 已把 model 和 coverVersion 放进 memory/disk cache key | RI04/08 复用，同址版本与删除回源红测；不新建缓存 |
| 主题与偏好 | DesktopTheme 已消费共享 AppThemeColorScheme、ThemeMode 与纯黑；DesktopAppPreferences 已用共享 ThemeDefaults/Codec；Android UiPreferences 还有日期／相对时间／简介图片／TabletUiMode | RI03 补共同权威与真实消费者，不复制色表或语言 JSON |
| 书架设置迁移 | LibraryPreferenceMigration 已存在；Desktop 周期目前 OFF/6/12/24h/WEEKLY，不能丢旧 6h 或以连续 set 冒充原子策略 | RI12/14：一次发布、marker 最后、最小恢复边界 |
| 更新／目录 | 复用 LibraryUpdateChecker、LibraryUpdateScheduler、DesktopTaskScheduler 和 commonMain SQLDelight schema；网络不入事务，文件不冒充 SQL 原子 | RI13/14，数据边界审查后下游使用 |
| 下载／阅读／追踪／迁移 | 复用已有 manager、Reader 请求、同步恢复、provider registry 和迁移用例；现有动作不得换成 DEMO 模型或空回调 | RI10/11/15 的真实 HTTP、DB、临时目录及失败证据 |

### 覆盖与红测位置

最终设计 6.2 当前有 **74 条唯一详情 ID**，每条已映射到 RI01/02/03/04/07/08/09/10/11/13/14/15/17。书架 L01–L08、设置 S01–S07、输入 I01–I08、CUI/DUI、T/AP 和共同 V 项以 roadmap 第 3 节为覆盖映射；没有删除被替代的原编号。此为文档完整性核验，不是产品行为证据。

| 簇 | 已有真实保护入口／新增失败测试位置 |
| --- | --- |
| 导航／作者／设置 | NavigationContractTest、TestNavigationControllerTest、DesktopSettingsSearchWiringTest、AuthorsProductionWiringTest；RI01 新增原生宿主事件测试 |
| 分类 | LibraryCategoryBehaviorTest、LibraryCategoryProjectionTest；RI02 真实 repository 和管理页面事件 |
| 主题／外观 | DesktopLocaleAdapterTest、DesktopPreferenceMigrationTest、DesktopSettingsContentAccessibilityTest；RI03 ColorScheme、日期、Markdown 和持久偏好 |
| 书架／封面／选择 | LibraryPageCompositionTest、LibraryParityIntegrationTest、MangaCoverAdapterTest、MangaCoverManagerTest；RI04–06 pointer/wheel/焦点与缓存请求 |
| 详情／章节／下载 | MangaDetailParityIntegrationTest、ChapterSelectionStateTest、ChapterSelectionActionsTest；RI07–10 production repository/flow/Compose/队列/文件 |
| 设置／更新恢复 | LibraryPreferenceMigrationTest、LibraryUpdateCheckerTest、LibraryUpdateSchedulerTest、LibraryUpdateRecoveryIntegrationTest；RI12–14 双存储故障与真实 DB |
| 迁移／系统／刷新 | DesktopMigrationParityTest、DesktopBatchMigrationControllerTest；RI15–17 真实 DB/文件、native adapter、发布 runtime 与 wheel |

以上后续入口只说明可复用位置；本轮未运行的类不能记为通过，各批次红测必须执行真实 production 与 wiring。

特别注意：当前 `MangaDetailParityIntegrationTest` 实际只有作者导航名字 helper 的两个测试，不是 74 项详情的完整集成证据。后续详情批次须扩展实际 repository、Compose 事件与导航测试，不能凭测试类名宣称覆盖。

### RI00 首簇保护基线

环境：Windows，JDK 21，Android SDK `D:/Android/Sdk` 的 android-36/android.jar、build-tools/36.0.0/aapt2.exe、platform-tools/adb.exe 已确认存在。Gradle 串行协调，未操作其他 worktree 的进程。

```powershell
python scripts/gradle-coordinator.py run --key interaction-ri00-baseline -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.ui.NavigationContractTest --tests mihon.desktop.test.navigation.TestNavigationControllerTest --tests mihon.desktop.ui.settings.DesktopSettingsSearchWiringTest --tests mihon.desktop.ui.authors.AuthorsProductionWiringTest
```

结果：`BUILD SUCCESSFUL`，1m51s，协调器 exitCode=0，状态 PASSED，记录的 PID 2600 已结束。原始日志 `.gradle-coordinator/interaction-ri00-baseline.log` 与状态 JSON 为本工作树忽略的过程产物；不把历史日志复制为本轮证据。

## RI01：根导航与设置宿主

固定必做项：五根宽 Rail／窄 Bar；详情、阅读器与设置子页隐藏根栏；浏览四子页签及旧作者映射；更多分组真实动作；设置双栏默认外观／窄屏目录返回；搜索锚点和作者详情上下文保留。分类目前接既有管理对话框，RI02 才转换为共用分类子页；没有用对话框证明分类子页已完成。

内聚性说明：此簇超过 8 个文件。五根标签各自已有嵌套 Navigator，需要在各真实所有者报告栈深度；十余个现有设置页需共同消费宿主返回规则。仅替换返回按钮接线、抽出统一宿主与目录，不复制设置业务，也不按文件拆开不能独立验收的功能。主要风险是局部返回／宽窄重排、原搜索锚点，以及嵌入作者后的 ScreenModel 生命周期。

运行库核对：本机实际 Voyager `1.1.0-beta03` 的 `Screen.rememberScreenModel` 以 receiver 的 ScreenKey 注册生命周期，而不是自动采用当前 Navigator 的 Screen。直接调用 `AuthorsRootScreen.Content()` 会保留独立作者所有者；嵌入浏览时须显式把所有者交给真实 Browse Screen，切换子页签不销毁模型，移除 Browse 后释放模型。此为当前缓存运行库的二进制核对，不依赖系统 JDK 独立客户端替代产品测试；最终仍以真实 Compose／Voyager 生命周期测试为验收。

### 实现及红绿证据

- Home 统一五根入口，原版导航容器的小型 Desktop adapter 消费同一个窗口资格。每个真实 Tab 的嵌套 Navigator 报告栈深度；push 隐藏根栏，pop 恢复，Reader 沿用既有隐藏规则，书架重复点击沿用原面板入口。
- 浏览使用原作者 model 和迁移页面，四子页签各保留局部状态。旧 Authors／AuthorsTab／AuthorsScreen 请求进入浏览第二页签；作者 query、详情返回及切回上下文保持，移除 Browse 释放原模型。
- 更多恢复原组序，分类、队列、统计、数据与存储、设置、关于及帮助接现有入口。仅下载开关接已有共享书架偏好；章节的全局下载锁定尚属 RI07，不提前宣称完整闭环。帮助使用现有系统 URI adapter，测试不会打开外部浏览器。
- 设置的唯一 catalog 同时供目录和搜索使用，公共十项顺序和原版图标固定，Desktop 常规设置保留在末尾。宽屏默认外观；窄屏从目录选择，返回目录；真实搜索定位、查询文本与当前页在宽窄切换时保留。既有设置业务和独立页面入口继续复用。

| 协调器 key | 正确的红测原因／绿色证据 |
| --- | --- |
| `interaction-ri01-red` | 旧作者独立根 Tab、更多缺新增入口、宽屏设置没有默认外观，25 项中 3 项按预期失败 |
| `interaction-ri01-host-red-behavior` | 1400×900 真实 Home 没有 Rail |
| `interaction-ri01-browse-red-valid` | 真实 Browse 缺作者子页签 |
| `interaction-ri01-author-owner-red-valid` | Browse 销毁后 creator flow 仍有订阅，真实生命周期断言超时 |
| `interaction-ri01-directory-order-red` | 常规设置占据共同目录首项 |
| `interaction-ri01-navigation-spacing-red` | 默认 Material3 底栏存在 8dp 横向间隔，原版要求 0 |
| `interaction-ri01-focused-final` | 11 类、105 项、0 失败、0 跳过；39 秒，2026-09-30 08:29:41 UTC 结束 |
| `interaction-ri01-directory-scroll-red` | 真实 ScrollToIndex(3)→缩窄→子页返回后目录滚动归零；1 项按预期失败 |

测试编译错误、夹具错误和旧空依赖错误不计为红测证据。首簇保护回归包含真实书架、封面 adapter、更新恢复、偏好迁移、locale、导航和设置；其中两个详情 helper 的局限已在 RI00 明确说明。

### 独立审查

主代理进行了唯一一轮独立审查，检查 production 的 Home／各 Tab 接线、Browse 作者所有者、旧请求、设置返回／搜索以及更多回调。唯一 P2：目录 LazyColumn 的滚动状态留在条件 composition 内，宽窄重排后丢失。交回原实施者先写上述真实失败测试，再把唯一 LazyListState 提升到持续存在的 SettingsRoot composition。

唯一修复复审已通过：主代理独立读取实际 production state 所有者、测试行为及 XML／协调器终态。`interaction-ri01-directory-scroll-green-valid` 四类 **50 项、0 失败、0 跳过**，37 秒，2026-09-30 08:49:05 UTC 结束。真实目录 ScrollToIndex(3) 后缩窄并点击子页返回，要求非零滚动、前后 scroll value 相等和下载可点击整行位置一致。初次绿测进程退出却未记录终态，协调器标记 ORPHANED／125，不计通过；恢复运行的内部文字 80→76px 是副标题换行重排，改为观察实际整行，并保留原失滚动断言，不扩大数值容差。其余格式／完整测试／发布运行结果待实际完成后补记。

### 图标来源

设置十项 SVG 来自固定 `google/material-design-icons` 提交 `bd8cb85bd4bad964fe6918f79665bb40c3a8efef`，路径为 `symbols/web/<name>/materialsymbolsrounded/<name>_24px.svg`，通过本地打包资源适配 ImageVector，不在运行时联网。阅读器开启 autoMirror；眼镜复用 Android `ic_glasses_24dp.xml` 的原 path。以下 SHA-256 为本轮实际文件核验，不是浏览器截图证据。

同目录打包固定提交的官方 Apache-2.0 `LICENSE` 和来源 `NOTICE`；LICENSE SHA-256 为 `49bbe9114e49214df2ccc324cb3ac8d1d1aa1c3a0947f94c286765e86647b32e`。下载仅在实施时通过会话代理进行，运行时无远端图标依赖。

| 文件 | SHA-256 |
| --- | --- |
| chrome_reader_mode.svg | b2d2bd32dda2a0e24caaa338f08714e3b7d247ab84f845208c4175f75573686c |
| code.svg | df0d955362c399d7246a437f817cd83b8da4f5da631a03882687c986ebc71785 |
| collections_bookmark.svg | 638039ec626c9d6605ad7d9e2cce8d980a5720377c7cf28d2903bba210c6cfef |
| download.svg | af215e8e4960234aec60127e3819718a972f4d2a90b3c617602134681bad557f |
| explore.svg | 2a8433fe386c496e725ce39deb77b84840d71c5dad8948173452041108af2c7b |
| info.svg | ab779a17ff3bc8f5a30e3231509ef9ff6fee709ffcaa46fb65217ce170f03e14 |
| palette.svg | 0b847ea803e92b6e1c792103c89e7448659cb001032ffd5699c1f956ca74f656 |
| security.svg | 30a0cddf9527bc5d93b3ec21cf08cdc90d28da2f5a21aa8e7f469c3edd8d2e98 |
| storage.svg | 10ab0efb808aa95895b06337ae41aca5985da8a2463a599a28f11b36e6160a69 |
| sync.svg | 4e76b510662a3d4317205bfac9cb8be32178dc7ee7d89447144bf9a1d38ca7a1 |

## 收口验证与当前门禁

- `interaction-ri01-format`：仓库 `spotlessCheck` 通过，30 秒。当前 app-desktop 未应用该 lint 插件，因此不把仓库检查声称为 Desktop 全文件 ktlint 覆盖；Desktop 本轮改动另做机械整理和 `git diff --check`。
- `bash scripts/build-desktop.sh full-tests`：本轮唯一完整 Desktop JVM 执行，启用 `-PincludeIntegrationTests=true`，4m27s，**3248 项、2 失败、3 条件跳过**。不计为全绿；不包含 live-network／network-survey／最终 parity 治理专项。完整日志为 `.gradle-coordinator/interaction-ri01-full-tests.log`。
- 失败一：`DesktopProductCapabilityContractTest` 指出 ID3 Browse roleEvidence 的旧行号214已失效。限定核对发现共8条 current 证据索引随本轮文件移动失效，已逐项更新为当前真实符号行；不改 capability 状态、action 语义、固定上游 ref、历史完成证据。
- 失败二：`SourceExtensionNavigationContractTest` 的旧 More 夹具没有 `getLibraryPreferences()` 的 MockK 答案。仅补真实 `LibraryPreferences(InMemoryPreferenceStore())`，生产行为和原断言保持。
- `interaction-ri01-final-gates-focused`：上述两个失败类 **40 项、0 失败、0 跳过**，39秒，2026-09-30 08:58:30 UTC 结束。原全量其他结果不抹去；修正后的当前 diff 尚未重跑完整测试，不能作为 build-only 的完整通过证据。
- 三条原条件跳过分别为 macOS 原生分享 JXA、当前 headless 环境的 Windows 窗口隐私、正式配置中不可用的 non-release 自定义间隔。没有把条件跳过计为运行验收通过。

流程纠正（2026-09-30）：用户要求全部任务完成后再做全量。前次在 RI01 后提前执行完整 Desktop 测试不符合本迭代的收口时机，保留其真实失败及修复证据；撤回首簇追加全量请求，不再因此阻断后续实现。RI00–RI17 只执行行为红绿和明确受影响的集成／wiring、格式与风险专项；完整 Android／Desktop 矩阵、正式构建及运行交付统一在 RI18 的最终冻结 diff 上执行。当前 focused 结果证明首簇变更，不能外推整份 roadmap 已完成或构建已通过。

## RI02：共用分类管理与详情归属

起点为 RI00／RI01 的提交 `14a8ce1569`，工作树开始时干净。固定验收 CUI1–CUI8 和 D-C3，HTML 的 Alt+↑／↓仅为 adapter 参照，不强制复制到 native。

- 复用真实 LibraryScreenModelFactory、GetCategories、CreateCategoryWithName、RenameCategory、ReorderCategory、DeleteCategory 和详情 SetMangaCategories 链；不新建分类 repository 或第二份持久分类列表。临时拖动顺序只属于视图投影，失败恢复真实权威。
- 主代理已核对 commonMain categories.sq 的系统分类删除保护，以及 mangas_categories.sq 的关联 ON DELETE CASCADE。删除分类不删除漫画；libraryView 使用默认分类投影。需由本批真实 SQLite／repository／Compose 测试验证，源码事实不替代通过证据。
- DeleteCategory 正常路径清理默认分类、更新包含／排除及下载三个引用集合并重排；其 DB 删除、偏好写入、后续重排不是跨存储事务。后两阶段失败与精确恢复是 RI12 的边界，本批不能宣称原子清理完成。
- 更多 push 共用普通 Screen，详情对话框编辑抛弃未确认草稿后 push 同页，返回保留原详情；管理页只列自定义分类，添加／重命名明确校验，删除具名确认，Escape 每次只退一层并还焦。
- 对原4个 category action 返回最小 Boolean 以区分实际 usecase 结果；原调用可忽略，不通过翻译后的反馈文字判断成功。不改变 Android domain 用例语义。

有效红测依次为 `interaction-ri02-entry-red`、`name-red-valid`、`cards-reorder-red-valid`、`detail-fab-red`、`library-entry-red`（均带 interaction-ri02 前缀）：分别证明真实导航、独立名称弹窗、卡片动作／手柄、窄窗 FAB 遮挡与详情入口、旧入口及原生 Escape 的缺口。不可用组件的编译错误和系统分类夹具错误不作为行为红证据。

最终 `interaction-ri02-final-focused-valid` 退出0／PASSED，2026-09-30 10:54:05–10:54:59 UTC，73项／0失败／0跳过。命令为协调器执行 `:app-desktop:jvmTest --offline -PincludeIntegrationTests=true`，过滤 CategoryManagementScreenTest（12）、DesktopSettingsSearchWiringTest（23）、LibraryParityIntegrationTest（16）、MangaDetailActionsTest（7）、MangaShareWiringTest（3）、MangaDetailLibraryEntryWiringTest（10）及 LibraryCategoryBehaviorTest 的真实 DI 分类 CRUD／创建失败两项。真实 pointer 拖动、聚焦 Alt+Down、SQLite 故障回滚／重试／重启、删除5组偏好引用及默认归属、详情草稿丢弃和 Screen owner Job 销毁均有实际事件证据。

本批超过8个文件／400行是同一分类管理能力在共用 Screen、More／详情导航、分类用例结果和真实 Compose／SQLite 契约中的内聚改动。保留原 repository、DI factory 及共享用例，没有为拆分估算复制生产实现。

### 独立初审与修复范围

主代理同一轮初审核对新Screen／More及详情接线、真实SQLite归属／删除、正常偏好引用清理、排序失败回权威、拖动事件、页面实例键和model销毁。`interaction-ri02-library-escape-green` 的9项新分类事件测试通过，但四相关类共58项中More旧夹具1项失败，不将该轮记为全绿。

初审三项需补齐：详情旧primary分类按钮仍与新的More入口并存；归属弹窗仍使用无滚动Column，多分类末项缺可达性证据；CustomAccessibilityAction验证不能单独证明普通键盘触发排序。交原实施者合并为唯一修复轮，补真实详情入口、24分类窄窗滚动及实际聚焦手柄键盘事件测试，预计约15–20分钟；不新增代理、审查轮次、全量或构建。旧factory/model CRUD保护仍适用，不改写为另一套实现；`interaction-ri02-review-red` 3项／3失败均为正确业务原因；最终73项 focused绿证据包含三项回归。主代理唯一修复复审核对：移除旧 primary enum／回调，保留收藏、间隔、追踪和分享断言；归属使用受父约束的 max320dp LazyColumn，360×600窗口24分类末项严格实体 bounds 与真实保存；手柄 RequestFocus 后原生 Alt+Down KeyDown／KeyUp 驱动 SQLite 顺序。三项已通过复审。

新增 Escape 断言还发现归属弹窗无焦点宿主，已显式初始化原生 focus owner；离屏夹具 WindowInfo 补真实 containerSize／containerDpSize，避免未提供尺寸造成无意义坐标，没有放宽 bounds 容差。名称空／重复／未变禁止确认、保存失败保留草稿；删除正常引用清理通过，跨存储部分失败仍由 RI12 闭环。

格式首检仅发现本批 XML 的 CRLF，已规范为 UTF-8／LF；`interaction-ri02-final-format-index` 的 spotlessCheck 与 DesktopProductCapabilityContractTest（34项／0失败／0跳过）均通过；6处 current roleEvidence 行号已机械更新，不修改 capability 状态或历史来源。

### RI03 前置来源核验（只读，不代表实施完成）

主代理核验固定提交 `866d045c7aba343fd739c57d15c81ff6df59e796` 的归档：upstream-themes manifest 的25个文件、upstream-appearance manifest 的29个 files 均与记录的 SHA-256 一致。appearance 的133个 translation_sources XML 未随归档保留，不能声称已全量核验；实现须消费仓库实际 moko 资源及共享色表，不复制 HTML JSON。

当前 i18n 有68个 strings.xml 目录，其中 tt 为空；实际非空及生成的 JVM mokoBundle 均为67种，缺定稿目录中的 kmr。已通过会话 HTTP 代理取得固定上游的 `i18n/src/commonMain/moko-resources/kmr/strings.xml`，18579字节，SHA-256 `e8e2a784862367489ba4ed75f93ac57ca34e7d8300213fd075dd0b95b9fab8bd`，与归档 manifest 的 kmr 记录一致。该只读前置文件暂存忽略的 `.gradle-coordinator/interaction-ri03-source/kmr-strings.xml`；尚未写入 production，补齐资源与真实语言／复数接线须随 RI03 红绿实施。

## RI03：上游主题与外观完整闭环（完成）

RI02 同批提交为 `d0e58ee8a695acfe5755ab881a34359ce11f18c2`。本批复用原实施代理，主代理负责接口、来源与独立验收；一轮初审及必要修复复审，focused 红绿／受影响集成／原生离屏视觉，完整测试和发布构建0次，预计1–3小时。共享迁移或网络边界出现具体新增风险时才追加相应专项，不扩张产品范围。

固定验收沿最终设计 AP01–AP15／T01–T08：14个平台可用 static 主题及完整浅／深／纯黑角色、三段模式、114dp横卡、LIGHT隐藏纯黑但保值，Windows MONET旧值只安全回退且解释；资源派生语言普通子页与全应用即时消费；平板四项统一启动时快照及安全手动重启提示；六日期格式、LocalDate相对时间和真实章节消费；简介 Markdown 图片开关复用已认证 Coil 链，保文字链接；单选新值即存、当前不关闭、取消无写入和还焦；320dp与200%字号可达性及原生视觉来源。

稳定接口：现 AppTheme／AppThemeColorScheme 仍是两端唯一色表；i18n 构建阶段从实际非空 moko 资源派生最小语言目录；LocaleAdapter 保留失败回滚／协调反馈，语言改变不得因 keyed composition 销毁 Voyager 子导航或业务 owner。TabletUiMode 沿已有 enum 身份共享，DesktopUiDependencies 保存本次启动唯一模式，Home／设置／详情共用 DesktopWindowLayout。日期按本地日历日政策，Markdown 复用现 parser／Coil transformer并映射 DesktopSourceImage，不复制 HTTP 或网络策略。旧 Desktop 列数持久值保留交 RI05，不中途删除。

主代理只读核对 RI02 提交的共享 literal 角色与固定上游：Catppuccin dark secondary／tertiary分别应为 `FFB4BEFE`／`FFA6E3A1`，light为 `FF7287FD`／`FF40A02B`；现沿用紫色主色。Nord light缺 `outlineVariant=FFD8DEE9`。已交原实施者纳入正确红绿角色测试；其余现有 static 色表显式颜色与冻结来源一致，尚不能由此声称隐式 Material3角色或实际渲染已经验收。TOKYONIGHT及上述两处需共享修正，不能只新增卡片后宣称全部主题完成。

SOURCE 尺寸补充：主题预览 card114dp／9:16，border4dp、outer17dp／inner13dp；其虚拟 Cover 沿冻结 AppThemePreferenceWidget 的 Android Book比例2:3。这是主题示意组件，Desktop真实书架／详情封面仍为7:10，两者不混为平台常量。三段沿冻结 MultiChoiceSegmentedButtonRow／SegmentedButton，以唯一 value约束模式；选中图标消费匹配的 RoundedFilled.CheckCircle。相对日期冻结边界为未来1–7日、今天、过去1–6日，其余绝对，所有差值基于本地日历日。

详情布局起点纠正：实施者核对实际 MangaDetailScreen 仍为全页 LazyColumn，没有消费统一窗口 adapter；RI01仅 Home／Settings 已接线。RI03须增加最小真实宽／窄详情宿主，宽时分区资料与章节、窄时保单栏，四种模式均消费同一启动 snapshot；不能以未消费的 expanded变量作为平板完成证据。相关真实 Screen红绿固定为当前启动改偏好不改变布局、新 owner启动才应用，与Home／Settings资格一致且不修改物理窗口。RI08仍负责其完整450dp／65%资料栏、独立滚动、精简工具栏及其余DUI出口，本批不提前勾选RI08。此为既有整体范围内的接口补接，不增加任务、代理或审查轮次。

阶段实现回执（不代表本批完整）：`interaction-ri03-foundation-red` shared2项与locale1项分别因static目录14／13、Catppuccin辅助色、语言68／3正确失败；`foundation-green`3项／0失败／0跳过，资源及下游重编译5m5s。`appearance-red`4项真实交互红；`appearance-green-valid`3绿、1项选语言后Main keyed重建退出子页仍红，不将该轮记全绿。`locale-owner-green-valid` 20s／PASSED，AppearanceInteractionTest4＋DesktopLocaleAdapterTest12，共16项／0失败／0跳过。真实Main→Home更多→Settings→Language选择留页，返回目录／根后根Library文字英语刷新；真实LibraryFactory创建数不增加、原SearchQuery保留。回执说明Provide使用locale composition通知而非Nav key，五根options和History日期缓存以locale更新，业务remember未重建；旧回滚／协调12项保持绿。主代理整体独立审查尚未进行。

`interaction-ri03-consumers-red` 保存日期／平板真实消费缺口；`consumers-green` 2026-09-30 11:48:53–11:50:16 UTC退出0／PASSED，AppearanceInteractionTest过滤 `six saved*`／`saved tablet*`，XML2项／0失败／0错误／0跳过。单纯支持六格式或写偏好不替代上述Screen消费证据；日历日边界、所有模式矩阵与新owner应用仍须最终相关回归核对。

用户更新AGENTS明确「当前 roadmap 全部实施任务完成后，在最终收口执行全量测试1次；全量测试额度不构成提前执行的授权」。已同步原实施代理，继续既有RI00–17 focused／受影响集成／格式、RI18统一完整矩阵与发布的时机，不因阶段结束提前模块完整或构建。

命令范围失误保留：`interaction-ri03-calendar-red`／`calendar-green` 把两个task先列、--tests放最后，Gradle只将filter应用末task，意外执行presentation-theme当前31项，违背本轮不提前模块完整的时机。原共享日差3场景＋真实Detail相对日期1场景均有正确业务红，green的4目标通过，但旧ThemeDefaults／SharedPaletteExact三处固定期望失败，整轮不记全绿、不作为最终完整矩阵证据。后续每task后紧跟专属filter，受影响旧期望按冻结SOURCE核对后仅focused修复；不重复模块完整，RI18最终矩阵仍待全部实施完成。具体旧期望：ThemeDefaultsTest目录新增TOKYONIGHT且置YOTSUBA后；SharedPaletteExactTest.catppuccin仅四个辅助色字面量按冻结SOURCE修正，其余完整角色保留；nord仅light outlineVariant从隐式 `FFCAC4D0`改SOURCE显式`FFD8DEE9`，其余角色不改。`calendar-green-focused` 各task后专属filter，shared UiDate3＋ThemeDefaults1＋SharedPaletteExact相关2＋实际Detail相对日期1，共7项／0失败／0跳过。没有随运行实际值批量重写Golden。

详情布局阶段：`detail-layout-red` 因真实Screen资格未消费正确失败；`detail-layout-green-valid` 四模式／六宽高组合、当前snapshot不即时应用以及绝对／相对日期相关3项全绿。临时详情分区左宽为min(width/2,450dp)，资格复用唯一adapter；RI08仍须落实最终65%公式、完整独立滚动与其余DUI，不把该初步分区称最终详情完成。Android日期wrapper已最小委托共享日差且新增实际wrapper契约，focused待完成。

Markdown阶段正确红验证真实详情仍显示原始Markdown语法；`interaction-ri03-markdown-green-render` 2026-09-30 12:18:33–12:18:54 UTC退出0／PASSED，真实详情图片开关场景1项，包含源typed路由／Referer以及关闭后零图片请求。此前`markdown-green`因PowerShell拆分代理JVM参数而找不到`.proxyHost=127.0.0.1` task，属于命令错误，不计行为红。presentation-core现为Android模块，不能直接导入整个Android renderer；纯Kotlin的SimpleMarkdownFlavourDescriptor及marker processor必须提取到现core/common共享，Android/Desktop调用同一flavour，Material样式与typed Coil转换留平台adapter，不能复制第二份解析语义。共享提取及Android包装的focused仍待完成。

Android日期包装、完整主题角色和离屏视觉、弹窗焦点仍在实施。独立审查及提交待稳定产物，RI03保持未勾选。

### RI03 独立初审与唯一修复轮（完成）

`interaction-ri03-native-visual-red`首轮因截图编码API编译失败，不算行为红；`native-visual-red-valid`退出0／PASSED，Desktop三个场景与共享Markdown AST两个场景共5项／0失败／0跳过。4张原生离屏contact图记录14个static主题×浅／深／纯黑、窄320dp／fontScale2；主代理已实际查看浅／深及窄窗图，不将同production factory的角色对照称独立上游颜色oracle。自查发现三段模式System换行造成高度不一致；`segment-height-red`按实际三个按钮bounds正确红，`segment-height-green`1项全绿，已更新窄窗图。共享AST早先一次失败因夹具用IElementType.toString比较，不算业务缺口；改成真实类型常量后通过。

`interaction-ri03-android-date-focused`以`:app:testReleaseUnitTest --tests *RelativeDateContractTest --offline`实际运行Android包装，2026-09-30 12:42:21–12:48:38 UTC，退出0／PASSED，1项／0失败／0跳过；6m15s包含受影响Android release资源、主与测试编译。其参数没有无filter的测试task，不计全量；SDK文件已核对，Android真实wrapper消费共享日差及现moko翻译／复数。

主代理同一轮初审核对真实Screen／偏好／locale owner、共享flavour与typed Coil、日期包装、窗口快照及事件测试。Tokyo production与冻结源码SHA-256同为`9f20f48e87898aea74922d955b9ef39e13eaef05e5a1dda2674a34a296855ba0`，源码字面量一致；新增主题仍缺独立全部角色golden。初审合并6项：直接偏好set及choice写后flush失败缺回滚／反馈；焦点测试未完整覆盖Shift+Tab及所有背景；Tokyo角色独立oracle缺口；三段icon={}抑制SOURCE默认checked图标；实际系统主题信号及owner状态保留覆盖缺口；Markdown测试只有请求而没有成功显示、每次重建Screen且失败图片／mounted切换缺证据。统一交原实施者唯一修复轮，预计追加30–60分钟focused及相关复验，不新增代理、审查轮次、全量或构建；局部写入边界不得扩成通用事务系统。尚未通过修复复审，RI03保持未勾选。

修复阶段的失败归因明确区分：`review-description-red`中共享disabled IMAGE片段返回null是正确业务红；Desktop仅缺新增ready语义标签不能证明旧图片解码或绘制失败，不将其记作产品缺陷。随后`review-description-red-wiring`在真实源图片成功解码、已知像素已绘制之后，暂撤描述annotator接线，真实换行／HTML字面保留断言正确失败；恢复production接线后的`review-description-green-valid`于2026-09-30 13:29:18–13:30:01 UTC退出0／PASSED，Desktop mounted描述、rollback再次失败及320dp可达3项，加共享Markdown契约3项，共6项／0失败／0跳过。成功图以4×4洋红源图在真实渲染区域的中心像素断言，不以tag存在代替绘制；同一mounted详情开关后owner不重建，disabled图片URL／alt及普通链接通过实际pointer事件，typed源client和Referer保持。最终受影响回归及主代理修复复审仍待完成。

最终相关回归保留真实终态：`interaction-ri03-final-affected`于2026-09-30 21:51:25–21:57:47 +08运行6m20s，整条FAILED／exit1；共享主题／日期四个明确受影响类32项、共享Markdown3项和Android RelativeDateContractTest1项XML均0失败／0跳过。失败来自Desktop格式整理漏显式withFrameNanos／heightIn import，以及i18n/core具体格式，不是业务红；运行期间未改输入。Android当前主代码和测试代码真实编译通过，新MangaInfoHeader共用AST接线已包含。只读诊断观察到Android现forkEvery=1造成focused方法过滤后仍启动大量空worker，后续focused可用忽略init限定测试class发现范围并保方法filter，不修改永久fork策略。

最小import／UTF-8-LF和排序修复后，只运行Desktop七个明确相关类＋格式：`interaction-ri03-final-desktop-green`于22:00:17–22:03:31 +08终态FAILED／exit1，104项中103通过、1失败；Appearance18、Locale12、Category12、Detail2、Image3、manifest34均0失败／0跳过，SettingsSearch23中22通过。root spotlessCheck和临时scoped Desktop五文件格式检查通过；此前共享／Android绿色不重复运行。唯一未闭合失败是早先已有的library search anchor测试，需保真实Library路由／锚点／滚动／interval写入断言修复fixture，不能以新Appearance绿替代。

主代理已实际查看最后生成的ri03-themes-light／dark／amoled及ri03-320dp-font200四张PNG。前者是逐主题选中预览的原生离屏拼接，14个static主题×浅／深／纯黑，不是同时选中14主题的产品页面；后者为浅／深／纯黑3列、每列320×800dp及fontScale2的页面／日期弹层拼接。默认checked图标恢复，三段同高、LIGHT无整行纯黑、末项和Cancel实际可达；SOURCE参数和语义色表对照而非像素级上游截图基准。唯一修复复审六项已核对production与真实事件／绘制证据，最终旧搜索fixture修复、索引稳定核验和提交仍待完成；当前不勾RI03。

RI03最终闭合：`interaction-ri03-library-search-green`于2026-09-30 14:06:37–14:07:23 UTC退出0／PASSED，SettingsSearch23项／0失败／0错误／0跳过；同条命令root spotlessCheck及Desktop新增五文件scoped格式通过。旧fixture用真实production catalog定位Library Display索引、ScrollToIndex滚至可见结果，并在merged row匹配完整title＋breadcrumb后点击；原Library导航、锚点高亮、滚动与6h更新偏好写入及再打开无旧锚点断言全部保留。修复仅测试定位，无production行为放宽。有效去重结果为Desktop104（81项沿上一轮有效结果＋SettingsSearch23）、共享主题／日期32、共享Markdown3、Android真实包装1，共140项／0失败／0跳过；所有失败命令的原终态仍保留，不改写成整条成功。

主代理完成本批唯一修复复审：六项反馈已闭合，按实际调用链核对局部保存边界、双向modal事件、独立Tokyo oracle、SOURCE默认模式图标、真实LocalSystemTheme消费及owner／焦点／滚动保留、mounted描述的typed请求／已知像素绘制／失败和disabled链接。最终四张原生图环境为Windows、Temurin JDK21.0.11、Compose1.10.2／Skiko0.9.37.4、density1、fontScale1／2；14主题拼接与320×800dp页面来源清楚。check_circle SVG／Tokyo Night／官方kmr SHA均实核且与冻结值相同；NOTICE保原来源及Apache许可证。主代理重核5处current roleEvidence行号，无剩余漂移，不改变capability状态／历史ref；git diff --check退出0。实现、测试、源资产、必要契约与checkoff随本批同一提交，不建立额外报告或推进提交。

本批文件数／行数超过Estimated scope，原因是同一个外观能力的共享主题与资源目录、Desktop页面和locale／布局／日期／简介真实消费者、Android共享包装及集成测试必须一起闭合；依赖及owner接口内聚，不按文件拆开不可独立验收的链路。边界保持：14个平台可用static主题，Windows无MONET provider；默认＋68种实际非空资源语言；平板保存仅下一启动owner应用并提示手动重启；旧列数2–6仍保留RI05迁移；详情最小分区不代替RI08完整布局。全量及正式发布本批0次，RI04–RI18继续串行推进。

### RI04 展示与续读前置核对（只读，不代表实施完成）

LibraryGrid／LibraryList当前没有显式共享Lazy状态和右侧滚动条；LibraryTab标签限定分类数大于一。两种布局的续读资格仍消费badges.unreadCount，隐藏角标会隐藏入口；已有syncedResumeMangaIds已验证恢复章节存在且非外链，但UI未消费，continueReadingRequest仍先选nextUnreadChapter再仅对相同目标附恢复页码。RI04须按稳定分类ID／有效作品锚点保护四布局位置，真实点击验证普通续读的有效未读目标、匹配目标的同步页码及过滤／陈旧目标回退，复用现Reader请求与恢复身份，不重写Reader算法。进一步核对既有LibraryPageCompositionTest和Detail实际nextUnreadChapter，二者保护最早适用未读优先于旧已读同步章、全已读隐藏普通续读；详情syncedResumeChapterId只影响匹配目标的Resume文案，不能误推为任意同步目标优先。RI04保留这些既有边界。封面应接现DesktopCustomCoverStore.resolveModel与rememberMangaCoverRequestState的typed源模型、memory／disk版本键，保持真实Desktop7:10和列表48dp独立小封面。这里仅核对入口，无本批实现或测试通过结论。

## RI04：四布局、封面、滚动恢复与续读（完成）

RI03同批提交为`56c963d79acf8582281fe1e6649d217f821e2ad4`，主代理确认提交后工作树干净。继续复用原实施代理，主代理固定接口／契约、独立初审1轮及必要修复复审1轮；按四组行为执行focused红绿与清理复验，稳定后1组明确受影响回归及格式，具体失败只补修复影响路径。完整测试／发布构建0次，预计1–2小时；不新增报告／快照、代理或状态引擎。

固定四组验收：四布局真实Scrollbar拖动1000作品至尾／回顶且不启动更新，稳定分类ID＋有效作品锚点／有界offset恢复分类切换及详情返回，排序／布局／列数／尺寸／删除有界回退；书架接现自定义cover优先与typed版本化request，真实临时文件／Coil验证替换／删除／源同址版本／失败与重新owner；续读不消费角标显示值，真实Reader导航验证未读资格、匹配目标的同步页码、过滤／陈旧／外部恢复与选择中不误读；单自定义分类与默认0区别、显示开关和既有搜索语义。Desktop网格／详情7:10、列表实际48dp小封面、Android2:3保持。

复用边界：Lazy与Voyager／ScreenModel所有者保存临时浏览位置，不持久写scroll、不迁移last_used_category原索引协议；封面沿DesktopCustomCoverStore／DesktopCoverUpdater／rememberMangaCoverRequestState，不改cache／HTTP／代理；普通续读保持既有最早适用未读与全已读隐藏，不将旧同步已读章提升为新入口。RI05再做统一面板／列数迁移／评分，RI06再改选择输入，RI08再做详情完整布局，RI10闭环其余下载／阅读动作。本批尚未完成独立审查或提交。

RI04首组真实红绿：`interaction-ri04-root-red`于22:38:12–22:38:55 +08终态FAILED／exit1，3项分别因CompactGrid右侧拖动不能到Work0999、隐藏未读角标后续读入口缺失、唯一自定义标签缺失而正确失败。最小接线后`interaction-ri04-root-green`于22:40:52–22:42:26 +08终态PASSED／exit0，3项／0失败／0跳过；58.399s测试、总1m33s。夹具使用真实SQLite／DI／RootNavigator，四布局千本pointer拖至末并回顶，关闭角标真实按钮推Reader的chapter／page／index／refs及选择中不推；分类custom／show／search／default0实际语义通过。位置恢复、封面文件／Coil、独立审查及提交仍待完成，RI04不勾选。

位置／封面第二组：`interaction-ri04-position-cover-red-valid`中分类A返回未恢复自身作品锚点是正确业务红；封面最初合并语义定位为空以及Skia编码API编译错误是夹具问题，不作产品红。改用实际AsyncImage未合并节点并先确认源图已绘制后，`interaction-ri04-cover-red-key`以真实请求memoryCacheKey为空正确失败。`interaction-ri04-position-cover-green`于22:56:02–22:57:39 +08终态PASSED／exit0，5项／0失败／0跳过，测试71.295s：分类A／B、详情返回、四布局、重排／搜索；同owner源像素及Referer、版本键、自定义四布局优先、删除后最新源图、同URL再次换版本均通过。补充边界组仍在实施，尚无独立验收结论。

补充边界两轮整条仍FAILED，不改写为通过。`interaction-ri04-edges-green`于23:11:15–23:12:05 +08失败涉及像素尺寸取整、默认章节排序和Windows DOS只读未阻止unlink；旧明确受影响方法通过。修正夹具后`interaction-ri04-edges-green-valid`于23:15:13–23:16:04 +08，5项中3通过、2失败：真实封面四布局／重新owner、文件写入删除失败与详情反馈重试、同步目标过滤／外链／陈旧／全已读边界已绿；其余失败是PNG输出目录及ScrollBy误定位分类横向Tab，继续保留非零Lazy偏移和真实图输出要求修正夹具。Windows故障用临时文件NOSHARE_WRITE／NOSHARE_DELETE句柄，POSIX限制临时文件及父目录写权限，finally恢复，不操作系统或用户文件、不跳过。

实施者收口核对最终设计4.2发现Grid未读角标与List两种角标尚未消费固定SOURCE角色；在既定四布局展示范围内补实际像素红绿，未读secondary／onSecondary、下载tertiary／onTertiary。预计追加5–10分钟，不新增代理、审查、全量或构建。本批最终受影响验证及独立初审尚未开始。

`interaction-ri04-badge-red`于23:32:13–23:32:38 +08正确复现CompactGrid未读背景实际primary而非独立secondary像素。三处角色参数修正后，`interaction-ri04-cover-write-red`于23:39:49–23:40:38 +08整条仍FAILED，4项中角标像素和原生浅／深四布局2项通过，半写封面与非零偏移2项失败。半写测试经真实文件writer写入部分数据后抛IOException，旧文件被截断是正确业务红；拟在原store局部同目录暂存→原子替换修复，原子替换不可用时失败并保旧文件，不静默非原子覆盖、不新建事务系统。非零偏移在先前夹具定位修正后仍失败，原因尚未证实；主代理要求对照实际Lazy事件／bounds与model锚点，保留非零偏移及返回恢复断言。不能把尚未定位的失败继续按夹具问题消除。主代理已查看`ri04-library-layouts.png`原生离屏浅／深四布局拼图，网格与独立列表尺寸有实际bounds断言，千本拖动仍以事件测试为证据；这不代表正式运行或独立整体审查已完成。

### RI04 最终闭合与独立验收

非零偏移失败最终已证实是新夹具冻结帧时钟：ScrollBy返回true，但原生VerticalScrollAxisRange与实体bounds均未移动，model仍为0与实际一致。只读Compose 1.10.2 jar确认ImageComposeScene.render默认timeNanos为0；改用递增System.nanoTime后实际移动29px、model记录29。merged节点top被viewport裁剪，改以同实体bottom严格验证29px位移；保分类及详情返回的非零offset断言，不修改生产observer。`interaction-ri04-position-final-green`为1项／0失败／0跳过，实际测试12.889s，排序、重排、列数、resize、删除／筛选及分类删除同方法均绿。封面真实半写红后同目录暂存／原子替换绿，原子替换不可用不直接覆盖；Windows真实句柄权限故障和详情错误／重试证据保留。文件与DB失效仍是两个边界，不宣称跨存储原子；不将封面事务扩展挂入RI12分类／书架偏好范围。

最终明确受影响8类：`interaction-ri04-final-affected`于2026-10-01 00:03:12–00:06:08 +08整条FAILED／exit1，141项中138通过、2失败、1既有条件跳过；root spotless及临时init限定2新增Desktop文件的格式检查通过。Interaction10、CoverManager7、CoverAdapter5、SyncContinuation7、ScreenModel58、Parity16均全绿；Category17中的2项失败，PageComposition20通过＋1跳过。跳过是既有explicit non-release build的assumption，当前Release构建条件下不满足，不能计作已验证非发布行为。初始加载用户选index0后默认分类首次出现，期望0实际1是真实回归；生产categoryIndex仅loaded后保持ID，loading继续原索引协议。另一项旧分类事务夹具仍期待Dialog标题及露出的根Sort，按RI02真实普通Screen路径修正，保混合归属、冻结、取消及删除数据断言。

`interaction-ri04-initial-category-green`于00:08:21–00:09:31 +08终态PASSED／exit0，原2失败＋位置1＋ScreenModel58＋Parity16，共77项／0失败／0跳过，spotless通过。主代理独立核对发现旧事务fixture恒返回rootModel会隐藏child owner问题；改首个root实例、后续真实factory独立child，实际管理Screen、child与root不同、管理页隐藏Sort、返回root scope有效及Sort恢复均断言。最后`interaction-ri04-owner-fixture-final`于00:12:25–00:13:28 +08终态PASSED／exit0，仅事务与位置2项／0失败／0跳过＋格式；未整组重跑。新增Badge缩进作机械整理。初始守卫修复只影响首次分类投影，相关模型／投影及真实多类路径已复验，其余封面／角标／普通Reader绿色证据仍适用。

去重有效合并证据为140项通过＋1项原有条件跳过，保留所有FAILED命令原终态。主代理本批唯一独立初审及必要修正核验已闭合：核实际Factory必需章节依赖、Root事件及Voyager owner、真实Lazy锚点／offset、typed请求／实际图片像素／版本、半写与权限故障、普通未读与同步目标边界、四布局角色／比例、单分类和旧索引协议；没有新增代理、全量、模块完整或正式构建。最终受影响类修复按必要影响范围复验，不以新绿覆盖旧失败事实。

主代理已实际查看[原生四布局图](ri04-library-layouts.png)：Windows／JDK21.0.11／Compose1.10.2／Skiko0.9.37.4，density与fontScale均1；8幅实际1200×900离屏页面缩50%拼为2400×900，上浅下深，列序Compact／Comfortable／List／CoverOnly。实际已知绿色像素及Grid7:10／List48dp bounds独立断言；右侧滚动条能力以1000作品真实pointer往返证据确认，不将该图称正式发布或原版像素基准。POSIX权限分支保留RI18 macOS实跑门禁。

本批9个内聚代码／测试文件加原生图，超过估算400行主要来自10个真实DB／UI／文件集成场景及同一Lazy宿主重排，保正常格式与同一owner上下文，不机械拆批。主代理仅定点修5条当前roleEvidence行号，manifest历史来源、状态和原排版保留；必要checkoff与production／测试／文档／索引同批提交。最终全量及正式交付仍未执行，继续RI05。

## RI05：追踪评分、书架顶栏与统一面板

2026-10-01 RI04同批提交为`0b04ea6b49dc341dbf6f1b66886f4f37e53e471a`，提交后工作树干净。RI05继续复用原实施代理，主代理固定评分／偏好／导航接口、独立初审1轮及必要修复复审1轮；五组focused红绿及清理、稳定后1组明确受影响集成／格式，完整测试、模块完整与发布构建0次，预计2–3小时。无需额外技能或新报告／快照；失败只追加直接影响路径的诊断和复验。验收已固定到page-contracts的RI05节，尚无实施或测试完成结论。

当前 LibraryScreenModel 从已登录 tracker 的原始 track.score 直接 average；Desktop registry 的 AniList请求与解析存 POINT_100，MAL等为10分，Kitsu解析 ratingTwenty／2已为10分。Android现有 Tracker.get10PointScore／Anilist.get10PointScore明确 AniList除10。RI05应复用并共享这个投影契约，保持存储及远端回写原始分数不变，验证混合 provider真实HTTP→数据库→平均分排序，不在UI随意按最大值猜尺度。Android当前平均值对已登录有效tracker的get10PointScore结果做average，含0分；不能凭分数大小猜provider格式或随意改变0分语义。Desktop当前LibraryComponents只有评分排序名称，没有每本评分或无评分显示，RI05须核定稿A07的可见反馈。旧列数为DesktopAppPreferences的library_grid_columns，lazy首次访问才迁移旧desktop/app节点；共享横／纵键为pref_library_columns_portrait_key／landscape_key，当前LibraryPreferenceMigration仅迁移display／sort。RI05需要复用已注册AppPreferences的旧值读取和现marker迁移，保护各共享显式值（包括0自动），不因删除旧外观入口而漏掉尚未触发lazy迁移的旧值；这里是只读事实，不声明具体实现已完成。这里只确认当前链路与复用入口，尚无本批红绿或修复结论。

RI05搜索接口已只读核定：当前LibrarySettingsScreen已有共享portrait／landscape0–10控件，Appearance是legacy单控件；catalog只把旧desktop_appearance_library_grid条目挂Appearance，Library目前只有display／update区域标题。计划复用现LibrarySettings真实列数区，与面板Display抽共用控件，将旧搜索记录／锚点迁到LibrarySettings并保Title／alias／滚动／高亮；沿现普通Screen／anchor链，不新增Root跳转或默认页特例。这里固定接口，不声明修改或验证已完成。

RI05自定义周期边界已在实现前固定：共享showLibraryIntervalFilter要求非Release且MANGA_OUTSIDE_RELEASE_PERIOD限制启用，当前Release因此隐藏；共享EvaluateLibrary还以该限制决定真实过滤。默认限制集合已包含此项。Desktop新面板按定稿七项覆盖呈现门控，始终列出该行，限制关闭时禁用并说明条件，活动提示只计实际生效的偏好；保留Android可见性及共享执行算法。该呈现扩展记为PROJECT_POLICY，不冒称SOURCE已无门控；现有条件测试须保留共享／Android契约，新Desktop事件测试验证启用与禁用两条链路。此处是接口决定，尚无实现通过结论。

RI05评分首轮`interaction-ri05-score-migration-red`终态FAILED，迁移断言为正确业务红，评分方法首次等待活动服务超时只是夹具问题。`interaction-ri05-score-red-valid`于00:51:25–00:51:56 +08仍因DI缓存夹具超时而FAILED，不能计作评分业务红。使用既有initDesktopDIForTest的trackerServiceRegistry参数修正实际注册入口后，`interaction-ri05-score-red-di`于00:54:24–00:54:50 +08终态FAILED／exit1，真实AniList POINT_100的80及Kitsu十分制8经SQLite与实际factory得到44，期望8，已确认评分尺度错误的正确业务红。主代理曾把后来覆盖的共享test-results XML误关联到00:51记录，已按实施者与对应日志纠正，不将该归属错误作为新产品故障。此时评分及迁移尚未绿，面板仍待实施。

RI05首个基础绿`interaction-ri05-foundation-green-jvm`于01:07:27–01:07:42 +08终态PASSED／exit0，共共享评分契约1、真实HTTP→SQLite→factory评分1、迁移4，六项／0失败／0跳过。先前`interaction-ri05-foundation-green`因commonTest误用未配置的kotlin.test编译失败，按既有jvmTest／JUnit模式修正夹具，不计行为红。共享投影已接Android Base／Anilist与Desktop实际平均值，存储80／8和回写语义保留；主代理只读核接口未发现下游阻断。Android实际包装、旧列数lazy及故障重试、Root面板仍待实现／验证，不代表RI05完成。

RI05第二组`interaction-ri05-options-migration-red`于01:13:27–01:13:44 +08终态FAILED／exit1，10项中6通过、4正确失败：实际Root仍有独立Settings入口，顶栏筛选和Home书架重选均没有三页统一面板（期望3、实际0），畸形legacy列数BROKEN被getInt默认3误导入。真实旧desktop/app lazy读取、各共享显式0优先、横纵／marker写前写后六种失败的重试与旧值保护已有绿色证据；面板接线及畸形值修复继续，不将整条FAILED改写为通过。

RI05保存故障组`interaction-ri05-options-write-red`于01:26:41–01:27:03 +08终态FAILED，实际筛选点击抛SecurityException及畸形shared列数BROKEN回默认0而未导入旧6是正确失败。复用RI03单Preference恢复边界并按原始值校验共享列数后，`interaction-ri05-options-write-green`于01:30:53–01:31:56 +08终态PASSED／exit0；写前／写后故障仍按权威旧值、原unset状态和可见错误反馈检验，不宣称跨DB／偏好多存储原子。自定义分类SQLite排序失败与提交后错误仍待补证。

RI05服务名／搜索组`interaction-ri05-rating-search-red-valid`于01:37:28–01:37:52 +08终态FAILED／exit1，2项正确失败：实际Root面板仍显示数字Tracker而非AniList／Kitsu名称，旧列数搜索仍到Appearance而非共享Library设置区域。前`interaction-ri05-rating-root-red`仅因Kitsu夹具缺必需included失败，不计业务红。重选场景已换真实Navigator(HomeScreen())，不再在测试lambda复制production onSelect；新真实宿主基础绿色对应`interaction-ri05-options-migration-green-valid`（01:21:56–01:22:25 +08，PASSED／exit0），此前同组lambda类型编译失败不计行为红。命名、评分可见投影及搜索接线继续实施。

RI05评分／搜索`interaction-ri05-rating-search-green-valid`于01:45:26–01:46:11 +08终态PASSED／exit0，两项／0失败／0跳过：14个真实形状HTTP响应及账号格式经production parser／SQLite／实际factory／Root，在四布局显示8.0／10、0.0／10与明确未评分，实际评分排序、服务名、单服务“已追踪”、注销及活动筛选提示；旧列数搜索进入LibrarySettings的两个共用滑块，实际写共享0／10而未双写legacy。前同组重复supportingContent编译错误保留为夹具／代码编译修正，不计业务红或测试绿。排序SQLite及原生键盘／菜单范围仍待验证，Android包装和稳定后受影响检查尚未执行。

RI05随机／还焦组`interaction-ri05-focus-random-red`于02:08:05–02:08:36 +08终态FAILED／exit1，正确失败分别是持久随机seed变化未重组真实Root卡片、无障碍点击关闭面板后仍回旧Search。实际seed已接Preference→LibraryState→Root remember依赖，Options FocusRequester由Root持有、关闭次帧恢复入口或仍存在的Root后，`interaction-ri05-focus-random-green`于02:25:55–02:26:49 +08终态PASSED／exit0，命令限定原生模态与随机重选两个方法。双向Tab限制在当前Dialog owner内验证，不把多个原生owner各自Focused误认为焦点逃逸；真实SQLite拒绝／提交后抛错恢复局部排序位及保留并发非排序位的场景已由实施者报告通过，稳定后证据仍待最终核对。原生菜单、尺寸／主题／200%字体、Android包装及受影响格式尚未收口，本批保持未完成。

RI05原生补证`interaction-ri05-native-menu-green`于02:38:18–02:38:52 +08整条仍FAILED／exit1，三项中两项通过：320dp／200%字体、三页独立滚动、窗口／主题变化下焦点与末控件可达；Desktop真实CloudSync绘制。共享SyncToolbarButton保留Android默认Sync图标，仅Desktop传组合图标。更多方法已越过Escape还焦及真实完整分类／全库scheduler范围断言，后段因夹具未先聚焦同步sheet内控件便发Escape失败，实施者正在按实际sync-close的RequestFocus修正夹具，不扩同步算法。先前PNG输出IOException及节点定位NoSuchElementException不计新增行为红；组合图标差异是实际像素红。主代理已查看当前320／font2的Filter及Display原生图，两图实际呈深色且会话内滚到末端，不能因文件名无dark后缀称浅色；正式发布、硬件及最终整个批次验收仍未完成。

RI05明确受影响`interaction-ri05-affected`于02:58:40–03:04:32 +08终态FAILED／exit1。Desktop九个限定类127项，8失败、1既有条件跳过；同命令共享评分、共享同步工具栏及Android实际Anilist／Kitsu包装各1项通过，root spotless与scoped新文件格式通过。主代理读取对应XML并核timestamp全部在同key时窗内，共130项＝121通过／8失败／1跳过，不把整条失败改成全绿。新增Options12、Migration8、Score1、Appearance18、SearchWiring24均通过；Category17中1失败、Interaction10中1失败、PageComposition21中4失败1跳过、Parity16中2失败。

本批同一独立初审发现搜索行为回归：重写Toolbar误删action_reset清空文本并保持搜索展开，该能力与定稿移除“清除筛选”不同，要求恢复实际输入清空链，不能以关闭搜索替代原断言。另要求区分allItems真空库的getting_started_guide与非空库经有效globalDownloadedOnly筛后NoMatch；后者实际约束／局部偏好锁定的新事件测试继续保留，不从hasActiveFilters删除全局条件以通过旧夹具。其余失败按定稿将Root直接Sort／Refresh旧入口迁到Panel／More，保原数据、任务范围、owner及位置断言。待原实施者完成必要修复，仅复验失败及直接影响方法，未改动绿色组复用；未增加代理、审查轮次、全量或构建。

RI05原8失败修复`interaction-ri05-repair-affected`于03:11:38–03:12:45 +08终态PASSED／exit0，命令以12个选择器限定失败及直接影响方法＋格式，实际14项通过。主代理随后核当前Root真空库修正删掉整个旧hasActiveFilters门控，会连真实局部未读／追踪筛选也错误显示guide；SOURCE Android LibraryTab:173和LibraryScreenModel:172–185区分局部活动条件、全局下载另行约束。要求复用局部有效谓词，guide仅无搜索、真空库且无有效局部条件；总提示仍global OR local，保非空99源NoMatch。`interaction-ri05-local-empty-red`于03:17:35–03:17:49 +08终态FAILED／exit1，一项正确复现局部空库NoMatch被guide替代，正在最小修复。另原生图审阅发现普通Row中的三Tab缺独立当前页指示器，键盘focus停Filter而正文Display时易混淆；要求核SOURCE TabbedDialog并复用原生选中页反馈，补直接可见状态红绿与原滚动／200%可达验证，未扩大业务或测试范围。以上均为本批同一初审及必要修复核验，尚未独立验收闭合。

RI05初审边界`interaction-ri05-review-boundaries-green`于03:24:16–03:25:11 +08终态PASSED／exit0，7项／0失败／0跳过：原生5及空态2。局部谓词hasActiveLocalFilters复用有效周期／活跃tracker条件，总hasActiveFilters为global OR local，guide门控保SOURCE局部语义；共享PrimaryTabRow提供当前页颜色及indicator，键盘focus可独立停其他页。前`interaction-ri05-tab-indicator-red`实际primary指示器像素期望FF0058CA而旧背景FFE3…正确失败，不用源码字符串代替可见反馈。主代理已重看更新后的Display320／200%浅色原生图及320深色More图：当前Display蓝色文字／下划线与焦点区别清晰、末选项及Close可达；紧凑More边界、搜索／面板／CloudSync／More次序可见。图是测试原生离屏场景，空占位封面及短任务提示不作为正式网络图片、最终更新流程或发布runtime证据。格式后原7方法必要复验及结构化交付仍待结束，尚未checkoff或提交。

### RI05 最终闭合与独立验收

原实施者六字段回执确认停止写入、未提交、全部协调器无STARTING／RUNNING，原session52740已消费、PID8980结束。格式后`interaction-ri05-final-boundaries`于03:36:41–03:37:37 +08终态PASSED／exit0，原生Options5及PageComposition2共7项／0失败／0跳过，同命令root与scoped spotlessCheck通过。主代理核实际产物及对应证据，完成本批唯一初审和必要修复复审：共享原始评分尺度、实际registry／factory／DI、Root与Home重选、三页原生事件、真实SQLite及Preference故障／恢复、列数旧值与搜索、随机卡片、菜单范围及还焦、空态局部门控、选中页像素与200%可达均闭合。没有以源码索引检查代替行为验证。

最终去重有效证据Desktop129项＝128通过＋1既有non-release假设跳过，共享Domain／Sync／Android实际包装各1通过，总132项＝131通过＋1跳过。跳过仅既有explicit non-release build exposes custom interval方法，Release的IS_NON_RELEASE_BUILD不满足；新Desktop面板门控由真实事件验证，Android共享可见性仍保SOURCE。初次affected及各失败命令保持原FAILED，不称一次全量通过；未改动绿色结果按影响复用，只复验修复及直接路径。全量、模块完整、正式构建与运行仍0次。

五张原生离屏产物：[Filter浅色320／font2](ri05-options-filter-320-font200.png)、[Display浅色320／font2](ri05-options-display-320-font200.png)、[Display深色320／font2](ri05-options-display-dark-320-font200.png)、[Sort深色](ri05-options-sort-dark.png)、[More窄窗](ri05-toolbar-more-320.png)。Windows／JDK21.0.11／Compose1.10.2／Skiko0.9.37.4、English、density1；前3张320×680／fontScale2，Sort1200×900／font1，More320×680／font1／SYSTEM实际深色。Filter／Display在会话末端滚动状态；主代理实际查看最终Display浅色及More深色，结合真实像素／bounds／事件核验，不作为正式发布、远端封面或硬件证据。

本批跨共享评分、Desktop统一面板及列数迁移／搜索、Android包装保护等内聚上下文，超过8文件／400行，保完整测试和正常格式，不机械拆分或另建路由、列数、客户端、调度器。列数迁移逐键幂等可重试，不称多键原子；排序恢复只补偿本次sort mask，保并发非排序flags。RI04位置／封面／续读继续保护。主代理仅定点修7条当前roleEvidence行号，273条当前symbol索引核对通过，保历史来源／状态／原排版；必要checkoff与production／测试／文档／原生图同批提交。继续RI06，物理输入及最终发布门禁仍在RI18。

### RI06 选择前置核对（只读，不代表实施完成）

现共享LibrarySelectionPolicy被Android LibraryScreenModel.toggleRangeSelection真实调用，默认追加范围且把锚点移动到目标；Desktop LibrarySelectionState也调用相同方法，现handlePrimaryClick只有Shift参数，ShiftAwareClickModifier只记录Press时Shift，不能覆盖Ctrl／Alt／主按钮与长按后click规则。RI06应在同一共享闭区间计算中显式传Desktop替换／追加及固定起点策略，保Android现默认行为；陈旧目标须在真实事件进入选择前按当前有效可见集验证，隐藏书架选择与章节裁剪接口不能混为一套策略。现ChapterSelectionState无锚点，RI09再消费共享范围计算，不提前改其用户行为。

原版CommonMangaItem的网格选择为secondary实色外框、内padding4dp、封面alpha0.76；列表selectedBackground为secondary浅色alpha0.22／深色0.16，封面不降低透明度。Desktop目前网格primaryContainer、列表primaryContainer0.4与定稿不符，RI06须用实际绘制像素及真实四布局事件闭合，不以字符串或图标名扫描代替。Ctrl滚轮250ms分段、输入／IME／模态／子页及修饰键排除属于Windows adapter；真实物理设备／DPI门禁仍在RI18，不把离屏事件冒充硬件证据。这里只定位下一批复用接口，无实施或验收结论。

## RI06：Windows选择、分类滚轮与批量动作

2026-10-01 RI05提交为`06c15f0480a2b0873ea5ff50155c2d892683324e`，提交后工作树干净。继续复用原实施代理，主代理固定共享范围／Windows输入及数据结果边界、独立初审1轮及必要修复复审1轮；预计6–8簇focused红绿、1组明确受影响集成／格式，预计4–6小时。全量、模块完整、正式构建0次，交付同一功能批production／tests／必要契约和证据／索引／提交，不新增技能、代理、报告或逐任务快照。真实硬件输入在RI18统一验收。失败追加仅直接影响路径的诊断和复验，不自动扩大到全量。

`interaction-ri06-selection-red`于04:00:04–04:00:28 +08终态FAILED／exit1，仅LibrarySelectionStateTest三项且三项正确失败：Shift收缩仍残留旧可见选择、隐藏或跨分类锚点未按目标重建替换集、陈旧主点击仍打开已删除目标。先修同一共享闭区间策略，Android默认Append＋Move保持；Windows显式Replace／Append＋Keep，陈旧目标由当前有效可见集守卫。真实pointer／滚轮／批量动作尚未验证，没有本批绿、独立验收或完成结论。

RI06第一、二簇：`interaction-ri06-selection-green`于04:03:44–04:04:09 +08终态PASSED／exit0，Desktop状态3＋共享策略5＝8项／0失败／0跳过。`interaction-ri06-pointer-red`于04:08:01–04:08:18 +08终态FAILED／exit1，仅两个实际事件方法，分别在Compact布局Ctrl点击和Alt点击错误推入Detail处正确失败；普通主点击已经打开详情，证明目标定位有效。接线修正后`interaction-ri06-pointer-green`因实验API opt-in编译失败，不计业务红或绿；`interaction-ri06-pointer-green-valid`于04:12:51–04:14:00 +08终态PASSED／exit0，两个四布局真实Press／Release方法＋状态3，共5项／0失败／0跳过。覆盖Ctrl／Shift扩大收缩／Ctrl+Shift／多选普通点击清零、Alt／次键及700ms长按释放抑制click。原exec已消费、对应进程结束，第一组共享默认Append＋Move保持；Ctrl滚轮、批量动作、SOURCE选择绘制及整体独立审查尚未闭合。

RI06滚轮首切组保留失败过程：`interaction-ri06-wheel-red`为1项正确业务失败；最小根内容adapter后`interaction-ri06-wheel-green`已越过换类后续段／相邻事件更新时间／精确250ms／反向，却在释放再按Ctrl、没有中间滚轮事件处失败。`interaction-ri06-wheel-green-location`于04:30:58–04:31:16 +08仍FAILED／exit1，1项仍为期望Wheel A、实际Wheel B；nativeKeyLocation及真实RequestFocus／frame校正不足以解释或解决全部失败，不能称仅夹具问题。沿现LocalWindowInfo.keyboardModifiers及窗口焦点重置段后，`interaction-ri06-wheel-green-window`于04:33:18–04:33:52 +08终态PASSED／exit0，原同方法1项／0失败／0跳过，保按键身份、无中间滚轮及消费边界断言。这里只闭合根owner分段、首尾不循环、不触更新任务；输入／IME／模态／子页排除及其他RI06能力仍待验证。Windows真实硬件及发布runtime仍在RI18，不以NativeScene窗口信号夹具代替实机。

RI06分类契约来源核对：Android ChangeCategoryDialog及共享CheckboxState对初始全选／空使用State二态，对初始混合使用TriState的混合→空→勾选→混合；C21明确验证混合循环，C18验证三个初始状态及混合确认保成员。page-contract将先前简写“全选／空／混合循环”明确为这套实际来源，保全部原定验收，不为实现结果降低要求，也不新增初始二态强制产生混合的行为。整行可点击和纵向滚动同为SOURCE当前组件能力，继续在本批真实Dialog事件中验收。

RI06滚轮排除及快速释放：`interaction-ri06-wheel-guards-red`为缺TestTag helper的编译失败，不计业务红。`interaction-ri06-wheel-guards-red-valid`于04:37:45附近至04:38:09 +08整条FAILED，2项中模态路径1通过、修饰键1因Ctrl＋Alt分类0→1正确失败。`interaction-ri06-wheel-guards-green`于04:40:48–04:41:37 +08整条FAILED，3项中模态及分段2通过，搜索失焦后切类1失败；`interaction-ri06-wheel-focus-release`于04:44:52–04:45:16 +08 FAILED，2项失败，严格Focused断言确认搜索实际上未失焦，不能将该夹具失败归为生产guard故障；另Rail焦点下keyup→keydown没有中间render时，期望Wheel C实际Wheel B是正确业务红，证实仅LaunchedEffect窗口状态会合并瞬态释放。

沿现HomeNavigationHost可选Modifier、HomeScreen父preview key及LibraryNavigationHost注册／注销模式同步转发Ctrl不再按住的KeyUp，回调只重置当前Root段且return false；原WindowInfo失焦及有帧补偿保持，不建OS全局监听。Search夹具改真实Tab并保严格Focused=false断言。`interaction-ri06-wheel-focus-release-green`于04:48:06–04:48:48 +08终态PASSED／exit0，原两个方法／0失败／0跳过；无帧快速释放、Rail真实焦点、真实搜索编辑排除及失焦后保query切类、Alt／Shift／横向／工具栏／Rail均通过。此前模态方法的绿色证据复用，完整批次稳定后再做明确受影响检查。SOURCE选择绘制、原版多选UI及批量结果仍未完成。

RI06选择绘制与上下栏：`interaction-ri06-selection-ui-red`于05:01:17–05:01:37 +08 FAILED／exit1，三项正确失败分别为网格选框没有固定4dp内边距、选择栏没有纯数字计数、全本地仍显示不可用下载占位。随后一次实验API缺opt-in的编译失败不计行为证据；`selection-ui-green-valid`及`selection-ui-green-final`仍整条FAILED，先后闭合全本地／混合动作分支和More→Escape还焦→第二次Escape退选择，剩余严格像素失败保留。静态Surface替换及额外等待都没有消除选框8%交互叠层，不能据此声称旧Card或短暂ripple是已证实原因。将真实pointer移到空区域后选框角色、封面alpha及固定padding通过；剩余浮点比例夹具改为实际整数测量height＝round(width／0.7)，保7:10来源算法、颜色和alpha严格断言。`interaction-ri06-batch-red`于05:14:49–05:15:31 +08整条FAILED，但其中四布局×显式LIGHT／DARK方法完整通过，包括系统DARK下应用LIGHT、网格仅封面降alpha和List48dp／浅深选底；不是原版截图基准或发布runtime证据。

RI06真实批量失败／重试：上述`batch-red`的分类与删除是正确保选失败，标记测试首次全scene文本“1”误匹配角标，不作业务红。`interaction-ri06-mark-red-valid`于05:16:17–05:16:34 +08在实际选择栏／Close入口证明保选失败；数据和错误反馈断言保持。`interaction-ri06-batch-green`于05:19:21–05:19:49 +08 PASSED／exit0，三个实际Root＋SQLite方法／0失败／0跳过：SQL拒绝后数据保持、可见错误和有效选择保留，移除故障后重试真实数据库成功才清选。复用既有分类BatchResult／下载结果，标记与删除最小返回成功值，不靠提示文案推断结果；下载深队列和文件生命周期仍交RI10。

RI06异步所有权及全库有效集：`interaction-ri06-owner-red`于05:22:33–05:22:54 +08 FAILED／exit1，两项正确失败为旧标记操作完成后清掉后来新选择，以及真实作品退出书架后保留失效ID。真实ChapterRepository门闩固定旧工作集，已先确认旧章节标记／新章节不动；跨分类、隐藏选择及反选此前断言保留。`interaction-ri06-owner-green`于05:24:48–05:25:22 +08 PASSED／exit0，两项／0失败／0跳过。局部selection revision仅保护这次异步完成的清选，当前加载的全库ID裁除实际失效目标，无变化裁剪不增加revision；不按当前可见分类误裁隐藏选择，不新增任务系统。分类Dialog整行／循环、陈旧卡片回调、删除快照及本批最终受影响检查仍待闭合，整体独立审查未开始。

RI06分类加载夹具与整行红测：`category-stale-red`及`category-stale-red-valid`分别保留NoSuch／加载未完成的失败；后者旧body回调实际将选择1变2为正确陈旧目标业务红。`interaction-ri06-category-stale-boundary`于05:34:50–05:35:31 +08整条FAILED，两项中旧body／long／continue对过滤和删除的守卫方法通过，分类仍没有行。后续有界诊断确认model及Dialog真实repository两目标读取均start／done，仍只有Progress和禁用OK，不归因为SQL阻塞或缺tag；补Snapshot通知未解决，相关FAILED终态保留，不写成已证实通知丢失。改NativeScene从Unconfined使用当前runBlocking事件循环后，`interaction-ri06-category-load-mainloop`于05:43:28–05:43:45 +08 FAILED／exit1，匹配XML时间21:43:38 UTC；已越过加载断言且真实checkbox pointer完整循环通过，分类文字整行点击期望Off、实际Indeterminate为正确业务红。该夹具上下文修正不修改production状态或算法，继续最小整行／滚动实现，分类整体尚未绿。

RI06整行及陈旧类别补验：`interaction-ri06-category-boundaries`于05:53:51–05:54:48 +08整条FAILED／exit1，匹配XML21:54:08 UTC三项中整行／混合循环方法1通过，长列表方法已越过真实ScrollBy、末项bounds及双向Tab后，关闭焦点全scene single定位到多个owner而夹具失败；不计该异常为生产焦点故障。主代理同一初审指出旧body／long／continue回调虽按最新有效集合守卫，仍捕获旧categoryId；扩展既有stale方法为目标仍属于新分类后执行Shift，实际范围与当前分类锚点不符，是正确业务红。继续让分类ID与有序集合来自同一份最新model状态，保持同一局部选择owner与共享策略，不新增事件系统。C21沿原验收补first归属A＋B、second归属B，使开框同时出现混合与全选，Escape保归属／选择并还焦，重新确认混合保持；下载菜单必须执行Root→真实队列→清选，不能只以入口存在或helper回调代替。整体初审及剩余绿测仍未闭合。

`interaction-ri06-category-focus-red`于05:57:02–05:58:00 +08 FAILED／exit1，匹配XML21:57:26 UTC，三项中旧有效目标跨分类回调方法已通过；C21混合＋全选及长列表两个方法均在实际“设置分类”触发器Focused=false处正确失败，已不是旧全scene多owner定位异常。沿现局部FocusRequester／关闭请求补最小还焦接线，保Escape只退一层、取消不写与选择保留；本批同一初审的焦点项尚未闭合，不把此前列表和循环绿测外推为整个弹窗通过。

RI06原生下载／无帧滚轮：`interaction-ri06-native-final-red`于06:01:25–06:02:25 +08 FAILED／exit1，XML22:01:51 UTC四项均失败。滚轮同方法补向下后立即反向向上、无中间render，期望Wheel B实际Wheel A，正确复现composition旧index；回调改读最新model类别与index。新增下载方法通过实际Root六个菜单／SQLite章节／production factory与manager队列，失败对照临时恢复旧clear-before边界验证读取异常丢选，不伪记为未经改动的初始源码首红；没有更改下载算法。正式clear-after恢复后，`interaction-ri06-native-final-green`于06:04:01–06:05:07 +08整条FAILED／exit1，XML22:04:18 UTC四项2通过（真实下载失败保混合有效对象、六种工作集及成功清选；无帧反向及原分段边界）＋2分类关闭／还焦失败。诊断没有关闭后的return-focus日志，长列表仍有旧modal Cancel焦点，尚未证实单纯FocusRequester时序原因；继续先核实际关闭回调／show标志和owner，再核还焦，不能盲加延时通过。所有原FAILED终态保持。

`interaction-ri06-native-close-green`于06:09:04–06:10:30 +08整条FAILED／exit1，匹配XML22:09:23 UTC，六项5通过：C21实际跨类混合＋全选、三态／二态及混合确认保成员，22分类／320dp／fontScale2真实滚动末项与双向Tab、Escape实际关闭owner／还焦，六个Root下载菜单到持久队列，滚轮无帧反向与原分段，打开删除固定ID集合及真实文件拒删＋第二作品SQL拒绝后的失效裁剪／隐藏有效重试／新作品保护。分类沿RI02显式Escape→onDismiss→consume及Cancel真实初焦点完成关闭、还焦，不以加延时解释旧失败。仅删下载方法1失败为点击遮罩正常关闭后继续读取旧modal空列表的NoSuch夹具错误，继续保背景不导航／数据不变、取消不写、只删下载保收藏断言修正路径，仅复验这一项。真实文件故障建立位置收紧到SQL触发器创建后的try紧邻处，保证建立即finally恢复，不扩大文件引擎。整条失败不改写为通过，Android及最终明确受影响／格式仍待完成。

RI06删除及Android接线：`interaction-ri06-delete-android-green`于06:13:48–06:14:22 +08 PASSED／exit0，Desktop原生只删下载1项和Android实际LibraryScreenModel包装1项均通过。Android方法实际执行toggleSelection／toggleRangeSelection，并验证默认追加及锚点移动，没有只测共享函数或改变Android生产语义。删除资格复核SOURCE containsLocalManga：含任一本地作品时隐藏删下载项，混合下载仍只入队远端子集；不把两种适用性混为一谈。

RI06锚点与格式：`interaction-ri06-anchor-red`于06:16:26–06:17:01 +08整条FAILED，状态方法正确复现取消勾选的有效锚点被其他失效ID裁剪误清（期望3／4、实际4）；同时原生绘制方法通过并生成本批图片。最小修正为非空保留集＋锚点仍在全库有效ID，最终受影响组该状态4项全通过。格式先有scoped失败和max-line-length定位过程；临时格式物化不计完整规则通过，规则恢复后`interaction-ri06-format-clean`于06:28:02–06:28:06 +08 PASSED／exit0。保留原失败，不将机械格式诊断计为行为红。

RI06稳定受影响组：`interaction-ri06-affected`于06:31:01–06:37:42 +08终态FAILED／exit1；Desktop明确9类116项中111通过、4失败、1既有非Release条件跳过，domain共享范围策略5项通过。四失败均为旧标记／删除入口或旧数量文案定位，保SQLite数据、导航owner、作用域、焦点和生命周期断言，只迁移操作路径。`interaction-ri06-affected-repair`于06:48:53–06:49:45 +08 PASSED／exit0，仅复验四失败方法（Category1、Options2、Page1），匹配XML时间22:49:10／13／41 UTC，0失败／0跳过；完整scoped格式规则及root／domain／app／Desktop的spotlessCheck通过，其余绿色方法复用，不重跑整组。合并有效去重证据为Desktop116＝115通过＋1既有条件跳过，共享5通过、Android实际包装1通过，总122＝121通过＋1跳过；不是全量证据。

RI06视觉为[四布局选择浅深色离屏图](ri06-selection-layouts.png)，主代理实际打开检查。Windows／Temurin JDK21.0.11、Compose1.10.2／Skiko0.9.37.4，density1／fontScale1／English，八幅1200×900真实Root场景按半尺寸排成2400×900，列Compact／Comfortable／List／CoverOnly，上LIGHT下DARK；包含系统DARK下应用LIGHT的反向信号。真实像素断言独立覆盖secondary实框、padding4、仅封面alpha0.76和列表48dp／浅深选底，不将当前图片作为上游像素基准、硬件输入或正式发布证明。

本批超过8文件／400行仍是同一Root交互与数据结果边界：共享范围策略、四布局pointer、根owner滚轮、上下选择栏、批量Dialog／异步结果及Android包装保护须一起编译验收。风险集中在输入抢占、陈旧目标、异步清选和部分数据／文件失败；主代理同一独立初审及必要修正核验逐项检查实际production、真实事件与SQLite／文件证据，没有增加第二个实现代理、独立审查轮次或全量运行。当前roleEvidence只修本批5处唯一符号定位漂移，历史fixedOriginal和actionInventory来源保留；索引只用于可定位性治理，不作为行为验收。

## RI07：章节设置、默认策略与实时投影

RI06已提交`8d66e8d03e74af4a3948ee450813b62b6899ace5`，提交后工作树干净。继续复用原实施代理；实施前固定契约已随RI06提交保存于page-contracts末尾，主代理负责共享接口、独立初审1轮及必要修复复审1轮。预算为约五簇focused红绿、一次明确受影响集成／wiring／scoped格式，预计3–5小时；全量、模块完整、finalParityAudit、正式构建0次。交付同批生产／测试／必要契约、单一报告与索引／提交，不创建第二套章节偏好或下载器；追加仅具体失败影响路径。

首选复用Manga.chapterFlags、SetMangaChapterFlags、LibraryPreferences六默认及SetMangaDefaultChapterFlags；实际factory位于`app-desktop/src/main/kotlin/mihon/desktop/library/MangaDetailScreenModelFactory.kt`。共同过滤／排序从Android真实ChapterFilter包装进入共享核心，平台仅供下载状态。统一三页模态、三态与权威持久值、显式批量默认、扫描组草稿、真实chapter number缺口、mounted repository／queue／file状态响应和章节可见裁剪为本批范围；RI08完整详情布局与头资料、RI09完整选择、RI10下载／文件动作仍由原责任批次闭环。当前红绿与接口核对按下列key记录；本批尚未收口。

`interaction-ri07-projection-red`于07:05:27–07:05:44 +08 FAILED／exit1，仅两个既有production API方法，正确失败为同作品外部CHAPTER_SHOW_READ更新后filterShowUnread仍true，以及8／8.5／3降序列表遗漏最低章之前missing2。对应focused filters为`*MangaDetailScreenModelTest.same manga repository*`与`*MangaDetailChapterRowsTest.descending final recognized*`，无编译或夹具失败，worker43788／Gradle52184已结束并消费。继续最小修权威flags同步与最低实际章节尾部缺口，尚无整体通过。主代理前置接口核对要求共享默认结果不能被Android忽略而静默改变异常／取消边界；Boolean拒绝与异常分别测试，不声称多偏好／DB原子事务。

`interaction-ri07-projection-green`于07:06:43–07:06:55 +08 PASSED／exit0，原两个方法通过并消费，最小修同作品权威flags同步及降序最低实际章前缺口。随后foundation首次漏runBlocking是夹具编译失败，不计业务红；`interaction-ri07-foundation-red-valid`于07:09:28–07:09:39 +08 FAILED／exit1，XML23:09:37.937 UTC实际两项。locale collator排序期望[2,1]、实际[1,2]为正确业务红；DI方法先在core InMemoryPreferenceStore.getStringSet的TODO、经extension初始化失败，未到共享默认binding解析，不计DI红。主代理已要求复用完整隔离PreferenceStore修setup、再仅原DI方法确认正确未绑定失败，不改core夹具扩大产品范围。

默认方法首次runBlocking末表达式返回Exception导致JUnit未发现，补显式Unit后只运行尚未有效红的默认／DI两方法。`interaction-ri07-foundation-default-di-red`于07:11:16–07:11:27 +08 FAILED／exit1，匹配XML23:11:25.085 UTC，2项正确失败：repository拒绝默认flags写入后await返回Unit而非可反馈的false，真实production DI未注册SetMangaDefaultChapterFlags。既有隔离完整PreferenceStore初始化已完成，未再遇core TODO；此组不重复先前collator红。异常边界的后续断言仍需绿测到达，不能提前宣称已覆盖。

`interaction-ri07-foundation-green`于07:13:03–07:13:29 +08仍FAILED／exit1，3项中单本拒绝／原异常及locale排序2通过、默认binding仍缺；保原终态。批量拒绝新增方法在原Unit／忽略拒绝边界对照下，`interaction-ri07-default-batch-red`于07:14:57–07:15:24 +08 FAILED，2项中真实DI已通过、awaitAll拒绝未抛为正确红。`interaction-ri07-foundation-green-final`于07:16:01–07:16:17 +08 PASSED／exit0，实际4项／0失败／0跳过，接口为await:Boolean、awaitAll保Unit并拒绝首错停止，原异常／取消不捕获，绑定使用真实LibraryPreferences。Desktop排序adapter消费共享getChapterSort，保SOURCE方向及locale collator。主代理同一初审已读实际接口／DI／测试与协调器证据，允许下游使用；Android实际包装与取消契约仍须在本批补齐。

`interaction-ri07-native-projection-red`于07:17:57–07:18:12 +08 FAILED／exit1，实际两个原生方法正确失败：真实详情章节设置点击后没有筛选／排序／显示Role.Tab，全局downloadedOnly设true后mounted远端Chapter Alpha仍存在，而局部raw flags0须保持。继续独立即时持久化红测后最小面板／共享过滤接线，尚无原生绿或整体完成结论。

RI07原生偏好写入：`interaction-ri07-native-preference-red`于07:21:04–07:21:18 +08 FAILED／exit1，实际书签偏好事件的持久化断言失败；后续绿测已覆盖旧XML，原红结果以对应协调器log及当时方法回执核对，不把新XML配给旧key。`interaction-ri07-native-projection-green`于07:25:36–07:26:18 +08 PASSED／exit0，Desktop三项／0失败／0跳过，实际三Tab、mounted全局下载覆盖入出及局部flags保持、书签事件SQLite持久化通过；同命令domain共享过滤两项通过，XML23:25:47.969 UTC，包含六种include／exclude及global覆盖／本地资格。尚未完成默认、扫描组和队列／文件实时全部边界。

`interaction-ri07-native-settings-red`于07:28:22–07:28:43 +08 FAILED／exit1，四项中默认入口缺失、扫描组入口缺失及外部筛选后章节选择未裁剪为三项正确红；display拒绝夹具在DI初始化后追加binding，被已缓存SetMangaChapterFlags实例遮住，实际写入成功，不计拒绝业务红。改为真实SQLite创建后、domain／factory解析前的可选mangaRepositoryOverride，默认null且沿已有chapter／category测试端口模式，不改production仓库和core PreferenceStore。`interaction-ri07-write-failure-red`于07:31:56–07:32:31 +08 FAILED／exit1，两项正确失败：真实display写拒绝没有失败反馈，以及补偿阶段取消被当成普通失败。`interaction-ri07-write-failure-green`于07:35:10–07:35:35 +08 PASSED／exit0，XML23:35:29.109／34.817 UTC，两项／0失败／0跳过；真实拒绝→可见反馈→重试、post-commit异常仅恢复display mask并保并发bookmark，以及补偿CancellationException传播通过。它不是跨SQL／偏好的原子事务证明，默认／扫描组后续仍需实际确认／取消／六默认与已有收藏显式应用的事件证据。

`interaction-ri07-defaults-scan-boundaries`于07:42:32–07:43:22 +08整条FAILED／exit1；XML23:43:04.679／21.734 UTC，五项中三项通过（真实默认取消／六值保存／显式favorite批量／非favorite保持／单本重置，扫描组SelectAll／取消／确认／重置驱动mounted列表，以及外部flags隐藏后选择退出）。初次权威repo读取异常仍逸出而没有产品反馈为正确红；未知chapterNumber=-1但name含数字时生成六个缺口（测试当时期望0）的断言前提，随后因已有缓存卷号恢复能力纠正，不计有效业务红。原整条失败保留，继续同一失败／投影簇最小修正；三项绿不外推为完整默认失败恢复、扫描组失败重试或所有缺章顺序已闭合。

RI07同一初审检查共享SetMangaChapterFlags写整个snapshot时的并发风险后，`interaction-ri07-concurrent-gap-red`于07:49:58–07:50:14 +08 FAILED／exit1，两个定点真实方法实际失败，XML23:50:07.846／13.231 UTC；并发断言为正确业务红，缺章方法的总量前提随后按既有缓存恢复能力纠正。有界repo门闩下交错设置导致第二字段NUMBER被旧snapshot覆盖为NAME（1048576→0）；当时按raw数值的缺章方法包含可解析未知名，行缺口总和88而raw共享总量5；5不是纠正后的验收值，不能以该差值外推正确缺口。最小修正限当前详情model设置串行边界内重读权威值、缺章按统一effective recognized投影计算但保实际行序；不修改共享数据库协议、Android现长按语义或下载算法。新增为本批即时保存及缺章既定边界，非增加独立审查轮次；这两项尚未绿。

RI07验收前提纠正：主代理读取旧`falls back to chapter name recognition for cached unrecognized volume numbers`方法，确认Desktop已有raw=-1时通过共享ChapterRecognition恢复“第22／16卷”的缓存能力。按AGENTS保护Desktop独有能力，前述一律忽略可解析名称的新增期望过严；保留实际失败记录，但不将其作为业务红。修正为一次effective recognized-number投影同时供shared missingChaptersCount总量和calculateChapterGap行提示消费，不持久化改目录、不复制公式。真正不可解析未知仍无缺口；重复／小数及非数字排序保持显示序和总量一致，旧卷号能力保护与正确总量断言须重新核验。此纠正不是以实现失败取消必做项，两处一致及最低章缺口要求保持。

`interaction-ri07-file-total-red`于07:52:38–07:53:04 +08 FAILED／exit1，两个真实mounted方法正确失败：production provider完成真实PNG并经manager删除后，queue始终为空而已删章节仍在下载过滤结果中；缺章总量尚无UI显示。随后纠正缓存恢复前提，`interaction-ri07-normalized-gap-red`于07:55:57–07:56:20 +08 FAILED／exit1，XML23:56:19.416 UTC，两项原算法对照正确红：统一可恢复数值总量应86而行缺口合计88，旧第22／16卷恢复保留且最低16之前须补15，实际遗漏。此处86替代先前无效raw总量5前提；不将XML缺失或被覆盖的方法配给后续key。继续本批最小通知端口、总量同偏好及规范化缺口实现，尚无绿结论。

`interaction-ri07-boundary-green`于07:58:14–07:58:57 +08 PASSED／exit0，实际18项／0失败／0跳过：Native五项（交错写、初次权威读、真实文件完成／删除且空队列不变、两处缺章同隐藏偏好、默认实际成功反馈及不回放），Rows十二项及补偿取消一项。缓存卷号保留，literal86和旧卷总量20独立期望固定；同一个effectiveMissingChapterNumber规则供shared总量及gap消费，缺章按unique整数区间只插一次且不改变真实行序。provider revision由真实删除／rename／manager目录与CBZ发布finally通知，factory向model绑定，mounted详情将revision加入投影依赖；不伪emit同值queue，不修改下载身份或结果算法。主代理同一初审已核代码／接口及协调器终态，文件部分失败仍属既有非SQL边界；模态完整focus、默认部分失败、扫描组拒绝和Android实际包装尚待本批闭合。

`interaction-ri07-partial-file-boundaries`于08:01:25–08:01:53 +08整条FAILED／exit1，XML00:01:36.334 UTC，四项中默认六偏好写入／flush前后拒绝恢复及保框重试、已保存默认＋favorite批量拒绝的明确部分反馈／显式重试、扫描组SQLite拒绝保原值／草稿及同框重试三项通过。whole manga删除补入既有file方法后，mounted列表仍保章节为正确红；queue为空保持断言，继续仅覆盖provider已有整本删除通知边界。原FAILED不能外推整批通过，不新造跨偏好／SQL原子结果。

`interaction-ri07-native-modal`于08:05:32–08:06:16 +08 PASSED／exit0，三个实际方法／0失败／0跳过：两个已有整本删除入口通知mounted过滤、原生双向完整Tab环及扫描组／默认／面板Escape逐层还真实触发器、320dp／fontScale2三页及24扫描组末项在主题／尺寸重排后可达且owner保持。两个provider整本删除沿相同finally revision通知，无目录算法扩展。主代理实际打开[浅色显示页320dp／200%字号](desktop-interaction-native/ri07-chapter-display-light-320-font200.png)与[深色扫描组末项](desktop-interaction-native/ri07-scanlators-dark-320-font200.png)，可见选项、默认／重置／关闭及确认／取消都在窗口内；英文Tab有大字号换行，不以该离屏图作为上游像素基准。场景Windows／Temurin JDK21.0.11、Compose1.10.2／Skiko0.9.37.4、真实DesktopTheme浅／深、density1／fontScale2、320×680、English；硬件／发布runtime仍属RI18。Android实际共享wrapper和最终明确受影响／格式尚未完成。

`interaction-ri07-active-filter-red`于08:11:31–08:11:52 +08 FAILED／exit1，XML00:11:42.276 UTC。活动反馈真实primary像素方法在ENABLED_NOT时没有提示，为正确红；三态方法读取ToggleableState时IllegalStateException不是有效Assertion红。核对当前modal owner与实际downloaded整行Role.Checkbox后，确认生产Row缺原生ToggleableState，onClick=null的子TriStateCheckbox也不补整行状态。继续先用contains明确断言该缺失红，再最小原生triStateToggleable接线，不能读取另一节点掩盖整行无状态或将enum实现词用于读屏。共享活动判断须覆盖原值NOT、global约束与扫描组，RI08再承担章节头完整资料。

`interaction-ri07-row-semantics-red-valid`于08:14:12–08:14:51 +08以实际downloaded整行缺ToggleableState的contains断言确认正确业务红；原IllegalStateException不升级证据。`interaction-ri07-active-android-green`于08:16:29–08:16:45 +08 FAILED于旧Desktop Reader保护夹具仍调用已移除独立bool setter的编译，未执行目标行为；修改为真实章节面板会话及same-id权威emit，保Reader／filter／snapshot断言。`interaction-ri07-active-android-green-valid`于08:18:31–08:19:19 +08仍FAILED于新Android测试RuntimeEnvironment.getApplication错误泛型编译，尚无这一组Desktop2／Android3行为结果，不计红绿。

默认首次消费者门禁：主代理及实施者核对Android MangaScreenModel line228首次加载nonfavorite时应用默认、Desktop此前仅reset／显式batch消费默认。新源作品真实入口统一SaveSourceMangaForDetails.awaitListed／awaitSearchResults／await→NetworkToLocalManga→MangaRepositoryImpl.insertNetworkManga；SQL同事务INSERT采用输入chapterFlags、existing UPDATE不改chapter_flags，能够只初始化真正新对象且避免precheck race。方案在本批默认策略范围内复用共享六mask打包、SetMangaDefaultChapterFlags同一六默认snapshot及生产DI延迟callback，不增加存储／marker／目录迁移；已有favorite与nonfavorite单本值均不自动覆写，Android现初始化不改，平台差异由PROJECT_POLICY D-D3持久化与D-D5已有作品仅显式覆盖解释。实际新listed／search／refreshed及已有对象DI＋SQLite红绿尚待闭合，不能只以“设为默认”入口通过宣称完整。

`interaction-ri07-new-default-red`于08:23:26–08:23:56 +08 FAILED／exit1，三项中活动图标真实primary像素、原生三态读态反转／global下载强制与解除raw值保持／四种排序方向两项通过；production DI解析SaveSourceMangaForDetails并调用awaitListed后，真实SQLite新对象chapterFlags期望1048885、实际0，为正确默认首次消费者红。随后共享六mask打包与真实源保存三个入口接线，不增加单独偏好、预检查或迁移marker。

`interaction-ri07-new-default-android-green-valid`于08:29:55–08:30:49 +08整体FAILED／exit1。Desktop真实源保存方法通过，匹配XML00:30:42.913 UTC，覆盖listed／search／refreshed新对象消费默认、已存favorite及nonfavorite单本值保持、真实详情factory读回。Android ChapterPreferencesContractTest两个实际wrapper／共享默认契约方法通过；新增actualMangaScreenModel方法期望两作品更新[42,43]、实际[]，失败XML00:30:16.057 UTC并提示主Looper待执行任务。该方法框架重试三次仍失败，不算三个独立用例；原因尚待实施者诊断，仅修验这一项，不能把整体FAILED记作全绿或把待执行夹具当生产原因。

Android页面默认测试失败诊断：实际launchNonCancellable委托launchIO，再进入NonCancellable；runTest的advanceUntilIdle不等待该真实IO工作。实施者和主代理核对共享协程helper与既有同类测试后，将其认定为夹具同步缺口，未改production；采用真实第二次repository.update完成信号等待，保更新对象顺序、六mask与全部默认偏好断言。修验只运行此失败方法，前述已通过契约方法和Desktop源保存方法不重复。

`interaction-ri07-android-default-green`于08:37:09–08:37:25 +08 PASSED／exit0，仅复验失败的actualMangaScreenModel方法，XML00:37:15.472 UTC，1项／0失败／0跳过。真实IO仓库写入完成信号后，两收藏对象及六项权威默认断言通过，未改Android production；前次整体FAILED及夹具失败事实保留。Android本批有效证据为实际filter包装／共享默认两方法与该页面包装一方法，不按框架重试次数加总。

稳定受影响验证`interaction-ri07-affected`于08:44:58–08:47:30 +08 FAILED／exit1。Desktop共164项：源保存13、DownloadProvider22、SortPersistence6、Selection8、Library既有导航／续读3、新原生面板19、章节默认5、Sort2、ChapterListItems7、Rows12、入库wiring10、DetailModel57；其中159通过、ChapterListItems五个真实行场景因未提供LocalDesktopUiDependencies失败。domain共享过滤2项通过，Android实际契约／页面包装3项通过；总169项＝164通过＋5失败，0跳过。对应Desktop XML时间00:45:34–00:47:29 UTC、domain00:45:14.622、Android00:45:22.279／31.416，均属于本次运行。新原生组包括Space三态SQLite消费、真实背景pointer隔离和同一模态owner／还焦；取消传播覆盖flags补偿读取及最终权威重读两个路径。五个旧行夹具须补真实界面依赖并仅受影响方法复验，原FAILED保留；未跑完整模块、全量或发布。

`interaction-ri07-affected-repair`于08:50:39–08:50:56 +08 PASSED／exit0，仅复验前述五个失败行方法及scoped格式；夹具只为真实ChapterRow提供DesktopAppPreferences与LocalDesktopUiDependencies，保原pointer／Reader、选择、下载／取消／重试调用次数断言，不改production以绕过失败。Desktop／domain／Android／i18n scoped格式checks通过，其余绿色方法复用；合并唯一有效证据169项全部通过、0失败／0跳过，仍不是全量证据。主代理核对原FAILED、修复diff、最终PASSED和两幅离屏图，完成同一独立初审及必要修正核验；后续仅收口索引、checkoff与提交。

RI07收口：实施代理返回status／diff／tests／commit／process／next并停写，34条RI07协调记录均为终态。30个代码／测试／资源文件、两幅离屏图及必要契约／索引／checkoff共同交付；超过8文件／400行仍内聚于章节权威设置、真实投影与失败恢复，共享Android消费、DI与文件信号必须一起验收，不按文件机械拆分。格式首次apply有五处超长行，后续一次apply仍失败，最终format-final于08:42:54–08:42:59 +08 PASSED；最后修复命令scoped checks全绿。11个Desktop文件及共享／Android受影响文件使用scoped Kotlin，base XML使用scoped XML，zh-rCN另核UTF-8／XML与行尾；其余九个Desktop修改文件逐diff核新区域，不宣称历史整文件均完成格式化。主代理最小更新cap17／24保护路径与适用证据、11处当前role定位漂移；279条当前角色定位有效，64项status、固定原版与actionInventory不变。索引核验仅为治理，不代替行为测试；后续RI08–RI18保持未完成。

## RI08：详情布局、资料、笔记与封面查看器

RI07已提交`27169e118e88618bfe24fe781106a24dd767f966`，提交后工作树干净。继续复用同一实施代理承担主要实现与验证；主代理固定接口、独立初审1轮及必要修正核验1轮，负责唯一报告／契约／manifest及同批提交。实施前固定验收见page-contracts的RI08节；约六个内聚功能簇focused红绿、稳定一次明确受影响集成／wiring／scoped格式，预计3–6小时。主要成本为原生布局／导航／失败恢复测试和既有RichEditor同版本Desktop接线；全量、模块完整、finalParityAudit、正式构建及runtime0次，仍统一在RI18。追加仅具体失败的相关路径；本批尚未实施完成。

本批收敛450dp／65%与小屏100dp／16dp公式、独立滚动和唯一工具栏，真实资料搜索／作者身份导航、简介／章节资料、typed封面查看／缩放／编辑、同一笔记草稿与唯一UpdateMangaNotes、重复收藏与取消收藏下载确认、单本间隔与当前可得预测。复用RI03日期／locale／description与图片开关、RI04封面版本／书架上下文、RI07权威章节投影；完整选择UI由RI09、文件保存由RI10、分享由RI11、更新预测由RI14、迁移事务由RI15闭环，不能以空回调或当前离屏图替代。先核实际RichEditor转换／生产DI及文件边界，再执行真实行为红测，尚无RI08测试或视觉完成证据。

`interaction-ri08-layout-red`于09:07:40–09:08:07 +08 FAILED／exit1，XML01:07:55.785 UTC实际三项／0跳过。真实factory／DI／SQLite及200章夹具均先成功显示Chapter1；宽栏SOURCE封面期望(450−32)×0.65＝271.7dp、实际120dp，普通TopBar实际仍常驻已读／更新／迁移／笔记且缺More，原生右侧pointer拖动后实际Chapter200未可见，为三个正确业务红。主代理读实际测试、XML和AndroidMangaAndSourceTitlesLarge的16dp内边距／0.65、Small的100dp／16dp间距／封面Align.Top，确认公式与实例入口一致。继续同一Screen owner窗口adapter／Header、唯一More和真实Scrollbar最小实现，尚无整体布局绿或RI08完成结论。

`interaction-ri08-layout-green`于09:11:47–09:12:20 +08 PASSED／exit0，匹配XML01:12:08.215 UTC，原三项／0失败／0跳过。实际宽封面按内容65%居中、窄100dp与7:10、普通四按钮、原生Scrollbar拖至Chapter200且左侧封面bounds保持、未触发更新通过。这里只闭合第一簇初绿；同作品稳定ID位置跨窗口／重排、其余资料／封面／笔记／收藏及最终受影响验证仍未完成，不能宣称RI08完成。

`interaction-ri08-position-notes-red`于09:15:11–09:15:30 +08 FAILED／exit1，XML01:15:21.346 UTC实际两项／0跳过。真实章节窗口从Chapter100及非零offset切窄后回到Chapter1，原位置上下文丢失；真实SQLite delegate拒绝notes更新后showNotesDialog=false，草稿被关闭，为两项正确业务红。位置沿同一Screen owner的chapter ID／有界offset，不将含header的窄屏index套宽屏；notes沿已绑定UpdateMangaNotes:Boolean确认结果，正确红后才接同版本RichEditor，尚未有这两项绿色结果。

`interaction-ri08-position-notes-green`于09:22:05 +08启动失败／exit255，协调器记录显示PowerShell将未引用的-D参数拆分且bat解析含管道的nonProxyHosts；没有执行Gradle或访问网络，不计业务红／绿或网络追加尝试。修正命令参数后`interaction-ri08-position-notes-green-valid`于09:26:50–09:27:34 +08 PASSED／exit0，实际两项／0失败／0跳过，XML01:27:24.270 UTC。Chapter100＋29px偏移跨窄宽和排序、真实repository拒绝笔记时保对话框／草稿且显式重试SQLite落盘通过；同版本RichEditor Desktop实际依赖解析和production编译已通过。实施者随后自查发现viewport观测Long key与实际chapter-ID字符串key不符，当前窗口测试由同一Lazy稳定key保护，失效锚点及有界恢复仍需真实事件补验，不能提前声称该接口整体通过。

`interaction-ri08-metadata-red`于09:29:30–09:29:46 +08 FAILED／exit1，实际三项／0跳过：资料标题没有实际搜索点击、tag导航错误进入全局搜索为正确业务红；笔记摘要方法随后证实初始insertNetworkManga不写notes，数据库实际为空，因此其摘要断言不计业务红。`interaction-ri08-metadata-green`于09:31:44–09:32:08 +08仍FAILED／exit1，XML01:32:00.176 UTC为三项中两过一失败；实际嵌套GlobalSearch标题查询及返回同owner、当前source的SourceBrowse tag查询已通过，笔记摘要失败来自同一夹具未落盘，不把该命令记为全绿。主代理核对实际repository及SQL入口均不包含notes插入；实施者须用真实update及读回断言修正夹具，先撤摘要增量重新确认业务红，再恢复实现。主代理同一初审要求五种笔记格式经真实编辑／SQL保存／重新打开验证，并核对保存中输入互锁，继续原范围内实现。

`interaction-ri08-summary-red-valid`于09:34:02–09:34:22 +08 FAILED／exit1，仅摘要方法，修正夹具使用真实repository.update(notes)及SQLite读回后撤去摘要增量，确认可见摘要缺失的正确红。恢复呈现后`interaction-ri08-content-boundaries-red`于09:36:25–09:36:59 +08 FAILED／exit1，XML01:36:44.900 UTC五项中摘要通过；暂停连载状态漏显示、保存中编辑器未disabled、实际字符串Lazy key未进入owner位置port三项为正确红，简介方法误匹配同名toolbar属于定位失败。随后`richtext-description-red`的XML01:38:55.928 UTC两项：正文独立展开入口缺失是正确红，五格式方法被原生selection Popup改变活动owner后Save定位失败，不计格式业务红；修正为实际包含EditableText的Dialog owner，不撤保存／重开格式验收。

`interaction-ri08-content-boundaries-green`于09:41:18–09:42:16 +08 PASSED／exit0，XML01:41:36.263 UTC六项／0失败／0跳过。真实SQL摘要→同草稿编辑、暂停／未知状态、延迟保存editor／格式／取消互锁、五种实际编辑格式→Markdown→SQLite→重开样式、删除章节锚点有界恢复及主动回资料顶部后不被后续仓库变更拉回、长简介展开／收起通过。五种格式包含实际SDK支持的下划线<u>存储及恢复，未引入第二parser；Deferred信号有10秒上限与finally释放。主代理继续核对窄320dp／200%字号短文字溢出和摘要富文本呈现，以及其余资料／封面／收藏；这些尚无完成证据。

`interaction-ri08-notes-controls-policy-red`于09:45:44–09:46:22 +08 FAILED／exit1，XML01:46:07.352 UTC七项／0跳过。撤回尚无有效红的新格式入口后，明确断言真实Bold编辑动作缺失形成正确红，随后恢复既有SDK入口；另四个正确红为320dp／fontScale2短简介实际溢出无展开、摘要显示原始Markdown无样式、重复作品点击即入库、取消收藏点击即改变SQLite。迁移与间隔两项初次NoSuchElement定位失败不计业务红，修正当前实际文案／操作路径后`interaction-ri08-migration-interval-red-valid`于09:47:53–09:48:17 +08 FAILED／exit1，XML01:48:10.060 UTC两项明确断言：More迁移未进入普通MigrationSearchScreen、真实间隔repository拒绝后草稿被关闭，为正确业务红。未放宽导航类型、取消无数据变化或失败保草稿的要求。

`interaction-ri08-notes-policy-green`于09:54:08–09:54:19 +08编译失败／exit1，新增picker参数使旧单参数factory函数引用不适配，不计业务红绿。保公开create(mangaId)签名并委托同一二参数重载后`notes-policy-green-valid`于09:55:38–09:57:04 +08仍FAILED／exit1，XML01:56:27.195 UTC七项中五绿；真实重复查询确认／取消、危险取消收藏不写、间隔拒绝保框重试、320dp／fontScale2实测溢出展开、rich摘要样式通过。剩余迁移测试使用了另一返回资源，实际MigrationSearchScreen使用desktop_ui_back；五格式摘要“不包含整个Markdown串”对正常有序编号不成立，修正实际样式／列表语义断言，保真实存储和重开样式要求。

`interaction-ri08-cover-entry-red`于10:02:36–10:03:19 +08 FAILED／exit1，XML02:02:47.136 UTC四项中迁移实际nestedScreen／ID／非Tab／返回同owner和五格式实际编辑→SQL→rich摘要→重开全部通过。两项新正确红均先从真实PNG／Coil绘出绿色像素：封面主体缺查看器点击；数据库coverLastModified=10而实际memoryCacheKey仍版本0，setManga未投影该值。主代理要求沿数据库及现store权威修正，不能动作后用另一时钟覆写正确版本；Viewer／缩放／Close／Escape／编辑及失效反馈仍待实施验收。

`interaction-ri08-cover-entry-green`于10:07:51–10:08:37 +08 PASSED／exit0，XML02:08:27.899 UTC两项／0失败／0跳过：真实PNG同址换色与数据库版本10→20进入实际memory／disk缓存键，封面主体打开同typed查看器，2倍缩放改变实际图像几何，Escape关闭并还焦封面触发器。新查看器当前只开放已接线的Close／Edit／缩放，保存／分享保持RI10／RI11边界；自定义编辑／删除及文件失败、模态大字验收尚未完成。

主代理同一初审核实取消收藏新增删除路径的具体缺口：removeFavorite调用旧Unit回调后返回true，而manager.deleteDownload丢弃provider.deleteChapterDownload的Boolean，真实deleteArtifact=false不能反馈。RI10仍负责完整队列／文件协议，RI08危险确认必须至少消费固定已下载snapshot的真实失败并可重试，不能先以假成功闭合D-C2。要求原实施者沿已存在LibraryFactory删除port复用cancelAndAwaitRetirement／Retirements和实际provider结果检查，仅有限对象与必要共同adapter、不重写engine。取消／未勾保文件、迟到新下载不扩张对象；此项待真实临时文件拒绝红绿及复核，不记已修复。

`interaction-ri08-cover-removal-red`于10:14:22–10:15:22 +08 FAILED／exit1，2项均失败。真实已下载文件拒绝删除后未出现错误与重试入口，为正确业务红；封面方法采样了Dialog背后的Header并超时，属于观察夹具问题，不算封面业务红。修正采样为active owner后，`interaction-ri08-cover-removal-partial-red`于10:16:38–10:17:27 +08 FAILED／exit1，2项中封面1项绿、删除1项有效红：SQL实际favorite=false已成功，但缺少“已移出书库／部分下载未删除”的明确部分完成反馈，不能用笼统保存失败暗示事务回滚。封面实际文件替换、取消、锁定拒绝保旧字节／版本／像素、删除失败和恢复源图经production factory／store／SQLite／Coil链路验证；该首次采样问题已纠正，不回填有效红结论。

`interaction-ri08-removal-green`于10:18:38–10:19:11 +08 PASSED／exit0，XML02:19:03.871 UTC为1项／0失败／0跳过。新增有限删除port复用manager.cancelAndAwaitRetirements(snapshot IDs)，再检查实际provider.deleteChapterDownload Boolean；本批不重写队列引擎。短结果区分membership未完成与membership已提交、下载未全删，UI显示明确部分完成提示；原snapshot重试后原文件被删除、打开确认后新增下载仍存在、favorite仍为false，避免重新加入书库。封面版本改为repository／store既有权威值，不用UI独立时钟制造第二版本。其余资料身份、章头行投影与原生边界和稳定受影响验证尚待完成，RI08未收口。

`interaction-ri08-metadata-rows-red`于10:20:44–10:21:07 +08 FAILED／exit1，4项／0跳过。作者与标签上下文缺角色搜索／全局范围，来源／处理后章节头先明确断言缺可点击入口，均为Assertion业务红；log末行749是runBlocking结束位置，并非NoSuchElement定位错误。`metadata-rows-green`先因String?及Boolean→Unit编译失败，不计红绿；`metadata-rows-green-valid`于10:23:56–10:24:44 +08 FAILED／exit1，XML02:24:27.208 UTC为4项3绿1失败：实际作者资料／角色搜索、标签两范围及窄窗换行、缺源导航通过，普通空心书签仍可执行的正确红待修。下一原生投影专项中处理后章头／仅真实书签已绿，仍需 SOURCE 未读点与已读alpha、标题右键复制和封面层级边界。

主代理同一初审补核D-B3落地：SourceBrowseScreen实际source=null时loadPage直接return，when没有缺源状态分支，推入类型正确仍落空列表，不能当恢复路径完成。已要求最小复用existing extensionListDestination等现入口，真实测试执行缺源反馈、恢复按钮导航及返回原详情；不重写扩展或RI11登录链。标题右键复制／真实结果已按原最终设计D-B1补明确到契约，未修改原定必做验收。

继续同一原生／数据边界初审：metadata-final-red及red-valid中章节点首次匹配整行、封面尚停于编辑Popup，不能据此宣称精确点布局或Header成功色业务红；标题上下文缺复制为有效红。metadata-boundaries-red与red-valid分别因unmergedChildren调用形式及其internal可见性编译失败，不计红绿。改用公共Semantics观察后metadata-boundaries-red-public于10:34:30–10:35:12 +08 FAILED／exit1，3项均失败，缺源落地、标题复制与章行继续按实际节点校验。

`interaction-ri08-row-removal-boundaries-red`于10:36:43–10:37:10 +08 FAILED／exit1，XML02:37:00.201 UTC，2项／0跳过，两个正确业务红：实际已读章文本color仍onSurfaceVariant／alpha1而非SOURCE onSurface／DISABLED_ALPHA=.38；部分删除重试使真实repository.updateAtomically调用2次，期望1。观察wrapper仍委托真实SQL，只计实际membership调用，不mock结果或复制算法。MangaRepositoryImpl该调用无条件走收藏同步日志及分类重写，部分完成会话应只重试原文件，不能重复membership写入；原实施者继续最小会话状态修正与绿测，不建通用恢复平台。

`interaction-ri08-native-boundaries-red`于10:41:37–10:43:10 +08 FAILED／exit1，实际9项4绿5失败。四项真实入口已绿：标题复制真实clipboard port成功／拒绝经shareService进入notification bus；缺源反馈→Extensions普通Screen→返回详情；部分删除重试只提交一次真实membership；长笔记异常保草稿、重试、重开及取消不写。三个有效新红为本地原生pointer仍入队、未下载图标缺SOURCE圆形箭头像素、Notes editor消费Tab而使modal焦点环不可达；Unread采到ghost零面积节点及cover超时仍作观察问题核对，不假称业务红。

`interaction-ri08-native-boundaries-green`于10:47:42–10:49:19 +08 FAILED／exit1，仅运行原五个未闭合方法，XML02:48:27.528 UTC为5项1绿4失败。本地下载placeholder真实pointer不入队已绿；Notes双向Tab及控件bounds已通过但Escape未还More焦点，仍有真实退出缺口。图标像素期望10526889与实际10527145仅绿色通道1级差，须区分Skia 8bit舍入与图形缺口；Unread继续限定实际可见同章、cover先核owner及文件阶段，不能改业务来适配夹具。主代理实际查看生成的320×680／density1／fontScale2浅色原生Notes图，五格式及Save／Cancel可见、草稿完整；该失败运行图暂落在app-desktop测试CWD的docs/evidence，已要求复用RI07有界visualFile并以当前命令绝对输出到root docs/evidence/desktop-interaction-native，仅稳定图纳入交付，不记最终视觉／focus通过。

`interaction-ri08-native-boundaries-green-final`于10:58:42–11:00:04 +08 FAILED／exit1，仅原四个未闭合方法，XML02:59:11.894 UTC为4项3绿1失败。Notes双向Tab覆盖五格式与Save／Cancel、Escape还More焦点已绿；SOURCE圆形箭头实际8bit像素已绿；封面真实文件选择／取消／拒绝替换／拒绝删除／恢复源图、数据库版本与Coil像素、Escape还封面焦点均绿。焦点overlay的ff1717fc在Tab离焦后为原图ff0000ff，属于观测时包含合法焦点样式，不修改封面来消除焦点。唯一Unread前置几何观察仍未闭合，继续限定实际可见同章unmerged Text，保primary像素与SOURCE已读alpha0.38断言。

主代理实际查看[Notes浅色](desktop-interaction-native/ri08-notes-320-font200-light.png)与[Notes深色](desktop-interaction-native/ri08-notes-320-font200-dark.png)稳定原生离屏图：320×680、density1／fontScale2、English、Windows／JDK21.0.11／Compose1.10.2／Skiko0.9.37.4；草稿、五个格式、Cancel／Save完整可见。键盘可达与关闭还焦以对应真实事件测试确认，图片不代替硬件或正式发布验收。

`interaction-ri08-final-policy-red`于11:02:36–11:03:14 +08 FAILED／exit1，实施者回执XML03:02:50.778 UTC为5项3绿2失败。Unread通过真实同章unmerged Text及严格像素／alpha断言；摘要pointer打开后Escape实际还摘要焦点、六个间隔确认／取消均绿。另两失败分别为系统分类0干扰single夹具和默认SOURCE降序不符合假定缺章位置，尚不记业务红；修正有界夹具后再验证删除锚点与重复入库实际导航。后续meta与XML须在稳定交付时核对，本批未完成。

`interaction-ri08-final-policy-red-valid`于11:05:48–11:06:26 +08 FAILED／exit1，四项1绿3失败。笔记resize／主题／Space及背景pointer隔离真实绿；NUMBER ASC夹具确认删除原锚点后MissingCountRow占旧index，offset29丢至0为正确业务红。重复入库已完成实际custom default分类但末断言List／Set不一致属于夹具；取消收藏Escape后旧modal仍前台导致后续root按钮NSE，继续用明确modal关闭断言定位，不把NSE直接记业务红。

`interaction-ri08-modal-close-red`于11:07:58–11:08:38 +08 FAILED／exit1，XML03:08:17.475 UTC实际三项正确失败：Remove／Interval／Duplicate的Escape都没有关闭当前modal。现Cancel实际控件初始focus及Screen owner触发器还焦最小接线，未引入新focus引擎。`interaction-ri08-final-policy-green`于11:10:07–11:11:14 +08 PASSED／exit0，主代理读meta与XML03:10:43.983 UTC确认5项／0失败／0跳过：三modal只关闭一层并还真实触发器、取消／未勾文件不删；ASC缺章邻行删除后恢复实际Chapter101与offset29；重复查看既有Detail并pop、迁移既有普通Screen并pop、继续加入真实默认分类SQL。缺章提示不作身份锚点，fallback只落有效ChapterRow并有界保offset。稳定受影响与scoped格式尚待完成。

同一初审继续核固定D-C4出口，发现现详情只有Hourglass编辑入口，未消费已有manga.nextUpdate／fetchInterval展示；原实施者确认没有其他详情consumer。RI08既定出口要求当前可得预测与检查间隔两种时间分清，RI14才消费新增预测，故先暂停尚未启动的唯一affected，补当前真实持久数据正值／无预测／COMPLETED、间隔确认后标签及320dp／fontScale2资料动作bounds／Tab红绿；不扩大预测算法、下载、分享范围。SOURCE Android MangaActionRow和SetIntervalDialog已有expectedNextUpdate语义，现共享属性在nextUpdate=0仍返回epoch1970，UI须尊重无预测占位而非展示1970／伪Soon；日期／locale沿RI03消费者。格式首次interaction-ri08-format-apply于11:12:19–11:13:01 +08失败为五处max-line-length及测试autocorrect未收敛，普通分行清理、不添加suppression，终态后才补固定出口。

`interaction-ri08-format-green`于11:15:16–11:16:02 +08仍FAILED／exit1，为四处剩余max-line-length及测试autocorrect未收敛，未执行affected、不记格式完成。`interaction-ri08-prediction-reachability-red`于11:25:33–11:26:06 +08 FAILED／exit1，XML03:25:59.392 UTC实际两项失败：通过真实MangaRepository.update持久nextUpdate／fetchInterval／status、成功重投影后，保存日期格式yyyy-MM-dd的预计更新日期仍缺失为正确D-C4业务红。窄窗首轮NSE尚需把HTTP源动作真实挂载／存在与几何断言分开，避免因未启用的动作资格误称布局红；原实施者修正fixture后仅复验该方法。

`interaction-ri08-reachability-red-valid`于11:28:27–11:29:22 +08 FAILED／exit1，明确资格断言确认SourceManager.get(42)=null，仍属DI夹具问题，不记几何红。本机patched Injekt的addSingleton／addSingletonFactory不清已有值，沿现addFactory绑定同一真实DesktopSourceManager并将LocalDesktopUiDependencies消费同实例，production DI未修改、未加网络请求或parser。`interaction-ri08-reachability-red-wiring`于11:31:59–11:32:39 +08 FAILED／exit1，主代理核meta和XML03:32:29.934 UTC实际1项正确失败：真实HTTP源的六动作已挂载，但Open browser实际actionable bounds为Rect.Zero，单Row将动作挤出可见区域。最小修复保同一动作顺序／callbacks并用FlowRow；预测与检查间隔两种独立文本沿既有持久值及UiDateFormat，新增文案同步base／zh-rCN；同范围绿与格式／affected尚待完成。

`interaction-ri08-prediction-reachability-green`于11:34:12–11:35:21 +08 PASSED／exit0，主代理核终态meta／XML实际2项／0失败／0跳过。通过真实SQLite持久2026-10-06预测日期及yyyy-MM-dd偏好，确认custom7／default／automatic14检查间隔分别展示，nextUpdate0及COMPLETED明确无预测、不出现1970；原生320dp／fontScale2六个实际HTTP动作bounds全在窗内，顺序Tab均可达。主代理读MangaUpdateSchedule实际Clock.zone／UiDateFormat consumer及Long绝对值边界，未新增预测算法。文件／导航／笔记其余已绿不重复，随后仅正常分行清理scoped格式与已声明唯一affected。

格式收口真实失败继续保留：`format-final`于11:36:44–11:37:38 +08失败3处；`format-clean`于11:40:18–11:40:55、`format-ready`于11:42:21–11:42:47、`format-complete`于11:46:25–11:46:55均exit1，剩Screen深层表达式与测试反馈文本两处max-line-length。它们是同一scoped机械格式收口，不记绿、不作行为红、未改变lint规则或添加suppression；保原source／fixture／assert／数据，仅普通分行、显式try／finally和最小语义局部变量。主代理提示diagnostic为格式处理后的行号，不能按原文件同编号盲修；实际格式通过后才进入唯一affected。

`interaction-ri08-format-blocks`于11:52:03–11:52:42 +08仍FAILED／exit1，两处格式后行号未闭合。原实施者用已缓存、与Spotless相同版本的ktlint1.8 CLI materialize真实格式输出，按实际违规表达式正常拆分，CLI最后退出0，未禁规则／suppression。`interaction-ri08-affected`于11:56:16–11:56:26 +08失败在scoped格式检查、尚未测试：CLI将新测试写CRLF而Spotless要求LF；仅规范UTF-8／LF后恢复原filters，不记行为失败。

`interaction-ri08-affected-valid`于11:57:19–12:02:01 +08实际FAILED／exit1，scoped Desktop／XML格式通过，12类159项151绿／8失败／0跳过。12类计数为Sort6、Extension6、Library3、Cover5、Actions7、ListItems7、Rows12、Native42、Entry10、Model57、SourceRefresh2、Appearance2；10整类仅明确受影响范围，另Library／Appearance共五方法，不是模块完整。八个旧入口／夹具问题逐项保留：书架旧Header Edit；空书签按钮被原版ordinary视图替代；新标题同名Cover CD被旧定位选中；两个entry模型缺真实GetDuplicateLibraryManga；旧独立Refresh入口；两个模型仍期待UI假时钟封面版本。修改仅受影响fixture／观察：查看器→Edit→Delete、production row customAccessibilityAction、限定Text、More→检查更新、同现repository真实GetDuplicate用例；GetMangaWithChapters真实读取测试repository持久42／43版本，保task／coverModel／feedback并新增精确版本相等。主代理逐diff核原文件／SQL／Reader／owner／不冒泡断言保留；42个新Native方法继续证明实际DI与SQLite链，不把FakeRepository单位场景称SQL证据。

`interaction-ri08-affected-repair`于12:04:35–12:05:04 +08编译失败为CustomActions误用SemanticsProperties，未测试；定点改正确SemanticsActions后`affected-repair-valid`于12:05:49–12:06:18 +08实际FAILED／exit1，八项7绿／1失败。最后Library定位仍误用通用Edit而非查看器action_edit_cover，修观察后`cover-fixture-green`于12:07:33–12:07:56 +08 PASSED／exit0，主代理核XML04:07:48.947 UTC为1项／0失败／0跳过及最终scoped格式绿。有效去重159＝151＋7＋1，绿色组不重新全跑，历史失败meta未改记绿。

主代理实际查看[详情资料动作图](desktop-interaction-native/ri08-detail-actions-320-font200-light.png)：320×680、density1／fontScale2、English、explicitLIGHT、Windows／JDK21／Compose1.10.2／Skiko0.9.37.4，真实已收藏HTTP源六动作换行、无预测与默认检查间隔可见；标题与来源正常换行。六动作实际bounds／Tab以同方法验证，图像不代替实体输入／发布验收。两张Notes浅／深图已独立查看，仍是离屏原生环境。

RI08同一独立初审及必要修正核验闭合，实施者六字段回执IMPLEMENTED／UNCOMMITTED／process NONE并停写；所有RI08协调记录无STARTING／RUNNING。20个源码／配置／测试文件、三幅原生图及必要文档／契约／索引／checkoff内聚于同一详情Screen的布局、资料、草稿、收藏和封面真实边界，超过8文件／400行保完整测试与正常格式，不机械拆批。主代理仅增cap22／24／26适用保护与证据、6处当前定位漂移；287条当前定位核验通过，64项status／FIXED_ORIGINAL／actionInventory与原基线不变。索引核验仅为治理，不替代行为测试。封面仍7:10，文件／DB不称跨存储原子；文件删除partial明确可重试且不重复membership、不扩迟到文件；保存／分享／新预测／完整迁移分别由RI10／11／14／15闭环。本批全量／模块完整／finalParityAudit／正式构建／runtime0次；必要checkoff与代码、测试、证据同批提交后继续RI09。

## RI09：章节选择与原版上下动作栏

RI08已提交`27a24dedc9709df3cf590a16aa121c32c6e3d6c5`，27个本任务路径同批提交；提交后禁用只读status的缓存读取，`git -c core.fsmonitor=false -c core.untrackedCache=false status --short --untracked-files=all`为空，空的app-desktop/docs目录未当成用户文件清理。RI08最终治理核对manifest与HEAD的64状态／固定原版／actionInventory未变，两语言新增各11资源名无新碰撞、UTF-8／XML通过；基线既有重名不在本轮扩大清理。

继续复用同一实施代理，主要实现约四个内聚簇：共享范围与真实原生选择输入、原版选择顶／底栏、真实前序／批量适用集与snapshot结果、320dp／fontScale2／浅深及明确affected。主代理固定接口、独立初审1轮及必要修正核验1轮，唯一docs／manifest／roadmap及git仍由主代理维护；预计3–5小时，成本集中真实事件、repository／manager边界及原版图标适配。每行为focused正确红绿、清理后相关复验，稳定一次精确affected／scoped格式；模块完整、全量、finalParityAudit、正式构建／runtime0次，统一RI18。追加仅具体失败及受影响路径，不新增代理或新计划／报告。

固定验收见page-contracts RI09。章节Shift／长按及CtrlShift为APPEND＋KEEP_START，普通／Ctrl移动锚点，owner隔离、失效单项／陈旧目标忽略；过滤隐藏即时裁章与锚点，不套书架保留策略。普通动作／FAB／章节头入口及刷新在选择中隐藏，TopBar仅Close／非clickable计数／SelectAll／FlipToBack、选中背景无checkbox；宽底栏按整个可用页宽右半，窄窗与大字真正可达。前序按Android filtered叙事升序take(pointerPos)不含当前，混合读态／书签／下载状态采用适用子集；删除固定对象、不扩迟到变化，批量结果保失败与操作期新增选择。完整队列／文件失败由RI10闭环，不以空回调提前完成。现共享BatchUpdateChapters吞CancellationException的边界若被本批消费，须focused确认后最小传播、保其它失败继续；不扩通用重构。当前只启动本批，尚无RI09实施或验证完成结论。

### RI09 首簇红绿与原生选择红（实施中）

主代理已读当前ChapterSelectionState、共享LibrarySelectionPolicy、domain BatchUpdateChapters及新增测试。范围计算复用共享APPEND＋KEEP_START，未改共享默认APPEND＋MOVE_TO_TARGET；作品owner／陈旧目标校验、可见反选及用户选择revision在本地状态层，批量completion只移成功ID且保护后来选择。domain仅补CancellationException传播，其余普通失败继续；尚未据首簇绿宣称产品选择UI完成。

协调器`interaction-ri09-foundation-red`在12:29:52–12:30:04退出1，原因是public接口暴露internal LibraryClickModifiers，属于编译错误而非业务红；最小改可见性后`foundation-red-valid`12:31:04–12:31:11退出1，domain实际3项／1失败，取消被吞（expected true、actual false）。该task graph失败阻断Desktop，未执行的章节测试不计红。随后`selection-red`12:31:39–12:31:57实际12项／4正确失败；`foundation-green`12:33:33–12:33:59 PASSED，Desktop12及domain3全绿、无跳过。共享默认策略与旧普通失败继续仍受保护。

原生`interaction-ri09-native-selection-red`12:36:55–12:37:03误用不存在的readFilterRaw字段，编译失败，不计业务红；核对实际unreadFilterRaw后`native-selection-red-valid`12:38:19–12:38:45 FAILED，XML04:38:34.066Z实际3项／3正确失败：Ctrl真实pointer误推Reader；真实650ms长按只选1／4未补2／3；选择顶栏缺原版非clickable纯数字。夹具挂载实际MangaDetailScreen／production factory／SQLite，含过滤后的旧row callback保护。当前进入最小UI接线，尚无本簇绿、批次完成或提交结论；模块完整、全量、audit、发布及正式runtime次数仍为0。

原生最小绿补记：`native-selection-green`12:46:38–12:46:48因漏filterAndSortChapters import编译失败，不计绿。补import后`native-selection-green-valid`12:47:45–12:48:36 PASSED／exit0，主代理已核协调器命令、终态及XML04:48:20.742Z：3项通过、0失败／跳过。真实Ctrl／Shift／CtrlShift、过滤旧回调、650ms长按及release均经production Root；底栏实际容器left600／right1200／width600，按1200宽整个页面右半计算。ChapterDonePreviousIcon两条path已对照Android实际XML，旧默认共享策略未改。此绿仅闭合原生选择首簇；批量适用集合／失败／删除快照、Escape／焦点和320大字待后两簇，不提前勾选RI09。

### RI09 前序接口决定（实施前）

主代理读取实际共享ChapterSort与Android markPreviousChapterRead：四sort comparator相等时返回0，Android filtered降序再asReversed反转整个tie组。实施者提供literal fixture：原始numbers=[3,-1,2.5,2.5,7,1]、target id3=2.5，旧ASC prefix为[2,6]而旧DESC／reverse prefix为[2,6,4]。此时精确照搬旧包装会违反既定D-E4“显示升降不改变前序对象”。这不是已执行测试结果；正确红仍待实际运行确认。

接口决定采用两端共用纯chaptersBeforePointer(filtered,manga,pointerId)：仅前序按现共享显式ASC comparator、相等以稳定chapter ID排序，再定位有效pointer取take(prefix)，排除当前；陈旧／隐藏pointer返回空。Android原markPrevious与Desktop均消费，避免Desktop独立重写；普通列表及全局getChapterSort不改。四sort、双方向、未知／小数／重复号均用literal预期、真实repository/wrapper验证。共享抽取及双端focused属本批既有前序风险门禁，不增加全量或审查轮次；此刻尚无该接口实现或测试完成结论。

RI09批量红：`interaction-ri09-batch-red`12:52:12–12:52:45 FAILED／exit1，主代理已核命令、终态及XML04:52:32.114Z：4项／4正确失败、0跳过。状态条件fresh未读无页码仍显示Unread；旧操作成功回执将用户新选Chapter4清空；真实SQLite只写成功Chapter1／3后，失败Chapter2也被清；SOURCE非tie前序实际[1,2,3]而预期[6,5,4]，含当前且方向错误。未执行后续sort loop不能计为已验证；新集合/反馈/快照实现尚待绿。

`interaction-ri09-previous-android-red`12:54:41–12:55:16 FAILED，domain先执行2项／2失败。正式共享helper方向稳定契约expected[2,6]／actual[2,6,4]是正确行为红；辅助旧包装characterization漏写显式ASC，实际ASC=1／DESC=0，expected[2,6]／actual[5,1]是夹具错误，不能计业务红。已核常量修正该夹具；Android被先行domain失败阻断未执行，单独focused wrapper红待取得。共享helper暂委托旧reverse仅为编译红，不作为正式实现，尚无本簇绿或提交。

Android前序真实红补记：`interaction-ri09-android-previous-red`12:55:58–12:57:15 FAILED／exit1；主代理已核init script仅限定Test include当前class、保实际Android编译与运行，及XML04:56:32.177Z。实际两个唯一方法：取消wrapper绿；前序wrapper通过真实MangaScreenModel.markPreviousChapterRead→SetReadStatus→repository port正确红，expected[2,6]／actual[2,6,4]。Gradle既有retry将同一失败方法运行3遍，所以XML4条／3fail，不能冒充4个独立测试或3个独立前序场景。Android端repository注入捕获真实更新，Desktop端另由SQLite断言存储，未用源码字符串充当行为证据。尚待共享稳定前序与双端最小绿。

RI09批量最小绿：`interaction-ri09-batch-green`12:58:54–13:00:49 PASSED／exit0，主代理已核三个实际XML：domain2（04:59:42.837Z）、Desktop4（05:00:23.984Z）、Android2（05:00:09.152Z），均0失败／跳过。其中当前真实共享helper契约1、实际Android wrapper2、Desktop native/SQL4是production行为证据；domain另外1为复制旧reverse/take包装的来源对照，不能算当前production行为完成证据，已要求清理或转为必要真实helper契约，不为数量保留退役逻辑。Desktop实际四sort×双direction literal前序、当前排除／陈旧目标、bookmark部分失败保Chapter2及retry、晚到成功保用户新选Chapter4、按状态条件动作均绿。当前只闭合本簇，删除对象快照／跳过结果／native Escape与focus等仍待末簇；RI09保持未勾选。

RI09末簇正确红：`interaction-ri09-results-native-red`13:04:41–13:05:55 FAILED／exit1，主代理已核实际meta/XML05:05:31.423Z，6项／6 Assertion失败、无跳过：混合状态缺Delete；secondary无已读上下文；选底用secondaryContainer而非SOURCE secondary浅色.22；selection Escape未退出；bookmark重写已存在对象；Delete打开确认前已立即删实际文件。失败前未到达的长按名称、后续删除retry和local子场景不计已红；实施者分别取得对应focused子边界再接线。Factory拟增加有限快照batch删除结果port，复用R8 retire＋真实provider Boolean，保BatchResult旧两参调用默认skippedIds兼容；这些尚待实现／验证，不代表本批队列深链RI10已闭合。

末簇长按边界`interaction-ri09-native-label-red`13:07:20–13:07:47 FAILED，XML05:07:41.143Z实际1项／1正确失败：真实650ms长按未显示操作名。没有把上次pixel先失败时未到达的分支计为已红。

主代理当前独审发现读态适用过滤回归：实际共享SetChapterReadStatus.filterToUpdate明确保全部distinct ID，源代码说明本地相同也必须保用户显式命令，Android SetReadStatus也接该port。新Desktop仅按read／lastPage先过滤会吞本地相同章的User同步意图；必须回用共享filter，UI动作显隐与命令发送分开。现有`selected unread preserves same value user intent and exposes write failure`保护ID／page0／UserContext不可削弱，新native factory→共享SetRead→实际SQLite另保护入口。

`interaction-ri09-results-native-green-read-red`13:15:47–13:16:08因TextButton漏命名modifier而编译失败，未执行测试，不计业务证据。修正参数后`...-valid`13:17:07–13:18:12终态FAILED，主代理已核XML：native8／5绿3fail（05:17:42.273Z），model1／1fail（05:18:11.435Z）、均无skip。两个显式命令正确红：native expectedIDs[1,2]／actual[2]，旧model expectedIDs[1,2]／actual[1]。其余尚失败：mixed没有观察到底栏反馈（原实施者定位为测试选中disabled行内语义callback，待精确bottom入口复验）；选底resting像素偏暗（focus/ripple候选，尚未证实，不调整预期颜色或容差）。五个有效绿为真实文件confirm／cancel／固定快照与partial retry、真实secondary context、长按名称、selection Escape／focus及bookmarkskip反馈；尚不能把该FAILED key标为PASSED或勾RI09。

同一前序包装审查还发现有效首项边界：Android旧pointerPos>=0时仍调用markChaptersRead(empty)，后者先clear selection后返回；新if(previous.isNotEmpty())会丢有效首项清选。已要求有效pointer与空prefix分开，并以actual wrapper验证“有效首项零写且清选，陈旧pointer无动作／保选”。该必要修正在同一前序功能边界内，不改变普通排序、协议或追加审查轮次；末簇modal Escape→selection Escape、强制宽屏320／font2可达及普通pointer→Reader仍须有效原生证据。

RI09末簇边界红补记：`interaction-ri09-native-boundaries-red`05:31:37.597–05:33:10.175Z，FAILED／exit1，主代理核实际meta及log。Desktop8条（native7＋model1），3绿5失败：ALWAYS320右半栏只有40×40命中区、长按Release误写书签、删除框关闭未还焦为必要行为边界；mixed下载bottom定位及320 focus节点NSE为观测问题，不计业务红。Android有效首项包装1唯一方法失败，既有retry重复3遍，不扩大计数。显式同值User读态、普通Reader→pop及严格静止像素已有有效绿。最小修正把原公式内底栏调整为有限FlowRow、48dp combinedClickable，长按释放抑制点击、实际删除触发点还焦；Android有效首pointer空prefix仍清选而陈旧pointer保选。随后native Tooltip附加owner影响测试定位，按真实含Focused节点owner及bottom subtree修正观测，保模态Tab／Escape／还焦、文件和SQL断言，没有降低SOURCE颜色或像素容差。

RI09稳定受影响组：`interaction-ri09-affected`05:49:32.420–05:51:27.475Z实际FAILED／exit1；Desktop51／48绿3失败、domain10绿、Android4绿，共65项／62绿3失败、0跳过，四module scoped spotless通过。三个失败是删除Cancel后再次打开误定位Tooltip，旧delete fixture未注入真实下载资格，旧bookmark期望冗余重写已书签对象。仅修夹具和适用子集预期，保部分失败／成功／skip与真实文件断言。`interaction-ri09-affected-repair`首次参数误名isChapterDownloaded编译失败，没有测试证据；`interaction-ri09-affected-repair-valid`05:54:53.881–05:55:37.166Z PASSED／exit0。主代理核实际XML05:55:27.353Z native1和05:55:35.559Z model2及log／meta，原3项全部通过，Desktop scoped格式通过；不重复此前62有效绿。最终65个有效去重测试全部通过、0跳过：Desktop51（新增native18、selection12、actions4、list7、model10），domain10（当前helper2／Batch3／原共享policy5），Android4（actual ScreenModel3＋显式同值User保护1）。旧复制reverse/take对照已替换为当前helper四sort／双方向literal行为，domain XML05:49:47.240Z有实际2绿。Android实际消费者仍保同值命令不重复删除下载，XML05:49:49.243Z／05:49:53.371Z保护。所有FAILED key保原状态，不用后续通过改写失败历史。

同一独立初审及必要修正核验完成：主代理核production diff、共享stable-prefix及Android／Desktop消费、factory bulk retirement／真实provider Boolean、真实SQLite显式User状态与文件拒绝／retry、普通Screen导航、纯计数及48dp范围、长按Release无副作用、modal与selection分层Escape／还焦。没有新增审查轮次。SOURCE动作图标逐项核对，前序自定义vector取Android ic_done_prev_24dp；只前序的stable ID ties是PROJECT_POLICY，不更改普通排序。成功／显式skip从接受时选择移出，失败保有效对象，新user revision不受晚回执清除。文件与SQL不宣称跨存储原子，队列深链RI10继续闭环。

原生视觉：[LIGHT](desktop-interaction-native/ri09-selection-320-font200-light.png)／[DARK](desktop-interaction-native/ri09-selection-320-font200-dark.png)主代理已实际view：真实CanvasLayersComposeScene，Windows／Temurin21.0.11／Compose1.10.2／Skiko0.9.37.4，320×680、density1／fontScale2、English、显式LIGHT／DARK；Bookmark键盘Focus及原生Tooltip可见，4动作可达、顶栏纯计数、选底无复选框。严格SOURCE secondary alpha浅.22／深.16像素由1200宽静止实际渲染测试取得；PNG不能代替真实硬件或发布runtime。

本批21个代码／测试／资源文件及2PNG属于同一章节选择和批量能力：共享范围／前序／取消与实际上下动作／对象快照／结果保选必须一同编译和验收，因此不按文件或行数拆分。19 Kotlin缓存ktlint及2 XML scoped spotless通过，git diff检查通过；manifest仅增当前capability24的有限角色／行为证据并修复行漂移，293条当前定位有效，64项status／FIXED_ORIGINAL／actionInventory全部保持不变。完整模块、finalParityAudit、全量、发布构建及运行验收均0，留RI18。实施代理六字段回执IMPLEMENTED／UNCOMMITTED／process NONE后停写；代码、测试、两PNG、索引及本次checkoff同一批提交。

### RI10 启动预算与前置产物

RI09已提交 `1f76f7462e367e6c2660f5f00f89107e9e3f7f76`，提交后worktree干净。2026-10-01 14:00左右正式复用原interaction_impl串行RI10，不新增代理；root继续接口／独审／docs与manifest／git，实施者继续production／测试及唯一Gradle协调者。预算三簇：手动候选及共享消费者→真实队列和有限文件结果→继续阅读与封面保存，focused红绿及一次精确affected／scoped格式，同一独立初审1及必要修正核验1，预计3–5小时。过程不运行完整模块／finalParityAudit／全量／发布／runtime，全部实施完成后RI18收口。新增入口仍须真实wiring，既有队列／Reader／Coil／代理／同步身份保持权威，不新建引擎；仅本批相关code／tests／PNG与必要索引／checkoff同提交。下段只读事实保存为实施前边界，不是RI10实现或验收结论。

### RI10 下载前置核对（只读，不代表实施完成）

实际共享入口为`domain/src/commonMain/kotlin/mihon/domain/chapter/interactor/FilterChaptersForDownload.kt`；它服务更新后的自动下载，以downloadNewChapters、favorite、分类包含／排除及已读章号规则门控，不能直接把手动下载接到该门控而造成非收藏或关闭自动下载时无工作集。Android MangaScreenModel现手动getUnreadChapters／getBookmarkedChapters以ReaderPreferences.skipFiltered选filtered／all，再按真实Download.State.NOT_DOWNLOADED取未读或书签；书签没有排除已读，getUnreadChaptersSorted用共享getChapterSort及反转得到叙事方向。RI10复用此手动候选语义与manager、保自动下载原语义，不为同名用例强行合并两种触发条件。

Android已存在skip_filtered（默认true）；Desktop现ReaderPreferences已有reader_skip_filtered_chapters及skipFilteredChapters旧键兼容（默认false），真实ReaderSettingsPanel和ReaderNavigator消费同一值。后续下载必须复用该已有平台偏好及Reader链，不造独立下载过滤开关或悄悄丢已有显式值；既有持久键差异需在适配边界说明，不在本次只读核对中实施Reader重构或迁移。当前详情enqueue仅剔除external／已下载后入现队列，delete回调仍Unit；真实队列／文件结果、叙事限额与Reader请求由RI10闭合，不能据现入口视为已完成。

RI10首簇接口重用确认：实际domain/tachiyomi/domain/library/LibraryDownloadSelection.kt已有selectLibraryDownloadChapters，实施者拟在同文件追加仅手动包装，先read／bookmark和平台普通可下载资格、用既有显式ASC comparator，再委托原queue／file排除及limit。Android与Desktop实际包装同消费；自动FilterChaptersForDownload不改变。以all原始稳定序加filtered有效ID资格，避免DESC显示列表翻转同值组，不给普通下载额外chapter ID tie、不改RI09previous-only策略。manager.enqueue拟返回实际Boolean且持久化成功后发布；原entry Unit接口保持，详情和书架production必须接真实结果；cancel／retry的Boolean或异常不能被factory吞掉。以上是实施接口，尚无完成结论。

RI10初红：interaction-ri10-manual-red 06:09:23.252–06:09:49.187Z FAILED，ChapterUpdate漏import／实际repository.update返回Unit误assertTrue及nullable feedback编译问题，未执行测试；旧RI09 XML不作为新证据。修夹具后manual-red-valid 06:11:35.552–06:13:08.519Z FAILED／exit1，主代理核meta／log／实际XML06:12:56.715Z：4项／4失败／0skip。local实际入queue、已读bookmark预期[1,5]实际[5]、非收藏缺非阻塞入库提示是3个正确业务红；初始DESC未渲染fixture约定Chapter1导致next前置失败，不是数量限额业务红。原实施者把夹具改为真实ASC载入后SQLite改升／降，并单独取next／scope红；具体local／缺源原因与当前是否按筛选范围必须可见，不以非空通用counts推断用户反馈已完成。没有生产实现或绿的结论。

首簇其余业务红：interaction-ri10-manual-scope-red 06:14:53.494–06:15:16.348Z FAILED／exit1，主代理核log／meta及XML06:15:07.864Z，2项／2正确失败、0skip。实际next排除已下载／已排队／external后应队列[2,4]，现[2]，已下载提前占了1章名额；真实menu没有表明持久化Reader偏好所决定的当前范围。此时仍未到达后续5／10／25和第二方向断言，不声称这些均已红。Detail实际subscribe(applyScanlatorFilter=true)已剔除扫描组，因此skip=false完整目录拟用同GetMangaWithChapters.awaitChapters(id,false)，Library已有GetChaptersByMangaId；不造新DI或把filtered state当all。原列表／选择裁剪权威保持，实际排除扫描组切换须保护下载偏好。此刻尚无绿／完成结论。

RI10首簇部分绿及原始扫描组红：manual-green-scan-red首轮06:20:18–06:20:42Z仅BatchChapterResult必填参数／Locale import编译失败，不计行为证据。manual-green-scan-red-valid 06:25:25.911–06:26:37.604Z终态FAILED／exit1，主代理核meta／XML06:26:00.523Z，6项4绿2失败、0skip。四个有效绿为真实menu限额／升降方向、已读书签和skip偏好、local／缺源具体反馈、nonfavorite Add→SQL收藏；各方法实际到达的循环保护可复用。新增扫描组 false期望[1]实际[2]是正确raw输入红；scope第一次label通过，第二次打开Popup的NSE属定位问题，不算业务红。原实施者接原awaitChapters(false)及准确Root／Popup事件定位后再复验，并启动实际Library factory／SQL和Android wrapper的共享消费红。新增raw查询的异常反馈及await前范围偏好捕获仍须真实错误／挂起边界保护；此刻只有部分绿，不勾RI10，不宣称双端消费者闭合。

实际wrapper红／修正复验：interaction-ri10-manual-wrappers-red 06:31:35.362–06:33:33.852Z FAILED／exit1，主代理核meta／当前XML。LibraryInteractionTest 06:32:35.101Z真实factory／SQL 1项expected[2]实际[1,2]，正确skip范围红；Detail XML06:32:24.327Z共2，raw扫描组方法已绿，scope方法在Escape未关闭实际Popup断言失败，仍待真实owner／key诊断。Android XML06:32:42.645Z唯一wrapper方法超时5000ms，既有retry运行3遍，当前不能证明算法缺口；须先核success state和downloadManager实际参数调用再取有效证据，不把超时冒充业务红。Library production最小共享消费正在接线，真实Boolean接受／取消／重试尚属下一簇，不能把当前Unit兼容调用记为完整队列闭环。

RI10 队列边界补充：`interaction-ri10-manual-queue-boundaries-red`（06:38:20–06:39:21Z）Desktop因Popup dismiss lambda返回Boolean而编译失败，无Desktop业务红；Android实际手动下载方法到达raw书签断言，预期[1,2]、实际[2]，同方法retry三次不计三项独立测试。`interaction-ri10-manual-boundaries-red-valid`（06:42:14–06:43:35Z）执行Desktop6项：Library真实factory范围已绿；Detail scope标签已绿，挂起查询期间偏好变化导致已接受范围改变、raw查询IOException逃逸两项有效红；manager实际SQL存储的拒绝写入产生幽灵队列、enqueue返回Unit而非真实接受结果两项有效红。domain共享手动下载契约2项已绿；Android本次在生命周期teardown的main-thread校验失败，不能计绿，也不是产品行为红。XML共11条源于Android单方法retry，实际9项独立行为，尚非整批通过。

`interaction-ri10-queue-wiring-red`（06:49:36–06:49:59Z）Android同一production wrapper方法实际1项通过、0失败／跳过，修正Standard Main测试生命周期后获得有效证据；Desktop因Boolean enqueue方法引用与旧Unit端口不适配而编译失败，新增队列UI红尚未执行。`interaction-ri10-queue-wiring-red-valid`（06:51:15–06:52:40Z）因新夹具误用DatabaseHandler.db导致测试编译失败，同样不计行为红；实施者改为解析真实Injekt Database并构造现PersistentDownloadStore。生产端口仍需消费manager实际Boolean，临时Unit适配仅用于确认缺口，不能作为完成结果；已有Android绿证据不重复运行。

`interaction-ri10-queue-wiring-red-final`（06:54:56–07:05:18Z）编译后实际执行8项，XML均07:05:17Z：manager2项、Detail范围冻结／raw查询异常及取消2项已绿；其余4项有效业务红为真实queued右键缺StartNow、缺源行内操作实际入队、详情manager preflight返回false仍报成功、书架false仍累计queued=1。没有跳过。编译期间原协调器及Kotlin daemon仍存活、资源未耗尽，等待原终态后再改输入，未启动第二Gradle或全局清理。四条入口继续最小修复，有限删除及Reader／封面保存仍未验收，不能据此勾选RI10。

`interaction-ri10-queue-wiring-green`（07:08:23–07:09:32Z）仅复跑原4条失败入口，XML07:09:31Z共4项3通过／1失败／0跳过：详情与书架真实preflight false均反馈跳过、缺源行内操作不入队已绿；queued右键已越过StartNow菜单、同源顺序和暂停恢复断言，后续新增mangaId检查失败，不能把整个方法或key计绿。该检查暴露旧队列seed helper仍用默认mangaId，实施者补真实ID后继续限定验证；取消／重试持久化失败及有限文件范围尚未取得完整证据。

`interaction-ri10-queue-results-red`（07:11:50–07:12:28Z）实际3项，XML07:12:27Z：queued StartNow完整方法已通过；取消在真实队列持久化拒绝时保持原工作集，但UI没有失败／重试反馈，形成有效业务红；重试时持久化IOException逃逸界面回调，形成有效业务红。仅该两条入口待最小修复，不重跑已通过的Android、候选或manager方法。

`interaction-ri10-queue-results-green`（07:13:31–07:13:36Z）仅因新增CancellationException／BatchChapterFailure未导入导致production编译失败，没有新行为结果。修正后`interaction-ri10-queue-results-green-valid`（07:14:15–07:15:01Z）实际3项全部通过、0跳过，XML07:15:00Z：取消实际持久化拒绝保队列并反馈失败／可重试，重试异常同样反馈且成功重试复用原chapter身份，queued方法用当前production enqueueDownloadBatch建队并核真实mangaId／顺序／resume。Library删除的固定文件范围仍待后续原生／临时目录证据；不能提前勾选RI10。

`interaction-ri10-removal-snapshot-red`（07:17:58–07:18:18Z）真实书架Root／factory／SQLite及临时下载目录2项均因正确行为失败、0跳过，XML07:18:17Z：确认时整本删除使弹框打开后新增artifact被删；membership已成功移出库而本机文件拒绝删除时，UI没有区分两类结果及保原重试。第一方法先验证取消不改变membership，后到达新增文件保护断言；第二方法失败在结果区分断言，后续重试分支尚未到达。修复仅在目标作品已知canonical／legacy目录一层捕获原章级artifacts并复用现retirement／Boolean删除，保没有SQL章节但仍有旧下载目录的既有能力，禁止改为只删当前repo章节或扫描全下载树。

`interaction-ri10-removal-snapshot-green`（07:23:17–07:24:17Z）原2项全部通过、0跳过。partial方法补入成功A／拒绝B，并在成功A原路径创建新文件后原弹框重试，确认新A及late orphan均保留、只删失败B。实际捕获仅已知目标canonical／legacy目录的一层章级artifact，保SQL无章节的旧目录；不重读整作品目录。独立接口核对仍发现同文件重试会再次取消原queue IDs、再次删除已成功封面，后来的同ID新下载／新封面存在被重试触碰风险；已交原实施者在当前会话完成状态内修正并补原方法验证，不扩大引擎／持久恢复或整类测试。此两项绿不是RI10最终验收。

`interaction-ri10-removal-completion-red`（07:27:05–07:27:23Z）因真实cover store接受ByteArray、夹具误传File而测试编译失败，不计业务红。`interaction-ri10-removal-completion-red-valid`（07:28:08–07:28:28Z）原partial方法1项失败，实际AssertAll报告后接受同章queue预期[1]／实际[]，新封面预期存在／实际已删除两条缺口；主代理核协调器／日志真实1失败，原XML已被后续focused覆写，具体双断言取自实施者当次回执，不声称主代理再次读取原XML。修正会话retirementCompleted、coverDeletionCompleted及首轮冻结选择clear token，不另建持久恢复状态。

`interaction-ri10-storage-boundaries-red`（07:32:14–07:33:00Z）实际3项1通过／2失败：原partial方法XML07:32:53.096Z已通过（实施者当次读取回执，后续XML覆盖）；新provider枚举拒绝XML07:32:58.798Z有效红，把目标目录listFiles=null当空成功；新manager priority XML07:32:58.256Z有效红，持久化写后拒绝使重启存储保留未接受排序。`interaction-ri10-storage-boundaries-green`（07:33:47–07:34:02Z）仅新两项全部通过、0跳过，主代理核XML07:34:01Z：provider使用拒绝枚举seam及默认真实FS、缺目录正常空；priority使用真实SQL和production状态转换到DOWNLOADING，保当前活动identity／暂停／重启顺序。该priority测试不是活跃HTTP producer验收，有限producer专项仍待；部分retirement失败时的原generation保护仍在收口，不以这两项绿勾选RI10。

`interaction-ri10-captured-retirement-red`（07:42:47–07:43:22Z）未形成业务红，后续修正夹具后才运行有效红。`interaction-ri10-captured-retirement-red-valid`（07:45:39–07:46:53Z）manager真实MockWebServer阻塞原producer，SQL仅拒绝第二目标；AssertAll暴露聚合失败把已接受第一目标也留作失败，以及原重试取消后来第一目标新generation。Library原partial方法另在固定数量显示缺口有效红。`interaction-ri10-captured-retirement-green`（07:50:43–07:50:59Z）编译失败：临界区中挂起调用，不作行为证据。`interaction-ri10-captured-retirement-green-valid`（07:52:27–07:53:21Z）Library固定数量／partial通过；manager逐ID拒绝与后来新queue／store保护通过，但末尾文件断言失败，尚不能归因为旧producer迟到发布。

`interaction-ri10-retirement-reservation-red`（08:01:05–08:01:32Z）两项有效红：在新enqueue之前已确认原取消HTTP producer没有发布文件，后续请求数预期1／实际3，定位到暂停后同drain pass仍开始新producer；失败retirement重试cleanup阻塞期间新enqueue预期拒绝／实际接受，暴露检查与清理之间的竞争。`interaction-ri10-retirement-reservation-green`（08:02:28–08:02:45Z）两项全部通过、0跳过；仅在实际drain/start入口增加暂停检查，复用existing enqueuePreflights在短锁内占位、finally释放，cleanup／await不持锁。

`interaction-ri10-completed-replacement-red`（08:03:46–08:03:58Z）真实文件保护断言失败，但旧generation先经另一取消入口退役，未覆盖原定自然完成分支；不作该分支红证据。`interaction-ri10-completed-replacement-natural-red`（08:04:45–08:04:57Z）同方法改为原capture→自然HTTP完成→真实provider删旧文件→同ID新generation真实HTTP完成→首次调用原handle，预期拒绝{401}／实际空且新文件被删，形成有效红。`interaction-ri10-completed-replacement-green`（08:06:25–08:07:03Z）三项全部通过、0跳过，主代理核XML timestamp08:07:00.415Z；包含自然完成替换、原活producer暂停／逐ID拒绝及重试清理占位。最小进程内WeakReference marker在新generation真正持久接受后标记原capture已替换，copy共享marker，不新增下载身份／磁盘协议。随后同generation多capture复用marker整理纳第三簇编译及最终直接影响focused核验，不将旧绿覆盖为整理后证据。以上是RI10下载／移除子簇证据，Reader／封面保存和最终批次验收仍未完成。

`interaction-ri10-reader-save-red`（16:12:12–16:12:35+08）viewer图像夹具参数不匹配而compileTest失败，不计业务红。`interaction-ri10-reader-save-red-valid`（16:13:51–16:15:13+08）四项全部有效红、0跳过，主代理读取实际XML：Library续读使用真实书签／下载／全局／扫描组及同步页码链，目标预期3／实际2；同一真实封面查看器无Save可执行入口；平台保存覆盖取消预期Cancelled／实际Saved；两个保存入口在真实partial-write失败后原文件长度预期3／实际2，证明原有效bytes被破坏。XML分别08:15:05.220Z／08:15:05.359Z／08:15:09.465Z。此轮已终态并交原实施者最小修复，仍未声明Reader模式／保存收口或正式runtime通过。

`interaction-ri10-reader-save-green`（08:19:07–08:19:59Z）原四项全部通过、0跳过，主代理核XML08:19:47.646Z／08:19:47.768Z／08:19:53.554Z。Library真实点击已到达书签＋下载目标3／同步page7／非空snapshot，解除下载筛选后到目标4，全局仅下载强制恢复3，真实文件删除后无续读目标且不覆盖本地偏好。查看器消费同一typed Coil成功图像，取消覆盖及写入拒绝保红色原文件，成功重试保存绿色实际图像，Escape返回原封面焦点。平台两个入口复用同目录staging＋ATOMIC_MOVE，写入失败finally清临时文件，不支持原子替换时报告失败，无破坏性copy fallback。此轮不包含尚待Reader模式持久隔离／外部浏览器专项及最后受影响核验。

`interaction-ri10-reader-browser-delete-red`（08:28:38–08:29:36Z）测试遗漏DesktopReaderScreen import导致compileTest失败，不计业务红。`interaction-ri10-reader-browser-delete-red-valid`（08:31:45–08:32:57Z）三项失败、0跳过，主代理核XML08:32:44.459Z：单章真实provider Boolean拒绝却关闭确认框，是有效红；external章节实际URLs预期一条／实际空，是有效wiring红，已重核现external:前缀被production parser识别，Screen调用旧全局openExternalLink而未消费现dependencies.externalUrlOpener Result。Reader模式方法在设置按钮click的NSE，未到persist分支，不作模式业务红；已交原实施者修夹具再核真实写入拒绝。后续单章及多选复用原确认／有限alias／原attempt adapter，只失败项留作重试，不重复已成功项，不重新捕获当前generation；仍属RI10既定边界，未新增引擎或协议。

`interaction-ri10-reader-mode-red-valid`（08:43:05–08:43:44Z）原模式方法改为真实中心pointer显示默认隐藏的Reader toolbar，再Settings→LTR；1项有效红、0跳过，主代理核XML08:43:35.501Z。实际MangaRepository写入拒绝，SQL保原viewerFlags7，但Settings没有可见失败反馈；已到persist分支，不再是按钮定位失败。要求同一方法继续核失败后UI真实选中权威AUTO、成功重试LTR、另一作品／global不变及真实返回重开；后续分支未到达前不宣称通过。runtime实际writer须消费Boolean并重读最新单本flags，不以旧全State覆盖阅读进度。

`interaction-ri10-reader-browser-green-batch-red`（08:46:50–08:48:22Z）3项1通过／2失败、0跳过，主代理核XML08:48:01.466Z。Reader模式原方法全绿：真实拒绝恢复AUTO selected并反馈、成功重试LTR、其他作品与global隔离、实际pop详情再进Reader仍选持久LTR。external已越过现依赖URLs及失败反馈，成功反馈被旧Snackbar排队延迟，末断言仍失败；尚不算整方法绿。原章节partial删除方法新增成功目标原路径替换文件后重试断言，正确红显示重试仍删已成功对象的新文件。最小共用有限执行器与Dialog pending失败项接口进行中，后续全量／正式runtime仍待RI18。

`interaction-ri10-finite-green-focus-red`（08:52:34–08:52:43Z）synchronous开框capture误调用suspend identity resolver导致production编译失败，不计业务红；修正为现resolver的Manga／Chapter同步overload消费已冻结对象，不用runBlocking或重建身份。`interaction-ri10-finite-green-focus-red-valid`（08:53:35–08:54:49Z）3项2通过／1失败：external现依赖Result及最新成功反馈、原批量partial重试保已成功目标新文件通过；single追加Cancel还焦分支在临时撤回新增返回token的未接入状态失败，未算单章整方法绿。主代理核终态及日志，XML08:54:33.553Z具体分支取实施者当次回执，原XML已后续focused覆写；不把该焦点分支失败冒充最初文件拒绝红。

`interaction-ri10-single-green-removal-red`（09:03:00–09:03:54Z）2项1通过／1失败、0跳过，主代理核XML09:03:41.101Z：single实际文件拒绝反馈／原对象重试／取消返回原inline操作焦点已全绿。原取消收藏方法扩两原文件，实际一成功一拒绝后在成功路径建新文件，原retry仍删除新文件，形成正确红；membership原提交次数及晚到不同对象边界继续保留。沿同一开框捕获／有限执行器接该产品消费者，整本capture还须保扫描组隐藏／无SQL旧文件，单章／多选继续仅其有限alias，不扩大目录树或下载协议。

`interaction-ri10-removal-save-green`（09:08:12–09:08:57Z）2项1通过／1失败、0跳过。主代理核meta／日志确认唯一Save方法失败；实施者当次XML09:08:44.923Z确认RemoveFavorite全绿：整本已知目录捕获保扫描组隐藏与无SQL旧文件、成功路径新文件及晚到对象保留、仅原失败项重试、membership只写一次。Save在追加窄窗夹具擅加绘制／semantics48dp断言处失败，Material3实际绘制40×40，不是既定可达要求；实际保存像素与Save通知标题已越过。纠正为真实touchBounds及事件，不修改Material3正常容器、不放宽文件数据断言。

`interaction-ri10-save-native-green`（09:11:01–09:11:54Z）原Save方法1项全绿、0跳过，主代理核XML09:11:26.819Z：320dp／fontScale2／浅深真实touchBounds48dp及窗口内可达，双向Tab／Space／Escape关闭还封面焦点；typed成功图像实际保存像素、覆盖取消／半写失败保原图与成功重试，以及真实通知title=Save／对应message。主代理实际打开[浅色保存查看器](desktop-interaction-native/ri10-cover-save-320-font200-light.png)和[深色保存查看器](desktop-interaction-native/ri10-cover-save-320-font200-dark.png)，标题两行、三个头部操作、Zoom与保存反馈均在窗口内。环境Windows／Temurin21.0.11／Compose1.10.2／Skiko0.9.37.4／density1／fontScale2／320×680／English，真实离屏Compose，非正式发布或硬件截图。有限文件执行器已供Library／单章／多选／取消收藏共用，原Factory Unit产品删除绑定已移除；main独审接口与这两幅图已核，无新增阻塞项，scoped及唯一精确受影响验证仍待，RI10未勾选。

RI10 唯一受影响收口组 `interaction-ri10-affected`（2026-10-01 09:27:06–09:30:24 UTC）已消费真实 FAILED 终态，未运行 module/full。Desktop XML 共115项：106通过、9失败、0跳过；其中 DownloadManager37、DownloadProvider2、DesktopShareService15、ReaderRuntimeFactory1、Library真实交互7、DetailActions7、Detail真实交互22、ReaderModel2全部通过。LibraryModel12项中8失败、DetailModel10项中1失败；domain共享手动候选2、Android实际手动下载wrapper1通过。实际 argv 保存在 `.gradle-coordinator/ri10-affected-args.json`，37个Desktop class/method filters分别绑定各task后，Android初始化脚本仅目标类；四scoped格式检查范围33Kotlin／2XML，不代表仓库全量格式。上述9失败须核对实现与旧fixture后完成受影响复验，不将整组宣称通过，不重新运行已绿完整组。

RI10 收口（2026-10-01）：原实施代理六字段回执已核对并停止写入，所有协调器无 STARTING／RUNNING。9个旧fixture失败原因已逐项确认：8个书架测试缺真实源前置，扫描组测试需显式Reader跳过筛选偏好，书签查询拒绝须注入正在消费的raw章节仓库；详情重试测试缺目标ERROR队列。只修改两旧测试文件的前置配置，保对象、ID、顺序、计数、失败反馈断言，不修改已绿production绕过guard。`interaction-ri10-affected-repair` 09:38:08–09:38:55 UTC的9方法XML全部通过／0跳过，但命令因测试文件两处格式FAILED；机械换行／import排序后 `interaction-ri10-format-repair` 09:39:47–09:39:52 UTC PASSED exit0，未重跑9项或既有106项。去重有效证据为Desktop115＋domain2＋Android1＝118项全部通过、0跳过；新纳两个fixture后scoped范围35Kotlin／2XML。格式初次配置失败、长行失败及此次格式失败均保留，失败命令不列为全绿。

同一独立审查及必要修正核验已完成：根核当前真实Factory绑定退役旧Unit／即时删除，确认shared候选与两平台consumer、原producer完成／替代generation／失败reservation、有限目录和原对象执行器、会员与文件独立提交边界、实际Reader入场／mode拒绝恢复、typedCoil→Save与atomic覆盖保护。根复核9项修复diff、实际XML及最终协调器终态；没有增加审查代理、重复全量或第二Gradle。manifest只补本批7项能力的当前consumer／适用证据、退役索引及19处漂移，306条当前symbol/line定位全部有效，64项status、FIXED_ORIGINAL和actionInventory保持不变；这些索引检查不充当行为测试。

本批超过8文件／400行仍属同一下载、阅读入场及文件动作用户能力：35Kotlin／2XML包含共享Android候选契约、同一manager／finite-file adapter和真实UI/wiring测试，2张PNG及3份既有文档与manifest为必要验收与维护资产；不按文件机械拆分不可独立验收的上下文。没有引入第二下载器、下载身份／同步协议、Reader图片引擎或长期删除队列。主要风险是原始会话快照仅进程内有效，数据库与文件不是跨存储原子事务，atomic替换不可用时保旧文件并明确失败；需重新发起操作时捕获新的对象，不把上一次会话的失败对象无限扩张。实体设备、正式process重启、Windows／macOS发布运行仍由RI18验收，本批没有全量、模块测试、finalParityAudit或发布构建。必要checkoff随本功能批与测试／production同一提交，随后推进RI11。

### RI11 启动预算与前置产物

RI10已提交 `6fb901f5f81d587e06281646034522f151ea9ef7`，启动RI11时工作树干净。复用唯一原实施代理，四内聚簇依次实施：追踪查询／远端刷新／重新匹配及服务上限；手动已读三态和增强入库；现网页登录恢复；文本与同typed封面分享终态。每簇仅focused红绿，末尾一组精确affected／wiring／scoped格式，根同轮独立初审一次与必要修正核验一次，预计3–5小时，主要成本为真实provider网络形状／SQLite／原生Compose事件；root维护本唯一报告、契约及manifest，实施代理承担主要代码与唯一Gradle协调。无全量、模块测试、finalParityAudit、正式构建／runtime，全部RI00–RI17实施完才RI18最终一次矩阵；失败只追加受影响路径诊断和必要修复验证，不提前扩大账号／OAuth／Cookie／代理范围。

补核实际已有SourceLoginController的attempt／ticket身份、redacted UI状态和SourceLoginSession Cookie校验可复用，打开浏览器不能宣称已验证；共享AuthenticatedSessionCommitter原契约允许已领取的有限本地提交在NonCancellable内完成，须区分取消／超时在领取前获胜与已领取提交的真实终态，不能虚构回滚已开始的写入。保护迟到UI／query回执不会污染新owner，保现Cookie存储及成功后恢复链，真实账号证据仍须RI18授权。

### RI11 追踪与平台动作前置核对（只读，不代表实施完成）

主代理读取当前TrackingScreenModel／TrackingSettingsScreen：未绑定查询初值为空，已绑定分支没有重匹配入口；model.validateEdit与UIChapterStepper均优先消费传入totalChapters，再回退track.totalChapters，详情onTracking仍传本地数量，因此服务总章数约束不能仅修UI。model.load调用registry.refresh及本地GetTracks，并不证明远端状态刷新。既有EnhancedTrackerWorkflow、provider解析、repository和共享SyncReadingProgressWithTrack／DelayedTrackerSyncQueue可复用，禁止另起追踪引擎。

Android实际TrackPreferences的autoUpdateTrackOnMarkRead键为pref_auto_update_manga_on_mark_read、默认ALWAYS，与阅读完成pref_auto_update_manga_sync_key布尔值分开；MangaScreenModel.markChaptersRead在本地写入后刷新远端、检查进度前移，ALWAYS回写、ASK等待明确操作、NEVER不回写。Desktop目前只有阅读完成autoUpdateTrack及ReaderProgressTracker→ReadingProgressTrackSync→现重试scheduler；详情标记用SetChapterReadStatus，不能复用阅读布尔值冒充三态手动策略。RI11须沿共享port与真实详情消费者接线，同时保护Reader既有链。

DesktopShareService已区分OpenedNatively／SharedNatively／CopiedToClipboard／Cancelled／Failed，WindowsUnavailable native port的文本降级为copyText；macOS异步terminal及临时文件清理已存在。BrowserLoginAdapter／SourceLoginSessionFactory／AuthenticatedSessionCommitter保现cookieJar，浏览器打开不是已验证登录。以上只读固定RI11接口及验收，不记实现、测试或真实账号成功；原账号／鉴权与代理范围保持。

RI11 首组 `interaction-ri11-tracking-foundation-red`（2026-10-01 09:52:06–09:52:37 UTC）FAILED已消费，实际XML09:52:35.782Z：2项失败、0跳过，正确红分别是服务总数100／本地2时合法进度80被本地上限误拒，以及迟到本地加载把用户新进度9覆盖为旧1。最小实现将load／bind／update／unbind共用既有operationMutex，保本地load与实际remote刷新独立命令。实际refresh(track)声明在TrackerProviderPort，真实DesktopProviderTrackerService与Enhanced具体实现已有该port；先前将它简写为service.refresh不精确，实际接线沿原port，缺port给出明确不支持反馈，不改端点或共享协议。原生HTTP→production provider→SQLite→UI远端刷新／重新匹配验证仍待后续首簇完成，不能把此两项unit红声称为集成证据。

`interaction-ri11-tracking-foundation-green` 于09:54:42–09:55:04 UTC PASSED exit0，实施代理当次回执2方法0fail／0skip，根核协调器及load锁／仅service total diff；其XML已被下一focused任务替换，不声称根事后读到了旧XML。后续 `interaction-ri11-tracking-native-red` 实施代理当次XML09:58:58.441Z为4项正确行为失败／0skip，终态FAILED已消费：未绑定query标题初值空、bound缺搜索替换入口、实际页面缺remote刷新、remote10／local2时9.5的+误disabled。最后一项红只到资格guard，尚未执行其后9.5→10 clamp断言，不虚称该分支红已执行；整原生场景绿时须覆盖该分支。新fixture使用真实DesktopTrackerServiceRegistry具体provider、隔离HTTP与SQL、普通Navigator及实际TrackingSettingsScreen，当前900dp viewport的语义事件不外推320／200%字号／浅深／键鼠／正式runtime验收，后者仍按固定范围补齐。

`interaction-ri11-tracking-native-green`（10:01:40–10:02:32 UTC）终态FAILED已消费，根核实际XML10:02:20.746Z：4项中3通过、1超时、0跳过。真实title→HTTP、POINT_100请求／raw score90→SQL／progress80及总数100→UI、fractional9.5→10夹到服务上限三项绿；重匹配已执行搜索／取消保旧对象，确认后5秒超时原因经具体生产parser核验为fixture错误响应MediaList:null，实际bind lookup要求data.Page.mediaList数组。修正只变为Page.mediaList=[]再单独复验，不称超时为业务红、不重复三个有效绿，不修改parser迎合fixture。后续原远端command错误／空／缺失／畸形反馈及保旧／重试矩阵继续本簇精确范围。

手动三态前置接口在同独审收敛：原AutoTrackState含titleRes仅Android app，可沿已有presentation-theme共享表现模式迁原同包enum及同键／ALWAYS默认，domain不新增UI依赖；最高有效章节／前移判断须由两真实wrapper消费最小共享core。ASK冻结原owner/event及原Track行／service／remote身份，确认时失效不套新匹配对象；取消保护不写远端／不重复写入，不禁止Android既有资格判定所必需的远端读取。详情有效绑定数遵循AndroidobserveTrackers实际combine，统计登录且支持作品源的服务绑定并随profile变化，不直接count全部SQL rows。以上为固定RI11验收的实施前接口核对，尚无手动／绑定数／增强入库完成结论。

`interaction-ri11-tracking-remote-green`（10:07:57–10:08:34 UTC）FAILED，实施代理当次回执HTTP403／429／500／空／缺失／畸形＋实际可见重试方法通过；rematch已成功HTTP→真实SQL替换且仅一行，失败是fixture硬要求rowID1保持而实际为2。根独立读取权威manga_sync.sq的UNIQUE(manga_id,sync_id) ON CONFLICT REPLACE与无_id INSERT、TrackRepositoryImpl.insertValues，确认任何InsertTrack均可能更换rowID，修正fixture只核作品／服务／远端身份和唯一行，不改现数据协议。`interaction-ri11-tracking-rematch-green`（10:14:23–10:14:38 UTC）PASSED exit0，实施代理当次回执该method绿；首簇累计7项有效去重绿＝foundation2／native四业务4／错误矩阵1，不把矩阵内部响应枚举数充当测试method数。后手动snapshot须refresh／insert后重新读取实际SQL身份，不能用带旧id的provider响应冻结ASK。

手动／绑定数首红实施代理回执：手动mark的真实SQL已读成功但没有远端Refresh请求，为正确业务红；绑定数场景尚在入口查找NSE，不能计正确红，需执行真实资料列表滚动定位后单方法确认。尚未到的确认／取消分支不得记完成；首簇有效绿证据保留，不重复宽测试。

`interaction-ri11-binding-red-valid`（10:20:37–10:21:08 UTC）FAILED已消费，实施代理当次XML10:21:03.761Z的1项正确行为红：真实资料列表滚动定位后，Tracking入口子树没有有效绑定数，先前入口NSE不计红。`interaction-ri11-manual-binding-green`（10:24:27–10:25:14 UTC）PASSED exit0，实施代理当次TrackingInteractionTest XML10:25:09.678Z回执2项0fail／0skip，根核协调器PASSED；后续专项XML已替换为10:30:01.539Z，不能声称事后根读取了旧XML：实际详情有效绑定1→服务退出0且SQL行保留；真实Factory手动已读→远端Refresh→SQL重读→既有ReadingProgressTrackSync／queue／workflow→SQL进度9。当前原enum同包迁共享表现模块，reader布尔独立；ASK／NEVER／身份取消／批量Library／增强入库及共享Android wrapper尚待后续focused，不能外推完成。

同一独审已读初步DesktopManualTracking及manualTrackProgress，共享core仍需过滤有限非负号，零成功章节必须在任何远端IO前返回，服务正确clamp到总章数不能被反馈为失败或谎报原大号已更新；资格SQL＋profile＋Enhanced.accept已复用。继续原簇必要正确红及绿，不新增宽验证或协议重构。

`interaction-ri11-manual-policy-red`（10:29:34–10:30:09 UTC）FAILED已消费：根核Desktop实际XML10:30:01.539Z的5项Assertion正确红，分别是服务cap实际SQL50而非10、ASK缺实际确认UI、零成功批次错误触发1次refresh、Enhanced入库未匹配、Library实际Factory手动已读未调用远端；domain共享策略1项正确红为NaN导致本应有效9被丢弃。共6项正确红，不代表尚未到的确认／取消／错误／身份guard分支已验证。

根此前“已有workflow会clamp”的判断已用实际源码纠正：独立读取DelayedTrackerSyncQueue.sync/drain/syncOne、TrackerProviderWorkflow.apply和Android TrackChapter，确认当前都无服务上限。D-H5既定上限需在现共享追踪策略最小补齐，由两端原consumer共用，属于同轮独审与范围内必要实现，不新增端点、队列、schema或Reader分页／图片算法。只用旧SQL total不够，实际写入还须消费provider刷新后的正数total；unknown不按本地条目数限幅，didRead自动／手动仅前移不降已有远端进度，用户显式编辑保自己的允许降低分支。旧pending原高水位在有效限幅成功后必须可清理，防止永久remaining，新增并发目标不得被旧完成清除。以上共享高风险接口通过独审前不作为下游已稳定产物，继续focused sync／drain／fresh total／remote ahead／unknown及Android实际wrapper；原Reader追踪契约受影响方法纳末尾唯一affected保护。

`interaction-ri11-manual-shared-boundary-red`（10:38:15–10:39:49 UTC）FAILED已消费：实施代理当次domain XML10:38:39.977Z确认fresh service cap预期10／实际50正确红；Android实际TrackChapter上限及实际manual wrapper各一个唯一方法正确红，各自自动重试三次不计为六项。Desktop四项未到业务，真实initUILayer先创建LibraryFactory、后注册其新依赖ReadingProgressTrackSync，InjektionException不计业务红。将同一既有sync singleton／scheduler注册块提前至LibraryFactory之前，保持原实例及装配，不用未来Injekt惰性访问掩盖依赖错误。

`interaction-ri11-manual-shared-boundary-green`（10:44:02–10:44:56 UTC）仍FAILED exit1。根独立核本次XML：domain两方法全绿（10:44:23.347／543Z）、Android两实际wrapper全绿（10:44:39.741／45.975Z）、Desktop五方法四绿一失败（10:44:42.221Z），合计9方法／8通过／1失败／0跳过。真实NEVER零远端IO与ASK取消保本地且不写进度、零成功集零IO、LibraryFactory手动远端更新、实际cap10反馈通过；Enhanced真实入库尚失败，不能记整批通过。shared测试逐sync／drain覆盖fresh正数total变化、remote已更高、remote超过total、unknown不限制及显式编辑允许降低，矩阵枚举不计额外方法。根同轮接口独审核对fresh no-op Success保远端、队列按原requested目标清理；Android普通update已持久一次，新增no-op须持久fresh，默认InsertTrack.await原本吞Exception的真实失败边界仍须保护及受影响focused，不以注入成功persist证明真实SQL失败安全。

RI11 Enhanced失败原因继续按实际断言纠正：实施代理消费原green并核完整failure，HTTP匹配与真实SQL保存均已执行成功，失败只在最后绑定数UI断言前未render（测试299行），不计为未接afterAdded。根补读addToLibraryUsingDefault确实委托toggleLibrary已有afterAdded接线，不重复增加匹配；夹具改为等待实际Tracking入口子树count1后单方法复验。默认persist吞异常、manual refresh迟到覆盖重新匹配及ASK身份确认仍按原簇补正确红，当前无存活Gradle进程。

`interaction-ri11-manual-identity-red`（10:55:19–10:56:06 UTC）FAILED已消费，根读取当前XML：Android10:55:45.705Z为同一方法三次自动重试，pending预期50／实际null正确红，计一个唯一方法；Desktop10:55:54.141Z为三方法，两失败一通过／0跳过，迟到manual refresh将新remote22覆盖为原11正确红，ASK实际确认SQL REPLACE后身份并拒绝后续rematch已绿。Enhanced本次在等待请求处失败，实施代理说明主事件循环被阻塞；与上次最终count断言失败区分，改为renderUntil真实requestCount>0再取请求并等待实际入口count，不降低SQL或HTTP断言。新缺口合计两个唯一正确红，后未到分支不能计为绿。

身份修复按同轮独审固定：现TrackRepositoryImpl／DatabaseHandler同一事务内比较原row／service／remote／library并写入，InsertTrack提供仅此有限awaitIfMatches结果，避免两次suspend读写间覆盖新绑定；不新增schema或通用事务系统。陈旧false不再把新匹配行纳本次ASK，部分服务只继续本次真实接受身份。默认Android持久失败传播保既有queue重试／取消语义，正常update和fresh no-op成功各只持久一次。全局TrackingSettingsScreen沿现共享AutoTrackState资源补真实三态持久入口，与Reader bool分开；尚待正确红绿及原生验收。

`interaction-ri11-manual-identity-green`（10:58:04–10:59:10 UTC）PASSED exit0已消费，根核Android实际默认持久化XML10:58:50.526Z一方法全绿、Desktop XML10:59:05.131Z三方法全绿，均0跳过。迟到刷新不覆盖新匹配且不提示新绑定、ASK真实SQL REPLACE后确认与后续rematch保护、Enhanced实际HTTP／SQL／界面绑定数已通过；默认Android保存拒绝保原pending50，成功update／fresh no-op各持久一次。同一独审已读真实TrackRepositoryImpl.insertIfMatches：DatabaseHandler现inTransaction=true内读原row并比作品／service／remote／library后写，不先悬挂到无条件插入；原子身份边界通过，可继续后续簇。私有字段映射后续重构需在同事务内复用，不嵌套handler等待。

本轮补核上游app实际RefreshTracks.await为supervisorScope逐服务失败隔离，DesktopManualTracking也须保普通单服务失败不阻断其他适用绑定，CE继续传播；仅本次真实接受身份进后续ASK。已达服务正数总数的manual绑定不能因源更大号误提示更新，前移资格须消费同一readProgressTarget，未知仍允许前移。这些为既定多绑定／进度前移边界，待同簇focused，不引入新并发框架或Reader行为。

`interaction-ri11-surfaces-policy-red`（11:03:08–11:03:38 UTC）FAILED已消费；实施代理回执domain已达cap资格、Desktop三态入口／首服务失败续行／cap不ASK三方法及封面真实Share入口为五个正确红。网页／文本分享初次NSE因fixture missing source未生成URL，不能计业务红；只补实际httpSource后再精确确认。新三态入口复用原TrackingSettingsScreen与共享AutoTrackState资源、既有单键恢复保存及真实反馈，不新增路由或偏好权威。

`interaction-ri11-policy-green-link-red`（11:05:24–11:06:16 UTC）FAILED exit1；根核domain XML11:05:42.806Z一方法绿、Tracking原生XML11:06:12.314Z三方法全绿，0跳过：三态实际点击持久并与Reader bool独立、首AniList403后真实Komga响应仍刷新SQL、fresh cap已达时不ASK。网页及链接分享XML11:06:05.386Z两方法正确红（真实网页入口未调用已注入browser Result，预期1／实际0；native不可用时缺明确复制链接反馈）；修正fixture后已到业务断言，不以先前NSE代替红。封面Share仍待单独绿；整命令没有记为全通过。新设置行搜索词／实际锚点及320dp／fontScale2键盘owner在本簇继续补齐。

`interaction-ri11-links-cover-green`（11:08:32–11:09:28 UTC）PASSED exit0已消费，根核实际XML：Detail三方法11:09:10.029Z、SourceLoginTestMode一方法11:09:23.437Z、SourceSharedState三方法11:09:26.675Z，7方法／0失败／0跳过。普通网页入口真实注入browser Result失败／重试、链接分享复制降级及剪贴板拒绝后重试、实际typed Coil绿色图像转现shareImage PNG并按Shared／Cancelled／Failed终态保留再清理临时文件与Escape还焦通过；四项既有登录characterization保护真实SourceBrowse入口观察、active／terminal共享state、Global恢复原query及stale attempt，未为了红测重写已有正确登录行为。mock账号／隔离fixture不是已授权真实账号或正式runtime证据。

同轮分享独审核具体早到terminal竞态：封面callback可先于withContext返回到达，Opened启动结果不得再覆盖真实成功／取消／失败终态。需沿当前平台backend有限保护与原生真实入口测试，不变share协议／临时文件生命周期；320dp／fontScale2浅深、双向Tab／背景隔离／关闭还焦与新增设置搜索定位仍待本批收口，完整全量／发布仍0。

Android持久拒绝证据的环境边界：测试在TrackRepository接口注入IOException，调用真实InsertTrack、TrackerManager默认persist及TrackChapter／store，验证异常不吞、pending保留与成功只持久一次；没有制造实体磁盘或SQLite引擎故障。Desktop身份保护与绑定／进度集成使用实际SQLDelight数据库。相应命令与方法数量按各自证据记录，不混用两端环境。

`interaction-ri11-native-search-terminal-red`（11:14:58–11:15:29 UTC）FAILED exit1已消费，根核XML：Detail11:15:17.941Z早到终态Completed被后到Share覆盖正确红；Search11:15:22.908Z真实catalog无manual策略正确红；Tracking11:15:26.178Z三方法中320dp／fontScale2缺bounded scroll、Escape不关闭原生edit Dialog为两个正确红，详情往返绑定方法NSE定位失败不计业务红。合计四个正确红、一个尚未到业务，后续修复仅原生可达性／同owner／搜索／终态，不新增产品能力或宽验证。

`interaction-ri11-native-search-terminal-green`（11:25:56–11:26:54 UTC）仍FAILED exit1已消费，根核XML：Detail两方法11:26:32.811Z全绿（早到真实终态优先、网页明确成功／失败／重试）；Tracking三方法11:26:44.566Z中Escape一层关闭／回实际服务trigger通过，320双向Tab断言与详情解除绑定timeout失败；Search11:26:41.246Z结果节点断言失败。合计6方法／3通过／3失败／0跳过。320真实bounded scroll与Close bounds已执行通过，随后Tab visited失败不能把键盘闭环记绿。实施代理诊断unmerged／merged语义ownership、焦点button子Text及当前confirmation owner，根指出搜索旧断言已flatten孩子，须准确说明实际owner修正，保真实输入→结果→route／anchor／持久读回；timeout／定位不当不计新业务红。剩余本批固定remote打开／复制、manual ASK原生owner和本地写拒绝门控按精确focused补，未进入下游或全量。

`interaction-ri11-native-local-boundaries-red`（11:30:22–11:31:17 UTC）FAILED已消费：实施代理当次Desktop五方法中详情导航／搜索取消／绑定／解除后同owner计数方法通过，其余四失败；Android仅编译错误（MockK DSL内解析Injekt.get为dynamic），不计业务红。搜索已通过结果／route而在高亮wrapper的文本断言失败，先前“结果仍缺失”的归因按实际失败行纠正；ASK使用重新遍历构造的SemanticsNode对象做in比较导致失败，修为同owner节点ID，未到还焦不提前记绿；bound远端链接缺真实入口为正确业务红。第二次Android编译缺api.get import亦不计红。

`interaction-ri11-native-local-boundaries-valid`（11:34:37–11:35:16 UTC）仍FAILED，根核Desktop XML11:35:01.108Z Search一方法、11:35:09.145Z Tracking三方法全部通过／0跳过：真实新策略搜索→同route／高亮anchor→ASK保存、bound320滚动／双向Tab、remote Result失败／重试及复制实际URL、manual ASK双向Tab／Escape保本地已读与还Detail Back。Android编译失败独立保留。`interaction-ri11-android-local-read-red`（11:36:39–11:37:28 UTC）单一方法正确红，根核XML11:36:51.463Z自动重试三次，repository写拒绝后远端仍收到9而预期null；计一个唯一方法。最小消费SetReadStatus.Result.Success，失败反馈后不调用Refresh／TrackChapter，Reader协议不变。

`interaction-ri11-final-native-green-format`（11:40:34–11:41:34 UTC）FAILED仅格式，根核实际Desktop三方法11:41:25.846Z及Android两方法11:41:16.695Z共五方法全绿／0跳过：native两个弹层／迟到CAS、Android本地失败门控与原成功共享包装通过。scoped Apply列出长行错误不记整命令通过，随后cached ktlint同实际规范整理31Kotlin／2XML，格式结果以末scoped Check为准。

主代理已实际查看`desktop-interaction-native/ri11-tracking-320-font200-light.png`（浅色服务编辑modal，截图处在实际滚动位置）与`ri11-manual-ask-320-font200-dark.png`（深色ASK）：Windows、JDK21／Compose1.10.2、CanvasLayers离屏、320×680／density1／fontScale2，控件／Close及确认／取消可达。native方法执行双向Tab、背景pointer隔离、Escape和真实trigger还焦；两图分别为上述场景，不外推每类两主题或实机DPI／发布runtime／真实账号。

`interaction-ri11-affected`（11:48:37–11:49:34 UTC）FAILED exit1已消费；根核domain XML11:49:17.420–664Z共12方法全绿、Android11:49:18.411／23.375Z四方法全绿／0跳过，scoped31Kotlin／2XML Check通过。Desktop compileTest失败，TrackingScreenModelTest旧真实constructor被机械误替换成小写helper却仍传use case参数；只恢复实际constructor，不改production，不计产品红。Desktop原filters尚未执行须续验，其他16方法／格式结果可复用。whole-file Check必要的旧区纯机械格式diff保留并说明，不以region规则替代项目检查。

收口同一独审指出multi-provider测试只证明后服务SQL续行，未覆盖首失败服务手动目标：pre-refresh失败被排除候选并由后成功覆盖反馈，导致该意图不进现持久retry。必要修正仅原已读／ALWAYS／ASK失败恢复边界：仍有效的原失败绑定可继续现sync queue，陈旧／CAS false仍排除，ASK取消不写或排队，实际失败／待重试不被后成功掩盖。终态后原实现者先focused红再最小修复，只补影响方法，复用已有绿，不增加全量、代理或独审轮。

`interaction-ri11-manual-failure-retention-red`当次XML11:54:39.524Z正确红为ASK应保原候选[2,6]、实际仅[6]：首服务refresh普通失败丢本次已读意图，后成功还覆盖错误反馈。必要修正保仍为原row／service／remote／library且有资格的失败绑定，陈旧CAS=false仍排除；ASK取消不排队，确认及ALWAYS复用原ReadingProgressTrackSync和持久队列。反馈只读既有store原ID的真实pending显示等待重试，不能因第二服务成功误报全部成功。

`interaction-ri11-affected-desktop-repair`于11:58:37–12:00:05 UTC PASSED／exit0，根核协调器终态与命令范围，代理当次回执Desktop86方法全绿／0跳过及31Kotlin／2XML scoped Check通过。与仍适用的原affected domain12／Android4及登录characterization4合计106有效去重绿；不将原affected编译失败整条改成绿。真实SQL／MockWebServer／现scheduler store覆盖ASK取消零pending、ALWAYS首服务403保raw9 pending及后Komga成功9不掩盖等待重试反馈。原构造误改只恢复测试实际构造；whole-file ktlint要求的旧区纯格式保留，未缩减检查范围。当前无Gradle存活进程，module／full／发布runtime均0。

提交前同一轮独审发现尚未覆盖的具体身份窗口：ManualTracking先核ASK行，shared sync随后按service重读SQL，期间重新匹配可把旧询问套新remote；provider请求等待中重新匹配，成功后原无条件insert也可能覆盖新绑定。此时只是源码风险观察，尚无可控行为复现，不能写已确认缺陷或已修复。已交原实施代理先真实SQL／production sync红测，若复现仅补有限manual身份上下文与现条件写，保原Reader及durablequeue协议，预计增加10–20分钟，在RI11既定预算内，不增加代理、全量或独审轮次。

`interaction-ri11-ask-sync-identity-red`终态FAILED／exit1，根核当次XML12:08:52.119Z两方法均为正确Assertion红／0跳过：shared重读窗口新remote22／library55／进度1被旧ASK写成remote22／library44／进度9，provider晚响应窗口新library55／进度1被旧响应覆为library44／进度9。Deferred只控制真实SQL／production共享sync及provider HTTP调用窗口，不复制算法。缺陷已证实，继续原有限manual expected-binding上下文及现条件写修正，保Reader默认分支、schema和持久队列协议；尚未宣称修复完成。

`interaction-ri11-ask-sync-identity-green`于12:12:04–12:12:59 UTC PASSED／exit0；根读本次XML确认Desktop Tracking9（12:12:50.184Z）＋Reader6（12:12:58.303Z）、domain默认共享sync2（12:12:31.047Z）、Android实际TrackChapter2（12:12:43.220Z），合计19方法／0失败／0跳过，32Kotlin／2XML scoped Check通过。仅新增两个正确红对应方法，17项为直接影响复验，本批有效去重108项。主代理复核真实production与测试：TrackerSyncRequest末尾nullable expectedBinding仅手动传实际SQL快照，shared重读及provider调用前核五身份，成功后现repository同事务条件保存；已失效旧命令不写新匹配、不排新绑定，反馈也核libraryId。两个身份竞态bug已修复。Reader默认null及durablequeue schema／序列化保持原链，无新账号、端点或队列。

同一轮独审与必要修正核验完成。两原生PNG已由根实际查看：320×680、density1、fontScale2、Windows／JDK21.0.11.10／Compose1.10.2，浅色服务绑定弹层真实滚动及双向Tab／关闭还焦、深色ASK取消／确认／Escape／背景阻隔；两种场景各一主题，不外推每类浅深或硬件DPI。网页登录保四个现有真实挂载／恢复／迟到事件characterization，不宣称真实账号验证。capability24／26／39／69／70／82追加本轮真实consumer／test与有限范围；全64条312 current locators已核，21处实际行漂移修正，FIXED_ORIGINAL／status／actionInventory不变。超过8文件／400行仍是同追踪、已读与分享能力的共享契约、production wiring及原生验证，旧区纯格式为实际whole-file Check所需，不拆开不可独立验收的上下文。

本批交付包含测试、实现、必要证据及RI11勾选的同一功能提交；formal runtime／真实账号／硬件及完整Android/Desktop、finalParityAudit、Windows/macOS构建均未执行，统一留RI18，不因尚无正式产物阻断后续RI12实施。

### RI12 书架设置、偏好一致性与恢复证据

RI11已提交 `6ea1078420970a4d827f1a06dd72b54d7116e4b3`，开始RI12时工作树干净。继续复用1名原实施代理，3内聚簇预计3–5小时：默认分类真实持久／入库；共享包含排除完整策略发布及删除引用；分类排序实际SQL清理与旧偏好迁移有限恢复。focused红绿按当前行为、末一组精确affected／wiring／scoped，主代理独审1轮及必要修正核验1轮，root维护唯一契约／报告／manifest与提交。主要成本为真实SQLite／偏好故障及原生事件。module／full／audit／build／runtime均不在本批执行；全部实施后RI18最终一次矩阵。失败仅受影响路径诊断修复，不新增长期双存储权威或通用恢复系统。

先固定DB与Preference非原子边界；共享显式有效值优先、marker最后，消费前有限恢复完整旧策略。S01／S02／S07须有实际消费者与失败反馈；S03–S06仅迁移／值域／端口，实际周期智能元数据RI14、Windows设备RI16验收，新未接线动作不能提前开放。保护RI11新收藏一次增强匹配及真实LibraryFactory DI注册次序。

实际LibrarySettingsScreen仍以Desktop旧周期enum及updateCategoryExcludes CSV显示，categorizedDisplaySettings仅直接set；LibraryUpdateScheduler读取旧Desktop包含／排除CSV。共享LibraryPreferences已有默认分类、两份更新分类StringSet、周期Int、设备／作品限制、元数据和categorized_display；LibraryScreenModel的真实排序投影及设置面板已消费categorized_display。ResetCategoryFlags.await实际写全体SQL flags为全局sort.type＋direction，现设置页未调用，关闭清理需真实wiring及两存储恢复，不另建分类排序权威。

实际DeleteCategory删除所选default_category并清理共享更新／下载分类引用，默认-1；MangaDetailScreenModel.addToLibraryUsingDefault已通过当前categories与共享默认决定直接入库／系统默认／选择草稿。RI12在RI08已稳定收藏入口上复用该链。LibraryPreferenceMigration VERSION2已有显示／排序／列数有效共享值优先与marker最后写，可以追加同风格迁移，不能覆盖旧明确值或让scheduler读取迁移半份策略。这里只读固定接口，无RI12实现／验证结论。

`interaction-ri12-default-delete-red`于12:23:38–12:24:03 UTC FAILED／exit1。首组两项测试覆盖真实设置默认分类入口与实际SQL删除后Preference拒绝；根核实际XML12:23:59.074Z两项均为List has more than one element，DI已有系统默认0、fixture对全部SQL分类使用single，尚未到业务，不计正确红；修正仅按实际新建自定义name／ID定位后focused确认。方法标题所述既有收藏保护尚未执行，不自动计为完成。后续补真实入库／取消及旧收藏不迁移验证。

`interaction-ri12-default-delete-red-valid`于12:26:14–12:26:30 UTC FAILED／exit1，根核XML12:26:25.801Z两项正确Assertion红／0跳过：实际设置没有共享默认分类控件；实际SQL删除后的StringSet拒绝以IOException从用例逸出。已修夹具使用实际自定义ID并保系统0，既有收藏真实fixture已补；红停在入口和异常捕获，后续完整选择／五引用重试／旧收藏保持尚须绿执行，不能提前外推。

`interaction-ri12-delete-recovery-red`于12:29:25–12:29:39 UTC FAILED／exit1；根核XML12:29:35.690Z两项正确Assertion红／0跳过：真实重新初始化后原已删分类引用仍保留；原ID重试又执行已完成的delete，受控拒绝时返回InternalError而非完成清理。有限恢复记录须让启动先补原ID引用及顺序，不以重新删除失效对象作为恢复，也不把普通重新初始化冒充正式进程／实体存储崩溃验收。

`interaction-ri12-default-delete-green`于12:32:53–12:33:30 UTC PASSED／exit0；根核协调器终态及当次XML，LibrarySettingsPolicyInteractionTest四项（12:33:23.205Z）和旧CategoryManagementScreenTest删除回归一项（12:33:27.593Z）均绿／0跳过。首簇默认分类控件与有限DeleteCategory恢复已实现，剩余策略发布／迁移／排序关闭及完整消费者故障门禁尚未实施，不外推RI12完成。主代理同一轮初审发现registerDesktopLibrary丢弃recoverPending结果，持续恢复拒绝仍可进入scheduler消费者；已交原实施者在同批策略Ready状态和现错误反馈中接线及真实wiring红绿，不扩大为整个应用退出或第二后台队列。共享新pending还需核Android实际恢复consumer与共享契约，不能仅凭Desktop启动成功声称两端一致。

`interaction-ri12-android-recovery-red`于12:45:54–12:47:09 UTC FAILED／exit1；根核XML12:46:42.289Z两唯一方法因原pending未消费，默认分类预期-1／实际1正确红，既有自动重试各三次产生6条失败，不记六项。测试执行真实AndroidPreferenceStore／shared DeleteCategory、原CategoryScreenModel及App现start scope，不把JVM启动片段等同正式APK运行。实际CategoryScreen在Loading早return之后才注册events collector，恢复拒绝若保Loading会无人消费错误事件；原实施者选择有限RecoveryError及既有EmptyScreen／Retry ActionButton adapter与同用例重试，补真实Screen wiring红绿，不新增导航pop或后台恢复器。App原IOscope先恢复再现startSync；失败不启动依赖的sync，日志保真实结果；不宣称所有WorkManager／Android S02连续set都有新Desktop完整snapshot门禁。必要验证预计新增15分钟，仍本批预算内，未增代理／独审轮次／全量。

`interaction-ri12-android-recovery-green`于12:57:31–12:58:31 UTC仍FAILED／exit1；代理当次回执模型5方法绿，Screen方法finally匿名父Screen缺key覆盖结果，不能记整条通过。该旧XML随后被单Screen focused替换，根不事后声称读过5模型XML。修正仅fixture固定parent key；根实际核Screen当前XML12:59:22.817Z一方法绿／0跳过，执行真实AndroidPreferenceStore／shared Delete／production CategoryScreen及普通Navigator：错误状态阻断、Back到parent、再进入、实际Retry后显示剩余分类并清原pending，构造及返回上下文正确。原生host使用已存在Robolectric／Compose test依赖，不新增平台框架，不等同正式APK验收。生产错误适配确新增同navigator的Back pop；不得继续简写为无导航调用。

同轮初审实际App调用点只有onCreate启动一次startSync；恢复失败return后分类页Retry成功若无人重新启动sync，可能停用既有同步直至重启。已交原实施者核现启动者及有限完成通知后原scope一次恢复，保原scheduler和协议，不添加周期重试器；当前仅源码风险观察，须正确行为红绿后才称修复。

S01无自定义边界在本场景实施／红测前核定：实际Android MangaScreenModel366–380及Desktop addToLibraryUsingDefault均对default=0或categories.isEmpty直接系统默认；-1有自定义候选才询问。定稿S01原意为暴露已有偏好与真实消费者，未明示取消此SOURCE分支，主代理据上游对齐规则保两端已有语义，补契约／最终设计边界及characterization；不能将控件名称“每次询问”外推为无候选也新增空框。-1有类取消不收藏、0／custom实际归属及旧收藏不搬仍为必做验收，没有把已失败的必要项改成不适用。

`interaction-ri12-android-resume-red`于13:04:14–13:05:10 UTC FAILED／exit1，正确失败及自动retry去重数以实施代理当次回执为准，根仅核协调器而未事后读取已替换旧XML。`interaction-ri12-android-resume-green`于13:06:22–13:07:04 UTC PASSED／exit0；根读当前XML：模型两方法（13:06:45.742Z）及真实Screen一方法（13:06:54.189Z）全绿／0跳过，验证初次原scope恢复及分类重试后原sync一次恢复。根复核DeleteCategory仅新增进程内recoveryReady StateFlow，确认／恢复开始置false，全部pending为空且引用／顺序完成才true；App原IO启动协程恢复失败后一次等待该通知再原AndroidSyncScheduler.start，不自动重试、不造第二scheduler。已恢复后的既有同步被启动门禁停用问题已修复。原正确Screen红在red-valid当时尚未加入匿名parent，确到internal_error断言失败；后green parent key错误是不同夹具阶段，二者不得混为一轮失败原因。

`interaction-ri12-policy-red`于13:12:37–13:13:15 UTC FAILED／exit1；根读当次XML13:13:08.920Z四方法／三正确失败／一通过／0跳过：实际scheduler未消费共享策略，期望[1]实际[1,2,3,4]；持续恢复拒绝未阻断真实update；原设置缺完整三态草稿入口。真实详情默认分类characterization已通过，覆盖有自定义时-1取消不入库、0与custom真实归属、删除默认回退及无自定义SOURCE分支，不把已绿行为算为红。`interaction-ri12-policy-green`于13:24:33–13:25:56 UTC PASSED／exit0；根核终态及当前XML13:25:50.415Z原四方法全绿／0跳过。初审继续核故障边界：当前scheduler对manual类别也读取全库策略，以及失败保存从非法旧策略补偿可能发布Ready；均交原实施者先行为红再有限修复，目前仍为待复现风险，不外推策略原子性或RI12完成。

`interaction-ri12-policy-boundary-red`于13:32:09–13:32:33 UTC FAILED／exit1；根核当次XML13:32:27.379Z四方法／四失败／0跳过。manual当前分类预期更新[1]实际[]，非法旧包含排除校正拒绝后recover预期false实际true，两项为已复现业务红；已交原实施者修复，尚未确认修复完成。另两失败准确停在草稿行预期Indeterminate实际On，以及交叉恢复后的最终用户确认预期空／空实际包含[2]／空；后者此前两次启动不复活旧ID、不自动扩为全库的断言已通过，不能将最后草稿失败记为恢复断言失败。真实事件目标／渲染或业务三态原因待原实施者诊断，不把未明确夹具原因的失败自动计为正确红。

`interaction-ri12-policy-opening-red`于13:49:25–13:50:39 UTC FAILED／exit1；根核XML13:50:27.733Z五方法／两通过／三正确UI断言失败／0跳过。原非法策略校正及manual分类两项已绿：manual不读全库include/exclude，保存前旧策略非法性进入有限requires-choice记录，拒绝后不能变有效。两个弹窗方法新增编辑前真实行状态断言，持久include预期On实际Off、恢复exclude预期Indeterminate实际Off；此前最终点击差异从此初态产生，不记为恢复不变量失败。原三态方法新增两集合重叠行：Exclude优先显示后一次点击预期Off实际On，确认清两侧的正确红。初审观察首次categories列表空而showEditor裁剪raw，原实施者将用实际Repository延迟首发定点验证，并在同入口首快照前阻断／延迟编辑，不仅预先等测试以掩盖真实加载窗口。当前整条命令仍失败，不算整组全绿或RI12完成。

首发延迟 `interaction-ri12-category-first-snapshot-red`于13:51:48–13:52:04 UTC为编译失败：Flow缺emitAll导致类型推断失败，未到业务、不计红。`...red-valid`于13:53:10–13:53:40 UTC FAILED／exit1；代理当次XML13:53:32.627Z一项正确Disabled断言红（首发前入口应禁用、实际未禁用）。根核协调器终态，但随后XML已由下一组替换，不事后声称读取旧红XML。`interaction-ri12-policy-boundary-green`当前XML13:55:28.586Z四项全绿／0跳过，根实际读取；包含实际Repository首发门闩、草稿初态／保存前后拒绝、旧journal与删除跨两次初始化、重叠三态。原生产categoriesLoaded首个真实Flow快照前阻断点击／键盘激活，之后从真实集合建完整草稿；Indeterminate点击同时清包含与排除，两项UI bug已修复。该组加前opening复用两范围绿仍仅覆盖第二簇；排序与迁移、共享契约／search／native及末组affected待完成，不能将四项绿记全RI12验收。

第三簇固定最小S03端口：原0／6／12／24／168小时控件及定时触发读写shared autoUpdateInterval，保旧能力且不长期双写旧enum；显式48／72合法值保持真实值／摘要，暂不新增选项，完整周期／时钟／智能／元数据仍RI14。`interaction-ri12-sort-migration-red`于14:04:15–14:04:48 UTC FAILED／exit1；根核实际当前XML：迁移14:04:40.524Z三方法两红一过，marker2未升级使实际import／retry返回false，72与显式空保护已有绿，不算三红；排序14:04:40.823Z两正确红，SQLflags预期64实际12，重置拒绝后分类开关预期保持true实际false。合五方法／四失败／一通过／0跳过。失败方法仍需绿执行后续值与故障断言，不能只凭返回值红宣称其余已覆盖。

原实施代理于此后发生模型capacity错误、非构建故障；根确认上述Gradle已结束并保现diff与证据，按原代理／模型恢复一次，不新增代理，不重跑旧组。恢复传递当前diff、新红位置、终态与原3–5h预算，主代理仅独立读审／文档。共享Android实际LibraryUpdateJob直接读取分类两键、现没有pending恢复门禁是新增影响范围观察；原App sync／Category门禁不能外推全部WorkManager链已恢复，已交原实施者核现fixture与最小消费者接线成本，尚无此链修复结论。

`interaction-ri12-migration-sort-boundaries`于14:25:54–14:28:31 UTC FAILED／exit1；根核当次XML：Migration14:28:18.833Z三方法全绿／0跳过，旧6h／完整CSV／显式空与72保护、exclude写前写后拒绝后原raw恢复及重试实际执行；该故障方法首次红只停VERSION2无升级，现绿不能据此捏造曾到故障断言的红。PolicyInteraction14:28:19.103Z四方法一过三失败／0跳过，SQL重置拒绝与SQL提交后错误现已绿；新增偏好写后错误仍关闭、删除记录写后失败恢复跳过ack两项为正确业务红。普通排序方法失败为UI等待超时；原实施者诊断实际偏好已false而第二click捕获尚未重组旧checked，改先等待真实Off语义再重开，保SQL与偏好断言，后续复验。不能将七项整条记通过。共享锁内migration完整门禁、实例同步及恢复前再次ack已初审读取；新增偏好／pending错误尚待修正绿，不外推全部journal崩溃／未知周期／AndroidJob验收。

后续focused终态均按原协调器记录保留：`recovery-consumers` 14:34:25–14:37:33 FAILED；`migration-consumer-boundaries` 14:40:52–14:42:31 FAILED，其中实际Android Worker及marker／未知周期已绿，并发fixture临时移除新实例同步保护作真实敏感性红，第一写入仍受门闩时第二调用已越过，随后恢复同步并绿。不能把此红描述成此前VERSION2已执行V3恢复。`native-search-gate-red-valid` 14:53:40–14:56:27 FAILED，新default写前写后／native／并发三项绿；未索引三title与临时移除migration门禁后的实际DI半策略Ready为两正确红，已恢复门禁及真实catalog／anchor。上述旧XML被后续focused替换，根核协调器记录及实施者当次回执，不事后声称读过所有原红XML。

`native-search-green` 15:07:14–15:08:04仅compileTest失败：新增Modifier末参破坏原trailing callback，改回callback末参／内部显式命名，保旧测试；`...green-valid` 15:16:54–15:18:00四项两绿两失败，新Root搜索／持久迁移门禁已绿，旧relaxed mock为新增nullable依赖制造Object→State ClassCast，显式stub null后保原搜索／写回断言。`...repair` 15:19:47–15:20:39旧搜索绿、native失败；`native-migration-final` 15:22:15–15:22:54迁移桥接与RAM journal重新ack两项绿、native失败；`native-focus-diagnostic` 15:24:29–15:25:05及`native-background-diagnostic` 15:26:36–15:27:16保失败。原新测试对背景pointer必须统一关闭或保持owner数的假设不属于固定契约，真实观察为更新弹层可关闭、默认弹层可保持，均不能执行父Back；改为实际背景隔离及Escape关闭一层，未改固定要求为不适用。最后default背景pointer后Escape失败证实缺focusable宿主，最小沿既有dialog模式加首帧焦点。`native-focus-green` 15:28:27–15:29:21 PASSED，完整双向Tab闭环、Space、30分类末项、320→300→320resize保焦点／滚动、背景父Back隔离、Escape关闭一层／还原入口焦点全绿，后在affected再次有效执行。

原 `format-apply` 15:31:38–15:32:11 FAILED保留：五长行不可自动修，缓存同版本ktlint明确输出后只缩短三新方法名及分行，局部CLI退出0。`interaction-ri12-affected` 15:36:09–15:39:16 FAILED／exit1，实际66方法64通过／两失败／0跳过（Desktop53中51通过、domain4及Android9通过）。新timer fixture的InMemoryPreferenceStore每次getInt新对象，设48后读0；改隔离真实DesktopPreferenceStore。旧Root刷新fixture仍写不再权威的旧CSV，期望[4]实际[1,2,3,4]；改调用实际policy.save原范围，保搜索不裁剪、manual分类、导航及阻止重复／原ID断言。均为fixture修正，无production变更。`interaction-ri12-affected-repair` 15:42:41–15:43:23 PASSED／exit0，仅上述两方法及25K／2XML scopedCheck，当前XML15:43:14.738Z和15:43:15.124Z两绿／0跳过。根读原affected归档13套有效结果与当前修复结果按class／method去重：**66全绿（Desktop53、domain4、Android9），0失败／0跳过**；未把重复红／自动retry计为新方法。原归档保失败事实，scopedSpotlessCheck和git diff --check通过。没有追加module／full／finalParityAudit／正式构建。

主代理同一初审与必要修正核验已关闭具体边界：共享Delete写前再次ack／已提交SQL不重复／五引用与顺序、Android App有限恢复通知及实际Category错误／Retry／普通Navigator Back、实际Worker恢复拒绝→既有retry／允许后真实HTTP＋SQL；Desktop完整旧策略发布、异常旧值显式选择、删除与旧journal两次初始化不复活／不自动扩全库、手动范围不读全库策略；S07实际ResetCategoryFlags＋失败原开启／原排序位恢复；V3完整raw／isSet记录、marker写前写后／恢复拒绝、lazy旧desktop/app桥接、实例串行／共享锁门禁、非法周期0及有效选择解除说明。共享contract采用repository seam执行真实Delete，不冒称其fake repository是SQLite；实际SQL证据由Desktop和AndroidWorker集成补足。没有全局恢复平台或长期双写。未设置限制清为旧Desktop不施加门槛，显式用户选择保留；现Library自定义周期筛选gate确消费同智能键，其SOURCE未设置默认在本迁移后不自动施加，不宣称此前该gate从未存在。

root已实际查看本批两张PNG：`docs/evidence/desktop-interaction-native/ri12-update-categories-light-320-font200.png`和`ri12-default-category-dark-320-font200.png`，Windows／JDK21／Compose1.10.2／Skia离屏，英文、320×680、density1、fontScale2，长列表末端及按钮可见；一浅更新／一深默认，不能外推每场景两主题或物理DPI验收。无屏幕像素读取，无正式runtime／账号／硬件验收。25K＋2XML与两PNG围绕同一设置／分类一致性及跨端消费者；超过Estimated scope的内聚性为共享锁、有限恢复与真实DI／SQL／事件须一同交付，风险集中写前ack、补偿与重新初始化。旧测试必要格式变动保留，不以文件数拆不可独立验收上下文。

manifest仅局部更新capability10／16／17／22／90的真实路径／方法／角色与范围，修当前定位4处行漂移；根只读核323 current locators、全部64capability、状态／FIXED_ORIGINAL／actionInventory原值保持。这是索引维护，不作源码扫描替代行为验证，也未执行最终audit。S01／S02／S07本批完成，S03–S06为迁移／值域／原能力端口，完整时钟／智能／元数据RI14、Windows条件RI16。RI12勾选与测试／实现／证据同功能提交；后续RI13继续，不以本批66绿外推整个roadmap完成。

### RI13 完整目录同步（实现、独立验收及提交已闭合）

共享 SourceMangaUpdateService 已提供 getMangaUpdate网络入口，不能重建第二份 source更新协议。实施前 Desktop LibraryUpdateChecker主要追加新 URL并更新章号／memo，分次更新 manga／chapter，尚无完整改名、重排和移除事务。Android实际复用入口是 `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt`，不是 domain中的同名文件；其非本地空响应抛 NoChaptersException，包含去重／名称规范化、recognition、sourceOrder、重复已读与换链接数值状态、dateFetch保护、下载目录重命名。RI13须保持characterization并抽取可共享计划／事务边界，保Desktop作者观察与同步身份；网络／文件不冒充SQL原子，不以当前Desktop标记 COMPLETE作为响应完整性证明。本段为实施前定位的复用边界，完成证据见本节末尾。

补充实际数据边界：ChapterRepositoryImpl.addAll自身一次事务但失败返回空，removeChaptersWithIds捕获并记录异常；现有单项方法不能直接保证整批同步原子。实际schema中history、chapter_pairings／boundaries／revisions、reading_events、sync_private_reading均按chapter ID引用并ON DELETE CASCADE；同步自然键消费者读取作品与章节URL，换链不能仅删后重插再称保身份。实施前将这些边界固定到page-contracts的RI13节，下文保留真实红绿及验收记录。

RI12已提交 `4fe9aab0d9d8cb4c7d3a04b323e74ebaee1deb7d`，开始RI13时树干净。继续原1实施代理，3内聚簇预计4–7小时：实际Android characterization与共享计划／adapter；单本SQL一次发布、事务内最新用户状态及适用身份引用保护；有限文件恢复、提交后下载／作者观察、书架／详情真实刷新入口。focused红绿及一组精确affected／wiring／scoped；root负责接口、独审1轮／必要修正核验1轮、唯一报告／契约／manifest／提交，不重复实施。主要成本为共享提取、实际FK／同步自然键及文件故障／重启。网络工具失败只诊断一次，有不可替代依赖或原范围／预算需扩大时说明；无新代理／报告／长期队列平台。全部full／audit／正式构建仍RI18。首次分派含真实原代码入口、保护边界及停止条件，先Characterization红／共享契约，不以原Desktop只追加的绿测替代完整目录验收。

RI13首轮真实Android wrapper characterization 3项通过；Desktop首轮fixture的SChapter Float编译错误单独保留，不作业务红。修fixture后的 `interaction-ri13-directory-red-valid` 两项真实SQL红分别观察旧目录残留／顺序不一致、唯一换链生成重复章节。接口核对实际SMangaUpdate为普通class，批准可选完整性字段但保二参数JVM ABI及真实拒绝链；旧扩展未标记静默截断无法自动识别。数据入口确认chapter ID能保FK而URL自然键仍需有限alias，固定不改旧journal、冲突拒绝、真实备份往返及多消费者映射门禁；alias不冒充旧wire跨设备传播。以上为实施前边界决定，无RI13完成结论。

身份存储选型已固定：来源chapter.memo按SOURCE被完整替换，不夹本地alias。复用共享BackupChapter，新增可选Proto14 aliases／15 canonicalUrl，旧备份为空默认，保持旧同步wire不变；本地有限SQL映射须与目录同事务，稳定首次URL，真实两端creator／restorer重建。换链唯一性按完整stored／prepared目录核对，不能只在missing／incoming子集唯一就重绑。当前 `interaction-ri13-directory-core-check` 已由原实施者启动，包含原Android3char、原Desktop2缺口及完整目录歧义新增红，尚无通过结论；核心输入冻结，root只读／文档工作。

root实际核 `interaction-ri13-directory-core-check` 2026-10-01 18:04:46.229–18:06:01.996 UTC，FAILED／exit1；Android当前XML18:05:44.566Z三char全绿，Desktop18:05:52.343Z三项两绿／一正确红，合6项5通过1失败0跳过。完整改名／重排／移除及唯一换链保ID／history／progress两原缺口已绿；新增保留重复身份红期望新ID与旧ID不同，实际仍ID2，确认仅missing／incoming唯一会误授换链资格。原实现者继续最小修正完整目录唯一性，尚不外推alias／备份／文件／完整入口完成。

原实施者后续 `directory-core-green` Desktop仍2通过1失败：完整目录identity唯一判定已修，但真实chapters._id不带AUTOINCREMENT，先删最大ID2再insert会分配同ID2，原新／旧身份断言再次失败。批准同事务先INSERT再DELETE最小修正，真正relink仍UPDATE；这不是fixture失败，也不能据此声称所有历史删除ID永久不复用。要求针对无新增仅删后再次新增的实际陈旧引用消费者核边界，若真实错写成立再给现有身份guard最小方案，未证实不扩全局allocator。结果尚待有效green及下游链闭合。

root核 `interaction-ri13-identity-boundaries-red` 18:17:39.229–18:18:29.367 UTC FAILED／exit1；Desktop XML18:18:25.352Z四方法1通过3正确红0跳过。完整目录歧义／本次ROWID顺序已绿；数据库重开后旧URL查询期望ID1实际null、显式不完整service未拒绝、Browse source持久化在注入目录失败后仍返回而未抛异常。最后方法红首先停在拒绝未传播，后续metadata rollback断言待绿执行，不伪称红已执行后半断言。批准复用原metadata映射和作者索引reconcile进入一次目录SQL边界，production两端同writer、预备bootstrap、保latest用户字段；不新建repository或网络协议。

root核 `identity-boundaries-green` 18:27:29.742–18:27:55.037 UTC FAILED，SQLDelight改变参数出现顺序后三调用处String／Long位置不匹配，属编译失败，不记行为绿或红。独立SourceAPI二参数constructor反射方法在该key仍实际执行且通过（XML18:27:39.084Z），不能抹掉整体失败。修为正确具名参数后 `interaction-ri13-identity-boundaries-green-valid` 18:30:16.039–18:31:28.854 UTC PASSED／exit0；root当前XML核Desktop6／0／0（18:31:24.180Z）及Android3／0／0（18:30:59.246Z），9项全绿0跳过。三新增缺口已执行到后续旧metadata／目录保留断言，当前核心含完整性／单本SQL／有限alias首段；备份重建、阅读自然键、真实文件阶段与完整UI消费者尚未闭合，不作RI13已完成结论。

有限文件阶段的落点已批准：本批尚未发布40.sqm内增加单本chapter-directory phase，与目录SQL同提交，记录原对象before／after、实际路径所需作品身份、原observedAt／extension身份／有限观测值和适用added IDs；pending先恢复或阻止新commit，原对象不匹配／文件冲突／失败保记录及可见错误。自动下载仍复用原filter／manager，须明确真实caller所有权并在接受后ack；队列／作者观察的幂等由实际manager／repo证明，不能只凭ack表。记录仅限本机此次有限副作用，不备份传播本机路径、不新建globaloutbox／scheduler，仍在已声明3簇及focused预算内。实现者先补真实reading／history clear／两端backup红，再实施此阶段，尚无完整阶段验证结论。

root核reading／backup归档：原 `reading-backup-red` Android XML18:40:04.429Z同一新方法因默认retry记录三次失败，唯一行为只有1项正确旧URLnull红；Desktop归档另含18:31旧6绿，不算此key新执行，data／Desktop编译问题不作业务红。机械修fixture后 `reading-backup-red-valid` 三新方法实际红：Desktop18:42:05.273Z备份旧引用null、data history18:42:18.430Z清除后期望0实际2000、reading18:42:19.087Z旧resume head期望ID1实际null。`interaction-ri13-reading-backup-green` 18:45:59.309–18:46:57.368 UTC PASSED；root四套归档18:46:45.878／49.452／51.689／52.859Z分别Android备份、Desktop备份、history及reading，各1方法0fail0skip，四唯一项闭合。实际两端creator→codec→restorer→重开及共用存储消费者执行，Proto14 urlAliases／15 canonicalUrl、原memo完整保留；不能把默认retry或旧XML计新增案例。

阶段调用归属已固定：共享目录SQL记录→原Android SyncChapters／Desktop checker／SaveSource适配rename→原时间／extension／值的作者观察ack→原AndroidJob／MangaScreen及Desktop LibraryUpdateScheduler投递原Filter／manager并ack。Desktop详情依据实际Android手动downloadNew资格由真实factory接callback；Browse策略NONE，不自动下载，不裁剪旧自动pending。过滤空结果也需明确完成，接受时固定策略／适用ID，写后异常须由原持久queue幂等证明；重试不拿新prefs或response重造工作集。尚待实施和验证，不表示phase已完成。

root核 `interaction-ri13-sql-state-red` 18:49:29.138–18:50:02.379 UTC FAILED／exit1：data XML18:49:56.763Z一正确真实Reader红，openSession接受旧ID2，先仅删除最高ID后另次新增又ID2，原session.await把新章page写6，期望0；Android18:49:38.053Z同一新方法默认retry三失败，唯一行为1项，目录拒绝后manga memo已提前发布。故仅同事务INSERT在DELETE前不足以保护后续独立新增，风险已由真实消费者证实。批准只针对chapters持久高水位：未发布40.sqm单行表、迁移当前MAX、insert／delete触发只增、既有唯一insert query显式分配更高ID；不重建FK、不改publicID／同步键／下载身份，须空表／删全部／重开／回滚／历史显式ID及溢出边界。不能声称知道迁移前已删除的历史最高ID。原实施者同时修Android提前memo写入，字段与目录同交易，保FetchInterval事后边界；尚待真实green及独立数据门禁验收。

`interaction-ri13-sql-state-green`初次仅SQLDelight触发器解析失败，未执行行为测试；原实施者沿仓库lowercase new／old修正。`...green-valid` 18:57:58.708–18:59:42.374 UTC PASSED／exit0，root归档核data18:59:08.104、Desktop18:59:27.222、Android18:59:29.264Z三唯一方法各1通过0失败0跳过。真实旧Reader不再改写新章；floor显式ID100→101、未提交回滚→102、删全重开→103，缺floor／Long.MAX拒绝并保目录均执行；Android组合memo与目录拒绝回滚已绿，FetchInterval事后策略未冒称SQL原子。root实际读取了floor schema／migration／insert与真实测试，以及alias distinct.single／owner guard、metadata共用helper与restricted字段；全批独立初审仍待phase／完整wiring／升级证据齐全后收口。

root核 `file-backup-boundaries-red` 当前归档19:03:05.874Z三方法2通过1正确红0跳过：真实下载图片在目录改名后按新身份读取为null；Desktop alias restore冲突保既有单本目录、legacy protobuf空alias字段／原memo两项已绿，为补充兼容证据，不虚构它们曾失败。phase原provider改名及崩溃／重启／队列接受验证待原实施者完成，未执行module／full／正式构建。

root核 `interaction-ri13-file-backup-boundaries-red`：Desktop XML19:03:05.874Z三项2通过／1正确失败，真实换链后下载图片通过新身份查找为null；alias restore冲突及旧protobuf默认字段为兼容补证绿，不冒充新增红。`file-phase-green`为两处生成API／返回类型编译失配，未执行行为测试；修正后 `interaction-ri13-file-phase-green-valid` 19:19:09.013–19:20:06.492 UTC PASSED／exit0，归档XML19:20:02.654Z三项0失败0跳过。真实目录换名后provider查找新身份、解码原PNG像素成功；两备份边界保持绿。单本有限phase已入同一未发布40.sqm；完整两端caller、作者／下载ack故障、UI反馈及升级门禁尚未完成，不能以该三项勾选RI13。

root继续本批唯一初审：实际SQL事务先取最新manga／chapter／excluded scanlator，先验证完整响应和alias所有权，目录源字段只更新metadata而保用户读态；metadata限制不改收藏／笔记／flags／source identity；真实creator同事务reconcile，网络在事务外。phase ack核原ID、effects／固定下载集合和单调完成状态；新目录必须先恢复旧phase。实际 `interaction-ri13-phase-ack-red` 归档XML19:27:25.489Z为2项1通过／1正确红、0跳过：文件collision保原及目标字节、移动后ack拒绝重开恢复通过；作者真实repo用原时间／扩展版本重放使sampleCount2→4，需完全相同观测去重并保不同时间历史。Android旧rename返回Unit且吞拒绝，不能直接当phase成功；原实施者继续同范围最小失败传播及两端caller。以上尚非完整独立验收结论，不重复实施或新增审查轮次。

root核 `interaction-ri13-android-phase-red-observation-green` 19:30:09.728–19:31:50.732 UTC整体FAILED：Desktop19:30:52.334Z作者真实仓库ack拒绝后重放与domain19:30:52.433Z完全相同观测去重各1绿；Android19:31:24.160Z同一方法框架retry三次正确红，rename IOException后pending为空，唯一新缺口1项。随后 `interaction-ri13-queue-observation-red` 19:53:41.307–19:55:06 UTC整体FAILED：Desktop19:55:01.705Z两正确红分别真实DI既有queue下目录已提前改变、旧phase重放把current从(version2,90000001)回退到(version1,2000)；data19:54:43.999Z共享SQL目录两契约绿，覆盖增改排移／最新读书签页码／全目录歧义。该key Android因新provider误用UniFile.extension编译失败，归档含旧19:31XML，不算重新执行或业务红。原实施者在现有下载边界增加有限lease及提交前拒绝，消费实际命名偏好，恢复核最新持久身份；尚待同精确范围green。

root核 `interaction-ri13-queue-observation-green` 20:02:05.958–20:03:53.720 UTC PASSED／exit0，Desktop20:03:41.069Z两项0fail／0skip：真实DI受影响queued对象在SQL前拒绝且保目录、仅sourceOrder变化通过；旧观测时间回放保较新时间戳current版本及同bucket既有证据。Android20:03:34.426Z四唯一方法0fail／0skip：原SOURCE3项及新增真实wrapper rename拒绝保phase／数据库重开后恢复方法，后者不能冒充真实Android filesystem验收。root实际读现有manager.withDirectoryChanges：短锁核queue／入队preflight／retirement／active producer后有限占用旧IDs，操作在锁外，finally释放；事务核最新affectedDownloadIds（含删除及实际有效标题变化），正常DI在lease内完成SQL和文件阶段，释放后作者及新下载投递，避免自身占用拒绝新ID。SourceDateQuality retain按完全相同对象去重、evaluationTime不回退同bucket已有时钟，current按incoming原时间有序upsert；SOURCE等时语义未重写。真实两端文件／accepted queue ack故障、HTTP／UI反馈、40→41升级及affected／scoped仍待，不标记RI13完成。

root核HTTP／native归档：`http-feedback-red` Desktop20:08:30.817Z真实MangaDex后页缺data提交partial目录1正确红，native20:08:32.022Z详情缺源无可见拒绝1正确红。`http-feedback-green-queue-prompt-red` HTTP20:14:13.533Z该方法1绿，body含空完整目录、malformed、403／429／500、后页缺data拒绝及最后成功目录；native20:14:14.937Z两方法1绿1正确红，缺源反馈已绿，真实HTTP+DI+queue拒绝出现Failure但没有完成／取消下载后重试的可见指导。root读取分页loop继续指出后页显式空data／total缺失或回退仍须按同一完整性矩阵验证，不能用缺字段一项代替所有声明剩余却未取得的页。上述均为现范围风险门禁，不额外完整模块测试。

root同一初审继续核Android实际队列：downloadChapters同步调用Downloader.queueChapters，但后者对缺HttpSource可直接返回Unit，DownloadStore.addAll使用异步apply，均不能单独证明目录阶段的持久接受；addDownloadsToStartOfQueue原新增guard仅锁内check、锁外重排，仍存在交错窗口。已交原实施者在原DownloadManager／Downloader／DownloadStore增加目录专用实际确认和有限队列占用保护，要求底层commit的Boolean拒绝保pending、匹配既有对象／已下载分支有实际证据；不全局改普通队列、不建立第二权威。新分页反例采用真实500条首分页／total501，后空页／缺total／回退total须保完整旧目录。当前测试和实现仍在进行，此段不作修复完成声明。

root核 `interaction-ri13-finite-consumers-pagination-red` 终态FAILED，20:30:20.156Z Desktop目录3项2绿（实际persistent queue ACK重开／linked Browse不抓新网络，creator index拒绝整事务回滚）和1正确分页红（后空页／回退total仍提交partial）；native20:30:09.588Z提示断言已过、Retry等待超时，实际定位combined bridge并发使FIFO HTTP响应错配，改按请求URL dispatcher；data为manga.status必填夹具失败，Android同文件方法retry3次为mock builder失败，均不算正确业务红。后 `interaction-ri13-pagination-green-android-acceptance-red` 20:35:21.624–20:36:11.762 UTC整体FAILED仅Android新getApplication泛型夹具编译：Desktop目录20:36:02.540Z两绿验证真实500条首分页后空／缺total／回退total拒绝及真实500+1共501SQL章成功，native20:36:05.463Z一绿验证真实HTTP＋DI＋queue冲突提示→取消适用下载→点击Retry→同owner目录；data20:35:43.476Z一绿验证40→41保页码／MAX种子41／alias FK删除／后续ID42。Android未执行，不使用旧XML。上述有效结果0跳过；原实施者继续Android实际文件及持久入队门禁，然后精确affected／scoped，尚未勾选RI13。

Android后续红前提更正：`interaction-ri13-android-acceptance-red-valid` 20:36:47.362–20:38:23.511 UTC FAILED，queue XML20:37:06.729Z三方法各retry3（9条）仅reorder可插受保护ID为正确红；缺Http／commit拒绝fixture作品favorite=false使真实downloadIds为空，两断言不是接受缺口的有效红，不能记为产品原因。文件XML20:36:56.070Z同方法retry3未抛目标collision，也不是已证实产品问题。下一Androidkey 20:42:24.462–20:44:26.003 UTC仍FAILED，reorder一绿，其余受上述空工作集及文件偏好前提影响；文件实际path诊断为中文而冻结策略要求hex，已证实InMemoryPreferenceStore getter重建使set真值丢失。实施者改用真实AndroidPreferenceStore并断言读回、固定favorite及非空downloadIds；未有效红的两接受入口按原Unit路径重新证实失败，再恢复目录专用直接commit确认。所有旧失败保留，不计framework retry／夹具错误为新增方法或正确红，不更改production文件策略以迎合错误fixture。

Android有效接受红与green闭合：根核20:46:58.163Z queue XML，真实favorite=true且phase.downloadIds.size=1前提下，缺HttpSource／同步commit拒绝／restore launch失败后join放行三个方法各retry3为三个唯一正确红；file20:46:50.935Z一绿为真实UniFile路径、目标冲突保两份字节、ASCII true实际读回、作品／章节重命名与重复调用，不冒称1/2/3字节PNG可解码。`interaction-ri13-android-nonempty-acceptance-green` 20:54:29.016–20:55:40.391 UTC PASSED／exit0，DirectoryDownloadAcceptanceTest六唯一方法0fail／0skip：原reorder guard、restore失败拒绝、同步接受队列重开与matching重试保ERROR／顺序、缺HTTP不ack、commit false不ack／不发布新队列、既有下载目录不重复入队。根实际核 DownloadStore仅本批键直接Editor.commit并保既有matching序列化顺序；Downloader仅目录确认路径、复用原restore Job及completion cause；Manager lock外等待、lock内检查与变更，普通入队仍原语义；Androidwrapper使用latest持久作品与实际Boolean确认后ack。最后精确affected／wiring、配对引用及scoped尚待，RI13仍未完成，无module/full/build追加。

RI13 最终精确affected归档闭合：首轮 `interaction-ri13-affected` 21:11:09–21:12:16Z 为编译失败，已执行domain1／dataAndroid5／sourceAPIAndroid1共7方法全绿与62Kotlin／2XML scoped Check，Desktop及app未执行；格式Apply此前两处max-line-length失败保留，不作为行为红。`affected-continue` 21:13:57–21:16:34Z dataJVM25全绿，app18唯一方法17绿／1匿名Source捕获导致MockK签名夹具失败，Desktop两旧Fake读取调用编译失败。`affected-fixtures` 21:18:01–21:19:49Z app该方法已绿，Desktop58唯一方法56绿／2夹具失败：SQLite线程连接未启FK导致配对删除不级联、HTTP raw JSON机械换行带入拼接符；均保留原断言，不记产品红。`affected-repair` 21:21:49–21:22:14Z 两方法通过；FK连接Properties修正影响共用Storage，因此 `directory-fk-green` 21:22:48–21:23:17Z 精确目录类21方法全部复验通过，native修正原结果复用，不扩大为58方法重复验证。

根独立读取五组归档XML，以平台／类／方法去重并采用最后有效结果：108方法全通过、0跳过，Desktop58／dataJVM25／app18／domain1／dataAndroid5／sourceAPIAndroid1；无旧XML或framework retry充数。62Kotlin／2XML scoped格式通过、git diff --check通过；manifest332条当前locator及受影响behaviorMethods核验通过，64项原status／FIXED_ORIGINAL／actionInventory不变。当前功能批76实际代码／测试文件，加必要契约／报告／manifest／checkoff，超过估算仍围绕一个完整目录交付：源→共同计划→单本事务→有限恢复→原消费端；不为行数拆开身份与恢复边界。

同一独立初审及必要修正核验已闭合：根核最新状态合并、配对三表保ID／重开、canonical别名冲突及40→41高水位升级、实际下载占用／持久接受、观测重放、HTTP完整性和两条真实详情反馈；末次FK环境与JSON夹具未削弱断言。实现代理结构化回执已验证并停写，全部Gradle终态已消费、无运行进程。必要checkoff随本功能批提交，不另建状态提交。边界：未标记旧扩展的静默截断无法自动识别；迁移floor仅知当前MAX与未来分配；文件不与SQL原子、本机phase不备份、alias不扩旧同步wire；Android文件证明真实字节／冲突，不宣称PNG像素解码。全量、发布runtime及实机输入仍留RI18。

### RI14 更新容错、精确恢复与策略（实施中）

现 DesktopTaskScheduler 已有持久 workset／worksetInitialized／completedUnitIds／failedUnits 与 checkpoint，不应重建调度器或通用outbox。当前 LibraryUpdateScheduler虽然保留stableIds，恢复时仍从重新按分类策略过滤的byId取目标；一次错误后break且没有区分跳过／未处理／失败全工作集。RI14在现有store及任务生命周期内补书架专用范围／结果恢复，保作者独立任务触发与CancellationException语义；只删除break不足以证明恢复完成。RI12文本误写S03–S06由RI15／16验收，已按已有总映射纠正：周期／智能／元数据RI14，设备限制RI16，不改变产品范围。

补核当前实际恢复／时钟边界：DesktopTaskScheduler.register在旧status=Failed时保留workset／completedUnitIds，即使caller生成新的idempotencyKey；RI14必须明确“重试原任务”与“新刷新”而非仅改key。现FetchInterval已共享，toMangaUpdate收到0窗口时getWindow(ZonedDateTime.now())、lastUpdate=0时Instant.now()，控制Clock用例须覆盖真实这两个分支，不能另写测试预测器。AndroidLibraryUpdateJob真实四规则及ONLY_FETCH_ONCE已核，未开始阅读仅totalChapters>0时跳过。实施前验收固定于page-contracts的RI14节；没有RI14实现／测试证据。

根追加只读治理核对发现旧parity-governance对历史behaviorMethods要求精确集；RI13新增方法不能直接并入旧审计字段。已修正本批manifest：保64项历史集合原值，新增证据按capability的interactionIterationEvidence.RI13登记；同一manifest仍是状态与证据权威，报告仅说明真实运行结果，旧statusDecision不重写。新Android CURRENT_ANDROID角色路径补入对应consumer声明。此为本批JSON证据修正，已amend唯一RI13功能提交，未改108通过的产品diff，未新增状态提交；精确四项manifest／历史治理检查加入下一次串行focused机会，不运行finalParityAudit或全量。

RI13已提交 `33276c6e01556d33b4d80d01e33fda2c9566e75a`，108项有效focused／独立验收／332当前locator闭合，开始RI14时工作树干净。复用原1实施代理，三内聚簇串行预计4–6小时：书架专用固定范围与逐本结果／恢复；共享智能候选与可控Clock持久周期；元数据开关、原合并与FetchInterval真实设置／反馈。根负责接口／唯一独立初审及必要修正核验、同一报告／manifest／提交；仅当前行为focused红绿、精确affected／wiring／scoped，全量／audit／正式发布仍RI18。主要成本为JSON兼容、单本提交与checkpoint崩溃窗口、两端共享规则及原生UI接线；不重建scheduler，不在无证据旧任务上扩全库。接口风险、不可替代环境依赖或超原范围／预算时先说明具体追加条件。

根核 `interaction-ri14-recovery-red` 2026-10-01 21:37:42–21:38:19 UTC FAILED／exit1，归档recovery XML21:38:08.970Z：三唯一方法0跳过，ABC预期[1,2,3]实[1,2]，fresh分类未执行原范围外第3项，legacy无固定范围仍请求全库，三项均为正确业务红。同key附四精确治理检查全失败，单独归档，不计RI14行为红：exact-contract在RI12的ID10 ADAPTER kind，task3在RI09的ID24新增历史集、task4在ID54新增历史集，task5在ID95旧测试名已变成explicit intent。RI13新增方法已独立保存并保历史集合原值，不能把旧批治理漂移抹去或推断最终全量通过；根负责RI18受影响索引／治理修正，不让实施代理扩大本批恢复范围。

RI14单本checkpoint接口批准：沿RI13本机phase增加可选书架occurrence／unit receipt，目录同SQL接受；真实文件／作者／下载均完成后保留完成phase，TaskStore实际completeUnit持久成功后才精确ACK清理。旧caller无receipt维持原语义，不新表／全局outbox／wire。验收固定effects全完成而checkpoint失败重启不再抓源／新observedAt／重复下载，checkpoint已成功而ACK失败仅清理旧receipt，取消后不改Cancelled亦不使未来新刷新永久卡住；原对象变化拒绝盲消费，旧范围不能套到新occurrence，新增count与ID来自原phase。尚未实施绿，不作恢复已完成结论。

根核 `interaction-ri14-recovery-green` 21:41:33.037–21:42:07.791 UTC PASSED／exit0，XML21:42:06.193Z三唯一方法全绿0跳过：ABC继续且持久成功1／3及失败2，新分类刷新改为范围[3]，legacy无固定范围不请求全库并反馈失败。仅首组三绿，真实receipt跨store／Clock／metadata／UI／其他后台任务兼容仍待，不能据此勾选RI14。根初审继续核原unit source/url不可替换、初始读取悬挂时取消、SINGLE真实入口，以及checkpoint完成但phase清理拒绝和Cancelled晚到边界，原实现者在后续冻结点补验。

根核 `interaction-ri14-receipt-red` 21:46:41.751–21:47:17.367 UTC FAILED／exit1，归档XML21:47:10.778Z真实MWS→SQL／creator／PersistentDownloadStore与实际Manager→FileTaskCheckpointStore两方法各正确红：真实队列及作者观察后checkpoint拒绝，SQLphase为空；DELETE phase拒绝时completedUnitIds期望[1]实[]。两方法先停在原SQL凭据与顺序断言，重开后无额外HTTP／无新观测／无重复队列断言待绿执行，不冒称红已覆盖后半。另一XML21:47:15.452Z两守卫正确红：原unit source/url替换仍返回true、libraryProvider悬挂时取消返回false。四唯一失败0跳过，无夹具失败或重试充数。最小receipt确认callback沿同TaskScheduler真实实例，非task入口先恢复原授权阶段与确认，Cancelled终态不改变，新范围须先收旧凭据再建立，尚待实施／有效绿及独审。

根核 `interaction-ri14-receipt-green` 21:59:16.639–22:00:53.813 UTC PASSED／exit0，归档两个XML22:00:46.694Z／22:00:51.628Z各2方法全绿、共四唯一0跳过。两跨store方法已执行后半重开：HTTP请求数保持1、旧quality快照／persistent queue／章URL及原新增数不变，成功checkpoint后ACK拒绝仍保完成集合，重启只补清理；query等待可取消、source/url替换完成记录被拒。默认receipt=null沿原phase流程，DI源码的callback接到既有DesktopTaskScheduler绑定；当前两跨store测试直接传同一真实TaskStore确认，完整factory／DI wiring尚待，不冒称已执行该接线；根继续核failedUnits与context一致、Cancelled终态及遗留阶段。

同一独审尚有后续风险门禁：failed-only选择必须读取收旧receipt之后的最新context，不能拿旧FAILED快照再次抓源；新book任务遇既有无receipt的Browse／详情phase须先恢复原授权范围而非永久拒绝；已保存SUCCESS后的清理失败不能假称该unit是FAILED，界面需给原任务恢复入口，只有实际FAILED集合才给逐项重试。上述纳入当前簇的实际red／green与UI，不追加通用任务平台或全量。RI14尚未完成。

根核 `interaction-ri14-receipt-boundary-red` 22:04:45.504–22:05:09.179 UTC FAILED／exit1，归档XML22:05:01.764Z两唯一正确红0跳过：显式retryFailed在旧effects已提交但checkpoint拒绝后发第二次HTTP（期望1实际2）；旧无taskReceipt阶段使新书架任务Failed（期望Completed）。原实现者已按同一初审修恢复后最新context与先收旧非task阶段、再进行当前任务同步；普通阶段的原策略和新增数不归新scope，FAILED清理已成SUCCESS时恢复原occurrence而非创建空重试。helper／控制流受影响，下一组仅3首绿＋4receipt＋2新增分支共9方法复验，不扩大module/full；有效结果待核。

根核 `interaction-ri14-receipt-boundary-green` 22:09:16.859–22:09:49.101 UTC PASSED／exit0，XML22:09:42.061Z Occurrence4及22:09:47.849Z Recovery5共9唯一全绿0跳过。恢复后重新读取原context；显式失败重试在已完成凭据确认后保原新增数／原occurrence，无第二次响应；旧无receipt阶段先按原effects完成，再当前task实际请求，新scope只计当前新增。首receipt方法因格式上限缩名 `completed effects survive checkpoint refusal and restart without another response or redelivery`，同一行为不新增唯一数。根继续同一初审核取消仅未处理且零FAILED时retryFailed不可执行B/C（明确resume才可续），原取消／late error／初始store拒绝及真实UI仍待；周期／smart／metadata尚未动，不声明RI14已闭合。

根核 `interaction-ri14-launch-cancel-red` 22:18:52.141–22:19:20.996 UTC FAILED／exit1，XML22:19:19.430Z两唯一正确红0跳过：Cancelled且无FAILED时retryFailed实调用[1,2,2,3]而应保[1,2]，未处理被错误执行；新TaskStore接受拒绝后outercatch仍尝试写旧terminal，又抛IOException checkpoint unavailable，未到本次可见失败通知。初始拒绝方法已执行旧Completed保留／无新业务前置，不把未执行的通知后断言计为通过。原实现者继续修两边界，后续真实Root／factory终态与初始存储错误反馈仍待。

原生结果红前提更正：`interaction-ri14-results-native-red` 22:21:48.367–22:22:34.366Z FAILED，XML22:22:28.168Z两方法在菜单后更新action点击处NoSuchElement，尚未绘制popup、未达到目标业务断言；该轮不算结果能力已证实红，不改生产入口迎合fixture。补真实事件后render，`results-native-red-valid` 22:23:17.720–22:23:56.136Z FAILED，XML22:23:46.339Z两唯一正确Assertion红0跳过：真实MWS／SQL／Task Failed后Root没有持久四态结果；旧Completed及新接受拒绝、无新增HTTP前置已满足，但Root无本次失败反馈。DI测试仅增加原FileTaskCheckpointStore构造factory末尾参数，production默认路径不变；SourceManager／Checker／Scheduler沿实际同实例。后半重开新model与仅失败retry事件待绿执行，失败明细要求用户可识别的Work b，不以内部URL key放宽。

- 取消／启动反馈 focused 复验：`interaction-ri14-results-green` 于 2026-10-01 22:27:52.842–22:28:40.921 UTC 结束，整体 FAILED。归档 `ri14-xml/results-green/` 中 Recovery 两方法实际全绿（22:28:39.720 UTC，0 skipped）：取消后无 FAILED 的仅失败重试不恢复 UNPROCESSED；初始持久接受拒绝保留旧 Completed 并报告当前启动失败。两个原生方法则在 DI 创建 ScreenModel 时先于 `Dispatchers.setMain()` 访问 Main（22:28:37.638 UTC），未到 UI 目标断言，属于夹具／初始化顺序错误，不能视为有效业务红测或完成证据。实施继续限定同两原生方法，真实 Root 挂载订阅与测试 Main 初始化须复验；同时补失败作品可见 HTTP 分类原因的原生断言。

- 原生结果／原因复验：`interaction-ri14-results-reason-red` 于 2026-10-01 22:35:01.867–22:36:04.073 UTC 结束 FAILED。原生两方法中，拒绝启动反馈已绿；逐本结果方法已显示真实四态计数，但测试用相同 key Root 替换 Root 仍复用 owner，停在 owner 不同断言，尚未执行新增 HTTP500 原因断言。不能将此次失败记为缺少原因的业务红测。改用真实书架设置页面替换、返回 Root，先证明旧 owner 销毁后可重新读取持久结果，仅复验受影响的方法，不修改 production key 来迁就测试。

- HTTP失败明细有效红测：`interaction-ri14-results-reason-red-valid` 于 2026-10-01 22:36:58.065–22:37:18.812 UTC 结束 FAILED。原生唯一方法 XML 22:37:10.600 UTC（1 failed，0 skipped）已执行真实 Root→书架设置→新 Root owner，验证原四态结果持久可读，且失败作品标题 Work b 可见；准确在实际 HTTP500 本地化原因缺失断言失败。后续使用现 production 错误分类映射补明细，保持同方法 green，不重新维护异常解析或显示原始 URL／实现字段。

- 逐本结果／真实重试原生绿测：`interaction-ri14-results-reason-green` 于 2026-10-01 22:38:58.885–22:39:28.771 UTC 结束 PASSED。归档唯一原生方法 XML 22:39:19.649 UTC（1 passed，0 skipped）完整执行真实 HTTP A成功／B500／C成功→SQLite→Root 四态展示→书架设置替换→新 Root owner 持久读回→Work b 与本地化500原因→真实仅重试 B（请求 a1/b2/c1）→各作品 SQL 各1章。先前拒绝启动 Root 与取消／接受拒绝两个 domain 绿测仍适用；进入共享智能候选、可控周期时钟簇，尚未进行 RI14 批次最终受影响复验或全量。新增 unit failure 复用 StoredAppError，仅本机书架 JSON 可选字段；重试开始、成功、跳过和完成凭据恢复均清除旧失败，界面复用现有 source 错误分类映射。

- 智能／周期测试首次命令：`interaction-ri14-smart-clock-red` 于 2026-10-01 22:42:05.893–22:42:21.561 UTC FAILED，仅新 Desktop fixture 缺 Chapter import／MockK DSL 歧义与 domain 旧 `src/test/java` 未注册导致未找到新方法，未形成正确业务红。新 FetchIntervalClockTest 改到实际 `commonTest`，继续同三 focused 方法，未据此改 production 策略。独立核对 SOURCE 时确认 Android 缺失源经 `getOrStub` 的 SourceNotInstalledException 进入 failedUpdates，现 Desktop 亦返回错误；故缺失源保持可见失败及重试，不能将其降级为智能 SKIPPED。本地免远端与五个既有规则跳过保持独立，测试预期须在实现前校准。

- 智能与时钟红测校准：`interaction-ri14-smart-clock-red-valid` 仍是两个新夹具编译错误（缺 GetChaptersByMangaId import、Chapter.chapterNumber Float／Double），未提供业务红。`interaction-ri14-smart-clock-red-valid2` 中真实 SQL／source 智能方法 XML 22:46:24.125 UTC 准确以远端请求期望1、实际6失败，保留为有效智能限制业务红；周期方法 XML 22:46:27.674 UTC 则用了每次 getter 新建偏好的 InMemoryPreferenceStore，期望[1]实际[]不能证明时钟缺口，改用现有隔离 DesktopPreferenceStore。domain 新 common 方法因推断非 Unit 未被 JUnit 发现，显式 runBlocking<Unit> 后再重验。后续只重验两个无效时钟方法，原智能有效红不重复执行；共享规则测试按各规则独立开关与优先顺序覆盖，不能以默认所有限制下 unstarted 被 HAS_UNREAD 提前拦截冒充 NON_READ 分支覆盖。

- 周期与预测有效红测：`interaction-ri14-clock-red-valid` 于 2026-10-01 22:48:08.448–22:48:21.817 UTC FAILED，归档两方法各1 failed、0 skipped。真实隔离偏好／FileTaskStore 周期方法 XML22:48:20.507 UTC 首个预期空请求、实际[1]，证实 restart 不应把普通 poll 当 due check；共享 FetchInterval 方法 XML22:48:16.032 UTC 在传入2001时间的未知 lastUpdate 分支期望2001-01-08、实际2001-01-04，证实墙钟泄漏。独立核对发现该测试原 nextUpdate=0 不能单独检出 zero-window 分支，要求再补 nextUpdate 位于 suppliedTime 窗口的 implicit 保持断言，先红后实现，以免仅修未知 lastUpdate 就使两个分支的宣称覆盖失真。

- 智能最小绿／追加边界红：`interaction-ri14-smart-green-window-red` 于 2026-10-01 22:59:10.907–23:00:41.362 UTC FAILED。Desktop周期方法真实绿；真实SQL／source智能方法已通过1成功、6跳过、1缺源失败的原断言，再在实际恢复源／仅失败重试后 sourceUnavailable 仍 true 断言正确失败。初始化取消方法正确以旧检查时间123被推进为1790899200000失败。domain共享规则矩阵两方法及未知 lastUpdate Clock 方法绿，新增独立 zero-window 方法正确以2001-01-01预测被改为2001-01-08失败。Android实际 worker 方法因新 SChapter Double／Float 夹具编译错误未执行，不算业务红或兼容证据。后续最小修复初始化接受／取消边界、重试与完成凭据的旧缺源标记清理、zero-window改用 supplied dateTime；同范围 focused green 后再接Android共享消费者。

- 智能／时钟 focused 绿与Android夹具区分：`interaction-ri14-smart-clock-green` 于 2026-10-01 23:02:01.350–23:03:33.263 UTC 整体 FAILED；归档 `ri14-xml/smart-clock-green/` Desktop三方法、domain四方法均真实绿、0 skipped（智能候选／缺源恢复、初始化取消、不重复周期、独立规则矩阵、两墙钟分支）。Android XML有同一唯一新worker方法的3条执行记录、均失败（XML23:02:39.414 UTC，0 skipped），不是3个独立方法；新增智能方法候选调用集合正确，但 relaxed DownloadManager.withDirectoryChanges 未执行实际 operation，未达到目标目录SQL／memo；故未提供Android持久化或共享消费者兼容绿证据，修平台port夹具后仅复验该方法。预测更新方案固定为现单本 phase 的可选原时间／窗口与 predictionPending（默认false，非schema／backup／wire）；只有预测repo更新真正成功并阶段ack后，才可确认effectsComplete和书架checkpoint，恢复不得更换原时间／设置或误报未保存预测为成功，随后用真实SQL拒绝红测验证。

- 元数据／预测／设置有效红及Android characterization：`interaction-ri14-metadata-settings-red` 于 2026-10-01 23:09:32.788–23:10:05.608 UTC FAILED；Desktop四方法准确分别在开启auto元数据后description仍旧、实际repo Boolean拒绝与Exception拒绝均未被消费、48h真实设置控件缺失断言失败。Android原worker唯一方法已绿，实际Sync SQL／memo／read／bookmark／page与候选请求集合均执行，可据此改用同共享智能helper后再保护。metadata回滚测试将在目录拒绝前把远端description／author改成不同值，不能以原等值映射通过冒充原子边界。SINGLE接口在实现前明确允许详情当前持久收藏／非收藏作品，经真实GetManga／ChapterRepository取单ID；显式详情沿Android手动fetchAllFromSource(true)语义，绕过批量智能限制，非收藏不改membership／不自动下载，本地仅沿既有文件源；批量手动分类仍保持智能规则。

- 元数据实现后定点复验：`interaction-ri14-metadata-settings-green` 于 2026-10-01 23:17:15.859–23:17:39.162 UTC FAILED，Data两个生成字段映射 fetchInterval／nextUpdate 未解析，domain共享SourceMangaMetadata两方法已绿；未提供Desktop／Android绿证据。机械改为真实生成字段 calculate_interval／nullable next_update 后，`interaction-ri14-metadata-settings-green-valid` 于23:19:55.766–23:20:49.711 UTC FAILED：Android实际智能worker与metadata worker两方法绿；Desktop四方法在 production initDomainLayer 新Checker eager解析 LibraryPreferences 时失败（约858），该类型原在后续UILayer约990才注册，尚未到目标断言。这是新生产DI注册顺序缺口，不能仅在测试预注册规避，须保持生产偏好唯一实例并修顺序／延迟消费再复验。Boolean预测拒绝已改真实目标manga SQLite trigger→MangaRepositoryImpl.update捕获并返回false；Exception变体保接口IOException注入，恢复沿原阶段且drop触发器，未将其宣称为两次SQLite拒绝。

- DI修复及预测真实绿：`interaction-ri14-metadata-settings-green-di` 于2026-10-01 23:26:55.466–23:27:40.083 UTC FAILED；归档 desktop XML 23:27:32.498 UTC，prediction Boolean实际SQL拒绝／Exception两方法绿，metadata方法进入私人笔记断言失败，insertNetworkManga原本不写notes，必须先用真实 repository.update种下再验证保留，不据此宣称产品删除笔记；设置方法 XML23:27:36.161 UTC 超时，继续精确区分控件到达／写入条件。Checker偏好改为调用时getter解析现同一Injekt实例，四方法已越过原初始化故障，生产DI顺序缺口已关闭；完整DI smoke仍在批次affected执行。后续只复验上述两个未绿方法，不重复两个预测或Android绿方法。

- 资料原子／封面／笔记绿与设置诊断：`interaction-ri14-metadata-settings-green-fixture` 于2026-10-01 23:30:00.433–23:30:26.015 UTC FAILED；实际metadata唯一方法 XML23:30:19.521 UTC绿、0 skipped，已先真实保存notes、改变拒绝的远端description并执行SQLtrigger，覆盖默认关闭、开启字段、用户标题资格、真实custom文件字节／版本保留及目录拒绝资料回滚。Settings唯一方法 XML23:30:21.851 UTC仍在smart rule manga_ongoing条件超时。`interaction-ri14-single-red-settings-diagnostic` 于23:32:16.068–23:32:46.503 UTC FAILED，SINGLE唯一方法 XML23:32:44.574 UTC直接调用最终runSingle API（最小scaffold沿原scope），准确以SINGLE期望／CATEGORY实际失败，未更换红绿调用；Settings XML23:32:40.731 UTC诊断确认实际迁移保旧能力的初值为restrictions=[]，Off整行点击正确写入[manga_ongoing]，不是生产未保存。测试先显式种下四共享限制后保On→Off及真实pref断言，不改迁移规则或降低必做条件。

- SINGLE／设置 focused 绿：`interaction-ri14-single-settings-green` 于2026-10-01 23:34:41.299–23:35:50.489 UTC PASSED，实际最终runSingle API从GetManga读取scope.singleMangaId，收藏／非收藏均只提交当前作品，绕批量规则不改变membership；设置四项、48／72h与metadata实际写入通过。尚须同SINGLE方法补验独立周期边界：Android显式详情不写全库lastUpdatedTimestamp，当前新Single共用初始化路径无条件写shared timestamp，会推迟全库自动周期；必须保全库原时刻，SINGLE仅保自身本机context检查时间，null prefs fallback也不能把Single当全库最近检查。此为同能力SOURCE边界，完成该focused红绿后再进入批次受影响验收。

- 最后界面边界：`interaction-ri14-final-surfaces-red`（2026-10-01 23:39:11–23:39:38 UTC）执行 5 个 Desktop focused 方法，2 绿、3 正确业务红：SINGLE 将全库周期时钟从 123456 推到当前时间；设置搜索缺少 Automatic updates 新锚点；结果弹层在 320px／200% 字体下按钮 bounds 合格，但双向 Tab 没有形成模态内焦点环。已通过的两项保护真实 Cancelled 已提交目录凭据在新刷新前恢复及设置 pre/post write refusal 的权威值反馈。
- `interaction-ri14-final-surfaces-green`（23:42:21–23:42:47 UTC）SINGLE 周期隔离与结果弹层两项全绿。结果弹层在浅／深主题、320×680px、fontScale=2 中执行 bounds、Tab／Shift+Tab、Escape、背景隔离与还焦，并生成原生离屏图：[浅色](desktop-interaction-native/ri14-update-results-light-320-font200.png)、[深色](desktop-interaction-native/ri14-update-results-dark-320-font200.png)；主代理已查看浅色产物。搜索已进入实际 Library 控件并高亮，余下失败是测试将 SettingsRoot 的 child Navigator 错当外层 Navigator，按真实导航契约修正夹具，不改变生产路由。原始 XML 位于 `.gradle-coordinator/ri14-xml/final-surfaces-{red,green}/desktop/`。以上不是完整 RI14 验收、全量测试或发布证据。

- 本地 SINGLE：`interaction-ri14-local-red-search-repair`（23:45:25–23:45:43 UTC）本地真实 DI／文件目录用例因没有 Source(0) 得到 FAILED 而非 Completed，确认正确红；设置搜索改用真实 SettingsRoot 嵌套导航夹具后绿。`interaction-ri14-local-green`（23:48:39–23:49:16 UTC）本地方法绿，已有章节 ID／read／bookmark／page、私人字段、新增第二章以及缺失路径保旧 SQL 均走实际实现；有限 adapter 只委托既有 LocalSourceReader 发现当前作品章节，再进入原 Checker／目录核心。主代理审查发现额外必修边界：共享下载筛选只核收藏与偏好，不排除本地源，故非收藏／默认关闭的绿不能证明收藏＋开启自动下载也不入队；增加同方法敏感红断言后，在 Desktop adapter 限定本地不产生远程下载 effects，不修改 Android 共享筛选语义。尚未给此组合绿证据。

- 本地下载边界红绿：`interaction-ri14-local-download-red`（23:50:24–23:50:42 UTC）同真实 local fixture 开启收藏＋DownloadPreferences.downloadNewChapters 后，实际 PersistentDownloadStore／Manager 中出现 sourceId=0 的 Chapter 2 QUEUED，确认真实错误。最小修正仅使 Desktop Checker 对本地源捕获 disabled download policy，共享 Android filter 未改。`interaction-ri14-local-download-green`（23:52:56–23:52:59 UTC）缺类型 import 的编译失败不算绿；修为实际 fully qualified 类型后，`interaction-ri14-local-download-green-valid`（23:53:37–23:54:09 UTC）exit0，原方法全部断言通过。两主题原生离屏图已由主代理分别查看。本批 affected／格式／索引／提交仍待。

- 收口范围与治理：按只读发现修正 4 处旧 Recovery register-only fixture 为明确原工作集，新刷新／恢复调用分开，网络 fixture 不再使用本地 source0；保留原历史方法名与业务断言，并保留独立 legacy 无范围拒绝用例。未把尚未执行的夹具修正虚记为失败。`interaction-ri14-format-apply`（23:59:46–00:00:04 UTC）失败于实际 ktlint 行长／条件括号等；经原 formatter 清理后 `interaction-ri14-format-ready`（2026-10-02 00:03:30–00:03:45 UTC）5模块 scoped Apply 通过，范围 28 Kotlin／2 XML，不格式化根文档／manifest。单 enum 文件改为 `LibraryUpdateSkipReason.kt`；两个新方法改短名，断言不变：`cancelled committed receipt is repaired before fresh scope without redelivery or misattributed counts`、`checker metadata preserves user title custom cover and atomic directory`，不视为新增方法数量。
- 主代理稳定输入索引核验：manifest 11个 capability 块中修复17个当前定位，新增6个当前role定位，总338个当前定位均有效；RI14 epoch记录53个唯一现有测试方法绑定。所有64项历史 behaviorMethods／status／actionInventory／FIXED_ORIGINAL逐项与HEAD相等。此为索引检查，不能代替行为验证。`git diff --check`通过。`interaction-ri14-affected` 在00:04:22 UTC启动（worker81604／Gradle76888），16个明确 Desktop filters＋5模块 scoped Check；command与log见同key JSON／log，未包含全量、模块完整、audit或构建。原6个domain／2个Android有效绿可复用，终态及根核尚待。

- affected 首轮事实：`interaction-ri14-affected` 于00:06:38 UTC终态FAILED，归档102个 Desktop 方法／94绿／8失败／0 skipped，5模块 scoped Check均通过。八项分别为 legacy 无范围任务从原期望Failed回退Pending（acceptedOccurrence捕获晚，真实生产回退），Browse原下载stage已ACK但新prediction待办的旧阶段断言，DI旧测试回调绕过真实Checker／目录下载effects，两处恢复夹具工作集／取消期待错误，虚拟时钟与真实IO的等待，未消费的更新Job，以及新增48／72h选中控件的旧表达。按实际XML／调用链逐项诊断，实施者只修失败项及必要影响路径；保留原legacy持久失败、取消终态、原工作集、队列与新增数核心断言，不将当前失败记为通过。
- 主代理修正自身当前证据归属：ID22属于membership，RI14元数据不应挂到该项；已恢复其HEAD事实、仅修当前行定位，将metadata／FetchInterval共享角色及实际Android UpdateManga消费引用归到更新capability61。历史methods／status／actionInventory／FIXED_ORIGINAL仍相等；无产品或测试变化、无需重复行为验证。最终定位预计339，须在修复源码格式稳定后再核。本轮仍属同一持续独立初审及必要修正核验，未新增独立审查者或全量测试。

- RI14最终根核：`interaction-ri14-affected-repair`（00:12:02–00:12:37 UTC）因测试assert导入编译失败未执行8方法，不计行为绿；同8filters的`interaction-ri14-affected-repair-valid`（00:13:30–00:14:00 UTC）8/8绿、0跳过，原94方法证据按未变路径复用，合计Desktop102唯一绿。归档root独立逐method合并复核102；再核原有效domainEligibility2／Clock2／Metadata2及Android实际worker2，合计110唯一绿、0 skipped，未把自动retry执行次数充数。5模块 scoped Check最终通过（29 Kotlin／2 XML）。legacy无范围任务拒绝现在持久Failed；本地收藏且开启自动下载不入远程队列，两项bug已修复。
- 实施者六字段回执 status=IMPLEMENTED、commit=UNCOMMITTED、process=NONE并停写；所有RI14 coordinator已无STARTING／RUNNING。最终allowlist29K／2XML／2PNG，另根维护roadmap、契约、原报告和manifest；超过8文件／400行属于同目录SQL有限phase、TaskStore凭据、scheduler和实际UI的内聚变更，不拆为不可验收的微提交。DI旧区按既有整文件格式规则机械清理，不改变旧语义。独立初审及上述必要修正核验已闭合；根再核339当前定位、全部历史methods／status／action／fixed source不变及diff check，通过后随本功能批一次提交，RI14 checkoff随同提交；RI15继续同一实施者。
- 原生结果图环境：Windows离屏 Compose1.10.2／Skiko0.9.37.4／JDK21.0.11.10，density=1、fontScale=2、320×680，强制LIGHT／DARK，滚动至Failed work18。真实Space、双向Tab、Escape、背景pointer隔离及还焦均通过；不是系统屏幕、物理200% DPI、正式产物或硬件证据。原task phase与store不是跨store原子，以有限receipt恢复；其他caller空receipt与旧两参数协议不变。完整矩阵及正式交付仍仅RI18。

### RI15 迁移搜索、确认与失败事务（实施中）

RI14已提交 `d9d80c0e652f4b4c15ef3bd9b6b8b9b8b75fbb7d`，37文件，110唯一focused绿／0跳过、339当前定位、29K／2XML scoped格式，工作树干净后复用原实施者正式执行RI15。预算3–5小时，仍1实施者＋主代理同批持续独立初审和必要修正核验；三簇串行：共享计划与真实SQL迁移原子边界→现搜索／确认与有限文件范围→batch实际DI／HTTP／导航和窄窗收口。当前只focused红绿及相关affected／scoped格式，不新增full／module／audit／发布。

实施者核当前用例先独立调用返回Unit且捕获Exception的UpdateChapter.awaitAll，再提交membership；章节异常可被吞，membership拒绝也可能留下目标半份读态；同对象尚无guard。首组红测覆盖真实SQLite拒绝与同对象。计划最小复用MangaRepositoryImpl原handler事务，读取最新source／target／chapter／category，以既有MigrationOrchestrator生成patch，与membership一次提交；网络和目标RI13目录先取得，不把网络放进SQL、不新schema／迁移器。same source＋URL拒绝须发生在目标保存可能更新原对象之前，事务再核原身份。copyNotes=false原repo COALESCE已保值，保持实际语义。数据接口在文件/UI依赖前接受root独立核验；有限文件模型另按具体必需说明，不预造持久状态。

以下为原前置只读事实，不能当本批实现证据：

实际 DesktopMigrateMangaUseCase先通过SaveSourceMangaForDetails保存目标目录，再独立updateChapter，最后updateMembershipsAtomically一次提交target与可选source移出。MigrationOptions现只含copyChapters／copyCategories／copyNotes，尚无封面和旧下载消费；共享MigrationFlag已包含CUSTOM_COVER与REMOVE_DOWNLOAD，可沿同一配置扩适用adapter，不新建迁移器。MigrationOrchestrator章状态沿识别号、最大已读上界、匹配书签／dateFetch，libraryPlan保chapter/viewer flags、迁移dateAdded和所选notes；复制与迁移由replace区分。实际MigrationSearchScreen两种动作均消费同一use case及批量转接。已固定目标独立身份／RI13目录、字段草稿与有限文件失败边界到page-contracts的RI15节，无RI15实现／测试结论。

RI15 第一组实际证据（2026-10-02 UTC）：`interaction-ri15-transaction-red` 00:21:09–00:21:42，真实Desktop SQLite两项正确红：membership拒绝留下target章节read／bookmark与version改动；相同source／URL未被拒绝。`transaction-green` 00:26:38–00:27:46，两项PASS／0跳过；主代理独立读取归档XML和SQL实现，确认commit读取当前两端身份／章节／选定分类，在同一handler事务调用共享MigrationOrchestrator、原章节writer／journal及membership，不把HTTP放进事务。此时只是第一组绿，不代表整个RI15已验收。

`interaction-ri15-android-directory-red-valid` 00:31:20–00:32:38：data真实共享storage两项PASS，Android旧wrapper吞异常后仍Result.success；后续db-green读取完整错误确认stub未匹配默认suspend lambda，底层是MockK NoAnswer，并非预定目录IOException，撤回这一有效业务红判定。既有maxRetries=2产生同方法三次失败执行，不计三项测试。初次android-directory-red的命令／discovery错误不作为业务红。后续`db-green` 00:37:01–00:37:31仍FAILED（Desktop和Android有失败），共享storage结果不能代替两端wrapper绿；Desktop13=4通过／9失败，主要为旧fixture空目标目录被RI13真实NoChapters拒绝，category用例因此未达到实际写入；Android一项adapter通过、两个拒绝方法stub错误。保留FAILED事实，先完整匹配真实9参数再恢复预定拒绝红绿；旧fixture仅补非空目录，原状态／category／notes断言保留。失败修复及同批审查继续，未放行下游文件边界。

文件契约核对：现RI10 cancelCapturedDownloadAttempts会启动原attempt的破坏性cleanup，Provider.deleteCapturedDownloadArtifacts直接删除，不能宣称两者提供可回滚prepare。拒绝了“先删除文件、SQL失败仅保收藏”的拟议方案，因为它不满足已固定的拒绝／中断保原有效文件。要求原manager／provider／coverStore中仅增加本次确认有限对象的prepare／commit／rollback，沿原generation与producer／lease屏障；回滚冲突保留恢复对象且不覆盖后来文件，不建全局outbox／新schema。两端wrapper及文件接口的必要故障测试尚在实施。

RI15 DB簇同批独立初审已通过：`interaction-ri15-android-rejections-red-valid` 00:39:18–00:39:44，完整9参数匹配后暂退旧吞目录错误／未消费atomic commit边界，两项正确断言红（XML00:39:31，maxRetries产生6失败执行＝2唯一方法）。`db-green-valid` 00:44:41–00:45:00 PASS，Desktop原9失败方法9绿／Android3绿；主代理独立合并transaction-green、db-green及db-green-valid归档，22唯一PASS／0skip（Desktop13、Android3、sharedStorage JVM3＋Android3）。fixture仅补真实非空目录和完整stub，source／target IDs、category／notes／读态／页码／dateAdded原断言保留；一次机械constructor清理误伤ListScreenModel三调用点已定点恢复，不改该model业务。核当前SQL身份guard、最新用户状态、共享chapter规则、chapter writer／journal及membership同事务；Android repository改必选且DomainModule真绑定。此放行仅DB簇，后续finite receipt／文件／搜索UI与真实batch仍须同批验收，当前不勾选RI15、不运行全量。

RI15有限恢复接口方向已核定（未完成实现）：既有chapter_directory_phases的optional MigrationReceipt＋平台staging sidecar仅记录本次operation／原身份／固定选项／acceptedAt及原文件，不建表或wire。prepared先于文件副作用落盘，committed与SQL效果同事务，checkpoint真实接受后ACK；普通目录消费者不得清掉迁移receipt。Manager原generation预占须拒活动producer／retirement并锁外等待leases；rollback不覆后来文件，冲突及postcommit清理拒绝留有限恢复对象、真实反馈。由原实施者先写红测试后实现，主代理继续同批独立审查。

RI15 `interaction-ri15-receipt-red` 00:47:46–00:48:26 UTC FAILED，XML00:48:24.691，3唯一正确业务红／0skip：SQL成功后重做把target dateAdded40写成0；prepared同源第二target未拒绝；首次文件副作用前的原phase未落盘。测试执行真实MangaRepositoryImpl和ChapterRepositoryImpl而非源码扫描。迁移receipt／普通目录guard及专用ACK正在实现，尚无该簇绿结论。

RI15 `interaction-ri15-receipt-green` 00:55:14–00:56:03 UTC PASS，XML00:56:01.962，3唯一PASS／0skip；主代理读取phase默认字段、Storage helper、repo同事务与ordinary ACK／finishFiles／effectsComplete guards，通过该数据边界初审。复用既有target单槽及source请求冲突覆盖源／目标参与冲突；原request必须相等、committed同事务保存，source与target身份重核。此时真实两端wrapper尚未接receipt，不作为整个迁移恢复完成证据。后续affected包含旧directory与RI14receipt回归。

文件adapter收窄为既有withDirectoryChanges预占：仅移除下载选项的本次受影响章节有QUEUED／active／retirement时明确拒绝，先完成或取消相关下载；不取消／重排／重入队，不让其他作品或未选文件阻断。SOURCE以实际downloadedCount>0显示移除下载，此有限Desktop拒绝需真实界面反馈及文档。主代理已指出该预占本身不等待artifact leases，不能宣称它提供租约屏障；原awaitArtifactDrain也只是快照等待，实际lease保护或明确安全拒绝待验证。只收窄实现机制，不豁免原文件／中断恢复门禁。

RI15 文件簇当前红证据：`file-staging-red` 00:58:07–00:58:24 UTC FAILED，XML00:58:22.902，2正确业务红／0skip；第一项assertThrows外层报unexpected Assertion，但主代理读取cause为SQL回调开始时原文件仍存在、未先暂存，第二项确认范围原文件未移除。`file-lease-red` 01:01:29–01:01:46 FAILED，XML01:01:45.443，原staging两项PASS、新真实coordinator active acquire／新lease保护一项正确红。类当前仍未接production，不能作为端到端迁移证明。

同批文件初审发现并交原实施者修复：source与target原cover bytes相同时，prepare在下载检查阶段失败，rollback仅凭installedDigest相等误删尚未移动的原target cover；实施者`cover-rollback-red` 01:03:28–01:03:45 FAILED报告精确复现，归档独立核验待后续收口。另外主代理读取本机JDK21 src.zip内WindowsFileCopy.java:318–320，ATOMIC_MOVE真实传MOVEFILE_REPLACE_EXISTING，省略REPLACE_EXISTING仍可覆盖检查后出现的目标；要求同父真正no-replace platform move及真实并发目的出现回归，macOS不可外推。target cover操作必须使用真实DesktopCustomCoverStore的有限reservation，不能与既有手动write／delete分开维护。

范围纠正：主代理早先要求完整Android文件staging／原子noreplace保护，超出了本Desktop UI批次的Android共享SQL契约，已明确收回该额外扩展。Android本轮共享真实SQL提交／章号／身份／latest-state及目录错误反馈，保现CoverCache／DownloadManager SOURCE功能，不新增Android JNA／JNI、不拒绝原可用文件选项；不能以sharedStorage绿宣称Android也具备本轮Desktop文件重启／回滚保证。Android URI／local原语无法直接复用Desktop平台moves，不能为此另造完整文件协议。全部既定Desktop文件／失败／重启／batch mandatory门禁保留，未来实证仍据真实生产链路。

RI15 platform ABI只读校对：通过既有mbp-lan读取实际Apple SDK三份直接include头文件，最终sys/stdio.h:37明确RENAME_EXCL=0x00000004、:51声明renamex_np(const char*,const char*,unsigned int)，OSX>=10.12。未修改Mac checkout／应用／系统配置、未构建或调用该native移动；这是函数签名与常量源码事实，actual Mac native碰撞／文件恢复／发布runtime仍RI18验证。

RI15 file adapter修正证据：主代理补读cover-rollback-red归档，XML01:03:44.689，一项正确红为未碰同bytes原cover消失。`file-collision-red` 01:13:12–01:13:29 UTC FAILED，XML01:13:28.686，2正确红／0skip：实际CoverStore reservation未阻止manual write／delete，Windows原ATOMIC_MOVE覆盖检查后目的且未拒绝。`file-staging-green` 01:15:16–01:15:33 PASS，XML01:15:31.792，6唯一PASS／0skip；主代理核所有归档及当前实现，samebytes依据backup存在／previousDigest修复、真实CoverStore同锁有限预占及coordinator active acquire／new lease guard成立，native Windows MoveFileW实际拒绝并保双方bytes。macOS用已核renamex_np／flag4，runtime待RI18。

此时只是file adapter级：prod用例／SQL trigger／DI／恢复尚未接。主代理要求Windows原生调用沿StdCall惯例而非默认C ABI，并提示实际generation集成风险：确认时无queue的既有下载，HTTP期间同章节新generation启动且完成，prepare时再无queue，单凭queue／active及相同content digest不能识别这次替换。须有限原manager generation／fingerprint快照核prepare，不能删除原确认后已完成的新attempt；不新增持久generation协议。后续真实用例红绿及同批审查继续，RI15未完成。

RI15 `interaction-ri15-product-files-red` 01:25:47–01:26:26 UTC FAILED，主代理独立读归档4正确业务红／0skip：Desktop实际usecase＋SQLite／provider／store／manager三方法（XML01:26:22.489）为忽略确认文件、SQL拒绝后原确认retry仍未处理文件、deferACK未保存原committed receipt；实际DownloadManager方法（XML01:26:25.202）用MockWebServer真实两次生产下载，原下载完成无queue时捕获确认、删除／重新enqueue并完成相同bytes，旧confirmation未拒绝。不是手动改marker或复制实现。实施者将沿原capturedGenerations弱引用扩无queue哨兵，Accepted强持原marker；任何实际enqueue／retry接受后沿旧失效路径，即使queue后来为空仍replaced。短锁核marker并preflight后才prepare SQL phase，重-init先恢复有限原phase／sidecar、不能用新manager重capture冒充原确认；不新增持久generation协议。当前production绿及启动顺序／真实DI仍待验收。

RI15 product文件链路同批独立核验：`product-files-green` 01:34:25–01:35:23 UTC PASS，归档Desktop XML01:35:19.439与manager XML01:35:22.118，4唯一PASS／0skip。真实usecase＋SQL＋provider／CoverStore确认文件／封面范围，HTTP期间新增原文件及改原封面不加入已接受清单；实际SQL trigger拒绝恢复原文件并允许同确认retry；deferACK保留committed receipt，重做不覆盖后来target note／date；实际manager两次同bytes已完成下载使旧confirmation失效。主代理核Windows MoveFileW已显式ALT_CONVENTION、原manager无queue哨兵与弱引用失效端口，Accepted仅强持本次marker。此证据限当前四方法，不等于UI／batch／启动DI完成。

RI15 `cancel-sidecar-red` 01:43:02.598 UTC XML，2唯一执行＝1PASS／1正确业务红／0skip：实际SQL提交后端口抛CancellationException，新owner只清理原staged／backup并ACK，source／target收藏、target date／后来note、已安装cover均保持；committed＋filesComplete=false却缺sidecar时旧recover未报错并吞记录为正确红。新增guard要求缺／坏原清单明确保留待清理，filesComplete=true则允许清单已释放后的原确认ACK。`prepared-recovery-red` XML01:44:45.056，2唯一＝缺sidecar1PASS／prepared1正确红：原finite staged未在新owner恢复到original，原target cover需恢复、source收藏保持、target未收藏；没有重新捕获generation冒充原确认。

RI15 file恢复当前未收口：`file-recovery-green` 01:46:43–01:47:09 FAILED，仅DesktopAppModule非suspend DI调用recoverPreparedFiles编译错误，未执行上述业务测试，不计新的业务红或通过。原实施者沿既有同步DI用runBlocking(IO)恢复，manager先创建不start，有限prepared恢复完成后才允许原worker；DesktopAppRuntime异步startupCleanup不能作为先于worker证明。专用ACK在释放私有capture前须确认committed及filesComplete，清理／checkpoint拒绝保留原receipt，防止已提交被误报普通失败后重写SQL；取消尚未prepared确认只释放本次私有capture，不碰原文件。新`recovery-search-red`在本次源码冻结窗口启动，恢复复验及真实DI／HTTP搜索、完整确认／batch尚待有效结果。本批仍未勾选／提交，不运行全量或发布。

RI15 搜索／确认真实事件证据：`recovery-search-red`／`red-valid`仅测试编译问题，不计业务红；`recovery-search-red-ready`恢复XML01:55:42.982三个方法PASS／0skip（prepared原文件恢复、缺sidecar拒绝、SQL committed后取消仅清理），UI只有缺目标源选择为正确红，另一项停在Select夹具。修真实descendant语义后`confirmation-red` XML01:56:54.248一项正确红为缺自定义封面字段。`search-confirmation-green`仅nullable编译失败；`green-valid` XML02:05:39.363确认1PASS、分页1timeout。根读取实际Source FilterList.equals恒false，分页必须复用同一remember SourceQuery.Search实例，不能每页创建新查询使coordinator重启第一页。`batch-red` XML02:09:49.810三个正确红为缺已持久化SUCCESS后ACK、checkpoint拒绝假RUNNING、旧NonCancellable未结束就resume；`batch-green` XML02:21:09.953三方法及搜索XML02:21:10.357一方法共4PASS／0skip。Next在真实pageError时禁用，不将拒绝append变为永久Loading。

RI15 原SQL／原cursor补证：`product-batch-green`初次仅nullable编译问题；`green-valid` XML02:25:34.823两个sidecar序列化入口拒绝、02:25:37.147两个实际DI UI方法，共4PASS／0skip：首项HTTP500后继续第二项，仅重试原失败目标，target date40／60保持；SQL trigger拒绝后Cancel清prepared anchor并保原收藏及上下文。`native-owner-red` XML02:29:13.561与.835各1正确红为runtime stop未等待原任务及旧详情回调未委派安全迁移，原生确认视觉方法当时PASS。`owner-checkpoint-green` XML02:33:11.520／.746／.759与02:33:13.993四方法PASS／0skip：原worker停止等待；model安全迁移port；私有capture清理及SQL ACK分别拒绝，filesComplete anchor保持且原结果可恢复；实际FileTaskCheckpointStore拒绝SUCCESS后保committed receipt并显示ERROR，原retry无新HTTP／SQL覆盖后来笔记。

RI15 窄窗／factory／启动补证：`final-policy-green` XML02:37:29.221四执行＝五字段batch／HTTP后来文件和换源隔离2PASS、空notes种子及checkbox尺寸假设2夹具失败，不计新业务红。`final-wiring-red` XML02:42:29.446五执行＝4PASS／1等待新文案timeout；四通过为实际Detail factory→独立目标／原身份和章节保持、实际DI重开在下载worker启动前恢复prepared原文件、空notes隐藏复制并保target真实笔记、浅深主题320dp／fontScale2实际Tab／ShiftTab／wheel最后完整label及checkbox／pointer／Escape还焦。主代理查看更新的两张原生离屏PNG，最后字段及所有动作完整可达；Windows／Compose1.10.2／Skiko0.9.37.4／JDK21／density1，不把离屏fontScale当实体DPI或正式发布验收。`file-feedback-red` XML02:44:06.344一正确业务红：实际文件变化已拒绝结束，却显示源数据异常而非重新确认。`cleanup-exit-red` XML02:48:51.884 UI两项及02:49:00.726旧chapter adapter均未提供新业务红：Cancel立即查询早于异步dismiss、Close等待超时及FakeChapterRepository缺syncDirectory；后续先修夹具并精确复验。最新cleanup-exit-red-valid仍运行，待有效绿及affected／格式／原TaskStore取消终态验证后收口。

本批超过8文件／400行的内聚性说明：迁移确认必须贯通既有共享SQL、Desktop有限文件／queue屏障、原TaskStore与真实UI／DI，不按文件机械拆开不可独立验收的事务上下文；不新增schema、同步／备份wire或通用outbox。Android仅复用shared原子数据提交与directory错误语义，保SOURCE文件接口，不承诺Desktop暂存／重启协议。当前RI15仍未勾选／提交，未执行全量／发布。

RI15 最后取消／关闭验证：`cleanup-exit-red-valid` 02:56:31.924–02:56:51.976 UTC FAILED，XML adapter02:56:43.932及UI02:56:46.213共4唯一＝2PASS／2正确业务红／0skip。已绿为文件变化明确提示及实际Cancel owner移除、薄Desktop adapter将CHAPTER交给原子repo且无旧逐章写；正确红为committed/filesComplete缺Close及SQL committed后cancelAll仍把原item记CANCELLED而非SUCCESS。`cleanup-exit-green` 02:59:40.381–03:00:17.311整体FAILED，adapter03:00:14.190与UI03:00:04.303共4唯一＝3PASS／1夹具timeout，取消后实际原队列SUCCESS、TaskStatus.Cancelled与queue.cancelled保持、原receipt已ACK／HTTP和SQL未重做成立；Close测试仍等旧Cancel文本，后续兼容两文案等待busy结束再独立assert Close及实际modal owner移除。Interrupted回调只原owner／item／op／target且actual committed receipt，NonCancellable完成旧effects→实际scheduler.transaction确认连续terminal prefix不跳未执行项→ACK；清理与反馈写拒绝均保原CE及receipt，不能遮蔽取消或翻转任务生命周期。

RI15 `format-apply` 03:02:42.964–03:02:56.513 FAILED，仅120字符长行门禁未通过，未计业务失败。实施者用同版本缓存ktlint定位并清理，最终scoped Check仍以affected协调器证据为准。根按稳定输入更新manifest的RI15 nested evidence及现行roles，64capabilities／345当前locators／180 epoch方法引用（每能力引用不能充作180独立测试），原id／status／actionInventory／root behaviorMethods／statusDecision／FIXED_ORIGINAL／upstream refs均不变；16处旧现行行号随实际代码漂移修正，未改历史权威或将历史绿冒作本轮证据。唯一`interaction-ri15-affected`已启动，范围为本批迁移与相关directory／receipt、导航／DI／factory／manager、shared plan／真实双端SQL与Android wrapper／ListWiring，以及31K／2XML scoped格式；源码稳定，模块全量／audit／发布为0。待终态有效证据后才checkoff／提交。

RI15 最终同批验收：原affected03:09:10–03:10:12整体FAILED仅Desktop主编译缺collectAsState／fillMaxSize imports，实际非Desktop归档23PASS／0skip（domain10、data JVM3／Android3、app wrapper4／List3）。affected-desktop03:12:13–03:12:49仅test imports编译失败，无Desktop行为执行；补齐后`affected-desktop-valid`03:13:49.440–03:15:34.686 PASS，XML03:15:03.948–03:15:32.205归档73 DesktopPASS／0skip，scoped31K／2XML通过。核心96项有效结果未整组重复。

取消清理入口：根实际读QueueScreen后确认Cancelled下无恢复按钮，原phase虽保留仍无法从前台重试；`cancel-cleanup-red` XML03:16:58.764一正确红为真实TaskStore拒绝后原QueueScreen缺继续清理入口。新增defaultfalse兼容旧cursor的item.cleanupPending，仅处理原确认恢复；UI真实按钮复用Interrupted回调与jobs／stop／join，不executeMigration／reopen／重做HTTP／SQL，Cancelled保持。`cancel-cleanup-green` XML03:20:29.273及03:20:33.974六相关方法PASS／0skip，scoped32K／2XML通过。通用恢复失败反馈不谎称prepared已提交；manual明确committed异常保已有“已提交／待清理”。随后feedback-final XML03:22:06.275停在commit等待timeout，未到文案断言，不计新业务红。

等待发布并发缺口：`waiting-owner-red` XML03:25:00.582一正确红，StateFlow真实等待事件inline选择目标后执行次数应1实0；`owner-green` XML03:28:17.745 controller8PASS／1同红，原生XML03:28:18.444四PASS。实际原因是execute与event重复发布WAITING，后者覆盖刚接受的QUEUED；最小删除前者，只保原事件权威，并在旧owner条件remove成功后接既有原checkpoint当前QUEUED，不跳ERROR／未处理项。root要求停止保护；`owner-stop-red`只夹具close取消共用父scope导致第二阶段selected=false，不计业务红；改stop／awaitStopped后`owner-stop-red-valid` XML03:34:48.837为正确红（正常接续阶段已通过，stop后调用应0实1）。finally增加currentCoroutineContext.isActive拒绝取消／停止后重启。`owner-final-green`03:35:43–03:36:06 PASS，controller XML03:35:59.248九方法及native03:35:59.882四方法全绿，scoped32K／2XML再次通过。

实施代理已结构化回执STOPWRITE／UNCOMMITTED／process NONE，无STARTING／RUNNING协调器。根读取原affected、desktop-valid及针对修复的最后有效XML，按class＋方法取最新结果独立去重核验为98PASS／0FAIL／0SKIP（Desktop75／domain10／data JVM3＋Android3／app7），不把重复执行／夹具失败／旧绿充数。根最终manifest64 capabilities／346当前locators与RI15 methods实存全部有效，历史status／actions／root methods／statusDecision／FIXED_ORIGINAL等权威保持；两PNG实际查看、架构／契约同步，同批独立初审及必要修正核验已关闭。RI15必要checkoff与production／tests及证据随本功能批提交；最终全量、audit、正式发布／Mac native及硬件验收仍RI18，当前均未执行。本批墙钟约3小时20分，在原3–5小时预算内，复用1实施代理未新增代理／独立审查轮次。

### RI16／RI17 前置核对（只读，不代表实施完成）

当前Desktop已使用JNA／jna-platform5.19.1，平台原生入口可沿WindowsSystemProxySelector的OS隔离模式，而实际platform／DI／LibraryUpdateScheduler尚无Wi-Fi／计量／电源production port。现scheduler在进程内delay轮询并用墙钟lastRun，恢复与固定工作集缺口先由RI14闭合；RI16在作品边界消费小型三值port和同一完整策略，不新装服务／第二调度器。实体硬件与正式runtime证据仍由RI18提供，此前读取不作native能力已完成结论。

RI06已存在LibraryCategoryWheelModifier，Ctrl平台条件、根owner及250ms分段属于分类切换，不能把该段状态重用成两段刷新阈值。现LibraryTab的刷新入口调用refreshLibrary(allItems, 当前category ID)，详情现有源刷新及model刷新入口尚需沿RI13／14稳定目录核心统一；RI17固定80／48dp、300／400ms、3秒绝对有效期与800ms静默冷却，普通内容wheel、独立scope／scroll owner及真实任务回执接线。实际共享Clock状态机／native wheel测试尚未实施，鼠标／触控板／DPI在RI18补齐。

2026-10-01 RI16只读来源核验在本次子进程显式HTTP／HTTPS_PROXY与localhost bypass下有界并发读取微软四个原生API页面，均HTTP200、无需重试；链接与具体状态边界已补唯一page-contracts。GetCost(NULL)仅machine-wide Internet成本，不能外推实际源／VPN路由；WLAN需接口GUID和WlanFreeMemory，电源失败／255未知，无电池不自动等同在线。此次是官方来源核对，未执行Windows native查询、未修改系统配置，也不是正式runtime或实体硬件验收。

### RI16 执行预算与前置冻结（2026-10-02）

RI15 已提交 `b761eccd712f5b351d02c4b660176dbac62d23f4`，42 文件，提交后工作区干净；98 项有效 focused、同批独立验收及必要修正核验闭合。2026-10-02 03:46 UTC 复用原 implementation 代理串行继续 RI16，主代理负责平台接口／契约、同一报告、manifest、整合与独立验收，不重复实施。预计 3–5 小时，主要成本为 Windows JNA 原生装配与 scheduler／checkpoint／UI 的红绿验证；不新增代理或审查轮次，仅当前行为 focused 及明确受影响 wiring／格式，模块完整／全量／audit／发布次数仍为 0。真实发布与无法模拟的硬件证据统一 RI18，证据未齐前 RI16 checkbox 不勾选。原生接口不可用时保未知并记录真实阻塞，不修改系统服务或供电／网络设置。

固定复用边界：RI14 原 scheduler、固定 workset／original occurrence、四状态与已完成集合；RI12 单一共享设备偏好与迁移规则；已有 JNA 5.19.1 与 OS 隔离。自动已开始单本不因条件变化中断，下一作品边界重判；选中 Unknown／不满足等待，未选忽略，手动／显式失败重试绕过，自动 resume 保原自动语义。原 checkStartedAt／FetchInterval window 保持，周期时间不得仅因初始化等待而推进，实际执行才记一次；sleep 之后同一任务只续一次。平台 unsupported 与可支持但 query Unknown 分开：macOS 不加载 Windows DLL、隐藏不可用条件，保持用户持久值且不阻断原有自动更新。生产 Test Mode 诊断须解析与 scheduler 同一 DI adapter；独立系统查询不替代 runtime。

WLAN GUID、native memory ownership、COM 成本返回的 machine-wide／连接对应界限与电源 ACLineStatus 均按已固定官方来源核验；任何活动连接歧义不得假称实际业务 Wi-Fi／非计量。预计成果是 production 代码、行为及 wiring 测试、必要架构说明与根维护的契约／证据索引，不新增另一本逐任务报告。

RI16 首簇红绿由根独立读取协调器及归档 XML：`interaction-ri16-device-red` 03:49:25.297–03:49:54.414 UTC FAILED，scheduler XML03:49:53.142 为已选未知 Wi-Fi 仍实际调用 source，predicate XML03:49:53.443 为 WIFI／UNSATISFIED expected[WIFI] actual[]，共两项正确业务红／0skip。`device-green`03:52:02.840–03:52:32.136 PASSED，对应 XML03:52:30.924／03:52:31.189 两项有效 PASS／0skip。当前只证明小 port 的选中三值判断及原 workset 等待／timestamp 不消费；Windows native、真实 DI／UI、重启／睡醒／恢复、手动已有等待绕过仍待后续验证，不能提前称 RI16 完成。

原生来源补核：微软[Wi-Fi access/location changes](https://learn.microsoft.com/en-us/windows/win32/nativewifi/wi-fi-access-location-changes) 指出 current_connection 涉及 BSSID，可触发位置授权或 ERROR_ACCESS_DENIED；本次只需 interface_state，不读取 SSID／BSSID、不扫描或改变位置配置。网络成本依[官方枚举](https://learn.microsoft.com/en-us/windows/win32/api/netlistmgr/ne-netlistmgr-nlm_connection_cost)判断 Unknown0、fixed／variable 及高位成本标志，GetCost(NULL) 的 machine-wide 边界保留。这是来源核对，尚未作为 native 查询通过证据。

RI16 `windows-boundary-red`03:55:00.470–03:55:17.950 UTC FAILED，根独立核归档 XML scheduler03:55:16.638 与 mapping03:55:17.001：5执行＝3正确业务红／2PASS／0skip，红为 manual 返回 suspended 自动 worker、WIFI 未映射原 GUID 状态、AC 未映射 0／1；绿为多连接／tunnel 保 Unknown 及 unit 边界 owner 重开保护。后者最初复用同 TaskStore 只证明 owner 重开，随后改为新 FileTaskCheckpointStore 读原路径，不将旧夹具绿冒作磁盘重启证据。成本测试初始高位 flags 错用0x100等，根按官方值指出后改为0x10000／0x20000／0x40000／0x80000，旧错值不作为 native 枚举证明。

`native-authority-red`仅 HRESULT Kotlin intValue 编译失败，没有业务执行；`red-valid`归档 XML04:01:17.310／.788 共9＝7PASS／2正确红／0skip：旧 occurrence 被替换仍发 source，以及真实 cost65536 expectedUNSAT actualUNKNOWN。绿包含 actual WLAN GUID／opcode6／成功及拒绝释放两 buffers 与 client、manual 安全替换、新 TaskStore 恢复 original workset／预测时刻，以及 unsupported 不加载 Windows DLL；Windows actual production adapter 查询输出 WIFI UNKNOWN、UNMETERED UNKNOWN、AC SATISFIED，未知原因尚未证实，不外推硬件或发布。根核本机官方 Windows SDK10.0.26100.0 的 netlistmgr.h:2180／2588 IID／CLSID 及 shared/netioapi.h:154 MIB_IF_ROW2，吻合声明；头文件不是 runtime 证据。

同一持续独审发现 native 只保 MediaConnectState==1 会遗漏 UP＋未知媒体的 tunnel，Type6 也含虚拟接口，不能称唯一物理路径。`native-route-red` XML04:03:33.694／.977 共4＝2PASS／2正确红／0skip：实际 MIB row bytes 中 active tunnel 被丢（expected2 actual1）；single virtual Ethernet 错判 UNSAT，而应保 Unknown。成本 high flags／原 occurrence 拒绝已通过本次影响方法，剩余绿及原 owner shutdown 接线待验。真实 Root 的 manual refresh 另有 model.isUpdating／activeJob 提前拒绝，scheduler API 绿不能代替 UI；本批后续明确纳入真实 Root→model→same scheduler 等待路径，不扩大为另一更新器。

RI16 `boundary-green-shutdown-red`归档 XML04:07:04.160／.442 共14＝13PASS／1正确红／0skip。Windows9全部 PASS；实际非敏感 MIB 查询成功，26 条 activity 摘要包括 virtual 与 physical，production COM GetCost 直接执行成功 cost1，产品单路由守卫仍保 UNKNOWN／UNKNOWN／AC SAT。不能外推切换硬件条件或正式发布 DI runtime。正确红为 manual 替换旧 native query owner 后 stopAndJoin 过早返回，随后沿现 stoppingJobs 纳入被替换 owner，等待实际结束后才能关闭资源。`ui-binding-red`04:10:04–04:11:29 UTC 执行设备 DI 缺 binding／设置缺入口两正确红，旧 shutdown 方法已通过；具体有效数量待根收口核准确归档。

真实 UI／模型归属：Root 原菜单点击已复现 model 提前报 already_running 的正确业务红（`root-wait-red-valid` XML04:21:41.071），同 key 实际 DI UI／scheduler 唯一 adapter 已通过。最初 spinner 断言读取 Injekt 预建诊断 model，Root 使用 LocalLibraryScreenModelFactory 的实际 owner，不计新业务红；夹具改为记录真实 factory owner后 `root-wait-green-valid`04:26:04–04:26:28 PASS。随后撤回额外 join guard，以正确 owner 复验原反馈路径仍 PASS，故不保该防御改动。`feedback-red` XML04:28:47.236三执行＝此 owner1PASS、等待产品原因与现 LibraryMangaTestModeController 同 DI 端口诊断两正确红。仍需等待 Cancel／Resume 区分 checking 与 active、错误／unsupported及 scoped格式／affected 收口。客户端现 Json.ignoreUnknownKeys=true，新增可选 Test Mode snapshot 字段不改既有协议字段或客户端操作；HTTP实际新值接线仍须行为验证。

RI16 等待控件有效证据：`wait-controls-red`归档XML04:33:29.649两项正确业务红，为实际Root自动Resume误显示checking和Running等待modal缺Cancel。`wait-controls-green`04:34:50.797–04:35:49.327整体FAILED，XML04:35:42.038两执行中自动Resume1PASS，modal1失败为NoSuchElementException，不能算两项全绿。`feedback-green-valid`04:38:24.319–04:38:55.614 PASSED，XML04:38:46.925两项PASS／0skip，为实际同DI诊断序列化及等待modal理由／取消／Resume／双向Tab／Escape回触发器。当前收口尚待等待checkpoint拒绝、显式retry／unsupported保值、窄窗及affected／格式，没有提前勾选或执行全量。

RI16 最后边界核验：`final-boundaries`04:44:35.585–04:45:21.586 PASSED，XML04:45:11.041／.660／.664，7唯一PASS／0skip；真实FileTaskCheckpointStore写拒绝后source不执行、原check timestamp与workset保持；接受时条件快照不被后来偏好覆盖，显式failed-only只重试原失败对象并绕过设备等待；未选未知与unsupported均不阻断且保用户值；cost null／未知bit保持Unknown；真实320dp／fontScale2设置保存拒绝可重试、unsupported控件隐藏、等待modal取消及键盘／Escape可达。

同批窄窗独审补证：新增说明挤占原逐本列表，`wait-row-red` XML04:48:55.191一正确业务红为原Work行无ScrollBy可达路径。仅等待状态给原说明与既有有界Lazy列表增加有界滚动容器，其他结果布局不改；`wait-row-green` XML04:50:51.488两PASS／0skip，原Work行真实可滚动读取。根重新查看同两PNG，浅图三条件完整、深图原work行与Cancel／Resume／Close完整，截图是实际滚动位置而非整页同时可见承诺；环境Windows／Compose1.10.2／Skiko0.9.37.4／JDK21／density1／fontScale2，不能当实体DPI、正式EXE或硬件输入证明。

`format-apply`04:54:41.873–04:54:57.519 UTC第一次仅4处lint拒绝，CLIktlint1.8.0定位并清理长行／原生导出命名等，未放宽max-line。`format-ready`05:01:13–05:01:17 UTC PASSED，scoped18K／2XML实际Apply通过。超过8文件／400行的内聚性：同一小port／native ABI→同一durable occurrence及取消／shutdown→实际settings／Root／TestMode，不能机械拆开恢复协议；未新增依赖、服务、schema或另一调度器。唯一`interaction-ri16-affected`05:02:36 UTC启动，预计64方法，为新行为27及既有受影响37、scoped18K／2XML Check；未跑Android／domain或任何模块全量、audit、发布。根在稳定源码更新RI16 nested evidence及12处现行locator漂移，保历史status／actions／root methods／statusDecision／upstream／FIXED_ORIGINAL不变；最终有效结果及提交尚待终态。

RI16 核心最终同批验收：`interaction-ri16-affected`05:02:36.032–05:03:50.272 UTC PASSED／exit0，根独立读取有效XML05:03:25.979–05:03:33.273，10类64唯一PASS／0FAIL／0SKIP：DI2、deviceWaiting8、原Recovery7、Scheduler13、core1、Windows9、TaskStore5、LibraryModel6、SettingsSearch2、Policy11。新行为27包含在64内，不加上先前重复红绿。scoped18K／2XML Check通过；agent结构化回执IMPLEMENTED／STOPWRITE／UNCOMMITTED／processNONE，记录的两进程已退出。根核64 capabilities、352现行locators、81 nested方法引用＝27唯一新方法，所有现行定位有效，历史authority不变；同批持续初审及必要修正核验已关闭。23实施路径＋根契约／报告／manifest／roadmap随本功能批提交。RI16核心可供RI17依赖，但正式Windows发布runtime／实体条件和Mac装配门禁尚未取得，RI16 checkbox仍未勾选；RI17–RI18继续，最终全量、audit、正式构建仍0。本批墙钟约1小时20分，在3–5小时预算内，未新增代理或审查轮次。

### RI17 执行预算与前置冻结（2026-10-02）

RI16核心已提交 `17ca343aafc07ee1638bcccf9b735b7c5292b57f`，27文件、提交后worktree干净；64唯一PASS／0FAIL／0SKIP、18K／2XML格式及同批独立验收闭合，正式发布／硬件门禁保留未勾选。继续复用原implementation代理主要实施RI17，根负责固定接口、同一报告／契约／manifest、整合和独立验收；预计2–3小时，主要成本真实wheel／滚动消费和页面→原刷新命令／任务所有权的红绿与受影响验证。无新代理或审查轮次；仅当前行为focused红绿、稳定后一组explicit affected与scoped格式，完整矩阵／audit／正式构建仍0，统一RI18。

固定最终设计4.5与D01–D08：80／48dp、Armed提示至少300ms、分段至少400ms、3秒绝对有效期、真实完成后连续800ms无滚动冷却；到顶本身不武装，未提交期间反向／离顶／失焦／离页／选择／模态／分类／查询／筛选变化撤销。非空短内容可用，空／加载／错误不可；无修饰原生内容wheel参与，Home／PageUp／Ctrl／Alt／Shift／横向／滚动条／代码定位不触发。Desktop adapter沿实际Compose／AWT源码确认单位转换与异常delta上限，不改变PROJECT_POLICY阈值，不复用RI06独立250ms分类段。

书架消费实际Root owner→LibraryScreenModel.refreshLibrary→同scheduler，当前完整分类不被UI查询／筛选裁剪；详情消费当前作品原真实目录更新链，宽屏仅右章节容器、窄屏内容区，不让左资料栏／面板触发。提示不挤列表，不新增更新器或定时假完成。实际接受与完成必须由原Job／scope权威判定，busy不同scope不能返回原任务后假称新范围刷新。此项只保护真实调用者，不额外要求全应用不同作品新增全局互斥；详情先核SaveSourceMangaForDetails真实Job／refreshStates或既有runSingle接线，沿原架构证明同作品去重／失败／取消／完成。Windows实体鼠标／precision touchpad／自然滚动／125–200%DPI门禁统一RI18，离屏与可控Clock不能替代实体证据。交付production／测试及必要架构文档，根四文件仍独占；不增加另一报告、逐任务快照或diff包。

RI17／RI18实体输入前置：2026-10-02用户明确答复“目前只有 Windows 鼠标，暂缺精确触控板”。这是器材可用性事实，不是取消原门禁的授权。代码实施、focused与最终完整矩阵／正式产物继续，原Windows precision touchpad验收保留未取得，不能以模拟wheel、Mac触控板或离屏density代替；鼠标／自然滚动／DPI实机路径在最终实际产物上提供。RI17对应checkbox未取得全部必做证据前保持未勾选。

RI17 红绿与接线核验（实施中）：`policy-acceptance-red`05:14:29–05:14:58 UTC为3项正确业务红（阈值／撤销2、忙碌时错误接受另一范围1）；`policy-acceptance-green`05:16:50–05:17:19为对应3项PASS／0skip。`native-red-valid`05:22:52–05:23:34为真实Root及详情事件两项Armed提示缺失的正确红；早先Locale缺失和platformScrollConfig调用错误仅编译失败，不计业务红。Compose1.10.2实际Windows滚轮配置负责AWT精度、scrollAmount、viewport和density转换；顶部不可消费时Foundation不派发postScroll，故Desktop adapter局部消费真实PointerEvent Final的未消费量并调用该版本配置。内部API抑制只限本文件，升级须复验真实事件，不能把raw scrollDelta当dp或另设经验倍数。

`native-green-valid`仅详情1PASS，Root仍失败；随后实际Root已Armed却出现默认分类空名称，记录为可见标题缺陷。Root夹具加强为当前分类两本＋其他分类一本，实际查询只显示一本但断言完整分类的两本且不更新第三本；范围方法随后PASS，不把原同属默认分类的夹具当范围保护。`owned-result-red-valid`05:45:08 XML正确复现实际FileTaskCheckpointStore的A分类任务Failed、B全库任务Running后，A调用者误读B occurrence。结果沿本次接受句柄固定，`input-boundaries-red`05:49:57该项PASS；同轮真实programmatic列表移动未延长800ms静默的正确红随后已绿。原生density1／2、fractional AWT、从中部到顶不武装、修饰键及已消费面板边界有效，最终去重数量以收口affected XML为准。

`native-boundaries-green`05:53:48–05:53:58 XML共5项＝4PASS／1FAIL／0skip：实际详情两个事件方法、programmatic静默和完整分类Root通过，Root菜单／选择／查询／focus夹具等待未闭合，不能称全绿。同一持续独审补查未启动Job取消和初始store拒绝仍借历史Completed；实施者已形成相应业务红，原生内容失焦仍Armed亦为正确红。当前仅修原请求完成归属及内容焦点撤销，不新增任务注册表／更新器；边界、格式、affected及独立验收闭合前不提交，不提前执行完整矩阵。

后续边界证据：`owner-focus-red`05:58 XML接受柄两项及内容失焦一项是正确业务红；`owner-focus-green`06:01接受柄4＋focus1 PASS，Root首个wheel夹具未等实际卡片渲染，不计业务红。`final-native-red-valid`查询阶段取消requestCancelled=false为正确红，Home／PageUp夹具的AWT KeyEvent直接包装是ClassCastException，不算功能红；随后查询取消和合法InternalKeyEvent焦点／键盘方法均通过。真实详情三种modal（已读确认、迁移搜索结果、重复作品）同一方法内三断言均复现Armed残留，最小补既有资格gate后PASS；是一个唯一方法的三条路径，不计三项新方法。

Armed绝对期补验：`inertia-root-red`06:32:23 domain正确红为3000ms持续输入直接重新Armed；到期后等待同400ms新段守卫，不加入假任务冷却，`inertia-root-green`domain三项全绿。Root仍失败时只做有界诊断：实际06:36:51 XML中CanvasLayers仍有focusedLayer及More菜单的Update library／Update category语义，旧ownerCount等待提前满足，关闭后的scroll实际仍被菜单隔离。主代理独立读取同证据，不能把此夹具状态当产品丢滚轮原因；后续等待真实菜单入焦及Escape后菜单语义消失，诊断删去，原撤销及零请求断言保留。有效最后绿、唯一affected及格式仍待终态。

### RI17 核心收口

RI17核心最终验收：唯一`interaction-ri17-affected`原36项为34PASS／2夹具前置FAIL／0skip，原生大delta后的120ms不是平滑滚动到顶保证，Root query模型就绪亦不等于真实EditableText／可见布局就绪；仅补有界实际等待、无追加scroll／生产改动。`interaction-ri17-affected-repair`06:51:39.783–06:52:03.376 UTC PASSED／exit0，XML06:51:56.478／58.126两方法PASS及原14K／2XML scopedCheck通过。根独立读取affected＋repair的最新方法union：10类36唯一PASS／0FAIL／0SKIP，domain3、Desktop33，其中新17包含在36内。各类为waiting2、acceptance5、scheduler3、RI06wheel3、LibraryModel6、Detail5、原Source entry2、native input4、Root／waiting3、policy3；不累加早先重复绿或把一方法的三modal断言算三方法。

同批初审及必要修正核验闭合。最终Root四种撤销分支各用新实际DI／Root场景，先确认Armed，再执行真实menu／Ctrl选择／query／window focus、断言旧提示撤销及零请求；原同CanvasLayers在popup关闭后的命中缓存复用未证明产品缺陷，不能记为生产丢滚轮修复。此前popup仍存在的诊断只解释当轮，不能外推文字／layer随后消失的轮次。无临时reflection／日志／重入helper留在交付。两张320dp／fontScale2实际PNG由根再次查看：浅书架及深详情提示完整、overlay不占列表布局空间；Windows／Compose1.10.2／Skiko0.9.37.4／JDK21／density1，实体DPI、鼠标和触控板仍非本证据。

代理六字段回执IMPLEMENTED／STOPWRITE／UNCOMMITTED／processNONE，最后worker46096及Gradle70348已退出，19实施路径＝14K／2XML／既有architecture／2PNG。根核64 capabilities、356现行role locators、11处行漂移（重复ADD_TO_LIBRARY以HEAD七行上下文定位，不盲取首项），RI17 nested覆盖17唯一新方法；status／decision／history／root methods／冻结actionInventory／upstream／FIXED_ORIGINAL保持不变。新增共享状态机及Desktop原生adapter角色与必要tag反映实际平台边界，未推广capability状态。超过8文件／400行的内聚性为同一纯状态机→原生输入→两个现有owner→本次任务结果权威，未新增更新器、持久gesture、task registry、HTTP client或依赖。RI17核心可提交并进入RI18，checkbox因正式发布／实体门禁仍未勾选；本批约1小时45分，在2–3小时预算内，全量／audit／正式构建仍0。

### RI18 收口预算及环境前置

RI18收口预算（2026-10-02，RI17核心已提交1e6a49c0c9）：复用唯一实施代理，主代理承担同一独立验收、根metadata／契约／报告及跨平台整合；无额外技能、代理或审查轮次。步骤为RI17专项闭合与核心提交→已确认provenance漂移的有限修正与focused guard→冻结全部行为diff→一次完整Android／共享／Desktop／test-desktop矩阵、governance、finalParityAudit及格式→官方build-only、Android candidate／verify及隔离macOS官方构建→实际发布runtime／Test Mode／升级与硬件边界记录→必要checkoff和功能批提交。预计3–6小时加设备等待，主要成本完整测试、R8与各平台正式构建；日志／XML／协调器／构建清单是过程证据，源码／唯一报告与正式产物为交付。macOS不覆盖旧checkout或旧应用，Android不安装或操作实体设备，所有运行使用隔离profile。全量只执行1次，失败先只读诊断及必要focused修复；超过预算或需要第二次完整矩阵／显著追加成本时先说明具体失败及替代方案，不将时间耗尽当通过。实体门禁保持原要求，暂缺触控板不阻断独立构建工作。

收口顺序按用户“全部功能实现后再全量”统一：先完成RI00–RI17实现、focused专项及独立审查；RI16／RI17必须依赖最终发布runtime的设备／键鼠证据在RI18构建后取得，相关checkbox保留未勾选，所有原定门禁仍须通过。修正此前“RI18前置包含尚未生成的发布产物证据”的顺序循环，不提前重复全量或发布、不改变验收范围。

2026-09-30 只读 SSH 预检：`mbp` 的连接在5秒上限超时，按已配置的 `mbp-lan` 有界追加一次后成功。实际系统 Darwin／x86_64／macOS 14.8.4，既有 Temurin 21.0.10+7 可执行。记录中的旧同步隔离 checkout 存在，HEAD `d9999a3e5807b27242c2b239233fc029969d4495`，工作树有未提交改动；本轮未修改、清理、构建或部署该目录。RI18 须另建本任务隔离 checkout，开始前再次核对身份、进程和配置。连通及 JDK 证据不能代替 macOS 构建、UI、钥匙串或正式运行验收。

Android只读预检 `python scripts/build-android.py check --signing` 返回0／signing verified，SDK36、build-tools36.0.0及JDK21.0.11可用，原发布证书身份验证通过。未分配候选版本、未构建／安装APK，不能作为最终候选验收；收口时在冻结源码上重新按规范核对。

RI18构建入口补核（只读）：当前build-desktop.sh的build-only会分配BUILD＋1并跳过JVM tests；macOS run_macos按MIHON_MACOS_DIST_ROOT／MIHON_MACOS_DEPLOY_DIR构建和复制，其默认部署目标是已有/Applications/Mihon Desktop.app，部署前会删除该目标。最终Mac验收必须显式指定本轮隔离路径，核实真实目标不与旧应用／旧checkout重叠，不能沿默认覆盖。Windows统一入口委托build-windows.ps1，其构建／runtime／extension验收后输出Final unpacked EXE。当前只是读取脚本，未分配版本、未执行构建或部署；最终同冻结行为diff完整JVM证据先齐，再使用build-only并记录各实际版本／来源，不能假称版本或运行已通过。

RI18协调器补核（仍为只读）：实际当前build-windows.ps1令$Gradle指向gradlew.bat并直接调用，build-desktop.sh的run_macos也直接调用gradlew；两脚本及两wrapper未发现gradle-coordinator调用。AGENTS所述统一入口已协调不能替代当前源码事实。最终须按届时真实脚本判断：已有内部协调时不嵌套；当前这种直接调用可用外层协调器保护整个正式build脚本，继续经统一构建入口，不改为直接Gradle部署。test-desktop中ExampleE2ETest是注释示例，ReaderPartialDownloadE2ETest是外部校验器契约，不代表已启动真实应用；完整客户端测试与正式产物Test Mode分别记录，不能混充运行验收。这里只读，无版本分配／测试／构建执行。

### RI18 有限清理、治理索引与冻结前核验

RI17核心提交为 `1e6a49c0c9ebee38b3a0eb841f8b53eef9c7bdf2`。RI18移除未被production消费的tryRun端口与非空Job的不可能分支；原busy拒绝测试现在直接调用真实acceptNow／acceptSingle。没有新增产品行为或测试方法。`interaction-ri18-cleanup-focused`（07:03:01.989–07:03:25.124 UTC）11项唯一方法全绿、0跳过，3K scoped格式通过；主代理独查实际3文件diff及XML，不把清理当作新功能红测。

治理首次10项为5通过／5失败；后续仅对仍失败守卫复验：5项2通过／3失败，3项2通过／1失败，最后roadmap单项通过。失败属于后续证据混入精确历史索引、错误ADAPTER种类／共同层及声明路径缺漏，未改守卫断言或产品逻辑。历史root行为契约恢复后，后续methods、path、platform role及fixture均完整保留在interactionIterationEvidence.RI18；ID95实际方法更名同步当前decision，旧Task16C decision原样保留history。没有提升64项status，没有改变fixed-original、upstreamSymbols或NR0 actionInventory／tracked基线；当前351项root锚点与5项迭代role锚点均指向真实源码。

manga.refresh与library-update.manual新增当前ENTRY／EFFECT／FEEDBACK记录于RI18 actionEvidence，绑定实际Root／Detail事件、工厂accepted端口及可见反馈。NR0冻结基线及其PENDING／LU-01历史记录保持原样，不将本轮证据伪装成该独立历史计划已完成。核心focused结果与稍后的完整矩阵、发布runtime及物理输入分别记账。

macOS本轮预检：14.8.4／x86_64／Temurin21.0.10，系统盘剩余4.5GiB，现有独立Mihon进程39908及旧checkout未提交改动保留。仅在新隔离目录构建，限制并发并监测空间；空间不足停止该平台，不清理其他任务。Windows只有鼠标，精确触控板／自然滚动及物理DPI门禁仍待实机证据。

### RI18 最终矩阵首次执行与失败保留

冻结输入后，唯一完整矩阵通过协调器 `interaction-ri18-final-matrix` 执行：`gradlew.bat jvmTest testReleaseUnitTest :test-desktop:test :app-desktop:parityGovernanceCheck :app-desktop:finalParityAudit spotlessCheck -PincludeIntegrationTests=true --offline --continue --console=plain`。实际开始为2026-10-02 07:28:20.700 UTC，结束为08:08:28.606 UTC，终态CANCELLED／exit130。外层15分钟等待超时后，原Gradle仍运行，因此未启动第二个Gradle；有界线程诊断确认Desktop旧启动夹具等待已不再自动启动的更新任务，随后仅停止协调器所记录的进程树。根独立核对worker64196、wrapper82580及test workers72188／74252均已退出，未清理全局Java或其他工作树应用。

根逐个解析实际归档 `.gradle-coordinator/ri18-xml/final-matrix/<module>/build/test-results/<task>`，按模块／任务／类／方法去重，332份XML共2487项＝2481通过、5失败、1跳过。旁路平级目录是重复副本，不叠计。test-desktop的52项来自未改变输入的FROM-CACHE，不称本轮新执行；跳过项是SyncGitCompareAcceptanceTest的显式本地Git比较门禁，不作已验证处理。domain两平台、data两平台及已结束公共模块的结果有效；app-desktop:jvmTest和app:testReleaseUnitTest尚未正常结束，没有本轮完整XML，旧focused XML不能补作本轮全量证据。完整治理、finalParityAudit及Desktop格式任务未取得最终执行结果，矩阵没有全绿。

5个XML失败为两平台SourceUpdateMemoContractTest仍期待空目录交给平台，而当前共享保护已拒绝空目录；三个旧版本数据库夹具从当前schema倒推时遗漏40.sqm整组对象，首次真实错误均为`chapter_url_aliases already exists`。日志另记录Desktop架构守卫两项失败及Android八个唯一失败（自动retry不重复计数）：生产初始化新增runBlocking、UI直接访问CategoryRepository，以及旧Android夹具未完整接入当前分类清理／目录phase链。各原因须由实际修复与聚焦结果分别确认，不把所有日志失败都归因于同一假设。禁止放宽架构基线、删除目录／归档断言或用CREATE IF NOT EXISTS隐藏迁移问题。

按原预算先进行有限修复与focused验证，暂不重复完整矩阵。新增实际DI取消测试 `cancelled DI owner releases a suspended category snapshot before recovery or producers` 在 `interaction-ri18-startup-cancellation-red` 正确失败：08:18:45.678 UTC XML，1失败／0跳过，原初始化在真实category查询挂起后不响应owner取消；finally释放查询并关闭上下文，协调器08:18:49.751正常FAILED／exit1。此业务红是suspend初始化修复的依据，源码扫描守卫不代替行为红。修复后仍须取得两个未完成模块的有效整体验证；需要追加全量次数时，先列出具体失败、拟执行范围和成本，等待用户决定。

RI18有限修复聚焦收口：`repair-focused`保留domain双端及data三个迁移方法共5通过、Android三个通过；Desktop类型缺失仅编译失败，Android余五项实际由新夹具使用未实现StringSet的InMemoryPreferenceStore引发。Sync启动的system-err确认App.startSync在DeleteCategory恢复处遇到同一TODO，不把单独timeout当因果。改用已有平台偏好后，`repair-valid`（09:56:34.605–10:01:00.672 UTC）Android五项通过，Desktop17项中15通过；余两项分别是model新adapter使用同一不完整偏好夹具，以及旧DI重建测试的注入回调只返回未持久化Chapter。后者改走实际注册Source→Checker→目录SQL／phase→持久下载manager，保两context隔离、已读排除与真实队列断言，并核原URL。`repair-last`（10:06:00.901–10:06:41.113 UTC）两个方法全绿，XML10:06:33.649／38.041；scoped格式共21K通过，git diff --check通过。中间suspend函数引用、Robolectric静态方法泛型、局部夹具声明与Spotless重复step配置错误未执行业务，不计业务红或新增通过。

按上述最新有效方法合并，30项唯一PASS／0FAIL／0SKIP：Desktop17、Android app8、domain JVM／Android2、data JVM3。新增两个实际取消方法包含在17内；根独查实际XML、Main选举后初始化、恢复先于producer、原生share port／scope／manager／SQL与网络清理、primary与suppressed错误，以及分类排序共享用例与原补偿边界。UI搬移时新增的一次无保护分类读取已在交付前撤回，继续使用已有flow。架构守卫零债务基线保持；没有改schema、删除业务断言或绕过真实manager。当前capability状态及历史权威不变，15处当前role／action行号按源码更新，重复downloadPolicy锚点通过HEAD上下文确认；新增取消方法登记cap4的RI18 nested证据，空目录契约和排序适配证据亦保留nested。

本批超过8文件／400行仍是一个内聚的最终失败修复：suspend初始化需要贯通Main、DI及函数引用契约，UI排序移动保共享用例，旧夹具同步实际目录／分类／下载链；DI大部分行变化来自新增有限异常边界后的格式缩进。已复用同一实施代理及持续独立验收，没有新增代理或独立审查轮次。上述30项是focused收口，两个未正常结束的模块全量仍未补跑，正式构建／平台运行／物理输入门禁仍待。

RI18原未跑门禁 `interaction-ri18-remaining-gates`（10:09:05.961–10:10:00.903 UTC）PASSED／exit0：parityGovernanceCheck 7项、finalParityAudit 1项全绿／0跳过，NON_TERMINAL_IDS为空，全仓spotlessCheck通过；它们不替代产品行为、发布runtime或硬件门禁。JUnit另明确警告两个旧下载方法返回int／boolean而未执行，不能把这种未发现当XML skip或通过。仅约束两方法runBlocking<Unit>并保持真实HTTP／manager／文件断言，`discovery-green`（10:17:07–10:17:20 UTC）2项PASS／0skip、单文件格式通过，实际XML10:17:18.993Z，警告消失；首轮业务已绿但格式失败的事实保留。修复focused共32唯一PASS，另有8项治理／审计PASS。

2026-10-02用户明确“批准”追加：仅将此前中断的 `:app-desktop:jvmTest` 和 `:app:testReleaseUnitTest`（包含集成）各完整补跑一次，预计30–45分钟；已完成其他模块与刚通过的治理／审计／全仓格式不整组重跑。实施者STOPWRITE，根独查32项及8项归档结果后冻结。当前production／test／manifest输入集合SHA-256为`ae7ccd3132a42458535a9193f6e8e3842f8afcd93d7f7495dd9b57b6afe0db0a`，逐文件记录保存在`.gradle-coordinator/ri18-release-freeze.json`，来源HEAD为`1e6a49c0c9ebee38b3a0eb841f8b53eef9c7bdf2`；报告与其他Markdown不参与该集合哈希，后续构建版本分配单独记录。此前中止矩阵的原冻结及失败证据保留，不冒作本次修改的验证结果。

### RI18 获批补验的失败证据

`interaction-ri18-interrupted-modules` 于10:18:40.760 UTC启动原定两个完整模块；Desktop任务于10:40:39 UTC报FAILED，根独立解析450份当前XML，3652项＝3559通过、90失败、3跳过，失败涉及28个类。归档为`.gradle-coordinator/ri18-xml/interrupted-modules/app-desktop/build/test-results/jvmTest`。本次已取得完整Desktop结果，不能因后续focused修复而改写为当时全绿。Android原协调器此时继续执行，不并发另起同工作树Gradle。

三项跳过分别为MacOsNativeSharePortTest的macOS实际JXA用例（已由下述macOS专项另行通过）、DesktopWindowPrivacyTest的真实Windows窗口affinity用例（当前完整测试强制headless），以及LibraryPageCompositionTest的显式non-release interval用例（当前构建开关不满足其前提）。不把条件跳过计入PASS。

原补验最终于10:47:42.715 UTC正常结束为FAILED／exit1，约29分钟，记录中的worker／wrapper与Desktop test worker已退出。Android完整126份XML共有709次执行；按类／方法去重为707项＝699通过、1失败、7跳过。唯一失败是DualPageProgressProductionWiringTest的`settled dual viewport records last visible page and completes only at actual end`，自动retry共三次均失败，不计成三个独立用例。实际XML失败为376行`current.chapter.read`，前一行`last.isRead`已通过；日志341只是方法入口。7跳过为真实FileProvider要求Unix宿主的3项，以及Release SQLite原生driver不能在host Robolectric加载、只在Debug JVM执行的4项旧数据库回调契约；未宣称这些门禁通过，也未运行实体Android验收。

完整XML揭示下载链中的`Node has been removed`，来自DesktopDownloadManager回退读取Injekt遗留偏好、随后访问已由前序测试删除的节点；多个后续runTest记录同路径的UncaughtExceptionsBeforeTest。设置夹具另有LibraryPreferences.autoUpdateMangaRestrictions调用不完整偏好实现的TODO。上述具体路径与修复效果仍须最小复现和focused证据闭合，不把所有90项失败归为一因。平台证据守卫实际指出ID82新增消费者混入历史精确路径集合；当前分享页面与测试证据必须保留到迭代层，历史精确索引及能力状态不得借修复改变。

### RI18 补验失败的定向修复

下载顺序依赖先保留有效复现：`download-isolation-repro`虽然3项通过，但实际XML显示下载先于DI节点清理执行，不能作为反证。随后仅用JUnit官方ClassName顺序在`isolation-android-red`固定真实DesktopAppRuntime节点清理在先，两项真实下载方法在后，重现相同`Node has been removed`；同轮Android原末页方法也仍在376行失败。修复为五个直接构造manager的夹具各自显式持有DownloadPreferences，保留production默认装配以及文件、冲突、重试断言，绿测结果另记。

Android唯一失败的原因已闭合：测试repository发送进度事件时，应用持有的接受回执及Main线程UI更新尚未完成。仅在原事件断言后等待真实`awaitAccepted(1)`并推进测试Main调度器，保留SQL、末页、回翻及已读断言；实施代理独立核对等待边界。`download-settings-green`归档Android XML时间11:01:12.340 UTC，该原方法1PASS／0FAIL／0SKIP。同一命令Desktop止于三处测试构造参数顺序编译错误，尚未运行Desktop测试，不能将该轮整体记为通过。

Desktop `download-settings-green-valid`（11:03:06.383–11:04:37.921 UTC）148项＝145PASS／3FAIL／0SKIP；真实DI清理前置、五个下载类、身份解析与四个污染受害方法、三个更新范围方法及34项capability契约通过。按原90失败的类／方法精确匹配闭合63项，不将额外通过方法冒作原失败。原ID82历史精确根字段已从RI00父提交核对恢复，后续分享路径／fixture／方法保留在RI18嵌套层，34项守卫通过且64项状态不变。

剩余两处Library设置类型错误来自relaxed依赖的categorySortSettings.failed返回泛型占位对象；资源／无障碍夹具明确不装配该无关可空保存端口后，`theme-role-red`（11:07:16.301–11:07:47.902 UTC）两项通过。该轮第三项则正确复现真实外观模式控件缺少RadioButton角色；本地Material3 1.9.0-beta03实际组件显示MultiChoice分支没有该角色，不能删掉语义断言。最小修复切换既有Material3单选分段控件，保留原保存及同值不写行为，RadioButton／Selected互斥状态、真实点击及原偏好失败消费者的绿测另记。此时原90失败已有65项闭合，正式构建仍未开始。

### RI18 定向闭合与最终源码冻结

补验原始失败保留不变；后续采用受影响方法复验，没有第三次全量。`ui-fixtures-green-valid`执行42项（38PASS／4FAIL），`ui-last-green-valid`执行4项（2PASS／2FAIL），`ui-final-two`执行最后2项全绿及对应scoped格式。最后两项修正测试的真实三态行语义及弹层初始焦点前置，保留完整业务断言；没有将焦点夹具问题记为production缺陷。外观单选分段的RadioButton／Selected修复与其保存失败、无效点击消费者已通过Windows及macOS实际Compose事件验证。

主代理从原归档XML逐项匹配类／方法，原Desktop90项全部获得后续PASS，Android唯一失败亦闭合；两个使用TempDir的方法仅做已核对的参数签名归一化。最终修复后189个唯一方法PASS，连同本批更早的专项为230个唯一方法PASS，均0FAIL／0SKIP；这些是最新方法结果并集，不改写原完整运行FAILED，也不将重复执行累加。原完整矩阵中未受影响的通过结果继续有效。治理／finalParityAudit的8项及完整格式检查已通过，随后改动的Kotlin文件另经scoped格式通过。

实施代理已返回六字段回执并停写，主代理独立核对生产变更、原失败闭合、最新XML及文件范围。正式构建前49个改动文件冻结，行为输入SHA-256为`3590a3d1b53bf6fd7ee05e5f20519568226c3f7f6533a10257578734d35df749`（Markdown与版本分配另记）。macOS隔离checkout先核对旧文件身份，再同步最终49个文件并逐项校验SHA一致。此后进入官方build-only与发布runtime验收，不追加全量测试。

### RI18 macOS 原生专项

在 `mbp-lan` 的独立 checkout `/Users/altair/github/mihon-interaction-ri18-20261002` 核对 HEAD 与25个冻结文件逐项SHA一致，保留旧checkout、已有应用和配置。环境为macOS14.8.4／x86_64、Temurin21.0.10、16GiB内存；限定Gradle2 workers、2GiB heap、Kotlin in-process。初次offline专项在三个UI依赖缺缓存处失败，未执行业务测试；确认本机xray代理后，仅在线重试相同专项，未重跑全量。

`interaction-ri18-macos-focused-online`（10:26:06.480–10:30:03.812 UTC）9项PASS／0FAIL／0SKIP：MigrationFileStagingTest原生文件保护6项，MacOsNativeSharePortTest实际production JXA delegate终态与自然退出1项，AppearanceInteractionTest真实Compose浅色隐藏纯黑设置并保偏好、Home内语言普通子页导航2项。JXA用例注入实际delegate回调，不声称已向另一个应用或真实账号分享。归档为`.gradle-coordinator/ri18-xml/macos-focused`。

`interaction-ri18-macos-permissions`（10:31:19.420–10:31:46.301 UTC）3项PASS／0FAIL／0SKIP，执行既定POSIX权限分支：LibraryInteractionTest真实磁盘封面失败保字节／版本并可重试，MangaDetailInteractionTest封面替换／取消／删除的实际文件和像素，LibraryOptionsInteractionTest固定删除集合与部分失败后的有效重试。归档为`.gradle-coordinator/ri18-xml/macos-permissions`。根独立读取两组6份XML合计12项；磁盘剩余约3.7GiB，未清理其他业务。这些是开发JVM／原生离屏专项，正式macOS应用装配、发布runtime、实体输入及账号验收仍分别待补。

新增主题角色修复在Mac按受影响范围复验：仅同步Appearance生产页和对应无障碍测试，两文件SHA逐项与Windows输入一致。`interaction-ri18-macos-theme`（11:12:24.446–11:16:14.651 UTC）3PASS／0FAIL／0SKIP：真实RadioButton／Selected互斥、单激活动作、Light隐藏纯黑并保值，以及真实Home语言子页导航；归档`.gradle-coordinator/ri18-xml/macos-theme`。后两项属于前述专项的修复后复验，Mac累计13个唯一通过方法，不能把重复执行计成15个。生产代码编译导致本次耗时约3分49秒；仍是开发JVM专项，尚未替代正式应用装配及发布runtime验收。

### RI18 首次正式构建与发布运行发现

官方入口分配Desktop `0.11.19.69.1e6a49c`。Windows后台启动PowerShell两次未进入构建（入口exit127，明确Windows PowerShell委托exit4294967295），不计作编译失败；保持已分配版本，使用协调器foreground及明确pwsh.exe继续官方`build-windows.ps1 -SkipTests -VersionAllocated -ExpectedVersion`后进入编译。Mac在隔离dist／deploy目录运行官方`build-desktop.sh build-only`成功，日志为`interaction-ri18-macos-release`；未覆盖旧安装。

实际Mac发布应用使用独立modern Test Mode profile启动，真实库模型READY，Windows专属设备条件在Mac为空。应用先正常退出，再向其实际数据库与文件目录种入有限的中断准备receipt；重新启动production后精确恢复原文件字节、删除receipt与staged文件，两个manga身份保留，真实搜索也成功。验收脚本最初将日志放入未标记profile及误用GET shutdown的两处操作错误已经纠正，不能归为产品缺陷；所有启动进程均已按本轮端口正常退出。

随后真实`POST /test/action/sort`返回500，正式包同时带入`kotlinx-coroutines-android`与Swing；AndroidDispatcherFactory优先被选中，因Android兼容层没有`Handler.createAsync(Looper)`导致`Dispatchers.Main`不可用，真实Voyager screenModelScope排序链失败。这是发布运行发现，不能用此前UI测试替代。Windows同源构建于11:40:27 UTC仅停止本轮协调器进程树（CANCELLED130），未发布该缺陷包。后续修复及最终产物另记。

Android正式候选前已只读核对Git登记且实际存在的工作树版本与候选目录，最高已交付code40，故本轮分配code41／`0.19.4-aex.23`，应用ID与原证书不变。再次`build-android.py check --signing`通过；版本分配不等于候选构建或安装完成。

### RI18 发布调度器修复闭合

仅在`app-desktop`依赖边界排除传递的`kotlinx-coroutines-android`，保留Swing、共享domain与Android依赖、扩展Handler／Looper兼容层。新增`DesktopMainDispatcherRuntimeTest`将真实main compilation runtime classpath与探针字节码交给独立JVM；明确不含coroutines-test，不调用setMain，先验证真实Swing EDT，再经DI／Library factory／Voyager排序链断言共享偏好持久化。

首次红测因Windows CreateProcess206命令行过长未执行，不计业务红；改用UTF-8 classpath文件与Java argfile后，`main-dispatcher-red-valid`的11:47:34.367 UTC XML正确复现同一Handler.createAsync错误。最小排除修复后11:47:59.173 XML通过；`main-dispatcher-affected`10项均PASS／0SKIP（新增fork、真实排序、许可资源与UI、6项既有Handler／Looper行为），原命令仅因两文件CRLF格式失败，规范化后`main-dispatcher-format`于11:51:24 PASS。主代理独立读五文件变更和10项XML，确认没有用测试dispatcher掩盖正式装配。

本批修复后的最新结果合计237项（按平台区分同一共享契约，236个源码方法），全部通过；10项受影响回归中3项与此前结果重复，新增7项不重复计数。Android与JVM各自运行的共享空目录契约保留两份平台证据。原完整矩阵及补验FAILED记录仍保留，不宣称又执行了一次全绿全量。

重新生成许可数据后实际204项，runtime provider与UI均通过原内容断言；先前203与中间205的缓存不能作为当前发布清单。最终53文件再次冻结，行为输入SHA-256为`47afc518d98f7293fbb18bed628c75e1cd6eebcfdc43627e4eb19e70ab05af73`，版本字段单独记录。Mac逐项同步校验后发现export仍FROM-CACHE恢复旧203项，停止该轮、仅删除本任务隔离目录下已确认的生成JSON并关闭该次构建缓存，再走官方build-only；实际生成204项且无Android dispatcher条目。没有修改生成插件或其他项目缓存。Windows官方脚本自身使用rerun-tasks重新生成。

### RI18 最终正式产物与实际运行

Windows官方构建入口分配`0.11.19.70.1e6a49c`；Bash委托启动再次exit127且未进入编译后，保持该版本、用已验证的foreground方式继续其官方PowerShell脚本。`interaction-ri18-windows-release-final-resume`于11:56:29.182–12:00:35.370 UTC PASSED，完成所有正式构建步骤、真实扩展APK安装／源解析验收、发布未打包目录及ZIP。完成报告使用日志中最终`Final unpacked EXE:`，文件存在；不使用tmp目录作交付。

macOS最终`interaction-ri18-macos-release-fresh`于11:57:34.060–11:59:38.943 UTC PASSED，版本`0.11.19.71.1e6a49c`。Mac因中间缓存清单构建被中止而额外分配一次BUILD；两端功能源码一致，版本分配分别记录，不把71误报成Windows版本。实际应用位于本轮独立目录，未覆盖旧checkout或现有安装；使用系统ditto完整归档应用包并传回Windows，SHA-256一致。直接核对Windows发布JAR及Mac归档内实际JAR，两端许可数据均204项，不含Android dispatcher；不是仅查生成目录。

两个平台各完成正常准备恢复和文件冲突恢复两种实际发布runtime场景。每种使用新modern profile，由发布应用先创建数据库，再正常退出并写入有限中断准备夹具。重新启动后验证精确原字节、暂存文件／receipt清理、两作品身份保留；通过真实Test Mode模型搜索／排序、再次退出重启后排序持久化。冲突场景实际报`A later download prevents rollback`，原字节、冲突文件和receipt均保留，移除仅本轮已核对的冲突文件后可重试成功。没有以系统JDK／独立HTTP或SQL客户端代替production恢复链。

Windows的冲突拒绝会保留jpackage启动器错误对话框，因此首次脚本等待自然退出超时；不能把该轮自动脚本记为通过。主代理核实本轮父子进程的发布EXE路径与独立profile后，仅关闭子进程的`Mihon Desktop.exe`错误窗口，确认进程退出，再从同一保存完好的receipt继续验证，恢复／排序／重启均通过。此证据证明拒绝覆盖与可恢复，不声称Windows错误路径能无窗口自动退出。所有验收实例最后已正常退出或按上述方式关闭，未操作其他Mihon实例。

Windows同一production DI设备端口的真实发布查询返回`ac=SATISFIED`、`wifi=UNKNOWN`、`network_not_metered=UNKNOWN`，三项能力均暴露；Mac能力集合为空。只报告查询实值，不推断未知的根因，不修改真实网络／供电状态；条件切换、等待续跑的物理验收仍待完成。这里的headless模型链不等于实际窗口、鼠标／触控板或DPI验收。

Android统一`candidate`流程内部协调器于12:01:34.301–12:05:14.454 UTC PASSED，正式候选在12:05:20.686 UTC生成，随后独立`verify --artifact`通过。原应用ID、证书连续，code41／`0.19.4-aex.23`、v2／v3签名、非debuggable、四ABI、min26／target36，R8／资源压缩开启，遥测／更新器关闭，mapping哈希已校验。R8日志包含Window extensions／sidecar与RE2类warning，但正式任务成功；没有安装设备或声称ART运行／原设备升级完成。

| 平台 | 本轮可交付产物 | SHA-256 |
| --- | --- | --- |
| Windows | [最终未打包EXE](<D:/Codex/worktrees/99ac/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.70.1e6a49c-unpacked/Mihon Desktop.exe>)；[完整ZIP](D:/Codex/worktrees/99ac/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.70.1e6a49c-windows.zip) | ZIP `7b82af8d263b4b0e382ab6830e34ba411fcdd65617a3f37323edd712cb270acc` |
| macOS Intel | [完整应用ZIP](D:/Codex/worktrees/99ac/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.71.1e6a49c-macos-x64.zip) | `2a7fc88e40873128406970fbc22e7df830b334a383d71fe2e23978bcc81f5d68` |
| Android | [正式签名候选APK](D:/Codex/worktrees/99ac/mihon/app/artifacts/android/0.19.4-aex.23-vc41-1e6a49c0c9-release/Mihon-Fork-0.19.4-aex.23-vc41-release-universal.apk)；[候选清单](D:/Codex/worktrees/99ac/mihon/app/artifacts/android/0.19.4-aex.23-vc41-1e6a49c0c9-release/artifact.json) | `913d654eca0fdee2d95b04db30a1b934b525c6b99c793daba8f73e0b21420853` |

正式构建均来自基线`1e6a49c0c9ebee38b3a0eb841f8b53eef9c7bdf2`加本功能批差异；冻结行为输入、各平台版本与Android候选productionInputsSha256由上述冻结文件／候选清单分别保存。构建后仅修正ID95当前角色索引从139到148（依赖边界和必要格式造成行漂移），不修改历史固定锚点、能力状态或冻结actionInventory；功能源码未改变。原索引契约单方法已由`interaction-ri18-final-locator-valid`复验PASS／0SKIP（XML 2026-10-02T12:08:21.159Z）；首次命令因PowerShell拆分未引号的Gradle属性参数而未执行测试，修正传参后仅重试该方法，没有追加audit或全量。该方法已包含在前述237项中，不重复累计。

本功能批涉及54个文件，超过原估算的内聚性在于同一最终收口：启动／迁移清理、现有分类排序适配与主题语义、发布依赖边界，以及真实矩阵揭示的旧夹具和必要索引修正。没有按文件拆成状态提交，也未扩展到其他功能；风险分别通过原生产集成测试、干净运行时fork、同批独立核验和两端真实发布运行覆盖。过程日志／XML／临时验收脚本保持忽略，交付为代码、既有文档和正式产物。

## 未完成与限制

RI00–RI15已按既有功能批完成并提交。RI16与RI17核心实现和专项验证已提交（`17ca343a`、`1e6a49c0c9`）；本轮RI18已完成可执行的代码修复、独立核验、原失败定向闭合、正式构建和上述发布运行验收，代码／测试／版本／必要文档随本功能批提交。两次完整执行的CANCELLED／FAILED及所有跳过记录保留，没有第三次完整矩阵，也没有改写为全量一次全绿。

RI16／RI17／RI18 checkbox保持未勾选：用户目前仅有Windows鼠标，尚未取得实际鼠标、精确触控板／自然滚动、125%／150%／200%物理DPI、实际网络／供电切换及休眠恢复、真实追踪／源登录账号、设备升级保留数据等完整门禁。离屏与Test Mode、模型／MockWebServer、签名候选和本轮隔离新数据库恢复均不替代这些要求；Android没有安装或操作实体设备，macOS没有覆盖现有配置。

用户可先用上表Windows最终EXE验收设置→外观单选、书架排序保存／重启、正常鼠标两段顶部刷新及左栏不误触；精确触控板和不同物理DPI仍需相应设备条件。完整手动条目沿用roadmap第6.3节，不另建进度权威或缩减原验收。

## 主干合并回归（2026-10-04）

用户授权合并主干、测试后提交并推送。整合输入为主干 `95da5cc78b2d43ef31558e85d76c00646d23ed41` 与交互分支 `2e16362957dd4e366944e41d2a5039a67ede5eef`，在独立整合分支解决8个冲突。启动保留交互分支的suspend初始化、迁移恢复与失败清理，同时传递主干的隔离同步仓库scope；同步面板保留主干密码帮助、关闭回焦与观察链，以及交互分支CloudSync图标。合并后的真实DI、导航／面板事件与运行时主线程由下述回归覆盖。

Desktop保留较新的BUILD73，Android保留较新的versionCode41／`0.19.4-aex.23`，应用ID及证书不变。manifest仅调整受合并影响的当前消费者符号／行号，历史固定锚点、actionInventory及capability状态保持原记录。主干未提交的文档与原型文件不纳入整合提交。

协调器 `interaction-main-integration-tests` 于2026-10-03 17:37:45–18:06:12 UTC执行一次限定合并点的回归及格式检查，PASSED／exit0，Gradle耗时28m26s。各平台XML已归档到 `.gradle-coordinator/interaction-main-integration-results/`，没有重试失败或flakyFailure。

| 验证范围 | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| domain：`mihon.domain.sync.*` | 37 | 0 | 0 |
| data：`mihon.data.sync.*`，含文件数据库恢复及十万事件场景 | 511 | 0 | 1 |
| presentation-sync：真实共享同步面板事件 | 73 | 0 | 0 |
| Desktop：同步、DI、启动／生命周期、真实Swing主线程、Test Mode、书架状态及manifest契约 | 216 | 0 | 0 |
| Android：`eu.kanade.tachiyomi.data.sync.*` | 22 | 0 | 3 |
| 合计 | 859 | 0 | 4 |

`spotlessCheck`及冲突Desktop文件的ktlint1.8.0检查通过，`git diff --check`通过。data跳过项是需显式开启的本地Git对照实验；Android三项真实FileProvider测试因Windows宿主路径分隔符不满足既有Unix前置而跳过，未计为通过。验证范围限定于合并点；RI18此前完整运行FAILED、既有发布产物的来源及RI16／RI17／RI18硬件／账号／升级门禁保持原记录。
