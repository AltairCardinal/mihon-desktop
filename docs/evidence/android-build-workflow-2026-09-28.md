# Android 构建流程实施与验收

日期：2026-09-28。状态：实施中。

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

## AB-03：CI 与文档

- Build workflow 使用 `candidate --unsigned`，显式准备 SDK 36/Build Tools 36.0.0，检查 Python 3.11+，保留既有 JDK 21 与 action SHA pin；上传完整 unsigned 目录。
- YAML 解析、路径与 CLI 检查通过；PR 权限仍为 `contents: read`，历史 release workflow 所有 job 保留上游仓库 gate，没有启用 fork 发布。
- 主代理独立核对 workflow 差异；旧 fork init 及脚本/CI 调用已删除，历史命令保持为历史证据。AGENTS、操作规范及仍未完成的同步性能 roadmap 构建入口同步更新，不改变该专项进度。
- 新增 Markdown 本地链接 8 处与 `git diff --check` 通过。真实 GitHub CI 和 Linux SDK 路径未执行，仅登记静态通过。

## AB-04：最终验证（进行中）

唯一一轮完整测试已启动：

```powershell
python scripts/gradle-coordinator.py start --key ab04-full -- .\gradlew.bat --offline --no-parallel --max-workers=1 --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :app-desktop:jvmTest :test-desktop:test spotlessCheck
```

日志 `.gradle-coordinator/ab04-full.log`。使用单 worker 控制资源；当前尚未完成，不预判最终状态。正式候选的真实 assemble 回执、签名及隔离运行留待本轮测试结束后记录。
