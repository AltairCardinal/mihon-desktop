# Task 4D 审查修复报告

## 结论

- 状态：`DONE`
- 风险轴：`android-install-trust-rollback`
- 平台边界：`android`
- 本轮关闭：1 项 Critical、6 项 Important。
- 用户边界不变：只有目录身份、SHA、APK 元数据、签名、共享更新策略、提交、runtime reload 全部成功后才发布安装成功；`ConfirmationRequired` / `Untrusted` 仍是显式失败。

## 修复内容

1. system commit 与 restore 各自使用独立 child transaction UUID，并保持到同一 parent transaction 的映射；取消命中当前真实 PackageInstaller child session，旧 child 的迟到回调不能结束 restore。
2. private/system 双安装拓扑的两侧都执行 repository、digest 与 signer continuity；缺失旧 trust sidecar 不再允许候选包自证明可信。
3. installer preference 与 commit target 在事务 prepare 时冻结；prepare 后从 system 切换为 private 仍按已冻结的 system plan 提交和回滚。
4. expected-absent 标记保留到 cleanup；重复 reload 与首次 topology I/O 失败后重试仍保持幂等。
5. canonical、copy、readonly、delete、sidecar 读写及原子移动失败统一映射为 `AppError.Storage`；missing、malformed、I/O sidecar 被明确区分。
6. private APK 与 private/system trust sidecar 均使用临时文件和原子替换；写失败时 coordinator 恢复旧 APK 与旧 metadata，不静默降级非原子移动。
7. cleanup 只有在全部删除成功后才移除 prepared token；prepare 失败的残留写入清理 journal，并在下一事务前重试。
8. 新增无反射生产 seam，覆盖 `ExtensionApi -> ExtensionManager -> ExtensionInstallCoordinator -> AndroidInstallPort -> DefaultAndroidInstallGateway` 的真实下载、校验、私有原子安装和 sidecar 持久化。

## TDD 证据

### RED

- 双侧 trust / expected-absent / cleanup：11 个定向用例中 3 个因错误接受非 selected target、marker 提前消费、delete 后 token 丢失而失败。
- 真实 Task 4C bridge：首次 system commit 与 restore 复用身份，parent 与 child UUID 相同的断言失败。
- production sidecar：3 个用例中 2 个因 missing sidecar 被接受、sidecar 原子写失败后旧 APK 未恢复而失败。
- full production wiring：先因缺少 gateway/client/installerProvider seam 编译失败；加入 seam 后暴露 JVM 广播边界，隔离该 Android framework 边界后真实文件链路转绿。
- prepare cleanup journal：第二次 prepare 前仍残留 `failed-prepare` 目录，预期 `[retry-prepare]`、实际 `[failed-prepare, retry-prepare]`。
- 组合回归曾捕获 7 个显式 custom port 测试误读 Injekt preference；修复为仅默认 Android port 冻结 preference。

### GREEN

- focused：`./gradlew :app:testReleaseUnitTest --tests "*AndroidExtensionInstallSecurityRollbackTest" --tests "*ExtensionInstallCoordinatorWiringTest" --tests "*ExtensionApiSharedCatalogTest" --no-parallel` → 30/30 PASS。
- lifecycle / manager：`./gradlew :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*ExtensionInstallSessionLifecycleTest" --no-parallel` → 24/24 PASS。
- 最终编译与 app 格式：`./gradlew :app:spotlessCheck :app:compileReleaseUnitTestKotlin --no-parallel` → `BUILD SUCCESSFUL`。
- 根格式：`./gradlew spotlessCheck --no-parallel` → `BUILD SUCCESSFUL`。
- whitespace：`git diff --check` → exit 0、无输出。

## Production wiring 与 mutation

- 完整 production 测试从 MockWebServer catalog 读取 repository base URL/name/fingerprint、declared SHA 与精确 download URL，经 Manager 和 shared coordinator 写入真实 private APK 与分侧 trust sidecar。
- 真实 system 测试执行 `DefaultAndroidInstallGateway -> installSystemAttempt -> PackageInstallerInstaller session/commit/callback -> reload failure -> 新 child restore`；两次 child UUID 与 parent 均不同，旧 system bytes 恢复。
- 父取消测试证明 cancel 广播携带当前 child UUID，Task 4C `onDestroy` 前不发布 Idle，清理确认后终止，并拒绝迟到 Installed。
- mutation 覆盖 fingerprint/SHA/signer、双侧 trust、installer preference 切换、Untrusted、receiver active gate、双侧 snapshot、fresh 删除、system downgrade、expected-absent、delete/readonly/containment、sidecar missing/malformed/I/O/atomic failure、HTTP 403/429/5xx/断网和写盘失败。

## 范围与剩余风险

- 本轮产品/测试变更限定为 `ExtensionInstaller.kt`、三个 Android extension 测试文件及本报告；未触碰工作区其他未跟踪文件。
- 本轮为 JVM production wiring、MockWebServer 与 Task 4C session harness 验证；没有在真实 Android 设备上执行厂商 PackageInstaller instrumentation，厂商差异仍是运行时剩余风险。

## 独立复审修复 Round 2

### 关闭的 3 项 Important

1. `DefaultAndroidInstallGateway.removeSystem()` 对 PackageInstaller 无回调使用 2 分钟有界等待，并在成功、失败和超时路径都于 `finally` 注销 receiver；package 已不存在时仍幂等清理 system trust sidecar，卸载成功但 sidecar 首删失败后可在第二次 rollback 完成清理。
2. system child teardown 先移除 parent→child mapping，再于 `finally` 完成 child lifecycle；在两步之间到达的 parent cancel 会落到 parent lifecycle 并保留 cancellation tombstone，不再因“mapping 存在但 child lifecycle 已消失”而被撤销。
3. private 文件或 system package 物理存在但 APK metadata 无法 inspect 时，topology 在提交前返回 `AppError.MalformedData`，不再把该侧误判为 absent；原 APK bytes 与 trust sidecar 保持不变。

### RED / GREEN

- C RED：真实 Default gateway 参数化 private/system 用例中，现实现对物理存在但 inspect 返回 null 的 APK 执行 `validate()` 未报错；GREEN 后 1/1 PASS，内部两侧均断言 `MalformedData`、APK/sidecar bytes 不变。
- A RED：真实 Default gateway 的无回调卸载在虚拟 120 秒后仍未完成；package absent 留下 system sidecar；成功卸载但 sidecar 首删失败后第二次 rollback 因 early return 仍残留 metadata。GREEN 后三项 3/3 PASS。
- A 完整链加强：`ExtensionInstallCoordinator -> AndroidInstallPort -> DefaultAndroidInstallGateway.removeSystem()` 在无回调时于有界等待后发布 `Failed(Storage)`，且 receiver 注销 1 次；1/1 PASS。
- B RED：通过可控 `ConcurrentHashMap.remove()` latch 精确阻塞 child teardown 窗口，parent cancel 后实际发布 `Installed`；GREEN 后同一用例稳定发布 `Idle`、清空 active transaction，并拒绝迟到 child `Installed`，1/1 PASS。

### Mutation 证据

- A：恢复 unbounded `result.await()` 与 absent early-return 后，三个行为测试 3/3 失败；完整 coordinator rollback mutation 持续悬挂至外部 124 秒测试上限，证明有界 wait 是终止所必需。mutation 已恢复。
- B：恢复“先 complete child lifecycle、后删除 parent mapping”的旧顺序后，确定性 teardown 用例稳定得到 `Installed` 而失败。mutation 已恢复。
- C：移除 private/system inspect-null 的 `failMalformed` 后，参数化用例因 `validate()` 错误成功而失败。mutation 已恢复。

### 最终验证

- fresh selected regression（`--rerun-tasks`）：Security 20 + Lifecycle 23 + Wiring 9 + Manager 2 = **54/54 PASS**，0 failures/errors/skips。
- `./gradlew :app:spotlessCheck :app:compileReleaseUnitTestKotlin --rerun-tasks --no-parallel`：`BUILD SUCCESSFUL`。
- `./gradlew spotlessCheck --no-parallel`：`BUILD SUCCESSFUL`。
- `git diff --check`：exit 0、无输出。

### 本轮范围与剩余风险

- 3 个任务文件共 +419/-36（455 changed lines），另追加本报告；未触碰既有无关 untracked 文件。
- JVM 测试已覆盖真实 Default gateway、coordinator rollback 与 Task 4C PackageInstaller harness；真实设备厂商 PackageInstaller 的回调差异仍需 instrumentation/实机验收，但无回调现已被本地 timeout 有界化。

## 最终审查闭环 Round 3

### 实现

- system child attempt 开始时将外层 parent lifecycle 从 `NEW` 提升为 `HANDED_OFF`；即使 parent→child mapping 已删除、child lifecycle 尚在 teardown，取消也不会走 pre-handoff 快速 Idle，而是由外层 shared coordinator 在 rollback/cleanup 完成后发布终态并解除 receiver gate。
- teardown 回归的阻塞点移到 `ConcurrentHashMap.remove()` 真正完成之后，并显式断言 mapping 已空、terminal 未完成、active transaction gate 仍存在；外层回滚后才得到 Idle，迟到 child Installed 仍被拒绝。
- `DefaultAndroidInstallGateway.installedPackage()` 在读取 requested package sidecar 前验证 inspected APK 的 package identity 与 extension feature；private/system 的 package mismatch 和 non-extension 都在 topology/validate 阶段以 `MalformedData` 失败，不会进入 commit 或写入错误目标。
- 将既有 sidecar atomic rollback 测试从比较 `Properties.store()` 自动生成的时间戳注释改为比较解析后的 Properties 字段，消除跨秒造成的非语义抖动，不改变产品行为。

### RED / GREEN 与 mutation

- 初始定向 RED：2/2 失败，0 errors。post-map-removal 取消错误地提前完成 terminal；private package mismatch 未返回失败，均精确命中审查 finding。
- 定向 GREEN：2/2 PASS。
- mutation 1：移除 parent lifecycle 的 handoff 状态提升，teardown 回归 1/1 稳定失败。
- mutation 2：仅移除 package identity guard，identity cross-wire 回归 1/1 稳定失败。
- mutation 3：恢复 package guard、仅移除 extension-feature guard，同一回归 1/1 稳定失败。三组 mutation 后均恢复生产实现。

### 最终验证

- fresh Security + Lifecycle（`--rerun-tasks`）：21 + 23 = **44/44 PASS**，0 failures/errors/skips。
- fresh Wiring + Manager（`--rerun-tasks`）：9 + 2 = **11/11 PASS**，0 failures/errors/skips。
- `./gradlew :app:spotlessCheck :app:compileReleaseUnitTestKotlin --rerun-tasks --no-parallel`：`BUILD SUCCESSFUL`。
- `./gradlew spotlessCheck --no-parallel`：`BUILD SUCCESSFUL`。
- `git diff --check`：exit 0、无输出。

### 范围与剩余风险

- Round 3 产品/测试变更为 3 个文件、123 changed lines（+123/-5）；另追加本报告。未修改计划、OpenSpec、progress 或无关未跟踪文件。
- JVM 测试已穿过真实 Default gateway 与 Task 4C PackageInstaller harness；真实 Android 厂商 PackageInstaller 的 instrumentation 差异仍需设备级验证。

## 最终竞态闭环 Round 4

### 实现

- parent 取消读取到 system child 后，不再把“child lifecycle 已被 teardown 删除”误判为整个安装已结束。child lifecycle 缺失或已进入 `COMPLETE` 时，仅清理 child tombstone，并保留 parent cancellation，回退到外层 `HANDED_OFF` lifecycle；终态和 receiver gate 继续由 shared coordinator 在 rollback、cleanup 与 outer flight 完成后收口。
- `installSystemAttempt()` 只在调用方确实提供外层 parent lifecycle 时提升其 handoff 状态。`downloadAndInstall()` 生产入口仍建立并提升 parent lifecycle；合法 standalone coordinator→Default gateway→Task 4C bridge seam 不再因不存在伪造 parent lifecycle 而在 child session 建立前失败，也不会创建遗留 lifecycle。
- 新参数化竞态测试覆盖两种确定性交错：取消已读到旧 child mapping 后 child lifecycle 被完整删除，以及取消已取得 child lifecycle 对象但该对象随后进入 `COMPLETE`。两者都要求取消目标安全回退到 parent、terminal/gate 等待 outer rollback/cleanup、迟到 child Installed 被拒绝。

### RED / GREEN 与 mutation

- standalone bridge RED：既有 `production system commit reload failure restores through a distinct child session` 1/1 失败；当前硬前置条件使 system child 从未建立，`extension-installs` 目录为空，精确命中审查 finding。
- stale-child RED：取消线程先读取旧 child ID，再由 teardown 真正 remove mapping 并 complete/remove child lifecycle，最后放行 lifecycle lookup；1/1 失败，期望 `Idle`、实际 `Installed`。
- 定向 GREEN：standalone bridge + stale missing child + lifecycle-object-then-`COMPLETE` 共 **3/3 PASS**；commit/restore 使用两个不同 child session，并与 parent、迟到 callback 隔离。
- mutation 1：移除 child `COMPLETE` 识别后，参数化竞态 1/2 失败，完成的 child 被错误用作取消目标而不是 parent；mutation 已恢复。
- mutation 2：恢复旧的“child missing 时同时撤销 parent/child tombstone并返回”行为后，参数化竞态 **2/2 失败**；mutation 已恢复。

### 最终验证

- fresh Lifecycle 全类（`--rerun-tasks`）：**25/25 PASS**，0 failures/errors/skips。
- fresh Security + Wiring + Manager（`--rerun-tasks`）：21 + 9 + 2 = **32/32 PASS**，0 failures/errors/skips。
- `./gradlew :app:spotlessCheck :app:compileReleaseUnitTestKotlin --rerun-tasks --no-parallel`：`BUILD SUCCESSFUL`。
- `./gradlew spotlessCheck --no-parallel`：`BUILD SUCCESSFUL`。
- `git diff --check`：exit 0、无输出。

### 范围与剩余风险

- Round 4 产品/测试变更严格限定为 2 个获准文件，共 208 changed lines（+196/-12），另追加本报告；未修改计划、OpenSpec、progress 或无关未跟踪文件。
- JVM 测试已覆盖真实 shared coordinator、Default Android gateway 与 Task 4C PackageInstaller harness 的 commit/reload-failure/restore、取消及 teardown 交错；真实 Android 厂商 PackageInstaller 的回调时序差异仍需设备级 instrumentation 验收。
