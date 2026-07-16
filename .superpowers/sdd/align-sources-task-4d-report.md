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
