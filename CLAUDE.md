# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 语言

与用户的所有交流使用中文。确认 bug 已修复时，须用中文明确告知用户。

## 功能规划原则

**所有功能在规划时都要确保用户界面也被考虑到。**

每个后端功能（Use Case、Manager、Repository）都必须有对应的 UI 入口，用户才能使用它。规划时需检查：

1. 用户如何触发该功能？（按钮/菜单/快捷键）
2. 操作结果如何反馈给用户？（状态更新/Toast/对话框）
3. 危险操作是否有确认步骤？（AlertDialog）

**后端实现了但 UI 入口缺失 = 用户完全用不到 = 功能等于未实现。**

## 完成报告格式

每次完成一轮开发后，完成报告必须按以下结构汇报，不得省略：

```
## 【功能特性】
- [功能名称]：用户会看到/用到的具体变化，说明操作路径和边界
  - 示例：下载队列 → 顶部显示 Pause/Resume FAB 按钮 → 点击暂停/继续所有下载

## 【BUG 修复】
- [bug 描述]：修复前的现象 → 修复后的行为
  - 示例：下载队列管理按钮不可见 → 按钮已改为 FAB，始终可见

## 【验收清单】
每项均须用户实际操作确认，格式：
- [ ] 操作路径 → 预期结果
```

规则：
- 每一项必须描述用户**实际能看到或操作的变化**，不写代码层面的实现细节
- 验收清单必须可执行——用户能在 5 分钟内逐条验证
- 功能边界必须说清楚（如：仅 QUEUED 状态可取消，DOWNLOADING 不行）

## Commands

```bash
# Check code formatting (must pass in CI)
./gradlew spotlessCheck

# Auto-fix formatting
./gradlew spotlessApply

# Build debug APK
./gradlew assembleDebug

# Build release APK (with telemetry and updater flags used in CI)
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater

# Run unit tests
./gradlew testReleaseUnitTest

# Run a single test class
./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.SomeTest"
```

Build flags:
- `-Pinclude-telemetry` — enables Firebase Analytics/Crashlytics
- `-Penable-updater` — enables in-app update checker
- `-Pdisable-code-shrink` — disables R8/ProGuard minification

## Architecture

Mihon is a multi-module Android app. Modules follow a layered architecture:

| Module | Purpose |
|---|---|
| `app/` | Presentation layer — Compose screens, Activities, DI wiring |
| `domain/` | Business logic — use cases, domain models, repository interfaces |
| `data/` | Data layer — SQLDelight DB, repository implementations, mappers |
| `presentation-core/` | Reusable Compose components shared across screens |
| `core/common/` | Shared utilities and Kotlin extensions |
| `source-api/` | KMP source abstraction (shared with extensions) |
| `source-local/` | Local file source (reading files from device storage) |
| `i18n/` | String resources via Moko |

### Package naming

The codebase has two package roots due to the Tachiyomi → Mihon fork history:
- `eu.kanade.tachiyomi.*` — app module and most legacy code
- `tachiyomi.domain.*` / `tachiyomi.data.*` — domain and data modules
- `mihon.domain.*` / `mihon.feature.*` — newer Mihon-specific additions

### Key patterns

**Dependency injection:** Injekt (a custom fork). Modules are registered in `app/src/main/java/eu/kanade/tachiyomi/di/`. Use `Injekt.get<T>()` to retrieve dependencies; use `by injectLazy<T>()` for lazy field injection.

**Navigation:** Voyager (`cafe.adriel.voyager`). Screens implement `cafe.adriel.voyager.core.screen.Screen`. Navigation is handled via `LocalNavigator`.

**Database:** SQLDelight with coroutines. SQL schema lives in `data/src/main/sqldelight/`. Generated Kotlin queries are in `tachiyomi.data.*.db`.

**Image loading:** Coil 3 with custom fetchers/decoders in `app/src/main/java/eu/kanade/tachiyomi/data/coil/`.

**Preferences:** `tachiyomi.core.common.preference` wrappers over AndroidX DataStore/SharedPreferences.

### Build logic

Custom Gradle plugins live in `buildSrc/src/main/kotlin/`:
- `mihon.android.application` — base app config
- `mihon.library` — library module config
- `mihon.code.lint` — Spotless + ktlint setup

Dependency versions are managed via version catalogs in `gradle/*.versions.toml`.

## Test Policy — Mandatory Integration Coverage

Domain-only unit tests are necessary but **not sufficient**. Every change that touches navigation, DI wiring, screen classes, or HTTP/API code **must** include integration-level tests as described below. Do not merge changes that only have domain-level unit tests when integration points are affected.

### 1. Navigation Type Safety Tests

**When required:** Any change that adds or modifies a Screen/Tab class, or changes a `navigator.push()` / `navigator.replace()` call.

**What to test:**
- Verify at compile time or in a test that every object passed to `navigator.push()` implements the correct Voyager type for that navigator context:
  - Inside a `TabNavigator` context, only `Tab` objects may be set via `tabNavigator.current = ...`. Regular `Screen` objects must use a separate nested `Navigator`, not the tab navigator.
  - Inside a regular `Navigator`, objects must implement `cafe.adriel.voyager.core.screen.Screen`.
- Write a JVM unit test that instantiates every Screen/Tab class with dummy parameters and asserts the correct interface:

```kotlin
@Test
fun `MangaDetailScreen implements Screen not Tab`() {
    val screen = MangaDetailScreen(mangaId = 1L)
    assertThat(screen).isInstanceOf(Screen::class.java)
    assertThat(screen).isNotInstanceOf(Tab::class.java)
}
```

- For each navigator.push() call site, write a test that verifies the pushed type is compatible with the navigator's expected type. If the code uses `LocalNavigator` inside a `TabNavigator`, the test must verify that a nested `Navigator` is used (not the tab navigator directly).

**Common pitfall (the bug that prompted this policy):** `LocalNavigator.currentOrThrow` inside a Tab's `Content()` resolves to the parent `Navigator` wrapping the `TabNavigator`, if one exists. If no parent `Navigator` exists, `push()` on the tab navigator with a non-Tab `Screen` causes a `ClassCastException` at runtime. Always verify your navigation hierarchy in tests.

### 2. DI Wiring Tests

**When required:** Any change that adds a new Injekt binding, adds a new `Injekt.get<T>()` call, or modifies a DI module.

**What to test:**
- Write a test that initializes all DI modules (or the relevant subset) and resolves every registered type without throwing. This catches missing bindings before runtime.

```kotlin
@Test
fun `all DI bindings resolve without error`() {
    // Initialize modules
    AppModule.register()
    DomainModule.register()
    // ... other modules

    // Assert each expected type resolves
    assertNotNull(Injekt.get<GetLibraryManga>())
    assertNotNull(Injekt.get<SourceManager>())
    // ... every type used in Screen/Tab Content() methods
}
```

- When adding a new `Injekt.get<T>()` call in a Composable, add the type to the DI wiring test.

### 3. HTTP / API Integration Tests (MockWebServer)

**When required:** Any change that touches HTTP client code, source implementations, API response parsing, or page loading logic.

**What to test:**
- Use `okhttp3.mockwebserver.MockWebServer` to enqueue realistic API responses (success, empty, error, malformed JSON).
- Test the **full parse path** from raw HTTP response to domain objects. Do not mock the parser — mock the server.

```kotlin
@Test
fun `MangaDex source parses chapter pages from real response shape`() {
    server.enqueue(MockResponse().setBody(realPageListJson))
    val pages = source.getPageList(chapter)
    assertThat(pages).isNotEmpty()
    assertThat(pages.first().imageUrl).isNotBlank()
}

@Test
fun `source handles empty page list without crashing`() {
    server.enqueue(MockResponse().setBody("""{"result":"ok","data":[]}"""))
    val pages = source.getPageList(chapter)
    // Should either return empty list or throw a descriptive exception,
    // NOT return an empty list that silently breaks the reader.
}
```

- Cover at minimum: successful response, empty/missing data, HTTP error codes (403, 429, 500), and malformed response bodies.

### 4. Screen Instantiation Smoke Tests

**When required:** Any change that adds or modifies a Screen or Tab class.

**What to test:**
- Instantiate every Screen and Tab with representative parameters on the JVM. This catches serialization issues, missing default values, and broken constructors.

```kotlin
@Test
fun `all screens can be instantiated`() {
    // These must not throw
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

### 5. Red-Green TDD Rules for UI Wiring Changes

When making a change that involves navigation, DI, or screen wiring, follow this sequence **strictly**:

1. **Red:** Write a failing test first that exercises the integration point (navigation push, DI resolution, or HTTP parse). Run it and confirm it fails for the right reason.
2. **Green:** Implement the minimal code to make the test pass.
3. **Refactor:** Clean up, then re-run all tests.

**What counts as a "UI wiring change":**
- Adding a new Screen or Tab class
- Adding or changing a `navigator.push()` / `navigator.replace()` call
- Adding a new `Injekt.get<>()` or `injectLazy<>()` call in a Composable
- Changing the navigation hierarchy (e.g., nesting a `Navigator` inside a `TabNavigator`)
- Modifying how a source fetches or parses HTTP responses

**The rule:** If your change introduces a new call to `navigator.push()`, `Injekt.get()`, or an HTTP endpoint, and you do not have a corresponding test that would fail if that call were broken, **the change is not ready to merge**.

### 6. Test Checklist Before Merge

For every PR, verify the following. If a row applies and has no test, the PR is blocked:

| Change type | Required test |
|---|---|
| New/modified Screen or Tab class | Screen instantiation test + navigation type test |
| New `navigator.push(X)` call | Test that X is compatible with the navigator context |
| New `Injekt.get<T>()` in a Composable | T is covered in DI wiring test |
| New/modified HTTP parsing code | MockWebServer test with success + failure cases |
| New domain use case | Unit test for the use case (existing practice) |
