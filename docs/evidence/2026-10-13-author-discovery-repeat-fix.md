# Windows 作者发现重复失败修复

## 范围和观察

- 基线：`abb9c69606`；初始工作区仅有用户的未跟踪 `docs/human/`，本次不暂存它。
- 用户运行的是 2026-10-12 的 Windows preview。只读查看本机保存的作者检查任务，确认失败的是手动作者扫描；失败摘要包含详情解析未实现、缺少 `org/json/JSONObject`、网络及登录失败。未修改本机任务、数据库、偏好或扩展。
- production scheduler 把 `Failed` checkpoint 当作每分钟可恢复任务，因此反复执行手动检查；共享 `runBareScan` 未填写源数，形成“7 of 0”。源码与行为测试证实这两条缺陷，不把其他源级错误推断成代理问题。

## 修复与边界

- `Pending/Running` 保留中断恢复；`Failed` 自动检查仅按共享到期查询和源级退避调度，不反复重放失败的手动 checkpoint。重启亦不主动重放失败手动检查，作者页与设置主动重试继续有效。
- 无 watch 或 disabled watch 的手动扫描返回实际完成数、总数、终态和截断信息；不创建 run/checkpoint，不更改关注或通知语义。
- 未解决扩展详情解析、JSON 运行时依赖、图源网络及用户登录问题。本轮不宣称所有源已恢复，也不操作用户当前运行实例。

## TDD 与验证

命令均经 `python scripts/gradle-coordinator.py run --key <key> -- .\gradlew.bat ...` 串行执行。

| 阶段 | key / 范围 | 结果 |
|---|---|---|
| 红 | `author-repeat-red`：`CreatorDiscoverySchedulerTest` | 新增两项因预期 1 次而实际 4 次失败 |
| 红 | `author-count-red`：`CreatorDiscoveryExecutorStateMachineTest.manual archive scan*`、`unwatched manual scan*` | 两项真实 SQLDelight 扫描测试因总数为 0 失败 |
| 绿 | `author-repeat-green`：下述三类 | 54 项通过 |
| 重构复验 | `author-repeat-final`：下述三类 | 54 项通过 |
| 格式 | `author-repeat-format`：`:domain:spotlessKotlinCheck :data:spotlessKotlinCheck`，`spotlessIdeHook` 为本次修改的绝对文件路径 | 两个文件 `IS CLEAN`；hook 检查后 check task 为 SKIPPED |
| 差异 | `git diff --check` | 通过 |

最终 focused 命令：

```powershell
.\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.domain.CreatorDiscoverySchedulerTest --tests mihon.desktop.domain.CreatorGlobalScheduleWiringTest :data:jvmTest --tests tachiyomi.data.creator.CreatorDiscoveryExecutorStateMachineTest
```

对应测试数量：scheduler 19、真实 Desktop DI/calendar wiring 2、共享 executor 与真实 SQLDelight repository 33。日志在本 worktree `.gradle-coordinator/<key>.log`。重构仅复用失败计数以生成裸扫描摘要；没有 schema、凭据、同步格式、导航或 DI 绑定变更。未运行完整模块测试、全量、Android runtime 或原生 Desktop 交互验收；体验构建的 production runtime 检查不能替代这些项目。
