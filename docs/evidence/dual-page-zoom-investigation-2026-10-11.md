# 双页阅读缩放问题调查与调整方案

日期：2026-10-11。范围：Windows Desktop 与 Android 双页模式，尤其是封面、尾页、手动单页和伙伴尚未出现时的缩放。优先级：阅读流畅、稳定，然后才是实现便利和其他功能。

建议保留共享配对、显示锁定、图片缓存和解码体系，局部调整呈现层：**初始图片仍按固定槽位排版，缩放、拖动、裁剪和高清可见区域统一使用完整阅读视口。** 这里的“完整视口”指阅读器可显示漫画的区域，不是自动隐藏系统栏或切换操作系统全屏。

本报告前半部分保留首次只读调查与组件预研的历史结论；后续用户已授权在独立工作树构建双端体验版，实际实现和验证见文末“预研体验版实施”。体验版验证与稳定发布、设备性能验收分别记录，不能互相替代。

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


## 预研体验版实施

用户在调查后要求：“已创建工作树，在本工作树中先构建预研性质的 windows 与安卓版本给我体验”。本次基线 `027ca888d24ff94a327eaf61963cbb7d8b274aa9`，工作树 `D:/Codex/worktrees/b7b3/mihon`，分支 `codex/dual-page-zoom-preview-20261011`。前文“未修改 production、未构建”等语句仅描述首次调查阶段；本节是后续实施证据。原有未跟踪 `docs/human/` 未修改、未提交。

### 已实现的行为与边界

- 两平台的 LEFT/RIGHT 孤页及双页共用整 frame 相机，初始 Fit 和左右槽位置不变；最大倍率仍为 5。普通单页与 FULL 图片保留原有适配路径。
- Windows 手势捕获位于未变换的完整视口。底图、高清瓦片和右键对象使用同一变换；反馈留在槽位，并保持在图片之上。区域请求先加 slot origin 再反算全视口，阅读曝光根据实际图像交集判断。持续鼠标拖动同时处理按住时的 Move/Enter/Exit；首个 Enter 含真实位移，不能遗漏。
- Android 原生图片 View 使用完整视口，SSIV 内部 scale/center 接入整组相机，继续使用同一 decoder 与原始输入。手势更新按显示帧合并，双击沿用设置中的动画时长；镜像在完整视口中反算，裁边保持原 Fit 内居中。动画图沿用原 drawable 与调度时钟，背景只绘制在实际图像矩形。
- 伙伴迟到、重试和 Single↔Double 原位更新不重置相机或原页；图片完全离屏时不因槽内空白仍可见而上报阅读。共享配对、显示锁定、缓存、解码租约和进度格式未改。

长期边界见[阅读器架构说明](../architecture/reader-shared-core.md)，实施范围见[本次计划](../roadmap/2026-10-11-dual-page-zoom-preview.md)。变更超过 8 文件/400 行的原因是完整相机需要同时覆盖两平台输入、底图/瓦片、曝光与原生适配，且必须带真实行为测试；没有借此扩展到分组、下载或数据库重构。

### 红绿与定向验证

共享 `domain/src/commonTest/resources/reader/dual-page-zoom.csv` 包含 9 条左右页、聚焦和跨中缝矩形向量，由双端 production 组件消费。所有 Gradle 任务串行经协调器运行；未运行完整模块或仓库全量测试。

| 协调器 key | 有效结果与解释 |
| --- | --- |
| `zoom-preview-desktop-red` | 3 项正确红测：左右聚焦错误，以及持续拖动停滞 |
| `zoom-preview-remaining-red` | Android 2 项正确红测：像素不跨槽、原生视口仍为局部；其中 Desktop 包名过滤错误的 no-match 不作为红测 |
| `zoom-preview-desktop-tiles-red` | 1 项正确红测：瓦片边缘缺失/离屏伙伴请求，底图相机与 region 几何不一致 |
| `zoom-preview-android-green2` | 30 项中 27 通过；3 个失败经查属于宿主夹具：Robolectric region decoder 返回空白位图，以及暂停时钟未逐帧推进动画；保留首次结果 |
| `zoom-preview-android-green3` | 30/30 通过：9 项新增视口行为、16 项 holder 集成、5 项原始方向接线；Robolectric 仅替换无法工作的底层 region codec seam，仍执行真实 SSIV 任务、瓦片选择和 Canvas 绘制，设备测试另验随包 JNI codec |
| `zoom-preview-desktop-green` | region 几何/接线、Bitmap 与 transform ownership 等相关范围通过；暴露首帧拖动和空白曝光两个目标失败，以及一条无关配对断言失败 |
| `zoom-preview-desktop-green2` | 42 项中 41 通过，包括新曝光回归、跨槽真实右键复制、多触点改变、动画与稳定挂载；剩首次 Enter 位移失败 |
| `zoom-preview-desktop-first-move` | 诊断确认首个按住移动是 Enter，具有真实 delta；保留失败证据，临时诊断输出已删除 |
| `zoom-preview-desktop-final-focused2` | 连续拖动、多触点切换 2/2 通过，6 个修改文件定向 Spotless/ktlint Apply+Check 通过；与此前未受后续修复影响的结果组合收口，没有声称最终代码重新全量通过 |

Android 新测试初次编译中的空值/方法调用错误，以及格式检查中的测试长名称问题，均单独修正，不作为行为红测。Desktop 模块没有独立 Spotless task，本次通过已有 app Spotless 配置定向检查 Desktop 6 个文件；首次错误 task 名未计作验证通过。

无关失败：`DesktopReaderProductRegressionTest.manual boundary removes conflicting automatic edge and retains unrelated edge`。该断言只走现有共享配对/presentation owner，未进入本次修改的输入和图像组件；相关 production 文件未变。本次没有修它，也没有在旧提交重跑，故只报告“未改动且无调用关联的失败”，不称已证明的基线通过/失败。

过程日志、argv、时间、PID、归档 XML 在本工作树忽略目录 `.gradle-coordinator/`，原始失败与补验结果均保留。两个代理分别实施平台变更，主代理独立复核输入坐标、曝光、资源身份和实际执行结果；没有增加第三个代理或全量验证。


### Android 原生窗口补验

使用本次新建的 `mihon-zoom-preview` AVD（API 36、`emulator-5586`、1536 MiB/2 核），明确核对实例、SDK、开发宿主 `app.mihon.desktop.fork.dev`、vc72 和测试包同一 Debug 证书。没有操作实体设备或既有模拟器数据。定向构建 `zoom-preview-android-native-build2` 通过（6 分 4 秒），包含本次 app 文件格式检查、Debug 与 AndroidTest APK。格式整理后的 `zoom-preview-android-refactor` 复验 30/30 通过（1 分 26 秒）。

原生测试通过真实 Copy 1.4.85 APK（SHA-256 `0a208f10b4f8fa3f3f0fae7704935e1a487c6ee342722c7e7818a02ad8c8dd6a`）、本机虚构 HTTP 响应、原 loader、原生 View 和窗口像素执行；章节和图片使用 `reader.example`、虚构标题和 PNG/GIF，不接触真实用户漫画。JSON 字段按该 APK 的真实 serializer 核实，原件与两份合成输入保留在本机忽略目录。

- 首次 `zoom-preview-android-native.log` 的 4 项均在缺少外置 JSON 的 fixture 构造时停止，没有进入产品验收。补齐暂存文件后，`native2` 的孤页双击和动画缩放/复位通过。
- `native2` 中伙伴错误重试用例受新 AVD 首次全屏系统提示遮挡；失败时真实 LEFT 错误信息和英文 Retry 按钮已显示。`retry-window.png` 证明拦截者是系统“Viewing full screen”提示。未修改应用反馈或弱化断言；仅给自建 AVD 设置已读提示后，`zoom-preview-android-retry.log` 原用例通过（1 项，7.199 秒），覆盖原 SQL 进度、固定反馈、实际触摸 Retry 和伙伴追加。
- 高清专项初次“2 倍必须产生更细采样”的断言错误：SSIV 的 base layer 已预解码细一档，该 AVD 的 1600×3200 夹具甚至已是 sample=1；因此既有底图能直接满足放大。测试需明确进入分级路径后，以真实捏合验证采样、同 decoder 与实际细瓦片绘制，不能把同档复用当作解码故障。


随后发现与流畅性相关的额外时序风险：SSIV 的 `onTilesInited` 在图片已测量且 max tile 已指定时，会先 `initialiseBaseLayer`，再进入下一次绘制；仅在 `onDraw/onReady` 设置 CUSTOM 可能晚于首次解码。全屏图片 View 不能先按整屏 CENTER_INSIDE 选 base 再缩回半槽，否则会发生不必要的细档解码。该风险既可能影响已测量控件重绑，也可能影响正常异步初始化；因此停止仅靠测试重建 grid 的方案，增加首次绑定的专门红测并修正预置时序，完成后再封存最终候选。

`zoom-preview-android-first-bind-red` 执行首次编码源绑定的真实 PageSlot/SSIV 链路，1 项因正确原因失败：1080×2400 视口中的 1600×3200 左侧孤页，base sample 预期 2，实际 1。该测试使用已布局但尚无图片/decoder 的新原生控件，替换 JVM 不可用的底层 codec，未重建 tile grid，也未修改采样 DPI 或 max tile 配置。原始 XML 已保存为同 key 的 `.xml`。

`zoom-preview-android-first-bind-green` 同一回归 1/1 通过。修复复用 holder 已读到的方向修正尺寸，在源绑定前及布局阶段预置半槽 CUSTOM minimum；没有增加读图、换 decoder 或改 SSIV 库。已知 raw ROI 先修正方向，未知裁边以整页尺寸保守起步，真实尺寸返回后沿用原相机精校。红测中另有旧夹具任务晚于 codec seam 启动而触发 JVM 缺 JNI 的后台日志，已通过提前安装 seam、等待任务结束后撤销来清理；绿色结果无该异常，主断言仍检验真实 native base 和初始像素。

`zoom-preview-android-final-check` 通过（1 分 35 秒）：目标 7 个 app 文件 Spotless/ktlint Apply+Check、31/31 focused tests（Viewport 10、holder 集成 16、方向接线 5）、Debug 与 AndroidTest 编译均完成。这是本次初始化修复后同一回归及影响范围的重构复验，XML 归档于对应 `-results/`。组合中的旧 bitmap 夹具仍有 Windows 缺 Android JNI 的后台日志，不能据此声称宿主已经验证 JNI；新增首绑用例单跑已清洁，随包 decoder 的正确性另由下述原生测试判定。

初始化修复后的 `zoom-preview-android-native-final.log` 执行 4 项，3 通过/1 失败（48.215 秒）：孤页双击、动画同 drawable 缩放/复位、错误伙伴触摸 Retry 均通过。高清用例已记录真实首次 base sample=2、捏合后 sample=1 的 4 张细瓦片；失败在测试要求“触点总间距扩大 4 倍，最终倍率也必须为 4”。实际 Android 手势开始帧重置 previous span，夹具首个有效跨度为 550，最终为 1600，因此倍率为 2.9090908，与 native scale 0.98181814 / 初始 0.3375 一致。只修正该用例的错误预期：仍发送同样的真实输入，检查实际 viewport/native 倍率一致、细瓦片完成、资源身份与实际绘制；未改 production 或另 3 项测试。首次失败保留，仅补验受影响方法。

`zoom-preview-android-native-span-check` 目标文件格式及 AndroidTest 增量构建通过（24 秒）。`zoom-preview-android-native-tile-recheck.log` 对上述单一方法补验通过（1 项，5.756 秒），已实际绘制细档 bitmap，确认同 native/decoder/provider 且真实窗口图片扩张。由首次 3 项通过加本次 1 项补验组合关闭原生范围；不描述为最终代码重新运行了完整 4 项。自建模拟器随后已停止，为最终打包释放内存。

### 体验产物与验证边界

Windows 最终执行 `zoom-preview-windows-final-package`，入口为 `scripts/build-desktop.sh preview`，构建与既有 production 扩展运行时检查通过；没有分配正式版本或执行完整测试。实际产物：

- [Windows 预研版 EXE](<D:/Codex/worktrees/b7b3/mihon/app-desktop/artifacts/preview/windows/20261011T135832Z-5b4f5b70/Mihon-Desktop-0.11.19.91.027ca88-unpacked/Mihon Desktop.exe>)，文件已确认存在，SHA-256 `b5251e021aed7192a12de000b69e35ba5b516fd8f71dade14b790b62e2a1fa35`。
- [Windows 构建清单](D:/Codex/worktrees/b7b3/mihon/app-desktop/artifacts/preview/windows/20261011T135832Z-5b4f5b70/preview-manifest.json) 记录 `build/sourceIntegrity/productionRuntime=PASS`、`nativeInteraction=NOT_RUN`；production 输入指纹 `e27445800212c2357e64df23762d90acfa0011a26270b8157b214385654fb182`。Windows 阅读器行为证据来自真实 Compose/Skia 组件事件与像素测试，不能将扩展运行时检查当成 EXE 阅读器的外部原生输入验收。

Android 执行 `python scripts/build-android.py candidate --offline`，内部协调器 `android-candidate` 构建通过（2 分 53 秒）；随后单独执行 `verify --artifact` 再次核验交付文件。

- [Android 预研版 APK](D:/Codex/worktrees/b7b3/mihon/app/artifacts/android/0.19.4-aex.54-zoom-preview-vc72-027ca888d2-release/Mihon-Fork-0.19.4-aex.54-zoom-preview-vc72-release-universal.apk)，SHA-256 `5334bafffc01aaebf2542f112f41cb3630efac37d2d1ff7b686c125a66e40df4`。
- [Android 产物清单](D:/Codex/worktrees/b7b3/mihon/app/artifacts/android/0.19.4-aex.54-zoom-preview-vc72-027ca888d2-release/artifact.json)：`app.mihon.desktop.fork`、vc72、`0.19.4-aex.54-zoom-preview`、不可调试、四种 ABI、R8/资源压缩开启、v2/v3 签名通过。保留原证书 SHA-256 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`；安装属于现有 fork 的升级，不是 `.dev` 并行安装。
- 本轮 R8 mapping 已随候选归档并校验，SHA-256 `eb1deac7f83e22b6ac4279933b9fd2142e4d857e6265b25da0caec241887cb5b`。没有执行 `install` 到实体设备，候选包运行和升级验收仍标为未执行。

两端最终清单记录同一个 production 输入指纹 `e27445800212c2357e64df23762d90acfa0011a26270b8157b214385654fb182`。产物在统一提交前生成，清单如实记录基线 `027ca888d24ff94a327eaf61963cbb7d8b274aa9` 加本次工作区输入；不能将其中的基线版本标识说成“遗漏了本次修复”，也不冒称产物直接构建于之后的新提交。测试和报告不进入 production 输入指纹，提交后只需复核同一指纹，不为 Git 状态变化重复构建。

体验步骤：Windows 退出旧版后打开上述 EXE；Android 自行安装上述同签名候选。两端进入阅读器的双页模式，选择封面或尾部孤页，双击/捏合放大后向另一半拖动，应能跨原槽位；复位回到原页侧，恢复正常翻页。普通单页、FULL 宽页沿用既有行为。

预研边界：未做长期帧时间、最慢帧、解码任务峰值和内存对照，未覆盖不同实体设备、厂商系统或 Release APK 的运行/升级验收。首次采样修复证明减少了不必要的细档请求，不等于已经量化整机性能收益。本批保护配对、缓存和资源生命周期，仍需用户体验反馈与上述性能验证才能据此判断稳定发布就绪。

## 用户验收后的正式开发与主干收口（2026-10-11–12）

用户确认上述双端体验验收通过，并要求正式开发、测试通过后合并主干。本批从 `e32e73ee63d182eedae4e8592c0e9bf75dcdde98` 继续，主干基线为 `027ca888d24ff94a327eaf61963cbb7d8b274aa9`。复用两位平台实施者，主代理串行运行验证并独立审查实际代码、红因、输入与资源接线；没有新增代理、全量测试或发布矩阵。

### 正式加固与结论

整视口相机、半槽初始 Fit、原生高清解码和缓存复用方案保持。此次仅修复测试证实的两个边界：

- Windows 原生输入取消产生的已消费释放，原先仍会记录为双击候选，紧随其后的拖动可能误复位。输入结束时清除该候选，正常双击和翻页逻辑保持。真实事件覆盖普通单页、双页及 Mouse/Touch。
- Android 双页 holder 卸载原先只释放呈现记录、取消绑定任务，没有回收左右原生图片。现在沿用普通单页的 `recycle()` 路径，释放 decoder、输入流和瓦片；重新挂载保留 View，重新绑定有效像素。既有 Pager 单击菜单/导航接线本来有效，无需另造手势处理。

本批超过 400 行主要来自两端真实组件和原生窗口回归、有限次数的资源与帧观察；产品变化限于上述输入收尾与图片释放。没有改配对、持久化、下载、缓存策略、解码并发或依赖。主代理复核共享输入的正常/取消路径、Android 代次失效与取消/回收顺序，并核对测试能在原实现失败；未发现剩余相关阻塞。

### 红绿、重构与影响范围验证

所有 Gradle 经协调器串行执行，离线、单 worker。Android 继续精确 class/method 过滤、1 GiB test heap、单 fork；格式检查只覆盖本批修改文件。

| 协调器 key | 结果及有效范围 |
| --- | --- |
| `zoom-formal-android-detach-red` | 夹具重复挂载导致已有 parent，未到产品断言，不计正确红测 |
| `zoom-formal-android-detach-red2` | 正确红测：真实 Activity 中卸载 holder 后原 Bitmap 未回收；1 项失败，57 秒 |
| `zoom-formal-android-detach-green` | 同一方法 1/1 通过，45 秒；再次挂载绘制成功，新增用例无 JNI/后台任务异常 |
| `zoom-formal-android-focused` | 32/32 通过：Viewport 11、holder 接线 16、方向接线 5；3 个修改文件 Spotless/ktlint、Debug 和 AndroidTest 构建通过，2 分 35 秒 |
| `zoom-formal-desktop-hardening` | 3 项中取消后卸载恢复、缩放中 resize 通过；循环尚未进入时，夹具误把反向返回 `[1,2]` 的当前页预期为 1，实际既有语义为 2 |
| `zoom-formal-desktop-cycles` | 测试缺少内部 Compose API 的局部 opt-in，编译失败；不计行为红测 |
| `zoom-formal-desktop-cycles2` | 四轮资源循环通过；原生取消后连续拖动的偏移预期 `[-30,-60,-90,-120]`，实际全 0，形成正确红测；39 秒 |
| `zoom-formal-desktop-cancel-green` | 原取消方法保持不变，最小修复后 1/1 通过，34 秒 |
| `zoom-formal-desktop-refactor` | 受影响真实组件类 28/28 通过，2 个修改文件 Spotless/ktlint 通过，45 秒；包括正常/取消/卸载的 Mouse/Touch 输入、跨槽像素与右键、触点变化、迟到伙伴、曝光、反馈、窗口变化和资源循环 |

日志及独立保存的 XML 位于本机 `.gradle-coordinator/` 对应 key，Android 最终 XML 位于 `zoom-formal-android-focused-results/`。这是当前影响范围的验证，不是完整模块或仓库全量通过。Android 旧的宿主 bitmap 夹具仍不能替代设备 JNI 验证；实际随包 decoder 由下述原生循环覆盖。

此前配对断言已在真实主干工作树、未修改的 `027ca888` 上单独运行：`zoom-formal-main-pairing-baseline`，26 秒，同样在 `DesktopReaderProductRegressionTest.manual boundary removes conflicting automatic edge and retains unrelated edge` 第 40 行失败。其调用链及相关生产/helper 文件与本次缩放变更无差异。本批保留该已证实的主干既有失败，不改变配对语义，也不把它算作本次测试通过。

### 有界稳定性与帧观察

Windows 使用真实 `ReaderContent` / ComposeScene / Skia 图片管线，以原输入入口做 4 轮 pinch、Mouse/Touch 连续拖动、双击复位和快速往返翻页。暖机后整个循环读取与解码均保持 6 次、holder 峰值 6，测试图片驻留/固定内存保持 36,864 bytes；关闭后缓存、租约、holder、驻留/固定/在途字节均归零，原生图片释放数等于解码数。

最终重构复验的离屏 `scene.render()` 记录：冷阶段 370 次，p95 3.383 ms、max 9.332 ms；暖阶段 656 次，p95 2.574 ms、max 6.477 ms。首次循环记录分别为冷 p95 4.600 / max 338.208 ms、暖 p95 2.709 / max 6.306 ms，原始结果保留，未删异常值。这些是小型合成图片的离屏渲染观察，排除了夹具 sleep，不包含实际 Windows GPU 呈现、屏幕帧间隔或真实大图解码性能；按帧采样的在途峰值 0 也不代表没有执行过任务。

Android 沿用专属 `mihon-zoom-preview` AVD（API 36、x86_64、1536 MiB、2 核、SwiftShader）。启动前检查空闲内存，核对 AVD、宿主 `.dev`、vc72 与原 Debug 证书；实际安装 APK SHA-256 与本轮文件一致。实体设备未操作。此次仅更新隔离开发宿主，不安装正式 Release 或改变用户数据。

原生新方法 `SharedDualPageSingleGestureNativeTest#repeatedGesturesKeepTapNavigationAndReleaseNativeResources` 通过真实窗口、原 HTTP/loader、SSIV、随包 JNI decoder 和既有 Pager 点按执行：4 轮捏合细化、取消拖动、缩放时菜单开关、双击复位，随后左右点按翻页/返回并退出。确认原 image/native/decoder/provider 在手势和相邻翻页中复用，不重新请求封面；退出后 decoder/provider 清空，观察过的 17 张瓦片均回收，前台原生任务归零。

首跑在 `fixture.cold()`、尚未进入场景前失败。专属旧模拟器中存在 5 个零字节 `.0` 文件，其 key 均不在 journal；`ChapterCache.clear()` 只按 journal 删除，因此夹具的物理清空断言失败。已保存目录/journal 证据，在再次核对 AVD、零长度及无索引后，仅删除这 5 个测试空孤儿；没有修改产品缓存、夹具断言或其他数据。其产生原因未确认，不将其归因于本次缩放改动。

原用例重跑 `zoom-formal-android-native-cycles2.log` **1/1 通过，19.683 秒**。真实 Window FrameMetrics：340 帧，p50 19.698 ms、p95 37.778 ms、max 201.842 ms，丢失的指标报告 0。原生产 executor 的观察包装记录提交 16 个任务、排队加运行的未完成任务峰值 4、结束 0；包装在 finally 恢复。PSS 起始 358,431 KiB，轮末采样峰值 358,431 KiB，退出后 352,493 KiB；不是连续采样的全局峰值。此软件模拟器存在超过帧预算的样本，不能据此宣称实体设备帧率达标，也没有修复前后同机硬件性能对照。循环完成后模拟器同步并正常关闭。

自动化实际使用的内部构建产物（不作为新的正式交付候选）：

- [Android Debug 验收宿主](D:/Codex/worktrees/b7b3/mihon/app/build/outputs/apk/debug/app-universal-debug.apk)，SHA-256 `3779a61582edffe30d01c91bca7121a47f8586a10b1fea74e2ce40eff07e9548`。
- [Android instrumentation 测试包](D:/Codex/worktrees/b7b3/mihon/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)，SHA-256 `55070c3da02a5c96dc2f5526738fe091818b79dcb804937ff31da3812c37544a`。

### 合并与交付边界

本批交付为正式实现、回归证据及本地主干合并，不另分配正式版本或发布安装包。前节体验 EXE/APK 对应已验收的预研源码，保留其真实版本和来源；本轮两项加固包含在新的源码提交中，不能将旧包标为该新提交的构建。用户体验验收与此次自动化共同支持本功能合入主干，不表示完整稳定发布矩阵、长期压力或不同实体硬件性能已通过。

合并前保护两工作树既有未跟踪 `docs/human/`，仅暂存本任务文件。Git fetch 已核对远端；本地主干有既有未推送提交，本批合并仅针对本地 `main`，不擅自推送无关历史。最终提交和主干包含关系由本轮完成报告记录。

## 既有 Desktop 配对测试失败的专项关闭（2026-10-12）

用户要求继续修复，并与会话 `01a11cdb-5dba-7140-887d-3a3216f14983` 协商。双方通过本机 `codex queue` 交换证据，对方只读核对产品契约及历史，确认由本工作树独占修改和验证，不并发修改文件或运行 Gradle。

诊断基线为 `8d5b5c3aac`，仅给原断言增加分组消息、给测试 helper 增加临时状态输出。`pairing-boundary-trace-red` 再次复现失败，实际分组为 `[[0],[1],[2],[3,4],[5,6]]`（以下索引均从 0 开始）。此前未修改旧主干 `027ca888` 的失败记录继续保留，不能将此次关闭描述为缩放变更引入或修复了产品配对回归。

已证实原因：`0ed7dce0d2c`（2026-10-09）为 `ReaderDualPagePresentationOwner.manualBoundaries` 引入旧格式边界的后继配对兼容规则。边界 `2` 除了要求该页单独显示，还要求紧接着的合法 `(3,4)` 配对；自动 `(4,5)` 与它共享页 `4`，因此并非旧测试声称的“无关配对”。该 Desktop 测试最后修改于 `dec006e7d3`（2026-10-05），断言未同步后来的契约。共享 `ReaderManualPairingIntentContractTest` 已覆盖后继配对、宽页例外及后来的显式关系优先级，另一个会话确认保留此产品规则。

此次只维护测试：原用例明确断言手动单页及后继配对覆盖相邻自动 `(2,3)`、`(4,5)`；另增 9 页章节中的远端 `(6,7)` 自动匹配，并以相同手动边界、仅缺远端匹配的样本作对照，证明该组合不会靠默认分组通过。两组夹具均调用真实共享 owner、边缘评分及 Desktop 呈现注册器，没有复制分组算法。诊断输出已移除，helper 恢复原样。没有生产代码、持久化、Android adapter、构建身份或依赖变化，因此无需生成新的 EXE/APK 或重复原生缩放验收；此次不是产品行为变更的红绿开发。

验证命令经协调器串行执行（离线、单 worker）：

```text
python scripts/gradle-coordinator.py run --key pairing-contract-focused --timeout-seconds 1200 -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.ui.reader.DesktopReaderProductRegressionTest --tests mihon.desktop.ui.reader.presentation.SharedDualPageDesktopPresentationWiringTest :domain:jvmTest --tests mihon.domain.reader.ReaderManualPairingIntentContractTest --offline --no-parallel --max-workers=1 --console=plain
```

结果 **32/32 通过**：Desktop 产品回归 9、Desktop 共享呈现接线 6、共享手动配对契约 17；19 秒。XML 归档在 `.gradle-coordinator/pairing-contract-focused-results/`，原失败 XML 为 `pairing-boundary-trace-red.xml`。这是旧基线失败加相关定向补验的关闭证据，不是最终代码重新通过全量测试。格式核验仅针对修改的 Kotlin 文件，协调器 key 为 `pairing-contract-format`；提交前另执行 `git diff --check`。
