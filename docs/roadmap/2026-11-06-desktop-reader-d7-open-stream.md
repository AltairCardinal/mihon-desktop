# D7：页表就绪到当前页开流

2026-11-06。用户授权先做剩余方向第一项。基线main `3d7652e30698508aa0c17798104037708d879b02`，工作树 `D:/Codex/worktrees/mihon-main-reader-delivery-20261106`；先核HEAD/工作区和协调器。保留已验收扩展兼容及D3/D4/D6，不触碰另一工作树的Android未提交文件。父入口：[首开计划](2026-11-05-desktop-reader-first-open.md)，前置：[D6结果](../evidence/desktop-reader-d6-2026-11-06.md)、[D5反例](../evidence/desktop-reader-d5-2026-11-06.md)。

## 目标与边界

唯一目标是减少PAGE_LIST_READY到当前目标OPEN_PAGE的真实依赖/工作，并令正常点击后的正确漫画首见F提前。旧D6窗口single约139–182ms、双页OFF约318ms，属于混合wall；此前20–60/30–100ms是工程预估，允许收益0，不是必须兑现的数字。当前主干已换协程/兼容接线，旧D6包不能充当同条件控制。

先查模型摄入/投影、Compose实际视口提交、scheduler与锁/预算；不处理Ready后显示、decoder简化、归档/下载位置缓存或整套配对算法。D5提前初始物理页准备因反向/双页负结果已撤回，必须有新的因果证据才能提出不同候选，不直接重贴旧patch。不得以去掉身份/进度保护、降低画质、保留旧图、预热书库或暂停全部后台任务换速度。

## 执行预算与职责

预计45–90分钟；Sol medium拥有production/tests、真实RED→GREEN及唯一串行Gradle，Luna medium拥有本轮ignored目录的限定旧证据提取和离线分析，主代理拥有docs、一次独立审查及唯一桌面输入/提交。两代理不操作GUI、不提交、不相互覆盖。独立审查最多一次，实际问题允许一次定向修复复验。所有重型Gradle走协调器，Windows启动沿已成功的 `.\gradlew.bat` 或绝对路径，不用 `./gradlew.bat`。

最多一个主干控制preview和一个候选preview；诊断优先现有开发runtime或控制包，不为每个marker重打包。最多三份有界fine ON诊断与14份fine OFF有效正式样本，不同时离线解帧和采集。默认同史莱姆04卷187页、index4、RTL、相同profile设置/CBZ SHA；单页和双页OFF优先，触及共用模型/呈现时ON必须核正确页对和正常翻页。旧raw仅用于归因背景，正式对照重采同主干控制。

不执行完整模块/全量、正式版本分配、自动推送或公共功能承诺更新。诊断工具失败先修工具，不择优丢慢样本；没有安全机制时提交已证实定位与不实施理由，不造收益。

## D7.0 同请求阶段定位

- [x] 沿当前production画出PAGE_LIST_READY→session collect/accept→ensure/publish→Compose commit/viewport→调度准入→目标OPEN_PAGE的实际调用链。Luna仅提取原D4/D5/D6明确关联字段和gap，标明旧仪器/旧基线，不能跨样本相减。
- [x] 复用默认关闭有界ReaderStartupObservation；只为缺失的入口/锁前后/viewport与目标准入补必要关联，携带chapter/generation/当前页，普通运行OFF零记录。诊断接线先真实focused验证，不新增同步落盘、每帧整章hash或采样系统。
- [x] 在当前主干正常打开或真实mounted production夹具定位至少一个具体工作/依赖，分清inclusive与exclusive、CPU与wait；无时间相关性不定因果。输出唯一候选及反例/停止条件，主代理核后才改加载行为。

## D7.1 唯一候选真实RED与最小实现

- [x] 先真实production/owner/Compose或scheduler接线RED，证明候选所指的多余工作/等待可控复现；不复制实现或扫描源码造测试，不用绝对ms断言。
- [x] 最小实现只改变该机制，保留代次、内容替换、位置/配对开关、重试、迟到输入、取消与资源退出、首次像素及完整单元进度。需要跨调用复用时必须列全实际输入；不完整则停止该设计。
- [x] 同focused GREEN、受影响接线及diff检查；主代理独立核真实RED原因、production wiring、竞态与短章负控。修复仅针对实际问题，不扩大候选。

## D7.2 外部采用裁决

- [x] 冻结控制/候选source/EXE/manifest与相同测量接线。两臂fine OFF、同正常点击callback及输入包络；不可从构建后修改追认包身份。
- [x] 先single/OFF每臂first/warm共8份；候选ON正确性/往返，若共用加载机制则补同内容控制。可辨方向再补反向fresh first，最多14有效。正式采集前核自有capture存活/有界新鲜度，首图前不GET；完整正确漫画后GET/stop，保留所有失效原件及原因。
- [x] R、每页/完整F、K最长/累计与首图后再黑、原始gap/PARTIAL及actual页序分列。主代理独立复算输入/latest OPEN关联和原帧。不能把更早内部OPEN_PAGE当成漫画更早出现。
- [x] 采用须真实依赖减少、外部F同起点改善可辨且反向重现、warm与受影响双页无可辨退化，并保持原图/恢复/翻页/取消/进度；不满足则撤回性能候选，保留诊断事实，不继续第二候选。

## D7.3 收口

- [x] 一份结果及必要逐页CSV、当前内部维护规则和父入口同步，记录实际包/source、采用/撤回与剩余未知。精确关停自有应用/采集/clock，正常退出保存进度，不关闭用户实例。
- [x] 只暂存本任务文件，focused/格式及本轮证据齐全后同批本地提交并报告hash；不自动推送，不把R≤100ms/K≤60ms或正式发布勾选为通过。若未采用，明确说明用户体验未取得证实改善。

## 2026-11-07 本批收口裁决

结果见[D7报告](../evidence/desktop-reader-d7-2026-11-07.md)及[20行逐页CSV](../evidence/desktop-reader-d7-2026-11-07.csv)。上面的checkoff表示候选研究、必要验证及不采用裁决完成，不表示采用条件通过。

- 三份fine ON诊断、真实factory/StateFlow RED→GREEN、候选17个不同focused与一次独立审查/必要原子发布修复完成；两包冻结，12份fine OFF三模式first/warm全部保留。
- single first F区间重叠，OFF first候选更晚，ON first较早/warm重叠；没有满足首次改善可辨且跨模式安全的条件。不加预留反向2份，不试第二候选。候选ON精确往返状态未完整确认，不能写成通过；实际漫画与翻页观察及重叠unit边界详见报告。
- 精确撤回元数据订阅迁移及配套并发改动/候选测试，三个候选文件与HEAD一致；四个保留诊断/测试文件SHA与控制完全一致。复用控制6个不同focused和代次修正1次补验、控制构建/真实对照，不为收口重建或全量。
- 保留D3/D4/D6和扩展兼容；唯一结果/必要CSV、内部架构、采集经验和研究索引同批本地提交，不推送或更新公共功能说明。本批11个相关文件围绕阶段诊断、真实测试及同一实验裁决内聚；没有共享协议/调度行为变更。
- [ ] 整体响应≤100ms、近黑≤60ms及追平官方：仍未通过。当前没有已确认可交付的D7首开毫秒收益；剩余混合wall仅是下一轮归因范围，不是承诺可回收收益。
