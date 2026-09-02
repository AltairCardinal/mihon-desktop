# Mihon Desktop 下载目录与 partial 阅读 CLOSE-01 性能报告

日期：2026-09-02

结论：`PASS`（两次原始运行按证据用途组合裁决；原始 JSON 的两个 `FAIL` 状态均原样保留，不改写、不删样）。

## 1. 裁决摘要

本报告同时关闭
[`2026-08-30-desktop-download-directory-and-partial-reading-plan.md`](./2026-08-30-desktop-download-directory-and-partial-reading-plan.md)
第 8.2 节相对性能门禁，以及被其接管的
[`2026-08-27-desktop-reader-upstream-semantics-adapter-refactor-roadmap.md`](./2026-08-27-desktop-reader-upstream-semantics-adapter-refactor-roadmap.md)
第 11.3 节绝对首帧预算。证据分工如下：

1. `close01-windows-performance-formal.json` 于 20:59:10～21:33:05 完成，早于当前 `LockApp.exe` 的
   21:58:24 启动时间。它使用默认 Direct3D、同一冻结产物、5 次预热和 30 组 AB/BA；其 downloaded
   directory/CBZ 绝对 P95 与 max 均通过 Reader 既定预算。该次下载服务器是旧 Python fixture，因此只采用其
   Reader 绝对时间和同机相对数据，不用它证明最终 MockWebServer 协议。
2. `close01-windows-performance-formal-corrected.json` 于 22:12:12～23:08:24 完成，补齐真实
   `mockwebserver3.MockWebServer 5.3.2`、1/180 页 Reader 矩阵、100 页正式下载、卷序列号和修正后的统计门禁。
   该次开始前 Windows 已进入登录锁屏，运行后仍有 `LogonUI`，`LockApp` 的 32 个线程均为 suspended；其
   Direct3D `decodeToPresented` 从前次约 39 ms 变为约 523 ms。因此该 raw 中 Reader 绝对墙钟列标记为
   `INVALID_FOR_ABSOLUTE_BUDGET`，但相同锁屏状态下交替执行的 baseline/candidate 配对差、确定性操作计数和
   非渲染下载吞吐仍保留为有效证据。
3. 修正后的稳定回归定义是“配对回归百分比的中位数 bootstrap 95% CI 下界 `> 5%`”。P95 继续报告为尾部
   诊断；不再把“每对百分比的 P95 bootstrap 下界”与中位数以 OR 连接。直接产物 P95 差异另行 bootstrap，
   不删除任何慢样本。

以上组合不是用锁屏样本放宽 1 秒门槛：Reader 绝对预算仍由锁屏前同一候选产物的有效样本满足；锁屏 raw 的
directory P95 1058.052 ms 与 baseline 1055.227 ms 只用于证明这次单项失败是共同图形会话条件，而不是候选新增工作。

## 2. 冻结产物与环境

| 项目 | 值 |
| --- | --- |
| Baseline EXE | `Mihon-Desktop-0.11.19.20.86ad546-unpacked/Mihon Desktop.exe` |
| Baseline EXE SHA-256 | `FD543D0F5A75B1D94D7827BBA026227332938DEA430332F343119D9DE84F095A` |
| Baseline ZIP SHA-256 | `2d7a4069c8166c9eb904b2638856f500f302c59e87a3a6ec17fa331cae6f3616` |
| Candidate EXE | `Mihon-Desktop-0.11.19.21.fbf75e1-unpacked/Mihon Desktop.exe` |
| Candidate EXE SHA-256 | `4E272A87708C68500CBDCE2FDA0A23FB432D1F49C3CC8BC177B0A6B681E150C2` |
| Candidate ZIP SHA-256 | `a68fc14f4c178b3b56d9ee2701557093eb63325f096ca5e96f9e1c45a668f6dc` |
| 固定 JPEG | 2400×3500，285,266 bytes，SHA-256 `04b96a41a279bdd7e57981fc5b9a65f068d1d64549cf6ce37318317f2382fb40` |
| 主机 | Windows 11 build 28000；Intel Core i9-13900F；32 logical CPU；31.8 GiB RAM |
| GPU | NVIDIA GeForce RTX 3070，driver `32.0.16.1047` |
| 文件系统 | baseline/candidate/work root 均为 `D:\`、NTFS、卷序列号 `00004823` |
| Renderer | 正式样本均为发布产物默认 Direct3D；software 只用于一次协议 smoke，不参与墙钟结论 |

修正版运行的 CPU 稳定门为 median 8.947%、P95 14.150%；运行期间 CPU median 13.077%、P95 20.376%。
锁屏前运行的对应值为 9.401%/15.46% 与 13.585%/30.11%。没有再次出现先前约 94% 的 Jellyfin `ffmpeg`
污染。

## 3. 协议与真实性边界

- 每个正式场景先对每个产物预热 5 次，再执行 30 组交替 AB/BA；bootstrap 10,000 次。
- Reader 覆盖 downloaded directory、downloaded CBZ、online 的 1 页和 180 页；候选另覆盖 partial 本地页与缺页联网。
- 冻结 EXE 的 Reader TTFF 使用其 production-mounted Test Mode HTTP fixture；100 页下载通过真实外部扩展、正式
  downloader/client 和 `mockwebserver3.MockWebServer 5.3.2`。这避免为冻结产物增加只为测量而存在的网络注入口。
- MockWebServer jar SHA-256 为
  `53a6e024ecc2b3f45d04d085161c6c10526a344b80bf3f0d4e6a13c33c183998`；server source SHA-256 为
  `fc83ac57b75040fec145e8892ac5c20dcb1259b5cc87f9d9637a59f3152fd59a`；主 class SHA-256 为
  `09bfbf1ff5b71117533583cdef0079b441f9cb6505e035754fa85c9d0585c293`。
- 计时从用户下载动作开始，到 100 个响应、100 个页文件提交且 production 下载队列完成；不在网络响应结束时提前截断。
- 所有 raw、预热和尾部样本保留；没有按结果重跑单场景、删除 outlier 或改写原始状态。

## 4. 锁屏前 Reader 绝对预算与相对数据

单位为 ms；回归为正表示候选更慢。

| 场景（180 页） | Baseline median / P95 / max | Candidate median / P95 / max | 配对回归 median；95% CI | 预算 |
| --- | ---: | ---: | ---: | --- |
| Downloaded directory | 285.950 / 441.414 / 532.594 | 280.798 / 561.440 / 683.507 | -6.534%；[-11.933%, 9.975%] | P95 ≤1000、max ≤2000：PASS |
| Downloaded CBZ | 162.212 / 257.363 / 453.804 | 145.421 / 256.968 / 317.164 | 10.252%；[-5.310%, 22.447%] | P95 ≤1500、max ≤3000：PASS |
| Online | 155.584 / 200.293 / 225.517 | 160.062 / 201.500 / 241.794 | 7.022%；[-0.291%, 15.107%] | 无 inherited 绝对门槛；稳定 >5%：否 |

旧 evaluator 把 directory/CBZ/online 和吞吐判为失败，只因它使用“配对百分比 P95 的 CI 下界 >5%”作为 OR
条件。按既定“稳定中位数回归”语义重新计算，上表三个中位数 CI 下界均不超过 5%，没有稳定回归。

## 5. 修正版 1/180 页配对结果

下表只用于相对回归和结构诊断；锁屏后的绝对 ms 不作为 Reader 预算。`P95 Δ` 是两个产物各自 P95 的直接差异。

| 场景 | 页数 | 配对回归 median；95% CI | 直接 P95 Δ；95% CI | 稳定 >5% |
| --- | ---: | ---: | ---: | --- |
| Downloaded directory | 1 | 7.943%；[-39.622%, 62.269%] | 0.007%；[-23.616%, 32.328%] | 否 |
| Downloaded directory | 180 | -3.717%；[-4.694%, 3.148%] | 0.268%；[-17.041%, 34.892%] | 否 |
| Downloaded CBZ | 1 | 22.736%；[-32.401%, 44.502%] | -9.733%；[-19.356%, 31.661%] | 否 |
| Downloaded CBZ | 180 | -11.874%；[-20.524%, 7.901%] | -10.651%；[-20.221%, 1.624%] | 否 |
| Online | 1 | 15.231%；[-28.317%, 38.735%] | -9.318%；[-32.951%, 10.652%] | 否 |
| Online | 180 | 10.142%；[-27.613%, 38.868%] | -0.018%；[-28.197%, 32.688%] | 否 |

每个 Reader 样本在首帧前都恰好一次 `PAGE_LIST_READY`、当前页 open 和当前页 decode；非当前页 open/decode、
`CACHE_RECONCILE` 与 `ADJACENT_IO` 均为 0。1 页与 180 页因此保持相同结构成本，没有页数相关的扫描或预取进入首帧。

## 6. 100 页下载与 partial 路由

| 指标 | Baseline median / P95 / max | Candidate median / P95 / max | 配对回归 median；95% CI | 直接 P95 Δ；95% CI |
| --- | ---: | ---: | ---: | ---: |
| 有效吞吐（MiB/s，越高越好） | 18.228 / 20.099 / 20.999 | 18.409 / 21.027 / 21.541 | -0.053%；[-7.824%, 5.636%] | -4.618%；[-8.476%, 0.274%] |
| 完成总时长（ms） | 1492.582 / 1782.282 / 1788.236 | 1477.799 / 1821.694 / 1827.100 | -0.053%；[-7.253%, 5.979%] | 2.211%；[-8.989%, 9.826%] |

60 个正式下载样本（30 baseline + 30 candidate）全部精确为：100 请求、28,526,600 response bytes、峰值并发 1、
100 个页提交、1 个章节提交。候选 runtime 的 `downloadIoLockViolations=0`。因此候选没有降低并发上限、增加请求或
commit，也没有把网络/页 I/O 放入协调锁。

候选 partial local/missing 各有 5 次预热和 30 个正式样本。锁屏墙钟仅作诊断：local median 1060.039 ms，missing
median 1059.850 ms。所有 missing 样本均为 `localHits=0`、`networkFallbacks=1`、`imageRequests=1`，且 immutable
snapshot 已知缺页后 `partialPageProbes/Opens/Copies=0`、锁违规 0；local 路由的 production contract 则固定为一次本地
命中、零图片网络。两条路径都没有在每页查询时扫描目录。

## 7. 最终门禁判定

- 当前计划第 8.1 节确定性操作门禁：PASS。
- 当前计划第 8.2 节完整下载、普通在线与 100 页吞吐“稳定回退 >5%”门禁：PASS；所有中位数 CI 下界 ≤5%。
- Reader 第 11.2 节 1/180 页首帧 I/O 门禁：PASS。
- Reader 第 11.3 节 unlocked/default-Direct3D 绝对预算：directory 与 CBZ 均 PASS。
- 修正版 raw 的唯一 `downloadedDirectory first-frame absolute budget failed` 发生在已确认锁屏的图形会话；baseline 与
  candidate 同时超过 1 秒，直接 P95 差仅 0.268% 且 CI 跨零。该单项按外部条件标为无效，不覆盖锁屏前通过证据。
- Android `assembleDebug` 在同一 product HEAD 上通过；`app-x86_64-debug.apk` 为 60,258,748 bytes，SHA-256
  `0E16924C1A0BAC7994FB8F249DF534583A06597FE1EB31C846042B551D55C77C`。

综上，`CLOSE-01` 可关闭。此结论不承诺锁屏时的 Direct3D 首帧速度；发布预算定义的是已登录、可呈现的正常图形会话。
若未来要把锁屏/远程断连作为支持场景，应单独制定 renderer/lifecycle 预算，不能反向改写本次发布门禁。

