# 双页阅读 CI 八项失败修复

日期：2026-10-13。范围：修复 `Build & Test` 的八项双页共享契约失败；不关闭通知、不跳过失败用例、不修改工作流。

## 基线与判断

CI 基线为 [run 38372999298](https://github.com/AltairCardinal/mihon-desktop/actions/runs/38372999298)（`5a5c54b167`，domain 675 项中 8 项失败）。本地基线 `a5057321bc`，初始仅有用户未跟踪的 `docs/human/`，未读取或修改该目录。限定两个失败类复现 77 项、8 项失败。

两个失败属于真实产品回归，均由共享 presentation owner 的已进入分组与自动预留交互导致：

- 迟到评分使未绘制伙伴转入后一组时，旧组的 membership 尚未释放，预留发布被拒绝。现在先在同一 owner 调用中移除旧归属，再发布新预留；已经准备的物理槽继续受保护。
- 导航提前建立的 Record 未继承预留标记。之后原生视口复用该 Record，迟到的更强评分可以拆开已经承诺的双页。现在导航建组和视口直接建组均继承同一预留状态。

另六项是此前行为变更后未同步的测试场景；依据现有 [稳定呈现设计](../2026-10-03-dual-page-stable-presentation-design.md) 的分组导航契约，以及 [手动配对修订](reader-manual-pairing-fix-2026-10-09.md) 的显式关系与 format 2 契约进行修正，不倒退生产行为：

| 失败场景 | 修正后的测试依据 |
|---|---|
| sample selection / late restore | 方向改变撤销旧视口；先实际重新挂载并报告绘制，再检查迟到恢复保护当前画面。手调明确断言新显式伙伴及完整回执。 |
| invalid restored record | format 2 已是有效格式；未知版本使用 `FORMAT_VERSION + 1`，继续覆盖页数不符和损坏记录。 |
| restore gates / successful save | 保存回执必须包含显式 `manualPairStarts`；恢复旧组合也持久保存该关系，不伪造空记录。失败保旧布局与导航后迟到保存的保护仍保留。 |
| saved / temporary progress | 使用完整 format 2 保存回执，继续确认布局调整本身不制造已读。 |
| save completes in single mode | 旧 format 1 边界 1 隐含后续 2/3 配对，不能用它制造 3/4 前置场景；改为明确保存 3/4，再测试保存中切单页的逻辑定位保护。 |
| explicit viewport / partial group | 下一屏越过整个已进入组，未绘制伙伴不会记已读；保留换视口前后的已绘制集合与布局原因不记阅读的断言。 |

保存校验没有放宽。新 fixture 均使用独立写出的预期记录，并对完整 `ReaderPairingAdjustment` 断言，不从请求复制期望值来规避错误。

## 验证

所有 Gradle 命令通过 `python scripts/gradle-coordinator.py run --key <key> -- gradlew.bat ...` 串行协调；日志与原始失败 XML 保存在忽略的 `.gradle-coordinator/`。以下均为 focused tests，不代表完整模块、全量测试或真实设备验收。

- `ci-reader-red`：两个原始失败类，77 项、8 项失败，保留 `ci-reader-red-session.xml` 与 `ci-reader-red-stable.xml`。
- `ci-reader-contract-red`：修正六项场景并加强预留/导航断言，production 未改，77 项、2 项失败。失败恰为上述真实预留回归；保留 `ci-reader-contract-red-stable.xml`。
- `ci-reader-green`：最小 production 修复后，同一范围 77 项通过。

- `ci-reader-integration`：Android/JVM 共享契约各 109 项、Desktop presentation wiring 6 项、Android 真实编码采样→共享会话→原生 display pages 1 项，共 225 项通过，耗时 5 分 55 秒。两个共享目标各包括 Session 31、StablePresentation 46、ManualPairingIntent 17、Rules 14、ChapterPairingRecord 1 项。
- 独立审查通过上述最小修复，并建议显式检查数据库分支确实发出 Save、临时分支不发出数据库写入。最终补入这两个分支及提交后显式伙伴断言，仅改变 Session 测试，不改变 production。
- `ci-reader-final`：上述断言加强后，两平台各重验 Session 31 项，共 62 项通过（38 秒）；其余集成结果未受影响，继续复用。组合证据覆盖 225 个执行实例，不宣称最终代码重跑完整模块或全量测试。
- `ci-reader-format`：不带 IDE hook 的 `:domain:spotlessKotlinCheck` 实际执行，通过（26 秒）。最终 `git diff --check` 通过。

失败基线及绿色回归的两个类使用以下 task 参数（前述协调器前缀不省略执行）：

```text
:domain:testReleaseUnitTest --tests mihon.domain.reader.SharedDualPageSessionContractTest --tests mihon.domain.reader.SharedDualPageStablePresentationContractTest
```

集成运行分别对 `:domain:testReleaseUnitTest`、`:domain:jvmTest` 指定以下五个完整类名；每项均独立使用 `--tests`，不是不限定范围的模块测试：

```text
mihon.domain.reader.SharedDualPageSessionContractTest
mihon.domain.reader.SharedDualPageStablePresentationContractTest
mihon.domain.reader.ReaderManualPairingIntentContractTest
mihon.domain.reader.SharedDualPageRulesContractTest
mihon.domain.reader.ChapterPairingRecordContractTest
```

平台接线参数：

```text
:app:testReleaseUnitTest --tests "*.SharedDualPageAndroidWiringTest.matching preference*"
:app-desktop:jvmTest --tests mihon.desktop.ui.reader.presentation.SharedDualPageDesktopPresentationWiringTest
```

Android 原命令前导通配符在 `forkEvery=1` 下造成逐类发现开销；最终 XML 确认只有上述 1 项执行，无跳过或失败。以后重复该用例时改用完整类名前缀 `eu.kanade.tachiyomi.ui.reader.viewer.pager.SharedDualPageAndroidWiringTest`。本轮不为改命令拼写重复已通过的接线测试。

最初 Windows `./gradlew.bat` 写法在协调器子进程中没有启动 Gradle，改用 `gradlew.bat` 后才获得真正的红测。集成命令的相对路径 `spotlessIdeHook` 导致格式任务跳过，不作为格式通过证据；绝对路径 IDE 模式虽对三个文件都输出 `IS CLEAN`，仍将 Check task 标为跳过，因此最终另用上述不带 hook 的真实 Kotlin Check 收口。

最终补验 task 参数：

```text
:domain:testReleaseUnitTest --tests mihon.domain.reader.SharedDualPageSessionContractTest :domain:jvmTest --tests mihon.domain.reader.SharedDualPageSessionContractTest
:domain:spotlessKotlinCheck
```

## 维护边界

预留、membership、物理槽锁和已读集合各有职责：分组占用不等于已绘制或已读；将未锁定伙伴移交新组时，先释放旧归属再发布预留；所有创建 Record 的入口须传递已有预留承诺。真实宽图、手动关系和显式上下文变化仍沿用现有规则，不新增算法或持久化格式。

本轮不生成 APK/桌面包，不操作实体设备，不修改公共能力声明。远端工作流是否恢复须以修复提交后的实际 GitHub Actions 结果为准。
