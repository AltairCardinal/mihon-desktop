# Mihon Desktop 原版实现对齐重构设计

> 日期：2026-07-12
> 上游差异基线：`docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md`
> 覆盖范围：基线中标记为“原版更优”的 64 项。
> 核心约束：原版能力完全对齐，Desktop 独有产品能力零回退。

## 1. 背景与目标

Mihon Desktop 基于原版 Mihon fork 而来。早期为了快速形成可用产品，部分本可复用或下沉为共享代码的能力在 `app-desktop` 中被重新实现。它们虽然达到了阶段性功能目标，却形成了以下长期技术债：

- Android 与 Desktop 存在两套业务规则、状态机、错误处理或数据格式；
- 原版修复和增强无法自然同步到 Desktop；
- Desktop 的简化实现覆盖不足，且容易与原版行为继续漂移；
- 平台适配、产品增强和临时兼容逻辑混在同一层，难以判断哪些代码应永久保留；
- 同名 `.tachibk`、偏好或队列状态可能具有不同语义，增加数据迁移风险。

本次重构不是简单补齐 UI，也不是把 Android API 机械移植到 JVM。目标是建立一个以原版实现为权威基线的共享架构：非平台特有的行为只保留一份共享实现；Android 与 Desktop 各自只承担必要的平台适配；Desktop 的独有产品能力以明确的增量层继续存在。

## 2. 非目标

- 不删除或弱化 Desktop 的作者聚合、作品比较、即将更新、键鼠操作、双页增强、自动滚动、文件系统工作流、FlareSolverr、测试控制面或宽屏布局。
- 不在 Desktop 上伪造不可可靠实现的 Android 能力，例如 Android Widget 或 `FLAG_SECURE`。
- 不为了代码外观一致而强迫 Desktop 使用 WorkManager、Android Intent、PackageInstaller 等不可用技术。
- 不在单次变更中同时迁移全部 64 项。
- 不借重构进行无关 UI 改版、依赖升级或数据库清理。

## 3. 对齐原则

### 3.1 权威实现顺序

每项差异必须按以下顺序决策：

1. **直接复用**：原版实现已位于 `commonMain` 或不依赖 Android，Desktop 直接接入，不再保留第二份业务实现。
2. **共享抽取**：原版逻辑混在 Android 层，但核心规则与平台无关，将其提取到 `domain/commonMain`、`data/commonMain`、`core/common` 或合适的共享模块。
3. **共享接口 + 平台适配**：能力确实依赖操作系统时，共享行为契约、状态、错误和测试向原版对齐，两端分别实现薄适配器。
4. **平台豁免**：Desktop 没有可靠等价机制时，不提供误导性的半实现；在 UI、文档和验收中明确能力边界。

禁止把“Desktop 当前已经能用”当作保留重复实现的理由。

### 3.2 完全对齐的定义

一个条目只有同时满足以下条件才算完成：

- 对相同业务输入，共享规则与原版产生相同结果；
- 成功、空数据、加载、取消、权限/数据缺失和错误反馈语义一致；
- 原版已有的数据格式、字段语义和兼容策略得到复用；
- 原版已有测试被共享复用，或在 Desktop/JVM 上有等价测试；
- Desktop 独有产品能力的特征测试保持通过；
- Desktop UI 有完整入口和反馈闭环；
- 旧 Desktop 重写路径被删除，或被明确登记为有技术原因的平台适配；
- 不要求用户清空数据库、偏好、下载或扩展目录才能升级。

## 4. 目标架构

```text
共享行为基线
domain/commonMain + data/commonMain + core/common + source-api/commonMain
                │
        ┌───────┴────────┐
        │                │
Android 薄适配层     Desktop 薄适配层
Activity/WorkManager  Window/OS scheduler
Intent/Package API    URI/File/ClassLoader API
        │                │
Android 产品 UI       Desktop 产品 UI
                         │
                 Desktop 独有增强层
        作者/作品、宽屏、键鼠、自动化、FlareSolverr 等
```

共享层负责：业务模型、用例、状态机、队列规则、序列化格式、错误分类、迁移规则和可跨平台测试的逻辑。

平台适配层负责：文件选择、通知投递、后台唤醒、凭据安全存储、URI 注册、窗口控制、系统安装和浏览器集成。

Desktop 独有增强层只能依赖共享契约，不得复制共享业务规则。增强关闭或不可用时，基础行为必须退化为原版语义，而不是进入另一套 Desktop 语义。

## 5. 迁移标签

每项差异使用以下标签标明最终归属：

| 标签 | 含义 | 处置 |
|---|---|---|
| `SHARE-DIRECT` | 已有共享实现可直接复用 | 改 Desktop wiring，删除重复实现 |
| `SHARE-EXTRACT` | 原版逻辑可跨平台但仍在 Android 层 | 先从原版提取共享核心，再接入两端 |
| `PLATFORM-ADAPTER` | 用户语义可对齐，实现必须依赖平台 | 共享接口与状态，保留薄平台 adapter |
| `DESKTOP-PRODUCT` | Desktop 永久产品增强 | 保留并接到共享基线之后 |
| `TEMP-COMPAT` | 为早期移植产生的临时兼容实现 | 双轨验证后删除 |
| `PLATFORM-EXEMPT` | Desktop 无可靠等价能力 | 明确边界，不伪造完全实现 |

## 6. Desktop 独有能力保护清单

以下能力属于永久产品增强，任何迁移任务都必须将相关回归测试列为删除旧路径前的门禁：

| 能力 | 归属边界 | 不允许的回退 |
|---|---|---|
| 作者聚合与作品版本比较 | Desktop UI + 共享作者/作品领域服务 | 作者 Tab、详情、归并、比较入口消失或结果变化 |
| 即将更新预测 | Desktop 产品用例，依赖共享更新数据 | Upcoming 页面消失或预测数据无法读取 |
| 键盘、鼠标、滚轮、右键菜单 | Desktop 阅读器输入 adapter | 快捷键、滚轮翻页、保存页面菜单失效 |
| 大屏双页与边缘匹配 | Desktop viewer 增强，依赖共享页面模型 | 双页、自动拆页或 spread matching 被原版基础 viewer 覆盖 |
| Webtoon 自动滚动 | Desktop viewer 增强 | 自动滚动开关、速度档位或停止行为丢失 |
| 普通文件系统与 CBZ 工作流 | Desktop storage adapter | 无法选择、打开目录或生成可外部读取的 CBZ |
| FlareSolverr 可选路径 | Desktop challenge adapter 的后备实现 | 原版式浏览器挑战接入后强制删除 FlareSolverr |
| Headless/Test Mode HTTP 控制面 | Desktop 测试基础设施 | 导航、动作、状态、截图 API 失效 |
| 自由窗口与宽屏布局 | Desktop UI 层 | 窗口缩放后布局退化为移动端固定宽度 |
| APK→JAR 能力 | Desktop 扩展平台 adapter 的兼容后备 | 新扩展架构接入时失去转换安装能力 |

## 7. 按功能关联性重组的 64 项差异

### A. 共享架构、状态与模块边界（7 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 3 | 状态管理 | `SHARE-EXTRACT` | 统一 ScreenModel、StateFlow、事件和生命周期语义 | 窗口生命周期由 Desktop adapter 提供 |
| 4 | 依赖注入 | `SHARE-EXTRACT` | 按领域拆分注册，复用共享 binding factory | Compose Local 仅保留 UI 依赖入口 |
| 7 | 偏好存储 | `SHARE-EXTRACT` | 统一 PreferenceStore key、默认值、迁移和测试 | `java.util.prefs` 只能作为 backend adapter |
| 12 | 崩溃与诊断 | `PLATFORM-ADAPTER` | 共享诊断模型、日志导出内容和错误分类 | 崩溃窗口/文件位置为桌面实现 |
| 93 | 高级维护 | `SHARE-EXTRACT` | 复用清理用例、结果模型和安全边界 | 打开目录是 Desktop 增强 |
| 95 | 代码模块边界 | `SHARE-EXTRACT` | Desktop 特有业务从 app UI 中移出，形成编译期依赖方向 | 不为拆模块而复制 common 能力 |
| 96 | 平台兼容层成本 | `PLATFORM-ADAPTER`、`TEMP-COMPAT` | compat stub 收敛到扩展 adapter，建立使用清单和契约测试 | 仍需保留实际被 APK 扩展调用的最小 stub |

该组是其他项目群的前置条件。完成后，Desktop 不应再直接以 Composable 状态、全局单例或 `java.util.prefs` 表达共享业务状态。

### B. 网络、后台执行与通知基础设施（4 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 8 | 网络栈 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 复用 OkHttp 配置、Cookie 语义、错误分类、重试策略 | 系统代理和浏览器 Cookie 导入为 adapter |
| 10 | 后台任务 | `PLATFORM-ADAPTER` | 共享任务定义、约束模型、幂等和恢复状态 | Desktop 使用 OS scheduler/启动恢复，不复制 WorkManager API |
| 11 | 通知 | `PLATFORM-ADAPTER` | 共享通知事件与进度模型 | 投递到 Windows/macOS/Linux 通知或应用内后备 |
| 61 | 书库更新 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 复用更新策略、分类过滤、失败汇总和持久任务状态 | 调度唤醒由 Desktop adapter 实现 |

### C. 书库与漫画详情（7 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 16 | 分类 | `SHARE-DIRECT`、`SHARE-EXTRACT` | 复用分类用例、排序规则和校验 | Desktop 可保留对话框形式 |
| 17 | 书库筛选 | `SHARE-EXTRACT` | 统一 LibraryFlags、组合规则和持久化 | 宽屏筛选 UI 保留 |
| 19 | 书库多选批处理 | `SHARE-EXTRACT` | 共享批量动作、部分失败和撤销语义 | 键鼠多选方式保留 |
| 22 | 收藏与分类联动 | `SHARE-DIRECT` | 接入原版收藏/分类用例和清理策略 | Desktop 详情布局保留 |
| 24 | 章节批量操作 | `SHARE-EXTRACT` | 共享范围选择、下载/书签/已读动作和错误结果 | Shift/鼠标选择为 Desktop 增强 |
| 26 | 封面管理 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 共享封面用例、缓存失效和错误模型 | 文件选择、剪贴板/系统分享为 adapter |
| 66 | 统计 | `SHARE-DIRECT`、`SHARE-EXTRACT` | 复用统计聚合、维度和筛选模型 | Desktop 图表布局可独立 |

### D. 源浏览、扩展与挑战处理（13 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 28 | 源列表 | `SHARE-EXTRACT` | 共享启用、语言、固定、排序和筛选规则 | Desktop 列表布局保留 |
| 29 | 单源浏览 | `SHARE-DIRECT`、`SHARE-EXTRACT` | 统一 paging、重试、空数据和 FilterList 状态 | 扩展调用由 Desktop loader adapter 提供 |
| 30 | 全局搜索 | `SHARE-DIRECT` | 复用并发搜索服务、结果状态和取消语义 | 宽屏结果布局保留 |
| 32 | 扩展仓库 | `SHARE-DIRECT` | 完整复用 extensionrepo domain、验证和错误模型 | 文件缓存位置为 Desktop adapter |
| 33 | 扩展发现 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 共享 index/installed model、版本比较和状态机 | PackageManager 与目录扫描分别实现 |
| 34 | 扩展安装 | `PLATFORM-ADAPTER`、`DESKTOP-PRODUCT` | 共享安装状态、验证、替换和回滚协议 | JAR/APK→JAR 为 Desktop 安装 adapter |
| 35 | 扩展加载 | `PLATFORM-ADAPTER`、`TEMP-COMPAT` | 共享加载结果、source discovery 与失败隔离契约 | URLClassLoader/字节码修补保留在 adapter 内 |
| 36 | 扩展安全 | `PLATFORM-ADAPTER` | 对齐签名/哈希信任模型、仓库信任和用户确认 | JVM 无 Android 包隔离，需进程隔离或明确风险 |
| 37 | 扩展详情与更新 | `SHARE-EXTRACT` | 共享详情模型、版本状态、更新事务和卸载语义 | Desktop 图标/文件入口保留 |
| 38 | 源偏好设置 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 共享 preference schema 和控件语义 | Desktop renderer 映射为 Compose 控件 |
| 39 | WebView/源登录 | `PLATFORM-ADAPTER` | 共享登录会话、Cookie 交换和完成/取消状态 | 使用桌面嵌入浏览器或系统浏览器回调 |
| 40 | Cloudflare 绕过 | `PLATFORM-ADAPTER`、`DESKTOP-PRODUCT` | 复用挑战检测、Cookie 验证和重试语义 | Desktop 浏览器 adapter + FlareSolverr 后备 |
| 87 | 国际化 | `SHARE-DIRECT` | Desktop UI 全部改用 i18n/Moko 资源和共享文案 key | 桌面专属文案新增资源，不硬编码英文 |

### E. 阅读器核心（8 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 9 | 图片加载与解码 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 共享请求、缓存 key、错误、取消和内存策略 | Skia/区域解码为 Desktop decoder adapter |
| 43 | 宽页拆分 | `SHARE-EXTRACT`、`DESKTOP-PRODUCT` | 共享拆分、反转、旋转和虚拟页算法 | Desktop edge matching 作为配对增强 |
| 44 | 大图与缩放 | `PLATFORM-ADAPTER` | 对齐区域解码、采样、内存上限和缩放状态契约 | 使用 Skia codec/tiles，不照搬 Android View |
| 45 | 图片预加载 | `SHARE-EXTRACT` | 共享窗口算法、取消、优先级和缓存驱逐 | 实际 decode dispatcher 为 Desktop adapter |
| 47 | 页面过渡 | `SHARE-EXTRACT` | 共享章节边界、缺章、加载、错误和重试状态 | Desktop viewer 渲染独立 |
| 49 | 点击区域方案 | `SHARE-DIRECT`、`DESKTOP-PRODUCT` | 复用导航区域模型和反转规则 | 键鼠映射叠加在共享命令之后 |
| 51 | 色彩处理 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 共享滤镜参数、模式、灰度和反色语义 | Skia shader/Compose effect 实现 |
| 54 | 跳过规则 | `SHARE-DIRECT` | 复用跳过已读、过滤、重复章节用例 | 键盘上下章操作调用同一用例 |

阅读器迁移必须保留双页、自动拆页、边缘匹配、自动滚动、键鼠和右键菜单。原版 Viewer 的 Android View 实现不是复用目标；应复用的是页面模型、算法、状态机和测试向量。

### F. 下载、更新与历史（6 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 56 | 下载队列 | `SHARE-EXTRACT` | 共享持久队列、状态机、恢复、取消和重试 | 文件写入与通知由 adapter 承担 |
| 57 | 下载并发 | `SHARE-EXTRACT` | 共享按源公平性、并发限制和退避策略 | Desktop 可提供不同默认并发值 |
| 59 | 自动下载 | `SHARE-DIRECT`、`PLATFORM-ADAPTER` | 复用规则、分类和数量限制 | 触发调度由 Desktop 后台 adapter 提供 |
| 62 | 更新列表 | `SHARE-DIRECT` | 复用更新状态、多选动作和下载联动 | Upcoming 增强继续读取共享更新数据 |
| 64 | 历史记录 | `SHARE-DIRECT` | 复用搜索、删除、继续阅读和清空用例 | Desktop History Tab 布局保留 |
| 53 | 章节进度 | `SHARE-DIRECT` | 复用阅读进度事务、history 和 tracker 事件 | Desktop viewer 只上报共享阅读事件 |

### G. 迁移与追踪（4 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 67 | 单部漫画迁移 | `SHARE-DIRECT`、`SHARE-EXTRACT` | 复用迁移选项、章节匹配、状态复制和结果模型 | Desktop 搜索/比较 UI 保留 |
| 68 | 批量迁移 | `SHARE-EXTRACT` | 共享批处理编排、恢复点、逐项失败和取消 | Desktop 可采用宽屏队列 UI |
| 69 | 追踪服务 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 复用 tracker manager、API、绑定和状态模型 | OAuth 回调与凭据存储为 Desktop adapter |
| 70 | 自动追踪更新 | `SHARE-DIRECT` | ReaderProgress 触发共享 tracker 更新策略 | Desktop 通知和失败重试走平台 adapter |

### H. 备份、恢复与数据兼容（4 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 71 | 手动备份 | `SHARE-EXTRACT` | 直接采用原版 protobuf schema、选项和 creator pipeline | Desktop 文件选择为 adapter |
| 72 | 备份恢复 | `SHARE-EXTRACT` | 复用 validator、预览、逐项恢复和结果模型 | Desktop 进度窗口/通知独立 |
| 73 | 自动备份 | `PLATFORM-ADAPTER` | 共享备份任务、保留策略和幂等 | Desktop scheduler 负责唤醒 |
| 74 | 跨端备份兼容 | `SHARE-DIRECT`、`TEMP-COMPAT` | `.tachibk` 只保留原版 protobuf 格式；读取旧 Desktop 格式后转换 | 旧 Desktop writer 在迁移窗口结束后删除 |

备份格式迁移必须先实现双读单写：能读取原版 protobuf 与旧 Desktop 格式，但只写原版 protobuf。至少跨两个 Desktop 正式版本保留旧格式读取器；是否删除读取器需依据遥测不可用情况下的发布周期和用户反馈单独决策。

### I. 系统集成、隐私与发布（7 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 81 | Deep link | `PLATFORM-ADAPTER` | 共享 URI parser、路由结果和错误页面 | Windows/macOS/Linux 分别注册 scheme |
| 82 | 分享/系统 Intent | `PLATFORM-ADAPTER` | 共享分享 payload 与动作 | Desktop 使用剪贴板、系统 share API 或浏览器 |
| 83 | 安全锁 | `PLATFORM-ADAPTER` | 共享锁定策略、超时和隐私状态 | 凭据使用 OS keystore；无生物识别时提供可靠替代 |
| 84 | 屏幕与截图安全 | `PLATFORM-ADAPTER`、`PLATFORM-EXEMPT` | 共享隐私开关和敏感页面标记 | 支持的平台启用窗口保护，不支持时明确提示 |
| 85 | Widget | `PLATFORM-EXEMPT` | 保留更新数据 provider 契约，不承诺跨桌面统一 Widget | 不创建不可维护的三平台 Widget 套件 |
| 86 | 应用更新 | `PLATFORM-ADAPTER` | 共享版本比较、release 校验、下载状态和回滚语义 | 安装包类型与签名验证按 OS 实现 |
| 92 | 安全设置 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 复用安全/隐私设置模型和说明 | 只展示当前 OS 可可靠支持的选项 |

### J. 设置、外观、无障碍与合规（4 项）

| 原编号 | 条目 | 主要标签 | 对齐方向 | Desktop 边界 |
|---:|---|---|---|---|
| 88 | 无障碍 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 共享语义标签、焦点顺序规范和可访问文案 | Desktop 增加键盘焦点与屏幕阅读器测试 |
| 90 | 设置搜索 | `SHARE-EXTRACT` | 共享 searchable preference model 和索引 | Desktop 搜索结果导航使用 Voyager adapter |
| 91 | 外观 | `SHARE-EXTRACT`、`PLATFORM-ADAPTER` | 共享主题模型、颜色语义和设置 key | 动态色仅在有可靠系统 API 时开放 |
| 94 | 开源许可与构建信息 | `SHARE-DIRECT`、`PLATFORM-ADAPTER` | 复用依赖许可数据模型和 release 信息 | Desktop 打包阶段生成自身依赖清单 |

## 8. 复用判断

### 8.1 能否直接复用现有功能

可以。分类、收藏、历史、更新、自动下载规则、迁移的部分用例、追踪领域模型、extension repo、数据库 schema 等已经位于共享模块或可直接被 JVM 编译。计划应优先改 Desktop wiring，不重写第三套实现。

### 8.2 何时抽取公共能力

原版实现如果只因构造函数持有 Android Context、通知器、文件 URI 或 WorkManager 而留在 `app/`，应先分离纯规则与 side effect。纯规则进入共享层，side effect 通过接口注入。不能把整个 Android 类复制到 `app-desktop` 后删除 import 来宣称对齐。

### 8.3 新能力追加到哪里

Desktop 独有能力默认追加到共享基线之后：

- viewer 增强消费共享 `ReaderPage`/导航命令；
- Upcoming 消费共享 updates 数据；
- 作者/作品功能消费共享 manga/source 数据；
- FlareSolverr 实现共享 challenge adapter 的后备策略；
- test mode 观察真实 ScreenModel/任务状态，不维护测试专用业务状态副本。

### 8.4 必须独立实现的技术原因

仅以下类别允许独立：操作系统通知与 scheduler、窗口与截图 API、文件选择、URI scheme 注册、OAuth 回调、凭据安全存储、安装包/扩展 class loading、图片原生解码和桌面输入。独立实现仍必须遵守共享接口、状态、错误和测试契约。

## 9. 统一数据流与错误模型

每条功能链采用相同分层：

```text
UI Intent
  → shared use case / state machine
  → shared repository contract
  → common data implementation 或 platform adapter
  → typed result/error
  → ScreenModel state/event
  → UI loading/empty/content/error feedback
```

禁止从 Composable 直接调用下载 manager、数据库 query、HTTP client 或扩展 ClassLoader。Desktop 产品增强同样必须通过 use case 或明确的 UI adapter 接入。

共享错误至少区分：网络不可用、认证/挑战、权限、源不兼容、数据畸形、磁盘空间、取消、部分成功和未知错误。平台 adapter 将系统异常映射为这些错误；UI 不解析异常字符串决定行为。

## 10. 用户体验要求

每个项目群的实施规格必须明确：

- **入口**：从哪个 Tab、菜单、详情动作或设置进入；
- **内容**：成功时展示的字段、排序和可执行动作；
- **加载**：首次加载、分页、后台刷新和长任务进度；
- **空状态**：无数据、无源、无权限、无 tracker 或无兼容扩展；
- **错误**：可重试与不可重试错误、部分成功和恢复建议；
- **反馈**：Snackbar、对话框、系统通知或任务结果页；
- **危险操作**：删除、覆盖、清空、迁移和格式转换必须确认；
- **边界**：平台豁免、扩展不兼容和后台能力限制必须可见。

## 11. 迁移流程与门禁

每项任务严格执行以下顺序：

1. **原版契约测试**：提取或新增能证明原版行为的测试向量。
2. **Desktop 特征测试**：固定需要保留的 Desktop 产品行为与现有数据读取能力。
3. **红**：为共享契约或 Desktop wiring 写失败测试，确认失败原因是尚未对齐。
4. **共享最小实现**：移动或提取原版逻辑，不增加未要求的新行为。
5. **绿**：Android 原测试、common/JVM 测试和 Desktop 集成测试全部通过。
6. **双轨比较**：对相同 fixture 同时运行旧 Desktop 路径与共享路径，记录差异并修正。
7. **UI/DI 切换**：新增导航、DI、HTTP、数据库或后台接点时补对应 wiring 测试。
8. **数据迁移**：验证旧偏好、数据库、队列、备份和扩展元数据可无损读取。
9. **Desktop 增强回归**：运行本设计第 6 节对应测试。
10. **删除旧路径**：只有前述门禁全部通过后才删除 `TEMP-COMPAT` 或重复实现。
11. **重构与全量验证**：Spotless、单元、集成、Desktop JVM/E2E。
12. **桌面构建验收**：使用 `scripts/build-desktop.sh`，启动固定路径未打包 EXE，并核对完整版本号。

如果新路径出现数据不兼容、Desktop 独有能力回退或无法建立等价集成测试，必须保留旧路径并停止该项切换，不能以“后续修复”完成迁移。

## 12. 项目群依赖与实施阶段

```text
阶段 0：契约与保护网
  Desktop 独有能力特征测试、共享错误模型、对齐矩阵
        │
阶段 1：共享基础
  A 架构/状态/偏好/模块边界
  B 网络/任务/通知契约
        │
阶段 2：数据安全优先
  H 备份格式与双读单写
  F 下载队列持久化与进度事务
        │
阶段 3：核心漫画闭环
  C 书库/详情
  F 更新/历史
  G 迁移
        │
阶段 4：高复杂度运行时
  E 阅读器
  D 源/扩展/挑战
  G 追踪
        │
阶段 5：平台完整性
  I 系统集成/隐私/更新
  J 设置/国际化/无障碍/许可
        │
阶段 6：删除兼容债务
  删除已替代重复实现、收紧模块依赖、更新维护文档
```

备份先于大规模状态迁移，是为了保证用户在后续阶段始终有可恢复路径。扩展与阅读器不放在最前，是因为它们依赖共享错误、偏好、状态和任务基础。

## 13. 测试策略

### 13.1 共享契约测试

- common test fixtures 必须同时被 Android 和 JVM target 执行；
- 状态机用确定性时钟和调度器；
- 序列化使用原版真实 fixture，禁止只测试自造简化对象；
- 源解析使用 MockWebServer，覆盖成功、空数据、403、429、500 和畸形响应；
- 扩展安装覆盖签名/哈希失败、替换回滚、损坏产物和不兼容 API；
- 下载和任务覆盖进程重启后的恢复语义。

### 13.2 UI wiring 测试

- 每个新增或修改 Screen/Tab 有实例化和导航类型测试；
- 每个新 `navigator.push()` 有上下文兼容测试；
- 每个新 DI binding 或 `Injekt.get()` 有解析测试；
- 每个后台任务有注册、恢复和取消集成测试；
- 每个危险操作有确认与取消路径测试。

### 13.3 Desktop 独有回归

- `app-desktop:jvmTest` 锁定产品增强逻辑；
- `test-desktop`/test mode 验证真实入口、反馈和窗口行为；
- 阅读器至少覆盖键盘、滚轮、双页、拆页、自动滚动和保存；
- 扩展至少覆盖预编译 JAR 与 APK→JAR 两条路径；
- Windows 验收使用固定未打包 EXE，不能用 MSI 替代。

## 14. 发布与回退策略

- 一次发布只切换一个可独立验收的能力链；
- 数据格式采用先读兼容、后写统一、最后删除旧 writer 的顺序；
- 对队列、备份、扩展元数据等高风险状态保留版本字段；
- 新共享路径在删除旧实现前必须经过至少一次完整 Desktop 构建验收；
- 回退必须能继续读取新版本写出的数据；若做不到，禁止发布格式切换；
- 不使用远程 feature flag 掩盖未完成的双实现，必要的本地迁移开关必须有删除版本和测试。

## 15. 文档产物与后续计划拆分

本设计通过后，实施计划拆分为：

1. 总路线图：项目群依赖、里程碑、统一门禁、完成度追踪；
2. 共享架构与偏好计划；
3. 网络、后台任务与通知计划；
4. 备份格式与跨端兼容计划；
5. 下载、更新、历史计划；
6. 书库与漫画详情计划；
7. 阅读器核心计划；
8. 源、扩展与挑战处理计划；
9. 迁移与追踪计划；
10. 系统集成、安全与应用更新计划；
11. 设置、国际化、无障碍与合规计划；
12. 重复实现删除与模块边界收尾计划。

每份实施计划必须包含确切文件、接口、TDD 红绿步骤、验证命令、Desktop 独有能力回归项和用户可执行验收清单。任何项目群均应能够独立交付，不依赖一次性完成全部 64 项。

## 16. 设计完成判据

整个重构完成时应满足：

- 64 个“原版更优”条目全部变为“已对齐”或有证据的 `PLATFORM-EXEMPT`；
- 非平台必需的 Desktop 重复业务实现归零；
- 原版与 Desktop 共用业务规则、数据格式、错误和契约测试；
- Desktop 独有产品能力保护清单全部通过；
- `.tachibk` 与其他共享数据可跨端交换；
- Desktop UI 不再大量硬编码英文；
- 所有导航、DI、HTTP、数据库和后台任务集成点有能在 wiring 损坏时失败的测试；
- 文档能说明每个剩余 Desktop adapter 的技术必要性、用户价值和失败边界。
