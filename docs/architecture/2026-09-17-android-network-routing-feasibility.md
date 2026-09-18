# Android 代理管理与插件覆盖：技术可行性预研

日期：2026-09-17。状态：源码与官方资料预研完成，未实施、未进行 Android 运行时验证。

## 1. 结论与决策边界

**有条件可行，建议推进；不能把 Desktop 实现直接复制到 Android，也不能承诺接管所有插件流量。**

| 能力 | 判断 | 主要条件 |
|---|---|---|
| 全局跟随系统、强制直连、手动 HTTP/SOCKS5 | 高可行性 | 复用 OkHttp 策略；SOCKS、系统变化和 PAC 须用 Android 发布运行时验证 |
| 默认 HttpSource 按插件包覆盖代理 | 高可行性 | 已有 sourceId → 插件包查询和 clientForSource 入口，补齐初始化与 DI wiring |
| 插件缓存 network.client.newBuilder() 派生客户端 | 条件可行 | Android 26–33 的调用方身份识别尚无已验证方案；不能仅更换全局客户端 |
| WebView 全局代理 | 条件可行 | AndroidX WebKit 功能检测、异步应用及既有登录流程验证 |
| 同进程多个 WebView 同时按插件使用不同代理 | 普通 ProxyController 方案不满足 | 该 API 是进程范围；需要额外隔离设计，不纳入首版默认承诺 |
| 插件自建 OkHttpClient、原始 Socket、外部浏览器 | 无法统一保证 | 不属于宿主管理客户端链路；展示边界，不能标记完全受管 |

建议首版目标为“宿主管理的 HTTP 请求支持全局与插件覆盖”，保留 API 26 最低版本。旧插件兼容性与 WebView 是发布门槛，未通过时只能交付明确标注覆盖范围的版本，不能宣布完全对齐 Desktop。

本预研只交付本文。后文批次是实施建议，不是已启动的 roadmap；没有创建 active-task、修改 capability 状态或完成 checkbox。后续若进入实施，按仓库规则建立执行计划、委派实施及独立审查。

## 2. 核对基线与现有链路

读取时 HEAD 为 `d80cead332`；工作区存在其他任务对 Android 扩展加载/权限、DI、双端浏览入口及 Desktop 网络文档的未提交修改。本文基于当时可见工作树，相关接口在实施前需再次核对；本任务不修改这些文件。

| 事实 | 源码依据（仓库根目录相对路径） | 对接意义 |
|---|---|---|
| minSdk 26、compile/target 36；OkHttp 5.3.2 | `buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt`；`gradle/libs.versions.toml` | 不能用本机 JDK 17 代替 Android API 可用性判断 |
| Android 的 clientForSource 返回统一 client | `core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt` | 尚无插件路由；保留 Cookie、UA、缓存、Cloudflare 拦截器 |
| HttpSource 默认 client 调用 network.clientForSource(id) | `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt` | 默认源无需逐插件修改 |
| ExtensionManager.getExtensionPackage(sourceId) 已存在 | `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt` | 复用包归属，不能重新靠域名分组 |
| APK 经 ChildFirstPathClassLoader 后实例化 Source/SourceFactory | `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt` | 注册身份必须早于构造器与 createSources，覆盖加载期取客户端 |
| Desktop 按插件派生客户端，动态 selector、独立 pool 和 route 观察 | `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopNetworkHelper.kt` | 抽取共同策略与契约，不照搬整个 Desktop helper |
| Desktop 通过 StackWalker 的类引用识别插件 classloader | `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionNetworkContext.kt` | 平台差异必须留在身份 adapter |
| Android 封面请求优先用 source.client，下载使用 source.getImage | `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt`；`app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt` | 复用真实业务链路；仍需阅读器、重定向与插件覆写集成验证 |
| Android 已有网络设置组与插件详情 Screen/ScreenModel | `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAdvancedScreen.kt`；`app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/details/ExtensionDetailsScreen.kt` | 追加入口和状态，不另建插件管理页面 |
| Cloudflare 使用 Android WebView；用户 WebView Activity 未声明独立进程 | `core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewInterceptor.kt`；`app/src/main/AndroidManifest.xml` | 修改 OkHttp 不会自动配置这些 WebView |

桌面现有对照测试包括 `DesktopNetworkHelperTest`、`DesktopExtensionNetworkRoutingIntegrationTest`、`DesktopExtensionSystemProxyIntegrationTest`。本文只阅读了测试，不将历史测试或源码存在当作 Android 已通过的证据。

## 3. 复用方案

### 3.1 共享语义与平台边界

保留现有全局三模式、插件四模式以及包名粒度：

```text
宿主请求 ───────────────────────────→ 全局策略
图源 → 已验证插件身份 → 插件策略 ─┬→ 跟随全局 → 全局策略
                                 ├→ 跟随系统 → Android 系统 adapter
                                 ├→ 强制直连 → 应用层 DIRECT
                                 └→ 手动代理 → 此插件固定入口
```

将 Desktop 的模式、入口解析、优先级、配置错误、策略快照与结果模型提取到现有共享模块：纯数据/状态放 `core/common` 可共享源集；`java.net`/OkHttp 接线放 Android/JVM 可共用的适当源集，具体 source-set 布局在实施时确认，不把 JVM 类型强塞进未来非 JVM 平台公共接口。

现有 `NetworkPreferences`/`PreferenceStore` 提供持久化接口，Desktop adapter 保持原键与旧配置迁移；Android 增加命名明确的全局及包级配置。Android 不依赖 `app-desktop`，不复制 Windows 注册表、Mixed 端口探测或 Desktop Cookie 实现。全局/插件 UI 分别复用各端原生 Compose 页面；共享用户语义与资源键，平台布局保留。

现有 `DesktopProxyRuntimeConfig` 解析器只支持无认证的 `http://`、`socks://`、`socks5://`，拒绝 userinfo、query、fragment 和非根路径。首版沿用边界，不混入 HTTPS 代理传输、订阅、节点切换或认证凭据管理。HTTP 代理承载 HTTPS 目标的 CONNECT 与 `https://` 代理入口是不同概念。

### 3.2 系统策略与失败语义

Android adapter 对每个 URI 委派系统 `ProxySelector`；通过公开 `ConnectivityManager.getDefaultProxy()` 辅助展示系统 HTTP 代理配置。不能只读一个 host:port 就丢掉 bypass/PAC，也不能用修改 `ProxySelector.setDefault` 或全局 JVM 属性的方式实现插件覆盖。系统网络变更时重新读取 selector/状态，避免永久捕获过期实例。

OkHttp 显式 proxy 优先于 proxySelector，因此派生插件客户端需要清除继承的显式 proxy，再装入插件 selector。手动地址非法或代理不可达时，返回明确错误，不自动添加 DIRECT 候选。跟随系统允许系统本身返回 DIRECT，但读取异常不能伪装成“已直连”。这些语义与 Desktop 共用契约。

“强制直连”仅控制应用 HTTP 层，不绕过 VPN/TUN；不申请 VpnService，不占用用户现有 VPN。手机 `127.0.0.1` 指手机自身，不能把 Windows 代理地址原样当成电脑入口；远端/LAN 地址是否可达由实际网络与监听配置决定。

### 3.3 插件身份：必须分两类解决

**默认 HttpSource：**优先复用 sourceId → package 查询。另建轻量身份注册端口，避免 `NetworkHelper → ExtensionManager → 插件构造 → NetworkHelper` 的初始化环。classloader → package 在实例化前注册，sourceId 映射在构造完成后发布；卸载/替换时清除旧代记录。构造期 id 尚未注册的情况，可通过新增宿主内部的 source 实例/类归属解析入口补足，同时保留已有 ABI；这仍需真实 APK 测试。

**旧插件直接读取 network.client：**Desktop 的 StackWalker 方案仅可作为 API 34+ adapter 候选。Android 官方将 StackWalker 标为 API 34，当前官方 Java 11 desugaring 清单未列出它；不能假设 desugar_jdk_libs 2.1.5 会补齐 API 26–33。

低版本候选是“加载期间显式作用域 + 后续调用栈类名与已登记类归属辅助识别”，但类名不是 classloader 身份。同名共享库、多插件协程、混淆和优化都可能造成歧义；仅 ThreadLocal 不保证跨线程/协程正确。不得将按类名猜测包装成已验证的完全接管。必须先做签名发布 APK 实验，确定识别准确性与开销。

若候选未达标，首版明确限制旧派生客户端的覆盖范围，保持其既有全局链路并提示不保证插件覆盖；若产品要求所有这些插件都严格支持，则本项阻塞发布，需追加显式扩展 API/兼容桥设计。不得用隐藏 API、全局 Injekt 临时替换或强行提升 minSdk 绕过问题。

保留 `getClient`、`getNonCloudflareClient`、`getCloudflareClient` 等扩展 ABI，且不丢失 Android 原本有/无 Cloudflare 拦截器的语义差异。由宿主取得的通用客户端继续走全局，不能把仓库、更新、追踪等请求误归到最近使用的插件。

### 3.4 动态更新、连接池与 DNS

首版保持 Desktop 产品边界：全局手动策略/DoH 修改在重启后生效，显示保存值与生效值；插件覆盖对后续新请求生效，在途下载不强制中断。系统本身的网络代理变化与用户修改全局偏好不是同一事件，必须单独测试。

Desktop 已有按插件独立 pool 与策略变化时清理空闲连接的机制，可作为复用起点；不能推断 `evictAll()` 已解决并发活跃 HTTP/2 连接与所有派生客户端问题。验收需证明新请求不会复用不适用的旧路由；若失败，再限定范围引入策略代际/客户端重建方案，并同样保护 Desktop 行为。

Android 当前 `DohProviders.kt` 会通过 `DnsOverHttps.Builder().client(build())` 捕获创建时的客户端。只对最终业务 client 改 selector，可能让 DoH 仍走旧全局策略。**这是源码推导出的风险，未做运行时复现。**建议共享 DNS 配置语义，按有效路由构建无 DoH 的引导客户端，再构建对应 DoH resolver，避免循环解析；沿用现有 provider/bootstrap 地址及源自定义 DNS 的兼容性。验证 HTTP 代理、SOCKS、DIRECT 各自是否触发目标 DNS，区分目标解析、代理地址解析和 DoH 请求，不能笼统承诺“全部 DNS 都在代理端”。

### 3.5 WebView 与验证登录

AndroidX `ProxyController` 的覆盖是进程范围，异步应用；使用前必须检测 `WebViewFeature.PROXY_OVERRIDE`，加载前等回调。当前未发现工程显式声明 WebKit 依赖，若采用需加入版本目录并做设备特性检测，不能仅按 OS 版本判断支持。

全局 WebView 策略可以研究接入；插件 A/B 同时需要不同策略时，不能逐次 setProxyOverride 就声称隔离。页面子资源、验证弹窗及 Cloudflare 后台 WebView 会互相影响。串行租约也只有在全部相关 WebView 生命周期都受控时才可能成立，不能默认成立。

建议首版分别显示“HTTP 请求覆盖”与“网页验证覆盖”。插件手动代理与 WebView 策略不一致时，在进入网页或触发验证前明确反馈，不静默切回其他出口。确需一致出口的插件，应阻止自动降级并给出可执行选择，例如返回设置调整代理后重试；不能把不一致的验证成功当作该代理链路验收通过。

若必须支持并发插件验证，再独立设计隔离 WebView 进程、数据目录、Cookie 交换和退出恢复。该项明显扩大架构，不列入首版。外部浏览器不受 Mihon 代理设置控制。

## 4. 用户入口与反馈建议

| 用户能力 | 建议入口 | 反馈与边界 |
|---|---|---|
| 全局代理 | 更多 → 设置 → 高级 → 网络 → 网络代理 | 三模式；编辑对话框校验后保存；已保存/生效策略、重启提示、连接测试 |
| 插件覆盖 | 浏览 → 扩展 → 已安装 → 扩展详情 → 网络路由 | 四模式；说明作用于插件内所有图源；HTTP/WebView 覆盖范围分开展示 |
| 找到插件代理 | 已有图源设置页增加“所属扩展的网络路由”跳转（实施时核对导航） | 跳到同一详情配置，不另存一套按 sourceId 的值 |
| 连接诊断 | 全局与插件网络区 → 测试连接 | 插件测试使用实际 source.client；显示目标、HTTP 状态、观测路由或失败阶段 |
| 恢复继承 | 插件网络路由 → 跟随 Mihon 全局设置 | 显示将使用的全局策略；保留输入草稿与生效配置的区别 |

诊断和业务必须经过同一 production client/DI/selector；允许有限诊断超时或新 pool，但不能创建另一个不带插件逻辑的“测试专用网络栈”。HTTP 403/429/500 表示已收到 HTTP 响应，不自动归因于代理握手失败；DNS、连接、TLS 与 HTTP 分阶段反馈。缓存命中无新连接时显示缓存或历史 route，不能冒充本次实际网络 route。

不增加普通保存的多余确认；如提供批量重置/删除全部配置，则需确认及结果反馈。代理地址和插件域名属于设备相关配置，不默认同步到其他设备；实施时核对 Android 备份与恢复的偏好筛选，避免恢复一个无效的回环入口造成全局断网。不记录代理凭据或完整带查询参数的请求 URL。

## 5. 实施前需通过的实验与验收

以下尚未执行，不能引用为测试通过证据。先写行为失败测试，再最小接线、回归；禁止源码字符串测试替代行为。

| 验证组 | 必须覆盖 | 通过条件 |
|---|---|---|
| 双端共享策略契约 | 3×4 优先级、解析、无效手动配置、继承/系统区别、保存与生效值 | Android/Desktop 运行相同契约；破坏 production 策略时测试失败 |
| Android DI 与真实 APK | 默认源、构造期取 client、SourceFactory、lazy/缓存 newBuilder、线程/协程、同名类、卸载重载 | 包 A/B 同时访问同域名仍命中各自代理；宿主请求走全局；无法归属如实报告 |
| HTTP 与 TLS 集成 | 直连、HTTP CONNECT、SOCKS5、重定向、空/缺失/畸形响应、403/429/500、超时及无效代理 | 实际请求到目标或测试代理；无静默直连；沿用真实解析链路 |
| 业务 wiring | 搜索→详情→章节→阅读图片、封面、下载、仓库/更新/追踪 | 切插件覆盖只影响本包；缓存/重试不绕开策略 |
| 连接与 DNS | HTTP/2 并发、缓存派生客户端、策略变化、DoH 开关、IPv4/IPv6、系统代理变化、bypass/PAC | 新请求策略一致；在途行为明确；DNS 实际路径有观测证据 |
| WebView | 功能支持/不支持、异步回调、Cloudflare、网页登录、A/B 并发、后台恢复 | 不串用代理；无法保证时有正确限制与反馈 |
| UI/导航 | 设置入口、详情网络区、图源快捷跳转、错误/重启反馈、Screen 实例化、DI 解析 | 用户能触发并看到结果；跳转符合现有 Voyager 层级 |
| 发布运行时 | 至少 API 26、一个 26–33 中间版本、34+；R8 release APK 与真实扩展 APK | HTTP/SOCKS/TLS/插件身份在 ART 上成立，不能用 JDK/JVM 测试替代 |

实验先使用隔离本地测试服务与测试插件，不访问真实账号。真机验证需具备对应设备或模拟器、可用代理以及可分发测试 APK；本轮未核验这些环境。PAC、厂商 ROM 与真实扩展兼容率只能按实际样本报告。

建议后续执行顺序：

1. **风险实验**：优先证伪 API 26–33 身份识别与 WebView 隔离假设；不能通过则收紧产品承诺或单独重新设计。
2. **全局能力批次**：抽取共享策略、Android adapter、全局 UI、诊断及双端契约，保留 Desktop 现有迁移与入口。
3. **插件能力批次**：加载身份、派生客户端、插件 UI、DNS/连接更新与业务隔离，一并交付生产 wiring 与测试。
4. **发布收口**：相关单元/集成/格式检查、独立审查及 Android release 运行时矩阵；若修改 Desktop，共享改动需按项目脚本完成 Desktop 发布验收。

工作量粗估：风险实验 1–2 人日；共享/全局 2–3 人日；插件与 UI/集成 3–5 人日；发布验证及修复 2–3 人日，合计约 8–13 人日，非承诺工期。设备缺失、旧插件身份实验失败或要求隔离 WebView 会扩大范围；这些条件触发时应先报告证据与新方案，不默默增加进程架构或降低 Android 版本覆盖。

## 6. 官方资料与证据等级

资料核对日期均为 2026-09-17。以下平台约束是官方资料事实；上文架构是基于源码的建议，运行时兼容性尚待实验。

- [Android StackWalker](https://developer.android.com/reference/java/lang/StackWalker)：API 34 起提供，解释旧版 Android 无法直接复用 Desktop 身份实现的边界。
- [Android Java 11 desugaring 清单](https://developer.android.com/studio/write/java11-default-support-table)：本轮查询没有 StackWalker 条目；这不能单独证明所有 desugaring 版本行为，故仍以最低版本实际运行作为门槛。
- [ConnectivityManager.getDefaultProxy](https://developer.android.com/reference/android/net/ConnectivityManager#getDefaultProxy())：返回当前默认 HTTP 代理配置，供系统状态展示；不等价于每个 URL 的最终路由。
- [Android Socket](https://developer.android.com/reference/java/net/Socket)：公开 Proxy 构造入口；SOCKS 在具体 Android runtime 的行为仍需实测。
- [AndroidX ProxyController](https://developer.android.com/reference/androidx/webkit/ProxyController)：进程范围 WebView override、特性检测和异步生效边界。
- [AndroidX ProxyConfig](https://developer.android.com/reference/androidx/webkit/ProxyConfig)：代理与 bypass/fallback 规则；手动模式不能误加直连回退。
- [OkHttp 5.3.2 官方源码](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt)：本轮通过代理读取对应 raw 源码，核实 proxy 优先级、默认 selector 及 newBuilder 继承配置/连接池。官网旧 API 页面返回 404，使用版本源码替代。

本轮未运行 Gradle、安装 APK、切换系统代理或接入真实插件网络。只做文档路径/内容核对与 `git diff --check`，不宣称上述功能已实现或 bug 已修复。
