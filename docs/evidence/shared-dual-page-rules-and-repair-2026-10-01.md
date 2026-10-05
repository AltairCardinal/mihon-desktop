# 双端共享双页规则与修复证据

2026-10-03最新边界：用户不接受显示后位移，并进一步拒绝牺牲加载速度。原“保留全局算法、等完整依赖段、两张图同时显示”方案撤回。[补充方案v2](../2026-10-03-dual-page-stable-presentation-design.md)提出局部互选、结构索引和固定槽位渐进显示；其匹配差异及无索引冷跳前提尚未关闭，不能宣称已经满足全部约束。仅完成限定源码读取、一次4096组评分的设计数学推演和文档核验；数学推演不是production测试，也不能证明真实匹配质量。产品代码、构建与全量测试均未执行，DP02继续未完成。

状态：DP01 A–E 已完成实施、独立审查、相关验证及功能提交 `2a0e7570b7f359c4899e45f95769cc13e553f228`。DP02 首次矩阵后的修复提交为 `f95ffeb1e0c75a11f9e2326cd06b1500dd434e7c`；第二次矩阵剩余5项的限定修复、追加局部复审和31项相关验证已完成，提交为 `ca4abd37a32c57c7f7cb4486f1bdfa78af5aa0e1`。用户批准的第三次 Desktop 完整复验已自然通过：3250项、0失败、0错误、3条件跳过；共享两个目标、Android 应用及客户端的第二次完整结果仍适用。

DP02保持未勾选。2026-10-03按根因收敛完成Android test-only修复：39个既定方法分轮有效通过，确认原裁边／Copy夹具与恢复验收问题已修复，正式宿主APK未变。Windows平台协程依赖泄漏已按严格红绿修复，真实production Main与Library59项回归通过，Android解析图前后相同，修复提交 `84d98c7176`。用户批准的追加最终矩阵已执行Desktop完整3251项（0失败／错误，3条件skip）和共享JVM620项；共享Android525与客户端53复用有效结果，纯换行格式修正后检查通过，应用Android完整708项已完成，0失败／错误、7原条件skip。Windows/macOS正式70构建完成；Windows冷启动及用户B1/B2运行结果已取得，macOS完整原生运行与Android实体人工确认仍未完成；旧正式产物和此前失败记录保留，不把局部通过等同最终交付。

基线 `31a6ab7269967cc615ed54ac2ca58fe8d9b3f1db`。本报告为本次唯一聚合证据；事实与构造夹具分别记录，最新结果见末节。

## A 输入事实

| 输入 | 来源、设置和事实 | 验收边界 |
|---|---|---|
| B1《地下忍者》169话 | 主代理使用现有正式 Windows `0.11.19.65.1a3246a`，隔离 profile，通过 production 收藏详情选择 → 图源 → encoded 链取得全部17页头部；RTL、双页开启、自动匹配开启、无手动记录。仅第10页1500×1067为宽图，其余1127×1600。 | 输入取证，不是本次修复运行通过。脱敏夹具 `domain/src/commonTest/resources/reader/shared-dual-page-real-b1.csv`；真实图片未加入仓库。自动边缘评分尚未取得。 |
| B2《花甲公主》19话 | 同一旧正式EXE、隔离profile与production route取得完整22页（总11,801,744B）。宽图3/10/19/21分别1500×1060、1500×1060、1500×1060、1500×1000；1为1115×1600、22为1130×1181，其余1130×1596；无手动记录。 | 第2页为开篇独占左侧；第20页实际夹在19/21宽图之间，依据设计采用AMBIGUOUS/FULL，无页码补丁。脱敏夹具 `shared-dual-page-real-b2.csv`。维度默认组契约不假称已有实际边缘候选评分。 |
| 构造反例 | 17页仅第14页宽且匹配15/16；10页先宽4、后宽8；开篇单页／双页剩余后接宽图；孤页夹宽图；UNKNOWN/FAILED；首尾1/2/4/5页。测试名明确标为 synthetic。 | 验证通用规则，不宣称实际第14页宽。 |
| 既有合法夹具 | `domain/src/commonTest/resources/reader/dual-page-fit.csv` 为双端已有尺寸与fit测量表；既有 ReaderPairingAdjustmentContractTest 为手动调整契约；既有 Desktop presentation tests 固定原左半屏宽封面和旧 parity（本次被设计替代）。 | A只读核验；C/E 已按共享合同替代旧平台特例，有效行为迁移见后文，未将旧测试删除当验证。 |

仓库仅增加无身份、无URL、无漫画图片的拓扑／尺寸资料。图片与运行profile位于忽略的 `app-desktop/tmp/udp-real-input-20261001/`，不属于交付物。

A一次只读核验已完成：UTF-8读取两份CSV逐行对照真实头部JSON，B1恰为1–17、B2恰为1–22有序页，全部正尺寸且逐项一致；SPREAD分别为10与3/10/19/21。CSV只有公开章节名与尺寸，不含账号、个人稳定身份、URL或实际漫画图片。

## B 共享契约与接口

规则入口 `SharedDualPageRules.build` 接收有序 `ReaderPageId`、明确 UNKNOWN/PORTRAIT/SPREAD/FAILED 事实、方向、已提交手动边界和用户自动匹配选择，输出页身份、物理槽位、独占原因、确定性及稳定单元ID。输入不包含平台 parity 或封面开关。有限相位传播在每个索引最多保留“无待配纵页／前一张待配纵页”两种状态；UNKNOWN分支为两种合法分类，伙伴唯一才能确认配对。暂定单页不反馈为永久边界。宽图、手动和有效匹配重置确定起点。

`SharedDualPageSession` 是 presentation 状态，不修改 ReaderSessionCore 或进度数据库规则。其 identity 绑定 generation、page-list revision、有序页面内容版本和有效旋转；事实ticket绑定位置序号与requestId；重复事实幂等，最新跳页、关闭、旧身份和旧请求拒绝。用户目标一直保留，组首张只作UI anchor。稳定事件核对 identity、layout revision、位置序号、真实完整可见集合、停稳和 USER_NAVIGATION 原因，再调用既有 ReaderProgressPolicy；LAYOUT_ONLY不产生进度。

事实、保存及viewport回调必须回传本owner实际签发的原ticket。`captureViewport`在原生呈现开始时绑定identity、layout revision、位置、单元和readingEligible；不得在迟到回调内从当前字段重建。相同identity的owner重建不接纳旧停稳事件，稳定单元ID不包含这个临时能力。手动保存／会话调整改变逻辑目标时消耗旧阅读原因，新真实导航才重新具有阅读资格；自然单页无变化操作不消耗正常阅读。所有session与保存owner方法必须经平台已有owner/actor串行调用，I/O回调先切回owner；C需以实际延迟、关闭和乱序接线测试证明，不把普通集合当跨线程安全。

现有 `ChapterPairingRepository` formatVersion=1、漫画归属与 CAS，以及 `adjustReaderPairing` 未改变。`configure`不提供任意手动边界参数；共享保存owner接收真实读取／保存结果后才能修改边界，平台只执行既有应用coordinator I/O。B 阶段为中间可编译曾保留旧 primitive/facade；C/D/E 已将真实双页入口迁到唯一 owner 并删除旧产品决策。

共享保存状态明确区分临时文件TEMPORARY与恢复失败后本次默认DEFAULT_UNVERIFIED：二者均不写库，但后者可重新恢复。恢复后无效format/pageCount/corrupt记录由共享校验决定INVALID_RECORD反馈。保存中禁重入，成功结果须与提案及有效CAS新revision一致；失败保留最后成功边界，冲突必须先reload，旧owner/旧ticket结果拒绝。已被应用coordinator受理的写入生命周期不随页面关闭取消。

## B focused 红绿记录

冻结范围：`:domain:jvmTest` 限定 `SharedDualPageRulesContractTest`、`SharedDualPageSessionContractTest`、`ReaderPairingAdjustmentContractTest`、`ReaderPortraitSingleSlotTest`。未运行完整模块或正式构建。

| key | 结果与实际原因 |
|---|---|
| `udp-core-red` | 21项，12失败，20秒。规则失败包括 matched15/16被旧parity拆为15与16/17、后到宽8拆散5/6、宽封面非FULL、UNKNOWN误当portrait、结构单页槽位。新session脚手架拒绝所有事件，定位、事实和稳定进度6项失败。旧调整与基础槽位通过。日志 `.gradle-coordinator/udp-core-red.log`。 |
| `udp-core-green` 初次编译 | session字段误插入Identity类，出现Unresolved reference；是实现编译错误而非行为红测。修正上下文后才重新启动同范围。 |
| `udp-core-green` | 同4类21项全绿，35秒；日志 `.gradle-coordinator/udp-core-green.log`。尚未构成B最终交付。 |
| `udp-ticket-restore-red` | 用户明确批准一次恢复漏洞定向红测：1项失败，18秒；相同identity与本地requestId导致旧owner ticket被新owner误接纳。修复只接受本owner实际签发的原ticket；内容身份、位置和版本检查仍保留。 |
| `udp-segment-slot-red` | 用户明确批准一次槽位定向红测：1项失败，16秒；8页、4宽、match6/7时5误居中。修复段内确定起点，只有为紧随match/手动边界保留且无相反约束时使用开篇侧；普通夹宽孤页仍居中。 |
| `udp-pairing-owner-red` | 新增共享保存状态4项红测，4失败，55秒；未实现load/save导致缺失ticket与状态失败。 |
| `udp-owner-green` | 4类29项全绿，41秒；含真实维度、ticket、槽位、共享保存成功／失败／CAS与会话默认。 |
| `udp-owner-boundaries-red` | 同一轮接口门禁发现3项缺口，3项失败，31秒：本次默认无法retry、invalid记录静默默认、未settled定位被调整制造进度。 |
| `udp-boundaries-green` | Session限定14项全绿，16秒；修复上述三个共享门禁，不依赖平台补判断。 |
| `udp-settled-owner-red` | 1项失败，18秒；旧holder事件在相同identity/layout/位置/单元ID的新owner被接纳。 |
| `udp-core-format` | 本次6个Kotlin文件限定格式整理成功，6秒；相对路径hook先跳过，后因ktlint生成缩进后的max-line-length失败，人工分行后成功。无其他文件格式写入。 |
| `udp-core-refactor` | 原4类33项全绿，25秒；包含signed viewport门禁及全部定向修复。XML非零：Rules13、Session15、Adjustment2、SingleSlot3，0失败0跳过。 |

用户随后明确批准“无限次数的定向测试”，用于本任务预研中的具体TDD失败修复；完整模块、full、正式构建和独立审查预算不变。后续focused继续限定实际失败和新增行为，不无故重复。

首次沙盒wrapper下载被网络权限拒绝，尚未进入编译／测试；随后按规则使用已有用户Python和缓存，未安装环境。Gradle均经同worktree协调器串行启动，状态终结后才重试；当前会话配置代理、SDK和Python UTF-8。

## C 双端内容事实、呈现与定位证据（已签收）

已实现 Android `ReaderPage.pageFact` 派生 flow；本地 Ready 页在既有 `AndroidReaderChapterContentPort` 读取头部，HTTP 页在既有 runner 接受编码结果后、发布 Ready 前读取头部。复用 `ImageUtil.getImageDimensions` 的 bounds 解码，不新建网络客户端。流通过 `use` 关闭；普通元数据错误是 FAILED，取消异常重新抛出。此处记录 C 初期事实发布状态；双端共享 owner、主动补齐和最终签收结果见后续同节记录，初期绿本身不作为 C 完成证据。

| key | 结果与实际原因 |
|---|---|
| `udp-android-facts-red` | 测试夹具 `UniFile?` 未解包导致编译失败，68秒；不计行为红证据。 |
| `udp-android-facts-red2` | 一个独立目录行为用例正确失败，44秒；真实 DirectoryPageLoader→content port、未挂 holder，期望 PORTRAIT/SPREAD，实际 UNKNOWN/UNKNOWN。项目 test-retry maxRetries=2 使该失败运行3次，不计3个独立验收。 |
| `udp-android-facts-green` | 同一目录用例通过，44秒，XML 1项0失败0跳过。 |
| `udp-android-facts-routes-red` | 取消反例正确失败：取消被当元数据错误而正常完成。HTTP用例因虚拟时钟先超时，该项不计有效业务红；目录仍绿。34秒。 |
| `udp-android-http-facts-red` | 改用真实IO时钟后，HTTP独立用例正确失败：MockWebServer→真实HttpSource解析→canonical物化→encoded缓存→normal scheduler已 Ready，但fact仍UNKNOWN。37秒；失败自动重试2次。 |
| `udp-android-facts-routes-green` | 3个独立用例全绿，24秒：目录无holder、真实HTTP无holder、取消穿透。XML非零、0失败0跳过。 |
| `udp-desktop-presentation-red` | 新测试遗漏ReaderPageSize导入，53秒编译失败；修正后继续同类行为红测，不计有效红。 |
| `udp-native-presentation-red` | 48秒；Android3个独立用例正确红（每项自动重试2次，共9次执行）：UNKNOWN误配对、直接17晚到宽10后丢实际选中目标、未呈现单页已记读。Desktop4个有效反例正确红：匹配15/16被拆、后宽回推、宽封面半幅、UNKNOWN误配对；另1项中间孤页夹具实际仍处开篇，初红原因未隔离，不作有效证据。 |
| `udp-native-presentation-green` | 30秒；Android3项绿，Desktop4项绿。剩余中间孤页用例因构造场景缩为5页宽2/4，实际开篇连续单页规则LEFT有效，FULL预期未隔离中间歧义；不改变共享规格和FULL预期，改用既有B契约的9页宽4/6构造中间场景。 |
| `udp-desktop-interior-slot-red` | 修正9页宽4/6夹具，在本文件HEAD原production策略下单项正确红，15秒：实际[page5,null]半幅，预期只有page5的FULL槽位。Python临时保存当前C实现bytes与忽略tmp备份、try/finally原样恢复，恢复后独立byte SequenceEqual=True；未使用git回滚。此为构造输入。 |
| `udp-content-identity-red` | 49秒；Desktop修正后的5项全部绿。Android两个独立身份例正确红（各自动重试2次）：同List/ReaderPage实际stream/encoded引用替换仍使用旧owner；旧header迟到将PORTRAIT写到新stream。 |
| `udp-content-identity-green` | 上述两个身份例绿，33秒。ReaderPage有序实际内容epoch与stream/reference替换失效事实；header只在所捕获版本仍匹配时发布；store新owner保留明确逻辑目标。其后发现首次物化不应等同替换，共享状态延续另行红测，不据此称生命周期完成。 |
| `udp-header-budget-red` | 1个独立畸形JPEG多APP1头部例正确红，24秒；实际native元数据读取1,572,868字节，超过512KiB。失败自动重试2次。 |
| `udp-header-budget-green` | 4个独立用例绿，55秒：512KiB header保护、正常目录/HTTP、取消。采用共享MAX_HEADER_BYTES常量限制既有流读取，超限解码无确定尺寸时FAILED/居中；不改变额外请求2并发/16页/32MiB/15秒总预算。 |
| `udp-content-lifecycle-red` | 58秒；3个共享生命周期反例正确红：重绑后定位sequence归零、已提交restore变WAITING、已消费阅读原因重新生成进度。Android首次encoded绑定反例正确红：普通首次物化关闭已恢复owner。 |
| `udp-native-viewport-release-red` | 73秒；修复后的内容重绑3项及首次物化1项绿。新增共享释放与原生viewer恢复各1项正确红：旧票据仍复用，恢复误增定位sequence；Android失败项自动重试2次。 |
| `udp-native-viewport-release-green` | 68秒；共享Session19项、Android原生接线6项全绿。共享rebindContent保留逻辑意图、提交状态及消费原因，拒绝旧owner能力；原生恢复静默定位并释放原呈现票据。 |
| `udp-native-holder-scope-red` | 同owner、identity/layout/loc/unit不变的真实holder脱离单例正确红：脱离后原票据仍复用；1个独立用例自动重试2次。该项补足不依赖SharedSession.close的原生视口生命周期反例。 |
| `udp-native-holder-scope-green` | 50秒；共享Session19项、Android原生接线7项及原有挂载重排1项绿。holder开始呈现捕获原viewport票据，detach撤销原实例，实际图像完成后经共享settle门禁再进入既有进度链。 |
| `udp-fact-demand-and-collector-red` | 52秒；shared导入了未配置的kotlin.test而编译失败；原生首版fixture仅检查UNRESOLVED不能区分旧尺寸误接纳，虽PASS但不是有效门禁证据。 |
| `udp-fact-demand-and-collector-red2` | 43秒；共享依赖范围/2并发16页/32MiB15秒3项正确红。修正的原生fixture以真实PNG邻页已知、contentLock协调identity读取期间stream替换，旧PORTRAIT被现场新签ticket接纳形成错误pair，UNRESOLVED预期实际null，1项正确红、自动重试2次。 |
| `udp-fact-demand-and-collector-green` | 44秒；共享22项（Session19+依赖预算3）、Android13项（原生8+头部5）全绿。事实消费者改用原子(version,fact)快照，ensure后核采样epoch；共享预算目前只含状态契约，实际流额度/主动deadline取消仍在实施。 |
| `udp-holder-content-red` | 首次两用例中“已渲染旧图”正确红；异步尺寸夹具重复mockkObject清掉原格式检测桩，触发缺Windows原生库，不计行为红。纠正夹具后同key仅重跑异步例，日志保存该次有效红：第二次尺寸IO期间stream替换，旧图仍创建1个实际图像View，预期0。临时撤去新单处guard并finally逐字节恢复；首轮日志被同key覆盖，首轮未作为独立完成证据。 |
| `udp-holder-rendered-content-red` | 45秒；恢复原字节guard的异步例绿；原图已onImageLoaded、尚未settle时替换同ReaderPage stream/status仍Ready，hasRenderedVisiblePages仍true，1项正确红、自动重试2次。保留独立key日志。 |
| `udp-fact-read-quota-red` | 48秒；新增共享读前授额单例正确红，实际无限授额而非32MiB。既有原生8项与holder内容2项仍绿。 |
| `udp-http-fact-read-quota-red` | 36秒；真实HTTP 33MiB响应经现有源与encoded复制链，没有在32MiB停止，1项正确红、自动重试2次。不是下载完成后的裁剪计数。 |
| `udp-holder-old-failure-red` | 50秒；旧stream异步读取失败晚到同ReaderPage新内容后仍显示实际errorLayout，1项正确红、自动重试2次。 |
| `udp-content-and-read-quota-green` | 55秒；共享23项（Session19+Demand4）、Android17项（原生8+事实6+holder3），40个独立用例全绿。共享锁串行读前额度与退款；真实响应在读取前限制32MiB，正常可见页和缓存路径保留。原生成功、失败、已渲染像素均绑定内容版本。 |
| `udp-native-fact-deadline-red` | 76秒；原生额外请求消费者与HTTP共用请求各1项正确红：实际adapter未发起所需事实补齐，HTTP可选消费者没有等待已在途的正常请求。每项自动重试2次，不计6项独立用例。 |
| `udp-native-fact-deadline-green` | 80秒；共享23项、原生9项、事实7项、holder3项，42个独立用例全绿。实际adapter最多2个额外消费者，虚拟15秒后撤销悬挂消费者而保留正常可见请求；真实HTTP消费者共用既有scheduler请求，撤销可选消费者不取消正常物化。共享预算随内容重绑移交，视图重建不重置。 |
| `udp-stable-full-scope-red` / `red2` | 63秒／51秒；单页夹具初始配置后未重新测量，捕获的holder已脱离或尚未重新挂载，分别空引用／找不到holder；不是有效行为红。首次同批下载与归档无holder事实链1项绿，归档只在Windows缺Android libarchive JNI的流边界适配ZipFile，实际ArchivePageLoader排序/content port/header正常执行，不作为ART原生归档最终验收。 |
| `udp-stable-full-scope-red3` | 50秒；排空初始配置并重新测量后，实际单页图像onImageLoaded，但尺寸确认改变共享layoutRevision、native slot不变而holder原cap未刷新，VM实际settled未调用；1项正确红、自动重试2次。 |
| `udp-pending-tail-reading-red` | 39秒；真实ViewPager跳尾页，UNKNOWN FULL经native尺寸变RIGHT、新holder完成绘图，平台永久layoutOnly标记阻止原未消费定位的VM settled；1项正确红、自动重试2次。 |
| `udp-native-presentation-scope-green` | 2秒；共享任务名误写desktopTest，任务解析前失败，未执行行为测试；随后恢复项目既有jvmTest及核对各筛选名。 |
| `udp-native-presentation-scope-green2` | 10分11秒后仅停止本key记录进程树（CANCELLED/130）；已有共享23项及tail/旧图局部PASS不能当整轮绿。已知两个失败：两向量的第二宿主重复挂载夹具错误、无holder的retained viewer选中开始未捕获cap。残余自动重试不提供新证据，保留日志；其他Java业务未停止。生命周期修复及邻章offscreen影响继续定向验证。 |

| `udp-offscreen-presentation-red` | 11分57秒自然FAILED/1，未取消；实际邻章offscreen holder捕获scope撤销当前章待绘制原票据，onImageLoaded后VM settled为0，1个独立用例自动重试2次同因。用例执行9.284/11.184/8.312秒，suite跨度464.23秒，长初始化/结束原因未明，不归因产品或内存。 |
| `udp-retained-item-jump-red` | 4分54秒，限定该例的忽略tmp init仅本次关闭Android retry；真实同item moveToPage增加定位sequence至1，但holder原cap仍0，正确红。CI/仓库默认及最终脚本未改。 |
| `udp-native-scopes-and-desktop-owner-red` | 5分01秒FAILED/1；四个holder scope与retained viewer 5项绿。B2原Bitmap模拟编码未先核22页头部，page2宽度1440，不能记production几何红；Desktop3项在未实现的真实model original fact票据null处正确红。 |
| `udp-desktop-owner-and-b2-green` | 测试body：复用既有手工CRC/scanline PNG后，22页原生pageFact先与真实CSV逐页相等，production Directory/content port→header→adapter→native View page2 LEFT720/x0、page20 FULL1440/x0均PASS；仅借用真实尺寸的合法合成pixels，不冒充原漫画图。Desktop17晚宽10保留逻辑17 PASS；另2项新达到章节placeholder的错误single()夹具，已修选择实际含图片unit，待复验。最终自然FAILED/1（7分59秒），未当整轮绿。 |

| `udp-retry-restore-logical-vm-red` | 5分48秒FAILED/1；shared retry返回false、Desktop retry仍接纳旧fact、实际repo读取时共享仍WAITING_FOR_RESTORE，3个独立正确红。Desktop已修placeholder夹具后的3个原fact/viewport/17逻辑目标门禁全绿。AndroidVM首版PASS/17.171秒，但模拟PNG没有证明实际[4,5]，不记有效门禁。 |
| `udp-desktop-header-logical-and-native-vm-red` | 4分58秒FAILED/1；共享Session20项绿；Desktop真实目录头部没有进入共享facts、方向切换把逻辑5归为锚点4，2个正确红。Android使用合法PNG、实际选中[4,5]前置后，真实VM仍选4，1个正确红/无自动retry。6项Desktop旧持久化夹具仅Ready没有纵图事实，保存被UNKNOWN门禁阻止；补明确尺寸事实，不将Ready当PORTRAIT，也不删除CAS/关闭/身份断言。 |
| `udp-desktop-header-bound-red` | 41秒FAILED/1；Desktop model5项、持久化9项、真实B2目录→encoded/header→shared1项绿；实际header lease返回全部1.5MiB，超过512KiB；该断言衡量返回长度，不独立衡量底层I/O消费，1个正确红。共享open request增加可选maxBytes作为原lease身份，旧默认full消费保留。 |

| `udp-desktop-header-bound-green` | 31秒PASSED/0；共享Session20、Desktop实际model5、真实持久化9、runtime factory2，共36项0fail/error/skip。头部租约返回≤512KiB且full租约共存，释放后owner零租约；测试衡量返回长度与租约，主代理独立核代码factory→store.readPrefix→inputStream.use/readNBytes(maxBytes)，确认真实读前限制。publisher identity/workRevision签原ticket进入同一owner锁；取消/迟到与Compose仍未完成。 |
| `udp-native-retry-red-and-logical-green` | 5分01秒FAILED/1；合法PNG/实际[4,5]前置后的VM显式逻辑5门禁PASS；旧进度例在数据库事件已发送但主线程尚未消费事务receipt时过早检查native.read，不据此修改production提前置已读；新retry例绑定为空，尚非有效业务红。 |
| `udp-native-store-retry-red` | 4分42秒FAILED/1，无自动retry。旧实际VM进度例等待既有coordinator.awaitAccepted后所有read=true、普通组首anchor3、数据库/事件次数断言PASS。初始store事实采样在实际stream替换窗口新SPREAD被送入旧identity，1项正确红；retry错误场景被成功专用load helper先行拦截，仍非预算业务红。 |

| `udp-native-retry-valid-red` | 4分14秒FAILED/1；纠正成功专用helper对预期错误的拦截后，真实现有retry入口仍未更新共享预算，1个正确行为红。 |
| `udp-single-mode-and-native-red` | 37秒FAILED/2；共享SINGLE物理呈现及跨模式原票据2项正确红；domain任务失败后Android未执行，不能声称原生红。 |
| `udp-native-single-red-mode-green` | 4分57秒FAILED；新增共享mode2、store原子采样1、原生retry1均绿。普通RTL/LTR各1项在实际选择时提前写进度正确红；LTR本轮未独立执行到旧像素断言，不冒充该链独立红。 |
| `udp-single-header-save-red` | 8秒FAILED/2；SINGLE尺寸事实撤销不变FULL原票据、双页保存完成把当前SINGLE逻辑目标改成旧双页anchor，2项正确红。 |
| `udp-native-single-owner-green` | 5分38秒FAILED；共享Session24、事实8、store1、retry1、VM逻辑目标与旧尾组进度2，共36项绿；普通Single2未产生事件，不能将38项记整轮通过。 |
| `udp-native-single-diagnostic-and-compose-red` | 4分53秒FAILED；旧SSIV child listener调用新闭包，替换像素误认已画，1项正确红。Desktop实际Compose不消费共享pair及decoder未完成即写进度，2项正确红；Single2仍无事件，图片/scope/idle诊断通过但尚未定位。 |
| `udp-single-native-submit-and-old-listener-green` | 4分37秒FAILED；复用ReaderPageImageView原validator后旧SSIV listener单例绿；Single诊断将可空Long取为原始类型出现NPE，不记新的行为红。 |
| `udp-single-original-ticket-diagnostic` | 4分29秒FAILED/1；实际渲染、store owner、issued ticket、layout/location/unit/readingEligible断言均通过，但lastRead仍null；这是进一步诊断，尚不能认定共享算法失败。 |
| `udp-single-selected-holder-diagnostic` | 4分16秒FAILED/1；实际selected adapter page正确，但原捕获holder已不在pager.children。初始化偏好发射刷新了adapter、夹具在runCurrent之前缓存过期holder；补初始化排空及重新measure后才取得实际附着holder，不改变production进度策略。 |
| `udp-native-single-and-compose-green` | 4分18秒FAILED/1；普通Single真实RTL/LTR holder两项绿。Desktop13项中8绿、5个普通翻页后没有绘图回执，未记整轮通过。 |
| `udp-native-restore-red-and-compose-turn-diagnostic` | 4分35秒FAILED/1；实际repo读取时共享尚无owner而非RESTORING，原生恢复1项正确红。Desktop5项仍失败，真实原cap的sequence=1/readingEligible=true，但尚未消费；此诊断不重复计算为新红用例。 |
| `udp-compose-turn-draw-diagnostic` | 28秒FAILED/1；Desktop5项实际native presenter active/idle且原票据有效，但drawn为空。图像曾作为offscreen绘制，既有一次性acknowledgeDraw返回false，错误阻止新可见呈现反馈；诊断沿用既有反例。 |
| `udp-native-restore-and-compose-draw-green` | 5分15秒FAILED/1；原生真实恢复及读取失败恢复2项、普通Single2项全绿。Desktop13项11绿、仅LTR动画开/关的调整2项失败（预期逻辑2、实际1），保持原预期继续修复；不能声明整个C完成。真实绘图反馈改核原像素lease及transform key，既有观察ack仍保持一次。 |
| `udp-cause-and-restore-cancel-red` | 5分08秒FAILED/1；实际Scene用户翻到逻辑index3后，前方晚宽事实将目标误改index4、location sequence增加至2并产生额外阅读，1项正确红。实际VM读取中取消后，retained owner停留RESTORING而非RESTORE_FAILED、不能重新请求恢复，1项正确红。Android该次限定init关闭自动retry，两项均非编译或夹具错误。 |
| `udp-cause-and-restore-cancel-green` | 5分45秒PASSED/0；原生取消/恢复/普通Single5项与实际Compose14项，共19项0fail/error/skip。导航原因绑定真实interaction开始时的native unit身份和目标、结束后消费；metadata重排不生成GoTo或额外阅读。取消在NonCancellable Main只失败原恢复请求、保持取消传播和可重试状态，无业务toast。LTR temporary调整同步消费共享重定位结果，动画开/关均绿。 |
| `udp-cold17-and-header-prefix` | 4分56秒FAILED/1；真实Skia从>512KiB合法JPEG的512KiB截断头部取得1200×1600尺寸，1项PASS。新增原生冷17 HTTP联通例在等待真实可选请求到宽10时超时，未到槽位断言；补请求/状态诊断后继续，当前不能记冷17通过或认定产品原因。 |
| `udp-cold17-diagnostic-and-desktop-visible-red` | 5分07秒FAILED/1；Desktop实际runtime的选中呈现未发源图片请求（预期1、实际0），1项正确红。原生路径仅/chapter、所有页Queue；夹具在安装loader后才调用publishLoadedPageListForTest，其中beginPageListLoadIfNeeded清理并recycle刚装的loader，导致正常与可选请求均不可执行；不是产品补齐行为红。夹具改用真实begin generation→installPageLoader→content port→complete原generation顺序，保持10秒等待与产品32MiB/15秒上限。 |
| `udp-cold17-fixed-and-desktop-demand-red` | 4分30秒FAILED/1；原生冷17真实HTTP source→encoded缓存→header→collector→shared→native联通单例PASS：初始UNKNOWN FULL，晚宽10后[15,16]/尾17 RIGHT、逻辑17保留，实际View宽720/x720（视口1440）。使用真实B1尺寸的合法合成pixels，不冒充原漫画图。Desktop新夹具局部变量dispatcher遮蔽MockWebServer接收者，编译失败，未作为行为红；限定this.dispatcher后只复跑Desktop三项，不重复原生绿。 |
| `udp-desktop-fact-demand-red` | 24秒FAILED/1；真实runtime冷17只请求/image/16及/image/15，没有依共享依赖补到/image/9，1项正确红。normal首轮绿验fixture采用虚拟5秒超时，真实IO尚未完成便超时，不记新业务红（沿用此前request0有效红）；改用真实dispatcher等待实际Ready提交。EXIF6合法JPEG经真实Skia decode与peek均40×60，1项PASS，证明Desktop输入方向已归一，不强加额外旋转。 |
| `udp-desktop-fact-demand-green` | 49秒FAILED/1；正常呈现启动实际HTTP像素读取1项PASS；补齐在首次正常窗口替换时被取消，只出现/image/15、14、16、15，尚未正确到宽9，保留失败。 |
| `udp-desktop-fact-demand-green2` | 29秒PASSED/0；正常可见加载与冷17 actual begin→共享依赖→runtime scheduler→HTTP encoded/header→tail RIGHT两项绿，维持原逻辑目标与header lease释放。此为实际model/content链证据；下一步actualCompose的在途重建/正常消费者保护与物理几何仍未验收。 |
| `udp-desktop-compose-cold-window-red` / `udp-cold-scene-and-window-contract-red` | 22秒/24秒FAILED；actualScene真实尾图已绘制停稳且原可选14请求在途，晚宽9后尾17仍FULL，1个独立反例。第二次加等待最终RIGHT仍失败；同轮core首次测试参数遗漏为编译错误，不计行为红。 |
| `udp-native-window-contract-red` | 7秒FAILED/1；同视口先layout-only加载、再真实阅读提交时重新moveTo，撤正常原key且discard可选原key，1项正确红。 |
| `udp-cold-scene-native-window-green` | 32秒FAILED/1；actualScene冷链与normal2项绿，尾17实际350宽/x350、逻辑目标保留、原正常与补齐请求各一次。core8项7绿，新例将pending key直接当active提交失败属于夹具；改为正常完成后poll原可选key，不放宽production准入。 |
| `udp-native-window-refactor-and-http-budget` | 33秒PASSED/0；core8及真实HTTP配额/截止2，共10独立项0fail/error/skip。同视口仅去重schedulePlan，progressEffect保留；实际NetworkInterceptor body Source计数32MiB停止，正常33MiB及合法缓存保留；真实悬挂body在15秒deadline停止并删除未提交文件。 |
| `udp-desktop-b2-compose-and-fact-consumers` / `udp-desktop-b2-compose-and-fact-consumers2` | 首轮4秒编译错误（nullable语义ID及test extension import），修复后20秒PASSED/0，3项：B2真实尺寸目录→header→共享→实际Compose，页2 LEFT350/x0、20 FULL700/x0；可选消费者15秒虚拟超时不取消原正常请求且其最终Ready；close停止原可选wait并取消真实page port。消费者虚拟时间与上一行真实HTTP15秒分别标注。 |
| `udp-exif-orientation-contract-red` | 32秒FAILED/1；坐标四角/奇数覆盖契约绿，bounded EXIF parser以NORMAL占位，EXIF2期待MIRROR_HORIZONTAL实际NORMAL，1项正确红。 |

| udp-exif-original-identity-red | 15秒FAILED；首次方向确认未更新共享身份，expected CLOCKWISE actual NORMAL，正确红。PNG/WebP/big-endian/畸形及坐标四项仍绿。 |
| udp-exif-native-fixture-red-and-identity-red | 4分22秒FAILED；纠正重复Bitmap被recycle和JUnit4 message位置后，实际SSIV EXIF2画原红而非绿、真实头部方向仍NORMAL，两个正确行为红。初版透明像素/夹具断言不计红；该轮domain private facts访问编译失败另记，不冒称身份红。 |
| udp-exif-native-pixels-green-retry-red | 4分45秒FAILED；八方向实际SSIV Bitmap源像素与真实JPEG头部2项、事实路线8项绿；同bytes失败重试丢已知CLOCKWISE方向1项正确红。pan的250ms墙钟动画尚未完成，不据虚拟Looper时钟改production。 |
| udp-exif-metadata-scope-green | 5分24秒FAILED；方向6+Session24、native facts8+八向像素/头部/重试3，共41项绿；pan边缘剩余>1px因轮询0.5源像素误差先停止，夹具改等待实际中心误差≤0.01。Desktop新fixture returns空格遗漏编译失败，未执行行为验收。 |
| udp-exif-holder-red-and-metadata-refactor | 5分05秒FAILED；pan实际touch逆映射与250ms墙钟完成1项绿，Skia八方向真实JPEG像素1项绿；dual/ordinary真实holder仍传NORMAL方向2项正确红。Desktop交错两方向身份、逻辑、页表和各HTTP一次均先绿，但二页尾几何误期望LEFT，修为共享既有RIGHT后复验，不改production。 |

| `udp-exif-region-png-red-and-desktop-refactor` | 4分43秒FAILED；新条带 PNG 两项在未实现 adapter 处正确行为红；Desktop 实际HTTP交错两方向、逻辑与页表身份、各请求一次及尾RIGHT几何1项全绿。该HTTP例不冒充两个同时悬挂的原header lease。 |
| `udp-exif-holder-green-region-contract-red` | 4分33秒FAILED；dual八方向门禁绿；ordinary方向事实已通过但夹具导航overlay空binding异常，不记该例完成。PNG尺寸/像素通过但BitmapFactory.decodeFile在Windows夹具留下文件句柄，改用inputStream.use；fit组合和odd region各1项正确红，保留夹具异常记录。 |
| `udp-exif-region-pixels-red-and-fixture-green` | 4分39秒FAILED；合法PNG条带/失败释放2项与full双页/普通方向2项绿。修正导航fixture后，mirror+fit预期TRANSVERSE而实际MIRROR_HORIZONTAL、odd split原region仍null，2项正确红。实际SSIV Bitmap source半页仍完整61宽、预期LEFT30宽，1项正确红。 |
| `udp-exif-region-fit-green-artifact-color-red` | 21秒编译失败；缓存SSIV真实ABI为ImageSource.uri(Context,Uri)，及BufferedSource局部变量类型不足，修正夹具/调用类型后另跑，不记行为红。 |
| `udp-exif-region-fit-green-artifact-color-red2` | 4分39秒FAILED；holder4、实际SSIV十六方向半页1、原PNG2，共7独立项绿。显式sRGB声明缺失、原decoder.recycle抛错后无owner接收却留下1个File，2项正确红；主代理核fresh XML与终态。实际SSIV的1项循环16半页向量不记16个独立用例。 |

| `udp-exif-artifact-color-green-holder-lifecycle-red` | 4分46秒FAILED；PNG4全绿，含此前正确红的显式sRGB及decoder释放失败未交付文件清理。实际holder新例在手动bind与collector替换前置尚未完成时失败，未到回收断言，不记产品回收红；后改用实际collector完成替换。 |
| `udp-exif-holder-collector-lifecycle-and-two-leases` | 4分35秒FAILED；实际collector替换、原文件释放、旧native失败不删新文件、当前失败即时删除等前置通过，explicit holder recycle仍留下临时文件，1项正确红。Desktop真实Directory→encoded readPrefix→两原header lease同时悬挂→交错确认EXIF→model/Compose收敛1项全绿，无原lease取消或重读，完成后零lease；不冒充原漫画正式运行。 |
| `udp-exif-recycle-green-original-callback-red` | 4分48秒FAILED；文件回收断言已绿，但保存的原SSIV listener在外部recycle后仍将像素重新标rendered，1项正确红。PNG6（新增取消、超大单行）与Native实际双JPEG→同physical owner/预算/其他原cap/旧viewport拒绝/真bytes替换1项，共7独立项绿。新增协议覆盖不虚称各自独立红；主代理核fresh XML与终态。 |

| `udp-exif-original-callback-and-pending-region-green` | 4分43秒FAILED；旧onReady回收门禁断言已过，后续测试却将成功bind已移除的errorLayout当作非空，line270夹具NPE，不记产品红。实际在途region编码→外部回收→拒绝移交并删除文件1项、原PNG6、native交错1项，共8项绿；仅修改可空布局assertNull前置及旧失败回调后仍null断言，production呈现代际未改。 |

| `udp-exif-recycle-final-green` | 4分47秒自然PASSED/0；fresh holder XML 5项0fail/error/skip。原SSIV成功/失败listener在显式recycle后均不恢复像素或错误布局；在途region完成后拒绝移交并删除文件。原PNG6及Native双JPEG交错1项同代码绿复用。主代理独立核回收nonce与内部旧呈现回收先于新validator绑定的实际路径。 |
| `udp-c-local-format6` | 45秒自然PASSED/0，原ktlint1.8.0、120字符、trim/end规则。真正模块白名单为App24/Domain16/Desktop26；Desktop仅忽略tmp复用已加载Spotless插件，仓库plugin和.editorconfig未变。git diff --check通过，Kotlin改动无冻结66名单之外文件。 |
| `udp-c-affected-refactor` | Windows wrapper命令行过长，立即FAILED/1且未进入Gradle；原66绝对路径加selectors超出batch长度。改同66 UTF-8清单及init短路径读取，原focused筛选条件不变。首次长启动工具自动审批超时未执行，系统允许的一次重试成功启动wrapper；该超时未指出不安全，非产品测试失败。 |
| `udp-c-affected-refactor2` | 12分22秒自然FAILED/1；三模块原规则Check覆盖App24/Domain16/Desktop26。fresh XML为Domain30、Desktop27、Android54，共111独立项，109绿/2fail、0error/skip。两失败分别是holder替换后原临时文件仍存在、初始事实测试仍期待已换physical bytes的旧缓存PORTRAIT被消费；保留失败，未将整轮称为绿。 |
| `udp-c-two-failures-diagnostic` | 23秒配置失败；PowerShell将未整体保护的property拆为无后缀路径与`.txt`独立argv，init读不存在文件，未进入行为。下一轮从argv数组构造完整UTF-8清单property，协调器JSON确认未拆分。 |
| `udp-c-two-failures-diagnostic2` | 15分04秒自然PASSED/0；fresh XML两项各1执行、0fail/error/skip，实际case为14.73/12.699秒。原holder释放断言保留，异常诊断只在捕获释放失败后执行，本轮未触发，不据此声明原组合失败原因已明。初始事实夹具按已冻结可信缓存边界同步：真实bytes替换后旧原子快照不得seed到原owner，维持UNKNOWN/FULL；下一ensure使用新physical身份/SPREAD收敛，旧original request拒绝。该夹具变更经主代理确认，不是新product行为红，不放宽production。 |
| `udp-c-holder-and-store-refactor` | 9分38秒自然FAILED/1；fresh XML Holder16中15绿/1fail、Shared Wiring11全绿，共27独立项26绿/1fail，0error/skip。原临时文件替换后仍存在，随后显式Files.delete成功；这只证明原检查当时文件未释放，尚不能证明首次delete是否调用、返回false的原因或native/Windows资源占用。主代理签收可信seed与Shared11结果，余下仅原Holder16组合定位。 |
| `udp-c-holder-first-delete-diagnostic` | 9分22秒自然FAILED/1；fresh XML Holder16中15绿/1fail，0error/skip。首次delete确实调用，其一次等价Files.delete抛FileSystemException“另一个程序正在使用此文件，进程无法访问”；随后显式删除成功仍不改变原释放断言失败。主代理签收正确资源红。SSIV URI provider自行打开在途流、view.recycle仅回收已安装decoder，是符合固定源码的解释，未识别外部占用PID。 |
| `udp-c-owned-region-stream-green` | 10分04秒自然PASSED/0；fresh XML原Holder16项0fail/error/skip，App3原Apply/Check通过。原临时文件占用红以先登记原InputStream、recycle finally关闭再删文件的最小修复转绿；不把case执行33.351秒与整个Gradle墙钟混为一谈。清理及同范围确定性在途句柄关闭门禁随后复验。 |
| `udp-c-owned-region-stream-refactor` | 9分51秒自然PASSED/0；fresh XML原Holder16项0fail/error/skip，App3原Apply/Check通过。清理后二次显式删除已移除，首次释放成功记录及原exists断言保留。同一真实holder生命周期用SSIV公开setExecutor暂停下一初始化，捕获尚开原stream，实际recycle后read/available均IOException、owner字段null、原File消失；原成功/失败listener均不能复活像素/错误。原PNG编写、header、geometry、fit/split、旧内容和cold17门禁仍在同16范围绿。 |
| `udp-d-matching-contract-red` | 50秒自然FAILED/1；fresh XML6独立项6fail、0error/skip，采样计划缺失、透明未合成、评分/选择无输出导致正确行为红。 |
| `udp-d-matching-contract-green` | 49秒自然PASSED/0；同6独立项0fail/error/skip，新增2文件原Apply/Check通过。共同50点原坐标/EXIF书脊侧、固定sRGB白底合成、白/暗/低方差及严格30阈值、完整候选manual先屏蔽后强边/tie earlier选择成立；尚不代表两端采样/设置接线完成。 |


| `udp-d-samples-owner-red` | 44秒自然FAILED/1；fresh XML9项（原规则6绿、新owner3红），0error/skip。三个新增用例均在第一个正向accept断言因尚缺实现正确失败；不宣称之后尚未到达的cache/ticket断言都有独立红因。 |
| `udp-d-samples-owner-green` | 49秒自然PASSED/0；同9项0fail/error/skip，3文件原Apply/Check通过。有限样本缓存、完整双方向标量、同owner物理cap、方向metadata局部失效、真bytes新owner/关闭及PORTRAIT准入实际执行通过。 |
| `udp-d-sample-repair-red` | 44秒自然FAILED/1；fresh XML11项10绿/1fail，0error/skip。capacity2、到达1→3→4→2的真实owner只能确认2条相邻关系，expected3/actual2正确红；输入list防御复制强化用例直接绿，不虚称新红。 |
| `udp-d-sample-repair-green` | 39秒自然PASSED/0；同11项0fail/error/skip、3文件原Apply/Check通过。共同requiredSamples按下一未评分邻边推进，原encoded重新取样先评分再淘汰，两种到达顺序完成相同3关系后都选1/2+3/4；不能代替后续实际平台lease补边接线证明。 |
| `udp-d-session-samples-red` | 44秒自然FAILED/1；Matching11绿，新Session2正确红，分别缺需求列表/原sample cap。 |
| `udp-d-session-samples-green` | 49秒自然PASSED/0；fresh XML Session26+Matching11共37项0fail/error/skip、5文件原Apply/Check通过。Session唯一持有样本owner，原header独立，完整匹配重排保逻辑目标/已消费read，manual恢复和成功保存后才屏蔽候选，metadata原cap局部失效/physical rebind/close通过。先前聊天曾误计43，按fresh XML纠正为37。 |
| `udp-d-sample-cancel-red` | 26秒自然FAILED/1；fresh XML1项1fail，expected owns=false/actual=true，正确证明关闭matching后原采样cap仍准入。 |
| `udp-d-sample-cancel-green` | 49秒自然PASSED/0；fresh XML Session27+Matching11共38项0fail/error/skip、5文件原Apply/Check通过。关闭matching或切Single撤原sample requests，重开不复活；confirmed same-content缓存/标量与原header票据保留，采样不制造导航/进度。 |

| `udp-d-shared-matching-refactor` | 49秒自然PASSED/0；fresh XML Session27+Matching11共38项0fail/error/skip、5文件原Apply/Check和git diff --check通过。机械清理计数为confirmedEdgeCount，明确包含匹配及拒绝评分的已确认关系；共同接口文档同步。平台原encoded/region lease与设置仍待D接线，未提前宣称D完成。 |

C组合复验边界：首次27项组合失败保留后，仅继续原Holder16组合定位首次delete，未重跑Shared11及其他范围。正确资源红之后，最小实现仅在临时PNG呈现路径先打开并登记owner持有的原InputStream，交原SSIV的inputStream provider；recycle finally关闭原流，再由holder释放原File。普通BufferedSource路径、原native分块/裁边/方向语义不变，不延时、不重试删除、不聚合PNG或Bitmap。最小绿之后，清理失败后二次显式删除，保留首次释放记录与原断言；同一生命周期用例以SSIV公开setExecutor暂停下一初始化，先证明原stream仍开，再验证实际recycle关闭同stream、清owner字段、删除原文件，保留原成功/失败listener拒绝。重构自然全绿后，C原111独立项的两个缺口均已关闭：Shared11以可信seed契约同步复验，Holder16以正确资源红→最小绿→清理重构复验，其余84及原66Check复用仍适用的结果，不把分轮数量累加成更多独立验收。

格式过程边界：早期IDE hook调用发生两次Windows文件映射异常（不同文件），停止并发源码读取后的有界重试未稳定，原因未证实。去除IDE hook后，临时配置先在projectsEvaluated修改过晚，随后显式跨模块FileCollection被Spotless的within-project边界拒绝；这些失败及仅App临时PASS均不计66完整门禁。最终按真实模块内FileCollection24/16/26、在task冻结前配置，先临时预格式落盘spacing，再机械拆长行和缩短测试描述，最后原120规则全部Apply通过。测试向量和预期均保留；测试描述缩短后冻结筛选使用保留前缀，并须由fresh XML非零计数确认。未添加永久formatter配置或依赖。

主代理已独立核对上述 Android 事实发布与取消实际产物，作为 C 同一轮审查的局部结果；视口、身份、预算及其他 route 仍须继续验证。首个目录红测3次执行的原因已核对为仓库 test-retry 插件，曾在交流中推测为引擎重复，现已纠正。


### D 双端真实采样与设置接线（接续实施）

原 A/B/C 签收证据继续复用，未重跑完整模块或正式构建。主代理已独立签收 D 共享 5 文件/38 项接口后才消费。
两端实际设置事件经原偏好、sampler、共享 owner 到实际布局；Desktop factory 负责接线，测试没有补绑生产缺失的 collector。
样本来自 Ready 原 encoded/region lease，不要求显示 full-page cache；默认 false，Desktop 旧 true 迁移保留。
Android 使用共同原坐标/方向计划，原子消费原 contentFact 后准入，不能仅核编码版本绕过未消费的新方向。
Session 兼容 matchedPairs 注入、旧产品 EdgePixelMatcher 评分/选择、历史并集和 manual 整章禁用已删除；
旧 PageTransform/presentation helper 的最终统一、旧进度预期属于 E，不据此提前宣称 DP01 完成。

| 协调器 key | 实际结果与边界 |
| --- | --- |
| `udp-d-desktop-sampler-green-resume` | 40秒自然 PASSED/0；Desktop 原 region sampler 3项、0fail/error/skip，不带临时格式 init。复用原正确红。 |
| `udp-d-desktop-wiring-red-resume` / `udp-d-android-settings-red-resume` | 分别46秒/84秒自然 FAILED/1；Desktop 实际设置已持久但共享匹配未形成，Android 实际 ReadingModePage 无设置，各1个正确红（Android retry3次）。 |
| `udp-d-platform-wiring-green-and-sampler-red` | 124秒自然 FAILED/1；Android 设置1绿，sampler两例及实际 viewer 接线一例因缺实现正确红（每例 retry3次）。Desktop目标pair/偏好已过；cacheRevision错误地被当作Bitmap条目数的断言失败不计产品红，改核实际cache.entryCount。 |
| `udp-d-platform-wiring-green-resume` / `udp-d-platform-wiring-green-resume2` | 首轮37秒FAILED：合法后续补边仍在途时要求立即零lease，改为关闭后自然闲置释放；第二轮57秒自然PASSED/0，Desktop真实设置/原lease1、Android设置1/leaf2/viewer1共5独立项0fail/error/skip。 |
| `udp-d-resume-local-format` / `udp-d-resume-local-format2` | 首轮18秒property未整体保护导致路径拆分，配置失败不计行为红；使用argv数组后25秒PASSED/0，冻结App11/Domain2/Desktop10共23文件原规则Apply/Check。 |
| `udp-d-deadline-and-migration-red` | 约1分钟自然FAILED/1；共享38及Desktop产品迁移8项绿，Default旧进度预期1红留给E。Desktop初版截止例直接绿，不能证明截止；Android三处JUnit断言参数顺序编译错误不计红。 |
| `udp-d-active-wait-red` / `udp-d-active-wait-green` | 红75秒FAILED/1：原region工作未完成时，虚拟15秒后仍未取消主动wait，两端各1个正确红（Android retry3次）。最小实现复用owner可选预算remainingWaitMillis，绿41秒PASSED/0：两端各1项0fail/error/skip；native返回后旧结果不发布、资源自然释放、逻辑目标保留。 |
| `udp-d-platform-refactor` | 自然PASSED/0但实际Android matching XML有一次失败后retry成功，不能记整轮全绿。失败为显式retry未新增采样expected20/actual19；保留证据并继续原两用例修复。共享38有效结果复用；Desktop10、Android设置1/leaf2绿；23文件原规则通过。 |
| `udp-d-retry-handoff-green` / `udp-d-retry-handoff-refactor` | 56秒/50秒自然PASSED/0；两端原两用例各1执行，均0fail/error/skip，无retry后消红。显式retry取消旧job后清negative，超时只核当前owner/Job写回；重构后同范围及冻结23文件原Apply/Check全绿。 |

实际 Android viewer 用例以19页合法PNG原编码执行18次region采样，确认17条相邻边跨越16页样本缓存仍全部评分；
改变原 stream 身份后从已有编码修复缺边，失败停止、普通rebuild不重复、显式原图retry才允许一次新尝试。
原失败callback与新retry交错必须撤旧matching Job；deadline callback也只允许当前owner/Job记录negative，不能污染新pass。

原 native decoder 的颜色来源经主代理固定源码核验：
[java_wrapper.cpp](https://github.com/tachiyomiorg/image-decoder/blob/41c059e5405c1d982c937767707270ee96c32614/library/src/main/cpp/java_wrapper.cpp)
在 icm_stream 为空时使用 cmsCreate_sRGBProfile，JPEG源ICC到该targetProfile转换，支持 false/null目标sRGB约束。
Windows Robolectric只替换不可加载的JNI region边界，执行真实编码像素/ARGB/共享白底合成及实际DisplayPage，不能冒称JNI验收。
原JNI EXIF1–8与PNG alpha正式ART入口已准备在SharedSpreadNativeAcceptanceTest；尚未在实体设备或模拟器运行，归DP02。

### E 保存、恢复与动画输入（接续实施）

Android 调整按钮仍走原 ReaderActivity、ReaderViewModel 和 application coordinator；
保存前 committed 不变，失败经原 Save ticket 回写 SAVE_FAILED 后可再次点击，CAS 冲突先按原 coordinator 重读。
pendingSave、previewAdjustment、applySaved 与复制的 snapshot/canSave 已移除，owner 和原票据决定结果准入。
既有 resolveReaderChapterEntryPage 事件将同一个恢复位置提交给共享 owner；不同目标才产生定位，
重开不再因 restore 前初始化的位置而退回封面。Loaded fallback 的夹具复用既有页列表，
与 production ChapterLoader.beginPageListLoadIfNeeded 的 Loaded 返回行为一致。

新 Load 可以取代未交付的 Save 票据；已接受的 repository 写入仍串行完成，新 read 排在其后。
RESTORING 保留 committed 且禁调整；旧 Save 成功或失败回调不能覆盖新 RESTORING/READY。
该共享接口的红、最小绿及重构 28 项已由主代理同轮局部签收。

| 协调器 key | 实际结果与边界 |
| --- | --- |
| `udp-e-android-save-red` / `udp-e-android-save-green2` | 原按钮失败与 CAS 两项正确红：SAVING 锁死、实际 repository 未重读；修正 callback 完成等待及无关边界夹具后，最小绿 fresh2 零 fail/error/skip。 |
| `udp-e-android-save-refactor2` | CANCELLED/130，不记整轮通过。原 A→B→A 延迟 callback 场景发现 SAVING 拒绝新 Load，随后 fixture cleanup 等未排空 StandardTestDispatcher；仅停止该 key 进程树，保留原失败。 |
| `udp-e-reload-and-input-red` | 新共享 reload 请求为 null 的正确红；Desktop tap 的 RTL/LTR facts 与 RTL matching 三项 expected3/actual1 正确红，key 三项直接绿。drag 三项未进入动画，属夹具前置失败，不计产品红。 |
| `udp-e-reload-owner-green` / `udp-e-reload-owner-refactor` | 两轮自然 PASSED/0，fresh SharedDualPageSessionContractTest28 单次零 fail/error/skip；覆盖成功/失败写结果、RESTORING/READY 旧回调拒绝。 |
| `udp-e-unsettled-drag-red` / `udp-e-unsettled-input-green` | Touch 事件使原 drag 三项进入真实 idle=false 窗口后直接绿；前者 fresh9 仅 tap3 失败，后者自然 PASSED/0、fresh9 零 fail/error/skip。 |
| `udp-e-android-save-refactor3` / `udp-e-save-reopen-green` | 前者自然 FAILED/1，两个重开场景 expected[4,5]/actual[0]，default fallback 夹具因每次换 pages 超时；修复既有 entry 事件接线及 Loaded 夹具后，后者80秒自然 PASSED/0，fresh8 单次零 fail/error/skip。 |
| `udp-e-save-and-input-refactor` | 自然 FAILED/1；Android fresh8、实际双页输入 fresh25 单次零 fail/error/skip 可复用，ReaderPageTurnPresentationTest40 中18 fail 不计组合绿。single 正确目标红为 rapid taps 两方向 expected2/actual1、user turn during external animation 两方向 expected0/actual3；其它像素/旧双页夹具失败不计这些业务红。 |
| `udp-e-input-migration-green` / `udp-e-presentation-owner-red` | 自然 FAILED/1；旧独立 renderer 未发布 header facts，竖图 fit 外侧 bars 又被当 decode gap。补原 fact 票据及区分内容区/全视口采样后，后者仍有实际动画两色采样与外部目标漂移失败；新增无 owner grouping 却自行配对的 presentation 红例正确失败。 |
| `udp-e-native-target-green` | 50秒自然 PASSED/0，fresh DualPageCurrentPageWiringTest25 + ReaderPageTurnPresentationTest40 共65单次零 fail/error/skip。tap 动画前记录 PageId，按新 units 映射；same-target native feedback 不重启动画，连续点击与 latest goto 保留。 |

独立 renderer 的尺寸输入经原 captureDualFact/acceptDualFact；它不冒充完整 runtime header collector，
后者复用 C/D 已签收链路。内容区采样保留 undrawn=0 失败；动画两色来自全视口原像素，不过滤空白获得绿。
E1/E2 保存与实际输入已局部签收；E3–E5 接续结果见后文，早期组合失败不能称为全绿。

## 未完成项

- B共享高风险接口门禁已签收；整个功能批次未提交，DP01保持未勾选。
- C两端内容发布、共享呈现/定位/进度、补齐预算及EXIF适配已完成focused重构并由主代理整体签收；D真实匹配/设置/失败重试/截止已完成focused重构并通过主代理收口独审；E保存、输入、旧权威迁移、真实parser及正式输入入口已实现，最终相关验证已完成，最终独审、整合和统一提交尚待完成。
- U02/U06真实源正式修复产物运行，U20最终Windows/Android/macOS交付。当前不能宣称两个BUG已修复。

## 最终环境只读预检

方向范围已追加：用户明确批准统一EXIF1–8实际显示及Android原生适配约3–6小时。以下原库差异是适配前观察，不是最终允许边界；共享方向身份、归一实际呈现、触摸/缩放/平移已完成C focused门禁，D采样已消费同一契约并签收，ART最后在DP02核验。

输入方向事实边界：Desktop合法EXIF6 JPEG真实Skia decode/peek均从原60×40归一为40×60，发布其有效尺寸且不再二次旋转。Android当前固定JPEG decoder按受控源码使用原始朝向：Gradle/POM pin解析为完整commit `41c059e5405c1d982c937767707270ee96c32614`；[decoder_jpeg.cpp](https://github.com/tachiyomiorg/image-decoder/blob/41c059e5405c1d982c937767707270ee96c32614/library/src/main/cpp/decoder_jpeg.cpp)直接使用JPEG原宽高，仅APP2处理ICC、按扫描行复制，无APP1/EXIF几何转置；[java_wrapper.cpp](https://github.com/tachiyomiorg/image-decoder/blob/41c059e5405c1d982c937767707270ee96c32614/library/src/main/cpp/java_wrapper.cpp)按该原bounds建立Bitmap。主代理完整核读以上文件、decoder_base.h及ImageDecoder.kt；这是固定原库来源核对，不能假称Windows Robolectric已执行JNI。DP02仍须以同编码EXIF图在原正式Android ART核实实际朝向。原库差异仅为适配前证据；用户已追加统一EXIF，Android方向显示、split/fit/crop适配已完成C focused验证，ART验收仍待DP02完成。NORMAL历史拆分保留，屏幕旋转/crop不改变源事实分类。

主代理已核验 Android `check --signing`：现有JDK21.0.11、SDK36/BuildTools36与既有正式证书可用；未构建或递增版本。macOS `mbp-lan` 为14.8.4、JDK21可用，已有其他Java及隔离Mihon业务实例，磁盘约12GiB；DP02必须隔离源码与部署目录并核对容量，当前未执行远端构建。输入取证旧EXE已通过 `/test/shutdown` 关闭。

主代理另已准备未安装的原源APK：忽略tmp中的copymanga-1.4.85.apk（455373字节），SHA-256为`0a208f10b4f8fa3f3f0fae7704935e1a487c6ee342722c7e7818a02ad8c8dd6a`；aapt2已核对版本85/1.4.85，原证书SHA-256为`896d1ca35175c42baf61b1a157e4354df9568beed4ccc82a03762d1167005e79`、与原sidecar repoFingerprint一致。sidecar artifactSha256对应Windows转换JAR而非APK，首次跨格式哈希对照失败已通过实际代码和本地哈希澄清，不记产品失败。ManHuaGui原1.4.28 APK沿用仓库合法夹具；这些准备不构成安装或运行验收。

### E3 旧权威清理与有效行为迁移

删除 Android 只被旧纯算法测试消费的 PagePairingAlgorithm/PairingState facade，以及 domain 的 ReaderPagePairing/ReaderPairingState/PagePairingOptions 与索引推槽重载。旧测试中独立 offset/parity 配对计算和 facade 对比不再作为产品证据；共享封面、manual 局部边界、late facts、完整匹配候选和修复由 SharedDualPageSession 原合同与两端真实 adapter/wiring 覆盖。方向、旋转、split、virtual geometry 的有效原测试保留；单页槽位结构原因与调整合同迁到实际共享 owner，未复制预期分组算法。Desktop renderer 仅消费 owner units，不在缺失 owner 时另建规则 fallback。

旧 visible-only Single/Dual settle 以及 generic layoutProgressAnchor 删除。相关模型/会话进度用例迁到 ReaderPagerNativePresentation 的原始 owner ticket、实际 slot drawn 与 idle 门禁；它们是 presenter 边界验证，不能替代 C 的完整 Compose 绘制证据。DefaultReaderMode 的手动记录 [4] 改由原 repository/coordinator 恢复，模式/尺寸变化保持逻辑页 2，不能再退回物理双页首槽 1。Webtoon 的 LazyColumn 多页恢复仍须保护原进度，因此保留仅在 WEBTOON 恢复/模式路径设置、由 settleWebtoon 消费的 webtoonRestoreAnchor；单页/双页不能使用此字段。

udp-e-presentation-migration-and-cover-red 自然 FAILED/exit1，其中宽封面 split=true 导致同 PageId 两个 half，是有效业务红；其它失败为测试 fixture 的 variance 不达门槛及未传 owner units，不计产品红。最小实现让 source ordinal 0 始终 whole。udp-e-shared-presenter-green 自然 FAILED/exit1，fresh XML 103 项中 101 绿、ReaderScreenModelTest 2 项旧影子/模式夹具失败；宽封面、实际 [2,3] 前置后的 matching tap/key/drag、Default [4] 恢复及其进度门禁均绿。原先 65 项绿中的三项 matching fixture 只触发重建、没有真正接受边；以本轮实际 [2,3] 前置的三项重新验证替换该部分结论，不将 fixture 错误冒充产品红。

### E4 原 HTTP parser、错误与恢复

选用既有 MangaDexSource 的真实 at-home JSON parser，经原 DesktopReaderChapterContentPort、CanonicalChapterMaterializeAdapter、session executor 和 ReaderScreenModel 运行；没有复制 parser、增加产品客户端或重写第三方源。MockWebServer 返回真实 result/baseUrl/chapter/hash/data/dataSaver 形状和实际 PNG。空 data、缺失 chapter、403、429、500、畸形 JSON 分别到达原 NoResults/MalformedData/Authentication/RateLimited/Server 契约；每次同章 retry 后通过正常 viewport 调度恢复 Ready，未以先行缓存代替恢复。

udp-e-parser-recovery-and-testmode-offline-red 自然 FAILED，仅 Test Mode 失败；fresh parser 1 项 PASS，内部包含六种错误及同章 HTTP 恢复。首轮恢复等待遗漏 viewport 正常调度和 Test Mode 章 identity 不匹配属于夹具错误，均不计产品红。既有 Android empty/malformed/pages:17 哨兵只证明 port 错误传播，不能冒充 parser 覆盖，也不能以 Desktop JSON 绿代替 Android 原扩展链。

### E5 mounted owner、正式输入与 ART 可执行入口

Test Mode 不再用 open/updatePage 影子页码回答产品状态。实际 ReaderViewport 挂载/卸载同一个 ReaderScreenModel；GET 读取 owner 当前页、页数、章身份、共享 units、方向、双页/自动匹配设置以及当前 display/visible PageId 索引。HTTP 翻页/定位和邻章动作在 EventQueue 调用既有 production 动作，仅成功动作记入历史；没有真实邻章 callback 时不能由旧 hasNext 影子发明邻章。旧 A 关闭回调不得清新 B 的 generation 保护保留，未 mount 的 B 仅存在导航请求，currentChapterId 为 0。

udp-e-testmode-matching-owner-red fresh1正确红 expected currentPage1/actual0；匹配章 fixture 后以未接 mounted consumer 的原条件确认。udp-e-testmode-matching-owner-green fresh1自然 PASS。随后主代理发现切条漫/加载/配对错误卸载 pager 时 ReaderViewport 仍存活，旧坐标未清：udp-e-native-compile-and-pager-unmount-red fresh1正确红 geometryAvailable expected false/actual true，Android compileReleaseAndroidTestKotlin 同轮通过；udp-e-pager-unmount-green fresh mounted Scene1 + navigation generation1 自然 PASS，单次零 fail/error/skip。实际 modifier 的 DisposableEffect 仅清本 model/本 publisher 的几何，不让旧 dispose 清新 pager。

nativeInput 提供实际 Compose viewportPixels、density、实际 AWT content-origin 换算的 viewportScreenPoints、三行三列 tapPoints，坐标系 awt_screen_points；windowPresent/showing/focused、Compose window 与 reader owner focus 缺失时 available=false 并给出 reason。该接口不读取屏幕、不做 screenshot、不建立控件树；离屏 Scene 的几何可以存在但无 AWT window 时不可原生输入。HTTP inputKind=application_action 不能当 physical key/mouse 验收。

DP01 冻结时准备的 Android release instrumentation 入口（历史记录；DP02 发现 R8 ABI 不兼容，当前修复入口见文末）：
- SharedSpreadNativeAcceptanceTest：原 JNI region 解码、共同 sRGB/alpha/EXIF 采样。
- SharedReaderViewNativeAcceptanceTest：actual ReaderPageImageView/SSIV，EXIF1–8 JPEG 四象限、full/split/fit/crop 的实际 Canvas 像素以及原 MotionEvent 双击缩放/拖动平移。Windows 仅编译，不执行或伪记 JNI/ART 结果。
- InstalledSharedReaderSourceAcceptanceTest：隔离 emulator 中已安装且已信任的原 ManHuaGui/CopyManga APK，原 ExtensionLoader、HttpSource parser、ChapterLoader、shared host 和 pageLoader。ManHuaGui packed HTML 复用既有合法仓库 fixture（SHA-256 795f701ea8a238dd1a1a368f31bd42c4a1d9bddb55cee47d8cc8bc9a1e8bcb52）；CopyManga 需要原真实响应文件，不生成替代 parser。输入文件限制在隔离 app 私有目录。原 NetworkHelper public getters 的 client 身份识别 R8 后实际存活 OkHttpClient 字段，临时附加受控本地 response 路由并核所有 public getters 与原 extension.client 消费它，finally 恢复原对象，不新增 keep 或关闭 R8。此受控响应链不是 TLS/代理阶段验收。

正式 ART 运行须在 DP02 构建隔离 release 测试包后按原 AndroidJUnitRunner：
adb shell am instrument -w -e class eu.kanade.tachiyomi.ui.reader.viewer.SharedReaderViewNativeAcceptanceTest <本次测试包>/androidx.test.runner.AndroidJUnitRunner。
源入口 class 为 eu.kanade.tachiyomi.ui.reader.InstalledSharedReaderSourceAcceptanceTest，可选 sharedReaderSourceId/sharedReaderChapterUrl/sharedReaderPageCount；CopyManga 必须给 sharedReaderSuccessFile/sharedReaderEmptyFile/sharedReaderMissingFile/sharedReaderMalformedFile 私有绝对文件路径。这些入口不安装 APK、不写生产漫画库、不操作实体设备；目前仅编译通过，不能宣称 ART 测试通过。

### E6 文档、机器权威与批次冻结

capability 43 的既有 VERIFIED 和 fixed-main provenance 保留；仅局部替换旧 facade/EdgePixelMatcher 产品路径、已删除的 fixture 方法和半屏宽封面 deviation，引用实际 shared owner、两端原 encoded sampler 与真实原 setting/layout wiring。宽封面旧左半屏 policy 标为实现已移除；DP01 checkbox 仍未勾选，最终独审/提交与 DP02 正式运行未由 metadata 推进。其他 capability 未因本次改写而提升状态。三个原 metadata focused 只是机器路径/证据一致性保护，不是行为验收。

udp-e-scoped-format-stable 自然 PASSED/0：app7、domain8、Desktop25 共40产品/测试 Kotlin 文件原限定 Apply/Check。前面 max-line-length 失败均为不可自动换行的长字面量/签名，按120限制等价拆分后通过。Desktop 大 metadata 守卫的既有434条长路径保留原格式（模块本未应用 Spotless），仅 cap43 局部 diff-check 和三个原 metadata focused，不扩大为数百历史路径格式化，不加全局 suppress。

最终相关验证冻结为：domain SharedDualPageSessionContractTest、ReaderPairingAdjustmentContractTest、ReaderPortraitSingleSlotTest、ReaderParityContractTest；Android 原真实保存8项；Desktop SharedDualPageDesktopPresentationWiringTest、DualPagedPresentationTest、DualPagePresentationIdentityTest、DesktopReaderProductRegressionTest、DualPageCurrentPageWiringTest、ReaderPageTurnPresentationTest、DefaultReaderModeTest、ReaderScreenModelTest、原 session dual settlement 单项、原 MangaDex parser 单项、WebtoonPresentationIdentityTest/InitialViewportBootstrapContractTest/AutoScrollTest、ReaderTestModeHttpTest、原导航 late-close 单项及三个 capability reader metadata 用例。includeIntegrationTests=true，另 compileReleaseAndroidTestKotlin；不含完整模块/full/finalParityAudit/正式构建。未改变的 ReaderTestModeController fixture 自己的 currentPageIndex 仍是实际 fixture/runtime 记录，不是已移除的 ReaderState 影子，因此复用原有效证据。

### E 最终相关验证结果（等待同轮独审/统一提交）

| key | 自然终态及有效范围 |
|---|---|
| udp-e-affected-batch | 5分22秒自然 FAILED/exit1，仅 Desktop metadata protection 一项失败；fresh domain4 suites 62、Android原保存8、Desktop业务15 suites147均单次零fail/error/skip。Desktop总16 suites150含metadata3，唯一failure为守卫不能解析全限定协程调用，不是业务失败。同轮 compileReleaseAndroidTestKotlin PASS，含最终原getter身份枚举的ART入口。 |
| udp-e-metadata-and-format-repair | 自然 FAILED，仅metadata随后发现旧ProductRegression方法名。Android原sampler测试改为等价import runBlocking，不扩守卫parser；app8/domain8/Desktop25共41产品/测试文件原Apply/Check PASS，compileReleaseUnitTestKotlin PASS。既有D sampler业务未改，复用D签收证据。 |
| udp-e-metadata-stable / udp-e-metadata-refactor / udp-e-metadata-c-evidence-repair | 自然 FAILED；依次暴露迁移后的宽封面测试真实present helper marker，以及C已改严的旧ID45方法引用。按实际代码修正guard/manifest；没有改变业务测试预期、状态/分类或增加产品范围。 |
| udp-e-metadata-final-green | 12秒自然 PASSED/exit0，fresh metadata3单次零fail/error/skip。cap43完成有效引用；ID45仅等价更新C现存page/chapter physical policy和full viewport prefs三个旧方法名，复用原C业务证据。 |

本批220项唯一有效验收为62 shared + 8 Android保存 + 147 Desktop实际业务 + 3 metadata；不把 FAILED 组合称为全绿，不重复累计已打印通过或内部fixture向量。每个key自然终态已核当轮fresh XML非零计数；后续仅metadata focused会覆盖Desktop XML，计数和有效key在此唯一文档保留，不新建报告/测试快照。所有key均终态，未留下RUNNING协调器记录；Gradle daemon可正常闲置，不全局清理Java。

超过8文件/400行的内聚性：A–E共享owner合同、两端原content/sampler/persistence/native呈现接线和行为测试共同编译及验收，不能拆成孤立算法或状态提交；没有泛重构、完整模块/full/finalParityAudit、正式构建或实体设备操作。既有A/B/C/D有效证据复用。最终DP01勾选和一个完整功能commit由主代理同轮独审后处理；DP02仍需正式同签名/隔离产物、ART/R8原View像素与触摸、原扩展真实源边界以及Windows/macOS物理键鼠运行证据。

### DP01 主代理最终签收

2026-10-02 主代理完成同轮独立审查，复用已签收的 A–D 与 E1/E2/E3/E4/E5 里程碑，核对 E 最终限定验证、ART 原 public getter 身份控制、cap43/45 局部引用及维护文档。domain fresh62 和 Android fresh8 零 fail/error/skip；Desktop147业务计数复用冻结批次当轮核验，metadata修复后fresh3通过。原组合失败保留，不重称全绿。41文件限定格式及 git diff --check 通过，无在途协调器任务。

DP01 在同一完整功能提交中勾选，正式 ART/R8、真实源正式产物和 Windows/macOS 物理键鼠仍是 DP02 必做项；此签收不宣称两个用户案例已在新正式产物修复。范围跨共享 owner、两端 adapter 与测试，保持一个可编译验收功能批次。Mac 最新只读容量约8.9GiB；超时息屏不等同密码锁屏，正式时先醒屏，再核会话/窗口/焦点。

### DP02 首轮完整矩阵（失败保留，未正式构建）

功能提交 `2a0e7570b7f359c4899e45f95769cc13e553f228` 后进入 DP02。构建脚本 `full-tests` 已核对为 Windows `-TestOnly -FullTests`，真实 `:app-desktop:jvmTest -PincludeIntegrationTests=true`；未递增 Desktop 版本。

- `udp-final-desktop-full`：启动阶段 exit255，GRADLE_OPTS 中未引号保护的 nonProxyHosts 管道被 cmd 解析；Gradle/测试未启动，不能记业务红或完整测试次数。改为带引号属性后执行下一 key，不改变代理 bypass。
- `udp-final-desktop-full-execution`：7分41秒自然 FAILED/exit1；fresh XML 3,250 项、35失败、0错误、3条件跳过。失败包括当前 authority/证据守卫引用、standalone双页缺 shared units、未mounted TestMode旧影子断言、runtime model回收、在线保存呈现、章节切换，以及实际首帧/OPEN I/O门禁；AuthorCard一项关系未证实，留待单项复现，不扩无关产品范围。
- `udp-final-shared-android-full`：19分49秒自然 FAILED/exit1；domain JVM 102 suites/620项及 Android目标86 suites/525项，均0失败/错误/跳过。app fresh708项/25独立失败/0错误/7条件跳过。失败涉及metadata-only额外图片读取、裸JUnit Android resources环境、邻章恢复、双页native重建/定位/进度/反馈、旧UNKNOWN双槽预期及activity CREATED前状态恢复。不能一概称夹具失败；原因和正确红按后续具体诊断记录。

首轮完整失败保留在上述协调器日志及原模块测试报告；不重新称全绿，不新增巨型diff或逐测试快照。后续只修复相关路径并执行用户已批准的必要 focused；追加完整模块/矩阵仍需说明具体范围与成本并取得批准。正式构建、R8/ART、真实源新产物、Windows/macOS外部键鼠均未执行，DP02保持未勾选。

跨工作树公开版本只读核对：当前任务Android code35/aex.17，其他本仓库工作树已分配至code38/aex.20；候选开始前再次串行核对并分配更高code，同证书不变。Mac真实输入CBZ原字节/原序包装仅在忽略目录，17页SHA-256 `fec7ffef2dc6773c0c917b5e9961fff745cca5eb9eaccc82deee2dffe26b522b`、22页SHA-256 `760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439`，Mac传输后哈希相同；未注入预期分组。最新Mac可用约8.5GiB，正式构建前复核。

- `udp-final-e2e-client-full`：16秒自然 PASSED/exit0，`:test-desktop:test` 由 Gradle FROM-CACHE 复用未改变客户端输入的完整结果；8 suites/52项/0失败/0错误/0跳过。不声称本轮新执行了物理 E2E；实际正式窗口与键鼠仍待 DP02。全局 spotlessCheck 留至修复冻结后执行一次。


### DP02 首轮完整失败后的限定修复

完整矩阵原始失败保留上文，不以 focused 覆盖为全量通过。原 Desktop prefix/full 同报 OPEN 与
首帧前非当前有界 header 为正确业务红：`udp-final-repair-header-red` 自然 FAILED/1，fresh2/2fail。
原1.5MiB文件仍真实执行512KiB有界读取与完整读取；修复使用独立 HEADER_READ/byteLimit 观测，
不缩减完整当前页 OPEN1、物理copy/network 或首帧非当前I/O门禁。
`udp-final-repair-header-green` 自然 FAILED/1，domain观测2绿/Desktop20中16绿，剩余4为呈现/时点夹具；
`udp-final-repair-header-green2` 自然 FAILED/1，fresh4中 SharedSpread 与180页首帧对照2绿，
RuntimeFactory EXIF/B2的原事实与结构断言通过，实际goto slots仍待诊断，未称整轮绿。

Android原 metadata-only expected0/actual1 和邻章恢复失败仍展示内容/丢恢复入口是正确业务红。
ChapterLoader adapter按原PREFETCH purpose禁止自动header，双页邻章门禁改用原store.isRestored实际共享状态；
保持保存失败仍可读、默认未验证可读但禁保存。`udp-final-repair-restore-and-slot-green` 自然FAILED/1，
5m56，fresh Android22/0fail/error/skip（WindowEffect3、PairingPersistence19），无失败重试；
仅Desktop RuntimeFactory2个实际slot失败。原metadata-only imageOPEN0与RTL恢复选择、current保留均通过。
当前裸JUnit5 Android资源/伪bytes及未安装model的adapter Activity属于环境/夹具缺口，迁移真实
Robolectric资源/合法PNG与其实际model边界，不作为产品红，也不删除原scheduler/progress预期。

`udp-final-repair-fixtures-green` 自然FAILED/1、45s：freshDesktop3中EXIF/B2两例的原方向、结构和
真实槽宽/位置均通过。两例明确关闭动画，仅证明facts→shared→Compose槽位；原E2动画窗口证据
仍复用，DP02真实B1/B2原生输入保持正常动画。AuthorCard原独立单项仍Late Author50恢复失败，
未证明shared关联，未改product或弱化断言。freshAndroid13：Core3与Materialize4资源迁移绿，
Adjacent6中4缺真实model owner；随后已给adapter fixture原store/model。

`udp-final-repair-progress-green` 自然FAILED/1、3m52，freshAndroid94/7fail/0error/skip，
Progress29中24绿、Holder47中46绿、SharedAndroid12全绿、Adjacent6中5绿。原B完成删除A、
A失败不删、邻章失败图片retry、同漫画resume/sync、新实例读取accepted write及原触摸/方向/资源
门禁有效绿保留。UNKNOWN不再假配对：原无方向/尺寸先为不确定FULL单槽，真实header后仍要求实际
portrait成对；native recreation保location/unit并核旧ticket拒绝，模式关闭/开启依法增加layoutRevision。
剩余原B封面3事件、layout single、Activity toast、loaded transition和dual idle逐项限定修复；
前者Explicit decode夹具需真实Ready前置，decode=false仍不得settle。Author临时只读诊断签名编译错误
没有测试执行，不算业务红，已修正。没有新增完整模块或全量验证。

`udp-final-repair-seven-and-author-diagnostic` 自然FAILED/1、1m28：freshAndroid7中4绿，
B封面三条实际事件与layout-only→真正绘制single门禁通过；Activity toast仅旧helper位置前置、
loaded transition和single idle仍红。idle两次实际调用堆栈来自setChaptersInternal初次positionPage
同索引手动通知和尾部原事件，不是两条网络响应；保持原exact1请求期望进行最小修复。
loaded transition因旧current-owner位置优先实际transition.loadedEntryPage，不能改邻章预期绕过。
Author原单项在临时只读factory/model观测下fresh1/0fail/error/skip：三个阶段cards52、All saved
index49/lastVisible51且loading=false；此轮没有位置覆盖，未确认产品根因。临时观测已移除，
没有改变Authors product/原断言；波动结果由主代理告知用户，不能据此称作者页已修复。


`udp-final-repair-native-three-green` 自然FAILED/1、50s，fresh3中idle/原loaded入口两绿，
Toast真实native pager尚未idle属夹具前置；后改原helper无动画定位，仍经真实Activity setup、
native adapter/holder呈现和原持久化失败Toast，不把layout-only当已读。
`udp-final-repair-owner-window-red` 自然FAILED/1、40s：Toast原方法1绿，原loaded-transition
方法新增旧窗口晚到→新窗口位置向量正确红，actual旧transition替代新chapter page2。
`udp-final-repair-owner-window-green` 自然PASSED/0、25s，fresh原方法1/0fail/error/skip：
显式target优先，只有from/to均属于当前窗口实例且共享恢复通过的selected transition才优先entry；
普通logicalTarget保留。初次同索引定位仅抑制额外通知，尾部原事件及外部moveToPage语义保留。
以上native delta已由主代理同轮局部签收；临时请求调用堆栈已清理，没有新增公共状态机制。

新增 HEADER_READ 必须被原 Python HTTP consumer 消费：
`udp-final-repair-http-header-red` 自然FAILED/1（Gradle6s），fresh1正确失败 expected exit1/actual0；
首帧前非当前有界header没有被原门禁拒绝。原shared validator最小增加noncurrent HEADER_READ拒绝，
`udp-final-repair-http-header-green` 自然PASSED/0（9s），fresh FinalParityReaderClient13、
PerformanceRunner5共18/0fail/error/skip。合法当前header、byteLimit512KiB及未来可选字段兼容；
完整current OPEN1/DECODE1与原20样本/预算/错误合同未缩减。新增消费端诊断及修复约10分钟；
原client完整FROM-CACHE52仅保历史，不能覆盖改变后的输入。

#### 限定修复冻结范围与首轮失败映射

本批内聚于首轮共享迁移遗漏、首帧实际header I/O、恢复/窗口身份与实际mounted测试入口，
超过8文件/400行主要来自既有wiring夹具的真实owner/合法图像补齐和原断言迁移；
固定原版来源与behaviorVectors未改。风险集中在首帧读取、邻章恢复和进度权威，
由正确业务红、真实production门禁、同轮接口独审及下列限定复验处理，不扩Authors产品。

| 原Desktop失败类（原数量） | 本批验证或复用 |
| --- | --- |
| ReaderArchitectureGuardTest(1)、DesktopProductCapabilityContractTest(1) | 原boundary/exact contract两个方法 |
| ReaderDeviationGovernanceTest(1)、ReaderFixedMainAuthorityTest(3) | deviation原合同及mutation两方法；RD01两/RD02一与fixture绑定、exact git objects两方法 |
| DesktopChapterPairingComposePersistenceTest(1)、DesktopReaderRegionPresentationWiringTest(1) | 原online first pair与Dual实际tiles两个方法 |
| DesktopReaderRuntimeFactoryTest(2) | 原next-prefetch filename与one shared core两个方法；有界header/overlap/cold17及EXIF/B2原focused有效绿复用 |
| ReaderCriticalPathProductionTest(2)、ReaderIoProductionWiringTest(1)、ReaderPresentationPipelineProductionTest(9) | header-green/green2自然终态中原首帧成本、I/O顺序和single/dual/webtoon×dir/cbz/online有效绿复用，无后续相关语义改变 |
| ReaderScenarioSmokeTestSuite(3)、TestHttpServerJsonTest(1) | 原read/goto/closed action三方法及fixture routes一方法 |
| AdaptiveReaderViewportTest(1) | 原resize/page/progress/real next tap方法 |
| ReaderChapterTransitionPresentationTest(5) | 原parameterized metadata方法全7向量（共享夹具影响其single/dual，保原两Webtoon合法向量） |
| ReaderKeyboardNavigationPositionTest(1)、ReaderDecodedImageRetryWiringTest(1) | 原last pair keyboard与Dual Retry两个方法 |
| AuthorCardProductionWiringTest(1) | 未修改产品或原断言；原单项失败与临时只读诊断PASS均保留，不归共享修复完成 |

原Desktop35中34在本批范围，另1 Authors仍波动且未证明原因。
Android原25映射：Core(1)与Materialize(1)的合法资源原7绿、WindowEffect(1)原3绿复用；
PairingPersistence(2)进入原全类19；Progress(9)原全类29；HolderLayout(6)原全类47；
SharedAndroid(1)原全类12；AdjacentViewer(4)原全类6。最终Android限定113项，
保原完成/失败/重开/邻章/恢复/资源与真实native动作边界；不运行未限定app模块。

冻结命令 key `udp-final-repair-frozen-related`：
`:app-desktop:jvmTest` 仅上述尚待复验的21个方法过滤（参数化实际预计27项）；
`:app:testReleaseUnitTest` 仅上述5个原类（预计113项），临时候选include与--tests一致、maxRetries=0；
`:test-desktop:test` 仅原FinalParityReaderClient/PerformanceRunner两个类（18项）。
共同 `-PincludeIntegrationTests=true --offline --continue`，不是完整模块/全量/finalParityAudit。
domain观测原2、ReaderTestModeHttp真实JSON/GET字段及SharedSpread原有效绿仍复用，
格式后客户端18同范围复验作为重构阶段。限定31个本批Kotlin文件Apply/Check，
长路径capability合同仅局部diff-check；全局spotlessCheck留主代理最终一次。

#### 冻结批实际终态与剩余原范围闭环

`udp-final-repair-frozen-related` 4m38自然FAILED/1：fresh Desktop27/9fail/0error/0skip，
Android113/1fail/0error/0skip，两个client合同18/0fail/error/skip。未称158项全绿；
Android其余112项有效绿继续复用，不重新执行113。剩余Desktop9及Android原sampler1逐项限定处理。
`udp-final-repair-ten-diagnostic` 自然FAILED/1、37s：Desktop10中online/RD02等4绿、metadata1与
transition5仍失败；sampler1的只读时序诊断通过。sampler原exact20语义保留：lease释放不代表原
matching job已经发布负结果，原pump改等待该job自然完成再显式retry，并保无无进展循环及错误
样本不得恢复旧positive的匹配门禁；没有改变sampler production或用累计调用数替代匹配结果。

online原20s失败实证为夹具一直扣住NON_CURRENT gate导致共享必要neighbor facts不能就绪；
仅实际FIRST_PAGE_PRESENTED之后放行原gate，保首帧非当前I/O为零，不增加等待上限。
Adaptive真实尺寸/共享unit前置补齐后，原第二logical页保持2，双页unit实际为[1,2]；
原resize/backsingle/progress/真实tap保护不变，未把OPEN1改为2/3。
`udp-final-repair-remaining-green` 自然PASSED/0、41s：fresh Desktop11（metadata1/RD02一/
online1/Adaptive1/transition7）及Android原sampler1全绿。

最终current-role引用核对更新26个失效行位（cap44/45/47/51/53/54/81/95）；固定原版git ref、
provenance、behaviorVectors及capability状态不改。cap53旧未消费settle方法引用改为该原方法实际
执行的acceptDualFact/presentReadyPagerViewport/progress断言。真实exact合同复验，不以字符串核对
代替行为。cap43 verificationScope记录已提交DP01与仍待DP02的边界。

#### 正常动画中最新跳转与旧真实输入的逻辑身份

在原ReaderChapterTransitionPresentationTest参数化方法内新增真实tap进入NEXT且native idle=false，
动画未结束时goto同logical页；要求物理回漫画页、旧输入不重新打开边界，新tap仍可进入transition，
过渡和terminal回调不误记进度。`udp-final-repair-transition-intent-red` fresh7中5个single/dual
向量因最新goto目标丢失正确失败，两个Webtoon原合法向量仍绿；这不是先停稳后晚到反馈的替代用例。

仅Compose intent predicate的green、仅mutation fence的green2仍各5失败；没有把它们称通过。
原单项native诊断证明shared已unit=null/location.sequence=2，但native仍idle于旧transition，
因为同页goto合并后原effect key不重启。最小修复沿已有shared logicalLocation：
原tap/Drag.Start intent保实际输入开始位置，composition原binding传该位置，
model在begin mutation点重新核owner.location；原goto effect消费同一位置变化。
metadata同location不撤合法目标，没有新增平行owner或公共状态机制。
`udp-final-repair-transition-intent-green3` 自然PASSED/0、24s，fresh原7向量零失败/错误/跳过，
主代理同轮独立核对该接口后允许原E2/快速输入/turn下游复验。所有临时打印与堆栈诊断已删除。

限定31个changed Kotlin严格Apply/Check的`udp-final-repair-scoped-format-final`自然PASSED/0；
后续10个delta文件`udp-final-repair-delta-format`因原长行及临时诊断文案失败，非业务红，
断行及移除临时观测后`udp-final-repair-delta-format2`自然PASSED/0、6s，严格120列Apply/Check。
未应用模块Spotless的长路径capability合同保原格式，以局部diff-check及实际exact合同验证；
没有执行全局Spotless。新增consumer红绿约10分钟、实际动画身份限定定位约30–40分钟，
均为原范围具体失败修复，无新代理/审查轮次或完整测试。

#### 修复交接的最终限定重构验证

`udp-final-repair-input-refactor` 自然PASSED/exit0、57s：
fresh Desktop6 suites/35项/0fail/0error/0skip，
分别为原E2真实未停稳tap/key/drag facts或matching及rapid/latestgoto11、
原page-turn外部请求/快速请求/外部动画中新输入/连续tap14、
原chapter transition全7、Adaptive1、online1、exact metadata1。
正常动画窗口实测通过；Drag.Start后begin推进共享位置仍能停稳，新输入可正常选择，
metadata同location不撤目标。Android原sampler1由Gradle UP-TO-DATE有效复用
（原remaining-green的fresh XML1/0/0/0，无后续相关产品改变），没有虚报为本轮新执行。
client冻结批fresh18继续有效；原完整52仅修复前历史，当前仍需主代理申请完整复验。

实际命令（协调器无并行，临时init仅限制候选与retry=0；此处只选sampler方法）：

```text
scripts/gradle-coordinator.py
start
--key
udp-final-repair-input-refactor
--
.\gradlew.bat
:app-desktop:jvmTest
--tests
*DualPageCurrentPageWiringTest.native input target survives facts or matching during the unsettled animation*
--tests
*DualPageCurrentPageWiringTest.mounted rapid taps and latest external goto retain their logical destinations*
--tests
*ReaderPageTurnPresentationTest.external page requests animate just like taps*
--tests
*ReaderPageTurnPresentationTest.latest rapid page request wins over settled page feedback*
--tests
*ReaderPageTurnPresentationTest.user turn during external animation clears stale programmatic feedback*
--tests
*ReaderPageTurnPresentationTest.rapid taps advance from the latest user turn target*
--tests
*ReaderChapterTransitionPresentationTest.ReaderContent mounts transition metadata in every viewer direction*
--tests
*AdaptiveReaderViewportTest.production reader resize preserves page and progress and real next tap resumes reporting*
--tests
*DesktopChapterPairingComposePersistenceTest.online reader with saved chapter identity presents its first paired pages*
--tests
*DesktopProductCapabilityContractTest.parity manifest defines the exact roadmap contract*
:app:testReleaseUnitTest
--tests
*SharedDualPageAndroidWiringTest.matching preference reaches actual encoded sampler shared session and native display pages
-I
app-desktop/tmp/udp-final-repair-related.init.gradle
-PincludeIntegrationTests=true
--offline
--continue
```

唯一evidence、架构/API和机器manifest局部同步；UTF-8两份JSON解析、原Python consumer AST及
git diff --check均通过。限定Kotlin格式已通过，长路径合同保原格式并经实际exact合同。
全部临时诊断已清理，Authors product及原断言无diff。修复以单次限定集合加仍适用的有效绿
覆盖原Desktop34个共享相关失败、Android25个相关失败，不声称首轮完整35/25已重新全量通过；
作者页独立1项仍保波动与未确认原因。无完整模块重跑、finalParityAudit、正式build、R8/ART、
实体安装或原生HTTP冒充输入。DP02仍未勾，下一步由主代理完成同轮剩余整合/全局格式/唯一repair
提交，并向用户申请具体追加完整验证与正式平台验收范围。子代理不单独提交。

#### 主代理整合与格式收口

主代理完成同轮接口与证据核对后接回唯一 Gradle 执行权。
`udp-final-global-spotless` 执行 `gradlew.bat spotlessCheck --offline`，
自然 FAILED/1、28s；其他格式目标通过，唯一失败为此前 DP01 文件
`DualPagePairingStore.kt` 的 CRLF 行尾。仅将该文件改为 UTF-8/LF，
Git 归一化后无产品内容差异，不改变行为或重新执行业务测试。
`udp-final-global-format-repair` 执行 `:app:spotlessKotlinCheck --offline`，
自然 PASSED/0、8s；为完成原失败后未执行的 app XML 与聚合目标，
`udp-final-app-format-complete` 执行 `:app:spotlessCheck --offline`，
自然 PASSED/0、11s。未第二次执行全局格式或完整业务矩阵。
Desktop 未纳入普通模块 Spotless 的范围继续使用已通过的严格限定格式证据。
唯一报告开头及 roadmap E 段的旧“未提交”描述已与 DP01 实际提交同步。
本次修复整合完成；DP02 保持未勾选，作者页历史失败的原因仍未确认，
不宣称其已修复，也未混入作者页产品或测试变更。

### DP02 用户批准的追加完整矩阵（第二轮）

用户明确回复“批准”后，冻结产品源码为 `f95ffeb1e0c75a11f9e2326cd06b1500dd434e7c`。
以下时间均为北京时间 2026-10-02。三条命令串行执行，无测试过滤、临时 init 或并发 Gradle；
复用已通过且无后续相关变更的格式证据，未重复全局格式。

| key / 命令范围 | 自然终态与有效 XML |
| --- | --- |
| `udp-final-approved-desktop-full` / 项目 `build-desktop.sh full-tests` | 12:22:20–12:28:49；自然 FAILED/1，Gradle6m25s；434 suites/3250项，5失败、0错误、3条件跳过 |
| `udp-final-approved-shared-android-full` / `:domain:jvmTest :domain:testDebugUnitTest :app:testReleaseUnitTest --offline --continue` | 12:29:34–12:51:15；自然 PASSED/0，21m40s；domain JVM102 suites/620项与Android目标86 suites/525项均0失败/错误/跳过；app124 suites/708项、0失败/错误、7条件跳过 |
| `udp-final-approved-client-full` / `:test-desktop:test --offline` | 12:53:02–12:53:25；自然 PASSED/0，22s；fresh8 suites/53项、0失败/错误/跳过，实际执行test任务，非先前完整52项的缓存复用 |

共享/Android命令的外层900秒等待曾返回 RUNNING/exit1，worker与Gradle继续正常运行。
主代理只查原进程与日志并等待，自然终态0为真实结果，没有重启命令或把外层超时当测试失败。
本轮Desktop作者页原用例通过，但保留上轮失败及诊断PASS的波动事实，未证明旧失败根因。

#### Desktop剩余5项：实际失败与只读定位

| 原用例 | 本轮失败证据 | 只读定位结论与仍待证明项 |
| --- | --- | --- |
| DesktopChapterPairingComposePersistenceTest / real reader adjustment button saves to file and reopened screen restores containing spread | expected源索引[4,5]，actual[5] | helper只等待累计decode与任意可见帧，可能提前观察合法未确认单槽；最终恢复错误未证实，后续核实际恢复、事实、确定分组与native停稳，保原[4,5]要求 |
| ReaderPresentationPipelineProductionTest / dual-directory | 可见页1 OPEN expected1，actual2 | region预览在decoded缓存查询前先pin原内容；UNKNOWN单槽到确认双页的unit重排会换holder，存在重新打开已释放原内容的生产路径；需原向量核contentKey/cache hit/重排时点，不放宽OPEN1 |
| ReaderProductionMutationEvidenceTest / dual-directory canonical decoder | 报呈现但未画出注入decoder的实际蓝色像素 | helper依据历史FIRST与累计decoderKeys结束，未核当前重排后的holder就绪；尚未证明永久丢内容或绕行decoder，须核当前unit/holder/idle/真实当帧像素，保像素断言 |
| ReaderProductionMutationEvidenceTest / dual-directory rejecting decoder | 200 pumps仍未到观察点 | 夹具固定等待邻页decoder，但当前页拒绝decode后没有首帧，邻页不得提前挂载；观察前提冲突已由源码确认，须绑定实际挂载单位，仍要求零FIRST/零绕行像素 |
| SourcePageFetcherFactBudgetTest / actual optional HTTP reads stop at shared byte quota while cached and normal reads survive | 实际网络body source字节expected33554432，actual33557760，多3328 | permit包在buffered source外侧，限制返回字节不能阻止下层预取；XML证实超额，具体3328来源仍需原单项requested/returned/缓冲余量诊断，不能增加32MiB容差 |

原实施者复用为只读诊断，没有改文件或启动新Gradle；未把Windows曾低至0.84GiB的空闲内存
当作上述失败原因，也未发现OOM证据。已知读取边界、观察前置及未证实因果分开记录。

Mac只完成同源准备：从本地分支建立20,476,016B Git bundle，以Mac已有c84ed331fa为前置验证，
建立干净隔离工作目录 `/Users/altair/Library/Caches/mihon-shared-dual-page-20261002-f95ffeb/repo`，
HEAD同为f95ffeb1e0；原日常worktree/应用未覆盖，没有Mac正式构建或键鼠验收。
Windows/Android正式构建、模拟器启动和正式原生验收均未开始。

用户随后批准这5项限定修复、一个额外repair提交、一次针对两处读取边界的独立复审，
以及修复后一次Desktop完整验证，预计总1–2小时。实施子代理独占Gradle串行focused，主代理
独审并统一提交/full。范围限Desktop预览/content lease、fetcher body读取及原5项；
原reopen所需Dual定位effect单点亦由主代理确认在该范围内，不修改共享、Android或客户端。
保留原分组[4,5]、像素、OPEN1和实际body字节硬上限；夹具错误不计业务红。

#### 批准的第二次限定修复：红绿与观察前置

`udp-second-repair-read-boundaries-red` 自然FAILED/1、30s，fresh Pipeline9项/3失败。
三条dual路径实际UNKNOWN[1]→确认[1,2]重挂holder；原当前页HEADER1/OPEN1/DECODE1/
FIRST之后出现第二次OPEN1而无第二次DECODE1，证实decoded cache命中前pin造成额外原内容打开。
同轮quota1绿不证明当时偶发超额已经消失。最小cache先acquireCached，小图hit不pin；
静态大图hit仍pin，miss仍原pin/decode，旧generation/attempt/关闭准入不变。

`udp-second-repair-body-chunk-red` 自然PASSED/0、31s（原quota1项），计数seam限制5000
返回仍被buffer按5000+3192拼成8192，未产生有效红；临时限制已撤。
`udp-second-repair-body-diagnostic` 自然FAILED/1、46s，fresh19项/1失败：Pipeline9与
Mutation8绿，quota1绿；reopen仍失败。canonical改为当前真实unit与蓝色已画门禁；reject
改为当前真实挂载单位，不要求拒绝首帧后仍提前挂载非当前页，保零FIRST/零绕行蓝色。
这些两项观察前置修正不是生产decoder缺口红。
`udp-second-repair-body-arrival-red` 自然FAILED/1、41s，fresh2项/1失败；HTTP仍exact
32MiB，reopen10s超时。该HTTP包到达诊断没有证明预算反例，不按业务红累计。

`udp-second-repair-chunked-diagnostic` 自然FAILED/1、29s，fresh2项/2失败：真实
MockWebServer chunked4864B与原counted source产生33556736B（超2304），最后permit只余2560
而原body source仍实际返回4864，证实外层permit无法限制下层refill。原exact32MiB不改。
reopen10s内事实0..7已确认、decoded4..7齐全，native NEXT仍占满viewport；此前“只是早观察”
推断被否定。`udp-second-repair-reopen-owner-diagnostic` 自然FAILED/1、32s，fresh1失败；
实际shared logical5、committed forcedSingle1/revision1均正确，native定位漂移仍在，未更改共享。

Java JVM叶适配器仅optional分支包原RealBufferedSource.source，先drain原buffer前缀，
原timeout/close/部分文件删除保留；normal/cache/source getImage与原client/headers不改。
`udp-second-repair-boundaries-green` 自然FAILED/1、40s，仅Kotlin sealed BufferedSource
匿名委托夹具编译失败；Java adapter已编译，不算行为红绿。opaque夹具改JDK Proxy包同真实
counted source，不为夹具改生产协议。
`udp-second-repair-boundaries-green2` 自然FAILED/1、1m13：worker77476发生Skiko
EXCEPTION_ACCESS_VIOLATION；native栈为DefaultDispatcher上的scrollToPage→forceRemeasure→
createGraphicsLayer，原fixture render/close在runBlocking线程。日志2项/1失败/1跳过，XML
未刷新，不称当前业务绿；未发现OOM证据，也不将空闲内存作为原因。沿既有fixture模式
将原Persistence Scene上下文改currentCoroutineContext，不修改生产调度。

`udp-second-repair-thread-counterfactual` 自然FAILED/1、1m09，fresh3项/1失败：撤本次
Dual equal-index scroll实施后，同线程reopen仍10s失败且NEXT全屏，正确业务红有效；HTTP2
零失败/错误/跳过，实际chunked exact32MiB、buffer前缀顺序、opaque optional零续读/普通路径
可读、预消费超额不续读并删除partial、15sdeadline回收均在原两方法执行。
`udp-second-repair-reopen-green` 自然FAILED/1、28s，fresh1失败：最小equal-index
scrollToPage仍未关闭native漂移，不把它称为修复。下一原方法仅记录实际effect调用顺序，
若换公开下一次measure定位接口，仍须原[4,5]中心+真实像素及正常动画input35绿后收口。
这些focused没有完整模块、正式build或ART；后续稳定结果追加于此。

#### 第二次限定修复最终分类与冻结结果

`udp-second-repair-reopen-effect-order` 自然FAILED/1、34s，fresh原1失败。实际effect顺序：
初始target6/current6/offset0完成；最终READY分组的[4,5]位于index4，target4/current6进入原
非相等animateScrollToPage，未出现after。并非equal-index分支阻塞。锁定Compose1.10.2
ImageComposeScene.render默认time=0；原方法所有render没有推进帧，已有PageTurn夹具每帧
增加16,000,000ns。因果对照纠正此前“业务定位错误”的归类：同线程反例仍未推进动画帧，
不能作为更改Dual产品逻辑的正确红。所有本轮Dual实施与临时effect/TestState手动open诊断
已撤，shared/location/保存及正常动画产品逻辑保持原实现。

原reopen循环沿已有16ms实际帧推进，同一线程render/effect/close；等待当前实际中心[4,5]
并画出原蓝色内容，保原10s窗口、原保存forcedSingle1与进度5断言。
`udp-second-repair-reopen-frame-green` 自然PASSED/0、24s，fresh1项零失败/错误/跳过。
此前“只是早观察”推断仍保否定；更准确原因是从UNKNOWN确认重排后真实动画未被测试推进，
不是源事实没有补齐，也不是需要更改共享组或永久禁止动画。

`udp-second-repair-scoped-format` 自然PASSED/0、20s，使用已有app插件上下文init，
严格Apply/Check仅8个实际改变的Kotlin文件，无max_line_length放宽、无模块全量格式。
`udp-second-repair-related-refactor` 自然PASSED/0、48s，实际执行jvmTest，fresh7suites/
31项（各单次），零失败/错误/跳过：

| affected类 | 原方法过滤范围与fresh数量 |
| --- | --- |
| DesktopChapterPairingComposePersistenceTest | 原reopen与online，2 |
| ReaderPresentationPipelineProductionTest | 原mounted visible/adjacent decode-window九条路由，9 |
| ReaderProductionMutationEvidenceTest | 原canonical与reject八条路由，8 |
| SourcePageFetcherFactBudgetTest | 原实际quota及15sdeadline，2 |
| DesktopReaderRegionPresentationOwnerTest | 原preview/tiles single-flight、generation/final-holder、ordinary/animated，3 |
| DesktopReaderRegionPresentationLifecycleTest | 原generation、detach、owner-close旧tile票据，3 |
| DesktopReaderPageImagePipelineTest | 原cache hit、cache identity、attempt/generation disposer、late close，4 |

冻结命令为 `:app-desktop:jvmTest` 加上述原方法 `--tests`、
`-PincludeIntegrationTests=true --offline`，完整argv保留于原协调器key。原5个失败向量
全由本轮有效fresh XML覆盖；cache lifecycle方法补actual cached大图holder：旧holder关闭
时该cached holder仍保encoded lease，最后holder关闭后lease为空。原OPEN exact1、蓝色像素、
reject零FIRST/零绕行、实际body exact32MiB、normal33MiB/cached无新network、15s回收均保。
本轮Dual/Single/shared/Android/client无产品diff；主代理确认原input35正常动画证据仍有效，
因此没有重复该无相关变化范围或完整模块。Java adapter已由实际compileJvmMainJava与原quota
向量消费，具体JVM版本/opaque/preconsumption/TCP边界见架构说明，不声称任意源预消费可撤回。

原生崩溃原件移入已忽略的协调器过程目录：
`.gradle-coordinator/udp-second-repair-boundaries-green2-hs_err_pid77476.log`，保留未删除。
未据一次合法同线程运行不崩溃证明Skiko内部根因，更不据此称OOM或资源不足。
全部临时诊断删除，未新增报告/快照/公共状态框架；本批产品变化仅两处Desktop读取边界。
唯一repair提交与批准的一次Desktop完整验证由主代理接权后统一执行，DP02正式ART/原生
键鼠/发布构建仍待验收。此前真实Copy生产缓存已找到完整22contents/22words原成功响应，
仅ignored私有目录保留，可派生缺失/空/截断受控边界；尚未执行Android原parser或ART，
不将Windows JVM成功替代该平台证据，也不声称成功响应未找到。

### DP02 第二轮剩余项提交与获批 Desktop 完整复验

限定修复已提交 `ca4abd37a32c57c7f7cb4486f1bdfa78af5aa0e1`。本批 11 个实际内容文件保持内聚：3 个 production 文件处理缓存 preview 与可选响应 body 两处读取边界，6 个测试/夹具文件覆盖其生命周期、额度及原呈现断言，2 个必要维护文档说明边界。主要风险是 cached raw lease 所有权和锁定 Okio 版本的公开 JVM Source 字段，已由真实生命周期与流式 quota 用例独立核验；没有 shared、Android、Dual/Single 产品改动。

用户批准的一次追加 Desktop 完整复验 `udp-final-second-repair-desktop-full` 于北京时间 2026-10-02 14:33:03–14:38:28 自然 PASSED/exit0，使用项目 `build-desktop.sh full-tests`。主代理核验 fresh 434 suites、3250 项、0 失败、0 错误、3 条件跳过。第二轮 shared 两目标、Android 应用和 client 完整绿色证据仍适用，未重复完整矩阵，也未再运行 finalParityAudit。

### DP02 三端正式构建、产物与尚未关闭的运行门槛

Desktop 统一分配为 `0.11.19.69.ca4abd3`。Windows 项目入口 `build-desktop.sh build-only` 已分配版本，但后续 PowerShell `-File` 启动无脚本输出、约30秒退出，未进入 Gradle。保留三个正式入口失败记录及最小启动诊断；`-Command` 调用同一项目平台脚本已证实可正常执行版本 guard。因此由协调器运行同一 `build-windows.ps1 -SkipTests -VersionAllocated -ExpectedVersion ...`，保留原构建、production runtime 验证和发布步骤，没有直接用 Gradle 部署。不能据此断言代理配置或 PowerShell 内部故障原因已定位。

`udp-final-windows-formal-command` 于北京时间 15:08:44–15:10:30 自然 PASSED/exit0，正式运行版本及原 ManHuaGui APK 装载/解析验收通过。日志唯一 `Final unpacked EXE:` 指向且主代理确认存在：[Mihon Desktop.exe](D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.69.ca4abd3-unpacked/Mihon%20Desktop.exe)。Windows ZIP SHA-256 为 `7f2f2bf20a020ff6658ae2dca08dbd831992c73ea28c21e88557a769fb4944e3`。构建验收尚不包含本次 B1/B2 冷热、直跳/顺读或物理输入。

macOS 在独立 detached worktree `/Users/altair/Library/Caches/mihon-shared-dual-page-20261002-f95ffeb/repo` 核对源码为同一 `ca4abd37a3`，不修改用户日常 checkout 或 `/Applications`。`udp-final-macos-encoded-focused` 于北京时间 14:50:59–14:54:23 自然 PASSED/exit0，fresh 2 suites/4 项包含真实 native Skia EXIF/alpha sampler 三项和实际 Compose reopen 一项，0失败/错误/跳过；不是完整模块。`udp-final-macos-formal-build` 经项目 `build-desktop.sh build-only` 正式构建与隔离部署成功，实际包位于上述独立根目录的 `artifacts/Mihon Desktop.app`。交付 ZIP SHA-256 为 `9c078e3febb6e9ffa4c312307fa974ad3b16b413d0fdb44cc17cf8c3bf70a661`，大小196568069字节。

Mac 正式包经 LaunchServices 非 headless 启动，隔离 profile 与端口均已核验。北京时间 15:28:32 醒屏后观察到 `displayAsleep=false`、会话锁定标志仍 true、原生事件权限 true、截图权限 false；HTTP state 健康不代替窗口和输入验收。已停止键鼠，等待用户进入桌面并置本次窗口前台后复核。没有请求 TCC 权限或读取 Mac 桌面像素，也不由锁定标志断言用户设置了自动锁屏或需要密码。

Android 统一 `candidate --offline` 内部任务 `android-candidate` 于北京时间 15:38:54–15:42:28 自然 PASSED/exit0，随后外部同证书签名、校验及发布成功；主代理再次用统一 `verify --artifact` 核验。正式候选为 [Mihon-Fork-0.19.4-aex.22-vc40-release-universal.apk](D:/Codex/worktrees/0877/mihon/app/artifacts/android/0.19.4-aex.22-vc40-ca4abd37a3-release/Mihon-Fork-0.19.4-aex.22-vc40-release-universal.apk)，SHA-256 `d27a010832f84d6b712883284850a263cfee5800ccf9d9d332338a23ed7ec9f1`，原证书 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，四 ABI、非调试、R8/资源压缩启用。版本40/`0.19.4-aex.22`。构建期间 tracked 源码和文档冻结，发布后才恢复证据维护。

### DP02 正式运行中新发现的验收入口阻塞（保留失败）

Release 测试包首次调用未引用 `-Pmihon.testBuildType=release`，PowerShell 将带点号的属性拆开，未进入编译；另一次 task 查询同类参数错误。日志保留，不能算行为红。按日志固定引号后的 `udp-final-android-release-test-apk-quoted` 于北京时间16:01:19–16:02:03 自然 PASSED/exit0，测试包经项目签名脚本 `-Instrumentation` 验证 targetPackage 和原证书后生成，SHA-256 `0aeb72c4fac319274adfb59aa5d6d85de4f6dbd30cce07041c4650e5d5df262d`。

专用 `mihon-pairing-api36` 以只读 overlay、不加载/保存快照、1536MiB/2核启动，ADB serial `emulator-5586`、hardware `ranchu`，启动前无其他设备或 emulator 进程，未操作实体设备。统一 install 核验正式候选安装哈希与证书成功，未使用 connected 任务覆盖候选。

`SharedSpreadNativeAcceptanceTest` 与 `SharedReaderViewNativeAcceptanceTest` 原 runner 实际执行2项、失败2项：均在读取 `ReaderImageOrientation.entries` 时 `NoSuchMethodError: getEntries()`，尚未进入 JNI 解码、实际像素或触摸。过程日志 `app-desktop/tmp/udp-final-android-native-20261002/exif-view-run.log` 保留。正式候选 mapping/usage 确认 R8 已消除内部 getters/部分函数并改写 Config 默认构造签名；测试包仅映射名称不能还原这些内部 ABI。这两测试文件仅在 DP01 的 `2a0e7570b7` 提交添加，后两轮修复未更改；当前事实是验收入口不兼容正式 R8 包，不能据此证明用户阅读功能回归，也不能声称 ART 已通过。编译/JVM 证据本来不包含该正式运行层。

Windows 同样查明：HTTP library `select(type=chapter)` 虽经真实 detail model 获取章节，却转入 `TestNavigationController.openReader`，强制 `dualPage=false`、空邻章和 synthetic progress tracker，不能作为正常 UI 打开两真实案例的最终证据。真实入口是详情页 `onReadChapter` → 原 request 全部 flags/chapters → `navigator.push(DesktopReaderScreen)`。本次 HTTP route 的17页缓存像素和实际 mounted geometry只作诊断，未记为B1最终通过。两次外部精确 PID 激活后 Windows 前台仍不属于本次应用，已停止原生输入并请求用户置前台。

DP02 保持未勾选。用户已批准追加一次 test-only 修复、主代理局部独审和定向 ART（45–90分钟）；复用原实施代理，不修改 production/keep、不重新打正式 APK、不追加全量。Windows/Mac现场输入确认仍待用户回复。

### DP02 获批 ART 入口修复与 Windows 冷配置启动失败（进行中）

Android 原测试依赖的宿主内部 ABI 不仅包含 `getEntries`，还包括被 R8 消除或改写的构造器和接口。当前修复使用 Android 公共 Intent/Activity/View/SQLite/SharedPreferences 边界，让正式 ReaderActivity、原扩展和 production holder 自行创建内部对象。入口探测中先后暴露 `SourceManager.getOnlineSources`、`PreferenceStore.getInt` 缺失，均发生于阅读行为之前；移除这些宿主内部接口依赖后，已实际进入 ReaderActivity，但30秒图像绘制检查尚未通过。保留各次日志，正在限定定位请求与挂载 View，不能称 JNI、采样、EXIF、触摸或错误恢复已通过。临时 smoke 分支不是原完整验收的替代证据。

后续 Copy 同方法对照取得实际章节1次、图片5次请求；View identity 核对为真实 ReaderPageImageView/SSIV。软件 Canvas 的多点结果为透明，但同次设备窗口 `app-desktop/tmp/udp-art-entry-repair-20261002/window-copy.png` 实际显示中央 CYAN 和 `1 / 22`，主代理已独立查看；宿主 PID 限定日志确认原 native decoder 加载成功。故本次“未观察到颜色”已定位为软件 Canvas 观测不适配实际硬件 View，不能归为产品空白页。test-only 修复转用公共 PixelCopy(window)和当前可见 View 几何，并防止旧缓存使不同输入假绿；完整 EXIF/触摸/采样断言仍未通过。

`native-pixels-signed.apk` SHA-256 `cea838880c9fbedd5e689c5e1ba67f9a8818849cceff09740208af075db77e3c` 的原方法临时入口 smoke，在 Copy、ManHuaGui 各自然 OK(1 test)，分别3.809秒、3.329秒。两次均清隔离缓存，断言章节和图片请求大于0以及实际当前窗口中心 CYAN；主代理核读两个 runner 原始日志。只确认正式候选的公共入口和像素观测可用，不把临时分支计为原完整源错误恢复或 EXIF/采样/触摸验收。正式宿主 APK 未改，后续只整合测试包。

同轮局部独审要求修正三个假绿风险：拖动前后目标色块必须非空，并从 window 坐标转换到实际目标 View；自动匹配先稳定展示关闭态双页，再经真实偏好开启并持续观察非默认单页，候选两页各自需要新 body 读取；EXIF 只变换候选一侧，另一侧保持 NORMAL 黄金，避免两边同时错误翻转仍相等。已读修正实现，实际 ART 全门槛仍待。

修正包的源用例因测试 HTTP server 对空连接 EOF 当致命异常而失败，不能归类为 production 错误传播失败。View 用例已通过 EXIF1 whole 像素/双击/拖动及 left split，随后在 EXIF1 right split+crop+FIT_WIDTH 失败；实际 fresh chapter/image 各3，`window-fit-crop.png` 显示仅绿色内容，主代理核图，未证明产品或夹具根因。限定对照仅把原80×60/60×80和白边4等比例放大至800×600/600×800和白边40，保持方向/半页/颜色黄金。另 sampler 在封面准备处读取全窗口中心失败，实际 half-slot 为540×2400、图像540×810；改用实际可见内容准备，不改变后续稳定分组门槛。各旧失败日志保留，以上均非完整验收通过。

`crop-scale-and-connection-signed.apk` SHA-256 `fefc38fd8a235cb72fe04f72ff04ba7a178f17e1d7279871f299d86f207ccf75` 修正 EOF 空连接和初始封面观测，等待 Activity 销毁后才清隔离缓存，并执行上述分辨率对照。`crop-scale-view-run.log` 自然1失败/38.381秒，仍是 EXIF1/variant2、fresh chapter/image各3；放大不能关闭此失败，已停止该项额外夹具变体，EXIF2–8未执行，不据此确定根因或改 production。

`connection-source-run.log` 自然1失败/50.428秒：ManHuaGui 成功、六错误和每次真实 Retry 恢复均已走过；Copy 成功和 empty ChapterError 已过，empty Retry 后出现真实图片错误 UI，chapterRequests=3、imageRequests仍5，未恢复 CYAN。一次限定取证 `copy-retry-error-observation-run.log` 同点失败/50.615秒，实际 PID10600日志为 `Failed to find image type` → `No decoder found to handle this stream` → SSIV `Image decoder failed to initialize and get image size`。已证实实际解码流不能识别，未确定错误字节的来源；不将缓存删除假设写为根因，也未放宽恢复门槛。

`cover-slot-sampler-run.log` 自然1失败/36.004秒：EXIF1 已通过同 owner 关闭态稳定双页、开启态稳定非默认单页以及候选两页新 body 的对照；下一 EXIF2 停在封面准备。此前真实翻页保存了进度，ReaderViewModel 默认恢复 chapter.last_page_read，重开不自动回0；本次还没执行到 EXIF2采样断言。下一包将通过既有 SavedState 输入 `page_index=0` 明确每向量起点，再以实际封面确认。独审另发现 alpha30/210 在重复预乘错误下平均色差约29.5仍可能低于30阈值；改为 alpha60/240，使错误对照超阈值，同时保留正确不透明黄金和方差保护，未更改 production 匹配规则。

Windows 原 profile 的关停、缓存移动和进度重置组合命令被工具自动策略整体拒绝，未执行；随后只读确认旧进程仍在、备份未产生。改用保留原 profile 的独立新目录 `app-desktop/tmp/udp-final-cold-input-20261002-ca4abd3`：从原两个作品、两话数据库建立一致备份，复制本次偏好和两原扩展，仅重置副本阅读进度；未复制 encoded cache、未注入配对记录，也未复制原 background-tasks journal。正常关停原隔离实例后，用同一正式 EXE 非 headless 启动。

北京时间18:06:34，正式包在此冷配置启动时发生 `IllegalStateException: Module with the Main dispatcher had failed to initialize`，原因链为 `NoSuchMethodException: android.os.Handler.createAsync(android.os.Looper)`；实际入口是 `LibraryScreenModel.syncBackgroundUpdate` 的 `screenModelScope.launch`。本次应用进程77700停留在 Compose 错误对话框，HTTP59143不可用；只读线程证据位于 `app-desktop/tmp/udp-final-cold-bootstrap-threads.txt`，crash log 位于该新 profile 的 `home/AppData/Local/Mihon/logs/mihon_crash.log`。这是正式产品启动失败，不是 Android 测试 ABI 问题，尚未修复。

已核正式发布 ZIP 与 EXE 同目录中的 coroutines-android JAR 字节一致，不是 Android 测试包构建插入；Desktop 同时具有 Android Handler stub 和 coroutines Swing，当前 dispatcher 选择 Android factory 后找不到 `createAsync`。Git 追溯显示失败调用点来自2026-09-09，Handler stub 最近修改来自2026-05-11；DP01 的 Desktop build 文件只改格式和测试资源，后两轮修复也未更改这些调用点。以上不能单独证明首次引入故障的提交。原暖 profile 的后台任务已完成，新冷配置实际触发了两个作品更新，是不同启动路径的线索；尚未做因果对照，不将其写为唯一触发原因。未擅自改产品、补 stub 方法或强制测试 dispatcher，也未因故障追加全量。

现有 `LibraryScreenModelTest` 的运行中更新/重建用例显式 `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`，因此验证后台状态行为，却不会检验正式包自动选择 Main dispatcher 的过程；正式 Windows 脚本的 extension runtime 验收使用隔离空 profile 和 `--headless`。这两类已有绿色结果均不能覆盖此次“有库数据、更新在运行、真实图形 Library 初始化”的失败路径，后续修复必须包含无测试 Main 替换的实际运行证据，不能仅重跑原全量来确认。

只读依赖声明进一步发现 `domain/build.gradle.kts` 的 `commonMain` 使用 `kotlinx.bundles.coroutines`，而 `gradle/kotlinx.versions.toml` 的该 bundle 包含 `coroutines-android`；此声明来自2026-03-18。Desktop 显式依赖 domain，因此存在平台依赖泄漏的确定声明路径；尚未占用实施代理的 Gradle 协调权运行 resolved dependency 查询或修改该声明。后续修复方向应优先校正平台依赖归属，保护 Android 原依赖，不能仅给 Desktop stub 增加 `createAsync` 让 Android dispatcher 在非 Swing 线程运行。

主代理已就此独立产品阻塞提出待批准增量：当前 ART 批次后复用原代理，校正 Desktop 平台依赖，补无 Main 替换的定向红绿与实际运行验证，主代理独审1轮，追加 Desktop 完整测试1次及 Windows/macOS 项目正式构建各1次，预计90–150分钟；不修改 Android 功能。不获批准则保留启动阻塞，仅继续当前已授权 ART test-only 批次。未收到回复前不执行这些追加修改、全量或重构建。

Mac 同一正式包继续使用隔离目录，经 LaunchServices 非 headless 启动。已在本次独立 local source 根目录准备 B1/B2 原 CBZ，分别核 SHA-256 `fec7ffef2dc6773c0c917b5e9961fff745cca5eb9eaccc82deee2dffe26b522b`、`760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439`，仅配置正常 RTL/双页/自动匹配和本地源目录，没有强制分组。实际应用41020、包装器41019，HTTP59131 HomeScreen健康；尚未通过真实本地源入口打开并发送原生键鼠，不能记为 B1/B2 通过。进程号仅为本次取证时点，后续操作须重新核验。

### 本轮 test-only 最终结果与继续边界（DP02 未完成）

最后一次采样中，本轮新增的 Activity 清理等待使用了无界 `waitForIdleSync`，实际窗口已经返回 Launcher，仪器仍不返回；停止本次隔离宿主后保留 `final-sampler-run.log`，该次不能作为业务失败或通过。最终 helper 改为公开 `isDestroyed` 的5秒有界检查，删除临时 smoke/诊断输出，并由外层 driver 分别限制 sampler150秒、source90秒。仅处理 `emulator-5586` 本次包，没有操作实体设备或其他实例。

`udp-art-test-only-bounded-final-apk` 自然 PASSED；四个实际改动测试文件限定格式 Apply/Check、最终 `git diff --check` 通过。最终签名测试包为 `app-desktop/tmp/udp-art-entry-repair-20261002/bounded-final-signed.apk`，SHA-256 `efb7b1fe5a611a98af142ddd4780c064700be5bb74a4d1e6820a3cc5306ceb55`，原证书连续。主代理核验其哈希以及正式宿主 APK 仍为 `d27a010832f84d6b712883284850a263cfee5800ccf9d9d332338a23ed7ec9f1`；没有修改产品、keep、正式 APK 或追加全量。

| 最终门槛 | 实际结果与边界 |
| --- | --- |
| `SharedSpreadNativeAcceptanceTest` | [原始日志](D:/Codex/worktrees/0877/mihon/app-desktop/tmp/udp-art-entry-repair-20261002/bounded-final-sampler-run.log) 自然 OK(1 test)/28.283秒。完整执行 EXIF1–8及第9轮alpha60/240；一侧 EXIF、另一侧 NORMAL 黄金，候选两页各自新 body 请求，关闭态双页和开启态非默认单页各稳定500ms。主代理核日志与判别力，确认该采样验收通过。 |
| `InstalledSharedReaderSourceAcceptanceTest` | [原始日志](D:/Codex/worktrees/0877/mihon/app-desktop/tmp/udp-art-entry-repair-20261002/bounded-final-source-run.log) 自然1失败/50.188秒。ManHuaGui 成功及六种错误→真实Retry经过断言；Copy初始成功与empty错误通过，empty Retry仍未恢复，后五种错误未执行。最后对照仅为Copy各向量图片URL增加唯一query，同一错误→Retry保持身份，保原22contents/words/order并仅淘汰页表JSON、保留图片body；依旧chapterRequests3/imageRequests5，不能把旧失败归结为手删图片缓存。实际PID11434日志仍为无法识别stream及SSIV初始化失败，根因未定位。 |
| `SharedReaderViewNativeAcceptanceTest` | [原始日志](D:/Codex/worktrees/0877/mihon/app-desktop/tmp/udp-art-entry-repair-20261002/crop-scale-view-run.log) 保留1失败/38.381秒；EXIF1 whole黄金、双击放大、实际平移和left split已过，right split+crop/FIT_WIDTH未过；分辨率等比例放大未关闭问题，后续EXIF2–8未执行。最终包未重复这个未修复用例，不将早前结果说成最终新包全绿。 |

实施代理已返回 `status/diff/tests/commit/process/next` 回执并交回执行权；无在途 Gradle/ART，外层 driver 自然退出且设备无活动 instrumentation。主代理同轮局部审查关闭了窗口像素、EXIF单侧黄金、alpha阈值、空色块、坐标换算和瞬态分组假绿风险；整体 ART 因上表两项未关闭，不签收最终交付。四个测试文件改动和必要文档保留未提交；既有版本分配不回滚。最后有效提交仍为 `ca4abd37a32c57c7f7cb4486f1bdfa78af5aa0e1`，未勾选 DP02、未更新 manifest 为新的运行通过。

当前测试入口必须使用已核验的专用 emulator 和同签名测试 APK。准备目录为 `/data/local/tmp/udp-art-native/`，包含两原扩展 `manhuagui.apk`/`copymanga.apk`、`source-inputs.json`、`copy/{success,empty,missing,malformed}.json`。输入行提供 mangaId/sourceId/mangaUrl/mangaTitle/chapterId/chapterUrl/chapterName，当前私有 B1/B2夹具 chapterId 为15640/15652；URL和实际捕获响应继续留在忽略目录，不提交。旧 `sharedReaderSourceId` 等直调内部ABI参数入口已被替换。通过 AndroidJUnitRunner 指定上述原类，运行前显式核 serial 和正式 APK 哈希，并对外层进程设置期限；不要用 connected 任务覆盖正式宿主。受控本地响应验收仍不代表真实远端、TLS/代理或 B1/B2 原漫画的完整原生交付。

继续前先区分两个待定位的 Android 失败与已明确的 Windows dispatcher 启动失败；尚未证明前两者由最近产品修复引入。此段记录时 Windows 额外修复/全量/双平台构建方案尚待批准。保留这两个红例，后续针对实际字节来源和裁切几何定位，不通过调整黄金或反复完整矩阵尝试收口。

### 2026-10-03 按根因收敛的续作授权与顺序

用户明确批准“根据当前方向继续，批准你再进行一次全量测试”。本次追加全量额度为一次，保留到必要修改稳定、发布范围冻结后使用；不立即执行，不重复已经适用的无关完整模块。

复用原实施代理 `dual_page_resume`，主代理独立验收本批实际改动；不新增代理。先将既定 Android 原生验收向量隔离执行，防止首个断言失败隐藏 EXIF2–8 和 Copy 后五种错误；原验收范围、golden、真实 Activity/扩展/解码链保持。诊断阶段控制45–60分钟，连续两次没有新证据停止重跑，返回具体证据缺口。重型 Gradle 由实施代理唯一协调。

三类失败分别追查正式 Desktop 调度器选择、实际裁切/缩放/可见区域、HTTP→缓存→解码器字节。根因确定后按红绿重构修复产品或修正测试，不猜改产品。全部必要修改稳定后才执行受影响的追加全量与项目正式构建，最后补齐正常入口 B1/B2 及真实键鼠运行验收。Android 产品未改变时复用原正式 APK；Desktop依赖调整须核对Android解析影响后再决定其证据可否复用。不新建平行报告或状态系统；尚未关闭 DP02。

独立向量首次执行取得完整的 View 分布：24项中19项通过，只有 EXIF1/5/6/7/8 的 right split＋crop＋FIT_WIDTH 失败。随后同一图片、同一黄金的 crop ON/OFF 定向对照中，ON失败而OFF通过。原 native decoder 固定提交 `41c059e5405c1d982c937767707270ee96c32614` 的灰度边界扫描以亮度阈值识别内容；原夹具的纯亮黄色区域可能被当作白边。当前修正仅为保留原四种黄金色块的夹具添加暗色内容边界，同时加入实际开启/关闭裁边的内容宽度差断言，不能仅凭 crop 参数已设置证明裁边有效。最终自然通过结果尚待核验，不据此修改产品解码规则。

Copy 的逐例独立进程确认初始真实图片为239字节PNG，后续错误流程却把章节JSON当作图片交给解码器。原扩展DEX进一步确认解析错误会返回 `tachiyomi-lib-textinterceptor` 虚拟地址，其原有 TextInterceptor 将错误说明绘制为图片；测试路由先改写主机，绕过了这个拦截器。这是测试夹具的确定边界错误，不是已证实的宿主 parser 或图片解码回归。修正方向是保留原虚拟拦截器，并严格拒绝未知真实请求；不伪造图片、不复制扩展的文本渲染、不改变第三方扩展。章节错误仍验证真实 Retry；扩展错误图片须证明实际错误反馈，并通过正常已有操作恢复到合法章节图片，不能仅展示错误页就算恢复通过。

最新 `udp-art-closed-panel-markers-apk` 自然 BUILD SUCCESSFUL/66秒；签名测试包 `closed-panel-markers-signed.apk` SHA-256 `6359806cc0b38e146b03785a90b822621f4ab6ca1a9e653ef393ee0f20392bc7`。2026-10-03 08:32 的两个关键定向用例已自然通过：`resume-crop-normal-run.log` 的 EXIF1/variant2 OK(1)/4.881秒，`resume-crop-exif6-run.log` 的 EXIF6/variant2 OK(1)/4.608秒。主代理独立核原日志、测试实现和包哈希；两项均保留原色块、FIT_WIDTH、左右方向，并验证实际 crop ON/OFF 的内容尺寸差。确认这两个裁边夹具失败已修复；完整24项及源错误恢复仍待，不据此宣布整体原生验收完成。

同包的 `resume-copy-empty-run.log` 自然 FAIL(1)/32.805秒，失败仅为仍期待 ChapterError。原拦截器已经生成24007字节PNG，SHA-256 `32623c8934994beaa9277f96a682370e77c24b18a184339cda348ebe59d8ee53`；主代理独立查看 `resume-observations/copymanga_320544/window.png`，实际阅读窗口显示“漫畫吐槽／加载失败，请清除缓存后重试／List is empty.”和 `1 / 1`。章节请求1、真实图片端点请求0，缓存体为原PNG而非章节JSON。确认保留虚拟主机后原错误反馈恢复；此项仍未完成用户正常恢复验收，不把旧 ChapterError 断言失败归为新产品故障。

随后 `udp-art-text-image-pixel-alignment-apk` 自然 BUILD SUCCESSFUL/25秒；`text-image-alignment-signed.apk` SHA-256 `8b7947299d1538b575d5c1dd077e91381144c8142555d4d2f400ef85a5b0e552`。`aligned-copy-empty-run.log` 自然 OK(1)/7.805秒：原拦截器PNG与当前窗口的黑色文字采样匹配至少95%（允许原SSIV缩放造成的1物理像素偏移），真实 More → Settings → Data and storage → Clear chapter cache 操作后重新打开原章，章节请求再次增加、真实图片端点请求增加并实际绘制CYAN。主代理核原日志与实现；未直接删除文件或复制错误渲染实现。确认 Copy empty 用例的测试路由和恢复验收已修复，余下响应向量及最终批次结果继续记录。

同包 `final-marked-view-24-run.log` 自然 OK(24)/90.82秒，完整 EXIF1–8 的 whole、left split、right split＋FIT_WIDTH＋crop ON/OFF、真实双击放大和实际拖动均通过；`final-route-sampler-run.log` 自然 OK(1)/29.368秒，保留原完整 EXIF／alpha／实际匹配分组门槛。主代理独立核两个 runner 原日志及先前读取的断言。确认原裁边测试失败已修复，完整 View 和 sampler 原生验收通过。

Copy vector1/2 分别在反馈观测失败后停止重复：原错误PNG为1000×473和1000×427，实际全宽白底范围为 `[0,944,1080,1455]` 和 `[0,969,1080,1430]`。长文字使原“每行60%为白色”观测切断图片范围，并非实际图片被高度限制。主代理独立查看 vector1 窗口，明确显示原 missing-field 错误说明；离线原图白色外边距定位所得126/131黑字匹配及比例2.114/2.113支持修正观测范围。保持95%字形门槛和真实恢复要求，未据失败猜改产品；修正后的余下五向量自然结果尚待。

`udp-art-white-margins-recovery-apk` 自然 BUILD SUCCESSFUL/27秒，签名包 SHA-256 `38edd857919d9a333422f912b386d2fa212dc3925c7ac3cc5d91bdfaeaece24b`。原14向量分别独立进程执行，`white-margin-{source}-vector{index}-run.log` 的13项自然 OK(1)：ManHuaGui全部7项，Copy成功、empty、missing、403、429、500。所有错误项均实际反馈后取得新合法章节响应/图片并绘制CYAN。唯一 Copy malformed 停在字形比对，原图细笔画未按实际缩放插值的采样为102/110，按实际1080×461绘制矩形缩放后为76/76；修正仅涉及测试图像对照，保留95%／1像素门槛。下一步只复验这项及必要代表性用例，不重复整组，也未使用全量额度。主代理独立核14份日志终态及本包完整哈希。

最终 `filtered-error-image-signed.apk` SHA-256 `8db2c27f7f203ffa03ab541aee067cda7ff05e77e70a153937b188b0391342d7`，只将原错误PNG按实际绘制矩形进行公共双线性缩放后比较字形。`filtered-copy-vector2-run.log` 自然 OK(1)/7.495秒，代表性 `filtered-copy-vector0-run.log` 自然 OK(1)/7.994秒；主代理核原日志、最终包哈希及真实恢复实现。复用未受此观测修正影响的13个源用例、完整View24和sampler1的有效绿色结果，三个既定入口共39个方法均有有效通过证据；不是一次39项完整运行，也不把早前失败整轮改称全绿。确认原裁边／Copy夹具和恢复验收问题已修复。正式宿主 APK 未改；本次专用5586模拟器及其两个已核进程正常关闭，未操作实体设备。Windows产品修复、追加全量和三端真实作品交付仍待，DP02保持未完成。

Windows严格红阶段新增 `DesktopProductionMainDispatcherTest`：Gradle提供实际 `jvmMain` runtime classpath，独立子进程只带probe类和production依赖，不带 `coroutines-test`，实际调用 Main 并创建真实 `LibraryScreenModel` 观察活跃更新任务。前两次临时依赖图查询分别因任务注册时点和artifact类型歧义失败，未执行Main，不计行为红；查询改为解析后的外部component graph，未改变产品。`udp-windows-production-main-red3` 自然失败/15秒，Main子进程精确复现 `Main dispatcher had failed to initialize` → `NoSuchMethodException: android.os.Handler.createAsync(android.os.Looper)`。主代理独立核XML完整原因链并保存 `windows-production-main-red3.xml`，随后才准许production修改。修复前Desktop外部组件284且含coroutines-android，domain Android release外部组件121、坐标SHA-256 `1291d53921f9f0c88afc547716dc6aca14a33edeecf6b51a199522d534bc54fc`；绿色阶段须保留Android图并确认Desktop回到真实Swing EDT。

绿色实现仅将domain的共同协程依赖展开为core／guava，将android依赖归属androidMain，并删除jvmMain重复的core／BOM声明；没有修改Handler stub、Library行为或强制调度器。`udp-windows-production-main-green` 自然 BUILD SUCCESSFUL/8秒，新真实production Main测试1项、0失败/错误，子进程执行真实Swing EDT和Library更新观察。Desktop外部组件283且不含coroutines-android；Android仍121且坐标SHA-256与修复前完全一致。主代理独立核resolved graph、测试实现、XML及实际diff，确认最小修复和平台边界有效；重构复验、相关格式及正式包冷启动仍须完成。

`udp-windows-production-main-refactor-context` 自然 BUILD SUCCESSFUL/12秒：新production Main测试1项和现有LibraryScreenModelTest58项共59项，0失败／错误／跳过，XML明确记录 `PRODUCTION_MAIN_EDT_LIBRARY_COMPLETE thread=AWT-EventQueue-0`；相关限定格式通过。首次重构命令只因临时格式hook缺app插件上下文失败，未运行业务，随后复用已有可靠入口修正，没有更改产品或建立新格式框架。主代理独立核实现、两份XML和协调器终态，确认调度器初始化问题已修复并完成同范围复验。正式包冷启动仍待最终构建验证。

本批13个受控文件内聚于DP02阻塞关闭：共享Android ART夹具替换重复内部ABI测试并保留原验收、Desktop平台依赖与真实Main回归、必要维护文档及已分配版本记录。超过8文件／400行主要来自夹具整合，不拆成不能独立验收的微任务。独立审查复用主代理当前轮，未新增代理或泛审；相关定向门槛满足后冻结产品，不新增能力，DP02仅在正式全量／构建／真实运行证据与提交齐全后勾选。

修复提交 `84d98c7176e7c26022f959066d432acd32406733` 后执行本次用户批准的追加最终矩阵。`udp-final-third-desktop-full` 经项目 `build-desktop.sh full-tests` 自然 PASSED／4分57秒；435 suites、3251 tests、0 failures／errors、3原条件skip，XML时段2026-10-03 01:06:05–01:10:34 UTC。共享与Android／客户端／格式第二条命令中，共享JVM620项新执行通过；共享Android525项和客户端53项为UP-TO-DATE复用，其图／源码未变。命令最终在domain Gradle脚本的换行格式检查失败，尚未执行应用完整Android单元测试，不能把整条命令称绿。

保留失败日志后仅运行格式修正：初次附带Desktop标准formatter任务因该项目没有此任务而未执行；`udp-final-third-domain-format-only` 经 `:domain:spotlessKotlinApply spotlessCheck` 自然 PASSED／16秒。修正只将domain脚本工作区换行还原为已提交内容，重新暂存核验后Git无源码diff，未改变冻结产品，不追加完整业务测试。应用Android完整单元测试由 `udp-final-third-android-remaining` 补上本矩阵首次实际执行，自然PASSED／16分33秒：124 suites、708 tests、0 failures／errors、7原条件skip。外层等待超时后查询协调器确认自然完成，未重启测试。Mac隔离repo已快进到同一 `84d98c7176`；原BUILD69工作区改动以具名stash保留，没有覆盖日常checkout／应用。


2026-10-03最终矩阵已完成：Desktop3251、共享JVM620、共享Android525、Android应用708、客户端53；除上述原条件skip外零失败／错误，最终spotlessCheck通过。Windows `udp-final-third-windows-build-only`自然PASSED，发布0.11.19.70.84d98c7；Final unpacked EXE为 `D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.70.84d98c7-unpacked/Mihon Desktop.exe`，文件存在。新正式包在此前失败的隔离配置启动真实Library READY，外部窗口确认书架及正常章节入口第169话真实图片；确认正式包冷启动问题已修复。用户移动窗口后重新核对实际窗口和命中PID；用户随后选择自行完成Windows双页／键鼠验收，停止Windows原生输入，B1/B2结果待用户反馈，DP02不勾选。

Mac同源码production Main定向测试1项通过，真实Swing EDT／Library完成；正式构建首次被Homebrew vendor guard拦截，不是业务失败。核对项目脚本原有Temurin21.0.10后以其绝对JAVA_HOME继续build-only，恢复失败构建分配的BUILD以保持两端70一致；`udp-final-third-macos-build-only-temurin`自然成功47秒，独立部署到 `/Users/altair/Library/Caches/mihon-shared-dual-page-20261002-f95ffeb/artifacts/Mihon Desktop.app`。LaunchServices启动实际PID47006，HTTP healthy，显示器正常唤醒后原生Tab投递成功；尚未完成正常LocalSource入口B1/B2及完整键鼠断言，不标为运行验收通过。没有重复完整JVM测试，也没有修改日常应用。


2026-10-03 Windows用户现场验收（正式0.11.19.70.84d98c7）：B1第17页先居中随后右侧，前组15/16，顺读尾部验收无误，退出重开结果一致；B2第20页居中，第2页左侧，页面不丢失。用户另观察未充分加载时当前页由居中移到右槽，随后左侧页出现。设计4.2第5优先级明确未决组先呈现目标页居中，再根据有效事实重排，7节保持原定位目标；与该加载策略一致，最终分组通过不等同没有视觉移动。没有复现反复来回摆动或目标丢失，不据此新增产品修复／全量测试。

纠正主代理之前的手动说明：当前Desktop键盘0／End使用presentation.displayUnits总数，而该序列包含章界提示；GoToPage处理命中transitionDirection时选中提示单元。因此用户按0先看到“下一页没有啦”，再退到17，与当前接线一致。此前将0说明为“直接到源末页”不准确；直接定位源17应使用底部源页码进度条。用户现有反馈证明边界前17页与15/16可读，但不能替代源页码条直接17的独立结果。此项不擅自修改快捷键语义，不把说明错误计为新增双页产品回归。Windows实测反馈保留，DP02仍未勾选。

## 2026-10-04 v3增量实施

用户要求实施唯一roadmap，起点为干净的 `9fe50edf6e`。按计划启用1名实施子代理，A→B→C串行；主代理进行1轮分段独立审查。定向红绿重构估算四组共12次，SP01末尾受影响批次验证1次；SP01实施、审查、验证及提交完成后才使用本轮新增最终矩阵1次、Windows/Android/macOS正式构建各1次与对应运行验收。失败不自动追加全量或正式构建，旧70证据不算v3通过。

首次只读前置核验：本worktree未观察到在途Gradle/协调器进程；Android SDK的android-36/android.jar、36.0.0/aapt2.exe和platform-tools/adb.exe存在，当前无连接设备，已有 `mihon-pairing-api36` 专项AVD。Mac入口可达、macOS14.8.4和既有Temurin21.0.10有效；旧隔离checkout为 `84d98c7176` 且保留BUILD69→70未提交改动，其他应用实例保持原状。这些只证明环境前置，不证明本轮原生行为通过。

SP01.A共享接口已通过首段独审与定向验证，已放行同一实施代理串行执行SP01.B Desktop→Android；尚未完成平台/资源/操作链验收，不勾选SP01或DP02。具体红因、结果、日志和S01–S17映射在本节随实际证据追加。

`sp-a-stable-red` 实际执行新 `SharedDualPageStablePresentationContractTest` 的8个用例，8个行为断言失败、0编译/引擎错误；主代理核读测试和原XML。正确红因包括未知目标FULL/未知前缀依赖、整组进度门禁、跳过未画伙伴、晚恢复换组与certain手调门禁。原XML保存于 `.gradle-coordinator/sp-a-stable-red-tests.xml`，日志为同目录 `sp-a-stable-red.log`。最小实现后 `sp-a-stable-green` 自然PASSED/1分15秒，8个用例通过。该结果仅证明首轮反例；竞争/预留、邻屏/history、身份失效及受影响旧契约仍在补齐，A接口尚未签收，两端原生接线尚未实施。

主代理执行 `python scripts/build-android.py check --signing` 退出0，既有正式证书可用，JDK21和SDK36前置通过；未构建、未安装、未分配版本。这不是Android业务验收证据。

SP01.A接口独审定向补齐了部分双页进度累计、动画邻屏生命周期、投影/页归属、空loading票据、真实内容替换、快速连续定位、视口变更和开篇摘要反例。初轮旧契约中与v3冷跳、默认双页、迟到恢复和逐页进度明确冲突的断言随规格更新；B1/B2、EXIF分类和保存失败保护未放宽。`sp-a-refactor-focused2` 的Android98项中视口反例失败，JVM尚未执行，未以旧XML记录本次JVM通过。

同一独审第一段最终关闭三项真实问题：自己的pending伙伴被占用索引误认成其他组、反向组首槽先画后丢失后槽、迟到手调保存未使原冲突历史失效。`sp-a-review-boundary-red` 实际执行2项/2个行为失败，最小修复后 `sp-a-review-boundary-green` 的这两项及视口反例3项全绿。可见冲突帧在真实release前保留，迟到保存只影响未来回访，不回跳当前定位；空placeholder不成为显示历史。

冻结共享快照后的 `sp-a-final-jvm-focused` 自然PASSED/12秒，包含domain Kotlin格式修正与检查；`sp-a-final-android-focused2` 运行真实 `:domain:testDebugUnitTest`。主代理独立解析两端新XML：JVM时间为2026-10-03T18:37:03Z，Android为18:38:08–09Z，均为8类100 tests、0 failures/errors/skips，其中新增稳定呈现契约30项、session28项。此前误用不存在的 `:domain:testAndroidHostTest` 仅配置失败、0测试，不计行为结果。对应日志位于 `.gradle-coordinator/` 同名文件；没有完整模块或正式构建。上述证据只签收共享接口，不证明平台真实首帧、零位移或用户交付；SP01保持未勾选，后续复用同一实施代理执行B/C。

SP01.B Desktop首轮通过真实 `ReaderContent`、HorizontalPager、原图像owner/pipeline和 `ZoomablePageBox` 离屏绘制复现：C已画、D未画时没有partial进度；实际tap及 `MouseWheelEvent` 都跳到源index3而不是未画伙伴index2。对应有效红为 `sp-b-desktop-native-red` 的partial用例、`sp-b-desktop-navigation-red` 及 `sp-b-desktop-wheel-red`。前两轮wide用例在伙伴放行前没有完成宿主首画，不能作为宽伙伴被错误半槽绘制的红因；已保留并区分其失败证据，不以它宣称修复宽图旧故障。

最小绿 `sp-b-desktop-native-green` 新XML时间2026-10-03T19:01:55.942Z，实际4项全绿：真实partial→同定位追加、连续tap、wheel及迟到wide。partial验证逐帧整幅内容矩形与真实Compose呈现对象身份；wide当前首画与伙伴到达后不画半槽、不移动C。数据和观察来自真实pipeline，未手工调用drawn。此里程碑尚不证明真实SQL写入、缩放/邻屏/内容替换、Android或正式产物；后续补齐，保持SP01未勾选。

原图输入只读核验：B1 CBZ有17图、原编码总6350792B，SHA-256 `fec7ffef2dc6773c0c917b5e9961fff745cca5eb9eaccc82deee2dffe26b522b`；B2有22图、11801744B，SHA-256 `760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439`。对应dimensions JSON的wide页分别为[10]及[3,10,19,21]。HostReaderArtFixture固定ManHuaGui单URL解析样例不等于原B1的17页验收，后续S09/S10必须绑定上述真实原图和正常产品来源链。该核验仅确认输入，未执行原生读取或配对验收。

Desktop后续原生反例覆盖了原内容lease保留、远页替换、显式crop/实际viewport变更、缩放平移中的迟到wide、真实曝光与prefetch区分、原Retry和触摸drag。`sp-b-desktop-geometry-red` 的2项实际失败分别为crop丢失未画伙伴、实际resize没有更新geometry context；`sp-b-desktop-drag-red` 使用项目既有Touch输入精确复现目标index2被跳到index3。早前Mouse输入未形成有效drag的失败不计业务红。反向D先画/C未画、无可选header的真实Ready和远端portrait猜测被实际wide纠正也形成独立行为红，随后最小修复；没有通过等待伙伴或新增首帧宽限获得绿色。

`SharedDualPageRuntimeProgressProductionTest` 通过真实Compose绘制、runtime factory、ReaderProgressTracker、RecordReadingProgress和内存SQLDelight/JDBC链验证partial→追加：先写源index1与1个事件，D放行后写index2与第二事件，多帧不重复计读。首轮 `sp-b-desktop-runtime-progress` 新XML时间2026-10-03T19:08:10.284Z，实际1项通过；并非手工调用settle或mock progress parser。

`sp-b-prepared-boundaries-green` 保存的4份新XML由主代理独立解析：共享stable31、session28，共59项；Desktop真实原生14、SQL生产链1，共15项，均0 failures/errors/skips。共享XML为2026-10-03T19:55:10–11Z，Desktop为19:55:28–37Z。该快照的原生测试逐帧核完整内容矩形、Compose对象身份、frameId和非默认zoom/pan，不仅比较中心线。此结果只证明该定向集合，不证明Android、资源预算或正式运行通过。

首次受影响旧Desktop回归 `sp-b-desktop-regression` 实跑83项，其中23项失败。分类中有3项旧契约与v3冷跳/缺尺寸/未画票据语义冲突，须按新协议适配；原即时翻页16项出现首帧背景，另4项双页快速/反向目标仍待诊断。没有将这些失败称为绿色。随后新原生14仍绿、即时翻页16仍失败，排除了仅凭上一帧LayoutCoordinates缓存作因果判断。新诊断XML确认逻辑定位先更新而旧页面仍可见时，旧票据被提前释放；修复与受影响复验尚在进行，SP01.B仍未签收，未启动完整模块测试或正式构建。

Desktop稳定里程碑随后关闭上述首帧空白：旧画面仍暴露时保留其原有有效几何票据，新逻辑定位撤销旧屏计读/追加资格；不将旧像素续画误当新导航。原PageTurn断言仍逐帧拒绝即时翻页背景；v3导航预期用真实 `ReaderPageIoObserver.firstPresented` 建立因果，前槽实际未画时不能把旧双页下标当作已读。`sp-b-desktop-slot-lifecycle-red` 的共享1项及Desktop3项保留两个正确红：从已画前槽反向时误走未来后槽，以及伙伴错误的真实Retry按钮重挂载当前已画图。修复仅优先相应方向内的未画页，并对重试页定向撤销lease；未锁伙伴换内容可继承原槽，已锁且内容未变的另一图保留frame、mount和变换，旧owner票据仍不能报告替换内容。

`sp-b-desktop-focused-final4` 新XML中共享60项全绿，Desktop86项仅远处宽图候选1项失败；其中PageTurn40、真实Native17和SQL1通过。格式同时另有3处长行失败，两类结果分别记录，不以业务绿覆盖格式失败。远宽图回归明确复现当前定位16时远处9的有效SPREAD仍留在[9,10]缓存候选。`sp-b-projection-delta-red` 的真实共享缓存契约1项正确失败后，最小修复只在changed页附近使用原有有界投影更新；当前已锁frame、不相邻单位对象和每源页唯一归属保持，重复且不改变布局的事实继续复用列表。没有扫描全章重算配对。

`sp-b-desktop-projection-green` 由主代理核验5份新XML：共享32＋28项（2026-10-03T20:29:07Z）、DesktopWiring5＋Native17＋SQL1项（20:29:21–26Z），共83项，0 failures/errors/skips。没有机械重复仍适用的其余旧回归；最终SP01冻结批次按后续实际改动验证。`sp-b-desktop-scoped-format` 自然PASSED/18秒，终态2026-10-03T20:31:43Z；复用原临时格式入口，仅App0、Domain10、Desktop14个本任务Kotlin目标。此前3次配置失败分别为未注册Desktop任务、PowerShell未整体引用参数及按需配置未加载app插件，均0业务测试，不计产品红。

实施者已交付 `status/diff/tests/commit/process/next` 结构化Desktop里程碑回执；主代理核证并放行同一代理继续Android B。当前全部Gradle已终止，无在途重型进程，产品与文档保持未提交。该回执只签收Desktop协议、几何/导航及真实SQL的开发定向证据；Desktop原B1/B2正常来源、EXIF补证、Android View/VM及JNI/ART、C资源/性能/操作反馈和正式S17仍未完成。SP01和DP02继续未勾选。

Android首轮 `sp-b-android-progress-red` 自然FAILED/14分47秒。主代理独立解析保存的XML（2026-10-03T20:38:47.468Z）：6 attempts、6 failures、0 errors/skips，实际是2个方法各执行3次，不能写成6个独立场景。真实VM＋SQL用例先成功写入partial源index1，第二次同pending追加index2时等待事件为空（行327），证明原一次性settled门禁吞掉追加；实际挂载Ready holder的用例在尚未native draw时发现frame.locked非空（行350），证明挂载过早成为几何承诺。二者为有效行为红，不是编译或测试引擎失败。

该轮前导通配符方法过滤触发大量Test Executor类发现，默认maxRetries=2又重复扫描，增加流程开销但未扩大业务执行集合；进程均自然结束，未并行重启。后续改用完整FQN方法选择及仓库已有 `udp-focused-no-retry.init.gradle`，仅当前定向TDD调用关闭自动重试，仓库和正式验证默认不变。VM调用只证明进度/wiring；实际Android像素、首帧和零位移仍须后续holder及JNI/ART证据，不能以这两个Robolectric用例代替。

Android追加的 `sp-b-android-activation-red` 使用完整FQN及已有focused无重试入口，实际2项/2失败，XML为2026-10-03T20:51:19.318Z。真实相邻章节activation尚在等待时，第二次actual callback累计仍为1而不是2；逻辑目标图片错误但确定伙伴实际已画时，原目标Error全局门禁使伙伴没有SQL事件。主代理核原XML及production接线，不把前轮Executor类发现次数当业务场景数。

最小实现保留原定位token、activation与pending对象，分别累计presented/accepted源页；等待activation结束后读取最新实际集合，不制造新导航。仅对实际绘制集合检查Error，保留章节、页身份、定位及隐私检查；离屏旧回执仍拒绝。`sp-b-android-progress-green` 自然PASSED/1分02秒，主代理独立解析3份新XML：共享stable32＋session28项（2026-10-03T20:54:17Z）、真实VM/SQL4项（20:54:35Z），共64项，0 failures/errors/skips；App2、Domain10、Desktop14个任务目标格式通过。确认Android同定位追加进度及等待激活时丢失回执的问题已修复；这是VM生产链验证，不能替代真实View像素与逐槽反馈验收。

为取得B的真实Android呈现红测，使用统一 `python scripts/build-android.py debug`，协调器 `android-candidate` 自然PASSED/2分06秒（21:17:12Z），随后 `sp-b-android-art-test-apk` 的 `:app:assembleDebugAndroidTest --no-parallel --max-workers=2` 自然PASSED/3分06秒（21:20:51Z）。两次构建期间全部tracked source/docs冻结；没有完整模块测试、正式候选、版本递增或实体安装。开发宿主为 `app.mihon.desktop.fork.dev`，APK SHA-256 `330ae4c3f8772137c8e850078e2b28a424d46d909c17407f0202ff6569bc1f43`；dev.test仪器APK为 `3b2287a59507e8ee951f752a6aee44f477d31bed03ab34e471b527aee5db9139`，target dev，两包同debug证书 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`。这些开发产物不作正式交付地址。

主代理启动前实核31.8GiB总内存、8.08GiB可用，无连接设备或既有emulator，5586/5587端口空闲。专用 `mihon-pairing-api36` 使用1536MiB/2核、read-only、不加载/保存快照、no-window启动，serial `emulator-5586`、hardware `ranchu`、API36 x86_64身份均实核；父PID40840，启动日志 `app-desktop/tmp/udp-sp01-art-20261004/emulator-{stdout,stderr}.log`。原ManHuaGui/Copy APK精确校验既有SHA后，连同原捕获响应只暂存到 `/data/local/tmp/udp-art-native/`。实施者通过统一installer安装dev宿主，仪器包仅安装此AVD；首次正常MainActivity初始化独立dev数据库，不迁移正式用户库。真实窗口首帧、伙伴迟到/宽图/错误及SQL的首次ART红正在执行，尚未记录其结果；不会把未执行的后续断言记为有效红或绿。

首次ART红 `sp-b-android-art-native-red` 自然终态101.615秒，3 tests/3 failures；portrait/wide/error三向量均停在“Actual first-slot pixels must reach the real SQL progress path”。主代理核原日志及测试：C实际PixelCopy矩形为 `(540,796)–(1077,1605)`，真实SinglePageSubHolder/SSIV为540×810，原Copy响应解析及C图片HTTP200存在，D gate尚未放行。故这是实际首图已显示但partial SQL未提交的正确红；不是图片未画或环境错误。宽伙伴不得半槽、逐槽Retry及伙伴追加的后续断言均未执行，不以此记录它们已红或已绿。开发宿主和源码身份保持上述绑定，首次仪器进程已自然结束，AVD仅本轮专用实例继续保留供focused修复复验；最小holder实际draw接线仍在实施。

原图输入进一步独立核对：主代理直接读取两份原CBZ的每张encoded图像头（仅元数据，不作呈现），实得B1的17图/6350792B及宽图[10]，B2的22图/11801744B及宽图[3,10,19,21]；全部原图EXIF为1，与既有dimensions JSON一致。此核验保护S09/S10输入真实性，不能替代正常来源入口及实际分组/几何，也不能证明S11的EXIF1–8。

首次最小协议修复将onImageLoaded与实际native draw分离，并接partial累计settle。`sp-b-android-native-protocol-format` 自然PASSED/18秒，21:29:01Z；随后开发宿主SHA `840b56ea7392ca77fa5139e60687100cb3cd14db39d6447ba5a2e439462cd324`、仪器SHA `5866fecc71c54b0379e90fd717342249bb0af6762b41fd01727b6ef82381c197` 在同一冻结源码上构建成功，仍为dev/debug证书，不递增正式身份。协议ART三向复验正在执行，结果待实际日志。主代理独审另指出新beforeDraw以尺寸存在即prepare可能提前锁定；实施者核真实缓存AAR的该版SSIV，确认sWidth/sHeight可先完成，而onDraw尚无ready bitmap/base content即返回。该边界确需补真实JNI metadata已完成但region decode受控的红测及最小修复；当前三向首协议结果不能排除它，也不能以跳过初始化导致死锁或额外等待一帧获得通过。

首协议ART `sp-b-android-art-native-protocol` 自然终态12.55秒，3 tests/2 failures。三向均已通过C实际像素→SQL源index1的首断言；error一向通过其原有Retry中心、C未缩放观测及伙伴实际补入SQL2。portrait/wide在真实doubletap后因夹具使用未乘祖先transform的getLocationInWindow/width关联PixelCopy中心而找不到对应View，属于观测坐标错误，不计产品宽图或追加的正确红。主代理核旧error反馈仍为全holder及XML center布局，要求使用完整bounds包含对应half而非中心点小于中线、补D Loading在C显示后仍有独立反馈，并将error纳入真实zoom；旧error单向PASS仅代表已执行的窄断言，不代表逐槽反馈已实现。

保持上述production宿主不变，仅增强原生测试并构建新仪器包 `c863801e9c323db3197b666be1fc1a897c340d9d6fbfb30cf507153ac5d4c11b`，`sp-b-android-native-boundaries-test-apk` 自然PASSED/28秒。新增 `ReaderPageImageNativeDrawGateTest` 使用原公开SSIV executor放行真实JNI初始化、hold原base decode任务，元数据阶段检查0prepare/ACK，release后检实际MAGENTA pixels与同一draw pass的prepare/ACK；不替换decoder或手工调用shared drawn。真实窗口Native3同时修正transform观测，增强Loading/Retry整槽界限。四项正确红正在执行，未提前记录结果；仍无正式版本分配或完整模块测试。

加强后的 `sp-b-android-art-boundaries-red` 自然终态44.839秒，4 tests/3 failures，主代理核原日志。portrait向量在修正实际transform关联后完整通过；真实JNI gate在sWidth=400、base decode held、isImageLoaded=false时prepare计数为1而非0，明确证明元数据提前锁定。error向量已通过C像素与SQL1，却在“D pending loading feedback must remain inside its LEFT slot”失败，证明加载反馈未按D独立槽位提供，尚未执行加强后的Retry断言。wide向量在真实D Ready及adapter SPREAD已到后无法取得当前C像素，需进一步核mount/pager状态；不能仅以缺观测异常声称已复现半槽或已修复几何。实现者继续按两个正确红最小修gate和槽内反馈，宽图在明确原因前保持原行为；没有完整模块或正式构建。

最小gate/Loading修复后的开发宿主SHA `8239cb23fefb46d6c6021a9181331097af3514bd9d0a011e597a854c8c16de03`，仪器SHA `1fa55c03317c7e3206d2724a1bae79b3da7f4ae9862b7b8b27ecc7a795cc8ee0` 均由冻结tracked源码构建成功。`sp-b-android-art-gate-loading` 自然终态89.863秒，4 tests/3 failures：portrait完整通过；error已通过D LEFT Loading及真实zoom，随后Retry整个bounds跨槽（line87）为新的有效行为红；wide本轮在launcher idle/setup超时，不能当宽图业务红。JNI metadata阶段0prepare已通过，但release后actual ACK未到，故完整native gate仍未绿。实施者诊断Android硬件子RenderNode可仅重绘SSIV自身而复用父wrapper displayList，父dispatchDraw不足以保证每次实际childdraw回执；下一最小修复移动到原SSIV/PhotoView子类onDraw前后，需继续用真实ready flags、PixelCopy及ACK证据核原因和同一门禁，不增加等待帧或替换解码器。

同一轮B进度/身份独审另登记两个待核风险：history frame.drawn包含以往实际显示，回翻新holder未画时不能仅用它在idle计读；原PhotoView复用时旧drawable非空也不能当新请求已解码。二者按S14/S15原边界补真实production接线反例，不提前称为已修复，也不以全量测试代替诊断。

将Loading/Error沿原FrameLayout放在不随pageContainer变换的物理half后，`sp-b-android-child-draw-error-format` 自然PASSED/18秒（22:10:01Z）。开发宿主SHA `d62511dd3f25c63053658c0a0bbe6a26320b1dba5d385d225e0e814ca0fb8fed` 与仪器SHA `0013b677aa8061f944f20f64e26b7c4820fd73f8c543eaadb5c8c7a3b551988c` 同冻结源码构建成功，终态分别22:11:22Z/44秒、22:12:14Z/28秒。

`sp-b-android-art-child-draw-error` 自然终态17.831秒，4 tests/1 failure。portrait完整绿；error通过LEFT Loading/SQL1→真实非默认zoom→Retry及error_message实际未裁切完整bounds均在LEFT→真实MotionEvent点击→D像素及SQL2；JNI gate通过metadata0prepare/0ACK、release后真实base准备和首次ACK仅1次prepare、实际窗口PixelCopy MAGENTA。主代理核日志及原子SSIV/PhotoView onDraw接线，首draw不等matcher，不替换原解码器；之前RenderNode机制仅为诊断推断，旧日志未记录足够flags，不据其单独宣称因果已证实。唯一wide现为明确业务红：实际D Ready及adapter SPREAD后，原C `mount.isAttachedToWindow` 为false（line113），证明当前已画容器被卸载。随后最小修稳定anchor/holder；当前宽图、history重挂进度与Photo新内容身份仍未签收，SP01.B不勾完成。

稳定holder修复保留未改变物理槽的原图片、容器、任务和变换，只撤未显示且不再属于该组的伙伴。开发宿主SHA `c58f77cc05491397989e191a33badcd9ce03ef7ec2ef741e8f1fa026c635b6f7`、仪器SHA `36c3cfb9d19558d8cf9c01946660c886eb0e0c970cd2a7a3ac63324067bd807d` 在同一冻结源码上构建成功。`sp-b-android-art-stable-holder` 自然终态51.842秒，4 tests/1 failure：portrait/error/gate完整通过；wide在真实D Ready及SPREAD到达后，连续30次C的完整内容矩形、mount/container身份、缩放和平移均保持且无D半槽像素，随后真实按键翻到D却无FULL像素（line123）。因此仅签收已执行的稳定几何断言，不能写宽图完整通过。

生产c58f保持不变，补诊断及原Single真实手势反例；`sp-b-android-native-single-diagnostic-test-apk` 自然PASSED/43秒，22:23:38Z，仪器SHA `f031f7ff869eb2b50f5093756979789460a71e2a63e642d57f9bed8381bf9f7a`。`sp-b-android-art-single-diagnostic-red` 自然终态82.661秒，4 tests/2 failures：portrait/error仍完整通过；D已选FULL、逻辑源index2/seq2与票据seq2一致、SSIV900×600且ready/baseLoaded为true，但该frame锁定/绘制集合为空；原Single双击时SSIV scale未升，实际图片2倍来自父容器，为正确手势回归红。最小修复在新presentation scope时仅invalidate原已解码child，并固定holder原始Double的手势上下文，不重解码或等待，不把撤伙伴后的Double改成Single手势。

`sp-b-android-native-scope-gesture-format` 自然PASSED/16秒，22:26:49Z；统一debug构建自然PASSED/22秒，22:28:22Z，宿主SHA `29ca55ce3bcdabb09bc2b14471331c805e5cbd7b7519543f794c13bf1291b881`；`sp-b-android-native-scope-gesture-test-apk` 自然PASSED/28秒，22:29:42Z，仪器SHA `3dbb3b737b9778645729bc9d7c4d85e9c3e04172c19fd9da2312d4b885f1fa64`。两包绑定同一冻结源码，原Native3＋Gate＋Single共5项的ART复验结果待实际终态。主代理同时核Android key/tap/wheel仍走旧pager组索引±1，将真实源页导航、邻屏拖动、history当前实画资格及PhotoView新请求身份继续列为B未闭项；不以当前窄几何绿提前推进C。当前资源约2.3–2.4GiB可用，无并发新增重型编译；只沿已有专用AVD及已构建两包继续focused原生复验，不清理其他Java/Gradle进程。

`sp-b-android-art-scope-gesture` 自然终态54.568秒，5 tests/1 failure；主代理核原日志及原Single真实MotionEvent/SSIV scale断言：portrait、error、Gate、Single完整通过，确认初始Single误用整组缩放的回归已修复。wide仍在实际下一步FULL像素处失败，C稳定且未半槽的既有断言继续通过；scope-change单独invalidate未关闭这个红。实施者保持同一问题诊断，检查ViewPager实际曝光后是否重新触发child绘制，不增加固定等待或重解码。此结果只签收四项及wide已执行的窄断言，Android B仍未完成，无提交或正式版本分配。

真实 `onPageScrolled` 与idle曝光回调只invalidate可见且本holder未实画的原child，已画C保留原容器/变换。`sp-b-android-native-boundary-batch-format` 自然PASSED/19秒，22:47:11Z；统一debug自然PASSED/24秒，22:47:53Z，宿主SHA `950b6b7aacd3324d5faa0de4cfcbd439e0f48ebdaee8ac38065e0626a32f2d46`；`sp-b-android-native-boundary-batch-test-apk` 自然PASSED/35秒，22:49:21Z，仪器SHA `2dddb46e80c5c28b6e7830553fdbce8826aca9aa71fd8aaa1e112523060790a0`。限定测试构建串行、max-workers=1，两包仍为同一冻结源码和原debug证书。资源检查发现约2GiB可用，但无持续换页或异常，另一工作树已无active任务，只复用常驻daemon；未终止其他进程。

`sp-b-android-art-boundary-batch-red` 自然终态40.003秒，9 tests/6 failures，主代理独读日志与实际测试。原NativeStable3向全部完整通过；wide的C30次内容矩形/挂载/变换不变、D不半槽、真实DPAD_LEFT后D宽度超过视口75%的FULL像素及SQL源index2均通过，确认宽图下一屏空白已修复。新增红保留：原Coil两帧GIF旧drawable仍显示、新请求encoded stream受控未完成时，replacement prepare应0实1（line79，ACK及release后断言尚未执行）；真实原reader回翻、旧holder已卸载、新窗口preDraw暂停而activation/SQL正常时，事件数43变45（line67），证明历史draw集合过早计读；key/tap/wheel/drag四个真实窗口事件在D原HTTP请求held时均应定位源index2，却跳index3（line49）。6项是正确行为红，不是环境、编译或空筛选。继续原B身份/计读/导航最小修复；Gate/Single仍有效且本次未改其行为，未机械重复。Android B及真实B1/B2、EXIF、C和正式S17仍未全部签收。

三类最小修复分别绑定Photo drawable对应的原decode identity（旧图可继续画，但不借用新请求prepare/ACK）、本holder当前owner/location的actual paint集合与shared历史draw集合的交集、共享源页导航再映射原ViewPager位置。原usePageTransitions保留；拖动沿原动画曝光锁定，到真实idle校正源目标，临时邻屏不读。`sp-b-android-native-source-progress-format` 自然PASSED/22秒，22:58:23Z；统一debug自然PASSED/34秒，23:00:30Z，宿主SHA `0d4d71e93e55cef80a06ab868d6165ff79f71b68eed81854d12a759c8bed2d5f`；`sp-b-android-native-source-progress-test-apk` 自然PASSED/34秒，23:02:31Z，仪器SHA `f9cfafd4ef43230651d6144b78d7bd78bba440934d0ee69c3408ebafb8786f03`。两包生产源码一致，仅History harness在测试包构建前加强SQL4前置、允许共享反向导航回到原[C,D] frame的源1或2；未改变未paint及SQL核心断言，原正确红仍满足该前置，不追加debug构建。

首次instrument命令遗漏 `.viewer` 包段，4个initializationError、0业务用例，单独保存 `sp-b-android-art-source-progress-runner-error.log`，不计产品红。更正FQN后，同两包的 `sp-b-android-art-source-progress` 自然终态43.885秒、OK (9 tests)，主代理独读终态及三类production修复：Photo旧图0prepare/ACK、新请求release后实际GREEN绘制；history旧holder卸载且新窗口未paint期间SQL事件不增加，release后实际C/D再读；四种输入先定位未画D且不计D、连续请求可到E；原Stable3的portrait追加、wide完整下一屏和逐槽error/真实Retry全部通过。确认跳过未显示伙伴、历史未画先计读及旧动画图借新请求资格的问题已修复。只是Android B窄里程碑，非整个SP01完成。

既定真实原图前置已完成：原专用 `emulator-5586` 复核AVD名 `mihon-pairing-api36` 后，暂存 `/data/local/tmp/udp-art-native/real-b1-original.cbz`（archive6352412B）与 `real-b2-original.cbz`（archive11803790B），设备端SHA分别与既有本机 `fec7ffef2dc6773c0c917b5e9961fff745cca5eb9eaccc82deee2dffe26b522b`、`760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439` 相同。此前6350792B/11801744B是17/22张encoded图像payload合计，不是ZIP总大小。本步骤只暂存已有输入，未触发应用、用户输入或并发编译，也不是原图呈现通过。

接续的 `sp-b-android-holder-progress-focused` 限定共享stable/session及既有AndroidWiring、ProgressProduction、HolderLayout类，串行max-workers=1、focused禁重试；共享任务UP-TO-DATE，沿用20:54Z的32＋28有效结果，不冒称fresh。app任务仍在实际逐项执行，若干旧dual夹具仅手调onImageLoaded却等待actual进度，每项有界超时；另有Single旧诊断反射已移除的issuedViewport字段。正在保留失败XML、区分夹具与真实回归，未提前记整批结果。终态后只适配受影响夹具为实际production child绘制，先两代表方法再同类复验；Robolectric证据不替代上述JNI/ART。B还须关闭真实邻屏曝光、显式viewport/settings及内容换版生命周期、D-first/UNKNOWN、B1/B2与EXIF补证，然后才进入C。

上述限定批次自然FAILED（23:17:39Z，7分27秒）：ProgressProduction33项/25失败、HolderLayout47项/15失败、AndroidWiring12项/6失败，共92项/46失败、0 errors/skips；原XML归档于 `.gradle-coordinator/sp-b-android-holder-progress-focused-xml/`。主代理独读失败列表，未把它们统一归为产品回归：旧夹具只发加载回调而未实际绘制、私有诊断字段变化及旧整组进度/未知页分组预期，需要按v3逐项适配；可见内容换版进入 `PairingStore.ensure` 后直接 `rebindContent` 抛异常则保留为S14真实失败。旧fact身份保护不能通过替换分组枚举断言丢失，后续须直接验证旧请求被拒及对应源页事实不变。

实际绘制夹具继续使用原encoded Bitmap、原SSIV和 `Canvas.draw`，由production child的真实draw pass产生prepare/ACK，不手工调用共享drawn。初次缺回执经flags和可见矩形定位为测试窗口小于声明视口，图片落在可见区外；修正Robolectric真实窗口尺寸，并正常消费原主线程post，未关闭可见性检查、改产品解码或引入固定等待帧。`sp-b-android-paint-fixture-green` 自然PASSED/26秒，两代表用例全绿（新XML23:28:28Z）。这只验证JVM生产接线，不替代真实ART/JNI解码。

`sp-b-android-progress-paint-regression` 自然FAILED（23:31:54Z，2分18秒），33项/10失败；XML保存为 `.gradle-coordinator/sp-b-android-progress-paint-regression.xml`。其中7项夹具向Single FULL传RIGHT而导致实际图片尺寸为0，2项仍要求旧整组进度门禁，1项保留为可见lease异常。最小测试适配通过反射调用原production `pageSides(displayPage)` 获取实际槽位，不复制共享规则；两partial用例保留原SQL、章末和activation保护，改为首张实际绘制→伙伴Retry后实际追加。`sp-b-android-paint-slot-focused` 自然PASSED（23:36:23Z，1分07秒），4个进度方法及1个原B2尺寸夹具方法共5项全绿。最后一项是尺寸CSV生成图的真实槽位接线，不能当作B2原图呈现通过。S14及其余B边界尚未关闭，未提交、未勾选SP01，未启动C或最终全量。

S14新增三项VM/holder测试拟走实际pager测量、原裁边偏好和原encoded snapshot身份更新。首 `sp-b-android-context-red` 自然FAILED（23:45:41Z，38秒），仅测试缺少 `Injekt.get` 扩展导入，0业务执行，不计正确红。修正测试入口后，`sp-b-android-context-red2` 自然FAILED（23:51:27Z，40秒），3项/3失败；XML保存为 `.gradle-coordinator/sp-b-android-context-red2.xml`，主代理独读。resize后仍能取得旧context0的locked/drawn frame；内容换版的owner变null，suppressed异常指向 `SharedDualPageSession.rebindContent → PairingStore.ensure` 的可见lease检查，确认这两项真实行为红。crop也观察到旧frame，但后续核出偏好事件没有到达config，不能算第三项正确产品红，见下段更正。下一原生租约测试继续使用既有0d4d开发宿主，生产尚未改变；它将核旧pixels、非默认zoom与挂载身份，不能仅以Store不抛认定修复。Ready页没有公开强制网络reload入口，底层header-retry序列与用户错误槽Retry的证据明确区分，不为测试扩展下载能力。

仅仪器包构建的首入口因可空 `fixture.images` 缺少解包而编译失败，0业务执行；最小修测试后 `sp-b-android-content-lease-red-test-apk2` 自然PASSED（23:53:31Z，29秒），仪器SHA `f1ebf2fb75b2bfe99731e79c0b5491b95d53c15c6e452e6355e1e75a7c0b21e1`，生产仍为原dev 0d4d。`sp-b-android-art-content-lease-red` 的 `numtests=2` 只表示计划数，实际仅开始content-leave第1项：真实HTTP C/D已画、SQL2和2倍zoom前置通过，后台stream新snapshot后原ART进程在 `DualPageViewerAdapter` fact collector → `PairingStore.ensure` → `SharedDualPageSession.rebindContent` 抛可见lease异常而崩溃，runner终态 `shortMsg=Process crashed`。主代理独读完整异常链，确认真实产品红；header-retry向未执行，后续像素保持及离屏换版断言也未执行，不把它们记为已失败或通过。继续同一S14最小修复，无在途ART/Gradle或正式构建。

首最小修后的 `sp-b-android-context-green` 自然FAILED（23:57:30Z，54秒），3项/1失败：resize和换版保持原owner/native尺寸通过，crop仍旧frame。主代理与实施者核当前夹具的 `InMemoryPreferenceStore.getBoolean` 每次生成新Preference对象，测试的 `cropBorders().set(true)` 未进入PagerConfig原注册flow；因此更正此前crop红因，不能据旧frame单独认定产品故障。该用例改用原Android偏好存储，并先断言config实际发生变化；不改通用内存偏好实现。原生租约尚未绿，整体S14仍未签收；另已登记换版保留旧图后再resize的组合及离屏补载去重风险，按同一S14范围验证。

真实crop事件红 `sp-b-android-crop-real-event-red` 自然FAILED（2026-10-04T00:00:12Z，35秒），1项：原Android偏好存储实际使 `config.imageCropBorders=true`，等价旧refresh链虽然detach撤销旧票据，新票据context仍0，准确失败在context未更新。`sp-b-android-lease-geometry-red` 自然FAILED（00:01:58Z，24秒），1项：可见旧bytes保留后真实resize，原SSIV ready/loaded/shown/visible均为true，但prepare计数0，证明旧lease资格错误依赖已清空的本context绘制集合。两项原XML按key归档；不借编译或缺事件记红。

最小实现使用独立可见内容lease跨geometry保持原回调资格，当前context的rendered集合仍重新收集；离屏补载按页去重、detach取消并核attachment。设置沿原Buffer快照重配图片，保持holder和旧bytes；主题不更新几何context。`sp-b-android-context-green2` 自然PASSED（00:06:18Z，54秒），Progress4＋HolderLayout1共5项/0 failures/errors/skips，包含bytes换版→resize→原child真实绘制、crop/resize、未显示的Single换版身份及crop region文件生命周期。

共享Store独审另要求保护原Single已显示后的换版：`sp-b-android-single-loaded-replacement-red` 自然FAILED（00:10:30Z，46秒），1项，首次原onLoaded计读已成功，新bytes仍复用旧content owner为正确红。窄修在原Single替图入口先释放旧scope，再Store正常rebind；不把双页可见旧lease规则扩为Single新产品行为，不改变Single原onLoaded进度入口，也不声称它是Single真实像素证据。`sp-b-android-context-single-green`（00:12:28Z，1分07秒）6项/1失败，其余5通过；失败是首次显示helper错误要求已计读同seq的新版再次 `readingEligible=true`。新owner、旧frame撤销及新encoded身份已通过；只修helper的二次资格预期，保drawn及SQL1断言、不造新导航。`sp-b-android-single-loaded-replacement-green` 自然PASSED（00:14:48Z，25秒），1项。production没有再变，最终同production的6项有效绿可合并复用。

格式首轮 `sp-b-android-content-context-format` 因Holder一处超过120字长行FAILED（00:07:59Z，29秒），0业务测试；修格式后 `sp-b-android-content-context-format2` 自然PASSED（00:16:10Z，17秒）。统一debug构建PASSED（00:16:49Z，29秒），开发宿主SHA `8128954ec2d40ff78cb12fd71fbedd414fe46672413322560be64f209159e30a`；testAPK构建PASSED（00:17:25Z，20秒），仪器SHA `32f790c9a2a502c368cf09c87bc267e68d7d94be5a9d887822fb19d06b0b2c03`，同冻结源码及原debug证书。debug文件为 `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-586468fcffd3/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`，主代理核存在并独算SHA一致；不作为正式交付包。

同两包的 `sp-b-android-art-content-context` 自然终态59.063秒、OK (11 tests)。主代理独读全部11个完成CODE0、0 failures：Content2、History1、Nav4、Stable3、SingleGesture1。内容向先真实HTTP C/D像素→SQL2→2倍zoom，新stream/header后12次实际PixelCopy的RED完整Rect、mount/container、scale/pan及SQL均不变；真实离屏再进入后新MAGENTA宽图FULL，header-retry向先保旧再沿原显式底层序列放行。后一向不是新增Ready网络UI或用户错误槽Retry证明。其余导航/逐槽错误Retry/宽图下一屏/未paint不计读/原Single手势继续通过，确认可见换版崩溃及换版后geometry回调失效已修复。主代理签收此窄S14及受影响Single接线里程碑；B剩余D-first/UNKNOWN、真实拖动迟到信息、原B1/B2和JNI EXIF、Desktop原图/迟到EXIF，以及C仍未完成，SP01/DP02保持未勾选。

Exposure新增三项使用同一production `8128954…`，只重建测试包。`sp-b-android-exposure-red-test-apk` 自然PASSED（00:36:31Z，24秒），testSHA `77394ef639c29e7732597f5139fb1c1259ca9a23502d5bb2482b91c7df6d1068`。`sp-b-android-art-exposure-red` 自然终态41.689秒，3项/2失败，主代理独读全部终态与测试事件。反向从已画后组经真实key进入目标，前页原HTTP仍UNKNOWN，目标RIGHT实际像素及前页后来wide后12次Rect/mount/逻辑目标不变全部通过。D-first用真实SSIV公开executor暂扣原JNI base任务，metadata正常且sWidth400、baseLoaded=false、queuedTask非空；D已实际LEFT并记录SQL2，C无RED像素，准确失败在C RIGHT加载反馈缺失，后续key/释放任务/累计回执尚未执行，不作通过。held-neighbor项则在E首像素曝光前置超时，邻holder尚无native child、正常HTTP仅C/D（D重复一次）；尚未放行F，因此不能记作F迟到移动画面的正确红。保留原失败日志，先核真实触摸与正常调度入口。SP01仍未完成。

治理检查同步把capability43当前production标记由未被消费的旧 `DualPageSettledVisiblePageReporter` 声明改为 `rememberPagerNativePresentation`、逐槽prepare/drawn及原model结算链。标记只承担治理定位，不替代真实行为/像素测试；该治理类与架构guard的运行留到SP01末受影响批次验证，manifest须依据最终有效证据更新，不提前把正式S17记绿。

Exposure Loading窄修把提示移除从setImage绑定结束移到本槽原child实际绘制且shared ACK成功，不新增呈现等待。格式首轮31秒仅Root治理文件CRLF/长行失败，后续配置顺序错误1秒及同文件反复max-line-length均为0业务测试；停止猜行，Root接回该文件最终格式责任。预格式13秒只作定位，两处猜测的常量分行已撤销，保原治理marker＋LF，最终SP01必须包括其正常Check。`sp-b-android-exposure-loading-format7` 暂只限定受影响产品/原生测试（治理文件未在本次清单）自然PASSED13秒，00:54:37Z；不以此宣称完整格式已过。统一debug自然PASSED（00:55:15Z，18秒），宿主SHA `929787d060dd4b53dee330fd438297667d691db37bd4ae09662a44379ab2ebb4`，开发文件为 `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-6b5081b50ee5/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`；testAPK自然PASSED14秒，testSHA `b4fb6291502df0f0981988a768bd5fa382773f4eae756851d0aa8074d2687d27`。

同两包 `sp-b-android-art-exposure-loading` 40.993秒、3项/2失败：D-first完整CODE0，执行C原JNI base held、D实际LEFT/SQL2、C RIGHT Loading、真实key优先C且D几何不变、释放原JNI后C实际首画及累计不回退；held新增诊断反射错误（Pager继承DirectionalViewPager，不能从错误ViewPager class取mScrollState），尚未到伙伴迟到；reverse则初始未来伙伴源4未就绪，仅源3实际BLUE/SQL3、HTTP3＋5，尚未执行反向。因此不把后两项当新产品红。测试前置窄修沿真实class层级读状态，反向初始只要求实际源3＋SQL3，保目标源2/UNKNOWN/RIGHT/迟到wide所有断言；production9297未变，不重建debug。

测试包 `sp-b-android-exposure-diagnostic-test-apk` 自然PASSED23秒，testSHA `1404c2d021661d57f27acf3121196228d286149c875025d730623335f1255bf0`。`sp-b-android-art-exposure-diagnostic` 11.646秒、3项/2失败：reverse核心完整通过；held真实触摸已使E BLUE像素曝光、原frame/逻辑C/SQL基线成立，F正常请求随后实际Ready/SPREAD，准确业务红在scrollX由-638变0（line244，后续Rect/frame断言尚未执行）。D-first同production在RIGHT Loading断言再次失败；其全树firstVisible观测尚未限定源1槽，故此前单次完整绿只保为该次结果，不声称反馈持续签收。先修观测按当前源/槽取得真实可见提示，再判剩余产品风险。拟沿原gesture协议延后adapter结构通知/定位，事实与曝光锁定照常，idle按原source导航消费最新投影，不以scrollTo掩盖重组。B仍未完成。

本轮Mac前置只读核验（08:47北京时间）：原隔离缓存checkout仍HEAD `84d98c7176e7c26022f959066d432acd32406733`，仅AppVersion.kt有未提交改动，macOS14.8.4及既有Temurin21路径可用。未复制源码、启动构建或操作日常实例；DP02使用新的隔离checkout，保护这份旧改动。

最小手势协议在 `hasActiveGesture` 且无显式源目标时只延后adapter结构投影；原事实及曝光锁定正常，idle先消费原源页导航cause，再发布最新pending投影，未用scrollTo恢复外观。Loading新绑定先设置本槽提示，覆盖StateFlow首次收集已经Ready的路径；可见旧lease早退不新挂提示。`sp-b-android-exposure-held-format` 自然PASSED（01:06:09Z，15秒），仍未包含Root治理文件的最终格式；统一debug自然PASSED（01:06:59Z，14秒），devSHA `2c27b96fe5c0036bad8be1f35924256231bf51e48b11aaebe3abae7bdca56c96`，开发文件 `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-fa20ee703dce/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`。testAPK自然PASSED（01:07:40Z，15秒），testSHA `89b47cebc3f1c1ebdd2ef83e1372f92dcd92bcce87cc1562424e3a6634917403`。

`sp-b-android-art-exposure-held-green` 自然终态45.389秒、3项/1失败：D-first及reverse完整CODE0；held晚F事实之后12次offset、实际完整Rect、PixelCopy内容、mount/container、scale/pan、frame与SQL全部不变，旧-638→0红已关闭。新增真实UP后E SQL3断言失败，最终回到C；随后独读实际DirectionalViewPager1.0.0 `determineTargetPage` 字节码，低velocity前页使用0.6截断偏置，原638/1080=0.591未跨既有释放阈值，符合回原页行为。这是已证实的手势前置不足，不记作源导航新产品红。保留失败、原几何日志及字节码过程证据；生产2c27不变，只使已完成held比较后继续实际MOVE到0.93W再UP，不调用scrollTo、源导航或伪造cause。

`sp-b-android-exposure-release-test-apk` 自然PASSED（01:13:15Z，23秒），testSHA `d3557e89b0ebe9644c7e1881c563856621acd08991715cd5a9044a5ce7b275a1`。`sp-b-android-art-exposure-release` 自然终态15.018秒、OK (3 tests)，全部CODE0。主代理独读原代码事件及终态：held F实际Ready/SPREAD后12次x=-638、完整View Rect(98,795–638,1605)、pixels(100,796–637,1605)、同mount/container、scale/pan、frame serial3、逻辑C及SQL(2,115)保持；原MOVE继续到x=-800、ItemInfo position10/offset-1/width1，原UP后E实际SQL3，再原key到F MAGENTA宽图，实际内容宽度超过视口75%且SQL4。完整几何过程见 `.gradle-coordinator/sp-b-android-art-exposure-release-geometry.log`，PID14568/01:13:51–52。D-first前图JNI解码held时后图LEFT、前图RIGHT Loading、源页导航与累计实际回执全部通过，反向UNKNOWN条件与前页晚wide零移动也通过。确认伙伴迟到导致真实held拖动复位及Ready初收缺Loading的问题已修复；主代理签收此B窄里程碑，不表示B或SP01完成。

同两APK的SourceNavigation4受影响复验首次应用启动在ExtensionManager→NotificationManager取消通知时发生包/UID不匹配SecurityException（Chrome包），0业务启动；完整原日志 `.gradle-coordinator/sp-b-android-art-exposure-source-regression.log` 保留，不归因手势改动，也不更改无关通知代码。仅专用AVD本dev实例force-stop后经原MainActivity正常初始化，同APK单次复验 `.gradle-coordinator/sp-b-android-art-exposure-source-regression2.log` 自然终态15.942秒、OK (4 tests)，key/tap/wheel/drag全部CODE0；根因仍未证实，不以重启成功作因果结论。主代理另只读取得AVD实际安装base.apk SHA，与上述本地dev2c27完全一致；本地testAPK SHA亦为d355，非仅文件名推断。未递增版本、未正式构建、未提前全量。

下一B工作使用既有原CBZ提取的17/22张原bytes，进入原Manhuagui/Copy扩展HTTP→正常loader→JNI→真实呈现及SQL，保原1页/22页夹具；原B1/B2条件槽位、JNI EXIF、Desktop原图/晚EXIF及旧focused适配尚欠。Loading受影响Native Error一项与下一原图批合并，避免无关重复Gate/Photo。C未启动；治理文件正常格式检查仍属SP01最终批次必做项。

治理格式处理的范围更正：主代理用本机cached ktlint1.8.0 CLI（独立格式诊断、384MiB上限，无Gradle/网络依赖下载）确认实文件采用项目120/intellij配置，但该既有整类包含大量需重排的旧行；直接格式化会涉及上千行及字面量正规化，已恢复诊断前本任务文件，不接纳未证实保语义的输出。为避免把额外marker改动扩大为旧治理文件重写，撤销本轮9行marker变更；01:35Z核该文件git diff/status均为空、index无暂存，保原文件。此前“最终需格式检查该新增改动”的安排随新增改动撤销，不再强行重格式无改动旧类；项目正常spotlessCheck和本任务实际修改代码的格式要求不变，该治理类与架构guard仍纳入SP01末受影响验证。新生产调用链、实际行为方法及日志登记parity manifest与本报告，旧未消费helper的声明不能作本轮行为通过证据。未改变任何S01–S17必做验收，也未新建formatter框架或取消项目规则。

### B Android 原图 JNI 开发证据（2026-10-04，尚非正式 S17）

本节 production 始终是上述开发宿主 SHA `2c27b96fe5c0036bad8be1f35924256231bf51e48b11aaebe3abae7bdca56c96`，没有产品修改、版本递增或正式构建。`HostReaderArtFixture` 只解压已核 SHA 的原 B1/B2 archive，保留 17 张 WebP／22 张 JPEG 原 bytes 和 MIME，进入已安装原扩展的 HTTP 解析、普通 loader、原 JNI SSIV 与真实窗口 PixelCopy；不注入事实、分组或实际绘制回执。B2 原 `words` 有乱序，原 parser 会按源身份排序；夹具的 original 分支据此把源页与 HTTP 图片路径一一映射，保留原响应形状和 parser，旧模拟图片分支不变。

| 原日志 key（均在 `.gradle-coordinator/`） | 实际终态及判别 |
|---|---|
| `sp-b-android-art-original-images` | 188.993秒，6项／5失败；晚到开篇完整绿。Copy 原图按 contents 物理位置路由错误，源20实际收到源12／22 bytes；相关前置失败不算产品红。其余像素超时继续诊断。初始 testSHA `84abe52931b732a02e306fef876f2d6a24fc6d3b3e3999f4eeec497c51658854`。 |
| `sp-b-android-art-original-images-diagnostic` | 98.129秒，3项／3失败，testSHA `99742dcb76e0716b4d9d5a74f888dc8a8d899a52a368336014de9da1eb294e6d`。修源映射后原尺寸／实际槽／JNI base 已成立；B1源17及B2源20仅18/25单点匹配，原高频边缘颜色经缩小滤波改变。开篇新 Dual owner 的真实邻页事实／未锁前置已过，导航提示把左侧像素染色。均为观测／提示前置，不据此改产品。 |
| `sp-b-android-art-original-images-observer` | 99.749秒，6项／2失败，testSHA `d690503b9061927969c0a86023a8cadb850a0323b0fde825aaf26f2f9d0ab461`；B1冷跳／反向、B1全17顺读／暖回翻、B2晚开篇及晚源20四项完整绿。两项早结构仍在提示遮挡前置，不能签首帧。有效进程 PID16542，原 geometry 日志保留旧进程片段，不能混入本轮证据。 |
| `sp-b-android-art-original-images-hint` | 84.889秒，3项／2失败，testSHA `2f7f16dba83d4a28e68837740118e4cceb60beb4e012f0f0c13f4d3eeba13bf3`；B1首15实际像素前的透明观察完整绿：PID17111／02:08:01.216Z，原 prepare 成功、源15尚未 drawn 时，实际事实 `[PORTRAIT,PORTRAIT]`、组合 `[14,15]`、普通 Ready `[true,true]`，原回调返回值不变；全17顺读和暖15/16回翻仍绿。两项 known 尚未完成真实提示关闭。 |
| `sp-b-android-art-original-images-hint-final` | 70.789秒，2项／2失败，testSHA `0f78b4eb5d89b0cd193247fe5bb7628b63d49b2082ada683fcbfc6f1a4831340`。PID17499／02:10:24.496Z观察旧提示 hidden，.617Z新 holder 加载，.752Z新提示才染色；已证实测试把“尚未显示”当作“关闭完成”的生命周期前置竞态，不是产品呈现红。 |
| `sp-b-android-art-original-images-mounted-hint` | 16.846秒，OK(2 tests)，全部CODE0，testSHA `4b85bb451371e9c5ceffa4e830db9d82f37efd392ce89fdba525173419580185`；先等新提示真实 shown，再经原公开 click 有界重试并观察 hidden。新 Dual owner 首 preDraw 真实邻事实／未锁前置、原图实际LEFT／FULL、12次几何及身份稳定均过。首实际 prepare 的 context 连续性证据仍待下项补齐，不凭最终 slot 代替。 |

观察器维持 5×5 空间网格，原图内每点最多偏移24源像素、只依原图梯度挑稳定点，不参考截图选择；真实窗口仍须至少20/25点匹配。原参考及实际同25空间点的 RGB 最大跨度须超过70，均匀任意颜色因实际跨度0不能通过；不硬凑暗点而拒绝浅色广告。首轮错源图已被尺寸／原参考联合校验拒绝。检查同时约束真实 View.page、原图尺寸、完整内容矩形、mount／container、scale／pan，保 original bytes 身份。原39张参考预检只用于观察器有效性，不作为产品呈现证据。

主代理独读 runner 终态、透明回调代码及 geometry，并独算 testSHA `d690…`、`2f7…`、`4b85…` 一致。当前已执行范围：B1冷跳源17仍RIGHT，真实反向到未画源16且源15UNKNOWN，晚源15到达不移动，再真实返回17；B1正常顺读实际覆盖全部17源页，及时15/16在首15像素前成立，暖回翻仍为原组合；B2源2／20晚结构首RIGHT且真实邻宽图到达后不移动，原key到宽图3／21 FULL及广告22可达。早结构使用真实 Single 正常读邻页／目标后经既有 mode consumer 创建新 Dual owner，首 preDraw 验证新 owner 事实，旧缓存本身不算已知证据。PID17791／02:13的 source20实际Rect `(0,437)–(1080,1963)`、source2实际Rect `(0,819)–(540,1581)` 与各自原 mount／container、scale1、pan0在12次观察中一致，见 `sp-b-android-art-original-images-mounted-hint-geometry.log`。

格式／测试包中间命令只承担编译前置；hint前两次格式失败0业务，透明观察降嵌套并分行后 `hint-test-apk3` 自然PASSED24秒。未安装／未运行的 observer中间包不记业务绿。所有旧失败及日志保留，不放宽原25/20阈值或重写生产逻辑获得通过。当前S09/S10原图行为范围可复用，S03早结构首次实际prepare的slot／context追加证据、JNI EXIF24／受影响Error、Desktop原图／晚EXIF、旧focused适配与C仍待完成；SP01、DP02均未勾选，未提交。

S03首实际绘制补证随后关闭：`sp-b-android-original-images-first-context-test-apk` 定向格式及testAPK自然PASSED，testSHA `1fa0cf8c23188289a5bb5b8925ca824552f9d5a0e1383bcc6e988bca6b055c22`；production仍2c27。`sp-b-android-art-original-images-first-context` 实际Time16.932秒、OK(2 tests)，全部CODE0。主代理独算testSHA并独读原终态／geometry：PID18077／02:18:44.096Z源20首actual prepare为FULL、邻事实 `[SPREAD,SPREAD]`，02:18:52.088Z源2为LEFT、邻事实 `[PORTRAIT,SPREAD]`；原 callback 成功后透明观察且返回值不变，目标仍 `wasDrawn=false`。预挂载票据context2／serial3与首actual票据context3／serial6不同，明确原启动设置／geometry变化发生在首pixel之前；两项实际窗口原图及12次稳定观察后frameId仍context3／serial6。不能用预挂载票据等同实际首帧，但首实际slot／facts及后续票据一致的断言已过，排除了最终新context掩盖首次错误slot。原六项及B1及时15/16证据可合并复用，主代理签收本节S09/S10与S03窄Android原图里程碑；仍非正式R8/S17或整个B完成。下一项仅同两包JNI EXIF24与受影响Error，不泛跑无关Native Gate／Photo；Desktop原图／晚EXIF、旧focused与C仍未完成。

同两包原 JNI／反馈受影响回归已关闭：`sp-b-android-art-exif-native` 原 runner Time91.689秒、OK(24 tests)、24个CODE0；`SharedReaderViewNativeAcceptanceTest` 经原 Activity／Single holder／SSIV，实际EXIF1–8×三种split／invert／crop／fit设置，variant0含真实双击放大和拖动平移，原golden角点进入PixelCopy。该范围保护共用原图片View／decoder及原Single行为，不把它说成24项独立Dual分组证据。`sp-b-android-art-loading-error-regression` 精确选择 `SharedDualPageStableNativeAcceptanceTest#actualPartialPixelsStayMountedThroughPartnerCompletionAndRetry[late-error]`，numtests=1，Time5.824秒、OK(1 test)、CODE0，原C实画／SQL→D LEFT独立Loading→原zoom→完整槽内Error／Retry→实际触摸重试→D像素／SQL追加全部过；未重复无关Gate／Photo。主代理独读终态及实际测试入口，签收Android B原图、JNI与反馈窄开发里程碑。当前无ART／Gradle进程，专用AVD保留原实例；Desktop原图与Ready先画／原EXIF晚到、旧受影响测试适配仍待完成，B整体未关，C未启动，无提交或正式验收。


### B Desktop Ready先画／原EXIF晚到（2026-10-04，开发窄里程碑）

`SharedDualPageLateExifProductionTest` 从原 runtime factory、MockWebServer 原编码EXIF6图片、同一个 content coordinator／encoded store 到 Skia／真实 ReaderViewport 离屏绘制；仅在原 coordinator 的 ContentOpenPort 加透明有界header门控，转交原open并最终恢复，不替换解析器、decoder、事实、frame或绘制回执。首图正常full请求不等待header。主代理独读实际入口及原XML：gate前真实源1 FIRST_PAGE_PRESENTED及彩色像素成立，gate后原header返回并实际HEADER_READ。正确红 expected CLOCKWISE_90、实际NORMAL，原因是原decode分类通过requestFact签新header能力，覆盖尚在途原能力。早期MockK泛型／suspend callOriginal异常与虚拟调度前置超时没有到产品断言，不算产品红；超时仅停止该协调器记录进程树，无全局Java清理。

最小修复：共享session提供acceptDecodedFact，以有效当前viewport、同版内容及规范未裁边实际尺寸分类，不签发或消费原header能力；Desktop原prepare调用该接口。原header迟到后正常确认方向，旧sample能力拒绝、新能力使用CLOCKWISE_90，首frame、mount及真实像素完整矩形不变。region session及原DesktopReaderSpreadSampler从真实更新身份取得方向，四角实际采样蓝／黄／红／绿均在RGB差30内。该采样检查不冒充C可选调度验收。原内容lease最终全部释放。

| 协调器 key／归档证据 | 实际结果 |
|---|---|
| `sp-b-desktop-late-original-exif-red4`／同名XML | 25秒自然FAILED，02:40:59.233Z，1项／1失败；上述真实首像素及原header返回前置全部通过，方向丢失为正确产品红。 |
| `sp-b-decoded-fact-contract-red`／同名XML | 16秒自然FAILED，02:43:53Z，Session29项／1失败，原请求被decode分类撤销；其余28过。先执行旧真实组合的业务红，再新增接口，不以缺符号编译失败代替。 |
| `sp-b-decoded-fact-green` | 30秒自然FAILED，共享62项／1失败；fixture把raw160×100当作规范effective尺寸，误标wide导致未画伙伴剔除。按原API契约改为100×160＋CLOCKWISE_90，保留完整frame等值断言，不放宽产品条件；该轮Desktop未执行。 |
| `sp-b-decoded-fact-green2` | 71秒自然PASSED，限定四文件格式通过；Session30＋Stable32、DesktopLateExif1＋NativeStable17＋SpreadSampler3，共83项，全部无失败／错误／跳过。主代理独读XML：共享02:50:43Z，Desktop02:51:04–13Z。 |
| `sp-b-decoded-fact-refactor-android` | 71秒自然FAILED；只重构测试采点，Desktop1 fresh02:52:51.017Z全绿；共享JVM62 UP-TO-DATE复用上轮有效结果。错误选择app Android目标，0 Android业务，不归为产品失败。 |
| `sp-b-decoded-fact-android-green` | 9秒自然PASSED，纠正为`:domain:testReleaseUnitTest`，仅两个完整FQN；Session30＋Stable32 fresh02:54:52.063/.166Z，62项无失败／错误／跳过。主代理独读XML及协调器终态。 |

共享新增契约还覆盖实际draw后的late方向保持frame、原header幂等、旧sample失效／新sample正确、非法尺寸、frame之外页、撤销context、换版及旧定位decode拒绝。新定位后旧locked frame仍保护原像素；原owner本来就拒绝旧未锁伙伴prepare，接口没有扩大旧权限。主代理完成本次相关共享接口与真实Desktop链定向独审，签收该窄bug修复；B仍欠Desktop原B1/B2与旧受影响测试适配，C未启动。SP01／DP02未勾选，无提交、全量测试或正式构建。


Desktop原B1/B2首次限定验证 `sp-b-desktop-original-native` 自然FAILED44秒，XML时间03:03:23.785Z，6项／2失败／0错误／0跳过，限定格式通过。两ZIP SHA及17／22张原bytes经真实CBZ/runtime/Skia/ReaderViewport；B1及时15/16与源17可达、B2早源2LEFT、早源20FULL、晚源20RIGHT→宽21FULL→22可达四项仅完成本轮终态像素条件，不提前签S03/S12。B1冷17首目标像素已有，后续等待源页index14／15事实超时；普通backward1不会请求index14（源15），不能为测试增加事实IO。B2冷2最终LEFT，宽伙伴3可由真实decode先贡献类型，单独延迟header没有保证首画时邻事实未知；尚不判产品红，不把期望改LEFT吞冷条件。

主代理本段独审发现两处证据不足：轮询最终正确slot不证明第一次实际绘制的slot/context；按slot和原图尺寸计算的Fit矩形不证明真实像素没有移动。下一次内聚test-only修正须透明观察原model同对象prepare成功、保持原返回值和真实draw，记录firstprepare邻UNKNOWN／已知及frame/slot，再以实际截图内容边界测几何；保20/25匹配和RGB非均匀跨度>70，不放宽已有阈值。冷B1只等待普通预取真实覆盖的index15（源16）；冷B2可有界延迟原伙伴decoder，释放后仍交原Skia。产品不为前置修改，六条件及原失败日志保留，当前B／SP01均未签收。


`sp-b-desktop-original-native-first-paint` 自然FAILED28秒，XML03:12:21.719Z，6项／3失败／0错误／0跳过，限定格式通过。同model对象的非挂起prepare调用原方法，成功才观察且保持原返回值，无第二model/owner；目标firstprepare与actualpaint frame相同、原25点阈值保留。B1冷17及两个B2 known 的首次条件通过；B1首17的邻index15为UNKNOWN，原晚源16头406396B、peek1127×1600；B2冷2首RIGHT、邻index0／2均UNKNOWN，原header与伙伴decode门控释放后两B2在stableAfterHeaders的observe为空，slot/frame/pixels原因待实际诊断，不能提前判位移或产品红。

本轮B1及时原15/16走真实factory/runtime发生 `The anchor page must be visible`，堆栈为实际partial settle→model→factory.onViewportSettled→DesktopReaderSession→ReaderSessionCore.settleViewport。主代理独读production：model逻辑target C不在仅D的实际draw集合，却把C当core anchor；core要求anchor属于actual visible集合。已确认该运行时边界遗漏，旧C-first SQL单项并未覆盖D-first，下一项先补真实D-first／SQL业务红；不得把未画C加入actual集合、强画C、吞异常或放宽core断言。拟仅平台model→runtime选实际anchor，保逻辑C、原C加载及next未画导航，验证后来C追加去重/max不回退与runtime snapshot。

新的非背景bbox仍有观测污染：ReaderState.showUI默认false，fixture.toggleUI把它设true，ReaderViewport实际画TopAppBar／Settings／BottomBar；不同RIGHT原图bbox同为[350,0,681,399]，与参考Fit右沿约631／633不一致，不能据此签纯漫画零位移。移除该fixture toggle后重验受影响六条件；本轮3绿仅保首prepare／原图点匹配窄证据，不将含工具栏bbox作S12绿。旧日志与失败保留，真实runtime bug与观测前置分别修复。无提交／全量／正式构建。


Desktop后图先画的正确红／修复与原图呈现映射随后按同一批次关闭：

| 协调器 key（原XML同目录归档） | 实际终态及限制 |
|---|---|
| `sp-b-partner-first-runtime-red`／`red2` | 参数路径截断及两条新增长行格式失败，分别2／11秒、0业务；不是产品红。纠正原argv整段引号／窄分行，未改变产品。 |
| `sp-b-partner-first-runtime-red3` | 自然FAILED29秒；Runtime2项／1正确失败，03:25:35.655Z，原C decoder held、真实D画出后进入core anchor断言；旧C-first通过。Original6项／1失败，03:25:26.024Z，冷B2晚结构后target/history仍C RIGHT，native却为zeroRect邻wide。toolbar已移除，真实像素边界不再包含681处控件。 |
| `sp-b-partner-first-runtime-green` | 自然终态约36秒；最小实际anchor修复后Runtime2 fresh03:28:28.686Z全绿。Original6／2失败；诊断额外render的PNG C完好，不能据单次语义nil就称真实像素空白。 |
| `sp-b-partner-first-runtime-refactor` | 35秒，Runtime2仍绿、Original6／2失败。观察器改为单次render→同帧semantics／Bitmap，12帧仍硬断言，无await／跳帧；修正快照一致性仍不能解决selected native。 |
| `sp-b-original-observation-diagnostic` | 自然FAILED约23秒，Original6／2；精确早退为missing selected native。RTL units count随晚宽图拆组变化，旧currentPage物理index选择邻wide而原可见C key不变；实际C slot／mount／额外PNG仍在，确认选中presenter映射错误，不扩大结论为已证实像素blank。 |
| `sp-b-visible-key-native-green` | 自然PASSED约31秒；Original6 fresh03:34:47.407Z＋Runtime2 fresh03:34:55.823Z，共8项无失败／错误／跳过，四文件限定格式通过。 |
| `sp-b-desktop-original-refactor` | 自然PASSED约40秒，终态03:36:32.748Z；Original6@03:36:07.605Z＋Runtime2@03:36:15.949Z＋PageTurn40@03:36:17.149Z＋NativeStable17@03:36:25.595Z，共65项fresh、无失败／错误／跳过；限定格式及diffcheck通过。主代理独读四份XML与协调器终态。 |

生产修改仅限既有接口：model提交进度时target在actual集合则用target，否则选实际已画页作core anchor；UI逻辑target保持C，core visibility断言不变。Runtime新D-first用例真实factory／Skia／Compose→tracker→SQL，首D写last2、C原decoder仍在途、UI逻辑1／runtime原context1不跳页、实际next tap仍处理未画C，释放后C实际画出并Ready，append最大值保持2，20次idle重复绘制reading_events数量恒定。不会把未画C当作actual；原C-first事件数及部分→完整追加也通过。确认后图先画导致真实runtime anchor断言的bug已修复。

双页pager复用原stable unitKey；非滚动时取实际visiblePagesInfo最靠近viewport的原key映射当前units，滚动保原currentPage，idle仍等实际settledPage与原programmatic／navigation cause。未scrollTo、伪导航或改owner/frame/geometry；真实原图12连续帧仍须25空间点至少20匹配、RGB跨度>70、同frame/mount及真实非背景bbox，未await掩盖临时失败。原图firstprepare调用原model同对象真实方法且保返回值；B2冷2首源2RIGHT时cover事实可以PORTRAIT，但源3宽事实必须UNKNOWN；晚到后源2不变、原tap到3FULL。源20早结构FULL bbox[209,0,491,399]、晚RIGHT[350,0,632,399]，开篇早LEFT[67,0,349,399]／晚RIGHT[350,0,632,399]。B1冷17RIGHT[350,0,631,399]，及时15/16首准备前事实可靠、真实左右原图并到17；B2末21wide及广告22也实际可达。原ZIP SHA／bytes未变，参考点独立于截图选择；额外PNG只用于诊断，不代替最终同帧证据。

主代理独审本次实际anchor、visible-key映射及真实生产测试，签收65项覆盖的Desktop窄里程碑；当时B尚欠原B1反向17→16／晚15→返回17的Desktop原图补链及Android旧受影响夹具，C未启动。其余已有Native反向／SQL证据可复用但不冒充原B1该操作；下一最小补链与B末受影响验证合并。仍无提交／SP01勾选／完整模块或正式构建。

### B 原图反向补链与 Android JVM 宿主（2026-10-04，仍在实施）

Desktop原B1冷17用例扩展公开反向操作至16：原15／16 header有界延后、原15 decoder有界延后，其余仍走同factory／CBZ／Skia链。首17 RIGHT；实际反向首16 RIGHT，首prepare前源15 UNKNOWN；释放原工作后连续12帧保持原frame、mount及实际像素边界，再公开next返回原17。`sp-b-android-affected-original-boundary-desktop.xml` 为fresh03:41:37.499Z，1项／0失败／0错误／0跳过，5.804秒。主代理独读测试与XML签收此补链，其他五个原图条件无相关改动，复用03:36绿证据。

同协调器随后执行Android三个受影响类，在第一个真实回翻SQL用例的无界主Looper drain挂起。两次线程转储均为InfiniteTransition→AndroidUiFrameClock→Choreographer→ShadowPausedLooper.idle，CPU持续而输出不进，无OOM；不是SSIV/JNI栈。仅停止该协调器记录的进程树，`sp-b-android-affected-original-boundary` CANCELLED／130，终态03:46:00.962Z；未取得Android完整类结果，不引用旧XML作绿。

Robolectric4.16.1原vsync实现证实自动推进时钟会持续执行加载动画。测试保存旧paused／frameDelay，暂停自动vsync并显式推进16ms真实帧。首次ViewRoot未挂children及后续posted callback没有送达分别诊断：原shared drawn、holder实际集合、eligible与pager idle均成立，VM／SQL尚空；队列head为target=null同步屏障，后有原holder的ViewRoot callback。真实帧推进traversal解除屏障后，原holder→ViewModel→数据库链完成首组、末组及回翻。16ms仅宿主时钟步长，不是产品等待。保留原actualpaint／SQL断言，撤去临时MockK及队列追踪。

| 协调器 key | 实际结果与归因 |
|---|---|
| `sp-b-android-progress-frame-host`、`frame-host2`、`frame-diagnostic` | 分别32／42／42秒自然FAILED，均1项；首次未挂children、原回调未送达的宿主前置，未认作产品红。 |
| `sp-b-android-progress-post-diagnostic` | 18秒、0业务；@Before隐式返回MockKStubScope导致JUnit拒绝，改为显式runBlocking<Unit>，不改方法过滤。 |
| `sp-b-android-progress-post-diagnostic2` | 33秒、1失败；Activity继承Context的临时MockK追踪递归StackOverflow，未到实际绘制。撤Activity追踪。 |
| `sp-b-android-progress-post-diagnostic3` | 42秒、1失败；attached／handler成立，paint后原post仍未消费，随后队列确认同步屏障。 |
| `sp-b-android-progress-frame-post-green` | 自然PASSED，原回翻SQL方法fresh04:10:23.859Z，1／0失败／0错误／0跳过。主代理独读XML及实际宿主实现。 |
| `sp-b-android-affected-frame-host-refactor` | 自然FAILED，终态04:15:30.323Z；Progress37／4失败、Holder47／24失败、Wiring12／2失败，总96／30、0错误／跳过。Progress双页33项绿；四个Single尚未推进原smooth导航，Holder有窗口生命周期前置及旧分组／onImageLoaded期待，Wiring旧optional及匹配当前重组期待属C。三份freshXML归档并独读，不宣称B全部绿。 |
| `sp-b-android-affected-host-boundaries` | 55秒自然FAILED；Single原content回调失效／换版／失败图不记读／存储反馈四项fresh04:18:13.366Z全绿，Holder首图几何绿；真实pinch尚未到手势断言，宿主重挂载旧DirectionalViewPager.LayoutParams被FrameLayout转换失败。6／1、0错误／跳过，继续窄宿主修复。 |

这一轮只改测试宿主及v3实际绘制预期，没有Android production变更，既有同SHA ART原图、JNI／EXIF及Loading→Error→Retry证据仍有效。旧可选前缀IO、16页等待与恢复／手调规则留C红绿，不能为B恢复旧行为。B、SP01、DP02均未勾选；无提交、完整模块测试、正式构建或版本递增。

Android旧布局夹具后续窄修与B重构复验关闭：

| 协调器 key | 实际结果及范围 |
|---|---|
| `sp-b-android-holder-v3-affected` | 0业务，Windows .bat命令长度超限／255；改用同Java21及原GradleWrapperMain入口，经同协调器传相同精确filters。已核JAVA_OPTS／GRADLE_OPTS为空并保原64m options，没有新过滤系统或测试框架。 |
| `sp-b-android-holder-v3-affected2` | 72秒自然FAILED，Holder46／11失败 fresh04:29:31.460Z、Single4／0 fresh04:29:56.997Z，总50／11、0错误／跳过。未重复C三项。 |
| `sp-b-android-holder-host-refactor3` | 12秒格式失败、0业务；新诊断字符串长行分拆。误取旧build XML的临时副本撤除，不作为本轮证据，原affected2归档保留。 |
| `sp-b-android-holder-host-refactor4` | 30秒自然FAILED，11／3、fresh04:34:18.485Z；真实初Root、合法LayoutParams及与测量一致窗口后，8项通过，包括pinch、double tap、Webtoon边界和页面重建。 |
| `sp-b-android-holder-three-boundaries` | 27秒自然FAILED，3／1、fresh04:37:19.146Z；known FULL及原bytes首次画→换版重绘→离屏失效通过，Retry未到点击，按钮宽度0。 |
| `sp-b-android-holder-retry-binding`、`retry-layout` | 分别26秒自然FAILED、各1／1，fresh04:39:36.492Z／04:41:43.895Z；同binding、attached、holder／pager1440宽，feedback参数720而实际缓存宽0，排除旧binding及错误half参数，尚未到click。 |
| `sp-b-android-holder-retry-measure` | 27秒自然PASSED，1／0／0错误／0跳过，fresh04:43:32.377Z；原forceLayout消费既有720参数后feedback720×3120、Retry264×144，实际完整LEFT边界成立；原按钮点击→正确页loader→Ready collector→错误清除及双图几何通过。 |
| `sp-b-android-b-affected-refactor` | 3分26秒自然PASSED，终态04:48:43.771Z；Progress37 fresh04:45:35.134Z、Holder46 fresh04:48:04.407Z、Wiring10 fresh04:48:30.605Z，共93／0失败／0错误／0跳过，无重试；限定格式和diffcheck通过。三XML按完整FQN归档，主代理独读确认。 |

具体前置与v3预期区分：新页面保持测试不应读setUp全局viewer，而读页面实际oldViewer；暂停vsync后须完成初Root，手动1440测量须与480dp／xxhdpi真实窗口一致。onSizeChanged按原宽度重新定位反馈后，手动measure缓存仍旧，forceLayout仅消费原布局参数，未给槽赋假尺寸。旧租约测试首次绘制漏Ready，被原callbackIsCurrent正确拒绝；补真实Ready前置后，旧bytes重绘／原frame／离屏失效全部保留。known宽图在未首paint时合法重挂，须重新取得真实holder。Retry使用原公开按钮performClick验证接线，不声称此JVM用例是触摸验收；同SHA ART late-error的真实触摸／zoom／SQL证据仍单列有效。

旧onImageLoaded不再伪造已读；未知事实不改写PORTRAIT，候选覆盖每个源页且唯一；未画伙伴按源页推进，章界保原0／End。重构结果已撤临时队列、MockK、Root参数和Retry日志，保真实窗口、状态恢复、有界帧及测量助手。主代理完成本段源码／XML／实际产物证据独审，签收SP01.B开发里程碑，允许同实施者进入C。

C明确欠项仍是 `cold real B1 dimensions flow from HTTP encoded headers to late tail native slot` 的旧额外前缀IO、`explicit native target supplements at most two missing facts and deadline` 的旧补读，以及 `matching preference reaches actual encoded sampler shared session and native display pages` 的旧当前重组期待。三项没有删除或计绿，需按C真实资源／操作链红绿适配；最终受影响集合再覆盖完整行为。SP01／DP02仍未勾选，未提交、未执行全量或正式构建。

### C 近窗准备、Desktop 原图片任务与正常请求窗口（2026-10-04，尚未完成 C）

B 的原生呈现与导航门禁通过后，继续同一实施代理、同一轮分段独审。以下均为开发期 focused 证据，未执行完整模块测试或正式构建，不表示 S16、C、SP01 或 S17 已完成。

| 范围 | 正确红因 | 绿／重构证据及边界 |
|---|---|---|
| 共享附近资料准备 | `sp-c-shared-window-red2` 的 FactDemand 8 项中 5 项失败：旧窗口只到当前页附近三页，首像素前未来资料请求及翻页／方向变化的旧采样撤销不满足 v3 | `sp-c-shared-window-green2`：Session 30、StablePresentation 32、FactDemand 8、SpreadMatching 12，共 82 项全绿、无 skip。准备窗口改为当前页沿阅读方向最多五页；当前局部呈现选择仍只消费其必要依赖。`goToPage`、实际源页导航、方向／开关变化撤销旧请求，保留同内容已确认样本。Android Debug 目标 FactDemand 8 项在 `sp-c-desktop-resource-green2` 另实跑全绿 |
| Desktop 首像素前资料与启动前忙状态 | `sp-c-desktop-resource-red2` 两项均因目标行为失败：普通 Ready 邻页仍等待 first-presented；真实 FULL_PAGE 解码正在运行时仍启动 REGION_TILE 采样 | `sp-c-desktop-resource-green2` Desktop 两项全绿；同一次任务另含上述 Android 共享 8 项。真实 RuntimeFactory／原 encoded owner／Skia／Compose 接线，无独立识别客户端；`combine` 观察原图片任务状态，取消首像素前的资料门禁，最多一个可选工作者 |
| 原图片 Entry 的忙状态与生命周期 | 在已有行为绿后补原 owner 的取消和共享资源边界验证，无额外产品实现 | `sp-c-desktop-resource-refactor`：原 Pipeline 13 项＋资源 Wiring 2 项，共 15 项全绿；排队未执行即取消后计数回零且不解码，代际替换／close 后非协作旧解码直到实际退出仍 busy，两个调用共享一个任务，optional tile 被真实 visible caller 升格只计一次，缓存和已完成 retained lease 不保持 busy。Root 已独读代码及 XML，签收此窄范围 |
| Desktop 反向阅读不额外补读 | `sp-c-desktop-normal-window-red` 一项正确失败：原 CatalogueSource Page → 普通 HTTP → Skia／Compose 已画 source index 16，实际反向导航后 target 15 已画，真实请求为 `[16,18,17,19,15,20,14,11,12]`；11／12 越过原 backward-one 正常预取窗口 | 移除 model 原 `startOptionalFactsLocked`／`loadFactPage` 主动补读及重复等待状态，只从普通 Ready encoded 内容贡献资料；不改正常 scheduler 或 Desktop P4。`sp-c-desktop-normal-window-green`：资源 Wiring 3 项＋Pipeline 13 项，共 16 项全绿、无 errors／skip，fresh UTC 05:20:39.590／05:20:47.589。同范围格式检查及 `git diff --check` 通过 |

协调器同 key 的 `.log` 与 `-TEST-<完整类名>.xml` 均留在忽略目录 `.gradle-coordinator/`。Root 独立读取了共享 82、Android 8、资源红绿、Pipeline 13 及 HTTP 红绿 XML，非零执行数与实际时间相符；没有以扫描源码代替行为测试。

过程失败不计行为证据：`sp-c-shared-window-red` 是 Windows Gradle 入口失败；共享首次 green 的两项旧“三页准备窗口”断言失败后按新的五页准备范围调整，保留当前呈现局部范围、旧票据拒绝和已确认缓存断言，实际正确红以 red2 归档为准。`sp-c-desktop-resource-red` 因 runtime／Compose UI 共用 StandardTestDispatcher，在真实解码前的宿主预热循环挂起；仅终止该协调器记录的进程树，保留 thread dump／log，不记 FULL_PAGE 业务红。改用已有 LateExif 测试验证的 runtime Default／UI Standard 隔离后，red2 才出现上述两个正确红因。`sp-c-desktop-resource-green` 因格式 init 的插件加载顺序失败、无业务用例；green2 才为有效绿。

资源独审仍有具体待关闭项：采样运行中遇忙与真正解析失败必须区分；等待单 permit 后及每个有界单位开始前须重新核对资源；idle 通知发生于旧 publisher job 结束前时，不得丢失恢复或要求用户 Retry。原 materialize runner 的 queued／running 物理任务、Android 原 SSIV base／tile 工作、真实内存压力也尚未完成接线。本段现有绿仅证明图片 Entry 生命周期及启动前门禁，不冒充上述能力。手动／设置／晚恢复／保存反馈与固定性能对照仍待实施；SP01、DP02 checkbox 保持未勾选。


#### C 原下载任务的物理完成与暂停语义（窄绿，竞态仍待行为验收）

`sp-c-desktop-physical-pause-red` fresh UTC 05:26:25.660 实跑两项：原非协作 materialize 在逻辑取消后仍受真实 gate 阻塞，source index 16 已实际画出的前置成立，REGION 实际启动 6 次而非 0，构成物理 busy 的正确红；稳定 busy → 旧 publisher 退出 → FULL 释放的中途案例在旧实现即绿，不能拿它证明 idle／completion 竞态已修复。最初第三个低层方法末尾返回 nullable Unit，JUnit 未发现；保留原两项 XML，不计零执行的低层方法为正确红。

修正该 test-only 返回类型后，`sp-c-desktop-pause-result-red` fresh UTC 05:28:27.686 一项正确红：原 FULL 解码真正 entered 后，sampler 以 null 返回暂缓，不能与坏图区分。当前最小实现使用 typed `DesktopReaderOptionalWorkPaused`，在 model 内单独处理，暂停不入 failedSpreadReads；取得单 permit 后和每条有界 edge 前重核资源。原单个 publisher Job 以 LAZY 先登记后启动，未真正完成时不启动第二工作者；真实完成回调触发重评，未引入固定等待或另一个调度 owner。

`ReaderPageMaterializeRunner.physicalWorkCount` 只读 Flow 与原 active/cancel 表分离：原 Job 登记后加一、invokeOnCompletion 的 finally 减一，通知在 runner 锁外；旧逻辑 cancel 不提前声称物理完成。Desktop Session 同时沿原 requestRevision 核 scheduler idle，原页请求、P4 和 materialize executor 不另起实现。

`sp-c-desktop-physical-pause-green` 终态 PASSED／exit 0，UTC 05:36:30.079：Runner JVM 5 项（05:35:47.297）、同共享契约 Android Debug 5 项（05:35:59.613）、Desktop Resource 6 项（05:36:17.531）、原 Pipeline 13 项（05:36:28.841），共 29 项全绿、无 failure／error／skip，scoped 格式通过。归档为 `.gradle-coordinator/sp-c-desktop-physical-pause-green-{jvmTest,testDebugUnitTest}-TEST-<完整类名>.xml`；Root 已核实际结果及 queued-before-body／已取消 scope／真实执行器拒绝／非协作旧任务的 production 契约。

此段签收真实物理计数与稳定 busy 的暂停／恢复窄范围；尚未签收 idle 发生于旧 publisher 完成前的竞态。下一用例仅以测试 dispatcher 扣留原 continuation，保真实 factory、HTTP、Skia、FULL 状态和 Flow，要求先见旧 publisher 尚 active 且前台已真实完成，再放行并自动恢复匹配。内存、Android native 与手动／保存／性能仍未完成，SP01／DP02 不勾选。


#### C Desktop 原内存账本压力与竞态证据判别

`sp-c-desktop-memory-pressure-red` fresh UTC 05:52:55.322 一项正确红：在同一个 RuntimeFactory 的原 Skia 路径取得真实 40×60 FULL 图片租约，仅在测试内把原 authority 的 soft budget 调为 128 B；snapshot.maxBytes、真实 pinned／overBudget 及原 foreground=0 前置成立，REGION 仍新增两次而非零。没有修改 resident／pressure／估算值，没有巨量分配，也不声称系统发生 OOM。

最小绿在原 `DesktopReaderImageMemoryAuthority` 上发布只读 snapshot Flow：原 lease／cache 变更后的通知在原锁外，按已有 revision 防倒序；model 从同账本的 overBudget 判断可选门禁，无第二内存 owner 或缓存。`sp-c-desktop-memory-pressure-green` Resource 7 项（UTC 05:54:41.955）＋原 UnifiedMemoryAuthority 4 项（05:54:56.146），共 11 项全绿、无 failure／error／skip，终态 PASSED 05:54:57.221。

重构将 reader 消费映射为压力布尔值并 distinctUntilChanged，避免每次普通 lease revision 都唤醒准备 collector。`sp-c-desktop-memory-pressure-refactor` 同 11 项再次全绿：原 UnifiedMemory 4 项 fresh 05:57:04.802、Resource 7 项 fresh 05:57:05.068，终态 PASSED 05:57:19.815；scoped 格式及 diffcheck 通过。真实 held lease 释放后原 overBudget 回零，REGION 继续增长，自动匹配仍开启、逻辑目标仍为 source index 0，同一 owner 的所需样本评分完成，未依赖用户 Retry／导航。Root 已独读夹具和原压力 Flow；其性能开销仍由 C 固定对照验证，不以 focused 绿冒充性能通过。

竞态尝试保留为过程记录：`sp-c-desktop-completion-race-red` 一项失败于监测 collector 消费 idle 的宿主前置；red2、red3、red4 均实际绿色，不能称正确红。最初只断言 projection 包含 `[2,3]`，普通双页也能满足，已加强原中途 case 的 REGION 增长及样本需求真正清空；但透明 dispatcher 的祖先调度计数仍不能证明最终 collector 已消费 idle。red5 一项失败于有界 drain 的 100 个原 runnable 上限（UTC 05:49:58.736），未到恢复断言；停止此方法，不放大上限，不保留无判别力 case 为完成保护。已恢复 production completion wake，dispatcher draft 仅留忽略目录，全部实际日志／XML 保留。

下一竞态方法收敛为装饰原 first-REGION 已获取内容 lease 的实际 release closure：原 region.use 在真实 busy Pause 后关闭租约时有界阻住，FULL 先真正完成，原 Default collector 通过其邻页 header 工作重新建立这一实际副作用证明已消费 idle；旧 publisher 此时仍未完成。随后放行同原 release／coordinator，检查无 Retry 的真实采样接受。它不替换 decoder、Deferred、content、lease 或 owner；未运行通过前，此项仍不签收。Android 原生资源门禁、操作与固定性能对照继续待实施，SP01／DP02 不勾选。


#### C 配置自反馈及真实关闭竞态的最终定向验收

前述原邻页 header 的 close 方法实际绿色：`sp-c-desktop-completion-close-red` fresh UTC 06:01:23.060 一项全绿，真实 target `[1,2]`、busy headerJobs `{1}`、idle 重新建立 2、旧 publisher 仍在关闭及原 release 转发恰一次均成立，但该 header 后续事件自然重评，不能保护“没有其他后续事件”的完成通知。下一同实例原非挂起 consumer 的 observed-red 首轮失败于 MockK 安装半态与原 collector 并发，未到行为断言；在原 model lifecycle lock 内完成安装后，observed-red2 fresh 06:06:59.871 仍一项实际绿，亦不记正确红。

由此查出真实自反馈：原 bind collector 的 ensureDualSession 每次调用同值 configure，SharedSession.configure 无条件 rebuild／layoutRevision 增长，publish 又更新被监听的 state。不是继续放大 dispatcher 等待，也不通过这些额外事件假装恢复正常。`sp-c-shared-configuration-idempotence-red` 一项正确失败（UTC 06:11:04.820）：同配置八次，layoutRevision 期望 6 实际 14。最小实现仅在 closed／VERTICAL 校验后对同 direction／automatic 早退；真实选项变化仍调用原 presentation、取消原请求，Single／Dual、手调、事实更新独立路径不改。

`sp-c-shared-configuration-idempotence-green`：Session 31、StablePresentation 32、FactDemand 8，共享 JVM 71 项 fresh UTC 06:12:36.277–402，同 Android Debug 镜像 71 项 fresh 06:12:48.445–567，共 142 项全绿／无 error／skip，终态 PASSED 06:12:49.485。契约同时验证 off／on、缓存 units 同实例、layout／sample／fact／location 不变、已画 frame 和原 header／sample capability 有效、VERTICAL 拒绝、真正 LTR 变化仍生效。Root 独读六份 XML、原 configure／publish 链、rebindContent 单独同步 presentation 的路径，签收此 A/C 接口增量，未扩大产品范围。

在幂等修复后，同 current source index 0、原 factory／runtime／model ===、原第一 REGION 已取得的内容 lease、真实 FULL 工作，再用透明 mockkObject 观察非挂起 startSpreadSamplesLocked：先 callOriginal 返回，才记录真实 idle consumer；原 lease.release 仅有界扣留，finally 转发同 coordinator，既不造 owner／lease／content，也不假造 busy。`sp-c-desktop-completion-close-after-idempotence-red` 一项正确红（UTC 06:14:08.832）：idle 已实际消费、FULL 真正完成、旧 publisher 尚在原关闭、放关闭后 publisher 已完成的前置全部成立，原 sampleDependencies 仍非空，失败为 `Actual completion resumes original sampling`。没有测试工具异常。

恢复原真正 completion 唤醒后，`sp-c-desktop-completion-close-green` 同一项全绿，fresh UTC 06:15:23.452，无 error／skip：无需 Retry、导航或额外 header 事件，原 REGION 增长且 sampleDependencies 清空。最终 `sp-c-desktop-resource-completion-refactor` Resource 8（UTC 06:17:11.579）＋UnifiedMemory 4（06:17:11.319），共 12 项全绿，终态 PASSED 06:17:26.185；scoped 格式和 diffcheck 通过。透明观测安装／卸载均持原 model 锁；终态同 owner／全五页 PORTRAIT／原 encoded activeLeaseCounts 为零／release 转发恰一次。Root 独读真实正确红、绿、重构 XML 与 production 通知／单 worker／压力接线，签收本段 Desktop 资源和关闭竞态。

此签收不包含性能通过或 C 整体完成。仍需 Desktop 旧匹配 wiring 的截止预期和悬挂／关闭真实链适配，Android 原物理／native／压力门禁、恢复／手调／保存闭环，以及固定四模式六场景三次配对性能证据；SP01 与 DP02 继续未勾选、不提前全量或正式构建。


#### C Desktop Retry 身份与跨开关原生采样上限

原保护方法保留名称 `actual setting event samples original encoded leases and changes shared visible layout`，仍经过实际 GeneralTab 开关、RuntimeFactory、原 encoded lease、正常源 Retry、Skia/sRGB、共享 owner 及 Compose 像素。适配过程的 singleflight-red 因原首画前置失败；red2 未到有效采样且当时未定因；red3 卡在旧本地夹具强制刷新后的源解析；red4 的 HTTP 夹具缺原 getImageUrl／Page.url。它们没有执行到 max1 目标，不记业务红，也没有修改本地源 Retry 产品行为。HTTP 夹具沿已有 CatalogueSource／FakeDesktopSourceManager／MockWebServer 链补全正常重试契约。

`sp-c-desktop-optional-retry-identity-red` fresh UTC 06:47:54.611 一项正确红：实际页 Ready、attempt 1、原 owner PORTRAIT／待评分依赖均成立，但 pipeline 的原注册 attempt 仍默认 0，可选 region 被当过期拒绝，目标 failedReads 期望 1 实际 0。最小接线只在原可选 region 消费前，用 model 已接受 snapshot 捕获的 ContentOpenRequest 调用原 beginPageAttempt；不从原始 incoming 状态提前推进，不撤销仍受保护的旧可见内容，不另建身份 owner。

`sp-c-desktop-optional-retry-identity-green` fresh UTC 06:48:49.348 仍为一项失败，必须分段分类：正常 Retry 及故障／显式重试段通过；真实原 REGION 非协作工作、虚拟 15_001 ms 无整章截止、实际 Off、source index 3 的真实绘制和 Skia 源色像素、重新 On／待评分依赖等前置均成立，最终 peakRegions 期望 1 实际 2，才是跨开关／Retry max1 的正确业务红。

原 Pipeline Entry 增加只读 optionalWork：只对新 optional Entry 登记，缓存不登记，同 key 单飞只计一次，被真实可见消费者升格后仍保留原 optional 资格，直到实际 Deferred completion 才减；逻辑取消、generation 或 close 不提前归零。Model 启动门禁等待该计数为零并消费其 Flow；每条 edge 的 canContinue 仍只查前台／压力，不用自身 optional 计数阻塞自己。`sp-c-desktop-optional-singleflight-green` 共 14 项全绿（原保护方法 1 项 UTC 06:52:09.006、Pipeline 13 项 UTC 06:52:21.070，终态 PASSED 06:52:23.107）。保持 On，只释放旧原 Entry，原采样需求自动完成、峰值仍 1，无额外 Retry／导航；随后 Off 保撤票据、旧组不复活、逻辑 source index 3 及 encoded lease 清空。

同轮独审发现 model 启动检查与实际 Entry 创建之间的交错：Retry 取消后清除 publisher 引用，两个不同 key 可能先后通过上层检查。实际创建点也须原子保证上限。`sp-c-desktop-optional-entry-admission-red` fresh UTC 06:54:40.983 一项正确红：第一 optional decoder 已真实进入、同 key 第二消费者仍只单飞一次，但不同 key 的真实 acquire 没有暂停并进入第二次解码。最小实现仅在原 Pipeline lock 的 getOrPut 新 Entry closure 重核 optionalWork；先处理缓存和同 key 复用，冲突抛已有 typed Pause，不排队，不标坏图，正常图片 Entry 仍可创建。

创建点单方法绿 fresh UTC 06:55:32.816，一项全绿。最终 `sp-c-desktop-optional-singleflight-refactor` 共 23 项全绿、无 failure／error／skip：Pipeline 14（UTC 06:57:33.659）、Resource 8（06:57:34.104）、原严格 Wiring 1（06:57:48.184），终态 PASSED 06:57:55.069；限定格式 Apply／Check 及 diffcheck 通过。Root 已独读正确红／绿／最终三份 XML、原 Entry 创建和完成、model Retry 捕获及 Flow admission 路径，签收这段 Desktop Retry／max1 边界。Android native／压力／恢复／手调／保存与固定性能对照仍未完成，SP01／DP02 不勾选、不提交、不提前全量或正式构建。

#### C Android 普通补读与冷恢复投影（adapter 窄段）

`sp-c-android-normal-read-restore-red` 首两项实际绿，不能当正确红：章尾目标 16 的附近事实依赖为空，先签 Load 的恢复场景已有同页表 owner，均未暴露目标问题。调整同一夹具后，`sp-c-android-normal-read-restore-red2` fresh UTC 07:06:20.826 两项均正确失败、无 error／skip：原 normal PageLoader consumer 正在运行，目标 2 有未知附近依赖，旧 adapter 仍调用 loadFactPage `[4,5]`；fresh requiresRestoration store 的冷 setChapters 没有创建 owner，目标不可呈现。

最小实现仅在 DualPageViewerAdapter 删除主动 Supplement 及其预算／等待字段，用普通已加载内容及原 matching 链贡献资料；冷新页表和邻章 Loaded 不再以配对记录已恢复作为投影前提。原 store 的 Load 请求、owner／页表身份和未知记录状态未改，不用空 READY 状态绕过等待。原严格方法名 `explicit native target supplements at most two missing facts and deadline` 作为历史保护入口保留：明确 normal consumer active／未知依赖非空、无额外 loadFactPage，虚拟 15_001 ms 后普通消费者仍活动。另一方法经过冷创建 owner、实际 beginRestore 和二次 setChapters，确认同 owner／pages、RESTORING、revision 未知且 adjustPairing 仍被禁止。

`sp-c-android-normal-read-restore-green` 两项 fresh UTC 07:09:32.886 全绿。最终 `sp-c-android-normal-read-restore-refactor` 三项全绿：Holder 一项 UTC 07:13:55.763、Wiring 两项 UTC 07:14:06.857，终态 PASSED 07:14:14.586，无 failure／error／skip，限定格式及 diffcheck 通过。冷 HTTP Holder 用例通过原 HttpPageLoader／ChapterContentPort、真实 PNG 编码和原图片 View 测量：current source index 16 Ready，不请求远处宽图 `/image/10`，该页事实仍 UNKNOWN；目标 RIGHT，原 View width／left 均 720。它复用 B1 的尺寸形状，不是 B1 原 WebP 字节；Robolectric 几何也不代替 ART 首像素，未据此宣称观察到 backward source 15。

Root 已独读红／绿／三项重构 XML、adapter 变更及上述真实方法，签收此窄段。ViewModel activation 前 await restore、原 matcher 的整 pass 截止、HTTP runner／SSIV 实际资源和平台内存、恢复／保存／CAS／手调闭环及性能仍待 C；SP01／DP02 保持未勾选，未提交或正式构建。

#### C Android 原 HTTP 忙闲启动门禁（窄绿）

`sp-c-android-http-physical-busy-red` 首轮仅测试 API 引用编译失败，零业务执行，不记正确红。修正原 spreadSampleDependencies 引用后，red2 fresh UTC 07:37:29.399 一项正确红：原 TestHttpSource／HTTP／缓存／encoded 路径的 current +4 已 Ready，透明扣留 source index 5 的 Canonical materialize executor 调用并仍委托原实现，原 runner physicalWorkCount=1、active key 前置成立；实际 preference 开启后，可选 decoder 工厂读数期望 0 实际 4。没有手工修改忙计数或复制 runner。

最小实现为 PageLoader 只读 hasForegroundWork／Flow；HttpPageLoader 读取原 scheduler pending／active 与原 runner actual physical，并用轻量 revision 通知 scheduler 变化。Adapter 沿每章原 loader 身份订阅 idle，在原 startMatching 启动前读取真实忙值；不改变正常调度、网络或创建新队列／线程池。Archive／Directory 等现有 loadPage 不执行异步 materialize，其 native 开图仍待 SSIV 资源门禁覆盖。

green 一项 fresh UTC 07:39:59.888 全绿：正常任务扣留期间可选读取为 0，仅释放同一任务后 physical 回零、实际读取增加且同 owner 的 sampleDependencies 清空，无 Retry／导航。最终 `sp-c-android-http-physical-refactor` 四项全绿（Holder 1，UTC 07:42:30.005；Wiring 3，07:42:40.770），无 error／skip，终态 PASSED 07:42:54.563；限定格式和 diffcheck 通过。采样区域测试 adapter 从真实 PNG 解码并调用原 AndroidReaderSpreadSampler，但不代替原 JNI decoder 的 ART 验收。

Root 独读红／绿／四项重构 XML、getter／通知／原 loader 身份和测试透明委托，签收同 loader 正常物理工作活动时的启动抑制及稳定 idle 恢复。此 case 的 scheduler 也仍 active，不能单独证明逻辑取消后只剩 physical busy 的 adapter 接线；切章／替换 loader 后旧物理任务、工作进行中遇忙、旧 optional Job max1、SSIV／平台内存及 VM／操作／性能仍待 C。SP01／DP02 保持未勾选。

#### C Android 逻辑取消与旧 HTTP loader 的实际物理结束

同轮独审要求补两个边界。沿上一段真实 HTTP 夹具，用原 public onPageSelected 取消逻辑请求，确认 scheduler pending／active 及 Runner logical active 全空、physicalWorkCount 仍为 1；同 loader 的 hasForegroundWork 仍为真，可选读数保持 0。另一向量 recycle 原 loader，旧非协作任务仍实际运行；新章原 HttpPageLoader 完成页表和 current +4、独立 idle 后，经真实 setChapters 与 preference On 触发匹配。`sp-c-android-old-http-physical-red` fresh UTC 07:49:01.660 两项中一项正确失败：旧任务未退出时，可选读数期望 0 实际 4；同 loader 加强向量已绿，不伪称为第二项业务红。

最小修复仅在原 Adapter 把 foreground 观察按真实 PageLoader 身份登记。退出窗口立即移除旧呈现 owner；旧 loader 仍忙时保留只读资源观察，实际 idle 后重评当前 owner 并撤观察。启动门禁覆盖当前及仍忙旧 loader，不保存旧页面的读取资格，不新建队列、线程池或工作 owner。

`sp-c-android-old-http-physical-green` fresh UTC 07:51:15.955 两项全绿。最终 refactor 五项全绿、无 failure／error／skip：Holder 一项 UTC 07:54:28.077，Wiring 四项 UTC 07:54:38.758，终态 PASSED 07:54:53.210；限定格式 Apply／Check 及 diffcheck 通过。保持 On 和当前 owner，仅释放同一旧任务，actual physical 回零后样本依赖自然清空，无额外 Retry／导航。Root 独读正确红、green 和最终两份 XML、原公开取消／recycle／replacement 路径及 Adapter 生命周期，签收这一窄段。原 matcher 整 pass 截止、进行中暂停／actual completion max1、SSIV／平台内存、VM／操作／性能仍待 C；SP01／DP02 未勾选，未提交、未提前全量或正式构建。

#### C Android 原 matcher 截止与同 adapter 实际完成上限

保留原保护方法 `matching preference reaches actual encoded sampler shared session and native display pages` 的真实 preference、PNG 编码、评分、DisplayPage、坏图停止／显式 Retry 和原 finally 回收。采样期望按 current 五页窗口改为四条实际样本，而非旧整章 18 条。首 deadline-red 在第 4 条 decoder 已回收但 Main 尚未 accept 时提前检查依赖，未到目标断言，不记截止红；仅把同 owner 的依赖清空加入原等待条件。deadline-red2 fresh UTC 08:01:23.591 一项正确失败：上述质量及重试前置通过，原 region 真正进入后推进虚拟 15_001 ms，旧 pass 截止错误取消实际 Job／子任务。最小移除原 withTimeout 及超时坏图记录；deadline-green fresh UTC 08:04:31.233 一项全绿。

随后 singleflight-red fresh UTC 08:07:17.357 一项正确失败：原 region 仍运行，Off 后原 Job cancelled 但未 completed、native active=1，真实 viewer.moveToPage 到 Ready 源 index 3 后重新 On，原 decoder 峰值期望 1 实际 2。最小实现保留原 Job 至真正 isCompleted，LAZY 先登记再启动，completion 在原 MainScope 重评当前 owner；坏图／未 Ready 首依赖在启动前返回，不新建任务队列或工作 owner。singleflight-green fresh UTC 08:10:18.405 一项全绿：保持 On，仅释放旧 region 后当前依赖自然清空，峰值 1、读取与回收相等、旧 2/3 分组不迟到发布。

最终 matcher-refactor 五项全绿、无 failure／error／skip：Sampler 两项 UTC 08:14:06.564，Wiring 三项 UTC 08:14:11.824，终态 PASSED 08:14:22.855；限定格式 Apply／Check 和 diffcheck 通过。Root 独读两条正确红、对应绿、最终 XML、原 Job 登记／取消／完成及实际操作序列，签收整 pass 截止移除和同 adapter 上限。跨 adapter 旧 native、进行中 HTTP 到达／关闭 completion、SSIV／Coil／平台压力、恢复／保存／手调及固定性能对照仍待；不把此 Robolectric region adapter 证据冒充 JNI ART 或 C 整体完成。SP01／DP02 保持未勾选、未提交。

#### C Android 进行中 HTTP 到达与旧采样关闭完成

`sp-c-android-http-mid-sample-red` fresh UTC 08:19:42.108 一项正确失败：原 PNG region 已进入，正常 source index 5 经原 HTTP executor 进入 physical=1；公开导航取消逻辑后 physical 仍为 1。只释放第一 region，扣留原 decoder.recycle 的实际关闭，第二条有界区域解码计数期望 1 实际 2。没有修改忙计数或复制 runner／sampler。

最小接线在原 sampler 的 open、decoder 创建和每条 bounded edge 前重核资源，以独立 CancellationException 类型 Pause 退出，保留未知样本，不记坏图。Adapter 从 Main 管理的订阅发布 @Volatile 不可变 loader 集合；IO 每次读取新快照及原 loader 同步 getter，不遍历 Main 可变 Map，不另建资源队列。green fresh UTC 08:24:05.752 一项全绿：先释放普通任务并消费 actual idle，旧 publisher 仍未完成、读取计数仍 1、需求仍待；保持 On，仅释放旧 decoder 实际关闭后，同 owner 的需求自然清空，无 Retry／导航／新 header。

最终 `sp-c-android-http-mid-sample-refactor` 六项全绿、无 failure／error／skip：Sampler 两项 UTC 08:31:46.857，Wiring 四项 UTC 08:31:54.594，终态 PASSED 08:32:06.642；限定五文件格式 Apply／Check、diffcheck 通过。此段 green／refactor XML 保存于 `.gradle-coordinator/<key>/TEST*.xml`，不是此前平铺归档格式。Root 独读正确红、green、最终两份 XML、原 region／recycle 委托和通知顺序及线程安全快照路径，签收这一窄段；另核原 setChapters 尾部已有 retainedPages 清理和取消旧 fact 订阅，该处没有新增修复。SSIV／Coil、跨 adapter 旧 native、内存压力、持久化操作及性能仍待 C；SP01／DP02 不勾选、未提交、不提前全量或正式构建。

#### C Android 销毁旧 viewer 后的实际 optional 上限

`sp-c-android-replacement-optional-red` fresh UTC 08:35:10.333 一项正确失败：保留原 preference／encoded／坏图 Retry 链，在原 region active=1、Job cancelled 但未 completed 时实际 destroy 旧 viewer；同 ReaderActivity 创建新 DualPageR2LPagerViewer，原 Ready 源 index 3 导航及 On 后，原 region 峰值期望 1 实际 2。最小实现在原 AndroidReaderSpreadSampler singleton 被动观察原 LAZY Job，StateFlow.update 登记／真正 completion 撤销；Main 无挂起地检查、登记、start，adapter 订阅归零恢复，不创建另一工作 owner、队列或线程池。自身 optional 计数仅用于新 pass admission，不用于暂停已运行的 edge。

green fresh UTC 08:36:55.610 一项全绿：旧物理工作未退时新 viewer 的 Ready 源页可正常定位，匹配峰值保持 1；保持 On，仅释放旧 region 后当前需求自动清空，旧结果不迟到发布。限定五文件格式通过，最终 refactor 七项全绿、无 failure／error／skip：Sampler 两项 UTC 08:38:56.484、Wiring 五项 UTC 08:39:01.901，终态 PASSED 08:39:14.526，XML 位于 `.gradle-coordinator/<key>/TEST*.xml`；diffcheck 通过。Root 独读正确红、green、最终两份 XML、原 destroy／新 viewer 事件、Main admission 和线程安全 actual completion，签收该资源观察接口后再进入 SSIV 下游。此处的原编码 Bitmap region adapter 不替代 JNI ART；SSIV／Coil 前台解码、平台压力、恢复操作和固定性能仍待 C。SP01／DP02 未勾选、未提交，未提前全量或正式构建。

#### C Android 原 SSIV 实际任务与 JNI 自动恢复

本段真实 ART 保留全部前置失败：首 red 使用残留缓存旧 RED，遗漏公开 Clear chapter cache；red2 仅编译测试包，没有执行 ART；red3 的测试 gate 超时抛错导致进程崩溃；diagnostic 的原输入五页均为 80×60 SPREAD、没有待采样依赖。这些均不是正确业务红。修正仅限测试夹具：公开冷缓存、复用既有真实 JPEG 40×60 输入和安全有界门闩，不注入事实、不修改匹配质量。

`sp-c-android-native-resource-red4` 是唯一正确业务红：实际 ReaderActivity、原 HTTP 内容、五页 PORTRAIT／原 owner、真实 SSIV Runnable 已进入默认 executor 且被透明测试门闩扣留；原设置 On 后 optional Job=1、decoder factory 读取=1，期望均为 0。Time 3.246 秒／一项失败，无崩溃。此读取发生在原 JNI 工厂前，不声称失败时 JNI 已完成。

最小 production 仅透明委托同版本 SSIV 原默认 AsyncTask.THREAD_POOL_EXECUTOR，入队前增加只读实际工作计数，原 Runnable finally 或提交异常才撤销；AtomicBoolean 保证拒绝及同步异常只撤一次。recycle／取消不提前减，不创建线程池或排队器。原 adapter 在 Main admission 和每个有界区域前检查，boolean distinct idle Flow 唤醒当前 owner；普通图片路径保持原执行器。

Executor 两项 JVM 绿 fresh UTC 09:05:34.445；限定五文件格式通过。统一开发 Debug 构建 PASSED UTC 09:13:48.567，测试包 PASSED 09:15:00.870。实际开发 APK 为 `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-d35bebd99900/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`，SHA-256 `285e05f0c03df0dddd18a1fe76a5e34694bc90c1efe8cbb84e1b016b88c7e0ea`；测试包 SHA-256 `a92ef616e9b20792e79b46acc26ca98df8bd18993c527ae3b3f9e374f74d1073`，沿用原 dev 证书。专用 emulator-5586 的原单方法 `originalSsivQueuedWorkDefersOptionalJniAndActualCompletionResumesWithoutRetry` 绿为 Time 3.715 秒、OK (1 test)。保持 On，仅释放原 SSIV 工作后，原 JNI 采样自动完成、同 owner 依赖清空、PixelCopy 确认真正 MAGENTA 图片绘制，无额外 Retry 或导航。

最后 `sp-c-android-native-resource-refactor` 九项全绿、无 failure／error／skip／retry：Wiring 五项 UTC 09:18:29.790、Sampler 两项 09:18:21.005、Executor 两项 09:18:28.254，终态 PASSED 09:18:45.353。XML 保存于 `.gradle-coordinator/sp-c-android-native-resource-refactor/`，ART 原日志为 `app-desktop/tmp/sp-c-android-native-resource-{red4,green}.log`。Root 独读 production 计数／执行器／adapter、两项平衡测试、原 ART 事件与前置、实际 SHA、最终三份 XML 和终态，签收此 SSIV 窄段。Coil 初始解码、平台压力、Desktop 跨 runtime、慢恢复／操作闭环及固定性能仍待 C；没有全量／正式构建，SP01／DP02 未勾选、未提交。

规模记录：2026-10-04 UTC 09:13 工作树实际 68 个变更文件，超过原 15–30 个文件估算；该数为当时快照，不是最终统计。增加部分主要覆盖原 S16 的逻辑取消后实际 HTTP／native 任务、跨 viewer 生命周期和完成唤醒，仍保护同一普通阅读优先链路。保持一名实施代理、同一轮分段独审、同一聚合报告；最终提交前再记录实际范围及内聚性，不用文件数拆开不可独立交付的协议。

#### C Android 原 Coil 初始解码实际完成观察

`sp-c-android-coil-resource-red` 是实际 ART 的正确红：合法双帧 GIF BufferedSource 经原 ReaderPageImageView／Coil 首次真实 read，被透明 ForwardingSource 门闩扣留；同 reader 五页真实 PORTRAIT、普通 HTTP idle、原图片尚未 loaded，真实 On 事件后 optional Job=1／工厂 reads=1，期望 0。Time 3.426 秒／一项失败，不是缓存或夹具输入失败。

最小改动只在原 ReaderPageImageView 两个 enqueue 处观察原返回 Disposable.job：提交前登记，实际 Deferred completion 才撤销，同步 enqueue 失败也仅撤一次。原请求、dispatcher、cache、PhotoView、Webtoon Bitmap 分支和成功／错误回调保持原链；dispose／逻辑取消不提前归零。只观察首次加载／解码，动画每帧继续沿既有链路，不将此证据扩称每帧性能验收。

Executor 首次 green 四项一项失败来自测试末尾主动 error 抛 IllegalStateException，日志保留；失败 XML 被后一次任务覆盖，未重造历史 XML，Root 已在覆盖前独读。测试改为原取消语义 CancellationException，保留 dispose→!completed→busy→实际 release 断言，不改 production 计数。`sp-c-android-coil-resource-green2` 四项全绿、无 failure／error／skip，XML fresh UTC 09:29:39.351，终态 PASSED 09:29:40.434；限定格式 PASSED 09:30:03.309。

统一开发 Debug PASSED UTC 09:30:35.831，实际 APK `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-3ace41fc61c6/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`，SHA-256 `85f09c46c2fce09ab4ceb165ca65408d2c50a8f5af0e5111694a604b53722215`；测试包 PASSED 09:31:16.245，SHA-256 `a8945fdc1f5c79dd2f6149a8624fce6bfaa35a70c46dd41c3e8127676db91615`。专用 emulator-5586 的 `sp-c-android-coil-resource-green.log` 执行原 SSIV 与 Coil 两方法，Time 6.501 秒、OK (2 tests)、各 STATUS_CODE 0。真实 GIF 显示为 Animatable、原 MAGENTA PixelCopy 及同 owner 原 JNI 采样依赖自然清空，无 Retry／导航。保留先前 SSIV 原场景的相关复验，不扩展所有 ART。

`sp-c-android-coil-resource-refactor` PASSED UTC 09:32:04.203，但 Executor 测试任务 UP-TO-DATE，复用上述 09:29:39.351 四项 XML，不称 fresh 重新执行；归档 green2 和 refactor 如实保存。Root 独读两原 enqueue、完成观察及取消／失败测试、正确 ART 红和两项绿、实际 SHA 与协调器终态，签收 Coil 初始解码窄段。平台压力、Desktop 跨 runtime、慢恢复／保存／手调及性能仍待 C，SP01／DP02 不勾选、不提交，不提前全量或正式构建。

#### C Android 系统压力、实际窗口恢复与中途有界退出

实际 ART 的 `sp-c-android-memory-pressure-red` 为正确红：对本 dev 进程执行 am send-trim-memory RUNNING_LOW，独立 Application ComponentCallbacks2 确认真实 level 10 回调，MemoryInfo.lowMemory=false（没有制造 OOM）；普通五页 PORTRAIT／HTTP 与原 native idle／原 CYAN 首图成立，真实 On 后 optional=1，期望 0。Time 3.101 秒／一项失败。

最小 production 将只读压力放在原 ReaderActivity：RUNNING_LOW／CRITICAL、BACKGROUND 以上和 onLowMemory 标记受压，排除 UI_HIDDEN；原 resume／窗口 focus 及 normal loader／SSIV／Coil 实际 idle 重新读取 ActivityManager.MemoryInfo.lowMemory。原 adapter admission 与每条 bounded edge 读取同 StateFlow，解除后沿原 scope 恢复，不另建资源 owner／工作队列／线程池，不用设备总 RAM 判永久受压，不挡普通图片。原 matching Job／全局 optional actual completion 上限不变。

统一 Debug PASSED UTC 09:38:10.596，实际 APK `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-466a87b94f8c/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`，SHA-256 `30bb44f96af2bf0ee91814c0d65c105b87c3764ba690f31fecfeca24ad8bc84d`；测试包 SHA-256 `aae54da44d3d1cab177b756c1dc076ae47a695e3de059a0f451c9297c13b3ddf`。`sp-c-android-memory-pressure-green.log` 的原单方法 `originalSystemTrimPausesOptionalJniAndWindowRecoveryResumesSameOwner` 实际 ART Time 3.422 秒、OK (1 test)、STATUS_CODE 0。真实 trim flow 保持 true 时不登记 optional；本 reader 的真实 Dialog 显示／关闭使窗口先失焦再恢复，原 MemoryInfo 刷新 false 后，同 owner／同 On／原 JNI 需求自然清空、原 CYAN 图继续可读，无 Retry 或导航。

同轮独审补齐中途压力 wiring 判别力：保留上述 admission 分支，临时仅撤 IO canContinue 中的 pressure 条件。原 Wiring helper 经真实 normal HTTP 内容、第一 region 门闩，调用原 Activity.onTrimMemory 后 pressure=true／normalbusy=false；释放第一 region，扣住原 recycle 实际关闭，第二条 bounded region 计数期望 1 实际 2。`sp-c-android-memory-pressure-mid-red` fresh UTC 09:40:56.304，一项正确失败。恢复原 IO 条件后 green fresh 09:41:44.774，一项全绿（终态 09:41:53.703）：原窗口 focus 回调刷新 false 时旧 publisher 仍 !completed、读取仍 1、需求未知；仅释放实际 close，原需求才在同 owner 自动完成。该中途段是 Robolectric 原生产接线／Bitmap region 边界，不冒充 JNI ART；真实系统回调来源与窗口事件由前述 ART 覆盖。

最后限定格式通过，`sp-c-android-memory-pressure-refactor` 十二项全绿、无 failure／error／skip：Sampler 两项 UTC 09:42:53.081、Wiring 六项 09:42:59.460、Executor 四项 09:42:57.860，终态 PASSED 09:43:12.929。三份 XML 归档 `.gradle-coordinator/sp-c-android-memory-pressure-refactor/`；mid 红绿各自目录保留。Root 独读 production 状态／生命周期及资源门禁、真实 Native 来源／解除、实际 SHA、mid 正确红／绿和原事件序列、最终三 XML／终态，关闭该独审缺口，签收压力窄段。

本阶段预算重估：截至本回执总约 16 小时；剩 C 2.5–4 小时（Desktop 跨 runtime 15–30 分钟，双端恢复／操作 45–75 分钟，开发性能 45–90 分钟，最终 focused／格式 20–30 分钟），DP02 正式三端约 3–5 小时，合计约 21.5–25 小时，仍在原 18–30 小时估算内。具体阻塞致显著超估时先报告事实／成本，不新增框架或默认为授权。SP01／DP02 仍未勾选／未提交，未提前全量／正式构建。

#### C Desktop 关闭旧 runtime 后的 Pipeline 实际资源观察

`sp-c-desktop-cross-runtime-red` fresh UTC 09:46:47.990 一项正确失败：原 Off／model.onDispose／runtime.close 后，原 decode Deferred 和 encoded lease 在透明 NonCancellable decorator 内仍在途；同 Factory 新 Runtime 的五页 PORTRAIT 和普通 cover 真正绘制均成立，真实 On 后原 region 调用峰值期望 1 实际 2。门闩在原 native.decode(bytes,key) 前，放行后委托真正 Skia；不称门闩期间已经进入 JNI 内部，不注入忙状态。

最小 production 仅将原 Pipeline 的只读 foregroundWork／optionalWork 移为同类 process-shared 观察；各原 Entry flags 仍局部，由真正 Deferred completion 一次撤销，取消／close 不归零。Flow.update 跨实例线程安全；新 optional Entry 创建使用 compareAndSet(0,1) 原子登记，cache／同 key 先复用，冲突沿原 typed Pause，普通 Entry 仍可创建。原 Model 消费同 Flow 自动恢复，不新增锁／队列／任务 owner，也不扩称观察全应用所有 Skia 客户端。

green fresh UTC 09:48:39.569 一项全绿：旧实际工作未退出时，新 runtime 普通图片仍能显示，匹配保持上限；只释放原门闩，同 owner／同 On 的待评分依赖自然清空，峰值仍 1。原 Pipeline admission 方法另覆盖第二 Pipeline 的不同 key 拒绝及同 key 单飞，不增加机械测试类。

格式首轮因 Desktop 配置时 Android 插件未加载失败、零业务测试，该日志保留；format2 先沿原 init 配置 app 再检查 Desktop，真通过。最后 `sp-c-desktop-cross-runtime-refactor` 二十四项全绿、无 failure／error／skip：Pipeline 十四项 UTC 09:51:32.519、Resource 九项 09:51:32.984、原严格设置 Wiring 一项 09:51:49.928，终态 PASSED 09:51:56.541，XML 归档同 key 目录。Root 独读正确红／绿、同 Factory replace／close／原 Skia 委托、global Flow／原 Entry CAS 和 completion、cache 先复用及第二 Pipeline 反例、最终三 XML 和终态，签收 Pipeline 窄段。

此处没有冒称旧 Session 的 normal materialize 物理 IO 已被跨 Runtime 门禁覆盖；它目前仍是 perSession 观察。同轮 S16 独审将复用原 holdOldBackground 夹具核一次该具体旧任务反例，先确定关闭／现接线是否已覆盖再决定必要最小修复。双端记录慢恢复／操作和性能仍待 C；SP01／DP02 未勾选、未提交、未提前全量或正式构建。

#### C Desktop 旧 Session 的正常物理 IO 跨 runtime

同一 S16 独审的具体反例 `sp-c-desktop-cross-runtime-http-red` fresh UTC 09:56:52.258 一项正确失败：原 normal NON_CURRENT_PAGE 任务已进入透明 NonCancellable gate，关闭旧 runtime 后仍未释放；同 Factory 新 Runtime 普通 cover 真正绘制、五页原 PORTRAIT、实际 On 均成立，可选 REGION 调用计数期望 0 实际 8。此 gate 是原 materialize 执行任务边界，不称旧网络 socket 正在传输，也不注入忙标记。

最小共享 API 只在原 ReaderPageMaterializeRunner 增加只读 processPhysicalWorkCount：既有 shouldStart 决定执行后，在原 lock 外登记，先于 instance Flow 通知与原 Job.start；原 invokeOnCompletion finally 一次撤销。原 instance 计数、队列、permits、pump、取消和调度不改。DesktopSession 的既有 physicalWorkCount／idle 及 Model Flow 消费改读 process 观察，原关闭任务真正退出才自动恢复；不新建工作 owner，不扩称整个应用 IO。Android 消费者未改，原同 adapter 旧 loader 范围仍按前述证据。

首 green 已通过 regions=0，但失败在忙时应有 header／spread deps 的旧测试前置：global busy 正确阻止可选 header，因此 spread deps 可为空。这不是新业务红，原日志／XML保留，原正确 red 不重写。green2 fresh UTC 10:01:24.356 一项全绿：旧任务未退期间只要求普通五页 Ready、原 cover 可画且 REGION=0；释放旧实际 gate 后必须 REGION>0、五页原 PORTRAIT、同 owner 和依赖清空同时成立，避免用初始空依赖误绿。

共享 Runner 五项 JVM fresh UTC 10:02:44.820 与五项 Android Debug 镜像 10:02:58.097 均全绿，无 failure／error／skip；原 queued-before-body、取消 scope、失败／非协作取消、P0 等边界增加 process baseline 断言，计数归回原基线。归档 `.gradle-coordinator/sp-c-reader-physical-observation-green/{jvm,android}/`。最终 refactor 的这两项任务 UP-TO-DATE，复用上述十项有效结果，不称 fresh 重新运行。

`sp-c-desktop-cross-runtime-http-refactor` 的 Desktop 二十五项真 fresh 全绿：Pipeline 十四项 UTC 10:04:38.335、Resource 十项 10:04:38.804、原严格设置 Wiring 一项 10:04:58.522，终态 PASSED 10:05:05.169。归档同 key 的 jvm／android／desktop 子目录。Domain 格式通过；Desktop 两轮仅新测试长行格式失败、零业务测试，日志保留，format3 真通过。最后只做方法声明／缩进和 assert 机械断行，不重复已适用的业务集合。

Root 独读共享登记先后／锁外 Flow／原 completion finally、仅两个 production Runner 消费入口、Desktop idle wiring、新原 Factory 事件和正确红／green2、十项共享镜像及二十五项平台实际 XML／终态，签收这一共享观察接口和 Desktop 窄段。此前 Pipeline 与 normal materialize 两个跨 runtime 实际缺口均已行为保护；双端慢恢复／手动／保存及固定性能仍待 C。SP01／DP02 未勾选、未提交，未提前全量／正式构建；剩 C 约 2–3 小时、DP02 约 3–5 小时，整体约 21.5–25 小时估算保持，具体超估先报告。

#### C Android 慢配对记录与原按钮状态

三条正确红分别保护真实接线：初始 `sp-c-android-nonblocking-restore-red` fresh UTC 10:13:50.553，一项失败，原 repository 已进入且读取扣留、owner 为 RESTORING／revision 未知，Mode 6 的原 init／activation 两秒内仍未返回；邻章 `sp-c-android-adjacent-nonblocking-restore-red` fresh 10:33:04.701，一项失败，原 chapter 3 页表已 Loaded／记录读取已进入，但正常邻章请求仍等待该读取；UI `sp-c-android-restoring-adjustment-ui-red2` fresh 10:42:01.849，一项失败，原 AppBars 的调整节点仍带 OnClick 且没有 Disabled，恢复期间未禁用。

最小实现沿原 ReaderViewModel／store／application coordinator：明确 DUAL_PAGE_R2L 或 AUTO 的实际 Dual viewer 允许激活和正常邻章页表先推进，原 Main callback scope 执行异步恢复，真实 repository IO 仍在原 coordinator scope。显式单页／条漫不扩大改写。原 owner／pages／Manga／请求身份继续校验，失败和 Retry／临时模式仍用原入口；不注入空 READY、默认 revision 或新恢复 owner。`pairingRecordReady` 仅重新读取当前 store owner 状态，原 Activity→AppBars→BottomBar 据此禁调；邻章／旧任务完成不能直接清 pending，不借“正在保存”文案，不在 RESTORING 增加 Retry。

失败分类如实保留：首 green／green2／green3 均停在实际 read entered 前置，虽 opening 已完成、后两次原 owner 已 RESTORING，原因未被诊断证明；不将其归为产品红或已证实的 scheduler 故障。只读诊断第一轮编译错误、UI 首红因表达式返回类型造成零测试、两轮格式入口／lint 失败均是零业务证据。diagnostic2 fresh 10:28:41.854 一项通过后，撤去诊断和无效 scheduler 补丁，沿原 Main callback scope 重构，未扩大超时或修改断言求绿。

首 refactor 六项一项、green4 七项两项失败来自旧 Retry／Default 在恢复未完成或旧显示区间内期待已恢复组合。保留真实按钮、原 SQL 和记录值，测试区分普通可读与实际 owner READY；Retry 从尚未涉及未来配对的源页进入，Default 保留当前回退分组及旧保存记录，不用后台回跳证明恢复。两项诊断通过后撤打印，未为这些测试修改共享画面保护规则。

`sp-c-android-nonblocking-restore-green5` 真 fresh UTC 10:47:24.054，八项全绿，无 failure／error／skip；终态 PASSED 10:48:03.536。包含两项慢读取、原请求签发、取消后重试、单页失败恢复、实际 Retry／Default 与 CAS。慢读取期间真 AppBars Adjust disabled、无额外 Retry；释放真实读取后同 owner 接纳原 `{3}` 记录、用户源页 3 定位不回跳，按钮重新 enabled。XML 归档 `.gradle-coordinator/sp-c-android-nonblocking-restore-green5/`，各正确红和失败日志保留。限定 format3 通过，未运行完整模块测试或正式构建。

Root 独读三个正确红、最终八项 XML／终态、原 store／Main 接线及实际 Compose 节点变化，签收 JVM 恢复／按钮窄段；它不冒称真实 JNI 首像素或迟到记录零位移。Android 对应 ART、Desktop 恢复／操作及固定性能仍待 C，SP01／DP02 未勾选、未提交。

规模补充：本轮实际只读统计的 70 文件快照分为产品实现 30、测试 35、文档 5；不是最终提交统计。恢复按钮接线后仍按同一 S13 能力批次记录最终内聚性与风险，不把文件数当作额外产品范围或提前完成证据。

#### C Android 迟到记录的实际 ART／几何

统一开发 Debug 自然 PASSED UTC 10:57:12，实际 APK `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-b1ab71e17160/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`，SHA-256 `2d9f648afa505d7d032871a711f3e658a85c665ee5655013a7bd312aa6e8720e`。初 test APK PASSED 10:58:45.215，SHA `7394ecb4ce1fa690b2274377377356d22b1711874d06633396f278be2cbcd43a`。首 ART 使用旧包名而找不到 runner，零业务；native2 实际图片已画出，但 repo entered 未成立，不能作为慢记录验收或正确产品红。两个原日志保留。

已读本机 Injekt 字节码确认夹具原因：addSingleton 的 factory 替换保留 existingValues 中已物化的旧 Lazy。测试仅用原 addFactory API 包装真实 repository，并断言当前 get 和真实 ViewModel 的 coordinator 均为同一包装器；不加 production seam、不重建上述 Debug。限定格式通过，`sp-c-android-restore-native-di-test-apk` PASSED UTC 11:12:21.364，新 test SHA `f0c30394a37a78c9fd5cca431ec6ecc9ee128a430c012b8eb4a97ae6cdb30a50`；两包 dev 证书连续。

专用 emulator-5586 已核注册身份 `app.mihon.desktop.fork.dev.test`／target `.dev`。原单方法 `heldOriginalPairingRecordDoesNotDelayNativePixelsOrMoveExposedSlots` 的 native3 在实际 PID 25461 执行，Time 11.995 秒、OK (1 test)、STATUS_CODE 0。HELD 阶段真 RESTORING／未知 revision／无保存 request，原 Copy HTTP／合法 JPEG／JNI／PixelCopy 已画源 1 和 2，SQL last_page_read=2，实际双击缩放到 2 倍。仅释放真实记录读取后，同 owner 接纳原 forced `{1}`，12 次完整矩形、图像边界、mount、container、scale／translation 和 frame 相同，SQL receipt 总数 188→188；该数是当时数据库累计基线，不是本用例新增 188 次进度。真实下一页键随后进入源 3 的 BLUE 图并推进原 SQL。

原几何记录 UTC 11:12:47.220／52.136：RED content `Rect(540,392–1077,2009)`、GREEN `Rect(0,392–537,2009)`，同 container `5c2108a`、mount RED `1c7a07e`／GREEN `bf9fa00`、scaleX/Y=2、translationX/Y=0；恢复前后相同。日志为 `app-desktop/tmp/sp-c-android-nonblocking-restore-native3.log` 与 `sp-c-android-nonblocking-restore-native-geometry3.log`。Root 独读真实依赖身份、门闩／原 SQL／touch／像素及 12 次断言、ART 终态／几何，独核生产及新 test SHA，签收 Android 开发慢恢复窄段。该证据不替代正式 R8、B1／B2 原图或完整性能；Desktop 恢复、其余操作／性能仍待 C，SP01／DP02 未勾选、未提交，未提前全量／正式构建。


#### C Desktop 慢记录期间的图片与原恢复按钮

`sp-c-desktop-nonblocking-restore-red` fresh UTC 11:18:08.386，两项正确失败：真实 repository 读取已进入并被扣留，原 viewport 仍只显示 Loading pages、没有原生首像素；读取失败时原 ERROR 页面也移除了图片，只余恢复反馈／原按钮。终态 FAILED 11:18:30.623，保留归档 XML 与日志。

最小 production 只改原 DesktopReaderScreen：双页原页表非空时进入 CONTENT；ERROR 在图片上用原 PairingRestoreError 的局部面板，沿用原 Retry／本次默认按钮及本地化。原 Model 异步恢复、Factory／owner／SQL 不新建；Single／Webtoon 原恢复路径及正常图片 loading/error 不扩大修改。未知 revision 禁调／禁保存，Default 不伪造已恢复或覆盖原记录。

green／green2 的后续失败保留：原业务已进入图片／按钮，但 Retry 的 Model READY 先于实际 enabled 节点重组；held 导航逻辑已到源页 3，原测试固定 render 时间没有推进真实动画。仅测试等待真实节点，并用 System.nanoTime 推进原动画，不改 production 导航、不扩大超时。`sp-c-desktop-nonblocking-restore-green3` fresh UTC 11:23:52.635，两项全绿、零 failure／error／skip，终态 PASSED 11:23:59.819。

真实 Factory／原持久化 repository／ComposeScene／Skia 证明：慢读取时首像素已画、原 Adjust disabled、真实 reader tap 进入源 3／4；仅释放原读取后接纳 saved forced `{3}`，当前 source 3 不回跳，frame／composition mount 与 BLUE 联合像素矩形 12 次相同，原 SQL 配对记录不被覆盖、按钮重新 enabled。读取失败分别执行真实原 Retry 与 Default：Retry 再次读取且 pending 时保留图片，完成才启用；Default 保留未知 revision／禁调且不再读，原 SQL 配对与 last_page_read=5 基线不变。

Root 独读正确红／green3 XML、协调器终态、production viewport 分支、完整原按钮／tap／SQL／Skia 事件，签收该慢恢复窄段。联合 BLUE 矩形是两图像素的包围框，不冒称各槽完整 geometry／transform；不替代 B 原生逐槽证据或正式 Windows／macOS 运行。原合法未知伙伴手调、其余操作／固定性能仍待 C；SP01／DP02 未勾选、未提交，未提前全量或正式构建。

规模补充：本次只读工作树为 74 个变更文件，其中 32 产品实现、37 测试、5 文档；这是当前快照，最终提交前再核。仍为原 S01–S16 同一功能批次，一名实施代理、同一轮分段独审，没有新增产品能力或独立框架。


#### C 原未知伙伴调整与保存重开语义

操作簇首格式／refactor 只有测试长行 lint，零业务，日志保留。refactor2 实际 Compose 六项两失败、Model 九项一失败：原新未知伙伴用例未真实建立 UNKNOWN；旧重开期待远处全章相位；DEFAULT_UNVERIFIED 直接 Model 局部调整仍无 repository writes，但旧断言期待局部集合不变。没有为旧期望修改 production。共享原 beginSave 的会话内 DEFAULT 局部语义保留，并检查真实 writes=0／未知记录不覆盖；Model 精确方法在后续 focused 已绿。

未知伙伴诊断最初按旧行号推测 automatic matching false，已撤回。实际失败在 `!unit.certain`，尚未执行设置断言；单次真实身份诊断显示 sameRuntimePrefs／setup／runtime／state 均 true，不存在该次假设的缓存或设置故障。DIRECTORY 已提供 header；ONLINE 的 NON_CURRENT_PAGE 门闩也不会扣住当屏伙伴。仅测试复用原 ONLINE MockWebServer，在 source index 2 的真实响应上放透明 CountDownLatch 门闩，原请求和图片 bytes 保持，finally 释放；不注入类型或组。

`sp-c-pairing-unknown-operations-focused` 的 Desktop fresh UTC 11:52:58.867，两项一失败；`legalUnknownPartnerAdjustmentUsesOriginalButtonWithoutDisablingMatching` 实际通过：原记录 READY、伙伴非 Ready、uncertain 双页 `{1,2}`，真实原按钮 enabled／onclick 保存 SQL forced `{1}`，automatic matching 保持 true。剩余失败仅原 reopen 最后等待，Android 因 Gradle stop-on-failure 未执行。Root 独读真实门闩、按钮、SQL、状态和 XML，签收这一 Desktop 操作窄段。

Android 首 diagnostic fresh UTC 11:59:30.723 一项失败为 JVM 无 imagedecoder JNI 的宿主前置，不是产品红；该命令 Desktop 未运行，不混存旧 XML。复用既有 encoded-region Robolectric adapter 并保留真正 On／原 owner／UI／SQL，`sp-c-pairing-unknown-operations-diagnostic2` fresh UTC 12:02:42.160 一项全绿、无 failure／error／skip：原 `Activity.AppBars` enabled 节点真实 performClick，SQL forced `{1}`，同 owner 的伙伴仍 Queue／UNKNOWN、自动匹配仍 On。仅此用例使用原区域 adapter，不关闭匹配或安装系统 JNI；不替代此前 ART/native 证据。Root 独读上述真实方法和 fresh XML，签收该 UI 操作窄段。

同 diagnostic2 的 Desktop fresh UTC 12:02:23.356 一项失败给出实际 current source 5、READY／revision 1／manual `{1}`、viewport `{5,6}`；旧期望 `{4,5}` 是冷跳应恢复远处整章相位的断言，与 v3 局部决定冲突。原真实保存／SQL 重开／记录恢复仍保留。只改原用例：接纳迟到记录后保护当前 frame，实际 Reader Settings Dual Off→On 保持 source 5／图片，随后真实 previous taps 回到 source 1，必须单页独占且原 forced `{1}` 仍保存。

`sp-c-pairing-reopen-local-boundary-green` fresh UTC 12:04:25.115，一项全绿、无 failure／error／skip。Root 独读实际设置节点／previous tap／单页像素／保存边界与 XML，签收重开语义；不预注最终组、不删掉保存／应用检查，也不修改 production 求旧相位绿。上述操作原类 refactor／格式和完整固定性能仍待 C；SP01／DP02 未勾选、未提交、未提前全量或正式构建。


#### C 操作簇关联复验与结果复用

`sp-c-pairing-operations-scoped-format` PASSED UTC 12:07:46.790。首次相关类命令 `sp-c-pairing-operations-refactor` 实际 Compose 六项全绿 fresh 12:10:21.313，Android 二十二项九失败 fresh 12:10:36.366；命令中的 Model 包名错误，没有其 XML，不记九项执行。该命令终态 FAILED 12:12:02.877，全部真实结果保留。

九项汇总后只改 test-only 前置／v3 预期，不改 production：远处保存边界不保证冷跳全章相位，但重开、ViewModel 取消后的 actor 次序、recreated viewer 必须保真实 SQL 并在正常回到保存 boundary 时独占；迟到宽图保逻辑源页及手动边界；末页只把实际 DisplayPage 的 source 7 传入原 settlement／SQL，不加入未在该 unit 的 source 6。该末页用例是原 Model 进度输入层，不冒称实际 native draw。邻章等待原 owner READY／RESTORE_FAILED，loaded 内容可进入原 adapter，明确导航后原 Dialog／Retry／Default 仍可用，未知 revision 仍禁保存。

`sp-c-pairing-operations-affected-green` Android 精确九项八通过、一转场前置失败，fresh UTC 12:17:55.505；正确 `mihon.desktop.ui.reader.DesktopChapterPairingModelIntegrationTest` 九项全绿 fresh 12:18:45.421，无 retry。转场停在未消费 Reload 的 raw Activity 宿主前置，未到旧最后配对断言。该夹具不启动实际 Activity collector，改用原 viewer.setChapters 消费真实已 Loaded／store ERROR 的加载事件，之后明确 loadNextChapter→原 ERROR Controls／Retry→READY→正常 boundary 3 独占；不模拟 header／组，不修改恢复实现。B／ART 的真实 collector 证据有效复用，此方法不被称为 collector wiring 验收。

`sp-c-pairing-transition-loaded-content-focused` fresh UTC 12:22:11.051，一项全绿，终态 PASSED 12:22:26.659。核 manifest／治理文件没有受保护的旧名称引用后，测试名改为 `RTL unloaded transition exposes content and explicit navigation preserves pairing Retry`。late-wide 用例追加正常回到 boundary 3 的实际 singleton；replacement 的同等检查已在 12:17:55 快照前包含，不重复复验。

最后 scoped-format-and-focused 首次只因测试声明长行 lint、第二次只因清单参数传递失败，均零业务，日志保留；不算新业务红。`sp-c-pairing-operations-final-focused3` fresh UTC 12:35:59.557，两项全绿、零 failure／error／skip／retry：上述转场和 late-wide。五文件限定 Spotless Apply／Check 同命令通过，终态 PASSED 12:36:23.926，真实 XML 归档 `.gradle-coordinator/sp-c-pairing-operations-final-focused3/`；diffcheck 通过。

有效组合覆盖 Compose 6＋Model 9＋Android 22＝37 个方法：Android 首次未受后改影响的 13 项通过、九项定向中的 8 项通过、剩余转场通过；末两项又对测试改名／late-wide 新检查补验。该组合不是最终源码重新执行三类 37 项的 fresh 结果。Root 独读首次和补验 XML／终态、邻章原 Controls、save／cancel／recreated viewer／boundary 应用与末页输入边界、最后两项源码／XML，签收同轮 S13 操作窄段。固定性能及 C 批次冻结仍待；SP01／DP02 未勾选、未提交，未提前全量／正式构建。


#### C 开发性能对照：Desktop 已取得证据，Android 尚待完整终态

Desktop 原 B2 archive SHA-256 为 `760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439`，22 张原 JPEG bytes 经真实 MockWebServer、Factory、正常解码、原图片 owner、ComposeScene 与 Skia，不使用微型图片或注入事实。四条件 Off／On／Suspended／ForegroundPressure ×六场景×三次，共 72 个样本；`sp-c-development-performance-refactor-and-native-apk` fresh XML UTC 12:56:47.945，单方法 26.137 秒、零 failure／error／skip。测试包构建与限定格式同 key 通过，不是正式产品构建。

透明 observer 调用原 `acceptReadyAsset` 和 `drawDualViewportPage`，仅其真实返回 true 后记 Ready／paint；明确目标源页、内容 generation／attempt 与 frame，第二图必须属于同一 frame 的另一槽，封面单槽记不适用。Ready／paint 使用真实墙钟；renderCost 仅是 scene.render 调用耗时，renderInterval 是实际 render-start 间隔，两者均不是原生 vsync／掉帧统计。TestScheduler 的 16ms 推进仅驱动开发宿主定时器，不冒称实际经过 16ms。

首 72 样本 On 的 18 项中 12 项确有 optional decode，Suspended 的 18 项均保留实际旧任务计数 1，直至正常图片绘制后才 release。该门闩在原 decoder 调用边界，不冒称 JNI 内部已开始。Desktop Pressure 为原 memory authority 的 128 **bytes** 软预算，确有 overBudget 且不新增 optional；不冒称物理系统 OOM。resident／pinned／Java heap 是观测采样峰，不是全进程高水位。300 页场景是 22 张原 payload 循环组成 300 个独立源页身份、冷跳源 index 199；它不是 300 张不同原图，也不替代 A／B 的 300 页首页证据。

不以平均值消除波动：首 72 中 On 300 Ready→paint 为 44.18／18.00／37.12ms，Off 为 24.59／24.26／26.50ms；Pressure rapid 输入一次 173.36ms，Off warm 首次 285.34ms。后一 Pressure 样本 optional decode peak 为 0，Off warm 有 157.78ms 单 render 成本，但不能据此未经分析将所有波动都归宿主。warm On 一次普通请求六源、Off 五源，不能称所有行 IO 完全相同；采样只读取现有 ready stream、不 materialize 的原生产契约及资源证据继续复用。

只对具体疑点追加 `sp-c-desktop-development-performance-300-diagnostic`：同一方法限定原 300 场景，四条件×三次=12 个样本；fresh XML UTC 13:40:42.572，1 方法 8.791 秒、零 failure／error／skip，限定格式同 key 通过。透明记录 normal HTTP 源索引、decoder enter／return、原 prepareDecoded 返回值及真实 Ready／paint。On 三次 Ready→paint 43.76／35.78／41.40ms，均相隔两个 render；Off 65.68／44.07／30.56ms，间隔二／三／二。On 0／2 在源 201 optional REGION 已进入且尚未返回时，源 199 已实际画出；同 render 的旧 ticket false→原新 ticket true 无等待。Off 1 同样在普通宽图事实到达后出现 ticket 换代。12 次请求均为正常源 198..203 六源、2,958,053 bytes。

Root 独读两组当前 XML、真实 observer／事件及源码，签收开发层级的“不等待 matcher 完成”窄证据。新诊断未复现稳定的 On 额外 render／大 render 峰，不证明数学零开销或所有硬件下无微小 CPU 竞争；正式 Windows／macOS、Android R8 的性能和原生体验仍归 DP02。两组构成 72＋12 的有效证据，不称最终完整矩阵重新执行。

Android 同样保原 B2 payload、实际扩展解析／normal loader／JNI SSIV／独立原图 25 点 PixelCopy。开发 Debug production 始终是已核 SHA `2d9f648afa505d7d032871a711f3e658a85c665ee5655013a7bd312aa6e8720e`；后续只重建测试 APK。Native1 首 11 行后错误地要求反向输入必须再次 child onDraw；已画邻图可能由 RenderNode 复用，改为保实际原 Ready／首次 draw，输入另记独立 PixelCopy 首次正确目标画面的观测上界，未再次 draw 就记不适用。Native2 在 12 行后 donor 预热 take(5)Ready 假设失败；原真实宽图及 manual `{1}` 使该请求假设不成立，改为正常源 3／4 真 portrait、原 HTTP／native idle 后扣原 optional。保 SQL；22 页场景 baseline `{1}`，300 独立章节为空，各自四条件一致，不称全部无手动记录。

Native3 为 19 行、Time 66.185 秒／一失败，pressure warm 的 trim 在 On 后建立，已有 task 不证明受压时新增。Native4 已先 trim→On，却在 On cold-jump 出现真实图片 Error，10 行、Time 62.56 秒／一失败；HTTP 两次返回原源 19 JPEG 517,837 bytes、缓存 SHA 一致，目标 frame 无 lock／draw、无 native 图且原 Error UI 可见。其原因尚未证实，不能记作 observer 假阴性或 BUG 已修复。精确单场景透明原 onImageLoadError／UI 文本诊断在 PID 29323、Time 3.918 秒、OK (1 test)，原 JNI／真实像素与反向均通过但没有复现，不消除原失败。Root 只读保留 PID 28679 的原 WARN／ERROR 日志，未取得对应解码 Throwable；其中 dex finalizer 断言没有已证实的业务因果关系。

Native5 PID 29471 有 23 行、Time 80.775 秒／一失败，最后 pressure300 仍 pressure=true／optional=1。停止猜式整组重跑，只在该场景记录原 pressure／optional flow、真实 MemoryInfo、focus／lifecycle 与墙钟。精确诊断 PID 30695、Time 4.303 秒、OK (1 test) 已证实：UTC 13:37:38.466 pressure=false、实际 lowMemory=false 后 optional=1；.477 原 trim 又 true；.489 实际 optional=0。此前 .300→.311→.328 同波形。原 adapter 在普通工作 idle 时查询实际 MemoryInfo 解除压力，符合既有恢复机制；瞬时 trim 不能伪装持续系统低内存。每段真压力仍禁止新可选 IO／解码，旧工作有界退出由原实际资源红绿／native 证据保护；不修改生产为永久压力 flag，也不以峰值最多一项代替 admission 门槛。

Native6 将按真实 trim／idle 解除波形完成原 72：初 trim→原 On→target release 保真 pressure=true／optional=0；记录实际状态转移和 lowMemory，finish 后等原 physical optional=0。不再要求解除期间已合法启动的旧 task 在任一后续瞬时 trim 立即从物理计数消失。该波形不覆盖持续 lowMemory；原持续 foreground busy 的 SSIV／Coil 红绿及 native 证据明确复用。Native6 已在 PID 30867 终态 Time 60.817 秒／一失败，仅 Off 六场景及 On 前三场景共九行，不是完整 72：On rapid-keys 的真实逻辑 target 源 3 未取得正确窗口像素。当前 frame 为 `{2 RIGHT,3 LEFT}`、target 3、seq 3／context 1，locked／drawn 为空；原 native 子图已 Ready／base，但没有对应真实绘制。透明原 error callback／UI 文本未记录异常；不能归为 observer 假阴性或已证实绘制门禁故障。原 raw／单方法失败保留，已停止整组重跑，尚待该路径的原 maps／prepare／内容绑定诊断。测试 APK SHA `12bd480c2241351d23d4188e91d6040e549d2cc5bd1efc1b0301e3edb54aa416` 对应该失败，production Debug 未改。Native4 图片 Error 原因也仍未知，两个相关失败不能因精确诊断或后续采样绿色无说明消除。开发私有字段／callback 观测不自动适用于 R8，不新增 bulk keep 或关 shrink 取得正式通过。

各 raw logcat 可能含历史进程，只按实际 PID／时间段／对应 test APK 统计，不从总行数拼出 72。以上均不勾选 SP01／DP02；尚欠 Android 完整性能结论、C 最终关联验证／签收／提交及正式三端验收。

后续限定诊断没有修改 production Debug。观察器已修正为只包装原非空 beforeImageDraw／onImageLoaded／onImageDrawn，原重绑定清空时保留 null。原 ReaderPageImageView 的两个绘制回调皆 null 时有直接允许分支；旧观察器在此补函数会增加 current／尺寸／可见性判断，这一观测语义改变已由实际源码核实。但没有原 Native4／Native6 对应时刻的 null 记录，不能据此认定两失败已被解释或 BUG 已修复。

三回调 guard 后，`sp-c-native-performance-callback-boundary` 的 Rapid／On 三次在 PID 31790、Time 10.912 秒、OK (1 test)，真实绑定、原 beforeDraw 和原图强 PixelCopy 均通过；三次都是 context 0，不能覆盖 Native6 的 context 1。随后 `sp-c-native-performance-geometry-diagnostic-apk3` PASSED，测试 APK SHA `e09ec0bf80d71362d33f87cbcc55326f26c1d86a4e91aceceddcf5a86b11a623`；真实 scale 设置事件形成 config 2／context 1，PID 32105、Time 4.224 秒、OK (1 test)。源 3 bound 4:0／1130×1596 Ready、原 beforeDraw=true，context 1／frame 13 的 RIGHT 槽实际绘制及强 PixelCopy 通过。它只覆盖明确设置边界；Native6 context 1 的原事件来源仍未证实，初始偏好 Flow 分派仅是待证假设。

同 `e09ec…` 测试 APK 的 `sp-c-native-performance-cold-diagnostic` 在 PID 32270、Time 10.581 秒、OK (1 test)，Cold-jump／On 三次的源 19 和反向源 18 真实 JNI／绑定／原图 PixelCopy 均通过，其中一次为实际 context 1；原错误回调及 Error UI 未复现异常，不消除 Native4 风险。进一步 `sp-c-native-performance-pending-context-apk` PASSED，SHA `46e11e98dffe9b9cf476e441025b2c79f68acb4991e59aac91c3d30c0dc5bb65`；PID 32578、Time 4.151 秒、OK (1 test)，真实 HTTP body 门闩建立逻辑源 3、候选 `[2,3]`、源 2 UNKNOWN；原 scale 事件形成目标 holder context 1 后放行，源 3 在 RIGHT 槽实际绘制并通过强 PixelCopy。严格的源 3 未 Ready 前置断言及完整 Throwable 记录仍在本方法重构复验；上述有限结果不是最终 72 对照全部通过。

范围快照：2026-10-04 UTC 14:39 的 `git status` 为 76 文件＝32 production、37 tests、6 docs、1 manifest，原规划约 15–30 文件的估算已被明显超过，C 耗时也超原 3–5 小时估算。新增实现与测试仍围绕 S01–S16 的共享呈现、原双端接线及原资源／操作链；没有新产品能力、HTTP 客户端、缓存系统或独立 benchmark 框架。继续使用一名实施者、同轮分段独审；全量矩阵和正式构建尚未启动，SP01／DP02 未勾选、未提交。两原生失败的定向诊断按已说明的追加 30–60 分钟估算收口，不用未复现或缩减为 24 个样本代替原 72 的必需证据。

严格 pending 重构补验：`sp-c-native-performance-pending-context-refactor-apk` PASSED 47700／38464，限定格式通过，测试 APK SHA `1c1fe358eb772bbb6d8e8c18e51ab9c4261b14bd21851e4088339167c8fe1728`。`sp-c-native-performance-pending-context-refactor` 在 PID 32754、Time 4.075 秒、OK (1 test)；日志确见 source 3 为 DownloadImage、source 2 UNKNOWN、`[2 RIGHT,3 LEFT]`，原 scale 事件后 context 1／frame 16，源 3 bound 4:0／原 beforeDraw=true／RIGHT 槽实际画出并通过强 PixelCopy。Root 独读实际前置、绑定、原回调及两终态，签收此窄场景；不解释 Native6 原失败。

正确 observer 的首次完整对照尝试 `sp-c-native-development-performance-correct-observer` 终态为 PID 929、Time 174.387 秒、一失败，测试及 production SHA 同前。失败发生在 suspended 条件为下一场景建立 donor 的预热步骤，尚未安装 measurement observer；源 3／4 未同时达到原 Ready／PORTRAIT／physical idle 前置，原 Error Layout 可见。已停止整组重跑，不降前置、不修改 production 猜绿。原 ERROR 日志没有目标 decode Throwable，SQLite lock／dex finalizer 不能作为已证原因；Native4、Native6 及本次 donor Error 仍是待分类的相关风险。

日志收集限制及纠正：原 geometry 文件保存 37 个起始行，但尾部缓冲缺失，且一条 frameIntervalNs 长行被截断；起始行数不能记作 37 份完整采样。随后补存同 PID 的 `sp-c-native-development-performance-correct-observer-ring.log`，确见 repeat 1／suspended 的 warm-sequential UTC 14:44:06.982、slow-partner 11.200、rapid-keys 15.688、cold-jump-reverse 20.170 都是 22 页场景并通过；下一个失败是 suspended／300 的 donor。先前仅依 37 条前缀推断“22 页场景返回 300 页”已撤回，未形成输入串扰证据，也没有因该推断修改 client／registry。被截断 cold-home 的资源尾字段已从原 ring 补取得；后续观测须完整保留有场景身份的字段，不能当缺失为零。原 raw／ring／错误日志全部保留；本轮仍不具备完整 72 性能通过结论。

冻结批次验证 `sp-c-final-affected-26` 首次 UTC 15:00:48–15:01:07 的五份 fresh XML／终态已归档同名协调器目录：真实 HTTP／来源路由三类 20 项、ReaderArchitectureGuardTest 三项和 production delegates 一项通过，共 24；通用 manifest／reader protection 两项失败，均因 ID 43 的原 Android 保护方法改成 expression helper 委托，证据解析器不能读取其直接行为 body。方法名称及原 helper 行为还在，不能借此删除保护、放宽 manifest 或插入符号字符串。全部 69 个本任务 Kotlin 的限定 Check 通过；剩两契约将通过恢复原命名方法的真实直接行为主体，并定向复验原匹配／replacement 两行为来关闭。24 项可复用，不把后续局部复验称最终代码重跑全部 26。SP01／DP02 仍未勾选、未提交，未提前全量或正式构建。

证据契约窄修已关闭：原 Android protected matching 方法恢复完整实际 runBlocking 主体，replacement 用本测试实例默认 false 的 flag 选择既有 recreate 分支，finally 还原；不复制主体、不改 production／Contract／manifest、不插入无意义 marker。`sp-c-protection-evidence-repair-four` PASSED 47300／43664；实际两行为 fresh XML UTC 15:11:32.498、两失败治理方法 fresh XML 15:11:46.969，分别 2 项／零 failure／error／skip，受影响文件 Apply／Check 通过。Root 独读真实主体、flag 默认／finally、两 XML／终态及原 24 的五份 XML，签收最终 26 的有效组合证据；它不是最终源码重新执行全部 26，两个真实行为另计为必要定向复验。原三方法保护和 manifest 状态未被弱化。

采样数据进一步核对：Root 将 correct-observer 的原落盘前缀和同 PID 929 的 ring 按 repeat／condition／scene 合并，取得 41 个唯一原样本，末尾 nativeHeap／nativeWork／optionalWork／manualBaseline 等字段齐全，最大行 1,188 字符。缺段和最后一行截断来自采集文件缓冲尾，不支持“Android logcat 单行上限”结论；撤回必须新增帧日志分块机制的要求，没有因此引入 logger 框架。41 是失败前有效数据，不是完整 72，也不把未测 31 补为通过。

新增取证的限定单场景：首 test APK 构建仅缺 ReaderPage import／零业务执行，修正后 `sp-c-native-performance-donor-error-diagnostic-apk2` PASSED 43316／32592、限定格式通过；测试 SHA `d8595082a498c16d8c1031993cd260c3eb225ded82c987339a5df63fec0a4d57`，production Debug 保持 `2d9f…`。`sp-c-native-performance-donor-error-diagnostic` 在 PID 5162、Time 6.298 秒、OK (1 test)，原 300／suspended donor 的真实 Ready／PORTRAIT／normal 与 native idle 前置成立，没有原 onError 异常；目标 199／context 0，Ready→paint 14.083ms、6 请求／3,064,618 bytes、native peak 4／optional peak 1。Root 独核构建／ART 终态；单次未复现不能关闭旧错误。

下一次完整对照的目的与边界：单场景无法复现前述长序列 donor Error，因此只追加一次原 4×6×3 的长序列运行，所有 suspended donor 启用现有 errorsOnly／非空原 error callback 取证，失败现场记录实际 page.status／errorPages／error_message／busy；相比上轮可取得此前缺失的具体异常，不是猜测 production 修复后刷绿。预计 5–10 分钟，产品／测试 APK 不重建、不新增 client／registry／框架；终态补 ring 并核完整落盘字段，失败立即停止该轮。若通过，只签开发对照，历史三条失败仍分别分类，不宣称 production BUG 已修复。此前定向诊断已超过 30–60 分钟估算，实际约 70 分钟时已向用户说明；下一段仍原 S01–S16 必要验收，整体预测约 28 小时。SP01／DP02 不提前勾选／提交，不启动全量或正式构建。

该次 `sp-c-native-development-performance-donor-observer-final` 已终态：实际 PID 5691、Time 68.551 秒、一失败、失败前十个完整样本；production／test APK 分别保持 `2d9f…`／`d859…`，不是完整 72 通过。repeat 0／On／cold-jump-reverse 的源 19 确有原 `Page.State.Error(IOException("Chapter cache rejected the image write"))`，真实栈为 `AndroidReaderMaterializePorts.kt:98` → `AndroidReaderEncodedPageStore.kt:73`，原 RIGHT errorPages=19 和 UI 消息一致，尚无对应 native 图／lock／draw；失败取证时 normal=false、nativeBusy=0、optionalBusy=0。原 HTTP 两次 200／517,837 bytes，缓存与响应 SHA `f8ec5de0f211bb0166e268bc7177f1356667fcf7ed920d1028e689b2f6a667cb` 一致，只证明已有正确字节，不能证明该 fetch 成功。原日志 `app-desktop/tmp/sp-c-native-development-performance-donor-observer-final.log` 保留完整 Throwable，geometry／ring／errors 同前缀保留；自有 logcat collector 已停止，无 Gradle／ART／采集在途。

Root 独读现场和原 ChapterCache，确认此次直接错误为 `putImageToCache` 返回 false，不是已经证实的 JNI 解码失败；false 可来自同 key 的 `diskCache.edit` 未取得 editor，或 commit 后 `isImageInCache` 失败，现场尚不能区分。原夹具 cold 在 finish 后直接删除 `.0` 文件，没有通过 ChapterCache journal API；是否与未完成真实物理工作构成此次原因仍是待证假设，不能以当前 idle 快照反推清理边界已经 idle。复用同一实施者，先做该路径的原测试定向观察，预计 20–40 分钟；暂停性能矩阵重跑及猜测性 production 修改。若证夹具隔离问题只修夹具，若证真实存储并发则先核定最小反例和原存储测试边界，不新增缓存系统／owner／调度机制。Native6 拒画及旧 donor Error 没有被此栈自动解释；SP01／DP02 仍未勾选、未提交，最终全量／正式构建未启动。

缓存支路定向取证（不重跑矩阵）：仅在原 HostReaderArtFixture 增加 opt-in 的原 ResponseBody／ForwardingSource 读取与关闭计数、原 ChapterCache 实例和 monitor 下的指定 key editor／活动 editor 快照，以及 cold 的 finish 前、raw 删除前后真实 processPhysicalWorkCount／native 计数；不调用 cache get／edit、不写 journal，不改读入字节、close、原 finish／清理时序。失败现场另外核实际 HttpPageLoader.chapterCache 与观察实例相同。该观测含短同步区间和日志，可能改变竞态时序；未复现不能消除原错误。

`sp-c-native-cache-branch-diagnostic-apk` 48552／42056 和补指定 keyEditor 的 `…-apk2` 48160／16232 均 PASSED、限定 Apply／Check 通过；仅安装测试 APK，最终 SHA `7ae77d1db0c868f99725ed68f0dfdab130b3593f9b7458f3e070e7400833ba57`，production Debug 保持 `2d9f…`。原 On／cold-jump-reverse 定向三次在 PID 6510、UTC 15:56:28–38、Time 11.886 秒／OK (1 test)，取得三个完整样本。三次 cold 各三个边界均 physical normal=0、native=0、editor 空；源 19 对应 key `e5102d094f3529194febfa459da2c556` 的三个原响应都完整读入 517,837 bytes，没有原 Storage 错误或该响应 read0。source.close 在原 saveTo 内、commit 之前，故此时 editor 仍 active／file=false 是正常写入中状态，不能据此宣称写入失败。原 raw／ring 日志为 `app-desktop/tmp/sp-c-native-cache-branch-diagnostic{,-geometry,-ring}.log`。

只补原失败的直接相邻前置：原测试 scene 参数支持逗号选择已有场景，执行 On／rapid-keys→cold-jump-reverse 各一次，保原 three-key 真实输入、finish、仅 optionalWork=0 等待和 raw cold。`sp-c-native-cache-adjacent-diagnostic-apk` PASSED 7128／44836、限定 Apply／Check 通过；测试 SHA `d2fcf60a786d6ecf8bf7b459f11f3081650320efdc5432818f903df22f8549d8`。PID 6814、UTC 16:02:13–19、Time 8.101 秒／OK (1 test)，两份完整样本，两个 cold 三边界仍 normal／native=0、editor 空，源 19 完整读入 517,837 bytes，无 Storage 错误。rapid 中 request5 对 key `95feaa57449e9b1f10eb4d3dd3b83a13` 完整读入 603,912 bytes，close 时 normal=2、该 editor 尚 active；紧随 request6 同 key close 时 read0／expected603,912、file=true、editor 已 null。这是实际重叠响应线索，但没有对应原 IOException，不能只凭 read0 判 edit-null 或断定原失败原因；取消／store session 检查也可能在读之前结束。原 raw／ring 日志为 `app-desktop/tmp/sp-c-native-cache-adjacent-diagnostic{,-geometry,-ring}.log`。两次自有采集器均仅按核对 PID／路径停止，ART／Gradle 均终态，没有继续刷次数／矩阵或修改 production。原 Native4／Native6／donor Error 和完整 72 验收仍未关闭；最终 26 组合证据继续复用。

夹具 cold 协议的确定性红绿（不是 production BUG 修复）：只在原 Native 类新增 `coldFixtureRemovesTheRealImageFromOriginalCacheJournal`，经原 source19 HTTP／JNI／独立原图 PixelCopy 先确认真实图片，再读取原 diskCache.size／本次 key journal 成员，调用真实 Host.cold 后核该 key 移除。`sp-c-native-cold-protocol-red-apk` PASSED 47772／47976，测试 SHA `ac1a16e058d2d1cf8d5fe99a503f9aaa57644ce80df8c787f7513add66934264`。实际 PID 7121、Time 4.028 秒／一失败，正确红为原 raw cold 后本次 key 仍在 journal，bytes 22,159,610→22,159,610、entryBefore=true／entryAfter=true；不是编译／空过滤或旧图片前置失败。raw 与 ring 取证见 `app-desktop/tmp/sp-c-native-cold-protocol-red{,-geometry}.log`。

最小绿仅改原 Host.cold：先正常 finish，再在测试端有界等待原 shared Runner 的实际 normal 计数与 ReaderPageImageView 实际 native 计数均为零，随后调用现有 `ChapterCache.clear()`，核当前实际 `.0` 文件全清、清理前有物理文件的 key 从 journal 移除。没有等待 optionalWork=0，没有改 SQL／manual／偏好、原 gate 或 production；opening 计时仍在 cold 之后，因此不将前置隔离等待混入 Ready→paint。公开 clear 仅清现存文件对应记录，之前 raw 删除已留下的无文件历史 orphan 不迁移、不强求全 size=0。`sp-c-native-cold-protocol-green-apk` PASSED 24356／32308、限定 Apply／Check 通过，测试 SHA `cbd6007fb2950e00d90243c859bd65c9bafb3aee8fe8ea9d8e322a5b09fce574`，production Debug 仍 `2d9f…`。实际协议 PID 7301、Time 3.687 秒／OK (1 test)，源19 key 从 journal 移除、bytes 22,159,610→19,593,299，当前物理 `.0` 全清、normal／native=0；保留的历史 orphan 已明确记录。

同 green 测试包关联验证串行完成：原 On／rapid→cold-reverse 两场景 PID 7455、Time 7.976 秒／OK (1 test)，两完整样本；原 Suspended／300 一场景 PID 7657、Time 6.431 秒／OK (1 test)，原 donor 真 Ready／PORTRAIT／foreground idle 成立，正常源199实际 PixelCopy 后仍断言 old optionalWork=1 和原 release latch=1，之后才按原 finally release，任务真实退出为零。公开 clear 没有借等待 matcher 清零取消该压力条件。三场景含真实普通输入／原图解码与像素，不能替代完整72。日志为 `app-desktop/tmp/sp-c-native-cold-protocol-green.log`、`…-adjacent-green.log`、`…-suspended-green.log`，同 `…-green-geometry.log`／`…-green-ring.log` 按各 PID 绑定，collector 45600 已仅停止自身。夹具协议窄修和相关检验均完成；没有证据把原 source19 Storage 错误、Native6 拒画或旧 donor Error 的根因归于该协议，本次不宣称三条 production 风险已修复。SP01／DP02、完整性能及正式验收仍待，不自行再开矩阵。

#### C 合法冷协议下的写入失败与原适配器修复

首次合法 cold 协议的完整尝试 `sp-c-native-development-performance-cold-protocol-final` 为 PID 7850、Time 132.966 秒／一失败：repeat 0 四条件六场景共 24 份，repeat 1／Off 前四场景共 4 份，合计 28 份完整样本；不是完整 72。repeat 1／Off／cold-jump-reverse 的源 19 仍有原 Ports98→Store73 的 Storage IOException、原 Error UI、无 native 图／lock／draw；两个原 HTTP 200／517,837 bytes 与缓存 SHA 正确。匹配关闭也会失败，公开 clear 修正没有消除它，不能归为 matcher 开启或宣称夹具修复等于生产故障修复。原 raw／geometry／ring／errors 全保留，collector 40632 已只停止自身，未继续相同矩阵。

在既有 HttpPageLoaderIntegrationTest 建立真实 MWS＋ChapterCache 反例：原响应第一次 read 时已经持有同 key editor，公开 onPageSelected 重定位产生当前 generation，第二响应未 read 就收到原 Storage IOException。首断言要求 held 状态 Ready 过强，保留其 trace 后修正为“写入忙期间不能变 Error，放行原普通 IO 后当前 Ready 和原 bytes”；`sp-c-cache-generation-red2` fresh UTC 16:42:40.038，1 项／1 正确行为失败、零 error／skip。cache／fetch／scheduler 的 HEAD 代码只读对照保留，未实际执行 HEAD，不能冒称基线实测或证明旧原生异常的唯一原因。未使用正常 reader VM／matcher／optional consumer，不将慢恢复当该反例根因。

不采用共享 Runner 等待旧同页 job 整体退出：原 `late failure from old generation cannot replace ready content of the same page` 要求旧 source 尚未返回时，新 generation 已 Ready，之后旧失败不覆盖。修复限于 AndroidReaderEncodedPageStore 原实例内的同 ref writer＋commit／cleanup 租用，HTTP／encoded 读取／matcher 在外，不同 ref 并行；cancel-before-enter 不 writer／不删他人字节，endSession 不提前放开旧租用，引用真实退出归零后才移除。原 Store 四项新增保护的 `sp-c-store-ref-writer-red` 为 4 项／3 正确红、1 不同 ref 基线绿；覆盖 cleanup 竞争、等待取消及会话关闭。

首次相关绿 22 项中 20 通过、2 测试宿主失败保留：普通 JVM 实际 Android Log.isLoggable 未 mock，引发 metadata initializer 错误，新 HTTP 方法迁入既有 ReaderPageFactProductionWiringTest Robolectric，保真实原缓存／header／公开 Loader；取消等待的后续同 ref 验证限定原 payload／writer 进入，不扩成同 URL 内容更新。原 DiskLruCache 2.0.2 javap 显示 dirty.renameTo(clean) 返回值被忽略，Windows 宿主实际重写仍保旧字节；不据此修改产品 cache。新响应所有权反例在原 store 两个调用者、真实当前 job 取消完成的前置下，HTTP 正文未读且原响应未 close，`sp-c-store-ready-and-response-close-red` 实跑 3 项／2 绿、1 正确资源红。最小 AndroidReaderPageFetchPort originalResponse.use 包住 permit／store 等待；strict mocks 只补必要 close stub，不放松原行为断言。

`sp-c-store-ref-writer-response-green` 的 Store12＋原 Loader9＋Robo2＝23 项通过。首重构只因新注释缺空行在 11 秒内格式失败、零业务执行；修正后 `sp-c-store-ref-writer-refactor-33-2` PASSED UTC 17:12:28.712，六份 fresh XML 为 Store12、ChapterLoaderStorageClassification3、HttpPageLoader9、HttpReaderMaterializeAdapter4、ReaderMaterializeProductionWiring3、ReaderPageFactProductionWiring2＝33，时间窗 17:11:56.543–17:12:18.062，零 failure／error／skip，8 文件限定格式通过；XML 保存在同名协调器目录。Root 独读两 production、实际 gate／writer／cleanup／Response.cancel 路径和六 XML，完成同一轮写入／资源窄段独审。跨 loader 同 URL 不在实例锁覆盖内；历史 Native4／Native6／donor 与新 Off Storage 是否属于本分支仍没有唯一因果证据，不宣称全面根治。必要开发 APK／原生定向和完整性能仍待。

范围更新：当前实际 84 文件＝34 production、43 tests、6 docs、1 manifest。相对上一 76，增加的是两原 Android 编码／fetch 适配器及六原测试文件；修复与 S16 的真实重定位、物理写入／cleanup 和取消响应所有权内聚。未新增全局缓存 owner／HTTP client／队列／持久格式、产品菜单或独立 benchmark；继续同一实施者和同轮独审，无最终全量／正式构建／版本递增。原冻结 26 的 MWS4／Loader9 已被最新 33 定向复验覆盖，其余未受影响结果复用，不称重新运行完整 26。SP01／DP02 继续未勾选、未提交。

#### C 新写入修复包的原生拒画与定向诊断

开发统一入口 Debug 构建 PASSED UTC 17:22:22.597，Main SHA-256 `18330c8fcd247de7f58b68ff21832ea4149bb00b3441c6b4c63071eccba5ee6b`，实际文件为 `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-57e03e745266/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`；Root 独立核存在及 hash。对应 androidTest SHA `874ea2fbb348e0e013d4f0f1c6810b96b32d9d2222edb0b9771022c82157e9e6`，两包均沿用原 Debug 证书，仅安装至本任务 serial 5586；不递增正式版本，不将旧 `2d9f…` 的结果记为新产品验收。

原计划四份定向样本在第一场景即停止：`sp-c-store-ref-writer-native-on.log` 为 PID 10523、Time 37.858 秒、1 项实跑／1 失败，On／rapid-keys 的目标源3未取得实际绘制和25点 PixelCopy；完整性能 row 为0，Off／cold、Suspended／300及72均未继续。实际 selected=10 是反向 adapter 的 ViewPager 索引，其项为[2RIGHT,3LEFT]，不是源10导航错位。源2 Queue、源3 Ready、ticket seq3／context0／serial7，lock／draw空、normal／native／optional实际计数0；无 Page.Error／Storage IOException。现场仅有一个 native ready/base child，但旧快照没有记录该 child 的 page 身份，不能据源3 Ready 推出源3原生已解码。Queue 也不表示 shared fact 必为UNKNOWN；三层绘制门禁的实际拒绝点未证实。geometry及同PID ring保留，自有 collector12480已停止；前述同 ref 写入和响应取消修复不等于本拒画问题已关闭。

只复用已装包和现有透明 `performanceDiagnostic=true`，单次 On／rapid-keys 诊断为 PID10792、Time5.805秒／OK(1 test)，1份完整 row，日志 `app-desktop/tmp/sp-c-store-ref-writer-rapid-diagnostic{,-geometry,-ring}.log`。真实源3 child identity104711299，boundContent／boundEncoded均4:0，fact3 PORTRAIT1130×1596，原 beforeDraw 两次 true，最终 lock／draw[4,3]，强 PixelCopy通过；实际为[3RIGHT,4LEFT]／serial15，而非旧失败[2RIGHT,3LEFT]。本次没有源2 child／fact的失败现场，且透明诊断会改变时序，不能由此关闭旧失败或组合成性能矩阵通过。观测 Ready→paint源0为25.51ms，input→child619ms／PixelCopy上界765ms，13请求／7,482,403bytes，均只属于这份带诊断样本。自有 collector16360已停止，无新production修改或构建；下一步限原接线的最小反例，不移除未知前页保护、不放宽像素或超时标准。

#### C 未承诺提案导航的正确反例与共享修复

仅扩展原 SharedReaderResourceNativeAcceptanceTest，复用原 B2 HTTP／JNI／SQL 和公开三次 LEFT：HTTP 门闩保持源2事实真实UNKNOWN、源3另行放行，context0／设置不变，要求3右槽真实绘制及25点 PixelCopy，之后仍断2 UNKNOWN／非Ready。首构建参数拆分和诊断 helper 作用域编译失败均零业务，不算红；限定修正后的测试包955fbb4a…60493在 Main18330 上运行 PID11066、Time35.823秒，一失败，仅因 SQL==3 过强。真实3／4已画、目标3右槽像素通过；只读数据库＋WAL确证 chapter15652.last_page_read=4／events559，与 frame15.drawn[3,4]一致。改测真实目标frame绘制集合最大页码，要求含3、无未画2，未改变产品进度策略。

同向量复验测试包 `2cd57f94150f137fc248ac5672d4a0c9f910d6216574f3c8787c7b2952c4c11d` 在原 Main18330 上，PID11337、Time35.043秒／1真实目标绘制失败，尚未进入进度断言。日志 `app-desktop/tmp/sp-c-rapid-unknown-progress-native{,-geometry,-ring}.log`：UTC17:55:11.498 原 beforeDraw 返回false，源3原生Ready／baseReady且bound／encoded／content均4:0；失败现场17:55:41.572事实2 UNKNOWN、3 PORTRAIT1130×1596，真实source3 child可见 Rect(0,819–540,1581)，有效frame7／context0仍[2RIGHT,3LEFT]，lock／draw空。UNKNOWN来自实际owner事实，不由Queue推断。此向量重现旧拒画signature，前页未知门禁正确拒绝错误LEFT；根因在导航复用了空提案且refresh仍从旧origin2决策，不是解除未知宽图保护。门闩30秒有界等待与失败后状态分别记录，不把 latch.count=1 单独当成整个等待期未超时的证明。两个自有collector42916／40080已仅停止自身，未追加原生矩阵。

原 StableContract 的公开Session反例：goTo2／capture(empty preparing)→navigate3→实际fact3 PORTRAIT→capture3，要求RIGHT、prepare／draw3成功且旧ticket不得补2；另保护prepare锁2但未draw的组在navigate3时保持原frame。`sp-c-unlocked-proposal-shared-red` fresh UTC17:58:41.405，2项／1正确失败 expected RIGHT actual LEFT，1已锁保护绿，零error／skip；XML在同key目录归档。

最小 production delta仅原 ReaderDualPagePresentationOwner.move：incoming排除 target不同于origin且locked／drawn皆空的提案，旧record沿原离场取消appendable，由新目标决策。已锁／已画判定、known(anchor)绘制门禁、数据格式和平台入口不变。`sp-c-unlocked-proposal-shared-green` PASSED56秒，四fresh XML共130项：Stable34＋Session31各JVM／AndroidDebug，UTC18:00:39.134／.284及18:00:52.119／.237，零failure／error／skip，三文件限定格式通过；归档在同key的jvmTest／testDebugUnitTest。Root独读move／原prepare门禁、新契约及四XML，完成同一失败路径窄审。后续仅Desktop三原接线类、更新开发包和同Native向量验证；未运行完整模块／72／正式构建，未提交，SP01／DP02仍待。实际文件数仍84，没有增加产品能力或实现文件；该共享导航修复直接保护S01／S06／S08／S14，不宣称所有历史Native异常已解释。

该修复后续平台窄验已完成：`sp-c-unlocked-proposal-desktop-refactor` PASSED38秒，三fresh XML共62＝ReaderPageTurnPresentation40＋SharedDualPageDesktopWiring5＋SharedDualPageNativeStablePresentation17，UTC18:04:44.983–18:04:59.231，零failure／error／skip，不启性能env。原统一Debug27秒通过，产品SHA `fdbd5b6a3a25ec6cccabe4991568f4de885b220bb87c93d4658b78d07749bc81`，实际文件 `app/artifacts/android/0.19.4-aex.22-vc40-9fe50edf6e-debug-15254212b98b/Mihon-Fork-0.19.4-aex.22-vc40-debug-universal.apk`，Root独核存在及hash；testAPK25秒通过，SHA `99ab1f1f4fd9d97e90a5cbd1fdf761d0f6f392995ec449c2792a68b028a1b77e`，仅本任务5586两包更新、原开发版本／证书不变。

同原Native反例绿色为 PID11740、Time7.02秒／OK(1 test)，日志 `app-desktop/tmp/sp-c-unlocked-proposal-native-green{,-geometry,-ring}.log`：源3原beforeDraw实际true，context0／frame16为[3RIGHT,4LEFT]，lock／draw[3,4]，25点PixelCopy及实际drawmax SQL通过；目标pixels后仍断源2UNKNOWN、非Ready且release未放行，不以已ready前页偷换未知条件。Root独读同PID原回调／frame／像素测试硬断言并签收该拒画分支修复，自有collector44488已停止。原写入33未相关改动可复用；完整性能仍待，不据此勾选SP01。共享导航改变影响Desktop快速输入／冷反翻，下一补验只复用原scene filter各12个配对样本，其他未受影响场景保留有效证据；Android新包先原四份定向，再核当前完整对照，未提前正式矩阵。

#### C 导航修复后的平台补验与约束恢复

同一产品 `fdbd5b6a…bc81`／测试 `99ab1f1f…1b77e` 的四份Android开发样本全部通过：On rapid＋cold两份为PID11943／10.725秒，Off cold为PID12161／6.021秒，Suspended 300为PID12313／8.328秒，均为OK(1 test)。原日志依次为 `app-desktop/tmp/sp-c-unlocked-proposal-native-{on-two,off-cold,suspended-300}{,-geometry,-ring}.log`；正式计时关闭performanceDiagnostic／cacheDiagnostic，donor原非空错误回调只透明转发。Ready→真实paint为7.09／11.23／14.03／6.89ms；Off反向复用RenderNode，childDraw记不适用，独立PixelCopy观测上界363ms，不能记0。On rapid输入→child约681ms／PixelCopy上界832ms。实际optional峰On1／Off0／Suspended1；Suspended在源199正确pixels后仍有实际旧工作1，随后finally release并核90秒门闩未超时、真实工作归零，不仅检查门闩count。Root独读stdout、同PID几何及上述硬断言，签收该四份定向开发证据；不是完整72或正式R8验收。

Desktop只补共享导航变更涉及的rapid-keys／cold-jump-reverse，各四条件×三次＝12份。`sp-c-unlocked-proposal-desktop-perf-rapid2` PASSED，fresh XML UTC18:16:02.641，方法14.925秒；`sp-c-unlocked-proposal-desktop-perf-cold` PASSED，fresh XML UTC18:17:15.822，方法7.539秒。两份XML均1实跑方法、12完整样本、零failure／error／skip，Root独立读取原XML及原Ready／paint时间。首rapid只因未设置原图目录环境变量、零样本失败；补环境后仅重跑原范围，无源码变化。冷反向所有样本为四个正常请求／2,162,984bytes；快速输入真实普通预取按时序请求5–8页，不能写成On／Off网络请求完全相等。rapid首Off有420ms单render峰，On三次Ready→paint约28–34ms，冷反向On約33ms；这些是开发离屏调用与观测，不是原生vsync或正式体验保证。当前24替换旧72中同两场景，另外四场景48及原300诊断12未受此空提案导航修改影响，沿用已有有效证据；不得称最终源码fresh完整72重新通过。

2026-10-05审计约束已由 `f94729a957` 单独提交。本轮恢复保守沿用北京时间02:37:19作为SP01剩余60分钟检查点起点，到03:37:19仍未签收时停止相关追加并报告缺口，不因再恢复代理重置。剩余问题限定S01／S06／S08／S14／S16：冻结现有源码和开发包，Android仅启动原固定72对照一次；失败即停后续场景，先保留样本及最小失败类别，不自动整组重跑。主代理核原证据与同轮尚欠签收、维护文档及提交，不重做A/B泛审。现有Gradle仅空闲daemon／Kotlin daemon，本任务AVD5586存活，无在途Gradle worker／ART／logcat；恢复同一实施代理，不新增代理。SP01／DP02继续未勾选，全量及正式构建尚未启动。

#### SP01签收映射

以下映射只归纳上文实际执行、独审及组合证据，不新建状态权威，也不把开发宿主提升为S17正式产物验收。Root本轮重新核原A两平台各八XML／100项、B Android三XML／93项，以及最新共享四XML／130项、Desktop三XML／62项、写入六XML／33项和保护补验两XML／4项，均零failure／error／skip。其中旧A/B绿色按相关修改影响复用，不能相加宣称最终源码重新执行了全部方法；原正确红因、宿主失败分类、生产入口和局限仍以上文各批次为准。

| 验收ID | 已核开发实现／证据 | 当前边界 |
|---|---|---|
| S01 无matcher可读 | StablePresentation／FactDemand及真实两端资源wiring；JNI目标pixels在旧optional仍运行时通过；最新空提案导航反例 | 本次Android完整72开发对照已签收；不由模型绿推导发布体验 |
| S02 伙伴迟到／失败 | Android StableNative／Holder、Desktop NativeStable真实逐槽几何及原Retry；晚宽图另屏FULL | 已核开发；不将底层Ready重载当新增UI入口 |
| S03 绘制截止 | StablePresentation事件交错／prepare锁与真实draw回执；两端首prepare和原图连续帧 | 已核开发；observer调用原方法并保返回值 |
| S04 竞争／预留 | StablePresentation／Rules／SpreadMatching实际选择器、同分与未知竞争／预留不重叠 | 已核开发；启发式分数不当真实标签，迟到漏识别与完整资料选错分开 |
| S05 已显示组保护 | Session／StablePresentation历史占用；ExposureNative真实拖动邻屏及Desktop稳定挂载 | 已核开发；显式手调按新上下文处理 |
| S06 未画伙伴导航 | PageTurn／Runtime actual-anchor＋SQL、SourceNavigationNative；最新unknown前页三次真实LEFT／pixels／drawmax SQL | 定向及本次Android完整快速输入对照已通过 |
| S07 会话历史 | 共享历史与唯一归属契约；B1原图真实回翻、模型／SQL停稳 | 已核开发；没有历史时不保证与顺读分组相同 |
| S08 冷跳／反向未知 | 共享两种局部反例、原B1 17→16→17、最新空提案RIGHT及旧票据拒绝 | 定向及本次完整冷反向对照已通过 |
| S09 B1原17图 | 原CBZ／WebP bytes经真实两端来源、15/16及时／不及时及源17直跳／暖回翻、首帧和12帧几何 | 已核开发；实际SHA／原图数量保留，不用尺寸生成图替代原图 |
| S10 B2原22图 | 原JPEG bytes、2已知LEFT／未知RIGHT、20已知FULL／未知RIGHT保持、广告22可达 | 已核开发；早到／晚到前置由实际事实及原prepare记录 |
| S11 类型／方向 | Orientation／PortraitSingle／Rules契约、Android JNI EXIF1–8×三设置、Desktop真实Skia迟到EXIF | 已核开发；JNI24是Single真实共享View方向验证，LTR共享镜像与Desktop入口分开 |
| S12 被动零位移 | 两端原View／Compose实际frame、mount、Rect、scale／pan及连续pixels；ContentLease真实旧图保留 | 已核开发；枚举、首尾PNG与Test Mode状态不替代逐帧几何 |
| S13 手调／保存 | Android与Desktop原按钮事件、真实repository／SQL、慢恢复、CAS／失败Retry、保存重开组合37及native慢记录 | 已核开发；未知旧记录未恢复不能覆盖，合法兜底仍可手调 |
| S14 上下文失效 | 最新Session／Stable130、ContentLease／History／Single原生、方向／开关／resize／内容版本与旧ticket回调 | 已核开发；最新空提案修复保已锁／已画组，完整输入对照通过 |
| S15 真实进度 | ProgressProduction37、Runtime实际已画anchor／SQL、HistoryNative、drawmax追加／离屏撤销 | 已核开发；读取最大实际绘制页，预取／UNKNOWN占位不计读 |
| S16 来源与资源 | 原ChapterContentRoute7、MWS／Loader异常与Retry、Store／HTTP33、Runner两平台物理完成、实际SSIV／Coil／系统trim及Desktop内存账本 | 开发资源及本次完整72已核；不将逻辑cancel当物理退出 |
| S17 正式三端 | 尚未启动本次正式矩阵／构建／运行 | DP02待验；旧正式70与开发Debug不能代替 |

当前没有新生产修改／新测试编写，既有保护补验与限定格式证据继续有效，不为此映射新增测试或再跑整类。

#### SP01当前Android完整开发对照与末段签收

本约束后的唯一完整对照使用前述同一开发产品／测试SHA，专属AVD5586、原B2 archive／payload及原正常reader链。精确方法 `SharedReaderResourceNativeAcceptanceTest.originalPayloadFourConditionsSixScenesHaveNoMatchingDependencyWait`，不设置condition／scene／repeat过滤，performanceDiagnostic／cacheDiagnostic默认false，仅donorDiagnostic=true透明记录原非空错误回调。实际ART PID12738，UTC18:48:08.613开始、18:52:52.915测试完成、.961 runner结束，stdout Time284.351秒／OK(1 test)、CODE0及最终-1，PTY18457自然exit0。日志 `app-desktop/tmp/sp-c-resume-current-native72{,-geometry,-ring}.log`；owned collector37956核SDK路径及5586参数后已停止，无在途Gradle／ART／自有logcat。

Root独读实际stdout／geometry、原测试硬断言及代理结构化回执；四条件×六场景×三次恰为72唯一casekey，25项必需字段齐全，同PID ring核尾。没有本轮FAILED、StorageIOException或anchor异常。全部Suspended18项实际旧optional仍为1且源pixels已正确，放行后归零、90秒门闩未超时；On18项中16项实际optional峰1／2项0，正常busy窗口不强行启动。压力18项先真trim→On／targetRelease时pressure=true、optional0；原MemoryInfo均lowMemory=false，normal idle可合法解除压力后启动1项，所有18份sceneFinally均actualOptional0。包含旧已运行工作，不据峰1认定受压时新准入；不冒称持续系统低内存或OOM。

逐场景三次波动保留，不用平均隐藏等待：72的目标Ready→paint最大为Off rapid43.03ms，On rapid6.56／2.98／12.08ms；On rapid输入→实际child为347.31／456.42／399.78ms、独立PixelCopy上界467.61／555.65／493.16ms，Off对应child362.62／332.92／420.67ms、PixelCopy455.55／446.90／503.26ms。原RenderNode复用没有新childDraw时明确不适用。On cold反向PixelCopy518.60／339.54／256.00ms，Off373.75／420.10／302.95ms，没有将单次差值都归因matcher。真实请求／bytes随正常窗口时序波动，不称IO完全相等；Java／native观测峰最大120,142,704／102,241,296B，不是全进程高水位。300仍为22原payload循环成300独立源身份、冷跳199，manual baseline与22页场景分别为[]／[1]。

结合无等待结构、真实物理完成／准入及原生pixels证据，本轮开发对照签收；数学零CPU开销、真实硬件掉帧和正式发布体验仍须DP02复核。历史Native4／Native6／Storage原失败和观测／冷夹具问题均保留，已由正确红确认的同ref写入／取消响应与空提案拒画各按实际反例修复；当前完整合法场景复验通过，不声称所有历史异常具有唯一同根。

同一独审的末段由Root完成：S01–S16按上表及上文真实生产链／原图／SQL／几何／资源结果满足开发门槛，已定位产品失败均有正确红、最小修复、关联绿与必要复验，当前无未关闭的实施验收失败。冻结批次采用原26首次结果＋保护2项补验的组合，MWS／Loader相关部分再由33覆盖，最新共享130／Desktop62／Android本72覆盖最后导航影响；不称最终代码fresh重新全跑上述全集。manifest仅新增原共享owner的生产映射及当前引用行，历史status／固定原图／保护方法未提升或删除。84文件内聚于原共享呈现、双端接线、资源／操作及对应验收，规模和成本已在审计中如实承认；未新增独立产品能力。维护文档同步后随SP01功能批次统一提交，DP02仍未完成，不提前启动其测试或构建。

本次恢复仅一个修复批次：产品实现0／测试或夹具修改0／开发构建0，Android instrumentation1次（4分44秒），主代理完成既有证据复核／维护文档与提交。SP01本段保守计时从02:37:19起，签收发生于03:37:19检查点前；具体截止及提交hash由Git／后续正式交付证据追溯。实现与测试编写没有历史分项计时，仍不倒算其占比；无外部等待扣除、无新的统计系统。

### DP02 v3首次最终矩阵与剩余门禁

SP01功能提交为 `dec006e7d37d31f78bad5edc3188b228530f2e81`，84文件＝34production／43tests／6docs／1manifest，13,649新增／1,501删除。提交后worktree clean；SP01开发门禁勾选不代表最终矩阵或正式交付通过。DP02五小时检查点从北京时间2026-10-05 02:59:00至07:59:00；不重置旧验证预算、不将正常工具等待剔除。Root执行本地唯一协调器重型工作，同一实施者负责Mac隔离前置；后续发现相关失败时复用该实施者优先修复，暂停正式构建。

Android统一入口 `check --signing` 实际通过：JDK21.0.11、SDK36／Build Tools36.0.0、原release证书已验证、versionCode仍40／aex.22，源码为干净SP01提交、productionInputsSHA `d540fc3f37697a3a46cad033d3f355d090eb017c71acf79c115c7c526173996f`。只读预检不分配版本、不构建、不安装。Mac14.8.4／Temurin21.0.10+7实核，Data约3.4–3.5GiB可用；新隔离根 `/Users/altair/Library/Caches/mihon-shared-dual-page-v3-20261005-dec006e/` 已有同commit的clean源码／B1/B2原SHA输入，AppVersion70。旧三个应用实例和旧dirty checkout保护，未正式构建／递增版本／启动新应用。

#### Desktop首次完整结果：失败保留，禁止自动重跑

命令为协调器 `dp02-v3-desktop-full` 包装项目 `scripts/build-desktop.sh full-tests`，实际Windows分派 `build-windows.ps1 -TestOnly -FullTests`，integration=true，版本不变；会话设置单worker／不并行，未启可选开发benchmark环境。第一次分离启动命令被自动审批拒绝，零执行，随后同key改run才实际启动，不能算两次全量。worker13168／process43004，UTC19:00:33.423–19:06:55.903自然FAILED，Gradle BUILD FAILED in6m20s。440份原XML完整保存在 `.gradle-coordinator/dp02-v3-desktop-full/`，原日志同key `.log`；总3294tests／35failures／0errors／10skips。没有把原XML通过部分覆盖或把测试失败记为全部通过。

| 失败组 | 首次实际证据／初步分类 | 处理边界 |
|---|---|---|
| DualPageCurrentPageWiring | 多项NoSuchFieldException issuedViewport发生于旧诊断helper；另有旧prefix reflow、旧[1,2]配对与手调预期失败 | 先恢复真实诊断并逐项对照v3，不删除真实logicaltarget／pixels／进度断言；私有旧字段失败不是产品红 |
| SessionIntegration／AdaptiveViewport／DefaultMode | 旧[1,2]／[2,1]与实际[2,3]／[3,2]不符 | 判断是设计明确冷起点变化还是production导航／进度回归，不先改期望刷绿 |
| RuntimeFactory三项 | 实际header两lease入飞仅[6]、冷反向optional未进入、B2普通holder前dimensions未确认 | 区分正常metadata与可选样本及真实模式／busy前置；核与Mac原图前置的关联 |
| ReaderScreenModel两项 | 重复settle次数预期1实际0；旧canonical viewport源页与当前真实双页不同 | 保真实prepare／draw／settle与用户原因，不用直接伪读代替 |
| Pipeline generation fence | decodeReturned后disposer预期1实际0 | 只读核是否把decoder返回错误当物理finally完成；不因此放松释放／资源归零要求 |
| PartialRace目录发布 | Windows Files.move实际AccessDeniedException | 当前独立lease／目录链是否受本轮改动影响仍待分类；不猜改文件协议或延长超时 |
| 非reader候选无关项 | GeneratedDependencyNotices／AboutUpdate两项199实际198；BrowseSearch HTTP header EOF；AuthorCard未出现late author | 保留首次失败，不自动修license／作者／搜索，不声称无关原因已证实 |

截至首次结果，正式Windows／Android／Mac构建均未启动，其他最终矩阵目标尚未执行；完整Desktop没有通过证据，不能据此前focused绿直接放行build-only。原实施者接手相关失败的唯一修复簇，主代理仅归因核对及同一必要修复复审。先只读分类，诊断30分钟／两次无新事实停损；预计定位5–15分钟、范围内最小修复与定向验证25–45分钟。若已有正确红且需产品最小修改，保严格TDD；旧设计预期／宿主／诊断前置只改必要测试部分。未受修改影响的首轮绿复用，未执行的矩阵保持待验，不自动重跑完整Desktop／Android对照。

#### Mac平台定向结果与正式门禁

`sp-dp02-mac-native-original-focused` 自然PASSED，UTC19:04:18.246–19:07:56.356，NativeStable实际17项，fresh XML19:07:40.326／14.82秒，零failure／error／skip。Original首次FQN误用ui.reader，零发现不能记通过；只补准确 `mihon.desktop.reader.SharedDualPageOriginalImagesProductionTest` 六项，没有重复Native17。补验 `sp-dp02-mac-original-six` 自然FAILED，UTC19:08:24.115–19:09:25.205，XML19:08:28.375／56.154秒，6项／5failures／0errors／0skips：原B1 cold17→reverse16→return17通过，其他五项停在实际元数据前置，未据此认定产品原因或降低前置。两组XML已归档现有协调器目录；真实MacSkia／Compose是平台定向证据，不能当正式发布应用运行通过。Mac无在途工作且AppVersion70，后续正式构建暂停，先统一诊断Windows与Mac相关metadata失败，不新建fixture协议／观察器／验收框架。

首轮Desktop的10个skip已逐XML核实：原图Original6因该次未提供 `MIHON_READER_ORIGINAL_ARCHIVES` 未执行，原可选开发性能方法1，MacNativeShare／WindowsPrivacy／非release Library各1条件不成立。不能把这些skip当通过；原图开发组合证据沿用，但当前Mac五失败和本次共享fixture适配需要按实际影响补Windows原图六项及Mac相关范围，不重跑完整Desktop来补环境。

Root另只读比较旧正式70的实际 `app-desktop-jvm-*.jar` 中 `META-INF/mihon/dependencies.json` 与本次生成resource：两者均198个library，uniqueId集合一致，removed／added均空。GeneratedDependencyNoticesResourceTest与AboutUpdateWiringTest中固定199的数量失败，不能作为本轮新增依赖遗漏证据。是否只修这两个无关测试的199→198、保排序／必需名称／许可证正文验收，已单独异步请求用户决定，未收到批准前不编辑；其他未相关作者／搜索／Windows目录失败继续保留原证据，不擅自修产品。

相关失败修复限定七个既有reader测试类及原Original／必要helper：先修失效诊断引用，核真实public location／frame；旧固定全章相位、双可选lease同时入飞、整章早确认与v3局部窗口／单可选工作者矛盾时，只改合法前置及本次明确新预期，保源目标／SQL真实drawmax、实际pixels／12帧几何及旧ticket拒绝。Pipeline decoder-return信号早于真正资源finally结束是待核竞态，必须保真实disposer完成与physical归零；两项ReaderScreenModel回调尚待准确分类，不能提前宣称该修复簇通过。诊断从UTC19:10起遵守30分钟／两次无新事实停损，原实施者主要实施，Root不重复写代码或重启全量。

#### 首轮相关focused：41项，25通过／16待修正，未重跑全量

九个既有测试文件的首轮适配没有production修改。两个启动前置分别为Windows `./gradlew.bat` 被cmd误解析、configuration-on-demand导致既有格式init找不到尚未加载的Spotless插件；两次均零业务执行、自然终态，原日志分别保留为 `dp02-related-reader-focused`／`dp02-related-reader-focused2`。修正为原已验证 `gradlew.bat` 入口及先配置 `:app:spotlessKotlinCheck`（该模块格式目标0文件），未修改构建脚本或扩大业务范围；第三key才是首个实际业务执行。

`dp02-related-reader-focused3` UTC19:41:49.801–19:42:38.835，worker43892／process40176自然FAILED；九份fresh XML已归档同key目录。Root独读XML核41tests／16failures／0errors／0skips：Pipeline1、RuntimeFactory3、Session1、Original6、Model4和SharedWiring3共18项全绿，DualCurrent21中7绿14失败，Adaptive1／Default1失败，合计25绿。Pipeline保真实disposer完成和physical工作归零；Model仅Ready页以synthetic40×60metadata经过真实prepareDecoded协议，不能将该helper当原生像素证明。

剩余16项已有可区分证据，不以再次全量代替诊断：Rapid2的第二次真实tap发生于源4未绘制，应先到4而非跳5；NativeAnimation6把实际进度回执anchor误当唯一逻辑目标；ModeChange3、Adaptive1、Default1把真实新伙伴绘制后的合法追加进度当重复；Matching1立即读取被显式设置重置的state ID，不能证明实际已画图片；Adjust3把部分绘制回执或第二槽尚未绘制当完整配对。只调整同九个测试的合法前置和契约断言，保独立source location／sequence、实际frame.drawn、完整伙伴绘制、idle去重及真实按钮／键鼠，不增加production机制或放宽像素、超时和完成标准。相应测试名称与精确过滤同步，不能保留“真实伙伴永不计读”的旧名称。

Root对Original补验另发现首绘后factDependencies可能为空，`held ∩ dependencies`会真空通过晚事实门槛；六项此刻虽绿仍未签收该门槛。实施者须记录真实已经进入的held header／decode项、断非空及其返回／fact完成，再保原首prepare、25点像素与12帧frame／mount／矩形检查；不等待根本未请求的窗外页，也不删除迟到事实前置。第二轮只补这六项、上述16失败及回执helper直接受影响方法，未改的绿色复用；当前没有正式构建、另一次完整Desktop或Android72。两项license范围扩张仍等待用户决定，未编辑。

随后 `dp02-related-reader-remaining` 为类型推导递归导致的compileTest失败，零业务；仅给Fixture的model／sharedOwner明确类型后，`dp02-related-reader-remaining2` UTC19:52:07–19:52:37运行27项／4失败／0error／0skip。Original6实际进入项非空、返回及fact后25点／12帧、Adjust5、Rapid2、Adaptive1、Default1共15项通过；剩余4项为新observer把动画裁剪后的bounds当测量尺寸，及把model快照当当前绘制全集。用原生语义节点未裁剪size／position、两槽相对中心X消去共同动画位移后，`dp02-related-reader-observer-final` UTC19:54:08.242–19:54:29.329实际12项全绿。14项未改绿色＋15项＋12项组成41项有效关联证据，不是fresh再跑41或完整Desktop。

macOS只同步Original最终测试fixture，在原隔离源码／原SHA输入上 `dp02-mac-original-fixture-final` UTC19:56:09.180–19:56:26.803实际6项全绿，XML timestamp19:56:17.241／8.972秒／零failure／error／skip，已归档本地同key；Native17未重复。production仍SP01提交、版本70，无正式构建。Root核Original实际进入／返回及facts门槛和原首prepare／强像素断言后签收该窄段。

Root末轮复审仍未签收DualCurrent的历史合并observer：`currentDrawn`合并含逻辑目标的历史frame可能误判当前画面，须改为当前active／selected／exposed原生对象的有效ticket／frameId、同一个owner.frame及实际rendered槽，不能退回历史。其直接消费者精确为NativeInput9、ModeChange3、Rapid2、Adjust5、LateMetadata1、MountedPair1共21参数，补验理由是共享observer改变，不是扩大整类。`visiblePageIds`不是delta；源码表明它取最后成功进度回执的frame.drawn，无新进度时提前返回可保留部分快照。旧XML直接观测唯一Dual history完整drawn2/3和native rendered2/3，但model可保留3；逐ACK先后未记录，只能推断。ReaderViewport与普通调度不读取此字段，HTTP导出不能据此作为当前像素全集；API参考同步说明边界，不新增production观测机制。原有41组合绿色尚不能代替这个准确当前frame门槛。

#### 剩余最终矩阵首次：共享全绿，Android18失败保留

Root从干净SP01 production及上述test-only差异启动 `dp02-v3-remaining-final-matrix`：`gradlew.bat :domain:jvmTest :domain:testDebugUnitTest :app:testReleaseUnitTest :test-desktop:test spotlessCheck --no-parallel --max-workers=1`，UTC20:00:24.677–20:19:35.290自然FAILED／exit1，worker46984／process45936确认已退出，Gradle19分10秒。途中只追加API观测说明，production／测试输入保持冻结；未另起Gradle、未重跑任何已完成完整目标。成本高于5–15分钟预估，DP02五小时检查点仍07:59，不扣除工具等待。

首次三目标XML按平台前缀保存在 `.gradle-coordinator/dp02-v3-remaining-final-matrix/`：domain JVM103份／665项、domain Android87份／570项均零failure／error／skip，实际XML timestamp分别20:00:29.947–20:00:35.558、20:00:38.708–20:00:42.871；app Release125份／735项／18failures／0errors／7skips，timestamp20:01:17.426–20:19:23.767。失败中止后未执行的 `:test-desktop:test` 与 `spotlessCheck` 保持待验，不能用其他旧XML当本次执行。共享两目标已完成，后续无相关production修改时复用，不重复运行。

Android18失败分布为CoreProduction／ChapterWindow／ProgressProduction／ProgressSettlementRace各1、SyncResume8、SyncSession6，均在六个旧reader测试fixture。SyncResume8直接是ReaderViewModel:1256读取 `defaultReadingMode().get()` 的Object→Number类型转换异常；另外10是初始化／激活成功断言，包装内因待核。真实偏好为Int／AUTO；六fixture都使用relaxed mock而未配置该Int，本轮loadChapter新增usesDualPageViewer判断触发原getMangaReadingMode。允许先核终态XML并仅补真实默认模式stub，保同步恢复／阅读进度全部原断言，原18项focused，预计5–10分钟；不修同步产品或新增框架。与当前Desktop21观察器门槛串行验证，限定既有15测试文件格式；无新的完整模块测试授权。license2及其他候选无关三项仍未修改，正式三端产物仍待。

#### 当前限定修复签收与执行偏差

当前修复只涉及15个既有reader测试文件（Desktop9／Android6），production／资源／构建配置没有改动。Android六fixture各只增加真实Int／AUTO默认模式stub，保原同步恢复、窗口、SQL进度与竞态断言。Desktop当前观察器改为选中、active、exposed原生对象的有效ticket、同一frameId及实际rendered槽；drawn只与该当前frame／槽相交，不合并历史，不把model快照当屏幕全集。没有原生节点时只记录未关联回执，不以历史兜底宣称完整关联；未另跑测试统计这类回执数量。Root独立核实际diff、production消费路径及下述fresh XML，签收相关修复；API参考只说明现有HTTP快照边界，不新增产品机制。

必须记录一次违反验证范围的执行失误：`dp02-current-native-default-pref-focused` 把 `:app:testReleaseUnitTest` 和 `:app-desktop:jvmTest` 同置于命令、将所有 `--tests` 放在末尾，Gradle过滤实际只绑定后一个Desktop task，Android因此意外无过滤。这是第二次未过滤App模块启动，不能写成仅39项focused或零业务执行。发现后只停止该key记录的进程树，UTC20:24:47.051–20:26:30.093，CANCELLED／exit130；最终stdout已有41 PASSED（18相关＋23额外），无FAILED，没有自然完成或fresh XML。该key归档中的125份XML是首次矩阵旧结果，不是本次产出。首轮735项证据保留；取消的误启动不构成有效完整证据，也不恢复任何额度。后续focused命令各仅一个Test task，精确过滤并核非零实际用例，不再自动启动完整App。

正确分开执行后，`dp02-default-pref-exact18` UTC20:26:51.997–20:27:58.741自然PASSED，fresh六XML18项／零failure、error、skip；`dp02-current-native-exact21` UTC20:29:04.468–20:29:31.788自然PASSED，fresh一XML21参数／零failure、error、skip。均归档于同key目录，worker／process已经退出。Android首次完整结果中的710个未受影响通过及7个skip可复用，18失败由此次补验关闭；不能称最终代码重新跑完735项。Desktop当前41项关联证据为首focused3中未受影响12项＋remaining2中Original6／Adaptive1／Default1共8项＋当前observer21；此前observer12已被21替代，不重复累计或声称fresh完整41／全量通过。Mac当前Original6与未受影响Native17同样按平台证据复用。

#### 格式、客户端与原三项未复现

`dp02-v3-client-format-first` UTC20:31:22.856–20:32:23.834自然PASSED，全局 `spotlessCheck` 已通过；该命令的客户端task仅UP-TO-DATE，XML仍是10月2日旧结果，不能记为本轮实际客户端执行。随后仅 `:test-desktop:test --rerun` 强制这个task首次实际执行，未强制重编依赖、未重复Desktop完整模块：`dp02-v3-client-first-execution` UTC20:34:43.186–20:35:00.604自然PASSED，fresh八XML53项／零failure、error、skip，已归档。Root核客户端使用独立本地HTTP夹具／模型与Python校验；没有向Windows日常应用发送原生键鼠事件。

首次Desktop余下三项采用原方法各一次、单一Test task、integration=true／单worker的最小诊断，无文件修改：`dp02-unrelated-three-diagnostic` UTC20:42:25.751–20:42:40.082自然PASSED，fresh三XML各1项／零failure、error、skip。目录重命名实际越过原ATOMIC_MOVE并通过最终re-probe／bytes；搜索原HTTP过期完成请求得到409／NO_ACTIVE_LOGIN；作者真SQLite／Compose切All恢复原Late50断言。三个fixture及直接production调用链均无SP01差异。此次只证明未复现，不证明杀毒、文件锁、延时或唯一故障原因；首次AccessDenied／HTTP EOF／索引未完成失败及日志继续保留，不重跑或自动修复作者／搜索／文件协议。

本段墙钟从首次相关诊断开始至上述终态均计入DP02原五小时检查点，没有外部等待扣除；实现0、新产品文件0、新构建0、新代理0。实际命令、前置失败、取消误启动及有效／旧XML分别如上，不把测试机器运行时间当测试工程工时。相关测试修正及必要说明随一个修复提交收口，提交hash由Git追溯。两个199→198的无关license断言仍未获用户决定、未编辑；完整Desktop尚不能描述为通过，Windows／Mac正式build-only门禁未放行，三端正式产物及S17仍待，DP02保持未勾选。

#### Android v3正式候选53：构建通过，运行待验

上述15测试文件及必要说明已随唯一修复提交 `c729f2f33b373dbcc4c83cf81443b76224e58b60` 完成；提交后工作区干净。Android的相关首次完整／focused门禁可独立复用，不依赖未获授权的Desktop license修正。构建前只读核其他活跃worktree版本和既有release目录：main已分配41，history分支44，sync分支实际正式候选最大52／aex.34。因此本worktree不使用原预测41，统一配置机械分配53／`0.19.4-aex.35`，原applicationId／证书连续；未引入其他分支的产品代码。专属API36 AVD实核为 `mihon-pairing-api36`，其旧正式宿主为34／aex.16，不能沿用历史日志的40推断当前安装。另一个模拟器及实体设备未操作。

本轮唯一统一入口 `python scripts/build-android.py candidate` 的内部 `android-candidate` 自然PASSED，UTC20:47:17.872–20:49:52.090／2分33秒；签名与清单生成到20:49:58.372，外层wrapper自然exit0。仅版本配置dirty，production源码冻结；没有Debug／full／clean／外层协调器嵌套／安装。代理用统一verify核验，Root再读取实际文件与artifact.json、独立计算SHA一致。正式候选为 [Mihon-Fork-0.19.4-aex.35-vc53-release-universal.apk](D:/Codex/worktrees/0877/mihon/app/artifacts/android/0.19.4-aex.35-vc53-c729f2f33b-release/Mihon-Fork-0.19.4-aex.35-vc53-release-universal.apk)，68,620,171B，SHA-256 `08af169db392743812e31e3f15d8076b95fb7a9dfc30f59cbf00303241233db4`。

清单为同目录 `artifact.json`：release fork ID、versionCode53、debuggable=false、R8=true、resourceShrinking=true、telemetry／updater=false、minSdk26／target36及四ABI；原证书 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，v2／v3已验证。sourceRevision为c729完整hash，sourceDiff `8a05c3a75544483b286140b1b5c528c51e6d7e67a3514b66a1d94aee5b071f0d`，productionInputs `2eab2c1b4a3b9298f05b321488cfcf2cb5957ea511e2313dc5274accf18da83a`，未跟踪输入为空hash；保存mapping SHA `492bc0c51fcfecc0901148ffc6bdfbe812d0d953dee4941234fa678f62717df2`。源码差异指纹是构建时仅版本分配的快照，后续纯证据文字不冒称构建输入变更。

当前只证明候选构建／签名／校验，不证明升级、JNI画面、正式性能或S17。正式宿主保持此不可变APK；后续只构建对应release instrumentation包，核原证书／targetPackage，在既授权专属模拟器安装同一候选后，首先运行原B1冷17／未知反16／回17精确方法验入口。旧Debug72不能替代53正式运行；内部getter／constructor若被R8消除，只读mapping／usage分类，不扩大keep、不以中间或重建APK替换候选。Windows／Mac正式构建仍未执行，DP02未勾选，07:59检查点不重置。

#### 正式53安装与首次ART入口失败：保留，停止后续场景

release instrumentation首次命令又遗漏已验证的整体属性引用，实际argv将 `-Pmihon.testBuildType=release` 拆为两个参数，约3秒FAILED、零编译／业务，不是产品红；原key与日志保留。此前start＋proxy命令被策略拒绝、零执行，不算一次构建。只修该同任务参数形式，`dp02-v3-release-instrumentation-argv` 的实际argv含完整单一属性，UTC20:58:37.664–20:59:12.897自然PASSED／34秒，main minify UP-TO-DATE。随后Root只对本roadmap第2.3.5补充focused单Test task、点号属性整体引用、核实际argv及记录误启动的明确规则，单独文档提交 `ae8e03516d6ab5bc9f608e770a77fa0a684c4c3f`；不改变53候选的c729＋版本快照，不重构建或修改其清单。

测试包经项目 `-Instrumentation` 原证书签名、targetPackage核验，APK SHA `14504423632f377738272b1cbf7944a4cde100c781eb61b673193ea2b5255f24`，路径 `app-desktop/tmp/dp02-v3-release-instrumentation/app-fork-release-test-signed.apk`。仅专属模拟器用统一install入口升级正式53，设备实际base.apk SHA仍 `08af169d…233db4`，版本53／aex.35；测试包安装后的设备SHA同签名测试文件，旧数据未卸载／清库。B1/B2原图与既有扩展输入SHA实核一致，实体设备与另一个模拟器未操作。这是签名／安装／身份连续证据，不是配对与数据保留的业务验收。

首次ART只执行 `SharedDualPageOriginalImagesNativeTest#originalB1ColdDirect17AndUnknownReverseKeepTheirFirstPixelsInThePromisedSlots`，UTC21:02:37.787–21:02:42.414，设备PID17884；runner Time1.823秒／numtests1／failure1／零skip，STATUS_CODE=-2、最终CODE=-1，adb exit0不能当通过。真实栈为 `NoSuchFieldError: ReaderPageMaterializeRunner.Companion`，在HostReaderArtFixture.cold:983访问全局physicalWork计数时失败，尚未launch目标ReaderActivity／PixelCopy／12帧断言。Root独读原stdout与进程记录，核正式53 usage207343的Companion字段、102362的Companion getter已被R8移除；mapping保生产Runner类，不表示测试ABI被保留。这是测试白盒入口与正式R8不兼容，当前不证明产品拒画、JNI故障或S17通过，也未执行到预先发现的Frame.getId风险。

原stdout／process／geometry／ring保存在 `app-desktop/tmp/dp02-v3-release-instrumentation/original-b1-first*`；原collector42332／adb35672／设备17884均已退出，无在途Gradle或ART。没有第二次B1、全Native、正式72、keep／production／fixture改动或正式主APK重构建。现有开发证据不升级为正式证据；正式原图与性能继续待验。后续入口须先明确实际保留的生产观测边界与测试适配范围，保原强像素／几何／进度／物理完成要求，不能删计数断言、将未知写0或把批量内部ABI保留当最小前置修复。存在多个尚未验证的内部访问风险，不逐异常试跑整组来发现；新增适配或显著成本须按roadmap既定停损／范围门槛先给出具体方案。Android版本与本正式候选／失败记录仍留待DP02收口提交，不借此提前勾选；两个无关license修正仍待用户决定。

#### R8入口只读复核：实际DEX纠正字段缺失推断

本次恢复UTC21:24:48起限定十分钟只读核对，沿用原实施者、没有新测试／构建／生产修改。首先确认Runner.cancel／close先移除或清空activeWork再取消job，不能用空逻辑列表代替物理完成；保留的HttpPageLoader.getForegroundWork／getHasForegroundWork确实消费原physical计数，但仅捕获adapter的loader集合不能直接声称全进程归零。只读mapping／usage可证getter或Companion被移除，mapping未列字段本身不能证明字段不存在。

Root进一步用SDK36实际 `dexdump.exe` 流式读取不可变53 APK的两个DEX，仅输出指定三个类的字段、不反汇编整包；单DEX临时解压文件已删除。classes3.dex实际Runner仍有PUBLIC STATIC FINAL `processPhysicalWorkCount: StateFlow`／`mutableProcessPhysicalWorkCount: MutableStateFlow`，实例physicalWorkCount及其backing flow也在；Frame仍有真实id、unit、lockedPageIds、drawnPageIds四字段。classes.dex实际ReaderPageImageView仍有PUBLIC STATIC FINAL `foregroundWork: StateFlow`／`decoderTasks: MutableStateFlow`及Companion。此前由mapping仅列部分字段推断“没有可靠真实计数路径”的结论撤回；缺失的是原测试所调的getter／Runner.Companion，原生产计数没有由这些证据证明消失。

实际DEX同时确认beforeImageDraw／onImageDrawn字段已收窄到具体production synthetic lambda类型，不能把普通测试function直接写入并声称透明转发；Frame.getId等getter移除需读取已证真实字段，而非制造frame。首B1cold方法与fixture不需改生产callback；本段新增事实使最小test-only适配可行。原实施者限两个既有测试文件调整此精确入口的读取方式：真实静态StateFlow和已核实例backing field、严格类型／存在核、无值兜底／无对象图遍历／无事件制造，保原物理归零、25点像素、12帧、源目标／事实／进度要求。

本修复窗口上限25分钟，预计10–20分钟，包含一次测试包编译／签装及同一原B1方法一次复验；不扩其他Original／全Native／正式72，不重新候选或安装中间主APK。当前缓存BuildConfig已核release53／aex35／COMMIT_SHA c729／COUNT9324，与候选一致；因roadmap文档提交改变HEAD，测试包命令可仅排除generateReleaseBuildConfig来复用这个已验证的冻结输入，避免纯文档变更触发主包minify。不得用此排除项生成或交付新的生产候选，正式宿主始终核同一08af哈希。需要改callback、事件含义、新observer或扩大此读取修正时，先回具体范围与成本，不逐异常试跑；R8像素及S17目前仍未通过。

#### 两文件字段适配：首次补验仍为缓存诊断ABI前置失败

`dp02-v3-release-field-abi-test` 从UTC21:49:03启动，限定两文件格式及release测试包编译通过，Gradle41秒；Root读取日志核主compileReleaseKotlin／minifyReleaseWithR8均UP-TO-DATE，没有重建或替换正式候选。ART同一B1 cold方法UTC21:50:45.708–21:50:53.091自然结束，设备PID18273、runner Time3.095秒／1项／1失败／0skip。Runner.Companion入口已越过，原静态StateFlow归零读取及ChapterCache.clear执行后，`cacheJournalState:417`反射调用已移除的DiskLruCache.size()报NoSuchMethodException；仍未launch目标Activity，零目标像素／12帧证据，不能记为产品红或正式B1通过。原日志 `app-desktop/tmp/dp02-v3-release-instrumentation/original-b1-field-adapted*` 保留，runner5132／collector30360及设备进程均已退出。

实施者只读实际DEX确认size:J与lruEntries字段保留；Root核现有synchronized(disk)范围和此前实际已过的diskCache／lruEntries读取，允许在原25分钟窗口内追加这一处严格Long字段读取更正及同方法一次复验，预计2–5分钟。不重置窗口，不扩其他场景；此追加编译／ART必须单独记成本，不能称原一次额度内没有失败。Root独审两文件：pageFact生产实现确为facts[pageId]，字段读取等价；计数仍是真实全局物理完成StateFlow，字段缺失／类型不符直接失败；像素／几何／源导航／事实门槛未删减，也未赋值任何绘制回调。冷读通过仍只关闭这一个正式方法，暖读／knownStructure与性能观察器的具体callback类型风险另保持待解决，不能直接试跑整组发现下一异常。

实施者实核原窗口起点为UTC21:44:05，检查点22:09:05，未扣除正常工具等待。第二次 `dp02-v3-release-cache-field-test` UTC21:56:15启动，测试包编译自然通过／34秒，主compile／R8仍UP-TO-DATE。测试包SHA `8ddad2ec20777bcccd9fcc02385c42b9156b0e7f91474744cf7298cbc138fb79`，上一适配包SHA `8b3bbda1d46ac5d5ad38d25b9801b5e5bb9c7b153783e0afdda5024b48e70d3e`；正式主包及设备仍08af。ART UTC21:57:56.288–21:58:05.210自然结束，PID18899，Time7.583秒／1项／1失败／0skip；cold的真实counter／journal／clear已越过，实际ReaderActivity及章节HTTP入口已启动，但首原图响应在fixture worker:269调用ImageUtil.findImageType时发生IncompatibleClassChangeError：R8已把该方法静态化，测试仍按virtual调用。真实章节响应已返回，原图响应未形成，未取得目标JNI／像素通过。`original-b1-cache-field-adapted*`原stdout／process／ring保留，runner41992／collector43736及设备进程已退出。

两次连续测试前置失败后按roadmap2.3.5转只读诊断，停止第三次编译／ART；不能因异常类型不同抹去同类前置循环。原实施者仅在原窗口内核首B1有限调用链尚未越过的实际DEX成员和静态签名，给最小一批修正及成本；不改ImageUtil生产、keep、绘制回调或其他场景。两个Desktop license断言及暖读／结构／性能的观察方式仍待决定，DP02未勾选。

#### 正式B1 cold单方法通过：范围仅实际像素、源导航与稳定槽位

上述只读核对确认首B1正常路径所用SSIV尺寸／缩放／三种sourceToViewCoord重载、实际page字段、Pair成员及既有页面／viewer／session接口保留；ImageUtil.findImageType(InputStream)实际PUBLIC STATIC，返回ImageType.mime实际PUBLIC FINAL String、getter移除。仅更正原HTTP夹具的stream.use内调用方式：按真实方法static modifier选择null或实际INSTANCE receiver，调用同一production识别方法，再严格读返回mime字段；返回null仍原unsupported错误，无新parser或硬编码原图类型。Root核实际diff，签收这个已知最小前置更正后，在原22:09:05窗口内允许第三次精确复验，不扩大keeper／callback／观察器／文件边界。

`dp02-v3-release-mime-abi-test` UTC22:01:50.997–22:02:26.026自然PASSED，Gradle34秒，限定格式通过、主compile／R8仍UP-TO-DATE。新签名测试包 `app-desktop/tmp/dp02-v3-release-instrumentation/app-fork-release-mime-test-signed.apk` SHA `f782cccff0c865d2fa345c566337456efc3323089bb49e0dd399369821f312b6`，原证书／targetPackage；正式主包及设备仍SHA08af。ART同一B1方法UTC22:03:37.844–22:03:50.433自然结束，PID19159、Time10.248秒、OK1／0failure／0skip、STATUS_CODE=0及最终CODE=-1；Root独读stdout、过程终态、源码断言并独立计算两APK哈希一致，worker17268／collector17548已退出。日志 `original-b1-mime-adapted*` 保留；geometry是logcat收集，应只引用PID19159的DualOriginal行，旧PID18899与系统ring不作为当前证据。

实际B1 archive SHA仍fec7ffef…2b522b；source17与source16的稳态内容／图像矩形均Rect(540,817–1080,1583)，scaleX/Y=1、translationX/Y=0；保25点至少20点匹配及非均匀图像门槛。每个stableOriginal继续检查12帧同mount／container／矩形／变换；真正迟到的source15从UNKNOWN变为PORTRAIT后source16保持RIGHT，回17恢复原矩形及RIGHT。键盘翻页动画期间一次9/25过渡不匹配被原awaitStable排除，未延长超时或将过渡当稳定帧。该方法三处SQL日志均(0,0)，只有读取、没有非零SQL／事件数量断言，因此不能据此签收正式进度持久化或整个S17。

原前置修正批次共3次测试包编译（41＋34＋34秒）及3次单方法ART（3.095＋7.583＋10.248秒），前两次失败完整保留；主应用构建0、新candidate0、生产／资源／keep改动0。从21:44:05至绿色自然终态22:03:50约19分45秒，Root核验／文档耗时另继续计入DP02，不把正常等待剔除。下一独立S10运行仅复用此测试包的两个B2 lateStructure方法，补原JPEG／真实Copymanga响应／迟到宽图／广告22，不包装绘制回调、不新增编译；暖读／knownStructure和正式性能仍等待新增观察方式的范围决定。DP02原07:59检查点不变。

#### 正式B2迟到结构：开篇通过，20的完整方法未通过

两个lateStructure方法复用正式08af及测试f782，零文件修改／编译／安装；B2设备输入SHA再次核同760c。`originalB2LateOpeningWidthKeeps2OnTheRightAndShows3FullOnTheNextRealKey` UTC22:06:36.890–22:06:49.783自然OK1、Time8.919秒／0failure／0skip。首次pidof采集为空，后以同时间窗TestRunner确证PID19362，不以空值当设备PID。Root独读runner和该PID几何：source2真实JPEG矩形Rect(540,819–1080,1581)，晚source3事实到达后12帧同mount／矩形／变换，source2仍RIGHT；真实下一按键source3实际Rect(0,818–1080,1582)、FULL及强像素通过。仅签收开篇迟到路径，不表示known opening LEFT已测。

`originalB2LateBodyWidthsKeep20OnTheRightAndDoNotLose21Or22` UTC22:06:49.784–22:07:28.997自然FAILURES1、Time37.358秒／1项／1failure／0skip，PID19527。真实source20强像素在22:06:56.727通过，矩形Rect(540,819–1080,1581)、RIGHT；之后没有stable source20或source21／22记录。终态为failure-only `nativePresentationState:803`调用已移除ReaderViewModel.getState引发NoSuchMethodError，遮蔽了原30秒等待信息。源码／日志只能把未满足条件收窄为最吻合晚neighbors{18,20}全部SPREAD等待；缺少逐页请求／响应及facts记录，尚不能证明哪页未到、是否真的请求或产品原因。广告22可达性及整个body方法未通过，不因首图通过而勾选。

原stdout／process／geometry／ring为 `original-b2-late-opening*`、`original-b2-late-body*`；11992／47820／47080／44976均已退出。发生首次失败即停止后续用例；只读诊断限5分钟，保原超时／late facts门槛。下一最小更正仅保护失败信息：在await诊断字符串构造处捕获LinkageError并记录实际类／message，继续保原condition、message、30秒、mounted与failureEvidence，不能把诊断不可用转成功；failureEvidence的剩余调用核为现有fixture／平台API。允许限定格式、一次测试包编译／签装及此body精确方法一次，预计2–4分钟、8分钟停止线，无callback／新observer／生产／keeper调整。B1及opening绿色不重跑。暖读／结构／性能观察方式额外45分钟问题已向用户提出，未获答复不执行依赖工作。

失败信息保护的 `dp02-v3-release-body-failure-info-test` UTC22:12:11.242–22:12:50.009自然PASSED，Gradle38秒／限定格式通过、主compile／R8 UP-TO-DATE。测试包SHA `4c0c0e94031f2d2add12d5ac8b8a643bd047bf70ed12a33b9abf168a53810df2`，签装后设备核同SHA，正式主包仍08af。受影响body唯一复验UTC22:14:03.588–22:14:42.672自然FAILURES1、Time37.982秒／1项／1failure／0skip、PID19810。Root独读原stdout／过程及Host实际5行try／catch，确认仅失败诊断字符串被保护，原30秒和条件不变；不因adb exit0宣称green。48868／18264／44684／2808均已退出，没有在途正式场景。

原错误现已明确为“Actual original neighbor bytes must confirm their oriented wide headers”，chapterRequests1、imageRequests4；实际已返回0-based indices[19,19,20,21]（源20两次、21、22），均HTTP200、原JPEG哈希和磁盘缓存一致。源19/index18不在返回向量，不足以直接证明没有发出请求或其最终fact状态。source20真实强像素／RIGHT矩形仍通过，但未过双方晚SPREAD条件，后续稳定／21／22可达性不能记为通过；SQL仍(0,0)，不签持久化。原日志 `original-b2-body-failure-info*` 保留，包括PID19810过滤段；未加新观察器或额外状态报告。该诊断修正共1编译＋1精确ART，约2分31秒命令墙钟，原两B2场景成本及失败不抹去。下一仅只读现有正常预取与事实供给边界、对照S10迟到前提，最多5分钟，不能以删除双邻事实门槛或注入原图事实换绿；完整方法保持失败／待修。

最后只读检查点仍未取得可证的窗口条件，Root于UTC22:21要求停止追加读取并回执、22:23收回该回合，后收到结构化回执。不能把计划5分钟写成精确实际耗时，也不将回执等待剔出DP02。已读入口为HttpPageLoader.onPageSelected→调度器moveTo，事实消费另受Session预算；未证明源19应请求或处于窗口外，产品／夹具因果仍未知，也无已证明可行的最小修正。此回合零文件修改／新进程／新验证；完整body保持失败，未第三次试跑。后续不得直接缩小allSPREAD、制造事实或把单侧晚到当两侧完整结构通过。正式暖读／结构／性能观察方式、无关license断言仍等用户范围决定；DP02检查点仍07:59。

随后Root直接只读核精确入口，首工具UTC22:24:59、收口核22:29:01，实际约4分2秒，超过声明3分钟，已停止、不重新计数。本轮零代理／测试／构建／产品修改。HttpPageLoader默认使用ReaderSchedulerPolicy.originalMihon（nearbyForward=4、nearbyBackward=0、maxConcurrentRequests=1）；moveTo的replaceWindow按anchor计算[start,end]及visible集合，直跳index19时这条正常预取不含index18。ReaderPage.publishHeaderFact从实际已拥有的stream读头，PairingStore／Adapter消费已有contentFact；当前reader目录没有loadFactPage的调用点。另一方面DualPagerPageHolder.loadSinglePage会另调用loader.loadPage，因此不能把“正常预取不含19”扩成“全部生产链绝不请求19”，也不能断言丢请求原因已证实。测试的双方晚事实前提不能仅靠正常前向预取保证，后续需在真实阅读准备／可见页加载边界证明其成立；不增加额外匹配IO、不缩小双方事实断言换绿。B2完整方法仍失败，S17与用户待决定项不变。

#### 2026-10-05用户批准后的恢复：许可断言与45分钟R8观察窗口

用户于本轮回复“允许”，批准此前提出的两个具体范围：正式R8测试观察方式适配最多45分钟，以及两个无关许可测试数量断言199→198。恢复计时从北京时间12:32:02开始，R8适配硬停止点13:17:02，不因换方法、key、编译或交接重置；不改变production、资源、keep或候选53，保首帧、25点强像素、12帧几何及固定性能标准。Root处理许可与独审，同一原实施者处理观察适配，本地重型Gradle串行。

原目标记录的阻塞终态updatedAt=1791153416，对应北京时间06:36:56；实际等待用户至12:32:02，单列5小时55分06秒，仅这段无可独立推进的等待从DP02主动检查点中剔除。此前提出问题至阻塞间仍有诊断／文档，全部计入，不扩大等待区间。原DP02 02:59:00→07:59:00五小时预算未重置，等额平移后的主动检查点为13:54:06；45分钟观察授权不是新一轮五小时额度。

Root仅改GeneratedDependencyNoticesResourceTest与AboutUpdateWiringTest两处199→198；实际resource198及与旧正式70相同uniqueId集合的既有证据复用，排序、所需依赖名称、许可证正文、真实DI与About→许可证列表→正文导航均保留。`dp02-license-count-approved-focused` UTC04:37:18.439–04:38:08.842自然PASSED，约50.4秒；实际argv仅一个jvmTest task及两条精确方法过滤，includeIntegrationTests=true、单worker。fresh两XML各1项／零failure、error、skip，实际测试时间0.169及3.130秒，已保存至同key临时目录。首次全量两处失败继续保留，由这次补验关闭；不描述为最终代码重新全量通过。两处数字调整无格式结构变化，git diff --check通过，原完整格式证据复用。

剩余首次全量失败中的相关reader测试已由既有受影响范围补验关闭，另三无关方法原样复验未复现；本次两许可失败关闭后，可按计划复用首次全量与补验的组合证据进入Windows/macOS build-only，不重新全量。该资格仍不证明正式运行／性能或DP02完成；B2 body、warm/known首帧、正式72与三端S17仍按实际证据保留缺口。

#### 45分钟R8观察适配提前停损：known2通过，warm首绘记录仍缺

同一实施者仅修改既有OriginalNative测试，把不合法的窄化callback赋值替换为平台OnPreDraw→registerFrameCommitCallback观察。preDraw从同一真实holder／owner／ticket读取frame、源页及slot、既有facts、真实ordinary Ready；目标必须尚未draw。commit回到原UI owner后必须同holder／ticket／frame，目标已实际locked与drawn，完整源页集合及slot／position与preDraw一致；不调用preparePage／reportDrawn，也不替换生产callback。25点强PixelCopy、非均匀原图及12帧几何保留。Root独审发现并在首次编译前纠正nested runOnMainSync及commit完整slot关联；原setMangaReadingMode在实际DEX已inline，known切换改走既有default-mode偏好事件／真实production consumer，仍真实Single暖读→新Dual owner，无事实或最终分组注入。

`dp02-v3-release-frame-commit-test` UTC04:43:46.581–04:44:41.359自然PASSED（54.8秒），仅Original限定格式和测试包；main compile／R8 UP-TO-DATE。签装测试SHA548e7aa027bcb7ed447139a46cc7c97dcc1e9411b41381fb4325c302962bf334，正式主包仍08af。

精确known opening UTC04:46:34.372–04:46:50.638、Time13.042／OK1／零skip，PID23380；known body UTC04:46:52.600–04:47:07.029、Time12.915／OK1／零skip，PID23546。Root独读runner、终态与同PID几何：源2首提交LEFT、facts[PORTRAIT,SPREAD]、Rect(0,819–540,1581)；源20首提交FULL、facts[SPREAD,SPREAD]、Rect(0,437–1080,1963)。实际首提交frame为generation1/context3/serial6，先前proposed为context2/serial3，未把两context描述成同一frame；像素及12帧验证绑定实际首提交frame。SQL仍(0,0)，不签收持久化或全S17。首提交上下界也不等于原prepare回调纳秒时间。

首次warm实际Time16.424／1项1失败：真实顺读强像素已到源17，但timely首绘记录为空，行135失败，后续warm reverse未执行。候选假设为predraw只检查isReady，可能漏掉原SSIV drawable=isReady||isImageLoaded、super.onDraw初始化后形成drawn的首帧。仅改为原drawable条件后，`dp02-v3-release-warm-drawable-test` UTC04:51:35.827–04:52:07.074自然PASSED（31.2秒），main compile／R8 UP-TO-DATE，签装SHA8a12c48c4bb9bab68e14300f1e77f5e60342bbbb8b1728015d211b346a0087e5。warm唯一补验UTC04:52:53.098–04:53:11.116，Time17.098／1项1失败，PID24170，仍行135记录null；假设未获证实。一次准备临时脚本的SyntaxError发生在ART启动前，不计产品红或执行成功；实际第二轮ART仅该方法一次。

连续两次warm没有新增可区分原因的现场事实，按2.3.3提前停止追加观察器、编译和ART，不等待耗满13:17:02。共2次测试包编译／格式、4次精确ART（2通过／2失败，runner时间合计59.479秒）；未重跑known2、已绿B1cold／opening或Debug72，未启动正式72。性能原Ready／逐槽draw回调纳秒测点仍未取得可证等价，不能用FrameCommit上界冒充。B2 cold body双方迟到前置仍失败／待决定。实施者回执及Root核验均确认本轮worker、ART、collector退出，未提交未验收的观察适配；所有原失败与当前日志保留于 `app-desktop/tmp/dp02-v3-release-instrumentation/*frame-commit*`。

#### 正式桌面产物：Mac构建及启动通过，Windows仅版本分配成功

Mac先核隔离容器的source子目录、HEAD dec006e、实际只有此前测试夹具dirty及70版本；Root本地核dec006e→ae8e035所有提交仅tests/docs，production／build输入无改变；将获准的两个许可测试断言同步到隔离source，不覆盖已有原图夹具。实际Temurin21.0.10+7、磁盘3.1GiB可用，现有三个日常／历史PID保持保护。`dp02-v3-macos-formal-build` UTC04:41:12.466–04:42:17.431自然PASSED（65秒），项目build-only、完整JVM0重跑，版本70→71，worker60955／process60956自然退出。

实际 `Final macOS app:` 为 `/Users/altair/Library/Caches/mihon-shared-dual-page-v3-20261005-dec006e/artifacts/0.11.19.71-dec006e/Mihon Desktop.app`，268MiB。Root独读实际打包JAR常量确认STAGE11／FEATURE19／BUILD71、GIT_HASH dec006e、IS_NON_RELEASE_BUILD=false，JAR SHA0f6580c30aedf36d0046c822570744d56cb13010e2c8706323d32cd2c8d52397。LaunchServices启动同包、独立新profile formal71-runtime、HTTP59351／JMX59352；实际PID61133及包路径／参数相符，health OK、HomeScreen与空library READY。正常临时唤醒后分别核displayAsleep=false、sessionLocked=false，目标on-screen窗口layer0／1024×768／(97,73)，原生事件权限true；未发送验收键鼠，不读取屏幕像素。仅证明正式包启动和窗口，不证明B1/B2、进度或性能；本次未发现需要改变macOS维护规则的新机制。

实际Mac包以ditto保留app结构归档，ZIP196,600,789字节，SHA104f1b8fb50c5e48cfc62e557d0d35413ee28d83551b9d9595caba22ebff086e；复制到本地后独算同SHA，可点击[Mac正式构建包](D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.71.dec006e-macos.zip)。该包仍有S17待验项，不把归档／启动记成阅读验收完成。Root仅调用本次59351的shutdown，返回202/success，保留profile以便后续复用。

Windows统一项目入口已分配0.11.19.71.ae8e035，但Git Bash派发至缓存PowerShell在进入脚本前退出127：`dp02-v3-windows-formal-build` UTC04:46:31.682–04:47:02.704（31秒）。保留71，不回滚或再次bump；显式已核pwsh.exe7.6.5续接同项目build-windows.ps1／SkipTests／VersionAllocated71：`dp02-v3-windows-formal-build-dispatch` UTC04:49:46.433–04:50:16.566（30秒）仍空log／exitFFFFFFFF，未进入Gradle。转只读入口诊断，系统PowerShell5.1在同foreground_options／DEVNULL条件下只读版本命令成功，再续接同script与已分配71的 `dp02-v3-windows-formal-system-shell` UTC04:53:35.635–04:54:05.767（30秒）同样退出FFFFFFFF／空log。所有worker与process自然退出；实际Gradle构建、runtime验收及正式发布均0，不宣称三次正式应用重构建或全量重跑。Windows版本71保留未提交，无新 `Final unpacked EXE:`；不提供旧70或tmp EXE充当本次交付。脚本执行前退出的真实原因仍未知，日志未提供自动审批拒绝理由，不把proxy、策略或PowerShell版本假设写成已证原因。连续入口失败后已停止新的启动／试探，后续需要最小入口诊断方案及用户决定。

许可两断言补验及Root实际两行diff核验已完成，独立获准的测试修正提交为 `15e77a4bde7813c163ff5ac5a425ed7c3992f816`（仅两个test文件）；与未验收DP02观察器／版本／证据分开，后者保持dirty不冒充收口。当前正式Android53仍原c729／08af，Mac71基于dec006e同SP01产品内容，Windows尚无正式包。SP01勾选不变，DP02不勾选；尚欠warm首帧、cold body完整路径、正式性能72、Windows构建及三端S17完整运行／持久化证据。

#### 独审补正known首候选证明：只接受一次登记，严格结果替代初版

Root最终独审发现初版observeFirstCommit在每次preDraw重复register；stale owner／ticket／frame返回后仍可能接受较晚candidate。这削弱了“首次”证明，初版known两次方法绿色保留为运行事实，但不单独用于首帧验收。原45分钟窗口内，Root只交回原实施者最小补正：helper成功register返回true、所有未合格前置返回false；每个原listener只有局部candidateScheduled，第一次合格登记后固定，即使commit失效或未产生真实draw，也保持原记录null／失败，不能跳过早期候选接受后帧。无通用状态系统／新observer，未继续warm诊断。

`dp02-v3-release-first-candidate-test` UTC05:07:29.092–05:08:01.397自然PASSED（32.3秒），仅Original限定格式及测试包，main compile／R8 UP-TO-DATE。签装测试SHA0466301647430e2dca97a84327ec367a7a9bef06b9274c4ee4aa8dc5bc67a366；正式主包及设备仍08af。最小修正真实影响两个known首帧判别，因此只补这两个精确方法：opening UTC05:08:36.304–05:08:51.509，Time12.356／OK1／零skip，PID24580；body UTC05:08:53.634–05:09:08.512，Time13.242／OK1／零skip，PID24736。Root独读当前helper／listener、自然过程终态、runner以及同PID实际frame记录，签收同首合格candidate context3／serial6、LEFT/[PORTRAIT,SPREAD]及FULL/[SPREAD,SPREAD]；原25像素与12帧检查仍通过。最新两结果替代初版known证据，不累计为四个不同验收项。不能把帧内上下界当原回调纳秒时间，也不签SQL持久化。

此必要审查修正额外1次32.3秒测试包编译、2次精确ART，未跑warm、body迟到、72或全量；一次归档Python-c引号SyntaxError发生在归档准备阶段，无测试启动／无写，使用现有rg读取完成，保留过程错误。整个用户45分钟窗口共3次测试包编译、6次精确ART（四次known方法运行成功，最终有效两项；warm两次失败），新正式Android候选0、Debug72重跑0、产品阅读代码／keep改动0。所有本轮协调器、ART和采集器自然退出；Mac本次61132／61133也已退出，三个保护PID44348／47006／55981仍在。Root同意首候选修正，warm记录丢失、cold body前置、正式性能等价测点和Windows入口原因仍未知／未关闭。

本轮按2.3停止继续试探。最小下一步候选是额外20分钟为warm捕获明确的首candidate前置／时序反例，以及10分钟定位Windows脚本执行前退出；范围仍仅既有测试／现有脚本入口诊断，不重新全量或正式Android候选，未知原因不承诺必修复。原性能纳秒口径保持，若提出改为真实FrameCommit上界必须先说明度量差异并由用户决定，当前未启动正式72。暂缓替代为保留现有Mac／Android候选、dirty及缺口，不勾选DP02。追加需用户决定，不能用原45分钟授权反复延长或通过换key重新计时。

Root末次终态／UTF-8核验为北京时间13:15:01，本轮恢复12:32:02至此约42分59秒，包含工具等待、并行桌面前置、审查和文档，不将正常等待剔除；45分钟R8窗口内收口。DP02原主动五小时检查点仍13:54:06，不因停损或纯证据记录重置。所有未验收DP02改动保持未提交。

#### 2026-10-05按用户纠偏自主续接：Windows正式交付入口关闭

用户明确约束用于缩短交付、制止扩张和无效测试，不用于反复中断开发。roadmap修订提交 `32141a9682` 撤回旧20／30分钟继续许可请求，时间与重复失败作为内部收敛检查点；范围内的必要修复、等价测试前置修正及未进入实际构建的续接自主执行。此前等待和失败记录保留，不因纠偏抹去成本。

Windows先以原生System PowerShell、同DEVNULL／进程组条件核命令执行与Python／Git解析；随后用 `-VersionAllocated -ExpectedVersion entry-preflight-only` 使原项目脚本在Gradle之前按预期抛出版本不一致错误。直调用及协调器 `dp02-windows-entry-version-guard` 均触达同一版本守卫；这是新的入口事实，仍未证实此前空日志退出的原因。守卫探针未递增版本、构建或运行测试。

沿用已分配BUILD71及当前HEAD身份，`dp02-v3-windows-formal-entry-verified` 经项目 `scripts/build-windows.ps1 -SkipTests -VersionAllocated -ExpectedVersion 0.11.19.71.32141a9` 续接build-only，UTC06:08:14.811–06:10:33.544自然PASSED／exit0，约138.7秒。实际Gradle正式产物构建1分56秒；生产扩展APK安装、来源ID与运行版本验收通过，正式发布成功。首次完整矩阵及受影响补验的组合证据复用，未重跑完整模块／全量，未分配BUILD72。

日志 `Final unpacked EXE:` 对应[Windows正式未打包应用](<D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.71.32141a9-unpacked/Mihon Desktop.exe>)，Root确认文件存在（472,576字节）。[Windows ZIP](D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.71.32141a9-windows.zip) 为188,809,012字节，独算SHA-256 `4adb246bf7266abd7e340e627ef26c591bf969d29818ca13b14d87f3caf4b4c6` 与构建日志一致。没有发送Windows原生键鼠；用户选择的B1/B2人工验收及完整S17仍待，正式产物通过不替代阅读／持久化／性能证据，DP02保持未勾选。

暖读原null仅在已有Original测试增加有界、失败时输出的前置记录；`dp02-v3-release-warm-prerequisite-diagnostic` 编译40.4秒，唯一warm诊断UTC06:07:01.072–06:07:21.545、Time16.782／1失败。PID25610记录同image／frame27：逻辑目标14且native可画时，preDraw矩形(-540,817–0,1583)尚不可见、drawn为空；下一preDraw变可见时已drawn[14]，随后[14,15]，首候选从未登记。故此前OR drawable假设不成立；本次新证据定位为preDraw几何可见性门槛漏掉实际提交的首帧。

最小修正仅将globalVisibleRect门槛移至FrameCommit：pre仍要求同逻辑目标、真实native可画且尚未drawn；首次合格候选固定，commit仍核同holder／owner／ticket／frame、实际可见、locked／drawn及完整源页／slot身份。Root独核等价边界，未制造绘制或修改生产callback。`dp02-v3-release-warm-commit-visible` UTC06:16:06.010–06:17:13.478自然PASSED（67.5秒），限定格式和测试包，main compile／R8仍UP-TO-DATE。签装测试SHA `ac74745b4713361e74b1abd4124a2b4b1c33eac88f4f581b189dbab6bad8d39f`；正式主包08af不变。

仅原warm方法UTC06:21:57.331–06:22:35.650自然结束、Time27.830／OK1／零failure、skip，设备PID25993。Root独读runner、终态、当前helper及同PID日志：首次15像素对应facts[PORTRAIT,PORTRAIT]／源索引[14,15]／ordinaryReady[true,true]；顺读0–16完整、真实回翻15/16再到17、25点强像素及12帧几何均通过。观察器漏记问题已修复；SQL仍(0,0)，不将该通过写成持久化验收。未重复known2、冷读绿色或72；Gradle、ART与collector均终态退出，B2晚到前置及正式性能继续待处理。

#### 晚B2前置收敛：保留真实晚到与回翻，移除不成立的反拖等待

普通前向预取不保证源19后台返回。先在原body方法试真实反拖、保持20选中且可见，要求19进入已有HTTP门闩；未新增IO或事实注入。首次共同门闩条件未成立，不能由返回向量断言19全链没有请求。读实际scheduler后将19/21门闩分离，先释放21；20原位12帧稳定通过。第二轮独立失败记录为19未进入门闩、21已进入、逻辑仍20、20实际可见且scroll=390，排除了本轮由21门闩仍未释放导致的等待；不调整手势档位、阈值或超时。

| 精确body补验 | 测试包编译 | ART结果 | 有效结论 |
|---|---:|---|---|
| `dp02-v3-release-b2-held-previous-holder` | 约40秒 | PID26295／39.255秒／1失败 | 首20 RIGHT、双方UNKNOWN通过；反拖联合前置未成立，后续未执行 |
| `dp02-v3-release-b2-two-stage-neighbors` | 31.2秒 | PID26618／40.166秒／1失败 | 21真实晚到后20原位12帧通过；19门闩未进入，后续未执行 |
| `dp02-v3-release-b2-real-reverse-return` | 28.9秒 | PID26896／12.673秒／OK1 | 普通回翻完整通过，见下文 |

最终仅更正原方法的真实阅读前置，删除反拖试探增量：冷20首RIGHT且19/21皆UNKNOWN → 21真实SPREAD晚到、20原位12帧 → 真实RIGHT回19、原宽图FULL及双方真实SPREAD → 真实LEFT回20、历史frameId／RIGHT／原内容几何及当前实际mount十二帧稳定 → 21 FULL → 广告22完整可达。未预注最终组、边界或fact，19由正常用户回翻触发，不宣称其到达时20持续可见；“后台晚到不自动位移”由21阶段持续可见验证，另以真实回翻验证19事实和历史槽位。该覆盖对应S10／S12／S17普通源页链，未用不成立的后台请求前置阻塞真实阅读验收。

最终ART UTC06:44:07.644–06:44:23.561自然通过；Root独核现body源代码、runner／process与PID26896几何。20首显示与返回均Rect(540,819–1080,1581)，实际mount与container也相同；19 FULL Rect(0,818–1080,1582)，21 FULL Rect(0,840–1080,1560)，22 RIGHT Rect(540,918–1080,1482)。签装测试SHA `1628571b3b961cd90b9f4117a05316fda239e483c724f538c0cf4c95f4198c5b`，所有编译main／R8均UP-TO-DATE，正式主包08af不变，所有自有进程退出。原失败保留，测试修正不能描述为产品bug修复；SQL仍0，正式保存和完整S17继续待验。正式原图六场景现有组合证据可复用，不重跑六场景整组；不称为当前测试包重新整组全绿。

正式性能接口只读核验：实际DEX保留SSIV的 `onImageEventListener` 接口字段、`setOnImageEventListener` 和真实Ready事件，现ReaderPageImageView逐槽draw函数字段窄化，无法直接透明包装。采用Ready→GPU FrameCommit上界与开发精确draw计时分别报告的方案已作为一次集中度量取舍提交用户；此项尚未得到答复，不启动依赖该口径的正式72。其他范围内工作继续，DP02未勾选。

#### 正式部分显示验收：首像素通过，进度资格待定位

`dp02-v3-release-stable-native-field-access` UTC06:53:54.926–06:54:25.016通过限定格式及测试包编译；仅适配Host真实字段读取和既有portrait／wide／error的精确参数过滤，保留原像素、挂载、变换、重试及SQL断言。测试包SHA `90710fb939d1d217b0cfc78d51bdea7b5a097c6384e276a71dc15a241613208e`，主compile／R8 UP-TO-DATE，正式主APK仍08af。

portrait唯一ART UTC06:54:49.995–06:55:31.099，PID27358，Time38.044秒／1失败。真实C的RIGHT首像素通过，但原30秒“Actual first-slot pixels must reach the real SQL progress path”超时；读取复制的数据库及WAL确认chapter15652进度0、事件0。尚未到达缩放、放行伙伴或后续进度断言，wide／error未执行。持久incognito=false不能证明失败时VM的Lazy值、activation或pending资格；旧getState诊断在R8宿主不可用，未据此归因产品。后续限定失败关闭前的实际字段资格快照及同一portrait补验，不改变超时或保存要求，不重跑原图六场景或全量。

#### Mac正式包原生键盘接线的证据边界

复用正式 `0.11.19.71.dec006e`，独立formal71-runtime profile及59351／59352端口，经LaunchServices启动实际应用PID61616／包装器61615。既有CoreGraphics工具核对醒屏、未锁定会话、目标PID及窗口焦点，以真实left／right事件使currentPage 0→1→0、locationSequence 0→1→2；不读取屏幕像素或辅助功能文本。输入发生在首图呈现完成前，使用现有local_archive生成的22页JPEG测试场景，不能作为原B1／B2、首帧稳定或真实阅读保存通过证据。

该场景未建立production数据库章节记录，实际pairingStatus为RESTORE_FAILED，因果尚未证实；HTTP data/manga端点仅返回占位成功，不能据此伪造数据库前置已满足。保留原日志 `app-desktop/tmp/dp02-macos-native-keys.log`，不启动重复生成场景。shutdown成功，Root后续只读ps确认61615／61616均已退出，保护的44348／47006／55981仍运行；零新构建、零全量。正式Mac原图、稳定性、保存和性能门槛保持待验。

#### 正式部分显示与保存：三种伙伴通过，夹具使用真实 production driver

S02／S12／S15／S17的唯一未过前置为首张实际像素后SQL仍0。实施者先只读核实际DEX约15分钟后回执，Root收回任务并承担后续修改，未并行重复实现。既有StableNative测试仅在原首SQL超时后读取真实资格状态并继续抛出原失败；不结算、不造回执、不放宽30秒。两次精确portrait失败快照确认：actual activation与selectedActivation相同、incognito Lazy已初始化且false、acceptedCount=1、settled=true、handle未关闭、viewer idle、实际holder readingEligible=true；第二次还确认队列writeFailures=0／pendingWrites=0。因此未以“没有保存资格”归因，也不把队列为空等同落盘成功。

只读真实AppModule／仓库及实际DEX确认Release使用Requery SqlDriver，原Host夹具另用Framework SQLite反复创建／查询同一数据库。最小test-only修正把专属manga／chapter创建和进度查询接回真实DI SqlDriver；同主键UPSERT保留其他章节与已有回执，查询真实chapters和reading_events，不 mock repository、不直接写进度。executeQuery的R8返回类型及QueryResult.Value实际构造器经DEX核对后使用保留入口；值不存在或错型直接失败。调整后绿色支持夹具接线更正有效，但尚未证实混用driver导致原失败的具体机制，不宣称产品存储bug已修复。

| 有界修正／验证 | 测试包编译与格式 | 精确ART结果 | 边界与结论 |
|---|---:|---|---|
| `dp02-v3-release-progress-qualification-test` | 47.56秒 | portrait，PID27829，36.004秒／失败 | 保存资格实际已接受；原SQL等待失败 |
| `dp02-v3-release-progress-storage-failure-test` | 32.74秒 | portrait，PID28032，35.953秒／失败 | 队列无记录失败、无在途写入；旧外部SQL仍0 |
| `dp02-v3-release-production-sql-fixture-test` | 35秒 | portrait，1.476秒／前置失败 | ContentValues EntrySet不支持toArray；改为迭代map，不算产品红 |
| `dp02-v3-release-production-sql-fixture-entries` | 31秒 | 未启动ART | 执行前发现REPLACE的外键级联风险，改同主键UPSERT |
| `dp02-v3-release-production-sql-fixture-owned-rows` | 30秒 | portrait，1.855秒／前置失败 | value-class constructor-impl已移除；改为已核保留构造器 |
| `dp02-v3-release-production-sql-value-constructor` | 33.24秒 | portrait，PID29224，7.224秒／OK1；wide，13.746秒／OK1；error，6.764秒／前置失败 | 前两项完整通过；error的action_retry资源字段被移除，后续未执行 |
| `dp02-v3-release-native-error-resource-id` | 29秒 | error，PID29741，7.941秒／OK1 | 用正式宿主实际资源名查询ID，非零必需；原裁边、真实触摸重试及SQL断言完整通过 |

本段所有编译仅限定owned Kotlin格式与 `:app:assembleReleaseAndroidTest`；实际main compile／R8均UP-TO-DATE，正式主APK与设备仍SHA `08af169db392743812e31e3f15d8076b95fb7a9dfc30f59cbf00303241233db4`，没有新candidate、keep或生产阅读代码修改。七次测试包编译约238.5秒，八次单参数ART合计110.963秒；UTC07:20:14–07:53:17约33分03秒墙钟，期间ABI诊断、夹具修正、签装和工具等待均计入，前述约15分钟委派只读及此后证据核验另计。实现／测试工程／文档未独立计时，不从机器时间推导其占比。两处可避免的夹具API错误保留成本，不包装成业务回归。

portrait与wide复用测试包SHA `3ea36cb0a9e4ef44094b7ecf93e45bb9b897c542adf8741b938ac452ac7a7a2d`；error仅追加实际资源ID修正，测试包SHA `159837b708ad5993638cce6629649d4f6d1804f774bbeeadc4a776f026ebbb68`。这是同一正式宿主的三项组合证据，不称最终测试包重新三项整组通过。原C的RIGHT首像素→SQL进度1及新增一回执→真实双击缩放→伙伴纵图／宽图／HTTP503及重试，均保留当前挂载、内容矩形与变换；纵图与重试伙伴实际画出后SQL进度2及第二回执，宽图通过真实下一键完整显示后SQL2／第二回执。error的按钮与提示完整留在左槽、真实触摸重试，不删除原断言。

Root独读runner终态（每项1、零failure／ignored）、同PID日志、当前夹具及实际主包哈希。最终外部只读复制 `tachiyomi.db` **及同名WAL** 后，chapter15652的last_page_read=2、read=0，目标累计事件7，最新两个事件为progress页1与页2，对应最后一次error方法；累计7包含此前有效尝试，不作为“本次只写两次”的断言，方法内baseline+1／+2才是去重证据。首次只复制主文件读到进度1／事件5，随后确认存在30,424字节WAL并补读，旧检查点不作记录丢失或产品故障证据。外部核验一次查询使用错误列名在只读查询阶段失败，随后按真实schema更正；没有写库或重跑ART。原日志为 `stable-native-portrait-qualification*`、`stable-native-portrait-storage-failure*`、`stable-native-*-production-sql*` 和 `stable-native-error-runtime-resource-id*`，所有自有runner／collector已终态退出。

本次三份测试文件约600行变更共同适配不可变正式R8宿主的观察、真实输入与保存验收；规模来自保留原判别力所需的实际ABI读取，不引入产品owner、缓存、调度或新能力。正式原图六项与部分显示三项各自复用有效结果，不为提交重跑已绿项。DP02保持未勾选，版本记录留待最终收口；剩余正式性能度量取舍仍待用户答复，Mac完整原图／操作／保存与Windows人工验收继续待验。

#### 进度回写及 Mac 真实入口边界复核

验收修正已提交 `d9e8d99435b9f7a525f673fb1397976dc9ad79ae`（三份测试、两份维护说明及本报告）；版本仍待最终收口。roadmap第5.0节现按矩阵、Android、Windows、Mac、性能与最终收口汇总当前有效证据和缺口，不新增任务checkoff、不称整个S17已过。

本轮只读检查既有 LibraryMangaTestModeController.selectChapter、TestNavigationController.openReader、MangaDetailScreen.onReadChapter 与 reader 原生坐标输出。前两者即使使用真实章节记录仍注入synthetic tracker；正常详情页用户点击则不覆盖真实runtime tracker。现有坐标仅来自已挂载reader，不能用于详情或本地来源入口，未发送猜测坐标、未启动新应用、未复制或改写profile数据库、未扩大观察框架／产品入口。此新增事实排除了“经HTTP选择真实章节即可证明保存”的替代路径；完整Mac原图／操作／保存仍待真实产品UI路径，正式性能口径也未获答复。Windows人工验收的具体操作清单已送出并等待实际版本绑定的结果，不将等待或未提交答案记为通过。
