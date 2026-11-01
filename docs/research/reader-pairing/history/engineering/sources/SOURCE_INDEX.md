# 工程来源原件索引

本目录保存 R6–R10 工程报告、实验原件和可恢复来源。这里是历史材料归档，不是当前产品行为结论，也不表示其中的候选已被采用。当前评估结论与限制见[历史处置](../R10.md)、[工程决策索引](../engineering-decisions.json)和[研究总索引](../../../HISTORY_INDEX.md)。

## R6–R9：报告、证据与 Git 历史

| 文件 | 内容 | 校验 |
|---|---|---|
| [reader-r6-r9-history-e323-to-bb78.bundle](reader-r6-r9-history-e323-to-bb78.bundle) | 从公开历史边界补齐到工程提交 `bb78b48237cc0954495b035d7f2c758c3aaaf1b0` 的增量 Git bundle；含 96 个提交、1,901 个增量对象。 | 2,724,196 B；SHA-256 `687b16182f838faa71c0710fd9da2ceec60a28efc093d42983d8009178576d9f`。在含公开边界完整历史的独立 bare 仓库中 `git bundle verify` 成功并解析到精确 bb78。bundle 要求 `3367aed7a88626aa68c8beb40c6839ded35e394d` 与 `24a2e6bc665cae8604c7cadd2814fb4fe5a70561` 两个前置对象；浅取 e323 的仓库不够，需完整公开祖先对象。e323 不是 bb78 的直接祖先。 |
| [reader-r6-r9-engineering-materials.zip](reader-r6-r9-engineering-materials.zip) | 报告、路线图及方向级证据原件。Markdown 作为 `.md.txt` 保存，避免被误当可执行输入；其余 JSON/JSONL/CSV/XML/log 等按原字节保存。 | 656,622 B；SHA-256 `52d089216d30b246618466414fb3e07ef67978acf94b812afe2483a3b7a5eb7f`；163/163 条目解压并逐项字节数与 SHA-256 核对通过。 |
| [reader-r6-r9-engineering-materials.json](reader-r6-r9-engineering-materials.json) | 上述 ZIP 的逐项来源 commit、Git blob、角色、原路径、字节数、SHA-256 与归档路径。 | 73,395 B；SHA-256 `27ef085a73701638a679828852a1c2cb77d1ed06a2d726b3df2e718b915c00c9`。 |

报告版本关系：R6 候选报告及路线图取自 `e4ba69f3`，优化报告取自 `2dae092b`；R7 初版 `e38315c5` 与最终版 `31d6b59f` 均保留，路线图和 13 项最终证据来自最终版；R8 研究包报告取自 `2ae30f16`，工程报告、路线图及 102 项证据来自 `375b567c`；R9 认证报告取自 `9b9ada3c`，工程报告、路线图及 36 项证据来自 `bb78b482`。逐条文件清单和哈希以 JSON 为准，不以较早草稿覆盖后续版本。

## R10：筛选来源与研究原件

[reader-r10-filtered-engineering-source-24cd1d11.zip](reader-r10-filtered-engineering-source-24cd1d11.zip)来自工程提交 `24cd1d11a6bcdb839075f51dc99816a76bbf651f`。最终 ZIP 为 24,279,414 B，SHA-256 `f541473dd006b4d6a2fdeaab2285aa876d5ee4fb1f7d306f0d221ed5596f69e2`；共 3,130 个 ZIP 条目，包含 3,126 个清单登记的载荷及归档清单文件。ZIP 解压后与生成候选逐项核对通过；生成的 `SOURCE-MANIFEST.json` 已去掉无必要的临时归档绝对路径，原始报告/日志/argv 没有被改写。

归档范围包括 R10 根目录中筛选出的 29 份 reader/CP1 报告、整个 `docs/evidence/reader-pairing-r10-20261028/` 的可保留内容、来源清单、排除清单和 WIP 草稿。64 个嵌套 ZIP 均检查过：60 个安全 source/data 包原样保留；4 个混合包不作为整体收录，其中唯一可保留的文本/source/data 成员单独提取并记录原 ZIP 与成员路径、哈希；6 个编译或媒体成员仅记排除信息。22 张原始 PNG 未进入归档。筛选清单记录源路径、Git blob、字节数和哈希；不含 PNG/JPG/WEBP/CBZ/DB/JAR/CLASS/APK/EXE 等直接成员。原始图像和漫画内容不进入 Git。

归档中的 8 个 WIP 文件均作为 `.txt` 惰性材料保存，并标明未编译、未测试、未验证：`first-stage-handoff.json`、`r9-auto-scene.init.gradle`、`R9NaturalAutoSceneTest.kt`、`R9WitnessPlan.kt`、`FakeSourceTestUtils.kt`、`reader-r10-03-original64-scene-data-20261030.json`、`R10-03-08b-source-audit.md`、`phase44-green-v1-ingest.md`。这些草稿不是已测候选或当前产品实现。

### R10 来源差异恢复边界

归档含 `source-diff/non-docs-text-e8beb-to-r10.patch` 及对应的 R10 变更文本快照。它只覆盖 e8 基线 `e8beb212a0ead306f6441b130c40d4b0a6c660f6` 到 R10 提交之间 483 个非 docs 纯文本变更路径；patch 为 4,075,086 B，SHA-256 `b0983342d15caaad4f165c28333a58e9bb79fea5fa5a4cfc0f0e332736d8fcee`。两端不是祖先关系，共同祖先为 `24a2e6bc665cae8604c7cadd2814fb4fe5a70561`。在隔离恢复目录中应用该筛选 patch 后，483 条目标路径的字节数和 Git blob SHA 均与 R10 清单一致；删除路径亦核对缺席。

这不是完整 R10 Git 树、完整历史或可直接应用到当前主干的生产 patch：它有意排除了 10 个 Markdown 路径、8 张变更 PNG 和 1 个备份文件，以及 Git 元数据、二进制对象和其他未选 docs。排除路径和源/目标哈希见 ZIP 内 `SOURCE-DIFF-MANIFEST.json` 与 `EXCLUSIONS.json`。恢复核验仅证明被选中的文本差异和快照可核对，不证明旧环境可直接构建或运行。

## 可复用与安全边界

- 所有归档包都作为数据读取；其中的 Gradle init、脚本、测试源码和命令行记录没有在本归档任务中执行。
- 原始日志、XML 与 argv 为保持证据身份而保留其历史本机/CI路径文本；这些文件不是当前可执行命令，重新运行前须按当前环境显式适配。
- 原始图像、CBZ 与构建产物被排除；其身份、来源及排除理由以归档内清单为准。未对被排除文件做运行时复现。
- 本页只说明原件的可追溯性和恢复限制，不替代报告中的性能结论、采用/不采用裁定或当前实现映射。
## H10：CP1-S4 唯一原始计量与快照

[CP1-S4 有界处置记录](../../PROCESS_DISPOSITION.md)收录完整 477 文件边界核对、重复判断、数据无效判定和未归档原件索引。原有 11 项正式/validated-pair-v2 无效计量包仍为 [CP1-S4 文本计量补充包](cp1-s4-unique-native-text-measurements-20261027.zip)：27,623 B，SHA-256 `e90c872b16c5a77aefcf2806fba13a7c4e06a5237e8739ebad438db27fbb698c`。另新增两份从旧 ignored 目录恢复的归档：

- [CP1-S4 唯一源快照包](cp1-s4-unique-source-snapshots-20261027.zip)：68 个未能从既有 archive 恢复的原始相对路径快照、35 种不同内容，ZIP 542,189 B，SHA-256 `d1aca80f09c0e361b31691c68ab8416f2436ae62af19e4975e898a30825b2a48`。包内清单将惰性 `.txt` 成员映射回原路径，并提供字节数、SHA-256 与 Git blob SHA-1；不执行其中的源码、init 或 patch。
- [CP1-S4 后续原始证据包](cp1-s4-unique-followup-native-measurements-20261027.zip)：24 项原始计量、比较、协议、测试范围和变异恢复证据，ZIP 35,406 B，SHA-256 `2d6c4469773f372da62fd654d014d4a6e0b48f2f41aea4eddc546b418cebc869`。包含 post-t170 v3 的有效 50 行候选运行（但未通过性能改进门槛）、task36 A-only 单次对照（未通过保留门槛）以及 task35/B 的协议与负向控制证据；每项原始路径、大小和 SHA-256 见包内 `FOLLOWUP-EVIDENCE-MANIFEST.json`。

这两组新材料保持与先前 formal50 无效仪器结果、validated-pair-v2 无效候选运行及 R10 F0 分开。原 176 页用户输入只保留身份 hash，不进入 Git。该处置不构成新的性能结论或当前 main 实现声明。
