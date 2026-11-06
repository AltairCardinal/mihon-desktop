# 新版扩展协程接口兼容与扩展故障隔离

日期：2026-11-05。状态：**EC-01–05 已完成定向验证与独立审查；继续 EC-06。常规详情/书库更新的返回消费与控制传播已通过本阶段验证；Desktop后台来源投影、EC-06/07整体收口及Android R8验收尚未完成**。

active-task: EC-06

实施进度：EC-01/02 完成正确 ABI 红→升级后绿，38 项最终 focused 测试通过、零跳过；12 个生产/测试运行时配置的协程均解析为 1.11.0，定向格式通过。主代理核对原始日志/XML、薄 JAR 内容和来源/哈希后独立审查通过。Desktop 真实源使用官方签名 JVM JAR；原 APK 的 Legacy 转换 VerifyError 单独记录，仍须 EC-08 的 Android DEX/R8 原生验证。详见[累计证据](../evidence/extension-coroutines-compatibility-2026-11-05.md)。

EC-03/04 已收口：正确分类与异步网络红测确认后，双端共享 39 项、Desktop 9 项、Android 文案 3 项，共 51 个独立 focused 用例通过、零跳过；采用未受影响的有效结果加失败项补验，不宣称最终代码重跑全量。真实外部扩展上下文中的别名及派生客户端、一次回调后 worker 终止、健康后续请求均有生产接线证据，24 个 Kotlin/build 文件定向格式通过。主代理独立核对实现、原始日志/XML及边界后通过审查，随本批内聚提交。加载/源 API/解析/阅读下载和全局搜索汇合仍待 EC-05–07；原 APK 的 R8 原生验收仍待 EC-08。

EC-05/06 阶段记录（2026-11-06）：共享第三方调用 guard 和 Android 加载失败管理纵向链路完成阶段验证与独立审查，41 个独立 focused 用例组合通过、零跳过。真实管理页在空列表也显示包名所对应的名称/版本与失败提示，显式重试和较新版更新接回原管道；广播、重试及信任保存各自的 owner 防止旧结果覆盖新状态。工厂原子性、取消、致命/宿主错误边界和双目标目录 flight 回归有证据，20 文件定向格式及末次 2 文件补验通过。本阶段作为内聚提交，不勾选 EC-05/06：Desktop 加载管理、Android typed reload 回滚补验、filter/client/baseUrl 实际消费、reader/download、Creator 及 parser 生命周期仍须继续完成；EC-07/08 保持原验收范围。

本计划对应用户要求的两件事：升级宿主协程运行库以兼容新版扩展；升级异常保护，使未来可识别的扩展链接错误转为单个源失败。后续执行按 EC-01 → EC-08 顺序进行；任务勾选表示该任务实现、必要验证、维护文档和本地提交已完成，不表示用户或正式发布验收通过。

EC-06 默认 HTTP 链阶段（2026-11-06）：默认 HttpSource 的 client／request hook、七类 Rx parser 及响应所有权已有正确红→绿证据，Android/JVM 同范围 32 项通过，零跳过。关闭阶段的 fatal／取消优先级另有真实失败回归，图片成功响应保持交给调用者。该阶段独立提交；EC-05/06 均不整项勾选，扩展自定义 Rx 调度、实际 filter/baseUrl 消费、reader/download、Creator、EC-07 全局搜索与 EC-08 R8 原生证据仍须继续。

EC-05 收口／EC-06 阅读下载阶段（2026-11-06）：双端加载、实际 filter/baseUrl 消费、管理页与详情 prepared metadata、冻结身份、typed 更新回滚及失败反馈完成必要独立审查与定向验证，EC-05 勾选。共享 guard 已接真实阅读和下载调用，确定性不兼容不自动重试、健康并行章节能完成；根代理 reader/download 的 46 项有效 focused 组合通过，新增/受影响 54 个 Kotlin 文件精确定向严格格式通过。累计证据分别保留误红、正确红、绿测及组合复用范围；不是最终代码全量重跑。此内聚阶段随代码、测试和维护说明提交；EC-06 仍须 Creator 分类保真、自定义 Rx／调度边界及剩余已列直接 API 消费，EC-07/08 保留完整原验收条件。

## 1. 起点与已有证据

EC-06 常规更新链阶段（2026-11-06）：共享实际元数据先捕获为宿主值，再进入Android
缓存/SQL；作品页/书库与Desktop Checker保留兼容分类、控制传播及调用前冻结归属，
错误文件不重读坏标签。真实SQL/Model/Worker/Module及旧正常路径27项有效focused
组合、准确11文件strict与Root独审通过。DI审查暴露DesktopSourceRepository的四个
后台投影仍裸读元数据，继续有限修复；EC-06/07不勾，EC-08原生门仍未执行。

EC-06 Smart 迁移阶段（2026-11-06）：引擎、实际Model/Screen和手动选择保留兼容Failure，
不假NotFound/Success、不隐藏错误行，普通顺序/按章策略保留；精确详情快照不新增URL/
initialized要求，健康成功亦记录坏候选冻结诊断，移除后迟到结果拒收。14项有效focused
组合、准确8文件正常strict及Root独审通过。常规详情/库更新链的实际返回字段和外层
Throwable catch仍有有限缺口，EC-06/07不勾选；EC-08原生装置准备中，未宣称通过。

EC-06/07 Browse 展示与同步恢复阶段（2026-11-06）：真实 Browse 标题/能力准备、assist/
WebView/login 晚读保护，以及 READER 缺失作品恢复的 API/返回字段保护完成。26+6 共32个
独立 focused 方法、准确10个 Kotlin 文件正常严格格式及互审通过；原请求 URL、无坏源
成功写入、冻结归属和真实返回路径有接线证据。格式失败仅缩短长测试名，未放宽规则。
不勾选 EC-06/07：Smart 迁移尚在实施；EC-08 原 APK/ART/R8 保持必做。详见累计证据。

EC-06/07 转换与 Android 消费阶段（2026-11-06）：Desktop materializer 的晚读URL、
兼容分类及冻结诊断、Android 全局转换/登录恢复、Browse Model/Paging、遗留数据仓库
三种分页及 DeepLink 消费完成正确红→最小实现→focused。54项有效组合、准确21个
Kotlin严格格式及必要独审通过。Browse重复项新增读取回归和筛选器后迟取owner的问题
均有正确红→修正；旧HTTP断言修为现有401/403语义，不改生产分类。本批不勾EC06/07：
Browse UI标题/WebView/assist、Smart迁移完整反馈和SyncRecovery尚欠；EC08原APK/
ART/R8仍未执行。命令、复用范围与装置错误详见累计证据，不与前轮60项混算。

EC-06/07 返回对象与 Desktop 快照阶段（2026-11-06）：共享 mapper、Creator 返回字段/
缓存 copy、Desktop Save 的 SQL 前字段捕获，以及真实 Desktop 全局搜索的安全描述、
元数据重试、未知行、登录地址显式读取和共享分页 URL 去重完成阶段实现。取消重试时
原错误/原描述同步恢复，Desktop reducer 的第三方读取移至锁外并拒收旧帧。阶段验证
为60项有效focused组合、18个Kotlin文件严格格式及独审通过，详见累计证据；EC-06/07
不勾选：materializer 晚读/分类/未知诊断、Android
mapper 调用端及登录恢复等已盘点消费仍欠，EC-08原APK/ART/R8保持必做。

EC-06/07 元数据消费阶段（2026-11-06）：共享 snapshot、Android 查询/排序/标题/源导航、
专用迁移顺序、Creator 两端实际 DI/原 Authors 显式入口及旧 Desktop picker 完成阶段
实现和独审。隐藏/未置顶/语言排除不执行不需要的 getter，未知 ID 不探测、不造值，
错误逐源保留；恢复缓存与 Android Bundle 行 key 缺口均有正确红→修正。50项有效focused
组合、24文件正常严格格式通过，详见累计证据。不勾选 EC06/07：Desktop global完整描述、
登录恢复及源返回对象延迟 getter 的实际消费仍须验证保护，EC08原APK/ART/R8保持必做。

EC-07 登记键／实际诊断阶段（2026-11-06）：宿主保存首次登记 ID，失败时按 Source 引用
查询，未知不再探测 getter；typed 桥、raw 兼容入口及替换/清空边界有真实接线证据。
独审发现的反向锁依赖由持锁查询正确红→最小修正关闭，29项有效focused组合、18文件
严格格式通过。双端实际搜索使用现有logcat/stderr记录冻结包名/版本、阶段和脱敏原栈；
writer IO失败不遮原错误，接受结果后在CAS/锁外写入。35项最终focused、10文件严格格式
及独立审查通过。该阶段随实现/测试/维护说明提交，不勾选EC06/07：Creator初始元数据、
legacy picker和全局搜索安全描述仍须完成；EC08原APK/ART/R8原生门继续保留。详见累计证据。

EC-07 双端三源UI阶段（2026-11-06）：Android真实搜索错误区显示冻结名/版本及未知说明，
并以真实页面红测修复逐源结果并发覆盖和旧URL订阅残留。双端失败/健康/挂起源、后续
查询或版本重查的取消及进度收敛有真实UI证据。13项focused、准确5文件严格格式与独审
通过；不勾选EC07，完整元数据描述、实际诊断sink及R8原生门仍为必做。

EC-06 直接调用收口阶段（2026-11-06）：DeepLink五类实际source hook、目录prepare/
必要元数据以及保留迁移查询/详情factory/实际batch queue章节调用精确保护；DeepLink
错误页及迁移既有反馈不伪装空结果，下游控制/宿主错误不降级。独审发现的每章SManga
共享污染已由正确SQL红测与最小修正关闭。27项有效focused组合、准确17文件严格格式
与独立复审通过。本批不勾选EC-06：Creator初始元数据、legacy picker及全局搜索的安全
描述仍要按原范围完成；诊断和R8原生验收继续。

EC-06 Creator/Rx 阶段（2026-11-06）：实际 adapter、DB service/checkpoint、两个
interactor 和双端反馈保留兼容分类；坏源不走自动 backoff，手动检查成功可恢复普通due，
健康源继续。三类自定义 Rx 真实线程证据限定独立线程未交回回调的边界，不泛化豁免。
21项有效focused、11文件严格格式及生产AppModule UI补验通过，主代理独立审查通过。
本批不勾选 EC-06：已盘点的 DeepLink/迁移/prepareNewChapter 直接调用与后续源元数据
消费仍须闭合；EC-07/08保留原验收条件。

EC-07 Desktop 页面阶段（2026-11-06）：实际搜索入口从已安装端口冻结归属，child 失败
保留原包名/版本，错误区域显示本地化兼容提示与名称/版本；同 id 新实例或版本变化
不沿用旧查询，健康交集复用。320px 真实提交/健康并行/期间更新/再检查有证据，30 项
focused 和 10 文件严格格式通过。该阶段不勾选 EC-07：安全元数据描述、Android 页面、
日志 sink、完整三源取消及 R8 仍保留必做。

EC-07 身份传输阶段（2026-11-06）：共享兼容异常保留旧 JVM 构造入口，并能随 IO/反射
重包装保留冻结身份和精确阶段；Android 实际搜索在源调用前按同一 installed 快照的
Source 引用冻结包名/版本，更新安装记录不改变旧任务错误归属。修复新归属映射的插件
相等性覆盖和旧构造包装丢身份问题后，23 项 focused、5 文件严格格式及独立复审通过。
该阶段不代表诊断/UI 已接齐；Desktop 捕获、实际日志、
安全元数据描述和双端 UI gate 仍待完成，EC-07 保持未勾选。

EC-07 筛选器准备阶段（2026-11-06）：双端真实 model/coordinator 的搜索前过滤器已接既有
类型化准备结果；错误只落到对应源，健康源仍完成，显式重试/新查询重新评估。普通 IO
准备失败的 child retry 会重新准备，取消时恢复原失败且不覆盖新请求，既有分页重试保持
原页语义。正确红测后，13 项有效 focused 组合及精确 5 文件严格格式通过，详见累计证据。
该阶段不勾选 EC-07：操作前安装身份冻结、
并发诊断、双端 UI 触发搜索及完整取消接线仍待完成；EC-06/08 状态保持不变。

- 源码基线：`main@2425aca5e53112a4d3214cf398f62c04e6b6ff88`。工作区已有阅读器首开优化及相关文档改动，均属于其他任务，禁止修改、暂存或提交。实施前重新检查 HEAD、工作区和构建占用；发生并发写入时使用独立 worktree。
- 项目协程版本由 [`gradle/kotlinx.versions.toml`](../../gradle/kotlinx.versions.toml) 的 BOM 管理，故障基线为 `1.10.2`，EC-02 已将源码升级为 `1.11.0`；这是 APK/JVM 应用内置的 `kotlinx.coroutines` 版本，不是 Android、Kotlin 编译器或 Mihon 应用版本。源码升级和 JVM 测试通过不等于反馈者安装包已更新或 Android 原生验收已完成。
- 反馈截图确认 Zerobyw 为 **1.6.2**。从公开仓库取得的同版本 APK，其 DEX 的 R8 标识与报错一致：`125eb32eedce472b811a65094f178dcd8f9e57abeaf4c38ce7128866500da47d`；`b`、`y`、`z` 三个类均携带该标识。
- 该 APK SHA-256：`b04e4a298e6fef4fa58ef452ece77dc988025515f4b9b835d54a085c072dc874`。DEX 实际引用 `kotlinx.coroutines.BuildersKt.runBlockingK$default(CoroutineContext, Function2, int, Object): Object`，必须保留方法名中的 **K**。
- 本机平板正式应用 `0.19.4-aex.116 / vc134` 的 APK 中，`BuildersKt` 仅定义旧的 `runBlocking` / `runBlocking$default`；本机 Zerobyw 为 **1.4.21**。按扩展名称排除不同版本不构成有效排除证据。
- 当前直接故障已由 APK 静态证据定位为宿主/扩展协程 ABI 不匹配；未在反馈者 Android 17 设备复现，不据此归因于联想设备或 Android 17。最初截图与后补文本的宿主 R8 标识不同，不混用为同一构建。
- 官方 [1.11.0 JVM API 清单](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/kotlinx-coroutines-core/api/kotlinx-coroutines-core.api) 同时保留新旧两组方法；[1.11.0 发布说明](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) 是升级检查依据。
- 故障基线的 [`UncaughtExceptionInterceptor`](../../core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/network/interceptor/UncaughtExceptionInterceptor.kt) 只捕获 `Exception`。`NoSuchMethodError` 属于 `LinkageError`；[OkHttp 5.3.2 AsyncCall](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealCall.kt) 对非 IO 的 Throwable 可能先通知失败再重抛，因此仅在搜索协程外层捕获不构成保护。EC-04 的工作区实现和最终收口状态以上方实施进度为准。

前序采集原件位于忽略目录 `.test-tmp/search-crash-20261105/`，不是可携带的测试依赖。EC-01 必须将需要长期维护的最小证据、制品来源和校验值纳入现有夹具机制，不能让后续测试依赖本机临时路径或聊天附件。

## 2. 交付目标与明确边界

### 两项目标

1. **当前兼容**：生产运行时提供协程 1.11.0 的新旧 ABI；Zerobyw 1.6.2 首次动态域名获取、搜索不再因为缺少 `runBlockingK$default` 失败；旧扩展保留原有能力。
2. **未来隔离**：在本计划覆盖的加载、元数据、源 API、网络拦截器与响应解析链路中，缺方法、缺类、字段/接口签名不匹配等链接错误被归类为扩展不兼容。失败源有明确反馈，其他源/任务可继续，原始原因和可确认的扩展身份可诊断。

### 不纳入本批

- 不制作独立的扩展版本查看 APK，不新建诊断页面、遥测平台、自动上传、仓库全扩展扫描或启动时全 DEX ABI 扫描。
- 不顺带修复现有“导出崩溃日志时再崩溃”。该问题原因尚未确认，需要独立任务与失败测试；本批只确保兼容性失败能通过现有错误反馈/诊断通道表达。
- 不升级 OkHttp、Kotlin 编译器、serialization 等无关依赖，不扩大 extension-lib 接受范围，不绕过签名/信任校验，不修改扩展 APK，不修改共享数据库 schema。
- 不自动停用、卸载、降级或更新用户扩展，不永久修改源启用状态，不改写下载/阅读持久化协议。
- 不承诺宿主具备未来任意插件所需的功能；未支持的功能可以失败，但不得把可隔离的链接错误变成整轮搜索或进程崩溃。
- 同进程扩展自行创建且未交回宿主管理的线程、JNI 崩溃、主动终止进程、虚拟机资源耗尽等不在保证内。不引入独立进程沙箱；不以吞掉全部 Throwable 模拟安全隔离。

## 3. 实施前固定的行为契约

| 情况 | 应有行为 | 禁止行为 |
| --- | --- | --- |
| 真实 Zerobyw 1.6.2 调用新协程 ABI | 运行到域名获取/搜索的正常业务结果或可解释的网络失败 | 仍抛缺少 `runBlockingK$default`；用 HTTP 成功代替扩展真实执行 |
| 旧版扩展调用旧协程 ABI | 真实旧二进制仍能调用；结果、取消和分页语义不变 | 只重编译旧扩展后宣布向后兼容 |
| 受保护的扩展抛 LinkageError | 形成有原始 cause 的类型化兼容性失败，停止当前失败操作 | 作为空列表、网络断开、登录失败或无限重试处理 |
| 全局搜索一个源不兼容 | 仅该源显示不兼容，其他源正常结束，进度收敛 | 一个子任务失败取消其他源，或卡在加载中 |
| 阅读/下载遇到不兼容 | 当前页/章节/任务沿现有失败状态反馈，未成功内容不标成功；其他独立任务继续 | 清空现有内容、误写阅读进度/下载完成、重复重试造成风暴 |
| 用户取消/离页/新搜索替代旧搜索 | 保留现有取消与代际失效规则，旧结果不得回写 | 将取消转换成不兼容，或用 supervisor 让已取消任务继续 |
| 反射/Rx/IO 包装的链接错误 | 检查有界、可防环的 cause 链，优先识别兼容性原因 | 外层 IOException 抢先导致误报网络错误 |
| VM 致命错误/线程终止 | 保留应有传播；测试使用可控实例，不制造真实 OOM | 捕获后继续当作普通扩展失败；从全局 uncaught handler 返回假装恢复 |
| 归因与日志 | 绑定具体操作的 sourceId 及已安装记录中的包名/版本；无可靠身份时明确未知 | 用“最后运行的源”猜测、用共享域名唯一归因、自动采集查询词/凭据 |

`LinkageError` 识别须包含 `NoSuchMethodError`、`NoClassDefFoundError`、`AbstractMethodError`、`NoSuchFieldError` 等；`ExceptionInInitializerError` 等带 cause 的错误先检查取消及致命原因，不因外层属于链接错误而吞掉底层致命错误。分类只决定如何呈现插件调用故障，不预判责任一定在扩展：宿主缺 ABI 同样可能是根因。

## 4. 复用链路与实现约束

- **版本权威**：只修改现有协程 BOM，并检查各消费端实际解析到的版本；同源的 core/android/guava/swing/test 必须对齐。保留现有 Kotlin/AGP 组合，出现具体不兼容再报告，不顺带整套升级。
- **错误模型**：复用 `AppError`、`toSourceAppError()`、`SourcePageResult` 和 `SourceRecoveryAction`，增加最小的兼容性分类/恢复语义。底层异常放在 `core/common` 或更低既有模块，不能让 network/source-api 反向依赖 domain/app。
- **网络**：升级共享拦截器并验证 Android `NetworkHelper`、Desktop `DesktopNetworkHelper` 的实际链。默认客户端、别名、`clientForSource` 及扩展常用的 `network.client.newBuilder()` 都需验证。不能假定所有扩展都会经过 `clientForSource`。
- **扩展执行**：复用 `SourceMangaSearchService`、`SourceMangaUpdateService`、`SharedReaderCatalogRequests`、双端 reader/download adapter 和 Desktop `safeSourceCall`，只在真正执行第三方代码的边界加保护；数据库、持久化和宿主 UI 错误不得被误标为插件不兼容。不改公开 Source 接口或动态代理包装 Source，避免破坏运行时类型、反射、身份及 extension ABI。
- **加载与来源**：复用 Android `ExtensionLoader`、Desktop 加载器、已安装扩展/源归属快照。错误类型、取消/致命判定共享；制品读取、反射包装和平台状态接线留在 adapter。保护源元数据访问，但不修改 `Source.id` 算法。
- **界面**：沿用已有搜索卡片、详情/阅读/下载错误位置及扩展管理入口，使用既有 i18n。显示“不兼容，可能需要更新应用或扩展”，不保证更新一定可解。确定性不兼容不参与自动重试；用户通过现有显式刷新/重试重新检查，无须新增卸载或停用操作。
- **诊断**：原始 cause 在异常内保留；落盘/分享继续遵守现有反馈的脱敏、预览、授权与容量边界。诊断写入失败不得遮蔽原兼容性失败；不把请求 URL、搜索词或账号信息新增到通用日志。

## 5. 执行任务

### [x] EC-01：固定真实 ABI 回归与未来不兼容夹具

**目的/前置**：开始任何 production 修改前，建立可重放的红测；本项只准备和验证夹具，不实现保护。

- 复用已有 external-v15/v16 编译/加载夹具与 `extensions/real` 制品记录方式。固定 Zerobyw 1.6.2 的下载来源、SHA-256、R8 标识、版本；核对源码/资源许可与存放规范。缺制品时仅允许有界下载和校验，不能用随意同版本文件替代。
- ABI 测试从**独立编译、未捆绑协程库的扩展/探针**调用新方法，在当前 1.10.2 宿主上确认失败；同时保留旧二进制调用旧方法的对照。反射可补充精确签名检查，不能代替真实调用。
- 用现有受控扩展夹具增加“编译期具备、运行期宿主不存在的 API”变体。compileOnly 桩不得打入扩展或宿主产品；通过生产 loader 和实际源调用触发缺方法/缺类。它必须在宿主升级到 1.11.0 后仍失败，用于后续保护红绿。
- 覆盖可控的网络拦截器链接错误与 API/筛选器/解析错误；真实站点网络不作为自动化必需条件。真实 Zerobyw 动态域名和 HTTP 响应使用既有网络注入 seam/MockWebServer 或现有测试路由，执行真实扩展逻辑，不复制解析器、不改最终扩展字节码。
- **验收**：记录红测命令、宿主/扩展 hash、原始缺失方法和失败位置，确认非安装、信任、DI、权限或联网失败；旧接口对照通过。若真实调用的可控路由尚无 seam，先明确最小测试接线范围，不宣称夹具已完成。不得提交无法构建/运行的红测为产品完成项；与 EC-02 内聚整合后提交。

### [x] EC-02：统一升级协程 1.11.0 并证明新旧接口兼容

**依赖**：EC-01 的真实 ABI 红测与旧二进制对照。

- 将协程 BOM 更新为固定的 `1.11.0`；检查 Android 生产运行时、共享 JVM、Desktop 运行时与对应测试依赖解析结果，查清冲突/强制旧版本来源。禁止仅升级某个 module、仅替换本机 JAR 或手写 `BuildersKt` 兼容桥。
- 运行 EC-01 同一 ABI 调用用例，补新旧入口的默认参数/返回值、异常及取消行为。最小回归选现有 `HttpSourceApiContractTest`、`SourceOnlyQueryContractTest`、`SourceCallWrapperTest` 中直接相关用例；不因公共依赖升级自动启动完整模块测试。
- 用真实 Zerobyw 制品覆盖进程内首次动态域名获取以及随后搜索，不能只测试缓存已就绪路径；HTTP 403/超时单独归为网络环境结果，不伪装成扩展搜索成功。
- **验收**：新旧独立二进制均可调用，依赖解析一致，未改变无关库版本。此时只能称“JVM/对应测试运行时 ABI 已通过”，R8 后制品和 Android 原生运行的结论留待 EC-08。
- **交付**：EC-01/02 的最小夹具、来源清单、升级与 focused 证据同一内聚提交；未来不兼容夹具仍保持预期失败，不能通过删除它来使测试变绿。

### [x] EC-03：统一链接错误分类、致命/取消边界及恢复语义

**依赖**：EC-01 的未来不兼容夹具可用；消费 EC-02 后的运行库。

- 先对 production `toSourceAppError()` 写失败测试：直接及 IOException/反射/Rx 包装的链接错误、cause 环/深度界限、普通 HTTP 错误、取消与可控 VMError/ThreadDeath 对照。
- 最小实现底层类型化兼容性异常和共享 `AppError` 分类；先排除取消与致命 cause，再识别兼容性，最后沿用现有普通错误分类。增加错误枚举时定向处理其真实消费端穷尽分支，不借机重写通用错误系统。
- 兼容性不映射为 Network/MalformedData/NoResults，不触发登录/WebView challenge 或自动重试。保持可确认来源与操作阶段，原始错误作为 cause 保留；缺身份明确未知，不生成假的 sourceId。
- **验收/验证**：共享错误契约在 Android/JVM 两个实际编译目标的定向测试通过；取消与宿主致命错误未被兼容性分类吞掉。同步在现有维护文档补充分类、限制和失败处理，不能先更新公共功能承诺。

### [x] EC-04：在真实 OkHttp 异步线程内隔离链接错误

**依赖**：EC-03 的底层异常与分类契约。

- 先通过 MockWebServer + 真正的 `newCall().enqueue()` 运行 production 网络链，令后续扩展拦截器触发链接错误。只在同步 `execute()` 或 mock Chain 中测试不足以覆盖本次崩溃。
- 升级共享 `UncaughtExceptionInterceptor`，在受保护的 `chain.proceed` 内将非致命链接错误转换为专用 IOException 子类/既有等效 IO 传输异常，保留 cause。保证回调恰好一次，连接/任务资源释放，异常不再从 OkHttp worker 逸出；网络层不得伪造 HTTP 响应或绑定不可靠源身份。
- 验证双端实际 builder/别名/`newBuilder()` 接线以及拦截顺序；网络拦截器、应用拦截器分别覆盖。保留 UserAgent/Cloudflare 的名称和既有兼容约束。异常发生在保护拦截器之外的场景明确留给 EC-06，不误称已覆盖。
- **验收/验证**：扩展故障 → 一次 onFailure → 可识别兼容性结果；后续健康请求成功，记录 uncaught 监听未收到链接错误。监听按测试实例装配并恢复，不以修改全局 handler 吞错实现测试。取消、403/429/500 和原有 Android/Desktop 网络路由用例保持原行为。

### [x] EC-05：隔离加载、工厂及元数据访问失败

**依赖**：EC-03；不扩大 extension-lib 支持区间或改信任流程。

- 在 Android `ExtensionLoaderMetadataWiringTest`、Desktop `DesktopExtensionLoaderTest` 等既有入口加入受控坏扩展与健康扩展；覆盖构造、SourceFactory、名称/语言/id/client getter、过滤器准备中的链接错误，标明哪些 getter 实际在加载阶段消费、哪些在调用阶段消费。
- 将有问题的第三方代码调用纳入同一取消/致命规则；尤其审查当前构造阶段的宽泛 `catch(Throwable)`，不能再吞掉致命 cause。保留包级加载诊断、已安装清单及失败记录，不把坏扩展当未安装或伪造加载成功；不改变既有工厂加载原子性。
- 来源使用实际安装/制品记录；旧对象、卸载/替换后迟到任务不能绑到新版本。初始化只读取已需要的元信息，不执行网络探测或扫描整份 APK 来猜全部能力。
- **验收/验证**：坏扩展在既有管理入口可见失败，健康扩展仍可加载/搜索；初次启动与显式 reload 的定向测试通过，签名/信任拒绝、旧 runtime 回滚行为保持不变。未知元信息有明确占位，不因生成错误文案再次调用坏 getter 而崩溃。

### [ ] EC-06：覆盖单源 API、解析及阅读/下载调用边界

**依赖**：EC-03/04/05；只改本项列出的第三方调用边界。

- 先列出双端实际调用清单：筛选器获取 → 热门/最新/搜索；作品详情/章节更新；页列表/图片请求与解析。沿现有 service/reader/download adapter 补守卫，不把整个数据库事务、页面 reducer 或下载持久化过程包成“扩展调用”。
- 特别覆盖 `SearchScreenModel` 在 `loadPageResult` 之前调用 `source.getFilterList()` 的路径，以及网络回调之后才执行的 Rx/普通解析代码。只修改 `SourceMangaSearchService` 内 catch 无法覆盖这些路径。
- 原始 LinkageError 及 EC-04 的 IO 包装均产出同一个共享不兼容结果。保留 Desktop `safeSourceCall` 超时与取消语义，复用 SharedReaderCatalogRequests 的去重/取消规则，不重写阅读器配对、holder 或缓存算法。
- 检查既有错误投影是否丢失分类，特别是 `CatalogueCreatorDiscoverySourceAdapter.toCreatorSourceFailure()` 的通用 Network 回退。仅补本错误的保真映射和直接消费端反馈，并通过真实 adapter 调用验证；不借此扩建 Creator 能力或重写其错误体系。
- **验收/验证**：源 API 同步抛错、挂起后抛错、解析抛错各有真实调用用例；详情/章节失败不写成功元数据，页列表失败不冒充空章，图片/下载失败不提交完成状态。健康并行章节或下载仍完成，确定性错误不自动入队重试。旧请求在新搜索/窗口替代后不回写。
- **范围止点**：保护宿主管理的调用和网络链。扩展刻意自建全新客户端且删除保护链、未管理的线程/JNI 另行说明；不引入字节码注入或进程隔离去补无限范围。

### [ ] EC-07：双端全局搜索收敛、界面反馈与来源诊断

**依赖**：EC-03–06，使用未来不兼容夹具；不新建页面或导航体系。

- 先执行真实 SearchScreenModel/对应 Desktop coordinator：一个源链接错误、一个源正常返回、一个源挂起后取消。断言逐源卡片、总进度、健康结果和新旧查询代际；不能只构造 `SearchItemResult.Error` 验证类型存在。
- 仅在需要时采用现有监督/逐源结果机制，确保每个扩展故障已转为结果；`supervisorScope` 本身不替代错误消费或网络线程保护。宿主自身缺陷仍正常暴露，不将所有错误统一归给插件。
- 使用既有 i18n、搜索错误卡、阅读/下载错误位置及扩展管理反馈。展示可确认的扩展名/版本和兼容性说明；无自动循环重试、无误导性的登录/网络恢复按钮。显式重试在扩展/应用更新后可重新评估，不永久拉黑。
- 最小诊断字段固定为来源身份、版本、操作阶段、错误类型和原始异常关联；复用现有渠道及隐私约束。并发源不会串身份，记录失败不会遮蔽产品错误；不改反馈服务协议或崩溃导出实现。
- **验收/验证**：Android/ Desktop 各一条真实 UI 接线用例触发全局搜索，坏源提示不兼容，健康源显示结果且整轮结束；补显式重试、取消/新查询及诊断来源断言。Desktop 若改变布局/动作，先按现有 UI 规范补本页契约和原生组件测试，不制作 HTML 原型。

### [ ] EC-08：R8 后扩展集成验证与维护交付

**依赖**：EC-01–07 相关 focused 测试及按风险的一轮独立审查通过。

- 在独立快照/无构建争用的 worktree，使用现有 `benchmark`（继承 release 的 R8、non-debuggable、独立包名）制作一次最小 ABI/隔离验收包。外部扩展与 instrumentation 必须单独编译，不能将宿主缺失依赖捆入测试 APK 来掩盖问题；测试 keep 规则不得只存在于验收候选而漏掉 production。
- 核对 APK 包名、hash、R8 配置及 DEX 新旧签名；在 API36 的独立模拟器执行真实 loader + Zerobyw 1.6.2 冷初始化/搜索、旧二进制对照、未来不兼容源与健康源并行三个聚焦场景。站点不可达时用 EC-01 的可控路由证明调用链；不能据此声称真实站点当前可用。
- Desktop 使用现有签名 JVM JAR 路径及受控未来夹具做真实 classloader/网络集成；APK→JAR 仅按已存在 Legacy 路径边界验证，不为该 APK 新建转换兼容系统。
- **验收**：当前新接口真实可调用；未来坏扩展产生兼容性提示且未出现未捕获错误，健康源可继续，取消/资源释放正常。保存确切制品/命令/平台/结果和未覆盖条件。反馈者 Android17/联想实机为独立待验项，本轮模拟器结论不替代该设备验收；未经新授权不操作用户平板。
- **交付**：合并本批必要维护说明，记录实际实现与仍存限制、focused 结果、R8 集成证据及 commit。仓库级 README/网站/changelog 仅在用户验收本次功能后更新；不为 roadmap 收口运行全量、`finalParityAudit`、分配正式版本或执行发布。用户以后要求可分发 APK 时再走既有 `scripts/build-android.py candidate`、签名与版本流程，不交付 benchmark 为正式包。

## 6. 验证范围与命令约束

执行者先确认各 source set 的实际测试 task，再将最终类名/方法选择器记入同一份实施记录。以下只给范围，不授权无筛选地执行整模块或通配所有 `*Extension*` 测试；新增测试名为实施时确定的名字，不把计划名称当成已有测试。

| 任务 | 最小验证入口/直接相关既有测试 | 新增行为断言 |
| --- | --- | --- |
| EC-01/02 | `source-api` 双目标的 HttpSourceApiContractTest；外部 v15/v16 ABI 夹具；domain SourceOnlyQueryContractTest；Desktop SourceCallWrapperTest | 新旧二进制真实调用、升级前正确红、升级后同范围绿、真实域名首次初始化 |
| EC-03 | domain 错误映射/恢复契约；core 下层分类契约，Android/JVM 定向执行 | 包装优先级、cause 防环/上限、取消/致命、不误归网络 |
| EC-04 | core AndroidNetworkHelperExtensionCompatibilityTest；JVM NetworkHelperSourceClientTest；DesktopNetworkHelperTest / DesktopExtensionNetworkRoutingIntegrationTest 的相关方法 | 真 enqueue、一次回调、无 worker 逃逸、派生客户端、健康后续请求 |
| EC-05 | Android ExtensionLoaderMetadataWiringTest；DesktopExtensionLoaderTest | 坏/好制品并存、工厂/元数据、reload 身份与失败回滚 |
| EC-06 | domain 源查询/章节更新契约；Android HttpPageLoaderIntegrationTest 的相关方法；Desktop 源调用、SourcePageFetcherNetworkPolicyTest、DownloadSourceRequestIntegrationTest | 搜索前过滤器、同步/挂起/解析错误、无成功写入、无自动重试 |
| EC-07 | 扩展 Android GlobalSearchRecoveryBehaviorTest，DesktopGlobalSearchPolicyExecutionTest / GlobalSearchResultProductionWiringTest 的相关路径 | 真正触发搜索的并发失败/健康/取消、进度收敛、旧结果拒收、UI/诊断身份 |
| EC-08 | 仅本任务新增/受影响 instrumentation 方法与外部制品集成 | R8 签名、宿主真实 wiring、Zerobyw/旧 ABI/未来 ABI 三组原生场景 |

命令形状：`python scripts/gradle-coordinator.py run --key <本项唯一key> -- ./gradlew <已确认task> --tests <精确类或方法>`。Android instrumentation 使用现有 runner 的 class/method selector，不将 connected 全量任务当聚焦验证。所有重型 Gradle 在同一 worktree 串行；超时先读协调器状态，不重复启动或批量清理 Java。

每项新增行为均先红、确认原因，再最小实现并复验同一 focused 范围；纯夹具归档/说明不虚构产品红测。修改文件做 `git diff --check`，代码格式只跑相关模块的现有 Spotless 检查；格式 task 范围明确，不趁机格式化其他任务文件。依赖升级可能影响更多模块：先通过实际依赖解析和针对相关协程消费行为的测试限定传播面，出现跨模块失败或具体未覆盖风险时，说明最小扩大范围、成本并取得用户批准，不能自行追加完整模块/全仓库验证。

## 7. 预算、实施角色与停止条件

- 计划编写轮仅写本 roadmap、0 个子代理并完成一轮文档核验；2026-11-05 用户以 active goal 明确授权实施完整 EC-01–08。实施采用下述角色与 focused 预算，不改其他 manifest/checkoff 系统，不推导正式发布或用户平板操作授权。
- 用户后续要求执行时，按仓库自动路由选择一个实施子代理承担首个内聚任务簇，主代理负责接口和集成验收；默认串行、单一代码/Gradle 写入者。核心共享异常/运行时变更在消费前安排未实施者独立审查，预算为一轮独立审查、必要时一轮修复复审，最多两个子代理；实施前声明实际模型、职责、所有权和预算，不在本计划写死未经用户指定的模型。
- 初估 EC-01/02 为 1–2 小时，EC-03/04 为 1–2 小时，EC-05/06 为 1–2 小时，EC-07/08 为 1–2 小时；合计约 **4–8 小时**，受夹具测试接线、Gradle/R8 缓存和模拟器条件影响。该估算包含双端接线及聚焦原生验证，不包含 Android17 厂商设备等待、正式发布或另一个日志导出故障。
- 每项默认一轮正确红→绿及修改后的同范围复验；失败只修本项并补受影响范围。EC-08 默认一次聚焦 R8 集成，只有具体代码/keep/接线变化使旧证据失效时才补对应场景。无默认完整模块/全量额度。
- 网络或工具失败先记录真实阶段；下载失败至多按既有代理规则有限重试。原制品不可得时保留缺口，不能用受控夹具代签真实 Zerobyw 验收；未获设备授权不以操作用户实例代替模拟器。
- 如新增无关依赖、改变存储/信任协议、需要独立进程、扩大全量验证或显著超出预算，停止该扩大项并报告原因、最小替代范围和成本；已授权的独立任务可以继续。不要为凑计划完成度把必要断言删掉或改为不适用。
- 每个内聚实现批次验证后仅提交本任务改动；只写当前任务状态和一份累计证据，不创建逐任务快照/重复报告。自动提交不等于推送授权；推送需核对完整提交集合、固定 SHA 并遵循 Git 交付细则。

## 8. 最终验收清单与文档入口

- [ ] **当前兼容**：独立编译的 Zerobyw 1.6.2 经宿主真实 loader，在首次域名获取/搜索中不再缺少 `runBlockingK$default`；保留受控 HTTP 结果和网络环境边界。
- [ ] **向后兼容**：未重编译的旧 ABI 扩展仍可搜索；新旧接口在 R8 后实际可调用。
- [ ] **未来故障**：升级后仍调用不存在接口的夹具在真实异步网络和源 API 路径中均只失败本源，健康源完成且无未捕获链接错误。
- [ ] **调用覆盖**：加载/工厂/元数据/过滤器、搜索/详情/章节、页列表/图片解析、下载均有对应 production 边界与失败状态证据；无假空结果、假完成或自动重试风暴。
- [ ] **取消与致命边界**：取消、代际失效、资源释放保持原语义；致命 cause 未被吞掉。
- [ ] **可诊断**：不兼容提示、版本/来源关联和原始异常可供现有诊断通道使用；身份不串源、未知不猜测，日志收集和分享授权边界保持原状。
- [ ] **双端交付**：共享契约、Android R8 原生场景与 Desktop 实际运行链分别有证据；厂商设备、正式候选及用户验收缺口单列。

维护时首先遵循 [验证与接线](../agent-guides/verification.md)、[Android 构建](../agent-guides/android-build.md)、[Desktop 自动化](../agent-guides/desktop-acceptance.md) 和 [进度、交接与报告](../agent-guides/governance-and-reporting.md)。扩展来源以 [源与扩展权威基线](source-extension-authority-baseline.md) 为对照；Desktop 制品边界遵循 [扩展制品策略](../architecture/desktop-extension-artifacts.md)，本次故障隔离属于下游跨平台可靠性增强，不宣称来自原始 Android Mihon。反馈隐私边界遵循 [自有应用更新与问题反馈](../architecture/application-updates-and-feedback.md)。实施时在既有维护说明补充实际行为，不能把本计划当作产品已实现的说明。
