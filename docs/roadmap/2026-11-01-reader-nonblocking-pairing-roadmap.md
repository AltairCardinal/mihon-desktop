---
status: in_progress
date: 2026-11-01
---

# 双页阅读非阻塞配对迭代 roadmap

## 1. 新会话入口

唯一功能规格：[双页阅读非阻塞配对设计](../2026-11-01-reader-nonblocking-pairing-design.md)。目标是未下载长章冷跳时先显示，后台匹配在下一次主动翻页纠正，允许重复边界页而不自动漏页。

**当前结论（2026-11-02）：全模式恢复尚未完成，下一任务为 NR01。** NB01～NB04及 A／B 的既有双页证据、Windows 图片重试人工验收保留；`0f9245e64d0fd2548fa7f0466dc5e09d1e6e0548` 又关闭两个 Android 特定时序，但复审发现“先单页恢复失败、随后切双页”的无效重试，以及单页仍保留配对二选一的设计错误。Desktop 非双页也仍有配对 loading/error 内容门禁。用户现已明确所有模式均应继续阅读、原记录受保护、仅设置内可选重读；以设计第 4.6 节和下面 NR01～NR03 为当前规格。本次仅文档设计，三个任务均未实施／验收，不把历史勾选视为新目标完成。未推送／发布。

本文件是这次独立修复的执行计划，不启动旧双页 roadmap 的 DP02 完整发布矩阵，不重做已完成历史批次。与旧文档冲突时，采用设计第 3 节的有限替代；未列出的身份、资源、持久化、原生和验证约束继续遵守。按第一个未勾选项推导当前进度，不另建 `active-task` 状态或第二套 manifest。

### 接手时只核对这些事实

1. 读取仓库 `AGENTS.md` 及动作命中的指南；UI／架构、验证、进度交接是必读入口。先报告实际流程预算。
2. 核对 HEAD、工作区、构建协调器及任务进程。规划起点 `5e04aa34be6f64a6765e95a928c96588b9a6cb58`；因主目录发生并发提交／暂存，本次文档在基线 `e29dbd5946a42b48819137a346064b86fc1869fb` 的独立 worktree 完成。起点已有 `AppVersion.kt` 修改和 `.codex-remote-attachments/`，不是本任务内容，不回滚、不暂存。不能假定这些状态在新会话仍相同。
3. 若相关源码变化，只检查受影响差异，不重做全仓库调查。原诊断 2 项测试只证明旧门禁，不是本次实现的通过证据。
4. 确认源页编号：验收第 126 页等于 index 125。按设计处理“同组未绘伙伴一次精确激活后可主动离开”，不得为了防漏页引入无限重复。
5. NB01～NB04与A／B原定选定行为的有效证据按影响复用；下一会话从第一个未勾选的 NR 任务接续，不重启历史批次。会话切换不重置预算、不授权全量验证；代理与写入权按当时规则及明确范围分配，两端不能另写配对规则。

### 2026-11-02 当前待办：全模式非阻塞恢复与 AUTO 后切换修复

工作目录继续使用 `D:/Codex/worktrees/reader-nonblocking-pairing-integration-20261101`，分支 `codex/reader-nonblocking-pairing-integration`。设计核对的 production 基线为 `0f9245e64d0fd2548fa7f0466dc5e09d1e6e0548`；实施前核对实际 HEAD、未提交差异和进程，保护其它任务改动。下文旧阶段的单页例外、冻结范围及“已收口”仅表示历史阶段，不能覆盖当前待办。当前用户请求只授权本轮设计落文档，不在该文档任务中自动启动实现或持续 Goal。

已证实的是静态生产调用链：Android 单页失败后 `Dialog.PairingRestoreError` 与未激活章节共存，切为双页后 `retryPairingRestore` 因无当前章返回；Desktop 非双页 `readerViewportBody` 用配对 loading/error 替换内容。尚无这两个新目标的运行失败证据，须按下面任务先取得真实红测。原两个 Android 时序的 2 RED、7 项定向绿及最终 2 项补验仍有效，不能冒充本轮新红绿。

- [ ] **NR01：Android 章节激活独立于配对恢复，统一设置重读入口。**

  **责任与边界：** 复用 `ReaderViewModel`、`AndroidChapterPairingCoordinator`、`ReaderActivity` 和 `ReaderSettingsDialog.ReadingModePage`。成功页列表／有效内容身份可正常激活，恢复走现有后台链；配对挂起或失败不再等待激活、生成恢复弹窗或要求使用默认。保留实际页列表错误、图片错误、取消、进度会话与内容身份复核。设置入口移出实际双页条件，当前有效失败 owner 才可重读；各模式用同一当前目标链，去重在途读取，不重激活章节、不依赖旧 dialog／全局目标。移除二选一展示及无调用的旧 UI 状态，保留被其它合法路径使用的数据语义。无需修改配对算法、数据库、点击导航或其它设置。

  **先红后绿：** 在 `AndroidChapterPairingPersistenceWiringTest` 与现有 Activity／viewer 接线夹具中，覆盖 NR-A～NR-D 的 Android 部分：单页读取挂起即可激活、失败仍可读；AUTO“先切双页再失败”和“先失败再切双页”两个精确顺序；真实 Settings 重读在单页／条漫／双页命中当前章，重复点击去重，重读中切模式／换章后旧结果无效。沿 production 模式变化入口及实际按钮语义，不能只设置最终状态。挑选现有单页／条漫真实 holder 用例补必要显示断言；未改已绘帧锁可复用，adapter/Ready 不能冒充新像素证据。已有断言若要求“单页失败则 viewerChapters 为空／出现默认选择”，按新契约替换，先证明旧实现失败，不继续维护相反产品要求。

  **逐任务收口：** 同一精确方法红绿、直接受影响的未知 revision 禁写／已知约束与 CAS／邻章及旧回调回归、改动文件格式检查。核对恢复成功后真实 viewer 重绑与下一源页可达；记录真实命令、非零用例及失败停点。测试及必要内部文档随实现提交，不执行整个测试类、全模块或原生矩阵。

- [ ] **NR02：Desktop 非双页内容解除配对门禁，设置入口与 Android 一致。**

  **责任与边界：** `DesktopReaderScreen.readerViewportBody` 保留真实章节加载／错误分支，对已加载内容不再按配对 loading/error 返回全屏门禁；`ReaderSettingsPanel` 中恢复入口独立于实际双页／pager 条件。核对 `ReaderScreenModel` 的现有 coordinator 和当前 owner 重读接线，若已满足则复用，只修改真实缺口。清理二选一生产 UI，不自动触发 `useDefault`；单页／条漫仍按自身布局显示。未改的真实在线图片 Retry、算法和 Windows 人工路径不重新实施。

  **先红后绿：** 复用已有真实 SQL／coordinator／完整 ReaderViewport 的测试：非双页持有记录读取时目标图片仍实际显示，失败后保持，自动／手动转双页仍显示包含当前锚点的临时组；主动设置中仅一个可选重读，实际点击产生当前章读取，请求中去重，失败后仍可读，成功不移动已绘图像。单页及条漫用受影响分支的最少代表向量，沿实际模式切换入口，不复制配对实现。

  **逐任务收口：** 复验上述精确方法及被改接线直接影响的记录禁写／帧稳定测试、改动文件格式；此前双页和图片 Retry 证据按源码差异复用。NR02可沿用NR01已固定的语义和验收，不引入共享新引擎或格式。不得为了勾选新增 Windows 候选包或重跑人工图片重试。

- [ ] **NR03：一次集中实现审查与证据收口。**

  NR01／NR02稳定后做一轮独立审查，聚焦全模式激活、AUTO两种顺序、当前owner重读、旧回调和未知记录保护；有问题只修复并补验受影响范围。核对设计 NR-A～NR-D 到真实方法／结果的映射，直接读取产物证明先红后绿，明确 JVM、Compose、像素及原生证据各自边界。文档与对应实现批次一并提交；不为关闭状态另建纯记录提交。检查工作区仅含本任务预期变更，报告本地提交、实际测试及未验证项，不推送／打包／发布、不扩大公共功能说明。

  **预算与停止条件：** 后续执行者按当时进程和依赖报告预算；默认按两端上述 focused 范围及一轮审查完成，不重跑 NB01～NB04、Windows 图片重试、全模块或全量。编译／夹具错误单独记录，产品红必须来自行为断言。仅当具体原生接线风险无法被现有有效证据和本次接线测试覆盖时，明确缺口后补最小原生验证；不把全新设备像素验收列为本轮默认条件。产品失败、证据缺失或未执行项保留未勾选，不以设计提交作为实现完成。

### 2026-11-02 冻结的剩余工作与交接

当前实现提交为 `63cef8f1ccf891b28659e294555c8d1a296d57df`，worktree 为 `D:/Codex/worktrees/reader-nonblocking-pairing-integration-20261101`，分支 `codex/reader-nonblocking-pairing-integration`。本次文档纠偏另作提交，不改写实现历史、不推送。NB04未完成，以下均为待实施／待验收契约，不是已修复承诺。

- [x] **A：双端恢复失败也必须非阻塞。** 正常打开、恢复、冷跳、翻页及后台配对／手动记录恢复失败时，自动保留可用画面并按临时规则继续；禁止自动中央浮层、模态弹窗或要求先选“本次默认”。不能只修改fixture掩盖production失败UI。保留未知／失败状态、已知手动约束及存储记录，不把失败当空记录或READY，不自动写默认覆盖旧数据。恢复操作仅在用户主动进入已有双页调整／阅读设置界面时可见，且仅保留一个可选的“重新读取双页调整”操作。正常阅读及主动打开设置都不得出现旧错误标题和“重试／本次默认”二选一错误块，不触发 useDefault，不另造提示产品。晚到成功只影响之后主动翻页，保持当前帧且不漏页。

  **先红后绿与最小范围：** Desktop执行真实恢复coordinator＋完整viewport，Android覆盖实际VM→Activity对话框接线；先证明现实现会自动弹层而违反契约，再最小修复，复验这些用例及直接影响的持久化安全契约。源码入口：Desktop `DesktopReaderScreen.kt` 的dual＋`PairingLoad.ERROR`→`PairingRestoreError`（旧 `dec006e7d37` 分支，本轮漏清理）；Android `ReaderViewModel.kt` 后台恢复失败→`Dialog.PairingRestoreError`，`ReaderActivity.kt`不可dismiss的AlertDialog。不能仅测共享helper或扫描符号。第二章节恢复失败原因尚未知，只沿当前fixture→真实持久化load路径有限追踪，不猜用户记录损坏。

- [x] **B：独立完成真实在线图片Retry闭环。** 配对恢复正常，准确同章／同owner；使用现有真实ONLINE source→SourcePageFetcher→完整ReaderViewport→准确失败页按钮，证明新attempt→新读取→真实解码／像素恢复、错误消失、正常伙伴和当前帧稳定。补真实源夹具的接线缺口，不复制另一种成功源，不用replacePage／强设Ready或只读计数代替。A的真实恢复失败场景独立验证，两个“重试”不得混入同一人工验收页。

  **环境与停止条件：** 一次原生验证前完成离线准备。RESTORE_FAILED、错误按钮未实际确认、捕获失败或跨章节拼接任一出现，都标记准备未完成，不交付用户猜测。失败后记录准确链路停点及新增信息再决定下一步，不无新证据重复点击／重建；不再笼统归因鼠标没点中、泛查F11或重跑已绿矩阵。准备合格后遵守用户要求暂停，由用户人工验收后再启动。

**证据纠偏与复用：** `manual-acceptance-01` 人工点击已使目标两层attempt达到7，证明重试触发；真实ONLINE地址强刷缺陷已修复，`.gradle-coordinator/nb04-testmode-online-refresh-artifacts/{red,http-green,controller-green}.xml`记录有效红绿及4项通过。`.gradle-coordinator/nb04-desktop-retry-artifacts/full-viewport-green.xml`证明既有完整viewport按钮链，但所用ReadyWindowSource不能覆盖真实ReaderTestModeOnlineSource，不能单独关闭B。Android `.gradle-coordinator/android-native/attempt-09/` 双向完整路径及Windows `attempt-12-stage-snapshots/`、`attempt-13-phase-retry/` 首绘／画面保持／纠正证据按未受影响差异复用；不重跑共享、双端纠正、进度等已绿范围。

**不合格的第二次人工场景：** `.gradle-coordinator/windows-native/manual-acceptance-02/pre-human-state.json`为章节991200023、READY；`prepared-state.json`及`handoff-state.json`为另一个章节991200025、RESTORE_FAILED。不得拼成同章的一次状态转换。后者encoded/model/runtime Ready与配对RESTORE_FAILED属于不同状态域，可以并存，Ready也不证明解码／实际绘出成功。handoff已记录RESTORE_FAILED、最后捕获无帧仍交付用户，是准备失败；用户截图进一步确认中央恢复浮层，不能算B验收通过。具体恢复失败原因仍未知。

**停止交接：** 本轮不启动应用、桌面操作、构建或产品测试，不改产品／测试代码，不创建报告体系或新会话。只修订设计与本roadmap，做一次文档只读核验及`git diff --check`，提交这两份文档后停止并归还写入权。后续先核对精确HEAD、dirty与任务进程；原manual02 launcher3920／runtime58328已由监督会话核实退出，不重新启动旧场景。产品实施／持续goal维持暂停；新会话不重置既有成本或验证授权。

### 2026-11-02 A／B 授权续作

用户已明确创建并执行新的持久 goal，接续上述冻结工作；上一轮停止只描述历史，不禁止本次实施。起点 HEAD `8288598486d3eaf53de64759c68d2f7b8711d62f`，分支及 worktree 与冻结交接一致，工作区干净，未发现遗留 Gradle／应用／任务脚本进程。主代理负责 B、整合及独立审查，1 个实施代理负责 A 双端和定向 ART 用例；Gradle 执行权串行移交。仅 focused 红绿、直接集成、改动文件格式及本次候选预检，不运行完整模块／全量／发布矩阵。NB04与 A／B 复选框继续等待全部必需证据及人工结果。

有界原因核对：只读打开原 manual02 的 `profile/home/AppData/Roaming/Mihon/mihon.db`，查询章节 `991200023`／`991200025`，仅存在 `(991200023, manga_id=991200022)`；后者不存在。`read_chapter` 的 fixture 只准备内容、不创建章节，真实 `ChapterPairingStorage.loadChapterPairing` 首先校验章节归属，否则抛出 `MissingChapterPairingIdentityException`。因此原第二场景缺少其准确数据库章节身份；没有用户手动记录损坏的证据。修复 A 仍覆盖真实恢复失败，不以补齐 B 夹具替代 production 修复。

首批离线证据：`nb-ab-desktop-red-and-online` 实际执行2个方法。Desktop真实SQL/coordinator→完整screen的 A 方法准确红于自动出现恢复控件；B真实ONLINE源→新attempt→HTTP读取→实际解码/恢复像素→错误消失及伙伴几何/物理frame均已经过，最后过严的共享session对象恒等断言失败。共享内容rebind会合法替换session，该断言已改为保持同一真实内容owner；此项是测试契约校正，不能宣称新增产品红或 B 通过。Android `nb-a-android-modal-red` 的1个方法实际执行3次（含项目失败重试），均红于后台恢复失败自动设置 `Dialog.PairingRestoreError`。两个平台的产品红均已取得；编译/命令准备失败不计产品红。日志与 XML 仍保存在现有 `.gradle-coordinator`，不建立新报告体系。

`nb-ab-desktop-focused-green-import-fixed` 的4个方法全部通过，0 failure/error/skip：真实SQL完整screen的失败→无自动层→主动Settings→Retry／session-only Default（历史二选一实现，已被用户拒绝，不能计作当前入口验收），held晚成功稳定frame／mount／像素及下一次真实tap不漏页，原未知revision持久安全，以及 B 的真实ONLINE source→完整viewport→实际Press跨帧Release→新attempt／读取／Skia像素恢复。B同时核对正常配对恢复、准确章节、同一内容owner、错误消失、伙伴bounds及物理frame保持。最终XML归档于 `.gradle-coordinator/nb-a-artifacts/`；此为自动化接线证据，不能替代候选窗口的人工结果。首轮修复验证因缺少 `fillMaxWidth` import 停在编译，未执行测试，之后仅修正该导入并运行同一范围。

本批11个 Kotlin 文件共同覆盖双端设置入口／Activity接线、恢复调度与真实SQL／HTTP／像素回归，超过8文件的原因是同一跨端用户行为的必要接线；没有修改共享算法、schema、普通图片重试实现或资源预算。原生测试只补恢复失败用户路径，既有首绘／纠正矩阵按影响复用。初次格式 `nb-ab-format` 的11目标正常Apply／Check通过；后续窄修正仍须最终限定格式。独立审查已要求复用Android原设置ScreenModel，并撤回无关的single弹窗dismiss变化。

Android focused 收口为7个独立方法的组合证据，不称一次最终7项全绿。原single读取失败和取消恢复2项通过；随后held读取与已知手动边界→CAS冲突→恢复读取失败2项通过，后者验证旧约束、外部SQL记录及继续导航；Settings剩余3项在 `nb-a-android-settings-three-scope-fixed` 通过。Robolectric原触屏／滚动动作停在Compose等待空闲或未触发恢复，不据此推断产品按钮无响应；改用真实按钮语义动作，同时断言真实load次数递增且命中准确章节，原生可见操作另验。两个后续设置模型未发布viewer的夹具问题通过复用既有 `ScreenModelTestHost` 修正，未改产品。`nb-a-android-settings-default-focused` 又仅复验其中1项，真实Settings内Default→DEFAULT_UNVERIFIED／revision未知／viewerChapters同引用／源页不变／零新增读取／SQL不写／调整disabled→同界面Retry恢复全部通过，1 unique／1 execution，0 failure/error/skip。XML在 `.gradle-coordinator/nb-a-artifacts/`。

本轮最终Kotlin格式为初次11文件，以及后续实际变化的2个androidTest文件、1个单元测试文件与最后1个原生方法文件的正常限定Apply／Check；最后原生构建 key `nb-a-native-compose-clock-fixed` 通过5m21s。只编译测试APK，不因夹具修正重复构建未变化的宿主。Debug宿主由项目入口生成于 `app/artifacts/android/0.19.4-aex.115-vc133-8288598486-debug-f249bef1965d/`，APK SHA256 `b598fece4af28fc75b6c6edfbfbf7398964edf2d9130b276b8d272c1ff208260`；源码 `8288598` 加本任务差异，productionInputs SHA256 `daec7916d7a557dac311ad65a70fd3dcef7ff42595d009e9f6b72cc6f9539be6`。主代理核当前4个Android产品差异文件与宿主清单一致、模拟器安装APK哈希一致。最终测试APK SHA256 `43ebb066510d968b86040c96f99ad6a70d344c8757ccde7277b4b887dd9acf3e`；双方dev包／runner／签名一致，不冒称Release、真机或发布验收。

Android新增原生路径已经通过：API36任务专属 `mihon-nonblocking-20261101`／`emulator-5580`，仅运行 `failedRestoreKeepsPixelsUntilUserOpensSettingsAndRetries[mode-6]`、`[mode-8]` 两个参数实例，前者另含独立AUTO7场景；2 tests／0 failure/error/skip，共3场景。实际VM coordinator恒等及真实SQL load身份、失败状态与unknown revision、JNI／PixelCopy真实目标、用户主动打开设置、6次可见Accessibility Settings／Retry操作及有效bounds、恢复后物理frame／原View挂载／像素几何保持、下一次真实按键后首个未绘候选获得真实绘出回执、原存储记录不变均经过。这里的点击明确为可见原生Accessibility动作，不称触屏或人工观察。AUTO未强设automaticDualPage，实际AdaptiveReaderViewport选择双页的断言已通过。

原生过程停点与纠正保留在 `.gradle-coordinator/android-native/restore-20261102/`：初次base方法名返回0 tests，核本机runner/JUnit字节码后改为带自定义参数后缀的精确过滤；attempt-01没有把故障送到真实owner，改为已验证的addFactory并核实际VM身份，未改生产读取；attempt-02未确认Settings节点，不推断翻译／输入未命中；attempt-03的有限诊断和自有截图证明menuVisible、locale、焦点、root身份均正确但Compose菜单未绘出，对照既有feedbackSnapshot发现新增路径漏推进受测试规则控制的Compose时钟。复用原waitForIdle／clearCache／root.refresh后，attempt-05-green在38.15s完成上述3场景。attempt-04-startup在执行测试前出现Chrome通知身份SecurityException，0 tests；只读发现App包名伪装读取主线程栈的相关路径，但未证实唯一原因，不归因配对、不扩大修复；保留后仅一次独立进程再启动取得有效原生结果。启动期短暂offline／keyguard过渡待自然结束再验，未输入凭证或改变屏幕超时。一次AVD与编译重叠导致可用内存下降后，精确关闭自有AVD并严格串行构建与设备运行；本轮实际AVD已正常关停，后续Windows预览不与其争用。没有重复旧coldSeek矩阵、完整模块、全量或发布矩阵。

### 单一可选恢复入口纠偏及限定收尾

用户先暂停原 goal，随后明确本轮只收尾已开始的局部源码纠偏，接续已有 focused 复验／格式并提交，汇报后停止；虽将 goal 状态重新设为 active，也不授权恢复整个 A／B、候选、桌面操作或完整验收推进。此前将旧错误标题与 Retry／Default 二选一移入设置的实现被用户明确拒绝；上述旧 focused／Android 原生证据只按未受影响的数据保护、帧及导航行为复用，不代表新的单一入口已验收。源码要求以本节及设计第4.5节为准。

Windows旧 preview `0.11.24.6.8288598`（目录 `20261101T184547Z-2caf09ed`）含被拒绝的二选一界面，不作为修正后的候选交付。manual-acceptance-03 曾实际显示 A 场景章节 `991200035` 的双图及 RESTORE_FAILED，无自动中央框；后续未确认恢复按钮，也未开始 B 图片重试场景。最后一次标题为“验证主动恢复失败仍留在用户打开的设置中”的点击使用滚动未停稳前的坐标 `(505,591)`，实际误改导航布局；该点击不是需求测试或恢复操作成功证据。

只读核对隔离 profile03 的 `reader_navigation_mode=L` 已持久化；干净 profile02 无该键，production 默认及误点前截图均为“左右”。不改回证据 profile、不复用其偏好；之后如获候选授权，使用新隔离 profile 并确认导航仍为“左右”。未操作日常配置。核实 profile、EXE、PID及端口归属后仅向本任务实例发出一次正常 shutdown：launcher29976／runtime24020 已退出，49211／49212 已释放；session.json 记录 `INVALIDATED_BY_MISCLICK_AND_REJECTED_RECOVERY_UI`。暂停后未再发送原生输入或采集。

本轮复用唯一实施代理和串行 Gradle 权，只运行 Desktop 真实 coordinator／完整 Settings 与 Android VM→Activity Settings 两个 selector 的 RED→最小实现→GREEN，以及受影响文件格式。B 的真实 ONLINE→完整 viewport 自动化绿与未受影响的数据保护证据复用，不重跑已绿范围。`reader-optional-reload-ui-red` 双端各1项执行／1 failure／0 error／0 skip，分别准确停在完整 Settings 仍存在旧标题／二选一的断言。最小实现只把双端 Settings 控件换为单一资源按钮，沿用原同章恢复方法，不调用 useDefault。`reader-optional-reload-ui-green` 的 Android 同一方法1项执行／0 failure／0 error／0 skip；Desktop 首次复验停在测试节点查找的 NoSuchElementException；字节码行号及谓词确认缺的是 Reload 节点，不能据此认定工具栏隐藏。测试只等待 showSettings 模型状态，尚未等待真实 Settings 重组；恢复等待实际 Reload 控件后，`reader-optional-reload-desktop-ui-sync-green-format-fixed` 同一方法1项执行／0 failure／0 error／0 skip，通过7.078秒（含编译任务4m9s）。日志明确记录重组前只有阅读器节点，重组后出现完整设置及 Reload；不是产品恢复控制器修复。途中一次新增诊断行超过120字符，仅格式失败、测试未执行；拆行后只复验该 Desktop selector 和文件格式，未重跑已绿 Android。单一入口的新候选和原生／人工验收均未运行；Android 原生测试标签仅机械同步，未重新编译／运行，不能称新入口原生通过。本次6个受影响 Kotlin 文件的限定 Spotless Apply／Check 通过，之后仅变更的 Desktop 测试文件也通过限定格式；base／简中 XML 解析及资源唯一性通过，最终 diff 空白检查通过。双端新入口的源码纠偏与 focused 验证已收口；B 真实 ONLINE 自动化及其他未受影响数据保护绿继续复用。以上是组合定向证据，不称完整模块或全量重验。按用户本轮要求提交后停止，不构建候选、不操作桌面或导航偏好、不推送／发布；A／B及NB04保持未完成，原 goal 在收尾后暂停，B人工环境仍为 NOT_PREPARED。

### 2026-11-02 再授权后的当前候选验收

用户已一次性恢复原 A／B 续作并撤销上轮局部收尾停止限制；此前暂停及不构建候选均为历史停点，不阻止本轮已授权工作。复用 `a623b488800e867707949b66643294c3664e8c0c` 的双端 focused GREEN、B真实ONLINE像素闭环及未受影响数据保护，不重跑已绿矩阵／全量／发布。仍只保留单一可选重读，A恢复失败与B图片重试分场景；最终人工窗口就绪后停止自动输入并暂停等待用户一次验收。

Android补充只在原ART方法添加实际Settings内旧错误标题／Retry／Default不存在及Reload唯一的断言；production未再修改。限定测试APK格式及编译通过（`nb-a-optional-reload-native-apk-import-fixed`）；初次失败是多余的assertDoesNotExist导入无法解析，未执行产品测试，去掉导入后同范围编译通过，不计产品RED。当前Debug宿主经项目入口生成于 `app/artifacts/android/0.19.4-aex.115-vc133-a623b48880-debug-a20f68967f25/`，APK SHA256 `6a2bf68454b6519e96f0435a25f58eae762a230a7bc69efc0b11a5fafdb802d3`；测试APK SHA256 `327f93f410992d4969bb85219c6f009e3a6eb1c2e074a50e28c24b9da0debf46`，双方dev目标与证书一致。宿主生产实现与a623b488一致，未提交差异为新增原生断言。

原无窗口专属AVD/API36上仅执行原精确mode6与mode8两实例一次，`optional-reload-20261102/instrumentation-result.json`为PASSED：2 started／2 passed／0 failed，38.4秒，包含实际AUTO7共3场景。六次可见Accessibility Settings／Reload实际bounds、新单一入口断言、真实VM/DI与同章load、SQL／JNI像素／当前物理frame及View挂载保持、真实后翻不漏页均通过。实际Reader前台guard PASS，未改屏幕超时；不是用户人工验收。设备按准确AVD身份正常关闭，Windows构建在释放后串行进行。

Windows准备全新manual-acceptance-04，源自干净隔离profile02的偏好与DB备份，绝不使用污染profile03。只读核导航无覆盖，预期RightAndLeft；B作品991200042／章节991200043已准确存在，A使用单独缺失章节991200045。当前仅离线准备，不提前称像素、按钮或人工窗口合格。后续候选和窗口结果沿本节补记，NB04继续未完成。

Windows当前候选为干净提交 `5007f5a83da6d9a9896477e0e4285b612cbe9dbe` 的 `0.11.24.6.5007f5a`，目录 `app-desktop/artifacts/preview/windows/20261101T202020Z-c848eb9b/`；manifest构建／sourceIntegrity／productionRuntime均PASS，完整产物SHA256 `c1c6b2574c71583509388d89367355b05159392aedfeec6a7469bede3324ce77`，原生层仍按清单保持NOT_RUN，实际交互单列。初次预览运行时报告旧8288598版本；只读javap证实生成BuildInfo为5007f5a而AppVersionKt仍内联8288598。限定非增量重编译得到正确字节码，但下一次预览日志明确FROM-CACHE并恢复旧值；随后仅本次构建进程设置GRADLE_OPTS关闭构建缓存及增量编译，项目preview入口通过（`nb-ab-preview-without-stale-cache`，2m7s）。没有改永久配置、清仓库或扩大测试；失败候选保留，不交付。预检首次误传manifest被判形状无效，改传实际EXE后候选／正常输入桌面／显示路径元数据PASS；不是产品或显示故障。

manual04 launcher75024→runtime65632，HTTP49213已核EXE／profile／父子链，原生工具选择到唯一当前候选窗口。A章节991200045实际RESTORE_FAILED同时双图可见，无自动恢复层；主动设置中只见“重新读取双页调整”，滚动后两次观察确认位置不再变化，再点击实际按钮(505,485)，不是导航区域。Escape后原双图／当前源页保持，状态仍真实失败。设置“左右”选中，随后只读偏好仍无导航覆盖；未操作日常配置。

B单独打开真实在线章节991200043（manga991200042），配对恢复READY。owner2／generation1的pageIndex125 full规则从ARMED实际进入HOLDING后才fail；首次即时查询仍ARMED只补一次只读观察，未重复导航或重建。规则FAILED且attempt0，正常伙伴126实际绘出，右侧原图片失败按钮真实可见；既有一次性教学提示已按“知道了”关闭，最终截图无额外浮层。源级imageAttempts仍Ready，不代表实际解码／绘出；准备脚本曾错误要求源状态Error，按真实截图、完整读取失败及实际可见伙伴核对后保留该状态域差异，未伪造状态。当前B只准备人工点击，不据按钮存在声称像素恢复成功。证据在manual04的session.json、a-before-settings／a-after-reload及b-ready-for-user.json。

已停止输入、截图及draw observer；保留唯一manual04窗口供用户点击右侧图片“重试”。预期右侧恢复真实图片、错误消失、左侧伙伴及当前页不动。等待一次人工结果，goal显式paused；B人工像素恢复及NB04仍未完成，未推送或发布。

### 2026-11-02 人工验收与最终收口

用户已确认当前manual04右侧图片恢复、错误消失、左侧伙伴和当前页不动。只读复核仍为候选0.11.24.6.5007f5a、章节991200043、generation1、locationSequence1、当前源页125／display[126,125]；目标125的model/runtime attempt由0→1，伙伴126仍为0，配对持续READY，source page list calls仍1。新增OPEN_PAGE→DECODE→FIRST_PAGE_PRESENTED命中准确source125，用户实际观察补足解码／可见像素证据，不拿DECODE或Ready单独作通过判断。onlineImageRequests前后均5，未声称新增网络响应；SourcePageFetcher允许复用有效编码目标，因此不能以HTTP计数必须增加作为本场景成功条件。首次收尾脚本对此作了过严断言，已按真实状态域及新读取／呈现事实纠正，未重试按钮、改状态或重跑已绿测试。

人工结果归档manual04/session.json，重试后完整状态为b-after-user-retry.json，与b-ready-for-user.json属于准确同章、同代际及同一已绑定owner。A的恢复失败场景与B独立。仅向已核launcher75024／runtime65632／profile04的HTTP49213发送一次正常shutdown，success=true；两PID退出且49213／49214无监听，未动日常实例。无桌面输入、无新候选／Gradle／测试。

本轮只同步Windows图片重试的中英文功能说明与changelog，撤回当前功能页中“首次槽位必须已确认布局”的过时表述；不宣传Android/macOS人工通过。README摘要和开始使用步骤未变，不发布站点或产品。网站VitePress构建2.23秒通过，保留内置断链检查；四个中英文目标页面的实际构建HTML、UTF-8及git diff空白检查通过，未改导航／布局或新增外部链接。产品验收按前述组合focused／原生／人工证据收口，不称最终代码完整模块或全量重验。A／B与NB01～NB04复选框依有效证据完成；推送、正式发布、Android真机及macOS不属本次必需门禁。

### 2026-11-02 后续 Android 时序审查修复（独立限定 Goal）

本节记录 `0f9245e64d` 当时限定范围的实现与证据。下文“保留单页旧流程”和“两个缺陷收口”不再构成全模式目标的完成条件；当前设计撤销该例外，后续执行从上方 NR01 接续。

审查基线 `f8a97a340812523b20472741ed55536492fd4023`，起始工作区干净。本轮只核实并修复两个此前稳定模式验收未覆盖的 Android 时序：AUTO 实际单页开始恢复、挂起时切实际双页后失败仍须激活可读；邻章配对预读挂起期间激活该章、原预读失败后，设置内可选重读必须命中当前失败章及有效 owner。审查线索不是既有产品红；先用实际 VM／Activity Settings、真实 SQL 和可控请求顺序取得失败证据，再最小修复。

复用一个实施代理和既有夹具，主代理负责独立审查及文档。限定两个新方法的红绿、直接相关单页／持久化／迟到回调集成回归和改动文件格式；不打包、不开设备／桌面、不重验 Windows、不运行完整模块或全量。成功后消费 Activity 相同 viewer 重绑接线再检查当前位置／页组；不伪造 READY／空记录、不授权未知记录写入，不引入新提示或决策步骤。公共说明不随本轮未经用户验收的修复扩大。

真实红证据为 `android-restore-races-red-fixture-fixed`，XML `restore-races-red.xml`：2 tests／2 failures／0 errors／0 skipped。AUTO 方法准确停于实际单页→双页后 currentChapter 未激活；邻章方法已调用真实 Settings 按钮动作，但当前B章3的读取次数仍2，未发出有效重读。首轮未计产品红：A在DI注册前创建Activity缺ReaderPreferences，B在失败状态发布后过早检查availability；修正夹具生命周期与等待实际可用性发布，再取得上述有效红。

根因与最小实现：初始 `restoreInBackground` 冻结了激活前等待策略，而失败提示按当前 viewer 判断，AUTO时序可同时隐藏错误并拒绝激活。现保留恢复结果，在UI实际激活及进度会话挂起返回后，用当下双页模式和同pages、未closed的有效owner判断可读；不要求owner必须持续失败，不自动重读、不伪造READY或默认记录。激活及后续availability发布从当前有效owner派生，未知revision仍不允许保存。双页Settings重读直接解析当前章节／有效失败owner，不依赖dialog／pairingRetryChapter；单页继续原目标链。旧请求的pages／owner／章节窗口保护不变。

`android-restore-races-green` 7个精确方法均通过，0 failure/error/skip，无失败重试；涵盖新2场景、原单页失败流程、原默认读取禁写后恢复、实际CAS冲突、失败后已知手动边界与记录保留、A→B→A旧保存失败回调隔离。该批为34.237秒测试／3m11s任务，非完整class或全量。独立审查后仅调整同一激活predicate，将读取许可与持久化状态分离，并强化原2方法为边界会影响的source3：成功后按Activity相同接线对真实viewer重绑，核owner／当前位置，再真实DPAD_LEFT到source4；其余5项按未受此差异影响复用。两新方法检查实际VM、Activity设置动作、SQL、adapter与按键接线；未新增paint夹具，不将adapter断言写成新像素证据，未改的共享绘制／已绘帧锁契约沿前述有效证据复用。

最终补验 `android-restore-races-current-owner-format-fixed` 为2 tests／0 failure／0 error／0 skip，任务2m43s；XML为 `restore-races-current-owner-green.xml`。最初同范围仅新增断言超120字符导致Spotless失败，未运行测试；机械拆行后同范围通过。两项均保持真实Settings读取目标、原SQL记录及未知revision保存禁用，恢复后保留当前owner／source3，再通过真实viewer下一键到source4；邻章场景还释放旧A章迟到失败，确认当前B的window／位置与重读目标不变。2个代码文件限定Spotless Apply／Check通过，内部文档UTF-8／本地链接和最终diff空白核验通过。主代理独立审查已关闭模式判定时机、激活availability覆盖、状态不应限制双页可读、当前owner身份及真实viewer重绑的检查项。

本轮两个缺陷按真实红绿及直接相关组合证据收口；本轮任务进程均退出，起始已有JVM未触碰。未重跑无关已绿测试、未执行完整模块／全量、未打包／启动原生或Windows窗口、未推送／发布。新2为确定性JVM/Robolectric状态管理／Settings／adapter／按键接线证据，不冒称新APK、像素或用户设备验收；既有未改绘制契约按影响复用。内部设计与架构已同步，公共说明保持原有验收范围。

## 2. 实施预算与边界

以下是下一会话实施时的规划估算，不是本轮已消耗或自动批准的额外费用。

| 项目 | 最小安排 |
| --- | --- |
| 技能／工具 | 优先项目原有工具与测试夹具；原生操作才按当前可用 computer-use 技能和平台指南加载；不启用 REA |
| 代理 | 默认 1 个实施子代理连续承担 NB01～NB03；主代理规划、只读审查与必要集成。普通路由按本机适用规则；最多 2 个子代理，不按平台同时写共享代码 |
| 并行 | 实施者写代码时主代理可核对验收与准备环境；共享接口 → Desktop → Android 串行。重型 Gradle 在同一 worktree 经协调器串行 |
| 独立审查 | 主代理未实施共享状态时负责 1 轮独立审查，接口检查与最终闭环分阶段记录；必要修复复审 1 轮。主代理若接管了核心实现，另由未实施者审查 |
| 验证 | 每个任务簇 focused 红→绿→必要重构复验；最终只补新变化及尚未覆盖的集成，不把累计成功称作最终代码全量通过；全模块／全量默认 0 次 |
| 交付物 | 共享与双端 production 修复、对应测试、与实现一致的内部文档、一个功能提交；体验产物按当次明确的日常验收安排提供 |
| 过程产物 | 现有协调器日志／JUnit XML；本 roadmap 的一份累计证据表。无需新生成器、逐任务报告、状态服务或巨型 diff 包 |
| 时间与主要成本 | 预计 1～2 个工作日；主要成本是双端帧身份／动画生命周期接线和定向测试，不是算法重写。执行前按实际基线和设备再估算 |
| 追加条件 | 新产品范围、预算显著超出、全量或正式发布、不可逆外部动作才另作决策；普通红绿与已知范围内的接线修复持续推进。环境问题先诊断，不靠反复重跑或换模型解决 |

执行中已确认并发主干推进至 `5dd165c734ef92d3cc6d49c103d1975574bf61b5`，是起始 `dcea04d9aa` 的后继，包含既有阅读器涟漪恢复、瞬翻预取重绘、放大平移边界及候选记录等其他任务。只核对与本次相交的 Desktop pager／原生测试、Android viewer／holder／测试和架构入口，保留其他主干内容；不重新实施这些能力或运行其完整发布矩阵。后续主干推进至 `c70672a9cbf0b1067e255b2c30e3fb2550da7be7`，相对前述主干新增库／全局搜索、已验文档和版本元数据，没有新增 reader 生产或测试交集。本次冻结的25文件差异通过 Git 三方应用迁入从该固定主干创建的新独立 worktree，原目录及证据保留；冲突按原所有权处理，最终仍以一个功能提交收口，不为整合预先提交未完成批次。预计增加约 30～60 分钟的冲突处理与最小 focused 补验，仍在原实施预算内；两端候选在整合后构建。旧基线排除的涟漪测试接口已在新主干恢复，整合后验证须移除该排除。

本迭代预计触及共享呈现与导航、两端 adapter 和测试，可能超过 8 文件／400 行；这些文件共同实现同一个跨端显示协议，按责任保持内聚，不为行数切成不能验收的零散功能。完整自动匹配算法优化、同步迁移、全章索引及其他旧故障不纳入本次范围。

## 3. 功能任务簇（按顺序）

以下复选框是带测试的任务门禁，不是逐条产生空提交的指令；一个用户能力在 NB04 完成后统一交付提交。若因跨会话需要保留阶段代码，须包含对应测试并注明能力未完成，不用阶段提交宣称修复完成。

- [x] **NB01：共享临时显示、稳定帧与不漏页导航。**

  **前置／输入：**设计第 3～5 节和 NB-A01～A12；现有共享 session／presentation／certifier／fact budget；真实 production 测试夹具。主代理先冻结可验证的不变量，然后由实施者写红测试。

  **修改所有权：**`domain/src/commonMain/kotlin/mihon/domain/reader/` 的 `SharedDualPageSession`、`ReaderDualPagePresentationOwner` 及必要 DTO／默认布局 helper；相应 `commonTest`。只在确有契约需要时修改其他共享类，不能重写 sampler、canonical 评分、持久化 schema 或 scheduler 策略。

  **必须先失败的行为：**200 页、第 126 页、只有本图几何可用且分析挂起时取得有效临时显示能力；该能力只能画正确内容；背景认证完成不改当前帧；126／127→127／128 可重复边界；只绘一页、反向、空绘出集合按规格推进；临时组与已认证组均覆盖“未绘伙伴首次停靠并更新锚点／页码、再次主动离开、不制造未绘进度”；退出／rebind 拒收旧回调。测试执行共享 production owner，不能复制算法。

  **最小实现：**分离呈现许可和认证证明；保留未知证据状态；复用默认规则；引入有界过渡重叠与准确 frame 目标；保留未绘伙伴的导航责任；保持实际绘制和结算身份。临时票据不得升级成 Certified，默认图组不写手动记录。已恢复手动设置／可信宽图继续优先，慢恢复不会挡图。

  **定向复验：**新增行为契约及受影响的 `SharedDualPageStablePresentationContractTest`、`SharedDualPageStrongRequirementsResearchTest`、认证研究／手动调整／进度中的具体方法。旧“未认证无绘制”测试改为分别证明认证仍拒绝伪造、临时合法显示可用，不直接删除旧安全断言。原 126 缺前缀反例继续保持未认证的预期。

  **退出条件：**共享红因正确、选定范围全绿；独立检查区分 logical page／frame 身份、手势取消、相同候选推进、重叠释放及进度；接口能够被两端直接使用。尚未接平台不得称用户黑屏已修复。NB02 依赖此门禁。

- [x] **NB02：Desktop 真实跳页、逐槽显示与过渡接线。**

  **前置／输入：**NB01 共享契约固定；检查当前 Desktop 改动，保护 `c54d59babc` 的单页来屏保留及已整合回归；不顺带重构阅读器其他模式。

  **修改所有权：**`app-desktop/.../ui/reader/ReaderScreenModel`、`ReaderPagerNativePresentation`、`DualPagePagerViewer`、`presentation/` 以及实际需要的 image/publisher 接线；对应 Desktop 测试。一般不改 native 图形后端或扩展运行时。

  **红测和实现一体：**从真实进度条／production 导航提交到真实内容链，构造 200 页在线清单；前缀分析响应保持挂起、目标页真实编码图片可返回，断言未认证期间已有目标像素。逐槽错误／重试与旧画面共存；frame 定位不能用重复页首次匹配；127 在出屏与入屏两帧均有有效 lease；动画取消和连续新目标不回跳。确认加载请求本身不依赖认证许可。

  **定向入口：**优先复用 `mihon.desktop.ui.reader` 包中的 `SharedDualPageOriginalImagesProductionTest`、`SharedDualPageNativeStablePresentationTest`、`SharedSpreadDesktopResourceWiringTest`、`ReaderChapterTransitionPresentationTest`，以及 `mihon.desktop.reader.SharedDualPageRuntimeProgressProductionTest` 的相关方法；必要时增加一个聚焦本次契约的测试类，不复制 fixture runtime。

  **范围门禁：**覆盖 NB-A01～A07 的 Desktop 实际接线，以及 A08～A12 的受影响分支。可控网络仅测试本次已改变的成功、挂起、图片失败和重试语义；若实际改了 HTTP 解析，再补项目规定的协议错误矩阵。资源限制保留、停止增强分析后仍可读，不能靠放大预算或让所有分析先完成来换绿。

  **退出条件：**真实像素和进度断言通过、受影响格式检查通过，记录精确测试方法和红绿原因；不把 source 文本扫描、空容器或长时间等到认证成功当首绘证据。

- [x] **NB03：Android 真实 viewer／holder 与相同导航契约接线。**

  **前置／输入：**NB01 共享契约和 NB02 暴露的接口问题已收敛；读取 Android 构建／原生指南，不直接照搬 Desktop 组件细节。

  **修改所有权：**`app/.../ui/reader/viewer/pager/` 中双页 viewer、adapter、holder 及必要 loader 调用点；对应 `app/src/test`。共享规则变化回到 NB01 同一 owner 修正，两端同步复验受影响方法。

  **必须先失败的真实行为：**进度条 126 冷跳，在需要的远端分析尚未完成时，通过真实 holder 的 before-draw 接口画出本图；不是给全部页面预填 Ready／header 后只等 Certified。旧 `uncertifiedOriginalNeverPreparesNativeSlot` 应保留原图认证不可伪造的含义，并新增合法临时绘制的相反场景。40 页全 Ready 的超过 16 页测试不能代替本轮在线 200 页验收。

  **最小实现与回归：**viewer 消费共享显示许可；按目标 frame 精确定位，修正 `indexOfFirst containsPage` 在过渡重叠中的歧义；holder 保留已绘像素、迟到伙伴／重试和每帧释放；触滑、点击、按键与进度条尊重同一源页意图。保留 page-list／loader／原图身份及内存门禁。

  **定向入口：**`DualPageViewportZoomTest`、`ReaderPairingPreparationIntegrationTest`、`ReaderPageFactProductionWiringTest` 及实际受影响的 progress／chapter-window 方法；用 production viewer/adapter/holder 进行挂起响应与绘制断言。支持 RTL／LTR，至少覆盖 A03～A07 的 Android 代表路径及 A08～A12 的变更分支。

  **退出条件：**所选 Android 方法真实执行且通过、格式通过；不得以 Desktop 通过替代 Android 接线，不把 JVM／Robolectric 结果写成用户设备已验收。

- [x] **NB04：双端闭环、独立审查、文档与提交。**

  **前置：**NB01～NB03 完成，没有已知未解释的相关测试失败。先汇总证据，找具体缺口，再选择补验，不因为任务结束重跑整个 reader 或整个模块。

  **独立审查：**检查完整 production 链路中是否还有配对→加载／绘制的硬依赖、临时结果是否污染认证、重叠帧是否误选／释放、未绘伙伴是否丢失或导致导航死循环、手动恢复是否被静默覆盖、进度和旧回调是否有效。最多按预算进行 1 轮必要修复复审。

  **自动化收口：**验收表 A01～A12 各有真实断言；已有有效证据可按最终差异复用，新增改动只复验受影响范围。保留普通认证测试及单页／条漫的精确关联回归。格式检查覆盖改动文件；确认非零 tests、skipped 和环境失败单独记录。

  **受影响平台用户路径：**在当次授权的 Windows 日常 preview 和 Android 测试候选／实际运行环境，各走一次“在线 200 页→跳 126→目标先显示→匹配迟到→前进纠正→回翻→重试”的真实入口路径。优先使用既有可控在线测试源或合法脱敏图片，不下载用户作品整卷。平台、候选来源、配置、输入和像素／页码观察分别记录；若 Android 仅取得原生宿主测试而无设备体验，明确记设备项 `NOT_RUN`，不混称双端实机完成。macOS 不因 Desktop 共用代码而自动触发正式验收；也不能据 Windows 通过声称 macOS 已验收。

  **交付：**将本设计及架构入口改成与最终行为一致的状态；最终核对 git status，仅提交本功能代码、测试及必要文档，报告 hash。没有推送授权时不推送；有推送授权则按 Git 交付细则核对整个集合和固定 SHA。公共功能说明等用户验收本次具体候选后再更新。

  **完成判断：**代码／自动化、平台运行、用户验收分别报告。平台必做项受阻时可提交已通过验证的实现供续验，但该复选框保持未完成并列出具体缺口；不把环境缺失或“提供包了”当验收通过，不为此扩大到全量／正式发布。

## 4. focused 执行约束与已验证命令形状

以下只示范参数和精确过滤，不是要求重跑诊断测试。每次选择当项真实受影响的类／方法；一次调用只包含一个 Test task，多个平台串行。在同一 worktree 通过协调器执行；超时先查状态，不能重复启动或全局清理 Java。

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
# 原诊断中实际成功的过滤方式；实施时换成当前任务的方法，核实际 argv 与非零用例。
python scripts/gradle-coordinator.py run --key 'pairing-diagnostic-example' -- 'D:\Shell\Github\mihon\gradlew.bat' --offline --console=plain '-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' :domain:jvmTest --tests 'mihon.domain.reader.ReaderPairingCertificationResearchTest.sixteen observed control pages cannot certify an unseen prefix reset' --no-daemon --no-parallel --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'
```

Windows 使用经核对的实际 wrapper 绝对路径；隔离 worktree 必须换为该目录的 wrapper，不能仍指回主目录。带点号的 Gradle 属性整体引用；`./gradlew.bat` 在当前协调器的 Windows 命令入口曾被解释错误，未整体引用的 `-Pkotlin.compiler.execution.strategy=in-process` 曾被 PowerShell 拆开。这两次属于启动／参数失败，没有执行产品测试，不算产品红因。优先修命令而非改 production。

内存参数是本机诊断成功配置，不是新的项目固定上限；重型测试前按当前资源和项目规则确认。离线依赖缺失时遵守联网指令和真实错误边界，不通过扩大测试解决环境问题。格式命令按项目现有过滤能力限定改动文件，避免 `spotlessApply` 改写用户未提交内容。

## 5. 累计证据与交接记录

实施者在同一表中补充必要条目，不复制大段日志。每项至少注明代码基线／后续相关变化、具体方法、红因、绿结果、日志／XML和平台边界。没有执行保持 `NOT_RUN`。

| 批次 | 实现／提交 | focused 红绿与格式 | 平台实际路径 | 未解决项 |
| --- | --- | --- | --- | --- |
| 规划 | 本次仅设计文档；源码基线见第 1 节 | 1 轮独立设计审查；已修正已认证帧未绘伙伴的停靠遗漏；UTF-8／相对链接及 diff 空白核验通过；本轮产品测试 NOT_RUN | NOT_RUN | NB01～NB04 全部待实施 |
| NB01 | 基线 `dcea04d9aaf716edd6a739bb8c06905ac7df352b`；独立 worktree `D:/Codex/worktrees/reader-nonblocking-pairing-20261101`，尚未提交；共享接口独立检查通过 | `nb01-red` 6/6 门禁行为红；`nb01-navigation-red` 2/2 未绘伙伴跳过红；`nb01-frame-selection-red` 2/2 错帧与认证背书红；`nb01-stale-frame-red` 1 fail / 1 pass，确认旧帧重授；`nb01-regression-green` 77 tests，0 failure/error/skip；后续精确旧帧拒收及组尾目标修正由 `nb01-final-focused` 10 tests，0 failure/error/skip 覆盖。日志为该 worktree `.gradle-coordinator/<key>.log`；最终 XML 为 `domain/build/test-results/jvmTest/TEST-mihon.domain.reader.SharedDualPageNonblockingPresentationTest.xml`（旧批 XML 已覆盖，以旧日志保留 77 项结果）。最终五文件 Spotless 输出 5 次 `IS CLEAN`，`git diff --check` 通过；最初相对 hook 路径跳过与超长行失败不计通过 | 不适用：共享接口阶段 | 等待双端接线与统一提交；不宣称最终代码重新通过 77 项全部回归 |
| NB02 | IN_PROGRESS，同 NB01 worktree；复用 production HTTP／Skia／Compose 夹具 | `nb02-cold-red` 在 `compileTestKotlinJvm` 因 1.5 GiB heap GC thrashing 退出，未执行测试，不作产品红因；停止后核对可用内存约 9.8 GiB，仅针对同一 focused 用例调整本次 JVM 参数。`nb02-cold-red-retry`（3 GiB heap／768 MiB metaspace）实际执行在线 200 页方法，1 test／1 failure：开章、跳 index125、HTTP 内容及目标 decoder 完成、未认证断言均经过，准确失败于 `A ready target must draw before matching completes`；不是空容器或编译失败。`nb02-native-frame-red` 的在线冷跳已在真实右槽像素谓词下通过，NeedFacts／194 gate 保持未完成；本机 intent→pixel 492ms，仅为该夹具观察，不作普遍性能承诺。同轮 Native 晚配对红为新目标仍选旧帧、晚宽目标红为已绘伙伴 bounds 消失。共享必要集成修正：`nb02-shared-mount-red` 2/2 行为红，覆盖独立挂载释放和发布前稳定帧身份；`nb02-prepared-projection-red` 确认已预备邻帧未进入投影；`nb02-shared-protocol-green` 16 项中 1 项异地激活拒收红、15 项通过；修正后 `nb02-shared-final` 16 tests，0 failure/error/skip，五文件 5 次 `IS CLEAN`。含共享新 12 契约及受影响 rebind/release/unpainted/rapid 方法，复用此前未受影响的 77 项结果，不声称最终版本重跑全部。后续 `nb02-shared-retry-green` 17 项通过，覆盖未锁伙伴重载不撤销已锁画面和部分 rebind；`nb02-memo-group-red` 以真实认证 [4,5] 与默认 [5,6] 的差异确认缓存旁路不能改变正确选择，`nb02-memo-bypass-green` 18 项通过，含左右已预备帧交替准备的身份稳定。`nb02-integration-focused` 实际 23 项，22 通过、1 failure、0 error/skip：真实在线首绘、晚配对、晚宽、拖动取消／候选失效、HTTP 重试、SQL 进度和章节边界通过；`hardening repeated zoom pan reset and rapid turns reuse bounded images and release on exit` 仍为 expected logical2 / actual1，须诊断后补验，不能称本批全绿。独立审查补充的晚宽后显式调整在 `nb01-latewide-adjust-red` 1 项失败（错误返回 Save）；限定拒绝逻辑页不在所保留物理帧时的调整后，`nb01-latewide-adjust-green` 34 项通过（新增非阻塞契约15、原手动意图17、原会话调整2），0 failure/error/skip。`nb02-hardening-trace` 确认快速反向新帧尚未绘出时实际应停靠未绘伙伴；保留每步已绘集合为空和全部像素／资源／退出断言后，`nb02-hardening-final-green` 最后单方法 1 test、0 failure/error/skip。此后只修改 Android 接线，复用本批其余 22 项未受影响证据，不称最终重跑全部 | Desktop Compose／Skia 实际像素已执行上述 focused 路径；候选窗口运行 NOT_RUN。独立 Android API36 AVD 环境启动预检通过并已精确关闭，尚未安装应用 | Desktop 最后 hardening 已关闭，限定格式通过，候选窗口运行 NOT_RUN；Android 实施中 |
| NB03 | IN_PROGRESS，同一 worktree；双方向 200 页挂起分析、独立 holder 许可、重叠帧导航测试已加入，产品接线尚待实际红因 | `nb03-holder-contract-red` 首轮未执行测试：`compileDebugUnitTestKotlin` 因未修改的 `PagerRippleTest.kt` 引用缺失 `pageTurnRippleMode`／`PageTurnRippleMode`／`prepareRipple`／`rippleMode` 接口而失败（9 处 unresolved）。属于基线测试编译阻塞，不作产品红。仅在本次临时 Gradle init 中排除这个过期文件后继续选定方法，不更改仓库旧测试，不将涟漪效果算作已验证。限定配置成功日志确认只排除该文件，其余 176 测试源参与编译；首次配置误入 buildSrc 和其作用域修正保留为环境日志，不计产品红。`nb03-holder-focused-red-fixed` 实际执行 13 次（5 个方法；失败项按项目 `maxRetries=2` 重试，不能把重试当独立覆盖），12 failure、0 error/skip：双方向冷跳缺合法临时能力、独立 holder 误共用票据、隐藏当前帧即撤销能力均已真实复现；1 项 RTL 重叠方法通过，核实 RTL 反转后的列表顺序恰好优先新帧，不能证明另一方向无歧义；同方法换为 LTR 后，`nb03-overlap-ltr-red` 1 方法执行 3 次（含失败重试）均准确失败：要求新 [4,5] 却选旧 [3,4]，0 error/skip，已证实同页首次匹配会选错重叠帧。`nb03-real-gesture-red` 保留 production adapter／listener，2 个方法执行 6 次（含失败重试）均失败：滑动应先停未绘源页4却跳5；取消后逻辑锚点未变但目标帧从 serial0 变为 null，已绘帧保护失效。没有以替换 adapter／listener 的手势桩作为证据。`nb03-holder-first-green` 10 方法执行20次（含重试），5方法通过、5方法失败，共15 failure、0 error/skip。独立 holder、隐藏帧保留、认证不可伪造及两项分析失败／恢复路径通过；冷跳夹具存在异步绑定与 mock overlay 问题，另实际 pager 回位仍失败（逻辑已停4但 native 仍选 [5,6]；取消时 frame／anchor 已保持但像素未恢复）。仅继续这5个失败方法诊断，不能称 NB03 已绿。后续 trace 确认滑动／取消用例尚在 SETTLING，补实际 computeScroll／Canvas 帧并等真实 IDLE 后，`nb03-native-idle-green` 的重叠、滑动、取消3方法通过；没有额外增加生产延时定位。冷跳新图片子视图到达后重新测量布局，`nb03-cold-layout-green` RTL／LTR 2方法首次通过、0 failure/error/skip、无重试：像素之后仍 NeedFacts／activeFacts，其他页 null 或零几何 UNKNOWN 且 NotAnalyzed，captureCertified 仍 null。累计10方法有效绿（首批5＋重叠1＋真实原生2＋稳定冷跳2），不是一次最终10方法重跑。临时诊断监听器／打印已移除，`nb03-final-app-format` App7正常 Apply／Check通过 | Android 阅读业务 NOT_RUN | 实际行为红绿、平台候选与格式待完成 |
| NB04 | 准备中；预算内第二代理完成只读夹具定位后，仅扩展 `HostReaderArtFixture` 的可选合成在线源并新增双方向 `SharedDualPageNonblockingNativeTest`，不改产品代码；主代理已只读检查隔离身份、SourceManager 委托与恢复、真实 Slider 单次输入及 PixelCopy 断言 | 原生测试编译／执行 NOT_RUN；`nb03-format-check` 最终成功：显式清单 `nb03-format-files.json` 包含 domain5／Desktop10／app7，共22个改动 Kotlin 文件（含两个新文件和两个 androidTest 文件），三模块正常 Apply 执行成功、Check 成功或当前输入 UP-TO-DATE，均非 SKIPPED。临时配置只限制本次文件，无永久构建配置改动；原先任务注册时机失败和超长行失败保留为过程日志，不计通过。旧外部源 APK／输入所在明确历史路径已不存在，未复用历史通过记录；新夹具使用合成 HTTP 页表和 PNG，不依赖私有作品或扩展下载，不宣称验证外部扩展 parser | Windows 候选 NOT_RUN；Android API36 Debug业务首批已运行，2/2失败，不能称平台通过；AVD 独立目录为上述 worktree `.gradle-coordinator/android-avd`，name=`mihon-nonblocking-20261101`。无窗口环境启动已验证 boot=1、API36、ranchu、x86_64、2560×1800；首次环境预检时dev包未安装；随后精确关闭并确认进程及 adb serial 消失。只证明环境，不作产品通过证据；预检见该目录 `environment-check.json` | 已从固定 `c70672a9cbf0b1067e255b2c30e3fb2550da7be7` 创建 `D:/Codex/worktrees/reader-nonblocking-pairing-integration-20261101`（branch `codex/reader-nonblocking-pairing-integration`），仅三方迁入本任务25文件，原 worktree／日志保留；两处代码／测试冲突由原实施者处理，文档自动干净合入。新 tree 后续日志位于其 `.gradle-coordinator/`；上述旧批日志仍在原目录。新基线 `nb-integration-pan-withdraw-red` 1/1真实失败：Zoom2/-100 在未绘宽槽撤销后自动变为Zoom2/0；按稳定frame身份保留最初非空物理槽位包络（不包含原本空的cover槽）后，同一 `nb-integration-pan-withdraw-green` 1 test、0 failure/error/skip，包含原Zoom1控制与新Zoom2真实GREEN矩形／mount／frame断言。`nb-integration-pan-narrow-red` 再以真实80×600窄伙伴补入既有加载焦点方法，1 test／1 failure：伙伴正常到达令Zoom2/+100自动变为+39.33333，证实不能只保护撤销槽；以当前物理frame内此前合法焦点协调实际边界收紧后，`nb-integration-pan-narrow-green` 1 test／0 failure/error/skip，真实RED矩形／mount／frame不动，Mouse向外不能超过+100、反向30立即到+70、再外拖不能重开到+100。移除永久槽包络特判，实际几何继续更新；主动scale／viewport更改清除保护。`nb-integration-pan-coalesced-red-fixed` production state的同帧几何＋输入1/1红（+100→0），最小修正后 `nb-integration-pan-coalesced-green` 1/0，保持+100且反向99立即跟随。`nb-integration-desktop-focused` 最终统一方案13个选定方法、31次执行，0 failure/error/skip／无重试（Native10＋真实HTTP1＋单双页预取4及动画偏好16）；已覆盖统一实现的撤槽Zoom2、窄伙伴和真实Mouse、main3个pan、取消／快速反向／过渡／重试，不能用旧包络版本的绿替代最终方案。`nb-integration-android-focused` 默认全部测试源编译、无旧Ripple排除，28个方法／35次执行，10 failure、0 error/skip：24方法首次通过（含持久化3、进度4、章节／webtoon、deadline、rebind、新门禁等）；两个encoded tile夹具手造无frame Single而被正确拒绘，Single像素夹具首轮异步未绑定后重试通过（不视稳定），source ripple同组停靠时 native index不变导致LTR方向判错。`nb-integration-android-six-fix` 仅4失败方法＋2个prepared／cancel传播方法，6 unique／10次执行，6 failure、0 error/skip：source ripple方向、Single真实像素及PagerRipple2均首次通过；encoded2仍各3次失败于subject首图绘制前置校验，尚未到tiles断言。两个encoded夹具改用实际Pair descriptor，并在真实配置回调后重新取目标帧、等待上一pager真实Scroller／Canvas回到IDLE；没有放宽production许可。`nb-integration-encoded-idle-provisional-red-fixed` 3 unique／5次执行：普通及镜像encoded方法均首次通过，全部拼缝／缩放复位断言保留；默认provisional fractional夹具3次准确失败于非法Single被beforeDraw拒收，0 error/skip。只读核对4个共享旧夹具调用点后，以真实Pair帧迁移；`nb-integration-provisional-four-green` 4 unique／6次执行，3 failure、0 error/skip：fractional拼缝、迟到伙伴与非法Single提案拒收、双页背景3方法首次通过；edge拖动方法首图beforeDraw失败被定位为LEFT封面夹具在首次配置回调前取得了旧帧；仅在该方法自身排空真实初始回调，共用Single helper及encoded路径不变。`nb-integration-edge-resource-green-import-fixed` 2 methods／2 executions、0 failure/error/skip、无重试：edge真实LEFT Single与RIGHT Pair拖动通过；A06在原deadline后经受控PageLoader正常入口令page2返回真实encoded Ready，由实际pager holder绑定／Canvas绘出，断言仍RESOURCE、原budget／location、实际drawn集合正确、没有新fact消费者，原显式retry仍通过。漏import造成的前一批编译失败未执行测试，不作产品红。本批仅证明holder／预算接线，不把受控PageLoader称作HttpPageLoader；后者交点留给在线原生ART。其他24项和已关闭分支按输入复用，不称最终重跑全部。`nb-integration-format-scoped` 正常执行限定25个Kotlin文件（domain5／Desktop12／app8）的Apply+Check，exit0／PASSED，未改永久构建配置；`git diff --check` 与4份内部文档UTF-8／24个本地链接核验通过。共享协议及两端接线独立审查已关闭上述已知项，最终平台路径仍待完成。准备保存同一功能提交并整合后续main变更；NB04与复选框不提前宣称完成。保留main用户拖动／反向跟随／缩放／窗口变化的限位契约；已保存29文件功能快照`e9c4e246b0e098e6fe08c16a227df32e9fdd2c42`，干净rebase到main `6b5831c64dd9f09a7d79c93fac9a75a548c740fa`后为`88737962edd9bd9bfcbf7261d4221d01cf1999fe`，25个已验证Kotlin文件整合前后内容一致。尚未回到main或推送。统一入口Debug构建PASSED，候选目录`app/artifacts/android/0.19.4-aex.115-vc133-88737962ed-debug-957a01500040`，实际versionName=`0.19.4-aex.115-9749`、code133、包`app.mihon.desktop.fork.dev`，APK SHA256=`42d58c7d63994a1d8279322209dbb0a282f36c1815232f6d99db2342939fae23`；已核对AVD身份并经统一入口安装／校验，未作为正式包。首次原生测试编译仅2处SDK常量引用错误，改为`AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id`后，`nb04-android-native-compile-fixed`的单测试文件正常Apply+Check及assembleDebugAndroidTest通过。test APK SHA256=`0343412ae120e82d4c8a5a8aad624b811d0a6333102f9d6f1a72542d6724f2dc`，manifest target及与宿主同调试签名已核，未改production。实际ART首批2 tests／2 failures：RTL已通过真实Slider跳logical125及RED像素，随后严格页码反馈检查失败，原因尚待真实raw range／菜单接线核实；LTR目标125仍Queue、伙伴126为DownloadImage，frame[125126]无drawn／native／像素，普通加载链路待诊断。完整runner结果为FAILURES，不因adb exit0或INSTRUMENTATION_CODE -1称通过。证据、元数据及对应test APK保留于`.gradle-coordinator/android-native/attempt-01/`；不以预填125Ready替代真实HTTP问题。新增真实HTTP回归 `nb04-dual-http-four-red` 4 unique／8 executions、6 failure，其中1次为夹具退出后的UncaughtExceptionsBeforeTest，不作产品红；其余有效失败准确为RTL挂125令126排队、LTR挂126令125排队，确认单槽普通调度的产品串行阻塞，另两到达顺序首次通过。`nb04-visible-pair-scheduler-red` 3 tests／1 failure后，新增精确可见pair普通第二槽准入；`nb04-visible-pair-scheduler-green-fixed` 3/0 failure/error/skip，含原Single serial、当前pair普通与Retry grant、stale／foreign／partial／union／wrong-window／fact两侧／background拒绝。首轮HTTP绿4/0，随后独立审查补 `nb04-viewport-identity-red` 5 unique／13 executions、12 failure：非当前viewer、旧holder恢复旧window、rebind旧页重新贴generation、opaque旧帧激活拒绝均准确复现，原Single1方法通过；最小修正后 `nb04-viewport-identity-green` 5/0 failure/error/skip。`nb04-http-lease-boundaries-green` 10方法／10 executions、0 failure/error/skip／无重试，使用真实ReaderViewModel.onViewerLoaded入口重验HTTP4方向／到达顺序，另含Retry、双向快速替换、原C提升复用、非合作取消及物理2实际退出；Runner未修改。新增IO租约首审已按这些反例关闭。ART将保留全部源页／像素／进度门禁，按实际串行事实transport拆分阶段；尾部同200页的实际错误／Retry仍在迟到匹配→前进纠正→回翻之后，方案已核；`nb04-desktop-scheduler-bridge` 1 test／0 failure/error/skip，验证新共享API默认调用的真实HTTP冷跳及退出。`nb04-dual-http-format-final` 限定本轮domain2／app7共9个Kotlin文件正常Apply+Check通过，其他本任务未变文件复用前批格式证据；为满足lint只补保语义布尔括号及缩短测试方法名，未改断言或production行为。`nb04-dual-http-native-compile` assembleDebugAndroidTest PASSED／exit0；新版test APK 2644106 bytes、SHA256=`7ac35d0c972b4f159325c87b780eccae42214d3c50438856945ceb2e97d4629e`。新版ART尚未执行，滑块浮点原因未证实；新夹具记录实际raw/min/max、VM源页／可见整数／菜单／live matching及认证原因，比较同时保留精确源页、整数文本与1e-4窄容差，不放过错页或空节点。当前功能范围34文件（30 Kotlin＋4内部Markdown），准备在原单功能提交上保存并整合main文档增量，再构建新Debug宿主。NB03／NB04仍未满足全部退出条件 |

### 最新原生进度补充（未完成门禁）

当前功能快照 `8959d011a8c3d95aa5a0ff7b0baadab1aec4f5d4` 已整合本地主干 `9b8ac6570180a6e71dda99141d39284313be44e7`；该次主干增量仅研究归档，没有 reader production 差异。新版 Debug 宿主 APK SHA256=`ee161772d29f6754ad642a192efa70f10ce6e09341ab7205a31dd5d45f0a0a13`，统一入口构建、安装及身份核对通过，仍是开发配置。

- `attempt-02`：RTL／LTR 均已取得目标 index125 的真实 RED 像素；实际 Slider range 为 `125.99999`，VM 源页125、显示状态126。合并无障碍节点的文本断言失败；同轮截图实际可见126。`ChapterNavigator` 当前页盒还绘制透明总页数占位，不能以合并节点的200替代屏幕当前页；尚不能把缓存认定为唯一原因。
- 为保留真实 UI 门禁，测试改用既有 Compose unmerged 语义验证126确实显示，同时保留 Slider 窄容差、整数、VM源页及像素断言；测试线程先等待 Compose idle，并刷新 API36 无障碍缓存。未为测试修改产品显示行为。单文件限定格式与 `nb04-feedback-observation-idle-final` 的测试APK编译通过。
- `attempt-03`：test APK SHA256=`fc12ae24620cbea61b1b9ff7cab71d61e47736d1fbd493c94a2569e7bc8ee2ac`；2 tests／2 failures、0 skipped。两方向首图及页码反馈均通过，准确失败于 `Real drawn target must reach chapter progress`。尚未执行完整迟到匹配→前进纠正→回翻→尾部错误／Retry，不能称原生路径通过。测试结束后的含WAL数据库快照中，章节 `last_page_read=0`，6个历史 reading event 全为page0，没有125事件；快照只证明此时持久化状态，不能单独推出断点原因。
- 只读核对生产链路：视口结算接受后立即入队保存，`onPause` 只更新历史，因此不能通过强加暂停／flush步骤掩盖当前失败。`nb04-progress-observation-compile` 限定测试文件格式与APK编译通过（4m46s）；`attempt-04` 的新观测包 SHA256=`4b3de72a5bb04dc015fdc420c827097c645a6a74c85337fd9e5fa33b46425806`。本轮2 tests／2 failures、0 skipped，失败门禁保持不变；两方向运行均观测到 source125、state126、memoryRead0、pendingPage0且该旧视口已settled；protectedPage125且为当前canonical对象；ticketEligible=true、rendered/drawn125、idle=true、resumeRequired=false、incognito=false、handle开放、写入失败及待写队列均0。这将断点缩小至真实滑块导航与进度结算接线；下一步以真实Activity／VM／Canvas／SQL回归固定红因后最小修复，保留全部持久化门禁。

针对该断点，最小产品修正仅在 `DualPageViewerAdapter.rebuildItems` 区分非空主动目标与被动刷新，避免显式跳页／源页翻页被误标 `layoutOnly`。`nb04-navigation-intent-focused-green` 6个既有真实Activity／VM／SQL相关方法首次通过，0 failure/error/skip，覆盖显式第二源页模式切换、部分视口进度追加、邻章窗口重建、裁边新几何上下文、图片属性刷新不重复记读及纯布局／单页路径；`nb04-navigation-intent-format` 的Adapter单文件Apply+Check通过（17s），4份内部文档UTF-8／25本地链接目标与diff空白检查通过；仍须由同一ART失败场景复验主动路径。此前两批新增JVM夹具未挂载真实选中holder，均为setup失败，未作为产品红因；相关新增方法已撤掉，保留有效ART03／04红证据，不降低像素或持久化门禁。

当前修复快照为 `e6f9e7798916b30feb2bb0c218fb7cf999f80c0e`。统一入口Debug构建1m14s通过并核验安装，APK SHA256=`7022eb20d6ef00c2c0ee689752597981726eb6f03a57be589dd54e61ebcab9df`；Native／HostFixture源未变，复用已固定同签名的test APK `4b3…5806`。`attempt-05` 两方向均确认真实首图、页码、memoryRead125、pendingPage125及SQL125，原进度缺口在相同原生门禁关闭；随后未绘126停靠、再次前进127真实绘制通过。但完整runner仍为2 tests／2 failures、0 skipped，失败于随后真实反向：共享目标仍127／sequence3，诊断摘要列出的[125,126]挂载frame没有locked／drawn。摘要遍历所有holder，不能据首个holder认定原生选中已回退；既有导航日志没有previous记录，正在补回翻前后精确选中descriptor、viewer当前项与窗口焦点观测。此时不能称完整原生路径通过；迟到匹配／纠正／尾部Retry仍待该断点关闭后继续。

`attempt-06` 使用仅新增精确导航观测的test APK `507fbd9c0b7ba7d6c467b6344a4c7fe5fdd8d64f381c033e5411cf844ae186d6`（编译／单文件格式通过），两向仍2 failures；回翻前后应用focus=false、activePackage=com.android.systemui、设备interactive=true，current与selected均为[127,128]，owner开放且idle。实际截图显示首次“Viewing full screen / Got it”系统教学浮层。此证据解释了没有previous事件，不能将其计作产品回翻缺陷。按既有研究记录，仅在本任务专用AVD核身份后将原为空的immersive_mode_confirmations确认；配置前后值保留于内部过程目录，不改变host系统或应用产品行为。

`attempt-07` 在同host／test输入下完整冷启动重跑，两向回翻实际focus=true、按键送达、owner从127/sequence3转126/sequence4，目标与选中[125,126]一致；红图、迟到绿图及原挂载／几何稳定检查也经过。完整runner仍2 tests／2 failures、0 skipped，准确失败于夹具等待page5当前HTTP active。实际HTTP返回已包含宽图21，并在20／18附近停止远前缀访问；源码Canonical的SPREAD硬边界与Certification收敛说明21已使126/127可确定，无须再访问5，没有预算耗尽证据。最小夹具修正将gate移到宽图21自身，保留UNKNOWN→真实active调用→release→宽图几何→认证及全部后续导航／重试／进度门禁；不改产品算法或预算。

`nb04-wide-reset-gate-compile` 单文件格式与test APK编译通过（4m34s），`attempt-08` test APK SHA256=`e4b04b248142de9b66536f685e1fa16fae6db05f98b5ca52d90ea3a05ecaec7b`。两向均走通实际held宽图21→release→真实SPREAD→认证[126,127]、原RED/GREEN挂载／几何不动、主动前进绿黄／正确物理leading、反向[124,125]及页码反馈；完整runner仍2 tests／2 failures、0 skipped，准确失败于测试把主动回访后的resume误当全局最大页码。生产ReaderProgressPolicy按本次有效视口取max，VM／Repository保存该位置；既有 `accepted last pair followed by a real back turn keeps read while saving the lower position` 明确后退保存较小位置且read保留。公共同步public_page同样是resume，不能虚构单调frontier。保持产品语义，夹具改在release宽图前等待实际126保存、捕获完整(position,eventcount)，后台认证后且下次主动输入前要求完全不变；再分别断言主动前进保存127、主动后退保存125，保全部真实绘制和完成门禁。设计4.4据既有语义澄清同一位置的后台重复与主动回访边界，不引入新存储或扩大范围。

`nb04-progress-oracle-compile` 单文件格式／test APK编译通过（4m22s），最终Native源SHA256=`9a872b36c7a162cf90d93b6d15d91448abb40bd9d2314c5e142555fe03dcb7ce`。`attempt-09` 完整ART **2 tests／2 passed、0 failure/error/skip**，runner原文`OK (2 tests)`，外层32.26s（runner30.499s），test APK SHA256=`5e9c28a9dcec8d77d4557f6c4dace9450d11b93f0b60a80aa00688e1f7d11116`，宿主仍是上述`e6f9e77989`／`7022…b9df`开发候选。两方向均实际走完200页在线冷跳125首绘／页码／SQL125、未绘126一次停靠且不记读／再次前进127／回126、真实held宽图21释放及认证[126,127]、旧RED/GREEN挂载与几何不动、后台完整进度tuple不变、主动纠正127与SQL127、回翻124/125和SQL125且未伪造完成，以及尾页区域180普通首图、实际HTTP503、真实UI Retry恢复蓝色伙伴、ORANGE挂载／几何与有效进度保持。无预填Ready或伪造facts替代该HTTP路径。该配置是API36 x86_64无窗口专用模拟器Debug ART，不声称实体设备或正式Release／R8矩阵通过。完成后已核AVD身份精确关闭，adb serial消失且无本任务emulator／qemu残留；过程记录`android-native/cleanup-after-art.json`。生产源码自`e6f9e77989`未变，后续仅本测试与内部文档修正，未为源码提交身份重复重建宿主。

Windows第一份开发预览由干净快照 `d8055a24aa5e843feab774735d2c9dfb741381f4` 构建，`nb04-windows-preview` PASSED（1m59s），实际版本`0.11.24.6.d8055a2`，production APK扩展运行时校验通过；sourceIntegrity／build／productionRuntime均PASS，原生层仍NOT_RUN。实际EXE位于当前worktree `app-desktop/artifacts/preview/windows/20261101T080007Z-7f201e97/Mihon-Desktop-0.11.24.6.d8055a2-unpacked/Mihon Desktop.exe`；应用包384文件／272645392 bytes，内容摘要`4ec8f3a25bbcf264a33bf424851aa6f50cc564ba9721dc70b8b3168ad965e93f`。只读preflight显示正常输入桌面、display路径容量2／模式容量4；这不是实际捕获或输入通过。

原生输入前核对既有TestMode线上夹具，确认其pageCount cap=180且全部同尺寸，不能用180页或无分组相位变化的输入替代本roadmap的200页／迟到宽图纠正。预算内给同一实施岗位增加最小测试源适配：cap仅到200，可选仅ONLINE的`widePageIndex`返回真实宽图编码，默认旧路径保持；通过真实HTTP端点／编码图像的focused红绿及原来源边界回归，再构建第二份preview。仍不修改阅读器算法、预算、API读取控制上限或正式版本，不以第一份预览运行时PASS冒称窗口路径通过。

窗口夹具补充的 `nb04-desktop-fixture-red` 实际3 tests／3 failures、0 error/skip：200页被旧180 cap拒绝、旧cap内指定21仍为竖图、非法负索引被忽略。最小实现后 `nb04-desktop-fixture-green` 5方法／5 executions、0 failure/error/skip／无retry（3个新真实HTTP方法＋原标准来源矩阵＋原离线部分下载方法），编码检查直接解码HTTP JPEG；省略可选字段时逐页JPEG字节相同且JSON无新增null键。非法源、非整数、越界、非竖向基础尺寸均400且保留原夹具。`nb04-desktop-fixture-format` 的3个Kotlin绝对target正常Apply+Check通过（14s），仅格式缩短新测试标签malformed→bad、JSON空白与中间val，无断言或产品逻辑变化。production仅既有TestMode Controller／HTTPServer，阅读器业务／共享／Android自ART09未变；真实I/O控制上限与分析预算均未改变。补充内部API说明后当前功能范围38文件（33 Kotlin＋5内部Markdown），仍以一个功能提交收口；准备第二份预览和窗口路径，NB04未提前勾选。

第二份Windows preview由干净功能快照 `ce6b22816392a7ee149898011be81245cade1548` 构建，`nb04-windows-preview-200` PASSED（1m24s），实际版本`0.11.24.6.ce6b228`，build／sourceIntegrity／productionRuntime均PASS；384文件／272646155 bytes，应用内容摘要`2321c550afa906fba17dfe72edae9dc3545febd6daa5296595a04925bbee892e`。实际EXE为当前worktree `app-desktop/artifacts/preview/windows/20261101T082229Z-8ff28170/Mihon-Desktop-0.11.24.6.ce6b228-unpacked/Mihon Desktop.exe`，包含200页／宽图夹具适配。独立profile／端口及精确关停信息已准备；原生验收在本轮末尾集中执行，manifest中的nativeInteraction保持其构建时NOT_RUN，实际窗口证据单列。

Windows本轮原生路径：NOT_RUN／视觉未确认。以第二份最终EXE、独立profile与49183／49184端口启动，已核launcher38164→runtime12080的实际路径／命令行／profile及HTTP归属；返回的唯一候选窗口id11209614。Computer Use首次窗口观察及激活后复核均只取得底层桌面画面，没有可见Mihon内容；同时实际mounted reader为200页、geometry／windowShowing／windowFocused均true。现证据不能区分渲染或捕获原因，不作产品根因判断，未向未知画面发送导航输入。按集中操作规则停止，精确正常shutdown返回202，runtime／launcher均退出且HTTP监听释放。内部证据在`.gradle-coordinator/windows-native/attempt-01/`，候选manifest原生层仍NOT_RUN；NB04及统一勾选继续未完成，下一轮先离线定位该画面缺口后续验，不重跑已通过的Android或全模块。

Windows `attempt-02`：核同一ce6候选、独立profile02、launcher11568→runtime23704与49185端口归属。初始捕获内容与当前HomeScreen不符，重新绑定后仍黑；实际窗口元数据affinity=0／cloaked=0，不能归因于保护设置。一次真实标题栏最大化后取得正确主页；随后真实阅读器设置开启自动匹配、真实底栏点击准确跳source125，屏幕第126/200页与目标JPEG可见。该临时fixture章未入DB，跳页后再次RESTORE_FAILED，尚未完成迟到认证路径。API_REFERENCE明确fixture不创建章节、持久化须预备真实身份，Repository缺归属时拒绝恢复；没有将临时默认选择当认证通过。正常shutdown202后两PID及HTTP监听退出，证据`windows-native/attempt-02/`。停实例后按既有Runtime.seed模式核空表，仅准备两组普通合成漫画/章节身份、未读page0，不写pairing/facts/Ready/reading events，FK及quick_check通过；用于下一轮相同production恢复路径，不改源码、算法或预算。

Windows `attempt-03`（同ce6候选，无源码变化）：已停profile02离线身份准备经独立只读审查通过，重新启动launcher24712→runtime36108、49185；实际新章恢复READY且autoSpreadMatching=true。第一冷章source125在伙伴126真实full HOLDING期间已绘出，release126后125的frame/nativeMount/mount/rootQuad保持；远宽图21仅ARMED未进入，不能作迟到匹配证明。第二冷章改用附近真实宽图123；首轮串联伙伴控制耗尽30秒控制时限，保留TIMED_OUT且不算通过。再用新随机内容token冷开同章，仅控制宽图123：规则7 full实际HOLDING期间，真实slider126/200、source125与126实际JPEG及Painter均已显示；规则8 prefix仍ARMED。释放7/8后full123真实完成37845 bytes；随后没有用户导航，currentPage仍125/sequence1，但currentDisplayPageIndices与visiblePageIndices均为空，最新实际窗口图片区域黑。此观察违反本迭代背景稳定要求，尚待production失败回归定位，不预判根因或称已修复。draw捕获3有dropped，不能作为完整逐帧区间证明；阶段JSON、实际Sky观察与控制记录分别保留于`windows-native/attempt-03/`。尚未执行前进纠正／回翻／尾部Retry，NB04继续未完成。停止observer、正常shutdown202后两PID退出且端口释放；后续先修此断点，不重跑Android或全模块。该TestMode入口的progressTracker仍为既有synthetic空写入，本轮不作SQL阅读进度通过声明。

Windows空显示断点的定向修正：`nb04-desktop-spread-handler-red` 1方法/1execution/1failure、0error/skip，真实HTTP200页／Skia／ReaderContent先绘125/126；调用生产后台setSpreadPages入口后，logical125、稳定frame及全像素bounds先通过，唯一失败是currentDisplayUnitId被清空。该JVM只证明handler缺陷，不冒称复现GPU黑屏或完整邻页HTTP异步回调。最小production修正仅ReaderScreenModel：后台spread集合直接更新，不再调用主动changePresentation清空链；方向／模式／自动布局／拆页／手调和真实geometry失效原入口不变，共享及Android生产未改，主代理独立审查通过。前三个邻页夹具尝试分别漏绘制observer或不能保证HTTP123进入普通解码，保留setup失败，不作为产品红因。

`nb04-desktop-spread-metadata-green` 6方法中新增handler及4个原生帧／迟到宽图／取消／快速反向方法首次通过，Default旧预期另行收敛；`nb04-desktop-spread-progress-green-fixed` 的handler与真实UI matching偏好RTL/LTR进度3executions通过。Default旧用例原matching=false、forcedSingle4且无manualPairStart2：实际普通几何可认证章首1/2，不要求original分析；修正旧2/3锚点假设，增加恢复READY/rev1、NotAnalyzed、Certified1/2及manual4仍单页的强断言，不修改共享门禁。纯自适应布局不追加阅读，与既有TEST_GUIDE一致；显式goToPage(2)新sequence后才报告新伙伴且去重。其辅助presentReadyPagerViewport漏setExposed(true)，导致Dual真实prepare拒绝，已按production beforeDraw顺序补齐该test-only协议；仍明确synthetic Ready非Skia像素。`nb04-desktop-visible-fixture-callers-green` 精确15个直接caller方法首次15/0failure/error/skip（Session1、Default1、ChapterTransition6、ReaderScreenModel7），完整关闭已发现关联失败；前述Native4／handler／UI双方向的有效通过证据按未变production复用，不重复全模块。累计本断点与关联传播21方法/22成功executions分批覆盖，非最终代码重新全量通过；`nb04-desktop-spread-format` 的4个Kotlin绝对target正常Apply+Check通过（16s），纯格式换行未改断言或production行为；Markdown UTF-8及diff空白核验通过。当前功能40文件（35Kotlin＋5内部Markdown），准备新预览及窗口复验，NB04未完成。

Windows `attempt-04`：新干净快照79f54f00184a582c2c3d0c764b50f433a7ba018d的preview构建1m21s通过，版本0.11.24.6.79f54f0，build/sourceIntegrity/productionRuntime均PASS；384文件272645976B，应用内容SHA256=ea6608371756eb9844171c0043af78043195dd0a8e4274c8f502eb57a25011f7。实际EXE在本worktree `app-desktop/artifacts/preview/windows/20261101T094827Z-4d46cc4c/Mihon-Desktop-0.11.24.6.79f54f0-unpacked/Mihon Desktop.exe`。使用已停任务profile02的合成配置副本profile04（191文件/5623747B），仅两组普通未读章节身份、配对记录0；不是用户数据、全新安装或升级迁移声明。核launcher4952→runtime8280、profile04及49187/49188端口。初始窗口捕获尺寸与实际命中不符，工具在业务输入前拒绝坐标；激活／最大化后以新观察续验，未将该拒绝算产品失败。新token在线200页真实slider跳125，126/200反馈和125126 JPEG可见；规则3 full123真实HOLDING时两图已绘，规则4 prefix仍ARMED；release后两规则实际COMPLETED、各37845B。current125/sequence1、currentDisplay/visible仍126125且nativeReason=ready；阶段Painter比较125及126的frame/nativeMount/mount/rootQuad/imageLayoutQuad/transform均相同，说明清选中缺口关闭。实际窗口图片区域却仍黑，额外观察未恢复；尚不能区分剩余显示与捕获原因，不声称GPU根因已证实。离线重读进一步确认：held/released/final末尾均为compose-frame-geometry，125/126的lastActualCommand分别一直为30573270486800/30573345034100，仅几何采样时间继续增长，不能称新真实draw命令持续；两页assetIdentity/contentIdentity各异且前后不变。现有API可通过重启观察器重新安装Painter，后续如采用只作诊断性强制重绘，不据此冒称稳定验收通过。draw记录有dropped，只作阶段命令端点对照，不作完整逐帧GPU证明。未继续前进纠正／回翻／尾部Retry，NB04仍未完成。证据`windows-native/attempt-04/`；正常stop observer+shutdown202，两PID退出且HTTP监听释放。下一轮先离线定位此剩余图像缺口，不重复已绿Android／完整模块。

后续离线证据校正：DECODE/VISIBLE_DECODE在Desktop管线decoder调用前发出，未区分FULL_PAGE或REGION_TILE；受控range=full只证明编码整读，不据此声称宽图123有可绘FULL_PAGE资产。现有直接raw事实/实际Painter才是相应证据。复用79候选的`attempt-05-render-trace`及暖app新token冷章`attempt-06-render-trace-warm`纯HTTP诊断均已正常关停并无监听；应用内completed Skia Picture记录工具独立于桌面捕获，固定1.8s窗口，而两次目标真实呈现分别约2.247s/2.405s，未覆盖关键目标／late阶段，故不作黑帧归因或原生验收通过。没有延长记录或匹配预算，也未为此重建候选。

剩余黑图离线诊断：相同普通JPEG的3次窄／宽屏向量均未取得产品红；首轮错误假定FULL_PAGE cache必含可见资产，第二轮错误强制123进入特定decoder，第三轮虽真实0→125/126且idle、资产旁证可读，但123 SPREAD事实没有发布，未发生现场宽图重排。没有放宽预算／强授需求，也未据此修改production。诊断源码、patch及三批XML/log已归档`.gradle-coordinator/nb04-desktop-identical-diagnostic-archive/`，正式Kotlin逐字节恢复9d6输入，git Kotlin diff为空；不将这些准备限制计作产品红或通过。

Windows `attempt-07-fullscreen-baseline`（相同79候选，带既有reader-render-trace，仅诊断）：核launcher10292→runtime31752、独立profile07和49191端口。Home最大化首次输入几何缺失，刷新后一次重试成功；未按旧坐标盲发业务输入。在线200页第1页先有白／蓝JPEG；随后仅启动draw观察器、尚未跳页，即出现图片黑区，current0/sequence0/selected0/visible0保持，第123页事件为0，两控制规则从未entered，超时仅ARMED过期。因此黑图不能只归因于迟到宽图或跳125。在此黑区按一次F11，图片立即恢复、后续观察仍可见；这是显式全屏／重绘干预，不算稳定验收通过。内部completed Skia Picture已保存（初始及F11前后，下一轮离线核对实际覆盖与内容），没有改记录1.8s时限。最后stop observer及normal shutdown202，PID与端口均退出；证据`windows-native/attempt-07-fullscreen-baseline/`。当前优先离线比较F11前基线及后续画面，核观察器／重绘／最终呈现层，尚未确定根因，NB04与本goal继续未完成。

Windows 后续证据校正：离线核对 `attempt-07` 的 `frame-014470.png` 及事件时间，发现该所谓 placement 前基线已经在 `reader-state fullscreen=true` 的重组中完成，并含全屏教学提示与正常白蓝图；它不是按 F11 前的黑区 Picture，不能据此将问题归到 GPU 或外部捕获。`attempt-08-no-observer` 使用同一79候选、独立合成 profile08，未启用 draw observation（active=false/captureId=0）或 render trace。真实滑块跳 index125／126页反馈，full123规则1真实 HOLDING 时125126双图可见；release 后 full／prefix 规则均实际 COMPLETED、各37845字节，current125/sequence1/selected与visible126125保持，但图片区域黑且补观察未恢复。标题、页码及教学提示文字也消失，而提示底色和滑块仍在；此观察提示需区分绘制与最终呈现层，不证明具体根因。故观察器并非黑图出现的必要条件，不以移除诊断开关当修复。未继续前进／回翻／Retry。证据位于 `.gradle-coordinator/windows-native/attempt-08-no-observer/`；launcher34708→runtime32624、49193/49194，正常shutdown202，进程及监听均退出。下一步需要真正状态切换前的 completed Picture 对照或可复现此现象的真实定向测试，NB04仍未完成。

为补齐上述区分证据，最小修正现有可选 render trace 的 F11 捕获切点：真实 ReaderViewport 按键闭包在模型全屏状态切换前调用既有 fullscreenRequest，后续 placement 捕获保留，未改预算、HTTP API、共享／Android逻辑或普通无诊断行为。`ReaderRenderTraceNativeTest.F11 captures the completed comic picture before changing reader fullscreen state` 经真实非聚焦／cloaked ComposeWindow、原解码器及真实Viewport闭包执行；`nb04-pre-key-trace-red` 1test/1failure/0error/skip，精确失败于 request 晚于模型toggle。最小实现后 `nb04-pre-key-trace-green` 新方法1/0（12.217s），原 `ReaderFullscreenTest.F11 key down toggles fullscreen and Escape exits fullscreen` 1/0（0.939s）；新增方法没有重试。原始PNG/events在 `.gradle-coordinator/nb04-pre-key-green-artifacts/`；根代理独立核XML、事件并查看真实红色PNG。cutoff34311632309400，baseline renderStarted34311621711500／captureStarted34311664644200，request34311669467599，模型toggle入口34311669730000；捕获与请求均早于状态切换，紧前state为false、后续为true。此次green在同一协调器调用列了两个精确过滤的Test task，偏离本roadmap“一次一个Test task”形状要求，实际仍串行；如实记录，不为命令形状重复测试，后续调用分别执行。两份Kotlin绝对目标的 `nb04-pre-key-format-apply`（14s）及 `nb04-pre-key-format-check`（13s）分别实际执行通过，非跳过；根代理复核 diff 空白通过。这只关闭诊断时序缺口，不证明Windows黑图根因或NB04已完成。

新候选源码 `d8674e947dbd7716957230fe4b739fda6f98800c`：首次 `nb04-windows-preview-pre-key` 的运行时版本门禁失败，期望d8674e9而实际4b1c6d7；生成BuildInfo源码已新，但JAR的AppVersion静态字符串仍旧，未将失败产物用于验收。限定Desktop compileKotlinJvm关闭本次增量并重编（`nb04-desktop-version-recompile`，3m29s，未跑测试），直接核编译类版本为d8674e9；同源码 `nb04-windows-preview-pre-key-fixed` 20s通过。preview目录 `20261101T110706Z-c979a040`，版本0.11.24.6.d8674e9，build/sourceIntegrity/productionRuntime PASS，原生层仍NOT_RUN；384文件272647486字节，内容摘要19296427f7c1fe5aa5549a9e55e304cf5f19838911c4c4f4a2787334dc6c90ca。

Windows `attempt-09-pre-key`（独立profile09、同候选、无draw observation、启用既有trace）：launcher13044→runtime11452、49195/49196。在线200页真实slider到125／126反馈，full123真实HOLDING时目标双图可见；release后图片及文字黑区复现，一次F11恢复。按键前基线frame4133的renderStarted35184102595800、captureStarted35184113133200，先于pre-toggle request35184129814100；reader-state变true在35184139169100，随后placement前基线frame4134。根代理下一轮离线查看 `frame-004133.png`，确认其中双图、标题、126页码及教学提示文字均完整且无全屏bubble，与外部捕获黑区不一致；frame4134已有全屏bubble。由此将剩余诊断范围缩到completed Picture之后的呈现或捕获环节，不能据此断言具体GPU／工具根因或默认Windows路径通过。trace169条capture及169条written、无gap类型；证据 `windows-native/attempt-09-pre-key/`。shutdown202，进程与端口均退出。前进纠正／回翻／Retry仍NOT_RUN；后续如进行替代渲染后端对照，仅为诊断，不替代默认候选验收。

Windows `attempt-10-software-compat` 使用同一d867候选及独立profile10，仅本次子进程环境指定 `SKIKO_RENDER_API=SOFTWARE_COMPAT`，未改持久配置。JDK `jcmd Thread.print` 的真实栈包含 `SoftwareRedrawer.renderImmediately`，确认软件路径实际执行。source0白蓝JPEG与文字先可见；尚未操作slider／F11、未启用trace／draw observation，随后图片与文字同样消失，仅提示底色及slider保留。current0/sequence0/selected0/visible0，123 full／prefix两规则仍ARMED且enteredNanos为空。由此排除“仅D3D”与“必须迟到123才出现”的解释，不证明实际窗口或Sky捕获哪层有错。launcher15100→runtime33736、49197/49198，shutdown202，进程及端口均退出；证据在 `windows-native/attempt-10-software-compat/`。本轮没有继续输入，也不将替代后端算作默认候选验收。

为区分内部Picture与窗口呈现，复用既有 `reader-compositor.exe` 窗口帧工具补一条真实阅读器诊断方法 `ReaderRenderTraceNativeTest.real reader bitmap and title survive idle and metadata in window capture`；只有测试及说明变化，未改production。真实ReaderViewport／解码PNG／原TopAppBar标题、trace及observer关闭、屏幕外非聚焦窗口，actual3844×2101／scale1.5。最初两轮分别败于错误的主题色文字预期、按宽度比例裁掉短标题的ROI，均为准备失败，未进入静置／元数据阶段，不计产品红。改为实际白标题固定dp范围、无条件保存首帧后，`nb04-real-reader-wgc-idle-roi` 1test/0failure/error/skip，11.698s；首帧36831698653600，metadataAt36840031639800，后置帧36840304396600，首帧后8.605743s／metadata后272.7568ms。五个收到的真实WGC帧均red639790／titleGlyph765；只保存首帧和后置PNG，根代理独立核XML、时间及两图，确认漫画、章名、页码和图标完整。没有逐tick强制重绘或系统输入，helper已退出。证据 `.gradle-coordinator/nb04-wgc-idle-roi-artifacts/`。该三页原生向量通过，不替代200页实际用户路径，不据此宣布Sky或GPU根因；后续应将既有独立窗口帧诊断用于同一候选的现场路径作交叉核对。

上述新增测试的单文件格式由 `nb04-real-reader-wgc-format-clean`（14s）覆盖：Apply实际执行、Check为UP-TO-DATE，目标仅该绝对Kotlin路径；先前两次最大行长格式失败已关闭，未为纯字符串换行重跑产品测试。`git diff --check` 通过。诊断图片、原始帧统计与XML继续只作过程证据，未扩大到完整模块测试。

### A01～A12 定向证据索引

以下定位到上述累计批次中的真实断言；未受后续差异影响的绿结果按输入复用，不称最终代码重新全量通过。Windows候选窗口的清选中状态缺陷已修复，独立窗口帧证实迟到匹配前后画面完整且稳定；attempt-13补齐主动双页相位纠正及回翻。NB04仍缺Windows真实Retry恢复证据。

| 验收项 | 当前有效断言入口与批次 |
| --- | --- |
| A01 首图先显示 | shared `cold 126 can draw with only its geometry while analysis and restore remain pending`；Desktop ResourceWiring在线200页方法／`nb04-desktop-scheduler-bridge`；Android `onlineTwoHundredColdJumpPaintsBeforeRequiredFactsRtl/Ltr`及HTTP四到达顺序／ART09两方向 |
| A02 逐槽与重试 | Desktop `partner error and actual retry button retain the current pixels transform mount and frame`；Android `realHttpDualRtl/LtrHeldTargetStillPaintsPartner`、`realHttpDualRtl/LtrHeldPartnerStillPaintsTarget`、`realHttpDualRtlRetryTargetWhilePartnerRemainsHeld`（`nb04-http-lease-boundaries-green`）；ART09真实503／UI Retry |
| A03 迟到纠正 | shared `late reliable regrouping preserves pixels then overlaps boundary on both turn directions`；Desktop `late original phase corrects on a native turn without moving pixels or losing an unpainted boundary`；Android `latePrefixGeometryNavigatesToTheExactOverlappingIncomingNativeFrame`；ART09宽图21到达与主动前进纠正 |
| A04 未绘伙伴 | shared `unpainted partner is activated once then can be deliberately left in default and certified frames`；Desktop actual forward drag；Android `realPagerSwipeActivatesItsUnpaintedPartnerBeforeLeavingTheGroup`；ART09实际按键停靠／离开／回翻 |
| A05 重叠与生命周期 | Desktop cancel／rapid native reverse两方法；Android `independentNativeMountsKeepTheirSiblingCapabilityAfterDetach`、`temporarilyHiddenCurrentNativeFrameRetainsItsLockedPixelsForCancelledMovement`、`realPagerCancelledMovementAndRapidReverseKeepExactLogicalFramesWithoutUndrawnReading`；共享独立lease契约 |
| A06 降级可读 | Android `requiredPreparationDeadlineCancelsItsLoaderAndRetriesAtTheSameLocation`在RESOURCE下通过普通PageLoader真实绘制（`nb-integration-edge-resource-green-import-fixed`）、`originalAnalysisFailureKeepsReadyNativePixelsWithoutManufacturingImageError`；Desktop `dual ready source paints immediately when optional header is unavailable`；物理取消边界见HTTP租约批次 |
| A07 空图与一次停靠 | shared `empty frames and exact trailing jump advance by the source anchor`及DEFAULT／Certified未绘伙伴矩阵；两端真实pager停靠／离开／取消；ART09未绘126无假进度且再次输入可离开 |
| A08 方向／边界／宽图 | Android lateWidePartner、encoded普通／镜像tiles及edge真实LEFT Single／RIGHT Pair；Desktop late wide requested page及最终pan焦点方案；共享封面／奇尾／宽图契约；ART09两方向leading槽与真实宽图reset |
| A09 手动恢复 | AndroidChapterPairingPersistenceWiringTest的`dual reading activates and source navigation remains available while original restore is held`、`real unknown partner adjustment button remains legal without disabling matching`、`restored manual boundary survives a late wide image dimension callback`（整合基组首次绿）；共享cold126恢复pending及已记录手动意图定向回归 |
| A10 身份／旧回调 | shared close／rebind／独立挂载契约；Android `unrelatedContentRebindRetainsNativeFrameAndOldNeighbourCloseRefreshesDeferredContent`；`nb04-viewport-identity-green` 5个真实拒收向量、opaque旧frame可合法再激活及HTTP实际退出边界；Desktop过渡／取消／退出资源 |
| A11 真实进度 | Android `actual VM appends partial viewport progress without replaying page selection`、`actual partner callback can persist while logical target has an image error`、`healthy partial image records itself while failed final partner cannot complete chapter`；共享progress契约；Desktop SQL进度；新增主动意图六方法复验与ART09后台tuple不变／主动视口正确保存／未绘不记读 |
| A12 单页／条漫／认证 | Android `ordinary single page actual pixels settle each page through the activity`、`webtoon holder production observer executes loading error and loaded states`、`uncertifiedOriginalNeverPreparesCertifiedNativeSlot`（整合基组首次绿）；原认证缺证据反例以`nb01-regression-green`中的`sixteen observed control pages cannot certify an unseen prefix reset`及`display dimensions alone do not become original certification evidence`为准 |

Android 完整原生路径证据以当前 worktree `.gradle-coordinator/android-native/attempt-09/` 为准；早期 attempt-02／03 保留历史失败及校正。这些内部运行文件不作为正式交付产物。Windows preview 实际窗口证据以attempt-12／13为准：迟到宽图后选中及画面保持，主动纠正和回翻已执行；尾部Retry恢复仍未确认通过，复选框继续保持未完成。Sky图片／文字黑区与独立窗口帧完整的差异单独记录，不据此外推显示扫描输出或具体工具根因。

交接保留本次精确 HEAD／diff、实际运行中的进程和协调器 key；进程仍运行时先接续观察，不重启同一任务。模型或会话切换不清除已消耗预算和未完成门禁。后续接续本 goal 时，沿已授权范围核对现有差异、最近证据和仍存活的执行进程，继续未完成门禁；不因复选框等待统一提交而重做已完成的 NB01，也不再次把已确定的用户优先级当开放问题。

Windows `attempt-12-stage-snapshots` 离线核对：同d867候选、默认后端、无trace／observer；launcher26144→runtime18796、49201/49202、独立profile12。在线200页1200×1800 JPEG、wide123为1800×1200；真实slider到index125，full123真实HOLDING期间125126已绘；release后current125/locationSequence1及visible126125不变。held与released窗口PNG均SHA256=fbb1a22697c56fc446f16e38556108df7aeaddce2527136345b57a904616e6ca，逐像素diff为空，漫画／标题／页码均完整；released阶段2秒窗口帧区间1793534110592～1793534112634ms包含Sky观察1793534111189～1793534111306ms，Sky黑区而独立同窗口帧完整。因此先前“实际图片消失”的说法须限定为Sky捕获观察，不能继续据此断定产品黑屏；具体捕获差异原因仍未证实，也不宣称显示扫描输出已观察。七阶段窗口捕获received/written分别99、99、99、98、98、62、96，missing均0且failures为空。真实NEXT到127 Single、PREV到126 Single，无源页遗漏；该夹具左蓝条满足原有LEFT页侧采样，不能把此路径冒称A03指定的126127新双页纠正。规则2 prefix123未entered，后来ARMED到期，不称实际读取完成。尾部index180的规则3实际HOLDING时181已绘，fail同一encoded读取后target180真实Retry错误槽、伙伴181保留；尚未点击Retry，恢复项未完成。中期检查时正常shutdown success，两个精确PID退出、端口释放。下一轮仅补无页侧信号的真实小JPEG迟到宽图纠正及UI Retry；原尺寸首绘／像素稳定证据仍保留，既有A03生产原生focused断言不重复。过程证据 `.gradle-coordinator/windows-native/attempt-12-stage-snapshots/`，NB04继续未完成。

Windows `attempt-13-phase-retry`：同d867默认候选、独立profile13（复制已停profile12，383文件，非新安装）、launcher34820→runtime33644、49203/49204。沿用真实online200夹具，仅图片尺寸改60×90、wide123为90×60，依据production ReaderPageSideHints小于64不采样，消除旧夹具蓝条的页侧信号，不注入facts／Ready／配对。真实slider到125，full123 HOLDING时125126已绘；release后full／prefix实际COMPLETED各880B，locationSequence1及visible126125保持，独立窗口帧漫画／标题／页码完整。真实NEXT到127、sequence2、visible127126；真实PREV到125、sequence3、visible125124，完成A03指定相位双页与回翻代表路径。真实slider到180，规则3 full180 HOLDING且181先绘；fail同一encoded端口后180显示实际“无法加载页面／重试”而181保留。Sky在最新错误截图按钮中心1920,729派发一次点击，刷新及独立retry_done窗口帧仍保留错误槽，productionEvents中180只有初次OPEN_PAGE／HEADER_READ，没有新增目标读取；尽管mountedReader.visible集合随后含180，不将该集合代替恢复像素或新读取证明。Retry未确认通过，禁止据此NB04勾选。retry_held窗口捕获64received／63written／missing1，pixel-queue-budget-exceeded，状态PARTIAL；已保存帧仅作阶段观察，不作完整逐帧证明；held／released／next／back／retry_error／retry_done分别100／99／100／100／97／100帧，missing均0、failures为空。正常shutdown success，两个精确PID退出、两端口释放；过程证据 `.gradle-coordinator/windows-native/attempt-13-phase-retry/`。下一轮离线核对实际输入与Retry production接线，若有产品红则补真实失败测试后最小修复，不重跑已绿矩阵。

Windows Retry离线补验（production基线af3deb3051439dc58676d2030197759689a18a88，与d867候选production相同）：发现既有NativeStable伙伴重试方法在真实按钮回调后由测试replacePage手动给Ready，不能独立证明runtime重新读取。复用SharedSpreadDesktopResourceWiringTest真实HTTP／factory／session／Skia／ReaderContent／ComposeScene新增两个精确方法：`real retry button rereads failed encoded partner and restores pixels without replacing drawn buddy`、`matching retry button survives a frame and recovers actual encoded read failure`。普通坏编码及matching=true＋既有ReaderTestModeController实际full读取IOException两向量，均通过真实Pointer Press→一帧→Release，spy仅记录并callOriginal；断言准确target／callbackCount1、新attempt、新OPEN_PAGE、实际decoder、目标色像素、错误按钮消失、伙伴bounds及frame保持。首轮nb04-desktop-actual-retry-red在点击前按钮取值发生NoSuchElement，属测试准备：过早开放成功响应使错误按钮先消失，不计产品红；修为实际按钮回调才解除普通坏编码故障。nb04-desktop-actual-retry-held-red实际1test／0failure/error/skip（3.466s），后续nb04-desktop-retry-frame-io-red虽沿用red命名，实际2test／0failure/error/skip（3.668s），并未取得产品红或修改production，不据命令名虚构红绿修复。根代理独立核XML和真实接线；下一步仅补原生按钮的新attempt进入与错误恢复，现有候选可复用，无需重建。

上述补验最终单文件格式nb04-desktop-actual-retry-format通过（17s，Apply执行、Check UP-TO-DATE，目标仅SharedSpreadDesktopResourceWiringTest.kt）；diff空白核验通过。XML归档 `.gradle-coordinator/nb04-desktop-retry-artifacts/{setup-red,malformed-green,frame-io-green}.xml`。实施者已冻结并归还Gradle／写入权，所有对应协调器进程、HTTP测试服务器及Scene均退出。本轮没有产品实现变化，故不重建preview、不重跑原已绿双端矩阵。

Windows `attempt-14-retry-native`：同d867候选，独立profile14、launcher13448→runtime37612、49205/49206；仅补原生Retry，online200恢复1200×1800，无新宽图，迟到纠正证据仍取attempt13。首次滑块输入被工具判定落在Explorer而拒绝，业务未执行；激活／最大化后用新观察校准，规则1未entered而ARMED到期，不算产品读取超时。真实滑块落index179、display180179，规则2 full180真实HOLDING／attempt0，179先绘；fail后左180真实错误槽、右179保留。规则3在窗口观察期间未entered到期，随后一次实际按钮点击未确认恢复；再arm规则4后以最新可见按钮640,729再次点击、并作一次Return诊断，规则4仍ARMED／未entered，实际新attempt未证明，独立retry_done窗口帧错误槽仍在，visible却含180179，NB04仍不通过。所有输入均Sky；独立窗口帧只复用既有capture-stage工具，无F11干预或配对事实注入。已保存held-state／held-controls、after-click、second-click、after-return和final-state，后续先离线检查候选实际编译接线／事件记录边界与production链路差异，不重复已绿focused来替代现场。正常shutdown success、两个精确PID退出且49205/49206监听释放。证据 `.gradle-coordinator/windows-native/attempt-14-retry-native/`。

Retry候选离线校验（当前production c00815b41c5cf3a551e3ca92b9f334c7d517633b）：d867候选app-desktop-jvm-448af47b758ea12096f0bb40c16fabda.jar与本worktree刚执行成功的app-desktop/build/libs/app-desktop-jvm.jar中，reader／ui.reader／ReaderTestModeController共646个class逐字节完全相同；domain候选jar与当前domain-jvm.jar中mihon.domain.reader共399class亦全同。factory class SHA256=106f69d73a09d11e705137a9663280ee55b7887c34ca687d8133697060dbb67c，model class=a2f14c95344cc79798c99e69b6c67628bbfc86ecf806c2e09841671a061b396f。排除此范围旧编译内容，不因怀疑增量而重建。attempt14 final-state实际仅36条productionEvents，record没有截断上限，非事件ring溢出；stderr只有既有SLF4J绑定缺失／Netty说明，没有本次Retry异常栈。独立held／retry_done窗口帧分别100／99且missing0/failures空。新增测试尚只ReaderContent，实际ReaderViewport另有父层焦点、toolbar及教程，下一项最小补验固定为同一IOException场景完整ReaderViewport与精确Retry文本语义，不先修改产品或新增矩阵。

Retry完整视口差异补验：`nb04-desktop-retry-full-viewport-red` 虽保留red命名，实际2test／0failure/error/skip（4.650s，完整ReaderViewport＋matching＋实际IOException 4.230s、普通坏编码0.419s），root独立核归档full-viewport-green.xml；真实父焦点／toolbar／教程、MR Retry文本精确语义、Press→一帧→Release，原目标回调一次、新attempt／OPEN_PAGE／实际解码和像素／错误消失／伙伴几何及frame保持均经过。未取得产品红，未改focus、overlay或其他production。单文件格式nb04-desktop-retry-full-viewport-format通过16s，Apply执行／Check UP-TO-DATE。与现场差异仍未解释；下一项限定为现有reader/state中最多3页的model／runtime attempt和load-state只读标量，用真实HTTP红绿补观测，不增加控制动作、图片内容或配对注入，随后新候选仅重试现场。

有界图片尝试观测：现有GET /test/reader/state的mountedReader新增imageAttempts，仅显示单元最多2页＋logical去重，分别读model与runtime尝试编号/短加载状态；full ReaderPageId、generation与chapter一致才关联runtime，不含异常、URL、文件内容。`nb04-desktop-image-attempt-http-red` 1test／1failure／0error/skip（3.607s），准确缺少imageAttempts，未挂载null及真实挂载/伙伴绘制前置通过；最小实现仅TestState／TestHttpServer。`nb04-desktop-image-attempt-http-green` 1test／0failure/error/skip（4.753s，构建3m20），复用真实factory／完整ReaderViewport／IoControl及embeddedServer，覆盖真实Retry后递增、有界去重、runtime实际activate换章不误配、无runtime/null及卸载mountedReader=null。根代理独立核XML和生产只读实现；两层分别采样，瞬态差异不视为缺陷。

用户已说明此前未值守，肉眼图片／文字显示项NOT_OBSERVED；不得把无人观察当作产品黑屏或工具错误的证明。用户主动提出人工验收，并明确要求环境就绪后暂停、完成后再继续。因此本轮仅完成上述已启动测试／格式、新preview及隔离错误页准备，不再执行Sky输入，不继续重复原生验收；人工结果返回前NB04与goal均不完成。

图片尝试诊断三文件格式nb04-desktop-image-attempt-format通过18s（3绝对target，Apply执行、Check UP-TO-DATE）；diff空白通过，HTTP红绿XML归档在 .gradle-coordinator/nb04-desktop-image-attempt-artifacts/http-{red,green}.xml。实施者已冻结并归还写入/Gradle权，无遗留测试/窗口进程。

人工验收环境已准备（用户明确要求就绪后暂停）：新干净快照dc86ee0053057498ecfb7a29b290b98f642289b8的preview版本0.11.24.6.dc86ee0，key nb04-human-retry-preview通过1m03s，build/sourceIntegrity/productionRuntime均PASS，nativeInteraction清单保持NOT_RUN。目录app-desktop/artifacts/preview/windows/20261101T125926Z-34beb4c0，384文件272652806B，内容SHA256=a57dc5e0888877a9f2e5bd6dc212fe0b45a08d23550e694af03a28cc074de756；仅新增TestMode只读诊断及测试/文档，阅读业务和Android未改。此前一次文档尾部空行被diffcheck指出，现已去除；不以该纯文档修正触发重建。

人工隔离实例保留：manual-acceptance-01/profile，launcher32052→runtime41896，HTTP49207/JMX49208，精确exe/profile/父子关系已核；session.json记录可安全关停的归属。第一份1200×1800输入在目标179首帧前得到合法Single，无错误按钮，未交付为验收环境；随后同一app新token改用既有60×90真实JPEG以免页侧信号、在线200页，程序化setup到index179（仅场景准备，不冒称原生滑块验收）。owner2的规则2 full180在实际HOLDING且179已绘、display180179之后fail；左180真实错误/Retry，右179正常蓝边白图，独立owned-window帧held/window/window-000001.png已核，未启Sky、trace或draw observer，也未点击Retry。两秒帧helper自然结束，所有控制规则均已结束，无持续采集/自动输入。pre-human-state及controls已保存。用户只需在Manual Retry acceptance窗口点击一次左侧重试，报告图片是否恢复、右侧是否移动，以及肉眼有无黑图/文字消失；人工结果未回前不判NB04通过。窗口按用户人工验收意图保留，用户可用标题栏关闭，恢复会话后先核session.json及现有进程，不重启同一场景。

人工验收01结果：用户恢复会话并明确报告“点重试没有任何反应”。根核原实例仍为41896/32052，post-human-state实际logical locationSequence6、目标180的modelAttemptGeneration=runtimeAttemptGeneration=7、两层Error；伙伴179两层0/Ready。productionEvents180仍只有初始OPEN_PAGE/HEADER_READ，读取规则均已FAILED、无挂起。由此确认按钮重试已到达底层，不能继续解释为Sky未送出点击；人工可见恢复失败。已保存user-result.json、post-human-state/controls后正常shutdown success，精确PID退出且49207/49208释放。

源码定位到真实验收源与先前成功夹具的差异：ReaderTestModeOnlineSource.getImageUrl委托ReaderTestModeController.resolveOnlineImageUrl，但后者只接受PARTIAL_DOWNLOAD，普通ONLINE重试强刷清imageUrl后返回null；SourcePageFetcher.resolveImageUrl会转为MalformedData，没有进入encoded OPEN_PAGE。先前成功测试用ReadyWindowSource.getImageUrl直接给URL，不能覆盖这个源入口。下一项先用真实ONLINE source/SourcePageFetcher及HTTP回归验证红因，再最小修复TestMode源解析并保留partial/过期身份边界；不修改共享或普通reader算法，不重跑已绿矩阵。

真实测试源刷新回归红已确认：`nb04-testmode-online-refresh-red` 新增ReaderTestModeHttpTest方法通过现有embeddedServer、实际controller.onlineSource/SourcePageFetcher先取真实JPEG成功且HTTP计数1，然后清空同Page.imageUrl，用新destination重新fetch。实际1test／1failure／0error/skip（0.483s），准确失败于要求强刷返回Success，实际Failure；不是编译或准备错误。该证据关闭“仅按钮未命中”的解释，并与人工两层attempt7/Error一致。修复限定controller的ONLINE/PARTIAL allowlist及当前chapter/token/规范页路径校验，后续选择新HTTP、身份边界、既有partial正常/离线四个精确方法，不扩大矩阵。

真实在线源最小修复：resolveOnlineImageUrl接受ONLINE及PARTIAL_DOWNLOAD，精确校验当前chapter/token及规范page路径；partial PREPARING门禁仍只用于partial。`nb04-testmode-online-refresh-green`因新边界用例误用单页standardSpec并取index1，在测试执行前精确取消（CANCELLED/130），改为显式3页；不计产品失败或测试执行。`nb04-testmode-online-refresh-green-fixed` BUILD通过3m12s，4unique／4executions／0failure/error/skip；真实HTTP强刷方法1/0（0.503s），当前来源／规范地址边界与既有partial准备／离线三个方法3/0（0.154s）。root独立核XML与最小production diff，真实源重新取图并通过Skia像素解码，旧token、异章、负数、越界、非数字、前导零、query、额外路径、image endpoint误作page及本地来源均拒绝。未修改普通reader、共享、Android或资源预算；先前按钮→runtime/呈现回归继续按未受影响差异复用。

在线源修复三文件限定格式nb04-testmode-online-refresh-format通过15s，Apply实际执行、Check UP-TO-DATE；diff空白检查通过。XML归档在 .gradle-coordinator/nb04-testmode-online-refresh-artifacts/{red,http-green,controller-green}.xml。实施者已冻结并归还写入/Gradle权，所有测试HTTP/manager/协调器进程已退出；接续仅新preview和人工验收场景准备。
