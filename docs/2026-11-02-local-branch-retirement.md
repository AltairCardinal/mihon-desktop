# 旧本地分支退役结果

日期：2026-11-02；清理前main `ad2d28eb9aa012822b673323169442b52ebf70b6`。承接[逐项复核](2026-11-02-local-work-recheck.md)、[旧副本与stash清理](2026-11-02-local-six-stash-cleanup.md)及[两WSL树清理](2026-11-02-wsl-author-eval-cleanup.md)。

## 实际结果

- 本地heads从76减至3：main和两条在途非阻塞配对分支。48条旧分支是main祖先，另25条保留独有候选/实验/文档历史，均已有精确归档。
- 73个旧HEAD先创建为`refs/archive/local-branches/20261102/<原分支名>`，再按预期旧SHA原子删除原heads；所有恢复引用均核对匹配。没有删除这些历史对象，也不依赖reflog过期策略。
- 25个非祖先HEAD另在13,940,128字节增量bundle中，verify、实际unbundle及自有pack核验通过。48个main祖先从固定main历史恢复；增量包不是独立全仓备份。
- main、两处nonblocking工作树仍在。两个在途分支HEAD保持，期间integration既有dirty内容有并行进展，状态条目集合未变；本任务未修改、回退或锁停在途树。远端引用、tags与Git配置均未改动。

## 恢复与有价值候选

私有归档：[恢复说明及候选索引](D:/Codex/archives/local-branches-20261102/README.md)、[73条完整映射](D:/Codex/archives/local-branches-20261102/plan.json)、[最终回执](D:/Codex/archives/local-branches-20261102/completion.json)。原仓库可用`git branch <新分支名> <archiveRef>`恢复，先查固定映射，不覆盖当前main/在途分支。

早先工作树归档说明中的“原分支仍在”描述其完成时状态；这些普通heads现在由上述archive引用承接。恢复源码改用精确archiveRef，旧ZIP/patch的固定HEAD及文件哈希不因此变化，原归档无需重打包。

明确保留：CHIPS `5758e44bd2`独立候选；app-lock测试兼容素材`cc262f62f8`；stage-A/暗底/02fbb无模型离线图像工具；R10及R1–R5暂缓/未知研究；不完全patch等价的title/feedback/history旧候选和原始审计文档。归档不等于全部已实现、无价值或被永久否定。现有主干已覆盖的搜索/书架/反馈/Android后继不重复移植，过期版号不重放。

本轮仅检查引用/补丁关系、历史处置、归档完整性和恢复关系，不运行产品测试、构建或新性能实验，不推送或发布。旧未提交/ignored材料由前述工作树归档保存，不把Git分支包扩称完整工作区备份。
