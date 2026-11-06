# D7：页表就绪到当前页开流

**裁决：不采用并精确撤回性能候选。** 单页首次正确漫画的外部F区间重叠，关闭匹配双页首次候选明显更晚，开启匹配首次较早但不能抵消其他模式结果。保留默认关闭诊断和真实回归，未取得可确认的新首开体验收益；未达到≤60ms近黑目标，不推送、不发布。

执行入口为[D7计划](../roadmap/2026-11-06-desktop-reader-d7-open-stream.md)。基线为main `3d7652e30698508aa0c17798104037708d879b02`；源码、构建和采集均在 `D:/Codex/worktrees/mihon-main-reader-delivery-20261106`，没有改另一工作树的Android未提交内容。Sol medium负责production及真实RED/GREEN，Luna medium负责限定证据提取和离线帧分析，主代理负责唯一桌面输入、独立审查和最终裁决。

## 证实的调用关系

页表后的模型/呈现路径包括session StateFlow → model.acceptSessionState → 身份/共享呈现投影 → Compose实际viewport → slot创建/应用及pin → 显示内容OPEN_PAGE；另有scheduler物理内容准备及encoded事实发布。这些工作可交错，不能将所有时间戳强串成一个严格串行依赖。归档copy与显示OPEN是不同读取语义，二者间隔不能全部归为header读取、磁盘I/O或一个锁的等待。

三份当前主干fine ON正常打开都在页表就绪前完成了首次Compose apply，分别提前49.568、27.111、20.926ms。页表后首次accept在约0.08–0.14ms内进入锁，inclusive结束约19.054、24.555、22.604ms；这不是独占CPU，也不能和其内投影相加。当前样本没有证实“订阅尚未安装”主导了正常打开的全部等待。

| 相对PAGE_LIST_READY，ms | 单页 | 双页OFF | 双页ON目标index4 |
|---|---:|---:|---:|
| settleViewport入口WAIT | 51.331 | 135.959 | 138.905 |
| 当前目标归档copy开始→结束 | 66.689→73.062 | 111.183→115.492 | 112.461→115.068 |
| 显示OPEN_PAGE | 118.507 | 275.628 | 150.600 |
| 应用FIRST_PAGE_PRESENTED | 192.864 | 418.337 | 497.725 |

这些为不同样本的混合wall端点，不能跨列相减确定模式因果。settleViewport的marker不覆盖所有物理准入入口，OFF/ON的目标copy早于表中WAIT；不能拿WAIT当作该copy的触发时间。copy marker只有chapter/index，没有请求purpose/调度因果关联，本表未独立确认这次copy的最初准入。ON另一页index5的OPEN为217.127ms。双页观测容量1024，OFF/ON分别丢弃1/82个后续事件；所列目标端点仍在保留范围，不称全程trace完整。

## 唯一候选与真实行为验证

原Screen通过LaunchedEffect在首次composition apply后订阅session。真实factory夹具证实：尚未挂载时session已Loaded但model仍Loading；迟到的旧代输入还能覆盖同步激活的新章。因此只试一个候选：将元数据订阅移到既有runtime IO scope，由model拥有唯一job，并在model dispose取消、拒绝旧代。实际物理页工作仍等待真实viewport，没有提前settle、开流或新增缓存。

独立审查发现IO订阅使原先“先发布session、再发布derived identity”的中间状态可被EDT观察。新增真实StateFlow逐emission观察RED后，限定accept/ensure/publish三个函数：先按incoming session准备owner，再一次CAS发布session及对应身份/投影；CAS变换保持纯函数，代次/owner等副作用在外部锁内执行。这个修复属于候选的并发前提，不是对主干已发生故障的宣称。

候选最终17项不同focused测试通过，覆盖factory/Screen真实接线、单页/OFF/ON、1/187页、挂载前后Loaded、旧代、实际CBZ无进展及晚到输入、身份复用与生命周期。持有job身份测试后来定向重验1项通过，故为18次执行、17个不同测试；没有再跑全模块/全量。早先用“零accept事件”或aggregate subscriptionCount恒等判断幂等的失败属于夹具断言问题，原件保留，不计production故障。Desktop无Spotless任务，格式检查使用git diff --check。

## 同条件包与采集口径

两臂都包含相同的默认关闭细分marker；正式12份均fine OFF，只保留既有Test Mode正常章节点击锚点。每份按最新OPEN的chapterId/openIntentNanos唯一关联callback；callback不是系统原始输入。sky输入前后包络及QPC校准单独保留，不将约100ms工具调用当精确输入时点。

离线沿用已校正D4纹理参考/ROI引擎与本轮adapter，当前性能样本只读本轮D7 raw。warm重新创建runtime时generation可再次为1，内部OPEN_PAGE还须限定在最新OPEN intent之后；只按章/代次筛选会混入上一次打开，逐页导出已校正。实际阅读位置取currentPage，scenarioCurrentPageIndex不作当前页；fallback R下界留空，不借F前帧补造R下界。

| 包 | tag | productSource SHA256 | artifact tree SHA256 |
|---|---|---|---|
| 控制 | 20261106T143916Z-c1b9bae6 | df591411b9b46d6e720e80b014c1da478a3fc139547087cd40dc34533de09ace | 64d4db28b49cf99f856db1b5e3b74e5abda32664681bd42c3b1662f755f15dc0 |
| 候选 | 20261106T150124Z-847822c1 | 55d52484a1fceeaeb7a4b63074379b97cc4c0fd6d63068715b531e47a7300c66 | ae32059cd1b46ec1e750a0c20826bb98c52a9acc7fcdf52e23a92c54075935d6 |

两包sourceCommit均为基线3d7652加冻结dirty输入，不是之后的最终提交。build/sourceIntegrity/productionRuntime均PASS；manifest中的nativeInteraction=NOT_RUN保持原值，本报告另记实际原生测试。两包均没有可选CDS archive，不将其差异用来解释收益。

同一史莱姆第04卷CBZ，187页、41,380,343字节，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。复制同一隔离profile，正常书架→详情→章节，从index4、RTL开始；不操作用户实例。顺序为单页C→E、OFF E→C、ON C→E，各first/warm一份，共12份，不把顺序交替称随机化或磁盘冷态控制。ON first实际显示[5,4]，保存进度后warm起点5显示[6,5]，只在同起点两臂间比较。

预检要求自有capture活动、无终态manifest、已落盘帧tail≤5秒，健康检查后≤10秒才派发输入；启动初期tail尚未产生时只在点击前有界等待，不创建伪样本。见[采集经验](../automation/DESKTOP_READER_LATENCY.md)。完整正确漫画出现后才GET /test/reader/state并停止WGC；离线读图不与采集并行。

12份manifest都保留PARTIAL/native-capture-exit:1，missing=0。主动stop导致的尾部状态不能改写为完整录制；输入到首图的局部覆盖、原始gap与纹理命中另逐份核对。WGC是合成窗口画面，不证明物理显示器扫描时点。

## 外部结果与裁决

以下为正常章节callback→正确漫画纹理首见的相邻原帧边界，单位ms；单页用目标index4，双页用完整两侧。每页端点、R来源、输入包络、K累计/最长和原始gap见[20行逐页CSV](desktop-reader-d7-2026-11-07.csv)。正收益范围表示控制减候选，只表示这对样本的观测差，不是稳定因果收益。

| 模式/次序 | 控制F | 候选F | 观测收益范围 |
|---|---:|---:|---:|
| 单页first | 334.425–351.092 | 309.214–342.546 | −8.121～41.878 |
| 单页warm | 151.873–168.540 | 84.843–101.510 | 50.363～83.697 |
| 双页OFF first | 533.403–583.402 | 1023.780–1090.445 | −557.042～−440.378 |
| 双页OFF warm | 136.239–161.239 | 111.254–136.254 | −0.015～49.985 |
| 双页ON first | 701.534–718.201 | 585.675–619.009 | 82.525～132.526 |
| 双页ON warm | 110.475–152.141 | 119.261–160.930 | −50.455～32.880 |

单页first没有可辨方向，故不启动预留的2份反向fresh first；即使warm一对较早，也没有满足“首次可辨改善并反向重现、warm/受影响双页无可辨退化”的采用门槛。OFF first明显更晚，ON first较早，不能把三种模式混算均值来抵消负结果，也没有证据将该差异全部归于订阅迁移。

| first阶段，ms | C单页 | E单页 | C双页OFF | E双页OFF |
|---|---:|---:|---:|---:|
| callback→OPEN intent | 32.182 | 31.473 | 38.976 | 167.693 |
| OPEN intent→PAGE_LIST_READY | 74.715 | 80.389 | 81.342 | 293.278 |
| PAGE_LIST_READY→目标4 OPEN_PAGE | 137.984 | 127.430 | 286.107 | 372.551 |
| 目标OPEN→应用FIRST_PAGE_PRESENTED | 67.825 | 58.662 | 126.979 | 207.208 |

OFF first的差异已出现在OPEN intent前及页表前，也出现在后续阶段；不能把总差全部归到页表后某个函数，或者反过来认定全部为环境负载。候选可能改变线程竞争和初始化时序，本次没有exclusive CPU/调度证据排除这些原因；缺少稳定性与安全采用证据时撤回，不据此宣判未来收益只会更小。

单页first最长/累计近黑C为224.999–308.331ms，E为183.332–316.662ms，仍重叠；OFF first两侧C为416.658–549.987ms，E为616.654–1058.313ms。ON first地图/人物的近黑分别C为499.996–674.994/574.995–691.661ms，E为408.326–558.323/458.325–591.657ms。双页各侧独立计算，不把两侧相加为人的黑屏时间。所有页首见后的已采帧未检出近黑段，不代表未观测间隙或结束后的持续保证。

E单页warm与E OFF warm未命中既有loading文字+spinner模板，R只取得首漫画上界101.510/136.254ms；F的前帧不能充当已确认R下界。其余R保留原帧转换范围。各first最大观测gap为C/E单页66.665/99.998ms、OFF174.996/374.994ms、ON208.333/166.662ms，不能用固定16.7ms替代；连续黑样本中间的gap仍视为未知，不拆成“有内容”。所有12份帧号连续、QPC=systemRelative100ns×100、PNG无缺失，实际输入到完整F均被覆盖；这些局部有效性不抹除PARTIAL告警。

根独立复算12份输入/最新OPEN关联、20页F两端、完整双页F、QPC原件覆盖和source冻结，并目视全部R/F前后帧；缩略联系图及复算回执留本轮ignored目录。原始硬件负载、温度、文件系统冷缓存与JIT状态没有完整控制，故不估计总体置信区间、探针无扰动值或相对Android官方的精确净差。

## 正确性边界与原件

单页候选原生观察地图→人物→地图；OFF候选地图/扉页→目录/人物→地图/扉页。ON观察正确漫画与翻页，但warm [6,5]后往返最终出现人物/地图。两臂warm本来均含重叠的[5,6]/[6,7]共享unit，现有导航不承诺返回同一旧物理页对；候选未改共享导航，却可能改变事实到达时序。这既不能确证为新回归，也不足以宣布ON精确往返验收通过。附加page-next/back.json误取通用/test/state，不包含reader身份，只留作无效辅助原件，未用其证明页对或代次。

全部原件保留在本树 `.test-tmp/desktop-first-open-d7-20261106/`：`actual`下三模式12份input/callback/state/WGC，三份diag；`sol`下真实RED、focused XML、控制/候选manifest及7文件冻结SHA；`root-review.json`和`root-formal-audit.json`为根审查。准备阶段端口10013、无输入中断、首次capture日志未落盘的等待均保留，没有拿慢样本当技术失败删除。

## 最终代码、验证复用与后续边界

完整候选patch及撤回前后7文件SHA保全于`sol/ignored/`。Factory、Screen及Factory测试与基线HEAD完全一致；保留的Region/Session/Model三个production诊断文件及CriticalPath测试，逐字节SHA与已构建控制一致。最终仅新增默认OFF的阶段关联，不把已撤回owned job/原子发布称为产品修复。

最终状态复用控制诊断`stages-focused.xml`4项与`observation-focused.xml`2项，再加锁内generation修正后的单项补验；6个不同测试、7次执行均通过。源码未再变化，control preview已有build/sourceIntegrity/productionRuntime PASS且完成本轮三模式正式对照，故不为收口重建包或重跑全模块。候选的17项测试结果只作为实验的实现/并发证据，不冒称撤回后重新执行过它们。

本批保留D3/D4/D6及扩展兼容，不引入第二候选。后续值得定位的是：已及时安装订阅时的19–24ms摄入inclusive里哪些为可删的真实CPU工作，以及真实viewport/内容事实到slot apply之间的等待；先取得相同请求的调度/排他耗时证据，再设计行为RED。D5提前物理准备和D7订阅迁移均已撤回，不能把旧patch换名字重试，也不能承诺把混合wall窗口全部回收。主干推送、公共功能说明、跨平台和完整发布矩阵不在本批。
