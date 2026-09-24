# 本次交付与验证报告

日期：2026-09-24。规范版本：1.0.0。详细时间与工具版本见 `report.json`。

## 结论

已编写完整规范及原生样例源码，已执行模型、包结构与安装器安全测试；没有生成 APK，没有运行 Android 仪器测试，没有获得原生截图或生产端到端证据，也没有向远程 GitHub 写入变更。

| 层级 | 结果 | 本次证据与边界 |
|---|---|---|
| 规范库存 | 已编写 | 六组32类别、128条要求、128个声明场景；包含适用条件、例外、参考与验收步骤。 |
| 原生样例源码 | AUTHORED | 32类别入口、每类4个预置场景、英文/简体中文资源；Kotlin/Compose 宿主集成尚未编译。 |
| Kotlin JVM 假模型 | **180 PASS / 0 FAIL** | `model-results.json`、`model-run.log`、`model-compile.log`；128个fixture保存恢复断言与52个模型/行为断言。 |
| 包结构检查 | **27 PASS / 0 FAIL** | `structure-results.json`；不证明 Kotlin 编译或发布包隔离。 |
| 导入工具安全测试 | **8 PASS / 0 FAIL** | `installer-results.json`、`installer-run.log`；临时合成Git仓库，未向真实Mihon工作树执行导入。 |
| Android 构建 | **BLOCKED** | 环境没有Android SDK、adb与已下载的项目Gradle Wrapper；仓库clone因DNS解析失败。 |
| 原生仪器测试 | **NOT_RUN** | 已编写10个测试方法；未编译、未执行。 |
| 全部128项原生场景 | **NOT_RUN** | 模型测试不会改写这些case的原生状态。 |
| 原生视觉/手势/无障碍 | **NOT_RUN** | 没有设备、截图或已批准视觉基准。 |
| 真实业务与发布隔离 | **NOT_RUN** | 假数据不等同真实数据流；未构建最终发布包。 |

## 本次运行的命令

```sh
python tools/run_model_checks.py
python tools/validate_package.py
python verification/test_installer.py
```

以上三条均实际执行，退出码为0。模型文件使用Kotlin编译后在JVM执行；原生Compose文件未被该步骤编译。

## 适配目标

行为参考：`mihonapp/mihon@f52d890e7f8a3c418ddab41f41d4b577bce0dc06`。
源码适配：`AltairCardinal/mihon@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。

远程Android fork的旧版图标/不可变集合接口已单独核对。本机可能有未推送代码；导入工具会按HEAD、组件、目标冲突等条件预检，不能替代真实工作树API审查。

## 模型检查实际覆盖

包括选择优先于搜索的返回、嵌套弹层返回、草稿关闭保护、取消编辑不提交、空/重复/未变化名称不可提交、删除范围、稳定ID与范围选择、仅下载筛选锁定、重复任务启动防护、暂停恢复保留进度、无权限/未信任/未选目录时拦截、页面边界限制、隐私模式不增加假历史、假队列与序列化恢复。

这些断言测试本包 reducer。真实Compose事件是否正确连接、系统返回是否按预期分发以及真实应用是否产生副作用，仍要跑原生/生产用例。

## 已执行的安装器测试

默认dry-run不写入；未知HEAD默认阻止；追加保留现有AGENTS/manifest；重复导入幂等；目标文件内容冲突阻止所有写入；预检后的并发修改被阻止；注入写失败后回滚已写文件；拒绝符号链接越界目标。

## 必须保留的待验证项

首次Android构建可能发现API、依赖、资源、格式或测试语义问题，需要在目标分支修复。所有环境矩阵和真实服务测试按照 `docs/ui/verification-plan.md` 执行。阅读器、扩展、追踪和存储等专项提供的是契约仿真；不要把样例替换成真实账号/数据库后直接执行破坏性测试。

构建后运行系统返回/IME、横竖屏、平板、深浅主题、大字体、RTL、TalkBack、进程恢复等环境检查。对样例与对应原版页面分别取证，再审批视觉基准。最终构建产物还需要验证不包含实验入口和调试代码。
