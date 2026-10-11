# 双端共享双页开发与第97话复现交接

> 2026-10-09 最新增量：code63 的章节开篇与手动跨页修复已合入本工作树。请先读[功能及人工验收交接](2026-10-09-code63-manual-pairing-handoff.md)，核对已交付APK、待用户复现的两部漫画和实时日志流程。下文保留原历史交接状态，不代表code63的当前验收结论。

交接日期：2026-10-09，北京时间。原会话：`codex://threads/01a102cd-3448-7a31-b105-6f534c98513c`。本文用于在新会话继续原工作，优先完成安卓真机第97话章末回翻复现，再处理原 DP02 剩余验收。主体实现已提交，DP02 尚未完成；第97话问题尚未证实原因，也没有修复结论。

**本文为纯文字交接，不包含图片、图片嵌入、图片附件路径、Base64 或旧截图工具输出。不要把原会话完整历史重新加载进上下文。** 用户报告原会话因图片相关的上下文膨胀 bug 卡死；这是用户提供的故障描述，本轮没有诊断 Codex 内部原因，也没有修改原会话记录。

## 新会话可直接使用的接续指令

```text
请在 D:/Codex/worktrees/0877/mihon 继续双端共享双页工作。
先读取 docs/roadmap/2026-10-09-shared-dual-page-text-only-handoff.md，
核对 git status、分支、当前进程和设备，不加载原卡死会话的图片或完整历史。
保留 AppVersion.kt 与 gradle/android-release.properties 两处既有版本改动。
优先按最新授权在安卓真机上复现：拷贝漫画 →《8月31日的长夏》→ 第97话，
从作品详情打开，正向翻到“后面没有啦”等无下一章提示，再回翻观察。
先完成一次快速、有界的实际复现并及时反馈；不重建、不跑全量，
不要重复已经完成的匹配修复、schema42启动修复或Android72性能采样。
有真实产品反例后才做最小修复、行为TDD及受影响验证。
沿用唯一roadmap与聚合证据，不新增产品计划、不把未复现称为已修复。
```

这段指令用于用户在新会话明确启动继续工作。原聊天的 GOAL、工具句柄、代理及 JavaScript store 不会因读取本文自动迁移；不要为接续擅自创建第二个 GOAL。当前归档任务只生成交接，没有代操作设备或继续产品实施。

## 当前仓库与必须保护的改动

| 项目 | 2026-10-09本轮只读核对结果 |
|---|---|
| 工作树 | `D:/Codex/worktrees/0877/mihon` |
| 分支 | `codex/shared-dual-page-rules-and-repair` |
| 交接前 HEAD | `01765fba14ee51b5e5c75ccd5226322027e5aa41`，记录 Windows 第97话观察 |
| 产品状态 | SP01 已完成并提交；第一个未勾选批次为 DP02 |
| 既有未提交文件 | `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt`、`gradle/android-release.properties` |
| Desktop版本差异 | BUILD 从已提交的70变为72；STAGE11、FEATURE19 |
| Android版本差异 | versionCode 从40变为57；versionName 从 `0.19.4-aex.22` 变为 `0.19.4-aex.39`；applicationId保持 `app.mihon.desktop.fork` |

两处版本差异跨越之前多轮正式候选分配，必须完整保留。不得 reset、恢复到 HEAD、随无关功能提交混入，也不得以当前候选目录名中的旧 commit 短号断定产物遗漏后来提交的修复：构建清单同时记录了构建时的未提交生产差异。

本文自己的文档提交会成为上述 HEAD 的后继；它不改变产品源码、版本或 DP02 完成状态。接续时重新执行 `git status --short` 和 `git log -5 --oneline`，以当时事实为准。

## 持续有效的用户意图与授权

1. 原总目标是完成[双端共享双页 roadmap](2026-10-01-shared-dual-page-rules-and-repair-roadmap.md)，及时更新进度；不能把阶段完成或工具问题当成整个目标完成。
2. 用户多次要求减少任务扩张、无效测试和频繁审批。范围内必要诊断、最小修复、focused测试和已有构建续接自主进行；时间检查点用于收敛，不能不断申请“再给20分钟”。实质扩张、增加正式构建或扩大测试仍按项目规则处理。
3. Windows computer use 已明确授权，后来暂时转为“修复后给我体验包人工验收”，并反馈“测试正常”。该反馈仅覆盖实际体验路径，不关闭原 DP02 全部正式验收。
4. 用户要求恢复正常测试流程并交付 APK，随后明确授权 ADB 调试安装闪退。该启动问题已修复并无清数据覆盖安装验证。
5. 最新业务授权是 **在已连接安卓真机上测试第97话**。2026-10-08 22:32用户说已在作品详情，点开97话；2026-10-09 00:31用户说已解锁、要求更快；02:02又说画面已回作品详情，要求重新测试97话。随后两条仅反映长时间思考无回应，没有新的产品结果。
6. 不得从上述授权推导清数据、卸载、改变锁屏策略、操作其他设备/实例、重跑完整矩阵或再次正式发版的无限授权。

时间均已从历史 UTC 转换为北京时间。02:02的作品详情位置是**用户最后提供的状态**，不是本交接轮验证过的当前画面。

## 最新任务 第97话章末回翻

操作路径：**拷贝漫画 →《8月31日的长夏》→ 第97话 → 从首页逐屏正向翻页 → 直到出现无下一章提示 → 反向回翻**。用户只说“就会出问题”，尚未用文字具体描述异常画面、翻页方向或手势；可以先执行已明确路径，若异常仍无法辨认，再集中询问最小必要信息。

### Windows 已观察到的结果

提交 `01765fba14` 及聚合报告“第97话章末回翻”段已有证据；不需要重新做一遍。

- 使用当前 Windows 体验包、隔离profile、真实源1.4.85，RTL、双页、自动匹配开启，24源页。
- 从首页真实方向键顺读，末段零基索引依次为 `[20,19] → [21]宽页 → [23,22]末双页 → 章末屏`，提示“已读完：第97话／后面没有啦”。
- RTL环境下 Left 继续正向仍停章末；Right 回翻依次恢复末双页、宽页、前双页。鼠标左侧正向、右侧回翻复验同样结果。
- 没观察到空白、错序或崩溃。**结论仅为该 Windows 配置未复现，不能写成 bug 已修复，也不能外推 Android 正常。**
- 该隔离profile为 `app-desktop/tmp/copy97-end-cua-20261008`，文字状态记录为其中的 `native-trace.json`。当次实例36776/15640及59797监听已精准关停。不要使用历史 PID 关闭任何当前进程。

### Android 实际进度与阻断

- 原会话确认华为平板API31、正式包 aex.39/code57、横屏双页，已从详情点开97话。
- 确认逐屏正向翻至 **22/24宽幅广告页**，但没有完成无下一章提示后的回翻。
- 设备因超时息屏；有界亮屏后显示锁屏，业务输入被现有 `Device.guard()` 拦住。用户解锁后首个观察仍在22/24；下一次翻页前又息屏，输入未执行。
- 历史工具配置使用 `scripts/android-sync-native-acceptance.py` 的 `Device` 进行身份、前台、锁屏保护。这是复用保护能力，不是把同步场景当成双页验收。
- 最后一次业务工具调用在2026-10-09 01:49左右只核对既有观察文件时间；02:02之后没有新的业务工具结果。不能把长时间无回应解释为仍在执行 ADB 测试。
- 后段 Android 观察没有写回原聚合报告或 roadmap；这是本交接从文字历史补回的重要缺口。没有产品 diff、失败测试或修复提交可以代表第97话问题已关闭。

### 接续的最小工作范围

先只读确认 `adb devices -l`，显式选择本次目标设备，核包版本、前台、亮屏和锁屏，再重新定位“第97话”并点击。不复用旧 serial、坐标、进程、截图或 JavaScript store。历史一次曾查错包名 `app.mihon.fork`；正确生产包名是 `app.mihon.desktop.fork`。

优先用实时 UI hierarchy、页码文字、系统状态及窄范围生产日志记录动作结果；关键图像异常确实需要时，只获取当下必要的新画面，避免把连续截图、旧图片历史或图片工具输出成批保留在模型上下文。不得为完成复现改超时、绕过锁屏保护或反复盲点。

一次有界顺读后回翻即可区分当前能否复现，数分钟内给出结果；不为只读复现运行产品测试或构建。有真实异常时，保存文字动作序列、页码/可见内容、方向、时间和最小反例，再沿 production 链定位。只有改产品行为时才执行正确红因 → 最小实现 → 同范围绿与重构复验。

## 已完成工作与关键提交

| 提交 | 结果与边界 |
|---|---|
| `dec006e7d3` | SP01主体：不等匹配即可阅读、稳定首帧/槽位、共享决策与两端接线、操作/保存/调度；不表示正式三端验收完成 |
| `1b7e290b95`、`5fe1ba589f` | 新版测试/验收流程定向迁移；不合并来源整产品分支，已有证据复用 |
| `762cc6ce83`、`a0553558ec` | Windows原生基础操作/进度恢复、真实缓存原图诊断及未验项记录 |
| `450de127dc`、`de23bc56c1` | 实际图片命令、已应用几何、真实前台owner IO控制；观察器证据不是屏幕vsync或正式GPU通过 |
| `541fb4dc47` | Retry指针被子按钮消费后，不再同时触发父级翻页；真实指针红绿及相关22项通过 |
| `1c6f9070ab` | 未显示的邻居proposal不能抢占冷跳目标；单独此修复仍未解决源16的错误兜底配对 |
| `ef6c56dbf1` | 从正常解码位图识别有界装订留白页侧，首次锁槽前选择前/后伙伴；Desktop原图冷跳与共享契约验证，Windows用户反馈正常 |
| `8dad3fbb39` | Android通过原SSIV公开Canvas绘制入口传页侧提示；不新增第二解码、不用生产反射 |
| `ea36794e7f` | 修复同身份已发布schema42数据安装后闪退；保留旧库数据，候选aex.39/code57实际无清数据覆盖安装并启动通过 |
| `01765fba14` | Windows第97话章末回翻未复现的观察记录；没有Android复现结果 |

### 冷跳配对修复不能回退的事实

用户明确指出，第169话左右外侧的空白是装订留白，当前两页应分别与各自前后页组合；原来只检查页码位置、可见集合的“通过”不等于配对内容正确。首次只修proposal抢占后的 Windows 体验仍失败，尤其漏测 **一基源16，即零基index15**。

后续源14、15、16、17的独立冷跳预期依次为 **13/14、15/16、15/16、17独占**；连续16→17与缓存伙伴先就绪也被纳入真实原图验证。印刷页码不能当source index；不要恢复旧测试要求的错误16/17组合。

页侧只是当前正常解码位图的可选提示，不增加IO或识别等待。手动边界、已锁历史优先；普通header不能抹掉同内容提示，伙伴先就绪也要核槽位兼容。双侧都白、都不白、弱证据等降级UNKNOWN；不能把单章白边规律推广为所有漫画必然正确。参数和边界见当前设计§7.3。

Android普通静态图的SSIV adapter通过真实Canvas转发一次原bitmap/matrix/paint；弱引用缓存不持图片lease。crop、sourceBounds、动画、不完整tile等仍降级未知；已锁旧画面不能因新locationSequence错误拒画。原Bitmap/Canvas的Robolectric绿色不能冒充APK的JNI/ART或硬件性能证据。

### Android 启动闪退已经修复

aex.38/code56在真实生产Requery/AndroidSqliteDriver启动读库时报 `Can't downgrade database from version 42 to 40`。同applicationId的另一已发布候选code55用了schema42，而本树当时仅支持40；APK versionCode更高不保证数据库兼容。

修复逐字复用已发布的40/41迁移及等价当前表/trigger，支持schema42，不清数据、不降低库版本、不合并另一个同步产品分支、不引入schema43。真实AppModule/SqlDriver红绿、8个新旧相关方法、限定格式及1轮独立数据兼容审查通过。

aex.39/code57保持原证书，无清数据覆盖更新真机，firstInstallTime保持，MainActivity实际前台、同进程持续存活、无新crash。**这个安装后启动闪退 bug 已修复。** 该结果不证明第97话回翻、全部升级矩阵或性能正常。

## 可复用验证与仍待完成的 DP02

唯一长期状态入口仍是原roadmap和[聚合证据](../evidence/shared-dual-page-rules-and-repair-2026-10-01.md)。本文是接续快照，不代替它们或 `parity-manifest.json`。

| 验证 | 现有证据与复用限制 |
|---|---|
| 首次完整矩阵 | 已实际执行：共享JVM665/Android570、客户端53通过；Desktop3294中35失败及Android735中18失败按相关focused补验关闭，格式通过。保留首次结果，不能说最终代码重新全量通过 |
| Windows装订留白 | `dp02-gutter-side-final-focused5`：共享3项、Desktop原图2项，零失败/错误/跳过；8文件strict格式通过；当前体验包用户反馈“测试正常” |
| Android页侧wiring | `android-gutter-final-focused6`：真实SSIV/租约/方向/旧回调等6项通过；5文件格式通过。尚不等于实体设备大范围分页验收 |
| schema42 | `schema42-final-focused8`实际7方法通过，加`schema42-rollback-focused1`补第8项；对应APK构建、verify和真机更新启动通过 |
| Android正式性能 | 固定四条件×六场景×三次，共72唯一样本，12+48+12分片已核。Ready回调→GPU commit上界2.38–268.54ms，输入→强PixelCopy上界243.59–1617.63ms；保留波动/OOM/分片限制，不签零卡顿或连续内存稳定 |

后续只补具体修改影响的focused范围，不重跑72或完整矩阵“求放心”。共享、Android、Desktop及正式产物属于不同层，不能相互替代。

原DP02主要未完成项仍包括：

- Windows纯自动冷拓扑、B1前组及时/不及时和B2条件时序的正式原生证据。旧正式71缺新mountedReader观察能力，接口缺失不能归因锁屏或强行签通过。
- Mac剩余B1不及时、B2条件场景、正式冷拓扑/时序；已有及时冷跳、保存/重启、设置/手调、章界证据按适用范围复用。
- 两桌面端受控迟到纵图、宽图、错误伙伴的正式原生逐帧零位移，tile/GPU及进度证据。已提交离屏场景与上一已应用布局不是正式屏幕逐帧证明。
- 正式Windows黑色过渡原因/时长及槽位帧状态。动画开/关观察的前一分组不同，不能据此作唯一变量归因。
- 固定正式性能对照与实际阅读观察的最终签收，以及版本、产物身份、必要证据和DP02收口提交。

最新用户选择先把分页匹配算法体验做好，并追加第97话特定复现；不要抢先泛做以上验收或重新正式构建。原必做项继续保留未完成，不能在新版日常流程中删掉或追认通过。

## 已核实的可用产物

以下文件在本交接轮已确认存在并重新计算SHA-256。只核文件身份，没有启动、安装或重新验收。

### 当前 Windows 人工体验包

[Mihon Desktop.exe](<D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/preview/windows/20261008T075221Z-5bf38ba6/Mihon-Desktop-0.11.19.72.1c6f907-unpacked/Mihon Desktop.exe>)

- EXE SHA-256：`a027f739dc14c88d8d4e323572284b10ec6f2478a1acbda09df874812444c576`。
- [preview-manifest.json](D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/preview/windows/20261008T075221Z-5bf38ba6/preview-manifest.json)记录基线`1c6f9070ab`及构建时生产差异，productSource digest为`2cdfcb4833a4d56a664d2bdebc9fac2b21d0224d4871b147ca378944111341fe`，包含后来提交的Desktop/共享留白修复。
- build/sourceIntegrity/productionRuntime为PASS，打包时nativeInteraction为NOT_RUN；用户后续人工反馈与第97话观察另记，不篡改manifest原始层级。

### 当前 Android 修复候选

[Mihon-Fork-0.19.4-aex.39-vc57-release-universal.apk](D:/Codex/worktrees/0877/mihon/app/artifacts/android/0.19.4-aex.39-vc57-8dad3fbb39-release/Mihon-Fork-0.19.4-aex.39-vc57-release-universal.apk)

- APK SHA-256：`3647232d5f9cdc1bd575dbd8799bd98df7da421ab046f28af83818e5f6802850`。
- [artifact.json](D:/Codex/worktrees/0877/mihon/app/artifacts/android/0.19.4-aex.39-vc57-8dad3fbb39-release/artifact.json)绑定源码基线8dad及当时SQL修复差异；productionInputs为`bbaef0afd9bd9be92d8f04c22336c42a118cc18ebb4807378b62aedc09866fdc`。
- 原证书、R8/资源压缩、四ABI、非调试；正式身份`app.mihon.desktop.fork`、schema42。不要再交付已确认启动失败的aex.38/code56。

### 旧正式桌面验收基线

[Windows正式71](<D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.71.32141a9-unpacked/Mihon Desktop.exe>)，EXE SHA-256为`93684e7620fa536a33c5de4ef7c5faffd2a5bed8779305bb739428580044c687`。

[Mac正式72观察包](D:/Codex/worktrees/0877/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.72.dec006e-observation-macos.zip)，ZIP SHA-256为`fdab35d1649d597dfe644c1a3a4f67d76618287f12580efe7fb9e1b9a49eb461`。

这些旧正式包不包含后来的所有观察接线和留白修复，不能当当前源码的完整正式验收产物。需要新正式构建时先证明具体必要性并遵守既有额度，不因接续自动重建。

## 代码与文档入口

先读本地[AGENTS.md](../../AGENTS.md)、[测试指南](../automation/TEST_GUIDE.md)，再按具体问题取最小范围；不用重新探索整个仓库。

- 规格：[完整设计v3](../2026-10-03-dual-page-stable-presentation-design.md)，S01–S17；留白修复在§7.3。旧DP01/U01–U20只作历史，不按旧条目重做。
- 状态：[唯一roadmap](2026-10-01-shared-dual-page-rules-and-repair-roadmap.md)；细节：[唯一聚合证据](../evidence/shared-dual-page-rules-and-repair-2026-10-01.md)。报告较长，先取标题及最后“2026-10-08”相关段，不一次全文塞入上下文。
- 共享规则：`domain/src/commonMain/kotlin/mihon/domain/reader/SharedDualPageRules.kt`、`SharedDualPageSession.kt`、`ReaderDualPagePresentationOwner.kt`；契约测试：`domain/src/commonTest/kotlin/mihon/domain/reader/SharedDualPageStablePresentationContractTest.kt`。
- Desktop呈现：`app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DualPagePagerViewer.kt`、`ReaderPagerNativePresentation.kt`、`ReaderScreenModel.kt`；真实原图测试：`app-desktop/src/test/kotlin/mihon/desktop/reader/SharedDualPageOriginalImagesProductionTest.kt`。
- Android绘制：`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt`、`ReaderDecodedPageCanvas.kt`、`pager/DualPagerPageHolder.kt`、`pager/DualPageViewerAdapter.kt`；测试：`app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPagerPageHolderLayoutTest.kt`。
- 生产数据库兼容测试：`app/src/test/java/eu/kanade/tachiyomi/di/AndroidLegacySyncMigrationTest.kt`；唯一schema在`data/src/commonMain/sqldelight/`。不要重建旧目录镜像。
- 设备保护：`scripts/android-sync-native-acceptance.py`的`Device.guard/dump/run`；只复用实际身份与输入保护，不运行无关同步整组场景。
- Android交付：[构建与验收规范](../architecture/android-build-and-acceptance.md)，统一`build-android.py`，不直接交付Gradle中间APK。
- Mac：[原生验收规范](../automation/MACOS_ACCEPTANCE.md)，统一`mac-acceptance.py`/共享session保护；SSH配置、端口、profile和权限需重新核实，不猜旧值。

## 工具与进程接续边界

本交接轮 `.gradle-coordinator/` 中没有STARTING/RUNNING记录；所查`android-candidate`、`dp02-gutter-side-final-focused5`、`schema42-final-focused8`及`schema42-rollback-focused1`均PASSED。没有本工作树可见的在途重型Gradle进程。

仍看到Windows体验应用父子进程 **45524/15376**，可执行文件均为上面当前preview；没有核实它们的profile、前台或启动责任。本轮未终止，可能是用户人工体验实例。旧隔离测试实例“已退出”不能外推为系统当前无应用。接续重新核身份，不全局杀Java或应用、不擅自关闭用户窗口。

安卓设备、Mac主机和原代理存活状态本轮未查询；过去的解锁、SSH可用、端口空闲或代理可复用都是历史信息。新会话不得继续轮询失效工具handle，也不得声称原代理仍运行。包含多项实质实施的roadmap后续实现按AGENTS要求委派；简单第97话只读复现可由主代理直接完成，不能为接续重新启动泛实现代理。

Windows computer use的历史正确入口是Node REPL的`@oai/sky`，需要读已安装computer-use插件skill/API后调用。精确匹配本次EXE、实例及窗口，一动作后刷新；截图错配时不派发业务输入。过去一次有限Node重置恢复了正确窗口，但不能把复发工具错配直接解释为锁屏。当前是否有该工具须重新查能力。

Mac通过统一session一次有界亮屏、LaunchServices、profile/PID/端口和权限保护；不要用单一loginwindow/lockflag要求用户反复解锁。缺观察接口404属于候选能力缺失，不属于屏幕锁定。

## 最小检查与完成标准

只读接续可从以下命令开始，不会构建或安装：

```powershell
Set-Location -LiteralPath 'D:\Codex\worktrees\0877\mihon'
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
git status --short
git log -5 --oneline
& 'D:\Android\Sdk\platform-tools\adb.exe' devices -l
# 从上一步明确选择目标后，用 -s <本次设备> 查询正确包名：
# adb -s <本次设备> shell dumpsys package app.mihon.desktop.fork
```

确需修改时，沿用严格行为TDD，测试必须执行真实production及wiring。Gradle经`python scripts/gradle-coordinator.py run --key <本轮唯一key> -- <精确过滤命令>`串行；外层超时先查status，不启动第二个任务。先核测试方法及过滤是否实际执行，编译失败/空过滤/skip不算行为红或通过。

新增体验构建走`bash scripts/build-desktop.sh preview`，不分配正式Desktop版本；正式Android候选只在需要交付新修复时走`python scripts/build-android.py candidate`并独立verify，安装和输入分别遵守授权。访问境外网络在当前会话设HTTP/HTTPS代理`http://127.0.0.1:10808`，本地端点绕过，不永久改系统配置。

最近这一项任务的可执行验收清单：

- [ ] 实际Android设备 → 核对aex.39/code57、目标应用前台及未锁屏 → 不复用历史状态。
- [ ] 作品详情 → 打开第97话 → 从首页正向翻至无下一章提示 → 回翻，文字记录显示内容、页码和输入方向。
- [ ] 若复现异常 → 保存最小实际路径并区分观察/假设/原因 → 针对真实production行为写正确失败测试；未复现只报告未复现。
- [ ] 若改代码 → 受影响focused红绿/重构、接线和格式通过 → 只提交本任务文件、报告hash，保留两处版本差异。
- [ ] 将结果追加原聚合证据及必要roadmap说明 → 不提前勾选DP02，不重复已完成矩阵或72样本。

交接文档本身仅做UTF-8、链接/产物、图片排除和diff核验，不执行产品测试。原始文字取自指定会话的本地rollout（264,389,722字节、35,818记录），结合当前Git与项目记录核对；压缩摘要本身是加密内容，未尝试恢复，未把图片或整段压缩历史作为接续输入。
