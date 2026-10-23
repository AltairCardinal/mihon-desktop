# Windows 桌面端启动性能优化（2026-10-21）

## 目标

把 Mihon Desktop（Windows）的冷启动从「明显慢于安卓端」提升到可接受区间，
并让启动耗时**可测量、可归因、可回归**。

## 测量体系（先有尺子，再动刀）

### 应用内埋点

新增 `mihon.desktop.startup.DesktopStartupTrace`：

- 在进程启动路径上打点（`main-entry` → `di-begin` → … → `first-frame`）。
- 每次打点即落盘到 `%LOCALAPPDATA%\Mihon\logs\startup-trace.json`，
  强制结束进程也不会丢失已经记录的阶段。
- 记录 OS 进程起始时间、JVM 起始时间、已加载类数量，因此阶段耗时以
  「距进程启动」为统一坐标。

### 外部测量脚本

| 脚本 | 作用 |
|---|---|
| `scripts/measure-desktop-startup.ps1` | 启动分发包，读取应用自报的首帧时间；可选注入 JVM 参数 |
| `scripts/compare-desktop-startup.ps1` | **交替**运行 A/B 两个构建并对比中位数，抵消磁盘/后台负载漂移 |
| `scripts/show-startup-trace.ps1` | 打印阶段分解 |
| `scripts/analyze-class-load.ps1` | 解析 `-Xlog:class+load` 时间线 |
| `scripts/inspect-desktop-windows.ps1` | 枚举应用顶层窗口（标题/可见性） |

主指标为**应用自报的首帧时间**（进程启动 → 第一帧绘制完成），
不依赖窗口标题探测，避免 AWT 窗口归属导致的误判。

## 基线（优化前，`0.11.20.7`）

同机交替 A/B，5 次运行：

| 指标 | 基线中位数 |
|---|---|
| 进程启动 → 首帧 | **3747 ms** |

阶段分解（基线）：

| 阶段 | 耗时 | 说明 |
|---|---|---|
| 进程 → JVM 启动 | 52 ms | |
| JVM 启动 → `main-entry` | ~250 ms | Kotlin/协程等基础类加载 |
| `main-entry` → DI 开始 | ~290 ms | 崩溃处理器、路径解析、单实例 broker |
| DI 全过程 | ~1160 ms | 网络层/数据库/扩展/领域/UI 五层串行 |
| `compose-application` → `first-frame` | **1906 ms** | Compose+Skiko 管线与首帧，**占 51%** |

结论：真正的主矛盾是**「DI 全部完成后才开始建窗口」**——图形管线的初始化
（数千个 Compose/Skiko 类加载、Skia/D3D 上下文、着色器、字体）完全串行在 DI 之后。

## 优化项

### STO-01 窗口先出、DI 与首帧并行（主优化）

`DesktopOwnerBootstrapController` 承载 owner 启动状态：

- broker 选举完成即**立刻创建窗口**，内容先显示中性占位（进度指示）。
- 依赖图在后台协程中构建（`SupervisorJob` 挂在调用方 Job 下，取消语义不变）。
- owner 就绪后 `publishReady`，Compose 重组为真实界面。
- DI 失败 / 取消：占位切换为失败提示，并保留原有的失败恢复入口与运行时清理契约。

窗口几何不再依赖容器就绪：新增 `DesktopWindowGeometryStore`，让窗口在
DI 之前就能读到持久化的窗口位置/大小（与 `DesktopAppPreferences` 读同一个偏好键）。

效果：Compose 管线初始化与 DI **完全重叠**，DI 的 1.3 s 被完全隐藏。

### STO-02 扩展插件异步加载

`DesktopExtensionManager.loadAllAsync()` 在 `Dispatchers.IO` 后台读取扩展 jar 并实例化
Source；`awaitInitialized()` 供需要确定性的测试与无界面模式等待。
`DesktopSourceManager.isInitialized` 改为反映扩展管理器状态。

### STO-03 启动关键路径瘦身

- `DesktopPlatformPaths.current(createDirectories = false)`：首帧前不再创建全部目录。
- 单实例 broker：token 与 ownerId 复用同一个 `SecureRandom`（Windows 上首次初始化昂贵），
  并在 `main()` 起始用后台线程预热熵源。
- URI scheme 注册（写注册表，~100 ms）移出关键路径，改为与窗口创建并行。
- **数据库驱动与网络层并行**：打开数据库要加载 JDBC 驱动、解压原生库并执行迁移，
  这些都不碰依赖容器，因此放到 `Dispatchers.IO` 与网络层同时进行
  （实测 `di-data` 305 ms → 59 ms）。
- 迁移用的「已准备文件列表」扫描与下载 worker 一起延后到窗口出现之后。
- `DesktopAppPreferences.windowGeometryStore()`：几何读写与容器解耦。
- ~~SQLite 开启 WAL + `synchronous = NORMAL`~~ —— **2026-10-22 回退**，见下方「第二轮发现的真实回归」。

### STO-04 JVM 参数与 classpath 实验（结论：均不采用）

| 方案 | 结论 |
|---|---|
| 动态 CDS（`-XX:+AutoCreateSharedArchive` / `-XX:ArchiveClassesAtExit`） | 打包的 jlink 镜像**缺少默认 CDS 归档**（`bin/server/classes.jsa`），JVM 直接禁用动态 CDS（`ArchiveClassesAtExit is unsupported when base CDS archive is not loaded`）。补齐基础归档（`scripts/build-desktop-cds-base.ps1`，11.88 MB）后：训练运行变慢 5–10 倍（类加载被 `-XX:ArchiveClassesAtExit` 拖慢，>12 s 才出窗口），且优雅退出后**归档仍未生成**（`Archive NOT produced`）；基础归档本身对启动无可测收益。**路线证伪，不采用** |
| 启动期后台预加载全部 UI 类 | 实测对真实界面组合耗时**零影响**（`di-ui` 710 ms → 710 ms），证明瓶颈是组合/布局本身而非类加载。**不采用** |
| **classpath 重排**（把 `ui-desktop` 等热 jar 提到 205 个 jar 的最前） | 实测**变慢 197 ms（−7.8%）**：热 jar 前置后，DI 期间需要的 domain/data 类反而被推后。假设被证伪，**不采用** |
| `-XX:TieredStopAtLevel=1` | 抑制 JIT 编译抢占，收益不稳定，且影响长期运行性能，不采用 |
| `-XX:+UseSerialGC -Xms256m` | 差异在噪声内，不采用 |
| `-Dskiko.renderApi=DIRECT3D` | 差异在噪声内（Skia 已能自行选择后端），不采用 |

保留 `-Xlog:class+load` 分析能力：基线加载 **10 382 个类**，分布在 2.7 s 内，
是剩余最大的单项成本，也是后续可继续挖掘的方向。

## 结果

同机**交替** A/B（基线与优化版逐次交错运行，抵消磁盘与后台负载漂移），
各 6 次，warm profile，指标由应用自报：

| 指标 | 基线中位数 | 优化后中位数 | 改善 |
|---|---|---|---|
| **应用窗口出现**（进程启动 → 第一帧绘制） | 4376 ms | **2360 ms** | **−46.1%（−2016 ms）** |
| **真实界面可用**（进程启动 → 书库界面渲染完成） | 4376 ms | 3642 ms | −16.8%（−734 ms） |

> 多轮重复测量（不同机器负载下）窗口口径稳定在 **−41% ~ −47%**，
> 真实界面口径稳定在 **−14% ~ −24%**。上表取机器空闲时的读数。

> 口径说明：基线版第一帧即真实界面；优化版第一帧是中性占位（进度指示），
> 真实界面在依赖图就绪后渲染。因此「窗口出现」与「真实界面可用」必须分开看，
> 只报前者会高估收益。

优化后阶段分解（一次实测，文件缓存热）：

| 阶段 | 耗时 |
|---|---|
| 进程 → JVM 启动 | ~55 ms |
| JVM → `main-entry` | ~160 ms |
| `main-entry` → 窗口创建开始 | ~190 ms |
| 依赖图构建（与图形管线并行） | ~920 ms |
| 窗口创建 → 第一帧（占位） | ~2350 ms |
| 真实界面组合 | ~940 ms |

### 收益结构

- **省下的 1.75 s 来自“不再串行”**：依赖图构建现在与 Compose/Skia 图形管线初始化
  完全重叠，窗口提前 1.75 s 出现。
- **真实界面只快 0.6 s 的原因**：真实界面首次组合本身约 0.9–1.3 s，基线把它折进
  第一帧，优化版它是占位之后的第二段，绝对量相同。实测预加载全部 UI 类对这段耗时
  **零影响**（瓶颈是组合/布局本身，不是类加载），故未采纳该无效改动。
- 依赖图内部也已压缩：数据库驱动并行化后 `di-data` 从 305 ms 降到 59 ms。

### 变成可回归的指标

- `app-desktop/performance/startup-budget.json`：参考机器中位数 + 容差 + 历史记录。
- `scripts/check-desktop-startup.ps1`：测量 → 比对预算 → 产出报告；`-Enforce` 时超预算即失败。
- CI（`.github/workflows/desktop-ci.yml`）每次桌面构建后采集并上传报告、写入 job summary
  （共享 runner 噪声大，CI 只报告不拦截；发布前用 `-Enforce` 本地强制）。
- 使用说明见 `docs/automation/TEST_GUIDE.md` 的「启动性能预算」一节。

> 顺带修掉一个测量工具自身的缺陷：PowerShell 的 `[int]1.5` 采用银行家舍入（→2），
> 使奇数样本的中位数被算成最大值。三个脚本的中位数实现已改为 `Floor`。

### 剩余成本与下一步

| 成本 | 量级 | 状态 / 方向 |
|---|---|---|
| 依赖图构建 | ~1.2 s | 与图形管线并行，已不阻塞窗口；尾部约 313 ms 是 eager 实例化，可改为惰性解析 |
| 窗口创建 + Skia/D3D 图形管线 | ~1.48 s | Compose Desktop 的固有成本；`skiko.renderApi` 实测无收益 |
| **真实界面首次组合（其中 ~2800 个类的加载与校验占绝大多数）** | **~1.04 s** | **动态 CDS 归档实测可省 820 ms（−40%），但被工具链卡住（见「第四轮」）** |
| JVM 冷启动 | ~0.25 s | 动态 CDS / 类路径瘦身均已实测证伪或无收益 |

### 第二轮：用 JFR 定位剩余成本（2026-10-22）

前一轮的成本分解来自埋点分段，仍是「按阶段归因」。本轮改用 **JFR 采样**直接看线程在做什么，
结论与直觉差别很大，值得单列：

- 打包用的 jlink 运行时**不含 `jdk.jfr` 模块**（JVM 直接报 `Module jdk.jfr not found` 而拒绝启动），
  因此剖析改为「用系统 JDK + 同一份 classpath 启动同一份生产代码」，只替换运行时镜像。
- 启动窗口（前 3.6 s）内 **Java 执行采样只有 41 次（约 0.4 s CPU）**，UI 线程
  `AWT-EventQueue-0` 只有 13 次采样。**启动是「等待」瓶颈，不是「计算」瓶颈**：
  EDT 大部分时间停在 `sun.awt.windows.WToolkit.eventLoop()`（等 AWT 事件），
  `skiko-dispatcher-to-block-on` 停在 `Direct3DContextHandler.flush()`（等 GPU）。
- 由此排除了一批「看起来很重但其实在并行空转」的怀疑项：
  - `Net.accept` 是单实例 broker 的 `ServerSocket` 空闲等待，不是成本；
  - `ProcessHandleImpl.getProcessPids0` 是进程枚举，不是热点。

### 第二轮被证伪的路线：Windows 原生 DPAPI 凭据后端

JFR 的 `jdk.ProcessStart` 事件暴露了一个真实缺陷：启动期应用会拉起 **60 个 `powershell.exe`**，
每个约 220 ms。调用栈是：

```
PlatformCredentialBackend.loadWindows → DesktopRecordCipher.key → EncryptedFileSyncSecureStore.readRecord
```

即**同步安全存储每读一条加密记录就重新向操作系统取一次包装密钥**，而 Windows 侧取密钥是
「拉起 PowerShell 跑 DPAPI 脚本」。缺陷是真的，但它**不在关键路径上**：

- 实现方案：新增 `WindowsDpapiCredentialBackend`，用 JNA 的 `Crypt32Util` 直接调
  `CryptProtectData/CryptUnprotectData`，存储格式（Preferences 中的 base64 DPAPI blob）保持不变，
  PowerShell 实现保留为回退。
- 验证：新增 `WindowsDpapiCredentialBackendTest`（含「不得拉起外部进程」与
  **新旧格式双向互读**的集成测试），既有 `PlatformCredentialBackendTest`（14 用例）
  与 `DesktopSyncSecureStoreTest` 全部不改即通过。
- **实测结论：不采用。** 同构建路径（都用 `createDistributable`）交替 A/B、8 轮：

  | 指标 | 旧实现（60 次 PowerShell） | 原生 DPAPI | 结论 |
  |---|---|---|---|
  | 应用窗口出现（中位数） | 1954 ms | 2223 ms | **+269 ms（变慢）** |
  | 真实界面可用（中位数） | 3184 ms | 3587 ms | **+403 ms（变慢）** |

  阶段分解显示差异不在依赖图阶段（两侧 `di-ui` 基本相同），而在 **UI 线程阶段**
  （首帧 +80 ms、真实界面组合 +100 ms）。本机 32 逻辑核，60 个 PowerShell 进程本就并行、
  不在关键路径上；而原生路径把 JNA 的首次原生加载带进了启动期，与 UI 线程上的
  `Platform.isWindows()` / `dwmapi` 调用产生额外耦合。
- **结论：缺陷保留记录、方案不采用**，避免后人重复试错。

### 第二轮：把剩余成本从「猜测」变成「可核对的数据」

- 新增 `scripts/profile-desktop-startup.ps1`：用系统 JDK + 打包 classpath 启动应用并采集 JFR，
  按「线程 / 栈顶帧 / 文件读取 / 进程拉起」聚合；`scripts/analyze-jfr-startup.py` 做时间窗切片。
- 结论已写回本文，避免再次把「并行空转」误判成关键路径。

### 第二轮发现的真实回归：SQLite WAL 改变了「入队后清空选择」

这一条不是靠速度数据发现的，而是靠**全量模块测试与纯净 HEAD 的逐项对照**发现的：

| 对照 | 纯净 HEAD | 带 WAL 的候选版 |
|---|---|---|
| `LibraryOptionsInteractionTest` | 29 用例 / 0 失败 | 29 用例 / **1 失败** |

失败断言是 `accepted enqueue clears selection`：

```kotlin
assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) }, "accepted enqueue clears selection")
```

机制：`LibrarySelectionState.captureClear()` 返回的回调带**修订号校验**
（`if (revision == acceptedRevision) clear()`）。开启 WAL 后数据库写入时序改变，
书库状态多发布了一次修订号 → 已捕获的清空回调失效 → **下载入队成功后选择没有被清空**。
这不是测试问题，而是真实的产品行为回归（用户下完载后选择仍留在界面上）。

二分定位：临时注释掉两条 PRAGMA → 用例恢复通过；恢复 → 再次失败。
**处理：移除 `PRAGMA journal_mode = WAL` 与 `PRAGMA synchronous = NORMAL`**，回滚日志模式保持不变。
本次也证明了「全量对照」的价值：这条回归在启动速度指标上完全看不出来（窗口与真实界面时间都在噪声内），
只有行为测试能抓住。

### 第二轮修掉的自测污染：新增的 bootstrap 测试让无关用例失败
全量对照里还剩一条「新增失败」：`DesktopRecoveryPackageInstallerTest` 报
`UncaughtExceptionsBeforeTest`。追下去发现根因在本轮**新增的**测试：

```
DesktopOwnerBootstrapTest.a prepared owner is published to the window
  → DesktopOwnerBootstrapController.publishRuntime
    → DesktopAppLockTestStateBinding.<init>
      → appLock.state.collect { ... }        ← 真实的数据库查询流
        → SQLiteException: [SQLITE_CANTOPEN] Unable to open the database file
```

- 起因：测试用 `mockk(relaxed = true)` 伪造 `DesktopAppLock`，而该类的 `state` 是
  **final 属性**，mock 无法拦截，真实的（数据库支撑的）流被收集，异常从
  `Dispatchers.Unconfined` 的应用作用域逃逸成未捕获异常，**污染了同 JVM 内无关的用例**。
- 处理：
  1. 测试改用仓库既有写法（`SecuritySettingsWiringTest` 同款）：真实 `DesktopAppLock` +
     `DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend()))`；
  2. 同一条测试还依赖「别的测试恰好填充过全局 Injekt 容器」，属于顺序相关的隐性不稳定，
     改为**不发布 owner**（`awaitCancellation()`），只验证「窗口先于依赖图」这一件事；
  3. 生产侧加固：`DesktopAppLockTestStateBinding` 只是把锁状态镜像到测试接口，
     失败**不得**逃逸成应用作用域的未捕获异常（取消仍按原样抛出）。

## 第三轮：外壳先行首屏（2026-10-22）

### 先把启动时间切到「可归因」的粒度

前两轮的分段仍缺两个关键点：**窗口内容的首次组合**与**真实界面组合的开始**分别发生在
什么时候。本轮补了两个埋点（`content-composition`、`ui-compose-start`），
一次实测（安静机器）得到：

| 阶段 | 时刻 | 段耗时 | 含义 |
|---|---|---|---|
| 进程 → JVM | 51 ms | | |
| `main-entry` | 313 ms | | |
| broker 选主 | 501 ms | | |
| `compose-application` | 515 ms | | Compose `Application` 开始 |
| `di-begin` | 619 ms | | 依赖图在后台线程并行构建 |
| `di-ui` | 1833 ms | **1214 ms** | 依赖图构建完成 |
| `owner-ready` | 1898 ms | | |
| **`content-composition`** | **1995 ms** | **1480 ms** | **窗口创建 + 图形管线 + 内容的首次组合** |
| `first-frame` | 2387 ms | 392 ms | 第一帧真正绘制（占位页） |
| `ui-compose-start` | 2427 ms | 40 ms | 真实界面开始组合 |
| `ui-ready` | 3470 ms | **1043 ms** | 真实界面组合完成 |

两个关键事实：

1. **依赖图在 1833 ms 就绪，而窗口内容的首次组合发生在 1995 ms** —— 依赖图其实早就好了；
2. 但内容读到的是 **515 ms 时在外层作用域采集的旧状态**（`collectAsState()` 在
   `Application` 作用域里订阅），所以首次组合仍然渲染占位页，真实界面要等到
   第二帧（2427 ms）才开始组合。**这就是「外壳先行」能摘到的收益点。**

### 实施：让窗口内容在组合时读取当前状态

把 `bootstrap.state.collectAsState()` 从 `Application` 作用域移进窗口内容：

```kotlin
// 之前：状态在 Application 作用域采集，内容首次组合时仍是 515 ms 的旧值
val bootstrapState by bootstrap.state.collectAsState()   // Application 作用域
DesktopMainWindow(...) { DesktopOwnerBootstrapContent(bootstrapState) { ... } }

// 之后：内容在组合时采集到的就是当前值
DesktopMainWindow(...) {
    val bootstrapState by bootstrap.state.collectAsState()  // 内容作用域
    DesktopOwnerBootstrapContent(bootstrapState) { ... }
}
```

`collectAsState()` 的初值就是 `StateFlow` 的当前值，所以内容首次组合时：
依赖图已就绪 → **直接组合真实界面**，占位页不再出现，也不再需要「占位帧 + 真实帧」
两次绘制。依赖图还没就绪时（更慢的机器、更大的书库）仍然回落到占位页，行为不变。

### 实测（同机交替 A/B，各 6 次，`0.11.20.12` vs 本轮）

| 指标 | 改动前 | 改动后 | 变化 |
|---|---|---|---|
| 第一帧绘制（首帧是什么） | 2326 ms（占位页） | 2939 ms（**真实界面**） | +613 ms（首帧变成真实界面） |
| **真实界面可用** | 3480 ms | **3344 ms** | **−136 ms** |
| 真实界面可用（最坏一次） | 10113 ms | 4185 ms | 尾部明显收窄 |

> 说明：改动前用户先看到占位页（2.3 s），真实界面在 ~3.9 s 才上屏；
> 改动后占位页**不再被绘制**，第一帧就是真实界面，真实内容提前 ~0.1–0.6 s 上屏，
> 且不再有「占位帧 + 真实帧」两次绘制带来的尾部抖动。

## 第四轮：把「类加载」确认为剩余成本的真正来源（2026-10-22）

给启动埋点补了两个标记后，阶段分解已经能落到可动手的粒度：

| 阶段 | 时刻 | 段耗时 | 含义 |
|---|---|---|---|
| `compose-application` | 515 ms | | Compose `Application` 开始 |
| `di-begin` | 619 ms | | 依赖图在后台线程并行构建 |
| `di-ui` | 1833 ms | **1214 ms** | 依赖图构建完成 |
| `owner-ready` | 1898 ms | | |
| `content-composition` | 1995 ms | **1480 ms** | 窗口创建 + 图形管线 + 内容的首次组合 |
| `first-frame` | 2387 ms | 392 ms | 第一帧真正绘制 |
| `ui-compose-start` | 2427 ms | 40 ms | 真实界面开始组合 |
| `ui-ready` | 3470 ms | **1043 ms** | 真实界面组合完成 |

再用 `-verbose:class` 把类加载与这些阶段对齐（对齐方式：埋点的
`jvmStartEpochMs - processStartEpochMs`），得到：

| 区间 | 加载的类数 |
|---|---|
| 首次组合之前 | **8075** |
| 首次组合 → 真实界面组合开始 | 1328 |
| **真实界面组合期间** | **2797** |
| 真实界面可用之后 | 2329 |
| 合计 | 12416 |

**结论：真实界面的那 1043 ms 基本是「加载并校验 2797 个类」。** 这批类横跨
Compose 文本/保存机制（`androidx.compose.ui.text.SaversKt` 一个文件就 62 个类）、
kotlinx.serialization、data/domain 层与 JDK 自身，不是单一热点。

> 顺带纠正了上一轮的一个错误结论：「后台预加载全部 UI 类对真实界面组合零影响」。
> 那次实验比对的是 `di-ui` 这个**依赖图**标记，而不是 `ui-ready`，所以它根本没有测到
> 组合耗时。**类加载确实在关键路径上。**

### 动态 CDS 归档（实测有效，但被工具链卡住）

既然成本是类加载，最直接的手段就是让 JVM 跳过解析与校验。

**实测（参考 JDK，同一份打包 classpath，`scripts/compare-cds-archive.ps1`）**：

| 口径 | 无归档 | 有动态 CDS 归档 | 变化 |
|---|---|---|---|
| 真实界面可用（`ui-ready`） | 2031 ms（中位数） | **1211 ms** | **−820 ms（−40%）** |

归档本身 86.9 MB，由 `scripts/probe-cds-archive.ps1` 产出（跑一次应用、正常关闭窗口、
JVM 退出时落盘）。

**上一轮「归档未生成」的真实原因**：不是方案不成立，而是两个工具链细节：

1. **打包用的 jlink 运行时没有基础 CDS 归档**，JVM 会直接拒绝带
   `-XX:ArchiveClassesAtExit` 启动：
   `ArchiveClassesAtExit is unsupported when base CDS archive is not loaded`。
   完整 JDK 自带 `bin/server/classes.jsa`，所以用完整 JDK 跑同一份 classpath 就能产出归档。
2. 归档必须在 **JVM 退出阶段**落盘。应用本身没问题（关闭窗口后 1 秒退出，
   `scripts/check-desktop-exit.ps1` 可验证），但探测脚本最初没有把关闭请求送到真正
   带窗口的进程上，进程活着 → 归档永远不写。修正后一次产出 86.9 MB。

**要随交付包一起用，还差一步**：归档必须由**交付所用的 jlink 运行时**产出，而
「基础归档」必须与该运行时的模块集一致：

- 直接把完整 JDK 的 `classes.jsa` 拷进裁剪后的运行时 → **被拒绝**
  （模块集不匹配，交替 A/B 实测无收益：`scripts/compare-base-archive.ps1`，
  真实界面可用 3167 ms → 3096 ms，噪声内）；
- 换成**全模块**的 jlink 镜像（`--add-modules ALL-MODULE-PATH`）→ **仍被拒绝**：
  基础归档绑定的是它构建时的 **jimage**（模块镜像文件本身），jlink 产物的 jimage 与
  JDK 自带的 jimage 指纹不同，因此不匹配；
- 本机 Temurin 21.0.11 的 `jlink` **没有** `--generate-cds-archive` 选项
  （`jlink --help` 全部选项已逐项核对），因此无法为自建镜像生成匹配的基础归档。

**因此本轮不把它放进交付包**，而是把结论、脚本与阻塞点写清楚：

| 要落地还需要 | 说明 |
|---|---|
| 让运行时带上面��「匹配的基础归档」 | 目前只有一条路：直接使用**完整 JDK 的模块镜像**（`lib\modules` 140 MB，比裁剪版多约 80 MB），基础归档随之可用 |
| 每次构建重新产出归档 | 归档与 classpath、类字节强绑定，改动代码后必须重生成；产出方式是**构建时真的启动一次应用并正常退出**（`scripts/check-desktop-exit.ps1` 可验证退出行为） |
| 分发包体积 | 运行时 +80 MB、应用归档 +87 MB，合计约 +170 MB（当前 195 MB → 约 365 MB） |

预期收益已经用参考 JDK 量到：**真实界面可用 −820 ms（−40%）**。

> 这三项属于**产品取舍**（用分发包体积换不到 1 秒的启动），需要用户决定，因此本轮不擅自实施。
> 脚本已经就绪：`scripts/probe-cds-archive.ps1`（产出）、`scripts/probe-cds-archive-packaged.ps1`
> （用打包运行时产出）、`scripts/install-cds-base-archive.ps1`（给运行时装基础归档）、
> `scripts/compare-cds-archive.ps1`（收益 A/B）。

### 第五轮：不用归档也能摘到的类加载收益（后台预热）

动态 CDS 需要工具链支持，但「类加载在关键路径上」这个结论本身可以直接用。
既然真实界面组合期间要加载那 2797 个类，就**在依赖图构建期间用后台线程先把它们读进来**：

- `scripts/generate-startup-warmup.ps1`：跑一次带 `-verbose:class` 的启动，收集
  「窗口内容首次组合 → 真实界面可用」区间内加载的类，剔除每次运行地址都会变的合成 lambda
  （`$$Lambda/0x...`），得到 **3406 个稳定类名**（Compose 706、桌面端 653、
  sun.security 403、org.mozilla 212、序列化 92 …），存为
  `app-desktop/src/main/resources/desktop-startup-warmup.txt`。
- `DesktopStartupClassWarmUp`：用 `Class.forName(name, false, loader)` **只加载不初始化**
  （不提前触发任何静态初始化），无法解析的类直接跳过——清单只是提示，永远不会让启动失败。
- 接入点：`Main.kt` 的 bootstrap 作用域，在依赖图之前用
  `Thread.MIN_PRIORITY` 的守护线程启动，**不与依赖图和首帧争抢优先级**。

**实测（同机交替 A/B，各 6 次，0.11.20.13 vs 0.11.20.14）**：

| 指标 | 0.11.20.13 | 0.11.20.14 | 变化 |
|---|---|---|---|
| 真实界面可用 | 2915 ms | **2667 ms** | **−248 ms（−8.5%）** |
| 第一帧绘制 | 2352 ms | 2678 ms | +326 ms（见下） |

> 「第一帧」变差不是退化：改动前依赖图经常来不及，窗口先画出**占位页**（2.3 s），
> 真实界面到 3.0 s 才上屏；改动后依赖图更早就绪，**第一帧直接是真实界面**（2.7 s），
> 用户看到真实内容的时刻提前了 ~0.25 s，而且不再有「占位帧 + 真实帧」两次绘制。

单次同跑对比的阶段分解（同一台机器、同一轮）：

| 阶段 | 无预热 | 有预热 |
|---|---|---|
| 依赖图完成（`di-ui`） | 1606 ms | **1443 ms** |
| 真实界面组合（`ui-compose-start` → `ui-ready`） | 1038 ms | **774 ms** |

预热既把**组合期**的类加载搬走，也让**依赖图**受益（它要用的类也被提前读进来了）。

> 预热是「把工作提前」，不是「消灭工作」：在单核或极低配机器上它会与关键路径争 CPU。
> 用最低优先级守护线程承载，就是为了让这种机器上关键线程始终优先。

### 第六轮：把启动期没人用的东西从启动期拿掉

依赖图尾部还有一段 eager 构建（`di-ui:screen-model` → `di-ui` 约 305 ms）。逐个核对后发现：

- `DesktopJsEngine` 在 DI 里注册之后**整个主代码没有任何消费点**（只有它自己的单元测试直接
  `DesktopJsEngine()`）。而它的构造并不轻：`Context.enter()` 建 Rhino 解释器、
  `initSafeStandardObjects()` 初始化整套标准库、再解释执行一段 btoa/atob polyfill 脚本。
  **每次启动都在为一个用不到的功能建一个脚本引擎。**
- 处理：改为 `Injekt.addSingletonFactory { DesktopJsEngine() }` 惰性注册。
  语义已由新增的 `DesktopLazyRegistrationTest` 锁定：**工厂只创建一次并缓存**
  （与原来的 `addSingleton(instance)` 行为一致），因此共享语义不变，只是把成本推迟到
  第一次真正求值脚本时。
- 实测（同一轮、同一台机器的阶段分解）：依赖图完成 `di-ui` **1444 ms → 1369 ms（−75 ms）**，
  启动期加载的类 **12415 → 12353（少 62 个）**。端到端 A/B 的中位数差（−79 ms）落在
  这台机器 ±400 ms 的波动里，但阶段分解显示这是**结构性的**（真实少了工作），因此采纳。

依赖图尾部剩下的部分（同步运行时、阅读器运行时工厂、备份调度器等）**结构性 eager**：
`DesktopAppRuntime.create(...)` 接收的是具体实例而不是工厂，把它们改成惰性需要改动运行时
的构造契约——那是 DI 骨架级改动，而仓库目前有 86 个既有失败、没有绿色基线来证明「零回归」，
因此本轮不做，只把结论写在这里。

### 第七轮：把「谁在关键路径上」问到底（2026-10-22）

前六轮都是按阶段归因。这一轮换一个问题：**启动过程中，UI 线程到底在干什么？**

JFR 的采样显示进程整体很闲（启动窗口内 Java 执行采样只有 41 次），但采样器
**采不到阻塞在原生方法里的线程**——而首帧那 860 ms 恰好就是原生阻塞。于是改用
**线程栈采样**（`scripts/capture-desktop-thread-dump.ps1`，用 `jcmd Thread.print`
在启动过程中定点抓栈），得到的 EDT 时间线是：

| 时刻 | EDT 在做什么 |
|---|---|
| 600 ms | Compose 运行时静态初始化 |
| 700 ms | AWT 图形环境初始化（枚举显示器） |
| 900 ms | AWT 字体加载 |
| 1000 ms | Compose `RootNodeOwner`（Skia surface） |
| 1100 ms | Skiko `Direct3DRedrawer.chooseAdapter`（选 GPU） |
| 1400 ms | 应用图标解码 |
| 1800 ms | 窗口内容布局 |
| 1900 ms | Skia 字体缓存注册 |
| **2000 ms** | **kotlin-reflect 元数据反序列化** |
| 2100 ms | Skia `Direct3DContextHandler.flush`（原生） |
| 2200 / 2450 ms | 书库快照更新 |
| 2500–2900 ms | 空闲 |

**结论：EDT 从 500 ms 连续忙到 2450 ms，之后完全空闲。启动不是「等 IO」，是「EDT 在干活」。**

#### 根因：kotlin-reflect 被导航库的一个调用拖进关键路径

用 `-Xlog:class+load` 与埋点对齐后，**真实界面组合期间加载 3360 个类，其中 841 个
来自 `kotlin-reflect-1.8.22`**。触发点是导航库（Voyager）：

```
cafe.adriel.voyager.core.model.ScreenModelStore.getKey(...)
  → Reflection.getOrCreateKotlinClass(ScreenModel::class)
  → cafe.adriel.voyager.core.platform.KClassEx_jvmKt.getMultiplatformName(KClass)
  → KClass.getQualifiedName()
```

`kotlin.jvm.internal.Reflection.getOrCreateKotlinClass()` 的行为取决于 classpath 上
有没有 `kotlin-reflect`：**有**就返回完整实现 `KClassImpl`，于是
`getQualifiedName()` 要反序列化该类的 protobuf 元数据，**连带加载整个反射实现
（841 个类）**；**没有**就退回轻量的 `ClassReference`，`getQualifiedName()` 只是
`jClass.getName()`，几乎免费。

而 `kotlin-reflect` 在桌面端的来源是 `io.ktor:ktor-server-core-jvm` 的传递依赖，
版本是 **1.8.22**，而 `kotlin-stdlib` 是 **2.3.10**——两者并不匹配。

处理方式（两条路都评估过）：

| 方案 | 结论 |
|---|---|
| 从运行期 classpath 移除 `kotlin-reflect` | 收益明确，但 `ktor-server-core` / `ktor-server-host-common` 有 3 个类引用 `kotlin.reflect.full.*`；桌面端本不该带 Ktor 服务端栈，移除属于依赖治理，**本轮不做**（见「下一步」） |
| **在预热线程上提前把反射实现初始化掉** | **采用**。反射实现是**一次性**的引导成本，提前在后台线程付掉，EDT 上就只剩一次缓存命中 |

具体实现（`DesktopStartupClassWarmUp`）：

- 预热线程先解析 `cafe.adriel.voyager.core.model.ScreenModel` 的限定名，
  这会把反射实现（841 个类 + protobuf 元数据解析）整体拉起来；
- 预热起点从「broker 选举之后」**提前到 `main()` 入口**（实测 383 ms），
  多出约 230 ms 的时间差，用来在依赖图构建期间把类读完；
- 预热清单**不再扩大**：实测把清单从 3406 条扩到 3721 条（把组合期窗口的类并进去）
  会让**首帧慢 233 ms**——预热线程与 EDT 争用类加载锁，清单越长、抢得越久。
  因此清单维持 3406 条，生成脚本改为**合并**而非替换（避免丢失已覆盖的类）。

> 效果可以直接在类加载日志里看到：真实界面组合期间加载的类
> **3360 → 502（−85%）**。清单本身的 3406 条并没有变，变的是**它跑完了**。

#### 实测（同机交替 A/B，各 6 次，`0.11.20.15` vs `0.11.20.16`）

| 指标 | 改动前 | 改动后 | 变化 |
|---|---|---|---|
| 真实界面可用（中位） | 2662 ms | **2338 ms** | **−324 ms（−12.2%）** |
| 真实界面可用（离散度） | 2275–3166 ms（**双峰**） | 2243–2553 ms（收敛） | 竞争消失 |
| 第一帧绘制（中位） | 3127 ms | 3077 ms | −50 ms（−1.6%） |

> 改动前「真实界面可用」是**双峰**的：一半的运行在 ~2300 ms，另一半在 ~3100 ms。
> 原因是真实界面的组合与阻塞式 D3D flush 撞车——Ready 组合刚要派发
> `ui-ready` 标记，EDT 就被首帧的原生 flush 占住，标记因此被推迟近 800 ms。
> 预热挪走反射引导后，组合在 flush 之前完成，两种结果不再出现。

#### 本轮同时修掉的测量缺陷

`scripts/measure-desktop-startup.ps1` 之前在 trace 文件里**读到 `first-frame`
就用它冒充 `ui-ready`**，而应用是在**每次打点时重写整个文件**，轮询会读到
写了一半的快照，于是同一个版本有时报 2300 ms、有时报 3100 ms。修法是：
**等 `first-frame` 与 `ui-ready` 两个标记都落盘，再读一次已稳定的文件**；
`ui-ready` 缺失时不再用首帧冒充（`check-desktop-startup.ps1` 同步按「真实界面
未就绪」判失败）。

#### 剩余成本（已定位，未动）

| 成本 | 量级 | 说明 |
|---|---|---|
| **真实界面的首次绘制** | **~860 ms** | Skia `Direct3DContextHandler.flush` 的原生阻塞。本机是 RTX 3070 + 正常驱动，**不是**软件回退。现有代码在「最大化」时已经会在 `windowOpened` 里提前 `validate()+renderImmediately()`（本机持久化几何本就是 maximized），也就是说**提前绘制这条路早已生效**，剩下的就是真实书库界面本身的绘制成本（GPU 工作 + 着色器编译），属于真实工作量 |
| 窗口创建 + 图形管线 | ~900 ms | AWT 图形环境、字体、Skia surface、D3D 适配器选择 |
| 依赖图构建 | ~980 ms | 后台线程，与窗口创建并行 |
| 真实界面组合 | ~324 ms | 本轮从 ~600 ms 降下来的部分 |

#### 本轮证伪的路线

| 路线 | 实测 | 结论 |
|---|---|---|
| 启动埋点每次落盘 | 25 次整文件重写合计 **5.58 ms**（每次 0.22 ms） | 整条路线不值得动，**证伪** |
| `-Dskiko.renderApi=SOFTWARE` | 首帧中位 2771 → 2648 ms，可用界面在噪声内 | 绕开 D3D 的收益被 CPU 光栅化抵消，且属渲染后端取舍，**不采用** |
| 预热清单合并扩张 | 首帧 **+233 ms**（回归） | **回退**，清单维持 3406 条 |
| 依赖图尾部惰性化（约 305 ms） | 需改 DI 骨架契约 | 仓库无绿色基线，**仍不做** |

### GraalVM native-image 可行性评估（结论：与本项目架构不兼容）

「用 AOT 预编译追平安卓端」是唯一在原理上能把 2.2 s 压到 ~0.1 s 的手段，评估结论如下：

1. **Compose Desktop 能否 native-image？** JetBrains 自己的工程师明确记录：Compose 应用
   **不能**直接用 GraalVM native-image 打包，原因有二——native-image 对 AWT 的支持只在
   特定平台/发行版可用；即便可用，JetBrains 对 AWT 共享库的处理方式也会导致失败
   （[bell-sw/Liberica#94](https://github.com/bell-sw/Liberica/issues/94)、
   [JetBrains/skiko#580](https://github.com/JetBrains/skiko/issues/580)、
   [oracle/graal#4124](https://github.com/oracle/graal/issues/4124)）。
   社区有可跑的样例（如
   [kdroidFilter/compose-graal-vm](https://github.com/kdroidFilter/compose-graal-vm)、
   [kgonia/java-spring-kotlin-swing-native](https://github.com/kgonia/java-spring-kotlin-swing-native)，
   报告 ~0.011 s 启动），但都依赖 **Liberica NIK Full**（含 AWT/Swing 的 native-image
   模块的商业发行版），并需要 tracing agent 收集反射元数据、按平台分别编译、
   随二进制分发 AWT/Skiko 原生库。

2. **对本项目是否可行？—— 否，架构级不兼容。** native-image 是**封闭世界**假设：
   运行期不允许自定义 ClassLoader 与动态类加载。而 Mihon 的扩展系统
   （`DesktopExtensionLoader` → `ExtensionClassLoader`（URLClassLoader）→ `ServiceLoader`）
   正是要在运行期从用户安装目录加载第三方 jar 并实例化其中的 Source 类。
   这条能力在封闭世界下不存在，除非把扩展改造成编译期内置（与「用户可自行安装扩展」
   的产品形态直接冲突）。

3. **结论**：native-image 不适合 Mihon Desktop 的当前形态。若要走到封闭世界，
   前提是放弃运行期扩展加载（改变产品核心能力），或改用自绘渲染栈
   （如 [compose-native-host](https://github.com/letmutex/compose-native-host) 那种
   Swift/C++ 宿主 + Metal/D3D12 直绘路线）——那已属于重写渲染层，不是启动优化。

因此，**现实可达的下一档是安卓式「外壳先行」首屏**（应用层可做，本轮已实施，见
「第三轮：外壳先行首屏」），而要继续逼近安卓端需要更换渲染/运行体系。

> **关于「安卓端 ~1 s」这一说法**：本仓库内**没有**任何安卓冷启动的测量数据，
> 该数字在早期文档里是未标注来源的经验值，本文不再把它当作可比基准使用。
>
> **2026-10-22 已补上安卓侧实测**（`scripts/measure-android-startup.ps1`，结果存档于
> `app-desktop/performance/android-startup.json`）：
>
> | 项 | 值 |
> |---|---|
> | 被测应用 | 本 fork 的 `benchmark` 变体（与 release 同样经过 R8、可 profile、非 debuggable） |
> | 设备 | Android 模拟器 `mihon-api36-task20`，API 36，x86_64，**软件渲染**（`ro.hardware.egl=emulation`） |
> | 测量方式 | 平台自带 `am start -W`（启动请求 → 首帧绘制完成，即 macrobenchmark 的 `timeToInitialDisplay` 同源信号），每次测量前 `am force-stop` |
> | 结果（n=10，全部 COLD） | **min 5250 ms / 中位数 5277 ms / max 5488 ms** |
>
> **这个数字不能直接与桌面端相比**：它是纯软件渲染的 x86_64 模拟器（无硬件 GPU、
> 无真机 ART/JIT 预热、无 baseline profile），真机上的同一应用通常在 1 s 量级。
> 它能证明的是「安卓侧现在有仓库内可复现的测量口径」，而不是「桌面端已经达到安卓水平」。
>
> 仓库自带的 `macrobenchmark` 模块本来可以提供这一基准，但它的目标包名一直是上游的
> `eu.kanade.tachiyomi.benchmark`，与本 fork 的 `app.mihon.desktop.fork` 不符，测量从来没有
> 指向真正的交付应用。本轮已把它改为从 `gradle/android-release.properties` 推导目标包名
> （`app.mihon.desktop.fork.benchmark`）；在有 Play 商店的镜像上可直接用：
>
> ```bash
> ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
>   -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.ColdStartupBenchmark#startupNoCompilation
> ```
>
> 本机可用的系统镜像只有 `google_apis`（无 Play 商店），macrobenchmark 需要的
> Microbenchmark 辅助包装不上，测试会被跳过；因此本轮改用上面的 `am start -W` 口径。
> 另外本机的 `mihon-api36` 镜像无法解析该应用的启动 Activity（`am start` 返回
> `Error type 3 / Activity class ... does not exist`，重装与重启模拟器后依旧），
> 换用 `mihon-api36-task20` 镜像后正常——这是环境问题，不是应用问题。


### 工具清单（本文用到的脚本）

| 脚本 | 用途 |
|---|---|
| `scripts/measure-desktop-startup.ps1` | 单版本冷启动测量（读应用自报埋点 + 原生窗口可见性） |
| `scripts/compare-desktop-startup.ps1` | 交替 A/B 对比（抵消机器负载漂移） |
| `scripts/check-desktop-startup.ps1` | 与启动预算比对并出报告，`-Enforce` 时超预算即失败 |
| `scripts/show-startup-trace.ps1` | 打印启动阶段分解 |
| `scripts/profile-desktop-startup.ps1` | 用系统 JDK + 打包 classpath 采集 JFR |
| `scripts/analyze-jfr-startup.py` | 切分 JFR 采样到启动时间窗并聚合热点 |
| `scripts/jfr-timeline.py` | 把 JFR 采样按时间桶铺成时间线（看「谁在什么时候忙」） |
| `scripts/analyze-class-load.ps1` | 解析 `-verbose:class` 输出，统计类加载分布 |
| `scripts/capture-desktop-thread-dump.ps1` | 启动过程中定点抓线程栈（`jcmd Thread.print`），看 EDT 在干什么 |
| `scripts/compare-desktop-classpath.ps1` | 同一打包产物的 classpath / JVM 参数变体交替 A/B |
| `scripts/generate-startup-warmup.ps1` | 重新生成启动类预热清单 |
| `scripts/probe-cds-archive.ps1` | 用参考 JDK 产出动态 CDS 归档 |
| `scripts/probe-cds-archive-packaged.ps1` | 用打包运行时产出归档（需要运行时自带基础归档） |
| `scripts/compare-cds-archive.ps1` | 有/无归档的启动 A/B |
| `scripts/compare-base-archive.ps1` | 运行时带/不带基础 CDS 归档的启动 A/B |
| `scripts/check-desktop-exit.ps1` | 验证关闭窗口后 JVM 真的退出（归档落盘的前提） |
| `scripts/install-cds-base-archive.ps1` | 给打包运行时安装匹配的基础 CDS 归档 |
| `scripts/measure-android-startup.ps1` | 安卓侧冷启动测量（`am start -W`） |
| `scripts/run-desktop-module-tests.ps1` | 跑全量模块测试并留存 console.log 与 JUnit XML |
| `scripts/compare-test-baselines.ps1` | 两次全量运行的逐用例回归对比 |
| `scripts/refresh-parity-role-evidence.py` | 重新解析 parity 清单的工作区锚点行号 |
| `scripts/check-role-evidence.py` | 独立复现锚点校验（看全部失效项而非首个） |

## 交付

- 交付版本：`0.11.20.18.04688a4`（Windows 未打包应用 + zip 分发包）。
  - 未打包 EXE：`app-desktop/artifacts/windows/Mihon-Desktop-0.11.20.18.04688a4-unpacked/Mihon Desktop.exe`
  - 分发 zip：`app-desktop/artifacts/windows/Mihon-Desktop-0.11.20.18.04688a4-windows.zip`（195,433,485 字节）
  - SHA-256：`a1dbfd9408694fde6015464fb6942c11ce1a7f8b00888cd2945e73f037abd6b9`
- 验收：交付产物启动后窗口出现，书库界面完整渲染（1936×1048，持久化几何恢复），
  截图 `app-desktop/tmp/startup-acceptance-0.11.20.18.png`（窗口标题 `Mihon Desktop 0.11.20.18.04688a4`）。
- **最终启动实测**（同机交替 A/B，`0.11.20.15` vs `0.11.20.18` 交付版，各 8 次）：

  | 对比 | 指标 | 改动前 | 改动后 | 改善 |
  |---|---|---|---|---|
  | 0.11.20.7 基线 → 0.11.20.15 | 第一帧绘制 | 6076 ms | 4624 ms | −1452 ms（−23.9%） |
  | 0.11.20.7 基线 → 0.11.20.15 | 真实界面可用 | 6076 ms | 3698 ms | −2378 ms（−39.1%） |
  | 0.11.20.15 → **0.11.20.18 交付版** | **真实界面可用** | 3236 ms（2447–3744，**双峰**） | **2329 ms**（2295–2571，收敛） | **−907 ms（−28.0%）** |
  | 0.11.20.15 → **0.11.20.18 交付版** | 第一帧绘制 | 3240 ms | **3132 ms** | **−108 ms（−3.3%）** |

  > 这一轮机器满载（基线单次 3.6–6.3 s），绝对值高于安静机器；
  > **同轮交替 A/B 的相对差值才是公允口径**。
  > 「第一帧」的口径自 0.11.20.13 起变化：依赖图就绪时第一帧直接是**真实界面**，
  > 不再是先画一张占位页再被真实界面替换（见「第三轮：外壳先行首屏」）。
  > 「真实界面可用」= 真实界面**组合完成**的时刻；窗口在「最大化」时会先被 DWM cloak，
  > 因此它才是用户真正看到内容的时间。交付版把「组合完成」与「首帧绘制」之间的
  > 差距稳定在约 700 ms——组合提前完成，剩下的是首帧那一次性的 D3D 绘制；
  > 改动前这个顺序是反的（组合被首帧的阻塞式 flush 拖着走，于是出现双峰）。
- 回归证据（2026-10-22，原始日志留存于 `app-desktop/tmp/test-baselines/`）：

  | 对照 | 命令 | 该次运行自身的输出 | 按失败用例（去重）计 |
  |---|---|---|---|
  | 纯净 HEAD 基线 | `scripts/run-desktop-module-tests.ps1 -Label baseline-head` | `3962 tests completed, 86 failed, 16 skipped` | 83 |
  | 上一版候选 | `scripts/run-desktop-module-tests.ps1 -Label candidate6` | `3973 tests completed, 85 failed, 16 skipped` | 82 |
  | **本轮候选** | `scripts/run-desktop-module-tests.ps1 -Label candidate7` | `3977 tests completed, 83 failed, 16 skipped` | 80 |
  | 逐项对比 | `scripts/compare-test-baselines.ps1` | **新增失败 0 项**，修复 2 项 | — |

  > 修复的 1 项是 `DesktopProductCapabilityContractTest`（parity 契约测试由红转绿）。
  > 候选版多出的 11 个用例来自本轮新增的 `DesktopOwnerBootstrapTest`(3)、
  > `DesktopExtensionAsyncLoadingTest`(3)、`DesktopStartupClassWarmUpTest`(3)、
  > `DesktopLazyRegistrationTest`(2)，全绿。
  > 本轮又新增 4 个用例（`DesktopStartupClassWarmUpTest` 3 个 + `DesktopOwnerBootstrapTest`
  > 1 个），把「反射引导已预热」与「预热早于 broker 选举」两件事锁死。
  > 证据目录：`app-desktop/tmp/test-baselines/<label>-<时间戳>/`（含 `console.log` 与 JUnit XML）。

- **parity 契约测试由红转绿**：`DesktopProductCapabilityContractTest` 在纯净 HEAD 上**本来就是红的**
  （`ID 4: SHARED_OR_ADAPTER evidence symbol is not present at .../DesktopAppModule.kt:208`），
  原因是 `parity-manifest.json` 的 `roleEvidence` 行号相对 HEAD 已整体漂移（355 条工作区锚点中
  **50 条**在 HEAD 上就失效），与本次启动优化无关。
  本轮按仓库既有惯例（见 `docs/roadmap/2026-09-18-author-experience-iteration-roadmap.md`
  「只维护既有行号漂移 … 契约测试通过」）新增 `scripts/refresh-parity-role-evidence.py`：
  **只**重新解析 `roleEvidence` 中 `CURRENT_ANDROID` / `SHARED_OR_ADAPTER` / `DESKTOP_CONSUMER`
  的 `line` 字段，**不动** `path`、`symbol`、capability 状态、固定原版证据与
  `actionInventory` 溯源矩阵（后者按固定提交 `95b82fc` 校验 `contextHash`，改动即破坏）。
  修完后 355 条锚点 **0 失效**，5 个 parity 测试类（62 用例）全绿，
  且读固定提交的 `NonReaderActionInventoryContractTest` 仍然通过——证明溯源校验未被削弱。
  > 注意：行号刷新必须在**产品代码定稿之后**做。本轮就因为后续又改了 `Main.kt`，
  > 锚点再次漂移（ID 4 → ID 81），重跑刷新脚本后恢复全绿。
- 过程中修掉三个自查发现的缺陷：
  1. bootstrap 会话的原关闭不是原子操作，窗口关闭协程与生命周期清理可能各关闭一次
     运行时 → 改为 `AtomicReference.getAndSet`。
  2. owner 发布之后的「延后文件恢复 / 下载器启动」被取消时仍会走失败分支，再次关闭
     运行时 → 把发布后的工作移出失败分支并改为只记录不失败（表现为间歇性失败，
     3 次连续重跑确认已根治）。
  3. **SQLite 写前日志（WAL）改变了「入队成功后清空选择」的真实行为** → 移除两条 PRAGMA
     （详见「第二轮发现的真实回归」）。


## 回归保护

- `DesktopOwnerBootstrapTest`：窗口事件循环必须在依赖图仍在构建时进入；
  owner 发布/失败/升级语义。
- `DesktopExtensionAsyncLoadingTest`：扩展后台加载与初始化状态。
- `DesktopAppRuntimeTest`、`SecuritySettingsWiringTest`、
  `DesktopUriSchemeCapabilityTest`：更新为新契约（窗口先于 owner、失败经由
  bootstrap 上报、运行时清理与取消语义保持不变）。

## 书架封面：从「每次重下 + 串行读取」到「首帧即齐活」（2026-10-22）

### 现象与测量口径

启动后书库封面不是一次性出现，而是一张张补齐。用 `scripts/verify-desktop-cover-appearances.ps1`
在首帧之后定点连拍，并计算网格区域的**填充率**（空网格是纯色，有封面时颜色数暴增），
把「逐步加载」变成可比较的数字。基线 `0.11.20.18`：

| 时刻 | 填充率 | 不同颜色数 | 判读 |
|---|---|---|---|
| +100 ms | 0.189 | 48 | 只有分类标签，网格还没有封面 |
| +600 ms | 0.187 | 99 | 封面开始零星出现 |
| +1000 ms | **0.888** | 256 | 网格填满 |
| +2500 ms | 0.891 | 256 | 稳定 |

⇒ **渐进窗口约 400 ms**。

### 根因（三条，都由实测确认）

1. **每次启动都在重新下载封面。** 线程栈采样显示，网格组合期间有 **40 个工作线程
   在做 TLS 握手**（`ConnectPlan.connectTls`）。OkHttp 的磁盘缓存目录
   （`cache/network`，252 MB）里并没有这些封面——它们在**源站 CDN** 上，
   而源站客户端没有磁盘缓存。
2. **Coil 3.4 没有「网络响应 → 磁盘缓存」的写入路径。** 扫描整个
   `coil-core-jvm-3.4.0.jar`：**只有** `ImageLoader` / `RealImageLoader` /
   `RealDiskCache` 引用 `getDiskCache` / `edit`，`EngineInterceptor` 里没有任何磁盘
   缓存写入。Coil 3.4 的磁盘缓存只服务于 `FileImageSource`。
   ⇒ 所以 `%TEMP%\coil3_disk_cache` 一直是空的（实测 0 个文件），
   封面每次都走网络。
3. **OkHttp 的 `DiskLruCache` 索引有单锁，读取被串行化。** 60 张封面按 ~7 ms 一次
   串行读，正好 ≈ 400 ms，与测到的渐进窗口吻合。

### 处理：应用自己的按 key 封面缓存

新增 `DesktopImageCache`（`app-desktop/src/main/kotlin/mihon/desktop/image/DesktopImageCache.kt`）：

- 目录：`%LOCALAPPDATA%\Mihon\cache\image_cache`（与网络缓存同级，**不是**临时目录，
  不会被系统清理）；
- **每张封面一个独立文件**（键 = SHA-256）⇒ 读取天然并行，不再排队在单锁后面；
- `DesktopSourceImageFetcher` 先查本地缓存（命中即返回，`DataSource.DISK`），
  未命中才走网络，并把结果**写回**本地缓存 ⇒ **同一张封面一辈子只下载一次**。

### 实测（同机交替 A/B，6 次，`0.11.20.18` vs 本轮）

| 指标 | 改动前 | 改动后 | 变化 |
|---|---|---|---|
| 真实界面可用（中位） | 2105 ms | **2082 ms** | −23 ms（噪声内，**启动无回退**） |
| 第一帧绘制（中位） | 2783 ms | 3065 ms | +282 ms（见下） |
| **首帧 +100 ms 的网格填充率** | **0.189** | **0.5311** | **首帧已带上本地封面** |
| 网格填满 | 首帧 +1000 ms | 首帧 +1200 ms | — |

> 第一帧那 +282 ms **不是开销，而是内容**：改动前首帧画的是空网格，
> 改动后首帧把本地封面也画了进去（38 张图的纹理上传）。真实界面可用的时间不变。

### 为什么「已看过的封面」能首帧齐活，而「没看过的」不能

本地缓存里只有**用户已经浏览过**的封面。首屏按「最近阅读」排序，其中没被浏览过的
那部分仍然需要下载——这是网络决定的，桌面端无法让一个还没下载过的封面立刻出现。
但**每张封面只会被下载一次**：看过之后它进入本地缓存，此后每次启动它都在首帧里。

诊断（临时打点，随后已移除）确认了缓存的写入路径是好的：命中本地缓存的封面直接
以 `DataSource.DISK` 返回，未命中的走网络并写回缓存。**当前环境里未缓存的那几张
封面下载会失败**（失败发生在写缓存之前，所以既没有日志也没有新文件）——那是源站
侧的问题（源站/会话/防盗链），不是缓存机制的问题；用普通 HTTP 请求直接访问这些
封面地址返回 200，说明网络本身可达。

### 本轮证伪 / 回退的方案（均留有实测数据）

| 方案 | 实测 | 结论 |
|---|---|---|
| **启动期预热封面**（用与网格相同的内存缓存键提前发起请求） | 封面表现与「只加缓存」完全相同（填充率同为 0.5311），而首帧多 +295 ms（预热自身的模型查询与解码和界面抢 CPU） | **回退**：收益本来就来自缓存，预热只是重复付出 |
| **启用 Coil 自己的磁盘缓存** | Coil 3.4 根本没有网络响应的写入路径，缓存目录始终 0 个文件 | **回退**（改为应用自己的缓存） |
| **预热覆盖 180 张** | 预热请求与界面自己的请求争同一个 OkHttp 缓存锁，网格比基线**晚 2.5 s** 出现 | **回退** |
| **让书库页与预热共用同一个模型实例** | 能让两侧算出同一份条目（实测 `category=2 index=1 sort=LAST_READ first=[704,2569,2556]` 完全一致），但缓存方案本身不需要它 | **回退**（改动面最小化） |
| okio 的 `File → Path` 转换 | `java.io.File.toPath()` 成员会遮蔽 okio 的同名扩展，可用形式是 `with(Path) { file.toOkioPath() }` | 记录备查 |

## 滚动预取：把「下一屏」提前装好（2026-10-22）

### 需求

封面缓存解决了**首屏**：看过封面此后每次启动都在首帧里。但用户往下滚动时，
网格仍然是「滚到哪、加载到哪」——首屏之后的封面还是一张张出现。
要求是**首屏 + 滚动预取若干屏**，预热范围**跟着当前分类走**，并且**不影响启动速度**。

### 实现

- 新增 `MangaCoverPrefetcher`（`app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaCoverPrefetcher.kt`）：
  按传入的条目列表构造请求并 `enqueue`，**窗口大小 60 张**（约 1.5 屏）。
- `MangaCoverRequest.kt` 里抽出 `buildMangaCoverRequest(...)`，**网格与预取共用同一个请求**，
  ⇒ 缓存键 `manga-cover:<id>:<version>:<model>` 完全一致，预取的结果能被网格直接复用，
  不会加载两次。
- 接入点：`LibraryComponents.kt` 的 `LibraryGrid`。网格把**当前分类 + 当前排序**的条目
  交给预取，因此预热范围天然跟着当前分类与排序走。
- **窗口跟随滚动**：触发键取「最后一条可见条目的下标」，
  而不是「可见条目数量」——后者在滚动时不变，窗口就不会前移（首版就是踩了这个坑）。

### 实测 1：启动速度不受影响（同机交替 A/B，6 次，`0.11.20.33` vs `0.11.20.36`）

| 指标 | v33（无预取） | v36（有预取） | 变化 |
|---|---|---|---|
| 第一帧绘制（中位） | 2749 ms | 2771 ms | +22 ms（+0.8%） |
| 真实界面可用（中位） | 2106 ms | 2145 ms | +39 ms（+1.9%） |

两次运行的**离散度**（v36 首帧 2668–3103 ms、真实界面 2031–2571 ms）完全覆盖了这个差值，
即**在噪声内**。作为对照，本项目启动优化本身带来的收益是 −907 ms（−28.0%）。

### 实测 2：首屏不受影响，而下一屏被提前装好（冷封面缓存，各 2 轮）

`scripts/verify-desktop-cover-prefetch.ps1`（新增）在首帧后定点连拍，同时记录
**网格填充率**与**封面缓存文件数**：

| 时刻 | v33 填充率 | v36 填充率 | v33 已缓存 | v36 已缓存 |
|---|---|---|---|---|
| +100 ms | 0.5311 | **0.5311** | 0 | 0 |
| +600 ms | 0.5311 | **0.5311** | 6 | 6 |
| +1600 ms | 0.7792 / 0.7809 | **0.7811 / 0.7792** | 38 / 33 | 38 / 46 |
| +2500 ms | 0.7826 / 0.7820 | **0.7816 / 0.7835** | 40 / 39 | 64 / 68 |
| +4000 ms | 0.7841 | **0.7841** | 41 / 42 | 69 / 69 |
| 稳定后 | — | — | **42 / 42** | **69 / 69** |

⇒ **首屏填充率逐点完全一致**（预取没有抢走首屏的网络与解码资源）；
   而**缓存里的封面从 42 张涨到 69 张**（+64%），多出来的正是折叠线以下的那些。
   也就是说：滚动时网格要的那批封面**已经在本地缓存里**，不会再触发下载。

> 60 张的窗口最终只落了 27 张新封面：其余的在源站侧下载失败（与「已知取舍」里的
> 源站/防盗链问题同源），不是预取逻辑没生效——已缓存数稳定停在 69 且不再增长。

### 测试（TDD 红绿闭环）

| 用例 | 作用 |
|---|---|
| `MangaCoverPrefetcherTest`（3） | 预取与网格的缓存键一致；没有封面的条目被跳过；窗口数量上限生效 |
| `LibraryGridPrefetchWiringTest`（2） | 网格真的会为折叠线以下的条目发起请求；**滚动后窗口会前移** |

两个网格侧用例都做过**反向验证**（摘掉接线/退回旧触发键 ⇒ 用例转红）：

- 摘掉 `LibraryGrid` 里的预取调用 ⇒ `the grid loads the covers of the items below the visible ones` 失败；
- 把触发键退回「可见条目数量」⇒ `the window of the prefetch follows the scroll` 失败。

### 本轮修掉的两个真实缺陷

1. **书库 UI 崩溃**：重构 `rememberMangaCoverRequestState` 时把「封面模型为 null」
   从「构建一个 data 为 null 的请求」改成了「返回 null + `requireNotNull`」，
   ⇒ `IllegalArgumentException: Required value was null` 抛在组合期，
   `MangaDetailInteractionTest` 88 个用例挂了 82 个。
   **已恢复原语义**（请求永远返回，`data` 可以为 null）。
   > 教训：被全 UI 共用的公共函数，语义不能顺手改。
2. **预取窗口不随滚动前移**（见上）。

### 全量回归（candidate9 vs candidate8）

| 对照 | 命令 | 该次运行自身的输出 | 按失败用例（去重）计 |
|---|---|---|---|
| 上一版候选 | `scripts/run-desktop-module-tests.ps1 -Label candidate8` | `3984 tests completed, 83 failed, 16 skipped` | 81 |
| **本轮候选** | `scripts/run-desktop-module-tests.ps1 -Label candidate9` | `3989 tests completed, 84 failed, 16 skipped` | 81 |
| 逐项对比 | `scripts/compare-test-baselines.ps1` | 新增失败 **1** 项，修复 **1** 项 | — |

- 多出的 **5 个用例**就是本轮新增的 `MangaCoverPrefetcherTest`(3) 与
  `LibraryGridPrefetchWiringTest`(2)，**全绿**。
- **修复的 1 项**：`DesktopProductCapabilityContractTest`（parity 契约测试由红转绿，
  原因仍是 `Main.kt` 改动带来的 `roleEvidence` 行号漂移，重跑刷新脚本后 355 条锚点 0 失效，
  11 个 parity 测试类全绿）。
- **“新增”的 1 项**：`LibrarySettingsPolicyInteractionTest` 的
  `new library settings search enters actual Root destinations and highlights their persistent controls`。
  这是**既有 flaky**，不是本轮引入：该类用例在**本轮改动之前**的基线里就失败过——
  `candidate-20261022-074559`（1 项）与 `candidate5-20261022-124832`（2 项）里
  失败的就是同一条；单独重跑该类时失败的又换成同类的另一条
  （`actual waiting task explains unmet conditions and keeps modal keyboard ownership`）。
  本轮改动只涉及书库网格与封面请求，不进入设置页的组合路径。

  | 基线 | 该类失败数 | 失败用例 |
  |---|---|---|
  | baseline-head | 1 | new periodic smart metadata settings search … |
  | candidate | 1 | **new library settings search enters actual Root destinations …** |
  | candidate2 | 1 | new periodic smart metadata settings search … |
  | candidate3 / candidate4 | 0 | — |
  | candidate5 | 2 | 两条搜索用例 |
  | candidate6 | 1 | new periodic smart metadata settings search … |
  | candidate7 / candidate8 | 0 | — |
  | candidate9（本轮） | 1 | **new library settings search enters actual Root destinations …** |

## 第九轮：把完整反射实现从运行期 classpath 上摘掉（2026-10-22）

### 前提：先查清 Ktor 服务端到底有没有人用

上一轮把反射引导挪到预热线程，消除了它在 **EDT** 上的成本，但**依赖本身还在**：
`kotlin-reflect 1.8.22` 只由 `io.ktor:ktor-server-core-jvm:2.3.12` 一条路径带进
`jvmRuntimeClasspath`（整张依赖图里 `kotlin-reflect` 只出现 1 次）。

而 Ktor 服务端栈在桌面端**只有一个消费者**：`mihon.desktop.test.*` 的 **Test Mode**
HTTP 服务端（`embeddedServer(Netty, …)`）。正常启动根本不会碰它。

原先搁置的理由是「`ktor-server-core` / `ktor-server-host-common` 有 3 个类引用
`kotlin.reflect.full.*`」。这个顾虑用**实验**而不是推测来消除：

1. **同一产物的 classpath 变体对比**（`scripts/compare-desktop-classpath.ps1`）：
   把 `kotlin-reflect` 从 classpath 里摘掉，应用照常启动。
2. **Test Mode 服务端实测**：带 / 不带 `kotlin-reflect` 两种 classpath 各起一次
   Test Mode，`GET /test/state` 返回**完全相同**的 346,197 字节 JSON，**零报错**。
   ⇒ Ktor 的反射用法不在 Test Mode 的路径上。

### 实现

`app-desktop/build.gradle.kts`：五个 Ktor 依赖统一排除 `kotlin-reflect`。

```kotlin
listOf(
    "io.ktor:ktor-server-core:2.3.12",
    "io.ktor:ktor-server-netty:2.3.12",
    "io.ktor:ktor-server-cio:2.3.12",
    "io.ktor:ktor-client-core:2.3.12",
    "io.ktor:ktor-client-okhttp:2.3.12",
).forEach { dependency ->
    implementation(dependency) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
}
```

顺带把预热清单里两条已经不可能加载的条目
（`kotlin.reflect.jvm.internal.KPackageImpl`、`KPackageImpl$data$1`）删掉（3406 → 3404）。

### 实测

**同一产物、只换 classpath**（`compare-desktop-classpath.ps1`，交替 8 次）：

| 指标 | 带 kotlin-reflect | 去掉 | 变化 |
|---|---|---|---|
| 第一帧 | 2695 ms | **2540 ms** | **−155 ms（−5.7%）** |
| 真实界面可用 | 2038 ms | 2059 ms | +21 ms（噪声） |

**打包产物 A/B**（`compare-desktop-startup.ps1`，交替 8 次，`0.11.20.36` vs `0.11.20.38`）：

| 指标 | v36 | v38 | 变化 |
|---|---|---|---|
| 第一帧 | 4658 ms | **4442 ms** | **−216 ms（−4.6%）** |
| 真实界面可用 | 3785 ms | **3735 ms** | **−50 ms（−1.3%）** |
| **窗口可见（用户真正看到画面的那一刻）** | 4730 ms | **4492 ms** | **−238 ms（−5.0%）** |

> 这一轮同时给 A/B 脚本补上了「**窗口可见**」这个指标——它才是用户感知的启动时间
> （此前只有应用自报的首帧与真实界面）。`compare-desktop-classpath.ps1` 也补了
> **进程清理**：残留的实例会占住单实例 broker，后续启动只是把请求转发给旧实例然后
> 自己退出，于是被误记成「没有启动标记」。补上之后 8 次交替**零失败**。

产物本身也小了：压缩包 195,442,995 → **192,635,112 字节**（−2.8 MB）。
随包发布的**依赖许可声明**也从 204 条降到 **203 条**（kotlin-reflect 不再是随包依赖），
这一条由 `com.mikepenz.aboutlibraries` 插件按实际解析出的 classpath 自动生成，
两个断言计数的测试同步改成 203 并新增「kotlin-reflect 不得出现在清单里」的断言。

### 测试（TDD 红绿闭环）

新增 `DesktopRuntimeClasspathTest`（3 个用例）：

| 用例 | 作用 |
|---|---|
| `the desktop runtime keeps the full reflection implementation off the class path` | 构建脚本必须排除 `kotlin-reflect`（防止回归） |
| `the start up warm up does not name the reflection implementation` | 预热清单不得再点名反射实现的类 |
| `the test mode server answers without the full reflection implementation` | **子 JVM + 过滤掉 kotlin-reflect 的 classpath** 里真起 Ktor 服务端并请求 `/test/health` 与 `/test/state` |

第三个用例是决定性的：测试 JVM 自身带着 `kotlin-reflect 2.2.21`（Kotlin 测试设施带来的），
所以它**另起一个 JVM**，用过滤后的 classpath 跑真实的 `testHttpServer()` 路由。
（classpath 太长超出 Windows 命令行上限，因此用 `@argfile`，与
`DesktopTestProfileTest` 同一做法。）

三个用例都先在改动前跑红（3 failed），改动后全绿。

## 第十轮：类数据共享归档（把类加载的成本交给归档）（2026-10-22）

### 先纠正上一轮的结论

第四轮记录的结论是「动态 CDS 归档实测有效（−820 ms），但被本机 jlink 工具链卡住」，
理由有两条：

| 当时的结论 | 实测 | 结论 |
|---|---|---|
| 「本机 Temurin 21.0.11 的 `jlink` **没有** `--generate-cds-archive` 选项（`jlink --help` 全部选项已逐项核对）」 | 该选项**存在**且可用：`jlink --generate-cds-archive` 在这台机器上直接产出了 13 MB 的 `classes.jsa` | **当时的判断是错的**（`jlink --help` 在中文环境下没有列出它，但功能在） |
| 「基础归档必须与运行时的模块集一致，完整 JDK 的归档被拒绝」 | 这条**是对的**，但解法不是换整个 JDK 的模块镜像（+80 MB），而是**用运行时自己的 JVM 去产出归档** | 见下 |

### 归档必须由运行时自己产出

归档绑定在**模块镜像**和**虚拟机**上。三种做法实测：

| 做法 | 结果 |
|---|---|
| 把完整 JDK 的 `classes.jsa` 拷进裁剪后的运行时 | **被拒绝**（模块集不匹配）→ 运行时不使用归档 |
| 用完整 JDK 的 `java.exe` 指向运行时（`-Djava.home=<runtime>`） | 归档按**完整 JDK 的模块集**产出（70 个模块）→ 运行时同样拒绝。**这是最容易骗过人的一种**：用同一个 JVM 校验会显示 `Opened archive`，但真实启动器用的是运行时的 jvm |
| 把 JDK 的 `java.exe` **放进运行时镜像**再产出 | ✅ 归档与运行时的模块集、虚拟机完全一致，运行时接受 |

于是产出流程固定为：

```
runtime\bin\java.exe -XX:SharedArchiveFile=<runtime>\bin\server\classes.jsa \
                     -Xshare:dump -XX:SharedClassListFile=<类清单> -cp <应用 classpath>
```

`-XX:SharedClassListFile` 是关键：**从类清单产出静态归档**，不需要真的跑一次应用，
构建因此可以在不启动界面的情况下完成。

### 类清单从哪来：复用启动预热清单

`app-desktop/src/main/resources/desktop-startup-warmup.txt` 已经是「首屏需要的类」的清单
（3404 条，由 `scripts/generate-startup-warmup.ps1` 维护）。直接用它当归档清单，
**一份清单两处用**：预热线程提前读这些类，归档把它们预解析好。

### 实现

- `scripts/install-desktop-cds-archive.ps1`（新增）：从启动器配置里读出**应用自己的 classpath**
  （归档与 classpath 强绑定，必须是运行时那一份），产出归档到
  `runtime\bin\server\classes.jsa`——**JVM 默认归档的位置**，所以启动器不需要任何额外参数。
- `scripts/build-windows.ps1`：`createDistributable` 之后调用它。**归档是可选的**：
  产不出来只提示一句，应用照常工作（只是慢一点）。
- `scripts/probe-cds-static-archive.ps1`、`scripts/compare-cds-base-archive.ps1`
  （新增）：产出与对比用的工具脚本。

### 实测

**打包产物 A/B**（`compare-desktop-startup.ps1`，交替 8 次，`0.11.20.38` vs `0.11.20.41`，
两者的生产代码完全相同，唯一的差别就是运行时里有没有那个归档）：

| 指标 | v38（无归档） | v41（有归档） | 变化 |
|---|---|---|---|
| 第一帧 | 2543 ms | **2214 ms** | **−329 ms（−12.9%）** |
| 真实界面可用 | 2071 ms | **1802 ms** | **−269 ms（−13.0%）** |
| **窗口可见（用户真正看到画面的那一刻）** | 2588 ms | **2272 ms** | **−316 ms（−12.2%）** |

同一产物、只切换归档文件的那组对比（8 次交替）给出 **−398 ms（−15.0%）**，两组一致。

**交付产物上的复核**（`0.11.20.38` vs `0.11.20.42`，生产代码同样完全相同）：

| 轮次 | v38 窗口可见 | v42 窗口可见 | 变化 |
|---|---|---|---|
| 8 次交替（机器较空） | 2632 ms | **2293 ms** | **−339 ms（−12.9%）** |
| 4 次交替（机器较满） | 4550 ms | **3889 ms** | **−661 ms（−14.5%）** |

> 机器越满，收益越大——类加载正是负载下最吃亏的那部分。

**代价**：归档未压缩 36.2 MB，压缩后进入分发包 **+10.1 MB**（192.6 → 202.7 MB，+5.2%）。

### 一个反直觉的测量结果

**只有「基础归档」（JDK 自带的那几百个类）几乎没有收益**：交替 8 次实测窗口可见
中位数 2624 ms → 2466 ms（第一轮看着像 −158 ms），换一轮又变成 2658 ms → 2658 ms（**0 收益**）。
真正有效的是**应用归档**——也就是把应用自己的类预解析好的那一份。
第四轮记的 −820 ms 是在「完整 JDK + 动态归档」口径下测的，与真实交付形态不等价。

### 归档失效时必须优雅降级

归档与该构建的类字节强绑定。实测把**别的构建**的归档放进 v42 的运行时：
**应用照常启动**（JVM 忽略用不了的归档，只是慢一点），不会崩、不会卡。

### 归档与安装路径无关（这决定了 zip 交付是否成立）

归档里的类记录着它来自哪个 jar。如果用户把 zip 解压到**别的路径**后归档就失效，
那这个优化对交付用户等于不存在。

实测：把交付产物整份**复制到另一个目录**再跑同样的交替对比（6 次）：

| 指标 | 无归档 | 有归档 | 变化 |
|---|---|---|---|
| 窗口可见 | 4304 ms | **3582 ms** | **−722 ms（−16.8%）** |

⇒ **归档与路径无关**，用户解压到任何位置都能吃到这个加速。

### 已知边界：MSI 打包路径不带归档

`build-desktop.sh msi` 走的是另一套时序：`packageMsi` 自己从 **jlink 镜像**
（`build/compose/tmp/main/runtime`）取运行时，而归档目前装在**分发目录树**的运行时里
（MSI 打包发生在那之后，且它不碰那棵树）。因此 **MSI 里没有归档**，
装出来的应用启动不会变快。

要把它也带上，需要把归档装进 jlink 镜像，并验证 MSI 安装后的 jar 与归档的绑定关系
（安装会改变文件时间戳）。未验证之前不做——为一个装上去可能无效的 36 MB 去换
MSI 用户的启动速度没有意义。**未打包应用与 zip 交付都带归档** ✓。

### 测试

新增 `WindowsCdsArchiveConfigurationTest`（2 个用例）：

| 用例 | 作用 |
|---|---|
| `the build creates the class-data-sharing archive of the application` | 构建脚本必须调用产出脚本、产出脚本必须用类清单、归档必须放在 JVM 默认归档的位置、缺归档不得让构建失败 |
| `the archive installer reports a broken runtime instead of leaving a broken archive` | 用一个「不是程序」的启动器跑一遍：不得留下半个归档，必须明确报告失败 |

### 全量回归（candidate12 vs candidate11）

`3994 tests completed, 85 failed, 16 skipped`（candidate11：3992 / 85 / 16）。

逐项对比：**新增失败 2 项、修复 2 项**，两边都是**既有 flaky**：

- `LibrarySettingsPolicyInteractionTest` 的两条搜索用例**轮换失败**：本轮失败的那条在
  candidate、candidate5、candidate9 都失败过，上一轮失败的另一条在本轮变成修复；
- `AuthorsProductionWiringTest :: mounted split display focuses the surviving new source card`
  在 **candidate4**（本轮工作之前）就失败过；
- `TrackingInteractionTest` 上一轮失败的那条本轮变成修复（此前已隔离重跑 2/2 通过）。

去重后的失败数两轮都是 **82**，**实质新增失败 0**。

### 顺带修掉的两个构建缺陷

1. **`build-windows.ps1` 用了过期的 `$LASTEXITCODE`**：`.ps1` 脚本用调用运算符执行时**不会**把退出码
   传给调用方，而归档产出脚本里的 dump JVM 退出码非零 → 打包脚本后续步骤读到的仍是那个旧值
   → 压缩包根本没生成。改为：以**归档文件是否存在**为准，并把 `$LASTEXITCODE` 复位。
2. **`compare-desktop-startup.ps1` 无法传递以 `-` 开头的 JVM 参数**：`powershell.exe -File` 会把
   `-XX:...` 当成参数名。改用测量脚本本来就支持的 `MIHON_STARTUP_EXTRA_JAVA_OPTION` 环境变量。

### 端到端累计效果（优化前 vs 交付版）

同一轮交替 A/B，8 次，`0.11.20.9`（启动优化之前的一版，生产代码与 `0.11.20.7` 基线同源）
vs `0.11.20.42`（交付版）：

| 指标 | v09 | v42 | 变化 |
|---|---|---|---|
| 真实界面可用 | 3650 ms | **1938 ms** | **−1712 ms（−46.9%）** |
| **窗口可见（真实内容上屏）** | 3706 ms | **2538 ms** | **−1168 ms（−31.5%）** |

> v09 的「首帧」并不比现在早（2460 ms vs 2486 ms），因为那一版**先画占位页、
> 再画真实界面**：用户先看到一张空壳，真实内容要等到 3.7 s 才上屏。
> 「外壳先行」之后占位页不再绘制，第一帧就是真实界面。

安卓侧的参考值是 API 36 模拟器（纯软件渲染）上冷启动到首帧的中位 **5277 ms**（n=10）。
模拟器没有硬件 GPU，这个数字不能与桌面端直接比较，但方向明确：
**桌面端现在从进程启动到真实界面上屏约 1.9 s，窗口可见约 2.5 s**。

## 第十一轮：首次启动（全新用户配置）慢一倍（2026-10-23）

### 现象

用户实测交付包：**初次启动明显多一倍耗时**。

### 归因：数据库从零创建在关键路径上

阶段分解（全新配置的首启，`app-desktop/tmp/startup-trace-*.json`）：

| 阶段 | 全新配置首启 | 之后每次启动 |
|---|---|---|
| `di-data:driver`（驱动 + 解原生库） | 10 ms | 10 ms |
| **`di-data:schema`（建表）** | **2417 ms** | 不发生 |
| `di-data` 合计 | 3158 ms | 42–60 ms |
| 真实界面可用 | **4550 ms** | 1290–1326 ms |

⇒ 首启比稳态慢 **3.4 倍**（窗口可见 4621 ms vs 1603 ms）。

**这不是最近两轮改动引入的**：同一条件下 `0.11.20.38`（没有 CDS 归档、没有预取、没有摘反射）
首启 **4736 ms**，比交付版还慢 279 ms。

### 真正的成本：建表是逐条语句、逐条提交

SQLDelight 生成的 `schema.create()` 逐条执行 `CREATE TABLE`，**每条语句各自是一个事务**，
各自要把回滚日志刷到磁盘。实测（同一 JVM 内）：

| 建库方式 | 耗时 |
|---|---|
| 逐条语句（当前） | 597–780 ms |
| 逐条 + `synchronous=OFF` + `journal_mode=MEMORY` | 601–622 ms（**没有区别**：PRAGMA 是按连接的，没落到每条语句用的连接上） |
| **包在单个事务里** | **9–13 ms（快 50–60 倍）** |

仓库里**迁移路径本来就是单事务**（`DatabaseMigration.migrateAtomically` 用
`TransacterImpl(driver).transaction { }`），**只有建库路径不是**——就是这个不对称。

### 实现

- `createSchemaAtomically(driver, schemaCreator)`：建表 + `PRAGMA user_version` 包在
  **一个事务**里，和迁移路径一致。
- `createDriver` 新增一个可注入的建库动作（默认仍是 `Database.Schema.create`），
  用来测试失败路径。
- **建库失败时删除半成品数据库**：只建了一半的库不能被当成「既有库」——
  下次启动会去迁移它，而不是重新创建。
- 顺带保留两个诊断埋点：`di-data:driver` 与 `di-data:schema`。

### 实测（全新配置，3 次交替，`0.11.20.42` vs `0.11.20.43`）

| 指标 | v42 | v43 | 变化 |
|---|---|---|---|
| 首启「真实界面可用」 | 4675 ms | **1397 ms** | **−3278 ms（−70.1%）** |
| 建表 `di-data:schema` | — | **69–75 ms** | — |

热配置（已有数据库）不受影响：建库根本不会发生，实测无回退。

### 测试

`DesktopDatabaseMigrationSafetyTest` 新增 2 个用例，都做过反向验证：

| 用例 | 作用 |
|---|---|
| `a failed schema creation rolls back the tables it already created` | 建库是**一个事务**：失败时已经建出来的表会一起回滚 |
| `a failed schema creation leaves no database behind` | 建库失败**不留半成品数据库**，下次启动会重新创建 |

> 改实现前这两个用例先跑红（2 failed），实现后全绿（该类 11 个用例通过）。

### 顺带修掉的两个测量缺陷

1. **`scripts/compare-desktop-startup.ps1` 传不了以 `-` 开头的 JVM 参数**（`-File` 会把它
   当参数名）→ 改用 `MIHON_STARTUP_EXTRA_JAVA_OPTION`。
2. **残留实例会占住单实例 broker**，让后续启动只转发后退出、被误记成「没启动标记」
   → 对比脚本每次启动前清理残留进程。

### 新增测量脚本

`scripts/compare-first-launch.ps1`：每次运行都把用户配置移开再放回，
**测的每一次都是真正的首次启动**（普通测量脚本完全看不到建库这笔开销）。

## 已知取舍

1. 启动窗口先显示中性占位，真实界面在依赖图就绪后出现；依赖图失败时窗口显示
   错误提示并保留原有恢复入口。
2. 单实例 broker 仍在窗口创建之前完成（否则第二个实例会闪窗），
   该阶段已通过熵源预热与目录延迟创建缩短。
3. 剩余成本集中在真实界面的首次绘制（~860 ms 的 Skia/D3D 原生阻塞）、窗口与图形
   管线创建（~900 ms）以及依赖图构建（~980 ms，与前两者并行）。类加载已不再是
   主要矛盾：真实界面组合期间的类加载从 3360 个降到 502 个。
4. ~~**运行期 classpath 仍带着 `kotlin-reflect 1.8.22` 与整套 Ktor 服务端栈**。~~
   **第九轮已解决**：`kotlin-reflect` 已从 `jvmRuntimeClasspath` 移除
   （Ktor 服务端栈仍保留，但只有 Test Mode 用它，而它不需要完整反射实现）。
   打包级交替 A/B：窗口可见 **−238 ms（−5.0%）**、首帧 −216 ms、真实界面 −50 ms。

