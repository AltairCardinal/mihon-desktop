# Task 3 报告：Desktop Skia、缓存与三种 viewer 生产接线

状态：`DONE_WITH_CONCERNS`

提交：`165c62de6 refactor(reader): wire Desktop reader to shared semantics`

## RED / GREEN

### RED 1：shared API 与 Desktop 旧接线不兼容

首轮命令：

```powershell
.\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.reader.PagePreloaderTest" --tests "mihon.desktop.reader.SkiaImageDecoderTest" --tests "mihon.desktop.ui.reader.ReaderScreenModelTest" --tests "mihon.desktop.reader.ReaderNavigatorTest"
```

稳定失败于 `compileKotlinJvm`，原因是生产接线仍使用旧 shared API：

- `DesktopPageCache` 缺 `generation`、`revision`、`beginGeneration()`、`commit()`，仍实现已删除的 `put()`。
- Desktop decoder 与 preloader 未传递 `PageDecodeRequest` / `PageDecodeResult` generation。
- Desktop 滤镜仍使用总开关 `enabled/grayscale/invert`，未适配独立开关。
- `ReaderChapterState.Error` 未提供 `retryTargetChapterId`。

GREEN：缓存委托 shared `ByteBudgetPageCache`；decode/preload 全链路携带 generation；错误状态携带当前章节 ID；滤镜适配 canonical 独立开关。相同聚焦命令通过。

### RED 2：晚到缓存与 transition 生产 API 缺失

新增真实行为测试后，`compileTestKotlinJvm` 因缺少以下生产能力而失败：

- `PagePreloader.cacheRevision`
- `readerPagePainterModel()`
- `ReaderScreenModel.chapterTransitionCommand()`
- `setChapterTransitionState()` / `retryChapterTransition()`
- `chapterTransitionPresentation()`

GREEN：Zoom/Webtoon 订阅 cache revision；Single/Dual 通过 `ZoomablePageBox` 进入相同链路；cache hit 时 painter model 为 `null`，不保留重复全图请求。章节 transition 区分 Wait、Loading、Error、Retry、Boundary 与 missing count。

### RED 3：Desktop 零预算缓存偏离 shared contract

`zero byte cache keeps shared no decoded memory semantics` 首次运行在 `DesktopPageCache` 构造器失败，因为 Desktop 错误要求 `maxBytes > 0`。

GREEN：改为与 shared 一致的 `maxBytes >= 0`；零预算缓存拒绝 decoded write，且不改变缓存内容。

## 生产调用链

### Skia / preload / cache / viewer

`DesktopReaderScreen` → `ReaderSideEffects` → `PagePreloader.preload()` → `ReaderPreloadPlanner.moveTo()` → fetch encoded bytes → `SkiaPageDecoder` 或 `SkiaRegionPageDecoder` → `DesktopPageCache.commit()` → shared byte-budget LRU / stale-generation 拒绝 → `cacheRevision` → `ZoomablePageBox` 或 `WebtoonPageItem` 重组。

- region decoder 按请求 `PixelBounds` 绘制目标区域。
- sampling 使用 ceiling 整数比例，结果宽高不超过上限。
- generation 变化会原子注册并取消旧 generation 的 LAZY/active jobs。
- beginGeneration 淘汰完整旧窗口；晚到旧结果由 cache commit 拒绝。
- Single 与 Dual 统一经 `ZoomablePageBox` 消费缓存；Webtoon 使用同一 painter-model 选择规则。

### 章节 metadata / skip / transition

`MangaDetailScreen` 可见章节集合 + `Chapter.scanlator` → `MangaDetailScreenModel.readerRequest()` → `ReaderChapterRef.isFiltered/isDuplicate` → `ReaderNavigator` → shared `ChapterSkipPolicy/findAdjacentChapter()` → `ReaderChapterTransitionModel` → Desktop transition feedback。

- 当前章节始终保留。
- read、filtered、duplicate 可组合跳过。
- 没有可用相邻章节时返回显式 Boundary，不回退到错误章节。
- 当前章节加载错误使用 shared RetryChapter 命令目标。

### 配对、导航与滤镜

- Desktop `DualPageState` 复用 shared `ReaderPairingState`，只保留 cover 单页、edge-matched pair、landscape parity 等产品 options。
- 虚拟页、键盘物理方向、tap preset 均映射到 shared reader contract。
- tint、brightness、grayscale、invert 独立启用；tint/brightness 使用 overlay，grayscale/invert 使用 color matrix。

## 测试与验证

- 聚焦最终命令（PagePreloader、SkiaImageDecoder、ReaderScreenModel、ReaderNavigator、真实 viewer cache integration、MangaDetail metadata）：`BUILD SUCCESSFUL`，约 6 秒。
- Desktop reader/UI 通配回归：

  ```powershell
  .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.reader.*" --tests "mihon.desktop.ui.reader.*"
  ```

  结果：`327 tests`，`BUILD SUCCESSFUL`。
- `:app-desktop:spotlessCheck`：模块无此任务。
- 仓库等价检查 `./gradlew spotlessCheck`：`BUILD SUCCESSFUL`，61 tasks up-to-date。
- `git diff --check` 与 `git diff --cached --check`：无输出，退出码 0。
- staged allowlist 审计：31 个文件，仅 Task 3 reader/ui.reader 与指定 library metadata 文件；未包含 parity manifest、OpenSpec、Task 4 源码字符串测试或其他用户改动。

## 变更文件

生产文件：

- `app-desktop/src/main/kotlin/mihon/desktop/reader/`：`DesktopPageCache.kt`、`DesktopPageDecoders.kt`、`DesktopReaderRuntimeFactory.kt`、`DualPageState.kt`、`PagePreloader.kt`、`ReaderColorFilter.kt`、`ReaderKeyboardAction.kt`、`ReaderNavigator.kt`、`ReaderPreferences.kt`、`SkiaImageDecoder.kt`、`VirtualPageList.kt`
- `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/`：`DesktopReaderScreen.kt`、`DualPagePagerViewer.kt`、`PageSplitHalf.kt`、`ReaderScreenModel.kt`、`ReaderSettingsPanel.kt`、`ReaderState.kt`、`TapZone.kt`、`WebtoonViewer.kt`、`ZoomablePageBox.kt`
- metadata 接线：`LibraryScreenModel.kt`、`MangaDetailScreen.kt`、`MangaDetailScreenModel.kt`

测试文件：

- `PagePreloaderTest.kt`、`ReaderNavigatorTest.kt`、`ReaderSettingsModelsTest.kt`、`SkiaImageDecoderTest.kt`
- `MangaDetailScreenModelTest.kt`
- `NavigationModeTest.kt`、`ReaderPageCacheIntegrationTest.kt`、`ReaderScreenModelTest.kt`

## 【功能特性】

- Desktop 阅读器缓存：Single、Dual、Webtoon 三种 viewer 都能在预加载晚到后切换到 decoded cache；缓存命中不会继续保留普通全图请求。
- 章节反馈：加载页显示 Loading；失败显示 Error 与 Retry；相邻章节显示 transition、缺章数量与 Continue；列表边界显示无相邻章节反馈。
- 阅读设置：色调、亮度、灰度、反色可独立启用；read、filtered、duplicate 跳章规则可组合。

## 【BUG 修复】

- shared reader API 升级后 Desktop 无法编译 → Desktop decoder/cache/preloader/navigation/filter/transition 已接入当前 shared contract。
- 快速翻页时旧预加载可能晚到回填或漏取消 → generation 原子注册、完整旧窗口淘汰、旧结果拒绝。
- 大图区域解码尺寸可能未按整数 ceiling 采样 → 输出尺寸始终不越请求上限。
- 相邻章节不存在时无反馈 → 显式 Boundary 反馈，不导航到错误章节。

## 【验收清单】

- [ ] 打开 Desktop 阅读器并快速连续翻页 → 当前窗口正常显示，旧页不会晚到覆盖当前窗口。
- [ ] 分别切换 Single、Dual、Webtoon → 预加载晚到后页面无重复全图加载闪烁。
- [ ] 在阅读设置分别启用 tint、brightness、grayscale、invert → 每个效果可独立生效和关闭。
- [ ] 开启跳过已读/过滤/重复章节并翻到章节边界 → 按组合规则寻找相邻章节；无目标时显示 Boundary。
- [ ] 触发页面加载错误并点 Retry → 同一章节重新加载；错误提示清除并显示 Loading。

## 顾虑

- 本 Task 按明确 allowlist 与测试要求未运行 `scripts/build-desktop.sh`，因此没有递增版本、构建/启动固定路径 EXE，也无法提供窗口标题版本验收；应由根任务在整合其他并行 Desktop 工作后统一执行。
- `app-desktop` 未注册 Spotless/ktlint/format task；仓库根 `spotlessCheck` 成功，但其任务列表不含 app-desktop 专属格式任务。
- 工作树仍有用户/其他 Task 的 parity、OpenSpec、文档、SDK 与 Task 4A 未提交文件；均未包含在提交 `165c62de6`。

---

# Task 3 独立审查修复：cache 变换与 adjacent transition 生产链

状态：`DONE`

提交：`fix(reader): complete Desktop cache and transition wiring`（本提交）

## RED / GREEN

### RED 4：Single/Dual cache hit 缺少显示变换

新增 `ReaderPageCacheIntegrationTest` 生产变换链用例，覆盖：

- cache hit 左、右虚拟半页；
- 90° 旋转虚拟页通过 shared `sourceBounds` 映射到正确源区域；
- pager 白边裁剪；
- cache hit 继续令普通全图 painter model 为 `null`。

首轮聚焦测试在 `compileTestKotlinJvm` 按预期失败：生产代码不存在 `transformCachedPageBitmap`，证明缓存快路径未接入拆页/裁边链。

GREEN：`ZoomablePageBox` 对预加载位图应用 `sourceBounds → splitHalf → cropBorders` 变换，Single viewer 转交 `VirtualReaderPage.sourceBounds`；Coil/local/cache 三条路径共享相同源区域语义。虚拟页 bounds 变化会重置旧变换结果；Webtoon 继续使用其独立裁边实现，没有改为 pager 裁边。

### RED 5：adjacent Loading/Error/Retry 没有生产 loader

新增 `DesktopReaderChapterTransitionIntegrationTest`，用可控 fake chapter repository 与 fake loader 覆盖：

- `Loading → Error → RetryChapter(targetId) → Loading → Loaded`；
- Loaded 页进入目标 `DesktopReaderScreen`，不重复加载目标章；
- PREVIOUS/NEXT 双侧 Boundary 都不调用 loader。

首轮聚焦测试在 `compileTestKotlinJvm` 按预期失败：生产代码缺少 `AdjacentChapterLoader`、`requestAdjacentChapterTransition()` 与 `destinationForChapterTransition()`。

GREEN：`DesktopReaderPageLoader` 抽出当前章/相邻章共用的本地下载、源 page list 与图片获取链；`ReaderScreenModel` 由真实 loader 结果更新 shared transition state；Retry 只消费 shared `RetryChapter` 并重试相同目标；`DesktopReaderScreen` 在 Loaded 后复用已加载页导航，Boundary 直接反馈且不加载。

## 最终测试与检查

- 最终聚焦测试：87 tests，0 failures / 0 errors，`BUILD SUCCESSFUL`。
- Desktop reader/UI 通配回归：331 tests，0 failures / 0 errors，`BUILD SUCCESSFUL`。
- 根 `spotlessCheck`：61 tasks，`BUILD SUCCESSFUL`。
- `git diff --check`：无输出，退出码 0。
- Task 4 产品回归与 Task 7 build/固定 EXE 验收不属于本修复，未运行且未宣称完成。

## 【功能特性】

- 相邻章节预加载：在 Desktop 阅读器触发上一章/下一章时，先显示 Loading；加载成功后显示 Continue，继续时直接使用已加载页面。
- 相邻章节重试：相邻章加载失败会显示 Error 与 Retry；Retry 仅重试失败的目标章，不会提前跳转。

## 【BUG 修复】

- 预加载命中后的宽页/裁边显示错误：修复前 Single/Dual 可能显示完整原图或未裁边图 → 修复后 cache、Coil、本地文件路径保持相同拆页/source bounds/裁边语义。
- 相邻章 Loading/Error/Retry 仅测试可达：修复前生产操作直接换页，失败只能成为新页面错误 → 修复后真实相邻加载结果驱动 shared transition，Retry 可恢复到 Loaded。

## 【验收清单】

- [ ] 在 Single 自动拆页中打开已预加载宽页并查看左右半页 → 两半显示不同且正确的源区域，不出现重复完整宽页。
- [ ] 在 Dual 自动拆页或开启 pager 裁边后查看预加载命中页 → 拆页与白边裁剪保持正确；Webtoon 独立裁边行为不变。
- [ ] 翻到可用相邻章节并触发切章 → 先显示 Loading，成功后显示 Continue，点击后进入目标章且页面直接可用。
- [ ] 模拟相邻章加载失败并点击 Retry → 同一目标章重新加载，成功后可 Continue；不会在失败时提前导航。
- [ ] 在章节列表最前与最后分别触发越界方向 → 显示 Boundary，且不发起章节加载。

---

# Task 3 第二轮最终修复：下采样 cache 的原图边界映射

状态：`DONE`

提交：`fix(reader): scale cached page source bounds`（本提交）

## RED / GREEN

### RED 6：真实预加载下采样丢失原始尺寸上下文

新增 `ReaderPageCacheIntegrationTest` 生产链测试，使用真实 `PagePreloader` 将 8×15 原图下采样为 4×7 cache bitmap，再交给 `ZoomablePageBox` 使用的 production 变换函数。首次聚焦运行在 `compileTestKotlinJvm` 按预期失败：生产代码缺少 `PreloadedPageBitmap`、`PagePreloader.getCachedPage()` 与携带原始尺寸的 cache 变换入口。

测试同时覆盖：

- 90° / 270° 旋转的奇数尺寸虚拟左右半页，输出分别为 3 / 4 像素高且落在正确颜色区域；
- 普通下采样宽页 split，左右输出分别为 3 / 4 像素宽且不是整页；
- 下采样白边页 crop，输出从 6×6 缩为 4×2；
- cache hit 的普通全图 painter model 继续为 `null`。

### RED 7：越界 source bounds 静默回退整页

新增越界回归用例后，临时恢复旧 `extractSkiaSubBitmap(...) ?: bitmap` 行为，单测按预期 1 test / 1 failure：没有抛出异常，证明测试能捕获静默整页回退。

GREEN：`PagePreloader` 为缓存 bitmap 保留原始宽高；`ZoomablePageBox` 按独立 x/y 比例把原图边界映射到下采样 bitmap，并以同一舍入规则映射共享边缘，保证奇数半页连续。原图越界或下采样后区域塌缩会显式失败，不再回退整页掩盖错误。

## 最终测试与检查

- 聚焦测试：`PagePreloaderTest` + `ReaderPageCacheIntegrationTest`，`BUILD SUCCESSFUL`。
- Desktop reader/ui.reader 通配回归：335 tests，0 failures / 0 errors，`BUILD SUCCESSFUL`。
- 根 `spotlessCheck`：61 tasks，`BUILD SUCCESSFUL`。
- `git diff --check`：无输出，退出码 0。
- Task 4 产品回归与 Task 7 build/固定 EXE 验收不属于本修复，未运行且未宣称完成。

## 【功能特性】

- Desktop 阅读器预加载缓存：Single/Dual 命中下采样缓存后，仍按原图坐标显示旋转虚拟半页、普通拆页和裁边结果；普通全图 cache hit 不再保留重复 painter 请求。功能边界仅为 Desktop reader cache 显示链，不改变 Webtoon 的独立裁边路径。

## 【BUG 修复】

- 下采样缓存错误使用原图边界：修复前旋转虚拟半页的原图坐标会越过小尺寸 cache bitmap，随后静默显示整页 → 修复后原始尺寸随缓存传递，边界按 x/y 比例映射后裁剪，90°/270° 与奇数边界均显示正确区域。
- 非法边界掩盖错误：修复前越界会回退整张缓存图 → 修复后越界或映射塌缩会显式失败，不再伪装成正常整页。

## 【验收清单】

- [ ] 在 Single 自动拆页中打开已预加载且发生下采样的 90°/270° 旋转奇数尺寸宽页 → 左右虚拟半页显示不同的正确区域，合计覆盖整页且不显示重复整页。
- [ ] 在 Single/Dual 打开已预加载的普通宽页 → 左右拆页尺寸按缓存奇数边界连续分配，内容不重复且不回退整页。
- [ ] 在 Dual 开启 pager 裁边并打开已预加载白边页 → 白边被裁除；Webtoon 独立裁边行为不变。
- [ ] 命中普通全图预加载缓存 → 页面直接显示缓存结果，不继续发起普通全图 painter 请求。
