# Android 阅读器稀疏打开探针

用于已下载章节的定向诊断，不是正式性能门禁，也不改变阅读器调度。默认关闭。实现位于 app 的 ReaderOpenLatencyProbe，单页 PagerPageHolder 与共享双页 DualPagerPageHolder 共用实际图像绘制回调。

## 配置与使用

1. 使用现有 benchmark 变体构建；它继承 release 的 R8、资源压缩和非调试运行时，使用独立的 app.mihon.desktop.fork.benchmark 身份与诊断签名。它不是正式候选，不递增正式版本，也不能覆盖官方、fork 正式或上游 benchmark。
2. 只在已授权的隔离实例准备虚构条目和相同 CBZ。benchmark sourceSet 的 ReaderLatencyPreparationActivity 仅负责准备，不打开阅读器；它校验固定的原始 CBZ SHA-256、187 张图片及复制后字节。该 Activity 不进入正式 release。
3. 若对照样本使用 SAF，须从正常存储位置设置进入系统目录选择器，为隔离应用授予专用目录，将相同 CBZ 放到 DownloadProvider 的规范路径。准备 Activity 的私有 file 路径不能直接冒充 SAF 对照。
4. 在应用外部 files/reader-open-real/config.json 写入以下字段，然后重新启动隔离应用的 MainActivity。配置只在主界面初始化时读取；不能在阅读器热路径上读配置。

~~~json
{"enabled":true,"mangaId":1,"chapterId":1,"label":"unique-run-label"}
~~~

正常从书架进入作品详情，再点击目标章节。离开阅读器后，异步导出 files/reader-open-real/sessions/<label>-<request>.json。每个进程使用新 label，采集入口先确认目标文件不存在；request 在同一进程跨重配置保持唯一，在新进程从头分配。读取导出时须等待完整有效 JSON。关闭时设 enabled=false 或移除配置后重新启动；配置缺失或解析失败会禁用探针。

## 时间点及真实范围

| 时间点 | 生产链路范围 |
| --- | --- |
| MAIN_TOUCH_DOWN / UP | MainActivity 实际输入分发，记录收到触摸的 System.nanoTime |
| ACTIVITY_CREATE / OPEN_READER_INTENT / INIT_IO_ENTER | 目标 ReaderActivity 创建、ViewModel 初始化入口及 IO 上下文 |
| MANGA_READY / SOURCE_READY / CHAPTER_LIST_BEGIN / READY | 真实仓库、阅读进度打开和章节列表初始化；边界间可能包含其他原有工作 |
| CHAPTER_LOAD_BEGIN / CHAPTER_IO_ENTER | 实际 ChapterLoader 进入与 IO 执行 |
| DOWNLOAD_CHECK_BEGIN / END | DownloadManager 的真实已下载判定 |
| GET_PAGES_BEGIN / END | 真实 PageLoader.getPages，包含规范路径与归档打开/列表加载 |
| CHAPTER_PATH_BEGIN / END | DownloadProvider 查找目标目录或 CBZ |
| ARCHIVE_OPEN_BEGIN / END、ARCHIVE_LIST_BEGIN / END | DownloadPageLoader 构造真实归档 reader，随后生成页列表 |
| HEADER_FACTS_BEGIN / END | 历史章级 Ready 页 header 遍历端点；下载首开 P0 已移除新加载和 Loaded 复用的遍历，当前样本应不出现，报告为不适用，不补造零耗时事件 |
| TARGET_HEADER_BEGIN / END | 单页 PagerPageHolder 原有 IO body 内，紧贴本页 `publishHeaderFact()` 调用前后；测实际调用 wall time，不含进入 IO 前排队。BEGIN 首次认领成功的同一次调用正常返回才写 END；取消／异常不补 END，后续调用不得补写 |
| PAGE_LIST_READY | ChapterLoader 将 materialize 页表结果接受并发布完成；已绑定章节与编码引用，不以前置全章 header 齐备为条件 |
| OPEN_PAGE / ENCODED_READ_COMPLETE | 首页 holder 真实读取编码内容；本页 header 仍由既有按需链生产，可能位于 PAGE_LIST_READY→OPEN_PAGE 段，不能只看第一段缩短 |
| DECODE_SUBMIT | ReaderPageImageView 提交当前首页图像到原有解码链路 |
| FIRST_DRAW | 原有 nativeImageDrawn 实际漫画像素回调，SSIV 需要真实 bitmap 绘制且 ready；不是窗口帧、onReady 或 onImageLoaded |
| FRAME_COMMIT | FIRST_DRAW 后该视图的提交回调，仅 API 29+ 且硬件加速时记录；不是显示面板呈现时间 |

所有时间戳只记录一次。阶段区间是观测范围，不能自动解释为单个 API 的独占 CPU 耗时或缺陷原因。TARGET_HEADER 仅在同目标请求、正常内容可见且 PAGE_LIST_READY≤BEGIN≤END≤OPEN_PAGE≤FIRST_DRAW 时用于拆分：P=BEGIN−PAGE_LIST_READY，H=END−BEGIN，Q=OPEN_PAGE−END，三者应等于页表后的原第二段。H可能包含读取、解析、锁或缓存命中，不等于独占CPU；重复holder／换内容／取消存在归属歧义时不使用这对事件归因。此细分未接入双页或条漫。

## 开销、隔离与失败边界

- 启用时在主线程预分配 32 个 session，每个 session 有固定 31 个 AtomicLong 时间槽（保留历史槽位，两个 TARGET_HEADER 事件追加于末尾）；超出预算拒绝继续采样，不增长集合。
- 热路径只校验目标 chapter/page/request 并对固定槽执行首次写入 CAS；不加旧版持锁日志、不做快照、文件写入或后台导出调度。capturedEvents/contains 只供测试和导出使用。
- page 固定为 0。单页与双页 holder 在绑定时捕获 chapter/page/request，迟到的旧请求与其他章节/页不能写入当前 session。异步提交回调捕获同一身份。
- ReaderActivity.onStop 用该 Activity 的 request 导出；快照、label 与目录在调度前绑定，后续活跃请求不能改写其归属。强停前未离开阅读器会丢失该样本；必要端点缺失或不完整导出必须判无效，不能补造时间点；HEADER_FACTS 等可选事件缺席不使样本无效，也不证明真机图像读取字节为零。
- 默认关闭的探针仍保留轻量入口判断；既有 ReaderDiagnostics 的产品开销保持原样，不能把这些开销归因于新增稀疏探针，也不能声称测量时它被禁用。
- 本探针覆盖分页单页/共享双页的首页，不宣称覆盖 Webtoon、所有页、所有存储类型、跨进程连续请求或完整发布性能矩阵。
- System.nanoTime、Perfetto trace clock、设备 uptime 和 MKV 原始相对 PTS 必须分别保存。跨时钟对齐需同次 trace clock snapshot 等证据；MKV 从零开始不能靠主机启动时间强行映射。
- 外部原表对照仍须同时录制原 MKV/Perfetto，保留原始逐帧 PTS，人工给出响应/近黑/首漫画的上下界。探针 FIRST_DRAW 不能直接替代原表视觉端点。

本轮真机仅对单页模式计时；最终对照通过正常 UI 将默认及目标漫画明确设为 RTL，准备 Activity 初始的 LTR 样本单独保留，不混入对齐表。共享双页已有探针接线，但未取得本轮真机计时样本。

## 定向验证

ReaderOpenLatencyProbeTest 覆盖默认禁用、32 session 上界、首次写入、错误页/章节、跨重配置旧请求拒绝。ReaderOpenLatencyWiringTest 执行真实 ChapterLoader/materialize：187 页新加载及新建 Loaded 页复用均不打开章级图像流；复用后仅目标页通过既有生产者取得真实方向与尺寸，编码身份和恢复页保持有效。ReaderImageOrientationWiringTest 中的 first content probe requires real bitmap draw and rejects old request 使用真实 SSIV bitmap/canvas，证明未绘制与旧请求不会产生 FIRST_DRAW，有效实际绘制才记录。

按根规则通过 Gradle 协调器运行这些 focused 测试与受影响格式检查；本文不授权完整模块或全量测试。真机验证须核实际 APK 身份、非调试标志、签名与哈希，且只操作当前已授权、确认解锁的设备及隔离包。
