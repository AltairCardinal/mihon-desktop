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

---


## 功能规划原则

**规划任何用户可见 capability 时都必须同时考虑用户界面。**

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
- 验收清单必须可执行；可在几分钟内手动完成的行为给出操作路径，其余行为给出自动化验证命令或运行时证据。
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
```


## 常用命令

```bash
# 检查格式（CI 必须通过）
./gradlew spotlessCheck

# 自动修复格式
./gradlew spotlessApply

# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK（使用 CI 的遥测与更新器参数）
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater

# 运行单元测试
./gradlew testReleaseUnitTest

# 运行单个测试类
./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.SomeTest"
```

构建参数：

- `-Pinclude-telemetry`：启用 Firebase Analytics / Crashlytics。
- `-Penable-updater`：启用应用内更新检查。
- `-Pdisable-code-shrink`：禁用 R8 / ProGuard 压缩。

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
- **数据库**：SQLDelight + 协程。schema 位于 `data/src/main/sqldelight/`，生成查询在 `tachiyomi.data.*.db`。
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
