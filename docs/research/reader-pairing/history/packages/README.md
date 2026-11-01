# 原始研究包归档

本目录保存本轮明确指定的九个原始ZIP；原包字节不改，旁边提取Markdown报告为 .md.txt，避免把文档误当作可执行代码。ZIP中的Python、shell及其他脚本只作为原始研究材料保存，没有执行。

完整逐包SHA-256、字节数、ZIP entry名/长度/CRC、文档提取映射和结构化问题/候选矩阵见 [package-inventory.json](../package-inventory.json)。所有提取文档带 .md.txt 后缀；每个ZIP原包保留全部代码、数据、报告和原目录结构。

## 版本关系

1. ordered-system-research.zip 是有序对象部分信息认证初始研究包；C1–C14候选表、H-A…H-G历史问题、E/N实验与负例见其提取报告。
2. ordered-system-engineering-results.zip 补充工程模型和本机低成本结果，保留为独立修订包。
3. ordered-system-r8-research-results.zip 包含48个主课题（A1–H6）、12个组合挑战（X1–X12）和7个新假设（NH-1…NH-7）。coverage矩阵67行：44项verified、15项conditional、4项refuted、4项limit-proved。报告明确指出没有真实IO/端到端时延，多个方向仍待裁定或待宿主验证。
4. ordered-system-r9-research-results.zip 是R9早期版：120题（A01–T06）、30个组合、30个新假设。其原始coverage保留较早状态。
5. R9有两个独立后续包：ordered-system-r9-final-results.zip 与 ordered-system-r9-fix-attachment9.zip。收尾包的 REPORT.md 声明为当前有效结论，并将修复前报告保留在包内；附件9保留为修复来源。两包有535个同路径entry，其中509项CRC/长度相同、26项不同；收尾包多43个路径，附件9没有独有路径。收尾版结论为45个机制（D4 3、D3 31、D2 11）；附件报告中的较早统计不同。两份原始包都保留，历史结论不混写。
6. R10有明确递进包：ordered-latency-r10-results.zip 初版、ordered-latency-r10-revision-results.zip 一次修订、ordered-latency-r10-final-results.zip 收口版。各自coverage覆盖Q001–Q120和C01–C30；初版有22项incomplete，修订和最终版均为150/150 completed。final报告仍明确产品提速未验证、原生Windows NOT_RUN。

## 归档边界

单独提交的 RESEARCH_REPORT.md（不在上述九个ZIP内）按此前决定排除，不将其当作权威报告；ZIP内部同名报告保留。原ZIP内容可能有彼此矛盾的早期结论，需按版本和包内修订说明阅读，不能把草案状态挪用为最终结论。这个归档仅索引研究范围、原结论、反例和数据路径，不判断任何候选是否已进入当前main。

## 完整性

归档了 9 个原包，压缩原件合计 9,174,095 bytes；ZIP entry共 2,346 项；额外提取 205 份 .md.txt 文档。逐包CRC检查均通过；每个副本SHA-256与输入一致。