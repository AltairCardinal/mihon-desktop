# 2026-11-02 旧研究工作树退役与必要数据保全

本批执行用户要求：只保留有意义的研究数据归档，清理其余。范围为下列5个旧工作树；正式产品源码、用户主树版本文件、附件和其他任务工作区不在清理范围。

## 实际结果

- 5个工作树目录及Git登记均已通过正常 `git worktree remove` 撤销，未使用force；5个分支HEAD保留，Git研究历史未删除。
- 唯一旧tracked版本差异已先保存working/base/binary diff并逐字节核验，再将旧树补回原HEAD，以正常方式退役；主工作树版本改动未动。
- 现有主干研究档案继续保留，暂缓方案仍按原报告裁定。原始输入、绘制证据及未公开数据按内容去重后存于Git外，不上传原图或签名资料。

| 已撤销工作树 | 保留分支 | 固定HEAD |
|---|---|---|
| `reader-pairing-main-20261013` | `codex/reader-pairing-main-20261013` | `f38e0f9f9854daa8f09f6b52c22a2cb6476d45f6` |
| `reader-pairing-r10-engineering-20261028` | `codex/reader-pairing-r10-engineering-20261028` | `24cd1d11a6bcdb839075f51dc99816a76bbf651f` |
| `reader-pairing-research-next-20261014` | `codex/reader-pairing-research-next-20261014` | `68a5575932d9cf9579e21ceebd7c0a5ed71cb5fc` |
| `reader-r1-r10-reevaluation-20261101` | `codex/reader-r1-r10-reevaluation-20261101` | `79663cd4dc849264c3dd1bdf51a50046ed07106f` |
| `reader-stamp-reevaluation-20261101` | `codex/reader-stamp-reevaluation-20261101` | `e8beb212a0ead306f6441b130c40d4b0a6c660f6` |

## 有意义数据及恢复

本机私有保全目录：`D:/Codex/research-archives/reader-pairing/retirement-20261102/`。入口为 `INDEX.md`、`FORMAT.md`、`manifest.json` 与 `ARCHIVE_SHA256SUMS.json`。跨机器复用需携带该目录，并具备主干研究档案Git锚点 `b9bac4dcd601224355492e2e468fcc0af577ff29`。旧工作树路径仅作来源，不再是可运行目录。

- 流式盘点14,135条非普通缓存路径，最终9,210条研究资料、16条私有调试签名资料保留；4,909条过程资料排除。另从保留Git历史补存25条曾因媒体边界未公开的来源（22 PNG、3处同一64页CBZ引用）。
- 共9,251条保留来源精确可恢复；既有Git档案中的742条记录只记恢复位置，352份展开页从CBZ原字节恢复，不重复保存。两个不同容器布局的大CBZ绑定不同历史输入身份，因此各保留原容器；同字节页仅在归档层去重，原路径/顺序不合并。
- `research-data.zip`：134,127,143 B，SHA-256 `1d87c318ba9ef76616d8b75936d7a03593c7e6943e65f4d065e57ad43fc0cf92`。另有43,245 B私有绘制/64页输入补包，以及44,658 B私有调试签名包；旧版本文件差异单独保存。
- 私有manifest SHA-256：`c57f1df37f438f03f7ae071e66ff240583a37ba33ecf35a1da9a612513f2569e`；封存清单SHA-256：`ffd9c7672206a77ad9e3235f2749193ff473640ca15fe7ce0cb289a901f65341`。Git只记录这些校验与来源，不保存私有载荷。

## 清理内容与核验

- 排除可重建build/Gradle/Kotlin缓存、已编译分发包、验证用bare Git副本、lock/PID/临时index、生成HTML报告和安装签名副产物；撤销工作树时一并移除。
- 进一步剔除两个约44MB的中间归档包、生成报告、18项编译/签名副产物及151份无测量或失败证据的纯过程日志。保留必要原始计量、失败健康结论、输入、源码/报告与来源清单。
- 三份约30MB R10绘制/消费账本经双层ZIP内容SHA验证，均已在既有Git档案中，未再复制；曾排除的私有PNG和64页输入已单独本地补档。
- 两个主私有ZIP全部7,804个对象及补包13个对象经独立CRC/SHA/字节验证；原路径集合与冻结盘点完全一致，最终未判定项为0。Git深层ZIP、gzip与CBZ定位进行了实际恢复；25条新增Git来源逐blob/成员复核通过。
- 工作树删除后再次完成9,251条恢复定位校验，并检查5个目录/登记不存在、5个branch HEAD仍在、主树用户版本文件哈希未变。没有执行产品构建、测试或性能采样，不宣称新增算法收益或100ms问题已解。

## 尚未清掉的过程副本：工具阻塞

下面路径已确认是旧隔离备份、重建副本和本次盘点/索引临时文件；研究恢复不依赖它们。自动审批拒绝了精确路径删除，仅返回 `blocked by policy`，未给细分原因，命令未执行，也没有改用其他机制绕过。因此不能把本批描述成“所有临时文件都清空”。

- `.gradle-coordinator/reevaluation/cleanup-20261102-quarantine/`
- `.gradle-coordinator/reevaluation/cleanup-20261102-restore-check/`
- `.gradle-coordinator/reader-research-retirement-20261102/`
- `.gradle-coordinator/reevaluation/cleanup-20261102-plan.json`
- `.gradle-coordinator/reevaluation/cleanup-20261102-approved.json`
- `.gradle-coordinator/reevaluation/cleanup-20261102-result.json`

这些残余不属于五个已撤销的工作树，也不是新增未处理研究方案；它们因工具阻塞仍在主树忽略目录原处。主干版本文件与用户附件始终按用户改动保护。
