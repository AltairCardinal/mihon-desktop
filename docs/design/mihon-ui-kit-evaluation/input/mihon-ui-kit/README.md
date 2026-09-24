# Mihon Agent UI 规范与原生验证目录

包含完整规范条款、按类别的 Android Compose 样例源码、假状态模型、待执行仪器测试和安全导入工具。Android APK 尚未构建；这些源码不能直接当 APK 安装，也没有写入你的远程 GitHub 仓库。

## 已交付

- `docs/ui/`：六组32类、128条规则、128项初始验收场景，Agent 路由、页面契约模板、组件基线、环境/业务验证矩阵、顺序路线图。
- `AGENTS.ui.md`：可追加到现有 AGENTS.md 的强制流程；不替换原有指令。
- `overlay/app/src/debug/`：32个类别入口，每个4个预置状态；原生 AppBar/SearchToolbar/Scaffold/Preference/AdaptiveSheet 等，独立内存假数据，第二个 debug 桌面入口。
- `overlay/app/src/androidTestDebug/`：10个 Compose/Espresso 仪器测试，尚未编译或执行。
- `verification/`：已执行本地检查的日志/结果，以及不可替代的 Android 验证边界。
- `tools/`：默认预检的导入脚本、模型测试和结构检查脚本。

## 目标与限制

行为参考使用 2026-09-22 原版 Mihon；代码适配你已读取的 `AltairCardinal/mihon` Android main@6fbf6df。没有取得本机工作树。不要将这包直接用于 mihon-desktop，也不要把新版上游的图标/集合 API 与旧 fork 混用。

实验数据由本包 reducer/内存 Preference 产生，不连接真实书库、下载、安装器、追踪或文件系统。宿主 Application 初始化仍会执行，请在无真实数据的全新 debug 安装中运行。业务专项是契约仿真，需要真实集成用例另行验证。

## 本地导入

需要 Python 3.10+、Git，以及目标 Android 仓库。先进入你自己的仓库建立独立工作分支，再运行以下命令；把 `/path/to/mihon` 换成真实 Android 仓库目录，Windows 也可传完整路径。

```sh
# 在本压缩包解压目录执行：默认仅显示计划，不写文件。
python tools/install_into_repo.py /path/to/mihon

# 审查计划后才应用。
python tools/install_into_repo.py /path/to/mihon --apply
```

HEAD 与已检查基线不同会默认阻止导入。只有检查组件差异后才能加 `--allow-different-base`。该选项不修复 API 差异。目标已存在内容不同的样例/规范会拒绝覆盖；已有 AGENTS 与 debug manifest 以追加/合并方式保留；Gradle 仅追加测试依赖块。工具不提交、不推送、不下载，也不自动解决 Git 合并冲突。

安装器进行写前检查并尝试回滚当前调用的失败写入；不承诺在崩溃或其他进程并发修改时提供数据库级事务性。先用独立工作树和 Git diff 检查变更。

## 构建和执行

在导入后的 Android 仓库中：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

任务名如因 flavor/构建脚本变化而不同，先 `./gradlew :app:tasks --all` 查真实名称。Windows 原生 shell 使用 `gradlew.bat`。安装 debug APK 后，启动新桌面入口“UI 验证目录 / UI Catalog”。依 UI-01 到 UI-32 遍历，每页切换场景，按 expected 执行；信息图标可隐藏测试面板。设备环境需真实改变，不能由场景预置代替。

详细执行方案：[顺序路线图](docs/ui/sequential-roadmap.md)、[验证计划](docs/ui/verification-plan.md)。统一规范入口：[docs/ui/README.md](docs/ui/README.md)。

## 本包可以立即重跑的检查

```sh
python tools/validate_package.py
python tools/run_model_checks.py
python verification/test_installer.py
```

模型脚本需要 `kotlinc` 和 Java。测试输出分别注明层级，不能把本地模型和结构结果计作 Android UI 通过。查看 [本次验证报告](verification/report.md)。
