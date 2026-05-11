# Mihon Android vs Mihon Desktop — 技术选型、架构与功能差异完整报告

> 生成日期：2026-03-28

---

## 1. 技术选型对比

| 维度 | Mihon Android | Mihon Desktop |
|------|--------------|---------------|
| **语言** | Kotlin (Android target) | Kotlin (JVM target) |
| **UI 框架** | Jetpack Compose (Android) | Compose Multiplatform (Desktop) |
| **构建系统** | Gradle + AGP (Android Gradle Plugin) | Gradle + Compose Desktop plugin |
| **最低平台** | Android 8+ (API 26) | JVM 17+ (macOS/Windows/Linux) |
| **导航** | Voyager (`cafe.adriel.voyager`) | Voyager (同一库) |
| **DI** | Injekt — `InjektModule.registerInjectables()` | Injekt — 手动 `addSingleton()`/`addSingletonFactory()` |
| **数据库** | SQLDelight + Android SQLite driver | SQLDelight + JVM SQLite driver (`sqlite-jdbc`) |
| **偏好存储** | AndroidX DataStore / SharedPreferences → `AndroidPreferenceStore` | `java.util.prefs.Preferences` → `DesktopPreferenceStore` |
| **网络** | OkHttp 5 + Android interceptors | OkHttp 5 + 自定义 `DesktopNetworkHelper` |
| **图片加载** | Coil 3 + 自定义 Fetcher/Decoder | 自定义 `ImageLoader` (OkHttp + `javax.imageio` + `ImageBitmap`) |
| **Cookie 管理** | Android `WebkitCookieManager` + OkHttp bridge | `DesktopCookieJar` (内存 `ConcurrentHashMap` + 手动注入) |
| **扩展系统** | APK-based (`PackageManager` + `DexClassLoader`) | JAR-based (`URLClassLoader`) |
| **后台任务** | WorkManager (定时/约束任务) | Kotlin Coroutines polling loops (自定义调度器) |
| **通知** | Android NotificationManager + Channels | 无 (Desktop 无原生通知) |
| **下载管理** | 多层：`DownloadManager` → `Downloader` → `DownloadCache` | 单类 `DesktopDownloadManager` (协程 + Semaphore) |
| **备份** | `BackupManager` + Protobuf `.tachibk` | `DesktopBackupManager` (JSON `.tachibk`) + `AutoBackupScheduler` |
| **版本管理** | `versionCode`/`versionName` in `build.gradle.kts` | `AppVersion.kt` (`0.STAGE.FEATURE.GIT_HASH`) |

---

## 2. 代码架构对比

### 2.1 模块结构

**Android（多模块分层）**
```
app/                  → 展示层 (Compose screens, Activities, DI)
domain/               → 业务逻辑 (use cases, models, repo interfaces)
data/                 → 数据层 (SQLDelight, repo implementations)
presentation-core/    → 共享 Compose 组件
core/common/          → 工具类 + Kotlin extensions (KMP commonMain/androidMain/jvmMain)
source-api/           → KMP source 抽象 (extensions 共享)
source-local/         → 本地文件源
i18n/                 → Moko 国际化
```

**Desktop（单模块扁平）**
```
app-desktop/src/main/kotlin/mihon/desktop/
├── di/               → DI wiring (DesktopAppModule)
├── domain/           → Desktop 特有 use cases
├── download/         → 下载管理
├── extension/        → JAR 扩展加载
├── network/          → 网络 + cookie
├── backup/           → 备份/恢复
├── platform/         → 平台适配
├── reader/           → 阅读器数据模型
├── settings/         → 偏好存储
└── ui/               → 所有 Compose UI screens
```

Desktop 直接复用 `domain/`、`data/`、`core/common/`、`source-api/` 的 `commonMain`/`jvmMain` 代码。

### 2.2 状态管理

| 维度 | Android | Desktop |
|------|---------|---------|
| **Screen 状态** | `StateScreenModel` (Voyager ScreenModel + StateFlow) | `remember { mutableStateOf() }` 直接在 Composable |
| **UI 事件** | Channel/SharedFlow 事件总线 | 无事件层，回调直接调用 |
| **生命周期** | Activity/Fragment lifecycle-aware | 无生命周期概念（窗口级） |

### 2.3 DI 模式

**Android**：模块化注册
```kotlin
class DomainModule : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addFactory { GetManga(get()) }
    }
}
```

**Desktop**：单一初始化函数
```kotlin
fun initDesktopDI(database: MihonDatabase) {
    Injekt.addSingleton(database)
    Injekt.addSingletonFactory { GetManga(Injekt.get()) }
    // 所有绑定在一个函数中
}
```

### 2.4 导航架构

两者都使用 Voyager，结构相同（`TabNavigator` + 嵌套 `Navigator`），Tab 名称有差异：

| | Android | Desktop |
|-|---------|---------|
| **Tabs** | Library / Updates / Browse / More / **History** | Library / Updates / Browse / More |
| **Detail 跳转** | `navigator.push(MangaScreen(id))` | `navigator.push(MangaDetailScreen(id))` |

---

## 3. 功能差异矩阵

### 3.1 Android 已实现、Desktop 已跟进（✅）

| 功能 | Desktop 实现 |
|------|-------------|
| 扩展商店 + 安装 | `DesktopExtensionManager` + `ExtensionListScreen` |
| 自定义扩展仓库 | `ExtensionRepoScreen` |
| 源偏好设置 | `SourcePreferencesScreen` |
| NSFW 扩展过滤 | `ExtensionListScreen` 过滤逻辑 |
| 全局搜索 | `GlobalSearchScreen` (多源并行) |
| 分类管理 | `CategoryManagementDialog` |
| 章节过滤/排序/多选 | `MangaDetailScreen` |
| Per-manga 阅读模式 | `MangaDetailScreen` + `readingModeFromViewerFlags()` |
| LTR/RTL/Webtoon 阅读 | `SinglePagePagerViewer` / `WebtoonViewer` |
| 双页阅读（Desktop 新增） | `DualPagePagerViewer` |
| Split Wide Pages | `VirtualPageList` + `SinglePagePagerViewer` |
| 书库「已下载」过滤 | `LibraryTab` 过滤 |
| 下载队列 + 并行控制 | `DesktopDownloadManager` (Semaphore) |
| 更新分类过滤 | `LibraryUpdateCategoryFilter` |
| 自动备份 | `AutoBackupScheduler` |
| Cloudflare cookie 导入 | `AdvancedSettingsScreen` |
| 批量分类设置 | `LibraryTab` BatchCategoryDialog |

### 3.2 Android 有、Desktop 缺失（待实现）

| 功能 | Android 实现 | 优先级 |
|------|-------------|--------|
| **History Tab** | 完整阅读历史 + 搜索 + 删除 | 高 |
| **Tracking** | MAL/AniList/Kitsu 双向同步 | 中 |
| **Migration 向导** | 批量/单个源迁移 | 中 |
| WebView | 内置浏览器（源登录/CF 挑战） | 低（已用手动 cookie 替代） |
| 通知系统 | 下载/更新/备份系统通知 | 低 |
| Widget | 书库更新桌面 Widget | 不适用 |
| URI Deep Link | `tachiyomi://` scheme | 低 |

### 3.3 Desktop 有、Android 无

| 功能 | 说明 |
|------|------|
| 双页阅读模式 | 左右两页并排（`DualPagePagerViewer`） |
| 键盘快捷键 | 方向键翻页、Esc 退出 |
| 鼠标滚轮翻页 | Webtoon / 单页滚轮支持 |
| 点击区域翻页 | `TapZone` 可视化配置 |
| 网络缓存管理 | 显示缓存大小 + 手动清理 |

---

## 4. 扩展系统对比

| 维度 | Android | Desktop |
|------|---------|---------|
| **格式** | APK (DEX bytecode) | JAR (JVM bytecode) |
| **加载器** | `PackageManager` + `DexClassLoader` | `URLClassLoader` |
| **发现** | Intent filter `tachiyomi.extension` | 扩展 API REST endpoint |
| **安装** | `PackageInstaller`（系统安装） | 下载 JAR 到 `~/.mihon/extensions/` |
| **安全** | APK 签名验证 | 无签名验证（信任扩展源） |
| **隔离** | 独立 APK 进程 | 同 JVM 进程（ClassLoader 隔离） |
| **兼容性** | Android 扩展原生支持 | 需 JVM 重编译的专用 JAR |

---

## 5. 数据存储对比

| 维度 | Android | Desktop |
|------|---------|---------|
| **数据库** | SQLDelight → Android SQLite | SQLDelight → `sqlite-jdbc` |
| **DB 路径** | `/data/data/app.mihon/databases/` | `~/.mihon/mihon.db` |
| **偏好** | SharedPreferences / DataStore | `java.util.prefs.Preferences` |
| **下载目录** | 用户选择（SAF） | `~/.mihon/downloads/` |
| **扩展目录** | APK 系统安装 | `~/.mihon/extensions/` |
| **缓存** | Android cache dir | `~/.mihon/cache/` |
| **备份格式** | Protobuf `.tachibk` | JSON `.tachibk` |

---

## 6. 测试对比

| 维度 | Android | Desktop |
|------|---------|---------|
| **框架** | JUnit 4 + AndroidX Test + Robolectric | JUnit 5 (Jupiter) |
| **UI 测试** | Compose Testing (`AndroidComposeTestRule`) | 无 Compose 测试（仅逻辑层） |
| **Mock** | MockK / Mockito | 手写 Fake（`FakeMangaRepository` 等） |
| **CI** | GitHub Actions（多 matrix） | 本地 `./scripts/build-desktop.sh` |
| **覆盖范围** | domain + data + 部分 UI | domain + data + navigation contract |

---

## 7. 关键差距总结

### 功能缺口（优先级排序）

1. **History Tab** — 用户无法查看/搜索/管理阅读历史
2. **Tracking 集成** — MAL/AniList/Kitsu 同步
3. **Migration 向导** — 源迁移重度用户核心需求

### 架构层面的差距

1. **状态管理**：Desktop 缺少 `ScreenModel` 层，状态散布在 Composable 中，难以测试和复用
2. **DI 组织**：单一 `initDesktopDI()` 随功能增长会变臃肿
3. **图片加载**：自定义 `ImageLoader` 缺少 Coil 的缓存策略和内存管理

### 合理的平台差异（不需填补）

- WebView → 手动 cookie 导入替代
- 通知 → 应用内 snackbar/toast 替代
- Widget / Intent / Deep Link → 桌面平台不适用

---

## 8. 单模块扁平架构 vs 多模块分层架构

### 8.1 Desktop 单模块架构的优势

**1. 极低的启动摩擦**

单模块意味着没有模块间依赖声明、没有 API/implementation 可见性管理、没有跨模块构建缓存失效。新增一个类直接放到对应包下即可，不需要思考"这个类属于哪个模块"。对于一人项目的快速迭代，这个优势是决定性的——Android Mihon 的多模块结构是多年多人协作演化出来的，不是一开始就有的。

**2. 编译速度**

多模块架构的构建速度优势来自增量编译（只重编变更模块）。但当项目规模 <50 个源文件时，全量编译也只需几秒。Desktop 当前约 70 个 Kotlin 源文件，全量编译 ~8 秒，增量编译优势尚不明显。反而多模块会引入 Gradle 模块配置开销（每个模块多 ~1-2 秒配置时间）。

**3. 无循环依赖焦虑**

多模块架构最常见的痛点是模块间循环依赖。例如 `domain` 模块定义了 `MangaRepository` 接口，`data` 模块实现它，但 `data` 的 mapper 又需要 `domain` 的模型类——稍有不慎就出现循环。单模块完全没有这个问题。

**4. 重构成本低**

移动一个类从 `reader/` 到 `domain/` 只是改 package 声明。多模块下同样的操作需要修改两个模块的 `build.gradle.kts`、可能调整 API 可见性、可能触发依赖传递变化。

### 8.2 Desktop 单模块架构的劣势

**1. 无编译期边界强制**

多模块架构中，`domain` 模块 **不能** import `data` 模块的类——Gradle 不允许反向依赖。这是架构约束的硬保证。单模块中，任何类都能 import 任何其他类，依赖方向完全靠开发者自觉。当前 Desktop 已经出现了轻微的边界模糊：`ui/reader/DesktopReaderScreen.kt` 直接调用 `DesktopDownloadManager`（跨越了展示层→数据层），而 Android 端这种调用必须经过 `domain` 层的 use case。

**2. 不可共享的 Desktop 特有逻辑**

如果未来出现第二个 Desktop 变体（例如一个轻量版只有阅读功能，没有书库管理），单模块架构下无法拆分复用。多模块可以让轻量版只依赖 `reader` + `source-api` 模块。

**3. 代码可发现性随规模下降**

70 个文件时，所有代码都在目力范围内。当文件数增长到 200+ 时（参考 Android 端 `app/` 模块有 400+ 文件），在扁平结构中找到"负责处理章节下载进度的代码"会越来越困难。模块名本身就是一种导航——看到 `data/` 就知道去那里找 repository 实现。

**4. 测试隔离困难**

多模块中，`domain` 模块的测试天然不能访问 `data` 层实现——编译器直接拒绝。单模块中，测试很容易不小心绕过抽象层直接操作实现类，导致测试与实现细节耦合。

### 8.3 判断：何时需要拆分

当前 Desktop 的 ~70 文件规模下，单模块是正确选择。建议的拆分阈值：

| 信号 | 说明 |
|------|------|
| 源文件数 > 150 | 平坦包结构导航困难 |
| 全量编译 > 30 秒 | 增量编译收益明显 |
| 出现第二个使用方 | 需要复用核心逻辑 |
| 多人协作 | 需要编译期边界防止误操作 |

目前没有一个信号亮红灯。过早拆模块是过度工程。

---

## 9. DI 模式对比：模块化注册 vs 单一初始化函数

### 9.1 Android 模块化注册的优势

**1. 关注点分离**

每个 `InjektModule` 只注册自己领域的绑定：`DomainModule` 注册 use cases，`DataModule` 注册 repositories，`AppModule` 注册 UI 层依赖。新增一个 use case 时，开发者明确知道去 `DomainModule` 添加——不会在错误的位置注册。

**2. 可选组合**

不同构建变体可以组合不同的模块。例如测试环境注册 `FakeDataModule` 替代 `DataModule`，而 `DomainModule` 保持不变。这使得 DI 层级可以像积木一样拼装。

**3. 可独立测试**

每个模块的绑定可以独立验证：初始化一个 `DomainModule`，mock 它的依赖，检查所有绑定是否能解析。不需要初始化整个应用的 DI 容器。

### 9.2 Android 模块化注册的劣势

**1. 模块间绑定不可见**

`DomainModule` 注册了 `GetManga`，`AppModule` 要用它——但在代码层面看不到这两者的关联。如果 `DomainModule` 忘记注册 `GetManga`，只有运行时 `Injekt.get<GetManga>()` 才会抛 `InjektionException`。编译时完全不报错。

**2. 注册顺序敏感**

模块初始化有顺序依赖：`DataModule` 必须在 `DomainModule` 之前初始化（因为 domain 的 use cases 依赖 data 的 repositories）。这个顺序不在类型系统中表达，只能靠约定和运行时发现。

### 9.3 Desktop 单一初始化函数的优势

**1. 依赖关系一目了然**

所有绑定在一个函数中，从上到下阅读就能看到完整的依赖图。不存在"这个绑定到底在哪个模块注册的"的困惑。

**2. 初始化顺序显式**

```kotlin
// 先注册 database，再注册 repository（它依赖 database），再注册 use case（它依赖 repository）
Injekt.addSingleton(database)
Injekt.addSingletonFactory { MangaRepositoryImpl(Injekt.get()) }
Injekt.addSingletonFactory { GetManga(Injekt.get()) }
```

顺序就在代码中，不需要靠约定。

**3. 启动调试简单**

应用启动失败时，断点打在 `initDesktopDI()` 一个函数里就能追踪所有绑定。不需要跨多个模块文件跳转。

### 9.4 Desktop 单一初始化函数的劣势

**1. 函数膨胀**

当前 `DesktopAppModule.kt` 已经 ~250 行。如果继续添加 History、Tracking、Migration 等功能的绑定，这个函数可能膨胀到 500+ 行。单一函数超过 300 行后可读性急剧下降。

**2. 无法部分替换**

测试时如果只想替换 network 层为 mock，需要复制整个 `initDesktopDI()` 函数然后修改其中几行。模块化方案只需替换一个模块。

**3. 不利于多人协作**

如果两个开发者同时在 `initDesktopDI()` 中添加新绑定，必然产生合并冲突。模块化注册中，各自改各自的模块文件，冲突概率低得多。

### 9.5 务实的演进方向

不需要立即引入 `InjektModule` 模式。中间态方案是**按职责拆分为多个 `init` 函数**：

```kotlin
fun initDesktopDI(database: MihonDatabase) {
    initDataLayer(database)
    initDomainLayer()
    initNetworkLayer()
    initExtensionLayer()
    initUILayer()
}
```

保留单一入口点的可读性，同时缓解函数膨胀和冲突。当（且仅当）需要多个构建变体或多人协作时，再提升为模块化注册。

---

## 10. 测试缺失的影响与弥补方案

### 10.1 当前缺失项

| 测试类型 | Android | Desktop | 影响 |
|---------|---------|---------|------|
| **Compose UI 测试** | `AndroidComposeTestRule` 验证 UI 交互 | 无 | 高 |
| **集成测试** | Robolectric 模拟 Android 环境运行完整流程 | 无 | 中 |
| **CI 自动化** | GitHub Actions 多 matrix | 仅本地脚本 | 中 |
| **Mock 框架** | MockK（成熟、表达力强） | 手写 Fake（简单但覆盖有限） | 低 |

### 10.2 缺失带来的具体风险

**Compose UI 测试缺失——风险最高**

当前 Desktop 的 UI bug 只能通过手动启动应用发现。典型的漏网之鱼：

- 按钮点击回调未绑定（编译通过，运行时点击无反应）
- 列表为空时没有显示空状态提示
- 对话框确认按钮的 `onClick` lambda 中状态更新顺序错误
- Composable 参数改了签名但调用方没同步更新（编译能过，因为 Kotlin 默认参数）

Android 端通过 `composeTestRule.onNodeWithText("XXX").performClick()` 可以自动化验证这些场景。Desktop 端完全依赖人工测试，但人工测试不可能覆盖所有分支。

**实际案例**：之前 `LibraryTab` 的 SelectionActionBar 中"Categories"按钮的 `onClick` 回调写错了变量名，编译通过但点击无效。如果有 UI 测试，一行 `onNodeWithText("Categories").performClick()` 就能提前发现。

**CI 缺失——风险累积**

没有 CI 意味着：
- 合并代码前不会自动运行测试——已有测试可能在某次重构后静默失败
- 没有构建状态徽章——无法判断主分支是否健康
- 依赖更新（Compose、SQLDelight、OkHttp）后无自动验证

### 10.3 是否有必要弥补

**Compose UI 测试：有必要，但需等条件成熟。**

Compose Multiplatform 在 2026 年已支持 Desktop 的 `@OptIn(ExperimentalTestApi::class) runComposeUiTest { }`，但 API 仍标记为实验性。等其稳定后引入，优先覆盖核心交互路径（阅读器翻页、书库搜索、扩展安装）。

**CI 自动化：有必要且可立即执行。**

成本极低（一个 GitHub Actions YAML 文件），收益明确（每次 push 自动运行 `./gradlew :app-desktop:jvmTest`）。

**MockK 替代手写 Fake：没有必要。**

手写 Fake 在当前规模下比 MockK 更好——它们是显式的、可读的、不依赖反射。MockK 的优势在大型项目中才明显。

### 10.4 弥补路线

| 阶段 | 工作 | 优先级 |
|------|------|--------|
| **立即** | 添加 GitHub Actions CI：push 触发 `jvmTest` + `spotlessCheck` | 高 |
| **短期** | 为所有 Screen/Tab 添加实例化冒烟测试（参见 CLAUDE.md Test Policy 第 4 节） | 高 |
| **中期** | 引入 Compose Desktop UI 测试：覆盖阅读器翻页、书库过滤、扩展安装 3 个核心流程 | 中 |
| **长期** | 添加端到端测试：MockWebServer 模拟源 API → 安装扩展 → 浏览漫画 → 阅读章节 | 低 |

---

## 11. 架构差距的设计初衷与追赶方案

### 11.1 为什么状态管理没有用 ScreenModel

**设计初衷**：Desktop 开发初期的目标是**最快速度验证功能可行性**。`remember { mutableStateOf() }` 是 Compose 最直接的状态管理方式，零依赖、零抽象、写起来最快。每个 Screen 的状态就在它的 `Content()` 函数里，一个文件解决一个页面，非常适合"写一个能工作的原型"。

引入 `ScreenModel` 需要：定义 sealed interface 作为 UI state、定义 sealed interface 作为 event、在 ScreenModel 中处理 event → 更新 state、在 Composable 中 collect state + 发送 event。一个页面的代码量增加约 2-3 倍。在功能高速迭代期（两周内从零搭建到 22 个阶段的功能），这个抽象成本是不值得的。

**现在的代价**：

- 状态逻辑和 UI 渲染混在同一个函数中，无法单独测试状态转换
- `DesktopReaderScreen.Content()` 已膨胀到 ~400 行，包含页面加载、缩放状态、阅读进度、键盘处理等多个关注点
- 状态重组效率低——状态变化时整个 Composable 重组，而 ScreenModel + StateFlow 可以精确通知变更的字段

**追赶方案**：

不需要一次性全部重构。按照"痛点驱动"原则，优先提取最膨胀的 3 个 Screen：

| Screen | 当前行数 | 状态复杂度 | 建议操作 |
|--------|---------|-----------|---------|
| `DesktopReaderScreen` | ~400 行 | 极高（页面、缩放、进度、设置、键盘） | 提取 `ReaderScreenModel` |
| `MangaDetailScreen` | ~350 行 | 高（漫画信息、章节列表、下载状态） | 提取 `MangaDetailScreenModel` |
| `LibraryTab` | ~300 行 | 中（搜索、过滤、选择、分类） | 提取 `LibraryScreenModel` |

其余页面（设置、扩展列表等）状态简单，保持 `mutableStateOf` 即可。

### 11.2 为什么 DI 用了单一函数

**设计初衷**：与上一条相同——速度。`initDesktopDI()` 一个函数从上到下写完所有绑定，不需要思考"这个绑定属于哪个模块"。在添加新功能时，打开一个文件，加一行 `Injekt.addSingletonFactory { ... }`，就完成了 DI 配置。

**追赶方案**：见上方 9.5 节——按职责拆分为多个 `init` 函数作为中间态。

### 11.3 为什么图片加载没用 Coil

**设计初衷**：Coil 3 对 Compose Desktop 的支持在 2025 年初仍处于早期阶段，API 不稳定。当时选择了最可靠的路径：OkHttp 下载字节 → `javax.imageio` 解码 → Compose `ImageBitmap`。这条路径虽然性能不好，但**在所有平台上都能工作，没有兼容性风险**。

实际上当前的代码已经引入了 Coil 3（`rememberAsyncImagePainter`），但裁边和分割功能仍然回退到 `javax.imageio`，因为 Coil 的 `AsyncImagePainter.State.Success` 不直接暴露可操作的像素缓冲区。

**追赶方案**：见 `EVOLUTION_PLAN.md` 第二部分——通过 Skiko 底层 API 直接调用 Skia 解码器，绕过 `javax.imageio`。这是性能提升最大的单一改动（JPEG 解码加速 3-5 倍），且无需引入新依赖（Skiko 已是 Compose Desktop 的传递依赖）。

---

## 12. 与主流漫画阅读器的架构对比

### 12.1 对比对象

| 阅读器 | 技术栈 | Stars | 定位 |
|--------|--------|-------|------|
| Suwayomi | Kotlin/Ktor + Web 前端 | 6.6K | 在线（Mihon 扩展兼容） |
| Mangayomi | Flutter/Dart | 3.1K | 在线（全平台） |
| SumatraPDF | C + Win32 | 16.3K | 本地（极致轻量） |
| YACReader | C++ + Qt | 1.2K | 本地（专业漫画） |
| OpenComic | JS + Electron | 1.7K | 本地（跨平台） |
| Kavita | C# + ASP.NET | 10.2K | 自托管服务器 |

### 12.2 架构维度对比

#### 扩展/插件系统隔离

| 阅读器 | 隔离方式 | Mihon Desktop 差距 |
|--------|---------|-------------------|
| Suwayomi | dex2jar + Android compat stub + ClassLoader 隔离 | Desktop 依赖预编译 JAR，无法直接加载 keiyoushi APK |
| Mangayomi | 独立扩展系统（Dart isolate） | Desktop 与 Mihon 生态绑定，非独立但兼容性更高 |
| Kavita | 无扩展系统，内置解析器 | 不可比 |

**差距**：Desktop 的 ClassLoader 隔离已经做得很好（child-first delegation、精确控制父加载前缀），但缺少 APK 直接加载能力。见 `EVOLUTION_PLAN.md` Phase A-D。

#### 图片渲染管线

| 阅读器 | 解码器 | 渲染层 | 预加载 | 大图处理 |
|--------|--------|--------|--------|---------|
| SumatraPDF | 自有 C 解码（极快） | Win32 GDI 直接绘制 | 全文档预渲染 | mmap + 按需解码 |
| YACReader | Qt QImageReader | QGraphicsView + OpenGL | QPixmapCache LRU | LOD 多级缩放 |
| OpenComic | Chromium libjpeg-turbo | HTML Canvas + GPU 合成 | `new Image()` 预取 | 自动 tiling |
| Mangayomi | Flutter image codec | Skia (via Flutter) | Flutter 框架管理 | 框架自动降采样 |
| **Mihon Desktop** | **javax.imageio（慢）** | **Compose Image + Skia** | **无预加载** | **无特殊处理** |

**差距**：

1. **解码速度**：所有竞品都使用平台原生或 SIMD 加速的解码器。Desktop 是唯一使用纯 Java 解码的。这是体验差距的最大来源。
2. **预加载**：所有成熟阅读器都有某种形式的预加载/预渲染。Desktop 完全没有。
3. **大图处理**：SumatraPDF 用 mmap 按需解码，YACReader 用 LOD，Chromium 用 tiling。Desktop 把 8000×6000 的图全量解码到内存。

#### 状态管理成熟度

| 阅读器 | 模式 | 可测试性 |
|--------|------|---------|
| Suwayomi | Ktor 后端 MVC + 前端状态管理 | 后端可独立测试，前端依赖框架 |
| Mangayomi | Flutter BLoC / Riverpod | 状态逻辑与 UI 分离，高可测试性 |
| Kavita | ASP.NET MVC + Angular Services | 清晰分层，服务可注入 mock |
| YACReader | Qt Model/View | 模型独立于视图，中等可测试性 |
| **Mihon Desktop** | **Compose mutableStateOf 内联** | **低——状态与 UI 混合** |

**差距**：Desktop 是对比中唯一没有显式状态管理层的。Mangayomi 用 BLoC，Kavita 用 Angular Services，Suwayomi 后端天然分离。这直接影响了可测试性和重构信心。

#### Cookie / 认证管理

| 阅读器 | 方案 | 持久化 |
|--------|------|--------|
| Suwayomi | Android compat `WebkitCookieManager` stub | 持久化到 SQLite |
| Mangayomi | `dio` cookie manager | 持久化到文件 |
| OpenComic | Electron `session.cookies` | Chromium cookie store（自动持久化） |
| **Mihon Desktop** | **`ConcurrentHashMap` 内存存储** | **不持久化——重启丢失** |

**差距**：Desktop 的 cookie 存储是纯内存的。应用重启后所有 cookie 丢失，用户需要重新手动导入 Cloudflare cookie。这是与所有竞品的差距——它们都有某种形式的 cookie 持久化。

修复方案：将 `DesktopCookieJar` 的 `ConcurrentHashMap` 序列化到 `~/.mihon/cookies.json`，启动时恢复。工作量 <50 行代码。

#### 错误恢复与容错

| 阅读器 | 扩展崩溃处理 | 网络错误处理 |
|--------|------------|------------|
| Suwayomi | ClassLoader 隔离，扩展异常不影响服务器 | HTTP 错误自动重试 + 用户提示 |
| Mangayomi | Dart isolate 隔离 | dio 拦截器 + 自动重试 |
| **Mihon Desktop** | **同 JVM 进程，扩展异常可能导致 UI 冻结** | **手动 try-catch，无统一错误处理** |

**差距**：Desktop 的扩展在主 JVM 进程中运行。一个扩展的 `popularMangaParse()` 如果抛出未捕获异常或进入死循环，可能冻结整个应用。Suwayomi 的服务端架构天然隔离了这个风险（前端在浏览器中不受影响）。

改善方案：为所有扩展调用添加 `withTimeout` + `try-catch` 包装，并在协程中运行（当前部分调用已经是，但不统一）。

### 12.3 综合评估

Mihon Desktop 在以下方面**领先或持平**竞品：
- **扩展生态覆盖**（依托 Mihon/keiyoushi 的 200+ 扩展）
- **阅读模式多样性**（单页/双页/Webtoon + Split + RTL，比大部分竞品全面）
- **代码共享效率**（KMP 直接复用 Android 端的 domain/data 层，Mangayomi 需要从零实现）

Mihon Desktop 在以下方面**落后**竞品：
- **图片渲染性能**（唯一使用 javax.imageio 的；无预加载；无大图优化）
- **状态管理成熟度**（唯一没有显式状态层的）
- **Cookie 持久化**（唯一不持久化的）
- **扩展容错**（缺少统一的超时和错误隔离）
- **CI/CD**（唯一没有自动化 CI 的）

优先补齐的顺序建议：图片渲染 > Cookie 持久化 > 扩展容错 > 状态管理重构 > CI。前三项都是 <100 行代码的改动但直接影响用户体验。
