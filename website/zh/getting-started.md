---
title: 开始体验
description: 从源码构建本地体验版，走通一条阅读流程。
---

# 开始体验

Mihon Desktop 处于早期开发阶段，**目前尚无公开发行包**。现阶段可以从源码构建体验版；未来公开构建将在本分支的 [Releases 页面](https://github.com/AltairCardinal/mihon-desktop/releases)提供。

## 选择平台

| 平台 | 当前范围 |
| --- | --- |
| Windows | 主要桌面目标平台，通过项目脚本构建本地体验版。 |
| macOS | 已有桌面构建入口，源码和产物目录须位于 APFS 卷。 |
| Android 8.0+ | 仓库维护独立应用身份与构建流程。 |
| Linux | 尚未配置桌面打包和验收流程。 |

## 准备工具链

安装 **Git、JDK 21、Python 3.11+**，将 `JAVA_HOME` 指向自己的 JDK 21，并确保终端可以调用 Git 和 Python。

共享构建涉及 Android 模块，需要配置 **Android SDK Platform 36** 和 **Build Tools 36.0.0**，通过 `ANDROID_HOME` 或本机 `local.properties` 指定自己的 SDK 路径。仓库已经包含 Gradle wrapper。

## 构建 Windows 体验版

在 PowerShell 中运行：

~~~powershell
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Preview
~~~

脚本会在 `app-desktop/artifacts/preview/windows/` 下生成独立应用目录。运行构建日志中 **`Final unpacked EXE:`** 对应的执行文件，并保留完整应用目录。

## 构建 macOS 体验版

将仓库克隆到 APFS 卷后运行：

~~~bash
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./scripts/build-desktop.sh preview
~~~

应用产物位于 `app-desktop/artifacts/preview/macos/` 下。

::: tip 体验版的含义
体验版不分配正式发行版本，不运行全量产品测试，适用于开发与日常试用反馈。生成体验包不等于完成原生交互验收或正式发布矩阵。
:::

## 构建 Android 开发版

配置好工具链后运行：

~~~bash
python scripts/build-android.py debug
~~~

签名、候选包、安装边界和其他构建模式见仓库的 [Android 构建与验收规范](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/architecture/android-build-and-acceptance.md)。开发版与正式版使用不同应用身份。

## 体验一条阅读流程

1. 通过已配置来源或本地书库添加阅读内容。
2. 打开章节，尝试双页阅读；封面、横版页或跨页配对不合适时，手动调整。
3. 需要跨设备继续阅读时，在同步面板连接 GitHub，让本分支的兼容构建加入同一同步空间。
4. 关注一位作者，浏览已配置来源中发现的作品。
5. 在桌面作者归档中查看语言信息，校正不准确的元数据。

跨平台体验前请阅读[功能边界](./features.md)。遇到问题时，欢迎[提交可复现的反馈](./contributing.md#report-a-problem)。

本应用不托管漫画内容。开发者与应用所使用的内容提供者没有隶属关系。
