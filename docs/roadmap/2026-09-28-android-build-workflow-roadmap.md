# Android 构建与验收流程实施

日期：2026-09-28。设计依据：[Android 构建、签名、交付与验收规范](../architecture/android-build-and-acceptance.md)。

active-task: AB-02

## 范围与执行预算

用户已批准执行设计。使用 1 个实施子代理顺序承担 AB-01/02/03，主代理负责接口、独立审查、文档和最终集成；同一工作树重型 Gradle 任务串行。1 轮独立审查按稳定里程碑进行，最多 1 轮修复复审；行为红绿仅运行 focused 验证，最终全量集中 1 次。发布、实体设备安装及 UI 操作不由实施授权自动推导；用户已选择自行验收真机，本次保留该边界。

预计 1–2 小时，主要成本为 Gradle、R8 与隔离环境验收；具体取决于缓存和设备可用性。交付代码、可追溯 Android 候选、更新后的操作规范及一份验收报告。追加全量/审查或扩大范围时按 AGENTS 说明具体原因与成本。

## 执行任务

- [x] **AB-01：默认 fork 身份和版本权威。**
  - 单一公开版本配置；默认 Release 为 fork，Debug 为独立 `.dev`；保留 namespace、原正式密钥和升级身份。
  - 迁移 Gradle 发布约束、签名脚本配置读取、专项隔离 init 前置断言和日常 CI 的不兼容参数；旧 fork init 暂作兼容入口。
  - 真实 AGP 配置/variant 红绿、merged manifest authority/test target、禁止参数负例；不以字符串扫描代替行为测试。
  - 身份与签名接口通过主代理独立检查后，AB-02 才消费。实际 APK/升级验证统一在 AB-04 完成，不提前把配置通过写成运行通过。
- [ ] **AB-02：统一构建、验证和安装入口。**
  - 薄 Python 入口复用协调器、Gradle 和外部签名，提供 check/debug/candidate/verify/install；候选输出 APK、mapping 及紧凑清单。
  - 测试未签名、错误身份/版本/证书、篡改、缺密钥、已占用任务、历史产物保护；无明确 install 动作不写设备。
  - 签名与安装边界独立审查；实际 SDK/APK 验证和模拟设备边界结合，不能只 mock 被验收的 APK parser/签名检查。
- [ ] **AB-03：CI 与文档切换。**
  - 日常 CI 用同一候选路径，未签名产物明确标注；不启用 fork 外部自动发布，不访问生产密钥。
  - 迁完当前调用方再去除旧 fork init；历史验收记录保留原命令并标注历史边界。
  - 操作规范和 AGENTS 指向可用命令；静态/本地验证与远端实际 CI 分开记录。
- [ ] **AB-04：最终候选和升级验证。**
  - 当前源码的 Android/相关共享、Desktop/Test Mode 完整验证和格式检查集中执行，复用仍适用的证据；按实际产品/构建输入变化判断正式平台产物的验证范围。
  - 正式 R8 APK、既有证书签名、配置/哈希核验；适用 API/ABI 的隔离安装、升级和冷启动。实体设备不自动操作。
  - 冻结源码与产物证据，记录已知失败、工具限制及待用户验收项；验证、独立审查、提交完成后勾选相应任务。

## 基线与证据边界

- 起点 `b085025984`，工作区干净；当前正式 Android 安装基线为 aex.14/code32。
- 已有诊断红测见[末页结算诊断](../evidence/android-reader-tail-settlement-2026-09-27.md)：`DualPageProgressProductionWiringTest` 切章场景 expected 4 / actual 2。本任务不改 Reader，不删除/跳过该用例，不将它误报为构建流程引入的回归，也不宣称全量绿色。
- 本计划的勾选只在对应实现、独立审查、验证及提交完成后推进；待设备或实际 CI 的证据不虚构为通过。

## AB-01 验证记录

- 真实 AGP 契约先因旧默认 ID `app.mihon` 失败，迁移后通过；下一候选为 aex.15/code33。
- `scripts/test_android_release_contract.py` 的配置四项及 manifest 一项分组执行通过：默认/兼容入口、三个隔离 ID、三个禁用参数、混用脚本拒绝；实际 Debug/Release manifest authority 及两种 instrumentation targetPackage 正确。
- `:app:spotlessKotlinCheck`、PowerShell 语法检查与 `git diff --check` 通过；签名脚本在读取密钥前拒绝真实旧 code32 APK。
- 主代理独立核对 Gradle 约束、签名元数据、隔离接线和测试，消除契约测试的版本硬编码后通过本里程碑审查。实际新 APK 的签名及运行证据仍归 AB-04。
- 本批涉及 10 个实现/测试文件及本计划：修改集中于同一默认身份迁移，隔离脚本、签名和 CI 参数必须同步适配，未按文件拆开提交。
