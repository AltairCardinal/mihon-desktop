# R6–R9 工程方向与当前主干关系

核对主干：`6b5831c64dd9f09a7d79c93fac9a75a548c740fa`。这是只读语义盘点与归档裁定；没有重跑历史测试、基准或包内命令。完整原记录、历史判定、证据路径与逐题分类见 [engineering-decisions.json](engineering-decisions.json)。

## 出处与覆盖

原文和ledger在 [保真材料ZIP](sources/reader-r6-r9-engineering-materials.zip)，逐件提交、Git blob、字节数、SHA及ZIP成员路径见 [来源清单](sources/reader-r6-r9-engineering-materials.json)。历史由 [Git bundle](sources/reader-r6-r9-history-e323-to-bb78.bundle)保存，不假定裸SHA远端可达。原版/终版题目修订关系见 [包清单](../package-inventory.json)。

覆盖R6初始14项、R7的12个具名工程方向、R8完整67条工程ledger、R9完整49条工程ledger（含协议/变体/LOCAL01）、D3_INDEX 45机制及最终180题。R8逐ID连接67题coverage。R9原D3_INDEX.questions只显式连接55/180题；没有以相似关键词强配剩余题目。

## 跨版本结论

- R6 C-SL和组合未获可靠整链收益；D3 repaired实验仅条件性有利且默认关闭。α/β header权威性不能靠“采用后变化再失效”补证明，222状态与n512旧结果不能外推今日生产。
- R6优化版撤回同值写生成器、unrelated误分类、循环覆盖、行数及缺失scale等旧结果，七项纠正见JSON；这不等于未来所有负载都无收益。
- R7历史最终保留index/compact/stamp；现在由11月1日 [A2](../../2026-11-01-main-reevaluation/A2.md)、[A3](../../2026-11-01-main-reevaluation/A3.md)、[A4](../../2026-11-01-main-reevaluation/A4.md)独立复评覆盖，三项暂缓。历史54 domain+5 Desktop+3 Android通过仅为当时记录。BX/精化容忍整链收益不稳，D4/D5未形成产品采用结论。
- R8 C5正确性：当前Session在一次certifyCapturing前建reads，入口/左扩全部尝试沿用同一collector，返回后snapshot，无手动attempt回滚，已有完整尝试支撑等价语义；不要求旧union类保留。
- R8 primitive评分与当前A1同向；R9 OPAQUE/LUT/PREFIX缓存是另一次试验，不能因A1存在就宣称这些缓存也已采用。
- R9 LOCAL01限Strong全原图Computed、128页/目标64/work20000及合法AUTO成本hint，不同于不安全的一般later-null。当前没有Strong consumer，保留研究而非认定漏合。目标11 cold45→43、目标9 cold43→43、warm40→40；wall跨进程不稳且warm回退。
- 多目标/输出共享、derive-before-evict、绑定确认、物理退出已有wiring。Strong专属预算不等于普通请求预算；旧evict-first、无绑定confirm、全局已发布表等前提不能直接投射当前主干。
- R8四个历史平台失败当时也在其基线出现；R9模拟计数及711行历史计量不能冒充当前失败或当前测试通过。

## 机制分组

| 机制 | 当前处置 | 边界与原因 |
|---|---|---|
| 完整尝试读支撑 | main已有等价 | 一次certifyCapturing前建reads，全部入口/左扩沿用该collector，返回后snapshot；无attempt回滚。R8 C5修复语义已有，不要求旧union类。 |
| primitive评分与部分和拒绝 | main已有等价 | A1使用IntArray与非负部分和早拒绝，R8 E5/X9同向；不等于R9评分缓存。 |
| 复用owner索引 | 暂缓有价值保留 | R7旧保留由11月1日A2独立复评覆盖：大章改善、小章/负控漂移，净收益不确定。 |
| 紧凑精确读值 | 暂缓有价值保留 | R7旧保留由11月1日A3独立复评覆盖：分配减少，默认空集合服务成本及跨进程结果不稳，净收益不确定。 |
| 保守变化戳 | 暂缓有价值保留 | R7旧保留由11月1日A4独立复评覆盖：稳定场景快，合法header频繁写回退，真实占比未知，净收益不确定。 |
| 全信息DP/关系树/摘要/按需结构 | 前提不适用/研究模型 | 模型结果不意味着当前部分信息逐目标consumer满足全信息增量树前提；当前无Strong系统。 |
| C-SL/BX分支证明 | 暂缓有价值保留 | R6 C-SL、R7 BX完整服务成本无稳定净收益；保留证明/反例/条件，不能只报内核。 |
| 作用域/投影/重签证据 | 暂缓有价值保留 | 精确memo已有，R6 scoped与R9投影/重签不等于当前实现；须计维护、发布、生命周期。 |
| 绑定发布与迟到确认 | main已有等价 | ticket绑定context、sequence、readingEligible和drawn，rebind释放旧授权，不迁移旧确认。 |
| 取消与物理退出 | main已有等价 | runner计实际物理执行至completion；同绑定普通提升保留jobKey，逻辑取消不等于物理退出。 |
| 多目标及输出共享 | main已有等价 | 当前聚合多目标并复用事实/输出，不再造共享scheduler。 |
| 先派生再驱逐 | main已有等价 | owner在raw LRU驱逐前派生邻接分数，旧evict-first模型活锁前提不成立。 |
| 身份与同内容复用边界 | main已有等价 | 严格identity/方向/recipe与incarnation授权；普通提升不含force/retry/换身份。 |
| 容量/GDS/分区留存 | 暂缓有价值保留 | 偏斜收益和最坏回退并存，估算条目/字节非JVM驻留；不扩当前默认容量。 |
| 阶段取证/渐进见证/融合归一化 | 暂缓有价值保留 | 合成计数不等于真实joint decode整链收益；重开/解码/队列及consumer证据不足。 |
| 预测/防抖/aging/闲时与动态策略 | 暂缓有价值保留 | 模型仅条件性成立或最坏回退；现有串行共享取消不是这些新策略已采用的证明。 |
| 并发/预算/协议模型 | 前提不适用/研究模型 | 有界协议知识不推广为原生活性/墙钟；Strong BC-2当前没有consumer，普通预算另有wiring。 |
| 理论界/数值域/独立oracle | 前提不适用/研究模型 | 保留定义、证明、反例与适用域，不是独立待合入产品能力。 |
| 因果配对/删失/统计/观测 | 前提不适用/研究模型 | 保留验证方法，旧模拟值、计数与历史失败不作当前性能/失败结论。 |
| L2/重启/强制切割规则 | 暂缓有价值保留 | 改变语义且缺质量裁决，局部性/性能不能替代输出质量。 |
| R9 OPAQUE/LUT/PREFIX缓存 | 暂缓有价值保留 | 不同于A1 primitive；LUT约256KiB/owner、PREFIX至少约6.8KiB，整链成本不稳定，历史试验撤回。 |
| 有界later-null局部强证明 | 暂缓有价值保留 | 限Strong全原图Computed、128页/目标64/work20000和合法AUTO成本hint；当前无Strong，不是漏合。cold45→43而wall不稳、warm回退；不同于一般later-null。 |
| 旧控制/无消费表/已替代 | 重复/被后续取代 | 旧比较控制或不存在的全局发布表维护，不构成当前缺口，保留撤回原因。 |

## 完整工程ledger定位

每个原ID的完整历史判定、实现、测试和计量出处均在JSON保留；下表机制键引用JSON机制分组，不以摘要替代原证据。

### R8 67条

| 原ID | 原local_decision | 当前机制键 |
|---|---|---|
| A1 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |
| A2 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |
| A3 | retain_safe_support_not_greedy_minimizer | exact-support |
| A4 | preserve_unbounded_dependency | theory |
| A5 | research_only | full-info |
| A6 | existing_domain_verified_platform_focused_partial_baseline_failures | bound |
| B1 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |
| B2 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |
| B3 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |
| B4 | retain_union_correctness_fix | exact-support |
| B5 | no_new_product_branch_required; measured_research_not_default | sharing |
| B6 | reject_default_idle_preparation | schedule |
| C1 | keep_global_default | stamp |
| C2 | keep_global_default | stamp |
| C3 | existing_index_and_owner_guard_kept_no_new_interner | index |
| C4 | reject_new_interning_keep_exact_compact | compact |
| C5 | retain_union_correctness_fix | exact-support |
| C6 | conditional_skew_benefit_research_only | retention |
| D1 | three_jvm_sample_work_verified_native_decoder_cost_not_measured; three_jvm_verified_keep_production | stage |
| D2 | existing_domain_verified_platform_focused_partial_baseline_failures | sharing |
| D3 | existing_domain_verified_platform_focused_partial_baseline_failures; three_jvm_warm_verified_conditional_not_default | schedule |
| D4 | existing_domain_verified_platform_focused_partial_baseline_failures | physical |
| D5 | existing_domain_verified_platform_focused_partial_baseline_failures | binding |
| D6 | keep_L0_default | full-info |
| E1 | research_only_safe_original_support | stage |
| E2 | no_unreachable_extreme_product_bug_claim | theory |
| E3 | conditional_complete_plan_only_research | theory |
| E4 | no_unreachable_extreme_product_bug_claim | theory |
| E5 | three_jvm_verified_keep_production | primitive-score |
| E6 | research_only_safe_original_support; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info, stage |
| F1 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |
| F2 | research_only_safe_original_support | stage |
| F3 | keep_L0_default | full-info |
| F4 | do_not_compile_target_specific_acquisition_dag | full-info |
| F5 | conditional_frontier_reuse_only | full-info |
| F6 | keep_L0_default | full-info |
| G1 | existing_domain_verified_platform_focused_partial_baseline_failures | bound |
| G2 | existing_domain_verified_platform_focused_partial_baseline_failures | physical |
| G3 | existing_domain_verified_platform_focused_partial_baseline_failures; three_jvm_warm_verified_conditional_not_default | retention |
| G4 | existing_domain_verified_platform_focused_partial_baseline_failures; three_jvm_warm_verified_conditional_not_default | schedule |
| G5 | existing_domain_verified_platform_focused_partial_baseline_failures | bound |
| G6 | conditional_skew_benefit_research_only; existing_domain_verified_platform_focused_partial_baseline_failures | retention |
| H1 | no_fake_deepcopy_or_diff_elimination_gain | measurement |
| H2 | separate_true_source_and_same_code_controls | measurement |
| H3 | no_unconditional_online_winner | measurement |
| H4 | never_compare_status_only | exact-support |
| H5 | three_jvm_migration_verified_not_default_no_quality_truth | rule |
| H6 | report_absolute_costs_not_python_upper_bound | measurement |
| X1 | keep_global_default | stamp |
| X2 | no_new_product_branch_required; real_owner_four_combination_work_verified_existing_mechanism_no_new_product_gain | sharing |
| X3 | existing_domain_verified_platform_focused_partial_baseline_failures; three_jvm_warm_verified_conditional_not_default | schedule |
| X4 | keep_L0_default | full-info |
| X5 | conditional_skew_benefit_research_only | retention |
| X6 | retain_safe_support_not_greedy_minimizer | exact-support |
| X7 | existing_domain_verified_platform_focused_partial_baseline_failures | bound |
| X8 | existing_domain_verified_platform_focused_partial_baseline_failures | bound |
| X9 | three_jvm_verified_keep_production | primitive-score |
| X10 | research_only_safe_original_support | stage |
| X11 | separate_true_source_and_same_code_controls | measurement |
| X12 | existing_domain_verified_platform_focused_partial_baseline_failures | physical |
| NH-1 | no_actual_producer_bug_proven | theory |
| NH-2 | no_fake_deepcopy_or_diff_elimination_gain | measurement |
| NH-3 | three_jvm_sample_work_verified_native_decoder_cost_not_measured | stage |
| NH-4 | retain_reset_as_cost_hint_not_certificate | protocol |
| NH-5 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |
| NH-6 | no_unreachable_extreme_product_bug_claim | theory |
| NH-7 | research_only; three_jvm_verified_research_only_no_profitable_full_info_consumer | full-info |

### R9 49条

| 原ID | 候选 | 原decision | 当前机制键 |
|---|---|---|
| I1 | 会话级 close（异步：请求 / 逻辑关闭 / 物理排空） | reuse_existing | physical |
| M32 | 句柄绑定确认 bound_confirm | reuse_existing | bound |
| I2 | 强证明预算合同 BC-2 | existing_real_budget_extended_not_reinvented | protocol |
| M31 | 增量撤销 inc_revoke（+ bound 确认） | inapplicable_to_current_native_shape | obsolete |
| M06 | D04 差值区间 memo（INTERVAL，替换 SIG） | rejected_for_current_production | scope |
| M01 | gated_shadow：修正版运行器（F1/F2）+ bound 确认 + 树路线 | not_native_shadow_adoption | branch |
| M02 | 持久关系树（PersistentStrongSession） | not_promoted_after_three_jvm_screening | full-info |
| M03 | 块摘要（BlockSummary 核心 + BlockSession 会话接线，同一机制两层证据） | no_additional_native_block_store | full-info |
| M05 | 分层/反向剪枝（seeded/goal） | rejected | branch |
| M07 | D04 比较签名 memo（SIG） | superseded_research_control | obsolete |
| M08 | 位集数值域（bitset） | rejected_for_current_domain | full-info |
| M09 | B01 规范 ZDD 摘要 | not_promoted | full-info |
| M10 | 按需结构 hybrid | not_promoted_to_partial_information_consumer | full-info |
| M11 | 按需结构 skikeep | not_promoted_to_partial_information_consumer | full-info |
| M12 | 按需结构 lazytree | not_promoted_to_partial_information_consumer | full-info |
| M13 | 按需结构 dirtylog | not_promoted_to_partial_information_consumer | full-info |
| M14 | 按需结构 sparsecp | not_promoted_to_partial_information_consumer | full-info |
| M15 | 按需结构 skirental | not_promoted_to_partial_information_consumer | full-info |
| M16 | rope 计划 | inapplicable_to_current_binding | binding |
| M17 | 差分输出 DiffPlan | existing_output_sharing_retained | sharing |
| M18 | 共享输出结构 | existing_output_sharing_retained | sharing |
| M19 | 见证前缀归一化（fused_prefix） | reject_current_measured_implementation | stage |
| M20 | 门控全信息索引 | not_promoted | full-info |
| M21 | 选择器 SEL | not_promoted | full-info |
| M22 | derive_first（模型修正） | reuse_existing | derive-first |
| M23 | 投影支撑 | rejected_for_current_production | scope |
| M24 | 重签（GSession reissue eager/lazy） | not_adopted | scope |
| M25 | noop_none 生产者提示 | rejected | obsolete |
| M26 | posrev 屏障 | no_new_position_revision_array | obsolete |
| M27 | 留存 GDS（字节/步数估计） | reject_for_current_consumer | retention |
| M28 | LUT 融合算子 | reject_current_measured_implementation | score-cache |
| M29 | straight-line 转移 | reuse_existing_or_reject_representation_only | obsolete |
| M30 | OwnerView | already_avoided_in_production | obsolete |
| M33 | value 复用 + incarnation 授权 | reuse_existing_with_boundary | binding |
| M34 | reserve1 预留 | reuse_existing_no_new_scheduler | schedule |
| M35 | 微批 batch_peritem | reuse_existing_no_new_scheduler | schedule |
| M36 | 退款隔离信用 refund_isolated | reuse_existing_no_new_scheduler | schedule |
| M37 | 动态预留/固定预留（调度） | reuse_existing_no_new_scheduler | schedule |
| M38 | 跨 rebind 重签（MigratingSession） | reuse_existing_with_boundary | binding |
| M39 | T04 携证明证书 + 小校验器（只测了校验器内核成本） | not_promoted | theory |
| M40 | X05 共享图携支撑（AB1） | not_promoted | theory |
| M41 | X30 L2 迁移（cut16）组合 | rejected_rule_change | rule |
| M42 | 采用策略 race_keep | do_not_default | schedule |
| M43 | 采用策略 race_cancel | do_not_default | schedule |
| M44 | 采用策略 adaptive | do_not_default | schedule |
| M45 | 采用策略 ski | do_not_default | schedule |
| M46 | 采用策略 gated_proof_first | existing_gated_serial_policy_retained | protocol |
| (M01 变体) | gated_shadow 用 fresh exact 路线 | reuse_existing_route_no_new_dp_promotion | branch |
| LOCAL01 | 由EXACT对照启发的已知later-null局部强证明 | retained_default_bounded_AUTO | local01 |

## 180题与已审阅的未映射工程方向

每题保留title、route、status、decision、remaining、work_package及原证据。55题按D3_INDEX显式引用映射；其余125题逐题阅读后分类如下。前四类是知识/反例归档，不是独立待合入功能，也不声称其全部模型协议已在main实现。

| 分类 | 数量 |
|---|---:|
| 理论界 | 18 |
| 测量统计工具 | 15 |
| 协议约束 | 39 |
| 抽象实验原型 | 22 |
| 独立工程候选（已审阅，原机制映射未覆盖） | 31 |

下列31题已结合当前主干与root独立语义审阅完成处置。原D3_INDEX未给这些题权威工程交叉编号的事实不变；下表是本次语义分组，不冒充原档案映射。条件研究不等于获准实施，也不等于已经证明净收益。

| 原ID | 问题 | 当前处置与边界 |
|---|---|---|
| E04 | 渐进见证服务。 | 暂缓有价值保留：A1已有获取后非负部分和early-null；没有采样或服务流式取消。阶段流式服务仍为条件研究，须包含真实开流/解码/取消成本。 |
| F01 | 按反事实分歧挑选事实。 | 暂缓有价值保留：PrefetchPlan聚合真实NeedFacts并有bounded lookahead；没有反事实或见证权重控制器。联合收益未校准，不能把既有NeedFacts聚合等同于信息价值最优控制器。 |
| F02 | 见证级联合信息价值。 | 暂缓有价值保留：PrefetchPlan聚合真实NeedFacts并有bounded lookahead；没有反事实或见证权重控制器。联合收益未校准，不能把既有NeedFacts聚合等同于信息价值最优控制器。 |
| F03 | “先分析后秩”的自适应阶段。 | 暂缓有价值保留：PrefetchPlan聚合真实NeedFacts并有bounded lookahead；没有反事实或见证权重控制器。联合收益未校准，不能把既有NeedFacts聚合等同于信息价值最优控制器。 |
| F05 | 取证顺序与证明难度联动。 | 暂缓有价值保留：PrefetchPlan聚合真实NeedFacts并有bounded lookahead；没有反事实或见证权重控制器。联合收益未校准，不能把既有NeedFacts聚合等同于信息价值最优控制器。 |
| G02 | 分支路径的支撑有向图。 | 暂缓有价值保留：当前使用exact read maps，没有intern DAG。Python负结果不能外推native布局；与A3同属layout候选，当前净收益不明。 |
| I01 | 关键路径感知准入。 | 暂缓有价值保留：均为acquisition排序；当前kind/latest-intent不是基于有界服务时长的最优策略。NH02合并I01方向，不是prooffrontier FIFO；须有真实服务时长与准入目标。 |
| I03 | 多消费者共享的负外部性。 | 暂缓有价值保留：当前没有经过许可的bounded bypass生产实现。共享负外部性策略条件保留，逻辑cancel不能算物理退出，必须维持现有physical authority。 |
| I04 | 服务阶段的最优切分。 | 暂缓有价值保留：既有header/decode阶段不等于模拟gate_split。split/joint排队模型的rho/p未按真实生产校准，阶段切分只作条件研究。 |
| I06 | 完成未消费的背压。 | 前提不适用/研究模型：现有bounded logical work及physical authority原则已有；不是模拟buffer=2常数。不同模型边界保留，不据此新增buffer或宣称模拟常数已采用。 |
| J01 | 低置信预测的投机准入。 | 暂缓有价值保留：固定K和分角色预算不等于按近期预测准确度门控。预测准确度门控仍为条件研究，不把现有预算命名为该策略。 |
| J03 | aging 的服务曲线。 | 暂缓有价值保留：当前没有动态aging/SLO策略。先定义产品可选延迟SLO，再研究aging；模型服务曲线不能独立决定产品策略。 |
| J04 | 只对相关变化防抖。 | 暂缓有价值保留：当前exact relevance判断不是时间debounce。时间防抖为条件研究，不能吞掉READ failure或其重试。 |
| L02 | proof 的结构化存储。 | 暂缓有价值保留：当前使用exact read maps，没有intern DAG。Python负结果不能外推native布局；与A3同属layout候选，当前净收益不明。 |
| O04 | 交互临界路径外的索引。 | 暂缓有价值保留：后台摘要索引不是speculative IO；当前没有获益consumer。闲时摘要仅保留条件研究，不能将既有预取当该索引接线。 |
| O05 | 稀疏已知岛与桥接。 | 暂缓有价值保留：known reset anchors不等于跨未知桥的all-completions摘要。保留条件精确算法方向，不凭reset anchors声明等价。 |
| O06 | 消费者适配的真实门槛。 | 前提不适用/研究模型：当前single-target API具有等价接口形状，Strong engine不在main。归Strong兼容设计知识；接口相似不代表Strong consumer已安装。 |
| Q02 | 持久派生索引的盈亏。 | 暂缓有价值保留：当前是内存owner并在close清理，不是持久化派生索引。归条件storage研究，需严格值身份、版本、恢复与真实持久成本。 |
| X12 | 渐进服务 × 分阶段取证（E04/F03）。 | 暂缓有价值保留：A1已有获取后非负部分和early-null；没有采样或服务流式取消。阶段流式服务仍为条件研究，须包含真实开流/解码/取消成本。 |
| X15 | 单事实价值 × 见证联合价值（F01/F02）。 | 暂缓有价值保留：PrefetchPlan聚合真实NeedFacts并有bounded lookahead；没有反事实或见证权重控制器。联合收益未校准，不能把既有NeedFacts聚合等同于信息价值最优控制器。 |
| X16 | 在途等待 × 共享负外部性（F04/I03）。 | 暂缓有价值保留：当前没有经过许可的bounded bypass生产实现。共享负外部性策略条件保留，逻辑cancel不能算物理退出，必须维持现有physical authority。 |
| X17 | 计算感知取证 × 反向剪枝（F05/B05）。 | 暂缓有价值保留：PrefetchPlan聚合真实NeedFacts并有bounded lookahead；没有反事实或见证权重控制器。联合收益未校准，不能把既有NeedFacts聚合等同于信息价值最优控制器。 |
| X27 | 五态无分配 × 结构化proof（L01/L02）。 | 暂缓有价值保留：当前使用exact read maps，没有intern DAG。Python负结果不能外推native布局；与A3同属layout候选，当前净收益不明。 |
| NH02 | NH-A3 | 暂缓有价值保留：均为acquisition排序；当前kind/latest-intent不是基于有界服务时长的最优策略。NH02合并I01方向，不是prooffrontier FIFO；须有真实服务时长与准入目标。 |
| NH11 | NH-C1 | 暂缓有价值保留：PREFIX normalization cache已有R9工程尝试，PNG总成本没有稳定收益。按已尝试缓存候选归档，不再当新缺口；与A1 primitive评分区分。 |
| NH13 | NH-C3 | 重复/被后续取代：归原题X06的分层index方向。作为X06同一方向保留，不列为独立优化。 |
| NH14 | NH-C4 | 暂缓有价值保留：这是prefix[0,t+2]右lookahead猜想，不是固定local radius。仅900随机样本，缺证明，不启用；不能从随机未见反例推出全域正确性。 |
| NH15 | NH-C5 | 暂缓有价值保留：后台摘要索引不是speculative IO；当前没有获益consumer。闲时摘要仅保留条件研究，不能将既有预取当该索引接线。 |
| NH18 | NH-G3 | 暂缓有价值保留：归R9 M06/M23 semantic projection已尝试方向；合法同内容无hits。rank jitter不合法，不能作为收益输入；此题不是A3紧凑精确比较。 |
| NH21 | NH-G6 | main已有等价：ReaderSpreadSamplesOwner.acceptSamples已先scores相邻推导，再while samples>capacity逐出，derive-before-evict已有等价。closure策略本身仅条件保留且未启用；cap1闭包仍有livelock反例，不据derive-first等价宣称closure采用。 |
| NH26 | HH5 | 暂缓有价值保留：既有header/decode阶段不等于模拟gate_split。split/joint排队模型的rho/p未按真实生产校准，阶段切分只作条件研究。 |

## 复查与重启边界

先查原编号、假设、反例和撤回原因，再按来源清单取ZIP成员或bundle。明确当前consumer、合法输入、身份/发布边界及真实成本缺口后，另立focused研究；不整树合入旧Strong/实验开关。R7低成本候选以A1为独立对照恢复A2/A3/A4，不能叠加借用旧收益。理论、统计工具和抽象模型无需伪装成待合并产品补丁。

本次只生成两文件并检查JSON、ID覆盖与本地链接，没有产品测试。180题均有明确当前关系处置；31题已完成语义审阅，仍保留原45机制没有显式覆盖它们的事实。这里的审阅完成不等于旧模型已在产品实现或旧测试当前通过。
