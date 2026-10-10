# 已审核同步交互的原生实现与验收

## 范围与固定验收

基线：`8581ccc7ea267326007e2b50228cd68e6ee0cfc2`。用户批准 `docs/prototypes/multi-device-sync/README.md` 中 2026-10-03 R1–R3/P1–P2 交互，并要求正式 Android APK 更新到既有下载站点。

- SOURCE：共享 `SyncPanelController`、安全 `SyncSetupStorage`、持久 `SyncRunStore` 和 `SyncPanelContent`；Android/Desktop 保留现有入口、导航及平台容器。
- PROJECT_POLICY：授权事实和空间事实独立；网络失败保留最后有效结论；检查按钮显示忙碌和最近检查结果，主操作根据空间问题与未完成步骤推荐。
- PROJECT_POLICY：观察记录必须绑定连接与凭据版本；损坏或未来格式不能解除恢复门禁。未完成更换复用已持久化 intent；关闭清除密码，取消需要确认且不得回滚已提交连接。
- PROJECT_POLICY：统计阶段显示不确定进度、隐藏已用和剩余时间；冻结计划才从零计时，总数保持固定。统计时间和暂停时间排除，重开/进程重建后保持基准。
- PROJECT_POLICY：暂停/继续使用卡片右下角的带图标 tonal 按钮、48dp 点击区域；同一任务只有一个操作。独立批量任务保留自己的暂停。
- HTML_ADAPTER：双端/单端选择器仅属于原型审阅工具，不新增原生业务设置。原型与 MockWebServer 均不能代表真实 GitHub 账号验收。

## 执行与边界

两个实施代理分担恢复流程和计时/原生呈现；主代理统一协调 Gradle、补充翻译、独立核对安全边界、正式构建与 Sites 发布。按功能红绿验证，再对最终 diff 执行适用验证；不安装或操作用户平板。

本批次涉及共享状态、数据库迁移、恢复呈现及跨平台契约，超过文件数量提示仍为同一用户可验收功能，不进行无关重构。仅新增安全展示观察，不建立第二套同步服务，不缓存验证码或明文密码。

## 验证记录

- 初始红：`sync-approved-red`。四项真实 presentation 测试因统计计时错误（预期 0，实际 50）和缺少新统计轨道/授权事实而失败。data 测试遇到缺少 `assertNull` import，属于测试编译问题，不作为行为红证据；补齐后单独执行 data 红。
- `sync-approved-data-red`：17 项执行、8 项正确失败；9 项既有恢复边界通过。新增迁移与计时基准分别预期 1000/51000、实际 null；授权事实、最近检查和待继续步骤缺失产生正确失败。
- `sync-approved-401-red`：真实 `/user` 401 后旧确认仍保留，正确失败；后续显式清除。
- `sync-approved-review-red2`：60 项存储/迁移通过，12 项 presenter 和统计 UI 通过；恢复排序、无效数据主操作和保留旧授权事实三项正确失败。未完成更换的只读检查发现隐式取消，正确失败并移除。
- `sync-approved-recovery-green`：共享 JVM 恢复契约全部通过，含 HTTP 错误/畸形返回、独立授权事实、账号预验证、绑定/凭据版本隔离、观察记录损坏、关闭重建和明确取消。

## 独立核对与长期维护

主代理核对生产存储、controller wiring 和原生离屏渲染。检查指出的方法页固定排序、数据异常不应推荐替换、失败操作不得遮蔽已确认授权、401 清除及只读检查不得取消 intent 均已加入真实行为契约并修复。观察记录不参与门禁决定；只有真实空间检查成功才更新既有安全门禁。

Schema 42 通过 `41.sqm` 为既有 pause clock 增加计划开始及统计前累计暂停基准。新运行在 `freezePlan` 的同一事务内固定总量与计时；读取后的显示扣除计划之后的暂停。升级前已冻结运行没有统计完成的历史时间，保留原 `created_at` 基准，不伪造从零重新开始。旧统计未完成记录保持未计时。

只有 `PREPARING` 可取消更换；`ACTIVATING` 或已提交连接不可回滚。取消只归档原有 intent，保留本机数据、旧连接与未应用决定。验证码和密码不持久化，关闭后的浏览器授权步骤视为中断，需要显式管理授权重新开始；已确认授权与最近检查的安全事实保留。

## 最终回归与定向修复

- `sync-approved-ui-green`：128 项真实 presentation 测试通过；`sync-approved-format6` 格式通过。离屏 Compose 图像核对统计、恢复排序与卡片按钮，不以 HTML 代替原生呈现。
- `sync-approved-final-run`：完整适用矩阵执行一次。Android Release 677 项（7 跳过）、Desktop JVM 3236 项（2 跳过）、domain 568 项、core/common 56 项、test-desktop 52 项通过；data JVM/Android 和 presentation 暴露旧测试样本与新增观察读取兼容问题，未将该失败运行记为全绿。
- 修复停用/旧格式连接的观察读取保护；将观察写入失败保留为既有 STORAGE 恢复反馈；更新原有更换契约，检查不再隐式取消，明确取消才解除 pending。Creator 16 的旧数据库构造先移除新增 pause clock，保留真实迁移断言。终态样本通过真实 claim/freezePlan 建立固定总量，不伪造开始时间。
- `sync-approved-repair-final`：原回归失败路径定向复验已通过；仍发现诊断快照单次 gate 被后台刷新消费，以及 R05 新运行未冻结计划的两项样本问题。诊断 gate 仅限定实际 capture 协程，仍保留 identity-changing 的 INCONSISTENT 与脱敏断言；R05 保留断线重试、禁止重复新轮次等全部断言。

- `sync-approved-fixture-green`：JVM/Android Release/Debug 的完整 SyncPanelStorageContract 与真实终态集成测试全部通过，格式通过；保持原始失败断言。没有重复完整矩阵。

## 正式构建

Windows `build-desktop.sh` 已分配 0.11.19.79.8581ccc；脚本的 PowerShell `-File` 转交在本机约 30 秒退出且没有编译输出。明确结束各启动进程后，以 `-Command` 调用其原有 `scripts/build-windows.ps1 -VersionAllocated -ExpectedVersion 0.11.19.79.8581ccc` 恢复同一版本构建，仍通过 Gradle 协调器串行执行，不直接替代其打包或运行验收步骤。未将这些启动失败作为测试执行或测试通过证据。

`sync-approved-windows-command` 已通过完整 Desktop JVM 测试、正式未打包构建及 production runtime/真实扩展安装链路验收。实际产物：

- `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.79.8581ccc-unpacked/Mihon Desktop.exe`
- `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.79.8581ccc-windows.zip`，SHA-256 `fe7d7c30845900ace047145f74db0ae83990651ccc01c32c295a60e29c824203`。

Android `scripts/build-android.py candidate` 与独立 `verify` 均通过。正式身份 `app.mihon.desktop.fork`，版本 `0.19.4-aex.30` / code `48`，沿用证书 SHA-256 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`；R8、资源压缩及 v2/v3 签名已核验。

- APK：`app/artifacts/android/0.19.4-aex.30-vc48-8581ccc7ea-release/Mihon-Fork-0.19.4-aex.30-vc48-release-universal.apk`
- SHA-256：`005016accc52bb5b0b18f73d23e0b0fb574873d9040b0cbbc8675f6f24651b12`。
- productionInputsSha256：`8123ddd98d9e16ebf8d3135af92ee81813dd5b8af4af613c65f22e785298b135`，包含本批次冻结的未提交生产 diff 与迁移输入。构建后仅补充证据和发布说明，不修改生产代码。
- 四个版本化下载分片逐个写后校验，总大小 `68726667` 字节；重组 SHA-256 与正式 APK 一致。下载脚本保持既有安卓浏览器两次用户手势：准备下载后点击“保存 APK”。

未安装、操作用户实体设备；正式候选身份与签名验收不等同于 Android 实机运行、升级或真实 GitHub 账号验收。远程用户执行最终验收。

## Sites 发布

使用既有下载站点与原访问权限，不建立新站点。源提交 `a59c77c5a202920227c4cc190b3261647c6a1f54`，发布 `appgdep_6ac103aee250819184db5c682dbff9db`，服务端结果 `succeeded`。

下载入口：<https://mihon-apk-sync-recovery.windy-lover-ds.chatgpt.site>。页面、版本化分片、整包校验值与正式候选一致；不更改原有两次手势下载脚本。固定版本下载页不添加自动更新任务。

## 远程验收

1. 原站点 → 下载 APK → 准备完毕后保存 APK → 在浏览器下载列表打开安装，版本显示 0.19.4-aex.30。
2. 空间恢复页面 → 检查或授权 → 主操作按实际空间问题显示；检查显示忙碌/时间，已确认授权显示管理授权；关闭重开不丢失安全事实。网络失败与明确 401 按不同边界反馈。
3. 开始同步 → 统计阶段等待条，无已用/剩余标签；固定总量后显示完成数/总量并从 00:00 计时。
4. 右下暂停 → 进度和时间停止；继续 → 保留已完成数量，已用时间排除暂停。
5. 未完成空间更换 → 继续创建/连接；统计或检查不隐式取消。明确取消需确认且只允许 PREPARING，保留原连接和本机数据。


## 2026-10-04 时间文案微调

PROJECT_POLICY：本轮仅修改共享进度卡片时间行。计划固定但剩余估时未知时仅显示已有“已用”文案；取得有效 wholeEta 后追加剩余估时，估时失效时再次隐藏。统计阶段仍不显示时间，暂停计时及布局高度不变。不新增估时算法或状态持久化。

真实 Compose D01 通过虚拟时钟和真实呈现验证未知 → 有效 → 过期估时；红测因旧界面缺少仅已用文案失败。仅执行受影响 SyncPanelContentTest 与格式检查，不重跑完整产品矩阵。本轮不构建或发布新 APK，云端 0.19.4-aex.30 不包含此次微调。

验证结果：`sync-eta-label-green` 的 115 项中 114 项通过（含 D01）；误加到无计划 D10 的无关时间断言已撤销。`sync-eta-label-final-format` 重验 D01/D10 及格式全部通过；生产变更在首次绿实现后未改变。`git diff --check` 通过。

## 2026-10-04 补交 Android 正式候选

用户要求构建供远程验收。本轮复用 4cc8be426e 的行为与格式证据，不重复功能测试；版本分配为 0.19.4-aex.31 / code 49。统一 candidate 构建和独立 verify 通过，沿用原发布证书，v2/v3 签名、R8、资源压缩已核验。

- 正式 APK：`app/artifacts/android/0.19.4-aex.31-vc49-4cc8be426e-release/Mihon-Fork-0.19.4-aex.31-vc49-release-universal.apk`。
- SHA-256：`28d913b31373c2e09a57526c5bdcf5636e2365eddc8833dd4caeb813b20d5780`。
- productionInputsSha256：`2c67746a0c0d1a1778e6903ba0daa65d58b3814849a72ae76e45201d39783b92`。
- 四个版本化分片写后与整包重组校验通过，总大小 68726667 字节。保留原下载脚本和两次浏览器手势，不安装或操作用户设备。

Sites 已发布成功：源提交 `12ab62cf68a00ceb8f00f2bf2dd91add785274b1`，deployment `appgdep_6ac12de06b4c81919297056cf8c5ed34`，结果 `succeeded`；既有下载入口 <https://mihon-apk-sync-recovery.windy-lover-ds.chatgpt.site> 和访问权限保持一致。
