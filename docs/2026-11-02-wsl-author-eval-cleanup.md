# WSL 作者发现评测工作树清理结果

2026-11-02。承接[旧工作复核](2026-11-02-local-work-recheck.md)与[六处副本清理](2026-11-02-local-six-stash-cleanup.md)。用户明确授权按同样原则处理剩余两处WSL评测树，保护非阻塞配对工作树。

| 目标 | 保全与处置 |
|---|---|
| mihon-pi-deepseek，HEAD b5f4f8c6ad | 原工作区clean；旧候选Git历史、原始日志/XML/报告保留。当前主干已有正式Service执行状态机及生产接线，不恢复旧平行实现。工作树目录/登记已移除，分支保留。 |
| mihon-pi-minimax，HEAD 5b17dd0751 | 原1修改/2新增：Service、Executor及RepositoryTest。working/base/patch与评测证据保留；原HEAD已是main祖先。旧XML14项5失败、后续取消，不能当通过的修复。目录/登记已移除，分支保留。 |

私有归档：[恢复说明](D:/Codex/archives/wsl-author-eval-20261102/README.md)、[逐文件清单](D:/Codex/archives/wsl-author-eval-20261102/manifest.json)、[最终回执](D:/Codex/archives/wsl-author-eval-20261102/completion.json)。207个条目原始约728KB，压缩约241KB。可选ACCEPTED review测试刺激保存为素材，未发现同名测试不等于证明现行产品缺陷。

## 清理与验证边界

- 两处WSL指针已保存原件；只对这两个明确Windows目标执行原生git worktree repair，再正常移除。无force、全局prune、分支删除或手工Git元数据删除。
- 两ZIP逐成员CRC/SHA/大小通过；增量bundle verify、实际unbundle和自有pack核对通过。bundle只有DeepSeek ref，MiniMax旧HEAD从已包含它的main历史恢复；未提交内容另在ZIP/patch。不能把增量包称作独立全仓备份。
- 两个旧物理目录和登记均不存在，原分支HEAD保持。主树产品文件未改；非阻塞配对两树的HEAD/status/diff指纹前后完全相同。
- 当前仅剩3处工作树登记：main与两处在途非阻塞配对树。原两WSL评测副本的清理已完成；后续在途开发和CHIPS独立候选不属于本轮。
- 没有运行Gradle、产品测试、联网或采用旧产品代码；历史失败证据保留，不伪称重新验收通过。只提交本清理记录，不推送。
