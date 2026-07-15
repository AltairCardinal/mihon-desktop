# 源与扩展权威实现基线

日期：2026-07-15
Task base：`d77ef4d2b63e00d8abe3e2da85b6ef4e4351ae58`

## 目的与边界

本基线只固定当前 Android 权威调用链、Desktop 对应实现、已存在 fixture 与 Desktop 产品保护网，为后续共享模型提供输入；本批不创建共享生产模型，也不改变用户界面或安装行为。

复用结论如下：

1. `SourceMangaSearchService`、`ExtensionRepoRepository`、`source-api` 类型、网络客户端、Desktop APK→JAR、原子替换、扩展详情与文件工具可以直接复用。
2. 源分页/错误、扩展目录/版本/安全/事务状态应从两端重复实现中抽取，供 Android 与 Desktop 共用；本批仅记录边界，不提前实现。
3. 新链路应追加到现有源与扩展入口，不建立第二套 Screen 或安装入口。
4. Android PackageManager/PackageInstaller 与 Desktop 目录、ClassLoader、APK→JAR、系统文件管理器属于真实平台差异，必须保留平台 adapter；把它们强行共享会丢失签名、包管理或 JVM 文件语义，也会破坏现有用户入口。

## 权威类映射

| 能力 | Android 权威实现 | Desktop 当前对应 | 直接复用 | 后续必须抽取 | 必须平台适配 | 现有 fixture / 保护测试 |
| --- | --- | --- | --- | --- | --- | --- |
| 扩展目录与更新检查 | `eu.kanade.tachiyomi.extension.api.ExtensionApi` | `mihon.desktop.extension.DesktopExtensionApi` | `ExtensionRepoRepository`、OkHttp、index JSON | 多仓库部分失败、版本判断、目录结果与错误 | 下载产物的最终安装动作 | Android repository index；Desktop `ExtensionIconLoadingTest`、`KeiyoushiChineseCompatibilityTest` |
| 扩展生命周期 | `eu.kanade.tachiyomi.extension.ExtensionManager` | `mihon.desktop.extension.DesktopExtensionManager`，UI 状态暂存于 `ExtensionListScreen` | `source-api`、扩展仓库领域层 | Installed/Available/Installing/Failed、信任、更新与回滚事务 | Android `ExtensionInstaller`；Desktop 文件提交与 reload | `DesktopExtensionManagerTest`、`ExtensionArtifactReplacementTest` |
| 扩展发现与加载 | `eu.kanade.tachiyomi.extension.util.ExtensionLoader` | `mihon.desktop.extension.DesktopExtensionLoader` | `Source`/`CatalogueSource` 合约 | 统一的加载结果、兼容错误与诊断 | Android PackageManager、签名与私有 APK；Desktop 目录、`ExtensionClassLoader`、ServiceLoader、compat 层 | `DesktopExtensionLoaderTest`、`ExtensionCompatibilityTest`、`KeiyoushiChineseCompatibilityTest` |
| 源列表 | `eu.kanade.tachiyomi.ui.browse.source.SourcesScreenModel` | `mihon.desktop.ui.browse.BrowseTab` / `BrowseSourceListScreen` + `DesktopSourceManager` | 源模型与启用源查询能力 | 列表状态、语言分组、启用/固定动作与错误 | Voyager 导航和宽屏 Compose 布局 | Android ScreenModel 流；Desktop `ScreenInstantiationSmokeTest` |
| 全局搜索 | `eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreenModel`（经 `SearchScreenModel`） | `mihon.desktop.ui.browse.GlobalSearchScreen` | `tachiyomi.domain.source.service.SourceMangaSearchService` | 查询、并发结果、空状态、分页与统一 AppError | 两端 Compose 呈现、导航与键鼠交互 | `SourceMangaSearchServiceTest`；Desktop 当前 Screen 路径 |
| 扩展列表操作 | `eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel` | `mihon.desktop.ui.extension.ExtensionListScreen` | 搜索/语言筛选的纯规则可迁入共享层 | 刷新、安装、更新、取消、卸载、信任与逐项反馈 | Android 安装会话；Desktop JAR/APK 文件流程与宽屏布局 | `ExtensionSearchTest`、`ExtensionLanguageFilterTest`、本产品基线测试 |
| 扩展详情与文件定位 | Android `ExtensionDetailsScreenModel` / `ExtensionDetailsScreen` | Desktop `ExtensionDetailsScreen` + `DesktopDirectoryOpener` | 扩展详情领域字段 | 详情加载、缺失、卸载结果 | Android Intent/包页面；Desktop 系统文件管理器 | `DesktopExtensionProductBaselineTest`、`ScreenInstantiationSmokeTest` |

## 当前调用链

### Android 扩展

`ExtensionsScreenModel` → `ExtensionManager.findAvailableExtensions/installExtension/updateExtension/uninstallExtension` → `ExtensionApi`（目录与 APK URL）或 `ExtensionInstaller`（安装会话）→ `ExtensionLoader`（PackageManager、签名、私有扩展选择）→ 已安装 `Source` 发布给源管理链路。

### Desktop 扩展

`ExtensionListScreen` → `DesktopExtensionApi.findAvailableExtensions/installExtension` → 下载 JAR/APK → `ApkToJarConverter`（APK 路径）→ `replaceExtensionArtifact` + sidecar metadata → `DesktopExtensionManager.reloadAll` → `DesktopExtensionLoader` / `ExtensionClassLoader` / ServiceLoader。已安装项由 `ExtensionDetailsScreen` 打开；“Open folder” 委托 `DesktopDirectoryOpener`。

### Android 源列表与搜索

`SourcesScreenModel` 从启用源用例收集列表并处理启用/固定；`GlobalSearchScreenModel` 继承 `SearchScreenModel`，选取启用的 `CatalogueSource`、并发搜索并发布逐源结果。

### Desktop 源列表与搜索

`BrowseTab` 的嵌套 Navigator 打开 `BrowseSourceListScreen`；列表从 `DesktopSourceManager` 读取并导航到 `SourceBrowseScreen`。`GlobalSearchScreen` 当前直接并发调用各 `CatalogueSource`；后续必须追加到现有 `SourceMangaSearchService` 链路并抽取共享状态，不另建搜索服务。

## Fixture 清单与可信度

| Fixture | 类型/版本 | 来源 | 当前用途 | 边界 |
| --- | --- | --- | --- | --- |
| `minimalDexBytes()` / `MINIMAL_DEX_BASE64` | 最小 DEX v035 | `ApkToJarConverterTest` 与 `DesktopExtensionProductBaselineTest` | 确认 production APK→JAR 转换仍可产出 JVM JAR | 合成结构 fixture，只保护转换机械链路，不证明第三方扩展兼容 |
| `MinimalTestSource` ServiceLoader JAR | repo test classpath | `ExtensionCompatibilityTest` | 确认 production loader 能发现 `Source` | 确定性 JVM fixture，不代表 Android API 使用面 |
| `eu.kanade.tachiyomi.extension.zh.manhuagui@1.4.28` | Keiyoushi APK | `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`，2026-07-15 记录 | `KeiyoushiChineseCompatibilityTest` 精确校验 package/version，下载并转换后调用 production `DesktopExtensionLoader`（ServiceLoader fast path + manifest sidecar fallback）；当前因缺少 `android.app.Application` compat 绑定而不暴露 `Source` | `unsupported` 网络 integration fixture；只有 Task 4 提供真实 Application adapter 且同一固定 fixture 能由 loader 暴露 `Source` 后才能转为 `required` |
| 本地 ManHuaGui 构建产物 | 版本由本地构建决定 | `/tmp/extensions-desktop/extensions-source/.../build/libs` | `ManhuaguiLoadTest` 的开发者诊断 | 非 CI 权威，不得单独作为新增 compat stub 的依据 |

## Compat evidence schema

资源：`app-desktop/src/test/resources/extensions/compat-evidence.json`。

每项字段固定为：

- `symbol`：真实被调用的完整 API 符号；
- `fixture`：`path-or-package@version`，必须能追溯到测试输入；
- `test`：仓库内触发加载/调用的保护测试；
- `status`：仅 `required` 或 `unsupported`；
- `removalCondition`：何时可以删除该兼容面或明确不支持结果。

首批只登记现有真实 Keiyoushi fixture 已触达的 `eu.kanade.tachiyomi.source.Source` 边界。现有 `AndroidCompatPhase*Test` 只直接调用自造 stub，不能证明第三方扩展使用，因此没有把 `android.*` / `androidx.*` 的未来可能需求预填进清单。后续审计必须为每个 compat public symbol 补真实 fixture 调用证据，或记录 `unsupported` 并删除无调用实现。

## Desktop 产品保护网与用户行为

- 用户入口：Browse → Sources / Global Search；Browse → Extensions → Installed/Available → Extension details。
- 扩展安装仍支持预编译 JAR 和 APK→JAR；替换后 reload，失败结果由现有安装 UI 显示。
- 详情页保留文件路径、大小、摘要、仓库信息、“Open folder”、源浏览/设置、清 Cookie 和卸载确认。
- 空状态、加载与错误沿用当前页面行为；本批不改变文案或状态模型。
- 功能边界：APK→JAR 只保证可转换的 DEX；Android-only AAR/QuickJS 或缺少真实调用证据的 API 不承诺支持。文件工具只负责 Desktop 目录 side effect，不进入共享业务层。
