---
change: align-reader-core
design-doc: docs/superpowers/specs/2026-07-15-mihon-reader-shared-core-design.md
base-ref: 20c56cbc6b62c4607c4d28709734142cc127a8b3
archived-with: 2026-07-15-align-reader-core
---

# Mihon 阅读器共享核心实施计划

> **2026-07-18 authority correction：** 本计划当时把 fork 新增的相邻 portrait 页 pairing 向量误写为 Android 原版默认。固定 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 并无直接可比的 pairing 算法；共享实现作为双端产品增强保留，不改变用户行为。证据见 [authority correction report](../../../.superpowers/sdd/authority-correction-wave2-reader-report.md)。

> 本计划在当前分支 `claude/pensive-vaughan` 和现有 Task 4A 未提交工作树上继续。不得创建平行实现，也不得把 SDK、Gradle 缓存、构建产物、旧 Task 3B 审查草稿或其他临时文件混入提交。

**目标：** 将固定 Android 原版中真实可比的页面、拆页、导航、章节跳过、滤镜参数、预加载和缓存语义收敛到 `domain/common`；将 fork-only 双页配对明确作为 Android/Desktop 共用的产品增强，并保留 Desktop 的 edge matching、Webtoon 自动滚动、键鼠导航和右键保存等产品能力。

**架构：** `domain/src/commonMain/kotlin/mihon/domain/reader/` 是唯一语义来源。Android 仅保留 Bitmap/View/Coil/Activity 适配，Desktop 仅保留 Skia/Compose/文件与输入设备适配。任何 Desktop 增强必须通过显式 options 进入共享核心，不能改变 Android 默认行为。

**技术栈：** Kotlin Multiplatform、Coroutines/Flow、Android View/Bitmap/Coil、Compose Desktop/Skia、JUnit/kotlin.test、Gradle、Android Emulator、项目桌面构建脚本。

## 执行状态

- [x] Task 1：审计现有 Task 4A 工作树并固定共享契约
- [x] Task 2：完成 Android 薄适配与生产接线
- [x] Task 3：完成 Desktop Skia、缓存与三种 viewer 生产接线
- [x] Task 4：锁定 Desktop 独有产品能力并清除重复规则
- [x] Task 5：更新证据、文档并完成静态与集成验证
- [x] Task 6：有意提交、独立审查与修复
- [x] Task 7：Android、Windows 与必要 macOS 运行时验收

archived-with: 2026-07-15-align-reader-core
---

## Task 1：审计现有 Task 4A 工作树并固定共享契约

**Files:**

- Modify: `openspec/changes/align-reader-core/tasks.md`
- Verify: `domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt`
- Verify: `domain/src/commonMain/kotlin/mihon/domain/reader/PageTransform.kt`
- Verify: `domain/src/commonMain/kotlin/mihon/domain/reader/ReaderNavigation.kt`
- Test: `domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt`
- Reference: `openspec/changes/align-reader-core/specs/shared-reader-core/spec.md`
- Reference: `docs/superpowers/specs/2026-07-15-mihon-reader-shared-core-design.md`

**Consumes:** 当前未提交实现、固定 main 可比行为 fixture、fork pairing provenance、OpenSpec 场景。

**Produces:** 可追溯的 shared contract、已核对的 RED/GREEN 证据、明确的 shared/platform/product 边界。

1. 用 `git status --short`、`git diff --stat`、`git diff --check` 归因现有变更；只把 Task 4A 文件列入后续提交。特别检查 `LibraryScreenModel.kt`、`MangaDetailScreen.kt`、`MangaDetailScreenModel.kt` 只补充 filtered/duplicate 元数据，不承载新的跳过决策。
2. 对照 OpenSpec 逐项核查 `ReaderPageModel`、`ReaderChapterState`、`ReaderTransition`、`ReaderPagePairing`、`ChapterSkipPolicy`、`ReaderColorFilterParams`、`ReaderPreloadPlanner`、`PageDecoder`、`RegionDecoder`、`PageCache`。共享 API 不得引用 Bitmap、View、ImageBitmap、Skia 或 Compose 类型。
3. 核查 `ReaderParityContractTest` 覆盖固定 main 可比的拆页方向与像素边界、fork 配对增强基线和 Desktop 显式 options、状态/Retry、导航反转、read+filtered+duplicate 组合跳过、滤镜边界、预加载窗口/代次/取消/淘汰/预算。
4. 重跑共享契约：

   ```powershell
   ./gradlew :domain:jvmTest --tests "mihon.domain.reader.ReaderParityContractTest"
   ```

   预期：全部通过；若失败，先确认测试表达 Android 原版事实，再做最小修复并重跑。已有历史 RED 证据与本轮 GREEN 命令一并记录到任务说明。
5. 运行 `git diff --check`，消除空白错误和意外二进制变更。

## Task 2：完成 Android 薄适配与生产接线

**Files:**

- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ViewerNavigation.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagePairingAlgorithm.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PairingState.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/coil/TachiyomiImageDecoder.kt`
- Add/Modify: `app/src/main/java/eu/kanade/tachiyomi/data/coil/AndroidReaderPageDecoder.kt`
- Add/Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/AndroidReaderColorFilter.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/coil/AndroidReaderPageDecoderContractTest.kt`

**Consumes:** Task 1 的共享契约。

**Produces:** Android pairing/transition/navigation/filter/skip/preload/decoder/cache 的真实生产委托，而非仅存在同名类型。

1. RED：先运行 Android wiring 和 decoder 契约测试，确认每个测试能定位到生产调用链；新增遗漏场景时先让测试因旧私有分支或缺少 delegate 失败。

   ```powershell
   ./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.reader.ReaderSharedParityWiringTest" --tests "eu.kanade.tachiyomi.data.coil.AndroidReaderPageDecoderContractTest"
   ```

2. GREEN：最小化 Android 适配。`PagePairingAlgorithm` 委托 fork 提取的共享 `ReaderPagePairing` 增强；transition holder 消费 `ReaderChapter.sharedStateFlow`；`ViewerNavigation` 只映射 Android 点击输入；`ReaderViewModel` 用 `filterChaptersForReader` 和组合 policy；`HttpPageLoader` 消费共享预加载窗口和取消集合；`AndroidReaderPageDecoder`/`TachiyomiImageDecoder` 执行共享 decoder/cache policy；`AndroidReaderColorFilter` 把共享参数转成 Android 图层滤镜。
3. 删除已被共享核心覆盖的 Android 私有业务判断，但保留 SubsamplingScaleImageView、动画解码、Activity 生命周期、Coil 与 Bitmap 的平台代码。
4. 重构后重跑上述两类测试，并运行代表性 Android reader 回归：

   ```powershell
   ./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.reader.*" --tests "eu.kanade.tachiyomi.data.coil.*"
   ```

5. 若通配任务不被 Gradle runner 接受，改用已发现的具体测试类逐个运行并记录完整列表；不得以 runner 过滤问题替代测试。

## Task 3：完成 Desktop Skia、缓存与三种 viewer 生产接线

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/DualPageState.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/VirtualPageList.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderNavigator.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt`
- Add/Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopPageCache.kt`
- Add/Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopPageDecoders.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/SkiaImageDecoder.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderColorFilter.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DualPagePagerViewer.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/WebtoonViewer.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ZoomablePageBox.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/reader/PagePreloaderTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/reader/SkiaImageDecoderTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderNavigatorTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderScreenModelTest.kt`

**Consumes:** Task 1 shared contract、Task 2 已确认的 Android 默认语义。

**Produces:** 有界 Skia 解码/缓存、可取消预加载、Single/Dual/Webtoon 三种 viewer 的晚到缓存重组，以及统一章节状态与错误反馈。

1. RED：为真实 PNG region decode、向上取整采样、byte-budget LRU、快速翻页旧代次拒绝回填、旧窗口全部淘汰、晚到缓存触发重组、三 viewer 复用 cache 增补或确认失败测试。

   ```powershell
   ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.reader.PagePreloaderTest" --tests "mihon.desktop.reader.SkiaImageDecoderTest" --tests "mihon.desktop.ui.reader.ReaderScreenModelTest"
   ```

2. GREEN：`SkiaRegionPageDecoder.decodeRegion` 必须真正按区域和有界目标解码；采样比例使用 ceiling，保证输出宽高不超过上限；`DesktopPageCache` 按 decoded bytes 计量并 LRU 淘汰。
3. `PagePreloader` 每次位置变化生成 generation，取消无用 job，完整淘汰旧窗口 key，拒绝陈旧回填；只有当前代次写入后递增 `cacheGeneration`。
4. Single（`ZoomablePageBox`）、Dual（`DualPagePagerViewer`）和 Webtoon（`WebtoonViewer`）都观察 `cacheGeneration` 并优先消费缓存。缓存命中时不能同时保留重复全图请求。
5. `ReaderScreenModel`/`DesktopReaderScreen` 展示 Loading、Error、Retry、相邻章节 transition、缺章数量和 Boundary；`ReaderNavigator` 只把桌面输入映射到共享命令和 skip policy。
6. 重构后重跑本 Task 测试，并运行 Desktop reader/UI 回归集：

   ```powershell
   ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.reader.*" --tests "mihon.desktop.ui.reader.*"
   ```

## Task 4：锁定 Desktop 独有产品能力并清除重复规则

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderKeyboardAction.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderPreferences.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderSettingsPanel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/TapZone.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/PageSplitHalf.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DualPageLayoutPolicyTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/WebtoonAutoScrollTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderKeyboardNavigationPositionTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/WebtoonContextMenuTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DualPageContextMenuWiringTest.kt`

**Consumes:** Task 3 的 shared core Desktop 接线。

**Produces:** Desktop 产品能力零回退；shared 语义已覆盖的拆页、导航、跳过重复实现被删除。

1. RED：集中产品回归测试必须能在 edge matching、封面单页、Webtoon 自动滚动、键鼠导航、右键保存任一 wiring 被移除时失败；补测右键保存虚拟半页时保存用户看到的正确区域。
2. GREEN：Desktop 增强通过 `PagePairingOptions`、输入适配器或 UI side effect 显式叠加；不复制 shared split/navigation/skip 算法。
3. 删除 `DualPageState`、`VirtualPageList`、`TapZone` 等文件中已经由 common 覆盖的规则，仅保留布局状态、Compose 交互和 Desktop 产品参数。
4. 运行产品回归：

   ```powershell
   ./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.reader.DesktopReaderProductRegressionTest" --tests "mihon.desktop.ui.reader.DualPageLayoutPolicyTest" --tests "mihon.desktop.ui.reader.WebtoonAutoScrollTest" --tests "mihon.desktop.ui.reader.ReaderKeyboardNavigationPositionTest" --tests "mihon.desktop.ui.reader.WebtoonContextMenuTest" --tests "mihon.desktop.ui.reader.DualPageContextMenuWiringTest"
   ```

## Task 5：更新证据、文档并完成静态与集成验证

**Files:**

- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Add/Modify: `docs/architecture/reader-shared-core.md`
- Modify: `openspec/changes/align-reader-core/tasks.md`

**Consumes:** Tasks 1–4 的最终生产调用链和测试结果。

**Produces:** parity 9/43/44/45/47/49/51/54 的可执行证据、维护文档、干净的格式与集成测试结果。

1. parity manifest 的实现路径必须指向真实 production delegate；测试路径必须在 wiring 损坏时失败，不能只断言文件或符号存在。
2. `reader-shared-core.md` 说明唯一语义来源、两端 adapter 边界、Desktop 显式增强、并发代次、内存预算、错误/Retry、维护与失败处理。
3. 运行 parity、全部相关模块测试、格式和 diff 检查：

   ```powershell
   ./gradlew :domain:jvmTest
   ./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.reader.*" --tests "eu.kanade.tachiyomi.data.coil.*"
   ./gradlew :app-desktop:jvmTest
   ./gradlew :test-desktop:test
   ./gradlew spotlessCheck
   git diff --check
   ```

4. 如果仓库既有 Test Mode reader 接口因本次状态模型变化而失配，先添加能暴露 wiring 的失败集成测试，再做最小修复；若接口未受影响，记录现有 `:test-desktop:test` 结果，不做无关扩展。
5. 按实际结果勾选 `openspec/changes/align-reader-core/tasks.md`，不得预先勾选未完成的运行时验收和审查项。

## Task 6：有意提交、独立审查与修复

**Files:** Task 4A 范围内全部文件；排除所有无关未跟踪项和旧 Task 3B 草稿。

**Consumes:** Task 5 全绿工作树。

**Produces:** 一个边界清晰的功能提交、独立 reviewer 结论、Critical/Important 清零。

1. 提交前执行 `git status --short` 和 `git diff --name-only`，使用显式路径 `git add`；严禁 `git add .`。
2. 提交信息采用 `refactor(reader): align desktop with shared Mihon semantics`。提交可包含 shared core、双端 adapter、测试、parity 和架构文档，但不能包含 OpenSpec 之外的缓存/构建产物/旧草稿。
3. 启动新的独立 reviewer，按 OpenSpec 合规、shared/platform/product 边界、真实 production 调用链、TDD/集成证据、内存并发、用户行为和提交范围进行 thorough review。
4. reviewer 的 Critical/Important 问题由新的实现代理按 RED → GREEN → refactor 修复；重新运行受影响测试后提交 `fix(reader): address shared reader review findings`。对不成立的意见给出代码与测试证据，不盲目修改。
5. 重复独立复审，直到 Critical/Important 为零；记录剩余非阻塞建议，不把它们伪装成已完成工作。

## Task 7：Android、Windows 与必要 macOS 运行时验收

**Files/Artifacts:**

- Android APK: `app/build/outputs/apk/debug/app-debug.apk`
- Desktop build script: `scripts/build-desktop.sh`
- Windows EXE: `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe`
- Version source: `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt`

**Consumes:** 独立审查批准的当前提交。

**Produces:** Android 模拟器、Windows 固定 EXE、必要 macOS Skia 的当前提交运行证据和最终版本号。

1. Android：自行检查 `adb devices`；无可用设备时用本地 SDK 创建/启动 AVD。构建 `./gradlew assembleDebug`，执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。通过可用 fixture/测试入口验证章节打开、翻页、LTR/RTL、grayscale/invert、错误 Retry、章节 Boundary 和代表性大图路径；保存命令、设备/API、APK mtime 与结果。
2. Windows：只通过 Git Bash 运行 `./scripts/build-desktop.sh`，获得新的 BUILD。确认固定 EXE 存在且 mtime 晚于本轮构建；启动该 EXE，核对窗口标题中的完整版本与 `AppVersion.kt` 完全一致，并执行读者入口、三 viewer、滤镜、Retry/Boundary、键鼠和右键保存快速验收。
3. macOS：仅当 Skia region/cache/native 行为无法被 Windows/JVM 证据排除平台风险时，通过 `ssh mbp`，失败再用 `ssh mbp-lan`。同步当前提交快照后运行同一 Skia fixture/相关测试；不得使用旧应用或旧构建结果代替。
4. 运行最终 `git status --short`，确认运行时生成物未进入提交。把实际版本、固定 EXE 绝对路径、Android 设备证据和 macOS 是否需要/结果写入完成报告。
5. 所有验收通过后勾选剩余 OpenSpec task；如果运行时发现 bug，回到失败测试，按 TDD 修复、复审并重新构建，不向用户追加流程确认。
