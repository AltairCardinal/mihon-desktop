# 作品详情按钮与作品级读完即删：验证记录

日期：2026-11-13（Asia/Shanghai）。基线：`f1eec27b39c456aa2738c308e7cc793886beec93`。
状态：本地实现及相关 focused 验证完成，保留两项旧并发测试的环境验证缺口；尚无用户体验验收、打包或远端交付。

## 范围与隔离

继续交接中的独立功能副本，未覆盖 `D:/Shell/Github/mihon` 的阅读器、同步或网站任务改动。
本批文件横跨共享策略、共享 Compose 组件、两端 adapter、资源、依赖及对应测试，均为同一用户能力闭环；
没有扩展到冻结的全局删除设置对齐、延迟删除、数据库迁移或同步协议。

采用正常 Gradle，经本副本协调器串行执行，单 worker、离线依赖。最初 1GB 堆在 Desktop 应用编译
发生 GC thrashing；资源回升后改为 3GB，双端生产代码及新共享模块均完成正常编译。
历史临时编译日志不充当本轮最终接线证据。

## 红测与实现

- `detail-ui-resume-red-3g`：Desktop 更多页右滑真实指针测试 1 失败，失败为右滑后缺少定制节点。
- 同一运行的 Android 右滑与复制链接两项各执行三次，均因缺少预期节点失败。
  “Failed to inject touch input”的内部原因是找不到 COPY_LINK，并非设备输入受阻。
- 修正共享右滑直接进入定制页；左滑保持首屏、额外页及上界。
- Android 原网页长按实际复制链接，新额外按钮改用 COPY_LINK、复制文案和 ContentCopy 图标，保留原回调。
- 后续真实下载管理器专项发现默认分类保护失败：回退 `listOf(0)` 被编译成 Integer，排除 ID 为 Long。
  `javap` 确认回退调用 `Boxing.boxInt`。改用共享 `Category.UNCATEGORIZED_ID`，不改变文件删除实现。

## 已取得的运行证据

- `detail-focused-green`：共享契约 3/3；Desktop UI 10/10、偏好持久化 2/2、阅读进度 21/21、真实 DI 1/1。
- 同轮 Android 新交互、作品开启/关闭、书签和 DI 均有通过日志，但默认分类失败；两项旧并发回归随后
  报 UncompletedCoroutinesError。本轮已停止，不能称为整轮通过，也不复用旧红测 XML 为绿测。
- `detail-focused-recheck`：Desktop 右滑/大字号 2/2；Android 下载保护 2/2、DI 2/2、作品覆盖 2/2。
  Android UI 4/5，唯一失败是“跟随全局”同时命中背景摘要和弹窗选项，已改为定位真实弹窗选项 tag；
  `detail-android-ui-final-lf` 五项复验 5/5，通过正常 Gradle。首次最后复验被测试文件的 CRLF 格式检查拦下，规范为 LF 后通过。
- 四个 Kotlin 目标的 scoped Spotless 检查及适用的 XML 检查通过；翻译目录保留项目原有 Spotless 排除，中文资源由实际资源生成/编译与界面用例覆盖。Desktop 原本未接 Spotless，
  临时使用仓库同版本 ktlint 1.8.0 检查六个相关文件。两个既有大文件及 Desktop 构建脚本仅核对本批局部，
  避免格式化既有未纳入本次行为的内容；`git diff --check` 通过。

### 两项旧并发回归的边界

`B completion deletes already accepted A after both same manga writes commit` 与
`successful accepted completion does not restore its cancelled download after reader close` 在正常 Gradle
中超时。将超时界限定为 20s、禁用重试后仍复现；保留失败，不算通过。

进一步定向编译 HEAD 原始 ReaderViewModel 与 HEAD 原始测试，使用本轮正常 Gradle 导出的类路径、
相同 Kotlin 2.3.10，并保持 `app_release` 模块名：2/2 仍同样超时。初次对照因模块名不一致出现
NoSuchMethodError，已丢弃，不能作为对照证据；有效对照见过程日志 `baseline/valid-baseline.log`。
正常 Gradle 和有效基线对照均记录 `UnsatisfiedLinkError: no imagedecoder`，发生在原生 decoder 初始化。
这是可观察的运行环境障碍；完整超时原因尚未全部排清，不把无修改基线也出现的现象认定为本次规则回归。
该定向对照不等于完整基线 Gradle 验收，也不能代替真机图像解码验证。

通过证据按未受后续行为改动影响的范围组合：共享 3、Desktop 34、Android 11，共 48 个不同相关用例。
Surface 背景修正及格式化后的 Desktop 右滑/大字号又复验 2/2；这两项不重复计数。
两项旧并发用例仍失败，不能将组合证据描述为“全部 focused 一次通过”或“最终代码全量通过”。

本轮未运行完整模块、全仓测试、finalParityAudit、正式发布矩阵或设备操作。

## 审查与界面证据

使用两名子代理：一名负责共享 UI 的测试与最小修正，一名负责删除路径独立只读审查。
一轮审查、一轮修复复审：确认复制链接修正及真实 manager/pending 队列专项覆盖有效；没有剩余审查 findings。
之后运行中发现的默认分类数字类型修正属于局部保护恢复，通过对应真实行为测试收口，不引入新的删除路径。

Desktop 使用真实 Compose 离屏渲染，320dp、fontScale=2。首轮定制弹窗选项和 Save/Cancel 可达；
首屏缺少背景的夹具已补 Material Surface，重出图片已核对，视口内无按钮越界；200% 字号下英文长词会换行。
两张图片为 `app-desktop/.test-tmp/detail-actions-visual/{primary,customize}-320-font200.png`；
该证据不代替候选窗口或用户验收。

## 维护与交付边界

实现边界见 [架构说明](../architecture/manga-detail-actions.md)。三态作品设置为本机 appState，双端标准偏好
备份创建/恢复排除它；按钮布局为设备通用偏好。保存对话框不触发删除，取消不保存，写入失败保留草稿。

本批只提交实现、测试、内部架构及本记录；日志、临时运行工具、参数和图片是过程产物，不进入提交。
未创建可体验包、未推送、未修改公共功能说明。最终提交在完成回执报告，两个旧并发用例的环境缺口仍保留。
