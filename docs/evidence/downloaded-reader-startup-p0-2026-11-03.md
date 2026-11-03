# Android 下载章节首开 P0 实测

日期：2026-11-03。范围：[执行 roadmap](../roadmap/2026-11-03-downloaded-reader-startup-optimization-roadmap.md) A0–A3；B/C 未执行。本轮不是正式发布或全部阅读模式验收。

下载章节首开的长时间空白已明显缩短：三次初始化→漫画首绘由 **646.843 / 632.686 / 651.699ms** 降至 **263.953 / 272.514 / 266.034ms**，平均减少 **376.243ms（58.4%）**。同原表视频口径，近黑范围从 **398–467 / 397–465 / 382–448ms** 缩短到 **68–99 / 67–100 / 84–117ms**。本场景正确封面可见，正常首次翻页可用；但近黑仍未达到≤60ms目标，不宣称已追平官方或全部模式验收通过。

## 同场景前后结果

顺序均为新进程首次①／同进程再次／另一个新进程首次②，单位 ms。旧值来自[已对齐 RTL 分析](../research/reader-pairing/2026-11-03-downloaded-open-latency-analysis.md)，不是本轮重测或同次随机 A/B。

| 连续内部阶段 | 旧主干三次 | P0 三次 | P0−旧主干，逐次 |
| --- | --- | --- | --- |
| 初始化→页表就绪 | 511.640 / 524.822 / 532.627 | 123.808 / 114.717 / 121.179 | −387.832 / −410.105 / −411.448 |
| 页表就绪→OPEN_PAGE | 46.189 / 28.418 / 40.159 | 49.552 / 70.422 / 44.393 | +3.363 / +42.004 / +4.234 |
| OPEN_PAGE→提交解码 | 12.688 / 2.484 / 8.720 | 2.681 / 6.509 / 8.567 | −10.007 / +4.025 / −0.153 |
| 提交解码→首次漫画绘制 | 76.327 / 76.961 / 70.194 | 87.911 / 80.865 / 91.894 | +11.584 / +3.904 / +21.700 |
| **合计** | **646.843 / 632.686 / 651.699** | **263.953 / 272.514 / 266.034** | **−382.890 / −360.172 / −385.665** |
| 章级 header，已含于旧第一段 | 397.032 / 412.359 / 403.196 | **已移除／不适用** | 不把缺席记成零耗时事件 |

总计按未舍入时间戳计算，分段显示舍入可产生0.001ms尾差。当前页段和绘制段并未全面缩短，暖态页表后段增加42.004ms；本页 header 仍在正常按需链，可进入页表后段，不能把第一段省下的时间全部当成总回收量。共享 owner、holder 和解码器未改，现有细分事件不足以把残余段的增加唯一归因于某个 API。

P0 内部均值 **267.500ms**，已接近上游探针旧参考 **220.830ms**，仍高 **46.670ms**。这是跨包、不同采集轮次的描述性比较，包/私有库/ART状态等差异保留，不用于扣除探针开销，也不能证明未改官方的外部首漫画端点已达标。三样本不是P95。P0 后较大的内部段是页表准备114.717–123.808ms与提交解码到首绘80.865–91.894ms；本轮不据阶段名推断唯一原因或追加P1实验。

| 原表外部指标 | 旧主干三次 | P0 三次 | P0−旧主干，逐次 |
| --- | --- | --- | --- |
| 输入 DOWN 送达 span→StartActivity | 25.659 / 23.520 / 27.289 | 24.453 / 24.400 / 24.573 | −1.206 / +0.880 / −2.716 |
| →performCreate | 40.789 / 38.382 / 45.504 | 69.497 / 43.223 / 40.738 | +28.708 / +4.841 / −4.766 |
| →首 Reader TX 结束 | 90.440 / 87.796 / 91.314 | 115.724 / 83.457 / 83.603 | +25.284 / −4.339 / −7.711 |
| 视频近黑范围 | 398–467 / 397–465 / 382–448 | **68–99 / 67–100 / 84–117** | 两端分别 −330/−368、−330/−365、−298/−331 |

输入取同次 Perfetto 中主线程 `deliverInputEvent src=0x1002 DOWN` 的 span 起点；首 TX 为最早 ReaderActivity `actual_frame_timeline_slice` 的 `ts+dur`。首次①窗口启动段比旧样本更长，仍没有抹去首漫画总改善，不能只选择较好端点报告。

同次 trace 的 `clock_snapshot` 提供 BOOTTIME−MONOTONIC 偏移，将 probe `FIRST_DRAW` 映射后，系统 DOWN span→应用首次漫画绘制为 **343.921 / 326.856 / 318.341ms**。probe 的 DOWN hook 映射分别落在该 span 内，距 span 起点约1.327 / 0.183 / 1.739ms；后者不是原表起点。旧 hook→FIRST_DRAW 的697.606 / 681.830 / 703.685ms不直接作为这个系统 span 表的差值。TX、应用绘制和物理可见是不同终点。

视频使用原生帧和原始归零 PTS，允许极淡残字／页码，与旧 `boundary-contact.png` 人工口径一致。下界=末近黑−首近黑，上界=首正确漫画−最后可辨详情；没有将输入时钟强行映射到 MKV。

| 样本 | 最后可辨详情 | 首近黑 | 末近黑 | 首正确漫画 | 近黑下界–上界 |
| --- | --- | --- | --- | --- | --- |
| 首次① | n34 / 1.757s | n35 / 1.773s | n39 / 1.841s | n40 / 1.856s | 68–99ms |
| 同进程再次 | n36 / 1.734s | n37 / 1.750s | n41 / 1.817s | n42 / 1.834s | 67–100ms |
| 首次② | n33 / 1.702s | n34 / 1.719s | n39 / 1.803s | n40 / 1.819s | 84–117ms |

帧编号从0开始；每个采集目录保存 `video-frames.json`、`contact.png` 和 `boundary-contact.png`，根目录保存 `boundary-candidates-p0.json`。相对官方旧参考近黑上界33–54ms，当前上界99–117ms仍明显更长；距本项目≤60ms目标，三个区间下界至少超出8 / 7 / 24ms，上界超出39 / 40 / 57ms。视频绝对输入→首漫画留待B的既有源PTS协议。

## 产物与输入

- 优化包：[隔离 benchmark APK](../../app/artifacts/android/diagnostics/reader-open-p0-20261103/Mihon-Fork-reader-open-p0-benchmark-universal.apk)；SHA256 `25ebe51c5303ae76cd3ef5eb46e0f53c5bc21690c3ad917fccd72b845bdeb890`。
- 包名 `app.mihon.desktop.fork.benchmark`，vc134 / `0.19.4-aex.116-benchmark`，继承 release 的 R8、资源压缩和非调试配置；原诊断证书 SHA256 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`，签名核验通过。保留数据更新安装；没有覆盖或卸载官方、fork 正式、上游诊断安装。
- 构建基线 `a91dfbacf8455c8c3ad310093ac33ec329ad509e`，实际 APK 源码是该基线加本批两文件 P0；`source-input.json` / `artifact.json` 保存实际 source diff SHA256、相关源码哈希及构建日志，最终提交只另含测试、维护说明与结果。
- 真机 PCE-W30 / API31，ADB `192.168.50.51:5555`。隔离包 `speed -f` 首次请求返回1；一次有界诊断重复返回 `Success`。实际 compiler filter 未读回，仍为 UNKNOWN，不能宣称与官方编译状态相同。
- 原专用 SAF 规范章节保持不变：`/sdcard/MihonLatencyForkProbe20261103/downloads/610004/Real CBZ 04/Volume 04_c77087.cbz`，41,380,343B，设备读回 SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`；没有重新执行 prep。
- `Real CBZ 04 → Volume 04`，mangaId/chapterId=1/1，page0 / 1/187，单页 RTL，自动放大横向图片 OFF，阅读模式提示 OFF，黑背景。真实 UI 预检截图保存在下述证据根；三次采集后各保留实际模式/横图设置与漫画像素截图。

## 实现、行为证据与边界

只删除 `AndroidReaderChapterContentPort` 新页表与 `ChapterLoader` 已 Loaded 复用的全章 header 遍历及专用参数。页序、章节与编码身份、generation、loader 安装/退役、错误/取消、恢复页不变；本页事实、holder、共享 owner、解码、EXIF、数据库和 Desktop 未修改。

两个真实行为红测在旧实现上都因实际打开187个流而失败（期望0，实际187），不是源码扫描或人为挂起。P0 后同两个方法通过：新页表保持页序、绑定与代次；新建 Loaded 页复用不扫描，恢复目标第126页可单独取得32×48、NORMAL真实元数据且编码身份稳定，远页仍为 UNKNOWN。

第三个定向检查 `ReaderImageOrientationWiringTest.first content probe requires real bitmap draw and rejects old request` 通过。三个行为检查均来自真实 production wiring；没有运行整个大型 wiring 类、全模块或 Desktop 回归。格式检查通过；一轮生产/测试/维护文档差异审查无阻断。

定向日志位于 `.gradle-coordinator/reader-open-p0-red-20261103.log`、`reader-open-p0-green-lf-20261103.log`、`reader-open-p0-format-20261103.log`。首次绿测尝试被 CRLF 格式拦截；统一 LF 后三个行为通过，再按格式器要求机械展开两个测试 lambda，仅补格式检查。构建 `reader-open-p0-benchmark-20261103.log` 为 `BUILD SUCCESSFUL`，耗时3分49秒；没有为文件名或最终报告重建 APK。

原 `HEADER_FACTS_BEGIN/END` 不再发出，章级区间为不适用，未伪造零耗时事件。本页 header 仍可计入 `PAGE_LIST_READY→OPEN_PAGE`；总时长必须与第一段同时比较。计数测试证明章级调用结构，不证明真机总读取字节为零。

## 原始证据索引

本机证据根：`.test-tmp/reader-open-p0-20261103/`。采集脚本复制自原 `fork-reader-tablet-probe-20261103`，原件未改；脚本 SHA与适配说明保存在 `script-provenance.json` 和产物清单。保留原 MKV+Perfetto、身份/解锁/焦点/导出隔离守卫；新增检查仅在计时采集后读取真实 UI。

| 样本 | 外部采集目录 | 探针与 UI 目录 | PID / request |
| --- | --- | --- | --- |
| 新进程首次① | `p0-saf-rtl-first-warm-1` | `p0-saf-rtl-first-warm-ui/probe-1.json` | 11276 / 1 |
| 同进程再次 | `p0-saf-rtl-first-warm-2` | `p0-saf-rtl-first-warm-ui/probe-2.json` | 11276 / 2 |
| 新进程首次② | `p0-saf-rtl-second-process-1` | `p0-saf-rtl-second-process-ui/probe-1.json` | 11278 / 1 |

三次 `capture.json` 为 OK；必要端点、次序、目标身份及未在 onStop 前导出均通过，没有排除的正式样本。三次先完成 page0 条件，之后才关闭探针检查正常翻页。MKV 为归零 PTS，不能用主机启动时间计算输入→视频首漫画；FIRST_DRAW 是应用真实绘制回调，仍不等于物理屏幕呈现。

三份最终 `reader-*.png` SHA256完全相同，已目视确认第04卷蓝色封面；正常阅读检查为 **1/187→2/187→1/187**，第2页真实像素已检查。探针配置读回 `enabled=false`，隔离包已停止；本轮 host Python／scrcpy 无残留，采集清理回执保留在各 `capture.json`。此顺手翻页检查不是性能或模式矩阵。

## 首轮未覆盖范围

本轮没有迁移旧的“无 holder 即全章事实齐备”测试，没有执行 B 的目录/其他归档/双页/条漫/恢复/迟到事实完整回归，也没有运行全部 EXIF、全模式或全量矩阵。已知旧 metadata retry 基线失败未重复运行。没有重新测官方、探针开关对照、追加样本、P1 或 Windows 优化；不得把三样本结果写成全部模式或正式发布通过。
