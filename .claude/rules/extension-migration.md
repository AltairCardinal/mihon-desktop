# 扩展 JVM 构建迁移计划

## 目标
将 `extensions-desktop` 的 JVM 构建链从 Android AAR 依赖迁移到 Mihon Desktop 可编译的扩展 API 层。

## 核心仓库
- `extensions-desktop`：https://github.com/AltairCardinal/extensions-desktop
- `extensions-source`（上游）：https://github.com/keiyoushi/extensions-source

## 构建产物
- 仓库地址：`https://raw.githubusercontent.com/AltairCardinal/extensions-desktop/repo`
- `index.min.json`：扩展索引
- `apk/*.jar`：扩展 JAR 文件

## 关键文件（extensions-desktop）
| 文件 | 用途 |
|---|---|
| `scripts/patch.sh` | 替换 extensions-source 的 Android 配置为 JVM 版本 |
| `patches/*.gradle.kts` | JVM 版 Gradle 配置 |
| `patches/*.kt` | JVM 版 Android stub/shim |
| `desktop-api/` | JVM 可编译的扩展 API 层 |
| `android-compat/` | Android 兼容性 stub |

## 当前状态
- ✅ CI 基础构建链已打通
- ✅ `patch.sh` 已适配 extensions-source 的 gradle/build-logic 结构
- 🔄 继续补 Android shim 覆盖更多扩展

## 阻塞项分类
1. **Android shim**：缺失的 Android framework helper（需继续补）
2. **第三方 AAR**：图像解码器、JS 引擎等（需替换为 JVM 依赖或 compileOnly）
3. **QuickJS 扩展**：暂列失败清单，评估 GraalJS 替代

## 查看 CI 运行
https://github.com/AltairCardinal/extensions-desktop/actions
