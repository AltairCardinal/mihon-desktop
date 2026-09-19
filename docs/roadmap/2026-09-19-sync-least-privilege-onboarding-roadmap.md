# 专用仓库最小权限同步配置 Roadmap

日期：2026-09-19。状态：关键技术预研 Conditional GO，待实施。当前 App 为 private，真实 Contents-only 闭环未验收；不代表功能、App 权限或发布产物已经改变。

设计权威：[最小权限配置设计](../2026-09-19-sync-least-privilege-onboarding-design.md)。预研证据：[关键技术可行性](../2026-09-19-sync-least-privilege-feasibility.md)。历史依据：[前序密码配置 roadmap](2026-09-18-sync-onboarding-password-roadmap.md)。本计划为产品 child plan，从第一个未勾选项推导进度，不声明 active-task，不切换其他专项的父计划 active-child-plan。

## 1. 目标与范围

用户在 GitHub 确认创建本人专用空私库 `mihon-sync`，安装公共 App 时仅选择该库；Mihon 使用 Contents 写和 Metadata 读权限，询问可选密码并初始化后自动合并。移除 API 自动建库及 Administration 要求。沿用既有 v2 格式、账号隔离、持久化、安全存储、双端共享视图和同步交换。

不是简单移除权限判断：当前实现拒绝用户创建的空库，并以创建标记证明初始化归属，必须一并修改。README 不作为空库；不接管未知非空仓库，不兼容 v1，不清除本地书架。排除组织账号、服务端代理、其他同步后端、改密、无关作者功能与全新原型系统。

## 2. 任务与依赖

- [x] M1：完成最小权限发现与专用空库初始化的共享生产闭环、安全审查。
- [x] M2：完成双端引导、准确错误和旧配置退出的产品接线。
- [ ] M3：完成真实最小权限联调、平台回归与可覆盖升级的分发验收。

依赖严格为 M1 → M2 → M3。M1 包含相互依赖的发现、初始化意图及 transport，作为一个可独立集成验收批次，不按文件拆散。M2 消费已审查接口，不提前复制一套模拟流程。

### M1：最小权限发现和安全初始化

**前置**：阅读设计第 3、5、6 节；核对当前 HEAD 和未提交改动，确认 production HTTP 注入、空间 v2 格式、现有初始化及存储签名。实现前先用官方说明和获准的隔离环境冻结无提交仓库的实际 GET/409/404 响应判据；若当前无真实环境，保留真实验证门槛，不将 fixtures 称为线上证据。

**修改范围**：data sync/auth、runtime 的 onboarding/storage、transport 及必要共享模型/契约；不重构交换算法、加密协议、作者数据或平台网络层。复用 GitHubSyncSpaceClient、GitHubPrivateRepositorySelector、SyncOnboarding、SyncSetupStorage、GitHubGitDatabaseClient。

**实现与交付**：

1. 删除 Administration 写权限硬门槛和 POST /user/repos 生产调用。未安装、安装暂停、缺 Contents、固定名不可见分别返回类型化结果；401、权限403、限流403/429、500、畸形/截断数据独立处理。
2. 核验本人私有固定名仓库和用户写权限，取得无提交/无引用证据后返回空库候选；不能仅靠 size=0、README 不存在或默认分支404。已授权但改名的旧空间不抢占明确固定名空目标；已有 v2 连接及多候选保持既有契约。
3. 实现设计第6节的用户确认意图、仓库 ID 绑定、create-only bootstrap、精确树校验、非强制发布及读取确认。参数/异常脱敏；两端争用不覆盖，赢家密码不被输家材料替换。
4. 重启按持久阶段恢复，包括 bootstrap 响应丢失、完整空间已发布、绑定已写入而 pending 未保存。旧自动建库 pending 明确终止并提供重查能力，不重放 POST；不破坏已完成 v2 绑定或旧队列。
5. 将新 pending 升级为独立版本及显式阶段，持久化 repository ID、随机 attempt nonce 和已确认 bootstrap commit/tree；bootstrap 内容绑定本次尝试。不得重解释 v2 `submitted` 布尔或继续使用固定 bootstrap 字符串证明归属。

**红绿重构与验收**：先运行失败的真实共享契约，再实现最小变更并重构。扩展 SyncSpaceDiscoveryContractTest、SyncSpaceTransportContractTest、SyncPanelStorageContract 及相关运行时测试，不在测试中复制状态机。至少覆盖：

| 类别 | 必须证据 |
| --- | --- |
| 权限与发现 | 仅 Contents/Metadata 成功；未安装、暂停、缺 Contents、403/429/500、错误账户、404不可见、分页和畸形数据 |
| 空库安全 | 真空库可初始化；README、空树历史、其他分支/标签、size=0但有提交、公开/非本人/不可写、仓库删除重建均拒绝 |
| 初始化 | 两种密码模式端到端成功；首次文件写入不替换、引用不 force；非空/不明状态零初始化写入 |
| 竞争与恢复 | 两设备同时初始化且密码不同；各写入响应丢失/重启；外部并发添加文件；其他任务 bootstrap 不被接管 |
| 本机接入 | 旧 pending 不重放、旧绑定断开不丢队列、已有 v2 继续交换、绑定/进度中断可恢复 |
| 生产链路 | 请求记录断言无 POST /user/repos、无修改仓库设置；DI 使用 production HTTP；敏感数据不进入日志 |

批次执行相关 JVM/Android 共享契约、wiring、格式检查；失败仅补受影响 focused 测试。进行安全/数据完整性独立审查，通过后 M2 才能消费接口。交付源码、测试、必要本计划证据及同批提交；未审查/未提交不勾选。

**M1 执行证据（2026-09-19）**：已移除 Administration 硬门槛和自动建库路径，完成只读安装/仓库发现、空库核验、带账户/仓库/nonce 的持久化初始化意图、create-only bootstrap、恢复及旧 pending 安全退出。共改动 12 个文件（约 2,314 行新增、486 行删除）；这些改动共同实现发现、存储和 Git 发布协议，拆开会留下不可编译或未受保护的中间状态。

- 红绿与 focused 验证：`SyncSpaceDiscoveryContractTest`、`SyncSpaceTransportContractTest`、`JvmSyncPanelStorageContractTest`、`SyncGitSafetyContractTest` 均通过；首次 ref 修复先以仅含 bootstrap 的目标树触发新断言失败，再验证修复后的 `SyncSpaceTransportContractTest` 23/23 和 Transport + Git Safety 合同 49/49 通过。`:data:testDebugUnitTest spotlessCheck`、`:data:spotlessCheck` 和 `git diff --check` 通过。
- 一次早期 `:data:jvmTest` 批次为 502 项中 501 项通过、1 项旧版空库初始化测试失败。失败断言依赖已取消的旧初始化语义；测试现验证没有 v3 用户确认意图时零写入并安全拒绝，相关 focused 测试通过。该修正后没有重跑整个 `:data:jvmTest`。
- 独立安全复审在首次 ref 发布修复后通过。首次 create-only ref 已指向同一提交中的 bootstrap、descriptor 和初始 index；响应丢失通过读回确认。仍存在 GitHub 多请求之间无法消除的 TOCTOU 窗口，代码不回滚或覆盖外部文件。
- 所有 GitHub HTTP 证据均来自本地 fixture；真实空库 GET/404/409 响应和账号联调未完成，保留为 M3 门槛。本批没有执行真实 GitHub 写入。

**停止边界**：若实现无法在 Contents-only 下完成 bootstrap，或平台响应无法支持可靠拒绝覆盖，记录具体响应/失败测试和替代方案，暂停相关写入实现；不得恢复 All repositories、Administration 或静默占用库作为兜底。

### M2：双端用户引导与恢复路径

**前置**：M1 接口、存储和初始化安全审查通过。覆盖设计第4、7节。

**修改范围**：共享 SyncPanelController/SyncPanel、presentation-sync、i18n、Android/Desktop sync adapters 和相应测试。优先共享，平台只保留浏览器、返回键/快捷键、安全存储的差异。不重做日常同步列表、调度、批量操作或其他页面。

**实现与交付**：

1. 增加“创建专用私有仓库 → 仅授权该库 → 我已完成，重新检查”引导，固定 HTTPS GitHub 路由并正确编码 owner/name/visibility；说明不要添加初始文件，不承诺 URL 参数一定生效。
2. 浏览器返回或用户重查时通过真实 controller 重验账号、安装、仓库 ID 和私有性。未安装与不可见分别反馈；不把登录授权成功等同安装完成。已有安装进入正确管理页。
3. 仅核验空库后进入可选密码页，提交动作明确初始化显示的仓库；已有无密码自动合并、有密码验证后合并。关闭清除未提交密码，后台完成不抢页，重复点击不产生并行初始化。
4. 旧本机绑定提供就地“断开旧连接”及现有确认框；解释保留书架/旧队列。普通非空库、远端旧格式、未完成初始化分别给出对应反馈及重查入口，不让用户反复重新登录。
5. 对已有全仓库/多个仓库授权提示收窄但不偷偷改授权；安装引导从不推荐 All repositories。更新现有文档必要入口说明；HTML DEMO 若仍是交互参考，应标记旧流程已过时或同步最小相关视图，不重写模型。

**红绿重构与验收**：共享 Compose + 实际 controller/存储交互测试先红后绿；验证点击、浏览器 URL、安全参数、返回重查、密码/错误状态转移，而非只查文本。Android 返回键与窄屏、Desktop 键盘/关闭与 Test Mode、断开确认取消/确认分别覆盖；新增导航或依赖解析补实例化/导航类型/DI 测试。

运行批次相关 UI、生命周期和集成/格式检查。独立审查聚焦 M1 guard 是否被 UI 绕过、账号回调隔离、初始化确认和权限说明；与 M1 使用同一合适审查者，但属于第二批审查，启动前按第3节审批预算。交付双端可执行入口、测试及一个功能提交。

**M2 执行证据（2026-09-19）**：已通过共享 `SyncPanelController`、`SyncPanelState`、Compose sync panel 和 i18n 接通“创建专用私有仓库 → 安装 Mihon GitHub App → 仅授权目标库 → 重新检查”的真实 discovery/onboarding 链路。未安装、已安装但目标库不可见、Contents 权限不足、安装暂停、不可写/不可用仓库均保留类型化反馈；只有重查确认的空 `mihon-sync` 私库才进入可选密码初始化，并在页面显示实际目标仓库。管理链接使用固定 HTTPS 路由，owner 和组织路径按 RFC3986 编码；`all` 或多个授权仓库显示范围提示，不自动收窄授权。旧绑定、无密码合并、错误密码、关闭清除未提交密码、后台完成不抢页及重复提交保护沿用既有 controller/平台 wiring。DEMO 文案已标明生产流程要求用户创建私有库并限制 App 授权，仍是本地交互模拟。

- 本批改动 12 个文件（约 828 行新增、99 行删除），这些文件共同组成安装元数据、发现结果传播、controller 状态、共享 Compose 入口、翻译和 DEMO 契约；拆分会留下未编译或未接线的中间状态。
- 红绿与 focused 验证：`SyncSpaceDiscoveryContractTest` 34/34、`SyncPanelOnboardingIntegrationTest` 2/2、Android sync panel 3/3、Desktop sync panel 与 Test Mode 生命周期通过；root `spotlessCheck`、`git diff --check` 和 DEMO `node --check` 通过。Compose 合同批次 22/23，唯一失败为 M1 HEAD 基线已复现的旧 `TextRange` selection 断言，未由 M2 引入。
- 独立 M2 复审通过，无 must-fix：未发现 UI 绕过 M1 discovery/空库 guard、stale callback 或错误账号混入；初始化确认、URL 编码、授权范围提示和旧绑定保护均符合边界。普通仓库直达入口和旧绑定错误页直达断开属于非阻塞后续建议。
- 所有 GitHub HTTP 仍来自本地 fixture；没有执行真实账号授权、权限变更、仓库写入或发布构建。DEMO 浏览器测试因当前环境没有 `playwright-core`/`PLAYWRIGHT_CORE_PATH` 未运行，保留为环境限制；真实最小权限联调继续作为 M3 门槛。

**M3 本地验收进展（2026-09-19）**：在不触碰真实 GitHub 账号、仓库或设备的前提下，已完成最终本地回归和发布候选检查。`:app-desktop:jvmTest` 与 `:app:testDebugUnitTest` 全量通过；`scripts/build-desktop.sh build-only` 通过生产扩展运行验收，生成版本 `0.11.19.49.d015682` 的 Windows 未打包 EXE，ZIP SHA-256 为 `16d8e12b7e086fd02e1abaddbb7d94f846cc38cfa24f9ad88fb595c68612d463`。使用 `scripts/android-fork-release.init.gradle` 的 `:app:assembleRelease` 通过 fork 身份、R8、遥测/更新器约束；已有签名脚本生成 `app.mihon.desktop.fork`、`0.19.4-aex.11`、versionCode 29、仅 `arm64-v8a` 的正式候选，证书和 v2/v3 签名通过，APK SHA-256 为 `8246bfb2e7d1c2864c2686cdcc1c13241e02f5cc15c32f3eee39a6da3c2e47e2`。`adb devices` 未发现真机，macOS 发布环境未执行；真实 GitHub App 权限、隔离库写入和恢复流程仍未取得授权，因此 M3 保持未勾选。

### M3：真实权限与发布验收

**前置**：M2 通过；正式发布身份和签名路径核验；实际 App 权限降低及测试账号/仓库写入取得明确授权。生产 App 尚未降低权限时可以先完成本地自动化，但不勾选真实最小权限验收。

**外部权限与真实流程**：

1. 当前公开页面已证实 App 为 private；将其改为 public 并用非所属账号核对可安装。随后降至 Contents 写、Metadata 读，移除 Administration。说明两项配置及旧客户端升级影响，不把“仅文档计划”当变更授权，也不要求 Marketplace 上架。
2. 用授权的隔离个人账号在 GitHub 网页创建固定名空私库，安装时仅勾选它；记录可脱敏复核的注册权限、安装权限及仓库选择结果。
3. 无 Administration 条件下，完成新空间无密码/有密码两个场景（使用两个隔离账号或明确批准的隔离重置，不能自动删除测试库）；第二设备加入、错误密码、真实交换及重启恢复。新手从无 App 安装开始，不能借用开发者已有全仓库授权。
4. 核对未安装、已授权但不可见及恢复授权三类反馈；日志仅保留阶段/状态，不保存 token、密码或私库正文。GitHub 创建网页和安装网页均以实际操作为证据，不以 API mock 成功代替。

**集成验证与产物**：受影响阶段完整测试集中执行，最终 Android/Desktop 全量回归一次；复用仍适用 focused 证据。重型 Gradle 通过 scripts/gradle-coordinator.py 串行协调。Windows/macOS 用 scripts/build-desktop.sh，只有符合等价全量证据条件时用 build-only。必要 Test Mode 验证真实生产 wiring。

Android 使用 fork 发布脚本与既有证书，版本码比实施时最新已分发版本更高，交付 arm64-v8a release；核验 applicationId、版本、证书、ABI、R8、SHA-256，并在真机保留数据覆盖升级。不能再次把 app.mihon 调试签名包作为 fork 升级包。Windows 交付固定 artifacts 内日志 Final unpacked EXE 指向的实际文件，macOS 交付实际验收包。

**完成标准**：逐项记录命令、结果、正式产物绝对链接、哈希和真实外部证据；不写入机密。前序 P6 的 macOS Keychain、GitHub 真联调、最低支持真机性能门槛在新结果确实覆盖时关联关闭，否则明确保持未验收。任何平台/外部门槛未通过，M3 保持未勾选，不将文档或 MockWebServer 成功描述为发布完成。

## 3. 执行预算与审批边界

本次规划只交付一份设计及本 roadmap，由主代理直接编写，一次文档一致性/链接/diff 检查后提交；不跑产品测试或构建，不启动子代理，不修改线上 App。

未来启动实施时必须再次说明实际预算：首个任务簇 M1 由实施子代理承担主要实现及验证，主代理负责接口、整合与验收；最多一个实施者及一个独立审查者，连续修复优先复用。M1/M2 因依赖与写入边界串行，不伪造并行收益。规划工作量约2–4个工作日，主要成本为生产 HTTP/持久化契约、并发故障注入、双端运行及真实 GitHub 权限验证，外部等待另计。

按仓库默认预算，独立审查1轮、修复复审1轮。由于 M1 安全协议必须在 M2 接线前通过，完整实施预计另需 M2/M3 合并审查1轮（约30–60分钟）；该额外轮次尚未批准，须在启动相关轮次前说明范围并申请，不能把本文当用户已批准扩额。全量回归仅最终1次；各批红绿按具体行为 focused 执行。必要修复复验只覆盖变更及影响路径，不扩大无关测试或交付。

线上 App 改权、隔离仓库写入、已有库删除/改名各自遵循明确授权；可先完成可审阅代码和本地验证，再请求具体外部动作。没有外部权限时继续独立实现，记录真实运行未完成，不用更大权限绕过问题。

已有未提交作者专项及其他文件保持原样。本计划不编辑并行父 roadmap 的 active-child-plan。每功能批次原则上一个包含测试、代码、必要文档及checkoff的提交；没有实现/审查/验证/提交全部完成不勾选，不产生纯状态推进提交。
