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
