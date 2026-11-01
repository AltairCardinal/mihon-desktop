# research-next 七项未提交内容的语义核对

比较主干固定为 `6b5831c64dd9f09a7d79c93fac9a75a548c740fa`；旧树HEAD为 `68a5575932d9cf9579e21ceebd7c0a5ed71cb5fc`。这是只读源码／历史证据核对，没有运行Gradle或新实验。这里的数学R4候选 **C是later-null取证提示**，不是2026-11-01已采用的Android请求提升C。

| 指定旧项 | 当前main语义与判定 | 归档定位 |
|---|---|---|
| ReaderPairingCertification.kt dirty | **main有等价**：相同D实现已在main。first==1仅opening=true；reserved入口须first>1且两端不forced；原R1 skip-left guard保留。旧dirty与当前文件逐字比较无净差异，不能当未交付新功能 | [完整working](research-next/working/domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPairingCertification.kt.txt)、[旧base](research-next/base/domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPairingCertification.kt.txt)、[dirty patch](research-next/uncommitted.patch)、[当前main](current-main/ReaderPairingCertification.kt.txt) |
| run.py dirty | **仍有研究价值，作为历史测量装置归档**。新增round5从HEAD源码构建before/C/D/CD/working并插query计数，供338状态比较；不是产品运行路径，也不是墙钟基准 | [working runner](research-next/working/scripts/reader-pairing-math/run.py.txt)、[base runner](research-next/base/scripts/reader-pairing-math/run.py.txt) |
| 10/22实现与测量报告 | **历史研究证据归档**。D与main等价；C仍未采用。原报告“已交付”指当时dirty实现，不构成提交身份。报告第2节称3d524基线，但现存round5 manifest的baselineHead为6feecff；保留两者，不静默改原文或将HEAD替代输入hash | [原报告](research-next/working/docs/2026-10-22-reader-pairing-implementation-and-measurement.md.txt)、[实际manifest](proofs/round5/manifest.json)、[result](proofs/round5/result.json) |
| ReaderPairingReachableEntryPruneTest.kt | **main有等价且补了显式失败消息**。当前同类保留forced-neighbour正例和无forced反例，在强转Certified前多一个assertTrue；完整unit／slot／certainty／isCurrent及合法持久record保护仍在 | [旧测试](research-next/working/domain/src/commonTest/kotlin/mihon/domain/reader/ReaderPairingReachableEntryPruneTest.kt.txt)、[当前测试](current-main/ReaderPairingReachableEntryPruneTest.kt.txt) |
| ReaderPairingTestPixels.kt | **main有等价**：旧工作文件与当前测试夹具字节相同。虚构物理色样生成器不是用户原图，不再重复实现 | [原夹具](research-next/working/domain/src/commonTest/kotlin/mihon/domain/reader/ReaderPairingTestPixels.kt.txt) |
| PairingImplementationExperiment.kt | **仍有研究价值，归档候选反例和成本计数**。真实owner／scan／certifier／canonicalPlan在固定338状态执行，量化C与D重叠，不能把338当自然用户分布或CPU采样 | [夹具](research-next/working/scripts/reader-pairing-math/PairingImplementationExperiment.kt.txt)、[计量结果](proofs/round5/result.json) |
| PairingQueryCounters.kt | **研究装置，非缺失产品能力**。只数owner query／hint query／hit，明确由runner注入副本，不能当main生产观测或实际I/O计数 | [原计数器](research-next/working/scripts/reader-pairing-math/PairingQueryCounters.kt.txt) |

## C／D逐项结论

D的结构证明与当前实现一致：页1的opening=false不可达；reservation只有前一step允许配对才可产生，forced任一端禁止，故可删相应入口。仍保留first>1非forced的reserved入口，未通过“少读事实”放宽完整unit一致性。当前main的D专门回归与上述实现共同保留，不能仅因旧树未提交而重复移植。

C仍未进入当前scan：`laterWins`在强度相同后依次读取nearDistance、laterDistance，没有knownLaterNoMatch捷径。历史候选在later已Computed(null)时跳过near，可能跳过near的RetryableFailure。10/22报告保留3条既有契约失败：前两条是失败/缺边保护风险，第3条仅需求集合变化且仍NeedFacts。归档不把它们改成当前main失败，也不将有界338状态的成功宣称全域正确。

原计数：D为48项Need→Certified、owner queries644→404；C单独64项Need→Certified却queries644→878；CD合并仍只新增64，queries590。数字不能相加，也不是毫秒／解码／用户收益。C如重启，先处理“被跳过near不能有可重试失败”的守卫，再以当前已含D的主干为基线，验证真实失败账本、读集失效和查询成本。不能原样开启旧C，更不能把它与新Android C混名。

旧报告所写919项domain全绿、桌面编译错误及13项两失败/一skip均是当时环境记录；本轮没有复验，不是当前6b58通过或失败。外围jar和编译器缓存与其完整等价性未在当时证明，恢复时必须核原manifest依赖，缺失不得拿新jar静默替代。
