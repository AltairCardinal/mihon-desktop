# Android 阅读器初始反馈交接

`ReaderActivity` 在安装内容视图后、初始化作品和来源之前挂载既有 `ReaderProgressIndicator`。更换 viewer 时复用该中心圈，并让新 viewer 暂时透明；透明不阻止测量和布局，也不属于反馈就绪判断。

页列表完成只负责把章节交给 viewer，不能撤下中心圈。阅读区域的 pre-draw 读取当前安装的 viewer、ViewModel 中的 viewer 和当前章节，三者身份一致后才查询 `Viewer.isInitialPageFeedbackReady`。分页模式只接受选中物理位置、当前选中物理帧、页面对象及槽位一致的正尺寸实际 holder（同值描述对象可以替换，仍须通过帧身份和选中视口校验）；双页逐真实物理槽检查进度、错误或已呈现图像，空的一侧不产生反馈要求。半屏反馈宽度在 holder 的 onMeasure 中按本次视口宽度设置，避免首测时 holder.width 尚为零而把圈测成零宽；同值布局参数不重复请求布局。单页和长条模式沿用各自 holder 的反馈。就绪后在同一次 pre-draw 显示 viewer 并撤下中心圈，避免中心圈与页圈叠加或页表到达后的无反馈间隔。

这份判断不等待图片下载或配对认证完成，不修改配对、请求复用和阅读进度。它每帧读取当前视图事实，没有异步“就绪”通知，因此旧 viewer、旧章、邻页和已 detach holder 的迟到工作不能撤下新 viewer 的中心圈。Activity 销毁时移除监听。

初始章节失败交给既有 `ChapterError` 对话框并撤下中心圈；重试沿用现有 `Loading` 对话框和章节重载流程。每个新 viewer 的 `initialFeedbackHandedOff` 只在当前物理反馈通过 pre-draw 检查后置为 true；首次失败撤下中心圈不会提前置为 true，因此首次重试成功发布页表时会重新挂圈并等待真实 holder 布局。交接完成后的普通邻章更新和手势不重新挂圈。无 loader 的既有重试语义不由该交接层改变。

回归入口为 `ReaderInitialLoadingIntegrationTest`；真实 Activity 生命周期、ViewModel、ChapterLoader 和冷 HTTP 页表共同覆盖首帧、布局等待、双槽及失败反馈。

硬件 RenderNode 录制可能进入 alpha=0 的子树，`isShown` / `getGlobalVisibleRect` 不能证明像素已经可见。`ReaderPageImageView.prepareNativeImageDraw` 在既有 prepare/drawn 回调之前检查自身和祖先 alpha；透明期只允许布局和后台准备，不认证原生绘制。交接从 alpha=0 恢复到 1 时，Activity 对该 viewer 子树的既有图片调用 `invalidateImageDraw`，让透明期的空 RenderNode 在同帧重新录制；已经可见时不重复失效。此保护属于 Android 可见性 adapter，不改变共享配对、请求调度或阅读进度算法。原生像素回归用同一已挂载父容器和 RecordingCanvas 分别验证透明期无 drawn、可见期真实子 draw 与 drawn，并检查录制前没有旧绘制记录。

## 双页 holder 后续曝光

手动调整配对、最终布局或父节点平移可能让已经 Ready 的原生图片从窗口外进入视口，且不再产生 pager scroll/idle。原生子节点在不可见期间可能保留空 RenderNode，只有外包装 invalidate 不能保证重录。`DualPagerPageHolder` 在 attach 时注册 pre-draw，在 detach 时移除；每次只读取图片实际可见区域和祖先 alpha，记录未曝光到曝光的跃迁并调用既有 `invalidateImageDraw`。稳定曝光不重复失效，原 scroll/idle 的未呈现重试保持原样。

曝光通知不获取或刷新 presentation frame，不认证、不读取编码流、不重绑图片，也不伪造 prepare/drawn。实际像素仍通过原 SSIV 绘制 gate 和当前票据上报，原有 loading 只由真正绘制完成撤下。若仍未绘制，应保留反馈并检查原 gate，不能在通知里绕过认证或定时隐藏 loading。

回归 `DualPagerPageHolderLayoutTest.dual native exposure invalidates only once without scrolling or rebinding` 使用真实 holder、挂载的原生图片和窗口曝光，覆盖无 scroll/idle 的平移、子节点失效、稳定帧不循环，以及图片/票据身份和编码读取数不变。硬件 RenderNode 的缓存机制与受控 manual 曝光证据来自本次真机诊断；受控时序不是自然现场唯一原因的证明。
