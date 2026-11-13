# 同步决定收尾与尝试状态修复

源码基线为 `3cd5a837d0a1fc97239e9c59f17d7827e7ff7559`，在独立 worktree 实施，未引入原工作区其他未提交内容。现场只读记录显示：两个已收到的批次共 253 条事件均已索引，除两个 `FAVORITE` 字段的 `DECISION/dirty=0` 外均已应用；两字段已有匹配当前 heads 的 `CONFIRM`，本地收藏已取消，待确认列表为空。此状态导致同一 run 停留于 `PARTIAL/projection_pending`、2170/2423，重复尝试无法处理孤儿字段。本轮未直接改写用户 profile。

修复沿用真实投影、当前 binding、活动空间和 owner 栅栏。新决定在事务内收尾；旧孤儿重新投影；启动及决定后的本地回执确认不访问 HTTP 或安全存储。schema 47 保存尝试起点、确认基线和暂停基线；旧尝试事实未知时不编造显示。共享 UI 区分总体进度、本次新增确认、等待本机/图源恢复、临时连接问题及真实无效事件。设计边界见架构指南对应章节。

验证按最小充分范围执行，未运行完整模块、全仓测试或正式发布矩阵。

| 协调器记录（忽略目录 `.gradle-coordinator/`） | 实际结果 |
| --- | --- |
| `sync-decisions-red` | 存储、重试、计数/时钟与 UI 取得行为红；首次迁移夹具遗漏旧 trigger，属于夹具错误，不作为产品红证据 |
| `sync-decisions-recovery-red` | 修正的真实 schema 46 文件升级、Runtime 启动恢复、临时网络反馈三项取得正确红 |
| `sync-decisions-boundaries-red` | 即时本地收尾、已应用回执启动恢复、新结构拒绝、真实无效事件统计和 UI 分类五项取得正确红 |
| `sync-decisions-green` | 一个兼容检查调用遗漏新必填参数，编译失败，未运行测试，不计验证通过 |
| `sync-decisions-green-2` | JVM data 74 项、共享原生 Compose 52 项，共 126 项；122 通过，4 失败 |
| `sync-decisions-platform` | 4 项失败对应补验全部通过；另执行 Android 38 项 inbox、1 项 panel、4 项 runtime，共 47 项全部通过，零跳过 |

四项失败中，两个历史 schema 回建夹具未移除后续版本的既有列，分别导致重复 `snapshot_field`/`opening_mode`；仅修正夹具以构造真实旧结构。另两个断言仍要求旧的“整轮时钟/累计确认”显示，已按新尝试语义更新，并保留真实 controller 的暂停、关闭重开和结果接线断言。后续仅修改这些测试夹具/断言，未改动已通过范围的 production 实现。

因此共有 **169 个平台/用例组合具备有效通过证据**，来自首轮结果与上述定向补验的组合，不能描述为最终代码单次重新通过 169 项。原始 XML 与日志由各模块 `build/test-results`、协调器 key 保存。关键范围包括整个 inbox 共享契约、受影响 runtime/panel 用例、RunStore、迁移/兼容检查、ProgressPresentation、ProgressControllerIntegration 和受影响 Content 用例。

最终 19 个改动 Kotlin 文件使用缓存的 ktlint 1.8.0 检查，零格式错误；两份资源 XML 解析及 `git diff --check` 通过。独立审查发现的迁移列组防护和无效事件分类问题已补失败测试、修复并复核通过。预览包构建及 production runtime 状态以本轮构建日志和其 `preview-manifest.json` 为准，不由以上测试替代用户实际同步验收。
