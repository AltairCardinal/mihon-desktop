# Mihon Desktop 竞品差距详细实现方案

> 来源：COMPARISON.md §12.3 综合评估中识别的 5 项落后领域
> 关联：ROADMAP.md 阶段 26

---

## 概述

与 Suwayomi、Mangayomi、YACReader、OpenComic、Kavita 等竞品对比，Mihon Desktop 在以下 5 个方面落后：

| # | 差距 | 影响程度 | 工作量 | 优先级 |
|---|------|---------|--------|--------|
| 1 | 图片渲染性能 | 用户体验核心 | 大 | 最高 |
| 2 | Cookie 持久化 | 功能缺失 | 小（~50 行） | 高 |
| 3 | 扩展容错 | 稳定性风险 | 小（~80 行） | 高 |
| 4 | 状态管理成熟度 | 可维护性 | 大 | 中 |
| 5 | CI/CD 自动化 | 质量保障 | 小（~30 行 YAML） | 高 |

---

## 1. 图片渲染性能

### 1.1 问题现状

| 维度 | 竞品 | Mihon Desktop |
|------|------|--------------|
| 图片解码 | libjpeg-turbo / Skia / Qt native（SIMD 加速） | `javax.imageio`（纯 Java，无 SIMD） |
| 预加载 | 前后 N 页缓存 | 无——翻页后才开始加载 |
| 大图处理 | tiling / LOD / mmap | 全量加载到内存 |
| 裁边/分割 | 从已解码缓存裁切 | 重新从 URL 解码整张图 |

### 1.2 实现方案

#### 1.2.1 Skia 原生图片解码

**原理**：Compose Desktop 的传递依赖 Skiko 已包含 Skia 的完整图片编解码器（libjpeg-turbo + libpng + libwebp），但 JetBrains 未在 Compose 的 `ImageBitmap` API 中暴露它。我们直接调用 `org.jetbrains.skia` 包下的底层 API。

**新文件** `app-desktop/src/main/kotlin/mihon/desktop/reader/SkiaImageDecoder.kt`

```kotlin
package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.net.URL

object SkiaImageDecoder {

    /** 解码完整图片 */
    fun decode(bytes: ByteArray): ImageBitmap {
        val skiaImage = SkiaImage.makeFromEncoded(bytes)
        return skiaImage.toComposeImageBitmap()
    }

    /** 解码并降采样（大图优化） */
    fun decodeDownsampled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): ImageBitmap {
        val data = Data.makeFromBytes(bytes)
        val codec = Codec.makeFromData(data)
        val sampleSize = calculateSampleSize(codec.width, codec.height, maxWidth, maxHeight)

        if (sampleSize <= 1) {
            return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }

        val targetWidth = codec.width / sampleSize
        val targetHeight = codec.height / sampleSize
        val info = ImageInfo(targetWidth, targetHeight, ColorType.N32, ColorAlphaType.PREMUL)
        val bitmap = Bitmap().apply { allocPixels(info) }
        codec.readPixels(bitmap, sampleSize)
        return bitmap.toComposeImageBitmap()
    }

    /** 获取图片尺寸（不解码像素） */
    fun peekSize(bytes: ByteArray): Pair<Int, Int> {
        val data = Data.makeFromBytes(bytes)
        val codec = Codec.makeFromData(data)
        return codec.width to codec.height
    }

    fun calculateSampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        var sampleSize = 1
        while (srcW / (sampleSize * 2) >= dstW && srcH / (sampleSize * 2) >= dstH) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
```

**预估性能提升**：

| 操作 | javax.imageio | Skia | 提升 |
|------|-------------|------|------|
| 2MB JPEG 解码（4000×3000） | ~100-150ms | ~20-40ms | **3-5x** |
| 500KB WebP 解码 | ~80-120ms | ~15-30ms | **4-5x** |
| 8000×6000 图降采样到 2000×1500 | ~200ms（全量解码后缩放） | ~30ms（解码时降采样） | **6-7x** |

#### 1.2.2 相邻页预加载

**新文件** `app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt`

```kotlin
package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class PagePreloader(
    private val windowSize: Int = 3,  // 前后各预加载页数
    private val scope: CoroutineScope,
) {
    private val cache = ConcurrentHashMap<Int, ImageBitmap>()
    private val loading = ConcurrentHashMap<Int, Job>()

    /**
     * 当前页变化时调用，触发预加载窗口更新。
     */
    fun onPageChanged(currentPage: Int, pageUrls: List<String>, viewWidth: Int, viewHeight: Int) {
        val range = (currentPage - windowSize)..(currentPage + windowSize)

        // 启动缺失页的预加载
        for (i in range) {
            if (i in pageUrls.indices && i !in cache && i !in loading) {
                val url = pageUrls[i]
                if (url.isBlank()) continue
                loading[i] = scope.launch(Dispatchers.IO) {
                    try {
                        val bytes = readBytes(url)
                        val bitmap = SkiaImageDecoder.decodeDownsampled(bytes, viewWidth, viewHeight)
                        cache[i] = bitmap
                    } catch (_: Exception) {
                        // 预加载失败不阻塞，翻到该页时走常规加载
                    } finally {
                        loading.remove(i)
                    }
                }
            }
        }

        // 清理超出窗口的缓存
        val keysToRemove = cache.keys().toList().filter { it !in range }
        keysToRemove.forEach { cache.remove(it) }

        // 取消超出窗口的加载任务
        val jobsToCancel = loading.keys().toList().filter { it !in range }
        jobsToCancel.forEach { loading.remove(it)?.cancel() }
    }

    /** 获取已预加载的页面，返回 null 表示未缓存 */
    fun get(page: Int): ImageBitmap? = cache[page]

    fun clear() {
        loading.values.forEach { it.cancel() }
        loading.clear()
        cache.clear()
    }

    private fun readBytes(url: String): ByteArray {
        return if (url.startsWith("file:")) {
            java.io.File(java.net.URI(url)).readBytes()
        } else {
            java.net.URL(url).readBytes()
        }
    }
}
```

**集成方式**：在 `DesktopReaderScreen` 中创建 `PagePreloader` 实例，每次 `currentPage` 变化时调用 `onPageChanged()`。`ZoomablePageBox` 先检查 `preloader.get(page)`，命中则直接显示 `Image(bitmap = ...)`，未命中则走 Coil `rememberAsyncImagePainter` 常规路径。

#### 1.2.3 裁边/分割复用已解码数据

**当前问题**：`loadAndCrop()` 和 `loadSplitHalf()` 在 `ZoomablePageBox.kt` 中用 `ImageIO.read(URL(url))` 重新解码整张图。

**修改** `ZoomablePageBox.kt`：

```kotlin
// 改前：从 URL 重新解码
croppedBitmap = withContext(Dispatchers.IO) {
    loadSplitHalf(url, splitHalf)  // 内部调用 ImageIO.read(URL(url))
}

// 改后：从 Skia 已解码数据裁切
val s = painterState
if (s is AsyncImagePainter.State.Success) {
    croppedBitmap = withContext(Dispatchers.Default) {
        val skiaBitmap = s.result.image.toSkiaBitmap()
        cropFromSkiaBitmap(skiaBitmap, splitHalf)  // 纯内存操作
    }
}
```

新增 `cropFromSkiaBitmap()` 工具函数：

```kotlin
fun cropFromSkiaBitmap(bitmap: SkiaBitmap, half: PageSplitHalf): ImageBitmap {
    val bounds = splitBounds(bitmap.width, bitmap.height, half)
    val cropped = Bitmap().apply {
        allocPixels(ImageInfo(bounds.width, bounds.height, bitmap.colorType, bitmap.alphaType))
    }
    bitmap.readPixels(cropped, bounds.x, bounds.y)
    return cropped.toComposeImageBitmap()
}
```

**收益**：裁边/分割延迟从 ~100-200ms 降到 ~5-10ms（消除 I/O + 重复解码）。

#### 1.2.4 大图降采样

在 `ZoomablePageBox` 中，根据视口大小传递 `maxWidth`/`maxHeight` 给解码器：

```kotlin
// DesktopReaderScreen 获取窗口尺寸
val windowInfo = LocalWindowInfo.current
val viewWidth = windowInfo.containerSize.width
val viewHeight = windowInfo.containerSize.height

// 传递给预加载器和 ZoomablePageBox
preloader.onPageChanged(currentPage, pageUrls, viewWidth, viewHeight)
```

8000×6000 图在 1920×1080 屏幕上解码为 ~2000×1500，内存从 ~183MB 降到 ~12MB。

### 1.3 修改文件清单

| 文件 | 操作 |
|------|------|
| `app-desktop/.../reader/SkiaImageDecoder.kt` | **新建** |
| `app-desktop/.../reader/SkiaImageDecoderTest.kt` | **新建** |
| `app-desktop/.../reader/PagePreloader.kt` | **新建** |
| `app-desktop/.../reader/PagePreloaderTest.kt` | **新建** |
| `app-desktop/.../ui/reader/ZoomablePageBox.kt` | **修改** — 集成 Skia 解码 + 预加载 + 裁切复用 |
| `app-desktop/.../ui/reader/DesktopReaderScreen.kt` | **修改** — 初始化预加载器 |
| `app-desktop/.../ui/reader/WebtoonViewer.kt` | **修改** — 添加 `beyondBoundsItemCount = 3` |

### 1.4 TDD 计划

| # | 测试 | 验证点 |
|---|------|--------|
| 1 | `SkiaImageDecoder.decode()` 正确解码 JPEG | 输出 ImageBitmap 尺寸匹配原图 |
| 2 | `SkiaImageDecoder.decode()` 正确解码 PNG | 同上 |
| 3 | `SkiaImageDecoder.decode()` 正确解码 WebP | 同上 |
| 4 | `decodeDownsampled()` 降采样 | 4000×3000 + maxWidth=1000 → 输出 1000×750 |
| 5 | `peekSize()` 不解码像素 | 返回正确尺寸，耗时 <5ms |
| 6 | `calculateSampleSize()` 边界值 | src=dst → sampleSize=1 |
| 7 | `PagePreloader.get()` 预加载后返回非 null | 调用 onPageChanged 后 get 命中 |
| 8 | `PagePreloader` 窗口外缓存被清理 | 翻到 page 10 后 page 1 的缓存被移除 |

---

## 2. Cookie 持久化

### 2.1 问题现状

`DesktopCookieJar` 使用 `ConcurrentHashMap<String, MutableMap<String, Cookie>>` 纯内存存储。应用重启后所有 cookie 丢失，用户手动导入的 `cf_clearance` cookie 需要重新操作。

竞品对比：
- Suwayomi：持久化到 SQLite
- Mangayomi：持久化到文件
- OpenComic：Chromium cookie store 自动持久化

### 2.2 实现方案

在 `DesktopCookieJar` 中增加 JSON 序列化/反序列化，持久化到 `~/.mihon/cookies.json`。

**修改文件** `core/common/src/jvmMain/kotlin/eu/kanade/tachiyomi/network/DesktopCookieJar.kt`

序列化格式：

```json
{
  "example.com": [
    {
      "name": "cf_clearance",
      "value": "abc123",
      "domain": "example.com",
      "path": "/",
      "expiresAt": 1743206400000,
      "secure": true,
      "httpOnly": true
    }
  ]
}
```

新增方法：

```kotlin
class DesktopCookieJar(
    private val persistFile: File = File(System.getProperty("user.home"), ".mihon/cookies.json"),
) : CookieJar {

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    init {
        loadFromDisk()
    }

    // ... 现有方法不变 ...

    // 在 saveFromResponse / addManual / remove / clear 的末尾调用
    private fun persistToDisk() {
        try {
            val data = cookieStore.mapValues { (_, cookies) ->
                cookies.values.map { cookie ->
                    SerializableCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = cookie.domain,
                        path = cookie.path,
                        expiresAt = cookie.expiresAt,
                        secure = cookie.secure,
                        httpOnly = cookie.httpOnly,
                    )
                }
            }
            persistFile.parentFile?.mkdirs()
            persistFile.writeText(json.encodeToString(data))
        } catch (_: Exception) {
            // 持久化失败不影响运行
        }
    }

    private fun loadFromDisk() {
        try {
            if (!persistFile.exists()) return
            val data: Map<String, List<SerializableCookie>> =
                json.decodeFromString(persistFile.readText())
            for ((domain, cookies) in data) {
                val map = cookieStore.getOrPut(domain) { mutableMapOf() }
                for (sc in cookies) {
                    // 跳过已过期的 cookie
                    if (sc.expiresAt < System.currentTimeMillis()) continue
                    map[sc.name] = sc.toOkHttpCookie()
                }
            }
        } catch (_: Exception) {
            // 加载失败清空，从干净状态开始
        }
    }
}

@Serializable
private data class SerializableCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAt: Long = 0L,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
) {
    fun toOkHttpCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .apply {
            if (expiresAt > 0L) expiresAt(expiresAt)
            if (secure) secure()
            if (httpOnly) httpOnly()
        }
        .build()
}
```

**写入时机**：每次 `saveFromResponse`、`addManual`、`remove`、`clear` 调用后异步持久化。使用 debounce（500ms）避免高频写入。

### 2.3 修改文件清单

| 文件 | 操作 |
|------|------|
| `core/common/src/jvmMain/.../DesktopCookieJar.kt` | **修改** — 增加持久化 |
| `core/common/src/jvmTest/.../DesktopCookieJarTest.kt` | **修改** — 增加持久化测试 |

### 2.4 TDD 计划

| # | 测试 | 验证点 |
|---|------|--------|
| 1 | 保存 cookie 后文件存在 | `persistFile.exists() == true` |
| 2 | 重新创建 CookieJar 实例后 cookie 恢复 | `loadForRequest()` 返回之前保存的 cookie |
| 3 | 过期 cookie 不被加载 | `expiresAt < now` 的 cookie 被跳过 |
| 4 | `clear()` 后文件为空或删除 | 文件内容为 `{}` |
| 5 | 文件损坏时不崩溃 | 写入非 JSON 内容后仍能正常初始化 |

---

## 3. 扩展容错

### 3.1 问题现状

当前 8 个扩展调用点全部有 `try-catch`，全部在协程中运行，但**没有任何一个设置了超时**。

风险场景：
- 扩展的 `getPageList()` 连接到一个响应极慢的服务器 → 协程挂起数分钟 → 用户看到永久加载圈
- 扩展的 `popularMangaParse()` 进入死循环（bug）→ 协程永不返回 → UI 线程因状态未更新而冻结
- 扩展的 HTTP 请求遇到连接超时但无读超时 → OkHttp 默认 30s 读超时，但解析阶段无限制

### 3.2 实现方案

创建一个统一的扩展调用包装器：

**新文件** `app-desktop/src/main/kotlin/mihon/desktop/extension/SourceCallWrapper.kt`

```kotlin
package mihon.desktop.extension

import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 统一的扩展方法调用包装器。
 * 为所有扩展调用提供超时保护和错误归一化。
 */
object SourceCallWrapper {

    /** 默认超时：30 秒 */
    val DEFAULT_TIMEOUT: Duration = 30.seconds

    /** 页面列表超时（下载场景可能更长）：60 秒 */
    val PAGE_LIST_TIMEOUT: Duration = 60.seconds

    /**
     * 在超时保护下调用扩展方法。
     * @param timeout 超时时长
     * @param block 要执行的扩展调用
     * @return SourceCallResult.Success 或 SourceCallResult.Failure
     */
    suspend fun <T> call(
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend () -> T,
    ): SourceCallResult<T> {
        return try {
            val result = withTimeout(timeout) { block() }
            SourceCallResult.Success(result)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            SourceCallResult.Failure("Request timed out after $timeout")
        } catch (e: Exception) {
            SourceCallResult.Failure(e.message ?: "Unknown error")
        }
    }
}

sealed interface SourceCallResult<out T> {
    data class Success<T>(val data: T) : SourceCallResult<T>
    data class Failure(val message: String) : SourceCallResult<Nothing>
}
```

**应用到 8 个调用点**：

```kotlin
// 改前（SourceBrowseScreen.kt）
try {
    val result = source.getPopularManga(page)
    mangas.addAll(result.mangas)
} catch (e: Exception) {
    errorMessage = e.message
}

// 改后
when (val result = SourceCallWrapper.call { source.getPopularManga(page) }) {
    is SourceCallResult.Success -> mangas.addAll(result.data.mangas)
    is SourceCallResult.Failure -> errorMessage = result.message
}
```

**8 个调用点及其建议超时**：

| 文件 | 方法 | 超时 |
|------|------|------|
| `SaveSourceMangaForDetails.kt` | `getMangaDetails()` + `getChapterList()` | 30s |
| `SourceBrowseScreen.kt` | `getPopularManga()` / `getSearchManga()` / `getLatestUpdates()` | 30s |
| `GlobalSearchScreen.kt` | `getSearchManga()` | 15s（并行查询多源，单源超时不应太长） |
| `DesktopReaderScreen.kt` | `getPageList()` | 60s |
| `DesktopDownloadManager.kt` | `getPageList()` | 60s |
| `LibraryUpdateChecker.kt` | `getChapterList()` | 30s |
| `MigrationSearchScreen.kt` | `getSearchManga()` + `getChapterList()` | 30s |
| `LibraryUpdateScheduler.kt` | 通过 `LibraryUpdateChecker` 间接调用 | 已由 Checker 覆盖 |

### 3.3 修改文件清单

| 文件 | 操作 |
|------|------|
| `app-desktop/.../extension/SourceCallWrapper.kt` | **新建** |
| `app-desktop/.../extension/SourceCallWrapperTest.kt` | **新建** |
| `app-desktop/.../domain/SaveSourceMangaForDetails.kt` | **修改** — 接入 wrapper |
| `app-desktop/.../ui/browse/SourceBrowseScreen.kt` | **修改** — 接入 wrapper |
| `app-desktop/.../ui/browse/GlobalSearchScreen.kt` | **修改** — 接入 wrapper |
| `app-desktop/.../ui/reader/DesktopReaderScreen.kt` | **修改** — 接入 wrapper |
| `app-desktop/.../download/DesktopDownloadManager.kt` | **修改** — 接入 wrapper |
| `app-desktop/.../domain/LibraryUpdateChecker.kt` | **修改** — 接入 wrapper |
| `app-desktop/.../ui/migration/MigrationSearchScreen.kt` | **修改** — 接入 wrapper |

### 3.4 TDD 计划

| # | 测试 | 验证点 |
|---|------|--------|
| 1 | 正常调用返回 Success | `SourceCallWrapper.call { "ok" }` → `Success("ok")` |
| 2 | 异常返回 Failure | `call { error("boom") }` → `Failure("boom")` |
| 3 | 超时返回 Failure | `call(1.seconds) { delay(5.seconds) }` → `Failure("timed out")` |
| 4 | 超时不阻塞调用方 | 验证超时后协程立即恢复，不等待 block 完成 |

---

## 4. 状态管理成熟度

### 4.1 问题现状

所有 Screen 的状态直接用 `remember { mutableStateOf() }` 内联在 `@Composable Content()` 函数中。3 个最膨胀的 Screen：

| Screen | 行数 | 状态变量数 | 关注点数 |
|--------|------|-----------|---------|
| `DesktopReaderScreen` | ~400 | 15+ | 5（页面加载、缩放、进度、键盘、设置） |
| `MangaDetailScreen` | ~350 | 12+ | 4（漫画信息、章节列表、下载、过滤） |
| `LibraryTab` | ~300 | 10+ | 4（搜索、过滤、选择、分类对话框） |

### 4.2 实现方案

引入 Voyager 的 `ScreenModel` 模式。每个 ScreenModel：
- 持有 `MutableStateFlow<UiState>` 作为单一状态源
- 提供 `fun onEvent(event: UiEvent)` 处理用户操作
- UI 层通过 `val state by screenModel.state.collectAsState()` 观察

以 `ReaderScreenModel` 为例：

**新文件** `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderScreenModel.kt`

```kotlin
package mihon.desktop.ui.reader

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReaderScreenModel(
    private val progressTracker: ReaderProgressTracker,
    private val readerPrefs: ReaderPreferences,
) : ScreenModel {

    data class State(
        val pageUrls: List<String> = emptyList(),
        val currentPage: Int = 0,
        val totalPages: Int = 0,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val readingMode: ReadingMode = ReadingMode.LTR,
        val zoomState: ZoomState = ZoomState(),
        val cropBorders: Boolean = false,
        val autoSplitPages: Boolean = false,
        val showSettings: Boolean = false,
        // ... 其余状态
    )

    sealed interface Event {
        data class PageChanged(val page: Int) : Event
        data class ZoomChanged(val zoom: ZoomState) : Event
        data object ToggleSettings : Event
        data class ReadingModeChanged(val mode: ReadingMode) : Event
        data object NextChapter : Event
        data object PrevChapter : Event
        // ... 其余事件
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onEvent(event: Event) {
        when (event) {
            is Event.PageChanged -> {
                _state.value = _state.value.copy(currentPage = event.page)
                progressTracker.onPageChanged(event.page)
            }
            is Event.ZoomChanged -> {
                _state.value = _state.value.copy(zoomState = event.zoom)
            }
            // ... 其余事件处理
        }
    }
}
```

**UI 层（简化后的 DesktopReaderScreen）**：

```kotlin
@Composable
override fun Content() {
    val screenModel = rememberScreenModel { ReaderScreenModel(progressTracker, readerPrefs) }
    val state by screenModel.state.collectAsState()

    // 纯 UI 渲染，不含业务逻辑
    Scaffold { padding ->
        when {
            state.isLoading -> LoadingIndicator()
            state.errorMessage != null -> ErrorScreen(state.errorMessage!!)
            else -> ReaderContent(
                state = state,
                onPageChanged = { screenModel.onEvent(Event.PageChanged(it)) },
                onZoomChanged = { screenModel.onEvent(Event.ZoomChanged(it)) },
                // ...
            )
        }
    }
}
```

### 4.3 实施顺序

按膨胀程度逐个重构，每个 ScreenModel 独立提交：

1. **ReaderScreenModel**（最膨胀、最复杂）
2. **MangaDetailScreenModel**
3. **LibraryScreenModel**

其余 Screen（设置页、扩展列表等）状态简单，不重构。

### 4.4 修改文件清单

| 文件 | 操作 |
|------|------|
| `app-desktop/.../ui/reader/ReaderScreenModel.kt` | **新建** |
| `app-desktop/.../ui/reader/ReaderScreenModelTest.kt` | **新建** |
| `app-desktop/.../ui/reader/DesktopReaderScreen.kt` | **修改** — 状态逻辑移入 ScreenModel |
| `app-desktop/.../ui/library/MangaDetailScreenModel.kt` | **新建** |
| `app-desktop/.../ui/library/MangaDetailScreenModelTest.kt` | **新建** |
| `app-desktop/.../ui/library/MangaDetailScreen.kt` | **修改** |
| `app-desktop/.../ui/library/LibraryScreenModel.kt` | **新建** |
| `app-desktop/.../ui/library/LibraryScreenModelTest.kt` | **新建** |
| `app-desktop/.../ui/library/LibraryTab.kt` | **修改** |

### 4.5 TDD 计划

每个 ScreenModel 的测试重点是**状态转换**：

| # | 测试 | 验证点 |
|---|------|--------|
| 1 | 初始状态 | `state.value.isLoading == true`，`currentPage == 0` |
| 2 | PageChanged 事件 | 发送 `PageChanged(5)` → `state.value.currentPage == 5` |
| 3 | 进度追踪联动 | `PageChanged` 触发 `progressTracker.onPageChanged()` |
| 4 | 错误状态 | 加载失败 → `errorMessage != null`，`isLoading == false` |
| 5 | 设置面板切换 | `ToggleSettings` → `showSettings` 翻转 |

---

## 5. CI/CD 自动化

### 5.1 问题现状

Desktop 端无 CI——测试仅在本地 `./scripts/build-desktop.sh` 中运行。已有测试可能在重构后静默失败，无人知晓。

### 5.2 实现方案

**新文件** `.github/workflows/desktop-ci.yml`

```yaml
name: Desktop CI

on:
  push:
    paths:
      - 'app-desktop/**'
      - 'core/common/src/jvmMain/**'
      - 'core/common/src/jvmTest/**'
      - 'source-api/src/jvmMain/**'
      - 'source-api/src/commonMain/**'
      - 'domain/**'
      - 'data/**'
  pull_request:
    paths:
      - 'app-desktop/**'

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - uses: gradle/actions/setup-gradle@v4

      - name: Run Desktop tests
        run: ./gradlew :app-desktop:jvmTest

      - name: Check code formatting
        run: ./gradlew spotlessCheck

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: desktop-test-results
          path: app-desktop/build/reports/tests/
```

### 5.3 额外：构建状态徽章

在 `README.md` 中添加：

```markdown
![Desktop CI](https://github.com/<owner>/<repo>/actions/workflows/desktop-ci.yml/badge.svg)
```

### 5.4 修改文件清单

| 文件 | 操作 |
|------|------|
| `.github/workflows/desktop-ci.yml` | **新建** |

---

## 执行顺序总览

按投入产出比排序：

```
1. CI/CD（5.2）             ← 30 行 YAML，立即收益，0 风险
2. Cookie 持久化（2.2）      ← ~50 行代码，立即解决用户痛点
3. 扩展容错（3.2）           ← ~80 行代码，防止应用冻结
4. Skia 解码器（1.2.1）      ← ~60 行代码，阅读体验质变
5. 预加载器（1.2.2）         ← ~80 行代码，翻页零等待
6. 裁切复用（1.2.3）         ← ~30 行修改
7. 大图降采样（1.2.4）       ← 与 Skia 解码器一起完成
8. 状态管理重构（4.2）       ← 工作量最大，最后做
```

前 3 项合计 ~160 行代码，可在一天内完成，立即消除与竞品最关键的 3 个差距。

---

## 验证方式

| # | 验证项 | 操作 |
|---|--------|------|
| 1 | CI 工作 | push 代码后 GitHub Actions 自动运行测试 |
| 2 | Cookie 持久化 | 手动导入 cf_clearance → 重启应用 → cookie 仍在 |
| 3 | 扩展超时 | 断网后浏览源 → 30 秒后显示超时错误（不永久加载） |
| 4 | 图片解码加速 | 翻页时肉眼无加载圈（当前有 ~200ms 的加载闪烁） |
| 5 | 预加载 | 快速连续翻 5 页 → 无白屏 |
| 6 | 大图内存 | 打开含 8000×6000 图的漫画 → 进程内存 <500MB |
| 7 | 状态管理 | `ReaderScreenModelTest` 全部通过，UI 行为不变 |
