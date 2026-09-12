# Mihon 双端原生界面同步演示

这是需求草案阶段的本地 HTML 原型。它把同步操作放进 Windows Desktop 与 Android 手机的 Mihon 页面路径中，不连接 Git 服务，也不代表同步需求已经获用户确认。

## 打开与测试

直接双击 `index.html`，或在浏览器地址栏打开该文件的 `file:///` 地址。HTML、CSS 和 JavaScript 均为本目录文件，没有 CDN、安装依赖或网络请求。

行为模型测试：

```text
node --test docs/prototypes/multi-device-sync/sync-model.test.cjs
```

双端视图契约测试：

```text
node --test docs/prototypes/multi-device-sync/ui-view.test.cjs
```

真实 file URL 浏览器验收（使用本机缓存的 Playwright/Chrome）：

```text
node --test docs/prototypes/multi-device-sync/ui-browser.test.cjs
```

## 原型路径

- 应用外「演示预览」工具条可切换 Windows Desktop / Android 手机、浅色 / 深色、离线、启动/定期同步和重置。收起工具条后，产品内容不显示设备实验台或模拟控制。
- Windows 使用 Mihon Desktop 外壳和六项底栏：书架、更新、历史、浏览、作者、更多；浏览页签按源码保留图源/插件，作者从独立底栏进入，迁移从更多进入。默认打开更新 → 同步，并保留更新 → 更新对照页。
- Android 使用手机状态栏、Material TopAppBar、五项底栏：书架、更新、历史、浏览、更多。作者入口在浏览内的「图源 / 作者 / 插件 / 迁移」页签，不加入 Android 底栏。
- 从书架漫画卡片、更新条目或作者详情进入漫画详情；在详情中收藏、取消收藏和开始阅读。作者可从 Windows 作者页或 Android 浏览 → 作者列表进入详情并关注。
- 更新页面保留平台差异：Windows 使用 48×68 封面、卡片行和筛选/日历/全部已读/刷新动作；Android 使用 56dp 紧凑行、44×44 封面和筛选/日历/刷新动作。
- 更新与同步是同一更新入口内的平级页签。同步页面沿用各端 TopAppBar、列表行、按钮、Snackbar 和开关语言；手动同步在产品页触发，启动/定期同步从应用外预览工具触发，并保留接收端取消确认、忽略、冲突和离线重试。
- 更新底栏的右上角显示当前设备未读更新数（超过 99 显示 99+），左下角独立显示同步失败、待确认、执行中或待发送状态；待确认使用柔和主题色清单图标。角标只作状态提示，更新页签内的单条/全部已读、确认/忽略和成功重试分别清除对应状态，后台状态变化不会强制切页。

## 建议演示顺序

1. 初始打开 Windows 更新 → 同步，电脑 B 会看到手机 A 发来的取消收藏待确认项；可确认或忽略本次取消。
2. 切换更新 → 更新，在一台设备（来源端）从更新条目或漫画详情操作收藏、阅读或关注，并点击更新 → 同步 →「立即同步」；再切换另一台设备（接收端）点击「立即同步」查看结果。
3. 在应用外工具条切换 Android，进入浏览 → 作者 → 作者详情关注；来源端先立即同步，再切换 Windows 或另一台设备作为接收端立即同步。Android 不会出现 Desktop 的独立作者底栏。
4. 在两台设备分别制造相反收藏决定；每次先在产生决定的来源端立即同步，再在另一台接收端立即同步，进入同步页冲突条目选择保留本地或采用远端。
5. 从漫画详情进入阅读器，来源端记录当前位置并立即同步，再切到接收端立即同步。接收端正在阅读时不自动翻页，可在同步页显式采用远端位置；阅读模式始终按设备独立保存。
6. 在应用外工具条切换离线，继续收藏或阅读；恢复在线后先同步来源端，再同步接收端。待发送操作会保留到重试成功。

## 源码对照与边界

界面还原依据仓库中的 `app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt`、`app-desktop/src/main/kotlin/mihon/desktop/ui/updates/UpdatesTab.kt`、`app-desktop/src/main/kotlin/mihon/desktop/ui/authors/AuthorsTab.kt`、`app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt`、`app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt`、`app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt` 和 `presentation-theme/src/commonMain/kotlin/eu/kanade/presentation/theme/colorscheme/TachiyomiColorScheme.kt`。颜色使用 Tachiyomi 默认浅色/深色主题；图标为本地 SVG Material 路径。

收藏、确认、去重、冲突、阅读位置和离线待发送由 `sync-model.js` 内存模型驱动。刷新页面会重新建立示例状态；没有持久化、真实远端、系统后台调度、真实凭据、跨源匹配、完整历史迁移、真实下载或漫画图片。Android 手机预览是本地视口原型，不能代替真实 Android 构建；Windows 窗口标题和控件是原应用外观示意。
