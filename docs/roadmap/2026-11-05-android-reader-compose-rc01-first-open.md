# Android 已下载单页首开实验：在 B9 上对齐官方 Compose 版本

**active-task：已收口（C0–C4，首轮早停，保留 B9）。** 执行人 Codex。要回答的问题是：固定阅读器代码和编译条件，仅把 Android Compose 库组从 B9 的版本换成官方 APK 所列版本，能否让平板首次打开已下载章节时更早看到正确漫画，并把近黑控制在 60 ms 以内？

**预算**：一个既有控制包 A，一个新候选包 B。先做 A 首次、B 首次；有采用机会才反序，做 B 首次及暖态、A 首次及暖态。计时打开总数为 **2 或 6**。每安装块的相同预热和功能检查另计。预计 30–60 分钟，主要成本在 Gradle 解析、R8 和四个以内的安装块。由一人操作设备，结尾集中验收一次。范围到本地实验裁决为止，不启动下一轮创建优化、发布或推送。

执行结果：[证据报告](../evidence/android-reader-compose-rc01-first-open-2026-11-05.md)。仅构建一个候选；计时 A1/B1 共2次，T 下降门槛未满足，F 差距区间跨零，按计划未进入反序/暖态。已撤回依赖修改并保数据恢复 B9。下文局部采用项保留为未执行的条件判据，不是待补采任务。

## 接手位置与依据

- 在 `D:/Codex/worktrees/mihon-reader-o-series-20261104`、分支 `codex/reader-open-o-series-20261104` 工作。起点 `91adf3169ec7a91f00ae25a4030bb8dd960a2d8c` 含归因报告，本计划随后只增加文档。先核对分支、提交和工作区；若已有后续产品差异，隔离到该基线，保护他人改动。原目录 `D:/Shell/Github/mihon` 仅从已列路径读取旧证据。
- **A** 为[已测 B9 APK](../../app/artifacts/android/diagnostics/reader-open-o9-overlay-narrow-20261104/Mihon-Fork-reader-open-o9-overlay-narrow-benchmark-universal.apk)，SHA-256 `4c3c6f82eea657aecd0c261972fa04c965a99ac5468f21309b87b032f23ccc59`。核同目录 `artifact.json`、`source-input.json` 和冻结差异。保留 A 的原始构建身份。
- **官方参照**为本机归档 `D:/Shell/Github/mihon/.test-tmp/upstream-reader-tablet-20261102/original-installed-base.apk`，SHA-256 `7a7969564a604bf74608758b540730e53dfe63e11782a718d32380dc9febed58`。已分别核对两包哈希并读取各自 `res/M7.json → libraries → uniqueId/artifactVersion`。官方仅提供目标依赖和历史体验参考；设备上的官方 APK、数据、设置及编译状态均不操作。
- 相同场景三份首次样本的中位数：B9 比官方晚见正确地图 **46.9 ms**（录像保守界限 12.9–63.6 ms）；最后一轮遍历的主线程 CPU 多 **11.439 ms**，其中 Compose 重组多 **3.807 ms**。Reader 创建 CPU 另多 **10.137 ms**。这些量用于选题，不预支为候选收益。[首图基准](../evidence/reader-open-o-series-2026-11-04.md#三方真机对照) · [调用和遍历证据](../evidence/android-reader-b9-prefirst-calls-2026-11-05.md) · [最近一次反序对照](../evidence/android-reader-single-first-open-2026-11-05.md)

## C0　冻结依赖差异，先判断能否只改一个变量

- [x] 在源码处于控制状态时保存 `:app:benchmarkRuntimeClasspath` 的完整解析结果，同时核对 `releaseRuntimeClasspath`。从 [版本目录](../../gradle/compose.versions.toml)、[Android Compose 插件接线](../../buildSrc/src/main/kotlin/mihon/buildlogic/ProjectExtensions.kt) 的 `configureCompose` 和 [app 依赖](../../app/build.gradle.kts) 跟到最终选择。用 `dependencyInsight` 查 `androidx.compose.runtime:runtime` 或实际选中的 `-android` 变体，记录是谁请求、哪个约束胜出。不要根据 BOM 标签猜实际版本。
- [x] 依赖清单中，两包共同列出的 **18 个不同版本的 AndroidX Compose 坐标**如下。除 `material:material` 为 B9 的 `1.10.5`，其余 17 项均为 `1.11.0-alpha05`；官方全部为 **`1.11.0-rc01`**。区分 BOM、元数据坐标与实际 Android 产物。以整组为一个实验变量。

| Maven 组（省略 `androidx.compose.` 前缀） | 模块 |
|---|---|
| animation | animation、animation-core、animation-graphics |
| foundation | foundation、foundation-layout |
| material | material、material-ripple |
| runtime | runtime、runtime-annotation、runtime-retain、runtime-saveable |
| ui | ui、ui-geometry、ui-graphics、ui-text、ui-tooling-preview、ui-unit、ui-util |

- [x] **优先最小 Android 约束变更。** 对照上游 `v0.19.9:gradle/libs.versions.toml` 的 `androidx.compose:compose-bom-beta:2026.04.00`；若直接替换当前 BOM 带动无关项，可改用更窄的 Android 依赖约束。先解析再打包。把变更前后的完整运行时组件按实际组、模块、选中版本、平台变体比较，既列升级项，也列新增及移除项。允许目标组对应的 Android 变体和必需的 Compose 内部传递项，逐项解释。BOM 本身单列。本轮固定 Material3（两包同为 `1.5.0-alpha14`）、Activity（`1.13.0`）、Lifecycle（`2.10.0`）、B9 Kotlin stdlib（`2.3.10`）、Kotlin/Compose 编译插件、AGP、R8、图片解码库和业务源码。Android release/benchmark 的目标运行库须一致；若触及跨平台声明，核桌面实际运行时解析无变化，无需构建桌面。
- **停止条件**：A 实际解析和已测 APK 清单无法对上、仅改清单文字而实际 Android 库未变、必须连带升级无关库／编译器／桌面运行库或改 Kotlin 代码，均记录具体冲突坐标并停止。未尝试可行的窄约束前，不把一次 BOM 解析结果当最终阻塞。已确认的参考包版本差异本身不证明性能原因。

从工作树运行，以下为控制组例子；候选用新的任务 key 存日志。先用离线缓存，仅缺固定坐标时按本机网络规则获取该项并记录，勿刷新整个缓存。

```powershell
python scripts/gradle-coordinator.py run --key compose-rc01-a-graph -- .\gradlew.bat :app:dependencies --configuration benchmarkRuntimeClasspath --offline --no-parallel --max-workers=2
python scripts/gradle-coordinator.py run --key compose-rc01-a-runtime -- .\gradlew.bat :app:dependencyInsight --configuration benchmarkRuntimeClasspath --dependency androidx.compose.runtime:runtime --offline --no-parallel --max-workers=2
```

## C1　构建一次，验证候选确实跑的是目标库

- [x] 复用[已归档的真实性能基线](../evidence/android-reader-b9-prefirst-traversals-2026-11-05.csv)，提前固定下文体验判据。在改动前锁定 A 身份和 C2 的实际输入、失败及通过条件；A 是性能对照，原有 UI 功能本应通过。不要为制造失败去改正确的功能断言，或把毫秒门槛写成易抖动的 JVM 测试。
- [x] C0 通过后经协调器运行 `:app:assembleBenchmark`，保存唯一候选的 APK、源码与解析差异、mapping、签名、manifest 和 SHA。A/B 均为 `app.mihon.desktop.fork.benchmark`、code 134、`0.19.4-aex.116-benchmark`、同一证书（SHA-256 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`）、同 ABI、非 debuggable、R8/资源缩减。核实源输入只有目标依赖差异，并记录构建生成的 revision 等身份字段。在 B 的 APK 中按内容定位依赖清单，资源文件名可能变化。归档与 C0 对应的实际选中版本；若包内清单来自别的变体或不一致，先用该 APK 对应的构建输入／实际 AAR 佐证，无法核定身份就停止。
- [x] 定向回归只跑[现有阅读器集成测试](../../app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderChapterErrorDialogIntegrationTest.kt)两项，使用 `:app:testReleaseUnitTest --tests "<全类名>.<方法名>"` 精确选择：`O9 unrelated state keeps brightness wrapper idle while actual brightness still draws` 和 `initial chapter error has a visible close action which finishes the reader`。全类名 `eu.kanade.tachiyomi.ui.reader.ReaderChapterErrorDialogIntegrationTest`。通过 XML 核有两条实际执行。若失败，只在有必要时对未改依赖的 A 源码复核同一用例，分清基线与候选回归；不为通过而改测试或改 UI。跑适用的格式检查和 `git diff --check`。本轮不加探针、方法采样、全模块测试或正式包。

## C2　同一平板顺序对照

1. **每个安装块都做同样前置。** 按[平板实测经验](../automation/ANDROID_TABLET_ACCEPTANCE.md)核对当次 USB serial、PCE-W30／API31。仅对隔离包保数据安装，读回实际 APK SHA、实际 ART 编译状态（沿用可核的 `verify/install`）、电源/温度、刷新或动画设置、分辨率和 probe OFF。两臂同一 USB 条件；状态未知或不等，不能据此决定采用。不清数据、缓存，不强编。前台需要时正常唤醒并解除无密码锁屏，离线分析和结束时熄屏。
2. **真实入口。** 在同一已下载《史莱姆》第 04 卷，章节 SHA-256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。确认单页 RTL、横图自动放大关闭、黑背景、原动画，地图第 5/187 页。每块同样进行一次预热，确认菜单能开合、5→6→5 正确，正常退出保存，再强停隔离应用。计时首次从新进程正常进入书架、作品详情、点击该章；暖态从阅读器返回详情，同一 PID 再点。保留真实漫画纹理和进度证据。
3. **首轮 A1 → B1。** 每块只采一次有效首次，早停门槛见 C3。若需反序，**B2 首次 → B2 暖态 → A2 首次 → A2 暖态**，即使 B1 与 B2 相邻也重新走相同安装和预热。仅一个反序块，无第三轮、官方重录或补采择优；技术失败保留原件，报告缺哪项证据。
4. **工具入口。** 已验证脚本在本工作树 `.test-tmp/reader-single-first-20261105/`，复用 `run_ui_pair.py` 的真实导航、`capture-raw.py` 的 Perfetto＋原生视频 PTS、时钟 helper 及 `check_page_texture.py`。在新的忽略目录使用唯一标签、候选 SHA 和新清单。旧 `run_ui_pair.py` 固定循环 first/warm；首轮副本只选 first，保留退出/清理，不改采集时序。旧 `operate.py` 的安装分支绑定历史 APK，勿原样执行安装。反序沿用原 first/warm 路径。不要运行原清单覆盖旧样本。

## C3　逐对计算，按预先约定的门槛裁决

每条记录标明样本、APK SHA、PID/boot、唯一 Reader 启动、trace 告警、采集与设备时钟误差。保留原始 PTS、正确地图前一帧和首帧、近黑首末帧。录制后不插帧。由同一套工具导出下列端点。

| 符号 | 算法与用途 |
|---|---|
| **T** 末次遍历主线程 CPU（ms） | 应用主线程收到 DOWN 到首个 Reader 关联的完成帧 `actual_frame_timeline_slice.ts+dur`。选择该窗口内开始的最后一次通用 `traversal`，确认在 Reader 创建结束后开始，将 slice 与该主线程所有核的 sched Running 取交集，裁到帧末。保留次数、相对时点及窗口身份未知。复用 `.test-tmp/b9-prefirst-calls-20261105/acceptance/traversals.py` 的算法，替换其硬编码输入输出。T 为主要机制指标，可顺记同段重组 CPU。 |
| **F** 正确地图首见（ms） | DOWN → 正确纹理第一次出现在视频中，以前一原生帧和首正确帧给出区间 [L,H]，点值取 H。按[既有帧界限函数](../../scripts/reader_open_replay_summary.py)与同次时钟包络计算。逐对给出候选减控制区间 **[B.L−A.H, B.H−A.L]**，上界 <0 才能分辨提前。 |
| **K** 近黑时长（ms） | 同一视频、相同视口及亮度判据取得区间 [L,H]，保留无独立近黑帧时的采样间隙，并核实首图后无再黑。H ≤60 表示该样本达标，L >60 表示超标，其余为未确认。 |
| 辅助端点 | 同次记录 DOWN→Reader 创建和 DOWN→Reader 窗口首帧，标明单位；辅助端点和 T 不能替代 F。 |

- [x] **首轮止步或反序。** B1 正确性通过、T 小于 A1、F 点值也早于 A1、K 上界 ≤60，才进入反序。首轮 F 差距跨零但点值向好可进入，须保留“未分辨”标记。任一必要条件缺失即保留 A；近黑跨 60 或设备条件缺失按“未确认”报告，不解释为性能下降。
- [ ] **局部采用（本次不适用：首轮早停，未执行反序）。** B2 的 T 也低于 A2，且反序首次 F 差距上界 <0；两轮候选首次 K 和反序候选暖态 K 的上界均 ≤60。暖态 F 无可分辨退化（若 B2 暖态 L > A2 暖态 H 即退化），地图、菜单、翻页、返回/恢复均正常，才保留这一个候选。两组分别报告，不能把顺序不同的样本混池；首轮差距若跨零，只能说反序明确、首轮方向一致。两个首次样本不能推断 P95 或一般设备收益。
- [x] **其他情况。** T 改善而 F 不支持，登记“本机布局工作减少，首图收益未证实”；两者方向混合、超预算或暖态／正确性失败，撤回候选；发现不可比则单列实验限制。只报告实测 B−A，不能直接从历史 46.9 ms 减去本轮点值来宣称新的官方差距。Reader 创建差额可作为以后独立题目，本轮到此结束。

## C4　交付和收尾

- [x] 用一份证据报告及必要的一张逐样本表交付：A/B 依赖解析差异、APK 身份、真实定向测试结果、A1/B1/可选 B2/A2 的 T/F/K 原值与界限、采用决定和具体触发条件。编译、解析、采集失败或未进入反序也可收口，未执行项标明原因。原 trace、录像、原图、大依赖图与包留在各自隔离目录。
- [x] 采用才保留最小 Android 依赖改动和候选标识，否则仅撤回本任务修改，设备保数据恢复已核 SHA 的 B9。正常退出保存第 5 页，确认探针关闭、隔离包和采集进程退出、屏幕已熄。只提交本次证据与获采用的改动，报告本地 hash；本轮结论仅适用于上述 Android 单页路径。
