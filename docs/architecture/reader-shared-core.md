# 阅读器共享核心

## 目的与边界

Android Mihon 是阅读器行为的权威来源。`domain/src/commonMain/kotlin/mihon/domain/reader/` 保存不依赖 UI、图片库或输入设备的唯一语义，Android 与 Desktop 的生产阅读器只做平台适配，Desktop 产品增强再以显式选项叠加。后续修改页序、章节过渡、导航、跳过、预加载或滤镜参数时，必须先修改共享契约与测试，禁止在任一平台重建平行算法。

本架构不改变数据库、备份文件或阅读进度持久化格式；它只收敛阅读器运行时决策。

| 唯一语义来源 | 职责 |
|---|---|
| `ReaderPageModel.kt` | 页面/章节/过渡状态、Retry 与 Boundary 命令、解码/区域解码/缓存接口、预加载窗口与 generation |
| `PageTransform.kt` | 旋转后尺寸与宽页判断、像素拆分边界、虚拟页映射、双页配对和滤镜参数 |
| `ReaderNavigation.kt` | Android 点击区域预设、阅读方向反转、物理方向命令、read/filtered/duplicate 组合跳过 |

共享 API 不得引用 Android `Bitmap`/`View`、Compose `ImageBitmap`、Skia、Coil 或平台输入事件。它只描述请求、结果、状态、命令和字节预算。

## Android adapter

Android 保留 View/Canvas、Bitmap、SubsamplingScaleImageView、Coil、Activity 生命周期与触摸输入：

- `AndroidTachiyomiPageDecoder` 以 `BufferedSource` 适配共享 `PageDecoder`，`TachiyomiImageDecoder` 把 `PageDecodeRequest` 和 generation 交给该 delegate；过期结果在提交图片前被拒绝。
- `ReaderPageImageView` 通过共享 `PageDecodeCachePolicy.TILED_READER` 禁止 Coil 再长期保存一份完整解码页；tile、动画和 Android 控件缓存仍属于平台层。
- `PagePairingAlgorithm`、`ViewerNavigation`、`ReaderChapter.sharedStateFlow`、`ReaderViewModel` 和 `HttpPageLoader` 分别消费共享配对、导航、章节状态、跳过与预加载契约。
- Android 权威 HTTP 预加载仍是当前页向后 4 页，通过 `ReaderPreloadPlanner(windowSize = 4, backwardWindowSize = 0)` 表达，不再保留另一套私有窗口算法。

## Desktop adapter 与显式增强

Desktop 保留 Compose/Skia、键盘和鼠标、右键保存、Webtoon 自动滚动、双页封面单页、edge matching 与 landscape parity。这些永久产品能力只能作为 `PagePairingOptions`、输入 adapter 或 UI side effect 显式启用，不能改变共享默认结果：

- `VirtualPageList` 委托共享虚拟页映射；`DualPageState` 委托 `ReaderPairingState`，再显式传入封面单页、forced single、matched pairs 与 spread 后 parity。
- `TapZone` 与 `ReaderKeyboardAction` 把桌面鼠标/键盘输入转换为共享逻辑命令；页码存储、滚动和章节切换仍由 Desktop UI 执行。
- 三种 viewer 都观察 `PagePreloader.cacheRevision`，晚到的当前 generation 缓存写入会触发重组；缓存命中时不再并行保留重复全图请求。
- 右键保存虚拟拆分页时使用共享虚拟页映射得到的 `sourceBounds`，只保存用户看到的半页；没有 bounds 时才保存完整原图。

## 解码、内存预算与并发

Desktop 数据流如下：

```text
页面位置变化
  → ReaderPreloadPlanner.moveTo(generation/window)
  → 取消旧 job + 淘汰旧窗口全部 key
  → 获取 encoded bytes + 探测尺寸
  → SkiaPageDecoder 或 SkiaRegionPageDecoder
  → DesktopPageCache(byte-budget LRU)
  → cacheRevision StateFlow
  → Single / Dual / Webtoon 重组
```

采样倍率使用向上取整，确保输出宽高不超过请求上限。超大图由 `SkiaRegionPageDecoder` 把请求的 source region 直接缩放到有界输出；预加载完整逻辑页时该 region 可以是完整 source bounds，但普通缓存只接收有界输出，不长期保存全尺寸 bitmap。

`DesktopPageCache` 按实际 decoded bytes 而不是条目数限制内存。超预算单项拒绝写入且不驱逐现有条目；正常写入按 LRU 淘汰，并通过 `PageCacheSnapshot` 暴露 generation、key 和 used/max bytes。

每次页面位置变化创建新 generation：

1. 取消所有不再需要的 active/queued job；
2. 完整淘汰旧窗口 key；
3. job 完成时核对 generation 和保留窗口；
4. 过期、取消、尺寸未知或解码失败的结果不写缓存，也不递增 cache revision；
5. 只有当前 generation 成功写入后才发布可观察 revision。

Android `HttpPageLoader` 使用同一代次/取消契约；非协作的旧请求即使晚到，也不能把旧页发布为 Ready 或 Error。

## 状态、Retry、Boundary 与用户反馈

章节状态统一为 `Wait`、`Loading`、`Loaded`、`Error(AppError)`。阅读器入口仍从漫画详情页打开章节，三种 Desktop viewer 与 Android pager/Webtoon holder 必须保留以下用户可见行为：

- 页面加载中显示 Loading；URL、缓存或解码失败显示明确 Error，而不是空白页；
- 当前章或相邻章失败时显示 Retry，重新发起同一共享 `RetryChapter` 命令；
- 存在相邻章节时显示目标标题和缺章数，Continue 才切换，Dismiss 留在当前章；
- 跳过策略耗尽或不存在相邻章时显示明确 Boundary，不越界打开其他章节；
- 预加载失败不阻断当前可见页，用户仍可对当前页执行 Retry。

## 滤镜与章节跳过

`ReaderColorFilterParams` 是亮度、色调、grayscale、invert、归一化和 `isEffective` 的唯一组合规则。Android 先把偏好映射为共享参数，再用 Android 硬件层 `ColorMatrix` 渲染；Desktop 保存相同参数并用 Compose `ColorMatrix` 渲染。两个平台可以使用不同图形 API，但开关组合、边界值、即时预览和持久化结果必须一致。

read、filtered、duplicate 三类章节跳过统一由 `ChapterSkipPolicy`、`filterChaptersForReader` 与 `findAdjacentChapter` 决定。Android `ReaderViewModel` 和 Desktop `ReaderNavigator` 只负责映射章节元数据；当前正在打开的章节始终保留，书库/详情层不得新增另一套跳过决策。

## 维护、验证与失败处理

1. 先把 Android 现有行为写成 `ReaderParityContractTest` 向量并确认 RED，再修改共享实现。
2. 平台 adapter 必须有行为测试，能在生产 delegate 被删除或绕过时失败；仅检查文件或类型存在不算证据。
3. Desktop 产品增强必须有集中回归测试，并保持显式 options/输入/UI side effect 边界。
4. parity 9、43、44、45、47、49、51、54 只有共享契约、Android 生产消费、Desktop 生产消费及保护测试都存在时才标为 `WIRED`。
5. 每次变更至少运行 domain common、Android reader/Coil、Desktop reader/UI/parity、`:test-desktop:test`、Spotless 与 diff 检查；Android 模拟器、Windows 固定 EXE 和必要 macOS Skia 验收属于运行时发布门禁。

本次状态模型没有改变现有 Test Mode HTTP reader 接口；`:test-desktop:test` 继续作为外部导航/状态协议的集成证据，不为共享核心增加另一套测试专用状态。如果未来 Test Mode 与生产状态失配，先添加失败集成测试，再在现有协议 adapter 做最小映射修复。

生产 wiring 失败时可以回滚入口切换提交；不得通过恢复长期双轨拆页、导航、跳过或预加载算法解决。未知尺寸、损坏图片、取消和过期代次都应产生明确失败或静默丢弃过期结果，并保持缓存与当前可见状态一致。
