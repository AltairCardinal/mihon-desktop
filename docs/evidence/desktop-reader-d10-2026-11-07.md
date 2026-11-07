# D10 Windows 下载 CBZ 与首次单页视口

## 当前边界

本页随 [执行路线](../roadmap/2026-11-07-windows-reader-cbz-first-page.md)维护。本轮按计划收口：采用仅归档B，联合C的两阶段容器及专用诊断全部撤回。最终A/B、B/A、两个B内部首次及B双页往返均已完成；归档严格目标达到，holder与整体体验目标未达到。以下保留实施/采样时的历史证据，最终判定见末节。只做 Desktop focused 测试、普通 preview 与指定窗口采样；不全量、不 Android 构建、不正式发布、不推送，公共能力说明保持原样。

## 固定输入与源码

- 指定工作树 `D:/Codex/worktrees/mihon-main-reader-delivery-20261106`，开始时分支 `local/windows-cbz-plan-20261107`、HEAD `f67c1d5edf9c5a03ae288d31f1231e8d1c31a3fc`，工作树干净。
- 本地 main 后续为 `851081066770d135fff50f04f67249080c4771bd`，相对计划基线只有文档变化；隔离分支合入得到 `815ddc028993ff2237abd8ff486acdd2a1350a80`。产品代码与 `ae5bb3bb5aca9e84e6616a1183129e6f0a25e77b` 相同。main 在另一工作树上使用，本轮继续隔离，未改其分支或工作目录。
- 控制快照 `D:/Codex/worktrees/mihon-cbz-d10-control-20261107` 固定于 `815ddc0289`；控制和候选不能引用外置归档 agent 或通用反射预热。
- 本机真实 CBZ SHA-256 实际核对为 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。不提交漫画、截图或视频；目标是 index 4 地图，187 页，单页 RTL。
- 原型 ZIP SHA-256 实际核对为 `716d54e3515b33a1dd8afe06252879556acb8837204b215365a688c9757f6733`，容器参考 `904bc8d34308`、`3b2040111964`。只移植有关组件差异，保留当前章节按钮与阅读流的独立回调。

## 真实行为红绿

本机协调器日志位于 `.gradle-coordinator/`；原件可能不随克隆分发。以下没有将编译失败作为行为红测。

| 范围 | 红测事实 | 绿测与结果 |
|---|---|---|
| 首屏 Queued holder | `d10-slot-red2`：真实 Compose 第 5 页排队时 `HOLDER_CREATE_BEGIN` 期望 1、实际 0 | `d10-slot-green`：同对象 Ready、Skia 实际绘出、movable content 交给分页器、退出关闭通过 |
| 正常 ReaderContent 及早翻页 | `d10-slot-navigation-red`：用户转到第 6 页后仍留在 startup viewport | `d10-slot-lifecycle-green`：下载入口限定、早翻页交接、迟到旧 Ready 不读图、关闭/换代/重试撤销、绑定幂等通过 |
| 相邻章节与键盘 | 复用当前 production wiring | 同一 focused 轮 36 项全部通过：StartupSingleViewport 2、PresentationImageOwner 13、ChapterTransitionIntegration 15、SinglePagePresentationIdentity 3、KeyboardNavigationPosition 3 |
| 下载归档后端 | `d10-miniz-red`：真实下载路由持有 CommonsZipArchiveLease，未命中拟接入的 miniz | `d10-miniz-boundaries2`：归档 53/53 通过；`d10-miniz-ui-draw-green`：原生 13/13 与真实 UI draw 1/1 通过 |

Windows 首次 `./gradlew.bat` 写法被协调器传给 cmd 后未启动 Gradle，退出 1；改为 `gradlew.bat` 后正常执行。该准备错误没有计为产品红测。

## 归档组件证据与审查

`miniz/` 的 11 份构建源码/头文件逐字节等于已校验实验 ZIP，许可证随源保存。JNI 注册表 ID、共享句柄所有权、元数据上限、原条目次序/带符号 CRC 指纹、流式写入/CRC、取消和回退经一轮独立审查；未发现 native 主体确定阻塞。审查提出的首次界面边界由主协调者独立核对后收口：不能用 `withFrameNanos` 证明 draw 已发生；真实 Compose 红测抑制三帧 `drawContent` 仍观察到旧代码启动加载，改为真实内容 draw 完成后幂等通知，Preparing/Failed 不触发。它不证明物理屏幕扫描已经完成。

13 项原生用例包括真实下载端口、非零 lease generation、未开始/加载中/不可用回退、非支持格式、65 MiB 流式页、CRC/写失败、等长同 mtime 替换、在途 release、旧 handle/index 拒绝与取消清理。一次整卷原生/旧后端逐页 SHA 对照 **187/187** 一致，共 **41,348,502** 字节；图像 SHA 链 `f8e27dfef45554ae098e3d9e0ba8612763275d6bd0c7e527d8dca45079255bac`，地图 SHA `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`。后续窗口计时不重复解整卷。

本机构建 DLL 266,752 字节、x64，SHA-256 `ba0d60bf027f23ebe3c60626f70e19d94a3662a7c25c8b0e82090a4b63bc1666`，仅依赖 bcrypt/KERNEL32，静态 MSVC CRT。构建清单含本次源码、脚本、工具链、pin、ABI 与 DLL 摘要；缺 DLL 回退测试不等于打包通过，包内位置与正常窗口命中仍待下节。

首次边界轮有两项额外失败并已说明：回退格式误以显式 SevenZip 链作为默认 Commons 的对照，改为实际默认选择链后通过；真实 HTTP retry 夹具在后台镜像发布 raw pages 时立即取 viewport，尚未等同代派生身份/位置，等待同代位置后通过。后者没有挂载 Compose，不能归因首屏组件；本次不改模型生产行为。整卷与最终 XML 留在 `.test-tmp/d10-miniz-final-xml`，归档集成 XML 在 `.test-tmp/d10-miniz-boundaries2-xml`。

格式检查：本任务新增文件与修改行按 ktlint 1.8.0 检查；非 vendor 的 `git diff --check` 通过。上游原样 miniz.h:577 自带行尾空格，保留该固定输入，不将其改成产品修补；局部 Git 属性同时保留原行尾。

该批超过通常文件/行数估算的原因是 vendored miniz 及 JNI/构建/租约/接线必须作为一个可重建单元审查；没有拆出第二套归档状态系统或持久化索引。

## 首屏容器的实现边界（实验候选，最终撤回）

仅已下载、分页单页、无自动拆页、首次显示使用轻量视口；双页及条漫沿原链。Queued holder 已注册 owner/page/generation/attempt/split/bounds 身份，没有 encodedRef 时不创建 region、不 acquire。Ready 经 owner 核对当前代次后一次绑定真实 decodeKey，重复相同绑定不再创建 region；已绑定的不同内容键拒绝。

同一 registry 的 movable content 在首绘后转入完整分页器，保留真实 holder。用户在首绘前改变页码、选择跨章过渡或换模式，也永久交给本代完整分页器；不在旧 Ready 到达后返回首屏。活动指针从 Press 开始保留轻量视口，Ready 首绘不能在 Release 前卸载手势 owner；释放时提交 RTL/LTR 横拖目标后再交接，取消和跨代均撤销。旧 slot 关闭、owner 换代或 attempt 推进均拒绝晚到绑定。实际绘出才报告进度。此处先记录候选行为，采用与否须由下面的产品样本决定。

独立审查先发现 Queued 横拖缺少导航，再发现活动拖动跨 Ready 首绘的交接竞态。两者均有真实 Touch 红测，第二个红测为 `First draw cannot unmount the owner of an active input gesture`。修复后的 `d10-miniz-ui-draw-green` 包含 StartupSingleViewport 3/3；主协调者核对活动手势从 Press 至 Release/取消的 lifetime、page/generation key 与固定首屏目标。最后 `d10-slot-final-focused2` 40 项通过；仅格式收尾后的 owner/observation 15 项在 `d10-format-focused` 通过，没有重复原生整卷验证。新文件 ktlint 及当前 UI/owner/session 修改行零诊断；既有文件的基线格式警告不扩散修整。

## 冻结预览与窗口前准备

| 臂 | 源码与性质 | 实际 preview manifest |
|---|---|---|
| A 默认控制 | 干净 `815ddc0289`，无本轮行为变化 | `D:/Codex/worktrees/mihon-cbz-d10-control-20261107/app-desktop/artifacts/preview/windows/20261107T082409Z-cd999b27/preview-manifest.json` |
| B 仅归档 | 干净 `f2928b7e1cd3f6b6f4ed6aa3f5a4c5e607c2905e` | `D:/Codex/worktrees/mihon-cbz-d10-archive-20261107/app-desktop/artifacts/preview/windows/20261107T084207Z-2af66014/preview-manifest.json` |
| C 联合候选 | 同一 B 加本轮两阶段容器与默认关观测；dirty 开发 preview，未提前宣称采用 | 正式C为 `D:/Codex/worktrees/mihon-main-reader-delivery-20261106/app-desktop/artifacts/preview/windows/20261107T090045Z-adea1876/preview-manifest.json`；旧084445只用于最初两份内部诊断 |

三包均由 `scripts/build-desktop.sh preview` 生成，实际 EXE 存在，构建和既有扩展运行时检查通过；nativeInteraction 仍为 NOT_RUN。三包 CDS 均因 Windows 参数长度跳过，未将其当性能失败或暗中只给候选额外预热。

B/C 的 DLL 都在 `app/resources/mihon-reader-miniz.dll`，包内各自构建清单的 source/buildScript 摘要已核对；B 的 DLL SHA 为 `9e1a02a553bdd61143f4a906095e1ffa4d10ad46d88380ddd8c82f5d3b5ccdf3`，C 为 `ba0d60bf027f23ebe3c60626f70e19d94a3662a7c25c8b0e82090a4b63bc1666`。独立工作路径的 DLL 字节不同，不能混报一个包 hash；二者固定源、ABI 1、x64 与静态 CRT 相同。普通打包产物还含链接 import library/exp，不作为运行依赖。

C 两份内部首次 profile 从同一 `normal-profile` 模板复制，精确 LF marker、RTL、单页、关闭拆页/匹配、原档 SHA 与完整应用树 digest 先核对；脚本、配置与原始状态保留在 `.test-tmp/d10-20261107/`。每份新 JVM，清除外置 agent 相关环境，只开启现有轻量观测；不提前进阅读器、不人为等目标档预热、不轮询首绘状态。

## 产品样本与采用裁决

分阶段取得产品preview、轻量观测首次、外部对照和双页恢复往返。下文记录联合C的归因及撤回；最终B单列，不能用C或历史实验替代。

### 首批正常窗口内部样本

C-i1/C-i2 均用全新模板 profile 和新 JVM，从书架进入详情、单击正常 Volume 04 章行，实际显示正确地图；mountedReader 为 RTL、单页、index [4]、187 页。两份均记录 ARCHIVE_BACKEND_MINIZ、目标 HOLDER_CREATE_BEGIN 仅 1 次、startupObservation dropped=0；进程模块的 DLL 路径均为该 C preview 的 app/resources。回调到应用绘出分别 274.7379、238.5969 ms，仍不是外部 F/R/K。原始状态、输入包络和进程记录位于 .test-tmp/d10-20261107/C-i1 与 C-i2。两次均正常 Escape 返回详情后调用自有 shutdown，launcher/runtime 已退出；没有采集器或 clock helper。严格分段、等待归因、外部对照和采用裁决尚待离线继续。


### C-i1/C-i2 分段及一次补点

| 相对 PAGE_LIST_READY，ms | C-i1 | C-i2 |
|---|---:|---:|
| 后端选择开始至页表 | 7.975 | 8.186 |
| 真实目标 holder 开始 | 82.040 | 75.091 |
| holder 实际 Compose apply | 110.368 | 97.333 |
| 显示 CONTENT_IO_BEGIN | 161.759 | 134.738 |
| 应用目标首绘 | 228.556 | 190.489 |
| 首次模型摄入开始→结束 | 36.803→57.054 | 36.286→54.247 |
| 目标 poll→prepare | 106.713→108.699 | 93.540→95.131 |
| prepare 后 gate→store-ready | 108.909→112.513 | 95.346→96.704 |
| store-ready→真实归档 copy | 112.513→127.429 | 96.704→107.630 |

两项严格内部目标：归档均达到；holder 分别超出 43.065 ms 预算 **38.975 / 32.026 ms**。这是这两份带观测样本的判断，不承诺稳定上界。根 COMPOSE_READER_ENTER 到首个 APPLY 共约 69.757/71.920 ms，其中 runtime 创建完成后到 APPLY 44.882/44.781 ms 尚未细分。poll→prepare 只有 1.986/1.591 ms，已包含 job 登记、派发与 permit，不能把此前约24 ms全归给线程派发。

Ready 早期记录只有 SESSION_ACCEPT 边界，不能精确证明哪次接受了目标页 Ready，故不由相邻事件猜绑定等待。本轮只补一次 root effects/nav/viewport、viewport loading/topbar、cache query/store/writer 与 MODEL_TARGET_READY_ACCEPTED。真实挂载观测红测缺首个新边界，补点后 41 项 focused 通过；格式收尾的观测2项通过。请求顺序、存储与配对行为均未修改。

补点 preview 为 `app-desktop/artifacts/preview/windows/20261107T090045Z-adea1876/`，artifact SHA `753c8ed060f89404e27adab88086324cf5ba367ea949adce035dcc77d2b79e39`。仅观测扩展，仍是 B 加同一容器的 dirty 开发候选。C-i3/C-i4 使用它及同一新模板配置；本次之后按可归因程度选择唯一最小行为改动或明确停手，不连续试探无因果支持的调度/共享协议。

C-i3/C-i4 已取到新诊断包的正常首开记录，均正确地图、187页、单页RTL、dropped=0，回调到应用首绘分别279.9171/243.1969 ms。章行可操作截图观察后到输入开始分别6.365/6.240秒，未人为等待预热；该时间含工具观察与操作开销。首绘后对全体条件标记的快速断言有一项缺席（待离线按实际分支核对，未据此判定产品故障）。两实例均正常Escape返回详情后shutdown，launcher/runtime自然退出，无采集器/clock helper。原件在.test-tmp/d10-20261107/C-i3、C-i4；精确分段与停手/改动裁决待离线继续。


### 补点样本的归因与停手边界

C-i3/C-i4 缺席的仅是 VIEWPORT_TOPBAR_BEGIN/END；两次实际不显示顶栏，对应条件分支未执行，没有 dropped。第一次 root viewport 的 AUX_STATE_READY 前分别21.125/17.286 ms，包含教程/窗口/fullscreen/test mount等混合初始化；实际 LoadingState 2.533/2.276 ms，ReaderSideEffects 3.269/2.763 ms。当前证据没有单独识别可延期且不损失窗口、焦点及退出语义的附属项，不据整个混合 span 延后它们。

| 相对本次 PAGE_LIST_READY，ms | C-i3 | C-i4 |
|---|---:|---:|
| 后端选择开始→页表 | 7.985 | 6.972 |
| 真 holder 开始创建 | 88.298 | 73.735 |
| 真 holder 实际 apply | 117.311 | 100.850 |
| 显示取图开始 | 165.948 | 139.619 |
| 应用首绘 | 229.910 | 195.970 |
| 回调→应用首绘 | 279.917 | 243.197 |

本次最终诊断源码两份样本：归档达到19.620 ms；容器仍分别超43.065 ms **45.233 / 30.670 ms**，不承诺稳定上界。既有 C-i1/C-i2 保留为补点前结果，不能替换其中较慢样本。request epoch=2、requestId=0、attempt=0 的目标 work 与 reader generation=1 依各自边界关联，不能把两种generation值混成一个字段语义。

调度结束→目标copy调用17.039/13.592 ms，分账如下（互斥的小段才相加；poll已早于schedule-end发生）：poll→prepare1.601/1.309 ms；gate等待0.247/0.208 ms；store可写准备1.022/1.027 ms；store-ready→cache查询4.537/2.934 ms；cache查询4.434/3.370 ms；查询结束→store入口2.533/1.874 ms；store入口→writer回调3.093/3.169 ms；writer→copy入口0.971/0.869 ms。copy调用4.319/2.944 ms含既有归档操作锁和真正复制/CRC，不冒充纯I/O。未证邻页抢permit、重复store初始化或多余派发，保留原请求优先级、存储原子落盘和取消链；本轮确认可直接回收量为0，机会窗口不报成收益。

目标 MODEL_TARGET_READY_ACCEPTED→绑定14.778/15.279 ms，其中接受到下次选中页组合5.994/7.314 ms，进入该组合到绑定8.784/7.965 ms。后段包括真实region绑定的首次工作；前段在一次Compose帧范围内，没有发现额外跳过一帧或重复开流。没有据此迁移订阅、在锁内I/O、提前创建无encodedRef的region，或让所有模式一律准备图片。

因此第4项本轮收口为有界归因与明确不实施额外行为变更。两阶段容器已实现的价值仍由同仪器A/C、C/A，以及因严格容器目标未达而必要的B/C、C/B可见对照决定，不能只凭更早构造对象采用。

### 正式可见对照预注册

顺序为 A1→C1→C2→A2（A/C、C/A），再 B1→C3→C4→B2（B/C、C/B，决定容器是否值得保留）；随后 C-off/C-on 各一次真实双页恢复、完整页对及正常往返。各份全新模板profile、新JVM、同一内容/设置/窗口；全部关细观测、无外置agent，不在首绘前轮询状态。WGC使用已有已核SHA的Python/native recorder、原帧与QPC，90秒/10000帧上限；由第一帧ready及即时存活/尾帧新鲜度门槛约束输入，不把旧ready当仍在录制。clock helper独立回环逐行协议，六次往返校准；draw observation只在首图和WGC停止后用于实际身份核验，不进入性能区间。

C frozen production-input digest `19bbd225f87158bcf4c6b988691f1e1dd37a2b45a657ec49938392083f63d522`，窗口前已与当前实际生产输入核对一致。原始manifest、配置、输入及采集负担见 `.test-tmp/d10-20261107/formal-plan.json` 和各样本目录。外部端点、采用及剩余差距待以下实际对照填写。


## 正式原帧结果与容器撤回（第一轮裁决）

按预注册顺序完成10个fresh JVM。原件在本工作树 `.test-tmp/d10-20261107/<sample>/`，包括process/profile、input、anchors、state、capture原帧/events/manifest及原生stderr。逐接收帧核对frameId从1连续、QPC严格递增、PNG存在、received==written、missing=0，且原生输入包络、callback、正确漫画和其后至少1秒均在采集覆盖内。各份主动stop均保留PARTIAL/native-capture-exit:1，不能改称完整RECORDED或物理屏幕扫描。B1因整理上下文前准备超时而留下无任何reader点击的 preparation-only-capture；核实采集进程已退出后才重新采集，同一JVM的首次reader仍在正式input内，没有替换慢样本。

主口径为同一章行EDT回调→WGC原帧像素转换，F取正确地图首次出现前一原帧到命中原帧，R取首次明确阅读器“正在加载页面”/spinner的前后帧，详情涟漪不算。人工逐份核对过渡contact sheet、B1原尺寸响应图及双页两侧纹理。下表单位ms，区间保留实际采样间隔，不取中点。逐项原始frameId、输入包络、等待、最大gap及原件SHA见[CSV](desktop-reader-d10-2026-11-07.csv)。

| 样本 | R | 正确地图F | K累计＝最长 |
|---|---:|---:|---:|
| A1 | 51.502–118.169 | 326.499–343.165 | 208.331–291.664 |
| C1 | 33.211–99.876 | 274.878–291.544 | 175.002–258.333 |
| C2 | 33.388–116.720 | 275.055–291.722 | 158.335–258.334 |
| A2 | 33.673–125.339 | 317.003–333.670 | 191.664–299.997 |
| B1 | 28.399–378.393 | 678.389–728.389 | 299.996–699.990 |
| C3 | 26.008–117.676 | 259.344–276.010 | 141.668–250.002 |
| C4 | 34.172–117.506 | 284.171–317.505 | 166.665–283.333 |
| B2 | 26.770–110.102 | 285.102–301.768 | 174.999–274.998 |

单页近黑使用1518×1143窗口章行ROI(676,220,823,120)、灰度均值<17；漫画黑边不计。每份只有一段连续等待，首图后1秒内没有再黑，因此累计与最长相同。内部长gap没有任意拆开；B1最大349.994ms、其余单页最大66.667–91.667ms。原生工具输入包络宽94.648–101.903ms，整个包络另报，不能当EDT送达精确值；callback比包络结束略晚亦保留。详情实际被观察后到输入为5.57–63.19秒，含WGC准备及健康防护，不是人为目标图片预热；没有首绘前轮询或提前开目标CBZ。系统文件缓存不清，后台负载与WGC扰动未独立校准，不声称无采集时日常性能。

A1/C1和C2/A2的F区间同方向分离，名义最小领先34.955/25.281ms，只证明这次联合候选在两次同仪器对照更早见图。B1慢样本完整保留，不能凭这一份给容器归因；反向C4/B2的F及K区间均重叠。严格holder目标已失败，容器自身F/K改善未复现，**不满足采用门槛，撤回本任务全部两阶段视口、owner延迟绑定、movable registry及其专用测试和诊断**。保留已提交B miniz；未将联合收益直接归给它。下一步仍须最终A/B、B/A及B自身两个轻量观测新JVM，未测完前只有归档组件结论。

撤回后生产源码与干净B的2442项原始生产输入逐字节一致，digest `4337fe6a70d9d6c254cfd216be816aa2da20afb0b329b77e1c17353e335dea1d`；三个文件仅工作树行尾差异已按B复原，Git产品diff为空。B整包摘要复核为 `9b44b80f1c49c83138dc06ae92d43abaae6086fe0fb90e00b1d88990beb52170`，不需为纯撤回和文档再重建同源码包。最终B仍可直接复用上述干净preview。

### 双页画面与身份

C-off实际[4 LEFT,3 RIGHT]→[6,5]→[4,3]，locationSequence为0→1→2，frame serial为0→1→5。C-on为[5 LEFT,4 RIGHT]→[7,6]→[5,4]，sequence为0→1→2，serial为1→2→6。稳定原生画面两侧完整；返回的assetIdentity/contentIdentity保持对应原页，frameMatchesAssetGeneration均true。draw_observation每段只保留最后120条，连续compose-frame-geometry记录存在258–2166条溢出；这些是最后应用的布局/Compose时钟代理，不冒充每条都是实际canvas command。原画面与身份共同证明本次稳定页对，不能仅凭相同pageIndex推断物理frame未更换。单页C1该hook记录0，未将其作为同对象首绘通过证据；同对象行为由真实Compose测试及细观测证明。

双页first F：off两侧均517.454–550.788；on右侧地图493.626–543.626、左侧人物543.626–576.959，因此完整页对543.626–576.959。两侧分别取避开toolbar/教学的纹理ROI，off两侧K累计/最长均399.995–516.662；on左441.661–541.658、右391.661–508.325。off原帧790/791在409.125/459.124ms曾出现“无法加载页面”重试槽，随后未点击重试即正常出图；这是观察，原因尚未证实，不归因于miniz或容器。最终B补做同模式恢复及往返，若复现则补一个A-off控制定位既有行为，不继续试性能改动。

工程目标判定：这些样本K下界均>60ms，明确未达到；R除C1上界≤100外其余均跨100，分辨率不足，不能宣布整体达标。官方安装版同页272.9–289.6ms仅为另一设备及采集条件的参照，不与内部首绘相减，也不从两份样本推P95。

撤回后的focused：`d10-final-b-focused2`共41项、40通过、1个真实整卷诊断用例因未设置MIHON_CBZ_DIAGNOSTIC而按设计跳过；复用前述187/187实物校验，不再次整卷解码。实际执行native12、owner12、章节接线15、真实首次draw1，零失败。先前`d10-final-b-focused`误用不存在的:test任务，在配置阶段结束，无产品用例执行；已改为项目:jvmTest定向命令。没有全模块或全量测试。


## 最终B裁决与交付

### 默认打开的同仪器结果

撤回C后，按事前保存的 `final-b-plan.json` 完成AB-A1→AB-B1→AB-B2→AB-A2；均为相同模板的新profile、新JVM、正常书架→详情→Volume04、RTL/index4。细观测关闭，不提前读目标CBZ，不在首绘前查询reader/state；应用与采集已自然退出。WGC原帧、callback、QPC和失败告警按前述相同口径逐份复算；PNG完整、frameId连续、received==written、missing=0，正确地图后另检查至少1秒，无再次闪黑。没有因上一轮B1较慢而删去或替换该样本。

| 样本 | R，ms | 正确地图F，ms | K累计＝最长，ms |
|---|---:|---:|---:|
| AB-A1 | 41.883–141.882 | 341.879–358.545 | 199.997–316.662 |
| AB-B1 | 34.374–101.039 | 292.707–309.374 | 191.668–275.000 |
| AB-B2 | 26.085–109.416 | 276.084–292.751 | 166.667–266.665 |
| AB-A2 | 31.880–115.215 | 298.551–315.218 | 183.337–283.338 |

两对A下界减B上界分别32.505/5.801ms，均支持本次同仪器F改善；不将两次差异的均值、最小值或中位数外推为日常保证。K/R区间重叠，未证独立改善；最终两份B的R都跨100ms，分辨率不足，K下界都超过60ms，明确未达到。原生工具输入包络宽92.513–99.974ms、详情观察到输入6.668–8.065秒（含WGC/健康检查），均保存于CSV；不混为EDT送达。正式细观测关闭，不能从这些状态直接得出每份实际后端，B1慢样本亦不猜测为回退。它们考核的是默认产品打开路径，未按命中与否过滤；实际miniz命中由下述同包轻量观测证明。

### 最终源码的两个内部首次

B-i1/B-i2均为相同B包/模板、新JVM，只有现有startupObservation打开，dropped=0；无WGC、外置agent或类替换。观察到可点击章行后分别6.066/7.235秒派发正常输入，没有人为预热等待；两次均为实际 `ARCHIVE_BACKEND_MINIZ`，非零generation=1，正确地图、187页。普通工具观察/输入耗时仍存在，不能称输入发生于进程启动瞬间；没有获得GUI中强制未就绪的样本，也未为制造该条件阻塞loader。真实端口回归另外证明未开始、加载中、不可用状态立即使用旧选择链，已就绪后的新开才切miniz。

| 相对已接受PAGE_LIST_READY，ms（首两行例外） | B-i1 | B-i2 |
|---|---:|---:|
| 同一后端选择入口→页表 | 6.659 | 7.717 |
| OPEN_READER_INTENT→页表 | 11.887 | 13.276 |
| 目标holder开始创建 | 150.285 | 156.156 |
| 目标holder实际COMPOSE_SLOT_APPLY | 166.536 | 172.734 |
| 显示CONTENT_IO_BEGIN | 167.713 | 174.099 |
| 应用FIRST_PAGE_PRESENTED | 223.733 | 232.509 |
| 章行callback→应用首绘 | 272.312 | 280.992 |

**归档≤19.620ms：两份均达到，余量12.961/11.903ms。holder≤43.065ms：两份均未达到，超出107.220/113.091ms。** 后者是撤回容器后原视口的最终数据；联合C本身也未达到（超45.233/30.670ms），不能把最终B的新样本与C的诊断点拼成一份“达标联合候选”。后端选择含实际就绪检查和解析，宽口径打开意图亦单列。显示acquire/open与归档向encoded store复制是不同入口，表格不把提前copy称作实际显示取图。

### 最终双页与已知限制

B-off和B-on均用新模板/新JVM正常恢复、完整双页、Left前翻/Right返回，稳定画面已人工核对：

| 模式 | 位置页对与左右 | locationSequence | 物理frame serial |
|---|---|---|---|
| 关闭匹配 | [4 LEFT,3 RIGHT]→[6,5]→[4,3] | 0→1→2 | 0→1→5 |
| 开启匹配 | [5 LEFT,4 RIGHT]→[7,6]→[5,4] | 0→1→2 | 1→2→6 |

每份稳定draw记录都匹配当前reader/frame代次及对应页内容，返回的contentIdentity与原页相同。off返回右页3的asset对象重新建立（1281242370→2124285715），内容身份仍相同，未声称是原物理对象；on两页asset身份保持。每段保留最后120条compose-frame-geometry，溢出2416–4758条，仅用于稳定页对/布局核验，不宣称覆盖所有实际绘制命令或动态过程中每一帧。原生画面独立确认两侧内容及往返。 退出后只读核验隔离SQLite：AB-B1/AB-B2的chapter906004均last_page_read=4，B-off为4、B-on为5，与各自back-state的当前页4/5及详情显示一致，read仍为0；不把双页返回的分组首索引5误当成错图。

B-off两侧首图均518.362–568.362ms，K累计/最长均416.662–524.995ms。B-on右侧地图533.943–583.943、左侧人物583.943–617.275ms，完整页对583.943–617.275；左侧K累计/最长483.324–583.322、右侧433.325–549.990ms。两份R分别43.367–101.700/33.953–100.619ms，均跨100预算。双页没有同期A控制，不宣称双页速度收益或整体体验追平；本轮规定的候选恢复、完整页对和往返检查通过。

C-off短暂重试槽未在最终B-off原帧复现；B-off帧829/830显示的是加载spinner，随后两页正常出现，没有点击重试。该观察不足以断言先前原因或宣称修复了一个miniz bug。C容器已整体撤回，没有新现象需要扩展为配对或共享调度修复。本轮不新增A-off复测。慢样本、PARTIAL、QPC长gap及采集扰动未校准限制全部保留，不能据此宣称全平台、全部CBZ或无采集的稳定上界。

### 采用范围与可执行产物

- **采用B的miniz后端**：真实下载路由、固定源码包内DLL、批量目录和流式页读取；实际187/187字节与身份校验，受支持格式的真实选择工作缩短，默认A/B与B/A本次F均有可辨改善。保留原指纹、排序、非零租约代次、取消/CRC/写失败清理和兼容回退；没有持久化索引、提前解卷或新全局调度。
- **撤回C容器及专用观测**：真实红绿和生命周期已验证，但严格holder目标失败、反向独立F/K改善未复现；最终生产输入回到干净B，没有遗留StartupSinglePageViewport、延迟绑定或专用测试。第4项有界归因可确认直接回收量0，不将机会窗口作收益。
- **保持未达到的目标**：holder严格预算、K≤60、R≤100整体通过、官方体验严格追平；这不阻止按原计划作局部采用裁决，但不能勾选父计划的整体体验目标。未做Android、完整模块/全量、正式发布或推送；公共功能说明没有更新。

实现提交为 `f2928b7e1cd3f6b6f4ed6aa3f5a4c5e607c2905e`，最终新增只有内部证据/CSV与父子路线图，生产输入digest仍为 `4337fe6a70d9d6c254cfd216be816aa2da20afb0b329b77e1c17353e335dea1d`。使用已冻结并逐项复核的干净B预览，不把dirty C、远端主干或正式发布包称作交付。

[最终B Mihon Desktop.exe](D:/Codex/worktrees/mihon-cbz-d10-archive-20261107/app-desktop/artifacts/preview/windows/20261107T084207Z-2af66014/Mihon-Desktop-0.11.24.6.f2928b7-unpacked/Mihon%20Desktop.exe) 来自该次构建日志的Final unpacked EXE，实际文件存在；完整包摘要 `9b44b80f1c49c83138dc06ae92d43abaae6086fe0fb90e00b1d88990beb52170`。包内 `app/resources/mihon-reader-miniz.dll` 为x64/ABI1、266752字节，SHA `9e1a02a553bdd61143f4a906095e1ffa4d10ad46d88380ddd8c82f5d3b5ccdf3`；build.json的14项源码及构建脚本摘要、pin、/MT、工具链与实际源再次匹配，只有bcrypt/KERNEL32运行依赖。普通界面真实draw后后台加载，快速正常首次B-i1/B-i2实际选到miniz。preview manifest的nativeInteraction保留构建时NOT_RUN，本次原生验收证据单独记录，不篡改原清单。

### 收口核验

| 路线图承诺 | 当前证据与结果 |
|---|---|
| 固定真实原档、地图、基线和对照 | 原档/目标SHA、A/B/C清单、全新profile、源码与完整包digest匹配 |
| 可重建下载后端、兼容/身份/租约/失败处理 | native构建源/许可证/清单，真实下载路由红绿，53项归档focused与一次187页全对照，独立生命周期审查；最终相关补验40通过/1诊断跳过 |
| 两阶段容器真实实现、接线与采用判定 | Queued/Ready/首绘/手势/早翻页/晚旧Ready红绿；严格和反向可见门槛失败，按计划撤回 |
| 两段等待的有界归因 | C-i3/C-i4互斥分账、当前代次关联；无确定可删交接/重复初始化，停止追加行为改动，专用补点随C撤回 |
| 最终预览、严格目标、F/R/K、双页与收尾 | 最终B包、两份内部首次、A/B与B/A原帧、B双页左右端点和往返，正常退出及自有采集/clock退出，父子checkoff与证据同批提交 |

提交前复核产品Git diff为空；最终focused日志 `d10-final-b-focused2`成功、41项中40通过/1真实整卷诊断跳过，前述实物证据不重复运行；本轮只读证据校验和文档diff检查，不增加测试矩阵。所有本轮新建应用及采集进程在各组正常返回详情后关闭，QPC helper正常退出；49531/49532/49541无监听。设备/内容局限、未通过预算及未验证的平台均保留。
