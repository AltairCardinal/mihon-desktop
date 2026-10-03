# 双端共享双页规则与修复证据

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
