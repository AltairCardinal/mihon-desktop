# 首次同步耗时不超过 Git 150%：5% 额度预研

## 目标与结论边界

用户目标是同一份 1,600 本书样本从开始同步到全部确认，不是暖增量或单个 API 的速度。本轮沿用 [前次配对对照](../git-comparison-1600-2026-09-25.md) 的输入指纹 `e2cbcb9dad186f22a15615b374588f8b694e37b2f660e515c160b10ceea17458`：424 本真实收藏加 1,176 本保形扩充，包含 8,700 条公开阅读状态，共 10,300 事件、41 协议批次。生产源码基线为 `89b03329d6`。

固定受控模型仍为每响应首部延迟 50ms、上下行各 10Mbit/s。原生 Git 同制品 `add + commit + push + ls-remote` 为 14.591507s，所以本轮门槛为 **21.8872605s**。授权、空间发现/解锁和预填实验书架不计时；完整 Runtime 从接受初导开始计时。没有访问或写入真实 GitHub 仓库，没有改变真实书架。

21.887s只适用于这组固定基准；换网络、机器或制品后必须重测配对的 Git 分母。当前原型仍有77个HTTP而Git为4个，上传正文也更大；不能据这一次结果保证任意延迟/带宽下都保持150%以内。

**已证明发布阶段存在进入 150% 范围的路线；尚未证明完整首次同步可以进入该范围。** 不能把预先生成文件的发布结果称为完整首次同步达标，也不能把本机单样本外推成 Android 或公网 GitHub 成绩。

## 分段诊断

复用真实 Runtime、文件 SQLite 和 production transport，增加低基数、单调时钟累计，并完整复验事件数及冻结制品重播。一次诊断样本结果：

| 指标 | 本轮诊断 | 前轮未插桩样本 |
| --- | ---: | ---: |
| 接受初导 → 导入生成完成 | 38.481s | 34.829s |
| 接受初导 → 首批确认 | 60.908s | 53.176s |
| 接受初导 → 全部确认 | 163.509s | 153.592s |
| 冻结制品 REST 重播 | 88.529s | 88.516s |
| Runtime HTTP / 批次 | 794 / 41 | 794 / 41 |

单样本存在运行波动及插桩差异，不将 9.917s 的端到端差异归因为某个确定原因。关键累计如下，包含嵌套项，**不可直接相加**：

| 分段 | 累计耗时 | 次数 |
| --- | ---: | ---: |
| 初导 `baseline.process` | 17.820s | 206 |
| 本地字段投影 `projector.project` | 19.629s | 231 |
| outbox 全流程 | 96.844s | 42 |
| └ 发布及确认（含确认回调） | 89.409s | 41 |
| └ 保存冻结制品 | 2.545s | 41 |
| └ 本地确认 | 2.118s | 41 |
| └ 领取批次 | 1.795s | 42 |
| └ 加密及 tree 制品准备 | 0.646s | 41 |
| snapshot observe（另有嵌套） | 1.121s | 125 |

源码确认 `SyncDatabaseExchange` 先处理全部初导页，再进入远端交换；每页 50 条，重复写 IMPORTING 阶段和 ACTIVE/COMPLETED 日志。17.820s 的 process 累计与 38.481s 的导入完成时间之间的残差不能直接称为“日志耗时”。本轮没有理由继续把纯加密或历史 tree 缓存当作首次等待的唯一主要原因。

## 发布原型：有界 staging tree，一次提交与确认

保留全部 41 个现有协议批次及其加密字节，不改变 256 事件/批次边界。使用现有 `SyncTreeEntryPayloadPlanner` 的 **2MiB 本地请求预算**，按文件分组创建未发布的 tree，下一组以上一 tree 为 base；全部对象齐备后才创建 commit、以 non-force 更新 ref，最后通过 production `readSnapshot` 验证索引/头并比较所有预期文件 OID。没有先伪造 PUBLISHED 来绕过 outbox。

| 同一冻结制品 | 原 REST 重播 | 合并发布原型 | 原生 Git |
| --- | ---: | ---: | ---: |
| 总耗时 | 88.516s | **20.755s** | 14.591507s |
| HTTP 次数 | 788 | **77** | 4 |
| 上传正文 B | 19,338,640 | 19,300,062 | 13,845,216 |
| 下载正文 B | 19,680,533 | **52,703** | 573 |
| ref 发布次数 | 41 | **1** | 1 |

原型为 Git 耗时的 **142.24%**，比原 REST 重播减少 **76.55%**。最大写请求 2,015,177B，小于 2MiB。请求构成为 11 次 tree POST、1 commit POST、1 ref PATCH，另有 44 blob GET、15 tree GET、2 ref GET、2 commit GET 和1次仓库读取。没有依靠激进并发；主要减少反复提交/确认，以及用已冻结字节的对象身份检查避免回读整个 batch 正文。

最终 86 文件、19,285,180B 与原 Runtime/Git 完全一致；初始 tree `aacceda21fa52a5f052574fca8351468ce0e9b1a`，最终 tree `134c7517f70e6e8b12963ae9e0ec667fe11e107f`。大样本实际经过网络模型、生产序列化/HTTP/快照验证和事后远端逐字节核验，不是请求数乘延迟得到的估计。

TDD RED：原型方法返回初始 snapshot 时，正确失败于最终 tree OID 不一致；实现后同制品 GREEN。独立审查要求本研究进一步收紧为“读回的 live ref 必须是本次 commit”，不只接受相同最终文件树：模拟另一个提交写入相同文件的场景先 RED，再加断言 GREEN。修正没有增加网络请求，但 **20.755s 来自该断言修正前版本**，没有把修正后的6场景小样本冒充再次大样本测速。

六个小样本故障场景通过：丢失 ref 响应可读回确认；更新后 ref 不可读不确认；竞争写入不覆盖对方；发布后 batch 字节损坏不确认；tree staging 失败保持原 ref；另一个相同内容的 commit 不按本次 commit 确认。这里只证明实验边界内的失败关闭，未覆盖所有恢复场景。

原型仍缺：持久的分组归属或可验证重建策略、跨进程恢复、组与现有 outbox/发现队列的整合、多 actor 合并、并发后继提交的准入、Android 资源上限。只接受本次空同步空间到最终树的已验证冻结文件；不支持任意含额外文件的仓库，不把 caller-owned 字节当通用可信接口。预加载全部约19MB文件的实验方式也不是移动端内存验收。因此该方法只能归档为可撤销补丁，不能作为生产功能保留。

## 本地原型：导入进度去重，完成状态只在真正结束后写入

同一 import id 只在首次页写 IMPORTING / ACTIVE，`baseline.process` 返回 remaining=0 时才写 COMPLETED。每页仍为50条，事务、事件顺序、逐页 yield 和取消异常传播保持原语义，没有增大批次或关闭持久化。

真实文件 SQLite + `SyncRunStore` + `SyncDatabaseExchange` 的101条/3页测试先 RED 后 GREEN：原逻辑在第一页完成后就写 COMPLETED；第二页前取消时数据库仍剩51条，却已经报告完成。新原型正常结束剩0、ACTIVE/COMPLETED各一次；取消剩51、没有COMPLETED。主代理复核时发现最初取消注入依赖“第二次 phase”，去重后该条件不会出现；已改为独立循环检查抛出取消，再对旧/新实现重跑有效 RED/GREEN，未拿失效测试作为证据。

小样本持久进度调用实际耗时：原 phase+log 195ms，新46ms；仅是一次局部测量，不直接线性外推1,600条。随后同1,600条完整Runtime对照通过，输入、网络模型及冷L2不变，明确跳过第二遍冻结制品重播：

| 指标 | 本轮诊断基线 | 进度原型 |
| --- | ---: | ---: |
| 接受初导 → 导入生成完成 | 38.481s | **19.244s** |
| 接受初导 → 首批确认 | 60.908s | **37.219s** |
| 接受初导 → 全部确认 | 163.509s | **137.661s** |
| baseline.process 累计 / 次数 | 17.820s / 206 | 17.731s / 206 |
| 字段投影累计 / 次数 | 19.629s / 231 | 15.448s / 231 |
| publish+confirm 累计 / 次数 | 89.409s / 41 | 89.298s / 41 |
| HTTP / 上传事件 / ref更新 | 794 / 10,300 / 41 | 794 / 10,300 / 41 |

导入生成减少19.237s（约50.0%），与process自身耗时基本不变、重复状态写入减少相符，证据足以将重复进度持久化列为明确热点。全部确认比本轮插桩基线少25.848s（15.8%），其中投影段还变化4.181s，不能把全部差值都归给日志。相比前轮未插桩153.592s，少15.931s（10.4%）；这些都是单样本，未随机交替。

**137.661s是只应用本地进度原型、仍逐批发布的真实完整耗时；20.755s是另一条预备文件的分组发布实验。两项没有整合，不能相加或相减后宣称测到了组合后的完整耗时。** 本轮没有安全地把分组发布接入Runtime，且优化后仅导入 process 与投影累计仍约33.18s，顺序处理的本地成本本身已超21.887s目标。

## 被排除的候选：仅改 WAL/FULL

事务分段看起来较慢，因此先做了有界 SQLite 小实验，保留 `synchronous=FULL`，没有测试以 NORMAL/OFF 换取耗时：

- Python SQLite 3.50.4、长连接、每模式 80 个50行事务，WAL/FULL 的 commit 中位约4.5ms，DELETE/FULL约12.3–13.5ms。
- 真实项目 Xerial sqlite-jdbc 3.51.0.0、每事务重开连接、模式通过每连接属性设置，在建库及重连后读回 journal_mode 与 synchronous=2，交错 DELETE→WAL→WAL→DELETE：DELETE 总wall 255.2/229.7ms，WAL 356.2/312.5ms；WAL commit 变短，但 open/close 成本上升，整体反而慢约32–39%。

这不是真实同步收益测量；合成表也不能代表完整业务 schema。它足以否定“根据 Python 长连接结果直接给 Desktop 全局开启 WAL 就会更快”的推断，所以没有为这个无收益信号追加完整1,600条实验或改应用配置。`JdbcSqliteDriver` 逐次连接语义下，一次性 PRAGMA 也不能代替每连接配置。

SQLite 官方说明 WAL + FULL 在每次提交同步 WAL，NORMAL 可能在断电后丢失已提交事务，见 [synchronous](https://sqlite.org/pragma.html#pragma_synchronous) 与 [WAL](https://sqlite.org/wal.html)。本研究不降低同步安全记录的持久性。

## 离完整目标还差什么

即使采用20.755s发布结果，顺序执行只给全部本地工作留下 **1.132s**。当前仅已测的导入处理和字段投影就各约18–20s。不能将这两个不同实验的数字拼接成“完整已达标”。

下一阶段的优先顺序应为：

1. 继续量化并降低本地导入、投影及进度持久化成本，保留共享逻辑与恢复语义。
2. 实现真正的多批发布状态链：冻结成员和制品、只在已验证快照下准备前驱链、组内失败不提前确认、结果未知先对账。现有 `getNextUploadBatch` 总是选择最早未发布批次，不能先标记 PUBLISHED 来取得后续批次。
3. 评估有界重叠“生成/持久制品 → staging 上传”，最后统一发布，使网络传输时间覆盖部分本地工作；先验证取消、重启、换连接及并发写，不凭理想重叠公式宣布达标。
4. 同一书架、同一协议制品重新做完整 Runtime 与 Git 配对。若改变压缩或制品格式，Git 对照也必须使用新制品，不能只让 REST 享受更小文件而保留旧 Git 分母。

GitHub 官方支持 create-tree 的 `content` 和 `base_tree`；这里2MiB是项目自身预算，不是声称 GitHub POST 的官方上限。递归 GET tree 的7MB限制不能误用为 POST限制，见 [Git trees API](https://docs.github.com/en/rest/git/trees)。GitHub另有一般每分钟80次、每小时500次内容生成请求等二级限制，具体端点可能更严，见 [REST rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)。本地夹具未模拟这些服务端限制，77个HTTP也不等于保证实网不限流。

## 复现与交付边界

所有私人样本、制品和实验数据库保留在 `.gradle-coordinator/`。归档补丁仅供在 `89b03329d6` 的隔离工作树应用；不上传原始书名、URL、数据库或加密制品。每次实验仍使用新输出目录和唯一 Gradle coordinator key。主代理审查范围为测量口径、传输与恢复边界、最终生产源码恢复；实施代理承担分段及本地原型，并独立审查其未实现的合并发布原型。

- [聚合数据及有效测试XML指纹](results.json)：不包含书名、URL或加密正文。
- [诊断补丁](diagnostic.patch)：独立应用于基线，运行原1600测试可重现分段；本轮 key=`git-compare-phase-1600`。
- [分组发布原型及故障测试](grouped-publish-prototype.patch)：独立应用于基线；本轮大样本 key=`git150-group-green`，故障修正 key=`git150-group-fault-green`。
- [本地进度原型、分段及测试](import-progress-prototype.patch)：独立应用于基线，**已包含诊断，不先叠加 diagnostic.patch**；有效 RED/GREEN key=`git150-progress-red-valid` / `git150-progress-green-valid`，正式 key=`git150-progress-1600`。
- [真实Xerial微探针](SqliteJdbcCommitProbe.java)与[Python长连接对照](sqlite-commit-probe.py)：只是合成表诊断，未接入应用。

原型运行命令（省略补丁应用前的工作树创建；输出必须换新名称）：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'

# 分组发布：复用前轮完全同一份冻结文件，不能换成新生成的密文来声称同制品配对。
$env:SYNC_GROUP_INPUT = "$PWD/.gradle-coordinator/git-compare-1600/formal-output-v2"
$env:SYNC_GROUP_OUTPUT = "$PWD/.gradle-coordinator/git150-research/new-group-result.json"
$env:SYNC_GROUP_FAULT_INPUT = "$PWD/.gradle-coordinator/git-compare-1600/smoke-l2-output"
python scripts/gradle-coordinator.py run --key new-group-research -- `
  .\gradlew.bat --offline :data:jvmTest --rerun --tests mihon.data.sync.SyncGroupedPublishResearchTest

# 在另一份基线工作树应用独立本地进度补丁。
$env:SYNC_COMPARE_INPUT = "$PWD/.gradle-coordinator/git-compare-1600/input.json"
$env:SYNC_COMPARE_OUTPUT = "$PWD/.gradle-coordinator/git150-research/new-progress-output"
$env:SYNC_COMPARE_COUNT = '1600'
$env:SYNC_COMPARE_SKIP_REPLAY = '1'
python scripts/gradle-coordinator.py run --key new-progress-research -- `
  .\gradlew.bat --offline :data:jvmTest --rerun `
  --tests mihon.data.sync.SyncImportProgressResearchTest `
  --tests mihon.data.sync.SyncGitCompareAcceptanceTest
```

JDBC微探针使用实际缓存的 `sqlite-jdbc-3.51.0.0.jar`：`java -cp <该jar绝对路径> <SqliteJdbcCommitProbe.java绝对路径> <新建的ignored输出目录>`。Python脚本在其同目录写JSON，复现时先复制到新的ignored目录再执行；不要把两种驱动的绝对耗时当作同一实现的前后对照。

补丁及机器证据使归档超过400行，但均服务同一首次同步基准；没有增加UI、产品开关或新生产传输路径。两个原型即使同时应用，Runtime也不会自动调用分组方法；它们不是可直接发布的组合优化。没有追加全量Android/Desktop测试、正式构建或设备验收。

## 最终归档核验

组合研究源码已通过 `:data:spotlessCheck`（coordinator key=`git150-spotless-final5`，exit 0）。三个补丁均在基线临时 Git index 验证可应用、可逆；分组与本地进度补丁也验证可同时应用，但未声称完成组合运行测量。所有生产与测试源码实验修改已撤回，交付仅为研究资料。
