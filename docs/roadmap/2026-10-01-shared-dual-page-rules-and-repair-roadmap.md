---
status: planned
date: 2026-10-01
---

# 双端共享双页规则与阅读器修复 roadmap

## 1 目标与执行状态

唯一规格：[完整设计](../2026-10-01-shared-dual-page-rules-and-repair-design.md)。Windows Desktop 与 Android 的全部双页业务决策进入一套KMP实现，修复《地下忍者》169话直接跳17时的错误配对，以及《花甲公主》19话中20的摆放，并保护2的左侧位置。固定验收为设计中的U01–U20。

规划基线为 `main@5f13b080547ab2fcdcd6deb7ecdfa463e2765421`。本文件是**未激活的产品 child plan**，从第一个未勾选项推导进度，不声明active-task，不改变父roadmap的active-child-plan。用户当前授权是完成设计与roadmap；本文不代表已获实施、构建、设备安装或部署授权。

计划只有一个完整产品功能批次DP01，以及最后的交付任务DP02。DP01内部按必要接口和平台接线分步完成，不能把孤立的共享helper、只有一端生效的迁移或单个测试类当成已交付能力。当前所有实施checkbox保持未勾选。

## 2 流程预算和责任

| 项目 | 预算和执行边界 |
|---|---|
| 本次文档 | 主代理直接完成，0子代理；设计与roadmap各1份，相关架构入口作局部更新；一次UTF-8、链接、内容和diff核验后提交，不运行产品测试或构建 |
| 实施技能 | 无额外必需插件；使用项目TDD、Desktop UI规范、既有Android构建规范。macOS执行前阅读MACOS_ACCEPTANCE；不引入HTML原型、图像生成或Docker |
| 实施委派 | 激活后将DP01首个任务簇A交给1名实施子代理，由其连续承担A–E主要实现及focused验证；主代理冻结验收、协调接口、独立审查和最终整合。后续修复复用原代理 |
| 并行 | 实施者写代码时，主代理可只读核对夹具来源、脚本和环境；独立读取可并行。A→B→C→D→E串行，Android/Desktop共享规则、状态和测试有依赖，不安排冲突写入或并发重型Gradle |
| 独立审查 | 1轮覆盖DP01，采用分段的同一轮审查：B稳定后先审高风险接口和纯规则，再允许C/D消费；E完成后审尚未审查的平台接线、状态与回归证据。同一实现不重复多轮泛审；接口后续变化进入修复复审 |
| 修复复审 | 最多1轮，针对本轮审查未通过项及影响路径；申请前说明失败证据、上一轮未解决原因、本轮范围及新增时间。不以预算额度替代证据 |
| focused | B/C/D/E四个行为组各预期红→绿→重构3轮，合计12轮限定验证；每轮冻结有关target/测试类集合，可以串行多条命令，各范围只执行一次，不将其包装成完整模块。A做一次夹具只读核验。失败诊断只补受影响focused，新增范围/轮次先说明并取得决定 |
| 批次验证 | E末尾一次受影响单元、集成、wiring和模块格式集合；限类执行，不运行完整模块、full或正式构建 |
| 最终全量 | DP01实现、独立审查、相关验证、提交全部完成后，DP02执行一次冻结的最终矩阵；不得提前，也不得在失败后自动重复 |
| 交付物 | 共享规则与状态、两端生产接线和设置、测试夹具/契约、维护文档、正式Windows EXE、Android候选APK、同一Desktop源码的macOS产物与运行证据 |
| 过程产物 | 使用既有Gradle协调器日志；未来只建1份聚合证据报告 `docs/evidence/shared-dual-page-rules-and-repair-2026-10-01.md`，不创建逐任务报告、快照或巨型diff包。当前不提前创建空报告 |
| Git | DP01原则一个包含production、测试、必要文档和checkoff的功能提交，审查修复最多增加一个；DP02版本和构建/运行证据合并收口。无纯close/advance提交 |
| 预计墙钟 | A取证与定夹具1–3小时；B–E实现14–23小时；独立审查与必要修复2–4小时；DP02最终测试/三端构建和运行3–6小时；合计20–36小时，取决于真实样例、缓存和平台可用性 |
| 主要成本 | 双端原生宿主、真实图片链和采样adapter、Gradle编译及正式构建；有界真实源读取。无需外部付费服务，不写真实GitHub空间，不部署Docker |

预计DP01涉及约25–45个文件，可能超过400行。内聚性来自同一共享双页能力必须包含事实输入、规则、定位、匹配、设置、保存和两端消费；只迁移某个算法无法独立验收。实现者交付时说明实际范围和风险，不为满足估算拆开不可编译上下文。

新建数据库schema、配对云同步、广告/OCR模型、Android新增LTR模式编码、重写单页/条漫、增加代理或审查、超过测试预算、再次full及不可逆部署均不在默认范围。必要时先说明具体原因、成本和替代方案，暂停扩展并等待决定；范围内诊断可以继续。

## 3 DP01 完整共享双页功能批次

- [ ] **DP01完成：设计U01–U19已实现并具有有效自动化证据，独立审查和相关验证通过，维护文档更新且功能提交完成。U02/U06的最终真实源正式产物运行及U20由DP02收口。**

### A 真实案例和接口前置

**目标**：建立可追溯的输入事实，确认方案能同时满足两个案例，避免用构造样例替代用户问题。

**委派内容**：主代理将设计、当前HEAD、已有未提交改动清单、上轮50项测试与算法反例交给实施者。实施者核对当前事实，不重复安装SDK或重跑原全量。首个任务簇必须由实施者承担夹具建立和后续主要代码，不只委派审查。

**范围**：现有两端reader测试夹具、受控本地图片样例和聚合证据中的案例表；此步不改排版production行为。

1. 核对用户实际版本、图源、章节页数、阅读方向、自动匹配、手动边界和加载顺序；复用现有生产源/缓存入口。用户实体设备操作、安装或读取私有数据无既有授权时留待明确授权，不能以本计划代替。
2. 获取B1的17页、B2的22页尺寸/顺序和影响边界，保留可复核来源。记录页2、15/16/17、20/21/22在相应阶段的实际组与槽位；不得假定B1第14页宽、B2第3页宽。
3. 仓库仅保存合法合成图及无敏感信息的拓扑/尺寸夹具；真实漫画图片留在获授权本地输入目录，不提交账号、令牌、带签名URL或整章内容。
4. 形成两类测试：原样拓扑对应真实案例；最小构造例对应奇偶覆盖匹配、后方宽图回推、孤页歧义、跨平台正方图差异。二者标签分开。
5. 保护已经正确的Desktop宽图后组合，至少选一组旧landscape parity曾正确处理的例子、一组自动匹配和一组手动调整例子，确认新共享策略保留其合理结果。

**前置门禁**：若真实事实推翻设计第4/5节，应给出最小反例和所需的共享规则修订，由主代理先修正文档再实施对应行为；不得偷加图源/页码特判。真实源暂不可用时，可以继续构造契约和独立共享工作，但保留真实案例验收缺口，最终不宣称BUG已修复。

**完成证据**：一次夹具范围/UTF-8/顺序核验、脱敏案例表、确定的共享输入输出接口和受影响文件边界。A不单独checkoff或提交纯状态。

### B 共享规则和定位状态

**目标**：产出两端可直接消费的唯一双页结果，解决奇偶策略、槽位、UNKNOWN及逻辑目标丢失。

**修改入口**：`domain/.../reader/PageTransform.kt`、`ReaderPortraitSingleSlot.kt`、`ReaderPairingAdjustment.kt`及相关session/presentation DTO；优先演进现有类，新增小型状态/结果文件可按职责命名。建议测试 `SharedDualPageRulesContractTest`、`SharedDualPageSessionContractTest`，名称可合并到现有契约类。

**先红**：

- 用真实生产build调用复现17页宽14和matched15/16冲突，并断言统一预期 `[15,16] [17]`。
- 测试后方宽图事实不能仅因旧奇偶回推拆散无关前组；UNKNOWN不当PORTRAIT，也不把临时单页当永久边界。
- 固定第5节全部槽位表、1/2/4/5页、宽封面/正方图、开篇连续单页及中间歧义；以B2真实夹具同时断言20和2。
- 输入GoToPage(17)→暂定布局→尺寸/匹配晚到→确定布局，断言逻辑目标仍是17；再覆盖跳5、切章、旧revision、模式/方向变化。
- 覆盖手动边界与自动匹配局部冲突、末页已在双页、单页/双页稳定事件原因和无伪进度。

**最小实现**：落地设计3–5、7、8的纯规则与状态。移除production分平台开关；整段奇偶不能覆盖更强依据。保留现有session和进度policy，输出双页规则结果及必要effects。可暂保留薄兼容facade以让中间步骤可编译，但不新增第二套最终生产入口。

**绿和重构**：复跑同一契约范围；删去重复判断和无效低层默认，不靠修改预期让红测通过。检查页覆盖、组内顺序、无重叠、方向对称、幂等及不同事实排列的最终一致性。

**接口门禁**：实施者交付B结果后，主代理在本轮独立审查中先检查身份/版本、UNKNOWN相位、定位意图生命周期、visible/settled原因、手动CAS边界和优先级。通过后C/D才能依赖；未通过交原实施者修复，不让下游靠adapter补丁掩盖接口缺陷。

### C 尺寸事实和双端呈现接线

**前置**：B接口门禁通过。Android与Desktop改动顺序交付，重型Gradle由同一协调者串行执行。

**修改入口**：Android `ReaderPage`、`HttpPageLoader`/既有内容端口、`DualPagePairingStore`、`DualPageViewerAdapter`、`DualPageR2LPagerViewer`、`DualPagerPageHolder`；Desktop `DesktopReaderRuntimeFactory`、encoded/presentation image owner、`ReaderScreenModel`、`DualPagedPresentation`、`DualPagePagerViewer`与其已有依赖装配。以真实需要为准，不顺便重写其他阅读模式。

**先红**：

- 图片已通过真实加载链物化、尚未挂载holder时，尺寸应进入共享事实；覆盖HTTP/下载/部分下载/本地文件、失败与重试。
- 冷启动直接跳17，经真实source→encoded→事实→共享结果→View/Compose，出现晚到宽页仍定位17；不能在测试中手工传最终分组。
- 对B2真实拓扑测量20右侧或规定的居中、2左侧；对宽封面和一页章节测量全视口居中，不只断言枚举。
- 通过延迟/取消/重复/旧章响应验证generation、identity和layout revision；有界补齐达到2并发、16页/32MiB/15秒上限后停止扩展且阅读不阻塞。
- 真实可见页门禁覆盖Android单页和双页、Desktop停稳；布局变化不伪造已读，用户实际看过尾页仍能完成。

**最小实现**：平台只映射输入事实/事件并渲染共享结果；删除平台内槽位重算、默认PORTRAIT补全和独立anchor归一决策。尺寸读取沿用encoded及production请求链，无新HTTP客户端/整章下载器。补齐需求复用现有scheduler和lease，取消仅撤销本次消费者。

**绿和重构**：分别复跑Android/Compose focused与相关内容路由测试；确认改坏真实绑定、尺寸发布或共享结果消费后测试会失败。统一的事实与规则不要求两个native decoder实现相同，但必须执行共同输入/输出契约。

### D 自动匹配共享与设置接线

**前置**：B/C稳定并可通过原生viewer跑共享布局。保持C的请求预算和资源所有权。

**修改入口**：Desktop `EdgePixelMatcher`及其调用者、共享纯采样计划/评分/候选选择、Android受控图像采样adapter、两端reader preferences与现有设置区域、Injekt wiring和本地化资源。复用现有UI和偏好体系，不新增页面/Tab。

**先红**：

- 同一编码合成图经Android/Skia真实采样路径，产生共同契约要求的方向、评分、边缘关系；白边/暗边/低方差、透明图、有效旋转和正方图均覆盖。
- 重叠候选、缓存淘汰、局部样本先到、完整样本后到得到稳定无重叠关系；不能将不同时刻的贪心结果无条件取并集。
- 用真实设置开关启停匹配，两端默认false；关闭后无旧候选作用，Desktop旧用户值保留，手动边界不屏蔽其他无冲突关系。
- 测试实际DI解析及采样lease释放，失败/取消/大图不能绕过预算或额外发网络请求。

**最小实现**：搬移现有有效评分和防误匹配逻辑到commonMain，原生类型仅留在采样adapter；按设计规范采样方向/颜色和候选选择。Android新增分页设置双页区域中的同语义开关及反馈，Desktop继续沿用已有入口。尺寸与匹配共同驱动共享状态，平台不得改写优先级。

**绿和重构**：同一共享匹配契约与两端UI/DI focused全绿；删除Desktop旧production评分/选择和“有任意手动边界则清空整章匹配”的决策。只保留必要adapter或被明确使用的测试工具，不保留可被重新调用的平行产品算法。

### E 全链路回归与批次交付

**目标**：完成A–D共同能力、实际进度/持久化链路和一轮独立审查的剩余部分。

**先红及补齐**：使用同一行为夹具经两端production路径覆盖U01–U19，含手动保存成功/失败/CAS、重开、邻章、快速跳页、无痕/进度原规则、切换双页与单页/条漫、窗口/Activity恢复及键鼠/触摸。HTTP改变部分执行成功、空/缺失、403/429/500、畸形和恢复响应，不mock parser。

**清理边界**：检查全部生产双页入口都进入共享owner；不得仅凭源码扫描证明完成。平台适配器断路/改错参数时行为测试应失败；源码守卫只能补充防回退。若增加Test Mode查询，只读暴露共享实际状态，不手工组装期望分组，不绕过用户入口。

**批次验证一次**：汇总本批受影响的shared contracts、Android真实holder/viewer与进度/保存集成、Desktop真实Compose/ScreenModel/DI与runtime、匹配和资源测试，全部使用 `--tests` 限定；运行有关模块Spotless检查。全量和正式构建仍禁止。

**独立审查收口**：主代理审查B之后尚未审查的production接线、解码预算、匹配/设置、身份和持久化、U01–U19证据；B接口发生相关变化时纳入已声明的一轮修复复审。没有具体失败证据不扩大到无关reader重构。更新架构/权威文档中已实现状态和必要parity证据，但仅按真实证据更新manifest，不把tracker文字反写成机器状态。

**提交与回执**：实施者先返回结构化 `status/diff/tests/commit/process/next`，可用现有 `scripts/agent-handoff.py` 核验。主代理核对diff、工作树和有效日志后只暂存本任务文件；DP01按实现、独立审查、相关验证、提交全部完成才勾选。无关同步/原型改动保持原样。

## 4 测试命令和证据要求

以下类名是计划名，首次建立测试时固定映射到U01–U19；重命名须同步本表和聚合报告，不能只运行空过滤器。命令从仓库根目录执行，先设置PowerShell环境：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
```

| 阶段 | 限定命令示例与证据 |
|---|---|
| B共享 | `python scripts/gradle-coordinator.py run --key udp-core-red -- .\gradlew.bat :domain:jvmTest --tests mihon.domain.reader.SharedDualPageRulesContractTest --tests mihon.domain.reader.SharedDualPageSessionContractTest`；绿/重构用各自key，保留正确红因 |
| C Android | 协调器运行 `:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.pager.SharedDualPageAndroidWiringTest"` 并追加受影响现有holder/进度/保存测试类；实际创建的类覆盖真实View，不以纯PairingState替代 |
| C Desktop | 协调器运行 `:app-desktop:jvmTest --tests "mihon.desktop.ui.reader.SharedDualPageDesktopWiringTest" -PincludeIntegrationTests=true`，并追加受影响已有定位/呈现类 |
| D匹配 | `:domain:jvmTest --tests "mihon.domain.reader.SharedSpreadMatchingContractTest"` 加真实Android/Skia采样与设置测试，均限类 |
| E批次 | 一个冻结的focused类集合及有关模块的 `spotlessCheck`；只执行本批有关类，不能省略集成开关或以重复完整模块代替 |

每次长调用前报告命令、预计时间和可用PID/日志；协调器仍为STARTING/RUNNING时不得启动第二个Gradle。外层超时先查状态，不重复命令；只允许停止协调器记录的进程树。SDK按真实文件检查，访问境外依赖在当前会话设置项目规定代理，本地MockWebServer绕过代理。

## 5 DP02 最终全量与正式交付

- [ ] **DP02完成：DP01已完成并提交；一次最终矩阵、正式Windows/Android/macOS构建与要求的运行证据、聚合报告、实际产物地址和收口提交齐全。**

### 前置和一次最终矩阵

冻结本次待发布产品diff、源码身份、测试目标和平台环境后才执行。检查构建脚本当前实际范围，复用等价全量证据以避免重复；全量之后相关产品代码再变化时，先focused修复，申请追加完整验证，不能自动重跑。

| 范围 | 最终执行要求 |
|---|---|
| Desktop完整JVM及集成 | Windows `bash scripts/build-desktop.sh full-tests` 一次；当前Windows脚本对应 `-TestOnly -FullTests`，执行前确认集成测试实际启用 |
| 共享规则完整回归 | 协调器串行运行 `:domain:jvmTest :domain:testDebugUnitTest`；后者验证共享契约Android目标，执行前核对有效测试引擎与非零用例 |
| Android完整单元 | 同一最终矩阵运行 `:app:testReleaseUnitTest`；按当前构建约定选择release测试配置，不用编译成功代替测试 |
| Desktop E2E客户端和格式 | 同轮 `:test-desktop:test spotlessCheck`；若data实际未修改，不为扩大范围额外跑data完整模块，受影响持久化已在focused集成覆盖 |
| Windows正式构建 | 等价完整Desktop证据有效后 `bash scripts/build-desktop.sh build-only`，执行版本分配、production runtime和正式产物发布，避免再次完整JVM |
| Android正式候选 | `python scripts/build-android.py check --signing` 后 `python scripts/build-android.py candidate`；同一正式证书、递增versionCode、R8/签名/清单校验，不隐式安装 |
| macOS正式构建和运行 | 先读MACOS_ACCEPTANCE，核对同一产品源码与等价全量证据；项目脚本 `build-only`，实际受影响平台focused/正式运行必做，完整JVM不重复。预先核对部署路径及实例，避免覆盖日常应用；现有脚本不能隔离时先解决部署授权/路径，不绕过项目入口 |

这是一次最终验证矩阵，可以分命令和平台串行执行；不能以命令多次调用将完整模块重复运行。SDK、签名、真实源或macOS不可用时保留真实阻塞，不把未执行项改为通过。正式候选成功不等于运行验收完成。

### 正式运行和人工验收

1. Windows使用本轮构建日志的 `Final unpacked EXE:` 路径，核对文件实际存在、版本、源码与隔离profile。只从正式产物启动Test Mode；不用 `app-desktop/tmp` 或Gradle build目录作为交付地址。
2. 两端运行B1冷/热缓存、顺读/直接跳17和前组15/16；B2直接跳20/顺读、返回2及其他正确双页。以真实production source/图片/共享规则/呈现链验收；不能预先注入最终分组或强制单页记录来制造通过。
3. Android对同一SHA-256正式APK运行R8/ART真实链路检查；安装与实体设备操作须有明确授权。用户选择自行验收时提供已验证候选、操作路径和记录项，DP02保留待验收状态，不代操作设备或伪记通过。
4. 人为限额/断网/失败使用受控夹具验证稳定居中、恢复、重复点击及不误记进度；正常网络的真实案例另记录，不能以失败兜底替代全部正常分组验收。
5. macOS验证同一Desktop共享逻辑、实际窗口、方向/设置、宽图/孤页和键鼠路径；不要求重复请求整套真实源数据。涉及新macOS经验时按MACOS_ACCEPTANCE回写通用约束。
6. 视觉用实际View/Compose离屏证据或获授权的平台外部工具，记录窗口/缩放/方向/主题；Test Mode不提供截图API，也不能读取桌面屏幕像素冒充离屏。

### 最终证据和交付

聚合报告只保存：各U编号对应的有效测试/运行证据、首个正确红因与最终结果摘要、真实样例与构造样例区分、剩余限制、构建日志和实际产物路径。原始过程日志沿用协调器；不另建逐任务报告。

最终用户报告按仓库的【功能特性】【BUG修复】【验收清单】输出，明确两端统一规则、自动匹配入口、宽图封面变化、两个案例修复和兜底边界。Windows链接只能用已确认存在的 `Final unpacked EXE:`；Android链接指向统一入口发布的候选目录APK，附版本/哈希证据；macOS提供实际已验收应用/交付包地址。全部证据就绪才勾选DP02并完成版本/必要文档提交。

## 6 失败和追加条件

- 真实夹具与规则冲突：保留反例，优先在共享层修正规格及相应红测；不能取消“2左侧”或“17不丢失”验收。无法无歧义推断20时按已批准的居中兜底。
- 高风险接口审查失败：不推进依赖它的下游；交原实施者修复，复审限原失败项及影响。1轮复审之外仍有阻塞时，说明证据、原因、范围和时间后申请追加。
- 请求或内存超预算：停止额外补齐/采样，保持可读目标及错误反馈；不提高并发、扩大缓存或全章解码来掩盖问题。新增预算须有真实样例和成本依据。
- Gradle超时或代理空闲：先查已报告状态/PID；进程仍运行则等待。代理无回执只发一次摘要follow-up，遵守仓库两次空闲确认规则，不重复实施。
- 全量失败：保留日志，修复并跑focused；再次完整模块/full必须申请。不得以阶段结束、提交或换聊天绕过最终验证门槛。
- 授权/签名/平台不可用：继续不依赖该条件的工作，标明受影响验收及交付；不创建替代签名、不隐式安装、不冒用其他实例，不把阻塞当完成。
