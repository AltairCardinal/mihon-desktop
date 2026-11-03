# Android 阅读器首开 P4：同次打开复用下载位置

日期：2026-11-04。状态：P4实现、定向验证、六份真机对照及独立核验完成，采用本次局部优化；首次内部首绘均值减少37.358ms（11.66%），近黑仍未达到≤60ms。入口为[P4计划](../roadmap/2026-11-04-reader-startup-p4-plan.md)。起点本地main `e848c8e98278a4a395ccf4c851f9f3e3d1468e1a`，保留P0/P3成果。实现与本证据随同一P4提交进入本地main，未推送。

用户要求主代理设计方案，Sol medium实施与唯一真机操作，Luna medium读取和离线核算。本轮仅一个性能候选，不运行完整模块/全量、Windows、模拟器或正式发布，不推送。官方APK与数据保持原状，平板按需唤醒、离线和收尾熄屏。

## 1. 既有证据与本轮选择

P3最终新进程首次①、同进程再次、新进程首次②的内部首绘为325.685/245.217/294.274ms。两次首次相差31.411ms，按同trace线程状态窗口分账，主线程Running相差约16.801ms、S相差14.391ms，其它状态差约0.219ms；这个账目本身不能证明函数独占CPU、I/O、锁或温度原因。

本轮复用原42点和trace。首段已有实际下载检查、章节路径、归档打开/列表读取边界，不需要重新埋相同含义的点：

| P3最终原件，ms | 首次① | 同进程再次 | 首次② |
| --- | ---: | ---: | ---: |
| DOWNLOAD_CHECK | 34.104 | 36.813 | 32.668 |
| CHAPTER_PATH | 37.306 | 39.640 | 38.308 |
| ARCHIVE_OPEN | 34.899 | 14.532 | 23.581 |
| ARCHIVE_LIST | 19.317 | 8.969 | 18.497 |
| GET_PAGES | 94.590 | 67.561 | 89.632 |

GET_PAGES包含路径与归档工作，DOWNLOAD_CHECK位于其前，不能将父子时间相加。这些是墙钟窗口，也不能把37–40ms直接宣布为可回收CPU或净收益。离线映射保存在`.test-tmp/reader-open-p4-20261104/analysis/p3-trace-mapping.json`，三份整数四段与P/H/Q和已核对。

源码链确认：ChapterLoader通过DownloadManager.isChapterDownloaded(skipCache=true)→DownloadCache→DownloadProvider.findChapterDir解析后，只保留Boolean；随后DownloadPageLoader.getPages再次解析同章位置，目录分支DownloadManager.buildPageList还会再解析一次。不是缺少全局缓存的猜测，而是同一次打开中丢弃已取得的结果后重复查找。

因此本轮只选择“复用本次已解析的位置”。新内部DownloadManager入口保持sourceManager.getOrStub(sourceId)语义；位置只在本次ChapterLoader/default factory/DownloadPageLoader之间传递，目录页表复用原筛选排序和错误处理。原Boolean API及其它消费者不变，无跨章节/generation或持久缓存；下一次打开仍查当前存储状态。

解码到首绘窗口另有AsyncTask分配/上传位图与主线程短decode片段，缺少目标任务ID、后台完成和UI回调的完整关联，无法证明某次上传完成决定了首绘。本轮没有据此改调度器或追加解码诊断构建。

## 2. 实现与正确性证据

实现限于三个生产文件：DownloadManager增加内部位置解析和目录页表重载；ChapterLoader把本次位置交给默认工厂，且按ReaderChapter对象身份限制接收者；DownloadPageLoader复用该位置。未传入位置的旧入口保留原路径，目录筛选、排序、页字节及空目录错误仍由同一实现提供。新打开重新解析，位置读取失败发布Storage错误，不偷偷重新查询或回退在线。

新增DownloadedChapterLocationIntegrationTest执行真实DownloadManager、DownloadCache、ChapterLoader、默认DownloadPageLoader和真实目录/PNG，仅替代外部provider等边界。首次有效RED中，页序和字节断言已通过，随后“只查询一次”的断言在旧实现得到三次调用而失败；堆栈分别来自下载判断、DownloadPageLoader和目录页表。原始失败保存在`.test-tmp/reader-open-p4-20261104/location-red.xml`，不是源码文本扫描或复制算法的测试。

最终9个定向方法通过，覆盖单次位置复用、既有页序和真实字节、新打开重查、所选位置失败不回退、下载缺失时原在线/缺source路线、generation迟到取消与前序资源退役、目录文件策略及默认工厂接线。格式检查和git diff检查通过。两处已有夹具按新内部入口机械调整；未执行的整类/全模块不能标为通过。CBZ路径及真实画面已通过下述同章节真机验证。

首次命令入口/variant错误、夹具Flow类型编译错误，以及GREEN后的长签名格式错误均已单独修正，不计为产品RED或性能结果。主代理完成一次独立审查，核对来源getOrStub语义、同chapter对象作用域、新打开重查、失效不回退、页表策略和generation资源边界，未发现阻断；随后冻结构建输入。尚未以测试通过替代真机收益判断。

验证原件与复用边界：

| 原件（均在本轮`.test-tmp`根目录） | 有效范围 | 协调器key |
| --- | --- | --- |
| `location-red.xml` / `location-green.xml` | 同一目录单次解析方法的真实RED→GREEN | `reader-open-p4-red-4-20261104` / `reader-open-p4-green-20261104` |
| `DownloadedChapterLocationIntegrationTest-boundaries.xml` | 上述新增4方法 | `reader-open-p4-boundaries-20261104` |
| `ChapterLoaderSessionGenerationIntegrationTest-boundaries.xml` | 迟到loader不能替换新generation；安装新loader前退役前序资源，2方法 | 同上 |
| `DownloadManagerPageListWiringTest-boundaries.xml` | 原上游文件探测策略及大小写敏感字典序，1方法 | 同上 |
| `ReaderChapterContentRouteTest-routes.xml` | 生产route全支持类型；默认工厂与具体loader绑定，2方法 | `reader-open-p4-route-format-20261104` |

准确方法名及失败分类见`focused-summary.json`。最终格式检查包含在最后一行的任务中；此前7方法已通过、只因一处长签名格式失败的运行不冒称整体命令通过，格式修正未改变这些方法执行的生产行为。

## 3. 对照协议与逐样本结果

控制A为P3最终APK，SHA256 `4d1ea1532076ff4aac9fd3c7de937854934828fbdc7618ce76311470c8f79d6c`；本轮无需新增仪器。候选B保留同套探针。CHAPTER_PATH在候选中测量取得本次已解析位置的边界，接近零不代表整个打开不查路径，真实解析仍在DOWNLOAD_CHECK。

候选B已构建：[Mihon-Fork-reader-p4-benchmark-universal.apk](../../app/artifacts/android/diagnostics/reader-open-p4-20261104/Mihon-Fork-reader-p4-benchmark-universal.apk)，SHA256 `2e88eed0532b59c0243a3bf515fb11255859014bb7e0cd57c4bd4596a913991d`。包名`app.mihon.desktop.fork.benchmark`、versionCode134 / `0.19.4-aex.116-benchmark`、non-debuggable、R8及资源压缩开启；原隔离证书SHA256 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`一致，可保留隔离数据更新。构建key `reader-open-p4-benchmark-build-20261104`成功，基线加生产diff SHA256 `6a05b5361d7745d4490b6c271ffb5285aaeab6c9677a49da7f605e88a51651e8`；18个相关源码/构建输入与冻结清单一致。完整`artifact.json`、输入清单、diff、签名和manifest读回在同一产物目录，不能把基线SHA单独当作本候选源码。

采样顺序A1首次+暖→B1首次+暖→B2首次→A2首次，双方各3份；同平板、史莱姆第04卷、原SAF/CBZ、1/1 page0、单页RTL、横图自动放大OFF、hint OFF、黑背景/原动画和实际OAT条件。每次首次重建目标进程，不宣称清空了系统文件缓存。记录所有有效样本，控制/候选及首/暖分别比较，不混入旧speed或其它仪器。

实际设备为PCE-W30、Android12/API31，USB serial `5JB0224111000992`；整组六次均USB供电、实际OAT前后均install/verify，没有强制编译、清数据、重新prep、无效样本重录或第二候选。六份均42点，目标1/187截图整张SHA256相同；六份已保存设置截图独立确认“自动放大横向图片”复选框为空。hint OFF继承P3且脚本未更改，本轮截图未包含该控件，不能称为独立读回。设置/截图/冻结输入证据见`final-condition-audit.json`，开关裁图见`analysis/landscape-switch-six-saved.png`。

电量快照均77%，已记录的电池温度25→26℃，Thermal Status均0；A1采集前未记录thermal，第一份为A1后。它们是计时窗口外的读数，不证明各CPU频率、温度或后台负载恒定。USB组与旧无线/speed组分开，不用跨组绝对差冒充本次收益。

### 3.1 内部四段与原表近黑区间

四段起点仍是ViewModel初始化处的OPEN_READER_INTENT，不是点击。所有单位ms；首①/首②分别为新进程第一次进入Reader，暖为同进程再次进入，不代表冷文件缓存。

| 实际采样顺序 | 初始化→页表 | 页表→OPEN_PAGE | OPEN_PAGE→提交解码 | 提交解码→首绘 | 合计 | 视频近黑范围 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| A1 控制首① | 155.151 | 67.607 | 9.670 | 85.153 | 317.580 | 99–133 |
| A1 控制暖 | 111.905 | 48.791 | 8.411 | 79.251 | 248.358 | 19–88 |
| B1 候选首① | 113.716 | 68.858 | 15.741 | 89.087 | 287.402 | 67–99 |
| B1 候选暖 | 113.319 | 45.499 | 8.771 | 86.356 | 253.944 | 35–69 |
| B2 候选首② | 110.917 | 67.859 | 11.076 | 88.835 | 278.688 | 66–100 |
| A2 控制首② | 166.618 | 57.021 | 12.913 | 86.673 | 323.225 | 99–132 |

按首次配对，B1比A1减少30.179ms，B2比A2减少44.538ms；两次首次均值320.402604→283.044531ms，减少37.358073ms（11.66%，每版本n=2）。第一段均值减少48.568ms，后三段分别增加6.044/2.117/3.048ms，合计抵消11.210ms。这证明本组首次有净收益，不承诺每次固定省37ms或P95；暖态唯一配对反而增加5.586ms，不宣称暖态改善。

近黑下界为末近黑PTS−首近黑PTS，上界为首正确漫画PTS−末可辨详情PTS。Sol与主代理都看了六份原生连续帧，再由Luna核整数PTS，触摸涟漪未计入近黑。B1暖的首近黑仍保留极淡不可读轮廓，采用保守较早帧36，不后移到37以凑≤60ms。原生视频采样间隔不恒定，因此上下界宽度不同，不把区间中点冒充精确测量。

| 样本 | 四边界零基帧号 | 四边界原生PTS，秒 |
| --- | --- | --- |
| A1首① | 94 / 95 / 101 / 102 | 1.606 / 1.623 / 1.722 / 1.739 |
| A1暖 | 35 / 36 / 37 / 38 | 1.681 / 1.715 / 1.734 / 1.769 |
| B1首① | 91 / 92 / 96 / 97 | 1.606 / 1.622 / 1.689 / 1.705 |
| B1暖 | 35 / 36 / 38 / 39 | 1.606 / 1.622 / 1.657 / 1.675 |
| B2首② | 98 / 99 / 103 / 104 | 1.703 / 1.720 / 1.786 / 1.803 |
| A2首② | 98 / 99 / 105 / 106 | 1.753 / 1.769 / 1.868 / 1.885 |

### 3.2 具体削减位置及未转化的耗时

| 样本，ms | DOWNLOAD_CHECK | CHAPTER_PATH | ARCHIVE_OPEN | ARCHIVE_LIST | GET_PAGES |
| --- | ---: | ---: | ---: | ---: | ---: |
| A1首① | 35.983 | 33.454 | 37.859 | 17.487 | 92.147 |
| A1暖 | 35.452 | 24.598 | 23.522 | 9.414 | 60.699 |
| B1首① | 35.045 | 0.002 | 28.796 | 24.902 | 56.771 |
| B1暖 | 29.501 | 0.002 | 27.239 | 14.134 | 43.739 |
| B2首② | 33.072 | 0.002 | 25.421 | 28.152 | 57.656 |
| A2首② | 31.910 | 44.739 | 46.119 | 19.230 | 115.061 |

CHAPTER_PATH近零与源码单次复用一致；真实解析仍由DOWNLOAD_CHECK承担约30–36ms。GET_PAGES包含路径和归档窗口，禁止相加重复记账。控制首次重复路径33.454/44.739ms被消除，但归档、页表后的工作与调度也变化，不能用被删区间代替完整净收益。

| 样本，ms | P：页表→目标header开始 | H：header读取 | Q：header结束→OPEN_PAGE |
| --- | ---: | ---: | ---: |
| A1首① | 55.367 | 4.598 | 7.641 |
| A1暖 | 39.789 | 4.228 | 4.773 |
| B1首① | 54.710 | 7.907 | 6.241 |
| B1暖 | 38.216 | 4.217 | 3.067 |
| B2首② | 50.480 | 8.535 | 8.844 |
| A2首② | 44.866 | 4.371 | 7.785 |

暖态未变快的可定位现象：初始化→GET_PAGES_END候选已提前24.526ms，但GET_PAGES_END→PAGE_LIST_READY从9.849变成35.789ms，多25.939ms，抵消前段收益。该窗口没有更多probe点；同窗口主线程S多18.307ms、Running多7.239ms，可见绘制/布局相关slice，但没有把发布页表的等待与某个任务连起来的因果边。不能把S直接命名为I/O、锁、GC，也不能断言全部25.939ms只来自调度。没有为此改全局调度器或增加本轮候选。

### 3.3 同次原表系统端点

下表四列均从同一trace最早的`deliverInputEvent src=0x1002`起算；该slice与映射后的应用MAIN_TOUCH_DOWN相邻，二者不混用。StartActivity取精确异步`StartActivity:<包>/<ReaderActivity>`，创建取performCreate开始，首图层取最早Reader实际TX的结束。FIRST_DRAW从probe MONOTONIC按同trace双clock snapshot映射到BOOTTIME。单位ms。

| 样本 | 输入→StartActivity | 输入→performCreate | 输入→首Reader TX结束 | 输入→映射FIRST_DRAW |
| --- | ---: | ---: | ---: | ---: |
| A1首① | 31.107 | 78.941 | 153.485 | 409.335 |
| A1暖 | 21.636 | 38.761 | 87.812 | 298.273 |
| B1首① | 33.067 | 60.134 | 140.866 | 366.961 |
| B1暖 | 25.674 | 40.752 | 104.775 | 306.097 |
| B2首② | 29.490 | 48.502 | 126.109 | 342.949 |
| A2首② | 34.353 | 89.714 | 168.976 | 425.554 |

首图层不代表漫画像素，映射FIRST_DRAW也不冒充物理显示完成；归零MKV PTS没有直接换算成点击→首漫画的绝对时间。本次输入→映射FIRST_DRAW首次均值少62.489ms，其中包含初始化以前的变化，不将全部归于本次删除的路径查找。

数据质量说明：A1首次trace有1条`trace_sorter_negative_timestamp_dropped`错误统计，其余为0；所用输入、精确Activity、TX、clock snapshot及probe端点齐全且时长非负，因此保留该样本及限制。Luna早期聊天中三份候选native摘要存在不可复算数字，主代理发现后撤回，没有用更换输入slice来编造解释；最终以六份重新查询CSV为准，主代理另从B1原trace独立复核Start/create，并从六份原probe复算全部四段及P/H/Q。更正、查询SQL、CSV与整数时间均保存在唯一`analysis/p4-abba-summary.json`及旁边文件，不靠聊天摘要复用证据。

## 4. 采用判断、原件与收尾

采用本次单次位置复用：真实RED、生产接线focused、独立审查、CBZ真机和两组首次净收益相互支持。首次近黑从99–133/99–132ms改善为67–99/66–100ms，仍高于≤60ms目标；暖态没有净收益证据。当前批次完成不等于整体首开目标完成或全部模式验收通过。

连接阻塞已解除：2026-11-04 00:21前原无线地址不可达；约01:00用户插USB，核同设备authorized后直接恢复原采样，不重新编译或改仪器。原无线失败不被解释成锁屏/关机。成功USB指定serial、正常唤醒、批后finally熄屏方法已记入[平板实测指南](../automation/ANDROID_TABLET_ACCEPTANCE.md)，AGENTS继续链接该唯一入口。

最终保留数据装回候选B，probe关闭后完成三轮1→2→1。Sol和主代理独立看7格截图：封面与第二页蓝色插画、页码和返回画面都正确，同页crop字节SHA相同，未复现P3已修复的停稳黑屏。原件`p4-final-normal-flip/contact.png`及`normal-flip.json`。最终`enabled=false`、隔离包无PID、无本任务scrcpy残留，电源读回Asleep且changing=false；见`final-device-cleanup.json`、`final-host-scrcpy.json`。官方与正式fork的APK/数据未覆盖；没有用户解锁请求或改屏幕常亮设置。

后续未执行范围保持不变：Windows、模拟器、其它模式/存储类型扩展真机验证、完整模块/全量与正式发布。本轮候选首次剩余页表→OPEN_PAGE约68ms、提交解码→首绘约89ms，尚未证明其中哪些依赖可安全删除；另有上述页表发布间隙波动。后续应先关联目标任务的提交/完成/主线程接收，再选一个候选，不重启已排除的全章扫描或仅凭S状态改调度策略。本批不自动扩展第二候选。

文件超过8个是同一内聚批次：3个生产文件、新集成测试及2处夹具、探针语义/设备经验/AGENTS入口、计划和结果与索引；没有改共享配对协议、schema、下载格式、全局调度或公共功能宣传。已完成风险边界对应的focused及真实CBZ路径，不为提交增加全量测试。

本轮原件目录`.test-tmp/reader-open-p4-20261104/`，APK目录`app/artifacts/android/diagnostics/reader-open-p4-20261104/`。最终只提交本任务代码、测试及必要文档，不推送；全量、Windows及正式发布仍后置。
