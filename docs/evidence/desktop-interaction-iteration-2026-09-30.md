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

## 未完成与限制

RI00／RI01 的实现、focused、唯一独立审查及必要修复复审已完成，代码、测试、索引修复和必要 checkoff 随本功能批同一提交。下一项为 RI02；完整计划的逐批追加审查预算仍另有待答请求。RI02–RI18 尚未实施；最终全量、正式构建及运行验收尚未执行。本轮没有 Android／macOS 构建、真实鼠标／触控板／硬件条件验收，没有交付 EXE/APK，没有把 HTML 勾选更新为 native 完成。
