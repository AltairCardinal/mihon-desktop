# 同步首次配置：专用仓库最小权限设计

日期：2026-09-19。状态：关键技术预研 Conditional GO；尚未实施和完成真实 GitHub 验收。当前 `mihon-desktop` 仍是 private GitHub App，无法向其他账号分发。

技术预研：[可行性报告](2026-09-19-sync-least-privilege-feasibility.md)。实施计划：[本迭代 roadmap](roadmap/2026-09-19-sync-least-privilege-onboarding-roadmap.md)。前序：[自动连接与可选密码设计](2026-09-18-sync-onboarding-password-design.md)及[前序 roadmap](roadmap/2026-09-18-sync-onboarding-password-roadmap.md)。

## 1. 目的、替代关系与边界

首次使用者从始至终只需将 `mihon-sync` 专用私有仓库授予公共 GitHub App `mihon-desktop`，无需自己注册 App，无需授予 All repositories，也无需先授权无关仓库。Android 与 Desktop 使用同一流程。

本设计替代前序计划中的“先安装并具备 Administration 写权限，再由客户端调用 API 自动建库”及“不接管任何用户创建空库”规则。新流程是：用户在 GitHub 确认创建专用空私库 → 仅安装到该库 → Mihon 询问可选密码 → 初始化空间并自动合并。建库是 GitHub 网页上的用户动作，初始化是 Mihon 的产品动作，两者在界面上分别说明。

保留前序 v2 空间格式、可选密码、已有空间自动加入、密码错误拒绝、初始合并、日常同步和本地数据保留语义。已成功建立的 v2 空间继续工作；“无需兼容”指旧恢复资料/旧空间格式，不允许借此破坏当前 v2 连接。旧格式仍不迁移、不覆盖。

不做：组织账号、任意仓库/分支编辑器、其他同步服务、服务端代理、App 私钥分发、密码找回/改密、重新设计同步队列、远端自动删除或改名。用户仓库和 App 注册配置的真实变更必须另有明确授权，本文不执行这些变更。

## 2. 平台事实与证据边界

核对日期为 2026-09-19：

- GitHub 区分用户授权与 App 安装；安装可以选择 Only select repositories。用户设备登录成功并不证明已经安装。[官方安装说明](https://docs.github.com/en/apps/using-github-apps/installing-a-github-app-from-a-third-party)
- GitHub 建库网页支持 `name`、`owner`、`visibility` 等预填参数；无效参数可能被忽略，因此必须在应用内复核最终私有性和所有者。[官方建库说明](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-new-repository#creating-a-new-repository-from-a-url-query)
- API 创建个人仓库支持 GitHub App 用户令牌，并要求 Administration 写权限；本轮删除对此 API 的依赖。[个人仓库 API](https://docs.github.com/en/rest/repos/repos#create-a-repository-for-the-authenticated-user)
- 仓库文件写入及 Git 引用写入使用 Contents 写权限；向空 Git 仓库直接创建引用有平台限制，须验证既有 Contents bootstrap 路径。[文件 API](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents)、[引用 API](https://docs.github.com/en/rest/git/refs#create-a-reference)

此前对“安装前绝不可能自动建库”的口头结论缺少隔离账号实测，不作为本设计前提。零选中仓库安装、未安装令牌建库均不是本轮依赖；不为了验证这些替代路线扩大范围。最小权限真实闭环仍是发布门槛，MockWebServer 不能替代。

## 3. 权限契约与分发

| 项目 | 目标 |
| --- | --- |
| App | 将当前 private 的 `mihon-desktop` 改为 public 后，由所有用户安装同一个 App；不要求 Marketplace |
| Repository permissions | Contents: Read and write；Metadata: Read-only |
| Administration | No access；生产初始化及日常交换均不得依赖 |
| 其他权限 | 不增加账户、组织、Actions、Workflows 等权限 |
| 安装范围 | 引导 Only select repositories，仅勾选本人 `mihon-sync` |
| 客户端凭据 | 复用设备授权及系统安全存储，不分发 App 私钥/client secret，不手填 PAT |

用户已有 All repositories 安装时，不在应用中偷偷改动授权；显示“当前授权范围包含全部仓库，可在 GitHub 收窄至 mihon-sync”的提示和管理入口。仍可连接目标空间，客户端不读取无关仓库正文。所选范围包含其他仓库时同样提示收窄，不能声称客户端已替用户限制服务端权限。

上线顺序：先完成不依赖 Administration 的实现、审查和受控运行验收，再由 App 管理者按明确授权降低注册权限。降低权限会影响仍要求 Administration 的旧客户端，须先提供更新包和升级说明；不能将旧客户端报错说成用户需要再次批准。真实权限降低后的复验未完成前，最小权限发布验收不通过。

## 4. 用户操作与界面状态

入口沿用书架顶栏“同步”和共享设置，不新增独立向导窗口体系。

| 状态 | 页面说明及动作 | 下一步 |
| --- | --- | --- |
| 未登录 | “连接 GitHub” | 设备授权完成后检查个人账号与安装 |
| 未安装 | “尚未安装 Mihon GitHub App”及三步说明 | “创建专用私有仓库”“安装并授权专用仓库”“我已完成，重新检查” |
| 已安装但看不到目标 | “尚未能访问 mihon-sync，可能尚未创建或未授权” | 同上，安装按钮优先进入既有安装配置 |
| 可初始化空库 | 显示账号/仓库及单个可选密码框 | “不设置密码并开始同步”或“设置密码并开始同步” |
| 已有无密码 v2 | 显示连接/合并进度 | 自动合并，无建库或密码确认 |
| 已有密码 v2 | 输入密码，支持显隐 | 正确后合并；错误留在原页，不写远端 |
| 初始化中 | “正在准备同步空间” | 成功后自动合并，可关闭面板 |
| 旧本机连接 | “此设备保留旧版同步连接，请先断开后重新配置” | 直接提供“断开旧连接”确认入口 |
| 旧远端/未知格式 | “此仓库中的同步格式不受支持，数据已保留” | 查看仓库、重新检查、返回；不自动覆盖 |
| 普通非空仓库 | “该仓库已有文件，无法作为新的空同步空间” | 查看仓库及处理说明，不提供一键清空 |

建库按钮使用固定 HTTPS GitHub 路由，通过现有 URL 构造器编码参数，例如 `https://github.com/new?name=mihon-sync&visibility=private&owner=<已核验登录名>`。页面提示：所有者选本人，保持 Private，不添加 README、.gitignore、License，不使用模板。URL 不携带令牌、密码、密钥或本地任务标识。浏览器可能登录不同账户，应用返回后必须重新核对稳定账号 ID；不信任预填值和回调。

安装按钮初次使用 `https://github.com/apps/mihon-desktop/installations/new`，说明选择 Only select repositories → mihon-sync。既有安装使用经过 HTTPS/GitHub origin 校验的官方安装配置地址；不得把任意 API 字符串交给浏览器。后续复核请求回到同一 production 客户端。按“我已完成”触发确定性检查；从浏览器返回可做一次有界检查，不靠无限轮询，不假定所有平台都收到回调。

提交可选密码同时表示用户明确同意初始化页面展示的专用空库，不增加额外合并确认。未填写密码时说明“仓库为私有，同步内容不额外加密”；已有密码空间禁止用空密码跳过。沿用既有密码字符/长度/派生参数契约和显隐行为。关闭面板清除未提交密码；提交后的安全材料仅存现有系统安全存储。

仅返回 404 不能证明私库不存在。页面同时提供创建和授权路径，GitHub 建库页的同名冲突由用户处理；应用不换名自动建第二个空间。README 文案无论是否等于历史默认文字，均不构成空库或所有权证明。

## 5. 发现与验证契约

复用 `GitHubSyncSpaceClient`、`GitHubPrivateRepositorySelector` 和 `SyncRuntime` 的 production HTTP/授权链，移除 Administration 必须为 write 的检查、`POST /user/repos` 及仓库 description 创建标记依赖。保持合法分页、origin 约束、私有性、所有者 ID 和用户实际写权限检查。

建议在现有 sealed result 上表达下列语义，名称以实施签名为准：

| 结果 | 判据与处理 |
| --- | --- |
| NeedsInstallation | 身份成功且完整安装列表中无本 App 的个人安装；展示安装向导 |
| NeedsRepositoryAccess | 安装有效，固定名仓库未出现在可访问范围/读取为 404；不能声明不存在 |
| NeedsContentsPermission / InstallationSuspended | 缺 Contents 写权限或安装暂停；提供准确安装管理路径 |
| EmptyRepository | 已核实固定名、本人、私有、可写、不可变仓库 ID，且取得一致的无提交/无引用证据 |
| Found | 已授权目标的同步分支存在完整有效 v2 descriptor；进入加入流程 |
| ResumeInitialization | 本机持久意图与远端本次初始化证据匹配；继续相同材料 |
| Occupied / UnsupportedRemoteFormat | 普通非空库和旧/未知协议分别反馈，均不写入 |
| InitializationUnconfirmed | 中断材料不匹配、并发尚未完成或无法证明归属；允许重查，不重新初始化 |
| Reauthorize / RateLimited / Retryable / Malformed | 401、权限403、限流403/429、5xx/网络、畸形返回分别处理 |

新建入口只接受固定名 `mihon-sync`。已连接 v2 空间沿用绑定仓库 ID 和路径；既有已授权有效 v2 候选/多候选语义保留，但只读取必要同步标识，不为了接入空库新增扫描普通业务内容。改名后的旧格式空间不应阻断一个已经明确选中的固定名空目标；未知仓库不因包含 `.mihon-sync` 目录就被应用接管。

空库判定不得仅使用 `size == 0`、README 不存在、默认分支 404 或某次 409。需组合元数据、完整引用/分支查询以及 GitHub 空库响应证据；任何已有提交/引用（包括只剩空树、其他分支或标签）都不是新空库。分页截断、权限失败、畸形响应、API 不一致均停止并允许重试。实施批次必须用真实空库响应确认判定组合，不能把所有 409 当空库。

## 6. 初始化、并发与持久化

改造现有 `SyncOnboarding`、`SyncSetupStorage`、`GitHubSyncTransport`，继续使用 v2 descriptor、密码包装、Git 非强制更新和基线合并。替代旧流程“由应用创建仓库”的所有权证明，建立“已核实目标 + 用户初始化意图 + 远端初始化证据”。不得只删除旧 guard 后调用 initialize。

1. 显示密码页前得到核验结果；用户提交时重新核对账号 ID、仓库 ID、本人所有、私有、可写和空状态。以实际 repository ID 绑定任务，同名删除重建必须使旧意图失效。
2. 首次远端写入前持久化初始化意图：账号/仓库 ID、固定分支、attempt ID、v2 材料、所处阶段及已确认的远端提交证据；不保存原始密码。不沿用 `submitted=true` 表示旧建库 POST 已发出的语义。
3. 复用 Contents API 的首次 bootstrap 能力，创建文件请求不得携带替换已有文件的 SHA。bootstrap 需具有可验证的任务归属；不得依靠公开 description 或固定字符串 alone 认领其他初始化。新 bootstrap 元数据不改变已完成 v2 的 descriptor/交换格式。
4. 在进入下一阶段前重新读取 bootstrap 精确内容和树、账号/仓库 ID；只允许本次已知内容。同步分支首次发布须保持完整 descriptor 与初始索引在同一提交中，引用创建或更新保持 create-only/非强制语义。默认分支名取 GitHub 元数据，不假定为 main，也不修改仓库设置。
5. 超时、409/422、取消或进程中断后先读远端事实。相同意图继续；其他端已发布有效空间则丢弃未使用材料，转入赢家空间的密码/无密码加入流程。不得沿用输家的密码或覆盖赢家 descriptor。
6. 若只有其他端的未完成 bootstrap，显示“另一设备正在准备同步空间，请稍后重试”；不以超时自动夺取。无法证明归属时显示可恢复错误并保留数据，不反复写入。权限撤销后暂停，恢复后再次核验目标身份。
7. 完整空间读取及校验成功后才绑定本机并启动初始合并；继续覆盖“绑定写入成功但进度尚未保存”窗口，避免重启后误建新空间。

GitHub 不提供跨“验空—写文件—发布引用”的整体事务，不能承诺隔离任意外部并发写入。通过写前复核、不替换文件、精确树核验、非强制发布阻止覆盖；检测到其他写入立即停下，保留可能已写入的本次 bootstrap，不自动回滚用户仓库。该边界必须通过并发和超时测试审查。

旧版未完成自动建库意图只可识别并明确停止，不在升级后继续执行 POST 或重新解释为用户确认。保留安全材料及本机队列，提供放弃旧配置/重新检查入口。已完成 v2 绑定不清除；旧 v1 绑定用现有断开语义退出，书架和旧队列保留但不混入新空间。不得把历史未上传队列直接改写归属。

## 7. 架构复用与错误可观测性

| 现有模块/文件 | 本轮变更 |
| --- | --- |
| data sync/auth/GitHubSyncSpaceClient.kt | 最小权限发现结果、专用空库核验、移除自动建库 |
| data sync/runtime/SyncOnboarding.kt、SyncSetupStorage.kt | 初始化意图、恢复与接入，复用系统安全存储 |
| data sync/transport/GitHubGitDatabaseClient.kt | bootstrap 归属、空库初始化、并发收敛；保留既有交换 |
| data sync/runtime/SyncPanelController.kt、SyncPanel.kt | 向导状态、准确错误、浏览器返回及旧连接退出 |
| presentation-sync/SyncPanelContent.kt、i18n | 双端共享步骤、动作与文案 |
| Android/Desktop sync adapters | 沿用浏览器/生命周期/安全存储接线，仅补必要差异 |

不另建同步 manager、网络客户端或数据库镜像。增加安全诊断原因码，区分未安装、缺 Contents、不可见、初始化冲突与本机旧绑定；日志只含阶段、原因码、HTTP 状态和必要 request ID，不记录令牌、密码、密钥、私库正文。面向用户使用可操作说明，不要求用户理解 Git 对象。

## 8. 验收与发布边界

共享契约在 JVM/Android 执行真实 production 实现；HTTP 用 MockWebServer，UI 用真实 controller 和共享 Compose，双端补原生浏览器/返回/重启验收。不得只验证字符串出现或 mock parser。

必须覆盖：从零开始且仅选专用仓库、安装缺失/暂停/缺 Contents、无 Administration 的成功闭环、所有空库反例、README 拒绝、两种密码模式、并发不同密码、所有持久阶段中断、账号与仓库替换、权限/私有性撤销、旧绑定退出、已有 v2 回归。验证全链路不再请求 POST /user/repos 或修改仓库设置，并验证生产 wiring 共用 HTTP 客户端。

真实 GitHub 验收使用明确获准的隔离个人账号、专用私库和合成书架；权限为 Contents 写、Metadata 读，安装选择仅该仓库。从用户网页建库到双端交换、重启恢复全程执行。无法取得该证据则标记外部验收未完成，不宣称最小权限可发布。不自动删除验收库。

Android ARM64 必须沿用 fork 包名/发布证书且版本码高于已分发版本；验证保留书架覆盖安装。Windows/macOS 使用规定脚本和正式运行时；Windows 报告引用日志 `Final unpacked EXE:`。前序 macOS Keychain、真实 GitHub、低端真机性能未完成项继续保留边界，可复用仍适用证据但不得以本次文档完成结案。
