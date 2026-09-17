# 收藏缺失插件建议安装：实施交接

更新时间：2026-09-17。本文由用户明确要求在重试失败后生成，用于接续工作，不代表功能验收完成。

**最新恢复状态（EIS-04 已完成，EIS-05 待实施）**：EIS-03 提交为 `e4d5bedde19513500820f6e636505659a4433531`；EIS-04 的 Android 批次 UI/DI、系统确认/卸载/回滚归属、Shizuku 两阶段协议及进程恢复已实现并通过同批独立审查。最终 `eis04-focused-final` PASSED，19 suites / 219 项全零失败/错误/跳过，Android instrumentation 编译通过；`eis04-readonly-format` 在原规则、无 IdeHook 下通过，diff-check 通过。Legacy/PackageInstaller 连续、取消继续、提交后停止，PRIVATE 跨进程，Shizuku 可用/无服务/授权失效，后台恢复、配置重建及原 PI session 进程退出恢复均有真实设备证据。完整证据和维护边界集中在 roadmap 第9节；本批 checkoff 随同功能提交，不另建状态提交。下一步 EIS-05 全量/Test Mode/正式三平台发布，尚未执行。验证使用协调器 `run`＋`--offline`，未修改安全配置。后文旧 EIS-03 阻塞为历史。

## 1. 目标与唯一执行工作区

- 原目标：完整实现 [roadmap](roadmap/2026-09-16-extension-install-suggestions-roadmap.md)，每完成一个批次才勾选；需求依据为 [requirements](2026-09-16-extension-install-suggestions-requirements.md)。不可将目标缩减为仅共享逻辑或 Desktop。
- **实施目录：`D:/Shell/Github/mihon-eis`**；分支：`codex/extension-install-suggestions`。
- 原目录 `D:/Shell/Github/mihon` 有其他任务的未提交改动。本次再次只读核对，包含源权限、导航、AndroidManifest、AppModule、ExtensionManager、i18n 等；不得覆盖、清理、回滚或混入本专项。
- EIS-03 代码、测试及 roadmap 记录已纳入本次功能提交；继续使用实施目录，接续前检查 `git status --short` 和 HEAD。
- 遵守实际 AGENTS.md：中文交流、严格红绿重构、真实 production wiring 测试、重型 Gradle 串行协调、功能提交前独立审查。checkbox 表示实现、审查、验证、提交全部完成。

## 2. 已完成并提交

| 批次 | 提交 | 内容与有效证据 |
|---|---|---|
| EIS-01 | `c1d733810e79acf8a9c4e06f9ca8abca93a2d550` | 共享识别、Android 系统/私有库存、Desktop 最终产物库存、双端 Flow/DI 与真实 SQL 契约；最终 focused 115 项通过，详见 roadmap 第 9 节 |
| EIS-02 | `6b34ea3a606a233ddd715ef65485cae401a61bef` | 双端建议区、单项安装、来源/网站选择、忽略/撤销/折叠、本地偏好及真实备份/同步隔离；有效 XML 合计 107 项通过，窄屏离屏检查已完成，详见 roadmap |

EIS-03 基线为 `e4d5bedde19513500820f6e636505659a4433531`；EIS-04 已验收并随本文作同批功能提交（以 Git 日志为准）。仅 EIS-05 未勾选；没有本专项最终正式发布产物。

## 3. 历史重试与当时阻塞（已恢复）

用户已多次明确授权执行 focused 验证，最后要求“再次尝试，如果失败的话写入交接文档”。本轮先核对 `eis03-takeover-green` 为 `NOT_STARTED`，随后重试原命令一次，仍在工具的 CreateProcess 阶段被拒绝：

```text
exec_command failed: CreateProcess ... rejected: blocked by policy
```

没有 Gradle 进程启动，也没有新的测试通过证据。以下为最初排查记录，随后用户补充了根因与最小复现（见本节后半部分）：

- 已检查两个用户级 `default.rules`，未发现禁止 Gradle 的显式条目；精简命令的本地 `execpolicy check` 返回 `matchedRules: []`，不能据此推断完整调用在所有策略层都被允许。
- 会话显示 `danger-full-access` / `approval_policy=never`；此前将通用拒绝直接称为 Auto-review 拒绝不准确，roadmap 已纠正。
- 没有修改安全配置，没有换 shell、包装脚本或其他间接方式规避拒绝。后续应在环境正常允许执行后恢复，不能把用户再次回复批准当成环境已经放行的证据。
- 原实施子代理 `/root/eis_implementation` 因平台用量限制进入 errored。后续主代理接管了少量已有红测试的实现；其变更尚缺独立审查。此前首个任务簇确已在主代理详细实现前委派，由原代理主要实现和验证 EIS-01/02 及 EIS-03 前半部分。

### 后续根因定位（用户提供）

用户报告已结合 Codex `0.154.0-alpha.6.2` 对应版本源码与纯文本输出命令查明：Windows 命令安全检查扫描整段 PowerShell 参数，将 `start` 与 HTTP URL 同时出现误判为危险启动操作，未准确区分程序参数。原验证命令同时包含协调器参数 `start` 与代理地址 `http://127.0.0.1:10808`；`approval_policy=never` 随后将该判定直接转为拒绝，完全文件访问权限也不能避免。

用户提供的最小复现结果：

| 纯文本输出命令 | 实测结果 |
|---|---|
| `Write-Output 'start'` | 成功 |
| `Write-Output 'status' 'http://127.0.0.1:10808'` | 成功 |
| `Write-Output 'start' 'http://127.0.0.1:10808'` | `blocked by policy` |

这解释了为什么命令在 Gradle 启动前失败：不是 Gradle、SDK、代理连接失败，也不是仓库构建 hook 或用户规则明确禁止。根治方向为修正 Codex 参数识别；用户也可通过正式审批配置处理误报。本文只归档用户提供的结论与复现，本轮未独立重跑或读取所述版本源码，消息未附源码文件/行号或链接，故不虚构引用。

本轮没有修改安全配置、变换命令绕过检查或恢复功能验证。根因已定位与执行限制已解除是不同状态；须在修正或正式审批生效后重新检查协调器并继续 focused 验证。EIS-03～05 仍未完成。

## 4. EIS-03 已验收实现位置

| 范围 | 当前文件与状态 |
|---|---|
| 共享仲裁 | `domain/src/commonMain/kotlin/mihon/domain/extension/service/ExtensionInstallArbiter.kt`：同包 lease、事务 ID、精确 owner 释放、停止/提交原子边界、进度及同步卸载预留 |
| 共享批次 | `domain/src/commonMain/kotlin/mihon/domain/extension/suggestion/ExtensionSuggestionBatchController.kt`：固定完整产物清单、串行安装、跳过/失败/暂停、恢复、停止；失败项重试、停止后不恢复未提交后续项已验证 |
| 提交门 | 同目录 service 下 `ExtensionInstallPort.kt`、`ExtensionInstallCoordinator.kt` 增加 `beforeCommit`；提交门拒绝时仅清理，不错误回滚未提交产物 |
| Desktop 安装 | `app-desktop/.../extension/DesktopExtensionApi.kt` 在读取信任 metadata 前预留；信任确认持有 lease；Flow 单次消费及异常释放。manager/UiPorts/presentation port 接入同一 arbiter |
| Desktop 状态 | `app-desktop/.../ui/extension/ExtensionsScreenModel.kt`：应用级 batch、资格复核、真实 API 安装、信任队列关联 batch transactionId、等待真实确认 job 清理 |
| Desktop 页面 | 新增 `ExtensionSuggestionBatchSection.kt` 并接入 `ExtensionListScreen.kt`：全部/搜索匹配安装、固定清单确认、折叠外计数与停止按钮。完整逐项结果、暂停后显式重确认、失败重试与来源冲突反馈均已通过真实离屏验证 |
| Android 入口 | `app/.../extension/ExtensionManager.kt`、`util/ExtensionInstaller.kt` 在普通安装/更新入口仲裁，真实安装 job 完成才释放；系统卸载仅派发阶段互斥 |
| Android 页面 | `app/.../ui/browse/extension/ExtensionsScreenModel.kt` 捕获同步 Busy 并清理自身请求标记；普通页面与建议区共享真实进度，Busy 不覆盖 owner；同步异常、清理和重试已验证 |
| 测试/资源 | domain 共享行为测试、Desktop API/ScreenModel/离屏测试、Android manager/UI wiring；若干旧测试增加真实 arbiter fixture。base/简中/繁中新增 batch 文案，XML 已解析核验，不等于资源编译通过 |

`...` 表示对应平台既有源码包路径；精确变更列表使用 `git status --short`。不要误删未跟踪的新 production/test 文件。

## 5. 历史测试证据与当时接续顺序（最终结果见顶部）

协调器记录都位于实施目录 `.gradle-coordinator/<key>.json` 和同名 `.log`，不受 Git 提交保护。原始 XML 在各模块 `build/test-results/`，会被之后测试覆盖。

| key | 真实结论 |
|---|---|
| `eis03-shared-green` | 当时的共享核心通过；不能覆盖后续修改 |
| `eis03-resume-api-green` | 当时恢复及 Desktop API focused 通过；不是 EIS-03 整批通过 |
| `eis03-boundary-red` | metadata 未受预留保护、同步确认异常泄露、恢复产物与已成功源冲突的有效红；取红临时回退已恢复 |
| `eis03-android-arbitration-red` | 真实 manager 安装与更新造成低层两次请求的有效红 |
| `eis03-platform-green` | **整轮 FAILED**，Desktop fixture 缺换行导致编译错误；部分共享/Android XML 通过不能代表整轮成功 |
| `eis03-ui-boundary-red` | **FAILED / exit 1，2m16s**。停止后仍可恢复、失败重试占位实现、Android 未捕获 Busy、Desktop 缺确认入口；Android XML 的 suppressed exception 明确包含 `ExtensionInstallBusy` |
| `eis03-takeover-green` | **NOT_STARTED**，本轮重试仍被策略拒绝；没有对应绿证据 |

主代理在最后一轮红之后实现了共享停止/重试、Android Busy 清理及 Desktop 确认初版，均未通过后续编译/行为测试。最后又补写 `ExtensionSuggestionRenderedTest.batch shows each result and confirms only failed items for retry`，**尚未运行**，也尚未实施该测试对应的完整结果/重试 UI。不得把未运行的测试称为有效红。

以下仅归档当时被拒绝的命令，不再作为接续命令；当前采用顶部说明的 foreground/offline 验证：

```powershell
Set-Location -LiteralPath 'D:\Shell\Github\mihon-eis'
$env:PYTHONUTF8='1'
$env:PYTHONIOENCODING='utf-8'
$env:PYTHONDONTWRITEBYTECODE='1'
$ErrorActionPreference='Stop'
$env:ANDROID_HOME='D:\Android\Sdk'
$env:ANDROID_SDK_ROOT='D:\Android\Sdk'
$env:HTTP_PROXY='http://127.0.0.1:10808'
$env:HTTPS_PROXY='http://127.0.0.1:10808'
$env:NO_PROXY='localhost,127.0.0.1'
python scripts/gradle-coordinator.py status --key eis03-takeover-green
python scripts/gradle-coordinator.py start --key eis03-takeover-green -- gradlew.bat :domain:jvmTest --tests '*ExtensionSuggestionBatchControllerTest' :app:testReleaseUnitTest --tests '*ExtensionPresentationWiringTest.another manager entry*' --continue --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 -Dhttp.nonProxyHosts=localhost|127.*' --no-daemon
python scripts/gradle-coordinator.py wait --key eis03-takeover-green --timeout-seconds 60
```

先读取 status；若已有 STARTING/RUNNING，省略 start 并等待同一任务。等待超时不是进程结束，不重复启动、不全局杀 Java。旧 PID 只供历史证据，不能按旧 PID 盲目操作。

接续步骤：

1. 完成上述已有红测试的绿/重构复验；随后运行 Desktop 已有确认离屏测试和新增结果/重试测试，确认真实失败原因，再实现余下 UI。
2. 检查 Android Busy 不覆盖其他入口进度、同步异常必清理、Explicit cancel/retry 不被退化。当前布尔状态还不等于用户能看到反馈。
3. 完成 Desktop 暂停、刷新后重新确认、结果、失败重试；清单冻结完整来源/包/版本，成功项保留，新建议不自动加入。英文数量文案也需区分单复数。
4. 完成已承诺的本地 HTTP fixture → production coordinator → 临时插件目录 → 真实 loader 安装闭环；覆盖签名/来源、回滚、取消后清理、晚到回调及同包跨 UI action 竞争。
5. 相关模块 focused/wiring/格式通过后，对尚未独立审查的接管代码和高风险仲裁边界做独立审查；不能由原实现者自称独立通过。之后才在同一 EIS-03 功能提交中勾选 roadmap。
6. 复用合适实施代理继续 EIS-04；EIS-03 接口验收前不让 Android 下游依赖未验收边界。最后按 roadmap EIS-05 完整测试和三平台正式发布验收。

## 6. EIS-04/05 不可遗漏的边界

- Android 系统卸载确认窗口仍缺异步 owner/结果释放，EIS-04 必须闭合“先等待系统卸载→后续同包安装”竞争；不能用超时猜测解锁。
- `util/ExtensionInstallActivity.kt` 的既有 onCreate 无重建 guard、onDestroy 删除 URI、启动异常只 toast 的路径已定位，必须用真实生命周期测试覆盖，而非只 JVM fake。
- Shizuku 回调须核对 `ShizukuInstaller.kt`、`IShellInterface.aidl`、`ShellInterface.kt` 的事务关联；不能让迟到回调推进新批次。Legacy/PackageInstaller 成功、取消、连续安装，私有安装重启，Shizuku 可用/不可用，均须真实平台验证。
- 历史环境准备：Android SDK `D:/Android/Sdk`；AVD `mihon-aex-api36` / `mihon-aex-api26`，曾对应 emulator-5580/5582。接续时重查设备身份，不据历史直接安装，不清用户数据，不覆盖官方包。fork 签名沿用仓库脚本，禁止把凭据写入文档。
- 2026-09-17 主代理重新核对 AVD 身份后，仅在 `emulator-5580`（`mihon-aex-api36`）安装官方 Shizuku APK；SHA-256 为 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`，本地包位于 `.gradle-coordinator/eis04-tools/`。按应用“View command”显示的已安装原生 `lib/x86_64/libshizuku.so` 入口启动；旧 `/sdcard/Android/data/moe.shizuku.privileged.api/start.sh` 不存在，不应复用旧命令。核验时服务 PID 为 28602，应用显示 `Shizuku is running`、`Version 13.5, adb`、`Authorized 0 applications`。重启后须重新检查服务与当前安装路径；后续须经正常授权流程验证 Mihon adapter，此环境准备不算 Shizuku 安装功能验收。`emulator-5582`（API 26）仍未安装，保留无服务对照；不操作另行连接的物理设备。
- 2026-09-17 最新包身份：API 36 的 `app.mihon.desktop.fork` 已为 versionCode 25 / `0.19.4-aex.7`，API 26 仍为 19 / `aex.1`。并行工作更新过 API 36；不得按当前工作树旧版本覆盖、降级或清数据。EIS-04 优先核对独立 debug/test 包，EIS-05 正式版本重新检查后递增。
- macOS 隔离目录曾建立于 `mbp-lan:/Users/altair/Github/mihon-eis`，分支 `codex/eis-release`，当时停在 EIS-02；尚未同步 EIS-03 未提交 diff，也未构建。重查 SSH、工作区和 HEAD，保护 Mac 原有脏目录。
- 最终全量 Android/Desktop 集合、Test Mode、Windows/macOS 运行验收尚未执行。正式桌面构建必须用 `scripts/build-desktop.sh`；最终 Windows 链接只用日志 `Final unpacked EXE:` 实际存在的路径。不得交付 tmp/build 中 EXE 冒充正式产物。

## 7. 文档与提交边界

本次 EIS-03 功能提交包含 production、测试、资源、roadmap checkoff 与本交接状态。EIS-03 独立验收已通过；不得把该批次完成解释为整个 roadmap 完成。下一步复用原实施代理开展 EIS-04，Android 异步卸载窗口、真实系统安装生命周期及最终三平台发布边界仍以第 6 节和 roadmap 为准。
