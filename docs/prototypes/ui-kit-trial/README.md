# Mihon UI 规范双端实验

打开 [双端并列入口](./index.html)，或单独打开 [单端入口](./device.html?platform=android)；单端参数可用 `platform=windows`。应用外工具栏可以切换深浅色，并链接到[旧同步 DEMO](../multi-device-sync/index.html)对照。本目录是本地 HTML 实验，不连接网络、数据库、下载器或真实同步任务；刷新会重置所有假数据。截图是候选视觉证据，未经人工确认为生产基准。

本实验只覆盖书架搜索与三态未读筛选、漫画章节选择与本地阅读预览、删除假下载确认，以及书架同步面板内的本机设置。底栏保留平台入口差异，但范围外页面明确禁用。Windows 有独立「作者」，Android 没有；从漫画卡片进入详情，两端都从书架顶栏进入同步面板。章节示例的其他漫画可用于检查下载状态隔离。

## 页面契约

首轮基线为已提交的 `407ec81428c5070c10758da51cf209e25da2c7ed`；本轮按[Desktop UI 规范](../../design/mihon-desktop-ui/README.md)及[冻结页面契约](../../design/mihon-desktop-ui/page-contracts.md)改进同一目录。HTML 实验不能充当 Kotlin production wiring 或原生 Compose 验收。

| 页面 / 类型 | 入口、容器与状态所有者 | 返回、关闭、焦点 | 副作用与边界 |
|---|---|---|---|
| 书架 / root | 各 iframe 自有顶栏、底栏和内存状态；标题、查询、筛选与漫画卡片 | 搜索开后输入聚焦；清空仍在搜索；Escape 先收筛选，再退出搜索；详情回跳恢复查询、筛选、滚动和卡片焦点 | 查询只查当前书架的标题；未读筛选按全部→仅未读→排除未读循环；状态不跨刷新 |
| 漫画详情 / detail | 卡片进入；唯一详情顶栏、章节滚动列表；稳定书 ID 与章节 ID | 返回先取消多选，再回书架；显式多选预选首项；取消最后一项退出；Shift/触摸长按以当前有序章节选择闭区间 | 全选、反选、删除作用于当前漫画；选择时隐藏继续阅读入口 |
| 阅读预览 / reader | 继续阅读或普通章节点击进入；按漫画与章节保存假位置 | 返回详情并恢复来源操作焦点；翻到第 3 页视作该章节读完 | 只显示示意页，不加载图片、写历史或调用真实阅读引擎；无未读候选时隐藏 FAB |
| 同步面板 / modal+settings | 书架同步按钮；面板内设置子页；当前 iframe 的两个布尔开关 | Escape 先设置返回面板，再关闭面板；关闭回到书架按钮；弹层入焦并圈定 Tab，背景 inert | 开关即时更新本机假状态；关闭不启动任务；遮罩关闭整个面板 |
| 移除下载 / modal | 已选择且至少一项已下载时可打开；打开时固定作品与章节 ID 快照 | 取消、遮罩、Escape 回原批量操作，保留选择；Tab 留在框内 | 只移除快照中仍已下载的章节；反馈实际数与跳过数，另一本不变 |

内容区域自身滚动，顶栏和底栏由外壳提供；面板自身滚动。Android 与 Windows 的差异限制在状态栏、顶栏高度、底栏入口和设置摘要。消息由应用窗口的状态条提供。外部工具栏不进入设备 iframe，切主题时不传递导航或焦点状态。

## 规则映射与来源

| 决定 | 来源和规则 | 本次可验证的范围 |
|---|---|---|
| 页面、局部模式与弹层返回分层 | UI 包 `01-navigation.md` UI-01、UI-03、UI-04；本机旧 DEMO `README.md` 的书架同步面板路径 | 浏览器实际点击与 Escape，测试检查标题、返回和面板层级 |
| 查询 `null` 与空串分开，清空不关闭、无结果给解除限制动作 | UI 包 `02-content-actions.md` UI-11 与 `04-async-feedback.md` UI-18 | 标题查询、本地即时过滤；没有异步旧结果测试 |
| 选择稳定 ID、计数、取消最后一项、批量适用子集 | UI 包 UI-08、UI-09；本机 `MangaScreenModel.kt` 的选择接口及 `MangaDetailScreen.kt` 的章节批量操作 | 三个假章节；全选、反选、Shift/触摸长按范围和确认快照；本机 Desktop 章节状态尚无反选与范围，这是 PROJECT_POLICY/HTML_ADAPTER |
| 弹层确认、副作用与本机开关 | UI 包 `03-dialogs-forms.md` UI-14、UI-15、UI-17；旧 DEMO `app.js` 的 `startup-setting`、`periodic-setting` 与面板返回 | 浏览器验证取消无副作用、Tab 圈定和本机隔离；无真实偏好存储 |
| 明暗主题、图标与平台导航差异 | UI 包 `05-visual-accessibility.md` UI-22、UI-25、UI-26；旧 DEMO `ui-view.js` 的图标路径；当前 `TachiyomiColorScheme.kt` 和 Desktop 组件封面源码 | 默认深/浅 `primary/surface/onSurface/surfaceContainerHigh` 对应源码；Desktop 网格及详情封面 7:10、Android Book 2:3；截图待人审 |

本包规范基于另一提交，`component-baseline.md` 也明示未核查本机工作树。因此上表的“规范来源”仅代表设计约束，不代表上游原版已满足。漫画名、封面占位、三条章节、默认已下载、显式多选预选首项、禁用范围外底栏、弹层 focus 细节和下载状态内存模型是本实验的**自主推断或简化**，不能算规范效果。平台入口和同步本机开关语义由本仓库现有 DEMO 与源码补足，不归功于 UI 包。

`NOT_IMPLEMENTED`：真实阅读引擎、下载器、同步、持久化、进程恢复、动态色、RTL 与原生键盘/读屏设备验证。浏览器测试会把当前页面元素的实际计算字号逐项设为原值两倍，检查关键操作和内容滚动；这仍不等同 Android 系统字体设置或原生读屏验证。UI-09、UI-12、UI-27 仍只验证本实验相关条款，不能标作整类通过。HTML 页面也不复用 Compose 的 `AppBar`、`AdaptiveSheet` 或 Preference 组件，仅模拟其可审阅结构。封面色块、三章节、每章三页、显式多选预选首项、HTML 的 450ms 长按阈值与按钮像素尺寸均为 HTML_ADAPTER 决定，不冒充源码常量。

## 冻结契约证据索引

以下均指 `trial.test.cjs` 中实际点击页面的浏览器用例。`PASS` 只表示对应 focused 用例通过；主代理的独立 oracle 与最终联合运行另行记录。

| ID | 操作与断言 | focused 用例 | 当前证据 |
|---|---|---|---|
| L1 | 搜索输入聚焦、清空不退出、无结果恢复 | 书架搜索和筛选 | PASS |
| L2 | 三态循环、`aria-pressed` 与结果数、查询叠加 | 改进书架 | PASS |
| L3 | 进入详情再回跳，查询、筛选、滚动和卡片焦点恢复 | 改进书架 | PASS |
| L4 | Escape 先收筛选、保留活动条件 | 契约补充：筛选先退 | PASS |
| D1 | 显式多选、点击切换、零选择退出；普通点击进预览 | 详情多选；改进章节 | PASS |
| D2 | 全选、反选、全选后反选退出 | 改进章节；契约补充：反选归零 | PASS |
| D3 | Desktop Shift 正反方向闭区间、Android CDP 真实触摸长按 | 改进章节；改进 Android；契约补充：反向范围 | PASS |
| D4 | FAB 进预览、翻页位置回详情、选择隐藏、无候选隐藏 | 改进章节；契约补充：读完本书 | PASS |
| D5 | 取消不变、快照确认、混合实际数与另一本隔离 | 详情多选；混合下载状态；弹层焦点 | PASS |
| D6 | 确认、选择、阅读预览、普通详情逐层返回 | 详情多选；改进章节；弹层焦点 | PASS |
| S1 | 两端开关即时状态与关闭重开 | 同步设置；改进弹层与主题 | PASS |
| S2 | 设置内退、主面板退出、关闭回触发器 | 同步设置；弹层焦点 | PASS |
| S3 | 弹层入焦、背景 inert、Tab/ShiftTab 与取消 | 弹层焦点；改进弹层与主题 | PASS |
| S4 | 切主题保留 DOM、设置及外层按钮焦点；书架回跳滚动、尺寸变化保留弹层 | 改进弹层与主题；改进书架 | PASS |
| V1 | 两端书架/详情真实封面几何与 SVG 描边 | 改进视觉；320 宽和桌面宽 | PASS |
| V2 | 深浅面板颜色匹配默认色板，主题切换后状态不丢 | 改进视觉；改进弹层与主题 | PASS |
| V3 | 320px、桌面宽无横溢；真实计算字号×2后，两端设置/确认/返回实际点击且溢出内容可滚达 | 320 宽和桌面宽；真实200%字号 | PASS（focused） |
| V4 | 深色双端、320px 书架/选择/确认、浅色双端设置及一张大字确认截图 | `screenshots/` 六张候选图 | 常规图已由主代理看图；大字图待看 |

视觉来源修正：首轮评估把两个平台 Book 封面都当 2:3；当前 Desktop `LibraryComponents.kt` 与 `MangaDetailComponents.kt` 均显式 `aspectRatio(0.7f)`，因此本轮改成 Windows 7:10、Android 2:3。`surfaceContainerHigh` 的深色 `#292730`、浅色 `#FCF7FF` 读取当前 `TachiyomiColorScheme.kt`，不以旧 DEMO 的普通 `surfaceContainer` 代替。CSS 的 560/720px 仅作为 560/720dp 面板上限的浏览器适配，并非 Compose 实测尺寸。

## 验证记录

首轮 TDD：先写 `trial.test.cjs`，浏览器红灯 4/4（目标入口尚不存在）。补页面后 4/4 绿；下载行为断言先因只有“已选”反馈而红，补假下载状态后绿。独立验收的描边图标、弹层焦点、零选择和跨漫画状态断言先红后绿；混合状态测试曾因 Snackbar 遮挡按钮红，改为不接收指针事件后绿。

本轮 TDD：新增四个组合场景起初 4/4 红，分别定位三态缺失、阅读入口缺失、手机长按不形成范围、切主题重绘破坏焦点。行为实现后各自 focused 转绿；书架滚动断言先纠正 Playwright 点击前需把卡片滚入视口的测试前置。视觉测试先因 Desktop 封面非 7:10 红，修复 CSS 后绿。追加读完全书隐藏 FAB 先红后绿。独立 oracle 对平台比例/颜色、外层焦点和开关事件负对照的验收由主代理运行；本目录不复制或更改其 oracle。

V3 复审修复：旧 `body.style.zoom=2` 只缩放视图，不能证明字号翻倍。新增测试先读取弹层标题计算字号，再将当前模态全部元素逐项设为原计算字号两倍并断言倍率；在 Android 320×390 和 Desktop 900×500 上实际点击设置开关、返回、关闭、下载确认取消和确认，并检查可滚达与无横向溢出。320×390 确认框曾超出视口（红），增加最大高度和内部滚动后 focused 绿；操作按钮在大字下保持单行。截图使用 320×700 展示完整大字确认框；窄高 390 的内部滚动由行为测试验证。

运行时在当前 PowerShell 会话设置 `PLAYWRIGHT_CORE_PATH` 指向可用的 `playwright-core` 目录，然后执行：

```powershell
node --test --test-concurrency=1 docs/prototypes/ui-kit-trial/trial.test.cjs docs/prototypes/ui-kit-trial/contract-oracle.test.cjs
node --check docs/prototypes/ui-kit-trial/trial.js
```

自动化实际驱动 Chrome 页面 DOM，检查搜索/筛选、选择/确认、面板返回、焦点圈定、设备隔离与两种视口尺寸。截图文件名标明主题和尺寸；截图只能证明当时的 HTML 像素，不能证明原生 Mihon 视觉一致。

## 截图与验收边界

下列截图均为 Chrome headless、本地 `zh-CN` 的候选图，基线 `407ec81428c5070c10758da51cf209e25da2c7ed` 后的未提交 v2 工作树。除标明 `font200` 的一张外均用浏览器默认字号。`dark/light` 是默认 Tachiyomi 主题的 HTML 映射；尺寸是浏览器外层视口像素，不是 Compose dp。

- [深色双端书架 1440×1000](screenshots/dark-dual-1440.png)
- [深色 Android 书架 320×700](screenshots/dark-android-320.png)
- [深色 Android 章节选择 320×700](screenshots/dark-android-selection-320.png)
- [深色 Android 下载确认 320×700](screenshots/dark-android-confirm-320.png)
- [浅色双端同步设置 1440×1000](screenshots/light-dual-sync-settings-1440.png)
- [深色 Android 下载确认：计算字号 200%，320×700](screenshots/dark-android-confirm-font200-320x700.png)

首轮完成时 6/6 浏览器用例通过，见[评估报告](../../design/mihon-ui-kit-evaluation/README.md)；它对应旧交互，不替代本轮联合验收。最终 `trial.test.cjs` 与独立 oracle 的完整运行、脚本静态检查和看图结论由主代理汇总；测试结果只对应上述本地样本，不代表 UI-01–32 全部符合。

## v2 最终联合验收

2026-09-25：主代理一次联合运行 `trial.test.cjs`（14项）与 `contract-oracle.test.cjs`（3项），**17/17通过，0失败、0跳过**，约19.3秒。四个脚本语法检查通过。主代理目视审阅深色双端书架、320px章节选择、浅色设置及字号200%确认框；它们仍是HTML候选图，不是原生基准。

长期规则入口见[Desktop UI实施规范](../../design/mihon-desktop-ui/README.md)，完整循环、基准纠正和独立审查结果见[改进报告](../../design/mihon-ui-kit-evaluation/improvement-report.md)。
