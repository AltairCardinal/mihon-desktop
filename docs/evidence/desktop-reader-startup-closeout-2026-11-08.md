# Windows 下载章节首开成果与研究收口

日期 2026-11-08。用户授权本轮再做一次实现审查、修复发现的问题、整合推送主干并清理对应研究分支。本次结束已有实现的交付与材料保全，后续方向保留为研究任务。

## 采用范围

| 成果 | 原始提交 | 本轮主干提交 |
|---|---|---|
| 包内 miniz CBZ 后端、真实下载路由与启动期库加载 | f2928b7e1c | d4984a518b |
| 数字页身份兼容、ComicInfo 图片识别与目录分配上界 | 73fb2590d8 | 55839fd9b1 |
| 轻 entry、同一个 Queued holder 到完整阅读器交接 | 44edd09def | d2bcff2591 |
| 完整模型最终入口非阻塞复读一次页表 | a51d1a6a72 | e530a715cd |

合并基线为实际远端 main 6eb3f67ba99b9cb9fc7cb047cdd9845c6d4a993d。该基线已有 Android 双页快翻和 Desktop 类别拖拽；定向整合没有删除或回退这些成果。工作区中的 Android/扩展未提交内容属于其他任务，本轮没有暂存。

9ce851d69c 的同步 Ready 补接与专用 work/capture 诊断留在研究材料中。其合法时序真实红绿通过，但两次副屏实际打开未触发，未取得性能净收益，本次产品整合仅止于 a51d1a6a72。

## 已测结果与限制

2026-11-09源码调查补充了一项测量前提：下述Windows记录使用的隔离PreferencesFactory在每次get时重新读取整份properties文件，普通Windows启动使用注册表后端。原A/B继续描述相同隔离环境中的结果；普通启动的绝对差距及准入命中率尚未由这些数据确认。新的函数链、库实现与修正后的研究优先级见[源码调查](desktop-reader-call-chain-source-analysis-2026-11-09.md)。

同一《史莱姆》第 04 卷第 5/187 页地图、单页 RTL。归档优化后两个新进程为 6.873 / 7.365 ms，低于上游探针 19.620 ms。最终复读成功时，页表到真实目标 holder 构造为 19.196 / 21.817 ms，实际挂载 40.831 / 44.377 ms。关闭细观测的正反窗口对照中，地图首见可确认提前区间分别为 16.86–50.19 和 7.12–40.45 ms。

这些是少量命中样本。副屏 DISPLAY2、96 DPI、1024×768 两个后续新进程都在页表到来前约 4 ms 决定回退，目标 holder 构造变成页表后 153.475 / 122.940 ms。因此采用的是可正确回退的条件优化，没有达成稳定追平原版。近黑已测区间仍为约 150–233 ms，60 ms 工程预算未通过。窗口捕获未观测显示面板扫描。各端输入、应用绘出与外部首见的边界详见[逐轮实测](desktop-reader-first-compose-2026-11-08.md)及[D10记录](desktop-reader-d10-2026-11-07.md)。

## 一次独立实现审查与整合验证

独立 Sol medium 未参与这些实现，本轮只读审查固定 a51d1a6a72 采用范围。审查覆盖 native 兼容回退、目录内存预算、CRC、取消/清理/租约、页身份，轻 entry 单次释放、同 holder 绑定/交接、早期手势、真实绘制进度、章节入口和原链 fallback。未发现可确认的阻塞缺陷；本次没有额外行为修补。

整合后的主干经 gradle-coordinator 运行 12 类 focused，key 为 reader-startup-closeout。104 项中 102 通过、2 个依赖外部原档环境变量的整卷诊断跳过，0 失败/错误。两个整卷诊断已有旧轮同字节原档的187/187实测，不将复用证据称作本轮重新执行。实际执行的 miniz 用例加载真实本机 DLL，包含页身份、真实内容字节、回退、CRC、租约与目录预算；首次组合与相关章节接线在真实 Screen/session/Compose 上执行。耗时 2m33s。

37 个采用的 app-desktop/构建脚本文件经 Git blob 逐项比对与审查固定 a51d1a6a72 完全一致。生产整合使用审查固定提交，修改行格式检查沿用原轮零诊断结果；主干非 vendor 差异的 git diff --check 通过。上游 vendor 的 CRLF 和 miniz.h 原始空白保持原字节，未做无关格式化。本轮未执行完整模块/全量测试、Android测试、正式发布或另建预览包。

## 研究方向

1. 优先消除一次性准入的时序依赖。限定下载单页，页表真实到来后用既有轻量视口挂载当前页及提交需求，实际 holder apply 后交接完整模型。先用延迟页表、Failed、旧代、退出和早翻页验证生产接线；随后副屏上以新控制/候选正反对照决定采用。不能通过固定等待、提前打开目标档或占位构造满足预算。
2. 在准入命中稳定后再诊断目标需求到 Ready 的队列。副屏两份实测目标 work 在页表后89.517–109.723 ms才被poll，poll到Ready发布约12.524–13.620 ms。先区分需求何时产生、队列/线程派发与状态消费，使用真实page/request epoch/id/attempt关联，不能把前段跨度全部算成可回收时间。
3. 以近黑时长继续约束过渡呈现。对同一正确目标页，分别记录响应R、漫画首见F、连续/累计近黑K；不能只延长旧页面或提前背景帧就宣布加载加速。现有数据不足以承诺下一轮毫秒收益。

研究重启需以本页采用边界为起点，恢复必要原件后再建立一个可失败的真实行为测试。本轮不自动继续上述实现。

## 原件保全与清理边界

Git 外归档位于 D:/Codex/archives/mihon-reader-startup-20261108/，入口为 README.md 与 manifest.json。7 个证据 ZIP 已校验 CRC、成员数、原始总字节数，并由主协调者复核包 SHA-256。覆盖 D9 方法原件、D10 与三个首次组合轮次、必要 profile DB/偏好、真实 RED/green XML、协调器日志、本次主干12份新XML、8份预览 manifest，以及去重后的规范 CBZ 与 005.webp。重复下载档、encoded 缓存和可重建的预览应用不保留为研究数据。

源码使用 research-heads.bundle 保存以下5个原始 HEAD，bundle 为204528字节，SHA-256为4d811c428ef64f5823b0e7542a98a1343eef29e000a370475cad91c185e44d4a。verify与实际unbundle均通过，依赖对象都在6eb3f67ba99b9cb9fc7cb047cdd9845c6d4a993d及后续main历史中；恢复命令见归档README。整个去重归档约98 MiB。数据和漫画原图保持Git外存储。

| 拟清理工作树名 | 原始HEAD |
|---|---|
| mihon-reader-first-compose-20261108 |9ce851d69cf4c13931dad3bd6c2d95ef6b29ad40|
| mihon-main-reader-delivery-20261106 |ea697035abc8d453e751227c948f773b722ac5b2|
| mihon-reader-single-first-slot-20261107 |3b2040111964d4cbedca029269ada10e4a09959b|
| mihon-cbz-d10-archive-20261107 |f2928b7e1cd3f6b6f4ed6aa3f5a4c5e607c2905e|
| mihon-cbz-d10-control-20261107 |815ddc028993ff2237abd8ff486acdd2a1350a80|

对应临时分支为 codex/reader-first-compose-20261108、local/windows-cbz-plan-20261107、codex/reader-single-first-slot-20261107。清理仅在远端main包含本次固定提交、归档可验证且源树Git干净后执行。主干工作树、Android研究及其他功能任务的工作树/分支不在这份清单中。历史工作树路径需通过归档manifest的oldRoot_to_archive_prefix映射定位原件。
