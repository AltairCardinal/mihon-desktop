## Why

桌面阅读器右键菜单中的 “Save Image” 和 “Copy to Clipboard” 仍为英文，和当前中文界面不一致。同时这两个操作依赖 `ImageIO` 从页面 URL 重新解码图片，遇到 WebP/AVIF 等阅读器常见格式时会静默失败，用户点击后没有可见结果。验证时还发现阅读器自定义点击导航没有区分鼠标主键/副键，右键打开菜单时会同时触发左键阅读器点击效果。

## What Changes

- 将阅读器页面右键菜单的图片操作文案改为中文。
- 复用桌面端已有 Skia 解码能力加载页面图片，覆盖 PNG/JPEG/WebP/AVIF 等阅读器支持格式。
- 保存图片和复制到剪贴板继续使用现有右键菜单入口，不新增能力或入口。
- 审查桌面端手写鼠标指针处理点；阅读器自定义 tap 导航仅响应鼠标主键，副键/中键不触发左键阅读功能。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- 无。此次是既有桌面阅读器右键菜单行为修复，不改变 spec 级能力边界。

## Impact

- 影响桌面端阅读器右键菜单、页面图片保存/复制 helper 和阅读器自定义鼠标点击导航。
- 不涉及数据库、HTTP API、导航、DI 或 public API。
