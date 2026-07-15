## Context

源与扩展横跨 `source-api`、网络、扩展仓库、安装器、ClassLoader、Android 包管理和 Desktop APK→JAR 兼容层。业务状态和安全规则可以共享，但实际安装、加载和登录挑战必须由平台 adapter 完成。仓库已经有可复用的 `SourceMangaSearchService`、扩展仓库领域层、Desktop 事务替换、APK→JAR、Cookie jar 和 FlareSolverr 后备，不应再建立第二套链路。

## Goals / Non-Goals

**Goals:** 统一源浏览/搜索状态、扩展生命周期和安全规则；保留并验证 Desktop APK→JAR；建立浏览器登录/Cookie 回传；用真实扩展证据收缩 compat stub；让 Android 与 Desktop 消费同一领域结果。

**Non-Goals:** 不复制 Android PackageInstaller/WebView；不承诺支持未通过真实 fixture 验证的扩展 API；不把 FlareSolverr 设为默认业务路径；不在本 change 重做与源/扩展无关的设置或平台集成。

## Decisions

1. 在 common/source-api 中共享源查询状态、分页结果、扩展目录状态、版本、安全、偏好 schema 和错误；平台 adapter 只负责文件、包、ClassLoader 与浏览器 side effect。
2. 源查询以现有 `SourceMangaSearchService` 为唯一请求入口，补充共享分页与错误映射，不复制源调用规则。
3. 安装与更新采用 `prepare → validate → commit → reload → rollback` 事务；只有 reload 成功后才发布 Installed，任一步失败都恢复旧版本与 sidecar。
4. 信任由仓库身份、声明摘要、下载摘要与已安装来源连续性共同决定；缺少可验证摘要时不得把“仓库身份相同”表述成 APK 签名等价。
5. Desktop 登录优先通过可取消、可超时的浏览器会话完成 Cookie 回传；手动导入与 FlareSolverr 是显式后备，取消/超时不得写入半成品凭据。
6. compat stub 使用“真实受支持扩展调用 + 自动化保护测试”白名单；未被引用的 stub 不得扩张，确认无调用后删除。
7. UI 继续使用现有源列表、单源浏览、全局搜索、扩展列表/详情/设置入口，但 ScreenModel 改为消费共享状态；保留宽屏布局、APK→JAR、文件工具和 Test Mode 等 Desktop 产品能力。
8. Android 源/扩展运行时通过自行部署的模拟器安装当前 APK 和代表性扩展验证，避免只从 JVM 测试外推 PackageManager/ClassLoader 行为。

## Risks / Trade-offs

- **第三方扩展行为不可控：** 用代表性 APK/JAR fixture、隔离加载、兼容矩阵与逐项失败反馈限制影响。
- **安全校验与旧扩展兼容冲突：** 明确信任迁移，失败时保留旧版本，不以兼容为由降低校验。
- **浏览器会话无法回传：** 提供取消、超时、手动重试与显式后备，不静默降级。
- **范围较大：** 按契约与 fixture、共享查询、扩展事务、平台登录、UI wiring、去重验收六个可独立审查批次推进。

## Migration Plan

先锁定 Android 权威行为和 Desktop 产品增强，再让新旧 Desktop 路径对同一 fixture 双轨比较；随后依次切换查询 ScreenModel、扩展目录/事务协调器和挑战登录。每个切换点通过测试后删除对应重复规则；安装事务成功前始终保留旧产物。最后更新 parity manifest、执行 Android 模拟器与 Windows/macOS 产品验收。

## Open Questions

代表性扩展集合由当前测试资源、已安装扩展目录和可稳定获取的官方仓库产物共同确定。纯 HTTP 扩展为最低必测集合；QuickJS 或 Android-only AAR 扩展只有在存在 JVM 可用实现时才进入支持矩阵，否则以明确“不兼容”结果结束，而不是扩张无证据 stub。
