# 旧Desktop四测试与10/12报告维护diff

旧树HEAD `f38e0f9f9854daa8f09f6b52c22a2cb6476d45f6`，当前main `6b5831c64dd9f09a7d79c93fac9a75a548c740fa`。这四份旧dirty只改测试／helper，不含产品实现；不能把旧红测或只改期待值当作已修复产品。完整[旧dirty patch](desktop-maintenance/uncommitted.patch)及各文件base／working／当前main快照同时保留。

| 旧项 | 旧dirty意图 | 当前main核对与判定 |
|---|---|---|
| DesktopReaderSessionIntegrationTest | pair期待2/3改1/2，进度到2但未读完，下一页再到3 | **main有等价核心覆盖，旧适配diff不再直接采用**。当前方法显式关闭自动匹配、接受真实模型fact、导航至canonical页1后断言pair1/2、进度2／未读完，尾页3单独呈现再读完。目标位置及准备已重构，不能只按旧行号补丁重放 |
| AdaptiveReaderViewportTest | 宽窄布局切换期待pair1/2，伙伴实际draw但不追加原读取原因，并调整reports序列 | **未闭环的历史维护过程，保留观察点**。当前方法仍有pair2/3／旧reports期待，生产UI/事实/原生生命周期已演进。主干有实际draw/settle与readingEligible保护，但不声称此旧方法的完整期待已被等价采用；不以未运行的当前断言宣布产品缺陷 |
| DefaultReaderModeTest | 布局settle不重用已消费原因，改pair与report次数 | **未闭环的历史维护过程**。当前同位置仍有原方法名和2/3期待，无法从文本证明所有fixture前提与旧dirty相同。先记录而非放宽／覆盖当前测试；若另有真实失败，应以当前prefs、认证事实和prepare/draw/settle重建focused RED |
| SharedDualPageDesktopWiringTest helper | 将所有Ready页预先capture/accept 40×60 facts再取投影 | **旧helper适配过程不迁移**。当前helper用ReaderPagerNativePresentation.begin→prepare→drawn→idle→close，并新增真实原票据及旧内容拒绝断言；不能把“一律给Ready页补fact”的旧准备视为新生产能力或Skia原图观察 |
| 10/12报告追加11行 | 记录新聊天交接、旧6方法尚3失败、另31方法尚1失败、未提交测试维护 | **历史失败／交接过程归档**。这些日期/工作树/输入条件下的未完成项不是当前6b58失败；报告已明确没有变绿，不能据其清除当前失败或宣称当前已修复 |

逐文件对照（保留完整base、working及当前main）：

- [SessionIntegration working](desktop-maintenance/working/app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderSessionIntegrationTest.kt.txt)／[当前差异](desktop-maintenance/comparison/DesktopReaderSessionIntegrationTest.kt.diff.txt)
- [Adaptive working](desktop-maintenance/working/app-desktop/src/test/kotlin/mihon/desktop/ui/reader/AdaptiveReaderViewportTest.kt.txt)／[当前差异](desktop-maintenance/comparison/AdaptiveReaderViewportTest.kt.diff.txt)
- [Default working](desktop-maintenance/working/app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DefaultReaderModeTest.kt.txt)／[当前差异](desktop-maintenance/comparison/DefaultReaderModeTest.kt.diff.txt)
- [Wiring helper working](desktop-maintenance/working/app-desktop/src/test/kotlin/mihon/desktop/ui/reader/SharedDualPageDesktopWiringTest.kt.txt)／[当前差异](desktop-maintenance/comparison/SharedDualPageDesktopWiringTest.kt.diff.txt)
- [报告完整working](desktop-maintenance/working/docs/evidence/reader-pairing-research-2026-10-12.md.txt)／[报告base](desktop-maintenance/base/docs/evidence/reader-pairing-research-2026-10-12.md.txt)

当前进度语义定位：[ReaderDualPagePresentationOwner](current-main/ReaderDualPagePresentationOwner.kt.txt)的readingEligible／progressPages依赖真实draw与sequence已报告集合；当前helper明确不是Skia像素观察。语义已有保护不意味着所有旧测试默认偏好相同。归档保留这两层差异，未运行新的focused或全模块测试。

## 若将来必须判定旧两项期待

最小缺口是当前Default／Adaptive指定方法在明确隔离prefs及当前原图认证前提下的真实执行，而不是新性能基准。应先固定对应方法、测试fixture与失败断言，再申请一次focused核验；本轮目标仅全历史研究收口，故没有运行，也没有把它们强行归成当前产品bug。旧C1门禁日志／报告只能作为历史来源。
