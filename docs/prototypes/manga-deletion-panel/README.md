# 读完即删 · 移动端留白提案 04

2026-11-17，待用户视觉审核。仅本地可交互 HTML；本轮未修改、构建或安装原生应用。

直接打开 `index.html`：Android 平板居中面板与手机底部面板并列。`device.html?screen=desktop` 保留桌面关闭按钮；`screen=tablet`、`screen=phone` 为 Android 视图。默认深色，可切换浅色，无 CDN、网络依赖或真实删除，刷新恢复样本。

## 依据和边界

- SOURCE：实现提交 5322c21abc 的 `DetailActionStrip` 即时写入偏好；Android `MangaDeletionSettingsSheet` 使用阅读设置同款 AdaptiveSheet，手机底部、平板居中，无保存／取消；Desktop `MangaDeletionSettingsDialog` 使用带关闭操作的居中窗口。
- SOURCE：平铺选择沿此前已审核的阅读模式式设计，三个互斥状态位于设置上方，开启才显示时机与书签设置；保留已有字号、主题、开关和选择组件映射。
- PROJECT_POLICY：用户要求增加移动端底部留白，本版手机和平板底部内边距均提议为32px，加 `env(safe-area-inset-bottom)`；这是本次提案，不声称32来自上游。最后设置行自身16px内边距额外保留。
- HTML_ADAPTER：CSS像素不等于原生dp；浏览器安全区在桌面通常为0，真实系统安全区需原生实现后验证。手机高度上限75%，小屏内容独立滚动；平板居中。原生dialog承担模态焦点隔离。

## 固定交互

模式、删除时机、书签开关修改立即更新当前预览的内存状态；Escape、遮罩或桌面关闭后重开仍保留。内层时机选择不同项即更新并关闭，选择原项不关闭，取消只关闭内层。只有开启展开子项，其他状态保留已设置值。双端状态与焦点隔离，主题切换不重置状态。刷新重置，不接真实偏好或下载文件。

## 打开与验证

从仓库根运行 `python -m http.server 53418 --bind 127.0.0.1 --directory docs/prototypes/manga-deletion-panel`，打开 `http://127.0.0.1:53418/`。现有本地服务继续用于审阅；所有资源标识v=4。服务记录在被忽略的 `.test-tmp/deletion-panel-preview/`，只停止记录并核对归属的本任务进程。

`browser.test.cjs` 导出 `verify(pw)`，在真实页面检查手机32px底部间距、贴底、无横向溢出、双端即时修改、关闭重开保留、内层返回、状态隔离与主题。旧版红测确认底部内边距16px且有保存按钮；新版以上检查通过。另在320×480视口检查75%高度上限、32px底部留白、内容可滚动及开关／模式可操作；深色截图实际查看。浏览器通过不代替原生验收。
