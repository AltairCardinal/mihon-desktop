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

### RI18 macOS 环境前置（只读，不代表构建验收）

收口顺序按用户“全部功能实现后再全量”统一：先完成RI00–RI17实现、focused专项及独立审查；RI16／RI17必须依赖最终发布runtime的设备／键鼠证据在RI18构建后取得，相关checkbox保留未勾选，所有原定门禁仍须通过。修正此前“RI18前置包含尚未生成的发布产物证据”的顺序循环，不提前重复全量或发布、不改变验收范围。

2026-09-30 只读 SSH 预检：`mbp` 的连接在5秒上限超时，按已配置的 `mbp-lan` 有界追加一次后成功。实际系统 Darwin／x86_64／macOS 14.8.4，既有 Temurin 21.0.10+7 可执行。记录中的旧同步隔离 checkout 存在，HEAD `d9999a3e5807b27242c2b239233fc029969d4495`，工作树有未提交改动；本轮未修改、清理、构建或部署该目录。RI18 须另建本任务隔离 checkout，开始前再次核对身份、进程和配置。连通及 JDK 证据不能代替 macOS 构建、UI、钥匙串或正式运行验收。

Android只读预检 `python scripts/build-android.py check --signing` 返回0／signing verified，SDK36、build-tools36.0.0及JDK21.0.11可用，原发布证书身份验证通过。未分配候选版本、未构建／安装APK，不能作为最终候选验收；收口时在冻结源码上重新按规范核对。

## 未完成与限制

RI00／RI01 的实现、focused、唯一独立审查及必要修复复审已完成，代码、测试、索引修复和必要 checkoff 随本功能批同一提交。首簇提交为 `14a8ce15695cf69ab2e468b825e88ec91641e08a`。用户继续要求完成剩余 roadmap，现复用原实施代理串行推进后续批次，每批按既有审查与必要修复复审流程执行；完整验证仍只在 RI18。RI02 实现、focused、唯一修复复审和格式／索引检查均通过，必要 checkoff 与代码／测试在本批同一提交；RI03–RI18 未完成；最终全量、正式构建及运行验收尚未执行。本轮没有 Android／macOS 构建、真实鼠标／触控板／硬件条件验收，没有交付 EXE/APK，没有把 HTML 勾选更新为 native 完成。
