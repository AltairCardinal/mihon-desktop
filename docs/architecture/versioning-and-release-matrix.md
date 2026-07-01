# 版本策略与发布矩阵

日期：2026-06-30

## 产品版本

Android、macOS、Windows 应对外使用同一产品语义版本。平台构建元数据可以不同，但必须能追溯到同一源码提交。

推荐格式：

```text
<product-version>+<platform>.<git-sha>
```

示例：

```text
0.20.0+android.abc1234
0.20.0+macos.abc1234
0.20.0+windows.abc1234
```

当前 `app-desktop` 的 `0.STAGE.FEATURE.GIT_HASH` 可在过渡期继续使用，但 Windows 发布前必须与 Android 版本策略对齐。

## 发布矩阵

| 平台 | 构建类型 | 产物 | 发布要求 |
| --- | --- | --- | --- |
| Android | debug | APK | 本地验证、开发调试 |
| Android | release | APK/AAB | 签名、更新器/遥测开关明确 |
| macOS | distributable | `.app` / `.dmg` | 本地部署和 smoke |
| Windows | distributable | EXE/MSI/ZIP 至少一种 | 安装、升级、卸载、用户数据保留 |
| Desktop headless | test mode | 无 UI 进程 | E2E/Robot/smoke |

## 发布前共同检查

- P0 技术债已 `PAID`。
- P1 技术债已 `PAID` 或有 `ACCEPTED` 记录、复查日期和发布影响说明。
- 备份 Android -> Windows -> Android 往返不丢关键字段。
- Crash 日志可发现、可导出、可轮转。
- 扩展加载失败可诊断。
- 自动更新、自动备份、自动库更新均可关闭。
