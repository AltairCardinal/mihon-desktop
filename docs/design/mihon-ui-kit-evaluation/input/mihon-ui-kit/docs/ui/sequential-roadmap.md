# 按类别执行的实现与验证路线图

本包已为每类写好入口与场景预置。下表用于导入后按顺序检查、修复并补齐真实验证。页面存在不表示该类别验证完成。所有 Android/实机状态初始为 NOT_RUN。

## 阶段 0：准备与导入

核对工作树与组件版本；在本地独立分支运行导入预检；审查变更后应用；确认现有 AGENTS 未被替换；构建 debug APK 与测试 APK。环境阻塞可继续做规范和模型审查，但不能跳过 Android 验收后宣布整体完成。

## 阶段 1–6：按 UI 编号递增

每行都执行：读规则/参考 → 查看渲染分支与四个 fixture → 编译 → 对应原生/手工测试 → 记录证据 → 修复回归。先运行可自动执行项，再用真实设备执行环境与业务验证。

| 顺序 | 类别 | 样例实现 | 验收场景 | 当前原生状态 |
|---|---|---|---|---|
| 01 | UI-01 页面类型与容器职责 | `NavigationExamples.kt` | C01–C04 | NOT_RUN |
| 02 | UI-02 顶层导航与标签切换 | `NavigationExamples.kt` | C01–C04 | NOT_RUN |
| 03 | UI-03 顶栏标题与操作区 | `NavigationExamples.kt` | C01–C04 | NOT_RUN |
| 04 | UI-04 返回关闭与取消 | `NavigationExamples.kt` | C01–C04 | NOT_RUN |
| 05 | UI-05 安全区与滚动容器 | `NavigationExamples.kt` | C01–C04 | NOT_RUN |
| 06 | UI-06 状态保留与回跳 | `NavigationExamples.kt` | C01–C04 | NOT_RUN |
| 07 | UI-07 主要操作与可用性 | `CollectionExamples.kt` | C01–C04 | NOT_RUN |
| 08 | UI-08 条目和点击区域 | `CollectionExamples.kt` | C01–C04 | NOT_RUN |
| 09 | UI-09 选择与批量操作 | `CollectionExamples.kt` | C01–C04 | NOT_RUN |
| 10 | UI-10 滑动拖拽与触觉 | `CollectionExamples.kt` | C01–C04 | NOT_RUN |
| 11 | UI-11 搜索与键盘 | `CollectionExamples.kt` | C01–C04 | NOT_RUN |
| 12 | UI-12 筛选排序显示偏好 | `CollectionExamples.kt` | C01–C04 | NOT_RUN |
| 13 | UI-13 菜单与溢出操作 | `DialogExamples.kt` | C01–C04 | NOT_RUN |
| 14 | UI-14 弹层与内部导航 | `DialogExamples.kt` | C01–C04 | NOT_RUN |
| 15 | UI-15 设置项与生效时机 | `DialogExamples.kt / PreferenceExamples` | C01–C04 | NOT_RUN |
| 16 | UI-16 输入校验与焦点 | `DialogExamples.kt` | C01–C04 | NOT_RUN |
| 17 | UI-17 破坏性操作 | `DialogExamples.kt` | C01–C04 | NOT_RUN |
| 18 | UI-18 加载空态与错误 | `AsyncExamples.kt` | C01–C04 | NOT_RUN |
| 19 | UI-19 刷新分页与长任务 | `AsyncExamples.kt` | C01–C04 | NOT_RUN |
| 20 | UI-20 反馈重试与恢复 | `AsyncExamples.kt` | C01–C04 | NOT_RUN |
| 21 | UI-21 全局模式与横幅 | `AsyncExamples.kt` | C01–C04 | NOT_RUN |
| 22 | UI-22 视觉基础与复用 | `VisualExamples.kt` | C01–C04 | NOT_RUN |
| 23 | UI-23 封面文本角标日期 | `VisualExamples.kt` | C01–C04 | NOT_RUN |
| 24 | UI-24 动效与滚动联动 | `VisualExamples.kt` | C01–C04 | NOT_RUN |
| 25 | UI-25 屏幕与系统适配 | `VisualExamples.kt` | C01–C04 | NOT_RUN |
| 26 | UI-26 无障碍与本地化 | `VisualExamples.kt` | C01–C04 | NOT_RUN |
| 27 | UI-27 作品与章节 | `BusinessExamples.kt` | C01–C04 | NOT_RUN |
| 28 | UI-28 阅读器 | `BusinessExamples.kt` | C01–C04 | NOT_RUN |
| 29 | UI-29 下载与后台控制 | `BusinessExamples.kt` | C01–C04 | NOT_RUN |
| 30 | UI-30 源扩展与外部入口 | `BusinessExamples.kt` | C01–C04 | NOT_RUN |
| 31 | UI-31 追踪与外部绑定 | `BusinessExamples.kt` | C01–C04 | NOT_RUN |
| 32 | UI-32 存储备份与导入导出 | `BusinessExamples.kt` | C01–C04 | NOT_RUN |

## 阶段 7：真实页面回归与发布隔离

把样例检查迁移到本次新增业务页面；验证真实宿主导航、权限/任务/数据交互和环境矩阵；审查首批截图基准；检查生产构建不含实验代码入口。记录未覆盖业务能力，不以 reducer 测试代替 E2E。

## 每一类完成条件

所有适用规则有依据；构建通过；声明场景具备实际层级的证据或具名阻塞；发现的问题被修复并重测；例外获准。类别状态允许“代码已写 / 设备阻塞”，不能把这种状态简写成“完成”。

## 给执行 Agent 的任务

```text
读取 AGENTS.md 和 docs/ui/README.md，检查当前分支与 component-baseline.md 的差异。
按 docs/ui/sequential-roadmap.md 顺序执行 UI-01 到 UI-32：编译现有样例，修复 API/布局/交互问题，补齐适用测试并记录证据。
对 production 新页面建立 page-contract，复用当前 Mihon 组件。
样例控制面板、日志和假业务不能进入 production；不得访问真实账号/书库/文件做破坏性测试。
运行环境阻塞时保留该项 BLOCKED，继续可以独立完成的类别检查。
最终输出各类别结果、实际运行命令、证据路径以及所有未验证项；不能只完成代码后停止汇报为已验收。
```
