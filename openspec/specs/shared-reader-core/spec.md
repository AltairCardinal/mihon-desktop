# shared-reader-core Specification

## Purpose
TBD - created by archiving change align-reader-core. Update Purpose after archive.
## Requirements
### Requirement: Android and Desktop share reader semantics
系统 SHALL 在 common 层定义页面、章节、过渡、拆页、配对、导航、跳过和滤镜语义，并让 Android 与 Desktop 生产阅读器共同消费这些语义。

#### Scenario: Same chapter fixture on both platforms
- **WHEN** Android 与 Desktop 读取相同章节、页面尺寸、方向和偏好 fixture
- **THEN** 两端产生相同的逻辑页序、章节边界、跳过结果和错误分类

#### Scenario: Platform renderer remains isolated
- **WHEN** 两端渲染共享页面状态
- **THEN** Android Bitmap/View 与 Desktop Skia/Compose 仅存在于各自 adapter，common API 不暴露平台类型

### Requirement: Wide pages and pairing follow the authoritative contract
系统 SHALL 以 Android 原版行为作为宽页拆分、旋转、方向反转、双页配对和未知尺寸处理的默认权威契约。

#### Scenario: Wide page is split in reading order
- **WHEN** 宽页在 LTR、RTL 或旋转模式下需要拆分
- **THEN** 虚拟页边界覆盖全部像素、顺序符合阅读方向且能映射回原始页

#### Scenario: Desktop product pairing is enabled
- **WHEN** Desktop 启用封面单页、edge matching 或 landscape parity 增强
- **THEN** 增强仅通过显式选项改变 Desktop 布局，Android 默认配对结果不变

### Requirement: Reader transitions and navigation are explicit
系统 SHALL 表达 Wait、Loading、Loaded、Error、缺章、上一章/下一章边界与 Retry 命令，并统一点击区和方向反转语义。

#### Scenario: Chapter load fails
- **WHEN** 当前或相邻章节加载失败
- **THEN** 阅读器显示可见错误与重试操作，重试重新发起同一共享命令

#### Scenario: No adjacent chapter exists
- **WHEN** 用户在章节边界继续导航且没有符合跳过规则的目标
- **THEN** 系统返回明确 Boundary 结果并显示边界反馈，不越界或打开错误章节

### Requirement: Chapter skipping uses one shared policy
系统 SHALL 使用可组合的 read、filtered、duplicate 策略寻找相邻章节，Android 与 Desktop 不得保留独立判定分支。

#### Scenario: Multiple skip reasons apply
- **WHEN** 相邻章节分别已读、被过滤或为重复章节
- **THEN** 系统跳过所有命中策略的章节并返回最近的有效目标或 Boundary

### Requirement: Image preloading is cancellable and memory bounded
系统 SHALL 共享预加载窗口、优先级、取消、代次和淘汰契约，并通过平台 decoder/cache adapter 限制缓存字节和图片尺寸。

#### Scenario: User changes pages quickly
- **WHEN** 新预加载代次在旧请求完成前开始
- **THEN** 旧请求被取消或拒绝回填，旧窗口的全部页面被淘汰

#### Scenario: Oversized image is loaded
- **WHEN** 页面尺寸或字节量超过缓存预算
- **THEN** 平台使用有界采样、区域解码或 tile，普通缓存不长期保留全尺寸 bitmap

#### Scenario: Preloaded image arrives after composition
- **WHEN** viewer 首次组合时缓存未命中但随后预加载完成
- **THEN** 可观察缓存代次触发重组，三种 viewer 使用缓存且不并行保留重复全图请求

### Requirement: Desktop reader product capabilities do not regress
Desktop 阅读器 MUST 在共享迁移后保留双页 edge matching、Webtoon 自动滚动、键盘/鼠标导航和右键保存。

#### Scenario: Shared core is wired into Desktop
- **WHEN** 用户使用任一 Desktop 阅读模式和产品增强
- **THEN** 操作路径、反馈和保存目标与迁移前一致，并由集中产品回归测试保护

### Requirement: Android reader runtime is verified on a deployed emulator
当 reader change 触及 Android production viewer、decoder 或 UI wiring 时，验证流程 MUST 自行部署 Android 模拟器并运行当前提交的应用。

#### Scenario: Android runtime acceptance is required
- **WHEN** shared reader core 完成 JVM/Android 单元测试
- **THEN** 自动化在模拟器安装当前 APK，并验证章节打开、翻页、滤镜、错误重试、章节边界和代表性大图路径
