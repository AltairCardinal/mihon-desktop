# Desktop 阅读进度与窗口关闭

DesktopReaderRuntimeFactory 同时作为应用级 reader service，由 DesktopAppModule 把同一个工厂实例接入 DesktopAppRuntime。工厂持有活动阅读器，以及已退出漫画但仍有已接受进度写入的阅读器；写入队列排空后移除引用。应用关闭先关闭这些 runtime，停止接收新 viewport 事件，再在 awaitStopped 中等待各 session 的独立进度 supervisor 完成，最后才允许窗口退出及数据库关闭。关闭开始后不再创建阅读器，重复关闭沿用既有 runtime 的幂等关闭行为。

进度继续由真实绘制/settled viewport → 共享策略 → ReaderProgressTracker → SQLDelight 事务提交，不从目标页码或未绘制图片补造进度，不改变隐身、同步身份或读完判定。普通退出漫画仍允许已接受写入在 ScreenModel scope 取消后排空。该保证适用于正常关闭窗口；强制结束进程、断电与底层存储失败不属于正常退出保证。runtime 清理异常沿既有关闭流程传播；不通过重建数据库或新增退出写入覆盖原队列。

回归入口：SharedDualPageRuntimeProgressProductionTest 的 window close 测试覆盖原生单页绘制、延迟真实 SQL 提交及窗口退出顺序；DesktopDiWiringTest 的 window shutdown 测试覆盖真实 DI 图的活动阅读器关闭。

共享进度策略与呈现边界见 [Reader shared core](reader-shared-core.md)。
