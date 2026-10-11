# 双页阅读缩放问题调查与调整方案

日期：2026-10-11。范围：Windows Desktop 与 Android 双页模式，尤其是封面、尾页、手动单页和伙伴尚未出现时的缩放。优先级：阅读流畅、稳定，然后才是实现便利和其他功能。

建议保留共享配对、显示锁定、图片缓存和解码体系，局部调整呈现层：**初始图片仍按固定槽位排版，缩放、拖动、裁剪和高清可见区域统一使用完整阅读视口。** 这里的“完整视口”指阅读器可显示漫画的区域，不是自动隐藏系统栏或切换操作系统全屏。

两端已经分别执行真实 production 组件的预研。核心几何与资源复用可行性通过；正式修复尚未实施。实际大图瓦片绘制、动画图片、双图合成及设备帧时间仍是实施验收门槛，不能把本报告当作修复完成或性能通过证明。

## 调查基线与证据边界

初始提交为 `d5d6982bd93ccb2f743c039bb5211aeb88720c81`，工作区已有书架相关改动及 `docs/human/`。调查期间另一个任务提交书架修复 `71543f4359`；本轮测试运行在该提交上，没有修改或覆盖这些并行工作。

本轮只增加临时研究测试，未修改 production。测试完成后，临时测试源码与补丁保存在本机忽略目录 `.gradle-coordinator/dual-zoom-research/`，产品测试目录恢复原状；正式提交只包含本报告。没有构建 APK/EXE、安装设备、操作用户数据或执行完整模块测试。

SOURCE 表示实际读取的当前实现或本轮组件实验；PROJECT_POLICY 表示建议固定的产品契约。没有用 HTML 原型替代原生组件验证。

主要源码入口：[Android 双页 holder](../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPagerPageHolder.kt)、[Android 图片 View](../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt)、[Desktop 双页呈现](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DualPagePagerViewer.kt)、[Desktop 缩放和手势](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ZoomablePageBox.kt)、[Desktop 瓦片视口](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderRegionTileViewport.kt)。

## 两端原因与复现

### Android 的缩放画布被量成初始图片大小

SOURCE：`DualPagerPageHolder.kt:184` 仅在构造时为 `DisplayPage.Double` 启用 `containerGestures`。半槽 Single 继续使用子图 SSIV 或 PhotoView 的内部手势；`PageSlot.onMeasure`（同文件约 778 行）却将图片 View 精确量为槽内 Fit 后的图片矩形。

例如 1400×1000 视口中的 400×800 纵页，初始图片只有 500×1000。内部缩放的画布和触摸边界也只有这个大小，所以画面放大后仍困在原矩形。真正 Double 使用父容器变换，行为不同。

本轮真实 View、SSIV、MotionEvent 和像素实验结果：

| 场景 | 缩放前的红色像素矩形 | 双击或拖动后的结果 |
| --- | --- | --- |
| 左侧 Single 现状 | `(200,0)-(700,1000)` | SSIV 倍率增加，但像素矩形完全不变 |
| 右侧 Single 现状 | `(700,0)-(1200,1000)` | 同样保持原矩形 |
| 只关闭后代裁剪 | 左侧初始矩形 | 像素能扩到 x=912，但另一半屏触摸无法交给原子图，命中次数为 0 |
| 临时启用既有整组变换 | 左侧初始矩形 | 2 倍并拖动后为 `(300,0)-(1300,1000)`，可跨中缝；复位恢复原矩形 |
| 同上，右侧 | 右侧初始矩形 | 2 倍并拖动后为 `(100,0)-(1100,1000)`；复位正确 |

容器候选保留同一 View、SSIV、Bitmap，原图打开次数不增加。不过 SSIV 内部倍率不随父容器倍率改变，所以这不是最终建议的一行修复。

另有生命周期问题：`DualPageViewerAdapter.kt:350` 可以复用 holder，`DualPagerPageHolder.updateDisplayPage` 允许保持页侧的 Single 与 Double 更新，但构造时的 `containerGestures` 不变。手势所有者不应随临时页数判断，更不能在伙伴到达时切换并重置用户变换。

既有 `SharedDualPageSingleGestureNativeTest` 只确认封面仍采用 SSIV 内部缩放、外层倍率为 1，没有检查越过原图片矩形后的像素，因而没有覆盖此次缺陷。

### Windows 存在坐标与连续拖动问题

SOURCE：`DualPagePagerViewer.kt:139` 建立两个半宽槽，约 446 行分别挂载 `ZoomablePageBox` 并传入同一个 `ZoomState`。`ZoomablePageBox.kt:334` 之后的图层仍分别围绕各自槽位中心缩放。共享同一倍率不等于共享同一坐标系。

真实 Compose/Skia 像素证明，Windows **不是 Android 那种硬性半槽裁剪**：400×400 视口中，左页原来占 x=0..199，2 倍后能画到 x=299；右页也能越过中缝。但以完整视口为基准聚焦时，现有两个局部中心得到错误位置和留白。

| 实验 | 左页 | 右页 |
| --- | --- | --- |
| 当前实现，2 倍并朝目标页平移 200 | x=100..399 | x=0..299 |
| 同一 production 内容增加整视口变换对照 | x=0..399 | x=0..399 |

候选保留了 frame、mount、图片 holder 和 decoded asset 身份。它只证明整视口几何可行，外层研究包装不能直接照搬到整个 Pager，否则会连同翻页、反馈和视口观测一起缩放。

还执行了真实 Compose 鼠标 Press→连续四次 Move→Release。预期位移为 `[-30,-60,-90,-120]`，实际为 `[0,-30,-30,-30]`。`ZoomablePageBox.kt:202` 附近在首次超过阈值的移动后结束跟踪，只处理一次 delta。连续拖动停滞已经复现；本次页码保持 1，没有证据宣称已发生误翻页。

架构文档 `docs/architecture/reader-shared-core.md:419` 已要求整组手势和变换，当前 Desktop 没有完整落实。此次证据指向呈现层职责问题，不支持重写共享分组状态机或加载调度核心。

## 推荐方案

### 固定排版与整组视口分离

PROJECT_POLICY：每个显示 frame 保留独立的原始 Fit 矩形；缩放状态属于整个 frame 的阅读视口。半槽的作用是决定 1 倍时的位置，不再限制放大后的可见区域。

```text
共享分组与显示锁定
        ↓
固定的左图和右图初始矩形
        ↓
一份整视口缩放与平移状态
        ↓
平台图片绘制与高清区域计算
        ↓
完整阅读视口裁剪
```

缩放不重新配对、不更新内容版本、不重新打开原图、不拼接双页大 Bitmap，也不改变阅读进度格式。伙伴迟到时只增加另一张图；原页的 Fit、变换、图片租约和挂载身份保持不变。平移约束不能按“当前恰好加载了几张图”动态重算，以免伙伴到达时跳动。

所有采用左右物理槽的 frame 使用同一手势所有者，不以当前 Single/Double 枚举选择控制器。普通单页模式和原本 FULL 的宽图/整章仅一页优先保留既有路径；不顺带改写宽图拆页、源加载和配对算法。

放大入口仍是已有手势和快捷操作，不新增模式开关。缩放应围绕用户输入的焦点或明确的双击目标，双击复位恢复初始排版；“使用完整视口”不等于用户一放大就强制居中，也不意味着拉伸或裁掉整页来填满屏幕。

### Windows 复用现有图层和瓦片几何

优先让图片与 tiles 共享整视口变换，手势在未变换的视口坐标中连续消费。局部图片可以使用等价的全局 pivot/平移补偿，避免把加载、错误、重试和章节过渡一起移动；若采用单独的整组图片层，也必须将反馈留在原位置。

现有 `ReaderRegionDrawGeometry` 已包含视口、绘制位置、尺寸和源区域。请求高清瓦片时，保留各槽真实 Fit 尺寸，将请求几何转为整视口并加上 slot origin，然后调用现有逆变换及 tile grid/LOD 算法；不需要另造解码器。

本轮直接调用 production 几何函数确认：相同聚焦场景，半槽几何只请求原图 x=16、宽16，全视口几何正确请求 x=0、宽32。只移动图层而不调整瓦片请求会遗漏应显示的高清区域。

`ReaderPresentationTransformOwner` 管理 crop/split 的 Bitmap 租约，不是手势控制器；其无变换分支复用原 Bitmap、key 不包含 zoom，应保留。

### Android 用原生完整视口接回有效倍率

推荐继续复用 SSIV 的原生解码与瓦片能力，让图片绘制 View 的视口覆盖阅读区域，用独立的初始 Fit 几何保留半槽排版，再把整组状态映射为各图的原生 scale/center。不要只把现有 `containerGestures` 改为 true 后交付。

已核对本机实际使用的 SSIV `66e0db195d`：瓦片采样和可见性只读取内部 scale/width/height，不读取祖先倍率。公开 `CUSTOM` 最小倍率、`PAN_LIMIT_OUTSIDE` 和 `setScaleAndCenter` 可以表达偏心的半槽初始位置，无需先 fork 依赖。

本轮追加实验使用同一个 1600×3200 原图、View、父级和 Bitmap：将原生视口扩展至 1400×1000，公开 API 仍能精确还原 `(200,0)-(700,1000)`；2 倍和平移后跨越中缝，再复位。原生 scale 从 0.3125 变为 0.625，当前真实 SSIV 采样决策从 8 变为 2，原图未重新打开。这证明几何和高清采样入口可以同时表达，不证明实际瓦片已解码或设备性能通过。

实施必须保护以下 adapter 边界：

- 全视口 View 不能让后一张图的自动背景盖住前一张图；背景与反馈按各自图像或槽位绘制。
- EXIF 镜像需要按完整视口反解目标矩形，避免左右交换；旋转、crop 和原始 sourceBounds 沿用现有坐标转换。
- PhotoView 会在 `setDisplayMatrix` 后重新约束并居中小图，不能直接套用 SSIV 的 API。动画图片需明确单一外部矩阵适配，并保留同一 drawable 和动画时钟。
- `setScaleAndCenter` 不重建 decoder，但其 pending pose 会触发瓦片刷新。按显示帧合并手势更新、使用同一相机动画时钟，并实测连续缩放的任务量；不能用采样因子改善替代性能验收。

## 不采用的捷径

| 方案 | 不采用的原因 |
| --- | --- |
| 仅关闭 clip | Android 像素可以越界，但命中、内部平移和瓦片视口仍不一致；实验已证明另一半屏无法交互 |
| 把所有孤页初始位置改成 FULL | 改变已确认的封面/页侧语义，伙伴到达时容易重新 Fit 或移动已显示内容 |
| 只给父容器设置 scale | 几何能改善，但可能保留错误高清采样，还会移动重试等反馈 |
| 两张图预先拼成大 Bitmap | 增加内存、复制和解码成本，可能等待伙伴，破坏当前逐张呈现和租约管理 |
| 重写共享配对或整个阅读器 | 已定位问题不在该层；扩大回归面，增加流畅和稳定风险 |

## 实施批次与固定验收

以下是待实施方案，不推进既有 roadmap 或 parity manifest 的完成状态。

1. **共享行为契约与红测。** 沿用 `domain/src/commonTest/resources/reader/dual-page-fit.csv` 的跨平台向量方式，增加半槽缩放、聚焦、跨中缝拖动与复位向量，由两端真实组件消费。像素与窗口几何继续留在平台 adapter，不进入 `ReaderSessionCore`。固定正确/错误/迟到伙伴、LTR/RTL、左/右孤页、FULL 和窗口变化预期。
2. **Windows 功能批次。** 范围限定 `DualPagePagerViewer`、`ZoomablePageBox` 的双页适配、region 请求几何及相关测试。完成连续手势、图片与 tiles 的一致变换、固定反馈、右键对象命中；复用原图租约。通过新增红测、稳定呈现、region viewport 和 transform/bitmap ownership 的 focused 回归后同批提交。
3. **Android 功能批次。** 范围限定 dual holder、原生图片 viewport adapter 和相关测试；不修改普通单页/拆页链、配对数据或下载链。覆盖半槽 Single↔Double 原位更新、首帧位置、迟到伙伴、连续 pinch/双击/拖动、镜像、动画和背景。高清与实际 tile 测试必须使用真实原生控件，不能只读取 scale。
4. **整合与日常体验。** 两端各走一条真实应用入口，并对同图、同缓存、同窗口做修复前后对照。公共手势/图像生命周期由未实施者独立检查一次；只补验相关失败，不扩大到完整模块或发布矩阵。需要体验包时走仓库 preview/Android 规定入口，不递增正式桌面版本来完成本项验证。

实施验收必须包含：

- [ ] 双页模式 → 左侧封面或右侧尾页 → 放大并拖到另一半视口 → 图像可跨越原边界，继续拖动持续响应，复位恢复原位置。
- [ ] 已放大的孤页 → 伙伴迟到、重试或失败 → 原页位置、倍率、挂载和租约保持，加载/重试按钮仍可达。
- [ ] 双图、单图、LTR/RTL、宽图 FULL、窗口旋转/resize → 相同输入得到对应稳定几何，不切换错误的手势控制器。
- [ ] 缩放后拖动 → 不抢翻页；复位后 → 既有翻页恢复。双击焦点、动画时长及连续触点改变有真实事件测试。
- [ ] 大图、crop、EXIF、region tiles → 原图与细化瓦片同位，放大后清晰；离屏伙伴不因布局或预取伪造阅读进度。
- [ ] 动画图与自动背景 → 双图不互相遮盖、不重启动画、不泄漏图像句柄。
- [ ] 同机冷/暖缓存、慢伙伴、连续缩放及快速翻页对照 → 无新增等待伙伴/匹配的首帧阻塞，无可感停顿；记录帧时间分布、最慢帧、内存与解码任务峰值。结构证据和实测共同判定，不以平均数或通用“全绿”替代。

若高清细化与输入争用，应先减少合并前的重复瓦片请求、保持已有底图可画；不能阻塞手势等待高清图，也不能通过无限增加解码并发或整图缓存解决。窗口变化可显式重算几何；图片迟到不是重算当前视图的理由。

## 本轮实验记录

Gradle 全部经 `scripts/gradle-coordinator.py` 串行执行，离线、`--max-workers=1`。Android 使用专用 init 限定精确 class、1 GiB 堆、单 fork、关闭重试。SDK 三个必需文件均存在。本轮没有全量测试、正式构建或原生设备操作。

| 协调器 key | 范围与结果 | 解释 |
| --- | --- | --- |
| `dual-zoom-desktop-research` | 初次 3 项，1 过/2 失败，构建 24 秒 | 首轮错误假定封面在右侧；左页红测和候选失败不作为有效证据，原结果保留 |
| `dual-zoom-desktop-refined` | 4 项，2 个候选通过、2 个正确红测，构建 19 秒 | 两项红测分别证明局部缩放中心和连续拖动停滞；不是已修复后的绿色回归 |
| `dual-zoom-android-research` | 3 项全部通过，构建 2 分 14 秒 | 现状特征、关闭裁剪不足、既有容器候选；通过不表示现有 bug 消失 |
| `dual-zoom-android-native-viewport` | 原生视口候选 1 项通过，构建 31 秒 | 原像素位置、跨槽/复位、身份/资源保持及真实采样算法；`actualTileDecode=NOT_RUN` |

日志、argv、PID 和四份 XML 位于仓库本机 `.gradle-coordinator/` 下对应 key；XML 已单独保存，后续 Gradle 不会覆盖这四份证据。

可重放的实验输入为该目录下 `dual-zoom-research/desktop-probe.patch` 和 `dual-zoom-research/DualPageSingleZoomResearchProbeTest.kt`。Desktop 补丁基线测试 blob 是 `c379048d51df62b462d9ae4face3a1e52263f0a5`。本轮使用 2 个调查子代理，主代理复核源码、运行结果和方案边界；没有额外独立报告或审查轮次。

可复核入口：

```powershell
# Windows 研究测试恢复到临时源文件后
python scripts/gradle-coordinator.py run --key dual-zoom-desktop-replay -- .\gradlew.bat :app-desktop:jvmTest --tests 'mihon.desktop.ui.reader.SharedDualPageNativeStablePresentationTest.research*' --offline --max-workers=1 --console=plain

# Android 研究测试恢复到临时源文件后；精确运行4个研究方法
python scripts/gradle-coordinator.py run --key dual-zoom-android-replay -- .\gradlew.bat :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageSingleZoomResearchProbeTest' -I .gradle-coordinator/dual-zoom-android-research.init.gradle --offline --max-workers=1 --console=plain
```

复核前按根 AGENTS 设置 UTF-8、验证环境和 Android SDK；确认没有其他 Gradle 运行，并核对 archive 的基线再恢复测试，不能覆盖其他任务改动。这些本机过程文件不是产品代码或长期测试入口；正式实施应将有价值的预研断言改写为可维护的回归测试。

本轮结论是呈现层调整具有可执行的技术路径，关键坐标与资源复用预研通过。真实高清瓦片、动画适配和设备性能没有被标记为通过，须按上述固定验收完成后才能宣称 bug 已修复。
