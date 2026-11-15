# 同步阻塞自动恢复修复与现场验收

日期：2026-11-15。问题和原始原因边界见[调查记录](../2026-11-15-sync-v4-auto-recovery-diagnosis.md)。本记录对应 Windows 本地体验包，不代表发布矩阵、原生视觉验收或用户验收。

## 实现边界

- 旧 `BLOCKED/MERGING/UNKNOWN` 只能凭当前启用空间及本轮已接收下载事实进行本地探测，保留原 run、检查点、已保存决定和确认账本。
- `local_projection` 持久保存仅本地恢复身份，进程中断后不能变成普通远端同步许可；成功投影和收尾后才回到原计划。本地失败按 `local_projection_storage` 冷却复核，不能静默清库或换空间。
- 本地恢复串行且可取消。暂停/取消优先停止并等待子任务，禁止并发恢复覆盖 owner 或停止任务引用；禁用空间、不支持格式和非目标 UNKNOWN 仍拒绝接管。
- Desktop 以 Runtime 的持久任务和 deadline 唤醒，覆盖重启、远端变化及新完成信号；查询暂时失败后记录诊断并冷却复核。网络短重试后转为十五分钟低频重试，尊重服务端和账户 gate。
- SQLite 数值错误码进入脱敏 journal；SQL、参数和任意异常文案不入库。没有新增 schema、同步协议、依赖或页面。

涉及的生产文件共同构成“旧轮次本地恢复 → 持久化准入 → 平台唤醒 → 状态反馈/诊断”的单一链路；超过常规文件/行数提示主要来自并发、真实数据库失败及 DI 回归，未拆成不同恢复引擎或无关重构。

## 验证证据

仅运行定向范围，没有完整模块、全仓测试或正式发布构建。协调器原始日志保存在忽略目录 `.gradle-coordinator/`。

| 记录 | 结果及范围 |
| --- | --- |
| `sync-local-red` | 旧 UNKNOWN 本地恢复取得预期行为失败；非 MERGING UNKNOWN 的拒绝边界通过 |
| `sync-retry-red` | 网络第四次永久结束、SQLite 扩展码未保存，均取得预期失败 |
| `sync-scheduler-red` | 退避重启与远端变化未自动续接，两项预期失败 |
| `sync-scheduler-lookup-red` | 暂时查询错误导致调度器停止，取得预期失败；生产 DI 到期恢复集成已通过 |
| `sync-local-green-fixed` | 14 项中 13 项通过；真实锁夹具使用 SQLDelight 借还连接导致手工事务不保留，一项失败，不计产品通过 |
| `sync-data-final` | 改为持有真实 JDBC 第二连接后的锁竞争及暂停/取消两项 JVM 通过；Android Debug 三项共享旧状态/owner 契约通过 |
| `sync-data-frozen` | 新 JVM 类去除多余契约继承后，仅其三项真实锁、并发停止、中断/禁用回归全部通过；最终限定文件 Spotless Apply/Check 通过 |
| `sync-desktop-green` | Scheduler 11 项及真实 Desktop DI + Runtime + SQLite 持久任务到期接续 1 项，共 12 项通过，零跳过 |
| `sync-scoped-format-final-2` | 临时 init 脚本将 ktlint/Spotless 精确限定本次 Kotlin 文件，实际 Apply/Check 通过；Desktop 复用已有格式插件，不修改项目依赖 |

这些是基线与受影响范围补验组成的证据，不能表述为“最终代码重新通过完整模块/全量测试”。期间修正了测试准备顺序、合成令牌有效期和真实锁持有方式，没有通过放宽产品断言取得通过。

独立审查发现暂停/取消、禁用绑定、失败冷却、并发接管及中断后本地权限标记问题；实现后由未编写 Runtime 的主代理核对互斥、epoch/停止闩、事务 claim 和持久标记，并结合上述实际回归关闭。原始 SQLite 异常缺少具体错误码的历史限制没有被新功能倒推消除。

## 候选与现场流程

- 入口：`scripts/build-desktop.sh preview`，协调器 `sync-recovery-preview`，构建及 production 扩展运行时检查通过。
- 实际候选：`app-desktop/artifacts/preview/windows/20261115T145158Z-d89a4258/Mihon-Desktop-0.11.24.10.c79ab30-unpacked/Mihon Desktop.exe`。
- manifest 明确记录构建时的 dirty 源码输入；生产输入摘要为 `20d66790add56fde6b1f0fb450ce402c67e433225863079cb42bc04f0fd3dc05`。原生交互层为 `NOT_RUN`，不能将无窗口实际运行冒充屏幕验收。
- 首先备份用户数据库并校验完整性。自验前发现另一版本在首次备份后启动过，因此另存紧邻自验的最新快照；后续还原以最新快照为准，保护这期间写入。原始备份也保留。
- 实际候选以原 profile 的 headless Test Mode 启动，未点击同步、重试、检查保存数据或替用户决定取消收藏。进程、EXE、参数与端口均核对归属。
- 22:53 启动后，旧任务从第 4 次 `BLOCKED/UNKNOWN` 自动进入第 5 次 `local_projection`；当前空间全部 11,920 个 dirty 字段自动处理，53 份下载回执、11,956 项已确认，剩余一份回执对应真实业务待确认。
- 之后自动进入原任务第 6 次远端同步，实际 HTTP 成功返回并持续上传既有计划；没有通过新建轮次、清除失败或隐藏待确认伪装成功。

22:58 观察到本轮 11 个上传批次、2,643 项全部确认，累计确认 14,599 项，活动空间 dirty 字段归零，无新 EXCEPTION/HTTP_FAILURE。终态为 `PARTIAL/pending_decision`，仅本轮一份下载回执因真实取消收藏选择暂不确认；不冒充 `SUCCEEDED`。另有不属于该冻结计划的 176 项排队数据没有被报告为本轮已完成。

自验应用通过自身关闭入口正常退出。还原前再次取得原 profile 单实例文件锁，确认 actor/epoch/next_seq 与自验前一致；先保存自验后的独立数据库，再由 SQLite 完成空 WAL 检查点，防止旧 sidecar 被套用到还原文件。23:01 原数据库恢复至自验前最新快照，文件 SHA-256 与快照一致，完整性检查返回 `ok`；再次读取为 `BLOCKED/MERGING/UNKNOWN`、第 4 次尝试、0 项确认、11,920 个活动 dirty 字段，与备份现场一致。候选没有重新启动，留待用户验证自动恢复。

本地还原不会撤销远端已经确认的上传。此次只补发已存在的稳定批次，actor 序号没有推进；重新同步依靠既有批次/事件身份进行幂等对账，不回滚新凭据。私人数据库、密封记录、备份清单、进程控制和详细采样保存在本机备份及忽略目录，不入库、不上传。用户原始备份、自验前最新快照和自验后副本均保留。
