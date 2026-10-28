# 双端图源图片请求核查与修复

## 范围与结论边界

本次起因是 Desktop 漫画柜后台下载收到 HTTP 403，却提示需要登录。先前在线阅读修复
`6bcde4fd7f`（2026-05-11）和 `dcb2dfb24c`（2026-08-03）已包含在排查时运行的
`be8faee272` 版本中，但没有接入独立的后台下载请求链。

核查范围是本仓库 Android、Desktop 的图源图片及相邻错误恢复入口：后台下载、在线阅读、
预加载、封面、图片保存/分享/设封面、失败图片外开、浏览器/WebView，以及共享错误与持久化。
代码审查覆盖入口和调用链，行为验证使用真实 production 实现、HTTP 服务器及真实扩展夹具；
不把代码检索、符号存在、类名检查当作行为通过证据。本次不是全仓库测试或稳定发布验收，
也不承诺任意第三方扩展、任意实时站点、所有设备不存在其他问题。

## 已确认的问题与实现

1. **Desktop 后台下载丢失源请求语义。** 原 `DefaultDownloadFileOperations` 只按 URL
   构造 GET，丢失图源 client、headers、自定义 imageRequest/getImage 的方法、body 和页面元数据。
   已有 page URL 的队列同样受影响。下载与阅读现共用 `resolveSourceImageResponse`，优先执行源
   `getImage(Page)`；Page 保留 sourcePageIndex、pageUrl、imageUrl。仅没有图片 API 的兼容源
   使用源 client/headers 构造 fallback。文件校验、临时页发布、partial index、取消代次和打包
   仍走既有下载实现，不另建下载系统。
2. **403 被误归类为登录。** 共享映射现区分 401 `Authentication` 和 403 `Forbidden`，
   Android 搜索/阅读、Desktop 图源错误/队列均显示对应提示；403 恢复为重试，不打开登录入口。
   旧队列 `Authentication` 中精确的 `HTTP 403`、`HTTP error 403` 在回读时转为 Forbidden；
   模糊文本不猜测，无数据库 schema 变化。
3. **Desktop 封面与阅读 fallback 吞掉源网络 getter 错误。** `SourceNetworkAccess` 对
   HttpSource 直接访问，其他源走可访问性受控的反射；只在方法不存在时允许 fallback。
   方法存在但抛错、不可访问、返回 null/错误类型时保留失败，不发无标头或默认客户端请求。
   缺少 DI 注册与 DI 工厂抛错同样区分，后者不吞掉。
4. **网络中断误报磁盘错误。** 下载源请求和响应体读取的 IOException 现归为 Network；
   本地写入、移动仍为 Storage/Permission，取消异常继续重抛。
5. **Desktop fat JAR 自带 HTTP 异常类时身份不一致。** 真实 ClassLoader 回归证明其错误被
   当成 MalformedData。现只将 `eu.kanade.tachiyomi.network.HttpException` 精确共享为宿主类型，
   保留其 Int 构造器和 code ABI，不将整个 network 包改为父加载。

上述更改跨越 shared error variant 的穷举消费者、双端反馈和测试，文件数较多是同一错误契约
的必要传播，不是新增功能或无关重构。未改第三方插件、用户库、系统代理或正在运行的应用。

## 入口清单

| 平台 / 用户入口 | 已核对的 production 链路与边界 | 行为证据或验证层级 |
| --- | --- | --- |
| Desktop 后台下载、队列重试、已有图片地址 | DesktopDownloadManager → 共用源 response resolver → source.getImage；缺失 imageUrl 仍调用源解析；再走现有下载发布 | DownloadSourceRequestIntegrationTest；DownloadManagerTest 的源元数据、child-loader POST、取消/恢复；真实漫画柜 DI 下载 |
| Desktop 在线阅读、预加载、partial 在线补页 | DesktopReaderRuntimeFactory → DesktopReaderMaterializePorts → SourcePageFetcher → 共用 response resolver | DesktopReaderMaterializePortsIntegrationTest：真实 HTTP 解析、POST/body/token、child loader、401/403/429/500；SourcePageFetcherNetworkPolicyTest |
| Desktop 书库/详情/作者/搜索/历史/更新/迁移等封面 | typed DesktopSourceImage → production DesktopImageLoader → SourceManager → 源 client/headers | DesktopImageLoaderTest 真实 manager→loader，包括 getter 失败零网络请求、非 public 实现、缓存 |
| Desktop 页面保存、分享、复制、设封面 | ZoomablePageBox → ready image lease → PageContextMenu → share service，使用已加载像素 | 代码审查；既有 PageContextMenuActionTest，最终是否执行见下文 |
| Desktop 封面保存与分享 | MangaCoverViewerDialog 只允许成功 loadedImage → 保存/分享，无额外 URL 请求 | 代码审查；沿用已验证的封面加载入口，不冒称原生保存对话框已验收 |
| Desktop 漫画/章节外开 | externalUrlOpener → 系统浏览器，独立会话 | 代码审查；浏览器不承诺源 OkHttp/interceptor/POST 能力；未发现产品失败页外开图片入口 |
| Desktop WebView | WebViewCompat 明确不支持引擎操作 | 代码审查；不把不支持路径记为已验证下载路径 |
| Android 后台下载、重试、已有缓存 | DownloadManager → Downloader.getOrDownloadImage → source.getImage(page)；缓存分支读 ChapterCache | 代码审查；共用 HttpSourceApiContractTest 双 target 验证，未执行 Android 下载服务设备级场景 |
| Android 在线阅读、预加载 | HttpPageLoader → AndroidReaderPageFetchPort → source.getImageUrl/getImage → source.client + imageRequest | HttpReaderMaterializeAdapterIntegrationTest：真实 HTTP、headers/client、POST/body、legacy getImage、401/403/429/500 |
| Android 书库/浏览/详情等封面与封面对话框 | App 注册 Manga/MangaCover fetcher → MangaCoverFetcher → 源 client、源 headers、缩略图 GET | 代码审查；封面不是章节 Page，不应强行调用章节图片 hook；未新增封面设备级验证 |
| Android 页面保存、分享、设封面 | ReaderViewModel 消费 Ready page.stream | 代码审查；无第二次网络下载，不冒称设备分享目标已验收 |
| Android 失败图片外开 | Pager/DualPager/Webtoon → WebViewActivity(sourceId) → source.headers → WebView.loadUrl | 代码审查；保留静态 headers，但浏览器不承诺 source.client、自定义 POST/body/图片解密；未执行设备浏览器验证 |
| 双端源搜索、章节/页面失败及持久化 | SourceErrorMapper、NetworkErrorMapper、StoredAppError → 平台反馈/恢复 | AppErrorTest、SourceMangaSearchServiceTest、DesktopSourceQueryBehaviorTest、Android adapter/UI resource tests |

扩展目录/安装协议和 tracker 自有认证策略不等同于图源图片请求；其既有“验证失败/下载被拒绝”
提示未因此改成登录提示，也没有将本次 Forbidden 语义强行套用到无关服务协议。

## 维护约束

- 新增或改动图源图片消费者，应先核对上表入口，章节图片复用源 getImage，封面复用源
  client/headers；不只复制一个 Referer，不以全局客户端代替源专用策略。
- Desktop 跨加载器图片操作共用 response resolver，调用失败不能偷偷改成普通 GET。
  没有该 API 的 CatalogueSource 仍有兼容 fallback，其边界必须保留测试。
- 错误分类须保留 HTTP 状态语义；403 本身不能证明登录失效。Cloudflare challenge 仍使用
  既有响应特征判断，不把全部 403 当成 challenge。
- 修改完成的证据必须覆盖真实产品入口和接线；“源客户端选对了”的测试不能代替实际 headers、
  method/body、原始页面元数据及错误恢复测试。

## 本轮验证记录

过程日志位于本地 `.gradle-coordinator/`（不作为交付物提交）。

- `source-image-red`：新下载入口 3 项均因实际请求缺 Referer 失败。
- `source-errors-red`：domain 错误/回读断言 4 项正确失败。
- `covers-red`：9 项中 2 项证明 getter 失败被静默当成成功；非 public 用例原来就通过。
- `legacy-403-red`：旧持久化 Authentication(HTTP 403) 期望 Forbidden 的断言正确失败。
- `source-network-red` / `source-body-red`：源请求、响应体网络中断曾误报 Storage，两项回归正确失败。
- `extension-http-red`：fat JAR HTTP 错误经真实 ClassLoader/mapper 得到 MalformedData，正确失败。
- `source-images-focused-3`：Desktop 89 项、Android app 8 项通过；包含真实漫画柜 APK 转换、
  production loader/SourceManager/DI、队列执行和文件内容断言。
- `source-images-green` / `source-images-focused-2` 的有效未受影响证据：domain 19 项、
  source-api 共用契约 JVM 10 项和 Android 10 项通过。前述运行整体失败不可描述为整体通过。

测试编译前置曾被与本次无关的 Android `PagerRippleTest` 旧 API，以及同目录另一任务修改中的
`ReaderFullscreenTest` 阻塞。没有修改或回滚这些用户文件。临时 init 只从测试编译排除这两个
已证实阻塞，Android 通过 Kotlin compile task 的 exclude 生效；production 源/资源和所有其他
测试依赖不替换。临时配置位于 `app-desktop/tmp/`，不提交，也不作为新的长期验证系统。

`source-images-final` 格式化后复验通过：Desktop 94、Android app 8、domain 19、source-api
JVM/Android 各 10 项，合计 141 项；定向 Kotlin/XML Spotless 与 git diff --check 通过。
追加保存/分享及下载重试检查见 `source-images-adjacent`：页面菜单 10 项通过；下载重试的
两处旧 403=Authentication/登录通知期望已按新契约修正，`source-images-adjacent-final`
的下载重试 17 项、页面菜单 10 项和定向格式检查通过。本轮合计 168 项通过；追加只改测试期望，
未重复执行无关模块。

格式检查使用文件集合明确限定本次 37 个 Kotlin/XML 文件；临时配置首次过早应用插件失败，
一次字符串 pattern 造成过宽遍历后已通过协调器取消，改用显式 file collection 并通过，未改变
仓库构建配置。独立审查核对了源请求/网络 getter 失败不降级、取消与存储错误边界、以及 HTTP
异常精确父加载规则。代码和测试提交保护原有 AppVersion/全屏控制器/全屏测试及新增 adapter，
未将其他会话工作纳入本次提交。

体验包结果以独立构建日志和 preview-manifest 为准，不把单元/集成测试通过当作产物运行通过。
本次未进行全模块/全仓库测试、真机操作、
macOS 原生验收或稳定发布；生产运行包检查与用户界面验收须分别报告，不能相互替代。
