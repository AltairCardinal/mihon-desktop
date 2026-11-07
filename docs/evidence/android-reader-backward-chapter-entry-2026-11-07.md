# Android fork：反向跨章入口被重置为首页

日期：2026-11-07。调查基线：`4bdca36f34`。用户确认受影响的是平板上的 Android fork，不是 Windows 程序。

## 结论与复现

已证实 Android `ReaderViewModel` 的章节变化监听重复解释入口位置：viewer 已经选中上一章末页后，监听再次调用“打开已读章节从首页开始”的策略，并用 `SharedDualPageSession.goToPage()` 将共享双页会话移动到首页。

回归测试执行真实 `ReaderViewModel`、`ChapterLoader`、章节窗口及 `DualPagePairingStore`/共享双页 owner：预加载 11 页的已读前章，选中其末页，再通过双页 viewer 使用的 `onPageSelected(..., recordProgress = false)` 激活它。修复前，owner 的源页下标从预期 `10` 变为 `0`；修复后保持 `10`，`requestedPage` 和 SavedState 的章节/页码也与该末页一致。未呈现、未结算的页面不写阅读进度。

本次自动化覆盖了生产状态与 viewer 接线，尚未生成新 APK 或完成本轮平板手势人工验收。不能将代码通过称为用户当前安装包已经更新。

## 引入时机

以下为 Git 源码追踪与当前行为回归，不是逐历史 APK 的运行二分；未核对用户安装包的源码清单，不能据此指定首个受影响的发行版本。

| 提交与本地时间 | 变化及作用 |
| --- | --- |
| `2fcbc9cb03`，2026-09-16 20:41 +08:00 | 新增共享 `resolveReaderChapterEntryPage()`，把它放入 Android 的 `currChapter` 变化监听；该函数对无显式恢复位置的已读章节返回首页。原本适合显式打开的策略因此也作用于阅读中跨章。 |
| `2a0e7570b7`，2026-10-02 05:44 +08:00 | 在同一监听加入双页 owner 的 `goToPage(requestedPage)`。从此，监听得出的错误入口直接改动共享双页的实际定位；这是本次已证实可见重置链路的关键引入点。 |
| `4bdca36f34`，2026-11-07 02:28 +08:00 | 上一轮修复给恢复页码加上章节身份条件，阻止上一章页码串入下一章；但保留了每次激活都重算入口并移动 owner 的链路。反向进入已读章节仍会得到首页。这一修复不完整，没有区分初次打开与阅读中切章。 |

对照本地上游引用 `upstream/main`（`446f6df82c`）：原版单页 pager 在更换章节窗口时通过 `getItemPosition()` 保留原有 `ReaderPage`，只在 pager 首次显示时执行初始定位，没有这里新增的共享双页 owner 重定位。前章页面在阅读流中位于当前章之前，反向越界自然进入其末页。

## 跨平台共享情况

| 层次 | 当前情况 | 本次处理 |
| --- | --- | --- |
| 共享原子打开 | 双端 production 打开链路通过 `RecordReadingProgress.openChapter()` 消费同一 `SqlDelightReadingProgressRepository` 的 `ReaderOpenContext`，一起决定页码与同步快照。 | 继续消费该结果，不在章节激活监听里第二次解释。 |
| 共享打开辅助策略 | `domain/.../progress/ReaderEntryResolver.kt` 的 `resolveReaderChapterEntryPage()` 仍用于 Desktop 的后备打开路径；此前 Android 监听也调用它。已读章节重新打开从首页开始本身正确。 | 移除 Android 的误用，不修改辅助规则，也不把“已读章节一律末页”加入共享层。 |
| 共享双页定位 | `SharedDualPageSession` 维护源页、配对和定位；平台传入 `goToPage(0)` 时会按要求移动。 | 保留共享核心，修正 Android 调用时机与输入。 |
| Android adapter | 章节激活监听既保存状态又改动 owner，覆盖 viewer 的跨章选择。单页/双页/条漫共用 `ReaderViewModel` 激活链路，其中双页 owner 的可见覆盖已由本次测试复现。 | 移除监听中的入口重算和导航副作用，传递真实选中的 `ReaderPage`。 |
| Windows adapter | `DesktopReaderScreen` 对 Previous 传 `ReaderInitialPage.LAST`，`ReaderScreenModel` 在页列表到达后解析末页；已有独立的 `ReaderChapterNavigationInitialPageTest` 与 `DesktopReaderChapterTransitionIntegrationTest`。 | 只读核对，没有修改或运行 Windows 测试，不声称 Windows 本轮原生验收通过。 |

## 修复边界与维护规则

- 初次打开、同步续读、进程恢复：沿用原有原子打开结果与 `pendingResumePosition`，在发布章节前把显式位置交给 owner；保留无效恢复位置的反馈与确认流程。
- 阅读中跨章：`loadNewChapter()` 携带 viewer 实际选中的 `ReaderPage`，在激活身份与时序仍有效时写入该章 `requestedPage`；不重算首页/末页，也不把原生页组下标当作源页码。
- 章节变化监听只保存已经接受的章节/位置，不发出新的导航。未结算的双页继续等待真实呈现后再记录进度。
- 选中页必须仍是当前已加载列表对应位置的同一个对象；旧列表、旧激活和较晚的当前章选择仍受现有 identity/settlement 检查保护。失效输入不得替换当前激活。
- 工具栏上一章/下一章按钮继续由 `ReaderActivity` 明确移动到下标 `0`；本次修复的是阅读流的反向跨章，不把所有“上一章”动作强制改成末页。

## 验证与过程中的失败

1. 红测：`backward-chapter-red`，新回归证实 `expected 10 / actual 0`。首次红测的失败收尾还暴露测试在后台激活结束前重置 Main dispatcher；新测试现会取消并等待其 ViewModel 根 job，绿色运行中不再出现该异常。
2. 首次绿色补验：状态/进度 18 项通过；原生 viewer 的 3 项用例因 relaxed mock 返回 `Object` 而不是 `ReaderViewModel.State`，在进入目标激活逻辑前失败。仅为该夹具补上类型正确的状态流，没有修改 viewer 产品逻辑。
3. 最终范围：`backward-chapter-final`，24 项全部通过、零跳过：
   - `ReaderSyncResumeWiringTest`：16 项；包含本次末页保持、上一轮向下一章串页、SavedState、同步恢复和无效位置确认。
   - `ReaderChapterWindowProductionWiringTest`：1 项。
   - `ReaderProgressSettlementRaceTest`：1 项。
   - `ReaderAdjacentChapterViewerProductionWiringTest`：6 项；覆盖单页、双页与条漫原生接线。
4. 三个修改 Kotlin 文件通过 Spotless IDE hook 检查（均为 `IS CLEAN`）；`git diff --check` 通过。
5. `npm --prefix website run docs:build` 通过，保留 VitePress 内部断链检查。双语公开说明仅登记用户已确认的上一轮向下一章串页修复，本次反向跨章仍待人工验收。

定向测试命令（经 `scripts/gradle-coordinator.py` 串行执行）：

```text
gradlew.bat :app:testReleaseUnitTest
  --tests eu.kanade.tachiyomi.ui.reader.ReaderSyncResumeWiringTest
  --tests eu.kanade.tachiyomi.ui.reader.ReaderChapterWindowProductionWiringTest
  --tests eu.kanade.tachiyomi.ui.reader.ReaderProgressSettlementRaceTest
  --tests eu.kanade.tachiyomi.ui.reader.viewer.ReaderAdjacentChapterViewerProductionWiringTest
```

没有运行完整模块/全量测试，没有构建、安装或推送候选。
