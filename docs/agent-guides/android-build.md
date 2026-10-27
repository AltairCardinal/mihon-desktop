# Android SDK、构建与验收

本页是[根规则](../../AGENTS.md)按动作加载的项目约束。仅阅读本次动作适用章节；代码命令与普通路径以仓库根为基准，Markdown 链接按本页位置解析。

准备 Android 编译/JVM 测试、SDK/签名诊断、候选校验、安装或验收前使用。完整模块示例仍受[验证范围与批准门槛](verification.md)约束；安装与原生输入各自需要任务授权。

### 本机 Android SDK

- 本机 Android SDK 固定安装在 `D:\Android\Sdk`。截至 2026-08-29，已安装 command-line tools 22.0、`platforms;android-36`、`build-tools;36.0.0` 与 `platform-tools`，满足当前 `compileSdk = 36` 的 Android 编译和 JVM 单元测试要求。
- 用户级 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 均指向 `D:\Android\Sdk`；用户 PATH 包含 `D:\Android\Sdk\platform-tools` 和 `D:\Android\Sdk\cmdline-tools\latest\bin`。已经打开的 PowerShell/Codex 进程不会自动刷新用户环境，必要时在当前会话显式设置：

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
```

- 仓库忽略的 `local.properties` 使用 `sdk.dir=D\:\\Android\\Sdk`，不得提交该机器专属文件。Gradle 应优先通过该文件发现 SDK；删除或迁移 SDK 时必须同步更新用户环境和 `local.properties`。
- command-line tools 必须从 Android 官方下载页取得并校验官方 SHA-256。本次安装包为 `commandlinetools-win-15859902_latest.zip`，校验值为 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`。下载和 `sdkmanager` 访问境外网络时遵循仓库代理规则。
- 当前 `sdkmanager` 22.0 会提示迁移到新的 Android CLI，但仍能完成 SDK package 安装。`android.exe` 首次运行会额外下载 CLI；除非任务明确需要，不要把这个下载加入普通 Gradle 验证流程。
- SDK 自检以真实文件和 Gradle Android task 为准：至少确认 `platforms\android-36\android.jar`、`build-tools\36.0.0\aapt2.exe`、`platform-tools\adb.exe` 存在，再运行受影响的 Android focused tests；历史 AVD 配置不能代替 SDK 安装证据。

---

## 常用命令

Android 构建身份、签名、产物及验收遵循 [Android 构建与验收规范](../../docs/architecture/android-build-and-acceptance.md)。正式身份和升级版本唯一来源是 `gradle/android-release.properties`；Release 默认属于本 fork，Debug 使用独立 `.dev` 身份。统一入口的验证进度见[实施记录](../../docs/evidence/android-build-workflow-2026-09-28.md)。

用户交付走 `scripts/build-android.py candidate`，不能直接把 `app/build/outputs` 中的 APK 当成正式交付。构建不隐式安装，安装不隐式操作应用；用户自行验收时不代操作实体设备。不同正式候选递增 versionCode，普通 Debug/查询/测试不递增。原证书必须连续，凭据不可用时不创建替代密钥。

Android 验收优先使用已授权、可隔离的模拟器。模拟器能够执行的真实 production、原生 UI/输入法、持久化、升级及集成验收，应继续执行，不得仅因没有实体设备或等待真机而停止。执行前核对 AVD、serial、正式产物版本、签名和应用身份，使用虚构夹具，不操作其他模拟器或用户实例。只有依赖实体硬件、厂商系统行为或计划明确要求真机的项目，才保留真机验收缺口，并说明模拟器不能覆盖的具体风险；模拟器通过不得宣称这些真机项目通过。

```bash
# 环境与正式签名预检（不构建、不安装）
python scripts/build-android.py check --signing

# 开发 APK；与正式安装隔离
python scripts/build-android.py debug

# 正式候选：R8、外部签名、校验及产物清单；不自动跑全量或安装
python scripts/build-android.py candidate

# 无密钥 CI 只生成明确标注的未签名候选
python scripts/build-android.py candidate --unsigned

# 核对既有候选；有安装授权时才执行独立的 install 命令
python scripts/build-android.py verify --artifact "<APK绝对路径>"
python scripts/build-android.py install --artifact "<APK绝对路径>" --serial "<本次确认的设备>"

# 运行单元测试
./gradlew testReleaseUnitTest

# 运行单个测试类
./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.SomeTest"
```

底层 `:app:assembleRelease` 仍可用于开发，但输出是未签名中间产物。重型 Gradle 验证继续经[Gradle 协调器](verification.md#gradle-生命周期)串行执行；统一构建入口已调用协调器，不要再从外层嵌套同一协调器任务。

本 fork 禁止 `-Pinclude-telemetry`、`-Penable-updater`、`-Pdisable-code-shrink`；Gradle 配置会直接拒绝。专项 AEX/EIS/Sync init 脚本仅用于各自隔离验收，不能混用，也不能作为正式交付路径。
