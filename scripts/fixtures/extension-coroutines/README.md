# EC-01/02 独立二进制夹具

这些是本项目受控样本，并非第三方发布物。`old` 固定以协程 1.10.2 编译，
`new` 固定以 1.11.0 编译；均只 `compileOnly`，导出普通薄 JAR。Kotlin 编译器
2.3.10 把相同的源码调用分别链接到旧 `runBlocking` 和新 `runBlockingK` ABI。
自动测试消费已归档的 JAR，不重新编译它们，也不需要联网。

`future` 的 Source 委派给现存独立 AEX-00 v1.6 样本，经过 production
DesktopExtensionLoader 加载，过滤器、搜索、解析和网络拦截器分别真实调用未来接口。
`stubs` 只供编译，导出 JAR 绝不包含 `org.jsoup.Jsoup`、`ec.absent.FutureApi`、
host API 或协程库。因此升级 1.11.0 后仍能触发缺方法／缺类。EC-03/04 已实现有界异常
分类与真实 OkHttp enqueue 线程内隔离；专用 executor 的 worker 结束后断言一次失败回调、
无 uncaught 逸出和健康后续请求，并覆盖 Android/Desktop production 网络客户别名。
这不代表所有源业务／加载边界已覆盖；EC-05/06 的扩展调用保护仍按 roadmap 逐项接入。

## 重建前置和命令

future 编译输入不是当前宿主源码或产物，而是原有 AEX-00 生成器产出的固定上游
v0.20.4 (`df6507256acce8e7f3660783a3db6dbd1a31b6b5`) API 编译快照。
其路径在忽略目录，干净 clone 必须先执行以下显式前置，不能直接重建 future：

```powershell
pwsh -File scripts/aex00-generate-external-v15-fixture.ps1 -ApiVersion v16 -OutputDirectory app-desktop/tmp/aex00-external-v16
python scripts/gradle-coordinator.py run --key ec-upstream-api -- .\gradlew.bat -p app-desktop/tmp/aex00-external-v16 aex00ExportSampleJar --no-daemon --no-parallel --max-workers=2
python scripts/gradle-coordinator.py run --key ec-fixtures -- .\gradlew.bat -p scripts/fixtures/extension-coroutines :old:jar :new:jar :future:jar --no-daemon --no-parallel --max-workers=2
```

生成器校验每个上游文件的 SHA-256 与 Git blob，保留 Apache-2.0 来源与许可。
需本机 Git 已有指定上游对象、JDK 21、Android SDK 36 以及生成器声明的编译依赖；
首次下载遵循 network 指南及代理设置。缺前置时构建明确失败，不改用宿主 API。
导出 future 时只从已归档 `aex00-external-v16-controlled-sample.jar` 取样本类。
不重新签署或改动既有 v15/v16 JAR。

审核 `javap -c` 的旧／新调用目标及每个 JAR 的 entries 后，显式复制：

- `old/build/libs/old.jar` → `source-api/src/commonTest/resources/extensions/ec-coroutines-old.jar`
- `new/build/libs/new.jar` → `source-api/src/commonTest/resources/extensions/ec-coroutines-new.jar`
- `future/build/libs/future.jar` → `app-desktop/src/test/resources/extensions/real/ec-coroutines-future.jar`

同时更新各 provenance 的 SHA-256；不把 `build/`、`.gradle/`、`.kotlin/` 或 stubs JAR 入库。
新旧 API 的运行契约由 source-api 的 Android/JVM 共用测试执行，future 由 Desktop
真实 loader 执行；独立 probe 加载不代表 Android R8 或原生扩展加载通过。

依赖接线检查只解析实际 runtime configuration，不执行测试、编译或修改产品配置：

```powershell
python scripts/gradle-coordinator.py run --key ec-runtime-alignment -- .\gradlew.bat -I scripts/fixtures/extension-coroutines/runtime-alignment.init.gradle :app:ecCoroutineRuntimeAlignment :source-api:ecCoroutineRuntimeAlignment :domain:ecCoroutineRuntimeAlignment :app-desktop:ecCoroutineRuntimeAlignment --offline --no-parallel --max-workers=2
```

它检查 Android release production/unit、共享 JVM/Android production/unit 及 Desktop
production/test，未解析依赖、缺 configuration 或任一协程模块非 1.11.0 均失败。

## 真实 Zerobyw

原 APK 和官方同版本 JAR 不入 Git。固定来源、hash、R8 标识见
`app-desktop/src/test/resources/extensions/real/zerobyw-1.6.2.provenance.json`。
未确认此 APK 的再分发许可；源码当前分支不能证明该发布 APK 的确切编译提交。

```powershell
$env:EC_ZEROBYW_JAR = python scripts/ec-fetch-zerobyw-fixture.py --kind jar
python scripts/gradle-coordinator.py run --key ec-zerobyw -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.extension.RealZerobywCoroutineCompatibilityTest -PincludeIntegrationTests=true --no-parallel --max-workers=2
```

已有本地输入可用 `--kind apk|jar --existing <文件>` 校验，全程不下载。原 APK 经现存
Legacy converter 的 JVM 装载遭遇 `i.intercept @901` VerifyError，不能到达本次 ABI 调用。
不为此修补转换器／混淆字节码；原 APK 的冷初始化／搜索留给 EC-08 Android DEX。
本测试改用官方签名 JAR，先经 production authenticator 验签，再按现有 JVM adapter 和
loader 运行；没有把 JVM JAR 当成原 APK，也不复制 parser。
网络经 production DI NetworkHelper 的既有 seam 指向 MockWebServer，验证冷域名获取和
随后两次搜索，不能证明真实站点当前在线，也不能代替 Android R8 真正 DEX 调用。
