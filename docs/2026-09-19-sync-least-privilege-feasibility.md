# 专用仓库最小权限同步：关键技术预研

- 日期：2026-09-19
- 结论：**有条件可行（Conditional GO）**
- 关联设计：[专用仓库最小权限设计](2026-09-19-sync-least-privilege-onboarding-design.md)
- 实施计划：[专用仓库最小权限 Roadmap](roadmap/2026-09-19-sync-least-privilege-onboarding-roadmap.md)

本文只确认关键技术路线、当前代码差距和发布门槛。未修改产品代码、线上 GitHub App、用户仓库或凭据；MockWebServer 结果不替代真实 GitHub 写入验收。

## 1. 结论矩阵

| 关键点 | 结论 | 证据与实施含义 |
| --- | --- | --- |
| 其他用户安装同一个 App | 当前阻塞 | `mihon-desktop` 公开页面于 2026-09-19 明确显示为 private；私有 App 只能由所属账户安装。上线前须改为 public，无需上架 Marketplace |
| 用户网页创建固定名私库 | 可行 | GitHub 官方支持用 URL query 预填 owner/name/visibility；README、模板等仍须页面提示且返回后复核 |
| 安装时只选 `mihon-sync` | 可行 | 官方安装流程支持 Only select repositories；仓库必须先存在，用户授权与 App 安装是不同状态 |
| 移除 Administration | 接口与源码层可行 | 现有交换涉及的 Contents、blob/tree/commit/ref 端点只需 Contents write；生产代码唯一明确的 Administration 用途是 `POST /user/repos` 和对应权限硬门槛 |
| 真空库首次初始化 | 有条件可行 | 官方要求空库先用 Contents API 创建内容，之后才能创建 Git ref；现有 transport 已实现该序列且 focused 契约通过。仍缺真实 Contents-only App 安装写入证据 |
| 不覆盖 README/其他历史 | 可行 | v2 transport 契约已拒绝 README 和异常树且零写入；发现层仍需增加完整可达引用核验，不能只看 `size == 0` |
| 两设备/中断安全恢复 | 架构可行，现存状态不足 | 现有非强制 ref 更新和读回确认可复用；固定 bootstrap 与 `submitted` 布尔无法证明新初始化意图，需版本化阶段和随机 nonce |
| 现有 v2 用户继续使用 | 可行 | 已完成连接已有 repository ID、descriptor 和安全材料；应原样保留。只终止旧自动建库 pending，不迁移 v1 |

最终 GO 需要同时关闭两个外部门槛：App 改为 public，以及使用隔离个人账号、仅选一个空私库、无 Administration 的真实闭环。当前不能宣称已经可向其他用户发布。

## 2. GitHub App 分发与安装

### 2.1 当前 App 不能供其他用户安装

只读打开 `https://github.com/apps/mihon-desktop` 返回：`mihon-desktop is a private GitHub App.`。GitHub 官方说明：public App 可由任意 GitHub 用户安装和授权；private App 只能安装到拥有它的账户。[可见性说明](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/making-a-github-app-public-or-private)

因此“所有用户共用开发者创建的同一个 App”方案成立的前置是将现有 App 设置为 public。Public 不等于 Marketplace 上架，不要求用户自己建立 GitHub App。这个外部配置变更不在本次预研授权内，M3 执行前另行核对并修改。

### 2.2 最小仓库选择闭环成立

GitHub 区分 authorization 与 installation，且两者可以各自存在；只有安装才授予仓库资源访问。安装页允许用户选择 Only select repositories 并勾选已有仓库。[第三方 App 安装说明](https://docs.github.com/en/apps/using-github-apps/installing-a-github-app-from-a-third-party)

GitHub 建库页支持以下 query：`name`、`description`、`visibility`、`owner`，其中 `owner=@me` 可指当前登录用户。[建库 URL 参数](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-new-repository#creating-a-new-repository-from-a-url-query)

建议产品链接预填 `owner=@me&name=mihon-sync&visibility=private` 及说明文字。URL 不能强制用户不选 README/.gitignore/License，也不能保证浏览器当前账号与应用授权账号一致；应用必须按稳定 account ID、repository ID、private、owner 和可达引用重新核验。

推荐顺序保持为：

1. 设备授权，取得当前个人账号身份。
2. 打开预填的 GitHub 建库页，用户确认创建空私库。
3. 打开 App 安装页，选择 Only select repositories → `mihon-sync`。
4. 返回 Mihon，重新读取用户安装及安装可见仓库。
5. 空库验证通过后才询问/提交可选密码并初始化。

该顺序从未要求 All repositories，也不授权其他既有仓库。浏览器返回只触发一次有界重查；用户始终可用“我已完成，重新检查”。

## 3. Contents-only 权限可行性

2026-09-19 只读 `GET /apps/mihon-desktop` 显示当前注册权限为 Administration write、Contents write、Metadata read。目标是删除 Administration，保留 Contents write 与 GitHub 自动附带的 Metadata read。

| 生产动作 | 当前端点 | 官方最低权限 | 结论 |
| --- | --- | --- | --- |
| 获取安装与安装可见仓库 | `GET /user/installations`、`GET /user/installations/{id}/repositories` | 用户访问令牌；前者无额外权限 | 保留现有分页/origin 校验 |
| 仓库元数据、读取树/对象 | `/repos/{owner}/{repo}`、Git database GET | Metadata/Contents read | Contents write 包含所需读能力，真实返回仍需验证 |
| 空库首个提交 | `PUT /repos/{owner}/{repo}/contents/.mihon-sync/bootstrap` | Contents write | 可行；不传 `sha`，因此不能替换同路径已有文件 |
| 创建 blob/tree/commit | `POST .../git/blobs|trees|commits` | Contents write | 可行 |
| 创建/非强制更新同步分支 | `POST/PATCH .../git/refs` | Contents write | 可行；更新保持 `force=false` |
| API 自动建库 | `POST /user/repos` | Administration write | 从生产流程删除 |

官方明确说明空仓库不能直接创建 Git reference；应先用 Contents API 初始化仓库。[Git database 指南](https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-your-git-database)、[引用 API](https://docs.github.com/en/rest/git/refs#create-a-reference)、[Contents API](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents)。blob/tree/commit/ref 写入均列在 Contents write 权限下：[权限矩阵](https://docs.github.com/en/rest/authentication/permissions-required-for-github-apps)。

源码检索确认当前 sync 生产代码中：

- `GitHubSyncSpaceClient` 在安装扫描时强制 `administration == write`，并在 `createOrResume()` 调用 `POST /user/repos`；这两处需要替换。
- `GitHubGitDatabaseClient` 的首次初始化已是 Contents bootstrap → 读取首个提交 → create-only 同步 ref → descriptor/index 提交，没有修改仓库设置、删除仓库或强制更新。
- 日常写入使用 blob/tree/commit/ref，`updateRef()` 明确传 `force=false`。

因此权限降级不存在需要重写 transport 的接口障碍。真实 GitHub 仍可能因令牌交集、安装尚未批准新权限或服务端响应差异返回 403/409/422，必须以 M3 的 scoped installation 运行结果收口。

## 4. 空库识别与首次初始化协议

### 4.1 当前能力

现有 v2 transport 支持：

- 仓库为 private，元数据 `size == 0` 且默认 ref 返回 404/409 时走空库路径；
- 读取根 contents，拒绝非空或畸形数组；
- 用 Contents API 写 `.mihon-sync/bootstrap` 建立默认分支首个提交；
- 精确核验 bootstrap 树后创建同步分支；
- 在同步分支同一提交中发布 v2 descriptor 和加密/明文初始索引；
- create-only ref、非强制更新、写后读回确认。

现有 focused 契约证明了空库 409、两种保护模式、README 拒绝、异常 bootstrap、并发 initializer、非快进冲突、丢失响应和篡改拒绝。它们执行真实 production codec/transport，但 HTTP 服务为 MockWebServer，不验证 GitHub 权限实际生效。

### 4.2 必须补强的空库判定

`size == 0` 不是充分条件。实施应检查：

1. 安装列表和安装仓库列表完整分页，固定名目标确实由当前个人 account ID 所有。
2. 仓库 ID 固定、private、未 archived/disabled，用户权限包含 push/admin，安装权限包含 Contents write。
3. 默认 ref 与 heads/tags 的可达引用视图一致为空；分页截断、403、异常409/422不转成空库。
4. 根 contents 为 GitHub 的真实空库响应；README、模板文件、只剩空树提交、其他分支或标签全部拒绝。
5. 用户提交密码时再检查一次相同仓库 ID 和空状态。

不要求枚举不可达的 dangling Git objects；产品的安全边界是“不覆盖任何可达用户内容或引用”。API 不能为“验空—首次 PUT”提供跨请求事务，因此不能承诺任意外部并发下仓库零修改。

### 4.3 建议的可恢复 bootstrap

当前 `.mihon-sync/bootstrap` 内容是固定字符串，无法证明是当前本机尝试写入。新协议应在本机安全存储中持久化随机 `attemptNonce`，首次 PUT 写入版本化 bootstrap，例如包含：协议版本、account ID、repository ID、attempt nonce、space ID、generation 和 descriptor 摘要。它不包含密码、数据密钥或令牌。

安全状态转换：

1. `VERIFIED_EMPTY`：记录仓库 ID、最新验证事实、材料和 nonce；尚未远端写入。
2. `BOOTSTRAP_SUBMITTING`：CAS 持久化后，PUT 固定路径且不传 `sha`。
3. `BOOTSTRAP_CONFIRMED`：读回路径、内容、commit/tree 和精确单文件树，必须匹配 nonce。
4. `SPACE_PUBLISHING`：从确认提交 create-only 创建同步分支并发布完整 descriptor/index。
5. `SPACE_CONFIRMED`：读回完整快照并匹配 material，再绑定本机。
6. `CONNECTED`：完成初始合并进度持久化；之后清理 pending。

如果 PUT 响应丢失，按 nonce 读回并继续；同路径已存在不同 nonce 时停止并显示另一设备正在初始化。若首次 PUT 之后外部写入其他文件，精确树复核失败并停止，可能留下 Mihon bootstrap，但不会覆盖外部文件或推进同步分支。应用不自动删除或回滚用户仓库。

两个设备不同密码时，首个 bootstrap path 的 create-only 语义决定赢家；输家不得接受不同 nonce、space ID 或 descriptor，也不得用自己的密码覆盖赢家。若赢家已完整发布，输家转入已有空间加入流程。

## 5. 持久化与兼容性差距

当前 `StoredSyncSetup(version = 2)` 有 `attemptId`、材料、`submitted`、repository ID 和 connected，但 `submitted` 专用于表示旧 `POST /user/repos` 是否已发出。直接把它解释成 bootstrap 阶段会使升级后的旧 pending 被错误重放。

实施应新增版本化 setup 记录（建议 v3）及显式阶段枚举，并增加 repository ID、attempt nonce、确认的 bootstrap commit/tree 等必要字段。`SyncSetupStorage` 继续使用现有安全存储 CAS，不保存原始密码。处理规则：

- 已完成 `StoredSyncConnection(version=2)` 保持可读，现有 v2 用户不重新配置。
- `StoredSyncSetup(version=2)` 只识别为旧自动建库 pending，停止并引导重新检查；不执行旧 POST、不自动转成 v3。
- v1/未知 connection 仍按既定“不兼容”规则保留数据并要求显式断开。
- v3 每一步都用 expected-value CAS，账号变化、仓库删除重建（ID 变化）或新尝试替换旧尝试时拒绝陈旧回执。

这些变化局限在现有 SyncOnboarding/SyncSetupStorage/transport 链路，无需新增同步 manager、数据库表或服务端。

## 6. 本次验证

执行命令：

```powershell
python scripts/gradle-coordinator.py run --key sync-least-privilege-spike -- .\gradlew.bat :data:jvmTest `
  --tests "mihon.data.sync.SyncSpaceTransportContractTest" `
  --tests "mihon.data.sync.SyncGitSafetyContractTest" `
  --tests "mihon.data.sync.SyncSpaceDiscoveryContractTest"
```

结果：协调器 PASSED；54项、0失败、0错误、0跳过：

| 套件 | 数量 |
| --- | ---: |
| SyncGitSafetyContractTest | 26 |
| SyncSpaceDiscoveryContractTest | 16 |
| SyncSpaceTransportContractTest | 12 |

只读外部证据：

- `GET https://api.github.com/apps/mihon-desktop`：Administration write、Contents write、Metadata read，更新时间 `2026-09-18T12:58:00Z`。
- `https://github.com/apps/mihon-desktop`：当前为 private GitHub App。

未执行：真实 App 改权、公开化、私库创建/安装/写入、用户令牌读取、仓库删除或清理、Android/Desktop 构建。工作区已有作者专项改动未触碰。

## 7. 实施前 GO/NO-GO 门槛

M1 可按现有 roadmap 启动，但不得把以下事项延后到发布后：

1. focused 红测先证明仅 Contents/Metadata 的安装 JSON 能进入空库候选，且生产请求不再包含 POST `/user/repos`。
2. v3 pending 与 attempt-specific bootstrap 协议先于 UI 接线完成独立安全审查。
3. 隔离账号记录真实空库的 repository/refs/contents 响应形状，固化 fixture 后补生产链路测试。
4. App 改 public 和移除 Administration 属于外部配置动作；实现、审查和本地验证完成后再执行，旧客户端升级影响须同时说明。
5. 用非 App 所属的隔离个人账号完成：网页建空私库 → 仅选择该库安装 → 两种密码模式之一初始化 → 第二设备加入 → 重启与真实交换。

若 Contents-only 首次 PUT/refs 在真实 GitHub 上失败，或无法在并发下保证不覆盖可达用户内容，则 M1 停止并返回具体响应与替代方案；不得恢复 All repositories 或 Administration 作为静默兜底。
