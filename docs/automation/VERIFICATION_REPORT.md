# Desktop Automation Verification

本文件只记录可复现的验证命令，不保存一次性截图结论。

## 当前验证命令

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.navigation.TestNavigationControllerTest" --tests "mihon.desktop.test.http.TestHttpServerJsonTest"
```

预期：通过。

```bash
./gradlew :app-desktop:jvmTest :test-desktop:test
```

预期：通过。若失败，优先修复测试编译或真实断言失败。

## 验证资产策略

- 本地截图目录：`/tmp/mihon-screens`
- CI 截图：上传为 workflow artifact
- 仓库中不保存 PNG/JPG 截图
