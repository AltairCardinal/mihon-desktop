# 下载章节启动：源视频时钟与GPU对照

日期：2026-11-02。本轮是性能调查，不改变产品行为，不表示候选通过体验验收。

## 方法与时钟边界

保留官方scrcpy 5.0.1 server未修改，以video-only TCP连接逐包保存12字节大端header与原H264。禁用device/stream/dummy元数据，保留frame metadata；bit62为codec config、bit61为keyframe、低61位为微秒PTS。config包保留但不算图像。常规scrcpy recorder会减去pts_origin，本轮直接保留原PTS，不使用归零容器或主机到达时间。

源码链：scrcpy [Streamer](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java)保存MediaCodec presentationTimeUs；[OpenGLRunner](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/opengl/OpenGLRunner.java)把SurfaceTexture timestamp传入eglPresentationTimeANDROID；AOSP [VirtualDisplaySurface](https://android.googlesource.com/platform/frameworks/native/+/171cac1b603e4bb83412eb596d05a500af5d7a76/services/surfaceflinger/DisplayHardware/VirtualDisplaySurface.cpp)使用systemTime，默认MONOTONIC。该链提供时钟domain依据，仍核验实际帧、端点及顺序，不把编码收到时间当采样时间。

测前、测后运行独立shell ClockSample：BOOTTIME-before、MONOTONIC、BOOTTIME-after连续采样16次，并保存boot_id。每行offset区间为[before−mono, after−mono]；用前后端点包络，不做延迟拟合或线性插值。与Perfetto ClockSnapshot交叉核对；API31部分trace只有起点快照，不能单靠它覆盖视频，必须使用helper后端点。仅在同boot、端点夹住完整源PTS窗口且offset稳定时关联trace。

误差分开报告：源PTS单位1µs，转换保留±1µs；时钟helper/快照的实测区间和残差；相邻原始帧之间的采样间隔。采集管线偏差仍未知，不纳入虚假的固定误差常数。所有数值都是**源视频捕获到漫画像素**的时间关联，不证明物理屏幕扫描呈现。

每份逐包复算PTS/flags/size并比对原metadata，payload拼接SHA256与H264一致。解码帧数必须等于非config包数、无B帧重排、源PTS严格递增后，才按帧序号关联原PTS。中心ROI的limited-range YAVG<17仅用于近黑候选；原始联系图人工确认首个漫画像素，部分入场与整页进入另记。不把Reader TX窗口帧或SSIV FIRST_DRAW当漫画已被捕获。

## 实验控制与限制

同一41,380,343B原CBZ，SHA256 b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677，187张WebP，正常Main→Library→详情→章节，第1/187页；未解压为目录。cold指新应用进程的首次reader，不是磁盘冷；平板复制/哈希已预热相应文件；AVD未清宿主缓存，重启后的guest缓存条件不同，未控制物理磁盘冷态。

官方AVD APK为GitHub v0.19.9发行的native x86_64，SHA256 3eb31a91d4ff24bbb0ce5007e757d83a22b5ba96bc9ef89a1eaac07e7d66ad0b；官方平板arm64 APK SHA256 7a7969564a604bf74608758b540730e53dfe63e11782a718d32380dc9febed58，安装原件保持不变。benchmark为既有优化APK，包名/签名独立，运行时off/on；没有重构建产品。

AVD官方沿用上一轮自建fixture DB/source610004、私有file URI下的canonical CBZ，未导入平板用户数据库；GPU组内一致，与真机存储/元数据仍不同。AVD API36 x86_64，2vCPU，1080×2400，实测MemTotal 2,532,300kB（镜像把1536MiB配置提高至2560MB）。GPU按SwiftShader A1→host B→SwiftShader A2顺序，实际renderer分别为Google SwiftShader与NVIDIA RTX3070/OpenGL ES3.1；不是仅凭启动参数。A1启动后较久才采集，B/A2是新boot，保留输入时uptime和既有trace调度负载，不能排除启动后台工作。每组只一冷一暖，不能由ABA少量差值给GPU作充分因果归因。

平板PCE-W30/API31：benchmark先私有file URI pilot，再正常系统文件夹选择器授权独立shared目录MihonLatencySlime20261102，按canonical下载目录复制同CBZ，校验字节；之后不再运行会重置私有路径的prep。UI对齐深色、RTL单页、黑背景、系统方向。官方UI只读确认/storage/emulated/0/Tachiyomi；未读取其私有URI字符串，不声称scheme逐字相同。benchmark漫画入口没有thumbnailUrl、显示占位封面，官方为真实封面；章节列表规模、用户库/扩展/source元数据、签名、ART profile仍不同，off/on与官方差值不能直接归因探针。

## 结果

| 对象 | 首次捕获漫画ms〔相邻帧区间〕 | 暖态捕获漫画ms〔相邻帧区间〕 |
|---|---:|---:|
| 真机官方 | 284.276〔267.502–284.278〕 | 289.347〔273.077–289.348〕 |
| 真机benchmark shared/off | 337.861〔319.504–337.863〕 | 316.652〔300.078–316.653〕 |
| 真机benchmark shared/on | 366.247〔332.026–366.249〕 | 308.576〔289.689–308.577〕 |
| 官方AVD SwiftShader A1 | 996.187〔859.929–996.188〕 | 665.992〔631.746–665.994〕 |
| 官方AVD NVIDIA host B | 2452.517〔2431.101–2452.518〕 | 2132.702〔2065.090–2132.703〕 |
| 官方AVD SwiftShader A2 | 1442.671〔1376.798–1442.672〕 | 745.179〔695.679–745.181〕 |
| 官方AVD transition scale=0 | 665.202〔636.314–665.203〕 | 704.450〔685.234–704.451〕 |

真机官方本轮的**中心ROI近黑候选区间**为16.601–49.832ms、34.154–68.668ms；亮度阈值不能证明全屏严格黑或物理黑屏。约284/289ms的捕获首图与短近黑间隔可同时存在；这是采集开启条件下的结果，不是无采集日常耗时。官方未修改APK的内部四段仍未直接测得，既有产品门槛不放宽。

本轮host配置加联合采集没有改善，反而更慢；A1/B/A2跨boot、输入时uptime分别1211.55/76.81/88.68s，不能把差值全归GPU。前1s/后2s的非idle Running CPU总计3705.867/4345.439/3638.311 CPU-ms，已排除utid/tid=0及swapper；该窗口包含采集，不是无采集后台基线。

暖态转场对照：A2默认首漫画745.179ms、近黑360.256–445.645ms；仅transition scale=0时首漫画704.450ms、近黑463.966–499.953ms。该样本首图略早而无内容间隔更长，支持区分旧页保留/淡出与图像准备；只有一对，不能一般化。scale已恢复1.0并读回，其他scale保持原值。

### 同一样本的内部探针与捕获端点

四段是init→列表、列表→调用当前页streamFn之前、该调用点→DECODE_SUBMIT、submit→FIRST_DRAW；OPEN_PAGE记录在调用当前页streamFn之前，不表示open/read完成，submit也不是底层解码开始。以下draw/commit/捕获均相对同一个主线程DOWN，不拼接不同样本。

| 样本 | 四段ms | init→FIRST_DRAW合计ms | FIRST_DRAW ms | FRAME_COMMIT回调 ms | 视频首漫画ms |
|---|---|---:|---:|---:|---:|
| tablet-shared-on-first | 120.584 / 46.122 / 12.257 / 93.337 | 272.301 | 327.603 | 345.312 | 366.247 |
| tablet-shared-on-warm | 115.536 / 34.460 / 6.061 / 85.645 | 241.703 | 287.957 | 306.593 | 308.576 |
| overhead-joint-first | 696.542 / 31.268 / 4.918 / 139.124 | 871.853 | 964.638 | 1006.622 | 1020.323 |
| overhead-joint-warm | 32.758 / 273.868 / 7.369 / 254.778 | 568.773 | 603.961 | 1111.328 | 623.954 |
| overhead-trace-only-first | 14.601 / 157.556 / 67.533 / 113.262 | 352.953 | 401.744 | 468.927 | —（未录视频） |
| overhead-trace-only-warm | 7.899 / 83.314 / 83.152 / 112.675 | 287.040 | 310.798 | 335.134 | —（未录视频） |

同probe-on APK在AVD的joint FIRST_DRAW为964.638/603.961ms，trace-only为401.744/310.798ms。联合采集有扰动嫌疑：同DOWN→draw窗内encoder Running CPU为407.986/342.500ms，app主线程Runnable等待185.149/232.170ms；trace-only无该encoder CPU，主线程等待87.028/58.539ms。固定顺序、单冷暖对和不同窗口仍有混杂，不能给每次结果固定扣除563/293ms，也不能声称codec已被证明是唯一原因。

反例：joint warm的视频首漫画623.954ms，早于FRAME_COMMIT回调1111.328ms；回调执行可能滞后，不能当首图present fence。shared on warm的首漫画308.576ms是部分入场，323.549ms才整页进入；A2 warm首帧也部分入场。FIRST_DRAW与视频捕获均不证明物理屏幕扫描。

上述漫画首帧已逐张目视确认；root独立复核了shared4、官方真机2及多份关键GPU/转场帧，其余由Luna离线目视核验。精简原ns/PTS/时钟区间/四段数据见[同名JSON](reader-startup-clock-gpu-2026-11-02.json)。原件在.test-tmp/slime-clock-gpu-20261102；有效正式联系图为contact-aligned-<sample>/transition-native-source-pts.png，shared四份沿用已核验原图。初次错包名派生图已明确作废，不能使用样本根目录的旧图；原始采集未受影响。私有路径pilot、无输入预检失败及A1尾部wrapper超时恢复记录全部保留。

所有自有辅助已结束，官方平板回详情；专属AVD已精确关闭。没有改变官方平板配置/数据目录或APK；正常阅读会自然记录进度。

## 复现与最小验证

通用解析器scripts/reader_video_clock.py被本轮实际collector及离线分析直接导入；可流式回放原包：`python scripts/reader_video_clock.py video.packets headers.jsonl --offset-low-ns LOW --offset-high-ns HIGH`。输出路径须不存在。offset必须来自相同设备/boot的有效采样，工具不会替调用者证明时钟domain。

使用现有Android SDK把scripts/reader-clock/ClockSample.java以javac的Android平台jar classpath编译，再用build-tools的d8转换为classes.dex；推到独立/data/local/tmp路径，以`CLASSPATH=... app_process / ClockSample`运行，测量结束删除。它只读取时钟，不安装/附着应用。先无输入预检，再采集；禁止绕过锁屏或用主机命令耗时校准。

验证：`python scripts/tests/reader-video-clock-test.py`，覆盖短读、config、原PTS/keyframe、截断payload、长度上限、异常session header、时钟区间、CLI流式输出及不覆盖既有文件。本轮真实raw逐包回放22份；分析入口错package实际负例RED→GREEN，显式包名与capture.json不一致在写派生文件前失败。没有Gradle、全量测试或产品行为修改。
