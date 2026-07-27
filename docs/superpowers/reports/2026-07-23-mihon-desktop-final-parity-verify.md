# Mihon Desktop 最终 parity 验证报告

## 当前结论

Windows、Android 与仓库内静态/自动化门禁已经完成；macOS 按用户决定延期到项目迁移至
macOS 机器后执行。因此父计划 Task 19 仍保持未勾选，本报告也不把尚未执行的 macOS
case 记为通过。

待验收 Windows 版本为 `0.11.14.51.19a55d7`：

- source commit：`19a55d7c27e854a9a5b8baa27871b6d8e1c3608c`
- source tree：`de17112f4dd9afd28ee30996253d1dc2d3be3744`
- 未打包应用：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop`
- 固定 EXE：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`
- provenance：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop.task151-provenance.json`
- artifact：366 files，252,609,988 bytes，SHA-256
  `37bf05a1766657bf6a28416768d81741fa8820a0724934c44b7cdf44435c1125`

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

所有 Gradle 重型任务均通过 `scripts/gradle-coordinator.py` 串行执行。

| 范围 | 结果 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| Android `testReleaseUnitTest` | 231/231 | 0 | 0 |
| Desktop `jvmTest` | 2,293/2,296 | 0 | 3 |
| `test-desktop:test` | 28/28 | 0 | 0 |
| Desktop smoke | 92/92 | 0 | 0 |
| `finalParityAudit` | PASS | 0 | 0 |
| `spotlessCheck` | PASS | 0 | 0 |

Desktop 的三个跳过均为明确环境边界：

- 两个 live extension compatibility case：本机没有 `~/.mihon/extensions` JAR；
- 一个 macOS JXA native-share case：当前主机是 Windows。

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

## 平台差异与豁免

- ID 85 Widget 保持唯一 `EXEMPT`，用户批准依据为
  `docs/superpowers/specs/2026-07-12-mihon-desktop-upstream-parity-design.md:217`；
  Desktop 不伪造跨 Windows/macOS 的 Android Widget。
- Windows/macOS 必须使用平台 adapter 的 credential、capture、share、URI、installer
  和文件选择能力保留明确 Supported/Limited/Unsupported/Failed 反馈。
- Linux 只保留防御性 fallback/Unsupported，不是产品、发行或验收平台。
- 正式签名、公证和发布安装交接属于 release operations，不是本次 repository-local
  parity closure 门禁。

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
```

## 剩余验收

项目迁移到 macOS 后，必须检出同一 source commit
`19a55d7c27e854a9a5b8baa27871b6d8e1c3608c`，使用构建脚本生成同版本 app bundle，
完成 Task 19 第 4 步的真实运行验收，并把实际命令、版本、产物路径、通过/失败/跳过补充到
本报告。该证据成立前不得勾选 Task 19，也不得进入 Task 20 最终 checkbox 收口。
