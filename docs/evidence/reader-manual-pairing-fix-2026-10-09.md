# 章级开篇与手动双页修复

日期：2026-10-09。状态：实现、定向验证、独立复审、签名候选及隔离升级完成；两部漫画的用户真机验收待反馈。

后续用户反馈撤销专用开篇菜单，明确保留自动配对规则。前文code63实现和菜单验收清单保留为历史证据；当前要求及后续产物以本文末节为准。

## 范围与基线

本轮用户授权同时修复额外封面导致的开篇错位，以及手动调整受导航历史影响的问题，交付带事件诊断日志的 Android APK，由用户自行验收。隔离分支 `codex/reader-manual-pairing-fix` 基于 `d52fa599c92287fd8a9fe8c068a3c1a4610ec96c`，不启动原开发会话，不改变其工作树。原设备候选为 `0.19.4-aex.44 / 62`，APK SHA-256 `98358cf03f6673a4fbcf3774323aabaf11641f0578a71be578cdaa2813c3d925`，已通过 ADB 只读核对实际 APK 哈希。原签名、正式包名及数据库升级连续性必须保留。

本轮属于日常定向修复，不运行完整模块测试、全量矩阵、Windows/macOS 正式发布或其他 roadmap 收口。交付 APK 不代表真实图源、所有设备或全部阅读器行为已通过。

## 用户案例与取证

以下使用界面一基页码；底层日志 `source`、`slots` 为零基索引。

- 漫画柜《不死之身的斯黛菈公主》第14话：用户说明第1张为单行本封面、第2张为正文第一页；期望 RTL 首屏第2张在左、第1张在右。该话原图未由本轮读取，不能宣称自动识别成功。
- 漫画柜《平行天堂》“番外篇 修车解咒”：用户在已连接设备复现并确认自动匹配开启。15:05:01–02曾呈现3–4；15:05:04调整后为2–3；15:05:06–07先去4–5再返回2–3；15:05:08调整后第3页独占，原因为 `MANUAL_BOUNDARY`；15:05:09–11旧4–5仍保留，返回第3页仍独占。相关侧提示均为 `UNKNOWN`，不能将本次故障归因于白边识别误判。
- 日志和源码共同指出：手动调整没有独立确定配对方向；新目标页的旧伙伴归属未完整释放；间接产生的单页不属于 `forcedSinglePages` 时，调整函数直接无操作。修复不能只清空整章历史或重置方向掩盖持久意图缺失。

原始日志仅存工作区外临时诊断目录，不提交设备序列号、用户库或漫画图像。日志采集已经停止，未操作设备界面、清库或重装。

## 本轮行为契约

本节为 PROJECT_POLICY，覆盖旧设计中“只持久化独占边界”的限制；仍复用共享 presentation owner、ChapterPairingRepository、CAS、应用级保存协调者和原进度链，不新增另一套配对引擎。

1. 章级开篇提供自动、首页独立、首两页并排；手动首两页并排在 RTL 为第2页左、第1页右，LTR 镜像。只影响当前章；真实宽图/正方独占图、少于两页及失败/未决事实仍受结构和安全约束，不裁切或伪造图片。
2. 手动调整2–3，明确选择第3页与第4页组成新双页，不依赖刚才前翻或回翻、缓存到达顺序和既有4–5历史。取消与新组合冲突的旧归属，保护无关区域和阅读位置。
3. 显式手动关系优先于自动页侧与边缘评分；自动功能可以帮助未手动指定的区域，不得悄悄覆盖用户已保存选择。单页需要可执行的调整/恢复路径，不能无反馈地陷入无法操作状态。
4. 保存成功后才发布持久布局；失败保旧值并反馈；CAS冲突、切章、关闭及迟到回调沿既有身份/序号门禁处理。布局调整不伪造阅读，页码/书签/源页身份不重新编号，不复制、删除图片。
5. 旧格式记录兼容恢复；新字段默认保持原行为。新记录同章重开应恢复同一显式关系。页面数改变时保留现有无效记录处理；相同页数但源内容重排无法可靠识别仍是既有限制。
6. 本机保存，不自动同步或进入备份；不将旧数据降级、清库或更换应用身份作为升级手段。

## UI 与诊断契约

沿用阅读器原生菜单/弹层和章级保存反馈，不新增独立设置页面。仅双页相关入口触发；选择、保存中、失败和恢复均可理解。非持久临时阅读仍须明确本次有效。Desktop消费共享语义及兼容存储，本轮Android之外的原生交互不冒称已验收。

Release保留有界事件日志：手动操作请求、当前显示页组/逻辑目标、期望关系、保存结果、拒绝原因及新旧布局。禁止逐绘制帧刷日志；不记录漫画名、章节名、图源URL、图像、账号、令牌或设备标识。记录页码采用明确统一的索引约定，便于将用户所见与日志对应。采集在用户复现前开始，避免有限logcat环形缓冲覆盖证据；不通过开启全局网络详细日志替代配对诊断。

## 固定验证与交付边界

- focused TDD：实际共享session/presentation执行两案例；前翻/回翻、已看旧伙伴、单页重试、自动开关/相反页侧提示、重开恢复、真实宽图和单页章节。
- 持久化专项：真实SQL及旧schema升级，旧format兼容、非AUTO空边界记录、非法/冲突关系、事务失败、CAS及迟到回调保护。
- Android接线：实际操作入口调用共享命令，保存/重载链路及日志事件；Desktop受影响接口与现有调用者编译/定向回归。
- 独立审查：先核验高风险模型/存储接口，再聚合检查完成实现与证据；不因任务完成重复全量。
- 原签名统一candidate构建和verify；用户负责两部漫画的真机体验。不会在用户设备自动安装或执行验收。

本轮变更超过8个文件/400行，是同一章级手动布局能力的共享模型、SQL格式迁移、Android入口与真实持久化、必要Desktop接线及其测试；按可独立验收的能力保持一个内聚提交，不按文件拆分。高风险持久化和恢复路径由未实施者独立审查，最多一次聚焦复审。

已获得的组合证据：真实旧schema42的193条完整DDL创建旧库并执行42→43迁移通过；保留format1并验证format2读写。Desktop模型9项、真实SQLite Compose6项通过。初始共享原始缺陷两项、真实Android开篇入口，以及后续恢复/未知伙伴边界均有实际失败证据；新增模型/存储测试有首跑即绿的部分，`manual-opening-red`也实际通过，不把它们冒称为红测。最终结果和候选产物在收口时记录。

## 人工验收操作

- 安装候选后进入《不死之身的斯黛菈公主》第14话，使用RTL双页，点击底栏“章节开篇”→“首两页并排”；期望第2张正文在左、第1张封面在右。退出再进入保持选择；切到其他章节不沿用本章选择。
- 在《平行天堂》“番外篇 修车解咒”保持自动匹配开启，先从2–3翻到4–5，再返回2–3并点击原“调整页面配对”；期望3–4成组，继续翻页/返回不恢复旧4–5冲突。若进入时保留旧的第3页单页状态，调整应有可执行结果，而不是永久无操作。
- 退出章节后重开，检查保存的开篇方式和手动配对；真实宽图继续完整独占，调整本身不制造阅读进度。
- 配对诊断继续使用Release INFO日志，标记为 `DualPageManual`，与已有 `DualPagePresentation` / `DualPageNavigation` 结合分析；源页字段为零基。须在复现前开始采集并保存到主机文件，Android环形缓冲不保证事后仍保留全过程。本轮不新增常驻日志服务、网络详细日志或漫画图像归档。

## 验证中的基线问题与关联修复

独立未修改的 `d52fa599c9` 工作树用三个精确测试方法复现 `late competition`、`committed reservation`、`explicit viewport event` 的自动布局/旧翻页失败。这三项保留为既有未修复问题，不通过改期待值或全量重跑消除，也不宣称整个阅读器测试全绿。

Android的默认阅读→明确重试恢复测试在旧基线也失败。该问题与本轮持久意图恢复直接相关，因此纳入最小修复：区分用户明确恢复请求和后台恢复；成功时只有仍拥有当前定位的明确请求可以解除相应旧布局，过时结果继续保护当前可见画面。不能在测试里手动释放实际界面会复用的holder来获得通过。


## 最终验证与交付

| 范围 | 实际结果与证据 |
|---|---|
| 最终共享手动/恢复 | `manual-legacy-green`：手动13、原调整helper3、late restore/save2，18项通过 |
| Android最终补验 | 同key：旧default→Retry、新开篇真实菜单、普通held恢复、初始Retry，4项通过；复用此前23项通过中未受变更影响的结果 |
| 存储与迁移 | `manual-final-focused`存储/兼容26项通过，`manual-last-integration`完整旧DDL迁移1项补验通过，合计27项组合证据 |
| Desktop | `manual-last-integration`模型9、真实SQLite Compose6通过；最终key对受影响的held与save各补验1项 |
| 格式 | domain/data/app适用Spotless检查通过；Desktop未配置同名Spotless任务，不声称跑过；所有本轮文件`git diff --check`通过 |
| 独立审查 | 首轮发现恢复与迁移证据问题；唯一聚焦复审确认阻塞关闭，PASS |
| 候选 | `python scripts/build-android.py candidate --offline`成功，Gradle构建2分28秒；统一verify签名、哈希、版本及清单通过 |
| 原生升级 | API36 x86_64隔离AVD原签名code62→63，真实旧APK建schema42后加入合成记录；新版production启动执行42→43，旧format1边界/revision、书签和阅读位置保持，SQLite integrity/外键检查通过 |

这里的测试结果是基线加相关补验的组合证据，不表示最终代码重新运行了完整模块或全量测试。最初三条已证明存在于d52的自动呈现测试失败仍未修复。新增开篇和format2的部分测试首跑即绿属于TDD时序缺口，已如实记录，不伪造失败证据。

原生验收使用新建无窗口AVD `mihon-manual-pairing-20261009`，只通过其明确ADB serial操作；实体设备未安装或操作。首次升级实际安装成功，但安装后拉取APK校验发生一次ADB传输中断；一次重试确认相同APK已经安装，哈希/签名一致，未重复改动应用。首次安装时间和数据库UID保持连续，冷启动`Status: ok / COLD`，进程crash缓冲无崩溃。证据在本工作树 `.gradle-coordinator/manual-runtime/upgrade-result.json` 与数据库副本、包信息和运行日志中；已关闭本次模拟器。此项不代表真实漫画图源或双页视觉已由代理实机验收。

交付：`0.19.4-aex.45 / versionCode 63`，包名`app.mihon.desktop.fork`，沿用原证书，Release/R8开启，非debuggable，含原四种ABI。配对事件使用INFO日志；R8后的DEX中保留`DualPageManual`、`DualPagePresentation`、`DualPageNavigation`诊断标记。未增加永久文件日志，所以用户复现前仍需启动实时ADB采集。

- [验收APK](../../app/artifacts/android/0.19.4-aex.45-vc63-d52fa599c9-release/Mihon-Fork-0.19.4-aex.45-vc63-release-universal.apk)
- APK SHA-256：`e99e734bc1b3b8e01be77e6f412d2a7819210303b1b7480dbd4a756091ddbe71`
- 产物清单记录基线`d52fa599c9`和冻结的未提交产品输入；productionInputs SHA-256：`23f361c3866cded0dd9179c7e5d65bbbc57bf4d8894fd4c8aaa7c0d5dce4541f`。产品源码构建期间未变化，随后只补充验证记录并统一提交；不能把清单基线误解为未包含本轮修复。
- 手动边界仅在可合法配对的相邻页生效，真实宽图不裁半；选择章节开篇会定位至本章开头。其他章不继承本章选择。Desktop只覆盖共享接线与自动化测试，没有增加Desktop开篇菜单或声明原生交互验收。

## 后续撤销专用菜单、统一通用调整（2026-10-09）

用户验收code63后要求砍掉“首两页并排”专用功能，进一步明确选择“只删除专用菜单，保留现有自动配对规则”。本批移除Android底栏整个“章节开篇”菜单及仅服务该菜单的Activity/AppBars/ViewModel/Viewer回调、三个语言资源中的四个未使用文案。自动首两页规则和其它自动配对原样保留；所有手动操作仍通过原“调整页面配对”按钮。不是删除首两页合法并排能力，也不是重写配对引擎。

正确红同时证明：旧AppBars仍显示专用菜单；首页单页使用普通调整按钮无操作。最小共享实现仅补pageCount已知、首页单页且至少两页时的普通调整，使用原FIRST_PAIR布局表示，去掉与0/1冲突的边界1及手动pair start1，保留无关区域和当前源页0。拆分及恢复复用code63已有分支；单页章/真实宽页由既有session约束拒绝非法拼接。schema43、format2、opening_mode字段、旧保存记录/CAS/恢复链均不改，不清数据。内部openingMode保留用于现有保存和通用调整，不再是独立用户入口。

TDD与相关回归：domain两个新行为正确失败后最小实现变绿；Android两个真实Compose事件用例分别因旧菜单存在、通用按钮未保存FIRST_PAIR正确失败，修复后通过真实点击→原VM/Viewer→共享命令→SQLite保存→重开恢复。`remove-opening-menu-refactor2`实际66项：domain32（调整4/手调14/自动规则14）、Android25、Desktop模型9；64通过、2失败、0跳过，整体exit1如实保留。9个Kotlin目标Spotless Apply/Check及diff检查通过。测试fixture非void/JUnit发现与误枚举VERTICAL、一次格式参数引用错误属于过程问题，已修正，不当产品红或最终通过证据。

两项旧Android回归失败没有经过新首页调整或AppBars路径：late-wide期望[4]实际[4,5]，last-spread期望[7]实际[6,7]。在既有干净修复工作树HEAD0ed7dce0d2/code63、retry禁用下，以`remove-opening-menu-baseline2`精确执行同两方法，46秒同样失败且差异完全相同；运行前后git status空。故确认先于本批存在，底层原因未诊断，不扩展修复、不改预期求绿、不声称全部阅读器测试通过。原XML/argv/日志在`D:/Codex/worktrees/reader-manual-pairing-fix/mihon/.gradle-coordinator/remove-opening-menu-baseline2-results.xml`及同key.json/.log。本批不重新跑完整模块、全量、原DP02矩阵或性能采样。

主代理独立核菜单调用链删除、首页局部关系修改、宽页/只有一页保护、原保存原子性及新鲜XML；SharedDualPageRules/SharedDualPageSession/PresentationOwner/data均无生产diff。原Desktop BUILD72未提交改动保持；code64版本分配属于本批新改动，沿用原证书。该批跨越菜单接线、共享helper、真实事件测试、文案和文档，是同一能力的内聚撤销及替代入口，不拆成不可独立验收的小提交。正式候选与实际Release UI验收结果在完成后补录。

### code64候选及实际运行边界

统一candidate一次构建完成（Gradle2分27秒），签名及独立verify通过。[aex.46/code64 APK](D:/Codex/worktrees/0877/mihon/app/artifacts/android/0.19.4-aex.46-vc64-d38a558baa-release/Mihon-Fork-0.19.4-aex.46-vc64-release-universal.apk) SHA256=`f008eb560aca875039dc9daf38381ee76c67a52e201bcacfbf6d7ae2a4e2127e`，productionInputs=`abb9aa89bb74ee9bdfe328812230d870eba49be44abfdd802d7f7c1566dd8d3f`。正式包名、原证书、非debuggable、R8/资源压缩、四ABI及schema43保持。专用mihon-pairing-api36以read-only/no-snapshot/no-window、1536MiB/2核启动，只在其明确serial上经统一入口依次验证安装原code63和新code64；未清数据、未卸载、未操作已连接实体设备。code64冷启动MainActivity为COLD/Status ok，PID6101实际RESUMED、同PID无fatal。

Release实机进程中的一次性UI探针明确观察到：工具栏没有专用菜单，原“调整页面配对”的实际无障碍点击形成0:RIGHT/1:LEFT；production日志保存operation1/revision1/current=true，实际SQLite副本也确认format2/pageCount22/revision1/FIRST_PAIR及schema43。源图为现有夹具生成的已知颜色，读取文本/元数据，没有向模型发送图片或识别原图。最初两次探针未找到可点击控件：中心触屏未建立所需工具栏状态，以及Icon描述节点需找到可点击父节点；测试修正用既有菜单键并沿父节点实际点击，不修改产品或放宽保存断言。

**原生重开验收未通过**：第三次完整UI探针前半菜单/按钮/配对通过，但新Activity首屏没有显示已保存的0/1组，`native-ui-parent-result.json`为passed=false（36.857秒）。新画面记录为FIRST_PAGE单页，保存数据本身正确。恢复链本批无diff，初始恢复的可见画面保护是待核推断，尚未给出唯一原因；不能称即时重开恢复、完整Release业务或整个阅读器全部通过。一次性探针源码、三次结果/日志、保存/呈现事件和SQLite副本保存在忽略目录`app-desktop/tmp/remove-opening-menu-acceptance-20261009/`，原失败原样保留，未提交成通过测试或删去失败日志。本轮正式行为测试仍为上面的真实Compose/共享/保存回归；不能拿JVM通过代签本原生失败。

用户明确范围仍是移除专用菜单、保留自动规则。扩大到原恢复链修复及再构建候选已向用户提出独立范围选择，答复前不改恢复协议、不新增候选。本候选可验收菜单移除与通用调整，但带上述恢复限制；原DP02、此前三项自动布局问题及本轮两个已证实code63基线失败保持未关闭。所有版本以实际清单为准，不能为安全回退仅降低APK或数据库版本。

## 2026-10-09 code66：首次空预备帧恢复已保存配对

用户在已安装code64上报告：漫画柜《不死之身的斯黛菈公主》第14话首次打开第1张LEFT单页，继续第2张RIGHT单页，翻到第3张后返回才出现第1张RIGHT/第2张LEFT。本批按该反馈修复首次恢复，不重新增加专用菜单或改自动匹配规则。真机仅核版本并读取现有文本缓冲，未取得本次操作事件；不能凭缺少日志否定反馈。模型未读取图片。

### 原因及最小修复

code64在隔离API36模拟器再次执行原Release UI探针：原按钮保存成功，但新Activity恢复首对失败（37.815秒），原结果保留于`app-desktop/tmp/pairing-initial-restore-20261009/native-code64-red-result.json`。共享红测进一步确认：capture(emptySet)产生尚无locked/drawn图片的visible预备帧，后台restore把它当成已显示画面标为invalidAfterRelease，之后仍沿默认单页准备首张图片。

`manualBoundaries`现在只保护有实际slot锁定、已有draw回执或导航后已挂载（activeSequence>0）的visible记录；初始sequence=0且没有任何图片承诺的预备帧失效，原ticket也失效，由既有wiring重新采用已保存关系。保留已锁但尚未drawn的画面和用户已经进入的loading组；恢复不改变location，不伪造阅读，也不增加数据库/识别等待。仅此共享呈现条件变更；无数据迁移、无新缓存、无新Load协议。若本地记录直到图片已锁定后才返回，仍保护当前画面，这一边界没有被取消。

### TDD、审查和关联证据

- `initial-pairing-restore-red`：16项中1项按正确原因失败，初始空ticket未失效；XML另存`shared-red.xml`。
- 初版只保护locked/drawn，独审发现会改变已进入但未出图的页组。新增`initial-pairing-navigation-red`：17项中1项正确失败（late restore使已进入[1,2]的frame失效）。补activeSequence保护后`initial-pairing-navigation-green`：17项manual及9项group/rapid契约均通过，14秒；XML红证据保留`navigation-red.xml`。左右阅读方向、锁定未绘制、真实已绘制、迟到恢复后继续按组导航都有production行为断言。
- 首轮`initial-pairing-restore-green`：共享16通过；Android25中23通过，仍失败`restored manual boundary survives a late wide image dimension callback`（[4]实际[4,5]）与`restored RTL last spread settles the last source page and marks chapter read`（[7]实际[6,7]）；Desktop9通过。上述两项与前一批干净code63 `remove-opening-menu-baseline2`复现断言相同，未弱化预期或冒称已修复。
- 稳定条件的`initial-pairing-restore-refactor`选择共享26、Android6条实际保存/重开、读取挂起仍能翻页、取消重试、相邻默认恢复及保存跨生命周期接线，Desktop9条持久化模型集成；未运行完整模块或全量。最终状态见本节后续产物结果。
- 复用原代理做独立只读审查，确认共享条件边界及initial sequence事实；实施由主代理接管。审查指出原生重复准备需在真实split后从source1返回source0，已通过实际按键修正，不清除保存行。代理无写入、无Gradle，未把未执行runtime标PASS。
- 将原一次性失败探针纳入正式Android原生回归，保留真实按钮→保存→新Activity恢复的原失败断言；重复执行时通过原按钮拆分/重新配对准备夹具，不清除用户或模拟器数据库。

### 最终构建及运行结果

`initial-pairing-restore-refactor`通过（8分23秒，含Release/R8测试构建）：共享26、Android6、Desktop9共41项通过，0失败/跳过，3个Kotlin文件格式检查通过。测试准备步骤随后修正并通过`initial-pairing-native-package`格式及重新编译；原证书测试APK SHA256=`b6dd0b40c2d2cbffa53a0c04ac04146f09f743aaa595eb5e6ec0dc9e1def2291`。

首份内部候选aex.47/code65 SHA256=`57c118989d12190faa11eff642553e811459fd3f0590204560eaee2b9bd43db5`，统一candidate/校验/模拟器覆盖安装通过；原生回归`native-code65-result.json`为PASS（8.589秒，OK 1 test）。新Activity首次prepare记录即context1/source0 RIGHT、source1 LEFT，没有先锁旧单页再挪动。此为合成夹具的真实Release生产链验收，不代签用户漫画原图。

最终真机只读核对发现：本轮开始为code64，结束前已被另一份code65更新；实际拉取APK SHA256=`4982bd37702dbe0af0e1bad70fd4018c7312dac99542ae8ed87ca43209aa6474`，与本轮内部候选不同。尚不知其来源，未擅自覆盖。已向用户确认是否同时验收其他功能，并按正式候选不得复用版本号的规则将最终交付分配为aex.48/code66。此调整仅版本元数据，复用上述行为测试，但最终APK仍须独立构建及原生验证。

最终[aex.48/code66 APK](D:/Codex/worktrees/0877/mihon/app/artifacts/android/0.19.4-aex.48-vc66-c0bc82d27d-release/Mihon-Fork-0.19.4-aex.48-vc66-release-universal.apk)已由统一candidate构建（2分24秒）、原证书校验及模拟器覆盖安装验证通过。SHA256=`b6f2187e39c5251a602f6e17af2650fec155d95acb463791b98467657f4d2770`，productionInputs=`241ab1906df3f135ef72fc5d30d686b718ec6828f4333b34415a113de252d4da`。Release/R8、资源压缩、非debuggable、四ABI、schema43/format2保持。`native-code66-result.json`原生重开回归PASS（6.456秒、OK 1 test），实际交付APK已覆盖验证，不仅复用code65结果。

本轮未安装或触屏操作物理设备，未清数据、未做图片识别。保留其来源待确认的code65；用户待验路径为覆盖安装本轮候选后，重新进入已保存首两页配对的第14话，预期首屏第1张右、第2张左，无须先翻到第3张再返回。首次读取确实迟至已锁图之后的保护边界仍如前述，不能宣称所有数据库迟到情况都会即时重排。两项Android基线失败、此前自动布局三项及原DP02未因此关闭。


## 2026-10-11 合入主干：发布数据库与同步阅读器兼容

用户要求将本分支/工作树合入主干，并批准预检发现31个冲突后的扩大范围（隔离整合、两个代理、一次独立审查及必要修复复审、定向迁移/Android/Desktop验证，非全量或发布构建）。整合基线为主干`51b84dbf8dbe6a4c60f936d3b60de861fe7a1f72`与本分支`21b5cffb0add3e6be1cc87c95108d18a8485ffa5`，共同祖先`31a6ab7269967cc615ed54ac2ca58fe8d9b3f1db`。在`D:/Codex/worktrees/dual-page-main-integration-20261011`完成整合。来源工作树既有AppVersion BUILD72未提交差异不纳入；整合保留主干Android70/aex52和Desktop BUILD91，不分配新候选。

### 合并内容与边界

- 保留通用“调整页面配对”、取消章节开篇专用菜单的既有选择，保留自动配对规则及code66首屏空预备帧恢复修复。
- Desktop逐块合并同步、历史、目录补全、reader factory/DI、真实主调度器classpath参数文件、新双页事实管线与原生呈现；64项capability按ID/字段三方合并，未以旧分支状态覆盖主干独立进展。父roadmap仍以主干Desktop交互计划为active-child，DP02未完成状态保持。
- 旧发布数据库迁移40/42有真实编号冲突。保留主干40/42/43，新增44（取已发布reader42原DDL）汇合到schema45。reader43保留开篇模式/手动配对/边界/修订/进度/暂停时钟并补同步修复家族；main44保留同步修复证据和章节身份并补配对意图。残缺家族、既有修复表缺列或错键原子拒绝，原始证据及user_version回滚。长期规则补入reader-shared-core。
- 新双页结算与主干失效同步恢复保护有交叉：Desktop自动fallback不提交进度，确认仅提交实际绘制集合，用户双页导航可确认新目标；Android确认保留待结算双页对象，使稍后绘制的伙伴继续累计进度。未绘制页面不作为阅读证据。

### 红绿与独立审查证据

过程日志均位于整合工作树`.gradle-coordinator/`，每个key的JSON记录完整argv、时间、PID和退出状态；重型Gradle串行执行，未使用完整模块测试。

- `merge-pairing-schema-red`：7项中新增3项正确失败（reader43无sync_repair_failures、main44无opening_mode、残缺配对家族未拒绝），原XML另存同key-results.xml。
- `merge-desktop-red-retry`：23项中3项恢复保护按正确原因失败；修复后`merge-desktop-green`执行17个精确类，160项中159通过，ReaderScreenModel23项全绿。原17份XML保存在`merge-desktop-green-xml/`。红XML已被Gradle覆盖，保留原红日志/argv及当时解析结果，不声称仍有红XML。首次start因Windows bat路径写法退出，未启动Gradle；retry key为改路径后的唯一实际红测任务。
- Desktop预览脚本`python scripts/tests/desktop-preview-test.py`：13项中9通过、4项macOS条件跳过。Desktop既有未注册Spotless，按其原边界做编译与diff检查；未宣称Desktop默认Spotless通过。
- 独立审查提出Android确认清掉双页待结算对象、reader43接受残缺同名同步修复表两项阻塞。`merge-review-red`中数据9项有2项正确失败（缺列/错键未拒绝）；Android新回归首次实际提交后等待伙伴进度超时，旧12项通过。Android默认retry使同一失败重复3次，下一轮已通过projectsEvaluated关闭retry。原XML另存`merge-review-schema-red-results.xml`及`merge-review-android-red-results.xml`。
- `merge-focused-format`对本轮app/data共12个Kotlin文件沿原Spotless规则格式化通过。Desktop唯一失败测试原夹具一直render(frameTime=0)，生产pager依赖动画时间；只将两处测试render改为System.nanoTime，保留全部真实绘制/原图租约强断言，原因须以后续精确补验确认，不直接记为已证实基线。

最终定向补验与合并结果见本节后续记录；不得将前述中间结果称为最终代码全量通过。原DP02及历史已确认的两项Android边界/尾页基线失败未自动关闭；本次没有正式构建、设备安装、远程push或图片识别。


### 定向补验与资源边界

`merge-focused-green`的数据JVM五个精确类/方法共39项通过，Android共享迁移契约9项通过，Android生产AppModule迁移回调12项通过。后续Debug阅读器测试同进程累积Robolectric图像状态，出现`OutOfMemoryError(DataBufferInt)`及MockK连带失败；发现后检查本机可用内存并经协调器停止该key，状态CANCELLED，原日志和已生成XML保留于`merge-focused-green-results/`。未将连带失败当作产品回归，也未把取消任务称为通过。Release原配置已有1GB堆/forkEvery=1，Debug未应用；仅在忽略目录本轮init中为Debug沿用1GB堆、单fork、按类新进程，retry关闭，未改仓库构建策略。

`merge-isolated-review`确认Android恢复类13项、竞态类1项通过。两项额外配对失败在隔离后仍重现，原因不是推测的内存：夹具GetManga返回viewerFlags=6，但SQLite仍viewer=0；主干原子openChapter会采用SQLite的manga，真实链路因此正确采用自动模式。将夹具数据库viewer与所选readingMode对齐，保留原真实控件/挂起读取断言，不修改production或放宽超时。补验仅覆盖5个显式双页场景及1个默认通用调整重开场景。

这轮同时运行Desktop伙伴确认新回归，第一次停在夹具displayUnits.single：该列表还包含两侧章节过渡项，尚未到目标行为，不算有效红测。改为选择唯一非过渡内容项，并继续强断言{target,partner}。SharedSpread调整帧时间后已越过历史绘制断言，但立即取当前帧的像素检查仍过早；进一步在原5秒pump中等待同一实际颜色像素条件，保留原阈值/采样点/最终断言，不扩大超时。后续有效红绿结果另记。

范围内的历史两项Android失败在本次重现为边界期望[4]实际[4,5]、尾页期望[7]实际[6,7]，与此前干净code63证据一致。它们未因合并被关闭；source其余DP02未完成与历史自动布局问题保持原状态。

整合期间主干新增`773533ae84e8386a0160f6029b698e3b5021a4e4`，仅同步文案/原型及说明7文件。已读取真实diff，后续在隔离工作树保留此并行提交再更新主干；新增文案不使此前阅读器/迁移行为证据失效。此为整合两个长期分支的内聚变更，超过8文件/400行的主要风险是发布schema、进度协议及DI/目录接线，采用上述定向测试及独立审查覆盖，未以行数拆分或启动完整发布矩阵。


### 本轮最终结果

- `merge-android-isolated-green`通过，3分47秒：受影响配对6项、production双页进度37项共43项，0失败/跳过；app定向格式检查通过。新的Android恢复确认回归已在`merge-isolated-review`13项恢复类中通过；该次竞态类1项亦通过。原内存连带失败全部由新进程中的对应类补验关闭，未重新执行数据迁移39+9或AppModule12项。
- `merge-desktop-partner-red`：新伙伴确认测试1项按正确原因失败（期望仅伙伴page1进度，实际空集合），XML保存为同key.xml。最小修复只在同页/同generation的已绘制集合中选择实际锚点，逻辑目标未绘制时回退已绘制伙伴；不创造目标页进度。
- `merge-desktop-partner-green`通过，23秒：ReaderScreenModel24项及SharedSpread原方法1项，共25项，0失败/跳过。SharedSpread保持挂起旧解码任务时仍能真实画出目标原图的要求；单调帧时钟及等待当前帧满足原像素条件后，原强断言通过。主代理独立核查最后production差异、真实native presenter测试与XML，确认修复复审指出的伙伴遗漏已关闭。
- 组合证据：数据JVM39、Android共享迁移9、Android生产迁移12、阅读恢复13、竞态1、双页进度37全部通过；Android配对25项的两项已证实旧基线失败保留，其余通过证据加6条夹具补验有效。Desktop最初17类160项结果与最后25项补验共同覆盖最终161个已选用例。这里是首次定向结果加局部修复补验，不是最终代码重新通过完整模块/全量。
- 独立审查1轮及修复复审1轮已执行；没有新增代理、追加正式审查轮次或扩大验证。最后Desktop小修复由原实施代理按有效红绿实现，主代理作整合核验。实际交付是本地主干合并，不生成APK/EXE、不安装、不推送远程。主干并行文案提交及原工作树未提交版本差异均保留。


## 2026-10-11 两项 Android 配对失败的诊断与修复

基线为本地主干 `43fbde5057bcd14297cd2b083049f5a5705263da`，整合工作树初始无改动。本轮只处理两项保留失败及直接相关的 Android 跳页接线，不修改共享配对算法、schema、发布版本或其他 DP02 待验范围。下述诊断索引均从0开始。

### 已证实原因

- `restored manual boundary survives a late wide image dimension callback` 是过时断言。状态输出证明：初始逻辑目标5、owner组[4,5]、原生组[4,5]；进入宽图2后两端均[2]；跳回4时两端仍为已经进入过的[4,5]。原断言要求[4]会拆掉有效既有组合，与当前局部决策及已进入组保留规则不符。用例现先断言初始owner/原生组，随后保留宽图独占、手动边界{3}、源页锚点4、真实数据库记录及返回边界页单页的强断言。此项不改产品规则，也不把空绘制历史描述为实际图片已画出。
- `restored RTL last spread settles the last source page and marks chapter read` 是产品缺陷。状态输出证明跳到7后逻辑目标7、owner组[7]，但原生ViewPager仍选中旧列表[6,7]，且没有锁定/绘制历史。`moveToPage`只更新共享owner，再在旧adapter列表查找包含目标的项；没有立即发布新的局部分组，下一次尺寸/匹配回调可能才把界面纠正。不能将原断言直接改成[6,7]。

### 实现及红绿证据

- 显式跳页调用`selectPage(..., publishLayout = true)`，复用既有`rebuildItems(page)`及锚点/内部IDLE保护，再定位原生页面；原生选页回调维持仅同步owner的路径，避免重入发布。既有有效组仍由共享owner决定。
- `pairing-baseline-bounded`重新执行两项原用例，两项按原原因失败；带状态XML保存在`.gradle-coordinator/pairing-baseline-bounded-results.xml`。
- `pairing-jump-red`执行修订边界用例、保持尾页单页要求的用例、新增直接冷跳接线用例：3项中边界通过，尾页及新增用例正确失败。新增用例owner预期[6,7]，原生实际[5,6]；其断言覆盖逻辑目标、共享组和真实ViewPager选择，而非扫描源码。红XML保存在同key的`-persistence.xml`及`-wiring.xml`。
- `pairing-jump-green`最小实现后3项全通过。尾页用例继续执行真实SQLite读取，确认`last_page_read=7`且`read=true`；新增用例同时覆盖未知尺寸和已知纵页尺寸，中段及尾页跳转均不依赖新图片回调。
- 所有Gradle调用串行经过协调器。第一次`pairing-baseline-trace`因宽泛方法筛选配合forkEvery=1触发大量无关类进程启动，确认后停止，状态CANCELLED；改用目标class文件include限制后再执行。`pairing-focused-green`仅init配置阶段失败（buildSrc没有app），修正条件后以`pairing-focused-verified`执行，前者不算测试结果。忽略目录init沿用1GB堆、单并发、按类fork、retry=0；未修改仓库测试配置。

### 相关回归及旧夹具收口

`pairing-focused-verified`执行配对持久化25项、Android共享接线27项、production进度37项及原生Holder精确3项，共92项；90通过、2失败，完整4份XML保存在`.gradle-coordinator/pairing-focused-results/`。后续production不再改动，只校正以下两个测试前提：

- 模式切换用例原先从未进入的[3,4]候选直接跳到4，修复后正确得到[4]。现在先通过真实`moveToPage(3)`进入并断言[3,4]，再选后一页4；仍保留原组、VM逻辑页、真实内部IDLE重入、尺寸重排及切单页的全部断言。
- Holder导航用例原先要求1→2→3逐源页前进，与用户已明确要求的整组翻页不符。本轮曾临时还原两个production文件到基线HEAD并仅运行该方法，`pairing-navigation-baseline`仍按同一原因失败（expected2/actual3），XML另存同key-results.xml；之后恢复本轮production差异。用例现精确要求[1,2]→[3]，同时断言owner/原生组一致，保留未绘制不结算、跨章节及回翻要求。

这两处测试维护不放宽跳页和进度要求，不引入新的产品行为修改；复用上述未受影响的90项通过结果，仅补验本轮原问题、新回归和两个调整用例。

最终补验：`pairing-final-focused`中边界、尾页、冷跳、整组导航4项通过；模式切换仍停在原内部IDLE前提。其后定向状态诊断证明，封面LEFT→FULL只改槽位、不改组数量，不能保证触发ViewPager内部IDLE。最终夹具先进入目标双页，真实启动SETTLING并断言该状态，再通过未进入的邻章页尺寸回调拆分相邻组，断言adapter项数增加；不模拟IDLE，不删除原重入/逻辑页/模式切换要求。`pairing-mode-window-verified`该方法通过（41秒），6个本轮Kotlin文件的Spotless检查通过。中间`pairing-mode-fixture-verified`、`pairing-mode-idle-verified`、`pairing-mode-native-verified`、`pairing-mode-state-trace`均因未制造IDLE的同一测试前提失败，未改production；临时状态输出已移除。

最终证据为92项首次定向回归的90项通过，加上述2项对应补验；本轮原两项失败和新增冷跳回归也在最后5项中再次通过。所有本轮选定范围的失败均已关闭，不声称最终代码重新跑过92项或整个模块。主代理完成差异及证据自查，`git diff --check`通过。无新增代理、无全量测试、无图片识别；使用Robolectric执行真实Android ViewPager/VM及SQLite接线，未运行设备验收、构建APK、安装或远程推送。修复随测试和本记录同一提交进入本地主干。
