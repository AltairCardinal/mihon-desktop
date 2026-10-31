# 阅读器 CP1 A 方案收口

日期：2026-11-01（Asia/Shanghai）。来源交接：`1e47b14ece19ae0a934e01f85235732e383a069c`。本文件是本次唯一收口记录；历史研究结论与原始数据保持原样。

## 决定与完成条件

- 恢复父设计 §7.9.7、整改 R3：任一原主要场景中位改善 ≥ max(该场景基线中位 × 15%, 100ms)，同时正确性与逐页/逐动作组 16ms 护栏通过。
- 复用 L1/v3 各 50 条有效样本。T170 重开 completeUnit 为 744.918→592.997ms，改善 151.922ms，门槛 111.738ms；target-source 为 736.912→588.198ms，改善 148.714ms，门槛 110.537ms。原场景收益通过。历史混合中位改善 15.012ms、未达 100ms 的 NoGo 保留。
- 不新增性能实验，不以未成立的数学不可达证明收口；排除 Skiko/D3D/VSync/窗口慢态研究及 R10 新优化。
- 工程交付：获准产品/测试整合进入本地 main，必要验证与 Windows main-preview 就绪。
- 用户体验：项目所有者于 2026-11-01 明确确认本轮 Windows 预览体验通过。工程交付与用户体验两项完成条件均已满足，Goal 可在验收文档归档后标记 complete。

正式原件仍在原研究树的 `.gradle-coordinator/research-evidence/cp1-s4-20261027/`：`validated-pair-v2/baseline-samples.jsonl` SHA256 `9e17ccc9bba5539063aba0ea30c2bdd6bc33f336294315f184750985ff0e4cba`；`post-t170-candidate-v3/candidate-samples.jsonl` SHA256 `5f801bed4bda4db1a3dbee3b458f73027588e58d2f830598060e7b8fe495daf8`；`comparison-v3.json` SHA256 `61f8dd9e4b3f1a201dfb16b87274ea1ad5219c62478fd61d0fba86752983acb0`。本轮只读核验均一致，两侧 50 条有效、同 oracle、完整样本/动作配对通过，uncaught 为 0。动作/页组最大中位退化分别为 15.447/8.347ms，均不超过 16ms；没有重写历史 `r3Passed=false` 的混合裁定。

## C0 接手与范围

- 已核目标：`D:/Shell/Github/mihon`，main 基线 `a59cfab401494d590aeacbb388f017a4ad49d1f9`。原工作区 AppVersion 修改与 `.codex-remote-attachments/` 保留；AppVersion 接手 SHA256 `555d42abf87335aa333182df5118c1ad9cdb43ebb93da8b5eed2e182055e6ee5`。
- 从该 main 创建独立整合树 `D:/Codex/worktrees/cp1-final-integration-20261101`，分支 `codex/cp1-final-integration-20261101`。
- 完整候选来源 `a635739aeed2e38f02beaabead07ae821876d0a8`；共同基点 `24a2e6bc665cae8604c7cadd2814fb4fe5a70561`。按三方净差异整合，不合并研究历史、不只摘末笔提交。
- 候选文档树与诊断树身份/既有差异已核对，与交接一致；不修改这些树。
- 主控负责范围和独立审查；Sol medium 拥有整合树 CP1 必要产品/测试改动；Luna xhigh 只读映射与串行定向验证。主控单独拥有本记录、提交和 main 整合。

| 行为/区域 | 最终处理 |
|---|---|
| 共享配对、事实身份/预算/取消、memo/剪枝 | main 自基点未变，纳入候选必要产品与行为契约测试 |
| Android 事实加载/双页 adapter | 纳入候选必要闭包；保留 main 单页进度 `6aeb27cb9e`、测试 `b5111ef56c` |
| Desktop 内容 owner/pipeline/session/model/绘制 | 纳入候选必要闭包；三方解决与 main 新功能重叠，保留全屏、单页及 trace 能力 |
| instrumentation benchmark、研究构建配置、独立性能计时工具 | 排除；正确性窗口回归另保留必要入口 |
| `880037a6` | main 仍使用全 displayUnits ID 作为动画 effect key，无等价实现；单行修复及回归已有局部红绿，但 phase48 筛选 T170/T30-reopen target 分别退化 25.8479/18.7221ms，越过既定 16ms 保护；没有原候选上下文的独立安全采用证据。本轮保持隔离 |
| `370f467` | main 没有 exposure draw revision/旧意图同步等价实现；涉及 Zoom/绘制依赖并耦合后续 ledger 诊断，phase58 组合 T170 complete 退化 135.0662ms、reopen 17.0594ms、next2 18.6294ms，越过保护。本轮保持隔离 |

## C1 实施、证据与审查

首轮整合源已稳定：60 个产品/测试文件，另本记录一份；无依赖或版本修改。共享与 Android 必要产品净差异保持候选实现；Desktop 的 `ReaderScreenModel` 保留 main 后续 certificationTargets 清理/过滤和全屏接口，`DualPagePagerViewer` 沿 main 的 ReaderPageImageContent 提取结构追加候选 Ready 门禁。其余 main 全屏/单页/trace/源请求接线不回退。

维护边界：事实读取许可归属具体 request/publisher，不能按同页把普通加载误标为配对工作；旧发布者的物理任务在真正退出前仍计忙，换代退款不能释放新代额度。memo 按实际记录的完整输入依赖复核，超过存储边界走正常认证而非丢失正确性。首次曝光须取得 Certified 槽位并通过当前物理内容/attempt/lease 校验；缺少可选 samples 只允许接纳真实几何，不伪造 Computed。已显示像素、手动配对、双页/宽图、缩放和进度继续沿现有生产链路，忙态/内存暂停按真实退出或 relief 恢复，不能变成永久失败。对应契约测试是维护入口，不能用源码字符串或模拟旧政策替代。

保留最小既有测试辅助：三个 header purpose 枚举用于普通/真实/投机预算接线断言；默认 null 的 P5 round observer/completion gate 用于真实 publisher 完成待消费、跨代退款及恢复竞态回归，不带 phase74 设施。窗口回归从原性能套件提取为 `DesktopReaderCanonicalNativeWindowTest` 两方法和 `ReaderCanonicalDrawReceipt`，保留完整 oracle、输入指纹、production binding、实际 draw、cleanup/uncaught 校验；移除正式 50 采样、统计 consumer 和 raw 写出。排除 benchmark 构建配置/instrumentation、memo 旧容量成本对照和模拟旧 admission 政策测试；保留直接执行生产 read-set/证书依赖的 round 反例契约。

历史真实 RED 与最终 focused 证据按统一审查 §19–22 复用：memo resident 失效与漏记 NotAnalyzed 分别由 A01/A02 真实红定位并修复；stop/refill、跨代退款、非协作 fetch 实际退出有真实生产红绿；§22 的 T170 错伴首次曝光、无 samples 几何拒收、跨 holder 旧 Ready 三个反例均有原始红绿。当前冲突是保留 main 附加逻辑并应用既定候选行为，未另创产品语义；若回归发现新的集成故障，仍须先确认真实失败再最小修复。

必要验证已闭环：共享契约、Android adapter/HTTP/近期单页进度、Desktop owner/pipeline/Model/publisher/进度与实际绘制，按风险选择 focused 类/方法；main 的新 source API 接线与按请求携带许可组合不能只凭旧 HTTP 证据继承，需当前真实 HTTP/body 回归。重型任务由协调器串行执行，使用低堆和原有资源保护，不运行完整模块/全仓或正式 50。

共享首轮 18 个受影响契约类执行 166 项，165 通过、1 项真实失败。失败位于 `ReaderPairingCertificationResearchTest` 新增增长反例的 manual 分支：自动分支已证明依赖扩展，单个有效 manual start=29 则依真实 scan 的显式手动优先语义直接认证 [29,30]，不应等待无关目标 side。仅修正该陈旧夹具，保留自动增长/越出邻域断言，并断言 manual 补齐分析前后页对及依赖不变。随后同失败方法及既有 manual 语义方法 2/2 通过；其余 165 项结果复用，覆盖原 166 项范围，不算 167 个独立用例。生产代码未因该修正变化。

Android 首次验证在测试编译阶段被基线未改的 `PagerRippleTest.kt` 挡住：它引用不存在的 pageTurnRippleMode/prepareRipple 等接口，0 项实际测试执行。已独立核该测试及 `Pager.kt` 相对 main 基线无差异，CP1 和近期单页进度回归无 Ripple 引用。保留失败原件，不追加无关产品修复；后续定向验证以 ignored init 的固定 12 源必要闭包编译，生产源码与原 focused selector 保留。该 Ripple 类未验证，不能声称 Android 全模块通过。

资源和编译适配：全测试源编译曾触发 4GiB 物理保留线，确认本任务进程实际退出、按 SHA 原样归档 fault 后，将测试源码编译收窄到 5 目标类及 7 个必要 helper（12 源）；生产代码仍完整编译，运行仍为原 15 个方法。Desktop 对应为 12 目标源及 7 helper（19 源）。临时 init 使用现行 KotlinCompile 的固定源列表及实际集合核对，不写入产品；源码编译与执行分开以释放编译器驻留。所有任务经现行 coordinator 串行，保留 4096MiB 物理/8192MiB commit 余量，资源不足不关闭用户应用、不降低保护；用户释放内存后实际余量恢复到 9328MiB。

Android 首次实际运行的 15 个方法产生 23 条记录、10 条失败（项目默认自动重试 2 次），不能据重试后绿认定全部通过。已确认并仅修正三项陈旧夹具：与串行 samplingMutex 冲突的双 decoder 互等；证书成立但物理采样尚未退出时提前比较计数；runTest 虚拟时钟抢过真实 I/O。保留 >16 页、每页/总次数、预算、证书、回收及时间阈值断言，未改生产。随后关闭自动重试的 5 项复验为 4 通过、1 失败；单项通过仍不足以解释组合失败，继续原 6 项组合确认。组合诊断显示旧 adapter 的 MainScope 取消后，后台 I/O 尚需旧 Main 调度器完成真正退出；原 @After 提前替换调度器/DI，遗留全局 optional 计数。仅修夹具销毁顺序：保留旧环境，等待并断言本 adapter Job 完成及计数归零，再恢复环境。未清零计数或修改 production。最新 `cp1-c1-android-lifecycle-six-20261101` 原 6 项同进程组合 retries=0 全通过；此前 Zoom 3 项无重试复验通过，其余原 15 方法中的通过结果按未变行为复用。普通单页真实像素进度回归已通过。实际 Android Test 设置由项目覆盖为 heap=1g、forkEvery=1；新验证明确 retries=0，不把最初临时 Test512 设置误报为实际值。

Desktop 首轮 32 个精确 selector 实际执行 67 项，60 通过、7 失败。原件保留于 `cp1-c1-desktop-jvm-focused-20261101`。已按真实生产语义修正旧夹具：canonical cover 后的 pair 为 [1,2]；阻塞栈入口为 reserveClassified；换代 budget shell 不同但实际共享窗口；Computed 投机成果可直接 promotion/cache 复用，不能强求同页重复 decode。stop/refill 全部观察显式限定 target30、同一 owner 和逻辑 intent，排除 warmup0。`cp1-c1-desktop-seven-recheck-20261101` 原失败七项复验为 3 通过、4 失败；容量退款、dispose 保护、stop/refill 已通过，其余真实进度、全屏翻页像素、同页 retry 与跨模型恢复仍在定位，未声称完成。最新 21 个 Desktop 修改源的格式检查与 19 源定向测试编译通过。

`cp1-c1-desktop-four-recheck-20261101` 剩余四项复验中，会话进度（显式隔离非自动匹配偏好）、全屏切换后翻页（使用既有 3s 像素等待，原八帧不足以等待真实动画）、同页 retry（真实 HTTP/source 夹具替代未配置的 relaxed 来源）均通过；保留完整 oracle、实际像素和进度断言，无 production 修改。跨模型恢复仍有一项夹具并发任务计数失败，未关闭。`cp1-c1-native-canonical-20261101` 真实窗口 2 项通过、0 跳过/失败/错误，覆盖 T170 首次打开及重开、T30 重开和连续十单元；使用成功编译且源未变的 native 字节码，跳过仅与代理编辑并发的测试源码重新编译。原输入指纹/full oracle/实际 draw/退出及 uncaught 检查保留。这是正确性验证，日志中的超时回执不构成新性能采样比较。

stop/refill 修改后的契约追加了本轮重建负控，没有冒称读取历史 A03 原 patch。最初按瞬时 active-spec 条件的注入未触发，不能算证据；第二次仅停止单个 publisher，正常绑定恢复后仍完成认证，同样不是持续停机 RED。经独立审阅，最终临时故障按同一 owner 实例/逻辑 sequence 跨 publisher 锁存停止新增 pairing 准入，保留在途任务、completion 消费/释放、普通 materializer 及其他位置。`cp1-c1-refill-location-negative-test-20261101` 实际触发 target30/sequence1，已消费 speculative29；target30 当时在途，尚未准入的 32/33 为派生 optional 需求，不能改称当时已存在未准入 classified-real。持续阻断后真实 target30 Certified 断言失败（1 项/1 失败），支持“同位置消费后持续停止新增准入会阻断认证”的区分力，不证明所有瞬时延迟。精确 patch/XML 原件在 ignored `.gradle-coordinator/cp1-stop-refill-location-mutant-20261101.patch` 和 `cp1-c1-refill-location-negative-results-20261101/`；之后 ReaderScreenModel 原始 SHA256 `9bc42b15402a63f826c308096dfdea2a6fc5360d2c8dcdc67d82d6523e9a0991` 已逐字节恢复，最终 `cp1-c1-desktop-final-two-test-20261101` 同一 stop/refill 方法 GREEN；故障源码不纳入提交。

历史性能只支持原固定样本/环境的阶段收益裁定；本次 main 保留了后续源码和平台接线差异，当前构建不声称重新测得这些数字，也不把历史 16ms 样本护栏扩称所有设备/合法时序无退化。原始 CPU draw 回执不是 GPU 或显示器光学完成。

两个可选修复的既有筛选结果是本轮隔离依据，不证明各单项修复在所有环境必然导致退化，也不抹去它们已成立的局部正确性修复。未为重新采用追加性能实验。

必要的预览入口修复：`validate-windows-extension-runtime.ps1` 原 server/app 两处 Hidden 启动与现行 Windows 静默规则冲突，改为脚本内局部 ProcessStartInfo 启动（UseShellExecute=false/CreateNoWindow=true），保留 PS5/PS7 参数引用、日志异步复制、实际非零退出码与超时。保有准确进程句柄，某一进程或日志清理失败仍继续其余清理；原运行错误不被覆盖，成功但清理失败亦失败并保留诊断。不是新通用进程平台。

该交付链有真实安全 RED（在违规启动调用处拦截，未执行 Hidden），以及 cleanup 中断导致 server 幸存的真实 RED；修复后 `scripts/tests/windows-extension-runtime-process-test.py` 两项 focused / 14 个完整 validator 子例通过，两种 PowerShell 均覆盖成功、失败、退出码、超时、清理异常及共享已有控制台父拓扑。共享 helper 与 PowerShell wrapper 的 Console 相同且非零，生产 server/app 均为零；既有终端 913 次只读可见/最小化状态采样未变，所有自有进程退出。日志 `.gradle-coordinator/cp1-runtime-cleanup-shared-verified-20261101.log`；主控具体修复复审完成。

最终 C1 结论：`cp1-c1-desktop-final-two-test-20261101` 两方法全部通过，跨模型恢复与负控恢复 GREEN 闭环。跨模型夹具改用现有真实 `ReaderScheduledRequest.gatePoint` 边界只读捕获并 callOriginal，取消后保留请求记录，以 process counter 判断真正物理退出；移除错误的 logical activeWork 反射。旧夹具“阻塞 page2、又先等依赖它的 page4 画面”不是本用例必要前提：certifier 初始邻域需要 page2 的 DIMENSIONS，与是否启用自动边缘匹配不同。当前从构造启用匹配，直接证明真实 held page2 请求的正 permit/唯一旧 owner，保留普通计数 0、并发上界、旧工作存活、新模型禁止提前采样、放行后依赖完成与计数归零，原 3s/8s 期限不变。请求没有唯一 sessionId，因此“切换边界后捕获的 permit”不被夸称为对所有跨 runtime 极端时序的归属证明；本轮实际场景断言通过。

| 最终必要验证范围 | 结果与原件 |
|---|---|
| 共享 18 个 reader 契约类，166 个独立用例范围 | 首轮 165 通过 + 原失败及既有 manual 方法复验 2/2；不重复计数为 167 |
| Android 5 个目标类、15 个独立方法范围 | 原通过部分复用；Zoom 3 项无重试复验通过；Shared 原 6 项同进程组合 retries=0 全通过。近期普通单页真实像素进度及 HTTP 接线通过 |
| Desktop 32 selector、67 个独立用例范围 | 首轮 60 通过，七项失败依次按 3/3/1 闭环；最后两方法组合通过包含 stop/refill 恢复 GREEN。不是重新全模块通过 |
| Desktop 实际窗口 | 两项 full oracle/实际 draw/cleanup 回归通过，0 跳过；不作新性能判定 |
| 修改源格式 | domain34、Android5、Desktop21 的 scoped Spotless 通过；最新测试源码编译 12/19 源固定闭包通过，production 完整编译 |
| Windows 预览运行时启动链 | 两项 focused / 14 子例通过，PS5/PS7、退出码/超时/清理异常与共享控制台父拓扑；无 Hidden 启动 |

主控冻结前独审确认：26 个 Kotlin production 文件中 21 个与完整候选内容一致（只归一换行）；五个差异为保留 main 的图像内容提取、decode-key 工厂、resize 旧像素保留、certificationTargets 清理/全屏接口，以及 Android 同类型 import 格式修正。没有 AppVersion/依赖/Skiko 文件改动。临时故障恢复后的 Model SHA256 与原本完全一致。未因阶段结束运行完整模块/全仓、formal50、finalParityAudit 或正式发布矩阵。

## C2 本地 main 与 Windows 预览

已完成。功能提交和已验收包源码均为 `987389f88231236178ae824086b26bde37eb5dce`，版本 `0.11.24.6.987389f`。现行 main-preview 从独占 detached worktree 构建，[协调器状态](D:/Shell/Github/mihon/.gradle-coordinator/cp1-c2-main-preview-20261101.json)为 `PASSED`，[构建日志](D:/Shell/Github/mihon/.gradle-coordinator/cp1-c2-main-preview-20261101.log)记录实际交付路径。

- [Windows 预览 EXE](<D:/Shell/Github/mihon/app-desktop/artifacts/preview/windows/main-67eb9cff56a240a6ad86440e55ea20b6/Mihon-Desktop-0.11.24.6.987389f-unpacked/Mihon Desktop.exe>)
- [预览清单](D:/Shell/Github/mihon/app-desktop/artifacts/preview/windows/main-67eb9cff56a240a6ad86440e55ea20b6/preview-manifest.json)：源码 clean、sourceIntegrity/build/productionRuntime 均为 PASS；完整产物树 384 文件、272315860 bytes，SHA256 `24815ea79a02fbf709b8fe2600064fbe3d8ccbc4f02552440097dfc92a22826a`。

可选 CDS 启动缓存因 Windows 命令长度限制跳过，应用使用正常启动路径。清单中 nativeInteraction 的 NOT_RUN 是构建时状态，保持原样；后续用户体验验收在 C3 单独记录。临时构建工作树与自有构建进程已清理。后续纯验收文档不改变已验收包的源码身份，无需重建产品。

提交与快进后原 AppVersion 未提交差异的 SHA256 仍为接手值，暂存区为空；Android 单页进度修复及测试保持在主干历史中。本轮不分配版本、不推送、不发布、不安装用户应用。

## C3 用户体验

已通过。项目所有者于 2026-11-01 对 Windows 预览 `0.11.24.6.987389f` 的体验问题卡片明确回复“体验通过”，确认本轮用户体验验收。

问题卡片覆盖顺读、倒读、跳读、重开后的配对，以及单双页切换、缩放与进度。首次槽位、后台稳定、宽图与手动调整的相关 production 契约和定向证据保留在 C1；不把简短体验反馈扩写成逐步骤实测日志，不推及 Android 真机或 macOS。本轮没有正式 Android 构建、平板安装或用户应用操作。

验收后同步原位更新中英文当前功能与 changelog，范围限定 Windows 已验收阅读路径。未改变摘要或操作入口，因此 README 与开始使用页不变；公共文档仅提交本地，不部署网站。本次文档构建、四个生成目标页与站内链接核验、git diff --check 均通过。A 方案研究收口、工程整合交付及用户体验验收现均完成。
