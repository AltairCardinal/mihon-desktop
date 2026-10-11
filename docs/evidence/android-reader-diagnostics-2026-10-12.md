# Android 阅读器本地诊断

目的：为连续阅读中的单双页错配、已下载页面转圈提供跨 logcat 窗口的证据。本轮只观察现有状态和回调，不改变配对、页面票据、加载或进度决策，不上传日志。

## 记录与取证

日志优先写入应用外部专属目录 `Android/data/<applicationId>/files/reader-diagnostics/`。使用 `adb -s <serial> pull /sdcard/Android/data/<applicationId>/files/reader-diagnostics <本地目录>` 拉取整个目录。系统返回外部目录为空时退回内部 `files/reader-diagnostics/`；日志和 logcat 的 `storage` 事件标记 INTERNAL/EXTERNAL。外部目录已选定但创建/写入失败时只报告 write_failure，不自动迁移到内部。Release 内部目录通常不能被普通 adb shell 读取，此时需保留安装和应用数据，不宣称日志已可取出。

复现后无需立即退出应用；保留出错前后的操作，不清除应用数据、不卸载。记录出错的大致时间和页面显示页码，再拉取所有轮转文件。`reader-0.log` 最新，编号越大越旧。重新进入阅读器、重启进程不会清空旧文件。

每条业务事件包含进程随机 ID、阅读会话 ID、版本号、墙钟和单调时钟、章节本地 ID 与 `sourceIndex`。`sourceIndex` 从 **0** 开始；界面页码通常从 1 开始，不能直接等同。章节 ID 不是漫画标题。未知章节/页面用 -1。

holder 加载和配对诊断事件额外携带进程内唯一的 `holder` 序号，重绑不变。应使用 process/session/chapter/sourceIndex/holder/generation 关联加载链，结合票据 context/serial 分辨同一页的新旧 holder 与旧回调。

事件覆盖会话起止、原生选择/导航/空闲、章节与显示集合、holder 绑定/票据变化、Page 状态、本地 loader 类型、读流起止、解码开始/ready、显示拒绝原因、首次 drawn/进度圈移除，以及错误类、取消和卸载。已有 decoded_fact/prepare/retained_lock 的事实、邻页、建议组、锁定组继续保留。Ready 表示输入可用，不等于像素已绘制；诊断时须追到 drawn。

详细导航及加载/拒画/首帧链限定当前双页阅读器 `DualPageR2LPagerViewer` / `DualPagerPageHolder`（包括双页模式中的单张显示）。普通单页 Pager 与 Webtoon 不具备本轮新增的详细导航/加载链；Activity 会话与页面选择仍有记录。日志不能据此诊断其他阅读模式的解码等待。

## 边界与失败

- 专用线程落盘；有界 512 条队列，调用方只尝试入队，不等待磁盘。不订阅完整 logcat。
- 最多 4 个、每个 4 MiB，总量最多 16 MiB；单条最多 8191 UTF-8 字节，截断不拆分多字节字符。容量不足时丢弃最旧文件；单条换行不能注入伪事件。
- 队列饱和时丢弃新事件，并在后续写入记录 dropped 数量。写失败隔离于阅读流程，后续事件继续尝试，成功后报告累计 write_failure。不可恢复的存储错误可能导致取证缺口。
- 只记录受控状态、数值及诊断字段；不写标题、URL、令牌、图片内容、文件路径或异常 message。相同绘制状态去重，不每帧写盘。
- 应用被强杀时队列尾部可能未落盘；已追加完成的文件保留。诊断不是崩溃恢复事务，也不能凭缺少 drawn 单独断定配对错误。

## 验证与交付

Focused TDD：`ReaderDiagnosticSinkTest` 对真实文件验证重启/轮转、队列饱和、UTF-8/字段安全、会话身份和失败恢复；`DualPageViewportZoomTest.diagnosticFileReceivesRealLoadingAndDrawCallbacks` 通过真实 holder、原生图片回调与文件 sink 验证加载、拒画、卸载重绑和首次绘制事件，并断言重复帧不会重复 drawn。

红证据：`.gradle-coordinator/reader-diag-red.log` 的空实现断言失败包括“Diagnostic events must survive process restart”；原生接线首次失败保留在 `reader-diag-wiring-red.log`（得到失败后取消通配符造成的冗余发现/重试）。双页跳页接线的确定失败保存在 `reader-diag-navigation-red.xml`：“The real dual viewer jump must reach the file”。绿基线 `reader-diag-green2.log` 为 6 个 sink 用例和 1 个原生接线用例通过。

最终局部证据：`reader-diag-final-green.log` 与 `reader-diag-recheck.log` 同 7 项通过，后者强制重跑且定向 Spotless Check 通过。随后为区分并存的新旧 holder，增加 holder 身份断言，`reader-diag-holder-red.xml` 因“Loading and presentation must identify their holder”失败；最小接线后 `reader-diag-holder-green.log` 同 7 项全部通过，定向 Spotless Apply/Check 通过。未运行完整模块测试或完整发布矩阵；未修改算法，不能把诊断交付称为错配或转圈已修复。

本批超过 8 个文件，仍是单一诊断链：应用初始化、Activity 会话、实际双页 Viewer/holder 观察点、文件写入、真实接线测试与本说明必须一起交付。没有修改配对算法、数据格式或阅读决策，没有新增网络请求、权限或 UI 设置页。

## 本次候选与设备安装

2026-10-12 使用 `python scripts/build-android.py candidate --offline` 构建签名 Release `0.19.4-aex.59` / versionCode 77，构建日志 `.gradle-coordinator/android-candidate.log` 为 PASS。产物位于 `app/artifacts/android/0.19.4-aex.59-vc77-2d5e16442a-release/Mihon-Fork-0.19.4-aex.59-vc77-release-universal.apk`，附带 `artifact.json` 和 R8 mapping。

- APK SHA-256：`0dd2db13c305835f6cedd1912d538fcc65fa7b86339eefdad58db68b226c9eb5`。
- 基线提交：`2d5e16442ac8c5c5ffd775e110d91e2ba42c1bd5`；构建时包含本批未提交修改，production 输入指纹为 `df3d866d432d117e50cf0b352694f16f8788be1af0eec6c8baf9c84144ec4188`，详细差异指纹见产物清单。
- 主代理独立核查异步写入、固定 session/holder、重复绘制去重及实际双页接线；反馈项已在上述 focused 范围验证。Release DEX 中确认诊断目录与读取、解码、失败、卸载事件标记保留；静态检查不替代运行验收。
- 使用统一 `install --artifact ... --serial ...` 在用户指定的 PCE-W30 真机从 versionCode 76 同签名覆盖升级至 77；安装工具重新拉取已安装 APK，确认身份、版本、证书和 SHA-256 与候选一致。
- 安装前后 `firstInstallTime` 同为 `2026-10-04 01:37:07`；新 `lastUpdateTime` 为 `2026-10-12 03:45:42`。原始设备记录仅保存在忽略目录 `.gradle-coordinator/reader-diagnostics-20261012/`。
- 未代操作用户真机应用。安装已验证；真机启动、日志目录可读取性、实际故障复现及故障日志提取仍待用户自行阅读后核验，不能声称本轮已在真机捕获故障或修复原问题。
