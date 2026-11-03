# Android 阅读器首开 P1 定位与结果

日期：2026-11-03。执行范围：[P1计划](../roadmap/2026-11-03-reader-startup-p1-plan.md)；P0产品基线 `64329568ace1b12b2b363cd39ef48890d477d462`，本轮起始主干 `8dfd2e2166fc1a013412759052c78a4239d2c9c1`。本轮不重复P0、B、Windows或全量验证，不推送或正式发布。

**本轮完成定位，没有采用新性能优化。** 同平板、同章节的两次控制采样中，本页header调用仅4.891 / 4.193ms，主要区间是它之前的准备与等待73.027 / 66.541ms。现有trace与代码尚不能证明其中某项等待可安全删除；按计划停止，不为凑结果合并IO或改动进度／身份保护。P0成果继续保留。

控制版首绘 **327.496 / 283.932ms**，较历史P0对应首次／再次 **263.953 / 272.514ms** 偏慢 **63.543 / 11.418ms**。本轮编译请求为Failure，P0最终请求为Success，实际ART filter均未读回；加上跨轮次、仅两样本等限制，不能把差值归因于两个新增事件，也不据此校正或宣称任何优化收益。近黑为 **103–128 / 55–98ms**，前者明确未达≤60ms，后者跨越60ms、未确认达标。

## 定位前已知事实

P0三份原件与时钟核对有效，`PAGE_LIST_READY→OPEN_PAGE` 分别为49.552 / 70.422 / 44.393ms。旧trace中有通用 traversal、Choreographer、Compose、RenderThread/GPU事件，没有绑定本次目标页／请求的 header、流读取或解析命名slice。它们不能把线程睡眠、Running或traversal统一归成header，现有证据不足以拆分P/H/Q。

因此本轮仅在原记录器数组末尾追加 `TARGET_HEADER_BEGIN/END`，单页holder原IO body内紧贴真实 `page.publishHeaderFact()` 调用。BEGIN成功首次认领的同一次正常返回才写END，取消／异常不在finally补END，重复调用不补写。保持固定预算、page0和捕获身份、区间外导出；不改变产品加载顺序，不给双页或条漫扩点。控制版因此是P0产品逻辑加两个定位点，后续如有候选则保持同一仪器。

扩展一个既有记录器方法先红后绿：旧名字集拒绝新BEGIN（期望true，实际false）；追加后验证新事件、首次写入不变、重复拒收及错误页／章节／请求拒收通过，同次格式检查通过。未重跑未变的P0测试或全记录器组。

本机原件根 `.test-tmp/reader-open-p1-20261103/`；复用P0适配的 `tablet_old_run.py` 和 `capture-old.py`，来源及哈希保存在 `script-provenance.json`。旧trace不足归属的离线依据保存在 `p1-0-existing-trace-scan.md`。Gradle单一协调者，设备单一操作者；P0 APK及原件保持不变。

## 三组结果分开记录

单位ms。历史P0顺序为首次①／同进程再次／新进程首次②；同仪器控制版只有首次／同进程再次。没有拼接不同APK凑三次基线，也没有候选APK或候选三次样本。

| 指标 | 历史P0三次 | P0+两定位点控制版两次 | P1候选 |
| --- | --- | --- | --- |
| 初始化→页表就绪 | 123.808 / 114.717 / 121.179 | 146.701 / 113.254 | 未构建、未采样 |
| 页表就绪→OPEN_PAGE | 49.552 / 70.422 / 44.393 | 85.707 / 83.055 | 不适用 |
| OPEN_PAGE→提交解码 | 2.681 / 6.509 / 8.567 | 9.340 / 3.860 | 不适用 |
| 提交解码→FIRST_DRAW | 87.911 / 80.865 / 91.894 | 85.747 / 83.763 | 不适用 |
| **合计** | **263.953 / 272.514 / 266.034** | **327.496 / 283.932** | **未采用候选** |
| 视频近黑范围 | 68–99 / 67–100 / 84–117 | 103–128 / 55–98 | 不适用 |

总计使用原始时间戳，显示舍入可有0.001ms尾差。控制版第二段均值84.381ms与历史P0三样本54.789ms的表面差值不作为因果开销或稳定性能结论；有效慢样本未剔除。本轮也不证明探针净开销、统计显著性或P95。

| 控制样本 | P：BEGIN−页表就绪 | H：END−BEGIN | Q：OPEN_PAGE−END | P+H+Q，即原第二段 |
| --- | ---: | ---: | ---: | ---: |
| 首次，PID17149/request1 | 73.027 | 4.891 | 7.790 | 85.707 |
| 同进程再次，PID17149/request2 | 66.541 | 4.193 | 12.321 | 83.055 |

两次均满足同章／页／请求且 `PAGE_LIST_READY≤BEGIN≤END≤OPEN_PAGE≤FIRST_DRAW`，正确目标图可见；未观察到需将首BEGIN与其他调用END拼接的异常。H是调用wall time，可能含读取、解析、锁或缓存命中，不是独占CPU，也不证明开流次数或读取字节。它本身不足以直接解释历史相对上游约33ms的第二段均值差，故不选择header复用候选。

P区trace仍只有通用UI／渲染slice。代码核对表明，页表之后还包括 `ReaderViewModel.loadChapter` 的章节窗口激活、pairing.ensure、无痕状态／阅读进度session、身份检查与状态发布，之后由Activity收集状态、pager adapter/首次布局、holder呈现准备再派发header IO。没有找到可直接证明无功能依赖的显式等待；这些环节涉及进度和呈现身份，不能只凭P大就删除。此处区分“观察到P较长”和“尚未证实可消除机制”，不把整个P叫UI CPU、header或调度缺陷。

因此P1-2未采用候选，P1-3不适用；仅保留有维护价值的两点诊断。没有改普通图像读取、共享协议、EXIF、持久缓存、并发、动画或画质，也没有用第二方案追加实验。若继续定位P中的具体依赖，应另定窄范围和预算；B的较完整验证仍未启动。

## 原表外部端点与同次视频

| 控制样本 | 系统DOWN span→StartActivity | →performCreate | →原定义首Reader TX结束 | →应用FIRST_DRAW，trace时钟映射 |
| --- | ---: | ---: | --- | ---: |
| 首次 | 27.068 | 47.721 | 120.323 | 390.925 |
| 同进程再次 | 21.545 | 38.719 | **无效：首TX dur=-1** | 330.151 |

输入起点取同次Perfetto中应用主线程首个 `deliverInputEvent src=0x1002 DOWN` span；StartActivity/performCreate取slice ts，首TX原定义取最早ReaderActivity `actual_frame_timeline_slice` 的ts+dur。暖样本首TX没有结束时刻，不能跳过它再冒称原定义有效。首个后续有效TX结束相对DOWN为 **119.376ms**，只单列为替代proxy，不参与首TX前后差值；该trace还报告 `trace_sorter_negative_timestamp_dropped: 1`，保留数据质量限制。

应用probe单调时钟经同trace `clock_snapshot` 的BOOTTIME−MONOTONIC偏移映射，probe DOWN落在各自首输入span内。映射的FIRST_DRAW是应用真实漫画绘制回调，不等于窗口首TX、视频首漫画或物理屏幕呈现。归零MKV没有设备绝对时钟映射，不生成输入→视频首漫画绝对值。

视频按P0同一极淡残字／页码容忍口径，使用原生帧与原始PTS人工判读：下界=末近黑−首近黑，上界=首正确漫画−最后可辨详情。

| 样本 | 最后可辨详情 | 首近黑 | 末近黑 | 首正确漫画 | 近黑下界–上界 |
| --- | --- | --- | --- | --- | --- |
| 首次 | n26 / 1.693s | n27 / 1.705s | n33 / 1.808s | n34 / 1.821s | **103–128ms** |
| 同进程再次 | n34 / 1.617s | n35 / 1.647s | n38 / 1.702s | n39 / 1.715s | **55–98ms** |

帧从0编号，边界原件在各采集目录的 `video-frames.json`、`contact.png`、`boundary-contact.png`，根保存 `boundary-candidates-control.json`。首次下界大于60ms，明确未达；暖样本跨60ms，未确认，不因下界55ms写成达标。没有重测官方或增加源PTS协议、第三控制样本、候选样本或全模式矩阵。

## APK、源、样本及验证索引

- [两点定位控制APK](../../app/artifacts/android/diagnostics/reader-open-p1-20261103/control/Mihon-Fork-reader-p1-control-benchmark-universal.apk)，SHA256 `f9b44407e8abb9e023d090d50967cc26fe291c826cdc75671071fd529afd0bfe`。这是P0产品逻辑的诊断控制版，不是新性能候选或正式发布。
- 包名 `app.mihon.desktop.fork.benchmark`，vc134 / `0.19.4-aex.116-benchmark`；原证书 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`，签名／非debug标志核验，保留R8及资源压缩。`source-input.json`、`artifact.json`、badging、signature、mapping保存基线8dfd2e2166fc加本批诊断diff及相关源码哈希。
- 唯一定位构建日志 `.gradle-coordinator/reader-open-p1-control-benchmark-20261103.log`，SUCCESS，2分18秒。定向RED/GREEN日志分别 `reader-open-p1-probe-red-20261103.log` / `reader-open-p1-probe-green-20261103.log`，后者单方法及格式通过，22秒。一轮独立差异审查通过，无追加测试或候选构建。
- 真机PCE-W30/API31，`192.168.50.51:5555`。保留数据更新隔离包，一次`speed -f`请求失败，原始输出 `control-compile-speed.json`；实际filter UNKNOWN。未卸载／覆盖官方、fork正式、上游诊断包，未重跑prep或变更SAF授权／内容。
- 同史莱姆第04卷、`Real CBZ 04→Volume 04`，manga/chapter=1/1，page0 / 1/187。规范SAF与P0相同，内容SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`、41,380,343B、187WebP。新包正常UI确认RTL、hint OFF、黑背景，采集后实际本作品RTL和横图自动放大OFF的截图均保留；不从prep默认值推断。
- 有效样本：`p1-control-aligned-1/2` 含MKV、trace和 `capture.json`（均OK）；`p1-control-aligned-ui` 含 `probe-1/2.json`、同PID17149/request1/2观察、设置和漫画截图。29事件是实际观察，不是固定有效性门槛；必要端点、目标身份、顺序及onStop前文件ABSENT均通过。离线数值汇总 `control-summary.md`。
- 无效样本：`p1-control-first-warm-1` 及对应 `*-ui` 原件保留，实际页码2/187。脚本在页码守卫处停止，finally强停前未返回触发onStop，因而无完整probe；未纳入有效分母。仅通过合成条目正常UI“标记为未读”清除恢复位置，以新label替换，不改私有数据库或SAF文件，不展开P0恢复原因调查。
- 正式采样后关闭probe，正常 **1/187→2/187→1/187** 通过。两份page0 PNG与已目视确认的P0蓝色封面完全同哈希，第2页PNG也与已确认P0真实第2页同哈希。离页后正常UI将合成条目标未读，避免后续恢复到第2页。`normal-check.json`及XML/PNG保留。此检查不是性能矩阵。
- 收尾读回 `enabled=false`，隔离包已停止，本轮host Python／scrcpy无残留；各capture保留采集退出及清理回执。产品读取／资源链未改，没有把取消、内容替换保护留待之后补验的新增复用机制。P0未做的B覆盖范围仍见其报告。
