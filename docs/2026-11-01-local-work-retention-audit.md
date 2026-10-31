# CP1 收口后的本地分支与工作树保留审查

日期：2026-11-01。审查基准：本地 `main=0126b62ac11e9f50fc2b4cd1c987994d5cc60102`。本报告提供处理建议，不执行合并、回滚、stash apply/drop、分支删除、工作树移除或 prune。

## 1. 结论与判定口径

- CP1 已按 A 方案完成净改动整合及 Windows 用户验收，见 [收口记录](2026-11-01-reader-cp1-closeout-progress.md)。旧研究分支的大量 `main..branch` 提交是历史身份差异，不能等同于尚缺功能，更不能相加成剩余任务量。
- 旧候选与主干采用三方净整合，故 `git cherry` 的非等价也不证明功能遗漏。本报告结合提交内容、收口范围和主干代码核对；对未做完整语义验证的内容明确保留为候选，不宣布可安全删除或可直接合入。
- 优先保留已验收主干；CP1 研究历史、失败实验及原始性能证据离线归档；其他任务的有效候选单独排队，不能重开 CP1 收口或整包合并历史分支。
- “可归档”不等于“已备份”或“立即删除”。只有备份可恢复性核验通过后，才考虑移除旧工作树。Git bundle 不含未提交、未跟踪及 ignored 文件；尤其不能丢失 `.gradle-coordinator` 中唯一测试证据、原始样本、复现材料和输入夹具。
- 本次未联网 fetch；所有“远端没有”仅指本地 remote-tracking refs。审查时 `origin/main=ca42b8f2c18b8f2fe8e28f71414fafbae1acd92d`，本地 main 对缓存 origin/main 为 37 ahead / 0 behind。这 37 笔不能因本次清理而顺带推送。

分类：**归档**＝不再作为待合功能，保留可恢复历史；**另案保留**＝确有候选或未核清差异，单独按需求评估；**证据保留**＝不进入产品代码；**待恢复访问**＝不能判断磁盘内容；**可弃候选**＝只在备份核验与清理授权后才可丢弃。

## 2. 本地分支逐项审查

分支名无前缀者均位于 `codex/`；HEAD 为唯一前 10 位。

| 分支 | main.. | HEAD | 内容与主干覆盖证据/不确定性 | 建议 |
|---|---:|---|---|---|
| cp1-s4-candidate-20261027 | 254 | `1e47b14ece` | CP1 收口交接、主要历史研究与大量证据；a635 产品闭包已入 main，HEAD 后续研究和 `217ce0420e` 等差异未全纳入。不能整支合。 | **保留为 CP1 最终历史/证据入口**，产品整合任务已收口；独有实验维持隔离。 |
| cp1-ledger-diagnostic-20261029 | 191 | `c11bccc380` | 被动 native/ledger、线程栈/前后台/D3D 诊断；含 880037a6、370f467 及后续动画候选。main 收口明确未采用前两项且排除诊断。 | **保留隔离研究**；可选正确性修复仅在新任务独立评估，不补合整支。 |
| reader-pairing-r10-engineering-20261028 | 191 | `24cd1d11a6` | R10 缓存/checkpoint/CPU/资源 ledger/原始成本与 first-next timing。收口明确排除 R10；当前分支是独立研究结果，非待漏合 CP1。 | **保留隔离研究**；如继续 R10 则新立验收，不混回 CP1。 |
| cp1-checkpoint-consumer-20261029 | 164 | `88992ac9dc` | 在 control 上加 `0a83c9e97f` 有界 checkpoint 精确证明复用及被动 draw 测量；a635 后新增内容未入 main，也不是 s4/ledger 的已证祖先。 | **归档为未采用实验候选**，保存快照供将来 R10 对照；不自动整合。 |
| cp1-checkpoint-control-20261029 | 163 | `05e769f6a0` | checkpoint 对照、被动 native 测量与 9701 优化；是 ledger 分支祖先。基础正确性闭包已入 main，对照/优化未承诺交付。 | **归档对照分支**；保留研究快照即可。 |
| cp1-handoff-baseline-20261029 | 162 | `c684eaa872` | 原 viewport 延迟/critical-path 交接基线，含 a635 后 9701 优化。是 s4、ledger、checkpoint-consumer 祖先。 | **归档旧基线**；既非新功能缺口，也不需补 merge。 |
| ordered-latency-r10-20261027 | 160 | `d198a5f917` | 独有匿名 R10 研究包、120 问及数据投影；共享基础同 a635 链但包不在收口范围。 | **归档独立研究包**，保留可追溯快照；将来研究再引用。 |
| cp1-wider-window-evaluated-20261027 | 158 | `056c481556` | 独立 3 页扩窗证明重试、memo/owner/session 调整与成本测试；相对 a635 有真实 production 差异，不能说已全部覆盖。 | **归档为已评估、未选入候选**；若重启采用则另案，不倒灌旧实现。 |
| reader-pairing-research-next-20261014 | 156 | `68a5575932` | 原长期 CP1 研究树，早期 `68a557` 防未证槽位/旧快照；最终采用 a635 的完整后继闭包而非照搬该 HEAD。正式 L1/v3 样本在此 worktree ignored 目录。 | **保留原证据树及原始数据**；产品工作已由 main 收口，不能因“归档分支”删除工作树。 |
| cp1-local01-native-20261027 | 97 | `3a5af9f9c4` | LOCAL01 原生导航验证及候选保留，早期 R6 性能链。当前 main 采用后续 a635 必要闭包；本分支历史报告未整体移入。 | **归档阶段验证快照**，不视作另一个待合产品实现。 |
| reader-pairing-r6-csl-d3 | 96 | `bb78b48237` | known-null ranks 跳过无关采样、原图解码/缓存成本优化及研究包。是早期演进实现，后续收口选 a635 闭包，未逐笔证明所有实验采用。 | **归档早期研究候选**；不建议回退式补合。 |
| reader-pairing-r5-confluence | 60 | `dad72cd250` | 更宽窗口合流认证及两套匿名算法研究包；主干已采用后续共享认证闭包，历史包未全移植。 | **归档阶段研究**，需要原数据时按此快照读取。 |
| reader-pairing-r4-entry-prune | 55 | `7214f9826f` | reachable entry 剪枝与早期审查/扩窗研究；main CP1 已纳入后续 memo/剪枝实现和契约。 | **归档早期候选**；不据 hash 缺席重复移植。 |
| reader-cp1-bounded-followup-20261015 | 12 | `55d91c1011` | >16 必要需求、并行预取、replay receipt runtime epoch 隔离及 R1 交接。产品基础被最终 a635 闭包取代/吸收，replay 计时工具属于排除的研究设施。 | **归档旧执行阶段**；不补合计时设施。 |
| reader-local-restoration-prototype | 9 | `02fbb1164a` | 离线图像修复原型、后续非破坏性/有界本地模型及无损 tint 审阅脚本；这是像素图像增强，与 CP1 资源恢复不是同一功能。未发现 main 中相应 restoration 模块/脚本交付。 | **保留独立原型**，如要产品化须另案；不能用 CP1 已收口为由删除。 |
| cp1-s4-l1-20261027 | 5 | `db7b0e24bc` | L1 >16 必要认证需求及 replay L0/计划基线；是 s4、ledger、R10、consumer 的祖先。main 已有后续必要需求/预算实现。 | **归档正式比较基线**，保留版本身份，不需补合。 |
| reader-pairing-main-20261013 | 4 | `f38e0f9f98` | chapter target 清理和 author mock 在 main 已有；但 f38 保留 mounted frame 的 setAutoSpreadMatching 改法未在 main（main 仍 changePresentation，清 displayUnit/visiblePages 并推进 input epoch）；489d 原生夹具修正亦不同。未执行当前行为复现，不能判现存 bug。 | **保留并另案复核开关匹配的画面连续性**；不要宣称整支已覆盖，也不要直接套旧补丁。 |
| sync-least-privilege-onboarding | 4 | `0f29ace4ad` | 四笔均 patch 等价；main 对应 `94cbb86b59`、`96a6b96684`、`71c9b60ebf`、`cb55c695c5`，含私有仓库初始化、最小权限引导、候选证据及中文文案。 | **归档，内容已有主干对应**；无需重复整合。 |
| feedback-form-screenshot-spec-20261017 | 3 | `d250cebdf5` | 分类反馈、私密 JPEG 附件、分类日志与临时诊断，107 文件实现/测试及两笔设计；main 缺 FeedbackCategory/Diagnostics/Screenshot 这些文件及服务迁移 0004/screenshots.mjs，只有早期反馈链。不是 CP1 内容。 | **需单独整合评审**，保留分支；覆盖共享协议、两平台与服务迁移，不应顺手合并。 |
| title-credits-main-daily-20261012 | 3 | `f598adc928` | 前两笔默认 patch 等价，最后一笔与 main `7d3c3d34bf` 零上下文 patch ID 完全一致；main 后续还有 `cf1ea0417f` dated-event title parser 改进。 | **归档，功能已有正式主干实现**；不要把旧解析器覆盖新修复。 |
| history-reader-ci-20261014 | 2 | `977e06c588` | 两笔：certificationTargets.retainAll 和 author hasTitleAttribution mock。已读取精确差异，main 两个对应代码均存在；reader-pairing-main 另有未覆盖后继内容。 | **归档，两笔局部修复已有对应**。 |
| android-dual-page-tile-seam | 1 | `3ed1cf3c49` | Android 双页原始 Fit 比例/贴脊像素对齐、SSIV 舍入与 resize pose 失效；main 的 ReaderPageImageView 仍为旧 blob `5b64aa38be`，修复确实未入。已读历史证据：focused 12项、53档模拟器窗口通过，用户尚未验收。 | **需单独整合**，保留分支；旧版本号不能重用，当前 CP1 adapter 接线需 focused 复验。 |
| android-ripple-wip-20261017 | 1 | `388abad41f` | 页翻 ripple 模式设置、偏好、Pager 实现及测试的 WIP。main 有 `53e23eb0b7` 边缘测试但缺 production 接口；本次收口报告实际记录这些测试导致 Android 测试编译失败。 | **保留 WIP，需单独收口决定**（完成实现或明确撤回孤立测试）；不自动合并。 |
| cp03-final-20260927 | 1 | `5840cc6bc1` | 恢复在线配对但不伪造进度，patch 等价于 main `9bf1e86b0d`；后续 CP1 继续覆盖相关内容/进度链。 | **归档，已有主干对应**。 |
| desktop-fullscreen-reader-20261017 | 1 | `fcf0f04fd3` | 唯一未合笔只把 Android 版本分配到 aex.107/vc125；没有未合全屏产品代码。分支名字不代表该一笔内容。 | **归档旧版本分配**；不要将过期版本号重放。 |
| luna-v12-reader-test-memory | 1 | `0c42b4b2dd` | 单个 IO production wiring 测试的有界 render、偏好隔离与清理。main 当前采用 fixture.readerPreferences/appPreferences、有界 MAX_FRAME_PUMPS、render.close 及更强首帧/页头读取断言；不是逐字相同，但关键修复已有后继实现。 | **归档为已替代测试修复**；未声称当前测试重新通过。 |
| pi-aa203 | 1 | `5ed4374467` | 独立作者发现 scheduler、设置/作者入口与 runtime/DI 草案。main 有正式 `2aa3c9f1cd` 及 `fcafbccf2e`，已读取现行 scheduler/runtime/DI：独立 due loop、恢复、取消/状态及停止接线均在。草案 patch 与正式 patch 不同。 | **归档评测草案**；正式实现已接线，未逐项证明草案所有细节语义等价，不能回贴旧实现。 |
| pi-eval-deepseek | 1 | `b5f4f8c6ad` | resilient creator discovery executor 草案（baseline、去重、失败退避等）。main 有正式 `c9c1605652` 及后续共享服务/事务改进，baseline/repository 接口保留，现行模型还有 observation 原子提交/epoch 等后继字段。 | **归档评测草案**；已有正式演进实现，未逐行作全量语义等价证明。 |
| spike/reader-local-image-restoration | 5 | `aa2d6a7dad` | 图像修复 stage-A/真实页面产物与亮度漂移记录，另夹 CHIPS cf_clearance 与 app-lock 测试小修。是 restoration-prototype 的早期祖先；后者含后续4笔改良。 | **归档早期 spike，保留后继 prototype**；夹带两笔小修未在本轮逐项作主干等价证明，不据此直接整支合。 |

补充：主干收口采用 a635 的必要闭包，未采用 a635 后的 9701 重复验证优化、checkpoint、R10、ledger 等。表中“归档早期候选”不表示其每个实验都已进入产品。title 最后一笔与 main 采用零上下文 patch-id 核对，避免默认 cherry 因上下文不同误报遗漏。

## 3. 未提交工作树逐项审查

初始快照：92 处登记，87 可访问＝34 dirty＋53 clean，另 5 不可访问。34 处合计 271 个 tracked 状态条目、84 个 untracked 状态条目（目录默认合并计数、未跨树去重）。本报告新增文件不计入这份接手快照。大差异按主题及局部内容分类，未做逐行语义等价证明；下表已纳入主审对覆盖关系的纠正。

`tracked/untracked` 为该工作树内的 porcelain 条目数。

| # | 工作树 | tracked/untracked | 内容类别与建议 |
|---|---|---:|---|
| 1 | `D:/Shell/Github/mihon` | 1/1 | `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt` 仅 `BUILD 6→9`；未跟踪 `.codex-remote-attachments/` 内有 `1-ordered-system-r9-fix-results.zip`。版本号本身不是功能需求；ZIP 是独有附件，建议先另案归档/核对用途，不能按垃圾处理。 |
| 2 | `D:/Codex/worktrees/85be/mihon-sync-old-96db` | 2/0 | 仅同步测试：`SyncGitSafetyContractTest.kt` 和大幅扩展的 `SyncScaleAcceptanceTest.kt`，新增首次导入、完整交换、增量、恢复与堆内存/请求测量场景。属于测试/性能验收 WIP，不是生产功能；保留到验收意图核对后再归档。 |
| 3 | `D:/Codex/worktrees/catalog-pack-20261012` | 1/0 | `gradle/android-release.properties` 仅 Android `versionCode 79→80`、`versionName 0.19.4-aex.61→.62`。打包版本元数据；与目标发布核对后可单独撤销/归档，不计产品需求。 |
| 4 | `D:/Codex/worktrees/cp1-ledger-diagnostic-20261029` | 0/1 | 仅 `app-desktop/hs_err_pid25124.log`，327 KB JVM 崩溃报告。临时诊断产物，正文未读；如需保留崩溃证据则归档，否则可在单独确认后清理。 |
| 5 | `D:/Codex/worktrees/cp1-s4-candidate-20261027` | 4/1 | 修改 `docs/2026-10-29-reader-cp1-new-session-handoff.md`、`docs/reader-cp1-progress.md`、`docs/reader-cp1-scope.md`、`docs/reader-cp1-wait-diagnosis-plan.md`，新增 `docs/2026-10-31-reader-cp1-independent-review-response.md`。这是 CP1 指标/停止条件争议及交接证据；结论已由 A 方案收口记录承接；原文保留为历史审查证据，不重开裁定、不当成产品代码。 |
| 6 | `D:/Codex/worktrees/cp1-s4-l1-20261027` | 0/5 | 新增 reader smoke/测量测试：`DesktopReaderNativePerformanceSmokeTest.kt`、`ReaderCanonicalPlanOracleTest.kt`、`ReaderSmokeTimingConsumer.kt`、`ReaderSmokeTimingConsumerTest.kt`，以及 `scripts/reader-pairing-perf/cp1-luna-performance17-r3.init.gradle`。是性能/生产路径观测夹具，不是产品能力；若仍需 CP1 证据则保留，否则按测试探针归档。 |
| 7 | `D:/Codex/worktrees/f235/hp02-history-candidate` | 3/3 | tracked 为 Desktop build 号 `69→75`、Android `app/proguard-rules.pro` 新增外部 formal fixture 的反射 ABI keep 规则、Android release metadata 变更；新增 `app/src/historyFormalAndroidTest/`（含 `HistoryFormalInstrumentationTest.kt`、`HistoryFormalReaderInstrumentationTest.kt`、`HistoryFormalSource.kt`、manifest）、`scripts/history-android-formal-fixture.py`、`scripts/history-android-formal.init.gradle`。这是独立 Android history formal 测试/收缩配置候选，值得保留；版本和 fixture 需与正式打包范围另案核对。 |
| 8 | `D:/Codex/worktrees/luna-v10-formal` | 13/3 | Desktop reader presentation owner、region/image、单双页 pager、screen/settings 与对应测试；新增 `WorkflowReaderCriticalPathTest.kt`、`WorkflowReaderProductionFixture.kt`、`ReaderPageTurnPresentationTest.kt`。属于页翻呈现/交互的真实生产接线候选及 acceptance tests；保留，后续与其他 Luna 迭代比对，不单独把测试当产品需求。 |
| 9 | `D:/Codex/worktrees/luna-v10-oracle` | 7/3 | v10 reader/screen/pager/settings 可访问性改动及 3 个相同 workflow/page-turn 新测试；范围比 v10-formal 小，是 oracle/子集候选。建议保留到选定规范候选后再合并/归档。 |
| 10 | `D:/Codex/worktrees/luna-v11-candidate` | 11/3 | Desktop screen、双/单页 pager、presentation image/transform、mounted reader fixture 与 IO wiring 测试、settings/accessibility；新增 workflow fixture 和 page-turn presentation tests。属于后续 reader presentation 迭代，保留并与 v10/v12 比较。 |
| 11 | `D:/Codex/worktrees/luna-v12-candidate` | 8/4 | 同类 reader/pager/settings 改动，另改 `parity-manifest.json`；新增 workflow 测试、独立 page-turn 测试和 presentation 测试。候选和跨端 parity 证据，保留；manifest 与测试应随最终行为核验。 |
| 12 | `D:/Codex/worktrees/luna-v13-candidate` | 8/3 | reader/pager/settings、mounted-reader fixture 和 accessibility 测试；新增 workflow fixture/page-turn tests。另一个 reader 页翻候选，保留并对照前后版本。 |
| 13 | `D:/Codex/worktrees/luna-v15-candidate` | 82/7 | 约 **5,377 增/1,931 删**：覆盖 Desktop reader screen/session/runtime/presentation、共享 `ReaderRequestScheduler.kt` 与 `ReaderSessionCore.kt`、settings，以及大量生产接线/reader/parity 测试；新增 critical-path、page-turn independent/runtime/recovery acceptance tests。抽查确认 scheduler 新增 interactive target replacement、session 新增 page prepare/register API。跨多层历史产品 WIP（未证明比当前 main 更好或尚缺这些功能）；**未逐行审查**，应优先保护并另案独立审查，不要由版本名推断已完成。 |
| 14 | `D:/Codex/worktrees/luna-v20-main-control` | 9/3 | reader 单/双页及 Webtoon、settings、accessibility 改动，新增 `docs/architecture/reader-shared-core.md` 和 workflow/page-turn tests。包含共享 reader 核心边界文档及候选实现；保留，需与 v15 的共享核心方案区分/比对。 |
| 15 | `D:/Codex/worktrees/luna-v7-historical` | 0/1 | 仅新建 `HistoricalOmissionTest.kt`，属于历史差异测试。可作为历史证据保留或随对应审计归档；不是功能代码。 |
| 16 | `D:/Codex/worktrees/luna-v9-reader-repro` | 1/0 | 仅对 `ReaderCriticalPathProductionTest.kt` 增 2 行，production-path 回归/复现测试改动。保留与对应问题一起核对，不算产品实现。 |
| 17 | `D:/Codex/worktrees/mihon-fork-reader-perf-parallel-20261020/mihon` | 24/2 | 约 **6,671 增/741 删**：reader 页面内容/图片 pipeline、spread sampler、`ReaderIoGate`、共享双页 session/prefetch/certification、Android pager 和 instrumentation；另有 in-process benchmark rules/fixture。涉及早期 CP1 产品与对照实验。主审补核：24 个 tracked 差异中 8 文件的当前工作区内容已与 main 一致（归一换行），含内容 owner、IO gate、prefetch、open coordinator 和相关测试；其余存在后续演进差异，未逐行审查。优先作为历史基线归档，不把 24 文件都当遗漏；只有明确新需求才提取残差。 |
| 18 | `D:/Codex/worktrees/mihon-m2-baseline-63236da` | 1/0 | `SyncPanelContent.kt` 将六种安装/仓库权限错误统一映射为 unknown。主审已核 main 的 setupProblemText 对这些错误有专属提示；旧兜底已被更具体实现替代。**归档，不应用旧映射**。 |
| 19 | `D:/Codex/worktrees/mihon-main-wip-20261011` | 12/1 | 同步 onboarding/password safety 设计文档及双端 HTML demo、样式/交互与 browser tests；新增 `docs/human/PROJECT_STATUS.md`（历史需求便笺，含本机图片引用）。README 明确原型不代表生产同步代码已改。值得保留作为产品设计/原型证据；新便笺先归档到合适位置并检查本地引用，不据此声称生产功能已完成。 |
| 20 | `D:/Codex/worktrees/mihon-update-feedback-20261013` | 0/2 | 新增反馈报告 HTML 原型 `docs/prototypes/feedback-report/device.html` 和 `feedback-browser.test.cjs`；另有 `.wrangler/` 本地生成状态目录（浅查直系 1 项，未递归）。原型值得保留；`.wrangler` 属工具运行产物，可单独确认后清理。 |
| 21 | `D:/Codex/worktrees/mihon-upstream-reader-perf-20261020/mihon` | 1/1 | `app/build.gradle.kts` 增加按 Gradle property 开关的 reader in-process benchmark source/assets/依赖接线；新增 `app/proguard-reader-inprocess-benchmark.pro`。纯 benchmark/build 接线，不是用户功能；保留测量方案，确认完结后归档。 |
| 22 | `D:/Codex/worktrees/reader-ci-history-20261014` | 1/0 | 仅改 `SharedDualPageNativeStablePresentationTest.kt`（约113 增/31 删）：真实编码像素、生产 Compose presentation、zoom/crop 与 ticket/sequence 断言。是双页稳定呈现回归测试维护，不改生产代码。相关证据文档记载 31 项中 1 项仍失败、需独立判断契约；只读审计没有重跑测试。保留测试及失败证据，不放宽断言。 |
| 23 | `D:/Codex/worktrees/reader-pairing-main-20261013` | 5/0 | 4 个 Desktop reader/session/pairing 测试加 `docs/evidence/reader-pairing-research-2026-10-12.md`；文件记录当前分支/目标测试的已知失败及不可放宽的断言。主要是测试与证据维护，保留并按文档交接核对失败，不视为产品代码变更。 |
| 24 | `D:/Codex/worktrees/reader-pairing-r3-baseline-20261012` | 0/1 | 新建 `app/src/androidTest/.../R3DownloadProbeInstrumentationTest.kt`（65 KB）。Android 下载/阅读探针；保留若仍支持测量，否则归档为历史探针；不能当功能缺口。 |
| 25 | `D:/Codex/worktrees/reader-pairing-research-next-20261014` | 3/5 | tracked 为 AppVersion、ReaderPairingCertification.kt、数学 runner。主审补核：认证生产文件归一换行后与 main **完全一致**；新增 test pixels 也一致，reachable-entry 测试存在后续差异。另有量化报告、实验/counter 脚本。**产品剪枝已覆盖，研究资料归档**，不再要求开发该算法；保留 ignored 正式样本。 |
| 26 | `D:/Codex/worktrees/sync-release-20260919` | 1/0 | 仅 Desktop `AppVersion.kt` `BUILD 50→51`。版本元数据，单独核对目标构建后归档/放弃；不作为产品需求。 |
| 27 | `D:/Shell/Github/mihon/.worktrees/feat-auto-20261023-5c28ea99/.candidate/reader-pairing-r6-baseline` | 2/2 | reader pairing candidate/session cost harness 与 trajectory 测试、`run.py`；约 482 增/285 删。属候选成本/轨迹研究夹具，不是用户行为改动；保留至研究引用核对后归档。 |
| 28 | `D:/Shell/Github/mihon/.worktrees/fork-02a609497a43-8dd57a8153c14128a96255666627d8c6` | 22/24 | 约 **617 增/160 删**：Desktop startup、DI、Main、window/source/extension 启动路径与相关测试；untracked 有 `DesktopOwnerBootstrap.kt`、`DesktopStartupTrace.kt`、startup test、budget JSON、优化设计文档及分析/测量/关闭验证脚本。是启动窗口提前显示/owner 异步准备等真实产品启动路径 WIP，建议完整保留并另案核对启动/关闭与行为回归；**未逐行审查**。 |
| 29 | `D:/Shell/Github/mihon-aex05-old` | 1/0 | `app/build.gradle.kts` 删除 `aboutLibraries` plugin alias、改为引用 generated resources。是旧 Android build 配置迁移候选；不属于用户功能，保留到构建接线另案确认。 |
| 30 | `D:/Shell/Github/mihon-fullscreen-white-edge-a68d287856` | 1/0 | 仅 Desktop `AppVersion.kt` `BUILD 6→9`，无相应 fullscreen 修复文件。应视为版本差异，不从 worktree 名称推定有产品修复；与目标构建核对后可归档/放弃。 |
| 31 | `D:/Shell/Github/mihon-pi-deepseek-aa203` | 0/2 | `pi-aa203.stdout.log`、`pi-aa203.stderr.log` 均为 0 字节。空诊断日志，没有可保留内容；可单独清理确认，不含产品 WIP。 |
| 32 | `D:/Shell/Github/mihon-reader-auto-release` | 40/2 | 约 **337 增/113 删**：Android/Desktop 阅读模式偏好、默认/自动模式与双页 viewer/settings、共享 parity、roadmap 和中英文资源；新增 `domain/.../ReaderModeFlags.kt` 及 contract test。是自动布局/继承历史候选。主审补核 40 tracked 中 9 文件与 main 相同；main 已有 ReaderModeFlags 及契约测试，但与这里未跟踪文件不同。**保留归档，采用前仅核对残差**；不能把自动布局整体说成尚未实现。仅 AppVersion 变动不计在能力判断中。 |
| 33 | `D:/Shell/Github/mihon-reader-error-exit-build` | 1/2 | ReaderActivity 增初始错误关闭/退出与重试说明，另两份 native/integration 测试。主审核 main 已有完全相同的 closeError/按钮处理和说明，两个新增测试归一换行后与 main **完全一致**。**已有主干对应，归档，不重复修复**；整个 ReaderActivity 不同是其他后继改动，不能据整文件不同误判遗漏。 |
| 34 | `D:/Shell/Github/mihon-task151-windows` | 6/4 | Desktop test mode、外部动作/分享策略和 TestHttpServer 改动；新增 `DesktopPlatformAcceptanceController.kt`、controller test 及 PowerShell/shell evidence scripts。属于 Windows 平台接受/分享的测试控制与证据夹具，不是普通用户功能；若 task151 仍在验收则保留，完成后归档。 |

## 4. Detached HEAD：九处逐项判断

**最终取舍见第 9 节。** 本节是首轮身份和保存风险核对；第 9 节已补完未提交内容的用途判断，取代本节“先归档再判断”的建议。

计数是相对本地 remote-tracking refs 的不可达提交数；本轮九处相对 main 的数量也相同。已有分支覆盖的提交不需要为每个工作树重复保留一份历史，但该树的未提交/ignored 内容仍须单独保存。

| 工作树（简称） | HEAD / 数量 | 内容、覆盖与处理 |
|---|---|---|
| `0877/mihon` | `9c7cbf5154` / 1 | archive-cleanup 快照仅 AppVersion BUILD 70→72（FEATURE 19）；不是遗漏产品修复。无分支覆盖，先固定归档引用，再可归档工作树；不回灌旧版本号。 |
| `l1-r3-baseline` | `db7b0e24bc` / 5 | 原准入去除 16 页上限、优先级/原图缓存与 L0 工具基线；已由 `cp1-s4-l1` 等多个研究分支包含。归档基线与正式样本，勿当作五项遗漏功能。 |
| `luna-v12-candidate` | `0c42b4b2dd` / 1 | 修复阅读器测试无限渲染与偏好隔离；由 `codex/luna-v12-reader-test-memory` 保留。未提交差异另见工作树表，测试候选不要与 HEAD 混为一体。 |
| `luna-v7-mihon` | `14e4dcdf90` / 1 | 18 文件、1415 增/59 删，跨章节展示、绘制/回放测试；提交明确记录 delayed-geometry 失败。无分支覆盖，固定归档引用并保留失败证据；不是仅一条日志或可直接采用的通过版本。 |
| `mihon-fork-reader-perf-parallel-20261020/mihon` | `faf002165b` / 17 | 早期 CP1 和平板对照记录，由 CP1 后续多个分支包含。历史归档；其 24/2 工作树差异和 ignored 数据单独处理。 |
| `mihon-m2-baseline-63236da` | `63236da236` / 1 | 同步私库最小权限实现，由 `sync-least-privilege-onboarding` 包含；按分支覆盖结论归档，另保留其单个本地配置差异。 |
| `.candidate/reader-pairing-r6-baseline` | `03a7fbd7f1` / 69 | R6 前的受控复用/分支尝试研究，由 `reader-pairing-r6-csl-d3`、`cp1-local01-native` 包含。仅作研究基线归档，不整合 69 笔。 |
| `mihon-rv01-evidence` | `6080f7926f` / 5 | 三笔旧版本分配；`ba780793ef` 治理证据修复与 main 中 `b4dc8a60ff` patch-id 一致；`fbb1a5d532` 非 Windows 跳过发布执行与 main `6a7c60d0a8` patch-id 一致。无分支覆盖，固定归档引用后归档；无须再合旧版本号。 |
| `mihon-task151-windows` | `8078bee94e` / 1 | tracking/settings 测试接线修复，与 main `fcbb351ba9` stable patch-id 一致（`23fb97c95ec7173588cd2dcfbf17adb036afd9af`）。HEAD 可归档，但其 6/4 工作树差异仍须保存；无分支覆盖，先固定引用。 |

## 5. 两份 stash：原清单遗漏了第三父树

stash 序号会随新建/drop 变化，后续操作应以这里固定的完整 SHA 再核身份，不能盲用序号。

### stash@{0}：`54cfc0355593383321e1ed306d78ea2c685a695d`

实际为 **29 个已跟踪文件 + 21 个未跟踪文件**。标题“全屏 WIP”不足以描述内容。必须保留完整 stash 三个父对象，不能只导出默认 `stash show` 的 29 文件 patch。

| 实际主题 | 内容与处理建议 |
|---|---|
| 全屏和窗口恢复 | `DesktopReaderFullscreenController` 改经 `DesktopFullscreenAdapter`，新增 Windows 无边框测试。main 已有演进后的 adapter，不能整文件覆盖；作为历史候选归档，若要采用剩余差异先三方核对。 |
| 启动/扩展/数据库/构建 | runtime `startAsync`、扩展异步装载、DI、构建 AppCDS、数据库测试、渲染后端偏好及两份设计/roadmap。main 已有不同的 `loadAllAsync/awaitInitialized`，不能据 API 名不同说能力全缺失。保留另案；roadmap 勾选不是当前实现或验收证据。 |
| 下载与 source image HTTP | `DesktopDownloadManager` 新增按 source/page 调用 `SourcePageFetcher.fetchImageResponse` 及请求头测试，混有异常映射和 source resolver 回退。是产品候选，不能随全屏草稿一起删；与主干当前 HTTP 链路另案比较、验证后再挑选。 |
| 阅读设置与双页身份 | Android 设置布局/选项位置、Desktop 面板、非法 pageId guard 与测试、中繁文案。保留为独立设置/边界候选；不将旧 SharedDualPageSession 整文件覆写当前 CP1。 |
| Android Ripple 测试 | 删除三项引用 pageTurnRippleMode/prepareRipple 的测试，并有格式调整。这不是实现波纹功能；不能以此让旧测试消失来冒充功能修复，应与波纹候选一并决定。 |
| 原型与浏览器测试 | Android reader-settings 八文件原型；feedback-report 两文件。归档为原型素材，不当成产品已交付或自动合入网站。 |

第三父树 `97a953e72ee0466e48fb658cab201b8fe8636eb5` 的 21 文件中，仅 `DesktopFullscreenAdapter.kt` 在 main 有同路径（内容不同），其余 20 个无同路径；这说明有独有材料，**不证明同等能力没有其他实现**。

### stash@{1}：`36d27d52f30c663d78d5146d116a5b0bf3ecdc97`

实际为 **9 个已跟踪文件 + 1 个未跟踪文件**（第三父树 `67b390ffeb8094efbaaa1c15cdf57b0591cd6fde` 内的 `docs/human/PROJECT_STATUS.md`）。同步设计/原型中包含把“用户已通过”改回“待审阅”、删去验证记录和测试的反向修改；不应应用到现在的同步实现或验收文档。`DETAIL-PARITY-ACCEPTANCE.md` 的 stash 后像与当前 main 相同。建议完整历史归档、不恢复到主干；保留来源以便追溯，但不是九项待做需求。

## 6. 五处不可访问登记

| 登记路径 | 本次能确认的事实 | 处理 |
|---|---|---|
| `/mnt/d/Shell/Github/mihon-pi-deepseek` | Windows 当前无法访问，Git 标记 gitdir 指向不存在位置；分支 HEAD `b5f4f8c6ad` 仍可读 | 先在原 WSL 环境核对挂载及目录；不可由 Windows 不可访问推断未提交已不存在。 |
| `/mnt/d/Shell/Github/mihon-pi-minimax` | 同上，分支 HEAD `5b17dd0751` 仍可读 | 同上，分支对象与磁盘未提交是两件事。 |
| `/tmp/aa203-clean` | 当前不可访问；登记 detached `c9c1605652` | 在原环境核对是否临时目录已消失、是否有备份，再决定 prune。 |
| `C:/Users/feeli/.codex/worktrees/f8e8/mihon` | 当前不可访问，登记 detached `024212ac6d` | 核对旧目录/备份后再清理登记；不能确认未提交内容。 |
| `D:/Codex/worktrees/9117/mihon` | `locked initializing`，HEAD `34ed3bcc91`；父目录目前仅见 `.codex-worktree-name` | 保留锁和登记，确认原创建任务已结束、无恢复需求后再解锁/修复或移除；本轮未解锁。 |

## 7. 建议的收尾顺序与边界

1. **先冻结可恢复材料**：为四处无分支覆盖的 detached HEAD 建归档引用；保留两份完整 stash（含第三父树）。将需保留的 refs 打包并验证 bundle 可读、对象可恢复；只打包 HEAD patch 不足以保存历史。
2. **保存未提交工作**：每树记录 HEAD、index/工作区二进制差异、未跟踪文件清单与文件本体；额外保存 ignored 的唯一证据和夹具。区分 staged/unstaged，保留文件哈希。排除可重新下载/编译的缓存，但不得按 `.gradle-coordinator` 整目录视为缓存删除。含用户附件、日志及原始数据的归档不自动推公共远端。
3. **CP1 研究组停止活跃**：原候选、R4–R10、control/consumer、性能/Skiko诊断只做归档；为未来可选研究留入口，不另建本轮开发目标。原正式 50 样本和本轮 focused/预览证据优先保存。
4. **独立候选留待办**：Android seam/ripple、分类截图反馈、自动匹配开关画面连续性、下载/启动/设置 WIP、其他业务树只保留具体候选条目，先按现主干查重；真正需要采用时才走小范围行为测试和整合。不要先把一切旧改动 merge 再修冲突。
5. **最后清理工作树**：有恢复验证、无活动进程占用、无唯一未保存文件后，才逐个移除。父子嵌套工作树先保存并处理子树；不可跨根递归删除；不可批量 force remove/prune。无访问权限的五处独立保留。
6. **主干版本号另决策**：当前 `AppVersion.kt` 是 BUILD 6→9，保持用户改动，不因 CP1 已交付就提交旧/新版本号，也不自行还原。附件 ZIP 保留或转个人归档，不放入产品提交。

这次只完成内容分流与风险判定；未执行清理、备份、归档引用创建或候选整合。用户现在可以结束 CP1 工作，剩余本地材料的整理不代表 CP1 未完成。

## 8. 核验范围

已读实际 refs、工作树登记、stash 各父树、差异统计及重点产品改动，并以 patch-id/实际主干内容核对已覆盖修复。大型 WIP 仅做有界内容分类，不声称逐行证明所有行为等价。没有运行 Gradle、全量测试、原生采样或网络请求；静态存在性不作为运行验收。本文为唯一交付报告，子代理扫描摘要仅为过程材料。

## 9. 九处 detached HEAD 最终取舍（含未提交内容）

补审基线 `e8beb212a0ead306f6441b130c40d4b0a6c660f6`。本节完成采用/已覆盖/淘汰的内容裁定，不以归档代替判断。没有执行删除或移植；“淘汰”表示不再作为产品或测试开发候选，“采用测试”表示应提取该具体场景到现有夹具，不能把它写成已经落地通过。

**结果：旧 production 实现采用 0 项；采用测试场景 9 项；其余已覆盖或淘汰。** 历史正式测量和验收证据仍有证明来源的用途，这不使其旧工具成为待开发功能。

| 工作树 | 已提交 HEAD 的最终决定 | 未提交内容的最终决定 | 后续用途 |
|---|---|---|---|
| `0877/mihon` | 淘汰：唯一差异是旧 BUILD 70→72 | 无 | 无代码需要提取 |
| `l1-r3-baseline` | 已被最终 CP1 替代：必要事实准入、优先级及原图复用不再从旧版移植；旧 replay 工具不采用 | 无 | 仅原正式性能对照的版本身份/数据继续用于证据，不再开发 |
| `luna-v12-candidate` | IO 测试有限渲染/偏好隔离已有更强后继实现 | 8 tracked/4 untracked 全部已分流：产品/设置/Workflow/普通页翻测试已覆盖；旧 parity 行号及重复 fixture 淘汰；只采用下面 T2–T3 | 提取两条测试刺激，不回迁产品 |
| `luna-v7-mihon` | 18 文件逐簇裁定：下载身份、常驻章节边界、元数据/主题/方向展示已覆盖；动态插入边界、手工返回锚点、Webtoon 第二套 edge gate 淘汰；仅采用 T1 | 无 | 提取一条迟到几何测试刺激，不回迁旧 boundary 状态机 |
| `mihon-fork-reader-perf-parallel…/mihon` | 早期 CP1 产品由最终闭包替代；平板对照记录是历史证据，不是遗漏能力 | 24 tracked/2 untracked 已分流：8 文件与 main 完全一致，pipeline test 仅断言换行差异；7 个 production 残差淘汰；其余旧测试/文档/benchmark 淘汰；仅采用 T4–T9 | 提取六条接线回归，不恢复旧测量构建分支 |
| `mihon-m2-baseline-63236da` | `63236da236` 经 git cherry 证实主干 patch 等价 | 唯一修改将六种权限错误统一显示 unknown；main 有各自明确提示，旧映射淘汰 | 无需提取 |
| `.candidate/reader-pairing-r6-baseline` | R6 旧策略不采用，当前认证/memo 合约为权威 | 两个 harness、trajectory、run.py 全部淘汰：依赖已不在 main 的 certifyConfluent/scopedReuse/候选 fixture；不为复用工具重建旧算法 | 无需提取，也不新建平行性能系统 |
| `mihon-rv01-evidence` | 两项治理/非 Windows 发布测试修复已有 patch 等价主干提交；三笔旧版本分配淘汰 | 无 | 无需提取 |
| `mihon-task151-windows` | tracking/settings 修复与主干 fcbb351ba9 patch 等价 | 6 tracked/4 untracked：分享/controller/test-mode/HTTP 已覆盖；controller test 与 main 完全一致；旧版本号、旧验收脚本淘汰 | 无需提取；旧脚本按 EXE 杀所有实例、旧截图端点和 provenance 做法不再采用 |

### 9.1 明确采用的九个测试场景

不是九套新测试系统，也不是九个已复现 bug。只把下列刺激/断言移到 main 现有测试类，旧独立夹具、旧产品接口、PNG输出及历史计时设施都不采用。

| 编号 | 来源与精确场景 | 必须保留的价值 / 主干缺口 | 采用范围 |
|---|---|---|---|
| T1 | v7 `ReplayAcceptanceTest`：`real dual pager preserves a boundary across delayed geometry change and inward input` | 真正进入章节边界后，迟到事实改变单位布局，边界不消失、不额外读取内容、不推进进度；一次向内输入返回合法图像。当前边界展示与迟到事实测试分别存在，缺这条交叉时序 | 加到现有 `ReaderChapterTransitionPresentationTest` 夹具。经 SharedDualPage 真实事实入口改变布局；保留有/无变化对照。淘汰旧 setSpreadPages、chapterTransitionReturnDisplayUnitId 依赖，不强求恢复已被布局变化删除的旧单位 ID |
| T2 | v12 `ReaderPageTurnIndependentTest`：`rapid requests keep the latest viewport when animation is disabled mid flight` | 先观察真实混合帧，动画中关闭开关，再连续发两个目的地；最新目的地及像素获胜，旧完成不得覆盖。main 快速请求测试没有中途关开关刺激 | 加到现有 `ReaderPageTurnPresentationTest`，复用真实像素 fixture、单/双页×LTR/RTL；预期按当前共享 owner 算，不搬旧硬编码页号 |
| T3 | 同上：`animation toggle preserves a tap destination already in flight` | 点击已产生混合动画帧后关开关，仍到达点击目的地；main 的偏好开关测试是在下一轮输入前生效 | 同 T2，不另建独立 fixture |
| T4 | 性能树 `SharedSpreadDesktopResourceWiringTest`：`mounted real neighbor demand promotes a queued same Entry when a slot becomes free` | 真实 viewport mount 把排队投机请求原位提为真实请求，Entry/registration/budget 不换、只解码一次。main 有 pipeline 排队单测与执行中 promotion wiring，但缺排队时真实 mount 接线 | 仅移入同名 main test 类及必要 helper |
| T5 | 同上：`mounted real neighbor demand promotes before its content key is registered` | 已登记需求但 content key/Entry 尚未创建时，真实 mount 的升级不能丢失，不能延期/退已扣预算；放行后一次解码 | 同 T4，预算观察适配当前 FactBudgetWindow |
| T6 | 同上：`late old publisher cleanup preserves a newer same page registration` | 新 publisher 同页登记之后，旧 finally 迟到不能删除新 registration。main 实际使用 compare-remove，现替换测试未固定这一 finally 次序 | 同 T4，保留两个可控调度器的真正生产工作，不复制旧 P5 快照设施 |
| T7 | 同上：`location change cancels active pairing publisher outside lifecycle lock` | 真正 goToPage/collector 替换触发取消；取消回调不能持 lifecycle lock，防止重入/死锁。仅“最终取消成功”不足以检查锁拓扑 | 同 T4，使用 main 现有 internal startSpreadPublisher，不恢复旧反射接口 |
| T8 | 同上：`page retry cancels active pairing publisher outside lifecycle lock` | retryPage 取消也必须在锁外；与导航是不同入口 | 同 T7 |
| T9 | 同上：`screen disposal cancels active pairing publisher outside lifecycle lock` | 无 collector 干扰时 onDispose 自身取消 publisher，回调不得持锁；避免由 collector 取消掩盖 dispose 漏项 | 同 T7 |

### 9.2 淘汰依据，不留笼统“另案复核”尾项

- **v7 产品**：main 使用有明确前/后方向身份的常驻 boundary display item，三种 viewer 及真实 ReaderContent 测试已经覆盖。旧 return 字段/动态反馈页建立第二套位置所有权；旧 showClose=false 也不符合主干瞬时错误反馈契约。采用它们会改变现有交互，不是缺失修复。旧 delayed-geometry 刺激有独立价值，所以单独取出 T1。
- **v12 产品与 Workflow**：main 已有 pageTurnAnimation 的设置、搜索、可达性、真实 pager 接线，以及 programmaticTarget/userTurnTarget/navigation intent。旧 pendingPagerPage 单个整数会损失这些协作。两个 Workflow 文件经去空白/import排序、归一类名比较，对应 main ReaderCriticalPathProductionTest/ReaderProductionTestFixture；main 还多 scene 尺寸、首帧截断、HEADER_READ 预算断言。旧文件不产生新能力。旧手动 beginGeneration 的迟到 decode 测试不采用：main 已有真实 runtime fence、lease 恰好释放及旧 generation 清理回归，旧手工推进测试接线更弱。
- **性能树产品**：旧 pipeline/sampler 缺真实前台工作分类、跨 runtime region slot 和整轮 sampling slot；旧 Model 缺完成未消费期间持有预算、精确准入/释放、defer 重试及忙态恢复，还会删除全屏接口；旧 Android adapter 缺 mutex/前台忙态/closed owner 防护；旧 budget 缺 FactBudgetWindow/rebind；旧 certification/session 缺完整 read-set、不可达入口剪枝及有界 memo。均淘汰旧残差，保留 main 后继实现。
- **性能树其余测试/工具**：旧“16页后停止”断言与当前 required 长前缀协议冲突；进度测试没有独有方法；网络/header 由当前真实 owner 和窗口契约覆盖。in-process 单页比较工具是特定外部 manifest/assets 和候选源码注入的一次性对照，旧构建修改还移除 updater 字段，不作为常驻工具采用。原始数据仍可证明历史测量，不能因此恢复旧构建配置。
- **R6 四文件**：两套成本 harness 依赖 main 已不存在的候选接口；trajectory 仅被过期 harness 消费；run.py 固定 E/CSL/D3 旧策略。main Memo/EquivalentContracts 测试已有当前依赖失效、稳定 epoch 和物理读取边界。全部淘汰，不为“也许有用”维持空消费者设施。
- **task151**：main controller 比旧版增加 openWebsite token/loopback/one-shot 保护，测试文件完全相同；HTTP/test-mode 有后继隔离和关停接线。旧 Windows 脚本按 EXE 批量杀实例，旧截图端点已撤销，provenance 也弱于 main。全部无需再移植。

### 9.3 验证状态与实施边界

本轮完成代码取舍，没有实施 T1–T9。主审复读了 T1–T3 的真实混合帧/布局刺激、T6 的双调度器旧 finally 竞争、T7–T9 的真实入口及持锁断言，并核对 main 的现有 promotion 测试、internal publisher 入口与 compare-remove。两位代理分别完成 reader 与性能/平台组的逐簇核对，未改变九处工作树。

后续若实施测试，最小范围为：T1 对应边界测试类；T2–T3 对应页翻测试类；T4–T9 精确方法及现有 executing promotion wiring/pipeline queued promotion，加修改源格式检查。不得据此次静态取舍声称新增回归已通过，也不触发全模块、正式50或新性能研究。

因此此前“九处没有内容值得使用”的说法过宽：**无旧产品需要恢复，但九个测试场景值得使用**。本节已完成具体取舍；测试移植与磁盘清理是后续执行动作，不再需要重新裁定这些旧产品实现。
