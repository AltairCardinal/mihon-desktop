# 扩展协程兼容性与故障隔离累计证据

日期：2026-11-05。实施起点：`main@736c6d18d1`。当前仅 EC-01/02；
EC-03–08 尚未实现／验收。本记录后续继续追加，不建立另一套 checkoff 状态。
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

## 尚待收口

- 主代理已独立核对红测原始缺方法、最终各目标 XML、依赖解析、格式终态、夹具无运行库/桩捆绑及制品校验。EC-01/02 审查通过，随本批内聚实现本地提交；后续继续 EC-03。
- future 目前固定预期故障，尚未实现 EC-03–07 的分类／隔离／UI 反馈。
- 原 APK 的 R8 后 Android 原生调用和反馈者厂商设备均未验收。不得据 JVM 证据宣称这些已通过。
