# 1,600 个真实形状书架条目的首次上传与原生 Git 对照

日期：2026-09-25。实验基线：`0c153cb233`（生产优化仍为 `a0142effab`）；本轮只增加测量夹具、脚本和证据，不改变产品行为。此前 direct-tree 复用预研未计入本轮生产基线。

## 结果

**同一份已冻结加密制品，在本地受控网络模型下，当前 REST 发布速度为原生 Git 的 16.5%，Git 约快 6.1 倍。** 这是一次当前实现对照，不是最优速度预测，也不是实网 GitHub 成绩。

| 路线 | 总耗时 | HTTP 次数 | 上传正文 B | 下载正文 B | 发布次数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 完整 Runtime 首次导入与上传 | 153.592 s | 794 | 19,338,640 | 19,682,529 | 41 |
| 同制品 REST 发布重播 | **88.516 s** | **788** | **19,338,640** | **19,680,533** | **41** |
| 原生 Git add / commit / push / 确认 | **14.592 s** | **4** | **13,845,216** | **573** | **1** |

比例为 `14.591507 / 88.516 = 16.4846%`；同有效数据量下，Git 速率为 REST 的 `6.0663` 倍。不能用 `14.592 / 153.592` 作为整条同步路线的公平速率，因为 Git 一侧没有从书架开始完成业务事件构造、加密与持久化。

Runtime 从接受初导起的时间点：冻结结束 **0.603 s**，导入任务全部生成完成 **34.829 s**，首批确认 **53.176 s**，全部确认 **153.592 s**。实验书架预填充 1.493 s 计时外。共 **10,300 事件、41 批**（40 × 256 + 60）。这些是同起点时间点，不应相加。Runtime 与重播相差 65.076 s，涉及数据库确认、制品准备、transport 生命周期/缓存及运行时状态等差别，尚不能全部归因为某一个 CPU 或 SQL 热点。

Git 分段：add 0.599 s、commit 0.181 s、push 13.514 s、远端 ref 确认 0.297 s。使用 `git version 2.54.0.windows.1`。最终 **86 文件、19,285,180 字节**；实际发送 pack 所在请求正文 13,845,096 字节，另有确认请求 120 字节。压缩效果来自真实同一加密文件的 Git 对象传输，未拿普通明文文件替代。

两路线初始 tree OID：`aacceda21fa52a5f052574fca8351468ce0e9b1a`；最终 tree OID：`134c7517f70e6e8b12963ae9e0ec667fe11e107f`。REST 重播 SHA-256 manifest、Git 远端逐文件字节和两边 tree OID 均通过独立复核。

完整聚合数值及每批制品大小/重播耗时见 [JSON 证据](git-comparison-1600-2026-09-25.json)。原始 Runtime summary SHA-256：`1282301740dfc2d13e8d3f98ed5ab399ffd29e607e6a73fb9aa4a7b38848d036`。

## 这次对照支持的后续方向

1. **优先比较有界多批次发布**：本次 41 次提交/确认、788 个 HTTP 请求，对比 Git 单次发布/4 请求，证明当前形状存在很大的请求与发布次数差距。不能把 6.1 倍直接当作某个 REST 改造的承诺；要保留首批及时确认、上传前持久制品、未知结果对账和冲突恢复。
2. **核查确认中的大正文回读能否安全复用**：REST 下载正文达 19.68MB，Git 仅 573B；源码 `GitHubGitDatabaseClient.matches` 会读取已发布 batch 与冻结制品比较。下一轮值得按端点计量，再验证以已验证对象 OID 和原制品复用减少回读的安全边界。本轮没有逐端点字节归因，不能称全部响应都是冗余。
3. **首次确认仍需要本地分段诊断**：本次首批确认 53.176s，导入生成 34.829s，仅更换远端传输不能保证消除该等待。继续区分 SQL、事件构造、序列化、加密及安全校验后再选择实现，不能把 Runtime 与重播的时间差全部算作“准备时间”。

原生 Git 是本实验中更快的发布基线，但尚未接入 Android/Desktop 生产链路；依赖体积、磁盘、凭据、后台取消和未知结果恢复均未验收。不因为此次对照直接替换传输实现，也不认为 16.5% 是当前架构的速度上限。

## 样本与隐私边界

只读本机 Mihon 默认数据库的一致快照，取得 424 个收藏条目、来自 7 个源的实际同步字段，以及它们的 2,286 条公开阅读状态。没有改动真实书架；私密阅读使用 `sync_private_reading` 的公开投影，不提取私密进度。书架外阅读历史、独立作者关注、封面图片二进制和不同步的描述/分类字段不纳入这次样本。

保留原 424 条，固定随机种子 `202609251600`，以打乱顺序的均衡重采样补充 1,176 条；随机更改漫画 URL 路径和标题，保证 `(source,url)` 唯一，尽量保持 UTF-8 长度，保留空值和每本书的章节/阅读复杂度。结果是 **1,600 本书、8,700 条公开阅读状态**。这不是 1,600 个小型收藏事件的简化样本。

| 形状指标 | 原书架 | 1,600 条样本 |
| --- | ---: | ---: |
| 每本公开阅读条数：中位 / p95 / 最大 | 0 / 16 / 293 | 0 / 16 / 293 |
| 单本输入 JSON 字节：中位 / p95 / 最大 | 309 / 3,253 / 54,419 | 310 / 3,253 / 54,419 |
| 仅漫画字段字节：中位 / p95 / 最大 | 221 / 391 / 650 | 221 / 401 / 650 |
| author 为空 | 24 / 424 | 92 / 1,600 |
| artist 为空 | 290 / 424 | 1,100 / 1,600 |

表中 JSON 字节是规范化输入记录大小，不是协议事件或网络字节。真实 production 实现负责构造协议事件、分批、加密、冻结上传制品。输入文件共 3,321,368 字节，SHA-256：`e2cbcb9dad186f22a15615b374588f8b694e37b2f660e515c160b10ceea17458`。

原始输入、缓存、加密文件及本地 Git 仓库均留在被忽略的 `.gradle-coordinator/` 下；实验数据库是独立临时文件，关闭后删除。因为实验密码是合成已知值，加密文件仍视为私人样本，不提交。仓库仅保存聚合证据和可复现的测量工具。

## 对照口径

两端使用相同的初始 `.mihon-sync` 文件树和完全相同的最终加密文件。REST 通过实际 `SyncRuntime` / 文件 SQLite / `GitHubSyncTransport`，服务器为项目 MockWebServer Git 数据库夹具；Git 使用真实 `git add`、`commit`、smart HTTP `push`、`ls-remote` 和本机 `git-http-backend`，远端是新建隔离 bare 仓库。

共同网络模型为每个 HTTP 响应首部延迟 50ms、上传及下载各 10Mbit/s。上传先完整读取请求体，再注入字节耗时；下载分块限速。该模型没有真实互联网丢包、TLS、GitHub 限流或服务端负载；字节统计为 HTTP 正文，不包含 headers/TLS/TCP。Git CGI 实际进程启动和磁盘成本计入，REST 服务端是内存夹具；不能把这个不对称模型当作真实 GitHub 的百分比。

分开记录三种时间，避免把业务准备成本混成传输差距：

1. **完整 Runtime 初导**：从接受初导、冻结书架开始，到全部事件发布确认；真实本地导入、SQLite、制品生成、加密、恢复记录和确认均计入。授权、空间发现/解锁，以及预先填充实验书架不计入。独立冷 L2 缓存使用 production 配置入口。
2. **冻结制品 REST 重播**：恢复初始 ref，从读取初态开始，按原顺序以 production transport 发布所有同一批次制品并确认；新 transport 无预热对象缓存，保留单次 transport 的正常缓存。不包含业务导入和 outbox 数据库确认，且不等价于 Runtime 每轮重新创建 transport 的完整调用成本。
3. **原生 Git**：相同最终文件已落在新工作树后，计时 `add + commit + push + ls-remote`。初始化仓库、安装冻结文件及事后逐文件验真不计入；加密制品生成同样不计入。

速度比例只使用同制品的第 2、3 项：`Git耗时 / REST重播耗时 × 100%`。这是当前发布路线在此模型下的比例，不是“优化极限”或“未来最快速度”。Git 一次提交最终树；REST 每批独立提交并确认，因此**最终数据一致，中间提交历史不同**。若要求 Git 保留全部中间提交及确认边界，需要另一次实验，本轮没有偷换为已测。

## 验证与局限

小样本先验证真实加密空间与 Runtime 入口；夹具开发时修正了提前触发导入、未接冷 L2、未配置环境变量会破坏普通测试等问题。未满足正式条件的试运行不作为结果样本。正式报告只采用完整通过正确性断言的一组配对实验，不挑选最快耗时。

正式正确性检查包括：实际上传事件数符合样本、各制品事件数之和等于上传事件数、持久冻结制品数等于 ref 更新次数；REST 重播远端每个文件的 SHA-256 与原 Runtime 最终树一致；Git 远端 ref、文件集合及逐文件原始字节一致；两路线初始/最终 Git tree OID 相等。

只测一组样本，不报告耗时 p95、置信区间或统计显著性。机器为 Windows 11 / i9-13900F（24 核、32 线程）/ 约 32GiB RAM，未独占机器。这里的 SQLite 是桌面 JVM 实现；未测 Android 设备、电量、后台限制和真实 GitHub。没有 APK/EXE 发布，也不代替用户此前保留的最终设备验收。

## 工具与复现

- 采样和原生 Git 通道：[`scripts/sync-git-comparison.py`](../../../scripts/sync-git-comparison.py)。采样以 SQLite `mode=ro` / `query_only` 打开数据库，拒绝覆盖现有输入。
- Runtime / REST 冻结制品重播：[`SyncGitCompareAcceptanceTest.kt`](../../../data/src/jvmTest/kotlin/mihon/data/sync/SyncGitCompareAcceptanceTest.kt)。实验依赖本机私有输入，不随常规单元测试自动运行。
- Git 后端为 Windows Git 安装附带的 `git-http-backend.exe`，只监听随机 loopback 端口，无需 Docker或真实 GitHub 写入。

实际正式命令如下（再次运行必须换新的 output、work 和 coordinator key；脚本会拒绝覆盖既有样本）。所有命令从仓库根目录执行：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'

# 首次生成；本轮 input.json 已存在，不要覆盖。
python -X utf8 scripts/sync-git-comparison.py sample `
  --database "$env:APPDATA/Mihon/mihon.db" `
  --output .gradle-coordinator/git-compare-1600/input.json --count 1600

$env:SYNC_COMPARE_INPUT = (Resolve-Path .gradle-coordinator/git-compare-1600/input.json).Path
$env:SYNC_COMPARE_OUTPUT = "$PWD/.gradle-coordinator/git-compare-1600/formal-output-v2"
$env:SYNC_COMPARE_COUNT = '1600'
python scripts/gradle-coordinator.py run --key git-compare-1600-valid -- `
  .\gradlew.bat --offline :data:jvmTest --rerun `
  --tests mihon.data.sync.SyncGitCompareAcceptanceTest

# 等 REST 完成后再运行，避免两条计时链路争用资源。
python -X utf8 scripts/sync-git-comparison.py git `
  --initial .gradle-coordinator/git-compare-1600/formal-output-v2/initial `
  --final .gradle-coordinator/git-compare-1600/formal-output-v2/final `
  --work .gradle-coordinator/git-compare-1600/native-run1 `
  --output .gradle-coordinator/git-compare-1600/native-result.json

Remove-Item Env:SYNC_COMPARE_INPUT,Env:SYNC_COMPARE_OUTPUT,Env:SYNC_COMPARE_COUNT
python scripts/gradle-coordinator.py run --key git-compare-format-skip -- `
  .\gradlew.bat --offline :data:spotlessCheck :data:jvmTest --rerun `
  --tests mihon.data.sync.SyncGitCompareAcceptanceTest
git diff --check
```

验证实绩：加密 2 本/7 阅读小样本通过；正式 1,600 条 focused 测试 **1 通过、0 失败**（测试体 248.111s，Gradle 总计约 4m11s）；原生 Git 正式一次成功；`:data:spotlessCheck` 通过；移除实验环境变量后 focused **1 跳过、0 失败**，不会让常规模块测试依赖私人数据库。Python AST 与 `git diff --check` 通过。主代理另独立核对输入指纹、初末 manifest 与两路线 tree OID，并审查只读采样、映射顺序、加密入口、计时边界和远端验真。没有追加全量测试或正式构建。

私人原始正式日志/XML 在 `.gradle-coordinator/git-compare-1600/evidence/runtime-1600.{log,xml}`，格式/skip 日志在 `.gradle-coordinator/git-compare-format-skip.log`。私人输入不上传，复现需复用同一私人样本，或重新采样并记录新的输入指纹。环境变量只用于显式启动实验，fixture 导出方法只在测试源码中；本批改动虽超过 400 行，仍是一套可独立复现的配对测量及聚合证据，未扩展生产功能。
