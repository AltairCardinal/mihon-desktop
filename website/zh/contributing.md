---
title: 参与贡献
description: 通过阅读案例、测试、代码、设计、文档和翻译帮助项目成长。
---

# 参与贡献

不必会写代码才能参与。可复现的阅读案例、清楚的问题反馈、设计建议、测试和翻译，都能帮助项目改进。

这是独立维护的分支。本项目新增能力与问题，请反馈到[本仓库 Issues](https://github.com/AltairCardinal/mihon-desktop/issues)。

## 找到合适的参与方向

| 方向 | 可以提供的帮助 |
| --- | --- |
| 阅读器与图像处理 | 提供可复现的跨页配对案例，改进匹配算法和阅读交互。 |
| 同步与共享 Kotlin | 检查双端行为与中断恢复，补充对应回归覆盖。 |
| 作者与语言信息 | 改进作者匹配、来源元数据处理、语言校正与筛选。 |
| 桌面体验 | 改进 Windows/macOS 行为、键盘操作、无障碍和打包。 |
| 文档与翻译 | 讲清安装流程，修正过时说明，保持中英文同步。 |

较大的改动请先开 issue，说明问题、预期行为和平台范围，再开始实施。先检查能否复用现有来源、用例、组件或共享模块。

## 反馈问题 {#report-a-problem}

提供操作系统、应用版本或提交号、精确复现步骤、预期结果和实际结果。附上相关截图或日志前，移除凭据和个人信息。

阅读器配对问题请注明页码、阅读方向和期望排列；同步问题请注明涉及平台与各设备最后执行的操作。仅分享有权分享的内容。

## 参与代码开发

开始前阅读仓库的[贡献指南](https://github.com/AltairCardinal/mihon-desktop/blob/main/CONTRIBUTING.md)和 [Agent 与工程规则](https://github.com/AltairCardinal/mihon-desktop/blob/main/AGENTS.md)。

| 目录 | 职责 |
| --- | --- |
| [app-desktop](https://github.com/AltairCardinal/mihon-desktop/tree/main/app-desktop) | 桌面 UI、平台适配、运行时与测试。 |
| [app](https://github.com/AltairCardinal/mihon-desktop/tree/main/app) | 本分支 Android 应用。 |
| [domain](https://github.com/AltairCardinal/mihon-desktop/tree/main/domain) / [data](https://github.com/AltairCardinal/mihon-desktop/tree/main/data) | 共享行为、数据模型与持久化。 |
| [source-api](https://github.com/AltairCardinal/mihon-desktop/tree/main/source-api) / [source-local](https://github.com/AltairCardinal/mihon-desktop/tree/main/source-local) | 来源契约与本地内容。 |
| [website](https://github.com/AltairCardinal/mihon-desktop/tree/main/website) | 本站的双语公开文档。 |
| [docs](https://github.com/AltairCardinal/mihon-desktop/tree/main/docs) | 架构、计划、证据与技术指南。 |

产品行为变化须执行红 → 绿 → 重构：先观察有意义的失败测试，用最小实现通过，再清理并复验同一 focused 范围。导航、依赖注入和网络变更须覆盖真实 production 接线。保持修改范围集中，如实说明实际运行的测试。纯文档修改无需运行产品测试。

桌面集成测试还提供需要显式启用 Test Mode 的 headless HTTP 控制接口。它是开发工具，不是面向读者的远程服务；使用时遵循现有 [Test Mode 边界](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#test-mode-屏幕录制权限边界)与仓库测试约定。

## 验收通过后更新公开文档 {#accepted-documentation}

公开功能说明描述当前公开基线，不跟随开发分支的每次提交自动宣称功能完成。

1. **实施并验证。** 将拟议行为、限制和测试证据写入对应内部计划或交接记录。所有者尚未验收时，不推进公开功能声明。
2. **取得所有者明确验收。** 构建成功、CI 通过、PR 合并或 Agent 完成汇报都不等于验收。记录所有者确认的具体行为和源码版本。
3. **原位更新当前说明。** 替换中英文功能页的受影响部分，包含平台范围、用户入口、反馈与限制；仅在需要时同步首页、体验说明和 README 摘要。
4. **追加一条简洁历史。** 链接实现版本，以及可用的公开 issue 或 PR；若在私有聊天中验收，只记录日期与最小范围摘要，不公开私有聊天链接或全文。说明变化，不追加第二份完整功能说明，也不编造发行版本。
5. **构建与检查网站。** 检查链接、双语一致性、导航和受影响页面，随后按仓库 Pages 流程发布。

2026 年 10 月 13 日的首版基线是对已公开说明的迁移，不追认其他开发中工作已经验收。

[公开文档维护规范](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/architecture/public-documentation.md)定义 Agent 流程、证据、部署及失败处理。一个功能验收通过，不表示可以顺带公开全部待验收能力。

## 本地预览文档

使用 Node.js 22 或更新版本，在仓库根目录运行：

~~~bash
npm --prefix website ci
npm --prefix website run docs:build
npm --prefix website run docs:preview
~~~

打开 VitePress 输出的本地地址，保留项目路径 `/mihon-desktop/`。构建结果位于 `website/.vitepress/dist/`，不要提交依赖目录或生成产物。

英文是站点根路径的默认语言，简体中文位于 `/zh/`。两种语言保持相同文件名与章节锚点；每页都有 Git 历史链接，无需复制多份“当前说明”也能追踪旧版本。

## 致谢与许可证

Mihon Desktop 建立在 [Mihon](https://github.com/mihonapp/mihon)、[Tachiyomi](https://github.com/tachiyomiorg/tachiyomi) 及贡献者的工作之上。贡献遵循仓库的 [Apache-2.0 许可证](https://github.com/AltairCardinal/mihon-desktop/blob/main/LICENSE)，请保留已有版权与归属声明。
