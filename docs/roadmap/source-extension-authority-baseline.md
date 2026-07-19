# 源与扩展权威实现基线

日期：2026-07-18
原始 Mihon 权威：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`（下称“固定 main”）

## 目的、身份与判定规则

本基线为 source/extension slice 重建可审计的来源。只有固定 main 中的实现、测试与调用链可以证明原始 Mihon 语义；当前 `app/` 是 Desktop fork 的 Android consumer/迁移目标，`domain/common` 是迁移输出，二者均不能因路径或复用关系自证为权威。`app-desktop/` 是 JVM 产品和平台 adapter。

后续任务必须先以固定 main 对照行为，再将当前 fork 差异逐项分类为：必要平台 adapter、已有正确性/安全性/UX 证据的增强，或待偿还的 fork 技术债。未知差异保持待偿还状态，不得称为更好或直接回填为原始语义。本批只记录来源，不删除任何 Desktop 功能或改变产品行为。

## 原始兼容核心与跨平台安全增强

固定 main 可作为逐项对照的 extension 核心仅包括：版本和更新判定、已安装/可用/不受信任展示、安装/更新/取消/信任的基本操作，以及 Android 签名与 PackageInstaller 流程。当前 shared/consumer 中的仓库 fingerprint 连续性、声明及下载 SHA-256 连续性、提交前快照、rollback 和 runtime restore 是跨平台安全与可靠性增强；它们保留并由两端消费，但不宣称来自固定 main。验证结论必须表述为“原始兼容核心 + 跨平台安全增强”。

## 能力来源矩阵

每行均给出固定 main 的符号/调用链、已迁移 shared 实现（没有则明确为无）、当前 Android consumer、Desktop consumer/adapter 以及偏离分类。路径均为 production 路径，测试 fixture 只能保护对应链路，不能替代来源证明。

| ID / 能力 | 固定 main 原始符号与调用链 | 已迁移 shared 实现 | 当前 Android consumer | Desktop consumer / adapter | 偏离分类与原因 |
| --- | --- | --- | --- | --- | --- |
| 28 源列表 | `SourcesScreenModel` → enabled/language/pin source interactors | `SourceMangaSearchService` 不负责源列表分组；其余无 | `SourcesScreenModel` | `BrowseTab` → `BrowseSourceListScreen` → `DesktopSourceManager` | Desktop 宽屏与 Voyager 是 platform adapter；列表共享状态已迁移时必须单独对照 main。 |
| 29 单源浏览 | `BrowseSourceScreenModel` → `CatalogueSource` 分页 | `SourceMangaSearchService` | `BrowseSourceScreenModel` | `SourceBrowseScreen`、`DesktopSourceQueryCoordinators` | 共享查询服务是迁移输出；分页/空/错误语义必须回放固定 main，键鼠布局是 platform adapter。 |
| 30 全局搜索 | `GlobalSearchScreenModel` → `SearchScreenModel` → enabled `CatalogueSource` | `SourceMangaSearchService` | `GlobalSearchScreenModel` | `GlobalSearchScreen`、`DesktopSourceQueryCoordinators` | generation、统一错误和多源聚合是共享迁移/增强，需与固定 main 分开验证；Desktop 呈现为 adapter。 |
| 32 扩展仓库 | `ExtensionReposScreenModel` → extension-repo interactors | `ExtensionRepoRepository`、`ExtensionRepoService` | `ExtensionReposScreenModel` | `ExtensionRepoScreen`、`DesktopExtensionApi` | shared repo 模型是迁移输出；repo fingerprint 去重必须以固定 main 的仓库操作为核心并单列连续性增强。 |
| 33 扩展发现 | `ExtensionManager.findAvailableExtensions` → `ExtensionApi`；安装后 `ExtensionLoader` | `ExtensionCatalogService` | `ExtensionManager`、`ExtensionApi`、`ExtensionLoader` | `DesktopExtensionApi`、`DesktopExtensionManager`、`DesktopExtensionLoader` | PackageManager/签名与 ClassLoader/JAR 是各自 adapter；兼容性诊断是跨端增强，不能倒推原始语义。 |
| 34 扩展安装 | `ExtensionManager.installExtension/updateExtension/cancelInstallUpdateExtension` → `ExtensionInstaller` | `ExtensionInstallCoordinator`、`ExtensionInstallPort` | `ExtensionManager`、`ExtensionInstaller` | `DesktopExtensionApi`、`DesktopExtensionManager`、`DesktopExtensionInstallPort`、APK→JAR | 原始基本安装、更新、取消必须逐项对照；SHA、snapshot、rollback/runtime restore 是保留的跨平台安全增强。 |
| 35 扩展加载 | `ExtensionLoader` → PackageManager、签名、私有 APK | 无 | `ExtensionLoader` | `DesktopExtensionLoader`、`ExtensionClassLoader`、ServiceLoader/compat | Android package/signature 与 Desktop JAR/compat 都是 platform adapter；缺真实 fixture 的 compat 能力仍为待偿还技术债。 |
| 36 扩展安全/信任 | `ExtensionLoader` 不受信任结果 → `ExtensionManager.trust` | `ExtensionTrustPolicy`、`RepositoryIdentity` | `ExtensionLoader`、`ExtensionManager.trust` | `DesktopExtensionInstallPort`、metadata sidecar、Desktop loader | 原始签名信任和基本 trust 为核心；repo fingerprint/SHA continuity 是安全增强，不能声称固定 main 已有。 |
| 37 扩展详情与更新 | `GetExtensionsByType`、`ExtensionsScreenModel` / `ExtensionDetailsScreenModel` → `ExtensionManager.updateExtension/uninstallExtension` | `ExtensionPresentationStore` 的分类、搜索、刷新、逐包安装终态与 enabled-first 规则 | 当前 Android consumer 已接入 shared classifier/action store | `ExtensionListScreen`、`ExtensionDetailsScreen` → Desktop `ExtensionsScreenModel` / `DesktopExtensionPresentationPort` → `DesktopExtensionManager` adapter | fixed-main 搜索、分类、刷新、逐包安装终态与详情缺失/卸载生命周期已由 shared contract 回放，并在 Task 6C/6D 接入当前 Android 与 Desktop consumer；文件信息、打开目录、APK→JAR 与信任恢复等 Desktop 产品/平台增强继续保留。 |
| 38 源偏好设置 | `SourcePreferencesScreen` → source preference schema | 无 | `SourcePreferencesScreen` | Desktop `SourcePreferencesScreen` | 控件渲染与文件/窗口交互是 platform adapter；missing、不可配置和 setup failure 的状态须保持可区分。 |
| 39 WebView/源登录 | `WebViewScreenModel` → `WebViewScreen` / `WebViewScreenContent` → Android CookieManager | `SourceLoginSession` | `WebViewScreenModel`、`WebViewScreen` | `DesktopBrowserLoginAdapter`、`DesktopSourceLoginDialog` | 受控浏览器和 Cookie 存储是 platform adapter；显式 login session 状态为跨端迁移输出，不可反称原始 WebView 行为。 |
| 40 Cloudflare 绕过 | 固定 main 的 `CloudflareInterceptor` / WebView Cloudflare help；无 FlareSolverr | 无 | `CloudflareInterceptor`、WebView UI | `CloudflareChallengeManager`、`DesktopCloudflareInterceptor`、`FlareSolverrClient`、`CloudflareBypassDialog` | FlareSolverr 是 Desktop-only、用户显式选择的后备产品能力；它不是原始 Mihon 语义，也不得静默接管请求。 |

## Manifest 结构化 provenance 契约

IDs 28、29、30、32–40 的 completion gate 只读取以下结构化字段；`authoritativeImplementation` 与 `desktopImplementation` 仅为旧消费者兼容文本，不提供完成证据：

- `upstreamRef`：必须精确等于固定 main；
- `upstreamSymbols`：非空对象列表，每项分别记录完整 repository-relative `path` 和非空 `symbol`，且 path 必须在固定 main tree 中存在；
- `sharedImplementationPaths`：显式路径列表；允许为空，非空时每条路径都必须存在；
- `currentAndroidConsumerPaths` / `desktopConsumerAdapterPaths`：非空完整路径列表，每条路径逐项存在；
- `deviations`：对象列表，每项各自带一个允许的 `classification` 和非空 `description`，不得用一段文字中的单个 token 为多个偏离兜底。

## Fixture 清单与可信度

| Fixture | 类型/版本 | 当前用途 | 可信度边界 |
| --- | --- | --- | --- |
| `minimalDexBytes()` / `MINIMAL_DEX_BASE64` | 最小 DEX v035 | `ApkToJarConverterTest`、`DesktopExtensionProductBaselineTest` 保护 production APK→JAR 转换 | 合成结构 fixture，只证明转换机械链路，不证明第三方扩展兼容。 |
| `MinimalTestSource` ServiceLoader JAR | repo test classpath | `ExtensionCompatibilityTest` 证明 production loader 能发现 `Source` | 确定性 JVM fixture，不代表 Android API 使用面。 |
| `eu.kanade.tachiyomi.extension.zh.manhuagui@1.4.28` | 固定 Keiyoushi APK，SHA-256 `200cfc4b3b9e98f387824e3cecb13f97f4b0971f8fb678ce49c60aab6856c0c8` | `RealExtensionCompatEvidenceTest` 经 production converter/loader 调用真实 Source/settings，并由 `compat-evidence.json` 绑定逐符号证据 | 先前“缺 `android.app.Application`、只能 unsupported”的结论已被 `2e17f259f` 的 Application DI 闭合和 `a1b65a746` 的 required evidence supersede；该 fixture 证明已实际调用的 Desktop compat 边界，不把 shim 或当前 consumer 升格为原版权威。 |
| 本地 ManHuaGui 构建产物 | 开发机临时版本 | `ManhuaguiLoadTest` 开发诊断 | 非 CI 权威，不能独立支持新增 compat stub。 |

## Compat evidence schema

`app-desktop/src/test/resources/extensions/compat-evidence.json` 的每项继续要求 `symbol`、可追溯的 `fixture`、真实加载/调用 `test`、仅 `required`/`unsupported` 的 `status` 和非空 `removalCondition`。自造 stub 测试不能证明第三方扩展使用；每个 public compat symbol 必须有真实 fixture 调用证据，或明确 `unsupported` 并在确认无生产引用后删除。该证据只证明 Desktop compatibility 边界，不证明固定 main 来源。

## Task 6B 的固定 main 回放规则

Task 6B 已完成，但后续维护仍不得把当前 `ExtensionsScreenModel`、`ExtensionManager` 或 `ExtensionDetailsScreenModel` 当作 authority fixture。权威固定为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`；名称/source/baseUrl/id 搜索、updates/installed/untrusted/语言分类、刷新、逐 package 安装步骤、取消、卸载、trust 和详情卸载事件均先从该快照回放，再比较当前 fork。

Task 6B 的差异分类结果：`ExtensionManager` 的异步初始化，以及事务 ID、active receiver 去重、reload/rollback callback 作为工程/安全增强保留；缺失的 `takeWhile { step != Installed }` 已恢复，trust reload 改走 injected loader seam。Task 6C/6D 已让 Desktop extension presentation 的列表状态、分类、安装动作、详情缺失/卸载生命周期消费同一 shared contract；后续维护不得恢复 Compose-local 分类、安装 reducer 或直接把当前 Desktop/Android consumer 当作 fixed-main 证据。

## Desktop 产品边界与现有保护网

- 用户入口保持 Browse → Sources / Global Search，以及 Browse → Extensions → Installed/Available → Extension details。
- Desktop 保留预编译 JAR、APK→JAR、原子替换/reload、文件摘要和仓库信息、Open folder、键鼠/宽屏、显式 FlareSolverr 后备与 Test Mode。
- Android-only AAR、QuickJS 或没有真实 fixture 调用的 compat API 不承诺支持；文件工具仅承担 Desktop side effect，不进入共享业务层。

## 固定 main 路径清单（CI 可携带）

`app-desktop/src/test/resources/parity/fixed-main-path-inventory.json` 是 IDs 28–40 的固定 main 路径证据。它记录精确的 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`，以及每个去重的 `upstreamSymbols.path` 在该提交 tree 中的 Git blob ID。契约测试只读取该 UTF-8 JSON 资源；不会在测试运行时启动 Git、读取 `.git`，或下载历史对象，因此默认 shallow checkout 的 Desktop CI 也可验证 provenance。

清单必须在拥有完整历史和该固定提交对象的本地 clone 中生成：先从 manifest 的 IDs 28–40 收集去重的 `upstreamSymbols.path`，再对每条路径执行 `git rev-parse 6fbf6dfca203d99d6dd32137f2df97ced40c81b8:<path>` 并写入 `blobId`。只有固定 ref 变更，或这些 IDs 的 upstream path 集合发生变更时，才可重新生成；生成后必须核对路径集合、ref 和 40 位小写 blob ID。不要以当前 fork 的路径、当前文件内容或 CI checkout 深度替代此证据。
