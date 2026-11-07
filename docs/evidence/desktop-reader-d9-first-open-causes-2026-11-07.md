# Windows 单页首次打开的两段耗时归因

样本来自 Windows D9 冻结的 D7 控制包（源码基线 `3d7652e306` 加诊断）和 Android 上游 v0.19.9 隔离探针（`60793cc0a3`）。两端均在应用新进程中从作品详情打开同一已下载《史莱姆》第 04 卷，单页 RTL，恢复第 5/187 页地图。CBZ SHA-256 为 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。以下 UI 分段各取一次首次打开。时间均为应用内墙钟毫秒，原始探针开销包含在内。

## 页表就绪前

绕开两端“打开意图”位置不同的问题，以各自开始打开目标归档为共同职责边界。Windows 从 `AWT_MOUSE_UP` 计时，Android 从 `MainActivity` 分发 `ACTION_UP` 计时。

| 连续阶段（ms） | Windows | 上游 Android | Windows 多用 |
|---|---:|---:|---:|
| 松开章节到开始打开归档 | 51.480 | 44.711 | 6.770 |
| 开始打开归档到页表就绪 | 109.760 | 19.620 | 90.140 |
| 合计 | 161.240 | 64.331 | 96.910 |

Windows 报告页表就绪的是 worker 37，线程 ID 108。该协程实际执行了 `loadChapterContent → downloadDescriptors → archiveDescriptors`。其中 `archiveDescriptors` 的 104.420 ms 内可分别归到以下工作。

| Windows 实际调用 | 首次 ms | 所做工作 |
|---|---:|---|
| `openArchive` | 64.216 | 创建 Commons Compress ZIP reader，读取条目并检查条目可读性、压缩方式及文件名兼容性。该方法内部没有各项的独立时钟。 |
| `CommonsZipArchiveLease.readSnapshot` | 26.903 | 筛选图片、自然排序、用归档目录元数据计算内容指纹。 |
| `opaquePageRef` 的 187 次调用 | 10.208 | 每页用目录指纹和页名计算 SHA-256，生成页标识。这里按 187 次调用的区间求和。 |

这三项在该 worker 上互不嵌套。余下约 3.09 ms 位于同一 `archiveDescriptors` 调用内。在同一 Windows 进程再次打开同一页时，`openArchive` 降至约 9.6 ms，`readSnapshot` 降至约 2.2 ms，187 次 `opaquePageRef` 共 4.2 ms。Android 首次建立归档加载器和列出页面分别为 0.576 和 18.953 ms，两端可以将归档入口到页表进行整体对照。归档库可以把不同工作放在构造或枚举时做。同期 Windows 界面线程在构造 `ReaderViewport` 时约用 57.7 ms，其中采到 Windows 全屏 adapter 初次初始化；这段与后台归档处理重叠，没有将它再计入页表差距。

### 首次使用 ZIP 路径的独立干预

取冻结包的原始 JAR 和原始应用 classpath，无窗口调用生产入口 `DesktopReaderContentAdapter.archiveDescriptors`，每次交给全新 adapter，验证返回 187 个页描述符。每个样本独立启动一个 JVM，Java 21.0.11 的 `jvm.dll` 与包内实读 SHA-256 相同。宿主已校验真实归档哈希，测试的是暖文件缓存。两张图片名的小 CBZ 与目标文件独立。类名从一次单独的类加载跟踪试跑取得，12 次正式测量均关闭方法跟踪和类日志，使用相同列表读取、两个快照计时钩子和同一框架。交错次序为 A/B/C/D、D/C/B/A、C/A/D/B。

| 测目标 CBZ 前在 JVM 中执行的操作 | 同一目标的三次页描述处理（ms） | 中位数（ms） | 计时期间 JVM 新加载类数 |
|---|---|---:|---:|
| A，直接测 | 178.560 / 165.632 / 161.074 | 165.632 | 679 |
| B，仅预先加载 124 个 Apache Commons 类，不执行静态初始化 | 136.720 / 130.954 / 130.121 | 130.954 | 553 |
| C，预先加载 236 个相关类，含上述 124 个，不执行静态初始化 | 113.332 / 110.632 / 113.187 | 113.187 | 441 |
| D，先用独立 adapter 打开另一份两页归档并关闭 | 39.567 / 42.979 / 40.850 | 40.850 | 71 |

仅把 Apache Commons 类加载移到计时前，该组件的中位数降低 34.678 ms。先走一遍另一归档还会改变类初始化、方法链接、JIT、安全提供程序和文件 API 的首次使用状态，实验未将这些额外因素逐一分开。组件计时没有前序 UI，不能将上述降幅直接套到已打开书架和详情的产品界面，也未测改用其他 ZIP 库的收益。

## 从页表到目标页显示取图

| 里程碑（相对页表就绪，ms） | Windows | 上游 Android |
|---|---:|---:|
| 各自完成首轮章节状态交接 | 37.128 | 36.310 |
| 开始创建目标页容器 | 171.484 | 43.065 |
| 显示链开始取目标图 | 189.498 | 77.968 |

上表第一行在 Windows 取首次 Loaded 的 `acceptSessionState` 返回，在 Android 取 `ReaderActivity.setChapters` 完成向 viewer 委托。Android 此时 adapter 已绑定；Windows 此时 model 已接收状态，Loaded 界面还要进行组合。这项职责差异对解释后续延迟很重要。从这一里程碑到目标容器，两端分别用 134.356 和 6.755 ms，观测差 127.601 ms。容器开始创建后到显示取图则是 18.014 和 34.903 ms。两段合起来解释为何容器处相差 128.419 ms，到显示取图缩为 111.531 ms。

Windows 同一次打开实际经历了以下调用，时间继续相对页表就绪。

| 时间（ms） | 观察到的操作 |
|---|---|
| 0.22–37.13 | 第一次摄入 Loaded 章节并维护共享状态。其中首轮页身份准备约 18.4 ms、首轮呈现状态投影约 10.9 ms，已包含在 37.13 内。 |
| 37.70–97.82 | 首次组合 Loaded `ReaderViewport`。内部单页呈现模型 `SinglePagedPresentation.present` 约 6.1 ms，`SinglePagePagerViewer` 约 37.3 ms。界面线程调用栈实际出现 JVM 定义类、为 lambda 链接调用点及计算阅读区几何。 |
| 103.82–112.91 | 提交真实视口并调度页面准备。pager 的呈现 effect 调用 `beginPagerViewport → scheduleViewportWithoutProgress → settleViewport`。该单页路径先产生目标内容需求。 |
| 121.72、138.48、160.49 | 三次进入单页容器。前两次没有调用图片内容，第三次才执行 `readyContent`。目标 index 4 的归档拷贝实际为 129.12–130.51。 |
| 163.55、171.43、171.48 | 进入 `ReaderPageImageContent`，随后 Compose 执行可移动内容的实际 body，最后开始创建 holder。 |
| 176.09、187.82、189.50 | holder 构造完成，slot 应用并 acquire，显示链请求原图。 |

从代码可以确定，单页容器只在 `page.loadState == Ready` 时启动图片内容；Queued、Resolving 和 Downloading 只显示加载提示。Android 的 ViewPager 则能先创建 `PagerPageHolder`，让 holder 的协程随后观察 READY 并打开流。Windows 首次记录中，目标页图片内容已在 163.55 ms 被调用，目标 header 直到 169.37 ms 才报告 accepted；不能用“等待目标 header 读完”解释之前的整段时间。第一轮 session 摄入确有双页相关工作，D8 已删除其中一处重复准备，D9 实测未包含 D8。

探针未记录前两次容器调用的 loadState 参数或 Compose skip 标志，因此仅能确定当时未调用图片内容。类加载是栈采样事实，没有给每个类或每项 Compose 操作分配独立毫秒。D7 更改订阅位置、D5 更改准备时机的既有对照不能代替对新假设的验证。若要评价后续候选，应在相同的当前主干、相同目标页上检验是否真的提前了 Ready 摄入、容器创建及漫画绘出。

源码入口为 [桌面归档](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderContentAdapter.kt)、[单页容器](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/SinglePagePagerViewer.kt)、[可移动图片内容](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderPageImageContent.kt) 和 [桌面阅读会话](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderSession.kt)。本机原件见 [两端分界复算](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/cause-crosscheck.json)、[12 次组件实测](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-cause/measurements-matched.csv)、[组件测试入口](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-cause/run.py) 和 [D9 首次原始调用](D:/Codex/worktrees/mihon-main-reader-delivery-20261106/.test-tmp/desktop-reader-d9-methods-20261107/actual/on/methods/events-1.tsv)。
