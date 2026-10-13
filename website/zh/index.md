---
layout: home
title: 为阅读打开更多可能
hero:
  name: Mihon Desktop
  text: 为阅读，打开更多可能。
  tagline: 在电脑上阅读，在 Android 上继续，发现关注作者的更多作品。
  image:
    src: /mihon-desktop.png
    alt: Mihon Desktop 项目图标
  actions:
    - theme: brand
      text: 了解功能
      link: /zh/features
    - theme: alt
      text: 构建体验版
      link: /zh/getting-started
features:
  - title: 更宽广的阅读体验
    details: 以 Windows 为主要目标的桌面应用，支持双页并排阅读，并通过图像边缘分析辅助跨页配对。
    link: /zh/features#two-page-reading
    linkText: 了解阅读体验
  - title: 随你而行的书库
    details: 在本项目兼容构建之间，同步漫画收藏、作者关注、已读状态和阅读位置。
    link: /zh/features#multi-device-sync
    linkText: 了解同步边界
  - title: 从作品，找到作者的更多作品
    details: 关注作者，浏览已配置来源中的作品与版本，逐步完善归档中的作品语言信息。
    link: /zh/features#authors-and-discovery
    linkText: 了解作者归档
---

<div class="home-body">
  <div class="home-status">
    <strong>早期开发阶段</strong>
    <span>目前尚无公开发行包。欢迎阅读源码、构建体验版，一起决定下一步。</span>
    <a href="./getting-started.html">开始体验 →</a>
  </div>
  <div class="home-story">
    <div>
      <h2>熟悉的起点，<br>更有野心的可能。</h2>
      <p>Mihon Desktop 是独立维护的 Mihon 分支。我们希望扩展它的阅读体验：桌面阅读、更智能的双页排版、多端阅读接续、作者作品发现，以及基于语言的作品整理。</p>
      <p>Windows 是主要桌面目标平台。仓库同时维护 macOS 构建和本项目的 Android 应用，并通过 Kotlin 共享部分业务逻辑。</p>
      <a href="./features.html">阅读当前功能说明 →</a>
    </div>
    <div class="home-principles">
      <h3>说清今天能做什么</h3>
      <p>桌面端提供跨页辅助匹配，Android 当前支持手动配对；语言信息来自元数据，并非图像识别。每项功能都写明边界。</p>
      <h3>一份当前说明，一段可追溯的历史</h3>
      <p>功能说明保存当前公开基线，验收后的变化直接更新对应章节；变更历史与每页 Git 历史保留演进记录。</p>
      <h3>和贡献者一起构建</h3>
      <p>可复现的阅读案例、认真的测试、设计、代码和翻译，都是帮助项目成长的方式。</p>
    </div>
  </div>
  <div class="home-bottom">
    <p>让下一次阅读，更好一点。</p>
    <a href="./contributing.html">找到你的参与方式 →</a>
  </div>
  <PageHistory />
</div>
