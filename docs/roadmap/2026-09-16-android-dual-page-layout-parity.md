# Android 双页布局修复与跨端显示契约

- 状态：COMPLETE（布局与后续阅读行为修复完成，Android/Windows正式产物验收通过；macOS工具链限制见下文）
- active-task：无（全部完成）
- 范围：修复 Android 双页布局、章节落点、跨章配对稳定性和入场提示；共享两端章节入口/章界语义，修复 Desktop 已读重开末页问题。
- 本计划是独立执行计划；不推进其他 reader roadmap 或 parity manifest 能力状态；本批次源码移动所需的当前证据行号维护单独记录。

## 问题与证据

2026-09-16 三星 SM-S9280 实机，包名 `app.mihon.desktop.fork`，版本 `0.19.4-aex.1`，
安装 APK SHA-256 `12f83d907ac6866c9fc6a321025bb78cb4945b8e1077f0d0274b007bff74671d`，与 rc9 正式包相同。

- 大小不一致：1440×3120 视口，左图片视图 720×1024，右图片视图 1125×1600，右侧父容器仅宽 720。
- 顶部错位：两个图片视图均 1125×1600，顶部 y=0；左槽占宽 1125、右槽剩宽 315。
- `DualPagerPageHolder.setImage` 先 `onDimensionsDecoded` 布局，后创建当前 sub-holder；新建侧保留 WRAP_CONTENT。
- 布局在视口宽高为零时返回，之后没有测量完成/尺寸变化补算。顶部错位实测与该路径吻合，具体先后顺序由 DP-01 红测试验证。
- Android 现有 `DualPageViewerAdapterTest` 实测对象为纯 PairingState，不能覆盖真实 View 测量。

## 产品约定与复用边界

1. 双页使用完整阅读视口、固定物理左右半屏槽；默认 Fit 保留完整图片、保持宽高比、垂直居中、向书脊对齐。
2. 同宽高比页面显示尺寸一致；不同宽高比允许自然留白与尺寸差异，不通过拉伸/隐式裁切实现强制等高。
3. Loading/Ready/Error/Retry、左右加载先后、缓存命中和视口 resize 不改变物理槽位，不因内容就绪重置整组视图。
4. 双页手势使用一致的组级缩放/平移，不能由单侧图片私自缩放造成大小分裂；单页行为保留。
5. 复用 `ReaderPairingState`、`ReaderPageImageView`、Desktop `DualPageDisplayUnitFrame` 与既有测试工具；不另建 session、下载器或解码器。
6. Android View 与 Desktop Compose 是平台 adapter；不移植 Desktop UI，不把像素/窗口几何加入 ReaderSessionCore。共享验收向量/显示约定，分别验证真实 production renderer。
7. 保留 Desktop 封面槽、手动配对、宽图等既有能力；本轮不统一历史上明确允许的平台独有封面策略。

## 执行批次

### DP-01：稳定双页显示与跨端回归（一个内聚功能批次）

- [x] 先以真实 Android holder/image view 测量测试复现左右加载顺序、测量前就绪、晚到第二页与 resize；确认失败原因后实现固定槽位及正确图片对齐。
- [x] 覆盖双页缩放/重置、单页与错误/重试边界；只修复与本问题有关的生产接线。
- [x] 使用同组几何验收向量覆盖 Android 与 Desktop renderer，保护默认 Fit、左右物理槽和居中语义；不以源码扫描或复制 production 算法替代测试。
- [x] 独立审查真实 diff 与红绿证据，修复阻塞项；相关 focused、集成与格式检查通过。
- [x] 更新架构文档中的显示契约与测试边界，提交本批次实现、测试及状态。

实施子代理承担 Android production 与测试及必要 Desktop 回归测试；主代理负责接口约束、发布脚本与验收。
若超过 8 文件/400 行，以真实 View fixture、手势及跨端契约同属该行为说明内聚性，不机械拆分。

### DP-02：正式 Android 更新与实机验收（依赖 DP-01）

- [x] 沿用 fork 包名与原发布证书，增加版本号；同步 release identity 校验，完成受影响测试及正式 R8 APK 构建签名。
- [x] 保留用户数据原位升级已连接三星实机，核验 APK 哈希/证书/版本和真实 ReaderActivity。
- [x] 在原问题章节核验双页居中、等比例同尺寸、翻页/返回与旋转或等价视口变化；自动化补充可控图片与加载时序证据。
- [x] 记录真实结果、产物路径、限制；提交发布配置和最终验收状态。未通过不得勾选。

本轮默认不修改 Desktop production，因此不做无关桌面版本发布。若实施确需改变 Desktop production，按项目构建脚本完成 Windows 发布与运行验收并明确原因。

## 流程预算与验证

- 最多两个子代理：一个主要实施者、一个独立审查者；主代理不重复实施委派范围。
- 重型 Gradle 由一个协调者串行执行，使用 `scripts/gradle-coordinator.py`；外层超时先查状态。
- 红绿重构仅 focused tests；稳定批次运行相关 Android/跨端集成与格式检查；收口全量测试最多一轮。
- 独立审查一轮、必要修复复审一轮；预计 60–100 分钟，成本主要在测试、R8 构建和实机验收。
- 失败仅补验相关路径；依赖下载遵守代理规则。签名密钥缺失不得替换；设备断开保留构建证据并报告实机阻塞。
- 不清除应用数据、不触碰 `testfile/` 用户未跟踪内容、不改原图或阅读数据库模型。

## 执行证据

checkbox 仅在实现、审查、验证与提交全部完成时勾选。

- DP-01 最终有效 RED：`.gradle-coordinator/dp01-red-final-fixture.log`，`:app:testReleaseUnitTest --tests '*DualPagerPageHolderLayoutTest'` exit 1；恢复原 HEAD 的 holder 并使用修正 UI 线程后的 fixture，真实 SSIV 已成功绑定，首次测量前就绪/晚到图片/Ready collector/共享向量均出现 width=1440、期望720，双击整组 scale=1、期望2。仅替代 JVM 缺失的静态图片格式嗅探原生边界，保留真实尺寸解析、图片绑定和 View 测量。
- 子代理后台协调器启动被自动策略拒绝，未启动进程；改用前台 `foreground` 已成功，未提升权限。
- 早期 fixture 的 DI 缺失、原生格式嗅探缺失、Unconfined dispatcher 在无 Looper 线程创建 View，均不计产品失败。`dp01-red-layout`/`dp01-green-layout` 等早期结果被最终 corrected fixture 取代；新增断言确保正常 setImage 不产生意外错误，防止仅几何通过掩盖绑定错误。最终GREEN、跨端与边界回归证据见后文。
- 内聚性：预计 diff 超过 400 行，主要来自真实 View/事件分发测试夹具、Android/Desktop 共用几何向量及两端断言；production 限于 Android 双页 holder 的布局与组手势，不按测试类或行数拆分提交。
- 独立审查第一轮：CHANGES_REQUESTED，仅一项 P2——`onSizeChanged` 中读取尚未重新 layout 的 child 宽高作为缩放 pivot，放大后 resize 可能偏移。Android `dp01-green-ui-thread` 7/7、Desktop `dp01-desktop-fit` 7/7 经审查者核对 XML；此前测试缺少 zoom+resize 组合。原实施者补该 RED/修复，并补真实双指与 retry 按钮接线；预算内一次修复复审仅核对这些路径，预计额外 5–10 分钟。
- 修复复审：原 pivot 与连续 pinch 坐标反馈问题关闭，`dp01-green-resize-gesture` 9/9；但错误态全域放行触摸会让按钮外的存活 SSIV 再次独立缩放，列为新 P2。原 Retry 测试 `performClick()` 未覆盖事件路由。已要求真实 dispatch RED/修复；2026-09-16 向用户申请追加一次约5分钟定向复审，批准前不启动额外审查或正式发布。
- 最终错误态 RED：`dp01-red-error-touch` 记录存活 SSIV 接收4次触摸（期望0）、组双指不放大；改为仅可见按钮命中范围放行。Retry 使用真实 `dispatchTouchEvent` 坐标点击，经 loader→Ready 清理错误。
- 最终 focused：`dp01-final-related` Android 33/33（holder9、pairing12、adapter8、adjacent4）行为测试通过；该进程随后因测试排版失败，纯排版修复后 `dp01-final-format` 的 `:app:spotlessCheck` exit0。`dp01-desktop-fit` Desktop 10/10（identity7、layout3）通过，实际 renderer/pipeline 共用5组向量。`git diff --check` 通过。最后错误路由修复已由用户追加授权的定向复审确认PASS。
- DP-02 收口全量：`dp02-full-tests` 串行运行 `:app:testReleaseUnitTest :app-desktop:jvmTest spotlessCheck`，exit0，6m42s；Android 424 tests/0 failure/0 skip，Desktop 3070 tests/0 failure/2 skip（macOS JXA 与非release专属假设），即3068通过；全仓格式通过。本轮唯一全量已完成，不重复运行。
- 发布准备：`dp02-release-build` 按 `scripts/android-fork-release.init.gradle` 构建 code20/`0.19.4-aex.2`、R8开启的正式配置 APK，exit0，2m32s；R8有Android Window/jsoup缺失类告警，未调整忽略规则，未阻断构建。沿用原4096位发布密钥签名并验证证书，日志 `.gradle-coordinator/dp02-signing.log`。
- 已验收产物：[Mihon-Fork-0.19.4-aex.2-rc1-universal.apk](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/Mihon-Fork-0.19.4-aex.2-rc1-universal.apk)，SHA-256 `47133d98ae7cef00b94f35fd3e8d0d6f72405bcb4ce10b9241a03ba792b184fa`。包名 `app.mihon.desktop.fork`，versionCode20，versionName `0.19.4-aex.2`。产物构建源为 HEAD `4bea9ad34e` 加本任务当时未提交 diff；同一 production/test diff 已提交为 `9ade703ae`。审查后未更改功能代码，复用同一受测二进制，不重新构建。
- 追加定向复审：用户已明确同意；复用 `dual_layout_review` 确认PASS，错误态按钮矩形路由及真实dispatch测试有效，无剩余审查阻塞。未改动已通过全量与构建的代码，不重复全量；DP-01在本批次提交，DP-02继续原位升级与实机验收。


## 最终实机验收与交付

- DP-01提交：`9ade703ae`。DP-02发布脚本版本升级与本节验收随本批次提交；共两个内聚提交，无纯状态推进提交。
- 三星 SM-S9280 原位 `adb install -r` Success；核验已安装 `app.mihon.desktop.fork` 的 versionCode20/`0.19.4-aex.2`，设备 `base.apk` SHA与上述签名产物完全一致。未清除用户数据，书架原书与下载章节仍能打开。
- 原问题 Ch.3 双页：视口1440×3120，两侧各720×1024，y=1048，右页不再以1125×1600溢出半屏；[竖屏实机证据](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/portrait.png)。
- Ch.1翻页与双击：实际图片内容切换后仍居中等大；两页一起放大，再次双击恢复。证据：[整组放大](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/group-zoom.png)、[重置](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/group-reset.png)。连续双指与错误态Retry由真实dispatch自动化覆盖；不声称已在实机注入双指或网络故障。
- 横屏视口3120×1440：两侧图片均1012×1440，左右槽各1560宽，图片向中间书脊对齐；[横屏实机证据](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/landscape.png)。已返回竖屏，系统user_rotation/accelerometer_rotation及插电亮屏设置均恢复原值0。
- 功能边界：保持比例的Fit；同宽高比图片显示等大，不同宽高比允许自然留白与尺寸差异；Desktop production及平台独有封面策略未改。Desktop仅共用向量验证真实renderer，因此本轮没有无关桌面版本发布。
- 另行记录的导航观察：本次首次进入Ch.1/Ch.3时先显示章节过渡页，向章内返回后正常显示双页；尚未确定该现象产生时间或因果。该配对/章节落点问题不属于本轮固定槽位与组手势修复，不以本次几何PASS声称导航异常已修复；后续应独立定位，不在本任务无授权扩大实现范围。

## 用户实测续修：章节衔接与双页稳定性

用户追加授权：修复已读章节打开落到章尾、存在相邻章仍显示过渡页、双页阅读中意外变单页，以及进入阅读时的模式气泡。Android 向 Windows 的正确行为对齐，两端共有缺陷同时修复。

### DP-03：共享阅读行为与平台接线（依赖已完成 DP-01）

- [x] 核实两端入口、章节窗口、配对及提示的实际 production 链路，区分已证实根因与待复现现象。
- [x] 红绿测试覆盖已读章节从第一页打开、显式定位/未读续读边界；已有相邻章时直接衔接，真实边界及加载失败仍有可操作反馈。
- [x] 红绿测试覆盖连续翻页、异步尺寸与章节窗口变化时双页稳定；保留宽图及奇数尾页的合理单页，不意外修改用户模式。
- [x] 进入阅读不弹模式气泡；必要的设置入口与用户主动操作反馈保持可用。
- [x] 复用已有 shared reader entry/session/pairing 核心，平台只保留 View/Compose 适配；共享契约与两端 production wiring 均有行为测试。
- [x] 一轮独立审查、必要的一轮定向修复复审通过；更新架构边界。

### DP-04：跨端正式产物及验收（依赖 DP-03）

- [x] 串行完成一次 Android/Desktop 全量测试及格式检查；Windows 使用正式构建脚本与 Test Mode 验收，macOS 按环境可用性验证并记录真实限制。
- [x] Android 增加版本并沿用原签名，正式 APK 原位安装三星；检查用户三项复现路径及跨章前后双页状态。
- [x] Windows 检查已读章节入口、跨章与双页，记录实际发布 EXE；只提交本任务变化，保留用户文件。

复用决策：已有 domain reader 核心与两端 adapter 可继续使用，不创建第二套 session、配对算法、下载器或进度存储；新增公共规则必须被真实两端链路消费。

本轮预算：复用一个实施代理和一个独立审查代理，无冲突实现并行；主代理负责 roadmap、设备诊断和发布验收。红绿仅 focused，收口全量一次，审查一轮及必要修复复审一轮，预计45–90分钟。过程证据沿用协调器日志，本文件记录计划与结果，不另建逐任务报告。重型 Gradle 同一时刻只有一个协调者。构建/设备/远程环境失败先诊断已有进程，只有具体失败路径追加验证；扩大范围或超过审查预算先说明并等待用户决定。

### DP-03/04 证据与限制（已完成）

- 三星原版aex.2复现：从章节列表打开已读Ch.3，画面为“已读完Ch.3/下一章Ch.4”，底部页码却为1/9，同时出现模式Toast；证据 `.gradle-coordinator/dp03-read-reopen-before.png`。
- 有效RED：`dp03-red-pairing` 的真实holder/adapter测试确认整窗R2L顺序错误、刷新丢尺寸及配对偏移、不能按双页任一成员恢复位置。`dp03-green-pairing` 首簇相关测试通过，后续继续入口/提示/未知尺寸测试。
- 对齐事实：Desktop无相邻章才显示终点；Android原来还根据forceTransition、章节编号gap及加载状态插入终点文案。Desktop章节详情入口对已读章仍采用lastPageRead，需要一并修复。
- macOS环境预检：`mbp`超时，`mbp-lan`可连接；现有仓库有用户改动，未触碰。远端`java -version`明确报告无法找到Java Runtime，常用JDK目录未找到安装，本轮macOS构建目前缺少JDK，不能以Windows通过代替macOS构建证据。优先完成用户指定Android/Windows，不擅自安装远程工具链。
- 验收临时设置：三星插电亮屏从0改为3，结束时恢复0；未改变系统旋转设置或清除应用数据。
- 内聚性与风险：本批次超过8文件，涉及共享入口/章界规则、Android三种viewer及Desktop三种presentation的真实消费点，不能只修双页后让单页/Webtoon继续出现有邻章的终点页。作为一个阅读行为批次审查；重点验证显式同步定位、未读续读、真正终点、邻章加载失败与配对更新，避免按文件拆开核心和接线。
- 进一步RED：`dp03-red-entry-feedback`确认Android真实updateViewer自动Toast、未知尺寸没有预占双槽，以及Desktop已读章实际落page7而非0；`dp03-red-boundary-runtime`确认Loaded邻章仍受gap/force影响保留过渡项，且有邻章终点文案仍VISIBLE。早期`dp03-red-boundary`测试编译失败不作为产品RED。
- 一轮独立审查期间发现双页真实NEXT指令方向与整窗R2L顺序相反，以及单页过渡项Loaded后删除时需要显式首/末页anchor。均为本批次导航闭环，交原实施者补mounted/输入链测试；不以纯items列表或helper通过替代真实导航验收。
- 独立审查最终PASS：同一连续审查内关闭双页NEXT方向、普通Pager/Webtoon首末锚点、失效配对缓存，以及Activity恢复/同模式刷新Toast漏项；真实模式切换继续遵守提示偏好。没有开启额外审查轮次。
- 最终focused：`dp03-final-related` domain8、Android40、Desktop50，共98项全部通过、0失败/0跳过，包括原双页几何、真实输入、mounted跨章定位、同步恢复及架构守卫。该进程末尾ReaderActivity CRLF格式失败，纯换行修复后`dp03-final-format-check` PASS；`git diff --check` PASS，未重复无变化行为测试。
- 补充有效RED：`dp03-red-navigation`、`dp03-red-ready-anchor-fixture`、`dp03-red-final-wiring`。早期Compose宿主生命周期缺失及重复setup等待卡住属于fixture问题，已采线程栈、仅终止对应协调器进程树并修正，不算产品RED；后续真实mounted测试正确红绿。
- 全量收口已启动`dp04-full-tests`：domain JVM、Android release单测、Desktop JVM、全仓spotless，串行workers2/offline；最终结果与补验见下文。
- 全量结果：`dp04-full-tests`运行6m48s，domain473/0失败、Android438/0失败；Desktop3070/1失败/2跳过，唯一失败为ID22的当前源码证据行号漂移。仅校正manifest 8处当前roleEvidence行号，能力状态、符号和历史authority/provenance不变；其余272处当前引用静态核对一致。补验`dp04-manifest-recheck`，不重跑全量。该机械维护是本批次源码移动导致的必要证据修正，不推进其他reader计划。

- `dp04-manifest-recheck` PASS（1m6s），唯一契约失败已关闭，全仓spotless通过；合并有效证据为domain473、Android438、Desktop3068通过/2按环境跳过。DP-03实现、测试、架构文档及必要证据修正随本批次提交；DP-04继续正式产物验收。

### DP-04 正式产物与最终验收

- 实现提交`2fcbc9cb0`；发布配置、桌面版本分配及本节验收同属DP-04发布提交，不创建纯状态推进提交。源码行为自独立审查/全量完成后未修改。
- Android：`dp04-android-release` PASS，正式R8/resource shrinking构建2m17s；`dp04-signing.log`核验原证书、code21/`0.19.4-aex.3`，原位安装Success，设备base.apk SHA与受测签名包一致，未清除书架或下载数据。
- Android产物：[Mihon-Fork-0.19.4-aex.3-rc1-universal.apk](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.3-rc1/Mihon-Fork-0.19.4-aex.3-rc1-universal.apk)，SHA-256 `6c3634bba3266f1270d112d1cc22ba99c634b7ab80920433a49fa255cfef0ade`。
- 三星实测：已读Ch.3重开即1/9双页，无章尾文案和模式气泡；[首次打开](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.3-rc1/acceptance/read-chapter-first-page.png)。连续真实左侧点击遍历Ch.3，9/9为正常奇数尾页，下一次直接进入Ch.4的1/9，继续3/9仍双页；[下一章首页](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.3-rc1/acceptance/forward-5.png)、[下一章继续双页](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.3-rc1/acceptance/forward-6.png)。
- 反向实测：右侧点击从Ch.4返回Ch.3末页，再回7/9双页，不出现有邻章的过渡提示；[返回前章双页](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.3-rc1/acceptance/backward-3.png)。退出再打开Ch.3仍从1/9开始，无自动气泡；[再次重开](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.3-rc1/acceptance/reopen-after-cross-chapter.png)。插电亮屏恢复原值0，旋转设置未修改。
- Windows：`dp04-windows-build`通过`scripts/build-desktop.sh build-only`，使用同一已通过全量+契约补验的代码，未重复全量测试。构建、正式发布运行时扩展验收、未打包发布与ZIP打包均PASS（1m38s）。最终EXE已核验存在：[Mihon Desktop.exe](<D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.39.2fcbc9c-unpacked/Mihon Desktop.exe>)；ZIP SHA-256 `f10c42875460a9644e759ef632a422d6e6c4e8d63d5fa6d8d410c0982697941b`。
- Windows最终EXE另以独立profile启动Test Mode，通过production downloaded-directory route实际打开12页章节，收到真实OPEN_PAGE/DECODE/FIRST_PAGE_PRESENTED；向该实例原生窗口投递实际方向键，呈现记录推进至page5；关闭后productionClosed=true，再通过shutdown正常退出。证据`.gradle-coordinator/dp04-windows-reader-state.json`、`dp04-windows-after-navigation.json`、`dp04-windows-reader-closed.json`。未触碰用户原有运行实例。
- Windows Test Mode夹具显式`isDualPage=false`，因此上述运行时证据仅覆盖单页打开/输入/关闭，不能因相邻页呈现事件误称双页运行时验收；双页、已读入口和跨章语义以真实production/mounted自动化证据为准，Android补实机跨章。没有新增测试专用产品接口。
- 边界：未读章节保留进度，显式同步/恢复定位优先；有邻章时直连，等待与失败仍有加载/重试反馈；宽图与奇数尾页可合法单页，模式不会因异步尺寸或邻章窗口更新意外退化。Desktop既有独立封面策略保持。macOS因远端缺少JDK未构建，不宣称本轮macOS产物通过。


## 2026-09-17 Android 默认自适应与配对定位对齐

本批次延续 Windows 已验收的逻辑首张语义。Android 的 `DisplayPage.Double.firstPage` 原本已经是右页，
本轮修复的是重配对定位、模式切换恢复点与进度回写边界，不把它描述成桌面 `max` 取页错误。

- 设置 → 阅读器 → 默认阅读模式 → 默认：按实际阅读容器（扣除固定 inset）的比例自动选择 RTL 单页或双页。
  新安装全局值为 0；已保存的 RTL、LTR、双页等显式值保持原样。漫画的 0 仍表示“跟随全局设置”，
  因此全局为手动模式时，漫画选择默认不会强制自动。阅读器设置明确显示继承关系；自动模式显示“默认 · 单页／双页”与说明。
- 复用 domain `AdaptiveReaderLayout`：首次有效尺寸 ≥1.35 为双页，否则单页；后续 ≥1.35 进入、≤1.25 退出，
  目标连续稳定 150ms，持续拉伸到同一目标不重置计时。无效尺寸、离开自动模式或 Activity 销毁取消待切换；
  工具栏、设置面板等 overlay 不改变阅读容器。旋转、分屏和窗口拉伸都通过实际布局尺寸触发。
- Android 本轮才将竖向封面对齐为左半槽唯一页；真实横向封面／跨页图仍占全宽，其他唯一末页保持原有布局。
  双页当前页是 RTL 右页，回单页显示该页。重配对同时决定边界和目标，例如 [1,2]→[2,3]→[1,2]；
  章节尾部不足两张仍可合法单页。domain `adjustReaderPairing` 供两端共用，Desktop 仅等价委托，无新增 Windows 产物。
- 同章布局重排只更新可见位置，不写阅读进度、完成状态或触发下载；真实翻页仍走既有 shared progress 链。
  当前位置从异步进度持久化中分离，过期写入不再覆盖新布局位置。未获激活的邻章不能改 requestedPage；
  ChapterTransition 加载完成进入真实邻章属于导航，仍经过既有激活与 settlement arbiter。
- 尺寸事实、手动配对边界和当前页由已有 ReaderViewModel 保留，Activity／viewer 重建后复用；
  页面列表身份改变或离开章节窗口会清理对应配对状态。不保证手动配对边界跨进程终止／重新打开持久化。
  Android 本轮不改 Desktop bit34 协议；既有高位标志保持，Android 漫画默认继续按原低位继承解释。

自动化覆盖：真实 ReaderActivity 更新入口与容器 resize、两个 Activity 实例复用同一 ViewModel 的定位和配对、
全局设置动态生效、真实 holder 封面物理槽、连续配对与邻章恢复、progress 数据链及迟到激活竞态；
共享策略与 Desktop 配对挂载测试保护等价委托。

### 本批次验证与交付

- 内聚性：变更超过 8 文件／400 行，因为共享规则、Android View／Compose 设置入口、Activity 生命周期、
  ViewModel 当前位置与进度、真实挂载测试必须一起交付。Desktop 只将已验收的纯策略等价委托到共享层。
- 红证据：`android-default-red`、`android-default-wiring-red`；最终相关回归 `android-default-related`
  为 domain 44、Android 67、Desktop 54 项全部通过。独立检查关闭了迟到邻章激活写入及过渡页导航被布局保护吞掉的边界。
- `android-default-full`：domain 477、Android 447 项完整测试全部通过、零跳过，全仓 `spotlessCheck` 通过（4m7s）。
  本轮只运行一次完整测试；上述 focused 验证与失败夹具修正不作为完整测试重复。
- `android-default-release`：正式 R8／资源压缩构建通过（3m46s），沿用既有可选 Window 扩展／jsoup 缺失类告警，未新增忽略规则。
  `android-default-signing.log` 验证原发布证书、包名 `app.mihon.desktop.fork`、versionCode 22／`0.19.4-aex.4`。
- [正式 APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.4-rc1/Mihon-Fork-0.19.4-aex.4-rc1-universal.apk)，
  SHA-256 `b93b2856753f7b4083f1b0449b260290630a665b5f2362a16c1fa026be41c4b7`。
  API 36 模拟器 `mihon-aex-api36` 原位升级成功，已安装 base.apk 哈希相同，原书架与下载章节保留。
- 正式运行验收：九页下载章节竖屏单页，旋转后首页独占左槽；普通双页显示页 2／3、当前页 2，返回竖屏仍显示右页 2。
  [横屏双页](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.4-rc1/acceptance/landscape-pair.png)、
  [返回单页](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.4-rc1/acceptance/portrait-right-page.png)。
  调整后显示页 3／4；再连续点击七次恢复页 2／3，无持续翻页，之后正常翻至页 4。
- 同为横向的显示尺寸 1800×1200 显示“默认 · 双页”，1400×1200 显示“默认 · 单页”，当前页保持 4：
  [宽比例](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.4-rc1/acceptance/resize-wide.png)、
  [窄比例](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.4-rc1/acceptance/resize-narrow.png)。
  这是真实 APK 显示尺寸变化验收，不冒称 OEM 分屏／自由窗口实测；这些入口的尺寸监听由真实容器挂载测试覆盖。
  验收后恢复模拟器 1080×1920、自动旋转 1／user_rotation 0。无真机连接，真机及厂商窗口体验留作用户验收。
