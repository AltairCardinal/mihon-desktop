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

## Review repair round 2

### 状态

`DONE_WITH_CONCERNS`

本轮关闭了 durable cancellation tombstone、cleanup/terminal 线性化、真实 `PackageInstaller.Session.commit` 覆盖和 Shizuku 延迟 callback 四项 review 问题。用户取消不再在平台清理前发布 `Idle`；PackageInstaller 会先 abandon session、注销 callback receiver 并销毁 service，随后 coordinator 才 rollback/reload/cleanup；Shizuku 活跃安装会等待其延迟 callback 与 service destroy，取消后的 callback 不再发布错误 terminal。

### TDD 与 mutation 证据

- durable tombstone：测试改为同一 UUID 在 public cancel 后连续 enqueue 两次；旧实现第一次 enqueue 会消费取消记录，第二次错误进入 `processEntry`，focused RED 1/1 failed。改为进程级 durable tombstone 后 focused GREEN。
- PackageInstaller 端到端：测试从真实 `downloadAndInstall`、coordinator port、production `installPrepared` 一直执行到 queue/session/`Session.commit`。旧实现会在 service destroy 前发布 `Idle`，focused RED 1/1 failed；修复后测试确认 destroy 前 terminal 未完成、session 未 rollback，destroy 后才按 `prepare -> validate -> commit -> rollback -> reload -> cleanup` 收尾，且 active maps 全部释放。
- commit wiring mutation：测试夹具把 PendingIntent identity 与 `Session.commit` identity 分离，并要求 commit 精确调用一次；临时删除 production `session.commit(intentSender)` 后 focused 测试失败，恢复后通过。
- 过早 Idle mutation：临时在 public cancel 入口立即写入 `InstallStep.Idle`，端到端 package 测试在第 396 行失败；恢复后通过。
- Shizuku 延迟 callback mutation：临时绕过 `Installer.continueQueue` 的 cancellation tombstone 分支，focused 测试在第 513 行失败；恢复后通过。
- 所有临时 mutation 均已恢复，最终 diff 不包含 mutation。

### Cleanup 线性化与资源边界

- public cancel 会先持久记录 transaction tombstone，再通过当前 owner 直接触发 cleanup；LocalBroadcast 仅保留兼容通知，因此广播丢失或延迟不会漏掉活跃安装清理。
- cancellation acknowledgement 不再使用 10 秒兜底提前放行。活跃 PackageInstaller/Shizuku transaction 只有在 session/callback receiver/service 生命周期安全结束后才完成 acknowledgement，coordinator 之后才允许 rollback 和 terminal。
- `pendingCancellations` 的已完成 deferred 会清扫；未完成 deferred 不会被 TTL 删除。tombstone 仅在没有 pending acknowledgement 时按 5 分钟 TTL 惰性清扫，避免 deferred 泄漏或取消意图在 cleanup 前失效。
- coordinator terminal collector 会先移除 `activeJobs`、`activeTransactions`、`activeSteps` 与取消集合，再发布 terminal；取消完成后 `platformResults` 也已释放。

### 最终验证

- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`：`BUILD SUCCESSFUL`，20/20 passed（13 lifecycle + 7 wiring）。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*PackageInstaller*" --tests "*Shizuku*"`：`BUILD SUCCESSFUL`，实际命中 3/3 passed（2 个 `ExtensionManagerUpdatePolicyWiringTest` + 1 个 Shizuku lifecycle）。PackageInstaller 没有独立类名匹配项，其真实 session/commit 路径由上一命令的 13 个 lifecycle 测试覆盖。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat spotlessCheck`：`BUILD SUCCESSFUL`，61 tasks 无格式违规。
- `git diff --check`：通过，无空白错误。

### 顾虑与技术边界

- 普通 JVM 加载真实 `ShizukuInstaller` 时，Shizuku Binder 静态初始化会调用 Android stub 的 `Binder.attachInterface`，无法在该测试环境实例化。测试因此执行真实共享 `Installer` production lifecycle，并精确使用 Shizuku 的 `cancelEntry(entry) = getActiveEntry() != entry` 语义、延迟 callback 与 service destroy；对应 mutation 已证明该测试能杀死取消后错误发布 terminal 的实现。未修改 Shizuku AIDL 或 `ShizukuInstaller` production 文件。
- 本轮只修改 3 个 tracked 代码/测试文件并追加本报告；工作区既有未跟踪文件保持未暂存、未修改。

## Review repair round 3

### 状态

`DONE_WITH_CONCERNS`

本轮关闭最终审查的 1 项 Important 与 1 项 Minor。`installPrepared` 的 cancellation guard、`platformResults` 注册和平台启动 handoff 现在与 public cancel 的 direct-terminal 判定共用单个 per-transaction lifecycle 临界区；取消不能再从 guard 已通过、platform result 尚未注册的窗口提前发布 `Idle`。高层 completed terminal registry 改为 5 分钟 TTL tombstone，并且只清理已过期且不在 `activeTransactions`、`activeSteps` 或 `platformResults` 中的 UUID，仍在 flight/platform lifecycle 的事务不会因 TTL 被重开。

### TDD：focused RED / GREEN

- startup race RED：测试侧用 `BlockingPlatformResults` 在 production guard 已通过、`platformResults.put` 尚未完成时通过 `CountDownLatch` 确定性暂停；随后从真实 public `cancelInstall` 入口取消。旧实现立即发布 `Idle`，focused 1/1 failed（`ExtensionInstallSessionLifecycleTest.kt:494`）。无 sleep 或仅靠调度时序的断言。
- tombstone pruning RED：写入 512 个完成 UUID、把可带时间戳 registry 老化并触发 production `updateInstallStep`。旧无界 Set 无法老化或清扫，focused 1/1 failed（当时 `ExtensionInstallSessionLifecycleTest.kt:570`）。
- GREEN：每 UUID `TransactionLifecycle` 只包围非 suspend 的 guard → result registration → `installApk` handoff，以及 cancel 对 `platformResults == null` 的判定；锁不跨 `awaitPlatformResult`、cleanup acknowledgement、rollback 或其他 suspend 点。两个 focused 测试 2/2 passed。
- race 用例放行后同步进入真实 PackageInstaller queue/session/commit owner；destroy 前断言无 terminal/rollback，destroy 后断言唯一 `Idle`，且调用顺序为 `prepare -> validate -> commit -> rollback -> reload -> cleanup`，`platformResults`、active jobs/transactions 与 coordinator `inFlight` 均已清空。
- pruning 用例断言近期 terminal 的重复 terminal 不会刷新或替换 tombstone；已过期但仍在 `activeSteps` 的 transaction 继续拒绝非 terminal 更新；移除最后 active/platform 引用并再次老化后才允许清扫；长期已完成 UUID 不再无限增长。

### Mutation 证据

- 临时去掉 `installPrepared` 与 `requestCancellation` 的共同 lifecycle 临界区：startup race focused 1/1 failed（最终测试行 496），证明测试能杀死 check-register/cancel 非原子实现；随后恢复。
- 临时禁用 `pruneCompletedTransactions()`：pruning focused 1/1 failed（最终测试行 619），证明内存边界测试依赖真实 production pruning；随后恢复。
- 两项 mutation 均已恢复；恢复后 `:app:spotlessApply` 与两个 focused GREEN 2/2 passed。

### 最终验证

- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`：`BUILD SUCCESSFUL`，22/22 passed（15 lifecycle + 7 wiring；保留既有 20 项并新增 2 项）。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*PackageInstaller*" --tests "*Shizuku*"`：`BUILD SUCCESSFUL`，实际命中 3/3 passed（2 个 ExtensionManager wiring + 1 个 Shizuku lifecycle）；真实 PackageInstaller session/commit 链路由上一命令的 lifecycle 用例覆盖。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat spotlessCheck`：`BUILD SUCCESSFUL`，61 tasks 无格式违规。
- `git diff --check`：通过；mutation 均已恢复。

### 顾虑与边界

- 普通 JVM 仍不能证明 Android 系统把 `startForegroundService` 调度到 `Service.onStartCommand/onDestroy` 的设备级时序；本轮 seam 确定性验证 production 高层线性化、真实 shared Installer owner/session/commit/cleanup 与 coordinator 收尾顺序，但不替代设备级 framework 验收。
- durable base tombstone、PackageInstaller/Shizuku cleanup 与 commit-sensitive wiring 均保持原实现；本轮产品与测试改动严格限于 `ExtensionInstaller.kt` 和 `ExtensionInstallSessionLifecycleTest.kt`，另仅追加本报告。开始时协调 HEAD 已由指派中的 `b3dbb5c9b` 推进到 `44d643795`，tracked 工作区无冲突改动，未回滚任何协调提交。

## Review repair round 4

### 状态

`DONE`

本轮关闭 extra-final review 的 1 项 Important 与 1 项 Minor。每个 transaction 现在持有显式 `NEW -> HANDED_OFF -> FINISHING -> COMPLETE` CAS phase：平台 result 注册与 `installApk` 成功交接后永久进入 `HANDED_OFF`，result 消费后进入 `FINISHING`，只有 coordinator flight 已移除并发布 terminal 后才进入 `COMPLETE` 并移除 lifecycle。public cancel 仅允许真正 `NEW` 的事务 direct terminal；`HANDED_OFF`/`FINISHING` 一律等待平台 acknowledgement，并由 cancellation-aware coordinator port 在 commit/reload 边界触发 rollback、runtime restore、cleanup 与唯一 terminal。

completed tombstone pruning 现在同时保护 transaction-aware active job 与未 `COMPLETE` lifecycle。即使 tombstone 已超过 5 分钟，只要 coordinator job 尚活或 transaction 仍在 `FINISHING`，late duplicate 都不能清除 tombstone并重开事务；只有 lifecycle 已 `COMPLETE` 且不存在 job/step/transaction/platform result 引用时才允许惰性清扫。

### TDD：确定性 RED / GREEN

- post-result FINISHING race：真实执行 `downloadAndInstall -> coordinator -> installPrepared -> PackageInstaller queue/session/commit -> callback`，success callback 消费 platform result 后用 latch 阻塞 runtime reload，再从 public `cancelInstall` 取消；rollback 另设 latch。旧的 `platformResult == null` direct 判定会在 rollback/cleanup/maps/flight 收口前发布 `Idle`，focused 1/1 failed。修复后 reload 与 rollback 阻塞期间 terminal 均未完成，最终调用顺序为 `prepare -> validate -> commit -> reload -> rollback -> reload -> cleanup`，只发布一个 `Idle`，且 platform result、active maps、lifecycle 与 coordinator flight 全部释放。
- active job pruning：在真实 coordinator prepare job 尚活时老化 terminal tombstone，并移除其他测试侧 active 引用以隔离 job 保护。旧 pruning 忽略 `activeJobs`，focused 1/1 assertion failed；transaction-aware active job 加入 active-safe 判定后通过。
- FINISHING lifecycle pruning：真实 PackageInstaller success callback 后阻塞 reload，使 lifecycle 已 `FINISHING`；测试移除 job/transaction/step 引用后老化 tombstone。旧 lifecycle 为空 monitor、pruning 不检查 phase，focused 1/1 assertion failed；未 `COMPLETE` lifecycle 纳入 active-safe 判定后通过。
- 三个新增 focused 用例最终 3/3 passed；完整 lifecycle + coordinator wiring 最终 25/25 passed（18 lifecycle + 7 wiring），保留前 22 项并新增 3 项。

### Mutation 证据

- 临时把 public cancel direct 条件从 `phase == NEW` 改回 `platformResult == null`：post-result FINISHING race 1/1 failed，证明测试能杀死 result 消费后回退 no-platform 的旧实现；随后恢复。
- 临时把 result 消费后的 `FINISHING` 提前改成 `COMPLETE`：FINISHING tombstone pruning 1/1 failed，证明 lifecycle 不能在 coordinator flight 前完成；随后恢复。
- 临时移除 active job pruning 引用：active-job tombstone pruning 1/1 failed，证明 TTL 清扫必须保留 transaction-aware job；随后恢复。
- 所有 mutation 均已恢复，最终 diff 不包含 mutation。

### 最终验证

- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`：`BUILD SUCCESSFUL`，25/25 passed。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*PackageInstaller*" --tests "*Shizuku*"`：`BUILD SUCCESSFUL`，实际命中 3/3 passed；真实 PackageInstaller session/commit 路径由 lifecycle suite 覆盖。
- `$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; .\gradlew.bat spotlessCheck`：`BUILD SUCCESSFUL`，61 tasks 无格式违规。
- `git diff --check`：通过；产品/测试改动严格限于 `ExtensionInstaller.kt` 与 `ExtensionInstallSessionLifecycleTest.kt`，另仅追加本报告。

### 边界

- `NEW` direct cancel 仍可立即向用户发布 `Idle`，但 cancellation tombstone 与 lifecycle 会保留到其 active job 完成后再 exactly-once CAS `COMPLETE`/remove，避免迟到协程重建 lifecycle 或越过 guard。
- 普通 JVM seam 验证 production 高层 phase、真实 shared Installer/PackageInstaller session/commit/callback 与 coordinator rollback/cleanup/flight 顺序，不替代 Android 设备对 foreground service 调度时序的验收；Package、Shizuku、commit identity、startup latch 与 5 分钟 TTL 既有语义保持不变。
