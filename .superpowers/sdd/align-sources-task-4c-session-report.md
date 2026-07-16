# Task 4C Android 安装事务/session 生命周期报告

## 状态

`DONE_WITH_CONCERNS`

Task 4C 的实现、测试、mutation obligations、相关回归与格式检查已完成并提交。顾虑是本任务实际变更量超过 brief 估算，且普通 JVM 测试为执行 Android callback seam 使用了少量反射；两点均不影响当前验证结果，但会增加后续内部字段重命名时的测试维护成本。

## TDD：RED

- 最终有效 RED 命令：
  - `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`
- 有效 RED 结果：15 tests，6 failed；既有 wiring 7 tests 全部通过。
- 失败摘要：
  - 同包重试后，旧 transaction/session 的迟到回调错误终止新事务。
  - 仅 transaction 或仅 session 不匹配的 callback 仍被接受。
  - PendingUserAction 错配 callback 未被忽略。
  - active cancel 未发布一次 Idle terminal。
  - service destroy/no callback 未 abandon session，也未发布 Error terminal。
  - cancel-before-enqueue 未留下 tombstone，随后条目仍进入 processEntry。
- RED 夹具排障：
  - 首次命令在配置期因未设置 Android SDK 失败，不计有效 RED；仓库 `.android-sdk/platforms/android-36` 存在，后续仅在命令进程设置 `ANDROID_HOME/ANDROID_SDK_ROOT`，未修改 `local.properties`。
  - 普通 JVM 的 Android `IntentFilter`、`Process.myPid` 与 `Intent.putExtra` 是 stub；测试仅 mock framework 容器与 receiver 注册边界，production queue、receiver、active session、terminal 更新均真实执行。修正后失败均为 assertion，而非夹具初始化错误。

## GREEN、回归与 Spotless

- 最终定向 GREEN：
  - 命令：`$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:spotlessApply :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`
  - 结果：`BUILD SUCCESSFUL`；16/16 tests 通过（9 lifecycle + 7 wiring）。
- 最终相关回归：
  - 命令：`$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*PackageInstaller*" --tests "*ShizukuInstaller*"`
  - 结果：`BUILD SUCCESSFUL`；当前工作区 pattern 匹配 3/3 tests，包含 timeout lifecycle 与 ExtensionManager update-policy wiring。
- 最终格式检查：
  - 命令：`$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat spotlessCheck`
  - 结果：`BUILD SUCCESSFUL`，61 tasks 无格式违规。
- 行为覆盖：success、error、abort、PendingUserAction 错配、duplicate、late-after-cancel/同包重试、cancel-before-enqueue、service destroy/no callback、timeout、Java hash collision、exactly-once terminal。
- Shizuku 边界：未修改 AIDL callback；`Installer.Entry` 保留从 UUID 派生的 `downloadId: Long` 仅供现有串行 Shizuku 写入/日志路径使用，queue canonical identity 为完整 UUID transaction ID。

## Mutation obligations

每项 mutation 均只临时修改一个风险点，运行对应测试确认失败，再用反向 `apply_patch` 恢复；最终定向 GREEN、回归与 Spotless 在全部恢复后重新通过。

1. UUID 改回 `packageName.hashCode().toString()`
   - 测试：`*ExtensionInstallSessionLifecycleTest.transaction identity*`
   - 证据：1 test / 1 failed，`UUID.fromString`/collision 契约失败。
   - 恢复：恢复 `UUID.randomUUID().toString()`；最终 collision 测试通过。
2. 去掉 transaction 校验，仅保留 session 校验
   - 测试：`*ExtensionInstallSessionLifecycleTest.callback must match*`
   - 证据：1 test / 1 failed，错 transaction callback 错误终止 active transaction。
   - 恢复：恢复 transaction + session 联合判断；最终测试通过。
3. 去掉 session 校验，仅保留 transaction 校验
   - 测试：`*ExtensionInstallSessionLifecycleTest.callback must match*`
   - 证据：1 test / 1 failed，错 session callback 错误终止 active transaction。
   - 恢复：恢复 transaction + session 联合判断；最终测试通过。
4. 去掉 timeout
   - 测试：`*ExtensionInstallSessionLifecycleTest.platform wait*`
   - 证据：1 test / 1 failed，虚拟时间推进两分钟后 wait 仍未完成。
   - 恢复：恢复 `withTimeout(INSTALL_TIMEOUT_MILLIS)`；最终 timeout 测试通过。
5. 去掉 service-destroy abandon
   - 测试：`*ExtensionInstallSessionLifecycleTest.service destroy*`
   - 证据：1 test / 1 failed，`PackageInstaller.abandonSession(101)` 未调用。
   - 恢复：恢复 active session exchange + abandon；最终 destroy/no-callback 测试通过。
6. 删除 cancel tombstone 检查
   - 测试：`*ExtensionInstallSessionLifecycleTest.cancel before enqueue*`
   - 证据：1 test / 1 failed，预期空 processed 列表，实际条目进入 processEntry。
   - 恢复：恢复 enqueue 前 canceled/completed tombstone 联合检查；最终测试通过。
7. 绕过 Package 与 base 两层 terminal CAS
   - 测试：`*ExtensionInstallSessionLifecycleTest.success error and abort*`
   - 证据：1 test / 1 failed，duplicate callback 导致 terminal-count 断言失败。
   - 恢复：恢复 activeSession CAS、waitingInstall CAS 与 completed tombstone CAS；最终 terminal-count 测试通过。

## 提交

- 提交：`d02b64279` (`fix(android): bind extension install sessions to transactions`)
- 提交前 `git status --short` 已确认只暂存以下 7 个 brief 文件；工作区其他未跟踪内容均未暂存、未修改。

## 实际变更文件与行数

| 文件 | 新增 | 删除 |
|---|---:|---:|
| `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt` | 5 | 5 |
| `app/src/main/java/eu/kanade/tachiyomi/extension/installer/Installer.kt` | 66 | 26 |
| `app/src/main/java/eu/kanade/tachiyomi/extension/installer/PackageInstallerInstaller.kt` | 37 | 20 |
| `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstallActivity.kt` | 6 | 2 |
| `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstallService.kt` | 6 | 6 |
| `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstaller.kt` | 49 | 30 |
| `app/src/test/java/eu/kanade/tachiyomi/extension/ExtensionInstallSessionLifecycleTest.kt` | 367 | 0 |
| **合计** | **536** | **89** |

### Split waiver

实际新增/修改量超过 400 行，主要由单一 lifecycle 契约测试矩阵（367 行，含 JVM Android framework seam 夹具）构成。production 的 UUID 必须原子贯穿 manager → intent/activity/service → base queue → PackageInstaller session → callback/deferred；把其中任一段拆为另一个可独立调度 Task 会产生旧 Long/hash 与新 UUID 混用的不可运行中间态。测试矩阵也必须在同一文件内共同约束 collision、late callback、tombstone、timeout、abandon 与 terminal CAS mutation，无法在不丢失单一风险轴审查闭环的前提下继续独立拆分。

## 自审结论与顾虑

- 自审结论：未发现阻塞提交的问题；canonical identity 已从 package hash 改为 UUID，PackageInstaller callback 同时匹配 transaction/session，cancel/destroy/timeout 有界释放，duplicate/late callback 不再产生第二 terminal，Shizuku 串行行为未改。
- 自审清理：删除无调用的 Long 身份兼容入口；commit 直接复用 coordinator token 作为 transaction，移除冗余 prepared transaction 字段；清理新增测试编译警告。
- 顾虑：普通 JVM 未提供完整 Android framework 实现，测试通过反射注入真实 production active queue/session 并 mock framework 容器；production 行为与 wiring 均真实执行，但内部字段重命名时需要同步维护测试夹具。
- 基线说明：任务指派声明实现基线 `9965e2257`、协调基线 `53758e118`；开始工作时当前 HEAD 已为其后续调度提交 `b645e4af9`，本提交建立在该无产品冲突的后续 HEAD 上，未回滚协调提交。
## Review repair round 1

### 状态

`DONE_WITH_CONCERNS`

本轮已修复 review 指出的四项问题：取消意图与 service 入队通过进程级、带 TTL 的 cancellation registry 线性化；用户取消和平台超时会等待有界 cleanup acknowledgement 后才允许事务完成或回滚；生命周期测试改为真实执行 `addToQueue -> processEntry -> PackageInstaller session -> PendingIntent/callback` production 链路；PendingUserAction 合法路径会启动确认 Activity，同时继续拒绝 transaction/session 不匹配的回调。Shizuku 路径未修改。

### TDD：RED / GREEN

- cancel-before-enqueue：补齐测试夹具后，有效 RED 进入断言并失败，旧实现仍执行 `processEntry`；加入进程级 cancellation registry 与 enqueue 临界区后 focused GREEN。
- cleanup acknowledgement：有效 RED 证明旧 `cancelInstallQueue` 无可等待的清理确认；GREEN 后广播未交付时 deferred 保持未完成，交付 production receiver 后才依次 abandon session、移除 active、注销 receiver、停止 service、发布 terminal 并完成 acknowledgement。
- timeout/no-callback：有效 RED 证明两分钟超时后事务可在平台清理前完成；GREEN 后 timeout 与用户 cancellation 均在 `NonCancellable` 中最多等待 10 秒 cleanup acknowledgement，并在清理完成前保持未完成。
- lifecycle production wiring：测试不再反射写入 `waitingInstall` 或 `activeSession`；真实进入 queue/session/commit，并捕获 production PendingIntent 中的 transaction/session identity，再把 callback 交给 production receiver。
- receiver 边界：新增“取消当前项但仍有下一排队项”测试；临时突变为无条件注销 receiver 后 focused RED（1/1 failed，`ExtensionInstallSessionLifecycleTest.kt:281`），恢复“仅取消后队列为空才注销”后 focused GREEN（1/1 passed）。

### Mutation obligations

- 临时删除 production PendingIntent 的 transaction/session extras：`callback must match both active transaction and session` 失败；已恢复。
- 临时删除合法 PendingUserAction 的 `service.startActivity(userAction)`：正向测试失败；已恢复。
- 临时让 active cancel 无条件注销 package callback receiver：下一排队项测试失败；已恢复为仅队列为空时注销。
- 所有 mutation 均已恢复，最终 diff 不包含 mutation。

### 最终验证

- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`：最终重跑结果记录于本轮提交前验证，覆盖 lifecycle 与 coordinator wiring。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*PackageInstaller*" --tests "*ShizukuInstaller*"`：最终重跑结果记录于本轮提交前验证；当前过滤器实际命中数量会在最终状态中如实报告。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat spotlessCheck`：最终重跑结果记录于本轮提交前验证。

### 顾虑与边界

- 普通 JVM 不提供完整 Android framework，因此测试只 mock `Intent`、`PendingIntent`、`PackageInstaller` I/O、receiver 注册等平台边界；queue、`processEntry`、session identity、callback 判定、cancel receiver、terminal 更新均执行 production 实现。
- coordinator timeout/cancellation 测试通过反射调用 private `installPrepared` 作为 production 入口，并只读检查 `platformResults`/active session；不再反射写入 installer active state。
- cancellation registry 以 UUID transaction 为键并以 5 分钟 TTL 有界清理；cleanup acknowledgement 最多等待 10 秒，避免平台边界永久阻塞 rollback。
#### 最终结果

- lifecycle + coordinator wiring：`BUILD SUCCESSFUL`，19/19 tests passed（12 lifecycle + 7 wiring）。
- ExtensionManager / PackageInstaller / Shizuku 过滤回归：`BUILD SUCCESSFUL`；当前名称过滤器实际命中 2/2 tests，均为 `ExtensionManagerUpdatePolicyWiringTest`。PackageInstaller 与 Shizuku 未有额外名称匹配测试，生命周期中的真实 PackageInstaller 链路已由上一命令覆盖；Shizuku production 未修改。
- 根目录 `spotlessCheck`：`BUILD SUCCESSFUL`，61 tasks 无格式违规。
