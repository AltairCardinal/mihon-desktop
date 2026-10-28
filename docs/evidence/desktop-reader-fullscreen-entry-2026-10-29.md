# 进入全屏的旧画布帧取证

范围：Windows 阅读器进入全屏时漫画先向上跳再恢复。使用应用内渲染插桩，未使用 Computer Use。
这些 PNG 是已完成 Skia Picture 的栅格化结果，未观测 GPU swap、DWM 或显示器呈现；用户现象尚待候选验收。

## 实际记录

- 源码候选：`5676f0eaa69d6c2327dadba2b947660683d8a973`，EXE 通过已核验的 JVM 属性启用诊断。
- 会话：`2026-10-28T21-14-56.358497800Z-c10f35dd-a93c-4a03-ba65-de8493158b5a`。
  位于 `app-desktop/artifacts/preview/windows/main-9a01606f27d948af8fe8ed27c525005a/reader-render-traces/`。
- RTL、双页开启、FIT_SCREEN、zoom=1、pan=0。请求 300 帧、保存 180 帧、缺失 120 帧、无事件丢失；
  缺帧均为 `capture-budget-exceeded`，manifest 为 PARTIAL。前两次进入的相关 PNG 完整，后续进入不能做像素结论。

第一次进入中，seq 294 开始原生变更，seq 295 返回时窗口客户区已经变为 1920×1080，
内部 Canvas 仍为 1904×993。seq 297 随即绘制 render 74，沿用旧画布与旧漫画 Picture。
seq 301 才开始下一轮最终布局，render 75 使用新的 1920×1080 画布及居中的漫画。

| render | 原生 Canvas 屏幕坐标与尺寸 | PNG | 漫画中心相对全屏顶边的几何投影 |
|---|---|---|---|
| 73 | 3848,1327；1904×993 | frame-000006.png | 527.5 |
| 74 | 3840,1296；1904×993 | 同一 PNG，像素完全一致 | 496.5 |
| 75 | 3840,1296；1920×1080 | frame-000075.png | 540.0 |

上表的投影由原生 Canvas 原点与图片绘制范围共同计算，不是显示器上的实测位置。
它显示旧内容先随原点上移 31 像素，之后才按全屏几何向下居中。第二次进入的 render 144–146
出现相同过程。完整分析报告保存在该会话的 `analysis.html`；分析器未检出 root 坐标回跳不代表稳定，
因为此次变化包含原生 Canvas 原点移动，而非仅 root 坐标移动。

## 对应实现与验证

实际依赖 Skiko 0.9.37.4 中，Direct3D 的 `SkiaLayer.reshape()` 顺序是父尺寸变更、
`syncBounds()`、`renderImmediately()`，随后才 `validate()`；内部 Canvas 的尺寸由 `doLayout()` 更新。
因此仅将递归失效和 `validate()` 提前仍会触发旧画布重绘，实测第一次尝试的相同回归仍失败。

最小调整是在成功的 Windows 无边框进入后，先通过公开的 `SkiaLayer.canvas.setBounds()`
同步内部原生画布，再执行外层布局。进一步的实际 PNG 回归发现，Compose 的
`ComposeContainer.setBounds()` 会先调整 SkiaLayer，之后才调用场景尺寸通知；仅同步 Canvas 的
首张全屏 PNG 中，漫画像素中心仍为 452.0，稳定后才为 1079.5。

最终在这一次同步布局中临时包装原 delegate：发生立即重绘时，先向已有父容器派发当前尺寸的
AWT resize 通知，使实际 Compose 场景消费新尺寸，再原样执行原 delegate。仅通知一次，布局结束
或失败均在 `finally` 中恢复原 delegate。没有替换真实画布或创建独立快照；原有下一事件轮的
最终布局、绘制和过期请求保护保留。

- `reader-entry-layout-red`：真实 ReaderViewport 回归先失败；新客户区宽 3840，render 7 画布宽仍为 519。
- `reader-entry-layout-green`：仅提前验证布局仍以同一尺寸差异失败。
- `reader-entry-canvas-green`：先同步内部画布后，相同回归通过。
- `reader-entry-layout-final`：增加真实 PNG 位置断言后失败，暴露上述旧场景中心；
  同轮原生画布边界和既有简化平移测试通过，不能用它们抵消这项失败。
- `reader-entry-scene-green`：提前通知现有 Compose 场景尺寸后，同一尺寸和 PNG 位置断言通过。
- `reader-repeated-capture-red`：真实阅读器连续三次往返，采集被旧 180 帧上限截断；
  提高到 900，保留 256 MiB 排队像素和 512 MiB PNG 限额，另行复验连续切换及显式预算耗尽。

最终定向验证用与实际故障相同的 1960×862 横向图片比例、按屏幕缩放去重的最多两块屏幕，
检查首张全屏 PNG 与稳定帧的实际漫画像素中心；同时覆盖单页／自动布局采集、原生多屏
画布边界和既有全屏控制器行为。实际命令、最终结果与候选源码以本轮协调器日志和交付清单为准。
没有执行完整模块或全仓测试，也不据此关闭用户报告的上跳问题。

`reader-entry-acceptance` 最终通过：7 项 nativeWindowTest、26 项 jvmTest，共 33 项，零失败、零跳过；
同次执行本次三个 Kotlin 文件的 Spotless 检查通过。100% 屏幕与原故障屏幕坐标相同，原生客户区为
`[3840,1296,1920,1080]`；150% 屏幕原生客户区为 `[0,0,3840,2160]`。

| 场景 | 首帧／稳定帧的漫画像素屏幕中心 Y | 完成帧数 | 缺帧 |
|---|---|---|---|
| 150% 横向图片进入 | 1080.0／1080.0 | 80 | 0 |
| 100% 横向图片进入 | 1835.5／1835.5 | 187 | 0 |
| RTL 连续三次往返 | 同次检查实际漫画存在 | 372 | 0 |
| LTR／AUTO | 同次检查实际漫画存在 | 121／102 | 0 |

显式一帧预算用例仍保存 1 帧、报告后续 211 帧缺失及 PARTIAL，符合预期；没有把它当作完整采集。
这些证据保存在 `app-desktop/artifacts/diagnostics/reader-entry-acceptance/`，未将用户漫画 PNG 纳入 Git。
