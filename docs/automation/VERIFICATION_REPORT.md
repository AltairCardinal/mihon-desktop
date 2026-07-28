# Desktop Automation Verification

本文件只记录可复现的验证命令。

## 当前验证命令

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.navigation.TestNavigationControllerTest" --tests "mihon.desktop.test.http.TestHttpServerJsonTest"
```

预期：通过。

```bash
./gradlew :app-desktop:jvmTest :test-desktop:test
```

预期：通过。若失败，优先修复测试编译或真实断言失败。

## 屏幕权限策略

- Test Mode 不提供截图 API，也不创建截图目录。
- macOS 自动化不调用系统截图命令。
- Windows 窗口隐私验收的外部截图只作为临时证据，不进入 Mihon bundle 或仓库。
