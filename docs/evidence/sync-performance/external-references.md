# 外部资料与使用边界

查阅日期：2026-09-22。以下均为官方资料，补充用户材料没有提供的API能力或测试工具说明。R1–R4中的源码事实仍以用户提供报告为依据；本次未调用用户的GitHub仓库API，未验证真实令牌权限、运行性能或设备状态。

正文中的[E1]–[E7]对应以下条目。API字段、版本和权限应在P0/P7/P10使用实际应用配置核验；文档公开能力不能代替生产适配验收。16MiB/64MiB缓存、2MiB请求、4MiB预取、性能阈值等均为本轮设计参数，未由这些来源给出。

## E1：GitHub Git trees

官方页面：[REST API endpoints for Git trees](https://docs.github.com/en/rest/git/trees)。

支持本文的事实：创建tree可对条目使用`content`或`sha`，二者互斥；`base_tree`用于保留既有树内容。递归tree截断时可逐层读取子树；非递归请求必须省略`recursive`参数，设置为`false`仍会启用递归。

设计使用：REST单批内联文件内容、固定OID分层读取与子树复用。UTF-8往返验证、应用容量预算、scope/guard检查属于本方案的额外要求。官方示例的API版本头不作为自动修改现有项目版本的依据。

## E2：GitHub Git blobs

官方页面：[REST API endpoints for Git blobs](https://docs.github.com/en/rest/git/blobs)。

支持本文的事实：默认JSON响应的`content`为Base64；`application/vnd.github.raw+json`返回raw blob数据。创建blob支持UTF-8与Base64编码。

设计使用：比较raw与JSON表示，向协议层交付相同原始存储字节。raw不会自动消除应用加密封装内部的Base64。实际传输字节、HTTP压缩、客户端副本和速度由测试计量，不承诺固定百分比收益。

## E3：GitHub限流与REST最佳实践

官方页面：[Rate limits for the REST API](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)；[Best practices for using the REST API](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)。

支持本文的事实：限流可能返回403或429，应结合响应内容、`Retry-After`、remaining/reset等信息判断。次级限流无等待提示时至少等待一分钟；反复失败须退避并有停止条件。官方建议串行请求，大量修改请求之间至少间隔一秒。私有资源404也可能涉及授权，不能一概理解为对象不存在。

设计使用：共享request gate、持久notBefore、错误分类、测试流量预算。四次网络失败及10/30/120秒来自R2，非GitHub规则；1500次API/100次修改请求为本轮真实测试保护预算，非服务商额度。应用无法据此精确推断所有未公开次级限制。

## E4：GitHub GraphQL提交

官方页面：[Commits](https://docs.github.com/en/graphql/reference/commits)；[A simpler API for authoring commits](https://github.blog/changelog/2021-09-13-a-simpler-api-for-authoring-commits/)。

支持本文的事实：`createCommitOnBranch`支持提交文件变更并更新目标分支；输入包含`expectedHeadOid`。

设计使用：单批文件集合一次mutation的受控候选。当前应用认证方式、请求大小、保护分支、错误分类与响应未知恢复必须额外验证；默认关闭，fixture通过不等于真实启用通过。不能把请求关联字段`clientMutationId`当作本方案可靠的幂等依据。

## E5：GitHub Git references

官方页面：[REST API endpoints for Git references](https://docs.github.com/en/rest/git/refs)。

支持本文的事实：更新reference时，`force=false`限制为快进更新。

设计使用：保留非强制更新和并发提交恢复；不将快进约束解释成精确expected-head比较。安全回滚检测、actor前驱和响应未知对账仍由原同步规则及本轮设计负责。

## E6：SQLite查询计划

官方页面：[EXPLAIN QUERY PLAN](https://www.sqlite.org/eqp.html)。

支持本文的事实：查询计划能够显示访问方式和索引使用；其输出格式可能随SQLite版本改变。

设计使用：为实际热点保存计划与输入规模，以行为、查询数量和实际性能验证优化，不对Android/JVM不同引擎的完整输出字符串强求一致。当前报告未提供实际执行计划，本文件不判定某条查询已经发生全表扫描。

## E7：Android WorkManager集成测试

官方页面：[Integration testing with WorkManager](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing)。

支持本文的事实：WorkManager提供集成测试工具，可通过测试驱动控制执行条件。

设计使用：恢复时点、提前唤醒、constraints与唯一执行权的生产wiring测试。工具不能证明特定真机锁屏、厂商省电策略或正式包的实际恢复体验；对应证据单独验收。

## 实施者核验清单

记录实际API版本头、Accept、认证类型、权限与分支保护；保存脱敏真实响应fixture；确认文档与固定API契约匹配。若来源发生变化，记录核验日期与差异，通过设计变更流程处理，不静默改协议、阈值或权限范围。
