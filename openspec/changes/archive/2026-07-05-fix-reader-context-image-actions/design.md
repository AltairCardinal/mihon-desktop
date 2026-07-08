## 修复方案

问题根因有三部分：

1. `PageContextMenu` 的菜单标题硬编码为英文，没有跟随当前中文界面。
2. `PageSaveHelper.loadImage()` 使用 `ImageIO.read(URL)`，`ImageIO` 对 WebP/AVIF 等格式支持不足，失败后返回 `null`，调用方直接 `return`，导致保存和复制操作静默无效。
3. 阅读器手写 `pointerInput` / `detectTapGestures` 点击导航只判断 `PointerEventType.Press` 或 tap 坐标，没有判断 `PointerButton.Primary`；macOS 和 Windows 的右键同样会进入 press/release 或 tap 流程，因此会触发左键阅读动作。

修复采用一个局部方案：

- 在 `PageContextMenu` 内集中定义中文菜单标题，并通过可测试 helper 生成菜单项标题。
- 在 `PageSaveHelper` 中复用已有 Skia 解码路径读取 URL bytes，再转为 `BufferedImage`，使保存和剪贴板沿用同一可靠解码结果。
- 继续保存到 `~/Pictures/Mihon`，继续复制 `DataFlavor.imageFlavor` 到系统剪贴板。读取失败仍不崩溃，保持当前失败边界。
- 在阅读器 tap 导航入口统一应用鼠标按钮过滤：只有 `PointerButton.Primary` 可以触发阅读器左键点击功能；`Secondary`、`Tertiary`、Back、Forward 不触发阅读器 tap 导航或双击重置。库列表的右键处理已显式判断 `PointerButton.Secondary`，此次不改普通 Compose `Button/IconButton/clickable` 组件语义。

用户可见行为：

- 入口：阅读器页面右键菜单。
- 展示：菜单显示“保存图片”“复制到剪贴板”；有封面回调时显示“设为封面”。
- 操作反馈：保存成功后仍尝试打开保存目录；复制成功后图片进入系统剪贴板。
- 鼠标行为：左键保持原有翻页/菜单/双击重置行为；右键只打开右键菜单，不再触发左键翻页、菜单切换或双击重置。
- 空/加载/错误边界：页面 URL 为空时不显示图片内容；图片读取失败时操作不崩溃但不会写入文件或剪贴板。
- 功能边界：仅对当前右键点击的页面图片生效；仍保存为 PNG 文件。

## 测试策略

- 单元测试先覆盖菜单标题必须是中文，避免英文硬编码回归。
- 单元测试覆盖 `PageSaveHelper.loadImage()` 能从 WebP 文件 URL 解码出图片。
- 单元测试覆盖阅读器 tap 导航只接受鼠标主键，副键/中键不返回阅读导航动作。
- 重跑相关 `app-desktop` 测试和格式检查。
