# Windows Reader P0 偏好后端校准

日期：2026-11-09。测试数据来自归档 `D:/Codex/archives/mihon-reader-startup-20261108/d10-evidence.zip` 的 `FC-C-reread1` profile，入口映射见同目录 `manifest.json`。原始 `values.properties` 位于 `d10-20261107/FC-C-reread1/profile/preferences/user/e1314f872f26bfe4b8c97e39ae04a40b0bc9085b0e50856a4e21e73809e21c60/values.properties`，共 2,107 字节、17 个持久键。测试内 fixture 的 SHA-256 为 `CC93C347804F5202155E0E8387A5A3035FE1F616CDE9D153430842C747606F82`。

## 场景和隔离

每个探针子 JVM 都重新构造同一生产 `ReaderPreferences`，并调用生产 `initialReaderState` 及 `rereadFirstCompositionState`。Reader 场景固定为 chapter `906004`、187 页、第5页（索引4）、已下载、单页 RTL；fixture 的持久 Reader 值为 RTL、双页关闭、自动拆页关闭、自动 spread 匹配关闭、预取 `OFF`。未保存的裁边、颜色滤镜等值继续由生产默认值解析。

恢复出的17个键名为：`__APP_STATE_creator_global_frequency_migrated`、`__APP_STATE_desktop_window_geometry`、`__APP_STATE_fork_update_WINDOWS_TESTING`、`__APP_STATE_library_update_scope_requires_choice`、`__APP_STATE_sync_schedule_anchor`、`auto_update_metadata`、`creator_discovery_frequency`、`library_interaction_parity_migration_version`、`library_update_manga_restriction`、`library_update_restriction`、`local_source_root_dir`、`network_mode`、`reader_auto_split_pages`、`reader_auto_spread_matching`、`reader_dual_page`、`reader_next_chapter_prefetch`、`reader_reading_mode`。除5个 `reader_*` 键外，其余设置原样种入私有节点，但本校准未读取数据库或访问其路径值。

计时用四个新 JVM，顺序为文件 / 注册表 / 注册表 / 文件；每个 JVM 在同一进程里记录首次和重复的 `initialReaderState`，以及两次现有复读检查。偏好读取计数另在两个全新 JVM 执行，不计入上述耗时。文件后端每个进程都设置实际 `IsolatedDesktopPreferencesFactory` 并核实 root 类型；Windows 后端不设置 factory override，并核实真实 root 为 JDK `WindowsPreferences`。两种后端都通过 `ReaderPreferences(store, legacy)` 注入本次唯一随机的私有节点，未访问普通 `/mihon` 节点。

私有注册表节点和文件 profile 均在 `finally` 中移除；子进程检查节点已消失，文件 profile 也不存在。子 JVM 使用 headless 模式并把合并后的 stdout/stderr 重定向到独立日志文件；ProcessBuilder 启动方式沿用项目 `DesktopTestProfileTest` 的既有做法。

## 计时结果

单次值以毫秒显示到 3 位小数。构造阶段只测 `ReaderPreferences` 构造；`DesktopPreferenceStore`、profile 准备、JVM 启动和设置写入均在计时外。计时轮不启用读取计数。

| 顺序 | 子进程 PID | 后端 / 实际 root | ReaderPreferences 构造 | initialState 首次 | initialState 同进程重复 | 复读首次 | 复读同进程重复 |
|---:|---:|---|---:|---:|---:|---:|---:|
| 1 | 10316 | 文件 / `mihon.desktop.platform.IsolatedDesktopPreferences` | 65.303 | 11.589 | 2.415 | 5.393 | 1.993 |
| 2 | 30708 | 注册表 / `java.util.prefs.WindowsPreferences` | 58.764 | 7.508 | 0.421 | 5.512 | 0.292 |
| 3 | 30272 | 注册表 / `java.util.prefs.WindowsPreferences` | 63.601 | 7.262 | 0.417 | 5.548 | 0.337 |
| 4 | 21776 | 文件 / `mihon.desktop.platform.IsolatedDesktopPreferences` | 64.359 | 13.200 | 3.370 | 6.028 | 2.164 |

本轮范围内的区间如下；只有两份独立进程样本，不解释为稳定分布或端到端启动提速。

| 后端 | 构造区间 | initialState 首次 | initialState 重复 | 复读首次 | 复读重复 |
|---|---:|---:|---:|---:|---:|
| 文件 | 64.359–65.303 ms | 11.589–13.200 ms | 2.415–3.370 ms | 5.393–6.028 ms | 1.993–2.164 ms |
| 注册表 | 58.764–63.601 ms | 7.262–7.508 ms | 0.417–0.421 ms | 5.512–5.548 ms | 0.292–0.337 ms |

两种后端的四次生产状态均一致：chapter `906004` / 页索引 4，RTL、非自动布局、非双页、无拆页与 spread，背景 `BLACK`，scale `FIT_SCREEN`，颜色滤镜关闭且 alpha 为 128；连续两次初始投影和两次复读均相同并接受。样本说明隔离文件后端会影响真实设置读取路径的时延，但这些微型组件值不能从旧端到端采样中直接相减。

## 真实读取计数与线程

计数使用单独子 JVM，通过包装 `Preferences` SPI 逐个记录 key 和来源，包装只用于计数轮；耗时轮不安装包装。文件与注册表的读取数和来源一致：子进程 PID 分别为 30,432 和 21,356。

| 调用 | 次数 | 来源与线程 |
|---|---:|---|
| `ReaderPreferences` 构造 | 48 | 主线程 `main`；`store` 29 次、私有 `legacy` 19 次 |
| 首次 `initialReaderState` | 25 | `store` 25 次；`AWT-EventQueue-0` |
| 重复 `initialReaderState` | 25 | `store` 25 次；`AWT-EventQueue-0` |
| 首次 `rereadFirstCompositionState` | 18 | `store` 18 次；`AWT-EventQueue-0` |
| 重复 `rereadFirstCompositionState` | 18 | `store` 18 次；`AWT-EventQueue-0` |

构造阶段的 `legacy` 读取为 19 项旧键各一次：`backgroundTheme`、`colorFilterAlpha`、`colorFilterB`、`colorFilterBrightness`、`colorFilterEnabled`、`colorFilterG`、`colorFilterGrayscale`、`colorFilterInvert`、`colorFilterR`、`cropBordersPager`、`cropBordersWebtoon`、`navigationMode`、`scaleType`、`skipDuplicateChapters`、`skipFilteredChapters`、`skipReadChapters`、`webtoonAutoScroll`、`webtoonAutoScrollSpeed`、`webtoonSidePadding`。

构造阶段 `store` 读取中有三项读取两次或更多：`reader_color_filter_enabled=2`、`reader_dual_page=2`、`reader_reading_mode=3`；其余 22 个当前 reader store key 各一次，包含 fixture 已保存的五项 Reader 键以及由 `ReaderPreferences` 默认项初始化的其余配置键。计数日志保留构造、首次投影和首次复读的完整 `store:key=count` / `legacy:key=count` 列表。重复投影与复读也实际计数，父测试校验其逐 key 总和和 store/legacy 来源，日志保留重复阶段总次数和线程；但重复阶段的完整 per-key 明细没有写入 XML。

## 验证记录

测试类：`mihon.desktop.ui.reader.ReaderPreferencesBackendCalibrationTest`。source-p0123-focused8 中的 P0 suite 为 1/1 通过，XML 时间为 `2026-11-09T06:45:18.743Z`，host 为 `G-BURST`；该合并 focused 批次为 135 项、1 项失败，失败项是 P1 的 `invalid saved page enters the full recovery reader`，不是 P0。六个子进程 PID（4 个计时、2 个计数）各不相同，parent PID 均为 `30028`；进程条件为 Windows 11、Eclipse Adoptium JDK `21.0.11`、headless，文件/注册表 root 与 factory 全名、私有 node 和 mode 均在 XML `system-out` 保存。测试也断言了 profile/node 清理。文件 profile 的路径由绝对临时目录传入；XML 经 `Properties.load` 读取时反斜线会按 properties 转义解释，故日志中的 profile 字符串不宜反向作为文件系统路径使用，具体隔离身份可用 process PID + node UUID 追溯。

该次 Gradle 调用限定在受影响的 reader focused 类：`ReaderFirstCompositionIntegrationTest`、`ReaderPresentationTransformOwnerTest`、`ReaderFullscreenTest`、`ReaderPreferencesBackendCalibrationTest`、`presentation.ReaderFirstCompositionTest`、`StartupSingleViewportTest`、`DesktopReaderChapterTransitionIntegrationTest`、`DesktopReaderPresentationImageOwnerTest`、`DesktopReaderAnimationPresentationWiringTest`、`SharedDualPageNativeStablePresentationTest`、`ReaderFullscreenFramePresentationTest`；未执行模块全量或仓库全量。

P0 仅做后端组件校准，不触碰数据库，不改变 production。结果校准了新采样必须固定明确后端；无法据此判断普通用户端到端的绝对收益，也不能把本表区间视为稳定性能保证。
