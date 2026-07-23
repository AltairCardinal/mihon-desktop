---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 7
capability-id: 19
related-capability-ids: 17,19
status: planned
task-base: 68cfae3aa118c242af1e8d427175efe5d4df99da
---

# Task 7A：Library 批量操作入口与反馈对齐

## 目标

补齐 fixed-main `LibraryBottomActionMenu` 已提供、Desktop 当前批量栏缺失的批量下载、迁移与反选入口，并为 ID 17 增加会在 Android production `filter()`/`sortForAndroid()` consumer 断线时失败的真实行为测试。复用现有选择状态、下载队列、迁移 controller/queue 和 partial-failure 模式；不得另建平行业务链。完成后仅输出 R2 hash/status 交给 R3 回收，本阶段不修改审计文件或预先承诺 `VERIFIED`。

## 已确认边界

- fixed-main authority 仅为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的 `LibraryBottomActionMenu → LibraryTab → LibraryScreenModel`。
- Desktop 已有分类、标记已读/未读、移出收藏、全选/清空和单项“下载下一未读”能力；Task 7 已证明这些 production 切片，不重复实现。
- Desktop 批量栏当前没有批量下载、迁移和反选入口，因此 ID 19 保持 `WIRED`。
- 下载复用 `MangaDetailDownloadAction`/`chaptersForDownloadAction` 的六档选择语义并扩展 `DesktopDownloadManager` 现有队列核心，不能用每本仅首个未读的 `enqueueNextUnreadDownload()` 冒充批量能力；迁移固定复用 `DesktopBatchMigrationController.submit()` → `MigrationBatchQueueScreen` → 逐项 `MigrationSearchScreen`；选择复用 `LibrarySelectionState`。

## 用户入口与反馈

- 入口：Library 多选模式的批量操作栏显示下载、迁移和反选。
- 下载：完整提供下一 1/5/10/25、全部未读、书签六种档位；对当前选择逐项处理，跳过已排队/下载中/已下载章节，成功项继续，失败项以现有可见 feedback 汇总，空选择不产生副作用。
- 迁移：仅对可迁移的非本地项开放，把当前选择转换为现有 batch request，提交既有 controller 后进入 queue，再由 queue 逐项进入搜索；导航类型必须与当前 Voyager `Navigator` 兼容。
- 反选：仅反选当前可见列表，隐藏/过滤项不被意外加入。
- 危险操作继续使用现有确认边界；本计划不新增静默删除或自动迁移。

## 文件与规模

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibrarySelectionState.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryComponents.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryScreenModel.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryParityIntegrationTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryScreenModelTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/NavigationContractTest.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/ui/library/LibrarySharedEvaluationWiringTest.kt`

总范围不超过 8 个文件、400 touched lines；若现有迁移入口不能复用，必须暂停并修正计划，不得另建迁移框架。

## TDD 固定步骤

1. **RED — 选择与入口：** 先写真实选择状态/UI wiring 测试，证明当前缺少可见列表反选，以及批量下载/迁移 action。
2. **RED — 行为与失败：** 先写 `LibraryScreenModel` 测试覆盖六档下载、跳过已排队/下载中/已下载、多项继续、空选择和部分失败反馈；迁移导航必须实例化 queue/search `Screen` 并验证 Navigator 类型；Android 测试必须执行 production model 的 filter/sort consumer，删除调用时失败。
3. **GREEN：** 用最小实现复用现有下载 action/queue、批量迁移 controller/queue 和选择链，让入口、反馈与 Android wiring 测试通过。
4. **REFACTOR：** 合并重复 action wiring，保持批量栏和 context menu 的既有行为，再跑 library focused tests。
5. **R2 交付：** 仅输出 R2 hash/status 交给 R3；manifest、contract、父计划与本计划的状态回收全部留在 R3。

## 验证

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.LibraryParityIntegrationTest" --tests "mihon.desktop.ui.library.LibraryScreenModelTest" --no-daemon
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.NavigationContractTest" --no-daemon
./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.library.LibrarySharedEvaluationWiringTest" --no-daemon
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest.task 7*" --no-daemon
./gradlew spotlessCheck --no-daemon
```
