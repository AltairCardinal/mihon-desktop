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
node --test docs/prototypes/multi-device-sync/ui-browser.test.cjs docs/prototypes/multi-device-sync/library-sync.test.cjs docs/prototypes/multi-device-sync/batch-sync.test.cjs
```

## 原型路径

- 应用外「演示预览」工具条可切换 Windows Desktop / Android 手机、浅色 / 深色、离线、启动/定期同步和重置。收起工具条后，产品内容不显示设备实验台或模拟控制。
- Windows 使用 Mihon Desktop 外壳和六项底栏：书架、更新、历史、浏览、作者、更多；浏览页签按源码保留图源/插件，作者从独立底栏进入，迁移从更多进入。默认打开书架，同步从书架顶栏进入；更新底栏只打开漫画更新列表。
- Android 使用手机状态栏、Material TopAppBar、五项底栏：书架、更新、历史、浏览、更多。作者入口在浏览内的「图源 / 作者 / 插件 / 迁移」页签，不加入 Android 底栏。
- 从书架漫画卡片、更新条目或作者详情进入漫画详情；在详情中收藏、取消收藏和开始阅读。作者可从 Windows 作者页或 Android 浏览 → 作者列表进入详情并关注。
- 更新页面保留平台差异：Windows 使用 48×68 封面、卡片行和筛选/日历/全部已读/刷新动作；Android 使用 56dp 紧凑行、44×44 封面和筛选/日历/刷新动作。
- 书架顶栏的同步按钮打开高位底部面板，同步是书架的子功能。面板最多 560px 宽、720px 高，窄屏自动适配，列表内部滚动；可用关闭按钮、遮罩、Escape 或下滑抓手关闭。关闭后回到书架，不取消后台同步，完成时也不会抢占当前页面。
- 同步面板右上角齿轮进入同一面板内的设置子页面，宽高不变；左上返回或 Escape 回到同步列表并恢复滚动位置。启动/定期开关仅影响当前设备，修改立即生效。关闭按钮、遮罩或下滑会关闭整个同步面板；后台同步完成不会改变当前子页面。
- 同步按钮只有三种表达：后台异步更新时旋转；有需手动处理事项时显示数量（取消收藏/关注确认、冲突、远端阅读位置选择合计，超过 99 显示 99+）；都没有时只保留普通同步图标。旋转与计数可同时出现。未发送队列、离线与上次失败在面板内说明，不增加顶栏状态。更新底栏仅显示漫画未读更新数，与同步数量独立。

## 大量待处理场景

打开应用外「演示预览」→「120 项待处理」，会替换当前示例数据并自动打开当前设备的同步面板。80 项取消收藏与 40 项取消关注均通过现有模型的收藏/关注、交换、取消与接收流程生成；不是静态占位行。条目标题、来源及确认/忽略操作完整可用，处理后数量立即减少。再次点击会重新建立 120 项场景，重置演示恢复普通数据。

标题、设置入口与待处理总数固定，列表内部滚动，优先展示待处理内容；书架顶栏超过 99 显示 99+，面板内显示精确总数。设置返回保留滚动位置。本原型直接渲染 120 行，不代表已实现面向无限数据的分页或虚拟列表。

## 章节式批量处理

交互参考原版 `MangaToolbar.kt`、`MangaBottomActionMenu.kt`、`MangaScreen.kt` 与 `MangaScreenModel.toggleSelection/toggleAllSelection/invertSelection`：长按条目进入选择，选择中点击条目切换；再次长按可连续选中范围，桌面端也可 Shift 点击。另提供“多选”显式入口。条目列表的直接上方集中显示多选操作条：未选择时提供“多选”和“全部处理”菜单；选择时显示已选数量、退出、全选、反选，以及“忽略所选”和“确认所选取消”，零选择时禁用操作。操作条在列表滚动时吸附于滚动区域顶部，同步面板标题不被选择模式替换，面板底部不再放置批量操作。全选覆盖整个待确认列表，不只当前可见区域。

列表上方操作条的“更多”提供“全部确认取消”和“全部忽略取消”，无需逐项勾选。两者都会显示一次汇总确认，列出漫画与作者数量和后果；取消汇总框保留选择。执行复用已有确认/忽略模型，结果显示实际处理数，全部完成退出选择。关闭面板、切换设备及重置场景清除选择。

批量仅处理取消收藏/关注，冲突与远端阅读位置需要各自的决定，不在此批量操作范围。确认框保存本次操作 ID；后台新到达的条目不加入，已被远端新操作作废的条目跳过并反馈。选择状态随有效待确认项更新，避免误处理失效操作。确认只移除接收端收藏/关注，忽略保留接收端状态，两者均不反向恢复来源端。

## 建议演示顺序

1. 初始打开 Windows 书架 → 顶栏同步，电脑 B 会看到手机 A 发来的取消收藏待确认项；可确认或忽略本次取消。
2. 打开底栏更新，在一台设备（来源端）从更新条目或漫画详情操作收藏、阅读或关注，并点击书架 → 顶栏同步 →「立即同步」；再切换另一台设备（接收端）点击「立即同步」查看结果。
3. 在应用外工具条切换 Android，进入浏览 → 作者 → 作者详情关注；来源端先立即同步，再切换 Windows 或另一台设备作为接收端立即同步。Android 不会出现 Desktop 的独立作者底栏。
4. 在两台设备分别制造相反收藏决定；每次先在产生决定的来源端立即同步，再在另一台接收端立即同步，进入同步页冲突条目选择保留本地或采用远端。
5. 从漫画详情进入阅读器，来源端记录当前位置并立即同步，再切到接收端立即同步。接收端正在阅读时不自动翻页，可在同步页显式采用远端位置；阅读模式始终按设备独立保存。
6. 在应用外工具条切换离线，继续收藏或阅读；恢复在线后先同步来源端，再同步接收端。待发送操作会保留到重试成功。

## 源码对照与边界

同步设置面板参考 Android 书架筛选使用的 `LibrarySettingsDialog.kt`、`TabbedDialog.kt` 和 `presentation-core/src/main/java/tachiyomi/presentation/core/components/AdaptiveSheet.kt`：采用顶部圆角、surfaceContainerHigh 表面、24px 内容边距；同步与设置子页面共用最大 560px 的面板，以容纳待处理列表并避免切换时尺寸跳变。按本次设计要求，Windows 与 Android 原型均从应用窗口底部弹出；这不表示 Windows 原有书架筛选已经采用底部面板。齿轮使用对应 `Icons.Default.Settings` 的 Material Filled SVG 路径。

界面还原依据仓库中的 `app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt`、`app-desktop/src/main/kotlin/mihon/desktop/ui/updates/UpdatesTab.kt`、`app-desktop/src/main/kotlin/mihon/desktop/ui/authors/AuthorsTab.kt`、`app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt`、`app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt`、`app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt` 和 `presentation-theme/src/commonMain/kotlin/eu/kanade/presentation/theme/colorscheme/TachiyomiColorScheme.kt`。颜色使用 Tachiyomi 默认浅色/深色主题；图标为本地 SVG Material 路径。

收藏、确认、去重、冲突、阅读位置和离线待发送由 `sync-model.js` 内存模型驱动。刷新页面会重新建立示例状态；没有持久化、真实远端、系统后台调度、真实凭据、跨源匹配、完整历史迁移、真实下载或漫画图片。Android 手机预览是本地视口原型，不能代替真实 Android 构建；Windows 窗口标题和控件是原应用外观示意。
