# AGENTS.md

本文件说明 Codex 在本仓库工作时必须遵守的规则。

## 语言

所有面向用户的交流使用中文。确认 bug 已修复时，必须用中文明确说明。

## TDD 强制要求

**所有改变产品行为的功能变化（新增、修改、修复）必须严格执行红绿重构流程。**

1. **红**：先写失败测试，并确认它因正确原因失败。
2. **绿**：写最小实现让测试通过，并确认全绿。
3. **重构**：清理代码，再次确认测试全绿。

**没有对应测试的功能代码不允许提交。**

纯文档、纯文案或不改变产品行为的机械配置调整可以直接执行。

---

## 工程治理与进度状态

- 按能够独立交付和审查的功能批次执行，不按文件、测试类或机械行数切成微任务。
- `Estimated scope` 只是审查提示。超过 8 个文件或 400 行时记录内聚性与风险说明即可；不得为满足估算值压缩格式、复制实现或拆开不可独立编译/验收的上下文。
- 父 roadmap 只保存宏观阶段与唯一 `active-child-plan`；当前执行计划保存唯一 `active-task`；产品 child plan 从第一个未勾选项推导进度，不再声明 `active-task`。
- `parity-manifest.json` 是 capability 状态与证据的机器权威；tracker/report 只保存说明或生成视图，不得反向覆盖 manifest。
- checkbox 仅表示“实现、独立审查、验证、提交全部完成”。测试已绿但未审查或未提交时保持未勾选。
- 一个功能批次原则上只产生一个包含测试、production 与必要 checkoff 的提交；审查修复最多增加一个提交。不得为 close、advance、record evidence 等纯状态推进单独提交。
- 独立审查按功能批次进行。仅在架构假设失效、数据/格式迁移、安全边界或独立用户能力出现时重规划；不得仅因行数增长或格式化结果重规划。

### 分层验证

- 红绿循环：仅运行当前行为的 focused tests。
- 批次完成：运行相关单元、集成、wiring 与格式检查。
- 阶段完成：运行对应模块完整测试。
- 最终收口：运行全量 Android/Desktop 测试、Test Mode、Windows/macOS 构建和运行验收。
- `finalParityAudit`、完整 Desktop 测试和发布构建不得在每个微任务后重复运行。

### 子代理完成回执与等待

- 子代理完成实现或审查时，先发送结构化回执，再结束任务。回执包含 `status`、`diff`、`tests`、`commit`、`process` 和 `next`；可用 `python scripts/agent-handoff.py` 验证。
- 长时间工具调用前报告命令、预计时间和可用的 PID/日志位置。
- 等待超时后先检查代理状态和已报告进程。进程仍运行时继续等待，不重复执行命令。
- 代理空闲但没有回执时，只发送一次“返回完成摘要”的 follow-up；连续两次确认空闲且仍无回执后才允许中断。
- 恢复代理时传递现有 diff、测试结果和进程状态，不重新探索或重新实现已经完成的工作。

### Gradle 生命周期

- 同一 worktree 的重型 Gradle 验证由一个协调者串行执行。一次性等待使用 `python scripts/gradle-coordinator.py run --key <name> -- <gradle command>`；需分离启动时使用 `start`，再用 `wait/status` 查询。
- 外层等待超时不代表 Gradle 结束；先查询协调器状态，仍为 `STARTING/RUNNING` 时不得启动第二个 Gradle。
- 需要终止时只停止协调器记录的进程树，不使用全局 Java/Gradle 进程清理。

### 本机 Android SDK

- 本机 Android SDK 固定安装在 `D:\Android\Sdk`。截至 2026-08-29，已安装 command-line tools 22.0、`platforms;android-36`、`build-tools;36.0.0` 与 `platform-tools`，满足当前 `compileSdk = 36` 的 Android 编译和 JVM 单元测试要求。
- 用户级 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 均指向 `D:\Android\Sdk`；用户 PATH 包含 `D:\Android\Sdk\platform-tools` 和 `D:\Android\Sdk\cmdline-tools\latest\bin`。已经打开的 PowerShell/Codex 进程不会自动刷新用户环境，必要时在当前会话显式设置：

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
```

- 仓库忽略的 `local.properties` 使用 `sdk.dir=D\:\\Android\\Sdk`，不得提交该机器专属文件。Gradle 应优先通过该文件发现 SDK；删除或迁移 SDK 时必须同步更新用户环境和 `local.properties`。
- command-line tools 必须从 Android 官方下载页取得并校验官方 SHA-256。本次安装包为 `commandlinetools-win-15859902_latest.zip`，校验值为 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`。下载和 `sdkmanager` 访问境外网络时遵循仓库代理规则。
- 当前 `sdkmanager` 22.0 会提示迁移到新的 Android CLI，但仍能完成 SDK package 安装。`android.exe` 首次运行会额外下载 CLI；除非任务明确需要，不要把这个下载加入普通 Gradle 验证流程。
- SDK 自检以真实文件和 Gradle Android task 为准：至少确认 `platforms\android-36\android.jar`、`build-tools\36.0.0\aapt2.exe`、`platform-tools\adb.exe` 存在，再运行受影响的 Android focused tests；历史 AVD 配置不能代替 SDK 安装证据。

---


## 功能规划原则

**规划任何用户可见 capability 时都必须同时考虑用户界面。**

### Desktop UI 规范入口

- 新增或修改 Desktop 页面、导航、弹层、列表交互，或对应 HTML 审阅原型时，先读 [Desktop UI 实施规范](docs/design/mihon-desktop-ui/README.md)，再读适用的组件事实表和页面契约；其他页面按同样字段定义本次契约，不机械套用三个样本的业务。
- 实现前固定验收，区分 `SOURCE`、`PROJECT_POLICY` 与 `HTML_ADAPTER`。平台常量按实际消费组件核对，不把 Android 值自动作为 Desktop 值；当前 Desktop 封面 7:10 与 Android Book 2:3 即为已知差异。
- 必做验收不得在实现后改为未实现或不适用以获得通过。行为测试执行真实事件及 wiring，视觉检查记录来源和环境；浏览器原型通过不能代替下文要求的原生测试及正式构建验收。

每项用户可见 capability 必须有入口和反馈；内部基础设施不要求独立 UI，但必须被真实产品链路使用并有集成测试。规划时检查：

1. 用户如何触发？（按钮、菜单、快捷键等）
2. 结果如何反馈？（状态、Toast、对话框等）
3. 危险操作是否需要确认？（AlertDialog）

**用户可见 capability 没有入口或反馈 = 功能未完成；内部基础设施没有 production wiring 或集成测试 = 功能未完成。**

### 复用优先

新增功能前必须检查项目内是否已有相同或相近能力可复用，包括：

- 已有 Use Case、Manager、Repository、Service
- 已有搜索、分页、错误处理、缓存、同步、下载、解析流程
- 已有 Screen、Tab、Composable、导航入口
- 已有数据模型、数据库表、查询、状态管理、测试工具

规划时必须回答：

1. 能否直接复用现有功能？
2. 不能直接复用时，是否应抽取公共能力供新旧功能共用？
3. 新特性应追加到已有链路，还是确实需要独立维护？
4. 若独立实现，必须说明不能复用的技术原因和用户体验原因。

**能复用却另起一套实现，默认不允许。**

## 上游对齐原则

Mihon Desktop 源自 Android Mihon。除平台 API 或技术栈差异确实无法复用外，功能语义、数据模型、状态转换、错误处理和持久化行为应与 Android Mihon 保持一致，不得仅因实现更省事而保留 Desktop 独立重写。

确需平台独立实现时，必须说明不可复用的技术原因，并将差异限制在平台 adapter 内。上游对齐不得删除、降级或改变 Desktop 独有功能；共享逻辑与独有能力冲突时，应抽取共享核心并通过平台扩展保留独有行为。

## 有效验证原则

测试必须执行真实 production 实现及其 wiring。不得使用仅扫描源码文本、检查符号字符串存在或在测试中复制实现逻辑的方式代替行为验证。

Android 与 Desktop 预期一致的行为必须使用共享契约测试覆盖；平台特有行为应使用独立集成测试覆盖。如果 production wiring 损坏后测试仍能通过，则该测试不能作为完成证据。

## 完成报告格式

每个面向用户的 change 或迭代最终完成后必须按以下结构汇报，不得省略：

```markdown
## 【功能特性】
- [功能名称]：用户能看到/使用的变化，说明操作路径和边界
  - 示例：下载队列 → 顶部显示 Pause/Resume FAB 按钮 → 点击暂停/继续所有下载

## 【BUG 修复】
- [bug 描述]：修复前现象 → 修复后行为
  - 示例：下载队列管理按钮不可见 → 按钮已改为 FAB，始终可见

## 【验收清单】
面向用户的验收项使用以下格式：
- [ ] 操作路径 → 预期结果
```

规则：

- 每项都必须描述用户实际可见或可操作的变化，不写纯代码细节作为主要内容。
- 验收清单必须可执行；可在几分钟内手动完成的行为给出操作路径，其余行为给出自动化验证命令或运行时证据。若验收生成了构建产物，必须在对应验收项中以 Markdown 超链接给出可点击的绝对本地路径；不得只写纯文本路径。
- Windows Desktop 构建完成后，完成报告必须引用构建日志中 `Final unpacked EXE:` 输出的实际绝对路径，例如 `[Mihon Desktop.exe](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-unpacked/Mihon%20Desktop.exe)`，并在报告前确认文件存在。不得把 `app-desktop/tmp/`、Gradle `build/` 或其他临时目录中的 EXE 写成交付地址。
- 必须说明功能边界，例如“仅 QUEUED 状态可取消，DOWNLOADING 不可取消”。
- 内部重构无需虚构新增 UI；应报告它保护的既有用户行为、production wiring、自动化验证证据和当前功能边界。
- 拆分的 Task 之间只记录任务状态和验证证据；完整的用户可见完成报告在 change 或迭代最终完成时统一输出。

## 桌面端构建与部署

每次完成桌面端迭代必须使用构建脚本，**不得直接调用 Gradle 构建部署**：

```bash
./scripts/build-desktop.sh           # BUILD +1，构建并验收未打包应用
./scripts/build-desktop.sh feature   # FEATURE +1，BUILD 重置为 1
./scripts/build-desktop.sh stage     # STAGE +1，FEATURE 重置为 0，BUILD 重置为 1
./scripts/build-desktop.sh msi       # 显式生成 MSI，最后重新生成并验收未打包应用
./scripts/build-desktop.sh build-only # 同一未提交 diff 已通过等价全量测试时，仅构建与运行验收
```

`build-only` 只用于当前未提交 diff 已有等价完整 Desktop JVM 测试证据、需要避免收口构建重复测试的情况；
它不得替代批次或阶段要求的全量测试。该模式仍须完成版本分配、正式产物构建、production runtime 验收与最终产物发布。

Windows 构建在运行验收后，会把完整未打包应用发布到
`app-desktop/artifacts/windows/Mihon-Desktop-<完整版本>-unpacked/`。构建日志中的
`Final unpacked EXE:` 是完成报告使用的唯一未打包 EXE 地址；`app-desktop/tmp/` 仅为内部构建和
Test Mode 输入。

## 常用命令

Android 构建身份、签名、产物及验收遵循 [Android 构建与验收规范](docs/architecture/android-build-and-acceptance.md)。正式身份和升级版本唯一来源是 `gradle/android-release.properties`；Release 默认属于本 fork，Debug 使用独立 `.dev` 身份。统一入口的验证进度见[实施记录](docs/evidence/android-build-workflow-2026-09-28.md)。

用户交付走 `scripts/build-android.py candidate`，不能直接把 `app/build/outputs` 中的 APK 当成正式交付。构建不隐式安装，安装不隐式操作应用；用户自行验收时不代操作实体设备。不同正式候选递增 versionCode，普通 Debug/查询/测试不递增。原证书必须连续，凭据不可用时不创建替代密钥。

```bash
# 检查格式（CI 必须通过）
./gradlew spotlessCheck

# 自动修复格式
./gradlew spotlessApply

# 环境与正式签名预检（不构建、不安装）
python scripts/build-android.py check --signing

# 开发 APK；与正式安装隔离
python scripts/build-android.py debug

# 正式候选：R8、外部签名、校验及产物清单；不自动跑全量或安装
python scripts/build-android.py candidate

# 无密钥 CI 只生成明确标注的未签名候选
python scripts/build-android.py candidate --unsigned

# 核对既有候选；有安装授权时才执行独立的 install 命令
python scripts/build-android.py verify --artifact "<APK绝对路径>"
python scripts/build-android.py install --artifact "<APK绝对路径>" --serial "<本次确认的设备>"

# 运行单元测试
./gradlew testReleaseUnitTest

# 运行单个测试类
./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.SomeTest"
```

底层 `:app:assembleRelease` 仍可用于开发，但输出是未签名中间产物。重型 Gradle 验证继续经上文协调器串行执行；统一构建入口已调用协调器，不要再从外层嵌套同一协调器任务。

本 fork 禁止 `-Pinclude-telemetry`、`-Penable-updater`、`-Pdisable-code-shrink`；Gradle 配置会直接拒绝。专项 AEX/EIS/Sync init 脚本仅用于各自隔离验收，不能混用，也不能作为正式交付路径。

## 架构

Mihon 是由 Android 应用、Mihon Desktop 和共享 Kotlin 模块组成的多平台代码库，采用分层架构：

| 模块 | 职责 |
|---|---|
| `app/` | 表现层：Compose 页面、Activity、DI wiring |
| `app-desktop/` | Desktop 表现层、平台 adapter、运行时 wiring 与桌面端测试 |
| `test-desktop/` | Desktop E2E 测试客户端与 Robot API |
| `domain/` | 业务逻辑：用例、领域模型、仓库接口 |
| `data/` | 数据层：SQLDelight 数据库、仓库实现、映射 |
| `presentation-core/` | 跨页面复用的 Compose 组件 |
| `core/common/` | 公共工具与 Kotlin 扩展 |
| `source-api/` | KMP 漫画源抽象，供扩展复用 |
| `source-local/` | 本地文件源 |
| `i18n/` | Moko 字符串资源 |

### 包名

因 Tachiyomi → Mihon 迁移历史，仓库存在多个包名前缀：

- `eu.kanade.tachiyomi.*`：app 模块和多数旧代码
- `tachiyomi.domain.*` / `tachiyomi.data.*`：domain 与 data 模块
- `mihon.domain.*` / `mihon.feature.*`：较新的 Mihon 功能
- `mihon.desktop.*`：Mihon Desktop 的 UI、平台 adapter、运行时与独有能力

### 关键模式

- **依赖注入**：Android 与 Desktop 均使用 Injekt。Android 模块注册在 `app/src/main/java/eu/kanade/tachiyomi/di/`；Desktop wiring 位于 `app-desktop/src/main/kotlin/mihon/desktop/di/` 和 `DesktopUiDependencies.kt`。使用 `Injekt.get<T>()` 获取依赖，使用 `by injectLazy<T>()` 延迟注入。
- **导航**：Android 与 Desktop 均使用 Voyager（`cafe.adriel.voyager`）。Screen 实现 `cafe.adriel.voyager.core.screen.Screen`，导航通过 `Navigator` / `LocalNavigator`。
- **数据库**：SQLDelight + 协程。schema 与 migration 的唯一权威位于 `data/src/commonMain/sqldelight/`，生成查询在 `tachiyomi.data.*.db`；不得重建 `data/src/main/sqldelight/` 镜像。
- **图片加载**：Coil 3，自定义 fetcher / decoder 位于 `app/src/main/java/eu/kanade/tachiyomi/data/coil/`。
- **偏好设置**：`tachiyomi.core.common.preference` 封装 AndroidX DataStore / SharedPreferences。

### 构建逻辑

自定义 Gradle 插件位于 `buildSrc/src/main/kotlin/`：

- `mihon.android.application`：应用基础配置
- `mihon.library`：库模块配置
- `mihon.code.lint`：Spotless + ktlint

依赖版本由 `gradle/*.versions.toml` 管理。

## 测试政策：必须覆盖集成点

仅有 domain 单元测试不够。任何涉及导航、DI wiring、Screen / Tab、HTTP / API 的变更，必须加入对应集成级测试。影响集成点却只有 domain 测试的改动不得合并。

### 1. 导航类型安全测试

**适用场景**：新增或修改 Screen / Tab，或修改 `navigator.push()` / `navigator.replace()`。

**测试要求**：

- 验证传给 `navigator.push()` 的对象符合当前 Voyager 导航上下文：
  - `TabNavigator` 中只能设置 `Tab`：`tabNavigator.current = ...`。
  - 普通 `Screen` 必须进入嵌套 `Navigator`，不能直接作为 Tab。
  - 普通 `Navigator` 中的对象必须实现 `cafe.adriel.voyager.core.screen.Screen`。
- JVM 测试必须实例化每个 Screen / Tab，并断言接口正确：

```kotlin
@Test
fun `MangaDetailScreen 是 Screen 不是 Tab`() {
    val screen = MangaDetailScreen(mangaId = 1L)
    assertThat(screen).isInstanceOf(Screen::class.java)
    assertThat(screen).isNotInstanceOf(Tab::class.java)
}
```

- 每个 `navigator.push()` 调用点都要测试推入类型与导航上下文兼容。
- 若在 `TabNavigator` 内使用 `LocalNavigator`，必须测试确实使用了嵌套 `Navigator`，而不是直接使用 tab navigator。

**常见坑**：Tab 的 `Content()` 中，`LocalNavigator.currentOrThrow` 可能解析到包裹 `TabNavigator` 的父 `Navigator`；若没有父 `Navigator`，向 tab navigator 推入非 Tab 的 Screen 会在运行时 `ClassCastException`。必须用测试验证导航层级。

### 2. DI Wiring 测试

**适用场景**：新增 Injekt 绑定、新增 `Injekt.get<T>()` 调用、修改 DI 模块。

**测试要求**：

- 初始化全部或相关 DI 模块，并断言每个注册类型都能解析。

```kotlin
@Test
fun `所有 DI 绑定都能解析`() {
    AppModule.register()
    DomainModule.register()

    assertNotNull(Injekt.get<GetLibraryManga>())
    assertNotNull(Injekt.get<SourceManager>())
}
```

- 在 Composable 中新增 `Injekt.get<T>()` 时，必须把该类型加入 DI wiring 测试。

### 3. HTTP / API 集成测试（MockWebServer）

**适用场景**：修改 HTTP 客户端、源实现、API 解析、页面加载逻辑。

**测试要求**：

- 使用 `okhttp3.mockwebserver.MockWebServer` 注入真实形状响应，覆盖成功、空数据、错误、畸形 JSON。
- 测试从原始 HTTP 响应到领域对象的完整解析路径，不得 mock parser。

```kotlin
@Test
fun `MangaDex 源能解析真实章节页响应`() {
    server.enqueue(MockResponse().setBody(realPageListJson))
    val pages = source.getPageList(chapter)
    assertThat(pages).isNotEmpty()
    assertThat(pages.first().imageUrl).isNotBlank()
}

@Test
fun `源遇到空页列表不会崩溃`() {
    server.enqueue(MockResponse().setBody("""{"result":"ok","data":[]}"""))
    val pages = source.getPageList(chapter)
    // 应返回空列表或抛出明确异常；
    // 不得静默返回会破坏阅读器的无意义结果。
}
```

最低覆盖：成功响应、空/缺失数据、HTTP 403 / 429 / 500、畸形响应体。

### 4. Screen 实例化冒烟测试

**适用场景**：新增或修改 Screen / Tab。

**测试要求**：在 JVM 上用代表性参数实例化每个 Screen / Tab，捕获序列化问题、默认值缺失和构造器错误。

```kotlin
@Test
fun `所有页面都能实例化`() {
    MangaDetailScreen(mangaId = 1L)
    SourceBrowseScreen(sourceId = 1L)
    DesktopReaderScreen(
        chapterTitle = "Ch 1",
        pageUrls = listOf("https://example.com/1.jpg"),
        isWebtoon = false,
        sourceId = 1L,
        chapterUrl = "/chapter/1",
        chapterId = 1L,
        progressTracker = mockProgressTracker,
    )
}
```

### 5. UI Wiring 变更的红绿 TDD

涉及导航、DI、Screen wiring 时必须严格按以下顺序：

1. **红**：先写覆盖集成点的失败测试（导航 push、DI 解析、HTTP 解析等），并确认失败原因正确。
2. **绿**：写最小实现让测试通过。
3. **重构**：清理后重新运行测试。

以下都属于 UI wiring 变更：

- 新增 Screen / Tab
- 新增或修改 `navigator.push()` / `navigator.replace()`
- 在 Composable 中新增 `Injekt.get<>()` 或 `injectLazy<>()`
- 修改导航层级，例如在 `TabNavigator` 中嵌套 `Navigator`
- 修改源的 HTTP 获取或解析方式

**规则**：新增 `navigator.push()`、`Injekt.get()` 或 HTTP 端点时，必须有对应测试能在其损坏时失败，否则不可合并。

### 6. 合并前测试清单

| 变更类型 | 必须测试 |
|---|---|
| 新增/修改 Screen 或 Tab | Screen 实例化测试 + 导航类型测试 |
| 新增 `navigator.push(X)` | 测试 `X` 与当前导航上下文兼容 |
| Composable 新增 `Injekt.get<T>()` | `T` 纳入 DI wiring 测试 |
| 新增/修改 HTTP 解析 | MockWebServer 成功 + 失败用例 |
| 新增 domain use case | use case 单元测试 |

---

## HTML 双端交互 DEMO

### 位置与用途

- DEMO 位于 `docs/prototypes/multi-device-sync/`，并列审阅入口是 `index.html`；`device.html` 仅用于单端隔离调试。完整交互基线、演示顺序和已知边界以同目录 `README.md` 为准。
- 该目录是 Windows Desktop 与 Android 共用的交互原型资产，可在设计其他双端功能时复用 Mihon 外壳、导航、主题、图标、面板、列表和演示工具栏。新增独立原型时优先在 `docs/prototypes/` 下建立语义清晰的同级目录；只有与多设备同步直接相关的交互才继续写入 `multi-device-sync/`。
- DEMO 只用于确认信息架构、界面状态和操作路径。它不连接真实 GitHub/Git 服务，不代表生产同步、持久化、系统后台任务或跨设备通信已实现，也不能代替 Android/Desktop 构建与运行验收。

### 文件职责

| 文件 | 职责 |
|---|---|
| `index.html`、`preview.js` | Windows 与 Android 并列容器、共享场景和应用外演示工具栏 |
| `device.html`、`app.js` | 单端入口、Mihon 页面与交互 wiring |
| `styles.css`、`ui-view.js` | 双端外观、主题、图标及可复用渲染组件 |
| `sync-interactions.js` | 同步面板及仅用于交互审阅的场景状态 |
| `sync-model.js` | 本地内存操作日志与设备间演示模型 |
| `*.test.cjs` | 模型、双端契约、浏览器布局及交互验证 |

### 开发规范

- 修改前先阅读该目录 `README.md`，确认当前已审核的交互、演示边界和对应源码依据。涉及长期交互规则、入口、状态或已知限制的变化，须同步更新 README。
- 默认同时维护并列的 Windows 与 Android 预览。共享语义、数据和操作结果保持一致；导航、尺寸或平台原生控件确有差异时限制在视图层，并在 README 说明原因。不得为了演示方便改变生产需求语义。
- 优先复用现有外壳、组件、图标和 `data-testid`，避免另建视觉体系。界面应贴合仓库当前 Mihon 实现；需要还原现有界面时先查对应 Compose 源码，不凭印象重画。
- 交互原型可使用满足审阅所需的最小模拟逻辑，但必须清楚区分 UI 场景与同步模型。纯展示场景放在 `sync-interactions.js`；操作因果、设备隔离或队列语义才进入 `sync-model.js`。不要为修饰界面顺带修复或扩张未获要求的模型能力。
- `index.html` 的两个 iframe 共享演示数据，但导航、焦点、滚动、选择、临时通知和计时器保持设备隔离。修改一端或重绘另一端时，不得抢焦点、泄漏未同步操作或重置对方的局部 UI 状态。
- 保持原型完全本地可运行：不引入 CDN、真实账号、真实令牌、远端请求或生产密钥。GitHub 授权只能使用明确标注的本地模拟页和演示数据。
- 临时反馈只在当前面板会话内显示，收起后不回放；危险或批量操作须提供与 Mihon 现有交互一致的确认和结果反馈。无编程背景用户能看到的文案应使用产品语言，不暴露 Git、提交、分支或队列实现细节。
- 与其他会话并行开发时，只编辑和提交本任务涉及的原型文件；已有未提交改动视为其他工作，禁止清理、覆盖或混入提交。

### 打开与验证

可直接打开 `docs/prototypes/multi-device-sync/index.html`。需要 HTTP 预览时，从仓库根目录运行：

```powershell
python -m http.server 50943 --directory docs/prototypes/multi-device-sync
```

验证按改动范围选择，不将 DEMO 变更升级为全量产品构建：

- 纯文案或说明：检查目标页面和 `git diff --check`。
- JavaScript 交互：运行受影响的 `*.test.cjs`，并对修改的脚本执行 `node --check`。
- 双端布局、导航或状态隔离：至少运行对应浏览器测试；共享预览改动包含 `parallel-preview.test.cjs`。
- `sync-model.js` 行为：先按红绿重构更新 `sync-model.test.cjs`，再运行受影响的浏览器交互测试。

浏览器测试使用本机 Chrome 与 `playwright-core`；需要时在当前会话设置 `PLAYWRIGHT_CORE_PATH`。常用完整 DEMO 验证为：

```powershell
node --test docs/prototypes/multi-device-sync/sync-model.test.cjs docs/prototypes/multi-device-sync/ui-view.test.cjs docs/prototypes/multi-device-sync/ui-browser.test.cjs docs/prototypes/multi-device-sync/library-sync.test.cjs docs/prototypes/multi-device-sync/batch-sync.test.cjs docs/prototypes/multi-device-sync/parallel-preview.test.cjs docs/prototypes/multi-device-sync/sync-interactions.test.cjs
```

## 桌面端自动化测试

Mihon Desktop 包含完整 E2E 自动化测试系统。

### 快速命令

```bash
# 运行冒烟测试
./scripts/desktop-smoke-test.sh

# 运行测试模块
./gradlew :test-desktop:test

# 运行全部桌面端测试
./gradlew :app-desktop:jvmTest

# 使用测试模式构建桌面端
./scripts/build-desktop.sh
```

### 测试文档

- 用户指南：`docs/automation/TEST_GUIDE.md`
- API 参考：`docs/automation/API_REFERENCE.md`
- 进度追踪：`docs/automation/TASK_TRACKER.md`

### 关键文件

| 文件 | 说明 |
|---|---|
| `app-desktop/src/main/kotlin/mihon/desktop/test/` | 测试基础设施：TestMode、TestState、HTTP Server |
| `test-desktop/src/main/kotlin/mihon/test/desktop/` | 测试客户端库：Robot 模式、HTTP 客户端 |
| `app-desktop/src/test/kotlin/mihon/desktop/smoke/` | 冒烟测试套件 |

### 测试模式启动

Windows 使用本轮已验收的固定未打包 EXE：

```powershell
& "app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe" --test-mode --test-http-port=8080 --headless
```

macOS 本机或通过 `ssh mbp` / `ssh mbp-lan` 使用应用包内可执行文件：

```bash
"/Applications/Mihon Desktop.app/Contents/MacOS/Mihon Desktop" --test-mode --test-http-port=8080 --headless
```

测试模式提供 HTTP API：

- `GET /test/state`：获取应用状态
- `POST /test/navigate/{screen}`：导航
- `POST /test/action/{action}`：执行动作

Test Mode 不提供截图 API，也不得读取桌面屏幕像素；视觉验证应使用不需要系统录屏权限的
离屏测试，或由平台外部验收工具完成。

详见 `docs/automation/TEST_GUIDE.md`。
