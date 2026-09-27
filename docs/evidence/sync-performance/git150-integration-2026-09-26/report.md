# 1,600 本书：分组发布整合后的实测

日期：2026-09-26。实验基线：`a291191bfba9dd8f0b1635f51823d365e4e88ef9`。

**真实 Runtime 整合后的发布及回读确认是 21.471 秒，完整首次同步是 64.814 秒。同制品原生 Git 是 14.758 秒，发布阶段为 Git 的 145.49%，本轮进入 150% 以内。** 这次是实际组合运行的结果，没有拼接独立实验耗时。

先纠正历史报告口径：用户最初依据 88.516 秒 REST 与 14.592 秒 Git 的对照提出 150% 目标，比较的是已生成制品的发布。先前预研报告将目标扩大为“完整首次同步也必须不超过 Git 的 150%”，没有明确区分，应以本报告的分层结果为准。

## 本轮结果

| 指标 | 上轮本地优化、仍逐批发布 | 本轮实际整合 |
| --- | ---: | ---: |
| 发布及回读确认 | 90.134 秒 | **21.471 秒** |
| outbox 全流程，含准备、持久化、本地确认 | 99.413 秒 | **25.423 秒** |
| 接受初导 → 全部完成 | 142.249 秒 | **64.814 秒** |
| 接受初导 → 导入生成完成 | 17.678 秒 | 17.558 秒 |
| 接受初导 → 首次确认可见 | 37.591 秒 | **63.093 秒** |
| HTTP 请求 / ref 更新 | 794 / 41 | **84 / 1** |
| 请求正文 / 响应正文 | 19,338,640 / 19,682,529 B | 19,300,062 / 54,917 B |

发布确认耗时减少 **76.18%**，完整流程减少 **54.44%**；相对最初 153.592 秒完整流程减少 57.80%。这是前后各一次样本，不代表统计显著性或所有设备的保证。

本轮 Git 使用 Runtime 刚生成的同一份最终加密文件：`add 0.656s + commit 0.165s + push 13.622s + ls-remote 0.315s = 14.758006s`。150% 门槛为 **22.137009 秒**；21.4714356 秒低于门槛 **0.6655734 秒**，余量很小。Git 4 次 HTTP、请求正文 13,844,359 B、响应 573 B。

`outbox.publishWithConfirm` 包含新发布入口内的制品校验、仓库检查、live ref 对账、分段 tree 创建、commit/ref、真实快照回读验证及本地快照观察回调；不含制品生成保存和最终 outbox 数据库确认。首次快照读取另为 0.198 秒；即使加上它，发布相关两段累计为 21.670 秒，约 Git 的 146.83%。这个累计明确取自同一轮，仍不是另一轮冻结 REST 重播。

**含本地准备及确认的 outbox 是 25.423 秒，尚未达到 150%；完整首次同步 64.814 秒也没有达到。** 不将发布阶段达标表述为整个同步只需 21 秒。

## 实际整合与安全边界

- 应用此前的导入进度去重、批量字段读取、描述页缓存，再将分组发布接入真实 `SyncDatabaseExchange → SyncOutboxExchange → GitHubSyncTransport`。没有伪造 PUBLISHED 来领取下一批。
- 一个数据库事务冻结同一 space/generation/actor/epoch 的连续批次。保留每批最多 256 个事件的协议边界；每组最多 50 批、原始密文累计 24 MiB。24 MiB 不是进程峰值内存或 JSON 请求体上限。
- 临时前驱视图只用于准备后续索引，不交给远端快照安全记录。全部随机密文持久化后才写远端。重启先恢复已有 prepared 前缀，不混入后来新增的数据。
- 使用既有 2 MiB tree 请求规划器分段创建对象，只提交、更新 ref 一次。未知结果先读 live ref 对账；检查全部批次与索引绑定、最终头以及各文件 Git OID，不回读全部批次大正文。
- 确认后先重验全体保存制品，再在单事务标记全部成员已发布并清理其发现记录。保留外来发现；Runtime 按真实组成员计数和记录日志。
- 首成员必须延伸 live actor 索引；不可变路径不得被不同内容覆盖。竞争 ref 不强制覆盖，保守返回未确认/冲突。

本轮没有数据库迁移或协议格式修改。未解决的生产边界包括：并发导致远端 head 已前移时，冻结组的安全重放/重定基策略；同 actor 后继提交的恢复准入；移动端内存、取消与后台行为；正式双端 wiring 和发布验收。因此交付为**已实际运行的整合实验补丁**，归档后恢复生产源码，不直接开启正式应用功能。

一次确认整个组的代价已测到：首次确认从 37.591 秒延后到 63.093 秒，尽管全部完成显著提前。后续若产品要求更早反馈，可比较首个小组先发、剩余大组随后发布；本轮没有尝试，也不能承诺其仍在 150% 内。

## 样本与可比性

沿用 424 本真实收藏加 1,176 本保形扩充、8,700 条公开阅读状态，共 10,300 事件、41 批（40×256+60）。输入 SHA-256：`e2cbcb9dad186f22a15615b374588f8b694e37b2f660e515c160b10ceea17458`。使用真实文件 SQLite、生产 Runtime、加密与 HTTP 实现，冷 L2。

两条通道串行执行，网络模型均为响应首部延迟 50ms、上下行各 10Mbit/s。没有真实 GitHub 写入。Git 从已安装最终文件开始计时，业务导入和加密不计入；Runtime 完整时间从接受首次导入开始。授权、空间发现与实验书架预填充在计时外。HTTP 正文统计不包括 TCP/TLS/headers。

同制品验证通过：初始 tree `5efbacf841cbc2db6cecf18bb5d96abfadc77a73`，最终 tree `e9373f96f1d066fc736b1ce7d80f8e2e50083a95`；两端均为 86 文件、19,285,180 B，Git 远端逐文件字节一致。与上轮每批事件数/密文大小元组也一致，SHA-256 为 `0aca4b2580f2429bccfff28890138597a43ae27cdd597579e8b4852ad46c91d1`；各轮随机密文本身不同，不能称历史轮次为同制品。

只有一次正式 1,600 条 Runtime 和一次同制品 Git 测量，无 p95/置信区间。REST 服务端为内存夹具，Git 为真实本机 git-http-backend，服务端成本不对称；不外推成公网 GitHub 或 Android 成绩。

## 验证证据

- Runtime 300 条先 RED：旧路径不能合并发布；整合后 GREEN，2,433 事件/10 批/1 ref。小样本结果不混入正式大样本。
- 伪造首前驱的 shared contract 先 RED 后 GREEN，拒绝发生在 ref 更新前。
- 文件数据库测试：第二成员制品保存失败整组回滚；关闭重开后的未知 ref 对账不重复发布；后加事件不入组；错误 generation 拒绝；第二成员确认失败整组回滚；成功确认只清理 own 发现、保留 foreign。
- 新发布入口的竞争 ref 测试：不 force、不确认、保留竞争写入。
- 恢复测试发现新校验的 Elvis 默认值 `0` 与 `Long` 比较误报冲突，改 `0L` 后通过。另一次测试把分页结果误当全部，改为核对两页后通过；没有删除回滚断言。
- `:data:spotlessCheck`、`git diff --check` 通过。正式 Runtime **1 test / 0 failures / 0 errors**，严格验证 41 批、1 ref、所有批次 PUBLISHED、序号 1..10300 连续、事件总数一致。没有重跑第二轮正式样本。
- 主代理独立审查准备/持久化/远端确认/队列/Runtime 集成，提出前驱锚检查并复核修复；归档补丁在临时 Git index 正向应用与反向检查通过。

这是研究整合验证，未运行全量 Android/Desktop 测试或正式 APK/EXE 构建，没有可交付安装包。真实设备与公网最终验收仍由用户执行。

## 产物与复现

[累计整合补丁](integrated-prototype.patch) 可直接应用于上述基线，已包含之前两份原型，**不要叠加旧补丁**。补丁 SHA-256：`0a473f226aa24a85507aa44e88a09cad4420864ae7ef84829ccdb7369211587c`。15 个源码/测试文件属于同一真实链路整合与累计本地优化，超过估算行数是上下游接口、持久化和契约共同变更，未拆成不可验收碎片。

[聚合 JSON](results.json) 保存本轮/前轮指标、Git 对照、原始证据哈希及协调器状态。私人输入、数据库、密文和原始日志只留在忽略目录 `.gradle-coordinator/sync-integrated-research/`，不提交。

应用补丁后，复现须使用新的输出目录和协调器 key：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:SYNC_COMPARE_INPUT = "$PWD/.gradle-coordinator/git-compare-1600/input.json"
$env:SYNC_COMPARE_OUTPUT = "$PWD/.gradle-coordinator/sync-integrated-research/reproduce-1600"
$env:SYNC_COMPARE_COUNT = '1600'
$env:SYNC_COMPARE_REQUIRE_GROUP = '1'
$env:SYNC_COMPARE_SKIP_REPLAY = '1'
$env:SYNC_COMPARE_JFR = '0'
python scripts/gradle-coordinator.py run --key sync-integrated-reproduce -- .\gradlew.bat --offline :data:jvmTest --rerun --tests mihon.data.sync.SyncGitCompareAcceptanceTest
# Runtime 完成后串行运行 Git，避免争用。
python -X utf8 scripts/sync-git-comparison.py git --initial "$env:SYNC_COMPARE_OUTPUT/initial" --final "$env:SYNC_COMPARE_OUTPUT/final" --work .gradle-coordinator/sync-integrated-research/reproduce-git --output .gradle-coordinator/sync-integrated-research/reproduce-git.json
```

下一步应优先决定首批确认体验与完整同步目标，再处理约 15.953 秒导入 process 和 19.739 秒字段投影，以及组准备/确认约 3.94 秒。以上为本轮可见剩余成本，不等于可以全部消除；无需为了发布阶段目标继续盲目增加传输方案。
