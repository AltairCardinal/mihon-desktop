# 同步进度、条目日志与待机恢复设计

2026-09-26 需求补充：[三阶段进度与实时剩余时间需求](2026-09-26-sync-progress-eta-requirements.md)。准备／传输／确认的显示、数据条目计数和 ETA 以新需求为准；该文档尚未实施，不改变本文的恢复与持久化安全约束。

日期：2026-09-19；2026-09-21 修订状态：实现审查不通过，R2–R4 重新打开。原有平台运行证据仅证明当时测试范围，不能证明覆盖升级、首次合并进度或自动恢复闭环已完成。当前修复规则以[修复设计](2026-09-21-sync-repair-design.md)及[修复 roadmap](roadmap/2026-09-21-sync-repair-roadmap.md)为准；本文保留原始需求和历史实现说明，不能作为当前实现已达标的证据。

实施入口：[本轮 Roadmap](roadmap/2026-09-19-sync-progress-resume-roadmap.md)。交互参考：[双端 DEMO](prototypes/multi-device-sync/index.html)及[说明](prototypes/multi-device-sync/README.md)。既有架构：[多设备同步技术方案](2026-09-13-multi-device-sync-technical-proposal.md)。

## 1. 目标与范围

用户在 Android 上开始同步，设备自动锁屏，返回应用后仍看到“正在合并数据…”；仅凭截图无法判断任务是否中断、网络等待或仍在处理。此次要让用户看到真实阶段、已完成数量与最近条目，并在返回应用时自动继续未完成同步。

本轮覆盖 Android 的待机／进程重建恢复，以及 Android、Windows Desktop 共用的同步进度与日志界面。Desktop 复用共享运行状态、持久化与应用启动恢复；Windows/macOS 系统电源唤醒监听和退出后常驻服务不属于本轮。前文“回到 Mihon Desktop”按故障截图和技术调研解释为返回 Android 应用，不声称已经调研完成桌面电源事件。

复用原有书架同步面板、首次合并页、记录页、运行协调器、数据库队列、HTTP 客户端与平台调度。新状态属于既有同步链路，不建立另一套交换算法或上传协议。不增加同步字段、GitHub 权限、密码模式、冲突处理能力或远端数据迁移。

## 2. 已核对的现状与证据边界

| 入口 | 当前事实 | 仍需补足 |
| --- | --- | --- |
| [Android App](../app/src/main/java/eu/kanade/tachiyomi/App.kt)与[调度器](../app/src/main/java/eu/kanade/tachiyomi/data/sync/AndroidSyncScheduler.kt) | 进程创建时可触发 STARTUP；周期任务受 CONNECTED 约束；现已在 ON_START 合并恢复判定并登记一次性补偿任务；隔离 API 26/33/35/36 AVD 已完成锁屏、Doze 和 force-stop 生命周期检查 | R5 仍需在真实同步空间中验证进程重建、网络切换与条目进度连续性 |
| [SyncWorker](../app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncWorker.kt) | 现已区分 PERIODIC/RECOVERY，使用独立同步通知；网络失败按有界预算返回 retry；恢复 Worker 只执行一次交换 | R5 验证通知权限、前台提升受限、Android 版本配额与停止原因 |
| [SyncPanelController](../data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanelController.kt) | 手动同步及首次合并仍由既有协程驱动；现已读取持久化运行状态、阶段计数与分页日志 | 在真实同步空间中补齐首次合并异常收口和条目推进验收 |
| [SyncCoordinator](../domain/src/commonMain/kotlin/mihon/domain/sync/runtime/SyncCoordinator.kt) | 进程内单执行者，现已增加 RECOVERY 触发类型并与手动请求共用 flight | R5 覆盖前台、周期、手动同时到达时的实际竞争 |
| [SyncRuntime](../data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRuntime.kt) | 现已把运行意图、阶段、尝试、ownerSession 和结束状态写入本机 SQLDelight 表；恢复只接受系统中断状态 | 真机验证进程重建、上传响应未知与空间切换时的恢复资格 |
| [SyncDatabaseExchange](../data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt)、[SyncOutboxStore](../data/src/commonMain/kotlin/mihon/data/sync/journal/SyncOutboxStore.kt) | 初始导入、收件与投影已有分段事务；上传前保存完整制品，确认后才清队列 | 从真实事务与发布确认产生进度，复用既有恢复边界 |
| [DesktopSyncScheduler](../app-desktop/src/main/kotlin/mihon/desktop/sync/DesktopSyncScheduler.kt) | 进程内定期检查墙钟，最长检查间隔 60 秒；启动和周期触发现已先调用共享 `resumeIfNeeded` | 保持现有定期语义，共享新状态与启动恢复；不承诺桌面专用电源唤醒即时性 |

因此应纠正“没有 checkpoint”的笼统说法：已有业务数据检查点，缺的是持久化运行意图、可观察进度与前台恢复编排。隔离 AVD 与 API 33/35/36 的 R8 release instrumentation 已证明平台和发布 wiring 路径可运行，但尚无用户设备的系统停止原因或真实同步空间故障复现，不把待机直接认定为截图卡住的已证实原因。

## 3. 页面交互

### 3.1 入口与布局

沿用“书架 → 同步”。首次配置的合并阶段与日常同步都使用同一进度组件：阶段标题 → 当前阶段计数／进度条 → 最近 5 条条目日志 → 适用操作。记录页提供本次运行的更多日志，以分页加载，沿用现有导航与面板大小。

收起不取消运行，重开恢复实际状态。后台恢复不打开面板、不改变当前阅读页、不抢焦点。正常恢复直接进入运行状态，不额外弹出“恢复成功”。运行完成移除忙碌状态；在当前可见面板保留本次结果，收起后不补播成功通知，历史可在记录页查看。

### 3.2 状态与用户操作

| 实际状态 | 页面文案示例 | 操作与恢复规则 |
| --- | --- | --- |
| 正在核对已有进度 | 正在恢复同步… | 展示已保存计数；自动继续，不要求点击继续 |
| 执行中 | 正在整理本机数据／获取同步数据／合并数据／上传变动 | 显示真实阶段与当前条目，可“暂停同步” |
| 网络不可用 | 等待网络连接 | 保留进度；网络恢复自动尝试，可暂停 |
| 已请求执行但系统尚未启动 | 等待继续同步 | 不伪装成正在处理；前台执行路径尝试接管 |
| 暂时网络失败 | 连接中断，正在重试 | 展示下次重试时间；保留检查点，不回到零 |
| 用户主动暂停 | 同步已暂停 | “继续同步”；回到前台、网络恢复和后台任务均不得自行撤销暂停 |
| 重试预算耗尽 | 连接失败，已保留进度 | “重试”；不在每次重绘／切前台时重置预算 |
| 授权失效 | 需要重新连接 GitHub | 进入既有连接入口，不循环重试 |
| 数据、私有性、空间身份或存储异常 | 同步未完成，并显示可理解的具体原因 | 沿用既有修复入口，保留有效数据，不以网络重试掩盖 |
| 完成 | 已完成本次同步 | 忙碌状态结束；待用户确认的取消收藏／关注仍独立显示 |

重试耗尽与阻塞状态会从本机最近一次运行记录恢复到面板，即使用户关闭后重新打开也保留计数与条目日志。重试耗尽提供“重试同步”以创建新的手动运行；BLOCKED 显示 `stopReason` 对应的授权、存储或数据原因，并复用已有修复入口。WAITING_RETRY 显示 `nextRetryAt` 倒计时，不把等待状态伪装成执行中。

“暂停同步”暂停当前逻辑运行，保存用户暂停意图，不回滚已确认结果。它与现有“暂停首次导入”区分：后者只暂停基线导入，仍允许其他变动交换；按钮命名必须明确范围，不能把两个动作绑定到同一布尔值。“取消同步”入口如保留，则终止本次运行的自动恢复，不清除业务队列、不关闭周期设置；以后的明确新请求按原语义执行。断开／换空间沿用确认框并撤销旧运行的执行权。

### 3.3 进度的计数定义

- 以当前阶段为单位，不混加本机导入、远端合并和上传计数；同一业务操作可能经过多个阶段，不能因此重复计为“总体完成”。
- 已知总量才显示“已处理 24 / 60 项”和确定进度条。远端枚举或身份核对尚无总量时显示阶段与活动指示，不生成猜测百分比或预计剩余时间。
- “项”的统计单位是冻结范围内的同步操作／导入单元，并在阶段内保持一致；条目日志可合并显示漫画名称及操作说明，不能把漫画数冒充事件数。
- `processed = completed + skipped + failed`，另列失败／跳过；失败全部处理完不等于同步成功。上传只有远端确认后才计完成，响应未知显示“正在确认结果”。
- 运行内各阶段冻结处理范围；运行期间新增本机操作留给后续轮次。新的远端快照需要继续处理时建立下一阶段段落，明确总量变化，不让已完成数倒退。
- 重试复用同一 runId 与操作身份，不重复增加完成数量。阶段计数来自持久化事实，UI 重绘和计时器不推进进度。

### 3.4 条目日志

默认展示最近 5 条，包含漫画／作者名、用户可理解的操作、阶段与结果；如“作品 A · 阅读记录 · 已合并”。未知标题使用“阅读记录”等类别，不展示内部路径、Git SHA、令牌、密码、仓库原文或异常堆栈。

条目最终结果以同一运行、阶段、业务身份去重；“处理中”必须有实际执行者，进程重建后改为待恢复。等待、重试和中断原因以系统事件行补充；没有证据时写“上次同步未完成”，不能写“设备待机导致暂停”。

运行概要默认保留最近 20 次；每次最多保存最近 500 条详情，超过后显示“仅保留最近 500 条”，累计计数独立保留。非终态运行和业务恢复数据不因日志保留上限删除。日志位于本机应用状态，不上传、不进入普通备份；锁屏通知只显示阶段和数量，不展示作品标题。

### 3.5 现有 DEMO 的差异

当前 DEMO 已将“设备待机后恢复 · 进度与日志”改为返回应用后自动恢复；“继续同步”只出现在用户主动暂停或重试耗尽。双端均覆盖未知总量、等待网络、授权阻塞、成功收口和焦点隔离。DEMO 仍是本地交互原型，不代表真实 Android 后台任务、持久化或跨设备通信。

## 4. 技术方案

### 4.1 共享运行编排与持久化

在既有 SyncRuntime / SyncCoordinator 上复用 `coordinator.synchronize`，由 `SyncRuntime.resumeIfNeeded`、`cancelSync`、`pauseSync` 和 `SyncRunStore` 提供恢复、用户操作与只读运行状态。Panel、启动、周期和平台 Worker 均经过同一执行入口，执行仍调用原有数据库交换链；`SyncProgressReporter` 只记录真实阶段与条目结果。

本地运行表保存 runId、空间与 generation（连接修订）、原始触发、恢复策略、状态、阶段、计数、尝试次数、下次重试时间、最近真实进展、停止原因及执行者身份。状态采用 QUEUED / RUNNING / WAITING_NETWORK / WAITING_RETRY / WAITING_SYSTEM / PAUSED_USER / BLOCKED / SUCCEEDED / CANCELLED；阶段另存，避免阶段与失败原因混用。字段仅属于本机，不更改远端协议。

执行前先提交运行意图，再安排执行。数据库和 WorkManager 不是同一事务：启动／回前台时对账，补齐“意图已保存、任务未入队”的窗口；多余 Worker 发现运行已结束则成功退出。队列非空本身不证明用户要求立即同步：普通新变动仍遵守启动／周期偏好，只有已接受且未完成的运行自动恢复。恢复前先以条件更新取得 ownerSession；取得失败不发起网络交换。

每个空间同一时刻只有一个执行者。沿用进程内协调锁，增加持久化 attemptId／ownerSession 与条件更新，防止旧尝试覆盖新状态和日志。执行者不能仅因墙钟时间过期被接管；同进程先取消并等待旧任务释放，应用新进程在没有健康本地执行者时把孤立 RUNNING 标为 WAITING_SYSTEM，再取得执行权；claim 失败不得发起网络发布。需要定时检测时使用可注入的单调时钟与会话身份，不用心跳超时作为并发写许可。

检查点复用 sync_imports、收件箱、投影状态和 prepared_upload；业务提交与对应计数尽可能同事务，无法同事务时以已有事实对账，不能让 UI 计数成为数据提交依据。初次连接还须恢复 pendingSetup，在真实交换成功且导入清空后补 complete 标记，避免数据已同步而配置页永远 MERGING。

### 4.2 返回应用立即恢复

Android 复用 ProcessLifecycleOwner 的 ON_START 作为统一前台入口；冷启动由 `AndroidSyncScheduler.start` 经同一判定合并，不同时在多个生命周期回调各开一轮。`SyncWorker` 也只通过同一 `SyncRuntime` 检查恢复资格，避免 Worker 先恢复一次、再重复交换。

恢复顺序：读取已接受的未完成运行 → 检查用户暂停、连接身份、错误和重试预算及 `nextRetryAt` → 观察已有健康执行者 → 没有执行者且到达重试时间时在应用前台的应用级协程中立即申请执行权并继续，同时登记一次性 WorkManager 作为持久后台补偿。尚未到重试时间时只保留运行意图并安排延迟唤醒，不启动业务请求；前台路径不等待下次周期、Worker 退避或 expedited 配额；数据库与初始化尚未就绪时先展示恢复状态。

Worker 与前台执行器竞争同一执行权。已有执行者时只订阅状态或退出本次冗余执行，不把恢复观察当成新同步请求追加一轮。需要从被系统停止的 Worker 转交时，先完成取消和释放再启动前台执行；跟随者取消不能取消 owner。应用进入后台时，前台执行器在安全边界交还任务，后台补偿从检查点继续；交接不能丢请求，也不能双重发布。

旧 HTTP 连接可能在唤醒后仍等待：复用 SyncHttpClient 的协程取消到 Call.cancel 链，给同步请求及阶段增加有界超时。返回前台只取消已经超时或确认失效的尝试，不能每次聚焦都重启健康同步。上传响应未知时先读回确认，复用冻结制品，禁止直接重发不同批次。无法及时释放旧执行者时显示可诊断的失败／等待状态，不并发启动替代写入。

“立即”的拟验收目标：受控设备、应用已可交互且本地存储就绪后 1 秒内呈现恢复／等待原因；网络可用且没有健康旧执行者或未释放事务时，2 秒内进入真实恢复步骤或发出首个业务请求。分别记录生命周期、意图提交、获执行权及首个请求时间。它是待实测的应用响应目标，不是对网络完成、冷启动耗时或系统全局调度的保证。

### 4.3 WorkManager 与后台连续性

保留唯一周期任务；现已新增 `mihon-sync-recovery` 一次性任务，使用 CONNECTED 约束并按 `nextRetryAt` 设置初始延迟。唯一任务名与 KEEP 用于减少重复调度，但不能替代执行权：前台恢复先检查持久化运行，退避 Worker 只作为补偿；恢复 Worker 不再调用两次恢复交换。Worker 与手动继续都复用同一 runId，ownerSession 条件更新阻止旧尝试回写。

短而用户可感知的一次性请求可使用 expedited，配额不足降为普通任务，不丢任务。长时、用户已发起且需要后台继续的同步，提供 dataSync 前台通知及取消／暂停操作；周期同步保持可中断的有界工作片段，不把全部周期任务提升为长期前台服务。

通知使用独立同步 channel/id，并在 Android 26–31 提供 expedited 所需 ForegroundInfo。不能直接复用返回 Unit 且吞掉启动限制的 `setForegroundSafely()` 作为成功证据：同步 adapter 应能得到“已提升／受限制”结果，受限则保留恢复意图并按普通任务／前台路径继续。通知权限拒绝、启动限制、超时、配额不足分别可观察；不把权限声明当成服务实际启动。

Android 16 长时间 Worker 仍受 job 配额影响，前台服务也不是待机期间永不停止的保证。Android 15+ dataSync 超时必须正确停止并保存进度。首轮交付以“返回应用自动继续”为必要目标，不要求用户关闭电池优化、不增加精确闹钟或屏幕常亮。

### 4.4 UIDT 的采用条件

保留上一轮调研的 Android 14+ UIDT 选项，作为长时间、用户明确发起网络传输的可选 adapter；不把自动前台恢复或周期触发伪装为新的用户发起任务。本轮基础实现不依赖 UIDT，也不为纯本地合并增加 UIDT 服务。

只有正式运行证据证明前台 Worker 的配额影响所需长传输，并确认该任务满足 UIDT 条件时，才在 roadmap 的决策点提出采用；说明新增 JobService、权限、通知、用户停止处理和 Android 26–33 回退的成本后审批。未采用时记录原因，不留下必选却未接线的分支。用户从系统任务管理器停止 UIDT 不得被后台自动重排。

### 4.5 网络、暂停与失败

网络回调只提示重新判定，不证明 GitHub 可达；使用实际 production 客户端确定 DNS、代理、TLS 和 HTTP 结果。每次连接状态变化合并为一次尝试，不用持续探测保持后台活跃。

网络失败与有网络问题的 PARTIAL 都保留未完成运行；首次失败后至多 3 次自动重试，退避 10 秒、30 秒、2 分钟写入 nextRetryAt。未到时间的周期、Worker 和回前台触发只观察并退出，不清除 nextRetryAt；确认联网可提前唤起一次，但仍受同一预算、服务端 Retry-After 和去重约束。后台实际时间允许更晚。第三次网络失败进入 FAILED／`retry_exhausted`，用户需发起新的手动运行；授权、空间变更、私有性、数据损坏和存储错误进入 BLOCKED，页面保留具体原因并提供既有修复入口，不自动绕过校验。

恢复既有运行与开启新自动同步分开：关闭“启动时同步”不否定此前已确认、尚未完成的运行；主动暂停／取消该运行则禁止恢复。关闭周期同步只停止未来周期触发，不误取消手动运行。所有取消都先持久化意图再停任务；系统直接杀进程没有 finally 回调也能从上次检查点恢复。数据库空间不足导致意图保存失败时不开始新的远端写入。

## 5. 复用、兼容与安全边界

共享状态、计数、恢复资格和失败规则放 domain/data；Compose 视图放 presentation-sync。Android 生命周期、网络回调、Worker 和通知留在 app adapter；Desktop 沿用已有 runtime service，不引入 Android API。所有入口使用同一 Injekt graph 和 production HTTP 配置。

历史实现曾通过 27 号增量迁移加入本机运行／日志状态；该编号与作者开发分支冲突，不能继续作为安全升级方案。修复采用固定作者结构基线与已交付数据库谱系识别，具体路径见修复设计第 2 节。旧版本没有运行记录时不根据陈旧 lastAttempt 猜测“待机中断”；未完成初次配置须从 pendingSetup 和导入事实对账，其余按既有启动／周期选项进入新运行。迁移不触碰同步事件身份、加密制品或远端文件。已有备份过滤须覆盖新增本机状态。

日志截断、页面计数错误不能改变收件／上传结果；暂停、取消、切空间和断开必须核验空间与连接修订，旧执行者不得写入新空间状态。用户待确认的取消条目不阻止其他同步，也不被“同步完成”自动确认。

## 6. 官方依据与限制

核对日期：2026-09-19。以下是平台约束，不是本机故障复现证据。

| 官方资料 | 对方案的约束 |
| --- | --- |
| [Doze 与 App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) | 待机可能延迟 CPU、网络和后台任务；唤醒不等于一次同步已完成 |
| [ProcessLifecycleOwner](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner) | 复用应用前台事件；不把它当毫秒级电源唤醒通知 |
| [WorkRequest、expedited 与约束](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) | expedited 仍可能延迟；KEEP、入队和 CONNECTED 不保证立即执行业务请求 |
| [长时间 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) | 需要正确前台通知及服务类型；Android 16 有 job 配额限制 |
| [前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout) | Android 15+ dataSync 后台运行受累计时限约束，必须处理停止 |
| [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt) | Android 14+、用户明确发起、可见时调度；系统仍可能停止，必须持久化进度 |

## 7. 完成判据

1. 双端面板的阶段、计数和条目来自真实提交；未知总量、失败、等待不会显示虚假百分比或持续运行。
2. Android 回前台能在无需点击继续的情况下恢复系统中断的已接受运行；用户主动暂停保持暂停。
3. 冷启动、网络变化、周期和手动触发同时到达时只有一个执行者；前台恢复不被旧 Worker 退避困住。
4. 杀进程、丢上传响应、切换空间与数据库失败后，已完成条目不重复应用，未确认制品不丢失或重新生成。
5. 真机／模拟器覆盖 API 26、33、35、36 的适用分支，正式 R8 APK 验证；Doze 与实际锁屏分别测试。发布和运行证据不能用 DEMO 替代。
6. 设计、原型、生产状态与实际验证一致；具体任务、验收和发布门槛见 Roadmap。
