# Mihon Desktop 演进计划：扩展系统 APK 兼容 + 阅读体验优化

## Context

Mihon Desktop 当前面临两个核心改进方向：

1. **扩展系统**：当前依赖 `extensions-desktop` 仓库预编译 JAR，维护成本高且与 keiyoushi 上游存在同步延迟。引入 Suwayomi 风格的 APK 直接加载能力，可以消除编译管道依赖，直接使用 keiyoushi 官方发布的扩展。

2. **阅读体验**：当前使用 `javax.imageio` 解码 + Coil 3 + Compose `Image`，与 OpenComic（Chromium 渲染）和 YACReader（Qt QGraphicsView）相比，在图片解码速度、大图处理、预加载策略上存在明显差距。

---

## 第一部分：扩展系统 APK 兼容

### 1.1 架构设计：双源共存

```
扩展安装请求
    ↓
ExtensionInstaller（新增路由层）
    ├── JAR 源（现有）：直接下载预编译 JAR → 保存到 ~/.mihon/extensions/
    └── APK 源（新增）：下载 APK → 提取 DEX → dex2jar 转换 → 保存为 JAR
    ↓
统一产物：~/.mihon/extensions/*.jar
    ↓
DesktopExtensionLoader（不变）：URLClassLoader + ServiceLoader 加载
```

**关键原则**：最终产物都是 JAR，`DesktopExtensionLoader` 无需改动。差异仅在安装阶段。

### 1.2 优先级与回退策略

```
用户请求安装扩展 "eu.kanade.tachiyomi.extension.zh.manhuagui"
    ↓
1. 优先尝试 JAR 源（现有 extensions-desktop 仓库）
   ├── 成功 → 安装完成（JAR 已验证兼容 JVM）
   └── 失败（JAR 不可用 / 仓库未收录）
       ↓
2. 回退到 APK 源（keiyoushi 官方仓库）
   ├── 下载 APK → dex2jar 转换 → 验证 → 安装
   ├── 成功 → 安装完成，标记来源为 APK_CONVERTED
   └── 失败（转换失败 / compat 层不足）
       ↓
3. 报错：「该扩展暂不支持桌面版」
```

元数据中记录来源：
```kotlin
@Serializable
data class ExtensionMeta(
    val pkgName: String,
    val versionCode: Long,
    val versionName: String,
    val iconUrl: String = "",
    val source: ExtensionOrigin = ExtensionOrigin.COMPILED_JAR,  // 新增
)

enum class ExtensionOrigin {
    COMPILED_JAR,     // 从 extensions-desktop 仓库下载的预编译 JAR
    CONVERTED_APK,    // 从 keiyoushi APK 转换而来
}
```

### 1.3 APK 转换管线

**新文件** `app-desktop/src/main/kotlin/mihon/desktop/extension/ApkToJarConverter.kt`

```kotlin
class ApkToJarConverter {
    /**
     * 从 APK 文件提取 DEX 并转换为 JVM JAR。
     * @return 转换后的 JAR 文件，或 null 如果转换失败
     */
    suspend fun convert(apkFile: File, outputDir: File): File?
}
```

流程：
1. APK 本质是 ZIP → 解压提取 `classes.dex`（可能有 `classes2.dex` 等多 DEX）
2. 使用 `dex-tools`（dex2jar）将每个 DEX 转换为 JVM `.class` 文件
3. 将所有 `.class` 打包为单一 JAR + 复制 APK 中的资源文件（`META-INF/services/`）
4. 验证产出 JAR 包含 `.class` 文件（现有 `DesktopExtensionApi` 已有此验证）

**依赖引入**：
```toml
# gradle/libs.versions.toml
[libraries]
dex-tools = { module = "com.github.nicehash:dex2jar", version = "2.4.22" }
```

预估包体增量：~3-4 MB

### 1.4 Android API 兼容层

**新包** `app-desktop/src/main/kotlin/mihon/desktop/compat/`

需要提供的 stub 类（参考 Suwayomi 的 AndroidCompat，按优先级排列）：

| 类 | 用途 | 实现策略 |
|---|------|---------|
| `android.content.Context` | 扩展获取 SharedPreferences、文件路径 | 最小实现：`getSharedPreferences()` → 路由到 `DesktopPreferenceStore` |
| `android.content.SharedPreferences` | 扩展存取偏好设置 | 基于 `java.util.prefs.Preferences` 实现 |
| `android.app.Application` | 少数扩展引用 | 继承 Context stub，单例 |
| `android.util.Log` | 日志输出 | 委托到 `println` / SLF4J |
| `android.util.Base64` | 编解码 | 委托到 `java.util.Base64` |
| `android.os.Build` | 系统版本检测 | 静态常量：`VERSION.SDK_INT = 30` |
| `android.webkit.CookieManager` | Cookie 管理 | 委托到 `DesktopCookieJar` |
| `android.net.Uri` | URL 解析 | 委托到 `java.net.URI` |
| `androidx.preference.*` | 源偏好设置 | 现有 `PreferenceScreen.kt` 反射桥已覆盖 |

**大部分是简单委托，不需要深度实现。** Suwayomi 的经验表明 ~95% 的扩展只用到 Context + SharedPreferences + Log + Base64。

### 1.5 ClassLoader 适配

现有 `ExtensionClassLoader` 已经将 `android.*` 和 `androidx.*` 路由到父 ClassLoader：

```kotlin
// 现有代码（DesktopExtensionLoader.kt）
private val parentLoadPrefixes = listOf(
    "android.", "androidx.",
    // ...
)
```

APK 转换的 JAR 中的 `android.*` 引用会被路由到我们提供的 compat 层。**ClassLoader 不需要改动。**

### 1.6 修改文件清单

| 文件 | 操作 |
|------|------|
| `app-desktop/.../extension/ApkToJarConverter.kt` | **新建** — APK→JAR 转换 |
| `app-desktop/.../extension/ApkToJarConverterTest.kt` | **新建** — 转换测试 |
| `app-desktop/.../compat/AndroidContext.kt` | **新建** — Context stub |
| `app-desktop/.../compat/AndroidSharedPreferences.kt` | **新建** — SharedPreferences stub |
| `app-desktop/.../compat/AndroidStubs.kt` | **新建** — Log, Base64, Build, Uri 等简单 stub |
| `app-desktop/.../compat/AndroidCompatTest.kt` | **新建** — compat 层测试 |
| `app-desktop/.../extension/ExtensionMeta.kt` | **修改** — 增加 `source: ExtensionOrigin` 字段 |
| `app-desktop/.../extension/DesktopExtensionApi.kt` | **修改** — 增加 APK 源回退逻辑 |
| `app-desktop/.../extension/DesktopExtensionManager.kt` | **修改** — 安装时选择 JAR/APK 路径 |
| `app-desktop/.../di/DesktopAppModule.kt` | **修改** — 注册 compat 层单例 |
| `gradle/libs.versions.toml` | **修改** — 添加 dex-tools 依赖 |
| `app-desktop/build.gradle.kts` | **修改** — 添加 dex-tools 依赖 |

### 1.7 TDD 执行顺序

**Phase A：APK 转换器（纯数据层，无 UI）**
1. RED：`ApkToJarConverterTest` — 给定一个测试 APK（内含 classes.dex），验证转换输出包含 .class 文件
2. GREEN：实现 `ApkToJarConverter`
3. REFACTOR

**Phase B：Android compat 层**
4. RED：`AndroidCompatTest` — `android.content.Context.getSharedPreferences()` 返回可用的 SharedPreferences
5. RED：SharedPreferences `getString/putString` 持久化到 `java.util.prefs`
6. RED：`android.util.Base64.encodeToString()` / `decode()` 与标准行为一致
7. GREEN：逐个实现 stub
8. REFACTOR

**Phase C：安装路由与回退**
9. RED：`ExtensionInstallRouterTest` — JAR 可用时优先 JAR，不触发 APK 转换
10. RED：JAR 不可用时回退到 APK，转换后安装成功
11. RED：APK 转换失败时返回错误
12. GREEN：修改 `DesktopExtensionApi` + `DesktopExtensionManager`
13. REFACTOR

**Phase D：集成验证**
14. 选取 3 个热门扩展（MangaDex、MangaSee、Manhuagui）进行端到端安装测试
15. 构建部署，手动验证扩展加载和浏览功能

---

## 第二部分：阅读体验优化

### 2.1 当前瓶颈分析

| 瓶颈 | 原因 | OpenComic/YACReader 如何解决 |
|------|------|---------------------------|
| **图片解码慢** | `javax.imageio` 无 SIMD，JPEG 解码比 libjpeg-turbo 慢 3-5 倍 | Chromium 用 libjpeg-turbo；Qt 用平台原生解码器 |
| **无预加载** | 当前页加载完才开始加载下一页 | Chromium 预取；Qt 用 QPixmapCache |
| **大图全量加载** | 8000×6000 扫描图整张解码为 ImageBitmap (~183MB) | Chromium 自动 tiling；Qt LOD |
| **翻页卡顿** | 每次翻页触发全量解码 + Compose 重组 | GPU 纹理缓存，翻页时零解码 |
| **裁边/分割重复解码** | `loadAndCrop`/`loadSplitHalf` 用 `ImageIO.read()` 重新从 URL 解码整张图 | 从已解码缓存裁切，不重复解码 |

### 2.2 优化方案（按优先级排序）

#### 优化 1：引入 Skia 原生图片解码（最高优先级）

**问题**：Compose Desktop 底层就是 Skia，Skia 包含高性能图片编解码器（libjpeg-turbo、libpng、libwebp），但 JetBrains 没有在 Kotlin API 中暴露它。当前通过 `javax.imageio` 解码是绕了一大圈。

**方案**：通过 Skiko（Skia for Kotlin）底层 API 直接调用 Skia 解码器。

```kotlin
// 通过 Skiko 的 org.jetbrains.skia 包访问 Skia 原生能力
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Codec

fun decodeWithSkia(bytes: ByteArray): ImageBitmap {
    val skiaImage = SkiaImage.makeFromEncoded(bytes)
    return skiaImage.toComposeImageBitmap()
}

// 或者使用 Codec API 支持降采样：
fun decodeWithSkiaDownsampled(bytes: ByteArray, targetWidth: Int): ImageBitmap {
    val codec = Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes))
    val sampleSize = (codec.width / targetWidth).coerceAtLeast(1)
    // ... 使用 codec.readPixels() 降采样解码
}
```

**收益**：
- JPEG 解码速度提升 3-5 倍（Skia 内部使用 libjpeg-turbo + SIMD）
- 原生支持 WebP、PNG、AVIF 等格式
- 无需 JNI 桥接——Skiko 已经是 Compose Desktop 的依赖

**新文件** `app-desktop/src/main/kotlin/mihon/desktop/reader/SkiaImageDecoder.kt`

#### 优化 2：相邻页预加载（高优先级）

**问题**：当前翻页后才开始加载下一页图片，用户感知到 loading 状态。

**方案**：预解码当前页前后各 N 页（可配置），缓存为 `ImageBitmap`。

```kotlin
class PagePreloader(
    private val decoder: SkiaImageDecoder,
    private val cacheSize: Int = 5,  // 前后各缓存页数
) {
    private val cache = LinkedHashMap<Int, ImageBitmap>(cacheSize * 2 + 1, 0.75f, true)

    suspend fun preload(currentPage: Int, pageUrls: List<String>) {
        val range = (currentPage - cacheSize)..(currentPage + cacheSize)
        for (i in range) {
            if (i in pageUrls.indices && i !in cache) {
                withContext(Dispatchers.IO) {
                    cache[i] = decoder.decode(pageUrls[i])
                }
            }
        }
        // 清理超出范围的缓存
        cache.keys.removeAll { it !in range }
    }

    fun get(page: Int): ImageBitmap? = cache[page]
}
```

**集成点**：`ZoomablePageBox` 检查 `PagePreloader.get(page)`，命中则直接显示（零等待），未命中则走 Coil 常规加载。

**新文件** `app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt`

#### 优化 3：裁边/分割复用已解码数据（中优先级）

**问题**：`loadAndCrop()` 和 `loadSplitHalf()` 都从 URL 重新读取并用 `ImageIO.read()` 解码整张图，即使 Coil 已经解码过一次。

**方案**：从 Coil 的 `AsyncImagePainter.State.Success` 中获取已解码的图像数据，直接在内存中裁切，避免二次解码。

```kotlin
// ZoomablePageBox.kt 中
LaunchedEffect(painterState, cropBorders, splitHalf) {
    val s = painterState
    if (s is AsyncImagePainter.State.Success) {
        val bitmap = s.result.image.toSkiaBitmap()  // 从已解码数据获取

        when {
            splitHalf != null -> {
                croppedBitmap = withContext(Dispatchers.Default) {
                    cropFromBitmap(bitmap, splitHalf)  // 内存裁切，不重新解码
                }
            }
            cropBorders -> {
                croppedBitmap = withContext(Dispatchers.Default) {
                    cropBordersFromBitmap(bitmap)  // 内存裁切
                }
            }
        }
    }
}
```

**收益**：消除裁边/分割时的重复 I/O 和解码，延迟从 ~100-200ms 降到 ~5-10ms。

#### 优化 4：大图降采样显示（中优先级）

**问题**：8000×6000 的扫描图整张解码为 `ImageBitmap` 占用 ~183MB 内存。

**方案**：根据显示区域大小计算降采样因子，首次加载低分辨率版本用于显示，缩放时按需加载高分辨率区域。

```kotlin
fun calculateSampleSize(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Int {
    val widthRatio = imageWidth / viewWidth
    val heightRatio = imageHeight / viewHeight
    return maxOf(widthRatio, heightRatio).coerceAtLeast(1)
}

// Skia Codec 支持原生降采样：
val codec = Codec.makeFromData(data)
val sampleSize = calculateSampleSize(codec.width, codec.height, viewWidth, viewHeight)
// codec 解码时直接以 1/sampleSize 分辨率输出
```

**收益**：8000×6000 图在 1920×1080 屏幕上只需解码为 2000×1500（~12MB），内存节省 93%。

#### 优化 5：Webtoon 模式虚拟化优化（低优先级）

**问题**：当前 `LazyColumn` 对每个可见项独立解码，滚动过快时出现白屏。

**方案**：增加 `LazyColumn` 的 `beyondBoundsItemCount` 配置，预渲染视口外的页面。

```kotlin
LazyColumn(
    // ...
    beyondBoundsItemCount = 3,  // 视口上下各预渲染 3 页
)
```

### 2.3 修改文件清单

| 文件 | 操作 |
|------|------|
| `app-desktop/.../reader/SkiaImageDecoder.kt` | **新建** — Skia 原生解码器 |
| `app-desktop/.../reader/SkiaImageDecoderTest.kt` | **新建** — 解码正确性 + 性能基准测试 |
| `app-desktop/.../reader/PagePreloader.kt` | **新建** — 相邻页预加载缓存 |
| `app-desktop/.../reader/PagePreloaderTest.kt` | **新建** — 预加载逻辑测试 |
| `app-desktop/.../ui/reader/ZoomablePageBox.kt` | **修改** — 集成 Skia 解码 + 预加载 + 裁切复用 |
| `app-desktop/.../ui/reader/SinglePagePagerViewer.kt` | **修改** — 传递预加载器 |
| `app-desktop/.../ui/reader/WebtoonViewer.kt` | **修改** — beyondBoundsItemCount + 预加载 |
| `app-desktop/.../ui/reader/DesktopReaderScreen.kt` | **修改** — 初始化预加载器、降采样参数 |

### 2.4 TDD 执行顺序

**Phase E：Skia 解码器**
1. RED：`SkiaImageDecoderTest` — 解码 JPEG/PNG/WebP 字节数组，验证输出 ImageBitmap 尺寸正确
2. RED：降采样解码测试 — 输入 4000×3000 图片 + sampleSize=2 → 输出 2000×1500
3. GREEN：实现 `SkiaImageDecoder`
4. REFACTOR

**Phase F：预加载器**
5. RED：`PagePreloaderTest` — 预加载后 `get()` 返回非 null
6. RED：翻页后旧缓存被清理
7. RED：缓存大小不超过配置值
8. GREEN：实现 `PagePreloader`
9. REFACTOR

**Phase G：集成到阅读器**
10. 修改 `ZoomablePageBox` 使用 Skia 解码 + 预加载
11. 修改裁边/分割从已解码数据裁切
12. 配置 Webtoon `beyondBoundsItemCount`
13. 端到端手动测试

---

## 第三部分：执行顺序总览

```
Phase A: APK 转换器              ← 可独立开发
Phase B: Android compat 层       ← 依赖 Phase A 的测试 APK
Phase C: 安装路由与回退           ← 依赖 A + B
Phase D: 扩展集成验证            ← 依赖 A + B + C

Phase E: Skia 解码器             ← 可独立开发，与 A-D 并行
Phase F: 预加载器                ← 依赖 E
Phase G: 阅读器集成              ← 依赖 E + F
```

建议先做 **Phase E（Skia 解码器）**——收益最大、风险最低、无外部依赖。
然后做 **Phase A-C（APK 兼容）**——工作量最大但价值明确。
最后做 **Phase F-G（预加载 + 集成）**——打磨体验。

---

## 验证方式

### 扩展系统
1. 从 keiyoushi releases 下载 MangaDex APK → 转换 → 安装 → 浏览漫画列表 → 阅读
2. 安装一个 JAR 源有但 APK 源也有的扩展 → 验证优先使用 JAR
3. 安装一个仅 keiyoushi 有的扩展（JAR 源没有）→ 验证 APK 回退成功
4. 安装一个使用 SharedPreferences 的扩展 → 验证偏好设置可用

### 阅读体验
5. 打开一个含大图扫描页的漫画 → 验证页面加载时间 <500ms（当前 ~1-2s）
6. 快速连续翻页 5 次 → 验证无白屏（预加载命中）
7. 开启裁边 → 验证无明显额外延迟
8. 打开 8000×6000 宽图 → 验证内存占用 <50MB（当前 ~183MB）
9. Webtoon 模式快速滚动 → 验证无白屏闪烁


## 验证结果
https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json

扫描它，找出可以正常转换的扩展，然后与suwayomi的扩展可用状况对比，证明不是你的转换有问题
