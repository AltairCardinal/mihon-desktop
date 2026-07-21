# Brainstorm Summary

- Change: align-sources-extensions
- Date: 2026-07-15

## 确认的技术方案

采用“共享业务核心 + 薄平台适配器”。源查询复用并扩展现有 `SourceMangaSearchService`；扩展发现、版本、安全、安装/更新事务和回滚状态进入共享层。Android 仅保留 PackageManager/PackageInstaller/WebView side effect，Desktop 仅保留目录、ClassLoader、APK→JAR、浏览器会话和系统文件操作。现有源/扩展 UI 入口保持不变，ScreenModel 改为消费共享状态。

用户已明确要求：除技术栈或平台能力强制不同外，Desktop 独有重写都向原版方式对齐；流程性确认项由 agent 自行权衡，不再暂停。因此本方案按该授权进入文档与实施计划。

## 候选方案与取舍

1. **共享业务核心 + 薄适配器（采用）**：最大限度消除并行规则，同时保留 APK→JAR、宽屏 UI、文件工具和 FlareSolverr 后备；迁移面较大，但可按契约分批切换。
2. **只建立 parity façade，保留 Android/Desktop manager**：短期风险较小，但继续维护两套版本、错误和事务规则，不符合技术债清理目标。
3. **直接把 Android manager 移到 Desktop**：表面最接近原版，但会把 PackageManager、PackageInstaller、WebView 等平台类型带入 JVM，形成新的兼容债。

## 关键取舍与风险

- 安装/更新使用 `prepare → validate → commit → reload → rollback`；只有 reload 成功才发布 Installed。
- 仓库身份连续性不冒充 APK 签名；摘要缺失时明确显示信任边界。
- 浏览器登录可取消、可超时，Cookie 只在完整会话成功后提交；FlareSolverr 仅显式后备。
- compat stub 必须同时有真实受支持扩展调用证据和自动化回归测试，否则不扩张并在确认无调用后删除。
- QuickJS/Android-only AAR 没有 JVM 实现时返回明确不兼容，不用无证据 stub 伪装支持。

## 测试策略

- RED：共享分页/空状态/错误，MockWebServer 403/429/500/畸形与多仓库部分失败。
- RED：JAR、APK→JAR、损坏产物、摘要/信任冲突、版本替换、reload 失败回滚、不兼容 API。
- GREEN：Android/Desktop production wiring、DI、导航、Screen 实例化、i18n 缺 key、Test Mode。
- 产品保护：APK→JAR、宽屏源 UI、扩展详情/文件工具、FlareSolverr 后备保持零回退。
- 运行时：Android 模拟器安装当前 APK 与代表性扩展；Windows 固定未打包 EXE；macOS 复核平台适配。

## Spec Patch

- 补充多仓库部分失败必须保留成功结果并逐仓库报错。
- 补充信任不连续与 reload 失败时的原子回滚场景。
- 补充本 change 触达文案的 i18n 完整性要求。
