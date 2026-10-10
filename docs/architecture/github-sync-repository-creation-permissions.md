# 同步仓库创建权限与回退路径

本次修改基线：`d7edbc00f7`。Android 与 Desktop 共用同步控制器及 Compose 面板。

## 权限来源与配置

GitHub App `mihon-desktop` 应申请 Repository permissions → Repository creation → Read and write。保留既有 Contents、Metadata 和 Administration 权限；Administration 仍供恢复私有状态、取消归档等已有功能使用，本次不移除。

App 管理员在 [Permissions & events](https://github.com/settings/apps/mihon-desktop/permissions) 保存后，已有安装的账号所有者还须批准新增权限。重新完成设备码授权不等于批准安装权限。参见 [GitHub 的权限变更说明](https://docs.github.com/en/apps/maintaining-github-apps/modifying-a-github-app-registration)。

同一个 App 的注册权限对安装它的所有账号一致，但授权分别由各安装账号批准。向其他账号开放安装还要求 App 为 Public；Private App 仅能安装到所属账号。本次不改变可见性。参见 [GitHub App 可见性说明](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/making-a-github-app-public-or-private)。

创建能力取自当前生产用户访问令牌请求 `/user/installations` 返回的匹配安装：`repository_creation == write` 或 `administration == write` 均满足创建权限前提。不得用公开 App 注册权限、OAuth `scope` 或响应中的 `X-Accepted-GitHub-Permissions` 代替当前安装权限；后者表示接口要求的权限。

## 共享交互契约

- **SOURCE**：复用 `GitHubSyncSpaceClient`、`SyncPanelController`、`SyncPanelContent` 和既有外部创建 URL、手动仓库确认、固定 ID 读回流程。
- **PROJECT_POLICY**：进入创建步骤先检查权限；检查中不显示可用的应用内创建按钮。检查确认有权限后才能提议应用内创建，并在最终提交前再次检查账号、凭据及实际权限。
- 未获准或无法确认权限时，保留外部浏览器创建路径；网络失败、限流、授权失效等仍分别反馈，不把检查失败解释为已获准。浏览器创建后用户返回应用确认，应用验证仓库属于当前账号、私有、空仓库、可访问，才继续设置。
- 授权或账号变化后重新检查，旧账号或旧凭据的异步结果不得解锁新会话的创建入口。
- 权限预检不创建任何仓库。真正创建仍须用户确认，并沿用现有创建尝试、结果不确定读回和防重复提交规则。
- GitHub App 通过 Repository creation 权限创建的仓库会自动授权给该 App；沿用安装仓库列表读回，已可访问时不再添加授权。参见 [GitHub 的权限说明](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/choosing-permissions-for-a-github-app)。

## 边界

预检确认权限前提，不保证后续写入成功：GitHub 服务、账号限制、安装状态及权限可能随后改变。最终拒绝仍保留真实错误与外部创建/重新检查路径，不显示为创建成功。仓库已被删除、内容非空或身份改变时，继续使用既有同步空间恢复流程；本次不修改其数据迁移语义。

2026-10-10 首次公开 App 元数据未含 Repository creation。本会话工具没有 App 注册配置写入口或已登录浏览器，用户随后在管理页保存新增权限；再次通过生产 GitHub API 读取 `/apps/mihon-desktop`，已确认 `repository_creation: write`，原有 Administration write、Contents write、Metadata read 均保留。注册配置已核验，具体安装账号是否批准仍由应用运行时检查，不以注册配置代替。

新增权限字段 `repository_creation` 的拼写已通过上述真实响应核验，HTTP 测试覆盖其安装响应形状。尚未对真实账号创建仓库或操作实体设备同步；不把模拟 HTTP 通过描述为真实安装权限/真实 GitHub 写入验收通过。

## 本次验证与交付

13 个代码、资源及测试文件构成单一创建权限功能批次，另含本文和两端构建版本分配；变更跨解析、控制器及共享页面以保证真实调用链闭合，不拆成独立维护的权限流程。

- `creation-preflight-red-valid`：真实 Compose/controller 红测确认无权限仍显示原生入口、确认后失权仍发 POST；`creation-permission-close-red` 确认慢查询阻塞关闭。
- `creation-preflight-focused` 与 `creation-preflight-repair`：HTTP 解析、仓库管理和控制器相关组合证据；首轮 6 个旧夹具的新增权限元数据预期错误已按真实响应分别修正并补验，没有放宽权限判断。
- `creation-permission-close-green` 与 `creation-permission-final`：原生确认、失权拒绝、浏览器创建至基线同步、未知状态重新检查、异步关闭/重开及既有名称编辑绑定通过；data/presentation Kotlin 与 i18n XML 正常格式检查通过。
- `creation-permission-desktop-candidate`：首次 Desktop 门禁 3253 项执行，1 项清理临时目录失败、2 项跳过。`creation-permission-desktop-di-recheck` 中原失败用例通过，但另一项先前已通过的用例出现同类 `DirectoryNotEmptyException`。组合结果未出现业务断言失败；临时目录清理仍存在不稳定性，不报告重新全量全绿，不扩展到无关运行时重构。
- `creation-permission-desktop-build`：复用上述同源组合证据，由标准脚本 `build-only` 完成 Windows 构建、运行版本及生产扩展安装链路验收。正式产物 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.88.d7edbc0-unpacked/Mihon Desktop.exe`。
- `android-candidate`：标准 `scripts/build-android.py candidate --offline` 完成 Release、R8/资源压缩、原证书签名及身份校验。APK 为 `app/artifacts/android/0.19.4-aex.50-vc68-d7edbc00f7-release/Mihon-Fork-0.19.4-aex.50-vc68-release-universal.apk`，SHA-256 `f594a7a0f666869a79dfb9c23e2072905ce13d9d34bc8ee68d1cb1dfc1114b11`。未隐式安装、上传或操作真实同步。

## 安装与授权失败页的交互规则（上一轮记录）

此节保留 `2c89d30eac` 基线迭代的设计与证据。首次连接的当前顺序以文末“先准备空间，再安装与授权”为准；以下“安装为主操作”现在仅适用于目标空间已准备的安装步骤。

共享恢复决策以最新已观察到的安装、授权、仓库和网络阻塞为先；未完成的空间设置/切换记录及旧初始化回执不覆盖当前阻塞。仅在没有当前阻塞时推荐接续设置，按钮明确表示继续设置，而非笼统的重试。

缺少安装时显示已确认的 GitHub 账号、尚未安装的同步应用和待检查的同步空间，唯一主操作为安装并授权；仓库状态未知时只提供“还没有同步仓库？创建同步空间”次操作，不声称仓库缺失。设置说明默认折叠，诊断可按需进入；其他安装/授权异常保留折叠的其他解决方式，不叠加通用重试与更换提示。

Android/Desktop 共享 Compose LocalWindowInfo 的窗口焦点事实：成功打开 GitHub 后，观察一次实际失焦再聚焦，自动执行一次只读检查；同一次外部动作不因后续切窗重复请求。再次打开 GitHub 才重新允许自动检查。返回不表示安装或授权成功；检查仍无安装时推荐继续安装，网络/权限/限流请求失败保留外部步骤记录并呈现真实阻塞。自动返回检查不恢复尚未完成的远端创建或初始化写入，不越过用户确认；原设置和连接记录保留，查到有效仓库后进入选择/密码等既有步骤。

2026-10-10 本轮基线为 `2c89d30eac`。普通设置与切换设置复用已观察的账号/安装事实；完成安装只清除已确认的外部步骤，不把原同步空间标为已经恢复。设置页携带旧暂停任务时，先解决当前安装阻塞；主界面的暂停/恢复语义保留。

本轮证据与交付：

- 有效行为红：`setup-redesign-red`、`setup-redesign-review-red`、`setup-redesign-latest-observation-red`、`setup-redesign-switch-official-red`、`setup-redesign-paused-old-red`，分别确认未完成设置、旧初始化、历史 HTTP 失败、切换发现分支和旧暂停任务造成错误推荐。
- 组合绿证据：`setup-redesign-latest-observation-green` 28 项；最后事实记录及返回 wiring 改动由 `setup-redesign-switch-official-green` 7 项补验，旧暂停优先级由 `setup-redesign-paused-old-final` 3 项补验。执行真实 HTTP/controller/Compose 点击，覆盖只读返回、只检查一次、检查未完成不误认批准、慢请求关闭重开、保留原绑定、继续安装/空间设置与既有暂停交互。正常 data/presentation/i18n 格式及 `git diff --check` 通过，未重复全量。
- 主代理独立检查实际 Compose 离屏中文首屏：Android 400×800、Desktop 560×680，确认一个安装主按钮、状态卡、未知空间待检查及教程默认收起。图片在忽略的 `presentation-sync/build/sync-visual/`，不作为实体设备或系统浏览器返回的业务验收。
- `android-candidate` 正式构建并核验 aex.51 / versionCode 69，原 fork 证书连续、不可调试、R8/资源压缩。APK：`app/artifacts/android/0.19.4-aex.51-vc69-2c89d30eac-release/Mihon-Fork-0.19.4-aex.51-vc69-release-universal.apk`，SHA-256 `4f993452d39518ce901901a594b6cc525b3270b248e8330bf56982b5dda3f57e`。用户明确授权后，通过独立安装命令安装到当前连接的华为 PCE-W30，并核对安装身份；未代操作真实同步。
- `setup-redesign-desktop-candidate` 首次完整 Desktop 门禁执行 3253 项、2 项失败、2 项跳过。两项均为 DI 用例结束后的临时目录删除 `DirectoryNotEmptyException`，首次失败 XML 保存在忽略的协调器目录。`setup-redesign-desktop-cleanup-recheck` 只补验这两项并通过，组合结果不等于最终重新全量全绿。
- `setup-redesign-desktop-build` 复用同源组合证据，由标准 `build-only` 脚本完成正式 Windows 构建及运行版本/生产扩展安装链路验收。正式 EXE：`app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.90.2c89d30-unpacked/Mihon Desktop.exe`。Android 候选先完成并安装，随后分配 Desktop 构建号；每个平台按自身构建时的冻结输入记录，不将两个构建时点描述为同一生产输入哈希。

## 先准备空间，再安装与授权（2026-10-10）

首次连接发现尚未安装 App 且没有已准备目标时，先复用仓库准备页：填写仓库名称，在浏览器创建私有空仓库，或选用该名称的已有仓库。创建页返回只保留草稿，不请求私有仓库、不标记创建成功；用户点击“我已创建，继续”或“使用已有仓库”后，才进入安装/授权此仓库步骤。目标仅记作用户声明“已准备、待验证”，不生成连接、密码、actor 或原生创建提案。已有 App 且实际具备创建权限时仍使用原生创建确认；已有有效空间/授权不要求重复创建或安装。

安装/授权返回后的自动检查使用本次选择的名称，保留账号、实际安装权限、仓库列表与固定 ID 校验；403、限流或网络失败不当作仓库不存在。只读验证到私有空仓库后，复用手动选取的固定 ID continuation（submitted=true），禁用自动访问授权写入；自定义名称通过同一扫描/空仓库证明与既有初始化保护。最终密码/初始化及切换确认仍使用原路径，旧连接在新目标确认前保持有效。

草稿和声明以 unbound recovery 的可选字段持久化，兼容旧记录；无绑定时按凭据 revision 恢复。有旧绑定的新空间切换另外绑定 active switch ID、账号和凭据 revision，只恢复草稿字段，不把无绑定流程的历史错误/外部对象范围当成当前绑定状态。返回修改不会丢掉此前的准备声明，修改名称后仍需重新明确确认。旧原生创建记录只有在已确认创建新空间、切换守卫通过且新目标已实际验证后，才用既有 CAS 归档保留；其他上下文继续拒绝替换固定 ID。

实际旧绑定→新空间的完整回归还覆盖双重结算：运行时和面板可能同时观察到导入队列已清空。switch checkpoint 仅在完整记录等价、只差 settled 标志时接受已完成读回；身份、material、其他字段变化或真实读取失败继续拒绝，不将同步成功后的重复结算误报为连接失败。首次有权限时的原生创建、显式授权和固定 ID 保护继续使用原路径。

本批次基线 `2f878e0184`。16 个实现、资源、说明及测试文件沿同一准备/授权/连接链路修改，另含两端构建版本分配；涉及只读 HTTP、固定 ID、草稿作用域及切换结算，按一个完整用户能力审查，没有新增恢复系统或数据库表。

- 行为红：`repository-first-red`、`repository-first-old-record-red`、`repository-first-primary-red` 与完整旧绑定路径，分别确认步骤顺序、旧记录接续、按钮主次和成功后重复结算的错误。
- 组合验证：`repository-first-settlement-green` 中两条真实 HTTP/controller/Compose 完整路径及首屏测试通过；该 run 随后因新 unit fixture 编译失败结束，不能记作整个命令成功。`repository-first-settlement-guards-final` 随后 23 项通过，覆盖 20 个 HTTP manager 用例及草稿/凭据、严格结算和过期官方回调的 3 项共享契约；其余未受局部修复影响的 focused 通过证据复用。正常 data/presentation/i18n 格式与 `git diff --check` 通过。
- 独立审阅实际 Compose 两步中文图，确认首次创建为主、首次没有继续按钮、准备目标标为待验证、安装位于第二步；浏览器同名打开后继续为主、改名恢复创建，由真实事件测试覆盖。图在忽略的 `presentation-sync/build/sync-visual/`，不作为真实 GitHub 写入验收。
- 正式 APK aex.52 / versionCode 70：`app/artifacts/android/0.19.4-aex.52-vc70-2f878e0184-release/Mihon-Fork-0.19.4-aex.52-vc70-release-universal.apk`，SHA-256 `0a3af0085e0804bad5ed92ab63b18fbeba2537ac6c8bd0bf1ce9a522d1c88bdb`。标准候选构建、原 fork 证书及包身份核验通过；用户明确授权后独立安装到当前连接的华为 PCE-W30，安装后核验通过，未代操作真实同步。
- `repository-first-desktop-candidate` 标准脚本一次完成完整 Desktop 门禁与正式构建：3253 项执行，0 失败、0 错误、2 项跳过；运行版本与生产扩展安装链路验收通过。正式 EXE：`app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.91.2f878e0-unpacked/Mihon Desktop.exe`。Android 先构建安装，Desktop 后分配版本，各产物依据各自冻结输入追溯。
