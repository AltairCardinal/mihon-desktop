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
| 37 扩展详情与更新 | `ExtensionsScreenModel` / `ExtensionDetailsScreenModel` → `ExtensionManager.updateExtension/uninstallExtension` | 无 presentation shared core（Task 6B 待建） | `ExtensionsScreenModel`、`ExtensionDetailsScreenModel` | `ExtensionListScreen`、`ExtensionDetailsScreen`、`DesktopExtensionManager` | 搜索、分类、更新、详情退出必须先回放固定 main；文件信息/打开目录为 Desktop 产品增强。 |
| 38 源偏好设置 | `SourcePreferencesScreen` → source preference schema | 无 | `SourcePreferencesScreen` | Desktop `SourcePreferencesScreen` | 控件渲染与文件/窗口交互是 platform adapter；missing、不可配置和 setup failure 的状态须保持可区分。 |
| 39 WebView/源登录 | `WebViewScreenModel` → `WebViewScreen` / `WebViewScreenContent` → Android CookieManager | `SourceLoginSession` | `WebViewScreenModel`、`WebViewScreen` | `DesktopBrowserLoginAdapter`、`DesktopSourceLoginDialog` | 受控浏览器和 Cookie 存储是 platform adapter；显式 login session 状态为跨端迁移输出，不可反称原始 WebView 行为。 |
| 40 Cloudflare 绕过 | 固定 main 的 `CloudflareInterceptor` / WebView Cloudflare help；无 FlareSolverr | 无 | `CloudflareInterceptor`、WebView UI | `CloudflareChallengeManager`、`DesktopCloudflareInterceptor`、`FlareSolverrClient`、`CloudflareBypassDialog` | FlareSolverr 是 Desktop-only、用户显式选择的后备产品能力；它不是原始 Mihon 语义，也不得静默接管请求。 |

## Task 6B 的固定 main 回放规则

Task 6B 不得把当前 `ExtensionsScreenModel`、`ExtensionManager` 或 `ExtensionDetailsScreenModel` 当作 authority fixture。RED 先从固定 main 回放名称/source/baseUrl/id 搜索、updates/installed/untrusted/语言分类、刷新、逐 package 安装步骤、取消、卸载、trust 和详情卸载事件；随后再比较当前 fork。

已知当前 fork 差异：`ExtensionManager` 的初始化在 coroutine 中异步启动，而固定 main 同一流程同步完成；`ExtensionsScreenModel` 收集安装流时缺少固定 main 的 `takeWhile { step != Installed }`。两项均尚未被证明为必要 adapter 或正确性/安全性/UX 增强，分类为待偿还 fork 技术债，Task 6B 必须用对照测试决定保留、修复或显式隔离。

## Desktop 产品边界与现有保护网

- 用户入口保持 Browse → Sources / Global Search，以及 Browse → Extensions → Installed/Available → Extension details。
- Desktop 保留预编译 JAR、APK→JAR、原子替换/reload、文件摘要和仓库信息、Open folder、键鼠/宽屏、显式 FlareSolverr 后备与 Test Mode。
- `minimalDexBytes()`、`MinimalTestSource` 和 Keiyoushi fixture 分别只证明转换、ServiceLoader 和真实扩展边界；本地构建产物只是诊断，均不构成固定 main 来源证明。
- Android-only AAR、QuickJS 或没有真实 fixture 调用的 compat API 不承诺支持；文件工具仅承担 Desktop side effect，不进入共享业务层。
