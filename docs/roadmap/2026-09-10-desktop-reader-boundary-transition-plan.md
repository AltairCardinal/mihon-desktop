# Desktop 阅读器章节边界过渡页对齐计划

## 结果

Desktop 阅读器在不存在上一章或下一章时，像 Android Mihon 一样把章节边界提示作为阅读流中的独立过渡页/条目显示；离开该过渡页后提示立即随页面一起消失，不再覆盖当前漫画页。

## 证据与上下文

- Android 权威实现：`PagerViewerAdapter` / `WebtoonAdapter` 将 `ChapterTransition` 插入页面序列，`ReaderTransitionView` 使用 `eu.kanade.presentation.reader.ChapterTransition` 绘制内容。
- 原版无章节文案：`MR.strings.transition_no_previous` 与 `MR.strings.transition_no_next`。
- 原版视觉：最大宽度 460dp、透明背景 `OutlinedCard`、水平 16dp/垂直 12dp 内边距、16dp 内容间距、primary 色 Info 图标、`bodyMedium` 文本；pager 居中且水平边距 64dp，webtoon 条目水平 32dp/垂直 128dp。
- 当前 Desktop 缺陷：`DesktopReaderScreen.ReaderViewport` 在内容外层叠加 `ChapterTransitionFeedback`，状态只能显式关闭或切章清理，导致遮页且往回翻不消失。

## 范围

- 允许修改 `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/` 下的阅读器呈现、导航和状态代码，以及对应 `app-desktop/src/test/` 测试与必要的 parity 证据。
- 可复用现有 `ReaderChapterTransitionModel`、`ReaderTransitionDirection` 和 i18n 资源；不得另造同义文案或独立业务模型。
- 禁止修改 Android 原版行为、下载/预取策略、章节筛选规则、无关 UI、用户现有 `testfile/`，禁止提交无关文件。

## 约束与授权

- 严格红绿重构：先增加会因“仍是覆盖层/不能随翻页退出/文本样式不符”而失败的 production wiring 行为测试，再做最小实现，最后整理并保持全绿。
- Pager 的上一章与下一章边界都必须支持独立过渡页；LTR/RTL 方向正确。Webtoon 必须使用独立列表条目表达边界，不以覆盖层表达。
- 复用原版字符串资源和视觉参数；若 Compose Desktop 无法直接复用 Android 类型耦合的 `ChapterTransition` composable，仅允许在 Desktop adapter 内实现等价外观，并用可观察的呈现契约测试约束。
- 保持现有有相邻章节时的激活、预取、页码、缩放、双页、自动滚动和键盘/点击导航语义。
- 中文交流、UTF-8、Gradle 协调器规则与仓库提交规则继续适用。Luna 不得启动下级代理、提交或发布。

## 完成标准

- (C1) 单页与双页 pager 在越过首/末页且无对应相邻章节时，新增且导航到一个独立章节边界 display item；漫画页仍是独立 item，不被遮罩。
- (C2) 从边界 item 往章节内部翻一页后，边界提示不再可见；再次翻到边界可重新显示。LTR/RTL 的前后方向均正确。
- (C3) Webtoon 无下一章时在内容末尾包含独立过渡条目，用户向上滚离该条目后当前页不带提示覆盖；无上一章的起始边界按原版阅读流语义呈现。
- (C4) 无上一章显示 `transition_no_previous`，无下一章显示 `transition_no_next`，不再使用“方向 + 章节”的 Desktop 拼接文案；提示页使用原版透明描边信息卡、Info 图标、排版宽度和 pager/webtoon 间距。
- (C5) 不再从 `ReaderViewport` 挂载边界全屏覆盖层；错误/加载过渡若仍存在，必须维持可用且不得重新引入持久遮页。
- (C6) focused production wiring/呈现测试、相关 Desktop reader 测试、`spotlessCheck`、`:app-desktop:jvmTest` 与正式 Desktop 构建运行验收通过；测试必须在移除 production wiring 后失败，不能只扫描源码文本。
- (C7) `git status` 审核后仅提交本任务文件，并在最终报告给出提交 hash 与实际 `Final unpacked EXE` 可点击路径。

## 正当阻塞项

仅限当前生产代码无法编译、Gradle/构建协调器不可用且一次诊断后仍无法恢复、或原版 Mihon 本仓库权威实现与需求发生不可调和冲突。实现难度、测试失败或需要改动多个内聚文件不是阻塞理由。

## 最终交付

Luna 返回逐项 C1–C6 的 diff、红绿重构命令与退出码、未验证项和流程状态；主模型独立审查、必要时定向返修、执行最终验证与构建、提交，并按仓库规定的功能/BUG/验收格式用中文交付 C1–C7 证据。
