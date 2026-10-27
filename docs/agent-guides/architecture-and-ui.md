# 架构、复用与界面规划

本页是[根规则](../../AGENTS.md)按动作加载的项目约束。仅阅读本次动作适用章节；代码命令与普通路径以仓库根为基准，Markdown 链接按本页位置解析。

规划、实现或审查 capability、共享逻辑、数据或 UI 前使用；Desktop UI 先遵循下文链接的既有实施规范，不另建规范体系。

## 功能规划原则

**规划任何用户可见 capability 时都必须同时考虑用户界面。**

### Desktop UI 规范入口

- 新增或修改 Desktop 页面、导航、弹层、列表交互，或对应 HTML 审阅原型时，先读 [Desktop UI 实施规范](../../docs/design/mihon-desktop-ui/README.md)，再读适用的组件事实表和页面契约；其他页面按同样字段定义本次契约，不机械套用三个样本的业务。
- 实现前固定验收，区分 `SOURCE`、`PROJECT_POLICY` 与 `HTML_ADAPTER`。平台常量按实际消费组件核对，不把 Android 值自动作为 Desktop 值；当前 Desktop 封面 7:10 与 Android Book 2:3 即为已知差异。
- 必做验收不得在实现后改为未实现或不适用以获得通过。行为测试执行真实事件及 wiring，视觉检查记录来源和环境；浏览器原型通过不能代替[原生测试](desktop-acceptance.md)及[正式构建验收](desktop-build.md)。

每项用户可见 capability 必须有入口和反馈；内部基础设施不要求独立 UI，但必须被真实产品链路使用并有集成测试。规划时检查：

1. 用户如何触发？（按钮、菜单、快捷键等）
2. 结果如何反馈？（状态、Toast、对话框等）
3. 危险操作是否需要确认？（AlertDialog）

**用户可见 capability 没有入口或反馈 = 功能未完成；内部基础设施没有 production wiring 或集成测试 = 功能未完成。**

### 复用优先

新增功能前必须检查项目内是否已有相同或相近能力可复用，包括：

- 已有 Use Case、Manager、Repository、Service
- 已有搜索、分页、错误处理、缓存、同步、下载、解析流程
- 已有 Screen、Tab、Composable、导航入口
- 已有数据模型、数据库表、查询、状态管理、测试工具

规划时必须回答：

1. 能否直接复用现有功能？
2. 不能直接复用时，是否应抽取公共能力供新旧功能共用？
3. 新特性应追加到已有链路，还是确实需要独立维护？
4. 若独立实现，必须说明不能复用的技术原因和用户体验原因。

**能复用却另起一套实现，默认不允许。**

上游对齐与共享契约的常驻约束见[根规则](../../AGENTS.md#上游对齐原则)，本页补充规划及架构事实。

## 架构

Mihon 是由 Android 应用、Mihon Desktop 和共享 Kotlin 模块组成的多平台代码库，采用分层架构：

| 模块 | 职责 |
|---|---|
| `app/` | 表现层：Compose 页面、Activity、DI wiring |
| `app-desktop/` | Desktop 表现层、平台 adapter、运行时 wiring 与桌面端测试 |
| `test-desktop/` | Desktop E2E 测试客户端与 Robot API |
| `domain/` | 业务逻辑：用例、领域模型、仓库接口 |
| `data/` | 数据层：SQLDelight 数据库、仓库实现、映射 |
| `presentation-core/` | 跨页面复用的 Compose 组件 |
| `core/common/` | 公共工具与 Kotlin 扩展 |
| `source-api/` | KMP 漫画源抽象，供扩展复用 |
| `source-local/` | 本地文件源 |
| `i18n/` | Moko 字符串资源 |

### 包名

因 Tachiyomi → Mihon 迁移历史，仓库存在多个包名前缀：

- `eu.kanade.tachiyomi.*`：app 模块和多数旧代码
- `tachiyomi.domain.*` / `tachiyomi.data.*`：domain 与 data 模块
- `mihon.domain.*` / `mihon.feature.*`：较新的 Mihon 功能
- `mihon.desktop.*`：Mihon Desktop 的 UI、平台 adapter、运行时与独有能力

### 关键模式

- **依赖注入**：Android 与 Desktop 均使用 Injekt。Android 模块注册在 `app/src/main/java/eu/kanade/tachiyomi/di/`；Desktop wiring 位于 `app-desktop/src/main/kotlin/mihon/desktop/di/` 和 `DesktopUiDependencies.kt`。使用 `Injekt.get<T>()` 获取依赖，使用 `by injectLazy<T>()` 延迟注入。
- **导航**：Android 与 Desktop 均使用 Voyager（`cafe.adriel.voyager`）。Screen 实现 `cafe.adriel.voyager.core.screen.Screen`，导航通过 `Navigator` / `LocalNavigator`。
- **数据库**：SQLDelight + 协程。schema 与 migration 的唯一权威位于 `data/src/commonMain/sqldelight/`，生成查询在 `tachiyomi.data.*.db`；不得重建 `data/src/main/sqldelight/` 镜像。
- **图片加载**：Coil 3，自定义 fetcher / decoder 位于 `app/src/main/java/eu/kanade/tachiyomi/data/coil/`。
- **偏好设置**：`tachiyomi.core.common.preference` 封装 AndroidX DataStore / SharedPreferences。

### 构建逻辑

自定义 Gradle 插件位于 `buildSrc/src/main/kotlin/`：

- `mihon.android.application`：应用基础配置
- `mihon.library`：库模块配置
- `mihon.code.lint`：Spotless + ktlint

依赖版本由 `gradle/*.versions.toml` 管理。
