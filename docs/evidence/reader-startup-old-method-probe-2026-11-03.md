# 按旧表方法复测：上游埋点构建

日期：2026-11-03。在同一 PCE-W30 / Android 12 真机，探针开启，按[旧表](reader-startup-performance-baseline-2026-11-02.md)的端点和“新进程首①／同进程暖／新进程首②”顺序取得三份有效样本。没有重建、安装或执行 compile；官方 APK 未动。

## 旧官方与本轮结果

单位均为毫秒；每格顺序为 **首①／暖／首②**。

| 旧表指标 | 旧官方安装版（历史记录） | 本轮上游埋点构建（probe on） |
| --- | --- | --- |
| 输入送达应用→系统 StartActivity 开始 | 21.156／20.774／21.811 | 26.620／22.702／24.015 |
| 输入送达应用→performCreate 开始 | 38.223／34.990／37.161 | 43.328／38.602／39.901 |
| 输入送达应用→Reader 关联图层首 TX 帧完成 | 79.670／73.461／82.282 | 88.815／72.897／87.339 |
| 视频近黑持续时间的采样界限 | 16–51／18–54／0–33 | 19–54／0–36／0–36 |

本轮首次 Reader TX 帧约 87–89ms，暖态约 73ms；这是旧表的窗口事务帧代理值，不是 FIRST_DRAW 或首漫画端点。近黑是上下界，不是精确持续时间。两组非同期，且包身份、元数据和运行状态仍有差异，不把表中差值当作探针净开销，也不据此宣布整体阅读器或双端性能达标。

## 方法保持与四边界

复用旧 `capture-original.py`，只适配 benchmark 包名、旧工具的绝对路径以及身份/焦点保护。仍使用官方 scrcpy 5.0.1：1920×2880 硬件 AVC、最高 60fps、12Mbps、MKV 录制、无播放/控制/音频；与 32MiB、12 秒 Perfetto 同时运行。保留原生录制帧 PTS，没有重采样，没有换成后续 raw-source-PTS/helper 协议。

系统起点为本次设备 uptime 输入命令区间内，benchmark 主线程首个 `deliverInputEvent src=0x1002`；匹配本次 Reader 的 StartActivity、performCreate 与 TX layer，TX 完成时间为该帧 `ts+dur`。视频仍独立计时，**未将 MKV 归零 PTS 与系统 ns 对齐**，不生成“输入→首漫画”的绝对值。

| 样本 | 最后残余详情 | 首近黑 | 末近黑 | 首蓝色目标漫画 | 下界／上界 |
| --- | --- | --- | --- | --- | --- |
| 首① | n26，1.656s | n27，1.674s | n28，1.693s | n29，1.710s | 19／54ms |
| 暖 | n33，1.631s | n34，1.648s | n34，1.648s | n35，1.667s | 0／36ms |
| 首② | n30，1.656s | n31，1.677s | n31，1.677s | n32，1.692s | 0／36ms |

与旧报告同语义人工判读：不以顶栏、页码代替内容，不要求全屏严格纯黑，允许 faint 残字/编码噪声；旧记录未保存可复用的数值阈值/ROI，不虚构阈值算法。下界=末近黑−首近黑，上界=首漫画−最后残余详情；只有一张近黑帧时下界保留 0。原尺寸关键帧已和联系图独立复核。

## 身份、验证与原件

复用已装 `app.mihon.benchmark` 0.19.9/code22，APK SHA256 `52be41958a884e24fac9ef05a46cd06d59a1e44344581e604569f879b90d813b`；源及构建关系见[上轮因果记录](reader-startup-tablet-causal-2026-11-02.md)。当前 `enabled=true`、sparse、stop，目标 manga/chapter=2、page0；正常 UI 确认横图自动放大和阅读模式提示均 OFF，未改变偏好。历史最后一次编译请求为 speed，但本轮未再执行，当前实际 filter 未读取，不能冒称已知。

同一史莱姆第04卷 CBZ：41,380,343B、187 张 WebP，实读 SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`，三次均 1/187。首①/暖 PID 20518，首② PID 20522；均经正常书架→详情→章节，未提前打开 reader。文件哈希核验使文件缓存已暖，不能称冷磁盘。

局部验证：适配脚本语法通过；三份采集成功，安装 SHA/config/页码/PID/请求与无残留断言通过；原生视频帧 PTS 严格递增；系统指标由独立只读核对，近黑由原尺寸帧独立目视核对。仅新增本报告和[精简 JSON](reader-startup-old-method-probe-2026-11-03.json)，不运行产品测试、全量测试或新实验。

旧官方原件为 `.test-tmp/upstream-original-trace-20261102/{cold1_verified,warm1,cold2}`；本轮原件为 `.test-tmp/reader-old-table-probe-20261103/{old-table-p1-1,old-table-p1-2,old-table-p2-1}`，包含 MKV、trace、SQL/输出、native PTS、`transition-native.png` 和 `keyframes-native`。这些个人画面/完整 trace 不提交。收尾 benchmark 已停，自有录制/追踪进程为空，设备临时副本已清理；没有推送。
