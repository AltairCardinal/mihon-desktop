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
