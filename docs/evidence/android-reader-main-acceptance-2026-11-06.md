# Android 下载章节打开优化主干交付

2026-11-06，项目所有者验收平板候选 0.19.4-aex.118（code 136）的打开延迟，并要求合入主干、提交推送。交付范围为已采用的 B9 阅读器优化和无计时记录器签名构建。该候选源码为 4f311f611b，APK SHA-256 为 af531bc58383ae557ccdf670239e216d5d147a7201a9da56412496a484ee6eff。

## 整合边界

实际远端起点为 701080c251c8fd8b35d0b57a481d93b9a9eb3701，属于已验收候选的祖先。主干快进到候选，保留 P0–P4、已采用的 O2/O4/O8/O9 及其定向红绿、真机测量和否决记录。普通单页合并读取及 Compose rc01 候选已经撤回，其报告用于说明裁决，产品代码不含这两项实验。

原工作目录 D:/Shell/Github/mihon 中的本地 main 含尚未授权发布的扩展兼容提交和未提交草稿，整体保留在 codex/extension-compatibility-preserved-20261106，原目录继续持有原草稿。交付 main 使用干净工作树 D:/Codex/worktrees/mihon-main-reader-delivery-20261106。没有清除、覆盖、暂存或发布该扩展分支内容。

此批同步中英文 features/changelog，限定于验收过的 Android 打开延迟和候选版本。文档原有其余平台范围继续沿用既有验收。无新版本分配或重建 APK。

## 验证

复用已验收 APK 的正式构建、签名、保数据升级及设备回读记录。交付中重新核 APK 和 artifact.json 的 SHA，一致；DEX 不包含 Session、TargetBinding、reader-open-real 配置路径、benchmark 准备入口或记录器方法。保留的同名适配器仅有构造入口。生产与构建输入相对 4f311f611b 只补充一条局部 ktlint 命名豁免和文件换行规范，保护常量 enabled 的既有接口。该格式修正不改变 Kotlin 行为。

原先探针在 app/src/main，现位于 app/src/readerProbe；正式 candidate 属性选择 app/src/noReaderProbe 的常量禁用适配器。非 candidate 的普通构建仍可使用诊断源码，具体入口和失败处理已补充进 reader-open-latency-probe.md。

定向复验选择真实 Reader/Compose 和 ready 章节投影路径，共三项。首次格式检查揭示适配器 const 小写命名限制，补局部豁免后又发现 Windows 换行差异；最终将该文件规范为 LF。未修改功能断言或扩大测试选择器。

最终协调器 reader-main-accepted-final exit 0，XML 实际三条用例全部通过，无失败、错误或跳过；:app:spotlessKotlinCheck 通过。网站 npm --prefix website run docs:build 成功，内部断链检查开启；git diff --check 在提交前核对。过程日志保存在本交付工作树 .test-tmp/ 和 .gradle-coordinator/，不提交系统 trace 或私有漫画原图。

## 已有边界

此次不重测性能，不把历史跨批次数字相减。官方差距与近黑的测量分辨率仍以既有证据为准。自动匹配的已知回翻错单元等未在本批修复，验收不扩大为全阅读模式、所有设备或 P95 保证。没有新执行全模块、全量或正式发布矩阵。

推送采用 scripts/git-delivery.py 核对 main、远端起点、固定目标 SHA 和完整授权提交集合；只有远端实际包含交付目标后才报告推送完成。相关实验分支仅在确认已被主干完整包含且工作区干净后清理，保留有用户文件的工作树及安装包。
