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

### RI12 书架设置前置核对（只读，不代表实施完成）

实际LibrarySettingsScreen仍以Desktop旧周期enum及updateCategoryExcludes CSV显示，categorizedDisplaySettings仅直接set；LibraryUpdateScheduler读取旧Desktop包含／排除CSV。共享LibraryPreferences已有默认分类、两份更新分类StringSet、周期Int、设备／作品限制、元数据和categorized_display；LibraryScreenModel的真实排序投影及设置面板已消费categorized_display。ResetCategoryFlags.await实际写全体SQL flags为全局sort.type＋direction，现设置页未调用，关闭清理需真实wiring及两存储恢复，不另建分类排序权威。

实际DeleteCategory删除所选default_category并清理共享更新／下载分类引用，默认-1；MangaDetailScreenModel.addToLibraryUsingDefault已通过当前categories与共享默认决定直接入库／系统默认／选择草稿。RI12在RI08已稳定收藏入口上复用该链。LibraryPreferenceMigration VERSION2已有显示／排序／列数有效共享值优先与marker最后写，可以追加同风格迁移，不能覆盖旧明确值或让scheduler读取迁移半份策略。这里只读固定接口，无RI12实现／验证结论。

### RI13 目录同步前置核对（只读，不代表实施完成）

共享 SourceMangaUpdateService 已提供 getMangaUpdate网络入口，不能重建第二份 source更新协议。当前 Desktop LibraryUpdateChecker主要追加新 URL并更新章号／memo，分次更新 manga／chapter，尚无完整改名、重排和移除事务。Android实际复用入口是 `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt`，不是 domain中的同名文件；其非本地空响应抛 NoChaptersException，包含去重／名称规范化、recognition、sourceOrder、重复已读与换链接数值状态、dateFetch保护、下载目录重命名。RI13须保持characterization并抽取可共享计划／事务边界，保Desktop作者观察与同步身份；网络／文件不冒充SQL原子，不以当前Desktop标记 COMPLETE作为响应完整性证明。这里只定位复用边界，未实施或验收RI13。

补充实际数据边界：ChapterRepositoryImpl.addAll自身一次事务但失败返回空，removeChaptersWithIds捕获并记录异常；现有单项方法不能直接保证整批同步原子。实际schema中history、chapter_pairings／boundaries／revisions、reading_events、sync_private_reading均按chapter ID引用并ON DELETE CASCADE；同步自然键消费者读取作品与章节URL，换链不能仅删后重插再称保身份。已将这些真实边界与共享Android characterization、有限恢复副作用要求固定到page-contracts的RI13节，尚无RI13实现／测试结论。

### RI14 恢复前置核对（只读，不代表实施完成）

现 DesktopTaskScheduler 已有持久 workset／worksetInitialized／completedUnitIds／failedUnits 与 checkpoint，不应重建调度器或通用outbox。当前 LibraryUpdateScheduler虽然保留stableIds，恢复时仍从重新按分类策略过滤的byId取目标；一次错误后break且没有区分跳过／未处理／失败全工作集。RI14在现有store及任务生命周期内补书架专用范围／结果恢复，保作者独立任务触发与CancellationException语义；只删除break不足以证明恢复完成。RI12文本误写S03–S06由RI15／16验收，已按已有总映射纠正：周期／智能／元数据RI14，设备限制RI16，不改变产品范围。

补核当前实际恢复／时钟边界：DesktopTaskScheduler.register在旧status=Failed时保留workset／completedUnitIds，即使caller生成新的idempotencyKey；RI14必须明确“重试原任务”与“新刷新”而非仅改key。现FetchInterval已共享，toMangaUpdate收到0窗口时getWindow(ZonedDateTime.now())、lastUpdate=0时Instant.now()，控制Clock用例须覆盖真实这两个分支，不能另写测试预测器。AndroidLibraryUpdateJob真实四规则及ONLY_FETCH_ONCE已核，未开始阅读仅totalChapters>0时跳过。实施前验收固定于page-contracts的RI14节；没有RI14实现／测试证据。

### RI15 迁移前置核对（只读，不代表实施完成）

实际 DesktopMigrateMangaUseCase先通过SaveSourceMangaForDetails保存目标目录，再独立updateChapter，最后updateMembershipsAtomically一次提交target与可选source移出。MigrationOptions现只含copyChapters／copyCategories／copyNotes，尚无封面和旧下载消费；共享MigrationFlag已包含CUSTOM_COVER与REMOVE_DOWNLOAD，可沿同一配置扩适用adapter，不新建迁移器。MigrationOrchestrator章状态沿识别号、最大已读上界、匹配书签／dateFetch，libraryPlan保chapter/viewer flags、迁移dateAdded和所选notes；复制与迁移由replace区分。实际MigrationSearchScreen两种动作均消费同一use case及批量转接。已固定目标独立身份／RI13目录、字段草稿与有限文件失败边界到page-contracts的RI15节，无RI15实现／测试结论。

### RI16／RI17 前置核对（只读，不代表实施完成）

当前Desktop已使用JNA／jna-platform5.19.1，平台原生入口可沿WindowsSystemProxySelector的OS隔离模式，而实际platform／DI／LibraryUpdateScheduler尚无Wi-Fi／计量／电源production port。现scheduler在进程内delay轮询并用墙钟lastRun，恢复与固定工作集缺口先由RI14闭合；RI16在作品边界消费小型三值port和同一完整策略，不新装服务／第二调度器。实体硬件与正式runtime证据仍由RI18提供，此前读取不作native能力已完成结论。

RI06已存在LibraryCategoryWheelModifier，Ctrl平台条件、根owner及250ms分段属于分类切换，不能把该段状态重用成两段刷新阈值。现LibraryTab的刷新入口调用refreshLibrary(allItems, 当前category ID)，详情现有源刷新及model刷新入口尚需沿RI13／14稳定目录核心统一；RI17固定80／48dp、300／400ms、3秒绝对有效期与800ms静默冷却，普通内容wheel、独立scope／scroll owner及真实任务回执接线。实际共享Clock状态机／native wheel测试尚未实施，鼠标／触控板／DPI在RI18补齐。

2026-10-01 RI16只读来源核验在本次子进程显式HTTP／HTTPS_PROXY与localhost bypass下有界并发读取微软四个原生API页面，均HTTP200、无需重试；链接与具体状态边界已补唯一page-contracts。GetCost(NULL)仅machine-wide Internet成本，不能外推实际源／VPN路由；WLAN需接口GUID和WlanFreeMemory，电源失败／255未知，无电池不自动等同在线。此次是官方来源核对，未执行Windows native查询、未修改系统配置，也不是正式runtime或实体硬件验收。

### RI18 macOS 环境前置（只读，不代表构建验收）

收口顺序按用户“全部功能实现后再全量”统一：先完成RI00–RI17实现、focused专项及独立审查；RI16／RI17必须依赖最终发布runtime的设备／键鼠证据在RI18构建后取得，相关checkbox保留未勾选，所有原定门禁仍须通过。修正此前“RI18前置包含尚未生成的发布产物证据”的顺序循环，不提前重复全量或发布、不改变验收范围。

2026-09-30 只读 SSH 预检：`mbp` 的连接在5秒上限超时，按已配置的 `mbp-lan` 有界追加一次后成功。实际系统 Darwin／x86_64／macOS 14.8.4，既有 Temurin 21.0.10+7 可执行。记录中的旧同步隔离 checkout 存在，HEAD `d9999a3e5807b27242c2b239233fc029969d4495`，工作树有未提交改动；本轮未修改、清理、构建或部署该目录。RI18 须另建本任务隔离 checkout，开始前再次核对身份、进程和配置。连通及 JDK 证据不能代替 macOS 构建、UI、钥匙串或正式运行验收。

Android只读预检 `python scripts/build-android.py check --signing` 返回0／signing verified，SDK36、build-tools36.0.0及JDK21.0.11可用，原发布证书身份验证通过。未分配候选版本、未构建／安装APK，不能作为最终候选验收；收口时在冻结源码上重新按规范核对。

RI18构建入口补核（只读）：当前build-desktop.sh的build-only会分配BUILD＋1并跳过JVM tests；macOS run_macos按MIHON_MACOS_DIST_ROOT／MIHON_MACOS_DEPLOY_DIR构建和复制，其默认部署目标是已有/Applications/Mihon Desktop.app，部署前会删除该目标。最终Mac验收必须显式指定本轮隔离路径，核实真实目标不与旧应用／旧checkout重叠，不能沿默认覆盖。Windows统一入口委托build-windows.ps1，其构建／runtime／extension验收后输出Final unpacked EXE。当前只是读取脚本，未分配版本、未执行构建或部署；最终同冻结行为diff完整JVM证据先齐，再使用build-only并记录各实际版本／来源，不能假称版本或运行已通过。

RI18协调器补核（仍为只读）：实际当前build-windows.ps1令$Gradle指向gradlew.bat并直接调用，build-desktop.sh的run_macos也直接调用gradlew；两脚本及两wrapper未发现gradle-coordinator调用。AGENTS所述统一入口已协调不能替代当前源码事实。最终须按届时真实脚本判断：已有内部协调时不嵌套；当前这种直接调用可用外层协调器保护整个正式build脚本，继续经统一构建入口，不改为直接Gradle部署。test-desktop中ExampleE2ETest是注释示例，ReaderPartialDownloadE2ETest是外部校验器契约，不代表已启动真实应用；完整客户端测试与正式产物Test Mode分别记录，不能混充运行验收。这里只读，无版本分配／测试／构建执行。

## 未完成与限制

RI00／RI01 的实现、focused、唯一独立审查及必要修复复审已完成，代码、测试、索引修复和必要 checkoff 随本功能批同一提交。首簇提交为 `14a8ce15695cf69ab2e468b825e88ec91641e08a`。用户继续要求完成剩余 roadmap，现复用原实施代理串行推进后续批次，每批按既有审查与必要修复复审流程执行；完整验证仍只在 RI18。RI02 实现、focused、唯一修复复审和格式／索引检查均通过，必要 checkoff 与代码／测试在本批同一提交；RI03 实现、唯一修复复审、140项有效focused证据、格式与索引检查已闭合，必要checkoff随本批同一提交；RI04实现、140项有效focused、唯一独立初审及必要修正核验、格式及索引已闭合，必要checkoff随本批同一提交；RI05实现、唯一独立初审及必要修复核验、132项去重focused证据、格式及索引检查已闭合，必要checkoff随本批同一提交；RI06实现、同一独立初审及必要修正核验、122项有效去重focused证据、scoped格式与273条当前索引检查已闭合，实施代理已回执停写、无存活Gradle进程，必要checkoff随本批同一提交；RI07实现、同一独立初审与必要修正核验、169项去重focused证据、scoped格式及279条当前索引已闭合，必要checkoff与代码／测试在本批同一提交；RI08实现、同一独立初审及必要修正核验、159项有效去重focused、scoped格式和287条当前索引已闭合，必要checkoff随本批同一提交；RI09实现、同一独立初审与必要修正核验、65项有效去重focused、scoped格式及293条当前索引已闭合，必要checkoff随本批同一提交；RI10实现、同一独立初审及必要修正核验、118项有效去重focused、35Kotlin／2XML scoped格式与306条当前索引已闭合，必要checkoff随本批同一提交；RI11–RI18 未完成；最终全量、正式构建及运行验收尚未执行。本轮没有 Android正式候选／macOS正式构建、真实鼠标／触控板／硬件条件验收，没有交付 EXE/APK，没有把 HTML 勾选更新为 native 完成。
