# D10 Windows 下载 CBZ 与首次单页视口

## 当前边界

本页随 [执行路线](../roadmap/2026-11-07-windows-reader-cbz-first-page.md)维护。本轮实施中；尚未完成产品窗口采用裁决，不能据组件测试宣布首开达标。只做 Desktop focused 测试、普通 preview 与指定窗口采样；不全量、不 Android 构建、不正式发布、不推送，公共能力说明保持原样。

## 固定输入与源码

- 指定工作树 `D:/Codex/worktrees/mihon-main-reader-delivery-20261106`，开始时分支 `local/windows-cbz-plan-20261107`、HEAD `f67c1d5edf9c5a03ae288d31f1231e8d1c31a3fc`，工作树干净。
- 本地 main 后续为 `851081066770d135fff50f04f67249080c4771bd`，相对计划基线只有文档变化；隔离分支合入得到 `815ddc028993ff2237abd8ff486acdd2a1350a80`。产品代码与 `ae5bb3bb5aca9e84e6616a1183129e6f0a25e77b` 相同。main 在另一工作树上使用，本轮继续隔离，未改其分支或工作目录。
- 控制快照 `D:/Codex/worktrees/mihon-cbz-d10-control-20261107` 固定于 `815ddc0289`；控制和候选不能引用外置归档 agent 或通用反射预热。
- 本机真实 CBZ SHA-256 实际核对为 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。不提交漫画、截图或视频；目标是 index 4 地图，187 页，单页 RTL。
- 原型 ZIP SHA-256 实际核对为 `716d54e3515b33a1dd8afe06252879556acb8837204b215365a688c9757f6733`，容器参考 `904bc8d34308`、`3b2040111964`。只移植有关组件差异，保留当前章节按钮与阅读流的独立回调。

## 真实行为红绿

本机协调器日志位于 `.gradle-coordinator/`；原件可能不随克隆分发。以下没有将编译失败作为行为红测。

| 范围 | 红测事实 | 绿测与结果 |
|---|---|---|
| 首屏 Queued holder | `d10-slot-red2`：真实 Compose 第 5 页排队时 `HOLDER_CREATE_BEGIN` 期望 1、实际 0 | `d10-slot-green`：同对象 Ready、Skia 实际绘出、movable content 交给分页器、退出关闭通过 |
| 正常 ReaderContent 及早翻页 | `d10-slot-navigation-red`：用户转到第 6 页后仍留在 startup viewport | `d10-slot-lifecycle-green`：下载入口限定、早翻页交接、迟到旧 Ready 不读图、关闭/换代/重试撤销、绑定幂等通过 |
| 相邻章节与键盘 | 复用当前 production wiring | 同一 focused 轮 36 项全部通过：StartupSingleViewport 2、PresentationImageOwner 13、ChapterTransitionIntegration 15、SinglePagePresentationIdentity 3、KeyboardNavigationPosition 3 |
| 下载归档后端 | `d10-miniz-red`：真实下载路由持有 CommonsZipArchiveLease，未命中拟接入的 miniz | `d10-miniz-boundaries2`：归档 53/53 通过；`d10-miniz-ui-draw-green`：原生 13/13 与真实 UI draw 1/1 通过 |

Windows 首次 `./gradlew.bat` 写法被协调器传给 cmd 后未启动 Gradle，退出 1；改为 `gradlew.bat` 后正常执行。该准备错误没有计为产品红测。

## 归档组件证据与审查

`miniz/` 的 11 份构建源码/头文件逐字节等于已校验实验 ZIP，许可证随源保存。JNI 注册表 ID、共享句柄所有权、元数据上限、原条目次序/带符号 CRC 指纹、流式写入/CRC、取消和回退经一轮独立审查；未发现 native 主体确定阻塞。审查提出的首次界面边界由主协调者独立核对后收口：不能用 `withFrameNanos` 证明 draw 已发生；真实 Compose 红测抑制三帧 `drawContent` 仍观察到旧代码启动加载，改为真实内容 draw 完成后幂等通知，Preparing/Failed 不触发。它不证明物理屏幕扫描已经完成。

13 项原生用例包括真实下载端口、非零 lease generation、未开始/加载中/不可用回退、非支持格式、65 MiB 流式页、CRC/写失败、等长同 mtime 替换、在途 release、旧 handle/index 拒绝与取消清理。一次整卷原生/旧后端逐页 SHA 对照 **187/187** 一致，共 **41,348,502** 字节；图像 SHA 链 `f8e27dfef45554ae098e3d9e0ba8612763275d6bd0c7e527d8dca45079255bac`，地图 SHA `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`。后续窗口计时不重复解整卷。

本机构建 DLL 266,752 字节、x64，SHA-256 `ba0d60bf027f23ebe3c60626f70e19d94a3662a7c25c8b0e82090a4b63bc1666`，仅依赖 bcrypt/KERNEL32，静态 MSVC CRT。构建清单含本次源码、脚本、工具链、pin、ABI 与 DLL 摘要；缺 DLL 回退测试不等于打包通过，包内位置与正常窗口命中仍待下节。

首次边界轮有两项额外失败并已说明：回退格式误以显式 SevenZip 链作为默认 Commons 的对照，改为实际默认选择链后通过；真实 HTTP retry 夹具在后台镜像发布 raw pages 时立即取 viewport，尚未等同代派生身份/位置，等待同代位置后通过。后者没有挂载 Compose，不能归因首屏组件；本次不改模型生产行为。整卷与最终 XML 留在 `.test-tmp/d10-miniz-final-xml`，归档集成 XML 在 `.test-tmp/d10-miniz-boundaries2-xml`。

格式检查：本任务新增文件与修改行按 ktlint 1.8.0 检查；非 vendor 的 `git diff --check` 通过。上游原样 miniz.h:577 自带行尾空格，保留该固定输入，不将其改成产品修补；局部 Git 属性同时保留原行尾。

该批超过通常文件/行数估算的原因是 vendored miniz 及 JNI/构建/租约/接线必须作为一个可重建单元审查；没有拆出第二套归档状态系统或持久化索引。

## 首屏容器的实现边界

仅已下载、分页单页、无自动拆页、首次显示使用轻量视口；双页及条漫沿原链。Queued holder 已注册 owner/page/generation/attempt/split/bounds 身份，没有 encodedRef 时不创建 region、不 acquire。Ready 经 owner 核对当前代次后一次绑定真实 decodeKey，重复相同绑定不再创建 region；已绑定的不同内容键拒绝。

同一 registry 的 movable content 在首绘后转入完整分页器，保留真实 holder。用户在首绘前改变页码、选择跨章过渡或换模式，也永久交给本代完整分页器；不在旧 Ready 到达后返回首屏。旧 slot 关闭、owner 换代或 attempt 推进均拒绝晚到绑定。实际绘出才报告进度。此处先记录候选行为，采用与否须由下面的产品样本决定。

## 产品样本与采用裁决

待产品 preview、两个轻量观测首次、外部 A/C 与 C/A、双页匹配开/关恢复及往返完成后填写。归档 19.620 ms、holder 创建 43.065 ms、实际挂载、点击到首绘、F/R/K 分开判断；未测项保持未测，不由历史实验替代。
