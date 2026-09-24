# 原版依据与当前 fork 组件基线

## 两个版本分别承担的职责

上游参考 commit：`f52d890e7f8a3c418ddab41f41d4b577bce0dc06`。
Android 样例适配 commit：`6fbf6dfca203d99d6dd32137f2df97ced40c81b8`，仓库 `AltairCardinal/mihon`。

规范引用的 S01–S23 在 `source-outline.md` 中提供上游固定提交链接。`catalog-manifest.json.source_links` 保留同样链接。下表是本次额外读取的 fork 代码；不是对本机未提交代码的检查。

| 组件/配置 | 已确认约束 |
|---|---|
| `app/build.gradle.kts` | namespace 为 eu.kanade.tachiyomi；debug suffix 为 .dev；已有 AndroidJUnitRunner；缺少本包专用的 Compose 测试依赖，需要追加。 |
| `presentation/components/AppBar.kt` | navigateUp 默认可空；使用 Icons.Outlined；AppBarActions 使用 ImmutableList；需完整传递回调与滚动行为。 |
| `presentation/more/settings/PreferenceScaffold.kt` | 顶栏与 PreferenceScreen 一并封装；onBackPressed 可空，普通子页必须主动传入。 |
| `presentation/more/settings/Preference.kt` | BasicListPreference/MultiSelectListPreference entries 使用 ImmutableMap；使用当前分支真实类型，不能直接移植新版 List/Map 调用。 |
| `presentation/theme/TachiyomiTheme.kt` | TachiyomiPreviewTheme 使用原版主题实现且不读取用户偏好；TachiyomiTheme 查询应用偏好。Lab 使用 PreviewTheme；生产按应用主题接入。 |
| `core/common/.../preference/Preference.kt` | get/set/delete/changes/stateIn 等接口；Lab 使用 MemoryPreference 实现，无持久磁盘写入。 |
| `presentation/category/components/CategoryListItem.kt` | reorderable receiver 的 draggableHandle；不能在任意布局作用域中复制此 Modifier。 |
| `presentation/category/components/CategoryFloatingActionButton.kt` | Material3 SmallExtendedFloatingActionButton 与 shouldExpandFAB；不要假定使用另一套 FAB API。 |
| `gradle/libs.versions.toml` | 已有 compose-bom-alpha、AndroidX JUnit/Espresso 版本；测试依赖应沿用该版本目录。 |

## 样例结构

`app/src/debug/java/eu/kanade/tachiyomi/uicatalog/`：Activity、索引、六组渲染函数、假状态 reducer、内存 Preference。`src/debug/res`：英文及简体中文资源。`src/androidTestDebug`：仅 debug 组合源集的 10 个仪器测试。

`AppBar`、`SearchToolbar`、项目 `Scaffold`、`PreferenceScreen`、`AdaptiveSheet` 等通过 import 直接使用宿主实现。新样例不是原版全部生产页面的复制品。真实页面导航、数据库、账号、文件和任务启动仍需对应生产集成用例。

## 移植与差异处理

导入工具预检目标文件、namespace、图标 API 和版本目录。精确 HEAD 不符时默认停止；`--allow-different-base` 仅表示你已审查差异，不能保证 API 兼容。新版上游使用 MaterialSymbols 等不同接口时，应在独立分支适配 import 与参数，保持行为契约不变。

本次没有成功取得完整仓库，也没有 Android 编译器依赖和 SDK，因此仍存在编译/API/格式风险。应用现有 Spotless/ktlint 后再编译；格式检查失败不能通过关闭质量工具规避。

注意：本包没有新增 main 业务源文件，但会给 app/build.gradle.kts 追加测试依赖，并合并 debug manifest。该 fork 的 preview/benchmark 会复用 debug 资源目录；代码和 Activity 仍应按真实合并结果核查，不能仅凭目录名称宣布发布包无实验入口。测试 Activity 具有仅 debug 的第二个桌面入口，exported=true 以便桌面启动，不解析外部 Intent 数据。

虽然样例操作不调用真实服务，启动 Activity 仍会运行宿主 Application 初始化。因此请在新建、无真实账号及书库的 debug 安装/模拟器中验证，不使用真实数据环境做破坏性测试。

## Fork 源码链接

- [app/build.gradle.kts](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/app/build.gradle.kts)
- [app/src/main/java/eu/kanade/presentation/components/AppBar.kt](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/app/src/main/java/eu/kanade/presentation/components/AppBar.kt)
- [app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScaffold.kt](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScaffold.kt)
- [app/src/main/java/eu/kanade/presentation/more/settings/Preference.kt](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/app/src/main/java/eu/kanade/presentation/more/settings/Preference.kt)
- [app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt)
- [core/common/src/main/kotlin/tachiyomi/core/common/preference/Preference.kt](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/core/common/src/main/kotlin/tachiyomi/core/common/preference/Preference.kt)
- [app/src/main/java/eu/kanade/presentation/category/components/CategoryListItem.kt](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/app/src/main/java/eu/kanade/presentation/category/components/CategoryListItem.kt)
- [app/src/main/java/eu/kanade/presentation/category/components/CategoryFloatingActionButton.kt](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/app/src/main/java/eu/kanade/presentation/category/components/CategoryFloatingActionButton.kt)
- [gradle/libs.versions.toml](https://github.com/AltairCardinal/mihon/blob/6fbf6dfca203d99d6dd32137f2df97ced40c81b8/gradle/libs.versions.toml)
