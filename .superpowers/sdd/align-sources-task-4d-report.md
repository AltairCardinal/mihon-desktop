# Task 4D 实施与验证报告

## 结论

- 状态：`DONE_WITH_CONCERNS`
- 风险轴：`android-install-trust-rollback`
- 平台边界：`android`
- 用户可见边界：安装只有在目录身份、摘要、APK 元数据、签名与共享更新策略全部通过且 runtime reload 成功后才发布；`ConfirmationRequired`/`Untrusted` 保持显式失败终态，仍由既有手动信任入口处理。

## Production wiring

1. MockWebServer 形状的目录响应经 `ExtensionCatalogService` 和 production `ExtensionApi` 转为 `Extension.Available`，repository base URL/name/fingerprint、declared SHA 与精确 download URL 无损进入 `ExtensionInstallRequest`。
2. `ExtensionManager` 继续通过 production `ExtensionInstaller` 和共享 `ExtensionInstallCoordinator` 安装；receiver 回调在同包事务活跃期间不能提前写 installed/untrusted runtime map。
3. `AndroidInstallPort` 使用共享 `ExtensionTrustPolicy` 与 `SharedExtensionUpdatePolicy`，校验 downloaded SHA、repository continuity、package/version/lib/signers，并把 `ConfirmationRequired` 映射为显式 authentication failure。
4. production gateway 使用 `cacheDir/extension-installs/<UUID>/candidate.apk`、canonical containment、private temp -> readonly -> atomic move；system commit/restore 复用 Task 4C 的受控 session。
5. `InstallPreState` 同时保存 private/system 只读 APK snapshot、版本/签名/信任、loader origin、commit target 与 expected-absent；失败时只恢复实际改动侧，双安装另一侧保持原样，fresh 安装删除，restore reload 幂等。
6. repository trust sidecar 按 private/system 分侧原子持久化，随安装、恢复和删除同步更新。

## TDD 证据

### RED

- 初始 focused 命令：`./gradlew :app:testReleaseUnitTest --tests "*AndroidExtensionInstallSecurityRollbackTest" --tests "*ExtensionInstallCoordinatorWiringTest" --tests "*ExtensionApiSharedCatalogTest"`：13 项中 1 项因 repository name 未保留而失败。
- receiver gate：回调仍提前发布 installed map，断言失败。
- Android gateway seam：新增 production seam 前编译失败；最小 seam 后拓扑用例暴露 readonly 前置条件并完成测试校准。
- Untrusted reload：预期 `InstallStep.Error`，实际得到 `Installed`。
- 双安装 snapshot：预期 copy/readonly 各 2 次，实际各 1 次。
- 自审新增共享更新策略用例：相同 versionCode 的 extension lib `1.5 -> 1.4` 预期拒绝，修复前断言失败。

### GREEN（最终 fresh）

- focused：21/21 PASS，`BUILD SUCCESSFUL`。
- regression：`*ExtensionManager*` + `*ExtensionInstallSessionLifecycleTest`，22/22 PASS，`BUILD SUCCESSFUL`。
- formatting：根 `./gradlew spotlessCheck`，`BUILD SUCCESSFUL`。
- whitespace：`git diff --check`，exit 0、无输出。

## Mutation 审计

所有临时变异均由行为测试杀死并恢复：

1. 丢弃 repository fingerprint/name。
2. 跳过 declared/downloaded SHA 校验。
3. 跳过 APK signer continuity。
4. 把 `Untrusted` 当作成功发布。
5. 允许 receiver 在 active transaction 期间提前改 runtime map。
6. 把双侧 `InstallPreState` 退化为单 snapshot。
7. 遗漏 fresh private 删除。
8. 遗漏 system downgrade/失败后的旧包恢复。
9. 把 expected-absent restore reload 当作 loader error。
10. 忽略 `delete=false`。
11. 忽略 `readonly=false`。
12. 忽略 canonical containment。
13. 把 403/429/500/断网 taxonomy 折叠为单一 Network。
14. 把本地写盘失败折叠为 Unknown/Network。
15. 用候选 lib 代替 installed lib 执行共享更新策略，放过同 versionCode 的 lib downgrade。

## 变更范围

- 实际产品/测试文件：6 个（允许列表 8 个中的子集）；未修改 receiver 源文件和既有 API 测试文件。
- diff：1118 additions / 242 deletions；超过 620 行估算。
- 超额原因：主要来自一个 production gateway seam（519 行）和完整的 trust/topology/failure matrix（463 行）；这与任务 brief 的 Split waiver 一致，拆分会产生 trust 已生效但 rollback/receiver 尚未精确的中间 production 状态。
- 未触碰或暂存工作区内其他用户改动、SDK、缓存、Desktop 临时产物、OpenSpec progress 或计划文件。

## 自审与剩余关注

- 自审中发现并修复了 installed lib 参数错误；新增 RED/GREEN 防止相同 versionCode 的 lib downgrade。
- 本轮验证是 JVM production wiring + MockWebServer + 可故障注入 Android gateway seam；未在真实 Android 设备上执行 PackageInstaller/文件系统 instrumentation。Task 4C session lifecycle 回归 20 项已通过，但设备厂商 PackageInstaller 行为仍是剩余运行时风险。
