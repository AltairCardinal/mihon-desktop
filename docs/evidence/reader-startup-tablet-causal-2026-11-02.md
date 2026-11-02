# 真机阅读器首开：计时副本差异的因果核查

日期：2026-11-02。仅 PCE-W30 / Android 12 真机；不表示 Android fork 或 Desktop 已通过性能验收。

**同一计时 APK 的编译命令干预使首读内部时间减少约 36ms，反向请求后恢复；外部首漫画端点同步反转约 35ms。** 完全无探针源码的优化副本仍与 probe-off 高度重叠，因此本轮没有支持“探针存在使副本整体慢数十毫秒”的证据。另确认测试包开启、官方关闭的横图自动放大，会增加首图后的 500ms 等待和 500ms 缩放；它解释后续视口变化，不是首漫画等待。

## 控制与已证实的贡献

同一真实 CBZ（41,380,343B、187 张 WebP，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`），正常书架→详情→第04卷→1/187。首读是新进程首次 reader，不是冷磁盘。benchmark 同 UID 10196、数据库、独立 SAF 目录和设置，原位切换 APK；官方 APK、偏好和 ART 配置均未修改。

本轮本地产物：[无探针 arm64 APK](D:/Shell/Github/mihon/.test-tmp/slime-tablet-causal-20261102/apks/pristine-base-arm64.apk)、[稀疏探针 arm64 APK](D:/Shell/Github/mihon/.test-tmp/slime-tablet-causal-20261102/apks/sparse-25-arm64.apk)，均为独立 benchmark 调查包。

- **无探针源码控制**：上游 `7a917968e3bf71c4a665e6655a550877d81ead1d` 原封不动构建原有 release 派生 benchmark，APK `b520f24c7e3797b95a31649dd586367a7e93347219eee550c5c96bb933316bdb`。与稀疏 APK `52be41958a884e24fac9ef05a46cd06d59a1e44344581e604569f879b90d813b` 的 off 状态按 S/P/P/S/S/P 交错，各三首三暖；两组分布重叠，没有复现官方速度差来自源码中探针的证据。off 不等于无探针构建。
- **编译干预**：只对 benchmark 执行 `cmd package compile -m verify/speed/verify -f app.mihon.benchmark`；不重装、不清 profile/数据，其他条件固定。三次均返回 Success，但 OEM 不公开 filter/reason，不能把请求确认冒充 filter 读回，更不能声称已知官方 filter。下表是一次 V/S/V 反转，不是 P95。

| 请求顺序 | UP→FIRST_DRAW，首/暖 ms | DOWN→首目标 reader 漫画像素，首 ms | DOWN→performCreate 结束，首 ms |
| --- | ---: | ---: | ---: |
| verify ① | 316.228 / 270.499 | 340.794 | 62.529 |
| speed | 280.156 / 227.883 | 306.069 | 54.999 |
| verify ② | 317.375 / 319.213 | 341.789 | 63.529 |

首读内部改善 36.072ms、恢复后回升 37.219ms；外部改善 34.725ms、恢复后回升 35.720ms。创建结束只贡献约 8ms，后续列表到当前页调用等阶段也改变，不能把全部差值归给 Activity 创建或 SAF。暖态回升方向一致但幅度波动大。

官方本轮两次首读的首目标漫画部分入场为 324.668/307.302ms，完整横封面为 344.210/326.938ms；speed-on 为 306.069ms 部分入场、324.044ms 完整横封面。**保留探针的副本可以接近官方同口径结果**；这些联合采集数据不是官方无采集日常时长。部分入场与完整封面不混比，详情旧封面不计 reader 目标内容。

- **设置差异**：正常 UI 截图确认两者均“适应屏幕/自动位置”，官方横图自动放大 OFF，benchmark ON。只将 benchmark 改 OFF 两首暖，再恢复 ON 一首暖；生产代码明确等待 500ms 后执行 500ms 动画。首图后的缩放应与首图时间分别记录。最终 benchmark 将横图自动放大、阅读模式提示均置 OFF，和官方这两项对齐；最终同时对齐不作单因素归因。
- **探针增量校验**：同 APK sparse on/off 按 ABBA 各两首两暖，另在 speed 请求策略下匹配 off 首暖；未出现能解释原先数十毫秒差距的稳定探针增量，样本少、视频帧离散，不能声称零开销。legacy 记录器的 draw/stop 导出各一首暖另列，不据小样本给出固定扣减常数。

## 正确内部探针与最终四段

旧包配置 `export=stop`，输入前文件不存在，返回前却已出现，真实行为 RED。新包四次正常 UI 打开及后续完整断言确认：返回前无文件，onStop 后有完整目标 chapter/page/request 事件，GREEN。稀疏模式配置阶段预分配槽；事件路径无锁、事件对象分配、热点 I/O 或导出调度，onStop 后才快照导出。没有改变 dispatcher、await 或原 reader 链路。

最终主内部数据使用同一稀疏 APK、最近一次 speed 请求、上述最终设置，**无视频、无 Perfetto、probe 开启**：

| 段，ms | 首读 | 同进程暖态 |
| --- | ---: | ---: |
| ViewModel init→页列表 Ready | 89.960 | 85.985 |
| 页列表 Ready→当前页 streamFn 调用前 | 18.434 | 16.870 |
| streamFn 调用前→SSIV decode submit | 6.532 | 7.328 |
| submit→FIRST_DRAW | 67.395 | 67.392 |
| init→FIRST_DRAW（原 ns 求差） | 182.322 | 177.575 |
| Main TOUCH_UP→FIRST_DRAW | 219.688 | 211.164 |

首读第一段中，下载判断/loader 选择 29.015ms、第二次路径查找 22.334ms、archive open 19.030ms、原 entry 列举/排序 15.474ms。它们是第一段内不重叠子段，尚有 isFile 等间隙，不能再与父段相加；不是整章解码。初始无视频四次也确认 DB/source/章节列表仅约 2–3ms，不能把整个约 100ms 叫作数据库或调度等待。provider 全窗口 IPC 数官方反而更多，未支持“benchmark 多做查询”的简单解释，未据此修改路径。

`DECODE_SUBMIT` 不是 native decoder 开始；`FIRST_DRAW` 不是物理呈现，FRAME_COMMIT 回调也不是可靠首像素 fence。最终 219.688ms 与早先联合采集 280.156ms 同时存在设置和轮次差异，不能把差值全算编码开销或拿来校正官方时间。

## 验证、来源与边界

focused JVM 先 RED 后 5/5 PASS；真实导出 RED/GREEN、各样本 1/187、安装 SHA/PID、目标 request/page、完整事件与顺序、输入前无残留均核对。`:app:spotlessKotlinCheck` 通过，`git diff --check` 通过；未运行全量测试、模拟器或重建最终 APK。

仅固定 page0、单 Reader 逐次退出再开；一次配置 32 sessions，本 harness 每进程只创建一次 Main。Main 重建、快速切章、Reader 生命周期重叠不在此探针契约内。阶段 CPU/IO 不混算，记录器未保存每个 marker 的实际 TID；系统 input/performCreate 保留 PID/TID。无采集官方内部四段仍未取得，不外推本副本比例。

源码位于 [隔离探针工作树](D:/Codex/worktrees/mihon-upstream-open-latency-20261102/docs/reader-open-latency-investigation/TABLET_CAUSAL_METHOD.md)；本任务源码提交 `ccb733763184ef01e4c7120f4b5941f7783c2b54`，构建源指纹见[精简 JSON](reader-startup-tablet-causal-2026-11-02.json)。APK 由 `79ea4f455644cc1ab98e35ad176e4e34b150fa38` 加所列源文件构建；构建后仅 ReaderActivity KDoc 归位，逻辑未变，保留构建时及提交时哈希。

逐次原 ns、条件、编译请求、自动候选及人工边界勘误见同名 JSON。自动 ROI 漏报不剔除、不伪补；早期焦点/章节定位失败在 reader 输入前退出，另列排除理由。原始视频、个人 trace、设置截图及原件只在忽略目录 `.test-tmp/slime-tablet-causal-20261102/`，不提交；关键帧以保留源 PTS、同 boot 时钟 helper 前后夹取复核，不表示物理扫描呈现。

收尾：所有自有采集辅助结束，benchmark 停止；官方 APK 哈希仍为 `7a7969564a604bf74608758b540730e53dfe63e11782a718d32380dc9febed58`，已回目标详情且第04卷可见。没有修改官方偏好/编译状态或推送远端，没有宣布双端性能达标。
