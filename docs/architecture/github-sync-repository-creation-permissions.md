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
