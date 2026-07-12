# Windows 未打包构建与版本规则实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Windows 默认构建始终产出并验收最新未打包 EXE，并用 `0.STAGE.FEATURE.BUILD.GIT_HASH` 区分每次构建。

**Architecture:** `AppVersion.kt` 保存三个受控数字字段，Gradle 只负责组合运行时版本和 MSI 三段版本；统一 shell 脚本负责版本分配，PowerShell 脚本负责测试、产物任务顺序和固定 EXE 验收。MSI 改为显式模式，并在 MSI 后重新生成未打包应用。

**Tech Stack:** Bash、PowerShell、Gradle Kotlin DSL、Kotlin/JUnit 5、Compose Desktop native distributions。

## 全局约束

- 应用版本必须使用 `0.STAGE.FEATURE.BUILD.GIT_HASH`。
- Windows 开发验收 EXE 固定为 `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe`。
- 默认、`feature`、`stage` 只以未打包应用作为验收产物；MSI 只能通过 `msi` 显式生成。
- `test-only` 和 `full-tests` 不修改版本号。
- 不回滚或覆盖工作区中无关的已有改动。
- 不自动创建 Git 提交。

---

### Task 1: 扩展应用版本模型

**Files:**
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/AppVersionTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt`
- Modify: `app-desktop/build.gradle.kts`

**Interfaces:**
- Produces: `AppVersion.BUILD: Int`
- Produces: `APP_VERSION = "0.$STAGE.$FEATURE.$BUILD.$GIT_HASH"`
- Produces: MSI 原生版本 `STAGE.FEATURE.BUILD`

- [ ] **Step 1: 写版本格式失败测试**

将 `AppVersionTest` 的格式断言改为五段格式，并增加 `BUILD >= 1`：

```kotlin
val pattern = Regex("""^0\.\d+\.\d+\.\d+\.[0-9a-f]{7}$""")
assertTrue(pattern.matches(APP_VERSION))
assertTrue(AppVersion.BUILD >= 1)
```

- [ ] **Step 2: 运行红测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.AppVersionTest"`

Expected: 编译失败，提示 `AppVersion.BUILD` 不存在，或格式断言失败。

- [ ] **Step 3: 添加 BUILD 并更新 Gradle 原生版本**

在 `AppVersion.kt` 添加：

```kotlin
const val BUILD = 1
```

将 `APP_VERSION` 改为五段格式。将 `desktopNativePackageVersion` 改为：

```kotlin
"${readAppVersionConstant("STAGE")}.${readAppVersionConstant("FEATURE")}.${readAppVersionConstant("BUILD")}"
```

- [ ] **Step 4: 运行绿测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.AppVersionTest"`

Expected: PASS。

---

### Task 2: 锁定 Windows 未打包产物契约

**Files:**
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/release/WindowsReleaseConfigurationTest.kt`
- Modify: `scripts/build-windows.ps1`

**Interfaces:**
- Consumes: 调用方传入的 `-ExpectedVersion`
- Produces: 固定路径 `app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`
- Produces: `-PackageMsi` 显式发布开关

- [ ] **Step 1: 写 PowerShell 脚本契约红测**

新增断言：

```kotlin
assertTrue(text.contains("[switch]${'$'}PackageMsi"))
assertTrue(text.contains(":app-desktop:createDistributable"))
assertTrue(text.contains("Mihon Desktop.exe"))
assertTrue(text.contains("ExpectedVersion"))
assertTrue(text.lastIndexOf(":app-desktop:createDistributable") > text.lastIndexOf(":app-desktop:packageMsi"))
```

并把“默认必须 packageMsi”的旧断言改成“仅 `PackageMsi` 分支执行 packageMsi”。

- [ ] **Step 2: 运行红测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.release.WindowsReleaseConfigurationTest"`

Expected: FAIL，提示缺少 `PackageMsi`、`createDistributable` 或固定 EXE 验收。

- [ ] **Step 3: 修改 PowerShell 构建流程**

参数增加：

```powershell
[switch]$PackageMsi,
[switch]$VersionAllocated,
[string]$ExpectedVersion
```

实现顺序：测试；若 `$PackageMsi` 则执行 `packageMsi`；始终最后执行 `createDistributable`；检查固定 EXE 存在且修改时间不早于构建开始时间。启动 EXE 后通过进程窗口标题验证 `$ExpectedVersion`，超时或不匹配时失败，并只终止本轮启动的进程。

- [ ] **Step 4: 运行绿测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.release.WindowsReleaseConfigurationTest"`

Expected: PASS。

---

### Task 3: 重写统一入口的版本分配与模式分发

**Files:**
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/release/WindowsReleaseConfigurationTest.kt`
- Modify: `scripts/build-desktop.sh`

**Interfaces:**
- Consumes: `hash|feature|stage|msi|test-only|full-tests`
- Produces: `-VersionAllocated -ExpectedVersion <full-version>` PowerShell 参数

- [ ] **Step 1: 写 shell 分发红测**

增加断言：脚本读取和替换 `BUILD`；默认模式递增 `BUILD`；`feature`/`stage` 重置为 `1`；`msi` 传递 `-PackageMsi`；测试模式不改版本；旧“hash 只刷新 Git hash”文案不存在。

- [ ] **Step 2: 运行红测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.release.WindowsReleaseConfigurationTest"`

Expected: FAIL，提示 BUILD 或 MSI 显式分发契约缺失。

- [ ] **Step 3: 实现版本状态转换**

在 shell 脚本中读取 `BUILD` 并按以下规则写回：

```text
hash/default: BUILD += 1
feature: FEATURE += 1; BUILD = 1
stage: STAGE += 1; FEATURE = 0; BUILD = 1
msi: BUILD += 1
test-only/full-tests: no change
```

完整版本组装为 `0.$STAGE.$FEATURE.$BUILD.$GIT_HASH`，Windows 非测试调用传递 `-VersionAllocated -ExpectedVersion`，`msi` 额外传递 `-PackageMsi`。

- [ ] **Step 4: 运行绿测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.release.WindowsReleaseConfigurationTest"`

Expected: PASS。

---

### Task 4: 更新仓库维护规则

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/automation/TEST_GUIDE.md`

**Interfaces:**
- Produces: 后续桌面迭代统一遵守的构建和报告契约。

- [ ] **Step 1: 写文档契约红测**

在 `WindowsReleaseConfigurationTest` 增加断言，要求 `AGENTS.md` 包含五段版本格式、固定 EXE 路径、MSI 不能代替开发验收、运行版本匹配要求。

- [ ] **Step 2: 运行红测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.release.WindowsReleaseConfigurationTest"`

Expected: FAIL，提示文档契约缺失。

- [ ] **Step 3: 更新中文文档**

更新 `AGENTS.md` 的“桌面端构建与部署”，并同步修改测试指南中的默认构建说明。明确完成报告必须包含完整版本号和未打包 EXE 绝对路径。

- [ ] **Step 4: 运行绿测**

Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.release.WindowsReleaseConfigurationTest"`

Expected: PASS。

---

### Task 5: 完整验证与真实未打包构建

**Files:**
- Verify: `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe`

**Interfaces:**
- Consumes: 完成后的统一构建脚本。
- Produces: 本轮唯一可验收的未打包 EXE 和经核对的运行版本。

- [ ] **Step 1: 运行格式与完整测试**

Run: `./gradlew spotlessCheck :app-desktop:jvmTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 执行默认统一构建**

Run: `./scripts/build-desktop.sh`

Expected: `BUILD` 自动递增；脚本生成固定路径 EXE，启动验证窗口标题后成功退出。

- [ ] **Step 3: 检查产物和版本**

检查固定 EXE 存在、修改时间属于本轮构建，并确认运行标题是 `Mihon Desktop 0.STAGE.FEATURE.BUILD.GIT_HASH`。

- [ ] **Step 4: 检查工作区范围**

Run: `git status --short` 和 `git diff --check`

Expected: 无空白错误；不包含对无关用户改动的回滚。
