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

### RI05 评分前置核对（只读，不代表实施完成）

当前 LibraryScreenModel 从已登录 tracker 的原始 track.score 直接 average；Desktop registry 的 AniList请求与解析存 POINT_100，MAL等为10分，Kitsu解析 ratingTwenty／2已为10分。Android现有 Tracker.get10PointScore／Anilist.get10PointScore明确 AniList除10。RI05应复用并共享这个投影契约，保持存储及远端回写原始分数不变，验证混合 provider真实HTTP→数据库→平均分排序，不在UI随意按最大值猜尺度。Android当前平均值对已登录有效tracker的get10PointScore结果做average，含0分；不能凭分数大小猜provider格式或随意改变0分语义。Desktop当前LibraryComponents只有评分排序名称，没有每本评分或无评分显示，RI05须核定稿A07的可见反馈。旧列数为DesktopAppPreferences的library_grid_columns，lazy首次访问才迁移旧desktop/app节点；共享横／纵键为pref_library_columns_portrait_key／landscape_key，当前LibraryPreferenceMigration仅迁移display／sort。RI05需要复用已注册AppPreferences的旧值读取和现marker迁移，保护各共享显式值（包括0自动），不因删除旧外观入口而漏掉尚未触发lazy迁移的旧值；这里是只读事实，不声明具体实现已完成。这里只确认当前链路与复用入口，尚无本批红绿或修复结论。

### RI13 目录同步前置核对（只读，不代表实施完成）

共享 SourceMangaUpdateService 已提供 getMangaUpdate网络入口，不能重建第二份 source更新协议。当前 Desktop LibraryUpdateChecker主要追加新 URL并更新章号／memo，分次更新 manga／chapter，尚无完整改名、重排和移除事务。Android实际复用入口是 `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt`，不是 domain中的同名文件；其非本地空响应抛 NoChaptersException，包含去重／名称规范化、recognition、sourceOrder、重复已读与换链接数值状态、dateFetch保护、下载目录重命名。RI13须保持characterization并抽取可共享计划／事务边界，保Desktop作者观察与同步身份；网络／文件不冒充SQL原子，不以当前Desktop标记 COMPLETE作为响应完整性证明。这里只定位复用边界，未实施或验收RI13。

### RI14 恢复前置核对（只读，不代表实施完成）

现 DesktopTaskScheduler 已有持久 workset／worksetInitialized／completedUnitIds／failedUnits 与 checkpoint，不应重建调度器或通用outbox。当前 LibraryUpdateScheduler虽然保留stableIds，恢复时仍从重新按分类策略过滤的byId取目标；一次错误后break且没有区分跳过／未处理／失败全工作集。RI14在现有store及任务生命周期内补书架专用范围／结果恢复，保作者独立任务触发与CancellationException语义；只删除break不足以证明恢复完成。RI12文本误写S03–S06由RI15／16验收，已按已有总映射纠正：周期／智能／元数据RI14，设备限制RI16，不改变产品范围。

### RI18 macOS 环境前置（只读，不代表构建验收）

收口顺序按用户“全部功能实现后再全量”统一：先完成RI00–RI17实现、focused专项及独立审查；RI16／RI17必须依赖最终发布runtime的设备／键鼠证据在RI18构建后取得，相关checkbox保留未勾选，所有原定门禁仍须通过。修正此前“RI18前置包含尚未生成的发布产物证据”的顺序循环，不提前重复全量或发布、不改变验收范围。

2026-09-30 只读 SSH 预检：`mbp` 的连接在5秒上限超时，按已配置的 `mbp-lan` 有界追加一次后成功。实际系统 Darwin／x86_64／macOS 14.8.4，既有 Temurin 21.0.10+7 可执行。记录中的旧同步隔离 checkout 存在，HEAD `d9999a3e5807b27242c2b239233fc029969d4495`，工作树有未提交改动；本轮未修改、清理、构建或部署该目录。RI18 须另建本任务隔离 checkout，开始前再次核对身份、进程和配置。连通及 JDK 证据不能代替 macOS 构建、UI、钥匙串或正式运行验收。

Android只读预检 `python scripts/build-android.py check --signing` 返回0／signing verified，SDK36、build-tools36.0.0及JDK21.0.11可用，原发布证书身份验证通过。未分配候选版本、未构建／安装APK，不能作为最终候选验收；收口时在冻结源码上重新按规范核对。

## 未完成与限制

RI00／RI01 的实现、focused、唯一独立审查及必要修复复审已完成，代码、测试、索引修复和必要 checkoff 随本功能批同一提交。首簇提交为 `14a8ce15695cf69ab2e468b825e88ec91641e08a`。用户继续要求完成剩余 roadmap，现复用原实施代理串行推进后续批次，每批按既有审查与必要修复复审流程执行；完整验证仍只在 RI18。RI02 实现、focused、唯一修复复审和格式／索引检查均通过，必要 checkoff 与代码／测试在本批同一提交；RI03 实现、唯一修复复审、140项有效focused证据、格式与索引检查已闭合，必要checkoff随本批同一提交；RI04实现、140项有效focused、唯一独立初审及必要修正核验、格式及索引已闭合，必要checkoff随本批同一提交；RI05–RI18 未完成；最终全量、正式构建及运行验收尚未执行。本轮没有 Android正式候选／macOS正式构建、真实鼠标／触控板／硬件条件验收，没有交付 EXE/APK，没有把 HTML 勾选更新为 native 完成。
