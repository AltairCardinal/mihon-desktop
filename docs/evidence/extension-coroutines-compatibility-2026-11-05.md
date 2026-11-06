# 扩展协程兼容性与故障隔离累计证据

日期：2026-11-05。实施起点：`main@736c6d18d1`。EC-01/02 已审查并提交
`b13d4523df147c7f47082df244df126b98310304`；EC-03/04 本地实现与 focused 完成，
待主代理独立审查／提交。EC-05–08 待实施／验收。本记录继续追加，不建立另一套 checkoff 状态。
工作区原有 Android 阅读器首开及相关文档修改受到保护，未纳入本任务。

## 范围与制品

唯一 production 变化是 `gradle/kotlinx.versions.toml` 的协程 BOM 1.10.2 → 1.11.0。
没有升级 Kotlin、OkHttp、serialization；没有增加兼容桥、改变扩展 lib 支持区间、
信任或存储协议。新增资源接线仅用于 Android 单元测试消费共用独立 JAR。

可携带自动夹具为独立薄 JAR，源码／重建前置见
[`scripts/fixtures/extension-coroutines/README.md`](../../scripts/fixtures/extension-coroutines/README.md)。
编译目标是独立项目，不依赖当前 host production 输出；future 的 API 来自原有
固定上游 v0.20.4 编译快照，干净 clone 须先按 README 执行既有 AEX-00 生成器和编译。
自动测试只读取归档 JAR，不重新编译或联网。

| 夹具 | 独立编译输入／SHA-256 | 作用 |
| --- | --- | --- |
| old | 协程 1.10.2；`1afaad085990336aded600c817711461f994c0f2fabf4adc27e3fb7bc61d5f82` | 旧 runBlocking / runBlocking$default |
| new | 协程 1.11.0；`c5544fb161b7a7d217134bc4b6955faf8333c920463c53014d5ef2c272562710` | 新 runBlockingK / runBlockingK$default |
| future | 固定外部 v1.6 Source + compileOnly 桩；`37243fcb5e8dc11f05d3aa56cac1af9bb1f3621ac45d6a17fc73882858082bcc` | 过滤器／解析／拦截器缺方法，搜索缺类 |

`javap -c` 已核对 old/new 的真实 invokestatic 目标。所有 JAR 的 entries 已核对：
不含 kotlin／kotlinx runtime、host API、`org.jsoup.Jsoup` 或 `ec.absent.FutureApi` 桩。
future 保留既有独立样本类，不能通过把桩或升级运行库塞进它来使其成功。
共享测试也验证 probe SHA 和无捆绑实现，future 通过 production DesktopExtensionLoader 加载。
宿主实际 core-jvm JAR SHA-256：1.10.2 为
`5ca175b38df331fd64155b35cd8cae1251fa9ee369709b36d42e0a288ccce3fd`，1.11.0 为
`d1d75aa01dffbb4d1c520e67e4c4e7f5f6174718e7cb4632412503f2f0e604fa`。

真实 Zerobyw 来源、长度、SHA、R8 标识和许可边界固定在
[`zerobyw-1.6.2.provenance.json`](../../app-desktop/src/test/resources/extensions/real/zerobyw-1.6.2.provenance.json)。
APK 与官方同版本 JAR 都不入 Git；`scripts/ec-fetch-zerobyw-fixture.py` 有界下载并校验，
`--existing` 可验证已有输入而不联网。当前源码分支不构成发布制品确切编译提交或
再分发许可的证据；没有把未知信息写成确定事实。

## 红测与前置诊断

所有下列重型 Gradle 均经 `scripts/gradle-coordinator.py` 在同一 worktree 串行。
日志保存在忽略目录 `.gradle-coordinator/<key>.log`，不作为可携带 fixture 依赖。
后台协调器从创建时隔离控制台，不隐藏用户终端，不操作用户平板。

1. `ec-fixtures`：外部构建第一次因 PowerShell 未引用 `-Dhttps.proxyHost` 参数而把
   `.proxyHost=127.0.0.1` 解析为 task；环境／命令错误，无产品结论。
2. `ec-fixtures-build`：引用完整 `-D...` 参数后，独立 `:old:jar :new:jar :future:jar`
   构建通过（24 秒）。构建命令见 fixture README；协程为 compileOnly。
3. `ec-abi-red`：BOM **仍为 1.10.2** 时执行：

   ```text
   python scripts/gradle-coordinator.py run --key ec-abi-red -- .\gradlew.bat :source-api:jvmTest --tests eu.kanade.tachiyomi.source.CoroutineBinaryCompatibilityTest --offline --no-parallel --max-workers=2 --console=plain
   ```

   old 通过，new 在 `ec.newabi.BlockingProbe.defaultValue` 真实调用失败：
   `NoSuchMethodError: Object kotlinx.coroutines.BuildersKt.runBlockingK$default(CoroutineContext, Function2, int, Object)`。
   2 测试／1 失败（13 秒）。这是正确 ABI 红；不是安装、信任、DI、资源或联网失败。
4. `ec-desktop-red`：真实原 APK 经既有 converter 转为 JVM JAR后，production loader
   装载混淆类 `i` 即遭 `VerifyError`：`i.intercept @901` 的 Object 无法赋给 `f`。
   尚未进入协程新接口或受控 HTTP，**不能当作 ABI 红**。不修补转换器／APK，
   原 APK 冷域名／搜索门槛转 EC-08 Android 原生 DEX/R8 验证。该已知必败尝试没有留作
   默认显式集成套件的新增失败测试；后续同名测试改用官方签名 JVM JAR并明确区分制品。
   此次 future 测试因表达式返回值非 void 未被 JUnit 发现，已改 `runBlocking<Unit>`，
   不能把此轮记为 future 已通过。

## 升级后的 focused 验证

`CoroutineBinaryCompatibilityTest` 对新旧二进制执行默认 context／返回值、显式
CoroutineName、原始 IllegalStateException、已取消 Job 的取消行为；不是反射查符号。
反射仅用于进入独立类，最终执行真实协程库实现。

- `ec-abi-green`：`source-api:jvmTest` 的新旧 ABI 2 项及 HttpSourceApiContractTest 10 项通过。
  同批 `source-api:testDebugUnitTest` 的 HTTP 10 项通过、ABI 2 项因 Android unit classpath
  未包含 commonTest resources 而失败（Required value null）。已补
  `android.sourceSets["test"].resources.srcDir("src/commonTest/resources")`，待同范围复验。
- `ec-desktop-green`：offline 解析缺缓存 `kotlinx-coroutines-guava:1.11.0`，未开始测试。
  这是依赖缓存缺口；按既有代理规则执行一次 online 精确范围，key `ec-desktop-green-online`。

### 执行范围失误与纠正

`ec-abi-green` 与 `ec-shared-green` 曾将多个 test task 列在所有 `--tests` 之前。
Gradle 的选择器绑定最后 task，前面 `:source-api:jvmTest` 因此意外运行其全部 23 项：
前者 23 通过；后者 21 通过、2 个新增 provenance 资源读取失败。
后者在此失败后停止，未执行后续 Android/domain 测试。它们不是获批的整模块或全量验证，
不得当作扩大范围获批或最终代码全量通过证据，也不据此再运行整模块。

错误原命令（如实保留）：

```text
python scripts/gradle-coordinator.py run --key ec-abi-green -- .\gradlew.bat :source-api:jvmTest :source-api:testDebugUnitTest --tests eu.kanade.tachiyomi.source.CoroutineBinaryCompatibilityTest --tests eu.kanade.tachiyomi.source.online.HttpSourceApiContractTest --no-parallel --max-workers=2 --console=plain -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808
python scripts/gradle-coordinator.py run --key ec-shared-green -- .\gradlew.bat :source-api:jvmTest :source-api:testDebugUnitTest :domain:jvmTest :domain:testDebugUnitTest --tests eu.kanade.tachiyomi.source.CoroutineBinaryCompatibilityTest --tests eu.kanade.tachiyomi.source.online.HttpSourceApiContractTest --tests tachiyomi.domain.source.service.SourceOnlyQueryContractTest --offline --no-parallel --max-workers=2 --console=plain
```

新增 provenance 失败因 `Properties.apply` 内 `javaClass` 接收者是 Properties，已改为
外层测试 class loader。每个 task 后紧跟其选择器的纠正命令：

```text
python scripts/gradle-coordinator.py run --key ec-shared-focused -- .\gradlew.bat :source-api:jvmTest --tests eu.kanade.tachiyomi.source.CoroutineBinaryCompatibilityTest --tests eu.kanade.tachiyomi.source.online.HttpSourceApiContractTest :source-api:testDebugUnitTest --tests eu.kanade.tachiyomi.source.CoroutineBinaryCompatibilityTest --tests eu.kanade.tachiyomi.source.online.HttpSourceApiContractTest :domain:jvmTest --tests tachiyomi.domain.source.service.SourceOnlyQueryContractTest :domain:testDebugUnitTest --tests tachiyomi.domain.source.service.SourceOnlyQueryContractTest --offline --no-parallel --max-workers=2 --console=plain
```

**实际结果**：`ec-shared-focused` 28 秒、exit 0。source-api JVM 与 Android unit 各
2 ABI + 10 HTTP，domain JVM 与 Android unit 的 SourceOnlyQuery 各 3 项，合计 **30 项通过、0 跳过**。
XML 各目标仅包含指定类；未重跑其它测试。

### Desktop 补验进展

`ec-desktop-green-online` 中 future 1 项实际执行通过，四个故障点仍预期不兼容。
官方同版本 JAR 通过 production 签名认证、现有 adapter／loader，并完成冷域名与两次真实搜索解析。
唯一新增测试失败是观测顺序断言：外层应用拦截器先看到初始搜索，再看到它内部嵌套的域名请求；
已改按 MockWebServer 真正接收次序断言域名 → page1 → page2，不能将前者当作请求已发出。
既有 SourceCallWrapperTest 的 403 原断言 Authentication 与 production 一直映射 Forbidden
不符，已定向修为 401 → Authentication、403 → Forbidden；没有改变产品 HTTP 分类。
该批其余 5 个 wrapper 用例通过；修正后的同范围结果见下方最终 focused 表。

### 格式入口诊断

app-desktop 未应用 Spotless。第一次局部 init 指向不存在的 extension，第二次从 source-api
指向跨 project 文件被 Spotless 拒绝；均未改产品文件。
第三次仅为本次 Desktop 3 文件临时应用现有 `mihon.code.lint`，source-api 2 文件定向 target。
原 convention 的 build 排除 filetree 仍导致 app-desktop/tmp 目录遍历，线程栈确认后经协调器
stop 取消 `ec-format-targeted`，不追加扫描；daemon 收到取消通知。下一次清空本次 init 的
exclude filetree，以准确文件集合检查；不修改项目的长期 lint 配置。
`ec-format-files` 使用 `targetExclude(project.files())` 后 15 秒、exit 0；
source-api 本次 build.gradle.kts／ABI 测试和 Desktop 本次 future／Zerobyw／wrapper
三个测试，共 5 文件的 Spotless Kotlin apply/check 通过。其版本为现有 ktlint 1.8.0。
source-api 只发生格式变化，复用其仍有效的 focused 行为结果；Desktop 在格式后复验。
一次性 init 脚本 `.test-tmp/ec-format.init.gradle` 是过程产物：在 afterProject 对上述
准确文件设置 target，仅在 Desktop 没有 spotless extension 时临时应用既有
`mihon.code.lint`，并清空本次 targetExclude；未改项目 build 配置或其它任务文件。

### 最终 focused 与实际运行时

```text
python scripts/gradle-coordinator.py run --key ec-desktop-final -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.extension.FutureExtensionAbiFixtureTest --tests mihon.desktop.extension.RealZerobywCoroutineCompatibilityTest --tests mihon.desktop.extension.SourceCallWrapperTest -PincludeIntegrationTests=true --offline --no-parallel --max-workers=2 --console=plain
python scripts/gradle-coordinator.py run --key ec-runtime-alignment -- .\gradlew.bat -I scripts/fixtures/extension-coroutines/runtime-alignment.init.gradle :app:ecCoroutineRuntimeAlignment :source-api:ecCoroutineRuntimeAlignment :domain:ecCoroutineRuntimeAlignment :app-desktop:ecCoroutineRuntimeAlignment --offline --no-parallel --max-workers=2 --console=plain
```

Desktop 显式设置 `EC_ZEROBYW_JAR` 为经获取脚本验证的本地官方 JAR；integration tag
已通过 `includeIntegrationTests=true` 开启，以下 **没有跳过**。

| 证据 key／目标 | 精确范围 | 结果 |
| --- | --- | --- |
| ec-shared-focused／source-api JVM | ABI 2 + HttpSourceApiContract 10 | 12 通过 |
| ec-shared-focused／source-api Android unit | 同上 | 12 通过 |
| ec-shared-focused／domain JVM | SourceOnlyQueryContract | 3 通过 |
| ec-shared-focused／domain Android unit | 同上 | 3 通过 |
| ec-desktop-final／Desktop | FutureExtensionAbiFixture | 1 通过 |
| ec-desktop-final／Desktop | 官方签名 Zerobyw 1.6.2 冷域名／两次搜索 | 1 通过 |
| ec-desktop-final／Desktop | SourceCallWrapper | 6 通过 |

`ec-desktop-final` 18 秒、exit 0；本簇最终 focused 合计 **38 项通过、0 失败、0 跳过**。
没有宣称最终代码重跑全量。依赖解析 `ec-runtime-alignment` 14 秒、exit 0，检查：

- app：`releaseRuntimeClasspath`、`releaseUnitTestRuntimeClasspath`；
- source-api／domain：各自 `jvmRuntimeClasspath`、`jvmTestRuntimeClasspath`、
  `debugRuntimeClasspath`、`debugUnitTestRuntimeClasspath`；
- app-desktop：`jvmRuntimeClasspath`、`jvmTestRuntimeClasspath`。

12 个实际 configuration 的协程 core/core-jvm、android、guava、swing、test/test-jvm
以及传递 jdk8/slf4j 和 BOM，凡存在者均为 **1.11.0**；无 unresolved dependency 或旧版本。
Desktop 保持原有 kotlinx-coroutines-android 排除。唯一版本源 diff 为协程 BOM，
没有手写强制旧版本或主动升级其它库。编译器自己的 Gradle/Kotlin daemon classpath
不属于产品 runtime，不能拿它内含协程 1.8.0 推翻产品实际配置解析。

获取脚本 `--existing` 对原 APK 和官方 JAR 均校验通过；`git diff --check` 通过。
已有阅读器文档仅产生其原有 CRLF 提示，未修改或格式化它。脚本、fixture、来源说明和
测试虽分多个文件，但共同构成单一可重放 ABI 回归，不引入产品状态系统。

## EC-01/02 交付边界

- 主代理已独立核对红测原始缺方法、各目标 XML、依赖解析、格式、夹具与制品校验，EC-01/02 提交为上述 b13d4523df。
- future 源 API、过滤器和解析仍保留预期故障，不能视为 EC-05–07 的调用边界／全局 UI 已完成。
- 原 APK 的 R8 后 Android 原生调用和反馈者厂商设备均未验收。不得据 JVM 证据宣称这些已通过。

## EC-03/04：下层分类与真实异步 HTTP 隔离

本簇起点 b13d4523df，单一代码／Gradle 写入者，无新子代理，不修改 reader 或 roadmap。
24 个 Kotlin/build 文件、3 个文案 XML、维护文档与本累计证据共同构成同一跨模块协议批次。

### 接口与边界

- core `inspectSourceFailure()` 以身份检查最多 64 个 cause；闭环穷尽身份后结束，未知超深尾保留原错误传播。
  VMError／ThreadDeath 优先于取消，取消优先于链接错误；Initializer／反射／Rx／IO 包装不能抢先吞掉原因。
  两个 equals/hashCode 相同但身份不同的 wrapper 仍能检查内层。
- `SourceCompatibilityException : IOException` 保留原 cause，不猜源身份或操作阶段。
  UncaughtExceptionInterceptor 只保护其后的 chain.proceed；非致命／非取消链接错误转为专用 IO。
  普通 IO 保留原实例，取消保留既有 IO 传输，普通 AssertionError 和未知深尾仍传播。
- domain mapper 先检查 fatal／未知尾／取消／链接，再沿用 AppErrorException、HTTP、IO 和 parser 分类。
  新 `AppError.ExtensionIncompatible` 不降为 Network／MalformedData／NoResults；分页 recovery 为 None。
  StoredAppError 只增加类型识别，无字段／schema 变化；实际 Json encode/decode 往返及旧未知 payload 可读。
- English／简体／繁体文案及直接错误选择器、必要 sealed 消费端做最小增补，无页面／布局／导航变更。
  这不等于 EC-07 完整搜索 UI 验收。维护规则见 [网络维护说明](../architecture/desktop-network-routing.md)。

已知后续风险：`CatalogueCreatorDiscoverySourceAdapter.toCreatorSourceFailure()` 的通用 else
仍会把新类别投影成 CreatorSourceFailure.Network，明确留待 EC-06/07，不在本簇扩 Creator 模型。
加载／factory／getter／过滤器、网络之后的 parser、阅读／下载和全局搜索汇合仍待 EC-05–07。

### 正确 RED 与真实网络证据

以下均经协调器 run，完整参数保存在同 key 的 `.gradle-coordinator/<key>.json`，原日志为同名 `.log`。
所有 test task 均紧随其精确 --tests；没有本簇新增整模块或全量。

| key | 精确入口 | 正确失败 |
| --- | --- | --- |
| ec03-mapper-red | domain:jvmTest / SourceCompatibilityErrorContractTest | 7 项中 6 失败、普通 HTTP 对照通过 |
| ec04-network-red | core:common:jvmTest / SourceFailureBoundaryContractTest | 2 项失败：真实 enqueue 后 worker 仍抛链接错误；包裹 VMError 被当 IO 吞掉 |
| ec03-presentation-red | app-desktop:jvmTest / SourceCompatibilityPresentationTest | 应有兼容文案，实际 Unknown error |
| ec03-android-presentation-red | app:testReleaseUnitTest / ExtensionInstallErrorMessageTest.compatibility uses* | 期望兼容资源，实际 null；原有 maxRetries=2 导致同一方法 3 次失败，不是 3 个不同用例 |

不是编译红或符号存在检查。首轮 core JVM 2 + domain JVM 7 在 `ec0304-core-domain-green` 通过。
网络共用 helper 只接入 core／Desktop 测试 source sets，future 拦截器来自 EC-01 不变的独立 JAR。
每场景独立 executor，用 afterExecute 观测 Throwable，callback 之后 join 实际 worker 至结束再断言，
不靠 sleep 阴性结果，不改全局 uncaught handler，也不吞错误。验证一次 onFailure、零 onResponse、
任务归零、健康后续请求，以及响应／executor／连接池释放。

Android production 默认／nonCloudflare／cloudflare／clientForSource 的派生客户均执行应用与网络拦截器。
Desktop 经 production loader 加载 future，并复用原 DerivedClientHttpSource 的非 inline 外部栈入口，
真正通过 StackWalker 上下文解析三个别名及 source 客户；断言 loader 登记 owner、非 global 客户与 route scope。
nullable test bridge 不论前值 null 与否均恢复。新 Desktop 测试首次未注入 appPreferences 而回退 global，
这是测试前置错误，补既有内存偏好后再通过，未修改产品 routing。

### 最终 focused：51 个独立用例

```text
python scripts/gradle-coordinator.py run --key ec0304-shared-platform-green -- .\gradlew.bat :core:common:jvmTest --tests eu.kanade.tachiyomi.network.SourceFailureBoundaryContractTest :core:common:testDebugUnitTest --tests eu.kanade.tachiyomi.network.SourceFailureBoundaryContractTest --tests "eu.kanade.tachiyomi.network.AndroidNetworkHelperExtensionCompatibilityTest.future linkage*" :domain:jvmTest --tests tachiyomi.domain.source.service.SourceCompatibilityErrorContractTest --tests mihon.domain.error.AppErrorTest --tests tachiyomi.domain.source.service.SourceOnlyQueryContractTest :domain:testDebugUnitTest --tests tachiyomi.domain.source.service.SourceCompatibilityErrorContractTest --tests mihon.domain.error.AppErrorTest --tests tachiyomi.domain.source.service.SourceOnlyQueryContractTest --offline --no-parallel --max-workers=2 --console=plain
python scripts/gradle-coordinator.py run --key ec0304-desktop-green -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.extension.DesktopExtensionLinkageIsolationTest --tests mihon.desktop.extension.SourceCompatibilityPresentationTest --tests mihon.desktop.extension.SourceCallWrapperTest --tests "mihon.desktop.extension.DesktopExtensionNetworkRoutingIntegrationTest.extension derived client*" --offline --no-parallel --max-workers=2 --console=plain
python scripts/gradle-coordinator.py run --key ec0304-platform-final -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.extension.DesktopExtensionLinkageIsolationTest :app:testReleaseUnitTest --tests eu.kanade.tachiyomi.ui.browse.extension.ExtensionInstallErrorMessageTest --offline --no-parallel --max-workers=2 --console=plain
```

| 证据／目标 | 范围 | 结果 |
| --- | --- | --- |
| shared-platform-green／core JVM | 边界／普通 401/403/429/500／取消 | 3 通过 |
| shared-platform-green／core Android unit | 同上 3 + production alias 1 | 4 通过 |
| shared-platform-green／domain JVM | compat 7 + AppError 6 + SourceOnlyQuery 3 | 16 通过 |
| shared-platform-green／domain Android unit | 同上 | 16 通过 |
| desktop-green／Desktop，未受后续改动影响的有效结果复用 | presentation 1 + wrapper 6 + 原 routing 1 | 8 通过 |
| platform-final／Desktop | 修正前置后的真实 scoped alias／enqueue | 1 通过 |
| platform-final／Android app release unit | 精确资源／安装文案类 | 3 通过 |

共享 **39** + Desktop **9** + Android app **3** = **51 项独立用例通过，无未解释失败、无跳过**。
shared-platform-green 42 秒，platform-final 28 秒；只对失败的 Desktop 新方法补验，未重复已有效的 8 项或共享 39 项。
组合证据不等于最终代码重跑了全量。

### 格式诊断、内存事件与收口

`ec0304-format` 原失败来自**旧增量历史**中的 D:/Github 路径，不是当前 target 扫描 AndroidCookieJar。
D:/Github 为 D:/Shell/Github 同仓库联接。只读 target diagnostic 与实际 Spotless 8.3 字节码确认，
处理 InputChanges 时先 relativize 才判断 REMOVED，旧路径因而被拒绝；afterProject 当前 target 已正确。
仅对四个内部 spotlessKotlin 使用原生 `--rerun` 强制非增量，未用 --rerun-tasks 重跑依赖；doFirst
断言实际 target 为 core 6／domain 6／Desktop 7／app 5，准确 24 文件，排除 filetree 为空。
`ec0304-format-exact` 处理历史后只报两条新增 when 过长，拆行后 `ec0304-format-finish` 全部通过。
prefs 注入修改**之后**又跑 `ec0304-isolation-format`，通过，然后才运行 platform-final。
没有修改项目全局格式系统或 reader；一次性 init 是忽略目录中的过程产物。

Android 红编译时空闲内存降至约 1.85GB/32GB，停止追加负载并检查进程。实施者基于前簇创建身份
及单次空闲栈停止 Kotlin daemon 59124，随后测试编译 RMI 断连，说明空闲快照与停止间出现复用竞态；
这是清理判断失误，不能归因产品。Gradle 自动 fallback 编译成功后才执行正确 RED，未重启该构建。
内存回升约 6GB；早先把 60476 按内存猜成 Kotlin daemon 的表述已纠正：当时 43636／60476 都是
GradleDaemon，52536 是 43636 的 fallback 子 Java，CPU 推进后退出。此后未停止任何 daemon，
不再用旧 PID／单次栈判定可清理；最终无活构建／测试 worker，标准缓存 daemon 单独报告。

没有全模块／全仓库、R8、安装、平板操作或正式构建。本簇无版本变化，EC-01/02 的生产依赖解析仍适用。
主代理已独立核对共享分类优先级、真实 enqueue worker 终止、外部别名上下文、测试接线和原始日志/XML，审查通过，随本批内聚提交；实施者停写后再授权下一簇。原 APK／厂商设备缺口继续留待 EC-08。

## EC-05/06 实施证据（在途，未完成整项验收）

起点为 EC-03/04 提交 `dea72d1aa3`。本节与上文累积复用，不把局部绿测描述成
Desktop、阅读／下载、Creator 或真实原 APK 已完成；用户既有 reader 改动未修改。
所有命令的完整 argv、时间和退出码均保存在 `.gradle-coordinator/<key>.json`，
原始日志是同 key `.log`。仅 focused task，每个 task 紧跟自身 `--tests`。

### 共享第三方调用 guard 与域服务

`guardSourceOperation` 只包实际第三方 lambda。稳定 phase 不携 query、URL 或身份猜测；
旧 `SourceCompatibilityException(Throwable)` JVM 构造保留。fatal、取消、未知深尾仍传播，
普通异常及普通 Error 原样；已有精确 phase 即便被 IO／反射包装也保留，原外层作为 cause。
MangaSearch 三类查询、MangaUpdate 实际 getMangaUpdate／id getter、共享 reader flight 的
source 调用接入；章节目录 require 验证留在 guard 外。健康源与坏源实际并行执行。

| key | 精确类／方法 | 结果及原因 |
| --- | --- | --- |
| ec06-source-operations-red | domain JVM / SourceOperationCompatibilityContractTest | 3 项正确失败，raw Linkage／错误分类 |
| ec06-source-operations-green | 同一类 | 3 项通过，27 秒；健康并行源成功、故障 flight 去重 |
| ec06-operation-phase-red | core JVM / SourceOperationGuardContractTest | wrapped 精确 phase 被宽阶段覆盖的正确失败 |
| ec06-operation-phase-green | 同一类 | 3 项通过；主代理独立审查公共接口 |
| ec06-host-error-red | 同类 broad host catch 方法 | 1 项正确失败：未标记宿主 Linkage 经 IOException 包装后未传播 |
| ec06-host-error-green | 同一类 | 当时 4 项通过；随后独立审查发现 marked initializer cause 内 AssertionError 的组合缺口，下一组红绿补齐，见下文 |

`searchAllPages` 默认 filter 那一行尚无对应真实失败，已撤回本簇预先写入的 guard；
后续 filter 入口实施先补正确 RED。reader flight 等候者取消与最后等候者退出的既有
focused 回归已在 JVM／Android 各执行 2 项，均通过，见下文。

### Android 加载／管理纵向链路

loader 对 construction／factory／实际消费的 id、name、lang 分别保护；不探测 client、
baseUrl 等 lazy getter。`LoadResult.Failure` 携带 PackageInfo 的包名／版本和 phase／typed cause，
不猜 source 身份。Manager 保留失败 map；inventory 的既有 `LOAD_FAILED` 是保留安装存在事实，
不是把坏包发布为 Source.Success。显式 retry 以对象身份 token 过滤同版本替换的迟到结果，
Receiver 的 load-start 立即使旧 token 失效。原事务 reload 的失败仍交 installer 处理回滚。

Robolectric 初始红的 OS 夹具校正必须与正确产品红分开：

- `ec05-android-loader-red` 所有 site 都 Error，尚未到预期 getter；发现真实 ChildFirstPathClassLoader
  的 system-first Source 身份与 sandbox fixture 不同，这份结果不算产品 RED。
- `ec05-android-loader-platform-red` 是夹具漏 chapterPageParse 的编译失败；
  `ec05-android-loader-platform-red2` 是健康控制调用 loadIcon 时 OS mock 缺口。均非产品 RED。
- 只加 internal classLoaderFactory OS seam；保留原两参 suspend 签名和默认真实 loader。
  不改 Source ABI／信任策略；mock 实际 ApplicationInfo.loadIcon，不改产品 icon 行为。
- `ec05-android-loader-correct-red`：健康 HttpSource 先真实 Success，clientReads=0；
  ctor/factory 实际 Error、id/name 实际 Success、lang 真实逃逸 NoSuchMethodError，形成正确矩阵红。
  entered 计数证明到达真实构造／factory／getter。
- `ec05-manager-correct-red`：初启 Failure 未进入管理失败 flow 的正确红；随后出现库存期望
  PRESENT 与既有 LOAD_FAILED 不符的测试断言问题，修正断言，未改变库存政策。
- `ec05-manager-retry-red`：显式 retry 未调用 loader，预期调用 1、实际 0 的正确红。
- `ec05-manager-loader-green`：loader 1 方法及 Manager 初启／same-version late retry 2 方法共
  3 项通过，25 秒。之后追加 factory 原子性／fatal／取消、实际 Tab UI、信任后失败测试，待补验。

`ec05-banner-red` 初始是单独 Banner + 真实 model／manager 的组件红，不能作为真实管理入口 gate；
主代理指出后测试改为 Navigator → extensionsTab → ExtensionScreen。原命令用了 leading-wildcard
类名，app release 的 forkEvery=1 下观察到大量短命 Test Executor 和显著发现开销；只观察到目标
方法失败，不声称执行了其它模块测试。后续改用完整 FQCN，并以真实 Tab 精确 RED/GREEN 为准。
该旧组件输入已通过协调器定向停止，终态 CANCELLED exit130，理由是旧输入后续发现／重试
已无真实 Tab 验收价值；核对本 key Test Executor 已退出，未停止 daemon。宽 pattern 是否
导致发现开销仅为推断，未宣称已证实根因，也不将取消任务计作 UI 通过。真实 Tab、格式与
相关生命周期回归的最终证据如下；EC-05/06 整项仍未完成。

### 本簇 Android 管理纵向阶段收口（39 个独立用例组合证据）

后续正确 RED 与最小修正：

| key | 实际失败／结果 |
| --- | --- |
| ec06-marked-initializer-red | guard 已标记的 ExceptionInInitializerError(AssertionError) 经 broad helper 又被抛出，1 项正确失败 |
| ec06-marked-initializer-green | 同一 guard 类 5 项通过；已标记且 fatal／cancel／未知尾检查通过就保留原分类；未标记 wrapped 宿主 Error 仍传播 |
| ec05-tab-trust-red | trust 后 Failure 丢失为正确红；UI 匿名 Voyager Screen 缺 key，是测试环境错误，不计 UI RED |
| ec05-tab-owner-red | 补稳定 Screen key 并等真实 model 加载完成后，112 行真实 Tab 缺 banner；新版本失败的 retry 被旧 busy 阻挡。两方法各 retry3 次正确失败 |
| ec05-android-vertical-green | 11 项中 10 项有效通过；仅旧 trust-success 因新事件路径额外触发通知而失败3次，保持旧 trust 不触发该通知后定向复验 |
| ec05-trust-final | trust-success／持久化失败／trust 后兼容失败 3 项通过，25 秒 |
| ec05-receiver-manual-red | 真实 Receiver／Manager：广播 A 先开始，手动 B 成功后 A 失败覆盖 runtime，正确失败；随后 owner-completed 接线修复 |
| ec05-trust-owner-red | 晚广播失败／较早完成的 trust 成功对照本来通过，不能称为产品 RED |
| ec05-trust-start-order-red | 较新的 trust 挂起时旧广播先结束，trust 成功结果丢失；正确失败后给 trust 装载分配新 owner |
| ec05-catalog-projection-red | 真实 Tab discovery 同包候选+健康候选，预期 Available 仅 healthy，实际含坏包；正确失败 |
| ec05-catalog-update-red | 简单过滤所有同包候选会隐藏较新版修复入口，真实 Tab 等不到更新候选；正确失败后只过滤同／旧版，并保留原安装动作、标为 Update |

`ec0506-stage-focused-final` 在上述前半段修正及格式后通过（55 秒）：core Android 5、domain
JVM／Android 各 3 个源操作+2 个 flight 取消、Receiver 5、Manager 5，总 25 项。
随后仅对 owner／投影实际变化补验，复用无变化的共享和初始扫描结果。

`ec05-android-management-final` 最终通过（33 秒），XML 精确为：Manager 5、ReceiverOrdering 7、
真实 Tab UI 1、原共享 projection adapter 1，共 14 项，无跳过。UI 从真实空列表失败开始，
随后加载同版 catalog 并保留健康候选，再切换较新版候选，点击 Update 实际到达原
Manager.installExtension／installer adapter（只替换制品下载执行边界），失败反馈保留，
最后点击 source load retry 成功移除 banner；没有伪造 Installed，也没有自动更新或自动 retry。

| 最终有效目标 | 独立用例数 | 证据 |
| --- | --- | --- |
| core JVM guard | 5 | marked-initializer-green |
| core Android guard | 5 | stage-focused-final |
| domain JVM | 3 源操作+2 flight 取消 | stage-focused-final |
| domain Android unit | 同上 5 | stage-focused-final |
| Android app 最新管理／receiver／projection／真实UI | 14 | android-management-final |
| Android app 未受后续变化影响的 loader／初启／scan-race | 1 loader+1 initial failure+3 scan-race = 5 | android-vertical-green 日志的有效通过复用 |

共享 20 + Android app 19 = **39 个独立用例组合通过**，不是最终代码重跑整模块或全量。
完整命令均为同 key JSON 内的 argv；例如最终 Android 命令为：

```text
python scripts/gradle-coordinator.py run --key ec05-android-management-final -- .\gradlew.bat :app:testReleaseUnitTest --tests eu.kanade.tachiyomi.ui.browse.extension.AndroidExtensionLoadFailureUiTest --tests eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiverOrderingTest --tests "eu.kanade.tachiyomi.extension.ExtensionManagerTest.explicit load retry*" --tests "eu.kanade.tachiyomi.extension.ExtensionManagerTest.replacement failure can retry*" --tests "eu.kanade.tachiyomi.extension.ExtensionManagerTest.trust persists*" --tests "eu.kanade.tachiyomi.extension.ExtensionManagerTest.failed trust persistence*" --tests "eu.kanade.tachiyomi.extension.ExtensionManagerTest.trusting an incompatible*" --tests "eu.kanade.tachiyomi.ui.browse.extension.ExtensionPresentationWiringTest.android get extensions consumes*" --offline --no-parallel --max-workers=2 --console=plain
```

格式使用忽略目录 `.test-tmp/ec0506-format.init.gradle` 准确集合：core 2／domain 4／app 14，
共 20 Kotlin 文件。清空 targetExclude，并 doFirst 断言实际文件集合；只对内部 spotlessKotlin
使用 --rerun 处理旧历史，不使用 --rerun-tasks。中间仅新测试长名称／长 lambda 行被拒绝，
拆行与导入后 `ec0506-stage-format-update` 全部通过（5 秒），截至该轮未再改 Kotlin，最后才执行
android-management-final。未扫描巨大 tmp，未改用户 reader 文件或项目全局格式系统。

维护边界同步在 `docs/architecture/desktop-network-routing.md` 7.6.2，fixture README 撤回
“尚未实现 EC-04”旧描述。根代理已独立核对公共 guard、包级失败、真实管理页入口、安装投影、
Receiver／重试／信任 owner 及原始日志/XML，完成审查后随本纵向阶段提交；EC-05/06 保持未整项完成。

#### 同轮独立审查末项：保存期间的信任 owner

主代理在停写后的独立审查指出：信任在保存成功后才分配新 owner，旧广播若在同步
`TrustExtension.trust()` 保存期间结束为 Untrusted，会先使旧 token 失效；用户实际保存
成功后反而不再执行 loader。该行为仍属于本批已改加载并发协议，不扩大业务范围。

- `ec05-trust-save-owner-red`：真实 Manager／Receiver，旧广播先开始，再在 Dispatchers.IO
  独立 worker 上受控阻塞同步保存；旧广播返回 Untrusted，保存成功后应继续本次装载。
  保存前／保存中无插件调用断言通过，随后 runtime 仍为空，正确 RED（同一方法 retry3，23 秒）。
- 最小实现只把信任 owner 分配前移到意图开始；保存成功后仍校验 owner 才移除 untrusted
  并调用 loader。保存失败不移除原 untrusted；保存中真正的新 package-start 仍能使旧意图失效。
- `ec05-trust-save-owner-green`：同一类 5 个相关交错方法（新增保存期两项及原三项）+
  Manager 信任成功／持久化失败／兼容失败 3 项，**8 项通过，无跳过，21 秒**。
  旧广播与信任双方线程均受控，结束释放 latch、取消并等待所属 scope，没有全局 hooks 或 sleep 证明。
- `ec05-trust-save-format`：仅 ExtensionManager.kt 与 ExtensionInstallReceiverOrderingTest.kt，
  doFirst 断言准确 2 targets，内部 spotlessKotlin --rerun／Apply／Check，4 秒通过。
  未重跑原 39 项无变化范围，格式仅排版；维护说明 7.6.2 同步保存期 owner 契约。

原组合 39 项加新增保存期两项，当前 **41 个独立用例组合证据通过**；最终补验的 8 项包含
原有 6 项，不能再加成 47。原始 argv／终态／日志仍在上述 key JSON／log。本次不提交，
再次停写交根代理审查，EC-05/06 未完范围不变。

### 仍未完成的范围（继续同一目标）

以下为 Android 加载阶段收口时的待项；默认 HTTP 链的后续进展见下一节。

Desktop loader／reload／failure 管理、Android installer typed reload 回滚的独立集成补验、
HttpSource client 调用期／baseUrl 管理投影 getter、filter 前置实际入口、reader／下载确定性故障不自动重试、
Creator 分类保真、默认 HTTP parser hook 与响应关闭证据仍待实现／验收。
core asObservable 当前为 execute + 同步 onNext，awaitSingle 无默认 subscribeOn；这不是
enqueue callback 的证据。后续 Rx 测试须先证明实际线程／逸出位置，不改全局 Rx hooks，
不把扩展自定义 operator 一概排除。原 APK／R8／厂商设备仍留 EC-08，当前不操作设备。

### EC-06 阶段：默认 HttpSource 请求／Rx 解析与响应所有权（2026-11-06）

本段由根代理实现，原实施代理独立审查；与尚在进行的 Desktop 加载管理／filter 消费
修改分开提交，不据此勾选整个 EC-05/06。
本批超过 400 行主要来自同一个真实线程／HTTP／响应所有权测试矩阵，五个文件仍属于
单一默认 HTTP 边界；主要风险是 Rx fatal 传播与资源提前关闭，由上述双目标行为测试覆盖。

- `ec06-http-default-red`：只运行新增 `HttpSourceCompatibilityBoundaryTest` 的 JVM
  目标，4/4 正确失败。请求／client 及图片请求仍抛原始 NoClassDefFoundError／
  NoSuchMethodError；parser Linkage 没有送达 onError；fatal 本身保留但响应关闭计数为 0。
  使用 MockWebServer 的真实响应与独立 executor／线程级 uncaught handler；不修改全局
  Rx 或 uncaught hooks，不以同步 mock mapper 替代实际 Rx 链。
- 最小实现把 client／request hook 的 guard 与 OkHttp.newCall 装配分开，七类默认
  parser 在 map 内转换 Linkage，原搜索 defer 时机不变。getImage 仅守卫请求构造，
  成功的 Response 仍由调用者读取并关闭；不改公开 Source／HttpSource ABI。
- `ec06-http-default-green` 名称虽含 green，实际状态为 FAILED：JVM 15 项中 14 项通过，
  新增第 5 项证明 use() 把关闭阶段的可控 VMError 压成 suppressed；Android 未执行。
  改显式 finally 后，`ec06-http-default-close-green` 实际也为 FAILED：JVM 16 项中
  15 项通过，第 6 项证明 parser AssertionError 先传播会隐藏 close 的 EIIE(Cancel)。
  两次失败均保留原始日志/XML，没有把命令名称当通过证据。
- 资源收尾最终按双方 fatal → 未知深尾 → 取消 → 宿主 Error 的优先级处理，普通
  cleanup 才可附加到 primary；OkHttp 对普通 close 异常的 quiet 语义保持原状。
- `ec06-http-default-final`：同一个新增类 6 项与直接受影响的既有
  `HttpSourceApiContractTest` 10 项，在 `:source-api:jvmTest` 和
  `:source-api:testDebugUnitTest` 各运行一次，**32 项通过，零跳过，6 秒**。
  原样 argv/终态位于 `.gradle-coordinator/<key>.json`，原始日志为同名 `.log`；
  两目标 XML 在 `source-api/build/test-results/{jvmTest,testDebugUnitTest}/`。
  新测试内部覆盖七类 parser、七类 request/client getter、健康后续响应、fatal／取消／
  普通 IO、图片响应不提前关闭；既有范围涵盖 Range、进度、HTTP 状态、取消及旧重载。
- `ec06-http-default-format-final`：仅 HttpSource.kt 与新增 BoundaryTest，doFirst 核对
  精确 2 targets，Spotless Apply／Check 通过（Gradle 1 秒）；此前格式失败仅为长签名，
  拆行后复验，不修改行为。根代理重新读取四份 XML，确认 32/0fail/0skip；未实施本段的
  原实施代理独立审查 production、测试和维护说明后给出 GO，无阻断。所有本段 worker
  均终止，未关闭缓存 daemon，未触碰其它任务的阅读器改动。

这是默认 HttpSource 的 production hooks 与资源证据；不能替代扩展覆盖 fetch/get、
自定义 operator／调度、reader/download、Creator、全局搜索和 R8 原生验收。维护边界
见 `docs/architecture/desktop-network-routing.md` 7.6.3。没有运行模块／仓库全量，未操作设备。

### EC-05/06 后续批：安装回滚、实际筛选器和元数据投影（阶段验证）

本批仍未完成整个 EC-05/06。下列绿测与尚待收口的 Desktop 验证分别记录，不以已有
loader 冷态不读 getter 代替实际业务消费测试。

- `ec05-android-typed-rollback-focused`：真实 Manager → Installer → AndroidInstallPort →
  Coordinator 的单方法通过（9 秒）。候选装载返回带可靠身份的兼容失败，旧 APK bytes、
  signers、trust、版本和原 Source 实例恢复；验证两次 reload、失败记录清除及 lease 释放。
  OS/装载结果是既有可控 seam，其余事务是 production。此前 `ec05-android-typed-rollback`
  错把长期 StateFlow 全量收集，测试不收敛；协调器停止本 key 后已退出，不能计为产品红。
- `ec06-filter-preparation-red`：共享三方法确认旧默认 getFilterList 调用会逸出；
  `ec06-filter-preparation-green`：相同三方法在 JVM／Android 各运行一次，6 项通过。
  Content 保留 FilterList 引用；Failure 保留 typed error；失败时默认 searchAllPages
  不调用搜索页，控制异常／宿主 Error 不降级，普通 IO 保持原分类。
- Android 真实 Browse 的构造与 Retry 曾分别由 `ec06-android-filter-ui-red`、
  `ec06-android-filter-retry-red` 确认原始链接错误及 Retry 未重新准备筛选器的业务失败。
  `ec06-android-filter-ui-green`：真实 Navigator/Screen 两项与直接相关旧行为两项共
  4 项通过（24 秒）。错误占位伴随 filterError，不执行查询；显式 Retry 获取新筛选器，
  同一引用进入真实搜索服务，保留既有目录过滤/Pager 行为。
- `ec05-android-metadata-projection-red`：两项均到达真实 GetExtensionsByType 的坏
  HttpSource.baseUrl，原始 NoSuchMethodError 逸出。`ec05-android-metadata-projection-green`
  4 个独立方法通过（18 秒）：新投影两项、loader 冷态到同一 HttpSource 实际 client
  消费一项、既有 Android presentation wiring 一项。健康 Source 引用不变；baseUrl
  仅读取一次后收敛，失败移入管理记录并保留 LOAD_FAILED 安装事实；旧同版本实例
  在 getter 内触发替换后，其迟到错误不能污染新实例。内存 presentationSources 只在
  本轮投影 copy 使用，Manager 校验原 Installed 引用，反馈不推进新加载 owner。
- `ec05-desktop-projection-filter-red` 未执行测试：任务名误写为不存在的
  `:app-desktop:test`，1 秒配置失败。纠正为 `:app-desktop:jvmTest` 后，
  `ec05-desktop-projection-filter-correct-red` 精确 5 方法全部正确失败（18 秒）：
  canonical repo 取了另一帧 runtime；坏包同版本仍作为 Available；baseUrl 原始链接
  错误逸出；真实 Navigator 下 SourceBrowse／MigrationSearch 构造各逸出筛选器错误。
  `ec05-desktop-projection-runtime-green`：该 5 方法与新增 Browse／Migration 普通 IO
  显式 Retry 两项、真实 installed ABI 回滚／健康升级两项、无 provider 管理可见一项、
  实际管理页 blank／unknown／reload 一项，共 **11 项通过、零跳过**（Gradle 6 秒）。
  新版候选 Update 实际点击与最后准确格式仍单独收口，不拿投影绿替代 UI 接线。
- `ec05-install-control-runtime-regression`：共享安装控制契约最终三方法在 JVM／Android
  各三项，另 JVM 精确选择既有成功、reload rollback、commit cancellation、同包共享、
  last collector cleanup 五项；Desktop 精确选择新版 Update 页一项、旧转换快照一项、
  跨包迟到 projection 一项及旧 artifact/runtime 回滚一项。**15 项通过、零跳过**
  （17 秒）。旧 fake loader 显式提供加载时 metadata，不要求 production 回读 sidecar。
  后续加强实际 Update 错误文案断言时发现旧通用文案与预期不同，另行区分并补真正
  typed compatibility 反馈门，不能把这次通过当成最后反馈验收。

Desktop loader 的独立装载矩阵还包括以下有效记录：

- `ec05-desktop-load-correct-red`、`ec05-desktop-diagnostic-red` 分别确认真实 service／
  strict／legacy provider 的构造、工厂与元数据失败会被吞掉或发布半包，以及原诊断
  cause walker 会重入有环 cause。首个 `ec05-desktop-load-red` 是测试泛型编译问题，
  不计为产品红。`ec05-desktop-load-green` 与 `ec05-desktop-snapshot-green` 给出对应
  discovery／控制传播／健康 helper 共存及冻结身份的绿证据。
- `ec05-desktop-snapshot-red` 在真实 provider 构造期间替换 sidecar，确认旧失败／
  成功 Source 会被归到新 version 999；`ec05-desktop-snapshot-green` 的 5 方法通过，
  原 162 快照同时用于成功 Source 和诊断，不在 report／presentation 时重读新 sidecar。
- `ec05-desktop-background-red`、`ec05-desktop-unawaited-red` 分别确认旧后台吞控制错／
  假 Ready，以及 async 只对测试 awaiter 抛错而 production 无 await 时不传播的问题。
  `ec05-desktop-background-green`、`ec05-desktop-unawaited-green` 保留 launch 的真实
  本任务异常传播与每次 attempt 的独立 completion；无人 await 时局部 handler 仍收到
  原控制错误，取消不假 Ready。不安装全局吞错 handler。
- `ec05-desktop-no-provider-red` 确认空／仅不可加载 helper 的真实 JAR 缺少包级反馈，
  最小普通诊断在上述 11 项 green 中复验。健康 provider 与可选依赖缺失 helper 共存
  仍可搜索，不把任意 helper 缺依赖都升级为整包兼容失败。
- `ec05-desktop-typed-reload-correct-red` 确认真实 child-loaded provider 在 candidate
  阶段可加载、installed JAR 阶段缺 ABI 后，错误被降为 MalformedData／恢复后残留诊断；
  对应两项在 11 项 green 中通过，旧 bytes／meta／Source runtime 恢复、健康升级清
  失败记录。前一 `ec05-desktop-typed-reload-red` 的 fixture 落入 parent-first 宿主路径，
  没触发目标故障，不计正确红。

详情与最终反馈的后续收口：

- `ec05-desktop-final-consumers-red` 三个真实失败分别是 primary VM fatal 被关闭阶段的
  cancellation 覆盖、typed Update 已进真实安装事务但 UI 未显示本地化兼容文案、源行
  与启用动作重新读取已准备过的坏 getter。`ec05-android-details-metadata-red` 仅一个
  方法重试三次：真实 GetExtensionSources → DetailsScreenModel 的 name getter 已到达，
  NoSuchMethodError 在 XML 中保留，Manager 却无失败反馈，不是三项独立用例。
- `ec05-details-final-consumers-green`：Desktop 6 方法与 Android 3 方法通过（37 秒）；
  包括真实筛选器草稿语义、migration HTTP 分页错误、原 Android source action 和真实
  管理 Banner gate。关闭 helper 比较双方 fatal／未知尾／取消，普通 IO 才可 suppressed；
  普通非 IO 关闭失败仍传播，不新建资源框架。
- `ec05-details-preference-frame-red` 缺 import，非产品红；后一个默认 service DTO 每次
  value 新建帧的输入不作为 production identity 证据。改用真实 MutableStateFlow 的
  稳定 runtime 对象后，`ec05-details-stable-frame-red` 确认禁用偏好反馈会重入 getter。
  `ec05-details-stable-frame-green` 3 方法通过（17 秒）：当前 model 的同 runtime／catalog
  帧复用 prepared projection；catalog 新帧会重新发现错误，runtime 新帧可用新 owner
  恢复，实际详情导航／移除仍工作。不建立全局 cache，key 与投影使用单次捕获的帧。
- `ec05-metadata-owned-final` 是 identity 去重与最后等价排版之后的精确复验：Desktop
  projection／源行 4 方法，Android 实际详情失败／偏好排放 2 方法及旧 fixed source
  action 1 方法，**7 项通过、零跳过（28 秒）**。Android 源行偏好变化保持原 Source 引用，
  name getter 不重入且未用的 baseUrl 零读取；去重比较原 Installed owner 的引用身份。
  三份原始 XML 已在覆盖前保留到 `.test-tmp/ec05-metadata-owned-final-xml/`。

实际新版 Update 页最后通过真实 DesktopExtensionApi.beginInstall → Manager → 安装协调器
到受控下载边界，typed compatibility 失败显示本地化文案，保留原失败包/版本与更新修复
入口，没有虚构 Installed、自动安装或泄露内部 ABI 细节。`ec05-desktop-failed-update-ui-red`
是测试错引不存在 copy 字段的编译问题；`ec05-desktop-failed-update-ui-correct-red` 才确认
Update 按钮缺失。`ec05-desktop-update-feedback-green` 名称虽含 green，实际普通 Network
预期文案不匹配，未当作兼容红；之后 typed compatibility 文案的真实红/绿由上述
final-consumers-red/green 和末次 7 项结果收口。

格式的有效门为 `ec0506-desktop-android-format` 中 domain 精确 5 targets、
`ec0506-owned-format-final` 中 app 精确 14 targets，以及
`ec0506-desktop-format-verified-final` 中 Desktop 精确 18 targets：共 **37 个 Kotlin
文件按原规则通过 Apply／Check**。前序失败均为长签名、长宿主表达式或等价 mixed 条件
括号，未扫描用户 reader 文件。两个 materialize key 只是临时精确 Apply 的排版过程：
先前 lint suppression 没有落下所需自动排版，随后仅临时关闭 max-line-length rule 使
自动修复落盘，再手动拆长行；**不计它们为 Check／通过证据**，最终严格 key 没有加载
该临时 init，项目格式配置没有改变。

每条命令的原样 argv、终态和退出码保存在 `.gradle-coordinator/<key>.json`，原始
日志在同名 `.log`，没有运行无选择器模块／仓库全量。reader／download 的根代理独立
实现与 46 项组合证据另由根代理补入，不能与本段重复累计。Creator、自定义 Rx／调度、
全局搜索汇合及原 APK DEX／R8／厂商设备仍按后续任务验收，本段不据此勾选整个 EC-06。

为补 root reader 历史 XML 被后续 focused task 覆盖的审计缺口，另执行
`ec06-desktop-reader-evidence-final`：只选仍适用的 8 个 reader／network consumer 方法，
不重跑已有下载 6 项。临时 init 仅开启该 test task 的 passed/failed/skipped 日志；
**8/0fail/0skip，4 秒**，原日志逐 case 写出 PASSED，XML 为 6+2，已保留在
`.test-tmp/ec06-desktop-reader-evidence-final-xml/`。这 8 项属于根代理 46 项的补证据，
不能再额外累加。末次只读进程检查无 Gradle Test Executor／GradleWorkerMain；缓存
Gradle/Kotlin daemon 保留，未终止其它会话进程。

### EC-06 阶段：双端阅读／下载实际边界（根代理实现、独立审查）

本批与上一节共同提交；54 个 Kotlin 文件属于同一扩展失败边界的加载、投影及业务消费，
包含两端真实接线和受控外部制品／线程矩阵，超过 8 文件／400 行仍保持内聚。主要风险
是共享错误投影吞控制异常、源归属与旧 runtime 错绑，以及确定性失败被反复排队；
没有增加 Source 代理、数据库／队列协议或全局异常钩子。既有阅读器首开任务文件受保护。

正确失败与最小实现按实际接线分层确认：

- `ec06-android-reader-red`：domain 控制门正确 RED（可控 VM fatal 被降为 Unknown），
  typed compatibility 对照通过。app 后续编译遇到新下载测试的两个 harness 泛型问题，
  不记为 app 行为红。`ec06-android-reader-app-correct-red` 才确认 page list 原始
  NoSuch 逸出；当前 port 尚未标记的 image Linkage 经新控制 helper 后未形成页错误。
- 加实际源 guard 后，`ec06-android-reader-boundary-red` 深入到两个真正的余下失败：
  holder 重绑定使 starts 从 `[0,1,2]` 变成 `[0,1,2,0]`；缓存包装 VM fatal 后仍回退
  源请求。只对不兼容页禁止自动 bind 重试，缓存 catch 先传播控制／宿主 Error；显式
  retry 保留。`ec06-reader-control-green` 的 domain 两方法双目标、reader 两方法及
  下载初始两方法共 8 通过（35 秒），后两项在后续下载最终门中覆盖，不能重复累计。
- `ec06-android-downloader-red`：两真实 private production image stage 使用原 Flow
  和虚拟 delay，确认 NoSuch 未类型化、包装 VM fatal 被重试。普通 IO 四次的对照已
  到达并通过。guard 仅包 getImage；retryWhen 先控制检查，再拒兼容错误，其余策略不改。
- 章节测试的首两次失败分别是 suspend addChapter 错用 verify、mock provider 的默认
  参数读取未构造偏好；均为 harness，已改 coVerify 与真实 provider 的 spy。另 owned job
  的首轮受 WorkManager 未初始化影响，之后只替换该 OS 调度 adapter，
  `ec06-download-job-behavior-red` 才明确 expected fatal、actual null。健康章对照最初
  用 InMemoryPreferenceStore 每次新建 getter，设置 CBZ=false 未保持；改自有 Android
  SharedPreferences 后，未改对应 production 即在 `ec06-download-healthy-baseline` 通过。
- `ec06-download-chapter-behavior-red` 中有效失败是兼容消息显示 raw ABI、章节吞掉
  包装 fatal、provider.toString 原始 Linkage。真实 downloadChapter、cache/rename、
  后台 owned job 与局部 handler 均有断言；页列表、图片 URL、图片各路径失败不提交
  完成，健康并行章节能最终 DOWNLOADED。`ec06-android-download-metadata-red` 另确认
  ComicInfo 的 getChapterUrl 原始 NoSuch；只补该 hook 和 name getter，不包宿主 XML/IO。
- `ec06-desktop-reader-source-red`：三个实际消费者均到达原始 NoSuch，分别是 overridden
  page list、yield 后的反射图片 hook 和网络 getter。守卫放在具体第三方边界，覆盖
  suspend 恢复；`ec06-desktop-reader-source-green` 同时跑既有 headers、自定义请求、
  child-loaded 来源和 HTTP 错误。最终可审计的 8 项 reader 原始证据以上一节的
  `ec06-desktop-reader-evidence-final` 为准，原下载 2 项由后续完整 6 项相关类替代。
- `ec06-desktop-download-source-red` 五个真实 queue cases：pages 逃到所属 handler，
  url 已绿，image 调用 4 次，client/headers 被误判 Storage。只加 queue 自身页列表
  guard、拒绝兼容重试和 marker 分类。首 green 尚有 image 稳定 AppErrorException 被
  二次包装，按原稳定结果分支顺序纠正。`ec06-desktop-download-host-control-red` 在
  真实 writePage 以 IOException 包 VM fatal，确认会落普通 Storage；恢复控制 helper
  后交所属任务处理器，未把整个磁盘阶段标为源代码。
- `ec06-download-boundaries-green`：Desktop 五个源 hook cases 加 host write fatal 共
  **6**，Android 完整下载类 **8**，合计 **14/0fail/0skip，22 秒**。源缺 ABI 只尝试一次、
  没有错误进度／完成目录；健康同队列章节真实发布。源 image URL/response、原 cause、
  phase、无 fallback 请求及不泄 ABI 的本地化消息均有实际断言。

受影响的旧行为与组合计数：

- `ec06-reader-download-regression` 中 domain 精确三方法双目标 **6** 通过；Android
  五个旧方法中 **4** 通过，另加的元数据偏好方法属于上一节，不重复累计。旧 explicit
  retry 门（同方法 retry3）在 encoded write 次数断言失败，不当成三个独立失败。
- `ec06-reader-old-retry-diagnostic` 确认实际错误是 `MockKException: Response.code()`
  未设答案。executor 已执行；读取响应状态码在 HEAD 的旧 production 已存在，因此只
  补两处响应桩 code=200，不改生产逻辑。`ec06-reader-old-retry-regression-green` 只
  补失败旧一项、相邻普通 IO 恢复一项，以及未执行的 Desktop 下载六项，**8 项通过**。
  外层 CLI 曾瞬时显示 NOT_STARTED，未据此重启；同 key 的权威 JSON exit0/PASSED、
  原始 BUILD SUCCESSFUL 和 XML Android2/Desktop6 已重新核对（30 秒）。
- 最终是 **46 个独立 focused 用例的有效组合**：domain 新2×2+旧3×2=10；Android
  reader 新2+旧6、download 新8=16；Desktop reader 新3+旧5、download 新6+旧6=20。
  这不是在最终代码上重跑全部模块。原始 argv/终态/日志按各 key 保留；末次 reader
  8 项 XML 已归档，剩余重复项采用后续有效结果，不将 retry 或重复补证累加。
- 根代理 **17 个 Kotlin 文件**严格格式：`ec06-reader-download-format-final` 中
  domain 精确2；`ec06-reader-download-format-app-desktop-complete` 中 app 精确7、
  Desktop 精确8（4 秒）。失败仅长签名排版；英文方法名缩短一个词、断行及自动格式
  不改行为。与上一节 37 个互不重叠文件共同通过，未扫描用户的 ReaderPage 等文件。

根代理与实施代理交叉独立审查其未实施的稳定片段，关键公共运行机制和平台 wiring
均已在下游前检查；最后 Desktop queue/Android ComicInfo 复审无阻断。维护边界见
7.6.4，实际 UI 契约见 page-contracts EC-UI-01–06。Creator、尚列的自定义 Rx/直接 API
消费、全局搜索汇合/冻结身份诊断和原 APK DEX/R8 均未借此声明完成，继续同一目标。

## EC-07 阶段：全局搜索筛选器准备失败（2026-11-06）

基线 `d9fce82dc9`，仅改双端实际搜索 model/coordinator、其行为测试和共享恢复动作映射。
未纳入工作区已有阅读器优化和另一实施者尚未收口的 Creator 改动。

- 正确红测 `ec07-global-filter-preflight-correct-red`：Android
  `SearchScreenModelBehaviorTest.incompatible filters fail only their source and explicit retry prepares again`
  与 Desktop `DesktopGlobalSearchPolicyExecutionTest.incompatible filter preparation fails only its source and next query recovers`
  均在真实 `getFilterList()` 抛出同一个 `NoSuchMethodError`，并未进入受保护的服务。
  Android 的自动 retry 三次仍算一个独立用例。首 key `ec07-global-filter-preflight-red`
  因 Windows 命令使用无扩展名的 wrapper 未启动 Gradle，不作为产品红测。
- 双端改用既有 `prepareSourceFilters`；失败通过正常 request/reducer 形成单源错误，
  不用空过滤器调用失败源。确定性不兼容恢复动作为 None，健康源仍完成。
  Android 全局显式 retry、Desktop 新查询可重新获取筛选器，不永久拉黑。
- 首次绿测中 Android 测试两个源使用相同 name/lang，落入既有排序比较器同键合并，
  健康源键缺失；将夹具健康源改为唯一名称，不修改本批范围外的排序实现，也不把该次算绿。
- `ec07-global-filter-retry-red` 证明初版普通 IO 重试只调用一次筛选器：真实 child retry
  绕过失败的准备步骤。修复为仅在该 request 的筛选器准备失败时重做准备，且仍在 IO
  dispatcher 执行；原先已准备好的分页请求继续原页重试，不重置成第一页。
- 最终 `ec07-global-filter-final-checked` **PASSED / exit0，20 秒**，原始日志、argv 和
  JSON 位于 `.gradle-coordinator/` 对应 key。Android model **5**、Desktop global policy
  **5**、Desktop child 分页重试 **2**，共 **12 项通过，零失败、零跳过**。XML 已核对并
  归档到 `.test-tmp/ec07-global-filter-final-xml/`。不是模块全量，也未将重试次数累计。
- 同一最终命令以 `.test-tmp/ec07-global-filter-format.init.gradle` 精确限定 Kotlin 文件：
  domain1/app2/Desktop2，Apply 后严格 Check 均通过。之前格式 key 的失败为既有长签名
  排版，不是行为失败；只调整该文件排版，未格式化用户阅读器文件。

独立审查后补验：`ec07-filter-retry-cancellation-red` 证实显式 filter retry 在准备时
取消会遗留 Loading。修复仅恢复仍由本次 retry 持有的原状态（引用身份核对），保留原
Failure、重新准备标记和原取消传播；新请求已替换状态时不恢复旧值。
`ec07-global-filter-reviewed-green` **PASSED / exit0，17 秒**：Desktop policy **6** 与
分页重试 **2** 项通过，零跳过，XML 归档 `.test-tmp/ec07-global-filter-reviewed-xml/`。
五文件严格格式 Check 通过。Android 与共享映射未改，复用上一 key 的 model5 项；最终
证据组合为 **13 个独立 focused 用例**，不声称最终代码重新运行了 Android 或整个模块。
初审和这一取消缺口修复复审均由未实施根代理片段的实施代理承担。

复现 focused 行为命令（经协调器串行执行；类选择器不可省略）：

```powershell
python scripts/gradle-coordinator.py run --key <唯一key> -- .\gradlew.bat :app:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModelBehaviorTest' :app-desktop:jvmTest --tests 'mihon.desktop.ui.browse.DesktopGlobalSearchPolicyExecutionTest' --tests 'mihon.desktop.ui.browse.DesktopSourceQueryBehaviorTest.later page failure stays visible and retry reloads the same page*' --tests 'mihon.desktop.ui.browse.DesktopSourceQueryBehaviorTest.empty first page stays empty while empty append keeps rows and retries the same page*' --offline --no-parallel --max-workers=2 --console=plain
```

本阶段不等于 EC-07 完成：源元数据/安装身份的操作前冻结、并发来源诊断、真正 UI 触发
搜索的双端接线及完整三源取消场景仍须继续；EC-06 Creator/Rx 与 EC-08 R8 也未收口。

## EC-07 阶段：冻结身份的异常传输与 Android 搜索绑定（2026-11-06）

基线 `5a57915882`。本批只改 core 异常/guard、domain 附加 helper、Android 实际搜索
model 及其测试；Desktop/UI/真实日志记录不是本批完成项。

- 正确红测 `ec07-identity-owner-red`（23 秒）：真实 `SearchScreenModel` 在源挂起期间
  将 installedFlow 从同包 1.2.3 换成 9.9.9，然后抛原始 NoSuchMethodError。
  当前错误链仅 generic 兼容性说明与原错误，缺包名/版本，断言正确失败，不是 DI 或编译错。
- 先从同一 installed 快照筛选并按 Source 引用匹配，冻结包名/名称/版本，再调用源。
  不在失败时查 manager；sourceId 来自本次实际 request，不为诊断重读 getter。
  类型化不兼容结果附冻结身份，原始 NoSuch cause 与精确阶段保留，普通错误投影不变。
- core 增加不可变身份和三参 ctor，同时保留真实一参/二参 JVM ctor。新增 guard 保真
  回归在旧的二参重包装行为下执行：`ec07-identity-nested-guard-red` 正确失败，实际
  identity=null、预期 old.package/1.2.3。随后仅将重包装改为传已有身份，不能用当前
  owner 覆写已有快照。反射/IO 原包装链仍完整保留。
- `ec07-identity-owner-guard-green` **PASSED / exit0，33 秒**：core guard 契约 JVM8、
  Android8，以及真实 Android model6，共 **22 项，零失败/零跳过**。新旧 ctor、
  单行/长度边界、unknown 明示、旧 owner/phase、取消/VMError 和旧搜索代际均有断言。
  原始 argv、终态和日志位于 `.gradle-coordinator/` 同 key；三份 XML 核对后按平台前缀
  归档 `.test-tmp/ec07-identity-owner-guard-green-xml/`，避免同类名覆盖证据。
- `ec07-identity-format` **PASSED / exit0，4 秒**：通过临时 init 精确限定 core2、domain1、
  app2，Apply 后严格 Check。格式仅排序/排版，不改变行为；既有用户阅读器文件未触及。

独立审查发现两个本批归属边界，均在提交前关闭：

- `ec07-identity-review-red` 中 core 正确红：旧二参 ctor 的外层 phase 非空、identity 为
  null，内层已有身份；guard 直接返回外层导致返回 marker.identity 仍 null。修复为仅在
  已有 phase 且已有身份与检查所得一致时复用，否则携原 phase/完整 cause 提升身份。
  该 key 的 Android 编译被另一任务正在编写的 Creator 测试夹具构造参数挡住，未执行，
  不作为 Android 行为红测；修正该夹具后另跑下项，未重复启动已活进程。
- `ec07-identity-equal-source-red` 正确红：两个真实 Source 的 equals/hash 相同但 id、
  名称和安装 owner 不同，B 先失败、A 后失败；A 的原始未来 API101 错误却带 second.package。
  新 identity 映射由 Map 改为 List<Pair>，取值只用引用 ===，不执行插件 equals/hash。
  此回归限定新身份链的原错误归属，不冒充既有 Source-key UI 容器支持任意自定义相等性。
- `ec07-identity-reviewed-final` **PASSED / exit0，14 秒**：core JVM8/Android8、Android
  实际搜索 model7，共 **23 项，零失败/零跳过**；五文件严格格式同时通过。三份 XML
  核对后另存 `.test-tmp/ec07-identity-reviewed-final-xml/`，按平台前缀避免覆盖。
  未实施这些片段的代理初审及上述修复复审均通过，GO 仅限本阶段，不是整个 EC-07。

复现行为命令：

```powershell
python scripts/gradle-coordinator.py run --key <唯一key> -- .\gradlew.bat :core:common:jvmTest --tests 'eu.kanade.tachiyomi.network.SourceOperationGuardContractTest' :core:common:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.network.SourceOperationGuardContractTest' :app:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModelBehaviorTest' --offline --no-parallel --max-workers=2 --console=plain
```

维护说明见 `desktop-network-routing.md` 7.6.5。这里只证明身份可随现有错误链传输和
Android 搜索绑定；不能把 DTO/异常 message 存在当成实际诊断记录或 UI 展示证据。
Desktop 的操作前捕获、元数据安全描述、并发源不串身份、真实日志记录失败保护、双端
UI gate、完整取消场景与 R8 原生仍是原 roadmap 的后续必做项。

## EC-07 阶段：Desktop 操作前归属与真实页面版本反馈（2026-11-06）

基线 `64d4cac450`。本批复用现有 extensionPresentationService、coordinator、错误卡和
SourceRecoveryAction，不新建页面/DI 类型/存储协议。范围超过 8 文件/400 行的内聚原因：
页面新增读取既有已安装端口，五个既有 UI 夹具需要明确提供空快照；两个主文件的必要
定向格式另有排版变更，未调整其他业务逻辑。最终 10 个 Kotlin 文件（domain1/Desktop9）。

- 首 `ec07-desktop-owner-ui-red` 停在 MockK 夹具构建异常，不计产品红，不据此推断业务
  根因。未改 production 重跑同方法的 `ec07-desktop-owner-ui-harness` 已执行实际输入与
  搜索按钮：健康结果出现，坏源挂起期间安装记录从 1.2.3 换到 9.9.9；失败链只有通用
  SourceCompat/NoSuch，缺包名/版本，正确红。XML 保存于
  `.test-tmp/ec07-desktop-owner-ui-red-xml/`，不混同前一次夹具错误。
- `ec07-desktop-replaced-source-red`：相同 id 的新 Source 对象遇到相同查询被旧 session
  复用，实际新源调用0次、预期1次，正确红。新查询身份按 Source 引用及冻结 owner DTO
  比较，不调用第三方 equals/hash。版本/实例变化会重新搜索该源，健康交集保留。
- 实际页面在 executeSearch 前读取 installed 快照；仅通过 Source=== 与 metadataSnapshot
  绑定可信名称/包/版本。新增 overload 保留旧调用接口；child 只给类型化兼容失败附冻结
  身份。未知明确为空，不从 routing JAR 名、primary fabricated-version 或相同 sourceId
  的其他对象借版本。domain 提取 helper 仅从既有有界 marker 链取身份，其他错误保持原态。
- 原生页面用例通过：**320px** 宽真实输入/点击提交 → 健康源显示结果 → 坏源只显示本地化
  不兼容与 Trusted extension/1.2.3，错误链保留 trusted.package，不含查询或 routing 名。
  安装记录更新后显式再点同关键词搜索，显示9.9.9，健康源实际搜索仍仅1次。
- 首绿 `ec07-desktop-owner-ui-green` 新用例与 policy7 通过；旧 canonical 错误反馈用例
  只等 repository.newFailed flag、render 一帧即断言失败。已读真实 fixture：该 flag 在
  throw 之前完成，早于 materializer/Compose 状态发布；改为有界等待实际错误文案及实际
  canonical UI，仍通过真实 wiring，错误不出现或旧结果回写仍会失败，不放宽预期。
- 最终 `ec07-desktop-owner-focused-final` **PASSED / exit0，31 秒**。亲核七份 XML：
  production3、policy8、authority1、history8、navigation3、retention4、sourceFilter3，
  共 **30 个独立 focused 用例，零失败/零跳过**。按实际类归档
  `.test-tmp/ec07-desktop-owner-focused-final-xml/`，不累计此前失败/重复运行。
- 同一最终命令严格检查精确 10 文件格式；早前 format key 仅长行排版失败。临时
  `ec07-desktop-owner-format-materialize` 只用于 Apply 展开格式，不作为 Check/验收；
  最终使用原规则严格 Check，无生产 suppress、无放宽 ktlint 规则。

复现：经协调器运行 `:app-desktop:jvmTest`，精确 `--tests` 以下七类：
`mihon.desktop.ui.browse.GlobalSearchResultProductionWiringTest`、
`DesktopGlobalSearchPolicyExecutionTest`、`GlobalSearchAuthorityWiringTest`、
`GlobalSearchRecentHistoryWiringTest`、`GlobalSearchResultNavigationTest`、
`GlobalSearchSessionRetentionTest`、`GlobalSearchSourceFilterWiringTest`（后六类同包前缀），
加 `--offline --no-parallel --max-workers=2 --console=plain`；不是模块全量。

维护见 7.6.5；EC-UI-07/08 只增加这条真实页面的部分证据，仍不整项通过。源 getter
安全描述、Android 页面、实际诊断 sink、完整三源取消与 R8 仍待完成，保留原 roadmap。

未实施本片段的代理独立审查三处 production 接线后通过：操作前单帧 metadataSnapshot、
Source 引用/owner 双条件复用、child 冻结身份和实际错误区域均符合本阶段边界；未将剩余
getter/日志/完整 UI gate 当作已通过。


## 2026-11-06 EC06 Creator / 自定义 Rx 阶段（focused，非全项完成）

本阶段仅修改 Creator adapter/service/interactor、两端既有错误反馈及对应测试。
不改 schema、公开 Source ABI、全局 Rx hooks，也不操作设备或发布包。

### 正确 RED 与夹具修正

- `ec06-creator-adapter-correct-red`：真实 adapter 作者 hook 的 raw NoSuchMethodError
  逸出、包装取消被重建、过滤器缺 ABI 逸出；`ec06-creator-hook-matrix-red` 单方法
  收集全部实际调用点，观察 filters/details raw NoSuch 逸出，search 被降为 Network。
- `ec06-creator-ui-schedule-red`：真实 SQLDelight repository + service 已有健康 work
  提交后，不兼容 checkpoint 仍是普通 backoff（1801000，而非 MAX）；两个真实
  DiscoverCreatorWorks overload 没有向既有 screen 消费端传播兼容错误；Desktop
  scheduler 仍反馈普通失败计数。上述分别为真实产品 RED。
- `ec06-creator-android-feedback-red`：实际 Navigator → 作者详情检查按钮 → model →
  interactor → service → Source 调用一次后没有本地化兼容反馈，正确 RED。test-retry
  的同一方法三次失败不计为三个独立用例。
- 早期 commonTest 的 kotlin.test import、MutableClock 私有 setter、非 Unit 测试
  返回值导致未发现，以及 Android BasePreferences/仓库类型/MockK SourceManager
  Flow 与列表方法冲突均为 harness 错误，不计正确 RED。健康 work 初为 0 是测试 watch
  排除了未知阅读语言，修正 fixture 的 includeUnknown 后才验证 checkpoint 产品红。
  withTimeout 的取消堆栈恢复可能复制 CE；测试保留本体或直接 cause 为原 CE 的分层断言，
  没为身份断言更改生产取消语义。

### 最终 GREEN 与可审计原件

`ec06-creator-contracts-green` 权威 JSON 为 PASSED/exit0，49 秒；逐 case 日志和
九份 XML 归档于 `.test-tmp/ec06-creator-contracts-green-xml/`。共 **21 个独立用例，
0 失败、0 跳过**：domain JVM 9、domain Android unit 6、data JVM 3、Desktop 2、
Android 实际 Compose 入口 1。不是模块全量；完整实际参数在同 key 的协调器 JSON。

精确选择器（每个 task 后分别绑定 --tests；统一 offline/no-parallel/max-workers=2）：

- `:domain:jvmTest`：CatalogueCreatorCompatibilityContractTest（3）、
  SourceRxCompatibilityBoundaryContractTest（3）、CreatorDiscoveryPlanningTest 的
  `catalogue adapter maps source failures*` 与 `structured author search failure falls back*`
  （2）、CreatorDiscoverySourceModeTest（1）。前三类包为
  `tachiyomi.domain.creator.service`，Rx 类包为 `tachiyomi.domain.source.service`。
- `:domain:testDebugUnitTest`：上述两个新增 contract 精确类（3+3）。
- `:data:jvmTest`：`tachiyomi.data.creator.CreatorDiscoveryExecutorStateMachineTest` 的
  `incompatible source waits for manual force*`、`discovery interactor keeps compatibility*`、
  `manual force bypasses backoff*`（3）。
- `:app-desktop:jvmTest`：`mihon.desktop.domain.CreatorDiscoverySchedulerTest` 的
  `compatibility failure keeps typed*` 与 `partial source failure ends*`（2）。
- `:app:testReleaseUnitTest`：
  `eu.kanade.tachiyomi.ui.browse.author.AndroidCreatorCompatibilityUiTest`（1）。

真实 service 门证明坏源只调用一次、健康源提交作品；推进一天仍不自动执行坏源、健康
源继续执行；manual force 修好后清兼容 checkpoint 并恢复正常 due。MAX 的 Instant
往返及相对当前时间的 Duration 计算无溢出；现有 SQL 仅比较 next_due_at，失败 checkpoint
不进入成功 calendar 的日期重算。未新增字段，也不宣称能自动识别扩展版本变化。
Android 实际点击测试验证现有错误区的中文兼容文案、一次源调用、不泄原 ABI 文本；
Desktop scheduler 验证 typed task cause 与既有本地化 failureMessage。

Rx 三方法在两个共享目标均执行真实 HttpSource override legacy fetchSearchManga →
Source 默认 suspend ABI → awaitSingle → SourceMangaSearchService：同步 custom map
raw Linkage 类型化且 onError=0；独立 executor 的 IO 包装 Linkage 经 onError=1 同样
类型化且 worker 无逸出；独立 executor raw Linkage 被 Rx 包装为 Fatal Scheduler.Worker
错误，仅到本测试专有线程 handler（有界 awaitTermination/join 后检查），宿主 await
仍活动且无结果。caller 取消确实 unsubscribe，随后健康源成功。没有全局 handler；
仅最后一种未交回宿主的独立线程链属于既定限制，不排除全部 custom/async。

### 格式、维护与未完边界

精确 11 Kotlin targets：domain 6 在 `ec06-creator-format-final` 的严格 Check 已过；
data 1/Desktop 2/app 2 在 `ec06-creator-format-strict` PASSED（4 秒）。前面几个格式
key 因 data 测试在 formatter 增加函数体缩进后超过 max120 失败，最终仅拆纯宿主
局部值；没有放宽规则、suppress 或触及 reader 用户改动。后续只有 import/排版及等价
宿主局部值，无产品行为更改，复用上面 21 项有效证据；git diff --check 通过。
维护规则见 desktop-network-routing 7.6.6。

有界只读盘点仍欠、未在本阶段擅改：Android DeepLinkScreenModel 的 getChapterList
及同链 source hooks；Desktop MangaDetailScreenModel.searchMigration 的 getSearchManga、
MangaDetailScreenModelFactory 与 DesktopAppModule migration queue 的 getChapterList；
Android SyncChaptersWithSource 在 ChapterDirectoryPlan.prepare 回调内的
source.prepareNewChapter 及相邻实际元数据读取。不能把整个数据库/目录计划包进 guard。
Creator enabledSourcesSnapshot 的 id/name/lang 仍为原有元数据入口，未宣称已冻结。
全局搜索完整安全描述/诊断、真实原 APK/R8 验收仍按 roadmap 待完成；本阶段不勾选整项 EC06。


独审补充的生产 DI gate：Android UI fixture 不再手工绑定 Application，改为真实
`Injekt.importModule(AppModule(activity.application))`；非被测 warmup 的
NetworkHelper/Database/DownloadManager 仍由局部 fixture 替代，SourceManager 为原真实
小实现，Application 只由生产模块注册。第一 key `ec06-creator-ui-production-di-green`
因模块异步 warmup 缺 NetworkPreferences 失败，是 harness 依赖缺口，不是产品 RED。
补齐非被测 warmup 边界后，`ec06-creator-ui-production-di-final` PASSED/exit0（15 秒），
实际 UI **1 个独立方法，0 失败、0 跳过**，精确该文件严格 Spotless Check 同命令通过。
原 XML 留存 `.test-tmp/ec06-creator-ui-production-di-final-xml/`。此门替代前述 UI 原件，
与仍有效的其余 20 项组成最终 21 项证据，不累计为 22 个独立方法；未重跑其余已绿范围。

主代理独立审查确认：Source hook 保护只围绕实际第三方调用；typed 原因传过两个
DiscoverCreatorWorks overload 和双端既有反馈，未加入登录/自动重试。实际 SQL 失败源
deadline 与成功 calendar 分离，manual 成功恢复普通 due；MAX 未作普通展示日期。
9 份旧有效 XML 共21项已逐一读取，最终 DI UI 原件替代旧 UI 后独立核对通过。新增
Application 解析来自 AppModule.registerInjectables 的生产注册，不再以测试手工注册证明。
本内聚批次包含11 Kotlin、两份维护证据及 roadmap 阶段说明，超8文件的原因是共享
分类/实际DB调度/双端反馈接线必须一起验证；不改持久化协议或 schema，不勾选整个EC06。


## 2026-11-06 EC06 裸调用补充：DeepLink / 迁移 / 章节准备

本簇由 ab443af354 后续工作区继续，仅闭合已盘点的实际 Source 调用及其必经错误投影。
没有改 Source ABI、数据库 schema、Source 对象类型、正常导航或新建错误反馈协议。
不操作设备、不正式构建、不做模块全量。主代理的全局搜索及用户 reader 改动不在本簇。

### 真实 RED

- `ec06-direct-hooks-red`：Android 实际 SQL 目录入口 prepare/id/toString 三点均为
  raw NoSuchMethodError，预期三点专用类型化传输；真实 firstResolvableSource 的 URI
  hook 同样 raw 逸出；共享 BatchMigrationOrchestrator 将受控 VM fatal 降为普通 Failed，
  原错误断言 actual=null。Android test-retry 三次为同一方法，不累计独立用例。
- 同 key Desktop 编译失败仅夹具漏 supportsLatest/getFilterList、误用不存在的
  stopAndJoin，是 harness 错误，不计产品红。仅修夹具后
  `ec06-migration-hooks-correct-red` 四个 Desktop 方法均正确 RED：实际 model 搜索与
  factory 章节入口 raw NoSuch；真实 DI batch 队列错误显示 private batch failure，未显示
  兼容文案；owned worker 的本测试局部 handler 未收到 wrapped fatal（被下游吞掉）。
  该 key 的 Android UI 当时只证明首 URI 缺反馈，不据此宣称其它 hook 已验证。
- `ec06-direct-ui-matrix-red`（46 秒）：仅为取一次明确 RED 原件临时关闭 app 自动
  test-retry，未更改项目规则/最终绿配置。真实 Navigator→DeepLinkScreen 的实际 entered
  矩阵为 `[uri:false:uri,manga:false:manga,id:false:id,chapter:false:chapter,chapters:false:chapters]`，
  五种 hook 确实执行而兼容文案都缺失。Desktop 真实 MangaDetailScreen 开启保留的旧
  picker 状态后点击源，model guard 已生效但旧 runCatching 吞掉 marker，既有 Snackbar
  文案等待超时。不是孤立错误组件或源码扫描。

### 最小实现及 GREEN

`ec06-direct-hooks-green` 权威 JSON 为 PASSED/exit0；实际逐 case 日志与九份 XML
归档 `.test-tmp/ec06-direct-hooks-green-xml/`。**26 个目标用例，0 失败、0 跳过**：
共享 domain JVM 4 + Android unit 4，Android app 8，Desktop 10。相同方法的两个目标
分别列证据，不把 RED 的重复 retry 累计进去。

精确复现使用该 key JSON 中的参数（各 task 后分别绑定 --tests；统一 offline、
no-parallel、max-workers=2）：

- `:domain:jvmTest`、`:domain:testDebugUnitTest` 各选择
  `mihon.domain.migration.MigrationOrchestratorTest` 的 `batch source boundary*`、
  `batch cancellation propagates*`、`batch continues after item failure*`、`batch waits for user*`。
  覆盖 wrapped VM/CE/host Error、未知深尾原传播，普通 IO/marked compatibility 失败后
  健康项继续，以及原取消/等待 checkpoint 行为。
- `:app:testReleaseUnitTest` 选择
  `eu.kanade.tachiyomi.data.backup.ChapterDirectorySourceContractTest` 的
  `actual directory source hooks*`、`directory source control*`、
  `actual Android wrapper deduplicates*`、`actual Android wrapper preserves original rename*`；
  `eu.kanade.tachiyomi.ui.deeplink.AndroidExternalActionSharedWiringTest` 的
  `resolvable source URI hook*`、`deep link chapter resolution*`、`source URL search uses*`；
  精确类 `eu.kanade.tachiyomi.ui.deeplink.AndroidDeepLinkCompatibilityUiTest`。
  实际 SQL 验证坏源零新增章/零 pending phase、健康目录成功；host database IOException
  即使带宿主 NoSuch 仍原对象传播。真实 UI 五 hook 都显示兼容文案且未进入章节同步；
  正常深链接保留 NoResults fallback 和成功章节的既有 sync/result wiring。
- `:app-desktop:jvmTest` 选择
  `mihon.desktop.ui.library.MangaDetailScreenModelTest` 的 `legacy migration search*`、
  `migrateTo persists*`；`mihon.desktop.ui.migration.MigrationSearchInteractionTest` 的
  `legacy detail factory chapter hook*`、`legacy detail factory callback migrates*`、
  `actual batch migration chapter ABI*`；
  `mihon.desktop.migration.DesktopBatchMigrationControllerTest` 的
  `migration owned worker preserves*`、`queue persists waiting selection*`、
  `pause cancel item cancel all*`、`resume waits for cancelled original*`；
  `mihon.desktop.ui.library.MangaDetailInteractionTest.retained legacy migration picker*`。
  真实 DI factory/batch 失败不产生迁移目标/receipt、不改变原书；旧健康迁移仍保留独立
  目标和章节属性。旧 picker 是保留分支的真实点击/Snackbar 门，不冒称当前 More 默认
  导航（More 仍进入普通 MigrationSearchScreen）。局部 owned scope handler 证实控制
  失败不会被普通 checkpoint 错误吞掉；没有全局 uncaught 吞错。

### 格式及边界

`ec06-direct-hooks-format` PASSED（8 秒），严格原规则，准确 domain 2/app 6/Desktop 9，
共 **17 Kotlin 文件**；无放宽规则或 suppress，未触碰保护的 reader 文件。格式化包含
当前目标文件的既有 import/排版整理，没有额外生产行为变更；git diff --check 通过。
维护见 desktop-network-routing 7.6.7。withSearch 健康 fixture 已恢复原 MangaDexSource
类型，仅故障参数非 null 时使用受控 delegate；故障场景有效证据不变，健康 factory
最终夹具补验另记下文，不重跑其余已有效范围。

本阶段不勾选整个 EC06。Creator enabledSourcesSnapshot 仍缺安全登记键/元数据结果接口；
保留 legacy picker 自身源行 id/name/lang 仍是后续安全描述欠项。Android 的登记键权威
是 sourcesMapFlow 的 map key（按实例引用查找）；Desktop 当前 getSource/去重仍读取
source.id，不能把临时新读值称为冻结登记键。已向 root 提供这份接口盘点，未擅自造 ID
或新增协议。全局诊断和原 APK/R8 原生门仍按 roadmap 待完成。


最终健康 fixture 类型补验：`ec06-migration-healthy-final` PASSED/exit0（6 秒），仅运行
`MigrationSearchInteractionTest.legacy detail factory callback migrates*` 一个旧方法，
0 失败、0 跳过；原 XML 另存 `.test-tmp/ec06-migration-healthy-final-xml/`。最终健康
fixture 保持真实 MangaDexSource，故障 fixture 才代理章节 hook。这一方法替代前一
26 项组合中的同名健康 factory 原件，最终仍为 **26 项**，不累计成 27，也不重复其余
25 项已有效证据。最终源码无后续行为变化，当前本簇 Gradle 命令均已终态。


### 提交前独审修正：每章可变 SManga 输入隔离

独审发现把 manga.toSManga() 提到 ChapterDirectoryPlan 外会让多章共享可变对象，改变
原来每章新建输入的语义。新增真实 SQL 两章测试：prepare 记录收到的标题和对象引用，
随后修改 SManga.title；第二章仍必须看到原标题，且引用不同。首 key
`ec06-prepare-manga-isolation-red` 因测试文件写入断言未命中、未落盘而 No tests found，
属于输入准备失败，不计产品红。`ec06-prepare-manga-isolation-correct-red` 取得正确 RED：
已真实新增两章，标题预期 `[Original, Original]`，实际
`[Original, Mutated by previous chapter hook]`。retry 三次仍是同一个方法。

修正只将 toSManga 放回每章 callback 的 if HttpSource 内、guard 外；仍不把宿主转换
当作第三方调用，也不改变 source hook 的守卫。`ec06-prepare-manga-isolation-green`
PASSED/exit0（27 秒），仅四个精确方法：`chapter preparation receives fresh*`、
`actual directory source hooks*`、`directory source control*`、
`actual Android wrapper deduplicates*`（同 ChapterDirectorySourceContractTest 类，
:app:testReleaseUnitTest 每条 --tests 均紧随该任务）。四项 0 失败、0 跳过；同命令准确
上述 production/test 两文件 strict Spotless Check 通过。原 XML 归档
`.test-tmp/ec06-prepare-manga-isolation-green-xml/`。其余有效范围未重跑：这四项替代
原组合中三项目录原件并增加一个隔离门，最终本簇有效组合为 **27 项**，不是将复跑
四项额外累计。维护7.6.7已明确每章输入隔离；git diff --check 通过，命令均已终态。

主代理独审及修复复审通过：精确 source hooks、宿主事务边界、既有取消分支与下游
host/fatal/未知尾传播均已检查。每章可变对象回归关闭；归档原件按平台+类+方法去重
独立核对，有效组合27项，最终健康factory原件替代同名旧项，未把重复补验累计。
本批17 Kotlin+两份维护证据及roadmap阶段说明是这些真实调用与下游反馈的内聚接线，
超过8文件不据此扩大测试；不包含root全局UI及用户reader改动，也不勾选整个EC06。

## EC-07 阶段：Android 真实页面与双端三源取消（2026-11-06）

基线 `ab443af354`，本批只含root全局UI/model及真实页面测试；直接调用簇已独立提交，
其并发改动不计入本批证据。生产改动是既有错误组件的可选身份说明及必要状态/订阅修复，
不新增页面/导航/DI类型或持久化协议。

- 首 key `ec07-android-global-ui-red` 为多余测试 import 编译错误，非产品红。
  `ec07-android-global-ui-correct-red` 执行真实 Navigator、输入与IME提交，健康作品和
  本地化兼容提示已出现，但缺 Trusted extension (1.2.3)，正确红；3次自动retry仍算1方法。
  XML 归档 `.test-tmp/ec07-android-global-ui-correct-red-xml/`。
- 最小UI实现仅消费操作前冻结身份，原错误正文/恢复动作不变。加入三源与新查询断言后，
  `ec07-global-ui-three-source-green` 的新Android方法失败、旧model/recovery通过。
  Desktop在final Manager getter装桩时报MockK错误，改为mock实际消费的既有presentation
  service接口，不改production；其后真实Desktop三源用例通过。
- `ec07-global-ui-diagnostic` 单次诊断关闭自动重试，真实计数broken2/healthy2且旧挂起源
  已取消，但语义树仍显示Healthy first、broken/pending Loading。已读production：
  updateItem在State外计算整图，线程池完成结果会互相覆盖。修复同一update内读改写，
  加State.generation屏障，CAS重试重算items并拒绝已失效结果，保留当前其他字段。
- `ec07-android-ui-atomic-red` 分步验证：broken/pending已正确结束、9.9.9提示出现，
  仍显示Healthy first。真正原因是getManga的produceState没有来源/URL身份；快速新查询
  没有经过Loading帧时会复用旧初值和订阅。以key(source,url)重建独立状态组，不能靠
  等待UI多一帧或只改测试文案掩盖。该次XML归档 `.test-tmp/ec07-android-ui-atomic-red-xml/`。
- 最终 `ec07-global-ui-three-source-final` **PASSED/exit0，39秒**，恢复默认测试retry和
  原严格格式规则。亲核四XML：Android真实UI1、model7、recovery2、Desktop真实页面3，
  **共13项，零失败/零跳过**；归档 `.test-tmp/ec07-global-ui-three-source-final-xml/`。
  精确app4/Desktop1共5文件Apply后strict Check通过；未扩模块全量。
- Android320px真实输入/IME：坏源提示和旧版本、健康作品出现，挂起源没有被兼容失败
  取消；第二查询取消旧挂起源、显示新健康作品/新owner，旧结果/版本消失、进度归零；
  第三查询无已安装归属时显示Unknown/Unknown。每次每源1调用，无自动重试。Desktop
  同一现有提交用例已加第三挂起源，显式版本重查取消旧源且健康实际search仍仅1次。

未实施本片段的代理已独审atomic generation更新、Compose key和details接线通过。
维护见7.6.8。此阶段补齐真实三源UI证据，仍不勾选EC07：完整元数据安全描述、实际诊断
写入及其失败保护、原APK/R8验收均按原计划继续。

## EC-07 宿主登记键与实际诊断阶段（2026-11-06）

本阶段为两个独立写入边界：实施代理负责登记键，主代理负责日志接线；同一 worktree 的
Gradle 窗口串行交接，主代理统一提交。共 28 个 Kotlin 文件（含 6 个旧 fake 的明确登记值
适配），另更新本证据、roadmap 与维护说明。范围跨 loader/manager/真实页面，是因身份
必须贯穿实际接线，不是另建插件协议、容器或日志平台；未碰已有 reader 首开任务文件。

### 宿主登记键：正确红测、反证与最小实现

- `ec07-registered-source-red`、`ec07-registered-android-stub-red` 确认未实现接口返回
  null、Android stub 排除和 Desktop 查询/重载又读取失效 ID getter。新增
  `SourceManager.getRegisteredSourceId` 只按现有宿主登记和 Source 引用查询；未知返回
  null。新 `SourceRegistration` 不用插件 equals/hash。
- Android 真实已安装替换链给出反证：equal-source 替换实际上已能发布，不能据 Map.equals
  推测另建 identity-map 包装。现有每帧 LocalSource 引用变化已使该实际帧不同；保留真实
  替换测试，仅复用原 map 的键，未改整体发布协议。
- Desktop loader 保留首次守卫读取的 ID，Installed/Loaded runtime 携带登记；查询、版本、
  重载和去重消费该键。builtin 构造登记，两个实际测试桥以宿主常量提供 typed 快照，
  原 raw callback 的 get/query 登记/短路语义保留；只读识别不刷新 raw callback。
- `ec07-registered-raw-edge-red` 确认失败刷新会残留已移除旧源，且 raw alias 仍探测
  已登记 builtin 的坏 getter。最小修正为先剔除失效记录、复用 observed/previous/known
  登记，保留首命中短路，不加失败时猜测 ID 的回退。
- 独审发现登记锁内新增 runtime lookup 与 manager 发布存在反向锁依赖。
  `ec07-registered-lock-order-red` 用真实 manager、owned 线程和有界 latch，证明持有
  runtimeLock 时已发布候选查询不能完成；finally 释放后 join 退出。去掉该嵌套 lookup，
  改用锁外取得的已发布登记快照，同一门通过。未创建永久死锁或全局清理进程。
- 第一轮 Android 装置缺 SecurityPreferences/NotificationManager 不是产品红测；补齐原
  通知 fixture 后的正确红才作为依据。`ec07-registered-lock-order-green` 初次入口缺
  Spotless 插件属于过程脚本失败，修正脚本后以 final 为终态，不算产品回归。

登记最终有效组合 **29 项，零失败/跳过**：domain 双目标各 1、Android 3、Desktop 24。
`ec07-registered-source-green` 保留未受后改影响的 DTO 双目标、Android 新门和 typed
快照门 4 项；`ec07-registered-source-regression` 保留未被末验替代的 20 项；
`ec07-registered-lock-order-final` 的最新 raw 三门、真实 child-loader 和锁序门共 5 项。
前两批 XML 已归档于同名 `.test-tmp/*-xml/`。最新 5 项 XML 在交窗后被下一次 JVM test
清理，不能引用空归档；其权威 JSON 为 PASSED/exit0，原 log 明确列出 5 个 PASSED，
15 秒终态有效，未为补归档重跑。根代理已核接口、锁顺序、实际测试和该日志。
准确 18 文件严格格式通过，锁修正后对应 2 文件重验通过；未重跑全部 23 项。

### 实际诊断：先失败，再接线与独审修正

- `ec07-search-log-ui-red`：真实 Android Release 页面兼容提示已出现，但 ERROR tag
  记录期望 1 实际 0；Desktop 实际页面 stderr 无归因 tag。Android 同一方法的 3 次
  retry 只算 1 个独立方法。core 新 UTF-8 门另确认 160 单元截断会拆开 emoji，出现
  `MalformedInputException`。XML 归档 `.test-tmp/ec07-search-log-ui-red-xml/`。
- 新共享 reporter 先仅加空 API，再以 `ec07-search-log-domain-red` 取得 4 方法中
  3 个真实行为红：无记录、未知/容量记录缺失、writer 调用次数为 0；普通错误零日志门
  保持绿。空 API 不作为实现提交。`ec07-search-log-model-correct-red` 以实际 model
  证明 writer 未调用，其余 7 方法通过。两个正确红 XML 已按同名目录归档。
- `ec07-search-log-model-red` 的前导通配符方法选择器造成无输出 worker 换代，已通过
  协调器停止本任务进程，标 CANCELLED，不算产品红；改用精确类后的 correct-red 有效。
- 最小实现：共享 formatter 只记录冻结身份、固定 phase/kind，以及既有 safeCrashReport
  处理后的原始链接错误栈。单字段最多 160 UTF-16 单元，不拆有效代理对；栈最多 2048
  ASCII 字符，含 tag 的 UTF-8 单条小于 4096 字节。不输出 Throwable.message、查询词、
  URL、凭据或源码文件名。普通 writer Exception 不遮蔽原错误，取消/致命/未知深尾和
  未标记宿主 Error 仍传播。取消和普通网络失败不生成兼容日志。
- Android 默认直接 Log.e：App.kt 的通用 logger 只在 Debug 初始化，不能作为 Release
  真实诊断接线。Desktop 默认使用现有 stderr；可注入 writer 测真实 IO 失败，不引入
  独立日志文件、反馈 codec/schema 或新的 DI 服务。旧 child 1/2 参数构造器保留。
- 未实施日志部分的代理独审发现 Android 先写后更新的代际竞态，以及普通 child 丢失
  已知 request ID。`ec07-search-log-acceptance-red` 用真实 model 证明 writer 看到的是
  Loading 而非已接受 Error；实际 child 记录 source=unknown 而非已知 9。其余旧行为
  和迟到请求零日志门保持绿。原 XML 归档同名目录。
- Android 最小修正为 updateAndGet 返回发布快照，同时检查 generation 和本次新建
  result 的引用身份；只有接受后在 CAS 外写入，不用跨重试残留 flag。Desktop 只在
  commit 接受后、锁外写入；始终以可信 request ID 补最小身份，包名/版本未知仍未知，
  已有冻结身份不改绑。两处补丁已独立复审代码 GO。

最终 `ec07-search-log-focused-green` **PASSED/exit0，52 秒**。根代理核对并归档 9 份
XML 于 `.test-tmp/ec07-search-log-focused-green-xml/`，**35 项，零失败/零跳过**：
domain reporter 两目标各 4、core guard/UTF-8 9、Android 真实 UI 1/model 8/recovery 2、
Desktop 真实 UI 3/实际诊断 2/分页和 owner 取消各 1。准确 domain2/core2/app3/Desktop3
共 10 文件定向严格格式通过。真实 Android 页面明确断言 BuildConfig.DEBUG=false、
三个查询分别记录旧版本、新版本和 unknown 归属，每次仅一条；Desktop 实际入口使用
默认 stderr，更新后重查共两条且保留健康结果。额外 writer IO 失败不掩盖本源错误、
健康源仍完成；迟到失效 child 不输出记录。没有运行完整模块或全量验证。
未实施日志部分的代理已独立核对最终 JSON、九份归档 XML、准确格式目标和两处修正，
本日志阶段最终 GO；不将该审查扩大到尚欠的安全元数据描述或 R8 原生门。

本段为有效组合和最终 focused 的分别说明，不声称所有登记门已在最终日志代码上重跑。
共享网络、反馈协议未改；安全崩溃文件/结构化反馈预览不会因此自动携带 logcat/stderr。
维护见 desktop-network-routing 7.6.9–10。EC-06/07 仍未整项完成：Creator/legacy picker
以及全局搜索元数据安全描述继续，EC-08 原 APK/ART/R8 原生验收仍为必做；不发布候选、
不操作用户平板、不据 JVM 结果宣布 Android17 厂商设备已验证。

## EC-06/07 安全元数据消费阶段（2026-11-06）

本批实施代理负责 Creator/旧 Desktop 迁移，主代理负责共享 snapshot 与 Android 搜索。
单一 Gradle 窗口串行交接，主代理统一提交。24 个 Kotlin 文件贯穿领域、实际 DI/service、
两个既有 UI 入口及行为测试；不是新增诊断系统或 Creator 能力。另同步内部维护说明、
本证据、roadmap 和既有页面契约，未修改受保护 reader 首开任务。

### 共享机制与 Android 实际搜索

- `ec07-source-metadata-snapshot-red`：空 API 脚手架得到 4 个真实失败（名称/部分语言
  未读取、控制未传播、兼容错误缺失），19 秒。实现后
  `ec07-source-metadata-contract-and-model-red` 的共享双目标各 4 方法绿；整体 key 仍
  因 Android 4 方法红而 FAILED，不能称整体绿。对应 XML 均已按同名目录归档。
- Android 四个正确红分别为：名称 getter 裸 NoSuch、已登记 ID 又读、未知登记还探测
  ID、同词但 owner 更新只调用 1 次而非 2 次。新 SourceMetadataSnapshot 仅保原引用和
  宿主登记键、受守卫名称/语言/错误，不读 ID，不用插件 equals/hash。未实施者独审后
  才消费；请求、排序、标题和导航用同帧值，同名不同 ID 不再被 TreeMap 合并。
- `ec06-creator-platform-metadata-red` 中 Android model 11 方法已经绿，真实 UI 仍
  直接访问失效 ID，正确红。最小 UI 修正让全局/迁移共用内容消费 State.metadata；导航
  再次检查冻结 ID，未知禁用而非造编号。
- `ec07-migrate-metadata-wiring-red`：实际迁移 model 显式传入的 SourceManager 没交给
  父 model，故父级认为全部未知，期望 Success 实际 Error；3 次 retry 算同 1 方法。
  只补父构造器传参后通过，保留 20→10 专用顺序、显式选择不套全局隐藏/语言规则、
  两源 API 各一次且 ID getter 零读。测试只关闭自己创建的父 dispatcher。
- 新 `ec07-metadata-selection-red` 的共享两门与实际 model 一门证明：先准备全部
  metadata 再丢弃会触发隐藏源/不匹配语言的名称 getter，宿主语言策略还未执行就读了
  name。三参数 overload 改为 language-first，纯宿主 predicate 在 guard 外；二参数
  入口保持原顺序。生产隐藏/置顶 ID 在读取之前排除，语言排除不执行名称。控件/策略
  及部分元信息的原规则不变，未知语言仍有错误反馈。
- `ec07-source-metadata-focused-green` 共享双目标各 6 方法及准确 13 文件严格格式
  已通过，但 App 编译在跨模块 nullable language smartcast 失败，不是产品红；只用局部
  val 缓存 language 后继续尚未执行的 App 范围，没有重跑有效共享 12 项。
- `ec07-source-metadata-app-final`：model12、recovery2、Migrate1 全绿；实际 UI 的第四
  次查询（未知登记）暴露 Lazy key 为 snapshot 对象、不能存入 Android Bundle。
  这是正确产品红。未知 key 改为可保存的代际/快照行位置字符串，仅为 UI token；源 ID
  仍 null，API 和导航仍禁用。该实际 UI 在后述代理主绿中通过，未重复其他 15 项。
- 真实页面覆盖四次查询、坏/健康/挂起三源、未置顶第四源零 getter/API、坏源 API 开始
  后 name/lang 失效仍能显示、旧挂起源取消、owner 新旧/未知版本、未知 ID 零 API、
  健康行实际 Navigator 推入 BrowseSourceScreen(10) 且 ID getter 零读。目的页只捕获
  真实 Screen，不借本门验证无关浏览内容。
- 最后 `ec07-metadata-recovery-cache-red` 的实际方法证实：元数据已恢复，同词切筛选仍
  复用旧 Error，34 秒。最小修正从同一旧 State 读取 results/owners/metadata，只在引用
  对应的前后快照都无 metadata error 且 owner 不变时复用。方法也验证 manual retry，
  恢复源 API 一次、健康源切筛选复用一次/手动重查两次。
  `ec07-metadata-recovery-cache-green` **PASSED/exit0，17 秒**，准确 3 个受影响方法
  及 App11 文件正常严格格式通过；不重跑未受影响的共享/UI/迁移。

Root 最终有效组合 **28 项，零失败/错误/跳过**：共享6×2、model12、recovery2、
Migrate1、实际 UI1。共享归档 `.test-tmp/ec07-source-metadata-focused-green-xml/`；
App 基线 `.test-tmp/ec07-source-metadata-app-final-xml/` 排除其失败 UI；实际 UI 取
`.test-tmp/ec06-creator-metadata-boundaries-green-xml/`；三方法末验取
`.test-tmp/ec07-metadata-recovery-cache-green-xml/`，同平台/类/方法覆盖旧结果而不重复计数。
共享/消费/predicate/保存 key 与缓存补丁均由未实施者独审，最终 GO；该 GO 不扩至未列边界。

### Creator/实际 DI 与保留旧迁移 picker

- `ec06-creator-metadata-red` 的实际 adapter 三方法正确红，包含 strict 注册零探测、
  已知元数据逐源失败/健康继续和控制优先。两端 production DI 新门在
  `ec06-creator-platform-metadata-red` 各三个 site 得到 raw NoSuch，而非仅 mock adapter。
- 原 AuthorsTab 仍调用 await(id, sources)，直接改成 await(id) 会改变 legacy 的
  permissive/includeUnknown 默认，故未改变它。Service 从实际已配置 Catalogue adapter
  派生本轮显式入口，继承宿主 lookup/身份 factory，而 cache 全新、保原显式来源与策略。
  独立旧入口没有 host lookup 才首次守卫登记；注入 lookup 返回 null 绝不探测 ID。
- 独审确认的两个具体旧语义问题加入真实红：未知登记但 lang=fr 已被排除，不应先
  MalformedData 中断健康 en；配置 resolver 指向 B，不应把显式 A 同 ID 的实际 API
  换给 B。`ec06-creator-legacy-selection-correct-red` 的这两门正确红后，仅调顺序和
  explicit clone resolver，不修改独立 adapter 本来的 resolver 语义。
- Desktop 实际 DI 选择门另证实多读一次 fr.name，改接已审三参 helper。已知隐藏 ID
  完全不读元信息，fr 只一次 language、零 name，健康 en 继续；selected unknown 的
  Malformed/实际 Linkage 分类和零 ID 读取仍由原门保留。
- 旧迁移 picker 初次 ID click 抢在行出现之前、unknown 等待超时属于装置问题，未记
  产品红。补实际 model.migrationSources 进入/读数后，同一四 site（ID/name/lang/未知）
  给出正确原始 getter 红。最小 safe DTO、禁用及实际 API 前登记复查后，通过真实 UI；
  正常来源仍走原搜索，错误走原 Snackbar，不假装空结果。
- `ec06-creator-metadata-boundaries-green` 保留有效 domain15、Android DI1、Desktop3，
  **19 项**；另 Root UI1 单列。只有 Authors 新 UI 首次等调用计数失败，不能计为绿。
  诊断确认 counter-only 等待未同步真实 SQL 后 UI 恢复；等待改为实际兼容语义节点，
  保留 API=1/poisoned ID=0，production 未为此改动。
- `ec06-creator-authors-di-feedback-green` 的真实 Authors DI/UI 及旧迁移 Snackbar
  各1通过；旧 DI shares 门缺新登记/已安装帧 fixture，补明确宿主记录，
  `ec06-creator-old-di-registration-green` 仅该旧1通过。没有 production fallback。

实施代理最终有效 **22 项，零失败/错误/跳过**：domain15、Android3、Desktop4。
三批有效 XML 分别保存在同名 `.test-tmp/*-xml/`，主代理已逐 case 去重核对。
`ec06-creator-metadata-strict-final` **PASSED/exit0，3秒**，准确 domain3/app3/Desktop5
共11 Kotlin 严格格式；临时 materialize Apply 的长行选项未作为验收，终态使用正常规则。
主代理独立核对 API/DI snapshot/explicit 继承、选择顺序、unknown及旧迁移错误接线通过。

全阶段 **50 项有效组合**与 **24 Kotlin 文件严格格式**通过，不声称最终代码重跑全部50。
未运行完整模块/全量，不推送、不做正式 APK、不操作用户平板。维护见7.6.11–12。
EC-06/07 保持未勾选：Desktop 全局完整描述与登录恢复仍欠；另已读到 toDomainManga
直接访问 SManga 属性，延迟 getter 的受保护消费须补真实失败验证。这是原调用覆盖范围的
未闭条件，不是原始 Zerobyw 崩溃的新归因。EC-08 原 APK/ART/R8 原生门仍须执行。

## EC-06/07 返回字段与 Desktop 全局快照阶段（2026-11-06）

基线 `312e067f75fd902ca66e900fa2f620095c87d6fb`。18个 Kotlin 文件属于同一返回对象/
搜索描述链及其真实测试；Save/SourceSharedState 既有测试的较大机械差异来自严格格式，
未扩产品范围，用户 reader 未提交内容未纳入。

### 返回对象、Creator 与真实 SQL

- `ec06-returned-manga-fields-red` 的真实 SQL 两门确认 getGenres 未分类、memo 包装
  fatal 未解包；domain 初次 fixture 漏 getFilterList 是编译装置错误，不计产品红。
  `ec06-returned-manga-domain-correct-red` 的 mapper2/Creator2正确红；实现逐 getter/
  getGenres/copy 守卫，保旧 JVM 入口、原 SManga 身份和宿主策略/构造在 guard 外。
- `ec06-returned-manga-first-green-refresh-red` 确认实际 refresh 把已标记兼容错误覆盖
  为 Storage；`ec06-returned-manga-early-read-red` 确认最早 listed.url 原 IO，以及
  merge 首次 getter 故障被 runCatching 吞掉。最小修正仅对未初始化属性保留旧回退，
  本链实际消费字段在 SQL 前捕获，全量字段在原章节准备回调之后捕获。
- `ec06-returned-manga-boundaries-green` **PASSED/exit0，21项**：domain JVM8、Android5、
  Desktop8。domain13 XML已归档；Desktop8 XML后被窄测覆盖，权威 JSON 和原日志逐
  case PASSED保留，不冒称后来 XML 来自该轮。
- `ec06-returned-manga-host-control-red` 确认存储 catch 的控制解包缺口；同项 green通过。
  最后 storage-marker-and-format 的两个真实方法通过且 XML2/零跳过已归档，但该 key
  因格式整体 FAILED，不能整体计绿。只透传显式兼容 marker，普通 IO 保持 Storage，
  宿主 IO(Linkage/Assertion/VM)依既有 helper 传播原 Error。domain4正常严格格式已有
  通过；`ec06-returned-manga-format-strict-final` **PASSED/exit0**，Desktop2严格通过。
  materialize Apply不作验收。本簇有效 **22项**（domain13/Desktop9），主代理独审GO。

### Desktop 查询描述、分页 URL 与显式登录地址

- `ec07-desktop-prepared-metadata-red` 四门正确红：3处重复 ID getter 的原始 NoSuch、
  1处隐藏 getter。prepared入口后四门绿。真实页面在搜索开始后毒化 id/name/lang：
  first-green-ui-red只先看到超时；补诊断的 `ec07-desktop-prepared-ui-cause-red` 确认
  composition捕获 `NoSuchMethodError: late ID`，不靠超时猜因。Screen/导航/重试接同一
  sourceMetadata，删除session第二份来源列表，未知不探测ID、不造请求。
- `ec07-prepared-screen-green-returned-recovery-red` 原4门/真实UI1绿；新 shared reducer2、
  Desktop URL/login2正确红。逐 URL 守卫，其余集合处理在外；Desktop 锁外 reduce，仅
  当前 state 实例仍有效才提交并附冻结归属。登录地址只在显式动作读取，校验展示帧的
  确切 request，失败回该源错误，不造地址。`ec07-prepared-returned-recovery-green`
  **PASSED/exit0**：共享新2门双目标、Desktop6及真实UI1通过，XML分平台归档。
- 独审发现 retry 取消的快照恢复缺口。首次 cancel-red被协程复制 CE 的 assertSame
  阻挡，不计产品红。改为取消类型/message后，
  `ec07-prepared-retry-metadata-cancel-correct-red` 在旧 snapshot 引用断言正确失败。
  最小修正仅在 owned started 帧中同时恢复原 metadata/Failure，不覆盖替代请求。
- 首轮 regression 保留有效18门：投影2、生产UI3、会话4、筛选UI3、共享coordinator6。
  Policy8失败来自旧mock未提供合法name/lang；登录UI旧fixture没有host登记/installed
  端口且未等真实代际，并用403期望登录。保留失败，补fixture，不加production回退，
  不改403分类；登录场景改用401并断言Authentication。
- `ec07-prepared-retry-cancel-final-regression` 的metadata7/Policy8通过，但因旧登录
  fixture整体FAILED；只复用15门。`ec07-prepared-login-auth-ui-final`实际登录UI1通过。
  初次准确格式domain2严格通过，Desktop10一处标题超120列失败；提取同值局部名称后
  `ec07-prepared-format-strict-final` **PASSED/exit0**，Desktop10正常严格通过。
  `ec07-prepared-final-ui-and-cancel` **PASSED/exit0**，末次实际搜索UI/登录UI/取消3门
  重新通过。Root有效 **38项**（共享2×2、Desktop34），零失败/错误/跳过；未实施者独审
  最终GO，取消修复只做同项复审，不宣称最终代码重跑全部38项。

本阶段有效组合 **60项**、准确 **18 Kotlin 文件**严格格式通过。上述命令/权威JSON/log
位于 `.gradle-coordinator/`，XML归档在同key的 `.test-tmp/*-xml/`；没有为补归档重跑。
未执行完整模块/全量、推送、发布或用户平板操作。维护说明见7.6.13及页面契约。

EC-06/07仍不勾选。下一闭合点：Desktop materializer 晚读URL、标记兼容分类与未知ID
诊断；Android全局/Browse/data paging、DeepLink/SmartSearch/SyncRecovery等已盘点的
mapper前后消费及登录恢复。不能以本批mapper/reducer绿替代实际caller。EC-08原APK/
ART/R8、旧ABI与未来ABI原生集成仍未执行；API36不能替代反馈者Android17厂商实机。

## EC-06/07 转换保真与 Android 调用端阶段（2026-11-06）

基线 `9fe1463226a6f5075815e4a8160fb65ba01b3442`。本阶段是既有异常边界的消费端接线，
不新增页面、依赖版本、schema 或反馈协议；用户 reader 未提交内容未纳入。下列 key 的
命令/权威 JSON/log 位于 `.gradle-coordinator/`，XML在同名 `.test-tmp/*-xml/`。

### Desktop materializer 与未知来源诊断（Root）

- `ec07-materializer-boundaries-red` 六门中五门正确红、一门普通存储 IO 正对照绿：
  真实 Save+挂载搜索页把 getGenres 的已标记兼容错误降为 Unknown；sync 晚读 URL 原始
  NoSuch 逸出；包装取消与宿主/致命错误被吞为结果；未知登记的兼容错误日志为0。
- 最小实现把冻结身份放入本轮 state 的纯宿主 Long→DTO map；job 内逐 URL 守卫并冻结
  原对象表，SQL/persist不guard，失败只认显式兼容 marker。控制与宿主 Error先传播，
  普通 IO 保持原 Unknown/Retry；兼容错误保留分类、归属、None恢复与安全日志。
  未知 ID 的已接受 prepared 错误诊断不创建 child 或假请求。
- `ec07-materializer-boundaries-green` **PASSED/exit0，39秒，8方法**，零失败/错误/跳过：
  新六门加真实 canonical 重试/旧完成拒收、close后不可取消完成拒收各1。归档4 XML
  方法数1+2+1+4；真实 SQL 的坏源零成功写入，健康结果与总进度保留。
- 首格式 key 仅因临时 init 缺显式 lint plugin 失败，不计产品红；correct格式一度有两
  个长测试声明，换行后 `ec07-materializer-format-strict-final` **PASSED/exit0**，正常
  严格规则准确5文件。未实施者独审GO，未扩至其他未列消费端。

### Android Global/Browse/Login（实施代理，Root独审）

- `ec06-android-late-mapping-red` 正确红2方法：Global的晚读字段兼容异常逃至 owned
  worker handler，未形成逐源错误；真实 Paging.load直接抛同类标记异常。XML6是各
  retry3，不算6。最小转换捕获在 SQL外，冻结归属并只在接受该代际后诊断。
- 首 registration-login key漏旧测试的新增 required sourceId，只是编译装置错误。
  `ec06-android-mapping-registration-login-correct-red` 的注册ID、prepared factory与
  缓存/同页重复四个独立方法正确红，原mapping2已绿。重复回归是新实现先转换所有项
  引入的多余 title/memo读取，修为先守卫URL、只转换未缓存首次项，仍保原分页边界。
- `ec06-android-login-ui-filter-owner-red` 确认真实Navigator登录点击和filter内替换
  安装帧的两门失败。Model在第三方筛选器调用前捕获owner存入状态；Pager消费同帧。
  Global实际点击用prepared ID/name，只守卫baseUrl，旧generation/pageError拒收，
  失败回既有错误区，不能打开WebView；SQL和Navigator都不包作扩展调用。
- `ec06-android-global-browse-login-focused-green` 整体FAILED，但有效34方法全绿并
  归档。失败为默认DI门fixture错误listing（生产AppModule已解析真实ExtensionManager），
  以及两条过期403→OpenLogin预期，均保留失败，不计新产品红。修fixture/旧断言并加
  HTTP矩阵，不改生产分类：401认证/登录，403 Forbidden/Retry，429/500保既有恢复。
- `ec06-android-late-caller-final-focused` **PASSED/exit0，24秒**：默认DI1、两条403
  修正、HTTP矩阵1准确补验通过，且准确10文件正常严格格式通过；中间materialize Apply
  不作最终证据。Root逐 case合并核对为当前38有效方法：Global Model14、factory4、真实
  登录UI1、Browse Model11、Recovery7、默认DI1；旧403方法改名后的两条历史失败不冒充
  当前方法或额外通过。未受后续改动影响的34与最终4组合复用，不宣称末次重跑38。

### 遗留仓库分页与 DeepLink（Root，实施代理独审）

- `ec06-data-deeplink-consumption-red` 正确红6独立方法：data2为实际Repository三路
  load API原NoSuch逸出、存储catch吞包装fatal；App4为登记95仍读ID、未知登记先发
  getManga、返回字段丢DETAILS阶段、真实UI的chapterUrl原NoSuch使该项卡住。App XML12
  是retry3，不算12；其余四个健康hook继续显示正确反馈，不靠整体超时猜原因。
- Repository将请求的宿主ID传给search/popular/latest Paging，逐API/字段守卫并保
  对应phase，现有catch先恢复控制；SQL在外、旧构造入口保留。DeepLink选定后只查宿主
  登记，未知显示原错误且不发作品API/探测ID；详情字段DETAILS、章节URL一次CHAPTERS
  守卫，之后本地查找/同步匹配用宿主值。旧测试manager补显式登记，无production回退。
- `ec06-data-deeplink-consumption-green` **PASSED/exit0，47秒，8方法**，零失败/错误/
  跳过：data2、Native DeepLink4、旧健康解析/章节同步2，XML分平台归档。主代理核对
  AndroidSourceManager确实按Source引用读既有map键，不执行getId。
- `ec06-data-deeplink-format-strict` data3正常严格通过，App3有一处长行阻止文件写入；
  手工换行后 `ec06-data-deeplink-format-strict-final` **PASSED/exit0**，准确App3正常
  严格通过；未放宽规则或格式化用户reader。实施代理独审GO。

本阶段有效组合 **54方法**（Root16、代理38）、准确 **21 Kotlin文件**严格格式通过，
`git diff --check`通过。两方互审各自未实施的目标链路；不与前阶段60项重复累计为本轮
重跑，不运行完整模块/全量、不推送/发布、不操作实体平板。维护说明见7.6.14与页面契约。

EC-06/07保持未勾选：Browse UI标题/openWebView/assist、Smart迁移实际失败反馈和
SyncRecovery尚未开始本簇实现，不能由本轮Model或mapper通过替代。EC-08原APK/ART/R8
及真实旧/未来ABI集成仍为必做，厂商Android17不以API36模拟器代验。

## EC-06/07 Browse 展示与同步恢复阶段（2026-11-06）

基线 `818e1ab53fddb936d21ccc066d992ec24a672223`。本阶段沿既有页面及恢复入口补
第三方读取，不新增页面、schema、依赖或反馈协议。命令/权威 JSON/log 位于
`.gradle-coordinator/`，XML 归档位于同名 `.test-tmp/*-xml/`。

### Browse 页面（实施代理，Root 独审）

- `ec06-browse-ui-metadata-red` **FAILED**，6 独立方法正确红；XML18为 retry3。
  真实 UI 的 name/supportsLatest/assist/baseUrl 和准备后毒化 getter 路径，以及
  legacy factory/Model 准备失败均执行 production，不以源码扫描替代。
- Model 在任何 filter/API 前冻结 owner，准备本页所需 name/supportsLatest；失败
  复用 filterError 阻查询。生产 toolbar/listing/WebView 使用准备值及宿主 ID；
  assist/显式动作只守卫 baseUrl，导航在外，错误仅回写当前 State/pageError。
  presentationError 沿原反馈位显示，保留健康内容，不在重绘时自动重试。
- `ec06-browse-ui-metadata-green` **PASSED/exit0，35秒，26独立方法**：真实 UI5、
  Model12、Recovery8、默认 DI1；4 XML均零失败/错误/跳过。真实401→点Login→
  晚baseUrl失败保留调用前owner，未导航；毒化后标题/ID不再被重复读取。
- `ec06-browse-ui-format` **PASSED**，准确7个Kotlin正常严格规则，无放宽。
  Root核对完整production diff、各测试边界及原件后独审GO。

### 同步恢复 READER（Root，实施代理独审）

- `ec06-sync-source-native-red` **FAILED/51秒**，真实Navigator→恢复Screen的1方法
  正确红，原NoSuch从LaunchedEffect逸出；XML3是retry，不作为3个独立用例。
- 保持原行为机械提取 helper 后，首 helper-red被并行Browse签名编译装置阻挡，不计
  产品红。`ec06-sync-source-helper-correct-red` **FAILED/37秒**，4独立方法正确红：
  已登记仍读ID、未知先探测、返回字段/setter缺少分类、wrapped控制未传播。
- helper使用宿主登记ID和Source引用owner，调用前冻结归属；详情API及返回字段/
  URL setter精确守卫，保留原URL、超时及IO调度。输入构造/SQL在guard外；普通
  存储IO不改类，wrapped fatal/取消/宿主Error先传播。当前请求接受后才反馈和诊断，
  沿原返回协议失败，不伪装成功导航或泛化为身份缺失。
- `ec06-sync-source-boundaries-green` **PASSED/32秒，6方法**：helper4、真实错误UI1
  （API/返回字段两site）、原健康返回路径1，XML归档零失败/错误/跳过。
- 独审建议把宿主input构造移出guard，已机械收窄。两次末验format key仅因测试名称
  超120列失败、尚未执行测试，不计产品红或绿；缩短名称，无suppression。
  `ec06-sync-source-strict-final` **PASSED/exit0，32秒**，准确3文件正常strict，
  同6方法重新通过；XML1+4+1零失败/错误/跳过。未实施者绑定最终证据独审GO。

本段有效组合32方法，不与前轮54项重复累计成末次全跑。Smart迁移搜索及其实际失败
反馈仍待收口，EC-06/07暂不勾选；EC-08原APK/ART/R8保持原必做范围。

## EC-06/07 Smart 迁移收口（2026-11-06）

基线 `34dcff172fe18940ecf48b90fa6a6ef312b87330`，前置实现测试曾以其父提交运行，
该基线仅增加已验证 Browse/Sync，不改变迁移语义。8个Kotlin文件为同一引擎、实际
ScreenModel、原反馈位及真实测试；无新页面/schema/协议，用户reader改动未纳入。

- 首 `ec06-smart-migration-source-red` 为 repository fixture 返回值类型编译错误，
  不计产品红。`ec06-smart-migration-source-correct-red` 的 Engine2正确红；Model3
  因 InMemoryPreferenceStore 每 getter 新建实例导致候选配置为空，不计产品证据。
  修为 initialPreferences 并断言实际配置/filter到达后，
  `ec06-smart-migration-model-behavior-red` **FAILED/42秒**：坏源假NotFound、重复
  ID getter、真实页面hideUnmatched隐去错误3门正确红；两选择模式健康对照已绿。
- 引擎只守卫实际ID/filter/API/返回字段，生产使用宿主ID重载；Model仅保真显式
  兼容marker，SQL/缓存/同步在外。普通按序、按章5并发/最新章策略保留，坏候选详情
  失败后继续剩余候选，不为健康未选候选新增详情探测。Failure沿原反馈位显示且不
  被hideUnmatched删除；手动选择亦保章/详情/标签错误与调用前owner。
- Root独审阻止完整mapper给details新增url/initialized要求，最终仅冻结原更新链
  消费字段。健康门禁读这两个字段、允许未初始化标题，并断言逗号genre元素不变；
  UpdateManga拿到宿主快照，不改变传给插件的原输入，也不守卫整个宿主更新过程。
- `ec06-smart-migration-first-green` **PASSED/36秒，9方法**，原件已归档。
  独审发现初始标签不诊断、健康候选成功时丢坏源日志；
  `ec06-smart-migration-diagnostics-red` **FAILED/21秒**，两日志门正确红，真实
  NonCancellable挂起→移除→等待原Job结束后的零回写/零日志正对照通过。
  以Selection(result, failures)保留诊断，await后检查context/item引用/scope再接受，
  初始已接受Failure记录一次，最终Failure不重复写日志。
- `ec06-smart-migration-final-focused` **PASSED/32秒，14独立方法**：Engine2、
  Model8、真实UI1、旧Batch3；4 XML零失败/错误/跳过，准确8文件正常strict通过。
  自查发现legacy ID入口在控制解包外；`ec06-smart-legacy-id-control-red` 正确红
  （预期宿主AssertionError却得IO，XML3为retry）。两行最小修正后
  `ec06-smart-legacy-id-control-green` **PASSED/13秒**，Engine2及对应2文件strict
  重新通过，其余12方法不受影响。有效组合仍为14，不冒称末次重跑全部14。
- Root亲读最终4 XML及Engine补验XML、完整production diff及关键测试，独审GO。
  Sync的未实施者独审亦亲读最终6项原件后GO。命令/权威JSON/log/XML仍按前述单一
  `.gradle-coordinator/`和`.test-tmp/*-xml/`机制归档，无额外状态系统。

### EC-06/07 原任务验收对照

| 原验收项 | 已有生产接线及有效证据 |
| --- | --- |
| FILTERS→热门/最新/搜索、同步/挂起及解析错误 | 默认HttpSource/Rx32双目标、直接调用与三种Paging、Smart引擎/Model；各段保留正确红测 |
| 详情/章节/页列表/图片/下载无假成功，健康并行继续 | 阅读下载46、Creator21、DeepLink/迁移/目录27及后续返回字段/SQL补验；确定性错误不自动重试 |
| getter/返回字段与真正caller，宿主存储不误归扩展 | 元数据、返回字段/Save、materializer、AndroidGlobal/Browse/DeepLink/Sync/Smart各阶段真实门 |
| 双端全局搜索真实入口、坏/健康/挂起源与进度 | Desktop页面30、Android真实页面与双端三源取消13及后续prepared/真实UI补验 |
| 显式重试、取消/新代际拒收、版本重新评估 | 双端三源、prepared retry取消红→修、materializer attempt/close与Smart移除迟到门 |
| UI/i18n/冻结名版本和安全诊断，坏源不显示登录恢复 | 身份传输23、实际日志35、登记键29、真实UI/Global登录及Browse/Sync/Smart后续门；unknown不探测ID、不造值 |

表内数值指对应阶段的有效组合，存在跨阶段复用；**不得相加声称末次全量通过**。
这些证据闭合表内已实施的调用与消费范围，不能据此整项勾选EC-06：有限收口审查又
确认常规 `UpdateManga.awaitFromRemote` 的返回详情仍交宿主更新链裸读；
`MangaScreenModel.fetchFromSource` 与 `LibraryUpdateJob` 的 `catch(Throwable)` 对包装
控制/宿主Error及兼容分类仍有消费缺口。这是原“作品详情/章节更新”实际路径，继续
以该有限链的真实失败门收口，不扩大到任意SChapter字段。EC-07已有全局入口证据，
但其前置EC-06未完成，暂仍未勾。扩展自行丢弃保护链、未管理线程/JNI为原范围止点。
EC-08原APK、R8后新旧ABI与未来源/健康源原生集成仍为必要门槛，本段不宣称该门通过，
不替代Android17厂商实机或正式候选验收。

## EC-08 原生装置准备（在途，未作原生验收结论）

准备以 `e329d42c7c699552b1f12200a80c52fafb037420` 为最近已提交功能基线；实际 R8
源码快照仍须包含 EC-06 余下常规更新链的最终提交，不能用此准备阶段代替完整门槛。

- 本地原 APK 经 `ec-fetch-zerobyw-fixture.py --kind apk --existing ...` 验证79679字节/
  固定SHA b04e4a…874，apksigner证书9add…da2；aapt2读回版本106002/1.6.2、target37，
  classes.dex头为038。未下载或修改/重签原 APK，未将它纳入Git。
- 将已归档 old/new/future 薄JAR用SDK36 D8 min26直接转换，仅为测试数据，不重编译
  宿主或旧输入。自有future DEX以独立`ec.future.controlled`1.6.1打包并用任务专属
  测试证书签署，不替代正式证书。容器/原DEX/hash和输入路径见资产provenance。
  D8对Kotlin2.3元数据有警告，exit0不作ART成功证据。最初keytool原生日志以Windows
  代码页输出，严格解码为UTF-8保留；后续Java子进程显式UTF-8/英文，不忽略解码错误。
- 新instrumentation调用真实private ExtensionLoader/信任路径和共享搜索service，
  用原保护链仅改回环传输目的地；独立probe不静态链接新blocking符号。真实原源在途
  时让未来源失败后继续健康结果；原异步网络worker逐线程join后再判一次callback/
  零uncaught，probe验证默认值/上下文/异常及已取消Job。专用模拟器、benchmark/
  nondebuggable入口先验证，finally恢复临时字段/偏好及本测试文件。
- 首compile及task inventory key因PowerShell拆分未引号的带点`-P`参数失败；
  权威command已核，未把它计为产品红。正确参数的compile在旧instrumentation
  `BrowseSourceUiWiringTest`漏接required host sourceId处编译失败；仅补健康fixture
  参数，无production默认回退，亦不计ABI红。
- `ec08-native-instrumentation-compile-reviewed` **PASSED/exit0，2分31秒**：准确3个
  文本target（app build、专项测试、旧UI fixture）正常strict与
  `compileBenchmarkAndroidTestKotlin`成功。只编译，无assemble/R8/运行。
- 未实施者独立核3个DEX class_defs均只含ec/aex样本，不含host/Kotlin/coroutines/
  编译桩，并核old/new method_ids分别引用runBlocking与runBlockingK及默认入口。
  首审的线程终态竞态、非挂起async不能证明并行、未实际验证取消3点已修；末次编译
  覆盖修复，同轮修复复审GO仅限装置与编译，不是运行验收。
- ADB枚举为空、无emulator/qemu进程。只在任务私有ANDROID_AVD_HOME创建新API36
  配置`.test-tmp/ec-native-avd-home/ec-api36.avd`，未启动，不复用用户AVD userdata。

下一步仍先收口有限Update/Manga/Library两端实际消费，再从完整已提交独立快照进行
一次聚焦benchmark R8宿主/test APK构建与专用AVD运行。须核实际包名/签名/hash、R8
配置/DEX和test APK无另一份宿主runtime或missing桩，不添加测试专用keep掩盖production。
未执行正式候选、发布、推送、全模块/全量测试或用户平板操作。

## EC-06 常规详情／书库更新返回消费（2026-11-06）

基线 `a9e5bea5ab9febf8fa58cc836139bbf707873ce5`。6个production与5个测试文件构成
共享元数据→Android详情/书库和Desktop唯一另一个调用端的有限链，Module只接新的
冻结诊断身份，不改schema/sourceDate数据或新增UI。Root独审未实施的完整production
及关键测试，并核原件；用户reader改动不纳入。

- `ec06-update-metadata-shared-red`：4方法中2新门正确红，旧标题/genre规则2绿；
  getter原IO/包装控制未按边界传播。随后Android实际SQL/cover门、真实Manga catch
  门与Desktop Checker实际四site门各有正确红，既有普通/健康语义保留。
- 首Android key缺测试stringResource import、hostID门的relaxed pendingPhase误走
  恢复分支均是装置问题，不计产品红；修为明确null后取得hostID正确门。
  Library早期多轮0进入计数/缺文件也不计产品证据；“缓存Scope”只是前期推断。
  最终确认Kotlin默认chapterRepository在mock body前解析时缺依赖，补齐后API到达。
- `ec06-library-update-default-dependency-correct-red` **FAILED/24秒**，诊断性关闭
  retry仅本key，之后恢复默认。两方法均先通过API进入计数1，再分别在错误文件
  重读source.toString的原NoSuch、包装VM被吞为null处正确失败；同key Manga已正确
  本地化并保冻结owner。保护版仅撤本任务内容取红、全文校验恢复，不回滚用户内容。
- 共享helper逐实际字段守卫并返回宿主MangaUpdate，Android先捕获再访问cover/cache/
  SQL；标题未初始化/genre逗号语义保留。Manga/Library先恢复控制再仅消费marker，
  owner在源调用前冻结；错误文件不再重读已知坏标签。Desktop返回消费及prepare
  callback精确守卫，目录事务在外，兼容沿原sourceError反馈，普通存储IO不改类。
- `ec06-update-consumers-final-focused` 整体**FAILED/1分48秒**，只复用有效共享8和
  App12；归档中Desktop XML为旧输出不计。Library唯一剩余host AssertionError
  Same引用失败实际为协程栈恢复副本，类型/message/原cause关联正确；VM Same和
  CE类型/message已过。`ec06-update-di-wire-red-control-regression` 的App1补验绿，
  该key另一Desktop方法装置失败不计正确红，不宣称该key整绿。
- 新Module DI门首unknown/builtin对照不足以检出遗漏接线。补真实Manager现有runtime
  snapshot的已知Source/registeredSources/metadataSnapshot，jar文件名故意不同，
  目标仍为真实Injekt Checker执行更新。早期expected2/actual3实为idReads，并非API
  次数；后台旧SourceRepository裸读已毒化的builtin ID使该装置失败。最终此门使用
  合法稳定ID，hostID不探测由专门矩阵覆盖，不因此声称旧Repository已保护。
- `ec06-update-di-known-owner-correct-red` **FAILED/9秒**：仅撤Module named provider，
  API两次到达已通过后，expected trusted.installed/actual null，证明接线必要；全文
  校验恢复。`ec06-update-consumers-desktop-final` **PASSED/exit0/21秒**，Desktop6
  零失败/错误/跳过；已知/未知归属、ID、冻结版本、真实commit和普通IO/控制边界有门。
- 精确定向format首key因Desktop未显式应用lint plugin失败，只修init；深层日志lambda
  格式后长行改为同义私有报告函数，无suppression。domain2正常strict复用，最终key
  App6/Desktop3正常strict；共准确11文件。Root已核完整diff与上述有效XML，独审GO。

有效组合**27**＝共享4×2＋Android13（Update4/Manga4/Library5）＋Desktop6；不是末次
重新全跑27，也不与前阶段相加声称全量。命令/权威JSON/log/XML按既有单一机制保存。

EC-06/07暂不勾：同轮审计确认DesktopSourceRepository四投影的公共mapper仍对所有
Source裸读id/lang/name，getSources另读supportsLatest，计数已有hostID仍重读。这是
后台DI中已观察到的getter路径，不把builtin测试Assertion推断为原APK崩溃新根因；
下一有限闭合点只修这条投影及其现有Module接线/真实Flow测试，不新增错误协议/UI。
EC-08仍须在最终提交快照执行原APK/ART/R8，不以本段绿测替代。
