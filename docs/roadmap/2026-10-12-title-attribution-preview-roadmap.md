# 标题署名双端预研体验计划

用户授权：2026-10-12，以预研性质实现 Windows 与 Android 版本供体验。基线 `c2da62a163`；开始时工作区干净。完整设计见[署名与追踪设计](../2026-10-12-creator-attribution-and-tracking-design.md)。

active-task: none

## 本轮交付范围

按 sourceId 显式开启标题署名规则；识别前置 `[社团 (个人)]`，个人与社团分别进入现有作者页及关注/新作详情复核。两端作品详情提供确认、关闭、真实解析预览与显式整理本图源书架入口。原始字段、漫画自然键、收藏、阅读状态保留；别名沿用已有管理。

规则是用户确认启用的预研语法，并非已验证所有扩展版本的作者适配。启用后仅合格标题替代字段署名；不合格标题回退现有字段。首次操作重算当前作品，书架整理由用户显式触发，使用有界批次；不处理未收藏历史发现，不自动转移上传者关注，不新增实时纠错同步或完整来源搜索过滤器适配。

## 固定步骤和验收

- [x] TA-01 共享提取、来源偏好与生产链路：先失败测试，再实现同一 parser/policy 贯穿库索引和发现详情；确认原文保留、source 隔离、个人/社团分别匹配、人工关系保留、关闭回退、备份 evidence 往返和双端 DI。
- [x] TA-02 原生操作闭环：详情打开“标题署名（预研）” → 预览解析结果 → 确认开启/关闭 → 当前署名刷新；取消不写入，失败保留草稿；显式整理此图源书架 → 进度和结果，取消/失败说明已完成批次；真实按钮事件与接线测试、标题变更重组、角色标记、窄屏与主题可达。
- [x] TA-03 独立审查和定向补验：审查共享判断、自动关系替换、偏好失败恢复、备份与 DI；最多一轮审查与一轮修复复验，关闭具体阻塞后按受影响范围验证和格式检查。
- [x] TA-04 体验包与提交：Windows 用 build-desktop preview，核对 Final unpacked EXE 与 production runtime；Android 用 build-android candidate 交付签名候选，Debug仅用于隔离原生回归，核对身份与产物清单；授权且可隔离的模拟器执行本地夹具原生用户路径；记录实际通过/未运行项，只提交本任务文件。

## 流程预算

实施代理一个，主代理同时处理 UI、文档和只读环境核查；稳定后独立审查代理一个，总计最多两个。唯一 Gradle 协调者串行执行 focused 红绿、接线及格式检查；不运行完整模块或全量测试。Windows preview 一次，Android candidate 一次（遵守仓库用户交付要求），另一个Debug包用于隔离模拟器回归；编译失败后的必要定向修复可补构建，但显著增加成本先说明。

交付物是双端真实体验产物、实现测试及本计划中的紧凑证据；过程日志使用现有 Gradle 协调器，不新增生成器或状态系统。预计 1–2 小时，主要成本为 Kotlin 编译、focused UI/集成验证和平台打包。没有稳定发布里程碑，不递增正式 Desktop 版本，不宣称发布就绪。

## 执行证据

本批跨共享解析/关系索引/发现复核/备份与双端原生接线，超过8文件/400行仍为一个可体验闭环；无数据库迁移，不拆成无法独立验收的微提交。其他会话的 catalog-filter 改动单独保护。

- 核心红测：`title-attribution-red-2`（7/2失败）、`title-attribution-data-red`（2/2）、`title-attribution-editor-red`（10/5）、`title-attribution-chapter-red-3`（5/1）、`title-attribution-notification-red`（24/1）、`title-attribution-preference-red`（4/1）；均记录于 `.gradle-coordinator/<key>.log`。
- 核心 focused 基线 `title-attribution-final`：4个domain类24项、4个data类72项，共96项通过。覆盖提取、来源设置、库索引、详情发现、通知退出、章节目录、同步投影和备份证据。随后提交后取消的审查修复由 `title-attribution-review-red` 两项正确失败及 `title-attribution-review-green` 相关6项通过关闭；没有声称最终代码重新运行96项。
- 双端入口红测：`title-attribution-ui-red-2` 中Desktop3、Android2因缺少真实详情入口正确失败；Session4在上一红测后转绿。初次原生夹具使用不支持string-set的测试store，已改为真实平台偏好，该夹具异常不作为行为红证据。
- `title-attribution-ui-green`：Android UI2、Android DI2、备份6、restore wiring5通过；Desktop DI2通过，Desktop UI2失败为等待下一次弹窗重组不足，测试改为等待实际节点出现。所有调用仍执行真实OnClick与editor，不模拟实现。
- 独立审查一轮及唯一修复复验：提交后取消P1已由真实SQLite事务测试关闭；失败后完成数、正文个人/社团标记P2已修。`title-attribution-partial-red` 的Session、Desktop、Android各一项正确复现部分成功后结果丢失。复审源码通过，无新增阻塞，最终focused 12项（Session5、Desktop4、Android3）已通过；同命令的独立instrumentation编译因多余成员导入失败，修复后 `title-attribution-device-test-build-2` 与 `title-attribution-device-test-fix` 构建、限定格式检查通过。
- 原生Android：任务独占AVD `mihon-title-attribution-preview-20261012`、serial `emulator-5558`、API36/ranchu；开发APK `b597abb51793f9ad30bd48d47b4804e6d167ba97db1afdf5ad3776d4b2b2e27c`，隔离包名 `.fork.dev`。真实MainActivity→详情来源开关→个人页→关注→真实SQL断言及原字段保留，`TitleAttributionPreviewInstrumentationTest` 1项通过，日志 `.tmp/title-attribution-native-result-final.txt`。初次标题节点的精确唯一断言不适合合并语义树，修正测试选择器后通过，产品未改；实体设备未操作。
- Windows：`title-attribution-windows-preview` 成功；[实际EXE](../../app-desktop/artifacts/preview/windows/20261011T193020Z-cabf768a/Mihon-Desktop-0.11.19.91.292eaf5-unpacked/Mihon%20Desktop.exe)。manifest build/sourceIntegrity/productionRuntime均PASS，nativeInteraction为NOT_RUN；Desktop真实Compose组件事件已由4项focused覆盖，未把组件测试称为打包后整窗原生点击。
- Android：[签名候选APK](../../app/artifacts/android/0.19.4-aex.60-vc78-292eaf5682-release/Mihon-Fork-0.19.4-aex.60-vc78-release-universal.apk)，vc78，原证书连续，R8与资源压缩开启，遥测/更新器关闭；SHA256 `fe456b8369fc191ce1e8a407b06a49e0796ae0aa8379347a01b1305a8f244a82`。`build-android.py verify`通过；已在任务独占API36模拟器安装并核对APK身份/hash；冷启动正常。复用同一虚构DB/偏好夹具后发生一次启动异常，重启后真实R8详情显示个人/社团，点击进入青岚作者页并显示已关注状态；这不是用户升级/备份迁移验收。
- 两端冻结的production输入摘要同为 `62548c7785e4e9dd588cb96939d72960f2330415723f1db8ee75750d607b8bcb`，基于HEAD292eaf加本轮未提交差异。包含另一任务未接线的元数据接口占位，不把它当作已实现语言筛选，本提交排除这些文件。未执行全量、联网成人图源、厂商真机或完整发布矩阵。

## Gradle 执行权协调

已读取 catalog-filter 实施计划的分工和协调节。当前标题署名会话继续持有重型 Gradle，正在执行双端 UI focused 绿测，接着 scoped 格式、接线、Windows preview、Android candidate（交付）/debug（隔离测试）和隔离模拟器验收。最后构建完成后会在本节明确写“已交接”，此前请勿另起 Gradle。可接收 catalog-filter 计划登记的精确 focused 红测命令，在两个署名批次间串行运行并将 key/结果写回本节；不自动运行未登记测试。

署名交付前请保持其他 production 文件不再新增行为实现（红测和接口允许），避免体验包包含未验证的新产品行为；i18n 将在署名提交后释放。MangaLanguageDetector 和 domain/source/service 当前差异属于 catalog-filter，不由本会话暂存或提交。

Android交付入口校正：签名预检已通过；按仓库既有要求交付candidate而非开发APK，不扩大产品范围。预期增加R8/签名构建约5–15分钟，仍保持串行与focused范围，不执行完整发布矩阵。

已看到 catalog-result-domain/desktop/android 三条红测登记。当前仍在原生测试APK修正及双端交付打包，尚未交接；为避免红测后的新行为进入尚未冻结的体验包，安排在署名产物完成后释放执行权，由catalog会话按其计划执行三条已登记命令。没有执行过的catalog红测不记为已运行。

候选版本校正：当前工作树原75、本机主工作树77（只读核对）；首次release构建在发布前用协调器取消，未交付重复版本。按唯一版本文件分配78/0.19.4-aex.60，包名及证书不变，沿用候选构建/校验链，不运行全量测试。原生Debug验收仍绑定75的独立.dev包；release需独立核对安装启动。

原生视觉核查：Debug真实窗口，1080×2400浅色和900×2000深色；详情署名随宽度换行、对话框说明/预览/原始字段/书架整理/取消/保存可达。截图本机 `.tmp/title-attribution-native-detail.png`、`title-attribution-native-dialog.png`、`title-attribution-native-dark-dialog.png`，不作为正式发布像素一致性证据。

**Gradle 已交接（双端产物均已冻结，2026-10-12）**：`android-candidate` 的vc78签名候选、`title-attribution-windows-preview` 均成功。标题署名会话不会再启动Gradle；catalog会话可开始已登记红测及后续串行任务。i18n暂待标题署名最终提交（本节将标明提交后释放），请勿提前追加共享资源片段。当前余项仅安装最终APK、文档和显式文件提交。

## 已知运行边界与交接

- Android最终APK额外验收保留一项观察：首次带夹具冷启动进入CrashActivity，`SecurityException: Caller app.mihon.desktop.fork ... cannot post for pkg com.android.chrome`，栈为 `NotificationExtensions.cancelNotification → ExtensionManager.updatePendingUpdatesCount`。再冷启动可用并完成作者页路径，不能据此说异常已修复。`App.getPackageName()` 仍读取主线程Chromium栈，即便调用来自后台；这与观察相符，但本轮未独立复现旧基线、未确认根因，也未扩张修改公共WebView/通知机制。日志/界面树 `.tmp/title-release-crash.xml`、`.tmp/title-release-author.xml`，实际作者页截图 `.tmp/title-attribution-release-author.png`。因此本轮只交付预研体验，不宣称稳定发布就绪。
- Windows packaged runtime PASS 是既有扩展运行时验证；整窗人工交互保持NOT_RUN，作者控件的真实Compose事件通过。Android虚构夹具Debug完整写入路径通过，R8候选读取/导航通过且带上述启动风险；未操作用户实体设备。
- 提交仅包含本功能、必要测试、此计划/设计更新和Android候选版本78。语言/翻译组任务、其报告292eaf和所有catalog红测文件均排除。
- Gradle已经释放。共享i18n在包含本计划的 `feat(authors): preview source-specific title credits on Android and Windows` 提交成功后释放；另一会话可用 `git log -1 --format=%s -- docs/roadmap/2026-10-12-title-attribution-preview-roadmap.md` 核对后写入catalog_result_*，无需另一个纯状态提交或队列回执。
