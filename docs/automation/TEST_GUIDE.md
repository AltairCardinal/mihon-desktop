# Mihon Desktop 自动化测试指南

## 快速开始

运行桌面 JVM 测试和 Robot 客户端测试：

```bash
./gradlew :app-desktop:jvmTest :test-desktop:test
```

构建并验收桌面应用：

```bash
./scripts/build-desktop.sh
```

Windows 默认先生成未打包应用用于运行验收，再封装完整 ZIP 作为交付物；不会生成 MSI。开发验收 EXE 为：

```text
app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe
```

`app-desktop/tmp/` 是自动化运行使用的临时未打包目录，不能把其中的 EXE 单独作为交付物；
该 launcher 必须与同级 `app/`、`runtime/` 一起存在。Windows 构建成功后会额外生成可搬运的完整 ZIP：

```text
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-windows.zip
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-windows.zip.sha256
```

对外验收和人工安装应使用 ZIP；脚本会在成功前检查压缩包内同时包含 launcher、应用文件和 Java runtime，
并生成 SHA-256。`tmp` 下的未打包目录仍仅供 Test Mode 与运行时自动化使用。

构建脚本会自动启动该 EXE，并确认窗口标题中的运行版本与本轮 `0.STAGE.FEATURE.BUILD.GIT_HASH` 完全一致。只有发布时才显式执行 `./scripts/build-desktop.sh msi`；MSI 不能替代未打包版本的开发验收。

启动测试模式：

```bash
"/Applications/Mihon Desktop.app" \
  --test-mode \
  --test-http-port=8080
```

无界面模式仅适合 HTTP 状态/API 测试：

```bash
"/Applications/Mihon Desktop.app" --test-mode --test-http-port=8080 --headless
```

## 测试分层

| 层级 | 位置 | 目标 |
|---|---|---|
| JVM 单元测试 | `app-desktop/src/test/kotlin` | 业务逻辑、导航契约、HTTP parser、状态模型 |
| 桌面自动化 API | `app-desktop/src/main/kotlin/mihon/desktop/test` | 测试模式 HTTP server 与状态回读 |
| Robot 客户端 | `test-desktop/src/main/kotlin` | 面向场景的测试 DSL |
| Robot 测试 | `test-desktop/src/test/kotlin` | 客户端序列化、Robot API、场景 smoke |

## 编写规则

- 禁止“成功或失败都算通过”的断言。
- HTTP/API 变更必须覆盖成功、空数据、错误状态和 malformed body。
- 导航变更必须验证 Tab 与 Screen 类型，以及 pending 状态不会互相覆盖。
- Reader API 必须验证 UI 状态与 `/test/reader/state` 一致。
- Test Mode 不读取屏幕像素；视觉问题使用不需要系统录屏权限的 Compose 离屏测试或人工检查。

## 常用命令

```bash
# 全部桌面 JVM 测试
./gradlew :app-desktop:jvmTest

# Robot 客户端测试
./gradlew :test-desktop:test

# 指定测试类
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.navigation.TestNavigationControllerTest"

# 冒烟测试脚本
./scripts/desktop-smoke-test.sh
```

## 测试模式 API

基础地址：

```text
http://localhost:8080/test
```

高频端点：

- `GET /health`：测试服务健康检查
- `GET /state`：应用状态
- `POST /navigate/{screen}`：导航
- `POST /action/{action}`：执行动作
- `GET /reader/state`：阅读器状态
- `POST /reader/next_page`：下一页
- `POST /reader/prev_page`：上一页
- `POST /reader/close`：关闭阅读器
- `POST /reset`：重置测试状态

完整字段见 [API_REFERENCE.md](./API_REFERENCE.md)。

## 最终对齐固定 EXE Runner

本轮构建已产出固定的 Windows 未打包应用后，运行：

```bash
./scripts/desktop-final-parity-test.sh
```

Runner 固定使用 `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe`，并要求 `./scripts/build-desktop.sh evidence` 生成的 Task151 provenance sidecar。启动前会复用既有 provenance verifier 同时核对当前已提交 product source identity 和完整未打包应用哈希；EXE、sidecar 缺失或任一身份不匹配都会 fail-closed，不以 mtime 猜测 freshness。有效产物始终以 `--test-mode --headless` 启动；若 `/test/health` 在启动前已响应则拒绝覆盖旧实例，启动后还会同时确认 health 与本次 PID 存活，并在成功、失败或超时时关闭本次启动的精确进程，不打开系统 UI。

`test-desktop` 客户端通过 `MIHON_FINAL_PARITY_SUMMARY_FILE` 写入汇总。Runner 将它与既有 `test-mode-coverage-inventory.json` 对比，逐项输出 family 和 permanent protection，并要求：

```text
Families: 13/13
Permanent protections: 5/5
Capabilities: 64/64 unmapped=0
```

产物错误会给出固定路径和 `evidence` 构建命令；启动错误会区分旧 health 占用、本次进程提前退出与超时，并给出进程或启动日志。provenance、health command 和 test command override 仅用于隔离 runner fixture，正常验收不得用它们替换真实 verifier、固定路径或默认 `test-desktop` client。
