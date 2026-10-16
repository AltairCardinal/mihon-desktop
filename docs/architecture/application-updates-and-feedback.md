# 自有应用更新与问题反馈

本 fork 的更新使用现有 GitHub Releases/Pages；免账号反馈由 Cloudflare Workers/D1 接收，
再由服务端专用凭据转交本项目 GitHub Issues。维护者需要配置 Cloudflare，最终用户无需任何账号。
不购买域名，不把维护者凭据放进客户端；免费额度与服务可达性仍受托管平台限制。
2026-10-15 已发布测试包使用旧的 GitHub 登录入口；免账号入口须以新的候选及实际部署验收为准。
本文是维护规范；具体版本是否已交付，以 Releases、候选验证和用户验收记录为准。

## 用户路径与边界

- Android 和 Desktop：关于 → 检查更新；同页可关闭自动检查、关闭测试版通道。
- 自动检查默认开启、测试版通道默认开启，适用于当前小范围测试。成功检查间隔为 12 小时；
  手动检查绕过间隔，网络错误不记录为成功。相同版本的自动提示去重。
- Android 正式身份：查看更新说明 → 下载 → 文件长度、SHA-256、包名、版本和签名检查 → 系统安装确认。
  Debug 身份只打开发布页，不尝试用正式 APK 覆盖调试安装。
- Desktop：关于页显示新版本和可滚动更新说明，并可打开发布页；用户下载对应平台包，退出应用后手动更换程序。
  本流程不承诺应用内下载、自解压覆盖、回滚或无提示安装。
- 关于 → 问题反馈：在原生表单填写标题/描述，可选附带应用信息；主动读取本应用崩溃日志后，
  预览删改并单独授权给维护者。确认公开问题与私密日志权限后点击提交，仅打开浏览器完成安全验证，
  程序随后发送固定快照并显示回执。打开表单不读取剪贴板、不读取日志、不发送正文，不采集联系方式。
  浏览器只收到随机验证会话编号，不收到正文、日志或native secret；关闭/取消不会继续后台上传。
  等待验证时取消不会发送正文；请求已发出时只能停止本机等待，服务可能已接收，同编号重试避免重复。
  日志仅在服务端私密保存供项目维护者查看，不进入 GitHub；不支持其他任意附件。
  提交成功先表示已持久接收、待处理，并不等于 GitHub Issue 已创建。
  后台仅转交公开的问题内容，页面不列出其他反馈或用户内容。
  用户不应把含凭据、私有地址或书库的原始日志粘贴到公开描述。
  旧客户端仍可打开现有免登录网页手动反馈/选择日志；服务保持兼容。

Android 复用主界面的启动检查，Desktop 在应用运行期间周期检查；本批没有新增 Android 周期后台任务，
也没有 FCM/APNs 即时推送。应用关闭、系统调度或网络不可达时不能保证通知时效。
通知不代替关于页的手动入口。
不启用 Crashlytics、分析遥测或自动上传日志。匿名指无需服务账号；网络服务仍可处理连接 IP，
接收数据库仅保存 HMAC IP 哈希用于短期限额，不承诺网络层不可追踪。

## 共享协议与平台适配

默认索引是 `https://altaircardinal.github.io/mihon-desktop/updates/v1.json`，
源文件是 `website/public/updates/v1.json`，由既有 Pages 工作流发布。
共享 `ReleaseServiceImpl` 和 `GetApplicationRelease` 负责验证、选择、版本比较和成功缓存。
两端通过 `ForkUpdateRequest` 明确选择本项目协议；上游兼容路径不作为失败回退源。
APK 安装验证复用既有 `AndroidRecoveryPackageInstaller` 的只读校验。
缓存使用固定的平台/渠道短键，并绑定当前安装版本、架构、包身份及签名；身份变化重新请求。
为适配 Desktop 系统 Preferences 的长度限制，缓存只保存最多 512 个 Unicode 码点的说明摘要，
摘要带截断标记并引导打开完整发布页；当次网络结果仍保留完整说明。缓存读取也会重新验证链接与 Android 身份。

v1 格式：

```json
{ "schemaVersion": 1, "releases": [] }
```

空数组表示尚无可用发布包，不能为了演示填入未上传的版本。
每项必须包含 `channel`、`platform`、`arch`、`packageType`、`version`、`releaseUrl`、
`downloadUrl`、`sha256`、`sizeBytes`、`notes`。
Android 另含 `versionCode`、`applicationId`、`certificateSha256`。

| 字段或规则 | 约束 |
| --- | --- |
| channel | `stable` 或 `testing`；testing 同时接受 stable，选择最高兼容版本 |
| Android | `android`；`universal` 或支持的 ABI；`apk`；正式包名和证书来自现有构建配置 |
| Windows | `windows`、`x86_64`、`zip` 或 `msi` |
| macOS | `macos`、`x86_64` 或 `arm64`、`zip` 或 `dmg` |
| Android 升级序列 | 正整数 versionCode；不从显示版本中提取数字 |
| Desktop 升级序列 | 四段非负整数，逐段比较；可附 7–40 位十六进制提交 hash，hash 不参与新旧判断 |
| 下载来源 | 仅 `https://github.com/AltairCardinal/mihon-desktop/releases/download/…`，与发布页 tag 一致 |
| 链接 | 禁止凭据、端口、query、fragment、编码路径和路径穿越；只接受有限的 tag/文件名字字符 |
| 完整性 | 64 位小写 SHA-256、准确字节数；Android 还验证预期版本、包名与正式签名 |
| 上限 | 索引 512 KiB、256 项、每项说明 32768 字；APK 512 MiB，Desktop 包小于 2 GiB |

切换稳定通道不会降级已安装版本，也不会保留旧测试版候选操作。
不支持的平台或架构显示“没有兼容安装包”。错误索引、未知 schema、HTTP 错误和身份错误
均显示检查失败，可手动重试；不跳转上游、不安装未验证内容。索引请求禁止 HTTP 重定向。
维护者修改仓库或信任源时必须同时调整客户端、发布工具和对应契约，不能只改网页链接。

## 发布既有候选

本流程不分配版本、不构建、不签名、不安装、不推送 Git，也不自动创建 Release。
先按既有 Android/Desktop 构建与验收规范取得允许分发的候选，并核对版本严格高于已分发版本。
Desktop ZIP 必须包含整个未打包应用目录及运行时，不能仅压缩 EXE；维护者核对 About 中的完整版本。
预览包和未签名 Android CI 包不能冒充正式候选。APK 继续保持原包名和签名连续。

1. 将验收过的文件上传到本仓库 Release。测试通道对应 GitHub prerelease，稳定通道对应普通 Release。
   写好更新说明，完成上传后发布。先保留本地候选及其 `artifact.json`、R8 mapping 和验收证据。
2. 为每个平台资产生成条目，输出放在忽略的产物目录。例如在仓库根目录：

   ```powershell
   python scripts/update-feed.py prepare --artifact "<既有完整ZIP绝对路径>" --platform windows --arch x86_64 --package-type zip --version "<About完整版本>" --channel testing --tag "<实际Release标签>" --notes-file "<UTF-8说明文件>" --output "app-desktop/artifacts/update-entry.json"
   python scripts/update-feed.py add --entry "app-desktop/artifacts/update-entry.json"
   python scripts/update-feed.py validate
   ```

   Android 使用 `--platform android --arch universal --package-type apk`；工具调用现有候选验证入口，
   从验证后的 APK 清单取得 code、包名、证书和版本，不接受调试或未签名包。
3. `add` 用维护者已有的 `gh` 登录只读查询该公开 Release，要求资产状态为 uploaded，
   GitHub 返回的 digest、长度和 URL 与本地文件一致。draft、错通道、缺 digest、未完成上传都会失败。
   相同条目重复加入是幂等的；同一平台、架构、通道、升级版本不能替换成另一个文件。
4. 审阅索引 diff 后按分支保护提交、合入。Pages 工作流验证索引与发布工具测试，再部署网站。
   部署成功后读取线上 JSON，并在旧版客户端“关于 → 检查更新”验证对应平台。
   Release 上传成功和本地索引写入成功都不等于客户端已可发现更新。

网络访问遵循仓库代理规则。`add` 不将 gh token 写入条目、日志或 APK。
GitHub 返回 digest 缺失时，先核对资产是否上传完成；不要删除完整性校验来通过发布。
Pages 部署失败时客户端继续使用上一份索引。紧急撤回可移除对应条目并重新部署；
客户端成功缓存有效期为 12 小时，手动检查可绕过该间隔请求刷新；离线、系统调度和托管端缓存
仍可能延迟可见性，不能据此承诺撤回即时生效。撤回不卸载已安装版本。

## 维护成本与验证

### 免账号反馈部署与失败处理

旧客户端网页固定入口为 `https://altaircardinal.github.io/mihon-desktop/feedback/`，当前仅跳转
`https://mihon-feedback.mihon-altaircardinal.workers.dev/`，不转发 query 或摘要。
接收服务、真实数据库迁移、配额与维护命令见 [服务维护说明](../../services/feedback/README.md)。
维护者通过 Cloudflare OAuth 部署；Turnstile secret、IP 哈希 secret、专用仓库 Issues-write token
只配置为 Worker secret。GitHub token 限 `AltairCardinal/mihon-desktop`，不复用本机广权限 gh token。
网页与应用不包含维护者凭据。站点 key 与数据库绑定 ID 是公开配置，不是机密。

用户显式确认后才向同源 API 提交；640 KiB 请求上限、字段白名单、验证码 hostname/action 校验、
每 IP 每小时 3 次及全站每天 50 次的数据库原子配额均在服务端执行。
成功返回 `received_pending` 与不可猜回执，重试使用相同 UUID 和相同内容，仍需新验证码；
页面草稿只留当前内存，刷新/关闭会丢失。服务未配置、验证码失效、超限或网络失败时保留本页输入并提示，
不回退到 GitHub 登录。回执不提供公开报告查询接口。

每五分钟最多转交五条；持久化记录先原子 claim，成功记录 Issue URL，429 延后，5xx/超时或进程中断
等不确定结果进入人工核对，禁止自动重复 POST。维护者按提交 UUID 标记对照 Issues 后再处理。
数据库报告、私密日志和 IP 哈希在 Cron 正常时通常保留最多 30 天，清理不依赖 GitHub token 有效性；
Cron 故障可能延长，GitHub 公开 Issue 不受数据库保留期约束。
变更上线必须用无 GitHub 登录的浏览器完成真实验证码与提交，并核对 D1 回执和后台 Issue；
打包或测试通过不能代替这一步。凭据存在性检查不能证明专用 token 权限有效。

`Anonymous feedback checks` 只运行服务的 Node 24 focused 测试和无密钥 dry-run，
不部署服务、不运行产品完整矩阵、不使用 GitHub Issues 写权限；维护者部署继续使用已有 OAuth。

更新流程使用公开 GitHub 免费设施；免账号反馈另使用 Cloudflare 免费 Workers/D1/Turnstile。
主要成本是候选构建、验收、发布说明、专用凭据轮换、垃圾反馈处理和服务维护时间。
GitHub、Cloudflare、验证码可达性会影响用户路径；免费方案不提供大陆网络可用性保证。
不为额度不足自动升级付费套餐；超限应失败并提示稍后重试。自动崩溃聚合、实时推送、
独立镜像、私密问题正文/联系方式或其他任意附件均不属于当前范围。

文本崩溃日志复用同一数据库：单次最终日志最多 256 KiB UTF-8，网页文件预览最多 4 MiB，
选择后可编辑精简；不静默截断，拒绝压缩包、二进制或无效编码。
`crashLog` 与独立 `crashLogConsent` 为可选字段，旧请求仍兼容；日志计入幂等内容哈希，
同 UUID 换日志不能覆盖已提交记录。公开问题同意不等于私密日志授权，文件变化/编辑需重新确认。
Cron 只读取转交所需的公开列及有无私密日志的布尔标记；公共回执、错误、配置和 Issue 均不返回日志
或下载链接。既有 Cloudflare OAuth 的维护者通过服务提取工具、严格 UUID 查询并保存到仓库外新文件；
不覆写本地文件、不输出日志或凭据，不添加任何匿名日志读取接口。

修改协议时运行对应共享 domain/data 契约和双端真实接线测试；HTTP 覆盖成功、空数组、
403/429/500、畸形 JSON、错误身份和不兼容平台。修改发布工具运行
`python scripts/tests/update-feed-test.py`，修改候选证明同时运行相关 `build-android-test.py` 测试。
网站运行 `npm --prefix website run docs:build` 并确认产物包含 `updates/v1.json`。
这些 focused 检查不等于正式发布矩阵；正式里程碑沿用项目独立的授权和验收门槛。

公共功能说明须等待用户验收本次具体候选后按[公共文档规则](public-documentation.md)更新。


### 应用内表单与验证会话

双端在原生表单填写问题，主动读取本应用日志、预览删改并单独授权后由程序提交。
共用提交状态/校验/HTTP协议；日志来源与浏览器启动留在平台adapter。Desktop无可用内嵌网页引擎，
仅验证码在系统浏览器完成；浏览器不接收正文、日志或native secret，不新增loopback/JS桥。
提交后冻结快照，失败保留本页草稿；取消停止验证等待及后续发送，成功反馈持久化回执。发送阶段取消不能撤回服务已接收的请求；
同编号重试仍保持幂等，界面不把取消冒充服务端撤销。
草稿/凭据不写偏好或磁盘，不自动读取用户日志，日志失败可不带日志继续。

验证会话的API、UTF8固定哈希协议、原子消费与配额见[服务维护说明](../../services/feedback/README.md#native-application-submission)。
会话只存哈希/状态，10分钟过期；独立限流事件仅UUID/HMAC IP/时间，正常最多24小时清理，
避免会话过期删除破坏小时/日限额。旧网页验证码、报告幂等、公开转交与私密日志规则保持兼容。
部署先运行0003迁移，回滚旧Worker不会删除已有报告/私密日志；元数据清理随新版恢复。
原生正式HTTP客户端及代理链复用：Android从既有nonCloudflareClient派生，Desktop移除源站挑战/挑战凭据拦截器，
保留生产代理/DNS/TLS和路由观察；反馈派生客户端禁止重定向/缓存/载荷和Authorization日志。
反馈API遇WAF挑战直接安全失败，不把带Bearer的轮询请求交给WebView或源站登录界面。
