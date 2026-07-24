---
status: completed
parent: Task 9
capability-ids: [47, 51]
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
resume-parent: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
---

# Task 9A reader production wiring protection

## Goal

用可执行 production wiring 测试替代 ID 47/51 的源码扫描证据；不改变 reader 用户行为。只有删除真实 wiring 会使测试 RED，才能重判状态。

## Stage A：产品与测试

**Budget:** 最多 8 个文件、400 行；一个实现代理、一个独立审查代理。

**Files:** `PagerTransitionHolder.kt`、`WebtoonTransitionHolder.kt`、`ReaderChapterTransitionIntegrationTest.kt`、`ReaderVisualComponents.kt`、`DesktopReaderScreen.kt`、`ReaderColorMatrixTest.kt`，以及仅在 fixture 无法接入时使用的一个测试配置文件和一个新 fixture 文件。production 文件只允许加入最小可测 seam。

**TDD:**
1. RED：Android fixture 实例化并驱动 pager/webtoon holder 的 `sharedStateFlow`；Desktop fixture 挂载 Compose/Modifier/render chain，并从 filter 参数观察像素结果。禁止 `readText`、源码字符串或符号扫描。
2. Mutation：分别移除 holder 订阅和 `ReaderViewport -> readerColorTransform -> readerColorMatrix` 委托，测试必须因 production wiring 缺失而 RED。
3. GREEN/重构：以最小 seam 恢复 wiring，focused、Android/desktop reader integration、格式检查全绿。

**Boundary:** Android 两种 viewer 都必须覆盖 Loading/Error/Loaded；Desktop 必须覆盖 disabled、grayscale、invert/combined 中至少一个有效变换，并证明 mounted path 实际调用矩阵。若 Compose test runtime 不可用，停止并回报，不能回退为源码扫描。

## Stage B：审计收口

**Budget:** 最多 5 个文件、280 行；不修改 Stage A 产品实现。

记录 Stage A commit hash、RED/GREEN/mutation 与零跳过证据；重判 ID 47/51，更新 manifest、contract、父计划及确有必要的 tracker/replan。只有五角色和可执行 wiring 全闭合才升 `VERIFIED`，否则保留 gap；随后恢复父 Task 9 流程。Stage B 提交只交付自身 hash，不改写自身 hash。

## Verification

运行两端 focused tests、普通 parity contract、显式 final gate、`spotlessCheck`、`git diff --check`、精确范围守卫与一次独立审查；测试失败或审查发现阻塞问题时仅允许一次修复复审，仍失败则停止并重新规划。

## Execution evidence

- Stage A 基线为 S1 `c04721f44b5c490c541f05c868c77975142e87e2`，产品与 fixture 提交为 `d0311eb381a45d323bc28cd1ee4ac010e312fc2d`；范围为 5 文件、251 touched，独立审查 `APPROVED 0/0/0`。
- RED 先精确命中缺失的 Pager/Webtoon production observer seam 与 Desktop mounted color layer；GREEN 后 Android reader 为 `14/14`、Desktop reader 为 `21/21`，两端均 `0 skipped`。
- Pager mutation `adb45f9979871ada2230f9599baefa82a5d80ec4` 退化为一次读取时精确 RED，恢复为 `1895d5fdbe716f710ccf68f2e820886a03c06f5c`。
- Webtoon mutation `f7c2be665c461a039ecc28216c5b9373a35c3e90` 退化为一次读取时精确 RED，恢复为 `39cc3b93bbdbecbde301dffb9c3a0a5778bc3c22`。
- Desktop mutation `28c3968724488eb283d454a02829fae4bb73f10b` 移除 mounted layer 的 transform 时像素测试精确 RED，恢复为 `fddd1f2acadfe04aa2c75b6e072fbf36afd65ca4`。
- Stage B 复核确认 ID 47/51 的固定原版、当前 Android、shared/adapter、Desktop consumer 与可执行 fixture 五角色均闭合，因此两项提升为 `VERIFIED`；Stage B 自身提交 hash 仅在交付报告中提供。
