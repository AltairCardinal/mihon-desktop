## 1. 共享契约与产品基线

- [x] 1.1 归因并审计当前 Task 4A 未提交工作树，记录每轮 RED/GREEN 命令与结果
- [x] 1.2 用 Android 原版 fixture 完成页面、拆分、配对、导航、跳过、过渡、滤镜和预加载共享契约测试
- [x] 1.3 集中保护 Desktop 双页/edge matching、自动滚动、键鼠和右键保存产品行为

## 2. Shared core 与双端生产接线

- [x] 2.1 完成 common 页面/章节/过渡、PageTransform、ReaderNavigation 与 skip policy
- [x] 2.2 完成 PageDecoder、RegionDecoder、PageCache、预加载代次/取消/淘汰与字节预算契约
- [x] 2.3 让 Android pairing、transition、navigation、filter、skip、preload 和 decoder/cache 生产路径委托 shared core
- [x] 2.4 让 Desktop 三种 viewer、ScreenModel、设置和 Skia decoder/cache 生产路径委托 shared core

## 3. 内存、错误与用户体验

- [x] 3.1 验证真实大图走有界采样/region decode，普通缓存不长期驻留全尺寸 bitmap
- [x] 3.2 验证快速翻页取消旧请求、拒绝陈旧回填并淘汰旧窗口全部页面
- [x] 3.3 验证阅读器入口、加载、错误、重试、缺章和章节边界反馈可见可操作
- [x] 3.4 验证 grayscale/invert 持久化并在 Android/Desktop 实际渲染路径生效

## 4. 去重、追踪与验证

- [x] 4.1 删除 Desktop 已由 shared core 覆盖的拆页、导航和跳过规则，保留有证据的产品/平台层
- [x] 4.2 更新 parity 9、43、44、45、47、49、51、54 与 reader 架构文档
- [x] 4.3 运行 domain、Android reader、Desktop reader/UI/parity、Test Mode、Spotless 和 diff 检查，并自行部署 Android 模拟器完成 reader 运行时验收
- [x] 4.4 提交功能并通过独立规格/代码质量 review，修复全部 Critical/Important 问题
- [x] 4.5 使用 `scripts/build-desktop.sh` 构建并启动固定 EXE，核对完整版本、mtime 和窗口标题
