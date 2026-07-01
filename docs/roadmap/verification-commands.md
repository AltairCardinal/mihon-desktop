# 验证命令清单

按改动范围选择最小充分验证。无法运行时必须说明原因。

| 范围 | 命令 |
| --- | --- |
| 格式检查 | `./gradlew spotlessCheck` |
| Android debug 构建 | `./gradlew assembleDebug` |
| Android release 构建 | `./gradlew assembleRelease -Pinclude-telemetry -Penable-updater` |
| Android 单元测试 | `./gradlew testReleaseUnitTest` |
| Desktop 单元测试 | `./gradlew :app-desktop:jvmTest` |
| Desktop 指定测试 | `./gradlew :app-desktop:jvmTest --tests "fully.qualified.TestName"` |
| Desktop E2E/Robot | `./gradlew :test-desktop:test` |
| Desktop 冒烟 | `./scripts/desktop-smoke-test.sh` |
| macOS Desktop 构建部署 | `./scripts/build-desktop.sh` |
| Windows 包 | 在 Windows 构建机运行 `./gradlew :app-desktop:packageMsi` 或正式脚本 |

## 当前基线守护测试

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.architecture.DesktopArchitectureGuardTest"
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.backup.DesktopBackupCreatorTest.createFromDatabase preserves manga viewer flags in backup viewer field"
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.backup.DesktopBackupRestorerTest.restore preserves viewer flags from backup viewer field"
```
