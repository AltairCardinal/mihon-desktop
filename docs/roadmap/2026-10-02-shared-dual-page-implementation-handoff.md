# 双端共享双页：停止验证膨胀后的实施交接

交接日期：2026-10-02（北京时间）。用户要求审计长时间实施，并转交新会话继续解决。本文是用户明确要求的交接文档，不是新的产品 roadmap，不重置既有授权、测试或审查预算。

## 1. 新会话从这里开始

**必须打开原工作目录 `D:/Codex/worktrees/0877/mihon`，不要创建干净 worktree 后直接开始实施。** 绝大多数成果仍是本地未提交文件，部分尚未被 Git 跟踪；仅拉取分支或交接文档提交拿不到这些成果。

- 分支：`codex/shared-dual-page-rules-and-repair`。
- 产品实施基线：`31a6ab7269967cc615ed54ac2ca58fe8d9b3f1db`。
- 审计时共有 79 个变更文件（不含本文）；产品代码没有提交，DP01、DP02 均未勾选。本文将单独作用户请求的文档提交，不能把它算作 DP01 完成。
- 原实施代理：`/root/dual_page_implementation`，已停工并返回回执。审计时所有 `udp-*` 协调器任务均处于终态，没有运行中的 Gradle；恢复时仍先查当前状态，不能依赖旧 PID。
- 新会话不得清理、覆盖、回滚这些改动，也不要执行全面格式化来“整理工作区”。开始时核对 Git 状态和最近提交。

按顺序读取：

1. 本文。
2. [原 roadmap](2026-10-01-shared-dual-page-rules-and-repair-roadmap.md) 中 D、E、DP02 与预算部分。
3. [设计](../2026-10-01-shared-dual-page-rules-and-repair-design.md) 中 6.3、7、8、U01–U20。
4. [唯一证据报告](../evidence/shared-dual-page-rules-and-repair-2026-10-01.md) 中最新 D 记录和“未完成项”，需要追溯某个失败时再查对应日志。

证据报告存在历史段落未及时改写的问题：开头及 C 小节仍写“进行中”，后文才记录 C 已签收。**当前准确状态以本文第 3 节与对应自然终态日志核对；E 收口时统一修正文档，不另建平行状态系统。**

## 2. 已有授权和不可重置的边界

- 用户已授权实施完整 roadmap，从 Windows 收藏自行取得两个真实案例。
- 用户明确批准“无限次数的定向测试”；必要 focused 不逐次请示，但每次必须对应当前验收或具体失败。此授权不等于无限探索、提前全量或重复全量。
- 用户明确批准统一两端 EXIF 显示并追加 Android 适配，原追加估计 3–6 小时。不能退回“各端保持不同方向”以简化收口。
- 原流程是一名实施代理承担主要代码，主代理负责接口、整合和独立验收；总代理默认上限 2。原代理若跨会话不可调用，按不可用交接处理，剩余一名额度可承接实施；不要重新招募多名并行审查者。先确认新会话实际可用工具。
- 独立审查与修复复审的既有额度已经在使用。A/B/C 有效结果复用，D/E 只完成同一轮尚未覆盖的部分及具体修复，不从头泛审全部阅读器。
- 产品行为严格红→绿→重构。编译、参数、格式和夹具失败不是有效业务红。
- DP01 完成实现、独审、相关验证与功能提交后，才能进入 DP02 的一次全量和正式构建。交接不构成提前构建理由。
- 实体设备安装/操作、覆盖日常应用及不可逆部署仍无默认授权；最终验收使用隔离环境。不要为已授权的普通实现和定向测试重新索取许可。

## 3. 已完成什么，尚不能宣称什么

| 部分 | 当前成果与有效证据 | 剩余边界 |
|---|---|---|
| A 真实事实 | 已通过旧正式 Windows 应用的 production 图源链取得 B1 的17页及 B2 的22页尺寸、顺序、设置；CSV与真实头部逐项核对 | 旧应用取证不能证明新修复产物已修好 |
| B 共享规则 | `SharedDualPageRules`、`SharedDualPageSession`、保存 owner 已实现；B 接口独审通过；无平台 parity 开关，UNKNOWN不伪装PORTRAIT，逻辑目标与进度原因分离 | D新增的采样协议需要完成局部接口签收；不是重新审整个 B |
| C 两端呈现 | 内容发布、实际 View/Compose、冷跳17、B2几何、请求预算、方向身份、EXIF旋转/镜像、split/fit/crop及临时资源回收已完成 focused 验证，主代理整体签收 | Windows Robolectric 不证明 Android JNI/ART；正式原生运行在 DP02 |
| D 共享匹配核心 | 50点采样计划、标准sRGB白底合成、原防误匹配参数、完整候选选择、有限样本缓存及逐邻边补齐、Session生命周期已实现；最新38项绿 | 两端生产采样/设置尚未接通；新共享接口尚未正式签收 |
| D Desktop采样叶适配器 | 已写最小实现和3项真实region测试，正确红已确认 | 最后绿测被格式配置阻断，**没有绿色完成证据** |
| E | 已识别保存失败、动画中重排等具体缺口 | 尚未收口，不得跳过 |
| DP01/DP02 | 无功能提交、无本轮正式产物 | 最终测试、三端正式构建和真实案例验收均未开始 |

### 应直接复用的稳定证据

日志均在本 worktree 的 `.gradle-coordinator/<key>.json` 和同名 `.log`，属于本地过程产物；不要要求 Git 中存在这些文件。

| key | 有效结果 |
|---|---|
| `udp-core-refactor` | B原四类33项绿，B首次接口签收依据；后续相关演进由下列证据补充 |
| `udp-c-affected-refactor2` | 111个独立用例中109绿、2失败；不是全绿证据。App24/Domain16/Desktop26格式目标检查通过 |
| `udp-c-holder-and-store-refactor` | Shared Android wiring 11项绿；Holder16中1项仍失败 |
| `udp-c-owned-region-stream-green` / `udp-c-owned-region-stream-refactor` | Holder16正确资源红后绿与重构全绿；关闭原stream后删文件，旧回调拒绝。C上述两个缺口已分别闭环，其余仍适用结果复用，未重跑111项 |
| `udp-native-window-refactor-and-http-budget` | 原窗口复用及真实HTTP字节/截止门禁；正常33MiB可见请求通过，额外读取32MiB截断、15秒停止并清未提交文件 |
| `udp-desktop-b2-compose-and-fact-consumers2` | 实际尺寸目录→header→共享→Compose：2为LEFT，20为FULL；可选消费者超时不取消正常消费者 |
| `udp-d-shared-matching-refactor` | 2026-10-02 00:34:47结束；Session27＋Matching11＝38项，0失败/错误/跳过，5文件原格式检查通过 |
| `udp-d-desktop-sampler-red` | Desktop采样3项因尚无输出正确红 |
| `udp-d-desktop-sampler-green` | 00:46:58结束；14.7秒后配置失败，未进入行为测试，不能算绿 |

不要累计红绿复跑次数来表示独立验收数。此前曾误报 Session26＋Matching11 为43，已纠正为37；新增取消用例后是38。

## 4. 审计结论与执行纠偏

10月1日11:11至10月2日00:47有146条协调器记录，累计约329分钟运行；33通过、112失败、1取消。许多失败是正确红测，失败率不能直接当作返工率。明确低效包括：

- C格式流程在20:59–21:54调用21次、18次失败；临时hook、插件加载、路径和命令行反复试错，最新D又被同一临时工具链阻断。
- 至少9轮含编译错误；另有虚拟时钟、图片夹具、旧预期、资源诊断方式问题造成无效红。
- 若干Android调用用例本身几十秒，整个Gradle却9–15分钟；具体环境耗时原因未证实，不能声称都是JVM启动或外部进程导致。
- 持续加入微小门禁并立即重启重型验证，使一项功能演变为长链。主代理重复读取、逐条提示和频繁等待增加了成本。

续作采用以下规则，不降低原验收：

1. **只推进第5节的有限闭环。** 新发现先归入对应既定验收；没有具体产品失败、集成断路或必要交付阻塞，不新增架构机制和测试组。
2. 每轮先冻结“待证明行为、production边界、测试类、通过后动作”。同一功能内独立的红例合并准备；依赖性红绿仍按TDD串行，不以省次数跳过正确红。
3. 已绿代码没有相关变化或新失败，直接复用证据。不因换会话、交接、提交或新审查者而重跑A/B/C。
4. 同一工具/夹具问题连续两次失败且没有新证据，停止盲重跑，先读错误、固定参数和最小复现；独立产品工作可继续。这个规则不增加用户审批流程。
5. 先完成真实生产接线，再围绕明确失败修复；不要继续给尚无消费者的helper增加一层又一层外围验证。
6. 主代理只在稳定接口、具体失败和阶段收口介入；交给实施者一份完整约束，避免每几分钟追加未来任务提示。进度说明优先报告“关闭了哪个缺口、距交付还缺什么”，不以测试数量代表完成度。
7. 复用唯一证据报告，结束时改写失效状态；不增加报告、生成器、状态台账或巨型diff包。本文是本次用户明确要求的交接例外。

## 5. 后续执行顺序与完成门槛

### 第一步：接管并打通当前D验证入口

接管读取控制在约20分钟：核对工作区、协调器终态，局部审查D共享5文件与38项结果，明确接口签收结论。相关文件是 `SharedSpreadMatching.kt`、`ReaderSpreadSamplesOwner.kt`、`SharedDualPageSession.kt` 及两个同名契约测试；不重新审查已签C。

当前直接阻塞位于忽略文件 `app-desktop/tmp/udp-local-kotlin-targets.init.gradle`：Desktop分支使用 `:app` 已加载的Spotless插件实例的class；仅配置Desktop时，该实例为null，报 `NullObject is neither a plugin or a rule source`。此前通过不代表所有任务组合都能工作。

**先把行为验证与这段临时格式接线分离。** 原Desktop sampler红已有效，下一步可用以下限定命令验证现有最小实现，无须先修一套新格式基础设施：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
& 'C:\Users\feeli\AppData\Local\Programs\Python\Python314\python.exe' scripts/gradle-coordinator.py run --key udp-d-desktop-sampler-green-resume -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.reader.DesktopReaderSpreadSamplerTest -PincludeIntegrationTests=true
```

若该key已存在，先读其状态/结果；不要覆盖历史记录。恢复时核对JDK21和会话代理配置。格式检查仍须完成，但固定一次可靠、限定文件的调用，不再反复试探不同hook，也不因临时脚本失败修改产品预期。

### 第二步：完成D双端匹配与开关闭环

一次功能范围包括以下必要项，不能交付孤立sampler：

- Desktop已有 `DesktopReaderSpreadSampler.kt` / `DesktopReaderSpreadSamplerTest.kt` 尚未绿；沿用 `DesktopReaderPageImagePipeline.openRegionSession` 和region lease。`SkiaRegionPageDecoder` 实际委托ImageIO，不能仅凭类名声称Skia颜色/方向行为。
- Android经原 `ImageDecoder` 的受控region与原内容所有权返回共同样本；Windows不能执行的JNI实测明确留给DP02，准备好对应正式运行测试，不复制评分逻辑。
- 验证原图坐标、EXIF仅归一一次、straight-alpha/sRGB与固定白底；不拿裁边/滤镜/缩放后的presentation缓存当原图。
- Session是样本/评分/候选的唯一owner。最多16页×100个ARGB样本；保留有效标量评分，`requiredSamples`逐缺失邻边从已有encoded重采样。缓存容量16与每次定位新增materialize需求16不是一回事。无新增网络队列，不绕过既有并发、截止与解码资源限制，失败不得循环无进展重采样。
- 完整候选按强度、同分源序选无重叠关系，先屏蔽手动冲突；保留白/暗/低方差与严格色差<30保护；正方图分类SPREAD，不能成为纵向配对证据。
- 自动匹配默认false，保留Desktop旧true。Android在现有 `ReadingModePage` 分页设置区接入 `ReaderPreferences`/`PagerConfig`。已有中英文 `MR.strings.desktop_ui_auto_spread_matching` 与Compose测试依赖可复用，不新增页面或重复文案。
- 删除Desktop production旧评分/选择及 `observeDesktopMatchedPairs` 的历史集合并集路径，清除“有任何manual就禁用整章匹配”。共享Session当前兼容 `matchedPairs` 参数只是迁移中状态，最终不能保留平台平行决策。

**完成门槛：真实开关事件→偏好→实际采样lease→共享候选→真实布局生效；关闭/切单页/图片替换后旧回调拒绝，关闭资源释放；破坏接线会使行为测试失败。** 原有共享38项按相关变更复用或定向复验，不按每个文件再拆一轮测试。

### 第三步：E仅关闭已知剩余缺口，完成DP01

1. **保存链路**：Android `ReaderViewModel` 保存catch仍主要清 `pairingSaving` 并发toast，须把失败/CAS送回共享保存状态，允许实际重试；清理 `pendingSave`、`previewAdjustment`、`applySaved` 等平行决策。真实UI→VM→repository覆盖成功、失败、冲突、重开，保存前不改变已提交边界，保留既有反馈。
2. **动画中输入**：Desktop `DualPagePagerViewer` 的layout LaunchedEffect会清 `userTurnTarget`，而tap仅先记native索引，逻辑GoTo较晚产生。针对真实tap/key/drag与动画中晚到事实/匹配重排，先复现目标丢失再最小修复；保护连续点击、外部跳转和drag既有语义。此前“翻页结束后再晚到”的绿测不能代替这个窗口。
3. **迁移清理/回归**：移除旧 `settleDualPage(visible)` / `layoutProgressAnchor` 等仍可被production绕行的双页决策；按U01–U19核实际邻章、恢复、进度/无痕、输入、关闭资源路径，只补缺失证据。
4. **HTTP证据**：现 `HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource` 的 `empty/malformed/pages:17` 哨兵证明错误传播，不是完整真实形状解析证据。改动涉及的生产HTTP/解析链用真实形状响应补成功、空/缺失、403/429/500、畸形与恢复。复用现有生产parser/adapter，不借此重写第三方图源或建新客户端。
5. **正式运行入口准备**：Test Mode页码动作/查询部分仍是测试侧影子状态，接实际reader/model只读状态和真实动作，禁止自己拼预期分组。HTTP Robot不算原生键鼠；分别提供证据。EXIF JNI/ART与真实漫画正式候选的必要测试准备应在DP01冻结前齐备。
6. **文档与manifest**：同步旧宽封面LEFT、landscape parity、显示缓存匹配及旧测试方法名。`parity-manifest.json` capability 43及相关进度条目是入口；只更新受影响证据/差异，不提前跑 `finalParityAudit`。更新 `DualPagedPresentationTest`、`DesktopReaderProductRegressionTest`、`DualPagePresentationIdentityTest` 对应旧预期并定向验证。

这些项可按相同上下文组成少量红绿批次，不拆成几十个微任务。最后冻结一次受影响类集合＋相关格式检查，完成剩余独审，提交本任务产品差异和必要文档。DP01只有在实现、独审、验证、提交齐全时勾选，不能以本文的文档提交代替。

### 第四步：DP02一次最终矩阵与正式交付

严格按原roadmap第5节串行执行，不扩大为新的验证矩阵：

- Windows `bash scripts/build-desktop.sh full-tests`；确认包含集成测试。
- `:domain:jvmTest :domain:testDebugUnitTest`、`:app:testReleaseUnitTest`、`:test-desktop:test spotlessCheck`，非零测试结果。
- 有有效完整Desktop证据后 `build-desktop.sh build-only`，避免再次完整JVM。最终Windows地址只取日志 `Final unpacked EXE:` 并确认存在。
- Android统一 `build-android.py check --signing` / `candidate`；同证书、递增versionCode、R8、同APK SHA绑定隔离原生运行。
- macOS同一Desktop源码、隔离部署，按项目脚本build-only与原生运行；先读 `MACOS_ACCEPTANCE.md`。
- 实际两作品冷/热、直跳/顺读与EXIF、设置、输入验收；正常成功和人为失败兜底分开记录。全量失败保留现场，focused修复后再次完整测试仍需用户批准。

建议按D、E、最终交付分别报告剩余估算，不承诺“再跑一轮就结束”。原整体估计20–36小时，EXIF追加后23–42小时；本轮已用掉大量时间，旧估计不是继续空转的免责额度。接管后先取得D第一个真实双端闭环，再据实修订剩余时间。

## 6. 必须保留的实现约束与真实输入

- 事实与viewport必须核原owner签发的票据，不能迟到回调时重建当前票据。物理bytes变化严格换身份；首次可信方向确认只失效相关页，保留其他页原在途任务、定位、预算和已消费阅读原因。
- EXIF1–8共享坐标与有效方向；Desktop已旋转的Skia图不能再旋转。Android保持原SSIV分块，不以整页Bitmap替代。
- Android非NORMAL＋split＋crop的临时PNG由 `ReaderPageRegionPng` 流式生成，原生条带≤4MiB；这不是整个应用内存≤4MiB。holder登记原InputStream，recycle finally关闭后删除owned file；旧回调不能复活像素或删新文件。不要回退成延迟/重试删除。
- 额外事实补齐：2并发、每定位最多16新增页、32MiB新读取、15秒主动等待；正常可见请求不因可选额度用尽而取消。
- B1真实宽图只有第10页（1500×1067），不是第14页。预期尾部 `[10] [11,12] [13,14] [15,16] [17]`，17为RIGHT；宽14＋match15/16仅构造例。
- B2真实宽图为3/10/19/21；第2页LEFT必须保留，第20页夹19/21宽图，允许AMBIGUOUS/FULL居中，绝不能错误LEFT。算法没有识别广告。
- 真实输入在忽略目录 `app-desktop/tmp/udp-real-input-20261001/`；图片在其 `home/AppData/Local/Mihon/cache/network/reader-encoded/`，文件前缀章节15640/15652。头部记录 `app-desktop/tmp/udp-b1-dimensions.json`、`udp-b2-dimensions.json`；提交的两份CSV位于 `domain/src/commonTest/resources/reader/`。
- 两话原设置RTL、双页与自动匹配开启，无手动记录。真实漫画图片、完整日常DB、账号或临时URL不能提交。

## 7. 环境与最终交付预检结果

这些是已有事实，恢复时只核可能改变的状态，不重新安装或全面探测。

| 项目 | 已确认入口与注意事项 |
|---|---|
| Python / Git Bash | `C:/Users/feeli/AppData/Local/Programs/Python/Python314/python.exe`；`C:/Program Files/Git/bin/bash.exe` |
| Java / SDK | JDK21 `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`；SDK `D:/Android/Sdk`，platform36/build-tools36/adb已核 |
| 网络 | 当前会话设置HTTP_PROXY/HTTPS_PROXY为 `http://127.0.0.1:10808`，localhost绕过；不改系统代理 |
| 协调器 | 同worktree一个重型Gradle协调者；外层等待超时先status，不重复启动。Windows构建脚本需适当由协调器管理；Android统一入口内部已有协调器，勿嵌套 |
| Android发布 | 目前属性vc35、`0.19.4-aex.17`；签名check已通过。下个候选临发核跨worktree占用后递增，不能复用35，不新建证书 |
| Android设备 | 已有实体设备但无操作授权；预备AVD `mihon-pairing-api36`，尚未启动。隔离overlay/data运行，不改日常base；Release instrumentation用正常 `-Pmihon.testBuildType=release` 和签名脚本 `-Instrumentation`，不借Sync专项keep规则 |
| Desktop版本 | 本worktree预检为0.11.19.68；Windows/macOS需同一源码与版本，避免各自递增。发布前再核版本并发 |
| macOS | `ssh -F C:/Users/feeli/.ssh/config ... mbp-lan`曾成功；14.8.4，JDK21，约12GiB空闲。已有其他业务实例，禁止清理/覆盖；原repo `/Users/altair/github/mihon` 的 `c84ed331fa0b7851b62dc44a66a8602bb3f60876` 是本基线祖先，可在DP01提交后用thin bundle＋隔离worktree，勿重新clone大仓库 |
| macOS部署 | `MIHON_MACOS_DIST_ROOT` / `MIHON_MACOS_DEPLOY_DIR` 用隔离绝对目录，核实际脚本；不覆盖 `/Applications` 日常应用。真实窗口与AX/键鼠按经验文档，禁止截屏像素冒充离屏 |
| 真实源APK | `app-desktop/tmp/udp-real-input-20261001/copymanga-1.4.85.apk`已下载核版本/证书但未安装；ManHuaGui 1.4.28合法夹具在 `app-desktop/src/test/resources/extensions/real/keiyoushi-manhuagui-1.4.28.apk`；不要把转换JAR哈希与APK哈希误比较 |

日常Windows应用及其他worktree/Java实例不属于本次可清理对象。原A取证隔离实例已关闭；新运行使用独立profile与自由端口，8080为其他本地业务，不可占用。

## 8. 给新会话的起始指令

> 在 `D:/Codex/worktrees/0877/mihon` 继续实现双端共享双页 roadmap。先读 `docs/roadmap/2026-10-02-shared-dual-page-implementation-handoff.md`，保留全部未提交成果。复用A/B/C有效证据，先局部签收D共享接口并运行尚未绿的Desktop采样器限定测试，再完成双端匹配/设置和E既定缺口，最后按DP02一次全量与正式交付。不要从头探索、另建worktree、重做已签收工作或反复折腾格式工具。已有无限定向测试与统一EXIF授权继续有效；每轮围绕具体剩余验收收口。正式构建、完整测试和产品提交时点仍按原roadmap，文档交接提交不代表DP01完成。
