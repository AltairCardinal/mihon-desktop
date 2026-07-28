# Mihon Desktop 最终 parity 验证报告

## 当前结论

Windows、Android、macOS 与仓库内静态/自动化门禁已经完成。Windows 与 macOS 均从同一
source commit `19a55d7c27e854a9a5b8baa27871b6d8e1c3608c` 生成版本
`0.11.14.51.19a55d7`，且各自启动本轮固定产物完成最终运行验收。Task 19 的延期条件已经
解除，可以进入 Task 20 文档收口。

Windows 最终产物：

- source commit：`19a55d7c27e854a9a5b8baa27871b6d8e1c3608c`
- source tree：`de17112f4dd9afd28ee30996253d1dc2d3be3744`
- 未打包应用：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop`
- 固定 EXE：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`
- provenance：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop.task151-provenance.json`
- artifact：366 files，252,609,988 bytes，SHA-256
  `37bf05a1766657bf6a28416768d81741fa8820a0724934c44b7cdf44435c1125`

macOS 最终产物：

- source commit：`19a55d7c27e854a9a5b8baa27871b6d8e1c3608c`
- source tree：`de17112f4dd9afd28ee30996253d1dc2d3be3744`
- app bundle：`/Applications/Mihon Desktop.app`
- 固定可执行文件：`/Applications/Mihon Desktop.app/Contents/MacOS/Mihon Desktop`
- provenance：`/Applications/Mihon Desktop.app.task151-provenance.json`
- artifact：327 files，264,649,870 bytes，SHA-256
  `8e3edc6af159f94c2fbce4d3092a7ceb557a68c17369b50a0d490beffffef0f1`
- 架构：Mach-O x86_64；`Info.plist` short/build version 均为 `11.14.51`

## 固定原版权威

唯一固定原版为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。验证使用
`git show 6fbf6df...:<path>` 读取固定 blob，并由 final parity contract 对 manifest
中的 path/symbol/line 逐项校验。示例：

- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`
- fixed blob：`9828155df3a543165f8b52a71bda27653e90fc5c`

最终 `productSource` 覆盖 1,703 个 production input，digest 为
`12f05d2c9ec7d9b7b41f790afac32100d8540ba2b6a5f4bfbd10c70912996a78`。
当前 `app/` 只作为 fork 后 Android consumer；Desktop compatibility shim 没有被当作原版依据。

## 自动化结果

Windows/Android 完整门禁按计划只执行一次；macOS 仅执行构建脚本内置的 Desktop 验证及延期的
最终运行验收。

| 范围 | 结果 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| Android `testReleaseUnitTest` | 231/231 | 0 | 0 |
| Windows Desktop `jvmTest` | 2,293/2,296 | 0 | 3 |
| `test-desktop:test` | 28/28 | 0 | 0 |
| Windows Desktop smoke | 92/92 | 0 | 0 |
| macOS build-script Desktop `jvmTest` | 2,295/2,296 | 0 | 1 |
| macOS final Test Mode | 13/13 families；5/5 protections；64/64 capabilities | 0 | 0 |
| `finalParityAudit` | PASS | 0 | 0 |
| `spotlessCheck` | PASS | 0 | 0 |

Windows Desktop 的三个跳过均为明确环境边界：

- 两个 live extension compatibility case：本机没有 `~/.mihon/extensions` JAR；
- 一个 macOS JXA native-share case：当前主机是 Windows。

macOS 构建脚本内的唯一跳过项为 Windows-only unsigned installer verifier：
`DesktopUpdateInstallerTest.real Windows verifier fails closed for unsigned file`。macOS 首次
冷构建执行同一 2,296 项 Desktop 测试时，有一个
`DesktopSettingsAboutExtensionAccessibilityTest` Compose create callback 在 5 秒内未调度，
结果为 2,294 passed / 1 failed / 1 skipped。该精确 case 随即定向通过，随后同一 source
commit 的完整构建脚本复跑为 2,295 passed / 0 failed / 1 skipped；只有后一次全绿结果进入
artifact provenance，首次失败及恢复过程保留为环境抖动证据。

全量门禁首次运行发现并修复了两类问题：旧治理测试仍把历史状态当成当前状态；Android
extension repository replace 从可变 UI state 反查旧仓库，初始化/刷新竞争时会错误返回
`FINGERPRINT_CHANGED`。修复后 replace 显式接收冲突对话框中的 old/new repo，全量复跑通过。

## Windows 最终运行验收

`scripts/build-desktop.sh evidence` 对提交 `19a55d7c2` 运行 Desktop 全量测试、构建固定
未打包应用、验证运行时版本并封存 provenance。随后
`scripts/desktop-final-parity-test.sh` 以 `--headless` 启动该固定 EXE：

- 场景族：13/13 PASS；
- 永久保护：5/5 PASS；
- capability 映射：64/64，unmapped=0；
- 启动端口：`127.0.0.1:8080`；
- 没有打开系统浏览器或资源管理器。

13 个场景族覆盖 library、manga detail、browse/global search/source login、extensions、
reader、downloads、updates/upcoming、history、migration、backup/restore、
settings/platform、tracking、about。五项永久保护为 authors entry、upcoming、dual-page、
auto-scroll、APK-to-JAR。

补充 smoke 首次误由 WSL/JDK 17 启动，因 `ExecutorService` 在该 JDK 上不满足测试所需的
`AutoCloseable` 编译边界而失败；这不是产品或测试行为失败。使用
`C:\Program Files\Git\bin\bash.exe` 与项目 Windows JDK 21 重跑后 92/92 PASS。WSL
冷缓存尝试耗时约 20 分钟，后续 Windows 验收不得再使用 WSL Bash 启动 Gradle。

## macOS 最终运行验收

在 detached worktree `/private/tmp/mihon-parity-19a55d7c` 检出固定 source commit 后，以
`bash scripts/build-desktop.sh evidence` 调用项目构建脚本。脚本重新生成
`/private/tmp/mihon-dist/main/app/Mihon Desktop.app`，部署到
`/Applications/Mihon Desktop.app`，并封存与 Windows 完全一致的 source tree、
1,703 个 production inputs 和 product-source digest。独立
`task15-build-provenance.py verify` 对实际部署 bundle 通过。

唯一 final parity 入口通过环境变量指向 app bundle 内的真实可执行文件及 bundle provenance，
在 `127.0.0.1:18080` 启动 `--test-mode --headless`：

- 场景族：13/13 PASS；
- 永久保护：5/5 PASS；
- capability 映射：64/64，unmapped=0；
- 运行失败：0；
- 进程由 runner 精确关闭，没有遗留 health owner。

### macOS 权限与发布边界

macOS 14.8.4 的 TCC 日志显示，本轮未签名 bundle 在普通 `--headless` 启动时预检
`kTCCServiceListenEvent`；加入 `--test-mode` 后还会预检
`kTCCServiceScreenCapture`。仓库中唯一读取屏幕像素的 Mihon production-bundle 代码是
Test Mode 的 `POST /test/screenshot` →
`ScreenshotService.capture()` → `java.awt.Robot.createScreenCapture()`。final parity
客户端没有调用该端点，但 Test Mode 启动链仍触发 ScreenCapture 预检。系统拒绝权限
（`authValue=0`）后 13/13 场景仍全部通过，证明 parity 验收不依赖截图。

仓库没有麦克风或系统音频捕获实现；“屏幕与系统音频录制”是 macOS 对该权限类别的系统名称。
bundle 未签名，因此 TCC 将其标识为 `InvalidCode`，这会使权限主体识别不稳定；正式签名与
公证仍属于 release operations，不是本轮门禁。用户已明确认为产品不应请求或保留该能力；
本轮仅记录诊断，不在 Task 19/20 外新增移除任务。若后续处理，应隔离或移除 Test Mode 截图
服务，而不是要求用户授予录屏权限。

## 平台差异与豁免

- ID 85 Widget 保持唯一 `EXEMPT`，用户批准依据为
  `docs/superpowers/specs/2026-07-12-mihon-desktop-upstream-parity-design.md:217`；
  Desktop 不伪造跨 Windows/macOS 的 Android Widget。
- Windows/macOS 使用平台 adapter 的 credential、capture、share、URI、installer 和文件选择
  能力保留明确 Supported/Limited/Unsupported/Failed 反馈。
- Linux 只保留防御性 fallback/Unsupported，不是产品、发行或验收平台。
- 正式签名、公证和发布安装交接属于 release operations，不是本次 repository-local
  parity closure 门禁。
- Test Mode 截图不是 final parity 场景依赖；macOS ScreenCapture 预检按上节记录为真实
  产品/自动化边界，不伪装为通过权限能力。

## 已执行命令

```text
.\gradlew.bat testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test
.\gradlew.bat :app:testReleaseUnitTest --tests eu.kanade.presentation.more.settings.screen.browse.ExtensionReposScreenModelWiringTest
.\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.parity.DesktopProductCapabilityContractTest
powershell -File scripts/tests/task15-platform-evidence-runner-test.ps1
bash scripts/build-desktop.sh evidence
bash scripts/desktop-final-parity-test.sh
"C:\Program Files\Git\bin\bash.exe" scripts/desktop-smoke-test.sh
.\gradlew.bat spotlessCheck :app-desktop:finalParityAudit

bash scripts/build-desktop.sh evidence
python3 scripts/task15-build-provenance.py verify \
  --repo /private/tmp/mihon-parity-19a55d7c \
  --artifact "/Applications/Mihon Desktop.app" \
  --provenance "/Applications/Mihon Desktop.app.task151-provenance.json"
MIHON_FINAL_PARITY_EXE="/Applications/Mihon Desktop.app/Contents/MacOS/Mihon Desktop" \
MIHON_FINAL_PARITY_PORT=18080 \
bash scripts/desktop-final-parity-test.sh
```

## 剩余豁免与限制

- ID 85 Widget 是唯一 capability `EXEMPT`；没有第二项产品豁免。
- Linux 仍只保留防御性 fallback，不是发行或验收平台。
- 正式签名、公证和发布安装交接属于 release operations。
- Test Mode 截图权限预检不影响已完成的 parity 验收，但不属于普通用户应授权的产品能力。
