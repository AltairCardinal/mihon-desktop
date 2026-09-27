# Android 构建流程实施与验收

日期：2026-09-28。状态：流程已实现，正式候选已通过 API26/API36 隔离升级；完整测试保留既有 Reader 失败，实体设备验收待用户反馈。

设计：[构建与验收规范](../architecture/android-build-and-acceptance.md)。执行范围：[AB-01–04](../roadmap/2026-09-28-android-build-workflow-roadmap.md)。

## 证据规则与基线

- 起始提交 `b085025984`；AB-01 提交 `831f646fda`，默认身份已迁至 `app.mihon.desktop.fork`，Debug 追加 `.dev`，下一候选 aex.15/code33。
- 既有正式基线 aex.14/code32：APK SHA-256 `ddf9319a8351056bbae636bea8ed9fcd074d7a63af9a9171a27dbcea81f8cd43`；后续正式候选沿用其证书。
- 用户选择自行验收实体设备。本轮不安装、重启或操作实体设备；隔离模拟器证据与用户实机反馈分别记录。
- 已有 Reader 切章回归红测见[诊断记录](android-reader-tail-settlement-2026-09-27.md)。本任务不修改 Reader，不删除或跳过该测试。
- 产物构建、签名、安装、启动、业务验证分别记录；未执行项不以配置检查替代。

## AB-01：身份与版本

| 验证 | 结果与证据 |
| --- | --- |
| 真实 AGP 红绿 | `.gradle-coordinator/ab01-red.log` 因旧默认 ID 失败；迁移后契约通过 |
| 配置正负例 | `scripts/test_android_release_contract.py` 配置四项通过：默认/兼容入口、AEX/EIS/Sync 隔离身份、禁用参数、混用脚本拒绝；日志 `android-contract-*.log` |
| 实际 manifest | manifest 测试独立执行通过：Debug/Release 包名、版本码、provider/Shizuku authority；Debug/Release instrumentation 宿主及测试包正确 |
| 签名边界 | 真实旧 code32 APK 被新版本契约拒绝，发生于密钥读取前，没有生成签名输出；PowerShell 语法通过 |
| 格式 | `:app:spotlessKotlinCheck` 与 `git diff --check` 通过 |
| 独立审查 | 主代理核对默认及隔离身份、Gradle 发布限制、签名读取与 manifest 测试；契约测试版本硬编码已移除 |

## AB-02：统一入口与边界

提交 `5ef02d8226`。主代理独立审查签名信任根、输入快照、APK/mapping 回执、安装校验及协调器互斥通过。

| 验证 | 结果与证据 |
| --- | --- |
| 入口行为 | `python scripts/tests/build-android-test.py` 16/16 通过，日志 `.gradle-coordinator/ab02-android-entry-tests.log` |
| 真实 SDK | 临时隔离证书、小型实际 APK，经 aapt2/zipalign/apksigner 读取；错误身份、版本、正式证书及篡改被拒绝。测试夹具不作为可运行产品 APK |
| 安装行为 | 仅模拟 ADB 传输，实际解析拉取 APK；覆盖新装、旧版升级、同哈希无操作、同 code 不同 Release 拒绝、同 code Debug 覆盖、证书不符、安装后哈希不符。此项不等同设备升级验收 |
| 构建来源 | 输入变化、相关未跟踪输入、空跑/旧回执、重复 Debug 和按 code 占用版本的 focused 测试通过 |
| 协调器 | 原行为红测后，20 项回归通过；随后非状态 JSON、保留 key、工作目录及竞争的 3 项 focused 通过。实际不同 key 同时启动只产生一个被管理子进程 |
| 正式凭据预检 | 主代理实际执行 `python scripts/build-android.py check --signing`，exit 0；既有 DPAPI 凭据可解密，密钥可签名，证书正确；没有生成正式候选或操作设备 |
| 查询与构建互不混淆 | 同次 `check --signing` 正确报告 `ab04-full` 为 RUNNING，仍完成只读检查，没有再启动 Gradle |

最终边界检查发现新装路径把未安装包的 `pm path` 非零退出视作查询故障。新增红测后，改为成功查询包列表并精确匹配；仅确认目标不存在时新装，ADB 故障、畸形列表及已列出但无 APK 路径均拒绝。安装相关 5 项 focused 通过（`.gradle-coordinator/ab02-new-install-fix.log`），主代理完成既定一次修复复审。没有重跑产品全量或操作实体设备。

## AB-03：CI 与文档

- Build workflow 使用 `candidate --unsigned`，显式准备 SDK 36/Build Tools 36.0.0，检查 Python 3.11+，保留既有 JDK 21 与 action SHA pin；上传完整 unsigned 目录。
- YAML 解析、路径与 CLI 检查通过；PR 权限仍为 `contents: read`，历史 release workflow 所有 job 保留上游仓库 gate，没有启用 fork 发布。
- 主代理独立核对 workflow 差异；旧 fork init 及脚本/CI 调用已删除，历史命令保持为历史证据。AGENTS、操作规范及仍未完成的同步性能 roadmap 构建入口同步更新，不改变该专项进度。
- 新增 Markdown 本地链接 8 处与 `git diff --check` 通过。真实 GitHub CI 和 Linux SDK 路径未执行，仅登记静态通过。

## AB-04：最终验证

唯一一轮完整测试命令：

```powershell
python scripts/gradle-coordinator.py start --key ab04-full -- .\gradlew.bat --offline --no-parallel --max-workers=1 --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :app-desktop:jvmTest :test-desktop:test spotlessCheck
```

日志 `.gradle-coordinator/ab04-full.log`，摘要 `.gradle-coordinator/ab04-full-summary.json`。使用单 worker 控制资源，53m42s 结束，整体 exit 1；不能表述为全量绿色。

- Android：655 个独立用例、657 次执行，3 次失败均为上述同一个既有 Reader 用例的原始执行及重试；7 项跳过，其余通过。没有隐藏该失败或改变 Reader。
- domain：568 项、0 失败；任务 `UP-TO-DATE`，复用仍适用的结果。
- data：752 项、0 失败、1 跳过，本轮实际执行通过。
- Desktop：3207 项、2 失败、2 跳过。一项是本次 Gradle 配置增加行数后，capability 95 的证据仍指向旧第 247 行，已机械更新为实际第 301 行；另一项隔离 author Test Mode 子进程未在等待期内健康就绪。随后只定向复验这两项，2/2 通过，46s；日志 `.gradle-coordinator/ab04-desktop-focused.log`。未改 Desktop 产品逻辑，未追加完整 Desktop 测试，也不将后一次定向结果改写为首次全量全绿。
- Test Mode：52 项、0 失败，任务 `UP-TO-DATE`；完整格式检查通过。
- 删除兼容 init 后的默认身份、三个专项隔离身份及混用拒绝：3 项 focused 通过，35s；没有再跑产品全量。

本轮修改 Android 构建/签名/安装路径和 Gradle 协调器，没有修改 Desktop 产品源码或打包配置。协调器已由本轮真实 Gradle 完整/定向任务执行验证；不为 Android 工具链迁移重新生成 Windows/macOS 发行包。既有 Desktop 运行证据不能冒充本轮新 Android 候选证据。

### 正式候选与隔离升级

- `candidate --unsigned --offline` 实际通过，Release/R8 2m36s；aex.15/code33 unsigned APK SHA-256 `a826e97e459aa5273431d3ac11e6f0329a0740933a16d3e271dbd0357a46ff9a`。`debug --offline` 实际通过，1m07s；独立 `.dev` APK SHA-256 `806dac98a531586aafcce739d9c7b706a0ea09d8cfb6e14b205329e549dea2a0`。日志分别为 `.gradle-coordinator/ab04-unsigned.log`、`ab04-debug.log`。
- 首次正式 code33 APK 签名/证书验证成功，但子 Windows PowerShell 找不到最后记录哈希所用的 `Get-FileHash`，入口退出失败且没有生成 `artifact.json`。失败目录保留，不作为正式交付。仅将哈希输出改为 .NET SHA-256，未改变签名或信任根；真实签名复验及隔离测试证书的完整 PowerShell 回归测试通过，后者刻意使 `Get-FileHash` 不可用，仍成功签名并由真实 SDK 核验哈希。
- 按版本占用规则，最终候选推进为 aex.16/code34。默认真实 AGP 契约 focused 通过；正式构建 2m07s、签名和入口 `verify` 均通过。完整产品测试运行于 code33，随后仅版本元数据、哈希输出兼容性及测试证据行号变化，差异由定向与真实候选验证覆盖，没有追加产品全量。
- API 36 x86_64：核对隔离 AVD 身份，原安装为同证书 aex.13/code31；用已核验 aex.14 正式 APK 保留数据升级并启动。经应用“更多 → 分类 → 添加”创建 `AB04_upgrade_API36`，界面可见；关闭应用后只读 SQLite 副本确认 schema40、分类 ID1 及名称。升级前数据库/偏好副本及 UI 证据位于 `.gradle-coordinator/ab04-api36-before*`。模拟器已关闭释放资源，等待新候选。

### 固定正式产物

- [aex.16/code34 universal APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.16-vc34-3630efd1ce-release/Mihon-Fork-0.19.4-aex.16-vc34-release-universal.apk)；[artifact.json](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.16-vc34-3630efd1ce-release/artifact.json)。
- APK SHA-256：`4ec3a35a0309aa335dd1bca8a28b223b6b29c1d87deeaf513343c8b37ca0ebd7`。
- 包名 `app.mihon.desktop.fork`，min26/target36，四种 ABI，非调试、R8/资源压缩开启，遥测/更新器关闭；既有证书 v2/v3 签名通过。源码基线 `3630efd1ce6d680d7b0c45bd0f3861b3fd8e280a` 加冻结差异，`productionInputsSha256=36581092c92a584381fba3f5f9f53441d9ff788b804ae31535828a68d466e133`；完整差异摘要与 mapping 哈希在清单中。
- API36：统一 `install` 实际从 code32 升级到 code34，安装前后均拉取实际 APK 验签/验哈希；冷启动成功，分类在 UI 仍可见，数据库分类 ID/名称完全一致。appId/首次安装时间保持连续；偏好仅 `__APP_STATE_last_version_code` 改变。该进程 crash buffer 未见崩溃。证据为 `.gradle-coordinator/ab04-api36-install.log`、`ab04-api36-category-after.xml`、`ab04-api36-after/` 及包信息前后对照。

- API26：核对既有同证书 aex.11/code29，保留数据升级到 aex.14，正常 UI 完成首次设置并选定存储目录，未额外授予安装应用或电池豁免权限。由分类界面建立 `AB04_upgrade_API26`，再通过统一 `install` 升到同一份 aex.16/code34 APK。冷启动直接进入主界面，分类仍可见；数据库分类 ID/名称完全一致，偏好仅版本记录变化，userId/首次安装时间连续；对应进程 crash buffer 未见崩溃。证据为 `.gradle-coordinator/ab04-api26-install.log`、`ab04-api26-category-after.xml`、`ab04-api26-before/`、`ab04-api26-after/` 及包信息对照。
- 最终 APK 的二进制 manifest 中 provider/Shizuku authority、四个快捷方式的 targetPackage 均为 fork 身份；记录 `.gradle-coordinator/ab04-final-manifest-wiring.txt`。
- Debug 独立新装：API26 原先没有 `.dev` 包，通过统一 `install` 安装本轮 aex.15/code33 Debug APK 并拉取验真成功；包列表同时存在 `.dev` 与正式 fork，正式包仍为 code34、原 UID/首次安装时间不变。日志 `.gradle-coordinator/ab04-api26-debug-install.log`；没有自动启动 Debug UI。两个隔离模拟器均已正常关闭，验收数据保留。

## 结果边界与后续验收

- 本轮没有修改 Reader，原切章进度红测仍失败；构建流程实现完成不代表该问题已修复，也不代表完整测试已全绿。
- 正式 APK 已完成 x86_64 API26/API36 安装、同证书升级、分类/偏好保留和冷启动；不据此宣称所有阅读器、同步、下载或扩展业务均重新验收。
- arm64 实体运行和用户阅读器人工验收仍由用户自行执行，未用 x86_64 模拟器结果替代。用户实体设备未被本轮安装、重启或控制；真实 GitHub CI 尚未执行。
- 用户验收可使用上方固定 APK：覆盖 aex.14 后检查原书库/分类/设置，再按 CP-03 清单检查配对恢复。该手工反馈未回报前保持待验收，不自动勾选。
