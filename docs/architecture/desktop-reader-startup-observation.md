# Desktop 阅读器首开诊断与无进展发布器

## 默认关闭的观测

仅 Test Mode 且环境变量 `MIHON_READER_STARTUP_OBSERVATION=1` 时，`ReaderTestModeController` 安装 `ReaderStartupObservation`。其线程时钟设施在 controller 准备期初始化；普通运行不创建事件、不读观测时钟、不安装 AWT listener。事件只存内存，容量默认 1024，满时保留最早事件并累计 dropped；GET `/test/reader/state` 随现有状态导出，退出移除自有窗口的鼠标监听。没有后台文件写入、图片重放或新控制接口。

AWT DOWN/UP 是自有 ComposeWindow 的 EDT 监听执行时刻；CHAPTER_CLICK 是正常详情章行回调进入、协程启动之前。它们不是操作系统原始输入时间。IO begin/end 跟随真实内容读取；DECODER_CALL_END 是 decoder 返回，RASTER_PIXELS_END 是同步像素读取返回；旧 DECODE 事件仅为 decode 调用前标记。Compose remember/apply、holder ready、prepare 与实际 Painter draw acknowledgement 分开记录，均不能替代外部可见画面或显示器扫描输出。

线程 CPU 累计值仅适用于同线程区间，Windows 量化可使短区间的 CPU 差大于墙钟差；不得将跨线程或嵌套区间相加为端到端耗时。字段缺席可能是调用分支未覆盖；dropped 非零时也不能声称全程完整。正式 C/D 性能对照关闭细观测，避免把诊断负担混入收益。

## Desktop 发布器的无进展终止

真实 187 页 CBZ 在固定位置、持续正常 Compose 帧且没有新 I/O、fact 或 sample revision 时，可反复启动没有结果的 pairing capability。若当前轮没有发布结果、没有活动任务及待消费完成项，完成本身不能成为下一轮的输入，否则持续争抢 ReaderScreenModel 生命周期锁。

此出口等待已有生产流的真实变化，不引入定时器、延迟或共享协议修改。模型在 publisher collect 的锁内增加输入 revision；规划时捕获 revision，完成时若已有后续输入到达，保留一次重臂，避免晚到 fact、重试、模式/内容或 viewport 需求变化被活动任务吞掉。既有 busy 状态实际退出的重臂、预算释放、取消清理和有界失败恢复保持原链路。该规则不把尚在运行或尚未消费的 capability 视为无进展，不改变共享配对判定、图像质量或资源预算。

定向验证包含真实 CBZ 的静止生产回归、短章节负控、当前真实双页 viewport 输入在旧任务终止窗口已被 collector 消费后的重臂竞态，以及缓存复用、邻页 header、重试/配置变化、实际资源退出与内容替换接线。收益由正常 UI 的首漫画外部采集裁决；内部不再空转不自动等于用户首开达到目标。当前原生验证限 Windows，其他平台未据此宣称性能结论。
