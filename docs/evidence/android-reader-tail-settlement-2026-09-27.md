# Android 双页阅读末页结算诊断

## 范围

用户已在调试 APK 复现《後日之舞》第四卷快速翻到下一卷后，212 页仍记录为第 210 页。以下先保留诊断阶段的原始证据；修复实施和正式候选的后续结果见文末。原实机阅读数据始终由用户自行操作。

此前实机数据库证据：第四卷最后事件为 `page_index=209`，下一卷随后已有进度事件；第四卷没有末页 `page_index=211` 或 `finished` 事件。实机未采集到逐次渲染/结算回调，因此不能从该记录直接判定缺失序号对应哪一页。

## 新增测试及实际链路

文件：`app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageProgressProductionWiringTest.kt`。

沿用现有 Robolectric fixture，执行挂载的 `DualPageR2LPagerViewer`、`ReaderActivity` 回调、真实 `ReaderViewModel`、进度策略、`RecordReadingProgress` 和 SQLDelight 内存数据库。源/章节加载依赖为 fixture；图片解码完成使用现有测试的外部回调模拟，不代表真实设备解码性能。

使用五页章节：封面页索引 0，中间双页 1/2，末尾双页 3/4。它覆盖末尾一整组双页丢失的机制，不等同于对 212 页文件执行了实机自动化。

只在测试 repository 包装器增加 `CompletableDeferred` 等待：中间页写入 SQL 后，暂缓 repository 返回，让真实结算仲裁器保持占用。这样控制写入与切章的先后次序，不依赖随机快速点击或固定休眠。

| 场景 | 核心断言 | 结果 |
| --- | --- | --- |
| 已渲染末页排队，立即切下一章 | 切章前确认末页 `pending.settled=true`；等下一章事件真正落库后，上一章应保存索引 4 | 失败：上一章仍是索引 2 |
| 已渲染末页排队，先释放写入并等待完成，再切下一章 | 上一章索引 4、已读，事件数为 2 | 通过 |
| 末页未渲染就切下一章 | 切章前确认 `pending.settled=false`；上一章保留索引 2、未读，事件数为 1 | 通过 |

三个新增用例都走到下一章持久化后调用 `onActivityFinish()`。测试没有执行真实 Android Activity 销毁/进程终止，故不把结果描述为退出生命周期已完整验收。

## 已证实与边界

- 已证实存在可导致同类缺页的竞态：末尾双页已经向 ViewModel 提交有效的显示结算，但写入排队时选择下一章，会导致上一章末页没有保存。
- 对照用例仅改变是否等待末页完成写入，结果恢复正常；因此该失败不能归因于末页未显示、SQL 无法写入或普通页码换算错误。
- 当前 `ReaderViewModel.onPageSelected()` 每次产生新序号；`onDualViewportSettled()` 异步调用 `ReaderViewportSettlementArbiter.runIfLatest()`，拿到锁后只接受最新序号。排队的旧章末页会失去写入资格。
- `updateChapterProgress()` 另有当前章节及 activation 检查。修复不能只取消序号检查；必须明确已接受的上一章结算如何在切章后安全完成，同时保护当前章节 UI 和其他副作用。
- 未渲染末页直接跳走仍不应被自动视为读完。原有图片失败、回退拖动、相邻章节激活、布局变化保护仍需保留。
- 这证明了一条真实产品链路中的丢失路径。历史实机那一次是否恰好命中同一分支，仍缺逐次回调日志；不能把自动化构造的时序当成历史实机日志。

## 验证及复跑

定向测试：

```powershell
python scripts/gradle-coordinator.py run --key reader-tail-settlement-diagnostic -- .\gradlew.bat :app:testReleaseUnitTest --tests '*DualPageProgressProductionWiringTest' --tests '*ReaderProgressSettlementRaceTest' --tests '*ReaderViewportSettlementArbiterTest'
```

关键回归用例预期保持红色，直到产品修复；本轮不以修改断言、禁用测试或跳过用例取得全绿。Gradle task 的失败是诊断结果，不是已修复的声明。

实际结果：16 个不同用例，15 个通过、1 个失败；项目自动重试失败用例两次，JUnit 因而统计为 18 次执行、3 次失败、0 个 error。三次失败均为 `java.lang.AssertionError: expected:<4> but was:<2>`，发生在最终数据库页码断言。整次 Gradle 调用耗时 15 分 2 秒。

本轮运行日志：`.gradle-coordinator/reader-tail-settlement-diagnostic.log`。JUnit 明细：`app/build/test-results/testReleaseUnitTest/TEST-eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageProgressProductionWiringTest.xml`。这些是本机构建产物，后续测试可能覆盖。

首次格式检查的 `spotlessIdeHook` 误传相对路径，不能作为格式验证证据。随后以绝对路径补跑单文件检查：`:app:spotlessKotlin` 输出 `IS CLEAN`，Gradle `BUILD SUCCESSFUL in 14s`；该 hook 模式下后续 `spotlessKotlinCheck` 显示 `SKIPPED`。日志为 `.gradle-coordinator/reader-tail-settlement-format.log`。`git diff --check` 通过。没有执行全量测试、实机复测或修复构建。

工作区另有并行的 Desktop 与共享阅读器修改。本轮只提交上述 Android 测试及本报告，不验收其他会话的改动；本次结果对应本轮 Android 编译及运行的产物，不宣称整个共享工作区已通过测试。

## 后续修复验收

1. 让本轮失败用例通过，且两个新增对照与原有阅读器保护测试继续通过。
2. 对待提交的末页结算保留合法的章节/session 上下文，避免复用当前章节状态造成错误写入或副作用。
3. 在同一实机、同一本书第四卷快速翻到下一卷后退出，核对第四卷 `last_page_read=211`、`read=1` 及完成事件；图片失败、回退和手动跳章不得误标已读。

## 修复实施与自动化结果（2026-09-28）

修复提交 `62510abce3` 将已经受理的末页结算放入应用级同书 FIFO，保存受理时的章节、事件和 session；正常关闭排空已受理事务，事务成功后才执行完成副作用。前一章失败时，后一章完成不会误删前一章下载。进度存储失败向仍活跃的阅读器发出一次提示。证据定位修复提交 `c762a68d58` 更新能力清单中移动的源码和测试引用，没有修改产品逻辑。

- 原快速跨章挂载回归在产品修复前稳定失败；修复后相关组合测试 79 个不同用例通过、无重试，包括关闭排空、旧页面回收、数据库重开、存储失败和下载保护。
- Android release JVM 672 次执行，失败 0、跳过 7、重试 0；domain JVM 568 次执行，失败 0；data JVM 752 次执行，失败 0、跳过 1、重试 0。联合命令 `:app:testReleaseUnitTest :domain:jvmTest :data:jvmTest` 退出码 0，日志 `.gradle-coordinator/rp02-android-full.log`。
- 第一次完整 Desktop JVM（含 integration tag）3212 次执行，只有能力清单第 47 项的旧源码行号断言失败；它不代表阅读器行为用例失败。修正清单及其旧测试引用后，3 类、63 次定向执行通过、失败 0、跳过 0，日志 `.gradle-coordinator/rp01-parity-evidence-green-retry.log`。最终 Desktop 全量复跑尚待超出原计划测试次数的批准，不能记为全量通过。
- 最终改动的 `spotlessCheck` 与 `git diff --check` 通过。共享 Android/Desktop 接受与排空契约的 production adapter 均已执行。

## 正式 Android 候选与待验边界

`python scripts/build-android.py candidate` 构建、R8、外部签名和产物清单成功；`verify --artifact` 再次验签并核对二进制。候选为 `app.mihon.desktop.fork`，`0.19.4-aex.17`、versionCode 35、minSdk 26、targetSdk 36、universal ABI，沿用证书 SHA-256 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`。APK：`app/artifacts/android/0.19.4-aex.17-vc35-c762a68d58-release/Mihon-Fork-0.19.4-aex.17-vc35-release-universal.apk`；文件 SHA-256：`74ac918fa1e0619a40b245550194fbf737de382ae22e78d711482d846411cf55`。构建日志 `.gradle-coordinator/android-candidate.log`；清单与 mapping 位于同一候选目录。

此产物没有自动安装，也没有代用户操作设备。原实机 T12 仍待用户自行安装同证书升级包后验收：在《後日之舞》第四卷实际显示最后一组页面，快速进入下一章并正常退出，再重开核对第四卷已读和最后一页；数据库证据目标是 `last_page_read=211`、`read=1`，同时下一章进度仍正确。末页未渲染就手动跳章须保持未读。按 roadmap，两类快速时序各三次及负向对照未取得原机证据，不能声称原机 bug 已确认修复。Windows/macOS 正式构建、Test Mode 及最终 Desktop 全量门禁也仍待完成。
