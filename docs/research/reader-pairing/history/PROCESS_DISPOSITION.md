# H10 有界过程材料处置

本页只记录本轮检查的旧 CP1-S4 忽略目录、其明确关联的 176 页输入，以及已归档研究包中的重复核对。它是有限遗漏审查，不代表检查过整盘、全部 worktree 或整个 `.gradle` 树；不据此推断产品缺陷或性能结论。研究处置总表见[历史索引](../HISTORY_INDEX.md)，工程原件总索引见[来源索引](engineering/sources/SOURCE_INDEX.md)。

## 检查边界

- 对 `D:/Codex/worktrees/reader-pairing-research-next-20261014/.gradle-coordinator/research-evidence/cp1-s4-20261027/` 完成一次逐文件流式枚举：477 个文件、30 个直接子目录、32 个根目录文件；没有路径深度截断、枚举错误或 reparse point，也没有跳过 `build/.gradle/caches/node_modules` 目录。5000 文件和 50 MiB 非过程文本上限均未触发。扩展名计数为 CSV 1、`.git-index` 1、Gradle init 19、JSON 114、JSONL 5、Kotlin 70、日志 65、`.original` 2、补丁 3、SHA sidecar 59、TXT 64、XML 74；除日志、XML、SHA sidecar 和临时 Git index 外的可读/源材料共 4,204,324 B。
- 94 个源码、测试、init、patch 或源码备份文件中，26 个在既有归档内可按原字节恢复（9 个 ZIP 成员、10 个 legacy-code 原件、7 个 R6–R9 bundle Git blob）；其余 68 个原始路径快照、35 种不同内容、合计 2,685,903 B，没有精确归档副本。它们按原字节保存在[CP1-S4 唯一源快照包](engineering/sources/cp1-s4-unique-source-snapshots-20261027.zip)（542,189 B，SHA-256 `d1aca80f09c0e361b31691c68ab8416f2436ae62af19e4975e898a30825b2a48`）；包内 `SOURCE-RESTORE-MANIFEST.json` 逐项映射原相对路径、字节数、SHA-256 与 Git blob SHA-1。ZIP 成员名加 `.txt` 只用于标记惰性归档，载荷字节没有改写；不得当作运行输入。
- 精确关联输入 `D:/Codex/worktrees/reader-pairing-research-next-20261014/.gradle-coordinator/reader-real-inputs/vol08-tablet-pages/`：仅核文件名、大小和哈希，未查看图像。目录含 176 个 `.webp`，共 32,691,624 B；按文件名排序的“相对文件名|字节数|SHA-256”清单摘要为 `714261da805e17752c1964e1a0d2f53ccb69ed71061c1a1518785a7eff1a057e`。
- 其直接来源 `D:/Codex/worktrees/reader-pairing-research-next-20261014/.gradle-coordinator/reader-real-inputs/vol08-tablet.cbz` 为 32,721,661 B，SHA-256 `4b66d7c71dd3b8979d95d1bb7403804f5207694782cb1850a66e6b88e0bc7ceb`。该 SHA 与多个实验输入清单中的 `archiveSha256` 相同；CBZ 和解码页均是用户阅读输入，不复制进 Git，仅保留身份哈希和排除理由。

## 唯一原始计量材料

已补档的 11 个 JSON/JSONL 文本原件已检查常见私钥、已知 API key 和 bearer 凭据形态，命中 0 项；检查只覆盖这 11 项，不代表扫描了整个 ignored 目录。

以下 11 个文本文件没有与本次限定的既有 R10 过滤包（含其嵌套 ZIP 成员）、R6–R9 工程材料 ZIP、九个原始研究 ZIP 的成员、legacy-code 哈希清单或 11/01 reevaluation 归档文件精确同哈希。本次只归档这些计量数据和最小运行判定，不复制测试源码、init、Gradle 日志、XML、图像或缓存。原始路径、文件名、SHA 与大小如下：

| ZIP 内路径（均带共同前缀 `cp1-s4/`） | 字节数 | SHA-256 |
|---|---:|---|
| `formal-cp1-s4-l1-formal50-20261027-01/baseline-samples.jsonl` | 70,970 | `930324a4aa2635e1b41b4fecbfdd9759a7e51efbc2ba095c6ff5f02d2f3f3840` |
| `formal-cp1-s4-l1-formal50-20261027-01/baseline-summary.json` | 6,041 | `cef2c0198e0237fe6a979f4f025fc6e12c36d7275412c6cd884b409a57a6e93a` |
| `formal-cp1-s4-l1-formal50-20261027-01/input-manifest.json` | 7,445 | `c7502c8b5f511bcf85a7e7b821c6093b5fa456e687b5eea4808de34d174f6f10` |
| `formal-cp1-s4-l1-formal50-20261027-01/run-manifest.json` | 3,720 | `0f5dbf31c96f60ce986a44c575e14f00ed0770a083fb76652005c5df54d6b4a4` |
| `validated-pair-v2/baseline-run-status.json` | 473 | `42124e996af2c1ba2e74ec09dd92f095add9b1450df99759b5d49e0cde5861d8` |
| `validated-pair-v2/baseline-samples.jsonl` | 70,898 | `9e17ccc9bba5539063aba0ea30c2bdd6bc33f336294315f184750985ff0e4cba` |
| `validated-pair-v2/baseline-summary.json` | 6,033 | `c8b7cb2c32ca378c3a13cd293755084604195a9a230a132e59195ca02ded0ee9` |
| `validated-pair-v2/candidate-run-status.json` | 475 | `dbea05c6ed9eea4b029e20b3492411ddb8b525a8918076253fd8eda65e835fb7` |
| `validated-pair-v2/candidate-samples.jsonl` | 69,175 | `c85a35b3fe6f85651a2e6fbc45dbc14acf5ef3ea3bbfeecea156ff648978b520` |
| `validated-pair-v2/candidate-summary.json` | 8,501 | `5b549a61f162abe3ffc0c62e827bb5cc7b0936fc4b794b07812a5863bfb9b216` |
| `validated-pair-v2/comparison.json` | 52,896 | `9bf49f1af4d83a875f525acac0d14d9f8fcf704351a498d448176e03dcea2ffb` |

以上原件被收录于[CP1-S4 计量补充包](engineering/sources/cp1-s4-unique-native-text-measurements-20261027.zip)，27,623 B，SHA-256 `e90c872b16c5a77aefcf2806fba13a7c4e06a5237e8739ebad438db27fbb698c`。ZIP 内 11 项逐项解压后与原始文件字节数、SHA-256 核对一致。正式 50 行数据在该 ignored 目录根与 formal50 子目录各有一份，哈希完全相同，补充包只保存一次。

### 数据身份与无效判定

**formal50 原始侧**的 run key 是 `cp1-s4-l1-formal50-20261027-01`，source HEAD 为 `db7b0e24bc9e166576f532921e63c6e1aa1c47ae`，side=`baseline`，expected test 为 `nativeWindowRecordsFiftyProductionReaderLatencySamples`。输入清单记录 CBZ SHA `4b66…`、source manifest SHA `d19f1744a4ac4f111f86765f2ee4fe999ef6b9da5541121e5e6d4e9430806122`、oracle SHA `0f0702257e24625be8a8413bcb85acf442b94ebdb42a1ccb2d511b96dc877b38`、model-candidate SHA `d9ec080ada70446d892aaac7433b6cafb11807d8c0e389819b40303df5748aa0`；数据声明 50 样本、176 页、93 个 oracle units、200 个预期结果行。

虽然 run 状态与 XML 显示测试任务通过且 JSONL 有 50 行，`run-manifest.json` 明确裁定 `INVALID_INSTRUMENTATION / NOT_COMPARABLE`：真实 draw coroutine 中出现 50 个未处理的 AWT Compose MockK no-stub 异常，时间戳与样本 ID 不足以建立可信计时；正式候选侧按裁定 `NOT_STARTED`。因此这些行仅作为失败仪器留下的原始材料保存，不支持性能结论或候选通过。

**validated-pair-v2** 是另一次独立配对尝试，基线和候选各计划／记录 50 行。baseline-run-status 为 `valid=true`；candidate-run-status 为 `valid=false`、`bodyCompleted=false`、`cleanupVerified=true`，虽有 50 行但包含 10 个候选失败。`comparison.json` 记录 sample IDs 相同、oracle SHA 相同，但 `pairedActionInputsMatch=false`、`runLevelValid=false`、`samplesValid=false`、`r3Passed=false`。所以该配对同样不可比较；保留全部 100 行、两个 summary/status 和 comparison，以免只保留有利一侧。

R10 材料也引用了相同的输入 CBZ SHA 与 source-manifest SHA，说明相关研究使用过同一输入集。但本次没有找到与 R10 结果完全相同的原始样本文件哈希；R10 归档中未找到上述 `d9ec…` candidate 指纹。本组 `db7b…` run 的无效判定与 R10 final 的 F0／130.19685ms 报告数据保持分开，不因样本数同为 50 就合并或互相替代。

### 逐项枚举补出的后续证据

以下原件此前未包含在 11 项计量补充包内，已另存于[CP1-S4 后续原始证据包](engineering/sources/cp1-s4-unique-followup-native-measurements-20261027.zip)（24 项、原始载荷 207,719 B，ZIP 35,406 B，SHA-256 `2d6c4469773f372da62fd654d014d4a6e0b48f2f41aea4eddc546b418cebc869`）。包内 `FOLLOWUP-EVIDENCE-MANIFEST.json` 记录每项来源相对路径、原字节数、SHA-256 和用途；载荷逐项按原字节保存。

- `post-t170-candidate-v3`：候选 50/50 条均完成，失败和超时均为 0。comparison 与已归档 `validated-pair-v2/baseline-samples.jsonl` 使用相同 50 个 sample ID、输入和 oracle，`runLevelValid=true`、`samplesValid=true`；但 `improvementGatePassed=false`、`r3Passed=false`。配对文件记录 complete-unit 中位改善 12.5968495 ms、target 中位改善 12.1673995 ms及 20,000 轮 bootstrap 区间；不满足既定采用门槛。此组是有效运行但未通过保留裁定的数据，不与 R10 final F0 或 `130.19685 ms` 结果合并。
- `task36` A-only 对照：单次 A-only 与单次 AB 运行按相同 13 个 sample ID、输入 manifest 和 oracle 对照；原协议明确两次运行非随机、非同步，因此不能据此外推一般性能效果。全部行正确，但保留门槛未通过：AB 相对 A-only 整体中位仅快 7.2849%（低于要求的 15%）、下一页中位仅快 3.6473%、最大单行回退 50.9813 ms（超过 16 ms 限额）。包内保留逐样本 CSV、comparison、source manifest、两侧 XML 和变更/恢复记录；对应原始 baseline 源文件快照在唯一源快照包中。
- 额外保留 task35 冻结的 focused whitelist、task36 预先冻结的比较门槛、B negative-control 协议与实际预期语义失败/XML/恢复记录。该 negative-control 是用于证明测试能发现语义偏差的刻意单文件变异，不是产品失败；task36 的失败门槛是采用判定，不是测试失败。

## 重复与排除

- `canonical-oracle-v2.json` 的 SHA-256 `0f0702257e24625be8a8413bcb85acf442b94ebdb42a1ccb2d511b96dc877b38` 已在 R10 归档 `docs/evidence/reader-pairing-r10-20261028/cp1-phase45-ingest-20261029/actual176-size/raw/canonical-oracle-v2.json` 原样保存；此处不重复。
- `formal50` 大型 Gradle log（642,456 B，SHA-256 `c42dfc17cf51605caf8efa590d8b222a3697f5bbdf58fc235e465bb3c495bb85`）及 XML（573,507 B，SHA-256 `3e581654e861ac2f9e52fe96ddacfb4ac75740bd8777e4f1dd6a0d86232b2434`）不另存：raw 50 行、summary、输入与 run adjudication 已保留；XML 的 PASS 不会覆盖日志及 run-manifest 中的无效仪器判定。
- `native-cp1-s4-candidate-native-w1` 的 48,230 B log（SHA-256 `3139b77208796fa9c69e7665208663ce47d2e1c7a25bec1ea326d713ccc1a41b`）和 14,725 B XML（SHA-256 `6af0d898f0895613defde10340ee15ebf75278080d76fd0685acd168a9b2ab85`）显示同类 MockK no-stub 异常；此单次 smoke/process transcript 不作为有效计量并未复制。
- 65 个普通 Gradle/协调日志、74 份 XML 中未入后续证据包的 70 份、64 个 TXT 中未收录的 56 个退出码/过程状态小文件、59 个 SHA sidecar，以及 612,751 B 的临时 `adoption/b-research.git-index`（SHA-256 `d8a34d33907102f810a0b3cb9123a6e685d471d439d0877fad781787fc871769`）按过程材料分组处置，不逐份复制。必要的有效候选/对照 XML、协议和变异恢复材料已随后续证据包保留；唯一的源码/测试/init/patch 快照按前节逐项归档。176 张 WEBP 和原 CBZ 只记录来源哈希，不纳入 Git。
- 176 张 WEBP 和原 CBZ 只记录来源哈希，不纳入 Git。深度 3 检查未发现独立图片、CBZ 或构建缓存；本结论仅限所述路径和深度。

## 缺失协调副本

`reader-cp1-scope.md` 是历史协调副本，在已核位置未找到。现存报告和研究范围记录承接其职责；这不证明具体文件与现有报告等价，也不证明其他路径上不存在副本。原件未找到的限制保留；若以后发现原件，应作为独立材料补记来源与 SHA，不覆盖本页已有计量数据。

本次没有重新运行任何旧测试、脚本、基准或生产代码，也没有删除旧工作树、ignored 原件或缓存；无新增产品缺陷结论。