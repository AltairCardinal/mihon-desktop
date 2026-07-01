# Desktop 扩展诊断基线

日期：2026-06-30

## 目标

Windows 发布前，扩展加载失败必须可诊断。当前阶段先建立分类和验证基线，后续 W3 逐步收敛实现。

## 失败分类

| 分类 | 含义 | 用户反馈 | 开发者日志 |
| --- | --- | --- | --- |
| `EXT_LOAD_MANIFEST_MISSING` | APK/JAR 缺少可识别 manifest 或扩展类 | 扩展格式不受支持 | 文件路径、manifest 解析结果 |
| `EXT_LOAD_NO_SERVICE` | ServiceLoader 未发现 Source | 未找到可加载源 | JAR 路径、ServiceLoader provider 列表 |
| `EXT_LOAD_CLASS_ERROR` | 反射或 ClassLoader 加载失败 | 扩展加载失败 | 异常类型、className、ClassLoader 策略 |
| `EXT_LOAD_COMPAT_ERROR` | Android API/stub 或依赖不兼容 | 扩展与桌面端不兼容 | 缺失类、缺失方法、依赖版本 |
| `EXT_LOAD_SOURCE_ERROR` | Source 初始化成功但调用失败 | 源运行失败 | sourceId、调用点、异常 |

## 当前验证入口

```bash
./gradlew :app-desktop:jvmTest --tests "*Extension*"
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionLoaderTest"
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.ExtensionCompatibilityTest"
```

## W3 收敛要求

- 不再用 `catch (_: Throwable)` 静默丢失扩展加载失败。
- 用户可见错误必须非空。
- 开发者日志必须包含扩展文件路径和失败分类。
- 至少一组真实扩展成功/失败样本纳入测试。
