# Desktop Reader 测试覆盖率报告

## 📊 测试统计

| 测试文件                    | 测试数量 | 状态    |
| --------------------------- | -------- | ------- |
| ReaderScreenModelTest       | 33       | ✅ 新增 |
| PhaseEReaderTest            | 32       | ✅ 已有 |
| EdgePixelMatcherTest        | 28       | ✅ 已有 |
| ReaderNavigatorTest         | 13       | ✅ 已有 |
| ReaderKeyboardActionTest    | 13       | ✅ 已有 |
| VirtualPageListTest         | 11       | ✅ 已有 |
| ReaderSettingsModelsTest    | 16       | ✅ 已有 |
| CropBorderScannerTest       | 8        | ✅ 已有 |
| ZoomStateTest               | 8        | ✅ 已有 |
| ViewerFlagsTest             | 7        | ✅ 已有 |
| SkiaImageDecoderTest        | 7        | ✅ 已有 |
| SourcePageFetcherTest       | 6        | ✅ 已有 |
| PagePreloaderTest           | 6        | ✅ 已有 |
| DualPageViewerAlignmentTest | 6        | ✅ 已有 |
| ReaderPageLoaderTest        | 6        | ✅ 新增 |
| PageContextMenuActionTest   | 4        | ✅ 已有 |

**总计**: 16 个测试文件，**196 个测试**，全部通过 ✅

---

## 🎯 功能覆盖矩阵

| 模块                            | 测试文件                                      | 覆盖率  |
| ------------------------------- | --------------------------------------------- | ------- |
| **DualPageState**               | PhaseEReaderTest, DualPageViewerAlignmentTest | ✅ 完整 |
| **SourcePageFetcher**           | SourcePageFetcherTest, ReaderPageLoaderTest   | ✅ 完整 |
| **ReaderScreenModel**           | ReaderScreenModelTest                         | ✅ 完整 |
| **EdgePixelMatcher**            | EdgePixelMatcherTest                          | ✅ 完整 |
| **VirtualPage/VirtualPageList** | VirtualPageListTest                           | ✅ 完整 |
| **PagePreloader**               | PagePreloaderTest                             | ✅ 完整 |
| **ReaderNavigator**             | ReaderNavigatorTest                           | ✅ 完整 |
| **ReaderKeyboardAction**        | ReaderKeyboardActionTest                      | ✅ 完整 |
| **ZoomState**                   | ZoomStateTest                                 | ✅ 完整 |
| **CropBorderScanner**           | CropBorderScannerTest                         | ✅ 完整 |
| **ReaderSettingsModels**        | ReaderSettingsModelsTest                      | ✅ 完整 |
| **ViewerFlags**                 | ViewerFlagsTest                               | ✅ 完整 |
| **SkiaImageDecoder**            | SkiaImageDecoderTest                          | ✅ 完整 |
| **PageContextMenu**             | PageContextMenuActionTest                     | ✅ 完整 |
| **SinglePagePagerViewer**       | -                                             | ⚠️ 缺失 |
| **DualPagePagerViewer**         | -                                             | ⚠️ 缺失 |
| **ZoomablePageBox**             | -                                             | ⚠️ 缺失 |
| **WebtoonViewer**               | -                                             | ⚠️ 缺失 |

---

## 🔧 缺失测试设计

### 1. SinglePagePagerViewerTest

```kotlin
/**
 * Tests for SinglePagePagerViewer navigation logic.
 *
 * Missing coverage:
 * - RTL/LTR page index mapping
 * - Virtual pages integration
 * - Page change callbacks
 * - Spread detection callbacks
 */
@Test
fun `pager index mapping in RTL mode`() {
    // Given 5 pages in RTL mode
    // When pageToPager(4) is called
    // Then pager index should be 0
}

@Test
fun `pager index mapping in LTR mode`() {
    // Given 5 pages in LTR mode
    // When pageToPager(2) is called
    // Then pager index should be 2
}

@Test
fun `external navigation scrolls pager to target`() {
    // Given pager at page 0
    // When currentPage changes to 3
    // Then pager should scroll to index 3
}
```

### 2. DualPagePagerViewerTest

```kotlin
/**
 * Tests for DualPagePagerViewer dual-page layout.
 *
 * Missing coverage:
 * - RTL dual-page group mapping
 * - Spread detection integration
 * - Forced single pages handling
 * - Matched pairs integration
 */
@Test
fun `dual page groups are created correctly`() {
    // Given 6 pages
    // When dual state is built
    // Then group 0 should be [0], group 1 should be [1,2], etc.
}

@Test
fun `RTL mode reverses page order in groups`() {
    // Given RTL mode with pages [0,1,2,3]
    // When group 1 is rendered
    // Then left page should be 1, right page should be 2
}
```

### 3. ZoomablePageBoxTest

```kotlin
/**
 * Tests for ZoomablePageBox zoom/pan behavior.
 *
 * Missing coverage:
 * - Zoom boundaries (min/max scale)
 * - Pan boundaries
 * - Double-tap reset
 * - Crop borders callback
 * - Spread detection callback
 */
@Test
fun `zoom has maximum scale limit`() {
    // Given zoom state with scale 5.0
    // When attempting to zoom in
    // Then scale should remain at MAX_SCALE (5.0)
}

@Test
fun `double tap resets zoom to default`() {
    // Given zoom state with scale 2.0
    // When double tap is detected
    // Then zoom should reset to 1.0
}
```

### 4. WebtoonViewerTest

```kotlin
/**
 * Tests for WebtoonViewer scrolling behavior.
 *
 * Missing coverage:
 * - Auto-scroll at bottom triggers onNextChapter
 * - Auto-scroll speed calculation
 * - Side padding application
 * - Border cropping
 */
@Test
fun `auto scroll triggers next chapter at bottom`() {
    // Given autoScroll enabled
    // When scroll reaches bottom
    // Then onNextChapter should be called
}

@Test
fun `auto scroll speed calculation`() {
    // Given Fast speed preset
    // When auto scroll is active
    // Then pixels per tick should match Fast preset
}
```

---

## 📋 建议优先级

### 🔴 高优先级 (影响核心功能)

1. **SinglePagePagerViewerTest**
   - RTL/LTR 映射逻辑是关键功能
   - 边界条件多，容易出错

2. **DualPagePagerViewerTest**
   - 双页模式是 Mihon 的特色功能
   - 分组逻辑复杂，已有 PhaseEReaderTest 但缺少 UI 层测试

### 🟡 中优先级 (提升可靠性)

3. **ZoomablePageBoxTest**
   - 缩放/平移是常用功能
   - 可以复用 ZoomState 的测试模式

4. **WebtoonViewerTest**
   - 自动滚动是关键功能
   - 测试相对简单

---

## ✅ 测试最佳实践

1. **每个测试只验证一个行为**
   - ✅ `goToPage clamps to max page`
   - ❌ `goToPage with various values works correctly`

2. **使用描述性的测试名称**
   - ✅ `appendLoadedPage pads to correct index when index is beyond current size`
   - ❌ `test1()`

3. **测试边界条件**
   - ✅ `goToPage with negative page returns 0`
   - ✅ `goToPage with page beyond max returns max`

4. **分离测试关注点**
   - ReaderScreenModel 测试状态管理
   - PagerViewer 测试 UI 逻辑
   - 功能模块测试纯算法

---

## 🔍 发现的问题

### ReaderPageLoader 问题分析

通过测试发现，`resolvedUrls.size=0` 的根本原因是：

**所有图片 HTTP 请求都失败了**

可能原因：

1. **缺少 Referer 头** - 图片服务器需要 Referer 头
2. **HTTP 403/404** - 资源不存在或被禁止
3. **网络问题** - 防火墙、代理、超时

### 验证方法

```kotlin
@Test
fun `HTTP 403 results in resolvedUrls size 0`() {
    // When all image requests return 403
    // Then resolvedUrls.size should be 0
    // This is EXPECTED behavior, not a bug
}
```

---

## 📁 测试文件位置

```
app-desktop/src/test/kotlin/mihon/desktop/reader/
├── CropBorderScannerTest.kt
├── DualPageViewerAlignmentTest.kt
├── EdgePixelMatcherTest.kt
├── PageContextMenuActionTest.kt
├── PagePreloaderTest.kt
├── PhaseEReaderTest.kt
├── ReaderKeyboardActionTest.kt
├── ReaderNavigatorTest.kt
├── ReaderPageLoaderTest.kt       # 新增
├── ReaderScreenModelTest.kt      # 新增
├── ReaderSettingsModelsTest.kt
├── SkiaImageDecoderTest.kt
├── SourcePageFetcherTest.kt
├── ViewerFlagsTest.kt
├── VirtualPageListTest.kt
└── ZoomStateTest.kt
```
