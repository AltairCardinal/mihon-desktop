# Windows 首次打开下载 CBZ 的两项目标实测

同一已下载《史莱姆》第 04 卷，187 个图片页，单页 RTL，恢复第 5 页地图。上游 Android v0.19.9 真机探针记录了两个基准，归档开始打开至页表就绪 19.620 ms，页表就绪至开始创建目标页容器 43.065 ms。Windows D9 当次记录分别为 109.760 和 171.484 ms。详细时序见[两端分段归因](desktop-reader-d9-first-open-causes-2026-11-07.md)。以下保留诊断环境和产品窗口两种测量的边界。

## 归档目录读取

无需预建作品索引的实测路径使用 [miniz 3.1.0](https://github.com/richgel999/miniz/tree/174573d60290f447c13a2b1b3405de2b96e27d6c)。每次打开取得一个全新 Windows 文件句柄，在该句柄上用 `mz_zip_reader_init_cfile` 打开 ZIP，从中央目录一次取得条目元数据。按现有格式计算目录指纹和各页 SHA-256 标识，建立 187 个真实 `ReaderPageDescriptor`，批量交给现有章节会话。后续从同一个句柄读目标页。这样首次打开避开 Commons Compress 的初始化和 JVM 上逐条生成指纹、页标识的原路径，格式和身份仍然逐个校验。未单独测定 miniz、原生 SHA-256 和批量跨 JNI 三者各自的贡献。

### 接回现有适配器、租约和生产会话后的结果

在隔离目录编译与基线相同的完整 `DesktopReaderContentAdapter`，只加入按格式选择的新归档租约。两臂从同一个 `archiveDescriptors` 入口开始，生产会话提供实际的非零租约代次，终点都是生产 `PAGE_LIST_READY`。原有代次保护、登记、释放、`copyArchivePage` 和普通文件名回退继续执行。从实际登记的租约核实本卷选中了 miniz，非简单命名样本选中了 Commons。两条路径均从该适配器读出正确页图，切代后再用旧页标识被拒绝。

| 完整适配器至生产会话页表就绪，每样本全新 JVM | 所有样本（ms） | 中位数（ms） |
|---|---|---:|
| Commons | 169.731、182.130、137.616 | 169.731 |
| miniz，含点击时首次载入 DLL | 31.603、8.706、7.534、8.263 | 8.484 |
| miniz，点击前只载入通用 DLL | 6.225、7.526、6.365、6.454、6.147 | 6.365 |

推荐在 Windows 应用空闲时加载 DLL，无需提前知道用户会选哪一章。上述五个样本均低于官方 19.620 ms 参考。点击才首次触碰新构建 DLL 出现 31.603 ms，超过目标。一次独立启动实测中 DLL 加载本身为 33.036 ms，说明移出点击路径的工作也有成本。应用启动后立即点击、宿主冷文件缓存和其它 CBZ 类型需要独立覆盖；这张表所用会话未启动窗口。集成结果见[首批数据](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/integrated-results.csv)、[追加独立样本](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/integrated-followup.csv)、[两种后端与切代实测](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/integrated-checks.txt) 和[适配器装载核验](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/overlay-proof.json)。

### 前置诊断：仅替换内容端口

复用 D9 控制包所用的冻结桌面 JAR、classpath 和真实 `DesktopReaderSession.start()`。仅替换本地内容端口。控制臂走原 `DesktopReaderContentAdapter.archiveDescriptors`，候选走上述原生适配。每样本独立 JVM，不打开窗口，不预读目标章节，不清宿主文件缓存。起点位于内容端口决定打开 CBZ 之前，终点取生产 `ReaderIoReporter` 发出的 `PAGE_LIST_READY` 单调时钟。各臂都核实了第 1 代会话已处于 Loaded，187 个索引及页面标识逐个一致。候选另从原句柄读取了 357,852 字节的第 5 页，SHA-256 为 `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`。

| 同一驱动、每次全新进程 | 所有样本（ms） | 中位数（ms） |
|---|---|---:|
| 现有 Commons 路径 | 159.808、181.023、168.820、145.200 | 164.314 |
| miniz，含本次载入 DLL | 3.951、3.698、4.100、3.543、3.995 | 3.951 |
| miniz，仅 DLL 在计时前载入 | 2.386、3.135、2.567 | 2.567 |

这组前置诊断覆盖到生产会话发布完整页表，未含归档租约注册和正常 UI，不能从 164.314 和 3.951 的差值推导窗口或可见黑屏收益。计时结束后另做了整卷校验，使用 Python 标准库独立解包生成参照。原型按页表次序取出的 187/187 份图像数据均匹配对应的 SHA-256，合计 41,348,502 字节。[逐页校验结果](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/all-pages-check.txt)

已核对冻结包源码 `3d7652e306` 到本轮主干基线 `660b18cc57` 的差异。归档适配器无变化，`DesktopReaderSession` 在此次页表事件之后增加了状态发布和视口调度观测。窗口候选另以 `660b18cc57` 为基线构建。

### 首次触碰新 DLL 的慢样本

另一个驱动从调用章节物化器之前到 `ReaderSessionCore` 接收 187 页 Loaded 返回，每次使用新 JVM。现有路径四次为 151.019–155.416 ms。直接载入 DLL 的五次为 28.918、7.078、7.448、7.265、7.150 ms。第一次运行刚构建的 DLL，至原生打开归档完成已过 25.675 ms，该次超过 19.620 ms；这段包含 DLL 加载与开档，没有系统事件可分摊责任。只在计时前载入 DLL，另三次为 6.491、6.708、6.073 ms。两种驱动在前序类初始化状态和计时端点上有别，样本分开报告。应用可在空闲时只载入通用库，冷档案和不同作品仍按点击时读取。实验没有测量断电后的冷文件缓存，也没有测 DLL 载入与启动输入竞争。

### 兼容边界和复查方法

原型仅接收已验证的简单 ZIP 子集，包括本卷的等宽数字 WebP 页名、可选 ComicInfo.xml、Store 或 Deflate。对复杂命名、目录、冲突、其它压缩形式等，接入产品时必须保留原适配器回退。额外夹具在旧句柄仍活着时，先改名移开旧文件，再将等长同修改时间的新归档移入同一路径。两个句柄分别读出旧、新页内容，各自页标识也分别匹配原实现。另一个非简单命名夹具被原型拒绝后，由现有适配器列出正确页面。该回退在测试驱动验证。单次原子覆盖打开文件的情形未获得有效的路径替换样本。

本机[会话原始逐次结果](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/session-results.csv)、[物化器对照结果](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/results.csv)、[替换与回退校验](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/lease-check.txt)、[会话驱动](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/SessionBench.java)、[归档原型](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-native/native.cpp)。原档 SHA-256 为 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。

### 作为备选的持久化索引

另一个实现预先建好每章索引，进入详情后预加载索引和该章的全部描述符。点击时打开文件的新句柄，从同一文件句柄核对原始 ZIP 中央目录字节，命中后返回真实 187 页描述符。三次新进程命中为 1.650–2.008 ms，中位数 1.891 ms；同一文件不命中回退为 183.531 ms。按章建索引一次 211.571 ms，每个新进程提前加载该章索引和对象花 58.974–72.371 ms。端点止于返回描述符；未测该备用方案接入会话至页表发布。等长、相同路径、创建和修改时间不变、叶内容变化的夹具触发了中央目录内容失效，回退后取出了新数据。对本卷优先无索引方案，避免把每章约 59–72 ms 移到详情。 [索引实验数据](D:/Shell/Github/mihon/.test-tmp/upstream-map-d9-compare-20261107/archive-next/results.json)

## 页表就绪至创建实际第 5 页容器

窗口实验提交 `904bc8d34308459b69cdfd8dd8c7dafe893d2d32`，基线 `660b18cc57`。将首屏可见的单页视口先挂载，首图绘出后把同一图片内容交给完整分页器。五个定向测试通过，含第 5 页 Queued → Ready → Skia 绘出 → 分页器接手、同一 holder 只创建一次。首开从隔离配置的书架进入作品详情，正常点击章节。全新进程打开第 5/187 页，未使用 D9 的方法计时 Agent。细探针零丢弃。

| 相对真实会话的页表就绪（ms） | 官方 Android 先前真机 | Windows 轻量视口，首次 |
|---|---:|---:|
| 开始创建真实目标页 holder | 43.065 | 99.901 |
| 真实目标页 holder 创建完成 | 43.330 | 105.769 |
| 目标图片显示链开始取图 | 77.968 | 119.240 |
| 应用报告绘出目标图 | 183.632 | 190.645 |

首次摄入 Loaded 在 +1.074 至 +19.822 ms。目标视口调度在 +40.717 至 +45.753 ms，视口提交为 +46.792 ms，此时尚无目标图片 holder。目标页归档复制为 +62.261 至 +64.647 ms。随后 Compose 在 +99.901 ms 调用真正的 `DesktopReaderPresentationImageOwner.createHolder`。其 `System.identityHashCode` 从首次绑定到分页器接手一直为 605374265。D9 的 171.484 ms 样本另开了方法计时，两者差值不能作为净收益。[候选里程碑与对象身份](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/firstA/first/feasibility.json)

上游 `PagerPageHolder` 可以先创建，之后观察图片 Ready。此桌面候选让单页优先进入视口，但仍沿用现有图片 holder 的契约。`readerPresentationDecodeKey` 要求 `page.encodedPageRef` 已经存在，且 `SinglePageDisplayUnitContainer` 在 Ready 才调用图片内容。在这次首次打开中，即使把复制之后的工作都降到零，复制完成时已经比 43.065 ms 目标晚了 21.582 ms。缩短归档目录读取会提前页表基准点，两项独立试验的结果无法直接相加推算端到端首绘。

### 只增加首次类初始化预热的对照

同一候选包、新进程、新配置。从应用启动 3 秒后，在后台预先加载和初始化实际路径中的 64 个产品类及 671 个依赖，不创建 reader 或 holder、不读目标档。735 项完成耗时 189.388 ms，本次在完成 36.142 秒后点击，不能外推到启动后立刻点击。目标视口提交 +24.442 ms，目标页复制 +36.961 至 +38.501 ms，带真实 encodedRef 的 Ready 快照 +41.521 ms 到达，模型接收完成 +44.463 ms。容器开始和完成分别为 +67.838 和 +69.593 ms，实际取图 +86.508 ms，应用首绘 +148.998 ms。Ready 摄入完成至容器创建为 23.375 ms，额外 Ready 观测 18 次共 0.851 ms。只有一个实际 holder，身份 822654019。单纯预热和简化分页器没有达到 43.065 ms。[同次完整记录](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/warmA/first/feasibility.json)

### 同一实际容器先挂载，再绑定图像

隔离分支 `codex/reader-single-first-slot-20261107` 的 `3b2040111964d4cbedca029269ada10e4a09959b` 改了现有 `DesktopReaderPresentationImageHolder` 的生命周期。首次单页视口拿到 Queued 页后，用页身份、代次和重试号注册并挂载实际 holder。Ready 带来 encodedRef 时才在同一个 holder 上验证并绑定 decodeKey、创建 region 和获取图像。切换到完整分页器时保留同一对象。创建、实际应用到 Compose、内容绑定、打开目标数据、应用首绘分别计时。定向测试检查 Ready 前没有读取、Ready 后同对象绘图、取消后释放；原来必须在构造时提供 decodeKey 的实现实际未通过这个用例。

两个样本使用相同的通用类预热清单，两项源代码已移除的匿名类不再存在；其余 733 项成功。全新进程与独立配置，正常从书架点击相同地图页。以下时间都相对本次页表就绪。

| Windows 同一次首次打开（ms） | 原归档后端 A | 原归档后端 B |
|---|---:|---:|
| 实际 holder 开始创建 | 20.755 | 27.111 |
| 实际 holder 创建完成 | 20.949 | 27.298 |
| 同一 holder 实际应用到 Compose | 36.144 | 44.158 |
| 为该 holder 绑定内容 | 73.664 | 87.199 |
| 显示链开始取目标图 | 84.190 | 96.079 |
| 应用报告目标首绘 | 149.146 | 157.322 |

两次均只创建一个目标 holder，直到绘出地图并交给分页器时对象身份分别为 1446085257、875579383。创建和挂载是不同事件，第二次挂载比 43.065 ms 晚 1.093 ms。与仅预热的一次 +148.998 ms 首绘比较，提前创建本身没有观察到稳定的首绘收益。该对照的测量条件包含预热，尚无两阶段候选关闭预热的等价结果。[首次 A](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoA/first/feasibility.json) 和[首次 B](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoB/first/feasibility.json)

## 两项合用后的正常窗口首次打开

以两阶段候选的实际预览包为宿主。额外的诊断 Agent 只在装载时替换归档适配器并在该应用启动时预载通用 DLL，保留项目自己的租约和会话。每次从新进程与独立配置进入书架 → 详情 → 章行，均为首次打开阅读器，同一地图页。两份证据均有 `adapterTransformed=true`、187 页、零归档回退、零细探针丢弃。创建、绑定到首绘各只用一个实际目标 holder，A 为 1454002316，B 为 363816270。同类预热条件下，联合 A/B 启动加载 DLL 分别用 1.027、1.078 ms。此前首次加载新 DLL 的 33.036 ms 单列在上节。

| 单位 ms，明确各行起点 | 上游 Android 探针 | 联合 A | 联合 B |
|---|---:|---:|---:|
| 开始打开目标 CBZ → 完整页表就绪 | 19.620 | **1.754** | **1.695** |
| 页表就绪 → 真正开始创建目标 holder | 43.065 | **54.270** | **63.496** |
| 页表就绪 → 目标 holder 创建完成 | 43.330 | 54.434 | 63.663 |
| 页表就绪 → 同一 holder 实际应用到 Compose | 无同义记录 | 74.399 | 83.282 |
| 页表就绪 → 显示链开始取图 | 77.968 | 141.609 | 132.104 |
| 页表就绪 → 应用报告首次绘出地图 | 183.632 | 217.711 | 190.886 |
| Windows 章行点击回调 → 页表就绪 | － | 17.794 | 23.271 |
| Windows 章行点击回调 → 开始创建实际 holder | － | 72.064 | 86.767 |
| Windows 章行点击回调 → 应用报告首次绘出地图 | － | 235.505 | 214.157 |

两项合用后，开档至页表都比上游样本快。页表至 holder 已到约五六十毫秒，距严格的 43.065 ms 分别还有 11.205 和 20.431 ms。保留两个样本，不主张稳定上界。Android 栏使用隔离的上游同版本探针 APK 既有数据，本轮未动平板；窗口首绘是应用内指标，本轮没有同步采集物理屏幕或近黑画面。

原后端两次从章行点击计约 79–89 ms 才有页表，holder 在点击后 106.478–109.755 ms 开始创建。快速后端先把页表提前到点击后 17.794–23.271 ms，holder 也实际提前到点击后 72.064–86.767 ms。因此，从更早的页表往后量，数字反而变大。从合用方案的单次细记录，还能看出下一处值得查的具体等待。视口调度在 +72.218 / +80.958 ms 已经结束，目标归档复制在 +95.599 / +104.949 ms 才开始，调度结束后空出 23.381 / 23.992 ms，复制本身仅 3.024 / 2.134 ms。两次模型已接收 Ready 到实际绑定内容另有 27.898 / 12.988 ms。下一步应沿这两处测调度唤醒与 UI 绑定，并以同版本正反顺序的点击到屏幕正确图像作体验判断。现有两组总时长不足以归因一个稳定的首绘净收益。

两组归档起点都在 `tryNew` 内，即将载入通用库并打开目标句柄时取单调时钟，和生产 `PAGE_LIST_READY` 对齐。更宽的打开意图 → 页表也分别只有 5.131 / 5.371 ms。九项容器定向测试已通过，覆盖未绑定时取消、绑定后释放、旧有 holder 生命周期和 UI 首屏接线。两轮用完正常退出独立实例。实验分支与外置归档适配器未进入主干产品。

[联合 A 原始时序](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoJointA/first/feasibility.json) · [A 归档时钟](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoJointA/archive-proof.json) · [联合 B 原始时序](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoJointB/first/feasibility.json) · [B 归档时钟](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoJointB/archive-proof.json)。

[两阶段容器预览程序](<D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/app-desktop/artifacts/preview/windows/20261107T071339Z-bd5a3b78/Mihon-Desktop-0.11.24.6.3b20401-unpacked/Mihon Desktop.exe>) 和[归档实验源码、诊断组件及组件测量数据](D:/Codex/worktrees/mihon-main-reader-delivery-20261106/app-desktop/artifacts/diagnostics/reader-cbz-slot-20261107/windows-reader-cbz-prototype.zip)，后者 SHA-256 为 `716d54e3515b33a1dd8afe06252879556acb8837204b215365a688c9757f6733`。单独运行预览程序只包含容器改造。联合实验使用[本机驱动](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/joint-run.py)及预热清单。
