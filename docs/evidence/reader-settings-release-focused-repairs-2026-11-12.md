# 阅读设置发布前定向修复（2026-11-12）

## 范围与状态

用户已验收双端阅读设置，功能已进入 origin/main 的 `51778f207077a552c233b62b5f6343847262930c`。本轮以该提交为基线。随后用户选择“先完成定向修复，暂缓发布”，因此不再运行完整 Desktop 测试、不分配正式 Windows 版本、不创建 GitHub Release，也不更改更新源。

首次完整测试 `.gradle-coordinator/reader-release-full-desktop.log` 因 ReaderKeepScreenOn 的无限周期任务占用虚拟调度时钟而取消；该次未执行范围没有通过证据。不是完整测试通过。

## 已关闭项

- 保活协程移动到 IO 实时时钟，保留原组合取消与 macOS 子进程释放。真实 `ReaderIoProductionWiringTest` 由 advanceUntilIdle 卡住变为通过；最终独立补验也通过。
- 默认阅读设置已进入摘要行及弹窗，资源身份测试改为真实点击、修改、关闭及重新打开；中英文、保存及选中状态断言通过。
- 配对持久化测试改用真实“单独设置→横向阅读→单/双页”控件。对话框自己的滚动节点和动画完成后再进行指针点击，保留原保存、恢复、锚点和像素断言。两项迁移用例最终均通过。
- parity manifest 的 18 处源码证据行号及稳定 pager key 标记与现有实现同步。对应契约 34 项及真实单页组合身份 3 项通过，没有改动配对算法或放宽原身份断言。

## 定向证据

全部 Gradle 命令均经 scripts/gradle-coordinator.py 串行执行，任务为 `:app-desktop:jvmTest --tests …`，未再次执行完整模块测试。

1. `reader-release-focused-repairs`：7 个指定类/用例范围，67 项执行，62 通过、5 失败；日志保留在 `.gradle-coordinator/reader-release-focused-repairs.log`。其中资源身份 15、产品契约 34、组合身份 3、IO wiring 1 均通过。
2. `reader-release-ui-repair`：`*DesktopChapterPairingComposePersistenceTest.singleRecordRecovery*` 及 `*DesktopChapterPairingComposePersistenceTest.real reader adjustment*`，修正弹窗滚动后 2/2 通过，34 秒。
3. `reader-release-remaining-focused`：`*DesktopChapterPairingComposePersistenceTest.heldRecord*`、`*DesktopReaderRuntimeFactoryTest.cold tail*`、`*HistoryReaderCatalogCompletionIntegrationTest.detail downloaded only*`、`*ReaderIoProductionWiringTest`，3/4 通过，24 秒。cold-tail 仍失败。
4. `git diff --check` 通过。Desktop 模块没有应用 mihon.code.lint/Spotless 插件，本轮不运行无关模块的格式任务。

## 未关闭风险

- cold-tail：最后一次失败于 `canonical tail right slot`，此前一次失败于 `canonical spread boundary requested`。历史 `.gradle-coordinator/closeout-reader-merge.log:366` 已有同名失败，另有历史单例通过，尚未确认具体原因；不能断言只是测试过时或本轮引入。本轮未更改其测试或生产算法。
- heldRecord：组合范围中失败于恢复边界后的下一次导航，currentPage=4 但可见集合为空；独立复验通过，时序敏感原因未确定。本轮未更改该用例断言或配对实现。
- 目录补全：组合范围 downloaded-only 用例等待 chapterIds.size==3 超时；独立复验通过。首次完整运行另一条 delayed-catalog 键盘跨章用例超时，而本轮组合执行通过。未证实原因，不将复跑通过写成 bug 已修复。

这些风险与首次完整测试尚未执行的范围仍阻塞发布收口。当前证据只支持上述局部修复，不支持“最终版本完整测试通过”或“发布就绪”。Android 已验收的 aex.136/vc154 APK 保持原样，未重新构建或上传。
