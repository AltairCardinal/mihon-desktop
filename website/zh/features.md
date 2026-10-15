---
title: 当前功能
description: 这个独立分支相对上游 Mihon 增加了什么，以及当前的功能边界。
---

# 当前功能

这里是本分支唯一的当前功能说明，集中介绍相对于上游 Mihon 新增的体验，不重复完整介绍继承而来的所有功能。

::: info 公开文档基线 · 2026 年 10 月 13 日
首版说明承接仓库中已经公开的 README 与指南，描述早期开发源码基线，**不代表公开发行或新增验收结论**。后续功能变化只有在项目所有者明确验收通过后，才会进入本页。[查看文档变更历史](./changelog.md)。
:::

| 新增能力 | Desktop | 本分支的 Android 应用 |
| --- | --- | --- |
| 桌面阅读 | Windows 为主要目标；macOS 已有构建入口 | Android 继续作为配套平台 |
| 双页阅读 | 手动配对与图像边缘辅助跨页匹配 | 双页阅读与手动配对 |
| 多端同步 | 兼容构建通过 GitHub 共享同步空间 | 兼容构建加入同一空间 |
| 作者关注与发现 | 关注作者，浏览已配置来源中的作品 | 复用作者业务，界面与结果依平台和来源而定 |
| 作品语言信息 | 元数据识别，支持在作者归档中手动校正 | 更完整的语言筛选仍是开发方向 |

## 桌面阅读 {#desktop-reading}

**Windows 启动提示：** 运行打包后的 EXE，应用初始化期间会显示深色 Mihon 卡片、启动文案和底部强调色条。原生提示在应用初始化前出现，并在第一个应用窗口打开时自动关闭。它是静态等待提示，不表示实际进度百分比。出现速度取决于机器；恢复最大化窗口时，完整内容呈现前仍可能有短暂间隔。本功能仅验收了 Windows 打包程序。

Mihon 的书库与阅读体验有了桌面入口。可以在桌面界面管理书库和下载、打开章节，并选择阅读模式与阅读方向。

**体验路径：**[构建桌面体验版](./getting-started.md) → 从已配置来源或本地书库添加作品 → 打开章节。

**Windows 翻页设置：** 阅读器设置 → 阅读模式 → 选择 **翻页式**，在下方 **翻页** 中选择 **从左到右** 或 **从右到左**，两项互斥。单页与双页布局沿用所选方向。自动模式保持自适应从右到左行为，禁用手动方向选择；条漫模式不显示翻页方向选项。

**平台边界：** Windows 是主要桌面开发与验证平台。仓库同时维护 macOS 构建入口，其体验版要求源码和产物目录位于 APFS 卷。当前未配置 Linux 桌面打包与验收流程。体验包构建成功不表示已经具备正式发布条件。

## 双页阅读与跨页匹配 {#two-page-reading}

将相邻两页并排阅读，展示横版页面；封面或页面顺序让配对偏移时，可以手动调整。

**体验路径：** 打开章节 → 选择双页阅读 → 检查页面顺序 → 配对不合适时，在阅读器中手动调整。建议使用熟悉的章节，对照原本应当连在一起的跨页。

**Android 阅读方向：** 阅读器设置 → 阅读模式提供 **双页式（从右到左）** 和 **双页式（从左到右）**，页面排列与翻页行为遵循所选方向。旧客户端读取不认识的左到右双页作品设置时，会降级为从左到右单页。

**Desktop 辅助匹配：** 根据相邻页面的图像边缘信息辅助识别跨页。这是配对辅助能力，不保证能正确识别每种扫描图或排版；手动校正仍是阅读流程的一部分。

**Android 边界：** 本分支 Android 阅读器已支持双页阅读和手动配对；Android 自动跨页匹配属于后续开发方向。

## 多端收藏与阅读进度同步 {#multi-device-sync}

使用专用的 **GitHub 私有仓库**保存同步空间，在本分支兼容的桌面和 Android 构建之间共享状态。

当前公开说明中的同步范围包括：

- 漫画收藏。
- 作者关注。
- 章节已读状态。
- 阅读位置。

**体验路径：** 在一台设备的同步面板中连接 GitHub → 创建或加入同步空间 → 在另一台设备的兼容构建中加入同一空间。通过面板的状态和反馈了解同步进展。

**兼容边界：** 上游 Mihon Android 应用没有实现本分支的同步协议。两端需要使用本仓库的兼容构建。

**内容边界：** 不同步已下载的章节图片。每台设备仍需能够获取对应阅读内容；同步范围是上述书库与阅读状态，并非搬运应用的全部文件。

## 作者关注与新作品发现 {#authors-and-discovery}

关注作者，浏览其作品以及不同来源中的版本。发现流程会定期检查已配置来源，查找与关注作者相关的新发现作品。

**体验路径：** 打开作者归档 → 关注一位作者 → 浏览找到的作品和来源版本。保留相关来源配置，让发现流程能够执行搜索。

**发现边界：** 结果依赖来源的搜索能力和作者信息，不是全网作品目录，也不保证找到作者的全部作品。别名、缺失署名和不一致的来源数据都可能影响匹配。

## 作品语言信息 {#work-language}

根据来源元数据、标签，以及标题或简介中的文本识别语言信息。桌面作者归档也支持手动校正。

**体验路径：** 在桌面作者归档中查看作品语言信息 → 元数据导致结果不准确时，手动校正。

**识别边界：** 这是基于元数据的识别，**不使用 OCR 读取漫画页面图片中的文字**；来源语言也不一定等于某个作品版本的实际语言。

**筛选边界：** 更完整的语言筛选属于开发方向。这份基线不宣称书库、搜索、作者和各平台上的筛选范围已经全部完成或统一。

## 其他适配与新增能力 {#other-additions}

以下较小的新增能力已有仓库指南，具体边界以对应文档为准。

| 能力 | 入口与边界 | 详情 |
| --- | --- | --- |
| 桌面下载位置 | 设置 → 下载 → 下载目录。重启后生效，不自动迁移已有下载。 | [存储与手动迁移](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/desktop-download-directory.md) |
| 桌面网络路由 | 设置 → 网络，支持对使用 Mihon 托管网络客户端的扩展请求单独覆盖策略；使用直连、系统策略或指定代理，不管理代理订阅或节点。 | [网络路由设计与边界](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/architecture/desktop-network-routing.md) |
| 外部浏览器来源验证 | 在来源验证中打开浏览器，完成网站验证后，主动导入当前域名的通行 Cookie。可选助手只在用户操作时读取该域名的通行值。 | [Cookie 助手使用与权限](https://github.com/AltairCardinal/mihon-desktop/blob/main/browser-extension/mihon-cookie-helper/README.md) |
| 缺失扩展安装建议 | 扩展页面根据书库收藏提示缺失扩展，提供单项或批量安装流程；可用性受已配置仓库与平台安装规则约束。 | [交付记录与平台边界](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/2026-09-17-extension-install-suggestions-handoff.md) |
| Upcoming 章节预测 | 从桌面更新页进入 Upcoming，查看预期日期；预测不保证实际发布时间。 | [已记录的桌面差异](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#desktop-产品差异) |
| 桌面 Webtoon 自动滚动 | 在 Webtoon 阅读设置中开启自动滚动并选择速度；本说明不宣称 Android 已有同等支持。 | [已记录的桌面差异](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#desktop-产品差异) |
| 桌面扩展兼容 | APK-to-JAR 兼容层将受支持的 Android 扩展行为适配到桌面，不保证所有 Android 扩展都可运行。 | [已记录的桌面差异](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#desktop-产品差异) |
| 桌面输入与系统集成 | 键盘鼠标操作、宽屏布局，以及桌面文件目录和剪贴板集成适配桌面使用习惯；各项操作的平台支持有所不同。 | [平台对照说明](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md) |

## 后续方向 {#direction}

项目继续围绕五个方向发展：Windows 阅读体验、更智能的双页匹配、可靠的多端接续、以作者为中心的作品发现，以及更完善的语言识别与筛选。

尚在开发的能力可能已经出现在源码、设计文档或原型中，但这不代表所有者已验收或功能已经公开发行。参阅[验收后的文档更新流程](./contributing.md#accepted-documentation)与[变更历史](./changelog.md)。
