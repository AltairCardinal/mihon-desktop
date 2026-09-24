# 阅读器双页与继续阅读行为迭代 Roadmap

- 制定日期：2026-09-24
- 状态：PLANNED；尚未实施、审查或发布
- 上级路线：[Desktop 重构路线](./2026-06-30-mihon-desktop-refactor-roadmap.md)。该路线当前唯一 `active-child-plan` 指向作者页迭代且存在其他任务的未提交改动；本计划暂不切换指针。正式启动前先协调唯一活动计划，不能同时声明两个 active child。
- 既有已完成记录：[Android 双页布局与跨端显示契约](./2026-09-16-android-dual-page-layout-parity.md)保留为历史证据；本计划修正其后发现的行为缺口，不回写已完成任务的勾选状态。
- 本文是待启动的产品 child plan；进度从下文第一个未勾选的功能批次推导，不声明 `active-task`。checkbox 只在实现、必要独立审查、验证和提交全部完成后勾选。

## 1. 用户结果与现状证据

| 编号 | 用户可见问题 | 当前证据 | 目标结果 |
| --- | --- | --- | --- |
| R-01 | Android RTL 双页、奇数张纵向页且首页独占时，最后一组右页先读、左页为真正末页；看完该组仍不能自动标记已读 | `DisplayPage.Double.firstPage` 是右页；`DualPageR2LPagerViewer.onDisplayPageSelected` 只传 `firstPage`，`ReaderViewModel` 构造单页 `visiblePageIds`；共享 `ReaderProgressPolicy` 已支持取可见集合的最后一页 | 稳定显示包含末页的双页组后，进度记录真正末页并自动标已读；布局重排、预加载及邻章不提前标记 |
| R-02 | 书库/漫画详情点“继续阅读”可能跳回已读的同步恢复章节 | Desktop 两入口和当前 Android 的书库、详情入口均让 `resumePosition` 章节优先于 `resolveReaderEntry`；Android `ReaderViewModel` 的 `resume=true` 还可能再次改写入口章节 | 先选择筛选后故事顺序最早的未读章节，再在该章节内恢复页进度；其他章节的旧同步位置不能覆盖章节选择 |
| R-03 | Android RTL 双页、偶数张纵向页且首页独占时，末页单独成组却显示在屏幕中间；边缘页方向还未对称 | Android `DisplayPage.Single` 除封面外使用整个视口并居中；Android 和 Desktop 首页均硬编码左槽，Desktop 已有末尾单页 RTL 右/LTR 左的规则 | RTL：首页左、独占末页右；LTR：首页右、独占末页左；中间双页依方向保持阅读顺序 |

R-01 与 R-03 是**不同页数、不同章节的两个复现用例**。在全纵向、无手动改配对的基线下：5 张页分组 `[0] [1,2] [3,4]`，末页在最后一组左侧；4 张页分组 `[0] [1,2] [3]`，末页是独占单页。宽图、强制单页、手动配对会改变组边界，验收仍以实际可见逻辑页为准，不用页数奇偶替代视口事实。

阅读器架构权威是[共享核心说明](../architecture/reader-shared-core.md)和[权威分类](../architecture/reader-authority.md)：平台 presentation 报告稳定视口与所有可见 `PageId`；KMP `ReaderProgressPolicy` 决定 `lastPageRead`、完成和幂等 effect。配对与物理槽属于 presentation，不能在 tracker 或数据库层靠“章节页数奇偶”补丁修复。

## 2. 冻结的行为契约与范围

1. **进度**：只有当前激活章节的真实 settled 视口提交进度。双页同时显示两张逻辑页时上报两张；共享策略取故事顺序较后的可见页。用于 UI 导航、预加载和恢复的首张/anchor 可以仍是双页右页或左页，不能混作进度页。切换显示模式、异步图片尺寸造成的重配对及初始布局恢复不生成新进度；真正翻页后才生成。错误页、过期 generation 和未激活邻章继续遵循现有门禁。
2. **继续阅读**：书库卡片、漫画详情的继续按钮在各平台先按既有筛选与故事顺序选最早未读章节，随后取该章节 `lastPageRead`；若同步恢复位置恰好指向所选章节，可使用该页和对应同步 snapshot。若同步位置指向别的章节，不传其 snapshot，也不改变所选章节。全部已读或无可读章节时保持现有无目标反馈；显式点选章节、历史记录的显式续读、跨设备同步数据本身不在本轮改变。进入 Reader 后不得被 `resume=true` 再次改写到其他章节。
3. **物理槽**：双页模式下，纵向首页独占时 RTL 左/LTR 右；纵向末页独占且不是首页时 RTL 右/LTR 左。单页章节同时是首页和末页，首页规则优先；真正横向封面/跨页图保持完整宽图呈现，不裁为半页。中间独占页沿已有方向和配对策略处理。本轮不调整相邻纵向页的分组顺序，不为末页位置额外拆开双页组。
4. **平台边界**：Desktop 已有 RTL/LTR 双页；Android 当前只有 `DUAL_PAGE_R2L`，低三位阅读模式值 0–7 已占用。本轮修复 Android 现有 RTL 双页，并用 Desktop 的 LTR 双页验证反向规则；共享纯排版契约必须表达两种方向。**不新增 Android LTR 双页模式、设置入口或持久化值**。如果用户要求 Android 本轮也提供 LTR 双页入口，先单独评估模式值扩容、旧值读取/降级、菜单和导航，再调整本计划预算与任务，不能把它当作小型槽位修复。
5. 不新增进度表/schema、同步格式、第二套进度 policy、额外网络请求或阅读模式默认值。保留书架、历史、下载和显式章节入口的其他既有行为。

## 3. 功能批次与依赖

| 批次 | 独立交付与入口 | 依赖 | 预计实施工时 |
| --- | --- | --- | --- |
| RD-01 | Android 阅读器双页最后一组能正确自动标已读 | 现有共享进度 contract | 2–4 小时 |
| RD-02 | 双端已支持的双页方向中，边缘单页进入正确物理半屏 | 现有 KMP 配对；可与 RD-01 在独立文件边界实施，但重型 Gradle 串行 | 3–5 小时 |
| RD-03 | Android/Desktop 书库和详情继续按钮选择最早未读章节并恢复该章页码 | 现有 `resolveReaderEntry`、筛选和同步读取；与 RD-01/02 无数据依赖 | 4–6 小时 |
| RD-04 | 双端集成、正式产物与运行验收 | RD-01～03 均完成、审查通过 | 2–4 小时，另计平台工具链等待 |

总估算约 11–19 工程小时。Android 新增 LTR 双页若被明确纳入，将另增约 4–8 小时及阅读模式持久化兼容审查；不在上述默认估算内。上述批次按用户行为拆分，不按文件/测试类拆微任务。某批超过 8 文件或 400 行时记录真实 View/Compose fixture、共享契约、双端接线为何必须一并提交，不机械拆开不可验收的链路。

### RD-01 · 双页可见页进度与自动已读

- [ ] Android 双页报告真实可见页集合，并在共享 progress effect 中完成末页判定。

**实现边界**：复用 `ReaderProgressPolicy` 与 `ReaderSessionCore`。由 `DisplayPage.Double` 生产两个稳定 `ReaderPageId`，`DisplayPage.Single` 生产一个；`ReaderViewModel` 以当前激活章节/settlement sequence 接收整组，`firstPage` 继续只作显示锚点。对接既有布局重排的 `layoutOnly` 保护及 Android `RecordReadingProgress` 事务；不在 holder 或 Activity 新增 `chapter.read = true` 特例。

**红绿重构**：先在现有 Android 真实 viewer→ViewModel→共享 policy→记录仓库的测试中让 5 张页 `[3,4]` 失败，要求 `lastPageRead=4`、read=true；对照 `[1,2]` 尚未完成。再覆盖单页、宽图单逻辑页、重配对不记进度、快速离开/邻章/重复回调、已读状态不倒退。测试必须触达 production 回调和真实持久化路径，不能只复制集合构造。最小修复、清理后重复 focused；相关 Android/共享契约和格式检查通过。

**用户验收**：Android 打开 5 张纵向页章节 → 双页模式翻到最后一组并停稳 → 返回章节列表显示已读；停在前一组时仍未读。记录真实章节/测试 fixture、页码和结果。

### RD-02 · 首页与末尾独占页按方向入槽

- [ ] 已支持的双页方向按 RTL 左/右、LTR 右/左呈现首页/独占末页；保持中间配对和宽图行为。

**实现边界**：优先复用 KMP `ReaderPagePairing` 与 Desktop `DualPagedPresentation` 现有分组；提取或增加仅依赖方向、组位置、页类型的纯物理槽决策，供 Android holder/adapter 与 Desktop presentation 消费。Android 在单页 holder 中建立固定左右半屏槽与空槽，不把纵向末页继续作为全屏 `CENTER`；保持现有图片 fit、对齐、缩放/触摸及错误重试。Desktop 修改目前无条件首页左槽的规则。横向封面全宽、条漫和普通单页模式不受影响。

**红绿重构**：先给 KMP 槽位决策写 1/4/5 张页与 RTL/LTR 行为测试；Android 使用真实 `DualPagerPageHolder` mounted 测量测试覆盖 4 张 RTL 的末页右半屏、首页左半屏与宽图全宽；Desktop 使用 production `DualPagedPresentation`/离屏渲染覆盖 4 张 RTL、4 张 LTR、5 张双页末组及真实边界尺寸。对手动调整配对、异步识别宽图、窗口尺寸切换验证显示重排不误记进度、不丢当前页；绿后重构并复验。Android LTR 只测共享规则，不伪称已有可操作入口。

**用户验收**：Android RTL 双页打开 4 张纵向页章节 → 首页在左半屏、最后独占页在右半屏；Desktop RTL 同样检查，切为 LTR → 首页在右半屏、最后独占页在左半屏。5 张页仍以两张末组按阅读顺序显示，不产生多余翻页。

### RD-03 · 最早未读章节与章内恢复

- [ ] 双端书库/详情“继续阅读”先选最早未读章节，再恢复该章节的页码；同步位置不跨章抢占。

**实现边界**：复用 `resolveReaderEntry`、Android 原有 `applyFilters`、Desktop 现有排序/外部章节排除及 `resolveReaderChapterEntryPage`。把“章节选择”和“该章节页码/同步 snapshot”明确分两步；必要时扩展共享纯决策输入，Android/Desktop adapter 分别提供各自筛选后的候选，禁止另造平台特有排序。修复 Android `ReaderViewModel` 在普通继续入口 `resume=true` 下再次切换章节的链路；显式历史/恢复仍遵守自身入口语义。同步仓库只读，写入协议不变。

**红绿重构**：先构造“较早未读章节 A 有页进度、较晚已读章节 B 是同步 resume”的真实入口失败测试；要求按钮进入 A 的进度页。覆盖同步 resume=A、无同步、全部已读、排序升降、过滤/外部章节、显式点击已读章节、历史入口、Reader 初始化时二次改写。共享章节选择契约、Android Activity/ViewModel 与 Desktop ScreenModel/UI wiring 测试均须能在生产接线损坏时失败；导航或 DI 改动补实例化/类型/解析测试。先红、最小实现转绿、重构后再绿。

**用户验收**：把第 1 章保持未读并读至中途，把第 2 章标已读且留有旧同步位置 → 从双端书库卡片和漫画详情点“继续阅读”均进入第 1 章的进度页；显式点击第 2 章仍按既有重读规则进入。全部已读时给现有反馈，不偷偷打开旧同步章节。

### RD-04 · 集成与正式运行验收

- [ ] RD-01～03 的双端入口、进度事务、物理槽、正式产物及运行证据全部通过，按项目规则提交并完成迭代报告。

**验证顺序**：每批只跑受影响 focused 红绿测试，批次完成跑相关共享、Android、Desktop 集成与 `spotlessCheck`；阶段收口串行执行一次完整 Android/Desktop JVM 测试和格式检查。使用 `scripts/gradle-coordinator.py run` 协调同一 worktree 的重型 Gradle；超时先查状态，不启动第二份。若已有同一未提交 diff 的等价完整 Desktop 测试证据，Windows 用 `scripts/build-desktop.sh build-only`；否则用正式构建脚本的常规模式，不直接 Gradle 部署。检查日志 `Final unpacked EXE:` 指向的正式 `artifacts/windows` 文件确实存在，并用该发布产物的 production Reader/Test Mode 链路验收。Android 使用仓库现有 fork release/signing 流程构建签名包，确认包名、版本、证书与安装包一致，在可用实机/模拟器上原位安装并运行上述两个章节用例；不清数据。macOS 按最终收口要求先检查工具链并尝试构建运行，可用性不足则记录具体阻塞，不用 Windows 结果代称。

**证据与边界**：保存红/绿命令、相关与全量结果、独立审查结论、发布产物链接及运行结果。历史/显式恢复、宽图、Webtoon、单页、下载和同步写入回归；`finalParityAudit` 只在对应 manifest 状态/证据需要变更或最终门禁要求时运行，不在每个批次重复。文档同步更新共享显示/进度契约和当前限制；manifest 只由真实能力和证据推进，不能从 tracker/report 反向覆盖。

## 4. 执行组织、风险和停止条件

- 本次仅制定一份 roadmap，不实施代码。正式实施前核对 HEAD、工作树与当前 active child，隔离作者页及其他会话未提交改动；必要时用独立 worktree。首个功能簇 RD-01 交实施子代理承担主要 production 与测试，主代理负责接口、整合和最终验收；相近后续工作优先复用原代理。最多 2 名子代理，独立审查 1 轮，必要修复复审 1 轮。RD-01～03 无下游生产接口依赖，可在稳定里程碑合并审查；若实施出现新的高风险跨模块协议/持久化变更，先审查该接口再让下游消费。
- 审查重点：双页可见集合没有被 UI anchor 缩成一页；布局回调不会产生虚假已读；同步 resume 不跨章节改变普通继续入口；历史/显式恢复仍可用；物理槽变化不改变配对、翻页、保存页码或错误重试。实现者不自审目标部分；阻塞问题交原实现者修复，复审仅核对失败项和影响路径。
- 已知旧测试可能把“同步位置优先”和“封面两方向均在左侧”写成预期。先用用户确认的新行为写失败测试，再更新这些旧断言；不能仅修改测试让现象消失。若真实发布版与当前源码在 RD-01 进度行为上不一致，先记录版本、调用链和复现证据，再确定是否另有桌面缺陷。
- 如要新增 Android LTR 双页，当前阅读模式低三位 0–7 已占满，不能复用值 7（AUTO）或覆盖旧模式。需先制定兼容编码、设置入口和旧版降级方案，并单独预算实现/审查；当前计划可独立完成既有模式缺陷。
- 若用户另要求在线真实来源、其他设备同步或额外发布渠道验收，先给出环境和成本；本计划不擅自扩大网络、账号、数据库或发布范围。没有可用 Android 设备时仍可完成正式 APK 与 mounted 测试，但设备运行验收保持未完成，不勾选 RD-04。
