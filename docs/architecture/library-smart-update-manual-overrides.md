# 智能更新的手动例外

SOURCE：Android `SettingsLibraryScreen` 原有智能更新多选弹窗与 `LibraryUpdateJob`；Desktop `LibrarySmartUpdateSettings` 原有即时智能规则开关与 `LibraryUpdateScheduler` 的任务快照。两端复用 `LibraryPreferences` 和 `libraryUpdateSkipReason`，不另建更新服务。

PROJECT_POLICY：设置 → 书库（Desktop 书架）→ 全局更新 → 智能更新。四个父条件被勾选时，其后缩进显示独立的“手动触发更新时忽略”子选项。子选项初始全部关闭；取消父条件仅隐藏子选项并保留其选择，重新勾选父条件会恢复。子选项的无障碍说明包含所属父条件。弹窗内容独立滚动，取消、遮罩或返回不写偏好，确认保存；Desktop Escape 关闭当前弹窗并将焦点还给入口。

两端使用各自 PreferenceStore 持久化父条件 `library_update_manga_restriction` 和独立子集合 `library_update_manual_restriction_overrides`。确认时依次写入两个集合；发生读写失败则尝试恢复原值及未设置状态，保留弹窗并显示错误。PreferenceStore 没有跨键事务，恢复也可能被存储故障拒绝；此时用户需检查实际设置后再试，不声称保存具有原子性。取消不写任何集合。

手动全局或分类更新仅过滤子集合中已指定的四个已知智能条件（已读、已开始、未完结、预计更新周期）。自动计划更新始终使用全部已选父条件。图源 `ONLY_FETCH_ONCE`、本地源、分类范围及其他保护不因子选项被绕过；作品详情单独刷新继续使用现有行为。未知限制键不能通过子集合绕过。

Desktop 在新任务初始化时保存有效规则快照。恢复或失败重试继续消费原任务快照，不读取后来修改的规则来改变已开始任务的范围。Android worker 在开始筛选时读取当前偏好，并根据自动任务 tag 区分触发来源。

固定验收：两端真实设置入口点击 → 子项显隐、父子独立、取消不写入、确认持久化；共享 eligibility 合约 → 单项例外、自动不例外、未知键保护、存储失败恢复；Android 实际 worker → 手动全项/单项及自动调用图源数；Desktop 实际 scheduler → 手动分类、自动计划、源限制、失败重试快照。HTML_ADAPTER：本次没有 HTML 原型，浏览器结果不能作为原生体验证据。

验证记录由本次 focused 测试与协调器日志提供；此功能不要求全模块、全量测试或正式发布构建。

## 本次验证证据（2026-10-13）

- 有效行为红：`smart-manual-red` 的 Android production worker 手动全项例外预期 6 次源调用，旧实现只有 2 次；自动分支保持 2 次。
- 有效保存红：`smart-manual-save-red` 的可用 Preference 合约夹具验证旧保存 helper 将未设置状态误恢复为显式值（预期 `isSet=false`、实际 `true`）。
- 有效 Android UI 红：修正真实懒列表滚动后，`smart-manual-ui-red` 对旧设置入口点击成功，但找不到 `smart-manual-manga_fully_read` 子项。`smart-manual-green4` 又发现子行缺少 checked 无障碍语义；该页面局部补充后，`smart-manual-ui-green5` 的真实设置交互通过。
- 有效 Desktop 红：此前组合命令先被 Android 失败阻断，Desktop 新测试尚未执行。之后 `smart-manual-desktop-baseline-red` 仅临时恢复本任务旧 scheduler caller/UI，三个新用例分别因单项例外仍被跳过、手动分支仍被跳过、智能设置标题无弹窗 click 入口失败；随后恢复当前实现。
- 夹具或工具失败不作为功能红：首次 Android UI 直接 `performScrollTo` 找不到尚未 compose 的懒列表入口；首次保存夹具使用了尚未实现 StringSet 的 InMemoryPreferenceStore；一次 test 错误 import 和一次 format init 误作用于 buildSrc 已分别更正。
- 格式边界：原 Spotless extension/task 配置即使显示目标数量，也未实际格式化 domain 外文件；临时 root 插件另遇既有 `clean` 注册冲突，这些不作为全范围格式通过证据。最终使用已缓存官方 `ktlint-cli:1.8.0`，working directory 为仓库根目录，显式限定 13 个本任务 Kotlin 文件；`smart-manual-format-cli2` 的格式化及独立只读检查通过，无大文件旧区域的无关重排。三份 XML 以 UTF-8 解析、尾随空白/末尾换行及差异检查验证。
- Desktop 绘制证据：`app-desktop/.gradle-coordinator/smart-manual-dialog.png`，JVM ComposeScene、900×760、暗色、英文；四个已选父条件与四个缩进子项、确认/取消按钮可见。这不是正式发布应用或原生系统窗口交互的验收。

本批跨共享 policy/preferences、两端 production 接线及设置 UI、真实 worker/scheduler/UI 测试和三份语言资源，超过 8 个文件来自同一能力的必要跨平台边界。关键风险是仅手动例外、来源保护、失败重试快照和偏好写失败；没有拆分独立更新机制或增加全量测试。

最终收口：`smart-manual-final` 完整运行所选共享/Android/Desktop focused 范围并通过（2 分 45 秒）。选定范围为共享 eligibility 4 项、Android 实际 worker 18 项和设置 UI 1 项、Desktop scheduler 14 项、SQL/source occurrence 2 项及设置/搜索/拒写 UI 4 项；没有执行完整模块或全量测试。之后仅在同一 Desktop nested UI 方法补上 Escape 关闭后触发器确实获得焦点，以及重新显示子项仍为选中状态的显式断言；`smart-manual-ui-contract-final` 在官方 CLI 格式化/只读检查后补验该 1 项并通过（22 秒，0 失败/错误/跳过），production 未再改变。后一次方法级执行会覆盖 Gradle 的 Desktop XML 目录，不能将后一次单项 XML 描述为完整范围结果；完整范围证据以其独立协调器命令和 PASS 结果为准。
