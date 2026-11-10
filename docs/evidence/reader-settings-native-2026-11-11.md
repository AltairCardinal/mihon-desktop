# 双端阅读设置原生实现验证

日期：2026-11-11。设计依据为用户已通过的 [v3 原型](../prototypes/reader-mode-default/README.md)，维护契约见 [双端阅读模式设置](../architecture/reader-settings.md)。本记录不是用户对原生安装包的体验验收。

## 源码与范围

- 原生实现提交：`b69311e5cb2ca1dba8c01e75c2198bbc660f28df`。
- 随本记录整合固定主干 `ad07de4cca5055eb1748df4f9ad95a312bbf1ce6`，保留该主干的启动优化与其他已有改动。
- 工作区：`D:/Codex/worktrees/mihon-reader-settings-chips-20261108`；分支 `codex/reader-settings-chips-20261108`。未修改其他工作区的未提交内容，未推送或分配本轮发布版本。
- Android：作品设置、阅读器快捷入口与全局默认共用分组控件；实际 VM、作品 flags、SQLite 保存和 viewer/config 接线。
- Desktop：作品与全局控件、启动/完整模型的统一设置投影、真实 VerticalPager、点击/上下键、连续页面间隙、全局搜索锚点与作品保存/重开。
- 超过 8 文件的原因是两套原生视图 adapter、共享持久化契约、实际阅读器接线及对应测试需要共同交付；没有引入新数据库列、下载机制或配对算法。

## 红绿证据

- 共享 AUTO LTR 首次 flags 往返失败；修复为兼容 Int 备份的模式 9 编码。旧 Desktop 仅 layout 标记的继承判定亦先失败后修复。
- Android 原控件缺少跟随/定制与纵向滚动分组，且不识别模式 9，初始行为测试失败。真实 VM 的旧双页标记用例出现 expected 6 / actual 7，接入共享有效设置后通过。
- Desktop 初始测试复现纵向 flags 被降级、默认切换丢失定制、缺少竖向 pager 和锁定 webtoon hint；修复后原范围复验。实际连续列表间隙也有旧 spacing 0 的失败证据。
- 主干合流后，新增测试复现启动投影丢失完整设置、纵向误用横向启动视图；统一投影并排除纵向后通过。原有 early 输入和图像交接测试保留。

## 验证结果

所有 Gradle 均通过项目协调器，限定 `--tests`，使用本机 JDK 21、已有 SDK 与缓存，`--offline --max-workers=2`。未运行完整模块/全量测试。

| 范围 | 结果与证据 |
| --- | --- |
| 共享 flags/设置契约 | 8 项通过，含旧数据、隐藏值、默认恢复、无关 flags、Int 往返；`reader-settings-shared-final-2`，后续 `reader-settings-desktop-green` 再次通过两契约类 |
| Android 设置与真实 VM/native config | 25 项组合通过；`reader-settings-android-legacy-green` 中 24 项通过，末例更正夹具预期后由 `reader-settings-android-chips-complete` 通过 |
| Android 生产入口与存储 | 1 项通过，`reader-settings-android-factory`：实际 Activity factory、AppBars/设置控件动作、真实 VM、SetMangaViewerFlags、MangaRepositoryImpl/SQLite、manga/events 订阅及新 LTR 双页 viewer/config；与前行共 26 项有通过证据 |
| Desktop 合流 focused 范围 | `reader-settings-merge-green` 执行 148 项，145 通过；一项来源夹具的 Flow/List 同名 getter MockK 冲突改为真实 SourceManager 对象后，`reader-settings-merge-loading-2` 通过。组合为 146 项通过、2 项已有基线失败 |
| Desktop 最终核心补验 | `reader-settings-merge-final-2` 的 3 项通过：完整初始设置投影、纵向排除横向 startup、真实 Loading UI 修改全部设置字段/作用域并保存，页表到达与第二次打开后仍保留 |
| 格式与资源 | Android 与 Desktop 任务改动的 focused ktlint 检查通过；Desktop 最后 8 接触文件再次通过；7 个相关 XML 解析通过；本任务相对固定主干的 `git diff --check` 通过 |

完整实际命令、退出码、日志保存在工作区忽略目录 `.gradle-coordinator/<上述 key>.json/.log`。这些记录是分阶段组合证据，不表示最终代码重新运行了整个模块或发布矩阵。

合流时保留主干已有第三方 `windows-miniz/miniz/LICENSE` 与校准 properties 夹具的原始 CRLF。相对旧分支的整份合流差异检查会报告这些既有行尾空白；本任务不改写它们。交付检查以 `git diff --cached --check ad07de4cca5055eb1748df4f9ad95a312bbf1ce6` 限定实际新增/修改内容。

## 两项已有失败

以下两个断言在未含本轮原生改动的独立 `c6ac535359` 快照中均复现相同失败；合流后的现象也相同：

1. `AdaptiveReaderViewportTest.production reader resize preserves page and progress and real next tap resumes reporting`：期望 `[2, 3]`，实际 `[1, 2]`。
2. `ReaderScreenModelTest.invalid sync resume confirms the drawn partner while logical target is still loading`：partner `prepare` 期望 true，实际 false。

不能据此宣称全绿，也未将其归因于本次设置实现。临时夹具尝试未关闭它们，已撤回；本轮不修改生产配对算法或放宽原断言。基线日志、退出码和两份 XML 保留在 `.gradle-coordinator/reader-settings-native/baseline/`；临时基线工作区已清理。

## 视觉与测试边界

- 人工核对实际 Compose 组件的默认、横向、纵向三种状态截图：`.gradle-coordinator/reader-settings-native/default.png`、`reading-mode.png`、`vertical.png`。中文、900×1600 离屏场景、测试 MaterialTheme；不是操作系统窗口或用户当前主题的验收。图标、方向等宽、说明、开关无是/否文字可见；截图推进动画时钟至稳定帧，纵向截图保留鼠标悬停反馈。
- Android 覆盖 320dp/1.6 字号、单选组语义、真实点击及持久化。补充生产入口测试使用已验证的 Dialog host，消费 Activity 与实际 overlay 共用的 factory；native viewer 容器未附着。synthetic PageLoader 按既有前台资源策略暂停 optional 原图采样，以隔离 Windows JVM 缺少 imagedecoder JNI 的边界；它验证 viewer/config 接线，不验证 JNI 采样、图像绘制或动画。
- 原 overlay 尝试的取消、Looper/动画/JNI 失败日志保留；最终 factory 用例没有未捕获异常。测试线程夹具修正不改变产品线程策略。
- 未启动真实设备/模拟器或完整 GUI 应用窗口，未生成本轮 APK/安装包，未做正式发布验收。原生功能待用户体验验收后再更新公共功能说明。
