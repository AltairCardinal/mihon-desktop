# Extensions Desktop API 迁移计划

## 目标

将 `extensions-desktop` 的 JVM 构建链从 Android AAR 依赖迁移到 Mihon Desktop 可编译的扩展 API 层，避免 `extensions-lib` / `quickjs-android` 在 `compileClasspath` 上阻断所有模块。

## 已完成

- 修正 GitHub Actions “0 产物也成功发布”的错误行为
- 改为按模块隔离构建，并允许部分成功发布
- 修正 `jar` 任务入口与补丁落点不一致的问题
- 修正 `android-compat` 的 JVM 11 目标、Kotlin stdlib 缺失问题
- 修正 `androidx.preference.Preference` shim 的 `setDefaultValue` final/override 错误
- 修正 `androidx.preference.Preference` shim 的属性 getter JVM 签名冲突
- 建立首版 `desktop-api` 模块，包含最小 `source/model/online/network/util` API、JVM `Injekt` 替身，并接入 `patch.sh`
- 将 `extensions-source` 的 JVM patch 从 `extensions-lib` AAR 迁移到 `project(":desktop-api")`
- 补齐首轮 Android shim：`Preference`、`CookieManager`、`Base64`
- 本地单模块构建已推进并打通到 `:src:en:aeinscans:jar`
- 已确认产物路径：`/tmp/extensions-desktop/extensions-source/src/en/aeinscans/build/libs/eu.kanade.tachiyomi.extension.en.aeinscans-v1.4.1.jar`

## 当前阻塞

### 1. 长尾 Android shim / 本地工具库还未补全

`extensions-lib` 的 AAR 阻塞已经被首版 `desktop-api` 绕开，但还存在零散 Android 依赖需要继续补：

- `android.webkit.CookieManager`
- `android.util.Base64`
- 其它后续会继续暴露的 Android framework helper

这些问题不再会阻断 API 层本身，但会逐步出现在具体 `lib/*` / `lib-multisrc/*` 模块上。

### 2. 第三方 Android AAR 仍然需要分类处理

除 `extensions-lib` 外，部分本地库或扩展仍可能引用第三方 Android-only 依赖，例如：

- 图像解码器
- JS 引擎
- 站点自带 Android helper AAR

这类依赖需要分别做三选一处理：

- 改成 JVM 可用替代
- 降级为 `compileOnly`
- 继续列入失败清单，避免阻断其余模块

## 下一阶段实施

### 阶段 A：扩展 `desktop-api` / shim 覆盖面

目标：把当前“单模块成功”推广到第一批可稳定构建的纯 HTTP 扩展。

内容：

- 跑一组代表性纯 HTTP 模块，逐个补 Android shim 缺口
- 只为真实需要的模块补最小本地 `lib:*` 依赖，避免把不相关 Android-only 库提前拖进来
- 把当前补丁里仍然“写死”的局部依赖，整理成可维护的规则或白名单

验收条件：

- 至少一批纯 HTTP 扩展可稳定完成 `jar`

### 阶段 B：拆分 QuickJS 支持

目标：不要让 QuickJS 扩展阻断非 QuickJS 扩展发布。

内容：

- `desktop-api` 默认不依赖 `quickjs-android`
- 对引用 `app.cash.quickjs.QuickJs` 的扩展：
  - 先识别并打入失败清单
  - 后续评估是否切换 GraalJS / JVM QuickJS 替代

验收条件：

- 非 JS 扩展先可发布
- JS 扩展被单独列入 unsupported / failed 列表

### 阶段 C：回到 CI

目标：将本地可编译链重新接回 GitHub Actions。

内容：

- patch.sh 复制 `desktop-api` 到 patched `extensions-source`
- settings/jvm patch 固定 include `:desktop-api`
- common/core/lib-multisrc JVM patch 改为依赖 `:desktop-api`
- workflow summary 输出：
  - 成功数
  - 失败数
  - QuickJS/AAR/其它三类失败原因统计

验收条件：

- `repo` 分支首次出现非空 `index.min.json`
- 至少一批纯 HTTP 扩展成功发布

## 风险

- `HttpSource`/`ParsedHttpSource` 依赖的 network API 面较大，可能需要从 Mihon Desktop 复制最小闭包，而不是单个文件
- QuickJS 相关扩展短期内很可能只能“跳过，不阻断”
- 某些源直接依赖第三方 Android AAR，后续仍需逐个处理
