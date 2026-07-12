# Windows 构建产物与版本规则设计

## 目标

将 Windows 未打包 EXE 设为唯一的开发验收产物，并让每次非测试构建都有唯一且可见的版本号，包括同一个 Git 提交在提交前发生的多次构建。

## 版本格式

应用版本格式为：

```text
0.STAGE.FEATURE.BUILD.GIT_HASH
```

示例：

```text
0.11.14.3.1dd3e83
```

- `STAGE`：开发阶段号。
- `FEATURE`：当前阶段内的功能批次号。
- `BUILD`：当前阶段和功能批次内，每次非测试构建的序号。
- `GIT_HASH`：标识源码提交，不再承担区分每次构建的职责。

`STAGE`、`FEATURE` 和 `BUILD` 保存在 `AppVersion.kt` 中。该文件仍是受版本控制的应用版本字段唯一来源；`BuildInfo.kt` 继续在构建时提供 Git hash。

### 计数规则

- 默认非测试构建：`BUILD` 加 1。
- `feature` 构建：`FEATURE` 加 1，`BUILD` 重置为 `1`。
- `stage` 构建：`STAGE` 加 1，`FEATURE` 重置为 `0`，`BUILD` 重置为 `1`。
- `test-only` 和 `full-tests`：不修改任何版本字段。
- 构建失败后不回退已经分配的 `BUILD`，确保日志和失败产物仍有唯一标识。

Windows MSI 原生包版本使用 `STAGE.FEATURE.BUILD`，例如 `11.14.3`。应用版本开头的 `0` 和 Git hash 不写入 MSI 包版本，因为 MSI 只接受三个数字段。

## Windows 产物契约

唯一的开发验收 EXE 固定为：

```text
app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe
```

统一构建脚本的默认模式 `hash`、`feature` 和 `stage` 在 Windows 上必须以执行 `createDistributable` 结束，并保证上述未打包 EXE 属于本次构建。

MSI 改为显式构建模式：

```bash
./scripts/build-desktop.sh msi
```

Compose 的原生打包任务共用并可能清理 `tmp/mihon-dist/main`。因此 MSI 模式必须先生成 MSI，最后再执行 `createDistributable`。这样可以保证固定路径下的未打包 EXE 不会被 MSI 构建删除，并且与 MSI 使用同一个应用版本。

## 构建流程

普通 Windows 构建流程：

1. 按当前模式分配新版本号。
2. 运行桌面 JVM 测试。
3. 使用 `createDistributable` 生成未打包应用。
4. 检查固定路径下的 EXE 存在，并确认它是在本轮构建中生成的。
5. 启动该 EXE，通过可见窗口标题或测试接口验证运行版本与预期完整版本完全一致。
6. 报告完整版本号和 EXE 绝对路径。

MSI 构建流程：

1. 分配一个新的 `BUILD`。
2. 运行测试。
3. 生成 MSI。
4. 在 MSI 之后重新执行 `createDistributable`。
5. 对固定路径下的 EXE 执行与普通构建相同的存在性和运行版本检查。
6. 同时报告 EXE 和 MSI 路径。

## 脚本接口

`scripts/build-desktop.sh` 继续作为跨平台统一入口，并负责分配版本号。支持以下模式：

- 无参数或 `hash`：`BUILD` 加 1，然后构建固定路径下的未打包应用。
- `feature`：`FEATURE` 加 1，`BUILD` 设为 `1`，然后构建。
- `stage`：`STAGE` 加 1，`FEATURE` 设为 `0`，`BUILD` 设为 `1`，然后构建。
- `msi`：`BUILD` 加 1，先生成 MSI，再重新生成并验证未打包应用。
- `test-only`：运行常规桌面测试，不修改版本号。
- `full-tests`：运行包含集成测试的桌面测试，不修改版本号。

`scripts/build-windows.ps1` 接收统一入口已经分配的预期完整版本号；如果被直接调用，则从 `AppVersion.kt` 计算版本。直接执行 PowerShell 脚本进行非测试构建时，也必须递增 `BUILD`，除非调用方明确标记版本已经由统一入口分配。

## 文档约束

`AGENTS.md` 必须明确：

- Windows 开发验收只能使用未打包 EXE。
- MSI 是发布产物，不能替代未打包版本的开发验收。
- 每次非测试构建必须递增 `BUILD`。
- 固定路径的 EXE 不存在或运行版本不匹配时，不得报告完成。
- 完成报告必须包含完整版本号和固定路径 EXE 的绝对路径。

## 失败处理

- `createDistributable` 完成后 EXE 不存在：构建失败。
- EXE 修改时间早于本轮构建开始时间：构建失败。
- 运行版本与预期版本不一致：终止本轮验证实例，构建失败，并报告预期版本和实际版本。
- MSI 构建失败：立即失败，不得使用旧产物宣称开发构建有效。
- MSI 已生成但后续未打包版本重新生成失败：可以说明 MSI 已生成，但整体命令必须失败，因为开发验收产物无效。

## 测试要求

- 脚本契约测试覆盖默认、`feature`、`stage`、`msi` 和 `test-only` 的版本变化。
- 脚本契约测试保证 `packageMsi` 不能成为 Windows 构建最后一个产物任务。
- 脚本契约测试保证固定 EXE 路径和运行版本检查不会被删除。
- 应用版本测试保证 `BUILD` 位于 `FEATURE` 与 `GIT_HASH` 之间。
- Windows 构建集成测试运行统一脚本，并确认固定路径 EXE 存在且版本正确。

## 边界

- 本次修改不改变 Android 应用版本规则。
- 构建脚本不会安装 MSI，也不会覆盖系统中已安装的 Mihon Desktop。
- MSI 成功不能作为未打包应用已经更新的证据。
- 不修改或回滚工作区中与本任务无关的已有改动。
