# 两项配对失败的契约与实际行为核对

日期：2026-11-11。核对基线 `7bf241f9e0eb0598001db134505cfc59673e3cd1`，承接[原生设置验证中保留的两项失败](reader-settings-native-2026-11-11.md#两项已有失败)。用户本轮要求区分过时测试与产品缺陷。

## 结论

两项均为过时测试，所验证产品路径符合当前契约。本轮只修改测试及证据，未改变生产配对、曝光或进度算法。

### 1. AdaptiveReaderViewportTest：旧分组与旧进度 oracle

依据[非阻塞设计 4.1、4.2、4.4](../2026-11-01-reader-nonblocking-pairing-design.md)和[共享核心当前契约](../architecture/reader-shared-core.md)，默认分组从章首推导，不因恢复目标或显示历史改变最终配对；已结算位置不能因单双页／几何变化重新制造阅读事件。

测试使用无自动边缘匹配、无手动开篇覆盖的五张竖图。0-based 正确组为 `[0][1,2][3,4]`。旧断言要求恢复目标 2 与 3 成对，还要求 resize 后事件从 `[2]` 变为 `[2,3]`，沿用了早于当前契约的行为。

保留真实 ReaderContent、image pipeline、Skia、Compose 场景与指针点击，以五种色图区分源页；新增实际像素与 owner 已绘历史交叉断言。`adaptive-canonical-diagnostic-red` 跑完整条链后，只有保留的两个旧 oracle 失败，其他检查通过：

- 窄屏实际只有源页 2；宽屏实际显示 1、2，逻辑锚点仍为 2。
- 菜单变化不替换已显示 frame，缩放与重绘不追加阅读事件；事件保持 `[2]`。
- 返回窄屏仍显示 2；两次真实点击分别显示 3、4，事件为 `[2,3,4]`，已报页集合逐次只含对应真实绘出页。

据此修正旧 oracle 并保留全部新增检查。`adaptive-canonical-green` 1/1 通过。

历史定位：`c729f2f33b`（2026-10-05）曾将 `[1,2]` 改为 `[2,3]`；后续规范配对与物理帧协议已取代旧显示历史分组语义。本轮没有对历史版本做二分，因此不把这一时间线称为已确认的首次失败提交。

### 2. ReaderScreenModelTest：漏模拟实际曝光回调

原方法手工构造 shared native presenter 后直接 `begin → prepare`。`63cef8f1cc`（2026-11-01）已将 shared mount 的初始曝光设为 false；真实 DualPagePagerViewer 经布局／页面准备回调设置曝光，旧夹具没有执行这一步。

原样 RED 复现 prepare=false。修正夹具时先保留负向门禁：未曝光时 prepare 必须 false、共享已绘历史为空、进度为空；再调用实际 setExposed(true)，原先的 partner prepare/draw、用户确认及仅一次进度记录断言全部通过。匹配 OFF/ON 两个参数均通过。读取用户匹配偏好是额外的测试不隔离风险，本轮使用隔离 DesktopPreferenceStore；它不是此次失败的直接原因。

新增真实 ImageComposeScene 场景补验，不手调曝光：

- 挂载实际 DualPagePagerViewer，由生产布局回调完成曝光。
- 目标页始终 Queued 且从未解码，只有伙伴页的品红 Skia 图像实际绘出并进入 presented 集合。
- 用户确认前后各 8 个稳定帧，当前画面持续存在伙伴像素，目标仍 Queued。
- 确认前无进度；确认后只记录实际绘出的伙伴一次，后续帧不重播。
- 匹配 OFF/ON 均成立；render Image 与 bitmap 都显式关闭。

## 最终验证与交付边界

`pairing-contract-final` 在最终测试代码上同次执行 focused ktlint 与 5 个行为用例，全部通过，退出码 0，17 秒：

| 用例 | 数量 | 结果 |
| --- | ---: | --- |
| 实际 resize／像素／进度／点击链路 | 1 | PASS |
| 手构 presenter 未曝光／曝光后确认，匹配 OFF/ON | 2 | PASS |
| 实际挂载／持续像素／Queued 目标／确认，匹配 OFF/ON | 2 | PASS |

原样失败证据为 `pairing-failures-current-red`（2/2 失败）。完整命令和退出码在忽略目录 `.gradle-coordinator/<key>.json/.log`，最终 XML 在 `app-desktop/build/test-results/jvmTest/`。后续新增负断言曾错误地把候选 visiblePageIds 当作已绘页集合；实际前后候选不变，已改为检查共享 drawn 历史与真实像素，未降低曝光或进度门禁。

本轮执行离屏 production 实现和真实指针／绘制链路，使用合成图像。未运行完整模块、全量测试、设备安装或正式构建；结论限定于这两项失败对应的条件，不外推其他配对／加载问题。
