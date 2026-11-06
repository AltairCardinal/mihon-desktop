# D6：Windows 阅读器安卓经验对齐与剩余等待 roadmap

2026-11-06。状态：本批实施、定向验证、独立审查及外部裁决已完成，C1/C3按局部工作量优化采用，随本批本地提交；未证实稳定首开毫秒收益，体验目标未达标。上一轮a5fb088dfc仅交付规划，本轮产品起点仍为42531460df的E0逻辑。分析权威：[优化分析与C1–C7排序](../evidence/desktop-reader-d6-optimization-analysis-2026-11-06.md)；父入口：[Windows首开计划](2026-11-05-desktop-reader-first-open.md)。结果权威：[D6结果与10行CSV](../evidence/desktop-reader-d6-2026-11-06.md)。勾选包含明确的条件不实施裁决，不等于所有候选均写入产品；不另建状态系统。

## 本批执行决议（先于代码修改）

用户要求将有一定收益的项目加入执行范围，由Sol/Luna实施、主代理审查并持续至完成。本批明确执行C1局部旧身份查找简化和C3每章下载判断一次；C2先用真实接线/原件证明重复与完整依赖，满足门槛才实现一个局部去重入口。C4/C5/C6各做一次有界源码/现有证据评估；无具体可控RED或收益依据时以“不实施及原因”裁决收口，不把条件任务悄悄删掉，也不为所有窗口另造包。C7默认不执行。

沿用原架构，C1/C3分别记录RED/GREEN和计数；若同一最终体验包包含多项局部改动，外部F只归因于最终组合，不拆分或累加每项毫秒。C2若增加跨调用状态复用或改变呈现触发，必须符合D6.7严格可见收益门槛，否则精确撤回该项，C1/C3另按局部工作量门槛判断。不得借本批重试D5、旧image lease、全局调度或新共享版本系统。

真实分工：Sol medium拥有全部production/tests与串行Gradle，Luna medium只拥有本批ignored目录下的限定读取/离线统计工具及材料，主代理拥有docs/唯一桌面输入/独立审查/最终提交；互不覆盖，代理不得提交。旧同名代理不可用时按相同职责新建两名。预计60–120分钟，一次独立审查及必要一次定向修复复验；优先复用E0控制，最多两个常规preview构建及14有效正式样本。若复杂候选需撤回后补最终包，仅在解释必要性后追加一次收尾构建；不追加性能候选或全量。具体无收益/无安全假设也是完成裁决，不能把未验证候选说成已优化。

## 基线、目标和边界

- 源码基线 `42531460dfeb08cc0bec2356f0951cd332913094`，独立工作树 `D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105`。D3/D4及D5点击锚点保留，D5性能调度/helper已撤回。实施前重新核HEAD、diff和包/source；有后续改动先判断证据适用性，不覆盖用户内容、不自动套旧patch。
- 史莱姆04卷CBZ，187页，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`；正常详情章行恢复index4、RTL、隔离profile，单页及双页ON/OFF。原件和成功测量流程沿[D5](../evidence/desktop-reader-d5-2026-11-06.md)与[采集指南](../automation/DESKTOP_READER_LATENCY.md)。
- 端点为R、每页/完整单元F、每页K首图前最长/累计与首图后再黑。callback与工具输入包络分列，整体R≤100ms、K≤60ms不能用较晚callback起点或应用首绘确认代签；正确画面、恢复、翻页、真实取消、身份与进度是硬条件。
- 收益排序C2→C4→C5→C1→C6→C3→C7；执行从便宜且确定的C1/C2归因开始。一次只验证一个性能机制，不按这个列表自动连续试7个候选。
- 上一轮仅文档的边界已由本轮明确实施授权更新；本批执行上节选定范围及必要验证，本地提交，不推送、不更新公共功能说明，不影响用户原实例/配置。完成勾选必须对应实施或条件裁决的实际证据，不等同≤60ms目标通过。

## 实际执行与范围收口

Sol medium负责2 production/2 tests、真实RED→GREEN及串行preview，Luna medium负责本轮ignored目录中的限定读取/统计，主代理负责唯一GUI输入、一次独立代码审查、原始端点复算、内部文档及最终提交。旧代理不可用后的同职责恢复只核既有冻结证据/补离线样本，没有重新实施或增加审查轮次。

实际复用E0、只新建一个C1+C3组合preview；6项focused GREEN、8份有效样本/10页（单页两臂first/warm4份、候选双页OFF/ON first2份、反向single first2份）。主代理逐份复算并核原帧。中途Esc归还桌面，用户授权恢复后只补末份控制；保留3份无输入准备采集和工具错误，未替换慢样本。恢复工作不新增性能候选、构建或产品测试。无全模块/仓库全量、finalParityAudit、正式发布、主干整合或推送。

下列conditional任务以进入门槛失败的明确裁决完成，不伪造RED或将未测调用账写成已测。未消除的性能问题保留在报告末节，不因本批完成自动继续新一轮。

## D6.0 固定事实与候选门槛

- [x] 核HEAD a5fb088dfc、受保护diff、E0与候选完整EXE/profile/manifest/产品指纹，冻结4源文件并核构建前后SHA；D3/D4保留，D5候选撤回。确认C1涉及共用模型身份接线，选真实模型、内容/方向/生命周期及三模式最小路径；C3选detail/library导航过滤接线。
- [x] 在真实ReaderScreenModel/owner接线用CountingList取得C1旧身份读取量，8页与187页连续两次结果一致。实际UI的ensure次数、同输入投影次数、exclusive CPU/锁时间本轮未测；未为C2制造一套新探针。旧D4/D5原件及当前源码不足以证明完整可复用输入，记录为门槛不满足，不能把大wall窗口全部记为C1/C2成本。
- [x] 形成两个低成本独立RED：同序身份查找与每章Boolean重复求值。C2缺安全完备键，C4/C5/C6缺可删依赖/重复的生产证据，按预先约定不实施；C7不立项。局部计数收益与外部组合F分别记账，不相加。

## D6.1 C1：旧身份查找局部等价简化（采用）

- [x] 真实acceptSessionState RED确认8页44、187页17,765次旧content读取；GREEN为16/374。覆盖同序、乱序pageId、版本变化、原始方向、换代及关闭；不是新建map或源码文本测试，未用绝对ms造红。
- [x] 同序时ordinal读取并校验pageId/contentVersion，其他情况保留原正确fallback/NORMAL方向。stamps、代次、内容替换、owner退役及进度不变；没有新缓存/锁/调度，没有恢复旧A2认证器补丁或并改C2投影策略。
- [x] 同一RED→GREEN及真实接线focused通过，主代理独立核源码/测试/冻结SHA；8页负控少做读取，没有新索引分配。仅采用工作量优化，未声称短章wall或稳定F收益。

## D6.2 C2：相同输入重复投影（门槛不满足，未实施）

- [x] 有界核ensure/accept/publish、configure、certifier及prepare依赖：已有同值guard/memo；位置、模式、header/sample、locked/painted、restore及pending等仍是合法输入。未取得同输入冗余生产RED，实际UI调用量/独占成本未知。
- [x] 不能只用layoutRevision或accept成功跳过真实变化；无完备现有身份，故不新增第二套revision、跨调用缓存或通知合批。原重试、重入、手势pending及退出链保留。
- [x] 条件裁决随唯一独立审查核对；无实现便不伪造GREEN/性能收益，不追加包。后续只有新的同请求重复证据与完整依赖键才能重启。

## D6.3 C4：Ready后交接（门槛不满足，未实施）

- [x] 核生产图片owner：Ready经owner/job检查直接发布StateFlow；未证实另一处可删除排队/交接。旧Ready后wall窗口及新ON开流→完整确认混合窗口保留，不能认作纯等待或全归解码。
- [x] 无可控真实冗余RED，不修改触发点、重试D5提前准备、旧图保护或首次/完整单元进度门槛，也不为猜测重打包。
- [x] 不新增该候选测试；本轮C1边界由focused及三模式实际正确漫画/往返覆盖。未宣称全套C4迟到/取消像素矩阵已跑，后续有具体机制再按触点规划。

## D6.4 C5：首次入口、会话与页表（门槛不满足，未实施）

- [x] 沿既有回调、OPEN/PAGE_LIST_READY/OPEN_PAGE/FIRST_PAGE_PRESENTED作同次阶段账；更细DB/IO/catalog/native exclusive尚未因果分摊，初始类加载与热态差不全部归为可删除成本。
- [x] 核活动进度身份及单次materialize职责，没有本次重复物理定位/构建的真实RED，故不移动原子恢复读取、预热书库或跨打开保存结果；Storage失败及原路由保留。
- [x] C3仅采用Boolean局部复用，不把它冒充位置复用/页表加速；无C5实现，不追加模块测试或新性能包。

## D6.5 C6：等价decoder工作（门槛不满足，未实施）

- [x] 核原图栅格供原图/四旋转采样、输出栅格供方向/采样和premul显示；未证明等价冗余，非readPixels余量不能直接删。
- [x] 不改codec/色彩/透明/分辨率/区域tile/动画或采样事实；无真实像素/资源RED便不实施，不照搬Android硬件位图策略。
- [x] 无C6代码变更，不新增完整格式/内存矩阵或库/GPU/CDS实验。当前三模式画面核验不代替这些矩阵，也不宣称这条路径永无优化空间。

## D6.6 C3：下载判断结果复用（Boolean采用，位置复用未实施）

- [x] 正常MangaDetailScreenModel.readerRequest→toReaderChapterRefs→ReaderNavigator RED证明远端每章2次，GREEN为1次；source0为0。过滤/重复章/下一章与library/detail一致性接线、下次打开重新判断均通过。
- [x] 仅局部downloaded值复用，无持久缓存、位置身份或过期策略扩张。真实provider同次重复物理定位未证明，故P4式位置传递不实施，不把两次Boolean等同两次昂贵磁盘扫描。
- [x] 一般来源只宣称回调去重；史莱姆source0该项收益0，不抵扣黑屏。C7没有新重复大读证据，不立项。

## D6.7 外部裁决与本地交付

- [x] 冻结两臂同点击锚点/fine OFF、源码/manifest/JVM与CBZ SHA；focused先过才构建。仅一次coordinator preview通过，build/sourceIntegrity/productionRuntime PASS，nativeInteraction构建时NOT_RUN保留；后续原生证据单列。
- [x] 完成单页两臂first/warm及反向first，共6份；因涉及共用模型补候选双页OFF/ON first和正常往返，共8份。双页没有同轮控制，不作提速或跨模式不退化证明。局部工作量采用足够，未为追噪声补满14份；复杂呈现/跨调用复用采用门槛未被绕过，因为本轮没有此类实现。
- [x] 唯一操作者核完整EXE/JVM PID、实际模式/页对，确认采集存活和有界tail新鲜度才点章节；正确完整漫画后GET/stop。8份latest OPEN+chapter+callback关联、帧序/PNG、输入覆盖、逐页/完整F/R/K数学与原帧确认通过。PARTIAL、长gap及E0 warm R上界保留，不按60fps补帧。
- [x] 首组F名义早57–66ms，反向名义早17ms但中断/环境漂移未控制，warm区间重叠、双页无控制：未证实稳定F收益。C1/C3按真实RED/GREEN、短章负控、无新缓存/等待协议及未观察到明确可见破坏的局部工作量门槛采用；不承诺全部模式性能不退化、R≤100ms或K≤60ms。
- [x] 一份结果及10行逐页CSV、内部维护/研究/采集经验、父入口与本计划同步。正常退出保存进度、精确关闭自有应用/捕获/QPC并核归属；保护用户实例/终端与主目录改动。本批验证后同批本地提交，不自动推送、整合、全量或发布。

## 剩余问题与下一轮边界

整体响应≤100ms、近黑≤60ms及追平官方体验仍未通过，属于未完成体验目标，不是本批候选已实现的承诺。C2/C4/C5/C6及位置复用均未实施，不能按checkoff对外宣传。

按当前证据先分析页表→当前开流，再分解Ready/绘制的实际依赖，其次核首次入口/页表抖动；所有区间都不能直接作为预计可回收ms。具体数值、未知项、停止条件及更新排序见[结果](../evidence/desktop-reader-d6-2026-11-06.md)和[分析末节](../evidence/desktop-reader-d6-optimization-analysis-2026-11-06.md#7-d6实施后的剩余优先级)。本批不自动进入另一候选或长期研究。

定向复验仅在新变化、失败或证据不足时运行：`ReaderIdentityReuseProductionTest`两方法、MangaDetailScreenModelTest新增远端/source0判断方法及原过滤重复章方法、LibraryScreenModelTest原library/detail过滤一致方法。精确已执行命令和XML见结果/sol证据；该列表不是完整模块/全量授权。Desktop无Spotless任务覆盖，本轮格式为git diff --check，不冒称跑过Spotless。
