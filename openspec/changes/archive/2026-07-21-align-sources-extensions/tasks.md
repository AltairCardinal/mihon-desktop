## 1. 权威 fixture 与保护网

- [x] 1.1 盘点 Android/Desktop 源、扩展、compat stub 与真实扩展调用链
- [x] 1.2 为源分页成功/空/403/429/500/畸形响应写共享与 MockWebServer RED 测试
- [x] 1.3 为 JAR、APK→JAR、损坏产物、版本替换、回滚与不兼容 API 写 RED 测试
- [x] 1.4 固定 Desktop APK→JAR、宽屏源 UI、文件工具与现有扩展产品行为基线

## 2. 共享源与扩展核心

- [x] 2.1 复用并扩展 `SourceMangaSearchService`，完成共享 query/request/page/empty/error core；源列表与 presentation wiring 不包含在本项完成证据中
- [x] 2.2 提取扩展发现、版本、安全、安装/更新事务与回滚状态到共享层
- [x] 2.3 让当前 Android consumer 与 Desktop consumer 的 production manager/ScreenModel 消费相同共享状态和错误
  - [x] 2.3.1 Android/Desktop 源查询已消费共享 query/page/error core
  - [x] 2.3.2 Android/Desktop 扩展链路已消费共享 catalog/version/security/install transaction core
  - [x] 2.3.3 当前 Android extension presentation consumer 接入固定 main 提取的共享契约
  - [x] 2.3.4 Desktop source result consumer 完成 canonical persistence、观察与导航 wiring
  - [x] 2.3.5 Desktop extension presentation consumer 完成 production wiring

## 3. Desktop 平台适配

- [x] 3.1 将 Desktop loader/installer 收敛为目录、ClassLoader、APK→JAR 与隔离 side effect
- [x] 3.2 实现仓库身份/摘要信任、下载校验与 reload 失败原子回滚
- [x] 3.3 实现可取消/超时的 Desktop 浏览器登录与 Cookie 回传；FlareSolverr 仅作显式后备
- [x] 3.4 将源列表、单源浏览、全局搜索、扩展详情/设置接入共享 ScreenModel 与 UI 状态；production wiring、当前 Android consumer 模拟器证据与最终 Windows/macOS 运行验收均已闭合
  - [x] 3.4.1 单源 canonical result 与全局搜索已有 fixed-main fixture、shared query/page/error output、当前 Android consumer 和 Desktop production wiring 四层证据；last-used/pinned/language 列表投影由当前两端分别从同一 fixed-main fixture 实现，不冒充 shared output
  - [x] 3.4.2 Desktop extension presentation、详情源状态/obsolete/NSFW 反馈及列表动作路由已有同样四层证据，并保留 Desktop 独有文件与事务能力
  - [x] 3.4.3 使用最终提交完成 Windows/macOS Desktop 构建运行验收，并与当前 Android consumer 已有的代表性源/扩展模拟器证据共同核对；该证据不构成原版权威
- [x] 3.5 将触达的 Desktop 文案迁入 i18n，并覆盖资源缺 key

## 4. 去重与验证

- [x] 4.1 依据真实扩展调用证据删除无使用 compat stub，以及重复搜索、版本和错误规则
- [x] 4.2 更新 parity 28–40、87 和维护文档
- [x] 4.3 运行 extension/source/network/DI/navigation/Test Mode 与产品回归矩阵
- [x] 4.4 自行部署 Android 模拟器，安装当前 APK 与代表性扩展并验收源/扩展真实路径
- [x] 4.5 提交并通过独立规格与代码质量 review
- [x] 4.6 使用构建脚本和固定 EXE 验收源/扩展关键用户路径，并在 macOS 复核平台适配
