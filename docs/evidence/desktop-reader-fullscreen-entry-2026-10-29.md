# 进入全屏的旧画布帧取证

范围：Windows 阅读器进入全屏时漫画先向上跳再恢复。使用应用内渲染插桩，未使用 Computer Use。
这些 PNG 是已完成 Skia Picture 的栅格化结果，未观测 GPU swap、DWM 或显示器呈现。
用户已确认 `56b451bb87` 候选仍然上跳；以下回归只解释并修正了一项绘制阶段缺陷，未关闭原问题。

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

## 候选复现仍失败后的记录

`56b451bb87` 的实际用户会话为 `2026-10-28T21-48-07.272590Z-493e7b75-8ffc-4868-bce2-e69c9f51a0f6`，
位于 `app-desktop/artifacts/preview/windows/main-af75317c4393407187b55692082346fd/reader-render-traces/`。
513 帧全部写入，缺帧和丢事件均为 0。五次进入全屏的首个 Picture 为 render 129、189、239、325、416，
均直接使用 1920×1080 画布，漫画绘制范围从 Y=118 到 962，中心 Y=540；此前的旧尺寸进入帧没有再出现。
用户仍观察到跳动，因此这些应用 Picture 不能解释当前现象，也不能据此判断窗口呈现稳定。

下一层取证使用[窗口合成帧采集](../architecture/desktop-reader-render-trace.md#窗口合成帧补充)，
与应用 Picture 保持独立帧号，按 QPC 时间对照；没有继续凭同一组应用尺寸回归推断原问题已修复。

## 窗口合成层的实际复现与对应修复

同一 `56b451bb87` 候选的用户会话
`2026-10-29T04-18-19.167280600Z-0a66429c-c345-4aab-b81c-f35c2b1f0a5e`
同时记录应用 Picture 和 WGC 窗口帧。应用 414/414，窗口 388/394；窗口整体为 PARTIAL，
缺失的 374、376、378、379、381、382 均在四次全屏往返之后，四段进入区间未报告采集缺口。
取证仍未观测显示器扫描输出；采集开销可能影响时长。

| 次数 | 首个中间窗口帧 → 首个稳定窗口帧 | 会话起点后的时间 ms | 已观察的中间区间 ms |
|---|---|---|---|
| 1 | 33 → 35 | 830.37 → 872.03 | 41.66 |
| 2 | 98 → 100 | 1988.69 → 2030.35 | 41.66 |
| 3 | 180 → 181 | 3422.00 → 3446.99 | 24.99 |
| 4 | 248 → 250 | 4605.32 → 4646.98 | 41.66 |

中间帧包含旧尺寸内容在新原点的版本（漫画 Y=79..914）及其非等比拉伸版本（Y=86..994），
随后才显示正确的新布局（Y=118..961）。在 x=40..1879、y=150..899 的漫画区域，窗口帧 33
与上一张旧 Picture 拉伸后的 RGB 平均绝对误差为 0.551/255，与正确新 Picture 的误差为 22.853/255；
窗口稳定帧 35 与新 Picture 的误差为 0.565/255。第一轮 829.11ms 已记录正确的新漫画绘制命令，
但 830.37ms 的窗口帧仍是旧内容。这将本轮修复限定到原生窗口变化与真实画面呈现之间的衔接。
原始记录及 `entry-evidence.json`、`entry-evidence.md` 保存在该会话目录。

`ReaderFullscreenCompositorTest` 在生产 controller、Compose／Skiko 和真实 Windows adapter 上，
使用可注入的屏幕外目标边界复现；真实绘制延迟 45ms，确保切换不能只靠在一次刷新内恰好完成。
首次有效红测录到全屏漫画上缘 `[80, 118, 118, 118, 118]`，失败于真实 WGC 像素位置断言。
红测 XML 与 PNG 保存于 `app-desktop/artifacts/diagnostics/reader-compositor-red-20261029/`。

修复在 Windows adapter 保存原始样式后暂缓原生窗口重绘，并维持过渡样式中的可见位清除，
完成最终实际渲染后恢复；仅入口开始暂停，其他主窗口流程不接入。资源完成与失败处理由现有
controller 的最新过渡回调统一收口。退出原样式保存不带暂停造成的临时隐藏位，避免退出后窗口消失。
对应测试扩为三次进入／退出，同时断言所有接收帧仍有漫画、每次全屏位置正确、退出后原生可见；
另检查隐藏、销毁、重复关闭、绘制失败及过期回调的清理。

接口依据：[WM_SETREDRAW](https://learn.microsoft.com/en-us/windows/win32/gdi/wm-setredraw)。
它明确区分保留屏幕内容与临时移除 `WS_VISIBLE`；本实现不对原先隐藏的窗口发送恢复消息。
本轮回归可证明上述真实合成场景的改动前后差异，仍须用户确认新候选中原有漫画的体验，
不把原生回归、内部 Picture 或体验包构建通过当作用户已经验收。

最终定向验证：7 项原生测试与 27 项 JVM 测试全部通过，7 个本次 Kotlin 文件的 ktlint 1.8.0 检查通过，
`git diff --check` 通过。原生范围为合成帧三次往返、普通／最大化画布边界、暂停资源生命周期，
以及真实阅读器进入全屏；后者分别保存 74/74 和 189/189 个 Picture，均无缺帧和丢事件，覆盖两种实际 DPI。
JVM 范围为 `ReaderFullscreenFramePresentationTest`、`ReaderFullscreenTest`，未运行模块全量。
命令及结果见 `.gradle-coordinator/reader-redraw-verified.{json,log}`；本轮最终合成 PNG 和 XML 归档于
`app-desktop/artifacts/diagnostics/reader-compositor-green-20261029/`。

补查曾发现 3 个及 1 个 `outer-picture-not-completed` 缺口，均出现在测试的 `session-close` 前。
原因是测试先销毁窗口，释放 Skiko Picture，排在销毁之后的最后一个完成回调才尝试截图。
测试现先在 EDT 排空 trace 再销毁窗口；保留原先零缺帧断言，production 诊断对真实关闭期间不完整记录的
PARTIAL 处理不变。相关记录保存在 `reader-redraw-trace-20261029/`，最终记录位于
`reader-redraw-trace-final-20261029/`，均在 diagnostics 目录下。

本批 9 个文件共同覆盖一个呈现修复：原生暂停、已有 controller 接线、真实合成与生命周期回归、
诊断测试收尾及两份既有维护文档；没有新增独立呈现系统或修改 Android、窗口启动流程和公共功能承诺。
