<p align="center">
  <img src="./app-desktop/src/main/resources/icons/mihon-desktop.png" alt="Mihon Desktop 图标" width="112" height="112">
</p>

<h1 align="center">Mihon Desktop</h1>

<p align="center">
  <strong>在电脑上阅读，在 Android 上继续，发现关注作者的更多作品。</strong>
</p>

<p align="center">
  <a href="#get-started"><img src="https://img.shields.io/badge/status-early%20development-F6BF1A?labelColor=941454" alt="状态：早期开发"></a>
  <a href="https://github.com/AltairCardinal/mihon-desktop/actions/workflows/desktop-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/AltairCardinal/mihon-desktop/desktop-ci.yml?branch=main&amp;label=Desktop%20CI" alt="Desktop CI"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-3072B3" alt="许可证：Apache 2.0"></a>
</p>

<p align="center">
  <a href="#get-started">开始体验</a> ·
  <a href="#features">功能</a> ·
  <a href="#roadmap">开发方向</a> ·
  <a href="#contribute">参与开发</a> ·
  <a href="https://github.com/AltairCardinal/mihon-desktop/issues">问题反馈</a>
</p>

<p align="center">
  <a href="./README.md">English</a> · <strong>简体中文</strong>
</p>

---

Mihon Desktop 是基于 [Mihon](https://github.com/mihonapp/mihon) 独立维护的开源项目。我们希望为 Mihon 增加更多富有野心的功能：桌面阅读、更智能的双页排版、多端阅读进度同步，以及围绕作者展开的作品管理和发现。

Windows 是主要桌面目标平台。仓库同时维护 macOS 构建和本项目的 Android 应用，并通过 Kotlin 共享部分业务逻辑。

> **早期开发阶段：** 下述功能描述对应当前源码版本。仓库尚未发布公开发行包，目前可以从源码构建体验版。欢迎参与试用、反馈问题和贡献代码。

<a id="features"></a>

## 功能

| 功能 | 当前开发版可体验的内容 |
| --- | --- |
| **桌面阅读** | 在桌面界面管理书库与下载，自定义阅读模式和阅读方向。 |
| **双页阅读与跨页匹配** | 将相邻两页并排阅读，展示横版页面，并手动调整配对。桌面端通过相邻页面的图像边缘辅助识别跨页。Android 已支持双页阅读和手动配对，自动匹配属于后续开发方向。 |
| **多端同步** | 通过专用 GitHub 私有仓库，在本项目兼容的桌面与 Android 构建间同步漫画收藏、作者关注、已读状态和阅读位置。 |
| **作者关注与作品发现** | 关注作者，浏览作品及其不同来源版本，并定期检查已配置来源中新发现的作品。结果取决于来源的搜索能力和作者信息。 |
| **作品语言信息** | 根据来源元数据、标签、标题或简介中的文本识别语言信息，并在桌面作者归档中手动校正。更完整的语言筛选属于后续开发方向。 |

同步功能用于**本项目的构建版本之间**；上游 Mihon Android 应用没有实现这套同步协议。当前同步范围不包含已下载的章节图片。

<a id="get-started"></a>

## 开始体验

### 发布状态

目前**尚无公开发行包**。发布后的构建可在 [Releases 页面](https://github.com/AltairCardinal/mihon-desktop/releases)查找；现阶段请按下述步骤从源码构建。

| 平台 | 当前范围 |
| --- | --- |
| **Windows** | 主要桌面开发与验证平台，可通过项目脚本构建本地体验版。 |
| **macOS** | 已有桌面构建入口；体验版构建要求源码和产物目录位于 APFS 卷上。 |
| **Android 8.0+** | 仓库维护独立 Android 应用，使用独立的应用 ID 和构建流程。 |

目前未配置 Linux 桌面打包和验收流程。

### 构建桌面体验版

安装 **Git、JDK 21、Python 3.11+**，将 `JAVA_HOME` 指向自己的 JDK 21 安装目录，并确保命令行可以调用 Git 和 Python。为共享构建中的 Android 模块配置 **Android SDK Platform 36** 和 **Build Tools 36.0.0**，通过 `ANDROID_HOME` 或 `local.properties` 指定自己的 SDK 路径。仓库已包含 Gradle wrapper。

**Windows — PowerShell**

```powershell
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Preview
```

脚本会在 `app-desktop/artifacts/preview/windows/` 下生成独立应用目录。运行构建日志中 **`Final unpacked EXE:`** 对应的执行文件，并保留完整应用目录。

<details>
<summary><strong>macOS — 终端</strong></summary>

将仓库克隆到 APFS 卷后运行：

```bash
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./scripts/build-desktop.sh preview
```

应用产物位于 `app-desktop/artifacts/preview/macos/` 下。

</details>

体验版构建不会分配正式发行版本，也不会运行全量产品测试，适合开发和日常试用反馈。

Android 开发可在配置工具链后运行 `python scripts/build-android.py debug`。签名及其他构建模式见 [Android 构建指南](./docs/architecture/android-build-and-acceptance.md)。

### 体验一条阅读流程

1. 通过已配置的来源或本地书库添加阅读内容。
2. 打开章节，尝试双页阅读；封面、横版页面或跨页配对不合适时，手动调整配对。
3. 如需跨设备继续阅读，在本项目兼容版本的同步面板中连接 GitHub，创建或加入同一同步空间。
4. 关注一位作者，浏览已配置来源中发现的作品。

桌面存储设置、下载迁移和恢复方法见[下载目录指南](./docs/desktop-download-directory.md)。

<a id="roadmap"></a>

## 开发方向

长期目标是扩展 Mihon 的阅读体验、个人书库管理，以及围绕作者展开的作品发现能力。

- **Windows 体验：** 改进日常阅读、打包和首次使用流程，为公开发行做好准备。
- **更智能的双页阅读：** 改进跨页识别，在 Android 与桌面端逐步统一配对规则和自动匹配能力。
- **可靠的多端接续：** 完善同步连接、冲突处理、阅读位置恢复和异常恢复。
- **作者与作品发现：** 改进作者身份匹配、作品归组，以及在已配置来源中的发现能力。
- **语言识别与筛选：** 改进作品语言信息，完善按阅读语言筛选作品的范围和交互。

以上是开发方向，具体范围与顺序会随实现进展和用户反馈调整。

<a id="contribute"></a>

## 参与开发

欢迎开发者、测试者、设计者，以及愿意改进文档和翻译的贡献者。

| 方向 | 可以提供的帮助 |
| --- | --- |
| **阅读器与图像处理** | 提供可复现的跨页配对案例，改进匹配算法和阅读交互。 |
| **同步与共享 Kotlin 代码** | 改进多端行为、中断恢复，并补充相关回归测试。 |
| **作者与语言信息** | 改进作者身份匹配、来源元数据处理，以及语言校正和筛选。 |
| **桌面体验** | 修复 Windows 与 macOS 问题，改进键盘操作、无障碍体验和打包。 |
| **文档与测试** | 完善安装说明和翻译，提交实际阅读过程中可复现的问题。 |

可以从[本仓库 Issues](https://github.com/AltairCardinal/mihon-desktop/issues)开始。较大的改动请先说明要解决的问题和预期行为。Pull Request 应保持范围集中；影响产品行为的改动需遵循仓库的测试先行流程。

### 代码入口

| 路径 | 职责 |
| --- | --- |
| [`app-desktop/`](./app-desktop/) | 桌面界面、平台适配和桌面运行时。 |
| [`app/`](./app/) | 本项目的 Android 应用。 |
| [`domain/`](./domain/) 与 [`data/`](./data/) | 共享业务逻辑、数据模型和持久化。 |
| [`source-api/`](./source-api/) 与 [`source-local/`](./source-local/) | 来源接口与本地内容支持。 |
| [`docs/`](./docs/) | 设计方案、实施记录和技术指南。 |

实现和验证约定见[仓库开发规则](./AGENTS.md)。纯文档修改不需要运行产品测试。

### 反馈问题

请在[本仓库](https://github.com/AltairCardinal/mihon-desktop/issues)反馈本项目的问题，并提供：

- 操作系统，以及应用版本或提交号。
- 复现步骤、预期结果与实际结果。
- 截图或相关日志；移除凭据和个人信息。
- 配对问题：页码、阅读方向和期望的页面排列。
- 同步问题：涉及的平台，以及各设备最后执行的操作。

<a id="credits"></a>

## 致谢与许可证

Mihon Desktop 建立在 [Mihon](https://github.com/mihonapp/mihon)、[Tachiyomi](https://github.com/tachiyomiorg/tachiyomi) 及其贡献者的工作之上。本项目独立维护开发与发布流程。

项目采用 [Apache License, Version 2.0](./LICENSE)，保留已有版权声明和归属：

```text
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
```

本应用不托管漫画内容。开发者与应用所使用的内容提供者没有隶属关系。
