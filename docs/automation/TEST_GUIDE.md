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

Windows 默认构建产物是未打包应用，不会生成 MSI。唯一的开发验收 EXE 为：

```text
app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe
```

构建脚本会自动启动该 EXE，并确认窗口标题中的运行版本与本轮 `0.STAGE.FEATURE.BUILD.GIT_HASH` 完全一致。只有发布时才显式执行 `./scripts/build-desktop.sh msi`；MSI 不能替代未打包版本的开发验收。

启动测试模式：

```bash
"/Applications/Mihon Desktop.app" \
  --test-mode \
  --test-http-port=8080 \
  --screenshot-dir=/tmp/mihon-screens
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
- 截图只作为本地调试产物，不提交到仓库。

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
