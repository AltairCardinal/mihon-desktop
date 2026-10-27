# 验证范围、TDD 接线与失败收口

本页是[根规则](../../AGENTS.md)按动作加载的项目约束。仅阅读本次动作适用章节；代码命令与普通路径以仓库根为基准，Markdown 链接按本页位置解析。

设计或修改行为测试、确定验证范围、执行 Gradle/产品验证或分析失败前使用。测试示例用于对应风险，不是无条件执行全模块或全量的命令清单。

### 分层验证

- 红绿循环：仅运行当前行为的 focused tests；重构后复验同一范围。
- 任务／功能批次完成：运行受影响的单元、集成、wiring 与格式检查。
- 阶段完成：运行该阶段受影响范围的回归测试。
- 没有 roadmap 的独立修复：以对应回归测试、受影响的集成测试及格式检查收口；有具体未覆盖风险时按下文申请最小范围扩验。
- 默认按日常体验交付：对本次变更选择最小充分验证，覆盖真实 production 行为、接线和受影响平台的一条用户路径。
- 只有用户明确指定稳定发布/完整验收里程碑后，才按该里程碑计划执行一次完整发布矩阵、Test Mode、正式构建和适用平台运行验收。日常流程仍须当次完成行为 TDD、真实集成接线，以及数据、安全、协议、发布身份等高风险专项验证；显式发布里程碑不豁免这些门槛。

完整模块测试是指未限定测试类或用例的模块测试，例如未使用
`--tests` 限定范围的 `:app-desktop:jvmTest`。
全量测试包括 roadmap 最终验证矩阵要求的完整模块测试组合。
命令是否经构建脚本或协调器包装，不改变其验证范围。

单个任务、功能批次、阶段、roadmap 实施项全部完成、会话结束、交接或提交，均不自动触发完整模块测试、全量测试、`finalParityAudit` 或正式发布构建，也不构成用户明确指定的发布里程碑。不得以拆分会话、将任务称为“迭代”或改用构建脚本绕过本规则。

完整模块测试可用于具体风险所需的最小模块范围，不等同全量测试或完整发布矩阵。日常任何超出 focused 的扩大验证，以及里程碑中的完整/全量重跑，均须先以证据说明实际变化、未覆盖风险、为何较小范围不够、拟执行的最小范围及成本，并取得用户明确批准；模块级验证足够时不得扩大至仓库全量。完整发布矩阵仍只在用户明确指定的稳定发布/完整验收里程碑中执行。

已有 roadmap 如安排了未被用户明确选择的完整发布矩阵，执行前应指出冲突并按本节调整；用户明确选择里程碑后，完整矩阵也只执行计划所需的一次，额度是上限，不是必做次数。

### 常见变更的验证范围

| 变更 | 日常体验的最小充分验证 | 需要当次增加的覆盖 |
|---|---|---|
| 文案、说明或局部视觉 | 目标文案/页面与 `git diff --check`；纯文档不跑产品测试 | 共享样式、布局或主题机制变化时覆盖受影响组件和实际尺寸/主题 |
| 给已有功能增加按钮或入口 | 真实点击 → 既有动作 → 用户反馈；导航或 DI 改动补真实接线测试，并在受影响平台走通该入口 | 新状态、危险/批量动作、公共接线变化时补失败路径、确认/反馈及相关集成覆盖 |
| 数据迁移、同步、凭据或删除 | 针对真实旧数据/持久化的行为测试、失败恢复和身份边界验证；本次使用前完成必要独立审查 | 按具体迁移风险覆盖旧版、平台、回滚与发布 runtime；不得将关键正确性留待定型后补 |
| 明确里程碑中的首次完整测试失败及局部修复 | 保留完整首次结果，分类失败；修复后运行相关回归、集成和格式检查，复用未受影响的有效证据 | 只有 focused 覆盖不了具体剩余风险时才提出最小扩大验证；相关失败关闭且影响范围清楚即可收口，不因“修过代码”重复申请全量 |

### 修复收口与扩大验证门槛

- 测试范围按行为、依赖及风险确定，不按修改行数确定。默认选择覆盖真实 production 行为及集成点的最小充分范围；小改动涉及公共基础设施时仍须评估传播范围。
- 全量测试失败后保留完整结果，汇总失败项并判断关联。先完成已知阻塞的诊断、修复和相关验证，再统一评估剩余风险；不得每修复一项就运行或申请一次全量测试。必要的 focused 红绿循环不消耗全量次数，显著增加成本时仍遵守流程预算。
- 未受后续改动影响的通过结果可以复用。记录基线代码版本及未提交差异、测试命令和结果、后续改动及影响范围、补充测试结果。完整测试已执行且基线有效时，基线加相关局部补验证据可以支持收口；不得将组合证据描述为“最终代码重新通过了全量测试”。
- 已执行计划要求的首次完整测试后，其失败项可以通过修复及相关补验关闭。相关回归、集成测试通过，且没有未解释的相关失败或影响范围疑点时，结束修复验证；无关失败如实报告，不自动扩大修复范围，也不得宣称全部测试通过。尚未完成执行的测试范围不能视为已有通过证据。
- “代码又改了”“上次全量失败”“为了保险”、提交或会话结束，均不能单独作为扩大验证申请理由。一次批准不授权以后每次修复重复执行；无新风险证据时不得反复提出同一扩大验证申请。

### Gradle 生命周期

- 同一 worktree 的重型 Gradle 验证由一个协调者串行执行。一次性等待使用 `python scripts/gradle-coordinator.py run --key <name> -- <gradle command>`；需分离启动时使用 `start`，再用 `wait/status` 查询。
- 外层等待超时不代表 Gradle 结束；先查询协调器状态，仍为 `STARTING/RUNNING` 时不得启动第二个 Gradle。
- 需要终止时只停止协调器记录的进程树，不使用全局 Java/Gradle 进程清理。

### 格式检查

```bash
# 检查格式（CI 必须通过）
./gradlew spotlessCheck

# 自动修复格式
./gradlew spotlessApply
```

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
