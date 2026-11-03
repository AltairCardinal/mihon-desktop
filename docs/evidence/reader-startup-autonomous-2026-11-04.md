# 下载章节首开自主优化记录

日期：2026-11-04。执行入口为[持续计划](../roadmap/2026-11-04-reader-startup-autonomous-plan.md)。R1已完成真实红绿、独立审查、一次构建及六份真机交替对照：保留局部无效工作削减，但**未证明首开或近黑净改善**；按预先固定的收益/成本规则收口，不继续R2/R3。性能目标未完成。基线为`d4c9862d47ce001e08f567303f7eea198148a966`，原工作区干净。

## R1：同值通知重复转换整章状态

观察：代码链显示页表完成后187次初始通知进入`ReaderChapter.dispatch`。reducer已为同值返回原snapshot，adapter仍无条件执行整章legacy映射。它不同于P3首次事实订阅重复重算，也不同于P4重复文件位置查找。

候选：只跳过`next === current`时的投影；保留初始绑定通知及所有真实变化。两条入口使用同一可重入锁；需由真实绑定测试和generation相关focused确认安全性。没有依据将此前页表发布间隙的全部波动都归因于此。

真实红测确认187页初始化时投影188次；同一测试的页面完整性断言先通过，最后计数期望1、实际188而失败。仓库失败重试使同一方法出现3条失败，不能写成3个独立红测。原XML为`.test-tmp/reader-open-goal-20261104/projection-red.xml`，协调器key `reader-open-p5-projection-red-20261104`。

最小修复后3个新增方法、6个`ReaderSessionProductionWiringTest`方法、5个`ChapterLoaderSessionGenerationIntegrationTest`方法，共14个通过；覆盖同值、真实状态/编码身份变化、descriptor建立后绑定前变化、迟到generation与资源退役。加强页序/内容身份断言后新3方法再次通过。原XML为同目录`projection-green.xml`、`session-green.xml`、`generation-green.xml`、`projection-final-green.xml`；GREEN key `reader-open-p5-projection-green-20261104`。首次格式因internal签名超长失败，机械换行后`:app:spotlessKotlinCheck`通过，key `reader-open-p5-projection-format-fix-20261104`。`git diff --check`通过，未执行整模块或全量测试。

主代理独立检查真实dispatch、绑定入口、可重入锁、reducer同值返回和flow顺序，未发现阻塞。production只增加同对象短路及现有函数internal测试可见性，不改变共享reducer、通知或仪器。实际修复的是同值通知重复转换整章页表，**不是已修复首开黑屏**。

### 构建及测量条件

- 控制A：P4隔离APK，SHA256 `2e88eed0532b59c0243a3bf515fb11255859014bb7e0cd57c4bd4596a913991d`。
- 候选B：[诊断APK](../../app/artifacts/android/diagnostics/reader-open-p5-20261104/Mihon-Fork-reader-r1-benchmark-universal.apk)，SHA256 `28d33774d31e4abc4de792e1dd0746d4a1b4335c16f5c419fca23ebc4f71d4aa`。`P5`仅为产物目录命名，本持续计划中为R1。
- `app.mihon.desktop.fork.benchmark`，code134/version `0.19.4-aex.116-benchmark`；原证书SHA256 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`，R8、资源压缩、non-debuggable。同42点仪器，未改记录器。
- 构建key `reader-open-r1-benchmark-build-20261104`；源码基线加本轮diff，diff SHA256 `a3cb19c9c9baefe0c0954236d7d4fa49ac9f8788dccf627a1b6db03bbec0b924`。同产物目录`artifact.json`保存20个关键输入，主代理复核20项均未变、APK哈希相符。诊断产物不冒充已发布主干包。
- 同一USB平板PCE-W30/API31，serial `5JB0224111000992`，实际OAT各组均`install/verify`。同史莱姆第04卷187页真实CBZ，沿用既有SAF与逻辑章节1/1，从page0开始，未清数据或重跑prep。
- 顺序为A1首次/暖→B1首次/暖→B2首次→A2首次。Main-only前置，不提前进入Reader。单页RTL，横图自动放大OFF，两个阅读提示OFF、原翻页动画ON、黑背景；实际控件截图留存，六份横图控件均独立核看为空框。
- 所有批次前后均USB供电、电量79%、Thermal Status 0；A1电池25℃，其余26℃。这些是计时窗口外读数，不证明CPU/GPU频率恒定。屏幕仅在前台操作时亮起，其余时段熄屏。官方与正式fork APK和数据未改动。

### 六份同次探针与原表指标

以下单位ms，四段起点是ViewModel初始化，**不是触摸送达**；第4段终点是目标图片绘制回调，不是物理屏幕呈现。

| 样本 | 初始化→页表 | 页表→OPEN_PAGE | OPEN_PAGE→解码提交 | 解码提交→首绘 | 合计 | getPages结束→页表 | 视频近黑区间 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| A1 首次 | 110.345 | 70.221 | 12.839 | 85.772 | 279.177 | 10.553 | 66–99 |
| A1 暖态 | 92.739 | 59.976 | 2.011 | 88.092 | 242.817 | 10.274 | 34–68 |
| B1 首次 | 96.925 | 98.810 | 11.245 | 91.446 | 298.426 | 6.331 | 67–101 |
| B1 暖态 | 72.280 | 83.717 | 5.088 | 96.339 | 257.423 | 3.767 | 50–85 |
| B2 首次 | 116.751 | 77.884 | 9.480 | 77.058 | 281.173 | 11.204 | 67–99 |
| A2 首次 | 123.107 | 60.976 | 9.463 | 99.505 | 293.051 | 15.056 | 67–100 |

原表native输入取较早的`deliverInputEvent src=0x1002`；StartActivity取Reader精确异步slice，不使用`startActivityInner`。下面首TX只是Reader关联图层帧完成，不能据此判实际内容响应≤100ms。

| 样本 | 输入→StartActivity | 输入→performCreate | 输入→首Reader TX结束 | 输入→映射FIRST_DRAW |
| --- | ---: | ---: | ---: | ---: |
| A1 首次 | 25.959 | 47.612 | 125.186 | 341.793 |
| A1 暖态 | 21.573 | 41.528 | 94.430 | 294.678 |
| B1 首次 | 31.371 | 53.418 | 140.545 | 367.169 |
| B1 暖态 | 22.284 | 37.934 | 95.617 | 307.118 |
| B2 首次 | 34.205 | 56.233 | 139.997 | 353.017 |
| A2 首次 | 36.922 | 59.815 | 144.344 | 368.757 |

probe MONOTONIC以各trace双clock snapshot映射到BOOTTIME，偏移约234727154338522ns、两快照差不超过521ns；没有用主机发ADB时间或归零视频PTS混算。视频只计算自身四边界的区间，没有补出绝对输入→实际漫画可见。前五份trace各记录1条`trace_sorter_negative_timestamp_dropped`，A2无；相关端点存在且顺序有效，所有样本保留，未将警告归因于产品或删除不利样本。

连续帧由Sol和主代理独立核看，Luna按原生PTS交叉计算；帧组A1首99/100/104/105、A1暖37/38/40/41、B1首95/96/100/101、B1暖36/37/40/41、B2首95/96/100/101、A2首102/103/107/108，依次为最后可辨详情/首近黑/末近黑/首正确漫画。残余可辨详情和输入涟漪不算近黑，正确漫画出现后的所核连续帧无二次闪黑。

全部原件位于`.test-tmp/reader-open-goal-20261104/r1/`，每个`r1-<control或candidate>-<批次>-<次序>`目录保存trace、视频、native PTS与`boundary/selection.json`、`continuous.png`；probe在相应`*-ui`目录。精简整数时间与计算为`analysis/sample-metrics.csv`、`sample-metrics.json`、`summary.txt`及沿用P4的SQL。原视频/图片/完整trace包含私人阅读内容，不提交Git。

### 结果解释、采用与停止决定

首次合计均值A **286.114ms**、B **289.800ms**，候选名义慢3.686ms；第一次配对慢19.249ms，第二次配对快11.878ms。唯一暖态242.817→257.423ms，名义慢14.606ms。两组首次近黑上界均值99.5→100.0ms，下界66.5→67.0ms，差0.5ms远小于视频帧间距，不能解释为真实改善或确定回退；候选两次首次下界均>60ms，目标仍未达。

局部窗口getPages结束→页表首次均值12.804→8.768ms，减少4.036ms；但它还包含其它工作与调度，不能把全部差值视为该函数独占收益。首次四段均值差为−9.888/+22.748/−0.789/−8.387ms，页表→OPEN_PAGE增加22.748ms抵消其它段下降。这说明“少做工作”不自动等于“更早显示”：剩余UI挂载、本页处理与帧机会仍影响终点。当前没有把该22.748ms落实到单一原因，不把线程休眠叫作I/O，也不声称本次guard导致新等待。

保留已用真实生产测试证明的局部冗余削减：不增加读取、调度、缓存或等待，真实变化和generation契约保持，正常翻页通过。没有证据证明整体回退由这处改动引起，同时也**不将它宣传为首开性能改善**。此次只确认重复投影问题已修复。后续若出现可复现回退，应按本报告基线和已冻结APK复查，不能以“逻辑上更少工作”为由否认实测。

按预先固定的主指标，本轮名义收益为−0.5ms，没有可确认正收益；决策用正收益计0，`ROI=0`，低于P4的四分之一门槛0.3104495ms/USD。此次停止依据是**本轮没有测到值得继续投入的净收益**，不是统计证明所有后续算法或平台均无优化空间。没有增加第三轮样本、第二候选或全模块测试去追有利数字。R2/R3未执行，双端总体目标仍未完成。

关闭probe后候选三轮正常1→2→1通过，7张图的页码和相同页内容经主代理复核，未复现P3停稳黑屏；证据`r1-final-normal-flip/contact.png`及相应JSON。最终probe OFF、隔离包无PID、采集进程退出。首次收尾读数为Asleep但changing=true，包装断言失败；只读复核已Asleep且changing=false，属于状态收敛，未重发唤醒。原件`final-device-cleanup.json`与`final-power-settled.json`均保留。成功操作补入既有平板指南，不改屏幕超时或锁屏配置。

## 成本与收益边界

P4首次近黑上界均值改善33ms，内部首绘改善37.358ms。本轮主收益与成本规则见计划。官方价格来源：[API价格](https://developers.openai.com/api/docs/pricing)、[Sol模型](https://developers.openai.com/api/docs/models/gpt-6.1-sol)、[Astra模型](https://developers.openai.com/api/docs/models/gpt-6-astra)，核价日期2026-11-04（本地）。

| 模型 | 普通未缓存输入 / 缓存输入 / 输出，USD每百万token | P4完整批次标准化成本 |
| --- | --- | ---: |
| GPT-6 Astra | 10 / 1 / 50 | $21.296114 |
| GPT-6.1 Sol | 2 / 0.10 / 10 | $5.141644 |
| GPT-6 Luna | 0.10 / 0.01 / 0.50 | $0.136608 |
| 合计 | 每请求输入超过272,000时，整请求输入价格×2、输出×1.5 | **$26.574366** |

P4覆盖UTC 2026-11-03 16:02:00至17:18:34.071，包括两次主代理turn及子代理期间工作，截止任务完成而非Git提交。按thread/turn/response身份核得363个唯一响应，无重复；其中24请求达到长上下文档位。缓存写入字段在每个响应中存在且为0；reasoning属于output，不另收费。服务档位缺失，以上按统一Standard价格估算API等价成本，不是实际账户账单；若整批均为Fast则成本×2，同口径比较的比例不变。

旧footer漏掉Sol中间一次57响应的turn（14,247,617输入token），因为续轮member登记发生在该turn最后响应后约0.764秒。此次按实际目标批次补齐，不把漏计当作成本下降。原件为`.test-tmp/reader-open-goal-20261104/cost/cost-reconciliation.json`及`p4-turn-breakdown.json`；只保存数字元数据，不把会话正文提交仓库。

P4主指标ROI为`33 / 26.574366 = 1.241798 ms/USD`；下一轮严格低于`0.3104495 ms/USD`才触发四分之一停止条件。不能用P4的33ms除本轮成本冒充本轮收益。

本轮收尾快照覆盖UTC **17:34:10至18:05:54.042**，287个唯一响应，按同口径已记录 **$14.882927**（Astra $12.468800、Sol $2.256028、Luna $0.158099）；无长上下文请求，缓存写入字段均为0。包含调查、核账、实施、测试/失败、采集分析、审查及截至该时刻的文档工作。固定数值原件为`cost/final-audit-output.json`，此后的少量提交/最终汇报token尚未进入该快照，因此是本轮总成本下界而非完整结算账单；后续成本只会上升，正收益为0的停止裁决不受影响。若采用未经正收益截断的名义差−0.5ms，当前比值约−0.0336ms/USD，同样没有达到继续条件。

## 当前未完成项

Android单页≤60ms仍未证实；完整输入→首漫画时钟对应、双页完整可读和Windows同条件首开仍须按基准处理。已有P4结果只支持该轮登记场景的改善，不代表整体目标完成。官方APK和数据不得修改。
