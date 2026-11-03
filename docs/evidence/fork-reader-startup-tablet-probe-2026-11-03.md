# Mihon Desktop 主干安卓版：真机同次探针与原表测量

日期：2026-11-03。基于本地主干 `0db8c3ddf37e28e2ca1a895cf21accbeee05a0e0` 加入稀疏探针，用 PCE-W30 / Android12(API31) 完成三次有效单页 RTL 采样；不是上游0.19.9探针副本。官方及fork正式APK/数据保持不变。

## 同次结果

每列的探针、Perfetto和视频属于同一次打开。单位毫秒。首次①/暖开 PID8418，首次② PID8419；request=1/2/1，chapter/page=1/0，均1/187。首次指新进程第一次打开阅读器，之前正常进入主界面和详情；不指物理磁盘冷态。

| 原表指标 | 新进程首次① | 同进程再次 | 新进程首次② |
| --- | ---: | ---: | ---: |
| 输入送达→系统 StartActivity 开始 | 25.659 | 23.520 | 27.289 |
| 输入送达→performCreate 开始 | 40.789 | 38.382 | 45.504 |
| 输入送达→阅读器关联图层首 TX 帧完成 | 90.440 | 87.796 | 91.314 |
| 视频近黑持续范围 | 398–467 | 397–465 | 382–448 |

原表系统起点严格使用本次主线程第一条 `deliverInputEvent src=0x1002` 的 span ts；不是稍晚的应用触摸hook。StartActivity/performCreate取slice开始；首TX取最早匹配ReaderActivity实际图层帧的ts+dur。图层首帧不代表漫画已出现，不能将约90ms当成漫画打开完成。

| 内部探针连续四阶段 | 新进程首次① | 同进程再次 | 新进程首次② |
| --- | ---: | ---: | ---: |
| 初始化→页面列表就绪 | 511.640 | 524.822 | 532.627 |
| 列表就绪→调用当前页数据流之前 | 46.189 | 28.418 | 40.159 |
| 调用点→提交解码 | 12.688 | 2.484 | 8.720 |
| 提交解码→首次实际漫画绘制 | 76.327 | 76.961 | 70.194 |
| 四段合计：初始化→首次绘制 | 646.843 | 632.686 | 651.699 |
| 其中：全187页 header facts 遍历（已含在第一段） | 397.032 | 412.359 | 403.196 |
| 输入送达→探针首次绘制（同trace时钟对齐） | 699.234 | 682.183 | 704.553 |

初始化起点为ViewModel `OPEN_READER_INTENT`，并非点击或真正发出Intent。OPEN_PAGE在调用streamFn之前；DECODE_SUBMIT是提交现有解码链，不是底层解码CPU开始。FIRST_DRAW接原有实际bitmap绘制回调，已由真实SSIV/canvas测试校验。上述区间包含调度、IO和原有工作，不是独占CPU耗时。

**观察：本轮最大的内部段是全187页header facts遍历，397–412ms，约占初始化至首绘总时长的61%–65%；再次打开仍执行该遍历。** 它发生在PAGE_LIST_READY之前，已包含在第一段，不得重复相加。GET_PAGES约69.342/73.258/79.792ms，其中归档打开+列表约32.329/31.737/42.056ms；详细纳秒值以JSON实际两个子段为准。

此观察定位了时间集中处；本轮未删除或并行化该遍历，不能据此宣布去掉它必然能节省相同毫秒数，也没有校准当前fork的无探针内部耗时。

## 视频四边界与时钟

| 样本 | 最后可辨详情 | 首近黑 | 末近黑 | 首目标漫画 | 下界–上界 ms |
| --- | --- | --- | --- | --- | --- |
| 新进程首次① | n25 / 1.619s | n26 / 1.635s | n49 / 2.033s | n50 / 2.086s | 398–467 |
| 同进程暖开② | n34 / 1.624s | n35 / 1.641s | n58 / 2.038s | n59 / 2.089s | 397–465 |
| 新进程首开② | n33 / 1.640s | n34 / 1.656s | n56 / 2.038s | n57 / 2.088s | 382–448 |

沿用旧表人工口径：允许极淡残字、加载指示和页码，不要求全屏纯黑；下界=末近黑−首近黑，上界=首漫画−最后可辨详情。旧记录没有可复用数值阈值，未捏造阈值算法。保留原分辨率PNG、MKV逐帧PTS及边界联系图；实施者判读后主代理独立核对三份边界图。视频无fps重采样，166/176/173帧，PTS严格递增且无B帧。

探针System.nanoTime通过各自Perfetto clock_snapshot映射至BOOTTIME，DOWN/UP分别落入同PID主线程第一/第二条触摸span。所有原始ns、映射offset、PID/request和四阶段算术见[同名JSON](fork-reader-startup-tablet-probe-2026-11-03.json)。

**本轮仍为原MKV协议，容器PTS未与设备时钟建立绝对映射。** 因此没有用MKV归零PTS推算“点击→视频首漫画”的绝对时延；上表输入→FIRST_DRAW只是应用绘制端点。FRAME_COMMIT回调比FIRST_DRAW迟约12.7秒，本轮不确认原因且不以其作首图提交或显示端点。

## 构建、设置与开销边界

- 源码冻结在上述本地主干加本任务诊断改动；构建输入13个源文件SHA及组合指纹 `599bbbaf9e2f3933c1f1f9b0c0cb1e94d748a0edf0ab4791f98e6241604df886` 已与当前源码逐项核对一致，不把旧主干SHA冒称无改动构建。
- 独立包 `app.mihon.desktop.fork.benchmark`，vc134 / `0.19.4-aex.116-benchmark`，benchmark继承release的R8与资源压缩，non-debuggable、profileable。独立Android Debug诊断证书 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`，不冒充正式签名。被测APK SHA256 `ae1e407aa4ae0a912606094cbb8e93991ad3a8f20f65c68c12ad7e55fa4f4fa9`。诊断APK、mapping、badging、signature和artifact.json位于 `app/artifacts/android/diagnostics/fork-reader-tablet-probe-20261103/`。
- 相同史莱姆第04卷CBZ：41,380,343B / 187WebP，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。通过正常系统目录选择器给隔离包授予独立SAF目录，以DownloadProvider规范下载CBZ路径读取；非本地源或提前解压目录。虚构书架条目，不导入官方私有数据库。复制/哈希已预热文件缓存。
- 默认及目标漫画均通过正常UI选择单页从右到左；横图自动放大OFF、阅读模式提示OFF，深色与黑色阅读背景。同一个APK与编译请求策略：仅对隔离包请求 `speed -f`，返回Success，未读取实际compiler filter，保持unknown。
- 正常书架→详情→Volume04；每场输入前确认probe导出不存在，返回后才异步导出。29固定时间槽/32 sessions，默认关闭；热路径无新增文件IO/持锁记录，保留原主干ReaderDiagnostics开销。探针开销和联合采集扰动尚未扣除。
- 复用原scrcpy5.0.1：1920×2880硬件AVC、最高60fps、12Mbps、10秒MKV、无播放/控制/音频，同时12秒32MiB Perfetto。本轮只测单页；双页有诊断接线，但未据此宣称完成双页真机测量。

最初三份LTR记录保留在 `fork-saf-first-warm-*` / `fork-saf-second-process-*`，标为valid-different-mode，排除最终表；私有file路径pilot在reader打开前被系统提示拦住，不作为有效样本。最终只用 `fork-saf-rtl-*`，未混用两组数据或事后扣固定开销。

## 验证与收尾

新增probe行为测试先RED：重配置request隔离与header遍历断言实际失败；最小实现后，最终五项focused（3 recorder、187页真实port接线、真实bitmap/canvas首绘及旧请求拒绝）和受影响Spotless通过，日志 `.gradle-coordinator/fork-reader-probe-final-five.log`。

审查过程中发现原有 `failed metadata retry preserves the confirmed direction of the same encoded stream` 失败，预期FAILED实际PORTRAIT；单方法复验仍红。主代理在未加探针的 `0db8c3ddf37e28e2ca1a895cf21accbeee05a0e0` 独立工作树仅运行同一方法，三次重试同样失败（原行121，新增测试后移到157），确认是主干基线已有失败；保留 `metadata-head-baseline.xml`，未修复/掩盖，也不报告整个类或模块全绿。未运行全模块、全量或正式发布。

本批改动覆盖Android诊断入口、真实下载/归档/遍历/绘制接线、隔离数据准备及其focused测试，文件数较多但均属于同一测量链路；没有修改产品的读取、配对或调度策略。维护用法与失败处理见[诊断说明](../architecture/reader-open-latency-probe.md)。

原始记录根：`.test-tmp/fork-reader-tablet-probe-20261103/`，最终三目录为 `fork-saf-rtl-first-warm-{1,2}`、`fork-saf-rtl-second-process-1`；probe在对应 `*-ui/probe-*.json`。包含MKV、Perfetto、原帧、PTS、设置/授权、APK身份、编译请求、观察及独立复算，不提交个人画面和完整trace。

收尾仅停止隔离包和本任务采集辅助，probe配置已disabled；自有scrcpy/perfetto与远端临时文件清理，保留隔离安装及专用synthetic目录用于复现，官方及fork正式应用不变。没有推送或正式发布。
