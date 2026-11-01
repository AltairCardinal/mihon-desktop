# 旧工作树代码与语义归档

范围仅两处旧工作树的指定7项研究dirty、4项Desktop测试diff及1份报告diff。当前比较主干固定 `6b5831c64dd9f09a7d79c93fac9a75a548c740fa`。没有删除／修改旧树，没有归档用户无关AppVersion，没有产品变更或新实验。

- [research-next七项逐项核对与C/D](research-next-dirty.md)
- [R1–R5理论／候选判定与恢复边界](rounds-r1-r5.md)
- [Desktop四测试与旧C1交接](desktop-test-maintenance.md)
- [来源与SHA](provenance.json)、[本子目录完整性清单](manifest.json)

判定摘要：R1 forced skip-left、R2 forced-target isolated、R4 D及像素夹具在当前main有等价实现；D回归还增加了明确Certified断言。五态摘要树、R3 A/B、R4 C、R5 E保存研究价值但不直接启用；它们有完整事实限制、失败语义反例、公开负值callback边界、未采用R4-CD前提或额外查询成本。旧四测试维护不是四项遗失产品功能，当前语义与fixture已演进；两条旧reports序列没有新执行证据，保留为历史观察点。

所有working原件均以`.txt`保字节，另存tracked dirty diff、必要base及当前main对照，不靠旧HEAD永远可达来保存未提交内容。理论／研究源与实际执行源同样快照；原报告的旧相对链接／绝对路径保留为原文，不是本目录可直接运行指令。总历史索引负责Git对象及原始包，本子目录清单只覆盖自身，不修改全局索引。

复原单个旧dirty patch时，应在独立旧base文件副本先check；不可直接覆盖当前main，更不可把多个研究候选默认叠加。这里的baseline-3d524、round5 instrumented-before、confluence R4-CD+hook是三个不同来源闭包，不能混名或互换。原报告曾记大范围测试／构建通过或失败，本轮均不继承为当前状态。

本轮验证限定原件SHA、JSON可解析、档案链接、patch在归档base副本的适用性及指定旧树工作文件未变；不运行Gradle／新benchmark／全模块。源码快照采用`.kt.txt`和`.py.txt`避免进入构建；局部属性仅为保真原件允许历史EOF与unified-diff空白context，正常说明Markdown仍按默认空白规则。
