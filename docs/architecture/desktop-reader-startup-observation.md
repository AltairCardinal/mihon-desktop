# Desktop 阅读器首开诊断与无进展发布器

## 默认关闭的观测

仅 Test Mode 且环境变量 `MIHON_READER_STARTUP_OBSERVATION=1` 时，`ReaderTestModeController` 安装 `ReaderStartupObservation`。其线程时钟设施在 controller 准备期初始化；普通运行不创建事件、不读观测时钟、不安装 AWT listener。事件只存内存，容量默认 1024，满时保留最早事件并累计 dropped；GET `/test/reader/state` 随现有状态导出，退出移除自有窗口的鼠标监听。没有后台文件写入、图片重放或新控制接口。

AWT DOWN/UP 是自有 ComposeWindow 的 EDT 监听执行时刻；CHAPTER_CLICK 是正常详情章行回调进入、协程启动之前。它们不是操作系统原始输入时间。IO begin/end 跟随真实内容读取；DECODER_CALL_END 是 decoder 返回，RASTER_PIXELS_END 是同步像素读取返回；旧 DECODE 事件仅为 decode 调用前标记。Compose remember/apply、holder ready、prepare 与实际 Painter draw acknowledgement 分开记录，均不能替代外部可见画面或显示器扫描输出。

线程 CPU 累计值仅适用于同线程区间，Windows 量化可使短区间的 CPU 差大于墙钟差；不得将跨线程或嵌套区间相加为端到端耗时。字段缺席可能是调用分支未覆盖；dropped 非零时也不能声称全程完整。正式 C/D 性能对照关闭细观测，避免把诊断负担混入收益。

## Desktop 发布器的无进展终止

真实 187 页 CBZ 在固定位置、持续正常 Compose 帧且没有新 I/O、fact 或 sample revision 时，可反复启动没有结果的 pairing capability。若当前轮没有发布结果、没有活动任务及待消费完成项，完成本身不能成为下一轮的输入，否则持续争抢 ReaderScreenModel 生命周期锁。

此出口等待已有生产流的真实变化，不引入定时器、延迟或共享协议修改。模型在 publisher collect 的锁内增加输入 revision；规划时捕获 revision，完成时若已有后续输入到达，保留一次重臂，避免晚到 fact、重试、模式/内容或 viewport 需求变化被活动任务吞掉。既有 busy 状态实际退出的重臂、预算释放、取消清理和有界失败恢复保持原链路。该规则不把尚在运行或尚未消费的 capability 视为无进展，不改变共享配对判定、图像质量或资源预算。

定向验证包含真实 CBZ 的静止生产回归、短章节负控、当前真实双页 viewport 输入在旧任务终止窗口已被 collector 消费后的重臂竞态，以及缓存复用、邻页 header、重试/配置变化、实际资源退出与内容替换接线。收益由正常 UI 的首漫画外部采集裁决；内部不再空转不自动等于用户首开达到目标。当前原生验证限 Windows，其他平台未据此宣称性能结论。

## D5：独立于细观测的正常点击锚点

Test Mode 的正常详情章行回调在启动协程前记录 `detailChapterClicks`，最多保留最近64条。记录包括递增 sequence、chapterId、单调时钟和可空的 openIntentNanos；沿现有 `/test/reader/state` 导出，不同步落盘。普通运行不记录，也不额外读取此时钟。它与默认关闭的 CHAPTER_CLICK 细事件是两个不同用途的观测：正式对照两臂都有前者，关闭后者。

导航对象上的临时 sequence 不进入 equality、导航 key 或持久化。runtime 构建期间通过既有 probe.bind 关联同章的第一个 OPEN；取消而没有构建时保持未关联，测试 API 或其他入口不会借用上一次点击。分析必须按 chapterId 与 openIntentNanos 精确关联最新 OPEN，不能取同进程第一条点击。该起点仍是应用回调，排除了此前的系统输入处理；不得称原始输入送达或据此判断完整响应预算。

## D5：已撤回的初始内容准备候选

当前 production 仍在接受页表后等待 Compose 实际 viewport 提交，再通过普通 scheduler 准备当前页 encoded 内容。D5曾尝试在下载路由已确定时由初始物理页提前提供无进度调度需求；虽然真实行为RED/GREEN证明依赖解除，但单页反向顺序未重现收益，关闭匹配双页首次有可辨退化，候选及共享resolver已精确撤回。不能将实验设计当作现行架构，也不将此结果推定为所有提前准备方案无效。

再次研究前先读 [D5证据](../evidence/desktop-reader-d5-2026-11-06.md)，提供新的触发条件或反例。沿用资源预算、代次、真实viewport、图像质量和进度保护；注意既有 applySchedulePlanLocked 内的 runner.cancel 即使空集合也会 pump，不能假定调度都在session锁外。保留的新长期设施只有上节Test Mode点击锚点。

## D6：同序身份读取与本次下载判断

Desktop `ReaderScreenModel.ensureDualSession` 在同作品、同generation、同有序pageId列表时，按ordinal读取上一身份的content，并继续核对pageId/contentVersion；校验不符仍使用原NORMAL默认方向。其他页序沿用原查找fallback，内容替换、stamps、版本推进、方向事实、owner退役及进度协议不变。目的仅为减少稳定同序下的重复线性查询，不建立共享/跨代缓存，不改配对认证算法，也不因同序而跳过合法状态更新。

`toReaderChapterRefs` 在一次章节投影内每章只求一次downloaded值，供isDownloaded和过滤共用；source0仍短路为已下载且不调用远端判断。下次打开重新判断，不将Boolean当成已验证内容位置，不跨打开缓存位置或失败；异常继续由既有打开链处理。该局部复用通过真实detail readerRequest与ReaderNavigator过滤/下一章接线验证。

真实模型计数夹具中8页读取44→16、187页17,765→374；远端每章下载回调2→1，source0为0。采用限局部工作量减少，未证实稳定首图毫秒收益；8份Windows外部样本、三模式画面/往返、条件项不实施及限制见[D6结果](../evidence/desktop-reader-d6-2026-11-06.md)。无新增探针或等待协议，不以本批代替完整模式/平台验收。
