# 自有应用更新与问题反馈

本 fork 的更新和反馈使用现有 GitHub 仓库、Releases、Pages 和 Issues，
不需要部署服务端、购买域名或把维护者凭据放进客户端。
本文是维护规范；具体版本是否已交付，以 Releases、候选验证和用户验收记录为准。

## 用户路径与边界

- Android 和 Desktop：关于 → 检查更新；同页可关闭自动检查、关闭测试版通道。
- 自动检查默认开启、测试版通道默认开启，适用于当前小范围测试。成功检查间隔为 12 小时；
  手动检查绕过间隔，网络错误不记录为成功。相同版本的自动提示去重。
- Android 正式身份：查看更新说明 → 下载 → 文件长度、SHA-256、包名、版本和签名检查 → 系统安装确认。
  Debug 身份只打开发布页，不尝试用正式 APK 覆盖调试安装。
- Desktop：关于页显示新版本和可滚动更新说明，并可打开发布页；用户下载对应平台包，退出应用后手动更换程序。
  本流程不承诺应用内下载、自解压覆盖、回滚或无提示安装。
- 关于 → 问题反馈：预览应用版本与系统摘要 → 自行复制 → 打开本仓库的 Issue 表单。
  打开表单不会自动提交，也不会将摘要拼入 URL。GitHub 提交需要登录，问题与手工附件公开可见。
  原始日志沿用原有本地入口，只有用户主动检查、脱敏并附上时才离开设备。

Android 复用主界面的启动检查，Desktop 在应用运行期间周期检查；本批没有新增 Android 周期后台任务，
也没有 FCM/APNs 即时推送。应用关闭、系统调度或网络不可达时不能保证通知时效。
通知不代替关于页的手动入口。
不启用 Crashlytics、分析遥测、匿名反馈或自动上传日志。

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

本流程使用公开 GitHub 免费设施；主要成本是候选构建、验收、发布说明和处理反馈的维护时间。
GitHub 可达性会影响下载和反馈；免费方案不提供大陆网络可用性保证。
后续只有需要匿名免登录反馈、自动崩溃聚合、实时推送或独立镜像时才评估额外服务。

修改协议时运行对应共享 domain/data 契约和双端真实接线测试；HTTP 覆盖成功、空数组、
403/429/500、畸形 JSON、错误身份和不兼容平台。修改发布工具运行
`python scripts/tests/update-feed-test.py`，修改候选证明同时运行相关 `build-android-test.py` 测试。
网站运行 `npm --prefix website run docs:build` 并确认产物包含 `updates/v1.json`。
这些 focused 检查不等于正式发布矩阵；正式里程碑沿用项目独立的授权和验收门槛。

公共功能说明须等待用户验收本次具体候选后按[公共文档规则](public-documentation.md)更新。
