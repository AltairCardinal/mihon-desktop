# Windows 阅读器延迟采集的已验证流程

2026-11-06在独立测试profile、与平板参考相同内容CBZ的Windows控制/实验preview上验证。桌面主机为i9-13900F、RTX 3070、Windows 11；PCE-W30仅为官方Android参考设备。主流程和采集边界沿用[Desktop验收](../agent-guides/desktop-acceptance.md)、[WGC窗口采集](../architecture/desktop-reader-render-trace.md)及[打开基准](../evidence/reader-startup-performance-baseline-2026-11-02.md)。

## 实例与数据

- 打包启动器会再启动同EXE的JVM子进程。实际窗口属于子PID，不能把Popen.pid直接交给WGC。核完整EXE、父子关系、唯一非最小化可见SunAwtFrame，然后实际收一帧才记录READY。HTTP服务ready不代表窗口或捕获ready。
- profile marker源码要求精确LF字节，Windows脚本用write_bytes生成。CRLF会触发Unrecognized test profile marker并呈现Failed to launch JVM对话框，不代表JDK缺失。保留失败现场后修模板。
- 真实CBZ原件为D:/Shell/Github/mihon/.test-tmp/slime-reader-remeasure-20261102/slime-vol04.cbz，SHA256 b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677。只复制至独立profile。合法空库种子里的source0书架作品/已下载章节能通过正常详情点击、生产进度恢复第5/187页；临时本地浏览入口固定从0开始，两种入口分别记录。
- Test Mode正常UI新建runtime同样可向当前scenario记录productionEvents，不一定需要fixture。读取GET /test/reader/state即可，点击前不要reset把正常页面清掉。chapter/generation/最新OPEN事件用于区别同进程再次打开。
- 若sky对已核身份的窗口返回旧终端残影，先读取该窗口最新accessibility树，仅点击其“最小化”，用activate_window恢复并再次观察，直到得到正确书架。此次在C3、CDon和ADon正式点书/采集前成功恢复；未操作终端、未提前进入阅读器。恢复期间截图不计性能样本，不能沿旧图坐标输入。

## 采集与输入

先观察独立窗口的书架和详情，确认正确章行/恢复页。由唯一操作者用computer-use技能node_repl/sky发送正常输入。现有capture_reader_window准备脚本及可复算原件在.test-tmp/desktop-first-open-20261106/luna/，调用既有scripts/capture-reader-compositor.py，不改产品绘制路径。

- 后台采集从创建时使用CREATE_NO_WINDOW。将正常UI动作准备好再Popen启动sidecar，即时返回PID，避免执行通道默认等10秒消耗采集窗口。
- 等ready.json记录实际首WGC帧后再点击。正常静止窗口也可能60fps回调，30秒/1800帧容易在点击前达到上限。现已验证90秒/10000帧的有界预算。脚本内部录制时间加10秒，而原生后端上限120秒，故外层上限110秒。不可直接外层传120。
- WGC的systemRelative100ns乘100为QPC纳秒。Windows HotSpot System.nanoTime同域。Node REPL未暴露process.hrtime，也禁止node:process；使用performance.now和常驻无窗口Python QueryPerformanceCounter helper，以多次往返区间交集校准。本次映射不确定度约19–38微秒。stdout必须按完整行解析，单次data回调可能只是一段数字或CRLF。
- sky.click自身前后包络约90–117ms，应把整个包络保留为输入时点的不确定范围，不能称精确EDT送达。若首匹配原帧为T1、前帧为T0、输入包络[I0,I1]，可见转换界限为[max(0,T0-I1),T1-I0]。第一张拍到画面的点值范围另列，不能漏掉相邻原帧间隙。

## 原件与裁决

实际WorldMap稳定WGC参考与ROI分别记录。本次1518×1143窗口使用地图纹理ROI(430,80,650,650)，近黑用旧详情有内容的章行ROI(676,220,823,120)，灰度均值阈值17/255。这些坐标只适用本次窗口，不复用到任意DPI/布局。

双页须先从mountedReader和稳定帧确认实际页对与左右位置，再为每侧取独立纹理ROI；避开工具栏及首次教学提示。2026-11-06关闭匹配首次/再次均为index[4,3]；开启匹配首次为[5,4]，保存进度后再次为[6,5]，A/C一致。自动匹配的首次与再次不能互作同内容对照。所谓“双页齐全”只表示两侧被测漫画纹理均出现，不表示每一像素未被工具栏遮挡。各侧近黑分别计算，与单页章行ROI也不直接作严格时长比较。

先核纹理、页码、方向和模式，再计算首图前近黑分段/总长/最长，保留前后可见原帧和间隙。首图后同样检查再黑。重复PNG按file缓存分析，逐接收帧保留独立QPC记录。采用相同ROI/阈值对照。

主动stop会使既有录制器记录native-capture-exit:1并标PARTIAL。本次received==written、missing=0、帧序连续，且输入到稳定地图已完整处于所录时间区间，因此可报告该局部转换端点并保留尾部告警；不得改写为RECORDED或完整物理扫描验证。采样间隙按实际界限报告。

区间结束后先正常退出阅读器保存进度，再关闭独立实例，核自有采集PID退出。编译/离线等待期间不继续派发输入或维持主动采集。其他用户实例与配置不操作。

## D4续测确认（2026-11-06）

- 用户恢复原生操作授权后，复用冻结控制/候选及已有focused结果，使用六份新隔离profile完成12条first/warm。中断的未点击C-single另存interrupted-C-single-20261106，原件不覆盖；每份的实际模式和页对由mountedReader与漫画画面共同确认。关闭匹配先D再C，其他模式先C再D；这是交替顺序，不能声称充分随机化或控制了磁盘/JIT冷态。
- 正式采样前移除MIHON_READER_STARTUP_OBSERVATION，保留原productionEvents。先实际看到完整正确漫画，再采0.5秒当前JVM子进程CPU，最后GET状态、写stop。避免早期GET争用阅读器锁；不得混用旧轮早期GET数据宣称同条件净收益。该CPU窗包含合法后台工作且以15.625ms量化，不是纯空转时间或整机占用率。
- 常驻QPC helper退出后必须新建helper、逐行读取器及校准函数；本次Node REPL旧函数保留旧cell绑定，单独重赋外层变量仍会读旧reader。正式输入前用新函数完成六次往返交集检查，校准失败时不派发输入。本轮正式映射宽约20微秒，输入时点仍保留约100ms的sky调用包络。
- 外部分析逐页携带nearBlack，不能把双页两侧混为同一个黑区。连续黑采样遇到长QPC gap不能按任意阈值拆开、当作有内容；保留整段、两端非黑原帧及内部未观测区间。R须实际核对明确阅读器载入反馈或漫画，旧详情点击涟漪不算。
- 本轮12条均主动stop，保留PARTIAL/native-capture-exit告警；帧号连续、原帧文件无缺失，首见两端处于已录范围。只报告该局部转换，不改写manifest或声称物理屏幕扫描验证。结束正常退出阅读器、POST shutdown，确认自有应用/采集退出，QPC helper exit0。

结果、逐样本边界和采用范围见[D4报告](../evidence/desktop-reader-d4-2026-11-06.md)；默认关闭的细观测与无进展唤醒规则见[内部架构](../architecture/desktop-reader-startup-observation.md)。

## D5同仪器对照与输入覆盖（2026-11-06）

- 控制E0与候选E1都包含同一低成本 `detailChapterClicks` 接线，细观测关闭。从 state 最新 OPEN 的 chapterId/openIntentNanos 唯一关联正常点击 callback，不能将累计事件里的第一次打开当 warm。callback→外部帧是主比较口径；原 sky 输入包络→外部帧另列，原始系统输入前段仍未知。实际工具包络有约93–643ms，不能统一写成100ms。
- profile 从 `.test-tmp/desktop-first-open-20261106/sol/normal-profile` 复制，核精确LF marker、原件SHA、恢复页及设置。启动参数中的完整 EXE 必须与所给 preview manifest 的 artifactPath、目录 tag 一致；E0/E1窗口标题相同，不能靠标题区别。nativeInteraction 构建时的 NOT_RUN 保持原值，后续原生结果写证据，不篡改构建 manifest。
- QPC helper 使用独立 TCP 逐行协议与新 client 对象；正式输入前六次校准。helper 空闲超时曾在输入前触发，校准失败不派发点击。辅助进程可有界延长空闲时间，结束发 stop 并核实际命令归属；PID会复用，禁止按旧PID表批量终止。
- READY 只证明曾捕获首帧，不能证明90秒后仍在录制。输入前若校准失败或处理拖延，必须重新确认采集仍存活且最近帧在当前时间附近；必要时停止并重建该次采集，不能仅凭旧 ready.json 继续点击。D5一份 E0-dual-off/first 在90秒上限后17.135秒才输入，即使 received==written/missing0仍无输入画面覆盖，必须标技术失效并保留。另建 fresh profile/JVM 补采 first，不用慢样本替换规则择优。
- visiblePageIndices 为集合顺序，左右页以 currentDisplayPageIndices 和实际画面为准。D5关闭匹配为[4,3]；开启匹配 first为[5,4]、正常保存进度后 warm为[6,5]。每页F/K及完整页对F分别保存。warm实际不同页对不与first交叉算收益。
- 本轮不采D4的0.5秒CPU窗，看到正确完整漫画才GET、停止WGC；离线读图期间不维持采集。主动停止的 PARTIAL/native-capture-exit:1继续保留，frame编号/PNG连续与输入到首图覆盖逐份核实，不称完整扫描验证。结束正常退出阅读器，POST shutdown并核精确EXE/自有采集/helper退出。

原件、14份有效样本及1份技术失效、采用裁决见 [D5报告](../evidence/desktop-reader-d5-2026-11-06.md)。

## D6：缓冲日志新鲜度与恢复后对照

- 先前用events.jsonl文件tail距当前≤1秒作为点击门槛过严。实际`CompositorWriter.log`以默认缓冲打开文件、逐行write而不逐事件flush；本轮静止窗口的已落盘tail可落后约0.7–2.3秒，不能单凭它认定录制结束。不要为修工具门槛而在两臂中途改变录制器flush或测量负担。
- 本轮成功预检组合为：核自有capture进程仍活动、终态manifest尚未产生、读取tail的前后QPC与最后已落盘帧相差≤5秒，健康检查后≤10秒才派发输入。它是有界存活防护，不是“最新帧的精确时间”。最终仍逐份核实际input包络、callback到正确漫画原帧均处于录制覆盖内；旧ready.json不替代此核验。预检失败发生在点击前时，不创建input.json。
- Esc中断的末份E0在章节输入前结束，整份profile和raw保留为`prep-only-E0-single-reverse-interrupted`，恢复后另复制fresh profile/JVM采first。已有慢样本不替换。反向pair中断前后相隔较大，必须披露负载/温度/磁盘冷态未控制，不能因区间刚好分离就给优化记稳定毫秒收益。
- 8份正式样本全部保留PARTIAL、实际gap及每页端点；原始QPC/回调关联由主代理复算，R/F命中与前帧另目视确认。E0 warm只有首漫画的R上界，不能把fallback过渡区间当成已确认的响应下界。正常退出阅读器后关停自有应用、采集与clock helper，按完整路径/命令核归属。

计数收益、8份/10页CSV、准备错误和未通过目标见[D6报告](../evidence/desktop-reader-d6-2026-11-06.md)。本节记录成功方法和限制，不新增永久采集系统或完整测试授权。

## D7：首次落盘等待与辅助状态接口

- 延续D6的活动进程/无终态manifest/tail≤5秒组合。采集刚启动时events.jsonl可能尚未生成首个可读取frame，不能立刻按采集死亡处理；本轮在派发章节点击前最多等待12秒、反复检查同一采集，成功后立即输入，仍要求健康检查到输入≤10秒。只等待录制器启动，不重发章节点击、不替换已发生的慢样本、不改变writer flush或后端负担。最终局部覆盖仍逐份检查。
- 辅助翻页身份必须读取 `/test/reader/state`。通用 `/test/state` 可以返回200且含HomeScreen等路由信息，但不包含mountedReader/sharedUnits/location，不能据此证明阅读器页对、代次或翻页成功。本轮误取的page-next/back原件保留、未用作身份验证；原生画面观察与正式采集的reader/state分开说明。
- 同进程再次打开可重建runtime并再次使用generation1；累计productionEvents中的OPEN_PAGE须按最新OPEN intent时间之后及章/代次/页筛选，不能只按generation混合首次与warm。OPEN_PAGE没有intent key，时间范围关联不称直接精确join。当前阅读位置取reader的currentPage，独立scenarioCurrentPageIndex不能代替；fallback R只导出上界。
- 采集完成后，先让每份stop产生终态manifest，再翻页或进入离线分析。ON可能在同一source index上重选重叠unit，正常往返必须记录实际页对/位置及对照，不能以index相同断言恢复同一物理frame。

12份对照、PARTIAL/gap与采用裁决见[D7结果](../evidence/desktop-reader-d7-2026-11-07.md)。复用既有采集器和有界准备脚本，没有增加正式产品接口或永久采样系统。
