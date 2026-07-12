# Task 0 完成报告

- 状态：DONE_WITH_CONCERNS

## RED

- 命令：`./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.*" --no-daemon --offline`
- 预期失败原因：`app-desktop/src/test/resources/parity/parity-manifest.json` 尚不存在。
- 输出摘要：Gradle 执行 1 个测试、1 个失败并以退出码 1 结束；测试结果 XML 明确记录 `java.lang.IllegalArgumentException: Missing parity/parity-manifest.json`，证明失败来自缺失 manifest，而非编译或环境错误。

## GREEN

- 命令：`./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.*" --no-daemon --offline`
- 输出摘要：退出码 0，耗时约 25.2 秒。契约测试读取真实 JSON 文件并验证：恰好 64 项、ID 唯一且集合精确、必填字段非空、状态与六种设计标签合法、初始状态全部为 `NOT_STARTED`、保护测试非空且不存在 `MISSING:`、所有证据路径均为真实文件。
- TDD 中间诊断：首次实现后发现 KMP 测试源集未把 `src/test/resources` 放入 classpath；改为从仓库根读取 brief 指定的真实 JSON。随后发现 Gradle 测试工作目录为 `app-desktop/`，修正为先定位仓库根再解析证据路径，之后 GREEN。

## 修改文件

- `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- `app-desktop/src/test/resources/parity/parity-manifest.json`
- `docs/desktop-parity/PARITY_TRACKER.md`
- `docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md`
- `docs/automation/TASK_TRACKER.md`
- `.superpowers/sdd/task-0-report.md`

## 自审

- manifest 是 64 项状态的唯一机器数据源，Markdown 未复制状态表。
- 六种合法设计标签为：`SHARE-DIRECT`、`SHARE-EXTRACT`、`PLATFORM-ADAPTER`、`DESKTOP-PRODUCT`、`TEMP-COMPAT`、`PLATFORM-EXEMPT`。
- 保护契约显式引用作者、Upcoming、双页、Webtoon 自动滚动、APK 转换、Test Mode 导航和 HTTP 的现有测试，避免另起重复实现。
- 比较报告原评分未修改；自动化场景全部明确标为计划中。
- `git diff --check` 退出码 0。
- 未读取、修改或暂存 `C/` 与 `app-desktop/tmp/`。

## 遗留问题与 concerns

- `:app-desktop:spotlessCheck --no-daemon --offline` 在 GREEN 后重跑时，Gradle Wrapper 仍尝试访问 `services.gradle.org`，随后因沙箱网络权限报 `java.net.SocketException: Permission denied: getsockopt`；因此本轮没有可确认的 Spotless 结果。Kotlin 测试已成功编译并通过，且 `git diff --check` 已通过。
- 64 项当前全部为路线图初始态 `NOT_STARTED`；本任务只建立治理与保护网，不声称任何能力已经对齐。

## 提交

- 初始 Task 0 提交：`de1710902 test(desktop): establish upstream parity tracking`。

## 审查修复

### RED

- 命令：`./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.*" --offline`
- 输出摘要：执行 1 个测试、1 个失败，`DesktopProductCapabilityContractTest.kt:97` 拒绝 manifest 中以契约测试自身或无关测试充当保护证据；`BUILD FAILED`，退出码 1。

### GREEN

- 命令：`./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.*" --tests "mihon.desktop.extension.ApkToJarConverterTest" --tests "mihon.desktop.network.FlareSolverrClientTest" --tests "mihon.desktop.reader.VirtualPageListTest" --tests "mihon.desktop.reader.EdgePixelMatcherTest" --tests "mihon.desktop.ui.reader.TapZoneTest" --offline`
- 输出摘要：parity 契约及全部受影响特征测试通过，`BUILD SUCCESSFUL in 7s`，退出码 0。
- `git diff --check`：退出码 0。
- Spotless concern：`./gradlew :app-desktop:spotlessCheck --offline` 的 Wrapper 尝试下载 Gradle 8.14.4，因沙箱网络权限报 `SocketException: Permission denied: getsockopt`，未得到格式任务结果。

### 审查项处理结果

- 非 `DESKTOP-PRODUCT` 条目保留 `protectionTests` 字段但统一为空数组，不再使用契约自引用或无关导航测试。
- 34（APK→JAR）、40（FlareSolverr）、43（拆页/边缘匹配）、49（双页点击区域）按设计标记 `DESKTOP-PRODUCT`，并绑定能力回退时会失败的精确测试。
- 新增 FlareSolverr 成功响应与失败响应特征测试。
- 作者、Upcoming、双页、自动滚动、Test Mode 导航/HTTP 保留为独立全局产品证据池，仅验证真实测试存在。
- 契约逐项固化设计文档 A–J 的 64 个精确标签集合；修正 10/11/12 为 `PLATFORM-ADAPTER`，85 为 `PLATFORM-EXEMPT`。
- ID 数量、唯一性、精确集合与标签映射均有清晰断言。
- `platformExemptionEvidence` 在非 `EXEMPT` 状态必须为 `NONE`；未来进入 `EXEMPT` 时必须指向真实证据文件。
- 文档明确区分全局产品证据池与 manifest 逐项 `protectionTests`。

### 审查修复文件

- `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- `app-desktop/src/test/kotlin/mihon/desktop/network/FlareSolverrClientTest.kt`
- `app-desktop/src/test/resources/parity/parity-manifest.json`
- `docs/desktop-parity/PARITY_TRACKER.md`
- `.superpowers/sdd/task-0-report.md`

### 审查修复提交

- 提交哈希：提交后回填。
