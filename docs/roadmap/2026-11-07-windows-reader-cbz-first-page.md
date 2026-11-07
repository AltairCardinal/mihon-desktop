# D10　Windows 下载章节首开优化设计与执行路线

供新 Sol 会话直接实施。首个未勾选任务是当前任务。研究基线 `c34fbccf92db` 含 [D9 归因](../evidence/desktop-reader-d9-first-open-causes-2026-11-07.md) 和[两项合用实测](../evidence/desktop-reader-cbz-slot-feasibility-2026-11-07.md)。交接时核对的最新产品基线为本地 main `ae5bb3bb5aca9e84e6616a1183129e6f0a25e77b`，已含新的章节入口修复。本页是[父路线图](2026-11-05-desktop-reader-first-open.md)的执行入口。以下方框均为待实施项。

## 目标、成本和采用口径

用户从书架进入详情，第一次点击已下载的《史莱姆》第 04 卷，单页 RTL，恢复第 5/187 页地图。交付是一个沿正常入口工作的 Windows 预览版、分项采用判定和一份实测报告。优先让正确漫画更早可见，同时考核两个内部目标。

| 毫秒 | 上游 Android 真机探针 | 上轮 Windows 联合实验 A / B | 本轮如何判定 |
|---|---:|---:|---|
| 开始打开 CBZ → 完整页表就绪 | 19.620 | 1.754 / 1.695 | 两个新进程样本各 ≤19.620 |
| 页表就绪 → 开始创建真正的目标页 holder | 43.065 | 54.270 / 63.496 | 同一联合候选两个新进程样本各 ≤43.065。另报实际挂载时间 |
| 页表就绪 → 显示链开始取图 | 77.968 | 141.609 / 132.104 | 查明视口调度后约 24 ms、模型 Ready 后约 13–28 ms 的去向，记录实际可回收量 |
| 页表就绪 → 应用首绘 | 183.632 | 217.711 / 190.886 | 同时记录从章行点击回调到首绘，联合实验曾为 235.505 / 214.157 |
| 画面体验 | 见[项目基准](../evidence/reader-startup-performance-baseline-2026-11-02.md) | 联合实验只采了应用内首绘 | 用正确地图首次可见 F 作为体验主指标，同时报告可辨阅读器响应 R、无内容最长及累计 K。工程目标 R≤100、K≤60 |

两项内部目标按同一次真实首开报告，分别给出“达到”“仍差多少”或“缺少可比样本”。归档单项可以凭真实工作量下降且体验没有可辨退化独立采用。容器单项只有在原指标严格追平且 F/K 无可辨退化，或产生可复现的 F/K 改善时采用。调度与绑定改动要有可复现的可见收益。同级、严格追平、屏幕上更早见图分别下结论。少量样本不推算 P95。实验中通用类预热约 189 ms，点击前早已完成；新包兑现多少必须再测。

由新会话 Sol 实施，独占一次窗口验收时段；原生文件句柄和 JNI 生命周期在下游依赖前集中做一次独立审查。已有证据直接复用。预算为一轮分任务定向红绿、一次合并验收；预计数小时，主要花在原生打包、首次 JVM 与窗口采样。每个未证实的时段最多尝试一个有因果证据的改动。后续遇到身份或画面回归先停用对应候选，其余能独立通过的部分继续。不触发全量测试、Android 构建、正式发版或推送。

## 实施顺序

### 1. 固定输入与对照

- [ ] 对齐产品基线、实验提交、真实原档、地图页与控制配置。

- 主干工作树在 `D:/Codex/worktrees/mihon-main-reader-delivery-20261106`。先检查分支、HEAD、未提交工作、两份实验提交以及它们到当前主干的差异；如主干已前进，核对涉及路径后从实际主干继续。旧项目入口 `D:/Shell/Github/mihon` 在设计时检出扩展兼容分支。控制样本和候选使用同一基线、运行时、显示器、视口大小、DPI、截图负载与设置。正常走详情章行，无在线取图或提前读目标 CBZ。
- 归档实验[源码与组件包](D:/Codex/worktrees/mihon-main-reader-delivery-20261106/app-desktop/artifacts/diagnostics/reader-cbz-slot-20261107/windows-reader-cbz-prototype.zip)的 SHA-256 为 `716d54e3515b33a1dd8afe06252879556acb8837204b215365a688c9757f6733`。容器参考依次为 `904bc8d34308459b69cdfd8dd8c7dafe893d2d32`、`3b2040111964d4cbedca029269ada10e4a09959b`。从包取源和对照数据，从 Git 取 UI 差异；将产品所需内容纳入仓库后，从干净快照可重建。普通预览须能独立运行。
- [本机原档](D:/Shell/Github/mihon/.test-tmp/slime-reader-remeasure-20261102/slime-vol04.cbz) SHA-256 为 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。目标 index 4 的原图 SHA-256 为 `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`。每个正式首次使用同一模板复制出的全新隔离配置和新 JVM，保持相同的下载、设置和缓存初态；不清系统文件缓存、不操作用户实例。缺实物时仍可用合成夹具做开发，把真实性能验收留为未测。勿将漫画加入 Git。

### 2. 将 miniz 接入实际下载归档路径

- [ ] 交付可重建的 Windows 后端，实证命中、兼容回退、页身份与租约安全。

两臂的开档起点统一放在下载 CBZ 已定位、即将选择后端的同一位置，包括点击后发生的加载检查、解析与回退；同时保留打开意图到页表作为宽口径复核。终点沿用收到完整、已接受页表时的生产事件。命中与回退分别报告，再报告默认打开路径，避免筛掉后端未就绪的第一次。

**覆盖范围。** 首期支持实验验证过的 Windows x64、本地磁盘下载 CBZ、根目录下等宽纯数字小写 `.webp` 页名、可选单个 `ComicInfo.xml`、Store 或 Deflate。按归档格式判断，不能按作品名、页数或章节 ID 分支。双页模式共享此归档入口。目录、非下载本地源、EPUB/RAR、其他平台沿用现有处理；非标准 CBZ（包括扩展名伪装、重复条目、目录、编码/额外字段会改变文件名解释等）交还现有选择链。首期不扩展支持格式，不建持久化索引。预建索引已测得每新进程还需提前载入目标章约 59–72 ms。

**接线。** 从 [DesktopReaderMaterializePorts.kt](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderMaterializePorts.kt) 的 `downloadDescriptors` 把下载 CBZ 优先选择传入[现有内容适配器](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderContentAdapter.kt)；保留既有调用者和显式 `preferNativeZip` 的原行为。新后端遵守现有 `RefCountedArchiveLease`，一次打开的文件句柄覆盖读取中央目录到目标页复制。页表算完仍由原会话登记真实 lease generation、发布 `PAGE_LIST_READY` 和接收关停。每代独立持有句柄，释放等在途操作结束；失败或取消关闭未登记句柄，清理临时页。

**身份与资源。** 归档元数据按原条目次序及现有分隔、路径、带符号 CRC 和方法名计算指纹，页列表按现有比较规则排序，保留每页完整 opaque URL 与 sourcePageIndex。原生侧批量返回有界的名称/标识或等效稳定数据；Kotlin 构造真实描述符。不要把实验所用 Kotlin 默认构造器的掩码、具体章节、裸指针所有权或诊断时间字段定为跨语言接口。解包按 index 从同一已验证句柄流式复制到现有 encoded store 分配的目标，检查范围、校验和、写入结果以及取消。原型每页先解入原生堆再复制 Java 数组，另有 64 MiB 上限；产品不应因为选择快速路径让原本可读的大图失败。若首期保留大小限制，须在发布页表前整档判定并回退；读中损坏、磁盘写失败、代次撤销沿用存储错误/取消链，不能换一个新打开的档案偷偷续读旧页标识。

**构建和加载。** 钉住实验使用的 miniz `174573d60290f447c13a2b1b3405de2b96e27d6c`，保留来源、许可证以及可重建的 x64 JNI 构建输入。复用 [Desktop 打包配置](../../app-desktop/build.gradle.kts) 的 Windows 应用资源链，核实实际预览包中 DLL 位置、ABI、依赖和源码版本。仅从包内确定位置装载。Windows 首个可交互界面出现后，后台幂等加载通用库；工作线程维护未开始、加载中、已就绪、不可用状态。首次点击遇 DLL 未就绪，直接用现有 reader 完成本次打开，后续新开章可选已就绪的库；不在 UI 线程等待，不并行解析同一个 CBZ 两遍。分别记录即时首次和自然就绪后的首次选择。上一轮点击才载入 DLL 有过 31.603 ms 超标。诊断覆盖层、735 类反射预热、全局 `-javaagent`、实验 DLL 或当前机器绝对路径均不进入默认产品运行方式。[现有启动预热](../../app-desktop/src/main/kotlin/mihon/desktop/startup/DesktopStartupClassWarmUp.kt)主要是后台类加载，不能视作已经复现了实验预热。

**定向红绿。** 扩展 [DesktopReaderContentAdapterTest](../../app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderContentAdapterTest.kt) 和现有下载会话接线。从真实下载路由选择后端，先证明合格夹具原先未命中新后端，再验证正确后端、非零代次、完整列表、目标页真实字节、原实现逐项身份相同。用少数夹具覆盖不支持格式/不可加载 DLL 正常回退，等长同 mtime 文件替换与在途关闭，旧标识拒绝。窗口前用真实 CBZ做一次 187 页清单和图像 SHA 核对，不为每次计时重复解整卷。缺 DLL 的回退测试不能冒充打包成功；实际 preview 必须在正常路由选择到 miniz。

### 3. 将已验证的两阶段容器移植到首次单页视口

- [ ] 正常首开挂载真实容器，Ready 后同对象首绘、交接、取消和翻页均通过。

移植须保留主干 `011681faac` 已区分的路径。底栏上一章/下一章按钮明确进入目标章首页，阅读流向前越界进入上一章末页，普通详情首开沿用恢复位置；异步页表到达后解析边界。轻量视口在页边界使用阅读流回调，不能把它接到底栏按钮回调。复用 [DesktopReaderChapterTransitionIntegrationTest](../../app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderChapterTransitionIntegrationTest.kt) 中已挂载工具栏和跨章边界的定向用例。旧实验的 `DesktopReaderScreen.kt` 不能覆盖当前文件。

**接线。** 参考两份实验提交，从 `DesktopReaderScreen → ReaderVisualComponents → StartupSinglePageViewport` 使用当前实际选中页。仅限本轮已下载、分页单页、未启用自动拆页的首次显示。旧路径仍负责双页匹配开/关、条漫及不符合条件的模式。不要整包挑入实验中的全模式端口修改、测试语义或反射预热。首次视口复用当前窗口几何、方向、裁边、缩放和已有的页面呈现通知。

**生命周期。** 在 Queued 时以 owner、pageId、generation、attempt、split/bounds 身份注册一个真实 `DesktopReaderPresentationImageHolder` 并挂入 Compose。等待期间保留加载反馈；没有 encodedRef 时既不开流也不创建 region。Ready 到达时，对仍属当前代次的同一对象一次性绑定完整 decodeKey 并经原 acquire 获取图像；错误仍可点重试，关闭、换章、换模式或换 attempt 使旧绑定作废。由 [ReaderPageImageContent](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderPageImageContent.kt) 在首绘后将已挂载内容移交现有分页器，退出双方不会重复 close。首绘前用户先翻页也须能转入真实分页器并尊重最新目标。保留实际绘出确认、原始图像、显示身份和阅读进度的原语义。

**定向红绿。** 在主干重放实验的 `StartupSingleViewportTest` 行为红测，收口以真实 Compose 挂载为准：Queued 时未读取、Ready 后同一对象绘出、分页器接手仍是该实例、退出前后不泄漏；模拟延迟 Ready 后早退/切页，不让旧图出现。复用 owner 的生命周期测试和既有模式接线，只有缺失的行为再新增。验证默认包无需外置 agent；若结果仍超过 43.065 ms，执行第 4 项的容器分支。

### 4. 分辨两个等待，按证据选择下一处改动

- [ ] 随实际产品样本定位容器前和取图前等待，验证有因果依据的改动或记录停手原因。

保留同源控制与候选，在[既有有界 startup 观测](../../app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderStartupObservation.kt)中按需补点，仅对当前章、代次、attempt、目标页关联同一单调时钟、线程及请求。只记录未覆盖的边界，常规运行关闭、首绘后统一导出。先复用已有[联合 A](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoJointA/first/state.json)及[联合 B](D:/Codex/worktrees/mihon-reader-single-first-slot-20261107/.test-tmp/first-slot-20261107/actual/twoJointB/first/state.json)；新产品候选先取两个独立首次，用同一份采样同时核对两条路径。下列数值均为可调查窗口，不能相加作为预计首绘收益。

| 要解决的问题 | 哪些数据能决定改哪里 | 有相应证据时执行的最小改动 |
|---|---|---|
| **严格追平容器创建**。A/B 的页表发布约 +0 ms，阅读器根 `SideEffect` 的 `COMPOSE_READER_APPLY` 在 +26.6/+27.8，首次 `acceptSessionState` 在 +28.8/+30.1 开始，+38.1/+49.4 返回，实际容器至 +54.3/+63.5 才开始。 | 分开标出根组合进入/提交、真实 Loaded 被消费、所选页可用、第一幅视口组合到 `createHolder`。观察事件所属线程；与后台配对投影重叠的片段不算成 UI 阻塞。严格预算还差 11.2/20.4 ms，归档复制发生在此之后。 | 若根提交前耗时最多，将首屏必需的现有视口/状态消费放在正常阅读器入口首轮组合，延后有实测成本且与当前图无关的附属 UI，自身必要的焦点、退出和加载反馈保留。若首轮摄入才占主导，从同一已接受快照先得出当前实际页和代次，仅延后证实与符合条件的单页无关的派生工作；模式切换仍能重建完整状态。已有 D8 复用保留，不再直接套 D7 的全局提前订阅。测试需锁定原来那项工作确实挡住真 holder，修复后正确页能先进入视口。 |
| **真实漫画更早取图**。从 `VIEWPORT_SCHEDULE_END` 到目标 `ARCHIVE_COPY_BEGIN` 为 23.381/23.992 ms。复制本身为 3.024/2.134 ms。 | 在 [DesktopReaderSession](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderSession.kt) 和[现有 runner](../../domain/src/commonMain/kotlin/mihon/domain/reader/scheduler/ReaderPageMaterializeRunner.kt) 对齐目标请求的 poll/登记、job 实际开始、物理 permit 获得、prepare 中 `ensureStoreWritable` 返回，再到缓存查询、[encoded store](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderEncodedPageStore.kt) 的写入门闩/临时文件、真实 copy。pump 可在结束标记前已被触发；按同一请求的时间线和互斥区间分账。 | 若相邻页抢先占 permit，则在既有优先级/取消预算内修正首次可见目标的顺序；若是同一 store 初始化或同一写入被重复等待，则在现有会话/引用生命周期复用，保留原子落盘；若时间耗在单次线程派发，只消除已证实多余的一次交接。都不能在 UI 或持锁区做 I/O。用可控阻塞的真实 runner/store 回归确认目标优先、取消和过期请求不污染新页。若日志未能区分上述原因，保留诊断，暂缓这一改动。 |
| **Ready 到实际绑定**。模型已接受目标 Ready 到 `HOLDER_CONTENT_BOUND` 为 27.898/12.988 ms。 | 区分目标状态发布、已挂载 slot 收到、内容键绑定、effect 提交/acquire 与实际 OPEN；记录同一对象及实际呈现。只有挂载完成的当前 visible holder 可消费。 | 若已有匹配 holder 仍在等下一次整页组合，令已挂载 slot 消费同一权威状态中该页的就绪更新，幂等绑定实际键并沿原链 acquire；关闭/换页同时撤销。若绑定后开流才慢，则只处理实测命中的后续交接。验证旧 Ready 晚到、重试、多次相同更新不会触发第二次开流/解码；保留绘出后记进度。 |

优先完成能缩短 F/K 的有证据分支；创建容器的严格目标单独裁决。某一段已经达标或不能归因即跳过其对应改动。若涉及共享 runner，先判断能否在 Desktop port 修正；确需改共享模块时才运行该 runner 已有共同契约定向测试。D5 已有反向和双页退化反例，本轮不恢复“在所有模式一律提前准备图片”方案。等待落在单个不可压缩的系统/帧区间时，记录上下界结束本轮。

### 5. 在产品窗口作采用裁决并交付

- [ ] 交付冻结候选、两项严格目标、外部 F/R/K 和保留或撤回的依据。

正式采样前给包、配置、代码版本、探针负担、打开顺序留档。区间跨过预算边界时写“分辨率不足”；不要拿区间中点宣称达标。如果只保留 B，在默认 B 与 A 的外部对照完成前，仅给归档组件结论。更多 ZIP 布局与页名可作为独立后续工作，按实际未命中类型与兼容证据扩展，本轮不将已测速度外推到回退的漫画。

同一地图的官方安装版单页首次外部可见区间中位数是 **272.9–289.6 ms**。它可作为体验参照；不同设备的原生输入和 Windows 章行回调需各自保留，不把内部首绘与该视频端点直接相减。[同页官方真机对照](D:/Codex/worktrees/mihon-reader-o-series-20261104/docs/evidence/reader-open-o-series-2026-11-04.md)

1. **控制和样本。** A 为同基线默认主干，B 为仅第 2 项，C 为最终联合候选。优先复用能核对源码、探针和运行条件的控制预览。B 在开发中保留独立提交/后端选择与组件数据。正式可见对照主做 A/C、C/A，各臂各两个独立新进程，共四次。若联合候选退化或收益归属决定能否保留容器，再作一次 B/C、C/B 定向对照；不为每个微改动建立完整矩阵。若仅剩归档项，则 A/B、B/A 即最终对照。共享归档影响双页，候选对匹配开、关各做一次真实恢复页、完整页对、翻页往返检查；发现选页或首图退化迹象时才补相应模式的一对控制。即时点击不等待人为预热；复用首次测试或另做一次快速打开，明确后端选取和结果，不能删去回退的慢样本。
2. **口径与采集。** 顺序、缓存初态、从详情可点击到实际点击的间隔和有效后端事前记录，快慢样本完整保留。按 [Windows 已验证流程](../automation/DESKTOP_READER_LATENCY.md)用真实章行输入、WGC 原帧、QPC 校准，比较回调→正确地图 F；原生输入包络另报。R 以真实阅读器反馈为准；K 提供累计与最长两组区间；双页记录第一张及两侧齐全。各臂保持相同细探针负担，正式 WGC 时关细探针，不在首绘前轮询状态。内部阈值用同一最终源码、正常预览、两个带同配置轻量观测的新进程判定；若第 4 项之后生产代码未动，复用那两份。没有外置类预热、字节码替换或强行延后页表发布。复核图片内容、实际 mounted 身份和退出保存；不跨进程时钟直接相减。
3. **判定。** 两对中的可见收益区间均支持同方向改进才报告此次体验收益，例如 F 的控制下界减候选上界均大于零。小于录制区分能力、反向不重现、负载异常影响结论时保留原件并注明未证实。若仅 K 明确改善，单报 K，F/R 不得有可辨退化。两项内部严格阈值有一份超标就列出超出量；样本达标不承诺稳定上界。出现错图、双页退化、启动等待、生命周期破坏则停用有问题的改动，选择已验证的较小组合。原生环境不可用时可交付已通过的组件证据，保留窗口验收缺口。
4. **交付。** 项目协调器串行跑相关 Desktop focused 测试和 `scripts/build-desktop.sh preview`，记录实际可执行文件、包内 DLL 来源及 hash。按步骤提交可独立审查的改动，将采用/撤回、真实样本、探针状态、当次 F/K 与剩余差距写入一份 evidence；同步本页复选框和父路线图。用户未验收前保持公共功能说明原样。正常退出测试阅读器，关停自己建立的实例和采集器。

新会话可直接从第 1 项开始。既有[两端差值归因](../evidence/desktop-reader-d9-first-open-causes-2026-11-07.md)、[D5 反例](../evidence/desktop-reader-d5-2026-11-06.md)、[D7 反例](../evidence/desktop-reader-d7-2026-11-07.md)和[D8 已采用的局部优化](../evidence/desktop-reader-d8-2026-11-07.md)作为已完成研究复用。
