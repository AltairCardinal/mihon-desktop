# Desktop Test Coverage Report

## 最终覆盖结论

最终 parity manifest 共 64 项，63 项 `VERIFIED`、1 项有用户批准与平台证据的 `EXEMPT`，
没有非终态 capability。覆盖证据必须执行 production implementation/wiring；源码字符串扫描、
测试内复制实现或仅验证 manifest 自身均不算完成证据。

| 覆盖层 | 最终边界 | 结果 |
|---|---|---:|
| 固定原版 provenance | 64 项 path/symbol/line/blob 与 fixture 来源 | PASS |
| Shared/Android/Desktop consumer | shared contract、当前 Android consumer、Desktop adapter/wiring | PASS |
| UI wiring | Screen/Tab、Voyager、DI、HTTP、数据库、后台任务 | PASS |
| Test Mode 场景 | 13 个场景族 | 13/13 |
| Desktop 永久保护 | Authors、Upcoming、双页、自动滚动、APK-to-JAR | 5/5 |
| Capability runtime 映射 | coverage inventory | 64/64，unmapped=0 |
| Final closure | `:app-desktop:finalParityAudit` | PASS |

## 最终执行结果

| 平台/任务 | 通过 | 失败 | 跳过 |
|---|---:|---:|---:|
| Android `testReleaseUnitTest` | 231 | 0 | 0 |
| Windows Desktop `jvmTest` | 2,293 | 0 | 3 |
| `test-desktop:test` | 27 | 0 | 0 |
| Windows Desktop smoke | 92 | 0 | 0 |
| macOS build-script Desktop `jvmTest` | 2,294 | 0 | 1 |
| Windows final runtime families/protections | 18 | 0 | 0 |
| macOS final runtime families/protections | 18 | 0 | 0 |

Windows 三个跳过项为两个缺少本机 live extension JAR 的 compatibility case 与一个
macOS-only JXA native-share case。macOS 唯一跳过项为 Windows-only unsigned installer
verifier。macOS 首次冷构建有一个 Compose callback 5 秒调度超时；精确 case 与随后完整
2,296 项复跑均通过，因此最终失败数为 0，且只有全绿复跑进入 artifact provenance。

## 场景族

最终 inventory 覆盖：

- library、manga detail；
- browse/global search/source login、extensions；
- reader、downloads、updates/upcoming、history；
- migration、backup/restore；
- settings/platform、tracking、about。

每个场景族都映射真实 controller/ScreenModel/use case/adapter，并保留 loading、empty、typed
failure、stale/unavailable、partial failure、cancel/close 等适用边界。永久保护不冒充普通
capability mapping。

## 真实剩余边界

- ID 85 Widget 为唯一平台 `EXEMPT`，不是测试缺口。
- live extension compatibility 仍取决于本机是否存在真实扩展 JAR；固定 APK fixture 与
  consumer-driven compat inventory 已提供 repository-local 保护。
- Test Mode 的 AWT Robot 截图服务和 `/test/screenshot` 已移除；旧端点由集成测试锁定为 404，
  旧 `--screenshot-dir` 参数被忽略。macOS 验收不再调用系统截图命令，普通运行和 Test Mode
  均不要求屏幕录制权限。
- Linux、正式签名、公证和 release handoff 不在当前产品/仓库验收边界。

## 合入门槛

```bash
./gradlew spotlessCheck
./gradlew testReleaseUnitTest
./gradlew :app-desktop:jvmTest
./gradlew :test-desktop:test
./gradlew :app-desktop:finalParityAudit
```

Desktop 迭代使用 `scripts/build-desktop.sh`；最终运行验收使用
`scripts/desktop-final-parity-test.sh`。详细版本、产物、哈希与环境证据见
逐项状态与验证证据以 `app-desktop/src/test/resources/parity/parity-manifest.json` 为准。
