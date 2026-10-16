# 自有更新与问题反馈：实施与验证记录

基线：`e0b8c6a0ae`。隔离工作分支：`codex/update-feedback-20261013`。
原工作区的用户未提交改动未纳入本批。具体候选尚未获得用户验收。

## 范围

共享更新索引、版本和成功缓存；Android 校验下载与系统安装入口；Desktop 自动检查和手动发布页；
双端关于页的更新偏好与显式安全摘要反馈；GitHub Pages 索引与发布条目校验工具。
复用既有网络、偏好、通知、导航和安装校验链路，不增加服务器或自动遥测。

本批跨共享模块、双端适配、发布工具和维护说明，超过 8 个文件/400 行。
这些变更共同构成同一更新协议的生产消费和发布输入，不能按文件拆为可独立验收的功能。
主要风险是签名/包身份验证、缓存隔离、生命周期取消和通知去重；通过真实集成测试及独立审查覆盖。

## 红阶段与配置检查

以下均是明确限定类/用例的 focused 验证，没有执行完整模块或发布矩阵。
Gradle 由同一协调器串行执行，日志保存在本工作树忽略的 `.gradle-coordinator/`。

| 批次 | 结果与证据边界 |
| --- | --- |
| `update-feedback-red` | Windows 参数中的管道符导致命令启动失败；不作为行为红证据 |
| `update-feedback-red2` | 共享 domain/data 的未实现契约产生预期失败；Desktop 编译内存不足，未执行 UI 测试 |
| `update-feedback-desktop-red` | 共享更新 domain 4、data 6 通过；安全摘要 4、Desktop 反馈 1、Desktop 更新接线 4 出现预期失败；Android 自有构建开关检查失败 |
| `update-feedback-integration-red` | Android 自有更新开关配置检查通过；原生 Preferences 缓存 4 个预期失败；Android 18 个测试中 11 个预期失败，覆盖缺少入口/偏好/DI、错误下载成功、版本身份验证和重复提示；Desktop 测试代码编译错误导致本轮未执行，不能复用旧 XML 冒充本轮结果 |
| `update-feedback-final-red` | 共享更新 4、原生缓存 4、安全摘要 4 通过。Desktop 更新 10 项中旧 4 项通过，新 6 项正确红；About 反馈通过、偏好开关正确红。Android 下载 5、投递 4 均正确红；兼容 6 项中 3 项正确红；About 开关通过，反馈入口滚动查询失败待诊断。本轮两端均编译并实际执行 |

缓存失败揭示真实桌面 Preferences 的键/值长度限制及旧身份/架构缓存复用，不能以内存偏好测试代替。
Android 下载失败揭示原下载 worker 吞掉异常后返回成功，以及未校验文件即提供安装操作的问题。
Desktop 新增取消、通知订阅时序与停止/重启清理的回归测试，后续结果在本记录内补齐。

`update-feedback-ui-red` 的 Desktop 部分已实际执行：更新生产接线 10/10、既有 About 更新操作
6/6、控制器 8/8 通过，About 反馈和开关通过；第三项大字号/键盘测试在滚动断言处失败待关闭。
本轮包含一次新的 Android 界面接线红阶段，不把整条命令称为全绿。

`update-feedback-ui-red2` 关闭了 Android UI 测试的 WorkManager mock 配置错误后，
实际得到三项正确红：Main 展示未记已提示、下载按钮未携带完整元数据、Debug 边界缺说明。
Desktop 大字号测试的滚动失败定位为测试帧时间始终为零；改用递增真实纳秒帧时间，
保留滚动范围、按钮边界、键盘循环和关闭恢复焦点断言，不删除验收要求。

`update-feedback-focused-green` 的已完成范围（2026-10-13 18:40–18:44 CST）：

| 范围 | 实际结果 |
| --- | --- |
| Android About / 更新 UI / DI / 下载 / 旧兼容 / Recovery | 3 + 3 + 3 + 5 + 6 + 4 项通过 |
| Android Delivery | 原 5 项通过；新增在途渠道/开关变化及展示渠道归属 2 项正确红，重试不计为额外用例 |
| domain JVM | 更新契约 4、原生 Preferences 4、安全摘要 4、既有上游对齐 4 通过 |
| data JVM | 自有 HTTP 协议 6、既有资产选择/错误回归 10 通过 |
| domain Android | 同一更新契约 4、安全摘要契约 4 通过 |
| data Android | 同一自有 HTTP 协议 6 通过 |
| Desktop | 更新生产接线 10、反馈 3、既有 About 无障碍 5、runtime 关闭 updater 1 通过 |
| 格式 | 仓库 `spotlessCheck` 通过；没有执行产品完整模块测试 |

Android 最后两项竞态修复仅修改 checker：返回结果前复核自动开关和请求渠道，
展示去重使用发现时的渠道。Worker、Recovery 及共享模块未受该修改影响，通过证据可复用。

`update-feedback-repair-and-notes-red` 于 18:54:10 CST 产出修复后的 Android 结果：
Delivery 7/7、Compatibility 6/6、DI Wiring 3/3、UI Wiring 3/3 全部通过；
`:app:spotlessCheck` 通过。该命令整体失败仅因其中先执行的 Desktop U1 正确红：
实际页面未显示 release notes。Android 相关失败已关闭，不需重复未受影响的下载与签名验证。

该集中运行于 18:47:42 CST 结束，耗时 13 分 7 秒；整体失败仅因上文两项正确红，
不把命令名字中的 green 当作成功证据。Desktop 四张浅深主题、320 px/200% 字号图位于
`app-desktop/build/reports/feedback-visual/`，经实际查看确认上下滚动及按钮可达。
这些是 Compose 渲染/事件测试，不等同 Windows 原生窗口或系统剪贴板验收。

## 独立审查

主代理核对未由自己实施的共享协议、缓存信任边界及 Android 下载/安装接线；
反馈实施代理核对未由自己实施的发布工具和 Desktop 生命周期。没有新增第三个代理。
一轮审查及必要修复复审发现并处理：发布工具对序列化后大小/整数范围校验不足、
Desktop 重启前旧任务清理及自动检查覆盖安装状态、Android 在途请求的偏好变化和展示渠道归属。
各项均以具体行为红测推动最小修复；最后 Android 两项已完成静态复核和限定补验。
低风险反馈 UI 由实施者按实际事件、四张渲染图自查，主代理另查看代表图及关键接线。
U1 的 Desktop 页内更新说明在最终契约核对时发现遗漏，作为原范围补齐并单独执行红绿测试。

## 发布工具与网站

- `python scripts/tests/update-feed-test.py`：13 项通过，包含真实 CLI、发布响应校验和原子索引写入。
  新增行为均先观察对应失败，再修复；严格数字和序列化后字节数限制也经红绿验证。
- `python scripts/tests/build-android-test.py AndroidEntryTest`：9 项通过；实际构建回执缺失/错误的
  `forkUpdater` 字段会拒绝候选，旧上游 updater 和遥测仍关闭。
- `AndroidSdkArtifactTest` 中 4 个相关用例通过：真实 SDK 解析/签名校验、旧产物配置兼容、
  信任根不可由产物元数据替换、错误身份/版本/未签名/篡改拒绝。
  只使用隔离虚构 APK/临时测试密钥，没有安装设备或替换正式密钥。
- `npm ci --no-audit --no-fund` 与 `npm run docs:build` 通过；已读取构建产物
  `website/.vitepress/dist/updates/v1.json`，内容为合法空索引。

## 交付与未执行边界

`update-feedback-notes-green` 于 18:58:36 CST 成功结束：AboutUpdate 7、DesktopForkUpdate 10、
AboutAccessibility 5 共 22 项通过，补齐 U1 且保留既有行为。之后只有本记录和计划状态更新，
没有产品代码变化。上述组合证据覆盖本批影响范围，不表述为“最终代码重新全量通过”。

`update-feedback-preview` 使用 `scripts/build-desktop.sh preview` 成功完成。
版本仍为 `0.11.19.91.e0b8c6a`，没有递增；基线加本批未提交源码的真实指纹记录在 manifest，
不能将版本尾部 hash 误认为已经包含本批实现的提交。
实际产物是：

- `app-desktop/artifacts/preview/windows/20261013T105924Z-9a2c17af/Mihon-Desktop-0.11.19.91.e0b8c6a-unpacked/Mihon Desktop.exe`
- 同级上层 `preview-manifest.json`：build / sourceIntegrity / productionRuntime 均 PASS；
  nativeInteraction 为 NOT_RUN。运行时 PASS 只覆盖现有真实扩展安装链及版本检查，
  不能充当本次反馈剪贴板、外部浏览器或系统通知的原生窗口验收。
- 构建前后 production input digest：`f026c8d5587aeeb599f0731209dfa4addba38a5a212d98689420e31c81bc0f9c`。

复验命令范围均可从同名 `.gradle-coordinator/<key>.json` 读取；关键末轮可复现为：

```text
:app:testReleaseUnitTest --tests eu.kanade.tachiyomi.data.updater.ForkAppUpdateDeliveryTest --tests eu.kanade.tachiyomi.data.updater.AppUpdateCheckerCompatibilityTest --tests eu.kanade.tachiyomi.data.updater.ForkAppUpdateWiringTest --tests eu.kanade.tachiyomi.data.updater.ForkAppUpdateUiWiringTest :app:spotlessCheck
:app-desktop:jvmTest --tests mihon.desktop.ui.settings.AboutUpdateWiringTest --tests mihon.desktop.ui.settings.DesktopForkUpdateWiringTest --tests mihon.desktop.ui.settings.DesktopSettingsAboutExtensionAccessibilityTest
```

这些 Gradle 参数仍应通过项目协调器串行执行。更早共享、下载、反馈和控制器的通过证据按上文复用。
原始工作区后续由其他工作推进的 reader/tutorial 变更不包含在此隔离候选中，也未被覆盖。

没有分配正式版本，没有创建/上传 Release，没有部署 Pages，没有宣称线上更新已生效。
空索引有意不发布虚构候选；首次实际分发仍需要验收过的签名安装包和发布条目。
公共功能说明按项目规则等待用户对具体候选的验收后更新。

远端 main 的只读核验确认必须通过 `Build & Test App` 与 `Desktop JVM Tests`，并要求分支最新。
现有工作流会运行完整 Android 单元测试/未签名候选构建及完整 Desktop JVM 测试。
初次实施没有创建 PR 或运行该矩阵，也没有绕过分支保护。

## 合入与部署授权

用户在交付实现提交 `313507791e` 后明确回复“允许”，授权创建 PR、执行一次必要的完整 CI，
通过后合入并部署空索引；不包含正式应用发布或对具体产品候选的用户验收。
创建 PR 前合入当前 `origin/main` 的 `5a5c54b167`，其相对实施基线的变化仅涉及 reader/tutorial，
与本批写入范围无冲突。完整 CI 用于确认该集成版本及远端强制门禁，原 preview 仍是合入前产物。
失败时保留首次结果，先分析关联并局部补验，不自动重复完整矩阵。

## 2026-10-15 集成与上线收口

用户明确收窄为完成更新推送与问题上报，不处理其他模块 BUG。原 PR #3 的首次完整 CI 保留：
Android run `38390170348` 的候选构建、格式与交付契约通过，domain 的 8 项 reader 契约失败；
Desktop run `38390170339` 完整测试失败，涉及 DI、reader、parity 等既有范围，不能宣称全绿。
文档 run `38390170367` 通过。没有因这些结果扩展实现任务。

恢复时远端主分支已到 `53e23eb0b7`，包含既有 reader 修复及另外维护工作。
其提交 `c104535272` 已将产品 CI 改为仅手动触发，`7dd535af0e` 记录移除过时自动合入门禁；
实际 GitHub main protection 的 required_status_checks 为 null。此政策不是本批为通过门禁而改动。
将当前 main 无冲突合入，仅复验受影响的更新/反馈真实接线，不手动补跑产品完整矩阵。
本次任务相对当前 main 的 diff 不包含其他模块修复。

发布工具 13 项、AndroidEntryTest 9 项在集成后重新通过。产品验证使用协调器
`update-feedback-main-integration`，限定 Android AboutFeedback / ForkAppUpdateWiring /
ForkAppUpdateDownload / ForkAppUpdateDelivery / ForkAppUpdateUiWiring，以及 Desktop AboutFeedback /
AboutUpdate / DesktopForkUpdate / DesktopSettingsAboutExtensionAccessibility；结果以最终记录为准。
共享更新协议和摘要代码未被主分支修改，复用原有双端契约证据。

`update-feedback-main-integration` 在 Android 单元测试编译阶段被主分支的 `PagerRippleTest.kt`
未解析引用阻塞，未运行任何当轮产品测试。遵照范围要求未修该阅读器文件。
第一次外部 init 配置遇到 buildSrc 无 :app 工程而启动失败；修正工程判断后的
`update-feedback-focused-integration2` 继续执行 Desktop，Android sourceSet 排除未生效，
仍为同一编译错误。以上均不作为 Android 通过证据，也不读取旧 XML 冒充新结果。

Desktop 在该轮实际完成并通过 26 项：AboutFeedback 3、AboutUpdate 7、DesktopForkUpdate 10、
AboutAccessibility 5、既有资源身份 About 用例 1。资源身份测试两处夹具显式指定
`manualOnlyUpdates = false`，保留既有下载/安装流程的双语断言；本 fork 的默认发布页模式
由独立 production 接线测试覆盖，没有改动旧安装能力或放宽断言。

Android 最后使用未提交的 `.gradle-coordinator/update-feedback-focused.init.gradle`，
对真实 `:app:compileReleaseUnitTestKotlin` 配置 `exclude('**/PagerRippleTest.kt')`，
仅从本次测试编译输入移除该无关测试文件，生产源码、依赖和被选更新/反馈测试不变。
此局部验证配置不用于正式候选、CI 或全量通过声明；未修改阅读器文件或提交排除规则。

`update-feedback-android-integration` 于 2026-10-15 22:45 CST 实际完成，21 项全部通过：
AboutFeedback 3、Delivery 7、Download 5、UI Wiring 3、Preference/Checker Wiring 3。
生产任务编译通过，真实 Activity/Voyager、WorkManager、HTTP 下载与包身份检查均由对应测试执行。
与当日 Desktop 26 项、发布工具 13 项、构建入口 9 项及网站构建结果一起支持本批集成收口。
`git diff origin/main --check` 通过；本批不声称整个项目全量通过。
新的 Windows preview 及线上部署结果以 PR #3 的后续工作流/交付记录为准，
索引仍有意保持为空，不为完成部署伪造应用发布或升级候选。

## 实际分发与运行验收

用户在明确得知尚缺真实发布和端到端验收后要求继续，进入仅限更新与反馈的分发收口。
不处理其他模块 BUG，不重复产品完整矩阵；复用已执行的完整基线及本批相关 focused 补验证据，
无关失败仍保留，不称全量通过。Windows 经 `scripts/build-desktop.sh build-only` 生成正式目录和完整 ZIP，
通过生产扩展运行时与版本检查，不把旧 preview 改名冒充正式产物。

- Android 独立旧基线：`0.19.4-aex.104` / vc122，正式签名、R8/资源压缩，
  SHA-256 `9b7b449a166b3de096bb36749b40cf38bb6f6ce6790eba0c9f87aee210ee8a07`。
- Android 更新候选：`0.19.4-aex.105` / vc123，同一正式包名及既有证书，
  SHA-256 `e9e4c865a3810e8ce31aefed05227cc660ff774c1931e387782705c460b53484`。
- Windows 更新候选：`0.11.20.7.70ef0b5`，完整 ZIP 196108975 bytes，
  SHA-256 `970bbddd86f12a52f8cc5951163a5aa9b801baf31b36d0a5d26be1cdf7b1fa56`。
  `Final unpacked EXE:` 为 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.20.7.70ef0b5-unpacked/Mihon Desktop.exe`。
- 版本先核对既有候选已到 vc121，再依次分配 122、123；候选来源为 `70ef0b5bea` 加对应版本字段差异，
  实际 sourceDiff/productionInputs 与映射、签名、哈希均保存在各自 `artifact.json`，不冒充 clean source 构建。

专用 API36 模拟器 `mihon-update-feedback-20261015` / `emulator-5580`，无用户数据，使用独立存储目录。
vc122 实际启动、设置通知与安装权限、关于页、反馈预览、复制成功均已执行。
从真实系统剪贴板粘贴到未提交的浏览器输入框，文本与五项预览摘要一致。
浏览器原生打开 GitHub 登录页，return_to 精确指向本项目 `2_report_issue.yml` 表单，未携带摘要。
该模拟器无 GitHub 登录；未声称完成登录浏览器内的提交。
接收端另用维护者 CLI 显式提交同一摘要，自测 Issue #5 读回完全一致后关闭；未附日志或书库数据。

安装入口首次在安装后的 adb 抽取核验返回失败；随后独立 `adb pull` 同一已安装 APK 成功且哈希完全一致。
没有因此重装或修改入口脚本，不把首次入口失败报告为通过。
过程日志位于忽略目录 `.gradle-coordinator/update-feedback-{baseline,release}-candidate.log`、
`update-feedback-windows-delivery.log`，原生 UI XML/PNG 与独立窗口截图同目录保存。

GitHub `update-feedback-20261015` 草稿的两项真实资产已上传，服务端 digest/大小与上述本地文件一致。
本次 feed 仅登记这两项 testing 条目，稳定通道仍无此测试候选；先发布并通过 publisher 的服务端校验，
再合入部署 feed。上线后旧客户端发现、下载与原位升级结果在本次交付 PR 的后续运行记录中回填。
公共功能文档仍等待用户对具体候选验收，不将发布动作当作产品验收。

## 2026-10-16：线上更新验收及免账号反馈补充

PR #6 已合并为 `cf71cc47519a1ab248e54f411163d34c6ae8b195`，Pages 运行 `38851486813` 成功；
真实线上索引返回已发布的 Android/Windows 两条 testing 条目。
专用 API36 模拟器里的 vc122 经真实 About 检查发现 0.19.4-aex.105，应用下载后显示安装通知，
经系统“Update”确认升级至 vc123。“App installed”、升级后 About 版本及已安装 APK 抽取哈希
`e9e4c865a3810e8ce31aefed05227cc660ff774c1931e387782705c460b53484` 均确认；未使用 adb install 替代升级。
Windows 隔离 preview 0.11.20.6 的真实 About 显示 0.11.20.7 与发布说明/手动 GitHub 下载入口；
原生反馈弹窗、安全摘要及真实系统剪贴板内容一致性通过。实体机厂商行为与已登录浏览器提交未宣称验收。

随后用户明确要求最终用户无需 GitHub 账号，选择免费托管并完成维护者 Cloudflare OAuth。
本批以 `cf71cc4751` 为基线，保持更新协议及此前正式包不变；免账号反馈另需新客户端候选。
已发布的 2026-10-15 包仍是旧 GitHub 表单入口，不将新源码或服务部署当作旧包已具备免账号能力。

### 红绿及独立审查

- 服务生产 Worker + 真实 SQLite：首轮 13 项全红（初始 503 无持久化/配额/转交），后全绿；
  网页生产事件首轮 6 项红后绿，补公开文本隔离、流式请求上限和缺凭据保留期清理。
- 一轮独立审查复现合法 `[redacted]` 五行原生摘要被拒的 P2；服务与网页新增回归均先红，
  仅精确放行既有 sentinel 后通过。实施者及未实施的审查者分别复验 **24/24 PASS**。
  覆盖真实 SQL 并发每 IP/全局配额、幂等/冲突、验证码/Origin、缺配置、持久化、转交 claim、
  429 延期、500/超时不重投、30 天清理及真实表单同意/草稿/错误/双击/语言事件。
- 客户端共享 JVM 摘要契约新目的地断言先红 1/4；Desktop 实际挂载 About 的新按钮/焦点用例
  先红 2/3；Android 恢复旧行为后实际 Activity/About 红 2/3（新入口文案找不到），未用源码扫描。
- `anonymous-feedback-clients-green` 中共享 JVM 4、Android 共享 4、Android About 3、Desktop About 3
  全部通过。第一次根格式检查同时报告本批 CRLF/长行与无关 `PagerRippleTest.kt` 格式问题。
  仅修正本批文件；限定绝对路径的 `anonymous-feedback-clients-refactor` 最终 **BUILD SUCCESSFUL**，
  同一 focused 范围及相关格式检查通过。无关阅读器文件未修改，未称整个仓库格式/测试全绿。
- Android 测试编译沿用既有外部 init，仅排除未解决的无关 PagerRippleTest 编译输入；
  不提交该排除，不用于候选、不改变 production，不把组合证据称全量重跑通过。
- `npm --prefix website run docs:build` 成功，产物包含固定反馈入口；部署前真实线上入口为 404。
  Worker dry-run 成功（11.21 KiB/gzip 4.01 KiB）。没有运行完整模块/全量测试。

### 真实部署状态与未关闭项

已创建独立免费 D1、限定正式 hostname 的 Turnstile，以及 `mihon-feedback` Worker；
远端迁移 `0001_feedback.sql` 成功。Worker 部署版本 `004c7ec9-8041-4c3d-9ed6-ff0063b3b3c3`，
绑定真实 D1/静态资产并注册每五分钟 Cron。Turnstile/IP 哈希 secret 从内存直传 Cloudflare，
没有写入聊天、仓库或普通日志，没有复用广权限 gh token。

真实无 GitHub 登录的应用内浏览器已打开 Worker 网页，中文/英文切换保留虚构标题/描述，
公开同意初始未勾选。缺少 `GITHUB_TOKEN` 时提交禁用且显示暂不可用，符合 fail-closed；
不将这一步当作真实匿名提交成功。维护者专用 token、真实生产验证码/提交回执/D1与 GitHub 接收、
稳定入口 Pages 部署及新客户端候选交付仍待完成。具体候选用户验收和公共宣传仍未获批准。

本批超过 8 个文件/400 行的主体是同一匿名接收协议、原子持久化、表单及行为回归，
不按文件拆开不可独立验收的上下文；风险由一次独立审查及一次限定修复复验覆盖。

### 维护者配置后真实接收验收与候选

维护者自行保存专用 GITHUB_TOKEN 后，公开 config 返回 accepting=true；没有读取或输出该令牌。
应用内浏览器以无 GitHub 登录的路径填写虚构内容，维护者亲自完成生产 Turnstile 后由网页显式提交。
真实回执与 D1 中提交 `a9903328-19f6-43bf-bdf6-2e109012cb38` 一致；正常 Cron 将记录从 PENDING
变为 SENT，并创建 [自测 Issue #8](https://github.com/AltairCardinal/mihon-desktop/issues/8)。
标题、描述、五行摘要和隐藏 UUID 标记读回一致后关闭自测 Issue；未以 CLI 创建或直接插库替代匿名提交。
成功回执截图保存于 `.gradle-coordinator/anonymous-feedback-receipt.png`。

PR #7 已合并为 `5c1cbe5b3290934a9e6edbaa4bbf40aeb0eda5cb`；合并后服务 CI `38856724263`、
Pages `38856724354` 成功。真实浏览器发现固定入口 `meta name=refresh` 未触发跳转，
停留在中转页（可点击后备链接）；据此最小修为 `http-equiv=refresh`，网站构建通过，待本交付合入后复验实际跳转。
这是反馈入口自身的修复，不涉及其他模块。

- Windows 日常体验包：`app-desktop/artifacts/preview/windows/20261016T053132Z-9c923eca/Mihon-Desktop-0.11.20.7.37f7e50-unpacked/Mihon Desktop.exe`。
  来自 clean `37f7e50d61`，统一 `preview` 入口构建成功，production runtime PASS；native interaction NOT_RUN，
  不分配正式 Desktop 版本，不把体验包登记为正式更新资产。
- Android 正式签名候选：`app/artifacts/android/0.19.4-aex.106-vc124-37f7e50d61-release/Mihon-Fork-0.19.4-aex.106-vc124-release-universal.apk`。
  核对两个工作目录现有正式候选最高 vc123 后分配 vc124；来源为 `37f7e50d61` 加机械版本字段差异。
  原包名与签名连续，R8/资源压缩、脚本候选验证通过；SHA-256
  `b911bf1efdd7fd42086e5b296fcf8328802000ccf008bc37731a33bc0e4059f3`。
  正式候选没有使用测试编译排除 init。专用 API36 模拟器已由统一 install 命令安装并核验 vc124；
  Android 原生新入口、固定入口修复部署及测试通道登记结果以下文/交付 PR 的实际结果为准。

当前完成证据限反馈与更新范围，未宣称其他模块修复或全量通过；具体候选仍待用户自行验收。
