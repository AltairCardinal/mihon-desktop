# Mihon Desktop 功能追赶路线图

## Context

Mihon Desktop 当前版本 `0.11.0`，已完成阶段 8–22（扩展生态、漫画笔记、源迁移、书库增强、更新过滤、下载增强、JS 引擎、阅读器高级功能、图像缩放、跳过已读章节、每部漫画阅读模式、批量分类管理、自动更新过滤、并行下载、自动备份、宽图分割等）。

**追踪服务已于 0.11.x 阶段移除**：经过分析，9 个追踪服务（MAL/AniList/Kitsu 等）无法跨语言识别漫画，且强依赖 Android OAuth/WebView 流程，桌面端移植价值有限。已删除所有 track/ 目录、TrackingSection UI 及相关 DI 绑定。

本路线图阶段 8–22 的功能已全部完成。当前进入架构演进阶段，重点解决与竞品的差距（见 `COMPARISON.md` 第 8–12 节分析）。

---

## 阶段 8 — 扩展生态系统（0.8.x）

> **为什么最优先**：没有扩展安装能力，用户只能手动放 JAR，极大限制可用性。

| 版本 | 功能 | 说明 |
|------|------|------|
| ✅ 8.0 | 扩展仓库管理 | 注册跨平台 use case（`GetExtensionRepo`/`CreateExtensionRepo`/`DeleteExtensionRepo`），Settings > Browse 添加仓库 URL 管理 UI |
| ✅ 8.1 | 扩展浏览与安装/更新 | 实现 `DesktopExtensionApi` 从仓库 `index.min.json` 获取列表；下载 JAR 到 `~/.mihon/extensions/`；ExtensionListScreen 增加"可用"标签页 |
| ✅ 8.2 | 源设置 (Source Preferences) | 实现 JVM 端 `PreferenceScreen` actual class，Switch/List/EditText/CheckBox/MultiSelect 全类型，入口在 Installed 标签页各源的设置按钮 |

**关键文件**：
- `data/src/commonMain/.../ExtensionRepoRepositoryImpl.kt`（可直接复用）
- `source-api/src/jvmMain/.../PreferenceScreen.kt`（需从空存根实现）
- `app-desktop/.../extension/DesktopExtensionManager.kt`（需扩展）
- `app-desktop/.../di/DesktopAppModule.kt`（注册新 use case）

**扩展构建策略（进行中）**：
- `extensions-source` 不能直接按 Android 方式全量编 JVM，必须在构建仓库中提供 `android-compat`、JVM 版 `common.gradle`、`lib-android`/`lib-multisrc` 约定插件和 `Preference`/`SharedPreferences` 替身
- CI 不能采用“全量任务一次性执行”的方式；应改为“按扩展模块选择性 include + 单模块隔离构建”，避免某个 Android-only 依赖阻断全部扩展发布
- 仓库发布策略必须允许**部分成功**：成功编译的扩展先写入 `index.min.json` 与 `apk/*.jar`，失败模块单独输出 `failed_modules.txt`，后续逐类消灭 Android/AAR/JS 依赖
- 优先清理三类阻塞项：Android 生命周期任务引用（如 `preBuild`）、本地 `lib/*` Android 变体、第三方 AAR 依赖（需替换为 JVM 依赖、降级为 `compileOnly` 或单独做 shim）
- 当前排障结论：不能继续把 `extensions-lib` / `quickjs-android` 当作可直接在 JVM 上消费的 AAR。下一步应改为对齐 Mihon Desktop 主仓库里的 `source-api` 与可复用网络 API，构建一个桌面可编译的 extension API 层；QuickJS 相关扩展继续单独标记为未支持或使用 JVM 替代实现

**状态标记**：
- `[已完成]` 修复 Actions 假成功、空 `repo` 发布、0 产物不报错
- `[已完成]` 改为按模块隔离构建，并允许部分成功发布
- `[已完成]` 清理共享 shim 首轮阻塞：`jar` 任务入口、`android-compat` JVM 版本、Kotlin stdlib、`Preference` stub 冲突
- `[已完成]` 建立首版 `desktop-api` 模块，对齐 Mihon Desktop 的 `source-api` / 最小 network API，并接入 `patch.sh`、`settings-jvm.gradle.kts`、`common/core/lib-android/lib-multisrc` JVM patch
- `[已完成]` 本地打通最小纯 HTTP 构建链：`desktop-api -> core -> lib-multisrc:keyoapp -> src:en:aeinscans`，已产出 `eu.kanade.tachiyomi.extension.en.aeinscans-v1.4.1.jar`
- `[进行中]` 继续补 Android shim 与局部库依赖，让更多 `lib/*` / `lib-multisrc/*` 能在 JVM 下编译
- `[下一步]` 将当前 `desktop-api` / shim 变更提交到 `extensions-desktop`，重新触发 Actions，并把失败模块按 `QuickJS / 第三方 AAR / Android shim` 三类输出

**实施文档**：
- [Extensions Desktop API 迁移计划](./extensions_desktop_api_migration.md)

---

## 阶段 9 — 追踪与笔记（0.9.x）

| 版本 | 功能 | 说明 |
|------|------|------|
| ✅ 9.4 | 漫画笔记 | 注册 `UpdateMangaNotes`，漫画详情页添加 Notes 按钮 → `DesktopMangaNotesScreen` |
| ~~9.0–9.3~~ | ~~追踪服务~~ | **已移除**：MAL/AniList/Kitsu/Shikimori/Bangumi/MangaUpdates 追踪服务无法跨语言识别漫画，桌面端移植价值有限，已全部删除 |

---

## 阶段 10 — 源迁移与书库增强（0.10.x）

| 版本 | 功能 | 说明 |
|------|------|------|
| ✅ 10.0 | 源迁移核心 | `DesktopSourceRepository` 实现 `SourceRepository` 接口；`DesktopMigrateMangaUseCase` 复制章节/分类/笔记 |
| ✅ 10.1 | 迁移 UI | `MigrationSourceScreen`（源列表）→ `MigrationMangaScreen`（漫画列表）→ `MigrationSearchScreen`（跨源搜索 + 确认弹窗），入口在 More 标签页 |
| ✅ 10.2 | 重复漫画检测 | 注册 `GetDuplicateLibraryManga`，`SourceMangaDetailScreen` 添加到书库时弹窗提示 |
| ✅ 10.3 | 书库显示增强 | 随机漫画（Shuffle 按钮）；章节多选批量操作；更新进度圆形指示器 |

**关键文件**：
- `data/src/androidMain/.../SourceRepositoryImpl.kt`（需提供 JVM 版本）
- `app/src/main/java/.../ui/browse/migration/`（Android 迁移 UI 参考）

---

## 阶段 11 — 更新与即将发布（0.11.x）

| 版本 | 功能 | 说明 |
|------|------|------|
| ✅ 11.0 | 更新过滤 | Updates Tab 过滤对话框：未读/已下载/已开始/已书签/扫描组，持久化到 `UpdatesPreferences`，日期分组显示 |
| ✅ 11.1 | 即将发布日历 | 注册 `GetUpcomingManga`，新增 `UpcomingScreen`，入口在 More 标签页 |
| ✅ 11.2 | 章节扫描组过滤 | 漫画详情章节列表按 scanlator 过滤，通过 `GetExcludedScanlators`/`SetExcludedScanlators` 持久化 |
| ✅ 11.3 | 更新时间显示 | Updates Tab 显示上次更新时间：今天→HH:mm，昨天→Yesterday，7天内→星期缩写，更早→MMM dd |

**关键文件**：
- `domain/src/commonMain/.../upcoming/interactor/GetUpcomingManga.kt`（可直接复用）

---

## 阶段 12 — 下载增强与兼容性（0.12.x）

| 版本 | 功能 | 说明 |
|------|------|------|
| ✅ 12.0 | 下载队列拖拽排序 | 集成 `sh.calvin.reorderable`，QUEUED 条目可拖拽重排；`DesktopDownloadManager.reorderItem()` |
| ✅ 12.1 | JavaScript 引擎 | 集成 Rhino（`org.mozilla:rhino`）；`DesktopJsEngine` 提供 `evaluate()`；含 btoa/atob 填充；注册到 DI |
| 12.2 | Cloudflare 绕过 | headless browser 或外部工具方案 |

---

## 不纳入桌面版的功能

| 功能 | 原因 |
|------|------|
| 安全锁定（生物识别/PIN） | 桌面 OS 自带锁屏 |
| 入门引导 | 桌面用户技术水平较高 |
| Deep Link | 桌面无标准化机制 |
| E-Ink 模式 | 桌面 E-Ink 设备极少 |
| WebView 清理 | Desktop 无 WebView |
| 硬件位图阈值 | Android 图形管线特有 |
| Crashlytics/Analytics 隐私设置 | Desktop 无 Firebase |

---

## 阶段 13 — 追踪服务补全（已移除）

> **已移除**：追踪服务无法跨语言/跨翻译识别漫画，且强依赖 Android OAuth/WebView，桌面端移植价值有限。所有追踪功能（MAL/AniList/Kitsu/Shikimori/Bangumi/MangaUpdates/Komga/Kavita/Suwayomi）已从桌面版完全删除。

---

## 阶段 14 — 阅读器高级功能（0.14.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 14.0 | **多种点击导航模式** | 5 种模式（RightLeft/L/Kindle/Edge/Disabled），2D 坐标区域划分，ReaderSettings 选择器 |
| ✅ 14.1 | **双页分割** | 宽图检测（width > height），自动拆分为左右页，RTL 反序 |
| ✅ 14.2 | **Webtoon 自动滚动** | 定时器驱动滚动，速度可调，到底自动切章 |

---

## 阶段 15 — 设置与数据管理（0.15.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 15.0 | **NSFW 源过滤** | Preference + 扩展/源列表过滤，默认显示 |
| ✅ 15.1 | **高级设置页** | 清除 Cookies、清除网络缓存、缓存大小显示 |
| ✅ 15.2 | **漫画封面自定义** | 文件选择器 → 保存到 `~/.mihon/covers/` → 加载优先级 |

---

## 阶段 16 — 应用内通知（0.16.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 16.0 | **通知基础设施** | Compose SnackbarHost + 通知队列，库更新/下载完成/备份完成通知 |

---

## 阶段 17 — 遗留与增强（0.17.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 17.0 | **分类拖拽排序** | 复用 `sh.calvin.reorderable`，后端 `reorder()` 已有 |
| ✅ 17.1 | **Cloudflare 绕过** | 手动 Cookie 导入 UI（cf_clearance）：高级设置页输入域名 + cookie 值，注入到 `DesktopCookieJar.addManual()` |

---

## 阅读器体验优化（0.9.8 ~ 0.9.9）

以下优化在阶段 9 实现过程中补充：

| 项目 | 实现说明 | 版本 |
|------|--------|------|
| **屏幕点击翻章** | 在首页/尾页边界点击翻页区域时触发章节切换（前一章/下一章），与键盘导航和底部栏按钮行为一致。支持 LTR/RTL 模式。 | 0.9.8 |
|   | - 实现 `PageNavAction` sealed interface（`ScrollTo` / `PrevChapter` / `NextChapter`） |  |
|   | - 提取纯函数 `tapLeftAction()`/`tapRightAction()` 用于页面边界检测 |  |
|   | - 在 `SinglePagePagerViewer` 和 `DualPagePagerViewer` 中添加 `onPrevChapter`/`onNextChapter` 回调 |  |
|   | - **修复 RTL 翻章方向**（0.9.9）：添加 `chapterNavForTapLeft()`/`chapterNavForTapRight()` 处理 RTL 物理方向与阅读方向的映射 |  |
| **渐进式页面加载** | 章节切换后，首页下载完成时立即显示阅读器，其余页在后台并行下载并逐页更新，大幅缩短首屏等待时间。 | 0.9.8 |
|   | - 改为使用 `Array<String?>` 槽位管理逐页下载状态 |  |
|   | - 在 `ZoomablePageBox` 中添加对空 URL 的处理：显示加载圈而非错误 |  |
|   | - 页面下载完成时实时更新 `resolvedUrls`，无需等待所有页面 |  |

---

---

## 阶段 18 — 阅读器图像显示增强（0.11.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 18.0 | **图像缩放类型** | `ScaleType` 枚举（FitScreen/FitWidth/FitHeight/Original/SmartFit），`ZoomablePageBox` 参数化，`ReaderPreferences` 持久化，Display Tab 添加选项 |
| ✅ 18.1 | **跳过已读章节** | `ReaderChapterRef.isRead` 字段，`ReaderPreferences.skipReadChapters`，`ReaderNavigator` 跳过逻辑，General Tab 开关 |
| ✅ 18.2 | **单页模式宽图分割** | `VirtualPage` + `buildVirtualPageList()`，`SinglePagePagerViewer` 虚拟页映射，`DesktopReaderScreen` 协调索引转换，Split 复选框移出 isDualPage 门控 |

---

## 阶段 19 — 全局搜索与源语言过滤（0.12.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 19.0 | **全局跨源搜索** | `GlobalSearchScreen`：并行查询所有 `CatalogueSource`，按源分组展示结果，支持 Loading/Error/Empty 状态 |
| ✅ 19.1 | **源语言过滤** | `DesktopBrowsePreferences.enabledLanguages`，Browse Tab 按语言过滤源列表，设置 UI |

---

## 阶段 20 — 每部漫画阅读模式（0.13.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 20.0 | **每部漫画阅读模式覆盖** | `readingModeFromViewerFlags()`，`MangaDetailScreen` 添加阅读模式选择器（Default/LTR/RTL/Webtoon），`DesktopReaderScreen.mangaViewerFlags` 参数覆盖全局默认 |

---

## 阶段 21 — 书库管理补全（0.14.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 21.0 | **批量分类管理** | `SelectionActionBar` 添加"Categories"按钮，`BatchCategoryDialog` 复选框对话框，批量调用 `SetMangaCategories` |
| ✅ 21.1 | **自动更新分类过滤** | `filterMangaForUpdate()`，`DesktopAppPreferences.updateCategoryExcludes`，`LibraryUpdateScheduler` 跳过排除分类，`LibrarySettingsScreen` UI |

---

## 阶段 22 — 下载与备份增强（0.15.x）

| 版本 | 功能 | 技术要点 |
|------|------|---------|
| ✅ 22.0 | **并行下载限制** | `DesktopDownloadPreferences.parallelDownloadLimit`（1–5），`DesktopDownloadManager` Semaphore 限制并发，`DownloadSettingsScreen` 单选项 |
| ✅ 22.1 | **自动备份** | `AutoBackupScheduler`（协程定时器），`AutoBackupInterval` 枚举，`pruneOldBackups()` 清理旧备份，`BackupSettingsScreen` 频率 + 最大数量 UI |

---

## 不纳入桌面版的 Android 功能

| 功能 | 原因 |
|------|------|
| 追踪服务（MAL/AniList 等） | 无法跨语言识别漫画，强依赖 Android OAuth/WebView |
| 安全锁定（生物识别/PIN） | 桌面 OS 自带锁屏 |
| E-Ink 模式 | 桌面 E-Ink 设备极少 |
| WiFi 限制下载 | 桌面端网络连接无此概念 |
| 音量键导航 | 桌面无音量键 |
| 设备方向锁定 | 桌面无旋转概念 |
| Shizuku 扩展安装 | Android 独有 |
| Material You / 动态颜色 | Android 系统级特性 |
| WebView 清理 | Desktop 无 WebView |
| 硬件位图阈值 | Android 图形管线特有 |
| Crashlytics/Analytics | Desktop 无 Firebase |

---

## 阶段 23 — 架构演进：DI 拆分（来源：COMPARISON.md §9.5）

> **动机**：`DesktopAppModule.kt` 已膨胀到 ~250 行，单一 `initDesktopDI()` 函数可读性下降，测试时无法部分替换。

| 版本 | 功能 | 说明 |
|------|------|------|
| 23.0 | **DI 按职责拆分** | 将 `initDesktopDI()` 拆分为 `initDataLayer()` / `initDomainLayer()` / `initNetworkLayer()` / `initExtensionLayer()` / `initUILayer()` 五个子函数，保留单一入口点 `initDesktopDI()` 统一调用 |

**关键文件**：
- `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`

**验收标准**：
- 每个子函数 <80 行
- 总入口函数 <20 行
- 所有现有测试仍通过
- 测试可以单独调用 `initDataLayer()` + `initDomainLayer()` 而不初始化 UI 层

---

## 阶段 24 — 测试基础设施（来源：COMPARISON.md §10.4）

> **动机**：Desktop 端缺少 CI 自动化和 UI 测试，bug 只能手动发现，已有测试可能在重构后静默失败。

| 版本 | 功能 | 优先级 | 说明 |
|------|------|--------|------|
| 24.0 | **GitHub Actions CI** | 高 | 添加 `.github/workflows/desktop-ci.yml`，push 触发 `./gradlew :app-desktop:jvmTest` + `spotlessCheck` |
| 24.1 | **Screen 实例化冒烟测试** | 高 | 为所有 Screen/Tab 添加 JVM 实例化测试（参见 CLAUDE.md Test Policy 第 4 节），验证构造函数不崩溃 |
| 24.2 | **Compose Desktop UI 测试** | 中 | 引入 `runComposeUiTest { }`，覆盖阅读器翻页、书库过滤、扩展安装 3 个核心流程 |
| 24.3 | **端到端集成测试** | 低 | MockWebServer 模拟源 API → 安装扩展 → 浏览漫画 → 阅读章节的完整链路 |

**关键文件**：
- `.github/workflows/desktop-ci.yml`（新建）
- `app-desktop/src/test/kotlin/mihon/desktop/ui/ScreenInstantiationTest.kt`（新建）
- `app-desktop/src/test/kotlin/mihon/desktop/ui/ComposeUiTest.kt`（新建，24.2）

---

## 阶段 25 — 状态管理重构（来源：COMPARISON.md §11.1）

> **动机**：状态逻辑和 UI 渲染混在 Composable 中，无法单独测试状态转换，膨胀的 Screen 难以维护。按"痛点驱动"原则，优先提取最膨胀的 3 个 Screen。

| 版本 | 功能 | 说明 |
|------|------|------|
| 25.0 | **ReaderScreenModel** | 从 `DesktopReaderScreen.Content()`（~400 行）提取页面加载、缩放状态、阅读进度、键盘处理到独立 ScreenModel，UI 层只负责渲染和事件分发 |
| 25.1 | **MangaDetailScreenModel** | 从 `MangaDetailScreen.Content()`（~350 行）提取漫画信息、章节列表、下载状态管理 |
| 25.2 | **LibraryScreenModel** | 从 `LibraryTab.Content()`（~300 行）提取搜索、过滤、选择、分类状态管理 |

**关键文件**：
- `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderScreenModel.kt`（新建）
- `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreenModel.kt`（新建）
- `app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryScreenModel.kt`（新建）
- 对应的 `*ScreenModelTest.kt` 测试文件

**验收标准**：
- 每个 Screen 的 `Content()` 函数 <150 行（纯 UI 渲染）
- 所有状态转换逻辑在 ScreenModel 中，可通过 JVM 单元测试验证
- 不改变用户可见行为

---

## 阶段 26 — 竞品差距补齐（来源：COMPARISON.md §12.3）

> **动机**：与 Suwayomi/Mangayomi/YACReader/OpenComic 等竞品对比，Desktop 在图片渲染、Cookie 持久化、扩展容错等方面落后。

| 版本 | 功能 | 说明 |
|------|------|------|
| ✅ 26.0 | **图片渲染性能优化** | Skia 原生解码 + 相邻页预加载 + 大图降采样 + 裁边复用已解码数据（详见 `EVOLUTION_PLAN.md` Phase E-G 和 `COMPETITIVE_GAP.md`） |
| ✅ 26.1 | **Cookie 持久化** | `DesktopCookieJar` 序列化到 `~/.mihon/cookies.json`，启动时恢复 |
| ✅ 26.2 | **扩展容错** | 所有扩展调用统一 `withTimeout` + `try-catch` 包装，超时/异常不冻结 UI |
| ✅ 26.3 | **扩展 APK 兼容** | 引入 dex2jar + Android compat 层，直接加载 keiyoushi APK（详见 `EVOLUTION_PLAN.md` Phase A-D） |

**实施文档**：
- [竞品差距详细实现方案](./COMPETITIVE_GAP.md)
- [演进计划：扩展 APK 兼容 + 阅读体验优化](./EVOLUTION_PLAN.md)

---

## 阶段 27 — APK 兼容性追平 Suwayomi（0.16.x）

> **动机**：当前 APK→JVM 扩展兼容率测试数据为 26.7%（12/45 中文源），但该数据因测试环境未初始化 DI 严重失真。Suwayomi 凭借 BytecodeEditor ASM 修补、manifest 精确类名发现和完整 Android stub 层实现接近 100% 兼容率。本阶段目标：修复测试方法论，补全关键缺失，将真实兼容率提升至 85%+。

| 版本 | 功能 | 说明 |
|------|------|------|
| 27.0 | **修复集成测试基线** | 写调用 `initDesktopDI()` 的集成测试套件，重测45个中文源 APK，得到真实成功率；隔离 Injekt 失败与真实兼容性失败 |
| 27.1 | **补全高频 Android stub** | 实现 `android.net.Uri`（URL 构建）、`android.util.Base64`（图片解码）、`android.os.AsyncTask`（老源异步）、`android.util.JsonReader/JsonWriter`、`android.graphics.Bitmap`（封面）等 20+ 高频缺失 API |
| 27.2 | **BytecodeEditor ASM 字节码修补** | 移植 Suwayomi 的 `BytecodeEditor`：dex2jar 转换后用 ASM 遍历所有类，将 `java/text/SimpleDateFormat` 等替换为兼容版本，解决日期解析差异 |
| 27.3 | **manifest 精确类名发现** | 从 APK `AndroidManifest.xml` 读取 `tachiyomi.extension.class` 元数据，替代当前的全类扫描，提升加载速度和准确性 |
| 27.4 | **SharedPreferences 磁盘持久化** | 将内存 Map 替换为持久化到 `~/.mihon/prefs/<packageName>.properties` 的实现，源设置重启后不丢失 |

**关键文件**：
- `app-desktop/src/main/kotlin/android/net/Uri.kt`（新建）
- `app-desktop/src/main/kotlin/android/util/Base64.kt`（新建）
- `app-desktop/src/main/kotlin/android/os/AsyncTask.kt`（新建）
- `app-desktop/src/main/kotlin/android/util/JsonReader.kt`（新建）
- `app-desktop/src/main/kotlin/android/graphics/Bitmap.kt`（新建）
- `app-desktop/src/main/kotlin/mihon/desktop/compat/BytecodeEditor.kt`（新建）
- `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionLoader.kt`（修改：manifest 解析）
- `app-desktop/src/main/kotlin/android/content/SharedPreferences.kt`（修改：磁盘持久化）

**验收标准**：
- 集成测试重测45个中文源，成功率 ≥ 55%（修复 DI 后预期基线）
- BytecodeEditor 实现后成功率 ≥ 75%
- manifest 发现 + stub 补全后成功率 ≥ 85%
- SharedPreferences 存储到磁盘，重启应用后源设置保留

---

## 技术风险

1. **源设置适配层（8.2）**：`PreferenceScreen` JVM 端为空存根，需完整实现 Compose preference 组件桥接，工作量大
2. **OAuth 登录（9.1）**：需用系统浏览器 + localhost 回调替代 Android WebView
3. **SourceRepository JVM 实现（10.0）**：目前仅 androidMain 有实现，需提供 jvmMain 版本
4. **JS 引擎（12.1）**：GraalJS 依赖重但 ES6+ 支持好，Rhino 轻量但 ES6+ 差

---

## 验证方式

每个阶段完成后：
1. 运行 `./scripts/build-desktop.sh stage`（新阶段）或 `feature`（功能迭代）
2. 所有新功能必须有对应测试（TDD 流程）
3. 按 CLAUDE.md 完成报告格式汇报
