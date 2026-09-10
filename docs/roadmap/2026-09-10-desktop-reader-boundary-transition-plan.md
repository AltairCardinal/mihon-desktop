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

## 完整章节信息补齐（当前迭代）

上一迭代仅实现提示卡，未包含原版 ChapterText，不能视为完整视觉对齐。本迭代使用 Luna xhigh 一个实现单元，主模型独立验收。现有未跟踪 testfile/ 不在范围内。

前置已核实：Android `eu.kanade.presentation.reader.ChapterTransition` 的边界布局、Desktop `ReaderChapterTransitionItem` 与 `DesktopReaderChapterContext`。后者已有 chapterTitle/scanlator/localChapterPath，优先从实际当前章节上下文及加载路由传递，不新建全局服务。Android composable 耦合 Android ReaderChapter 与预览，不能直接由 Desktop 引用；边界布局限于 Desktop adapter 对齐，不改 Android 行为。

允许写入阅读器 UI/相关上下文 adapter、对应测试、必要 parity 证据和本计划。步骤：先渲染失败测试 → 最小 production 元数据贯通与布局 → focused 自测及离屏 PNG → 主审 → 完整 Desktop 测试/格式/正式构建 → 提交。不得修改无关功能或以源码扫描代替渲染测试。

本迭代冻结标准（初始 pending）：

| ID | 输入/操作 | 可观察预期与验收 |
|---|---|---|
| C1 | 无上一章边界 | 原版 transition_no_previous 卡在上，24dp 后 transition_current 与当前章名在下；挂载真实 viewer 检查文字及位置。 |
| C2 | 无下一章边界 | transition_finished 与当前章名在上，24dp 后原版 transition_no_next 卡在下；切章后标题来自新上下文。 |
| C3 | 原版完整 ChapterText | header titleMedium/下边距4dp，章名20sp/titleLarge/最多5行省略；有 scanlator 显示 bodySmall/2dp/secondary alpha/最多2行；真实本地加载时显示22sp CheckCircle/label_downloaded，无下载不伪造标记。 |
| C4 | 单页/双页/LTR/RTL/Webtoon，长章名及明暗背景 | 容器最大460dp填充，卡片按内容宽居中，pager水平64dp，webtoon水平32dp/垂直128dp；有可读对比度。离屏实际渲染观察两方向、长标题及两背景，PNG为测试输出。 |
| C5 | 从边界返回漫画页 | 保留独立条目与翻回消失；真实 viewer 渲染/输入或滚动验证，真实页码及章节导航无回归。 |
| C6 | 验证/交付 | 红绿focused渲染行为证据，parity、格式、一次Desktop全量及正式Windows runtime构建通过，主模型观察PNG并仅提交相关文件。 |

过程产物只更新本计划；测试 PNG 为视觉验收证据，放忽略的 build 输出。最终需记录本迭代实际验证与边界，不能以子代理自报或测试绿代替主模型视觉观察。

### 当前迭代验收证据

- 红测：`reader-boundary-layout-red-20260910-r2` 因缺失原版章节区失败；`reader-boundary-prefetch-red-20260910-r1` 因预取激活未保留实际本地状态失败。编译错误和测试夹具错误不作为产品红测证据。
- Luna 自测：`reader-boundary-layout-focused-final-20260910-r3` 通过 59 项测试。实现使用 GPT-5.6 Luna / xhigh；子目标创建时有文本缩写偏差，已保留记录，主模型仍按本计划完整 C1–C6 验收。
- 主模型独立 focused：`reader-boundary-main-focused-20260910` 退出码 0，覆盖完整章节加载与 session 集成、真实 ReaderContent 挂载、翻页回归及 DesktopProductCapabilityContractTest。主审发现单页父子重复点击处理风险，已关闭子图片的单击导航；回退断言使用递增动画帧和固定 640px 视口，确认提示移出且漫画条目可见。
- 主模型实际观察 `app-desktop/build/reader-boundary-samples/` 的灰底 previous、黑底 Webtoon、白底 next PNG，核对原版中文、章节信息顺序、对齐、图标及对比度。输出来自真实 Compose 离屏渲染，不是系统屏幕截取。
- 变更超过 8 个文件/400 行：本批次是同一章节边界能力，文件跨越内容路由→预取/激活→当前章节上下文→真实 viewer；必须一起维护才不会在切章时丢失或串用本地标记。新增测试为这条链路的行为证据，无独立产品范围或无关重构。
- 边界：仅补齐无相邻章节的提示页，不改变 Android、章节筛选或下载/预取策略；本次验证目标为 Windows Desktop，不要求 Android/macOS 全量发布。
- 完整验证：`reader-boundary-layout-full-20260910` 执行 `spotlessCheck :app-desktop:jvmTest`，退出码 0。JUnit 汇总 2,988 项，2,986 通过、0 失败/错误、2 跳过；跳过项为 macOS 原生分享及非发布构建的书库设置条件用例，本批次阅读器测试无跳过。
- 发布验收：`scripts/build-desktop.sh build-only` 退出码 0，基于上述同一产品 diff 的完整测试证据。正式未打包运行时版本与 production APK 安装验收通过，发布版本 `0.11.19.33.892a006`；EXE 为 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.33.892a006-unpacked/Mihon Desktop.exe`。构建日志：`.gradle-coordinator/reader-boundary-layout-build-20260910.log`。
- 主模型结论：C1–C6 的本批次实现、复审、渲染观察、focused/全量与 Windows 发布验收均通过；随本计划所在功能提交一起交付。用户侧仍可按前后边界→查看章节信息→翻回漫画的路径复验。
