# Android 回流功能合并清单模板

用途：任何从 `app-desktop` 回流 Android 的功能，都必须先填写本模板。缺项或命中禁止项时，不得合并。

## 1. 基本信息

| 字段 | 内容 |
| --- | --- |
| 合并请求 ID | `MGE-YYYYMMDD-NN` |
| 关联 roadmap 任务 |  |
| 关联技术债 |  |
| `app-desktop` 源功能 |  |
| Android 目标入口 |  |
| 优先级 |  |
| 状态 | `DRAFT / REVIEW / APPROVED / REJECTED / MERGED` |

## 2. 用户功能规格

- 入口：
- 加载中反馈：
- 成功反馈：
- 空状态：
- 错误状态：
- 权限或数据缺失：
- 功能边界：

## 3. 共享业务逻辑

| 规则/算法 | 目标模块 | 测试 |
| --- | --- | --- |
|  |  |  |

## 4. Android 平台实现

| 能力 | Android 实现 |
| --- | --- |
| UI | 原版 Mihon Screen/ScreenModel |
| DI | 原版 Mihon Injekt 模块 |
| 后台任务 | WorkManager |
| 通知 | 原版 Mihon Notification |
| Web/Auth | 原版 Mihon WebView |
| 文件/权限 | Android 存储和权限模型 |

## 5. 禁止项自检

- [ ] 未复制 Desktop Composable。
- [ ] 未引入 `DesktopAppModule`、Desktop runtime、Desktop scheduler。
- [ ] 未引入 `DesktopExtensionLoader`、`ApkToJarConverter`、child-first ClassLoader。
- [ ] 未引入 `app-desktop/src/main/kotlin/android/**` 或 `androidx/**` stubs。
- [ ] 未引入 Swing/AWT、`java.awt.Desktop`、桌面路径策略。
- [ ] Android UI 未直接调用 Repository。

## 6. TDD 与验收

- 红：失败测试和失败原因。
- 绿：最小实现。
- 重构：重跑测试。
- 验收命令：
- 证据：
