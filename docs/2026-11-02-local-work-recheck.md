# 本地遗留工作逐项复核（第二轮）

日期：2026-11-02；核对基准 `main=ebf49a52319126ae51a4f5f6eb58ff1887656eb1`。旧清单取自 `e8beb212a0:docs/2026-11-01-local-work-retention-audit.md`；该旧报告后来被有意清理，本次不恢复旧报告，只保存增量结论。

后续用户已授权并完成本表中六处旧副本及剩余stash的归档清理，实际结果见[清理记录](2026-11-02-local-six-stash-cleanup.md)。下文表格保留核对时快照，不将其“仍在”状态误当当前状态。

## 1. 总体变化与判定

| 项目 | 上轮 | 本轮实际状态 |
|---|---:|---|
| Git 工作树登记 | 92 | 10；8个普通 Windows 入口可读，2个 WSL 指针入口失效，但其 Windows 物理目录和 Git 元数据均在，显式绑定后可只读核对 |
| 旧清单 dirty 工作树 | 34 | 6处仍在且 dirty，28处路径及登记已移除 |
| 全部现存工作树状态 | 34 dirty / 53 clean / 5未知 | 9 dirty / 1 clean；含旧清单外两处 nonblocking 树和已核清的 MiniMax 树 |
| 原29分支 | 29 | 15仍有本地 heads、14已删除 heads；其中13个删除的研究分支由 CP1 bundle 精确保留，另1个是旧 Android 版本分配，通过现存 stash 的祖先历史仍可达 |
| 原9处 detached 登记 | 9 | 均已移除；不能把“对象尚在”说成完整旧工作区已归档 |
| stash | 2 | 1；旧全屏混合 WIP 仍在，旧同步原型反向差异 stash 已移出列表 |
| 原5处不可访问登记 | 5 | 3处已移除；其余2处 WSL 登记已定位 Windows 实体并只读取得 status |

**本轮结论：主要功能和研究资料已经完成实质性处理，余项主要是仍保留的工作副本与在途任务。** [六项功能收口记录](2026-11-01-candidate-function-closeout-plan.md)的最终交付部分已覆盖 F01白缝、F02水波、F03分类截图反馈、F04设置布局、F09即时空帧、F11匹配开关验证，并记录用户验收。旧 [功能取舍文档](2026-11-01-local-candidate-function-decisions.md)较早章节中的“待办”须结合后来的最终交付读取，不能重新当作全部未完成。

这次只复核源码对应、Git状态和归档材料，没有重新运行产品测试、原生验收、性能测试或联网核对服务/远端。下文引用历史通过结果时属于证据读取，不是本轮重新通过。对大型旧WIP不作逐行等价保证。

## 2. 原29分支逐项复核

未带前缀的分支名均为`codex/`。

| 旧分支（固定HEAD） | 旧→今 main.. | 当前引用/归档 | 当前处置与相对旧建议的变化 |
|---|---:|---|---|
| cp1-s4-candidate-20261027 (`1e47b14ece`) | 254→254 | heads已删；B | 文：CP1产品闭包已收口，研究/证据归档；从“保留活跃历史入口”转为离线历史入口，不补合整支。R已保全选定源码/计量，但不宣称每个旧路径都纳入R。 |
| cp1-ledger-diagnostic-20261029 (`c11bccc380`) | 191→191 | heads已删；B | 文：隔离诊断研究已归档，880037a6/370f467及未采用设施不恢复；旧“可选修复另案”不能当现在待合任务。 |
| reader-pairing-r10-engineering-20261028 (`24cd1d11a6`) | 191→191 | heads仍在；R筛选源 | 文：R10逐项处置完成，仍暂缓；11/02退役记录称工作树已撤销、必要私有资料Git外保全。保留历史不代表100ms及缓存上界问题已解决。 |
| cp1-checkpoint-consumer-20261029 (`88992ac9dc`) | 164→164 | heads已删；B | 文：未采用checkpoint实验已离线归档；不构成CP1漏合。 |
| cp1-checkpoint-control-20261029 (`05e769f6a0`) | 163→163 | heads已删；B | 文：对照/优化分支已归档；基础闭包已由后继收口，旧优化不自动回迁。 |
| cp1-handoff-baseline-20261029 (`c684eaa872`) | 162→162 | heads已删；B | 文：旧基线已归档，非功能缺口。 |
| ordered-latency-r10-20261027 (`d198a5f917`) | 160→160 | heads已删；B；R原包 | 文：独立研究包已有R版本/来源索引，继续归档，不进入活跃产品队列。 |
| cp1-wider-window-evaluated-20261027 (`056c481556`) | 158→158 | heads已删；B | 文：已评估未采用的扩窗候选归档；R保留逐机制暂缓和反例，不能声称全部实现已入main。 |
| reader-pairing-research-next-20261014 (`68a5575932`) | 156→156 | heads仍在；R关键源/证据 | 文：原“不得因归档删除原始证据树”已被后续保全及退役替代：必要资料去重可恢复、树已退役；不是研究被永久否定。 |
| cp1-local01-native-20261027 (`3a5af9f9c4`) | 97→97 | heads已删；B | 文：阶段快照已归档；R说明后来LOCAL01目录副本与原报告不能无据视为完全同一，未新增产品待办。 |
| reader-pairing-r6-csl-d3 (`bb78b48237`) | 96→96 | heads已删；B+R6+归档ref | 文：早期候选/工程材料已有可导入历史bundle及方向级处置；不回退式补合。 |
| reader-pairing-r5-confluence (`dad72cd250`) | 60→60 | heads已删；B | 文：阶段研究归档；R对五态树、独立扩窗等保持明确暂缓边界，不把一般扫描收敛误称专用优化已采用。 |
| reader-pairing-r4-entry-prune (`7214f9826f`) | 55→55 | heads已删；B | 文：阶段归档；R区分D等价与C不可原样采用，不按旧hash缺席补合。 |
| reader-cp1-bounded-followup-20261015 (`55d91c1011`) | 12→12 | heads已删；B | 文：旧执行阶段及计时设施归档，产品闭包已有后继；不新增计时设施交付。 |
| reader-local-restoration-prototype (`02fbb1164a`) | 9→9 | heads仍在 | 文：从笼统“独立原型”细化为模型管线暂停接入默认阅读器、暗底算法保留素材、02fbb一次性无模型离线去偏色审阅工具保留；不是必须接入main的缺口。 |
| cp1-s4-l1-20261027 (`db7b0e24bc`) | 5→5 | heads已删；B；仍为R10/research-next祖先 | 文：正式比较基线归档；R保存相关formal50无效仪器裁定与后续独立数据，不把XML PASS当收益证据。 |
| reader-pairing-main-20261013 (`f38e0f9f98`) | 4→4 | heads仍在 | 源+文：旧“另案复核匹配连续性”已完成F11真实双向独色像素/位置/进度验证，后续收口记录通过。当前仍走changePresentation，不需要恢复f38绕开入口的旧补丁；旧候选退出活跃队列。 |
| sync-least-privilege-onboarding (`0f29ace4ad`) | 4→4 | heads仍在；completed-worktrees包有精确入口 | 旧：原四笔patch等价结论未发现后续反证，保持已有main对应/可归档；本轮未重跑协议或重做语义审计。 |
| feedback-form-screenshot-spec-20261017 (`d250cebdf5`) | 3→3 | heads仍在 | 源+文：旧“需单独整合”已由F03完成。29ffe87e8c在main祖先链，当前共享分类/截图模型、双端处理器/诊断DI、Worker截图协议及0004迁移均在。服务更新和用户验收仅援引closeout文档，本轮未联网核服务。 |
| title-credits-main-daily-20261012 (`f598adc928`) | 3→3 | heads仍在；completed-worktrees包有精确入口 | 旧：原patch等价及后继解析器改进结论保持，归档旧实现；本轮未重新审整个标题解析语义。 |
| history-reader-ci-20261014 (`977e06c588`) | 2→2 | heads仍在 | 旧：原两笔局部修复已有main对应，保持归档建议；本轮未重读测试mock/retainAll细节。 |
| android-dual-page-tile-seam (`3ed1cf3c49`) | 1→1 | heads仍在；completed-worktrees包有精确入口 | 源+文：旧“确未入、需整合”已过时；后继3407b98f49为main祖先，当前原图fitScale、按共享书脊求中心、整数半视口/ULP对齐与resize姿态处理已在。133包PixelCopy验证及用户验收援引收口记录，不冒充本轮重测。 |
| android-ripple-wip-20261017 (`388abad41f`) | 1→1 | heads仍在 | 源+文：旧WIP/孤立测试待决定已由F02收口。68e5e22107在main祖先链，当前默认SHADOW_LINE、SMOOTH选项、偏好/ViewerConfig/设置/Pager实际实现都在；非“只有测试”。 |
| cp03-final-20260927 (`5840cc6bc1`) | 1→1 | heads仍在；completed-worktrees包有精确入口 | 旧：原patch等价且后继CP1覆盖，保持归档，不重复整合。 |
| desktop-fullscreen-reader-20261017 (`fcf0f04fd3`) | 1→1 | heads已删；当前refs/stash为包含引用 | 源：唯一提交只有Android vc124→125、aex.106→.107；分支删除不丢独立全屏产品实现。未核实独立长期包，不把stash可达对象当完备归档。 |
| luna-v12-reader-test-memory (`0c42b4b2dd`) | 1→1 | heads仍在 | 旧：原有界render/偏好隔离/清理已由现行后继测试替代的判断保持；本轮未重跑该测试。 |
| pi-aa203 (`5ed4374467`) | 1→1 | heads仍在 | 旧：正式作者发现scheduler/runtime/DI已有后继，评测草案归档；未逐项重做草案语义等价证明。 |
| pi-eval-deepseek (`b5f4f8c6ad`) | 1→1 | heads仍在 | 旧：正式executor/共享服务/事务改进已有后继，评测草案归档；不回贴旧模型。 |
| spike/reader-local-image-restoration (`aa2d6a7dad`) | 5→5 | heads仍在；为prototype祖先 | 源+文：已核5笔包含stage-A三笔及两项夹带残差。5758e44bd2的CHIPS partitionKey fallback当前main确实缺少（主干仅一次cookies.get，测试无CHIPS用例），应保留为独立候选；未运行浏览器复现、未确认旧补丁适合当前API。cc262f62f8仅将app-lock测试executor.use改try/finally shutdown；main仍.use，属测试兼容候选。不得整支合入。 |

## 3. 原34处未提交工作树逐项复核

| # | 原登记路径 | 当前状态 | 归档证据 / 保留建议 |
|---|---|---|---|
| 1 | `D:/Shell/Github/mihon` | **仍登记、可访问、dirty 2项**（AppVersion、`.codex-remote-attachments/`） | 旧审计指出版本改动与ZIP附件均须保护。当前仍保留；不提交/清理。 |
| 2 | `D:/Codex/worktrees/85be/mihon-sync-old-96db` | **已移除/路径不存在** | 原2份同步安全/压力测试保存在 detached归档 retained-tests/sync-scale。本轮9份保留测试统一SHA验证全部匹配。已完成筛选保全，无需恢复整个旧仓库。 |
| 3 | `D:/Codex/worktrees/catalog-pack-20261012` | **已移除/路径不存在** | 仅旧Android版号79→80；按旧发布元数据淘汰，不列功能待办。完整旧工作区不再承诺保留。 |
| 4 | `D:/Codex/worktrees/cp1-ledger-diagnostic-20261029` | **已移除/路径不存在；dirty材料已归档** | `cp1-cleanup-20261101-063834` manifest目标verified（17,643文件，payload-v2，含崩溃日志status），cleanup-results为REMOVED；有完整dirty文件归档记录，未复验载荷哈希。 |
| 5 | `D:/Codex/worktrees/cp1-s4-candidate-20261027` | **已移除/路径不存在** | `cp1-cleanup-20261101-063834` 的manifest将此树列为verified，291个本地文件、payload-v2，cleanup-results列为REMOVED，README说明bundle/dirty payload恢复流程。可恢复性有直接归档证据；未执行逐字节复验。 |
| 6 | `D:/Codex/worktrees/cp1-s4-l1-20261027` | **已移除/路径不存在** | `cp1-cleanup-20261101-063834` manifest对此树verified，28个本地文件、payload-v2，status明确包含4个untracked测试及脚本目录，cleanup-results为REMOVED；本地dirty文件有档案证据，未逐字节复验。 |
| 7 | `D:/Codex/worktrees/f235/hp02-history-candidate` | **已移除/路径不存在** | 独有history formal夹具、脚本和proguard增量在 retained-tests/history-formal，本轮SHA核验通过；旧版号淘汰。已筛选保全，非当前Android功能待办。 |
| 8 | `D:/Codex/worktrees/luna-v10-formal` | **已移除/路径不存在** | 旧动画/呈现实现已由后继替代；取舍F09/F13及新T1–T9/50参数验证记录承接。按筛选淘汰旧副本，未保存完整工作区，不应重新列为必须恢复的WIP。 |
| 9 | `D:/Codex/worktrees/luna-v10-oracle` | **已移除/路径不存在** | 同v10正式候选：旧oracle/夹具按后继覆盖筛选淘汰，所需独立刺激已提取；没有完整副本恢复承诺。 |
| 10 | `D:/Codex/worktrees/luna-v11-candidate` | **已移除/路径不存在** | 旧pager/owner实现不重新采用；已筛选刺激及后继正式动画/像素测试承接，旧完整副本已淘汰。 |
| 11 | `D:/Codex/worktrees/luna-v12-candidate` | **已移除/路径不存在** | HEAD仍由测试内存分支保留；工作区后继页翻/偏好/IO测试已有，选择性刺激已提取。旧dirty全量副本不保留，不能把HEAD保留冒称全部dirty可恢复。 |
| 12 | `D:/Codex/worktrees/luna-v13-candidate` | **已移除/路径不存在** | 旧呈现候选按F09/F13处置，后继测试/像素门禁承接，不再要求并排维护旧实现；无完整副本恢复保证。 |
| 13 | `D:/Codex/worktrees/luna-v15-candidate` | **已移除/路径不存在** | 原82文件跨层方案已被逐场景筛选，正式主干后继与T1–T9承接必要刺激；不是仍待整合82文件。完整旧源码副本已淘汰，不声称逐行全部等价。 |
| 14 | `D:/Codex/worktrees/luna-v20-main-control` | **已移除/路径不存在** | 旧控制候选与核心文档按后继架构和场景筛选处理；不恢复旧owner/scheduler结构，原完整副本不再承诺保存。 |
| 15 | `D:/Codex/worktrees/luna-v7-historical` | **已移除/路径不存在** | 历史遗漏测试不形成用户能力；随旧重复夹具筛选淘汰，不另开产品开发。 |
| 16 | `D:/Codex/worktrees/luna-v9-reader-repro` | **已移除/路径不存在** | 原2行复现测试属于后继页翻/关键路径覆盖的历史素材；已退出活跃队列，不需恢复整棵树。 |
| 17 | `D:/Codex/worktrees/mihon-fork-reader-perf-parallel-20261020/mihon` | **已移除/路径不存在** | 早期CP1跨端WIP已有后继正式闭包；上轮已确认其中8文件与main一致。本轮按后继及研究去留处理；完整旧dirty副本不再承诺恢复，不能把24/2当未做功能数。 |
| 18 | `D:/Codex/worktrees/mihon-m2-baseline-63236da` | **已移除/路径不存在** | 原同步权限unknown兜底已被main专门错误提示取代；HEAD由最小权限分支保留。淘汰旧单文件修改，无需恢复。 |
| 19 | `D:/Codex/worktrees/mihon-main-wip-20261011` | **仍登记、可访问、dirty 13项** | 12/1仍在，主要同步原型/状态文档含反向修改。建议按现行同步交付筛选；若无独有素材需保存，可在单独清理中归档后移除，禁止整包apply。 |
| 20 | `D:/Codex/worktrees/mihon-update-feedback-20261013` | **仍登记、可访问、dirty 2项**（`.wrangler/`、feedback原型） | 0/2仍在：反馈HTML/浏览器测试及.wrangler运行状态。F03产品和用户验收已完成，旧原型不再是功能待办；按需保留素材、另清工具状态。 |
| 21 | `D:/Codex/worktrees/mihon-upstream-reader-perf-20261020/mihon` | **已移除/路径不存在** | 原版Android对照用benchmark/build接线，性能对照当时未完成并已有历史说明；这不是目前CP1交付门槛。旧副本按测量素材筛选退出，不宣称完整恢复。 |
| 22 | `D:/Codex/worktrees/reader-ci-history-20261014` | **仍登记、可访问、dirty 1项** | 1/0测试残差仍在；F09正式50项已通过，相关stable四项基线失败仍由收口记录保留。不能仅凭旧测试文件未提交重开配对研究，先查对应契约是否已被后继覆盖，再按需归档。 |
| 23 | `D:/Codex/worktrees/reader-pairing-main-20261013` | **已移除/路径不存在** | RETIREMENT明确退役；原5份测试/报告变化由legacy-code/desktop-maintenance的working/base/patch逐项保全，17项清理表提供原SHA。matching验证已完成，不恢复f38旧实现。 |
| 24 | `D:/Codex/worktrees/reader-pairing-r3-baseline-20261012` | **已移除/路径不存在** | 65KB Android下载/配对探针是旧性能测量素材，不是新增产品能力。没有本轮精确成员级保全证明，不承诺完整恢复；若未来需复现实验再查来源，不作为当前功能未完成。 |
| 25 | `D:/Codex/worktrees/reader-pairing-research-next-20261014` | **已移除/路径不存在；部分材料保全** | 原7项算法/测试/实验资料由legacy-code/research-next逐文件working/base/patch保全；剩余旧版本差异在retirement私有包单独保存，本轮14文件封存SHA全部匹配。已退役归档，不是只有部分脏内容未知。 |
| 26 | `D:/Codex/worktrees/sync-release-20260919` | **已移除/路径不存在** | 仅旧Desktop BUILD50→51，按过期版本分配淘汰，不列功能待办；无完整旧副本承诺。 |
| 27 | `D:/Shell/Github/mihon/.worktrees/feat-auto-20261023-5c28ea99/.candidate/reader-pairing-r6-baseline` | **已移除/路径不存在；完整副本未保全** | `detached-cleanup`曾列对应snapshot，但后续README/cleanup-result记载bundle已删，snapshots主体已清理；仅留9个特定同步/history测试文件与validation，不含证据证明R6 candidate夹具/轨迹dirty副本在保留集合内。 |
| 28 | `D:/Shell/Github/mihon/.worktrees/fork-02a609497a43-8dd57a8153c14128a96255666627d8c6` | **仍登记、可访问、dirty 46项** | 22/24仍在，main已包含04be后继启动主体。功能取舍F07/F08已明确多数后像相同，残差缺后继修复不回迁；保留有用测试刺激后可单独清理，无须恢复旧启动架构。 |
| 29 | `D:/Shell/Github/mihon-aex05-old` | **已移除/路径不存在** | 旧Android aboutLibraries/build迁移，仅构建历史；已退出活跃队列，不由该旧文件存在推断当前构建缺陷。无完整旧副本恢复承诺。 |
| 30 | `D:/Shell/Github/mihon-fullscreen-white-edge-a68d287856` | **已移除/路径不存在** | 只有BUILD6→9，没有独有全屏修复；旧副本已淘汰，main仍有受保护相同版本差异，无需重建。 |
| 31 | `D:/Shell/Github/mihon-pi-deepseek-aa203` | **仍登记、可访问、dirty 2项**（两空日志） | 0/2仍在，本轮两个日志大小均为0。可作为明确空日志清理对象；不涉及作者发现产品实现。 |
| 32 | `D:/Shell/Github/mihon-reader-auto-release` | **已移除/路径不存在** | AUTO=7/DEFAULT继承、迁移和双端布局已由main后继承接，F10已逐语义处置，旧未跟踪同名flags不是漏文件。旧完整dirty副本已筛选淘汰，不重新整体采用。 |
| 33 | `D:/Shell/Github/mihon-reader-error-exit-build` | **已移除/路径不存在** | 上轮已确认关闭/重试production片段与两份测试均已入main；旧副本移除合理，非待修bug；不承诺所有旧文件完整恢复。 |
| 34 | `D:/Shell/Github/mihon-task151-windows` | **已移除/路径不存在** | 原tracking/settings修复patch等价已入main；platform acceptance夹具为历史验证资产，当前不形成新用户需求。后续若明确需要其中独立场景再从可得来源恢复，不把未找到完整副本说成新产品阻塞。 |

另有旧清单外三处dirty：nonblocking 25/0、nonblocking-integration 13/0、WSL MiniMax实体1/2。两处nonblocking包含产品/测试/roadmap的在途差异，禁止当旧实验清理。DeepSeek实体clean。

## 4. 原9处 detached HEAD

9个旧HEAD的对象本轮都仍可读取；已移除工作树登记。部分旧完整快照和bundle随后按筛选结果被删除，以下不承诺还原所有旧dirty/ignored内容。

| 原工作树 | HEAD | 当前保留关系与建议 |
|---|---|---|
| `0877/mihon` | `9c7cbf5154` | 仅旧 BUILD70→72；仍有 `refs/codex/snapshots/9ab3d357e6f48b83ccb0ae0032fac65bec387ed7` 包含。已退出活跃队列，不恢复版本号。 |
| `l1-r3-baseline` | `db7b0e24bc` | CP1 bundle 精确含该HEAD，R10/research-next及研究refs仍包含。基线归档完成。 |
| `luna-v12-candidate` | `0c42b4b2dd` | `codex/luna-v12-reader-test-memory`仍保留；旧WIP后继测试已筛选，原完整dirty副本不再承诺保留。 |
| `luna-v7-mihon` | `14e4dcdf90` | 对象仍在，但没有现存ref包含；原完整detached bundle已删除。后继边界/动画测试与选取场景已入主干，不重新采用旧实现；若要长期保存精确旧commit须另固定引用，不能依赖对象缓存。 |
| `mihon-fork-reader-perf-parallel-20261020/mihon` | `faf002165b` | 由R10/research-next和研究refs祖先包含；旧跨端产品后继已整合。无需恢复早期全部工作区。 |
| `mihon-m2-baseline-63236da` | `63236da236` | 同步最小权限分支保留；旧unknown兜底已被专门提示替代。归档完成，不回迁旧错误映射。 |
| `.candidate/reader-pairing-r6-baseline` | `03a7fbd7f1` | `refs/codex/reader-history-archive-20261101/*`包含，R6–R9恢复包和来源索引已保留。研究归档完成。 |
| `mihon-rv01-evidence` | `6080f7926f` | 对象在、无ref包含；两个实质修复上轮已证 patch 等价进入main，余为旧版号。按已筛选淘汰处理，不列开发待办；不承诺完整旧commit长期保存。 |
| `mihon-task151-windows` | `8078bee94e` | 对象在、无ref包含；原修复与main `fcbb351ba9` patch等价，当前后继测试接线已筛选。无需重复整合；不承诺旧dirty副本可完整恢复。 |

## 5. 两份 stash 的变化

| 原条目 | 当前状态 | 本轮判断与建议 |
|---|---|---|
| `54cfc0355593383321e1ed306d78ea2c685a695d`（原stash0） | 仍是唯一stash；29 tracked＋第三父树21 untracked，数量不变 | 设置布局F04已交付；下载与全屏采用后继已验收实现；启动已有主干后继。剩下旧渲染缓存、原型和测试刺激属于研究/素材，不能整包apply。可以保留为一个历史容器，后续若清掉它先决定是否还要保存这些素材。它的存在不表示还有29项功能未做。 |
| `36d27d52f30c663d78d5146d116a5b0bf3ecdc97`（原stash1） | 已不在stash列表；对象仍能读取，但无现存ref包含 | 原9 tracked＋1 untracked的同步原型/状态反向修改不回迁。相应main-wip工作树仍在，不能断言两者逐字相同；本轮确认“已移出列表”，不虚构该stash已被独立长期归档。 |

## 6. 原5处不可访问登记重新核清

| 原登记 | 当前实际情况 | 建议 |
|---|---|---|
| `/mnt/d/Shell/Github/mihon-pi-deepseek` | 登记仍在；Windows实体 `D:/Shell/Github/mihon-pi-deepseek`存在。`.git`写WSL绝对路径，普通Windows git入口失败；使用现存 `.git/worktrees/mihon-pi-deepseek`＋实体work-tree显式只读核对为clean | 已不是内容未知；后续由原环境清理或修复登记，不能自动prune为不存在目录。 |
| `/mnt/d/Shell/Github/mihon-pi-minimax` | 同类路径问题；实体可读，显式status为1修改/2新增：CreatorDiscoveryService、CreatorDiscoveryExecutor及RepositoryTest | 是旧作者发现执行器候选，先保留并与现行主干对齐，不能当空目录；本次未修指针或采用代码。 |
| `/tmp/aa203-clean` | 登记已移除，原HEAD `c9c1605652`为正式作者发现实现并已入main | 不再是清理待办；未证明当时临时目录曾有额外未提交内容。 |
| `C:/Users/feeli/.codex/worktrees/f8e8/mihon` | 登记/路径已移除 | 旧完整快照不再承诺恢复；按现存后继交付和筛选材料处理，不重建空工作树。 |
| `D:/Codex/worktrees/9117/mihon` | 原locked initializing登记已移除，原main历史HEAD保留 | 无需再解锁；不据此声称曾存在的未知未提交内容已被恢复。 |

## 7. 本轮归档核验与限制

- `D:/Codex/archives/cp1-cleanup-20261101-063834/history.bundle`、`D:/Codex/archives/completed-worktrees-cleanup-20261101/history.bundle` 本轮 `git bundle verify` 均返回0；核了前者对13个已删研究分支的精确heads。没有为本次复核重新克隆整个归档或重放所有工作区。
- [研究退役记录](research/reader-pairing/history/RETIREMENT-2026-11-02.md)列出的私有目录 `D:/Codex/research-archives/reader-pairing/retirement-20261102/`仍在。本轮流式重算封存清单14个文件的SHA256与大小，全部匹配，包括134,127,143字节研究ZIP及manifest。本轮另外核验detached归档9份保留测试的SHA与大小全部匹配。9,251条恢复定位、深层ZIP/CBZ重建结果属于原交付记录，本轮没有重新展开全部重验。
- `D:/Codex/archives/detached-cleanup-20261101/README.md`已明确撤销完整旧仓库恢复承诺，仅保留9个同步/history独有测试文件和validation。不能再引用更早“36棵完整快照均在”的过程记录证明当前备份。新一轮已选测试/修复与现有主干是保留的成果，旧Luna实现和重复夹具已被明确淘汰。
- 旧审计文件删除本身有 `cleanup-result.json`记录，不是本轮读错分支或报告突然丢失。原报告仍可从固定Git提交取回；本轮不恢复它覆盖新的结论。
- 主工作树 BUILD6→9的SHA256仍是 `555d42abf87335aa333182df5118c1ad9cdb43ebb93da8b5eed2e182055e6ee5`，远端附件ZIP仍在。均属用户保留内容。
- 本轮读取时缓存 `origin/main=1e8596dd5c5c056a1fc922b5aa24e3b0a662c01e`，本地main对缓存为88 ahead/0 behind。身份差异不等于88项未交付，不擅自推送这些历史。

## 8. 剩余处理建议

1. **继续保护在途工作**：两处 reader-nonblocking-pairing工作树（分别25/0、13/0）已有真实产品/测试/roadmap差异。这是新任务，未做本轮行为验收，不能随旧CP1清理，也不能由“原目标已完成”推断可以合入。
2. **可独立整理六处旧dirty**：main版本号/附件保持用户意图；main-wip同步原型、feedback原型、reader-ci测试、fork启动残差、pi-aa203空日志分别处理。启动主体/反馈功能已入main，旧副本不是新增功能待办；对残留测试或原型先按需保存，再清理。
3. **核清MiniMax实体候选**：作者发现执行器1/2未提交内容单独对齐主干；修复WSL/Windows元数据入口是环境维护，不要顺便采用旧实现。
4. **研究不重开**：R1–R10方向已有逐项处置，已采用/暂缓/未知边界见 [研究总索引](research/reader-pairing/HISTORY_INDEX.md)。未知性能问题与暂缓方案保留研究价值，不作为这次结束工作的门槛。
5. **保留真正独立残差**：spike中的 `5758e44bd2` CHIPS分区Cookie回退在main缺失，本轮主审也确认当前helper只有一次cookies.get、无partitionKey。这是可另案评估的候选，未复现当前故障、未核当前浏览器API适用性；app-lock测试的ExecutorService.use与try/finally差异仅是测试兼容候选，不能据旧提交说明宣布当前编译失败。
6. **只处理明确残余**：研究忽略目录及detached剩余受保护文件的删除曾遭自动审批拒绝，记录仍在。它们是清理残余，不是算法/功能阻塞；本次没有重试删除或绕过限制。

本次不新增产品开发、不删除工作树/分支/stash、不修Git元数据、不联系其他会话、不推送。静态资料复核不等于对现行所有功能作全面验收。
